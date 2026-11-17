package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon.FinancialStatementsType;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.key.FinancialReportId;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.*;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.usMarketDate;
import static com.ykm.stock.analytics.converter.futu.FutuQuoteConverter.financialPeriodEnd;

@Component(TaskCode.FINANCIAL_REPORT_DAILY_SYNC)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class FinancialReportDailySyncJob implements Job {
    private static final LocalDate BACKFILL_START = LocalDate.of(2014, 1, 1);
    private static final int BACKFILL_PAGE_SIZE = 50;
    private static final int INCREMENTAL_PAGE_SIZE = 10;
    private static final List<FinancialStatementsType> STATEMENT_TYPES = List.of(FinancialStatementsType_Income,
            FinancialStatementsType_BalanceSheet, FinancialStatementsType_CashFlow, FinancialStatementsType_MainIndex);
    private record DownloadedReport(FinancialReport report, LocalDate sourceDate, boolean existingOnly) {}

    private final StockRepository stocks;
    private final FutuService futu;
    private final StockStatusRepository statuses;
    private final FinancialReportRepository reports;
    private final EntityManager entityManager;
    private final FutuQuoteConverter converter;
    private final TransactionTemplate transactions;
    private final DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) {
        // 一次查詢有效 CS／OS／ADRC 股票及財報水位。
        List<ActiveStockWithStatus> candidates = stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE);
        if (candidates.isEmpty()) {
            log.info("Financial report sync skipped | reason=NO_TARGETS");
            return;
        }
        int reportsSaved = 0;
        int stocksWithoutReports = 0;
        int unsupportedStocks = 0;
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            Set<String> supportedSymbols = session.supportedUsFinancialReportStocks();

            // 逐股同步富途支援的代碼。有水位就從當日起更新，否則從 2014 年回補。
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
                    // 四表下載完成後，在同一交易保存財報並推進水位。
                    transactions.executeWithoutResult(transaction -> replaceReportsAndAdvanceWatermark(stock.getFigi(), downloadedReports));
                    reportsSaved += downloadedReports.size();
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("FINANCIAL_REPORT_SYNC_FAILED:" + stock.getFigi() + ":" + stock.getSymbol(), ex);
                }
            }
            log.info("Financial reports saved | targets={} unsupported={} empty={} reports={}",
                    candidates.size(), unsupportedStocks, stocksWithoutReports, reportsSaved);
        }
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

            // 富途由新至舊分頁。遇到早於起點的報表，讀完這頁就停止翻頁。
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
        for (FinancialReport report : reports.findByFigiAndPeriodEndBetween(figi, firstPeriodEnd, latestPeriodEnd)) existing.put(reportId(report), report);
        List<FinancialReport> changed = new ArrayList<>();
        Set<FinancialReport> replaced = new LinkedHashSet<>();
        for (DownloadedReport downloaded : downloadedReports) {
            FinancialReport report = downloaded.report();
            FinancialReport previous = existing.get(reportId(report));
            FinancialReportId sourceId = new FinancialReportId(figi, report.getStatementType(), downloaded.sourceDate(), report.getPeriodType());
            // 已回傳的舊報期也可能改版；仍只同步原水位範圍內的新報表。
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
        // 先刪除已受管理的舊列，再以新實體插回相同主鍵。
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
            // JSONB 會展開科學記號，僅 BigDecimal scale 不同時沿用原列。
            if (a.fieldId() != b.fieldId() || !Objects.equals(a.name(), b.name()) || !sameNumber(a.value(), b.value())
                    || !sameNumber(a.yearOverYearPercent(), b.yearOverYearPercent()) || !sameNumber(a.quarterOverQuarterPercent(), b.quarterOverQuarterPercent())) return false;
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
