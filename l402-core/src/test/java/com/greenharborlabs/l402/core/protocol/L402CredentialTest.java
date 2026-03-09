package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L402Credential.parse")
class L402CredentialTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private byte[] rootKey;
    private byte[] preimageBytes;
    private byte[] paymentHash;
    private byte[] tokenIdBytes;
    private MacaroonIdentifier identifier;
    private Macaroon macaroon;
    private String macaroonBase64;
    private String preimageHex;
    private String tokenIdHex;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        rootKey = new byte[32];
        RANDOM.nextBytes(rootKey);

        preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

        tokenIdBytes = new byte[32];
        RANDOM.nextBytes(tokenIdBytes);

        identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());

        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        preimageHex = HEX.formatHex(preimageBytes);
        tokenIdHex = HEX.formatHex(tokenIdBytes);
    }

    @Nested
    @DisplayName("valid L402 header")
    class ValidL402Header {

        @Test
        @DisplayName("parses L402 prefix with valid base64 macaroon and hex preimage")
        void parsesL402Prefix() {
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Credential credential = L402Credential.parse(header);

            assertThat(credential).isNotNull();
            assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
            assertThat(credential.preimage().toHex()).isEqualTo(preimageHex);
            assertThat(credential.macaroon().identifier()).isEqualTo(macaroon.identifier());
        }

        @Test
        @DisplayName("parses macaroon with correct signature preservation")
        void preservesMacaroonSignature() {
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Credential credential = L402Credential.parse(header);

            assertThat(credential.macaroon().signature()).isEqualTo(macaroon.signature());
        }

        @Test
        @DisplayName("parses macaroon with correct location preservation")
        void preservesMacaroonLocation() {
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Credential credential = L402Credential.parse(header);

            assertThat(credential.macaroon().location()).isEqualTo("https://example.com");
        }
    }

    @Nested
    @DisplayName("valid LSAT header (backward compatibility)")
    class ValidLsatHeader {

        @Test
        @DisplayName("parses LSAT prefix with valid base64 macaroon and hex preimage")
        void parsesLsatPrefix() {
            String header = "LSAT " + macaroonBase64 + ":" + preimageHex;

            L402Credential credential = L402Credential.parse(header);

            assertThat(credential).isNotNull();
            assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
            assertThat(credential.preimage().toHex()).isEqualTo(preimageHex);
        }
    }

    @Nested
    @DisplayName("malformed headers")
    class MalformedHeaders {

        @Test
        @DisplayName("throws MALFORMED_HEADER for null header")
        void throwsForNull() {
            assertThatThrownBy(() -> L402Credential.parse(null))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for empty string")
        void throwsForEmptyString() {
            assertThatThrownBy(() -> L402Credential.parse(""))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for header without colon separator")
        void throwsForNoColon() {
            String header = "L402 " + macaroonBase64 + preimageHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for wrong prefix (Bearer)")
        void throwsForWrongPrefix() {
            String header = "Bearer " + macaroonBase64 + ":" + preimageHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for preimage with wrong hex length (62 chars)")
        void throwsForShortPreimageHex() {
            String shortHex = preimageHex.substring(0, 62);
            String header = "L402 " + macaroonBase64 + ":" + shortHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for preimage with invalid hex characters")
        void throwsForInvalidHexChars() {
            String invalidHex = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
            String header = "L402 " + macaroonBase64 + ":" + invalidHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for missing macaroon data")
        void throwsForMissingMacaroon() {
            String header = "L402 :" + preimageHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for invalid base64 macaroon")
        void throwsForInvalidBase64() {
            String header = "L402 !!!invalid-base64!!!:" + preimageHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }

        @Test
        @DisplayName("throws MALFORMED_HEADER for preimage with uppercase hex (regex requires lowercase)")
        void throwsForUppercaseHex() {
            String upperHex = preimageHex.toUpperCase();
            String header = "L402 " + macaroonBase64 + ":" + upperHex;

            assertThatThrownBy(() -> L402Credential.parse(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.MALFORMED_HEADER);
        }
    }
}
