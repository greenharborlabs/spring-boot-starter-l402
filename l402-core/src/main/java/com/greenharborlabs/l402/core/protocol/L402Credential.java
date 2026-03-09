package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;

import java.util.Objects;

/**
 * An authenticated L402 credential consisting of a macaroon, preimage proof-of-payment,
 * and the hex-encoded token identifier.
 */
public record L402Credential(Macaroon macaroon, PaymentPreimage preimage, String tokenId) {

    public L402Credential {
        Objects.requireNonNull(macaroon, "macaroon must not be null");
        Objects.requireNonNull(preimage, "preimage must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        if (tokenId.isEmpty()) {
            throw new IllegalArgumentException("tokenId must not be empty");
        }
    }

    @Override
    public String toString() {
        return "L402Credential[tokenId=" + tokenId + "]";
    }
}
