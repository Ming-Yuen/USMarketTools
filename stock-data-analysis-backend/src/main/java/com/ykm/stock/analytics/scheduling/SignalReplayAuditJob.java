package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.SignalTrackingLog;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.ObjectiveBandVerdict;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.RadarEvaluationResult;
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.FxRateService.FxRateLookup;
import com.ykm.stock.analytics.service.market.FxRateService.FxResolution;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.valuation.FundamentalSnapshotCalculator;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.DayEpsResolution;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手動觸發型全系統歷史信號回放與準確度審計排程（唯讀所有正式業務表，絕不覆寫任何實盤結果表）。
 * 直接重用正式計算鏈（MarketContextService、QuoteNormalizationService、PriceStructureService、
 * SupportResistanceService、TradePlanService、FundamentalSnapshotCalculator、ValuationSummaryCalculator），
 * 財報採嚴格 Point-in-Time（sec_periodic_available_from_date <= D，缺 SEC 採 period_end + 45d 保守延遲）杜絕未來函數，
 * 跑完後產出兩份未壓縮獨立 .csv（事件明細與量化成績單）供 /jobConfig 直接下載。
 */
@Slf4j
@DisallowConcurrentExecution
@Component(TaskCode.SIGNAL_REPLAY_AUDIT)
public class SignalReplayAuditJob implements Job {
    public static final String FILE_TYPE_EVENTS = "events";
    public static final String FILE_TYPE_SUMMARY = "summary";
    private static final LocalDate DEFAULT_START_DATE = LocalDate.of(2019, 1, 1);
    private static final int MIN_HISTORICAL_SAMPLES = 6;
    private static final long CONSERVATIVE_FILING_LAG_DAYS = 45L;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_SUPPORT_UPPER_FACTOR = new BigDecimal("1.03");
    private static final BigDecimal HALF_ATR = new BigDecimal("0.50");
    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.of("Asia/Hong_Kong"));

    /** 預設跨產業代表股池（含大盤 ETF、長牛龍頭、曾大幅腰斬之基本面惡化股、金融、能源工業防禦與 ADR，避免倖存者偏差）。 */
    private static final Map<String, String> DEFAULT_SYMBOL_GROUPS = createDefaultSymbolGroups();

    private static final List<TurningPointSpec> HISTORICAL_TURNING_POINTS = List.of(
            new TurningPointSpec("2020_COVID_TOP", "TOP", LocalDate.of(2020, 2, 19), LocalDate.of(2020, 3, 23)),
            new TurningPointSpec("2020_COVID_BOTTOM", "BOTTOM", LocalDate.of(2020, 3, 23), LocalDate.of(2020, 8, 31)),
            new TurningPointSpec("2022_BEAR_TOP", "TOP", LocalDate.of(2022, 1, 3), LocalDate.of(2022, 6, 30)),
            new TurningPointSpec("2022_BEAR_BOTTOM", "BOTTOM", LocalDate.of(2022, 10, 12), LocalDate.of(2023, 3, 31)),
            new TurningPointSpec("2025_SPRING_TOP", "TOP", LocalDate.of(2025, 2, 19), LocalDate.of(2025, 4, 8)),
            new TurningPointSpec("2025_SPRING_BOTTOM", "BOTTOM", LocalDate.of(2025, 4, 8), LocalDate.of(2025, 7, 31)),
            new TurningPointSpec("2026_AUTUMN_TOP", "TOP", LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 20))
    );

    private final StockRepository stockRepository;
    private final StockQuoteRepository quoteRepository;
    private final StockSplitRepository splitRepository;
    private final StockStatusRepository statusRepository;
    private final StockFinancialPeriodRepository financialPeriodRepository;
    private final AdrShareRatioRepository adrShareRatioRepository;
    private final StockIndustryHistoryRepository industryHistoryRepository;
    private final MarketStatusService marketStatusService;
    private final DateTimeService dateTimeService;
    private final QuoteNormalizationService quoteNormalizationService;
    private final PriceStructureService priceStructureService;
    private final MarketContextService marketContextService;
    private final SupportResistanceService supportResistanceService;
    private final TradePlanService tradePlanService;
    private final FxRateService fxRateService;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;

    @Value("${app.signal-replay.output-dir:${java.io.tmpdir}/usmarkettools-signal-replay}")
    private String outputDirPath = Paths.get(System.getProperty("java.io.tmpdir"), "usmarkettools-signal-replay").toString();

    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();
    private final FundamentalSnapshotCalculator fundamentalCalculator = new FundamentalSnapshotCalculator();
    /** Quartz 每次觸發都會建立新 Job 實例；使用 static 共享給 JobController 單例讀取即時進度。 */
    private static final AtomicReference<Map<String, Object>> STATUS_REF = new AtomicReference<>();

    static void resetStatusForTest() { STATUS_REF.set(null); }

    private record TurningPointSpec(String id, String kind, LocalDate actualDate, LocalDate searchEndDate) {}

    private record MonthEndPitMultiples(LocalDate date, Map<String, BigDecimal> multiples) {}

    record StockBundle(
            Stock stock,
            String groupTag,
            boolean etf,
            boolean adr,
            boolean verifiedAdr,
            boolean financialInstitution,
            StockStatus status,
            List<QuoteBarRow> quotes,
            Map<LocalDate, Integer> quoteIndexByDate,
            List<StockSplit> splits,
            List<StockFinancialPeriod> periods,
            List<StockIndustryHistory> industryHistory,
            String currentIndustryName,
            List<MonthEndPitMultiples> monthEndPitMultiples
    ) {}

    private record DailyMarketContext(
            LocalDate day,
            Instant cutoff,
            String phase,
            PreloadedMarketData preloaded,
            MarketRegime regime,
            EntryPermission permission,
            BigDecimal vixClose,
            BigDecimal treasury10y,
            String creditSpreadStatus
    ) {}

    private record PitValuationSnapshot(
            String primaryMetric,
            BigDecimal currentMultiple,
            String verdict,
            BigDecimal fairValueLow,
            BigDecimal fairValueMid,
            BigDecimal fairValueHigh,
            BigDecimal historicalPercentile,
            String fundamentalBacking,
            FundamentalSnapshotDto fundamentals,
            String pitBasis
    ) {}

    private record StockStateKey(
            String stance,
            String radarTrack,
            String weeklyTrend,
            String dailyMinorTrend,
            boolean highRiskBreakdown
    ) {}

    record ForwardSettlement(
            LocalDate entryDate,
            BigDecimal entryOpen,
            BigDecimal ret5dPct,
            BigDecimal ret20dPct,
            BigDecimal ret60dPct,
            BigDecimal spyRet20dPct,
            BigDecimal excessRet20dPct,
            String outcomeStatus,
            LocalDate outcomeDate,
            BigDecimal mfe60dPct,
            BigDecimal mae60dPct
    ) {}

    private record EventRow(
            LocalDate signalDate,
            String phase,
            String eventKind,
            String symbol,
            String stockGroup,
            String industryName,
            String classificationBasis,
            String marketRegime,
            String marketGate,
            BigDecimal vixClose,
            BigDecimal treasury10y,
            String creditSpreadStatus,
            BigDecimal signalClose,
            String weeklyTrend,
            String dailyMinorTrend,
            boolean highRiskBreakdown,
            String valuationPrimaryMetric,
            BigDecimal valuationMultiple,
            String valuationVerdict,
            BigDecimal fairValueLow,
            BigDecimal fairValueMid,
            String fundamentalBacking,
            String pitBasis,
            String stance,
            String radarTrack,
            String planStatus,
            BigDecimal protectiveStop,
            BigDecimal firstTarget,
            boolean cooldownDistinct,
            ForwardSettlement settlement
    ) {}

    private record DailyRegimeRecord(
            LocalDate date,
            String phase,
            MarketRegime regime,
            EntryPermission permission,
            BigDecimal spyClose,
            BigDecimal qqqClose,
            BigDecimal spyNextDayRetPct,
            BigDecimal qqqNextDayRetPct
    ) {}

    private record GroupAccumulator(
            int totalCount,
            int ret5Count,
            BigDecimal ret5Sum,
            int ret20Count,
            BigDecimal ret20Sum,
            int win20Count,
            int excess20Count,
            BigDecimal excess20Sum,
            int ret60Count,
            BigDecimal ret60Sum,
            int targetHitCount,
            int stopHitCount,
            int resolvedOutcomeCount,
            int mfeCount,
            BigDecimal mfeSum,
            int maeCount,
            BigDecimal maeSum
    ) {
        static GroupAccumulator empty() {
            return new GroupAccumulator(0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO,
                    0, BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        }

        GroupAccumulator add(ForwardSettlement s) {
            int nTotal = totalCount + 1;
            int n5 = ret5Count;
            BigDecimal s5 = ret5Sum;
            if (s.ret5dPct() != null) { n5++; s5 = s5.add(s.ret5dPct()); }
            int n20 = ret20Count;
            BigDecimal s20 = ret20Sum;
            int w20 = win20Count;
            if (s.ret20dPct() != null) {
                n20++;
                s20 = s20.add(s.ret20dPct());
                if (s.ret20dPct().signum() > 0) w20++;
            }
            int nex20 = excess20Count;
            BigDecimal sex20 = excess20Sum;
            if (s.excessRet20dPct() != null) { nex20++; sex20 = sex20.add(s.excessRet20dPct()); }
            int n60 = ret60Count;
            BigDecimal s60 = ret60Sum;
            if (s.ret60dPct() != null) { n60++; s60 = s60.add(s.ret60dPct()); }
            int tHit = targetHitCount;
            int stHit = stopHitCount;
            int res = resolvedOutcomeCount;
            if (SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST.equals(s.outcomeStatus())) { tHit++; res++; }
            else if (SignalTrackingLog.OUTCOME_STOP_HIT_FIRST.equals(s.outcomeStatus())) { stHit++; res++; }
            else if (SignalTrackingLog.OUTCOME_EXPIRED_NEITHER.equals(s.outcomeStatus())
                    || SignalTrackingLog.OUTCOME_AMBIGUOUS_SAME_BAR.equals(s.outcomeStatus())) { res++; }
            int nMfe = mfeCount;
            BigDecimal sMfe = mfeSum;
            if (s.mfe60dPct() != null) { nMfe++; sMfe = sMfe.add(s.mfe60dPct()); }
            int nMae = maeCount;
            BigDecimal sMae = maeSum;
            if (s.mae60dPct() != null) { nMae++; sMae = sMae.add(s.mae60dPct()); }
            return new GroupAccumulator(nTotal, n5, s5, n20, s20, w20, nex20, sex20, n60, s60, tHit, stHit, res, nMfe, sMfe, nMae, sMae);
        }
    }

    @Autowired
    public SignalReplayAuditJob(
            StockRepository stockRepository,
            StockQuoteRepository quoteRepository,
            StockSplitRepository splitRepository,
            StockStatusRepository statusRepository,
            StockFinancialPeriodRepository financialPeriodRepository,
            AdrShareRatioRepository adrShareRatioRepository,
            StockIndustryHistoryRepository industryHistoryRepository,
            MarketStatusService marketStatusService,
            DateTimeService dateTimeService,
            QuoteNormalizationService quoteNormalizationService,
            PriceStructureService priceStructureService,
            MarketContextService marketContextService,
            SupportResistanceService supportResistanceService,
            TradePlanService tradePlanService,
            FxRateService fxRateService,
            ObjectMapper objectMapper) {
        this.stockRepository = stockRepository;
        this.quoteRepository = quoteRepository;
        this.splitRepository = splitRepository;
        this.statusRepository = statusRepository;
        this.financialPeriodRepository = financialPeriodRepository;
        this.adrShareRatioRepository = adrShareRatioRepository;
        this.industryHistoryRepository = industryHistoryRepository;
        this.marketStatusService = marketStatusService;
        this.dateTimeService = dateTimeService;
        this.quoteNormalizationService = quoteNormalizationService;
        this.priceStructureService = priceStructureService;
        this.marketContextService = marketContextService;
        this.supportResistanceService = supportResistanceService;
        this.tradePlanService = tradePlanService;
        this.fxRateService = fxRateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(JobExecutionContext context) {
        Instant startedAt = dateTimeService.now();
        String runId = RUN_ID_FMT.format(startedAt);
        JobDataMap params = context == null ? new JobDataMap() : context.getMergedJobDataMap();
        LocalDate lastClosed = marketStatusService.getLastClosedTradingDay(startedAt);
        LocalDate rawEndDate = resolveDateParam(params, "endDate", lastClosed);
        LocalDate endDate = (rawEndDate != null && lastClosed != null && rawEndDate.isAfter(lastClosed)) ? lastClosed : rawEndDate;
        LocalDate startDate = resolveDateParam(params, "startDate", DEFAULT_START_DATE);
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("INVALID_SIGNAL_REPLAY_DATE_RANGE");
        }
        List<String> requestedSymbols = parseSymbolsParam(params.getString("symbols"));
        boolean defaultUniverse = requestedSymbols.isEmpty();

        updateStatus("RUNNING", runId, startedAt, null, startDate, endDate, 0, 0, 0, null, 0, 0, null, List.of());
        try {
            // 1. 解析代表性樣本池並唯讀預載 5 年前置日線、拆股、財季分母、外幣匯率與產業歷史
            List<LocalDate> replayDays = marketStatusService.getTradingDays(startDate, endDate);
            if (replayDays.isEmpty()) {
                throw new IllegalStateException("SIGNAL_REPLAY_NO_TRADING_DAYS");
            }
            List<StockBundle> rawBundles = loadStockBundles(requestedSymbols, startDate.minusYears(5), endDate);
            if (rawBundles.isEmpty()) {
                throw new IllegalStateException("SIGNAL_REPLAY_EMPTY_STOCK_POOL");
            }
            Set<String> allCurrencies = collectReportingCurrencies(rawBundles);
            FxRateLookup fxLookup = fxRateService.preload(allCurrencies, startDate.minusYears(5), endDate);
            List<StockBundle> bundles = attachMonthEndPitMultiples(rawBundles, fxLookup);
            StockBundle spyBundle = findBundleBySymbol(bundles, "SPY");
            StockBundle qqqBundle = findBundleBySymbol(bundles, "QQQ");

            // 2a. 逐日預載大盤背景與體制切換事件（每日僅查詢一次大盤序列，零重複 DB 負擔）
            List<EventRow> marketEvents = new ArrayList<>();
            List<DailyRegimeRecord> dailyRegimes = new ArrayList<>();
            List<DailyMarketContext> dailyContexts = new ArrayList<>(replayDays.size());
            MarketRegime previousRegime = null;
            int totalDays = replayDays.size();

            for (int dayIdx = 0; dayIdx < totalDays; dayIdx++) {
                LocalDate day = replayDays.get(dayIdx);
                Instant cutoff = marketStatusService.getSessionCloseAt(day);
                String phase = classifyMarketPhase(day);

                PreloadedMarketData preloadedMarket = marketContextService.preloadMarketData(day, cutoff);
                MarketRegime currentRegime = extractPreloadedRegime(preloadedMarket);
                EntryPermission currentPermission = extractPreloadedPermission(preloadedMarket);
                BigDecimal vixClose = preloadedMarket.vixClose() != null && preloadedMarket.vixClose().status() == MetricStatus.AVAILABLE
                        ? preloadedMarket.vixClose().value() : null;
                BigDecimal treasury10y = preloadedMarket.validatedTenYearYieldPct();
                CreditSpreadSummaryDto creditSpread = preloadedMarket.creditSpread();
                String creditSpreadStatus = creditSpread != null ? creditSpread.status() : "UNAVAILABLE";

                dailyContexts.add(new DailyMarketContext(day, cutoff, phase, preloadedMarket,
                        currentRegime, currentPermission, vixClose, treasury10y, creditSpreadStatus));

                BigDecimal spyClose = closeOnDate(spyBundle, day);
                BigDecimal qqqClose = closeOnDate(qqqBundle, day);
                BigDecimal spyNextRet = nextTradingDayReturnPct(spyBundle, day);
                BigDecimal qqqNextRet = nextTradingDayReturnPct(qqqBundle, day);
                dailyRegimes.add(new DailyRegimeRecord(day, phase, currentRegime, currentPermission,
                        spyClose, qqqClose, spyNextRet, qqqNextRet));

                boolean regimeChanged = currentRegime != null && (previousRegime == null || currentRegime != previousRegime);
                if (regimeChanged || dayIdx == totalDays - 1) {
                    ForwardSettlement spySettle = spyBundle == null
                            ? emptySettlement()
                            : settleForward(spyBundle, spyBundle, day, null, null);
                    String kind = regimeChanged ? "MARKET_REGIME_CHANGE" : "MARKET_REGIME_LATEST";
                    marketEvents.add(new EventRow(
                            day, phase, kind, "SPY/QQQ", "BENCHMARK_INDEX", "US_BROAD_MARKET", "BENCHMARK",
                            regimeName(currentRegime), permissionName(currentPermission),
                            vixClose, treasury10y, creditSpreadStatus,
                            spyClose, null, null, false,
                            null, null, null, null, null, null, "MARKET_EOD",
                            regimeName(currentRegime), "MARKET_REGIME", null, null, null,
                            regimeChanged, spySettle));
                    if (currentRegime != null) {
                        previousRegime = currentRegime;
                    }
                }
                if (dayIdx == 0 || (dayIdx + 1) % 100 == 0) {
                    updateStatus("RUNNING", runId, startedAt, null, startDate, endDate,
                            0, totalDays, bundles.size(), day, 0, marketEvents.size(), null, List.of());
                }
            }

            // 2b. 以有界多執行緒按股票平行重放（各股狀態完全獨立、零寫入正式表，顯著縮短 2019–2026 全歷史耗時）
            int totalExpectedSnapshots = Math.max(1, totalDays * bundles.size());
            AtomicInteger snapshotCounter = new AtomicInteger(0);
            AtomicInteger emittedCounter = new AtomicInteger(marketEvents.size());
            int workers = Math.min(Math.max(1, bundles.size()), Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors())));
            List<EventRow> events = new ArrayList<>(marketEvents);
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            try {
                List<Callable<List<EventRow>>> tasks = new ArrayList<>(bundles.size());
                for (StockBundle bundle : bundles) {
                    tasks.add(() -> replaySingleStock(
                            bundle, spyBundle, dailyContexts, fxLookup,
                            runId, startedAt, startDate, endDate, totalDays, bundles.size(),
                            totalExpectedSnapshots, snapshotCounter, emittedCounter));
                }
                List<Future<List<EventRow>>> futures = executor.invokeAll(tasks);
                for (Future<List<EventRow>> future : futures) {
                    events.addAll(future.get());
                }
            } finally {
                executor.shutdownNow();
            }
            events.sort(Comparator.comparing(EventRow::signalDate)
                    .thenComparingInt(r -> "MARKET_REGIME_CHANGE".equals(r.eventKind()) || "MARKET_REGIME_LATEST".equals(r.eventKind()) ? 0 : 1)
                    .thenComparing(EventRow::symbol));
            int processedSnapshots = snapshotCounter.get();

            // 3. 寫出兩份獨立未壓縮 .csv 檔案至暫存輸出目錄（不污染 Git 工作區與正式業務表）
            Path outDir = resolveOutputDir();
            Files.createDirectories(outDir);
            String eventsFileName = "signal_replay_events_" + runId + ".csv";
            String summaryFileName = "signal_replay_summary_" + runId + ".csv";
            Path eventsPath = outDir.resolve(eventsFileName);
            Path summaryPath = outDir.resolve(summaryFileName);
            writeEventsCsv(eventsPath, events);
            int summaryRowCount = writeSummaryCsv(summaryPath, startDate, endDate, bundles.size(), dailyRegimes, events, spyBundle, qqqBundle);

            List<Map<String, Object>> files = List.of(
                    fileDescriptor(FILE_TYPE_EVENTS, eventsFileName, Files.size(eventsPath), events.size()),
                    fileDescriptor(FILE_TYPE_SUMMARY, summaryFileName, Files.size(summaryPath), summaryRowCount)
            );
            Instant finishedAt = dateTimeService.now();
            updateStatus("COMPLETED", runId, startedAt, finishedAt, startDate, endDate,
                    totalDays, totalDays, bundles.size(), endDate, processedSnapshots, events.size(), null, files);
            if (defaultUniverse && processedSnapshots > 0 && bundles.size() == DEFAULT_SYMBOL_GROUPS.size()) {
                JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.SIGNAL_REPLAY_AUDIT, endDate);
            }
            log.info("Signal replay audit completed | runId={} startDate={} endDate={} symbols={} snapshots={} events={} outDir={}",
                    runId, startDate, endDate, bundles.size(), processedSnapshots, events.size(), outDir);
        } catch (Exception ex) {
            Instant failedAt = dateTimeService.now();
            updateStatus("FAILED", runId, startedAt, failedAt, startDate, endDate,
                    0, 0, 0, null, 0, 0, ex.getMessage(), List.of());
            log.error("Signal replay audit failed | runId={}", runId, ex);
            throw new IllegalStateException("SIGNAL_REPLAY_AUDIT_FAILED: " + ex.getMessage(), ex);
        }
    }

    /** 單檔股票完整逐日回放工作單元（每檔獨立維護狀態與冷卻期，零跨執行緒共享寫入）。 */
    private List<EventRow> replaySingleStock(
            StockBundle bundle,
            StockBundle spyBundle,
            List<DailyMarketContext> dailyContexts,
            FxRateLookup fxLookup,
            String runId,
            Instant startedAt,
            LocalDate startDate,
            LocalDate endDate,
            int totalDays,
            int totalSymbols,
            int totalExpectedSnapshots,
            AtomicInteger snapshotCounter,
            AtomicInteger emittedCounter) {
        List<EventRow> stockEvents = new ArrayList<>();
        StockStateKey prevState = null;
        Map<String, LocalDate> lastDistinctSignalDate = new HashMap<>();

        for (int dayIdx = 0; dayIdx < totalDays; dayIdx++) {
            DailyMarketContext ctx = dailyContexts.get(dayIdx);
            LocalDate day = ctx.day();
            Integer endQuoteIdx = bundle.quoteIndexByDate().get(day);
            if (endQuoteIdx == null) {
                continue;
            }
            int doneSnapshots = snapshotCounter.incrementAndGet();
            List<QuoteBarRow> windowQuotes = sliceQuotesEndingAt(bundle.quotes(), day.minusYears(5), endQuoteIdx);
            if (windowQuotes.size() < 60) {
                continue;
            }
            AnalysisContext analysisContext = new AnalysisContext(bundle.stock().getFigi(), ctx.cutoff(), ctx.cutoff(), AnalysisHorizon.POSITION);
            MetricResult<NormalizedPriceSeriesDto> price = quoteNormalizationService.normalizeFromLoaded(
                    analysisContext, day.minusYears(5), day, bundle.stock(), bundle.status(), windowQuotes, bundle.splits());
            if (price.status() != MetricStatus.AVAILABLE || price.value() == null || price.value().bars().isEmpty()) {
                continue;
            }
            MetricResult<PriceStructureDto> structure = priceStructureService.analyze(analysisContext, price.value());
            if (structure.status() != MetricStatus.AVAILABLE || structure.value() == null) {
                continue;
            }
            MetricResult<MarketContextDto> marketResult = marketContextService.evaluateWithPreloaded(
                    analysisContext, price.value(), structure.value(), List.of(), List.of(), ctx.preloaded());
            MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
            MetricResult<SupportResistanceDto> support = supportResistanceService.analyze(
                    analysisContext, price.value(), structure.value(), marketValue);
            MetricResult<TradePlanResultDto> tradePlans = MetricResult.unavailable(
                    MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
            if (support.status() == MetricStatus.AVAILABLE) {
                PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
                tradePlans = tradePlanService.build(analysisContext, price.value(), structure.value(), support.value(), constraint);
            }

            BigDecimal closePrice = windowQuotes.getLast().closePrice();
            String weeklyTrend = extractTrend(structure.value(), AnalysisTimeframe.WEEKLY);
            String dailyMinorTrend = ValuationSummaryCalculator.extractDailyMinorTrend(structure);
            IndustryResolution indRes = resolveIndustryAt(bundle, ctx.cutoff());

            String stance;
            String radarTrack;
            boolean highRiskBreakdown;
            LevelZoneDto supportZone = null;
            PitValuationSnapshot pitVal;
            if (bundle.etf()) {
                MetricResult<InvestmentSummaryDto> etfSummary = summaryCalculator.calculateForEtf(
                        AnalysisHorizon.POSITION, closePrice, bundle.stock().getCurrency(),
                        structure, support, tradePlans, marketResult, false);
                InvestmentSummaryDto sumVal = etfSummary.status() == MetricStatus.AVAILABLE ? etfSummary.value() : null;
                stance = sumVal != null ? sumVal.stance() : "INSUFFICIENT_DATA";
                highRiskBreakdown = sumVal != null && sumVal.breakoutDiagnosis() != null
                        && sumVal.breakoutDiagnosis().diagnosis() != null
                        && "HIGH_RISK_BREAKDOWN".equals(sumVal.breakoutDiagnosis().diagnosis().name());
                radarTrack = "ETF_TECHNICAL_BULLISH_SUPPORT".equals(stance) ? "ETF_BULLISH" : "NONE";
                pitVal = new PitValuationSnapshot(null, null, "NOT_APPLICABLE_FOR_ETF",
                        null, null, null, null, "NOT_APPLICABLE_FOR_ETF", null, "ETF_NO_FINANCIALS");
            } else {
                pitVal = evaluatePitValuation(bundle, day, closePrice, fxLookup);
                RadarEvaluationResult eval = summaryCalculator.evaluateForRadar(
                        AnalysisHorizon.POSITION,
                        closePrice,
                        pitVal.verdict(),
                        pitVal.fairValueLow(),
                        pitVal.fundamentalBacking(),
                        pitVal.fundamentals(),
                        null,
                        structure,
                        support,
                        tradePlans,
                        marketResult,
                        false);
                stance = eval.stance();
                highRiskBreakdown = eval.highRiskBreakdown();
                supportZone = eval.supportZone();
                radarTrack = resolveRadarTrack(eval, closePrice, pitVal.verdict(), pitVal.fundamentalBacking());
            }

            StockStateKey nextState = new StockStateKey(stance, radarTrack, weeklyTrend, dailyMinorTrend, highRiskBreakdown);
            boolean isActionableSignal = isActionableBuyOrDefensiveSignal(stance, radarTrack, highRiskBreakdown);
            boolean stateChanged = prevState == null
                    || !Objects.equals(prevState.stance(), nextState.stance())
                    || !Objects.equals(prevState.radarTrack(), nextState.radarTrack())
                    || prevState.highRiskBreakdown() != nextState.highRiskBreakdown()
                    || !Objects.equals(prevState.weeklyTrend(), nextState.weeklyTrend());
            prevState = nextState;

            if (stateChanged || dayIdx == totalDays - 1) {
                List<TradePlanDto> planList = tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                        ? tradePlans.value().plans() : List.of();
                TradePlanDto matchedPlan = findMatchingPlan(planList, supportZone);
                BigDecimal protectiveStop = roundPrice(resolveProtectiveStop(matchedPlan, supportZone));
                BigDecimal firstTarget = roundPrice(resolveFirstTargetAbovePrice(matchedPlan, closePrice));
                String planStatus = matchedPlan != null && matchedPlan.status() != null ? matchedPlan.status().name() : null;

                String cooldownKey = stance == null ? "NONE" : stance;
                LocalDate prevCooldownDate = lastDistinctSignalDate.get(cooldownKey);
                boolean cooldownDistinct = isActionableSignal
                        && (prevCooldownDate == null || isOutsideCooldown(prevCooldownDate, day));
                if (cooldownDistinct) {
                    lastDistinctSignalDate.put(cooldownKey, day);
                }

                ForwardSettlement settlement = settleForward(bundle, spyBundle, day, protectiveStop, firstTarget);
                String eventKind = dayIdx == totalDays - 1 && !stateChanged ? "STOCK_LATEST_SNAPSHOT"
                        : (isActionableSignal ? "STOCK_SIGNAL_EVENT" : "STOCK_STATE_TRANSITION");
                stockEvents.add(new EventRow(
                        day, ctx.phase(), eventKind, bundle.stock().getSymbol(), bundle.groupTag(),
                        indRes.industryName(), indRes.classificationBasis(),
                        regimeName(ctx.regime()), permissionName(ctx.permission()),
                        ctx.vixClose(), ctx.treasury10y(), ctx.creditSpreadStatus(),
                        closePrice, weeklyTrend, dailyMinorTrend, highRiskBreakdown,
                        pitVal.primaryMetric(), pitVal.currentMultiple(), pitVal.verdict(),
                        pitVal.fairValueLow(), pitVal.fairValueMid(), pitVal.fundamentalBacking(), pitVal.pitBasis(),
                        stance, radarTrack, planStatus, protectiveStop, firstTarget,
                        cooldownDistinct, settlement));
                emittedCounter.incrementAndGet();
            }

            if (doneSnapshots == 1 || doneSnapshots % 400 == 0) {
                int approxDays = Math.min(totalDays, (int) Math.round((doneSnapshots * (double) totalDays) / totalExpectedSnapshots));
                updateStatus("RUNNING", runId, startedAt, null, startDate, endDate,
                        approxDays, totalDays, totalSymbols, day, doneSnapshots, emittedCounter.get(), null, List.of());
            }
        }
        return stockEvents;
    }

    private BigDecimal roundPrice(BigDecimal price) {
        return price == null ? null : price.setScale(4, RoundingMode.HALF_UP);
    }

    /** 供 JobController 查詢最新回放進度與可下載 CSV 清單（重啟後自動自磁碟還原最近一次完成紀錄）。 */
    public Map<String, Object> getStatusSnapshot() {
        Map<String, Object> current = STATUS_REF.get();
        if (current != null) {
            return current;
        }
        Map<String, Object> persisted = readPersistedStatus();
        if (persisted != null) {
            STATUS_REF.compareAndSet(null, persisted);
            return persisted;
        }
        Map<String, Object> idle = new LinkedHashMap<>();
        idle.put("status", "IDLE");
        idle.put("progressPct", 0);
        idle.put("files", List.of());
        return idle;
    }

    /** 供 JobController 直接串流未壓縮 .csv 檔案，並以 realPath 與副檔名嚴格防範路徑穿越及符號連結外逃。 */
    public Path resolveDownloadFile(String fileType) {
        String normalized = fileType == null ? "" : fileType.trim().toLowerCase(Locale.ROOT);
        if (!FILE_TYPE_EVENTS.equals(normalized) && !FILE_TYPE_SUMMARY.equals(normalized)) {
            throw new IllegalArgumentException("INVALID_SIGNAL_REPLAY_FILE_TYPE");
        }
        Map<String, Object> snapshot = getStatusSnapshot();
        Object rawFiles = snapshot.get("files");
        if (!(rawFiles instanceof List<?> list)) {
            return null;
        }
        Path outDir;
        try {
            outDir = resolveOutputDir().toRealPath();
        } catch (IOException ex) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            if (normalized.equals(map.get("type")) && map.get("fileName") instanceof String fileName
                    && !fileName.isBlank() && fileName.endsWith(".csv")
                    && !fileName.contains("/") && !fileName.contains("\\")) {
                Path candidate = outDir.resolve(fileName).normalize();
                if (!candidate.startsWith(outDir) || !Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    Path realCandidate = candidate.toRealPath();
                    if (realCandidate.startsWith(outDir) && Files.isRegularFile(realCandidate)) {
                        return realCandidate;
                    }
                } catch (IOException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    // --- 樣本池與資料唯讀預載 ---

    private List<StockBundle> loadStockBundles(List<String> requestedSymbols, LocalDate preloadFrom, LocalDate preloadThrough) {
        List<String> targetSymbols = requestedSymbols.isEmpty()
                ? new ArrayList<>(DEFAULT_SYMBOL_GROUPS.keySet())
                : new ArrayList<>(new LinkedHashSet<>(requestedSymbols));
        if (!targetSymbols.contains("SPY")) targetSymbols.addFirst("SPY");
        if (!targetSymbols.contains("QQQ")) targetSymbols.add(1, "QQQ");

        List<Stock> foundStocks = stockRepository.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, targetSymbols);
        Map<String, Stock> bySymbol = new LinkedHashMap<>();
        for (Stock s : foundStocks) {
            if (s != null && s.getSymbol() != null) {
                bySymbol.putIfAbsent(s.getSymbol().trim().toUpperCase(Locale.ROOT), s);
            }
        }
        List<Stock> orderedStocks = new ArrayList<>();
        for (String sym : targetSymbols) {
            Stock matched = bySymbol.get(sym);
            if (matched != null) {
                orderedStocks.add(matched);
            }
        }
        if (orderedStocks.isEmpty()) {
            return List.of();
        }

        List<String> figis = orderedStocks.stream().map(Stock::getFigi).toList();
        Map<String, StockStatus> statusMap = new HashMap<>();
        for (StockStatus st : statusRepository.findAllById(figis)) {
            statusMap.put(st.getFigi(), st);
        }
        Map<String, List<QuoteBarRow>> quotesByFigi = new HashMap<>();
        for (QuoteBarRow bar : quoteRepository.findQuoteBarsOrdered(figis, preloadFrom, preloadThrough)) {
            quotesByFigi.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
        }
        Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
        for (StockSplit sp : splitRepository.findByFigiInOrderByExecutionDateAsc(figis)) {
            splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
        }
        Map<String, List<StockFinancialPeriod>> periodsByFigi = new HashMap<>();
        for (StockFinancialPeriod fp : financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(figis)) {
            periodsByFigi.computeIfAbsent(fp.getFigi(), k -> new ArrayList<>()).add(fp);
        }
        Map<String, AdrShareRatio> adrBySymbol = new HashMap<>();
        for (AdrShareRatio ratio : adrShareRatioRepository.findBySymbolIn(targetSymbols)) {
            if (ratio.getSymbol() != null) {
                adrBySymbol.put(ratio.getSymbol().trim().toUpperCase(Locale.ROOT), ratio);
            }
        }
        Instant windowStart = preloadFrom.atStartOfDay(MarketStatusService.NY_ZONE).toInstant();
        Instant windowEnd = preloadThrough.plusDays(1).atStartOfDay(MarketStatusService.NY_ZONE).toInstant();
        Map<String, List<StockIndustryHistory>> historyByFigi = new HashMap<>();
        for (StockIndustryHistory h : industryHistoryRepository.findOverlappingByFigis(figis, windowStart, windowEnd)) {
            historyByFigi.computeIfAbsent(h.getFigi(), k -> new ArrayList<>()).add(h);
        }
        Map<String, String> currentIndustryByFigi = new HashMap<>();
        for (StockIndustryHistory cur : industryHistoryRepository.findCurrentByFigis(figis)) {
            if (cur.getIndustry() != null) {
                currentIndustryByFigi.putIfAbsent(cur.getFigi(), cur.getIndustry().getIndustry());
            }
        }

        List<StockBundle> bundles = new ArrayList<>(orderedStocks.size());
        for (Stock s : orderedStocks) {
            String sym = s.getSymbol().trim().toUpperCase(Locale.ROOT);
            String groupTag = DEFAULT_SYMBOL_GROUPS.getOrDefault(sym,
                    SecurityTypePolicy.isEtfType(s.getStockType()) ? "BENCHMARK_ETF" : "CUSTOM_STOCK");
            boolean etf = SecurityTypePolicy.isEtfType(s.getStockType());
            boolean adr = SecurityTypePolicy.isAdrType(s.getStockType());
            AdrShareRatio adrRatio = adr ? adrBySymbol.get(sym) : null;
            boolean verifiedAdr = adr && adrRatio != null && adrRatio.isVerified()
                    && adrRatio.getAdrRatio() != null && adrRatio.getAdrRatio().signum() > 0;
            boolean financialInst = isFinancialInstitutionBySic(s.getSic());
            List<QuoteBarRow> stockQuotes = quotesByFigi.getOrDefault(s.getFigi(), List.of());
            Map<LocalDate, Integer> idxMap = new HashMap<>(stockQuotes.size() * 2);
            for (int i = 0; i < stockQuotes.size(); i++) {
                idxMap.put(stockQuotes.get(i).quoteDate(), i);
            }
            bundles.add(new StockBundle(
                    s, groupTag, etf, adr, verifiedAdr, financialInst,
                    statusMap.get(s.getFigi()),
                    stockQuotes,
                    idxMap,
                    splitsByFigi.getOrDefault(s.getFigi(), List.of()),
                    periodsByFigi.getOrDefault(s.getFigi(), List.of()),
                    historyByFigi.getOrDefault(s.getFigi(), List.of()),
                    currentIndustryByFigi.getOrDefault(s.getFigi(), etf ? "ETF" : "UNCLASSIFIED"),
                    List.of()
            ));
        }
        return bundles;
    }

    /** 預先按各月末交易日當時 PIT 可見財季計算月末估值倍數（嚴格 <= mDay，零未來函數，避免逐日重算 5 年月末）。 */
    private List<StockBundle> attachMonthEndPitMultiples(List<StockBundle> rawBundles, FxRateLookup fxLookup) {
        List<StockBundle> enriched = new ArrayList<>(rawBundles.size());
        for (StockBundle b : rawBundles) {
            if (b.etf() || (b.adr() && !b.verifiedAdr()) || b.periods().isEmpty() || b.quotes().isEmpty()) {
                enriched.add(b);
                continue;
            }
            Map<YearMonth, QuoteBarRow> monthEndBars = new LinkedHashMap<>();
            for (QuoteBarRow bar : b.quotes()) {
                monthEndBars.put(YearMonth.from(bar.quoteDate()), bar);
            }
            List<MonthEndPitMultiples> monthMultiples = new ArrayList<>(monthEndBars.size());
            for (QuoteBarRow mBar : monthEndBars.values()) {
                LocalDate mDay = mBar.quoteDate();
                List<StockFinancialPeriod> mPit = filterPitVisiblePeriods(b.periods(), mDay);
                if (mPit.isEmpty()) continue;
                StockFinancialPeriod mp = mPit.getLast();
                FxResolution mFx = resolveFx(fxLookup, mp, b.stock().getCurrency(), mDay);
                BigDecimal mFxRate = mFx != null && mFx.isUsable() ? mFx.usdPerUnit() : null;
                BigDecimal mShares = resolvePitSharesAtDay(mp, b.splits(), mDay);
                BigDecimal mMcap = mShares != null && mShares.signum() > 0 ? mBar.closePrice().multiply(mShares, MC) : null;
                DayEpsResolution mEps = StockValuationService.resolveDayEps(
                        b.stock().getCurrency(), b.status(), mp, b.splits(), mDay, mFxRate,
                        b.adr(), b.verifiedAdr(), mMcap, mBar.closePrice());
                Map<String, BigDecimal> mult = computeMultiples(
                        mp, mBar.closePrice(), mMcap, mEps.epsAtDay(), mFxRate, b.stock().getCurrency(), b.financialInstitution());
                monthMultiples.add(new MonthEndPitMultiples(mDay, mult));
            }
            enriched.add(new StockBundle(
                    b.stock(), b.groupTag(), b.etf(), b.adr(), b.verifiedAdr(), b.financialInstitution(),
                    b.status(), b.quotes(), b.quoteIndexByDate(), b.splits(), b.periods(),
                    b.industryHistory(), b.currentIndustryName(), monthMultiples));
        }
        return enriched;
    }

    // --- 嚴格 Point-in-Time (PIT) 估值與基本面重算（零未來函數） ---

    private PitValuationSnapshot evaluatePitValuation(StockBundle bundle, LocalDate day, BigDecimal closePrice, FxRateLookup fxLookup) {
        if (bundle.adr() && !bundle.verifiedAdr()) {
            return new PitValuationSnapshot(null, null, "INSUFFICIENT_EVIDENCE", null, null, null, null,
                    "INSUFFICIENT_DATA", null, "ADR_RATIO_UNVERIFIED");
        }
        List<StockFinancialPeriod> pitPeriods = filterPitVisiblePeriods(bundle.periods(), day);
        if (pitPeriods.isEmpty() || closePrice == null || closePrice.signum() <= 0) {
            return new PitValuationSnapshot(null, null, "INSUFFICIENT_EVIDENCE", null, null, null, null,
                    "INSUFFICIENT_DATA", null, "PIT_NO_PERIOD");
        }
        StockFinancialPeriod matched = pitPeriods.getLast();
        String pitBasis = resolvePitBasis(matched);
        String quoteCurrency = bundle.stock().getCurrency();
        FxResolution fxRes = resolveFx(fxLookup, matched, quoteCurrency, day);
        BigDecimal fxRate = fxRes != null && fxRes.isUsable() ? fxRes.usdPerUnit() : null;
        String fxFailure = fxRes != null && !fxRes.isUsable() && fxRes.reason() != null ? fxRes.reason().name() : null;
        String splitError = StockValuationService.splitSyncError(bundle.status());

        BigDecimal sharesAtDay = resolvePitSharesAtDay(matched, bundle.splits(), day);
        BigDecimal marketCapAtDay = sharesAtDay != null && sharesAtDay.signum() > 0 ? closePrice.multiply(sharesAtDay, MC) : null;
        DayEpsResolution epsRes = StockValuationService.resolveDayEps(
                quoteCurrency, bundle.status(), matched, bundle.splits(), day, fxRate,
                bundle.adr(), bundle.verifiedAdr(), marketCapAtDay, closePrice);
        BigDecimal epsAtDay = epsRes.epsAtDay();

        Map<String, BigDecimal> dayMultiples = computeMultiples(matched, closePrice, marketCapAtDay, epsAtDay,
                fxRate, quoteCurrency, bundle.financialInstitution());
        String primaryMetric = choosePrimaryMetric(dayMultiples, bundle.financialInstitution());
        BigDecimal currentMultiple = primaryMetric == null ? null : dayMultiples.get(primaryMetric);

        // 嚴格以 <= day 之近 5 年月末日與各月末日當時 PIT 可見財季構建歷史估值帶 [L, M, H]
        ValuationReferenceDto historicalRef = buildPitHistoricalReference(
                bundle, day, matched, primaryMetric, currentMultiple, closePrice, sharesAtDay, fxRate, quoteCurrency);
        ObjectiveBandVerdict bandVerdict = ValuationSummaryCalculator.computeObjectiveBandAndVerdict(closePrice, historicalRef, null);
        String verdict = bandVerdict != null ? bandVerdict.verdict() : "INSUFFICIENT_EVIDENCE";

        BigDecimal peAtDay = dayMultiples.get("PE");
        FundamentalSnapshotDto fundamentals = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                pitPeriods, matched, bundle.splits(), day, quoteCurrency,
                closePrice, marketCapAtDay, marketCapAtDay == null ? "VALUATION_DATE_SHARES_MISSING" : null,
                epsAtDay, epsRes.peReason(), peAtDay,
                bundle.financialInstitution(), splitError, fxRate, fxFailure, bundle.verifiedAdr()));
        boolean currentLoss = StockValuationService.isCurrentTtmLoss(
                matched, bundle.status(), day, bundle.adr(), bundle.verifiedAdr());
        String backing = ValuationSummaryCalculator.evaluateFundamentalBacking(fundamentals, currentLoss);

        return new PitValuationSnapshot(
                primaryMetric,
                currentMultiple,
                verdict,
                bandVerdict == null ? null : bandVerdict.low(),
                bandVerdict == null ? null : bandVerdict.mid(),
                bandVerdict == null ? null : bandVerdict.high(),
                historicalRef == null ? null : historicalRef.currentPercentile(),
                backing,
                fundamentals,
                pitBasis);
    }

    List<StockFinancialPeriod> filterPitVisiblePeriods(List<StockFinancialPeriod> allPeriods, LocalDate day) {
        if (allPeriods == null || allPeriods.isEmpty() || day == null) {
            return List.of();
        }
        List<StockFinancialPeriod> visible = new ArrayList<>();
        for (StockFinancialPeriod p : allPeriods) {
            if (p == null || p.getPeriodEnd() == null) {
                continue;
            }
            LocalDate availDate = resolvePitAvailableDate(p);
            if (availDate != null && !availDate.isAfter(day)) {
                visible.add(p);
            }
        }
        return visible;
    }

    private LocalDate resolvePitAvailableDate(StockFinancialPeriod period) {
        // 與正式 ForwardValuationCalculator.isPeriodReportedAtCutoff 一致：優先採 secPeriodicAvailableFromDate（首份定期 10-Q/10-K 公開日），
        // 避免數年後 10-K 比較期間重報把 secAvailableFromDate 推後 1~3 年而造成嚴重過期 EPS 失真。
        if (period.getSecPeriodicAvailableFromDate() != null) {
            return period.getSecPeriodicAvailableFromDate();
        }
        if (period.getSecAvailableFromDate() != null) {
            return period.getSecAvailableFromDate();
        }
        return period.getPeriodEnd() == null ? null : period.getPeriodEnd().plusDays(CONSERVATIVE_FILING_LAG_DAYS);
    }

    private String resolvePitBasis(StockFinancialPeriod period) {
        if (period == null) return "PIT_NO_PERIOD";
        if (period.getSecAvailableFromDate() != null
                || period.getSecPeriodicAvailableFromDate() != null
                || period.getEpsTtmSecAvailableFromDate() != null) {
            return "PIT_SEC_FILED";
        }
        return "PIT_CONSERVATIVE_45D";
    }

    private BigDecimal resolvePitSharesAtDay(StockFinancialPeriod period, List<StockSplit> splits, LocalDate day) {
        if (period == null || period.getSharesOutstanding() == null || period.getSharesOutstanding().signum() <= 0
                || !MetricStatus.AVAILABLE.name().equals(period.getSharesOutstandingStatus())) {
            return null;
        }
        LocalDate basisDate = period.getSharesBasisDate() != null ? period.getSharesBasisDate() : period.getPeriodEnd();
        LocalDate shareSecAvail = period.getSharesSecAvailableFromDate();
        // 嚴格禁止把未來才觀測或申報的股數（含 OBSERVED_SHARES）反推至歷史日期
        if (basisDate == null || basisDate.isAfter(day) || (shareSecAvail != null && shareSecAvail.isAfter(day))) {
            return null;
        }
        BigDecimal factor = StockSplitCalculator.shareFactor(splits, basisDate, day);
        if (factor == null || factor.signum() <= 0) {
            return null;
        }
        return period.getSharesOutstanding().multiply(factor, MC);
    }

    private Map<String, BigDecimal> computeMultiples(
            StockFinancialPeriod p,
            BigDecimal closePrice,
            BigDecimal marketCap,
            BigDecimal epsAtDay,
            BigDecimal fxRate,
            String quoteCurrency,
            boolean financialInstitution) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (closePrice == null || closePrice.signum() <= 0) {
            return out;
        }
        if (epsAtDay != null && epsAtDay.signum() > 0) {
            out.put("PE", closePrice.divide(epsAtDay, 4, RoundingMode.HALF_UP));
        }
        boolean sameCcy = quoteCurrency != null && p.getReportingCurrency() != null
                && quoteCurrency.equalsIgnoreCase(p.getReportingCurrency());
        BigDecimal fxMult = sameCcy ? BigDecimal.ONE : fxRate;
        if (marketCap != null && marketCap.signum() > 0 && fxMult != null && fxMult.signum() > 0) {
            if (p.getCommonEquity() != null && p.getCommonEquity().signum() > 0
                    && MetricStatus.AVAILABLE.name().equals(p.getCommonEquityStatus())) {
                BigDecimal eqUsd = p.getCommonEquity().multiply(fxMult, MC);
                out.put("PB", marketCap.divide(eqUsd, 4, RoundingMode.HALF_UP));
            }
            if (!financialInstitution) {
                if (p.getRevenueTtm() != null && p.getRevenueTtm().signum() > 0
                        && MetricStatus.AVAILABLE.name().equals(p.getRevenueTtmStatus())) {
                    BigDecimal revUsd = p.getRevenueTtm().multiply(fxMult, MC);
                    out.put("PS", marketCap.divide(revUsd, 4, RoundingMode.HALF_UP));
                }
                if (p.getFreeCashFlowTtm() != null && p.getFreeCashFlowTtm().signum() > 0
                        && MetricStatus.AVAILABLE.name().equals(p.getFreeCashFlowTtmStatus())) {
                    BigDecimal fcfUsd = p.getFreeCashFlowTtm().multiply(fxMult, MC);
                    out.put("PFCF", marketCap.divide(fcfUsd, 4, RoundingMode.HALF_UP));
                }
                if (p.getEbitdaTtm() != null && p.getEbitdaTtm().signum() > 0
                        && MetricStatus.AVAILABLE.name().equals(p.getEbitdaTtmStatus())
                        && p.getEvAdjustment() != null
                        && MetricStatus.AVAILABLE.name().equals(p.getEvAdjustmentStatus())) {
                    BigDecimal evAdj = p.getEvAdjustment().multiply(fxMult, MC);
                    BigDecimal ev = marketCap.add(evAdj, MC);
                    if (ev.signum() > 0) {
                        BigDecimal ebitdaUsd = p.getEbitdaTtm().multiply(fxMult, MC);
                        out.put("EV_EBITDA", ev.divide(ebitdaUsd, 4, RoundingMode.HALF_UP));
                    }
                }
            }
        }
        return out;
    }

    private String choosePrimaryMetric(Map<String, BigDecimal> multiples, boolean financialInstitution) {
        List<String> order = financialInstitution
                ? List.of("PB", "PE")
                : List.of("PE", "EV_EBITDA", "PFCF", "PS", "PB");
        for (String key : order) {
            BigDecimal val = multiples.get(key);
            if (val != null && val.signum() > 0) {
                return key;
            }
        }
        return null;
    }

    private ValuationReferenceDto buildPitHistoricalReference(
            StockBundle bundle,
            LocalDate day,
            StockFinancialPeriod matched,
            String metricCode,
            BigDecimal currentMultiple,
            BigDecimal currentPrice,
            BigDecimal sharesAtDay,
            BigDecimal fxRate,
            String quoteCurrency) {
        if (metricCode == null || currentMultiple == null || currentMultiple.signum() <= 0
                || currentPrice == null || currentPrice.signum() <= 0) {
            return null;
        }
        LocalDate from5y = day.minusYears(5);
        YearMonth currentYm = YearMonth.from(day);
        List<BigDecimal> samples = new ArrayList<>();
        int windowMonths = 0;
        LocalDate firstSampleDate = null;
        LocalDate lastSampleDate = null;
        for (MonthEndPitMultiples m : bundle.monthEndPitMultiples()) {
            LocalDate mDay = m.date();
            if (mDay.isBefore(from5y)) continue;
            // 僅採用嚴格早於當月且 <= day 的已完成月末（當月採當日 currentMultiple，避免月中使用月底未來收盤價）
            if (!YearMonth.from(mDay).isBefore(currentYm) || mDay.isAfter(day)) break;
            windowMonths++;
            BigDecimal sample = m.multiples().get(metricCode);
            if (sample != null && sample.signum() > 0) {
                samples.add(sample);
                if (firstSampleDate == null) firstSampleDate = mDay;
                lastSampleDate = mDay;
            }
        }
        windowMonths++;
        samples.add(currentMultiple);
        if (firstSampleDate == null) firstSampleDate = day;
        lastSampleDate = day;
        if (samples.size() < MIN_HISTORICAL_SAMPLES) {
            return null;
        }
        Collections.sort(samples);
        BigDecimal p25 = percentile(samples, new BigDecimal("0.25"));
        BigDecimal p50 = percentile(samples, new BigDecimal("0.50"));
        BigDecimal p75 = percentile(samples, new BigDecimal("0.75"));
        BigDecimal priceLow = impliedPriceForMultiple(metricCode, p25, currentMultiple, currentPrice, matched, sharesAtDay, fxRate, quoteCurrency);
        BigDecimal priceMed = impliedPriceForMultiple(metricCode, p50, currentMultiple, currentPrice, matched, sharesAtDay, fxRate, quoteCurrency);
        BigDecimal priceHigh = impliedPriceForMultiple(metricCode, p75, currentMultiple, currentPrice, matched, sharesAtDay, fxRate, quoteCurrency);
        if (priceLow == null || priceMed == null || priceHigh == null
                || priceLow.signum() <= 0 || priceMed.signum() <= 0 || priceHigh.signum() <= 0) {
            return null;
        }
        BigDecimal pctRank = midRankPercentile(samples, currentMultiple);
        return new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, metricCode, MetricStatus.AVAILABLE.name(), null,
                p25, p50, p75, priceLow, priceHigh,
                samples.size(), windowMonths, windowMonths - samples.size(),
                firstSampleDate, lastSampleDate, currentMultiple, pctRank, priceMed,
                null, null, null, null, null, null,
                null, null, null, null, null, null, List.of());
    }

    private BigDecimal impliedPriceForMultiple(
            String metricCode,
            BigDecimal targetMultiple,
            BigDecimal currentMultiple,
            BigDecimal currentPrice,
            StockFinancialPeriod matched,
            BigDecimal sharesAtDay,
            BigDecimal fxRate,
            String quoteCurrency) {
        if ("EV_EBITDA".equals(metricCode)) {
            if (matched == null || matched.getEbitdaTtm() == null || matched.getEvAdjustment() == null
                    || sharesAtDay == null || sharesAtDay.signum() <= 0) {
                return null;
            }
            boolean sameCcy = quoteCurrency != null && matched.getReportingCurrency() != null
                    && quoteCurrency.equalsIgnoreCase(matched.getReportingCurrency());
            BigDecimal fxMult = sameCcy ? BigDecimal.ONE : fxRate;
            if (fxMult == null || fxMult.signum() <= 0) {
                return null;
            }
            BigDecimal impliedEv = targetMultiple.multiply(matched.getEbitdaTtm().multiply(fxMult, MC), MC);
            BigDecimal impliedMcap = impliedEv.subtract(matched.getEvAdjustment().multiply(fxMult, MC), MC);
            return impliedMcap.signum() > 0 ? impliedMcap.divide(sharesAtDay, 4, RoundingMode.HALF_UP) : null;
        }
        return currentPrice.multiply(targetMultiple, MC).divide(currentMultiple, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal percentile(List<BigDecimal> sortedAsc, BigDecimal quantile) {
        if (sortedAsc.size() == 1) return sortedAsc.getFirst();
        BigDecimal pos = quantile.multiply(BigDecimal.valueOf(sortedAsc.size() - 1), MC);
        int lo = pos.intValue();
        int hi = Math.min(sortedAsc.size() - 1, lo + 1);
        BigDecimal frac = pos.subtract(BigDecimal.valueOf(lo), MC);
        BigDecimal diff = sortedAsc.get(hi).subtract(sortedAsc.get(lo), MC);
        return sortedAsc.get(lo).add(diff.multiply(frac, MC), MC).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal midRankPercentile(List<BigDecimal> sortedAsc, BigDecimal current) {
        int below = 0;
        int equal = 0;
        for (BigDecimal s : sortedAsc) {
            int cmp = s.compareTo(current);
            if (cmp < 0) below++;
            else if (cmp == 0) equal++;
        }
        BigDecimal rank = BigDecimal.valueOf(below).add(BigDecimal.valueOf(equal).multiply(new BigDecimal("0.5"), MC), MC);
        return rank.multiply(HUNDRED, MC).divide(BigDecimal.valueOf(sortedAsc.size()), 2, RoundingMode.HALF_UP);
    }

    // --- 雷達軌道與計畫提取（完全對齊 DailyOpportunityRadarService 規則） ---

    private String resolveRadarTrack(RadarEvaluationResult eval, BigDecimal evalPrice, String verdict, String backing) {
        if (eval == null) return "NONE";
        boolean headwindRegime = eval.marketRegime() == MarketRegime.BEAR || eval.marketRegime() == MarketRegime.PANIC;
        LevelZoneDto supportZone = eval.supportZone();
        boolean nearOrInSupport = supportZone != null && supportZone.lower() != null && supportZone.upper() != null
                && supportZone.upper().signum() > 0 && evalPrice != null
                && evalPrice.compareTo(supportZone.lower()) >= 0
                && evalPrice.compareTo(supportZone.upper().multiply(MAX_SUPPORT_UPPER_FACTOR, MC)) <= 0;
        if (headwindRegime) {
            boolean watchEligible = "DEEP_UNDERVALUED".equals(verdict) && "STRONG".equals(backing)
                    && !eval.forwardValueTrap() && !eval.highRiskBreakdown() && !eval.stopBreachedOrReclaim()
                    && nearOrInSupport && "WAIT_FOR_MARKET_STABILIZATION".equals(eval.stance());
            return watchEligible ? "HEADWIND_WATCH" : "NONE";
        }
        boolean valueVerdict = "DEEP_UNDERVALUED".equals(verdict) || "MODERATELY_UNDERVALUED".equals(verdict) || "FAIR_LOWER".equals(verdict);
        boolean valueBacking = "STRONG".equals(backing) || "MIXED".equals(backing);
        boolean confirmationOk = eval.marketRegime() == MarketRegime.CORRECTION || !eval.confirmationPending();
        boolean stanceAllows = "ACCUMULATE_AT_SUPPORT".equals(eval.stance())
                || ("WATCH_PULLBACK_TO_BAND".equals(eval.stance()) && confirmationOk);
        if (valueVerdict && valueBacking && nearOrInSupport
                && !eval.forwardValueTrap() && !eval.highRiskBreakdown() && !eval.stopBreachedOrReclaim() && stanceAllows) {
            return DailyOpportunityRadar.TRACK_VALUE_SUPPORT;
        }
        if ("TREND_PULLBACK_STARTER".equals(eval.stance()) && eval.trendPullbackStarter() != null && eval.trendPullbackStarter().eligible()) {
            return DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER;
        }
        return "NONE";
    }

    private TradePlanDto findMatchingPlan(List<TradePlanDto> plans, LevelZoneDto supportZone) {
        if (plans == null || plans.isEmpty()) return null;
        if (supportZone != null && supportZone.id() != null) {
            for (TradePlanDto p : plans) {
                if (p != null && supportZone.id().equals(p.sourceZoneId())) {
                    return p;
                }
            }
        }
        for (TradePlanDto p : plans) {
            if (p != null && p.status() != null && "ACTIVE".equals(p.status().name())) {
                return p;
            }
        }
        return plans.getFirst();
    }

    private BigDecimal resolveProtectiveStop(TradePlanDto plan, LevelZoneDto supportZone) {
        if (plan != null && plan.stop() != null && plan.stop().protectivePrice() != null && plan.stop().protectivePrice().signum() > 0) {
            return plan.stop().protectivePrice();
        }
        if (supportZone != null && supportZone.lower() != null && supportZone.lower().signum() > 0) {
            return supportZone.lower();
        }
        return null;
    }

    private BigDecimal resolveFirstTargetAbovePrice(TradePlanDto plan, BigDecimal price) {
        if (plan == null || plan.targets() == null || price == null) return null;
        for (TargetDto t : plan.targets()) {
            if (t != null && t.price() != null && t.price().compareTo(price) > 0) {
                return t.price();
            }
        }
        return null;
    }

    // --- 事後真實次日開盤進場與跨拆股結算（純驗證用，絕不回傳給任何歷史決策） ---

    ForwardSettlement settleForward(
            StockBundle bundle,
            StockBundle spyBundle,
            LocalDate signalDate,
            BigDecimal protectiveStopOnSignalDate,
            BigDecimal firstTargetOnSignalDate) {
        Integer sigIdx = bundle.quoteIndexByDate().get(signalDate);
        if (sigIdx == null || sigIdx + 1 >= bundle.quotes().size()) {
            return emptySettlement();
        }
        QuoteBarRow entryBar = bundle.quotes().get(sigIdx + 1);
        LocalDate entryDate = entryBar.quoteDate();
        BigDecimal entryOpen = entryBar.openPrice();
        if (entryOpen == null || entryOpen.signum() <= 0) {
            return emptySettlement();
        }
        // 將訊號日基準的止損／目標價按拆股因子對齊至進場日基準（跨拆股日完全精準）
        BigDecimal sigToEntryFactor = StockSplitCalculator.priceFactor(bundle.splits(), signalDate, entryDate);
        BigDecimal stopAtEntry = scaleNullable(protectiveStopOnSignalDate, sigToEntryFactor);
        BigDecimal targetAtEntry = scaleNullable(firstTargetOnSignalDate, sigToEntryFactor);

        BigDecimal ret5 = forwardReturnFromEntryOpen(bundle, sigIdx + 1, entryDate, entryOpen, 5);
        BigDecimal ret20 = forwardReturnFromEntryOpen(bundle, sigIdx + 1, entryDate, entryOpen, 20);
        BigDecimal ret60 = forwardReturnFromEntryOpen(bundle, sigIdx + 1, entryDate, entryOpen, 60);

        BigDecimal spyRet20 = null;
        if (spyBundle != null) {
            Integer spyEntryIdx = spyBundle.quoteIndexByDate().get(entryDate);
            if (spyEntryIdx != null) {
                QuoteBarRow spyEntryBar = spyBundle.quotes().get(spyEntryIdx);
                BigDecimal spyEntryOpen = spyEntryBar.openPrice() != null && spyEntryBar.openPrice().signum() > 0
                        ? spyEntryBar.openPrice() : spyEntryBar.closePrice();
                if (spyEntryOpen != null && spyEntryOpen.signum() > 0) {
                    spyRet20 = forwardReturnFromEntryOpen(spyBundle, spyEntryIdx, entryDate, spyEntryOpen, 20);
                }
            }
        }
        BigDecimal excess20 = (ret20 != null && spyRet20 != null)
                ? ret20.subtract(spyRet20).setScale(2, RoundingMode.HALF_UP) : null;

        int maxForwardIdx = Math.min(bundle.quotes().size() - 1, sigIdx + 60);
        int evaluatedSessions = maxForwardIdx - sigIdx;
        BigDecimal maxFavorable = BigDecimal.ZERO;
        BigDecimal maxAdverse = BigDecimal.ZERO;
        String outcome = stopAtEntry == null && targetAtEntry == null ? "NO_PLAN" : SignalTrackingLog.OUTCOME_PENDING;
        LocalDate outcomeDate = null;

        for (int idx = sigIdx + 1; idx <= maxForwardIdx; idx++) {
            QuoteBarRow bar = bundle.quotes().get(idx);
            BigDecimal factorToEntry = StockSplitCalculator.priceFactor(bundle.splits(), bar.quoteDate(), entryDate);
            BigDecimal rawOpen = bar.openPrice() != null ? bar.openPrice() : bar.closePrice();
            BigDecimal rawHigh = bar.highPrice() != null ? bar.highPrice() : bar.closePrice();
            BigDecimal rawLow = bar.lowPrice() != null ? bar.lowPrice() : bar.closePrice();
            if (rawOpen == null || rawHigh == null || rawLow == null) {
                continue;
            }
            BigDecimal adjOpen = rawOpen.multiply(factorToEntry, MC);
            BigDecimal adjHigh = rawHigh.multiply(factorToEntry, MC);
            BigDecimal adjLow = rawLow.multiply(factorToEntry, MC);

            BigDecimal favPct = adjHigh.subtract(entryOpen, MC).divide(entryOpen, MC).multiply(HUNDRED, MC);
            BigDecimal advPct = adjLow.subtract(entryOpen, MC).divide(entryOpen, MC).multiply(HUNDRED, MC);
            if (favPct.compareTo(maxFavorable) > 0) maxFavorable = favPct;
            if (advPct.compareTo(maxAdverse) < 0) maxAdverse = advPct;

            if (SignalTrackingLog.OUTCOME_PENDING.equals(outcome)) {
                boolean stopHit = stopAtEntry != null && adjLow.compareTo(stopAtEntry) <= 0;
                boolean targetHit = targetAtEntry != null && adjHigh.compareTo(targetAtEntry) >= 0;
                if (stopHit && targetHit) {
                    if (adjOpen.compareTo(stopAtEntry) <= 0) {
                        outcome = SignalTrackingLog.OUTCOME_STOP_HIT_FIRST;
                    } else if (adjOpen.compareTo(targetAtEntry) >= 0) {
                        outcome = SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST;
                    } else {
                        outcome = SignalTrackingLog.OUTCOME_AMBIGUOUS_SAME_BAR;
                    }
                    outcomeDate = bar.quoteDate();
                } else if (stopHit) {
                    outcome = SignalTrackingLog.OUTCOME_STOP_HIT_FIRST;
                    outcomeDate = bar.quoteDate();
                } else if (targetHit) {
                    outcome = SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST;
                    outcomeDate = bar.quoteDate();
                }
            }
        }
        if (SignalTrackingLog.OUTCOME_PENDING.equals(outcome)) {
            if (evaluatedSessions >= 60) {
                outcome = targetAtEntry == null ? SignalTrackingLog.OUTCOME_STOP_ONLY_NO_TARGET : SignalTrackingLog.OUTCOME_EXPIRED_NEITHER;
                outcomeDate = bundle.quotes().get(maxForwardIdx).quoteDate();
            } else {
                outcome = "PENDING_HORIZON";
            }
        }
        BigDecimal mfeOut = evaluatedSessions >= 60 ? maxFavorable.setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal maeOut = evaluatedSessions >= 60 ? maxAdverse.setScale(2, RoundingMode.HALF_UP) : null;
        return new ForwardSettlement(
                entryDate,
                entryOpen.setScale(4, RoundingMode.HALF_UP),
                ret5, ret20, ret60, spyRet20, excess20,
                outcome, outcomeDate,
                mfeOut,
                maeOut);
    }

    private BigDecimal forwardReturnFromEntryOpen(StockBundle bundle, int entryIdx, LocalDate entryDate, BigDecimal entryOpen, int tradingDaysHorizon) {
        int targetIdx = entryIdx + tradingDaysHorizon - 1;
        if (targetIdx >= bundle.quotes().size()) {
            return null;
        }
        QuoteBarRow targetBar = bundle.quotes().get(targetIdx);
        BigDecimal factorToEntry = StockSplitCalculator.priceFactor(bundle.splits(), targetBar.quoteDate(), entryDate);
        BigDecimal adjClose = targetBar.closePrice().multiply(factorToEntry, MC);
        return adjClose.subtract(entryOpen, MC).divide(entryOpen, MC).multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
    }

    // --- 兩份獨立未壓縮 .csv 檔案產出 ---

    private void writeEventsCsv(Path path, List<EventRow> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(String.join(",",
                    "signal_date", "market_phase", "event_kind", "symbol", "stock_group",
                    "industry_name", "classification_basis", "market_regime", "market_gate",
                    "vix_close", "treasury_10y", "credit_spread_status",
                    "signal_close", "weekly_trend", "daily_minor_trend", "high_risk_breakdown",
                    "valuation_metric", "valuation_multiple", "valuation_verdict", "fair_value_low", "fair_value_mid",
                    "fundamental_backing", "pit_basis", "stance", "radar_track", "plan_status",
                    "protective_stop", "first_target", "cooldown_distinct",
                    "entry_date", "entry_open", "ret_5d_pct", "ret_20d_pct", "ret_60d_pct",
                    "spy_ret_20d_pct", "excess_ret_20d_pct", "outcome_status", "outcome_date",
                    "mfe_60d_pct", "mae_60d_pct"));
            writer.newLine();
            for (EventRow r : rows) {
                ForwardSettlement s = r.settlement();
                writer.write(csvRow(
                        r.signalDate(), r.phase(), r.eventKind(), r.symbol(), r.stockGroup(),
                        r.industryName(), r.classificationBasis(), r.marketRegime(), r.marketGate(),
                        r.vixClose(), r.treasury10y(), r.creditSpreadStatus(),
                        r.signalClose(), r.weeklyTrend(), r.dailyMinorTrend(), r.highRiskBreakdown(),
                        r.valuationPrimaryMetric(), r.valuationMultiple(), r.valuationVerdict(), r.fairValueLow(), r.fairValueMid(),
                        r.fundamentalBacking(), r.pitBasis(), r.stance(), r.radarTrack(), r.planStatus(),
                        r.protectiveStop(), r.firstTarget(), r.cooldownDistinct(),
                        s.entryDate(), s.entryOpen(), s.ret5dPct(), s.ret20dPct(), s.ret60dPct(),
                        s.spyRet20dPct(), s.excessRet20dPct(), s.outcomeStatus(), s.outcomeDate(),
                        s.mfe60dPct(), s.mae60dPct()));
                writer.newLine();
            }
        }
    }

    private int writeSummaryCsv(
            Path path,
            LocalDate startDate,
            LocalDate endDate,
            int symbolCount,
            List<DailyRegimeRecord> dailyRegimes,
            List<EventRow> events,
            StockBundle spyBundle,
            StockBundle qqqBundle) throws IOException {
        int writtenRows = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(String.join(",",
                    "section", "group_key", "count", "allow_or_win20_pct",
                    "avg_ret_5d_pct", "avg_ret_20d_pct", "avg_excess_20d_pct", "avg_ret_60d_pct",
                    "target_hit_first", "stop_hit_first", "target_hit_rate_pct",
                    "avg_mfe_60d_pct", "avg_mae_60d_pct", "notes"));
            writer.newLine();

            // 1. Metadata & Global Regime Stability
            int regimeSwitches = 0;
            int allowDays = 0;
            int allowWithNext = 0;
            int allowSpyCrash = 0;
            int restrictedWithNext = 0;
            int restrictedSpyCrash = 0;
            MarketRegime prev = null;
            for (DailyRegimeRecord d : dailyRegimes) {
                if (prev != null && d.regime() != null && d.regime() != prev) regimeSwitches++;
                if (d.regime() != null) prev = d.regime();
                if (d.permission() == EntryPermission.ALLOW) {
                    allowDays++;
                    if (d.spyNextDayRetPct() != null) {
                        allowWithNext++;
                        if (d.spyNextDayRetPct().compareTo(new BigDecimal("-2.00")) <= 0) allowSpyCrash++;
                    }
                } else if (d.permission() != null) {
                    if (d.spyNextDayRetPct() != null) {
                        restrictedWithNext++;
                        if (d.spyNextDayRetPct().compareTo(new BigDecimal("-2.00")) <= 0) restrictedSpyCrash++;
                    }
                }
            }
            String metaNotes = "window=" + startDate + "~" + endDate + ";symbols=" + symbolCount
                    + ";regimeSwitches=" + regimeSwitches
                    + ";spyCrashLeMinus2Pct_ALLOW=" + pct(allowSpyCrash, allowWithNext) + "%"
                    + ";RESTRICTED=" + pct(restrictedSpyCrash, restrictedWithNext) + "%"
                    + ";note=TREND_PULLBACK_STARTER_requires_live_forecast_history";
            writer.write(csvRow("META", "FULL_REPLAY", dailyRegimes.size(), pct(allowDays, dailyRegimes.size()),
                    null, null, null, null, null, null, null, null, null, metaNotes));
            writer.newLine();
            writtenRows++;

            // 2. 7 Historical Market Phases
            Map<String, List<DailyRegimeRecord>> byPhase = new LinkedHashMap<>();
            for (DailyRegimeRecord d : dailyRegimes) {
                byPhase.computeIfAbsent(d.phase(), k -> new ArrayList<>()).add(d);
            }
            for (Map.Entry<String, List<DailyRegimeRecord>> entry : byPhase.entrySet()) {
                List<DailyRegimeRecord> list = entry.getValue();
                int pSwitches = 0;
                int pAllow = 0;
                int pBearPanic = 0;
                MarketRegime pPrev = null;
                for (DailyRegimeRecord d : list) {
                    if (pPrev != null && d.regime() != null && d.regime() != pPrev) pSwitches++;
                    if (d.regime() != null) pPrev = d.regime();
                    if (d.permission() == EntryPermission.ALLOW) pAllow++;
                    if (d.regime() == MarketRegime.BEAR || d.regime() == MarketRegime.PANIC) pBearPanic++;
                }
                String pNotes = "switches=" + pSwitches + ";bearOrPanicDays=" + pBearPanic
                        + ";range=" + list.getFirst().date() + "~" + list.getLast().date();
                writer.write(csvRow("PHASE_REGIME", entry.getKey(), list.size(), pct(pAllow, list.size()),
                        null, null, null, null, null, null, null, null, null, pNotes));
                writer.newLine();
                writtenRows++;
            }

            // 3. Key Turning Points Lag Table
            for (TurningPointSpec tp : HISTORICAL_TURNING_POINTS) {
                if (tp.actualDate().isBefore(startDate) || tp.actualDate().isAfter(endDate)) continue;
                DailyRegimeRecord signalDay = findTurningPointSignal(dailyRegimes, tp);
                if (signalDay == null) {
                    writer.write(csvRow("TURNING_POINT", tp.id(), 0, null, null, null, null, null,
                            null, null, null, null, null, "actual=" + tp.actualDate() + ";signal=NOT_TRIGGERED"));
                    writer.newLine();
                    writtenRows++;
                    continue;
                }
                int lagDays = Math.max(0, marketStatusService.getTradingDays(tp.actualDate(), signalDay.date()).size() - 1);
                ForwardSettlement spySettle = spyBundle == null ? emptySettlement()
                        : settleForward(spyBundle, spyBundle, signalDay.date(), null, null);
                String tpNotes = "kind=" + tp.kind() + ";actual=" + tp.actualDate() + ";signalDate=" + signalDay.date()
                        + ";lagTradingDays=" + lagDays + ";regime=" + regimeName(signalDay.regime());
                writer.write(csvRow("TURNING_POINT", tp.id(), lagDays, null,
                        spySettle.ret5dPct(), spySettle.ret20dPct(), null, spySettle.ret60dPct(),
                        null, null, null, spySettle.mfe60dPct(), spySettle.mae60dPct(), tpNotes));
                writer.newLine();
                writtenRows++;
            }

            // 4. Stock Signals Aggregated by Stance (cooldownDistinct=true to prevent repeated-day inflation)
            Map<String, GroupAccumulator> byStance = new LinkedHashMap<>();
            Map<String, GroupAccumulator> byGroupBuy = new LinkedHashMap<>();
            Map<String, GroupAccumulator> bySymbolBuy = new LinkedHashMap<>();
            for (EventRow r : events) {
                if ("MARKET_REGIME_CHANGE".equals(r.eventKind()) || "MARKET_REGIME_LATEST".equals(r.eventKind())) {
                    continue;
                }
                if (!r.cooldownDistinct()) {
                    continue;
                }
                String stKey = r.stance() == null ? "UNKNOWN" : r.stance();
                byStance.put(stKey, byStance.getOrDefault(stKey, GroupAccumulator.empty()).add(r.settlement()));
                if (isBullishBuyStance(stKey)) {
                    byGroupBuy.put(r.stockGroup(), byGroupBuy.getOrDefault(r.stockGroup(), GroupAccumulator.empty()).add(r.settlement()));
                    bySymbolBuy.put(r.symbol(), bySymbolBuy.getOrDefault(r.symbol(), GroupAccumulator.empty()).add(r.settlement()));
                }
            }
            writtenRows += writeAccumulatorSection(writer, "BY_STANCE_DISTINCT", byStance, "20d_cooldown_dedup");
            writtenRows += writeAccumulatorSection(writer, "BUY_SIGNALS_BY_GROUP", byGroupBuy, "bullish_entries_survivorship_check");
            writtenRows += writeAccumulatorSection(writer, "BUY_SIGNALS_BY_SYMBOL", bySymbolBuy, "per_symbol_bullish_entries");
        }
        return writtenRows;
    }

    private int writeAccumulatorSection(BufferedWriter writer, String section, Map<String, GroupAccumulator> map, String notes) throws IOException {
        int count = 0;
        for (Map.Entry<String, GroupAccumulator> entry : map.entrySet()) {
            GroupAccumulator a = entry.getValue();
            writer.write(csvRow(
                    section, entry.getKey(), a.totalCount(), pct(a.win20Count(), a.ret20Count()),
                    avg(a.ret5Sum(), a.ret5Count()), avg(a.ret20Sum(), a.ret20Count()),
                    avg(a.excess20Sum(), a.excess20Count()), avg(a.ret60Sum(), a.ret60Count()),
                    a.targetHitCount(), a.stopHitCount(), pct(a.targetHitCount(), a.resolvedOutcomeCount()),
                    avg(a.mfeSum(), a.mfeCount()), avg(a.maeSum(), a.maeCount()), notes));
            writer.newLine();
            count++;
        }
        return count;
    }

    private DailyRegimeRecord findTurningPointSignal(List<DailyRegimeRecord> dailyRegimes, TurningPointSpec tp) {
        for (DailyRegimeRecord d : dailyRegimes) {
            if (d.date().isBefore(tp.actualDate()) || d.date().isAfter(tp.searchEndDate())) {
                continue;
            }
            if ("TOP".equals(tp.kind()) && d.permission() != null && d.permission() != EntryPermission.ALLOW) {
                return d;
            }
            if ("BOTTOM".equals(tp.kind()) && d.permission() == EntryPermission.ALLOW) {
                return d;
            }
        }
        return null;
    }

    // --- 狀態追蹤與磁碟持久化 ---

    private void updateStatus(
            String status, String runId, Instant startedAt, Instant finishedAt,
            LocalDate startDate, LocalDate endDate,
            int processedDays, int totalDays, int totalSymbols, LocalDate currentDate,
            int processedSnapshots, int emittedEvents, String errorMessage,
            List<Map<String, Object>> files) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskCode", TaskCode.SIGNAL_REPLAY_AUDIT);
        snapshot.put("status", status);
        snapshot.put("runId", runId);
        snapshot.put("startedAt", startedAt == null ? null : startedAt.toString());
        snapshot.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        snapshot.put("startDate", startDate == null ? null : startDate.toString());
        snapshot.put("endDate", endDate == null ? null : endDate.toString());
        snapshot.put("processedDays", processedDays);
        snapshot.put("totalDays", totalDays);
        snapshot.put("totalSymbols", totalSymbols);
        snapshot.put("currentDate", currentDate == null ? null : currentDate.toString());
        snapshot.put("processedSnapshots", processedSnapshots);
        snapshot.put("emittedEvents", emittedEvents);
        int progressPct = totalDays <= 0 ? ("COMPLETED".equals(status) ? 100 : 0) : Math.min(100, (processedDays * 100) / totalDays);
        snapshot.put("progressPct", progressPct);
        snapshot.put("errorMessage", errorMessage);
        snapshot.put("files", files);
        STATUS_REF.set(Collections.unmodifiableMap(snapshot));
        if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
            persistStatus(snapshot);
        }
    }

    private void persistStatus(Map<String, Object> snapshot) {
        try {
            Path outDir = resolveOutputDir();
            Files.createDirectories(outDir);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("latest-status.json").toFile(), snapshot);
        } catch (IOException ex) {
            log.warn("Failed to persist signal replay status json | reason={}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPersistedStatus() {
        try {
            Path file = resolveOutputDir().resolve("latest-status.json");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return objectMapper.readValue(file.toFile(), LinkedHashMap.class);
        } catch (IOException ex) {
            return null;
        }
    }

    void setOutputDirPath(String outputDirPath) { this.outputDirPath = outputDirPath; }

    private Path resolveOutputDir() {
        String effective = (outputDirPath == null || outputDirPath.isBlank())
                ? Paths.get(System.getProperty("java.io.tmpdir"), "usmarkettools-signal-replay").toString()
                : outputDirPath.trim();
        return Paths.get(effective);
    }

    private Map<String, Object> fileDescriptor(String type, String fileName, long sizeBytes, int rowCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("fileName", fileName);
        map.put("sizeBytes", sizeBytes);
        map.put("rowCount", rowCount);
        return map;
    }

    // --- 基礎工具方法 ---

    private record IndustryResolution(String industryName, String classificationBasis) {}

    private IndustryResolution resolveIndustryAt(StockBundle bundle, Instant cutoff) {
        if (bundle.etf()) {
            return new IndustryResolution("ETF", "ETF_SECURITY_TYPE");
        }
        for (int i = bundle.industryHistory().size() - 1; i >= 0; i--) {
            StockIndustryHistory h = bundle.industryHistory().get(i);
            if (h.getObservedFrom() != null && !h.getObservedFrom().isAfter(cutoff)
                    && (h.getObservedTo() == null || h.getObservedTo().isAfter(cutoff))
                    && h.getIndustry() != null) {
                return new IndustryResolution(h.getIndustry().getIndustry(), "HISTORICAL_OBSERVED");
            }
        }
        return new IndustryResolution(bundle.currentIndustryName(), "CURRENT_SNAPSHOT");
    }

    private List<QuoteBarRow> sliceQuotesEndingAt(List<QuoteBarRow> all, LocalDate fromDate, int endIdx) {
        int startIdx = endIdx;
        while (startIdx > 0 && !all.get(startIdx - 1).quoteDate().isBefore(fromDate)) {
            startIdx--;
        }
        return all.subList(startIdx, endIdx + 1);
    }

    private boolean isFinancialInstitutionBySic(String sic) {
        if (sic == null || sic.isBlank()) return false;
        try {
            int code = Integer.parseInt(sic.trim());
            return code >= 6000 && code <= 6499;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private Set<String> collectReportingCurrencies(List<StockBundle> bundles) {
        Set<String> currencies = new LinkedHashSet<>();
        for (StockBundle b : bundles) {
            for (StockFinancialPeriod p : b.periods()) {
                if (p.getReportingCurrency() != null && !p.getReportingCurrency().isBlank()) {
                    currencies.add(p.getReportingCurrency().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return currencies;
    }

    private FxResolution resolveFx(FxRateLookup lookup, StockFinancialPeriod period, String quoteCurrency, LocalDate day) {
        if (period == null) return null;
        String rep = period.getReportingCurrency() == null ? quoteCurrency : period.getReportingCurrency();
        return lookup.resolve(rep, quoteCurrency, day);
    }

    private StockBundle findBundleBySymbol(List<StockBundle> bundles, String symbol) {
        for (StockBundle b : bundles) {
            if (symbol.equalsIgnoreCase(b.stock().getSymbol())) {
                return b;
            }
        }
        return null;
    }

    private BigDecimal closeOnDate(StockBundle bundle, LocalDate date) {
        if (bundle == null) return null;
        Integer idx = bundle.quoteIndexByDate().get(date);
        return idx == null ? null : bundle.quotes().get(idx).closePrice();
    }

    private BigDecimal nextTradingDayReturnPct(StockBundle bundle, LocalDate date) {
        if (bundle == null) return null;
        Integer idx = bundle.quoteIndexByDate().get(date);
        if (idx == null || idx + 1 >= bundle.quotes().size()) return null;
        QuoteBarRow cur = bundle.quotes().get(idx);
        QuoteBarRow next = bundle.quotes().get(idx + 1);
        BigDecimal factor = StockSplitCalculator.priceFactor(bundle.splits(), next.quoteDate(), cur.quoteDate());
        BigDecimal adjNextClose = next.closePrice().multiply(factor, MC);
        return adjNextClose.subtract(cur.closePrice(), MC).divide(cur.closePrice(), MC).multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP);
    }

    private MarketRegime extractPreloadedRegime(PreloadedMarketData preloaded) {
        if (preloaded == null || preloaded.marketSnapshot() == null || preloaded.marketSnapshot().value() == null) {
            return null;
        }
        return preloaded.marketSnapshot().value().regime();
    }

    private EntryPermission extractPreloadedPermission(PreloadedMarketData preloaded) {
        MarketRegime regime = extractPreloadedRegime(preloaded);
        if (regime == null) return null;
        if (regime == MarketRegime.BEAR || regime == MarketRegime.PANIC) return EntryPermission.BLOCK;
        if (regime == MarketRegime.CORRECTION) return EntryPermission.REQUIRE_CLOSE_CONFIRMATION;
        return EntryPermission.ALLOW;
    }

    private String extractTrend(PriceStructureDto structure, AnalysisTimeframe tf) {
        if (structure == null || structure.timeframes() == null) return null;
        for (TimeframeStructureDto item : structure.timeframes()) {
            if (item != null && item.timeframe() == tf
                    && item.minorTrend() != null && item.minorTrend().value() != null
                    && item.minorTrend().value().state() != null) {
                return item.minorTrend().value().state().name();
            }
        }
        return null;
    }

    private boolean isActionableBuyOrDefensiveSignal(String stance, String radarTrack, boolean highRiskBreakdown) {
        if (highRiskBreakdown) return true;
        if (radarTrack != null && !"NONE".equals(radarTrack)) return true;
        return isBullishBuyStance(stance)
                || "AVOID_OR_REDUCE".equals(stance)
                || "WAIT_FOR_MARKET_STABILIZATION".equals(stance)
                || "WATCH_PULLBACK_TO_BAND".equals(stance);
    }

    private boolean isBullishBuyStance(String stance) {
        return "ACCUMULATE_AT_SUPPORT".equals(stance)
                || "TREND_PULLBACK_STARTER".equals(stance)
                || "ETF_TECHNICAL_BULLISH_SUPPORT".equals(stance);
    }

    private boolean isOutsideCooldown(LocalDate previousDate, LocalDate currentDate) {
        List<LocalDate> sessions = marketStatusService.getTradingDays(previousDate, currentDate);
        return sessions.size() > SignalTrackingService.DEDUP_TRADING_DAYS;
    }

    private String classifyMarketPhase(LocalDate date) {
        if (date.isBefore(LocalDate.of(2020, 2, 19))) return "1_2019_SLOW_BULL";
        if (date.isBefore(LocalDate.of(2020, 5, 1))) return "2_2020_COVID_CRASH_V";
        if (date.isBefore(LocalDate.of(2022, 1, 1))) return "3_2020_2021_LIQUIDITY_BULL";
        if (date.isBefore(LocalDate.of(2022, 12, 1))) return "4_2022_RATE_HIKE_BEAR";
        if (date.isBefore(LocalDate.of(2025, 2, 1))) return "5_2023_2024_LONG_BULL";
        if (date.isBefore(LocalDate.of(2025, 7, 1))) return "6_2025_SPRING_SHOCK_REBOUND";
        if (date.isBefore(LocalDate.of(2026, 10, 1))) return "7A_2025H2_2026Q3_TREND";
        return "7B_2026_OCT_NOV_TOP_TURN";
    }

    private LocalDate resolveDateParam(JobDataMap params, String key, LocalDate fallback) {
        if (params == null || !params.containsKey(key)) return fallback;
        String raw = params.getString(key);
        if (raw == null || raw.isBlank()) return fallback;
        return LocalDate.parse(raw.trim());
    }

    private List<String> parseSymbolsParam(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> parsed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .toList();
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("SIGNAL_REPLAY_SYMBOLS_PARAM_EMPTY");
        }
        return parsed;
    }

    private BigDecimal scaleNullable(BigDecimal value, BigDecimal factor) {
        if (value == null || factor == null || factor.signum() <= 0) return value;
        return value.multiply(factor, MC);
    }

    private ForwardSettlement emptySettlement() {
        return new ForwardSettlement(null, null, null, null, null, null, null, "PENDING_HORIZON", null, null, null);
    }

    private String regimeName(MarketRegime regime) { return regime == null ? "UNAVAILABLE" : regime.name(); }

    private String permissionName(EntryPermission permission) { return permission == null ? "UNAVAILABLE" : permission.name(); }

    private BigDecimal avg(BigDecimal sum, int count) {
        if (count <= 0 || sum == null) return null;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(int numerator, int denominator) {
        if (denominator <= 0) return null;
        return BigDecimal.valueOf(numerator).multiply(HUNDRED, MC)
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private String csvRow(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            Object v = values[i];
            if (v == null) continue;
            String text = v instanceof BigDecimal bd ? bd.stripTrailingZeros().toPlainString() : String.valueOf(v);
            if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                sb.append('"').append(text.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static Map<String, String> createDefaultSymbolGroups() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("SPY", "BENCHMARK_ETF");
        map.put("QQQ", "BENCHMARK_ETF");
        map.put("NVDA", "LONG_BULL");
        map.put("AAPL", "LONG_BULL");
        map.put("MSFT", "LONG_BULL");
        map.put("GOOGL", "LONG_BULL");
        map.put("AMZN", "LONG_BULL");
        map.put("META", "LONG_BULL");
        map.put("AVGO", "LONG_BULL");
        map.put("TSLA", "HIGH_BETA_GROWTH");
        map.put("SHOP", "HIGH_BETA_GROWTH");
        map.put("INTC", "CRASHED_DETERIORATED");
        map.put("PYPL", "CRASHED_DETERIORATED");
        map.put("BA", "CRASHED_DETERIORATED");
        map.put("PFE", "CRASHED_DETERIORATED");
        map.put("NKE", "CRASHED_DETERIORATED");
        map.put("DIS", "CRASHED_DETERIORATED");
        map.put("CVS", "CRASHED_DETERIORATED");
        map.put("JPM", "FINANCIAL");
        map.put("BAC", "FINANCIAL");
        map.put("XOM", "CYCLICAL_DEFENSIVE");
        map.put("CAT", "CYCLICAL_DEFENSIVE");
        map.put("LLY", "CYCLICAL_DEFENSIVE");
        map.put("COST", "CYCLICAL_DEFENSIVE");
        map.put("TSM", "ADR_VERIFIED");
        map.put("BABA", "CRASHED_ADR");
        return Collections.unmodifiableMap(map);
    }
}
