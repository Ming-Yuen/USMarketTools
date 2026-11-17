package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.util.List;
public record TargetDto(BigDecimal price, BigDecimal weight, TargetAction action, String sourceZoneId, String reason, List<String> evidenceRefs) {
 public TargetDto { evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
}
