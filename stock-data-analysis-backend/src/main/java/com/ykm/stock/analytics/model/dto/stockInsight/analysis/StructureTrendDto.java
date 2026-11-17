package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.time.Instant; import java.util.List;
public record StructureTrendDto(TrendState state, Instant asOf, PriceRelation highRelation, PriceRelation lowRelation, List<String> pivotIds, Instant lastTransitionAt) {
 public StructureTrendDto { pivotIds=pivotIds==null?List.of():List.copyOf(pivotIds); }
}
