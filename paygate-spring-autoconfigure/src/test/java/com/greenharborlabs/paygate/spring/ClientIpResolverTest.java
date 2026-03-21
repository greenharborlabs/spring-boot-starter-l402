package com.greenharborlabs.paygate.spring;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    @Nested
    @DisplayName("when trustForwardedHeaders=false")
    class UntrustedMode {

        private final ClientIpResolver resolver =
                new ClientIpResolver(false, List.of());

        @Test
        @DisplayName("returns remoteAddr ignoring X-Forwarded-For header")
        void returnsRemoteAddrIgnoringXff() {
            var request = new MockHttpServletRequest();
            request.setRemoteAddr("192.168.1.100");
            request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("192.168.1.100");
        }
    }

    @Nested
    @DisplayName("when trustForwardedHeaders=true")
    class TrustedMode {

        @Nested
        @DisplayName("without X-Forwarded-For header")
        class NoXff {

            private final ClientIpResolver resolver =
                    new ClientIpResolver(true, List.of());

            @Test
            @DisplayName("returns remoteAddr when no XFF header present")
            void returnsRemoteAddr() {
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("172.16.0.50");

                String result = resolver.resolve(request);

                assertThat(result).isEqualTo("172.16.0.50");
            }
        }

        @Nested
        @DisplayName("with X-Forwarded-For header")
        class WithXff {

            @Test
            @DisplayName("scans right-to-left skipping trusted proxies and returns first non-trusted entry")
            void rightToLeftScanSkipsTrustedProxies() {
                var resolver = new ClientIpResolver(true, List.of("proxy2"));
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("proxy2");
                request.addHeader("X-Forwarded-For", "client, proxy1, proxy2");

                String result = resolver.resolve(request);

                assertThat(result).isEqualTo("proxy1");
            }

            @Test
            @DisplayName("returns remoteAddr when all XFF entries are trusted")
            void returnsRemoteAddrWhenAllTrusted() {
                var resolver = new ClientIpResolver(true, List.of("client", "proxy1", "proxy2"));
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("10.0.0.1");
                request.addHeader("X-Forwarded-For", "client, proxy1, proxy2");

                String result = resolver.resolve(request);

                assertThat(result).isEqualTo("10.0.0.1");
            }

            @Test
            @DisplayName("returns single XFF value when present")
            void returnsSingleXffValue() {
                var resolver = new ClientIpResolver(true, List.of());
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("10.0.0.1");
                request.addHeader("X-Forwarded-For", "203.0.113.50");

                String result = resolver.resolve(request);

                assertThat(result).isEqualTo("203.0.113.50");
            }

            @Test
            @DisplayName("trims whitespace from XFF entries")
            void trimsWhitespaceFromXffEntries() {
                var resolver = new ClientIpResolver(true, List.of("proxy2"));
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("proxy2");
                request.addHeader("X-Forwarded-For", "  client ,  proxy1 ,  proxy2 ");

                String result = resolver.resolve(request);

                assertThat(result).isEqualTo("proxy1");
            }

            @Test
            @DisplayName("returns remoteAddr when XFF header is empty")
            void returnsRemoteAddrWhenXffEmpty() {
                var resolver = new ClientIpResolver(true, List.of());
                var request = new MockHttpServletRequest();
                request.setRemoteAddr("10.0.0.1");
                request.addHeader("X-Forwarded-For", "");

                String result = resolver.resolve(request);

                assertThat(result).isEqualTo("10.0.0.1");
            }
        }
    }
}
