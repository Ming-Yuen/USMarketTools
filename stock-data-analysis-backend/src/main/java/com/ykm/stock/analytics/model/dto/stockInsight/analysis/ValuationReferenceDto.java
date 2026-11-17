package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 估值比較參考區間（自身歷史分位、同業市場比較或產業倍數假設情境）。 */
public record ValuationReferenceDto(
        String kind,
        String metricCode,
        String status,
        String reasonCode,
        BigDecimal multipleLow,
        BigDecimal multipleMedian,
        BigDecimal multipleHigh,
        BigDecimal priceLow,
        BigDecimal priceHigh,
        Integer sampleCount,
        Integer windowSampleCount,
        Integer excludedSampleCount,
        LocalDate sampleStartDate,
        LocalDate sampleEndDate
) {
    public static final String KIND_HISTORICAL_MARKET = "HISTORICAL_MARKET";
    public static final String KIND_PEER_MARKET = "PEER_MARKET";
    public static final String KIND_INDUSTRY_ASSUMPTION = "INDUSTRY_ASSUMPTION";

    public ValuationReferenceDto(
            String kind,
            String metricCode,
            String status,
            String reasonCode,
            BigDecimal multipleLow,
            BigDecimal multipleMedian,
            BigDecimal multipleHigh,
            BigDecimal priceLow,
            BigDecimal priceHigh,
            Integer sampleCount
    ) {
        this(kind, metricCode, status, reasonCode, multipleLow, multipleMedian, multipleHigh,
                priceLow, priceHigh, sampleCount, null, null, null, null);
    }

    public static ValuationReferenceDto unavailable(String kind, String metricCode, String status, String reasonCode) {
        return new ValuationReferenceDto(kind, metricCode, status, reasonCode,
                null, null, null, null, null, null, null, null, null, null);
    }

    public static ValuationReferenceDto unavailableWithSampleCoverage(
            String kind, String metricCode, String status, String reasonCode,
            Integer sampleCount, Integer windowSampleCount, Integer excludedSampleCount,
            LocalDate sampleStartDate, LocalDate sampleEndDate) {
        return new ValuationReferenceDto(kind, metricCode, status, reasonCode,
                null, null, null, null, null, sampleCount, windowSampleCount, excludedSampleCount,
                sampleStartDate, sampleEndDate);
    }
}
