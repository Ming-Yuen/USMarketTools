package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record AnalysisReportRequest(@NotBlank String figi, LocalDate valuationDate, AnalysisHorizon horizon) {}
