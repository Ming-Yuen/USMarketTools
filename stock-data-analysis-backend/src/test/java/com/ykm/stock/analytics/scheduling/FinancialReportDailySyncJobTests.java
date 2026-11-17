package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon.F10Type;
import com.futu.openapi.pb.QotGetFinancialsStatements.FinancialFieldInfo;
import com.futu.openapi.pb.QotGetFinancialsStatements.FinancialItem;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetFinancialsStatements.S2C;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mapstruct.factory.Mappers;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.futu.openapi.pb.QotCommon.F10Type.*;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinancialReportDailySyncJobTests {
    @Test
    void alreadyReturnedOlderReportCorrectsExistingDateWithoutBackfillingUnstoredPeriods() {
        openAapl();
        status.setFinancialReportDate(LocalDate.of(2026, 10, 1));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(10))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2026-09-25", F10Type_Annual).toBuilder().setDateTime(1790352000L).build();
        QotGetFinancialsStatements.FinancialReport unstored = report("2025-09-26", F10Type_Annual).toBuilder()
                .setDateTime(Instant.parse("2025-09-26T16:00:00Z").getEpochSecond()).build();
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 10)).thenReturn(page("older", raw, unstored));
        FinancialReport old = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2026, 9, 25), OBSERVED_AT.minusSeconds(86400), java.util.Map.of(100L, "Total Revenue"));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(old));
        job.execute(context);
        verify(reports).delete(old);
        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager).persist(written.capture());
        assertThat(written.getAllValues()).singleElement().satisfies(row -> assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 26)));
        verify(session, times(4)).financialStatements(eq("AAPL"), any(), isNull(), eq(10));
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void nativeUsMidnightAndDateOnlyReportsKeepTheirOwnBusinessDate() {
        QotGetFinancialsStatements.FinancialReport raw = report("2026-09-30", F10Type_Q3);
        assertThat(FutuQuoteConverter.financialPeriodEnd(raw)).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(FutuQuoteConverter.financialPeriodEnd(raw.toBuilder().setDateTime(Instant.parse("2026-09-30T04:00:00Z").getEpochSecond()).build()))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void financialDateEncodedAtHongKongMidnightDoesNotBecomePreviousUsCalendarDay() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        // Futu 實際回應：AAPL 2026/FY 的 epoch 是香港 9/26 零時，dateTimeStr 卻顯示美東 9/25。
        QotGetFinancialsStatements.FinancialReport raw = report("2026-09-25", F10Type_Annual).toBuilder()
                .setDateTime(1790352000L).build();
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        job.execute(context);
        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager).persist(written.capture());
        assertThat(written.getAllValues()).singleElement().satisfies(row -> assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 26)));
    }

    @Test
    void correctingEncodedDateKeepsOriginalObservationWhenPayloadIsUnchanged() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2026-09-25", F10Type_Annual).toBuilder()
                .setDateTime(1790352000L).build();
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        Instant originalTime = OBSERVED_AT.minusSeconds(86400);
        FinancialReport old = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2026, 9, 25), originalTime, java.util.Map.of(100L, "Total Revenue"));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(old));
        job.execute(context);
        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager).persist(written.capture());
        assertThat(written.getAllValues()).singleElement().satisfies(row -> {
            assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 26));
            assertThat(row.getObservedAt()).isEqualTo(originalTime);
        });
        verify(reports).delete(old);
    }

    private static final Instant OBSERVED_AT = Instant.parse("2026-11-12T00:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockStatus status = new StockStatus();
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final FutuService futu = mock(FutuService.class);
    private final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
    private final DateTimeService dateTimeService = mock(DateTimeService.class);
    private final FinancialReportDailySyncJob job = new FinancialReportDailySyncJob(stocks, futu, statuses, reports, entityManager,
            Mappers.getMapper(FutuQuoteConverter.class), new TransactionTemplate(transactionManager), dateTimeService);
    private final JobExecutionContext context = mock(JobExecutionContext.class);

    @BeforeEach
    void stubTransactionsAndTime() {
        when(transactionManager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(dateTimeService.now()).thenReturn(OBSERVED_AT);
    }

    @Test
    void jobBackfillsAllFourTablesAndKeepsQuarterAndYearAtTheSamePeriodEnd() {
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
        InOrder order = inOrder(entityManager, reports, transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(reports).findByFigiAndPeriodEndBetween(eq("F1"), any(), any());
        order.verify(entityManager, times(3)).persist(written.capture());
        List<FinancialReport> rows = written.getAllValues();
        order.verify(transactionManager).commit(any());
        assertThat(rows).extracting(FinancialReport::getPeriodEnd)
                .containsExactly(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 9, 30), LocalDate.of(2014, 1, 1));
        assertThat(rows).extracting(FinancialReport::getPeriodType).containsExactly(4, 7, 1);
        assertThat(rows).extracting(FinancialReport::getObservedAt).containsExactly(OBSERVED_AT, OBSERVED_AT, secondPageObservedAt);
        assertThat(rows.getFirst().getCurrency()).isEqualTo("USD");
        assertThat(rows.getFirst().getItems().getFirst().name()).isEqualTo("Total Revenue");
        assertThat(rows.getFirst().getItems().getFirst().value()).isEqualByComparingTo("0");
        assertThat(rows.getFirst().getItems().getLast().value()).isNull();
        verify(session, times(5)).financialStatements(eq("AAPL"), any(), nullable(String.class), eq(50));
        verify(session).financialStatements("AAPL", FinancialStatementsType_BalanceSheet, null, 50);
        verify(session).financialStatements("AAPL", FinancialStatementsType_CashFlow, null, 50);
        verify(session).financialStatements("AAPL", FinancialStatementsType_MainIndex, null, 50);
        verify(session).close();
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    @Test
    void trustClassifiedCommonStockDownloadsFinancialsAndAdvancesItsWatermark() {
        status.setFigi("F1");
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                .thenReturn(List.of(new ActiveStockWithStatus(stock("F1", "EQIX", "CS"), status)));
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("EQIX", "SPY"));
        when(session.financialStatements(eq("EQIX"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        when(session.financialStatements("EQIX", FinancialStatementsType_Income, null, 50))
                .thenReturn(page("-1", report("2026-09-30", F10Type_Q3)));

        job.execute(context);

        verify(session, times(4)).financialStatements(eq("EQIX"), any(), isNull(), eq(50));
        verify(session, never()).financialStatements(eq("SPY"), any(), nullable(String.class), anyInt());
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void scheduledStocksUseTheirOwnWatermarks() {
        LocalDate date = LocalDate.of(2026, 9, 30);
        status.setFigi("F1");
        status.setFinancialReportDate(date);
        StockStatus tsmStatus = new StockStatus();
        tsmStatus.setFigi("F2");
        StockStatus msftStatus = new StockStatus();
        msftStatus.setFigi("F3");
        ActiveStockWithStatus aapl = new ActiveStockWithStatus(stock("F1", "AAPL", "CS"), status);
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl,
                new ActiveStockWithStatus(stock("F2", "TSM", "ADRC"), tsmStatus),
                new ActiveStockWithStatus(stock("F3", "MSFT", "CS"), msftStatus)));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("AAPL", "TSM", "MSFT"));
        when(session.financialStatements(anyString(), any(), isNull(), anyInt())).thenReturn(page("-1"));

        job.execute(context);

        verify(session, times(4)).financialStatements(eq("AAPL"), any(), isNull(), eq(10));
        verify(session, times(4)).financialStatements(eq("TSM"), any(), isNull(), eq(50));
        verify(session, times(4)).financialStatements(eq("MSFT"), any(), isNull(), eq(50));
        verify(session, times(12)).financialStatements(anyString(), any(), nullable(String.class), anyInt());
    }

    @Test
    void incrementalSyncContinuesToTheInclusiveWatermarkAndDoesNotRegressIt() {
        openAapl();
        status.setFinancialReportDate(LocalDate.of(2026, 9, 30));
        StockStatus snapshot = new StockStatus();
        snapshot.setFinancialReportDate(LocalDate.of(2025, 6, 30));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                .thenReturn(List.of(new ActiveStockWithStatus(stock("F1", "AAPL", "CS"), snapshot)));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(10))).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 10))
                .thenReturn(page("older", report("2025-09-30", F10Type_Annual)));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, "older", 10))
                .thenReturn(page("stop", report("2025-06-30", F10Type_Q2), report("2025-03-31", F10Type_Q1)));
        job.execute(context);

        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        verify(entityManager, times(2)).persist(written.capture());
        verify(reports, never()).delete(any(FinancialReport.class));
        assertThat(written.getAllValues()).extracting(FinancialReport::getPeriodEnd)
                .containsExactly(LocalDate.of(2025, 9, 30), LocalDate.of(2025, 6, 30));
        verify(session, times(4)).financialStatements(eq("AAPL"), any(), isNull(), eq(10));
        verify(session).financialStatements("AAPL", FinancialStatementsType_Income, "older", 10);
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        verify(session).supportedUsFinancialReportStocks();
        verify(session).close();
        verifyNoMoreInteractions(session);
    }

    @Test
    void repeatedCursorFailsTheJobBeforeSavingThatStock() {
        openAapl();
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50))
                .thenReturn(page("same", report("2025-09-30", F10Type_Annual)));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, "same", 50)).thenReturn(page("same"));

        assertThatThrownBy(() -> job.execute(context)).hasMessageContaining("F1:AAPL")
                .hasRootCauseMessage("FINANCIAL_STATEMENT_CURSOR_INVALID:FinancialStatementsType_Income");
        verifyNoInteractions(reports, transactionManager);
        verify(statuses, never()).findAllByIdForUpdate(any());
        assertThat(status.getFinancialReportDate()).isNull();
        verify(session).close();
    }

    @Test
    void emptyResponseDoesNotAdvanceWatermark() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        job.execute(context);
        assertThat(status.getFinancialReportDate()).isNull();
        verifyNoInteractions(reports, transactionManager);
    }

    private void openAapl() {
        status.setFigi("F1");
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                .thenReturn(List.of(new ActiveStockWithStatus(stock("F1", "AAPL", "CS"), status)));
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("AAPL", "TSM"));
    }

    @Test
    void identicalRefreshKeepsFirstObservedTimeAndDoesNotReplaceKnownPayload() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2025-09-30", F10Type_Annual);
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        Instant firstObserved = OBSERVED_AT.minusSeconds(86400);
        FinancialReport existing = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2025, 9, 30), firstObserved, java.util.Map.of(100L, "Total Revenue"));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(existing));

        job.execute(context);

        verify(reports, never()).deleteAllInBatch(anyList());
        verify(entityManager, never()).persist(any());
        verifyNoInteractions(entityManager);
        assertThat(existing.getObservedAt()).isEqualTo(firstObserved);
        assertThat(status.getFinancialReportDate()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    @Test
    void jsonbNumericScaleDifferencesDoNotCreateARevisionOrRefreshObservedTime() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2025-09-30", F10Type_Annual);
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        Instant firstObserved = OBSERVED_AT.minusSeconds(86400);
        FinancialReport existing = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2025, 9, 30), firstObserved, java.util.Map.of(100L, "Total Revenue"));
        existing.setItems(List.of(new FinancialReport.Item(100, "Total Revenue", java.math.BigDecimal.ZERO, null, null), existing.getItems().getLast()));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(existing));

        job.execute(context);

        verify(entityManager, never()).persist(any());
        assertThat(existing.getObservedAt()).isEqualTo(firstObserved);
    }

    @Test
    void revisedPayloadReplacesSameKeyAndKeepsFirstAvailability() {
        openAapl();
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        QotGetFinancialsStatements.FinancialReport raw = report("2025-09-30", F10Type_Annual);
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50)).thenReturn(page("-1", raw));
        FinancialReport existing = Mappers.getMapper(FutuQuoteConverter.class).toFinancialReport(raw, "F1", FinancialStatementsType_Income,
                LocalDate.of(2025, 9, 30), OBSERVED_AT.minusSeconds(86400), java.util.Map.of(100L, "Total Revenue"));
        existing.setItems(List.of(new FinancialReport.Item(100, "Total Revenue", java.math.BigDecimal.TEN, null, null),
                existing.getItems().getLast()));
        when(reports.findByFigiAndPeriodEndBetween(eq("F1"), any(), any())).thenReturn(List.of(existing));
        job.execute(context);
        ArgumentCaptor<FinancialReport> written = ArgumentCaptor.captor();
        InOrder order = inOrder(entityManager, reports, transactionManager);
        order.verify(transactionManager).getTransaction(any());
        order.verify(reports).delete(existing);
        order.verify(reports).flush();
        order.verify(entityManager).persist(written.capture());
        order.verify(transactionManager).commit(any());
        assertThat(written.getAllValues()).singleElement().satisfies(row -> {
            assertThat(row.getObservedAt()).isEqualTo(existing.getObservedAt());
            assertThat(row.getCurrency()).isEqualTo("USD");
        });
    }

    @Test
    void missingLockedStockStatusFailsBeforeReplacingReports() {
        openAapl();
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of());
        when(session.financialStatements(eq("AAPL"), any(), isNull(), eq(50))).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50))
                .thenReturn(page("-1", report("2025-09-30", F10Type_Annual)));

        assertThatThrownBy(() -> job.execute(context)).hasRootCauseMessage("FINANCIAL_REPORT_STOCK_STATUS_MISSING:F1");
        verifyNoInteractions(reports);
        verify(statuses, never()).save(any());
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
