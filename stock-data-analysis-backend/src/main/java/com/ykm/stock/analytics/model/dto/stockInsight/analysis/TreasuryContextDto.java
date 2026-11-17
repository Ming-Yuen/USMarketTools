package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.util.List;

public record TreasuryContextDto(MetricResult<BigDecimal> tenYear, MetricResult<BigDecimal> twoYear,
                                 MetricResult<BigDecimal> tenYearChange20, MetricResult<BigDecimal> curve10yMinus2y,
                                 List<AnalysisReasonDto> reasons, List<String> evidenceRefs) {
    public TreasuryContextDto {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
