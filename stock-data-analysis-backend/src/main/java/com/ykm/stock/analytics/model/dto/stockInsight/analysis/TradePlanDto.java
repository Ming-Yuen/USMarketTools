package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.*;
import java.util.List;

public record TradePlanDto(String planId, AnalysisHorizon horizon, SetupType setupType, PriceRangeDto entryRange, EntryMethod entryMethod,
 String trigger, StopRuleDto stop, List<TargetDto> targets, MetricResult<RiskRewardDto> riskReward, TradePlanStatus status,
 List<AnalysisReasonDto> reasons, String sourceZoneId, int sourceBoundsVersion, AnalysisTimeframe structureTimeframe,
 Instant createdSignalAt, LocalDate validThroughSession, List<String> evidenceRefs, PositionSizingDto positionSizing) {
 public TradePlanDto {
  targets = targets == null ? List.of() : List.copyOf(targets);
  reasons = reasons == null ? List.of() : List.copyOf(reasons);
  evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
 }

 public TradePlanDto(String planId, AnalysisHorizon horizon, SetupType setupType, PriceRangeDto entryRange, EntryMethod entryMethod,
  String trigger, StopRuleDto stop, List<TargetDto> targets, MetricResult<RiskRewardDto> riskReward, TradePlanStatus status,
  List<AnalysisReasonDto> reasons, String sourceZoneId, int sourceBoundsVersion, AnalysisTimeframe structureTimeframe,
  Instant createdSignalAt, LocalDate validThroughSession, List<String> evidenceRefs) {
  this(planId, horizon, setupType, entryRange, entryMethod, trigger, stop, targets, riskReward, status, reasons,
   sourceZoneId, sourceBoundsVersion, structureTimeframe, createdSignalAt, validThroughSession, evidenceRefs, null);
 }

 public TradePlanDto withPositionSizing(PositionSizingDto sizing) {
  return new TradePlanDto(planId, horizon, setupType, entryRange, entryMethod, trigger, stop, targets, riskReward, status,
   reasons, sourceZoneId, sourceBoundsVersion, structureTimeframe, createdSignalAt, validThroughSession, evidenceRefs, sizing);
 }
}
