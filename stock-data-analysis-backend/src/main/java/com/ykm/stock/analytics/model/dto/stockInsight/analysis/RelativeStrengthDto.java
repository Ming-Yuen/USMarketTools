package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record RelativeStrengthDto(String benchmark,
                                  MetricResult<BigDecimal> excessReturn20,
                                  MetricResult<BigDecimal> excessReturn60,
                                  MetricResult<BigDecimal> excessReturn120,
                                  Map<Integer, MetricResult<RelativeStrengthWindowDto>> windows,
                                  MetricResult<BigDecimal> primarySignal,
                                  List<AnalysisReasonDto> reasons) {
    public RelativeStrengthDto {
        windows = windows == null ? Map.of() : Map.copyOf(windows);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
    public RelativeStrengthDto(String benchmark, MetricResult<BigDecimal> excessReturn20, MetricResult<BigDecimal> excessReturn60) {
        this(benchmark, excessReturn20, excessReturn60,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("RELATIVE_STRENGTH_120_UNAVAILABLE","RELATIVE_STRENGTH","120日相對強弱未提供。")),
                Map.of(),
                excessReturn60 != null && excessReturn60.status() == MetricStatus.AVAILABLE
                        ? MetricResult.available(clamp(excessReturn60.value()))
                        : MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("RELATIVE_STRENGTH_INSUFFICIENT_DATA","RELATIVE_STRENGTH","相對強弱資料不足。")),
                List.of());
    }
    private static BigDecimal clamp(BigDecimal difference) {
        if (difference == null) return BigDecimal.ZERO;
        BigDecimal score = difference.divide(new BigDecimal("0.05"), java.math.MathContext.DECIMAL128);
        return score.max(BigDecimal.ONE.negate()).min(BigDecimal.ONE);
    }
}
