package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.time.*; import java.util.List;
public record MarketSnapshotDto(MarketRegime regime, MetricResult<BigDecimal> fearGreedScore, MetricResult<BigDecimal> breadthScore, MetricResult<BigDecimal> vixProviderScore, LocalDate dataDate, Instant sourceUpdatedAt, List<AnalysisReasonDto> reasons, List<String> evidenceRefs) { public MarketSnapshotDto { reasons=reasons==null?List.of():List.copyOf(reasons); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); } }
