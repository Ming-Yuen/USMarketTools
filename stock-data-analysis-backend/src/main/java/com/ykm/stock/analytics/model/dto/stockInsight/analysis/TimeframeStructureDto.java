package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.util.List;
public record TimeframeStructureDto(AnalysisTimeframe timeframe, List<NormalizedBarDto> bars, MetricResult<AtrPointDto> atr14,
 List<ConfirmedPivotDto> minorPivots, List<ConfirmedPivotDto> majorPivots, MetricResult<PriceRelation> highRelation,
 MetricResult<PriceRelation> lowRelation, MetricResult<StructureTrendDto> minorTrend, MetricResult<StructureTrendDto> majorTrend,
 List<StructureEventDto> structureEvents, List<AnalysisReasonDto> reasons) {
 public TimeframeStructureDto {
  bars=bars==null?List.of():List.copyOf(bars);
  minorPivots=minorPivots==null?List.of():List.copyOf(minorPivots);
  majorPivots=majorPivots==null?List.of():List.copyOf(majorPivots);
  structureEvents=structureEvents==null?List.of():List.copyOf(structureEvents);
  reasons=reasons==null?List.of():List.copyOf(reasons);
 }
}
