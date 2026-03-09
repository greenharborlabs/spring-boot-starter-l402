package com.greenharborlabs.l402.core.lightning;

public interface LightningBackend {
    Invoice createInvoice(long amountSats, String memo);
    Invoice lookupInvoice(byte[] paymentHash);
    boolean isHealthy();
}
