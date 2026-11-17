package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.time.*; import java.util.List;
public record ConfirmedPivotDto(String id, PivotKind kind, PivotScale scale, AnalysisTimeframe timeframe, BigDecimal price,
 LocalDate occurrenceSession, Instant occurrenceTime, Instant confirmationTime, Instant observedAt,
 BigDecimal atrAtOccurrence, BigDecimal atrAtConfirmation, List<String> evidenceRefs, String dependenceKey) {
 public ConfirmedPivotDto { evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
}
