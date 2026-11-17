package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.Map;

public record AnalysisApiError(String code, Map<String, String> fieldErrors, String requestId) {
    public AnalysisApiError {
        fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }
}
