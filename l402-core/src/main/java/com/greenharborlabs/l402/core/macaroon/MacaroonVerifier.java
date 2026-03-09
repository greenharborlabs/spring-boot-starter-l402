package com.greenharborlabs.l402.core.macaroon;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MacaroonVerifier {

    private MacaroonVerifier() {}

    public static void verify(Macaroon macaroon, byte[] rootKey,
                              List<CaveatVerifier> caveatVerifiers,
                              L402VerificationContext context) {
        byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
        byte[] sig = MacaroonCrypto.hmac(derivedKey, macaroon.identifier());

        for (Caveat caveat : macaroon.caveats()) {
            sig = MacaroonCrypto.hmac(sig, caveat.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (!MacaroonCrypto.constantTimeEquals(sig, macaroon.signature())) {
            throw new MacaroonVerificationException("signature verification failed");
        }

        for (Caveat caveat : macaroon.caveats()) {
            CaveatVerifier verifier = findVerifier(caveatVerifiers, caveat.key());
            if (verifier == null) {
                throw new MacaroonVerificationException("no verifier for caveat: " + caveat.key());
            }
            verifier.verify(caveat, context);
        }
    }

    private static CaveatVerifier findVerifier(List<CaveatVerifier> verifiers, String key) {
        for (CaveatVerifier v : verifiers) {
            if (v.getKey().equals(key)) {
                return v;
            }
        }
        return null;
    }
}
