package com.greenharborlabs.l402.lightning.lnd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LndConfigTest {

    @Test
    void validConfig_withAllFields() {
        var config = new LndConfig("localhost", 10009, "/path/tls.cert", "/path/admin.macaroon");

        assertThat(config.host()).isEqualTo("localhost");
        assertThat(config.port()).isEqualTo(10009);
        assertThat(config.tlsCertPath()).isEqualTo("/path/tls.cert");
        assertThat(config.macaroonPath()).isEqualTo("/path/admin.macaroon");
    }

    @Test
    void validConfig_withNullOptionalFields() {
        var config = new LndConfig("localhost", 10009, null, null);

        assertThat(config.tlsCertPath()).isNull();
        assertThat(config.macaroonPath()).isNull();
    }

    @Test
    void shouldRejectNullHost() {
        assertThatThrownBy(() -> new LndConfig(null, 10009, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void shouldRejectBlankHost() {
        assertThatThrownBy(() -> new LndConfig("  ", 10009, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void shouldRejectEmptyHost() {
        assertThatThrownBy(() -> new LndConfig("", 10009, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100, 65536, 70000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void shouldRejectInvalidPort(int port) {
        assertThatThrownBy(() -> new LndConfig("localhost", port, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 443, 10009, 65535})
    void shouldAcceptValidPort(int port) {
        var config = new LndConfig("localhost", port, null, null);
        assertThat(config.port()).isEqualTo(port);
    }
}
