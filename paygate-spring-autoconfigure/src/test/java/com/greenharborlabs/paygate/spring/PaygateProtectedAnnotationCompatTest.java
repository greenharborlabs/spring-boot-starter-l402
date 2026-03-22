package com.greenharborlabs.paygate.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Backward-compatibility test verifying that {@link PaygateProtected} annotation
 * continues to work unchanged after the dual-protocol refactoring.
 *
 * <p>Uses real auto-configuration (not manual bean wiring) to test the full
 * annotation scanning path: auto-config creates {@link PaygateEndpointRegistry},
 * which calls {@code scanAnnotatedEndpoints}, discovering annotated controller methods.
 *
 * <p>Runs in L402-only mode with no MPP properties set, proving the annotation
 * contract is preserved for existing users who have not opted into dual-protocol.
 */
@SpringBootTest(
        classes = PaygateProtectedAnnotationCompatTest.TestApp.class,
        properties = {
                "paygate.enabled=true",
                "paygate.backend=lnbits",
                "paygate.root-key-store=memory",
                "paygate.test-mode=true",
                "paygate.service-name=compat-test-service",
                "paygate.default-timeout-seconds=3600"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("@PaygateProtected annotation backward compatibility")
class PaygateProtectedAnnotationCompatTest {

    private static final String PATH_DEFAULT = "/api/compat/default";
    private static final String PATH_CUSTOM_PRICE = "/api/compat/custom-price";
    private static final String PATH_CUSTOM_TIMEOUT = "/api/compat/custom-timeout";
    private static final String PATH_WITH_CAPABILITY = "/api/compat/with-capability";
    private static final String PATH_WITH_DESCRIPTION = "/api/compat/with-description";
    private static final String PATH_UNPROTECTED = "/api/compat/public";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaygateEndpointRegistry endpointRegistry;

    // -----------------------------------------------------------------------
    // Test application — uses real auto-configuration via @EnableAutoConfiguration
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration")
    static class TestApp {

        @RestController
        static class CompatController {

            @PaygateProtected(priceSats = 10)
            @GetMapping(PATH_DEFAULT)
            String defaultEndpoint() {
                return "default-content";
            }

            @PaygateProtected(priceSats = 50)
            @GetMapping(PATH_CUSTOM_PRICE)
            String customPriceEndpoint() {
                return "custom-price-content";
            }

            @PaygateProtected(priceSats = 10, timeoutSeconds = 300)
            @GetMapping(PATH_CUSTOM_TIMEOUT)
            String customTimeoutEndpoint() {
                return "custom-timeout-content";
            }

            @PaygateProtected(priceSats = 10, capability = "premium")
            @PostMapping(PATH_WITH_CAPABILITY)
            String capabilityEndpoint() {
                return "capability-content";
            }

            @PaygateProtected(priceSats = 10, description = "Custom desc")
            @GetMapping(PATH_WITH_DESCRIPTION)
            String descriptionEndpoint() {
                return "description-content";
            }

            @GetMapping(PATH_UNPROTECTED)
            String publicEndpoint() {
                return "public-content";
            }
        }
    }

    // -----------------------------------------------------------------------
    // Registry discovery tests — verify annotation scanning finds all endpoints
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("endpoint registry discovery")
    class RegistryDiscovery {

        @Test
        @DisplayName("default @PaygateProtected is discovered with priceSats=10")
        void defaultAnnotationDiscovered() {
            var config = endpointRegistry.findConfig("GET", PATH_DEFAULT);
            assertThat(config).isNotNull();
            assertThat(config.priceSats()).isEqualTo(10L);
        }

        @Test
        @DisplayName("default @PaygateProtected resolves sentinel timeout to global default (3600)")
        void defaultAnnotationTimeout() {
            var config = endpointRegistry.findConfig("GET", PATH_DEFAULT);
            assertThat(config).isNotNull();
            assertThat(config.timeoutSeconds()).isEqualTo(3600L);
        }

        @Test
        @DisplayName("default @PaygateProtected has empty description when none specified")
        void defaultAnnotationDescription() {
            var config = endpointRegistry.findConfig("GET", PATH_DEFAULT);
            assertThat(config).isNotNull();
            assertThat(config.description()).isEmpty();
        }

        @Test
        @DisplayName("default @PaygateProtected has empty capability")
        void defaultAnnotationCapability() {
            var config = endpointRegistry.findConfig("GET", PATH_DEFAULT);
            assertThat(config).isNotNull();
            assertThat(config.capability()).isEmpty();
        }

        @Test
        @DisplayName("custom priceSats=50 is preserved in registry")
        void customPriceDiscovered() {
            var config = endpointRegistry.findConfig("GET", PATH_CUSTOM_PRICE);
            assertThat(config).isNotNull();
            assertThat(config.priceSats()).isEqualTo(50L);
        }

        @Test
        @DisplayName("custom timeoutSeconds=300 is preserved (not replaced by default)")
        void customTimeoutDiscovered() {
            var config = endpointRegistry.findConfig("GET", PATH_CUSTOM_TIMEOUT);
            assertThat(config).isNotNull();
            assertThat(config.timeoutSeconds()).isEqualTo(300L);
        }

        @Test
        @DisplayName("capability='premium' is preserved in registry")
        void capabilityDiscovered() {
            var config = endpointRegistry.findConfig("POST", PATH_WITH_CAPABILITY);
            assertThat(config).isNotNull();
            assertThat(config.capability()).isEqualTo("premium");
        }

        @Test
        @DisplayName("description='Custom desc' is preserved in registry")
        void descriptionDiscovered() {
            var config = endpointRegistry.findConfig("GET", PATH_WITH_DESCRIPTION);
            assertThat(config).isNotNull();
            assertThat(config.description()).isEqualTo("Custom desc");
        }

        @Test
        @DisplayName("unprotected endpoint is NOT in the registry")
        void unprotectedNotInRegistry() {
            var config = endpointRegistry.findConfig("GET", PATH_UNPROTECTED);
            assertThat(config).isNull();
        }

        @Test
        @DisplayName("registry contains exactly 5 protected endpoints")
        void registrySize() {
            assertThat(endpointRegistry.size()).isEqualTo(5);
        }
    }

    // -----------------------------------------------------------------------
    // HTTP behavior tests — verify 402 for protected, 200 for unprotected
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("HTTP response behavior")
    class HttpBehavior {

        @Test
        @DisplayName("default protected endpoint returns 402 without auth")
        void defaultEndpointReturns402() throws Exception {
            mockMvc.perform(get(PATH_DEFAULT))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("custom-price endpoint returns 402 without auth")
        void customPriceEndpointReturns402() throws Exception {
            mockMvc.perform(get(PATH_CUSTOM_PRICE))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("custom-timeout endpoint returns 402 without auth")
        void customTimeoutEndpointReturns402() throws Exception {
            mockMvc.perform(get(PATH_CUSTOM_TIMEOUT))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("capability endpoint returns 402 without auth")
        void capabilityEndpointReturns402() throws Exception {
            mockMvc.perform(post(PATH_WITH_CAPABILITY))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("description endpoint returns 402 without auth")
        void descriptionEndpointReturns402() throws Exception {
            mockMvc.perform(get(PATH_WITH_DESCRIPTION))
                    .andExpect(status().isPaymentRequired());
        }

        @Test
        @DisplayName("unprotected endpoint returns 200")
        void unprotectedEndpointReturns200() throws Exception {
            mockMvc.perform(get(PATH_UNPROTECTED))
                    .andExpect(status().isOk())
                    .andExpect(content().string("public-content"));
        }
    }
}
