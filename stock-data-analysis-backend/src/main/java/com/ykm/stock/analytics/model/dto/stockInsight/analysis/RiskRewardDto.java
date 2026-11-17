package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal;
public record RiskRewardDto(BigDecimal entry, BigDecimal stop, BigDecimal grossRisk, BigDecimal grossReward, BigDecimal grossRR,
 BigDecimal netRisk, BigDecimal netReward, BigDecimal netRR, BigDecimal potentialUpsidePercent, BigDecimal potentialDownsidePercent) {}
