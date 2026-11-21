package com.ykm.stock.analytics.service.report;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosis;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto.DcfBranchDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.MacroCautionDto;
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
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RiskRewardDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetAction;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.AppliedFiltersDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingOptionsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.MarketBackgroundDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.ScopeSummaryDto;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.FigiKnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.KnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository.SectorCandidateMeta;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
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
import com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.analysis.calculator.ExecutionCostCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator.SizingInputs;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.RadarEvaluationResult;
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.FxRateService.FxRateLookup;
import com.ykm.stock.analytics.service.market.FxRateService.FxResolution;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.PortfolioExposureSnapshot;
import com.ykm.stock.analytics.service.valuation.DcfValuationCalculator;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator.SelectedRevisionTarget;
import com.ykm.stock.analytics.service.valuation.FundamentalSnapshotCalculator;
import com.ykm.stock.analytics.service.valuation.IndustryValuationProposalService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.DayEpsResolution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 08_Enhancement：產業與自選股「建議買入排行榜」核心服務。
 * 嚴格護欄：
 * 1. 0 AI Token、0 外部 HTTP 請求（嚴禁注入或呼叫 AnnualEarningsForecastSyncService 及任何外部行情／AI 服務）。
 * 2. 採 REPEATABLE_READ 唯讀一致性快照＋IN (...) 批量預載（上限 300 檔），避免 N+1 查詢與記憶體溢位。
 * 3. 四梯隊防價值陷阱排序（同區破位判定、ACTIVE 可執行門檻、ETF 雙軌評分、1% 風控倉位試算）與短生命週期不可變報表快照，確保畫面與下載 100% 一致。
 */
