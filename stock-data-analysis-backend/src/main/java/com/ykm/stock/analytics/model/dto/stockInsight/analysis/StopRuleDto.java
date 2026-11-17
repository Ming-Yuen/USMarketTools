package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.time.Instant; import java.util.List;
public record StopRuleDto(BigDecimal protectivePrice, BigDecimal closeThreshold, int requiredCloses, AnalysisTimeframe evaluationTimeframe,
 BigDecimal bufferAtr, BigDecimal atrValue, AnalysisTimeframe atrTimeframe, Instant confirmedAt, List<String> anchorEvidenceRefs) {
 public StopRuleDto { anchorEvidenceRefs=anchorEvidenceRefs==null?List.of():List.copyOf(anchorEvidenceRefs); }
}
