package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.util.List;
public record TradePlanResultDto(List<TradePlanDto> plans, BigDecimal currentPrice, java.time.LocalDate priceDataThrough,
 MetricResult<RiskRewardDto> currentPriceRR, List<AnalysisReasonDto> reasons, List<String> evidenceRefs, boolean contextEvaluated) {
 public TradePlanResultDto { plans=plans==null?List.of():List.copyOf(plans); reasons=reasons==null?List.of():List.copyOf(reasons); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
}
