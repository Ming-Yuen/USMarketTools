package com.ykm.stock.analytics.service.enhancement;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.FredCreditSpreadClient;
import com.ykm.stock.analytics.client.FredCreditSpreadClient.CreditSpreadPoint;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.entity.MacroCreditSpreadDaily;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MacroCreditSpreadDailyRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 2.5 總經高收益債信用利差（FRED BAMLH0A0HYM2）同步與防禦評估服務。
 * 規則邊界：
 * - 只持久化 FRED 原始觀測值，絕不複製舊值充當無數據日。
 * - 前 20 筆有效觀測均線排除當期（prior 20 observations），deltaBps = 100 × (oasPct - prior20MeanPct)。
 * - ELEVATED（>= 4.50%）僅顯示警示；STRESS（>= 5.00%）或 WIDENING（oasPct >= 3.50% 且 delta >= +50 bps，須為新鮮觀測）才觸發 MacroCaution 縮倉。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MacroCreditSpreadService {
    public static final BigDecimal THRESHOLD_ELEVATED_PCT = new BigDecimal("4.50");
    public static final BigDecimal THRESHOLD_STRESS_PCT = new BigDecimal("5.00");
    public static final BigDecimal THRESHOLD_WIDENING_MIN_OAS_PCT = new BigDecimal("3.50");
    public static final BigDecimal THRESHOLD_WIDENING_DELTA_BPS = new BigDecimal("50");
    public static final int MAX_FRESH_TRADING_DAY_LAG = 5;

    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_ELEVATED = "ELEVATED";
    public static final String STATUS_STRESS = "STRESS";
    public static final String STATUS_WIDENING = "WIDENING";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    public static final String TRIGGER_HY_OAS_STRESS = "HY_OAS_STRESS";
    public static final String TRIGGER_HY_OAS_WIDENING = "HY_OAS_WIDENING";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int INCREMENTAL_OVERLAP_DAYS = 30;
    private static final int PERSIST_BATCH_SIZE = 500;
    private static final long LIVE_OBSERVATION_WINDOW_DAYS = 7L;

    private final FredCreditSpreadClient fredClient;
    private final MacroCreditSpreadDailyRepository repository;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactionTemplate;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private PortfolioSysConfigRepository sysConfigRepository;

    public record SyncResult(int fetchedCount, int savedCount, LocalDate latestObservationDate) {}

    /** 同步 FRED BAMLH0A0HYM2 原始觀測：無水位時自 sync_history_start_date 全量回補，有水位時往回重疊 30 天增量同步。 */
    public SyncResult syncLatest() {
        if (!fredClient.isConfigured()) {
            log.warn("FRED HY OAS sync skipped: FRED_API_KEY not configured");
            return new SyncResult(0, 0, repository.findTopByOrderByObservationDateDesc().map(MacroCreditSpreadDaily::getObservationDate).orElse(null));
        }
        LocalDate todayNy = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate watermark = jobConfigRepository == null
                ? null
                : jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.CREDIT_SPREAD_DAILY_SYNC).orElse(null);
        LocalDate historyStart = PortfolioSysConfigRepository.resolveSyncHistoryStartDate(sysConfigRepository);
        LocalDate from;
        if (watermark != null) {
            LocalDate overlapFrom = watermark.minusDays(INCREMENTAL_OVERLAP_DAYS);
            from = overlapFrom.isBefore(historyStart) ? historyStart : overlapFrom;
        } else {
            from = historyStart;
        }
        List<CreditSpreadPoint> points = fredClient.fetchHighYieldOas(from, todayNy);
        int saved = persistPoints(points);
        LocalDate fetchedLatest = points.stream()
                .map(CreditSpreadPoint::observationDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        LocalDate latestDate = fetchedLatest != null
                ? fetchedLatest
                : repository.findTopByOrderByObservationDateDesc().map(MacroCreditSpreadDaily::getObservationDate).orElse(null);
        if (!points.isEmpty() && fetchedLatest != null) {
            JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.CREDIT_SPREAD_DAILY_SYNC, fetchedLatest);
        }
        log.info("FRED HY OAS sync completed | from={} fetched={} saved={} latestObservationDate={}",
                from, points.size(), saved, latestDate);
        return new SyncResult(points.size(), saved, latestDate);
    }

    public int persistPoints(List<CreditSpreadPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        Instant now = dateTimeService.now();
        LocalDate todayNy = now.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate liveCutoffDate = todayNy.minusDays(LIVE_OBSERVATION_WINDOW_DAYS);
        int totalChanged = 0;
        for (List<CreditSpreadPoint> batch : Lists.partition(points, PERSIST_BATCH_SIZE)) {
            Integer batchSaved = transactionTemplate.execute(txStatus -> {
                List<LocalDate> dates = batch.stream().map(CreditSpreadPoint::observationDate).distinct().toList();
                Map<LocalDate, MacroCreditSpreadDaily> existing = new LinkedHashMap<>();
                for (MacroCreditSpreadDaily row : repository.findAllById(dates)) {
                    existing.put(row.getObservationDate(), row);
                }
                int changed = 0;
                for (CreditSpreadPoint point : batch) {
                    // 歷史舊日期（早於近 7 日即時視窗）寫入或清掉逾期回補時戳時將 observedAt 設為 NULL，保留近 7 日內真實即時抓取值之觀測時間。
                    boolean isHistoricalBackfillDate = point.observationDate().isBefore(liveCutoffDate);
                    Instant targetObservedAt = isHistoricalBackfillDate ? null : now;
                    MacroCreditSpreadDaily current = existing.get(point.observationDate());
                    if (current == null) {
                        MacroCreditSpreadDaily created = new MacroCreditSpreadDaily();
                        created.setObservationDate(point.observationDate());
                        created.setSeriesId(point.seriesId());
                        created.setOasPct(point.oasPct());
                        created.setSourceRealtimeStart(point.realtimeStart());
                        created.setObservedAt(targetObservedAt);
                        existing.put(point.observationDate(), created);
                        changed++;
                    } else if (current.getOasPct().compareTo(point.oasPct()) != 0) {
                        current.setOasPct(point.oasPct());
                        current.setSourceRealtimeStart(point.realtimeStart());
                        current.setObservedAt(targetObservedAt);
                        changed++;
                    } else if (isHistoricalBackfillDate && isBackfilledObservedAt(point.observationDate(), current.getObservedAt())) {
                        current.setObservedAt(null);
                        changed++;
                    }
                }
                if (changed > 0) {
                    repository.saveAll(existing.values());
                }
                return changed;
            });
            totalChanged += batchSaved == null ? 0 : batchSaved;
        }
        return totalChanged;
    }

    /** 判斷既有 observedAt 是否為事後回補所寫（晚於觀測日逾 7 天），避免抹去當週即時同步捕獲的真實觀測時間。 */
    private boolean isBackfilledObservedAt(LocalDate observationDate, Instant observedAt) {
        if (observationDate == null || observedAt == null) {
            return false;
        }
        LocalDate observedDateNy = observedAt.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        return observedDateNy.isAfter(observationDate.plusDays(LIVE_OBSERVATION_WINDOW_DAYS));
    }

    /** 依指定美東交易日（含當日之前）評估最新高收益債信用利差與擴寬風險；歷史查詢嚴格截斷於 asOfDate 與 observationCutoff。 */
    @Transactional(readOnly = true)
    public CreditSpreadSummaryDto evaluateAt(LocalDate asOfDate) {
        return evaluateAt(asOfDate, null);
    }

    @Transactional(readOnly = true)
    public CreditSpreadSummaryDto evaluateAt(LocalDate asOfDate, Instant observationCutoff) {
        if (asOfDate == null) {
            return CreditSpreadSummaryDto.unavailable(STATUS_UNAVAILABLE);
        }
        List<MacroCreditSpreadDaily> recentDesc = repository.findTop30ByObservationDateLessThanEqualOrderByObservationDateDesc(asOfDate);
        if (observationCutoff != null) {
            recentDesc = recentDesc.stream()
                    .filter(row -> row.getObservedAt() == null || !row.getObservedAt().isAfter(observationCutoff))
                    .toList();
        }
        return evaluateFromDescendingRows(recentDesc, asOfDate);
    }

    /** 純記憶體評估（供 PreloadedMarketData 與單元測試直接呼叫）。 */
    public CreditSpreadSummaryDto evaluateFromDescendingRows(List<MacroCreditSpreadDaily> recentDesc, LocalDate asOfDate) {
        if (recentDesc == null || recentDesc.isEmpty()) {
            return CreditSpreadSummaryDto.unavailable(STATUS_UNAVAILABLE);
        }
        MacroCreditSpreadDaily latest = recentDesc.getFirst();
        if (latest == null || latest.getOasPct() == null || latest.getObservationDate() == null) {
            return CreditSpreadSummaryDto.unavailable(STATUS_UNAVAILABLE);
        }
        BigDecimal oasPct = latest.getOasPct().setScale(2, RoundingMode.HALF_UP);
        BigDecimal prior20MeanPct = null;
        BigDecimal rawDeltaBps = null;
        BigDecimal deltaBps = null;
        if (recentDesc.size() >= 21) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 1; i <= 20; i++) {
                sum = sum.add(recentDesc.get(i).getOasPct(), MC);
            }
            BigDecimal rawPrior20Mean = sum.divide(BigDecimal.valueOf(20), MC);
            prior20MeanPct = rawPrior20Mean.setScale(4, RoundingMode.HALF_UP);
            rawDeltaBps = latest.getOasPct().subtract(rawPrior20Mean, MC).multiply(HUNDRED, MC);
            deltaBps = rawDeltaBps.setScale(1, RoundingMode.HALF_UP);
        }

        boolean stale = false;
        if (asOfDate != null && latest.getObservationDate().isBefore(asOfDate)) {
            int tradingLag = Math.max(0, calendar.getTradingDays(latest.getObservationDate(), asOfDate).size() - 1);
            stale = tradingLag > MAX_FRESH_TRADING_DAY_LAG;
        }

        boolean stress = latest.getOasPct().compareTo(THRESHOLD_STRESS_PCT) >= 0;
        boolean elevated = latest.getOasPct().compareTo(THRESHOLD_ELEVATED_PCT) >= 0;
        boolean widening = rawDeltaBps != null
                && latest.getOasPct().compareTo(THRESHOLD_WIDENING_MIN_OAS_PCT) >= 0
                && rawDeltaBps.compareTo(THRESHOLD_WIDENING_DELTA_BPS) >= 0;

        String status;
        boolean macroCautionTriggered;
        if (stale) {
            status = STATUS_STALE;
            macroCautionTriggered = false;
        } else if (stress) {
            status = STATUS_STRESS;
            macroCautionTriggered = true;
        } else if (widening) {
            status = STATUS_WIDENING;
            macroCautionTriggered = true;
        } else if (elevated) {
            status = STATUS_ELEVATED;
            macroCautionTriggered = false;
        } else {
            status = STATUS_NORMAL;
            macroCautionTriggered = false;
        }

        java.util.ArrayList<String> triggers = new java.util.ArrayList<>();
        if (!stale) {
            if (stress) {
                triggers.add(TRIGGER_HY_OAS_STRESS);
            }
            if (widening) {
                triggers.add(TRIGGER_HY_OAS_WIDENING);
            }
        }

        return new CreditSpreadSummaryDto(
                latest.getSeriesId(),
                oasPct,
                latest.getObservationDate(),
                prior20MeanPct == null ? null : prior20MeanPct.setScale(2, RoundingMode.HALF_UP),
                deltaBps,
                status,
                macroCautionTriggered,
                stale,
                latest.getObservedAt(),
                List.copyOf(triggers)
        );
    }

    /** 判定是否應寫入 MacroCautionDto.triggerCodes（依未四捨五入之雙重獨立判定，可同時輸出 STRESS 與 WIDENING，ELEVATED 僅顯示警示）。 */
    public static List<String> resolveMacroCautionTriggers(CreditSpreadSummaryDto spread) {
        if (spread == null || spread.stale() || !spread.macroCautionTriggered()) {
            return List.of();
        }
        if (spread.resolvedTriggerCodes() != null && !spread.resolvedTriggerCodes().isEmpty()) {
            return List.copyOf(spread.resolvedTriggerCodes());
        }
        if (STATUS_STRESS.equals(spread.status())) {
            return List.of(TRIGGER_HY_OAS_STRESS);
        }
        if (STATUS_WIDENING.equals(spread.status())) {
            return List.of(TRIGGER_HY_OAS_WIDENING);
        }
        return List.of();
    }
}
