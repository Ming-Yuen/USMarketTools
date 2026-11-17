package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.mockito.InOrder;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockSplitDailyTaskTests {
    private static final LocalDate FROM = LocalDate.of(2014, 1, 1);
    private static final LocalDate THROUGH = LocalDate.of(2026, 11, 11);
    private static final LocalDate MAGN_DATE = LocalDate.of(2024, 11, 4);

    /** 舊流程以同一日期、13:1 及 Massive 404 重現空結果；新流程使用實測 Futu 欄位。 */
    @Test
    void magnRecentSplitMustNotDependOnMissingHistoricalTickerDetails() {
        Fixture f = new Fixture();
        f.stock.setSymbol("MAGN");
        f.run(List.of(join(MAGN_DATE, 13)));
        assertThat(f.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.getExecutionDate()).isEqualTo(MAGN_DATE);
            assertThat(row.getSplitFrom()).isEqualByComparingTo("13");
            assertThat(row.getSplitTo()).isEqualByComparingTo("1");
        });
        assertThat(f.status.getSplitMessage()).isNull();
    }

    @Test
    void currentSymbolCarriesOldSymbolEventAndRenameDoesNotDuplicateIt() {
        Fixture f = new Fixture();
        f.stock.setSymbol("MAXNQ");
        List<QotCommon.Rehab> source = List.of(join(LocalDate.of(2024, 10, 9), 100));
        f.run(source);
        f.stock.setSymbol("ANOTHER");
        f.run(source);
        assertThat(f.rows).containsOnlyKeys("FUTU:F1:2024-10-09");
        verify(f.splits, times(1)).saveAll(any());
        assertThat(f.status.getSplitFromDate()).isEqualTo(FROM);
        assertThat(f.status.getSplitDate()).isEqualTo(THROUGH);
    }

    @Test
    void stockDividendAddsSharesAndCashDividendIsExcluded() {
        Fixture f = new Fixture();
        QotCommon.Rehab bonus = rehab("2024-05-24").setBonusBase(10).setBonusErt(3).build();
        QotCommon.Rehab cash = rehab("2024-06-01").setDividend(0.5).setFwdFactorA(0.9).build();
        f.run(List.of(bonus, cash));
        assertThat(f.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.getSplitFrom()).isEqualByComparingTo("10");
            assertThat(row.getSplitTo()).isEqualByComparingTo("13");
        });
    }

    @Test
    void splitAndTransferKeepCorrectBeforeAfterDirection() {
        Fixture f = new Fixture();
        f.run(List.of(rehab("2024-06-10").setSplitBase(1).setSplitErt(10).build(),
                rehab("2025-01-01").setTransferBase(10).setTransferErt(3).build()));
        assertThat(f.rows.get("FUTU:F1:2024-06-10").getSplitTo()).isEqualByComparingTo("10");
        assertThat(f.rows.get("FUTU:F1:2025-01-01").getSplitTo()).isEqualByComparingTo("13");
    }

    @Test
    void cutoffIncludesSplitsNeededByReportsBeforeFirstValuationYear() {
        Fixture f = new Fixture();
        LocalDate beforeFirstValuationYear = LocalDate.of(2015, 11, 30);
        f.run(List.of(join(FROM.minusDays(1), 3), rehab("2020-04-02").setSplitBase(2).setSplitErt(3).build(),
                join(FROM, 2), join(beforeFirstValuationYear, 2), join(THROUGH, 4), join(THROUGH.plusDays(1), 5)));
        assertThat(f.rows.values()).extracting(StockSplit::getExecutionDate)
                .containsExactly(FROM, beforeFirstValuationYear, LocalDate.of(2020, 4, 2), THROUGH);
    }

    @Test
    void providerCorrectionReplacesRatioAndUnchangedRerunDoesNotWrite() {
        Fixture f = new Fixture();
        f.run(List.of(join(MAGN_DATE, 13)));
        f.run(List.of(join(MAGN_DATE, 14)));
        f.run(List.of(join(MAGN_DATE, 14)));
        assertThat(f.rows.values()).singleElement().satisfies(row -> assertThat(row.getSplitFrom()).isEqualByComparingTo("14"));
        verify(f.splits, times(2)).saveAll(any());
        verify(f.splits, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void correctionUpdatesExistingFutuIdWithoutBulkDeletingManagedEntity() {
        Fixture f = new Fixture();
        f.run(List.of(join(MAGN_DATE, 13)));
        f.run(List.of(join(MAGN_DATE, 14)));
        // 查詢與更新同在一筆 JPA 交易；刪除受管理的相同 ID 再 merge 可能變成更新已刪列。
        verify(f.splits, never()).deleteAllByIdInBatch(any());
        assertThat(f.rows.get("FUTU:F1:2024-11-04").getSplitFrom()).isEqualByComparingTo("14");
    }

    @Test
    void switchingSourceReplacesAllSameDayRowsWithoutCompoundingOrDeletingOlderHistory() {
        Fixture f = new Fixture();
        f.store("ALPACA:one", MAGN_DATE);
        f.store("ALPACA:duplicate", MAGN_DATE);
        f.store("MASSIVE:existing", MAGN_DATE);
        f.store("ALPACA:old", FROM.minusDays(1));
        f.run(List.of(join(MAGN_DATE, 13)));
        assertThat(f.rows).containsOnlyKeys("ALPACA:old", "FUTU:F1:2024-11-04");
    }

    @Test
    void missingEventIsPreservedAndDateCorrectionCannotSilentlyRemoveIt() {
        Fixture f = new Fixture();
        f.run(List.of(join(MAGN_DATE, 13)));
        f.run(List.of());
        assertThat(f.rows).hasSize(1);
        assertThat(f.status.getSplitMessage()).contains("SPLIT_STORED_EVENT_NOT_RETURNED");
        assertThat(f.status.getSplitDate()).isEqualTo(MAGN_DATE.minusDays(1));
    }

    @Test
    void invalidRatiosDuplicateDatesAndMixedShareActionsCannotPartiallySave() {
        Fixture f = new Fixture();
        for (List<QotCommon.Rehab> source : List.of(
                List.of(join(MAGN_DATE, 0)), List.of(join(MAGN_DATE, 13), join(MAGN_DATE, 13)),
                List.of(rehab("2024-11-04").setSplitBase(1).setSplitErt(2).setBonusBase(10).setBonusErt(3).build()),
                List.of(rehab("2024-11-04").setCompanyActFlag(QotCommon.CompanyAct.CompanyAct_Join_VALUE).build()))) {
            f.run(source);
            assertThat(f.status.getSplitMessage()).startsWith("ERROR:");
            assertThat(f.status.getSplitDate()).isNull();
        }
        verifyNoInteractions(f.splits);
    }

    @Test
    void missingStatusFailsBeforeChangingEvents() {
        Fixture f = new Fixture();
        doReturn(List.of()).when(f.statuses).findAllByIdForUpdate(any());
        assertThatThrownBy(() -> f.run(List.of(join(MAGN_DATE, 13)))).hasMessage("SPLIT_STOCK_STATUS_MISSING:F1");
        verifyNoInteractions(f.splits);
    }

    @Test
    void stockFailurePreservesExistingDatesAndEventsAndRecordsReason() {
        Fixture f = new Fixture();
        f.run(List.of(join(MAGN_DATE, 13)));
        when(f.session.rehab("MAGN")).thenThrow(new IllegalStateException("FUTU_REQUEST_REHAB_FAILED:Unknown stock"));
        f.task.execute(null);
        assertThat(f.rows).hasSize(1);
        assertThat(f.status.getSplitDate()).isEqualTo(THROUGH);
        assertThat(f.status.getSplitMessage()).contains("Unknown stock");
    }

    @Test
    void validEmptyHistoryAdvancesCheckedRange() {
        Fixture f = new Fixture();
        f.run(List.of());
        assertThat(f.rows).isEmpty();
        assertThat(f.status.getSplitDate()).isEqualTo(THROUGH);
        assertThat(f.status.getSplitMessage()).isNull();
    }

    @Test
    void eachStockIsSavedBeforeNextDownloadAndSingleStockFailureDoesNotStopOthers() {
        Fixture f = new Fixture();
        Stock unknown = f.addStock("F2", "UNKNOWN");
        f.addStock("F3", "MASK");
        when(f.session.rehab("UNKNOWN")).thenThrow(new IllegalStateException("FUTU_REQUEST_REHAB_FAILED:Unknown stock"));
        f.run(List.of(join(MAGN_DATE, 13)));
        InOrder order = inOrder(f.session, f.splits, f.statuses);
        order.verify(f.session).rehab("MAGN");
        order.verify(f.splits).saveAll(any());
        order.verify(f.statuses).save(f.status);
        order.verify(f.session).rehab("UNKNOWN");
        order.verify(f.statuses).save(f.states.get(unknown.getFigi()));
        order.verify(f.session).rehab("MASK");
        order.verify(f.statuses).save(f.states.get("F3"));
        order.verify(f.session).close();
        assertThat(f.rows).containsOnlyKeys("FUTU:F1:2024-11-04");
        assertThat(f.states.get("F2").getSplitMessage()).contains("Unknown stock");
        assertThat(f.states.get("F3").getSplitDate()).isEqualTo(THROUGH);
        verify(f.futu, times(1)).openObservationSession();
    }

    @Test
    void brokenConnectionAbortsWithoutDownloadingRemainingStocks() {
        Fixture f = new Fixture();
        f.addStock("F2", "MASK");
        when(f.session.rehab("MAGN")).thenThrow(new IllegalStateException("FUTU_REQUEST_REHAB_TIMEOUT"));
        assertThatThrownBy(() -> f.task.execute(null)).hasMessage("FUTU_REQUEST_REHAB_TIMEOUT");
        assertThat(f.status.getSplitMessage()).contains("FUTU_REQUEST_REHAB_TIMEOUT");
        verify(f.session, never()).rehab("MASK");
        verify(f.session).close();
    }

    @Test
    void ambiguousCurrentSymbolCannotCopyOneHistoryToTwoFigis() {
        Fixture f = new Fixture();
        f.stock.setSymbol("SAME");
        f.addStock("F2", "SAME");
        f.task.execute(null);
        verify(f.session, never()).rehab(any());
        verifyNoInteractions(f.splits);
        assertThat(f.states.values()).allSatisfy(status ->
                assertThat(status.getSplitMessage()).contains("SPLIT_CURRENT_SYMBOL_AMBIGUOUS"));
    }

    @Test
    void emptyUniverseDoesNotOpenConnection() {
        Fixture f = new Fixture();
        f.targets.clear();
        f.task.execute(null);
        verifyNoInteractions(f.futu);
    }
    private static QotCommon.Rehab.Builder rehab(String date) {
        return QotCommon.Rehab.newBuilder().setTime(date).setCompanyActFlag(0)
                .setFwdFactorA(1).setFwdFactorB(0).setBwdFactorA(1).setBwdFactorB(0);
    }
    private static QotCommon.Rehab join(LocalDate date, int before) {
        return rehab(date.toString()).setCompanyActFlag(QotCommon.CompanyAct.CompanyAct_Join_VALUE).setJoinBase(before).setJoinErt(1).build();
    }

    private static class Fixture {
        final StockSplitRepository splits = mock(StockSplitRepository.class);
        final StockStatusRepository statuses = mock(StockStatusRepository.class);
        final DateTimeService clock = mock(DateTimeService.class);
        final Map<String, StockSplit> rows = new LinkedHashMap<>();
        final Stock stock = new Stock();
        final StockStatus status = new StockStatus();
        final StockRepository stocks = mock(StockRepository.class);
        final FutuService futu = mock(FutuService.class);
        final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        final MarketStatusService market = mock(MarketStatusService.class);
        final Map<String, Stock> targets = new LinkedHashMap<>();
        final Map<String, StockStatus> states = new LinkedHashMap<>();
        final StockSplitDailyTask task = new StockSplitDailyTask(stocks, futu, splits, statuses, Mappers.getMapper(FutuQuoteConverter.class),
                new TransactionTemplate(new NoOpTransactionManager()), clock, market);

        Fixture() {
            stock.setFigi("F1");
            stock.setSymbol("MAGN");
            status.setFigi("F1");
            targets.put("F1", stock);
            states.put("F1", status);
            when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenAnswer(call -> List.copyOf(targets.values()));
            when(market.getLastClosedTradingDay()).thenReturn(THROUGH);
            when(futu.openObservationSession()).thenReturn(session);
            when(session.rehab(anyString())).thenReturn(List.of());
            when(clock.now()).thenReturn(Instant.parse("2026-11-12T03:00:00Z"));
            when(statuses.findAllByIdForUpdate(any())).thenAnswer(call -> call.<Collection<String>>getArgument(0).stream().map(states::get).toList());
            when(splits.findByFigiAndExecutionDateBetween(eq("F1"), any(), any())).thenAnswer(call -> rows.values().stream()
                    .filter(row -> !row.getExecutionDate().isBefore(call.getArgument(1)) && !row.getExecutionDate().isAfter(call.getArgument(2))).toList());
            when(splits.saveAll(any())).thenAnswer(call -> {
                List<StockSplit> saved = call.getArgument(0);
                saved.forEach(row -> rows.put(row.getSourceEventId(), row));
                return saved;
            });
            doAnswer(call -> { call.<Collection<String>>getArgument(0).forEach(rows::remove); return null; }).when(splits).deleteAllByIdInBatch(any());
        }

        void run(List<QotCommon.Rehab> source) {
            doReturn(source).when(session).rehab(stock.getSymbol());
            task.execute(null);
        }

        Stock addStock(String figi, String symbol) {
            Stock added = new Stock();
            added.setFigi(figi);
            added.setSymbol(symbol);
            targets.put(figi, added);
            StockStatus addedStatus = new StockStatus();
            addedStatus.setFigi(figi);
            states.put(figi, addedStatus);
            return added;
        }

        void store(String id, LocalDate date) {
            StockSplit row = new StockSplit();
            row.setSourceEventId(id);
            row.setFigi("F1");
            row.setExecutionDate(date);
            row.setSplitFrom(BigDecimal.ONE);
            row.setSplitTo(BigDecimal.TEN);
            rows.put(id, row);
        }
    }
}
