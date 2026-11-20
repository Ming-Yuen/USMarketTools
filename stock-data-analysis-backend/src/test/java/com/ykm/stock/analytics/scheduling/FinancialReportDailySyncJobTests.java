package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon.F10Type;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetFinancialsStatements.FinancialFieldInfo;
import com.futu.openapi.pb.QotGetFinancialsStatements.FinancialItem;
import com.futu.openapi.pb.QotGetFinancialsStatements.S2C;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Annual;
import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Q1;
import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Q2;
import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Q3;
import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Q4;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_Income;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_MainIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinancialReportDailySyncJobTests {
    private static final Instant OBSERVED_AT = Instant.parse("2026-11-12T00:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockStatus status = new StockStatus();
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final MarketEventRepository marketEvents = mock(MarketEventRepository.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final SecSubmissionsArchive submissionsArchive = mock(SecSubmissionsArchive.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final FutuService futu = mock(FutuService.class);
    private final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
    private final DateTimeService dateTimeService = mock(DateTimeService.class);
    private final FinancialReportDailySyncJob job = new FinancialReportDailySyncJob(
            stocks, futu, statuses, reports, marketEvents, secFacts, submissionsArchive, entityManager,
            Mappers.getMapper(FutuQuoteConverter.class), new TransactionTemplate(transactionManager), dateTimeService);
    private final JobExecutionContext context = mock(JobExecutionContext.class);

    @BeforeEach
    void stubTransactionsAndTime() {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(dateTimeService.now()).thenReturn(OBSERVED_AT);
        when(marketEvents.findEarningsFigisBetween(any(), any())).thenReturn(Set.of());
        when(secFacts.findRecentPeriodicFilingCiks(anyCollection(), any())).thenReturn(Set.of());
    }

    /** Scenario：停抓 MainIndex（statement_type=4），只下載損益、資產負債與現金流量三表。 */
    @Test
    void jobBackfillsThreeCoreStatementsWithoutMainIndexAndKeepsQuarterAndYearSeparate() {
        openAapl();
        Instant secondPageObservedAt = OBSERVED_AT.plusSeconds(1);
        when(dateTimeService.now()).thenReturn(OBSERVED_AT, secondPageObservedAt);
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("older",
                report("2025-09-30", F10Type_Q4), report("2025-09-30", F10Type_Annual)));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, "older", 50)).thenReturn(page("before-start",
                report("2014-01-01", F10Type_Q1), report("2013-12-31", F10Type_Annual)));

        job.execute(context);

        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager, times(3)).persist(written.capture());
        List<FinancialReport> rows = written.getAllValues();
        assertThat(rows).extracting(FinancialReport::getPeriodEnd)
                .containsExactly(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 9, 30), LocalDate.of(2014, 1, 1));
        assertThat(rows).extracting(FinancialReport::getPeriodType).containsExactly(4, 7, 1);
        verify(session, times(4)).financialStatements(eq("AAPL"), any(), nullable(String.class), eq(50));
        verify(session).financialStatements("AAPL", FinancialStatementsType_BalanceSheet, null, 50);
        verify(session).financialStatements("AAPL", FinancialStatementsType_CashFlow, null, 50);
        verify(session, never()).financialStatements(eq("AAPL"), eq(FinancialStatementsType_MainIndex), nullable(String.class), anyInt());
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    /** Scenario：平日僅同步無水位新股或近 14 天財報日曆／SEC 新申報股票；無新事件且已有水位股票跳過富途請求。 */
    @Test
    void weekdayFiltersToNullWatermarkRecentEarningsCalendarOrRecentSecFilings() {
        status.setFigi("F1");
        status.setFinancialReportDate(LocalDate.of(2026, 9, 30));
        StockStatus recentSecStatus = new StockStatus();
        recentSecStatus.setFigi("F2");
        recentSecStatus.setFinancialReportDate(LocalDate.of(2026, 6, 30));
        StockStatus newStockStatus = new StockStatus();
        newStockStatus.setFigi("F3");

        Stock quietStock = stock("F1", "QUIET", "Common Stock");
        quietStock.setCik("0000000001");
        Stock secFiledStock = stock("F2", "NVDA", "Common Stock");
        secFiledStock.setCik("0001045810");
        Stock newStock = stock("F3", "IPO", "Common Stock");

        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(quietStock, status),
                new ActiveStockWithStatus(secFiledStock, recentSecStatus),
                new ActiveStockWithStatus(newStock, newStockStatus)));
        when(secFacts.findRecentPeriodicFilingCiks(anyCollection(), any())).thenReturn(Set.of("0001045810"));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("QUIET", "NVDA", "IPO"));
        when(session.financialStatements(anyString(), any(), isNull(), anyInt())).thenReturn(page("-1"));

        job.execute(context);

        verify(session, never()).financialStatements(eq("QUIET"), any(), nullable(String.class), anyInt());
        verify(session, times(3)).financialStatements(eq("NVDA"), any(), isNull(), eq(10));
        verify(session, times(3)).financialStatements(eq("IPO"), any(), isNull(), eq(50));
    }

    /** Scenario：週六 Cron 觸發時執行全市場巡檢，包含近 14 天無新事件的既有股票。 */
    @Test
    void saturdayPatrolSyncsAllSupportedTrackedStocks() {
        status.setFigi("F1");
        status.setFinancialReportDate(LocalDate.of(2026, 9, 30));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                .thenReturn(List.of(new ActiveStockWithStatus(stock("F1", "AAPL", "Common Stock"), status)));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("AAPL"));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(10))).thenReturn(page("-1"));

        JobExecutionContext saturday = mock(JobExecutionContext.class);
        when(saturday.getTrigger()).thenReturn(mock(CronTrigger.class));
        when(saturday.getScheduledFireTime()).thenReturn(Date.from(Instant.parse("2026-11-14T02:00:00Z")));

        job.execute(saturday);

        verify(session, times(3)).financialStatements(eq("AAPL"), any(), isNull(), eq(10));
    }

    @Test
    void alreadyReturnedOlderReportCorrectsExistingDateWithoutBackfillingUnstoredPeriods() {
        openAapl();
        status.setFinancialReportDate(LocalDate.of(2026, 10, 1));
        when(marketEvents.findEarningsFigisBetween(any(), any())).thenReturn(Set.of("F1"));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(10))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2026-09-25", F10Type_Annual).toBuilder().setDateTime(1790352000L).build();
        QotGetFinancialsStatements.FinancialReport unstored = report("2025-09-26", F10Type_Annual).toBuilder()
                .setDateTime(Instant.parse("2025-09-26T16:00:00Z").getEpochSecond()).build();
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 10)).thenReturn(page("older", raw, unstored));
        FinancialReport old = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2026, 9, 25), OBSERVED_AT.minusSeconds(86400), Map.of(100L, "Total Revenue"));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(old));

        job.execute(context);

        verify(reports).delete(old);
        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager).persist(written.capture());
        assertThat(written.getAllValues()).singleElement().satisfies(row -> assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 26)));
        verify(session, times(3)).financialStatements(eq("AAPL"), any(), isNull(), eq(10));
    }

    @Test
    void financialDateEncodedAtHongKongMidnightDoesNotBecomePreviousUsCalendarDay() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2026-09-25", F10Type_Annual).toBuilder()
                .setDateTime(1790352000L).build();
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        job.execute(context);
        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager).persist(written.capture());
        assertThat(written.getAllValues()).singleElement().satisfies(row -> assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 26)));
    }

    @Test
    void incrementalSyncContinuesToTheInclusiveWatermarkAndDoesNotRegressIt() {
        openAapl();
        status.setFinancialReportDate(LocalDate.of(2026, 9, 30));
        StockStatus snapshot = new StockStatus();
        snapshot.setFinancialReportDate(LocalDate.of(2025, 6, 30));
        when(marketEvents.findEarningsFigisBetween(any(), any())).thenReturn(Set.of("F1"));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                .thenReturn(List.of(new ActiveStockWithStatus(stock("F1", "AAPL", "Common Stock"), snapshot)));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(10))).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 10))
                .thenReturn(page("older", report("2025-09-30", F10Type_Annual)));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, "older", 10))
                .thenReturn(page("stop", report("2025-06-30", F10Type_Q2), report("2025-03-31", F10Type_Q1)));

        job.execute(context);

        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager, times(2)).persist(written.capture());
        assertThat(written.getAllValues()).extracting(FinancialReport::getPeriodEnd)
                .containsExactly(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 6, 30));
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void revisedPayloadReplacesSameKeyAndKeepsFirstAvailability() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2025-09-30", F10Type_Annual);
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        FinancialReport existing = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2025, 9, 30), OBSERVED_AT.minusSeconds(86400), Map.of(100L, "Total Revenue"));
        existing.setItems(List.of(new FinancialReport.Item(100, "Total Revenue", BigDecimal.TEN, null, null),
                existing.getItems().getLast()));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(existing));

        job.execute(context);

        InOrder order = inOrder(entityManager, reports, transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(reports).delete(existing);
        order.verify(reports).flush();
        order.verify(entityManager).persist(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    void unsupportedOtcStockSkipsRemainingStatementsAndContinuesBatch() {
        StockStatus otcStatus = new StockStatus();
        otcStatus.setFigi("BBG000BZMKF4");
        status.setFigi("F1");
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(stock("BBG000BZMKF4", "SCKT", "Common Stock"), otcStatus),
                new ActiveStockWithStatus(stock("F1", "AAPL", "Common Stock"), status)));
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("SCKT", "AAPL"));
        when(session.financialStatements("SCKT", FinancialStatementsType_Income, null, 50)).thenReturn(null);
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50))
                .thenReturn(page("-1", report("2025-09-30", F10Type_Annual)));

        job.execute(context);

        verify(session).financialStatements("SCKT", FinancialStatementsType_Income, null, 50);
        verify(session, never()).financialStatements("SCKT", FinancialStatementsType_BalanceSheet, null, 50);
        verify(session, never()).financialStatements("SCKT", FinancialStatementsType_CashFlow, null, 50);
        verify(statuses, never()).findAllByIdForUpdate(List.of("BBG000BZMKF4"));
        verify(entityManager, times(1)).persist(any());
        assertThat(otcStatus.getFinancialReportDate()).isNull();
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    @Test
    void persistenceFailureDoesNotAdvanceWatermark() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50))
                .thenReturn(page("-1", report("2025-09-30", F10Type_Annual)));
        doThrow(new IllegalStateException("write failed")).when(entityManager).persist(any());

        assertThatThrownBy(() -> job.execute(context)).hasRootCauseMessage("write failed");
        assertThat(status.getFinancialReportDate()).isNull();
        verify(transactionManager).rollback(any());
        verify(session).close();
    }

    private void openAapl() {
        status.setFigi("F1");
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                .thenReturn(List.of(new ActiveStockWithStatus(stock("F1", "AAPL", "Common Stock"), status)));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("AAPL", "TSM"));
    }

    private Stock stock(String figi, String symbol, String type) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setStockType(type);
        return stock;
    }

    private S2C page(String cursor, QotGetFinancialsStatements.FinancialReport... rows) {
        return S2C.newBuilder().setNextKey(cursor).addAllReportList(List.of(rows))
                .addStructureList(FinancialFieldInfo.newBuilder().setFieldId(100).setDisplayName("Total Revenue")).build();
    }

    private QotGetFinancialsStatements.FinancialReport report(String date, F10Type type) {
        return QotGetFinancialsStatements.FinancialReport.newBuilder().setDateTimeStr(date).setFinancialType(type).setCurrencyCode("USD")
                .addItemList(FinancialItem.newBuilder().setFieldId(100).setData(0))
                .addItemList(FinancialItem.newBuilder().setFieldId(101)).build();
    }
}
