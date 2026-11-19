package com.ykm.stock.analytics.service.analysis;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.SignalTrackingLog;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.SignalTrackingLogRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService.SignalTrackingStatsSummary;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 驗收訊號事後追蹤跨拆股結算（1 拆 10 不誤判暴跌 90%）、同日雙觸（open 先決 / AMBIGUOUS_SAME_BAR）、
 * 20 個交易日冷卻去重、拆股覆蓋不足保護與統計勝率彙總。
 */
class SignalTrackingServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-17T22:00:00Z");

    private final SignalTrackingLogRepository logRepository = mock(SignalTrackingLogRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final MarketStatusService calendar = new MarketStatusService(null);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private SignalTrackingService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        service = new SignalTrackingService(logRepository, quotes, splits, statuses, calendar, clock, tx, entityManager);
        when(clock.now()).thenReturn(NOW);
    }

    /**
     * Scenario：訊號於收盤 100（止損 90，目標 120）發出，第 10 個交易日發生 1 拆 10 拆股；
     * 拆股後股價為 11（實際 +10%），於第 30 個交易日漲至 12.2（實際 +22%，觸及目標 120）。
     * 必須經 StockSplitCalculator 對齊回訊號日基準，不得誤判暴跌 -90% 或觸發止損！
     */
    @Test
    void settleSingleSignalAlignsAcross10For1SplitWithoutFalseStopOrCrash() {
        LocalDate signalDate = LocalDate.of(2026, 6, 1);
        List<LocalDate> future = calendar.getTradingDays(signalDate.plusDays(1), signalDate.plusDays(130));
        LocalDate splitDate = future.get(9);
        LocalDate targetHitDate = future.get(29);
        LocalDate due60 = future.get(59);

        SignalTrackingLog log = new SignalTrackingLog();
        log.setFigi("F_NVDA");
        log.setSymbol("NVDA");
        log.setSignalDate(signalDate);
        log.setSignalType(SignalTrackingLog.SIGNAL_ACCUMULATE_AT_SUPPORT);
        log.setSignalClose(new BigDecimal("100.00"));
        log.setProtectiveStop(new BigDecimal("90.00"));
        log.setFirstTargetPrice(new BigDecimal("120.00"));
        log.setOutcomeStatus(SignalTrackingLog.OUTCOME_PENDING);

        StockSplit split = new StockSplit();
        split.setSourceEventId("NVDA-10-1");
        split.setFigi("F_NVDA");
        split.setExecutionDate(splitDate);
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(BigDecimal.TEN);

        StockStatus status = new StockStatus();
        status.setFigi("F_NVDA");
        status.setSplitFromDate(LocalDate.of(2020, 1, 1));
        status.setSplitDate(due60);

        List<QuoteBarRow> bars = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            LocalDate d = future.get(i);
            if (d.isBefore(splitDate)) {
                // 拆股前微漲至 105
                bars.add(new QuoteBarRow("F_NVDA", d, new BigDecimal("103"), new BigDecimal("106"),
                        new BigDecimal("101"), new BigDecimal("105"), new BigDecimal("100000"), NOW));
            } else if (d.isBefore(targetHitDate)) {
                // 拆股後價格 11（還原 = 110，+10%），低點 10.5（還原 = 105，未觸 90 止損）
                bars.add(new QuoteBarRow("F_NVDA", d, new BigDecimal("10.8"), new BigDecimal("11.2"),
                        new BigDecimal("10.5"), new BigDecimal("11.0"), new BigDecimal("1000000"), NOW));
            } else {
                // 第 30 日起漲至 12.2（還原 = 122，+22%，觸及目標 120）
                bars.add(new QuoteBarRow("F_NVDA", d, new BigDecimal("11.8"), new BigDecimal("12.5"),
                        new BigDecimal("11.7"), new BigDecimal("12.2"), new BigDecimal("1000000"), NOW));
            }
        }

        boolean changed = service.settleSingleSignal(log, due60, NOW, bars, List.of(split), status);
        assertThat(changed).isTrue();
        assertThat(log.getReturn5d()).isEqualByComparingTo("0.050000");
        assertThat(log.getReturn20d()).isEqualByComparingTo("0.100000");
        assertThat(log.getReturn60d()).isEqualByComparingTo("0.220000");
        assertThat(log.getOutcomeStatus()).isEqualTo(SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST);
        assertThat(log.getOutcomeDate()).isEqualTo(targetHitDate);
        assertThat(log.getMfe60d()).isEqualByComparingTo("0.250000");
        assertThat(log.getMae60d()).isEqualByComparingTo("0.000000");
        assertThat(log.getSettledPathDate()).isEqualTo(due60);
    }

    @Test
    void settleSingleSignalDistinguishesSameBarAmbiguityAndGapOpenPriority() {
        LocalDate signalDate = LocalDate.of(2026, 8, 3);
        List<LocalDate> future = calendar.getTradingDays(signalDate.plusDays(1), signalDate.plusDays(30));
        LocalDate day1 = future.getFirst();
        StockStatus status = new StockStatus();
        status.setFigi("F_GAP");
        status.setSplitFromDate(LocalDate.of(2020, 1, 1));
        status.setSplitDate(future.get(10));

        // 1) 開盤介於止損與目標間，當日高低雙觸 -> AMBIGUOUS_SAME_BAR
        SignalTrackingLog ambig = pendingLog("F_GAP", signalDate, "100", "95", "110");
        List<QuoteBarRow> ambigBars = List.of(
                new QuoteBarRow("F_GAP", day1, new BigDecimal("100"), new BigDecimal("112"), new BigDecimal("94"), new BigDecimal("102"), BigDecimal.TEN, NOW));
        service.settleSingleSignal(ambig, day1, NOW, ambigBars, List.of(), status);
        assertThat(ambig.getOutcomeStatus()).isEqualTo(SignalTrackingLog.OUTCOME_AMBIGUOUS_SAME_BAR);

        // 2) 開盤直接跳空低於止損，當日再拉過目標 -> STOP_HIT_FIRST
        SignalTrackingLog gapDown = pendingLog("F_GAP", signalDate, "100", "95", "110");
        List<QuoteBarRow> gapDownBars = List.of(
                new QuoteBarRow("F_GAP", day1, new BigDecimal("94"), new BigDecimal("112"), new BigDecimal("93"), new BigDecimal("111"), BigDecimal.TEN, NOW));
        service.settleSingleSignal(gapDown, day1, NOW, gapDownBars, List.of(), status);
        assertThat(gapDown.getOutcomeStatus()).isEqualTo(SignalTrackingLog.OUTCOME_STOP_HIT_FIRST);
    }

    @Test
    void settleSingleSignalSkipsWhenSplitSyncLagsBehindDueDate() {
        LocalDate signalDate = LocalDate.of(2026, 8, 3);
        List<LocalDate> future = calendar.getTradingDays(signalDate.plusDays(1), signalDate.plusDays(30));
        LocalDate due5 = future.get(4);

        SignalTrackingLog log = pendingLog("F_LAG", signalDate, "100", "90", "115");
        StockStatus laggingStatus = new StockStatus();
        laggingStatus.setFigi("F_LAG");
        laggingStatus.setSplitFromDate(LocalDate.of(2020, 1, 1));
        laggingStatus.setSplitDate(signalDate); // 尚未同步至 due5

        List<QuoteBarRow> bars = future.subList(0, 5).stream()
                .map(d -> new QuoteBarRow("F_LAG", d, new BigDecimal("102"), new BigDecimal("105"),
                        new BigDecimal("101"), new BigDecimal("104"), BigDecimal.TEN, NOW))
                .toList();

        boolean changed = service.settleSingleSignal(log, due5, NOW, bars, List.of(), laggingStatus);
        assertThat(changed).isFalse();
        assertThat(log.getSettled5dDate()).isNull();
        assertThat(log.getReturn5d()).isNull();
    }

    @Test
    void recordSignalsEnforces20TradingDayCooldownBoundaryAndExcludesHeadwindWatchOnly() {
        LocalDate day0 = LocalDate.of(2026, 9, 1);
        List<LocalDate> sessions = calendar.getTradingDays(day0.plusDays(1), day0.plusDays(45));
        LocalDate day19 = sessions.get(18); // 第 19 個交易日 -> 仍在冷卻期內
        LocalDate day20 = sessions.get(19); // 第 20 個交易日 -> 冷卻結束，可再次記帳

        SignalTrackingLog existing = pendingLog("F_A", day0, "100", "90", "120");
        List<SignalTrackingLog> persisted = new ArrayList<>();
        doAnswer(inv -> {
            persisted.add((SignalTrackingLog) inv.getArgument(0));
            return null;
        }).when(entityManager).persist(any());
        when(logRepository.findSignalsInDateRange(anyCollection(), any(), any())).thenReturn(List.of(existing));

        DailyOpportunityRadar normalRow = radarRow("F_A", "AAA", SignalTrackingLog.SIGNAL_ACCUMULATE_AT_SUPPORT, false);
        DailyOpportunityRadar headwindRow = radarRow("F_B", "BBB", SignalTrackingLog.SIGNAL_ACCUMULATE_AT_SUPPORT, true);

        int countAt19 = service.recordSignals(day19, List.of(normalRow, headwindRow));
        assertThat(countAt19).isZero();
        assertThat(persisted).isEmpty();

        int countAt20 = service.recordSignals(day20, List.of(normalRow, headwindRow));
        assertThat(countAt20).isEqualTo(1);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.getFirst().getFigi()).isEqualTo("F_A");
        assertThat(persisted.getFirst().getSignalDate()).isEqualTo(day20);
    }

    @Test
    void recordSignalsBlocksOutOfOrderBackfillWithin20TradingDays() {
        LocalDate day0 = LocalDate.of(2026, 9, 1);
        List<LocalDate> sessions = calendar.getTradingDays(day0.plusDays(1), day0.plusDays(30));
        LocalDate day10 = sessions.get(9);

        // 較晚交易日 day10 已有既有訊號，若倒序嘗試寫入 day0，雙向冷卻視窗仍須攔截
        SignalTrackingLog futureExisting = pendingLog("F_A", day10, "100", "90", "120");
        when(logRepository.findSignalsInDateRange(anyCollection(), any(), any())).thenReturn(List.of(futureExisting));

        int inserted = service.recordSignals(day0, List.of(radarRow("F_A", "AAA", SignalTrackingLog.SIGNAL_ACCUMULATE_AT_SUPPORT, false)));
        assertThat(inserted).isZero();
    }

    @Test
    void settleSingleSignalWithholdsPartialMfeOnGapAndRecoversAfterBackfill() {
        LocalDate signalDate = LocalDate.of(2026, 6, 1);
        List<LocalDate> future = calendar.getTradingDays(signalDate.plusDays(1), signalDate.plusDays(130));
        LocalDate due60 = future.get(59);

        SignalTrackingLog record = pendingLog("F_GAP60", signalDate, "100", "85", "130");
        StockStatus status = new StockStatus();
        status.setFigi("F_GAP60");
        status.setSplitFromDate(LocalDate.of(2020, 1, 1));
        status.setSplitDate(due60);

        List<QuoteBarRow> barsWithGap = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            if (i == 9) {
                continue; // 刻意缺少第 10 個交易日
            }
            LocalDate d = future.get(i);
            barsWithGap.add(new QuoteBarRow("F_GAP60", d, new BigDecimal("102"), new BigDecimal("112"),
                    new BigDecimal("98"), new BigDecimal("105"), new BigDecimal("10000"), NOW));
        }

        // 1) 缺棒時：滿 60 日不得把前 9 天暫算值寫入 mfe60d/mae60d，且標記 DATA_GAP
        boolean changedGap = service.settleSingleSignal(record, due60, NOW, barsWithGap, List.of(), status);
        assertThat(changedGap).isTrue();
        assertThat(record.getSettled60dDate()).isEqualTo(due60);
        assertThat(record.getSettledPathDate()).isNull();
        assertThat(record.getMfe60d()).isNull();
        assertThat(record.getMae60d()).isNull();
        assertThat(record.getOutcomeStatus()).isEqualTo(SignalTrackingLog.OUTCOME_DATA_GAP);

        // 2) 補齊第 10 個交易日後重跑結算：自動從 DATA_GAP 恢復，寫入完整 60D MFE/MAE 與最終 outcome
        List<QuoteBarRow> fullBars = new ArrayList<>(barsWithGap);
        fullBars.add(new QuoteBarRow("F_GAP60", future.get(9), new BigDecimal("102"), new BigDecimal("115"),
                new BigDecimal("96"), new BigDecimal("104"), new BigDecimal("10000"), NOW));
        boolean changedRecovered = service.settleSingleSignal(record, due60, NOW, fullBars, List.of(), status);
        assertThat(changedRecovered).isTrue();
        assertThat(record.getSettledPathDate()).isEqualTo(due60);
        assertThat(record.getMfe60d()).isEqualByComparingTo("0.150000");
        assertThat(record.getMae60d()).isEqualByComparingTo("-0.040000");
        assertThat(record.getOutcomeStatus()).isEqualTo(SignalTrackingLog.OUTCOME_EXPIRED_NEITHER);
    }

    @Test
    void buildStatsSummaryAggregatesByTypeRegimeIndustryAndTier() {
        SignalTrackingLog win = pendingLog("F_1", LocalDate.of(2026, 6, 1), "100", "90", "120");
        win.setIndustryCode("I41");
        win.setIndustryName("Semiconductors");
        win.setMarketCapTier("MEGA_LARGE");
        win.setMarketRegime("RISK_ON");
        win.setReturn5d(new BigDecimal("0.04"));
        win.setReturn20d(new BigDecimal("0.12"));
        win.setOutcomeStatus(SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST);

        SignalTrackingLog starterNoTarget = pendingLog("F_2", LocalDate.of(2026, 6, 5), "50", "46", null);
        starterNoTarget.setSignalType(SignalTrackingLog.SIGNAL_TREND_PULLBACK_STARTER);
        starterNoTarget.setIndustryCode("I40");
        starterNoTarget.setIndustryName("Software - Application");
        starterNoTarget.setMarketCapTier("MID");
        starterNoTarget.setMarketRegime("RISK_ON");
        starterNoTarget.setReturn20d(new BigDecimal("-0.04"));
        starterNoTarget.setOutcomeStatus(SignalTrackingLog.OUTCOME_STOP_HIT_FIRST);

        when(logRepository.findAllByOrderBySignalDateDescIdDesc()).thenReturn(List.of(win, starterNoTarget));

        SignalTrackingStatsSummary stats = service.buildStatsSummary();
        assertThat(stats.totalSignals()).isEqualTo(2);
        assertThat(stats.settled20dCount()).isEqualTo(2);
        assertThat(stats.overallWinRate20d()).isEqualByComparingTo("0.5000");
        assertThat(stats.overallAvgReturn20d()).isEqualByComparingTo("0.040000");
        // starterNoTarget 無目標價，不混入雙邊目標先中率分母 -> 雙邊先中率為 1/1 = 1.0000
        assertThat(stats.overallTargetHitRate()).isEqualByComparingTo("1.0000");
        assertThat(stats.bySignalType()).hasSize(2);
        assertThat(stats.byMarketRegime()).hasSize(1);
        assertThat(stats.byIndustry()).hasSize(2);
        assertThat(stats.byMarketCapTier()).hasSize(2);
    }

    private SignalTrackingLog pendingLog(String figi, LocalDate signalDate, String close, String stop, String target) {
        SignalTrackingLog log = new SignalTrackingLog();
        log.setFigi(figi);
        log.setSymbol(figi);
        log.setSignalDate(signalDate);
        log.setSignalType(SignalTrackingLog.SIGNAL_ACCUMULATE_AT_SUPPORT);
        log.setSignalClose(new BigDecimal(close));
        log.setProtectiveStop(stop == null ? null : new BigDecimal(stop));
        log.setFirstTargetPrice(target == null ? null : new BigDecimal(target));
        log.setOutcomeStatus(SignalTrackingLog.OUTCOME_PENDING);
        return log;
    }

    private DailyOpportunityRadar radarRow(String figi, String symbol, String stance, boolean headwindWatchOnly) {
        DailyOpportunityRadar row = new DailyOpportunityRadar();
        row.setFigi(figi);
        row.setSymbol(symbol);
        row.setStance(stance);
        row.setHeadwindWatchOnly(headwindWatchOnly);
        row.setClosePrice(new BigDecimal("100"));
        row.setProtectiveStop(new BigDecimal("92"));
        row.setFirstTargetPrice(new BigDecimal("120"));
        row.setIndustryCode("I41");
        row.setIndustryName("Semiconductors");
        row.setMarketCapTier("MEGA_LARGE");
        row.setMarketRegime("RISK_ON");
        return row;
    }
}
