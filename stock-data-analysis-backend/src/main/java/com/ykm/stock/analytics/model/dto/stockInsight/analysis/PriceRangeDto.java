package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal;
public record PriceRangeDto(BigDecimal lower, BigDecimal upper, String basisId) {}
