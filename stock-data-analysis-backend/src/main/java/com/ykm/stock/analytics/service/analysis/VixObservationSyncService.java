package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.client.YahooVixClient;
import com.ykm.stock.analytics.model.dto.thirdparty.response.YahooVixChartResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VixObservationSyncService {
    private final YahooVixClient client;
    private final MarketSentimentService sentiment;
    private final MarketStatusService marketStatusService;

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
