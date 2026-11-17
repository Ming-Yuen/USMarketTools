package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.time.LocalDate;
public record AtrPointDto(LocalDate sessionDate, int period, BigDecimal atr, BigDecimal atrPercent, LocalDate seedDate, int trCount, int warmupCount) {}
