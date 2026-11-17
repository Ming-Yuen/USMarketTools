package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecEdgarFeedArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component(TaskCode.SEC_FINANCIAL_FACTS_DAILY_SYNC)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class SecFinancialFactsDailySyncJob implements Job {
    private static final LocalDate BACKFILL_START = LocalDate.of(2014, 1, 1);

    private final StockRepository stocks;
    private final SecFinancialFactRepository facts;
    private final StockStatusRepository statuses;
    private final SecCompanyFactsArchive archives;
    private final SecFinancialStatementArchive statementArchives;
    private final SecFinancialFactConverter converter;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final DateTimeService clock;
    private final SecIssuerHistory issuerHistory;
    private final StockValuationDailyTask valuation;
    private final SecEdgarFeedArchive edgarFeed;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // CIK 去重讀 ZIP；同發行人的股票於同一交易更新水位。
        List<ActiveStockWithStatus> candidates = stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE);
        if (candidates.isEmpty()) {
            log.info("SEC financial facts skipped | reason=NO_TARGETS");
            return;
        }
        int failed = 0;
        int empty = 0;
        int saved = 0;
        List<String> missingEntries = new ArrayList<>();
        Map<String, List<ActiveStockWithStatus>> issuers = candidates.stream()
                .collect(Collectors.groupingBy(row -> row.stock().getCik(), LinkedHashMap::new, Collectors.toList()));
        for (String cik : List.copyOf(issuers.keySet())) {
            for (String predecessor : issuerHistory.predecessors(cik)) issuers.putIfAbsent(predecessor, List.of());
        }
        // ZIP 整體失敗直接中止，不以舊快取冒充已完成來源檢查。
        try (SecCompanyFactsArchive.Snapshot archive = archives.prepare()) {
            Map<String, LocalDate> storedPeriods = storedFinancialPeriods(issuers);
            for (Map.Entry<String, List<ActiveStockWithStatus>> target : issuers.entrySet()) {
                String cik = target.getKey();
                List<String> figis = target.getValue().stream().map(row -> row.stock().getFigi()).toList();
                LocalDate storedPeriod = storedPeriods.get(cik);
                LocalDate periodFrom = periodFrom(target.getValue(), storedPeriod);
                try {
                    JsonNode company = archive.read(cik);
                    Instant observedAt = clock.now();
                    List<SecFinancialFact> downloaded = converter.convert(cik, company, observedAt, periodFrom);
                    List<SecFinancialFact> equityHistory = converter.historicalEquityComponents(cik, company, observedAt, BACKFILL_START, periodFrom);
                    if (downloaded.isEmpty() && equityHistory.isEmpty() && storedPeriod == null) {
                        empty++;
                        continue;
                    }
                    Integer inserted = transactions.execute(transaction -> saveCompany(cik, figis, downloaded, equityHistory, periodFrom, storedPeriod));
                    saved += inserted == null ? 0 : inserted;
                    if (downloaded.isEmpty()) empty++;
                } catch (SecCompanyFactsArchive.EntryMissingException ex) {
                    // ZIP 缺少此發行人，保留舊 facts 及水位，下次照常檢查。
                    missingEntries.add(cik);
                } catch (RuntimeException ex) {
                    failed++;
                    // 單 CIK 回滾且不推水位，其他公司繼續；同 ZIP 可重跑，不需另一份逐股狀態。
                    log.warn("SEC financial facts CIK failed | cik={} figis={} reason={}:{}", cik, figis, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
            if (!missingEntries.isEmpty()) log.warn("SEC Company Facts ZIP missing entries | count={} ciks={}",
                    missingEntries.size(), missingEntries.stream().limit(30).toList());
            log.info("SEC financial facts completed | ciks={} missingEntries={} noFacts={} inserted={} failed={} archive={}",
                    issuers.size(), missingEntries.size(), empty, saved, failed, archive.version());
        }
        statementArchives.downloadMissing(BACKFILL_START.getYear());
        prepareEdgarCache();
        if (failed > 0) throw new JobExecutionException("SEC_FINANCIAL_FACTS_PARTIAL_FAILURE:" + failed + "/" + issuers.size());
    }

    private void prepareEdgarCache() throws JobExecutionException {
        Map<Instant, List<SecFinancialFact>> candidates = valuation.pendingEdgarFilings(clock.now(), edgarFeed.automaticFrom());
        if (candidates.isEmpty()) return;
        SecEdgarFeedArchive.Session session = edgarFeed.downloadingSession();
        Set<LocalDate> failedDates = new TreeSet<>();
        candidates.forEach((cutoff, facts) -> failedDates.addAll(session.confirm(facts, cutoff).failedDates()));
        log.info("SEC EDGAR Feed cache prepared | archivesIndexed={} failedDates={}", session.archivesOpened(), failedDates);
        if (!failedDates.isEmpty()) throw new JobExecutionException("SEC_EDGAR_FEED_PARTIAL_FAILURE:" + failedDates);
    }

    private Map<String, LocalDate> storedFinancialPeriods(Map<String, List<ActiveStockWithStatus>> issuers) {
        List<String> ciks = issuers.entrySet().stream().filter(entry -> entry.getValue().isEmpty() || entry.getValue().stream()
                .anyMatch(target -> target.status().getSecFinancialFactsDate() != null)).map(Map.Entry::getKey).toList();
        Map<String, LocalDate> periods = new HashMap<>();
        if (!ciks.isEmpty()) {
            for (Object[] row : facts.findLatestFinancialPeriodEnds(ciks)) periods.put((String) row[0], (LocalDate) row[1]);
        }
        return periods;
    }

    private LocalDate periodFrom(List<ActiveStockWithStatus> targets, LocalDate storedPeriod) {
        if (storedPeriod == null || targets.stream().anyMatch(target -> target.status().getSecFinancialFactsDate() == null)) return BACKFILL_START;
        // 歷史前身 CIK 沒有股票狀態列，從自身已入庫的財報期末繼續。
        LocalDate oldest = targets.stream().map(target -> target.status().getSecFinancialFactsDate()).min(Comparator.naturalOrder()).orElse(storedPeriod);
        // 舊版的 ZIP 日期可能晚於已入庫的最新財報期末。
        LocalDate start = oldest.isAfter(storedPeriod) ? storedPeriod : oldest;
        return start.isBefore(BACKFILL_START) ? BACKFILL_START : start;
    }

    private int saveCompany(String cik, List<String> figis, List<SecFinancialFact> downloaded, List<SecFinancialFact> equityHistory,
                            LocalDate periodFrom, LocalDate storedPeriod) {
        List<StockStatus> locked = figis.isEmpty() ? List.of() : statuses.findAllByIdForUpdate(figis);
        if (locked.size() != figis.size()) throw new IllegalStateException("SEC_FINANCIAL_FACTS_STOCK_STATUS_MISSING:" + cik);
        Map<FactKey, SecFinancialFact> existing = new HashMap<>();
        if (!downloaded.isEmpty()) {
            List<SecFinancialFact> prior = facts.findByCikAndPeriodEndGreaterThanEqual(cik, periodFrom);
            for (SecFinancialFact fact : prior) existing.put(SecFinancialFactConverter.key(fact), fact);
        }
        if (!equityHistory.isEmpty()) {
            for (SecFinancialFact fact : facts.findByCikAndTagInAndPeriodEndLessThan(cik, SecFinancialFactConverter.EQUITY_COMPONENT_TAGS, periodFrom))
                existing.put(SecFinancialFactConverter.key(fact), fact);
        }
        boolean rebuild = locked.stream().anyMatch(status -> status.getSecFinancialFactsDate() == null);
        List<SecFinancialFact> inserts = new ArrayList<>();
        boolean removed = false;
        List<SecFinancialFact> disclosures = new ArrayList<>(downloaded);
        disclosures.addAll(equityHistory);
        for (SecFinancialFact fact : disclosures) {
            SecFinancialFact previous = existing.get(SecFinancialFactConverter.key(fact));
            if (previous != null) {
                boolean sameDisclosure = SecFinancialFactConverter.sameDisclosure(previous, fact);
                if (!rebuild) {
                    if (!sameDisclosure) throw new IllegalStateException("SEC_FACT_EXISTING_CONFLICT:" + SecFinancialFactConverter.key(fact));
                    previous.setReportFiscalYear(fact.getReportFiscalYear());
                    previous.setReportFiscalPeriod(fact.getReportFiscalPeriod());
                    continue;
                }
                if (sameDisclosure) fact.setObservedAt(previous.getObservedAt());
                entityManager.remove(previous);
                removed = true;
            }
            inserts.add(fact);
        }
        if (removed) entityManager.flush();
        if (!inserts.isEmpty()) {
            LocalDateTime updatedAt = clock.nowLocalDateTime();
            for (SecFinancialFact fact : inserts) {
                fact.setUpdatedAt(updatedAt);
                entityManager.persist(fact);
            }
        }
        LocalDate latest = downloaded.stream()
                .filter(fact -> "us-gaap".equals(fact.getTaxonomy()) && !"shares".equals(fact.getUnit())
                        && !fact.getPeriodEnd().isAfter(fact.getFiled()))
                .map(SecFinancialFact::getPeriodEnd).max(Comparator.naturalOrder()).orElse(null);
        if (storedPeriod != null && (latest == null || storedPeriod.isAfter(latest))) latest = storedPeriod;
        if (latest != null) {
            for (StockStatus status : locked) {
                if (!downloaded.isEmpty() || status.getSecFinancialFactsDate() != null) status.setSecFinancialFactsDate(latest);
            }
        }
        entityManager.flush();
        return inserts.size();
    }
}
