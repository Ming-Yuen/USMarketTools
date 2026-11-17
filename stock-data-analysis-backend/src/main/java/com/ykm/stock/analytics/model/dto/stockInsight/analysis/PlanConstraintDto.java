package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.util.List;
public record PlanConstraintDto(EntryPermission entryPermission, List<String> reasonCodes, List<String> evidenceRefs) {
 public PlanConstraintDto { entryPermission=entryPermission==null?EntryPermission.UNKNOWN:entryPermission; reasonCodes=reasonCodes==null?List.of():List.copyOf(reasonCodes); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
 public static PlanConstraintDto unevaluated(){ return new PlanConstraintDto(EntryPermission.UNKNOWN,List.of("CONTEXT_NOT_EVALUATED"),List.of()); }
}
