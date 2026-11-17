package com.ykm.stock.analytics.service.futu;

import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetOwnerPlate;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotGetResearchMorningstarReport;
import com.futu.openapi.pb.QotGetValuationDetail;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.futu.openapi.pb.QotCommon.ValuationType.ValuationType_PE;
import static com.futu.openapi.pb.QotCommon.ValuationIntervalType.ValuationIntervalType_Year5;

class FutuQuoteClientTests {
    @Test
    void financialReportUniverseRequestsBothStocksAndTrustsSeparately() {
        captureCallbacks(0);
        AtomicInteger calls = new AtomicInteger();
        QotGetStaticInfo.Response response = QotGetStaticInfo.Response.newBuilder().setRetType(0).build();
        when(sdk.getStaticInfo(any())).thenAnswer(call -> {
            QotGetStaticInfo.C2S request = call.<QotGetStaticInfo.Request>getArgument(0).getC2S();
            assertThat(request.getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_US_Security_VALUE);
            int serial = calls.incrementAndGet();
            assertThat(request.getSecType()).isEqualTo(serial == 1
                    ? QotCommon.SecurityType.SecurityType_Eqty_VALUE : QotCommon.SecurityType.SecurityType_Trust_VALUE);
            quoteCallback.get().onReply_GetStaticInfo(sdk, serial, response);
            return serial;
        });

        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThat(client.getStockBasicInfo()).isSameAs(response);
            assertThat(client.getTrustBasicInfo()).isSameAs(response);
        }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void snapshotPreservesShareClassSymbolsAndChecksReplySerial() {
        captureCallbacks(0);
        QotGetSecuritySnapshot.Response response = QotGetSecuritySnapshot.Response.newBuilder().setRetType(0).build();
        when(sdk.getSecuritySnapshot(any())).thenAnswer(call -> {
            QotGetSecuritySnapshot.C2S input = call.<QotGetSecuritySnapshot.Request>getArgument(0).getC2S();
            assertThat(input.getSecurityListList()).extracting(QotCommon.Security::getCode).containsExactly("GOOG", "GOOGL", "BRK.B");
            quoteCallback.get().onReply_GetSecuritySnapshot(sdk, 8, response);
            return 8;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThat(client.getSecuritySnapshots(List.of("GOOG", "GOOGL", "BRK.B"))).isSameAs(response);
            doAnswer(call -> {
                quoteCallback.get().onReply_GetSecuritySnapshot(sdk, 8, response);
                return 9;
            }).when(sdk).getSecuritySnapshot(any());
            assertThatThrownBy(() -> client.getSecuritySnapshots(List.of("GOOG"))).hasMessage("FUTU_GET_SECURITY_SNAPSHOT_REPLY_MISMATCH");
        }
        verify(sdk).close();
    }

    @Test
    void snapshotDisconnectFailsImmediately() {
        captureCallbacks(0);
        when(sdk.getSecuritySnapshot(any())).thenAnswer(call -> {
            connectionCallback.get().onDisconnect(sdk, 7);
            return 3;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThatThrownBy(() -> client.getSecuritySnapshots(List.of("AAPL"))).hasMessage("FUTU_DISCONNECTED:7");
        }
    }
    @Test
    void rehabRequestPreservesCurrentSymbolAndMatchesReplySerial() {
        captureCallbacks(0);
        QotRequestRehab.Response response = QotRequestRehab.Response.newBuilder().setRetType(0).build();
        when(sdk.requestRehab(any())).thenAnswer(call -> {
            QotRequestRehab.Request request = call.getArgument(0);
            assertThat(request.getC2S().getSecurity().getCode()).isEqualTo("MAXNQ");
            assertThat(request.getC2S().getSecurity().getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_US_Security_VALUE);
            quoteCallback.get().onReply_RequestRehab(sdk, 3, response);
            return 3;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThat(client.getRehab("MAXNQ")).isSameAs(response);
        }
        verify(sdk).close();
    }

    @Test
    void rehabRejectsLateReplyFromAnotherStock() {
        captureCallbacks(0);
        when(sdk.requestRehab(any())).thenAnswer(call -> {
            quoteCallback.get().onReply_RequestRehab(sdk, 2, QotRequestRehab.Response.newBuilder().setRetType(0).build());
            return 3;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThatThrownBy(() -> client.getRehab("MASK")).hasMessage("FUTU_REQUEST_REHAB_REPLY_MISMATCH");
        }
    }

    @Test
    void rehabDisconnectIsPropagatedImmediately() {
        captureCallbacks(0);
        when(sdk.requestRehab(any())).thenAnswer(call -> {
            connectionCallback.get().onDisconnect(sdk, 7);
            return 3;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThatThrownBy(() -> client.getRehab("MASK")).hasMessage("FUTU_DISCONNECTED:7");
        }
    }

    private final FTAPI_Conn_Qot sdk = mock(FTAPI_Conn_Qot.class);
    private final AtomicReference<FTSPI_Conn> connectionCallback = new AtomicReference<>();
    private final AtomicReference<FTSPI_Qot> quoteCallback = new AtomicReference<>();

    private void captureCallbacks(long connectError) {
        doAnswer(call -> { connectionCallback.set(call.getArgument(0)); return null; }).when(sdk).setConnSpi(any());
        doAnswer(call -> { quoteCallback.set(call.getArgument(0)); return null; }).when(sdk).setQotSpi(any());
        when(sdk.initConnect("127.0.0.1", 11111, false)).thenAnswer(call -> {
            connectionCallback.get().onInitConnect(sdk, connectError, "Connection response");
            return true;
        });
    }

    @Test
    void replyArrivingBeforeDispatchReturnsIsNotLost() {
        captureCallbacks(0);
        QotGetOwnerPlate.Response response = QotGetOwnerPlate.Response.newBuilder().setRetType(0)
                .setS2C(QotGetOwnerPlate.S2C.newBuilder()).build();
        when(sdk.getOwnerPlate(any())).thenAnswer(call -> {
            QotGetOwnerPlate.Request request = call.getArgument(0);
            assertThat(request.getC2S().getSecurityList(0).getCode()).isEqualTo("LEU");
            assertThat(request.getC2S().getSecurityList(0).getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_US_Security_VALUE);
            quoteCallback.get().onReply_GetOwnerPlate(sdk, 1, response);
            return 1;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThat(client.getOwnerPlate(List.of("LEU"))).isSameAs(response);
        }
        verify(sdk).close();
    }

    @Test
    void stockDetailRequestsPreserveSymbolAndDeliverEarlyReplies() {
        captureCallbacks(0);
        QotGetValuationDetail.Response valuation = QotGetValuationDetail.Response.newBuilder().setRetType(0).build();
        QotGetResearchMorningstarReport.Response research = QotGetResearchMorningstarReport.Response.newBuilder().setRetType(0).build();
        when(sdk.getValuationDetail(any())).thenAnswer(call -> {
            QotGetValuationDetail.C2S request = call.<QotGetValuationDetail.Request>getArgument(0).getC2S();
            assertThat(request.getSecurity().getCode()).isEqualTo("BRK.B");
            assertThat(request.getSecurity().getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_US_Security_VALUE);
            assertThat(request.getIntervalType()).isEqualTo(QotCommon.ValuationIntervalType.ValuationIntervalType_Year5);
            assertThat(request.getValuationType()).isEqualTo(ValuationType_PE);
            quoteCallback.get().onReply_GetValuationDetail(sdk, 4, valuation);
            return 4;
        });
        when(sdk.getResearchMorningstarReport(any())).thenAnswer(call -> {
            QotGetResearchMorningstarReport.C2S request = call.<QotGetResearchMorningstarReport.Request>getArgument(0).getC2S();
            assertThat(request.getSecurity().getCode()).isEqualTo("BRK.B");
            assertThat(request.getSecurity().getMarket()).isEqualTo(QotCommon.QotMarket.QotMarket_US_Security_VALUE);
            quoteCallback.get().onReply_GetResearchMorningstarReport(sdk, 5, research);
            return 5;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThat(client.getValuationDetail("BRK.B", ValuationType_PE, ValuationIntervalType_Year5)).isSameAs(valuation);
            assertThat(client.getMorningstarReport("BRK.B")).isSameAs(research);
        }
        verify(sdk).close();
    }

    @Test
    void stockDetailRejectsResponseForAnotherRequestSerial() {
        captureCallbacks(0);
        QotGetValuationDetail.Response valuation = QotGetValuationDetail.Response.newBuilder().setRetType(0).build();
        when(sdk.getValuationDetail(any())).thenAnswer(call -> {
            quoteCallback.get().onReply_GetValuationDetail(sdk, 4, valuation);
            return 5;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThatThrownBy(() -> client.getValuationDetail("AAPL", ValuationType_PE, ValuationIntervalType_Year5))
                    .hasMessage("FUTU_GET_VALUATION_DETAIL_REPLY_MISMATCH");
        }
    }

    @Test
    void financialStatementRequestPreservesTypeCursorAndOriginalCurrency() {
        captureCallbacks(0);
        QotCommon.FinancialStatementsType type = QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow;
        QotGetFinancialsStatements.Response response = QotGetFinancialsStatements.Response.newBuilder().setRetType(0).build();
        when(sdk.getFinancialsStatements(any())).thenAnswer(call -> {
            QotGetFinancialsStatements.C2S request = call.<QotGetFinancialsStatements.Request>getArgument(0).getC2S();
            assertThat(request.getSecurity().getCode()).isEqualTo("AAPL");
            assertThat(request.getStatementType()).isEqualTo(type);
            assertThat(request.getNextKey()).isEqualTo("page-2");
            assertThat(request.getNum()).isEqualTo(20);
            assertThat(request.hasCurrencyCode()).isFalse();
            quoteCallback.get().onReply_GetFinancialsStatements(sdk, 6, response);
            return 6;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThat(client.getFinancialStatements("AAPL", type, "page-2", 20)).isSameAs(response);
        }
    }

    @Test
    void queryTimeoutFailsAndClosesWithoutRetry() {
        captureCallbacks(0);
        when(sdk.getOwnerPlate(any())).thenReturn(1);
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 0)) {
            assertThatThrownBy(() -> client.getOwnerPlate(List.of("LEU"))).hasMessage("FUTU_GET_OWNER_PLATE_TIMEOUT");
        }
        verify(sdk, times(1)).getOwnerPlate(any());
        verify(sdk).close();
    }

    @Test
    void connectionFailurePreservesErrorAndClosesTheConnection() {
        captureCallbacks(8);

        assertThatThrownBy(() -> new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1))
                .hasMessage("FUTU_CONNECT_FAILED:8:Connection response");
        verify(sdk).close();
    }

    @Test
    void disconnectDuringARequestFailsTheWaitingQuery() {
        captureCallbacks(0);
        when(sdk.getOwnerPlate(any())).thenAnswer(call -> {
            connectionCallback.get().onDisconnect(sdk, 9);
            return 1;
        });
        try (FutuQuoteClient client = new FutuQuoteClient(sdk, "127.0.0.1", 11111, 1)) {
            assertThatThrownBy(() -> client.getOwnerPlate(List.of("LEU"))).hasMessage("FUTU_DISCONNECTED:9");
        }
        verify(sdk).close();
    }
}
