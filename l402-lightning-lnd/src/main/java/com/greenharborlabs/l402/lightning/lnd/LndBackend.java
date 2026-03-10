package com.greenharborlabs.l402.lightning.lnd;

import com.google.protobuf.ByteString;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningBackend;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;

import java.time.Instant;

/**
 * {@link LightningBackend} implementation backed by an LND node via gRPC.
 */
public class LndBackend implements LightningBackend {

    private final LndConfig config;
    private final LightningGrpc.LightningBlockingStub stub;

    public LndBackend(LndConfig config, ManagedChannel channel) {
        this.config = config;
        this.stub = LightningGrpc.newBlockingStub(channel);
    }

    @Override
    public Invoice createInvoice(long amountSats, String memo) {
        var request = Lnrpc.Invoice.newBuilder()
                .setValue(amountSats)
                .setMemo(memo)
                .build();

        Lnrpc.AddInvoiceResponse addResponse = stub.addInvoice(request);

        // Look up the full invoice to get creation/expiry timestamps
        Lnrpc.Invoice lndInvoice = stub.lookupInvoice(
                Lnrpc.PaymentHash.newBuilder()
                        .setRHash(addResponse.getRHash())
                        .build());

        return mapInvoice(lndInvoice);
    }

    @Override
    public Invoice lookupInvoice(byte[] paymentHash) {
        var request = Lnrpc.PaymentHash.newBuilder()
                .setRHash(ByteString.copyFrom(paymentHash))
                .build();

        Lnrpc.Invoice lndInvoice = stub.lookupInvoice(request);
        return mapInvoice(lndInvoice);
    }

    @Override
    public boolean isHealthy() {
        try {
            Lnrpc.GetInfoResponse info = stub.getInfo(
                    Lnrpc.GetInfoRequest.getDefaultInstance());
            return info.getSyncedToChain();
        } catch (StatusRuntimeException _) {
            return false;
        }
    }

    private static Invoice mapInvoice(Lnrpc.Invoice lndInvoice) {
        Instant createdAt = Instant.ofEpochSecond(lndInvoice.getCreationDate());
        Instant expiresAt = createdAt.plusSeconds(lndInvoice.getExpiry());

        byte[] preimage = null;
        if (!lndInvoice.getRPreimage().isEmpty()) {
            preimage = lndInvoice.getRPreimage().toByteArray();
        }

        return new Invoice(
                lndInvoice.getRHash().toByteArray(),
                lndInvoice.getPaymentRequest(),
                lndInvoice.getValue(),
                lndInvoice.getMemo(),
                mapStatus(lndInvoice.getState()),
                preimage,
                createdAt,
                expiresAt
        );
    }

    private static InvoiceStatus mapStatus(Lnrpc.Invoice.InvoiceState state) {
        return switch (state) {
            case SETTLED -> InvoiceStatus.SETTLED;
            case CANCELED -> InvoiceStatus.CANCELLED;
            case OPEN, ACCEPTED -> InvoiceStatus.PENDING;
            case UNRECOGNIZED -> InvoiceStatus.PENDING;
        };
    }
}
