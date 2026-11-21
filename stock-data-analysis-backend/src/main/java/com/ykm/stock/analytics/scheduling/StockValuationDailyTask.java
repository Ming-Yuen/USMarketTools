package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.InAppNotification;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.InAppNotificationRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.DailyOpportunityRadarService;
import com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioClosingService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 每日收市估值任務：同步富途流通股數與原生估值快照、補建缺失財季分母，並刷新全市場最新收市估值快照（stock_valuation_latest）。
 */
@Component(TaskCode.STOCK_VALUATION_DAILY)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class StockValuationDailyTask implements Job {
    public static final String ALERT_EOD_PIPELINE_DEFERRED = "EOD_PIPELINE_DEFERRED";

    private static final int SHARE_SNAPSHOT_BATCH_SIZE = 400;
    /** 日常全市場發布下游板塊資金流與機會雷達前，目標營運股收齊當日行情的最低覆蓋百分比（容許極少數停牌／缺棒，擋住行情分批同步未完成時提前凍結）。 */
    private static final int MIN_MARKET_QUOTE_COVERAGE_PCT = 95;
    private static final int MIN_MARKET_SPLIT_COVERAGE_PCT = 95;
    private static final List<String> REQUIRED_BENCHMARK_SYMBOLS = List.of("SPY", "QQQ");
    private static final String SPLIT_MISSING_MARKER = "SPLIT_STORED_EVENT_NOT_RETURNED";

    private final StockRepository stocks;
    private final StockStatusRepository statuses;
    private final StockShareObservationRepository shareObservations;
    private final FutuService futu;
    private final StockValuationService stockValuationService;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    private final StockValuationLatestRepository latestValuations;
    private final SectorMoneyFlowService sectorMoneyFlowService;
    private final DailyOpportunityRadarService dailyOpportunityRadarService;
    private final SignalTrackingService signalTrackingService;
    private final PortfolioClosingService portfolioClosingService;
    @Autowired(required = false)
    private StockQuoteRepository quoteRepository;
    @Autowired(required = false)
    private DailyOpportunityRadarRunRepository radarRunRepository;
    @Autowired(required = false)
    private InAppNotificationRepository notificationRepository;
    @Autowired(required = false)
    private Scheduler scheduler;

    @Override
    public void execute(JobExecutionContext context) {
        Instant startedAt = dateTimeService.now();
        JobDataMap params = context == null ? new JobDataMap() : context.getMergedJobDataMap();
        LocalDate requestedDate = requestedValuationDate(params.getString("valuationDate"), startedAt);
        LocalDate targetDate = requestedDate != null ? requestedDate : calendar.getLastClosedTradingDay(startedAt);
        boolean forceRebuildPeriods = parseBooleanParam(params, "rebuildPeriods");
        String configuredFigis = params.getString("figis");
        boolean marketWideRun = configuredFigis == null || configuredFigis.isBlank();

        List<ActiveStockWithStatus> targets = findValuationTargets(requestedDate, configuredFigis);
        if (targets.isEmpty()) return;

        // 1. 日常執行時同步富途最新股數與估值快照（僅在股數變動或當日尚無收市快照時寫入）
        RuntimeException shareFailure = requestedDate == null ? refreshShareObservations(targets) : null;

        // 2. 為尚無財季分母列（或明確指定重建）的股票整股建立 stock_financial_period
        List<String> targetFigis = targets.stream().map(row -> row.stock().getFigi()).toList();
        List<String> periodCandidates = targets.stream()
                .filter(row -> row.status().getFinancialReportDate() != null || row.status().getSecFinancialFactsDate() != null)
                .map(row -> row.stock().getFigi()).toList();
        List<String> toRebuild = forceRebuildPeriods ? periodCandidates
                : stockValuationService.findFigisMissingFinancialPeriods(periodCandidates);
        if (!toRebuild.isEmpty()) {
            stockValuationService.rebuildFinancialPeriods(toRebuild);
        }

        // 3. 刷新最新估值快照（日常執行按各股最新已入庫收市行情日，指定日期則按指定日）
        stockValuationService.refreshLatestValuations(targetFigis, requestedDate);
        log.info("Daily valuation refresh completed | valuationDate={} targets={} rebuiltPeriods={}",
                targetDate, targetFigis.size(), toRebuild.size());

        // 4. 僅於日常全市場執行（非指定歷史日、非局部 figis）、目標普通營運股當日行情／估值／拆股與大盤基準（SPY/QQQ）皆達標時：
        //    - 訊號結算獨立執行（不因當日雷達延後而漏結算）
        //    - 估值與拆股就緒時串接板塊資金流、每日機會雷達（已凍結同日雷達則跳過避免覆寫）、收盤簡報與非同步籌碼同步
        //    - 上游未齊延後時寫入冪等站內警告 EOD_PIPELINE_DEFERRED，後續補跑成功自動標為已讀
        if (requestedDate == null && marketWideRun
                && sectorMoneyFlowService != null && dailyOpportunityRadarService != null && signalTrackingService != null) {
            List<ActiveStockWithStatus> coverageBase = targets.stream()
                    .filter(row -> row.stock() != null && SecurityTypePolicy.isOperatingCompanyType(row.stock().getStockType()))
                    .toList();
            if (coverageBase.isEmpty()) {
                coverageBase = targets;
            }
            List<ActiveStockWithStatus> splitCandidates = targets.stream()
                    .filter(row -> row.stock() != null && SecurityTypePolicy.supportsValuation(row.stock().getStockType()))
                    .toList();
            if (splitCandidates.isEmpty()) {
                splitCandidates = targets;
            }
            List<String> coverageFigis = coverageBase.stream().map(row -> row.stock().getFigi()).toList();
            List<String> splitCandidateFigis = splitCandidates.stream().map(row -> row.stock().getFigi()).toList();
            Set<String> splitQuotedFigis = quoteRepository != null
                    ? new HashSet<>(quoteRepository.findQuotedFigisByDate(targetDate, splitCandidateFigis))
                    : splitCandidates.stream()
                            .filter(row -> row.status() != null && targetDate.equals(row.status().getQuoteDate()))
                            .map(row -> row.stock().getFigi())
                            .collect(Collectors.toSet());
            long readyQuotes = coverageFigis.stream().filter(splitQuotedFigis::contains).count();
            boolean quotesBatchReady = readyQuotes * 100L >= (long) coverageBase.size() * MIN_MARKET_QUOTE_COVERAGE_PCT;
            if (quotesBatchReady) {
                int settled = signalTrackingService.settlePendingSignals(targetDate);
                log.info("Signal tracking settlement completed | tradeDate={} updatedSettlements={}", targetDate, settled);
            }
            LocalDate snapshotDate = resolveEffectiveSnapshotDate(targetDate);
            boolean valuationsReady = targetDate.equals(snapshotDate)
                    && (quoteRepository == null || latestValuations == null
                    || latestValuations.countByValuationDateAndFigiIn(targetDate, coverageFigis) * 100L
                            >= (long) coverageBase.size() * MIN_MARKET_QUOTE_COVERAGE_PCT);

            // 發布前重讀最新 StockStatus，避免股數與估值刷新期間基本面 Job 已補齊拆股卻仍用入口過期快照誤判
            Map<String, StockStatus> freshStatusMap = splitQuotedFigis.isEmpty() ? Map.of()
                    : statuses.findAllById(splitQuotedFigis).stream()
                            .collect(Collectors.toMap(StockStatus::getFigi, s -> s, (a, b) -> a));
            List<StockStatus> splitBase = splitCandidates.stream()
                    .filter(row -> splitQuotedFigis.contains(row.stock().getFigi()))
                    .map(row -> freshStatusMap.getOrDefault(row.stock().getFigi(), row.status()))
                    .filter(st -> !hasSplitDiscrepancyMessage(st))
                    .toList();
            long readySplits = splitBase.stream()
                    .filter(st -> hasValidSplitCoverageThrough(st, targetDate))
                    .count();
            boolean operatingSplitsReady = !splitBase.isEmpty()
                    && readySplits * 100L >= (long) splitBase.size() * MIN_MARKET_SPLIT_COVERAGE_PCT;
            boolean benchmarksSplitReady = areBenchmarkSplitsReady(targetDate);
            boolean splitsReady = operatingSplitsReady && benchmarksSplitReady;

            if (quotesBatchReady && valuationsReady && splitsReady) {
                boolean radarAlreadyFrozen = radarRunRepository != null && radarRunRepository.existsById(snapshotDate);
                if (!radarAlreadyFrozen) {
                    sectorMoneyFlowService.refreshDaily(snapshotDate, startedAt);
                    dailyOpportunityRadarService.refreshDaily(snapshotDate, startedAt);
                } else {
                    log.info("Daily radar already frozen; skipping sector flow & radar refresh | tradeDate={}", snapshotDate);
                }
                boolean radarPublished = radarRunRepository == null || radarRunRepository.existsById(snapshotDate);
                if (radarPublished) {
                    // 4b. 獨立容錯隔離執行持倉自動體檢與站內收盤簡報／提醒寫入：異常僅記警告日誌，絕不回滾上游估值、板塊資金流、雷達與訊號結算
                    boolean closingPublished = true;
                    if (portfolioClosingService != null) {
                        try {
                            Optional<com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto> briefing =
                                    portfolioClosingService.publishDailyClosing(snapshotDate, startedAt);
                            closingPublished = briefing != null && briefing.isPresent();
                        } catch (RuntimeException ex) {
                            closingPublished = false;
                            log.warn("Portfolio closing health check & briefing failed (isolated; upstream committed) | tradeDate={}",
                                    snapshotDate, ex);
                        }
                    }
                    // 4c. 僅於收盤簡報亦完成發布時才將既有延後警告自動標為已讀，避免簡報失敗卻靜默消掉告警；雷達發布後正常異步觸發籌碼同步
                    if (closingPublished) {
                        markDeferredNotificationReadSafely(targetDate);
                    }
                    triggerChipEventSyncSafely(snapshotDate);
                } else {
                    log.warn("Batch 03 downstream deferred: radar not published after refresh | targetDate={}", targetDate);
                    emitDeferredNotificationSafely(targetDate, "RADAR_DEFERRED_MARKET_CONTEXT_NOT_READY",
                            readyQuotes, coverageBase.size(), readySplits, splitBase.size(), benchmarksSplitReady);
                }
            } else {
                String deferReason = !quotesBatchReady ? "QUOTES_NOT_READY"
                        : (!valuationsReady ? "VALUATIONS_NOT_READY"
                        : (!benchmarksSplitReady ? "BENCHMARK_SPLITS_NOT_READY" : "SPLITS_NOT_READY"));
                log.warn("Batch 03 downstream deferred: targetDate={} reason={} readyQuotes={}/{} snapshotDate={} valuationsReady={} readySplits={}/{} benchmarksSplitReady={}",
                        targetDate, deferReason, readyQuotes, coverageBase.size(), snapshotDate,
                        valuationsReady, readySplits, splitBase.size(), benchmarksSplitReady);
                emitDeferredNotificationSafely(targetDate, deferReason,
                        readyQuotes, coverageBase.size(), readySplits, splitBase.size(), benchmarksSplitReady);
            }
        }

        // 5. 保存可算估值後回報股數快照連線異常
        if (shareFailure != null) {
            throw new IllegalStateException("SHARE_OBSERVATION_SYNC_FAILED", shareFailure);
        }
    }

    private LocalDate resolveEffectiveSnapshotDate(LocalDate targetDate) {
        if (latestValuations == null) {
            return targetDate;
        }
        return latestValuations.findMaxValuationDateAtOrBefore(targetDate).orElse(null);
    }

    private LocalDate requestedValuationDate(String configuredDate, Instant startedAt) {
        if (configuredDate == null || configuredDate.isBlank()) return null;
        LocalDate day = LocalDate.parse(configuredDate.trim());
        if (!calendar.isTradingDay(day) || calendar.getSessionCloseAt(day).isAfter(startedAt)) {
            throw new IllegalArgumentException("VALUATION_DATE_NOT_CLOSED_SESSION");
        }
        return day;
    }

    private List<ActiveStockWithStatus> findValuationTargets(LocalDate requested, String configuredFigis) {
        Set<String> selected = configuredFigis == null || configuredFigis.isBlank() ? Set.of()
                : Arrays.stream(configuredFigis.split(",")).map(String::strip)
                        .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        List<ActiveStockWithStatus> targets = requested == null
                ? stocks.findValuationStocks(ActiveStatus.ACTIVE)
                : stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, requested);
        if (selected.isEmpty()) return targets;
        List<ActiveStockWithStatus> matching = targets.stream()
                .filter(row -> selected.contains(row.stock().getFigi())).toList();
        if (matching.isEmpty()) throw new IllegalArgumentException("VALUATION_SCOPE_NO_MATCH");
        return matching;
    }

    RuntimeException refreshShareObservations(List<ActiveStockWithStatus> targets) {
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            Set<String> supported = session.supportedUsStocks();
            Map<String, Long> symbolCounts = targets.stream()
                    .filter(row -> row.stock().getSymbol() != null)
                    .collect(Collectors.groupingBy(row -> row.stock().getSymbol(), Collectors.counting()));
            List<ActiveStockWithStatus> supportedTargets = targets.stream()
                    .filter(row -> supported.contains(row.stock().getSymbol()) && symbolCounts.get(row.stock().getSymbol()) == 1L)
                    .toList();
            int saved = 0;
            for (int start = 0; start < supportedTargets.size(); start += SHARE_SNAPSHOT_BATCH_SIZE) {
                List<ActiveStockWithStatus> batch = supportedTargets.subList(
                        start, Math.min(start + SHARE_SNAPSHOT_BATCH_SIZE, supportedTargets.size()));
                Map<String, FutuService.ShareSnapshot> downloaded = session.shareCounts(
                        batch.stream().map(row -> row.stock().getSymbol()).toList());
                Instant observedAt = dateTimeService.now();
                LocalDate lastClosed = calendar.getLastClosedTradingDay(observedAt);
                List<String> batchFigis = batch.stream().map(row -> row.stock().getFigi()).toList();
                Map<String, StockShareObservation> latestByFigi = shareObservations.findLatestAt(batchFigis, observedAt).stream()
                        .collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));
                Map<String, StockShareObservation> closingByFigi = shareObservations.findLatestClosingSnapshotAt(
                                batchFigis, calendar.getSessionOpenAt(lastClosed), calendar.getSessionCloseAt(lastClosed), observedAt)
                        .stream().collect(Collectors.toMap(StockShareObservation::getFigi, row -> row));

                List<StockShareObservation> rows = new ArrayList<>();
                List<String> sharesChangedFigis = new ArrayList<>();
                for (ActiveStockWithStatus target : batch) {
                    String figi = target.stock().getFigi();
                    FutuService.ShareSnapshot snapshot = downloaded.get(target.stock().getSymbol());
                    if (snapshot == null) {
                        log.warn("Share snapshot unavailable | figi={} symbol={}", figi, target.stock().getSymbol());
                        continue;
                    }
                    StockShareObservation previous = latestByFigi.get(figi);
                    StockShareObservation existingClosing = closingByFigi.get(figi);
                    boolean sharesChanged = previous == null
                            || previous.getIssuedShares() != snapshot.issuedShares()
                            || (snapshot.outstandingShares() != null
                                    && !Objects.equals(previous.getOutstandingShares(), snapshot.outstandingShares()));
                    boolean missingNativeMultiples = hasMissingNativeMultiple(previous, snapshot)
                            || hasMissingNativeMultiple(existingClosing, snapshot);
                    if (!sharesChanged && !missingNativeMultiples && existingClosing != null) continue;

                    StockShareObservation observation = new StockShareObservation();
                    observation.setFigi(figi);
                    observation.setObservedAt(observedAt);
                    observation.setIssuedShares(snapshot.issuedShares());
                    observation.setOutstandingShares(snapshot.outstandingShares());
                    observation.setSnapshotPrice(snapshot.price());
                    observation.setSnapshotMarketCap(snapshot.marketCap());
                    observation.setOutstandingMarketCap(snapshot.outstandingMarketCap());
                    observation.setPeTtmRate(snapshot.peTtmRate());
                    observation.setPbRate(snapshot.pbRate());
                    observation.setSnapshotQuoteTime(snapshot.quoteTime());
                    observation.setSource("FUTU");
                    rows.add(observation);
                    if (sharesChanged && previous != null) {
                        sharesChangedFigis.add(figi);
                    }
                }
                if (!rows.isEmpty()) {
                    transactions.executeWithoutResult(transaction -> {
                        List<String> sortedFigis = rows.stream().map(StockShareObservation::getFigi).distinct().sorted().toList();
                        if (statuses.findAllByIdForUpdate(sortedFigis).size() != sortedFigis.size()) {
                            throw new IllegalStateException("SHARE_OBSERVATION_STOCK_STATUS_MISSING");
                        }
                        for (StockShareObservation row : rows) {
                            entityManager.persist(row);
                        }
                        entityManager.flush();
                        if (!sharesChangedFigis.isEmpty() && stockValuationService != null) {
                            stockValuationService.rebuildFinancialPeriodsInCurrentTx(sharesChangedFigis);
                        }
                    });
                    saved += rows.size();
                }
            }
            log.info("Share observations collected | targets={} supportedUnique={} saved={}",
                    targets.size(), supportedTargets.size(), saved);
            return null;
        } catch (RuntimeException failure) {
            log.error("Share observation collection failed; valuation uses only already stored eligible observations", failure);
            return failure;
        }
    }

    private boolean hasMissingNativeMultiple(StockShareObservation stored, FutuService.ShareSnapshot incoming) {
        if (stored == null) return false;
        return (stored.getPeTtmRate() == null && incoming.peTtmRate() != null)
                || (stored.getPbRate() == null && incoming.pbRate() != null);
    }

    private boolean parseBooleanParam(JobDataMap params, String key) {
        if (params == null || !params.containsKey(key)) {
            return false;
        }
        Object raw = params.get(key);
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    private boolean areBenchmarkSplitsReady(LocalDate targetDate) {
        List<Stock> benchmarks = stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, REQUIRED_BENCHMARK_SYMBOLS);
        if (benchmarks == null || benchmarks.isEmpty()) {
            return false;
        }
        Set<String> presentSymbols = benchmarks.stream()
                .filter(s -> s != null && s.getSymbol() != null)
                .map(s -> s.getSymbol().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String required : REQUIRED_BENCHMARK_SYMBOLS) {
            if (!presentSymbols.contains(required)) {
                return false;
            }
        }
        List<String> figis = benchmarks.stream().map(Stock::getFigi).distinct().toList();
        Map<String, StockStatus> statusMap = statuses.findAllById(figis).stream()
                .collect(Collectors.toMap(StockStatus::getFigi, s -> s, (a, b) -> a));
        for (Stock benchmark : benchmarks) {
            if (!hasValidSplitCoverageThrough(statusMap.get(benchmark.getFigi()), targetDate)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasValidSplitCoverageThrough(StockStatus status, LocalDate targetDate) {
        if (status == null || status.getSplitFromDate() == null || status.getSplitDate() == null) {
            return false;
        }
        if (status.getSplitDate().isBefore(targetDate)) {
            return false;
        }
        String msg = status.getSplitMessage();
        return msg == null || msg.isBlank() || msg.toUpperCase(Locale.ROOT).startsWith("WARNING:");
    }

    private boolean hasSplitDiscrepancyMessage(StockStatus status) {
        return status != null && status.getSplitMessage() != null && status.getSplitMessage().contains(SPLIT_MISSING_MARKER);
    }

    private String deferredDedupKey(LocalDate targetDate) {
        return "EOD_DEFERRED:" + targetDate;
    }

    private void emitDeferredNotificationSafely(LocalDate targetDate,
                                                String reason,
                                                long readyQuotes,
                                                int totalTargets,
                                                long readySplits,
                                                int splitTargets,
                                                boolean benchmarksSplitReady) {
        if (notificationRepository == null || targetDate == null) {
            return;
        }
        String dedupKey = deferredDedupKey(targetDate);
        try {
            TransactionTemplate requiresNewTx = new TransactionTemplate(transactions.getTransactionManager());
            requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNewTx.executeWithoutResult(tx -> {
                if (notificationRepository.existsByDedupKey(dedupKey)) {
                    return;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tradeDate", targetDate.toString());
                payload.put("reason", reason);
                payload.put("readyQuotes", readyQuotes);
                payload.put("totalTargets", totalTargets);
                payload.put("readySplits", readySplits);
                payload.put("splitTargets", splitTargets);
                payload.put("benchmarksSplitReady", benchmarksSplitReady);

                InAppNotification notif = new InAppNotification();
                notif.setDedupKey(dedupKey);
                notif.setTradeDate(targetDate);
                notif.setSeverity(InAppNotification.SEVERITY_WARNING);
                notif.setCategory(InAppNotification.CATEGORY_CLOSING_BRIEFING);
                notif.setAlertCode(ALERT_EOD_PIPELINE_DEFERRED);
                notif.setPayload(payload);
                notif.setRead(false);
                notif.setReadAt(null);
                notif.setCreatedAt(dateTimeService.now());
                notificationRepository.save(notif);
            });
        } catch (DataIntegrityViolationException ex) {
            log.debug("Deferred EOD notification already persisted concurrently | tradeDate={}", targetDate);
        } catch (RuntimeException ex) {
            log.warn("Failed to emit EOD_PIPELINE_DEFERRED notification (isolated) | tradeDate={}", targetDate, ex);
        }
    }

    private void markDeferredNotificationReadSafely(LocalDate targetDate) {
        if (notificationRepository == null || targetDate == null) {
            return;
        }
        try {
            TransactionTemplate requiresNewTx = new TransactionTemplate(transactions.getTransactionManager());
            requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNewTx.executeWithoutResult(tx ->
                    notificationRepository.markReadByAlertCodeAndTradeDateLessThanEqualIfUnread(
                            ALERT_EOD_PIPELINE_DEFERRED, targetDate, dateTimeService.now()));
        } catch (RuntimeException ex) {
            log.warn("Failed to auto-mark EOD_PIPELINE_DEFERRED read (isolated) | tradeDate={}", targetDate, ex);
        }
    }

    private void triggerChipEventSyncSafely(LocalDate tradeDate) {
        if (scheduler == null) {
            return;
        }
        JobKey jobKey = JobKey.jobKey(TaskCode.CHIP_EVENT_DAILY_SYNC, TaskGroup.SCHEDULE.name());
        try {
            if (!scheduler.isStarted() || !scheduler.checkExists(jobKey)) {
                log.info("Skip post-radar chip event trigger: scheduler not started or job absent | tradeDate={}", tradeDate);
                return;
            }
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
            Date nowDate = Date.from(dateTimeService.now());
            boolean hasEffectiveTrigger = triggers != null && triggers.stream().anyMatch(tr ->
                    tr != null
                            && (tr.getStartTime() == null || !tr.getStartTime().after(nowDate))
                            && (tr.getEndTime() == null || tr.getEndTime().after(nowDate)));
            if (!hasEffectiveTrigger) {
                log.info("Skip post-radar chip event trigger: schedule disabled, not yet started, or expired | tradeDate={}", tradeDate);
                return;
            }
            JobDataMap triggerData = new JobDataMap();
            if (tradeDate != null) {
                triggerData.put("tradeDate", tradeDate.toString());
            }
            scheduler.triggerJob(jobKey, triggerData);
            log.info("Triggered post-radar chip event sync asynchronously | tradeDate={}", tradeDate);
        } catch (SchedulerException | RuntimeException ex) {
            log.warn("Post-radar chip event trigger failed (fallback cron will catch up) | tradeDate={}", tradeDate, ex);
        }
    }
}