@Service
@Slf4j
public class BuyRankingReportService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final ZoneId HK_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final DateTimeFormatter HK_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'HKT'");
    private static final int BATCH_SIZE = 150;
    private static final int PARALLEL_EVAL_THRESHOLD = 8;
    private static final int MAX_SNAPSHOT_CACHE_SIZE = 32;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(15);
    private static final long EARNINGS_TIER3_CALENDAR_DAYS = 14L;
    private static final Pattern SYMBOL_SPLIT_PATTERN = Pattern.compile("[\\s,;]+");

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_SUPPORT_NEAR_FACTOR = new BigDecimal("1.03");
    private static final BigDecimal STARTER_LOW_FACTOR = new BigDecimal("0.99");
    private static final BigDecimal STARTER_HIGH_FACTOR = new BigDecimal("1.03");
    private static final BigDecimal HALF_ATR = new BigDecimal("0.50");
    private static final BigDecimal TARGET_FRONT_RUN_ATR = new BigDecimal("0.10");
    private static final BigDecimal MAX_RR_NORM = new BigDecimal("5.0");
    private static final BigDecimal MAX_DISCOUNT_NORM = new BigDecimal("0.40");
    private static final BigDecimal PEG_MIN_NORM = new BigDecimal("0.50");
    private static final BigDecimal PEG_MAX_NORM = new BigDecimal("1.50");
    private static final BigDecimal PE_MIN_NORM = new BigDecimal("10.0");
    private static final BigDecimal PE_MAX_NORM = new BigDecimal("35.0");
    private static final BigDecimal CMF_NORM_BOUND = new BigDecimal("0.25");
    private static final BigDecimal WEIGHT_50 = new BigDecimal("0.50");
    private static final BigDecimal WEIGHT_30 = new BigDecimal("0.30");
    private static final BigDecimal WEIGHT_20 = new BigDecimal("0.20");
    private static final BigDecimal STARTER_DIST_NEG_BOUND = new BigDecimal("0.01");
    private static final BigDecimal STARTER_DIST_POS_BOUND = new BigDecimal("0.03");

    private static final List<String> ALL_SCOPE_MODES = List.of(
            BuyRankingDtos.SCOPE_SECTOR, BuyRankingDtos.SCOPE_PORTFOLIO, BuyRankingDtos.SCOPE_CUSTOM);
    private static final List<String> ALL_PORTFOLIO_TYPES = List.of(
            PortfolioItem.TYPE_WATCHLIST, PortfolioItem.TYPE_HOLDING);
    private static final List<String> ALL_ASSET_TYPES = List.of(
            BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR, BuyRankingDtos.ASSET_ETF, BuyRankingDtos.ASSET_REIT);
    private static final List<String> DEFAULT_ASSET_TYPES = List.of(
            BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR);
    private static final List<String> ALL_TIERS = List.of(
            BuyRankingDtos.TIER_1, BuyRankingDtos.TIER_2, BuyRankingDtos.TIER_3, BuyRankingDtos.TIER_4);
    private static final List<String> ALL_FUNDAMENTAL_GRADES = List.of(
            BuyRankingDtos.GRADE_ALL, BuyRankingDtos.GRADE_MIXED, BuyRankingDtos.GRADE_STRONG);
    private static final List<String> ALL_SORT_OPTIONS = List.of(
            BuyRankingDtos.SORT_TIER_COMPOSITE,
            BuyRankingDtos.SORT_NET_RR_DESC,
            BuyRankingDtos.SORT_DISCOUNT_DESC,
            BuyRankingDtos.SORT_DISTANCE_TO_BUY_ZONE_ASC,
            BuyRankingDtos.SORT_ROIC_DESC,
            BuyRankingDtos.SORT_SYMBOL_ASC);
    private static final List<String> ALL_HORIZONS = List.of(
            AnalysisHorizon.POSITION.name(), AnalysisHorizon.SWING.name());

    private static final Set<String> UNVERIFIED_VALUATION_REASONS = Set.of(
            ValuationReason.ADR_RATIO_UNVERIFIED.name(),
            ValuationReason.ADR_SHARES_BASIS_UNVERIFIED.name(),
            ValuationReason.FX_RATE_UNAVAILABLE.name(),
            ValuationReason.FX_RATE_STALE.name(),
            ValuationReason.REPORTING_CURRENCY_MISMATCH.name(),
            ValuationReason.SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED.name()
    );

    private final StockRepository stockRepository;
    private final StockStatusRepository stockStatusRepository;
    private final StockValuationLatestRepository latestValuations;
    private final StockIndustryRepository industryRepository;
    private final StockIndustryHistoryRepository industryHistoryRepository;
    private final PortfolioItemRepository portfolioItemRepository;
    private final SectorMoneyFlowDailyRepository sectorMoneyFlowRepository;
    private final StockQuoteRepository quoteRepository;
    private final StockSplitRepository splitRepository;
    private final MarketEventRepository marketEventRepository;
    private final StockEarningsForecastRepository forecastRepository;
    private final StockFinancialPeriodRepository financialPeriodRepository;
    private final FinancialReportRepository financialReportRepository;
    private final AdrShareRatioRepository adrShareRatioRepository;

    private final MarketContextService marketContextService;
    private final PortfolioManagementService portfolioManagementService;
    private final QuoteNormalizationService quoteNormalizationService;
    private final PriceStructureService priceStructureService;
    private final SupportResistanceService supportResistanceService;
    private final TradePlanService tradePlanService;
    private final PositionSizingCalculator positionSizingCalculator;
    private final FxRateService fxRateService;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;

    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();
    private final FundamentalSnapshotCalculator fundamentalCalculator = new FundamentalSnapshotCalculator();
    private final ForwardValuationCalculator forwardCalculator = new ForwardValuationCalculator();
    private final DcfValuationCalculator dcfCalculator = new DcfValuationCalculator();
    private final ExecutionCostCalculator costCalculator = new ExecutionCostCalculator();

    private final Map<String, CachedReportSnapshot> snapshotCache = new LinkedHashMap<>(MAX_SNAPSHOT_CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedReportSnapshot> eldest) {
            return size() > MAX_SNAPSHOT_CACHE_SIZE;
        }
    };

    @Autowired
    public BuyRankingReportService(
            StockRepository stockRepository,
            StockStatusRepository stockStatusRepository,
            StockValuationLatestRepository latestValuations,
            StockIndustryRepository industryRepository,
            StockIndustryHistoryRepository industryHistoryRepository,
            PortfolioItemRepository portfolioItemRepository,
            SectorMoneyFlowDailyRepository sectorMoneyFlowRepository,
            StockQuoteRepository quoteRepository,
            StockSplitRepository splitRepository,
            MarketEventRepository marketEventRepository,
            StockEarningsForecastRepository forecastRepository,
            StockFinancialPeriodRepository financialPeriodRepository,
            FinancialReportRepository financialReportRepository,
            AdrShareRatioRepository adrShareRatioRepository,
            MarketContextService marketContextService,
            PortfolioManagementService portfolioManagementService,
            QuoteNormalizationService quoteNormalizationService,
            PriceStructureService priceStructureService,
            SupportResistanceService supportResistanceService,
            TradePlanService tradePlanService,
            PositionSizingCalculator positionSizingCalculator,
            FxRateService fxRateService,
            MarketStatusService calendar,
            DateTimeService dateTimeService,
            TransactionTemplate transactions) {
        this.stockRepository = stockRepository;
        this.stockStatusRepository = stockStatusRepository;
        this.latestValuations = latestValuations;
        this.industryRepository = industryRepository;
        this.industryHistoryRepository = industryHistoryRepository;
        this.portfolioItemRepository = portfolioItemRepository;
        this.sectorMoneyFlowRepository = sectorMoneyFlowRepository;
        this.quoteRepository = quoteRepository;
        this.splitRepository = splitRepository;
        this.marketEventRepository = marketEventRepository;
        this.forecastRepository = forecastRepository;
        this.financialPeriodRepository = financialPeriodRepository;
        this.financialReportRepository = financialReportRepository;
        this.adrShareRatioRepository = adrShareRatioRepository;
        this.marketContextService = marketContextService;
        this.portfolioManagementService = portfolioManagementService;
        this.quoteNormalizationService = quoteNormalizationService;
        this.priceStructureService = priceStructureService;
        this.supportResistanceService = supportResistanceService;
        this.tradePlanService = tradePlanService;
        this.positionSizingCalculator = positionSizingCalculator;
        this.fxRateService = fxRateService;
        this.calendar = calendar;
        this.dateTimeService = dateTimeService;
        this.transactions = transactions;
    }

    /** 不可變報表計算結果（含完整篩選排序後列表，供畫面分頁回應與 Excel / CSV 匯出共用同一份數據）。 */
    public record CachedReportSnapshot(
            String reportId,
            Instant createdAt,
            LocalDate asOfDate,
            String generatedAtHk,
            Instant observationCutoff,
            String resolvedLocale,
            MarketBackgroundDto marketContext,
            ScopeSummaryDto scopeSummary,
            Map<String, Integer> tierCounts,
            AppliedFiltersDto appliedFilters,
            List<BuyRankingItemDto> fullSortedItems
    ) {}

    private record ValidatedParams(
            String scopeMode,
            List<String> rawIndustryTokens,
            List<String> portfolioTypes,
            List<String> customSymbols,
            Set<String> assetTypes,
            boolean excludeReits,
            Set<String> tiers,
            String minFundamentalGrade,
            BigDecimal minNetRiskReward,
            Integer excludeEarningsWithinDays,
            String sortBy,
            AnalysisHorizon horizon,
            String resolvedLocale,
            int page,
            int pageSize
    ) {}

    private record CandidateEntry(Stock stock, String assetType) {}

    private record CandidateResolution(
            List<CandidateEntry> candidates,
            List<String> industryCodes,
            List<String> industryNames,
            String scopeLabel,
            int matchedTotalCount,
            boolean truncated,
            List<String> unmatchedSymbols,
            List<String> untrackedSymbols,
            List<String> excludedByAssetTypeSymbols
    ) {}

    private record ConsistentSnapshotData(
            LocalDate asOfDate,
            LocalDate lastClosedTradingDay,
            boolean staleDateFallback,
            Instant sessionCutoff,
            LocalDate fiveYearsAgo,
            PreloadedMarketData preloadedMarket,
            PortfolioExposureSnapshot exposure,
            Map<String, StockIndustry> industriesByCode,
            LocalDate sectorFlowDate,
            Map<String, SectorMoneyFlowDaily> sectorFlowMap,
            CandidateResolution resolution,
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

    private record TierClassification(
            String tier,
            BigDecimal tierScore,
            String reasonCode,
            Map<String, Object> reasonParams
    ) {}

    private record BuyZoneExtraction(
            BuyZoneDto buyZone,
            String source,
            BigDecimal distancePct,
            boolean nearWithin3Pct,
            BigDecimal sizingEntryPrice,
            BigDecimal stopLoss,
            BigDecimal firstTarget,
            BigDecimal evalNetRr,
            BigDecimal planNetRr,
            TradePlanDto matchedPlan
    ) {}

    /** 提供前端渲染控制面板所需之權威選項字典，不於前端寫死枚舉。 */
    public BuyRankingOptionsDto getOptions() {
        return new BuyRankingOptionsDto(
                ALL_SCOPE_MODES,
                ALL_PORTFOLIO_TYPES,
                ALL_ASSET_TYPES,
                ALL_TIERS,
                ALL_FUNDAMENTAL_GRADES,
                ALL_SORT_OPTIONS,
                ALL_HORIZONS,
                BuyRankingDtos.MAX_CANDIDATES
        );
    }

    /** 報表查詢主入口：驗證參數 -> 一致性批量載入 -> 四梯隊評分與倉位試算 -> 快取不可變快照 -> 回傳分頁結果。 */
    public BuyRankingResponse generateReport(BuyRankingRequest request, String acceptLanguage) {
        ValidatedParams params = validateAndNormalize(request, acceptLanguage);
        CachedReportSnapshot snapshot = evaluateInternal(params);
        putSnapshot(snapshot);
        return toPagedResponse(snapshot, params.page(), params.pageSize());
    }

    /** 報表匯出共用入口：優先命中同次畫面之 reportId 快照（100% 一致）；過期回 410；未帶 reportId 時依請求重算完整結果。 */
    public CachedReportSnapshot resolveForExport(String reportId, BuyRankingRequest request, String acceptLanguage) {
        if (reportId != null && !reportId.isBlank()) {
            CachedReportSnapshot cached = getSnapshotIfValid(reportId.trim());
            if (cached == null) {
                throw new ResponseStatusException(HttpStatus.GONE, "REPORT_SNAPSHOT_EXPIRED");
            }
            String reqLocale = request == null ? null : request.locale();
            if ((reqLocale != null && !reqLocale.isBlank()) || (acceptLanguage != null && !acceptLanguage.isBlank())) {
                String requestedLoc = BuyRankingReasonFormatter.normalizeLocale(reqLocale, acceptLanguage);
                if (!requestedLoc.equals(cached.resolvedLocale())) {
                    return reLocalizeSnapshot(cached, requestedLoc);
                }
            }
            return cached;
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REPORT_REQUEST_OR_ID_REQUIRED");
        }
        ValidatedParams params = validateAndNormalize(request, acceptLanguage);
        return evaluateInternal(params);
    }

    private CachedReportSnapshot evaluateInternal(ValidatedParams params) {
        long t0 = System.nanoTime();
        Instant now = dateTimeService.now();
        LocalDate lastClosedDay = calendar.getLastClosedTradingDay(now);
        Instant obsCutoff = now;
        LocalDate maxAnnualObsDate = obsCutoff.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate forecastHistoryFrom = maxAnnualObsDate.minusDays(
                ForwardValuationCalculator.MAX_FORECAST_STALE_DAYS + ForwardValuationCalculator.REV_90D_MAX_DAYS + 10L);

        // 1. 於單一 REPEATABLE_READ 唯讀交易內解析實際有效基準日並批量載入所有分析原料（0 外部 API、0 N+1）
        ConsistentSnapshotData data = loadConsistentData(
                params, lastClosedDay, obsCutoff, forecastHistoryFrom, maxAnnualObsDate);
        long tLoadDone = System.nanoTime();

        LocalDate asOfDate = data.asOfDate();
        Instant sessionCutoff = data.sessionCutoff();
        LocalDate fiveYearsAgo = data.fiveYearsAgo();
        MarketBackgroundDto marketBg = buildMarketBackground(
                data.preloadedMarket(), asOfDate, data.lastClosedTradingDay(), data.staleDateFallback(), data.sectorFlowDate());
        MarketRegime globalRegime = parseMarketRegime(marketBg.marketRegime());

        // 2. 純記憶體並行評估四梯隊、技術買區、止損、Net R/R、Reverse DCF 與 1% 風險倉位試算（零 DB 呼叫、全無狀態計算器）
        List<CandidateEntry> candidateList = data.resolution().candidates();
        List<BuyRankingItemDto> evaluated;
        if (candidateList.size() >= PARALLEL_EVAL_THRESHOLD) {
            evaluated = candidateList.parallelStream()
                    .map(entry -> evaluateCandidate(
                            entry, params, asOfDate, sessionCutoff, obsCutoff, fiveYearsAgo, maxAnnualObsDate,
                            data, marketBg, globalRegime))
                    .filter(Objects::nonNull)
                    .toList();
        } else {
            List<BuyRankingItemDto> seq = new ArrayList<>(candidateList.size());
            for (CandidateEntry entry : candidateList) {
                BuyRankingItemDto item = evaluateCandidate(
                        entry, params, asOfDate, sessionCutoff, obsCutoff, fiveYearsAgo, maxAnnualObsDate,
                        data, marketBg, globalRegime);
                if (item != null) {
                    seq.add(item);
                }
            }
            evaluated = seq;
        }
        long tEvalDone = System.nanoTime();

        // 3. 套用風險門檻（minFundamentalGrade、minNetRiskReward）後統計四梯隊筆數，再套用梯隊勾選過濾
        List<BuyRankingItemDto> riskFiltered = new ArrayList<>(evaluated.size());
        for (BuyRankingItemDto item : evaluated) {
            if (!passesRiskFilters(item, params)) {
                continue;
            }
            riskFiltered.add(item);
        }

        Map<String, Integer> tierCounts = new LinkedHashMap<>();
        tierCounts.put(BuyRankingDtos.TIER_1, 0);
        tierCounts.put(BuyRankingDtos.TIER_2, 0);
        tierCounts.put(BuyRankingDtos.TIER_3, 0);
        tierCounts.put(BuyRankingDtos.TIER_4, 0);
        for (BuyRankingItemDto item : riskFiltered) {
            tierCounts.merge(item.tier(), 1, Integer::sum);
        }

        List<BuyRankingItemDto> tierFiltered = new ArrayList<>(riskFiltered.size());
        for (BuyRankingItemDto item : riskFiltered) {
            if (params.tiers().contains(item.tier())) {
                tierFiltered.add(item);
            }
        }

        // 4. 先計算預設推薦序（recommendedRank：Tier 1 -> 4），再套用所選 sortBy 並核發最終 rank
        tierFiltered.sort(defaultTierCompositeComparator());
        Map<String, Integer> recommendedRankByFigi = new HashMap<>(tierFiltered.size());
        for (int i = 0; i < tierFiltered.size(); i++) {
            recommendedRankByFigi.put(tierFiltered.get(i).figi(), i + 1);
        }

        if (!BuyRankingDtos.SORT_TIER_COMPOSITE.equals(params.sortBy())) {
            tierFiltered.sort(buildSortComparator(params.sortBy()));
        }

        List<BuyRankingItemDto> rankedItems = new ArrayList<>(tierFiltered.size());
        for (int i = 0; i < tierFiltered.size(); i++) {
            BuyRankingItemDto raw = tierFiltered.get(i);
            int recRank = recommendedRankByFigi.getOrDefault(raw.figi(), i + 1);
            rankedItems.add(withRanks(raw, i + 1, recRank));
        }

        CandidateResolution res = data.resolution();
        ScopeSummaryDto scopeSummary = new ScopeSummaryDto(
                params.scopeMode(),
                res.scopeLabel(),
                res.industryCodes(),
                res.industryNames(),
                params.portfolioTypes(),
                res.matchedTotalCount(),
                res.candidates().size(),
                rankedItems.size(),
                BuyRankingDtos.MAX_CANDIDATES,
                res.truncated(),
                res.unmatchedSymbols(),
                res.untrackedSymbols(),
                res.excludedByAssetTypeSymbols()
        );

        AppliedFiltersDto appliedFilters = new AppliedFiltersDto(
                params.scopeMode(),
                res.industryCodes(),
                params.portfolioTypes(),
                params.customSymbols(),
                List.copyOf(params.assetTypes()),
                params.excludeReits(),
                List.copyOf(params.tiers()),
                params.minFundamentalGrade(),
                params.minNetRiskReward(),
                params.excludeEarningsWithinDays(),
                params.sortBy(),
                params.horizon().name()
        );

        String generatedAtHk = now.atZone(HK_ZONE).format(HK_TS_FMT);
        String reportId = UUID.randomUUID().toString();
        long tTotalDone = System.nanoTime();
        log.info("Buy ranking report generated | scope={} matched={} evaluated={} filtered={} loadMs={} evalMs={} totalMs={}",
                params.scopeMode(), res.matchedTotalCount(), res.candidates().size(), rankedItems.size(),
                (tLoadDone - t0) / 1_000_000L, (tEvalDone - tLoadDone) / 1_000_000L, (tTotalDone - t0) / 1_000_000L);
        return new CachedReportSnapshot(
                reportId,
                now,
                asOfDate,
                generatedAtHk,
                obsCutoff,
                params.resolvedLocale(),
                marketBg,
                scopeSummary,
                Map.copyOf(tierCounts),
                appliedFilters,
                List.copyOf(rankedItems)
        );
    }

    /**
     * 解析實際有效基準美東交易日：優先採用截至最近收市日為止已入庫之最新估值快照日，
     * 避免每日 10:00 HKT 收盤估值排程尚未完成前，因強制鎖定當日而令全部標的誤落為 QUOTE_MISSING / UNAVAILABLE。
     */
    private LocalDate resolveEffectiveAsOfDate(LocalDate lastClosedDay) {
        if (lastClosedDay == null || latestValuations == null) {
            return lastClosedDay;
        }
        Optional<LocalDate> latestValDate = latestValuations.findMaxValuationDateAtOrBefore(lastClosedDay);
        return latestValDate != null && latestValDate.isPresent() ? latestValDate.get() : lastClosedDay;
    }

    private ConsistentSnapshotData loadConsistentData(
            ValidatedParams params,
            LocalDate lastClosedDay,
            Instant obsCutoff,
            LocalDate forecastHistoryFrom,
            LocalDate maxAnnualObsDate) {
        if (transactions != null && transactions.getTransactionManager() != null) {
            TransactionTemplate readTx = new TransactionTemplate(transactions.getTransactionManager());
            readTx.setReadOnly(true);
            readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return readTx.execute(status -> loadConsistentDataBody(
                    params, lastClosedDay, obsCutoff, forecastHistoryFrom, maxAnnualObsDate));
        }
        return loadConsistentDataBody(
                params, lastClosedDay, obsCutoff, forecastHistoryFrom, maxAnnualObsDate);
    }

    private ConsistentSnapshotData loadConsistentDataBody(
            ValidatedParams params,
            LocalDate lastClosedDay,
            Instant obsCutoff,
            LocalDate forecastHistoryFrom,
            LocalDate maxAnnualObsDate) {
        // 0. 於同一 REPEATABLE_READ 快照內解析實際有效基準交易日與回退旗標，避免跨日或批次重寫造成基準日與資料不一致
        LocalDate asOfDate = resolveEffectiveAsOfDate(lastClosedDay);
        boolean staleDateFallback = lastClosedDay != null && asOfDate != null && asOfDate.isBefore(lastClosedDay);
        Instant sessionCutoff = calendar.getSessionCloseAt(asOfDate);
        LocalDate fiveYearsAgo = asOfDate.minusYears(5);

        // 1. 全域單次預載：產業字典、大盤背景、既倉曝險與最新板塊資金流
        List<StockIndustry> allIndustries = industryRepository.findAll();
        Map<String, StockIndustry> industriesByCode = new LinkedHashMap<>();
        for (StockIndustry ind : allIndustries) {
            if (ind != null && ind.getCode() != null) {
                industriesByCode.put(ind.getCode(), ind);
            }
        }

        CandidateResolution resolution = resolveCandidates(params, industriesByCode, asOfDate, sessionCutoff);
        PreloadedMarketData preloadedMarket = marketContextService.preloadMarketData(asOfDate, obsCutoff);
        PortfolioExposureSnapshot exposure = portfolioManagementService.loadExposureSnapshot(asOfDate, obsCutoff);

        LocalDate sectorFlowDate = null;
        Map<String, SectorMoneyFlowDaily> sectorFlowMap = new HashMap<>();
        if (sectorMoneyFlowRepository != null) {
            List<SectorMoneyFlowDaily> flows = sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(asOfDate);
            if (flows != null && !flows.isEmpty()) {
                sectorFlowDate = asOfDate;
            } else {
                Optional<LocalDate> latestDate = sectorMoneyFlowRepository.findLatestTradeDateAtOrBefore(asOfDate);
                if (latestDate != null && latestDate.isPresent()) {
                    sectorFlowDate = latestDate.get();
                    flows = sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(sectorFlowDate);
                }
            }
            if (flows != null) {
                for (SectorMoneyFlowDaily row : flows) {
                    if (row != null && row.getIndustryCode() != null) {
                        sectorFlowMap.putIfAbsent(row.getIndustryCode(), row);
                    }
                }
            }
        }

        if (resolution.candidates().isEmpty()) {
            return new ConsistentSnapshotData(
                    asOfDate, lastClosedDay, staleDateFallback, sessionCutoff, fiveYearsAgo,
                    preloadedMarket, exposure, industriesByCode, sectorFlowDate, sectorFlowMap, resolution,
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    FxRateLookup.EMPTY, Map.of());
        }

        // 2. 分批（每批 150 檔）以 IN (...) 載入所有候選之狀態、估值、產業、5 年日線、拆股與非 ETF 財報／預測／事件
        List<String> allFigis = resolution.candidates().stream().map(c -> c.stock().getFigi()).toList();
        Map<String, StockStatus> statusByFigi = new HashMap<>();
        Map<String, StockValuationLatest> valuationByFigi = new HashMap<>();
        Map<String, StockIndustryHistory> industryByFigi = new HashMap<>();
        Map<String, List<QuoteBarRow>> quotesByFigi = new HashMap<>();
        Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
        Map<String, List<MarketEvent>> earningsByFigi = new HashMap<>();
        Map<String, List<StockEarningsForecast>> forecastsByFigi = new HashMap<>();
        Map<String, List<StockFinancialPeriod>> periodsByFigi = new HashMap<>();
        Map<String, List<KnownReportPeriod>> knownReportsByFigi = new HashMap<>();

        Set<String> etfFigis = resolution.candidates().stream()
                .filter(c -> BuyRankingDtos.ASSET_ETF.equals(c.assetType()))
                .map(c -> c.stock().getFigi())
                .collect(Collectors.toSet());

        for (List<String> batch : Lists.partition(allFigis, BATCH_SIZE)) {
            for (StockStatus st : stockStatusRepository.findAllById(batch)) {
                statusByFigi.put(st.getFigi(), st);
            }
            for (StockValuationLatest v : latestValuations.findByFigiIn(batch)) {
                valuationByFigi.put(v.getFigi(), v);
            }
            for (StockIndustryHistory ih : industryHistoryRepository.findByFigisAt(batch, sessionCutoff)) {
                industryByFigi.putIfAbsent(ih.getFigi(), ih);
            }
            for (QuoteBarRow bar : quoteRepository.findQuoteBarsOrdered(batch, fiveYearsAgo, asOfDate)) {
                quotesByFigi.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
            }
            for (StockSplit sp : splitRepository.findByFigiInOrderByExecutionDateAsc(batch)) {
                splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
            }

            List<String> nonEtfBatch = batch.stream().filter(f -> !etfFigis.contains(f)).toList();
            if (!nonEtfBatch.isEmpty()) {
                for (MarketEvent ev : marketEventRepository.findVisibleEarningsForFigis(nonEtfBatch, obsCutoff)) {
                    earningsByFigi.computeIfAbsent(ev.getFigi(), k -> new ArrayList<>()).add(ev);
                }
                for (StockEarningsForecast f : forecastRepository.findVisibleForStocksBetween(
                        nonEtfBatch, forecastHistoryFrom, maxAnnualObsDate, obsCutoff)) {
                    forecastsByFigi.computeIfAbsent(f.getFigi(), k -> new ArrayList<>()).add(f);
                }
                for (StockFinancialPeriod p : financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(nonEtfBatch)) {
                    periodsByFigi.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
                }
                List<String> figisWithForecasts = nonEtfBatch.stream()
                        .filter(forecastsByFigi::containsKey)
                        .toList();
                if (!figisWithForecasts.isEmpty()) {
                    for (FigiKnownReportPeriod r : financialReportRepository.findKnownReportPeriodsForFigis(figisWithForecasts, obsCutoff)) {
                        knownReportsByFigi.computeIfAbsent(r.figi(), k -> new ArrayList<>())
                                .add(new KnownReportPeriod(r.periodEnd(), r.periodType(), r.fiscalYear(), r.period(), r.observedAt()));
                    }
                }
            }
        }

        FxRateLookup fxLookup = preloadBatchFx(periodsByFigi, asOfDate);
        Map<String, AdrShareRatio> adrBySymbol = preloadBatchAdrRatios(resolution.candidates());

        return new ConsistentSnapshotData(
                asOfDate, lastClosedDay, staleDateFallback, sessionCutoff, fiveYearsAgo,
                preloadedMarket, exposure, industriesByCode, sectorFlowDate, sectorFlowMap, resolution,
                statusByFigi, valuationByFigi, industryByFigi, quotesByFigi, splitsByFigi,
                earningsByFigi, forecastsByFigi, periodsByFigi, knownReportsByFigi,
                fxLookup, adrBySymbol
        );
    }

    private CandidateResolution resolveCandidates(
            ValidatedParams params,
            Map<String, StockIndustry> industriesByCode,
            LocalDate asOfDate,
            Instant sessionCutoff) {
        if (BuyRankingDtos.SCOPE_SECTOR.equals(params.scopeMode())) {
            return resolveSectorCandidates(params, industriesByCode, asOfDate, sessionCutoff);
        }
        if (BuyRankingDtos.SCOPE_PORTFOLIO.equals(params.scopeMode())) {
            return resolvePortfolioCandidates(params);
        }
        return resolveCustomCandidates(params);
    }

    private CandidateResolution resolveSectorCandidates(
            ValidatedParams params,
            Map<String, StockIndustry> industriesByCode,
            LocalDate asOfDate,
            Instant sessionCutoff) {
        if (params.rawIndustryTokens().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INDUSTRY_CODES_REQUIRED");
        }
        Map<String, List<StockIndustry>> byLowerName = new HashMap<>();
        for (StockIndustry ind : industriesByCode.values()) {
            if (ind.getIndustry() != null) {
                byLowerName.computeIfAbsent(ind.getIndustry().trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(ind);
            }
        }

        Set<String> resolvedCodes = new LinkedHashSet<>();
        List<String> resolvedNames = new ArrayList<>();
        for (String token : params.rawIndustryTokens()) {
            StockIndustry exact = industriesByCode.get(token);
            if (exact != null) {
                if (resolvedCodes.add(exact.getCode())) {
                    resolvedNames.add(exact.getIndustry());
                }
                continue;
            }
            List<StockIndustry> byName = byLowerName.get(token.toLowerCase(Locale.ROOT));
            if (byName == null || byName.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_INDUSTRY:" + token);
            }
            if (byName.size() > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AMBIGUOUS_INDUSTRY:" + token);
            }
            StockIndustry match = byName.getFirst();
            if (resolvedCodes.add(match.getCode())) {
                resolvedNames.add(match.getIndustry());
            }
        }

        List<SectorCandidateMeta> rawRows = industryHistoryRepository.findActiveTrackedCandidateMetaByIndustryCodesAt(
                resolvedCodes, sessionCutoff, asOfDate);
        Set<String> seenFigis = new LinkedHashSet<>();
        List<SectorCandidateMeta> eligibleMeta = new ArrayList<>();
        for (SectorCandidateMeta meta : rawRows) {
            if (meta == null || meta.figi() == null || !seenFigis.add(meta.figi())) {
                continue;
            }
            String assetType = classifyAssetType(meta.stockType(), meta.modelCode());
            if (assetType == null || !isAssetAllowed(meta.stockType(), meta.modelCode(), assetType,
                    params.assetTypes(), params.excludeReits())) {
                continue;
            }
            eligibleMeta.add(meta);
        }

        int totalMatched = eligibleMeta.size();
        boolean truncated = totalMatched > BuyRankingDtos.MAX_CANDIDATES;
        List<SectorCandidateMeta> cappedMeta = truncated
                ? eligibleMeta.subList(0, BuyRankingDtos.MAX_CANDIDATES) : eligibleMeta;
        List<String> cappedFigis = cappedMeta.stream().map(SectorCandidateMeta::figi).toList();
        Map<String, Stock> stockByFigi = new HashMap<>(cappedFigis.size());
        for (List<String> batch : Lists.partition(cappedFigis, BATCH_SIZE)) {
            for (Stock s : stockRepository.findAllById(batch)) {
                stockByFigi.put(s.getFigi(), s);
            }
        }
        List<CandidateEntry> capped = new ArrayList<>(cappedMeta.size());
        for (SectorCandidateMeta meta : cappedMeta) {
            Stock s = stockByFigi.get(meta.figi());
            if (s != null) {
                capped.add(new CandidateEntry(s, classifyAssetType(meta.stockType(), meta.modelCode())));
            }
        }
        String scopeLabel = resolvedNames.size() == 1 ? resolvedNames.getFirst() : "MultiSector_" + resolvedNames.size();
        return new CandidateResolution(
                List.copyOf(capped), List.copyOf(resolvedCodes), List.copyOf(resolvedNames), scopeLabel,
                totalMatched, truncated, List.of(), List.of(), List.of());
    }

    private CandidateResolution resolvePortfolioCandidates(ValidatedParams params) {
        List<PortfolioItem> allItems = portfolioItemRepository.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc();
        Set<String> allowedTypes = new LinkedHashSet<>(params.portfolioTypes());
        List<String> itemFigis = allItems.stream()
                .filter(i -> i != null && i.getFigi() != null && allowedTypes.contains(i.getItemType()))
                .map(PortfolioItem::getFigi)
                .distinct()
                .toList();
        if (itemFigis.isEmpty()) {
            String label = buildPortfolioScopeLabel(params.portfolioTypes());
            return new CandidateResolution(List.of(), List.of(), List.of(), label, 0, false, List.of(), List.of(), List.of());
        }

        Map<String, Stock> stockByFigi = new HashMap<>();
        Map<String, String> modelCodeByFigi = new HashMap<>();
        for (List<String> batch : Lists.partition(itemFigis, BATCH_SIZE)) {
            for (Stock s : stockRepository.findAllById(batch)) {
                stockByFigi.put(s.getFigi(), s);
            }
            for (StockValuationLatest v : latestValuations.findByFigiIn(batch)) {
                modelCodeByFigi.put(v.getFigi(), v.getModelCode());
            }
        }

        List<CandidateEntry> matched = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        List<String> excludedByType = new ArrayList<>();
        for (String figi : itemFigis) {
            Stock s = stockByFigi.get(figi);
            if (s == null || s.getActiveStatus() != ActiveStatus.ACTIVE) {
                continue;
            }
            if (!s.isTracked()) {
                untracked.add(s.getSymbol() != null ? s.getSymbol() : figi);
                continue;
            }
            String modelCode = modelCodeByFigi.get(figi);
            String assetType = classifyAssetType(s.getStockType(), modelCode);
            if (assetType == null || !isAssetAllowed(s.getStockType(), modelCode, assetType,
                    params.assetTypes(), params.excludeReits())) {
                excludedByType.add(s.getSymbol() != null ? s.getSymbol() : figi);
                continue;
            }
            matched.add(new CandidateEntry(s, assetType));
        }

        matched.sort(Comparator.comparing((CandidateEntry c) -> c.stock().getSymbol(), Comparator.nullsLast(String::compareTo))
                .thenComparing(c -> c.stock().getFigi()));
        int totalMatched = matched.size();
        boolean truncated = totalMatched > BuyRankingDtos.MAX_CANDIDATES;
        List<CandidateEntry> capped = truncated ? List.copyOf(matched.subList(0, BuyRankingDtos.MAX_CANDIDATES)) : List.copyOf(matched);
        String label = buildPortfolioScopeLabel(params.portfolioTypes());
        return new CandidateResolution(
                capped, List.of(), List.of(), label, totalMatched, truncated,
                List.of(), List.copyOf(untracked), List.copyOf(excludedByType));
    }

    private CandidateResolution resolveCustomCandidates(ValidatedParams params) {
        if (params.customSymbols().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CUSTOM_SYMBOLS_REQUIRED");
        }
        List<Stock> foundStocks = stockRepository.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, params.customSymbols());
        Map<String, List<Stock>> byUpperSymbol = new HashMap<>();
        for (Stock s : foundStocks) {
            if (s != null && s.getSymbol() != null) {
                byUpperSymbol.computeIfAbsent(s.getSymbol().trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
            }
        }
        List<String> foundFigis = foundStocks.stream().map(Stock::getFigi).distinct().toList();
        Map<String, String> modelCodeByFigi = new HashMap<>();
        for (List<String> batch : Lists.partition(foundFigis, BATCH_SIZE)) {
            for (StockValuationLatest v : latestValuations.findByFigiIn(batch)) {
                modelCodeByFigi.put(v.getFigi(), v.getModelCode());
            }
        }

        List<CandidateEntry> matched = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        List<String> excludedByType = new ArrayList<>();
        Set<String> seenFigis = new LinkedHashSet<>();

        for (String sym : params.customSymbols()) {
            List<Stock> candidatesForSym = byUpperSymbol.get(sym);
            if (candidatesForSym == null || candidatesForSym.isEmpty()) {
                unmatched.add(sym);
                continue;
            }
            candidatesForSym.sort(Comparator.comparing(Stock::isTracked, Comparator.reverseOrder())
                    .thenComparing(Stock::getFigi));
            Stock chosen = candidatesForSym.getFirst();
            if (!chosen.isTracked()) {
                untracked.add(sym);
                continue;
            }
            String modelCode = modelCodeByFigi.get(chosen.getFigi());
            String assetType = classifyAssetType(chosen.getStockType(), modelCode);
            if (assetType == null || !isAssetAllowed(chosen.getStockType(), modelCode, assetType,
                    params.assetTypes(), params.excludeReits())) {
                excludedByType.add(sym);
                continue;
            }
            if (seenFigis.add(chosen.getFigi())) {
                matched.add(new CandidateEntry(chosen, assetType));
            }
        }

        int totalMatched = matched.size();
        boolean truncated = totalMatched > BuyRankingDtos.MAX_CANDIDATES;
        List<CandidateEntry> capped = truncated ? List.copyOf(matched.subList(0, BuyRankingDtos.MAX_CANDIDATES)) : List.copyOf(matched);
        return new CandidateResolution(
                capped, List.of(), List.of(), "Custom", totalMatched, truncated,
                List.copyOf(unmatched), List.copyOf(untracked), List.copyOf(excludedByType));
    }

    private BuyRankingItemDto evaluateCandidate(
            CandidateEntry entry,
            ValidatedParams params,
            LocalDate asOfDate,
            Instant sessionCutoff,
            Instant obsCutoff,
            LocalDate fiveYearsAgo,
            LocalDate maxAnnualObsDate,
            ConsistentSnapshotData data,
            MarketBackgroundDto marketBg,
            MarketRegime globalRegime) {
        Stock stock = entry.stock();
        String figi = stock.getFigi();
        boolean isEtf = BuyRankingDtos.ASSET_ETF.equals(entry.assetType());
        StockStatus status = data.statusByFigi().get(figi);
        StockValuationLatest valuation = data.valuationByFigi().get(figi);
        StockIndustryHistory indHist = data.industryByFigi().get(figi);
        boolean officialIndustry = indHist != null && indHist.getIndustry() != null;
        String indCode = officialIndustry
                ? indHist.getIndustry().getCode()
                : (valuation != null ? valuation.getIndustryCode() : null);
        String indName = officialIndustry
                ? indHist.getIndustry().getIndustry()
                : (indCode != null && data.industriesByCode().containsKey(indCode)
                        ? data.industriesByCode().get(indCode).getIndustry() : indCode);
        SectorMoneyFlowDaily sectorFlow = indCode == null ? null : data.sectorFlowMap().get(indCode);
        boolean sectorBreadthBreakdown = officialIndustry
                && asOfDate.equals(data.sectorFlowDate())
                && SectorMoneyFlowService.isSameDayBreadthBreakdown(sectorFlow, asOfDate);

        // 1. 財報事件（含 Day 0 美東日曆日；ETF 跳過）；若啟用 excludeEarningsWithinDays 則優先移除
        List<MarketEvent> stockEarnings = isEtf ? List.of() : data.earningsByFigi().getOrDefault(figi, List.of());
        LocalDate nextEarningsDate = null;
        List<MarketEvent> pastEarnings = new ArrayList<>();
        List<MarketEvent> windowEvents = new ArrayList<>();
        LocalDate winFrom = asOfDate.minusDays(10);
        LocalDate winTo = asOfDate.plusDays(20);
        for (MarketEvent ev : stockEarnings) {
            if (ev == null || ev.getEventDate() == null) {
                continue;
            }
            if (!ev.getEventDate().isAfter(asOfDate)) {
                pastEarnings.add(ev);
            }
            if (!ev.getEventDate().isBefore(asOfDate)) {
                if (nextEarningsDate == null || ev.getEventDate().isBefore(nextEarningsDate)) {
                    nextEarningsDate = ev.getEventDate();
                }
            }
            if (!ev.getEventDate().isBefore(winFrom) && !ev.getEventDate().isAfter(winTo)) {
                windowEvents.add(ev);
            }
        }
        Integer daysToEarnings = nextEarningsDate == null ? null : (int) ChronoUnit.DAYS.between(asOfDate, nextEarningsDate);
        if (!isEtf && params.excludeEarningsWithinDays() != null && params.excludeEarningsWithinDays() > 0
                && daysToEarnings != null && daysToEarnings >= 0 && daysToEarnings <= params.excludeEarningsWithinDays()) {
            return null;
        }

        // 2. 5 年行情正規化與技術結構／支撐阻力／交易計畫精算（完整傳入 horizon）
        List<QuoteBarRow> rawQuotes = data.quotesByFigi().get(figi);
        List<StockSplit> stockSplits = data.splitsByFigi().getOrDefault(figi, List.of());
        AnalysisContext context = new AnalysisContext(figi, sessionCutoff, obsCutoff, params.horizon());
        boolean hasTargetDayQuote = rawQuotes != null && !rawQuotes.isEmpty() && asOfDate.equals(rawQuotes.getLast().quoteDate());

        MetricResult<NormalizedPriceSeriesDto> price = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<PriceStructureDto> structure = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<MarketContextDto> marketResult = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<SupportResistanceDto> support = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MetricResult<TradePlanResultDto> tradePlans = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());

        if (hasTargetDayQuote) {
            MetricResult<NormalizedPriceSeriesDto> normalized = quoteNormalizationService.normalizeFromLoaded(
                    context, fiveYearsAgo, asOfDate, stock, status, rawQuotes, stockSplits);
            if (normalized != null) {
                price = normalized;
            }
            if (price.status() == MetricStatus.AVAILABLE && price.value() != null && !price.value().bars().isEmpty()) {
                MetricResult<PriceStructureDto> analyzedStructure = priceStructureService.analyze(context, price.value());
                if (analyzedStructure != null) {
                    structure = analyzedStructure;
                }
                if (structure.status() == MetricStatus.AVAILABLE && structure.value() != null) {
                    MetricResult<MarketContextDto> evaluatedMarket = marketContextService.evaluateWithPreloaded(
                            context, price.value(), structure.value(), pastEarnings, windowEvents, data.preloadedMarket());
                    if (evaluatedMarket != null) {
                        marketResult = evaluatedMarket;
                    }
                    MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
                    MetricResult<SupportResistanceDto> analyzedSupport = supportResistanceService.analyze(
                            context, price.value(), structure.value(), marketValue);
                    if (analyzedSupport != null) {
                        support = analyzedSupport;
                    }
                    if (support.status() == MetricStatus.AVAILABLE && support.value() != null) {
                        PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
                        MetricResult<TradePlanResultDto> builtPlans = tradePlanService.build(
                                context, price.value(), structure.value(), support.value(), constraint);
                        if (builtPlans != null) {
                            tradePlans = builtPlans;
                        }
                    }
                }
            }
        }

        // 基準日無行情時不回退舊日收盤價充當現價，避免缺行情列展示過期價格與折價
        BigDecimal evalPrice = (price.status() == MetricStatus.AVAILABLE && price.value() != null && !price.value().bars().isEmpty())
                ? price.value().bars().getLast().close()
                : (hasTargetDayQuote && rawQuotes != null && !rawQuotes.isEmpty() ? rawQuotes.getLast().closePrice() : null);
        List<TradePlanDto> planList = tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();

        // 3. 分流：ETF 走純技術與資金流路徑；個股（普通股／ADR／REIT）走估值、財務品質與 Reverse DCF 路徑
        if (isEtf) {
            return buildEtfItem(
                    entry, params, asOfDate, data, marketBg, globalRegime, indCode, indName, sectorFlow,
                    sectorBreadthBreakdown, hasTargetDayQuote, evalPrice, price, structure, support, tradePlans, marketResult, planList);
        }
        return buildEquityItem(
                entry, params, asOfDate, obsCutoff, maxAnnualObsDate, data, marketBg, globalRegime,
                indCode, indName, sectorFlow, sectorBreadthBreakdown, status, valuation, nextEarningsDate, daysToEarnings,
                hasTargetDayQuote, evalPrice, price, structure, support, tradePlans, marketResult, planList);
    }

    private BuyRankingItemDto buildEtfItem(
            CandidateEntry entry,
            ValidatedParams params,
            LocalDate asOfDate,
            ConsistentSnapshotData data,
            MarketBackgroundDto marketBg,
            MarketRegime globalRegime,
            String indCode,
            String indName,
            SectorMoneyFlowDaily sectorFlow,
            boolean sectorBreadthBreakdown,
            boolean hasTargetDayQuote,
            BigDecimal evalPrice,
            MetricResult<NormalizedPriceSeriesDto> price,
            MetricResult<PriceStructureDto> structure,
            MetricResult<SupportResistanceDto> support,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketResult,
            List<TradePlanDto> planList) {
        Stock stock = entry.stock();
        String figi = stock.getFigi();

        if (!hasTargetDayQuote || evalPrice == null || evalPrice.signum() <= 0
                || price.status() != MetricStatus.AVAILABLE || price.value() == null) {
            return buildSimpleTier4Item(entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                    "NOT_APPLICABLE_FOR_ETF", "NOT_APPLICABLE", "INSUFFICIENT_DATA", null,
                    evalPrice, null, null, null, null, null, null, null, null, null, null,
                    "QUOTE_MISSING", Map.of());
        }

        MetricResult<InvestmentSummaryDto> etfSummaryRes = summaryCalculator.calculateForEtf(
                params.horizon(), evalPrice, stock.getCurrency(), structure, support, tradePlans, marketResult, sectorBreadthBreakdown);
        InvestmentSummaryDto etfSummary = etfSummaryRes.status() == MetricStatus.AVAILABLE ? etfSummaryRes.value() : null;
        if (etfSummary == null) {
            return buildSimpleTier4Item(entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                    "NOT_APPLICABLE_FOR_ETF", "NOT_APPLICABLE", "INSUFFICIENT_DATA", null,
                    evalPrice, null, null, null, null, null, null, null, null, null, null,
                    "QUOTE_MISSING", Map.of());
        }

        LevelZoneDto supportZone = findZoneById(support, etfSummary.technicalSupportZoneId());
        BuyZoneExtraction zoneEx = extractRegularBuyZone(evalPrice, supportZone, planList);
        // 嚴格採用選定支撐區之同區診斷（僅無選定支撐區時才回退 primaryDiagnosis，避免他區破位誤降級）
        BreakoutDiagnosisDto diag = findMatchedZoneDiagnosis(support, supportZone);
        BreakoutDiagnosis diagEnum = diag != null ? diag.diagnosis() : null;
        boolean highRiskBreakdown = diagEnum == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN;
        boolean suspectedShakeout = diagEnum == BreakoutDiagnosis.SUSPECTED_SHAKEOUT;
        boolean hardStopBreachedOrInvalidated = summaryCalculator.hasHardStopBreachOrInvalidation(
                params.horizon(), structure, support, planList, evalPrice)
                || isHardStopBreachedOrInvalidated(zoneEx.matchedPlan(), evalPrice, zoneEx.stopLoss());
        boolean stopBreachedOrReclaim = hasFlag(etfSummary, "PROTECTIVE_STOP_OR_RECLAIM_PENDING");

        BigDecimal trendScore = computeEtfTrendScore(etfSummary.weeklyTrend(), etfSummary.aboveSma200());
        BigDecimal cmf20d = computeEtfCmf20d(price.value().bars());
        BigDecimal cmfScore = normalizeLinear(cmf20d, CMF_NORM_BOUND.negate(), CMF_NORM_BOUND, new BigDecimal("50"));
        MarketContextDto marketVal = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
        BigDecimal rsSignal = (marketVal != null && marketVal.relativeStrength() != null
                && marketVal.relativeStrength().primarySignal() != null
                && marketVal.relativeStrength().primarySignal().status() == MetricStatus.AVAILABLE)
                ? marketVal.relativeStrength().primarySignal().value() : null;
        BigDecimal rs60d = (marketVal != null && marketVal.relativeStrength() != null
                && marketVal.relativeStrength().excessReturn60() != null
                && marketVal.relativeStrength().excessReturn60().status() == MetricStatus.AVAILABLE)
                ? marketVal.relativeStrength().excessReturn60().value() : null;
        BigDecimal rsScore = normalizeLinear(rsSignal, BigDecimal.ONE.negate(), BigDecimal.ONE, new BigDecimal("50"));
        BigDecimal netRrScore = normalizeNetRr(zoneEx.evalNetRr());
        BigDecimal etfScore = trendScore.multiply(WEIGHT_30, MC)
                .add(cmfScore.multiply(WEIGHT_30, MC), MC)
                .add(rsScore.multiply(WEIGHT_20, MC), MC)
                .add(netRrScore.multiply(WEIGHT_20, MC), MC)
                .setScale(2, RoundingMode.HALF_UP);

        String etfStance = etfSummary.stance();
        boolean planActive = zoneEx.matchedPlan() != null && zoneEx.matchedPlan().status() == TradePlanStatus.ACTIVE;
        boolean purePullbackWait = isOnlyWaitingForPullbackAboveZone(zoneEx.matchedPlan());
        TierClassification tc;
        if (highRiskBreakdown) {
            tc = new TierClassification(BuyRankingDtos.TIER_4, etfScore, "HIGH_RISK_BREAKDOWN", Map.of());
        } else if (hardStopBreachedOrInvalidated || (stopBreachedOrReclaim && !suspectedShakeout)) {
            tc = new TierClassification(BuyRankingDtos.TIER_4, etfScore, "STOP_BREACHED_INVALIDATED", Map.of());
        } else if ("DOWNTREND".equals(etfSummary.weeklyTrend()) && Boolean.FALSE.equals(etfSummary.aboveSma200())) {
            tc = new TierClassification(BuyRankingDtos.TIER_4, etfScore, "ETF_TECHNICAL_DOWNTREND", Map.of());
        } else if (suspectedShakeout) {
            tc = new TierClassification(BuyRankingDtos.TIER_3, etfScore, "SUSPECTED_SHAKEOUT_OBSERVING", Map.of());
        } else if (!marketBg.marketDataReady() || globalRegime == null || globalRegime == MarketRegime.UNAVAILABLE) {
            tc = new TierClassification(BuyRankingDtos.TIER_3, etfScore, "MARKET_DATA_NOT_READY", Map.of());
        } else if (globalRegime == MarketRegime.BEAR || globalRegime == MarketRegime.PANIC
                || "WAIT_FOR_MARKET_STABILIZATION".equals(etfStance)) {
            tc = new TierClassification(BuyRankingDtos.TIER_3, etfScore, "MARKET_REGIME_HEADWIND_PAUSE",
                    Map.of("marketRegime", globalRegime.name()));
        } else if ("WAIT_FOR_STABILIZATION".equals(etfStance)) {
            tc = new TierClassification(BuyRankingDtos.TIER_3, etfScore, "ETF_WAIT_TECHNICAL_CONFIRMATION", Map.of());
        } else if (globalRegime == MarketRegime.CORRECTION) {
            String etfRightSideBasis = resolveEtfRightSideBasis(supportZone, diag, zoneEx.matchedPlan(), asOfDate);
            if ("ETF_TECHNICAL_BULLISH_SUPPORT".equals(etfStance) && planActive && etfRightSideBasis != null) {
                tc = new TierClassification(BuyRankingDtos.TIER_1, etfScore, "CORRECTION_RIGHT_SIDE_CONFIRMED",
                        Map.of("confirmationBasis", etfRightSideBasis));
            } else {
                tc = new TierClassification(BuyRankingDtos.TIER_3, etfScore, "MARKET_CORRECTION_WAIT_CONFIRMATION", Map.of());
            }
        } else if ("ETF_TECHNICAL_BULLISH_SUPPORT".equals(etfStance) && planActive) {
            tc = new TierClassification(BuyRankingDtos.TIER_1, etfScore, "ETF_BULLISH_SUPPORT_ACTIVE",
                    Map.of("etfScore", etfScore));
        } else if (("ETF_TECHNICAL_BULLISH_SUPPORT".equals(etfStance) || "ETF_TECHNICAL_NEUTRAL".equals(etfStance))
                && purePullbackWait
                && zoneEx.buyZone() != null && zoneEx.distancePct() != null && zoneEx.distancePct().signum() > 0) {
            tc = new TierClassification(BuyRankingDtos.TIER_2, etfScore, "ETF_WAIT_PULLBACK",
                    Map.of("distancePct", zoneEx.distancePct()));
        } else {
            tc = new TierClassification(BuyRankingDtos.TIER_3, etfScore, "ETF_WAIT_TECHNICAL_CONFIRMATION", Map.of());
        }

        boolean macroCautionActive = etfSummary.macroCaution() != null && etfSummary.macroCaution().active();
        PositionSizingDto sizing = computePositionSizing(
                figi, stock.getCurrency(), indCode, evalPrice, zoneEx, tc.tier(),
                false, globalRegime, macroCautionActive, sectorBreadthBreakdown, data.staleDateFallback(), data.exposure());

        return assembleItem(
                entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                etfSummary.verdict(), etfSummary.fundamentalBacking(), etfStance,
                diagEnum == null ? null : diagEnum.name(),
                evalPrice, zoneEx, sizing, null, null, null, null, null, null, null, "NOT_APPLICABLE_FOR_ETF",
                trendScore, cmf20d, rs60d, null, null, tc);
    }

    private BuyRankingItemDto buildEquityItem(
            CandidateEntry entry,
            ValidatedParams params,
            LocalDate asOfDate,
            Instant obsCutoff,
            LocalDate maxAnnualObsDate,
            ConsistentSnapshotData data,
            MarketBackgroundDto marketBg,
            MarketRegime globalRegime,
            String indCode,
            String indName,
            SectorMoneyFlowDaily sectorFlow,
            boolean sectorBreadthBreakdown,
            StockStatus status,
            StockValuationLatest v,
            LocalDate nextEarningsDate,
            Integer daysToEarnings,
            boolean hasTargetDayQuote,
            BigDecimal evalPrice,
            MetricResult<NormalizedPriceSeriesDto> price,
            MetricResult<PriceStructureDto> structure,
            MetricResult<SupportResistanceDto> support,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketResult,
            List<TradePlanDto> planList) {
        Stock stock = entry.stock();
        String figi = stock.getFigi();
        boolean isAdr = SecurityTypePolicy.isAdrType(stock.getStockType());
        AdrShareRatio adrRatio = isAdr && stock.getSymbol() != null
                ? data.adrBySymbol().get(stock.getSymbol().trim().toUpperCase(Locale.ROOT)) : null;
        boolean verifiedAdr = isAdr && adrRatio != null && adrRatio.isVerified()
                && adrRatio.getAdrRatio() != null && adrRatio.getAdrRatio().signum() > 0;

        List<StockFinancialPeriod> stockPeriods = data.periodsByFigi().getOrDefault(figi, List.of());
        List<KnownReportPeriod> knownReports = data.knownReportsByFigi().getOrDefault(figi, List.of());
        List<StockEarningsForecast> stockForecasts = data.forecastsByFigi().getOrDefault(figi, List.of());
        List<StockSplit> stockSplits = data.splitsByFigi().getOrDefault(figi, List.of());

        StockFinancialPeriod matchedPeriod = StockValuationService.matchPeriod(stockPeriods, asOfDate);
        FxResolution fxRes = resolveFxForCandidate(data.fxLookup(), matchedPeriod, stock.getCurrency(), asOfDate);
        BigDecimal fxRateToQuote = fxRes != null && fxRes.isUsable() ? fxRes.usdPerUnit() : null;
        String fxFailReason = fxRes != null && !fxRes.isUsable() && fxRes.reason() != null ? fxRes.reason().name() : null;

        String unverifiedFromValuation = findUnverifiedValuationReason(v);
        String valuationBlockedReason = null;
        if (isAdr && !verifiedAdr) {
            valuationBlockedReason = ValuationReason.ADR_RATIO_UNVERIFIED.name();
        } else if (fxFailReason != null) {
            valuationBlockedReason = fxFailReason;
        } else if (unverifiedFromValuation != null) {
            valuationBlockedReason = unverifiedFromValuation;
        }

        boolean valuationFresh = v != null && asOfDate.equals(v.getValuationDate());
        boolean priceNormalized = hasTargetDayQuote
                && price != null && price.status() == MetricStatus.AVAILABLE
                && price.value() != null && !price.value().bars().isEmpty();
        BigDecimal effectivePrice = hasTargetDayQuote
                ? (evalPrice != null ? evalPrice : (valuationFresh ? v.getClosePrice() : null))
                : null;
        String splitError = StockValuationService.splitSyncError(status);
        BigDecimal capAtDay = valuationFresh ? v.getMarketCap() : null;
        BigDecimal capClosePrice = (valuationFresh && v.getClosePrice() != null && v.getClosePrice().signum() > 0)
                ? v.getClosePrice() : effectivePrice;
        DayEpsResolution epsResolution = StockValuationService.resolveDayEps(
                stock.getCurrency(), status, matchedPeriod, stockSplits, asOfDate, fxRateToQuote,
                isAdr, verifiedAdr, capAtDay, capClosePrice);
        String peReason = valuationFresh && v.getPeReason() != null ? v.getPeReason().name() : epsResolution.peReason();
        boolean financialInstitution = v != null && IndustryValuationProposalService.isFinancialInstitutionModel(v.getModelCode());

        FundamentalSnapshotDto fundamentalsDto = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                stockPeriods, matchedPeriod, stockSplits, asOfDate, stock.getCurrency(),
                effectivePrice, capAtDay, null,
                epsResolution.epsAtDay(), peReason, valuationFresh ? v.getPe() : null,
                financialInstitution, splitError, fxRateToQuote, valuationBlockedReason, verifiedAdr));
        FutuObservationsDto futuObs = buildReadOnlyForwardObservations(
                asOfDate, obsCutoff, maxAnnualObsDate, stock.getCurrency(), effectivePrice,
                capAtDay, epsResolution.epsAtDay(), peReason,
                stockSplits, splitError, stockForecasts, knownReports, stockPeriods);

        BigDecimal fxMult = resolveQuoteFxMultiplier(fxRateToQuote, matchedPeriod, stock.getCurrency());
        BigDecimal roic = extractAvailableValue(fundamentalsDto == null ? null : fundamentalsDto.roic());
        BigDecimal rawAdjFcfTtm = extractAvailableValue(fundamentalsDto == null ? null : fundamentalsDto.adjFcfTtm());
        BigDecimal adjFcfTtm = (rawAdjFcfTtm != null && fxMult != null) ? rawAdjFcfTtm.multiply(fxMult, MC) : null;
        BigDecimal forwardPeg = (futuObs != null && futuObs.forward() != null)
                ? extractAvailableValue(futuObs.forward().forwardPeg()) : null;
        BigDecimal forwardPe = (futuObs != null && futuObs.forward() != null)
                ? extractAvailableValue(futuObs.forward().forwardPe()) : null;

        // Reverse DCF：複用正式 DcfValuationCalculator.calculate(...)，使用經 3 交易日時效驗證之 10Y 殖利率與實際觀測日
        BigDecimal validatedTenYear = data.preloadedMarket() != null ? data.preloadedMarket().validatedTenYearYieldPct() : null;
        LocalDate validatedTenYearDate = data.preloadedMarket() != null ? data.preloadedMarket().validatedTenYearObservationDate() : null;
        ReverseDcfSelection reverseDcf = computeReverseDcf(
                stock, v, valuationFresh, asOfDate, effectivePrice, matchedPeriod, fundamentalsDto, futuObs,
                fxMult, adjFcfTtm, valuationBlockedReason, financialInstitution, validatedTenYear, validatedTenYearDate);

        // 估值折價百分比：報表契約採 (price - fairValueMid) / fairValueMid（負值表折價，如 -0.152 = -15.2%）
        BigDecimal valuationDiscountPct = (valuationFresh && effectivePrice != null && effectivePrice.signum() > 0
                && v.getFairValueMid() != null && v.getFairValueMid().signum() > 0)
                ? effectivePrice.subtract(v.getFairValueMid(), MC).divide(v.getFairValueMid(), 6, RoundingMode.HALF_UP)
                : null;

        String verdict = valuationFresh && v.getVerdict() != null ? v.getVerdict() : "INSUFFICIENT_EVIDENCE";
        boolean currentLoss = StockValuationService.isCurrentTtmLoss(matchedPeriod, status, asOfDate, isAdr, verifiedAdr);
        String backing = valuationFresh && v.getFundamentalBacking() != null
                ? v.getFundamentalBacking()
                : ValuationSummaryCalculator.evaluateFundamentalBacking(fundamentalsDto, currentLoss);

        // Step A：未核實 ADR／匯率／多股類攔截，嚴禁進入 Tier 1–3
        if (valuationBlockedReason != null) {
            return buildSimpleTier4Item(entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                    verdict, backing, "UNVERIFIED", null, effectivePrice,
                    valuationDiscountPct, forwardPeg, forwardPe, roic, adjFcfTtm,
                    reverseDcf.impliedGrowth(), reverseDcf.basis(), reverseDcf.status(),
                    nextEarningsDate, daysToEarnings, valuationBlockedReason, Map.of());
        }
        // Step B：缺日線或正規化／拆股完整性檢核失敗一律攔截至 Tier 4（不得回退原價進 Tier 3）
        if (!hasTargetDayQuote || effectivePrice == null || effectivePrice.signum() <= 0) {
            return buildSimpleTier4Item(entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                    verdict, backing, "INSUFFICIENT_DATA", null, effectivePrice,
                    valuationDiscountPct, forwardPeg, forwardPe, roic, adjFcfTtm,
                    reverseDcf.impliedGrowth(), reverseDcf.basis(), reverseDcf.status(),
                    nextEarningsDate, daysToEarnings, "QUOTE_MISSING", Map.of());
        }
        if (!priceNormalized) {
            String normCode = (price != null && price.reasons() != null && !price.reasons().isEmpty()
                    && price.reasons().getFirst() != null && price.reasons().getFirst().code() != null)
                    ? price.reasons().getFirst().code() : "NORMALIZATION_FAILED";
            return buildSimpleTier4Item(entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                    verdict, backing, "INSUFFICIENT_DATA", null, effectivePrice,
                    valuationDiscountPct, forwardPeg, forwardPe, roic, adjFcfTtm,
                    reverseDcf.impliedGrowth(), reverseDcf.basis(), reverseDcf.status(),
                    nextEarningsDate, daysToEarnings, "QUOTE_NORMALIZATION_FAILED", Map.of("code", normCode));
        }

        BigDecimal bandLow = valuationFresh ? v.getFairValueLow() : null;
        RadarEvaluationResult eval = summaryCalculator.evaluateForRadar(
                params.horizon(), effectivePrice, verdict, bandLow, backing,
                fundamentalsDto, futuObs, structure, support, tradePlans, marketResult, sectorBreadthBreakdown);

        TrendPullbackStarterDto starter = eval.trendPullbackStarter();
        boolean starterActive = "TREND_PULLBACK_STARTER".equals(eval.stance())
                && starter != null && starter.eligible()
                && starter.anchorPrice() != null && starter.anchorPrice().signum() > 0
                && starter.suggestedStop() != null && starter.suggestedStop().signum() > 0
                && starter.anchorPrice().compareTo(starter.suggestedStop()) > 0;
        BuyZoneExtraction zoneEx = starterActive
                ? extractStarterBuyZone(effectivePrice, starter, support, params.horizon())
                : extractRegularBuyZone(effectivePrice, eval.supportZone(), planList);

        BreakoutDiagnosisDto zoneDiag = eval.matchedZoneDiagnosis();
        BreakoutDiagnosis diagEnum = zoneDiag != null ? zoneDiag.diagnosis() : null;
        boolean sameZoneHighRiskBreakdown = eval.highRiskBreakdown();
        boolean sameZoneSuspectedShakeout = diagEnum == BreakoutDiagnosis.SUSPECTED_SHAKEOUT;
        boolean hardStopBreachedOrInvalidated = eval.hardStopBreachedOrInvalidated()
                || isHardStopBreachedOrInvalidated(zoneEx.matchedPlan(), effectivePrice, zoneEx.stopLoss());
        boolean planActive = zoneEx.matchedPlan() != null && zoneEx.matchedPlan().status() == TradePlanStatus.ACTIVE;

        TierClassification tc = classifyEquityTier(
                eval, verdict, backing, valuationFresh, valuationDiscountPct,
                sameZoneHighRiskBreakdown, sameZoneSuspectedShakeout, hardStopBreachedOrInvalidated,
                daysToEarnings, marketBg.marketDataReady(), globalRegime,
                starterActive, planActive, zoneEx);

        boolean macroCautionActive = eval.macroCaution() != null && eval.macroCaution().active();
        PositionSizingDto sizing = computePositionSizing(
                figi, stock.getCurrency(), indCode, effectivePrice, zoneEx, tc.tier(),
                starterActive, globalRegime, macroCautionActive, sectorBreadthBreakdown, data.staleDateFallback(), data.exposure());

        return assembleItem(
                entry, params.resolvedLocale(), indCode, indName, sectorFlow,
                eval.verdict(), eval.fundamentalBacking(), eval.stance(),
                diagEnum == null ? null : diagEnum.name(),
                effectivePrice, zoneEx, sizing,
                valuationDiscountPct, forwardPeg, forwardPe, roic, adjFcfTtm,
                reverseDcf.impliedGrowth(), reverseDcf.basis(), reverseDcf.status(),
                null, null, null, nextEarningsDate, daysToEarnings, tc);
    }

    private TierClassification classifyEquityTier(
            RadarEvaluationResult eval,
            String verdict,
            String backing,
            boolean valuationFresh,
            BigDecimal valuationDiscountPct,
            boolean sameZoneHighRiskBreakdown,
            boolean sameZoneSuspectedShakeout,
            boolean hardStopBreachedOrInvalidated,
            Integer daysToEarnings,
            boolean marketDataReady,
            MarketRegime globalRegime,
            boolean starterActive,
            boolean planActive,
            BuyZoneExtraction zoneEx) {
        String stance = eval.stance();

        // 1. 先攔截核心資料缺口與明確避雷／價值陷阱條件 → TIER_4
        if (!valuationFresh || "INSUFFICIENT_EVIDENCE".equals(verdict)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "VALUATION_INSUFFICIENT", Map.of());
        }
        if ("WEAKENING".equals(backing) && sameZoneHighRiskBreakdown) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "WEAKENING_AND_BREAKDOWN", Map.of());
        }
        if ("OVERVALUED_RISK".equals(verdict)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "OVERVALUED_RISK", Map.of());
        }
        if ("WEAKENING".equals(backing)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "FUNDAMENTAL_WEAKENING", Map.of());
        }
        if ("CAUTION_VALUE_TRAP".equals(stance) || eval.forwardValueTrap()) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "VALUE_TRAP_WARNING", Map.of());
        }
        if (sameZoneHighRiskBreakdown) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "HIGH_RISK_BREAKDOWN", Map.of());
        }
        // 已確認跌破保護止損或技術失效絕不可被疑似洗盤豁免；僅待收復（RECLAIM_REQUIRED）搭配同區 SUSPECTED_SHAKEOUT 才進 Tier 3
        if (hardStopBreachedOrInvalidated || (eval.stopBreachedOrReclaim() && !sameZoneSuspectedShakeout)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "STOP_BREACHED_INVALIDATED", Map.of());
        }
        if ("INSUFFICIENT_DATA".equals(backing)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "FUNDAMENTAL_INSUFFICIENT", Map.of());
        }
        if ("MODERATELY_OVERVALUED".equals(verdict) && "HOLD_TRAIL_NO_CHASE".equals(stance)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "HOLD_TRAIL_NO_CHASE_NEW_ENTRY", Map.of());
        }
        if ("CAUTION_OVEREXTENSION".equals(stance)) {
            return new TierClassification(BuyRankingDtos.TIER_4, null, "CAUTION_OVEREXTENSION", Map.of());
        }

        // 2. 事件、疑似洗盤與盤勢觀望 → TIER_3
        if (daysToEarnings != null && daysToEarnings >= 0 && daysToEarnings <= EARNINGS_TIER3_CALENDAR_DAYS) {
            return new TierClassification(BuyRankingDtos.TIER_3, null, "EARNINGS_WITHIN_14D",
                    Map.of("daysToEarnings", daysToEarnings));
        }
        if (sameZoneSuspectedShakeout) {
            return new TierClassification(BuyRankingDtos.TIER_3, null, "SUSPECTED_SHAKEOUT_OBSERVING", Map.of());
        }
        if (!marketDataReady || globalRegime == null || globalRegime == MarketRegime.UNAVAILABLE) {
            return new TierClassification(BuyRankingDtos.TIER_3, null, "MARKET_DATA_NOT_READY", Map.of());
        }
        if (globalRegime == MarketRegime.BEAR || globalRegime == MarketRegime.PANIC) {
            return new TierClassification(BuyRankingDtos.TIER_3, null, "MARKET_REGIME_HEADWIND_PAUSE",
                    Map.of("marketRegime", globalRegime.name()));
        }
        boolean isUndervalued = "DEEP_UNDERVALUED".equals(verdict) || "MODERATELY_UNDERVALUED".equals(verdict);
        if (globalRegime == MarketRegime.CORRECTION) {
            // 修正期右側確認特例須同步尊重正式立場（不可蓋過 WAIT_FOR_STABILIZATION／WAIT_FOR_MARKET_STABILIZATION）
            boolean stanceAllowsCorrection = "WATCH_PULLBACK_TO_BAND".equals(stance) || "ACCUMULATE_AT_SUPPORT".equals(stance);
            boolean correctionRightSideEligible = stanceAllowsCorrection
                    && isUndervalued
                    && eval.rightSideConfirmationBasis() != null
                    && planActive
                    && ("STRONG".equals(backing) || "MIXED".equals(backing));
            if (correctionRightSideEligible) {
                BigDecimal score = computeTier1ValueScore(zoneEx.evalNetRr(), valuationDiscountPct, eval.supportZone());
                return new TierClassification(BuyRankingDtos.TIER_1, score, "CORRECTION_RIGHT_SIDE_CONFIRMED",
                        Map.of("confirmationBasis", eval.rightSideConfirmationBasis()));
            }
            return new TierClassification(BuyRankingDtos.TIER_3, null, "MARKET_CORRECTION_WAIT_CONFIRMATION", Map.of());
        }

        // 3. Tier 1（當下優先可下手：強勢回踩試單〔獨立均線／AVWAP 錨點契約，與單股頁及雷達一致〕 或 低估支撐且計畫為 ACTIVE）
        if (starterActive && ("STRONG".equals(backing) || "MIXED".equals(backing))) {
            TrendPullbackStarterDto starter = eval.trendPullbackStarter();
            BigDecimal score = computeTier1StarterScore(zoneEx.evalNetRr(), starter);
            Map<String, Object> reasonParams = new LinkedHashMap<>();
            if (starter.matchedAnchor() != null) {
                reasonParams.put("anchor", starter.matchedAnchor());
            }
            if (starter.positionFraction() != null) {
                reasonParams.put("positionFraction", starter.positionFraction());
            }
            return new TierClassification(BuyRankingDtos.TIER_1, score, "TREND_PULLBACK_STARTER_ACTIVE", reasonParams);
        }
        if ("ACCUMULATE_AT_SUPPORT".equals(stance) && planActive && ("STRONG".equals(backing) || "MIXED".equals(backing))) {
            BigDecimal score = computeTier1ValueScore(zoneEx.evalNetRr(), valuationDiscountPct, eval.supportZone());
            Map<String, Object> reasonParams = new LinkedHashMap<>();
            if (zoneEx.evalNetRr() != null) {
                reasonParams.put("netRr", zoneEx.evalNetRr());
            }
            return new TierClassification(BuyRankingDtos.TIER_1, score, "ACCUMULATE_AT_SUPPORT_ACTIVE", reasonParams);
        }

        // 4. Tier 2（基本面好、估值合理或偏低，僅差價格高於買區上緣等待回踩；若計畫另有收盤確認／R/R 等阻擋原因則進 Tier 3）
        boolean fairOrUndervalued = isUndervalued || "FAIR_LOWER".equals(verdict) || "FAIR_UPPER".equals(verdict);
        boolean purePullbackWait = isOnlyWaitingForPullbackAboveZone(zoneEx.matchedPlan());
        if (("STRONG".equals(backing) || "MIXED".equals(backing))
                && fairOrUndervalued
                && purePullbackWait
                && !"WAIT_FOR_STABILIZATION".equals(stance)
                && !"WAIT_FOR_MARKET_STABILIZATION".equals(stance)
                && zoneEx.buyZone() != null
                && zoneEx.distancePct() != null
                && zoneEx.distancePct().signum() > 0) {
            return new TierClassification(BuyRankingDtos.TIER_2, null, "WAIT_PULLBACK_TO_BUY_ZONE",
                    Map.of("distancePct", zoneEx.distancePct()));
        }

        // 5. 其餘合格但無成形買區或仍待收盤／訊號確認 → TIER_3
        return new TierClassification(BuyRankingDtos.TIER_3, null, "WAIT_SIGNAL_OR_CONFIRMATION", Map.of());
    }

    /** Tier 1 價值軌分數：Net R/R (50%) + 折價幅度 (30%) + 支撐強度 (20%)，採固定區間夾限至 [0, 100]，單筆樣本零 NaN。 */
    private BigDecimal computeTier1ValueScore(BigDecimal netRr, BigDecimal valuationDiscountPct, LevelZoneDto supportZone) {
        BigDecimal rrScore = normalizeNetRr(netRr);
        // valuationDiscountPct 為負值表折價（如 -0.152），取反得正折價幅度 0.152
        BigDecimal discountMagnitude = valuationDiscountPct == null ? null : valuationDiscountPct.negate();
        BigDecimal discountScore = normalizeLinear(discountMagnitude, BigDecimal.ZERO, MAX_DISCOUNT_NORM, BigDecimal.ZERO);
        BigDecimal supportStrength = supportZone == null || supportZone.effectiveStrength() == null
                ? BigDecimal.ZERO : clamp(supportZone.effectiveStrength(), BigDecimal.ZERO, HUNDRED);
        return rrScore.multiply(WEIGHT_50, MC)
                .add(discountScore.multiply(WEIGHT_30, MC), MC)
                .add(supportStrength.multiply(WEIGHT_20, MC), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Tier 1 強勢試單軌分數：Net R/R (50%) + PEG/高成長 P/E (30%) + 錨點貼近度 (20%)。 */
    private BigDecimal computeTier1StarterScore(BigDecimal netRr, TrendPullbackStarterDto starter) {
        BigDecimal rrScore = normalizeNetRr(netRr);
        BigDecimal growthValScore = BigDecimal.ZERO;
        if (starter != null) {
            if ("FORWARD_PEG".equals(starter.forwardGrowthBasis()) && starter.forwardPeg() != null) {
                BigDecimal clamped = clamp(starter.forwardPeg(), PEG_MIN_NORM, PEG_MAX_NORM);
                growthValScore = PEG_MAX_NORM.subtract(clamped, MC)
                        .divide(PEG_MAX_NORM.subtract(PEG_MIN_NORM, MC), MC)
                        .multiply(HUNDRED, MC);
            } else if ("HYPER_GROWTH_FORWARD_PE".equals(starter.forwardGrowthBasis()) && starter.forwardPe() != null) {
                BigDecimal clamped = clamp(starter.forwardPe(), PE_MIN_NORM, PE_MAX_NORM);
                growthValScore = PE_MAX_NORM.subtract(clamped, MC)
                        .divide(PE_MAX_NORM.subtract(PE_MIN_NORM, MC), MC)
                        .multiply(HUNDRED, MC);
            }
        }
        BigDecimal anchorProximity = BigDecimal.ZERO;
        if (starter != null && starter.distanceRatio() != null) {
            BigDecimal dist = starter.distanceRatio();
            if (dist.signum() < 0) {
                BigDecimal ratio = clamp(dist.abs().divide(STARTER_DIST_NEG_BOUND, MC), BigDecimal.ZERO, BigDecimal.ONE);
                anchorProximity = BigDecimal.ONE.subtract(ratio, MC).multiply(HUNDRED, MC);
            } else {
                BigDecimal ratio = clamp(dist.divide(STARTER_DIST_POS_BOUND, MC), BigDecimal.ZERO, BigDecimal.ONE);
                anchorProximity = BigDecimal.ONE.subtract(ratio, MC).multiply(HUNDRED, MC);
            }
        }
        return rrScore.multiply(WEIGHT_50, MC)
                .add(growthValScore.multiply(WEIGHT_30, MC), MC)
                .add(anchorProximity.multiply(WEIGHT_20, MC), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BuyZoneExtraction extractRegularBuyZone(BigDecimal evalPrice, LevelZoneDto supportZone, List<TradePlanDto> planList) {
        TradePlanDto matchedPlan = findMatchingPlan(planList, supportZone);
        if (matchedPlan == null && supportZone == null && planList != null) {
            for (TradePlanDto p : planList) {
                if (p != null && p.status() != TradePlanStatus.UNAVAILABLE && p.entryRange() != null) {
                    matchedPlan = p;
                    break;
                }
            }
        }

        BigDecimal low = null;
        BigDecimal high = null;
        String source = "NONE";
        if (matchedPlan != null && matchedPlan.entryRange() != null
                && matchedPlan.entryRange().lower() != null && matchedPlan.entryRange().upper() != null) {
            low = matchedPlan.entryRange().lower();
            high = matchedPlan.entryRange().upper();
            source = "TRADE_PLAN_ENTRY";
        } else if (supportZone != null && supportZone.lower() != null && supportZone.upper() != null) {
            low = supportZone.lower();
            high = supportZone.upper();
            source = "SUPPORT_ZONE";
        }

        BuyZoneDto buyZone = null;
        BigDecimal distancePct = null;
        boolean nearWithin3Pct = false;
        if (low != null && high != null && high.signum() > 0) {
            BigDecimal mid = low.add(high, MC).divide(TWO, 4, RoundingMode.HALF_UP);
            buyZone = new BuyZoneDto(scale4(low), mid, scale4(high));
            if (evalPrice != null && evalPrice.signum() > 0) {
                if (evalPrice.compareTo(low) < 0) {
                    distancePct = evalPrice.subtract(low, MC).divide(low, 6, RoundingMode.HALF_UP);
                } else if (evalPrice.compareTo(high) <= 0) {
                    distancePct = BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
                } else {
                    distancePct = evalPrice.subtract(high, MC).divide(high, 6, RoundingMode.HALF_UP);
                }
                nearWithin3Pct = evalPrice.compareTo(low) >= 0
                        && evalPrice.compareTo(high.multiply(MAX_SUPPORT_NEAR_FACTOR, MC)) <= 0;
            }
        }

        BigDecimal stopLoss = null;
        if (matchedPlan != null && matchedPlan.stop() != null && matchedPlan.stop().protectivePrice() != null
                && matchedPlan.stop().protectivePrice().signum() > 0) {
            stopLoss = matchedPlan.stop().protectivePrice();
        } else if (supportZone != null && supportZone.lower() != null && supportZone.anchorAtr() != null
                && supportZone.anchorAtr().signum() > 0) {
            BigDecimal candidate = supportZone.lower().subtract(supportZone.anchorAtr().multiply(HALF_ATR, MC), MC);
            if (candidate.signum() > 0) {
                stopLoss = candidate;
            }
        }

        BigDecimal firstTarget = null;
        BigDecimal evalNetRr = null;
        BigDecimal planNetRr = null;
        if (matchedPlan != null) {
            if (matchedPlan.riskReward() != null && matchedPlan.riskReward().status() == MetricStatus.AVAILABLE
                    && matchedPlan.riskReward().value() != null) {
                planNetRr = scale4(matchedPlan.riskReward().value().netRR());
            }
            if (matchedPlan.targets() != null && !matchedPlan.targets().isEmpty()) {
                TargetDto first = matchedPlan.targets().getFirst();
                if (first != null && first.price() != null && first.price().signum() > 0) {
                    firstTarget = first.price();
                    if (evalPrice != null && firstTarget.compareTo(evalPrice) > 0
                            && stopLoss != null && stopLoss.signum() > 0 && evalPrice.compareTo(stopLoss) > 0) {
                        MetricResult<RiskRewardDto> rr = costCalculator.calculate(evalPrice, stopLoss, matchedPlan.targets());
                        if (rr.status() == MetricStatus.AVAILABLE && rr.value() != null) {
                            evalNetRr = scale4(rr.value().netRR());
                        }
                    }
                }
            }
        }

        BigDecimal sizingEntry = (matchedPlan != null && matchedPlan.entryRange() != null && matchedPlan.entryRange().upper() != null)
                ? matchedPlan.entryRange().upper()
                : (buyZone != null && buyZone.high() != null ? buyZone.high() : evalPrice);
        return new BuyZoneExtraction(buyZone, source, distancePct, nearWithin3Pct, sizingEntry,
                scale4(stopLoss), scale4(firstTarget), evalNetRr, planNetRr, matchedPlan);
    }

    private BuyZoneExtraction extractStarterBuyZone(
            BigDecimal evalPrice,
            TrendPullbackStarterDto starter,
            MetricResult<SupportResistanceDto> support,
            AnalysisHorizon horizon) {
        BigDecimal anchor = starter.anchorPrice();
        BuyZoneDto buyZone = null;
        if (anchor != null && anchor.signum() > 0) {
            BigDecimal low = anchor.multiply(STARTER_LOW_FACTOR, MC);
            BigDecimal high = anchor.multiply(STARTER_HIGH_FACTOR, MC);
            buyZone = new BuyZoneDto(scale4(low), scale4(anchor), scale4(high));
        }
        BigDecimal stopLoss = starter.suggestedStop();
        BigDecimal firstTarget = resolveNearestResistanceTarget(support, evalPrice, horizon);
        BigDecimal evalNetRr = null;
        if (firstTarget != null && evalPrice != null && stopLoss != null && stopLoss.signum() > 0
                && evalPrice.compareTo(stopLoss) > 0) {
            TargetDto single = new TargetDto(firstTarget, BigDecimal.ONE, TargetAction.TAKE_PROFIT, null,
                    "上方已確認阻力區前置0.10 ATR", List.of());
            MetricResult<RiskRewardDto> rr = costCalculator.calculate(evalPrice, stopLoss, List.of(single));
            if (rr.status() == MetricStatus.AVAILABLE && rr.value() != null) {
                evalNetRr = scale4(rr.value().netRR());
            }
        }
        // 強勢試單之倉位試算入場參考價與單股分析頁 100% 一致：採 starter.anchorPrice()，非 anchor * 1.03
        BigDecimal sizingEntry = anchor != null && anchor.signum() > 0 ? anchor : evalPrice;
        return new BuyZoneExtraction(buyZone, "PULLBACK_ANCHOR", BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP), true,
                sizingEntry, scale4(stopLoss), scale4(firstTarget), evalNetRr, evalNetRr, null);
    }

    /** 沿用 TradePlanCalculator 之跨週期有效阻力前置 0.10 ATR 目標規則，供強勢試單計算單目標（權重 1）Net R/R。 */
    private BigDecimal resolveNearestResistanceTarget(
            MetricResult<SupportResistanceDto> support, BigDecimal evalPrice, AnalysisHorizon horizon) {
        if (support == null || support.status() != MetricStatus.AVAILABLE || support.value() == null
                || support.value().resistances() == null || evalPrice == null) {
            return null;
        }
        AnalysisTimeframe minTf = horizon == AnalysisHorizon.SWING ? AnalysisTimeframe.DAILY : AnalysisTimeframe.WEEKLY;
        BigDecimal nearest = null;
        for (LevelZoneDto zone : support.value().resistances()) {
            if (zone == null || zone.state() != ZoneState.ACTIVE || zone.lower() == null
                    || zone.anchorAtr() == null || zone.anchorAtr().signum() <= 0
                    || zone.timeframe() == null || zone.timeframe().ordinal() < minTf.ordinal()) {
                continue;
            }
            BigDecimal target = zone.lower().subtract(zone.anchorAtr().multiply(TARGET_FRONT_RUN_ATR, MC), MC);
            if (target.compareTo(evalPrice) > 0 && (nearest == null || target.compareTo(nearest) < 0)) {
                nearest = target;
            }
        }
        return nearest;
    }

    private PositionSizingDto computePositionSizing(
            String figi,
            String currency,
            String industryCode,
            BigDecimal evalPrice,
            BuyZoneExtraction zoneEx,
            String tier,
            boolean starterSetup,
            MarketRegime regime,
            boolean macroCautionActive,
            boolean sectorBreadthBreakdown,
            boolean staleDateFallback,
            PortfolioExposureSnapshot exposure) {
        if (BuyRankingDtos.TIER_4.equals(tier)) {
            return new PositionSizingDto(
                    "NOT_RECOMMENDED", "TIER_4_NOT_RECOMMENDED", false, 0L,
                    null, zoneEx.stopLoss(), null, null, null, null, null,
                    exposure != null && exposure.config() != null ? exposure.config().totalCapital() : null,
                    exposure != null && exposure.config() != null ? exposure.config().riskPerTradePct() : null,
                    exposure != null && exposure.config() != null ? exposure.config().maxSinglePositionPct() : null,
                    exposure != null && exposure.config() != null ? exposure.config().maxSectorExposurePct() : null,
                    0, 1, "NOT_RECOMMENDED", null, industryCode, null, null, false,
                    exposure == null ? 0 : exposure.excludedHoldingsCount(), List.of());
        }
        if (exposure == null || exposure.config() == null) {
            return PositionSizingDto.notApplicable("PORTFOLIO_CONFIG_MISSING", null, null, null, null, industryCode);
        }
        if (exposure.unverifiedHoldingFigis().contains(figi) && "USD".equalsIgnoreCase(currency)) {
            return PositionSizingDto.notApplicable(
                    "EXISTING_HOLDING_VALUE_UNVERIFIED",
                    exposure.config().totalCapital(),
                    exposure.config().riskPerTradePct(),
                    exposure.config().maxSinglePositionPct(),
                    exposure.config().maxSectorExposurePct(),
                    industryCode);
        }
        BigDecimal entryRef = zoneEx.sizingEntryPrice() != null ? zoneEx.sizingEntryPrice()
                : (zoneEx.buyZone() != null && zoneEx.buyZone().high() != null ? zoneEx.buyZone().high() : evalPrice);
        // 當報表資料日落後最新已收市交易日（staleDateFallback）時，標記為非即時可執行（僅供試算參考）
        boolean planActionable = !staleDateFallback && BuyRankingDtos.TIER_1.equals(tier);
        BigDecimal existingHoldingMv = exposure.holdingMarketValueUsdByFigi().getOrDefault(figi, BigDecimal.ZERO);
        BigDecimal currentSectorMv = industryCode == null ? BigDecimal.ZERO
                : exposure.sectorMarketValueUsdByIndustry().getOrDefault(industryCode, BigDecimal.ZERO);

        return positionSizingCalculator.calculate(new SizingInputs(
                currency,
                industryCode,
                entryRef,
                evalPrice,
                zoneEx.stopLoss(),
                planActionable,
                staleDateFallback,
                starterSetup,
                regime,
                macroCautionActive,
                sectorBreadthBreakdown,
                exposure.config(),
                existingHoldingMv,
                currentSectorMv,
                exposure.excludedHoldingsCount()
        ));
    }

    private record ReverseDcfSelection(BigDecimal impliedGrowth, String basis, String status) {}

    /** 僅於已明確確認同幣（reportingCurrency 非空且等於 quoteCurrency）時才採 1.0；其餘必用已核驗匯率，不可把未知幣別當 USD。 */
    private BigDecimal resolveQuoteFxMultiplier(
            BigDecimal fxRateToQuote, StockFinancialPeriod matchedPeriod, String quoteCurrency) {
        if (fxRateToQuote != null && fxRateToQuote.signum() > 0) {
            return fxRateToQuote;
        }
        if (matchedPeriod != null && matchedPeriod.getReportingCurrency() != null
                && !matchedPeriod.getReportingCurrency().isBlank()
                && quoteCurrency != null
                && matchedPeriod.getReportingCurrency().trim().equalsIgnoreCase(quoteCurrency.trim())) {
            return BigDecimal.ONE;
        }
        return null;
    }

    private ReverseDcfSelection computeReverseDcf(
            Stock stock,
            StockValuationLatest v,
            boolean valuationFresh,
            LocalDate asOfDate,
            BigDecimal evalPrice,
            StockFinancialPeriod matchedPeriod,
            FundamentalSnapshotDto fundamentalsDto,
            FutuObservationsDto futuObs,
            BigDecimal fxMult,
            BigDecimal adjFcfUsd,
            String valuationBlockedReason,
            boolean financialInstitution,
            BigDecimal tenYearYieldPct,
            LocalDate tenYearObservationDate) {
        boolean reit = isReitCandidate(stock.getStockType(), v == null ? null : v.getModelCode());
        BigDecimal marketCapUsd = valuationFresh && "USD".equalsIgnoreCase(stock.getCurrency()) ? v.getMarketCap() : null;
        BigDecimal sharesAtDay = (marketCapUsd != null && marketCapUsd.signum() > 0
                && evalPrice != null && evalPrice.signum() > 0)
                ? marketCapUsd.divide(evalPrice, 6, RoundingMode.HALF_UP) : null;

        BigDecimal fcfUsd = null;
        if (fxMult != null && matchedPeriod != null
                && MetricStatus.AVAILABLE.name().equals(matchedPeriod.getFreeCashFlowTtmStatus())
                && matchedPeriod.getFreeCashFlowTtm() != null) {
            fcfUsd = matchedPeriod.getFreeCashFlowTtm().multiply(fxMult, MC);
        }
        BigDecimal revCagr3y = extractAvailableValue(fundamentalsDto == null ? null : fundamentalsDto.revenueCagr3y());
        BigDecimal fcfCagr3y = extractAvailableValue(fundamentalsDto == null ? null : fundamentalsDto.fcfCagr3y());
        BigDecimal forwardGrowth = (futuObs != null && futuObs.forward() != null)
                ? extractAvailableValue(futuObs.forward().impliedEpsGrowth()) : null;

        DcfValuationDto dcf = dcfCalculator.calculate(new DcfValuationCalculator.Inputs(
                asOfDate, stock.getCurrency(), evalPrice, marketCapUsd, sharesAtDay,
                financialInstitution, reit, false, valuationBlockedReason,
                fcfUsd, adjFcfUsd, tenYearYieldPct, tenYearObservationDate, revCagr3y, fcfCagr3y, forwardGrowth));
        if (dcf == null) {
            return new ReverseDcfSelection(null, null, MetricStatus.INSUFFICIENT_DATA.name());
        }
        // 優先扣 SBC 之實質 FCF 分支（有效且正值）；若扣 SBC 基準有效但求解超界，保留 REVERSE_DCF_OUT_OF_RANGE 不回退標準 FCF
        DcfBranchDto adj = dcf.sbcAdjustedFcf();
        if (adj != null && MetricStatus.AVAILABLE.name().equals(adj.status()) && adj.reverseDcf() != null) {
            if (MetricStatus.AVAILABLE.name().equals(adj.reverseDcf().status())) {
                return new ReverseDcfSelection(adj.reverseDcf().impliedStage1GrowthRate(), "SBC_ADJUSTED_FCF", MetricStatus.AVAILABLE.name());
            }
            return new ReverseDcfSelection(null, "SBC_ADJUSTED_FCF",
                    adj.reverseDcf().reasonCode() != null ? adj.reverseDcf().reasonCode() : adj.reverseDcf().status());
        }
        DcfBranchDto std = dcf.standardFcf();
        if (std != null && MetricStatus.AVAILABLE.name().equals(std.status()) && std.reverseDcf() != null) {
            if (MetricStatus.AVAILABLE.name().equals(std.reverseDcf().status())) {
                return new ReverseDcfSelection(std.reverseDcf().impliedStage1GrowthRate(), "STANDARD_FCF", MetricStatus.AVAILABLE.name());
            }
            return new ReverseDcfSelection(null, "STANDARD_FCF",
                    std.reverseDcf().reasonCode() != null ? std.reverseDcf().reasonCode() : std.reverseDcf().status());
        }
        return new ReverseDcfSelection(null, null, dcf.reasonCode() != null ? dcf.reasonCode() : dcf.status());
    }

    private BuyRankingItemDto assembleItem(
            CandidateEntry entry,
            String locale,
            String indCode,
            String indName,
            SectorMoneyFlowDaily sectorFlow,
            String verdict,
            String backing,
            String stance,
            String breakoutDiagnosis,
            BigDecimal evalPrice,
            BuyZoneExtraction zoneEx,
            PositionSizingDto sizing,
            BigDecimal valuationDiscountPct,
            BigDecimal forwardPeg,
            BigDecimal forwardPe,
            BigDecimal roic,
            BigDecimal adjFcfTtm,
            BigDecimal reverseDcfImpliedGrowth,
            String reverseDcfBasis,
            String reverseDcfStatus,
            BigDecimal etfTrendScore,
            BigDecimal etfCmf20d,
            BigDecimal etfRs60dVsSpy,
            LocalDate nextEarningsDate,
            Integer daysToEarnings,
            TierClassification tc) {
        Stock s = entry.stock();
        BigDecimal stopDistPct = (evalPrice != null && evalPrice.signum() > 0 && zoneEx.stopLoss() != null)
                ? zoneEx.stopLoss().subtract(evalPrice, MC).divide(evalPrice, 6, RoundingMode.HALF_UP) : null;

        Long suggestedShares = sizing == null ? null : sizing.suggestedShares();
        String scaleTag = resolveScaleTag(sizing);
        String sizingStatus = sizing == null ? PositionSizingDto.STATUS_NOT_APPLICABLE : sizing.status();
        boolean sizingActionable = sizing != null && sizing.planActionable();
        String sizingReason = sizing == null ? null : sizing.reasonCode();
        boolean sectorWarn = sizing != null && sizing.sectorConcentrationWarning();
        List<String> sizingTags = sizing == null || sizing.sizingTags() == null ? List.of() : sizing.sizingTags();

        String actionReason = BuyRankingReasonFormatter.formatReason(tc.reasonCode(), tc.reasonParams(), locale);
        return new BuyRankingItemDto(
                0,
                0,
                tc.tier(),
                tc.tierScore(),
                s.getFigi(),
                s.getSymbol(),
                s.getLongName(),
                s.getCurrency(),
                indCode,
                indName,
                entry.assetType(),
                s.getStockType(),
                verdict,
                backing,
                stance,
                breakoutDiagnosis,
                scale4(evalPrice),
                zoneEx.buyZone(),
                zoneEx.distancePct(),
                zoneEx.nearWithin3Pct(),
                zoneEx.source(),
                zoneEx.stopLoss(),
                stopDistPct,
                zoneEx.firstTarget(),
                zoneEx.evalNetRr(),
                zoneEx.planNetRr(),
                suggestedShares,
                scaleTag,
                sizingStatus,
                sizingActionable,
                sizingReason,
                sectorWarn,
                sizingTags,
                valuationDiscountPct,
                scale4(forwardPeg),
                scale4(forwardPe),
                roic,
                adjFcfTtm,
                reverseDcfImpliedGrowth,
                reverseDcfBasis,
                reverseDcfStatus,
                etfTrendScore,
                etfCmf20d,
                etfRs60dVsSpy,
                nextEarningsDate,
                daysToEarnings,
                sectorFlow == null ? null : sectorFlow.getFlowState(),
                sectorFlow == null ? null : sectorFlow.getCmf20d(),
                tc.reasonCode(),
                tc.reasonParams(),
                actionReason
        );
    }

    private BuyRankingItemDto buildSimpleTier4Item(
            CandidateEntry entry,
            String locale,
            String indCode,
            String indName,
            SectorMoneyFlowDaily sectorFlow,
            String verdict,
            String backing,
            String stance,
            String breakoutDiagnosis,
            BigDecimal evalPrice,
            BigDecimal valuationDiscountPct,
            BigDecimal forwardPeg,
            BigDecimal forwardPe,
            BigDecimal roic,
            BigDecimal adjFcfTtm,
            BigDecimal reverseDcfImpliedGrowth,
            String reverseDcfBasis,
            String reverseDcfStatus,
            LocalDate nextEarningsDate,
            Integer daysToEarnings,
            String reasonCode,
            Map<String, Object> reasonParams) {
        BuyZoneExtraction emptyZone = new BuyZoneExtraction(null, "NONE", null, false, null, null, null, null, null, null);
        TierClassification tc = new TierClassification(BuyRankingDtos.TIER_4, null, reasonCode, reasonParams);
        PositionSizingDto tier4Sizing = new PositionSizingDto(
                "NOT_RECOMMENDED", "TIER_4_NOT_RECOMMENDED", false, 0L,
                null, null, null, null, null, null, null, null, null, null, null,
                0, 1, "NOT_RECOMMENDED", null, indCode, null, null, false, 0, List.of());
        return assembleItem(
                entry, locale, indCode, indName, sectorFlow, verdict, backing, stance, breakoutDiagnosis,
                evalPrice, emptyZone, tier4Sizing, valuationDiscountPct, forwardPeg, forwardPe, roic, adjFcfTtm,
                reverseDcfImpliedGrowth, reverseDcfBasis, reverseDcfStatus, null, null, null,
                nextEarningsDate, daysToEarnings, tc);
    }

    private String resolveScaleTag(PositionSizingDto sizing) {
        if (sizing == null) {
            return "NOT_APPLICABLE";
        }
        if ("EXISTING_HOLDING_VALUE_UNVERIFIED".equals(sizing.reasonCode())) {
            return "UNVERIFIED";
        }
        if (sizing.scaleLabel() != null && !sizing.scaleLabel().isBlank()) {
            return sizing.scaleLabel();
        }
        return sizing.status() != null ? sizing.status() : "NOT_APPLICABLE";
    }

    private boolean passesRiskFilters(BuyRankingItemDto item, ValidatedParams params) {
        boolean isEtf = BuyRankingDtos.ASSET_ETF.equals(item.assetType());
        if (!isEtf && !BuyRankingDtos.GRADE_ALL.equals(params.minFundamentalGrade())) {
            if (BuyRankingDtos.GRADE_STRONG.equals(params.minFundamentalGrade())) {
                if (!"STRONG".equals(item.fundamentalBacking())) {
                    return false;
                }
            } else if (BuyRankingDtos.GRADE_MIXED.equals(params.minFundamentalGrade())) {
                if (!"STRONG".equals(item.fundamentalBacking()) && !"MIXED".equals(item.fundamentalBacking())) {
                    return false;
                }
            }
        }
        if (params.minNetRiskReward() != null) {
            BigDecimal effectiveRr = BuyRankingDtos.TIER_2.equals(item.tier()) && item.planNetRiskReward() != null
                    ? item.planNetRiskReward() : item.netRiskReward();
            if (effectiveRr == null || effectiveRr.compareTo(params.minNetRiskReward()) < 0) {
                return false;
            }
        }
        return true;
    }

    private Comparator<BuyRankingItemDto> defaultTierCompositeComparator() {
        return (a, b) -> {
            int tierCmp = Integer.compare(tierOrder(a.tier()), tierOrder(b.tier()));
            if (tierCmp != 0) {
                return tierCmp;
            }
            if (BuyRankingDtos.TIER_1.equals(a.tier())) {
                int scoreCmp = compareNullableDesc(a.tierScore(), b.tierScore());
                if (scoreCmp != 0) {
                    return scoreCmp;
                }
            } else if (BuyRankingDtos.TIER_2.equals(a.tier())) {
                BigDecimal distA = a.distanceToBuyZonePct() == null ? null : a.distanceToBuyZonePct().abs();
                BigDecimal distB = b.distanceToBuyZonePct() == null ? null : b.distanceToBuyZonePct().abs();
                int distCmp = compareNullableAsc(distA, distB);
                if (distCmp != 0) {
                    return distCmp;
                }
            }
            return tieBreakBySymbolAndFigi(a, b);
        };
    }

    private Comparator<BuyRankingItemDto> buildSortComparator(String sortBy) {
        return switch (sortBy) {
            case BuyRankingDtos.SORT_NET_RR_DESC -> (a, b) -> {
                int cmp = compareNullableDesc(a.netRiskReward(), b.netRiskReward());
                return cmp != 0 ? cmp : tieBreakBySymbolAndFigi(a, b);
            };
            case BuyRankingDtos.SORT_DISCOUNT_DESC -> (a, b) -> {
                // valuationDiscountPct 負值表折價，折價越深數值越小（升冪排序）
                int cmp = compareNullableAsc(a.valuationDiscountPct(), b.valuationDiscountPct());
                return cmp != 0 ? cmp : tieBreakBySymbolAndFigi(a, b);
            };
            case BuyRankingDtos.SORT_DISTANCE_TO_BUY_ZONE_ASC -> (a, b) -> {
                BigDecimal distA = a.distanceToBuyZonePct() == null ? null : a.distanceToBuyZonePct().abs();
                BigDecimal distB = b.distanceToBuyZonePct() == null ? null : b.distanceToBuyZonePct().abs();
                int cmp = compareNullableAsc(distA, distB);
                return cmp != 0 ? cmp : tieBreakBySymbolAndFigi(a, b);
            };
            case BuyRankingDtos.SORT_ROIC_DESC -> (a, b) -> {
                int cmp = compareNullableDesc(a.roic(), b.roic());
                return cmp != 0 ? cmp : tieBreakBySymbolAndFigi(a, b);
            };
            case BuyRankingDtos.SORT_SYMBOL_ASC -> this::tieBreakBySymbolAndFigi;
            default -> defaultTierCompositeComparator();
        };
    }

    private int tieBreakBySymbolAndFigi(BuyRankingItemDto a, BuyRankingItemDto b) {
        int symCmp = Objects.compare(a.symbol(), b.symbol(), Comparator.nullsLast(String::compareTo));
        if (symCmp != 0) {
            return symCmp;
        }
        return Objects.compare(a.figi(), b.figi(), Comparator.nullsLast(String::compareTo));
    }

    private int compareNullableAsc(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }

    private int compareNullableDesc(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }

    private int tierOrder(String tier) {
        if (BuyRankingDtos.TIER_1.equals(tier)) return 1;
        if (BuyRankingDtos.TIER_2.equals(tier)) return 2;
        if (BuyRankingDtos.TIER_3.equals(tier)) return 3;
        return 4;
    }

    private BigDecimal computeEtfTrendScore(String weeklyTrend, Boolean aboveSma200) {
        boolean up = "UPTREND".equals(weeklyTrend);
        boolean down = "DOWNTREND".equals(weeklyTrend);
        boolean above = Boolean.TRUE.equals(aboveSma200);
        boolean below = Boolean.FALSE.equals(aboveSma200);
        if (up && above) return HUNDRED;
        if (down && below) return BigDecimal.TEN;
        if (up || above) return new BigDecimal("65");
        return new BigDecimal("50");
    }

    /** 以同拆股基準近 20 日 OHLCV 計算成交額加權 20D Chaikin Money Flow（範圍 -1 至 +1）；若視窗內存在未知／缺失成交量則回傳 null 採中性分。 */
    static BigDecimal computeEtfCmf20d(List<NormalizedBarDto> bars) {
        if (bars == null || bars.size() < 20) {
            return null;
        }
        int start = bars.size() - 20;
        BigDecimal mfvSum = BigDecimal.ZERO;
        BigDecimal dollarVolSum = BigDecimal.ZERO;
        for (int i = start; i < bars.size(); i++) {
            NormalizedBarDto bar = bars.get(i);
            if (bar == null || bar.close() == null || bar.high() == null || bar.low() == null
                    || bar.volume() == null || bar.volume().status() != MetricStatus.AVAILABLE
                    || bar.volume().value() == null) {
                return null;
            }
            // 區分「已知零成交」（該日貢獻 0 但不破壞有效視窗）與「缺失／未核驗成交量」（直接回傳 null）
            if (bar.volume().value().signum() <= 0) {
                continue;
            }
            BigDecimal dollarVol = bar.close().multiply(bar.volume().value(), MC);
            if (dollarVol.signum() <= 0) {
                continue;
            }
            BigDecimal mfm = SectorMoneyFlowService.moneyFlowMultiplier(bar.high(), bar.low(), bar.close());
            mfvSum = mfvSum.add(mfm.multiply(dollarVol, MC), MC);
            dollarVolSum = dollarVolSum.add(dollarVol, MC);
        }
        if (dollarVolSum.signum() <= 0) {
            return null;
        }
        return mfvSum.divide(dollarVolSum, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeNetRr(BigDecimal netRr) {
        return normalizeLinear(netRr, BigDecimal.ZERO, MAX_RR_NORM, BigDecimal.ZERO);
    }

    private BigDecimal normalizeLinear(BigDecimal value, BigDecimal min, BigDecimal max, BigDecimal fallbackIfNull) {
        if (value == null) {
            return fallbackIfNull;
        }
        BigDecimal clamped = clamp(value, min, max);
        BigDecimal span = max.subtract(min, MC);
        if (span.signum() <= 0) {
            return fallbackIfNull;
        }
        return clamped.subtract(min, MC).divide(span, MC).multiply(HUNDRED, MC);
    }

    private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    private MarketBackgroundDto buildMarketBackground(
            PreloadedMarketData preloaded,
            LocalDate asOfDate,
            LocalDate lastClosedTradingDay,
            boolean staleDateFallback,
            LocalDate sectorFlowDate) {
        MarketRegime regime = MarketRegime.UNAVAILABLE;
        LocalDate marketDate = null;
        if (preloaded != null && preloaded.marketSnapshot() != null
                && preloaded.marketSnapshot().status() == MetricStatus.AVAILABLE
                && preloaded.marketSnapshot().value() != null) {
            regime = preloaded.marketSnapshot().value().regime();
            marketDate = preloaded.marketSnapshot().value().dataDate();
        }
        boolean ready = regime != null && regime != MarketRegime.UNAVAILABLE && asOfDate.equals(marketDate);
        BigDecimal vix = (preloaded != null && preloaded.vixClose() != null
                && preloaded.vixClose().status() == MetricStatus.AVAILABLE) ? preloaded.vixClose().value() : null;
        BigDecimal tenYear = preloaded != null ? preloaded.validatedTenYearYieldPct() : null;
        BigDecimal tenYearChange20 = preloaded != null ? preloaded.validatedTenYearChange20() : null;
        CreditSpreadSummaryDto credit = preloaded != null ? preloaded.creditSpread() : null;
        BigDecimal hyOasPct = credit != null ? credit.oasPct() : null;
        BigDecimal hyOasDelta = credit != null ? credit.deltaVsPrior20Bps() : null;
        String hyOasStatus = credit != null ? credit.status() : null;
        // 共用 ValuationSummaryCalculator.buildMacroCaution（含 10Y 20 期升幅 >= 0.30、VIX >= 25、HY OAS 與盤勢），避免市場背景與單列縮倉矛盾
        MacroCautionDto caution = summaryCalculator.buildMacroCaution(regime, vix, tenYearChange20, credit);
        boolean macroCaution = caution != null && caution.active();
        return new MarketBackgroundDto(
                regime == null ? MarketRegime.UNAVAILABLE.name() : regime.name(),
                vix,
                tenYear,
                hyOasPct,
                hyOasDelta,
                hyOasStatus,
                macroCaution,
                ready,
                marketDate,
                sectorFlowDate,
                lastClosedTradingDay,
                staleDateFallback
        );
    }

    private ValidatedParams validateAndNormalize(BuyRankingRequest request, String acceptLanguage) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "REQUEST_BODY_REQUIRED");
        }
        String scopeMode = request.scopeMode() == null ? "" : request.scopeMode().trim().toUpperCase(Locale.ROOT);
        if (!ALL_SCOPE_MODES.contains(scopeMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE_MODE");
        }

        List<String> rawIndustryTokens = new ArrayList<>();
        if (request.industryCodes() != null) {
            for (String s : request.industryCodes()) {
                if (s != null && !s.isBlank()) {
                    rawIndustryTokens.add(s.trim());
                }
            }
        }
        if (request.sectors() != null) {
            for (String s : request.sectors()) {
                if (s != null && !s.isBlank()) {
                    rawIndustryTokens.add(s.trim());
                }
            }
        }

        List<String> portfolioTypes;
        if (request.portfolioTypes() == null) {
            portfolioTypes = ALL_PORTFOLIO_TYPES;
        } else {
            Set<String> parsed = new LinkedHashSet<>();
            for (String pt : request.portfolioTypes()) {
                if (pt == null || pt.isBlank()) continue;
                String norm = pt.trim().toUpperCase(Locale.ROOT);
                if (!ALL_PORTFOLIO_TYPES.contains(norm)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PORTFOLIO_TYPE:" + pt);
                }
                parsed.add(norm);
            }
            if (BuyRankingDtos.SCOPE_PORTFOLIO.equals(scopeMode) && parsed.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PORTFOLIO_TYPES_REQUIRED");
            }
            portfolioTypes = parsed.isEmpty() ? ALL_PORTFOLIO_TYPES : List.copyOf(parsed);
        }

        Set<String> customSet = new LinkedHashSet<>();
        if (request.customSymbols() != null) {
            for (String entry : request.customSymbols()) {
                if (entry == null || entry.isBlank()) continue;
                for (String token : SYMBOL_SPLIT_PATTERN.split(entry.trim())) {
                    if (!token.isBlank()) {
                        customSet.add(token.trim().toUpperCase(Locale.ROOT));
                    }
                }
            }
        }

        Set<String> assetTypes = new LinkedHashSet<>();
        if (request.assetTypes() == null) {
            assetTypes.addAll(DEFAULT_ASSET_TYPES);
        } else {
            for (String at : request.assetTypes()) {
                if (at == null || at.isBlank()) continue;
                String norm = at.trim().toUpperCase(Locale.ROOT);
                if (!ALL_ASSET_TYPES.contains(norm)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ASSET_TYPE:" + at);
                }
                assetTypes.add(norm);
            }
            if (assetTypes.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ASSET_TYPES_REQUIRED");
            }
        }
        boolean excludeReits = request.excludeReits() == null || request.excludeReits();

        Set<String> tiers = new LinkedHashSet<>();
        if (request.tiers() == null) {
            tiers.addAll(ALL_TIERS);
        } else {
            for (String t : request.tiers()) {
                if (t == null || t.isBlank()) continue;
                String norm = t.trim().toUpperCase(Locale.ROOT);
                if (!ALL_TIERS.contains(norm)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TIER:" + t);
                }
                tiers.add(norm);
            }
        }

        String minGrade = request.minFundamentalGrade() == null || request.minFundamentalGrade().isBlank()
                ? BuyRankingDtos.GRADE_ALL
                : request.minFundamentalGrade().trim().toUpperCase(Locale.ROOT);
        if (!ALL_FUNDAMENTAL_GRADES.contains(minGrade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FUNDAMENTAL_GRADE");
        }

        BigDecimal minRr = request.minNetRiskReward();
        if (minRr != null && minRr.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MIN_NET_RR");
        }

        Integer excludeEarningsDays = request.excludeEarningsWithinDays();
        if (excludeEarningsDays != null && excludeEarningsDays < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EXCLUDE_EARNINGS_DAYS");
        }

        String sortBy = request.sortBy() == null || request.sortBy().isBlank()
                ? BuyRankingDtos.SORT_TIER_COMPOSITE
                : request.sortBy().trim().toUpperCase(Locale.ROOT);
        if (!ALL_SORT_OPTIONS.contains(sortBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_SORT_BY");
        }

        AnalysisHorizon horizon = AnalysisHorizon.POSITION;
        if (request.horizon() != null && !request.horizon().isBlank()) {
            String h = request.horizon().trim().toUpperCase(Locale.ROOT);
            if (!ALL_HORIZONS.contains(h)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HORIZON");
            }
            horizon = AnalysisHorizon.valueOf(h);
        }

        int page = request.page() == null ? 1 : request.page();
        int pageSize = request.pageSize() == null ? BuyRankingDtos.MAX_CANDIDATES : request.pageSize();
        if (page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (pageSize < 1 || pageSize > BuyRankingDtos.MAX_CANDIDATES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_SIZE");
        }

        String resolvedLocale = BuyRankingReasonFormatter.normalizeLocale(request.locale(), acceptLanguage);
        return new ValidatedParams(
                scopeMode, List.copyOf(rawIndustryTokens), portfolioTypes, List.copyOf(customSet),
                assetTypes, excludeReits, tiers, minGrade, minRr, excludeEarningsDays,
                sortBy, horizon, resolvedLocale, page, pageSize);
    }

    private BuyRankingResponse toPagedResponse(CachedReportSnapshot snapshot, int page, int pageSize) {
        List<BuyRankingItemDto> all = snapshot.fullSortedItems();
        long fromLong = (long) (page - 1) * (long) pageSize;
        List<BuyRankingItemDto> sliced;
        if (fromLong < 0L || fromLong >= all.size()) {
            sliced = List.of();
        } else {
            int fromIdx = (int) fromLong;
            int toIdx = (int) Math.min((long) all.size(), fromLong + (long) pageSize);
            sliced = List.copyOf(all.subList(fromIdx, toIdx));
        }
        return new BuyRankingResponse(
                snapshot.reportId(),
                snapshot.asOfDate(),
                snapshot.generatedAtHk(),
                snapshot.observationCutoff(),
                snapshot.resolvedLocale(),
                snapshot.marketContext().marketRegime(),
                snapshot.marketContext(),
                snapshot.scopeSummary(),
                all.size(),
                page,
                pageSize,
                snapshot.tierCounts(),
                snapshot.appliedFilters(),
                sliced
        );
    }

    private CachedReportSnapshot reLocalizeSnapshot(CachedReportSnapshot src, String targetLocale) {
        List<BuyRankingItemDto> localized = new ArrayList<>(src.fullSortedItems().size());
        for (BuyRankingItemDto item : src.fullSortedItems()) {
            String reason = BuyRankingReasonFormatter.formatReason(item.reasonCode(), item.reasonParams(), targetLocale);
            localized.add(new BuyRankingItemDto(
                    item.rank(), item.recommendedRank(), item.tier(), item.tierScore(),
                    item.figi(), item.symbol(), item.stockName(), item.currency(),
                    item.industryCode(), item.sector(), item.assetType(), item.rawStockType(),
                    item.verdict(), item.fundamentalBacking(), item.stance(), item.breakoutDiagnosis(),
                    item.currentPrice(), item.buyZone(), item.distanceToBuyZonePct(), item.nearSupportWithin3Pct(),
                    item.buyZoneSource(), item.stopLossPrice(), item.stopLossDistancePct(),
                    item.firstTargetPrice(), item.netRiskReward(), item.planNetRiskReward(),
                    item.suggestedShares(), item.positionScaleTag(), item.positionSizingStatus(),
                    item.positionSizingActionable(), item.positionSizingReasonCode(),
                    item.sectorConcentrationWarning(), item.positionSizingTags(),
                    item.valuationDiscountPct(), item.forwardPeg(), item.forwardPe(),
                    item.roic(), item.adjFcfTtm(), item.reverseDcfImpliedGrowth(),
                    item.reverseDcfBasis(), item.reverseDcfStatus(),
                    item.etfTrendScore(), item.etfCmf20d(), item.etfRs60dVsSpy(),
                    item.nextEarningsDate(), item.daysToEarnings(),
                    item.sectorFlowState(), item.sectorCmf20d(),
                    item.reasonCode(), item.reasonParams(), reason));
        }
        return new CachedReportSnapshot(
                src.reportId(), src.createdAt(), src.asOfDate(), src.generatedAtHk(),
                src.observationCutoff(), targetLocale, src.marketContext(), src.scopeSummary(),
                src.tierCounts(), src.appliedFilters(), List.copyOf(localized));
    }

    private void putSnapshot(CachedReportSnapshot snapshot) {
        synchronized (snapshotCache) {
            snapshotCache.put(snapshot.reportId(), snapshot);
        }
    }

    private CachedReportSnapshot getSnapshotIfValid(String reportId) {
        Instant now = dateTimeService.now();
        synchronized (snapshotCache) {
            CachedReportSnapshot found = snapshotCache.get(reportId);
            if (found == null) {
                return null;
            }
            if (found.createdAt() != null && Duration.between(found.createdAt(), now).compareTo(SNAPSHOT_TTL) > 0) {
                snapshotCache.remove(reportId);
                return null;
            }
            return found;
        }
    }

    private BuyRankingItemDto withRanks(BuyRankingItemDto item, int rank, int recommendedRank) {
        return new BuyRankingItemDto(
                rank, recommendedRank, item.tier(), item.tierScore(),
                item.figi(), item.symbol(), item.stockName(), item.currency(),
                item.industryCode(), item.sector(), item.assetType(), item.rawStockType(),
                item.verdict(), item.fundamentalBacking(), item.stance(), item.breakoutDiagnosis(),
                item.currentPrice(), item.buyZone(), item.distanceToBuyZonePct(), item.nearSupportWithin3Pct(),
                item.buyZoneSource(), item.stopLossPrice(), item.stopLossDistancePct(),
                item.firstTargetPrice(), item.netRiskReward(), item.planNetRiskReward(),
                item.suggestedShares(), item.positionScaleTag(), item.positionSizingStatus(),
                item.positionSizingActionable(), item.positionSizingReasonCode(),
                item.sectorConcentrationWarning(), item.positionSizingTags(),
                item.valuationDiscountPct(), item.forwardPeg(), item.forwardPe(),
                item.roic(), item.adjFcfTtm(), item.reverseDcfImpliedGrowth(),
                item.reverseDcfBasis(), item.reverseDcfStatus(),
                item.etfTrendScore(), item.etfCmf20d(), item.etfRs60dVsSpy(),
                item.nextEarningsDate(), item.daysToEarnings(),
                item.sectorFlowState(), item.sectorCmf20d(),
                item.reasonCode(), item.reasonParams(), item.actionReason());
    }

    private boolean isReitCandidate(String stockType, String modelCode) {
        return SecurityTypePolicy.isReitType(stockType) || "REIT".equalsIgnoreCase(modelCode);
    }

    private String classifyAssetType(String stockType, String modelCode) {
        if (SecurityTypePolicy.isEtfType(stockType)) return BuyRankingDtos.ASSET_ETF;
        if (SecurityTypePolicy.isAdrType(stockType)) return BuyRankingDtos.ASSET_ADR;
        if (isReitCandidate(stockType, modelCode)) return BuyRankingDtos.ASSET_REIT;
        if (SecurityTypePolicy.isOperatingCompanyType(stockType)) return BuyRankingDtos.ASSET_COMMON_STOCK;
        return null;
    }

    private boolean isAssetAllowed(String stockType, String modelCode, String assetType,
                                   Set<String> selectedAssets, boolean excludeReits) {
        // 對原始證券類型與估值模型獨立做 REIT 排除，避免 ADR 型 REIT（stockType=ADR, modelCode=REIT）漏網
        if (isReitCandidate(stockType, modelCode)) {
            if (excludeReits) {
                return false;
            }
            return selectedAssets.contains(BuyRankingDtos.ASSET_REIT) || selectedAssets.contains(assetType);
        }
        return selectedAssets.contains(assetType);
    }

    /** 檢查估值主原因碼與全部倍數分項原因碼，防止多股類未核實（如 PE 可用但 PB/PS 標記 SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED）漏網。 */
    private String findUnverifiedValuationReason(StockValuationLatest v) {
        if (v == null) {
            return null;
        }
        ValuationReason[] reasons = new ValuationReason[]{
                v.getReasonCode(), v.getPeReason(), v.getPbReason(),
                v.getPsReason(), v.getEvEbitdaReason(), v.getPfcfReason()
        };
        for (ValuationReason r : reasons) {
            if (r != null && UNVERIFIED_VALUATION_REASONS.contains(r.name())) {
                return r.name();
            }
        }
        return null;
    }

    /** 嚴格取得選定支撐區之同區破位診斷；僅於無選定支撐區時才回退 primaryDiagnosis。 */
    private BreakoutDiagnosisDto findMatchedZoneDiagnosis(
            MetricResult<SupportResistanceDto> support, LevelZoneDto supportZone) {
        if (support == null || support.status() != MetricStatus.AVAILABLE || support.value() == null) {
            return null;
        }
        if (supportZone == null) {
            return support.value().breakoutDiagnosis();
        }
        if (support.value().zoneDiagnoses() != null) {
            for (BreakoutDiagnosisDto d : support.value().zoneDiagnoses()) {
                if (d != null && d.matchesZone(supportZone)) {
                    return d;
                }
            }
        }
        BreakoutDiagnosisDto primary = support.value().breakoutDiagnosis();
        return primary != null && primary.matchesZone(supportZone) ? primary : null;
    }

    /** 判定是否屬硬性跌破保護止損或技術失效（不可被 SUSPECTED_SHAKEOUT 豁免）。 */
    private boolean isHardStopBreachedOrInvalidated(TradePlanDto matchedPlan, BigDecimal evalPrice, BigDecimal stopLoss) {
        if (matchedPlan != null) {
            if (matchedPlan.status() == TradePlanStatus.INVALIDATED) {
                return true;
            }
            if (hasPlanReason(matchedPlan, "PROTECTIVE_STOP_BREACHED")
                    || hasPlanReason(matchedPlan, "TECHNICAL_INVALIDATION_CONFIRMED")) {
                return true;
            }
        }
        return evalPrice != null && stopLoss != null && stopLoss.signum() > 0
                && evalPrice.compareTo(stopLoss) <= 0;
    }

    /** 檢核計畫是否明確僅因「現價高於入場區上限」（ENTRY_PRICE_ABOVE_CAP）等待回踩；無計畫、缺原因證據或含其他阻擋原因一律留 Tier 3。 */
    private boolean isOnlyWaitingForPullbackAboveZone(TradePlanDto matchedPlan) {
        if (matchedPlan == null || matchedPlan.status() != TradePlanStatus.WAITING_TRIGGER) {
            return false;
        }
        if (matchedPlan.reasons() == null || matchedPlan.reasons().isEmpty()) {
            return false;
        }
        boolean hasAboveCap = false;
        for (com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto r : matchedPlan.reasons()) {
            if (r == null || r.code() == null) {
                continue;
            }
            if ("ENTRY_PRICE_ABOVE_CAP".equals(r.code())) {
                hasAboveCap = true;
            } else {
                return false;
            }
        }
        return hasAboveCap;
    }

    private boolean hasPlanReason(TradePlanDto plan, String code) {
        if (plan == null || plan.reasons() == null) {
            return false;
        }
        for (com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto r : plan.reasons()) {
            if (r != null && code.equals(r.code())) {
                return true;
            }
        }
        return false;
    }

    /** ETF 於 CORRECTION 修正期檢查同區右側確認依據（有效 confirmed retest 或同區有效 SPRING_RECLAIMED）。 */
    private String resolveEtfRightSideBasis(
            LevelZoneDto supportZone, BreakoutDiagnosisDto matchedDiag, TradePlanDto matchedPlan, LocalDate asOfDate) {
        if (supportZone == null) {
            return null;
        }
        if (matchedDiag != null && matchedDiag.matchesZone(supportZone)
                && matchedDiag.diagnosis() == BreakoutDiagnosis.SPRING_RECLAIMED
                && matchedDiag.reclaimDate() != null
                && (matchedDiag.validThroughSession() == null || asOfDate == null
                || !asOfDate.isAfter(matchedDiag.validThroughSession()))) {
            return "SPRING_RECLAIMED";
        }
        if (supportZone.retestState() == com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState.SUCCEEDED
                && supportZone.roleVersion() > 1
                && supportZone.roleConfirmationPrice() != null && supportZone.roleConfirmationPrice().signum() > 0
                && supportZone.roleConfirmedAt() != null) {
            if (matchedPlan != null && (matchedPlan.status() == TradePlanStatus.INVALIDATED
                    || hasPlanReason(matchedPlan, "ENTRY_EXPIRED")
                    || (asOfDate != null && matchedPlan.validThroughSession() != null
                    && asOfDate.isAfter(matchedPlan.validThroughSession())))) {
                return null;
            }
            return "CONFIRMED_RETEST";
        }
        return null;
    }

    private String buildPortfolioScopeLabel(List<String> portfolioTypes) {
        if (portfolioTypes.size() == 1) {
            return portfolioTypes.getFirst();
        }
        return "Portfolio";
    }

    private MarketRegime parseMarketRegime(String raw) {
        if (raw == null || raw.isBlank()) return MarketRegime.UNAVAILABLE;
        try {
            return MarketRegime.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return MarketRegime.UNAVAILABLE;
        }
    }

    private LevelZoneDto findZoneById(MetricResult<SupportResistanceDto> support, String zoneId) {
        if (zoneId == null || support == null || support.status() != MetricStatus.AVAILABLE || support.value() == null) {
            return null;
        }
        for (LevelZoneDto z : support.value().supports()) {
            if (z != null && zoneId.equals(z.id())) return z;
        }
        for (LevelZoneDto z : support.value().containingZones()) {
            if (z != null && zoneId.equals(z.id())) return z;
        }
        return null;
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

    private boolean hasFlag(InvestmentSummaryDto summary, String code) {
        if (summary == null || summary.risks() == null) return false;
        for (InvestmentSummaryDto.SummaryFlagDto flag : summary.risks()) {
            if (flag != null && code.equals(flag.code())) return true;
        }
        return false;
    }

    /** 唯讀本地 PostgreSQL 既有預測快取，嚴禁觸發外部 Alpha Vantage API。 */
    private FutuObservationsDto buildReadOnlyForwardObservations(
            LocalDate asOfDate,
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
                asOfDate, obsCutoff, currency, closePrice, marketCap, null,
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

    private FxRateLookup preloadBatchFx(Map<String, List<StockFinancialPeriod>> periodsByFigi, LocalDate asOfDate) {
        if (fxRateService == null || periodsByFigi == null || periodsByFigi.isEmpty() || asOfDate == null) {
            return FxRateLookup.EMPTY;
        }
        Set<String> currencies = new LinkedHashSet<>();
        for (List<StockFinancialPeriod> list : periodsByFigi.values()) {
            for (StockFinancialPeriod p : list) {
                if (p != null && p.getReportingCurrency() != null && !p.getReportingCurrency().isBlank()
                        && !"USD".equalsIgnoreCase(p.getReportingCurrency().trim())) {
                    currencies.add(p.getReportingCurrency().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (currencies.isEmpty()) {
            return FxRateLookup.EMPTY;
        }
        return fxRateService.preload(currencies, asOfDate, asOfDate);
    }

    private Map<String, AdrShareRatio> preloadBatchAdrRatios(List<CandidateEntry> candidates) {
        if (adrShareRatioRepository == null || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        List<String> adrSymbols = new ArrayList<>();
        for (CandidateEntry c : candidates) {
            if (c != null && c.stock() != null && SecurityTypePolicy.isAdrType(c.stock().getStockType())
                    && c.stock().getSymbol() != null && !c.stock().getSymbol().isBlank()) {
                adrSymbols.add(c.stock().getSymbol().trim().toUpperCase(Locale.ROOT));
            }
        }
        if (adrSymbols.isEmpty()) {
            return Map.of();
        }
        Map<String, AdrShareRatio> out = new HashMap<>();
        for (AdrShareRatio ratio : adrShareRatioRepository.findBySymbolIn(adrSymbols)) {
            if (ratio != null && ratio.getSymbol() != null) {
                out.put(ratio.getSymbol().trim().toUpperCase(Locale.ROOT), ratio);
            }
        }
        return out;
    }

    private FxResolution resolveFxForCandidate(FxRateLookup fxLookup, StockFinancialPeriod matchedPeriod,
                                               String quoteCurrency, LocalDate asOfDate) {
        if (matchedPeriod == null) {
            return null;
        }
        String rep = matchedPeriod.getReportingCurrency() == null ? null : matchedPeriod.getReportingCurrency().trim();
        String quote = quoteCurrency == null ? null : quoteCurrency.trim();
        // 財報幣別缺失時嚴禁靜默當成 USD，回傳正式 REPORTING_CURRENCY_MISMATCH 攔截至 Tier 4
        if (rep == null || rep.isEmpty() || quote == null || quote.isEmpty()) {
            return FxResolution.unavailable(rep, quote, ValuationReason.REPORTING_CURRENCY_MISMATCH);
        }
        if (rep.equalsIgnoreCase(quote)) {
            return FxResolution.identity(quote.toUpperCase(Locale.ROOT));
        }
        if (fxLookup == null) {
            return FxResolution.unavailable(rep, quote, ValuationReason.FX_RATE_UNAVAILABLE);
        }
        return fxLookup.resolve(rep, quote, asOfDate);
    }

    private BigDecimal extractAvailableValue(MetricResult<BigDecimal> metric) {
        return metric != null && metric.status() == MetricStatus.AVAILABLE ? metric.value() : null;
    }

    private BigDecimal scale4(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }
}
