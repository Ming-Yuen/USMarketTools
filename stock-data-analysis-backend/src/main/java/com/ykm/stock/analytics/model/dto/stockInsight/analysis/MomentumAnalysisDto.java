package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MomentumAnalysisDto(Map<String, MetricResult<BigDecimal>> indicators,
                                  MetricResult<BigDecimal> primarySignal,
                                  List<AnalysisReasonDto> reasons,
                                  Map<String, IndicatorInterpretationDto> interpretations,
                                  List<IndicatorSeriesPointDto> series) {
    public MomentumAnalysisDto {
        indicators = indicators == null ? Map.of() : Map.copyOf(indicators);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        interpretations = interpretations == null ? Map.of() : Map.copyOf(interpretations);
        series = series == null ? List.of() : List.copyOf(series);
    }

    public MomentumAnalysisDto(Map<String, MetricResult<BigDecimal>> indicators,
                               MetricResult<BigDecimal> primarySignal,
                               List<AnalysisReasonDto> reasons,
                               Map<String, IndicatorInterpretationDto> interpretations) {
        this(indicators, primarySignal, reasons, interpretations, List.of());
    }

    public MomentumAnalysisDto(Map<String, MetricResult<BigDecimal>> indicators,
                               MetricResult<BigDecimal> primarySignal, List<AnalysisReasonDto> reasons) {
        this(indicators, primarySignal, reasons, Map.of(), List.of());
    }
}
