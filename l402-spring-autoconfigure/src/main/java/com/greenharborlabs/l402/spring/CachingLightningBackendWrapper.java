package com.greenharborlabs.l402.spring;

import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import java.time.Duration;

/**
 * Decorates a {@link LightningBackend} to cache the result of {@link #isHealthy()}
 * for a configurable TTL. All other methods delegate directly to the wrapped backend.
 *
 * <p>Thread safety is achieved via {@code volatile} fields; the slight risk of
 * duplicate evaluation on concurrent cache miss is acceptable for a health check.
 */
public class CachingLightningBackendWrapper implements LightningBackend {

    private final LightningBackend delegate;
    private final long ttlNanos;

    private volatile boolean cachedHealthy;
    private volatile long cachedAtNanos;

    public CachingLightningBackendWrapper(LightningBackend delegate, Duration ttl) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be null or negative");
        }
        this.delegate = delegate;
        this.ttlNanos = ttl.toNanos();
        // Force first call to miss the cache
        this.cachedAtNanos = 0;
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
        // nanoTime can wrap, so use subtraction for correct elapsed calculation
        if (cachedAtNanos != 0 && (now - cachedAtNanos) < ttlNanos) {
            return cachedHealthy;
        }
        boolean healthy = delegate.isHealthy();
        cachedHealthy = healthy;
        cachedAtNanos = now;
        return healthy;
    }

    /**
     * Returns the wrapped backend, useful for unwrapping in tests or diagnostics.
     */
    public LightningBackend getDelegate() {
        return delegate;
    }
}
