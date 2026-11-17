package com.ykm.stock.analytics.model.dto.thirdparty.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class FearAndGreedResponse {

    @JsonProperty("fear_and_greed")
    private SentimentSnapshot fearAndGreed;

    @JsonProperty("fear_and_greed_historical")
    private HistoricalSentiment fearAndGreedHistorical;

    @JsonProperty("market_momentum_sp500")
    private HistoricalSentiment marketMomentumSp500;

    @JsonProperty("market_momentum_sp125")
    private HistoricalSentiment marketMomentumSp125;

    @JsonProperty("stock_price_strength")
    private HistoricalSentiment stockPriceStrength;

    @JsonProperty("stock_price_breadth")
    private HistoricalSentiment stockPriceBreadth;

    @JsonProperty("put_call_options")
    private HistoricalSentiment putCallOptions;

    @JsonProperty("market_volatility_vix")
    private HistoricalSentiment marketVolatilityVix;

    @JsonProperty("market_volatility_vix_50")
    private HistoricalSentiment marketVolatilityVix50;

    @JsonProperty("junk_bond_demand")
    private HistoricalSentiment junkBondDemand;

    @JsonProperty("safe_haven_demand")
    private HistoricalSentiment safeHavenDemand;

    @Data
    public static class SentimentSnapshot {

        private BigDecimal score;
        private String rating;
        private String timestamp;

        @JsonProperty("previous_close")
        private BigDecimal previousClose;

        @JsonProperty("previous_1_week")
        private BigDecimal previous1Week;

        @JsonProperty("previous_1_month")
        private BigDecimal previous1Month;

        @JsonProperty("previous_1_year")
        private BigDecimal previous1Year;
    }

    @Data
    public static class HistoricalSentiment {

        private BigDecimal timestamp;
        private BigDecimal score;
        private String rating;
        private List<HistoricalPoint> data;
    }

    @Data
    public static class HistoricalPoint {

        private BigDecimal x;
        private BigDecimal y;
        private String rating;
    }
}