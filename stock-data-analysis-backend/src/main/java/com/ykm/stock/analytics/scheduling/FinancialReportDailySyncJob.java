package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon.FinancialStatementsType;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.CompanySubmissions;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.key.FinancialReportId;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_Income;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.financialPeriodEnd;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.usMarketDate;

@Component(TaskCode.FINANCIAL_REPORT_DAILY_SYNC)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class FinancialReportDailySyncJob implements Job {
    private static final LocalDate BACKFILL_START = LocalDate.of(2014, 1, 1);
    private static final int BACKFILL_PAGE_SIZE = 50;
    private static final int INCREMENTAL_PAGE_SIZE = 10;
    private static final int RECENT_FILING_LOOKBACK_DAYS = 14;
    private static final List<FinancialStatementsType> STATEMENT_TYPES = List.of(
            FinancialStatementsType_Income, FinancialStatementsType_BalanceSheet, FinancialStatementsType_CashFlow);

    private record DownloadedReport(FinancialReport report, LocalDate sourceDate, boolean existingOnly) {}

    private final StockRepository stocks;
    private final FutuService futu;
    private final StockStatusRepository statuses;
    private final FinancialReportRepository reports;
    private final MarketEventRepository marketEvents;
    private final SecFinancialFactRepository secFacts;
    private final SecSubmissionsArchive submissionsArchive;
    private final EntityManager entityManager;
    private final FutuQuoteConverter converter;
    private final TransactionTemplate transactions;
    private final DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) {
        List<ActiveStockWithStatus> universe = stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE);
        if (universe.isEmpty()) {
            log.info("Financial report sync skipped | reason=NO_TARGETS");
            return;
        }
        List<ActiveStockWithStatus> candidates = selectTargets(universe, context);
        if (candidates.isEmpty()) {
            log.info("Financial report sync skipped | reason=NO_PENDING_OR_RECENT_FILINGS universe={}", universe.size());
            return;
        }

        int reportsSaved = 0;
        int stocksWithoutReports = 0;
        int unsupportedStocks = 0;
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            Set<String> supportedSymbols = session.supportedUsFinancialReportStocks();
            for (ActiveStockWithStatus candidate : candidates) {
                Stock stock = candidate.stock();
                if (!supportedSymbols.contains(stock.getSymbol())) {
                    unsupportedStocks++;
                    continue;
                }
                LocalDate watermark = candidate.status().getFinancialReportDate();
                LocalDate firstPeriodEnd = watermark == null ? BACKFILL_START : watermark;
                int pageSize = watermark == null ? BACKFILL_PAGE_SIZE : INCREMENTAL_PAGE_SIZE;
                try {
                    List<DownloadedReport> downloadedReports = new ArrayList<>();
                    for (FinancialStatementsType statementType : STATEMENT_TYPES) {
                        downloadedReports.addAll(downloadStatement(session, stock, statementType, firstPeriodEnd, pageSize));
                    }
                    if (downloadedReports.isEmpty()) {
                        stocksWithoutReports++;
                        continue;
                    }
                    transactions.executeWithoutResult(transaction -> replaceReportsAndAdvanceWatermark(stock.getFigi(), downloadedReports));
                    reportsSaved += downloadedReports.size();
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("FINANCIAL_REPORT_SYNC_FAILED:" + stock.getFigi() + ":" + stock.getSymbol(), ex);
                }
            }
            log.info("Financial reports saved | universe={} targets={} unsupported={} empty={} reports={}",
                    universe.size(), candidates.size(), unsupportedStocks, stocksWithoutReports, reportsSaved);
        }
    }

    /** 手動指定 FIGI 或週六全市場巡檢同步全部；平日僅同步無水位新股、近 14 天財報日曆或 SEC submissions 新申報股票。 */
    private List<ActiveStockWithStatus> selectTargets(List<ActiveStockWithStatus> universe, JobExecutionContext context) {
        JobDataMap parameters = context == null ? null : context.getMergedJobDataMap();
        String requestedFigis = parameters == null ? null : parameters.getString("figis");
        if (requestedFigis != null && !requestedFigis.isBlank()) {
            Set<String> selected = Arrays.stream(requestedFigis.split(",")).map(String::trim)
                    .filter(value -> !value.isBlank()).collect(Collectors.toSet());
            return universe.stream().filter(item -> selected.contains(item.stock().getFigi())).toList();
        }
        if (isWeeklyRefresh(context)) {
            return universe;
        }

        List<ActiveStockWithStatus> existingWatermarkStocks = universe.stream()
                .filter(item -> item.status().getFinancialReportDate() != null).toList();
        if (existingWatermarkStocks.isEmpty()) {
            return universe;
        }

        LocalDate todayNy = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate lookbackFrom = todayNy.minusDays(RECENT_FILING_LOOKBACK_DAYS);
        Set<String> recentEarningsFigis = marketEvents.findEarningsFigisBetween(lookbackFrom, todayNy);

        Set<String> candidateCiks = existingWatermarkStocks.stream()
                .map(item -> item.stock().getCik()).filter(Objects::nonNull)
                .filter(cik -> !cik.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> recentSecCiks = new HashSet<>();
        if (!candidateCiks.isEmpty()) {
            recentSecCiks.addAll(secFacts.findRecentPeriodicFilingCiks(candidateCiks, lookbackFrom));
            collectRecentSubmissionCiks(candidateCiks, lookbackFrom, recentSecCiks);
        }

        return universe.stream().filter(item -> item.status().getFinancialReportDate() == null
                || recentEarningsFigis.contains(item.stock().getFigi())
                || (item.stock().getCik() != null && recentSecCiks.contains(item.stock().getCik()))).toList();
    }

    private void collectRecentSubmissionCiks(Set<String> ciks, LocalDate lookbackFrom, Set<String> recentSecCiks) {
        try (SecSubmissionsArchive.Snapshot snapshot = submissionsArchive.prepare()) {
            if (snapshot == null) return;
            for (String cik : ciks) {
                if (recentSecCiks.contains(cik)) continue;
                Optional<CompanySubmissions> sub = snapshot.findCompany(cik);
                if (sub.isPresent() && sub.get().latestPeriodicFilingDate() != null
                        && !sub.get().latestPeriodicFilingDate().isBefore(lookbackFrom)) {
                    recentSecCiks.add(cik);
                }
            }
        }
    }

    private boolean isWeeklyRefresh(JobExecutionContext context) {
        return context != null && context.getTrigger() instanceof CronTrigger && context.getScheduledFireTime() != null
                && context.getScheduledFireTime().toInstant().atZone(ZoneId.of(JobScheduleReconciler.SCHEDULE_TIMEZONE)).getDayOfWeek() == DayOfWeek.SATURDAY;
    }

    private List<DownloadedReport> downloadStatement(FutuService.ObservationSession session, Stock stock,
                                                    FinancialStatementsType statementType, LocalDate firstPeriodEnd, int pageSize) {
        List<DownloadedReport> statementReports = new ArrayList<>();
        Set<FinancialReportId> seenReports = new HashSet<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        while (true) {
            QotGetFinancialsStatements.S2C page = session.financialStatements(stock.getSymbol(), statementType, cursor, pageSize);
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
                FinancialReport report = converter.toFinancialReport(sourceReport, stock.getFigi(), statementType, periodEnd, observedAt, fieldNames);
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

    private void replaceReportsAndAdvanceWatermark(String figi, List<DownloadedReport> downloadedReports) {
        List<StockStatus> lockedStatuses = statuses.findAllByIdForUpdate(List.of(figi));
        if (lockedStatuses.isEmpty()) throw new IllegalStateException("FINANCIAL_REPORT_STOCK_STATUS_MISSING:" + figi);
        StockStatus currentStatus = lockedStatuses.getFirst();
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
            if (previous != null && shifted == null && samePayload(previous, report)) continue;
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
        if (currentWatermark == null || latestPeriodEnd.isAfter(currentWatermark)) currentStatus.setFinancialReportDate(latestPeriodEnd);
    }

    private boolean samePayload(FinancialReport first, FinancialReport second) {
        return Objects.equals(first.getFiscalYear(), second.getFiscalYear()) && Objects.equals(first.getPeriod(), second.getPeriod())
                && Objects.equals(first.getCurrency(), second.getCurrency()) && Objects.equals(first.getAccountingStandards(), second.getAccountingStandards())
                && Objects.equals(first.getAuditorReport(), second.getAuditorReport()) && sameItems(first.getItems(), second.getItems());
    }

    private boolean sameItems(List<FinancialReport.Item> first, List<FinancialReport.Item> second) {
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
