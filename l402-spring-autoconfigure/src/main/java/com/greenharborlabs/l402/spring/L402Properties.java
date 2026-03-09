package com.greenharborlabs.l402.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the L402 Spring Boot starter.
 *
 * <p>Bound from the {@code l402.*} namespace in application configuration.
 */
@ConfigurationProperties("l402")
public class L402Properties {

    private boolean enabled = false;

    private String backend;

    private long defaultPriceSats = 10;

    private long defaultTimeoutSeconds = 3600;

    private String serviceName;

    private String rootKeyStore = "file";

    private String rootKeyStorePath = "~/.l402/keys";

    private String credentialCache = "caffeine";

    private int credentialCacheMaxSize = 10000;

    private boolean testMode = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public long getDefaultPriceSats() {
        return defaultPriceSats;
    }

    public void setDefaultPriceSats(long defaultPriceSats) {
        this.defaultPriceSats = defaultPriceSats;
    }

    public long getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(long defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getRootKeyStore() {
        return rootKeyStore;
    }

    public void setRootKeyStore(String rootKeyStore) {
        this.rootKeyStore = rootKeyStore;
    }

    public String getRootKeyStorePath() {
        return rootKeyStorePath;
    }

    public void setRootKeyStorePath(String rootKeyStorePath) {
        this.rootKeyStorePath = rootKeyStorePath;
    }

    public String getCredentialCache() {
        return credentialCache;
    }

    public void setCredentialCache(String credentialCache) {
        this.credentialCache = credentialCache;
    }

    public int getCredentialCacheMaxSize() {
        return credentialCacheMaxSize;
    }

    public void setCredentialCacheMaxSize(int credentialCacheMaxSize) {
        this.credentialCacheMaxSize = credentialCacheMaxSize;
    }

    public boolean isTestMode() {
        return testMode;
    }

    public void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }
}
