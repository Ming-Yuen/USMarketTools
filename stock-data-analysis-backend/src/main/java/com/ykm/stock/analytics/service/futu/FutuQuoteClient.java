package com.ykm.stock.analytics.service.futu;

import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotGetOwnerPlate;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotGetPlateSet;
import com.futu.openapi.pb.QotGetValuationPlateStockList;
import com.futu.openapi.pb.QotGetEarningsCalendar;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetResearchMorningstarReport;
import com.futu.openapi.pb.QotGetValuationDetail;
import com.futu.openapi.pb.QotGetSecuritySnapshot;

import java.time.LocalDate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 單次下載共用一條 OpenD 連線；同一連線只循序執行一個請求，結束即關閉。 */
class FutuQuoteClient implements AutoCloseable, FTSPI_Conn, FTSPI_Qot {
    private final FTAPI_Conn_Qot connection;
    private final int timeoutSeconds;
    private final CompletableFuture<Void> connected = new CompletableFuture<>();
    private volatile CompletableFuture<QotGetStaticInfo.Response> staticInfoReply;
    private volatile CompletableFuture<QotGetOwnerPlate.Response> ownerPlateReply;
    private volatile CompletableFuture<QotGetPlateSet.Response> plateSetReply;

    private volatile CompletableFuture<QotGetValuationPlateStockList.Response> valuationReply;
    private volatile CompletableFuture<QotGetEarningsCalendar.Response> earningsReply;
    private volatile CompletableFuture<Reply<QotGetValuationDetail.Response>> valuationDetailReply;
    private volatile CompletableFuture<Reply<QotGetFinancialsStatements.Response>> financialStatementsReply;
    private volatile CompletableFuture<Reply<QotGetResearchMorningstarReport.Response>> morningstarReply;
    private volatile CompletableFuture<Reply<QotRequestRehab.Response>> rehabReply;
    private volatile CompletableFuture<Reply<QotGetSecuritySnapshot.Response>> snapshotReply;

    FutuQuoteClient(String host, int port, int timeoutSeconds) {
        this(newConnection(), host, port, timeoutSeconds);
    }

    FutuQuoteClient(FTAPI_Conn_Qot connection, String host, int port, int timeoutSeconds) {
        this.connection = connection;
        this.timeoutSeconds = timeoutSeconds;
        connection.setClientInfo("stock-industry-sync", 1);
        connection.setConnSpi(this);
        connection.setQotSpi(this);
        try {
            if (!connection.initConnect(host, port, false)) throw new IllegalStateException("FUTU_CONNECT_START_FAILED");
            await(connected, timeoutSeconds, "CONNECT");
        } catch (RuntimeException ex) {
            connection.close();
            throw ex;
        }
    }

    QotGetStaticInfo.Response getStockBasicInfo() {
        return getUsStaticInfo(QotCommon.SecurityType.SecurityType_Eqty_VALUE);
    }

    QotGetStaticInfo.Response getTrustBasicInfo() {
        return getUsStaticInfo(QotCommon.SecurityType.SecurityType_Trust_VALUE);
    }

