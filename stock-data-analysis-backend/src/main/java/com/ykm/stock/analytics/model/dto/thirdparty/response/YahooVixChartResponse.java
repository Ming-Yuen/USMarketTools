package com.ykm.stock.analytics.model.dto.thirdparty.response;

import lombok.Data;

import java.util.List;

@Data
public class YahooVixChartResponse {
    private Chart chart;

    @Data
    public static class Chart {
        private List<Result> result;
    }

    @Data
    public static class Result {
        private Meta meta;
        private List<Long> timestamp;
        private Indicators indicators;
    }

    @Data
    public static class Meta {
        private String symbol;
        private Double regularMarketPrice;
        private Double chartPreviousClose;
    }

    @Data
    public static class Indicators {
        private List<QuoteWrapper> quote;
    }

    @Data
    public static class QuoteWrapper {
        private List<Double> open;
        private List<Double> high;
        private List<Double> low;
        private List<Double> close;
    }
}
