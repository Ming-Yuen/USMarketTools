package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplit;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockSplitDailyTaskTests {
    private static final LocalDate FROM = LocalDate.of(2014, 1, 1);
    private static final LocalDate THROUGH = LocalDate.of(2026, 11, 11);
    private static final LocalDate MAGN_DATE = LocalDate.of(2024, 11, 4);

    /** Scenario：平日增量對已有 splitDate 水位的股票改走 Massive /stocks/v1/splits，不開啟富途連線；無拆股新股才呼叫富途。 */
    @Test
    void weekdayIncrementalUsesMassiveForExistingStocksAndSkipsFutuConnection() {
        Fixture f = new Fixture();
        f.status.setSplitFromDate(FROM);
        f.status.setSplitDate(THROUGH.minusDays(1));
        MassiveSplit nvdaSplit = new MassiveSplit();
        nvdaSplit.setId("SP1");
        nvdaSplit.setTicker("MAGN");
        nvdaSplit.setExecutionDate(THROUGH);
        nvdaSplit.setSplitFrom(BigDecimal.ONE);
        nvdaSplit.setSplitTo(BigDecimal.TEN);
        nvdaSplit.setAdjustmentType("forward_split");
        when(f.massive.getSplitsFrom(THROUGH.minusDays(8))).thenReturn(List.of(nvdaSplit));

        f.task.execute(null);

        verifyNoInteractions(f.futu);
        assertThat(f.rows.get("MASSIVE:SP1")).satisfies(row -> {
            assertThat(row.getExecutionDate()).isEqualTo(THROUGH);
            assertThat(row.getSplitFrom()).isEqualByComparingTo("1");
            assertThat(row.getSplitTo()).isEqualByComparingTo("10");
        });
        assertThat(f.status.getSplitDate()).isEqualTo(THROUGH);
    }

    /** Scenario：splitDate == null 的新股在平日首次同步時自動呼叫富途 requestRehab 回補完整歷史。 */
    @Test
    void newStockWithoutSplitDateBackfillsFromFutuOnWeekday() {
        Fixture f = new Fixture();
        f.stock.setSymbol("MAGN");
        f.runFutuNewOrSaturday(List.of(join(MAGN_DATE, 13)));
        assertThat(f.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.getExecutionDate()).isEqualTo(MAGN_DATE);
            assertThat(row.getSplitFrom()).isEqualByComparingTo("13");
            assertThat(row.getSplitTo()).isEqualByComparingTo("1");
        });
        assertThat(f.status.getSplitMessage()).isNull();
        verifyNoInteractions(f.massive);
    }

    /** Scenario：週六 Cron 對帳時對已有水位的股票執行富途全量對帳，更正比例並檢查遺失事件。 */
    @Test
    void saturdayReconcileUpdatesExistingFutuRatioAndDetectsMissingStoredEvent() {
        Fixture f = new Fixture();
        f.runSaturday(List.of(join(MAGN_DATE, 13)));
        f.runSaturday(List.of(join(MAGN_DATE, 14)));
        assertThat(f.rows.get("FUTU:F1:2024-11-04").getSplitFrom()).isEqualByComparingTo("14");
        verify(f.splits, never()).deleteAllByIdInBatch(any());

        f.runSaturday(List.of());
        assertThat(f.status.getSplitMessage()).contains("SPLIT_STORED_EVENT_NOT_RETURNED");
        assertThat(f.status.getSplitDate()).isEqualTo(MAGN_DATE.minusDays(1));
    }

    @Test
    void stockDividendAddsSharesAndCashDividendIsExcluded() {
        Fixture f = new Fixture();
        QotCommon.Rehab bonus = rehab("2024-05-24").setBonusBase(10).setBonusErt(3).build();
        QotCommon.Rehab cash = rehab("2024-06-01").setDividend(0.5).setFwdFactorA(0.9).build();
        f.runFutuNewOrSaturday(List.of(bonus, cash));
        assertThat(f.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.getSplitFrom()).isEqualByComparingTo("10");
            assertThat(row.getSplitTo()).isEqualByComparingTo("13");
        });
    }

    @Test
    void switchingSourceReplacesAllSameDayRowsWithoutCompoundingOrDeletingOlderHistory() {
        Fixture f = new Fixture();
        f.store("ALPACA:one", MAGN_DATE);
        f.store("MASSIVE:existing", MAGN_DATE);
        f.store("ALPACA:old", FROM.minusDays(1));
        f.runSaturday(List.of(join(MAGN_DATE, 13)));
        assertThat(f.rows).containsOnlyKeys("ALPACA:old", "FUTU:F1:2024-11-04");
    }

    @Test
    void eachStockIsSavedBeforeNextDownloadAndSingleStockFailureDoesNotStopOthers() {
        Fixture f = new Fixture();
        Stock unknown = f.addStock("F2", "UNKNOWN");
        f.addStock("F3", "MASK");
        when(f.session.rehab("UNKNOWN")).thenThrow(new IllegalStateException("FUTU_REQUEST_REHAB_FAILED:Unknown stock"));
        f.runFutuNewOrSaturday(List.of(join(MAGN_DATE, 13)));
        InOrder order = inOrder(f.session, f.splits, f.statuses);
        order.verify(f.session).rehab("MAGN");
        order.verify(f.splits).saveAll(any());
        order.verify(f.statuses).save(f.status);
        order.verify(f.session).rehab("UNKNOWN");
        order.verify(f.statuses).save(f.states.get(unknown.getFigi()));
        order.verify(f.session).rehab("MASK");
        order.verify(f.statuses).save(f.states.get("F3"));
        order.verify(f.session).close();
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
        verifyNoInteractions(f.futu, f.massive, f.splits);
        assertThat(f.states.values()).allSatisfy(status ->
                assertThat(status.getSplitMessage()).contains("SPLIT_CURRENT_SYMBOL_AMBIGUOUS"));
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
        final MassiveService massive = mock(MassiveService.class);
        final FutuService futu = mock(FutuService.class);
        final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        final MarketStatusService market = mock(MarketStatusService.class);
        final Map<String, Stock> targets = new LinkedHashMap<>();
        final Map<String, StockStatus> states = new LinkedHashMap<>();
        final StockSplitDailyTask task = new StockSplitDailyTask(stocks, massive, futu, splits, statuses,
                Mappers.getMapper(FutuQuoteConverter.class), new TransactionTemplate(new NoOpTransactionManager()), clock, market);

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
            when(statuses.findAllById(any())).thenAnswer(call -> call.<Collection<String>>getArgument(0).stream().map(states::get).toList());
            when(statuses.findAllByIdForUpdate(any())).thenAnswer(call -> call.<Collection<String>>getArgument(0).stream().map(states::get).toList());
            when(splits.findByFigiAndExecutionDateBetween(anyString(), any(), any())).thenAnswer(call -> {
                String figi = call.getArgument(0);
                LocalDate from = call.getArgument(1);
                LocalDate to = call.getArgument(2);
                return rows.values().stream()
                        .filter(row -> figi.equals(row.getFigi()) && !row.getExecutionDate().isBefore(from) && !row.getExecutionDate().isAfter(to))
                        .toList();
            });
            when(splits.saveAll(any())).thenAnswer(call -> {
                List<StockSplit> saved = call.getArgument(0);
                saved.forEach(row -> rows.put(row.getSourceEventId(), row));
                return saved;
            });
            doAnswer(call -> {
                call.<Collection<String>>getArgument(0).forEach(rows::remove);
                return null;
            }).when(splits).deleteAllByIdInBatch(any());
        }

        void runFutuNewOrSaturday(List<QotCommon.Rehab> source) {
            doReturn(source).when(session).rehab(stock.getSymbol());
            task.execute(null);
        }

        void runSaturday(List<QotCommon.Rehab> source) {
            doReturn(source).when(session).rehab(stock.getSymbol());
            JobExecutionContext ctx = mock(JobExecutionContext.class);
            when(ctx.getTrigger()).thenReturn(mock(CronTrigger.class));
            // 2026-11-14T02:00:00Z is Saturday 10:00 Asia/Hong_Kong
            when(ctx.getScheduledFireTime()).thenReturn(Date.from(Instant.parse("2026-11-14T02:00:00Z")));
            task.execute(ctx);
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
