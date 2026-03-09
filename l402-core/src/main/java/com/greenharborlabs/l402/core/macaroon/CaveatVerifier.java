package com.greenharborlabs.l402.core.macaroon;

public interface CaveatVerifier {
    String getKey();
    void verify(Caveat caveat, L402VerificationContext context);
}
