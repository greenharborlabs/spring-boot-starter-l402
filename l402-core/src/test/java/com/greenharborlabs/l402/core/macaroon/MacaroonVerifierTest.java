package com.greenharborlabs.l402.core.macaroon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MacaroonVerifier")
class MacaroonVerifierTest {

    private static final int IDENTIFIER_LENGTH = 66;

    private byte[] rootKey;
    private byte[] identifier;
    private L402VerificationContext context;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        rootKey = new byte[32];
        random.nextBytes(rootKey);
        identifier = new byte[IDENTIFIER_LENGTH];
        random.nextBytes(identifier);
        context = new L402VerificationContext();
    }

    /**
     * Builds a macaroon with a correctly computed HMAC chain signature.
     * derivedKey = HMAC-SHA256("macaroons-key-generator", rootKey)
     * sig = HMAC-SHA256(derivedKey, identifier)
     * for each caveat: sig = HMAC-SHA256(sig, "key=value".getBytes(UTF_8))
     */
    private Macaroon createValidMacaroon(byte[] rootKey, byte[] identifier, String location, List<Caveat> caveats) {
        byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
        byte[] sig = MacaroonCrypto.hmac(derivedKey, identifier);
        for (Caveat c : caveats) {
            sig = MacaroonCrypto.hmac(sig, c.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new Macaroon(identifier, location, caveats, sig);
    }

    /**
     * Creates a simple CaveatVerifier that accepts any caveat with the given key.
     */
    private CaveatVerifier acceptingVerifier(String key) {
        return new CaveatVerifier() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext ctx) {
                // accepts unconditionally
            }
        };
    }

    /**
     * Creates a CaveatVerifier that always rejects by throwing.
     */
    private CaveatVerifier rejectingVerifier(String key) {
        return new CaveatVerifier() {
            @Override
            public String getKey() {
                return key;
            }

            @Override
            public void verify(Caveat caveat, L402VerificationContext ctx) {
                throw new MacaroonVerificationException("caveat rejected: " + caveat);
            }
        };
    }

    @Nested
    @DisplayName("valid macaroon verification")
    class ValidMacaroon {

        @Test
        @DisplayName("succeeds for valid macaroon with no caveats")
        void validNoCaveats() {
            Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", List.of());

            assertThatCode(() ->
                    MacaroonVerifier.verify(macaroon, rootKey, List.of(), context)
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("succeeds for valid macaroon with caveats and matching verifiers")
        void validWithCaveats() {
            List<Caveat> caveats = List.of(
                    new Caveat("service", "api"),
                    new Caveat("tier", "premium")
            );
            Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

            List<CaveatVerifier> verifiers = List.of(
                    acceptingVerifier("service"),
                    acceptingVerifier("tier")
            );

            assertThatCode(() ->
                    MacaroonVerifier.verify(macaroon, rootKey, verifiers, context)
            ).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("tampered signature")
    class TamperedSignature {

        @Test
        @DisplayName("fails when a byte in the signature is flipped")
        void tamperedSignatureFails() {
            Macaroon valid = createValidMacaroon(rootKey, identifier, "https://example.com", List.of());

            byte[] tamperedSig = valid.signature();
            tamperedSig[0] = (byte) (tamperedSig[0] ^ 0xFF);
            Macaroon tampered = new Macaroon(valid.identifier(), valid.location(), valid.caveats(), tamperedSig);

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(tampered, rootKey, List.of(), context)
            ).isInstanceOf(MacaroonVerificationException.class);
        }
    }

    @Nested
    @DisplayName("tampered caveat")
    class TamperedCaveat {

        @Test
        @DisplayName("fails when a caveat value is changed but signature is unchanged")
        void tamperedCaveatFails() {
            List<Caveat> originalCaveats = List.of(new Caveat("service", "api"));
            Macaroon valid = createValidMacaroon(rootKey, identifier, "https://example.com", originalCaveats);

            // Build a tampered macaroon: different caveat value, same signature
            List<Caveat> tamperedCaveats = List.of(new Caveat("service", "admin"));
            Macaroon tampered = new Macaroon(valid.identifier(), valid.location(), tamperedCaveats, valid.signature());

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(tampered, rootKey, List.of(acceptingVerifier("service")), context)
            ).isInstanceOf(MacaroonVerificationException.class);
        }
    }

    @Nested
    @DisplayName("wrong root key")
    class WrongRootKey {

        @Test
        @DisplayName("fails when verified with a different root key")
        void wrongRootKeyFails() {
            Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", List.of());

            byte[] wrongKey = new byte[32];
            System.arraycopy(rootKey, 0, wrongKey, 0, rootKey.length);
            wrongKey[0] = (byte) (wrongKey[0] ^ 0xFF);

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(macaroon, wrongKey, List.of(), context)
            ).isInstanceOf(MacaroonVerificationException.class);
        }
    }

    @Nested
    @DisplayName("caveat verifier rejection")
    class CaveatVerifierRejection {

        @Test
        @DisplayName("propagates exception when caveat verifier rejects")
        void verifierRejectsPropagates() {
            List<Caveat> caveats = List.of(new Caveat("service", "api"));
            Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(macaroon, rootKey, List.of(rejectingVerifier("service")), context)
            ).isInstanceOf(MacaroonVerificationException.class);
        }
    }

    @Nested
    @DisplayName("missing caveat verifier")
    class MissingCaveatVerifier {

        @Test
        @DisplayName("fails when macaroon has a caveat but no verifier for that key")
        void missingVerifierFails() {
            List<Caveat> caveats = List.of(new Caveat("service", "api"));
            Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

            // Provide a verifier for a different key — "service" has no verifier
            List<CaveatVerifier> verifiers = List.of(acceptingVerifier("tier"));

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(macaroon, rootKey, verifiers, context)
            ).isInstanceOf(MacaroonVerificationException.class);
        }

        @Test
        @DisplayName("fails when caveat verifiers list is empty but caveats are present")
        void emptyCaveatVerifiersWithCaveatsFails() {
            List<Caveat> caveats = List.of(
                    new Caveat("service", "api"),
                    new Caveat("tier", "premium")
            );
            Macaroon macaroon = createValidMacaroon(rootKey, identifier, "https://example.com", caveats);

            assertThatThrownBy(() ->
                    MacaroonVerifier.verify(macaroon, rootKey, List.of(), context)
            ).isInstanceOf(MacaroonVerificationException.class);
        }
    }
}
