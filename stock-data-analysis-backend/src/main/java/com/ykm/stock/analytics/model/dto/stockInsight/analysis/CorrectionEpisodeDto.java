package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.time.LocalDate;
public record CorrectionEpisodeDto(LocalDate peakDate, LocalDate startDate, LocalDate troughDate, LocalDate endDate, BigDecimal troughDrawdown, boolean completed) {}
