package com.ykm.stock.analytics.service.valuation;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.CustomPeerGroupDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto.FinancialValue;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FxConversionDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockCustomPeer;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.enhancement.SecCustomTagMappingService;
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.FxRateService.FxRateLookup;
import com.ykm.stock.analytics.service.market.FxRateService.FxResolution;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.MarketMultiples;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.ReportValues;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.Flow;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.Flows;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.PeriodAlignment;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.PeriodWindow;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 財季分母整股重建、最新估值快照刷新與歷史交易日即時估值服務。
 */
@Service
@Slf4j
public class StockValuationService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final LocalDate PERIOD_REBUILD_START = LocalDate.of(2014, 1, 1);
    private static final BigDecimal TWO_PERCENT = new BigDecimal("0.02");
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");
    private static final Set<String> US_DOMESTIC_GAAP_FORMS = Set.of("10-K", "10-K/A", "10-KT", "10-KT/A", "10-Q", "10-Q/A");
    private static final Set<String> PERIODIC_SHARE_FORMS = Set.of("10-K", "10-K/A", "10-KT", "10-KT/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");
    private static final Set<String> PERIODIC_STATEMENT_FORMS = Set.of(
            "10-K", "10-K/A", "10-KT", "10-KT/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A", "6-K", "6-K/A");
    private static final int REBUILD_BATCH_SIZE = 40;
    private static final int LATEST_BATCH_SIZE = 100;
    private static final int MIN_HISTORICAL_SAMPLES = 6;
    private static final int MIN_PEER_ISSUERS = 5;
    private static final int MIN_CUSTOM_PEER_ISSUERS = 3;
    private static final BigDecimal MEGA_LARGE_CAP_THRESHOLD = new BigDecimal("10000000000");
    private static final BigDecimal MID_CAP_THRESHOLD = new BigDecimal("2000000000");
    private static final BigDecimal CUSTOM_PEER_CAP_RATIO_WARN = BigDecimal.TEN;
    private static final BigDecimal CUSTOM_PEER_GROWTH_DIFF_WARN = new BigDecimal("0.20");
    public static final String TIER_MEGA_LARGE = "MEGA_LARGE";
    public static final String TIER_MID = "MID";
    public static final String TIER_SMALL = "SMALL";
    private static final List<String> NON_FIN_SUPPORTED_METRICS = List.of("PE", "PB", "PS", "PFCF", "EV_EBITDA");
    private static final List<String> FIN_SUPPORTED_METRICS = List.of("PE", "PB");

    private final StockRepository stocks;
    private final StockStatusRepository statuses;
    private final StockQuoteRepository quotes;
    private final StockSplitRepository splits;
    private final FinancialReportRepository reports;
    private final SecFinancialFactRepository secFacts;
    private final StockShareObservationRepository shareObservations;
    private final StockIndustryHistoryRepository industries;
    private final IndustryValuationConfigRepository industryConfigs;
    private final StockFinancialPeriodRepository financialPeriods;
    private final StockValuationLatestRepository latestValuations;
    private final StockCustomPeerRepository customPeers;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final SecIssuerHistory issuerHistory;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final FxRateService fxRateService;
    private final AdrShareRatioRepository adrShareRatios;
    @Autowired(required = false)
    private ObjectProvider<SecCustomTagMappingService> customTagMappingServiceProvider;

    private final FinancialReportValuationCalculator calculator = new FinancialReportValuationCalculator();
    private final SecFinancialFactsCalculator secCalculator = new SecFinancialFactsCalculator();
    private final FundamentalSnapshotCalculator fundamentalCalculator = new FundamentalSnapshotCalculator();
    private final ReentrantLock localPublishLock = new ReentrantLock();

    public StockValuationService(
            StockRepository stocks,
            StockStatusRepository statuses,
            StockQuoteRepository quotes,
            StockSplitRepository splits,
            FinancialReportRepository reports,
            SecFinancialFactRepository secFacts,
            StockShareObservationRepository shareObservations,
            StockIndustryHistoryRepository industries,
            IndustryValuationConfigRepository industryConfigs,
            StockFinancialPeriodRepository financialPeriods,
            StockValuationLatestRepository latestValuations,
            StockCustomPeerRepository customPeers,
            MarketStatusService calendar,
            DateTimeService dateTimeService,
            SecIssuerHistory issuerHistory,
            TransactionTemplate transactions,
            EntityManager entityManager) {
        this(stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, financialPeriods, latestValuations, customPeers,
                calendar, dateTimeService, issuerHistory, transactions, entityManager, null, null);
    }

    @Autowired
    public StockValuationService(
            StockRepository stocks,
            StockStatusRepository statuses,
            StockQuoteRepository quotes,
            StockSplitRepository splits,
            FinancialReportRepository reports,
            SecFinancialFactRepository secFacts,
            StockShareObservationRepository shareObservations,
            StockIndustryHistoryRepository industries,
            IndustryValuationConfigRepository industryConfigs,
            StockFinancialPeriodRepository financialPeriods,
            StockValuationLatestRepository latestValuations,
            StockCustomPeerRepository customPeers,
            MarketStatusService calendar,
            DateTimeService dateTimeService,
            SecIssuerHistory issuerHistory,
            TransactionTemplate transactions,
            EntityManager entityManager,
            FxRateService fxRateService,
            AdrShareRatioRepository adrShareRatios) {
        this.stocks = stocks;
        this.statuses = statuses;
        this.quotes = quotes;
        this.splits = splits;
        this.reports = reports;
        this.secFacts = secFacts;
        this.shareObservations = shareObservations;
        this.industries = industries;
        this.industryConfigs = industryConfigs;
        this.financialPeriods = financialPeriods;
        this.latestValuations = latestValuations;
        this.customPeers = customPeers;
        this.calendar = calendar;
        this.dateTimeService = dateTimeService;
        this.issuerHistory = issuerHistory;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.fxRateService = fxRateService;
        this.adrShareRatios = adrShareRatios;
    }

    private record ReportObservationKey(int statementType, LocalDate periodEnd, int periodType) {}

    private record IndustryModelContext(String industryCode, String modelCode, String configuredPrimaryMetric,
                                        BigDecimal assumedMultipleLow, BigDecimal assumedMultipleHigh,
                                        boolean financialInstitution) {}

    private record ShareEvaluation(BigDecimal marketCap, BigDecimal sharesAtDay, String marketCapSource,
                                   Instant sharesObservedAt, String sharesReason, String sharesCalculation) {}

    private record DayDenominators(BigDecimal epsAtDay, BigDecimal sharesAtDay, String peReason,
                                   BigDecimal fxRateToQuote, String fxFailureReason) {}

    private record AdrContext(boolean adr, boolean verified, BigDecimal ratio, String underlyingSymbol) {
        static AdrContext nonAdr() {
            return new AdrContext(false, true, BigDecimal.ONE, null);
        }

        static AdrContext unverified() {
            return new AdrContext(true, false, null, null);
        }

        static AdrContext verified(AdrShareRatio entity) {
            return new AdrContext(true, true, entity.getAdrRatio(), entity.getUnderlyingSymbol());
        }
    }

    /** Controller 入口：整股重建指定股票的財季分母並刷新最新估值快照，回傳指定日期區間的即時估值。 */
    public List<ValuationResultDto> recalculate(String figi, LocalDate from, LocalDate through) {
        if (figi == null || figi.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "figi is required");
        }
        Stock stock = stocks.findById(figi.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        Instant now = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        LocalDate effectiveFrom = from == null ? lastClosed : from;
        LocalDate effectiveThrough = through == null ? effectiveFrom : through;
        if (effectiveFrom.isAfter(effectiveThrough)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid from/through dates are required");
        }
        if (effectiveThrough.isAfter(lastClosed)) {
            effectiveThrough = lastClosed;
        }
        rebuildAndRefreshLatest(List.of(stock.getFigi()));
        if (effectiveFrom.isAfter(effectiveThrough)) return List.of();
        return evaluateHistoryRange(stock, effectiveFrom, effectiveThrough, false, now);
    }

    /** Controller 入口：查詢指定區間本地即時歷史估值（支援 DAILY 與 MONTH_END）。 */
    @Transactional(readOnly = true)
    public List<ValuationResultDto> history(String figi, LocalDate from, LocalDate through, String frequency) {
        if (from == null || through == null || from.isAfter(through)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valid from/through dates are required");
        }
        boolean monthly = "MONTH_END".equalsIgnoreCase(frequency);
        if (!monthly && !"DAILY".equalsIgnoreCase(frequency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "frequency must be DAILY or MONTH_END");
        }
        Stock stock = stocks.findById(figi)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        Instant now = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        LocalDate end = through.isAfter(lastClosed) ? lastClosed : through;
        if (from.isAfter(end)) return List.of();
        return evaluateHistoryRange(stock, from, end, monthly, now);
    }

    /** 供個股分析報告讀取指定交易日即時估值、當期 14 項財務原料、5 年月末序列、基本面快照與前瞻估值對齊上下文（純唯讀，不寫庫）。 */
    public record ReportValuationBundle(
            ValuationResultDto valuation,
            FinancialsDto financials,
            List<ValuationResultDto> history,
            FundamentalSnapshotDto fundamentals,
            LocalDate resolvedValuationDate,
            BigDecimal closePriceAtDay,
            BigDecimal marketCapAtDay,
            String sharesReasonAtDay,
            BigDecimal epsAtDay,
            String peReasonAtDay,
            List<StockSplit> splits,
            String splitSyncError,
            List<StockFinancialPeriod> storedPeriods,
            BigDecimal sharesAtDay,
            BigDecimal dcfMarketCapAtDay,
            BigDecimal fxRateToQuote,
            String valuationBlockedReason,
            boolean financialInstitution
    ) {
        public ReportValuationBundle(
                ValuationResultDto valuation, FinancialsDto financials, List<ValuationResultDto> history,
                FundamentalSnapshotDto fundamentals, LocalDate resolvedValuationDate, BigDecimal closePriceAtDay,
                BigDecimal marketCapAtDay, String sharesReasonAtDay, BigDecimal epsAtDay, String peReasonAtDay,
                List<StockSplit> splits, String splitSyncError, List<StockFinancialPeriod> storedPeriods) {
            this(valuation, financials, history, fundamentals, resolvedValuationDate, closePriceAtDay,
                    marketCapAtDay, sharesReasonAtDay, epsAtDay, peReasonAtDay, splits, splitSyncError, storedPeriods,
                    null, marketCapAtDay, BigDecimal.ONE, null, false);
        }

        public ReportValuationBundle(ValuationResultDto valuation, FinancialsDto financials,
                                     List<ValuationResultDto> history, FundamentalSnapshotDto fundamentals) {
            this(valuation, financials, history, fundamentals,
                    valuation == null ? null : valuation.valuationDate(),
                    valuation == null ? null : valuation.price(),
                    null, null, null, null, List.of(), null, List.of(),
                    null, null, BigDecimal.ONE, null, false);
        }

        public ReportValuationBundle(ValuationResultDto valuation, FinancialsDto financials, List<ValuationResultDto> history) {
            this(valuation, financials, history, null);
        }
    }

    @Transactional(readOnly = true)
    public ReportValuationBundle evaluateForReport(Stock stock, LocalDate day, Instant cutoff, Instant supplementalCutoff, String industryCode) {
        String figi = stock.getFigi();
        List<StockFinancialPeriod> periods = financialPeriods.findByFigiOrderByPeriodEndAsc(figi);
        Optional<StockValuationLatest> storedLatest = latestValuations.findById(figi);

        Instant now = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        StockStatus status = statuses.findById(figi).orElse(null);
        LocalDate effectiveLatest = effectiveLatestQuoteDate(status, lastClosed);
        boolean defaultLatestRequest = supplementalCutoff != null && supplementalCutoff.isAfter(cutoff);
        LocalDate earliestTargetDay = defaultLatestRequest && effectiveLatest.isBefore(day) ? effectiveLatest : day;

        if (periods.isEmpty() && storedLatest.isEmpty()) {
            StockQuote singleQuote = quotes.findByFigiAndQuoteDate(figi, day);
            if (defaultLatestRequest && singleQuote == null && day.equals(lastClosed) && effectiveLatest.isBefore(day)) {
                StockQuote fallbackQuote = quotes.findByFigiAndQuoteDate(figi, effectiveLatest);
                if (fallbackQuote != null) {
                    day = effectiveLatest;
                    cutoff = calendar.getSessionCloseAt(day);
                    singleQuote = fallbackQuote;
                }
            }
            List<StockSplit> emptyPeriodSplits = splits.findByFigiInOrderByExecutionDateAsc(List.of(figi));
            LocalDate latestSess = defaultLatestRequest ? effectiveLatest : lastClosed;
            String cikNoPeriod = resolveCik(stock, day);
            boolean multiNoPeriod = cikNoPeriod != null && !stocks.findMultiSecurityIssuers(List.of(cikNoPeriod), ActiveStatus.ACTIVE).isEmpty();
            Instant obsCutoffNoPeriod = defaultLatestRequest ? now : (supplementalCutoff != null ? supplementalCutoff : cutoff);
            StockShareObservation latestObsNoPeriod = shareObservations
                    .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", obsCutoffNoPeriod).orElse(null);
            StockShareObservation cutoffObsNoPeriod = defaultLatestRequest && day.equals(latestSess) ? latestObsNoPeriod
                    : shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", obsCutoffNoPeriod).orElse(null);
            StockShareObservation closingSnapNoPeriod = null;
            if (day.equals(latestSess)) {
                List<StockShareObservation> snaps = shareObservations.findLatestClosingSnapshotAt(
                        List.of(figi), calendar.getSessionOpenAt(day), cutoff, obsCutoffNoPeriod);
                if (!snaps.isEmpty()) closingSnapNoPeriod = snaps.getFirst();
            }
            StockShareObservation primaryObsNoPeriod = closingSnapNoPeriod != null ? closingSnapNoPeriod : cutoffObsNoPeriod;
            boolean verifiedNoPeriod = multiNoPeriod && (defaultLatestRequest
                    ? isMultiClassVerifiedOneToOne(cikNoPeriod, latestObsNoPeriod, emptyPeriodSplits, now)
                    : isMultiClassVerifiedAtCutoff(cikNoPeriod, primaryObsNoPeriod, day, obsCutoffNoPeriod, emptyPeriodSplits));
            BigDecimal closeNoPeriod = singleQuote != null ? singleQuote.getClosePrice() : null;
            ShareEvaluation shareEvalNoPeriod = evaluateQuoteOnlyMarketCap(
                    singleQuote, emptyPeriodSplits, status, primaryObsNoPeriod,
                    multiNoPeriod, verifiedNoPeriod, day, cutoff, obsCutoffNoPeriod, latestSess);
            return new ReportValuationBundle(
                    null, null, List.of(), null, day, closeNoPeriod,
                    shareEvalNoPeriod.marketCap(), shareEvalNoPeriod.sharesReason(),
                    null, "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE", emptyPeriodSplits, splitSyncError(status), List.of());
        }

        LocalDate historyFrom = earliestTargetDay.minusYears(5);

        List<StockQuote> quoteRows = quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(figi, historyFrom, day);
        Map<LocalDate, StockQuote> quoteByDate = new LinkedHashMap<>();
        for (StockQuote q : quoteRows) quoteByDate.put(q.getQuoteDate(), q);
        StockQuote targetQuote = quoteByDate.get(day);
        if (targetQuote == null) {
            targetQuote = quotes.findByFigiAndQuoteDate(figi, day);
            if (targetQuote != null) quoteByDate.put(day, targetQuote);
        }

        boolean fellBackToEffectiveLatest = false;
        if (defaultLatestRequest && targetQuote == null && day.equals(lastClosed)
                && effectiveLatest.isBefore(day) && quoteByDate.containsKey(effectiveLatest)) {
            day = effectiveLatest;
            cutoff = calendar.getSessionCloseAt(day);
            targetQuote = quoteByDate.get(day);
            fellBackToEffectiveLatest = true;
        }
        LocalDate latestSessionForRequest = defaultLatestRequest ? effectiveLatest : lastClosed;
        List<StockSplit> stockSplits = splits.findByFigiInOrderByExecutionDateAsc(List.of(figi));
        String cik = resolveCik(stock, day);
        boolean multiSecurityIssuer = cik != null && !SecurityTypePolicy.isAdrType(stock.getStockType())
                && !stocks.findMultiSecurityIssuers(List.of(cik), ActiveStatus.ACTIVE).isEmpty();
        StockShareObservation latestObs = shareObservations
                .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", now).orElse(null);
        StockShareObservation cutoffObs = day.equals(latestSessionForRequest) ? latestObs
                : shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", cutoff).orElse(null);
        StockShareObservation closingSnap = null;
        if (day.equals(latestSessionForRequest)) {
            List<StockShareObservation> snaps = shareObservations.findLatestClosingSnapshotAt(
                    List.of(figi), calendar.getSessionOpenAt(day), cutoff, now);
            if (!snaps.isEmpty()) closingSnap = snaps.getFirst();
        }
        boolean verifiedOneToOne = multiSecurityIssuer && isMultiClassVerifiedOneToOne(cik, latestObs, stockSplits, now);
        IndustryModelContext modelCtx = resolveIndustryModel(stock, fellBackToEffectiveLatest ? null : industryCode, cutoff, now);

        StockFinancialPeriod matchedPeriod = matchPeriod(periods, day);
        AdrContext adrCtx = resolveAdrContext(stock);
        boolean unverifiedAdr = adrCtx.adr() && !adrCtx.verified();
        FxRateLookup fxLookup = preloadFxLookup(periods, historyFrom, day);
        StockShareObservation primaryObs = filterObsForAdrDay(
                closingSnap != null ? closingSnap : cutoffObs, adrCtx, day, latestSessionForRequest);
        StockShareObservation effectiveCutoffObs = filterObsForAdrDay(cutoffObs, adrCtx, day, latestSessionForRequest);
        // 單次計算當日股數與市值，共用傳入估值、14 項財務原料、價格換算分母與基本面快照；未核實 ADR 直接阻斷股數與市值避免任何旁路洩漏
        ShareEvaluation dayShareEval = unverifiedAdr
                ? new ShareEvaluation(null, null, null, null, "ADR_RATIO_UNVERIFIED", null)
                : matchedPeriod == null
                        ? evaluateQuoteOnlyMarketCap(targetQuote, stockSplits, status, primaryObs, multiSecurityIssuer, verifiedOneToOne, day, cutoff, now, latestSessionForRequest)
                        : evaluateDayShares(targetQuote, matchedPeriod, stockSplits, status,
                                primaryObs, effectiveCutoffObs, multiSecurityIssuer, verifiedOneToOne, day, cutoff, now, latestSessionForRequest);

        ValuationResultDto dayValuation;
        if (!unverifiedAdr && periods.isEmpty() && storedLatest.isPresent() && day.equals(storedLatest.get().getValuationDate())) {
            dayValuation = ValuationResultDto.fromLatest(storedLatest.get());
        } else {
            dayValuation = evaluateSingleDay(stock, status, targetQuote, matchedPeriod, stockSplits,
                    primaryObs, effectiveCutoffObs,
                    multiSecurityIssuer, verifiedOneToOne, modelCtx, day, cutoff, now, latestSessionForRequest, dayShareEval,
                    adrCtx, fxLookup);
        }

        FinancialsDto financialsDto = matchedPeriod == null ? null
                : toFinancialsDto(matchedPeriod, stockSplits, day, status, dayShareEval);

        List<ValuationResultDto> monthlyHistory = buildHistoryFromLoaded(
                stock, status, quoteByDate, periods, stockSplits, closingSnap, cutoffObs,
                multiSecurityIssuer, verifiedOneToOne, modelCtx, latestSessionForRequest, now, true, adrCtx, fxLookup);

        boolean historicalDate = day.isBefore(latestSessionForRequest);
        boolean financialInstitution = modelCtx != null && modelCtx.financialInstitution();
        String resolvedModelCode = modelCtx != null ? modelCtx.modelCode()
                : dayValuation.modelCode() != null ? dayValuation.modelCode() : IndustryValuationProposalService.MODEL_NON_FINANCIAL;
        String selectedMetric = selectPrimaryMetricForDay(modelCtx, dayValuation.metrics(), matchedPeriod);
        DayDenominators denoms = resolveDayDenominators(stock, status, matchedPeriod, stockSplits, day, dayShareEval, adrCtx, fxLookup);
        String fallbackIndustryCode = fellBackToEffectiveLatest ? null : industryCode;
        String resolvedIndustryCode = modelCtx != null && modelCtx.industryCode() != null ? modelCtx.industryCode() : fallbackIndustryCode;

        BigDecimal closePrice = targetQuote != null ? targetQuote.getClosePrice() : null;
        BigDecimal peAtDay = positiveMetricValue(dayValuation.metrics(), "PE");
        String peMetricReason = dayValuation.metrics() != null && dayValuation.metrics().pe() != null
                && dayValuation.metrics().pe().reasonCode() != null
                ? dayValuation.metrics().pe().reasonCode() : denoms.peReason();
        BigDecimal dayMarketCap = unverifiedAdr ? null : (dayShareEval == null ? null : dayShareEval.marketCap());
        String daySharesReason = unverifiedAdr ? "ADR_RATIO_UNVERIFIED" : (dayShareEval == null ? null : dayShareEval.sharesReason());
        String valuationBlockedReason = unverifiedAdr ? "ADR_RATIO_UNVERIFIED" : denoms.fxFailureReason();
        FundamentalSnapshotDto fundamentalsDto = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                periods,
                matchedPeriod,
                stockSplits,
                day,
                stock.getCurrency(),
                closePrice,
                dayMarketCap,
                daySharesReason,
                denoms.epsAtDay(),
                peMetricReason,
                peAtDay,
                financialInstitution,
                splitSyncError(status),
                denoms.fxRateToQuote(),
                valuationBlockedReason,
                adrCtx.adr() && adrCtx.verified()
        ));

        BigDecimal currentSelectedMultiple = positiveMetricValue(dayValuation.metrics(), selectedMetric);
        ValuationReferenceDto historicalRef = buildHistoricalReference(
                selectedMetric, currentSelectedMultiple, monthlyHistory, matchedPeriod, denoms, stock.getCurrency(), financialInstitution, day);
        BigDecimal targetRevGrowthYoy = extractAvailableMetric(fundamentalsDto == null ? null : fundamentalsDto.revenueGrowthYoy());
        ValuationReferenceDto peerRef = buildPeerReference(
                stock, selectedMetric, dayValuation.metrics(), resolvedIndustryCode, day, historicalDate,
                matchedPeriod, denoms, stock.getCurrency(), dayMarketCap, targetRevGrowthYoy, financialInstitution);
        ValuationReferenceDto assumptionRef = buildIndustryAssumption(
                selectedMetric, dayValuation.metrics(), modelCtx, resolvedIndustryCode, historicalDate, matchedPeriod, denoms, stock.getCurrency());

        ValuationResultDto enrichedValuation = dayValuation.withReferences(
                resolvedModelCode, selectedMetric, historicalRef, peerRef, assumptionRef);

        // 顯式查詢指定估值日截點時，前瞻預期對比之市值與 TTM EPS 僅採用截至該截點已公開之股數與盈利版本；未核實 ADR 維持阻斷不覆寫
        BigDecimal forwardMarketCap = dayMarketCap;
        String forwardSharesReason = daySharesReason;
        BigDecimal forwardEpsAtDay = denoms.epsAtDay();
        String forwardPeReason = peMetricReason;
        if (!defaultLatestRequest && !unverifiedAdr) {
            Instant forwardCutoff = supplementalCutoff != null ? supplementalCutoff : cutoff;
            LocalDate queryDay = day;
            StockShareObservation strictCutoffObs = filterObsForAdrDay(shareObservations
                    .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", forwardCutoff).orElse(null),
                    adrCtx, day, latestSessionForRequest);
            StockShareObservation strictClosingSnap = null;
            if (day.equals(latestSessionForRequest)) {
                List<StockShareObservation> strictSnaps = shareObservations.findLatestClosingSnapshotAt(
                        List.of(figi), calendar.getSessionOpenAt(day), cutoff, forwardCutoff);
                if (!strictSnaps.isEmpty()) strictClosingSnap = strictSnaps.getFirst();
            }
            StockShareObservation strictPrimaryObs = filterObsForAdrDay(
                    strictClosingSnap != null ? strictClosingSnap : strictCutoffObs,
                    adrCtx, day, latestSessionForRequest);
            boolean forwardVerifiedOneToOne = multiSecurityIssuer
                    && isMultiClassVerifiedAtCutoff(cik, strictPrimaryObs, queryDay, forwardCutoff, stockSplits);

            List<StockFinancialPeriod> publishedPeriods = periods.stream()
                    .filter(p -> isPeriodPublishedAtCutoff(p, queryDay, forwardCutoff))
                    .toList();
            StockFinancialPeriod latestPublished = matchPeriod(publishedPeriods, day);
            List<StockFinancialPeriod> shareKnownPeriods = periods.stream()
                    .filter(p -> hasCutoffEligibleShares(p, queryDay, forwardCutoff))
                    .toList();
            StockFinancialPeriod shareMatched = matchPeriod(shareKnownPeriods, day);

            ShareEvaluation forwardShareEval;
            if (latestPublished != null && hasCutoffEligibleShares(latestPublished, queryDay, forwardCutoff)) {
                forwardShareEval = evaluateDayShares(targetQuote, latestPublished, stockSplits, status,
                        strictPrimaryObs, strictCutoffObs, multiSecurityIssuer, forwardVerifiedOneToOne,
                        day, cutoff, forwardCutoff, latestSessionForRequest);
            } else {
                ShareEvaluation obsEval = evaluateQuoteOnlyMarketCap(targetQuote, stockSplits, status,
                        strictPrimaryObs, multiSecurityIssuer, forwardVerifiedOneToOne,
                        day, cutoff, forwardCutoff, latestSessionForRequest);
                if (obsEval.sharesAtDay() != null || shareMatched == null) {
                    forwardShareEval = obsEval;
                } else {
                    forwardShareEval = evaluateDayShares(targetQuote, shareMatched, stockSplits, status,
                            strictPrimaryObs, strictCutoffObs, multiSecurityIssuer, forwardVerifiedOneToOne,
                            day, cutoff, forwardCutoff, latestSessionForRequest);
                }
            }

            List<StockFinancialPeriod> epsKnownPeriods = periods.stream()
                    .filter(p -> {
                        if (p.getPeriodEnd() == null || p.getPeriodEnd().isAfter(queryDay)) return false;
                        Instant epsAvail = p.getEpsTtmAvailableAt() != null ? p.getEpsTtmAvailableAt() : p.getAvailableAt();
                        if (epsAvail == null || epsAvail.isAfter(forwardCutoff)) return false;
                        boolean usesSec = p.getEpsTtmSource() != null && p.getEpsTtmSource().contains("SEC");
                        if (!usesSec) return true;
                        LocalDate epsSecFrom = p.getEpsTtmSecAvailableFromDate() != null
                                ? p.getEpsTtmSecAvailableFromDate() : p.getSecAvailableFromDate();
                        return epsSecFrom == null || !epsSecFrom.isAfter(queryDay);
                    })
                    .toList();
            StockFinancialPeriod epsMatched = matchPeriod(epsKnownPeriods, day);
            boolean epsBlockedByMultiClass = epsMatched != null && multiSecurityIssuer && !forwardVerifiedOneToOne
                    && epsMatched.getEpsTtmSource() != null && epsMatched.getEpsTtmSource().contains("SEC");
            if (epsBlockedByMultiClass) {
                forwardEpsAtDay = null;
                forwardPeReason = "SEC_SHARE_CLASS_EPS_UNVERIFIED";
            } else {
                DayDenominators cutoffDenoms = resolveDayDenominators(stock, status, epsMatched, stockSplits, day, forwardShareEval, adrCtx, fxLookup);
                forwardEpsAtDay = cutoffDenoms.epsAtDay();
                forwardPeReason = cutoffDenoms.peReason();
            }
            forwardMarketCap = forwardShareEval == null ? null : forwardShareEval.marketCap();
            forwardSharesReason = forwardShareEval == null ? null : forwardShareEval.sharesReason();
        }

        return new ReportValuationBundle(
                enrichedValuation, financialsDto, monthlyHistory, fundamentalsDto,
                day, closePrice, forwardMarketCap, forwardSharesReason, forwardEpsAtDay, forwardPeReason,
                stockSplits, splitSyncError(status), periods,
                unverifiedAdr ? null : (dayShareEval == null ? null : dayShareEval.sharesAtDay()),
                dayMarketCap,
                denoms.fxRateToQuote(), valuationBlockedReason, financialInstitution);
    }

    /** 當財報、SEC 披露或拆股有變更時，整股重建財季分母並刷新最新收市估值快照。 */
    public void rebuildAndRefreshLatest(Collection<String> figis) {
        if (figis == null || figis.isEmpty()) return;
        Set<String> uniqueFigis = figis.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueFigis.isEmpty()) return;
        rebuildFinancialPeriods(uniqueFigis);
        refreshLatestValuations(uniqueFigis, null);
    }

    /** 整股重建指定股票自 2014 年起的所有標準化財季分母列（每股約 40 列）；每批先按 FIGI 字典序鎖定 stock_status 再重讀原料重建。 */
    public void rebuildFinancialPeriods(Collection<String> figis) {
        if (figis == null || figis.isEmpty()) return;
        List<String> orderedFigis = figis.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).distinct().sorted().toList();
        if (orderedFigis.isEmpty()) return;

        for (List<String> batchFigis : Lists.partition(orderedFigis, REBUILD_BATCH_SIZE)) {
            transactions.executeWithoutResult(tx -> {
                statuses.findAllByIdForUpdate(batchFigis);
                rebuildFinancialPeriodsInCurrentTx(batchFigis);
            });
        }
    }

    /**
     * 於呼叫端既有的寫入交易中直接重建指定股票的標準化財季分母（呼叫端須已按 FIGI 字典序鎖定 stock_status）。
     * 先 flush 上游寫入確保讀取到最新財報／SEC／拆股／主檔／股數，並在同一交易內原子替換分母列。
     */
    public void rebuildFinancialPeriodsInCurrentTx(Collection<String> figis) {
        if (figis == null || figis.isEmpty()) return;
        List<String> orderedFigis = figis.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).distinct().sorted().toList();
        if (orderedFigis.isEmpty()) return;

        entityManager.flush();
        Instant calculatedAt = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(calculatedAt);

        for (List<String> batchFigis : Lists.partition(orderedFigis, REBUILD_BATCH_SIZE)) {
            List<Stock> batchStocks = stocks.findAllById(batchFigis).stream()
                    .filter(s -> SecurityTypePolicy.supportsValuation(s.getStockType()))
                    .toList();
            if (batchStocks.isEmpty()) continue;

            List<String> activeFigis = batchStocks.stream().map(Stock::getFigi).toList();
            Map<String, StockStatus> statusMap = statuses.findAllById(activeFigis).stream()
                    .collect(Collectors.toMap(StockStatus::getFigi, row -> row));
            Map<String, List<FinancialReport>> reportsByFigi = reports.findByFigiInOrderByFigiAscPeriodEndAsc(activeFigis).stream()
                    .collect(Collectors.groupingBy(FinancialReport::getFigi));
            Map<String, List<StockSplit>> splitsByFigi = splits.findByFigiInOrderByExecutionDateAsc(activeFigis).stream()
                    .collect(Collectors.groupingBy(StockSplit::getFigi));

            Set<String> ciks = new LinkedHashSet<>();
            for (Stock s : batchStocks) {
                if (s.getCik() != null && !s.getCik().isBlank()) {
                    ciks.add(s.getCik());
                    ciks.addAll(issuerHistory.predecessors(s.getCik()));
                }
            }
            Map<String, List<SecFinancialFact>> factsByCik = ciks.isEmpty() ? Map.of()
                    : secFacts.findPeriodRebuildFacts(ciks, SecFinancialFactsCalculator.INPUT_TAGS, PERIOD_REBUILD_START).stream()
                            .collect(Collectors.groupingBy(SecFinancialFact::getCik, LinkedHashMap::new, Collectors.toCollection(ArrayList::new)));
            // 06_Enhancement 2.4B：僅於該期間缺少標準 us-gaap 標籤時，確定性注入經人工確認落庫之自訂標籤補洞事實（0 AI 消耗，絕不覆寫標準事實）。
            SecCustomTagMappingService customTagService = customTagMappingServiceProvider == null
                    ? null : customTagMappingServiceProvider.getIfAvailable();
            if (customTagService != null && !ciks.isEmpty()) {
                Map<String, List<SecFinancialFact>> gapFillByCik = customTagService.buildConfirmedGapFillFactsByCik(
                        ciks, factsByCik, PERIOD_REBUILD_START);
                if (!gapFillByCik.isEmpty()) {
                    if (factsByCik.isEmpty()) {
                        factsByCik = new LinkedHashMap<>();
                    }
                    for (Map.Entry<String, List<SecFinancialFact>> entry : gapFillByCik.entrySet()) {
                        factsByCik.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
                    }
                }
            }
            Set<String> multiSecurityIssuers = ciks.isEmpty() ? Set.of()
                    : stocks.findMultiSecurityIssuers(ciks, ActiveStatus.ACTIVE);
            Map<String, StockShareObservation> latestObsByFigi = shareObservations.findLatestAt(activeFigis, calculatedAt).stream()
                    .collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
            Map<String, IndustryModelContext> modelsByFigi = resolveBatchIndustryModels(batchStocks, calendar.getSessionCloseAt(lastClosed), calculatedAt);
            Map<String, AdrContext> adrContextsByFigi = loadAdrContexts(batchStocks);

            Map<String, List<StockFinancialPeriod>> builtByFigi = new LinkedHashMap<>();
            for (Stock stock : batchStocks) {
                String figi = stock.getFigi();
                String cik = stock.getCik() != null && !stock.getCik().isBlank() ? stock.getCik() : null;
                List<SecFinancialFact> rawCikFacts = collectStockSecFacts(stock, factsByCik);
                List<StockSplit> stockSplits = splitsByFigi.getOrDefault(figi, List.of());
                boolean multiClass = cik != null && !SecurityTypePolicy.isAdrType(stock.getStockType())
                        && multiSecurityIssuers.contains(cik);
                boolean verifiedOneToOne = multiClass && isMultiClassVerifiedFromRawFacts(
                        latestObsByFigi.get(figi), rawCikFacts, stockSplits, calculatedAt);
                IndustryModelContext modelCtx = modelsByFigi.get(figi);
                AdrContext adrCtx = adrContextsByFigi.getOrDefault(figi, AdrContext.nonAdr());
                List<StockFinancialPeriod> built = buildPeriodsForStock(
                        stock, statusMap.get(figi), reportsByFigi.getOrDefault(figi, List.of()),
                        rawCikFacts, stockSplits, latestObsByFigi.get(figi),
                        multiClass, verifiedOneToOne, modelCtx != null && modelCtx.financialInstitution(),
                        adrCtx, lastClosed, calculatedAt);
                builtByFigi.put(figi, built);
            }

            financialPeriods.deleteByFigiIn(builtByFigi.keySet());
            financialPeriods.flush();
            for (List<StockFinancialPeriod> rows : builtByFigi.values()) {
                for (StockFinancialPeriod row : rows) {
                    entityManager.persist(row);
                }
            }
            entityManager.flush();
            log.info("Stock financial periods rebuilt | stocks={} rows={}",
                    builtByFigi.size(), builtByFigi.values().stream().mapToInt(List::size).sum());
        }
    }

    private record PendingTargetEvaluation(
            Stock stock,
            LocalDate valuationDate,
            Instant priorCalculatedAt,
            ValuationResultDto evaluated,
            StockValuationLatest builtSnapshot,
            String selectedMetric,
            String industryCode,
            boolean financialInstitution,
            StockFinancialPeriod matchedPeriod,
            DayDenominators denoms,
            ValuationReferenceDto historicalRef,
            BigDecimal marketCap,
            BigDecimal revenueGrowthYoy,
            boolean directRefresh,
            long periodCount,
            Instant maxPeriodUpdatedAt
    ) {}

    private record IssuerSnapshotRow(String rawIssuerKey, StockValuationLatest snapshot) {}

    private record IndustryDateKey(String industryCode, LocalDate valuationDate) {}

    private record IssuerPeerAggregate(
            String canonicalIssuerKey,
            BigDecimal usdMarketCapMedian,
            String marketCapTier,
            Map<String, BigDecimal> metricMedians,
            BigDecimal revenueGrowthYoyMedian,
            BigDecimal roeMedian,
            BigDecimal fcfMarginMedian
    ) {}

    private void addIndustryDatePair(Set<IndustryDateKey> pairs, String industryCode, LocalDate valuationDate) {
        if (industryCode != null && !industryCode.isBlank() && valuationDate != null) {
            pairs.add(new IndustryDateKey(industryCode, valuationDate));
        }
    }

    private List<String> queryFigisByIndustryDatePairs(Set<IndustryDateKey> pairs) {
        if (pairs == null || pairs.isEmpty()) return List.of();
        Map<LocalDate, Set<String>> industriesByDate = new LinkedHashMap<>();
        for (IndustryDateKey pair : pairs) {
            industriesByDate.computeIfAbsent(pair.valuationDate(), k -> new LinkedHashSet<>()).add(pair.industryCode());
        }
        Set<String> figis = new LinkedHashSet<>();
        for (Map.Entry<LocalDate, Set<String>> entry : industriesByDate.entrySet()) {
            List<String> rows = latestValuations.findActiveTrackedFigisByIndustryCodesAndDates(
                    entry.getValue(), List.of(entry.getKey()));
            if (rows != null) {
                figis.addAll(rows);
            }
        }
        return List.copyOf(figis);
    }

    /** 批次刷新指定股票的最新估值快照（兩階段計算：先記憶體評估個股與五年分位，再整合變更前後同業與自訂對標一次性寫入完整快照與 verdict）。 */
    public void refreshLatestValuations(Collection<String> figis, LocalDate requestedDate) {
        if (figis == null || figis.isEmpty()) return;
        List<String> orderedFigis = figis.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).distinct().toList();
        if (orderedFigis.isEmpty()) return;
        Instant calculatedAt = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(calculatedAt);

        Map<String, PendingTargetEvaluation> pendingByFigi = new LinkedHashMap<>();
        Set<IndustryDateKey> directAffectedPairs = new LinkedHashSet<>();

        // 階段一：批次載入個股輸入，於記憶體完成當日倍數、基本面與五年月末歷史參考計算（不提前寫入缺 verdict 的半成品）
        for (List<String> batchFigis : Lists.partition(orderedFigis, LATEST_BATCH_SIZE)) {
            Map<String, StockValuationLatest> priorLatestMap = latestValuations.findByFigiIn(batchFigis).stream()
                    .collect(Collectors.toMap(StockValuationLatest::getFigi, row -> row));

            List<Stock> batchStocks = stocks.findAllById(batchFigis).stream()
                    .filter(s -> s.isTracked() && s.getActiveStatus() == ActiveStatus.ACTIVE
                            && SecurityTypePolicy.supportsValuation(s.getStockType()))
                    .toList();
            if (batchStocks.isEmpty()) continue;

            List<PendingTargetEvaluation> batchEvaluated = evaluateTargetBatchInMemory(
                    batchStocks, priorLatestMap, requestedDate, null, lastClosed, calculatedAt, true);
            for (PendingTargetEvaluation item : batchEvaluated) {
                String evaluatedFigi = item.stock().getFigi();
                pendingByFigi.put(evaluatedFigi, item);
                StockValuationLatest prior = priorLatestMap.get(evaluatedFigi);
                if (prior != null) {
                    addIndustryDatePair(directAffectedPairs, prior.getIndustryCode(), prior.getValuationDate());
                }
                addIndustryDatePair(directAffectedPairs, item.industryCode(), item.valuationDate());
            }
        }
        if (pendingByFigi.isEmpty()) return;

        // 階段二：僅針對本輪直接變動股票的新舊精確 (industryCode, valuationDate) 配對與一層自訂引用收集反向目標
        Set<String> actuallyUpdatedDirectFigis = new LinkedHashSet<>(pendingByFigi.keySet());
        Set<String> externalCandidateFigis = new LinkedHashSet<>(queryFigisByIndustryDatePairs(directAffectedPairs));
        List<String> customReferencers = customPeers.findEligibleTargetFigisByPeerFigiIn(actuallyUpdatedDirectFigis);
        if (customReferencers != null) externalCandidateFigis.addAll(customReferencers);
        externalCandidateFigis.removeAll(actuallyUpdatedDirectFigis);

        loadExternalAffectedEvaluations(externalCandidateFigis, pendingByFigi, lastClosed, calculatedAt);

        commitEvaluatedTargetsWithPeerVerdicts(pendingByFigi, Set.of(), lastClosed, calculatedAt);
    }

    /** 當人工追蹤開關（tracked）變更時，於單一發布鎖內同步刷新恢復追蹤股快照及受影響同業／自訂引用者的 verdict。 */
    public void onTrackedChanged(Collection<String> changedFigis) {
        if (changedFigis == null || changedFigis.isEmpty()) return;
        List<String> normalized = changedFigis.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isBlank()).distinct().toList();
        if (normalized.isEmpty()) return;

        Instant calculatedAt = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(calculatedAt);
        List<Stock> foundStocks = stocks.findAllById(normalized);
        List<Stock> reEnabledStocks = foundStocks.stream()
                .filter(s -> s.isTracked() && s.getActiveStatus() == ActiveStatus.ACTIVE
                        && SecurityTypePolicy.supportsValuation(s.getStockType()))
                .toList();
        Set<String> reEnabledFigis = reEnabledStocks.stream().map(Stock::getFigi).collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> disabled = normalized.stream().filter(f -> !reEnabledFigis.contains(f)).toList();

        Map<String, PendingTargetEvaluation> pendingByFigi = new LinkedHashMap<>();
        Set<IndustryDateKey> directAffectedPairs = new LinkedHashSet<>();

        if (!reEnabledStocks.isEmpty()) {
            for (List<Stock> batchStocks : Lists.partition(reEnabledStocks, LATEST_BATCH_SIZE)) {
                List<String> batchFigis = batchStocks.stream().map(Stock::getFigi).toList();
                Map<String, StockValuationLatest> priorMap = latestValuations.findByFigiIn(batchFigis).stream()
                        .collect(Collectors.toMap(StockValuationLatest::getFigi, row -> row));
                List<PendingTargetEvaluation> batchEval = evaluateTargetBatchInMemory(
                        batchStocks, priorMap, null, null, lastClosed, calculatedAt, true);
                for (PendingTargetEvaluation item : batchEval) {
                    String f = item.stock().getFigi();
                    pendingByFigi.put(f, item);
                    StockValuationLatest prior = priorMap.get(f);
                    if (prior != null) {
                        addIndustryDatePair(directAffectedPairs, prior.getIndustryCode(), prior.getValuationDate());
                    }
                    addIndustryDatePair(directAffectedPairs, item.industryCode(), item.valuationDate());
                }
            }
        }

        if (!disabled.isEmpty()) {
            for (StockValuationLatest prior : latestValuations.findByFigiIn(disabled)) {
                addIndustryDatePair(directAffectedPairs, prior.getIndustryCode(), prior.getValuationDate());
            }
        }

        Set<String> reverseProbeKeys = new LinkedHashSet<>(pendingByFigi.keySet());
        reverseProbeKeys.addAll(disabled);
        if (reverseProbeKeys.isEmpty()) return;

        Set<String> externalAffected = new LinkedHashSet<>(queryFigisByIndustryDatePairs(directAffectedPairs));
        List<String> referencers = customPeers.findEligibleTargetFigisByPeerFigiIn(reverseProbeKeys);
        if (referencers != null) externalAffected.addAll(referencers);
        externalAffected.removeAll(reverseProbeKeys);

        loadExternalAffectedEvaluations(externalAffected, pendingByFigi, lastClosed, calculatedAt);
        // 即使預檢時無受影響對象，仍進入發布鎖與狀態列鎖重查，防止停追提交前另一交易併發新增引用而漏算
        commitEvaluatedTargetsWithPeerVerdicts(pendingByFigi, disabled, lastClosed, calculatedAt);
    }

    private LocalDate resolveTargetModelValuationDate(String figi, LocalDate lastClosed) {
        Optional<StockValuationLatest> stored = latestValuations.findById(figi);
        if (stored.isPresent() && stored.get().getValuationDate() != null) {
            return stored.get().getValuationDate();
        }
        StockStatus st = statuses.findById(figi).orElse(null);
        return effectiveLatestQuoteDate(st, lastClosed);
    }

    /** Controller 入口：讀取指定股票的自訂同業對標組設定、可選覆寫指標及各對手最新追蹤／市值狀態。 */
    @Transactional(readOnly = true)
    public CustomPeerGroupDto getCustomPeers(String figi) {
        Stock target = requireTargetStock(figi);
        Instant now = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        LocalDate modelDate = resolveTargetModelValuationDate(target.getFigi(), lastClosed);
        IndustryModelContext modelCtx = resolveIndustryModel(target, null, calendar.getSessionCloseAt(modelDate), now);
        List<String> supported = modelCtx != null && modelCtx.financialInstitution() ? FIN_SUPPORTED_METRICS : NON_FIN_SUPPORTED_METRICS;

        StockValuationLatest targetLatest = latestValuations.findById(target.getFigi()).orElse(null);
        BigDecimal targetMarketCap = targetLatest != null ? targetLatest.getMarketCap() : null;
        String targetCurrency = targetLatest != null && targetLatest.getCurrency() != null
                ? targetLatest.getCurrency() : target.getCurrency();
        LocalDate targetValuationDate = targetLatest != null ? targetLatest.getValuationDate() : null;
        String targetPrimaryMetric = targetLatest != null ? targetLatest.getPrimaryMetric() : null;
        String targetIssuerKey = canonicalIssuerKey(target.getCik(), target.getFigi());

        List<StockCustomPeer> rows = customPeers.findByFigiOrderBySortOrderAsc(target.getFigi());
        if (rows.isEmpty()) {
            return new CustomPeerGroupDto(target.getFigi(), null, supported, List.of(),
                    targetMarketCap, targetCurrency, targetValuationDate, targetPrimaryMetric, targetIssuerKey);
        }
        String preferredMetric = rows.getFirst().getPreferredMetric();
        List<String> peerFigis = rows.stream().map(StockCustomPeer::getPeerFigi).toList();
        Map<String, Stock> peerStockMap = stocks.findAllById(peerFigis).stream()
                .collect(Collectors.toMap(Stock::getFigi, s -> s));
        Map<String, StockValuationLatest> peerLatestMap = latestValuations.findByFigiIn(peerFigis).stream()
                .collect(Collectors.toMap(StockValuationLatest::getFigi, v -> v));

        List<CustomPeerGroupDto.PeerItem> items = new ArrayList<>(rows.size());
        for (StockCustomPeer row : rows) {
            Stock ps = peerStockMap.get(row.getPeerFigi());
            StockValuationLatest pv = peerLatestMap.get(row.getPeerFigi());
            String pInd = pv != null && pv.getIndustryCode() != null ? pv.getIndustryCode()
                    : (ps != null && ps.getClassification() != null ? ps.getClassification().getCode() : null);
            String pCur = pv != null && pv.getCurrency() != null ? pv.getCurrency() : (ps != null ? ps.getCurrency() : null);
            String pIssuerKey = canonicalIssuerKey(ps != null ? ps.getCik() : null, row.getPeerFigi());
            items.add(new CustomPeerGroupDto.PeerItem(
                    row.getPeerFigi(),
                    ps != null ? ps.getSymbol() : row.getPeerFigi(),
                    ps != null ? ps.getLongName() : null,
                    pInd,
                    ps != null && ps.getActiveStatus() == ActiveStatus.ACTIVE,
                    ps != null && ps.isTracked(),
                    pv != null ? pv.getValuationDate() : null,
                    pCur,
                    pv != null ? pv.getMarketCap() : null,
                    pv != null ? pv.getMarketCapTier() : null,
                    pIssuerKey,
                    pv != null ? pv.getPe() : null,
                    pv != null ? pv.getPb() : null,
                    pv != null ? pv.getPs() : null,
                    pv != null ? pv.getPfcf() : null,
                    pv != null ? pv.getEvEbitda() : null
            ));
        }
        return new CustomPeerGroupDto(target.getFigi(), preferredMetric, supported, items,
                targetMarketCap, targetCurrency, targetValuationDate, targetPrimaryMetric, targetIssuerKey);
    }

    /** Controller 入口：整組原子替換自訂對標清單與選填覆寫指標，並於同一交易鎖內同步更新目標股最新快照之 verdict。 */
    public CustomPeerGroupDto saveCustomPeers(String figi, List<String> peerFigis, String preferredMetric) {
        if (peerFigis == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CUSTOM_PEERS_LIST_REQUIRED");
        }
        Stock target = requireTargetStock(figi);
        Instant now = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        LocalDate modelDate = resolveTargetModelValuationDate(target.getFigi(), lastClosed);
        IndustryModelContext modelCtx = resolveIndustryModel(target, null, calendar.getSessionCloseAt(modelDate), now);
        boolean financial = modelCtx != null && modelCtx.financialInstitution();
        List<String> supported = financial ? FIN_SUPPORTED_METRICS : NON_FIN_SUPPORTED_METRICS;

        String normalizedMetric = preferredMetric != null && !preferredMetric.isBlank()
                ? preferredMetric.trim().toUpperCase() : null;
        if (normalizedMetric != null && !supported.contains(normalizedMetric)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CUSTOM_PEER_METRIC");
        }

        List<String> deduplicated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String pf : peerFigis) {
            if (pf == null || pf.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CUSTOM_PEER_FIGI_BLANK");
            }
            String trimmed = pf.trim();
            if (seen.add(trimmed)) {
                deduplicated.add(trimmed);
            }
        }

        if (!deduplicated.isEmpty()) {
            Map<String, Stock> peerMap = stocks.findAllById(deduplicated).stream()
                    .collect(Collectors.toMap(Stock::getFigi, s -> s));
            if (peerMap.size() != deduplicated.size()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PEER_STOCK_NOT_FOUND");
            }
            for (String pf : deduplicated) {
                Stock peerStock = peerMap.get(pf);
                if (!SecurityTypePolicy.supportsValuation(peerStock.getStockType())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CUSTOM_PEER_TYPE");
                }
                if (isSelfOrSiblingIssuer(target, modelDate, peerStock.getCik(), peerStock.getFigi())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CUSTOM_PEER_SELF_OR_SIBLING_FORBIDDEN");
                }
            }
        }

        // 先取得統一發布鎖，再按與批次刷新一致之 FIGI 字典序單次鎖定目標股與被引用對手，完成替換與 verdict 更新
        List<String> lockOrder = new ArrayList<>();
        lockOrder.add(target.getFigi());
        lockOrder.addAll(deduplicated);
        List<String> sortedLocks = lockOrder.stream().distinct().sorted().toList();

        runWithPublishLock(() -> transactions.executeWithoutResult(tx -> {
            entityManager.flush();
            entityManager.clear();
            statuses.acquireValuationPublishLock();
            statuses.findAllByIdForUpdate(sortedLocks);

            Stock lockedTarget = stocks.findById(target.getFigi()).orElse(target);
            StockValuationLatest existing = latestValuations.findById(target.getFigi()).orElse(null);
            LocalDate lockedModelDate = existing != null && existing.getValuationDate() != null
                    ? existing.getValuationDate() : modelDate;
            IndustryModelContext lockedModelCtx = resolveIndustryModel(
                    lockedTarget, null, calendar.getSessionCloseAt(lockedModelDate), now);
            List<String> lockedSupported = lockedModelCtx != null && lockedModelCtx.financialInstitution()
                    ? FIN_SUPPORTED_METRICS : NON_FIN_SUPPORTED_METRICS;
            if (normalizedMetric != null && !lockedSupported.contains(normalizedMetric)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CUSTOM_PEER_METRIC");
            }

            customPeers.deleteByFigi(target.getFigi());
            customPeers.flush();
            List<StockCustomPeer> persistedRows = new ArrayList<>(deduplicated.size());
            for (int i = 0; i < deduplicated.size(); i++) {
                StockCustomPeer row = new StockCustomPeer();
                row.setFigi(target.getFigi());
                row.setPeerFigi(deduplicated.get(i));
                row.setPreferredMetric(normalizedMetric);
                row.setSortOrder(i);
                row.setUpdatedAt(now);
                entityManager.persist(row);
                persistedRows.add(row);
            }
            entityManager.flush();

            if (existing != null && existing.getValuationDate() != null
                    && lockedTarget.isTracked()
                    && lockedTarget.getActiveStatus() == ActiveStatus.ACTIVE) {
                LocalDate valDate = existing.getValuationDate();
                Instant evalCut = laterInstant(laterInstant(now, dateTimeService.now()), existing.getCalculatedAt());
                List<PendingTargetEvaluation> freshEvalList = evaluateTargetBatchInMemory(
                        List.of(lockedTarget),
                        Map.of(target.getFigi(), existing),
                        null,
                        Map.of(target.getFigi(), valDate),
                        lastClosed,
                        evalCut,
                        false
                );
                if (!freshEvalList.isEmpty()) {
                    PendingTargetEvaluation freshEval = freshEvalList.getFirst();
                    List<IssuerSnapshotRow> customCandidateRows = new ArrayList<>();
                    if (!deduplicated.isEmpty()) {
                        List<Object[]> rawCustom = latestValuations.findActiveTrackedCustomPeerSnapshots(deduplicated, List.of(valDate));
                        if (rawCustom != null) {
                            for (Object[] tuple : rawCustom) {
                                if (tuple != null && tuple.length >= 2 && tuple[0] instanceof String rawKey
                                        && tuple[1] instanceof StockValuationLatest snap) {
                                    customCandidateRows.add(new IssuerSnapshotRow(rawKey, snap));
                                }
                            }
                        }
                    }
                    List<IssuerSnapshotRow> industryCandidateRows = new ArrayList<>();
                    if (freshEval.industryCode() != null && !freshEval.industryCode().isBlank()) {
                        List<Object[]> rawInd = latestValuations.findActiveTrackedPeerSnapshots(freshEval.industryCode(), valDate);
                        if (rawInd != null) {
                            for (Object[] tuple : rawInd) {
                                if (tuple != null && tuple.length >= 2 && tuple[0] instanceof String rawKey
                                        && tuple[1] instanceof StockValuationLatest snap) {
                                    industryCandidateRows.add(new IssuerSnapshotRow(rawKey, snap));
                                }
                            }
                        }
                    }
                    ValuationReferenceDto peerRef = computePeerReferenceFromSnapshots(
                            lockedTarget,
                            freshEval.selectedMetric(),
                            freshEval.evaluated().metrics(),
                            freshEval.industryCode(),
                            valDate,
                            false,
                            freshEval.matchedPeriod(),
                            freshEval.denoms(),
                            lockedTarget.getCurrency(),
                            freshEval.marketCap(),
                            freshEval.revenueGrowthYoy(),
                            freshEval.financialInstitution(),
                            persistedRows,
                            customCandidateRows,
                            industryCandidateRows
                    );
                    ValuationSummaryCalculator.ObjectiveBandVerdict bandVerdict = ValuationSummaryCalculator.computeObjectiveBandAndVerdict(
                            freshEval.evaluated().price(), freshEval.historicalRef(), peerRef);
                    existing.setVerdict(bandVerdict.verdict());
                    existing.setFairValueLow(bandVerdict.low());
                    existing.setFairValueMid(bandVerdict.mid());
                    existing.setFairValueHigh(bandVerdict.high());
                    existing.setFundamentalBacking(freshEval.builtSnapshot().getFundamentalBacking());
                    entityManager.merge(existing);
                    entityManager.flush();
                }
            }
        }));

        return getCustomPeers(target.getFigi());
    }

    /** Controller 入口：清空目標股自訂對標組並即時還原為預設產業規模分層 verdict。 */
    public CustomPeerGroupDto clearCustomPeers(String figi) {
        return saveCustomPeers(figi, List.of(), null);
    }

    private Stock requireTargetStock(String figi) {
        if (figi == null || figi.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "figi is required");
        }
        Stock stock = stocks.findById(figi.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        if (!SecurityTypePolicy.supportsValuation(stock.getStockType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "STOCK_TYPE_NOT_SUPPORTED");
        }
        return stock;
    }

    private List<PendingTargetEvaluation> evaluateTargetBatchInMemory(
            List<Stock> batchStocks,
            Map<String, StockValuationLatest> priorLatestMap,
            LocalDate requestedDate,
            Map<String, LocalDate> explicitDateByFigi,
            LocalDate lastClosed,
            Instant calculatedAt,
            boolean directRefresh) {
        List<String> activeFigis = batchStocks.stream().map(Stock::getFigi).toList();
        Map<String, StockStatus> statusPreview = statuses.findAllById(activeFigis).stream()
                .collect(Collectors.toMap(StockStatus::getFigi, row -> row));

        Map<String, LocalDate> dateByFigi = new HashMap<>();
        Map<LocalDate, List<Stock>> stocksByDate = new LinkedHashMap<>();
        for (Stock stock : batchStocks) {
            String figi = stock.getFigi();
            LocalDate targetDay = explicitDateByFigi != null && explicitDateByFigi.containsKey(figi)
                    ? explicitDateByFigi.get(figi)
                    : (requestedDate != null ? requestedDate : effectiveLatestQuoteDate(statusPreview.get(figi), lastClosed));
            StockValuationLatest prior = priorLatestMap.get(figi);
            if (directRefresh && prior != null && prior.getValuationDate() != null && targetDay.isBefore(prior.getValuationDate())) {
                continue;
            }
            dateByFigi.put(figi, targetDay);
            stocksByDate.computeIfAbsent(targetDay, k -> new ArrayList<>()).add(stock);
        }
        if (dateByFigi.isEmpty()) return List.of();

        List<String> eligibleFigis = new ArrayList<>(dateByFigi.keySet());
        Map<String, Map<LocalDate, StockQuote>> quotesByFigiAndDate = new HashMap<>();
        Map<LocalDate, Map<String, StockShareObservation>> closingSnapsByDate = new HashMap<>();
        Map<String, Map<LocalDate, StockQuote>> monthEndQuotesByFigi = new HashMap<>();
        Map<String, IndustryModelContext> modelsByFigi = new HashMap<>();

        for (Map.Entry<LocalDate, List<Stock>> entry : stocksByDate.entrySet()) {
            LocalDate d = entry.getKey();
            List<Stock> dateStocks = entry.getValue();
            List<String> dateFigis = dateStocks.stream().map(Stock::getFigi).toList();
            Instant dClose = calendar.getSessionCloseAt(d);

            List<StockQuote> dayQuotes = quotes.findStoredQuotes(dateFigis, d);
            if (dayQuotes != null) {
                for (StockQuote q : dayQuotes) {
                    quotesByFigiAndDate.computeIfAbsent(q.getFigi(), k -> new HashMap<>()).put(d, q);
                }
            }
            List<StockShareObservation> snapsList = shareObservations
                    .findLatestClosingSnapshotAt(dateFigis, calendar.getSessionOpenAt(d), dClose, calculatedAt);
            Map<String, StockShareObservation> snaps = snapsList == null ? Map.of()
                    : snapsList.stream().collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
            closingSnapsByDate.put(d, snaps);

            List<StockQuote> monthRows = quotes.findMonthEndQuotes(dateFigis, d.minusYears(5), d);
            Map<String, List<StockQuote>> groupedMonthRows = new HashMap<>();
            if (monthRows != null) {
                for (StockQuote mq : monthRows) {
                    groupedMonthRows.computeIfAbsent(mq.getFigi(), k -> new ArrayList<>()).add(mq);
                }
            }
            for (String f : dateFigis) {
                List<StockQuote> sorted = new ArrayList<>(groupedMonthRows.getOrDefault(f, List.of()));
                StockQuote dayQ = quotesByFigiAndDate.getOrDefault(f, Map.of()).get(d);
                if (dayQ != null && sorted.stream().noneMatch(item -> d.equals(item.getQuoteDate()))) {
                    sorted.add(dayQ);
                }
                sorted.sort(Comparator.comparing(StockQuote::getQuoteDate));
                Map<LocalDate, StockQuote> qMap = new LinkedHashMap<>();
                for (StockQuote sq : sorted) {
                    if (!sq.getQuoteDate().isAfter(d)) {
                        qMap.put(sq.getQuoteDate(), sq);
                    }
                }
                monthEndQuotesByFigi.put(f, qMap);
            }

            modelsByFigi.putAll(resolveBatchIndustryModels(dateStocks, dClose, calculatedAt));
        }

        Map<String, List<StockFinancialPeriod>> periodsByFigi = financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(eligibleFigis)
                .stream().collect(Collectors.groupingBy(StockFinancialPeriod::getFigi));
        Map<String, List<StockSplit>> splitsByFigi = splits.findByFigiInOrderByExecutionDateAsc(eligibleFigis).stream()
                .collect(Collectors.groupingBy(StockSplit::getFigi));

        Set<String> ciks = new LinkedHashSet<>();
        for (Stock s : batchStocks) {
            LocalDate d = dateByFigi.get(s.getFigi());
            if (d != null) {
                String resolved = resolveCik(s, d);
                if (resolved != null && !resolved.isBlank()) ciks.add(resolved);
            }
        }
        Set<String> multiSecurityIssuers = ciks.isEmpty() ? Set.of()
                : stocks.findMultiSecurityIssuers(ciks, ActiveStatus.ACTIVE);
        Map<String, List<SecFinancialFact>> multiClassShareFactsByCik = multiSecurityIssuers.isEmpty() ? Map.of()
                : secFacts.findLatestIssuerShareFacts(multiSecurityIssuers, calculatedAt).stream()
                        .collect(Collectors.groupingBy(SecFinancialFact::getCik));
        List<StockShareObservation> latestObsRows = shareObservations.findLatestAt(eligibleFigis, calculatedAt);
        Map<String, StockShareObservation> latestObsMap = latestObsRows == null ? Map.of()
                : latestObsRows.stream().collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));

        Map<String, AdrContext> adrContextsByFigi = loadAdrContexts(batchStocks);
        LocalDate batchMinDate = dateByFigi.values().stream().min(LocalDate::compareTo).orElse(lastClosed).minusYears(5);
        LocalDate batchMaxDate = dateByFigi.values().stream().max(LocalDate::compareTo).orElse(lastClosed);
        List<StockFinancialPeriod> allBatchPeriods = periodsByFigi.values().stream().flatMap(List::stream).toList();
        FxRateLookup batchFxLookup = preloadFxLookup(allBatchPeriods, batchMinDate, batchMaxDate);

        List<PendingTargetEvaluation> results = new ArrayList<>(eligibleFigis.size());
        for (Stock stock : batchStocks) {
            String figi = stock.getFigi();
            LocalDate stockValDate = dateByFigi.get(figi);
            if (stockValDate == null) continue;
            StockStatus status = statusPreview.get(figi);
            if (directRefresh && status == null) continue;

            LocalDate stockEffectiveLatest = effectiveLatestQuoteDate(status, lastClosed);
            Instant stockCutoff = calendar.getSessionCloseAt(stockValDate);
            StockQuote stockQuote = quotesByFigiAndDate.getOrDefault(figi, Map.of()).get(stockValDate);
            List<StockFinancialPeriod> stockPeriods = periodsByFigi.getOrDefault(figi, List.of());
            StockFinancialPeriod matched = matchPeriod(stockPeriods, stockValDate);
            List<StockSplit> stockSplits = splitsByFigi.getOrDefault(figi, List.of());
            String cik = resolveCik(stock, stockValDate);
            boolean multiClass = cik != null && !SecurityTypePolicy.isAdrType(stock.getStockType())
                    && multiSecurityIssuers.contains(cik);
            StockShareObservation latestObs = latestObsMap.get(figi);
            StockShareObservation closingSnap = closingSnapsByDate.getOrDefault(stockValDate, Map.of()).get(figi);
            StockShareObservation primaryObs = closingSnap != null ? closingSnap : latestObs;
            boolean verifiedOneToOne = multiClass && isMultiClassVerifiedFromRawFacts(
                    latestObs, multiClassShareFactsByCik.getOrDefault(cik, List.of()), stockSplits, calculatedAt);
            IndustryModelContext modelCtx = modelsByFigi.get(figi);
            boolean financialInstitution = modelCtx != null && modelCtx.financialInstitution();
            AdrContext adrCtx = adrContextsByFigi.getOrDefault(figi, AdrContext.nonAdr());

            ShareEvaluation dayShareEval = matched == null
                    ? evaluateQuoteOnlyMarketCap(stockQuote, stockSplits, status, primaryObs, multiClass, verifiedOneToOne, stockValDate, stockCutoff, calculatedAt, stockEffectiveLatest)
                    : evaluateDayShares(stockQuote, matched, stockSplits, status,
                            primaryObs, latestObs, multiClass, verifiedOneToOne, stockValDate, stockCutoff, calculatedAt, stockEffectiveLatest);

            ValuationResultDto evaluated = evaluateSingleDay(
                    stock, status, stockQuote, matched, stockSplits,
                    primaryObs, latestObs, multiClass, verifiedOneToOne,
                    modelCtx, stockValDate, stockCutoff, calculatedAt, stockEffectiveLatest, dayShareEval,
                    adrCtx, batchFxLookup);

            String selectedMetric = selectPrimaryMetricForDay(modelCtx, evaluated.metrics(), matched);
            String resolvedModelCode = modelCtx != null ? modelCtx.modelCode()
                    : evaluated.modelCode() != null ? evaluated.modelCode() : IndustryValuationProposalService.MODEL_NON_FINANCIAL;
            ValuationResultDto normalizedEvaluated = evaluated.withReferences(resolvedModelCode, selectedMetric, null, null, null);
            DayDenominators denoms = resolveDayDenominators(stock, status, matched, stockSplits, stockValDate, dayShareEval, adrCtx, batchFxLookup);

            Map<LocalDate, StockQuote> monthQuoteMap = monthEndQuotesByFigi.getOrDefault(figi, Map.of());
            List<ValuationResultDto> monthlyHistory = buildHistoryFromLoaded(
                    stock, status, monthQuoteMap, stockPeriods, stockSplits, closingSnap, latestObs,
                    multiClass, verifiedOneToOne, modelCtx, stockEffectiveLatest, calculatedAt, true, adrCtx, batchFxLookup);
            BigDecimal currentSelectedMultiple = positiveMetricValue(normalizedEvaluated.metrics(), selectedMetric);
            ValuationReferenceDto historicalRef = buildHistoricalReference(
                    selectedMetric, currentSelectedMultiple, monthlyHistory, matched, denoms,
                    stock.getCurrency(), financialInstitution, stockValDate);

            BigDecimal closePrice = stockQuote != null ? stockQuote.getClosePrice() : null;
            BigDecimal peAtDay = positiveMetricValue(normalizedEvaluated.metrics(), "PE");
            String peMetricReason = normalizedEvaluated.metrics() != null && normalizedEvaluated.metrics().pe() != null
                    && normalizedEvaluated.metrics().pe().reasonCode() != null
                    ? normalizedEvaluated.metrics().pe().reasonCode() : denoms.peReason();
            boolean unverifiedAdr = adrCtx.adr() && !adrCtx.verified();
            BigDecimal marketCap = unverifiedAdr ? null : (dayShareEval == null ? null : dayShareEval.marketCap());
            String sharesReason = unverifiedAdr ? "ADR_RATIO_UNVERIFIED" : (dayShareEval == null ? null : dayShareEval.sharesReason());
            String fxBlockReason = unverifiedAdr ? "ADR_RATIO_UNVERIFIED" : denoms.fxFailureReason();
            FundamentalSnapshotDto fundamentalsDto = fundamentalCalculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                    stockPeriods, matched, stockSplits, stockValDate, stock.getCurrency(),
                    closePrice, marketCap, sharesReason, denoms.epsAtDay(), peMetricReason, peAtDay,
                    financialInstitution, splitSyncError(status),
                    denoms.fxRateToQuote(), fxBlockReason, adrCtx.adr() && adrCtx.verified()
            ));

            BigDecimal revGrowthYoy = extractAvailableMetric(fundamentalsDto == null ? null : fundamentalsDto.revenueGrowthYoy());
            BigDecimal roe = extractAvailableMetric(fundamentalsDto == null ? null : fundamentalsDto.roe());
            BigDecimal fcfMargin = financialInstitution ? null
                    : extractAvailableMetric(fundamentalsDto == null ? null : fundamentalsDto.fcfMargin());
            String marketCapTier = resolveMarketCapTier(stock.getCurrency(), marketCap);
            BigDecimal histPercentile = historicalRef != null && "AVAILABLE".equals(historicalRef.status())
                    ? historicalRef.currentPercentile() : null;
            boolean currentLoss = isCurrentTtmLoss(matched, status, stockValDate, adrCtx.adr(), adrCtx.verified());
            String fundamentalBacking = ValuationSummaryCalculator.evaluateFundamentalBacking(fundamentalsDto, currentLoss);

            StockValuationLatest snapshot = new StockValuationLatest();
            populateLatestEntity(snapshot, normalizedEvaluated, marketCap, marketCapTier,
                    revGrowthYoy, roe, fcfMargin, histPercentile, null, null, null, null, fundamentalBacking);

            String resolvedIndustryCode = modelCtx != null && modelCtx.industryCode() != null
                    ? modelCtx.industryCode() : normalizedEvaluated.industryCode();
            StockValuationLatest priorRow = priorLatestMap.get(figi);
            Instant priorCalcAt = priorRow != null ? priorRow.getCalculatedAt() : null;
            Instant maxPeriodUpdatedAt = stockPeriods.stream().map(StockFinancialPeriod::getUpdatedAt)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            results.add(new PendingTargetEvaluation(
                    stock, stockValDate, priorCalcAt, normalizedEvaluated, snapshot, selectedMetric,
                    resolvedIndustryCode, financialInstitution, matched, denoms, historicalRef,
                    marketCap, revGrowthYoy, directRefresh, stockPeriods.size(), maxPeriodUpdatedAt
            ));
        }
        return results;
    }

    private void loadExternalAffectedEvaluations(
            Set<String> externalFigis,
            Map<String, PendingTargetEvaluation> pendingByFigi,
            LocalDate lastClosed,
            Instant calculatedAt) {
        if (externalFigis == null || externalFigis.isEmpty()) return;
        for (List<String> part : Lists.partition(new ArrayList<>(externalFigis), LATEST_BATCH_SIZE)) {
            Map<String, StockValuationLatest> storedMap = latestValuations.findByFigiIn(part).stream()
                    .filter(v -> v.getValuationDate() != null)
                    .collect(Collectors.toMap(StockValuationLatest::getFigi, v -> v));
            if (storedMap.isEmpty()) continue;

            List<Stock> extStocks = stocks.findAllById(storedMap.keySet()).stream()
                    .filter(s -> s.isTracked() && s.getActiveStatus() == ActiveStatus.ACTIVE
                            && SecurityTypePolicy.supportsValuation(s.getStockType()))
                    .toList();
            if (extStocks.isEmpty()) continue;

            Map<String, LocalDate> explicitDates = new HashMap<>();
            for (Stock s : extStocks) {
                StockValuationLatest sv = storedMap.get(s.getFigi());
                if (sv != null && sv.getValuationDate() != null) {
                    explicitDates.put(s.getFigi(), sv.getValuationDate());
                }
            }
            List<PendingTargetEvaluation> extEvaluated = evaluateTargetBatchInMemory(
                    extStocks, storedMap, null, explicitDates, lastClosed, calculatedAt, false);
            for (PendingTargetEvaluation item : extEvaluated) {
                pendingByFigi.put(item.stock().getFigi(), item);
            }
        }
    }

    private void runWithPublishLock(Runnable publishTx) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            localPublishLock.lock();
            boolean registered = false;
            try {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            localPublishLock.unlock();
                        }
                    });
                    registered = true;
                }
                publishTx.run();
            } finally {
                if (!registered) {
                    localPublishLock.unlock();
                }
            }
            return;
        }
        localPublishLock.lock();
        try {
            publishTx.run();
        } finally {
            localPublishLock.unlock();
        }
    }

    private void commitEvaluatedTargetsWithPeerVerdicts(
            Map<String, PendingTargetEvaluation> pendingByFigi,
            Collection<String> explicitlyDisabledFigis,
            LocalDate lastClosed,
            Instant calculatedAt) {
        Set<String> initialCheck = new LinkedHashSet<>(pendingByFigi.keySet());
        if (explicitlyDisabledFigis != null) {
            initialCheck.addAll(explicitlyDisabledFigis);
        }
        if (initialCheck.isEmpty()) return;

        // 先取得統一交易層級發布鎖，於鎖內先確認真正將完整發布之變動集，再依精確 (industryCode, valuationDate) 配對決定反向目標並單次排序取鎖
        runWithPublishLock(() -> transactions.executeWithoutResult(tx -> {
            entityManager.flush();
            entityManager.clear();
            statuses.acquireValuationPublishLock();

            Map<String, PendingTargetEvaluation> workingPending = new LinkedHashMap<>(pendingByFigi);
            Set<String> directInputFigis = new LinkedHashSet<>();
            for (Map.Entry<String, PendingTargetEvaluation> entry : workingPending.entrySet()) {
                if (entry.getValue() != null && entry.getValue().directRefresh()) {
                    directInputFigis.add(entry.getKey());
                }
            }
            if (explicitlyDisabledFigis != null) {
                directInputFigis.addAll(explicitlyDisabledFigis);
            }
            if (directInputFigis.isEmpty()) return;

            Map<String, Stock> preDirectStocks = new HashMap<>();
            Map<String, StockStatus> preDirectStatuses = new HashMap<>();
            Map<String, StockValuationLatest> preDirectLatest = new HashMap<>();
            for (List<String> directPart : Lists.partition(new ArrayList<>(directInputFigis), 500)) {
                List<Stock> sRows = stocks.findAllById(directPart);
                if (sRows != null) {
                    for (Stock s : sRows) preDirectStocks.put(s.getFigi(), s);
                }
                List<StockStatus> stRows = statuses.findAllById(directPart);
                if (stRows != null) {
                    for (StockStatus st : stRows) preDirectStatuses.put(st.getFigi(), st);
                }
                List<StockValuationLatest> lvRows = latestValuations.findByFigiIn(directPart);
                if (lvRows != null) {
                    for (StockValuationLatest lv : lvRows) preDirectLatest.put(lv.getFigi(), lv);
                }
            }

            Set<String> preCommittableDirectFigis = new LinkedHashSet<>();
            Set<String> preUntrackedFigis = new HashSet<>();
            if (explicitlyDisabledFigis != null) {
                preUntrackedFigis.addAll(explicitlyDisabledFigis);
            }
            Set<IndustryDateKey> changedPairs = new LinkedHashSet<>();

            for (String figi : directInputFigis) {
                StockValuationLatest ex = preDirectLatest.get(figi);
                if (explicitlyDisabledFigis != null && explicitlyDisabledFigis.contains(figi)) {
                    if (ex != null) {
                        addIndustryDatePair(changedPairs, ex.getIndustryCode(), ex.getValuationDate());
                    }
                    continue;
                }
                PendingTargetEvaluation pending = workingPending.get(figi);
                StockStatus st = preDirectStatuses.get(figi);
                Stock curStock = preDirectStocks.get(figi);
                if (curStock == null && pending != null) {
                    curStock = pending.stock();
                }
                if (curStock != null && (!curStock.isTracked() || curStock.getActiveStatus() != ActiveStatus.ACTIVE)) {
                    preUntrackedFigis.add(figi);
                    continue;
                }
                if (pending == null || st == null || !pending.directRefresh()) {
                    continue;
                }
                LocalDate valDate = pending.valuationDate();
                if (ex != null && ex.getValuationDate() != null) {
                    if (valDate.isBefore(ex.getValuationDate())) {
                        continue;
                    }
                    if (valDate.equals(ex.getValuationDate())
                            && ex.getCalculatedAt() != null
                            && calculatedAt.isBefore(ex.getCalculatedAt())) {
                        continue;
                    }
                }
                preCommittableDirectFigis.add(figi);
                if (ex != null) {
                    addIndustryDatePair(changedPairs, ex.getIndustryCode(), ex.getValuationDate());
                }
                addIndustryDatePair(changedPairs, pending.industryCode(), valDate);
            }

            Set<String> effectiveChangedOrDisabled = new LinkedHashSet<>(preCommittableDirectFigis);
            if (explicitlyDisabledFigis != null) {
                effectiveChangedOrDisabled.addAll(explicitlyDisabledFigis);
            }
            if (effectiveChangedOrDisabled.isEmpty()) {
                return;
            }

            // 因版本較新而跳過完整覆寫的直接目標（如快批已先發布的 A），若屬於實際變動集 B 的同業或自訂引用者，仍保留於 reverseTargetFigis 僅更新 verdict
            Set<String> reverseTargetFigis = new LinkedHashSet<>(queryFigisByIndustryDatePairs(changedPairs));
            List<String> currentReferencers = customPeers.findEligibleTargetFigisByPeerFigiIn(effectiveChangedOrDisabled);
            if (currentReferencers != null) reverseTargetFigis.addAll(currentReferencers);
            reverseTargetFigis.removeAll(effectiveChangedOrDisabled);
            reverseTargetFigis.removeAll(preUntrackedFigis);

            workingPending.keySet().removeIf(k -> !preCommittableDirectFigis.contains(k) && !reverseTargetFigis.contains(k));

            TreeSet<String> allLockSet = new TreeSet<>(effectiveChangedOrDisabled);
            allLockSet.addAll(reverseTargetFigis);
            if (allLockSet.isEmpty()) return;
            List<String> sortedLocks = List.copyOf(allLockSet);

            Map<String, StockStatus> lockedStatuses = new HashMap<>();
            Map<String, Stock> lockedStocks = new HashMap<>();
            Map<String, StockValuationLatest> existingLatest = new HashMap<>();
            Map<String, List<StockCustomPeer>> lockedCustomMap = new HashMap<>();
            Set<String> allReferencedCustomPeerFigis = new LinkedHashSet<>();

            for (List<String> lockBatch : Lists.partition(sortedLocks, 500)) {
                List<StockStatus> statusRows = statuses.findAllByIdForUpdate(lockBatch);
                if (statusRows != null) {
                    for (StockStatus st : statusRows) lockedStatuses.put(st.getFigi(), st);
                }
                List<Stock> stockRows = stocks.findAllById(lockBatch);
                if (stockRows != null) {
                    for (Stock s : stockRows) lockedStocks.put(s.getFigi(), s);
                }
                List<StockValuationLatest> latestRows = latestValuations.findByFigiIn(lockBatch);
                if (latestRows != null) {
                    for (StockValuationLatest lv : latestRows) existingLatest.put(lv.getFigi(), lv);
                }
                List<StockCustomPeer> customRows = customPeers.findByFigiInOrderByFigiAscSortOrderAsc(lockBatch);
                if (customRows != null) {
                    for (StockCustomPeer r : customRows) {
                        lockedCustomMap.computeIfAbsent(r.getFigi(), k -> new ArrayList<>()).add(r);
                        allReferencedCustomPeerFigis.add(r.getPeerFigi());
                    }
                }
            }

            Set<String> committableDirectFigis = new LinkedHashSet<>();
            Set<String> concurrentlyUntrackedFigis = new HashSet<>(preUntrackedFigis);

            for (String figi : sortedLocks) {
                PendingTargetEvaluation pending = workingPending.get(figi);
                StockStatus status = lockedStatuses.get(figi);
                Stock currentStock = lockedStocks.get(figi);
                if (currentStock == null && pending != null) {
                    currentStock = pending.stock();
                }
                if (currentStock != null && (!currentStock.isTracked() || currentStock.getActiveStatus() != ActiveStatus.ACTIVE)) {
                    concurrentlyUntrackedFigis.add(figi);
                    continue;
                }
                if (!preCommittableDirectFigis.contains(figi) || pending == null || status == null || !pending.directRefresh()) {
                    continue;
                }
                StockValuationLatest ex = existingLatest.get(figi);
                LocalDate valDate = pending.valuationDate();
                if (ex != null && ex.getValuationDate() != null) {
                    if (valDate.isBefore(ex.getValuationDate())) {
                        continue;
                    }
                    if (valDate.equals(ex.getValuationDate())
                            && ex.getCalculatedAt() != null
                            && calculatedAt.isBefore(ex.getCalculatedAt())) {
                        continue;
                    }
                }
                committableDirectFigis.add(figi);
            }

            if (committableDirectFigis.isEmpty() && (explicitlyDisabledFigis == null || explicitlyDisabledFigis.isEmpty())) {
                return;
            }

            // 鎖內重查直接目標之財季分母簽章（筆數與最新計算時間），防止鎖外讀取舊分母後被併發重建覆蓋發布舊倍數
            Instant evalCutoff = laterInstant(calculatedAt, dateTimeService.now());
            if (!committableDirectFigis.isEmpty()) {
                Map<String, StockFinancialPeriodRepository.FigiPeriodSignature> currentSigs = new HashMap<>();
                for (List<String> sigPart : Lists.partition(new ArrayList<>(committableDirectFigis), 500)) {
                    List<StockFinancialPeriodRepository.FigiPeriodSignature> rows = financialPeriods.findPeriodSignaturesByFigiIn(sigPart);
                    if (rows != null) {
                        for (StockFinancialPeriodRepository.FigiPeriodSignature sig : rows) {
                            currentSigs.put(sig.figi(), sig);
                        }
                    }
                }
                List<Stock> staleDirectStocks = new ArrayList<>();
                Map<String, StockValuationLatest> staleDirectStored = new HashMap<>();
                Map<String, LocalDate> staleDirectDates = new HashMap<>();
                for (String dirFigi : committableDirectFigis) {
                    PendingTargetEvaluation pre = workingPending.get(dirFigi);
                    Stock s = lockedStocks.get(dirFigi);
                    if (pre == null || s == null) continue;
                    StockFinancialPeriodRepository.FigiPeriodSignature cur = currentSigs.get(dirFigi);
                    long curCount = cur == null ? 0L : cur.periodCount();
                    Instant curMaxUpdated = cur == null ? null : cur.maxUpdatedAt();
                    if (curCount != pre.periodCount() || !Objects.equals(curMaxUpdated, pre.maxPeriodUpdatedAt())) {
                        staleDirectStocks.add(s);
                        StockValuationLatest ex = existingLatest.get(dirFigi);
                        if (ex != null) staleDirectStored.put(dirFigi, ex);
                        staleDirectDates.put(dirFigi, pre.valuationDate());
                    }
                }
                if (!staleDirectStocks.isEmpty()) {
                    for (List<Stock> stalePart : Lists.partition(staleDirectStocks, LATEST_BATCH_SIZE)) {
                        List<PendingTargetEvaluation> reEvaluated = evaluateTargetBatchInMemory(
                                stalePart, staleDirectStored, null, staleDirectDates, lastClosed, evalCutoff, true);
                        for (PendingTargetEvaluation item : reEvaluated) {
                            workingPending.put(item.stock().getFigi(), item);
                        }
                    }
                }
            }

            // 批次補評鎖內新增、版本變更或由較新直接目標降級為僅更新 verdict 之反向目標
            List<Stock> toEvalStocks = new ArrayList<>();
            Map<String, StockValuationLatest> toEvalStored = new HashMap<>();
            Map<String, LocalDate> toEvalDates = new HashMap<>();
            for (String revFigi : reverseTargetFigis) {
                if (concurrentlyUntrackedFigis.contains(revFigi)) {
                    workingPending.remove(revFigi);
                    continue;
                }
                Stock s = lockedStocks.get(revFigi);
                StockValuationLatest ex = existingLatest.get(revFigi);
                if (s == null || ex == null || ex.getValuationDate() == null
                        || !s.isTracked() || s.getActiveStatus() != ActiveStatus.ACTIVE
                        || !SecurityTypePolicy.supportsValuation(s.getStockType())) {
                    workingPending.remove(revFigi);
                    continue;
                }
                PendingTargetEvaluation pre = workingPending.get(revFigi);
                boolean needsEval = pre == null
                        || pre.directRefresh()
                        || !Objects.equals(ex.getValuationDate(), pre.valuationDate())
                        || !Objects.equals(ex.getCalculatedAt(), pre.priorCalculatedAt());
                if (needsEval) {
                    workingPending.remove(revFigi);
                    toEvalStocks.add(s);
                    toEvalStored.put(revFigi, ex);
                    toEvalDates.put(revFigi, ex.getValuationDate());
                    evalCutoff = laterInstant(evalCutoff, ex.getCalculatedAt());
                }
            }
            if (!toEvalStocks.isEmpty()) {
                for (List<Stock> evalPart : Lists.partition(toEvalStocks, LATEST_BATCH_SIZE)) {
                    List<PendingTargetEvaluation> evaluatedBatch = evaluateTargetBatchInMemory(
                            evalPart, toEvalStored, null, toEvalDates, lastClosed, evalCutoff, false);
                    for (PendingTargetEvaluation item : evaluatedBatch) {
                        workingPending.put(item.stock().getFigi(), item);
                    }
                }
            }

            if (workingPending.isEmpty()) {
                return;
            }

            List<String> finalTargets = workingPending.keySet().stream().sorted().toList();
            Set<String> snapshotLoadIndustries = new LinkedHashSet<>();
            Set<LocalDate> snapshotLoadDates = new LinkedHashSet<>();
            for (IndustryDateKey pair : changedPairs) {
                snapshotLoadIndustries.add(pair.industryCode());
                snapshotLoadDates.add(pair.valuationDate());
            }
            for (String figi : finalTargets) {
                PendingTargetEvaluation item = workingPending.get(figi);
                if (item != null) {
                    if (item.industryCode() != null && !item.industryCode().isBlank()) {
                        snapshotLoadIndustries.add(item.industryCode());
                    }
                    if (item.valuationDate() != null) {
                        snapshotLoadDates.add(item.valuationDate());
                    }
                }
            }

            Set<String> excludedFromDbOverlay = new HashSet<>(committableDirectFigis);
            excludedFromDbOverlay.addAll(concurrentlyUntrackedFigis);

            Map<String, IssuerSnapshotRow> snapshotOverlayByFigi = new LinkedHashMap<>();
            if (!snapshotLoadIndustries.isEmpty() && !snapshotLoadDates.isEmpty()) {
                List<Object[]> dbIndustryRows = latestValuations.findActiveTrackedPeerSnapshotsIn(
                        snapshotLoadIndustries, snapshotLoadDates);
                if (dbIndustryRows != null) {
                    for (Object[] tuple : dbIndustryRows) {
                        if (tuple != null && tuple.length >= 2 && tuple[0] instanceof String rawKey
                                && tuple[1] instanceof StockValuationLatest snap) {
                            if (!excludedFromDbOverlay.contains(snap.getFigi())) {
                                snapshotOverlayByFigi.put(snap.getFigi(), new IssuerSnapshotRow(rawKey, snap));
                            }
                        }
                    }
                }
            }
            if (!allReferencedCustomPeerFigis.isEmpty() && !snapshotLoadDates.isEmpty()) {
                List<Object[]> dbCustomRows = latestValuations.findActiveTrackedCustomPeerSnapshots(
                        allReferencedCustomPeerFigis, snapshotLoadDates);
                if (dbCustomRows != null) {
                    for (Object[] tuple : dbCustomRows) {
                        if (tuple != null && tuple.length >= 2 && tuple[0] instanceof String rawKey
                                && tuple[1] instanceof StockValuationLatest snap) {
                            if (!excludedFromDbOverlay.contains(snap.getFigi())) {
                                snapshotOverlayByFigi.put(snap.getFigi(), new IssuerSnapshotRow(rawKey, snap));
                            }
                        }
                    }
                }
            }
            for (String figi : committableDirectFigis) {
                PendingTargetEvaluation item = workingPending.get(figi);
                Stock s = lockedStocks.getOrDefault(figi, item.stock());
                String rawKey = s.getCik() != null && !s.getCik().isBlank() ? s.getCik() : s.getFigi();
                snapshotOverlayByFigi.put(figi, new IssuerSnapshotRow(rawKey, item.builtSnapshot()));
            }

            Map<String, List<IssuerSnapshotRow>> overlayByIndustryAndDate = new HashMap<>();
            for (IssuerSnapshotRow row : snapshotOverlayByFigi.values()) {
                StockValuationLatest snap = row.snapshot();
                if (snap.getIndustryCode() != null && snap.getValuationDate() != null) {
                    String key = snap.getIndustryCode() + "|" + snap.getValuationDate();
                    overlayByIndustryAndDate.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                }
            }

            List<StockStatus> updatedStatuses = new ArrayList<>();
            for (String figi : finalTargets) {
                PendingTargetEvaluation pending = workingPending.get(figi);
                StockStatus status = lockedStatuses.get(figi);
                if (pending == null || status == null || concurrentlyUntrackedFigis.contains(figi)) {
                    continue;
                }
                if (pending.directRefresh() && !committableDirectFigis.contains(figi)) {
                    continue;
                }
                Stock currentStock = lockedStocks.getOrDefault(figi, pending.stock());
                StockValuationLatest existing = existingLatest.get(figi);
                LocalDate valDate = pending.valuationDate();
                if (!pending.directRefresh() && (existing == null || !Objects.equals(existing.getValuationDate(), valDate))) {
                    continue;
                }

                List<StockCustomPeer> targetCustom = lockedCustomMap.getOrDefault(figi, List.of());
                List<IssuerSnapshotRow> customCandidateRows = new ArrayList<>(targetCustom.size());
                for (StockCustomPeer cp : targetCustom) {
                    IssuerSnapshotRow r = snapshotOverlayByFigi.get(cp.getPeerFigi());
                    if (r != null) customCandidateRows.add(r);
                }
                List<IssuerSnapshotRow> industryCandidateRows = pending.industryCode() == null ? List.of()
                        : overlayByIndustryAndDate.getOrDefault(pending.industryCode() + "|" + valDate, List.of());

                ValuationReferenceDto peerRef = computePeerReferenceFromSnapshots(
                        currentStock,
                        pending.selectedMetric(),
                        pending.evaluated().metrics(),
                        pending.industryCode(),
                        valDate,
                        false,
                        pending.matchedPeriod(),
                        pending.denoms(),
                        currentStock.getCurrency(),
                        pending.marketCap(),
                        pending.revenueGrowthYoy(),
                        pending.financialInstitution(),
                        targetCustom,
                        customCandidateRows,
                        industryCandidateRows
                );

                ValuationSummaryCalculator.ObjectiveBandVerdict bandVerdict = ValuationSummaryCalculator.computeObjectiveBandAndVerdict(
                        pending.evaluated().price(), pending.historicalRef(), peerRef);

                if (pending.directRefresh()) {
                    StockValuationLatest target = existing != null ? existing : new StockValuationLatest();
                    populateLatestEntity(
                            target,
                            pending.evaluated(),
                            pending.builtSnapshot().getMarketCap(),
                            pending.builtSnapshot().getMarketCapTier(),
                            pending.builtSnapshot().getRevenueGrowthYoy(),
                            pending.builtSnapshot().getRoe(),
                            pending.builtSnapshot().getFcfMargin(),
                            pending.builtSnapshot().getHistoricalPercentile(),
                            bandVerdict.verdict(),
                            bandVerdict.low(),
                            bandVerdict.mid(),
                            bandVerdict.high(),
                            pending.builtSnapshot().getFundamentalBacking()
                    );
                    target.setCalculatedAt(calculatedAt);
                    if (existing != null) {
                        entityManager.merge(target);
                    } else {
                        entityManager.persist(target);
                    }
                    if (target.hasAvailableMetric() && (status.getValuationDate() == null || !valDate.isBefore(status.getValuationDate()))) {
                        status.setValuationDate(valDate);
                    }
                    status.setValuationMessage(truncateSummary(valDate + ":" + formatStatusSummary(target)));
                    updatedStatuses.add(status);
                } else {
                    existing.setVerdict(bandVerdict.verdict());
                    existing.setFairValueLow(bandVerdict.low());
                    existing.setFairValueMid(bandVerdict.mid());
                    existing.setFairValueHigh(bandVerdict.high());
                    existing.setFundamentalBacking(pending.builtSnapshot().getFundamentalBacking());
                    entityManager.merge(existing);
                }
            }
            entityManager.flush();
            if (!updatedStatuses.isEmpty()) {
                statuses.saveAllAndFlush(updatedStatuses);
            }
        }));
    }

    /** 找出尚未建立任何財季分母列但已有財報或 SEC 紀錄的股票 FIGI。 */
    @Transactional(readOnly = true)
    public List<String> findFigisMissingFinancialPeriods(Collection<String> candidateFigis) {
        if (candidateFigis == null || candidateFigis.isEmpty()) return List.of();
        List<String> list = candidateFigis.stream().distinct().toList();
        Set<String> existing = new HashSet<>();
        for (List<String> part : Lists.partition(list, 500)) {
            existing.addAll(financialPeriods.findExistingFigis(part));
        }
        return list.stream().filter(figi -> !existing.contains(figi)).toList();
    }

    /** 按 AGENTS.md 規則為交易日 D 配對財季分母：優先 period_start <= D <= period_end，缺當期時回退 period_end < D 的最近一列。 */
    public static StockFinancialPeriod matchPeriod(List<StockFinancialPeriod> periods, LocalDate day) {
        if (periods == null || periods.isEmpty() || day == null) return null;
        StockFinancialPeriod containing = null;
        for (StockFinancialPeriod period : periods) {
            if (period.getPeriodStart() != null && !day.isBefore(period.getPeriodStart()) && !day.isAfter(period.getPeriodEnd())) {
                if (containing == null || period.getPeriodEnd().isBefore(containing.getPeriodEnd())) {
                    containing = period;
                }
            }
        }
        if (containing != null) return containing;

        StockFinancialPeriod latestPrior = null;
        for (StockFinancialPeriod period : periods) {
            if (!period.getPeriodEnd().isAfter(day)) {
                if (latestPrior == null || period.getPeriodEnd().isAfter(latestPrior.getPeriodEnd())) {
                    latestPrior = period;
                }
            }
        }
        return latestPrior;
    }

    private List<ValuationResultDto> evaluateHistoryRange(Stock stock, LocalDate from, LocalDate through, boolean monthly, Instant now) {
        String figi = stock.getFigi();
        List<StockFinancialPeriod> periods = financialPeriods.findByFigiOrderByPeriodEndAsc(figi);
        List<StockQuote> quoteRows = quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(figi, from, through);
        if (quoteRows.isEmpty()) return List.of();
        Map<LocalDate, StockQuote> quoteByDate = new LinkedHashMap<>();
        for (StockQuote q : quoteRows) quoteByDate.put(q.getQuoteDate(), q);

        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        StockStatus status = statuses.findById(figi).orElse(null);
        LocalDate effectiveLatest = effectiveLatestQuoteDate(status, lastClosed);
        List<StockSplit> stockSplits = splits.findByFigiInOrderByExecutionDateAsc(List.of(figi));
        String cik = resolveCik(stock, through);
        boolean multiClass = cik != null && !SecurityTypePolicy.isAdrType(stock.getStockType())
                && !stocks.findMultiSecurityIssuers(List.of(cik), ActiveStatus.ACTIVE).isEmpty();
        StockShareObservation latestObs = shareObservations
                .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", now).orElse(null);
        StockShareObservation closingSnap = null;
        if (!through.isBefore(effectiveLatest)) {
            List<StockShareObservation> snaps = shareObservations.findLatestClosingSnapshotAt(
                    List.of(figi), calendar.getSessionOpenAt(effectiveLatest), calendar.getSessionCloseAt(effectiveLatest), now);
            if (!snaps.isEmpty()) closingSnap = snaps.getFirst();
        }
        boolean verifiedOneToOne = multiClass && isMultiClassVerifiedOneToOne(cik, latestObs, stockSplits, now);
        Instant throughClose = calendar.getSessionCloseAt(through);
        String industryCode = industries.findAt(figi, throughClose).map(row -> row.getIndustry().getCode()).orElse(null);
        IndustryModelContext modelCtx = resolveIndustryModel(stock, industryCode, throughClose, now);
        AdrContext adrCtx = resolveAdrContext(stock);
        FxRateLookup fxLookup = preloadFxLookup(periods, from, through);

        return buildHistoryFromLoaded(stock, status, quoteByDate, periods, stockSplits,
                closingSnap, latestObs, multiClass, verifiedOneToOne, modelCtx, effectiveLatest, now, monthly, adrCtx, fxLookup);
    }

    private LocalDate effectiveLatestQuoteDate(StockStatus status, LocalDate lastClosed) {
        if (status != null && status.getQuoteDate() != null && status.getQuoteDate().isBefore(lastClosed)) {
            return status.getQuoteDate();
        }
        return lastClosed;
    }

    private List<ValuationResultDto> buildHistoryFromLoaded(
            Stock stock, StockStatus status, Map<LocalDate, StockQuote> quoteByDate,
            List<StockFinancialPeriod> periods, List<StockSplit> stockSplits,
            StockShareObservation closingSnap, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne, IndustryModelContext modelCtx,
            LocalDate lastClosed, Instant calculatedAt, boolean monthly,
            AdrContext adrCtx, FxRateLookup fxLookup) {
        if (quoteByDate.isEmpty()) return List.of();
        Collection<LocalDate> targetDays;
        if (monthly) {
            Map<YearMonth, LocalDate> monthLastQuoteDays = new LinkedHashMap<>();
            for (LocalDate quoteDate : quoteByDate.keySet()) {
                monthLastQuoteDays.put(YearMonth.from(quoteDate), quoteDate);
            }
            targetDays = monthLastQuoteDays.values();
        } else {
            targetDays = quoteByDate.keySet();
        }
        List<ValuationResultDto> results = new ArrayList<>(targetDays.size());
        for (LocalDate day : targetDays) {
            StockQuote quote = quoteByDate.get(day);
            Instant dayCutoff = calendar.isTradingDay(day) ? calendar.getSessionCloseAt(day) : calculatedAt;
            StockFinancialPeriod matched = matchPeriod(periods, day);
            StockShareObservation dayPrimary = filterObsForAdrDay(
                    day.equals(lastClosed) && closingSnap != null ? closingSnap : fallbackObs,
                    adrCtx, day, lastClosed);
            StockShareObservation dayFallback = filterObsForAdrDay(fallbackObs, adrCtx, day, lastClosed);
            results.add(evaluateSingleDay(stock, status, quote, matched, stockSplits,
                    dayPrimary, dayFallback, multiClass, verifiedOneToOne, modelCtx, day, dayCutoff, calculatedAt, lastClosed,
                    null, adrCtx, fxLookup));
        }
        return List.copyOf(results);
    }

    /** 歷史估值日絕不以晚於目標日觀察之 Futu 最新股數快照倒填 ADR 歷史股數，避免誤算回購／增發前市值。 */
    private StockShareObservation filterObsForAdrDay(
            StockShareObservation obs, AdrContext adrCtx, LocalDate day, LocalDate lastClosed) {
        if (obs == null || adrCtx == null || !adrCtx.adr()) {
            return obs;
        }
        if (day != null && day.equals(lastClosed)) {
            return obs;
        }
        if (obs.getObservedAt() == null || day == null) {
            return null;
        }
        LocalDate obsDate = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        return obsDate.isAfter(day) ? null : obs;
    }

    /** 即時計算單一交易日 D 的五項估值倍數（支援非美元財報官方匯率換算與已核實 ADR 公司口徑估值）。 */
    private ValuationResultDto evaluateSingleDay(
            Stock stock, StockStatus status, StockQuote quote, StockFinancialPeriod period,
            List<StockSplit> stockSplits, StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne, IndustryModelContext modelCtx,
            LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed,
            ShareEvaluation precomputedShareEval, AdrContext adrCtx, FxRateLookup fxLookup) {
        String figi = stock.getFigi();
        String quoteCurrency = stock.getCurrency();
        String industryCode = modelCtx == null ? null : modelCtx.industryCode();
        String modelCode = modelCtx == null ? IndustryValuationProposalService.MODEL_NON_FINANCIAL : modelCtx.modelCode();
        boolean financialInstitution = modelCtx != null && modelCtx.financialInstitution();
        AdrContext effectiveAdr = adrCtx != null ? adrCtx : AdrContext.nonAdr();
        FxConversionDto adrBaseMeta = effectiveAdr.adr()
                ? new FxConversionDto(false, period == null ? null : period.getReportingCurrency(), quoteCurrency,
                        null, null, null, true, effectiveAdr.verified() ? effectiveAdr.ratio() : null,
                        effectiveAdr.verified(), effectiveAdr.underlyingSymbol())
                : null;

        if (quote == null || quote.getClosePrice() == null) {
            ValuationResultDto missingPrice = unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, null, "CLOSING_PRICE_MISSING");
            return adrBaseMeta != null ? missingPrice.withFxConversion(adrBaseMeta) : missingPrice;
        }
        if (quote.getClosePrice().signum() <= 0) {
            ValuationResultDto invalidPrice = unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), "CLOSING_PRICE_INVALID");
            return adrBaseMeta != null ? invalidPrice.withFxConversion(adrBaseMeta) : invalidPrice;
        }
        if (effectiveAdr.adr() && !effectiveAdr.verified()) {
            return unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), "ADR_RATIO_UNVERIFIED")
                    .withFxConversion(adrBaseMeta);
        }
        if (period == null) {
            ValuationResultDto noPeriod = unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
            return adrBaseMeta != null ? noPeriod.withFxConversion(adrBaseMeta) : noPeriod;
        }

        String reportingCurrency = period.getReportingCurrency();
        FxResolution fx = resolveFxForDay(fxLookup, reportingCurrency, quoteCurrency, day);
        if (!fx.isUsable()) {
            String fxFail = fx.reason() != null ? fx.reason().name() : "REPORTING_CURRENCY_MISMATCH";
            FxConversionDto failedFx = new FxConversionDto(false, reportingCurrency, quoteCurrency, null, null, null,
                    effectiveAdr.adr(), effectiveAdr.adr() ? effectiveAdr.ratio() : null,
                    effectiveAdr.adr() ? effectiveAdr.verified() : null, effectiveAdr.underlyingSymbol());
            return unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), fxFail)
                    .withFxConversion(failedFx);
        }
        BigDecimal fxMult = fx.usdPerUnit();
        FxConversionDto fxDto = (fx.converted() || effectiveAdr.adr())
                ? new FxConversionDto(fx.converted(), reportingCurrency, quoteCurrency, fx.rateDate(),
                        fx.converted() ? fx.usdPerUnit() : null, fx.source(),
                        effectiveAdr.adr(), effectiveAdr.adr() ? effectiveAdr.ratio() : null,
                        effectiveAdr.adr() ? effectiveAdr.verified() : null, effectiveAdr.underlyingSymbol())
                : null;

        // 1. 計算交易日 D 的總市值（報告查詢重用單次計算的 ShareEvaluation；ADR 歷史日絕不回退晚於 D 的 Futu 股數觀察）
        StockShareObservation safePrimaryObs = filterObsForAdrDay(primaryObs, effectiveAdr, day, lastClosed);
        StockShareObservation safeFallbackObs = filterObsForAdrDay(fallbackObs, effectiveAdr, day, lastClosed);
        ShareEvaluation shareEval = precomputedShareEval != null ? precomputedShareEval
                : evaluateMarketCap(quote, period, stockSplits, status, safePrimaryObs, safeFallbackObs,
                        multiClass, verifiedOneToOne, day, cutoff, calculatedAt, lastClosed);
        BigDecimal marketCap = shareEval.marketCap();
        String sharesReason = shareEval.sharesReason();

        // 2. 計算交易日 D 對應的 EPS 分母（已核實 ADR 統一採公司整體口徑 NetIncome_USD / adrSharesAtDay，與 P/E 與參考價反算 100% 一致）
        DayEpsResolution epsRes = resolveAlignedDayEps(quoteCurrency, status, period, stockSplits, day,
                shareEval.sharesAtDay(), sharesReason, effectiveAdr, fxMult);
        BigDecimal epsAtDay = epsRes.epsAtDay();
        String peReason = epsRes.peReason();
        String peSource = effectiveAdr.adr() ? period.getNetIncomeCommonTtmSource() : period.getEpsTtmSource();
        LocalDate pePeriodStart = effectiveAdr.adr() ? period.getNetIncomeCommonTtmPeriodStart() : period.getEpsTtmPeriodStart();

        // 3. 組裝已對齊報價幣別之 EV 與五項倍數
        BigDecimal equityVal = MetricStatus.AVAILABLE.name().equals(period.getCommonEquityStatus()) && period.getCommonEquity() != null
                ? period.getCommonEquity().multiply(fxMult, MC) : null;
        BigDecimal revenueVal = MetricStatus.AVAILABLE.name().equals(period.getRevenueTtmStatus()) && period.getRevenueTtm() != null
                ? period.getRevenueTtm().multiply(fxMult, MC) : null;
        BigDecimal fcfVal = MetricStatus.AVAILABLE.name().equals(period.getFreeCashFlowTtmStatus()) && period.getFreeCashFlowTtm() != null
                ? period.getFreeCashFlowTtm().multiply(fxMult, MC) : null;
        BigDecimal ebitdaVal = MetricStatus.AVAILABLE.name().equals(period.getEbitdaTtmStatus()) && period.getEbitdaTtm() != null
                ? period.getEbitdaTtm().multiply(fxMult, MC) : null;
        BigDecimal evVal = MetricStatus.AVAILABLE.name().equals(period.getEvAdjustmentStatus())
                && period.getEvAdjustment() != null && marketCap != null
                ? marketCap.add(period.getEvAdjustment().multiply(fxMult, MC), MC) : null;

        MarketMultiples multiples = calculator.calculate(new FinancialReportValuationCalculator.ValuationInputs(
                day, quote.getClosePrice(), quoteCurrency, quoteCurrency,
                marketCap, epsAtDay, equityVal, revenueVal, fcfVal, evVal, ebitdaVal));

        BigDecimal pe = multiples.pe().value();
        String resolvedPeReason = pe != null ? null : peReason != null ? peReason : reasonOf(multiples.pe());

        BigDecimal pb = multiples.pb().value();
        String resolvedPbReason = resolveMarketCapMultipleReason(
                pb, reasonOf(multiples.pb()), "NON_POSITIVE_COMMON_EQUITY", period.getCommonEquityReason(), sharesReason);

        BigDecimal ps = multiples.ps().value();
        String resolvedPsReason = resolveMarketCapMultipleReason(
                ps, reasonOf(multiples.ps()), "NON_POSITIVE_REVENUE", period.getRevenueTtmReason(), sharesReason);

        BigDecimal pfcf = financialInstitution ? null : multiples.pfcf().value();
        String resolvedPfcfReason = financialInstitution ? "PFCF_NOT_APPLICABLE_TO_INDUSTRY"
                : resolveMarketCapMultipleReason(
                        pfcf, reasonOf(multiples.pfcf()), "NON_POSITIVE_FCF", period.getFreeCashFlowTtmReason(), sharesReason);

        BigDecimal evEbitda = financialInstitution ? null : multiples.evEbitda().value();
        String evCalcReason = reasonOf(multiples.evEbitda());
        String resolvedEvReason = financialInstitution ? "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY"
                : evEbitda != null ? null
                : "NON_POSITIVE_EBITDA".equals(evCalcReason) ? evCalcReason
                : period.getEvAdjustmentReason() != null && period.getEvAdjustmentReason().metricStatus() == MetricStatus.UNVERIFIED ? period.getEvAdjustmentReason().name()
                : period.getEbitdaTtmReason() != null && period.getEbitdaTtmReason().metricStatus() == MetricStatus.UNVERIFIED ? period.getEbitdaTtmReason().name()
                : marketCap == null && sharesReason != null ? sharesReason
                : period.getEvAdjustmentReason() != null ? period.getEvAdjustmentReason().name()
                : period.getEbitdaTtmReason() != null ? period.getEbitdaTtmReason().name() : evCalcReason;

        ValuationResultDto.Metrics dtoMetrics = new ValuationResultDto.Metrics(
                ValuationResultDto.financialValue(pe, resolvedPeReason, peSource, pePeriodStart, period.getPeriodEnd()),
                ValuationResultDto.financialValue(pb, resolvedPbReason, period.getCommonEquitySource(), null, period.getPeriodEnd()),
                ValuationResultDto.financialValue(ps, resolvedPsReason, period.getRevenueTtmSource(), period.getRevenueTtmPeriodStart(), period.getPeriodEnd()),
                ValuationResultDto.financialValue(pfcf, resolvedPfcfReason, period.getFreeCashFlowTtmSource(), period.getFreeCashFlowTtmPeriodStart(), period.getPeriodEnd()),
                ValuationResultDto.financialValue(evEbitda, resolvedEvReason, combineSources(period.getEvAdjustmentSource(), period.getEbitdaTtmSource()),
                        period.getEbitdaTtmPeriodStart(), period.getPeriodEnd())
        );

        boolean hasAnyAvailable = pe != null || pb != null || ps != null || pfcf != null || evEbitda != null;
        String overallSource = period.getSecCik() != null ? "FUTU_AND_SEC_FINANCIAL_FACTS" : "FINANCIAL_REPORT_AS_OBSERVED";
        String primaryMetric = selectPrimaryMetric(modelCtx, dtoMetrics);

        return new ValuationResultDto(
                figi,
                day,
                cutoff,
                overallSource,
                industryCode,
                modelCode,
                primaryMetric,
                hasAnyAvailable ? "AVAILABLE" : "INSUFFICIENT_DATA",
                hasAnyAvailable ? null : "VALUATION_INPUTS_INCOMPLETE",
                dtoMetrics,
                quote.getClosePrice(),
                quoteCurrency,
                calculatedAt,
                period.getAvailableAt(),
                shareEval.sharesObservedAt(),
                shareEval.marketCapSource(),
                period.getSecAvailableFromDate(),
                null,
                null,
                null,
                fxDto
        );
    }

    private String resolveMarketCapMultipleReason(BigDecimal multipleVal, String calcReason, String nonPositiveCode,
                                                  ValuationReason denomReason, String sharesReason) {
        if (multipleVal != null) return null;
        if (nonPositiveCode.equals(calcReason)) return calcReason;
        if (denomReason != null && isInvalidatingReason(denomReason)) return denomReason.name();
        if (sharesReason != null) return sharesReason;
        return denomReason != null ? denomReason.name() : calcReason;
    }

    private ShareEvaluation evaluateQuoteOnlyMarketCap(
            StockQuote quote, List<StockSplit> stockSplits, StockStatus status,
            StockShareObservation obs, boolean multiClass, boolean verifiedOneToOne,
            LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed) {
        if (quote == null || quote.getClosePrice() == null || quote.getClosePrice().signum() <= 0) {
            return new ShareEvaluation(null, null, null, null, "CLOSING_PRICE_MISSING", null);
        }
        String splitError = splitSyncError(status);
        BigDecimal price = quote.getClosePrice();
        if (day.equals(lastClosed)) {
            if (sameCloseSnapshot(obs, quote, day, cutoff, calculatedAt)) {
                BigDecimal mktCap = obs.effectiveMarketCap();
                return new ShareEvaluation(mktCap, mktCap.divide(price, MC), "FUTU_CLOSE_SNAPSHOT", obs.getObservedAt(), null, null);
            }
            if (obs != null) {
                return evaluateObservedMarketCap(price, obs, multiClass, stockSplits, splitError, day, false);
            }
        } else if ((!multiClass || verifiedOneToOne) && obs != null) {
            return evaluateObservedMarketCap(price, obs, multiClass, stockSplits, splitError, day, true);
        }
        String reason = multiClass && !verifiedOneToOne ? "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED" : "VALUATION_DATE_SHARES_MISSING";
        return new ShareEvaluation(null, null, null, null, reason, null);
    }

    private ShareEvaluation evaluateDayShares(
            StockQuote quote, StockFinancialPeriod period, List<StockSplit> stockSplits, StockStatus status,
            StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne,
            LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed) {
        if (quote != null && quote.getClosePrice() != null && quote.getClosePrice().signum() > 0) {
            return evaluateMarketCap(quote, period, stockSplits, status, primaryObs, fallbackObs,
                    multiClass, verifiedOneToOne, day, cutoff, calculatedAt, lastClosed);
        }
        return evaluateSharesWithoutQuote(period, stockSplits, status, primaryObs, fallbackObs,
                multiClass, verifiedOneToOne, day, lastClosed);
    }

    private ShareEvaluation evaluateMarketCap(
            StockQuote quote, StockFinancialPeriod period, List<StockSplit> stockSplits, StockStatus status,
            StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne,
            LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed) {
        String splitError = splitSyncError(status);
        BigDecimal price = quote.getClosePrice();
        if (day.equals(lastClosed)) {
            if (sameCloseSnapshot(primaryObs, quote, day, cutoff, calculatedAt)) {
                BigDecimal mktCap = primaryObs.effectiveMarketCap();
                boolean hasDirectCap = primaryObs.getOutstandingMarketCap() != null && primaryObs.getOutstandingMarketCap().signum() > 0;
                String snapCalc = hasDirectCap ? "富途同日收盤快照流通市值換算股數" : "富途同日收盤快照已發行市值換算股數（舊快照備援）";
                return new ShareEvaluation(mktCap, mktCap.divide(price, MC), "FUTU_CLOSE_SNAPSHOT", primaryObs.getObservedAt(), null, snapCalc);
            }
            StockShareObservation obs = primaryObs != null ? primaryObs : fallbackObs;
            if (obs != null) {
                return evaluateObservedMarketCap(price, obs, multiClass, stockSplits, splitError, day, false);
            }
            if (multiClass && !verifiedOneToOne) {
                return new ShareEvaluation(null, null, null, null, "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED", period.getSharesOutstandingCalculation());
            }
        } else if (multiClass && !verifiedOneToOne) {
            return new ShareEvaluation(null, null, null, null, "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED", period.getSharesOutstandingCalculation());
        }

        if (period.getSharesOutstanding() != null && period.getSharesBasisDate() != null) {
            if (splitError != null && !period.getSharesBasisDate().equals(day)) {
                return new ShareEvaluation(null, null, null, period.getSharesOutstandingAvailableAt(), splitError, period.getSharesOutstandingCalculation());
            }
            BigDecimal sharesAtDay = period.getSharesOutstanding()
                    .multiply(StockSplitCalculator.shareFactor(stockSplits, period.getSharesBasisDate(), day), MC);
            String src = period.getSecShareAccession() != null || (multiClass && day.equals(lastClosed))
                    ? "SEC_FILING_SHARES" : "OBSERVED_SHARES";
            return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, src,
                    period.getSharesOutstandingAvailableAt(), null, period.getSharesOutstandingCalculation());
        }
        if (fallbackObs != null) {
            return evaluateObservedMarketCap(price, fallbackObs, multiClass, stockSplits, splitError, day, true);
        }
        String fallbackReason = multiClass && day.equals(lastClosed) ? "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED"
                : !multiClass && period.getSharesOutstandingReason() != null
                ? period.getSharesOutstandingReason().name() : "VALUATION_DATE_SHARES_MISSING";
        return new ShareEvaluation(null, null, null, period.getSharesOutstandingAvailableAt(), fallbackReason, period.getSharesOutstandingCalculation());
    }

    private ShareEvaluation evaluateObservedMarketCap(BigDecimal price, StockShareObservation obs, boolean useMultiClassEquiv,
                                                      List<StockSplit> stockSplits, String splitError, LocalDate day, boolean includeBasisDate) {
        LocalDate basis = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        String calc = observedSharesCalculation(obs, useMultiClassEquiv, includeBasisDate ? basis : null);
        if (splitError != null && !basis.equals(day)) {
            return new ShareEvaluation(null, null, null, obs.getObservedAt(), splitError, calc);
        }
        BigDecimal sharesAtDay = observedBaseShares(obs, useMultiClassEquiv)
                .multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
        BigDecimal mktCap = price == null ? null : price.multiply(sharesAtDay, MC);
        return new ShareEvaluation(mktCap, sharesAtDay, "OBSERVED_SHARES", obs.getObservedAt(), null, calc);
    }

    private ShareEvaluation evaluateSharesWithoutQuote(
            StockFinancialPeriod period, List<StockSplit> stockSplits, StockStatus status,
            StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne, LocalDate day, LocalDate lastClosed) {
        String splitError = splitSyncError(status);
        StockShareObservation obs = primaryObs != null ? primaryObs : fallbackObs;
        if (day.equals(lastClosed) && obs != null) {
            return evaluateObservedMarketCap(null, obs, multiClass, stockSplits, splitError, day, false);
        }
        if (multiClass && !verifiedOneToOne) {
            return new ShareEvaluation(null, null, null, null, "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED", period.getSharesOutstandingCalculation());
        }
        if (period.getSharesOutstanding() != null && period.getSharesBasisDate() != null) {
            if (splitError != null && !period.getSharesBasisDate().equals(day)) {
                return new ShareEvaluation(null, null, null, period.getSharesOutstandingAvailableAt(), splitError, period.getSharesOutstandingCalculation());
            }
            BigDecimal sharesAtDay = period.getSharesOutstanding()
                    .multiply(StockSplitCalculator.shareFactor(stockSplits, period.getSharesBasisDate(), day), MC);
            String src = period.getSecShareAccession() != null || (multiClass && day.equals(lastClosed))
                    ? "SEC_FILING_SHARES" : "OBSERVED_SHARES";
            return new ShareEvaluation(null, sharesAtDay, src, period.getSharesOutstandingAvailableAt(), null, period.getSharesOutstandingCalculation());
        }
        if (fallbackObs != null) {
            return evaluateObservedMarketCap(null, fallbackObs, multiClass, stockSplits, splitError, day, true);
        }
        String fallbackReason = multiClass && day.equals(lastClosed) ? "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED"
                : !multiClass && period.getSharesOutstandingReason() != null
                ? period.getSharesOutstandingReason().name() : "VALUATION_DATE_SHARES_MISSING";
        return new ShareEvaluation(null, null, null, period.getSharesOutstandingAvailableAt(), fallbackReason, period.getSharesOutstandingCalculation());
    }

    private BigDecimal observedBaseShares(StockShareObservation obs, boolean multiClass) {
        if (multiClass && obs.effectiveMarketCap() != null && obs.getSnapshotPrice() != null && obs.getSnapshotPrice().signum() > 0) {
            return obs.effectiveMarketCap().divide(obs.getSnapshotPrice(), MC);
        }
        return BigDecimal.valueOf(obs.effectiveShares());
    }

    private String observedSharesCalculation(StockShareObservation obs, boolean multiClass, LocalDate basisDate) {
        boolean hasDirectOutstanding = obs.getOutstandingShares() != null && obs.getOutstandingShares() > 0;
        if (basisDate != null) {
            return hasDirectOutstanding ? "富途流通在外股數（基準日 " + basisDate + "）" : "富途已發行股數備援（基準日 " + basisDate + "）";
        }
        if (multiClass) {
            return obs.getOutstandingMarketCap() != null && obs.getOutstandingMarketCap().signum() > 0
                    ? "富途最新等值流通股數基準" : "富途最新等值股數基準（已發行市值備援）";
        }
        return hasDirectOutstanding ? "富途最新流通在外股數" : "富途最新已發行股數（舊快照備援）";
    }

    /** 將配對財季分母轉為前端 FinancialsPanel 所需的 14 項財務指標，並共用同一次 ShareEvaluation 保證股數與 BVPS 完全一致。 */
    private FinancialsDto toFinancialsDto(
            StockFinancialPeriod p, List<StockSplit> stockSplits, LocalDate day,
            StockStatus status, ShareEvaluation shareEval) {
        LocalDate end = p.getPeriodEnd();
        String splitError = splitSyncError(status);

        FinancialValue epsVal;
        if (p.getEpsTtm() != null && MetricStatus.AVAILABLE.name().equals(p.getEpsTtmStatus()) && p.getEpsShareBasisDate() != null) {
            if (splitError != null && !p.getEpsShareBasisDate().equals(day)) {
                epsVal = FinancialValue.unavailable(MetricStatus.INSUFFICIENT_DATA.name(), splitError,
                        p.getEpsTtmCalculation(), p.getEpsTtmPeriodStart(), end, p.getEpsTtmAvailableAt());
            } else {
                BigDecimal factor = StockSplitCalculator.shareFactor(stockSplits, p.getEpsShareBasisDate(), day);
                BigDecimal adjustedEps = factor.signum() > 0 ? p.getEpsTtm().divide(factor, MC) : p.getEpsTtm();
                epsVal = FinancialValue.available(adjustedEps, p.getEpsTtmCalculation(), p.getEpsTtmPeriodStart(), end, p.getEpsTtmAvailableAt());
            }
        } else {
            epsVal = toDtoValue(p.getEpsTtm(), p.getEpsTtmStatus(), p.getEpsTtmReason(),
                    p.getEpsTtmCalculation(), p.getEpsTtmPeriodStart(), end, p.getEpsTtmAvailableAt());
        }

        BigDecimal sharesAtDay = shareEval == null ? null : shareEval.sharesAtDay();
        Instant sharesAvailAt = shareEval != null && shareEval.sharesObservedAt() != null
                ? shareEval.sharesObservedAt() : p.getSharesOutstandingAvailableAt();
        String sharesCalc = shareEval != null && shareEval.sharesCalculation() != null
                ? shareEval.sharesCalculation() : p.getSharesOutstandingCalculation();
        String sharesReason = shareEval != null ? shareEval.sharesReason()
                : (p.getSharesOutstandingReason() == null ? null : p.getSharesOutstandingReason().name());
        String sharesStatus = sharesAtDay != null
                ? MetricStatus.AVAILABLE.name()
                : (sharesReason != null ? ValuationReason.statusNameFor(sharesReason) : p.getSharesOutstandingStatus());

        FinancialValue sharesDto = sharesAtDay != null
                ? FinancialValue.available(sharesAtDay, sharesCalc, null, end, sharesAvailAt)
                : FinancialValue.unavailable(sharesStatus, sharesReason, sharesCalc, null, end, sharesAvailAt);

        FinancialValue bvpsDto;
        if (sharesAtDay != null && sharesAtDay.signum() > 0 && p.getCommonEquity() != null && MetricStatus.AVAILABLE.name().equals(p.getCommonEquityStatus())) {
            BigDecimal bvps = p.getCommonEquity().divide(sharesAtDay, MC);
            bvpsDto = FinancialValue.available(bvps, "普通股權益 ÷ 當日拆股對齊流通股數", null, end,
                    laterInstant(p.getCommonEquityAvailableAt(), sharesAvailAt));
        } else {
            bvpsDto = toDtoValue(null, sharesAtDay == null ? sharesStatus : p.getCommonEquityStatus(),
                    sharesAtDay == null ? ValuationReason.fromCode(sharesReason) : p.getCommonEquityReason(),
                    p.getBookValuePerShareCalculation(), null, end, p.getBookValuePerShareAvailableAt());
        }

        return new FinancialsDto(
                end,
                p.getAvailableAt(),
                p.getReportingCurrency(),
                toDtoValue(p.getRevenueTtm(), p.getRevenueTtmStatus(), p.getRevenueTtmReason(), p.getRevenueTtmCalculation(), p.getRevenueTtmPeriodStart(), end, p.getRevenueTtmAvailableAt()),
                toDtoValue(p.getNetIncomeCommonTtm(), p.getNetIncomeCommonTtmStatus(), p.getNetIncomeCommonTtmReason(), p.getNetIncomeCommonTtmCalculation(), p.getNetIncomeCommonTtmPeriodStart(), end, p.getNetIncomeCommonTtmAvailableAt()),
                epsVal,
                toDtoValue(p.getOperatingCashFlowTtm(), p.getOperatingCashFlowTtmStatus(), p.getOperatingCashFlowTtmReason(), p.getOperatingCashFlowTtmCalculation(), p.getOperatingCashFlowTtmPeriodStart(), end, p.getOperatingCashFlowTtmAvailableAt()),
                toDtoValue(p.getCapitalExpenditureTtm(), p.getCapitalExpenditureTtmStatus(), p.getCapitalExpenditureTtmReason(), p.getCapitalExpenditureTtmCalculation(), p.getCapitalExpenditureTtmPeriodStart(), end, p.getCapitalExpenditureTtmAvailableAt()),
                toDtoValue(p.getFreeCashFlowTtm(), p.getFreeCashFlowTtmStatus(), p.getFreeCashFlowTtmReason(), p.getFreeCashFlowTtmCalculation(), p.getFreeCashFlowTtmPeriodStart(), end, p.getFreeCashFlowTtmAvailableAt()),
                toDtoValue(p.getEbitdaTtm(), p.getEbitdaTtmStatus(), p.getEbitdaTtmReason(), p.getEbitdaTtmCalculation(), p.getEbitdaTtmPeriodStart(), end, p.getEbitdaTtmAvailableAt()),
                toDtoValue(p.getCommonEquity(), p.getCommonEquityStatus(), p.getCommonEquityReason(), p.getCommonEquityCalculation(), null, end, p.getCommonEquityAvailableAt()),
                sharesDto,
                bvpsDto,
                toDtoValue(p.getTotalDebt(), p.getTotalDebtStatus(), p.getTotalDebtReason(), p.getTotalDebtCalculation(), null, end, p.getTotalDebtAvailableAt()),
                toDtoValue(p.getCashLikeAssets(), p.getCashLikeAssetsStatus(), p.getCashLikeAssetsReason(), p.getCashLikeAssetsCalculation(), null, end, p.getCashLikeAssetsAvailableAt()),
                toDtoValue(p.getPreferredEquity(), p.getPreferredEquityStatus(), p.getPreferredEquityReason(), p.getPreferredEquityCalculation(), null, end, p.getPreferredEquityAvailableAt()),
                toDtoValue(p.getNoncontrollingInterest(), p.getNoncontrollingInterestStatus(), p.getNoncontrollingInterestReason(), p.getNoncontrollingInterestCalculation(), null, end, p.getNoncontrollingInterestAvailableAt())
        );
    }

    private FinancialValue toDtoValue(BigDecimal value, String status, ValuationReason reason, String calc, LocalDate start, LocalDate end, Instant availAt) {
        if (value != null && MetricStatus.AVAILABLE.name().equals(status)) {
            return FinancialValue.available(value, calc, start, end, availAt);
        }
        String code = reason == null ? null : reason.name();
        String resolvedStatus = status != null ? status : ValuationReason.statusNameFor(code);
        return FinancialValue.unavailable(resolvedStatus, code, calc, start, end, availAt);
    }

    /** 建構單一股票自 2014 年起的全部標準化財季分母列。 */
    List<StockFinancialPeriod> buildPeriodsForStock(
            Stock stock, StockStatus status, List<FinancialReport> rawFutuReports,
            List<SecFinancialFact> rawSecFacts, List<StockSplit> stockSplits, StockShareObservation latestObs,
            boolean multiClass, boolean verifiedOneToOne, boolean financialInstitution,
            LocalDate latestShareBasisDate, Instant calculatedAt) {
        return buildPeriodsForStock(stock, status, rawFutuReports, rawSecFacts, stockSplits, latestObs,
                multiClass, verifiedOneToOne, financialInstitution, resolveAdrContext(stock), latestShareBasisDate, calculatedAt);
    }

    List<StockFinancialPeriod> buildPeriodsForStock(
            Stock stock, StockStatus status, List<FinancialReport> rawFutuReports,
            List<SecFinancialFact> rawSecFacts, List<StockSplit> stockSplits, StockShareObservation latestObs,
            boolean multiClass, boolean verifiedOneToOne, boolean financialInstitution, AdrContext adrCtx,
            LocalDate latestShareBasisDate, Instant calculatedAt) {
        String figi = stock.getFigi();
        LocalDate knownNyDay = calculatedAt.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        List<FinancialReport> eligibleFutu = rawFutuReports.stream()
                .filter(r -> r.getPeriodEnd() != null && !r.getPeriodEnd().isAfter(knownNyDay))
                .toList();
        List<ReportValues> extracted = calculator.extract(eligibleFutu);
        PeriodAlignment alignment = secCalculator.alignReportPeriods(extracted, rawSecFacts, calculatedAt);
        if (alignment.windows().isEmpty()) return List.of();

        Map<ReportObservationKey, Instant> futuObservedMap = new HashMap<>();
        List<ReportValues> rawAligned = alignment.reports();
        for (int i = 0; i < eligibleFutu.size() && i < rawAligned.size(); i++) {
            FinancialReport src = eligibleFutu.get(i);
            ReportValues norm = rawAligned.get(i);
            ReportObservationKey key = new ReportObservationKey(norm.statementType(), norm.periodEnd(), norm.periodType());
            Instant prev = futuObservedMap.get(key);
            if (prev == null || (src.getObservedAt() != null && src.getObservedAt().isAfter(prev))) {
                futuObservedMap.put(key, src.getObservedAt());
            }
        }
        List<ReportValues> alignedReports = normalizeReportStandardsWithSec(stock, rawAligned, alignment.facts());

        List<StockFinancialPeriod> builtRows = new ArrayList<>();
        for (PeriodWindow window : alignment.windows()) {
            LocalDate periodEnd = window.periodEnd();
            List<ReportValues> reportsUpToP = alignedReports.stream()
                    .filter(r -> !r.periodEnd().isAfter(periodEnd)).toList();
            String periodCik = resolveCik(stock, periodEnd);
            List<SecFinancialFact> periodCikFacts = periodCik == null ? List.of()
                    : alignment.facts().stream().filter(f -> periodCik.equals(f.getCik())).toList();
            List<SecFinancialFact> factsUpToP = periodCikFacts.stream()
                    .filter(f -> !f.getPeriodEnd().isAfter(periodEnd)).toList();

            ReportValues income = statementAtPeriod(reportsUpToP, 1, periodEnd);
            ReportValues balance = statementAtPeriod(reportsUpToP, 2, periodEnd);
            ReportValues cashFlow = statementAtPeriod(reportsUpToP, 3, periodEnd);

            boolean hasFutuAtEnd = income != null || balance != null || cashFlow != null;
            boolean hasSecAtEnd = factsUpToP.stream().anyMatch(f -> periodEnd.equals(f.getPeriodEnd()) && !"shares".equals(f.getUnit()));
            if (!hasFutuAtEnd && !hasSecAtEnd) continue;

            // 優先採用本期原始財報幣別與 SEC 核心金額單位，避免非美 ADR 被掛牌幣別 USD 覆蓋
            String reportingCurrency = firstNonBlank(
                    income != null ? income.currency() : null,
                    balance != null ? balance.currency() : null,
                    cashFlow != null ? cashFlow.currency() : null,
                    latestReportCurrency(reportsUpToP),
                    dominantSecMonetaryUnit(factsUpToP, periodEnd),
                    SecurityTypePolicy.isAdrType(stock.getStockType()) ? null : stock.getCurrency());

            Flows secFlows = factsUpToP.isEmpty() || reportingCurrency == null ? null
                    : secCalculator.calculateSelectedFacts(factsUpToP, periodCik, stock.getSic(), reportingCurrency,
                            periodEnd, calculatedAt, reportsUpToP);

            StockFinancialPeriod row = assemblePeriodRow(
                    stock, status, window, reportsUpToP, income, balance, cashFlow,
                    secFlows, periodCikFacts, rawSecFacts, futuObservedMap, stockSplits, latestObs,
                    multiClass, verifiedOneToOne, financialInstitution, adrCtx,
                    reportingCurrency, periodCik, latestShareBasisDate, calculatedAt);
            builtRows.add(row);
        }
        return builtRows;
    }

    private StockFinancialPeriod assemblePeriodRow(
            Stock stock, StockStatus status, PeriodWindow window, List<ReportValues> reportsUpToP,
            ReportValues income, ReportValues balance, ReportValues cashFlow,
            Flows secFlows, List<SecFinancialFact> periodCikFacts, List<SecFinancialFact> allStockSecFacts,
            Map<ReportObservationKey, Instant> futuObservedMap, List<StockSplit> stockSplits, StockShareObservation latestObs,
            boolean multiClass, boolean verifiedOneToOne, boolean financialInstitution, AdrContext adrCtx,
            String reportingCurrency, String periodCik, LocalDate latestShareBasisDate, Instant calculatedAt) {
        String figi = stock.getFigi();
        LocalDate periodEnd = window.periodEnd();
        StockFinancialPeriod row = new StockFinancialPeriod();
        row.setFigi(figi);
        row.setPeriodEnd(periodEnd);
        row.setPeriodStart(window.periodStart());
        row.setFiscalYear(window.fiscalYear());
        row.setFiscalQuarter(window.fiscalQuarter());
        row.setReportingCurrency(reportingCurrency);
        boolean secUsGaapAtEnd = hasDomesticUsGaapFilingAt(periodCikFacts, periodEnd);
        String normStandard = firstNonBlank(
                secUsGaapAtEnd ? "US_GAAP" : null,
                income != null ? FinancialReportValuationCalculator.normalizeAccountingStandard(income.accountingStandards()) : null,
                balance != null ? FinancialReportValuationCalculator.normalizeAccountingStandard(balance.accountingStandards()) : null,
                secFlows != null ? "US_GAAP" : null);
        row.setAccountingStandards(normStandard);
        row.setUpdatedAt(calculatedAt);

        Instant incomeObs = statementObservedAt(income, futuObservedMap);
        Instant balanceObs = statementObservedAt(balance, futuObservedMap);
        Instant cashFlowObs = statementObservedAt(cashFlow, futuObservedMap);
        Flow secRevAtEnd = secFlows == null ? null : samePeriodSecFlow(secFlows.revenue(), periodEnd);
        Flow secCfoAtEnd = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.OPERATING_CASH_FLOW), periodEnd);
        LocalDate secAnnualIncStart = secRevAtEnd != null && secRevAtEnd.periodStart() != null
                && ChronoUnit.DAYS.between(secRevAtEnd.periodStart(), periodEnd) >= 350 ? secRevAtEnd.periodStart() : null;
        LocalDate secAnnualCfStart = secCfoAtEnd != null && secCfoAtEnd.periodStart() != null
                && ChronoUnit.DAYS.between(secCfoAtEnd.periodStart(), periodEnd) >= 350 ? secCfoAtEnd.periodStart() : null;
        List<ReportValues> reportsWithSecMissing = secFlows == null || secFlows.missingQuarters().isEmpty() ? reportsUpToP
                : combineReportsWithMissingQuarters(reportsUpToP, secFlows.missingQuarters());
        LocalDate ttmStart = resolveFutuTtmStart(reportsWithSecMissing, periodCikFacts, figi, 1, periodEnd, window.periodStart(), secAnnualIncStart);
        LocalDate cfTtmStart = resolveFutuTtmStart(reportsWithSecMissing, periodCikFacts, figi, 3, periodEnd, window.periodStart(), secAnnualCfStart);

        ReportValues effectiveIncome = effectiveFlowStatement(income, secFlows, 1, periodEnd);
        ReportValues effectiveCashFlow = effectiveFlowStatement(cashFlow, secFlows, 3, periodEnd);
        Set<Flow> usedSecFlows = new ArrayListSet();

        // 1. TTM 營業收入
        applyResolvedFlow(resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.REVENUE_AS_REPORTED,
                secRevAtEnd, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 營業收入 TTM", "SEC 營業收入 TTM", usedSecFlows),
                row::setRevenueTtm, row::setRevenueTtmStatus, row::setRevenueTtmReason,
                row::setRevenueTtmSource, row::setRevenueTtmCalculation, row::setRevenueTtmPeriodStart, row::setRevenueTtmAvailableAt);

        // 2. TTM 普通股淨利
        Flow secNetIncome = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.NET_INCOME_COMMON), periodEnd);
        applyResolvedFlow(resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.NET_INCOME_COMMON,
                secNetIncome, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 普通股淨利 TTM", "SEC 普通股淨利 TTM", usedSecFlows),
                row::setNetIncomeCommonTtm, row::setNetIncomeCommonTtmStatus, row::setNetIncomeCommonTtmReason,
                row::setNetIncomeCommonTtmSource, row::setNetIncomeCommonTtmCalculation, row::setNetIncomeCommonTtmPeriodStart, row::setNetIncomeCommonTtmAvailableAt);

        // 3. TTM 稀釋 EPS 與拆股基準驗證
        populateEpsTtm(stock, row, reportsUpToP, effectiveIncome, secFlows, allStockSecFacts, stockSplits,
                multiClass, verifiedOneToOne, adrCtx, reportingCurrency, periodEnd, ttmStart, incomeObs, latestShareBasisDate, calculatedAt, usedSecFlows);

        // 4. TTM 營運現金流
        applyResolvedFlow(resolveTrailingFlow(reportsUpToP, effectiveCashFlow, FinancialConcept.OPERATING_CASH_FLOW,
                secCfoAtEnd, secFlows, reportingCurrency, cfTtmStart, cashFlowObs, "Futu 營運現金流 TTM", "SEC 營運現金流 TTM", usedSecFlows),
                row::setOperatingCashFlowTtm, row::setOperatingCashFlowTtmStatus, row::setOperatingCashFlowTtmReason,
                row::setOperatingCashFlowTtmSource, row::setOperatingCashFlowTtmCalculation, row::setOperatingCashFlowTtmPeriodStart, row::setOperatingCashFlowTtmAvailableAt);

        // 5. TTM 資本支出
        Flow secCapex = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.CAPITAL_EXPENDITURE), periodEnd);
        applyResolvedFlow(resolveTrailingFlow(reportsUpToP, effectiveCashFlow, FinancialConcept.CAPITAL_EXPENDITURE,
                secCapex, secFlows, reportingCurrency, cfTtmStart, cashFlowObs, "Futu 資本支出 TTM", "SEC 完整資本支出 TTM", usedSecFlows),
                row::setCapitalExpenditureTtm, row::setCapitalExpenditureTtmStatus, row::setCapitalExpenditureTtmReason,
                row::setCapitalExpenditureTtmSource, row::setCapitalExpenditureTtmCalculation, row::setCapitalExpenditureTtmPeriodStart, row::setCapitalExpenditureTtmAvailableAt);

        // 6. TTM 自由現金流
        applyResolvedFlow(resolveFreeCashFlow(reportsUpToP, effectiveCashFlow, secFlows, financialInstitution, reportingCurrency, periodEnd, cfTtmStart, cashFlowObs, usedSecFlows),
                row::setFreeCashFlowTtm, row::setFreeCashFlowTtmStatus, row::setFreeCashFlowTtmReason,
                row::setFreeCashFlowTtmSource, row::setFreeCashFlowTtmCalculation, row::setFreeCashFlowTtmPeriodStart, row::setFreeCashFlowTtmAvailableAt);

        // 7. TTM 營運 EBITDA
        applyResolvedFlow(resolveEbitda(reportsUpToP, effectiveIncome, secFlows, financialInstitution, reportingCurrency, periodEnd, ttmStart, incomeObs, cashFlowObs, usedSecFlows),
                row::setEbitdaTtm, row::setEbitdaTtmStatus, row::setEbitdaTtmReason,
                row::setEbitdaTtmSource, row::setEbitdaTtmCalculation, row::setEbitdaTtmPeriodStart, row::setEbitdaTtmAvailableAt);

        // 8 & 11-14 & EV Adjustment：資產負債表項目
        populateBalanceAndEv(row, balance, secFlows, financialInstitution, reportingCurrency, periodEnd, balanceObs, usedSecFlows);

        // 9 & 10：期末流通股數基準與每股帳面值（已核實 ADR 的 SEC 普通股數依 adrRatio 換算為 ADR 等值股數）
        populateSharesAndBookValue(row, periodCikFacts, latestObs, stockSplits, multiClass, verifiedOneToOne, adrCtx, status, periodEnd, calculatedAt);

        // 15. 財務品質深化擴充（使用獨立 qualitySecFlows，不讓晚到的 SBC 推遲既有 EPS／前瞻的公開時間門）
        populateFinancialQualityFields(row, reportsUpToP, effectiveIncome, balance, effectiveCashFlow,
                secFlows, reportingCurrency, periodEnd, ttmStart, cfTtmStart, incomeObs, balanceObs, cashFlowObs);

        // 彙整本列觀察時間與 SEC 披露日期
        Instant maxAvail = maxInstant(incomeObs, balanceObs, cashFlowObs);
        LocalDate maxSecAvail = null;
        for (Flow secFlow : usedSecFlows) {
            if (secFlow != null) {
                maxAvail = laterInstant(maxAvail, secFlow.observedAt());
                maxSecAvail = laterDate(maxSecAvail, secFlow.availableFrom());
            }
        }
        row.setAvailableAt(maxAvail);
        row.setSecAvailableFromDate(maxSecAvail);
        SecFinancialFact earliestPeriodicSec = selectEarliestPeriodicSecEvidence(periodCikFacts, periodEnd, calculatedAt);
        if (earliestPeriodicSec != null) {
            row.setSecPeriodicObservedAt(earliestPeriodicSec.getObservedAt());
            row.setSecPeriodicAvailableFromDate(earliestPeriodicSec.getAvailableFromDate());
            if (row.getSecCik() == null && periodCik != null) {
                row.setSecCik(periodCik);
            }
        }
        if (!usedSecFlows.isEmpty() && periodCik != null) {
            row.setSecCik(periodCik);
        }
        return row;
    }

    private SecFinancialFact selectEarliestPeriodicSecEvidence(
            List<SecFinancialFact> periodCikFacts, LocalDate periodEnd, Instant calculatedAt) {
        if (periodCikFacts == null || periodCikFacts.isEmpty() || periodEnd == null) {
            return null;
        }
        SecFinancialFact best = null;
        Instant bestReadyAt = null;
        for (SecFinancialFact f : periodCikFacts) {
            if (f == null || !"us-gaap".equals(f.getTaxonomy()) || "shares".equals(f.getUnit())) continue;
            String form = Objects.toString(f.getForm(), "");
            if (!PERIODIC_STATEMENT_FORMS.contains(form)) continue;
            if (("6-K".equals(form) || "6-K/A".equals(form))
                    && (f.getReportFiscalYear() == null || f.getReportFiscalPeriod() == null)) {
                continue;
            }
            if (f.getPeriodEnd() == null || Math.abs(ChronoUnit.DAYS.between(f.getPeriodEnd(), periodEnd)) > 7) continue;
            if (f.getFiled() == null || f.getPeriodEnd().isAfter(f.getFiled())) continue;
            if (f.getAvailableFromDate() == null || f.getObservedAt() == null || f.getObservedAt().isAfter(calculatedAt)) continue;

            Instant effectiveStart = f.getAvailableFromDate().atStartOfDay(MarketStatusService.NY_ZONE).toInstant();
            Instant readyAt = laterInstant(f.getObservedAt(), effectiveStart);
            if (best == null
                    || readyAt.isBefore(bestReadyAt)
                    || (readyAt.equals(bestReadyAt) && f.getAvailableFromDate().isBefore(best.getAvailableFromDate()))
                    || (readyAt.equals(bestReadyAt) && f.getAvailableFromDate().equals(best.getAvailableFromDate())
                            && f.getObservedAt().isBefore(best.getObservedAt()))) {
                best = f;
                bestReadyAt = readyAt;
            }
        }
        return best;
    }

    private record ResolvedFlow(BigDecimal value, String status, ValuationReason reason, String source,
                                String calculation, LocalDate periodStart, Instant availableAt) {}

    private ResolvedFlow resolveTrailingFlow(
            List<ReportValues> reportsUpToP, ReportValues latestFutu, FinancialConcept concept,
            Flow secTotal, Flows secFlows, String currency, LocalDate futuStart, Instant futuObs,
            String futuCalc, String secCalc, Set<Flow> usedSecFlows) {
        MetricResult<BigDecimal> futuMetric = latestFutu == null ? missingMetric("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE")
                : calculator.trailingTwelveMonths(reportsUpToP, latestFutu.figi(), latestFutu.statementType(), concept, latestFutu.periodEnd(), currency);
        List<Flow> mixed = new ArrayList<>();
        if (futuMetric.value() == null && latestFutu != null && secFlows != null && sameCurrency(currency, latestFutu.currency())) {
            List<ReportValues> combined = supplementFlowReports(reportsUpToP, latestFutu, secFlows, mixed, List.of(concept));
            futuMetric = calculator.trailingTwelveMonths(combined, latestFutu.figi(), latestFutu.statementType(), concept, latestFutu.periodEnd(), currency);
            if (futuMetric.value() == null) mixed.clear();
        }
        boolean useSec = preferSec(secTotal, futuMetric, latestFutu);
        if (useSec) {
            usedSecFlows.add(secTotal);
            if (secTotal.value() != null) {
                String calc = secTotal.note() != null ? "SEC " + secTotal.note() : secCalc;
                return new ResolvedFlow(secTotal.value(), MetricStatus.AVAILABLE.name(), null, "SEC",
                        calc, secTotal.periodStart(), secTotal.observedAt());
            }
            ValuationReason reason = ValuationReason.fromCode(secTotal.reason());
            return new ResolvedFlow(null, reason == null ? MetricStatus.INSUFFICIENT_DATA.name() : reason.statusName(),
                    reason, "SEC", secCalc, secTotal.periodStart(), secTotal.observedAt());
        }
        if (futuMetric.value() != null) {
            usedSecFlows.addAll(mixed);
            String src = mixed.isEmpty() ? "FUTU" : "FUTU_AND_SEC";
            String calc = mixed.isEmpty() ? futuCalc : futuCalc + "（SEC 補缺）";
            Instant avail = mixed.stream().map(Flow::observedAt).filter(Objects::nonNull)
                    .reduce(futuObs, this::laterInstant);
            return new ResolvedFlow(futuMetric.value(), MetricStatus.AVAILABLE.name(), null, src, calc, futuStart, avail);
        }
        String reasonStr = hasReportReason(secTotal, latestFutu) ? secTotal.reason() : reasonOf(futuMetric);
        ValuationReason reason = ValuationReason.fromCode(reasonStr);
        return new ResolvedFlow(null, reason == null ? MetricStatus.INSUFFICIENT_DATA.name() : reason.statusName(),
                reason, "FUTU", futuCalc, futuStart, futuObs);
    }

    private void populateEpsTtm(
            Stock stock, StockFinancialPeriod row, List<ReportValues> reportsUpToP, ReportValues income,
            Flows secFlows, List<SecFinancialFact> allStockSecFacts, List<StockSplit> stockSplits,
            boolean multiClass, boolean verifiedOneToOne, AdrContext adrCtx, String currency, LocalDate periodEnd, LocalDate futuStart, Instant incomeObs,
            LocalDate latestShareBasisDate, Instant calculatedAt, Set<Flow> usedSecFlows) {
        String figi = row.getFigi();
        boolean isAdr = adrCtx != null && adrCtx.adr();
        // ADR 的 Futu Diluted EPS 為每 ADR 口徑而 SEC 為每原股口徑，禁止將兩者混算於同一 TTM 內或交叉驗證
        boolean allowSecEps = (!multiClass || verifiedOneToOne) && !isAdr;
        Flow issuerEps = secFlows == null ? null : samePeriodSecFlow(secFlows.dilutedEpsTtm(), periodEnd);
        Flow secEps = allowSecEps ? issuerEps : null;

        List<ReportValues> epsSourceReports = reportsUpToP;
        ReportValues epsPeriod = income;
        MetricResult<BigDecimal> futuEps = income == null ? null
                : calculator.trailingTwelveMonths(reportsUpToP, figi, 1, FinancialConcept.DILUTED_EPS, income.periodEnd(), currency);
        LocalDate epsBasisFrom = income == null ? null : calculator.trailingBasisFromDate(reportsUpToP, figi, 1, income.periodEnd());
        List<Flow> mixedEps = new ArrayList<>();

        if ((futuEps == null || futuEps.value() == null) && allowSecEps && income != null
                && sameCurrency(currency, income.currency()) && secFlows != null) {
            List<ReportValues> combined = supplementFlowReports(reportsUpToP, income, secFlows, mixedEps, List.of(FinancialConcept.DILUTED_EPS));
            MetricResult<BigDecimal> candidate = calculator.trailingTwelveMonths(combined, figi, 1, FinancialConcept.DILUTED_EPS, income.periodEnd(), currency);
            if (candidate.value() != null) {
                futuEps = candidate;
                epsSourceReports = combined;
                epsBasisFrom = calculator.trailingBasisFromDate(combined, figi, 1, income.periodEnd());
            } else {
                mixedEps.clear();
            }
        }

        LocalDate resolvedBasisDate = null;
        boolean futuSplitUnconfirmed = false;
        if (futuEps != null && futuEps.value() != null && epsPeriod != null) {
            if (!hasSplitAfter(stockSplits, epsBasisFrom, latestShareBasisDate)) {
                resolvedBasisDate = latestShareBasisDate;
            } else if (allowSecEps && verifyFutuEpsAtBasis(stock, epsSourceReports, epsPeriod, allStockSecFacts, currency, stockSplits,
                    latestShareBasisDate, latestShareBasisDate, calculatedAt)) {
                resolvedBasisDate = latestShareBasisDate;
            } else if (allowSecEps && verifyFutuEpsAtBasis(stock, epsSourceReports, epsPeriod, allStockSecFacts, currency, stockSplits,
                    epsPeriod.periodEnd(), latestShareBasisDate, calculatedAt)) {
                resolvedBasisDate = epsPeriod.periodEnd();
            } else {
                futuEps = unverifiedMetric("EPS_SPLIT_BASIS_UNCONFIRMED");
                futuSplitUnconfirmed = true;
                mixedEps.clear();
            }
        }

        boolean hasVerifiedFutu = futuEps != null && futuEps.value() != null && resolvedBasisDate != null;
        boolean completeSecInstead = !mixedEps.isEmpty() && secEps != null && secEps.value() != null
                && epsPeriod != null && epsPeriod.periodEnd().equals(secEps.periodEnd());
        boolean useSec = allowSecEps && (completeSecInstead || preferSec(secEps, futuEps, epsPeriod));
        if (useSec) {
            LocalDate secBasisDate = resolveSecEpsBasisDate(stock, secEps, allStockSecFacts, currency, stockSplits, latestShareBasisDate, calculatedAt);
            if (secEps.value() != null && secBasisDate != null) {
                usedSecFlows.add(secEps);
                row.setEpsTtm(secEps.value());
                row.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
                row.setEpsTtmSource("SEC");
                row.setEpsTtmCalculation("SEC 稀釋 EPS TTM");
                row.setEpsTtmPeriodStart(secEps.periodStart());
                row.setEpsTtmAvailableAt(secEps.observedAt());
                row.setEpsTtmSecAvailableFromDate(secEps.availableFrom());
                row.setEpsShareBasisDate(secBasisDate);
                return;
            }
            if (!hasVerifiedFutu) {
                String secFail = secEps.value() != null ? "EPS_SPLIT_BASIS_UNCONFIRMED" : secEps.reason();
                ValuationReason reason = ValuationReason.fromCode(secFail);
                row.setEpsTtmStatus(reason == null ? MetricStatus.INSUFFICIENT_DATA.name() : reason.statusName());
                row.setEpsTtmReason(reason);
                row.setEpsTtmSource("SEC");
                row.setEpsTtmPeriodStart(secEps.periodStart());
                row.setEpsTtmAvailableAt(secEps.observedAt());
                row.setEpsTtmSecAvailableFromDate(secEps.availableFrom());
                return;
            }
        }

        if (hasVerifiedFutu) {
            usedSecFlows.addAll(mixedEps);
            String src = mixedEps.isEmpty() ? "FUTU" : "FUTU_AND_SEC";
            String calc = epsPeriod != null && epsPeriod.periodType() == 7 ? "Futu 直接申報年度稀釋 EPS" : "Futu 四季稀釋 EPS 之和";
            Instant avail = mixedEps.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(incomeObs, this::laterInstant);
            LocalDate epsSecAvailFrom = mixedEps.stream().map(Flow::availableFrom).filter(Objects::nonNull).reduce(null, this::laterDate);
            row.setEpsTtm(futuEps.value());
            row.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
            row.setEpsTtmSource(src);
            row.setEpsTtmCalculation(calc);
            row.setEpsTtmPeriodStart(futuStart);
            row.setEpsTtmAvailableAt(avail);
            row.setEpsTtmSecAvailableFromDate(epsSecAvailFrom);
            row.setEpsShareBasisDate(resolvedBasisDate);
            return;
        }

        String failCode;
        if (futuSplitUnconfirmed) {
            failCode = "EPS_SPLIT_BASIS_UNCONFIRMED";
        } else if (!allowSecEps && (futuEps == null || futuEps.value() == null) && issuerEps != null && issuerEps.periodEnd() != null) {
            failCode = "SEC_SHARE_CLASS_EPS_UNVERIFIED";
        } else if (futuEps != null) {
            failCode = reasonOf(futuEps);
        } else if (issuerEps != null && issuerEps.reason() != null) {
            failCode = issuerEps.reason();
        } else {
            failCode = "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE";
        }
        ValuationReason reason = ValuationReason.fromCode(failCode);
        row.setEpsTtmStatus(reason == null ? MetricStatus.INSUFFICIENT_DATA.name() : reason.statusName());
        row.setEpsTtmReason(reason);
        row.setEpsTtmSource("FUTU");
        row.setEpsTtmPeriodStart(futuStart);
        row.setEpsTtmAvailableAt(incomeObs);
    }

    private void applyResolvedFlow(
            ResolvedFlow flow,
            java.util.function.Consumer<BigDecimal> valSetter,
            java.util.function.Consumer<String> statusSetter,
            java.util.function.Consumer<ValuationReason> reasonSetter,
            java.util.function.Consumer<String> srcSetter,
            java.util.function.Consumer<String> calcSetter,
            java.util.function.Consumer<LocalDate> startSetter,
            java.util.function.Consumer<Instant> availSetter) {
        if (flow.value() != null) valSetter.accept(flow.value());
        statusSetter.accept(flow.status());
        if (flow.reason() != null) reasonSetter.accept(flow.reason());
        srcSetter.accept(flow.source());
        if (flow.calculation() != null) calcSetter.accept(flow.calculation());
        startSetter.accept(flow.periodStart());
        availSetter.accept(flow.availableAt());
    }

    private ResolvedFlow resolveFreeCashFlow(
            List<ReportValues> reportsUpToP, ReportValues cashFlow, Flows secFlows,
            boolean financialInstitution, String currency, LocalDate periodEnd, LocalDate cfStart, Instant cfObs, Set<Flow> usedSecFlows) {
        MetricResult<BigDecimal> futuFcf = cashFlow == null ? missingMetric("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE")
                : calculator.trailingTwelveMonths(reportsUpToP, cashFlow.figi(), 3, FinancialConcept.FREE_CASH_FLOW, cashFlow.periodEnd(), currency);
        boolean directFutuFcf = futuFcf.value() != null;
        List<Flow> mixedFcf = new ArrayList<>();
        if (futuFcf.value() == null && cashFlow != null && sameCurrency(currency, cashFlow.currency())) {
            List<ReportValues> combined = supplementFlowReports(reportsUpToP, cashFlow, secFlows, mixedFcf,
                    List.of(FinancialConcept.OPERATING_CASH_FLOW, FinancialConcept.CAPITAL_EXPENDITURE));
            futuFcf = calculator.trailingTwelveMonths(combined, cashFlow.figi(), 3, FinancialConcept.FREE_CASH_FLOW, cashFlow.periodEnd(), currency);
            if (futuFcf.value() == null) mixedFcf.clear();
        }
        Flow secFcf = secFlows == null ? null : samePeriodSecFlow(secFlows.freeCashFlow(), periodEnd);
        if (preferSec(secFcf, futuFcf, cashFlow)) {
            usedSecFlows.add(secFcf);
            if (secFcf.value() != null) {
                String calc = secFcf.note() != null ? "SEC " + secFcf.note() : "SEC CFO - CapEx";
                return new ResolvedFlow(secFcf.value(), MetricStatus.AVAILABLE.name(), null, "SEC", calc, secFcf.periodStart(), secFcf.observedAt());
            }
            ValuationReason reason = financialInstitution ? ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY : ValuationReason.fromCode(secFcf.reason());
            return new ResolvedFlow(null, reason.statusName(), reason, "SEC", null, secFcf.periodStart(), secFcf.observedAt());
        }
        if (futuFcf.value() != null) {
            usedSecFlows.addAll(mixedFcf);
            String src = mixedFcf.isEmpty() ? "FUTU" : "FUTU_AND_SEC";
            String calc = !mixedFcf.isEmpty() ? "Futu + SEC 營運現金流減資本支出 TTM"
                    : directFutuFcf ? "Futu 自由現金流 TTM" : "Futu 營運現金流減資本支出 TTM";
            Instant avail = mixedFcf.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(cfObs, this::laterInstant);
            return new ResolvedFlow(futuFcf.value(), MetricStatus.AVAILABLE.name(), null, src, calc, cfStart, avail);
        }
        ValuationReason reason = financialInstitution ? ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY
                : ValuationReason.fromCode(hasReportReason(secFcf, cashFlow) ? secFcf.reason() : reasonOf(futuFcf));
        return new ResolvedFlow(null, reason.statusName(), reason, "FUTU", null, cfStart, cfObs);
    }

    private ResolvedFlow resolveEbitda(
            List<ReportValues> reportsUpToP, ReportValues income, Flows secFlows,
            boolean financialInstitution, String currency, LocalDate periodEnd, LocalDate ttmStart,
            Instant incomeObs, Instant cfObs, Set<Flow> usedSecFlows) {
        if (financialInstitution) {
            return new ResolvedFlow(null, MetricStatus.NOT_APPLICABLE.name(), ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY,
                    "FUTU", "金融機構不適用營運 EBITDA", ttmStart, incomeObs);
        }
        MetricResult<BigDecimal> futuEbitda = income == null ? missingMetric("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE")
                : calculator.trailingEbitda(reportsUpToP, income);
        List<Flow> mixedEbitda = new ArrayList<>();
        if (futuEbitda.value() == null && income != null && secFlows != null && sameCurrency(currency, income.currency())) {
            List<ReportValues> combined = supplementFlowReports(reportsUpToP, income, secFlows, mixedEbitda,
                    List.of(FinancialConcept.OPERATING_PROFIT, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION));
            futuEbitda = calculator.trailingEbitda(combined, income);
            if (futuEbitda.value() == null) mixedEbitda.clear();
        }
        Flow secEbitda = secFlows == null ? null : samePeriodSecFlow(secFlows.ebitda(), periodEnd);
        if (preferSec(secEbitda, futuEbitda, income)) {
            usedSecFlows.add(secEbitda);
            if (secEbitda.value() != null) {
                String calc = secEbitda.note() != null ? "SEC " + secEbitda.note() : "SEC OperatingIncomeLoss + DD&A";
                return new ResolvedFlow(secEbitda.value(), MetricStatus.AVAILABLE.name(), null, "SEC", calc, secEbitda.periodStart(), secEbitda.observedAt());
            }
            ValuationReason reason = ValuationReason.fromCode(secEbitda.reason());
            return new ResolvedFlow(null, reason.statusName(), reason, "SEC", null, secEbitda.periodStart(), secEbitda.observedAt());
        }
        if (futuEbitda.value() != null) {
            usedSecFlows.addAll(mixedEbitda);
            boolean usedCashFlowDda = income != null && calculator.flowInput(income, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION).value() == null;
            String src = mixedEbitda.isEmpty() ? "FUTU" : "FUTU_AND_SEC";
            String calc = mixedEbitda.isEmpty()
                    ? (usedCashFlowDda ? "Futu Operating Profit + 現金流量表 DD&A" : "Futu Operating Profit + 損益表 DD&A")
                    : "Futu + SEC Operating Profit + DD&A";
            Instant baseObs = usedCashFlowDda ? laterInstant(incomeObs, cfObs) : incomeObs;
            Instant avail = mixedEbitda.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(baseObs, this::laterInstant);
            return new ResolvedFlow(futuEbitda.value(), MetricStatus.AVAILABLE.name(), null, src, calc, ttmStart, avail);
        }
        ValuationReason reason = ValuationReason.fromCode(hasReportReason(secEbitda, income) ? secEbitda.reason() : reasonOf(futuEbitda));
        return new ResolvedFlow(null, reason.statusName(), reason, "FUTU", null, ttmStart, incomeObs);
    }

    private void populateBalanceAndEv(
            StockFinancialPeriod row, ReportValues balance, Flows secFlows,
            boolean financialInstitution, String currency, LocalDate periodEnd, Instant balanceObs, Set<Flow> usedSecFlows) {
        Map<FinancialConcept, Flow> secBalUsed = new EnumMap<>(FinancialConcept.class);
        ReportValues currencyMatchedBalance = balance != null && sameCurrency(currency, balance.currency()) ? balance : null;
        ReportValues enrichedBalance = currencyMatchedBalance != null && secFlows != null
                ? supplementBalance(currencyMatchedBalance, secFlows, secBalUsed) : currencyMatchedBalance;

        // 8. 普通股權益（與面板優先股、權益修正共用同一份 enrichedBalance，避免普通股權益漏扣已補入之優先股）
        MetricResult<BigDecimal> futuEq = enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING") : calculator.commonEquity(enrichedBalance);
        Flow secEq = secFlows == null ? null : samePeriodSecFlow(secFlows.equity(), periodEnd);
        boolean useSecEq = preferSec(secEq, futuEq, enrichedBalance);
        if (useSecEq) {
            usedSecFlows.add(secEq);
            if (secEq.value() != null) {
                row.setCommonEquity(secEq.value());
                row.setCommonEquityStatus(MetricStatus.AVAILABLE.name());
                row.setCommonEquitySource("SEC");
                row.setCommonEquityCalculation(secEq.note() != null ? "SEC " + secEq.note() : "SEC StockholdersEquity");
                row.setCommonEquityAvailableAt(secEq.observedAt());
            } else {
                ValuationReason r = ValuationReason.fromCode(secEq.reason());
                row.setCommonEquityStatus(r.statusName());
                row.setCommonEquityReason(r);
                row.setCommonEquitySource("SEC");
                row.setCommonEquityAvailableAt(secEq.observedAt());
            }
        } else if (futuEq.value() != null) {
            List<Flow> eqFlows = collectConceptFlows(secBalUsed, List.of(
                    FinancialConcept.STOCKHOLDERS_EQUITY, FinancialConcept.TOTAL_EQUITY,
                    FinancialConcept.PREFERRED_STOCK_CAPITAL, FinancialConcept.NONCONTROLLING_INTEREST_EQUITY));
            usedSecFlows.addAll(eqFlows);
            row.setCommonEquity(futuEq.value());
            row.setCommonEquityStatus(MetricStatus.AVAILABLE.name());
            row.setCommonEquitySource(eqFlows.isEmpty() ? "FUTU" : "FUTU_AND_SEC");
            row.setCommonEquityCalculation(eqFlows.isEmpty() ? "Futu 期末普通股股東權益" : "Futu + SEC 期末普通股股東權益");
            row.setCommonEquityAvailableAt(eqFlows.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(balanceObs, this::laterInstant));
        } else {
            ValuationReason r = ValuationReason.fromCode(hasReportReason(secEq, enrichedBalance) ? secEq.reason() : reasonOf(futuEq));
            row.setCommonEquityStatus(r.statusName());
            row.setCommonEquityReason(r);
            row.setCommonEquitySource("FUTU");
            row.setCommonEquityAvailableAt(balanceObs);
        }

        // 12–14. 現金、優先股權益、非控制權益
        populateStandardBalanceComponent(FinancialConcept.CASH_AND_EQUIVALENTS,
                enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING") : enrichedBalance.value(FinancialConcept.CASH_AND_EQUIVALENTS),
                enrichedBalance, secFlows, secBalUsed, periodEnd, balanceObs, usedSecFlows,
                "SEC CashAndCashEquivalentsAtCarryingValue", "期末獨立現金及約當現金",
                row::setCashLikeAssets, row::setCashLikeAssetsStatus, row::setCashLikeAssetsReason,
                row::setCashLikeAssetsSource, row::setCashLikeAssetsCalculation, row::setCashLikeAssetsAvailableAt);
        populateStandardBalanceComponent(FinancialConcept.PREFERRED_STOCK_CAPITAL,
                enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING") : calculator.preferredEquity(enrichedBalance),
                enrichedBalance, secFlows, secBalUsed, periodEnd, balanceObs, usedSecFlows,
                "SEC 優先股帳面值", "期末優先股帳面值",
                row::setPreferredEquity, row::setPreferredEquityStatus, row::setPreferredEquityReason,
                row::setPreferredEquitySource, row::setPreferredEquityCalculation, row::setPreferredEquityAvailableAt);
        populateStandardBalanceComponent(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY,
                enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING") : calculator.noncontrollingInterest(enrichedBalance),
                enrichedBalance, secFlows, secBalUsed, periodEnd, balanceObs, usedSecFlows,
                "SEC 非控制權益帳面值", "期末非控制權益帳面值",
                row::setNoncontrollingInterest, row::setNoncontrollingInterestStatus, row::setNoncontrollingInterestReason,
                row::setNoncontrollingInterestSource, row::setNoncontrollingInterestCalculation, row::setNoncontrollingInterestAvailableAt);

        // 11. 有息債務與 EV 調整淨額（直接由同報期已解析之債務、優先股、非控制權益與現金組合，保證面板與 EV 100% 一致）
        if (financialInstitution) {
            row.setTotalDebtStatus(MetricStatus.NOT_APPLICABLE.name());
            row.setTotalDebtReason(ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY);
            row.setTotalDebtSource("FUTU");
            row.setTotalDebtCalculation("金融機構不適用一般營運企業 EV 債務口徑");
            row.setTotalDebtAvailableAt(balanceObs);
            row.setEvAdjustmentStatus(MetricStatus.NOT_APPLICABLE.name());
            row.setEvAdjustmentReason(ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY);
            row.setEvAdjustmentSource("FUTU");
            return;
        }

        MetricResult<BigDecimal> futuDebt = enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING") : calculator.totalDebt(enrichedBalance);
        Flow secDebt = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.CURRENT_DEBT_AND_LEASES), periodEnd);
        if (preferSec(secDebt, futuDebt, enrichedBalance)) {
            usedSecFlows.add(secDebt);
            setBalanceComponent(row::setTotalDebt, row::setTotalDebtStatus, row::setTotalDebtReason,
                    row::setTotalDebtSource, row::setTotalDebtCalculation, row::setTotalDebtAvailableAt,
                    secDebt.value(), secDebt.reason(), "SEC", "SEC 計息債務（排除營運租賃）", secDebt.observedAt());
        } else {
            List<Flow> debtFlows = collectConceptFlows(secBalUsed, List.of(
                    FinancialConcept.CURRENT_DEBT, FinancialConcept.CURRENT_OPERATING_LEASES, FinancialConcept.LONG_TERM_OPERATING_LEASES,
                    FinancialConcept.CURRENT_FINANCE_LEASES, FinancialConcept.LONG_TERM_FINANCE_LEASES, FinancialConcept.TOTAL_FINANCE_LEASES));
            if (futuDebt.value() != null) usedSecFlows.addAll(debtFlows);
            String std = enrichedBalance == null ? null : FinancialReportValuationCalculator.normalizeAccountingStandard(enrichedBalance.accountingStandards());
            String debtCalc = "IFRS".equals(std) ? "IFRS 計息債務（含全部租賃負債）" : "US_GAAP 計息債務（排除營運租賃／純借款）";
            setBalanceComponent(row::setTotalDebt, row::setTotalDebtStatus, row::setTotalDebtReason,
                    row::setTotalDebtSource, row::setTotalDebtCalculation, row::setTotalDebtAvailableAt,
                    futuDebt.value(), reasonOf(futuDebt), !debtFlows.isEmpty() && futuDebt.value() != null ? "FUTU_AND_SEC" : "FUTU", debtCalc,
                    debtFlows.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(balanceObs, this::laterInstant));
        }

        String financingScopeFail = checkFinancingScopeReason(enrichedBalance);
        String evFail = null;
        BigDecimal evAdjVal = null;
        String evAdjSource = combineSources(row.getTotalDebtSource(), row.getCashLikeAssetsSource());
        if (financingScopeFail != null) {
            evFail = financingScopeFail;
        } else if (MetricStatus.UNVERIFIED.name().equals(row.getPreferredEquityStatus()) && row.getPreferredEquityReason() != null) {
            evFail = row.getPreferredEquityReason().name();
        } else if (MetricStatus.UNVERIFIED.name().equals(row.getNoncontrollingInterestStatus()) && row.getNoncontrollingInterestReason() != null) {
            evFail = row.getNoncontrollingInterestReason().name();
        } else if (!MetricStatus.AVAILABLE.name().equals(row.getTotalDebtStatus()) || row.getTotalDebt() == null) {
            evFail = row.getTotalDebtReason() != null ? row.getTotalDebtReason().name() : "EV_COMPONENTS_UNRESOLVED";
        } else if (!MetricStatus.AVAILABLE.name().equals(row.getCashLikeAssetsStatus()) || row.getCashLikeAssets() == null) {
            evFail = row.getCashLikeAssetsReason() != null ? row.getCashLikeAssetsReason().name() : "EV_COMPONENTS_UNRESOLVED";
        } else {
            BigDecimal prefVal = MetricStatus.AVAILABLE.name().equals(row.getPreferredEquityStatus()) && row.getPreferredEquity() != null
                    ? row.getPreferredEquity() : BigDecimal.ZERO;
            BigDecimal nciVal = MetricStatus.AVAILABLE.name().equals(row.getNoncontrollingInterestStatus()) && row.getNoncontrollingInterest() != null
                    ? row.getNoncontrollingInterest() : BigDecimal.ZERO;
            if (prefVal.signum() != 0) evAdjSource = combineSources(evAdjSource, row.getPreferredEquitySource());
            if (nciVal.signum() != 0) evAdjSource = combineSources(evAdjSource, row.getNoncontrollingInterestSource());
            evAdjVal = row.getTotalDebt().add(prefVal, MC).add(nciVal, MC).subtract(row.getCashLikeAssets(), MC);
        }

        if (evAdjVal != null && row.getEbitdaTtm() != null) {
            String evStd = "SEC".equals(row.getTotalDebtSource()) ? "US_GAAP"
                    : enrichedBalance != null ? FinancialReportValuationCalculator.normalizeAccountingStandard(enrichedBalance.accountingStandards())
                    : row.getAccountingStandards();
            String ebitdaStd = "SEC".equals(row.getEbitdaTtmSource()) ? "US_GAAP" : row.getAccountingStandards();
            if (!Objects.equals(evStd, ebitdaStd)) {
                evAdjVal = null;
                evFail = "EV_ACCOUNTING_STANDARD_UNCONFIRMED";
            }
        }
        if (evAdjVal != null) {
            row.setEvAdjustment(evAdjVal);
            row.setEvAdjustmentStatus(MetricStatus.AVAILABLE.name());
            row.setEvAdjustmentSource(evAdjSource);
        } else {
            ValuationReason r = ValuationReason.fromCode(evFail != null ? evFail : "EV_COMPONENTS_UNRESOLVED");
            row.setEvAdjustmentStatus(r.statusName());
            row.setEvAdjustmentReason(r);
            row.setEvAdjustmentSource(evAdjSource != null ? evAdjSource : "FUTU");
        }
    }

    private void populateStandardBalanceComponent(
            FinancialConcept concept, MetricResult<BigDecimal> futuVal, ReportValues enrichedBalance,
            Flows secFlows, Map<FinancialConcept, Flow> secBalUsed, LocalDate periodEnd, Instant balanceObs,
            Set<Flow> usedSecFlows, String secCalc, String futuCalc,
            java.util.function.Consumer<BigDecimal> valSetter,
            java.util.function.Consumer<String> statusSetter,
            java.util.function.Consumer<ValuationReason> reasonSetter,
            java.util.function.Consumer<String> srcSetter,
            java.util.function.Consumer<String> calcSetter,
            java.util.function.Consumer<Instant> availSetter) {
        Flow secFlow = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(concept), periodEnd);
        if (preferSec(secFlow, futuVal, enrichedBalance)) {
            usedSecFlows.add(secFlow);
            setBalanceComponent(valSetter, statusSetter, reasonSetter, srcSetter, calcSetter, availSetter,
                    secFlow.value(), secFlow.reason(), "SEC", secCalc, secFlow.observedAt());
            return;
        }
        Flow sup = secBalUsed.get(concept);
        boolean usedSup = sup != null && futuVal.value() != null;
        if (usedSup) usedSecFlows.add(sup);
        setBalanceComponent(valSetter, statusSetter, reasonSetter, srcSetter, calcSetter, availSetter,
                futuVal.value(), reasonOf(futuVal), usedSup ? "FUTU_AND_SEC" : "FUTU", futuCalc,
                usedSup ? laterInstant(balanceObs, sup.observedAt()) : balanceObs);
    }

    private void setBalanceComponent(
            java.util.function.Consumer<BigDecimal> valSetter,
            java.util.function.Consumer<String> statusSetter,
            java.util.function.Consumer<ValuationReason> reasonSetter,
            java.util.function.Consumer<String> srcSetter,
            java.util.function.Consumer<String> calcSetter,
            java.util.function.Consumer<Instant> availSetter,
            BigDecimal val, String failReason, String src, String calc, Instant avail) {
        srcSetter.accept(src);
        calcSetter.accept(calc);
        availSetter.accept(avail);
        if (val != null) {
            valSetter.accept(val);
            statusSetter.accept(MetricStatus.AVAILABLE.name());
        } else {
            ValuationReason r = ValuationReason.fromCode(failReason != null ? failReason : "BALANCE_INPUT_MISSING");
            statusSetter.accept(r.statusName());
            reasonSetter.accept(r);
        }
    }

    private void populateSharesAndBookValue(
            StockFinancialPeriod row, List<SecFinancialFact> periodCikFacts, StockShareObservation latestObs,
            List<StockSplit> stockSplits, boolean multiClass, boolean verifiedOneToOne, AdrContext adrCtx,
            StockStatus status, LocalDate periodEnd, Instant calculatedAt) {
        AdrContext effectiveAdr = adrCtx != null ? adrCtx : AdrContext.nonAdr();
        if (effectiveAdr.adr() && !effectiveAdr.verified()) {
            row.setSharesOutstandingStatus(MetricStatus.UNVERIFIED.name());
            row.setSharesOutstandingReason(ValuationReason.ADR_RATIO_UNVERIFIED);
            row.setSharesOutstandingSource("ADR_UNVERIFIED");
            row.setSharesOutstandingCalculation("ADR 換股比例尚未核實，暫不換算流通股數");
            row.setBookValuePerShareStatus(MetricStatus.UNVERIFIED.name());
            row.setBookValuePerShareReason(ValuationReason.ADR_RATIO_UNVERIFIED);
            row.setBookValuePerShareSource("ADR_UNVERIFIED");
            return;
        }
        if (multiClass && !verifiedOneToOne) {
            row.setSharesOutstandingStatus(MetricStatus.UNVERIFIED.name());
            row.setSharesOutstandingReason(ValuationReason.SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED);
            row.setSharesOutstandingSource("SEC_FILING_SHARES");
            row.setSharesOutstandingCalculation("多股類非 1:1 經濟權結構，歷史申報股數不直接套用");
            row.setBookValuePerShareStatus(MetricStatus.UNVERIFIED.name());
            row.setBookValuePerShareReason(ValuationReason.SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED);
            row.setBookValuePerShareSource("SEC_FILING_SHARES");
            return;
        }

        SecFinancialFact secShare = selectFilingSharesAtPeriod(periodCikFacts, periodEnd, calculatedAt, effectiveAdr.adr());
        BigDecimal sharesAtPeriodEnd = null;
        if (secShare != null && secShare.getValue() != null && secShare.getValue().signum() > 0) {
            if (hasSplitBetween(stockSplits, secShare.getPeriodEnd(), secShare.getFiled())) {
                row.setSharesOutstandingStatus(MetricStatus.UNVERIFIED.name());
                row.setSharesOutstandingReason(ValuationReason.SEC_SHARE_SPLIT_BASIS_UNCONFIRMED);
                row.setSharesOutstandingSource("SEC_FILING_SHARES");
                row.setBookValuePerShareStatus(MetricStatus.UNVERIFIED.name());
                row.setBookValuePerShareReason(ValuationReason.SEC_SHARE_SPLIT_BASIS_UNCONFIRMED);
                return;
            }
            BigDecimal normalizedShares = effectiveAdr.adr() && effectiveAdr.ratio() != null && effectiveAdr.ratio().signum() > 0
                    ? secShare.getValue().divide(effectiveAdr.ratio(), 4, RoundingMode.HALF_UP)
                    : secShare.getValue();
            String shareCalc = effectiveAdr.adr()
                    ? "SEC " + secShare.getTag() + " ÷ ADR 比例 " + effectiveAdr.ratio().stripTrailingZeros().toPlainString()
                            + "（基準日 " + secShare.getPeriodEnd() + "）"
                    : "SEC " + secShare.getTag() + "（基準日 " + secShare.getPeriodEnd() + "）";
            row.setSharesOutstanding(normalizedShares);
            row.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
            row.setSharesOutstandingSource("SEC_FILING_SHARES");
            row.setSharesOutstandingCalculation(shareCalc);
            row.setSharesOutstandingAvailableAt(secShare.getObservedAt());
            row.setSharesSecAvailableFromDate(secShare.getAvailableFromDate());
            row.setSharesBasisDate(secShare.getPeriodEnd());
            row.setSecShareAccession(secShare.getAccession());
            if (splitSyncError(status) == null || secShare.getPeriodEnd().equals(periodEnd)) {
                sharesAtPeriodEnd = normalizedShares
                        .multiply(StockSplitCalculator.shareFactor(stockSplits, secShare.getPeriodEnd(), periodEnd), MC);
            }
        } else if (!effectiveAdr.adr() && latestObs != null && splitSyncError(status) == null) {
            LocalDate obsDate = latestObs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            BigDecimal baseShares = observedBaseShares(latestObs, multiClass);
            row.setSharesOutstanding(baseShares);
            row.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
            row.setSharesOutstandingSource("OBSERVED_SHARES");
            row.setSharesOutstandingCalculation(observedSharesCalculation(latestObs, false, obsDate));
            row.setSharesOutstandingAvailableAt(latestObs.getObservedAt());
            row.setSharesBasisDate(obsDate);
            sharesAtPeriodEnd = baseShares.multiply(StockSplitCalculator.shareFactor(stockSplits, obsDate, periodEnd), MC);
        } else {
            ValuationReason reason = splitSyncError(status) != null
                    ? ValuationReason.SPLIT_SYNC_ERROR : ValuationReason.VALUATION_DATE_SHARES_MISSING;
            row.setSharesOutstandingStatus(reason.statusName());
            row.setSharesOutstandingReason(reason);
            row.setSharesOutstandingSource("OBSERVED_SHARES");
        }

        if (sharesAtPeriodEnd != null && sharesAtPeriodEnd.signum() > 0
                && row.getCommonEquity() != null && MetricStatus.AVAILABLE.name().equals(row.getCommonEquityStatus())) {
            row.setBookValuePerShare(row.getCommonEquity().divide(sharesAtPeriodEnd, MC));
            row.setBookValuePerShareStatus(MetricStatus.AVAILABLE.name());
            row.setBookValuePerShareSource(row.getCommonEquitySource());
            row.setBookValuePerShareCalculation("期末普通股權益 ÷ 期末拆股對齊流通股數");
            row.setBookValuePerShareAvailableAt(laterInstant(row.getCommonEquityAvailableAt(), row.getSharesOutstandingAvailableAt()));
        } else {
            ValuationReason bvReason = row.getSharesOutstandingReason() != null
                    ? row.getSharesOutstandingReason()
                    : row.getCommonEquityReason() != null ? row.getCommonEquityReason() : ValuationReason.COMMON_EQUITY_MISSING;
            row.setBookValuePerShareStatus(bvReason.statusName());
            row.setBookValuePerShareReason(bvReason);
            row.setBookValuePerShareSource(row.getCommonEquitySource());
            row.setBookValuePerShareAvailableAt(row.getCommonEquityAvailableAt());
        }
    }

    private SecFinancialFact selectFilingSharesAtPeriod(List<SecFinancialFact> facts, LocalDate periodEnd, Instant calculatedAt, boolean adr) {
        LocalDate ceiling = periodEnd.plusDays(45);
        List<SecFinancialFact> counts = facts.stream().filter(fact -> "shares".equals(fact.getUnit())
                && fact.getPeriodStart() == null && fact.getPeriodEnd() != null && !fact.getPeriodEnd().isAfter(ceiling)
                && fact.getFiled() != null && !fact.getPeriodEnd().isAfter(fact.getFiled())
                && fact.getObservedAt() != null && !fact.getObservedAt().isAfter(calculatedAt)).toList();
        List<SecFinancialFact> balance = counts.stream()
                .filter(fact -> "us-gaap".equals(fact.getTaxonomy()) && "CommonStockSharesOutstanding".equals(fact.getTag())
                        && !fact.getPeriodEnd().isAfter(periodEnd)).toList();
        List<SecFinancialFact> covers = counts.stream()
                .filter(fact -> "dei".equals(fact.getTaxonomy()) && "EntityCommonStockSharesOutstanding".equals(fact.getTag())
                        && PERIODIC_SHARE_FORMS.contains(Objects.toString(fact.getForm(), ""))).toList();
        SecFinancialFact bestBalance = latestShareFact(balance);
        SecFinancialFact bestCover = latestShareFact(covers);
        if (bestBalance != null && bestCover != null) {
            if (!bestBalance.getPeriodEnd().isBefore(periodEnd.minusDays(7))) {
                return bestBalance;
            }
            // 僅對 ADR／20-F 外企申報且封面與主表日期相近（<= 45 天）時防護封面少一位數異常（如 BABA 2026 封面 1.858B vs 主表 18.58B），保留一般美股 10-K/10-Q 原行為
            String coverForm = Objects.toString(bestCover.getForm(), "");
            boolean foreignCover = adr || "20-F".equals(coverForm) || "20-F/A".equals(coverForm) || "6-K".equals(coverForm);
            long dateGapDays = Math.abs(ChronoUnit.DAYS.between(bestCover.getPeriodEnd(), bestBalance.getPeriodEnd()));
            if (foreignCover && dateGapDays <= 45) {
                BigDecimal ratio = bestCover.getValue().divide(bestBalance.getValue(), MC);
                if (ratio.compareTo(new BigDecimal("0.5")) < 0 || ratio.compareTo(BigDecimal.valueOf(2)) > 0) {
                    return bestBalance;
                }
            }
            return bestCover.getPeriodEnd().isAfter(bestBalance.getPeriodEnd()) ? bestCover : bestBalance;
        }
        return bestBalance != null ? bestBalance : bestCover;
    }

    private SecFinancialFact latestShareFact(List<SecFinancialFact> counts) {
        SecFinancialFact latest = counts.stream()
                .max(Comparator.comparing(SecFinancialFact::getPeriodEnd).thenComparing(SecFinancialFact::getFiled)).orElse(null);
        if (latest == null || latest.getValue() == null || latest.getValue().signum() <= 0) return null;
        List<SecFinancialFact> versions = counts.stream()
                .filter(f -> latest.getPeriodEnd().equals(f.getPeriodEnd()) && latest.getFiled().equals(f.getFiled())).toList();
        if (versions.stream().anyMatch(f -> f.getValue().compareTo(latest.getValue()) != 0)) return null;
        return versions.stream().min(Comparator.comparing(SecFinancialFact::getAccession)).orElse(null);
    }

    private boolean verifyFutuEpsAtBasis(Stock stock, List<ReportValues> reports, ReportValues latest, List<SecFinancialFact> allStockSecFacts,
                                         String currency, List<StockSplit> stockSplits, LocalDate targetBasisDate,
                                         LocalDate horizonBasisDate, Instant calculatedAt) {
        List<ReportValues> periods = calculator.trailingPeriods(reports, latest.figi(), 1, latest.periodEnd());
        if (periods.isEmpty()) return false;
        boolean anyExposed = false;
        for (ReportValues period : periods) {
            // 受拆股影響與否必須依 (periodEnd, horizonBasisDate] 判定，不可依待測 targetBasisDate 跳過驗證
            if (!hasSplitAfter(stockSplits, period.periodEnd(), horizonBasisDate)) continue;
            anyExposed = true;
            BigDecimal periodEps = period.value(FinancialConcept.DILUTED_EPS).value();
            BigDecimal netIncome = period.value(FinancialConcept.NET_INCOME_COMMON).value();
            List<SecFinancialFact> periodCikFacts = cikFactsForPeriod(stock, allStockSecFacts, period.periodEnd());
            SecFinancialFactsCalculator.EpsSplitVerification verdict = secCalculator.verifyEpsSplitBasis(
                    periodEps, netIncome, period.periodEnd(), period.periodType() == 7,
                    periodCikFacts, currency, stockSplits, targetBasisDate, horizonBasisDate, calculatedAt);
            if (verdict == SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED) {
                continue;
            }
            if (verdict == SecFinancialFactsCalculator.EpsSplitVerification.REJECTED) {
                return false;
            }
            if (period.periodType() == 4 && isFourthQuarterVerifiedViaAnnual10K(stock, reports, period, allStockSecFacts,
                    currency, stockSplits, targetBasisDate, horizonBasisDate, calculatedAt)) {
                continue;
            }
            return false;
        }
        return anyExposed;
    }

    /** 美國公司第四季僅提交 10-K（無單季 10-Q XBRL 標籤）：核對同日年度 EPS 與 Q1..Q3 皆通過驗證、四季連續且排除對照拆股基準歧義。 */
    private boolean isFourthQuarterVerifiedViaAnnual10K(Stock stock, List<ReportValues> reports, ReportValues q4,
                                                        List<SecFinancialFact> allStockSecFacts, String currency, List<StockSplit> stockSplits,
                                                        LocalDate targetBasisDate, LocalDate horizonBasisDate, Instant calculatedAt) {
        if (q4.fiscalYear() == null || !sameCurrency(currency, q4.currency())) return false;
        List<ReportValues> annuals = reports.stream()
                .filter(r -> r.statementType() == 1 && r.periodType() == 7 && q4.periodEnd().equals(r.periodEnd())
                        && sameCurrency(currency, r.currency()))
                .toList();
        if (annuals.size() != 1) return false;
        ReportValues annual = annuals.getFirst();
        BigDecimal annualEps = annual.value(FinancialConcept.DILUTED_EPS).value();
        BigDecimal annualNi = annual.value(FinancialConcept.NET_INCOME_COMMON).value();
        List<SecFinancialFact> annualCikFacts = cikFactsForPeriod(stock, allStockSecFacts, annual.periodEnd());
        if (annualEps == null || secCalculator.verifyEpsSplitBasis(annualEps, annualNi, annual.periodEnd(), true,
                annualCikFacts, currency, stockSplits, targetBasisDate, horizonBasisDate, calculatedAt)
                != SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED) {
            return false;
        }
        List<ReportValues> fyQuarters = reports.stream()
                .filter(r -> r.statementType() == 1 && Objects.equals(q4.fiscalYear(), r.fiscalYear())
                        && r.periodType() >= 1 && r.periodType() <= 4 && sameCurrency(currency, r.currency()))
                .sorted(Comparator.comparing(ReportValues::periodEnd).reversed())
                .toList();
        if (fyQuarters.size() != 4 || !fyQuarters.getFirst().periodEnd().equals(q4.periodEnd())
                || fyQuarters.stream().map(ReportValues::periodType).distinct().count() != 4
                || !calculator.areContiguousQuarters(fyQuarters, q4.periodEnd())) {
            return false;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ReportValues q : fyQuarters) {
            BigDecimal qEps = q.value(FinancialConcept.DILUTED_EPS).value();
            if (qEps == null) return false;
            if (q.periodType() != 4 && hasSplitAfter(stockSplits, q.periodEnd(), horizonBasisDate)) {
                BigDecimal qNi = q.value(FinancialConcept.NET_INCOME_COMMON).value();
                List<SecFinancialFact> qCikFacts = cikFactsForPeriod(stock, allStockSecFacts, q.periodEnd());
                if (secCalculator.verifyEpsSplitBasis(qEps, qNi, q.periodEnd(), false,
                        qCikFacts, currency, stockSplits, targetBasisDate, horizonBasisDate, calculatedAt)
                        != SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED) {
                    return false;
                }
            }
            sum = sum.add(qEps, MC);
        }
        BigDecimal tol = annualEps.abs().multiply(new BigDecimal("0.04"), MC).max(new BigDecimal("0.03"));
        if (sum.subtract(annualEps, MC).abs().compareTo(tol) > 0) return false;

        BigDecimal q4Eps = q4.value(FinancialConcept.DILUTED_EPS).value();
        if (q4Eps == null) return false;
        if (q4Eps.signum() != 0) {
            for (LocalDate rival : secCalculator.distinctRivalBasisDates(stockSplits, q4.periodEnd(), targetBasisDate, horizonBasisDate)) {
                BigDecimal q4UnderRival = q4Eps.multiply(StockSplitCalculator.shareFactor(stockSplits, targetBasisDate, rival), MC);
                BigDecimal rivalSum = sum.subtract(q4Eps, MC).add(q4UnderRival, MC);
                if (rivalSum.subtract(annualEps, MC).abs().compareTo(tol) <= 0) return false;
            }
        }
        return true;
    }

    private LocalDate resolveSecEpsBasisDate(Stock stock, Flow secEps, List<SecFinancialFact> allStockSecFacts, String currency,
                                             List<StockSplit> stockSplits, LocalDate latestShareBasisDate, Instant calculatedAt) {
        if (secEps == null || secEps.value() == null || secEps.epsBasisFrom() == null || secEps.periodEnd() == null) return null;
        if (!hasSplitAfter(stockSplits, secEps.epsBasisFrom(), latestShareBasisDate)) {
            return latestShareBasisDate;
        }
        List<SecFinancialFact> secCikFacts = cikFactsForPeriod(stock, allStockSecFacts, secEps.periodEnd());
        boolean directAnnual = secEps.epsBasisFrom().equals(secEps.periodEnd());
        LocalDate latestSplitToNow = stockSplits.stream().map(StockSplit::getExecutionDate)
                .filter(exec -> exec.isAfter(secEps.epsBasisFrom()) && !exec.isAfter(latestShareBasisDate))
                .max(LocalDate::compareTo).orElse(null);
        if (latestSplitToNow != null && secEps.earliestFiled() != null && !secEps.earliestFiled().isBefore(latestSplitToNow)
                && StockSplitCalculator.shareFactor(stockSplits, secEps.earliestFiled(), latestShareBasisDate).compareTo(BigDecimal.ONE) == 0
                && !secCalculator.hasUnrestatedPostSplitComparative(secEps, allStockSecFacts, d -> resolveCik(stock, d),
                        currency, stockSplits, latestShareBasisDate, calculatedAt)) {
            return latestShareBasisDate;
        }
        if (directAnnual && secCalculator.verifyEpsSplitBasis(secEps.value(), null, secEps.periodEnd(), true,
                secCikFacts, currency, stockSplits, latestShareBasisDate, latestShareBasisDate, calculatedAt)
                == SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED) {
            return latestShareBasisDate;
        }
        // 全數組成季皆須於拆股前申報完成（檢查 latestFiled 而非 earliestFiled），禁止混用拆前與拆後申報季
        if (!hasSplitBetween(stockSplits, secEps.epsBasisFrom(), secEps.periodEnd())
                && secEps.latestFiled() != null && !hasSplitBetween(stockSplits, secEps.periodEnd(), secEps.latestFiled())) {
            return secEps.periodEnd();
        }
        if (directAnnual && secCalculator.verifyEpsSplitBasis(secEps.value(), null, secEps.periodEnd(), true,
                secCikFacts, currency, stockSplits, secEps.periodEnd(), latestShareBasisDate, calculatedAt)
                == SecFinancialFactsCalculator.EpsSplitVerification.VERIFIED) {
            return secEps.periodEnd();
        }
        return null;
    }

    private List<SecFinancialFact> cikFactsForPeriod(Stock stock, List<SecFinancialFact> allStockSecFacts, LocalDate periodEnd) {
        if (stock == null || allStockSecFacts == null || allStockSecFacts.isEmpty()) return List.of();
        String cik = resolveCik(stock, periodEnd);
        if (cik == null) return List.of();
        return allStockSecFacts.stream().filter(f -> cik.equals(f.getCik())).toList();
    }

    private boolean isPeriodPublishedAtCutoff(StockFinancialPeriod p, LocalDate queryDay, Instant cutoffAt) {
        return ForwardValuationCalculator.isPeriodReportedAtCutoff(p, queryDay, cutoffAt)
                || hasCutoffEligibleShares(p, queryDay, cutoffAt);
    }

    private boolean hasCutoffEligibleShares(StockFinancialPeriod p, LocalDate queryDay, Instant cutoffAt) {
        if (p == null || p.getPeriodEnd() == null || p.getPeriodEnd().isAfter(queryDay)
                || p.getSharesOutstanding() == null || p.getSharesBasisDate() == null
                || p.getSharesBasisDate().isAfter(queryDay)
                || p.getSharesOutstandingAvailableAt() == null
                || p.getSharesOutstandingAvailableAt().isAfter(cutoffAt)) {
            return false;
        }
        boolean usesSecShares = p.getSecShareAccession() != null
                || "SEC_FILING_SHARES".equals(p.getSharesOutstandingSource());
        if (!usesSecShares) {
            return true;
        }
        LocalDate shareSecFrom = p.getSharesSecAvailableFromDate() != null
                ? p.getSharesSecAvailableFromDate() : p.getSecAvailableFromDate();
        return shareSecFrom == null || !shareSecFrom.isAfter(queryDay);
    }

    private boolean isMultiClassVerifiedAtCutoff(String cik, StockShareObservation obs, LocalDate queryDay,
                                                 Instant cutoffAt, List<StockSplit> stockSplits) {
        if (obs == null || cik == null) return false;
        List<SecFinancialFact> rawFacts = secFacts.findLatestIssuerShareFacts(List.of(cik), cutoffAt);
        if (rawFacts == null || rawFacts.isEmpty()) return false;
        List<SecFinancialFact> cutoffEligible = rawFacts.stream()
                .filter(f -> f.getPeriodEnd() != null && !f.getPeriodEnd().isAfter(queryDay)
                        && f.getFiled() != null && !f.getFiled().isAfter(queryDay)
                        && (f.getAvailableFromDate() == null || !f.getAvailableFromDate().isAfter(queryDay))
                        && f.getObservedAt() != null && !f.getObservedAt().isAfter(cutoffAt))
                .toList();
        return isMultiClassVerifiedFromRawFacts(obs, cutoffEligible, stockSplits, cutoffAt);
    }

    private boolean isMultiClassVerifiedOneToOne(String cik, StockShareObservation obs,
                                                 List<StockSplit> stockSplits, Instant calculatedAt) {
        if (obs == null || cik == null) return false;
        List<SecFinancialFact> shareFacts = secFacts.findLatestIssuerShareFacts(List.of(cik), calculatedAt);
        return isMultiClassVerifiedFromRawFacts(obs, shareFacts, stockSplits, calculatedAt);
    }

    private boolean isMultiClassVerifiedFromRawFacts(StockShareObservation obs, List<SecFinancialFact> rawCikFacts,
                                                     List<StockSplit> stockSplits, Instant calculatedAt) {
        if (obs == null || rawCikFacts == null || rawCikFacts.isEmpty()) return false;
        List<SecFinancialFact> shareCounts = rawCikFacts.stream()
                .filter(f -> "shares".equals(f.getUnit()) && f.getPeriodStart() == null && f.getPeriodEnd() != null
                        && f.getFiled() != null && !f.getPeriodEnd().isAfter(f.getFiled())
                        && f.getObservedAt() != null && !f.getObservedAt().isAfter(calculatedAt)
                        && ("CommonStockSharesOutstanding".equals(f.getTag()) || "EntityCommonStockSharesOutstanding".equals(f.getTag())))
                .toList();
        SecFinancialFact latestSec = latestShareFact(shareCounts);
        if (latestSec == null || latestSec.getValue() == null || latestSec.getValue().signum() <= 0) return false;

        BigDecimal equivShares = observedBaseShares(obs, true);
        LocalDate obsDate = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        BigDecimal adjSecShares = latestSec.getValue()
                .multiply(StockSplitCalculator.shareFactor(stockSplits, latestSec.getPeriodEnd(), obsDate), MC);
        if (adjSecShares.signum() <= 0) return false;
        BigDecimal relDiff = equivShares.subtract(adjSecShares, MC).abs().divide(adjSecShares, MC);
        return relDiff.compareTo(TWO_PERCENT) <= 0;
    }

    private List<SecFinancialFact> collectStockSecFacts(Stock stock, Map<String, List<SecFinancialFact>> factsByCik) {
        if (stock.getCik() == null || stock.getCik().isBlank()) return List.of();
        List<SecFinancialFact> combined = new ArrayList<>(factsByCik.getOrDefault(stock.getCik(), List.of()));
        for (String pred : issuerHistory.predecessors(stock.getCik())) {
            combined.addAll(factsByCik.getOrDefault(pred, List.of()));
        }
        return combined;
    }

    private String resolveCik(Stock stock, LocalDate day) {
        if (stock.getCik() == null || stock.getCik().isBlank() || !SecurityTypePolicy.supportsValuation(stock.getStockType())) {
            return null;
        }
        return issuerHistory.cikAt(stock.getCik(), day);
    }

    private IndustryModelContext resolveIndustryModel(Stock stock, String knownIndustryCode, Instant cutoff, Instant configAsOf) {
        String code = knownIndustryCode;
        String plate = null;
        Optional<StockIndustryHistory> hist = industries.findAt(stock.getFigi(), cutoff);
        if (hist.isPresent() && hist.get().getIndustry() != null) {
            if (code == null) code = hist.get().getIndustry().getCode();
            plate = hist.get().getIndustry().getSourcePlateCode();
        }
        IndustryValuationConfig cfg = code == null ? null
                : industryConfigs.findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(code, configAsOf).orElse(null);
        return buildIndustryModelContext(stock, code, plate, cfg);
    }

    private Map<String, IndustryModelContext> resolveBatchIndustryModels(List<Stock> batchStocks, Instant cutoff, Instant configAsOf) {
        List<String> figis = batchStocks.stream().map(Stock::getFigi).toList();
        Map<String, String> codes = new HashMap<>();
        Map<String, String> plates = new HashMap<>();
        for (StockIndustryHistory h : industries.findByFigisAt(figis, cutoff)) {
            if (h.getIndustry() != null) {
                codes.put(h.getFigi(), h.getIndustry().getCode());
                if (h.getIndustry().getSourcePlateCode() != null) plates.put(h.getFigi(), h.getIndustry().getSourcePlateCode());
            }
        }
        Map<String, IndustryValuationConfig> cfgs = new HashMap<>();
        if (!codes.isEmpty()) {
            for (IndustryValuationConfig cfg : industryConfigs.findByIndustryCodesAt(Set.copyOf(codes.values()), configAsOf)) {
                cfgs.put(cfg.getIndustryCode(), cfg);
            }
        }
        Map<String, IndustryModelContext> result = new HashMap<>();
        for (Stock s : batchStocks) {
            String figi = s.getFigi();
            String code = codes.get(figi);
            result.put(figi, buildIndustryModelContext(s, code, plates.get(figi), code == null ? null : cfgs.get(code)));
        }
        return result;
    }

    private IndustryModelContext buildIndustryModelContext(Stock stock, String code, String plate, IndustryValuationConfig cfg) {
        String resolvedModel = IndustryValuationProposalService.resolveModelCode(
                cfg == null ? null : cfg.getModelCode(), plate, stock.getSic());
        return new IndustryModelContext(code, resolvedModel,
                cfg == null ? null : cfg.getPrimaryMetric(),
                cfg == null ? null : cfg.getAssumedMultipleLow(),
                cfg == null ? null : cfg.getAssumedMultipleHigh(),
                IndustryValuationProposalService.isFinancialInstitutionModel(resolvedModel));
    }

    /** 依單一主指標選擇規則挑出當期主指標（銀行／保險絕不退回 EV_EBITDA 或 PFCF）。 */
    public static String selectPrimaryMetric(String modelCode, String configuredPrimaryMetric, ValuationResultDto.Metrics metrics) {
        boolean financial = IndustryValuationProposalService.isFinancialInstitutionModel(modelCode);
        if (configuredPrimaryMetric != null && !configuredPrimaryMetric.isBlank()) {
            String upper = configuredPrimaryMetric.trim().toUpperCase();
            if (!financial || "PE".equals(upper) || "PB".equals(upper)) {
                return upper;
            }
        }
        if (financial) {
            return "PB";
        }
        if (metrics != null && metrics.pe() != null && metrics.pe().value() != null && metrics.pe().value().signum() > 0) {
            return "PE";
        }
        if (metrics != null && metrics.evEbitda() != null && metrics.evEbitda().value() != null && metrics.evEbitda().value().signum() > 0) {
            return "EV_EBITDA";
        }
        if (metrics != null && metrics.ps() != null && metrics.ps().value() != null && metrics.ps().value().signum() > 0) {
            return "PS";
        }
        return "PE";
    }

    private String selectPrimaryMetric(IndustryModelContext ctx, ValuationResultDto.Metrics metrics) {
        return selectPrimaryMetric(ctx == null ? null : ctx.modelCode(),
                ctx == null ? null : ctx.configuredPrimaryMetric(), metrics);
    }

    private String selectPrimaryMetricForDay(IndustryModelContext ctx, ValuationResultDto.Metrics metrics, StockFinancialPeriod period) {
        String modelCode = ctx == null ? null : ctx.modelCode();
        String configured = ctx == null ? null : ctx.configuredPrimaryMetric();
        boolean financial = IndustryValuationProposalService.isFinancialInstitutionModel(modelCode);
        if (configured != null && !configured.isBlank()) {
            String upper = configured.trim().toUpperCase();
            if (!financial || "PE".equals(upper) || "PB".equals(upper)) return upper;
        }
        if (financial) return "PB";
        boolean hasAnyDayMultiple = metrics != null && ((metrics.pe() != null && metrics.pe().value() != null)
                || (metrics.evEbitda() != null && metrics.evEbitda().value() != null)
                || (metrics.ps() != null && metrics.ps().value() != null));
        if (hasAnyDayMultiple || period == null) {
            return selectPrimaryMetric(modelCode, null, metrics);
        }
        if (MetricStatus.AVAILABLE.name().equals(period.getEpsTtmStatus()) && period.getEpsTtm() != null && period.getEpsTtm().signum() > 0) {
            return "PE";
        }
        if (MetricStatus.AVAILABLE.name().equals(period.getEbitdaTtmStatus()) && period.getEbitdaTtm() != null
                && period.getEbitdaTtm().signum() > 0 && MetricStatus.AVAILABLE.name().equals(period.getEvAdjustmentStatus())) {
            return "EV_EBITDA";
        }
        if (MetricStatus.AVAILABLE.name().equals(period.getRevenueTtmStatus()) && period.getRevenueTtm() != null && period.getRevenueTtm().signum() > 0) {
            return "PS";
        }
        return "PE";
    }

    /** 供估值計算、每日機會雷達、持倉體檢與建議買入排行榜共用之交易日對齊 TTM EPS 分母與原因碼解析（含已核實 ADR、幣別與拆股基準驗證）。 */
    public record DayEpsResolution(BigDecimal epsAtDay, String peReason) {}

    public static DayEpsResolution resolveDayEps(
            String quoteCurrency, StockStatus status, StockFinancialPeriod period,
            List<StockSplit> stockSplits, LocalDate day) {
        return resolveDayEps(quoteCurrency, status, period, stockSplits, day, null, false, false, null, null);
    }

    public static DayEpsResolution resolveDayEps(
            String quoteCurrency, StockStatus status, StockFinancialPeriod period,
            List<StockSplit> stockSplits, LocalDate day, BigDecimal fxRateToQuote) {
        return resolveDayEps(quoteCurrency, status, period, stockSplits, day, fxRateToQuote, false, false, null, null);
    }

    public static DayEpsResolution resolveDayEps(
            String quoteCurrency, StockStatus status, StockFinancialPeriod period,
            List<StockSplit> stockSplits, LocalDate day, BigDecimal fxRateToQuote,
            boolean isAdr, boolean verifiedAdr, BigDecimal marketCapAtDay, BigDecimal closePriceAtDay) {
        if (isAdr && !verifiedAdr) {
            return new DayEpsResolution(null, "ADR_RATIO_UNVERIFIED");
        }
        if (period == null) {
            return new DayEpsResolution(null, "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        }
        boolean sameCcy = sameCurrency(quoteCurrency, period.getReportingCurrency());
        boolean hasFx = fxRateToQuote != null && fxRateToQuote.signum() > 0;
        if (!sameCcy && !hasFx) {
            return new DayEpsResolution(null, "REPORTING_CURRENCY_MISMATCH");
        }
        if (isAdr) {
            if (!MetricStatus.AVAILABLE.name().equals(period.getNetIncomeCommonTtmStatus())
                    || period.getNetIncomeCommonTtm() == null) {
                String niReason = period.getNetIncomeCommonTtmReason() != null
                        ? period.getNetIncomeCommonTtmReason().name() : "NET_INCOME_COMMON_MISSING";
                return new DayEpsResolution(null, niReason);
            }
            if (marketCapAtDay == null || marketCapAtDay.signum() <= 0
                    || closePriceAtDay == null || closePriceAtDay.signum() <= 0) {
                return new DayEpsResolution(null, "VALUATION_DATE_SHARES_MISSING");
            }
            BigDecimal fxMult = sameCcy ? BigDecimal.ONE : fxRateToQuote;
            BigDecimal sharesAtDay = marketCapAtDay.divide(closePriceAtDay, MC);
            BigDecimal netIncomeQuote = period.getNetIncomeCommonTtm().multiply(fxMult, MC);
            return new DayEpsResolution(netIncomeQuote.divide(sharesAtDay, MC), null);
        }
        String splitError = splitSyncError(status);
        BigDecimal epsAtDay = null;
        String peReason = period.getEpsTtmReason() == null ? null : period.getEpsTtmReason().name();
        if (period.getEpsTtm() != null && MetricStatus.AVAILABLE.name().equals(period.getEpsTtmStatus())) {
            if (period.getEpsShareBasisDate() == null) {
                peReason = "EPS_SOURCE_PERIOD_MISSING";
            } else if (splitError != null && !period.getEpsShareBasisDate().equals(day)) {
                peReason = splitError;
            } else {
                BigDecimal factor = StockSplitCalculator.shareFactor(stockSplits, period.getEpsShareBasisDate(), day);
                if (factor.signum() > 0) {
                    BigDecimal rawEps = period.getEpsTtm().divide(factor, MC);
                    epsAtDay = sameCcy ? rawEps : rawEps.multiply(fxRateToQuote, MC);
                    peReason = null;
                } else {
                    peReason = "EPS_SPLIT_BASIS_UNCONFIRMED";
                }
            }
        } else if (peReason == null) {
            peReason = "DILUTED_EPS_TTM_MISSING";
        }
        return new DayEpsResolution(epsAtDay, peReason);
    }

    private DayDenominators resolveDayDenominators(
            Stock stock, StockStatus status, StockFinancialPeriod period,
            List<StockSplit> stockSplits, LocalDate day, ShareEvaluation dayShareEval,
            AdrContext adrCtx, FxRateLookup fxLookup) {
        AdrContext effectiveAdr = adrCtx != null ? adrCtx : AdrContext.nonAdr();
        if (effectiveAdr.adr() && !effectiveAdr.verified()) {
            return new DayDenominators(null, null, "ADR_RATIO_UNVERIFIED", null, "ADR_RATIO_UNVERIFIED");
        }
        if (period == null) {
            return new DayDenominators(null, dayShareEval == null ? null : dayShareEval.sharesAtDay(),
                    "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE", null, null);
        }
        FxResolution fx = resolveFxForDay(fxLookup, period.getReportingCurrency(), stock.getCurrency(), day);
        if (!fx.isUsable()) {
            String fxFail = fx.reason() != null ? fx.reason().name() : "REPORTING_CURRENCY_MISMATCH";
            return new DayDenominators(null, dayShareEval == null ? null : dayShareEval.sharesAtDay(),
                    fxFail, null, fxFail);
        }
        DayEpsResolution epsRes = resolveAlignedDayEps(stock.getCurrency(), status, period, stockSplits, day,
                dayShareEval == null ? null : dayShareEval.sharesAtDay(),
                dayShareEval == null ? null : dayShareEval.sharesReason(),
                effectiveAdr, fx.usdPerUnit());
        return new DayDenominators(epsRes.epsAtDay(), dayShareEval == null ? null : dayShareEval.sharesAtDay(),
                epsRes.peReason(), fx.usdPerUnit(), null);
    }

    private DayEpsResolution resolveAlignedDayEps(
            String quoteCurrency, StockStatus status, StockFinancialPeriod period,
            List<StockSplit> stockSplits, LocalDate day, BigDecimal sharesAtDay, String sharesReason,
            AdrContext adrCtx, BigDecimal fxMult) {
        if (period == null || fxMult == null || fxMult.signum() <= 0) {
            return new DayEpsResolution(null, period == null ? "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE" : "FX_RATE_UNAVAILABLE");
        }
        if (adrCtx != null && adrCtx.adr()) {
            if (!adrCtx.verified()) {
                return new DayEpsResolution(null, "ADR_RATIO_UNVERIFIED");
            }
            if (!MetricStatus.AVAILABLE.name().equals(period.getNetIncomeCommonTtmStatus())
                    || period.getNetIncomeCommonTtm() == null) {
                String niReason = period.getNetIncomeCommonTtmReason() != null
                        ? period.getNetIncomeCommonTtmReason().name() : "NET_INCOME_COMMON_MISSING";
                return new DayEpsResolution(null, niReason);
            }
            if (sharesAtDay == null || sharesAtDay.signum() <= 0) {
                return new DayEpsResolution(null, sharesReason != null ? sharesReason : "VALUATION_DATE_SHARES_MISSING");
            }
            BigDecimal netIncomeQuote = period.getNetIncomeCommonTtm().multiply(fxMult, MC);
            return new DayEpsResolution(netIncomeQuote.divide(sharesAtDay, MC), null);
        }
        String splitError = splitSyncError(status);
        BigDecimal epsAtDay = null;
        String peReason = period.getEpsTtmReason() == null ? null : period.getEpsTtmReason().name();
        if (period.getEpsTtm() != null && MetricStatus.AVAILABLE.name().equals(period.getEpsTtmStatus())) {
            if (period.getEpsShareBasisDate() == null) {
                peReason = "EPS_SOURCE_PERIOD_MISSING";
            } else if (splitError != null && !period.getEpsShareBasisDate().equals(day)) {
                peReason = splitError;
            } else {
                BigDecimal factor = StockSplitCalculator.shareFactor(stockSplits, period.getEpsShareBasisDate(), day);
                if (factor.signum() > 0) {
                    epsAtDay = period.getEpsTtm().divide(factor, MC).multiply(fxMult, MC);
                    peReason = null;
                } else {
                    peReason = "EPS_SPLIT_BASIS_UNCONFIRMED";
                }
            }
        } else if (peReason == null) {
            peReason = "DILUTED_EPS_TTM_MISSING";
        }
        return new DayEpsResolution(epsAtDay, peReason);
    }

    private AdrContext resolveAdrContext(Stock stock) {
        if (stock == null || !SecurityTypePolicy.isAdrType(stock.getStockType())) {
            return AdrContext.nonAdr();
        }
        if (adrShareRatios == null || stock.getSymbol() == null || stock.getSymbol().isBlank()) {
            return AdrContext.unverified();
        }
        AdrShareRatio ratio = adrShareRatios.findById(stock.getSymbol().trim().toUpperCase(java.util.Locale.ROOT)).orElse(null);
        if (ratio == null || !ratio.isVerified() || ratio.getAdrRatio() == null || ratio.getAdrRatio().signum() <= 0) {
            return AdrContext.unverified();
        }
        return AdrContext.verified(ratio);
    }

    private Map<String, AdrContext> loadAdrContexts(Collection<Stock> batchStocks) {
        if (batchStocks == null || batchStocks.isEmpty()) {
            return Map.of();
        }
        Set<String> adrSymbols = new LinkedHashSet<>();
        for (Stock s : batchStocks) {
            if (s != null && SecurityTypePolicy.isAdrType(s.getStockType())
                    && s.getSymbol() != null && !s.getSymbol().isBlank()) {
                adrSymbols.add(s.getSymbol().trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        Map<String, AdrShareRatio> ratioBySymbol = new HashMap<>();
        if (!adrSymbols.isEmpty() && adrShareRatios != null) {
            for (AdrShareRatio row : adrShareRatios.findBySymbolIn(adrSymbols)) {
                if (row != null && row.getSymbol() != null) {
                    ratioBySymbol.put(row.getSymbol().trim().toUpperCase(java.util.Locale.ROOT), row);
                }
            }
        }
        Map<String, AdrContext> result = new HashMap<>();
        for (Stock s : batchStocks) {
            if (s == null) continue;
            if (!SecurityTypePolicy.isAdrType(s.getStockType())) {
                result.put(s.getFigi(), AdrContext.nonAdr());
                continue;
            }
            String sym = s.getSymbol() == null ? null : s.getSymbol().trim().toUpperCase(java.util.Locale.ROOT);
            AdrShareRatio ratio = sym == null ? null : ratioBySymbol.get(sym);
            if (ratio != null && ratio.isVerified() && ratio.getAdrRatio() != null && ratio.getAdrRatio().signum() > 0) {
                result.put(s.getFigi(), AdrContext.verified(ratio));
            } else {
                result.put(s.getFigi(), AdrContext.unverified());
            }
        }
        return result;
    }

    private FxRateLookup preloadFxLookup(Collection<StockFinancialPeriod> periods, LocalDate fromDate, LocalDate throughDate) {
        if (fxRateService == null || periods == null || periods.isEmpty() || fromDate == null || throughDate == null) {
            return FxRateLookup.EMPTY;
        }
        Set<String> currencies = new LinkedHashSet<>();
        for (StockFinancialPeriod p : periods) {
            if (p != null && p.getReportingCurrency() != null && !p.getReportingCurrency().isBlank()) {
                currencies.add(p.getReportingCurrency().trim().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return fxRateService.preload(currencies, fromDate, throughDate);
    }

    private FxResolution resolveFxForDay(FxRateLookup fxLookup, String reportingCurrency, String quoteCurrency, LocalDate day) {
        if (sameCurrency(quoteCurrency, reportingCurrency)) {
            return FxResolution.identity(quoteCurrency);
        }
        if (fxRateService == null) {
            return FxResolution.unavailable(reportingCurrency, quoteCurrency, ValuationReason.REPORTING_CURRENCY_MISMATCH);
        }
        FxRateLookup lookup = fxLookup != null ? fxLookup : FxRateLookup.EMPTY;
        return lookup.resolve(reportingCurrency, quoteCurrency, day);
    }

    private String dominantSecMonetaryUnit(List<SecFinancialFact> facts, LocalDate periodEnd) {
        if (facts == null || facts.isEmpty()) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (SecFinancialFact f : facts) {
            if (f == null || f.getUnit() == null || f.getUnit().isBlank()) continue;
            String unit = f.getUnit().trim();
            if ("shares".equalsIgnoreCase(unit) || "pure".equalsIgnoreCase(unit) || unit.contains("/")) continue;
            if (unit.length() != 3) continue;
            int weight = periodEnd != null && periodEnd.equals(f.getPeriodEnd()) ? 3 : 1;
            counts.merge(unit.toUpperCase(java.util.Locale.ROOT), weight, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** 填入 10 項財務品質深化欄位（使用獨立 qualitySecFlows，不影響既有 14 項與 availableAt 披露時間門）。 */
    private void populateFinancialQualityFields(
            StockFinancialPeriod row,
            List<ReportValues> reportsUpToP,
            ReportValues effectiveIncome,
            ReportValues balance,
            ReportValues effectiveCashFlow,
            Flows secFlows,
            String reportingCurrency,
            LocalDate periodEnd,
            LocalDate ttmStart,
            LocalDate cfTtmStart,
            Instant incomeObs,
            Instant balanceObs,
            Instant cashFlowObs) {
        Set<Flow> qualitySecFlows = new ArrayListSet();

        // 毛利 TTM
        Flow secGross = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.GROSS_PROFIT), periodEnd);
        ResolvedFlow gross = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.GROSS_PROFIT,
                secGross, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 毛利 TTM", "SEC 毛利 TTM", qualitySecFlows);
        row.setGrossProfitTtm(gross.value());
        row.setGrossProfitTtmSource(gross.source());
        row.setGrossProfitTtmReason(gross.value() != null ? null : (gross.reason() != null ? gross.reason() : ValuationReason.GROSS_PROFIT_MISSING));

        // 營業利益 TTM
        Flow secOp = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.OPERATING_PROFIT), periodEnd);
        ResolvedFlow op = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.OPERATING_PROFIT,
                secOp, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 營業利益 TTM", "SEC 營業利益 TTM", qualitySecFlows);
        row.setOperatingProfitTtm(op.value());
        row.setOperatingProfitTtmSource(op.source());
        row.setOperatingProfitTtmReason(op.value() != null ? null : (op.reason() != null ? op.reason() : ValuationReason.OPERATING_PROFIT_MISSING));

        // 稅前盈利 TTM
        Flow secPretax = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.PRETAX_PROFIT), periodEnd);
        ResolvedFlow pretax = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.PRETAX_PROFIT,
                secPretax, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 稅前盈利 TTM", "SEC 稅前盈利 TTM", qualitySecFlows);
        row.setPretaxProfitTtm(pretax.value());
        row.setPretaxProfitTtmSource(pretax.source());
        row.setPretaxProfitTtmReason(pretax.value() != null ? null : (pretax.reason() != null ? pretax.reason() : ValuationReason.PRETAX_OR_TAX_MISSING));

        // 所得稅費用 TTM
        Flow secTax = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.INCOME_TAX_EXPENSE), periodEnd);
        ResolvedFlow tax = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.INCOME_TAX_EXPENSE,
                secTax, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 所得稅費用 TTM", "SEC 所得稅費用 TTM", qualitySecFlows);
        row.setIncomeTaxExpenseTtm(tax.value());
        row.setIncomeTaxExpenseTtmSource(tax.source());
        row.setIncomeTaxExpenseTtmReason(tax.value() != null ? null : (tax.reason() != null ? tax.reason() : ValuationReason.PRETAX_OR_TAX_MISSING));

        // 利息費用 TTM（正規化為正數規模）
        Flow secInt = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.INTEREST_EXPENSE_AS_REPORTED), periodEnd);
        ResolvedFlow interest = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.INTEREST_EXPENSE_AS_REPORTED,
                secInt, secFlows, reportingCurrency, ttmStart, incomeObs, "Futu 利息費用 TTM", "SEC 利息費用 TTM", qualitySecFlows);
        BigDecimal intVal = interest.value() == null ? null : interest.value().abs();
        row.setInterestExpenseTtm(intVal);
        row.setInterestExpenseTtmSource(interest.source());
        row.setInterestExpenseTtmReason(intVal != null ? null : (interest.reason() != null ? interest.reason() : ValuationReason.INTEREST_EXPENSE_MISSING));

        // SBC TTM（Futu 無獨立 SBC，以 SEC 標準標籤為主）
        Flow secSbc = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.SBC), periodEnd);
        if (secSbc != null && secSbc.value() != null) {
            row.setSbcTtm(secSbc.value());
            row.setSbcTtmSource("SEC");
            row.setSbcTtmReason(null);
        } else {
            row.setSbcTtm(null);
            row.setSbcTtmSource("SEC");
            row.setSbcTtmReason(secSbc != null && secSbc.reason() != null
                    ? ValuationReason.fromCode(secSbc.reason()) : ValuationReason.SBC_MISSING);
        }

        // 普通股現金股息流出 TTM（正規化為非負流出規模）
        Flow secDiv = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.CASH_DIVIDENDS_PAID), periodEnd);
        ResolvedFlow div = resolveTrailingFlow(reportsUpToP, effectiveCashFlow, FinancialConcept.CASH_DIVIDENDS_PAID,
                secDiv, secFlows, reportingCurrency, cfTtmStart, cashFlowObs, "Futu 現金股息 TTM", "SEC 現金股息 TTM", qualitySecFlows);
        BigDecimal divVal = div.value() == null ? null : (div.value().signum() < 0 ? div.value().negate() : div.value());
        row.setCashDividendsPaidTtm(divVal);
        row.setCashDividendsPaidTtmSource(div.source());
        row.setCashDividendsPaidTtmReason(divVal != null ? null : (div.reason() != null ? div.reason() : ValuationReason.SHAREHOLDER_RETURN_MISSING));

        // 普通股淨發行現金流 TTM（正數發行、負數回購；不以 SEC gross 回購冒充 net）
        MetricResult<BigDecimal> futuNetIssuance = effectiveCashFlow == null
                ? missingMetric("SHAREHOLDER_RETURN_MISSING")
                : calculator.trailingTwelveMonths(reportsUpToP, effectiveCashFlow.figi(), 3,
                        FinancialConcept.NET_COMMON_STOCK_ISSUANCE, effectiveCashFlow.periodEnd(), reportingCurrency);
        if (futuNetIssuance.value() != null) {
            row.setNetCommonStockIssuanceTtm(futuNetIssuance.value());
            row.setNetCommonStockIssuanceTtmSource("FUTU");
            row.setNetCommonStockIssuanceTtmReason(null);
        } else {
            row.setNetCommonStockIssuanceTtm(null);
            row.setNetCommonStockIssuanceTtmSource("FUTU");
            String rCode = reasonOf(futuNetIssuance);
            row.setNetCommonStockIssuanceTtmReason(rCode != null
                    ? ValuationReason.fromCode(rCode) : ValuationReason.SHAREHOLDER_RETURN_MISSING);
        }

        // 期末商譽與無形資產合計（優先 Futu 權威合計或分項合成，缺時由 SEC Goodwill + Intangibles 補齊）
        ReportValues currencyMatchedBalance = balance != null && sameCurrency(reportingCurrency, balance.currency()) ? balance : null;
        MetricResult<BigDecimal> futuGw = currencyMatchedBalance == null
                ? missingMetric("GOODWILL_INTANGIBLES_MISSING") : calculator.goodwillAndIntangibles(currencyMatchedBalance);
        Flow secGw = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.GOODWILL_AND_INTANGIBLES), periodEnd);
        if (preferSec(secGw, futuGw, currencyMatchedBalance)) {
            row.setGoodwillAndIntangibles(secGw.value());
            row.setGoodwillAndIntangiblesSource("SEC");
            row.setGoodwillAndIntangiblesReason(secGw.value() != null ? null
                    : (secGw.reason() != null ? ValuationReason.fromCode(secGw.reason()) : ValuationReason.GOODWILL_INTANGIBLES_MISSING));
        } else if (futuGw.value() != null) {
            row.setGoodwillAndIntangibles(futuGw.value());
            row.setGoodwillAndIntangiblesSource("FUTU");
            row.setGoodwillAndIntangiblesReason(null);
        } else {
            row.setGoodwillAndIntangibles(null);
            row.setGoodwillAndIntangiblesSource("FUTU");
            String rCode = reasonOf(futuGw);
            row.setGoodwillAndIntangiblesReason(rCode != null
                    ? ValuationReason.fromCode(rCode) : ValuationReason.GOODWILL_INTANGIBLES_MISSING);
        }

        // 期末顯式短期計息債務（缺披露保留 NULL，不沿用 EV 補零）
        MetricResult<BigDecimal> futuCurDebt = currencyMatchedBalance == null
                ? missingMetric("CURRENT_DEBT_MISSING") : calculator.explicitCurrentDebt(currencyMatchedBalance);
        Flow secCurDebt = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.CURRENT_DEBT), periodEnd);
        if (preferSec(secCurDebt, futuCurDebt, currencyMatchedBalance)) {
            row.setCurrentDebt(secCurDebt.value());
            row.setCurrentDebtSource("SEC");
            row.setCurrentDebtReason(secCurDebt.value() != null ? null
                    : (secCurDebt.reason() != null ? ValuationReason.fromCode(secCurDebt.reason()) : ValuationReason.CURRENT_DEBT_MISSING));
        } else if (futuCurDebt.value() != null) {
            row.setCurrentDebt(futuCurDebt.value());
            row.setCurrentDebtSource("FUTU");
            row.setCurrentDebtReason(null);
        } else {
            row.setCurrentDebt(null);
            row.setCurrentDebtSource("FUTU");
            String rCode = reasonOf(futuCurDebt);
            row.setCurrentDebtReason(rCode != null ? ValuationReason.fromCode(rCode) : ValuationReason.CURRENT_DEBT_MISSING);
        }
    }

    private BigDecimal positiveMetricValue(ValuationResultDto.Metrics metrics, String metricCode) {
        if (metrics == null || metricCode == null) return null;
        ValuationResultDto.Value cell = metrics.byMetricCode(metricCode);
        if (cell != null && "AVAILABLE".equals(cell.status()) && cell.value() != null && cell.value().signum() > 0) {
            return cell.value();
        }
        return null;
    }

    /** 自身歷史估值參考：以單一適用主指標貫穿 <= valuationDate 的 5 年月頻有效樣本計算 P20 / P50 / P80 並換算價格區間，保留視窗總月數與排除月數。 */
    private ValuationReferenceDto buildHistoricalReference(
            String selectedMetric, BigDecimal currentMultiple, List<ValuationResultDto> monthlyHistory, StockFinancialPeriod period,
            DayDenominators denoms, String quoteCurrency, boolean financialInstitution, LocalDate day) {
        if (denoms != null && denoms.fxFailureReason() != null) {
            ValuationReason failReason = ValuationReason.fromCode(denoms.fxFailureReason());
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_HISTORICAL_MARKET, selectedMetric,
                    failReason.statusName(), denoms.fxFailureReason());
        }
        if (period != null && !sameCurrency(quoteCurrency, period.getReportingCurrency())
                && (denoms == null || denoms.fxRateToQuote() == null || denoms.fxRateToQuote().signum() <= 0)) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_HISTORICAL_MARKET, selectedMetric,
                    MetricStatus.UNVERIFIED.name(), "REPORTING_CURRENCY_MISMATCH");
        }
        int windowCount = 0;
        LocalDate firstSampleDate = null;
        LocalDate lastSampleDate = null;
        List<BigDecimal> samples = new ArrayList<>();
        for (ValuationResultDto row : monthlyHistory) {
            if (row.valuationDate() == null || row.valuationDate().isAfter(day)) continue;
            windowCount++;
            if (row.metrics() == null) continue;
            ValuationResultDto.Value cell = row.metrics().byMetricCode(selectedMetric);
            if (cell != null && "AVAILABLE".equals(cell.status()) && cell.value() != null && cell.value().signum() > 0) {
                samples.add(cell.value());
                if (firstSampleDate == null || row.valuationDate().isBefore(firstSampleDate)) {
                    firstSampleDate = row.valuationDate();
                }
                if (lastSampleDate == null || row.valuationDate().isAfter(lastSampleDate)) {
                    lastSampleDate = row.valuationDate();
                }
            }
        }
        int excludedCount = Math.max(0, windowCount - samples.size());
        if (samples.size() < MIN_HISTORICAL_SAMPLES) {
            return ValuationReferenceDto.unavailableWithSampleCoverage(
                    ValuationReferenceDto.KIND_HISTORICAL_MARKET, selectedMetric,
                    MetricStatus.INSUFFICIENT_DATA.name(), "HISTORICAL_SAMPLES_INSUFFICIENT",
                    samples.size(), windowCount, excludedCount, firstSampleDate, lastSampleDate);
        }
        samples.sort(BigDecimal::compareTo);
        BigDecimal low = quantile(samples, 0.20);
        BigDecimal median = quantile(samples, 0.50);
        BigDecimal high = quantile(samples, 0.80);
        BigDecimal rawPriceLow = convertMultipleToPrice(selectedMetric, low, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal rawPriceMedian = convertMultipleToPrice(selectedMetric, median, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal rawPriceHigh = convertMultipleToPrice(selectedMetric, high, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal priceLow = rawPriceLow != null && rawPriceHigh != null ? rawPriceLow : null;
        BigDecimal priceHigh = rawPriceLow != null && rawPriceHigh != null ? rawPriceHigh : null;
        BigDecimal priceMedian = priceLow != null && priceHigh != null ? rawPriceMedian : null;
        BigDecimal currentPercentile = midRankPercentile(samples, currentMultiple);
        return new ValuationReferenceDto(ValuationReferenceDto.KIND_HISTORICAL_MARKET, selectedMetric,
                MetricStatus.AVAILABLE.name(), null, low, median, high, priceLow, priceHigh,
                samples.size(), windowCount, excludedCount, firstSampleDate, lastSampleDate,
                currentMultiple, currentPercentile, priceMedian);
    }

    /** 市值量級切分：僅對 currency = 'USD' 且 marketCap > 0 計算，否則固定為 null。 */
    public static String resolveMarketCapTier(String currency, BigDecimal marketCap) {
        if (!"USD".equalsIgnoreCase(currency) || marketCap == null || marketCap.signum() <= 0) {
            return null;
        }
        if (marketCap.compareTo(MEGA_LARGE_CAP_THRESHOLD) >= 0) {
            return TIER_MEGA_LARGE;
        }
        if (marketCap.compareTo(MID_CAP_THRESHOLD) >= 0) {
            return TIER_MID;
        }
        return TIER_SMALL;
    }

    private BigDecimal extractAvailableMetric(MetricResult<BigDecimal> metric) {
        if (metric != null && metric.status() == MetricStatus.AVAILABLE && metric.value() != null) {
            return metric.value();
        }
        return null;
    }

    private String canonicalIssuerKey(String rawKey, String figi) {
        if (rawKey != null && !rawKey.isBlank()) {
            String trimmed = rawKey.trim();
            if (trimmed.matches("[0-9]{10}")) {
                return issuerHistory.canonicalCik(trimmed);
            }
            return trimmed;
        }
        return figi;
    }

    private boolean isSelfOrSiblingIssuer(Stock targetStock, LocalDate day, String candidateRawKey, String candidateFigi) {
        if (targetStock == null) return false;
        if (targetStock.getFigi().equals(candidateFigi)) return true;
        String candCanonical = canonicalIssuerKey(candidateRawKey, candidateFigi);
        if (targetStock.getCik() != null && !targetStock.getCik().isBlank()) {
            String targetCurrentCanon = issuerHistory.canonicalCik(targetStock.getCik());
            if (Objects.equals(targetCurrentCanon, candCanonical)) return true;
            String dayCik = resolveCik(targetStock, day);
            if (dayCik != null && Objects.equals(issuerHistory.canonicalCik(dayCik), candCanonical)) return true;
        }
        return false;
    }

    /** 同業市場估值參考（個股報告入口）：支援 CUSTOM_PEERS -> SAME_TIER -> ADJACENT_TIER -> ALL_INDUSTRY 四級回退與基本面中位數對照。 */
    private ValuationReferenceDto buildPeerReference(
            Stock targetStock,
            String selectedMetric,
            ValuationResultDto.Metrics targetMetrics,
            String industryCode,
            LocalDate valuationDate,
            boolean historicalDate,
            StockFinancialPeriod period,
            DayDenominators denoms,
            String quoteCurrency,
            BigDecimal targetMarketCap,
            BigDecimal targetRevGrowthYoy,
            boolean financialInstitution) {
        if (historicalDate || valuationDate == null) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_PEER_MARKET, selectedMetric,
                    MetricStatus.NOT_APPLICABLE.name(), "HISTORICAL_DATE_NOT_APPLICABLE");
        }

        List<StockCustomPeer> configuredCustom = customPeers.findByFigiOrderBySortOrderAsc(targetStock.getFigi());
        List<IssuerSnapshotRow> customSnapshotRows = new ArrayList<>();
        if (configuredCustom != null && !configuredCustom.isEmpty()) {
            List<String> peerFigis = configuredCustom.stream().map(StockCustomPeer::getPeerFigi).toList();
            List<Object[]> rawCustom = latestValuations.findActiveTrackedCustomPeerSnapshots(peerFigis, List.of(valuationDate));
            if (rawCustom != null) {
                for (Object[] tuple : rawCustom) {
                    if (tuple != null && tuple.length >= 2 && tuple[0] instanceof String rawKey
                            && tuple[1] instanceof StockValuationLatest snap) {
                        customSnapshotRows.add(new IssuerSnapshotRow(rawKey, snap));
                    }
                }
            }
        }

        List<IssuerSnapshotRow> industrySnapshotRows = new ArrayList<>();
        if (industryCode != null && !industryCode.isBlank()) {
            List<Object[]> rawInd = latestValuations.findActiveTrackedPeerSnapshots(industryCode, valuationDate);
            if (rawInd != null) {
                for (Object[] tuple : rawInd) {
                    if (tuple != null && tuple.length >= 2 && tuple[0] instanceof String rawKey
                            && tuple[1] instanceof StockValuationLatest snap) {
                        industrySnapshotRows.add(new IssuerSnapshotRow(rawKey, snap));
                    }
                }
            }
        }

        return computePeerReferenceFromSnapshots(
                targetStock, selectedMetric, targetMetrics, industryCode, valuationDate, false,
                period, denoms, quoteCurrency, targetMarketCap, targetRevGrowthYoy, financialInstitution,
                configuredCustom == null ? List.of() : configuredCustom,
                customSnapshotRows, industrySnapshotRows);
    }

    /** 純記憶體同業參考與品質中位數計算核心（供個股分析報告與兩階段批次刷新完全共用，零資料庫 I/O）。 */
    private ValuationReferenceDto computePeerReferenceFromSnapshots(
            Stock targetStock,
            String selectedMetric,
            ValuationResultDto.Metrics targetMetrics,
            String industryCode,
            LocalDate valuationDate,
            boolean historicalDate,
            StockFinancialPeriod period,
            DayDenominators denoms,
            String quoteCurrency,
            BigDecimal targetMarketCap,
            BigDecimal targetRevGrowthYoy,
            boolean financialInstitution,
            List<StockCustomPeer> configuredCustom,
            List<IssuerSnapshotRow> customCandidateRows,
            List<IssuerSnapshotRow> industryCandidateRows) {
        if (historicalDate || valuationDate == null) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_PEER_MARKET, selectedMetric,
                    MetricStatus.NOT_APPLICABLE.name(), "HISTORICAL_DATE_NOT_APPLICABLE");
        }

        String targetTier = resolveMarketCapTier(quoteCurrency, targetMarketCap);

        // 先整理全產業（排除目標股自身及兄弟股）之發行人聚合，並計算全產業主指標中位數副標（>= 5 家時提供）
        List<IssuerPeerAggregate> allIndustryIssuers = aggregatePeerIssuers(targetStock, valuationDate, industryCandidateRows);
        List<BigDecimal> allIndustryMultiples = new ArrayList<>();
        for (IssuerPeerAggregate issuer : allIndustryIssuers) {
            BigDecimal val = issuer.metricMedians().get(selectedMetric);
            if (val != null && val.signum() > 0) {
                allIndustryMultiples.add(val);
            }
        }
        BigDecimal industryAllMedian = null;
        Integer industryAllCount = industryCode != null && !industryCode.isBlank() ? allIndustryMultiples.size() : null;
        String industryAllMetricCode = industryCode != null && !industryCode.isBlank() ? selectedMetric : null;
        if (allIndustryMultiples.size() >= MIN_PEER_ISSUERS) {
            allIndustryMultiples.sort(BigDecimal::compareTo);
            industryAllMedian = quantile(allIndustryMultiples, 0.50);
        }

        String peerFallbackReason = null;

        // 第一優先：CUSTOM_PEERS（有效發行人 >= 3 家，不套用市值分層過濾）
        if (configuredCustom != null && !configuredCustom.isEmpty()) {
            String rawPref = configuredCustom.getFirst().getPreferredMetric();
            String customMetric = resolveCustomMetric(rawPref, selectedMetric, financialInstitution);
            List<IssuerPeerAggregate> customIssuers = aggregatePeerIssuers(targetStock, valuationDate, customCandidateRows);
            List<IssuerPeerAggregate> validCustom = new ArrayList<>();
            for (IssuerPeerAggregate issuer : customIssuers) {
                BigDecimal m = issuer.metricMedians().get(customMetric);
                if (m != null && m.signum() > 0) {
                    validCustom.add(issuer);
                }
            }
            if (validCustom.size() >= MIN_CUSTOM_PEER_ISSUERS) {
                BigDecimal currentCustomMultiple = positiveMetricValue(targetMetrics, customMetric);
                return assembleAvailablePeerReference(
                        customMetric, currentCustomMultiple, validCustom,
                        ValuationReferenceDto.SCOPE_CUSTOM_PEERS, targetTier, null,
                        industryAllMetricCode, industryAllMedian, industryAllCount,
                        valuationDate, period, denoms, quoteCurrency, targetMarketCap, targetRevGrowthYoy, financialInstitution, true);
            }
            peerFallbackReason = "CUSTOM_PEERS_INSUFFICIENT";
        }

        // 第二至第四優先：產業規模分層（SAME_TIER -> ADJACENT_TIER -> ALL_INDUSTRY，門檻皆 >= 5 家）
        if (industryCode == null || industryCode.isBlank()) {
            return ValuationReferenceDto.unavailablePeer(
                    selectedMetric, MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_CLASSIFICATION_MISSING",
                    targetTier, peerFallbackReason, null, null, null);
        }

        List<IssuerPeerAggregate> validIndustryIssuers = new ArrayList<>();
        for (IssuerPeerAggregate issuer : allIndustryIssuers) {
            BigDecimal m = issuer.metricMedians().get(selectedMetric);
            if (m != null && m.signum() > 0) {
                validIndustryIssuers.add(issuer);
            }
        }
        if (validIndustryIssuers.size() < MIN_PEER_ISSUERS) {
            return ValuationReferenceDto.unavailablePeer(
                    selectedMetric, MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_PEER_COMPANIES_INSUFFICIENT",
                    targetTier, peerFallbackReason, industryAllMetricCode, industryAllMedian, industryAllCount);
        }

        List<IssuerPeerAggregate> chosenIssuers = null;
        String chosenScope = null;

        if (targetTier != null) {
            List<IssuerPeerAggregate> sameTier = new ArrayList<>();
            for (IssuerPeerAggregate issuer : validIndustryIssuers) {
                if (targetTier.equals(issuer.marketCapTier())) {
                    sameTier.add(issuer);
                }
            }
            if (sameTier.size() >= MIN_PEER_ISSUERS) {
                chosenIssuers = sameTier;
                chosenScope = ValuationReferenceDto.SCOPE_SAME_TIER;
            } else {
                List<IssuerPeerAggregate> adjacentTier = new ArrayList<>();
                for (IssuerPeerAggregate issuer : validIndustryIssuers) {
                    if (isAdjacentOrSameTier(targetTier, issuer.marketCapTier())) {
                        adjacentTier.add(issuer);
                    }
                }
                if (adjacentTier.size() >= MIN_PEER_ISSUERS) {
                    chosenIssuers = adjacentTier;
                    chosenScope = ValuationReferenceDto.SCOPE_ADJACENT_TIER;
                }
            }
        }

        if (chosenIssuers == null) {
            chosenIssuers = validIndustryIssuers;
            chosenScope = ValuationReferenceDto.SCOPE_ALL_INDUSTRY;
        }

        BigDecimal currentSelectedMultiple = positiveMetricValue(targetMetrics, selectedMetric);
        return assembleAvailablePeerReference(
                selectedMetric, currentSelectedMultiple, chosenIssuers,
                chosenScope, targetTier, peerFallbackReason,
                industryAllMetricCode, industryAllMedian, industryAllCount,
                valuationDate, period, denoms, quoteCurrency, targetMarketCap, targetRevGrowthYoy, financialInstitution, false);
    }

    private boolean isAdjacentOrSameTier(String targetTier, String candidateTier) {
        if (candidateTier == null) return false;
        if (TIER_MEGA_LARGE.equals(targetTier)) {
            return TIER_MEGA_LARGE.equals(candidateTier) || TIER_MID.equals(candidateTier);
        }
        if (TIER_SMALL.equals(targetTier)) {
            return TIER_SMALL.equals(candidateTier) || TIER_MID.equals(candidateTier);
        }
        if (TIER_MID.equals(targetTier)) {
            return TIER_MEGA_LARGE.equals(candidateTier) || TIER_MID.equals(candidateTier) || TIER_SMALL.equals(candidateTier);
        }
        return false;
    }

    private String resolveCustomMetric(String preferredMetric, String fallbackMetric, boolean financialInstitution) {
        if (preferredMetric == null || preferredMetric.isBlank()) {
            return fallbackMetric;
        }
        String upper = preferredMetric.trim().toUpperCase();
        List<String> allowed = financialInstitution ? FIN_SUPPORTED_METRICS : NON_FIN_SUPPORTED_METRICS;
        return allowed.contains(upper) ? upper : fallbackMetric;
    }

    private List<IssuerPeerAggregate> aggregatePeerIssuers(
            Stock targetStock, LocalDate valuationDate, List<IssuerSnapshotRow> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, List<StockValuationLatest>> grouped = new LinkedHashMap<>();
        for (IssuerSnapshotRow row : rows) {
            if (row == null || row.snapshot() == null) continue;
            StockValuationLatest snap = row.snapshot();
            if (!Objects.equals(snap.getValuationDate(), valuationDate)) continue;
            if (isSelfOrSiblingIssuer(targetStock, valuationDate, row.rawIssuerKey(), snap.getFigi())) continue;
            String canonicalKey = canonicalIssuerKey(row.rawIssuerKey(), snap.getFigi());
            grouped.computeIfAbsent(canonicalKey, k -> new ArrayList<>()).add(snap);
        }

        List<IssuerPeerAggregate> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<StockValuationLatest>> entry : grouped.entrySet()) {
            List<StockValuationLatest> snaps = entry.getValue();
            List<BigDecimal> usdCaps = new ArrayList<>();
            List<BigDecimal> revGrowths = new ArrayList<>();
            List<BigDecimal> roes = new ArrayList<>();
            List<BigDecimal> fcfMargins = new ArrayList<>();
            Map<String, List<BigDecimal>> metricSamples = new HashMap<>();

            for (StockValuationLatest s : snaps) {
                if ("USD".equalsIgnoreCase(s.getCurrency()) && s.getMarketCap() != null && s.getMarketCap().signum() > 0) {
                    usdCaps.add(s.getMarketCap());
                }
                if (s.getRevenueGrowthYoy() != null) revGrowths.add(s.getRevenueGrowthYoy());
                if (s.getRoe() != null) roes.add(s.getRoe());
                if (s.getFcfMargin() != null && !IndustryValuationProposalService.isFinancialInstitutionModel(s.getModelCode())) {
                    fcfMargins.add(s.getFcfMargin());
                }
                for (String code : NON_FIN_SUPPORTED_METRICS) {
                    BigDecimal val = extractLatestMetric(s, code);
                    if (val != null && val.signum() > 0) {
                        metricSamples.computeIfAbsent(code, k -> new ArrayList<>()).add(val);
                    }
                }
            }

            BigDecimal usdCapMedian = medianScaled(usdCaps, 4);
            String explicitTier = null;
            if (usdCapMedian != null) {
                explicitTier = resolveMarketCapTier("USD", usdCapMedian);
            } else {
                for (StockValuationLatest s : snaps) {
                    if (s.getMarketCapTier() != null) {
                        explicitTier = s.getMarketCapTier();
                        break;
                    }
                }
            }

            Map<String, BigDecimal> metricMedians = new HashMap<>();
            for (Map.Entry<String, List<BigDecimal>> me : metricSamples.entrySet()) {
                List<BigDecimal> list = me.getValue();
                list.sort(BigDecimal::compareTo);
                metricMedians.put(me.getKey(), quantile(list, 0.50));
            }

            result.add(new IssuerPeerAggregate(
                    entry.getKey(),
                    usdCapMedian,
                    explicitTier,
                    metricMedians,
                    medianScaled(revGrowths, 6),
                    medianScaled(roes, 6),
                    medianScaled(fcfMargins, 6)
            ));
        }
        return result;
    }

    private ValuationReferenceDto assembleAvailablePeerReference(
            String metricCode,
            BigDecimal currentMultiple,
            List<IssuerPeerAggregate> selectedIssuers,
            String peerScope,
            String targetTier,
            String peerFallbackReason,
            String industryAllMetricCode,
            BigDecimal industryAllMedian,
            Integer industryAllCount,
            LocalDate valuationDate,
            StockFinancialPeriod period,
            DayDenominators denoms,
            String quoteCurrency,
            BigDecimal targetMarketCap,
            BigDecimal targetRevGrowthYoy,
            boolean financialInstitution,
            boolean evaluateCustomWarnings) {
        List<BigDecimal> issuerMultiples = new ArrayList<>(selectedIssuers.size());
        List<BigDecimal> revGrowthList = new ArrayList<>();
        List<BigDecimal> roeList = new ArrayList<>();
        List<BigDecimal> fcfMarginList = new ArrayList<>();

        for (IssuerPeerAggregate issuer : selectedIssuers) {
            BigDecimal m = issuer.metricMedians().get(metricCode);
            if (m != null && m.signum() > 0) {
                issuerMultiples.add(m);
            }
            if (issuer.revenueGrowthYoyMedian() != null) revGrowthList.add(issuer.revenueGrowthYoyMedian());
            if (issuer.roeMedian() != null) roeList.add(issuer.roeMedian());
            if (!financialInstitution && issuer.fcfMarginMedian() != null) {
                fcfMarginList.add(issuer.fcfMarginMedian());
            }
        }

        issuerMultiples.sort(BigDecimal::compareTo);
        BigDecimal low = quantile(issuerMultiples, 0.25);
        BigDecimal median = quantile(issuerMultiples, 0.50);
        BigDecimal high = quantile(issuerMultiples, 0.75);
        BigDecimal rawPriceLow = convertMultipleToPrice(metricCode, low, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal rawPriceMedian = convertMultipleToPrice(metricCode, median, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal rawPriceHigh = convertMultipleToPrice(metricCode, high, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal priceLow = rawPriceLow != null && rawPriceHigh != null ? rawPriceLow : null;
        BigDecimal priceHigh = rawPriceLow != null && rawPriceHigh != null ? rawPriceHigh : null;
        BigDecimal priceMedian = priceLow != null && priceHigh != null ? rawPriceMedian : null;
        BigDecimal currentPercentile = midRankPercentile(issuerMultiples, currentMultiple);

        BigDecimal peerRevGrowthMedian = medianScaled(revGrowthList, 6);
        BigDecimal peerRoeMedian = medianScaled(roeList, 6);
        BigDecimal peerFcfMarginMedian = financialInstitution ? null : medianScaled(fcfMarginList, 6);
        Integer peerFcfCount = financialInstitution ? null : (fcfMarginList.isEmpty() ? 0 : fcfMarginList.size());

        List<String> warnings = new ArrayList<>();
        if (evaluateCustomWarnings) {
            if ("USD".equalsIgnoreCase(quoteCurrency) && targetMarketCap != null && targetMarketCap.signum() > 0) {
                boolean capMismatch = false;
                for (IssuerPeerAggregate issuer : selectedIssuers) {
                    BigDecimal pCap = issuer.usdMarketCapMedian();
                    if (pCap != null && pCap.signum() > 0) {
                        BigDecimal larger = targetMarketCap.max(pCap);
                        BigDecimal threshold = targetMarketCap.min(pCap).multiply(CUSTOM_PEER_CAP_RATIO_WARN, MC);
                        if (larger.compareTo(threshold) > 0) {
                            capMismatch = true;
                            break;
                        }
                    }
                }
                if (capMismatch) {
                    warnings.add("CUSTOM_PEER_MARKET_CAP_MISMATCH");
                }
            }
            if (targetRevGrowthYoy != null && peerRevGrowthMedian != null) {
                BigDecimal growthDiff = targetRevGrowthYoy.subtract(peerRevGrowthMedian, MC).abs();
                if (growthDiff.compareTo(CUSTOM_PEER_GROWTH_DIFF_WARN) >= 0) {
                    warnings.add("CUSTOM_PEER_GROWTH_DIVERGENCE");
                }
            }
        }

        return new ValuationReferenceDto(
                ValuationReferenceDto.KIND_PEER_MARKET,
                metricCode,
                MetricStatus.AVAILABLE.name(),
                null,
                low,
                median,
                high,
                priceLow,
                priceHigh,
                issuerMultiples.size(),
                null,
                null,
                valuationDate,
                valuationDate,
                currentMultiple,
                currentPercentile,
                priceMedian,
                peerScope,
                targetTier,
                peerFallbackReason,
                industryAllMetricCode,
                industryAllMedian,
                industryAllCount,
                peerRevGrowthMedian,
                revGrowthList.size(),
                peerRoeMedian,
                roeList.size(),
                peerFcfMarginMedian,
                peerFcfCount,
                warnings
        );
    }

    private BigDecimal medianScaled(List<BigDecimal> values, int scale) {
        if (values == null || values.isEmpty()) return null;
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        int n = sorted.size();
        if (n == 1) return sorted.getFirst().setScale(scale, RoundingMode.HALF_UP);
        double pos = 0.50 * (n - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        if (lower == upper) {
            return sorted.get(lower).setScale(scale, RoundingMode.HALF_UP);
        }
        BigDecimal weight = BigDecimal.valueOf(pos - lower);
        BigDecimal lowVal = sorted.get(lower);
        BigDecimal highVal = sorted.get(upper);
        return lowVal.add(highVal.subtract(lowVal, MC).multiply(weight, MC), MC).setScale(scale, RoundingMode.HALF_UP);
    }

    /** 產業估值假設：僅在最新收市日讀取當前生效設定換算假設價格區間；無經驗分佈故 currentPercentile 固定為 null。 */
    private ValuationReferenceDto buildIndustryAssumption(
            String fallbackMetric, ValuationResultDto.Metrics metrics, IndustryModelContext modelCtx, String industryCode, boolean historicalDate,
            StockFinancialPeriod period, DayDenominators denoms, String quoteCurrency) {
        String configuredMetric = modelCtx != null && modelCtx.configuredPrimaryMetric() != null && !modelCtx.configuredPrimaryMetric().isBlank()
                ? modelCtx.configuredPrimaryMetric().trim().toUpperCase() : null;
        String assumptionMetric = configuredMetric != null ? configuredMetric : fallbackMetric;
        if (historicalDate) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                    MetricStatus.NOT_APPLICABLE.name(), "HISTORICAL_DATE_NOT_APPLICABLE");
        }
        if (industryCode == null || industryCode.isBlank()) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                    MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_CLASSIFICATION_MISSING");
        }
        if (configuredMetric == null) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                    MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_PRIMARY_METRIC_MISSING");
        }
        boolean financialInstitution = modelCtx.financialInstitution();
        if (financialInstitution && !"PE".equals(assumptionMetric) && !"PB".equals(assumptionMetric)) {
            String reason = "PFCF".equals(assumptionMetric) ? "PFCF_NOT_APPLICABLE_TO_INDUSTRY" : "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY";
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                    MetricStatus.NOT_APPLICABLE.name(), reason);
        }
        BigDecimal assumedLow = modelCtx.assumedMultipleLow();
        BigDecimal assumedHigh = modelCtx.assumedMultipleHigh();
        if (assumedLow == null || assumedHigh == null || assumedLow.signum() <= 0 || assumedHigh.compareTo(assumedLow) < 0) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                    MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_FAIR_RANGE_MISSING");
        }
        BigDecimal multipleLow = assumedLow.setScale(4, RoundingMode.HALF_UP);
        BigDecimal multipleHigh = assumedHigh.setScale(4, RoundingMode.HALF_UP);
        BigDecimal rawPriceLow = convertMultipleToPrice(assumptionMetric, multipleLow, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal rawPriceHigh = convertMultipleToPrice(assumptionMetric, multipleHigh, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal priceLow = rawPriceLow != null && rawPriceHigh != null ? rawPriceLow : null;
        BigDecimal priceHigh = rawPriceLow != null && rawPriceHigh != null ? rawPriceHigh : null;
        BigDecimal assumptionMidPrice = priceLow != null && priceHigh != null
                ? priceLow.add(priceHigh, MC).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP) : null;
        BigDecimal currentMultiple = positiveMetricValue(metrics, assumptionMetric);
        return new ValuationReferenceDto(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                MetricStatus.AVAILABLE.name(), null, multipleLow, null, multipleHigh, priceLow, priceHigh,
                null, null, null, null, null, currentMultiple, null, assumptionMidPrice);
    }

    /** 以所選交易日配對之同財季每股分母（已含當日匯率與 ADR 股數口徑對齊）將目標估值倍數換算為隱含正股價；分母非正或缺值時回傳 null。 */
    private BigDecimal convertMultipleToPrice(
            String metricCode, BigDecimal multiple, StockFinancialPeriod period,
            DayDenominators denoms, String quoteCurrency, boolean financialInstitution) {
        if (metricCode == null || multiple == null || multiple.signum() <= 0 || period == null || denoms == null) {
            return null;
        }
        if (denoms.fxFailureReason() != null) {
            return null;
        }
        BigDecimal fxMult;
        if (sameCurrency(quoteCurrency, period.getReportingCurrency())) {
            fxMult = BigDecimal.ONE;
        } else if (denoms.fxRateToQuote() != null && denoms.fxRateToQuote().signum() > 0) {
            fxMult = denoms.fxRateToQuote();
        } else {
            return null;
        }
        BigDecimal impliedPrice = switch (metricCode.toUpperCase()) {
            case "PE" -> denoms.epsAtDay() != null && denoms.epsAtDay().signum() > 0
                    ? multiple.multiply(denoms.epsAtDay(), MC).setScale(4, RoundingMode.HALF_UP) : null;
            case "PB" -> MetricStatus.AVAILABLE.name().equals(period.getCommonEquityStatus())
                    && period.getCommonEquity() != null && period.getCommonEquity().signum() > 0
                    && denoms.sharesAtDay() != null && denoms.sharesAtDay().signum() > 0
                    ? multiple.multiply(period.getCommonEquity().multiply(fxMult, MC), MC).divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            case "PS" -> MetricStatus.AVAILABLE.name().equals(period.getRevenueTtmStatus())
                    && period.getRevenueTtm() != null && period.getRevenueTtm().signum() > 0
                    && denoms.sharesAtDay() != null && denoms.sharesAtDay().signum() > 0
                    ? multiple.multiply(period.getRevenueTtm().multiply(fxMult, MC), MC).divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            case "PFCF" -> !financialInstitution && MetricStatus.AVAILABLE.name().equals(period.getFreeCashFlowTtmStatus())
                    && period.getFreeCashFlowTtm() != null && period.getFreeCashFlowTtm().signum() > 0
                    && denoms.sharesAtDay() != null && denoms.sharesAtDay().signum() > 0
                    ? multiple.multiply(period.getFreeCashFlowTtm().multiply(fxMult, MC), MC).divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            case "EV_EBITDA" -> {
                if (financialInstitution || !MetricStatus.AVAILABLE.name().equals(period.getEbitdaTtmStatus())
                        || period.getEbitdaTtm() == null || period.getEbitdaTtm().signum() <= 0
                        || !MetricStatus.AVAILABLE.name().equals(period.getEvAdjustmentStatus())
                        || period.getEvAdjustment() == null
                        || denoms.sharesAtDay() == null || denoms.sharesAtDay().signum() <= 0) {
                    yield null;
                }
                BigDecimal ebitdaQuote = period.getEbitdaTtm().multiply(fxMult, MC);
                BigDecimal evAdjQuote = period.getEvAdjustment().multiply(fxMult, MC);
                BigDecimal impliedMarketCap = multiple.multiply(ebitdaQuote, MC).subtract(evAdjQuote, MC);
                yield impliedMarketCap.signum() > 0 ? impliedMarketCap.divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            }
            default -> null;
        };
        return impliedPrice != null && impliedPrice.signum() > 0 ? impliedPrice : null;
    }

    private BigDecimal extractLatestMetric(StockValuationLatest row, String metricCode) {
        if (row == null || metricCode == null) return null;
        return switch (metricCode.toUpperCase()) {
            case "PE" -> "AVAILABLE".equals(row.getPeStatus()) ? row.getPe() : null;
            case "PB" -> "AVAILABLE".equals(row.getPbStatus()) ? row.getPb() : null;
            case "PS" -> "AVAILABLE".equals(row.getPsStatus()) ? row.getPs() : null;
            case "PFCF" -> "AVAILABLE".equals(row.getPfcfStatus()) ? row.getPfcf() : null;
            case "EV_EBITDA" -> "AVAILABLE".equals(row.getEvEbitdaStatus()) ? row.getEvEbitda() : null;
            default -> null;
        };
    }

    static BigDecimal midRankPercentile(List<BigDecimal> sortedAscending, BigDecimal currentMultiple) {
        if (currentMultiple == null || currentMultiple.signum() <= 0
                || sortedAscending == null || sortedAscending.isEmpty()) {
            return null;
        }
        int below = 0;
        int equal = 0;
        for (BigDecimal sample : sortedAscending) {
            int cmp = sample.compareTo(currentMultiple);
            if (cmp < 0) {
                below++;
            } else if (cmp == 0) {
                equal++;
            }
        }
        BigDecimal rank = BigDecimal.valueOf(below)
                .add(BigDecimal.valueOf(equal).multiply(new BigDecimal("0.5"), MC), MC);
        return rank.multiply(BigDecimal.valueOf(100), MC)
                .divide(BigDecimal.valueOf(sortedAscending.size()), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal quantile(List<BigDecimal> sortedAscending, double p) {
        int n = sortedAscending.size();
        if (n == 1) return sortedAscending.getFirst().setScale(4, RoundingMode.HALF_UP);
        double pos = p * (n - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        if (lower == upper) {
            return sortedAscending.get(lower).setScale(4, RoundingMode.HALF_UP);
        }
        BigDecimal weight = BigDecimal.valueOf(pos - lower);
        BigDecimal lowVal = sortedAscending.get(lower);
        BigDecimal highVal = sortedAscending.get(upper);
        return lowVal.add(highVal.subtract(lowVal, MC).multiply(weight, MC), MC).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 判定當期 TTM 是否為虧損（普通股／已核實 ADR／未核實 ADR 三態分流）：
     * 1. 未核實 ADR 直接回 false（維持阻斷語意，不混用未核實口徑）；
     * 2. 已核實 ADR 優先採公司層級 netIncomeCommonTtm <= 0（與公司口徑 P/E 一致）；
     * 3. 普通股優先採拆股已對齊之 epsTtm <= 0，缺 epsTtm 時回退公司層級 netIncomeCommonTtm <= 0。
     */
    public static boolean isCurrentTtmLoss(StockFinancialPeriod matched, StockStatus status, LocalDate day,
                                           boolean adr, boolean verifiedAdr) {
        if (matched == null) {
            return false;
        }
        if (adr && !verifiedAdr) {
            return false;
        }
        if (adr) {
            if (matched.getNetIncomeCommonTtm() != null && MetricStatus.AVAILABLE.name().equals(matched.getNetIncomeCommonTtmStatus())) {
                return matched.getNetIncomeCommonTtm().signum() <= 0;
            }
        }
        if (matched.getEpsTtm() != null && MetricStatus.AVAILABLE.name().equals(matched.getEpsTtmStatus())) {
            String splitError = splitSyncError(status);
            if (splitError == null || matched.getEpsShareBasisDate() == null || matched.getEpsShareBasisDate().equals(day)) {
                return matched.getEpsTtm().signum() <= 0;
            }
        }
        return matched.getNetIncomeCommonTtm() != null
                && MetricStatus.AVAILABLE.name().equals(matched.getNetIncomeCommonTtmStatus())
                && matched.getNetIncomeCommonTtm().signum() <= 0;
    }

    private void populateLatestEntity(
            StockValuationLatest entity,
            ValuationResultDto dto,
            BigDecimal marketCap,
            String marketCapTier,
            BigDecimal revenueGrowthYoy,
            BigDecimal roe,
            BigDecimal fcfMargin,
            BigDecimal historicalPercentile,
            String verdict,
            BigDecimal fairValueLow,
            BigDecimal fairValueMid,
            BigDecimal fairValueHigh,
            String fundamentalBacking) {
        entity.setFigi(dto.figi());
        entity.setValuationDate(dto.valuationDate());
        entity.setAnalysisAsOf(dto.analysisAsOf());
        entity.setCalculatedAt(dto.calculatedAt());
        entity.setFinancialObservedAt(dto.financialObservedAt());
        entity.setSharesObservedAt(dto.sharesObservedAt());
        entity.setMarketCapSource(dto.marketCapSource());
        entity.setSecFinancialAvailableFromDate(dto.secFinancialAvailableFromDate());
        entity.setFinancialSource(dto.source());
        entity.setCurrency(dto.currency());
        entity.setClosePrice(dto.price());
        entity.setIndustryCode(dto.industryCode());
        entity.setModelCode(dto.modelCode());
        entity.setPrimaryMetric(dto.primaryMetric());
        entity.setStatus(dto.status());
        entity.setReasonCode(ValuationReason.fromCode(dto.reasonCode()));

        ValuationResultDto.Metrics m = dto.metrics();
        entity.setPe(m.pe().value());
        entity.setPeStatus(m.pe().status());
        entity.setPeReason(ValuationReason.fromCode(m.pe().reasonCode()));
        entity.setPeFinancialSource(m.pe().financialSource());
        entity.setPePeriodStart(m.pe().periodStart());
        entity.setPePeriodEnd(m.pe().periodEnd());

        entity.setPb(m.pb().value());
        entity.setPbStatus(m.pb().status());
        entity.setPbReason(ValuationReason.fromCode(m.pb().reasonCode()));
        entity.setPbFinancialSource(m.pb().financialSource());
        entity.setPbPeriodEnd(m.pb().periodEnd());

        entity.setPs(m.ps().value());
        entity.setPsStatus(m.ps().status());
        entity.setPsReason(ValuationReason.fromCode(m.ps().reasonCode()));
        entity.setPsFinancialSource(m.ps().financialSource());
        entity.setPsPeriodStart(m.ps().periodStart());
        entity.setPsPeriodEnd(m.ps().periodEnd());

        entity.setPfcf(m.pfcf().value());
        entity.setPfcfStatus(m.pfcf().status());
        entity.setPfcfReason(ValuationReason.fromCode(m.pfcf().reasonCode()));
        entity.setPfcfFinancialSource(m.pfcf().financialSource());
        entity.setPfcfPeriodStart(m.pfcf().periodStart());
        entity.setPfcfPeriodEnd(m.pfcf().periodEnd());

        entity.setEvEbitda(m.evEbitda().value());
        entity.setEvEbitdaStatus(m.evEbitda().status());
        entity.setEvEbitdaReason(ValuationReason.fromCode(m.evEbitda().reasonCode()));
        entity.setEvEbitdaFinancialSource(m.evEbitda().financialSource());
        entity.setEvEbitdaPeriodStart(m.evEbitda().periodStart());
        entity.setEvEbitdaPeriodEnd(m.evEbitda().periodEnd());

        entity.setMarketCap(marketCap);
        entity.setMarketCapTier(marketCapTier);
        entity.setRevenueGrowthYoy(revenueGrowthYoy);
        entity.setRoe(roe);
        entity.setFcfMargin(fcfMargin);
        entity.setHistoricalPercentile(historicalPercentile);
        entity.setVerdict(verdict);
        entity.setFairValueLow(fairValueLow);
        entity.setFairValueMid(fairValueMid);
        entity.setFairValueHigh(fairValueHigh);
        entity.setFundamentalBacking(fundamentalBacking);
    }

    private ValuationResultDto unavailableDayResult(
            String figi, LocalDate day, Instant cutoff, Instant calculatedAt,
            String industryCode, String modelCode, String currency, BigDecimal price, String reason) {
        boolean financial = IndustryValuationProposalService.isFinancialInstitutionModel(modelCode);
        String pfcfReason = financial ? "PFCF_NOT_APPLICABLE_TO_INDUSTRY" : reason;
        String evReason = financial ? "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY" : reason;
        ValuationResultDto.Metrics metrics = new ValuationResultDto.Metrics(
                ValuationResultDto.financialValue(null, reason, "FUTU", null, null),
                ValuationResultDto.financialValue(null, reason, "FUTU", null, null),
                ValuationResultDto.financialValue(null, reason, "FUTU", null, null),
                ValuationResultDto.financialValue(null, pfcfReason, "FUTU", null, null),
                ValuationResultDto.financialValue(null, evReason, "FUTU", null, null)
        );
        String status = ValuationReason.statusNameFor(reason);
        return new ValuationResultDto(figi, day, cutoff, "FINANCIAL_REPORT_AS_OBSERVED",
                industryCode, modelCode, financial ? "PB" : "PE", status, reason,
                metrics, price, currency, calculatedAt, null, null, null, null, null, null, null);
    }

    private ReportValues statementAtPeriod(List<ReportValues> reports, int statementType, LocalDate periodEnd) {
        return reports.stream()
                .filter(r -> r.statementType() == statementType && periodEnd.equals(r.periodEnd()))
                .max(Comparator.comparingInt(ReportValues::periodType))
                .orElse(null);
    }

    private ReportValues effectiveFlowStatement(ReportValues futuAtPeriod, Flows secFlows, int statementType, LocalDate periodEnd) {
        if (futuAtPeriod != null) return futuAtPeriod;
        if (secFlows == null) return null;
        return secFlows.missingQuarters().stream()
                .filter(r -> r.statementType() == statementType && periodEnd.equals(r.periodEnd()))
                .max(Comparator.comparingInt(ReportValues::periodType))
                .orElse(null);
    }

    private List<ReportValues> combineReportsWithMissingQuarters(List<ReportValues> base, List<ReportValues> missing) {
        List<ReportValues> combined = new ArrayList<>(base.size() + missing.size());
        combined.addAll(base);
        combined.addAll(missing);
        return combined;
    }

    private Flow samePeriodSecFlow(Flow flow, LocalDate periodEnd) {
        if (flow == null || flow.periodEnd() == null || periodEnd == null) return null;
        return Math.abs(ChronoUnit.DAYS.between(flow.periodEnd(), periodEnd)) <= 7 ? flow : null;
    }

    private List<ReportValues> normalizeReportStandardsWithSec(Stock stock, List<ReportValues> aligned, List<SecFinancialFact> secFactsList) {
        if (aligned.isEmpty() || secFactsList.isEmpty()) return aligned;
        List<ReportValues> result = new ArrayList<>(aligned.size());
        for (ReportValues r : aligned) {
            String rcik = resolveCik(stock, r.periodEnd());
            List<SecFinancialFact> cikFacts = rcik == null ? List.of()
                    : secFactsList.stream().filter(f -> rcik.equals(f.getCik())).toList();
            if (r.periodType() == 7 && r.statementType() != 2 && isShortTransitionAnnualAt(cikFacts, r.periodEnd())) {
                continue;
            }
            boolean domesticUsGaapAtEnd = hasDomesticUsGaapFilingAt(cikFacts, r.periodEnd());
            boolean blankStandardWithSecUsGaap = (r.accountingStandards() == null || r.accountingStandards().isBlank())
                    && cikFacts.stream().anyMatch(f -> "us-gaap".equals(f.getTaxonomy()));
            if (domesticUsGaapAtEnd || blankStandardWithSecUsGaap) {
                result.add(new ReportValues(r.figi(), r.statementType(), r.periodEnd(), r.periodType(),
                        r.fiscalYear(), r.currency(), "US_GAAP", r.values(), r.unknownNames(), r.pendingItems()));
            } else {
                result.add(r);
            }
        }
        return List.copyOf(result);
    }

    private boolean isShortTransitionAnnualAt(List<SecFinancialFact> cikFacts, LocalDate periodEnd) {
        if (cikFacts == null || cikFacts.isEmpty() || periodEnd == null) return false;
        boolean hasShortTransition = cikFacts.stream().anyMatch(f -> ("10-KT".equals(f.getForm()) || "10-KT/A".equals(f.getForm()))
                && f.getPeriodStart() != null && f.getPeriodEnd() != null
                && Math.abs(ChronoUnit.DAYS.between(f.getPeriodEnd(), periodEnd)) <= 7
                && ChronoUnit.DAYS.between(f.getPeriodStart(), f.getPeriodEnd()) + 1 < 330);
        if (!hasShortTransition) return false;
        return cikFacts.stream().noneMatch(f -> f.getPeriodStart() != null && f.getPeriodEnd() != null
                && Math.abs(ChronoUnit.DAYS.between(f.getPeriodEnd(), periodEnd)) <= 7
                && ChronoUnit.DAYS.between(f.getPeriodStart(), f.getPeriodEnd()) + 1 >= 350);
    }

    private boolean hasDomesticUsGaapFilingAt(List<SecFinancialFact> cikFacts, LocalDate periodEnd) {
        if (cikFacts == null || cikFacts.isEmpty() || periodEnd == null) return false;
        return cikFacts.stream().anyMatch(f -> "us-gaap".equals(f.getTaxonomy())
                && US_DOMESTIC_GAAP_FORMS.contains(Objects.toString(f.getForm(), ""))
                && f.getPeriodEnd() != null && Math.abs(ChronoUnit.DAYS.between(f.getPeriodEnd(), periodEnd)) <= 7);
    }

    private String latestReportCurrency(List<ReportValues> reportsUpToP) {
        for (int i = reportsUpToP.size() - 1; i >= 0; i--) {
            String cur = reportsUpToP.get(i).currency();
            if (cur != null && !cur.isBlank()) return cur;
        }
        return null;
    }

    private List<Flow> collectConceptFlows(Map<FinancialConcept, Flow> secBalUsed, List<FinancialConcept> concepts) {
        List<Flow> flows = new ArrayList<>();
        for (FinancialConcept c : concepts) {
            Flow f = secBalUsed.get(c);
            if (f != null) flows.add(f);
        }
        return flows;
    }

    private String checkFinancingScopeReason(ReportValues balance) {
        if (balance == null) return null;
        for (FinancialConcept financing : List.of(FinancialConcept.FEDERAL_FUNDS_AND_REPO_LIABILITIES,
                FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, FinancialConcept.BANK_DEPOSITS)) {
            MetricResult<BigDecimal> value = balance.value(financing);
            if (value.status() == MetricStatus.UNVERIFIED) return reasonOf(value);
            if (value.value() != null && value.value().signum() != 0) return "EV_FINANCING_SCOPE_UNRESOLVED";
        }
        return null;
    }

    private LocalDate resolveFutuTtmStart(List<ReportValues> reports, List<SecFinancialFact> periodCikFacts,
                                          String figi, int statementType, LocalDate periodEnd,
                                          LocalDate quarterStart, LocalDate secAnnualStart) {
        List<ReportValues> periods = calculator.trailingPeriods(reports, figi, statementType, periodEnd);
        if (periods.isEmpty()) return quarterStart;
        if (periods.size() == 1 && periods.getFirst().periodType() == 7) {
            if (secAnnualStart != null) return secAnnualStart;
            LocalDate priorAnnualEnd = reports.stream()
                    .filter(r -> r.figi().equals(figi) && (r.periodType() == 7 || r.periodType() == 4) && r.periodEnd().isBefore(periodEnd))
                    .map(ReportValues::periodEnd)
                    .filter(d -> {
                        long days = ChronoUnit.DAYS.between(d, periodEnd);
                        return days >= 350 && days <= 380;
                    })
                    .max(LocalDate::compareTo).orElse(null);
            if (priorAnnualEnd != null) return priorAnnualEnd.plusDays(1);
            return periodEnd.minusYears(1).plusDays(1);
        }
        if (!calculator.areContiguousQuarters(periods, periodEnd)) {
            return quarterStart;
        }
        ReportValues earliest = periods.getLast();
        LocalDate earliestEnd = earliest.periodEnd();

        LocalDate secExactStart = secCalculator.exactQuarterStart(periodCikFacts, earliestEnd);
        if (secExactStart != null) {
            return secExactStart;
        }

        Set<LocalDate> validPriorEnds = reports.stream()
                .filter(r -> r.figi().equals(figi) && r.statementType() == statementType && r.periodEnd().isBefore(earliestEnd))
                .filter(r -> isAdjacentPriorQuarter(r, earliest))
                .map(ReportValues::periodEnd)
                .collect(Collectors.toSet());
        if (validPriorEnds.size() == 1) {
            return validPriorEnds.iterator().next().plusDays(1);
        }

        LocalDate secPriorQuarterEnd = secCalculator.uniquePriorSecQuarterEnd(periodCikFacts, earliestEnd);
        if (secPriorQuarterEnd != null) {
            return secPriorQuarterEnd.plusDays(1);
        }

        if (areThreeMonthCalendarQuarters(periods)) {
            return YearMonth.from(earliestEnd).minusMonths(2).atDay(1);
        }
        return earliestEnd.minusDays(90);
    }

    private boolean isAdjacentPriorQuarter(ReportValues candidate, ReportValues earliest) {
        int candidateQuarter = candidate.periodType() == 7 ? 4 : candidate.periodType();
        if (candidateQuarter < 1 || candidateQuarter > 4) return false;
        long gapDays = ChronoUnit.DAYS.between(candidate.periodEnd(), earliest.periodEnd());
        if (gapDays < 70 || gapDays > 125) return false;
        if (earliest.fiscalYear() != null && candidate.fiscalYear() != null) {
            int candidateSeq = candidate.fiscalYear() * 4 + candidateQuarter;
            int earliestSeq = earliest.fiscalYear() * 4 + earliest.periodType();
            return candidateSeq == earliestSeq - 1;
        }
        int expectedPrevQuarter = earliest.periodType() == 1 ? 4 : earliest.periodType() - 1;
        return candidateQuarter == expectedPrevQuarter;
    }

    private boolean areThreeMonthCalendarQuarters(List<ReportValues> periods) {
        if (periods.size() != 4) return false;
        for (int i = 0; i < periods.size(); i++) {
            LocalDate end = periods.get(i).periodEnd();
            YearMonth ym = YearMonth.from(end);
            if (!end.equals(ym.atEndOfMonth())) return false;
            if (i > 0 && !YearMonth.from(periods.get(i - 1).periodEnd()).minusMonths(3).equals(ym)) return false;
        }
        return true;
    }

    private Instant statementObservedAt(ReportValues report, Map<ReportObservationKey, Instant> map) {
        if (report == null) return null;
        return map.get(new ReportObservationKey(report.statementType(), report.periodEnd(), report.periodType()));
    }

    private ReportValues supplementBalance(ReportValues balance, Flows sec, Map<FinancialConcept, Flow> usedByConcept) {
        Map<FinancialConcept, MetricResult<BigDecimal>> values = new EnumMap<>(FinancialConcept.class);
        values.putAll(balance.values());

        // 修正富途個別報期母公司股東權益欄位錯值（如缺 8095 少數股東權益，且富途總權益與 SEC StockholdersEquity 一致、8085 卻偏離逾 10%）
        Flow secParent = sec.input(FinancialConcept.STOCKHOLDERS_EQUITY, balance);
        BigDecimal futuParent = balance.value(FinancialConcept.STOCKHOLDERS_EQUITY).value();
        BigDecimal futuTotalEq = balance.value(FinancialConcept.TOTAL_EQUITY).value();
        BigDecimal futuNci = balance.value(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY).value();
        if (futuNci == null && futuParent != null && futuTotalEq != null && secParent != null
                && secParent.value() != null && secParent.value().signum() > 0 && balance.periodEnd().equals(secParent.periodEnd())) {
            BigDecimal totalDiff = futuTotalEq.subtract(secParent.value(), MC).abs().divide(secParent.value(), MC);
            BigDecimal parentDiff = futuParent.subtract(secParent.value(), MC).abs().divide(secParent.value(), MC);
            if (totalDiff.compareTo(TWO_PERCENT) <= 0 && parentDiff.compareTo(TEN_PERCENT) > 0) {
                values.put(FinancialConcept.STOCKHOLDERS_EQUITY, MetricResult.available(secParent.value()));
                usedByConcept.put(FinancialConcept.STOCKHOLDERS_EQUITY, secParent);
            }
        }

        // 當富途僅列非流動借款 8069（與 SEC LongTermDebtNoncurrent 一致）而漏列短期借款 8057/8058 時，由同報期 SEC 補齊短期純借款
        BigDecimal futuCurDebt = balance.value(FinancialConcept.CURRENT_DEBT).value();
        BigDecimal futuCurCombined = balance.value(FinancialConcept.CURRENT_DEBT_AND_LEASES).value();
        BigDecimal futuLtDebt = balance.value(FinancialConcept.LONG_TERM_DEBT).value();
        Flow secCurDebt = sec.input(FinancialConcept.CURRENT_DEBT, balance);
        Flow secLtNoncurrent = sec.input(FinancialConcept.LONG_TERM_DEBT, balance);
        if (futuCurDebt == null && futuCurCombined == null && futuLtDebt != null && futuLtDebt.signum() > 0
                && secLtNoncurrent != null && secLtNoncurrent.value() != null && futuLtDebt.compareTo(secLtNoncurrent.value()) == 0
                && secCurDebt != null && secCurDebt.value() != null && secCurDebt.value().signum() > 0
                && balance.periodEnd().equals(secCurDebt.periodEnd())) {
            values.put(FinancialConcept.CURRENT_DEBT, MetricResult.available(secCurDebt.value()));
            usedByConcept.put(FinancialConcept.CURRENT_DEBT, secCurDebt);
        }

        for (FinancialConcept concept : List.of(
                FinancialConcept.CURRENT_OPERATING_LEASES, FinancialConcept.LONG_TERM_OPERATING_LEASES,
                FinancialConcept.CURRENT_FINANCE_LEASES, FinancialConcept.LONG_TERM_FINANCE_LEASES, FinancialConcept.TOTAL_FINANCE_LEASES,
                FinancialConcept.CASH_AND_EQUIVALENTS, FinancialConcept.PREFERRED_STOCK_CAPITAL,
                FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, FinancialConcept.STOCKHOLDERS_EQUITY,
                FinancialConcept.TOTAL_EQUITY, FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED)) {
            Flow input = sec.input(concept, balance);
            MetricResult<BigDecimal> existing = values.getOrDefault(concept, balance.value(concept));
            if (existing.value() != null || existing.status() == MetricStatus.UNVERIFIED || input == null || input.value() == null
                    || !balance.periodEnd().equals(input.periodEnd())) continue;
            if ((concept == FinancialConcept.PREFERRED_STOCK_CAPITAL && input.value().signum() == 0 && "PreferredStock default 0".equals(input.note()))
                    || (concept == FinancialConcept.NONCONTROLLING_INTEREST_EQUITY && input.value().signum() == 0
                            && "NoncontrollingInterest default 0".equals(input.note()))) {
                continue;
            }
            values.put(concept, MetricResult.available(input.value()));
            usedByConcept.put(concept, input);
        }
        return new ReportValues(balance.figi(), balance.statementType(), balance.periodEnd(), balance.periodType(),
                balance.fiscalYear(), balance.currency(), balance.accountingStandards(), values, balance.unknownNames(), balance.pendingItems());
    }

    private List<ReportValues> supplementFlowReports(List<ReportValues> reports, ReportValues latest, Flows sec,
                                                     List<Flow> used, List<FinancialConcept> concepts) {
        List<ReportValues> available = new ArrayList<>(reports);
        if (sec != null) available.addAll(sec.missingQuarters());
        List<ReportValues> trailing = calculator.trailingPeriods(available, latest.figi(), latest.statementType(), latest.periodEnd());
        Map<ReportObservationKey, ReportValues> replaced = new HashMap<>();
        for (ReportValues report : trailing) {
            Map<FinancialConcept, MetricResult<BigDecimal>> values = new EnumMap<>(FinancialConcept.class);
            values.putAll(report.values());
            boolean secSuppliedCapex = false;
            if (sec != null) {
                for (FinancialConcept concept : concepts) {
                    MetricResult<BigDecimal> original = calculator.flowInput(report, concept);
                    Flow input = sec.input(concept, report);
                    if (original.value() != null || original.status() == MetricStatus.UNVERIFIED || input == null || input.value() == null) continue;
                    if (concept == FinancialConcept.CAPITAL_EXPENDITURE && input.value().signum() < 0) continue;
                    values.put(concept, MetricResult.available(input.value()));
                    used.add(input);
                    if (concept == FinancialConcept.CAPITAL_EXPENDITURE) secSuppliedCapex = true;
                }
            }
            if (report.statementType() == 3 && report.value(FinancialConcept.FREE_CASH_FLOW).status() == MetricStatus.INSUFFICIENT_DATA) {
                MetricResult<BigDecimal> cfo = values.containsKey(FinancialConcept.OPERATING_CASH_FLOW)
                        ? values.get(FinancialConcept.OPERATING_CASH_FLOW)
                        : calculator.flowInput(report, FinancialConcept.OPERATING_CASH_FLOW);
                MetricResult<BigDecimal> capex = values.get(FinancialConcept.CAPITAL_EXPENDITURE);
                BigDecimal separateNetPpe = report.value(FinancialConcept.NET_PPE_CASH_FLOW).value();
                boolean safeFutuCapex = secSuppliedCapex || separateNetPpe == null || separateNetPpe.signum() == 0;
                if (safeFutuCapex && cfo != null && cfo.value() != null && capex != null && capex.value() != null) {
                    values.put(FinancialConcept.FREE_CASH_FLOW, MetricResult.available(cfo.value().subtract(capex.value().abs(), MC)));
                }
            }
            ReportValues enriched = new ReportValues(report.figi(), report.statementType(), report.periodEnd(), report.periodType(),
                    report.fiscalYear(), report.currency(), report.accountingStandards(), values, report.unknownNames(), report.pendingItems());
            replaced.put(new ReportObservationKey(report.statementType(), report.periodEnd(), report.periodType()), enriched);
        }
        List<ReportValues> result = new ArrayList<>(available.size());
        for (ReportValues item : available) {
            ReportObservationKey key = new ReportObservationKey(item.statementType(), item.periodEnd(), item.periodType());
            result.add(replaced.getOrDefault(key, item));
        }
        return result;
    }

    private boolean sameCloseSnapshot(StockShareObservation obs, StockQuote quote, LocalDate day, Instant cutoff, Instant calculatedAt) {
        if (obs == null || quote == null || quote.getClosePrice() == null || quote.getClosePrice().signum() <= 0
                || obs.getSnapshotPrice() == null || obs.getSnapshotPrice().signum() <= 0
                || obs.effectiveMarketCap() == null || obs.effectiveMarketCap().signum() <= 0
                || obs.getSnapshotQuoteTime() == null || obs.getSnapshotQuoteTime().isBefore(calendar.getSessionOpenAt(day))
                || obs.getSnapshotQuoteTime().isAfter(cutoff)
                || obs.getObservedAt().isBefore(cutoff)
                || obs.getSnapshotQuoteTime().isAfter(obs.getObservedAt()) || obs.getObservedAt().isAfter(calculatedAt)
                || !obs.getSnapshotQuoteTime().atZone(MarketStatusService.NY_ZONE).toLocalDate().equals(day)) {
            return false;
        }
        return obs.getSnapshotPrice().setScale(8, RoundingMode.HALF_UP)
                .compareTo(quote.getClosePrice().setScale(8, RoundingMode.HALF_UP)) == 0;
    }

    public static String splitSyncError(StockStatus status) {
        if (status == null) return null;
        String msg = status.getSplitMessage();
        if (msg != null && !msg.isBlank() && !msg.startsWith("WARNING:")
                && !msg.startsWith("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:")) {
            return "SPLIT_SYNC_ERROR";
        }
        return null;
    }

    private boolean preferSec(Flow sec, MetricResult<BigDecimal> existing, ReportValues current) {
        if (sec == null || sec.periodEnd() == null) return false;
        if (current == null || sec.periodEnd().isAfter(current.periodEnd())) return true;
        if (sec.periodEnd().isBefore(current.periodEnd())) return false;
        return sec.value() != null && (existing == null || existing.value() == null);
    }

    private boolean hasReportReason(Flow sec, ReportValues current) {
        return sec != null && sec.reason() != null && (current == null || current.periodEnd().equals(sec.periodEnd()));
    }

    private boolean hasSplitAfter(List<StockSplit> splits, LocalDate after, LocalDate through) {
        if (after == null || through == null || !after.isBefore(through)) return false;
        return splits.stream().anyMatch(s -> s.getExecutionDate().isAfter(after) && !s.getExecutionDate().isAfter(through));
    }

    private boolean hasSplitBetween(List<StockSplit> splits, LocalDate first, LocalDate second) {
        if (first == null || second == null || first.equals(second)) return false;
        LocalDate from = first.isBefore(second) ? first : second;
        LocalDate to = first.isAfter(second) ? first : second;
        return splits.stream().anyMatch(s -> s.getExecutionDate().isAfter(from) && !s.getExecutionDate().isAfter(to));
    }

    private boolean isInvalidatingReason(ValuationReason reason) {
        return reason.metricStatus() == MetricStatus.UNVERIFIED || reason.metricStatus() == MetricStatus.NOT_APPLICABLE;
    }

    private String combineSources(String first, String second) {
        if (Objects.equals(first, second)) return first == null ? "FUTU" : first;
        if (first == null) return second;
        if (second == null) return first;
        return "FUTU_AND_SEC";
    }

    private static boolean sameCurrency(String first, String second) {
        return first != null && !first.isBlank() && second != null && first.equalsIgnoreCase(second);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private Instant maxInstant(Instant... instants) {
        Instant max = null;
        for (Instant item : instants) {
            max = laterInstant(max, item);
        }
        return max;
    }

    private Instant laterInstant(Instant a, Instant b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private LocalDate laterDate(LocalDate a, LocalDate b) {
        return a == null ? b : b == null || a.isAfter(b) ? a : b;
    }

    private MetricResult<BigDecimal> missingMetric(String reason) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(reason, "VALUATION", "缺少已核實的估值輸入。"));
    }

    private MetricResult<BigDecimal> unverifiedMetric(String reason) {
        return MetricResult.unavailable(MetricStatus.UNVERIFIED, AnalysisReasonDto.of(reason, "VALUATION", "來源範圍或基準尚未確認。"));
    }

    private String reasonOf(MetricResult<BigDecimal> metric) {
        return metric == null || metric.value() != null || metric.reasons().isEmpty() ? null : metric.reasons().getFirst().code();
    }

    private String formatStatusSummary(StockValuationLatest row) {
        return "PE=" + (row.getPe() != null ? "AVAILABLE" : row.getPeReason())
                + ";PB=" + (row.getPb() != null ? "AVAILABLE" : row.getPbReason())
                + ";PS=" + (row.getPs() != null ? "AVAILABLE" : row.getPsReason())
                + ";PFCF=" + (row.getPfcf() != null ? "AVAILABLE" : row.getPfcfReason())
                + ";EV_EBITDA=" + (row.getEvEbitda() != null ? "AVAILABLE" : row.getEvEbitdaReason());
    }

    private String truncateSummary(String value) {
        return value.length() <= 3000 ? value : value.substring(0, 3000);
    }

    private static final class ArrayListSet extends LinkedHashSet<Flow> {}
}
