package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint exposing L402 status and configuration.
 *
 * <p>Available at {@code GET /actuator/l402} when the actuator is on the classpath
 * and the endpoint is exposed.
 */
@Endpoint(id = "l402")
public class L402ActuatorEndpoint {

    private final L402Properties properties;
    private final LightningBackend lightningBackend;
    private final L402EndpointRegistry endpointRegistry;
    private final CredentialStore credentialStore;
    private final L402EarningsTracker earningsTracker;

    public L402ActuatorEndpoint(L402Properties properties,
                                 LightningBackend lightningBackend,
                                 L402EndpointRegistry endpointRegistry,
                                 CredentialStore credentialStore,
                                 L402EarningsTracker earningsTracker) {
        this.properties = properties;
        this.lightningBackend = lightningBackend;
        this.endpointRegistry = endpointRegistry;
        this.credentialStore = credentialStore;
        this.earningsTracker = earningsTracker;
    }

    @ReadOperation
    public Map<String, Object> l402Info() {
        var result = new LinkedHashMap<String, Object>();
        result.put("enabled", properties.isEnabled());
        result.put("backend", properties.getBackend());
        result.put("backendHealthy", lightningBackend.isHealthy());
        result.put("serviceName", properties.getServiceName());
        result.put("protectedEndpoints", buildProtectedEndpoints());
        result.put("credentials", buildCredentials());
        result.put("earnings", buildEarnings());
        return result;
    }

    private List<Map<String, Object>> buildProtectedEndpoints() {
        Collection<L402EndpointConfig> configs = endpointRegistry.getConfigs();
        return configs.stream().map(this::toEndpointMap).toList();
    }

    private Map<String, Object> toEndpointMap(L402EndpointConfig config) {
        var map = new LinkedHashMap<String, Object>();
        map.put("method", config.httpMethod());
        map.put("path", config.pathPattern());
        map.put("priceSats", config.priceSats());
        map.put("timeoutSeconds", config.timeoutSeconds());
        map.put("description", config.description());
        map.put("pricingStrategy",
                config.pricingStrategy() == null || config.pricingStrategy().isEmpty()
                        ? null
                        : config.pricingStrategy());
        return map;
    }

    private Map<String, Object> buildCredentials() {
        var map = new LinkedHashMap<String, Object>();
        map.put("active", credentialStore.activeCount());
        map.put("maxSize", properties.getCredentialCacheMaxSize());
        return map;
    }

    private Map<String, Object> buildEarnings() {
        var map = new LinkedHashMap<String, Object>();
        map.put("totalInvoicesCreated", earningsTracker.getTotalInvoicesCreated());
        map.put("totalInvoicesSettled", earningsTracker.getTotalInvoicesSettled());
        map.put("totalSatsEarned", earningsTracker.getTotalSatsEarned());
        return map;
    }
}
