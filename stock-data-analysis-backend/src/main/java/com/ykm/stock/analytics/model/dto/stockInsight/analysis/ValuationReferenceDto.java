package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 估值比較參考區間（自身歷史分位、同業市場比較或產業倍數假設情境）。
 * currentPercentile 為 0–100 經驗分位排名（mid-rank）；INDUSTRY_ASSUMPTION 或當期倍數非正／缺失時固定為 null。
 * priceMedian 對市場錨點為 P50 換算參考價；對 INDUSTRY_ASSUMPTION 為假設上下限中點（非經驗分位中位數）。
 */
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
        LocalDate sampleEndDate,
        BigDecimal currentMultiple,
        BigDecimal currentPercentile,
        BigDecimal priceMedian
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
            Integer sampleCount,
            Integer windowSampleCount,
            Integer excludedSampleCount,
            LocalDate sampleStartDate,
            LocalDate sampleEndDate
    ) {
        this(kind, metricCode, status, reasonCode, multipleLow, multipleMedian, multipleHigh,
                priceLow, priceHigh, sampleCount, windowSampleCount, excludedSampleCount,
                sampleStartDate, sampleEndDate, null, null, null);
    }

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
                priceLow, priceHigh, sampleCount, null, null, null, null, null, null, null);
    }

    public static ValuationReferenceDto unavailable(String kind, String metricCode, String status, String reasonCode) {
        return new ValuationReferenceDto(kind, metricCode, status, reasonCode,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ValuationReferenceDto unavailableWithSampleCoverage(
            String kind, String metricCode, String status, String reasonCode,
            Integer sampleCount, Integer windowSampleCount, Integer excludedSampleCount,
            LocalDate sampleStartDate, LocalDate sampleEndDate) {
        return new ValuationReferenceDto(kind, metricCode, status, reasonCode,
                null, null, null, null, null, sampleCount, windowSampleCount, excludedSampleCount,
                sampleStartDate, sampleEndDate, null, null, null);
    }
}
