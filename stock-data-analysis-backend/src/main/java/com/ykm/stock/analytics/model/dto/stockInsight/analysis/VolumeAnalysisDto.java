package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.util.List;
public record VolumeAnalysisDto(MetricResult<BigDecimal> rvol20, MetricResult<BigDecimal> obv, MetricResult<BigDecimal> netVolumeDays20, MetricResult<BigDecimal> primarySignal, MetricResult<VolumeProfileDto> profile252, MetricResult<BigDecimal> anchoredVwap, List<AnalysisReasonDto> reasons, List<String> evidenceRefs) { public VolumeAnalysisDto { reasons=reasons==null?List.of():List.copyOf(reasons); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); } }
