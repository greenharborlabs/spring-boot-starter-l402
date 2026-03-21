package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ClientIpCaveatVerifier")
class ClientIpCaveatVerifierTest {

    private ClientIpCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ClientIpCaveatVerifier(50);
    }

    @Test
    @DisplayName("getKey returns 'client_ip'")
    void getKeyReturnsClientIp() {
        assertThat(verifier.getKey()).isEqualTo("client_ip");
    }

    // ---------------------------------------------------------------
    // Acceptance scenario 1 & 2: single IP matching
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("single IP matching")
    class SingleIpMatching {

        @Test
        @DisplayName("1: client_ip=192.168.1.100 accepts request from 192.168.1.100")
        void exactIpv4Match() {
            Caveat caveat = new Caveat("client_ip", "192.168.1.100");
            L402VerificationContext context = contextWithClientIp("192.168.1.100");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("2: client_ip=192.168.1.100 rejects request from 10.0.0.1")
        void ipv4Mismatch() {
            Caveat caveat = new Caveat("client_ip", "192.168.1.100");
            L402VerificationContext context = contextWithClientIp("10.0.0.1");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    // ---------------------------------------------------------------
    // Acceptance scenario 3: comma-separated IPs
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("multiple IPs (comma-separated)")
    class MultipleIps {

        @Test
        @DisplayName("3: client_ip=192.168.1.100,10.0.0.1 accepts request from 10.0.0.1")
        void commaSeparatedMatchesSecondIp() {
            Caveat caveat = new Caveat("client_ip", "192.168.1.100,10.0.0.1");
            L402VerificationContext context = contextWithClientIp("10.0.0.1");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("comma-separated list accepts request matching first IP")
        void commaSeparatedMatchesFirstIp() {
            Caveat caveat = new Caveat("client_ip", "192.168.1.100,10.0.0.1");
            L402VerificationContext context = contextWithClientIp("192.168.1.100");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("comma-separated list rejects IP not in list")
        void commaSeparatedRejectsUnlistedIp() {
            Caveat caveat = new Caveat("client_ip", "192.168.1.100,10.0.0.1");
            L402VerificationContext context = contextWithClientIp("172.16.0.1");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    // ---------------------------------------------------------------
    // Acceptance scenario 4: IPv6
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("IPv6 matching")
    class Ipv6Matching {

        @Test
        @DisplayName("4: client_ip=2001:db8::1 accepts request from 2001:db8::1")
        void exactIpv6Match() {
            Caveat caveat = new Caveat("client_ip", "2001:db8::1");
            L402VerificationContext context = contextWithClientIp("2001:db8::1");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("different IPv6 representations do NOT match (exact string comparison)")
        void differentIpv6RepresentationsDoNotMatch() {
            Caveat caveat = new Caveat("client_ip", "2001:db8::1");
            L402VerificationContext context = contextWithClientIp("2001:0db8:0000:0000:0000:0000:0000:0001");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("IPv6 loopback ::1 matches exactly")
        void ipv6LoopbackMatch() {
            Caveat caveat = new Caveat("client_ip", "::1");
            L402VerificationContext context = contextWithClientIp("::1");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty caveat value (client_ip=,,,) rejects")
        void emptyCaveatValueRejects() {
            Caveat caveat = new Caveat("client_ip", ",,,");
            L402VerificationContext context = contextWithClientIp("192.168.1.100");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("missing request.client_ip in context rejects (fail-closed)")
        void missingRequestClientIpRejects() {
            Caveat caveat = new Caveat("client_ip", "192.168.1.100");
            L402VerificationContext context = L402VerificationContext.builder()
                    .requestMetadata(Map.of())
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("exceeding maxValuesPerCaveat rejects")
        void exceedingMaxValuesRejects() {
            ClientIpCaveatVerifier restrictedVerifier = new ClientIpCaveatVerifier(2);
            Caveat caveat = new Caveat("client_ip", "192.168.1.1,192.168.1.2,192.168.1.3");
            L402VerificationContext context = contextWithClientIp("192.168.1.1");

            assertThatThrownBy(() -> restrictedVerifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("whitespace around comma-separated values is trimmed")
        void whitespaceTrimmed() {
            Caveat caveat = new Caveat("client_ip", " 192.168.1.100 , 10.0.0.1 ");
            L402VerificationContext context = contextWithClientIp("10.0.0.1");

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("semantically empty value after trim (client_ip= , , ) rejects")
        void semanticallyEmptyAfterTrimRejects() {
            Caveat caveat = new Caveat("client_ip", " , , ");
            L402VerificationContext context = contextWithClientIp("192.168.1.100");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("malformed IP value naturally fails matching (no special handling)")
        void malformedIpFailsMatching() {
            Caveat caveat = new Caveat("client_ip", "not-an-ip");
            L402VerificationContext context = contextWithClientIp("192.168.1.100");

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    // ---------------------------------------------------------------
    // Monotonic restriction (US4)
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("monotonic restriction (US4)")
    class MonotonicRestriction {

        @Test
        @DisplayName("US4-5: client_ip=1.1.1.1,2.2.2.2 → client_ip=1.1.1.1 is subset (accepted)")
        void subsetIpsIsMoreRestrictive() {
            Caveat previous = new Caveat("client_ip", "1.1.1.1,2.2.2.2");
            Caveat current = new Caveat("client_ip", "1.1.1.1");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("client_ip=1.1.1.1 → client_ip=1.1.1.1,3.3.3.3 is superset (rejected)")
        void supersetIpsIsNotMoreRestrictive() {
            Caveat previous = new Caveat("client_ip", "1.1.1.1");
            Caveat current = new Caveat("client_ip", "1.1.1.1,3.3.3.3");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("identical IP sets are accepted (not broadening)")
        void identicalIpSetsAccepted() {
            Caveat previous = new Caveat("client_ip", "1.1.1.1,2.2.2.2");
            Caveat current = new Caveat("client_ip", "1.1.1.1,2.2.2.2");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("single IP to same single IP is accepted")
        void singleToSameAccepted() {
            Caveat previous = new Caveat("client_ip", "10.0.0.1");
            Caveat current = new Caveat("client_ip", "10.0.0.1");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("disjoint IP sets (no overlap) is rejected")
        void disjointIpSetsRejected() {
            Caveat previous = new Caveat("client_ip", "1.1.1.1");
            Caveat current = new Caveat("client_ip", "2.2.2.2");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------

    private static L402VerificationContext contextWithClientIp(String ip) {
        return L402VerificationContext.builder()
                .requestMetadata(Map.of(VerificationContextKeys.REQUEST_CLIENT_IP, ip))
                .build();
    }
}
