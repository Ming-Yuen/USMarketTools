package com.ykm.stock.analytics.service.portfolio;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosis;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.TrendPullbackStarterDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.HoldingHealthItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioAlertDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.SectorExposureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.WatchlistOpportunityDto;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.DailyClosingBriefing;
import com.ykm.stock.analytics.model.entity.InAppNotification;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockOptionsSummary;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.DailyClosingBriefingRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.FigiKnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.KnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockOptionsSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator.SizingInputs;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.RadarEvaluationResult;
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.FxRateService.FxRateLookup;
import com.ykm.stock.analytics.service.market.FxRateService.FxResolution;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioSplitAligner.AlignedPosition;
import com.ykm.stock.analytics.service.portfolio.PortfolioSplitAligner.AlignedPrice;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator.SelectedRevisionTarget;
import com.ykm.stock.analytics.service.valuation.FundamentalSnapshotCalculator;
import com.ykm.stock.analytics.service.valuation.IndustryValuationProposalService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.DayEpsResolution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 持倉與重點自選股每日自動體檢核心服務。
 * 嚴格遵守：
 * 1. 以 portfolio_item 為母體批次讀取本地資料庫，絕不呼叫 AnnualEarningsForecastSyncService 或任何外部 API。
 * 2. 透過 REPEATABLE_READ 唯讀快照一次擷取持倉、風控設定、行情、拆股、財報、預測與事件，避免跨 Job 讀寫錯位。
 * 3. 事前參考止損與 T1/T2 均自上一份已發布簡報對齊拆股後比對，不回頭捏造當日剛產生的目標已觸及。
 */
@Service
public class PortfolioHealthCheckService {
    public static final String SOURCE_MODE_CLOSING_SNAPSHOT = "CLOSING_SNAPSHOT";
    public static final String SOURCE_MODE_LIVE = "LIVE";

    public static final String ALERT_HOLDING_HIGH_RISK_BREAKDOWN = "HOLDING_HIGH_RISK_BREAKDOWN";
    public static final String ALERT_HOLDING_STOP_BREACHED = "HOLDING_STOP_BREACHED";
    public static final String ALERT_HOLDING_TECHNICAL_INVALIDATION = "HOLDING_TECHNICAL_INVALIDATION";
    public static final String ALERT_HOLDING_SUSPECTED_SHAKEOUT = "HOLDING_SUSPECTED_SHAKEOUT";
    public static final String ALERT_HOLDING_BREACH_OBSERVING = "HOLDING_BREACH_OBSERVING";
    public static final String ALERT_HOLDING_EARNINGS_COUNTDOWN = "HOLDING_EARNINGS_COUNTDOWN";
    public static final String ALERT_HOLDING_EARNINGS_GAP_EXCEEDS_STOP = "HOLDING_EARNINGS_GAP_EXCEEDS_STOP";
    public static final String ALERT_HOLDING_FUNDAMENTAL_WEAKENING = "HOLDING_FUNDAMENTAL_WEAKENING";
    public static final String ALERT_HOLDING_VALUE_TRAP = "HOLDING_VALUE_TRAP";
    public static final String ALERT_HOLDING_OVERVALUED_PROFIT_TAKING = "HOLDING_OVERVALUED_PROFIT_TAKING";
    public static final String ALERT_HOLDING_TARGET_REACHED = "HOLDING_TARGET_REACHED";
    public static final String ALERT_HOLDING_AT_RESISTANCE_ZONE = "HOLDING_AT_RESISTANCE_ZONE";
    public static final String ALERT_HOLDING_SECTOR_CONCENTRATION = "HOLDING_SECTOR_CONCENTRATION";
    public static final String ALERT_WATCHLIST_ACCUMULATE_SUPPORT = "WATCHLIST_ACCUMULATE_SUPPORT";
    public static final String ALERT_WATCHLIST_TREND_PULLBACK_STARTER = "WATCHLIST_TREND_PULLBACK_STARTER";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int BATCH_SIZE = 50;
    private static final long EARNINGS_COUNTDOWN_DAYS = 7L;
    private static final BigDecimal BUFFER_ADEQUATE_PCT = new BigDecimal("0.10");

    private final PortfolioItemRepository portfolioItemRepository;
    private final PortfolioRiskConfigService riskConfigService;
    private final DailyClosingBriefingRepository briefingRepository;
    private final StockRepository stockRepository;
    private final StockStatusRepository stockStatusRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final StockSplitRepository stockSplitRepository;
    private final StockValuationLatestRepository latestValuations;
    private final StockFinancialPeriodRepository financialPeriods;
    private final FinancialReportRepository financialReports;
    private final StockEarningsForecastRepository forecasts;
    private final MarketEventRepository marketEvents;
    private final StockIndustryHistoryRepository industryHistories;

    private final QuoteNormalizationService quoteNormalization;
    private final PriceStructureService priceStructures;
    private final MarketContextService marketContextService;
    private final SupportResistanceService supportResistanceService;
    private final TradePlanService tradePlanService;
    private final PortfolioSplitAligner splitAligner;
    private final PositionSizingCalculator positionSizingCalculator;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final FxRateService fxRateService;
    private final AdrShareRatioRepository adrShareRatios;
    @Autowired(required = false)
    private StockOptionsSummaryRepository optionsSummaryRepository;

    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();
    private final FundamentalSnapshotCalculator fundamentalCalculator = new FundamentalSnapshotCalculator();
    private final ForwardValuationCalculator forwardCalculator = new ForwardValuationCalculator();

    public PortfolioHealthCheckService(
            PortfolioItemRepository portfolioItemRepository,
            PortfolioRiskConfigService riskConfigService,
            DailyClosingBriefingRepository briefingRepository,
            StockRepository stockRepository,
            StockStatusRepository stockStatusRepository,
            StockQuoteRepository stockQuoteRepository,
            StockSplitRepository stockSplitRepository,
            StockValuationLatestRepository latestValuations,
            StockFinancialPeriodRepository financialPeriods,
            FinancialReportRepository financialReports,
            StockEarningsForecastRepository forecasts,
            MarketEventRepository marketEvents,
            StockIndustryHistoryRepository industryHistories,
            QuoteNormalizationService quoteNormalization,
            PriceStructureService priceStructures,
            MarketContextService marketContextService,
            SupportResistanceService supportResistanceService,
            TradePlanService tradePlanService,
            PortfolioSplitAligner splitAligner,
            PositionSizingCalculator positionSizingCalculator,
            MarketStatusService calendar,
            DateTimeService dateTimeService,
            TransactionTemplate transactions) {
        this(portfolioItemRepository, riskConfigService, briefingRepository, stockRepository,
                stockStatusRepository, stockQuoteRepository, stockSplitRepository, latestValuations,
                financialPeriods, financialReports, forecasts, marketEvents, industryHistories,
                quoteNormalization, priceStructures, marketContextService, supportResistanceService,
                tradePlanService, splitAligner, positionSizingCalculator, calendar, dateTimeService,
                transactions, null, null);
    }

    @Autowired
    public PortfolioHealthCheckService(
            PortfolioItemRepository portfolioItemRepository,
            PortfolioRiskConfigService riskConfigService,
            DailyClosingBriefingRepository briefingRepository,
            StockRepository stockRepository,
            StockStatusRepository stockStatusRepository,
            StockQuoteRepository stockQuoteRepository,
            StockSplitRepository stockSplitRepository,
            StockValuationLatestRepository latestValuations,
            StockFinancialPeriodRepository financialPeriods,
            FinancialReportRepository financialReports,
            StockEarningsForecastRepository forecasts,
            MarketEventRepository marketEvents,
            StockIndustryHistoryRepository industryHistories,
            QuoteNormalizationService quoteNormalization,
            PriceStructureService priceStructures,
            MarketContextService marketContextService,
            SupportResistanceService supportResistanceService,
            TradePlanService tradePlanService,
            PortfolioSplitAligner splitAligner,
            PositionSizingCalculator positionSizingCalculator,
            MarketStatusService calendar,
            DateTimeService dateTimeService,
            TransactionTemplate transactions,
            FxRateService fxRateService,
            AdrShareRatioRepository adrShareRatios) {
        this.portfolioItemRepository = portfolioItemRepository;
        this.riskConfigService = riskConfigService;
        this.briefingRepository = briefingRepository;
        this.stockRepository = stockRepository;
        this.stockStatusRepository = stockStatusRepository;
        this.stockQuoteRepository = stockQuoteRepository;
        this.stockSplitRepository = stockSplitRepository;
        this.latestValuations = latestValuations;
        this.financialPeriods = financialPeriods;
        this.financialReports = financialReports;
        this.forecasts = forecasts;
        this.marketEvents = marketEvents;
        this.industryHistories = industryHistories;
        this.quoteNormalization = quoteNormalization;
        this.priceStructures = priceStructures;
        this.marketContextService = marketContextService;
        this.supportResistanceService = supportResistanceService;
        this.tradePlanService = tradePlanService;
        this.splitAligner = splitAligner;
        this.positionSizingCalculator = positionSizingCalculator;
        this.calendar = calendar;
        this.dateTimeService = dateTimeService;
        this.transactions = transactions;
        this.fxRateService = fxRateService;
        this.adrShareRatios = adrShareRatios;
    }

    /** 單次體檢的完整結果（含可複用之大盤快照與 USD 曝險分佈，供收盤簡報進一步計算雷達前 5 名股數）。 */
    public record HealthEvaluationOutput(
            PortfolioHealthSummaryDto summary,
            PortfolioRiskConfigDto config,
            PreloadedMarketData preloadedMarket,
            Map<String, BigDecimal> holdingMarketValueUsdByFigi,
            Map<String, BigDecimal> sectorMarketValueUsdByIndustry,
            Set<String> unverifiedHoldingFigis,
            int excludedHoldingsCount
    ) {
        public HealthEvaluationOutput {
            holdingMarketValueUsdByFigi = holdingMarketValueUsdByFigi == null ? Map.of() : Map.copyOf(holdingMarketValueUsdByFigi);
            sectorMarketValueUsdByIndustry = sectorMarketValueUsdByIndustry == null ? Map.of() : Map.copyOf(sectorMarketValueUsdByIndustry);
            unverifiedHoldingFigis = unverifiedHoldingFigis == null ? Set.of() : Set.copyOf(unverifiedHoldingFigis);
        }
    }

    private record LoadedInputs(
            List<PortfolioItem> items,
            PortfolioRiskConfigDto config,
            DailyClosingBriefing priorBriefing,
            Map<String, Stock> stocksByFigi,
            Map<String, StockStatus> statusByFigi,
            Map<String, StockValuationLatest> valuationByFigi,
            Map<String, StockIndustryHistory> industryByFigi,
            Map<String, List<QuoteBarRow>> quotesByFigi,
            Map<String, List<StockSplit>> splitsByFigi,
            Map<String, List<MarketEvent>> earningsByFigi,
            Map<String, List<StockEarningsForecast>> forecastsByFigi,
            Map<String, List<StockFinancialPeriod>> periodsByFigi,
            Map<String, List<KnownReportPeriod>> knownReportsByFigi,
            FxRateLookup fxLookup,
            Map<String, AdrShareRatio> adrBySymbol
    ) {}

    private record PriorHoldingReference(
            String positionId,
            LocalDate referenceDate,
            BigDecimal protectiveStop,
            String stopSource,
            BigDecimal target1,
            BigDecimal target2,
            LocalDate targetsDate
    ) {}