    private QotGetStaticInfo.Response getUsStaticInfo(int securityType) {
        CompletableFuture<QotGetStaticInfo.Response> reply = new CompletableFuture<>();
        staticInfoReply = reply;
        QotGetStaticInfo.C2S input = QotGetStaticInfo.C2S.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE)
                .setSecType(securityType).build();
        if (connection.getStaticInfo(QotGetStaticInfo.Request.newBuilder().setC2S(input).build()) <= 0)
            throw new IllegalStateException("FUTU_GET_STATIC_INFO_SEND_FAILED");
        return await(reply, timeoutSeconds, "GET_STATIC_INFO");
    }

    QotGetOwnerPlate.Response getOwnerPlate(List<String> symbols) {
        CompletableFuture<QotGetOwnerPlate.Response> reply = new CompletableFuture<>();
        ownerPlateReply = reply;
        QotGetOwnerPlate.C2S.Builder input = QotGetOwnerPlate.C2S.newBuilder();
        for (String symbol : symbols) input.addSecurityList(QotCommon.Security.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setCode(symbol));
        if (connection.getOwnerPlate(QotGetOwnerPlate.Request.newBuilder().setC2S(input).build()) <= 0)
            throw new IllegalStateException("FUTU_GET_OWNER_PLATE_SEND_FAILED");
        return await(reply, timeoutSeconds, "GET_OWNER_PLATE");
    }

    QotGetPlateSet.Response getPlateSet(int type) {
        CompletableFuture<QotGetPlateSet.Response> reply = new CompletableFuture<>();
        plateSetReply = reply;
        QotGetPlateSet.C2S input = QotGetPlateSet.C2S.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setPlateSetType(type).build();
        if (connection.getPlateSet(QotGetPlateSet.Request.newBuilder().setC2S(input).build()) <= 0)
            throw new IllegalStateException("FUTU_GET_PLATE_SET_SEND_FAILED");
        return await(reply, timeoutSeconds, "GET_PLATE_SET");
    }

    QotGetValuationPlateStockList.Response getValuationPage(String plate, QotCommon.ValuationType metric, String cursor) {
        CompletableFuture<QotGetValuationPlateStockList.Response> reply = new CompletableFuture<>();
        valuationReply = reply;
        QotGetValuationPlateStockList.C2S.Builder input = QotGetValuationPlateStockList.C2S.newBuilder()
                .setSecurity(QotCommon.Security.newBuilder().setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setCode(plate))
                .setValuationType(metric).setNum(50);
        if (cursor != null) input.setNextKey(cursor);
        if (connection.getValuationPlateStockList(QotGetValuationPlateStockList.Request.newBuilder().setC2S(input).build()) <= 0)
            throw new IllegalStateException("FUTU_GET_VALUATION_PLATE_STOCK_LIST_SEND_FAILED");
        return await(reply, timeoutSeconds, "GET_VALUATION_PLATE_STOCK_LIST");
    }

    QotGetEarningsCalendar.Response getEarningsCalendar(LocalDate from, LocalDate through) {
        CompletableFuture<QotGetEarningsCalendar.Response> reply = new CompletableFuture<>();
        earningsReply = reply;
        QotGetEarningsCalendar.C2S input = QotGetEarningsCalendar.C2S.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE)
                .setBeginDate(from.toString()).setEndDate(through.toString()).build();
        if (connection.getEarningsCalendar(QotGetEarningsCalendar.Request.newBuilder().setC2S(input).build()) <= 0)
            throw new IllegalStateException("FUTU_GET_EARNINGS_CALENDAR_SEND_FAILED");
        return await(reply, timeoutSeconds, "GET_EARNINGS_CALENDAR");
    }

    QotGetValuationDetail.Response getValuationDetail(String symbol, QotCommon.ValuationType metric,
                                                      QotCommon.ValuationIntervalType interval) {
        CompletableFuture<Reply<QotGetValuationDetail.Response>> reply = new CompletableFuture<>();
        valuationDetailReply = reply;
        QotGetValuationDetail.C2S input = QotGetValuationDetail.C2S.newBuilder().setSecurity(usStock(symbol))
                .setValuationType(metric).setIntervalType(interval).build();
        int serial = connection.getValuationDetail(QotGetValuationDetail.Request.newBuilder().setC2S(input).build());
        return awaitReply(reply, serial, "GET_VALUATION_DETAIL");
    }

    QotGetFinancialsStatements.Response getFinancialStatements(String symbol, QotCommon.FinancialStatementsType statement,
                                                               String cursor, int size) {
        CompletableFuture<Reply<QotGetFinancialsStatements.Response>> reply = new CompletableFuture<>();
        financialStatementsReply = reply;
        QotGetFinancialsStatements.C2S.Builder input = QotGetFinancialsStatements.C2S.newBuilder()
                .setSecurity(usStock(symbol)).setStatementType(statement)
                .setFinancialType(QotCommon.F10Type.F10Type_QuarterlyAnnual).setNum(size);
        if (cursor != null) input.setNextKey(cursor);
        int serial = connection.getFinancialsStatements(QotGetFinancialsStatements.Request.newBuilder().setC2S(input).build());
        return awaitReply(reply, serial, "GET_FINANCIALS_STATEMENTS");
    }

    QotGetResearchMorningstarReport.Response getMorningstarReport(String symbol) {
        CompletableFuture<Reply<QotGetResearchMorningstarReport.Response>> reply = new CompletableFuture<>();
        morningstarReply = reply;
        QotGetResearchMorningstarReport.C2S input = QotGetResearchMorningstarReport.C2S.newBuilder().setSecurity(usStock(symbol)).build();
        int serial = connection.getResearchMorningstarReport(QotGetResearchMorningstarReport.Request.newBuilder().setC2S(input).build());
        return awaitReply(reply, serial, "GET_MORNINGSTAR_REPORT");
    }

    private static QotCommon.Security usStock(String symbol) {
        if (symbol == null || symbol.isBlank() || !symbol.equals(symbol.strip()))
            throw new IllegalArgumentException("FUTU_SYMBOL_INVALID");
        return QotCommon.Security.newBuilder().setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setCode(symbol).build();
    }

    QotGetSecuritySnapshot.Response getSecuritySnapshots(List<String> symbols) {
        CompletableFuture<Reply<QotGetSecuritySnapshot.Response>> reply = new CompletableFuture<>();
        snapshotReply = reply;
        QotGetSecuritySnapshot.C2S.Builder input = QotGetSecuritySnapshot.C2S.newBuilder();
        for (String symbol : symbols) input.addSecurityList(usStock(symbol));
        int serial = connection.getSecuritySnapshot(QotGetSecuritySnapshot.Request.newBuilder().setC2S(input).build());
        return awaitReply(reply, serial, "GET_SECURITY_SNAPSHOT");
    }

    @Override
    public void onReply_GetSecuritySnapshot(FTAPI_Conn client, int serialNo, QotGetSecuritySnapshot.Response response) {
        CompletableFuture<Reply<QotGetSecuritySnapshot.Response>> reply = snapshotReply;
        if (reply != null) reply.complete(new Reply<>(serialNo, response));
    }

    QotRequestRehab.Response getRehab(String symbol) {
        CompletableFuture<Reply<QotRequestRehab.Response>> reply = new CompletableFuture<>();
        rehabReply = reply;
        QotRequestRehab.C2S input = QotRequestRehab.C2S.newBuilder().setSecurity(usStock(symbol)).build();
        int serial = connection.requestRehab(QotRequestRehab.Request.newBuilder().setC2S(input).build());
        return awaitReply(reply, serial, "REQUEST_REHAB");
    }

    @Override
    public void onReply_RequestRehab(FTAPI_Conn client, int serialNo, QotRequestRehab.Response response) {
        CompletableFuture<Reply<QotRequestRehab.Response>> reply = rehabReply;
        if (reply != null) reply.complete(new Reply<>(serialNo, response));
    }

    private <T> T awaitReply(CompletableFuture<Reply<T>> reply, int serial, String operation) {
        if (serial <= 0) throw new IllegalStateException("FUTU_" + operation + "_SEND_FAILED");
        Reply<T> received = await(reply, timeoutSeconds, operation);
        // 回覆可能先於 dispatch 返回，核對序號後才交付給本次股票請求。
        if (received.serial() != serial) throw new IllegalStateException("FUTU_" + operation + "_REPLY_MISMATCH");
        return received.response();
    }

    private record Reply<T>(int serial, T response) {}

    @Override
    public void onReply_GetValuationDetail(FTAPI_Conn client, int serialNo, QotGetValuationDetail.Response response) {
        CompletableFuture<Reply<QotGetValuationDetail.Response>> reply = valuationDetailReply;
        if (reply != null) reply.complete(new Reply<>(serialNo, response));
    }

    @Override
    public void onReply_GetFinancialsStatements(FTAPI_Conn client, int serialNo, QotGetFinancialsStatements.Response response) {
        CompletableFuture<Reply<QotGetFinancialsStatements.Response>> reply = financialStatementsReply;
        if (reply != null) reply.complete(new Reply<>(serialNo, response));
    }

    @Override
    public void onReply_GetResearchMorningstarReport(FTAPI_Conn client, int serialNo, QotGetResearchMorningstarReport.Response response) {
        CompletableFuture<Reply<QotGetResearchMorningstarReport.Response>> reply = morningstarReply;
        if (reply != null) reply.complete(new Reply<>(serialNo, response));
    }

    @Override
    public void onReply_GetValuationPlateStockList(FTAPI_Conn client, int serialNo, QotGetValuationPlateStockList.Response response) {
        CompletableFuture<QotGetValuationPlateStockList.Response> reply = valuationReply;
        if (reply != null) reply.complete(response);
    }

    @Override
    public void onReply_GetEarningsCalendar(FTAPI_Conn client, int serialNo, QotGetEarningsCalendar.Response response) {
        CompletableFuture<QotGetEarningsCalendar.Response> reply = earningsReply;
        if (reply != null) reply.complete(response);
    }

    @Override
    public void onInitConnect(FTAPI_Conn client, long errCode, String description) {
        if (errCode == 0) connected.complete(null);
        else connected.completeExceptionally(new IllegalStateException("FUTU_CONNECT_FAILED:" + errCode + ":" + description));
    }

    @Override
    public void onDisconnect(FTAPI_Conn client, long errCode) {
        IllegalStateException failure = new IllegalStateException("FUTU_DISCONNECTED:" + errCode);
        connected.completeExceptionally(failure);
        CompletableFuture<QotGetStaticInfo.Response> staticReply = staticInfoReply;
        if (staticReply != null) staticReply.completeExceptionally(failure);
        CompletableFuture<QotGetOwnerPlate.Response> ownerReply = ownerPlateReply;
        if (ownerReply != null) ownerReply.completeExceptionally(failure);
        CompletableFuture<QotGetPlateSet.Response> dictionaryReply = plateSetReply;
        if (dictionaryReply != null) dictionaryReply.completeExceptionally(failure);
        CompletableFuture<QotGetValuationPlateStockList.Response> valuation = valuationReply;
        if (valuation != null) valuation.completeExceptionally(failure);
        CompletableFuture<QotGetEarningsCalendar.Response> earnings = earningsReply;
        if (earnings != null) earnings.completeExceptionally(failure);
        CompletableFuture<Reply<QotGetValuationDetail.Response>> detail = valuationDetailReply;
        if (detail != null) detail.completeExceptionally(failure);
        CompletableFuture<Reply<QotGetFinancialsStatements.Response>> statements = financialStatementsReply;
        if (statements != null) statements.completeExceptionally(failure);
        CompletableFuture<Reply<QotGetResearchMorningstarReport.Response>> morningstar = morningstarReply;
        if (morningstar != null) morningstar.completeExceptionally(failure);
        CompletableFuture<Reply<QotRequestRehab.Response>> rehab = rehabReply;
        if (rehab != null) rehab.completeExceptionally(failure);
        CompletableFuture<Reply<QotGetSecuritySnapshot.Response>> snapshot = snapshotReply;
        if (snapshot != null) snapshot.completeExceptionally(failure);
    }

    @Override
    public void onReply_GetStaticInfo(FTAPI_Conn client, int serialNo, QotGetStaticInfo.Response response) {
        CompletableFuture<QotGetStaticInfo.Response> reply = staticInfoReply;
        if (reply != null) reply.complete(response);
    }

    @Override
    public void onReply_GetOwnerPlate(FTAPI_Conn client, int serialNo, QotGetOwnerPlate.Response response) {
        CompletableFuture<QotGetOwnerPlate.Response> reply = ownerPlateReply;
        if (reply != null) reply.complete(response);
    }

    @Override
    public void onReply_GetPlateSet(FTAPI_Conn client, int serialNo, QotGetPlateSet.Response response) {
        CompletableFuture<QotGetPlateSet.Response> reply = plateSetReply;
        if (reply != null) reply.complete(response);
    }

    static <T> T await(CompletableFuture<T> reply, int timeoutSeconds, String operation) {
        try { return reply.get(timeoutSeconds, TimeUnit.SECONDS); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FUTU_" + operation + "_INTERRUPTED", ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("FUTU_" + operation + "_TIMEOUT", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("FUTU_" + operation + "_FAILED", ex.getCause());
        }
    }

    @Override
    public void close() { connection.close(); }

    private static FTAPI_Conn_Qot newConnection() {
        Sdk.initialize();
        return new FTAPI_Conn_Qot();
    }

    private static class Sdk {
        static { FTAPI.init(); }
        static void initialize() {}
    }
}
