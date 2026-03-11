package com.greenharborlabs.l402.core.protocol;

import com.greenharborlabs.l402.core.credential.CredentialStore;
import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.CaveatVerifier;
import com.greenharborlabs.l402.core.macaroon.L402VerificationContext;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.macaroon.MacaroonMinter;
import com.greenharborlabs.l402.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.l402.core.macaroon.RootKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("L402Validator")
class L402ValidatorTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SERVICE_NAME = "test-api";

    private byte[] rootKey;
    private byte[] preimageBytes;
    private byte[] paymentHash;
    private byte[] tokenIdBytes;
    private String tokenIdHex;
    private MacaroonIdentifier identifier;
    private Macaroon macaroon;
    private String validAuthHeader;

    /** Simple in-memory RootKeyStore backed by a map keyed on hex tokenId. */
    private final Map<String, byte[]> rootKeyMap = new HashMap<>();
    private final RootKeyStore rootKeyStore = new RootKeyStore() {
        @Override
        public GenerationResult generateRootKey() {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            byte[] tokenId = new byte[32];
            RANDOM.nextBytes(tokenId);
            return new GenerationResult(key, tokenId);
        }

        @Override
        public byte[] getRootKey(byte[] keyId) {
            return rootKeyMap.get(HEX.formatHex(keyId));
        }

        @Override
        public void revokeRootKey(byte[] keyId) {
            rootKeyMap.remove(HEX.formatHex(keyId));
        }
    };

    /** Simple in-memory CredentialStore backed by a map keyed on tokenId. */
    private final Map<String, L402Credential> credentialMap = new HashMap<>();
    private final CredentialStore credentialStore = new CredentialStore() {
        @Override
        public void store(String tokenId, L402Credential credential, long ttlSeconds) {
            credentialMap.put(tokenId, credential);
        }

        @Override
        public L402Credential get(String tokenId) {
            return credentialMap.get(tokenId);
        }

        @Override
        public void revoke(String tokenId) {
            credentialMap.remove(tokenId);
        }

        @Override
        public long activeCount() {
            return credentialMap.size();
        }
    };

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        rootKeyMap.clear();
        credentialMap.clear();

        rootKey = new byte[32];
        RANDOM.nextBytes(rootKey);

        preimageBytes = new byte[32];
        RANDOM.nextBytes(preimageBytes);
        paymentHash = MessageDigest.getInstance("SHA-256").digest(preimageBytes);

        tokenIdBytes = new byte[32];
        RANDOM.nextBytes(tokenIdBytes);
        tokenIdHex = HEX.formatHex(tokenIdBytes);

        identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        macaroon = MacaroonMinter.mint(rootKey, identifier, "https://example.com", List.of());

        // Register the root key so the validator can look it up by tokenId
        rootKeyMap.put(tokenIdHex, rootKey);

        // Build a valid Authorization header: L402 <base64-macaroon>:<hex-preimage>
        byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
        String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
        String preimageHex = HEX.formatHex(preimageBytes);
        validAuthHeader = "L402 " + macaroonBase64 + ":" + preimageHex;
    }

    @Nested
    @DisplayName("valid credential")
    class ValidCredential {

        @Test
        @DisplayName("returns credential when macaroon signature and preimage are valid")
        void validCredentialPasses() {
            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402Credential credential = validator.validate(validAuthHeader);

            assertThat(credential).isNotNull();
            assertThat(credential.tokenId()).isEqualTo(tokenIdHex);
            assertThat(credential.preimage().toHex()).isEqualTo(HEX.formatHex(preimageBytes));
        }
    }

    @Nested
    @DisplayName("invalid macaroon signature")
    class InvalidMacaroonSignature {

        @Test
        @DisplayName("throws INVALID_MACAROON when macaroon signature is tampered")
        void tamperedSignatureReturnsInvalidMacaroon() {
            // Tamper the macaroon signature by flipping a byte
            byte[] tamperedSig = macaroon.signature();
            tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
            Macaroon tampered = new Macaroon(
                    macaroon.identifier(), macaroon.location(), macaroon.caveats(), tamperedSig);

            byte[] serialized = MacaroonSerializer.serializeV2(tampered);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_MACAROON);
        }
    }

    @Nested
    @DisplayName("wrong preimage")
    class WrongPreimage {

        @Test
        @DisplayName("throws INVALID_PREIMAGE when preimage does not hash to paymentHash")
        void wrongPreimageReturnsInvalidPreimage() {
            // Use a different random preimage that won't match the payment hash
            byte[] wrongPreimage = new byte[32];
            RANDOM.nextBytes(wrongPreimage);
            String wrongPreimageHex = HEX.formatHex(wrongPreimage);

            byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String header = "L402 " + macaroonBase64 + ":" + wrongPreimageHex;

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE);
        }
    }

    @Nested
    @DisplayName("cached credential")
    class CachedCredential {

        @Test
        @DisplayName("returns cached credential without re-verification when present in store")
        void returnsCachedWithoutReVerification() {
            // Pre-populate the credential store with a cached credential
            PaymentPreimage preimage = PaymentPreimage.fromHex(HEX.formatHex(preimageBytes));
            L402Credential cached = new L402Credential(macaroon, preimage, tokenIdHex);
            credentialStore.store(tokenIdHex, cached, 3600);

            // Use a rootKeyStore that returns null -- if re-verification happened,
            // it would fail because there's no root key. The cached path should skip that.
            RootKeyStore emptyKeyStore = new RootKeyStore() {
                @Override
                public GenerationResult generateRootKey() {
                    return new GenerationResult(new byte[32], new byte[32]);
                }

                @Override
                public byte[] getRootKey(byte[] keyId) {
                    return null;
                }

                @Override
                public void revokeRootKey(byte[] keyId) {
                }
            };

            L402Validator validator = new L402Validator(
                    emptyKeyStore, credentialStore, List.of(), SERVICE_NAME);

            L402Credential result = validator.validate(validAuthHeader);

            assertThat(result).isSameAs(cached);
        }
    }

    @Nested
    @DisplayName("expired caveat")
    class ExpiredCaveat {

        @Test
        @DisplayName("throws EXPIRED_CREDENTIAL when valid_until caveat is in the past")
        void expiredCaveatReturnsExpiredCredential() throws NoSuchAlgorithmException {
            // Create a macaroon with an expired valid_until caveat
            long pastEpochSeconds = Instant.now().minusSeconds(3600).getEpochSecond();
            List<Caveat> caveats = List.of(
                    new Caveat(SERVICE_NAME + "_valid_until", String.valueOf(pastEpochSeconds))
            );

            // Re-mint with caveats
            Macaroon macaroonWithExpiry = MacaroonMinter.mint(
                    rootKey, identifier, "https://example.com", caveats);

            byte[] serialized = MacaroonSerializer.serializeV2(macaroonWithExpiry);
            String macaroonBase64 = Base64.getEncoder().encodeToString(serialized);
            String preimageHex = HEX.formatHex(preimageBytes);
            String header = "L402 " + macaroonBase64 + ":" + preimageHex;

            // Create a valid_until caveat verifier that throws EXPIRED_CREDENTIAL for past timestamps
            CaveatVerifier validUntilVerifier = new CaveatVerifier() {
                @Override
                public String getKey() {
                    return SERVICE_NAME + "_valid_until";
                }

                @Override
                public void verify(Caveat caveat, L402VerificationContext context) {
                    long expiryEpoch = Long.parseLong(caveat.value());
                    Instant expiry = Instant.ofEpochSecond(expiryEpoch);
                    if (!expiry.isAfter(context.getCurrentTime())) {
                        throw new L402Exception(
                                ErrorCode.EXPIRED_CREDENTIAL,
                                "Credential expired at " + expiry,
                                null);
                    }
                }
            };

            L402Validator validator = new L402Validator(
                    rootKeyStore, credentialStore, List.of(validUntilVerifier), SERVICE_NAME);

            assertThatThrownBy(() -> validator.validate(header))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
        }
    }
}
