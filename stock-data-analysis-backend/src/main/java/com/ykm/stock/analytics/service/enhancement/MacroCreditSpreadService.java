package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.FredCreditSpreadClient;
import com.ykm.stock.analytics.client.FredCreditSpreadClient.CreditSpreadPoint;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.entity.MacroCreditSpreadDaily;
import com.ykm.stock.analytics.repository.jpa.MacroCreditSpreadDailyRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Optional;

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
    private static final int INITIAL_BACKFILL_DAYS = 1100;
    private static final int INCREMENTAL_OVERLAP_DAYS = 30;

    private final FredCreditSpreadClient fredClient;
    private final MacroCreditSpreadDailyRepository repository;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactionTemplate;

    public record SyncResult(int fetchedCount, int savedCount, LocalDate latestObservationDate) {}

    /** 同步最新或回補近 3 年 FRED BAMLH0A0HYM2 原始觀測；外部呼叫在交易外執行。 */
    public SyncResult syncLatest() {
        if (!fredClient.isConfigured()) {
            log.warn("FRED HY OAS sync skipped: FRED_API_KEY not configured");
            return new SyncResult(0, 0, repository.findTopByOrderByObservationDateDesc().map(MacroCreditSpreadDaily::getObservationDate).orElse(null));
        }
        LocalDate todayNy = dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        Optional<MacroCreditSpreadDaily> latestStored = repository.findTopByOrderByObservationDateDesc();
        LocalDate from = latestStored
                .map(row -> row.getObservationDate().minusDays(INCREMENTAL_OVERLAP_DAYS))
                .orElse(todayNy.minusDays(INITIAL_BACKFILL_DAYS));
        List<CreditSpreadPoint> points = fredClient.fetchHighYieldOas(from, todayNy);
        int saved = persistPoints(points);
        LocalDate latestDate = repository.findTopByOrderByObservationDateDesc()
                .map(MacroCreditSpreadDaily::getObservationDate)
                .orElse(null);
        log.info("FRED HY OAS sync completed | fetched={} saved={} latestObservationDate={}", points.size(), saved, latestDate);
        return new SyncResult(points.size(), saved, latestDate);
    }

    public int persistPoints(List<CreditSpreadPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        Integer saved = transactionTemplate.execute(txStatus -> {
            List<LocalDate> dates = points.stream().map(CreditSpreadPoint::observationDate).distinct().toList();
            Map<LocalDate, MacroCreditSpreadDaily> existing = new LinkedHashMap<>();
            for (MacroCreditSpreadDaily row : repository.findAllById(dates)) {
                existing.put(row.getObservationDate(), row);
            }
            Instant now = dateTimeService.now();
            int changed = 0;
            for (CreditSpreadPoint point : points) {
                MacroCreditSpreadDaily current = existing.get(point.observationDate());
                if (current == null) {
                    MacroCreditSpreadDaily created = new MacroCreditSpreadDaily();
                    created.setObservationDate(point.observationDate());
                    created.setSeriesId(point.seriesId());
                    created.setOasPct(point.oasPct());
                    created.setSourceRealtimeStart(point.realtimeStart());
                    created.setObservedAt(now);
                    existing.put(point.observationDate(), created);
                    changed++;
                } else if (current.getOasPct().compareTo(point.oasPct()) != 0) {
                    current.setOasPct(point.oasPct());
                    current.setSourceRealtimeStart(point.realtimeStart());
                    current.setObservedAt(now);
                    changed++;
                }
            }
            if (changed > 0) {
                repository.saveAll(existing.values());
            }
            return changed;
        });
        return saved == null ? 0 : saved;
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

        return new CreditSpreadSummaryDto(
                latest.getSeriesId(),
                oasPct,
                latest.getObservationDate(),
                prior20MeanPct == null ? null : prior20MeanPct.setScale(2, RoundingMode.HALF_UP),
                deltaBps,
                status,
                macroCautionTriggered,
                stale,
                latest.getObservedAt()
        );
    }

    /** 判定是否應寫入 MacroCautionDto.triggerCodes（僅 STRESS 與 WIDENING 觸發縮倉，ELEVATED 僅顯示警示）。 */
    public static List<String> resolveMacroCautionTriggers(CreditSpreadSummaryDto spread) {
        if (spread == null || spread.stale() || !spread.macroCautionTriggered()) {
            return List.of();
        }
        java.util.ArrayList<String> triggers = new java.util.ArrayList<>();
        if (spread.oasPct() != null && spread.oasPct().compareTo(THRESHOLD_STRESS_PCT) >= 0) {
            triggers.add(TRIGGER_HY_OAS_STRESS);
        }
        if (spread.oasPct() != null && spread.deltaVsPrior20Bps() != null
                && spread.oasPct().compareTo(THRESHOLD_WIDENING_MIN_OAS_PCT) >= 0
                && spread.deltaVsPrior20Bps().compareTo(THRESHOLD_WIDENING_DELTA_BPS) >= 0) {
            triggers.add(TRIGGER_HY_OAS_WIDENING);
        }
        return List.copyOf(triggers);
    }
}
