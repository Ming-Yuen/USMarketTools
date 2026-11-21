package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceBasisDescriptor;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceSeriesCoverageDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StructureTrendDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TrendState;
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

    private static void addBar(List<QuoteBarRow> quotes, Map<LocalDate, Integer> idxMap, String figi,
                               LocalDate date, int open, int high, int low, int close) {
        idxMap.put(date, quotes.size());
        quotes.add(new QuoteBarRow(figi, date, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), BigDecimal.valueOf(1000), Instant.parse(date + "T20:00:00Z")));
    }
}
