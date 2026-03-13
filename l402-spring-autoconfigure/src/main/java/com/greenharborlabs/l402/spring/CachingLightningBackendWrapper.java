package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import java.time.Duration;

/**
 * Decorates a {@link LightningBackend} to cache the result of {@link #isHealthy()}
 * for a configurable TTL. All other methods delegate directly to the wrapped backend.
 *
 * <p>Thread safety is achieved via a single {@code volatile} snapshot record;
 * the slight risk of duplicate evaluation on concurrent cache miss is acceptable
 * for a health check.
 */
public class CachingLightningBackendWrapper implements LightningBackend {

    private record HealthSnapshot(boolean healthy, long atNanos) {}

    private final LightningBackend delegate;
    private final long ttlNanos;

    private volatile HealthSnapshot snapshot = new HealthSnapshot(false, 0);

    public CachingLightningBackendWrapper(LightningBackend delegate, Duration ttl) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be null or negative");
        }
        this.delegate = delegate;
        this.ttlNanos = ttl.toNanos();
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        return delegate.createInvoice(amountSats, memo);
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        return delegate.lookupInvoice(paymentHash);
    }

    @Override
    public boolean isHealthy() {
        long now = System.nanoTime();
        HealthSnapshot current = snapshot;
        // nanoTime can wrap, so use subtraction for correct elapsed calculation
        if (current.atNanos() != 0 && (now - current.atNanos()) < ttlNanos) {
            return current.healthy();
        }
        boolean healthy = delegate.isHealthy();
        snapshot = new HealthSnapshot(healthy, now);
        return healthy;
    }

    /**
     * Returns the wrapped backend, useful for unwrapping in tests or diagnostics.
     */
    public LightningBackend getDelegate() {
        return delegate;
    }
}
