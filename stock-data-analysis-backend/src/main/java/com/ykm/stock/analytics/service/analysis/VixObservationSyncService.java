package com.ykm.stock.analytics.service.analysis;

import com.google.common.collect.Lists;
import com.ykm.stock.analytics.client.FredVixClient;
import com.ykm.stock.analytics.client.FredVixClient.VixPoint;
import com.ykm.stock.analytics.client.YahooVixClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.thirdparty.response.YahooVixChartResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class VixObservationSyncService {
    private static final int BACKFILL_SAVE_BATCH_SIZE = 500;
    private static final long INCREMENTAL_OVERLAP_DAYS = 45L;
    private static final int FULL_HISTORY_MAX_ALLOWED_GAPS = 5;

    private final YahooVixClient client;
    private final MarketSentimentService sentiment;
    private final MarketStatusService marketStatusService;
    private final FredVixClient fredVixClient;
    private final MarketSentimentDailyRepository repository;
    @Autowired(required = false)
    private PortfolioSysConfigRepository sysConfigRepository;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;

    public VixObservationSyncService(
            YahooVixClient client,
            MarketSentimentService sentiment,
            MarketStatusService marketStatusService) {
        this(client, sentiment, marketStatusService, null, null);
    }

    @Autowired
    public VixObservationSyncService(
            YahooVixClient client,
            MarketSentimentService sentiment,
            MarketStatusService marketStatusService,
            FredVixClient fredVixClient,
            MarketSentimentDailyRepository repository) {
        this.client = client;
        this.sentiment = sentiment;
        this.marketStatusService = marketStatusService;
        this.fredVixClient = fredVixClient;
        this.repository = repository;
    }

    /**
     * 依 job_config.watermark_date 與 sys_config(sync_history_start_date) 補齊 FRED VIXCLS 缺口（不覆寫既有觀測）：
     * - 無水位時：自 sync_history_start_date 全量檢查並補齊至最新已收市交易日；
     * - 有水位 W 時：檢查並補齊 [W - 45d, 最新已收市日] 內缺失交易日。
     */
    public int backfillHistoricalIfNeeded(Instant observedAt) {
        if (fredVixClient == null || !fredVixClient.isConfigured() || repository == null) {
            return 0;
        }
        LocalDate historyStart = PortfolioSysConfigRepository.resolveSyncHistoryStartDate(sysConfigRepository);
        LocalDate through = marketStatusService.getLastClosedTradingDay();
        if (through == null || !through.isAfter(historyStart)) {
            return 0;
        }
        LocalDate watermark = jobConfigRepository == null
                ? null
                : jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.MARKET_SENTIMENT_DAILY).orElse(null);
        LocalDate from = resolveCheckStart(historyStart, watermark);
        if (!through.isAfter(from)) {
            return 0;
        }
        Set<LocalDate> existingDates = new HashSet<>(repository.findDatesWithVixBetween(from, through));
        int missingSessions = countMissingSessions(from, through, existingDates);
        if (missingSessions == 0) {
            return 0;
        }
        return backfillFromFred(from, through, observedAt, existingDates);
    }

    /** 實查目標區間內本地 VIX 缺口是否已補齊，避免未設 FRED key 或部分回傳時誤推 MARKET_SENTIMENT_DAILY 水位。 */
    public boolean isHistoryReadyForWatermark() {
        if (jobConfigRepository == null || repository == null) {
            return true;
        }
        LocalDate historyStart = PortfolioSysConfigRepository.resolveSyncHistoryStartDate(sysConfigRepository);
        LocalDate through = marketStatusService.getLastClosedTradingDay();
        if (through == null || !through.isAfter(historyStart)) {
            return true;
        }
        LocalDate watermark = jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.MARKET_SENTIMENT_DAILY).orElse(null);
        LocalDate from = resolveCheckStart(historyStart, watermark);
        if (!through.isAfter(from)) {
            return true;
        }
        Set<LocalDate> existingDates = new HashSet<>(repository.findDatesWithVixBetween(from, through));
        int missingSessions = countMissingSessions(from, through, existingDates);
        int maxAllowedGaps = watermark == null ? FULL_HISTORY_MAX_ALLOWED_GAPS : 0;
        return missingSessions <= maxAllowedGaps;
    }

    private LocalDate resolveCheckStart(LocalDate historyStart, LocalDate watermark) {
        if (watermark == null) {
            return historyStart;
        }
        LocalDate overlapFrom = watermark.minusDays(INCREMENTAL_OVERLAP_DAYS);
        return overlapFrom.isBefore(historyStart) ? historyStart : overlapFrom;
    }

    private int countMissingSessions(LocalDate from, LocalDate through, Set<LocalDate> existingDates) {
        LocalDate checkEnd = marketStatusService.getPreviousTradingDay(through);
        if (checkEnd == null || checkEnd.isBefore(from)) {
            return 0;
        }
        int missingSessions = 0;
        for (LocalDate session : marketStatusService.getTradingDays(from, checkEnd)) {
            if (!existingDates.contains(session)) {
                missingSessions++;
            }
        }
        return missingSessions;
    }

    /** 依指定區間向 FRED 下載 VIXCLS 並僅寫入本地尚無 vix_close 之市場日期；保留未知公開時間為 NULL。 */
    public int backfillFromFred(LocalDate from, LocalDate through, Instant observedAt) {
        if (fredVixClient == null || !fredVixClient.isConfigured()) {
            throw new IllegalStateException("FRED_API_KEY_NOT_CONFIGURED");
        }
        Set<LocalDate> existingDates = repository == null
                ? Set.of()
                : new HashSet<>(repository.findDatesWithVixBetween(from, through));
        return backfillFromFred(from, through, observedAt, existingDates);
    }

    private int backfillFromFred(LocalDate from, LocalDate through, Instant observedAt, Set<LocalDate> existingDates) {
        Instant effectiveObservedAt = observedAt == null ? Instant.now() : observedAt;
        List<VixPoint> points = fredVixClient.fetchVixCloses(from, through);
        if (from != null && through != null && from.isBefore(through) && points.isEmpty()) {
            throw new IllegalStateException("FRED_VIXCLS_EMPTY_OBSERVATIONS");
        }
        List<MarketSentimentDaily> missingRows = new ArrayList<>();
        for (VixPoint point : points) {
            if (point == null || point.observationDate() == null || point.vixClose() == null) {
                continue;
            }
            LocalDate date = point.observationDate();
            if (date.isBefore(from) || date.isAfter(through) || existingDates.contains(date)) {
                continue;
            }
            if (marketStatusService != null && !marketStatusService.isTradingDay(date)) {
                continue;
            }
            MarketSentimentDaily row = new MarketSentimentDaily();
            row.setMarketDate(date);
            row.setVixClose(point.vixClose());
            row.setVixSource(FredVixClient.SOURCE_FRED_VIXCLS);
            row.setVixSourceAt(null);
            row.setVixObservedAt(effectiveObservedAt);
            missingRows.add(row);
        }
        for (List<MarketSentimentDaily> batch : Lists.partition(missingRows, BACKFILL_SAVE_BATCH_SIZE)) {
            sentiment.save(batch);
        }
        if (!missingRows.isEmpty()) {
            log.info("FRED VIXCLS historical backfill completed | from={} through={} inserted={}",
                    from, through, missingRows.size());
        }
        return missingRows.size();
    }

    /** 只取得已收市日的真正 VIX 點數，不使用 CNN 的 VIX 情緒分數代替。 */
    public List<MarketSentimentDaily> downloadObservations(Instant observedAt) {
        LocalDate through = marketStatusService.getLastClosedTradingDay();
        YahooVixChartResponse response = client.fetchDaily();
        YahooVixChartResponse.Result result = response.getChart().getResult().getFirst();
        List<Long> timestamps = result.getTimestamp();
        List<YahooVixChartResponse.QuoteWrapper> quoteWrappers = result.getIndicators() == null ? null : result.getIndicators().getQuote();
        if (timestamps == null || quoteWrappers == null || quoteWrappers.isEmpty() || quoteWrappers.getFirst().getClose() == null)
            throw new IllegalStateException("Yahoo VIX chart is missing timestamps/close values");
        List<Double> closes = quoteWrappers.getFirst().getClose();
        if (timestamps.size() != closes.size()) throw new IllegalStateException("VIX_BAR_LENGTH_MISMATCH");
        List<MarketSentimentDaily> rows = new ArrayList<>();
        LocalDate latest = null;
        for (int i = 0; i < timestamps.size(); i++) {
            Long timestamp = timestamps.get(i);
            Double close = closes.get(i);
            if (timestamp == null || close == null || !Double.isFinite(close) || close <= 0) continue;
            LocalDate date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.of("America/New_York")).toLocalDate();
            if (date.isAfter(through)) continue;
            MarketSentimentDaily row = new MarketSentimentDaily();
            row.setMarketDate(date);
            row.setVixClose(BigDecimal.valueOf(close));
            row.setVixSource("YAHOO_FINANCE_CBOE_VIX");
            row.setVixObservedAt(observedAt);
            // Bar timestamp 表示交易日，不是來源公開時間，不能推定為16:15發布。
            rows.add(row);
            if (latest == null || date.isAfter(latest)) latest = date;
        }
        if (latest == null || latest.isBefore(through)) throw new IllegalStateException("VIX_CLOSE_STALE_SOURCE: required=" + through + ", latest=" + latest);
        return rows;
    }

    public void saveObservations(List<MarketSentimentDaily> rows) {
        sentiment.save(rows);
    }
}
