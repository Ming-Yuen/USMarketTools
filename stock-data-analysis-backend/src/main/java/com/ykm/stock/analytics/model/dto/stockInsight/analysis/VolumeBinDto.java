package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal;
public record VolumeBinDto(int index, BigDecimal lower, BigDecimal upper, BigDecimal volume) {}
