package com.greenharborlabs.l402.spring.security;

import com.greenharborlabs.l402.core.lightning.PaymentPreimage;
import com.greenharborlabs.l402.core.macaroon.Caveat;
import com.greenharborlabs.l402.core.macaroon.Macaroon;
import com.greenharborlabs.l402.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.l402.core.protocol.L402Credential;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.security.SecureRandom;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L402AuthenticationTokenTest {

    private static final SecureRandom RNG = new SecureRandom();

    @Test
    void unauthenticatedTokenHoldsRawCredentials() {
        var token = new L402AuthenticationToken("mac-base64", "abcd".repeat(16));

        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.getRawMacaroon()).isEqualTo("mac-base64");
        assertThat(token.getRawPreimage()).isEqualTo("abcd".repeat(16));
        assertThat(token.getTokenId()).isNull();
        assertThat(token.getServiceName()).isNull();
        assertThat(token.getAttributes()).isEmpty();
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void unauthenticatedTokenRedactsSensitiveValues() {
        var token = new L402AuthenticationToken("mac-base64", "abcd".repeat(16));

        assertThat(token.getPrincipal()).isEqualTo("[unauthenticated-l402]");
        assertThat(token.getCredentials()).isEqualTo("[REDACTED]");
    }

    @Test
    void unauthenticatedTokenRejectsNullMacaroon() {
        assertThatThrownBy(() -> new L402AuthenticationToken(null, "abcd".repeat(16)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unauthenticatedTokenRejectsNullPreimage() {
        assertThatThrownBy(() -> new L402AuthenticationToken("mac", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void authenticatedTokenExposesCredentialDetails() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("service", "api"),
                new Caveat("valid_until", "2026-12-31T23:59:59Z")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "my-api");

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getTokenId()).isEqualTo(credential.tokenId());
        assertThat(token.getServiceName()).isEqualTo("my-api");
        assertThat(token.getPrincipal()).isEqualTo(credential.tokenId());
        assertThat(token.getCredentials()).isEqualTo(credential);
        assertThat(token.getL402Credential()).isEqualTo(credential);
    }

    @Test
    void authenticatedTokenHasL402Authority() {
        L402Credential credential = createTestCredential(List.of());
        var token = L402AuthenticationToken.authenticated(credential, "svc");

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_L402");
    }

    @Test
    void authenticatedTokenExtractsCaveatAttributes() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("service", "api"),
                new Caveat("tier", "premium")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "api");

        assertThat(token.getAttributes())
                .containsEntry("tokenId", credential.tokenId())
                .containsEntry("serviceName", "api")
                .containsEntry("service", "api")
                .containsEntry("tier", "premium");
        assertThat(token.getAttribute("tier")).isEqualTo("premium");
        assertThat(token.getAttribute("nonexistent")).isNull();
    }

    @Test
    void builtInAttributesCannotBeOverwrittenByCaveats() {
        L402Credential credential = createTestCredential(List.of(
                new Caveat("tokenId", "attacker-controlled-value"),
                new Caveat("serviceName", "attacker-service")
        ));

        var token = L402AuthenticationToken.authenticated(credential, "trusted-service");

        // Built-in attributes must win over attacker-controlled caveat keys
        assertThat(token.getAttribute("tokenId")).isEqualTo(credential.tokenId());
        assertThat(token.getAttribute("serviceName")).isEqualTo("trusted-service");
    }

    @Test
    void authenticatedTokenWithNullServiceName() {
        L402Credential credential = createTestCredential(List.of());
        var token = L402AuthenticationToken.authenticated(credential, null);

        assertThat(token.getServiceName()).isNull();
        assertThat(token.getAttributes()).doesNotContainKey("serviceName");
        assertThat(token.getAttributes()).containsKey("tokenId");
    }

    private L402Credential createTestCredential(List<Caveat> caveats) {
        byte[] paymentHash = new byte[32];
        byte[] tokenIdBytes = new byte[32];
        RNG.nextBytes(paymentHash);
        RNG.nextBytes(tokenIdBytes);

        var identifier = new MacaroonIdentifier(0, paymentHash, tokenIdBytes);
        byte[] idBytes = MacaroonIdentifier.encode(identifier);
        byte[] sig = new byte[32];
        RNG.nextBytes(sig);

        var macaroon = new Macaroon(idBytes, null, caveats, sig);
        var preimage = new PaymentPreimage(paymentHash); // reuse hash as preimage for test

        String tokenId = java.util.HexFormat.of().formatHex(tokenIdBytes);
        return new L402Credential(macaroon, preimage, tokenId);
    }
}
