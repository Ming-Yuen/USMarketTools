package com.ykm.stock.analytics.service.analysis;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.TrendPullbackStarterDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RiskRewardDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.FigiKnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.KnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.calculator.ExecutionCostCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.RadarEvaluationResult;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator.SelectedRevisionTarget;
import com.ykm.stock.analytics.service.valuation.FundamentalSnapshotCalculator;
import com.ykm.stock.analytics.service.valuation.IndustryValuationProposalService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.DayEpsResolution;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 每日收市雙軌機會雷達（兩階段掃描架構）：
 * 階段一：單一 SQL 從 stock_valuation_latest 初篩候選股（自動排除 REIT 與未來 14 日財報，次清單僅讀 PostgreSQL 既有年度共識快取）。
 * 階段二：分批載入 5 年日線與共用大盤快取，直接複用單股分析核心服務完成技術精算並原子寫入 daily_opportunity_radar。
 * 嚴禁於批次迴圈內呼叫 AnnualEarningsForecastSyncService 或任何外部 API。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailyOpportunityRadarService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int TECH_BATCH_SIZE = 50;
    private static final long EARNINGS_EXCLUSION_CALENDAR_DAYS = 14L;
    private static final BigDecimal MAX_SUPPORT_UPPER_FACTOR = new BigDecimal("1.03");
    private static final BigDecimal HALF_ATR = new BigDecimal("0.50");
    private static final List<String> VALUE_VERDICTS = List.of(
            "DEEP_UNDERVALUED", "MODERATELY_UNDERVALUED", "FAIR_LOWER");
    private static final List<String> VALUE_BACKINGS = List.of("STRONG", "MIXED");

    private final StockValuationLatestRepository latestValuations;
    private final StockQuoteRepository quotes;
    private final StockSplitRepository splits;
    private final StockStatusRepository statuses;
    private final StockFinancialPeriodRepository financialPeriods;
    private final FinancialReportRepository financialReports;
    private final StockEarningsForecastRepository forecasts;
    private final MarketEventRepository marketEvents;
    private final StockIndustryRepository industries;
    private final SectorMoneyFlowDailyRepository sectorMoneyFlows;
    private final DailyOpportunityRadarRepository radarRepository;
    private final DailyOpportunityRadarRunRepository radarRunRepository;

    private final QuoteNormalizationService quoteNormalization;
    private final PriceStructureService priceStructures;
    private final MarketContextService marketContextService;
    private final SupportResistanceService supportResistanceService;
    private final TradePlanService tradePlanService;
    private final SignalTrackingService signalTrackingService;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();
    private final FundamentalSnapshotCalculator fundamentalCalculator = new FundamentalSnapshotCalculator();
    private final ForwardValuationCalculator forwardCalculator = new ForwardValuationCalculator();
    private final ExecutionCostCalculator costCalculator = new ExecutionCostCalculator();

    private record CandidateRow(StockValuationLatest valuation, Stock stock, boolean valueTrack, boolean starterTrack) {}

    /**
     * 執行指定美東收市交易日之兩階段每日機會雷達掃描，完成後於短交易原子發布快照與發布紀錄（含零入選日）。
     * 若該交易日已存在完整發布紀錄，直接沿用已凍結快照避免覆寫歷史名單。
     */
    public List<DailyOpportunityRadar> refreshDaily(LocalDate radarDate, Instant observationCutoff) {
        if (radarDate == null) {
            return List.of();
        }
        if (radarRunRepository.existsById(radarDate)) {
            List<DailyOpportunityRadar> existing = radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(radarDate);
            log.info("Daily opportunity radar already published | radarDate={} rows={}", radarDate, existing.size());
            return existing;
        }
        Instant startedAt = dateTimeService.now();
        Instant obsCutoff = observationCutoff != null ? observationCutoff : startedAt;
        Instant sessionCutoff = calendar.getSessionCloseAt(radarDate);
        LocalDate earningsCutoff = radarDate.plusDays(EARNINGS_EXCLUSION_CALENDAR_DAYS);
        LocalDate maxAnnualObsDate = obsCutoff.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate minAnnualObsDate = radarDate.minusDays(ForwardValuationCalculator.MAX_FORECAST_STALE_DAYS);

        // 0. 預載共用大盤上下文（SPY / QQQ 272 交易日 / VIX / Treasury / 宏觀事件）與當日板塊資金流快照
        PreloadedMarketData preloadedMarket = marketContextService.preloadMarketData(radarDate, obsCutoff);
        MarketRegime globalRegime = preloadedMarket.marketSnapshot() != null
                && preloadedMarket.marketSnapshot().status() == MetricStatus.AVAILABLE
                && preloadedMarket.marketSnapshot().value() != null
                ? preloadedMarket.marketSnapshot().value().regime() : null;
        LocalDate marketDataDate = preloadedMarket.marketSnapshot() != null
                && preloadedMarket.marketSnapshot().status() == MetricStatus.AVAILABLE
                && preloadedMarket.marketSnapshot().value() != null
                ? preloadedMarket.marketSnapshot().value().dataDate() : null;
        // 上游大盤行情未同步至當日或狀態不可用時，直接中止且不寫入發布紀錄，避免提前凍結空清單
        if (globalRegime == null || globalRegime == MarketRegime.UNAVAILABLE || !radarDate.equals(marketDataDate)) {
            log.warn("Daily opportunity radar deferred: market context not ready | radarDate={} regime={} marketDataDate={}",
                    radarDate, globalRegime, marketDataDate);
            return List.of();
        }
        boolean headwindRegime = globalRegime == MarketRegime.BEAR || globalRegime == MarketRegime.PANIC;

        Map<String, String> industryNames = industries.findAll().stream()
                .collect(Collectors.toMap(StockIndustry::getCode, StockIndustry::getIndustry, (a, b) -> a));
        Map<String, SectorMoneyFlowDaily> sectorFlowMap = sectorMoneyFlows
                .findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(radarDate).stream()
                .collect(Collectors.toMap(SectorMoneyFlowDaily::getIndustryCode, row -> row, (a, b) -> a));

        // 1. 階段一：單一 SQL 初篩（大盤 BEAR/PANIC 時主清單預先收緊至 DEEP_UNDERVALUED + STRONG，次清單跳過）
        List<String> valueVerdicts = headwindRegime ? List.of("DEEP_UNDERVALUED") : VALUE_VERDICTS;
        List<String> valueBackings = headwindRegime ? List.of("STRONG") : VALUE_BACKINGS;
        List<Object[]> rawValue = latestValuations.findValueRadarCandidates(
                radarDate, earningsCutoff, obsCutoff, SecurityTypePolicy.OPERATING_STOCK_TYPES, valueVerdicts, valueBackings);

        List<Object[]> rawStarter = headwindRegime || globalRegime == MarketRegime.CORRECTION
                ? List.of()
                : latestValuations.findStarterRadarCandidates(
                        radarDate, earningsCutoff, minAnnualObsDate, maxAnnualObsDate, obsCutoff,
                        SecurityTypePolicy.OPERATING_STOCK_TYPES);

        Map<String, CandidateRow> candidateMap = new LinkedHashMap<>();
        for (Object[] tuple : rawValue) {
            StockValuationLatest v = (StockValuationLatest) tuple[0];
            Stock s = (Stock) tuple[1];
            candidateMap.put(v.getFigi(), new CandidateRow(v, s, true, false));
        }
        for (Object[] tuple : rawStarter) {
            StockValuationLatest v = (StockValuationLatest) tuple[0];
            Stock s = (Stock) tuple[1];
            CandidateRow existing = candidateMap.get(v.getFigi());
            if (existing != null) {
                candidateMap.put(v.getFigi(), new CandidateRow(v, s, true, true));
            } else {
                candidateMap.put(v.getFigi(), new CandidateRow(v, s, false, true));
            }
        }

        List<CandidateRow> candidates = new ArrayList<>(candidateMap.values());
        List<DailyOpportunityRadar> published = new ArrayList<>();
        LocalDate fiveYearsAgo = radarDate.minusYears(5);
        // 涵蓋最遠合法最新預期（D-45）再回溯 90D 基期上限（120 天）之完整歷史視窗（>= 165 天）
        LocalDate forecastHistoryFrom = maxAnnualObsDate.minusDays(
                ForwardValuationCalculator.MAX_FORECAST_STALE_DAYS + ForwardValuationCalculator.REV_90D_MAX_DAYS + 10L);

        // 2. 階段二：分批（每批 50 檔）載入 5 年日線與唯讀前瞻快取，複用單股分析核心精算
        for (List<CandidateRow> batch : Lists.partition(candidates, TECH_BATCH_SIZE)) {
            List<String> batchFigis = batch.stream().map(c -> c.valuation().getFigi()).toList();
            Map<String, List<QuoteBarRow>> quotesByFigi = new HashMap<>();
            for (QuoteBarRow bar : quotes.findQuoteBarsOrdered(batchFigis, fiveYearsAgo, radarDate)) {
                quotesByFigi.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
            }
            Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
            for (StockSplit sp : splits.findByFigiInOrderByExecutionDateAsc(batchFigis)) {
                splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
            }
            Map<String, StockStatus> statusByFigi = statuses.findAllById(batchFigis).stream()
                    .collect(Collectors.toMap(StockStatus::getFigi, row -> row, (a, b) -> a));
            Map<String, List<MarketEvent>> earningsByFigi = new HashMap<>();
            for (MarketEvent ev : marketEvents.findVisibleEarningsForFigis(batchFigis, obsCutoff)) {
                earningsByFigi.computeIfAbsent(ev.getFigi(), k -> new ArrayList<>()).add(ev);
            }
            Map<String, List<StockEarningsForecast>> forecastsByFigi = new HashMap<>();
            for (StockEarningsForecast f : forecasts.findVisibleForStocksBetween(batchFigis, forecastHistoryFrom, maxAnnualObsDate, obsCutoff)) {
                forecastsByFigi.computeIfAbsent(f.getFigi(), k -> new ArrayList<>()).add(f);
            }
            Map<String, List<StockFinancialPeriod>> periodsByFigi = new HashMap<>();
            for (StockFinancialPeriod p : financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(batchFigis)) {
                periodsByFigi.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
            }
            Map<String, List<KnownReportPeriod>> knownReportsByFigi = new HashMap<>();
            for (FigiKnownReportPeriod r : financialReports.findKnownReportPeriodsForFigis(batchFigis, obsCutoff)) {
                knownReportsByFigi.computeIfAbsent(r.figi(), k -> new ArrayList<>())
                        .add(new KnownReportPeriod(r.periodEnd(), r.periodType(), r.fiscalYear(), r.period(), r.observedAt()));
            }

            for (CandidateRow candidate : batch) {
                evaluateCandidate(
                        candidate, radarDate, sessionCutoff, obsCutoff, startedAt, fiveYearsAgo, maxAnnualObsDate,
                        preloadedMarket, headwindRegime, industryNames, sectorFlowMap,
                        quotesByFigi.get(candidate.valuation().getFigi()),
                        splitsByFigi.getOrDefault(candidate.valuation().getFigi(), List.of()),
                        statusByFigi.get(candidate.valuation().getFigi()),
                        earningsByFigi.getOrDefault(candidate.valuation().getFigi(), List.of()),
                        forecastsByFigi.getOrDefault(candidate.valuation().getFigi(), List.of()),
                        periodsByFigi.getOrDefault(candidate.valuation().getFigi(), List.of()),
                        knownReportsByFigi.getOrDefault(candidate.valuation().getFigi(), List.of()),
                        published
                );
            }
        }

        long valueCount = published.stream().filter(r -> DailyOpportunityRadar.TRACK_VALUE_SUPPORT.equals(r.getTrackType())).count();
        long starterCount = published.stream().filter(r -> DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER.equals(r.getTrackType())).count();
        Instant publishedAt = dateTimeService.now();

        // 3. 短交易原子發布當日雷達明細、發布紀錄（含零入選日）與合格訊號帳本，避免發布後停機導致跨日永久漏記訊號
        List<DailyOpportunityRadar> finalRows = transactions.execute(tx -> {
            if (radarRunRepository.existsById(radarDate)) {
                return radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(radarDate);
            }
            radarRepository.deleteByRadarDate(radarDate);
            radarRepository.flush();
            for (DailyOpportunityRadar row : published) {
                entityManager.persist(row);
            }
            DailyOpportunityRadarRun run = new DailyOpportunityRadarRun();
            run.setRadarDate(radarDate);
            run.setMarketRegime(globalRegime.name());
            run.setHeadwindActive(headwindRegime);
            run.setCandidateCount(candidates.size());
            run.setValueCount((int) valueCount);
            run.setStarterCount((int) starterCount);
            run.setObservationCutoff(obsCutoff);
            run.setPublishedAt(publishedAt);
            entityManager.persist(run);
            if (signalTrackingService != null) {
                signalTrackingService.recordSignalsInCurrentTx(radarDate, published, publishedAt);
            }
            entityManager.flush();
            return published;
        });

        log.info("Daily opportunity radar refreshed | radarDate={} regime={} candidates={} publishedValue={} publishedStarter={}",
                radarDate, globalRegime, candidates.size(), valueCount, starterCount);
        return finalRows == null ? List.of() : finalRows;
    }

    private void evaluateCandidate(
            CandidateRow candidate,
            LocalDate radarDate,
            Instant sessionCutoff,
            Instant obsCutoff,
            Instant calculatedAt,
            LocalDate fiveYearsAgo,
            LocalDate maxAnnualObsDate,
            PreloadedMarketData preloadedMarket,
            boolean headwindRegime,
            Map<String, String> industryNames,
            Map<String, SectorMoneyFlowDaily> sectorFlowMap,
            List<QuoteBarRow> rawQuotes,
            List<StockSplit> stockSplits,
            StockStatus status,
            List<MarketEvent> stockEarnings,
            List<StockEarningsForecast> stockForecasts,
            List<StockFinancialPeriod> stockPeriods,
            List<KnownReportPeriod> knownReports,
            List<DailyOpportunityRadar> out) {
        StockValuationLatest v = candidate.valuation();
        Stock s = candidate.stock();
        String figi = v.getFigi();

        if (rawQuotes == null || rawQuotes.isEmpty() || !radarDate.equals(rawQuotes.getLast().quoteDate())) {
            return;
        }
        AnalysisContext context = new AnalysisContext(figi, sessionCutoff, obsCutoff, AnalysisHorizon.POSITION);
        MetricResult<NormalizedPriceSeriesDto> price = quoteNormalization.normalizeFromLoaded(
                context, fiveYearsAgo, radarDate, s, status, rawQuotes, stockSplits);
        if (price.status() != MetricStatus.AVAILABLE || price.value() == null || price.value().bars().isEmpty()) {
            return;
        }
        MetricResult<PriceStructureDto> structure = priceStructures.analyze(context, price.value());
        if (structure.status() != MetricStatus.AVAILABLE || structure.value() == null) {
            return;
        }

        List<MarketEvent> pastEarnings = new ArrayList<>();
        List<MarketEvent> windowEvents = new ArrayList<>();
        LocalDate windowFrom = radarDate.minusDays(10);
        LocalDate windowThrough = radarDate.plusDays(20);
        LocalDate nextEarningsDate = null;
        for (MarketEvent ev : stockEarnings) {
            if (ev.getEventDate() == null) continue;
            if (!ev.getEventDate().isAfter(radarDate)) {
                pastEarnings.add(ev);
            } else if (nextEarningsDate == null || ev.getEventDate().isBefore(nextEarningsDate)) {
                nextEarningsDate = ev.getEventDate();
            }
            if (!ev.getEventDate().isBefore(windowFrom) && !ev.getEventDate().isAfter(windowThrough)) {
                windowEvents.add(ev);
            }
        }

        MetricResult<MarketContextDto> marketResult = marketContextService.evaluateWithPreloaded(
                context, price.value(), structure.value(), pastEarnings, windowEvents, preloadedMarket);
        MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
        MetricResult<SupportResistanceDto> support = supportResistanceService.analyze(
                context, price.value(), structure.value(), marketValue);
        MetricResult<TradePlanResultDto> tradePlans = MetricResult.unavailable(
                MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        if (support.status() == MetricStatus.AVAILABLE) {
            PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
            tradePlans = tradePlanService.build(context, price.value(), structure.value(), support.value(), constraint);
        }

        // 純記憶體計算基本面與唯讀前瞻指標（共用 StockValuationService 正式財季配對與 EPS 分母規則，0 次外部 API、0 次額外 DB 查詢）
        BigDecimal latestClose = price.value().bars().getLast().close();
        BigDecimal evalPrice = v.getClosePrice() != null && v.getClosePrice().signum() > 0 ? v.getClosePrice() : latestClose;
        StockFinancialPeriod matchedPeriod = StockValuationService.matchPeriod(stockPeriods, radarDate);
        String splitError = StockValuationService.splitSyncError(status);
        DayEpsResolution epsResolution = StockValuationService.resolveDayEps(s.getCurrency(), status, matchedPeriod, stockSplits, radarDate);
        BigDecimal epsAtDay = epsResolution.epsAtDay();
        String peReason = v.getPeReason() != null ? v.getPeReason().name() : epsResolution.peReason();
        boolean financialInstitution = IndustryValuationProposalService.isFinancialInstitutionModel(v.getModelCode());
        FundamentalSnapshotDto fundamentalsDto = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                stockPeriods, matchedPeriod, stockSplits, radarDate, s.getCurrency(),
                evalPrice, v.getMarketCap(), null, epsAtDay, peReason, v.getPe(),
                financialInstitution, splitError));
        FutuObservationsDto futuObs = buildReadOnlyForwardObservations(
                radarDate, obsCutoff, maxAnnualObsDate, s.getCurrency(), evalPrice, v.getMarketCap(),
                epsAtDay, peReason, stockSplits, splitError, stockForecasts, knownReports, stockPeriods);

        RadarEvaluationResult eval = summaryCalculator.evaluateForRadar(
                AnalysisHorizon.POSITION,
                evalPrice,
                v.getVerdict(),
                v.getFairValueLow(),
                v.getFundamentalBacking(),
                fundamentalsDto,
                futuObs,
                structure,
                support,
                tradePlans,
                marketResult
        );

        Integer daysToNextEarnings = nextEarningsDate == null ? null
                : (int) ChronoUnit.DAYS.between(radarDate, nextEarningsDate);
        String indCode = v.getIndustryCode();
        String indName = indCode == null ? null : industryNames.getOrDefault(indCode, indCode);
        SectorMoneyFlowDaily sectorFlow = indCode == null ? null : sectorFlowMap.get(indCode);
        List<TradePlanDto> planList = tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();
        boolean macroCautionActive = eval.macroCaution() != null && eval.macroCaution().active();
        String regimeName = eval.marketRegime() != null ? eval.marketRegime().name() : null;

        // 主清單：低估支撐低接（VALUE_SUPPORT）
        if (candidate.valueTrack()) {
            LevelZoneDto supportZone = eval.supportZone();
            BigDecimal supportDistPct = computeSupportDistancePct(evalPrice, supportZone);
            // 直接以實際收盤價 evalPrice 與 [lower, upper * 1.03] 比較，避免六位小數進位放行略超 3% 標的
            boolean nearOrInSupport = supportZone != null
                    && supportZone.lower() != null
                    && supportZone.upper() != null
                    && supportZone.upper().signum() > 0
                    && evalPrice.compareTo(supportZone.lower()) >= 0
                    && evalPrice.compareTo(supportZone.upper().multiply(MAX_SUPPORT_UPPER_FACTOR, MC)) <= 0;

            boolean eligibleValue;
            boolean watchOnlyFlag = false;
            if (headwindRegime) {
                // BEAR / PANIC 自動收緊為極窄逆風觀察交集：DEEP_UNDERVALUED + STRONG + 支撐內或 <=3% + 無破位/陷阱
                eligibleValue = "DEEP_UNDERVALUED".equals(v.getVerdict())
                        && "STRONG".equals(v.getFundamentalBacking())
                        && !eval.forwardValueTrap()
                        && !eval.highRiskBreakdown()
                        && !eval.stopBreachedOrReclaim()
                        && nearOrInSupport
                        && "WAIT_FOR_MARKET_STABILIZATION".equals(eval.stance());
                watchOnlyFlag = eligibleValue;
            } else {
                // 非 CORRECTION 情境下，WATCH_PULLBACK_TO_BAND（含 FAIR_LOWER）同樣不得於 confirmationPending 時放行，避免比 DEEP_UNDERVALUED 更寬鬆
                boolean confirmationOk = eval.marketRegime() == MarketRegime.CORRECTION || !eval.confirmationPending();
                boolean stanceAllows = "ACCUMULATE_AT_SUPPORT".equals(eval.stance())
                        || ("WATCH_PULLBACK_TO_BAND".equals(eval.stance()) && confirmationOk);
                eligibleValue = nearOrInSupport
                        && !eval.forwardValueTrap()
                        && !eval.highRiskBreakdown()
                        && !eval.stopBreachedOrReclaim()
                        && stanceAllows;
            }

            if (eligibleValue) {
                TradePlanDto matchedPlan = findMatchingPlan(planList, supportZone);
                BigDecimal protectiveStop = resolveValueProtectiveStop(matchedPlan, supportZone);
                BigDecimal firstTarget = resolveFirstTargetAbovePrice(matchedPlan, evalPrice);
                BigDecimal netRr = firstTarget == null ? null : resolveEvalPriceNetRr(matchedPlan, evalPrice, protectiveStop);
                String planStatus = matchedPlan != null && matchedPlan.status() != null
                        ? matchedPlan.status().name() : null;
                BigDecimal discountToMid = computeDiscountToMid(evalPrice, v.getFairValueMid());

                DailyOpportunityRadar row = baseRow(
                        radarDate, DailyOpportunityRadar.TRACK_VALUE_SUPPORT, v, s, indCode, indName,
                        regimeName, eval.stance(), watchOnlyFlag, macroCautionActive, evalPrice,
                        discountToMid, futuObs, nextEarningsDate, daysToNextEarnings, sectorFlow, obsCutoff, calculatedAt);
                if (supportZone != null) {
                    row.setSupportLower(scale4(supportZone.lower()));
                    row.setSupportUpper(scale4(supportZone.upper()));
                    row.setSupportDistancePct(supportDistPct);
                }
                if (matchedPlan != null && matchedPlan.entryRange() != null) {
                    row.setPlanEntryLower(scale4(matchedPlan.entryRange().lower()));
                    row.setPlanEntryUpper(scale4(matchedPlan.entryRange().upper()));
                }
                row.setProtectiveStop(scale4(protectiveStop));
                row.setFirstTargetPrice(scale4(firstTarget));
                row.setNetRiskReward(netRr == null ? null : netRr.setScale(4, RoundingMode.HALF_UP));
                row.setPlanStatus(planStatus);
                out.add(row);
            }
        }

        // 次清單：強勢成長股趨勢回踩試單（TREND_PULLBACK_STARTER，100% 沿用 Batch 02 判定口徑）
        if (candidate.starterTrack()) {
            TrendPullbackStarterDto starter = eval.trendPullbackStarter();
            if ("TREND_PULLBACK_STARTER".equals(eval.stance()) && starter != null && starter.eligible()) {
                BigDecimal discountToMid = computeDiscountToMid(evalPrice, v.getFairValueMid());
                DailyOpportunityRadar row = baseRow(
                        radarDate, DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER, v, s, indCode, indName,
                        regimeName, eval.stance(), false, macroCautionActive, evalPrice,
                        discountToMid, futuObs, nextEarningsDate, daysToNextEarnings, sectorFlow, obsCutoff, calculatedAt);
                row.setForwardPeg(starter.forwardPeg() == null ? null : starter.forwardPeg().setScale(4, RoundingMode.HALF_UP));
                row.setForwardPe(starter.forwardPe() == null ? null : starter.forwardPe().setScale(4, RoundingMode.HALF_UP));
                row.setForwardGrowthBasis(starter.forwardGrowthBasis());
                row.setPullbackAnchor(starter.matchedAnchor());
                row.setPullbackAnchorPrice(scale4(starter.anchorPrice()));
                row.setSupportDistancePct(starter.distanceRatio());
                row.setProtectiveStop(scale4(starter.suggestedStop()));
                row.setPositionFraction(starter.positionFraction());
                out.add(row);
            }
        }
    }

    private DailyOpportunityRadar baseRow(
            LocalDate radarDate,
            String trackType,
            StockValuationLatest v,
            Stock s,
            String indCode,
            String indName,
            String regimeName,
            String stance,
            boolean headwindWatchOnly,
            boolean macroCautionActive,
            BigDecimal evalPrice,
            BigDecimal discountToMid,
            FutuObservationsDto futuObs,
            LocalDate nextEarningsDate,
            Integer daysToNextEarnings,
            SectorMoneyFlowDaily sectorFlow,
            Instant obsCutoff,
            Instant calculatedAt) {
        DailyOpportunityRadar row = new DailyOpportunityRadar();
        row.setRadarDate(radarDate);
        row.setTrackType(trackType);
        row.setFigi(v.getFigi());
        row.setSymbol(s.getSymbol());
        row.setStockName(s.getLongName());
        row.setCurrency(s.getCurrency());
        row.setIndustryCode(indCode);
        row.setIndustryName(indName);
        row.setMarketCapTier(v.getMarketCapTier());
        row.setMarketRegime(regimeName);
        row.setVerdict(v.getVerdict());
        row.setFundamentalBacking(v.getFundamentalBacking());
        row.setStance(stance);
        row.setHeadwindWatchOnly(headwindWatchOnly);
        row.setMacroCautionActive(macroCautionActive);
        row.setClosePrice(evalPrice);
        row.setFairValueLow(v.getFairValueLow());
        row.setFairValueMid(v.getFairValueMid());
        row.setFairValueHigh(v.getFairValueHigh());
        row.setDiscountToMid(discountToMid);
        row.setRevenueGrowthYoy(v.getRevenueGrowthYoy());
        if (futuObs != null && futuObs.forward() != null) {
            ForwardMetrics fm = futuObs.forward();
            if (fm.forwardPeg() != null && fm.forwardPeg().status() == MetricStatus.AVAILABLE && fm.forwardPeg().value() != null) {
                row.setForwardPeg(fm.forwardPeg().value().setScale(4, RoundingMode.HALF_UP));
            }
            if (fm.forwardPe() != null && fm.forwardPe().status() == MetricStatus.AVAILABLE && fm.forwardPe().value() != null) {
                row.setForwardPe(fm.forwardPe().value().setScale(4, RoundingMode.HALF_UP));
            }
        }
        row.setNextEarningsDate(nextEarningsDate);
        row.setDaysToNextEarnings(daysToNextEarnings);
        if (sectorFlow != null) {
            row.setSectorCmf20d(sectorFlow.getCmf20d());
            row.setSectorFlowState(sectorFlow.getFlowState());
        }
        row.setObservationCutoff(obsCutoff);
        row.setCalculatedAt(calculatedAt);
        return row;
    }

    /** 僅讀取已批次載入的 PostgreSQL stock_earnings_forecast 紀錄，純記憶體產出 ForwardMetrics（含季度／年度 90D 下修）。 */
    private FutuObservationsDto buildReadOnlyForwardObservations(
            LocalDate radarDate,
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
        // 依 (source, fiscalPeriod, periodType) 挑出最新一筆觀察，與 StockEarningsForecastRepository.findLatestForStock 口徑一致
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
                radarDate, obsCutoff, currency, closePrice, marketCap, null,
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

    /** 計算收盤價距有效支撐區上緣比例：位於區間內為 0，高於上緣為 (close - upper)/upper，低於下緣為負（不入選）。 */
    public static BigDecimal computeSupportDistancePct(BigDecimal close, LevelZoneDto zone) {
        if (close == null || zone == null || zone.lower() == null || zone.upper() == null || zone.upper().signum() <= 0) {
            return null;
        }
        if (close.compareTo(zone.lower()) < 0) {
            return close.subtract(zone.lower(), MC).divide(zone.lower(), 6, RoundingMode.HALF_UP);
        }
        if (close.compareTo(zone.upper()) <= 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return close.subtract(zone.upper(), MC).divide(zone.upper(), 6, RoundingMode.HALF_UP);
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

    private BigDecimal resolveValueProtectiveStop(TradePlanDto matchedPlan, LevelZoneDto supportZone) {
        if (matchedPlan != null && matchedPlan.stop() != null && matchedPlan.stop().protectivePrice() != null
                && matchedPlan.stop().protectivePrice().signum() > 0) {
            return matchedPlan.stop().protectivePrice();
        }
        if (supportZone != null && supportZone.lower() != null && supportZone.anchorAtr() != null && supportZone.anchorAtr().signum() > 0) {
            BigDecimal stop = supportZone.lower().subtract(supportZone.anchorAtr().multiply(HALF_ATR, MC), MC);
            return stop.signum() > 0 ? stop : null;
        }
        return null;
    }

    /** 與 TradePlanService 一致：僅於第一道阻力目標嚴格高於 evalPrice 時採用，絕不跳過未突破的第一阻力改取後續目標。 */
    private BigDecimal resolveFirstTargetAbovePrice(TradePlanDto matchedPlan, BigDecimal evalPrice) {
        if (matchedPlan == null || matchedPlan.targets() == null || matchedPlan.targets().isEmpty() || evalPrice == null) {
            return null;
        }
        TargetDto first = matchedPlan.targets().getFirst();
        if (first != null && first.price() != null && first.price().compareTo(evalPrice) > 0) {
            return first.price();
        }
        return null;
    }

    private BigDecimal resolveEvalPriceNetRr(TradePlanDto matchedPlan, BigDecimal evalPrice, BigDecimal protectiveStop) {
        if (matchedPlan == null || matchedPlan.targets() == null || matchedPlan.targets().isEmpty()
                || evalPrice == null || protectiveStop == null) {
            return null;
        }
        MetricResult<RiskRewardDto> evalRr = costCalculator.calculate(evalPrice, protectiveStop, matchedPlan.targets());
        return evalRr.status() == MetricStatus.AVAILABLE && evalRr.value() != null ? evalRr.value().netRR() : null;
    }

    private BigDecimal computeDiscountToMid(BigDecimal price, BigDecimal mid) {
        if (price == null || price.signum() <= 0 || mid == null || mid.signum() <= 0) {
            return null;
        }
        return mid.subtract(price, MC).divide(mid, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale4(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }
}
