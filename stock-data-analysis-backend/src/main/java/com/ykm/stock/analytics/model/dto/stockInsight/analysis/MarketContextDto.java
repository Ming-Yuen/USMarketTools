package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.List;
import java.util.Map;

public record MarketContextDto(MomentumVolumeDto momentum, RelativeStrengthDto relativeStrength, MetricResult<MarketSnapshotDto> market,
                               List<EventRiskDto> events, PlanConstraintDto tradeConstraint, List<AnalysisReasonDto> reasons, List<String> evidenceRefs,
                               MomentumAnalysisDto momentumDetail, VolumeAnalysisDto volume, DrawdownAnalysisDto drawdown,
                               TreasuryContextDto treasury, Map<String, SourceFreshnessDto> sourceFreshness) {
    public MarketContextDto {
        events = events == null ? List.of() : List.copyOf(events);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        sourceFreshness = sourceFreshness == null ? Map.of() : Map.copyOf(sourceFreshness);
    }
    public MarketContextDto(MomentumVolumeDto momentum, RelativeStrengthDto relativeStrength, MetricResult<MarketSnapshotDto> market,
                            List<EventRiskDto> events, PlanConstraintDto tradeConstraint, List<AnalysisReasonDto> reasons, List<String> evidenceRefs,
                            MomentumAnalysisDto momentumDetail, VolumeAnalysisDto volume, DrawdownAnalysisDto drawdown) {
        this(momentum, relativeStrength, market, events, tradeConstraint, reasons, evidenceRefs,
                momentumDetail, volume, drawdown, null, Map.of());
    }
    public MarketContextDto(MomentumVolumeDto momentum, RelativeStrengthDto relativeStrength, MetricResult<MarketSnapshotDto> market,
                            List<EventRiskDto> events, PlanConstraintDto tradeConstraint, List<AnalysisReasonDto> reasons, List<String> evidenceRefs) {
        this(momentum, relativeStrength, market, events, tradeConstraint, reasons, evidenceRefs,
                null, null, null, null, Map.of());
    }
}
