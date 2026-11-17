package com.ykm.stock.analytics.converter.jpa;

import com.ykm.stock.analytics.model.dto.thirdparty.response.FearAndGreedResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily.SentimentMetric;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Component
public class MarketSentimentDailyMapper {
    /** 每個來源指標使用自己的日期；不能把前一天的廣度值貼到今天的總指數日期。 */
    public List<MarketSentimentDaily> toDailyRows(FearAndGreedResponse source, Instant observedAt) {
        Map<LocalDate, MarketSentimentDaily> rows = new LinkedHashMap<>();
        SentimentMetric fearGreed = toSnapshotMetric(source.getFearAndGreed(), observedAt);
        if (fearGreed == null) fearGreed = toHistoricalMetric(source.getFearAndGreedHistorical(), observedAt);
        add(rows, fearGreed, MarketSentimentDaily::setFearAndGreed);
        add(rows, toHistoricalMetric(source.getMarketMomentumSp500(), observedAt), MarketSentimentDaily::setMarketMomentumSp500);
        add(rows, toHistoricalMetric(source.getStockPriceStrength(), observedAt), MarketSentimentDaily::setStockPriceStrength);
        add(rows, toHistoricalMetric(source.getStockPriceBreadth(), observedAt), MarketSentimentDaily::setStockPriceBreadth);
        add(rows, toHistoricalMetric(source.getPutCallOptions(), observedAt), MarketSentimentDaily::setPutCallOptions);
        add(rows, toHistoricalMetric(source.getMarketVolatilityVix(), observedAt), MarketSentimentDaily::setMarketVolatilityVix);
        add(rows, toHistoricalMetric(source.getJunkBondDemand(), observedAt), MarketSentimentDaily::setJunkBondDemand);
        add(rows, toHistoricalMetric(source.getSafeHavenDemand(), observedAt), MarketSentimentDaily::setSafeHavenDemand);
        if (rows.isEmpty()) throw new IllegalStateException("MARKET_SENTIMENT_TIMESTAMP_REQUIRED");
        return List.copyOf(rows.values());
    }

    private void add(Map<LocalDate, MarketSentimentDaily> rows, SentimentMetric metric,
                     BiConsumer<MarketSentimentDaily, SentimentMetric> setter) {
        if (metric == null || metric.getScore() == null || metric.getCapturedAt() == null) return;
        if (metric.getCapturedAt().isAfter(metric.getObservedAt())) throw new IllegalStateException("CNN_SOURCE_TIME_IN_FUTURE");
        LocalDate date = metric.getCapturedAt().atZone(ZoneOffset.UTC).toLocalDate();
        MarketSentimentDaily row = rows.computeIfAbsent(date, key -> {
            MarketSentimentDaily created = new MarketSentimentDaily();
            created.setMarketDate(key);
            created.setCnnSource("CNN_FEAR_AND_GREED");
            return created;
        });
        setter.accept(row, metric);
    }

    private SentimentMetric toSnapshotMetric(FearAndGreedResponse.SentimentSnapshot source, Instant observedAt) {
        if (source == null || source.getScore() == null || source.getTimestamp() == null) return null;
        return new SentimentMetric(source.getScore(), Instant.parse(source.getTimestamp()), observedAt);
    }

    private SentimentMetric toHistoricalMetric(FearAndGreedResponse.HistoricalSentiment source, Instant observedAt) {
        if (source == null) return null;
        if (source.getScore() != null && source.getTimestamp() != null)
            return new SentimentMetric(source.getScore(), toInstant(source.getTimestamp()), observedAt);
        if (source.getData() == null) return null;
        return source.getData().stream().filter(point -> point != null && point.getX() != null && point.getY() != null)
                .max(Comparator.comparing(FearAndGreedResponse.HistoricalPoint::getX))
                .map(point -> new SentimentMetric(point.getY(), toInstant(point.getX()), observedAt)).orElse(null);
    }

    private Instant toInstant(BigDecimal epochMillis) {
        return Instant.ofEpochMilli(epochMillis.longValueExact());
    }
}
