package com.greenharborlabs.l402.lightning.lnd;

/**
 * Configuration for connecting to an LND node over gRPC.
 *
 * @param host         LND gRPC host
 * @param port         LND gRPC port
 * @param tlsCertPath  path to TLS certificate file, or null for plaintext/test channels
 * @param macaroonPath path to admin macaroon file, or null for unauthenticated/test channels
 */
public record LndConfig(
        String host,
        int port,
        String tlsCertPath,
        String macaroonPath
) {

    public LndConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535, got: " + port);
        }
    }
}
