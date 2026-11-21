package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryMethod;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceBasisDescriptor;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceSeriesCoverageDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SetupType;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StopRuleDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StructureTrendDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetAction;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TrendState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.model.entity.SignalTrackingLog;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.scheduling.SignalReplayAuditJob.ForwardSettlement;
import com.ykm.stock.analytics.scheduling.SignalReplayAuditJob.StockBundle;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.FxRateService.FxRateLookup;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.TriggerKey;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignalReplayAuditJobTests {

    @Mock private StockRepository stockRepository;
    @Mock private StockQuoteRepository quoteRepository;
    @Mock private StockSplitRepository splitRepository;
    @Mock private StockStatusRepository statusRepository;
    @Mock private StockFinancialPeriodRepository financialPeriodRepository;
    @Mock private AdrShareRatioRepository adrShareRatioRepository;
    @Mock private StockIndustryHistoryRepository industryHistoryRepository;
    @Mock private MarketStatusService marketStatusService;
    @Mock private DateTimeService dateTimeService;
    @Mock private QuoteNormalizationService quoteNormalizationService;
    @Mock private PriceStructureService priceStructureService;
    @Mock private MarketContextService marketContextService;
    @Mock private SupportResistanceService supportResistanceService;
    @Mock private TradePlanService tradePlanService;
    @Mock private FxRateService fxRateService;

    @TempDir
    Path tempDir;

    private SignalReplayAuditJob job;

    @BeforeEach
    void setUp() {
        SignalReplayAuditJob.resetStatusForTest();
        job = new SignalReplayAuditJob(
                stockRepository, quoteRepository, splitRepository, statusRepository,
                financialPeriodRepository, adrShareRatioRepository, industryHistoryRepository,
                marketStatusService, dateTimeService, quoteNormalizationService,
                priceStructureService, marketContextService, supportResistanceService,
                tradePlanService, fxRateService, new ObjectMapper());
        job.setOutputDirPath(tempDir.toString());
    }

    @Test
    void filterPitVisiblePeriodsExcludesFutureSecFilingsAndEnforcesConservative45DayLag() {
        LocalDate replayDay = LocalDate.of(2024, 5, 10);

        StockFinancialPeriod secPast = new StockFinancialPeriod();
        secPast.setPeriodEnd(LocalDate.of(2024, 3, 31));
        secPast.setSecPeriodicAvailableFromDate(LocalDate.of(2024, 5, 2));
        // 模擬數年後 10-K 比較欄位把 secAvailableFromDate 推後，仍應以首份定期 10-Q 公開日為準避免嚴重過期 EPS
        secPast.setSecAvailableFromDate(LocalDate.of(2026, 2, 26));

        StockFinancialPeriod secFuture = new StockFinancialPeriod();
        secFuture.setPeriodEnd(LocalDate.of(2024, 3, 31));
        secFuture.setSecPeriodicAvailableFromDate(LocalDate.of(2024, 5, 11));

        StockFinancialPeriod adrPast = new StockFinancialPeriod();
        adrPast.setPeriodEnd(LocalDate.of(2024, 3, 25)); // +45d = 2024-05-09 <= 2024-05-10

        StockFinancialPeriod adrFuture = new StockFinancialPeriod();
        adrFuture.setPeriodEnd(LocalDate.of(2024, 3, 31)); // +45d = 2024-05-15 > 2024-05-10

        List<StockFinancialPeriod> visible = job.filterPitVisiblePeriods(
                List.of(secPast, secFuture, adrPast, adrFuture), replayDay);

        assertEquals(2, visible.size());
        assertTrue(visible.contains(secPast));
        assertTrue(visible.contains(adrPast));
        assertFalse(visible.contains(secFuture));
        assertFalse(visible.contains(adrFuture));
    }

    @Test
    void settleForwardHandlesNextDayOpenEntrySplitAdjustmentAndStopTargetHitOrder() {
        Stock stock = new Stock();
        stock.setFigi("FIGI_NVDA");
        stock.setSymbol("NVDA");
        stock.setCurrency("USD");

        // 2-for-1 split on 2024-06-05 (between signalDate 2024-06-03 / entryDate 2024-06-04 and later bars)
        StockSplit split = new StockSplit();
        split.setFigi("FIGI_NVDA");
        split.setSourceEventId("SPL_2024_06_05");
        split.setExecutionDate(LocalDate.of(2024, 6, 5));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(BigDecimal.valueOf(2));

        List<QuoteBarRow> quotes = new ArrayList<>();
        Map<LocalDate, Integer> idxMap = new LinkedHashMap<>();
        // Day 0: signalDate 2024-06-03, close 100
        addBar(quotes, idxMap, "FIGI_NVDA", LocalDate.of(2024, 6, 3), 99, 101, 98, 100);
        // Day 1: entryDate 2024-06-04, open 100, high 103, low 97, close 102
        addBar(quotes, idxMap, "FIGI_NVDA", LocalDate.of(2024, 6, 4), 100, 103, 97, 102);
        // Day 2: split day 2024-06-05 (2-for-1), post-split prices halved: open 51, high 56 (adj 112 -> hits target 110), low 50 (adj 100), close 55 (adj 110)
        addBar(quotes, idxMap, "FIGI_NVDA", LocalDate.of(2024, 6, 5), 51, 56, 50, 55);
        // Days 3..62: steady post-split close 55 (adj 110 -> +10% vs entryOpen 100) so full 60-session MFE/MAE is computed
        LocalDate d = LocalDate.of(2024, 6, 6);
        for (int i = 0; i < 60; i++) {
            addBar(quotes, idxMap, "FIGI_NVDA", d.plusDays(i), 54, 56, 52, 55);
        }

        StockBundle bundle = new StockBundle(
                stock, "LONG_BULL", false, false, false, false, null,
                quotes, idxMap, List.of(split), List.of(), List.of(), "Semiconductors", List.of());

        // SPY flat at 500 across the same dates
        Stock spy = new Stock();
        spy.setFigi("FIGI_SPY");
        spy.setSymbol("SPY");
        List<QuoteBarRow> spyQuotes = new ArrayList<>();
        Map<LocalDate, Integer> spyIdx = new LinkedHashMap<>();
        for (QuoteBarRow q : quotes) {
            addBar(spyQuotes, spyIdx, "FIGI_SPY", q.quoteDate(), 500, 501, 499, 500);
        }
        StockBundle spyBundle = new StockBundle(
                spy, "BENCHMARK_ETF", true, false, false, false, null,
                spyQuotes, spyIdx, List.of(), List.of(), List.of(), "ETF", List.of());

        ForwardSettlement settled = job.settleForward(
                bundle, spyBundle, LocalDate.of(2024, 6, 3),
                new BigDecimal("92"), new BigDecimal("110"));

        assertEquals(LocalDate.of(2024, 6, 4), settled.entryDate());
        assertEquals(0, new BigDecimal("100.0000").compareTo(settled.entryOpen()));
        assertEquals(0, new BigDecimal("10.00").compareTo(settled.ret5dPct()));
        assertEquals(0, new BigDecimal("10.00").compareTo(settled.ret20dPct()));
        assertEquals(0, new BigDecimal("10.00").compareTo(settled.excessRet20dPct()));
        assertEquals(SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST, settled.outcomeStatus());
        assertEquals(LocalDate.of(2024, 6, 5), settled.outcomeDate());
        assertEquals(0, new BigDecimal("12.00").compareTo(settled.mfe60dPct()));
        assertEquals(0, new BigDecimal("-3.00").compareTo(settled.mae60dPct()));
    }

    @Test
    void executeProducesUncompressedCsvFilesPersistsStatusAndBlocksInvalidDownloadType() throws Exception {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        when(dateTimeService.now()).thenReturn(now);

        LocalDate start = LocalDate.of(2024, 4, 1);
        LocalDate end = LocalDate.of(2024, 4, 3);
        List<LocalDate> replayDays = List.of(
                LocalDate.of(2024, 4, 1),
                LocalDate.of(2024, 4, 2),
                LocalDate.of(2024, 4, 3));
        when(marketStatusService.getTradingDays(start, end)).thenReturn(replayDays);
        when(marketStatusService.getLastClosedTradingDay(any())).thenReturn(end);
        when(marketStatusService.getSessionCloseAt(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return Instant.parse(d + "T20:00:00Z");
        });

        Stock spy = new Stock();
        spy.setFigi("FIGI_SPY");
        spy.setSymbol("SPY");
        spy.setStockType("ETP");
        spy.setCurrency("USD");
        spy.setActiveStatus(ActiveStatus.ACTIVE);

        Stock qqq = new Stock();
        qqq.setFigi("FIGI_QQQ");
        qqq.setSymbol("QQQ");
        qqq.setStockType("ETP");
        qqq.setCurrency("USD");
        qqq.setActiveStatus(ActiveStatus.ACTIVE);

        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(spy, qqq));
        when(statusRepository.findAllById(any())).thenReturn(List.of());
        when(splitRepository.findByFigiInOrderByExecutionDateAsc(any())).thenReturn(List.of());
        when(financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(any())).thenReturn(List.of());
        when(adrShareRatioRepository.findBySymbolIn(any())).thenReturn(List.of());
        when(industryHistoryRepository.findOverlappingByFigis(any(), any(), any())).thenReturn(List.of());
        when(fxRateService.preload(any(), any(), any())).thenReturn(mock(FxRateLookup.class));

        List<QuoteBarRow> bars = new ArrayList<>();
        LocalDate base = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 95; i++) {
            LocalDate d = base.plusDays(i);
            if (d.isAfter(end)) break;
            bars.add(new QuoteBarRow("FIGI_SPY", d, BigDecimal.valueOf(500 + i), BigDecimal.valueOf(502 + i),
                    BigDecimal.valueOf(499 + i), BigDecimal.valueOf(501 + i), BigDecimal.valueOf(1_000_000), now));
            bars.add(new QuoteBarRow("FIGI_QQQ", d, BigDecimal.valueOf(400 + i), BigDecimal.valueOf(402 + i),
                    BigDecimal.valueOf(399 + i), BigDecimal.valueOf(401 + i), BigDecimal.valueOf(1_000_000), now));
        }
        when(quoteRepository.findQuoteBarsOrdered(anyList(), any(), any())).thenReturn(bars);

        PreloadedMarketData marketDay1 = new PreloadedMarketData(
                LocalDate.of(2024, 4, 1), Instant.parse("2024-04-01T20:00:00Z"),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(),
                MetricResult.available(new MarketSnapshotDto(MarketRegime.RISK_ON, null, null, null, LocalDate.of(2024, 4, 1), now, List.of(), List.of())),
                MetricResult.available(new BigDecimal("14.5")));
        PreloadedMarketData marketDay2 = new PreloadedMarketData(
                LocalDate.of(2024, 4, 2), Instant.parse("2024-04-02T20:00:00Z"),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(),
                MetricResult.available(new MarketSnapshotDto(MarketRegime.CORRECTION, null, null, null, LocalDate.of(2024, 4, 2), now, List.of(), List.of())),
                MetricResult.available(new BigDecimal("21.2")));
        when(marketContextService.preloadMarketData(eq(LocalDate.of(2024, 4, 1)), any())).thenReturn(marketDay1);
        when(marketContextService.preloadMarketData(eq(LocalDate.of(2024, 4, 2)), any())).thenReturn(marketDay2);
        when(marketContextService.preloadMarketData(eq(LocalDate.of(2024, 4, 3)), any())).thenReturn(marketDay2);

        NormalizedBarDto dummyBar = new NormalizedBarDto(
                LocalDate.of(2024, 4, 1), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                MetricResult.available(BigDecimal.TEN), now, now, now, now, null, "B");
        NormalizedPriceSeriesDto series = new NormalizedPriceSeriesDto(
                "FIGI_SPY", "DAILY", List.of(dummyBar),
                mock(PriceBasisDescriptor.class), mock(PriceSeriesCoverageDto.class),
                MetricStatus.AVAILABLE, List.of(), null);
        when(quoteNormalizationService.normalizeFromLoaded(any(AnalysisContext.class), any(), any(), any(), any(), anyList(), anyList()))
                .thenReturn(MetricResult.available(series));

        TimeframeStructureDto dailyTf = new TimeframeStructureDto(
                AnalysisTimeframe.DAILY, List.of(dummyBar), null, List.of(), List.of(), null, null,
                MetricResult.available(new StructureTrendDto(TrendState.UPTREND, now, null, null, List.of(), now)),
                null, List.of(), List.of());
        PriceStructureDto priceStructure = new PriceStructureDto(
                "FIGI_SPY", "HASH", LocalDate.of(2024, 4, 1), List.of(dailyTf), List.of(), List.of());
        when(priceStructureService.analyze(any(), any())).thenReturn(MetricResult.available(priceStructure));
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), anyList(), anyList(), any()))
                .thenReturn(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()));
        when(supportResistanceService.analyze(any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new SupportResistanceDto(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())));
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(List.of(), BigDecimal.TEN, LocalDate.of(2024, 4, 1),
                        MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(), false)));

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        JobDataMap map = new JobDataMap();
        map.put("startDate", "2024-04-01");
        map.put("endDate", "2024-04-03");
        map.put("symbols", "SPY,QQQ");
        when(ctx.getMergedJobDataMap()).thenReturn(map);

        job.execute(ctx);

        Map<String, Object> status = job.getStatusSnapshot();
        assertEquals("COMPLETED", status.get("status"));
        assertEquals(100, status.get("progressPct"));
        assertTrue(Files.isRegularFile(tempDir.resolve("latest-status.json")));

        Path eventsCsv = job.resolveDownloadFile(SignalReplayAuditJob.FILE_TYPE_EVENTS);
        Path summaryCsv = job.resolveDownloadFile(SignalReplayAuditJob.FILE_TYPE_SUMMARY);
        assertNotNull(eventsCsv);
        assertNotNull(summaryCsv);
        assertTrue(eventsCsv.getFileName().toString().endsWith(".csv"));
        assertTrue(summaryCsv.getFileName().toString().endsWith(".csv"));

        String eventsContent = Files.readString(eventsCsv, StandardCharsets.UTF_8);
        assertTrue(eventsContent.contains("signal_date,market_phase,event_kind,symbol"));
        assertTrue(eventsContent.contains("MARKET_REGIME_CHANGE"));

        String summaryContent = Files.readString(summaryCsv, StandardCharsets.UTF_8);
        assertTrue(summaryContent.contains("section,group_key,count"));
        assertTrue(summaryContent.contains("META,FULL_REPLAY"));

        assertThrows(IllegalArgumentException.class, () -> job.resolveDownloadFile("../latest-status.json"));
        assertThrows(IllegalArgumentException.class, () -> job.resolveDownloadFile("zip"));
    }

    @Test
    void reconcilerSupportsManualOnlyScheduleWithoutCronTrigger() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        TriggerKey triggerKey = TriggerKey.triggerKey(TaskCode.SIGNAL_REPLAY_AUDIT + "-trigger", TaskGroup.SCHEDULE + "-triggers");
        when(scheduler.checkExists(triggerKey)).thenReturn(false);

        JobConfig cfg = new JobConfig();
        cfg.setTaskCode(TaskCode.SIGNAL_REPLAY_AUDIT);
        cfg.setTaskGroup(TaskGroup.SCHEDULE);
        cfg.setJobClassName(SignalReplayAuditJob.class.getName());
        cfg.setTriggerType(JobConfig.TriggerType.CRON);
        cfg.setCronExpression(null);
        cfg.setActiveStatus(ActiveStatus.ACTIVE);

        JobScheduleReconciler reconciler = new JobScheduleReconciler(scheduler);
        reconciler.reconcile(cfg);

        verify(scheduler).addJob(any(JobDetail.class), eq(true));
        verify(scheduler, never()).scheduleJob(any(JobDetail.class), any());
        assertNull(cfg.getCronExpression());
    }

    @Test
    void settleForwardMarksEntryGapAboveTargetAndBelowStopInsteadOfFakeHit() {
        Stock stock = new Stock();
        stock.setFigi("FIGI_GAP");
        stock.setSymbol("NVDA");
        stock.setCurrency("USD");

        List<QuoteBarRow> quotes = new ArrayList<>();
        Map<LocalDate, Integer> idxMap = new LinkedHashMap<>();
        // Day 0 signal: close 100, target 110, stop 92
        addBar(quotes, idxMap, "FIGI_GAP", LocalDate.of(2024, 6, 3), 99, 101, 98, 100);
        // Day 1 entry: gaps UP to open 115 (>= target 110), then sells off to close 95
        addBar(quotes, idxMap, "FIGI_GAP", LocalDate.of(2024, 6, 4), 115, 116, 94, 95);
        LocalDate d = LocalDate.of(2024, 6, 5);
        for (int i = 0; i < 60; i++) {
            addBar(quotes, idxMap, "FIGI_GAP", d.plusDays(i), 95, 97, 93, 95);
        }
        StockBundle bundle = new StockBundle(
                stock, "LONG_BULL", false, false, false, false, null,
                quotes, idxMap, List.of(), List.of(), List.of(), "Semiconductors", List.of());

        ForwardSettlement gappedUp = job.settleForward(
                bundle, null, LocalDate.of(2024, 6, 3),
                new BigDecimal("92"), new BigDecimal("110"));
        assertEquals("ENTRY_GAPPED_ABOVE_TARGET", gappedUp.outcomeStatus());
        assertEquals(LocalDate.of(2024, 6, 4), gappedUp.outcomeDate());

        // Gap DOWN below protectiveStop (entryOpen 90 <= stop 92)
        List<QuoteBarRow> downQuotes = new ArrayList<>();
        Map<LocalDate, Integer> downIdx = new LinkedHashMap<>();
        addBar(downQuotes, downIdx, "FIGI_GAP", LocalDate.of(2024, 6, 3), 99, 101, 98, 100);
        addBar(downQuotes, downIdx, "FIGI_GAP", LocalDate.of(2024, 6, 4), 90, 96, 88, 94);
        for (int i = 0; i < 60; i++) {
            addBar(downQuotes, downIdx, "FIGI_GAP", d.plusDays(i), 94, 96, 93, 95);
        }
        StockBundle downBundle = new StockBundle(
                stock, "LONG_BULL", false, false, false, false, null,
                downQuotes, downIdx, List.of(), List.of(), List.of(), "Semiconductors", List.of());
        ForwardSettlement gappedDown = job.settleForward(
                downBundle, null, LocalDate.of(2024, 6, 3),
                new BigDecimal("92"), new BigDecimal("110"));
        assertEquals("ENTRY_GAPPED_BELOW_STOP", gappedDown.outcomeStatus());
        assertEquals(LocalDate.of(2024, 6, 4), gappedDown.outcomeDate());
    }

    @Test
    void executeCapturesPlanStatusTransitionToActiveUnbiasedStanceCooldownP20P80AndNonPitMode() throws Exception {
        Instant now = Instant.parse("2026-11-21T12:00:00Z");
        when(dateTimeService.now()).thenReturn(now);

        LocalDate d1 = LocalDate.of(2024, 4, 1);
        LocalDate d2 = LocalDate.of(2024, 4, 2);
        LocalDate d3 = LocalDate.of(2024, 4, 3);
        List<LocalDate> replayDays = List.of(d1, d2, d3);
        when(marketStatusService.getTradingDays(d1, d3)).thenReturn(replayDays);
        when(marketStatusService.getTradingDays(eq(d1), eq(d2))).thenReturn(List.of(d1, d2));
        when(marketStatusService.getLastClosedTradingDay(any())).thenReturn(d3);
        when(marketStatusService.getSessionCloseAt(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return Instant.parse(d + "T20:00:00Z");
        });

        Stock spy = new Stock();
        spy.setFigi("FIGI_SPY");
        spy.setSymbol("SPY");
        spy.setStockType("ETP");
        spy.setCurrency("USD");
        spy.setActiveStatus(ActiveStatus.ACTIVE);

        Stock acme = new Stock();
        acme.setFigi("FIGI_ACME");
        acme.setSymbol("ACME");
        acme.setStockType("CS");
        acme.setCurrency("USD");
        acme.setActiveStatus(ActiveStatus.ACTIVE);

        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(spy, acme));
        when(statusRepository.findAllById(any())).thenReturn(List.of());
        when(splitRepository.findByFigiInOrderByExecutionDateAsc(any())).thenReturn(List.of());
        when(adrShareRatioRepository.findBySymbolIn(any())).thenReturn(List.of());
        when(industryHistoryRepository.findOverlappingByFigis(any(), any(), any())).thenReturn(List.of());
        when(fxRateService.preload(any(), any(), any())).thenReturn(FxRateLookup.EMPTY);

        // 10 prior month-end bars (P/E = 20, 30, 40, ..., 110) + daily bars through d3 (price=10 -> P/E=10 -> sorted 11 samples [10..110])
        // Formal P20/P50/P80 on [10..110] (n=11): pos=2 -> 30, pos=5 -> 60, pos=8 -> 90 (old P25/P75 gave 35 / 85!)
        List<QuoteBarRow> bars = new ArrayList<>();
        LocalDate mStart = LocalDate.of(2023, 6, 30);
        for (int i = 0; i < 10; i++) {
            LocalDate md = mStart.plusMonths(i);
            BigDecimal px = BigDecimal.valueOf((i + 2) * 10L); // 20..110 -> P/E 20..110 with eps=1
            bars.add(new QuoteBarRow("FIGI_ACME", md, px, px, px, px, BigDecimal.valueOf(10000), now));
            bars.add(new QuoteBarRow("FIGI_SPY", md, BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(10000), now));
        }
        // Pad >= 60 bars inside 5y window prior to p0 (2022-01-01) so month-end valuation samples stay strictly [20..110]
        LocalDate pad = LocalDate.of(2021, 1, 2);
        for (int i = 0; i < 70; i++) {
            LocalDate pd = pad.plusDays(i);
            bars.add(new QuoteBarRow("FIGI_ACME", pd, BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.valueOf(10000), now));
            bars.add(new QuoteBarRow("FIGI_SPY", pd, BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(10000), now));
        }
        // Replay days d1, d2, d3: price = 10 (PE = 10 -> DEEP_UNDERVALUED vs P20=30)
        for (LocalDate rd : replayDays) {
            bars.add(new QuoteBarRow("FIGI_ACME", rd, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.valueOf(10000), now));
            bars.add(new QuoteBarRow("FIGI_SPY", rd, BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(500), BigDecimal.valueOf(10000), now));
        }
        bars.sort(java.util.Comparator.comparing(QuoteBarRow::quoteDate));
        when(quoteRepository.findQuoteBarsOrdered(anyList(), any(), any())).thenReturn(bars);

        // Two financial periods:
        // p1 (2023-03-31, filed 2023-05-01): strong positive fundamentals (eps=1.0, rev=100, fcf=20, ebitda=30, eq=50)
        StockFinancialPeriod p0 = buildPeriod("FIGI_ACME", LocalDate.of(2022, 3, 31), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 5, 1), "1.0", "80", "16", "24", "45");
        StockFinancialPeriod p1 = buildPeriod("FIGI_ACME", LocalDate.of(2023, 3, 31), LocalDate.of(2023, 1, 1), LocalDate.of(2023, 5, 1), "1.0", "100", "20", "30", "50");
        // p2 (2024-03-31, filed on d3 = 2024-04-03): revenue crashes -20% & negative FCF -> WEAKENING -> CAUTION_VALUE_TRAP on d3!
        StockFinancialPeriod p2 = buildPeriod("FIGI_ACME", LocalDate.of(2024, 3, 31), LocalDate.of(2024, 1, 1), d3, "1.0", "80", "-5", "10", "50");
        when(financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(any())).thenReturn(List.of(p0, p1, p2));

        PreloadedMarketData riskOn = new PreloadedMarketData(
                d1, Instant.parse("2024-04-01T20:00:00Z"),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(),
                MetricResult.available(new MarketSnapshotDto(MarketRegime.RISK_ON, null, null, null, d1, now, List.of(), List.of())),
                MetricResult.available(new BigDecimal("14.0")));
        when(marketContextService.preloadMarketData(any(), any())).thenReturn(riskOn);

        NormalizedBarDto bar10 = new NormalizedBarDto(
                d1, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                MetricResult.available(BigDecimal.TEN), now, now, now, now, null, "B");
        NormalizedPriceSeriesDto series = new NormalizedPriceSeriesDto(
                "FIGI_ACME", "DAILY", List.of(bar10),
                mock(PriceBasisDescriptor.class), mock(PriceSeriesCoverageDto.class),
                MetricStatus.AVAILABLE, List.of(), null);
        when(quoteNormalizationService.normalizeFromLoaded(any(AnalysisContext.class), any(), any(), any(), any(), anyList(), anyList()))
                .thenReturn(MetricResult.available(series));

        // Weekly majorTrend = UPTREND, weekly minorTrend = DOWNTREND (verifies weekly_trend column reads majorTrend!)
        TimeframeStructureDto weeklyTf = new TimeframeStructureDto(
                AnalysisTimeframe.WEEKLY, List.of(bar10), null, List.of(), List.of(), null, null,
                MetricResult.available(new StructureTrendDto(TrendState.DOWNTREND, now, null, null, List.of(), now)),
                MetricResult.available(new StructureTrendDto(TrendState.UPTREND, now, null, null, List.of(), now)),
                List.of(), List.of());
        TimeframeStructureDto dailyTf = new TimeframeStructureDto(
                AnalysisTimeframe.DAILY, List.of(bar10), null, List.of(), List.of(), null, null,
                MetricResult.available(new StructureTrendDto(TrendState.UPTREND, now, null, null, List.of(), now)),
                MetricResult.available(new StructureTrendDto(TrendState.UPTREND, now, null, null, List.of(), now)),
                List.of(), List.of());
        PriceStructureDto priceStructure = new PriceStructureDto(
                "FIGI_ACME", "HASH", d1, List.of(dailyTf, weeklyTf), List.of(), List.of());
        when(priceStructureService.analyze(any(), any())).thenReturn(MetricResult.available(priceStructure));

        MarketContextDto allowCtx = new MarketContextDto(
                null, null,
                MetricResult.available(new MarketSnapshotDto(MarketRegime.RISK_ON, null, null, null, d1, now, List.of(), List.of())),
                List.of(),
                new PlanConstraintDto(EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), List.of()),
                List.of(), List.of(), null, null, null, null, Map.of(), null);
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), anyList(), anyList(), any()))
                .thenReturn(MetricResult.available(allowCtx));

        LevelZoneDto zoneZ1 = new LevelZoneDto(
                "Z1", AnalysisTimeframe.WEEKLY, new BigDecimal("9.8"), new BigDecimal("10.2"),
                ZoneRole.SUPPORT, new BigDecimal("70"), now, 3, now, ZoneState.ACTIVE,
                BigDecimal.TEN, new BigDecimal("1.0"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("70"),
                1, now, null, List.of(), List.of(), 2, 0, 3, null, null, RetestState.NONE, true, List.of());
        when(supportResistanceService.analyze(any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new SupportResistanceDto(List.of(), List.of(), List.of(zoneZ1), List.of(), List.of(), List.of())));

        // Day 1: Z1 plan is WAITING_TRIGGER (while unrelated Z2 plan is ACTIVE -> must NOT match Z2!); target1=9.5 (<=10), target2=15 -> firstTarget must be null!
        TradePlanDto z2ActiveUnrelated = new TradePlanDto("P_Z2", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, new StopRuleDto(new BigDecimal("8.0"), new BigDecimal("8.2"), 2, AnalysisTimeframe.DAILY, new BigDecimal("0.5"), new BigDecimal("1.0"), AnalysisTimeframe.DAILY, now, List.of()),
                List.of(new TargetDto(new BigDecimal("20"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R2", "R2", List.of())),
                MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.ACTIVE, List.of(), "Z2", 1, AnalysisTimeframe.WEEKLY, now, d3, List.of());
        TradePlanDto z1Waiting = new TradePlanDto("P_Z1_W", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, new StopRuleDto(new BigDecimal("9.0"), new BigDecimal("9.2"), 2, AnalysisTimeframe.DAILY, new BigDecimal("0.5"), new BigDecimal("1.0"), AnalysisTimeframe.DAILY, now, List.of()),
                List.of(new TargetDto(new BigDecimal("9.5"), new BigDecimal("0.5"), TargetAction.TAKE_PROFIT, "R1", "R1", List.of()),
                        new TargetDto(new BigDecimal("15.0"), new BigDecimal("0.5"), TargetAction.TAKE_PROFIT, "R2", "R2", List.of())),
                MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.WAITING_TRIGGER, List.of(), "Z1", 1, AnalysisTimeframe.WEEKLY, now, d3, List.of());
        TradePlanDto z1Active = new TradePlanDto("P_Z1_A", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, new StopRuleDto(new BigDecimal("9.0"), new BigDecimal("9.2"), 2, AnalysisTimeframe.DAILY, new BigDecimal("0.5"), new BigDecimal("1.0"), AnalysisTimeframe.DAILY, now, List.of()),
                List.of(new TargetDto(new BigDecimal("14.0"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "R1", List.of())),
                MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.ACTIVE, List.of(), "Z1", 1, AnalysisTimeframe.WEEKLY, now, d3, List.of());

        when(tradePlanService.build(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            AnalysisContext ac = inv.getArgument(0);
            Instant cutoff = ac.analysisAsOf();
            if (cutoff.equals(Instant.parse("2024-04-01T20:00:00Z"))) {
                return MetricResult.available(new TradePlanResultDto(List.of(z2ActiveUnrelated, z1Waiting), BigDecimal.TEN, d1, null, List.of(), List.of(), true));
            }
            return MetricResult.available(new TradePlanResultDto(List.of(z2ActiveUnrelated, z1Active), BigDecimal.TEN, d2, null, List.of(), List.of(), true));
        });

        // 1) PIT mode execution
        JobExecutionContext pitCtx = mock(JobExecutionContext.class);
        JobDataMap pitMap = new JobDataMap();
        pitMap.put("startDate", "2024-04-01");
        pitMap.put("endDate", "2024-04-03");
        pitMap.put("symbols", "ACME");
        when(pitCtx.getMergedJobDataMap()).thenReturn(pitMap);

        job.execute(pitCtx);

        List<String> eventLines = Files.readAllLines(job.resolveDownloadFile(SignalReplayAuditJob.FILE_TYPE_EVENTS), StandardCharsets.UTF_8);
        List<String> acmeRows = eventLines.stream().filter(l -> l.contains(",ACME,")).toList();
        // Expect 3 distinct events for ACME: d1 (WAITING_TRIGGER), d2 (planStatus -> ACTIVE!), d3 (CAUTION_VALUE_TRAP)
        assertEquals(3, acmeRows.size());
        // Day 1: weekly_trend=UPTREND, fair_value_low=30 (P20, not 35 P25!), fair_value_mid=60, plan_status=WAITING_TRIGGER, first_target empty (must not skip 9.5 to 15!)
        String[] r1 = acmeRows.get(0).split(",", -1);
        assertEquals("2024-04-01", r1[0]);
        assertEquals("UPTREND", r1[13]);
        assertEquals("30", r1[19]);
        assertEquals("60", r1[20]);
        assertEquals("ACCUMULATE_AT_SUPPORT", r1[23]);
        assertEquals("VALUE_SUPPORT", r1[24]);
        assertEquals("WAITING_TRIGGER", r1[25]);
        assertEquals("", r1[27]);

        // Day 2: emitted purely because planStatus changed WAITING_TRIGGER -> ACTIVE!
        String[] r2 = acmeRows.get(1).split(",", -1);
        assertEquals("2024-04-02", r2[0]);
        assertEquals("VALUE_SUPPORT", r2[24]);
        assertEquals("ACTIVE", r2[25]);
        assertEquals("14", r2[27]);

        // Day 3: CAUTION_VALUE_TRAP with high_risk_breakdown=false must STILL have cooldown_distinct=true!
        String[] r3 = acmeRows.get(2).split(",", -1);
        assertEquals("2024-04-03", r3[0]);
        assertEquals("false", r3[15]);
        assertEquals("CAUTION_VALUE_TRAP", r3[23]);
        assertEquals("true", r3[28]);

        String summaryContent = Files.readString(job.resolveDownloadFile(SignalReplayAuditJob.FILE_TYPE_SUMMARY), StandardCharsets.UTF_8);
        assertTrue(summaryContent.contains("valuationMode=PIT"));
        assertTrue(summaryContent.contains("BY_STANCE_DISTINCT,CAUTION_VALUE_TRAP,1,"));
        assertTrue(summaryContent.contains("BY_TRACK_PLAN_STATUS,VALUE_SUPPORT|WAITING_TRIGGER,1,"));
        assertTrue(summaryContent.contains("BY_TRACK_PLAN_STATUS,VALUE_SUPPORT|ACTIVE,1,"));

        // 2) NON_PIT mode execution: on d1 (2024-04-01), period p2 (2024-03-31) is ALREADY matched via formal matchPeriod (even before filed date 2024-04-03!)
        JobExecutionContext nonPitCtx = mock(JobExecutionContext.class);
        JobDataMap nonPitMap = new JobDataMap();
        nonPitMap.put("startDate", "2024-04-01");
        nonPitMap.put("endDate", "2024-04-01");
        nonPitMap.put("symbols", "ACME");
        nonPitMap.put("valuationMode", "NON_PIT");
        when(nonPitCtx.getMergedJobDataMap()).thenReturn(nonPitMap);
        when(marketStatusService.getTradingDays(d1, d1)).thenReturn(List.of(d1));

        job.execute(nonPitCtx);

        List<String> nonPitEvents = Files.readAllLines(job.resolveDownloadFile(SignalReplayAuditJob.FILE_TYPE_EVENTS), StandardCharsets.UTF_8);
        String acmeNonPit = nonPitEvents.stream().filter(l -> l.contains(",ACME,")).findFirst().orElseThrow();
        String[] npCols = acmeNonPit.split(",", -1);
        assertEquals("WEAKENING", npCols[21]);
        assertEquals("NON_PIT_FORMAL", npCols[22]);
        assertEquals("CAUTION_VALUE_TRAP", npCols[23]);
        String nonPitSummary = Files.readString(job.resolveDownloadFile(SignalReplayAuditJob.FILE_TYPE_SUMMARY), StandardCharsets.UTF_8);
        assertTrue(nonPitSummary.contains("valuationMode=NON_PIT"));

        // 3) Invalid valuationMode must fail fast
        JobExecutionContext badCtx = mock(JobExecutionContext.class);
        JobDataMap badMap = new JobDataMap();
        badMap.put("startDate", "2024-04-01");
        badMap.put("endDate", "2024-04-01");
        badMap.put("valuationMode", "BAD_MODE");
        when(badCtx.getMergedJobDataMap()).thenReturn(badMap);
        assertThrows(IllegalArgumentException.class, () -> job.execute(badCtx));
    }

    private static StockFinancialPeriod buildPeriod(
            String figi, LocalDate periodEnd, LocalDate periodStart, LocalDate filedDate,
            String eps, String rev, String fcf, String ebitda, String equity) {
        StockFinancialPeriod p = new StockFinancialPeriod();
        p.setFigi(figi);
        p.setPeriodEnd(periodEnd);
        p.setPeriodStart(periodStart);
        p.setReportingCurrency("USD");
        p.setSecPeriodicAvailableFromDate(filedDate);
        p.setEpsTtm(new BigDecimal(eps));
        p.setEpsTtmStatus("AVAILABLE");
        p.setEpsTtmSource("SEC");
        p.setEpsTtmPeriodStart(periodStart);
        p.setRevenueTtm(new BigDecimal(rev));
        p.setRevenueTtmStatus("AVAILABLE");
        p.setFreeCashFlowTtm(new BigDecimal(fcf));
        p.setFreeCashFlowTtmStatus("AVAILABLE");
        p.setEbitdaTtm(new BigDecimal(ebitda));
        p.setEbitdaTtmStatus("AVAILABLE");
        p.setEvAdjustment(BigDecimal.ZERO);
        p.setEvAdjustmentStatus("AVAILABLE");
        p.setCommonEquity(new BigDecimal(equity));
        p.setCommonEquityStatus("AVAILABLE");
        p.setSharesOutstanding(new BigDecimal("10"));
        p.setSharesOutstandingStatus("AVAILABLE");
        p.setSharesBasisDate(periodEnd);
        p.setSharesSecAvailableFromDate(filedDate);
        return p;
    }

    private static void addBar(List<QuoteBarRow> quotes, Map<LocalDate, Integer> idxMap, String figi,
                               LocalDate date, int open, int high, int low, int close) {
        idxMap.put(date, quotes.size());
        quotes.add(new QuoteBarRow(figi, date, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.valueOf(1000), Instant.parse(date + "T20:00:00Z")));
    }
}
