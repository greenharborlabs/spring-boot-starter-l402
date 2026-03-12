package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link L402LightningHealthIndicator}.
 *
 * <p>Verifies healthy/unhealthy mapping, exception handling, and TTL-based caching.
 */
@DisplayName("L402LightningHealthIndicator")
class L402LightningHealthIndicatorTest {

    @Nested
    @DisplayName("health status mapping")
    class HealthStatusMapping {

        @Test
        @DisplayName("returns UP when backend is healthy")
        void returnsUpWhenHealthy() {
            var indicator = new L402LightningHealthIndicator(
                    new ControllableBackend(true), 10_000);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("backend", "reachable");
        }

        @Test
        @DisplayName("returns DOWN when backend is unhealthy")
        void returnsDownWhenUnhealthy() {
            var indicator = new L402LightningHealthIndicator(
                    new ControllableBackend(false), 10_000);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("backend", "unreachable");
        }

        @Test
        @DisplayName("returns DOWN with exception detail when backend throws")
        void returnsDownOnException() {
            var backend = new ControllableBackend(true) {
                @Override
                public boolean isHealthy() {
                    throw new RuntimeException("connection refused");
                }
            };
            var indicator = new L402LightningHealthIndicator(backend, 10_000);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("backend", "error");
        }
    }

    @Nested
    @DisplayName("caching behavior")
    class CachingBehavior {

        @Test
        @DisplayName("returns cached result within TTL window")
        void returnsCachedWithinTtl() {
            var backend = new CountingBackend(true);
            var indicator = new L402LightningHealthIndicator(backend, 60_000);

            indicator.health();
            indicator.health();
            indicator.health();

            assertThat(backend.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("re-checks backend after TTL expires")
        void reChecksAfterTtlExpires() {
            var backend = new CountingBackend(true);
            // TTL of 0 means every call re-checks
            var indicator = new L402LightningHealthIndicator(backend, 0);

            indicator.health();
            indicator.health();

            assertThat(backend.callCount).isEqualTo(2);
        }

        @Test
        @DisplayName("reflects updated backend status after TTL expires")
        void reflectsUpdatedStatusAfterTtl() {
            var backend = new CountingBackend(true);
            var indicator = new L402LightningHealthIndicator(backend, 0);

            Health first = indicator.health();
            assertThat(first.getStatus()).isEqualTo(Status.UP);

            backend.healthy = false;
            Health second = indicator.health();
            assertThat(second.getStatus()).isEqualTo(Status.DOWN);
        }
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    static class ControllableBackend implements LightningBackend {

        private final boolean healthy;

        ControllableBackend(boolean healthy) {
            this.healthy = healthy;
        }

        @Override
        public Invoice createInvoice(long amountSats, String memo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Invoice lookupInvoice(byte[] paymentHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }
    }

    static class CountingBackend extends ControllableBackend {

        volatile int callCount;
        volatile boolean healthy;

        CountingBackend(boolean healthy) {
            super(healthy);
            this.healthy = healthy;
        }

        @Override
        public boolean isHealthy() {
            callCount++;
            return healthy;
        }
    }
}
