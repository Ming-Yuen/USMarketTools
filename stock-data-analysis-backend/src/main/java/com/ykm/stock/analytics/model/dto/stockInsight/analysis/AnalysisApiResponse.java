package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

public record AnalysisApiResponse<T>(boolean success, String message, T data, AnalysisApiError error) {
    public static <T> AnalysisApiResponse<T> ok(T data) {
        return new AnalysisApiResponse<>(true, "OK", data, null);
    }
    public static <T> AnalysisApiResponse<T> error(String message, AnalysisApiError error) {
        return new AnalysisApiResponse<>(false, message, null, error);
    }
}
