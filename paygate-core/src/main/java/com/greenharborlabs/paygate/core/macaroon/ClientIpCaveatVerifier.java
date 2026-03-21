package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;

/**
 * Verifies that the request client IP matches at least one IP address
 * specified in the {@code client_ip} caveat value (comma-separated).
 *
 * <p>Comparison is exact string match (case-sensitive). Stateless and thread-safe.
 */
public class ClientIpCaveatVerifier implements CaveatVerifier {

    private final int maxValuesPerCaveat;

    public ClientIpCaveatVerifier(int maxValuesPerCaveat) {
        this.maxValuesPerCaveat = maxValuesPerCaveat;
    }

    @Override
    public String getKey() {
        return "client_ip";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        // 1. Extract request client IP — fail-closed if absent
        String requestClientIp = context.getRequestMetadata()
                .get(VerificationContextKeys.REQUEST_CLIENT_IP);
        if (requestClientIp == null) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Client IP missing from verification context", null);
        }

        // 2. Split caveat value by comma
        String[] rawIps = caveat.value().split(",", -1);

        // 3. Reject if IP count exceeds max
        if (rawIps.length > maxValuesPerCaveat) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Client IP caveat contains " + rawIps.length
                            + " values, exceeding maximum of " + maxValuesPerCaveat, null);
        }

        // 4. Reject if any IP is empty after trim
        for (String raw : rawIps) {
            if (raw.trim().isEmpty()) {
                throw new L402Exception(ErrorCode.INVALID_SERVICE,
                        "Empty client IP in caveat value", null);
            }
        }

        // 5. Match request client IP against each allowed IP (exact string match)
        for (String raw : rawIps) {
            if (requestClientIp.equals(raw.trim())) {
                return;
            }
        }

        // 6. No IP matched — reject
        throw new L402Exception(ErrorCode.INVALID_SERVICE,
                "Request client IP does not match any allowed IP", null);
    }
}
