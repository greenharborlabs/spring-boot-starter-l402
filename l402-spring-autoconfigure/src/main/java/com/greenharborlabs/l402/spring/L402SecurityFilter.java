package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.FileBasedRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.InMemoryRootKeyStore;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import com.greenharborlabs.l402.core.protocol.L402Exception;
import com.greenharborlabs.l402.core.protocol.L402Validator;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Servlet filter that enforces L402 payment authentication on endpoints
 * registered in the {@link L402EndpointRegistry}.
 *
 * <p>Flow per request:
 * <ol>
 *   <li>Match request against registry; if no match, pass through</li>
 *   <li>Check Lightning backend health; if down, return 503</li>
 *   <li>Parse Authorization header; if absent/malformed, create invoice and return 402 challenge</li>
 *   <li>Validate credential; on success add headers and pass through; on failure return error</li>
 * </ol>
 */
public class L402SecurityFilter implements Filter {

    private static final System.Logger log = System.getLogger(L402SecurityFilter.class.getName());

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String L402_PREFIX = "L402 ";
    private static final String LSAT_PREFIX = "LSAT ";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final L402EndpointRegistry registry;
    private final LightningBackend lightningBackend;
    private final RootKeyStore rootKeyStore;
    private final L402Validator validator;
    private final ApplicationContext applicationContext;
    private volatile L402Metrics metrics;

    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              CredentialStore credentialStore,
                              List<CaveatVerifier> caveatVerifiers,
                              String serviceName) {
        this(registry, lightningBackend, rootKeyStore, credentialStore,
                caveatVerifiers, serviceName, null);
    }

    public L402SecurityFilter(L402EndpointRegistry registry,
                              LightningBackend lightningBackend,
                              RootKeyStore rootKeyStore,
                              CredentialStore credentialStore,
                              List<CaveatVerifier> caveatVerifiers,
                              String serviceName,
                              ApplicationContext applicationContext) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.lightningBackend = Objects.requireNonNull(lightningBackend, "lightningBackend must not be null");
        this.rootKeyStore = Objects.requireNonNull(rootKeyStore, "rootKeyStore must not be null");
        Objects.requireNonNull(credentialStore, "credentialStore must not be null");
        Objects.requireNonNull(caveatVerifiers, "caveatVerifiers must not be null");
        String resolvedServiceName = (serviceName == null || serviceName.isBlank()) ? "default" : serviceName;
        this.validator = new L402Validator(rootKeyStore, credentialStore, caveatVerifiers, resolvedServiceName);
        this.applicationContext = applicationContext;
    }

    /**
     * Sets the optional metrics recorder. When non-null, the filter will
     * record Micrometer counters at each decision point (challenge, pass, reject).
     */
    public void setMetrics(L402Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String method = httpRequest.getMethod();
        String path = httpRequest.getRequestURI();

        // 1. Check if this endpoint is protected
        L402EndpointConfig config = registry.findConfig(method, path);
        if (config == null) {
            chain.doFilter(request, response);
            return;
        }

        // 2. Check Lightning backend health
        if (!lightningBackend.isHealthy()) {
            log.log(System.Logger.Level.WARNING, "Lightning backend health check failed for {0} {1}", method, path);
            writeLightningUnavailableResponse(httpResponse);
            return;
        }

        // 3. Check Authorization header
        String authHeader = httpRequest.getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || authHeader.isEmpty()
                || (!authHeader.startsWith(L402_PREFIX) && !authHeader.startsWith(LSAT_PREFIX))) {
            try {
                writePaymentRequiredResponse(httpRequest, httpResponse, config);
                recordChallenge(path);
            } catch (Exception e) {
                log.log(System.Logger.Level.WARNING, "Failed to create invoice for {0} {1}: {2}", method, path, e.getMessage());
                if (!httpResponse.isCommitted()) {
                    writeLightningUnavailableResponse(httpResponse);
                }
            }
            return;
        }

        // 4. Try to parse and validate credential
        try {
            L402Credential credential = validator.validate(authHeader);

            // Success: add headers and pass through
            log.log(System.Logger.Level.DEBUG, "L402 credential validated successfully, tokenId={0}", credential.tokenId());
            httpResponse.setHeader("X-L402-Token-Id", credential.tokenId());
            httpResponse.setHeader("X-L402-Credential-Expires",
                    Instant.now().plus(config.timeoutSeconds(), ChronoUnit.SECONDS).toString());

            chain.doFilter(request, response);
            recordPassed(path, config.priceSats());

        } catch (L402Exception e) {
            ErrorCode errorCode = e.getErrorCode();
            log.log(System.Logger.Level.WARNING, "L402 validation failed, errorCode={0}, tokenId={1}", errorCode, e.getTokenId());
            if (errorCode == ErrorCode.MALFORMED_HEADER) {
                // Malformed L402 header: issue a new challenge
                try {
                    writePaymentRequiredResponse(httpRequest, httpResponse, config);
                    recordChallenge(path);
                } catch (Exception ex) {
                    log.log(System.Logger.Level.WARNING, "Failed to create invoice for {0} {1}: {2}", method, path, ex.getMessage());
                    if (!httpResponse.isCommitted()) {
                        writeLightningUnavailableResponse(httpResponse);
                    }
                }
            } else {
                writeErrorResponse(httpResponse, errorCode, e.getMessage(), e.getTokenId());
                recordRejected(path);
            }
        } catch (Exception e) {
            // Fail closed: any unexpected exception from validation produces 503, never 500
            log.log(System.Logger.Level.WARNING, "Unexpected error during L402 validation for {0} {1}: {2}", method, path, e.getMessage());
            if (!httpResponse.isCommitted()) {
                writeLightningUnavailableResponse(httpResponse);
            }
        }
    }

    private void writePaymentRequiredResponse(HttpServletRequest request,
                                               HttpServletResponse response,
                                               L402EndpointConfig config)
            throws IOException {

        // Generate root key and retrieve the tokenId the store used internally
        byte[][] outRootKey = new byte[1][];
        byte[] tokenId = generateRootKeyAndGetTokenId(outRootKey);
        byte[] rootKey = outRootKey[0];

        // Resolve effective price: dynamic strategy overrides static annotation value
        long effectivePrice = resolvePrice(request, config);

        // Create Lightning invoice
        Invoice invoice = lightningBackend.createInvoice(effectivePrice, config.description());

        // Build MacaroonIdentifier and mint macaroon
        MacaroonIdentifier identifier = new MacaroonIdentifier(0, invoice.paymentHash(), tokenId);
        Macaroon macaroon = MacaroonMinter.mint(rootKey, identifier, null, List.of());

        // Serialize and encode
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

        // Build response
        String wwwAuth = "L402 macaroon=\"" + macaroonBase64 + "\", invoice=\"" + invoice.bolt11() + "\"";

        response.setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        response.setHeader("WWW-Authenticate", wwwAuth);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 402, "message": "Payment required", "price_sats": %d, "description": "%s", "invoice": "%s"}"""
                .formatted(effectivePrice, escapeJson(config.description()), invoice.bolt11()));
    }

    /**
     * Generates a root key via the store and retrieves the tokenId that the store used.
     * Uses instanceof pattern matching to access the store-specific getLastGeneratedKeyId() method.
     * The generate+get pair is synchronized on the store to prevent interleaving.
     */
    private byte[] generateRootKeyAndGetTokenId(byte[][] outRootKey) {
        synchronized (rootKeyStore) {
            outRootKey[0] = rootKeyStore.generateRootKey();
            if (rootKeyStore instanceof InMemoryRootKeyStore mem) {
                return mem.getLastGeneratedKeyId();
            } else if (rootKeyStore instanceof FileBasedRootKeyStore file) {
                return file.getLastGeneratedKeyId();
            } else {
                // Fallback: generate a random tokenId. The root key won't be retrievable
                // by this tokenId — this is a known limitation for custom RootKeyStore impls.
                // They would need to implement a similar getLastGeneratedKeyId() method.
                byte[] tokenId = new byte[32];
                SECURE_RANDOM.nextBytes(tokenId);
                return tokenId;
            }
        }
    }

    /**
     * Resolves the effective price for an endpoint by looking up the pricing strategy
     * bean from the ApplicationContext. Falls back to the static annotation price if
     * no strategy is configured, the ApplicationContext is unavailable, or the bean
     * does not exist.
     */
    private long resolvePrice(HttpServletRequest request, L402EndpointConfig config) {
        String strategyName = config.pricingStrategy();
        if (strategyName == null || strategyName.isBlank() || applicationContext == null) {
            return config.priceSats();
        }
        try {
            L402PricingStrategy strategy = applicationContext.getBean(strategyName, L402PricingStrategy.class);
            return strategy.calculatePrice(request, config.priceSats());
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                    "Pricing strategy bean ''{0}'' not found or failed; falling back to static price {1} sats",
                    strategyName, config.priceSats());
            return config.priceSats();
        }
    }

    private void writeErrorResponse(HttpServletResponse response, ErrorCode errorCode,
                                    String message, String tokenId) throws IOException {
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType("application/json");

        String tokenDetail = tokenId != null ? tokenId : "";
        response.getWriter().write("""
                {"code": %d, "error": "%s", "message": "%s", "details": {"token_id": "%s"}}"""
                .formatted(errorCode.getHttpStatus(), errorCode.name(),
                        escapeJson(message), escapeJson(tokenDetail)));
    }

    private void writeLightningUnavailableResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code": 503, "error": "LIGHTNING_UNAVAILABLE", "message": "Lightning backend is not available. Please try again later."}""");
    }

    private void recordChallenge(String path) {
        try {
            if (metrics != null) { metrics.recordChallenge(path); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record challenge metric: {0}", e.getMessage());
        }
    }

    private void recordPassed(String path, long priceSats) {
        try {
            if (metrics != null) { metrics.recordPassed(path, priceSats); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record passed metric: {0}", e.getMessage());
        }
    }

    private void recordRejected(String path) {
        try {
            if (metrics != null) { metrics.recordRejected(path); }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "Failed to record rejected metric: {0}", e.getMessage());
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
