package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern HEADER_PATTERN =
            Pattern.compile("(LSAT|L402) (.*?):([a-f0-9]{64})");
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Parses an L402/LSAT Authorization header into an {@link L402Credential}.
     *
     * @param authorizationHeader the raw Authorization header value
     * @return a parsed credential
     * @throws L402Exception with {@link ErrorCode#MALFORMED_HEADER} on any parse failure
     */
    public static L402Credential parse(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isEmpty()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Authorization header must not be null or empty", null);
        }

        Matcher matcher = HEADER_PATTERN.matcher(authorizationHeader);
        if (!matcher.matches()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Authorization header does not match L402/LSAT format", null);
        }

        String macaroonBase64 = matcher.group(2);
        String preimageHex = matcher.group(3);

        if (macaroonBase64.isEmpty()) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Macaroon data must not be empty", null);
        }

        byte[] macaroonBytes;
        try {
            macaroonBytes = Base64.getDecoder().decode(macaroonBase64);
        } catch (IllegalArgumentException e) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Invalid base64 macaroon encoding: " + e.getMessage(), null);
        }

        Macaroon macaroon;
        try {
            macaroon = MacaroonSerializer.deserializeV2(macaroonBytes);
        } catch (IllegalArgumentException e) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Invalid macaroon data: " + e.getMessage(), null);
        }

        PaymentPreimage preimage;
        try {
            preimage = PaymentPreimage.fromHex(preimageHex);
        } catch (IllegalArgumentException e) {
            throw new L402Exception(ErrorCode.MALFORMED_HEADER,
                    "Invalid preimage hex: " + e.getMessage(), null);
        }

        MacaroonIdentifier id = MacaroonIdentifier.decode(macaroon.identifier());
        String tokenId = HEX.formatHex(id.tokenId());

        return new L402Credential(macaroon, preimage, tokenId);
    }

    @Override
    public String toString() {
        return "L402Credential[tokenId=" + tokenId + "]";
    }
}
