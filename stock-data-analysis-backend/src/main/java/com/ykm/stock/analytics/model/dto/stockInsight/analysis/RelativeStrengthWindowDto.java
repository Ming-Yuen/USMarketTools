package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal;
public record RelativeStrengthWindowDto(int sessions, BigDecimal stockReturn, BigDecimal benchmarkReturn, BigDecimal returnDifference, BigDecimal ratioChange) {}
