package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;
import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import com.greenharborlabs.l402.core.protocol.L402Credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring integration tests for {@link L402SecurityFilter}.
 *
 * <p>Tests the filter behavior for protected and unprotected endpoints covering:
 * <ul>
 *   <li>No auth header on protected endpoint returns 402 with WWW-Authenticate</li>
 *   <li>Valid L402 credential returns 200 with token headers</li>
 *   <li>Non-protected endpoint passes through without authentication</li>
 *   <li>Lightning backend unavailable returns 503</li>
 * </ul>
 *
 * <p>Uses a test-specific configuration that manually wires all required beans,
 * avoiding dependency on L402AutoConfiguration which does not yet exist.
 */
@SpringBootTest(classes = L402SecurityFilterTest.TestApp.class)
@AutoConfigureMockMvc
@DisplayName("L402SecurityFilter")
class L402SecurityFilterTest {

    private static final byte[] ROOT_KEY = new byte[32];
    private static final HexFormat HEX = HexFormat.of();
    private static final long PRICE_SATS = 10;
    private static final String PROTECTED_PATH = "/api/protected";
    private static final String PUBLIC_PATH = "/api/public";

    static {
        new SecureRandom().nextBytes(ROOT_KEY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LightningBackend lightningBackend;

    // -----------------------------------------------------------------------
    // Test application and configuration
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        LightningBackend lightningBackend() {
            return new StubLightningBackend();
        }

        @Bean
        RootKeyStore rootKeyStore() {
            return new InMemoryTestRootKeyStore(ROOT_KEY);
        }

        @Bean
        CredentialStore credentialStore() {
            return new InMemoryTestCredentialStore();
        }

        @Bean
        List<CaveatVerifier> caveatVerifiers() {
            return List.of();
        }

        @Bean
        L402EndpointRegistry l402EndpointRegistry() {
            var registry = new L402EndpointRegistry();
            registry.register(
                    new L402EndpointConfig("GET", PROTECTED_PATH, PRICE_SATS, 600, "Test protected endpoint", "")
            );
            return registry;
        }

        @Bean
        L402SecurityFilter l402SecurityFilter(
                L402EndpointRegistry endpointRegistry,
                LightningBackend lightningBackendBean,
                RootKeyStore rootKeyStore,
                CredentialStore credentialStore,
                List<CaveatVerifier> caveatVerifiers
        ) {
            return new L402SecurityFilter(
                    endpointRegistry, lightningBackendBean, rootKeyStore, credentialStore, caveatVerifiers, "test-service"
            );
        }

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @L402Protected(priceSats = 10, description = "Test protected endpoint")
        @GetMapping(PROTECTED_PATH)
        String protectedEndpoint() {
            return "protected-content";
        }

        @GetMapping(PUBLIC_PATH)
        String publicEndpoint() {
            return "public-content";
        }
    }

    // -----------------------------------------------------------------------
    // Test scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("no auth header on protected endpoint")
    class NoAuthHeader {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("returns 402 with WWW-Authenticate header")
        void returns402WithWwwAuthenticate() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(header().string("WWW-Authenticate", containsString("L402")))
                    .andExpect(header().string("WWW-Authenticate", containsString("macaroon=")))
                    .andExpect(header().string("WWW-Authenticate", containsString("invoice=")));
        }

        @Test
        @DisplayName("returns JSON body with payment details")
        void returns402JsonBody() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(402)))
                    .andExpect(jsonPath("$.message", is("Payment required")))
                    .andExpect(jsonPath("$.price_sats", is(10)))
                    .andExpect(jsonPath("$.invoice", notNullValue()));
        }
    }

    @Nested
    @DisplayName("malformed auth header on protected endpoint")
    class MalformedAuthHeader {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(true);
            ((StubLightningBackend) lightningBackend).setNextInvoice(createStubInvoice());
        }

        @Test
        @DisplayName("returns 402 when Authorization header is not L402 scheme")
        void nonL402SchemeReturns402() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", "Bearer some-token"))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("returns 402 when Authorization header has malformed L402 value")
        void malformedL402Returns402() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", "L402 not-valid-format"))
                    .andExpect(status().isPaymentRequired());
        }
    }

    @Nested
    @DisplayName("valid credential on protected endpoint")
    class ValidCredential {

        @Test
        @DisplayName("returns 200 with X-L402-Token-Id and X-L402-Credential-Expires headers")
        void validCredentialReturns200WithHeaders() throws Exception {
            ((StubLightningBackend) lightningBackend).setHealthy(true);

            // Generate a preimage and its corresponding payment hash
            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);

            // Generate a token ID
            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);

            // Mint a real macaroon using the known root key
            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, List.of());

            // Serialize the macaroon to V2 binary and base64 encode
            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);

            // Format preimage as hex
            String preimageHex = HEX.formatHex(preimage);

            // Build the L402 Authorization header: L402 <base64-macaroon>:<hex-preimage>
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-L402-Token-Id"))
                    .andExpect(header().exists("X-L402-Credential-Expires"))
                    .andExpect(content().string("protected-content"));
        }

        @Test
        @DisplayName("X-L402-Token-Id header contains the hex-encoded token ID from the macaroon")
        void tokenIdHeaderMatchesMacaroon() throws Exception {
            ((StubLightningBackend) lightningBackend).setHealthy(true);

            byte[] preimage = new byte[32];
            new SecureRandom().nextBytes(preimage);
            byte[] paymentHash = sha256(preimage);

            byte[] tokenId = new byte[32];
            new SecureRandom().nextBytes(tokenId);
            String expectedTokenIdHex = HEX.formatHex(tokenId);

            MacaroonIdentifier identifier = new MacaroonIdentifier(0, paymentHash, tokenId);
            Macaroon macaroon = MacaroonMinter.mint(ROOT_KEY, identifier, null, List.of());

            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimage);
            String authHeader = "L402 " + macaroonBase64 + ":" + preimageHex;

            mockMvc.perform(get(PROTECTED_PATH)
                            .header("Authorization", authHeader))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-L402-Token-Id", is(expectedTokenIdHex)));
        }
    }

    @Nested
    @DisplayName("unprotected endpoint")
    class UnprotectedEndpoint {

        @Test
        @DisplayName("passes through without authentication")
        void publicEndpointReturns200WithoutAuth() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }

        @Test
        @DisplayName("does not add L402 response headers")
        void publicEndpointHasNoL402Headers() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-L402-Token-Id"))
                    .andExpect(header().doesNotExist("X-L402-Credential-Expires"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"));
        }
    }

    @Nested
    @DisplayName("Lightning backend unavailable")
    class LightningUnavailable {

        @BeforeEach
        void setUp() {
            ((StubLightningBackend) lightningBackend).setHealthy(false);
        }

        @Test
        @DisplayName("returns 503 when Lightning is unreachable and no auth header present")
        void returns503WhenLightningDown() throws Exception {
            mockMvc.perform(get(PROTECTED_PATH))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code", is(503)))
                    .andExpect(jsonPath("$.error", is("LIGHTNING_UNAVAILABLE")))
                    .andExpect(jsonPath("$.message", containsString("Lightning backend is not available")));
        }

        @Test
        @DisplayName("unprotected endpoint still works when Lightning is down")
        void publicEndpointStillWorksWhenLightningDown() throws Exception {
            mockMvc.perform(get(PUBLIC_PATH))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    private static Invoice createStubInvoice() {
        byte[] paymentHash = new byte[32];
        new SecureRandom().nextBytes(paymentHash);
        Instant now = Instant.now();
        return new Invoice(
                paymentHash,
                "lnbc100n1p0testinvoice",
                PRICE_SATS,
                "Test invoice",
                InvoiceStatus.PENDING,
                null,
                now,
                now.plus(1, ChronoUnit.HOURS)
        );
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // -----------------------------------------------------------------------
    // Stub / in-memory implementations for test isolation
    // -----------------------------------------------------------------------

    /**
     * Controllable stub for LightningBackend that allows tests to set health status
     * and pre-configure the next invoice to be returned.
     */
    static class StubLightningBackend implements LightningBackend {

        private volatile boolean healthy = true;
        private volatile Invoice nextInvoice;

        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        void setNextInvoice(Invoice invoice) {
            this.nextInvoice = invoice;
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            if (!healthy) {
                throw new RuntimeException("Lightning backend is not available");
            }
            if (nextInvoice != null) {
                return nextInvoice;
            }
            byte[] paymentHash = new byte[32];
            new SecureRandom().nextBytes(paymentHash);
            Instant now = Instant.now();
            return new Invoice(paymentHash, "lnbc" + amountSats + "n1pstub", amountSats,
                    memo, InvoiceStatus.PENDING, null, now, now.plus(1, ChronoUnit.HOURS));
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            if (!healthy) {
                throw new RuntimeException("Lightning backend is not available");
            }
            return null;
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }
    }

    /**
     * In-memory RootKeyStore backed by a single fixed root key.
     * All calls to {@code generateRootKey()} and {@code getRootKey()} return the same key.
     */
    static class InMemoryTestRootKeyStore implements RootKeyStore {

        private final byte[] rootKey;

        InMemoryTestRootKeyStore(byte[] rootKey) {
            this.rootKey = rootKey.clone();
        }

        @Override
        public byte[] generateRootKey() {
            return rootKey.clone();
        }

        @Override
        public byte[] getRootKey(byte[] keyId) {
            return rootKey.clone();
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            // no-op for tests
        }
    }

    /**
     * In-memory CredentialStore for test isolation.
     */
    static class InMemoryTestCredentialStore implements CredentialStore {

        private final Map<String, L402Credential> store = new ConcurrentHashMap<>();

        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) {
            store.put(tokenId, credential);
        }

        @Override
        public L402Credential get(String tokenId) {
            return store.get(tokenId);
        }

        @Override
        public void revoke(String tokenId) {
            store.remove(tokenId);
        }

        @Override
        public long activeCount() {
            return store.size();
        }
    }
}
