package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.CompanySubmissions;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.FilingSubmission;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.enhancement.SecCustomTagMappingService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@Component(TaskCode.SEC_FINANCIAL_FACTS_DAILY_SYNC)
@DisallowConcurrentExecution
@Slf4j
public class SecFinancialFactsDailySyncJob implements Job {
    private static final LocalDate BACKFILL_START = LocalDate.of(2014, 1, 1);

    private final StockRepository stocks;
    private final SecFinancialFactRepository facts;
    private final StockStatusRepository statuses;
    private final SecCompanyFactsArchive archives;
    private final SecSubmissionsArchive submissionsArchive;
    private final SecFinancialFactConverter converter;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final DateTimeService clock;
    private final SecIssuerHistory issuerHistory;
    private final StockValuationService stockValuationService;
    @Autowired(required = false)
    private ObjectProvider<SecCustomTagMappingService> customTagMappingServiceProvider;

    public SecFinancialFactsDailySyncJob(StockRepository stocks, SecFinancialFactRepository facts,
                                         StockStatusRepository statuses, SecCompanyFactsArchive archives,
                                         SecSubmissionsArchive submissionsArchive, SecFinancialFactConverter converter,
                                         TransactionTemplate transactions, EntityManager entityManager,
                                         DateTimeService clock, SecIssuerHistory issuerHistory) {
        this(stocks, facts, statuses, archives, submissionsArchive, converter,
                transactions, entityManager, clock, issuerHistory, null);
    }

    @Autowired
    public SecFinancialFactsDailySyncJob(StockRepository stocks, SecFinancialFactRepository facts,
                                         StockStatusRepository statuses, SecCompanyFactsArchive archives,
                                         SecSubmissionsArchive submissionsArchive, SecFinancialFactConverter converter,
                                         TransactionTemplate transactions, EntityManager entityManager,
                                         DateTimeService clock, SecIssuerHistory issuerHistory,
                                         StockValuationService stockValuationService) {
        this.stocks = stocks;
        this.facts = facts;
        this.statuses = statuses;
        this.archives = archives;
        this.submissionsArchive = submissionsArchive;
        this.converter = converter;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.clock = clock;
        this.issuerHistory = issuerHistory;
        this.stockValuationService = stockValuationService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        // CIK 去重讀 ZIP；在所需歷史範圍（2014 年起）與既有披露列做整份 CIK JSON 比對。
        List<ActiveStockWithStatus> candidates = stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE);
        if (candidates.isEmpty()) {
            log.info("SEC financial facts skipped | reason=NO_TARGETS");
            return;
        }
        int failed = 0;
        int empty = 0;
        int changedCount = 0;
        List<String> missingEntries = new ArrayList<>();
        Set<String> modifiedFigis = new LinkedHashSet<>();
        Map<String, List<ActiveStockWithStatus>> issuers = candidates.stream()
                .collect(Collectors.groupingBy(row -> row.stock().getCik(), LinkedHashMap::new, Collectors.toList()));
        Map<String, List<String>> predecessorDependents = new HashMap<>();
        for (Map.Entry<String, List<ActiveStockWithStatus>> entry : List.copyOf(issuers.entrySet())) {
            String currentCik = entry.getKey();
            List<String> dependentFigis = entry.getValue().stream().map(r -> r.stock().getFigi()).toList();
            for (String predecessor : issuerHistory.predecessors(currentCik)) {
                issuers.putIfAbsent(predecessor, List.of());
                predecessorDependents.computeIfAbsent(predecessor, ignored -> new ArrayList<>()).addAll(dependentFigis);
            }
        }