    private record EvaluatedHoldingPrePass(
            PortfolioItem item,
            Stock stock,
            StockStatus status,
            StockValuationLatest valuation,
            String industryCode,
            String industryName,
            LocalDate evalDate,
            BigDecimal closePrice,
            AlignedPosition alignedPosition,
            BigDecimal rawMarketValue,
            BigDecimal marketValue,
            BigDecimal costTotal,
            BigDecimal unrealizedPnlAmount,
            BigDecimal unrealizedPnlPct,
            List<String> dataGapReasons
    ) {}

    public HealthEvaluationOutput evaluate(LocalDate tradeDate, Instant observationCutoff, String sourceMode) {
        Instant now = dateTimeService.now();
        LocalDate effectiveDate = tradeDate != null ? tradeDate : calendar.getLastClosedTradingDay(now);
        Instant obsCutoff = observationCutoff != null ? observationCutoff : now;
        Instant sessionCutoff = calendar.getSessionCloseAt(effectiveDate);
        LocalDate fiveYearsAgo = effectiveDate.minusYears(5);
        LocalDate maxAnnualObsDate = obsCutoff.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate forecastHistoryFrom = maxAnnualObsDate.minusDays(
                ForwardValuationCalculator.MAX_FORECAST_STALE_DAYS + ForwardValuationCalculator.REV_90D_MAX_DAYS + 10L);

        // 1. 以單一短 REPEATABLE_READ 唯讀交易一致擷取個人持倉、風控設定、拆股與技術原料
        LoadedInputs loaded = loadConsistentInputs(effectiveDate, sessionCutoff, obsCutoff, fiveYearsAgo, forecastHistoryFrom, maxAnnualObsDate);
        PreloadedMarketData preloadedMarket = marketContextService.preloadMarketData(effectiveDate, obsCutoff);
        MarketRegime globalRegime = preloadedMarket != null && preloadedMarket.marketSnapshot() != null
                && preloadedMarket.marketSnapshot().status() == MetricStatus.AVAILABLE
                && preloadedMarket.marketSnapshot().value() != null
                ? preloadedMarket.marketSnapshot().value().regime() : null;

        Instant priorCutoff = (sessionCutoff != null && sessionCutoff.isBefore(obsCutoff)) ? sessionCutoff : obsCutoff;
        Map<String, PriorHoldingReference> priorRefs = extractPriorReferences(loaded.priorBriefing(), priorCutoff);

        // 2. 第一輪：計算所有 HOLDING 的拆股對齊市值、原幣損益與 USD 板塊曝險彙總（僅計入當日行情齊全且拆股已驗證之 USD 持倉）
        Map<String, BigDecimal> holdingMvUsdByFigi = new HashMap<>();
        Map<String, BigDecimal> sectorMvUsdByIndustry = new HashMap<>();
        Map<String, SectorExposureAccumulator> sectorAccMap = new LinkedHashMap<>();
        Set<String> unverifiedHoldingFigis = new LinkedHashSet<>();
        List<EvaluatedHoldingPrePass> holdingPrePasses = new ArrayList<>();
        List<PortfolioItem> watchlistItems = new ArrayList<>();
        Set<String> coverageWarningsSet = new LinkedHashSet<>();
        int excludedHoldingsCount = 0;

        for (PortfolioItem item : loaded.items()) {
            if (PortfolioItem.TYPE_WATCHLIST.equals(item.getItemType())) {
                watchlistItems.add(item);
                continue;
            }
            EvaluatedHoldingPrePass pre = preEvaluateHolding(item, effectiveDate, loaded);
            holdingPrePasses.add(pre);
            String sym = pre.stock() != null ? pre.stock().getSymbol() : item.getFigi();
            if (!pre.dataGapReasons().isEmpty()) {
                for (String gap : pre.dataGapReasons()) {
                    coverageWarningsSet.add(gap + ":" + sym);
                }
            }
            boolean isUsd = pre.stock() != null && "USD".equalsIgnoreCase(pre.stock().getCurrency());
            boolean targetDateFresh = pre.evalDate() != null && effectiveDate.equals(pre.evalDate());
            if (isUsd && pre.rawMarketValue() != null && targetDateFresh) {
                holdingMvUsdByFigi.put(item.getFigi(), pre.rawMarketValue());
                if (pre.industryCode() != null && !pre.industryCode().isBlank()) {
                    sectorMvUsdByIndustry.merge(pre.industryCode(), pre.rawMarketValue(), (a, b) -> a.add(b, MC));
                    SectorExposureAccumulator acc = sectorAccMap.computeIfAbsent(pre.industryCode(),
                            k -> new SectorExposureAccumulator(pre.industryCode(), pre.industryName()));
                    acc.holdingCount++;
                    acc.marketValueUsd = acc.marketValueUsd.add(pre.rawMarketValue(), MC);
                } else if (!SecurityTypePolicy.isEtfType(pre.stock().getStockType())) {
                    coverageWarningsSet.add("INDUSTRY_UNCLASSIFIED:" + sym);
                }
            } else {
                excludedHoldingsCount++;
                unverifiedHoldingFigis.add(item.getFigi());
                if (pre.stock() == null || pre.stock().getCurrency() == null || pre.stock().getCurrency().isBlank()) {
                    coverageWarningsSet.add("CURRENCY_MISSING:" + sym);
                } else if (!isUsd) {
                    coverageWarningsSet.add("NON_USD_EXCLUDED:" + pre.stock().getSymbol());
                }
            }
        }

        // 計算板塊集中度與板塊層級警報（每交易日每產業一筆）
        PortfolioRiskConfigDto config = loaded.config();
        BigDecimal totalCapital = config.totalCapital();
        BigDecimal sectorCapAmount = totalCapital.multiply(config.maxSectorExposurePct(), MC);
        List<SectorExposureDto> sectorExposures = new ArrayList<>();
        List<PortfolioAlertDto> sectorAlerts = new ArrayList<>();
        for (SectorExposureAccumulator acc : sectorAccMap.values()) {
            BigDecimal exposurePct = totalCapital.signum() > 0
                    ? acc.marketValueUsd.divide(totalCapital, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            boolean exceeded = acc.marketValueUsd.compareTo(sectorCapAmount) > 0;
            sectorExposures.add(new SectorExposureDto(
                    acc.industryCode,
                    acc.industryName,
                    acc.holdingCount,
                    acc.marketValueUsd.setScale(2, RoundingMode.HALF_UP),
                    exposurePct,
                    config.maxSectorExposurePct(),
                    exceeded
            ));
            if (exceeded) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("industryCode", acc.industryCode);
                payload.put("industryName", acc.industryName != null ? acc.industryName : acc.industryCode);
                payload.put("holdingCount", acc.holdingCount);
                payload.put("marketValueUsd", acc.marketValueUsd.setScale(2, RoundingMode.HALF_UP));
                payload.put("exposurePct", exposurePct);
                payload.put("maxSectorExposurePct", config.maxSectorExposurePct());
                sectorAlerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_WARNING,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_SECTOR_CONCENTRATION,
                        null,
                        null,
                        acc.industryName != null ? acc.industryName : acc.industryCode,
                        payload
                ));
            }
        }
        sectorExposures.sort(Comparator.comparing(SectorExposureDto::exposurePct, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SectorExposureDto::industryCode));

        // 3. 第二輪：逐檔完成 HOLDING 深度體檢（技術破位、財報倒數、基本面惡化、高估與目標觸及），並彙整第二輪新增的資料缺口
        List<HoldingHealthItemDto> holdingResults = new ArrayList<>(holdingPrePasses.size());
        int criticalCount = 0;
        int warningCount = 0;
        int infoCount = 0;
        int alertHoldingCount = 0;

        for (EvaluatedHoldingPrePass pre : holdingPrePasses) {
            HoldingHealthItemDto result = evaluateHoldingFull(
                    pre, effectiveDate, sessionCutoff, obsCutoff, fiveYearsAgo, maxAnnualObsDate,
                    preloadedMarket, loaded, priorRefs.get(pre.item().getFigi()));
            holdingResults.add(result);
            for (String gap : result.dataGapReasons()) {
                coverageWarningsSet.add(gap + ":" + result.symbol());
            }
            if (!result.alerts().isEmpty()) {
                alertHoldingCount++;
            }
            for (PortfolioAlertDto alert : result.alerts()) {
                if (InAppNotification.SEVERITY_CRITICAL.equals(alert.severity())) {
                    criticalCount++;
                } else if (InAppNotification.SEVERITY_WARNING.equals(alert.severity())) {
                    warningCount++;
                } else {
                    infoCount++;
                }
            }
        }
        warningCount += sectorAlerts.size();

        // 依最高嚴重度排序（CRITICAL -> WARNING -> INFO -> NONE），同級按代碼排序
        holdingResults.sort(Comparator.comparingInt((HoldingHealthItemDto h) -> severityRank(h.highestSeverity()))
                .thenComparing(HoldingHealthItemDto::symbol, Comparator.nullsLast(String::compareTo)));

        // 4. 第三輪：逐檔評估 WATCHLIST 進場機會並計算建議買入股數
        List<WatchlistOpportunityDto> watchlistOpportunities = new ArrayList<>();
        for (PortfolioItem watch : watchlistItems) {
            WatchlistOpportunityDto opp = evaluateWatchlistItem(
                    watch, effectiveDate, sessionCutoff, obsCutoff, fiveYearsAgo, maxAnnualObsDate,
                    preloadedMarket, loaded, holdingMvUsdByFigi, sectorMvUsdByIndustry, unverifiedHoldingFigis, excludedHoldingsCount);
            if (opp != null) {
                watchlistOpportunities.add(opp);
                infoCount++;
            }
        }

        List<String> coverageWarnings = List.copyOf(coverageWarningsSet);
        boolean completeCoverage = coverageWarnings.isEmpty();
        boolean structureHealthy = alertHoldingCount == 0 && sectorAlerts.isEmpty() && completeCoverage;

        PortfolioHealthSummaryDto summaryDto = new PortfolioHealthSummaryDto(
                effectiveDate,
                sourceMode,
                now,
                globalRegime != null ? globalRegime.name() : MarketRegime.UNAVAILABLE.name(),
                holdingResults.size(),
                watchlistItems.size(),
                alertHoldingCount,
                criticalCount,
                warningCount,
                infoCount,
                structureHealthy,
                completeCoverage,
                excludedHoldingsCount,
                coverageWarnings,
                holdingResults,
                watchlistOpportunities,
                sectorExposures,
                sectorAlerts
        );

        return new HealthEvaluationOutput(
                summaryDto,
                config,
                preloadedMarket,
                Map.copyOf(holdingMvUsdByFigi),
                Map.copyOf(sectorMvUsdByIndustry),
                Set.copyOf(unverifiedHoldingFigis),
                excludedHoldingsCount
        );
    }

    private LoadedInputs loadConsistentInputs(LocalDate effectiveDate,
                                              Instant sessionCutoff,
                                              Instant obsCutoff,
                                              LocalDate fiveYearsAgo,
                                              LocalDate forecastHistoryFrom,
                                              LocalDate maxAnnualObsDate) {
        TransactionTemplate repeatableReadTx = new TransactionTemplate(transactions.getTransactionManager());
        repeatableReadTx.setReadOnly(true);
        repeatableReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        return repeatableReadTx.execute(tx -> {
            List<PortfolioItem> items = portfolioItemRepository.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc();
            PortfolioRiskConfigDto config = riskConfigService.getConfig();
            DailyClosingBriefing priorBriefing = briefingRepository
                    .findTopByTradeDateBeforeOrderByTradeDateDesc(effectiveDate).orElse(null);

            if (items.isEmpty()) {
                return new LoadedInputs(items, config, priorBriefing,
                        Map.of(), Map.of(), Map.of(), Map.of(),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                        FxRateLookup.EMPTY, Map.of());
            }

            List<String> allFigis = items.stream().map(PortfolioItem::getFigi).distinct().toList();
            Map<String, Stock> stocksByFigi = new HashMap<>();
            Map<String, StockStatus> statusByFigi = new HashMap<>();
            Map<String, StockValuationLatest> valuationByFigi = new HashMap<>();
            Map<String, StockIndustryHistory> industryByFigi = new HashMap<>();
            Map<String, List<QuoteBarRow>> quotesByFigi = new HashMap<>();
            Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
            Map<String, List<MarketEvent>> earningsByFigi = new HashMap<>();
            Map<String, List<StockEarningsForecast>> forecastsByFigi = new HashMap<>();
            Map<String, List<StockFinancialPeriod>> periodsByFigi = new HashMap<>();
            Map<String, List<KnownReportPeriod>> knownReportsByFigi = new HashMap<>();

            for (List<String> batch : Lists.partition(allFigis, BATCH_SIZE)) {
                for (Stock s : stockRepository.findAllById(batch)) {
                    stocksByFigi.put(s.getFigi(), s);
                }
                for (StockStatus st : stockStatusRepository.findAllById(batch)) {
                    statusByFigi.put(st.getFigi(), st);
                }
                for (StockValuationLatest v : latestValuations.findByFigiIn(batch)) {
                    valuationByFigi.put(v.getFigi(), v);
                }
                for (StockIndustryHistory ih : industryHistories.findByFigisAt(batch, sessionCutoff)) {
                    industryByFigi.put(ih.getFigi(), ih);
                }
                for (QuoteBarRow bar : stockQuoteRepository.findQuoteBarsOrdered(batch, fiveYearsAgo, effectiveDate)) {
                    quotesByFigi.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
                }
                for (StockSplit sp : stockSplitRepository.findByFigiInOrderByExecutionDateAsc(batch)) {
                    splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
                }
                for (MarketEvent ev : marketEvents.findVisibleEarningsForFigis(batch, obsCutoff)) {
                    earningsByFigi.computeIfAbsent(ev.getFigi(), k -> new ArrayList<>()).add(ev);
                }
                for (StockEarningsForecast f : forecasts.findVisibleForStocksBetween(batch, forecastHistoryFrom, maxAnnualObsDate, obsCutoff)) {
                    forecastsByFigi.computeIfAbsent(f.getFigi(), k -> new ArrayList<>()).add(f);
                }
                for (StockFinancialPeriod p : financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(batch)) {
                    periodsByFigi.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
                }
                for (FigiKnownReportPeriod r : financialReports.findKnownReportPeriodsForFigis(batch, obsCutoff)) {
                    knownReportsByFigi.computeIfAbsent(r.figi(), k -> new ArrayList<>())
                            .add(new KnownReportPeriod(r.periodEnd(), r.periodType(), r.fiscalYear(), r.period(), r.observedAt()));
                }
            }

            FxRateLookup fxLookup = preloadFxLookup(periodsByFigi, effectiveDate);
            Map<String, AdrShareRatio> adrBySymbol = preloadAdrRatios(stocksByFigi);
            return new LoadedInputs(
                    items, config, priorBriefing,
                    stocksByFigi, statusByFigi, valuationByFigi, industryByFigi,
                    quotesByFigi, splitsByFigi, earningsByFigi, forecastsByFigi, periodsByFigi, knownReportsByFigi,
                    fxLookup, adrBySymbol
            );
        });
    }

    private EvaluatedHoldingPrePass preEvaluateHolding(PortfolioItem item, LocalDate targetDate, LoadedInputs loaded) {
        String figi = item.getFigi();
        Stock stock = loaded.stocksByFigi().get(figi);
        StockStatus status = loaded.statusByFigi().get(figi);
        StockValuationLatest valuation = loaded.valuationByFigi().get(figi);
        StockIndustryHistory ih = loaded.industryByFigi().get(figi);
        // 與 PortfolioManagementService 嚴格統一採用 D 日收市產業歷史分類，不回退估值快照欄位以免跨入口出現不同集中度判定
        String industryCode = ih != null && ih.getIndustry() != null ? ih.getIndustry().getCode() : null;
        String industryName = ih != null && ih.getIndustry() != null ? ih.getIndustry().getIndustry() : industryCode;

        List<QuoteBarRow> rawQuotes = loaded.quotesByFigi().get(figi);
        List<String> gaps = new ArrayList<>();
        QuoteBarRow latestRaw = (rawQuotes == null || rawQuotes.isEmpty()) ? null : rawQuotes.getLast();
        LocalDate evalDate = latestRaw != null ? latestRaw.quoteDate() : null;
        BigDecimal closePrice = latestRaw != null ? latestRaw.closePrice() : null;
        if (latestRaw == null || closePrice == null || closePrice.signum() <= 0) {
            gaps.add("QUOTE_MISSING");
        } else if (!targetDate.equals(evalDate)) {
            gaps.add("TARGET_DATE_QUOTE_MISSING");
        }

        AlignedPosition aligned = null;
        BigDecimal rawMarketValue = null;
        BigDecimal marketValue = null;
        BigDecimal costTotal = null;
        BigDecimal pnlAmount = null;
        BigDecimal pnlPct = null;

        if (evalDate != null && closePrice != null && closePrice.signum() > 0) {
            aligned = splitAligner.alignHolding(
                    item.getShares(),
                    item.getAvgCost(),
                    item.getCostBasisDate(),
                    evalDate,
                    status,
                    loaded.splitsByFigi().getOrDefault(figi, List.of())
            );
            if (!aligned.verified()) {
                gaps.add(aligned.status());
            } else {
                rawMarketValue = aligned.rawAdjustedShares().multiply(closePrice, MC);
                BigDecimal rawCostTotal = aligned.rawAdjustedShares().multiply(aligned.rawAdjustedAvgCost(), MC);
                marketValue = rawMarketValue.setScale(2, RoundingMode.HALF_UP);
                costTotal = rawCostTotal.setScale(2, RoundingMode.HALF_UP);
                pnlAmount = rawMarketValue.subtract(rawCostTotal, MC).setScale(2, RoundingMode.HALF_UP);
                pnlPct = rawCostTotal.signum() > 0
                        ? rawMarketValue.subtract(rawCostTotal, MC).divide(rawCostTotal, 6, RoundingMode.HALF_UP) : null;
            }
        }

        return new EvaluatedHoldingPrePass(
                item, stock, status, valuation, industryCode, industryName,
                evalDate, closePrice, aligned, rawMarketValue, marketValue, costTotal, pnlAmount, pnlPct, gaps
        );
    }

    private HoldingHealthItemDto evaluateHoldingFull(EvaluatedHoldingPrePass pre,
                                                     LocalDate targetDate,
                                                     Instant sessionCutoff,
                                                     Instant obsCutoff,
                                                     LocalDate fiveYearsAgo,
                                                     LocalDate maxAnnualObsDate,
                                                     PreloadedMarketData preloadedMarket,
                                                     LoadedInputs loaded,
                                                     PriorHoldingReference priorRef) {
        String figi = pre.item().getFigi();
        Stock stock = pre.stock();
        String symbol = stock != null ? stock.getSymbol() : figi;
        String stockName = stock != null ? stock.getLongName() : null;
        String currency = stock != null ? stock.getCurrency() : null;
        boolean isEtf = stock != null && SecurityTypePolicy.isEtfType(stock.getStockType());
        List<PortfolioAlertDto> alerts = new ArrayList<>();
        List<String> gaps = new ArrayList<>(pre.dataGapReasons());

        // 1. 財報事件（ETF／ETP 無單一公司財報，跳過財報倒數；一般股票即使缺當日行情，已知 0–7 日曆日尚未發生的財報仍照常提醒）
        List<MarketEvent> stockEarnings = isEtf ? List.of() : loaded.earningsByFigi().getOrDefault(figi, List.of());
        LocalDate nextEarningsDate = null;
        Integer daysToEarnings = null;
        String bufferLevel = null;
        if (!isEtf) {
            LocalDate obsNyDate = obsCutoff != null ? obsCutoff.atZone(MarketStatusService.NY_ZONE).toLocalDate() : targetDate;
            LocalDate earningsBaseDate = obsNyDate.isAfter(targetDate) ? obsNyDate : targetDate;
            MarketEvent upcomingEarnings = findUpcomingOrTodayEarnings(stockEarnings, earningsBaseDate, obsCutoff);
            nextEarningsDate = upcomingEarnings != null ? upcomingEarnings.getEventDate() : null;
            daysToEarnings = nextEarningsDate == null ? null : (int) ChronoUnit.DAYS.between(earningsBaseDate, nextEarningsDate);
            bufferLevel = classifyEarningsBuffer(pre.unrealizedPnlPct());

            if (upcomingEarnings != null && daysToEarnings != null && daysToEarnings >= 0 && daysToEarnings <= EARNINGS_COUNTDOWN_DAYS) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("eventDate", nextEarningsDate.toString());
                payload.put("daysToEarnings", daysToEarnings);
                payload.put("timingCode", resolveEarningsTimingCode(upcomingEarnings));
                payload.put("bufferLevel", bufferLevel);
                if (pre.unrealizedPnlPct() != null) {
                    payload.put("unrealizedPnlPct", pre.unrealizedPnlPct());
                }
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_WARNING,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_EARNINGS_COUNTDOWN,
                        figi, symbol, stockName, payload
                ));
            }
        }

        // 若當日行情缺失，無法進行當日技術與收盤比較，保留已產出之財報警示與缺口原因
        List<QuoteBarRow> rawQuotes = loaded.quotesByFigi().get(figi);
        boolean hasTargetDayBar = pre.evalDate() != null && targetDate.equals(pre.evalDate()) && pre.closePrice() != null;
        if (!hasTargetDayBar || stock == null) {
            String missingVerdict = isEtf ? "NOT_APPLICABLE_FOR_ETF" : null;
            String missingBacking = isEtf ? "NOT_APPLICABLE" : null;
            String missingStance = isEtf ? "INSUFFICIENT_DATA" : null;
            return buildHoldingResult(pre, symbol, stockName, currency, missingVerdict, missingBacking, missingStance, null,
                    null, null, null, null, null, nextEarningsDate, daysToEarnings, bufferLevel, alerts, gaps);
        }

        // 2. 複用單股分析核心（POSITION 週期，與 Batch 02/03 保持 100% 口徑一致，零外部 API）
        List<StockSplit> stockSplits = loaded.splitsByFigi().getOrDefault(figi, List.of());
        AnalysisContext context = new AnalysisContext(figi, sessionCutoff, obsCutoff, AnalysisHorizon.POSITION);
        MetricResult<NormalizedPriceSeriesDto> price = quoteNormalization.normalizeFromLoaded(
                context, fiveYearsAgo, targetDate, stock, pre.status(), rawQuotes, stockSplits);

        MetricResult<PriceStructureDto> structure = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<MarketContextDto> marketResult = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<SupportResistanceDto> support = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<TradePlanResultDto> tradePlans = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());

        if (price.status() == MetricStatus.AVAILABLE && price.value() != null && !price.value().bars().isEmpty()) {
            structure = priceStructures.analyze(context, price.value());
            if (structure.status() == MetricStatus.AVAILABLE && structure.value() != null) {
                List<MarketEvent> pastEarnings = isEtf ? List.of() : stockEarnings.stream()
                        .filter(ev -> ev.getEventDate() != null && !ev.getEventDate().isAfter(targetDate))
                        .toList();
                LocalDate winFrom = targetDate.minusDays(10);
                LocalDate winTo = targetDate.plusDays(20);
                List<MarketEvent> windowEvents = isEtf ? List.of() : stockEarnings.stream()
                        .filter(ev -> ev.getEventDate() != null && !ev.getEventDate().isBefore(winFrom) && !ev.getEventDate().isAfter(winTo))
                        .toList();
                marketResult = marketContextService.evaluateWithPreloaded(
                        context, price.value(), structure.value(), pastEarnings, windowEvents, preloadedMarket);
                MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
                support = supportResistanceService.analyze(context, price.value(), structure.value(), marketValue);
                if (support.status() == MetricStatus.AVAILABLE && support.value() != null) {
                    PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
                    tradePlans = tradePlanService.build(context, price.value(), structure.value(), support.value(), constraint);
                    if (tradePlans.status() != MetricStatus.AVAILABLE) {
                        gaps.add("TECHNICAL_ANALYSIS_INCOMPLETE");
                    }
                } else {
                    gaps.add("TECHNICAL_ANALYSIS_INCOMPLETE");
                }
            } else {
                gaps.add("TECHNICAL_ANALYSIS_INCOMPLETE");
            }
        } else {
            gaps.add("TECHNICAL_SERIES_UNAVAILABLE");
        }

        // 3. 純記憶體組裝基本面、前瞻共識與綜合判定（0 次外部 API）；ETF／ETP 走純技術摘要且不產生 VALUATION_MISSING 缺口
        BigDecimal evalPrice = pre.closePrice();
        StockValuationLatest v = pre.valuation();
        boolean valuationFresh = false;
        String resolvedVerdict;
        String resolvedBacking;
        String resolvedStance;
        boolean forwardValueTrap = false;
        TrendPullbackStarterDto resolvedStarter = null;

        if (isEtf) {
            MetricResult<InvestmentSummaryDto> etfSummary = summaryCalculator.calculateForEtf(
                    AnalysisHorizon.POSITION, evalPrice, currency, structure, support, tradePlans, marketResult);
            if (etfSummary.status() == MetricStatus.AVAILABLE && etfSummary.value() != null) {
                resolvedVerdict = etfSummary.value().verdict();
                resolvedBacking = etfSummary.value().fundamentalBacking();
                resolvedStance = etfSummary.value().stance();
            } else {
                resolvedVerdict = "NOT_APPLICABLE_FOR_ETF";
                resolvedBacking = "NOT_APPLICABLE";
                resolvedStance = "INSUFFICIENT_DATA";
            }
        } else {
            valuationFresh = v != null && targetDate.equals(v.getValuationDate());
            if (v == null) {
                gaps.add("VALUATION_MISSING");
            } else if (v.getValuationDate() != null && !valuationFresh) {
                gaps.add("VALUATION_STALE");
            }
            List<StockFinancialPeriod> stockPeriods = loaded.periodsByFigi().getOrDefault(figi, List.of());
            List<KnownReportPeriod> knownReports = loaded.knownReportsByFigi().getOrDefault(figi, List.of());
            List<StockEarningsForecast> stockForecasts = loaded.forecastsByFigi().getOrDefault(figi, List.of());

            StockFinancialPeriod matchedPeriod = StockValuationService.matchPeriod(stockPeriods, targetDate);
            String splitError = StockValuationService.splitSyncError(pre.status());
            boolean isAdr = SecurityTypePolicy.isAdrType(stock.getStockType());
            AdrShareRatio adrRatio = isAdr && stock.getSymbol() != null
                    ? loaded.adrBySymbol().get(stock.getSymbol().trim().toUpperCase(java.util.Locale.ROOT)) : null;
            boolean verifiedAdr = isAdr && adrRatio != null && adrRatio.isVerified()
                    && adrRatio.getAdrRatio() != null && adrRatio.getAdrRatio().signum() > 0;
            FxResolution fxRes = resolveFxForStock(loaded.fxLookup(), matchedPeriod, stock.getCurrency(), targetDate);
            BigDecimal fxRateToQuote = fxRes != null && fxRes.isUsable() ? fxRes.usdPerUnit() : null;
            String valuationBlockedReason = isAdr && !verifiedAdr ? "ADR_RATIO_UNVERIFIED"
                    : (fxRes != null && !fxRes.isUsable() && fxRes.reason() != null ? fxRes.reason().name() : null);
            BigDecimal capAtDay = valuationFresh ? v.getMarketCap() : null;
            BigDecimal capClosePrice = (valuationFresh && v.getClosePrice() != null && v.getClosePrice().signum() > 0)
                    ? v.getClosePrice() : evalPrice;
            DayEpsResolution epsResolution = StockValuationService.resolveDayEps(
                    stock.getCurrency(), pre.status(), matchedPeriod, stockSplits, targetDate, fxRateToQuote,
                    isAdr, verifiedAdr, capAtDay, capClosePrice);
            String peReason = valuationFresh && v.getPeReason() != null ? v.getPeReason().name() : epsResolution.peReason();
            boolean financialInstitution = v != null && IndustryValuationProposalService.isFinancialInstitutionModel(v.getModelCode());
            FundamentalSnapshotDto fundamentalsDto = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                    stockPeriods, matchedPeriod, stockSplits, targetDate, stock.getCurrency(),
                    evalPrice, valuationFresh ? v.getMarketCap() : null, null, epsResolution.epsAtDay(), peReason,
                    valuationFresh ? v.getPe() : null, financialInstitution, splitError,
                    fxRateToQuote, valuationBlockedReason, verifiedAdr));
            FutuObservationsDto futuObs = buildReadOnlyForwardObservations(
                    targetDate, obsCutoff, maxAnnualObsDate, stock.getCurrency(), evalPrice,
                    valuationFresh ? v.getMarketCap() : null, epsResolution.epsAtDay(), peReason,
                    stockSplits, splitError, stockForecasts, knownReports, stockPeriods);

            String verdict = valuationFresh && v.getVerdict() != null ? v.getVerdict() : "INSUFFICIENT_EVIDENCE";
            boolean currentLoss = StockValuationService.isCurrentTtmLoss(matchedPeriod, pre.status(), targetDate, isAdr, verifiedAdr);
            String backing = valuationFresh && v.getFundamentalBacking() != null
                    ? v.getFundamentalBacking() : ValuationSummaryCalculator.evaluateFundamentalBacking(fundamentalsDto, currentLoss);
            BigDecimal bandLow = valuationFresh ? v.getFairValueLow() : null;

            RadarEvaluationResult eval = summaryCalculator.evaluateForRadar(
                    AnalysisHorizon.POSITION, evalPrice, verdict, bandLow, backing,
                    fundamentalsDto, futuObs, structure, support, tradePlans, marketResult);
            resolvedVerdict = eval.verdict();
            resolvedBacking = eval.fundamentalBacking();
            resolvedStance = eval.stance();
            forwardValueTrap = eval.forwardValueTrap();
            resolvedStarter = eval.trendPullbackStarter();
        }

        // 僅當同一持倉生命週期（positionId 一致）才承接上一份簡報的事前止損與 T1/T2，避免平倉重買誤沿用舊基準
        PriorHoldingReference effectivePriorRef = (priorRef != null
                && pre.item().getPositionId() != null
                && pre.item().getPositionId().equals(priorRef.positionId()))
                ? priorRef : null;

        // 4. 破位止損診斷（最高優先；優先比對前期簡報記錄的事前參考止損，並去重合併同日 HIGH_RISK_BREAKDOWN）
        boolean actualPriceStopTriggered = false;
        BreakoutDiagnosisDto primaryDiag = support.status() == MetricStatus.AVAILABLE && support.value() != null
                ? support.value().breakoutDiagnosis() : null;
        String diagCode = primaryDiag != null && primaryDiag.diagnosis() != null ? primaryDiag.diagnosis().name() : null;
        boolean highRiskBreakdown = primaryDiag != null && primaryDiag.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN;

        boolean priorStopBreached = false;
        AlignedPrice alignedPriorStop = null;
        if (effectivePriorRef != null && effectivePriorRef.protectiveStop() != null && effectivePriorRef.referenceDate() != null) {
            alignedPriorStop = splitAligner.alignPrice(
                    effectivePriorRef.protectiveStop(), effectivePriorRef.referenceDate(), targetDate, pre.status(), stockSplits);
            if (alignedPriorStop.verified() && alignedPriorStop.rawAdjustedPrice() != null
                    && evalPrice.compareTo(alignedPriorStop.rawAdjustedPrice()) <= 0) {
                priorStopBreached = true;
            }
        }

        List<TradePlanDto> planList = tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();

        // 06_Enhancement 2.1：若距財報 <= 14 天且已快取之跨式 Implied Move 大於保護止損距離，提示財報跳空打穿止損風險（0 外部 API）
        if (!isEtf && optionsSummaryRepository != null && daysToEarnings != null && daysToEarnings >= 0 && daysToEarnings <= 14
                && nextEarningsDate != null && evalPrice != null && evalPrice.signum() > 0) {
            BigDecimal refStop = (alignedPriorStop != null && alignedPriorStop.verified() && alignedPriorStop.rawAdjustedPrice() != null)
                    ? alignedPriorStop.rawAdjustedPrice()
                    : planList.stream()
                            .filter(p -> p != null && p.stop() != null && p.stop().protectivePrice() != null
                                    && p.stop().protectivePrice().signum() > 0)
                            .map(p -> p.stop().protectivePrice())
                            .findFirst()
                            .orElse(null);
            if (refStop != null && refStop.signum() > 0 && refStop.compareTo(evalPrice) < 0) {
                Optional<StockOptionsSummary> optFound = optionsSummaryRepository
                        .findTopByFigiAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(figi, targetDate);
                if (optFound.isPresent() && optFound.get().getImpliedMovePct() != null
                        && optFound.get().getImpliedMovePct().signum() > 0) {
                    StockOptionsSummary opt = optFound.get();
                    boolean matchesUpcomingEarnings = nextEarningsDate.equals(opt.getNextEarningsDate())
                            && opt.getStraddleExpiration() != null && !opt.getStraddleExpiration().isBefore(nextEarningsDate)
                            && opt.getSnapshotDate() != null && !opt.getSnapshotDate().isBefore(targetDate.minusDays(7));
                    BigDecimal stopDist = evalPrice.subtract(refStop, MC).divide(evalPrice, 6, RoundingMode.HALF_UP);
                    if (matchesUpcomingEarnings && opt.getImpliedMovePct().compareTo(stopDist) > 0) {
                        Map<String, Object> gapPayload = new LinkedHashMap<>();
                        gapPayload.put("impliedMovePct", opt.getImpliedMovePct().setScale(4, RoundingMode.HALF_UP));
                        gapPayload.put("stopDistancePct", stopDist.setScale(4, RoundingMode.HALF_UP));
                        gapPayload.put("daysToEarnings", daysToEarnings);
                        gapPayload.put("eventDate", nextEarningsDate.toString());
                        alerts.add(new PortfolioAlertDto(
                                InAppNotification.SEVERITY_WARNING,
                                InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                                ALERT_HOLDING_EARNINGS_GAP_EXCEEDS_STOP,
                                figi, symbol, stockName, gapPayload
                        ));
                    }
                }
            }
        }

        if (priorStopBreached) {
            actualPriceStopTriggered = true;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
            payload.put("protectiveStop", alignedPriorStop.adjustedPrice());
            payload.put("stopSource", effectivePriorRef.stopSource() != null ? effectivePriorRef.stopSource() : "PRIOR_BRIEFING_STOP");
            payload.put("referenceDate", effectivePriorRef.referenceDate().toString());
            // 僅呈現已證實之前期止損資訊，不將可能屬於不同支撐區之今日主診斷區間欄位張冠李戴
            alerts.add(new PortfolioAlertDto(
                    InAppNotification.SEVERITY_CRITICAL,
                    InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                    ALERT_HOLDING_STOP_BREACHED,
                    figi, symbol, stockName, payload
            ));
        } else if (highRiskBreakdown) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
            payload.put("diagnosis", BreakoutDiagnosis.HIGH_RISK_BREAKDOWN.name());
            if (primaryDiag.zoneId() != null) payload.put("zoneId", primaryDiag.zoneId());
            if (primaryDiag.zoneTimeframe() != null) payload.put("zoneTimeframe", primaryDiag.zoneTimeframe().name());
            if (primaryDiag.supportLower() != null) payload.put("supportLower", primaryDiag.supportLower().setScale(4, RoundingMode.HALF_UP));
            if (primaryDiag.supportUpper() != null) payload.put("supportUpper", primaryDiag.supportUpper().setScale(4, RoundingMode.HALF_UP));
            if (primaryDiag.breachDate() != null) payload.put("breachDate", primaryDiag.breachDate().toString());
            if (primaryDiag.rvolAtBreach() != null) payload.put("rvolAtBreach", primaryDiag.rvolAtBreach());
            alerts.add(new PortfolioAlertDto(
                    InAppNotification.SEVERITY_CRITICAL,
                    InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                    ALERT_HOLDING_HIGH_RISK_BREAKDOWN,
                    figi, symbol, stockName, payload
            ));
        } else {
            TradePlanDto stopBreachedPlan = findPlanByReason(planList, "PROTECTIVE_STOP_BREACHED");
            TradePlanDto invalidatedPlan = stopBreachedPlan == null ? findPlanByReason(planList, "TECHNICAL_INVALIDATION_CONFIRMED") : null;
            if (stopBreachedPlan != null) {
                actualPriceStopTriggered = true;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                if (stopBreachedPlan.stop() != null && stopBreachedPlan.stop().protectivePrice() != null) {
                    payload.put("protectiveStop", stopBreachedPlan.stop().protectivePrice().setScale(4, RoundingMode.HALF_UP));
                }
                payload.put("stopSource", "CURRENT_ZONE_PLAN");
                if (stopBreachedPlan.sourceZoneId() != null) {
                    payload.put("zoneId", stopBreachedPlan.sourceZoneId());
                }
                BreakoutDiagnosisDto matchedDiag = findMatchingZoneDiagnosis(support.value(), stopBreachedPlan);
                if (matchedDiag != null && matchedDiag.diagnosis() != null && matchedDiag.diagnosis() != BreakoutDiagnosis.NONE) {
                    payload.put("diagnosis", matchedDiag.diagnosis().name());
                }
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_CRITICAL,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_STOP_BREACHED,
                        figi, symbol, stockName, payload
                ));
            } else if (invalidatedPlan != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                if (invalidatedPlan.stop() != null && invalidatedPlan.stop().closeThreshold() != null) {
                    payload.put("closeThreshold", invalidatedPlan.stop().closeThreshold().setScale(4, RoundingMode.HALF_UP));
                }
                payload.put("stopSource", "TECHNICAL_INVALIDATION");
                if (invalidatedPlan.sourceZoneId() != null) {
                    payload.put("zoneId", invalidatedPlan.sourceZoneId());
                }
                BreakoutDiagnosisDto matchedDiag = findMatchingZoneDiagnosis(support.value(), invalidatedPlan);
                if (matchedDiag != null && matchedDiag.diagnosis() != null && matchedDiag.diagnosis() != BreakoutDiagnosis.NONE) {
                    payload.put("diagnosis", matchedDiag.diagnosis().name());
                }
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_CRITICAL,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_TECHNICAL_INVALIDATION,
                        figi, symbol, stockName, payload
                ));
            } else if (primaryDiag != null && primaryDiag.diagnosis() == BreakoutDiagnosis.SUSPECTED_SHAKEOUT) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                payload.put("diagnosis", BreakoutDiagnosis.SUSPECTED_SHAKEOUT.name());
                if (primaryDiag.zoneId() != null) payload.put("zoneId", primaryDiag.zoneId());
                if (primaryDiag.supportLower() != null) payload.put("supportLower", primaryDiag.supportLower().setScale(4, RoundingMode.HALF_UP));
                if (primaryDiag.breachDate() != null) payload.put("breachDate", primaryDiag.breachDate().toString());
                if (primaryDiag.barsSinceBreach() != null) payload.put("barsSinceBreach", primaryDiag.barsSinceBreach());
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_WARNING,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_SUSPECTED_SHAKEOUT,
                        figi, symbol, stockName, payload
                ));
            } else if (primaryDiag != null && primaryDiag.diagnosis() == BreakoutDiagnosis.BREACH_OBSERVING) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                payload.put("diagnosis", BreakoutDiagnosis.BREACH_OBSERVING.name());
                if (primaryDiag.zoneId() != null) payload.put("zoneId", primaryDiag.zoneId());
                if (primaryDiag.supportLower() != null) payload.put("supportLower", primaryDiag.supportLower().setScale(4, RoundingMode.HALF_UP));
                if (primaryDiag.breachDate() != null) payload.put("breachDate", primaryDiag.breachDate().toString());
                if (primaryDiag.barsSinceBreach() != null) payload.put("barsSinceBreach", primaryDiag.barsSinceBreach());
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_WARNING,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_BREACH_OBSERVING,
                        figi, symbol, stockName, payload
                ));
            }
        }

        // 5. 基本面惡化警戒（WEAKENING / CAUTION_VALUE_TRAP / 前瞻陷阱；ETF／ETP 跳過）
        if (!isEtf) {
            if ("WEAKENING".equals(resolvedBacking)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("fundamentalBacking", resolvedBacking);
                payload.put("verdict", resolvedVerdict);
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_WARNING,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_FUNDAMENTAL_WEAKENING,
                        figi, symbol, stockName, payload
                ));
            }
            if ("CAUTION_VALUE_TRAP".equals(resolvedStance) || forwardValueTrap) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("stance", resolvedStance);
                payload.put("forwardValueTrap", forwardValueTrap);
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_WARNING,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_VALUE_TRAP,
                        figi, symbol, stockName, payload
                ));
            }

            // 6. 高估分批止盈提醒（OVERVALUED_RISK：close > fairValueHigh * 1.15，限當日最新估值）
            if (valuationFresh && "OVERVALUED_RISK".equals(resolvedVerdict)) {
                boolean inProfit = pre.unrealizedPnlPct() != null && pre.unrealizedPnlPct().signum() > 0;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                if (v.getFairValueHigh() != null) {
                    payload.put("fairValueHigh", v.getFairValueHigh().setScale(4, RoundingMode.HALF_UP));
                }
                payload.put("inProfit", inProfit);
                if (pre.unrealizedPnlPct() != null) {
                    payload.put("unrealizedPnlPct", pre.unrealizedPnlPct());
                }
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_INFO,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_OVERVALUED_PROFIT_TAKING,
                        figi, symbol, stockName, payload
                ));
            }
        }

        // 7. 上方目標達標（收盤價 >= 事前對齊 T2 / T1）或無事前目標但進入強阻力區
        boolean targetAlertEmitted = false;
        if (effectivePriorRef != null && effectivePriorRef.targetsDate() != null
                && (effectivePriorRef.target1() != null || effectivePriorRef.target2() != null)) {
            AlignedPrice alignedT2 = effectivePriorRef.target2() == null ? null
                    : splitAligner.alignPrice(effectivePriorRef.target2(), effectivePriorRef.targetsDate(), targetDate, pre.status(), stockSplits);
            AlignedPrice alignedT1 = effectivePriorRef.target1() == null ? null
                    : splitAligner.alignPrice(effectivePriorRef.target1(), effectivePriorRef.targetsDate(), targetDate, pre.status(), stockSplits);
            if (alignedT2 != null && alignedT2.verified() && alignedT2.rawAdjustedPrice() != null
                    && evalPrice.compareTo(alignedT2.rawAdjustedPrice()) >= 0) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("targetLevel", "T2");
                payload.put("targetPrice", alignedT2.adjustedPrice());
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                payload.put("referenceDate", effectivePriorRef.targetsDate().toString());
                payload.put("sameBarStopAndTargetAmbiguous", actualPriceStopTriggered);
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_INFO,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_TARGET_REACHED,
                        figi, symbol, stockName, payload
                ));
                targetAlertEmitted = true;
            } else if (alignedT1 != null && alignedT1.verified() && alignedT1.rawAdjustedPrice() != null
                    && evalPrice.compareTo(alignedT1.rawAdjustedPrice()) >= 0) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("targetLevel", "T1");
                payload.put("targetPrice", alignedT1.adjustedPrice());
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                payload.put("referenceDate", effectivePriorRef.targetsDate().toString());
                payload.put("sameBarStopAndTargetAmbiguous", actualPriceStopTriggered);
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_INFO,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_TARGET_REACHED,
                        figi, symbol, stockName, payload
                ));
                targetAlertEmitted = true;
            }
        }
        if (!targetAlertEmitted && support.status() == MetricStatus.AVAILABLE && support.value() != null) {
            LevelZoneDto inResistance = support.value().containingZones().stream()
                    .filter(this::isStrongResistance)
                    .findFirst().orElse(null);
            if (inResistance != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("closePrice", evalPrice.setScale(4, RoundingMode.HALF_UP));
                payload.put("resistanceLower", inResistance.lower().setScale(4, RoundingMode.HALF_UP));
                payload.put("resistanceUpper", inResistance.upper().setScale(4, RoundingMode.HALF_UP));
                payload.put("zoneId", inResistance.id());
                alerts.add(new PortfolioAlertDto(
                        InAppNotification.SEVERITY_INFO,
                        InAppNotification.CATEGORY_PORTFOLIO_HOLDING,
                        ALERT_HOLDING_AT_RESISTANCE_ZONE,
                        figi, symbol, stockName, payload
                ));
            }
        }

        // 8. 記錄當日有效參考止損與 T1/T2（優先近端有效計畫，再回退近端週線支撐並疊加第二軌試單緊貼止損），作為隔日收盤體檢之事前基準
        SupportResistanceDto supportValue = support.status() == MetricStatus.AVAILABLE ? support.value() : null;
        HoldingReferenceSelection nextRef = resolveHoldingReference(planList, supportValue, resolvedStarter, evalPrice);
        TradePlanDto matchedPlan = nextRef.matchedPlan();
        BigDecimal nextRefStop = nextRef.protectiveStop();
        String nextRefStopSource = nextRef.stopSource();
        BigDecimal nextT1 = null;
        BigDecimal nextT2 = null;
        if (matchedPlan != null && matchedPlan.targets() != null) {
            if (!matchedPlan.targets().isEmpty()) {
                TargetDto t1 = matchedPlan.targets().get(0);
                if (t1 != null && t1.price() != null && t1.price().compareTo(evalPrice) > 0) {
                    nextT1 = t1.price().setScale(4, RoundingMode.HALF_UP);
                }
            }
            if (matchedPlan.targets().size() >= 2) {
                TargetDto t2 = matchedPlan.targets().get(1);
                if (t2 != null && t2.price() != null && t2.price().compareTo(evalPrice) > 0) {
                    nextT2 = t2.price().setScale(4, RoundingMode.HALF_UP);
                }
            }
        }
        LocalDate nextTargetsDate = (nextT1 != null || nextT2 != null) ? targetDate : null;

        return buildHoldingResult(
                pre, symbol, stockName, currency,
                resolvedVerdict, resolvedBacking, resolvedStance, diagCode,
                nextRefStop, nextRefStopSource, nextT1, nextT2, nextTargetsDate,
                nextEarningsDate, daysToEarnings, bufferLevel, alerts, gaps
        );
    }

    private WatchlistOpportunityDto evaluateWatchlistItem(PortfolioItem watch,
                                                          LocalDate targetDate,
                                                          Instant sessionCutoff,
                                                          Instant obsCutoff,
                                                          LocalDate fiveYearsAgo,
                                                          LocalDate maxAnnualObsDate,
                                                          PreloadedMarketData preloadedMarket,
                                                          LoadedInputs loaded,
                                                          Map<String, BigDecimal> holdingMvUsdByFigi,
                                                          Map<String, BigDecimal> sectorMvUsdByIndustry,
                                                          Set<String> unverifiedHoldingFigis,
                                                          int excludedHoldingsCount) {
        String figi = watch.getFigi();
        if (unverifiedHoldingFigis.contains(figi)) {
            return null;
        }
        Stock stock = loaded.stocksByFigi().get(figi);
        // 自選買點通知僅適用估值支撐與前瞻成長回踩雙軌，嚴格僅放行支援公司估值之證券類型（排除 ETF／ETP 與未知類型）
        if (stock == null || !SecurityTypePolicy.supportsValuation(stock.getStockType())) {
            return null;
        }
        boolean isAdr = SecurityTypePolicy.isAdrType(stock.getStockType());
        AdrShareRatio adrRatio = isAdr && stock.getSymbol() != null
                ? loaded.adrBySymbol().get(stock.getSymbol().trim().toUpperCase(java.util.Locale.ROOT)) : null;
        boolean verifiedAdr = isAdr && adrRatio != null && adrRatio.isVerified()
                && adrRatio.getAdrRatio() != null && adrRatio.getAdrRatio().signum() > 0;
        if (isAdr && !verifiedAdr) {
            return null;
        }
        StockStatus status = loaded.statusByFigi().get(figi);
        StockValuationLatest v = loaded.valuationByFigi().get(figi);
        boolean valuationFresh = v != null && targetDate.equals(v.getValuationDate());
        List<QuoteBarRow> rawQuotes = loaded.quotesByFigi().get(figi);
        if (rawQuotes == null || rawQuotes.isEmpty() || !targetDate.equals(rawQuotes.getLast().quoteDate())) {
            return null;
        }
        List<StockSplit> stockSplits = loaded.splitsByFigi().getOrDefault(figi, List.of());
        AnalysisContext context = new AnalysisContext(figi, sessionCutoff, obsCutoff, AnalysisHorizon.POSITION);
        MetricResult<NormalizedPriceSeriesDto> price = quoteNormalization.normalizeFromLoaded(
                context, fiveYearsAgo, targetDate, stock, status, rawQuotes, stockSplits);
        if (price.status() != MetricStatus.AVAILABLE || price.value() == null || price.value().bars().isEmpty()) {
            return null;
        }
        MetricResult<PriceStructureDto> structure = priceStructures.analyze(context, price.value());
        if (structure.status() != MetricStatus.AVAILABLE || structure.value() == null) {
            return null;
        }

        List<MarketEvent> stockEarnings = loaded.earningsByFigi().getOrDefault(figi, List.of());
        List<MarketEvent> pastEarnings = stockEarnings.stream()
                .filter(ev -> ev.getEventDate() != null && !ev.getEventDate().isAfter(targetDate))
                .toList();
        LocalDate winFrom = targetDate.minusDays(10);
        LocalDate winTo = targetDate.plusDays(20);
        List<MarketEvent> windowEvents = stockEarnings.stream()
                .filter(ev -> ev.getEventDate() != null && !ev.getEventDate().isBefore(winFrom) && !ev.getEventDate().isAfter(winTo))
                .toList();

        MetricResult<MarketContextDto> marketResult = marketContextService.evaluateWithPreloaded(
                context, price.value(), structure.value(), pastEarnings, windowEvents, preloadedMarket);
        MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
        MetricResult<SupportResistanceDto> support = supportResistanceService.analyze(
                context, price.value(), structure.value(), marketValue);
        MetricResult<TradePlanResultDto> tradePlans = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        if (support.status() == MetricStatus.AVAILABLE && support.value() != null) {
            PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
            tradePlans = tradePlanService.build(context, price.value(), structure.value(), support.value(), constraint);
        }

        BigDecimal evalPrice = price.value().bars().getLast().close();
        List<StockFinancialPeriod> stockPeriods = loaded.periodsByFigi().getOrDefault(figi, List.of());
        List<KnownReportPeriod> knownReports = loaded.knownReportsByFigi().getOrDefault(figi, List.of());
        List<StockEarningsForecast> stockForecasts = loaded.forecastsByFigi().getOrDefault(figi, List.of());
        StockFinancialPeriod matchedPeriod = StockValuationService.matchPeriod(stockPeriods, targetDate);
        String splitError = StockValuationService.splitSyncError(status);
        FxResolution fxRes = resolveFxForStock(loaded.fxLookup(), matchedPeriod, stock.getCurrency(), targetDate);
        BigDecimal fxRateToQuote = fxRes != null && fxRes.isUsable() ? fxRes.usdPerUnit() : null;
        String valuationBlockedReason = fxRes != null && !fxRes.isUsable() && fxRes.reason() != null
                ? fxRes.reason().name() : null;
        BigDecimal capAtDay = valuationFresh ? v.getMarketCap() : null;
        BigDecimal capClosePrice = (valuationFresh && v.getClosePrice() != null && v.getClosePrice().signum() > 0)
                ? v.getClosePrice() : evalPrice;
        DayEpsResolution epsResolution = StockValuationService.resolveDayEps(
                stock.getCurrency(), status, matchedPeriod, stockSplits, targetDate, fxRateToQuote,
                isAdr, verifiedAdr, capAtDay, capClosePrice);
        String peReason = valuationFresh && v.getPeReason() != null ? v.getPeReason().name() : epsResolution.peReason();
        boolean financialInstitution = v != null && IndustryValuationProposalService.isFinancialInstitutionModel(v.getModelCode());
        FundamentalSnapshotDto fundamentalsDto = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                stockPeriods, matchedPeriod, stockSplits, targetDate, stock.getCurrency(),
                evalPrice, valuationFresh ? v.getMarketCap() : null, null, epsResolution.epsAtDay(), peReason,
                valuationFresh ? v.getPe() : null, financialInstitution, splitError,
                fxRateToQuote, valuationBlockedReason, verifiedAdr));
        FutuObservationsDto futuObs = buildReadOnlyForwardObservations(
                targetDate, obsCutoff, maxAnnualObsDate, stock.getCurrency(), evalPrice,
                valuationFresh ? v.getMarketCap() : null, epsResolution.epsAtDay(), peReason,
                stockSplits, splitError, stockForecasts, knownReports, stockPeriods);

        String verdict = valuationFresh && v.getVerdict() != null ? v.getVerdict() : "INSUFFICIENT_EVIDENCE";
        boolean currentLoss = StockValuationService.isCurrentTtmLoss(matchedPeriod, status, targetDate, isAdr, verifiedAdr);
        String backing = valuationFresh && v.getFundamentalBacking() != null
                ? v.getFundamentalBacking() : ValuationSummaryCalculator.evaluateFundamentalBacking(fundamentalsDto, currentLoss);
        BigDecimal bandLow = valuationFresh ? v.getFairValueLow() : null;

        RadarEvaluationResult eval = summaryCalculator.evaluateForRadar(
                AnalysisHorizon.POSITION, evalPrice, verdict, bandLow, backing,
                fundamentalsDto, futuObs, structure, support, tradePlans, marketResult);

        // 大盤 BEAR / PANIC 或財報封鎖時不發自選買入通知
        if (eval.marketRegime() == MarketRegime.BEAR || eval.marketRegime() == MarketRegime.PANIC
                || eval.marketRegime() == null || eval.marketRegime() == MarketRegime.UNAVAILABLE) {
            return null;
        }
        boolean macroCautionActive = eval.macroCaution() != null && eval.macroCaution().active();
        StockIndustryHistory ih = loaded.industryByFigi().get(figi);
        String industryCode = ih != null && ih.getIndustry() != null ? ih.getIndustry().getCode() : null;
        String industryName = ih != null && ih.getIndustry() != null ? ih.getIndustry().getIndustry() : industryCode;
        BigDecimal existingHoldingMv = holdingMvUsdByFigi.getOrDefault(figi, BigDecimal.ZERO);
        BigDecimal currentSectorMv = industryCode == null ? BigDecimal.ZERO
                : sectorMvUsdByIndustry.getOrDefault(industryCode, BigDecimal.ZERO);

        // 路徑 1：低估支撐買入區（須同區匹配之最終交易計畫為 ACTIVE，嚴防高於上限／等待觸發／非 USD 誤發買點通知）
        if ("ACCUMULATE_AT_SUPPORT".equals(eval.stance()) && eval.supportZone() != null) {
            List<TradePlanDto> planList = tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                    ? tradePlans.value().plans() : List.of();
            TradePlanDto matchedPlan = findMatchingPlan(planList, eval.supportZone());
            if (matchedPlan == null || matchedPlan.status() != TradePlanStatus.ACTIVE
                    || matchedPlan.entryRange() == null || matchedPlan.stop() == null) {
                return null;
            }
            PositionSizingDto sizing = positionSizingCalculator.calculate(new SizingInputs(
                    stock.getCurrency(),
                    industryCode,
                    matchedPlan.entryRange().upper(),
                    evalPrice,
                    matchedPlan.stop().protectivePrice(),
                    true,
                    false,
                    eval.marketRegime(),
                    macroCautionActive,
                    loaded.config(),
                    existingHoldingMv,
                    currentSectorMv,
                    excludedHoldingsCount
            ));
            if (!PositionSizingDto.STATUS_AVAILABLE.equals(sizing.status())
                    || sizing.suggestedShares() == null || sizing.suggestedShares() <= 0L) {
                return null;
            }
            BigDecimal firstTarget = matchedPlan.targets().isEmpty() ? null
                    : matchedPlan.targets().getFirst().price().setScale(4, RoundingMode.HALF_UP);
            return new WatchlistOpportunityDto(
                    figi, stock.getSymbol(), stock.getLongName(), stock.getCurrency(),
                    industryCode, industryName,
                    ALERT_WATCHLIST_ACCUMULATE_SUPPORT,
                    eval.verdict(), eval.fundamentalBacking(), eval.stance(),
                    evalPrice.setScale(4, RoundingMode.HALF_UP),
                    matchedPlan.entryRange().lower().setScale(4, RoundingMode.HALF_UP),
                    matchedPlan.entryRange().upper().setScale(4, RoundingMode.HALF_UP),
                    matchedPlan.stop().protectivePrice().setScale(4, RoundingMode.HALF_UP),
                    firstTarget,
                    sizing
            );
        }

        // 路徑 2：強勢成長股趨勢回踩試單（嚴格對齊 Batch 02 eligible starter）
        TrendPullbackStarterDto starter = eval.trendPullbackStarter();
        if ("TREND_PULLBACK_STARTER".equals(eval.stance()) && starter != null && starter.eligible()
                && starter.anchorPrice() != null && starter.suggestedStop() != null) {
            PositionSizingDto sizing = positionSizingCalculator.calculate(new SizingInputs(
                    stock.getCurrency(),
                    industryCode,
                    starter.anchorPrice(),
                    evalPrice,
                    starter.suggestedStop(),
                    true,
                    true,
                    eval.marketRegime(),
                    macroCautionActive,
                    loaded.config(),
                    existingHoldingMv,
                    currentSectorMv,
                    excludedHoldingsCount
            ));
            if (!PositionSizingDto.STATUS_AVAILABLE.equals(sizing.status())
                    || sizing.suggestedShares() == null || sizing.suggestedShares() <= 0L) {
                return null;
            }
            return new WatchlistOpportunityDto(
                    figi, stock.getSymbol(), stock.getLongName(), stock.getCurrency(),
                    industryCode, industryName,
                    ALERT_WATCHLIST_TREND_PULLBACK_STARTER,
                    eval.verdict(), eval.fundamentalBacking(), eval.stance(),
                    evalPrice.setScale(4, RoundingMode.HALF_UP),
                    starter.anchorPrice().setScale(4, RoundingMode.HALF_UP),
                    starter.anchorPrice().setScale(4, RoundingMode.HALF_UP),
                    starter.suggestedStop().setScale(4, RoundingMode.HALF_UP),
                    null,
                    sizing
            );
        }
        return null;
    }

    private HoldingHealthItemDto buildHoldingResult(EvaluatedHoldingPrePass pre,
                                                    String symbol,
                                                    String stockName,
                                                    String currency,
                                                    String verdict,
                                                    String backing,
                                                    String stance,
                                                    String breakoutDiag,
                                                    BigDecimal refStop,
                                                    String refStopSource,
                                                    BigDecimal refT1,
                                                    BigDecimal refT2,
                                                    LocalDate refTargetsDate,
                                                    LocalDate nextEarningsDate,
                                                    Integer daysToEarnings,
                                                    String bufferLevel,
                                                    List<PortfolioAlertDto> alerts,
                                                    List<String> gaps) {
        String highest = "NONE";
        for (PortfolioAlertDto alert : alerts) {
            if (severityRank(alert.severity()) < severityRank(highest)) {
                highest = alert.severity();
            }
        }
        AlignedPosition aligned = pre.alignedPosition();
        return new HoldingHealthItemDto(
                pre.item().getFigi(),
                pre.item().getPositionId(),
                symbol,
                stockName,
                currency,
                pre.industryCode(),
                pre.industryName(),
                pre.evalDate(),
                pre.closePrice() == null ? null : pre.closePrice().setScale(4, RoundingMode.HALF_UP),
                aligned != null && aligned.verified() ? aligned.adjustedShares() : null,
                aligned != null && aligned.verified() ? aligned.adjustedAvgCost() : null,
                pre.marketValue(),
                pre.unrealizedPnlAmount(),
                pre.unrealizedPnlPct(),
                aligned != null ? aligned.status() : "QUOTE_MISSING",
                verdict,
                backing,
                stance,
                breakoutDiag,
                refStop,
                refStopSource,
                refT1,
                refT2,
                refTargetsDate,
                nextEarningsDate,
                daysToEarnings,
                bufferLevel,
                highest,
                alerts,
                gaps
        );
    }

    private Map<String, PriorHoldingReference> extractPriorReferences(DailyClosingBriefing priorBriefing, Instant obsCutoff) {
        if (priorBriefing == null || priorBriefing.getPortfolioHealth() == null
                || priorBriefing.getPortfolioHealth().holdings() == null) {
            return Map.of();
        }
        if (priorBriefing.getGeneratedAt() != null && obsCutoff != null && priorBriefing.getGeneratedAt().isAfter(obsCutoff)) {
            return Map.of();
        }
        Map<String, PriorHoldingReference> map = new HashMap<>();
        LocalDate priorDate = priorBriefing.getTradeDate();
        for (HoldingHealthItemDto h : priorBriefing.getPortfolioHealth().holdings()) {
            if (h == null || h.figi() == null) continue;
            LocalDate stopDate = h.evalDate() != null ? h.evalDate() : priorDate;
            LocalDate targetsDate = h.referenceTargetsDate() != null ? h.referenceTargetsDate() : stopDate;
            map.put(h.figi(), new PriorHoldingReference(
                    h.positionId(),
                    stopDate,
                    h.referenceProtectiveStop(),
                    h.referenceStopSource(),
                    h.referenceTarget1(),
                    h.referenceTarget2(),
                    targetsDate
            ));
        }
        return map;
    }

    private MarketEvent findUpcomingOrTodayEarnings(List<MarketEvent> earnings, LocalDate tradeDate, Instant obsCutoff) {
        MarketEvent best = null;
        for (MarketEvent ev : earnings) {
            if (ev == null || ev.getEventDate() == null || ev.getEventDate().isBefore(tradeDate)) {
                continue;
            }
            if (ev.getEventDate().equals(tradeDate) && hasSameDayEarningsPassed(ev, tradeDate, obsCutoff)) {
                continue;
            }
            if (best == null || ev.getEventDate().isBefore(best.getEventDate())) {
                best = ev;
            }
        }
        return best;
    }

    private boolean hasSameDayEarningsPassed(MarketEvent ev, LocalDate tradeDate, Instant obsCutoff) {
        if (obsCutoff == null) {
            return false;
        }
        String precision = ev.getTimePrecision() == null ? "" : ev.getTimePrecision().trim().toUpperCase(java.util.Locale.ROOT);
        if ("EXACT".equals(precision) && ev.getOccurrenceAt() != null) {
            return !ev.getOccurrenceAt().isAfter(obsCutoff);
        }
        String timing = resolveEarningsTimingCode(ev);
        if ("BEFORE_OPEN".equals(timing)) {
            Instant sessionOpen = calendar.getSessionOpenAt(tradeDate);
            Instant cutoffRef = sessionOpen != null ? sessionOpen : calendar.getSessionCloseAt(tradeDate);
            return cutoffRef != null && !obsCutoff.isBefore(cutoffRef);
        }
        return false;
    }

    /** 優先讀取 MarketEventSyncService 寫入之 timePrecision（BEFORE_OPEN / AFTER_CLOSE / EXACT），再回退 eventSubtype。 */
    private String resolveEarningsTimingCode(MarketEvent ev) {
        if (ev == null) {
            return "DATE_ONLY";
        }
        String precision = ev.getTimePrecision() == null ? "" : ev.getTimePrecision().trim().toUpperCase(java.util.Locale.ROOT);
        if ("BEFORE_OPEN".equals(precision) || "BMO".equals(precision)) {
            return "BEFORE_OPEN";
        }
        if ("AFTER_CLOSE".equals(precision) || "AMC".equals(precision)) {
            return "AFTER_CLOSE";
        }
        if ("EXACT".equals(precision) && ev.getOccurrenceAt() != null && ev.getEventDate() != null) {
            Instant openAt = calendar.getSessionOpenAt(ev.getEventDate());
            Instant closeAt = calendar.getSessionCloseAt(ev.getEventDate());
            if (openAt != null && ev.getOccurrenceAt().isBefore(openAt)) {
                return "BEFORE_OPEN";
            }
            if (closeAt != null && !ev.getOccurrenceAt().isBefore(closeAt)) {
                return "AFTER_CLOSE";
            }
            return "DATE_ONLY";
        }
        String subtype = ev.getEventSubtype() == null ? "" : ev.getEventSubtype().toUpperCase(java.util.Locale.ROOT);
        if (subtype.contains("BMO") || subtype.contains("BEFORE") || subtype.contains("PRE")) {
            return "BEFORE_OPEN";
        }
        if (subtype.contains("AMC") || subtype.contains("AFTER") || subtype.contains("POST")) {
            return "AFTER_CLOSE";
        }
        return "DATE_ONLY";
    }

    private String classifyEarningsBuffer(BigDecimal unrealizedPnlPct) {
        if (unrealizedPnlPct == null) {
            return "UNKNOWN";
        }
        if (unrealizedPnlPct.signum() < 0) {
            return "NEGATIVE";
        }
        if (unrealizedPnlPct.compareTo(BUFFER_ADEQUATE_PCT) >= 0) {
            return "ADEQUATE";
        }
        return "THIN";
    }

    private TradePlanDto findPlanByReason(List<TradePlanDto> plans, String reasonCode) {
        if (plans == null || reasonCode == null) {
            return null;
        }
        for (TradePlanDto p : plans) {
            if (p == null || p.reasons() == null) continue;
            for (AnalysisReasonDto r : p.reasons()) {
                if (r != null && reasonCode.equals(r.code())) {
                    return p;
                }
            }
        }
        return null;
    }

    private BreakoutDiagnosisDto findMatchingZoneDiagnosis(SupportResistanceDto sr, TradePlanDto plan) {
        if (sr == null || plan == null || plan.sourceZoneId() == null) {
            return null;
        }
        if (sr.zoneDiagnoses() != null) {
            for (BreakoutDiagnosisDto diag : sr.zoneDiagnoses()) {
                if (diag != null && plan.sourceZoneId().equals(diag.zoneId())
                        && (plan.sourceBoundsVersion() <= 0 || plan.sourceBoundsVersion() == diag.zoneBoundsVersion())) {
                    return diag;
                }
            }
        }
        BreakoutDiagnosisDto primary = sr.breakoutDiagnosis();
        if (primary != null && plan.sourceZoneId().equals(primary.zoneId())
                && (plan.sourceBoundsVersion() <= 0 || plan.sourceBoundsVersion() == primary.zoneBoundsVersion())) {
            return primary;
        }
        return null;
    }

    private boolean isStrongResistance(LevelZoneDto z) {
        if (z == null || z.role() != ZoneRole.RESISTANCE) {
            return false;
        }
        if (z.majorLevel() || z.strengthLabel() == ZoneStrengthLabel.HIGH) {
            return true;
        }
        return z.effectiveStrength() != null && z.effectiveStrength().compareTo(new BigDecimal("60")) >= 0;
    }

    private TradePlanDto findMatchingPlan(List<TradePlanDto> plans, LevelZoneDto zone) {
        if (plans == null || zone == null || zone.id() == null) {
            return null;
        }
        for (TradePlanDto plan : plans) {
            if (plan != null && zone.id().equals(plan.sourceZoneId())
                    && (plan.sourceBoundsVersion() <= 0 || plan.sourceBoundsVersion() == zone.boundsVersion())) {
                return plan;
            }
        }
        return null;
    }

    private record HoldingReferenceSelection(TradePlanDto matchedPlan, BigDecimal protectiveStop, String stopSource) {}

    private HoldingReferenceSelection resolveHoldingReference(List<TradePlanDto> planList,
                                                              SupportResistanceDto supportValue,
                                                              TrendPullbackStarterDto starter,
                                                              BigDecimal evalPrice) {
        TradePlanDto matchedPlan = null;
        BigDecimal refStop = null;
        String stopSource = null;

        if (planList != null && evalPrice != null) {
            for (TradePlanDto plan : planList) {
                if (isEligibleHoldingReferencePlan(plan, evalPrice)) {
                    matchedPlan = plan;
                    refStop = plan.stop().protectivePrice().setScale(4, RoundingMode.HALF_UP);
                    stopSource = "PLAN_PROTECTIVE_STOP";
                    break;
                }
            }
        }

        if (refStop == null && supportValue != null && evalPrice != null) {
            List<LevelZoneDto> orderedZones = new ArrayList<>();
            if (supportValue.containingZones() != null) {
                for (LevelZoneDto z : supportValue.containingZones()) {
                    if (z != null && z.role() == ZoneRole.SUPPORT) {
                        orderedZones.add(z);
                    }
                }
            }
            if (supportValue.supports() != null) {
                for (LevelZoneDto z : supportValue.supports()) {
                    if (z != null && z.role() == ZoneRole.SUPPORT) {
                        orderedZones.add(z);
                    }
                }
            }
            Set<String> seenZoneIds = new LinkedHashSet<>();
            for (LevelZoneDto zone : orderedZones) {
                if (zone.id() != null && !seenZoneIds.add(zone.id())) {
                    continue;
                }
                if (zone.timeframe() != AnalysisTimeframe.WEEKLY || zone.state() != ZoneState.ACTIVE
                        || zone.lower() == null || zone.anchorAtr() == null || zone.anchorAtr().signum() <= 0) {
                    continue;
                }
                if (isZoneDisqualifiedForReferenceStop(zone, planList, supportValue)) {
                    continue;
                }
                BigDecimal fallback = zone.lower().subtract(zone.anchorAtr().multiply(new BigDecimal("0.50"), MC), MC);
                if (fallback.signum() > 0 && fallback.compareTo(evalPrice) < 0) {
                    refStop = fallback.setScale(4, RoundingMode.HALF_UP);
                    stopSource = "ZONE_ANCHOR_ATR_STOP";
                    break;
                }
            }
        }

        if (starter != null && starter.eligible() && starter.suggestedStop() != null
                && starter.suggestedStop().signum() > 0 && evalPrice != null
                && starter.suggestedStop().compareTo(evalPrice) < 0) {
            BigDecimal starterStop = starter.suggestedStop().setScale(4, RoundingMode.HALF_UP);
            if (refStop == null || starterStop.compareTo(refStop) > 0) {
                refStop = starterStop;
                stopSource = "STARTER_ANCHOR_STOP";
            }
        }

        return new HoldingReferenceSelection(matchedPlan, refStop, refStop != null ? stopSource : null);
    }

    private boolean isEligibleHoldingReferencePlan(TradePlanDto plan, BigDecimal evalPrice) {
        if (plan == null || evalPrice == null) {
            return false;
        }
        if (plan.status() == TradePlanStatus.UNAVAILABLE || plan.status() == TradePlanStatus.INVALIDATED) {
            return false;
        }
        if (hasAnyPlanReason(plan, "PROTECTIVE_STOP_BREACHED", "TECHNICAL_INVALIDATION_CONFIRMED",
                "HIGH_RISK_BREAKDOWN_DETECTED", "RECLAIM_REQUIRED")) {
            return false;
        }
        return plan.stop() != null && plan.stop().protectivePrice() != null
                && plan.stop().protectivePrice().signum() > 0
                && plan.stop().protectivePrice().compareTo(evalPrice) < 0;
    }

    private boolean isZoneDisqualifiedForReferenceStop(LevelZoneDto zone,
                                                       List<TradePlanDto> planList,
                                                       SupportResistanceDto supportValue) {
        if (planList != null && zone.id() != null) {
            for (TradePlanDto plan : planList) {
                if (plan != null && zone.id().equals(plan.sourceZoneId())
                        && (plan.sourceBoundsVersion() <= 0 || plan.sourceBoundsVersion() == zone.boundsVersion())) {
                    if (plan.status() == TradePlanStatus.INVALIDATED || plan.status() == TradePlanStatus.UNAVAILABLE
                            || hasAnyPlanReason(plan, "PROTECTIVE_STOP_BREACHED", "TECHNICAL_INVALIDATION_CONFIRMED",
                                    "HIGH_RISK_BREAKDOWN_DETECTED", "RECLAIM_REQUIRED")) {
                        return true;
                    }
                }
            }
        }
        if (supportValue != null) {
            if (supportValue.zoneDiagnoses() != null) {
                for (BreakoutDiagnosisDto diag : supportValue.zoneDiagnoses()) {
                    if (diag != null && diag.matchesZone(zone) && diag.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN) {
                        return true;
                    }
                }
            }
            BreakoutDiagnosisDto primary = supportValue.breakoutDiagnosis();
            if (primary != null && primary.matchesZone(zone) && primary.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyPlanReason(TradePlanDto plan, String... reasonCodes) {
        if (plan == null || plan.reasons() == null || reasonCodes == null) {
            return false;
        }
        for (AnalysisReasonDto r : plan.reasons()) {
            if (r == null || r.code() == null) {
                continue;
            }
            for (String target : reasonCodes) {
                if (target.equals(r.code())) {
                    return true;
                }
            }
        }
        return false;
    }

    private FutuObservationsDto buildReadOnlyForwardObservations(LocalDate evalDate,
                                                                 Instant obsCutoff,
                                                                 LocalDate maxObsDate,
                                                                 String currency,
                                                                 BigDecimal closePrice,
                                                                 BigDecimal marketCap,
                                                                 BigDecimal epsAtDay,
                                                                 String peReason,
                                                                 List<StockSplit> stockSplits,
                                                                 String splitError,
                                                                 List<StockEarningsForecast> allForecasts,
                                                                 List<KnownReportPeriod> knownReports,
                                                                 List<StockFinancialPeriod> storedPeriods) {
        if (allForecasts == null || allForecasts.isEmpty()) {
            return null;
        }
        Map<String, StockEarningsForecast> latestByKey = new LinkedHashMap<>();
        for (StockEarningsForecast f : allForecasts) {
            if (f.getObservationDate() == null || f.getObservationDate().isAfter(maxObsDate)) continue;
            String key = f.getSource() + "|" + f.getFiscalPeriod() + "|" + f.getPeriodType();
            StockEarningsForecast cur = latestByKey.get(key);
            if (cur == null || f.getObservationDate().isAfter(cur.getObservationDate())
                    || (f.getObservationDate().equals(cur.getObservationDate())
                    && f.getObservedAt() != null && (cur.getObservedAt() == null || f.getObservedAt().isAfter(cur.getObservedAt())))) {
                latestByKey.put(key, f);
            }
        }
        List<StockEarningsForecast> latestList = new ArrayList<>(latestByKey.values());
        latestList.sort(Comparator.comparing(StockEarningsForecast::getPeriodEnd, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StockEarningsForecast::getFiscalPeriod, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StockEarningsForecast::getPeriodType, Comparator.nullsLast(String::compareTo))
                .thenComparing(StockEarningsForecast::getSource, Comparator.nullsLast(String::compareTo)));

        ForwardValuationCalculator.Inputs calcInputs = new ForwardValuationCalculator.Inputs(
                evalDate, obsCutoff, currency, closePrice, marketCap, null,
                epsAtDay, peReason, stockSplits, splitError, latestList, knownReports, storedPeriods);
        SelectedRevisionTarget revTarget = forwardCalculator.selectRevisionTarget(calcInputs);
        List<StockEarningsForecast> revHistory = List.of();
        if (revTarget != null && revTarget.latest().getObservationDate() != null) {
            LocalDate anchor = revTarget.latest().getObservationDate();
            LocalDate from = anchor.minusDays(ForwardValuationCalculator.REV_90D_MAX_DAYS + 5L);
            revHistory = allForecasts.stream()
                    .filter(f -> Objects.equals(f.getSource(), revTarget.source())
                            && Objects.equals(f.getFiscalPeriod(), revTarget.fiscalPeriod())
                            && Objects.equals(f.getPeriodType(), revTarget.periodType())
                            && f.getObservationDate() != null
                            && !f.getObservationDate().isBefore(from)
                            && !f.getObservationDate().isAfter(anchor))
                    .toList();
        }
        ForwardMetrics forwardMetrics = forwardCalculator.calculate(calcInputs, revHistory);
        return new FutuObservationsDto(null, List.of(), forwardMetrics);
    }

    private FxRateLookup preloadFxLookup(Map<String, List<StockFinancialPeriod>> periodsByFigi, LocalDate effectiveDate) {
        if (fxRateService == null || periodsByFigi == null || periodsByFigi.isEmpty() || effectiveDate == null) {
            return FxRateLookup.EMPTY;
        }
        Set<String> currencies = new LinkedHashSet<>();
        for (List<StockFinancialPeriod> list : periodsByFigi.values()) {
            for (StockFinancialPeriod p : list) {
                if (p != null && p.getReportingCurrency() != null && !p.getReportingCurrency().isBlank()
                        && !"USD".equalsIgnoreCase(p.getReportingCurrency().trim())) {
                    currencies.add(p.getReportingCurrency().trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        if (currencies.isEmpty()) {
            return FxRateLookup.EMPTY;
        }
        return fxRateService.preload(currencies, effectiveDate, effectiveDate);
    }

    private Map<String, AdrShareRatio> preloadAdrRatios(Map<String, Stock> stocksByFigi) {
        if (adrShareRatios == null || stocksByFigi == null || stocksByFigi.isEmpty()) {
            return Map.of();
        }
        List<String> adrSymbols = new ArrayList<>();
        for (Stock s : stocksByFigi.values()) {
            if (s != null && SecurityTypePolicy.isAdrType(s.getStockType())
                    && s.getSymbol() != null && !s.getSymbol().isBlank()) {
                adrSymbols.add(s.getSymbol().trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (adrSymbols.isEmpty()) {
            return Map.of();
        }
        Map<String, AdrShareRatio> out = new HashMap<>();
        for (AdrShareRatio ratio : adrShareRatios.findBySymbolIn(adrSymbols)) {
            if (ratio != null && ratio.getSymbol() != null) {
                out.put(ratio.getSymbol().trim().toUpperCase(java.util.Locale.ROOT), ratio);
            }
        }
        return out;
    }

    private FxResolution resolveFxForStock(FxRateLookup fxLookup, StockFinancialPeriod matchedPeriod,
                                           String quoteCurrency, LocalDate targetDate) {
        if (matchedPeriod == null || matchedPeriod.getReportingCurrency() == null || quoteCurrency == null) {
            return null;
        }
        if (matchedPeriod.getReportingCurrency().trim().equalsIgnoreCase(quoteCurrency.trim())) {
            return FxResolution.identity(quoteCurrency.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (fxLookup == null) {
            return null;
        }
        return fxLookup.resolve(matchedPeriod.getReportingCurrency(), quoteCurrency, targetDate);
    }

    private int severityRank(String severity) {
        if (InAppNotification.SEVERITY_CRITICAL.equals(severity)) return 0;
        if (InAppNotification.SEVERITY_WARNING.equals(severity)) return 1;
        if (InAppNotification.SEVERITY_INFO.equals(severity)) return 2;
        return 3;
    }

    private static final class SectorExposureAccumulator {
        final String industryCode;
        final String industryName;
        int holdingCount;
        BigDecimal marketValueUsd = BigDecimal.ZERO;

        SectorExposureAccumulator(String industryCode, String industryName) {
            this.industryCode = industryCode;
            this.industryName = industryName;
        }
    }
}
