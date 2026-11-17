package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.LocalDate;
import java.util.List;

public record PriceSeriesCoverageDto(LocalDate requestedFrom, LocalDate requestedTo, LocalDate actualFrom, LocalDate actualTo,
                                     int validCount, int expectedCount, List<LocalDate> gapDates, List<String> basisSegments) {
    public PriceSeriesCoverageDto {
        gapDates = gapDates == null ? List.of() : List.copyOf(gapDates);
        basisSegments = basisSegments == null ? List.of() : List.copyOf(basisSegments);
    }
}