        try (SecCompanyFactsArchive.Snapshot archive = archives.prepare();
             SecSubmissionsArchive.Snapshot submissions = submissionsArchive.prepare()) {
            for (Map.Entry<String, List<ActiveStockWithStatus>> target : issuers.entrySet()) {
                String cik = target.getKey();
                List<ActiveStockWithStatus> issuerStocks = target.getValue();
                List<String> figis = issuerStocks.stream().map(row -> row.stock().getFigi()).toList();
                try {
                    Optional<CompanySubmissions> companySub = submissions == null ? Optional.empty() : submissions.findCompany(cik);
                    Map<String, FilingSubmission> filingMetadata = companySub.map(CompanySubmissions::filingsByAccession).orElse(Map.of());
                    String resolvedSic = companySub.map(CompanySubmissions::sic).orElse(null);

                    JsonNode company = archive.read(cik);
                    Instant observedAt = clock.now();
                    List<SecFinancialFact> downloaded = converter.convert(cik, company, observedAt, BACKFILL_START, filingMetadata);
                    SecCustomTagMappingService customTagService = customTagMappingServiceProvider == null
                            ? null : customTagMappingServiceProvider.getIfAvailable();
                    if (downloaded.isEmpty()) {
                        empty++;
                        boolean sicChanged = syncSicOnlyIfChanged(issuerStocks, resolvedSic);
                        int customChanges = runCustomTagSyncSafely(customTagService, cik, company);
                        if (sicChanged || customChanges > 0) {
                            modifiedFigis.addAll(figis);
                            modifiedFigis.addAll(predecessorDependents.getOrDefault(cik, List.of()));
                        }
                        continue;
                    }
                    SaveOutcome outcome = transactions.execute(transaction -> saveCompany(cik, issuerStocks, downloaded, resolvedSic));
                    if (outcome != null && outcome.factChanges() > 0) {
                        changedCount += outcome.factChanges();
                    }
                    if (outcome != null && outcome.valuationNeedsRebuild()) {
                        modifiedFigis.addAll(figis);
                        modifiedFigis.addAll(predecessorDependents.getOrDefault(cik, List.of()));
                    }
                    int customChanges = runCustomTagSyncSafely(customTagService, cik, company);
                    if (customChanges > 0) {
                        modifiedFigis.addAll(figis);
                        modifiedFigis.addAll(predecessorDependents.getOrDefault(cik, List.of()));
                    }
                } catch (SecCompanyFactsArchive.EntryMissingException ex) {
                    missingEntries.add(cik);
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("SEC financial facts CIK failed | cik={} figis={} reason={}:{}",
                            cik, figis, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
            if (!modifiedFigis.isEmpty() && stockValuationService != null) {
                stockValuationService.rebuildAndRefreshLatest(modifiedFigis);
            }
            if (!missingEntries.isEmpty()) {
                log.warn("SEC Company Facts ZIP missing entries | count={} ciks={}",
                        missingEntries.size(), missingEntries.stream().limit(30).toList());
            }
            log.info("SEC financial facts completed | ciks={} missingEntries={} noFacts={} changed={} rebuiltStocks={} failed={} archive={}",
                    issuers.size(), missingEntries.size(), empty, changedCount, modifiedFigis.size(), failed, archive.version());
        }
        if (failed > 0) throw new JobExecutionException("SEC_FINANCIAL_FACTS_PARTIAL_FAILURE:" + failed + "/" + issuers.size());
    }

    private record SaveOutcome(int factChanges, boolean valuationNeedsRebuild) {}

    private boolean syncSicOnlyIfChanged(List<ActiveStockWithStatus> issuerStocks, String resolvedSic) {
        if (resolvedSic == null || issuerStocks.isEmpty()) return false;
        List<String> needsSicUpdate = issuerStocks.stream()
                .filter(row -> !Objects.equals(resolvedSic, row.stock().getSic()))
                .map(row -> row.stock().getFigi()).toList();
        if (needsSicUpdate.isEmpty()) return false;
        transactions.executeWithoutResult(tx -> {
            List<Stock> managed = stocks.findAllById(needsSicUpdate);
            for (Stock stock : managed) stock.setSic(resolvedSic);
            stocks.saveAllAndFlush(managed);
        });
        return true;
    }

    private SaveOutcome saveCompany(String cik, List<ActiveStockWithStatus> issuerStocks, List<SecFinancialFact> downloaded, String resolvedSic) {
        List<String> figis = issuerStocks.stream().map(row -> row.stock().getFigi()).toList();
        Map<FactKey, SecFinancialFact> existing = new HashMap<>();
        for (SecFinancialFact fact : facts.findByCikAndPeriodEndGreaterThanEqual(cik, BACKFILL_START)) {
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

        boolean sicNeedsUpdate = resolvedSic != null && issuerStocks.stream()
                .anyMatch(row -> !Objects.equals(resolvedSic, row.stock().getSic()));
        boolean watermarkNeedsUpdate = latest != null && issuerStocks.stream()
                .anyMatch(row -> row.status().getSecFinancialFactsDate() == null || latest.isAfter(row.status().getSecFinancialFactsDate()));

        if (inserts.isEmpty() && updates.isEmpty() && !sicNeedsUpdate && !watermarkNeedsUpdate) {
            return new SaveOutcome(0, false);
        }

        List<StockStatus> locked = figis.isEmpty() ? List.of() : statuses.findAllByIdForUpdate(figis);
        if (locked.size() != figis.size()) throw new IllegalStateException("SEC_FINANCIAL_FACTS_STOCK_STATUS_MISSING:" + cik);

        LocalDateTime updatedAt = clock.nowLocalDateTime();
        for (SecFinancialFact updated : updates) {
            updated.setUpdatedAt(updatedAt);
        }
        for (SecFinancialFact inserted : inserts) {
            inserted.setUpdatedAt(updatedAt);
            entityManager.persist(inserted);
        }
        if (sicNeedsUpdate) {
            List<Stock> managedStocks = stocks.findAllById(figis);
            for (Stock stock : managedStocks) {
                stock.setSic(resolvedSic);
            }
        }
        if (latest != null) {
            for (StockStatus status : locked) {
                LocalDate currentWatermark = status.getSecFinancialFactsDate();
                if (currentWatermark == null || latest.isAfter(currentWatermark)) {
                    status.setSecFinancialFactsDate(latest);
                }
            }
        }
        entityManager.flush();
        int factChanges = inserts.size() + updates.size();
        return new SaveOutcome(factChanges, factChanges > 0 || sicNeedsUpdate);
    }

    private int runCustomTagSyncSafely(SecCustomTagMappingService customTagService, String cik, JsonNode company) {
        if (customTagService == null) {
            return 0;
        }
        try {
            return customTagService.syncConfirmedMappingsForCik(cik, company);
        } catch (RuntimeException ex) {
            log.warn("SEC confirmed custom tag sync failed (isolated from standard SEC facts) | cik={} reason={}",
                    cik, ex.getMessage());
            return 0;
        }
    }
}
