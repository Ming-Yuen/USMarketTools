package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record MomentumAnalysisDto(Map<String, MetricResult<BigDecimal>> indicators,
                                  MetricResult<BigDecimal> primarySignal,
                                  List<AnalysisReasonDto> reasons,
                                  Map<String, IndicatorInterpretationDto> interpretations) {
    public MomentumAnalysisDto {
        indicators = indicators == null ? Map.of() : Map.copyOf(indicators);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        interpretations = interpretations == null ? Map.of() : Map.copyOf(interpretations);
    }

    public MomentumAnalysisDto(Map<String, MetricResult<BigDecimal>> indicators,
                               MetricResult<BigDecimal> primarySignal, List<AnalysisReasonDto> reasons) {
        this(indicators, primarySignal, reasons, Map.of());
    }
}
