package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.DailyOpportunityRadarService;
import com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int SHARE_SNAPSHOT_BATCH_SIZE = 400;
    /** 日常全市場發布下游板塊資金流與機會雷達前，目標營運股收齊當日行情的最低覆蓋百分比（容許極少數停牌／缺棒，擋住行情分批同步未完成時提前凍結）。 */
    private static final int MIN_MARKET_QUOTE_COVERAGE_PCT = 95;

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

    @Override
    public void execute(JobExecutionContext context) {
        Instant startedAt = dateTimeService.now();
        JobDataMap params = context == null ? new JobDataMap() : context.getMergedJobDataMap();
        LocalDate requestedDate = requestedValuationDate(params.getString("valuationDate"), startedAt);
        LocalDate targetDate = requestedDate != null ? requestedDate : calendar.getLastClosedTradingDay(startedAt);
        boolean forceRebuildPeriods = Boolean.parseBoolean(params.getString("rebuildPeriods"));
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

        // 4. 僅於日常全市場執行（非指定歷史日、非局部 figis）、目標普通營運股當日行情批次已達覆蓋門檻（>= 95%）且估值就緒時，
        //    串接板塊量價資金流、每日機會雷達（同交易原子寫入當日新訊號）與歷史訊號到期結算（不讓未核實 ADR 拖累收盤流程）
        if (requestedDate == null && marketWideRun
                && sectorMoneyFlowService != null && dailyOpportunityRadarService != null && signalTrackingService != null) {
            List<ActiveStockWithStatus> coverageBase = targets.stream()
                    .filter(row -> row.stock() != null && com.ykm.stock.analytics.service.analysis.SecurityTypePolicy.isOperatingCompanyType(row.stock().getStockType()))
                    .toList();
            if (coverageBase.isEmpty()) {
                coverageBase = targets;
            }
            long readyQuotes = coverageBase.stream()
                    .filter(row -> row.status() != null && targetDate.equals(row.status().getQuoteDate()))
                    .count();
            boolean quotesBatchReady = readyQuotes * 100L >= (long) coverageBase.size() * MIN_MARKET_QUOTE_COVERAGE_PCT;
            LocalDate snapshotDate = resolveEffectiveSnapshotDate(targetDate);
            if (quotesBatchReady && targetDate.equals(snapshotDate)) {
                sectorMoneyFlowService.refreshDaily(snapshotDate, startedAt);
                dailyOpportunityRadarService.refreshDaily(snapshotDate, startedAt);
                int settled = signalTrackingService.settlePendingSignals(snapshotDate);
                log.info("Signal tracking settlement completed | tradeDate={} updatedSettlements={}", snapshotDate, settled);
                // 4b. 獨立容錯隔離執行持倉自動體檢與站內收盤簡報／提醒寫入：異常僅記警告日誌，絕不回滾上游估值、板塊資金流、雷達與訊號結算
                if (portfolioClosingService != null) {
                    try {
                        portfolioClosingService.publishDailyClosing(snapshotDate, startedAt);
                    } catch (RuntimeException ex) {
                        log.warn("Portfolio closing health check & briefing failed (isolated; upstream committed) | tradeDate={}",
                                snapshotDate, ex);
                    }
                }
            } else {
                log.warn("Batch 03 downstream deferred: targetDate={} readyQuotes={}/{} snapshotDate={}",
                        targetDate, readyQuotes, coverageBase.size(), snapshotDate);
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
                }
                if (!rows.isEmpty()) {
                    transactions.executeWithoutResult(transaction -> {
                        List<String> figis = rows.stream().map(StockShareObservation::getFigi).toList();
                        if (statuses.findAllByIdForUpdate(figis).size() != rows.size()) {
                            throw new IllegalStateException("SHARE_OBSERVATION_STOCK_STATUS_MISSING");
                        }
                        for (StockShareObservation row : rows) {
                            entityManager.persist(row);
                        }
                        entityManager.flush();
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
}
