package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotCommon.FinancialStatementsType;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.CompanySubmissions;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.FilingSubmission;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplit;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.SecCustomFinancialFact;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.key.FinancialReportId;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.FigiStatementPeriodEnd;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.enhancement.SecCustomTagMappingService;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_Income;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.financialPeriodEnd;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.usMarketDate;

/**
 * 三合一基本面與拆股每日同步排程（逐公司原子寫入拆股、富途財報、SEC 財務事實並同交易重建財季分母）。
 * 以 stock_status.fundamental_sync_date（香港排程日）作為單一斷點水位：
 * - 任一步驟失敗或強制關停時該組交易全數回滾且不推進水位，重跑直接跳過同日已完成股票；
 * - 週六自動全市場巡檢；若週六中途中止，跨日後對尚未越過最近週六水位的股票自動補跑巡檢。
 */
@Component(TaskCode.STOCK_FUNDAMENTAL_DAILY_SYNC)
@DisallowConcurrentExecution
@Slf4j
public class StockFundamentalDailySyncJob implements Job {
    private static final ZoneId HK_ZONE = ZoneId.of(JobScheduleReconciler.SCHEDULE_TIMEZONE);
    private static final LocalDate BACKFILL_START = LocalDate.of(2014, 1, 1);
    private static final int BACKFILL_PAGE_SIZE = 50;
    private static final int INCREMENTAL_PAGE_SIZE = 10;
    private static final int INCREMENTAL_SPLIT_LOOKBACK_DAYS = 7;
    private static final int RECENT_FILING_LOOKBACK_DAYS = 14;
    private static final int FAST_BATCH_SIZE = 200;
    private static final String SPLIT_MISSING_MARKER = "SPLIT_STORED_EVENT_NOT_RETURNED";
    private static final List<FinancialStatementsType> STATEMENT_TYPES = List.of(
            FinancialStatementsType_Income, FinancialStatementsType_BalanceSheet, FinancialStatementsType_CashFlow);

    private record DownloadedReport(FinancialReport report, LocalDate sourceDate, boolean existingOnly) {}

    private record SplitDownload(Stock stock, List<StockSplit> splits, LocalDate from, boolean fullReconcile,
                                 boolean preserveExistingDiscrepancy, boolean ambiguousSymbol, String failureMessage) {}

    private record ReportDownload(Stock stock, List<DownloadedReport> reports, boolean attempted, boolean unsupported) {}

    private record SecCikDownload(String rawCik, List<SecFinancialFact> facts, String resolvedSic,
                                  List<SecCustomFinancialFact> customFacts, boolean missingEntry) {}

    private record CompanyGroup(String groupKey, String canonicalCik, List<Stock> members,
                                boolean fullPatrol, boolean hasReportEligible, boolean hasSecEligible,
                                boolean splitOnlyCatchup) {}

    private record GroupCommitResult(boolean valuationInputChanged, boolean splitDiscrepancy, boolean splitFailed,
                                     int reportsSaved, int secFactChanges) {}

    private final StockRepository stocks;
    private final StockStatusRepository statuses;
    private final StockSplitRepository splitRepository;
    private final FinancialReportRepository reports;
    private final SecFinancialFactRepository secFacts;
    private final StockFinancialPeriodRepository financialPeriods;
    private final MarketEventRepository marketEvents;
    private final MassiveService massiveService;
    private final FutuService futu;
    private final SecCompanyFactsArchive companyFactsArchive;
    private final SecSubmissionsArchive submissionsArchive;
    private final FutuQuoteConverter futuConverter;
    private final SecFinancialFactConverter secConverter;
    private final SecIssuerHistory issuerHistory;
    private final MarketStatusService marketStatusService;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final StockValuationService stockValuationService;
    @Autowired(required = false)
    private ObjectProvider<SecCustomTagMappingService> customTagMappingServiceProvider;

    @Value("${split.history-start-date:2014-01-01}")
    private LocalDate splitHistoryStartDate = LocalDate.of(2014, 1, 1);

