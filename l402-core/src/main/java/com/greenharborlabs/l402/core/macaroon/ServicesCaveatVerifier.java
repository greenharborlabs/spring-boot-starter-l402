package com.greenharborlabs.l402.core.macaroon;

import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Exception;

public class ServicesCaveatVerifier implements CaveatVerifier {

    @Override
    public String getKey() {
        return "services";
    }

    @Override
    public void verify(Caveat caveat, L402VerificationContext context) {
        String serviceName = context.getServiceName();
        if (serviceName == null) {
            throw new L402Exception(ErrorCode.INVALID_SERVICE,
                    "Service name is null in verification context", null);
        }

        String[] serviceEntries = caveat.value().split(",");
        for (String entry : serviceEntries) {
            String name = entry.split(":")[0].trim();
            if (name.equals(serviceName)) {
                return;
            }
        }

        throw new L402Exception(ErrorCode.INVALID_SERVICE,
                "Service '" + serviceName + "' not found in caveat services list", null);
    }
}
