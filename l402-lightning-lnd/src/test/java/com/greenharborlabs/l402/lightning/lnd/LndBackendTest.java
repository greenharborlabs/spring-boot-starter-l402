package com.greenharborlabs.l402.lightning.lnd;

import com.google.protobuf.ByteString;
import com.greenharborlabs.l402.core.lightning.Invoice;
import com.greenharborlabs.l402.core.lightning.InvoiceStatus;
import com.greenharborlabs.l402.core.lightning.LightningException;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import lnrpc.LightningGrpc;
import lnrpc.Lnrpc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LndBackendTest {

    private io.grpc.Server grpcServer;
    private ManagedChannel channel;

    private LndBackend startBackendWith(LightningGrpc.LightningImplBase impl) throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(impl)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor()
                .build();
        return new LndBackend(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (grpcServer != null) {
            grpcServer.shutdownNow();
        }
    }

    @Test
    void createInvoice_wrapsStatusRuntimeExceptionInLndException() throws Exception {
        var backend = startBackendWith(new LightningGrpc.LightningImplBase() {
            @Override
            public void addInvoice(Lnrpc.Invoice request, StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("LND node unreachable")
                        .asRuntimeException());
            }
        });

        assertThatThrownBy(() -> backend.createInvoice(100, "test"))
                .isInstanceOf(LndException.class)
                .isInstanceOf(LightningException.class)
                .hasMessageContaining("Failed to create invoice via LND")
                .hasMessageContaining("UNAVAILABLE")
                .hasCauseInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void lookupInvoice_wrapsStatusRuntimeExceptionInLndException() throws Exception {
        var backend = startBackendWith(new LightningGrpc.LightningImplBase() {
            @Override
            public void lookupInvoice(Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("invoice not found")
                        .asRuntimeException());
            }
        });

        byte[] paymentHash = new byte[32];

        assertThatThrownBy(() -> backend.lookupInvoice(paymentHash))
                .isInstanceOf(LndException.class)
                .isInstanceOf(LightningException.class)
                .hasMessageContaining("Failed to lookup invoice via LND")
                .hasMessageContaining("NOT_FOUND")
                .hasCauseInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void createInvoice_returnsInvoiceOnSuccess() throws Exception {
        byte[] rHash = new byte[32];
        rHash[0] = 1;

        var backend = startBackendWith(new LightningGrpc.LightningImplBase() {
            @Override
            public void addInvoice(Lnrpc.Invoice request, StreamObserver<Lnrpc.AddInvoiceResponse> responseObserver) {
                responseObserver.onNext(Lnrpc.AddInvoiceResponse.newBuilder()
                        .setRHash(ByteString.copyFrom(rHash))
                        .setPaymentRequest("lnbc100n1test")
                        .build());
                responseObserver.onCompleted();
            }
        });

        Invoice invoice = backend.createInvoice(100, "test memo");

        assertThat(invoice.paymentHash()).isEqualTo(rHash);
        assertThat(invoice.bolt11()).isEqualTo("lnbc100n1test");
        assertThat(invoice.amountSats()).isEqualTo(100);
        assertThat(invoice.memo()).isEqualTo("test memo");
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.PENDING);
    }

    @Test
    void lookupInvoice_returnsInvoiceOnSuccess() throws Exception {
        byte[] rHash = new byte[32];
        rHash[0] = 2;
        long creationDate = Instant.now().getEpochSecond();

        var backend = startBackendWith(new LightningGrpc.LightningImplBase() {
            @Override
            public void lookupInvoice(Lnrpc.PaymentHash request, StreamObserver<Lnrpc.Invoice> responseObserver) {
                responseObserver.onNext(Lnrpc.Invoice.newBuilder()
                        .setRHash(ByteString.copyFrom(rHash))
                        .setPaymentRequest("lnbc200n1test")
                        .setValue(200)
                        .setMemo("lookup memo")
                        .setState(Lnrpc.Invoice.InvoiceState.SETTLED)
                        .setCreationDate(creationDate)
                        .setExpiry(3600)
                        .build());
                responseObserver.onCompleted();
            }
        });

        Invoice invoice = backend.lookupInvoice(rHash);

        assertThat(invoice.paymentHash()).isEqualTo(rHash);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.SETTLED);
        assertThat(invoice.amountSats()).isEqualTo(200);
    }

    @Test
    void isHealthy_returnsFalseOnGrpcFailure() throws Exception {
        var backend = startBackendWith(new LightningGrpc.LightningImplBase() {
            @Override
            public void getInfo(Lnrpc.GetInfoRequest request, StreamObserver<Lnrpc.GetInfoResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        });

        assertThat(backend.isHealthy()).isFalse();
    }
}
