package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;

import java.util.Base64;
import java.util.Objects;

/**
 * Represents an L402 payment challenge issued to a client that has not yet paid.
 * Contains the macaroon (pre-authorized but unsigned by payment), the Lightning invoice,
 * the price in satoshis, and a human-readable description.
 */
public record L402Challenge(Macaroon macaroon, String bolt11Invoice, long priceSats, String description) {

    public L402Challenge {
        Objects.requireNonNull(macaroon, "macaroon must not be null");
        Objects.requireNonNull(bolt11Invoice, "bolt11Invoice must not be null");
        if (bolt11Invoice.isEmpty()) {
            throw new IllegalArgumentException("bolt11Invoice must not be empty");
        }
        if (priceSats <= 0) {
            throw new IllegalArgumentException("priceSats must be positive, got " + priceSats);
        }
    }

    @Override
    public String toString() {
        return "L402Challenge[priceSats=" + priceSats + ", description=" + description + "]";
    }

    /**
     * Formats the challenge as a {@code WWW-Authenticate} header value.
     * <p>Example: {@code L402 macaroon="<base64>", invoice="<bolt11>"}
     *
     * @return the header value string
     */
    public String toWwwAuthenticateHeader() {
        String macaroonBase64 = Base64.getEncoder().encodeToString(MacaroonSerializer.serializeV2(macaroon));
        return "L402 macaroon=\"" + macaroonBase64 + "\", invoice=\"" + sanitizeBolt11ForHeader(bolt11Invoice) + "\"";
    }

    /**
     * Strips characters from a bolt11 string that could enable HTTP header injection
     * (CRLF) or break the WWW-Authenticate header format (double quotes).
     */
    private static String sanitizeBolt11ForHeader(String bolt11) {
        var sb = new StringBuilder(bolt11.length());
        for (int i = 0; i < bolt11.length(); i++) {
            char c = bolt11.charAt(i);
            if (c != '"' && c != '\r' && c != '\n') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Builds a JSON response body describing the payment challenge.
     * Uses manual string construction to avoid external JSON library dependencies.
     *
     * @return a JSON string
     */
    public String toJsonBody() {
        String escapedDescription = description == null ? "null" : "\"" + escapeJson(description) + "\"";
        return "{\"code\":402,\"message\":\"Payment required\",\"price_sats\":" + priceSats
                + ",\"description\":" + escapedDescription
                + ",\"invoice\":\"" + escapeJson(bolt11Invoice) + "\"}";
    }

    private static String escapeJson(String value) {
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