    @Autowired
    public StockFundamentalDailySyncJob(StockRepository stocks,
                                        StockStatusRepository statuses,
                                        StockSplitRepository splitRepository,
                                        FinancialReportRepository reports,
                                        SecFinancialFactRepository secFacts,
                                        StockFinancialPeriodRepository financialPeriods,
                                        MarketEventRepository marketEvents,
                                        MassiveService massiveService,
                                        FutuService futu,
                                        SecCompanyFactsArchive companyFactsArchive,
                                        SecSubmissionsArchive submissionsArchive,
                                        FutuQuoteConverter futuConverter,
                                        SecFinancialFactConverter secConverter,
                                        SecIssuerHistory issuerHistory,
                                        MarketStatusService marketStatusService,
                                        DateTimeService dateTimeService,
                                        TransactionTemplate transactions,
                                        EntityManager entityManager,
                                        StockValuationService stockValuationService) {
        this.stocks = stocks;
        this.statuses = statuses;
        this.splitRepository = splitRepository;
        this.reports = reports;
        this.secFacts = secFacts;
        this.financialPeriods = financialPeriods;
        this.marketEvents = marketEvents;
        this.massiveService = massiveService;
        this.futu = futu;
        this.companyFactsArchive = companyFactsArchive;
        this.submissionsArchive = submissionsArchive;
        this.futuConverter = futuConverter;
        this.secConverter = secConverter;
        this.issuerHistory = issuerHistory;
        this.marketStatusService = marketStatusService;
        this.dateTimeService = dateTimeService;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.stockValuationService = stockValuationService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        // 1. 固定本輪日期基準（香港排程日、最近週六巡檢水位、美股最近已收市交易日）
        Instant startedAt = dateTimeService.now();
        LocalDate todayHk = startedAt.atZone(HK_ZONE).toLocalDate();
        LocalDate latestSaturdayHk = todayHk.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        boolean isSaturdayHk = todayHk.getDayOfWeek() == DayOfWeek.SATURDAY;
        LocalDate splitThrough = marketStatusService.getLastClosedTradingDay();
        if (splitHistoryStartDate.isAfter(splitThrough)) {
            throw new IllegalArgumentException("INVALID_SPLIT_SYNC_RANGE");
        }

        // 2. 聯集三個來源資格名單，保留各自資格判定並按 canonicalCik 聚合為公司組
        List<Stock> splitEligibleList = stocks.findSplitSyncStocks(ActiveStatus.ACTIVE);
        List<ActiveStockWithStatus> reportEligibleList = stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE);
        List<ActiveStockWithStatus> secEligibleList = stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE);

        Map<String, Stock> universeByFigi = new LinkedHashMap<>();
        Set<String> splitEligibleFigis = new HashSet<>();
        Set<String> reportEligibleFigis = new HashSet<>();
        Set<String> secEligibleFigis = new HashSet<>();
        for (Stock s : splitEligibleList) {
            universeByFigi.putIfAbsent(s.getFigi(), s);
            splitEligibleFigis.add(s.getFigi());
        }
        for (ActiveStockWithStatus row : reportEligibleList) {
            universeByFigi.putIfAbsent(row.stock().getFigi(), row.stock());
            reportEligibleFigis.add(row.stock().getFigi());
        }
        for (ActiveStockWithStatus row : secEligibleList) {
            universeByFigi.putIfAbsent(row.stock().getFigi(), row.stock());
            secEligibleFigis.add(row.stock().getFigi());
        }
        if (universeByFigi.isEmpty()) {
            log.info("Fundamental daily sync skipped | reason=NO_TARGETS");
            return;
        }

        Map<String, Long> splitSymbolCounts = splitEligibleList.stream()
                .collect(Collectors.groupingBy(Stock::getSymbol, Collectors.counting()));
        Map<String, StockStatus> initialStatuses = new HashMap<>();
        for (StockStatus row : statuses.findAllById(universeByFigi.keySet())) {
            initialStatuses.put(row.getFigi(), row);
        }

        Set<String> requestedFigis = parseRequestedFigis(context);
        boolean manualFigisMode = !requestedFigis.isEmpty();

        List<CompanyGroup> allGroups = buildCompanyGroups(
                universeByFigi, initialStatuses, splitEligibleFigis, splitSymbolCounts,
                reportEligibleFigis, secEligibleFigis, todayHk, latestSaturdayHk, isSaturdayHk, splitThrough, requestedFigis);
        if (allGroups.isEmpty()) {
            log.info("Fundamental daily sync skipped | reason=ALL_ALREADY_SYNCED todayHk={} splitThrough={}", todayHk, splitThrough);
            return;
        }
        long splitOnlyCatchupGroups = allGroups.stream().filter(CompanyGroup::splitOnlyCatchup).count();

        // 3. 交易外批次準備：Massive 平日拆股、近 14 天財報觸發名單、三表各自最新期末日、SEC 壓縮檔
        Map<String, List<MassiveSplit>> massiveSplitsByTicker = preloadMassiveSplitsIfNeeded(
                allGroups, splitEligibleFigis, splitSymbolCounts, initialStatuses, splitThrough);

        List<Stock> fastQuietSplitOnly = new ArrayList<>();
        List<CompanyGroup> fullProcessingGroups = new ArrayList<>();
        for (CompanyGroup group : allGroups) {
            if (!manualFigisMode && canUseFastQuietSplitPath(group, splitEligibleFigis, splitSymbolCounts,
                    initialStatuses, massiveSplitsByTicker, splitThrough)) {
                fastQuietSplitOnly.add(group.members().getFirst());
            } else {
                fullProcessingGroups.add(group);
            }
        }

        // 4. 平日純拆股無變動股票（多數 ETF 與美股收市後純拆股補推組）按 200 檔一批快速推進水位與斷點
        int fastAdvanced = advanceFastQuietSplits(fastQuietSplitOnly, splitThrough, todayHk);

        // 5. 逐組處理一般公司（交易外取數 → 單組短交易原子寫入拆股／財報／SEC／重建分母／推進斷點水位）
        Set<String> modifiedValuationFigis = new LinkedHashSet<>();
        int groupsProcessed = 0;
        int reportsSaved = 0;
        int secFactChanges = 0;
        int unsupportedReportStocks = 0;
        int splitDiscrepancies = 0;
        int splitFailures = 0;
        List<String> missingSecEntries = new ArrayList<>();

        if (!fullProcessingGroups.isEmpty()) {
            Map<String, Map<Integer, LocalDate>> latestStatementEndsByFigi = preloadLatestStatementEnds(
                    fullProcessingGroups, reportEligibleFigis);
            boolean anySecGroup = fullProcessingGroups.stream()
                    .anyMatch(g -> !g.splitOnlyCatchup() && g.hasSecEligible());
            LocalDate todayNy = startedAt.atZone(MarketStatusService.NY_ZONE).toLocalDate();
            LocalDate filingLookbackFrom = todayNy.minusDays(RECENT_FILING_LOOKBACK_DAYS);

            try (SecSubmissionsArchive.Snapshot submissions = (anySecGroup || hasReportGroup(fullProcessingGroups))
                    ? submissionsArchive.prepare() : null;
                 SecCompanyFactsArchive.Snapshot companyFacts = anySecGroup ? companyFactsArchive.prepare() : null) {

                Set<String> recentEarningsFigis = Set.of();
                Set<String> recentSecCiks = new HashSet<>();
                if (!manualFigisMode && hasWeekdayReportGroup(fullProcessingGroups)) {
                    recentEarningsFigis = marketEvents.findEarningsFigisBetween(filingLookbackFrom, todayNy);
                    collectRecentSecCiks(fullProcessingGroups, secEligibleFigis, filingLookbackFrom, submissions, recentSecCiks);
                }

                boolean needFutu = needsFutuSession(fullProcessingGroups, splitEligibleFigis, reportEligibleFigis,
                        splitSymbolCounts, initialStatuses, manualFigisMode, recentEarningsFigis, recentSecCiks, splitThrough);

                FutuService.ObservationSession futuSession = null;
                Set<String> supportedFutuReportSymbols = Set.of();
                try {
                    if (needFutu) {
                        futuSession = futu.openObservationSession();
                        if (hasReportGroup(fullProcessingGroups)) {
                            supportedFutuReportSymbols = futuSession.supportedUsFinancialReportStocks();
                        }
                    }

                    for (CompanyGroup group : fullProcessingGroups) {
                        List<SplitDownload> splitDownloads = prepareGroupSplits(
                                group, splitEligibleFigis, splitSymbolCounts, initialStatuses,
                                massiveSplitsByTicker, futuSession, splitThrough);

                        List<ReportDownload> reportDownloads = group.splitOnlyCatchup() ? List.of() : prepareGroupReports(
                                group, reportEligibleFigis, initialStatuses, latestStatementEndsByFigi,
                                manualFigisMode, recentEarningsFigis, recentSecCiks,
                                futuSession, supportedFutuReportSymbols);
                        for (ReportDownload rd : reportDownloads) {
                            if (rd.unsupported()) unsupportedReportStocks++;
                        }

                        List<SecCikDownload> secDownloads = group.splitOnlyCatchup() ? List.of() : prepareGroupSec(
                                group, secEligibleFigis, companyFacts, submissions, missingSecEntries);

                        // 單組原子交易：寫入原料、各自水位、原地重建財季分母，成功才推進 fundamental_sync_date
                        GroupCommitResult result = commitCompanyGroup(
                                group, splitDownloads, reportDownloads, secDownloads,
                                splitThrough, todayHk, manualFigisMode);

                        groupsProcessed++;
                        reportsSaved += result.reportsSaved();
                        secFactChanges += result.secFactChanges();
                        if (result.splitDiscrepancy()) splitDiscrepancies++;
                        if (result.splitFailed()) splitFailures++;
                        if (result.valuationInputChanged()) {
                            for (Stock member : group.members()) {
                                if (SecurityTypePolicy.supportsValuation(member.getStockType())) {
                                    modifiedValuationFigis.add(member.getFigi());
                                }
                            }
                        }
                    }
                } finally {
                    if (futuSession != null) {
                        futuSession.close();
                    }
                }
            }
        }

        // 6. 整輪完成後於交易外批次刷新本輪有變動股票的最新估值快照（若中途強殺，10:00 每日估值會補刷全市場）
        if (!modifiedValuationFigis.isEmpty() && stockValuationService != null) {
            stockValuationService.refreshLatestValuations(modifiedValuationFigis, null);
        }
        if (!missingSecEntries.isEmpty()) {
            log.warn("SEC Company Facts ZIP missing entries | count={} ciks={}",
                    missingSecEntries.size(), missingSecEntries.stream().limit(30).toList());
        }
        log.info("Fundamental daily sync completed | todayHk={} splitThrough={} splitOnlyCatchupGroups={} fastSplitStocks={} groups={} reports={} secChanges={} rebuiltValuationStocks={} unsupportedReports={} splitDiscrepancies={} splitFailures={}",
                todayHk, splitThrough, splitOnlyCatchupGroups, fastAdvanced, groupsProcessed, reportsSaved, secFactChanges,
                modifiedValuationFigis.size(), unsupportedReportStocks, splitDiscrepancies, splitFailures);
    }

    private Set<String> parseRequestedFigis(JobExecutionContext context) {
        JobDataMap parameters = context == null ? null : context.getMergedJobDataMap();
        String requested = parameters == null ? null : parameters.getString("figis");
        if (requested == null || requested.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(requested.split(",")).map(String::trim)
                .filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<CompanyGroup> buildCompanyGroups(Map<String, Stock> universeByFigi,
                                                  Map<String, StockStatus> initialStatuses,
                                                  Set<String> splitEligibleFigis,
                                                  Map<String, Long> splitSymbolCounts,
                                                  Set<String> reportEligibleFigis,
                                                  Set<String> secEligibleFigis,
                                                  LocalDate todayHk,
                                                  LocalDate latestSaturdayHk,
                                                  boolean isSaturdayHk,
                                                  LocalDate splitThrough,
                                                  Set<String> requestedFigis) {
        Map<String, List<Stock>> groupedMembers = new LinkedHashMap<>();
        Map<String, String> groupCanonicalCik = new HashMap<>();
        for (Stock stock : universeByFigi.values()) {
            String rawCik = stock.getCik() != null && !stock.getCik().isBlank() ? stock.getCik().trim() : null;
            String canonical = rawCik == null ? null : issuerHistory.canonicalCik(rawCik);
            String key = canonical != null ? "CIK:" + canonical : "FIGI:" + stock.getFigi();
            groupedMembers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stock);
            if (canonical != null) {
                groupCanonicalCik.put(key, canonical);
            }
        }

        boolean manualMode = !requestedFigis.isEmpty();
        List<CompanyGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Stock>> entry : groupedMembers.entrySet()) {
            List<Stock> members = entry.getValue().stream()
                    .sorted(Comparator.comparing(Stock::getFigi)).toList();
            if (manualMode) {
                boolean matches = members.stream().anyMatch(s -> requestedFigis.contains(s.getFigi()));
                if (!matches) continue;
            } else {
                boolean allSyncedToday = members.stream().allMatch(s -> {
                    StockStatus st = initialStatuses.get(s.getFigi());
                    return st != null && st.getFundamentalSyncDate() != null && !st.getFundamentalSyncDate().isBefore(todayHk);
                });
                if (allSyncedToday) {
                    boolean needsSplitCatchup = members.stream().anyMatch(s -> isEligibleForSplitOnlyCatchup(
                            s, splitEligibleFigis, splitSymbolCounts, initialStatuses.get(s.getFigi()), splitThrough));
                    if (!needsSplitCatchup) continue;
                    // 同日早盤前已同步基本面但美股收市後 split_date 落後：走純拆股增量補推，強制關閉 fullPatrol／財報／SEC
                    groups.add(new CompanyGroup(entry.getKey(), groupCanonicalCik.get(entry.getKey()), members,
                            false, false, false, true));
                    continue;
                }
            }

            // 週六當天，或跨日後組內有股票已初始化過斷點水位但尚未越過最近週六水位時，執行週六全市場巡檢
            boolean fullPatrol = isSaturdayHk || (!manualMode && members.stream().anyMatch(s -> {
                StockStatus st = initialStatuses.get(s.getFigi());
                return st != null && st.getFundamentalSyncDate() != null
                        && st.getFundamentalSyncDate().isBefore(latestSaturdayHk);
            }));
            boolean hasReport = members.stream().anyMatch(s -> reportEligibleFigis.contains(s.getFigi()));
            boolean hasSec = members.stream().anyMatch(s -> secEligibleFigis.contains(s.getFigi()));
            groups.add(new CompanyGroup(entry.getKey(), groupCanonicalCik.get(entry.getKey()), members,
                    fullPatrol, hasReport, hasSec, false));
        }
        return groups;
    }

    private boolean isEligibleForSplitOnlyCatchup(Stock stock,
                                                  Set<String> splitEligibleFigis,
                                                  Map<String, Long> splitSymbolCounts,
                                                  StockStatus status,
                                                  LocalDate splitThrough) {
        if (stock == null || !splitEligibleFigis.contains(stock.getFigi())) {
            return false;
        }
        if (splitSymbolCounts.getOrDefault(stock.getSymbol(), 0L) != 1L) {
            return false;
        }
        if (status == null || hasSplitDiscrepancyMessage(status)) {
            return false;
        }
        String msg = status.getSplitMessage();
        if (msg != null && !msg.isBlank() && !isWarningSplitMessage(status)) {
            return false;
        }
        return status.getSplitDate() == null || status.getSplitDate().isBefore(splitThrough);
    }

    private boolean isWarningSplitMessage(StockStatus status) {
        return status != null && status.getSplitMessage() != null
                && status.getSplitMessage().toUpperCase(java.util.Locale.ROOT).startsWith("WARNING:");
    }

    private Map<String, List<MassiveSplit>> preloadMassiveSplitsIfNeeded(List<CompanyGroup> groups,
                                                                         Set<String> splitEligibleFigis,
                                                                         Map<String, Long> splitSymbolCounts,
                                                                         Map<String, StockStatus> initialStatuses,
                                                                         LocalDate through) {
        LocalDate minFrom = null;
        for (CompanyGroup group : groups) {
            if (group.fullPatrol()) continue;
            for (Stock s : group.members()) {
                if (!splitEligibleFigis.contains(s.getFigi())) continue;
                if (splitSymbolCounts.getOrDefault(s.getSymbol(), 0L) != 1L) continue;
                StockStatus st = initialStatuses.get(s.getFigi());
                if (group.splitOnlyCatchup() && !isEligibleForSplitOnlyCatchup(s, splitEligibleFigis, splitSymbolCounts, st, through)) {
                    continue;
                }
                if (st == null || st.getSplitDate() == null) continue;
                LocalDate from = incrementalSplitFrom(st.getSplitDate());
                if (minFrom == null || from.isBefore(minFrom)) minFrom = from;
            }
        }
        if (minFrom == null) {
            return Map.of();
        }
        List<MassiveSplit> allSplits = massiveService.getSplitsFrom(minFrom);
        Map<String, List<MassiveSplit>> byTicker = new LinkedHashMap<>();
        for (MassiveSplit split : allSplits) {
            if (split.getTicker() == null || split.getTicker().isBlank()) continue;
            byTicker.computeIfAbsent(split.getTicker().trim(), ignored -> new ArrayList<>()).add(split);
        }
        return byTicker;
    }

    private boolean canUseFastQuietSplitPath(CompanyGroup group,
                                             Set<String> splitEligibleFigis,
                                             Map<String, Long> splitSymbolCounts,
                                             Map<String, StockStatus> initialStatuses,
                                             Map<String, List<MassiveSplit>> massiveSplitsByTicker,
                                             LocalDate through) {
        if (group.fullPatrol() || group.hasReportEligible() || group.hasSecEligible() || group.members().size() != 1) {
            return false;
        }
        Stock stock = group.members().getFirst();
        if (!splitEligibleFigis.contains(stock.getFigi()) || splitSymbolCounts.getOrDefault(stock.getSymbol(), 0L) != 1L) {
            return false;
        }
        StockStatus status = initialStatuses.get(stock.getFigi());
        if (status == null || status.getSplitDate() == null) {
            return false;
        }
        // 保留週六拆股缺口差異訊息，不讓平日 Massive 空結果清除缺口
        if (hasSplitDiscrepancyMessage(status)) {
            return false;
        }
        if (status.getSplitMessage() != null && !status.getSplitMessage().isBlank()
                && !isWarningSplitMessage(status) && SecurityTypePolicy.supportsValuation(stock.getStockType())) {
            return false;
        }
        List<MassiveSplit> events = massiveSplitsByTicker.getOrDefault(stock.getSymbol(), List.of());
        if (events.isEmpty()) {
            return true;
        }
        LocalDate from = incrementalSplitFrom(status.getSplitDate());
        try {
            return convertMassiveSplits(stock, events, from, through, dateTimeService.now()).isEmpty();
        } catch (RuntimeException ex) {
            // 單股異常拆股資料改交由 fullProcessingGroups 的逐股隔離路徑記錯，絕不中止全市場批次
            return false;
        }
    }

    private int advanceFastQuietSplits(List<Stock> fastQuietStocks, LocalDate through, LocalDate todayHk) {
        if (fastQuietStocks.isEmpty()) return 0;
        List<String> figis = fastQuietStocks.stream().map(Stock::getFigi).distinct().sorted().toList();
        for (List<String> batch : Lists.partition(figis, FAST_BATCH_SIZE)) {
            transactions.executeWithoutResult(tx -> {
                List<StockStatus> locked = statuses.findAllByIdForUpdate(batch);
                if (locked.size() != batch.size()) {
                    throw new IllegalStateException("FUNDAMENTAL_SYNC_STOCK_STATUS_MISSING");
                }
                for (StockStatus status : locked) {
                    if (status.getSplitFromDate() == null) status.setSplitFromDate(splitHistoryStartDate);
                    if (!hasSplitDiscrepancyMessage(status)) {
                        if (status.getSplitDate() == null || status.getSplitDate().isBefore(through)) {
                            status.setSplitDate(through);
                        }
                        if (!isWarningSplitMessage(status)) {
                            status.setSplitMessage(null);
                        }
                    }
                    status.setFundamentalSyncDate(todayHk);
                }
                statuses.saveAll(locked);
            });
        }
        return figis.size();
    }

    private Map<String, Map<Integer, LocalDate>> preloadLatestStatementEnds(List<CompanyGroup> groups,
                                                                            Set<String> reportEligibleFigis) {
        List<String> figis = groups.stream().filter(g -> !g.splitOnlyCatchup())
                .flatMap(g -> g.members().stream())
                .map(Stock::getFigi).filter(reportEligibleFigis::contains).distinct().toList();
        if (figis.isEmpty()) return Map.of();
        Map<String, Map<Integer, LocalDate>> result = new HashMap<>();
        for (List<String> part : Lists.partition(figis, 500)) {
            List<FigiStatementPeriodEnd> rows = reports.findLatestStatementPeriodEnds(part);
            if (rows == null) continue;
            for (FigiStatementPeriodEnd row : rows) {
                result.computeIfAbsent(row.figi(), ignored -> new HashMap<>())
                        .put(row.statementType(), row.latestPeriodEnd());
            }
        }
        return result;
    }

    private void collectRecentSecCiks(List<CompanyGroup> groups, Set<String> secEligibleFigis,
                                      LocalDate lookbackFrom, SecSubmissionsArchive.Snapshot submissions,
                                      Set<String> recentSecCiks) {
        Set<String> candidateCiks = new LinkedHashSet<>();
        for (CompanyGroup group : groups) {
            if (group.fullPatrol() || group.splitOnlyCatchup()) continue;
            for (Stock s : group.members()) {
                if (s.getCik() != null && !s.getCik().isBlank()) {
                    candidateCiks.add(s.getCik());
                    candidateCiks.addAll(issuerHistory.predecessors(s.getCik()));
                }
            }
        }
        if (candidateCiks.isEmpty()) return;
        recentSecCiks.addAll(secFacts.findRecentPeriodicFilingCiks(candidateCiks, lookbackFrom));
        if (submissions == null) return;
        for (String cik : candidateCiks) {
            if (recentSecCiks.contains(cik)) continue;
            Optional<CompanySubmissions> sub = submissions.findCompany(cik);
            if (sub.isPresent() && sub.get().latestPeriodicFilingDate() != null
                    && !sub.get().latestPeriodicFilingDate().isBefore(lookbackFrom)) {
                recentSecCiks.add(cik);
            }
        }
    }

    private boolean hasReportGroup(List<CompanyGroup> groups) {
        return groups.stream().anyMatch(g -> !g.splitOnlyCatchup() && g.hasReportEligible());
    }

    private boolean hasWeekdayReportGroup(List<CompanyGroup> groups) {
        return groups.stream().anyMatch(g -> !g.splitOnlyCatchup() && g.hasReportEligible() && !g.fullPatrol());
    }

    private boolean needsFutuSession(List<CompanyGroup> groups,
                                     Set<String> splitEligibleFigis,
                                     Set<String> reportEligibleFigis,
                                     Map<String, Long> splitSymbolCounts,
                                     Map<String, StockStatus> initialStatuses,
                                     boolean manualFigisMode,
                                     Set<String> recentEarningsFigis,
                                     Set<String> recentSecCiks,
                                     LocalDate splitThrough) {
        for (CompanyGroup group : groups) {
            for (Stock s : group.members()) {
                StockStatus st = initialStatuses.get(s.getFigi());
                if (splitEligibleFigis.contains(s.getFigi()) && splitSymbolCounts.getOrDefault(s.getSymbol(), 0L) == 1L) {
                    if (group.splitOnlyCatchup() && !isEligibleForSplitOnlyCatchup(s, splitEligibleFigis, splitSymbolCounts, st, splitThrough)) {
                        continue;
                    }
                    if (group.fullPatrol() || st == null || st.getSplitDate() == null) {
                        return true;
                    }
                }
                if (!group.splitOnlyCatchup() && reportEligibleFigis.contains(s.getFigi())
                        && shouldQueryFutuReport(s, st, group.fullPatrol(), manualFigisMode, recentEarningsFigis, recentSecCiks)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean shouldQueryFutuReport(Stock stock, StockStatus status, boolean fullPatrol,
                                          boolean manualFigisMode, Set<String> recentEarningsFigis,
                                          Set<String> recentSecCiks) {
        if (manualFigisMode || fullPatrol) return true;
        if (status == null || status.getFinancialReportDate() == null) return true;
        if (recentEarningsFigis.contains(stock.getFigi())) return true;
        if (stock.getCik() != null && !stock.getCik().isBlank()) {
            if (recentSecCiks.contains(stock.getCik())) return true;
            for (String pred : issuerHistory.predecessors(stock.getCik())) {
                if (recentSecCiks.contains(pred)) return true;
            }
        }
        return false;
    }

    private List<SplitDownload> prepareGroupSplits(CompanyGroup group,
                                                   Set<String> splitEligibleFigis,
                                                   Map<String, Long> splitSymbolCounts,
                                                   Map<String, StockStatus> initialStatuses,
                                                   Map<String, List<MassiveSplit>> massiveSplitsByTicker,
                                                   FutuService.ObservationSession futuSession,
                                                   LocalDate through) {
        List<SplitDownload> downloads = new ArrayList<>();
        Instant observedAt = dateTimeService.now();
        for (Stock stock : group.members()) {
            if (!splitEligibleFigis.contains(stock.getFigi())) continue;
            StockStatus snapshot = initialStatuses.get(stock.getFigi());
            if (group.splitOnlyCatchup() && !isEligibleForSplitOnlyCatchup(stock, splitEligibleFigis, splitSymbolCounts, snapshot, through)) {
                continue;
            }
            if (splitSymbolCounts.getOrDefault(stock.getSymbol(), 0L) != 1L) {
                downloads.add(new SplitDownload(stock, List.of(), splitHistoryStartDate, false, false, true, null));
                continue;
            }
            boolean fullReconcile = group.fullPatrol() || snapshot == null || snapshot.getSplitDate() == null;
            LocalDate from = fullReconcile ? splitHistoryStartDate : incrementalSplitFrom(snapshot.getSplitDate());
            try {
                if (fullReconcile) {
                    List<QotCommon.Rehab> source = futuSession.rehab(stock.getSymbol());
                    List<StockSplit> splits = convertFutuSplits(stock, source, splitHistoryStartDate, through, observedAt);
                    downloads.add(new SplitDownload(stock, splits, splitHistoryStartDate, true, false, false, null));
                } else {
                    List<MassiveSplit> rawEvents = massiveSplitsByTicker.getOrDefault(stock.getSymbol(), List.of());
                    List<StockSplit> converted = convertMassiveSplits(stock, rawEvents, from, through, observedAt);
                    downloads.add(new SplitDownload(stock, converted, from, false, hasSplitDiscrepancyMessage(snapshot), false, null));
                }
            } catch (RuntimeException failure) {
                if (isFatalFutuTransportError(failure)) {
                    throw failure;
                }
                String message = formatSplitFailureMessage(failure);
                log.warn("Split stock failed | symbol={} figi={} reason={}", stock.getSymbol(), stock.getFigi(), message);
                downloads.add(new SplitDownload(stock, List.of(), from, fullReconcile, false, false, message));
            }
        }
        return downloads;
    }

    private boolean isFatalFutuTransportError(RuntimeException failure) {
        String reason = String.valueOf(failure.getMessage());
        return reason.startsWith("FUTU_DISCONNECTED") || reason.startsWith("FUTU_CONNECT_")
                || reason.endsWith("_TIMEOUT") || reason.endsWith("_SEND_FAILED")
                || reason.endsWith("_REPLY_MISMATCH") || reason.endsWith("_INTERRUPTED");
    }

    private String formatSplitFailureMessage(RuntimeException failure) {
        String message = "ERROR:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        return message.substring(0, Math.min(1000, message.length()));
    }

    private List<ReportDownload> prepareGroupReports(CompanyGroup group,
                                                     Set<String> reportEligibleFigis,
                                                     Map<String, StockStatus> initialStatuses,
                                                     Map<String, Map<Integer, LocalDate>> latestStatementEndsByFigi,
                                                     boolean manualFigisMode,
                                                     Set<String> recentEarningsFigis,
                                                     Set<String> recentSecCiks,
                                                     FutuService.ObservationSession futuSession,
                                                     Set<String> supportedSymbols) {
        List<ReportDownload> downloads = new ArrayList<>();
        for (Stock stock : group.members()) {
            if (!reportEligibleFigis.contains(stock.getFigi())) continue;
            StockStatus status = initialStatuses.get(stock.getFigi());
            if (!shouldQueryFutuReport(stock, status, group.fullPatrol(), manualFigisMode, recentEarningsFigis, recentSecCiks)) {
                continue;
            }
            if (!supportedSymbols.contains(stock.getSymbol())) {
                downloads.add(new ReportDownload(stock, List.of(), false, true));
                continue;
            }
            Map<Integer, LocalDate> statementEnds = latestStatementEndsByFigi.getOrDefault(stock.getFigi(), Map.of());
            List<DownloadedReport> downloadedReports = new ArrayList<>();
            boolean unsupported = false;
            for (FinancialStatementsType statementType : STATEMENT_TYPES) {
                LocalDate statementWatermark = statementEnds.get(statementType.getNumber());
                LocalDate firstPeriodEnd;
                int pageSize;
                if (statementWatermark == null) {
                    firstPeriodEnd = BACKFILL_START;
                    pageSize = BACKFILL_PAGE_SIZE;
                } else if (group.fullPatrol()) {
                    // 週六巡檢往前多回看一年以捕捉舊期重述，同時維持單頁 10 期控制請求次數
                    LocalDate patrolFrom = statementWatermark.minusYears(1);
                    firstPeriodEnd = patrolFrom.isBefore(BACKFILL_START) ? BACKFILL_START : patrolFrom;
                    pageSize = INCREMENTAL_PAGE_SIZE;
                } else {
                    firstPeriodEnd = statementWatermark;
                    pageSize = INCREMENTAL_PAGE_SIZE;
                }
                List<DownloadedReport> statementReports = downloadStatement(
                        futuSession, stock, statementType, firstPeriodEnd, pageSize);
                if (statementReports == null) {
                    unsupported = true;
                    break;
                }
                downloadedReports.addAll(statementReports);
            }
            downloads.add(new ReportDownload(stock, unsupported ? List.of() : downloadedReports, true, unsupported));
        }
        return downloads;
    }

    private List<SecCikDownload> prepareGroupSec(CompanyGroup group,
                                                 Set<String> secEligibleFigis,
                                                 SecCompanyFactsArchive.Snapshot companyFacts,
                                                 SecSubmissionsArchive.Snapshot submissions,
                                                 List<String> missingSecEntries) {
        if (!group.hasSecEligible() || companyFacts == null) return List.of();
        Set<String> rawCiks = new LinkedHashSet<>();
        for (Stock member : group.members()) {
            if (!secEligibleFigis.contains(member.getFigi())) continue;
            if (member.getCik() != null && !member.getCik().isBlank()) {
                rawCiks.add(member.getCik());
                rawCiks.addAll(issuerHistory.predecessors(member.getCik()));
            }
        }
        if (rawCiks.isEmpty()) return List.of();

        SecCustomTagMappingService customTagService = customTagMappingServiceProvider == null
                ? null : customTagMappingServiceProvider.getIfAvailable();
        List<SecCikDownload> downloads = new ArrayList<>();
        for (String rawCik : rawCiks) {
            Optional<CompanySubmissions> companySub = submissions == null ? Optional.empty() : submissions.findCompany(rawCik);
            Map<String, FilingSubmission> filingMetadata = companySub.map(CompanySubmissions::filingsByAccession).orElse(Map.of());
            String resolvedSic = companySub.map(CompanySubmissions::sic).orElse(null);
            try {
                JsonNode companyNode = companyFacts.read(rawCik);
                Instant observedAt = dateTimeService.now();
                List<SecFinancialFact> converted = secConverter.convert(rawCik, companyNode, observedAt, BACKFILL_START, filingMetadata);
                List<SecCustomFinancialFact> customFacts = extractCustomFactsSafely(customTagService, rawCik, companyNode);
                downloads.add(new SecCikDownload(rawCik, converted, resolvedSic, customFacts, false));
            } catch (SecCompanyFactsArchive.EntryMissingException ex) {
                missingSecEntries.add(rawCik);
                downloads.add(new SecCikDownload(rawCik, List.of(), resolvedSic, List.of(), true));
            }
        }
        return downloads;
    }

    private List<SecCustomFinancialFact> extractCustomFactsSafely(SecCustomTagMappingService customTagService,
                                                                  String cik, JsonNode companyNode) {
        if (customTagService == null) return List.of();
        try {
            return customTagService.extractConfirmedCustomFacts(cik, companyNode);
        } catch (RuntimeException ex) {
            log.warn("SEC confirmed custom tag extract failed (isolated from standard SEC facts) | cik={} reason={}",
                    cik, ex.getMessage());
            return List.of();
        }
    }

    private GroupCommitResult commitCompanyGroup(CompanyGroup group,
                                                 List<SplitDownload> splitDownloads,
                                                 List<ReportDownload> reportDownloads,
                                                 List<SecCikDownload> secDownloads,
                                                 LocalDate splitThrough,
                                                 LocalDate todayHk,
                                                 boolean manualFigisMode) {
        List<String> sortedGroupFigis = group.members().stream().map(Stock::getFigi).distinct().sorted().toList();
        return transactions.execute(tx -> {
            Map<String, StockStatus> lockedStatuses = new HashMap<>();
            for (StockStatus status : statuses.findAllByIdForUpdate(sortedGroupFigis)) {
                lockedStatuses.put(status.getFigi(), status);
            }
            if (lockedStatuses.size() != sortedGroupFigis.size()) {
                throw new IllegalStateException("FUNDAMENTAL_SYNC_STOCK_STATUS_MISSING:" + group.groupKey());
            }

            boolean valuationInputChanged = false;
            boolean splitDiscrepancy = false;
            boolean splitFailed = false;
            int reportsSaved = 0;
            int secFactChanges = 0;

            // 1. 寫入拆股與拆股水位
            for (SplitDownload sd : splitDownloads) {
                StockStatus status = lockedStatuses.get(sd.stock().getFigi());
                if (sd.ambiguousSymbol()) {
                    status.setSplitMessage("ERROR:IllegalStateException:SPLIT_CURRENT_SYMBOL_AMBIGUOUS");
                    splitFailed = true;
                    continue;
                }
                if (sd.failureMessage() != null) {
                    status.setSplitMessage(sd.failureMessage());
                    splitFailed = true;
                    continue;
                }
                boolean hadErrorBefore = status.getSplitMessage() != null && !status.getSplitMessage().isBlank();
                if (sd.fullReconcile()) {
                    SplitSaveOutcome outcome = applyFullSplitsInCurrentTx(sd.stock(), status, sd.splits(), sd.from(), splitThrough);
                    if (outcome.changed() || (hadErrorBefore && status.getSplitMessage() == null)) {
                        valuationInputChanged = true;
                    }
                    if (outcome.discrepancy()) {
                        splitDiscrepancy = true;
                    }
                } else {
                    boolean changed = applyIncrementalSplitsInCurrentTx(
                            sd.stock(), status, sd.splits(), sd.from(), splitThrough, sd.preserveExistingDiscrepancy());
                    if (changed || (hadErrorBefore && status.getSplitMessage() == null)) {
                        valuationInputChanged = true;
                    }
                }
            }

            // 2. 寫入富途財報與財報水位
            for (ReportDownload rd : reportDownloads) {
                if (rd.unsupported() || rd.reports().isEmpty()) continue;
                StockStatus status = lockedStatuses.get(rd.stock().getFigi());
                boolean changed = applyFinancialReportsInCurrentTx(rd.stock().getFigi(), status, rd.reports());
                if (changed) {
                    valuationInputChanged = true;
                }
                reportsSaved += rd.reports().size();
            }

            // 3. 寫入 SEC 標準事實、已確認自訂事實、當前 CIK 股票之 SIC 與 SEC 水位
            SecCustomTagMappingService customTagService = customTagMappingServiceProvider == null
                    ? null : customTagMappingServiceProvider.getIfAvailable();
            Map<String, List<Stock>> membersByRawCik = group.members().stream()
                    .filter(s -> s.getCik() != null && !s.getCik().isBlank())
                    .collect(Collectors.groupingBy(Stock::getCik));
            for (SecCikDownload sec : secDownloads) {
                List<Stock> currentCikMembers = membersByRawCik.getOrDefault(sec.rawCik(), List.of());
                if (!sec.missingEntry()) {
                    SecSaveOutcome outcome = applySecFactsInCurrentTx(
                            sec.rawCik(), currentCikMembers, lockedStatuses, sec.facts(), sec.resolvedSic());
                    secFactChanges += outcome.factChanges();
                    if (outcome.valuationInputChanged()) {
                        valuationInputChanged = true;
                    }
                    if (customTagService != null && !sec.customFacts().isEmpty()) {
                        int customChanged = customTagService.persistConfirmedCustomFactsInCurrentTx(sec.rawCik(), sec.customFacts());
                        if (customChanged > 0) {
                            valuationInputChanged = true;
                        }
                    }
                } else if (sec.resolvedSic() != null && !currentCikMembers.isEmpty()) {
                    if (updateSicIfChangedInCurrentTx(currentCikMembers, sec.resolvedSic())) {
                        valuationInputChanged = true;
                    }
                }
            }

            entityManager.flush();

            // 4. 若本組有任一原料變動或有可估值股尚未建立財季分母，同交易直接原地重建財季分母（不搶全局估值鎖）
            List<String> valuationFigis = group.members().stream()
                    .filter(s -> SecurityTypePolicy.supportsValuation(s.getStockType()))
                    .map(Stock::getFigi).distinct().sorted().toList();
            if (!valuationFigis.isEmpty() && stockValuationService != null) {
                boolean needsRebuild = valuationInputChanged;
                if (!needsRebuild && !group.splitOnlyCatchup()) {
                    boolean anyHasReportOrSec = valuationFigis.stream().anyMatch(figi -> {
                        StockStatus st = lockedStatuses.get(figi);
                        return st != null && (st.getFinancialReportDate() != null || st.getSecFinancialFactsDate() != null);
                    });
                    if (anyHasReportOrSec) {
                        Set<String> existing = financialPeriods.findExistingFigis(valuationFigis);
                        needsRebuild = valuationFigis.stream().anyMatch(f -> !existing.contains(f));
                    }
                }
                if (needsRebuild) {
                    stockValuationService.rebuildFinancialPeriodsInCurrentTx(valuationFigis);
                    valuationInputChanged = true;
                }
            }

            // 5. 無拆股對帳差異或個股拆股失敗且非手動局部補抓時，推進整組的單一斷點水位
            if (!manualFigisMode && !splitDiscrepancy && !splitFailed) {
                for (StockStatus status : lockedStatuses.values()) {
                    status.setFundamentalSyncDate(todayHk);
                }
            }
            statuses.saveAll(lockedStatuses.values());
            entityManager.flush();

            return new GroupCommitResult(valuationInputChanged, splitDiscrepancy, splitFailed, reportsSaved, secFactChanges);
        });
    }

    private record SplitSaveOutcome(boolean changed, boolean discrepancy) {}

    private SplitSaveOutcome applyFullSplitsInCurrentTx(Stock stock, StockStatus status, List<StockSplit> downloaded,
                                                        LocalDate from, LocalDate through) {
        Map<LocalDate, List<StockSplit>> stored = splitRepository.findByFigiAndExecutionDateBetween(stock.getFigi(), from, through)
                .stream().collect(Collectors.groupingBy(StockSplit::getExecutionDate));
        List<String> replaced = new ArrayList<>();
        List<StockSplit> changed = new ArrayList<>();
        for (StockSplit row : downloaded) {
            List<StockSplit> previous = stored.remove(row.getExecutionDate());
            if (previous != null && previous.size() == 1 && sameSplitPayload(previous.getFirst(), row)) continue;
            if (previous != null) {
                for (StockSplit old : previous) {
                    if (!old.getSourceEventId().equals(row.getSourceEventId())) replaced.add(old.getSourceEventId());
                }
            }
            changed.add(row);
        }
        if (!replaced.isEmpty()) splitRepository.deleteAllByIdInBatch(replaced);
        if (!changed.isEmpty()) splitRepository.saveAll(changed);
        status.setSplitFromDate(from);
        boolean discrepancy = !stored.isEmpty();
        if (!discrepancy) {
            status.setSplitDate(through);
            status.setSplitMessage(null);
        } else {
            LocalDate firstMissing = stored.keySet().stream().min(LocalDate::compareTo).orElseThrow();
            status.setSplitDate(firstMissing.minusDays(1));
            status.setSplitMessage("ERROR:" + SPLIT_MISSING_MARKER + ":" + firstMissing + ":dates=" + stored.size());
        }
        return new SplitSaveOutcome(!changed.isEmpty() || !replaced.isEmpty(), discrepancy);
    }

    private boolean applyIncrementalSplitsInCurrentTx(Stock stock, StockStatus status, List<StockSplit> downloaded,
                                                      LocalDate from, LocalDate through, boolean preserveExistingDiscrepancy) {
        boolean changedAny = false;
        if (!downloaded.isEmpty()) {
            Map<LocalDate, List<StockSplit>> stored = splitRepository.findByFigiAndExecutionDateBetween(stock.getFigi(), from, through)
                    .stream().collect(Collectors.groupingBy(StockSplit::getExecutionDate));
            List<String> replaced = new ArrayList<>();
            List<StockSplit> changed = new ArrayList<>();
            for (StockSplit row : downloaded) {
                List<StockSplit> previous = stored.remove(row.getExecutionDate());
                if (previous != null && previous.size() == 1 && sameSplitRatio(previous.getFirst(), row)) continue;
                if (previous != null) {
                    for (StockSplit old : previous) {
                        if (!old.getSourceEventId().equals(row.getSourceEventId())) replaced.add(old.getSourceEventId());
                    }
                }
                changed.add(row);
            }
            if (!replaced.isEmpty()) splitRepository.deleteAllByIdInBatch(replaced);
            if (!changed.isEmpty()) splitRepository.saveAll(changed);
            changedAny = !changed.isEmpty() || !replaced.isEmpty();
        }
        if (status.getSplitFromDate() == null) status.setSplitFromDate(splitHistoryStartDate);
        if (!preserveExistingDiscrepancy && !hasSplitDiscrepancyMessage(status)) {
            if (status.getSplitDate() == null || status.getSplitDate().isBefore(through)) {
                status.setSplitDate(through);
            }
            if (!isWarningSplitMessage(status)) {
                status.setSplitMessage(null);
            }
        }
        return changedAny;
    }

    private boolean applyFinancialReportsInCurrentTx(String figi, StockStatus currentStatus, List<DownloadedReport> downloadedReports) {
        LocalDate firstPeriodEnd = downloadedReports.stream().map(DownloadedReport::sourceDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate latestPeriodEnd = downloadedReports.stream().map(value -> value.report().getPeriodEnd()).max(LocalDate::compareTo).orElseThrow();
        Map<FinancialReportId, FinancialReport> existing = new HashMap<>();
        for (FinancialReport report : reports.findByFigiAndPeriodEndBetween(figi, firstPeriodEnd, latestPeriodEnd)) {
            existing.put(reportId(report), report);
        }
        List<FinancialReport> changed = new ArrayList<>();
        Set<FinancialReport> replaced = new LinkedHashSet<>();
        for (DownloadedReport downloaded : downloadedReports) {
            FinancialReport report = downloaded.report();
            FinancialReport previous = existing.get(reportId(report));
            FinancialReportId sourceId = new FinancialReportId(figi, report.getStatementType(), downloaded.sourceDate(), report.getPeriodType());
            if (downloaded.existingOnly() && previous == null && !existing.containsKey(sourceId)) continue;
            FinancialReport shifted = null;
            if (!downloaded.sourceDate().equals(report.getPeriodEnd())) {
                shifted = existing.remove(sourceId);
                if (shifted != null) replaced.add(shifted);
            }
            if (previous != null && shifted == null && sameReportPayload(previous, report)) continue;
            FinancialReport first = previous == null || shifted != null && shifted.getObservedAt().isBefore(previous.getObservedAt())
                    ? shifted : previous;
            if (first != null) report.setObservedAt(first.getObservedAt());
            if (previous != null) replaced.add(previous);
            changed.add(report);
        }
        if (!replaced.isEmpty()) {
            replaced.forEach(reports::delete);
            reports.flush();
        }
        changed.forEach(entityManager::persist);
        LocalDate currentWatermark = currentStatus.getFinancialReportDate();
        if (currentWatermark == null || latestPeriodEnd.isAfter(currentWatermark)) {
            currentStatus.setFinancialReportDate(latestPeriodEnd);
        }
        return !changed.isEmpty() || !replaced.isEmpty();
    }

    private record SecSaveOutcome(int factChanges, boolean valuationInputChanged) {}

    private SecSaveOutcome applySecFactsInCurrentTx(String cik, List<Stock> currentCikMembers,
                                                    Map<String, StockStatus> lockedStatuses,
                                                    List<SecFinancialFact> downloaded, String resolvedSic) {
        if (downloaded.isEmpty()) {
            boolean sicChanged = resolvedSic != null && !currentCikMembers.isEmpty()
                    && updateSicIfChangedInCurrentTx(currentCikMembers, resolvedSic);
            return new SecSaveOutcome(0, sicChanged);
        }
        Map<FactKey, SecFinancialFact> existing = new HashMap<>();
        for (SecFinancialFact fact : secFacts.findByCikAndPeriodEndGreaterThanEqual(cik, BACKFILL_START)) {
            existing.put(SecFinancialFactConverter.key(fact), fact);
        }
        List<SecFinancialFact> inserts = new ArrayList<>();
        List<SecFinancialFact> updates = new ArrayList<>();
        for (SecFinancialFact incoming : downloaded) {
            SecFinancialFact previous = existing.get(SecFinancialFactConverter.key(incoming));
            if (previous == null) {
                inserts.add(incoming);
            } else if (!SecFinancialFactConverter.sameDisclosure(previous, incoming)) {
                previous.setValue(incoming.getValue());
                previous.setFiled(incoming.getFiled());
                previous.setForm(incoming.getForm());
                previous.setAvailableFromDate(incoming.getAvailableFromDate());
                previous.setReportFiscalYear(incoming.getReportFiscalYear());
                previous.setReportFiscalPeriod(incoming.getReportFiscalPeriod());
                updates.add(previous);
            }
        }

        LocalDate latest = downloaded.stream()
                .filter(fact -> "us-gaap".equals(fact.getTaxonomy()) && !"shares".equals(fact.getUnit())
                        && !fact.getPeriodEnd().isAfter(fact.getFiled()))
                .map(SecFinancialFact::getPeriodEnd).max(Comparator.naturalOrder()).orElse(null);

        boolean sicChanged = resolvedSic != null && !currentCikMembers.isEmpty()
                && updateSicIfChangedInCurrentTx(currentCikMembers, resolvedSic);

        LocalDateTime updatedAt = dateTimeService.nowLocalDateTime();
        for (SecFinancialFact updated : updates) {
            updated.setUpdatedAt(updatedAt);
        }
        for (SecFinancialFact inserted : inserts) {
            inserted.setUpdatedAt(updatedAt);
            entityManager.persist(inserted);
        }
        if (latest != null) {
            for (Stock member : currentCikMembers) {
                StockStatus status = lockedStatuses.get(member.getFigi());
                if (status != null) {
                    LocalDate currentWatermark = status.getSecFinancialFactsDate();
                    if (currentWatermark == null || latest.isAfter(currentWatermark)) {
                        status.setSecFinancialFactsDate(latest);
                    }
                }
            }
        }
        int factChanges = inserts.size() + updates.size();
        return new SecSaveOutcome(factChanges, factChanges > 0 || sicChanged);
    }

    private boolean updateSicIfChangedInCurrentTx(List<Stock> currentCikMembers, String resolvedSic) {
        List<String> needsUpdate = currentCikMembers.stream()
                .filter(s -> !Objects.equals(resolvedSic, s.getSic()))
                .map(Stock::getFigi).toList();
        if (needsUpdate.isEmpty()) return false;
        List<Stock> managed = stocks.findAllById(needsUpdate);
        for (Stock s : managed) {
            s.setSic(resolvedSic);
        }
        stocks.saveAllAndFlush(managed);
        return true;
    }

    private List<DownloadedReport> downloadStatement(FutuService.ObservationSession session, Stock stock,
                                                     FinancialStatementsType statementType, LocalDate firstPeriodEnd, int pageSize) {
        List<DownloadedReport> statementReports = new ArrayList<>();
        Set<FinancialReportId> seenReports = new HashSet<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        while (true) {
            QotGetFinancialsStatements.S2C page = session.financialStatements(stock.getSymbol(), statementType, cursor, pageSize);
            if (page == null) return null;
            Instant observedAt = dateTimeService.now();
            Map<Long, String> fieldNames = new HashMap<>();
            for (QotGetFinancialsStatements.FinancialFieldInfo field : page.getStructureListList()) {
                if (field.hasFieldId() && field.hasDisplayName()) fieldNames.put(field.getFieldId(), field.getDisplayName());
            }

            boolean reachedOlderReports = false;
            for (QotGetFinancialsStatements.FinancialReport sourceReport : page.getReportListList()) {
                LocalDate sourceDate = usMarketDate(sourceReport.hasDateTimeStr() ? sourceReport.getDateTimeStr() : null,
                        sourceReport.hasDateTime() ? sourceReport.getDateTime() : null);
                LocalDate periodEnd = financialPeriodEnd(sourceReport);
                if (periodEnd == null) throw new IllegalStateException("FINANCIAL_REPORT_PERIOD_END_MISSING");
                if (periodEnd.isBefore(firstPeriodEnd)) reachedOlderReports = true;
                FinancialReport report = futuConverter.toFinancialReport(sourceReport, stock.getFigi(), statementType, periodEnd, observedAt, fieldNames);
                FinancialReportId id = reportId(report);
                if (!seenReports.add(id)) throw new IllegalStateException("FINANCIAL_REPORT_DUPLICATE:" + id);
                statementReports.add(new DownloadedReport(report, sourceDate, periodEnd.isBefore(firstPeriodEnd)));
            }

            String nextCursor = page.getNextKey();
            if (reachedOlderReports || nextCursor.isBlank() || "-1".equals(nextCursor)) break;
            if (!seenCursors.add(nextCursor)) throw new IllegalStateException("FINANCIAL_STATEMENT_CURSOR_INVALID:" + statementType);
            cursor = nextCursor;
        }
        return statementReports;
    }

    private LocalDate incrementalSplitFrom(LocalDate splitDate) {
        LocalDate candidate = splitDate.minusDays(INCREMENTAL_SPLIT_LOOKBACK_DAYS);
        return candidate.isBefore(splitHistoryStartDate) ? splitHistoryStartDate : candidate;
    }

    private List<StockSplit> convertMassiveSplits(Stock stock, List<MassiveSplit> source, LocalDate from, LocalDate through, Instant observedAt) {
        Map<LocalDate, StockSplit> events = new TreeMap<>();
        for (MassiveSplit item : source) {
            LocalDate date = item.getExecutionDate();
            if (date == null) throw new IllegalStateException("SPLIT_SOURCE_DATE_MISSING");
            if (date.isBefore(from) || date.isAfter(through)) continue;
            if (item.getSplitFrom() == null || item.getSplitTo() == null
                    || item.getSplitFrom().signum() <= 0 || item.getSplitTo().signum() <= 0)
                throw new IllegalStateException("SPLIT_SOURCE_VALUE_INVALID:" + date);
            if (item.getSplitFrom().compareTo(item.getSplitTo()) == 0) continue;
            StockSplit row = new StockSplit();
            String idSuffix = item.getId() != null && !item.getId().isBlank()
                    ? item.getId().trim() : stock.getFigi() + ":" + date;
            row.setSourceEventId("MASSIVE:" + idSuffix);
            row.setFigi(stock.getFigi());
            row.setExecutionDate(date);
            row.setSplitFrom(item.getSplitFrom());
            row.setSplitTo(item.getSplitTo());
            row.setObservedAt(observedAt);
            if (events.putIfAbsent(date, row) != null)
                throw new IllegalStateException("SPLIT_DUPLICATE_SOURCE_DATE:" + date);
        }
        return List.copyOf(events.values());
    }

    private List<StockSplit> convertFutuSplits(Stock stock, List<QotCommon.Rehab> source, LocalDate from, LocalDate through, Instant observedAt) {
        if (source == null) throw new IllegalStateException("SPLIT_SOURCE_DATA_MISSING");
        Map<LocalDate, StockSplit> events = new TreeMap<>();
        for (QotCommon.Rehab input : source) {
            StockSplit row = futuConverter.toStockSplit(input, stock.getFigi(), from, through, observedAt);
            if (row == null) continue;
            if (events.putIfAbsent(row.getExecutionDate(), row) != null)
                throw new IllegalStateException("SPLIT_DUPLICATE_SOURCE_DATE:" + row.getExecutionDate());
        }
        return List.copyOf(events.values());
    }

    private boolean hasSplitDiscrepancyMessage(StockStatus status) {
        return status != null && status.getSplitMessage() != null && status.getSplitMessage().contains(SPLIT_MISSING_MARKER);
    }

    private boolean sameSplitPayload(StockSplit left, StockSplit right) {
        return left.getSourceEventId().equals(right.getSourceEventId()) && sameSplitRatio(left, right);
    }

    private boolean sameSplitRatio(StockSplit left, StockSplit right) {
        return left.getSplitFrom().compareTo(right.getSplitFrom()) == 0 && left.getSplitTo().compareTo(right.getSplitTo()) == 0;
    }

    private boolean sameReportPayload(FinancialReport first, FinancialReport second) {
        return Objects.equals(first.getFiscalYear(), second.getFiscalYear()) && Objects.equals(first.getPeriod(), second.getPeriod())
                && Objects.equals(first.getCurrency(), second.getCurrency()) && Objects.equals(first.getAccountingStandards(), second.getAccountingStandards())
                && Objects.equals(first.getAuditorReport(), second.getAuditorReport()) && sameReportItems(first.getItems(), second.getItems());
    }

    private boolean sameReportItems(List<FinancialReport.Item> first, List<FinancialReport.Item> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            FinancialReport.Item a = first.get(index);
            FinancialReport.Item b = second.get(index);
            if (a.fieldId() != b.fieldId() || !Objects.equals(a.name(), b.name()) || !sameNumber(a.value(), b.value())
                    || !sameNumber(a.yearOverYearPercent(), b.yearOverYearPercent()) || !sameNumber(a.quarterOverQuarterPercent(), b.quarterOverQuarterPercent()))
                return false;
        }
        return true;
    }

    private boolean sameNumber(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private FinancialReportId reportId(FinancialReport report) {
        return new FinancialReportId(report.getFigi(), report.getStatementType(), report.getPeriodEnd(), report.getPeriodType());
    }
}
