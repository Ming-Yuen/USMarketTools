package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.*;
import java.util.List;

public record SourceFreshnessDto(String source, LocalDate dataDate, Instant availableAt, Instant observedAt,
                                 MetricStatus status, List<AnalysisReasonDto> reasons, List<String> evidenceRefs) {
    public SourceFreshnessDto {
        status = status == null ? MetricStatus.UNVERIFIED : status;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
