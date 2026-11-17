package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.List;
import java.util.Map;

public record AnalysisReasonDto(String code, String component, String message, Map<String, Object> params, List<String> evidenceRefs) {
    public AnalysisReasonDto {
        params = params == null ? Map.of() : Map.copyOf(params);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
    public static AnalysisReasonDto of(String code, String component, String message) {
        return new AnalysisReasonDto(code, component, message, Map.of(), List.of());
    }
}
