package com.greenharborlabs.paygate.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the client IP address from an {@link HttpServletRequest}, optionally
 * inspecting the {@code X-Forwarded-For} header when behind trusted proxies.
 */
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final boolean trustForwardedHeaders;
    private final Set<String> trustedProxyAddresses;

    public ClientIpResolver(boolean trustForwardedHeaders, List<String> trustedProxyAddresses) {
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustedProxyAddresses = new HashSet<>(trustedProxyAddresses);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (!trustForwardedHeaders) {
            return remoteAddr;
        }

        String xff = request.getHeader(X_FORWARDED_FOR);
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }

        String[] entries = xff.split(",");

        // Walk right-to-left, skipping trusted proxies
        for (int i = entries.length - 1; i >= 0; i--) {
            String entry = entries[i].trim();
            if (!trustedProxyAddresses.contains(entry)) {
                return entry;
            }
        }

        // All entries were trusted proxies
        return remoteAddr;
    }
}
