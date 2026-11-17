package com.ykm.stock.analytics.service.valuation;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto.FinancialValue;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
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
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.MarketMultiples;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.ReportValues;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.Flow;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.Flows;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.PeriodAlignment;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.PeriodWindow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.stream.Collectors;

/**
 * 財季分母整股重建、最新估值快照刷新與歷史交易日即時估值服務。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockValuationService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final LocalDate PERIOD_REBUILD_START = LocalDate.of(2014, 1, 1);
    private static final BigDecimal TWO_PERCENT = new BigDecimal("0.02");
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");
    private static final Set<String> US_DOMESTIC_GAAP_FORMS = Set.of("10-K", "10-K/A", "10-KT", "10-KT/A", "10-Q", "10-Q/A");
    private static final Set<String> PERIODIC_SHARE_FORMS = Set.of("10-K", "10-K/A", "10-KT", "10-KT/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");
    private static final int REBUILD_BATCH_SIZE = 40;
    private static final int LATEST_BATCH_SIZE = 100;
    private static final int MIN_HISTORICAL_SAMPLES = 6;
    private static final int MIN_PEER_ISSUERS = 5;

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
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final SecIssuerHistory issuerHistory;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    private final FinancialReportValuationCalculator calculator = new FinancialReportValuationCalculator();
    private final SecFinancialFactsCalculator secCalculator = new SecFinancialFactsCalculator();

    private record ReportObservationKey(int statementType, LocalDate periodEnd, int periodType) {}

    private record IndustryModelContext(String industryCode, String modelCode, String configuredPrimaryMetric,
                                        BigDecimal assumedMultipleLow, BigDecimal assumedMultipleHigh,
                                        boolean financialInstitution) {}

    private record ShareEvaluation(BigDecimal marketCap, BigDecimal sharesAtDay, String marketCapSource,
                                   Instant sharesObservedAt, String sharesReason) {}

    private record DayDenominators(BigDecimal epsAtDay, BigDecimal sharesAtDay) {}

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

    /** 供個股分析報告讀取指定交易日即時估值、當期 14 項財務原料與 5 年月末序列（純唯讀，不寫庫）。 */
    public record ReportValuationBundle(ValuationResultDto valuation, FinancialsDto financials, List<ValuationResultDto> history) {}

    @Transactional(readOnly = true)
    public ReportValuationBundle evaluateForReport(Stock stock, LocalDate day, Instant cutoff, Instant supplementalCutoff, String industryCode) {
        String figi = stock.getFigi();
        List<StockFinancialPeriod> periods = financialPeriods.findByFigiOrderByPeriodEndAsc(figi);
        Optional<StockValuationLatest> storedLatest = latestValuations.findById(figi);
        if (periods.isEmpty() && storedLatest.isEmpty()) {
            return new ReportValuationBundle(null, null, List.of());
        }

        Instant now = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(now);
        LocalDate historyFrom = day.minusYears(5);
        List<StockQuote> quoteRows = quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(figi, historyFrom, day);
        Map<LocalDate, StockQuote> quoteByDate = new LinkedHashMap<>();
        for (StockQuote q : quoteRows) quoteByDate.put(q.getQuoteDate(), q);
        StockQuote targetQuote = quoteByDate.get(day);
        if (targetQuote == null) {
            targetQuote = quotes.findByFigiAndQuoteDate(figi, day);
            if (targetQuote != null) quoteByDate.put(day, targetQuote);
        }

        StockStatus status = statuses.findById(figi).orElse(null);
        LocalDate effectiveLatest = effectiveLatestQuoteDate(status, lastClosed);
        boolean defaultLatestRequest = supplementalCutoff != null && supplementalCutoff.isAfter(cutoff);
        if (defaultLatestRequest && targetQuote == null && day.equals(lastClosed)
                && effectiveLatest.isBefore(day) && quoteByDate.containsKey(effectiveLatest)) {
            day = effectiveLatest;
            cutoff = calendar.getSessionCloseAt(day);
            targetQuote = quoteByDate.get(day);
        }
        LocalDate latestSessionForRequest = defaultLatestRequest ? effectiveLatest : lastClosed;
        List<StockSplit> stockSplits = splits.findByFigiInOrderByExecutionDateAsc(List.of(figi));
        String cik = resolveCik(stock, day);
        boolean multiSecurityIssuer = cik != null && !stocks.findMultiSecurityIssuers(List.of(cik), ActiveStatus.ACTIVE).isEmpty();
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
        boolean verifiedOneToOne = multiSecurityIssuer && isMultiClassVerifiedOneToOne(figi, cik, latestObs, stockSplits, periods, now);
        IndustryModelContext modelCtx = resolveIndustryModel(stock, industryCode, cutoff, now);

        StockFinancialPeriod matchedPeriod = matchPeriod(periods, day);
        StockShareObservation primaryObs = closingSnap != null ? closingSnap : cutoffObs;
        ValuationResultDto dayValuation;
        if (periods.isEmpty() && storedLatest.isPresent() && day.equals(storedLatest.get().getValuationDate())) {
            dayValuation = ValuationResultDto.fromLatest(storedLatest.get());
        } else {
            dayValuation = evaluateSingleDay(stock, status, targetQuote, matchedPeriod, stockSplits,
                    primaryObs, cutoffObs,
                    multiSecurityIssuer, verifiedOneToOne, modelCtx, day, cutoff, now, latestSessionForRequest);
        }

        FinancialsDto financialsDto = matchedPeriod == null ? null
                : toFinancialsDto(matchedPeriod, stockSplits, day, latestSessionForRequest, cutoffObs, multiSecurityIssuer, verifiedOneToOne, status);

        List<ValuationResultDto> monthlyHistory = buildHistoryFromLoaded(
                stock, status, quoteByDate, periods, stockSplits, closingSnap, cutoffObs,
                multiSecurityIssuer, verifiedOneToOne, modelCtx, latestSessionForRequest, now, true);

        boolean historicalDate = day.isBefore(latestSessionForRequest);
        boolean financialInstitution = modelCtx != null && modelCtx.financialInstitution();
        String resolvedModelCode = modelCtx != null ? modelCtx.modelCode()
                : dayValuation.modelCode() != null ? dayValuation.modelCode() : IndustryValuationProposalService.MODEL_NON_FINANCIAL;
        String selectedMetric = selectPrimaryMetricForDay(modelCtx, dayValuation.metrics(), matchedPeriod);
        DayDenominators denoms = resolveDayDenominators(stock, status, targetQuote, matchedPeriod, stockSplits,
                primaryObs, cutoffObs, multiSecurityIssuer, verifiedOneToOne, day, cutoff, now, latestSessionForRequest);
        String resolvedIndustryCode = modelCtx != null && modelCtx.industryCode() != null ? modelCtx.industryCode() : industryCode;

        ValuationReferenceDto historicalRef = buildHistoricalReference(
                selectedMetric, monthlyHistory, matchedPeriod, denoms, stock.getCurrency(), financialInstitution, day);
        ValuationReferenceDto peerRef = buildPeerReference(
                selectedMetric, resolvedIndustryCode, day, historicalDate, matchedPeriod, denoms, stock.getCurrency(), financialInstitution);
        ValuationReferenceDto assumptionRef = buildIndustryAssumption(
                selectedMetric, modelCtx, resolvedIndustryCode, historicalDate, matchedPeriod, denoms, stock.getCurrency());

        ValuationResultDto enrichedValuation = dayValuation.withReferences(
                resolvedModelCode, selectedMetric, historicalRef, peerRef, assumptionRef);

        return new ReportValuationBundle(enrichedValuation, financialsDto, monthlyHistory);
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

    /** 整股重建指定股票自 2014 年起的所有標準化財季分母列（每股約 40 列）。 */
    public void rebuildFinancialPeriods(Collection<String> figis) {
        if (figis == null || figis.isEmpty()) return;
        List<String> orderedFigis = figis.stream().filter(Objects::nonNull).distinct().toList();
        Instant calculatedAt = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(calculatedAt);

        for (List<String> batchFigis : Lists.partition(orderedFigis, REBUILD_BATCH_SIZE)) {
            List<Stock> batchStocks = stocks.findAllById(batchFigis).stream()
                    .filter(s -> SecurityTypePolicy.isOperatingCompanyType(s.getStockType()))
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
                            .collect(Collectors.groupingBy(SecFinancialFact::getCik));
            Set<String> multiSecurityIssuers = ciks.isEmpty() ? Set.of()
                    : stocks.findMultiSecurityIssuers(ciks, ActiveStatus.ACTIVE);
            Map<String, StockShareObservation> latestObsByFigi = shareObservations.findLatestAt(activeFigis, calculatedAt).stream()
                    .collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
            Map<String, IndustryModelContext> modelsByFigi = resolveBatchIndustryModels(batchStocks, calendar.getSessionCloseAt(lastClosed), calculatedAt);

            Map<String, List<StockFinancialPeriod>> builtByFigi = new LinkedHashMap<>();
            for (Stock stock : batchStocks) {
                String figi = stock.getFigi();
                String cik = stock.getCik() != null && !stock.getCik().isBlank() ? stock.getCik() : null;
                List<SecFinancialFact> rawCikFacts = collectStockSecFacts(stock, factsByCik);
                List<StockSplit> stockSplits = splitsByFigi.getOrDefault(figi, List.of());
                boolean multiClass = cik != null && multiSecurityIssuers.contains(cik);
                boolean verifiedOneToOne = multiClass && isMultiClassVerifiedFromRawFacts(
                        latestObsByFigi.get(figi), rawCikFacts, stockSplits, calculatedAt);
                IndustryModelContext modelCtx = modelsByFigi.get(figi);
                List<StockFinancialPeriod> built = buildPeriodsForStock(
                        stock, statusMap.get(figi), reportsByFigi.getOrDefault(figi, List.of()),
                        rawCikFacts, stockSplits, latestObsByFigi.get(figi),
                        multiClass, verifiedOneToOne, modelCtx != null && modelCtx.financialInstitution(),
                        lastClosed, calculatedAt);
                builtByFigi.put(figi, built);
            }

            transactions.executeWithoutResult(tx -> {
                financialPeriods.deleteByFigiIn(builtByFigi.keySet());
                financialPeriods.flush();
                for (List<StockFinancialPeriod> rows : builtByFigi.values()) {
                    for (StockFinancialPeriod row : rows) {
                        entityManager.persist(row);
                    }
                }
                entityManager.flush();
            });
            log.info("Stock financial periods rebuilt | stocks={} rows={}",
                    builtByFigi.size(), builtByFigi.values().stream().mapToInt(List::size).sum());
        }
    }

    /** 批次刷新指定股票的最新估值快照（requestedDate 為 null 時按各股最新已入庫收市行情日，避免行情尚未同步時沖掉既有有效快照）。 */
    public void refreshLatestValuations(Collection<String> figis, LocalDate requestedDate) {
        if (figis == null || figis.isEmpty()) return;
        List<String> orderedFigis = figis.stream().filter(Objects::nonNull).distinct().toList();
        Instant calculatedAt = dateTimeService.now();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(calculatedAt);

        for (List<String> batchFigis : Lists.partition(orderedFigis, LATEST_BATCH_SIZE)) {
            List<Stock> batchStocks = stocks.findAllById(batchFigis).stream()
                    .filter(s -> s.isTracked() && s.getActiveStatus() == ActiveStatus.ACTIVE
                            && SecurityTypePolicy.isOperatingCompanyType(s.getStockType()))
                    .toList();
            if (batchStocks.isEmpty()) continue;

            List<String> activeFigis = batchStocks.stream().map(Stock::getFigi).toList();
            Map<String, StockStatus> statusPreview = statuses.findAllById(activeFigis).stream()
                    .collect(Collectors.toMap(StockStatus::getFigi, row -> row));
            Map<String, LocalDate> dateByFigi = new HashMap<>();
            Set<LocalDate> distinctDates = new LinkedHashSet<>();
            for (String figi : activeFigis) {
                LocalDate targetDay = requestedDate != null ? requestedDate : effectiveLatestQuoteDate(statusPreview.get(figi), lastClosed);
                dateByFigi.put(figi, targetDay);
                distinctDates.add(targetDay);
            }

            Map<String, Map<LocalDate, StockQuote>> quotesByFigiAndDate = new HashMap<>();
            Map<LocalDate, Map<String, StockShareObservation>> closingSnapsByDate = new HashMap<>();
            for (LocalDate d : distinctDates) {
                Instant dClose = calendar.getSessionCloseAt(d);
                for (StockQuote q : quotes.findStoredQuotes(activeFigis, d)) {
                    quotesByFigiAndDate.computeIfAbsent(q.getFigi(), k -> new HashMap<>()).put(d, q);
                }
                Map<String, StockShareObservation> snaps = shareObservations
                        .findLatestClosingSnapshotAt(activeFigis, calendar.getSessionOpenAt(d), dClose, calculatedAt)
                        .stream().collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
                closingSnapsByDate.put(d, snaps);
            }

            Map<String, List<StockFinancialPeriod>> periodsByFigi = financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(activeFigis)
                    .stream().collect(Collectors.groupingBy(StockFinancialPeriod::getFigi));
            Map<String, List<StockSplit>> splitsByFigi = splits.findByFigiInOrderByExecutionDateAsc(activeFigis).stream()
                    .collect(Collectors.groupingBy(StockSplit::getFigi));

            Set<String> ciks = batchStocks.stream().map(Stock::getCik).filter(Objects::nonNull)
                    .filter(c -> !c.isBlank()).collect(Collectors.toSet());
            Set<String> multiSecurityIssuers = ciks.isEmpty() ? Set.of()
                    : stocks.findMultiSecurityIssuers(ciks, ActiveStatus.ACTIVE);

            Map<String, StockShareObservation> latestObsMap = shareObservations.findLatestAt(activeFigis, calculatedAt).stream()
                    .collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
            Map<String, IndustryModelContext> modelsByFigi = resolveBatchIndustryModels(batchStocks, calendar.getSessionCloseAt(lastClosed), calculatedAt);

            transactions.executeWithoutResult(tx -> {
                Map<String, StockStatus> lockedStatuses = statuses.findAllByIdForUpdate(activeFigis).stream()
                        .collect(Collectors.toMap(StockStatus::getFigi, row -> row));
                Map<String, StockValuationLatest> existingLatest = latestValuations.findByFigiIn(activeFigis).stream()
                        .collect(Collectors.toMap(StockValuationLatest::getFigi, row -> row));

                List<StockStatus> updatedStatuses = new ArrayList<>();
                for (Stock stock : batchStocks) {
                    String figi = stock.getFigi();
                    StockStatus status = lockedStatuses.get(figi);
                    if (status == null) continue;
                    LocalDate stockValDate = dateByFigi.getOrDefault(figi, lastClosed);
                    LocalDate stockEffectiveLatest = effectiveLatestQuoteDate(status, lastClosed);
                    Instant stockCutoff = calendar.getSessionCloseAt(stockValDate);
                    StockQuote stockQuote = quotesByFigiAndDate.getOrDefault(figi, Map.of()).get(stockValDate);

                    List<StockFinancialPeriod> stockPeriods = periodsByFigi.getOrDefault(figi, List.of());
                    StockFinancialPeriod matched = matchPeriod(stockPeriods, stockValDate);
                    List<StockSplit> stockSplits = splitsByFigi.getOrDefault(figi, List.of());
                    String cik = resolveCik(stock, stockValDate);
                    boolean multiClass = cik != null && multiSecurityIssuers.contains(cik);
                    StockShareObservation latestObs = latestObsMap.get(figi);
                    StockShareObservation closingSnap = closingSnapsByDate.getOrDefault(stockValDate, Map.of()).get(figi);
                    StockShareObservation primaryObs = closingSnap != null ? closingSnap : latestObs;
                    boolean verifiedOneToOne = multiClass && isMultiClassVerifiedOneToOne(
                            figi, cik, latestObs, stockSplits, stockPeriods, calculatedAt);

                    ValuationResultDto evaluated = evaluateSingleDay(
                            stock, status, stockQuote, matched, stockSplits,
                            primaryObs, latestObs, multiClass, verifiedOneToOne,
                            modelsByFigi.get(figi), stockValDate, stockCutoff, calculatedAt, stockEffectiveLatest);

                    StockValuationLatest target = existingLatest.getOrDefault(figi, new StockValuationLatest());
                    populateLatestEntity(target, evaluated);
                    if (existingLatest.containsKey(figi)) {
                        entityManager.merge(target);
                    } else {
                        entityManager.persist(target);
                    }

                    if (target.hasAvailableMetric() && (status.getValuationDate() == null || !stockValDate.isBefore(status.getValuationDate()))) {
                        status.setValuationDate(stockValDate);
                    }
                    status.setValuationMessage(truncateSummary(stockValDate + ":" + formatStatusSummary(target)));
                    updatedStatuses.add(status);
                }
                entityManager.flush();
                statuses.saveAllAndFlush(updatedStatuses);
            });
        }
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
        boolean multiClass = cik != null && !stocks.findMultiSecurityIssuers(List.of(cik), ActiveStatus.ACTIVE).isEmpty();
        StockShareObservation latestObs = shareObservations
                .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", now).orElse(null);
        StockShareObservation closingSnap = null;
        if (!through.isBefore(effectiveLatest)) {
            List<StockShareObservation> snaps = shareObservations.findLatestClosingSnapshotAt(
                    List.of(figi), calendar.getSessionOpenAt(effectiveLatest), calendar.getSessionCloseAt(effectiveLatest), now);
            if (!snaps.isEmpty()) closingSnap = snaps.getFirst();
        }
        boolean verifiedOneToOne = multiClass && isMultiClassVerifiedOneToOne(figi, cik, latestObs, stockSplits, periods, now);
        Instant throughClose = calendar.getSessionCloseAt(through);
        String industryCode = industries.findAt(figi, throughClose).map(row -> row.getIndustry().getCode()).orElse(null);
        IndustryModelContext modelCtx = resolveIndustryModel(stock, industryCode, throughClose, now);

        return buildHistoryFromLoaded(stock, status, quoteByDate, periods, stockSplits,
                closingSnap, latestObs, multiClass, verifiedOneToOne, modelCtx, effectiveLatest, now, monthly);
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
            LocalDate lastClosed, Instant calculatedAt, boolean monthly) {
        if (quoteByDate.isEmpty()) return List.of();
        if (monthly) {
            Map<YearMonth, LocalDate> monthLastQuoteDays = new LinkedHashMap<>();
            for (LocalDate quoteDate : quoteByDate.keySet()) {
                monthLastQuoteDays.put(YearMonth.from(quoteDate), quoteDate);
            }
            List<ValuationResultDto> monthlyResults = new ArrayList<>(monthLastQuoteDays.size());
            for (LocalDate day : monthLastQuoteDays.values()) {
                StockQuote quote = quoteByDate.get(day);
                Instant dayCutoff = calendar.isTradingDay(day) ? calendar.getSessionCloseAt(day) : calculatedAt;
                StockFinancialPeriod matched = matchPeriod(periods, day);
                StockShareObservation primary = day.equals(lastClosed) && closingSnap != null ? closingSnap : fallbackObs;
                ValuationResultDto dto = evaluateSingleDay(stock, status, quote, matched, stockSplits,
                        primary, fallbackObs, multiClass, verifiedOneToOne, modelCtx, day, dayCutoff, calculatedAt, lastClosed);
                monthlyResults.add(dto);
            }
            return List.copyOf(monthlyResults);
        }

        List<ValuationResultDto> dailyResults = new ArrayList<>();
        for (Map.Entry<LocalDate, StockQuote> entry : quoteByDate.entrySet()) {
            LocalDate day = entry.getKey();
            StockQuote quote = entry.getValue();
            Instant dayCutoff = calendar.isTradingDay(day) ? calendar.getSessionCloseAt(day) : calculatedAt;
            StockFinancialPeriod matched = matchPeriod(periods, day);
            StockShareObservation primary = day.equals(lastClosed) && closingSnap != null ? closingSnap : fallbackObs;
            ValuationResultDto dto = evaluateSingleDay(stock, status, quote, matched, stockSplits,
                    primary, fallbackObs, multiClass, verifiedOneToOne, modelCtx, day, dayCutoff, calculatedAt, lastClosed);
            dailyResults.add(dto);
        }
        return List.copyOf(dailyResults);
    }

    /** 即時計算單一交易日 D 的五項估值倍數。 */
    private ValuationResultDto evaluateSingleDay(
            Stock stock, StockStatus status, StockQuote quote, StockFinancialPeriod period,
            List<StockSplit> stockSplits, StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne, IndustryModelContext modelCtx,
            LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed) {
        String figi = stock.getFigi();
        String quoteCurrency = stock.getCurrency();
        String industryCode = modelCtx == null ? null : modelCtx.industryCode();
        String modelCode = modelCtx == null ? IndustryValuationProposalService.MODEL_NON_FINANCIAL : modelCtx.modelCode();
        boolean financialInstitution = modelCtx != null && modelCtx.financialInstitution();

        if (quote == null || quote.getClosePrice() == null) {
            return unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, null, "CLOSING_PRICE_MISSING");
        }
        if (quote.getClosePrice().signum() <= 0) {
            return unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), "CLOSING_PRICE_INVALID");
        }
        if (period == null) {
            return unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        }

        String reportingCurrency = period.getReportingCurrency();
        boolean currencyMatch = sameCurrency(quoteCurrency, reportingCurrency);
        if (!currencyMatch) {
            return unavailableDayResult(figi, day, cutoff, calculatedAt, industryCode, modelCode, quoteCurrency, quote.getClosePrice(), "REPORTING_CURRENCY_MISMATCH");
        }

        String splitError = splitSyncError(status);
        // 1. 計算交易日 D 對應的 EPS 分母
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
                    epsAtDay = period.getEpsTtm().divide(factor, MC);
                    peReason = null;
                } else {
                    peReason = "EPS_SPLIT_BASIS_UNCONFIRMED";
                }
            }
        } else if (peReason == null) {
            peReason = "DILUTED_EPS_TTM_MISSING";
        }

        // 2. 計算交易日 D 的總市值
        ShareEvaluation shareEval = evaluateMarketCap(quote, period, stockSplits, status, primaryObs, fallbackObs,
                multiClass, verifiedOneToOne, day, cutoff, calculatedAt, lastClosed);
        BigDecimal marketCap = shareEval.marketCap();
        String sharesReason = shareEval.sharesReason();

        // 3. 組裝 EV 與五項倍數
        BigDecimal equityVal = MetricStatus.AVAILABLE.name().equals(period.getCommonEquityStatus()) ? period.getCommonEquity() : null;
        BigDecimal revenueVal = MetricStatus.AVAILABLE.name().equals(period.getRevenueTtmStatus()) ? period.getRevenueTtm() : null;
        BigDecimal fcfVal = MetricStatus.AVAILABLE.name().equals(period.getFreeCashFlowTtmStatus()) ? period.getFreeCashFlowTtm() : null;
        BigDecimal ebitdaVal = MetricStatus.AVAILABLE.name().equals(period.getEbitdaTtmStatus()) ? period.getEbitdaTtm() : null;
        BigDecimal evVal = MetricStatus.AVAILABLE.name().equals(period.getEvAdjustmentStatus())
                && period.getEvAdjustment() != null && marketCap != null
                ? marketCap.add(period.getEvAdjustment(), MC) : null;

        MarketMultiples multiples = calculator.calculate(new FinancialReportValuationCalculator.ValuationInputs(
                day, quote.getClosePrice(), quoteCurrency, reportingCurrency,
                marketCap, epsAtDay, equityVal, revenueVal, fcfVal, evVal, ebitdaVal));

        BigDecimal pe = multiples.pe().value();
        String resolvedPeReason = pe != null ? null : peReason != null ? peReason : reasonOf(multiples.pe());

        BigDecimal pb = multiples.pb().value();
        String pbCalcReason = reasonOf(multiples.pb());
        String resolvedPbReason = pb != null ? null
                : "NON_POSITIVE_COMMON_EQUITY".equals(pbCalcReason) ? pbCalcReason
                : period.getCommonEquityReason() != null && isInvalidatingReason(period.getCommonEquityReason()) ? period.getCommonEquityReason().name()
                : sharesReason != null ? sharesReason
                : period.getCommonEquityReason() != null ? period.getCommonEquityReason().name() : pbCalcReason;

        BigDecimal ps = multiples.ps().value();
        String psCalcReason = reasonOf(multiples.ps());
        String resolvedPsReason = ps != null ? null
                : "NON_POSITIVE_REVENUE".equals(psCalcReason) ? psCalcReason
                : period.getRevenueTtmReason() != null && isInvalidatingReason(period.getRevenueTtmReason()) ? period.getRevenueTtmReason().name()
                : sharesReason != null ? sharesReason
                : period.getRevenueTtmReason() != null ? period.getRevenueTtmReason().name() : psCalcReason;

        BigDecimal pfcf = financialInstitution ? null : multiples.pfcf().value();
        String pfcfCalcReason = reasonOf(multiples.pfcf());
        String resolvedPfcfReason = financialInstitution ? "PFCF_NOT_APPLICABLE_TO_INDUSTRY"
                : pfcf != null ? null
                : "NON_POSITIVE_FCF".equals(pfcfCalcReason) ? pfcfCalcReason
                : period.getFreeCashFlowTtmReason() != null && isInvalidatingReason(period.getFreeCashFlowTtmReason()) ? period.getFreeCashFlowTtmReason().name()
                : sharesReason != null ? sharesReason
                : period.getFreeCashFlowTtmReason() != null ? period.getFreeCashFlowTtmReason().name() : pfcfCalcReason;

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
                ValuationResultDto.financialValue(pe, resolvedPeReason, period.getEpsTtmSource(), period.getEpsTtmPeriodStart(), period.getPeriodEnd()),
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
                null
        );
    }

    private ShareEvaluation evaluateMarketCap(
            StockQuote quote, StockFinancialPeriod period, List<StockSplit> stockSplits, StockStatus status,
            StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne,
            LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed) {
        String splitError = splitSyncError(status);
        BigDecimal price = quote.getClosePrice();

        if (multiClass) {
            if (day.equals(lastClosed)) {
                if (sameCloseSnapshot(primaryObs, quote, day, cutoff, calculatedAt)) {
                    BigDecimal mktCap = primaryObs.effectiveMarketCap();
                    BigDecimal eqShares = mktCap.divide(price, MC);
                    return new ShareEvaluation(mktCap, eqShares, "FUTU_CLOSE_SNAPSHOT", primaryObs.getObservedAt(), null);
                }
                StockShareObservation obs = primaryObs != null ? primaryObs : fallbackObs;
                if (obs != null) {
                    LocalDate basis = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
                    if (splitError != null && !basis.equals(day)) {
                        return new ShareEvaluation(null, null, null, null, splitError);
                    }
                    BigDecimal equivShares = obs.effectiveMarketCap() != null && obs.getSnapshotPrice() != null && obs.getSnapshotPrice().signum() > 0
                            ? obs.effectiveMarketCap().divide(obs.getSnapshotPrice(), MC)
                            : BigDecimal.valueOf(obs.effectiveShares());
                    BigDecimal sharesAtDay = equivShares.multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
                    return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, "OBSERVED_SHARES", obs.getObservedAt(), null);
                }
                if (verifiedOneToOne && period.getSharesOutstanding() != null && period.getSharesBasisDate() != null) {
                    if (splitError != null && !period.getSharesBasisDate().equals(day)) {
                        return new ShareEvaluation(null, null, null, null, splitError);
                    }
                    BigDecimal sharesAtDay = period.getSharesOutstanding()
                            .multiply(StockSplitCalculator.shareFactor(stockSplits, period.getSharesBasisDate(), day), MC);
                    return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, "SEC_FILING_SHARES",
                            period.getSharesOutstandingAvailableAt(), null);
                }
                return new ShareEvaluation(null, null, null, null, "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED");
            }
            if (!verifiedOneToOne) {
                return new ShareEvaluation(null, null, null, null, "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED");
            }
            if (period.getSharesOutstanding() != null && period.getSharesBasisDate() != null) {
                if (splitError != null && !period.getSharesBasisDate().equals(day)) {
                    return new ShareEvaluation(null, null, null, null, splitError);
                }
                BigDecimal sharesAtDay = period.getSharesOutstanding()
                        .multiply(StockSplitCalculator.shareFactor(stockSplits, period.getSharesBasisDate(), day), MC);
                String src = period.getSecShareAccession() != null ? "SEC_FILING_SHARES" : "OBSERVED_SHARES";
                return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, src,
                        period.getSharesOutstandingAvailableAt(), null);
            }
            if (fallbackObs != null) {
                LocalDate basis = fallbackObs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
                if (splitError != null && !basis.equals(day)) {
                    return new ShareEvaluation(null, null, null, null, splitError);
                }
                BigDecimal sharesAtDay = BigDecimal.valueOf(fallbackObs.effectiveShares())
                        .multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
                return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, "OBSERVED_SHARES", fallbackObs.getObservedAt(), null);
            }
            return new ShareEvaluation(null, null, null, null, "VALUATION_DATE_SHARES_MISSING");
        }

        // 單一普通股
        if (day.equals(lastClosed)) {
            if (sameCloseSnapshot(primaryObs, quote, day, cutoff, calculatedAt)) {
                BigDecimal mktCap = primaryObs.effectiveMarketCap();
                BigDecimal sharesAtDay = mktCap.divide(price, MC);
                return new ShareEvaluation(mktCap, sharesAtDay, "FUTU_CLOSE_SNAPSHOT", primaryObs.getObservedAt(), null);
            }
            StockShareObservation obs = primaryObs != null ? primaryObs : fallbackObs;
            if (obs != null) {
                LocalDate basis = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
                if (splitError != null && !basis.equals(day)) {
                    return new ShareEvaluation(null, null, null, null, splitError);
                }
                BigDecimal sharesAtDay = BigDecimal.valueOf(obs.effectiveShares())
                        .multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
                return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, "OBSERVED_SHARES", obs.getObservedAt(), null);
            }
        }
        if (period.getSharesOutstanding() != null && period.getSharesBasisDate() != null) {
            if (splitError != null && !period.getSharesBasisDate().equals(day)) {
                return new ShareEvaluation(null, null, null, null, splitError);
            }
            BigDecimal sharesAtDay = period.getSharesOutstanding()
                    .multiply(StockSplitCalculator.shareFactor(stockSplits, period.getSharesBasisDate(), day), MC);
            String src = period.getSecShareAccession() != null ? "SEC_FILING_SHARES" : "OBSERVED_SHARES";
            return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, src,
                    period.getSharesOutstandingAvailableAt(), null);
        }
        if (fallbackObs != null) {
            LocalDate basis = fallbackObs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            if (splitError != null && !basis.equals(day)) {
                return new ShareEvaluation(null, null, null, null, splitError);
            }
            BigDecimal sharesAtDay = BigDecimal.valueOf(fallbackObs.effectiveShares())
                    .multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
            return new ShareEvaluation(price.multiply(sharesAtDay, MC), sharesAtDay, "OBSERVED_SHARES", fallbackObs.getObservedAt(), null);
        }
        String fallbackReason = period.getSharesOutstandingReason() != null
                ? period.getSharesOutstandingReason().name() : "VALUATION_DATE_SHARES_MISSING";
        return new ShareEvaluation(null, null, null, null, fallbackReason);
    }

    /** 將配對財季分母轉為前端 FinancialsPanel 所需的 14 項財務指標，並按所選交易日對齊每股指標拆股基準。 */
    private FinancialsDto toFinancialsDto(
            StockFinancialPeriod p, List<StockSplit> stockSplits, LocalDate day, LocalDate lastClosed,
            StockShareObservation obs, boolean multiClass, boolean verifiedOneToOne, StockStatus status) {
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

        BigDecimal sharesAtDay = null;
        Instant sharesAvailAt = p.getSharesOutstandingAvailableAt();
        String sharesCalc = p.getSharesOutstandingCalculation();
        String sharesStatus = p.getSharesOutstandingStatus();
        String sharesReason = p.getSharesOutstandingReason() == null ? null : p.getSharesOutstandingReason().name();

        if (multiClass && !verifiedOneToOne && !day.equals(lastClosed)) {
            sharesStatus = MetricStatus.UNVERIFIED.name();
            sharesReason = ValuationReason.SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED.name();
        } else if (day.equals(lastClosed) && obs != null) {
            LocalDate basis = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            BigDecimal rawShares = multiClass && obs.effectiveMarketCap() != null && obs.getSnapshotPrice() != null && obs.getSnapshotPrice().signum() > 0
                    ? obs.effectiveMarketCap().divide(obs.getSnapshotPrice(), MC)
                    : BigDecimal.valueOf(obs.effectiveShares());
            sharesAtDay = rawShares.multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
            sharesAvailAt = obs.getObservedAt();
            boolean hasDirectOutstanding = obs.getOutstandingShares() != null && obs.getOutstandingShares() > 0;
            sharesCalc = multiClass
                    ? (obs.getOutstandingMarketCap() != null && obs.getOutstandingMarketCap().signum() > 0
                            ? "富途最新等值流通股數基準" : "富途最新等值股數基準（已發行市值備援）")
                    : (hasDirectOutstanding ? "富途最新流通在外股數" : "富途最新已發行股數（舊快照備援）");
        } else if (p.getSharesOutstanding() != null && MetricStatus.AVAILABLE.name().equals(p.getSharesOutstandingStatus()) && p.getSharesBasisDate() != null) {
            if (splitError == null || p.getSharesBasisDate().equals(day)) {
                sharesAtDay = p.getSharesOutstanding().multiply(StockSplitCalculator.shareFactor(stockSplits, p.getSharesBasisDate(), day), MC);
            } else {
                sharesStatus = MetricStatus.INSUFFICIENT_DATA.name();
                sharesReason = splitError;
            }
        }

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

            String reportingCurrency = firstNonBlank(
                    income != null ? income.currency() : null,
                    balance != null ? balance.currency() : null,
                    cashFlow != null ? cashFlow.currency() : null,
                    latestReportCurrency(reportsUpToP),
                    stock.getCurrency());

            Flows secFlows = factsUpToP.isEmpty() || reportingCurrency == null ? null
                    : secCalculator.calculateSelectedFacts(factsUpToP, periodCik, stock.getSic(), reportingCurrency,
                            periodEnd, calculatedAt, reportsUpToP);

            StockFinancialPeriod row = assemblePeriodRow(
                    stock, status, window, reportsUpToP, income, balance, cashFlow,
                    secFlows, periodCikFacts, rawSecFacts, futuObservedMap, stockSplits, latestObs,
                    multiClass, verifiedOneToOne, financialInstitution,
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
            boolean multiClass, boolean verifiedOneToOne, boolean financialInstitution,
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
        LocalDate ttmStart = resolveFutuTtmStart(reportsWithSecMissing, figi, 1, periodEnd, window.periodStart(), secAnnualIncStart);
        LocalDate cfTtmStart = resolveFutuTtmStart(reportsWithSecMissing, figi, 3, periodEnd, window.periodStart(), secAnnualCfStart);

        ReportValues effectiveIncome = effectiveFlowStatement(income, secFlows, 1, periodEnd);
        ReportValues effectiveCashFlow = effectiveFlowStatement(cashFlow, secFlows, 3, periodEnd);
        Set<Flow> usedSecFlows = new ArrayListSet();

        // 1. TTM 營業收入
        ResolvedFlow rev = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.REVENUE_AS_REPORTED,
                secRevAtEnd, secFlows, reportingCurrency, ttmStart, incomeObs,
                "Futu 營業收入 TTM", "SEC 營業收入 TTM", usedSecFlows);
        row.setRevenueTtm(rev.value());
        row.setRevenueTtmStatus(rev.status());
        row.setRevenueTtmReason(rev.reason());
        row.setRevenueTtmSource(rev.source());
        row.setRevenueTtmCalculation(rev.calculation());
        row.setRevenueTtmPeriodStart(rev.periodStart());
        row.setRevenueTtmAvailableAt(rev.availableAt());

        // 2. TTM 普通股淨利
        Flow secNetIncome = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.NET_INCOME_COMMON), periodEnd);
        ResolvedFlow netInc = resolveTrailingFlow(reportsUpToP, effectiveIncome, FinancialConcept.NET_INCOME_COMMON,
                secNetIncome, secFlows, reportingCurrency, ttmStart, incomeObs,
                "Futu 普通股淨利 TTM", "SEC 普通股淨利 TTM", usedSecFlows);
        row.setNetIncomeCommonTtm(netInc.value());
        row.setNetIncomeCommonTtmStatus(netInc.status());
        row.setNetIncomeCommonTtmReason(netInc.reason());
        row.setNetIncomeCommonTtmSource(netInc.source());
        row.setNetIncomeCommonTtmCalculation(netInc.calculation());
        row.setNetIncomeCommonTtmPeriodStart(netInc.periodStart());
        row.setNetIncomeCommonTtmAvailableAt(netInc.availableAt());

        // 3. TTM 稀釋 EPS 與拆股基準驗證
        populateEpsTtm(row, reportsUpToP, effectiveIncome, secFlows, allStockSecFacts, stockSplits,
                multiClass, verifiedOneToOne, reportingCurrency, periodEnd, ttmStart, incomeObs, latestShareBasisDate, calculatedAt, usedSecFlows);

        // 4. TTM 營運現金流
        ResolvedFlow cfo = resolveTrailingFlow(reportsUpToP, effectiveCashFlow, FinancialConcept.OPERATING_CASH_FLOW,
                secCfoAtEnd, secFlows, reportingCurrency, cfTtmStart, cashFlowObs,
                "Futu 營運現金流 TTM", "SEC 營運現金流 TTM", usedSecFlows);
        row.setOperatingCashFlowTtm(cfo.value());
        row.setOperatingCashFlowTtmStatus(cfo.status());
        row.setOperatingCashFlowTtmReason(cfo.reason());
        row.setOperatingCashFlowTtmSource(cfo.source());
        row.setOperatingCashFlowTtmCalculation(cfo.calculation());
        row.setOperatingCashFlowTtmPeriodStart(cfo.periodStart());
        row.setOperatingCashFlowTtmAvailableAt(cfo.availableAt());

        // 5. TTM 資本支出
        Flow secCapex = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.CAPITAL_EXPENDITURE), periodEnd);
        ResolvedFlow capex = resolveTrailingFlow(reportsUpToP, effectiveCashFlow, FinancialConcept.CAPITAL_EXPENDITURE,
                secCapex, secFlows, reportingCurrency, cfTtmStart, cashFlowObs,
                "Futu 資本支出 TTM", "SEC 完整資本支出 TTM", usedSecFlows);
        row.setCapitalExpenditureTtm(capex.value());
        row.setCapitalExpenditureTtmStatus(capex.status());
        row.setCapitalExpenditureTtmReason(capex.reason());
        row.setCapitalExpenditureTtmSource(capex.source());
        row.setCapitalExpenditureTtmCalculation(capex.calculation());
        row.setCapitalExpenditureTtmPeriodStart(capex.periodStart());
        row.setCapitalExpenditureTtmAvailableAt(capex.availableAt());

        // 6. TTM 自由現金流
        populateFreeCashFlow(row, reportsUpToP, effectiveCashFlow, secFlows, financialInstitution, reportingCurrency, periodEnd, cfTtmStart, cashFlowObs, usedSecFlows);

        // 7. TTM 營運 EBITDA
        populateEbitda(row, reportsUpToP, effectiveIncome, secFlows, financialInstitution, reportingCurrency, periodEnd, ttmStart, incomeObs, cashFlowObs, usedSecFlows);

        // 8 & 11-14 & EV Adjustment：資產負債表項目
        populateBalanceAndEv(row, balance, secFlows, financialInstitution, reportingCurrency, periodEnd, balanceObs, usedSecFlows);

        // 9 & 10：期末流通股數基準與每股帳面值
        populateSharesAndBookValue(row, periodCikFacts, latestObs, stockSplits, multiClass, verifiedOneToOne, status, periodEnd, calculatedAt);

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
        if (!usedSecFlows.isEmpty() && periodCik != null) {
            row.setSecCik(periodCik);
        }
        return row;
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
            StockFinancialPeriod row, List<ReportValues> reportsUpToP, ReportValues income,
            Flows secFlows, List<SecFinancialFact> allStockSecFacts, List<StockSplit> stockSplits,
            boolean multiClass, boolean verifiedOneToOne, String currency, LocalDate periodEnd, LocalDate futuStart, Instant incomeObs,
            LocalDate latestShareBasisDate, Instant calculatedAt, Set<Flow> usedSecFlows) {
        String figi = row.getFigi();
        boolean allowSecEps = !multiClass || verifiedOneToOne;
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
        if (futuEps != null && futuEps.value() != null && epsPeriod != null) {
            if (!hasSplitAfter(stockSplits, epsBasisFrom, latestShareBasisDate)) {
                resolvedBasisDate = latestShareBasisDate;
            } else if (verifyFutuEpsAtBasis(epsSourceReports, epsPeriod, allStockSecFacts, currency, stockSplits, latestShareBasisDate, calculatedAt)) {
                resolvedBasisDate = latestShareBasisDate;
            } else if (!hasSplitBetween(stockSplits, epsBasisFrom, epsPeriod.periodEnd())
                    && verifyFutuEpsAtBasis(epsSourceReports, epsPeriod, allStockSecFacts, currency, stockSplits, epsPeriod.periodEnd(), calculatedAt)) {
                resolvedBasisDate = epsPeriod.periodEnd();
            } else {
                futuEps = unverifiedMetric("EPS_SPLIT_BASIS_UNCONFIRMED");
                mixedEps.clear();
            }
        }

        boolean completeSecInstead = !mixedEps.isEmpty() && secEps != null && secEps.value() != null
                && epsPeriod != null && epsPeriod.periodEnd().equals(secEps.periodEnd());
        boolean useSec = allowSecEps && (completeSecInstead || preferSec(secEps, futuEps, epsPeriod));
        if (useSec) {
            LocalDate secBasisDate = resolveSecEpsBasisDate(secEps, allStockSecFacts, currency, stockSplits, latestShareBasisDate, calculatedAt);
            if (secEps.value() != null && secBasisDate != null) {
                usedSecFlows.add(secEps);
                row.setEpsTtm(secEps.value());
                row.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
                row.setEpsTtmSource("SEC");
                row.setEpsTtmCalculation("SEC 稀釋 EPS TTM");
                row.setEpsTtmPeriodStart(secEps.periodStart());
                row.setEpsTtmAvailableAt(secEps.observedAt());
                row.setEpsShareBasisDate(secBasisDate);
                return;
            }
            String secFail = secEps.value() != null ? "EPS_SPLIT_BASIS_UNCONFIRMED" : secEps.reason();
            ValuationReason reason = ValuationReason.fromCode(secFail);
            row.setEpsTtmStatus(reason == null ? MetricStatus.INSUFFICIENT_DATA.name() : reason.statusName());
            row.setEpsTtmReason(reason);
            row.setEpsTtmSource("SEC");
            row.setEpsTtmPeriodStart(secEps.periodStart());
            row.setEpsTtmAvailableAt(secEps.observedAt());
            return;
        }

        if (futuEps != null && futuEps.value() != null && resolvedBasisDate != null) {
            usedSecFlows.addAll(mixedEps);
            String src = mixedEps.isEmpty() ? "FUTU" : "FUTU_AND_SEC";
            String calc = epsPeriod != null && epsPeriod.periodType() == 7 ? "Futu 直接申報年度稀釋 EPS" : "Futu 四季稀釋 EPS 之和";
            Instant avail = mixedEps.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(incomeObs, this::laterInstant);
            row.setEpsTtm(futuEps.value());
            row.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
            row.setEpsTtmSource(src);
            row.setEpsTtmCalculation(calc);
            row.setEpsTtmPeriodStart(futuStart);
            row.setEpsTtmAvailableAt(avail);
            row.setEpsShareBasisDate(resolvedBasisDate);
            return;
        }

        String failCode;
        if (!allowSecEps && (futuEps == null || futuEps.value() == null) && issuerEps != null && issuerEps.periodEnd() != null) {
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

    private void populateFreeCashFlow(
            StockFinancialPeriod row, List<ReportValues> reportsUpToP, ReportValues cashFlow,
            Flows secFlows, boolean financialInstitution, String currency, LocalDate periodEnd, LocalDate cfStart, Instant cfObs, Set<Flow> usedSecFlows) {
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
        boolean useSec = preferSec(secFcf, futuFcf, cashFlow);
        if (useSec) {
            usedSecFlows.add(secFcf);
            if (secFcf.value() != null) {
                row.setFreeCashFlowTtm(secFcf.value());
                row.setFreeCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
                row.setFreeCashFlowTtmSource("SEC");
                row.setFreeCashFlowTtmCalculation(secFcf.note() != null ? "SEC " + secFcf.note() : "SEC CFO - CapEx");
                row.setFreeCashFlowTtmPeriodStart(secFcf.periodStart());
                row.setFreeCashFlowTtmAvailableAt(secFcf.observedAt());
                return;
            }
            ValuationReason reason = financialInstitution ? ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY : ValuationReason.fromCode(secFcf.reason());
            row.setFreeCashFlowTtmStatus(reason.statusName());
            row.setFreeCashFlowTtmReason(reason);
            row.setFreeCashFlowTtmSource("SEC");
            row.setFreeCashFlowTtmPeriodStart(secFcf.periodStart());
            row.setFreeCashFlowTtmAvailableAt(secFcf.observedAt());
            return;
        }
        if (futuFcf.value() != null) {
            usedSecFlows.addAll(mixedFcf);
            String src = mixedFcf.isEmpty() ? "FUTU" : "FUTU_AND_SEC";
            String calc = !mixedFcf.isEmpty() ? "Futu + SEC 營運現金流減資本支出 TTM"
                    : directFutuFcf ? "Futu 自由現金流 TTM" : "Futu 營運現金流減資本支出 TTM";
            Instant avail = mixedFcf.stream().map(Flow::observedAt).filter(Objects::nonNull).reduce(cfObs, this::laterInstant);
            row.setFreeCashFlowTtm(futuFcf.value());
            row.setFreeCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
            row.setFreeCashFlowTtmSource(src);
            row.setFreeCashFlowTtmCalculation(calc);
            row.setFreeCashFlowTtmPeriodStart(cfStart);
            row.setFreeCashFlowTtmAvailableAt(avail);
            return;
        }
        ValuationReason reason = financialInstitution ? ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY
                : ValuationReason.fromCode(hasReportReason(secFcf, cashFlow) ? secFcf.reason() : reasonOf(futuFcf));
        row.setFreeCashFlowTtmStatus(reason.statusName());
        row.setFreeCashFlowTtmReason(reason);
        row.setFreeCashFlowTtmSource("FUTU");
        row.setFreeCashFlowTtmPeriodStart(cfStart);
        row.setFreeCashFlowTtmAvailableAt(cfObs);
    }

    private void populateEbitda(
            StockFinancialPeriod row, List<ReportValues> reportsUpToP, ReportValues income,
            Flows secFlows, boolean financialInstitution, String currency, LocalDate periodEnd, LocalDate ttmStart,
            Instant incomeObs, Instant cfObs, Set<Flow> usedSecFlows) {
        if (financialInstitution) {
            row.setEbitdaTtmStatus(MetricStatus.NOT_APPLICABLE.name());
            row.setEbitdaTtmReason(ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY);
            row.setEbitdaTtmSource("FUTU");
            row.setEbitdaTtmCalculation("金融機構不適用營運 EBITDA");
            row.setEbitdaTtmPeriodStart(ttmStart);
            row.setEbitdaTtmAvailableAt(incomeObs);
            return;
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
        boolean useSec = preferSec(secEbitda, futuEbitda, income);
        if (useSec) {
            usedSecFlows.add(secEbitda);
            if (secEbitda.value() != null) {
                row.setEbitdaTtm(secEbitda.value());
                row.setEbitdaTtmStatus(MetricStatus.AVAILABLE.name());
                row.setEbitdaTtmSource("SEC");
                row.setEbitdaTtmCalculation(secEbitda.note() != null ? "SEC " + secEbitda.note() : "SEC OperatingIncomeLoss + DD&A");
                row.setEbitdaTtmPeriodStart(secEbitda.periodStart());
                row.setEbitdaTtmAvailableAt(secEbitda.observedAt());
                return;
            }
            ValuationReason reason = ValuationReason.fromCode(secEbitda.reason());
            row.setEbitdaTtmStatus(reason.statusName());
            row.setEbitdaTtmReason(reason);
            row.setEbitdaTtmSource("SEC");
            row.setEbitdaTtmPeriodStart(secEbitda.periodStart());
            row.setEbitdaTtmAvailableAt(secEbitda.observedAt());
            return;
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
            row.setEbitdaTtm(futuEbitda.value());
            row.setEbitdaTtmStatus(MetricStatus.AVAILABLE.name());
            row.setEbitdaTtmSource(src);
            row.setEbitdaTtmCalculation(calc);
            row.setEbitdaTtmPeriodStart(ttmStart);
            row.setEbitdaTtmAvailableAt(avail);
            return;
        }
        String fail = hasReportReason(secEbitda, income) ? secEbitda.reason() : reasonOf(futuEbitda);
        ValuationReason reason = ValuationReason.fromCode(fail);
        row.setEbitdaTtmStatus(reason.statusName());
        row.setEbitdaTtmReason(reason);
        row.setEbitdaTtmSource("FUTU");
        row.setEbitdaTtmPeriodStart(ttmStart);
        row.setEbitdaTtmAvailableAt(incomeObs);
    }

    private void populateBalanceAndEv(
            StockFinancialPeriod row, ReportValues balance, Flows secFlows,
            boolean financialInstitution, String currency, LocalDate periodEnd, Instant balanceObs, Set<Flow> usedSecFlows) {
        Map<FinancialConcept, Flow> secBalUsed = new EnumMap<>(FinancialConcept.class);
        ReportValues enrichedBalance = balance != null && secFlows != null && sameCurrency(currency, balance.currency())
                ? supplementBalance(balance, secFlows, secBalUsed) : balance;

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

        // 12. 現金及約當現金
        Flow secCash = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.CASH_AND_EQUIVALENTS), periodEnd);
        MetricResult<BigDecimal> futuCash = enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING")
                : enrichedBalance.value(FinancialConcept.CASH_AND_EQUIVALENTS);
        if (preferSec(secCash, futuCash, enrichedBalance)) {
            usedSecFlows.add(secCash);
            setBalanceComponent(row::setCashLikeAssets, row::setCashLikeAssetsStatus, row::setCashLikeAssetsReason,
                    row::setCashLikeAssetsSource, row::setCashLikeAssetsCalculation, row::setCashLikeAssetsAvailableAt,
                    secCash.value(), secCash.reason(), "SEC", "SEC CashAndCashEquivalentsAtCarryingValue", secCash.observedAt());
        } else {
            Flow cashSup = secBalUsed.get(FinancialConcept.CASH_AND_EQUIVALENTS);
            if (cashSup != null && futuCash.value() != null) usedSecFlows.add(cashSup);
            setBalanceComponent(row::setCashLikeAssets, row::setCashLikeAssetsStatus, row::setCashLikeAssetsReason,
                    row::setCashLikeAssetsSource, row::setCashLikeAssetsCalculation, row::setCashLikeAssetsAvailableAt,
                    futuCash.value(), reasonOf(futuCash), cashSup != null && futuCash.value() != null ? "FUTU_AND_SEC" : "FUTU",
                    "期末獨立現金及約當現金", cashSup != null && futuCash.value() != null ? laterInstant(balanceObs, cashSup.observedAt()) : balanceObs);
        }

        // 13. 優先股權益
        Flow secPref = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.PREFERRED_STOCK_CAPITAL), periodEnd);
        MetricResult<BigDecimal> futuPref = enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING")
                : calculator.preferredEquity(enrichedBalance);
        if (preferSec(secPref, futuPref, enrichedBalance)) {
            usedSecFlows.add(secPref);
            setBalanceComponent(row::setPreferredEquity, row::setPreferredEquityStatus, row::setPreferredEquityReason,
                    row::setPreferredEquitySource, row::setPreferredEquityCalculation, row::setPreferredEquityAvailableAt,
                    secPref.value(), secPref.reason(), "SEC", "SEC 優先股帳面值", secPref.observedAt());
        } else {
            Flow prefSup = secBalUsed.get(FinancialConcept.PREFERRED_STOCK_CAPITAL);
            if (prefSup != null && futuPref.value() != null) usedSecFlows.add(prefSup);
            setBalanceComponent(row::setPreferredEquity, row::setPreferredEquityStatus, row::setPreferredEquityReason,
                    row::setPreferredEquitySource, row::setPreferredEquityCalculation, row::setPreferredEquityAvailableAt,
                    futuPref.value(), reasonOf(futuPref), prefSup != null && futuPref.value() != null ? "FUTU_AND_SEC" : "FUTU",
                    "期末優先股帳面值", prefSup != null && futuPref.value() != null ? laterInstant(balanceObs, prefSup.observedAt()) : balanceObs);
        }

        // 14. 非控制權益
        Flow secNci = secFlows == null ? null : samePeriodSecFlow(secFlows.inputs().get(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY), periodEnd);
        MetricResult<BigDecimal> futuNci = enrichedBalance == null ? missingMetric("BALANCE_SHEET_MISSING")
                : calculator.noncontrollingInterest(enrichedBalance);
        if (preferSec(secNci, futuNci, enrichedBalance)) {
            usedSecFlows.add(secNci);
            setBalanceComponent(row::setNoncontrollingInterest, row::setNoncontrollingInterestStatus, row::setNoncontrollingInterestReason,
                    row::setNoncontrollingInterestSource, row::setNoncontrollingInterestCalculation, row::setNoncontrollingInterestAvailableAt,
                    secNci.value(), secNci.reason(), "SEC", "SEC 非控制權益帳面值", secNci.observedAt());
        } else {
            Flow nciSup = secBalUsed.get(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY);
            if (nciSup != null && futuNci.value() != null) usedSecFlows.add(nciSup);
            setBalanceComponent(row::setNoncontrollingInterest, row::setNoncontrollingInterestStatus, row::setNoncontrollingInterestReason,
                    row::setNoncontrollingInterestSource, row::setNoncontrollingInterestCalculation, row::setNoncontrollingInterestAvailableAt,
                    futuNci.value(), reasonOf(futuNci), nciSup != null && futuNci.value() != null ? "FUTU_AND_SEC" : "FUTU",
                    "期末非控制權益帳面值", nciSup != null && futuNci.value() != null ? laterInstant(balanceObs, nciSup.observedAt()) : balanceObs);
        }

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
            List<StockSplit> stockSplits, boolean multiClass, boolean verifiedOneToOne,
            StockStatus status, LocalDate periodEnd, Instant calculatedAt) {
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

        SecFinancialFact secShare = selectFilingSharesAtPeriod(periodCikFacts, periodEnd, calculatedAt);
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
            row.setSharesOutstanding(secShare.getValue());
            row.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
            row.setSharesOutstandingSource("SEC_FILING_SHARES");
            row.setSharesOutstandingCalculation("SEC " + secShare.getTag() + "（基準日 " + secShare.getPeriodEnd() + "）");
            row.setSharesOutstandingAvailableAt(secShare.getObservedAt());
            row.setSharesBasisDate(secShare.getPeriodEnd());
            row.setSecShareAccession(secShare.getAccession());
            if (splitSyncError(status) == null || secShare.getPeriodEnd().equals(periodEnd)) {
                sharesAtPeriodEnd = secShare.getValue()
                        .multiply(StockSplitCalculator.shareFactor(stockSplits, secShare.getPeriodEnd(), periodEnd), MC);
            }
        } else if (latestObs != null && splitSyncError(status) == null) {
            LocalDate obsDate = latestObs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            BigDecimal baseShares = multiClass && latestObs.effectiveMarketCap() != null
                    && latestObs.getSnapshotPrice() != null && latestObs.getSnapshotPrice().signum() > 0
                    ? latestObs.effectiveMarketCap().divide(latestObs.getSnapshotPrice(), MC)
                    : BigDecimal.valueOf(latestObs.effectiveShares());
            row.setSharesOutstanding(baseShares);
            row.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
            row.setSharesOutstandingSource("OBSERVED_SHARES");
            boolean hasDirectOutstanding = latestObs.getOutstandingShares() != null && latestObs.getOutstandingShares() > 0;
            row.setSharesOutstandingCalculation(hasDirectOutstanding ? "富途流通在外股數（基準日 " + obsDate + "）"
                    : "富途已發行股數備援（基準日 " + obsDate + "）");
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

    private SecFinancialFact selectFilingSharesAtPeriod(List<SecFinancialFact> facts, LocalDate periodEnd, Instant calculatedAt) {
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

    private boolean verifyFutuEpsAtBasis(List<ReportValues> reports, ReportValues latest, List<SecFinancialFact> rawSecFacts,
                                         String currency, List<StockSplit> stockSplits, LocalDate targetBasisDate, Instant calculatedAt) {
        List<ReportValues> periods = calculator.trailingPeriods(reports, latest.figi(), 1, latest.periodEnd());
        if (periods.isEmpty()) return false;
        for (ReportValues period : periods) {
            if (!hasSplitAfter(stockSplits, period.periodEnd(), targetBasisDate)) continue;
            BigDecimal periodEps = period.value(FinancialConcept.DILUTED_EPS).value();
            BigDecimal netIncome = calculator.flowInput(period, FinancialConcept.NET_INCOME_COMMON).value();
            if (secCalculator.isEpsSplitBasisVerified(periodEps, netIncome, period.periodEnd(), period.periodType() == 7,
                    rawSecFacts, currency, stockSplits, targetBasisDate, calculatedAt)) {
                continue;
            }
            if (period.periodType() == 4 && isFourthQuarterVerifiedViaAnnual10K(reports, period, rawSecFacts, currency, stockSplits, targetBasisDate, calculatedAt)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /** 美國公司第四季僅提交 10-K（無單季 10-Q XBRL 標籤）：核對同日年度 EPS 已通過 SEC 10-K 驗證，且同財年 Q1..Q4 之和與年度 EPS 股份基準吻合。 */
    private boolean isFourthQuarterVerifiedViaAnnual10K(List<ReportValues> reports, ReportValues q4, List<SecFinancialFact> rawSecFacts,
                                                        String currency, List<StockSplit> stockSplits, LocalDate targetBasisDate, Instant calculatedAt) {
        if (q4.fiscalYear() == null) return false;
        ReportValues annual = reports.stream()
                .filter(r -> r.statementType() == 1 && r.periodType() == 7 && q4.periodEnd().equals(r.periodEnd()))
                .findFirst().orElse(null);
        if (annual == null) return false;
        BigDecimal annualEps = annual.value(FinancialConcept.DILUTED_EPS).value();
        BigDecimal annualNi = calculator.flowInput(annual, FinancialConcept.NET_INCOME_COMMON).value();
        if (annualEps == null || !secCalculator.isEpsSplitBasisVerified(annualEps, annualNi, annual.periodEnd(), true,
                rawSecFacts, currency, stockSplits, targetBasisDate, calculatedAt)) {
            return false;
        }
        List<ReportValues> fyQuarters = reports.stream()
                .filter(r -> r.statementType() == 1 && Objects.equals(q4.fiscalYear(), r.fiscalYear()) && r.periodType() >= 1 && r.periodType() <= 4)
                .toList();
        if (fyQuarters.size() != 4) return false;
        BigDecimal sum = BigDecimal.ZERO;
        for (ReportValues q : fyQuarters) {
            BigDecimal qEps = q.value(FinancialConcept.DILUTED_EPS).value();
            if (qEps == null) return false;
            sum = sum.add(qEps, MC);
        }
        BigDecimal tol = annualEps.abs().multiply(new BigDecimal("0.04")).max(new BigDecimal("0.03"));
        return sum.subtract(annualEps, MC).abs().compareTo(tol) <= 0;
    }

    private LocalDate resolveSecEpsBasisDate(Flow secEps, List<SecFinancialFact> rawSecFacts, String currency,
                                             List<StockSplit> stockSplits, LocalDate latestShareBasisDate, Instant calculatedAt) {
        if (secEps == null || secEps.value() == null || secEps.epsBasisFrom() == null || secEps.periodEnd() == null) return null;
        if (!hasSplitAfter(stockSplits, secEps.epsBasisFrom(), latestShareBasisDate)) {
            return latestShareBasisDate;
        }
        LocalDate latestSplitToNow = stockSplits.stream().map(StockSplit::getExecutionDate)
                .filter(exec -> exec.isAfter(secEps.epsBasisFrom()) && !exec.isAfter(latestShareBasisDate))
                .max(LocalDate::compareTo).orElse(null);
        if (latestSplitToNow != null && secEps.earliestFiled() != null && !secEps.earliestFiled().isBefore(latestSplitToNow)
                && StockSplitCalculator.shareFactor(stockSplits, secEps.earliestFiled(), latestShareBasisDate).compareTo(BigDecimal.ONE) == 0) {
            return latestShareBasisDate;
        }
        boolean annual = secEps.periodStart() != null
                && ChronoUnit.DAYS.between(secEps.periodStart(), secEps.periodEnd()) + 1 >= 364;
        if (annual && secCalculator.isEpsSplitBasisVerified(secEps.value(), null, secEps.periodEnd(), true,
                rawSecFacts, currency, stockSplits, latestShareBasisDate, calculatedAt)) {
            return latestShareBasisDate;
        }
        if (!hasSplitBetween(stockSplits, secEps.epsBasisFrom(), secEps.periodEnd())
                && secEps.earliestFiled() != null && !hasSplitBetween(stockSplits, secEps.periodEnd(), secEps.earliestFiled())) {
            return secEps.periodEnd();
        }
        if (annual && secCalculator.isEpsSplitBasisVerified(secEps.value(), null, secEps.periodEnd(), true,
                rawSecFacts, currency, stockSplits, secEps.periodEnd(), calculatedAt)) {
            return secEps.periodEnd();
        }
        return null;
    }

    private boolean isMultiClassVerifiedOneToOne(String figi, String cik, StockShareObservation obs,
                                                 List<StockSplit> stockSplits, List<StockFinancialPeriod> periods, Instant calculatedAt) {
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

        BigDecimal equivShares = obs.effectiveMarketCap() != null && obs.getSnapshotPrice() != null && obs.getSnapshotPrice().signum() > 0
                ? obs.effectiveMarketCap().divide(obs.getSnapshotPrice(), MC)
                : BigDecimal.valueOf(obs.effectiveShares());
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
        if (stock.getCik() == null || stock.getCik().isBlank() || !SecurityTypePolicy.isOperatingCompanyType(stock.getStockType())) {
            return null;
        }
        return issuerHistory.cikAt(stock.getCik(), day);
    }

    private IndustryModelContext resolveIndustryModel(Stock stock, String knownIndustryCode, Instant cutoff, Instant configAsOf) {
        String figi = stock.getFigi();
        String code = knownIndustryCode;
        String plate = null;
        Optional<StockIndustryHistory> hist = industries.findAt(figi, cutoff);
        if (hist.isPresent() && hist.get().getIndustry() != null) {
            if (code == null) code = hist.get().getIndustry().getCode();
            plate = hist.get().getIndustry().getSourcePlateCode();
        }
        String cfgModel = null;
        String cfgMetric = null;
        BigDecimal cfgLow = null;
        BigDecimal cfgHigh = null;
        if (code != null) {
            Optional<IndustryValuationConfig> cfg = industryConfigs
                    .findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(code, configAsOf);
            if (cfg.isPresent()) {
                cfgModel = cfg.get().getModelCode();
                cfgMetric = cfg.get().getPrimaryMetric();
                cfgLow = cfg.get().getAssumedMultipleLow();
                cfgHigh = cfg.get().getAssumedMultipleHigh();
            }
        }
        String resolvedModel = IndustryValuationProposalService.resolveModelCode(cfgModel, plate, stock.getSic());
        return new IndustryModelContext(code, resolvedModel, cfgMetric, cfgLow, cfgHigh,
                IndustryValuationProposalService.isFinancialInstitutionModel(resolvedModel));
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
            IndustryValuationConfig cfg = code == null ? null : cfgs.get(code);
            String resolvedModel = IndustryValuationProposalService.resolveModelCode(
                    cfg == null ? null : cfg.getModelCode(), plates.get(figi), s.getSic());
            result.put(figi, new IndustryModelContext(code, resolvedModel,
                    cfg == null ? null : cfg.getPrimaryMetric(),
                    cfg == null ? null : cfg.getAssumedMultipleLow(),
                    cfg == null ? null : cfg.getAssumedMultipleHigh(),
                    IndustryValuationProposalService.isFinancialInstitutionModel(resolvedModel)));
        }
        return result;
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

    private DayDenominators resolveDayDenominators(
            Stock stock, StockStatus status, StockQuote quote, StockFinancialPeriod period,
            List<StockSplit> stockSplits, StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne, LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed) {
        if (period == null || !sameCurrency(stock.getCurrency(), period.getReportingCurrency())) {
            return new DayDenominators(null, null);
        }
        String splitError = splitSyncError(status);
        BigDecimal epsAtDay = null;
        if (period.getEpsTtm() != null && MetricStatus.AVAILABLE.name().equals(period.getEpsTtmStatus()) && period.getEpsShareBasisDate() != null) {
            if (splitError == null || period.getEpsShareBasisDate().equals(day)) {
                BigDecimal factor = StockSplitCalculator.shareFactor(stockSplits, period.getEpsShareBasisDate(), day);
                if (factor.signum() > 0) {
                    epsAtDay = period.getEpsTtm().divide(factor, MC);
                }
            }
        }
        if (quote != null && quote.getClosePrice() != null && quote.getClosePrice().signum() > 0) {
            ShareEvaluation eval = evaluateMarketCap(quote, period, stockSplits, status, primaryObs, fallbackObs,
                    multiClass, verifiedOneToOne, day, cutoff, calculatedAt, lastClosed);
            return new DayDenominators(epsAtDay, eval.sharesAtDay());
        }
        BigDecimal sharesAtDay = resolveSharesWithoutQuote(period, stockSplits, status, primaryObs, fallbackObs,
                multiClass, verifiedOneToOne, day, lastClosed);
        return new DayDenominators(epsAtDay, sharesAtDay);
    }

    private BigDecimal resolveSharesWithoutQuote(
            StockFinancialPeriod period, List<StockSplit> stockSplits, StockStatus status,
            StockShareObservation primaryObs, StockShareObservation fallbackObs,
            boolean multiClass, boolean verifiedOneToOne, LocalDate day, LocalDate lastClosed) {
        String splitError = splitSyncError(status);
        StockShareObservation obs = primaryObs != null ? primaryObs : fallbackObs;
        if (multiClass) {
            if (day.equals(lastClosed) && obs != null) {
                LocalDate basis = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
                if (splitError != null && !basis.equals(day)) return null;
                BigDecimal equiv = obs.effectiveMarketCap() != null && obs.getSnapshotPrice() != null && obs.getSnapshotPrice().signum() > 0
                        ? obs.effectiveMarketCap().divide(obs.getSnapshotPrice(), MC)
                        : BigDecimal.valueOf(obs.effectiveShares());
                return equiv.multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
            }
            if (!verifiedOneToOne) return null;
        } else if (day.equals(lastClosed) && obs != null) {
            LocalDate basis = obs.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            if (splitError != null && !basis.equals(day)) return null;
            return BigDecimal.valueOf(obs.effectiveShares()).multiply(StockSplitCalculator.shareFactor(stockSplits, basis, day), MC);
        }
        if (period.getSharesOutstanding() != null && period.getSharesBasisDate() != null) {
            if (splitError != null && !period.getSharesBasisDate().equals(day)) return null;
            return period.getSharesOutstanding().multiply(StockSplitCalculator.shareFactor(stockSplits, period.getSharesBasisDate(), day), MC);
        }
        return null;
    }

    /** 自身歷史估值參考：以單一適用主指標貫穿 <= valuationDate 的 5 年月頻有效樣本計算 P20 / P50 / P80 並換算價格區間，保留視窗總月數與排除月數。 */
    private ValuationReferenceDto buildHistoricalReference(
            String selectedMetric, List<ValuationResultDto> monthlyHistory, StockFinancialPeriod period,
            DayDenominators denoms, String quoteCurrency, boolean financialInstitution, LocalDate day) {
        if (period != null && !sameCurrency(quoteCurrency, period.getReportingCurrency())) {
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
        BigDecimal rawPriceHigh = convertMultipleToPrice(selectedMetric, high, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal priceLow = rawPriceLow != null && rawPriceHigh != null ? rawPriceLow : null;
        BigDecimal priceHigh = rawPriceLow != null && rawPriceHigh != null ? rawPriceHigh : null;
        return new ValuationReferenceDto(ValuationReferenceDto.KIND_HISTORICAL_MARKET, selectedMetric,
                MetricStatus.AVAILABLE.name(), null, low, median, high, priceLow, priceHigh,
                samples.size(), windowCount, excludedCount, firstSampleDate, lastSampleDate);
    }

    /** 同業市場估值參考：僅在最新收市日聚合相同 valuationDate 的同產業快照，按 coalesce(cik, figi) 去重 >= 5 家計算 P25 / P50 / P75。 */
    private ValuationReferenceDto buildPeerReference(
            String selectedMetric, String industryCode, LocalDate valuationDate, boolean historicalDate,
            StockFinancialPeriod period, DayDenominators denoms, String quoteCurrency, boolean financialInstitution) {
        if (historicalDate || valuationDate == null) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_PEER_MARKET, selectedMetric,
                    MetricStatus.NOT_APPLICABLE.name(), "HISTORICAL_DATE_NOT_APPLICABLE");
        }
        if (industryCode == null || industryCode.isBlank()) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_PEER_MARKET, selectedMetric,
                    MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_CLASSIFICATION_MISSING");
        }
        List<Object[]> rows = latestValuations.findActiveTrackedPeerSnapshots(industryCode, valuationDate);
        Map<String, List<BigDecimal>> byIssuer = new LinkedHashMap<>();
        for (Object[] tuple : rows) {
            if (tuple == null || tuple.length < 2 || !(tuple[0] instanceof String issuerKey)
                    || !(tuple[1] instanceof StockValuationLatest snapshot)) {
                continue;
            }
            if (!Objects.equals(snapshot.getValuationDate(), valuationDate)) {
                continue;
            }
            BigDecimal val = extractLatestMetric(snapshot, selectedMetric);
            if (val != null && val.signum() > 0) {
                byIssuer.computeIfAbsent(issuerKey, k -> new ArrayList<>()).add(val);
            }
        }
        List<BigDecimal> issuerMultiples = new ArrayList<>();
        for (List<BigDecimal> shareClassMultiples : byIssuer.values()) {
            shareClassMultiples.sort(BigDecimal::compareTo);
            issuerMultiples.add(quantile(shareClassMultiples, 0.50));
        }
        if (issuerMultiples.size() < MIN_PEER_ISSUERS) {
            return ValuationReferenceDto.unavailable(ValuationReferenceDto.KIND_PEER_MARKET, selectedMetric,
                    MetricStatus.INSUFFICIENT_DATA.name(), "INDUSTRY_PEER_COMPANIES_INSUFFICIENT");
        }
        issuerMultiples.sort(BigDecimal::compareTo);
        BigDecimal low = quantile(issuerMultiples, 0.25);
        BigDecimal median = quantile(issuerMultiples, 0.50);
        BigDecimal high = quantile(issuerMultiples, 0.75);
        BigDecimal rawPriceLow = convertMultipleToPrice(selectedMetric, low, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal rawPriceHigh = convertMultipleToPrice(selectedMetric, high, period, denoms, quoteCurrency, financialInstitution);
        BigDecimal priceLow = rawPriceLow != null && rawPriceHigh != null ? rawPriceLow : null;
        BigDecimal priceHigh = rawPriceLow != null && rawPriceHigh != null ? rawPriceHigh : null;
        return new ValuationReferenceDto(ValuationReferenceDto.KIND_PEER_MARKET, selectedMetric,
                MetricStatus.AVAILABLE.name(), null, low, median, high, priceLow, priceHigh,
                issuerMultiples.size(), null, null, valuationDate, valuationDate);
    }

    /** 產業估值假設：僅在最新收市日讀取當前生效設定換算假設價格區間；歷史日期一律 NOT_APPLICABLE。 */
    private ValuationReferenceDto buildIndustryAssumption(
            String fallbackMetric, IndustryModelContext modelCtx, String industryCode, boolean historicalDate,
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
        return new ValuationReferenceDto(ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, assumptionMetric,
                MetricStatus.AVAILABLE.name(), null, multipleLow, null, multipleHigh, priceLow, priceHigh, null);
    }

    /** 以所選交易日配對之同財季每股分母將目標估值倍數換算為隱含正股價；分母非正或缺值時回傳 null。 */
    private BigDecimal convertMultipleToPrice(
            String metricCode, BigDecimal multiple, StockFinancialPeriod period,
            DayDenominators denoms, String quoteCurrency, boolean financialInstitution) {
        if (metricCode == null || multiple == null || multiple.signum() <= 0 || period == null || denoms == null
                || !sameCurrency(quoteCurrency, period.getReportingCurrency())) {
            return null;
        }
        BigDecimal impliedPrice = switch (metricCode.toUpperCase()) {
            case "PE" -> denoms.epsAtDay() != null && denoms.epsAtDay().signum() > 0
                    ? multiple.multiply(denoms.epsAtDay(), MC).setScale(4, RoundingMode.HALF_UP) : null;
            case "PB" -> MetricStatus.AVAILABLE.name().equals(period.getCommonEquityStatus())
                    && period.getCommonEquity() != null && period.getCommonEquity().signum() > 0
                    && denoms.sharesAtDay() != null && denoms.sharesAtDay().signum() > 0
                    ? multiple.multiply(period.getCommonEquity(), MC).divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            case "PS" -> MetricStatus.AVAILABLE.name().equals(period.getRevenueTtmStatus())
                    && period.getRevenueTtm() != null && period.getRevenueTtm().signum() > 0
                    && denoms.sharesAtDay() != null && denoms.sharesAtDay().signum() > 0
                    ? multiple.multiply(period.getRevenueTtm(), MC).divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            case "PFCF" -> !financialInstitution && MetricStatus.AVAILABLE.name().equals(period.getFreeCashFlowTtmStatus())
                    && period.getFreeCashFlowTtm() != null && period.getFreeCashFlowTtm().signum() > 0
                    && denoms.sharesAtDay() != null && denoms.sharesAtDay().signum() > 0
                    ? multiple.multiply(period.getFreeCashFlowTtm(), MC).divide(denoms.sharesAtDay(), 4, RoundingMode.HALF_UP) : null;
            case "EV_EBITDA" -> {
                if (financialInstitution || !MetricStatus.AVAILABLE.name().equals(period.getEbitdaTtmStatus())
                        || period.getEbitdaTtm() == null || period.getEbitdaTtm().signum() <= 0
                        || !MetricStatus.AVAILABLE.name().equals(period.getEvAdjustmentStatus())
                        || period.getEvAdjustment() == null
                        || denoms.sharesAtDay() == null || denoms.sharesAtDay().signum() <= 0) {
                    yield null;
                }
                BigDecimal impliedMarketCap = multiple.multiply(period.getEbitdaTtm(), MC).subtract(period.getEvAdjustment(), MC);
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

    private void populateLatestEntity(StockValuationLatest entity, ValuationResultDto dto) {
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

    private LocalDate resolveFutuTtmStart(List<ReportValues> reports, String figi, int statementType,
                                          LocalDate periodEnd, LocalDate quarterStart, LocalDate secAnnualStart) {
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
        LocalDate earliestEnd = periods.stream().map(ReportValues::periodEnd).min(LocalDate::compareTo).orElse(periodEnd);
        List<ReportValues> prior = reports.stream()
                .filter(r -> r.figi().equals(figi) && r.statementType() == statementType && r.periodEnd().isBefore(earliestEnd))
                .max(Comparator.comparing(ReportValues::periodEnd)).stream().toList();
        if (!prior.isEmpty() && ChronoUnit.DAYS.between(prior.getFirst().periodEnd(), earliestEnd) <= 105) {
            return prior.getFirst().periodEnd().plusDays(1);
        }
        return earliestEnd.minusDays(90);
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

    private String splitSyncError(StockStatus status) {
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

    private boolean sameCurrency(String first, String second) {
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
