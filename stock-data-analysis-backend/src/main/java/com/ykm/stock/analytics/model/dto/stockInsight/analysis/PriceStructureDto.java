package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.time.LocalDate; import java.util.List;
public record PriceStructureDto(String figi, String inputHash, LocalDate priceDataThrough, List<TimeframeStructureDto> timeframes, List<String> evidenceRefs, List<AnalysisReasonDto> reasons) {
 public PriceStructureDto {
  timeframes=timeframes==null?List.of():List.copyOf(timeframes);
  evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs);
  reasons=reasons==null?List.of():List.copyOf(reasons);
 }
}
