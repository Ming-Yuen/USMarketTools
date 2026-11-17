package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecEdgarFeedArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive.ClassFact;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive.FilingIndustry;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.entity.*;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.*;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.ReportValues;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.Flow;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.Flows;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component(TaskCode.STOCK_VALUATION_DAILY)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class StockValuationDailyTask implements Job {
    private static final int BATCH_SIZE = 100;
    private static final String EDGAR_CACHE_NOT_READY = "SEC_EDGAR_FEED_CACHE_NOT_READY";
    private static final Pattern NAMED_SHARE_CLASS = Pattern.compile("(?i)\\bclass\\s+([a-z0-9]+)\\b");
    // 富途銀行及承保產業；保險經紀的營運模式不在此列。
    private static final Set<String> FINANCIAL_INSTITUTION_PLATES = Set.of(
            "US.LIST2481", "US.LIST2456", "US.LIST2429", "US.LIST2512", "US.LIST2484", "US.LIST2467", "US.LIST2465");
    private static final Set<String> FINANCIAL_INSTITUTION_SICS = Set.of("6021", "6022", "6029", "6035", "6036", "6712",
            "6311", "6321", "6324", "6331", "6351", "6361", "6399");
    private final StockRepository stocks;
    private final StockStatusRepository statuses;
    private final StockQuoteRepository quotes;
    private final StockSplitRepository splits;
    private final FinancialReportRepository reports;
    private final FinancialReportItemMappingRepository mappings;
    private final StockValuationDailyRepository valuations;
    private final TransactionTemplate transactions;
    private final DateTimeService dateTimeService;
    private final MarketStatusService calendar;
    private final StockShareObservationRepository shareObservations;
    private final FutuService futu;
    private final EntityManager entityManager;
    private final SecFinancialFactRepository secFacts;
    private final StockIndustryHistoryRepository industries;
    private final SecFinancialStatementArchive statementArchives;
    private final SecEdgarFeedArchive edgarFeed;
    private final SecIssuerHistory issuerHistory;
    private final FinancialReportValuationCalculator calculator = new FinancialReportValuationCalculator();
    private final SecFinancialFactsCalculator secCalculator = new SecFinancialFactsCalculator();
    @Value("${valuation.history-start-date:2016-01-01}")
    private LocalDate historyStartDate = LocalDate.of(2016, 1, 1);

    @Override
    public void execute(JobExecutionContext context) {
        Instant startedAt = dateTimeService.now();
        JobDataMap params = context.getMergedJobDataMap();
        LocalDate requested = requestedValuationDate(params.getString("valuationDate"), startedAt);
        List<ActiveStockWithStatus> targets = findValuationTargets(requested, params.getString("figis"));
        if (targets.isEmpty()) return;

        List<String> figis = targets.stream().map(target -> target.stock().getFigi()).toList();
        // 日常從估值水位之後開始；空水位從設定起點回補。
        LocalDate lastClosed = calendar.getLastClosedTradingDay(startedAt);
        TreeMap<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> days = planDates(
                targets, requested, lastClosed, firstQuoteDates(figis), historyStartDate);
        if (days.isEmpty()) return;
        Set<String> rebuildFigis = targets.stream().filter(row -> row.status().getValuationDate() == null)
                .map(row -> row.stock().getFigi()).collect(Collectors.toSet());
        boolean incremental = requested == null;

        // 日常同步當前股數。EDGAR 只讀本地 JSON，按交易日計算和提交。
        RuntimeException shareFailure = incremental ? refreshShareObservations(targets) : null;
        Set<LocalDate> failedFeedDays = calculateAndSaveValuations(days, lastClosed, targets, figis, rebuildFigis, incremental);
        // 先保存可算的估值，再回報來源故障。
        if (!failedFeedDays.isEmpty()) throw new IllegalStateException("SEC_EDGAR_FEED_PARTIAL_FAILURE:" + failedFeedDays);
        if (shareFailure != null) throw new IllegalStateException("SHARE_OBSERVATION_SYNC_FAILED", shareFailure);
    }

    private LocalDate requestedValuationDate(String configuredDate, Instant startedAt) {
        if (configuredDate == null || configuredDate.isBlank()) return null;
        LocalDate day = LocalDate.parse(configuredDate);
        if (!calendar.isTradingDay(day) || calendar.getSessionCloseAt(day).isAfter(startedAt))
            throw new IllegalArgumentException("VALUATION_DATE_NOT_CLOSED_SESSION");
        return day;
    }

    private List<ActiveStockWithStatus> findValuationTargets(LocalDate requested, String configuredFigis) {
        Set<String> selected = configuredFigis == null || configuredFigis.isBlank() ? Set.of()
                : Arrays.stream(configuredFigis.split(",")).map(String::strip).filter(value -> !value.isBlank()).collect(Collectors.toSet());
        List<ActiveStockWithStatus> targets = requested == null ? stocks.findValuationStocks(ActiveStatus.ACTIVE)
                : stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, requested);
        if (selected.isEmpty()) return targets;
        List<ActiveStockWithStatus> matching = targets.stream().filter(row -> selected.contains(row.stock().getFigi())).toList();
        if (matching.isEmpty()) throw new IllegalArgumentException("VALUATION_SCOPE_NO_MATCH");
        return matching;
    }

    private Map<String, LocalDate> firstQuoteDates(List<String> figis) {
        Map<String, LocalDate> firstQuotes = new HashMap<>();
        for (Object[] row : quotes.findFirstQuoteDates(figis))
            firstQuotes.put((String) row[0], row[1] == null ? LocalDate.MAX : (LocalDate) row[1]);
        return firstQuotes;
    }

    private Set<String> valuationCiks(List<ActiveStockWithStatus> targets, List<String> figis, LocalDate firstDay, LocalDate lastDay) {
        Set<String> ciks = targets.stream().map(row -> row.stock().getCik()).filter(Objects::nonNull)
                .filter(cik -> !cik.isBlank()).collect(Collectors.toSet());
        for (String cik : List.copyOf(ciks)) ciks.addAll(issuerHistory.predecessors(cik));
        ciks.addAll(valuations.findRecordedSecCiks(figis, firstDay, lastDay));
        return ciks;
    }

    // 只準備估值水位之後、已有行情且落在 EDGAR 下載範圍的交易日。
    Map<Instant, List<SecFinancialFact>> pendingEdgarFilings(Instant knownAt, LocalDate firstFeedDate) {
        List<ActiveStockWithStatus> targets = stocks.findValuationStocks(ActiveStatus.ACTIVE);
        if (targets.isEmpty()) return Map.of();
        LocalDate lastClosed = calendar.getLastClosedTradingDay(knownAt);
        LocalDate from = firstFeedDate.isAfter(historyStartDate) ? firstFeedDate : historyStartDate;
        List<String> figis = targets.stream().map(target -> target.stock().getFigi()).toList();
        TreeMap<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> days = planDates(
                targets, null, lastClosed, firstQuoteDates(figis), from);
        if (days.isEmpty()) return Map.of();

        Set<String> ciks = valuationCiks(targets, figis, days.firstKey(), days.lastKey());
        if (ciks.isEmpty()) return Map.of();
        Function<List<SecFinancialFact>, Set<FactKey>> depreciation = statementArchives.loadDepreciation(ciks);
        Map<String, List<FilingIndustry>> filingIndustries = statementArchives.filingIndustries(ciks);
        Map<String, List<ClassFact>> classFacts = statementArchives.classFacts(ciks);
        List<FinancialReportItemMapping> catalog = mappings.findAll();
        Map<Instant, List<SecFinancialFact>> byClose = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> entry : days.entrySet()) {
            LocalDate day = entry.getKey();
            Instant cutoff = calendar.getSessionCloseAt(day);
            List<ActiveStockWithStatus> daily = List.copyOf(entry.getValue().values());
            Map<FactKey, SecFinancialFact> pending = new LinkedHashMap<>();
            for (int start = 0; start < daily.size(); start += BATCH_SIZE) {
                List<ActiveStockWithStatus> batch = daily.subList(start, Math.min(start + BATCH_SIZE, daily.size()));
                List<PreparedValuation> prepared = prepareBatch(batch, day, cutoff, knownAt, lastClosed, catalog,
                        depreciation, filingIndustries, classFacts);
                collectPendingDepreciation(prepared, pending);
            }
            if (!pending.isEmpty()) byClose.put(cutoff, List.copyOf(pending.values()));
        }
        return byClose;
    }

    private Set<LocalDate> calculateAndSaveValuations(TreeMap<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> days,
                                                     LocalDate lastClosed, List<ActiveStockWithStatus> targets, List<String> figis,
                                                     Set<String> rebuildFigis, boolean incremental) {
        Instant calculatedAt = dateTimeService.now();
        List<FinancialReportItemMapping> catalog = mappings.findAll();
        Set<String> ciks = valuationCiks(targets, figis, days.firstKey(), days.lastKey());
        Function<List<SecFinancialFact>, Set<FactKey>> depreciation = statementArchives.loadDepreciation(ciks);
        SecEdgarFeedArchive.Session feed = edgarFeed.session();
        Map<String, List<FilingIndustry>> filingIndustries = statementArchives.filingIndustries(ciks);
        Map<String, List<ClassFact>> classFacts = statementArchives.classFacts(ciks);

        Set<LocalDate> failedFeedDays = new TreeSet<>();
        for (Map.Entry<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> entry : days.entrySet()) {
            LocalDate day = entry.getKey();
            Instant cutoff = calendar.getSessionCloseAt(day);
            List<ActiveStockWithStatus> daily = List.copyOf(entry.getValue().values());
            for (int start = 0; start < daily.size(); start += BATCH_SIZE) {
                List<ActiveStockWithStatus> batch = daily.subList(start, Math.min(start + BATCH_SIZE, daily.size()));
                List<PreparedValuation> prepared = prepareBatch(batch, day, cutoff, calculatedAt, lastClosed, catalog,
                        depreciation, filingIndustries, classFacts);
                if (prepared.isEmpty()) continue;
                SecEdgarFeedArchive.Confirmation confirmed = confirmDepreciation(prepared, feed, day, cutoff, calculatedAt);
                List<LocalDate> failures = confirmed == null ? List.of() : confirmed.failedDates();
                failedFeedDays.addAll(failures);
                Map<String, ConfirmedValuation> finalValues = applyConfirmedDepreciation(prepared, day, cutoff, calculatedAt, depreciation, confirmed);
                Set<FactKey> missing = confirmed == null ? Set.of() : confirmed.missingFacts();
                executeBatch(prepared, day, cutoff, calculatedAt, lastClosed, rebuildFigis, finalValues, missing, !failures.isEmpty(), incremental);
            }
        }
        return failedFeedDays;
    }

    private record ValuationInputs(Stock stock, StockQuote quote, List<ReportValues> reports, Instant futuObservedAt,
                                   StockShareObservation shares, SecFinancialFact filingShares, boolean requiresClassShares, Flow classEps,
                                   boolean financialInstitution, boolean securitiesBroker, String cik, List<SecFinancialFact> facts,
                                   BigDecimal issuerMarketCap) {}

    private record PreparedValuation(ValuationInputs inputs, StockStatus status, List<StockSplit> adjustments, boolean useCloseSnapshot,
                                      Flows secFinancials, List<SecFinancialFact> pendingDepreciation, StockValuationDaily row) {}

    private record ConfirmedValuation(StockValuationDaily row, Flows financials) {}

    private SecEdgarFeedArchive.Confirmation confirmDepreciation(List<PreparedValuation> batch, SecEdgarFeedArchive.Session feed,
                                                               LocalDate day, Instant cutoff, Instant calculatedAt) {
        Map<FactKey, SecFinancialFact> pending = new LinkedHashMap<>();
        collectPendingDepreciation(batch, pending);
        if (pending.isEmpty()) return null;
        boolean hasLaterRevision = pending.values().stream().anyMatch(fact -> fact.getAvailableFromDate().isAfter(day));
        return feed.confirm(pending.values(), hasLaterRevision ? calculatedAt : cutoff);
    }

    private void collectPendingDepreciation(List<PreparedValuation> batch, Map<FactKey, SecFinancialFact> pending) {
        for (PreparedValuation valuation : batch) {
            for (SecFinancialFact fact : valuation.pendingDepreciation()) pending.putIfAbsent(SecFinancialFactConverter.key(fact), fact);
        }
    }

    private Map<String, ConfirmedValuation> applyConfirmedDepreciation(List<PreparedValuation> batch, LocalDate day, Instant cutoff, Instant calculatedAt,
                                                              Function<List<SecFinancialFact>, Set<FactKey>> depreciation,
                                                              SecEdgarFeedArchive.Confirmation confirmed) {
        if (confirmed == null) return Map.of();
        Function<List<SecFinancialFact>, Set<FactKey>> supplemented = facts -> {
            Set<FactKey> keys = new HashSet<>(depreciation.apply(facts));
            keys.addAll(confirmed.facts().apply(facts));
            return keys;
        };
        Map<String, ConfirmedValuation> results = new HashMap<>();
        for (PreparedValuation result : batch) {
            if (result.pendingDepreciation().isEmpty() || confirmed.facts().apply(result.pendingDepreciation()).isEmpty()) continue;
            ValuationInputs inputs = result.inputs();
            Flows secFinancials = secCalculator.calculateSelectedFacts(inputs.facts(), inputs.cik(), inputs.stock().getCurrency(), day, calculatedAt,
                    inputs.reports(), supplemented, ignored -> {});
            StockValuationDaily row = valuationRow(inputs, result.status(), result.adjustments(), day, cutoff, calculatedAt,
                    result.useCloseSnapshot(), secFinancials);
            results.put(row.getFigi(), new ConfirmedValuation(row, secFinancials));
        }
        return results;
    }

    private void executeBatch(List<PreparedValuation> batch, LocalDate day, Instant cutoff, Instant calculatedAt,
                              LocalDate lastClosed, Set<String> rebuildFigis, Map<String, ConfirmedValuation> confirmed,
                              Set<FactKey> missing, boolean feedFailed, boolean incremental) {
        try {
            transactions.executeWithoutResult(transaction -> saveBatch(batch, day, cutoff, calculatedAt, lastClosed, rebuildFigis,
                    confirmed, missing, feedFailed, incremental));
            log.info("Financial valuation batch committed | date={} stocks={}", day, batch.size());
        } catch (RuntimeException failure) {
            log.error("Financial valuation batch rolled back | date={} figis={}", day, batch.stream().map(result -> result.row().getFigi()).toList(), failure);
            throw failure;
        }
    }

    private RuntimeException refreshShareObservations(List<ActiveStockWithStatus> targets) {
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            Set<String> supported = session.supportedUsStocks();
            Map<String, Long> symbolCounts = targets.stream().filter(row -> row.stock().getSymbol() != null)
                    .collect(Collectors.groupingBy(row -> row.stock().getSymbol(), Collectors.counting()));
            List<ActiveStockWithStatus> supportedTargets = targets.stream().filter(row -> supported.contains(row.stock().getSymbol())
                    && symbolCounts.get(row.stock().getSymbol()) == 1).toList();
            int saved = 0;
            for (int start = 0; start < supportedTargets.size(); start += 400) {
                List<ActiveStockWithStatus> batch = supportedTargets.subList(start, Math.min(start + 400, supportedTargets.size()));
                Map<String, FutuService.ShareSnapshot> downloaded = session.shareCounts(batch.stream().map(row -> row.stock().getSymbol()).toList());
                Instant observedAt = dateTimeService.now();
                List<StockShareObservation> rows = new ArrayList<>();
                for (ActiveStockWithStatus target : batch) {
                    FutuService.ShareSnapshot snapshot = downloaded.get(target.stock().getSymbol());
                    if (snapshot == null) {
                        log.warn("Share snapshot unavailable | figi={} symbol={}", target.stock().getFigi(), target.stock().getSymbol());
                        continue;
                    }
                    StockShareObservation observation = new StockShareObservation();
                    observation.setFigi(target.stock().getFigi());
                    observation.setObservedAt(observedAt);
                    observation.setIssuedShares(snapshot.issuedShares());
                    observation.setSnapshotPrice(snapshot.price());
                    observation.setSnapshotMarketCap(snapshot.marketCap());
                    observation.setSnapshotQuoteTime(snapshot.quoteTime());
                    observation.setSource("FUTU");
                    rows.add(observation);
                }
                if (!rows.isEmpty()) transactions.executeWithoutResult(transaction -> {
                    List<String> figis = rows.stream().map(StockShareObservation::getFigi).toList();
                    if (statuses.findAllByIdForUpdate(figis).size() != rows.size()) throw new IllegalStateException("SHARE_OBSERVATION_STOCK_STATUS_MISSING");
                    for (StockShareObservation row : rows) entityManager.persist(row);
                    entityManager.flush(); // 使用已配置的 JDBC batch，避免 assigned ID saveAll 每行先 SELECT。
                });
                saved += rows.size();
            }
            log.info("Share observations collected | targets={} supportedUnique={} saved={}", targets.size(), supportedTargets.size(), saved);
        } catch (RuntimeException failure) {
            log.error("Share observation collection failed; valuation uses only already stored eligible observations", failure);
            return failure;
        }
        return null;
    }

    private TreeMap<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> planDates(List<ActiveStockWithStatus> targets,
                                                                                     LocalDate requested, LocalDate lastClosed,
                                                                                     Map<String, LocalDate> firstQuotes, LocalDate firstDay) {
        TreeMap<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> days = new TreeMap<>();
        if (requested != null) {
            for (ActiveStockWithStatus target : targets) {
                if (!requested.isBefore(valuationStart(target.stock(), firstQuotes))) addDate(days, requested, target);
            }
            return days;
        }
        for (ActiveStockWithStatus target : targets) {
            StockStatus status = target.status();
            if (status.getQuoteDate() == null) continue;
            LocalDate latest = status.getQuoteDate().isBefore(lastClosed) ? status.getQuoteDate() : lastClosed;
            LocalDate from = status.getValuationDate() == null ? firstDay : calendar.getNextTradingDay(status.getValuationDate());
            if (from.isBefore(firstDay)) from = firstDay;
            LocalDate start = valuationStart(target.stock(), firstQuotes);
            if (from.isBefore(start)) from = start;
            if (from.isAfter(latest)) continue;
            if (!calendar.isTradingDay(from)) from = calendar.getNextTradingDay(from);
            for (LocalDate day = from; !day.isAfter(latest); day = calendar.getNextTradingDay(day)) {
                addDate(days, day, target);
            }
        }
        return days;
    }

    private LocalDate valuationStart(Stock stock, Map<String, LocalDate> firstQuotes) {
        LocalDate firstQuote = firstQuotes.getOrDefault(stock.getFigi(), LocalDate.MIN);
        LocalDate listed = stock.getListDate() == null ? LocalDate.MIN : stock.getListDate();
        return firstQuote.isAfter(listed) ? firstQuote : listed;
    }

    private void addDate(TreeMap<LocalDate, LinkedHashMap<String, ActiveStockWithStatus>> days, LocalDate day, ActiveStockWithStatus target) {
        days.computeIfAbsent(day, key -> new LinkedHashMap<>()).putIfAbsent(target.stock().getFigi(), target);
    }

    private List<PreparedValuation> prepareBatch(List<ActiveStockWithStatus> targets, LocalDate day, Instant cutoff, Instant calculatedAt,
                                                 LocalDate lastClosed, List<FinancialReportItemMapping> catalog,
                                                 Function<List<SecFinancialFact>, Set<FactKey>> depreciation, Map<String, List<FilingIndustry>> filingIndustries,
                                                 Map<String, List<ClassFact>> classFacts) {
        List<String> figis = targets.stream().map(row -> row.stock().getFigi()).toList();
        Map<String, StockQuote> prices = quotes.findStoredQuotes(figis, day).stream().collect(Collectors.toMap(StockQuote::getFigi, row -> row));
        List<StockValuationDaily> existing = valuations.findByFigiInAndValuationDate(figis, day);
        // 使用股票已識別的發行人；重算優先沿用當日紀錄，財報另按本輪取得時間及目標財季篩選。
        Map<String, String> eligibleCiks = secIssuerCiks(targets, existing, day);
        List<String> ciks = eligibleCiks.values().stream().distinct().toList();
        List<FinancialReport> source = reports.findValuationReports(figis, day, calculatedAt);
        Map<String, List<StockSplit>> adjustments = splits.findByFigiInOrderByExecutionDateAsc(figis).stream()
                .collect(Collectors.groupingBy(StockSplit::getFigi));
        Map<String, List<ReportValues>> financials = calculator.extract(source, catalog).stream()
                .collect(Collectors.groupingBy(ReportValues::figi));
        Set<String> multiSecurityIssuers = ciks.isEmpty() ? Set.of() : stocks.findMultiSecurityIssuers(ciks, ActiveStatus.ACTIVE);
        Map<String, List<SecFinancialFact>> byCik = ciks.isEmpty() ? Map.of()
                : secFacts.findValuationFlows(ciks, SecFinancialFactsCalculator.INPUT_TAGS, day, calculatedAt).stream()
                        .collect(Collectors.groupingBy(SecFinancialFact::getCik));
        Map<String, BigDecimal> issuerMarketCaps = issuerMarketCaps(multiSecurityIssuers, byCik, classFacts, day, calculatedAt);
        Map<String, String> industryPlates = new HashMap<>();
        for (StockIndustryHistory history : industries.findByFigisAt(figis, cutoff)) {
            String plate = history.getIndustry().getSourcePlateCode();
            if (plate != null) industryPlates.put(history.getFigi(), plate);
        }
        Set<String> financialInstitutions = industryPlates.entrySet().stream().filter(entry -> FINANCIAL_INSTITUTION_PLATES.contains(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> securitiesBrokers = new HashSet<>();
        Set<String> withSecIndustry = new HashSet<>();
        eligibleCiks.forEach((figi, cik) -> filingIndustries.getOrDefault(cik, List.of()).stream()
                .filter(filing -> filing.filed().isBefore(day)).findFirst().ifPresent(filing -> {
                    withSecIndustry.add(figi);
                    String plate = industryPlates.get(figi);
                    if (plate == null && FINANCIAL_INSTITUTION_SICS.contains(filing.sic())) financialInstitutions.add(figi);
                    // SEC 6211 也包含部分資產管理公司，只用來細分富途資本市場或補缺。
                    if ("6211".equals(filing.sic()) && (plate == null || "US.LIST2260".equals(plate))) securitiesBrokers.add(figi);
                }));
        // 只有富途與 SEC 都無產業分類時，才使用 Finnhub 的 Banking 補充銀行適用性。
        for (ActiveStockWithStatus target : targets) {
            String figi = target.stock().getFigi();
            if (!industryPlates.containsKey(figi) && !withSecIndustry.contains(figi)
                    && "Banking".equalsIgnoreCase(target.stock().getFinnhubIndustry())) financialInstitutions.add(figi);
        }
        List<StockShareObservation> observations = day.equals(lastClosed)
                ? latestCloseShares(targets, prices, day, cutoff, calculatedAt)
                : shareObservations.findLatestAt(figis, cutoff);
        Map<String, StockShareObservation> shares = observations.stream()
                .collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
        if (!day.equals(lastClosed) && existing.stream().anyMatch(row -> row.getSecShareAccession() == null
                && row.getSharesObservedAt() != null && row.getSharesObservedAt().isAfter(cutoff))) {
            for (StockShareObservation observation : shareObservations.findPreviouslyUsedAfterClose(figis, day, calculatedAt))
                shares.put(observation.getFigi(), observation);
        }
        Map<String, StockValuationDaily> previous = existing.stream()
                .collect(Collectors.toMap(StockValuationDaily::getFigi, row -> row));
        List<PreparedValuation> prepared = new ArrayList<>();
        for (ActiveStockWithStatus target : targets) {
            Stock stock = target.stock();
            String figi = stock.getFigi();
            String cik = eligibleCiks.get(figi);
            List<StockSplit> events = adjustments.getOrDefault(figi, List.of());
            List<SecFinancialFact> facts = cik == null ? List.of() : byCik.getOrDefault(cik, List.of()).stream()
                    .filter(fact -> !"EarningsPerShareDiluted".equals(fact.getTag())
                            || fact.getAvailableFromDate() == null || !fact.getAvailableFromDate().isAfter(day)
                            || !splitBetween(events, day, fact.getFiled())).toList();
            List<ReportValues> values = financials.getOrDefault(figi, List.of());
            SecFinancialFactsCalculator.PeriodSelection selection = secCalculator.selectPeriods(values, facts, day, calculatedAt);
            values = selection.reports();
            facts = selection.facts();
            Instant financialObservedAt = source.stream().filter(report -> figi.equals(report.getFigi())
                    && selection.reports().stream().anyMatch(value -> value.statementType() == report.getStatementType()
                    && value.periodType() == report.getPeriodType() && Objects.equals(value.fiscalYear(), report.getFiscalYear())))
                    .map(FinancialReport::getObservedAt).max(Comparator.naturalOrder()).orElse(null);
            StockQuote quote = prices.get(figi);
            // 富途快照及 Company Facts 都可能給整家公司股數；同一 CIK 的股類不能共用。
            boolean requiresClassShares = cik != null && multiSecurityIssuers.contains(cik)
                    || stock.getLongName() != null && NAMED_SHARE_CLASS.matcher(stock.getLongName()).find();
            List<ClassFact> disclosures = cik == null ? List.of() : classFacts.getOrDefault(cik, List.of());
            Flow classEps = null;
            SecFinancialFact filingShares = null;
            if (cik != null) {
                if (requiresClassShares) {
                    classEps = filingClassEps(stock, disclosures, facts, day, calculatedAt);
                    filingShares = filingClassShares(stock, disclosures, day, calculatedAt);
                } else filingShares = selectFilingShares(facts, day, calculatedAt);
            }
            StockShareObservation observation = requiresClassShares ? null : shares.get(figi);
            StockValuationDaily old = previous.get(figi);
            boolean useCloseSnapshot = useCloseSnapshot(day, lastClosed, old, observation);
            boolean financialInstitution = financialInstitutions.contains(figi);
            boolean securitiesBroker = securitiesBrokers.contains(figi);
            List<SecFinancialFact> pending = new ArrayList<>();
            Flows secFinancials = facts.isEmpty() ? null : secCalculator.calculateSelectedFacts(facts, cik, stock.getCurrency(), day,
                    calculatedAt, values, depreciation, pending::add);
            ValuationInputs inputs = new ValuationInputs(stock, quote, values, financialObservedAt, observation, filingShares, requiresClassShares,
                    classEps, financialInstitution, securitiesBroker, cik, facts, cik == null ? null : issuerMarketCaps.get(cik));
            StockValuationDaily row = valuationRow(inputs, target.status(), events, day, cutoff, calculatedAt, useCloseSnapshot, secFinancials);
            if (!SecFinancialFactsCalculator.DEPRECIATION_SCOPE_UNRESOLVED.equals(row.getEvEbitdaReason())) pending.clear();
            prepared.add(new PreparedValuation(inputs, target.status(), events, useCloseSnapshot, secFinancials, pending, row));
        }
        return prepared;
    }

    private boolean splitBetween(List<StockSplit> splits, LocalDate after, LocalDate through) {
        return splits.stream().anyMatch(split -> split.getExecutionDate().isAfter(after) && !split.getExecutionDate().isAfter(through));
    }

    private Map<String, BigDecimal> issuerMarketCaps(Set<String> ciks, Map<String, List<SecFinancialFact>> facts,
                                                    Map<String, List<ClassFact>> disclosures, LocalDate day, Instant knownAt) {
        if (ciks.isEmpty()) return Map.of();
        List<ActiveStockWithStatus> securities = stocks.findIssuerSecurities(ciks, ActiveStatus.ACTIVE);
        List<String> figis = securities.stream().map(row -> row.stock().getFigi()).toList();
        if (figis.isEmpty()) return Map.of();
        Map<String, StockQuote> prices = quotes.findStoredQuotes(figis, day).stream().collect(Collectors.toMap(StockQuote::getFigi, row -> row));
        Map<String, List<StockSplit>> events = splits.findByFigiInOrderByExecutionDateAsc(figis).stream().collect(Collectors.groupingBy(StockSplit::getFigi));
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map.Entry<String, List<ActiveStockWithStatus>> issuer : securities.stream().collect(Collectors.groupingBy(row -> row.stock().getCik())).entrySet()) {
            String cik = issuer.getKey();
            SecFinancialFact total = latestShareCount(facts.getOrDefault(cik, List.of()).stream()
                    .filter(fact -> "CommonStockSharesOutstanding".equals(fact.getTag()) && "shares".equals(fact.getUnit())
                            && fact.getPeriodStart() == null && !fact.getPeriodEnd().isAfter(fact.getFiled())
                            && !fact.getAvailableFromDate().isAfter(day) && !fact.getObservedAt().isAfter(knownAt)).toList());
            if (total == null || issuer.getValue().size() < 2) continue;
            BigDecimal cap = issuerMarketCap(issuer.getValue(), total, disclosures.getOrDefault(cik, List.of()), prices, events, day, knownAt);
            if (cap != null) result.put(cik, cap);
        }
        return result;
    }

    private BigDecimal issuerMarketCap(List<ActiveStockWithStatus> securities, SecFinancialFact total, List<ClassFact> disclosures,
                                       Map<String, StockQuote> prices, Map<String, List<StockSplit>> events, LocalDate day, Instant knownAt) {
        LocalDate basis = total.getPeriodEnd();
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        Set<String> classes = new HashSet<>();
        String currency = securities.getFirst().stock().getCurrency();
        for (ActiveStockWithStatus security : securities) {
            Stock stock = security.stock();
            StockQuote price = prices.get(stock.getFigi());
            java.util.regex.Matcher namedClass = NAMED_SHARE_CLASS.matcher(Objects.toString(stock.getLongName(), ""));
            if (!SecurityTypePolicy.isOperatingCompanyType(stock.getStockType()) || !Objects.equals(stock.getCik(), issuerHistory.cikAt(stock.getCik(), day))
                    || !sameCurrency(currency, stock.getCurrency()) || !namedClass.find() || !classes.add(namedClass.group(1).toUpperCase(Locale.ROOT))
                    || price == null || price.getClosePrice() == null || price.getClosePrice().signum() <= 0
                    || splitCoverageReason(security.status(), basis, day) != null) return null;
            SecFinancialFact count = filingClassShares(stock, disclosures, day, knownAt);
            if (count == null || !total.getAccession().equals(count.getAccession()) || !basis.equals(count.getPeriodEnd())) return null;
            List<StockSplit> splitsForClass = events.getOrDefault(stock.getFigi(), List.of());
            if (splitsForClass.stream().anyMatch(split -> split.getExecutionDate().isAfter(basis)
                    && !split.getExecutionDate().isAfter(count.getFiled()))) return null;
            shares = shares.add(count.getValue());
            BigDecimal adjusted = count.getValue().multiply(StockSplitCalculator.shareFactor(splitsForClass, basis, day));
            value = value.add(price.getClosePrice().multiply(adjusted));
        }
        // 股類須合計回同份申報的公司股數，才可排除遺漏未上市股類。
        return shares.compareTo(total.getValue()) == 0 ? value : null;
    }

    private void saveBatch(List<PreparedValuation> batch, LocalDate day, Instant cutoff, Instant calculatedAt, LocalDate lastClosed,
                           Set<String> rebuildFigis, Map<String, ConfirmedValuation> confirmed, Set<FactKey> missing,
                           boolean feedFailed, boolean incremental) {
        List<String> figis = batch.stream().map(result -> result.row().getFigi()).toList();
        Map<String, StockStatus> locked = statuses.findAllByIdForUpdate(figis).stream().collect(Collectors.toMap(StockStatus::getFigi, row -> row));
        Map<String, StockValuationDaily> previous = valuations.findByFigiInAndValuationDate(figis, day).stream()
                .collect(Collectors.toMap(StockValuationDaily::getFigi, row -> row));
        // 寫入前確認拆股沒有在取數和核對期間被更正。
        boolean feedChecked = batch.stream().anyMatch(result -> !result.pendingDepreciation().isEmpty());
        Map<String, List<StockSplit>> currentSplits = feedChecked
                ? splits.findByFigiInOrderByExecutionDateAsc(figis).stream().collect(Collectors.groupingBy(StockSplit::getFigi)) : Map.of();
        List<StockStatus> updated = new ArrayList<>();
        List<StockValuationDaily> saved = new ArrayList<>();
        Set<String> retract = new LinkedHashSet<>();
        Map<LocalDate, List<String>> summariesToRestore = new HashMap<>();
        for (PreparedValuation result : batch) {
            ValuationInputs inputs = result.inputs();
            String figi = result.row().getFigi();
            StockStatus status = locked.get(figi);
            if (status == null || status.getQuoteDate() == null || status.getQuoteDate().isBefore(day)) continue;
            if (incremental && status.getValuationDate() != null && !status.getValuationDate().isBefore(day)) continue;
            StockValuationDaily old = previous.get(figi);
            ConfirmedValuation verified = confirmed.get(figi);
            if (feedFailed && old != null && old.getEvEbitda() != null
                    && !result.pendingDepreciation().isEmpty() && verified == null) continue;
            StockValuationDaily row = verified == null ? result.row() : verified.row();
            List<StockSplit> events = feedChecked ? currentSplits.getOrDefault(figi, List.of()) : result.adjustments();
            Flows secFinancials = verified == null ? result.secFinancials() : verified.financials();
            boolean splitChanged = !sameSplitCoverage(result.status(), status) || !events.equals(result.adjustments());
            boolean useCloseSnapshot = useCloseSnapshot(day, lastClosed, old, inputs.shares());
            if (splitChanged || result.useCloseSnapshot() != useCloseSnapshot) {
                row = valuationRow(inputs, status, events, day, cutoff, calculatedAt, useCloseSnapshot, secFinancials);
            }
            if (SecFinancialFactsCalculator.DEPRECIATION_SCOPE_UNRESOLVED.equals(row.getEvEbitdaReason())
                    && result.pendingDepreciation().stream().map(SecFinancialFactConverter::key).anyMatch(missing::contains))
                row.setEvEbitdaReason(EDGAR_CACHE_NOT_READY);
            List<String> unknown = inputs.reports().stream().flatMap(report -> report.unknownNames().stream()).distinct().toList();
            boolean retained = false;
            if (!rebuildFigis.contains(figi)) {
                ValuationUpdate update = reconcile(old, row, status, events, day, calculatedAt,
                        secFinancials, inputs.requiresClassShares() && row.getSecShareAccession() == null);
                row = update.row();
                retained = update.retained();
            }
            if (!retained) saved.add(row);
            if (!day.isBefore(historyStartDate)) {
                if (row.hasAvailableMetric() && (status.getValuationDate() == null || status.getValuationDate().isBefore(day))) status.setValuationDate(day);
                else if (!row.hasAvailableMetric() && day.equals(status.getValuationDate())) retract.add(figi);
            }
            LocalDate watermark = status.getValuationDate();
            String currentMessage = status.getValuationMessage();
            if (watermark != null && day.isBefore(watermark)) {
                if (!messageIsOnOrAfter(currentMessage, watermark))
                    summariesToRestore.computeIfAbsent(watermark, date -> new ArrayList<>()).add(figi);
            } else if (!messageIsOnOrAfter(currentMessage, day.plusDays(1))) {
                String outcome = retained ? "VALUATION_INPUTS_NO_LONGER_AVAILABLE; retained previous result" : reasons(row);
                if (day.isBefore(historyStartDate)) outcome += ";OUTSIDE_CONFIGURED_VALUATION_RANGE:" + historyStartDate;
                if (!unknown.isEmpty()) outcome += ";UNMAPPED_FINANCIAL_ITEM:" + String.join(",", unknown);
                status.setValuationMessage(message(day + ":" + outcome));
            }
            updated.add(status);
        }
        if (updated.isEmpty()) return;
        for (Map.Entry<LocalDate, List<String>> entry : summariesToRestore.entrySet()) {
            for (StockValuationDaily row : valuations.findByFigiInAndValuationDate(entry.getValue(), entry.getKey()))
                locked.get(row.getFigi()).setValuationMessage(message(row.getValuationDate() + ":" + reasons(row)));
        }
        List<StockValuationDaily> replaced = saved.stream().filter(row -> rebuildFigis.contains(row.getFigi()))
                .map(row -> previous.get(row.getFigi())).filter(Objects::nonNull).toList();
        if (!replaced.isEmpty()) {
            replaced.forEach(entityManager::remove);
            entityManager.flush();
        }
        LocalDateTime updatedAt = saved.isEmpty() ? null : dateTimeService.nowLocalDateTime();
        for (StockValuationDaily row : saved) {
            row.setUpdatedAt(updatedAt);
            StockValuationDaily old = previous.get(row.getFigi());
            if (old != null && !rebuildFigis.contains(row.getFigi())) {
                row.setStock(old.getStock());
                entityManager.merge(row);
            } else entityManager.persist(row);
        }
        entityManager.flush();
        if (!retract.isEmpty()) {
            Map<String, LocalDate> latest = new HashMap<>();
            for (Object[] row : valuations.findLatestAvailableDates(retract, historyStartDate, lastClosed)) latest.put((String) row[0], (LocalDate) row[1]);
            for (String figi : retract) locked.get(figi).setValuationDate(latest.get(figi));
        }
        statuses.saveAllAndFlush(updated);
    }

    private boolean sameSplitCoverage(StockStatus first, StockStatus second) {
        return Objects.equals(first.getSplitFromDate(), second.getSplitFromDate())
                && Objects.equals(first.getSplitDate(), second.getSplitDate()) && Objects.equals(first.getSplitMessage(), second.getSplitMessage());
    }

    private boolean useCloseSnapshot(LocalDate day, LocalDate lastClosed, StockValuationDaily old, StockShareObservation observation) {
        return day.equals(lastClosed) || old != null && observation != null
                && observation.getObservedAt().equals(old.getSharesObservedAt()) && "FUTU_CLOSE_SNAPSHOT".equals(old.getMarketCapSource());
    }

    private boolean messageIsOnOrAfter(String value, LocalDate day) {
        // 此欄由本 Job 以 ISO 交易日開頭寫入，日期前綴可按順序比較。
        return value != null && value.length() > 10 && value.charAt(10) == ':'
                && value.substring(0, 10).compareTo(day.toString()) >= 0;
    }

    private SecFinancialFact selectFilingShares(List<SecFinancialFact> facts, LocalDate day, Instant calculatedAt) {
        List<SecFinancialFact> eligible = facts.stream().filter(fact -> fact.getAvailableFromDate() != null
                && !fact.getAvailableFromDate().isAfter(day) && fact.getPeriodEnd() != null && !fact.getPeriodEnd().isAfter(day)
                && fact.getObservedAt() != null && !fact.getObservedAt().isAfter(calculatedAt)).toList();
        List<SecFinancialFact> counts = eligible.stream().filter(fact -> "shares".equals(fact.getUnit())
                && fact.getPeriodStart() == null && fact.getFiled() != null && !fact.getPeriodEnd().isAfter(fact.getFiled())).toList();
        List<SecFinancialFact> balance = counts.stream().filter(fact -> "us-gaap".equals(fact.getTaxonomy())
                && "CommonStockSharesOutstanding".equals(fact.getTag())).toList();
        List<SecFinancialFact> covers = counts.stream().filter(fact -> "dei".equals(fact.getTaxonomy())
                && "EntityCommonStockSharesOutstanding".equals(fact.getTag())
                && Set.of("10-K", "10-K/A", "10-Q", "10-Q/A").contains(Objects.toString(fact.getForm(), ""))).toList();
        LocalDate balanceDate = balance.stream().map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(LocalDate.MIN);
        LocalDate coverDate = covers.stream().map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(LocalDate.MIN);
        return latestShareCount(coverDate.isAfter(balanceDate) ? covers : balance);
    }

    private SecFinancialFact latestShareCount(List<SecFinancialFact> counts) {
        SecFinancialFact latest = counts.stream().max(Comparator.comparing(SecFinancialFact::getPeriodEnd)
                .thenComparing(SecFinancialFact::getFiled)).orElse(null);
        if (latest == null || latest.getValue().signum() <= 0) return null;
        List<SecFinancialFact> versions = counts.stream().filter(fact -> latest.getPeriodEnd().equals(fact.getPeriodEnd())
                && latest.getFiled().equals(fact.getFiled())).toList();
        if (versions.stream().anyMatch(fact -> fact.getValue().compareTo(latest.getValue()) != 0)) return null;
        return versions.stream()
                .min(Comparator.comparing(SecFinancialFact::getAccession)).orElse(null);
    }

    private Flow filingClassEps(Stock stock, List<ClassFact> disclosures, List<SecFinancialFact> companyFacts, LocalDate day, Instant calculatedAt) {
        java.util.regex.Matcher namedClass = NAMED_SHARE_CLASS.matcher(Objects.toString(stock.getLongName(), ""));
        if (!namedClass.find()) return null;
        String stockClass = namedClass.group(1);
        List<SecFinancialFact> eligible = new ArrayList<>();
        for (ClassFact disclosure : disclosures) {
            if (!"EarningsPerShareDiluted".equals(disclosure.tag()) || !stockClass.equalsIgnoreCase(disclosure.shareClass())
                    || disclosure.filed().isAfter(calculatedAt.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate())
                    || disclosure.reportedEnd() == null || !Objects.equals(stock.getCurrency(), disclosure.unit())
                    || !Set.of("10-K", "10-K/A", "10-Q", "10-Q/A").contains(disclosure.form())
                    || disclosure.quarters() < 1 || disclosure.quarters() > 4) continue;
            Set<List<LocalDate>> dates = new HashSet<>();
            for (SecFinancialFact companyFact : companyFacts) {
                if (!disclosure.accession().equals(companyFact.getAccession()) || companyFact.getPeriodStart() == null
                        || !roundedMonthEnd(companyFact.getPeriodEnd()).equals(disclosure.roundedEnd())) continue;
                long days = java.time.temporal.ChronoUnit.DAYS.between(companyFact.getPeriodStart(), companyFact.getPeriodEnd()) + 1;
                if (Math.round(days / (365.25 / 4)) == disclosure.quarters()) dates.add(List.of(companyFact.getPeriodStart(), companyFact.getPeriodEnd()));
            }
            if (dates.size() != 1) continue;
            List<LocalDate> period = dates.iterator().next();
            SecFinancialFact fact = new SecFinancialFact();
            fact.setCik(stock.getCik());
            fact.setTaxonomy("us-gaap");
            fact.setTag("EarningsPerShareDiluted");
            fact.setUnit(stock.getCurrency() + "/shares");
            fact.setAccession(disclosure.accession());
            fact.setFiled(disclosure.filed());
            fact.setForm(disclosure.form());
            fact.setPeriodEnd(period.getLast());
            fact.setPeriodStart(period.getFirst());
            fact.setAvailableFromDate(calendar.getNextTradingDay(disclosure.filed()));
            fact.setObservedAt(calculatedAt);
            fact.setValue(disclosure.value());
            eligible.add(fact);
        }
        return eligible.isEmpty() ? null : secCalculator.dilutedEps(eligible);
    }

    private SecFinancialFact filingClassShares(Stock stock, List<ClassFact> disclosures, LocalDate day, Instant calculatedAt) {
        java.util.regex.Matcher namedClass = NAMED_SHARE_CLASS.matcher(Objects.toString(stock.getLongName(), ""));
        if (!namedClass.find()) return null;
        List<SecFinancialFact> eligible = new ArrayList<>();
        for (ClassFact disclosure : disclosures) {
            if (!"CommonStockSharesOutstanding".equals(disclosure.tag()) || disclosure.quarters() != 0 || !"shares".equals(disclosure.unit())
                    || !namedClass.group(1).equalsIgnoreCase(disclosure.shareClass()) || !disclosure.filed().isBefore(day)
                    || disclosure.reportedEnd() == null || !disclosure.roundedEnd().equals(roundedMonthEnd(disclosure.reportedEnd()))
                    || !Set.of("10-K", "10-K/A", "10-Q", "10-Q/A").contains(disclosure.form())) continue;
            LocalDate available = calendar.getNextTradingDay(disclosure.filed());
            if (available.isAfter(day) || disclosure.reportedEnd().isAfter(disclosure.filed())) continue;
            SecFinancialFact fact = new SecFinancialFact();
            fact.setAccession(disclosure.accession());
            fact.setFiled(disclosure.filed());
            fact.setPeriodEnd(disclosure.reportedEnd());
            fact.setAvailableFromDate(available);
            fact.setObservedAt(calculatedAt);
            fact.setValue(disclosure.value());
            eligible.add(fact);
        }
        return latestShareCount(eligible);
    }

    private LocalDate roundedMonthEnd(LocalDate date) {
        LocalDate previous = date.withDayOfMonth(1).minusDays(1);
        LocalDate current = java.time.YearMonth.from(date).atEndOfMonth();
        return date.getDayOfMonth() < current.getDayOfMonth() - date.getDayOfMonth() ? previous : current;
    }

    private Map<String, String> secIssuerCiks(List<ActiveStockWithStatus> targets, List<StockValuationDaily> existing, LocalDate day) {
        Map<String, String> recorded = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (StockValuationDaily row : existing) {
            if (row.getSecFinancialCik() == null) continue;
            String previous = recorded.putIfAbsent(row.getFigi(), row.getSecFinancialCik());
            if (previous != null && !previous.equals(row.getSecFinancialCik())) conflicts.add(row.getFigi());
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (ActiveStockWithStatus target : targets) {
            String figi = target.stock().getFigi();
            if (conflicts.contains(figi) || !SecurityTypePolicy.isOperatingCompanyType(target.stock().getStockType())) continue;
            String current = target.stock().getCik();
            String historical = issuerHistory.cikAt(current, day);
            String cik = historical;
            if (Objects.equals(current, historical) && issuerHistory.predecessors(current).isEmpty()) cik = recorded.getOrDefault(figi, current);
            if (cik != null && !cik.isBlank()) result.put(figi, cik);
        }
        if (!conflicts.isEmpty()) log.warn("SEC valuation issuer identity conflict | figis={}", conflicts);
        return result;
    }

    /** 優先使用與收盤價一致的同日快照市值，否則找股份基準可用的較早觀察；不倒填時間。 */
    private List<StockShareObservation> latestCloseShares(List<ActiveStockWithStatus> targets, Map<String, StockQuote> prices,
                                                           LocalDate day, Instant cutoff, Instant calculatedAt) {
        Map<String, StockShareObservation> selected = new LinkedHashMap<>();
        List<String> figis = targets.stream().map(target -> target.stock().getFigi()).toList();
        for (StockShareObservation row : shareObservations.findLatestClosingSnapshotAt(figis, calendar.getSessionOpenAt(day), cutoff, calculatedAt)) {
            if (sameCloseSnapshot(row, prices.get(row.getFigi()), day, cutoff, calculatedAt)) selected.put(row.getFigi(), row);
        }
        Map<Instant, List<String>> scopes = new LinkedHashMap<>();
        for (ActiveStockWithStatus target : targets) {
            if (selected.containsKey(target.stock().getFigi())) continue;
            Instant through = calculatedAt;
            if (target.status().getSplitDate() != null) {
                Instant covered = target.status().getSplitDate().plusDays(1).atStartOfDay(MarketStatusService.NY_ZONE).toInstant().minusNanos(1000);
                if (covered.isBefore(through)) through = covered;
            }
            scopes.computeIfAbsent(through, key -> new ArrayList<>()).add(target.stock().getFigi());
        }
        for (Map.Entry<Instant, List<String>> scope : scopes.entrySet()) {
            for (StockShareObservation row : shareObservations.findLatestAt(scope.getValue(), scope.getKey())) selected.put(row.getFigi(), row);
        }
        List<String> missingAtClose = figis.stream().filter(figi -> !selected.containsKey(figi)).toList();
        if (!missingAtClose.isEmpty()) {
            for (StockShareObservation row : shareObservations.findLatestAt(missingAtClose, cutoff)) selected.put(row.getFigi(), row);
        }
        List<String> alreadyLatest = scopes.getOrDefault(calculatedAt, List.of());
        List<String> missing = targets.stream().map(target -> target.stock().getFigi()).filter(figi -> !selected.containsKey(figi) && !alreadyLatest.contains(figi)).toList();
        // 無可用舊觀察仍保留最新輸入及原本的覆蓋不足原因，不能把缺值當成功。
        if (!missing.isEmpty()) for (StockShareObservation row : shareObservations.findLatestAt(missing, calculatedAt)) selected.put(row.getFigi(), row);
        return List.copyOf(selected.values());
    }

    private boolean sameCloseSnapshot(StockShareObservation observation, StockQuote quote, LocalDate day, Instant cutoff, Instant calculatedAt) {
        if (observation == null || quote == null || quote.getClosePrice() == null || quote.getClosePrice().signum() <= 0
                || observation.getSnapshotPrice() == null || observation.getSnapshotPrice().signum() <= 0
                || observation.getSnapshotMarketCap() == null || observation.getSnapshotMarketCap().signum() <= 0
                || observation.getSnapshotQuoteTime() == null || observation.getSnapshotQuoteTime().isBefore(calendar.getSessionOpenAt(day))
                || observation.getSnapshotQuoteTime().isAfter(cutoff)
                || observation.getObservedAt().isBefore(cutoff)
                || observation.getSnapshotQuoteTime().isAfter(observation.getObservedAt()) || observation.getObservedAt().isAfter(calculatedAt)
                || !observation.getSnapshotQuoteTime().atZone(MarketStatusService.NY_ZONE).toLocalDate().equals(day)) return false;
        // 按正式行情欄位的八位小數精度核對；不採價格百分比容差或推測拆股比例。
        return observation.getSnapshotPrice().setScale(8, RoundingMode.HALF_UP).compareTo(quote.getClosePrice().setScale(8, RoundingMode.HALF_UP)) == 0;
    }

    private StockValuationDaily valuationRow(ValuationInputs inputs, StockStatus status, List<StockSplit> adjustments,
                                             LocalDate day, Instant cutoff, Instant calculatedAt, boolean useCloseSnapshot, Flows secFinancials) {
        StockQuote quote = inputs.quote();
        StockValuationDaily row;
        if (quote == null || quote.getClosePrice() == null)
            row = unavailableRow(inputs.stock(), quote, day, cutoff, calculatedAt, "CLOSING_PRICE_MISSING");
        else if (quote.getClosePrice().signum() <= 0)
            row = unavailableRow(inputs.stock(), quote, day, cutoff, calculatedAt, "CLOSING_PRICE_INVALID");
        else if (inputs.reports().isEmpty() && secFinancials == null && inputs.classEps() == null)
            row = unavailableRow(inputs.stock(), quote, day, cutoff, calculatedAt, "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        else row = calculate(inputs, status, adjustments, day, cutoff, calculatedAt, useCloseSnapshot, secFinancials);
        if (inputs.financialInstitution()) {
            row.setPfcf(null);
            row.setPfcfReason("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
        }
        if (inputs.financialInstitution() || inputs.securitiesBroker()) {
            row.setEvEbitda(null);
            row.setEvEbitdaReason("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");
        }
        // Futu 與 SEC 都無法補足的財務輸入，使用不偏向任一來源的最終原因碼。
        row.setPeReason(combinedMissingReason(row.getPeReason()));
        row.setPbReason(combinedMissingReason(row.getPbReason()));
        row.setPsReason(combinedMissingReason(row.getPsReason()));
        row.setPfcfReason(combinedMissingReason(row.getPfcfReason()));
        row.setEvEbitdaReason(combinedMissingReason(row.getEvEbitdaReason()));
        return row;
    }

    private String combinedMissingReason(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case "SEC_REVENUE_MISSING" -> "REVENUE_MISSING";
            case "SEC_FCF_COMPONENTS_MISSING" -> "FCF_COMPONENTS_MISSING";
            case "SEC_EPS_TTM_PERIODS_MISSING" -> "EPS_TTM_PERIODS_MISSING";
            case "SEC_TTM_PERIODS_MISSING" -> "TTM_PERIODS_MISSING";
            case "SEC_COMMON_EQUITY_UNRESOLVED" -> "COMMON_EQUITY_UNRESOLVED";
            case "SEC_EBITDA_COMPONENTS_UNRESOLVED" -> "EBITDA_COMPONENTS_UNRESOLVED";
            case "SEC_EV_COMPONENTS_UNRESOLVED" -> "EV_COMPONENTS_UNRESOLVED";
            case "SEC_FINANCIAL_INPUT_MISSING" -> "FINANCIAL_INPUT_MISSING";
            case "SEC_BALANCE_INPUT_MISSING" -> "BALANCE_INPUT_MISSING";
            default -> reason;
        };
    }

    private record ValuationUpdate(StockValuationDaily row, boolean retained) {}

    private LocalDate latestEnd(LocalDate first, LocalDate second) {
        return first == null || second != null && second.isAfter(first) ? second : first;
    }

    private ValuationUpdate reconcile(StockValuationDaily old, StockValuationDaily row, StockStatus status,
                                      List<StockSplit> adjustments, LocalDate day, Instant calculatedAt, Flows secFinancials,
                                      boolean requiresClassShares) {
        String figi = row.getFigi();
        boolean invalidStoredPb = old != null && old.getPb() != null && old.getPb().signum() <= 0;
        boolean invalidStoredPs = old != null && old.getPs() != null && old.getPs().signum() <= 0;
        boolean invalidStoredPfcf = old != null && old.getPfcf() != null && old.getPfcf().signum() <= 0;
        boolean retained = false;
        if (old != null && row.losesAvailableMetric(old) && !confirmedInputsChanged(old, row)) {
            String oldPeInvalidation = null;
            if (old.getPe() != null && row.getPe() == null) {
                LocalDate oldBasis = old.getEpsBasisFromDate() != null ? old.getEpsBasisFromDate() : old.getIncomePeriodEnd();
                oldPeInvalidation = shareBasisReason(status, adjustments, oldBasis, day);
                if (oldPeInvalidation == null && invalidatesPe(row.getPeReason())) oldPeInvalidation = row.getPeReason();
                if (oldPeInvalidation == null && row.getIncomePeriodEnd() != null && old.getIncomePeriodEnd() != null
                        && row.getIncomePeriodEnd().isAfter(old.getIncomePeriodEnd())) oldPeInvalidation = row.getPeReason();
            }
            if ("CLOSING_PRICE_INVALID".equals(row.getPeReason())) {
                log.warn("Previous valuation invalidated | figi={} date={} reason={}", figi, day, row.getPeReason());
            } else {
                boolean revoked = false;
                if (oldPeInvalidation != null || "REPORTING_CURRENCY_MISMATCH".equals(row.getPeReason())) {
                    old.setPe(null);
                    old.setPeReason(oldPeInvalidation != null ? oldPeInvalidation : row.getPeReason());
                    revoked = true;
                }
                boolean nativeMarketCap = "FUTU_CLOSE_SNAPSHOT".equals(old.getMarketCapSource());
                LocalDate oldShareBasis = old.getSecShareBasisDate();
                if (oldShareBasis == null && old.getSharesObservedAt() != null)
                    oldShareBasis = old.getSharesObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
                String oldSharesInvalidation = nativeMarketCap || oldShareBasis == null ? null
                        : splitCoverageReason(status, oldShareBasis, day);
                if (!nativeMarketCap && oldSharesInvalidation == null && old.getSharesObservedAt() != null) {
                    LocalDate basis = oldShareBasis;
                    for (StockSplit split : adjustments) {
                        LocalDate from = basis.isBefore(day) ? basis : day;
                        LocalDate through = basis.isAfter(day) ? basis : day;
                        if (split.getExecutionDate().isAfter(from) && !split.getExecutionDate().isAfter(through)
                                && (old.getCalculatedAt() == null || split.getObservedAt() == null || split.getObservedAt().isAfter(old.getCalculatedAt()))) {
                            oldSharesInvalidation = "SHARE_SPLIT_BASIS_CHANGED";
                            break;
                        }
                    }
                }
                if (!nativeMarketCap && oldSharesInvalidation == null && "SPLIT_SYNC_ERROR".equals(row.getPbReason())) oldSharesInvalidation = row.getPbReason();
                if (requiresClassShares && old.getMarketCapSource() != null) {
                    oldSharesInvalidation = "VALUATION_DATE_SHARES_MISSING";
                    old.setMarketCapSource(null);
                    old.setSharesObservedAt(null);
                    old.setSecShareAccession(null);
                    old.setSecShareBasisDate(null);
                }
                if (oldSharesInvalidation != null) {
                    old.setPb(null);
                    old.setPbReason(oldSharesInvalidation);
                    old.setPs(null);
                    old.setPsReason(oldSharesInvalidation);
                    old.setPfcf(null);
                    old.setPfcfReason("PFCF_NOT_APPLICABLE_TO_INDUSTRY".equals(row.getPfcfReason()) ? row.getPfcfReason() : oldSharesInvalidation);
                    old.setEvEbitda(null);
                    old.setEvEbitdaReason("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY".equals(row.getEvEbitdaReason()) ? row.getEvEbitdaReason() : oldSharesInvalidation);
                    revoked = true;
                } else {
                    if (invalidatesFinancialMetric(row.getPbReason())) {
                        old.setPb(null);
                        old.setPbReason(row.getPbReason());
                        revoked = true;
                    }
                    if (invalidatesFinancialMetric(row.getPsReason()) || invalidatesSecMetric(old.getPsFinancialSource(), row.getPsReason(),
                            old.getPsPeriodEnd(), secFinancials == null ? null : secFinancials.revenue())) {
                        old.setPs(null);
                        old.setPsReason(row.getPsReason());
                        revoked = true;
                    }
                    if ("PFCF_NOT_APPLICABLE_TO_INDUSTRY".equals(row.getPfcfReason())
                            || invalidatesFinancialMetric(row.getPfcfReason()) || invalidatesSecMetric(old.getPfcfFinancialSource(), row.getPfcfReason(),
                            old.getPfcfPeriodEnd(), secFinancials == null ? null : secFinancials.freeCashFlow())) {
                        old.setPfcf(null);
                        old.setPfcfReason(row.getPfcfReason());
                        revoked = true;
                    }
                    if (invalidatesEv(row.getEvEbitdaReason())) {
                        old.setEvEbitda(null);
                        old.setEvEbitdaReason(row.getEvEbitdaReason());
                        revoked = true;
                    }
                }
                // 已存非正倍數本身已違反 N.M. 規則，原料暫缺也不能把它保留為可用。
                if (invalidStoredPb) {
                    old.setPb(null);
                    old.setPbReason("NON_POSITIVE_COMMON_EQUITY");
                    revoked = true;
                }
                if (invalidStoredPs) {
                    old.setPs(null);
                    old.setPsReason("NON_POSITIVE_REVENUE");
                    revoked = true;
                }
                if (invalidStoredPfcf && !"PFCF_NOT_APPLICABLE_TO_INDUSTRY".equals(row.getPfcfReason())) {
                    old.setPfcf(null);
                    old.setPfcfReason("NON_POSITIVE_FCF");
                    revoked = true;
                }
                boolean refreshed = refreshReports(old, row);
                boolean updated = mergeAvailableMetrics(old, row);
                if (revoked || updated || refreshed) old.setCalculatedAt(calculatedAt);
                // 新整列能涵蓋仍可信的舊指標時採新計算，否則只撤資格並保留舊批次來源時間。
                if (!revoked || !row.hasAvailableMetric() || row.losesAvailableMetric(old)) row = old;
                retained = !revoked && !updated && !refreshed;
            }
        }
        return new ValuationUpdate(row, retained);
    }

    /** 新報期只取代相應指標，即使該期尚不能算出倍數。 */
    private boolean refreshReports(StockValuationDaily old, StockValuationDaily next) {
        boolean pe = isNewerPeriod(next.getIncomePeriodEnd(), old.getIncomePeriodEnd());
        boolean pb = isNewerPeriod(next.getBalancePeriodEnd(), old.getBalancePeriodEnd());
        boolean ps = isNewerPeriod(next.getPsPeriodEnd(), old.getPsPeriodEnd());
        boolean fcf = isNewerPeriod(next.getPfcfPeriodEnd(), old.getPfcfPeriodEnd());
        // 存量可用 EV/EBITDA 的兩個輸入在同一期；任一份新報表都可能令舊倍數失效。
        boolean ev = isNewerPeriod(next.getEvEbitdaPeriodEnd(), old.getEvEbitdaPeriodEnd())
                || isNewerPeriod(next.getBalancePeriodEnd(), old.getEvEbitdaPeriodEnd());
        if (pe) {
            old.setPe(next.getPe());
            old.setPeReason(next.getPeReason());
            old.setPeFinancialSource(next.getPeFinancialSource());
            old.setIncomePeriodEnd(next.getIncomePeriodEnd());
            old.setEpsBasisFromDate(next.getEpsBasisFromDate());
        }
        if (pb) {
            old.setPb(next.getPb());
            old.setPbReason(next.getPbReason());
            old.setPbFinancialSource(next.getPbFinancialSource());
            old.setBalancePeriodEnd(next.getBalancePeriodEnd());
        }
        if (ps) {
            old.setPs(next.getPs());
            old.setPsReason(next.getPsReason());
            old.setPsFinancialSource(next.getPsFinancialSource());
            old.setPsPeriodStart(next.getPsPeriodStart());
            old.setPsPeriodEnd(next.getPsPeriodEnd());
        }
        if (fcf) {
            old.setPfcf(next.getPfcf());
            old.setPfcfReason(next.getPfcfReason());
            old.setPfcfFinancialSource(next.getPfcfFinancialSource());
            old.setPfcfPeriodStart(next.getPfcfPeriodStart());
            old.setPfcfPeriodEnd(next.getPfcfPeriodEnd());
        }
        if (ev) {
            old.setEvEbitda(next.getEvEbitda());
            old.setEvEbitdaReason(next.getEvEbitdaReason());
            old.setEvEbitdaFinancialSource(next.getEvEbitdaFinancialSource());
            old.setEvEbitdaPeriodEnd(next.getEvEbitdaPeriodEnd());
        }
        if (!pe && !pb && !ps && !fcf && !ev) return false;
        if (next.getSecFinancialCik() != null) old.setSecFinancialCik(next.getSecFinancialCik());
        if (isNewerPeriod(next.getSecFinancialAvailableFromDate(), old.getSecFinancialAvailableFromDate())
                || old.getSecFinancialAvailableFromDate() == null)
            old.setSecFinancialAvailableFromDate(next.getSecFinancialAvailableFromDate());
        if (next.getFinancialObservedAt() != null
                && (old.getFinancialObservedAt() == null || next.getFinancialObservedAt().isAfter(old.getFinancialObservedAt())))
            old.setFinancialObservedAt(next.getFinancialObservedAt());
        return true;
    }

    private boolean isNewerPeriod(LocalDate next, LocalDate previous) {
        return next != null && previous != null && next.isAfter(previous);
    }

    private boolean invalidatesPe(String reason) {
        return Set.of("NON_POSITIVE_EPS", "EPS_SPLIT_BASIS_UNCONFIRMED", "SPLIT_SYNC_ERROR", "SPLIT_COVERAGE_INSUFFICIENT",
                "FINANCIAL_CONCEPT_VALUE_CONFLICT", "FINANCIAL_PERIOD_CONFLICT").contains(reason == null ? "" : reason);
    }

    private boolean invalidatesFinancialMetric(String reason) {
        return Set.of("FINANCIAL_CONCEPT_VALUE_CONFLICT", "FINANCIAL_PERIOD_CONFLICT", "REPORTING_CURRENCY_MISMATCH",
                "VALUATION_DENOMINATOR_ZERO", "NON_POSITIVE_COMMON_EQUITY", "NON_POSITIVE_REVENUE", "NON_POSITIVE_FCF").contains(Objects.toString(reason, ""));
    }

    private boolean invalidatesSecMetric(String source, String reason) {
        return usesSec(source) && Set.of("SEC_FACT_VERSION_CONFLICT", "SEC_TTM_PERIOD_CONFLICT", "SEC_REVENUE_SCOPE_CONFLICT",
                "SEC_REVENUE_NET_SCOPE_UNVERIFIED", "SEC_FCF_SCOPE_CONFLICT", "SEC_CAPITAL_EXPENDITURE_SIGN_UNVERIFIED").contains(Objects.toString(reason, ""));
    }

    private boolean invalidatesSecMetric(String source, String reason, LocalDate oldEnd, Flow candidate) {
        return invalidatesSecMetric(source, reason) && (oldEnd == null || candidate != null && oldEnd.equals(candidate.periodEnd()));
    }

    /** 同一收盤價、股數基準與已知報期下合併新算出的指標，避免某一份報表暫缺時丟失另一個已確認的倍數。 */
    private boolean mergeAvailableMetrics(StockValuationDaily old, StockValuationDaily next) {
        boolean sameCapital = old.getClosePrice() != null && next.getClosePrice() != null && old.getClosePrice().compareTo(next.getClosePrice()) == 0
                && old.getSharesObservedAt() != null && old.getSharesObservedAt().equals(next.getSharesObservedAt())
                && Objects.equals(old.getSecShareAccession(), next.getSecShareAccession())
                && Objects.equals(old.getMarketCapSource(), next.getMarketCapSource());
        boolean updatePe = sameCapital && old.getPe() == null && next.getPe() != null
                && (old.getIncomePeriodEnd() == null || next.getIncomePeriodEnd() != null
                        && !next.getIncomePeriodEnd().isBefore(old.getIncomePeriodEnd()));
        boolean updatePb = sameCapital && old.getPb() == null && next.getPb() != null
                && (old.getBalancePeriodEnd() == null || next.getBalancePeriodEnd() != null
                        && !next.getBalancePeriodEnd().isBefore(old.getBalancePeriodEnd()));
        boolean updatePs = sameCapital && next.getPs() != null && (usesSec(old.getPsFinancialSource()) || usesSec(next.getPsFinancialSource()));
        updatePs |= usesSec(next.getPsFinancialSource()) && ("VALUATION_DENOMINATOR_ZERO".equals(next.getPsReason())
                || "NON_POSITIVE_REVENUE".equals(next.getPsReason()));
        boolean updateFcf = sameCapital && next.getPfcf() != null && (usesSec(old.getPfcfFinancialSource()) || usesSec(next.getPfcfFinancialSource()));
        updateFcf |= usesSec(next.getPfcfFinancialSource()) && "NON_POSITIVE_FCF".equals(next.getPfcfReason());
        boolean updateEvEbitda = sameCapital && next.getEvEbitda() != null && old.getEvEbitda() == null;
        if (!updatePe && !updatePb && !updatePs && !updateFcf && !updateEvEbitda) return false;
        Instant oldFutuTime = futuInputTime(old);
        Instant nextFutuTime = futuInputTime(next);
        if ((updatePe || updatePb) && nextFutuTime != null && (oldFutuTime == null || nextFutuTime.isAfter(oldFutuTime)))
            oldFutuTime = nextFutuTime;
        if (updatePe) {
            old.setPe(next.getPe());
            old.setPeReason(null);
            old.setPeFinancialSource(next.getPeFinancialSource());
            old.setIncomePeriodEnd(next.getIncomePeriodEnd());
            old.setEpsBasisFromDate(next.getEpsBasisFromDate());
        }
        if (updatePb) {
            old.setPb(next.getPb());
            old.setPbReason(null);
            old.setPbFinancialSource(next.getPbFinancialSource());
            old.setBalancePeriodEnd(next.getBalancePeriodEnd());
        }
        if (updatePs) {
            old.setPs(next.getPs());
            old.setPsReason(next.getPsReason());
            old.setPsFinancialSource(next.getPsFinancialSource());
            old.setPsPeriodStart(next.getPsPeriodStart());
            old.setPsPeriodEnd(next.getPsPeriodEnd());
        }
        if (updateFcf) {
            old.setPfcf(next.getPfcf());
            old.setPfcfReason(next.getPfcfReason());
            old.setPfcfFinancialSource(next.getPfcfFinancialSource());
            old.setPfcfPeriodStart(next.getPfcfPeriodStart());
            old.setPfcfPeriodEnd(next.getPfcfPeriodEnd());
        }
        if (updateEvEbitda) {
            old.setEvEbitda(next.getEvEbitda());
            old.setEvEbitdaReason(null);
            old.setEvEbitdaFinancialSource(next.getEvEbitdaFinancialSource());
            old.setEvEbitdaPeriodEnd(next.getEvEbitdaPeriodEnd());
        }
        old.setFutuFinancialObservedAt(oldFutuTime);
        if (Arrays.asList(old.getPeFinancialSource(), old.getPbFinancialSource(), old.getPsFinancialSource(), old.getPfcfFinancialSource(), old.getEvEbitdaFinancialSource()).stream().anyMatch(this::usesSec)) {
            if (next.getSecFinancialCik() != null) old.setSecFinancialCik(next.getSecFinancialCik());
            if (next.getSecFinancialAvailableFromDate() != null && (old.getSecFinancialAvailableFromDate() == null
                    || next.getSecFinancialAvailableFromDate().isAfter(old.getSecFinancialAvailableFromDate()))) old.setSecFinancialAvailableFromDate(next.getSecFinancialAvailableFromDate());
            if (next.getFinancialObservedAt() != null && (old.getFinancialObservedAt() == null || next.getFinancialObservedAt().isAfter(old.getFinancialObservedAt())))
                old.setFinancialObservedAt(next.getFinancialObservedAt());
        } else {
            old.setSecFinancialCik(null);
            old.setSecFinancialAvailableFromDate(null);
            old.setFinancialObservedAt(oldFutuTime);
        }
        return true;
    }

    private boolean usesSec(String source) {
        return "SEC".equals(source) || "FUTU_AND_SEC".equals(source);
    }

    private Instant futuInputTime(StockValuationDaily row) {
        return row.getFutuFinancialObservedAt() != null ? row.getFutuFinancialObservedAt()
                : row.getSecFinancialAvailableFromDate() == null && row.getSecFinancialCik() == null
                    ? row.getFinancialObservedAt() : null;
    }

    private boolean invalidatesEv(String reason) {
        return invalidatesFinancialMetric(reason) || Set.of("EV_FINANCING_SCOPE_UNRESOLVED", "EV_LEASE_SCOPE_UNRESOLVED",
                "EV_ACCOUNTING_STANDARD_UNCONFIRMED", "EBITDA_FINANCE_COST_SCOPE_UNRESOLVED",
                "PREFERRED_EQUITY_MARKET_VALUE_MISSING", "NONCONTROLLING_INTEREST_MARKET_VALUE_MISSING", "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "NON_POSITIVE_EBITDA",
                "SEC_EBITDA_COMPONENTS_UNRESOLVED", "EBITDA_COMPONENTS_UNRESOLVED", SecFinancialFactsCalculator.DEPRECIATION_SCOPE_UNRESOLVED, EDGAR_CACHE_NOT_READY)
                .contains(Objects.toString(reason, ""));
    }

    private boolean confirmedInputsChanged(StockValuationDaily old, StockValuationDaily next) {
        Instant previousFutu = futuInputTime(old);
        Instant currentFutu = futuInputTime(next);
        if (previousFutu != null && currentFutu != null && currentFutu.isAfter(previousFutu)) return true;
        if (next.getSecShareAccession() != null && !next.getSecShareAccession().equals(old.getSecShareAccession())) return true;
        if (next.getSharesObservedAt() != null && old.getSharesObservedAt() != null
                && (!next.getSharesObservedAt().equals(old.getSharesObservedAt()) || old.getMarketCapSource() != null
                    && !Objects.equals(old.getMarketCapSource(), next.getMarketCapSource()))) return true;
        BigDecimal[] previous = {old.getClosePrice(), old.getPe(), old.getPb(), old.getPs(), old.getPfcf(), old.getEvEbitda()};
        BigDecimal[] current = {next.getClosePrice(), next.getPe(), next.getPb(), next.getPs(), next.getPfcf(), next.getEvEbitda()};
        for (int index = 0; index < previous.length; index++) {
            if (index == 3 && (usesSec(old.getPsFinancialSource()) || usesSec(next.getPsFinancialSource()))) continue;
            if (index == 4 && (usesSec(old.getPfcfFinancialSource()) || usesSec(next.getPfcfFinancialSource()))) continue;
            if (previous[index] != null && current[index] != null && previous[index].compareTo(current[index]) != 0) return true;
        }
        return false;
    }

    private StockValuationDaily unavailableRow(Stock stock, StockQuote quote, LocalDate day,
                                              Instant cutoff, Instant calculatedAt, String reason) {
        StockValuationDaily row = new StockValuationDaily();
        row.setFigi(stock.getFigi());
        row.setValuationDate(day);
        row.setCalculatedAt(calculatedAt);
        row.setValuationCutoff(cutoff);
        row.setCurrency(stock.getCurrency());
        row.setClosePrice(quote == null ? null : quote.getClosePrice());
        row.setPeReason(reason);
        row.setPbReason(reason);
        row.setPsReason(reason);
        row.setPfcfReason(reason);
        row.setEvEbitdaReason(reason);
        return row;
    }

    private StockValuationDaily calculate(ValuationInputs inputs, StockStatus status, List<StockSplit> adjustments,
                                          LocalDate day, Instant cutoff, Instant calculatedAt, boolean useCloseSnapshot, Flows secFinancials) {
        Stock stock = inputs.stock();
        String figi = stock.getFigi();
        StockQuote quote = inputs.quote();
        List<ReportValues> financials = inputs.reports();
        StockShareObservation observation = inputs.shares();
        SecFinancialFact filingShares = inputs.filingShares();
        boolean multiSecurityIssuer = inputs.requiresClassShares();
        Flow classEps = inputs.classEps();
        boolean futuEpsBasisUnconfirmed = splitBetween(adjustments, day, calculatedAt.atZone(MarketStatusService.NY_ZONE).toLocalDate())
                && day.isBefore(calendar.getLastClosedTradingDay(calculatedAt));
        ReportValues income = financials.stream().filter(row -> row.statementType() == 1)
                .max(Comparator.comparing(ReportValues::periodEnd).thenComparingInt(ReportValues::periodType)).orElse(null);
        MetricResult<BigDecimal> eps = income == null ? null : futuEpsBasisUnconfirmed ? missing("EPS_SPLIT_BASIS_UNCONFIRMED")
                : calculator.trailingTwelveMonths(financials, figi, 1, FinancialConcept.DILUTED_EPS, income.periodEnd(), income.currency());
        LocalDate epsBasisFrom = income == null ? null : calculator.trailingBasisFromDate(financials, figi, 1, income.periodEnd());
        Flow issuerEps = secFinancials == null ? null : secFinancials.dilutedEpsTtm();
        Flow secEps = classEps != null ? classEps : issuerEps;
        ReportValues epsPeriod = income;
        List<Flow> mixedEpsInputs = new ArrayList<>();
        boolean newerSecQuarter = income != null && issuerEps != null && issuerEps.periodEnd() != null
                && issuerEps.periodEnd().isAfter(income.periodEnd()) && secFinancials != null
                && secFinancials.missingQuarters().stream().anyMatch(report -> report.statementType() == 1
                        && report.periodEnd().equals(issuerEps.periodEnd()));
        if ((eps == null || eps.value() == null || newerSecQuarter) && !futuEpsBasisUnconfirmed && !multiSecurityIssuer && income != null
                && sameCurrency(stock.getCurrency(), income.currency()) && secFinancials != null) {
            // SEC 最新單季若比已知的 Futu 財報更新，只有在缺季序列可精確接續時才延伸 EPS 的報期。
            ReportValues latest = secFinancials.missingQuarters().stream().filter(report -> report.statementType() == 1
                    && issuerEps != null && issuerEps.periodEnd() != null
                    && report.periodEnd().equals(issuerEps.periodEnd()) && report.periodEnd().isAfter(income.periodEnd()))
                    .max(Comparator.comparing(ReportValues::periodEnd)).orElse(income);
            List<ReportValues> combined = supplementFlowReports(financials, latest, secFinancials, mixedEpsInputs,
                    List.of(FinancialConcept.DILUTED_EPS));
            eps = trailing(combined, latest, FinancialConcept.DILUTED_EPS, income.currency());
            if (eps.value() == null) mixedEpsInputs.clear();
            else {
                epsBasisFrom = calculator.trailingBasisFromDate(combined, figi, 1, latest.periodEnd());
                epsPeriod = latest;
            }
        }
        // 缺季混合不是獨立股份基準；若 SEC 已有同報期完整 TTM，優先採用一致的四季 SEC EPS。
        boolean completeSecInsteadOfMixed = !mixedEpsInputs.isEmpty() && secEps != null && secEps.value() != null
                && epsPeriod != null && epsPeriod.periodEnd().equals(secEps.periodEnd());
        boolean useSecEps = (!multiSecurityIssuer || classEps != null)
                && (completeSecInsteadOfMixed || preferSec(secEps, eps, epsPeriod));
        if (useSecEps) {
            eps = secMetric(secEps);
            epsBasisFrom = secEps.epsBasisFrom();
        }
        String peReason = eps == null ? "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE" : reason(eps);
        if (eps == null && secEps != null) {
            if (multiSecurityIssuer && secEps.periodEnd() != null) peReason = "SEC_SHARE_CLASS_EPS_UNVERIFIED";
            else if (secEps.reason() != null) peReason = secEps.reason();
        }
        if (peReason == null) peReason = shareBasisReason(status, adjustments, epsBasisFrom, day);
        String currency = stock.getCurrency();
        if (!useSecEps && income != null && !sameCurrency(currency, income.currency())) peReason = "REPORTING_CURRENCY_MISMATCH";
        LocalDate peEnd = useSecEps ? secEps.periodEnd() : epsPeriod == null ? null : epsPeriod.periodEnd();
        if (multiSecurityIssuer && issuerEps != null && issuerEps.periodEnd() != null
                && (peEnd == null || issuerEps.periodEnd().isAfter(peEnd))) {
            peReason = "SEC_SHARE_CLASS_EPS_UNVERIFIED";
            peEnd = issuerEps.periodEnd();
            useSecEps = false;
            epsBasisFrom = null;
        }
        BigDecimal shares = null;
        BigDecimal marketCap = null;
        String marketCapSource = null;
        String sharesReason = "VALUATION_DATE_SHARES_MISSING";
        boolean useFilingShares = false;
        if (useCloseSnapshot && sameCloseSnapshot(observation, quote, day, cutoff, calculatedAt)) {
            // 採來源同份、同收盤價的公司市值估算，不把股數取得日當生效日，也不再次套用拆股比例。
            marketCap = observation.getSnapshotMarketCap();
            marketCapSource = "FUTU_CLOSE_SNAPSHOT";
            sharesReason = null;
        } else if (observation != null) {
            LocalDate basis = observation.getObservedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
            sharesReason = splitCoverageReason(status, basis, day);
            if (sharesReason == null) shares = BigDecimal.valueOf(observation.getIssuedShares())
                    .multiply(StockSplitCalculator.shareFactor(adjustments, basis, day));
            if (shares != null) {
                marketCap = quote.getClosePrice().multiply(shares);
                marketCapSource = "OBSERVED_SHARES";
            }
        }
        if (marketCap == null && filingShares != null) {
            LocalDate basis = filingShares.getPeriodEnd();
            sharesReason = splitCoverageReason(status, basis, day);
            if (sharesReason == null && adjustments.stream().anyMatch(split -> split.getExecutionDate().isAfter(basis)
                    && !split.getExecutionDate().isAfter(filingShares.getFiled()))) sharesReason = "SEC_SHARE_SPLIT_BASIS_UNCONFIRMED";
            if (sharesReason == null) {
                marketCap = quote.getClosePrice().multiply(filingShares.getValue())
                        .multiply(StockSplitCalculator.shareFactor(adjustments, basis, day));
                marketCapSource = "OBSERVED_SHARES";
                useFilingShares = true;
            }
        }
        ReportValues balance = financials.stream().filter(value -> value.statementType() == 2)
                .max(Comparator.comparing(ReportValues::periodEnd).thenComparingInt(ReportValues::periodType)).orElse(null);
        ReportValues cashFlow = financials.stream().filter(value -> value.statementType() == 3)
                .max(Comparator.comparing(ReportValues::periodEnd).thenComparingInt(ReportValues::periodType)).orElse(null);
        MetricResult<BigDecimal> equity = balance == null ? missing("BALANCE_SHEET_MISSING")
                : sameCurrency(currency, balance.currency()) ? calculator.commonEquity(balance) : missing("REPORTING_CURRENCY_MISMATCH");
        MetricResult<BigDecimal> revenue = trailing(financials, income, FinancialConcept.REVENUE_AS_REPORTED, currency);
        List<Flow> mixedRevenueInputs = new ArrayList<>();
        if (revenue.value() == null && income != null && sameCurrency(currency, income.currency()) && secFinancials != null) {
            List<ReportValues> combined = supplementFlowReports(financials, income, secFinancials, mixedRevenueInputs,
                    List.of(FinancialConcept.REVENUE_AS_REPORTED));
            revenue = trailing(combined, income, FinancialConcept.REVENUE_AS_REPORTED, currency);
            if (revenue.value() == null) mixedRevenueInputs.clear();
        }
        MetricResult<BigDecimal> fcf = trailing(financials, cashFlow, FinancialConcept.FREE_CASH_FLOW, currency);
        List<Flow> mixedFcfInputs = new ArrayList<>();
        if (fcf.value() == null && cashFlow != null && sameCurrency(currency, cashFlow.currency())) {
            List<ReportValues> combined = supplementFlowReports(financials, cashFlow, secFinancials, mixedFcfInputs,
                    List.of(FinancialConcept.OPERATING_CASH_FLOW, FinancialConcept.CAPITAL_EXPENDITURE));
            fcf = trailing(combined, cashFlow, FinancialConcept.FREE_CASH_FLOW, currency);
            if (fcf.value() == null) mixedFcfInputs.clear();
        }
        Flow secRevenue = secFinancials == null ? null : secFinancials.revenue();
        Flow secFcf = secFinancials == null ? null : secFinancials.freeCashFlow();
        boolean useSecRevenue = preferSec(secRevenue, revenue, income);
        boolean useSecFcf = preferSec(secFcf, fcf, cashFlow);
        if (useSecRevenue) revenue = secMetric(secRevenue);
        else if (revenue.value() == null && hasReportReason(secRevenue, income)) revenue = missing(secRevenue.reason());
        if (useSecFcf) fcf = secMetric(secFcf);
        else if (fcf.value() == null && hasReportReason(secFcf, cashFlow)) fcf = missing(secFcf.reason());
        MetricResult<BigDecimal> ebitda = income == null ? missing("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE")
                : sameCurrency(currency, income.currency()) ? calculator.trailingEbitda(financials, income) : missing("REPORTING_CURRENCY_MISMATCH");
        List<Flow> mixedEbitdaInputs = new ArrayList<>();
        if (ebitda.value() == null && income != null && sameCurrency(currency, income.currency()) && secFinancials != null) {
            List<ReportValues> combined = supplementFlowReports(financials, income, secFinancials, mixedEbitdaInputs,
                    List.of(FinancialConcept.OPERATING_PROFIT, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION));
            ebitda = calculator.trailingEbitda(combined, income);
            if (ebitda.value() == null) mixedEbitdaInputs.clear();
        }
        BigDecimal evMarketCap = multiSecurityIssuer ? inputs.issuerMarketCap() : marketCap;
        MetricResult<BigDecimal> ev = balance == null ? missing("BALANCE_SHEET_MISSING")
                : !sameCurrency(currency, balance.currency()) ? missing("REPORTING_CURRENCY_MISMATCH")
                : calculator.enterpriseValue(evMarketCap, balance);
        List<Flow> mixedEvInputs = new ArrayList<>();
        if (ev.value() == null && balance != null && sameCurrency(currency, balance.currency()) && secFinancials != null) {
            ReportValues combined = supplementBalance(balance, secFinancials, mixedEvInputs);
            ev = calculator.enterpriseValue(evMarketCap, combined);
            if (ev.value() == null) mixedEvInputs.clear();
        }
        Flow secEquity = secFinancials == null ? null : secFinancials.equity();
        Flow secEvAdjustment = secFinancials == null ? null : secFinancials.enterpriseValueAdjustment();
        Flow secEbitda = secFinancials == null ? null : secFinancials.ebitda();
        boolean useSecEquity = preferSec(secEquity, equity, balance);
        List<Flow> mixedEquityInputs = new ArrayList<>();
        if (equity.value() == null && balance != null && sameCurrency(currency, balance.currency()) && secFinancials != null) {
            equity = calculator.commonEquity(supplementBalance(balance, secFinancials, mixedEquityInputs));
            if (equity.value() == null) mixedEquityInputs.clear();
            else useSecEquity = preferSec(secEquity, equity, balance);
        }
        if (useSecEquity) equity = secMetric(secEquity);
        else if (equity.value() == null && hasReportReason(secEquity, balance))
            equity = missing(secEquity.reason());
        boolean useSecEnterprise = preferSec(secEvAdjustment, ev, balance);
        boolean useSecEbitda = preferSec(secEbitda, ebitda, income);
        if (useSecEnterprise) {
            ev = secEvAdjustment.value() == null ? secMetric(secEvAdjustment)
                    : evMarketCap == null ? missing("VALUATION_DATE_SHARES_MISSING") : MetricResult.available(evMarketCap.add(secEvAdjustment.value()));
            mixedEvInputs.clear();
        }
        if (useSecEbitda) {
            ebitda = secMetric(secEbitda);
            mixedEbitdaInputs.clear();
        }
        else if (ebitda.value() == null && hasReportReason(secEbitda, income))
            ebitda = missing(secEbitda.reason());
        boolean needsDepreciationProof = SecFinancialFactsCalculator.DEPRECIATION_SCOPE_UNRESOLVED.equals(reason(ebitda));
        LocalDate evBalanceEnd = useSecEnterprise ? secEvAdjustment.periodEnd() : balance == null ? null : balance.periodEnd();
        LocalDate ebitdaEnd = useSecEbitda ? secEbitda.periodEnd() : income == null ? null : income.periodEnd();
        if (ev.value() != null && ebitda.value() != null && !Objects.equals(evBalanceEnd, ebitdaEnd))
            ev = missing("FINANCIAL_PERIOD_CONFLICT");
        // 多股類只使用已核對全部股類及 SEC 公司總股數的發行人市值，不以單一股類代替。
        BigDecimal companyMarketCap = evMarketCap;
        if (multiSecurityIssuer && companyMarketCap != null) {
            sharesReason = null;
            marketCapSource = "SEC_ISSUER_CLASS_SHARES";
        } else if (multiSecurityIssuer && marketCap != null) sharesReason = "ISSUER_MARKET_CAP_UNRESOLVED";
        FinancialReportValuationCalculator.MarketMultiples multiples = calculator.calculate(new FinancialReportValuationCalculator.ValuationInputs(
                day, quote.getClosePrice(), currency, currency, companyMarketCap, peReason == null ? eps.value() : null,
                equity.value(), revenue.value(), fcf.value(), ev.value(), ebitda.value()));
        StockValuationDaily row = new StockValuationDaily();
        row.setFigi(figi);
        row.setValuationDate(day);
        row.setCalculatedAt(calculatedAt);
        row.setValuationCutoff(cutoff);
        row.setFinancialObservedAt(inputs.futuObservedAt());
        row.setFutuFinancialObservedAt(row.getFinancialObservedAt());
        row.setPsFinancialSource(useSecRevenue ? "SEC" : mixedRevenueInputs.isEmpty() ? "FUTU" : "FUTU_AND_SEC");
        row.setPsPeriodStart(useSecRevenue ? secRevenue.periodStart() : null);
        row.setPsPeriodEnd(useSecRevenue ? secRevenue.periodEnd() : income == null ? null : income.periodEnd());
        row.setPfcfFinancialSource(useSecFcf ? "SEC" : mixedFcfInputs.isEmpty() ? "FUTU" : "FUTU_AND_SEC");
        row.setPfcfPeriodStart(useSecFcf ? secFcf.periodStart() : null);
        row.setPfcfPeriodEnd(useSecFcf ? secFcf.periodEnd() : cashFlow == null ? null : cashFlow.periodEnd());
        if (useSecRevenue) recordSecTiming(row, secRevenue);
        else for (Flow input : mixedRevenueInputs) recordSecTiming(row, input);
        if (useSecFcf) recordSecTiming(row, secFcf);
        if (useSecEps) recordSecTiming(row, secEps);
        else for (Flow input : mixedEpsInputs) recordSecTiming(row, input);
        if (useSecEquity) recordSecTiming(row, secEquity);
        if (useSecEnterprise) recordSecTiming(row, secEvAdjustment);
        if (useSecEbitda) recordSecTiming(row, secEbitda);
        for (Flow input : mixedEvInputs) recordSecTiming(row, input);
        for (Flow input : mixedEbitdaInputs) recordSecTiming(row, input);
        for (Flow input : mixedFcfInputs) recordSecTiming(row, input);
        for (Flow input : mixedEquityInputs) recordSecTiming(row, input);
        if (useSecEps || useSecEquity || useSecRevenue || useSecFcf || useSecEnterprise || useSecEbitda || !mixedEvInputs.isEmpty()
                || !mixedEbitdaInputs.isEmpty() || !mixedFcfInputs.isEmpty() || !mixedEquityInputs.isEmpty()
                || !mixedRevenueInputs.isEmpty() || !mixedEpsInputs.isEmpty()) row.setSecFinancialCik(secFinancials == null ? stock.getCik() : secFinancials.cik());
        row.setPeFinancialSource(useSecEps ? "SEC" : mixedEpsInputs.isEmpty() ? "FUTU" : "FUTU_AND_SEC");
        row.setPbFinancialSource(useSecEquity ? "SEC" : mixedEquityInputs.isEmpty() ? "FUTU" : "FUTU_AND_SEC");
        boolean mixedEv = !mixedEvInputs.isEmpty() || !mixedEbitdaInputs.isEmpty() || useSecEnterprise || useSecEbitda;
        row.setEvEbitdaFinancialSource(useSecEnterprise && useSecEbitda ? "SEC" : mixedEv ? "FUTU_AND_SEC" : "FUTU");
        row.setBalancePeriodEnd(useSecEquity ? secEquity.periodEnd() : balance == null ? null : balance.periodEnd());
        row.setEvEbitdaPeriodEnd(ebitdaEnd);
        row.setIncomePeriodEnd(peEnd);
        row.setEpsBasisFromDate(epsBasisFrom);
        if (useFilingShares) {
            row.setSharesObservedAt(filingShares.getObservedAt());
            row.setSecShareBasisDate(filingShares.getPeriodEnd());
            row.setSecShareAccession(filingShares.getAccession());
        } else if (marketCap != null) row.setSharesObservedAt(observation.getObservedAt());
        row.setMarketCapSource(marketCapSource);
        row.setCurrency(currency);
        row.setClosePrice(quote.getClosePrice());
        row.setPe(multiples.pe().value());
        row.setPeReason(row.getPe() != null ? null : peReason != null ? peReason : reason(multiples.pe()));
        row.setPb(multiples.pb().value());
        String pbReason = reason(multiples.pb());
        row.setPbReason(row.getPb() != null ? null : "NON_POSITIVE_COMMON_EQUITY".equals(pbReason) ? pbReason
                : sharesReason != null ? sharesReason : equity.value() == null ? reason(equity) : pbReason);
        row.setPs(multiples.ps().value());
        String psReason = reason(multiples.ps());
        String revenueFailure = reason(revenue);
        row.setPsReason(row.getPs() != null ? null : "NON_POSITIVE_REVENUE".equals(psReason) ? psReason
                : invalidatesFinancialMetric(revenueFailure) || invalidatesSecMetric("SEC", revenueFailure) ? revenueFailure
                : sharesReason != null ? sharesReason : revenue.value() == null ? revenueFailure : psReason);
        row.setPfcf(multiples.pfcf().value());
        String pfcfReason = reason(multiples.pfcf());
        String fcfFailure = reason(fcf);
        row.setPfcfReason(row.getPfcf() != null ? null : "NON_POSITIVE_FCF".equals(pfcfReason) ? pfcfReason
                : invalidatesFinancialMetric(fcfFailure) || invalidatesSecMetric("SEC", fcfFailure) ? fcfFailure
                : sharesReason != null ? sharesReason : fcf.value() == null ? fcfFailure : pfcfReason);
        row.setEvEbitda(multiples.evEbitda().value());
        String evFailure = reason(ev);
        String ebitdaFailure = reason(ebitda);
        String evMetricReason = reason(multiples.evEbitda());
        row.setEvEbitdaReason(row.getEvEbitda() != null ? null : "NON_POSITIVE_EBITDA".equals(evMetricReason) ? evMetricReason
                : needsDepreciationProof ? ebitdaFailure : invalidatesEv(evFailure) ? evFailure
                : invalidatesEv(ebitdaFailure) ? ebitdaFailure : evMarketCap == null && sharesReason != null ? sharesReason
                : ev.value() == null ? reason(ev) : ebitda.value() == null ? reason(ebitda) : reason(multiples.evEbitda()));
        return row;
    }

    private boolean preferSec(Flow sec, MetricResult<BigDecimal> existing, ReportValues current) {
        if (sec == null || sec.periodEnd() == null) return false;
        if (current == null || sec.periodEnd().isAfter(current.periodEnd())) return true;
        if (sec.periodEnd().isBefore(current.periodEnd())) return false;
        return sec.value() != null && (existing == null || existing.value() == null);
    }

    private boolean hasReportReason(Flow sec, ReportValues current) {
        return sec != null && sec.reason() != null && (current == null
                || current.periodEnd().equals(sec.periodEnd()));
    }

    private MetricResult<BigDecimal> secMetric(Flow flow) {
        return flow.value() == null ? missing(flow.reason()) : MetricResult.available(flow.value());
    }

    private ReportValues supplementBalance(ReportValues balance, Flows sec, List<Flow> used) {
        Map<FinancialConcept, MetricResult<BigDecimal>> values = new EnumMap<>(FinancialConcept.class);
        values.putAll(balance.values());
        for (FinancialConcept concept : List.of(FinancialConcept.CURRENT_OPERATING_LEASES, FinancialConcept.LONG_TERM_OPERATING_LEASES,
                FinancialConcept.CURRENT_FINANCE_LEASES, FinancialConcept.LONG_TERM_FINANCE_LEASES, FinancialConcept.TOTAL_FINANCE_LEASES,
                FinancialConcept.CASH_AND_EQUIVALENTS,
                FinancialConcept.PREFERRED_STOCK_CAPITAL, FinancialConcept.NONCONTROLLING_INTEREST_EQUITY,
                FinancialConcept.STOCKHOLDERS_EQUITY, FinancialConcept.TOTAL_EQUITY,
                FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED)) {
            Flow input = sec.input(concept, balance);
            MetricResult<BigDecimal> existing = balance.value(concept);
            if (existing.value() != null || existing.status() == MetricStatus.UNVERIFIED || input == null || input.value() == null
                    || !balance.periodEnd().equals(input.periodEnd())) continue;
            values.put(concept, MetricResult.available(input.value()));
            used.add(input);
        }
        return new ReportValues(balance.figi(), balance.statementType(), balance.periodEnd(), balance.periodType(), balance.fiscalYear(),
                balance.currency(), balance.accountingStandards(), values, balance.unknownNames(), balance.pendingItems());
    }

    private List<ReportValues> supplementFlowReports(List<ReportValues> reports, ReportValues latest, Flows sec, List<Flow> used,
                                                    List<FinancialConcept> concepts) {
        if (sec == null) return reports;
        List<ReportValues> available = new ArrayList<>(reports);
        available.addAll(sec.missingQuarters());
        List<ReportValues> result = new ArrayList<>();
        for (ReportValues report : calculator.trailingPeriods(available, latest.figi(), latest.statementType(), latest.periodEnd())) {
            Map<FinancialConcept, MetricResult<BigDecimal>> values = new EnumMap<>(FinancialConcept.class);
            values.putAll(report.values());
            for (FinancialConcept concept : concepts) {
                MetricResult<BigDecimal> original = calculator.flowInput(report, concept);
                Flow input = sec.input(concept, report);
                if (original.value() != null || original.status() == MetricStatus.UNVERIFIED || input == null || input.value() == null) continue;
                if (concept == FinancialConcept.CAPITAL_EXPENDITURE && input.value().signum() < 0) continue;
                values.put(concept, MetricResult.available(input.value()));
                used.add(input);
            }
            if (report.statementType() == 3 && report.value(FinancialConcept.FREE_CASH_FLOW).status() == MetricStatus.INSUFFICIENT_DATA) {
                MetricResult<BigDecimal> cfo = values.get(FinancialConcept.OPERATING_CASH_FLOW);
                MetricResult<BigDecimal> capex = values.get(FinancialConcept.CAPITAL_EXPENDITURE);
                if (cfo != null && cfo.value() != null && capex != null && capex.value() != null)
                    values.put(FinancialConcept.FREE_CASH_FLOW, MetricResult.available(cfo.value().subtract(capex.value().abs())));
            }
            result.add(new ReportValues(report.figi(), report.statementType(), report.periodEnd(), report.periodType(), report.fiscalYear(),
                    report.currency(), report.accountingStandards(), values, report.unknownNames(), report.pendingItems()));
        }
        return result;
    }

    private void recordSecTiming(StockValuationDaily row, Flow sec) {
        if (sec.observedAt() == null || sec.availableFrom() == null) return;
        if (row.getFinancialObservedAt() == null || sec.observedAt().isAfter(row.getFinancialObservedAt())) row.setFinancialObservedAt(sec.observedAt());
        row.setSecFinancialAvailableFromDate(latestEnd(row.getSecFinancialAvailableFromDate(), sec.availableFrom()));
    }

    private MetricResult<BigDecimal> trailing(List<ReportValues> financials, ReportValues latest, FinancialConcept concept, String currency) {
        if (latest == null) return missing("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        return sameCurrency(currency, latest.currency()) ? calculator.trailingTwelveMonths(financials, latest.figi(), latest.statementType(),
                concept, latest.periodEnd(), currency) : missing("REPORTING_CURRENCY_MISMATCH");
    }

    private MetricResult<BigDecimal> missing(String reason) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(reason, "VALUATION", "缺少當日已知的同口徑輸入。"));
    }

    private boolean sameCurrency(String first, String second) { return first != null && second != null && first.equalsIgnoreCase(second); }

    private String shareBasisReason(StockStatus status, List<StockSplit> adjustments, LocalDate periodEnd, LocalDate day) {
        if (periodEnd == null) return "EPS_SOURCE_PERIOD_MISSING";
        String coverage = splitCoverageReason(status, periodEnd, day);
        if (coverage != null) return coverage;
        LocalDate from = periodEnd.isBefore(day) ? periodEnd : day;
        LocalDate through = periodEnd.isAfter(day) ? periodEnd : day;
        for (StockSplit split : adjustments) {
            if (split.getExecutionDate().isAfter(from) && !split.getExecutionDate().isAfter(through)) return "EPS_SPLIT_BASIS_UNCONFIRMED";
        }
        return null;
    }

    private String splitCoverageReason(StockStatus status, LocalDate basis, LocalDate day) {
        String splitMessage = status.getSplitMessage();
        // 此特定錯誤已由同步流程把有效水位截到首個缺席事件之前，較早日期仍可使用。
        if (splitMessage != null && !splitMessage.isBlank() && !splitMessage.startsWith("WARNING:")
                && !splitMessage.startsWith("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:")) return "SPLIT_SYNC_ERROR";
        LocalDate from = basis.isBefore(day) ? basis : day;
        LocalDate through = basis.isAfter(day) ? basis : day;
        if (status.getSplitFromDate() == null || status.getSplitFromDate().isAfter(from)
                || status.getSplitDate() == null || status.getSplitDate().isBefore(through)) return "SPLIT_COVERAGE_INSUFFICIENT";
        return null;
    }

    private String reason(MetricResult<BigDecimal> metric) { return metric.value() != null ? null : metric.reasons().getFirst().code(); }

    private String reasons(StockValuationDaily row) {
        return "PE=" + (row.getPe() != null ? "AVAILABLE" : row.getPeReason()) + ";PB=" + (row.getPb() != null ? "AVAILABLE" : row.getPbReason())
                + ";PS=" + (row.getPs() != null ? "AVAILABLE" : row.getPsReason()) + ";PFCF=" + (row.getPfcf() != null ? "AVAILABLE" : row.getPfcfReason())
                + ";EV_EBITDA=" + (row.getEvEbitda() != null ? "AVAILABLE" : row.getEvEbitdaReason());
    }

    private String message(String value) { return value.length() <= 3000 ? value : value.substring(0, 3000); }
}
