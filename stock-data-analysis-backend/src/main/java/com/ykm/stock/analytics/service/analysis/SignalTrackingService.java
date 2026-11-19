package com.ykm.stock.analytics.service.analysis;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.SignalTrackingLog;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.SignalTrackingLogRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.calculator.QuoteNormalizer;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 歷史雷達訊號事後追蹤與跨拆股到期結算服務：
 * 1. 記錄當日觸發 ACCUMULATE_AT_SUPPORT 與 TREND_PULLBACK_STARTER 之訊號（同標的同訊號 20 個美股交易日內去重，歷史入場價位永不竄改）。
 * 2. 後續滿 5 / 20 / 60 個美股交易日時，依 StockSplitCalculator.priceFactor(splits, quoteDate, signalDate) 將後續行情換算回訊號日基準，
 *    條件式回填 +5D/+20D/+60D 報酬、MFE/MAE 與目標／止損首次觸發狀態（已結算欄位永不覆寫）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignalTrackingService {
    private static final MathContext MC = MathContext.DECIMAL128;
    public static final int DEDUP_TRADING_DAYS = 20;
    private static final int SETTLE_BATCH_SIZE = 100;
    private static final int SETTLE_LOOKBACK_CALENDAR_DAYS = 550;

    private final SignalTrackingLogRepository logRepository;
    private final StockQuoteRepository quotes;
    private final StockSplitRepository splits;
    private final StockStatusRepository statuses;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    public record GroupStats(
            String groupKey,
            String groupLabel,
            int totalSignals,
            int settled5dCount,
            BigDecimal avgReturn5d,
            int settled20dCount,
            BigDecimal avgReturn20d,
            BigDecimal winRate20d,
            int settled60dCount,
            BigDecimal avgReturn60d,
            int resolvedOutcomeCount,
            int targetHitFirstCount,
            int stopHitFirstCount,
            BigDecimal targetHitRate,
            BigDecimal avgMfe60d,
            BigDecimal avgMae60d
    ) {}

    public record SignalTrackingStatsSummary(
            int totalSignals,
            int pendingCount,
            int settled20dCount,
            BigDecimal overallWinRate20d,
            BigDecimal overallAvgReturn20d,
            BigDecimal overallTargetHitRate,
            List<GroupStats> bySignalType,
            List<GroupStats> byMarketRegime,
            List<GroupStats> byIndustry,
            List<GroupStats> byMarketCapTier
    ) {}

    /** 依當日雷達結果記錄 ACCUMULATE_AT_SUPPORT 與 TREND_PULLBACK_STARTER 訊號（獨立交易入口）；同 (figi, signalType) 於 20 個交易日內雙向去重。 */
    public int recordSignals(LocalDate signalDate, List<DailyOpportunityRadar> radarRows) {
        if (signalDate == null || radarRows == null || radarRows.isEmpty()) {
            return 0;
        }
        Instant now = dateTimeService.now();
        Integer savedCount = transactions.execute(tx -> recordSignalsInCurrentTx(signalDate, radarRows, now));
        return savedCount == null ? 0 : savedCount;
    }

    /**
     * 於呼叫端既有交易內原子寫入當日合格訊號（供 DailyOpportunityRadarService 發布雷達明細與 run 同交易提交）。
     * 透過 stock_status 列鎖序列化同 FIGI 寫入，並以雙向 20 交易日冷卻視窗防重。
     */
    public int recordSignalsInCurrentTx(LocalDate signalDate, List<DailyOpportunityRadar> radarRows, Instant now) {
        if (signalDate == null || radarRows == null || radarRows.isEmpty()) {
            return 0;
        }
        List<DailyOpportunityRadar> eligible = radarRows.stream()
                .filter(row -> row != null && !row.isHeadwindWatchOnly()
                        && row.getClosePrice() != null && row.getClosePrice().signum() > 0
                        && (SignalTrackingLog.SIGNAL_ACCUMULATE_AT_SUPPORT.equals(row.getStance())
                        || SignalTrackingLog.SIGNAL_TREND_PULLBACK_STARTER.equals(row.getStance())))
                .sorted(Comparator.comparing(DailyOpportunityRadar::getFigi)
                        .thenComparing(DailyOpportunityRadar::getStance))
                .toList();
        if (eligible.isEmpty()) {
            return 0;
        }

        // 精確計算雙向 20 個交易日冷卻視窗（涵蓋前 19 與後 19 個交易日，防同日並發或倒序補寫突破去重）
        List<LocalDate> priorSessions = calendar.getTradingDays(signalDate.minusDays(50), signalDate);
        LocalDate dedupWindowStart = priorSessions.size() >= DEDUP_TRADING_DAYS
                ? priorSessions.get(priorSessions.size() - DEDUP_TRADING_DAYS)
                : signalDate.minusDays(35);
        List<LocalDate> futureSessions = calendar.getTradingDays(signalDate, signalDate.plusDays(50));
        LocalDate dedupWindowEnd = futureSessions.size() >= DEDUP_TRADING_DAYS
                ? futureSessions.get(DEDUP_TRADING_DAYS - 1)
                : signalDate.plusDays(35);

        Instant ts = now != null ? now : dateTimeService.now();
        List<String> figis = eligible.stream().map(DailyOpportunityRadar::getFigi).distinct().toList();
        // 鎖定對應 stock_status 序列化同 FIGI 並發寫入，避免跨執行緒突破 20 交易日冷卻視窗
        statuses.findAllByIdForUpdate(figis);
        List<SignalTrackingLog> nearby = logRepository.findSignalsInDateRange(figis, dedupWindowStart, dedupWindowEnd);
        Set<String> blockedKeys = nearby.stream()
                .filter(existing -> existing.getSignalDate() != null)
                .filter(existing -> isWithinCooldown(existing.getSignalDate(), signalDate))
                .map(existing -> existing.getFigi() + "|" + existing.getSignalType())
                .collect(Collectors.toSet());

        int inserted = 0;
        for (DailyOpportunityRadar row : eligible) {
            String signalType = row.getStance();
            String key = row.getFigi() + "|" + signalType;
            if (blockedKeys.contains(key)) {
                continue;
            }
            SignalTrackingLog entry = new SignalTrackingLog();
            entry.setFigi(row.getFigi());
            entry.setSymbol(row.getSymbol());
            entry.setSignalDate(signalDate);
            entry.setSignalType(signalType);
            entry.setSignalClose(row.getClosePrice());
            entry.setProtectiveStop(row.getProtectiveStop());
            entry.setFirstTargetPrice(row.getFirstTargetPrice());
            entry.setPlanStatus(row.getPlanStatus());
            entry.setIndustryCode(row.getIndustryCode());
            entry.setIndustryName(row.getIndustryName());
            entry.setMarketCapTier(row.getMarketCapTier());
            entry.setMarketRegime(row.getMarketRegime());
            entry.setOutcomeStatus(SignalTrackingLog.OUTCOME_PENDING);
            entry.setCreatedAt(ts);
            entry.setUpdatedAt(ts);
            entityManager.persist(entry);
            blockedKeys.add(key);
            inserted++;
        }
        if (inserted > 0) {
            entityManager.flush();
        }
        return inserted;
    }

    /** 對所有未完成 +5D/+20D/+60D、完整 60D 路徑或未終結之歷史訊號執行拆股對齊結算（直接從 log 取 FIGI，不排除停追／下市）。 */
    public int settlePendingSignals(LocalDate asOfDate) {
        if (asOfDate == null) {
            return 0;
        }
        LocalDate minLookbackDate = asOfDate.minusDays(SETTLE_LOOKBACK_CALENDAR_DAYS);
        List<SignalTrackingLog> pending = logRepository.findPendingSettlement(asOfDate, minLookbackDate);
        if (pending.isEmpty()) {
            return 0;
        }

        Instant now = dateTimeService.now();
        TransactionTemplate repeatableReadTx = new TransactionTemplate(transactions.getTransactionManager());
        repeatableReadTx.setReadOnly(true);
        repeatableReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        int updatedTotal = 0;
        for (List<SignalTrackingLog> batch : Lists.partition(pending, SETTLE_BATCH_SIZE)) {
            List<String> batchFigis = batch.stream().map(SignalTrackingLog::getFigi).distinct().toList();
            LocalDate minSignalDate = batch.stream().map(SignalTrackingLog::getSignalDate).min(LocalDate::compareTo).orElse(asOfDate);
            LocalDate maxSignalDate = batch.stream().map(SignalTrackingLog::getSignalDate).max(LocalDate::compareTo).orElse(asOfDate);
            List<LocalDate> maxFuture = calendar.getTradingDays(maxSignalDate.plusDays(1), maxSignalDate.plusDays(130));
            LocalDate maxDue60 = maxFuture.size() >= 60 ? maxFuture.get(59) : asOfDate;
            LocalDate effectiveMaxDate = maxDue60.isBefore(asOfDate) ? maxDue60 : asOfDate;

            // 在單一 PostgreSQL REPEATABLE READ 唯讀快照內同時讀取行情、拆股事件與覆蓋狀態，防止跨 Job 讀到舊事件配新 splitDate
            BatchSettlementInputs inputs = repeatableReadTx.execute(tx -> {
                Map<String, List<QuoteBarRow>> qMap = new HashMap<>();
                for (QuoteBarRow bar : quotes.findQuoteBarsOrdered(batchFigis, minSignalDate, effectiveMaxDate)) {
                    qMap.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
                }
                Map<String, List<StockSplit>> spMap = new HashMap<>();
                for (StockSplit sp : splits.findByFigiInOrderByExecutionDateAsc(batchFigis)) {
                    spMap.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
                }
                Map<String, StockStatus> stMap = statuses.findAllById(batchFigis).stream()
                        .collect(Collectors.toMap(StockStatus::getFigi, row -> row, (a, b) -> a));
                return new BatchSettlementInputs(qMap, spMap, stMap);
            });
            Map<String, List<QuoteBarRow>> quotesByFigi = inputs == null ? Map.of() : inputs.quotesByFigi();
            Map<String, List<StockSplit>> splitsByFigi = inputs == null ? Map.of() : inputs.splitsByFigi();
            Map<String, StockStatus> statusByFigi = inputs == null ? Map.of() : inputs.statusByFigi();

            List<SignalTrackingLog> dirty = new ArrayList<>();
            for (SignalTrackingLog record : batch) {
                boolean changed = settleSingleSignal(
                        record,
                        asOfDate,
                        now,
                        quotesByFigi.getOrDefault(record.getFigi(), List.of()),
                        splitsByFigi.getOrDefault(record.getFigi(), List.of()),
                        statusByFigi.get(record.getFigi()));
                if (changed) {
                    dirty.add(record);
                }
            }
            if (!dirty.isEmpty()) {
                Integer batchUpdated = transactions.execute(tx -> {
                    int applied = 0;
                    for (SignalTrackingLog computed : dirty) {
                        if (computed.getId() != null) {
                            SignalTrackingLog managed = logRepository.findById(computed.getId()).orElse(null);
                            if (managed != null && applyConditionalUpdates(managed, computed, now)) {
                                applied++;
                            }
                        } else {
                            entityManager.merge(computed);
                            applied++;
                        }
                    }
                    if (applied > 0) {
                        entityManager.flush();
                    }
                    return applied;
                });
                if (batchUpdated != null) {
                    updatedTotal += batchUpdated;
                }
            }
        }
        return updatedTotal;
    }

    private boolean applyConditionalUpdates(SignalTrackingLog target, SignalTrackingLog computed, Instant now) {
        boolean modified = false;
        if (target.getSettled5dDate() == null && computed.getSettled5dDate() != null) {
            target.setReturn5d(computed.getReturn5d());
            target.setSettled5dDate(computed.getSettled5dDate());
            modified = true;
        }
        if (target.getSettled20dDate() == null && computed.getSettled20dDate() != null) {
            target.setReturn20d(computed.getReturn20d());
            target.setSettled20dDate(computed.getSettled20dDate());
            modified = true;
        }
        if (target.getSettled60dDate() == null && computed.getSettled60dDate() != null) {
            target.setReturn60d(computed.getReturn60d());
            target.setSettled60dDate(computed.getSettled60dDate());
            modified = true;
        }
        if (target.getSettledPathDate() == null && computed.getSettledPathDate() != null) {
            target.setMfe60d(computed.getMfe60d());
            target.setMae60d(computed.getMae60d());
            target.setSettledPathDate(computed.getSettledPathDate());
            modified = true;
        }
        boolean targetOutcomeMutable = SignalTrackingLog.OUTCOME_PENDING.equals(target.getOutcomeStatus())
                || SignalTrackingLog.OUTCOME_DATA_GAP.equals(target.getOutcomeStatus());
        if (targetOutcomeMutable
                && computed.getOutcomeStatus() != null
                && !Objects.equals(target.getOutcomeStatus(), computed.getOutcomeStatus())) {
            target.setOutcomeStatus(computed.getOutcomeStatus());
            target.setOutcomeDate(computed.getOutcomeDate());
            modified = true;
        }
        if (modified) {
            target.setUpdatedAt(now);
        }
        return modified;
    }

    /** 單筆訊號跨拆股結算純計算邏輯（支援直接單元測試，嚴格保證已結算欄位絕不覆寫）。 */
    boolean settleSingleSignal(
            SignalTrackingLog record,
            LocalDate asOfDate,
            Instant now,
            List<QuoteBarRow> stockQuotes,
            List<StockSplit> stockSplits,
            StockStatus status) {
        LocalDate signalDate = record.getSignalDate();
        BigDecimal signalClose = record.getSignalClose();
        if (signalDate == null || signalClose == null || signalClose.signum() <= 0 || !asOfDate.isAfter(signalDate)) {
            return false;
        }

        // 依美股交易日曆產生 signalDate 後第 1..60 個交易日
        List<LocalDate> futureSessions = calendar.getTradingDays(signalDate.plusDays(1), signalDate.plusDays(130));
        if (futureSessions.isEmpty()) {
            return false;
        }
        LocalDate due5 = futureSessions.size() >= 5 ? futureSessions.get(4) : null;
        LocalDate due20 = futureSessions.size() >= 20 ? futureSessions.get(19) : null;
        LocalDate due60 = futureSessions.size() >= 60 ? futureSessions.get(59) : null;

        Map<LocalDate, QuoteBarRow> quoteByDate = new HashMap<>();
        for (QuoteBarRow bar : stockQuotes) {
            if (bar != null && bar.quoteDate() != null
                    && QuoteNormalizer.validOhlcv(bar.openPrice(), bar.highPrice(), bar.lowPrice(), bar.closePrice(), bar.volume())) {
                quoteByDate.put(bar.quoteDate(), bar);
            }
        }

        boolean changed = false;

        // 1. 獨立條件式結算 +5D / +20D / +60D 報酬（已結算絕不覆寫，缺拆股覆蓋時保留待補）
        if (record.getSettled5dDate() == null && due5 != null && !asOfDate.isBefore(due5)) {
            BigDecimal ret5 = computeSplitAlignedCloseReturn(signalDate, signalClose, due5, quoteByDate.get(due5), stockSplits, status);
            if (ret5 != null) {
                record.setReturn5d(ret5);
                record.setSettled5dDate(due5);
                changed = true;
            }
        }
        if (record.getSettled20dDate() == null && due20 != null && !asOfDate.isBefore(due20)) {
            BigDecimal ret20 = computeSplitAlignedCloseReturn(signalDate, signalClose, due20, quoteByDate.get(due20), stockSplits, status);
            if (ret20 != null) {
                record.setReturn20d(ret20);
                record.setSettled20dDate(due20);
                changed = true;
            }
        }
        if (record.getSettled60dDate() == null && due60 != null && !asOfDate.isBefore(due60)) {
            BigDecimal ret60 = computeSplitAlignedCloseReturn(signalDate, signalClose, due60, quoteByDate.get(due60), stockSplits, status);
            if (ret60 != null) {
                record.setReturn60d(ret60);
                record.setSettled60dDate(due60);
                changed = true;
            }
        }

        // 2. 路徑結算：MFE / MAE 與目標／止損首次觸發順序（僅在路徑尚未完成滿期或結果尚未終結時評估）
        boolean outcomeMutable = SignalTrackingLog.OUTCOME_PENDING.equals(record.getOutcomeStatus())
                || SignalTrackingLog.OUTCOME_DATA_GAP.equals(record.getOutcomeStatus());
        boolean needPathEval = record.getSettledPathDate() == null || outcomeMutable;
        LocalDate pathLimitDate = due60 != null && asOfDate.isAfter(due60) ? due60 : asOfDate;
        if (needPathEval && hasSplitCoverage(status, signalDate, pathLimitDate)) {
            BigDecimal stop = record.getProtectiveStop();
            BigDecimal target = record.getFirstTargetPrice();
            boolean validStop = stop != null && stop.signum() > 0 && stop.compareTo(signalClose) < 0;
            boolean validTarget = target != null && target.compareTo(signalClose) > 0;

            BigDecimal maxMfe = BigDecimal.ZERO;
            BigDecimal minMae = BigDecimal.ZERO;
            boolean anyPathGap = false;
            String detectedOutcome = null;
            LocalDate detectedOutcomeDate = null;

            int maxIndex = Math.min(60, futureSessions.size());
            for (int i = 0; i < maxIndex; i++) {
                LocalDate session = futureSessions.get(i);
                if (session.isAfter(pathLimitDate)) {
                    break;
                }
                QuoteBarRow bar = quoteByDate.get(session);
                if (bar == null) {
                    anyPathGap = true;
                    break;
                }
                BigDecimal factor = StockSplitCalculator.priceFactor(stockSplits, session, signalDate);
                BigDecimal adjOpen = bar.openPrice().multiply(factor, MC);
                BigDecimal adjHigh = bar.highPrice().multiply(factor, MC);
                BigDecimal adjLow = bar.lowPrice().multiply(factor, MC);

                BigDecimal barMfe = adjHigh.divide(signalClose, MC).subtract(BigDecimal.ONE, MC);
                BigDecimal barMae = adjLow.divide(signalClose, MC).subtract(BigDecimal.ONE, MC);
                if (barMfe.compareTo(maxMfe) > 0) maxMfe = barMfe;
                if (barMae.compareTo(minMae) < 0) minMae = barMae;

                if (detectedOutcome == null && (validStop || validTarget)) {
                    boolean hitStop = validStop && adjLow.compareTo(stop) <= 0;
                    boolean hitTarget = validTarget && adjHigh.compareTo(target) >= 0;
                    if (hitStop && hitTarget) {
                        if (adjOpen.compareTo(stop) <= 0) {
                            detectedOutcome = SignalTrackingLog.OUTCOME_STOP_HIT_FIRST;
                        } else if (adjOpen.compareTo(target) >= 0) {
                            detectedOutcome = SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST;
                        } else {
                            detectedOutcome = SignalTrackingLog.OUTCOME_AMBIGUOUS_SAME_BAR;
                        }
                        detectedOutcomeDate = session;
                    } else if (hitTarget) {
                        detectedOutcome = SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST;
                        detectedOutcomeDate = session;
                    } else if (hitStop) {
                        detectedOutcome = SignalTrackingLog.OUTCOME_STOP_HIT_FIRST;
                        detectedOutcomeDate = session;
                    }
                }
            }

            // 唯有完整 60 個交易日無缺口才寫入 MFE/MAE 與 settledPathDate，防止短期暫算值污染 60D 統計
            if (record.getSettledPathDate() == null && !anyPathGap && due60 != null && !asOfDate.isBefore(due60)) {
                record.setMfe60d(maxMfe.setScale(6, RoundingMode.HALF_UP));
                record.setMae60d(minMae.setScale(6, RoundingMode.HALF_UP));
                record.setSettledPathDate(due60);
                changed = true;
            }

            if (outcomeMutable) {
                String nextOutcome = null;
                LocalDate nextOutcomeDate = null;
                if (detectedOutcome != null) {
                    nextOutcome = detectedOutcome;
                    nextOutcomeDate = detectedOutcomeDate;
                } else if (due60 != null && !asOfDate.isBefore(due60)) {
                    if (anyPathGap) {
                        nextOutcome = SignalTrackingLog.OUTCOME_DATA_GAP;
                    } else if (!validTarget) {
                        nextOutcome = SignalTrackingLog.OUTCOME_STOP_ONLY_NO_TARGET;
                    } else {
                        nextOutcome = SignalTrackingLog.OUTCOME_EXPIRED_NEITHER;
                    }
                    nextOutcomeDate = due60;
                }
                if (nextOutcome != null
                        && (!Objects.equals(record.getOutcomeStatus(), nextOutcome)
                        || !Objects.equals(record.getOutcomeDate(), nextOutcomeDate))) {
                    record.setOutcomeStatus(nextOutcome);
                    record.setOutcomeDate(nextOutcomeDate);
                    changed = true;
                }
            }
        }

        if (changed) {
            record.setUpdatedAt(now);
        }
        return changed;
    }

    /** 彙總歷史訊號勝率與期望值統計（按訊號類型、大盤 Regime、產業與市值量級分組）。 */
    @Transactional(readOnly = true)
    public SignalTrackingStatsSummary buildStatsSummary() {
        List<SignalTrackingLog> all = logRepository.findAllByOrderBySignalDateDescIdDesc();
        if (all.isEmpty()) {
            return new SignalTrackingStatsSummary(0, 0, 0, null, null, null, List.of(), List.of(), List.of(), List.of());
        }
        GroupStats overall = aggregateGroup("ALL", "ALL", all);
        LocalDate activeTrackingCutoff = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate()
                .minusDays(SETTLE_LOOKBACK_CALENDAR_DAYS);
        int pendingCount = (int) all.stream()
                .filter(r -> r.getSignalDate() != null && !r.getSignalDate().isBefore(activeTrackingCutoff))
                .filter(r -> r.getSettled60dDate() == null
                        || r.getSettledPathDate() == null
                        || SignalTrackingLog.OUTCOME_PENDING.equals(r.getOutcomeStatus()))
                .count();

        List<GroupStats> byType = groupAndAggregate(all, r -> nonBlankOrUnknown(r.getSignalType()), r -> nonBlankOrUnknown(r.getSignalType()));
        List<GroupStats> byRegime = groupAndAggregate(all, r -> nonBlankOrUnknown(r.getMarketRegime()), r -> nonBlankOrUnknown(r.getMarketRegime()));
        List<GroupStats> byIndustry = groupAndAggregate(all,
                r -> nonBlankOrUnknown(r.getIndustryCode()),
                r -> r.getIndustryName() != null && !r.getIndustryName().isBlank() ? r.getIndustryName() : nonBlankOrUnknown(r.getIndustryCode()));
        List<GroupStats> byTier = groupAndAggregate(all, r -> nonBlankOrUnknown(r.getMarketCapTier()), r -> nonBlankOrUnknown(r.getMarketCapTier()));

        return new SignalTrackingStatsSummary(
                all.size(),
                pendingCount,
                overall.settled20dCount(),
                overall.winRate20d(),
                overall.avgReturn20d(),
                overall.targetHitRate(),
                byType,
                byRegime,
                byIndustry,
                byTier
        );
    }

    private List<GroupStats> groupAndAggregate(
            List<SignalTrackingLog> rows,
            java.util.function.Function<SignalTrackingLog, String> keyFn,
            java.util.function.Function<SignalTrackingLog, String> labelFn) {
        Map<String, List<SignalTrackingLog>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (SignalTrackingLog row : rows) {
            String key = keyFn.apply(row);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            labels.putIfAbsent(key, labelFn.apply(row));
        }
        List<GroupStats> result = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<SignalTrackingLog>> entry : grouped.entrySet()) {
            result.add(aggregateGroup(entry.getKey(), labels.get(entry.getKey()), entry.getValue()));
        }
        result.sort(Comparator.comparingInt(GroupStats::totalSignals).reversed().thenComparing(GroupStats::groupKey));
        return result;
    }

    private GroupStats aggregateGroup(String key, String label, List<SignalTrackingLog> rows) {
        List<BigDecimal> r5 = new ArrayList<>();
        List<BigDecimal> r20 = new ArrayList<>();
        List<BigDecimal> r60 = new ArrayList<>();
        List<BigDecimal> mfes = new ArrayList<>();
        List<BigDecimal> maes = new ArrayList<>();
        int win20 = 0;
        int targetHit = 0;
        int stopHit = 0;

        for (SignalTrackingLog r : rows) {
            if (r.getReturn5d() != null) r5.add(r.getReturn5d());
            if (r.getReturn20d() != null) {
                r20.add(r.getReturn20d());
                if (r.getReturn20d().signum() > 0) win20++;
            }
            if (r.getReturn60d() != null) r60.add(r.getReturn60d());
            if (r.getSettledPathDate() != null && r.getMfe60d() != null) mfes.add(r.getMfe60d());
            if (r.getSettledPathDate() != null && r.getMae60d() != null) maes.add(r.getMae60d());
            if (SignalTrackingLog.OUTCOME_TARGET_HIT_FIRST.equals(r.getOutcomeStatus())) {
                targetHit++;
            } else if (SignalTrackingLog.OUTCOME_STOP_HIT_FIRST.equals(r.getOutcomeStatus())
                    && r.getFirstTargetPrice() != null && r.getFirstTargetPrice().compareTo(r.getSignalClose()) > 0) {
                stopHit++;
            }
        }
        int resolvedTwoSided = targetHit + stopHit;
        BigDecimal winRate20 = r20.isEmpty() ? null
                : BigDecimal.valueOf(win20).divide(BigDecimal.valueOf(r20.size()), 4, RoundingMode.HALF_UP);
        BigDecimal targetHitRate = resolvedTwoSided == 0 ? null
                : BigDecimal.valueOf(targetHit).divide(BigDecimal.valueOf(resolvedTwoSided), 4, RoundingMode.HALF_UP);
        return new GroupStats(
                key,
                label,
                rows.size(),
                r5.size(),
                average(r5),
                r20.size(),
                average(r20),
                winRate20,
                r60.size(),
                average(r60),
                resolvedTwoSided,
                targetHit,
                stopHit,
                targetHitRate,
                average(mfes),
                average(maes)
        );
    }

    private boolean isWithinCooldown(LocalDate existingDate, LocalDate candidateDate) {
        if (existingDate.equals(candidateDate)) {
            return true;
        }
        LocalDate earlier = existingDate.isBefore(candidateDate) ? existingDate : candidateDate;
        LocalDate later = existingDate.isBefore(candidateDate) ? candidateDate : existingDate;
        List<LocalDate> sessionsBetween = calendar.getTradingDays(earlier.plusDays(1), later);
        return sessionsBetween.size() < DEDUP_TRADING_DAYS;
    }

    private BigDecimal computeSplitAlignedCloseReturn(
            LocalDate signalDate,
            BigDecimal signalClose,
            LocalDate dueDate,
            QuoteBarRow dueBar,
            List<StockSplit> stockSplits,
            StockStatus status) {
        if (dueBar == null || dueBar.closePrice() == null || !hasSplitCoverage(status, signalDate, dueDate)) {
            return null;
        }
        BigDecimal factor = StockSplitCalculator.priceFactor(stockSplits, dueDate, signalDate);
        BigDecimal adjClose = dueBar.closePrice().multiply(factor, MC);
        return adjClose.divide(signalClose, MC).subtract(BigDecimal.ONE, MC).setScale(6, RoundingMode.HALF_UP);
    }

    private boolean hasSplitCoverage(StockStatus status, LocalDate fromDate, LocalDate throughDate) {
        if (status == null || status.getSplitFromDate() == null || status.getSplitDate() == null) {
            return false;
        }
        if (status.getSplitFromDate().isAfter(fromDate) || status.getSplitDate().isBefore(throughDate)) {
            return false;
        }
        String msg = status.getSplitMessage();
        return msg == null || msg.isBlank() || msg.toUpperCase(Locale.ROOT).startsWith("WARNING:");
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v, MC);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private String nonBlankOrUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private record BatchSettlementInputs(
            Map<String, List<QuoteBarRow>> quotesByFigi,
            Map<String, List<StockSplit>> splitsByFigi,
            Map<String, StockStatus> statusByFigi) {}
}
