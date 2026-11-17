package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.List;

public record NormalizedPriceSeriesDto(String figi, String timeframe, List<NormalizedBarDto> bars,
                                       PriceBasisDescriptor priceBasis, PriceSeriesCoverageDto coverage,
                                       MetricStatus quality, List<String> evidenceRefs, String inputHash) {
    public NormalizedPriceSeriesDto {
        bars = bars == null ? List.of() : List.copyOf(bars);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
}
