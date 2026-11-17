package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.List;

public record MetricResult<T>(MetricStatus status, T value, List<AnalysisReasonDto> reasons, List<String> evidenceRefs, T lastKnown) {
    public MetricResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (status != MetricStatus.AVAILABLE && value != null) throw new IllegalArgumentException("non-AVAILABLE metric cannot expose value");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
    public static <T> MetricResult<T> available(T value) { return new MetricResult<>(MetricStatus.AVAILABLE, value, List.of(), List.of(), null); }
    public static <T> MetricResult<T> available(T value, List<String> evidenceRefs) { return new MetricResult<>(MetricStatus.AVAILABLE, value, List.of(), evidenceRefs, null); }
    public static <T> MetricResult<T> unavailable(MetricStatus status, AnalysisReasonDto reason) { return new MetricResult<>(status, null, List.of(reason), List.of(), null); }
    public static <T> MetricResult<T> unavailable(MetricStatus status, List<AnalysisReasonDto> reasons, List<String> evidenceRefs) { return new MetricResult<>(status, null, reasons, evidenceRefs, null); }
}
