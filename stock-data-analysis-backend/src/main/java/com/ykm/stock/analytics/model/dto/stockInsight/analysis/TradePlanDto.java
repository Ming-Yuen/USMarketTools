package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.time.*; import java.util.List;
public record TradePlanDto(String planId, AnalysisHorizon horizon, SetupType setupType, PriceRangeDto entryRange, EntryMethod entryMethod,
 String trigger, StopRuleDto stop, List<TargetDto> targets, MetricResult<RiskRewardDto> riskReward, TradePlanStatus status,
 List<AnalysisReasonDto> reasons, String sourceZoneId, int sourceBoundsVersion, AnalysisTimeframe structureTimeframe,
 Instant createdSignalAt, LocalDate validThroughSession, List<String> evidenceRefs) {
 public TradePlanDto { targets=targets==null?List.of():List.copyOf(targets); reasons=reasons==null?List.of():List.copyOf(reasons); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
}
