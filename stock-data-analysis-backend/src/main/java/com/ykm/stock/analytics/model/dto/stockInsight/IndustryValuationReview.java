package com.ykm.stock.analytics.model.dto.stockInsight;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 分類與正式估值設定分開；草稿只作 API 回應，不另建持久化副本。 */
public record IndustryValuationReview(String industryCode, String industry, Current current,
                                      Map<String, List<String>> supportedMetrics, boolean canPropose) {
    public record Overview(List<OverviewItem> items, Map<String, List<String>> supportedMetrics) {}
    public record OverviewItem(String industryCode, String industryName, String modelCode, String primaryMetric,
                               BigDecimal assumedMultipleLow, BigDecimal assumedMultipleHigh, Instant effectiveAt,
                               String sourceUrl, boolean configured, boolean canPropose, long activeStockCount) {}
    public record Source(String url, String title, LocalDate publishedDate, String evidence) {}
    public record Current(Instant effectiveAt, String modelCode, String primaryMetric,
                          BigDecimal assumedMultipleLow, BigDecimal assumedMultipleHigh,
                          String rationale, String sourceUrl) {}
    public record Draft(String modelCode, String primaryMetric, BigDecimal assumedMultipleLow,
                        BigDecimal assumedMultipleHigh, LocalDate asOf, String rationale, List<Source> sources) {}
    public record Update(Instant baseEffectiveAt, String modelCode, String primaryMetric,
                         BigDecimal assumedMultipleLow, BigDecimal assumedMultipleHigh,
                         String rationale, String sourceUrl) {}
}
