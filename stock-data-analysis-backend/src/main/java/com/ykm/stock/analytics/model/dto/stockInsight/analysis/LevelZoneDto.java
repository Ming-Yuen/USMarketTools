package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.time.Instant; import java.util.List;
public record LevelZoneDto(String id, AnalysisTimeframe timeframe, BigDecimal lower, BigDecimal upper, ZoneRole role,
 BigDecimal strength, Instant confirmedAt, int testCount, Instant lastTestAt, ZoneState state, BigDecimal center, BigDecimal anchorAtr,
 int boundsVersion, ZoneStrengthLabel strengthLabel, BigDecimal effectiveStrength, int roleVersion, Instant roleConfirmedAt,
 BigDecimal roleConfirmationPrice, List<String> seedIds, List<String> dependenceKeys, int rejectionCount, int penetrationCount, int ageBars,
 BigDecimal distanceLowerPercent, BigDecimal distanceUpperPercent, RetestState retestState, boolean majorLevel, List<String> evidenceRefs) {
 public LevelZoneDto { seedIds=seedIds==null?List.of():List.copyOf(seedIds); dependenceKeys=dependenceKeys==null?List.of():List.copyOf(dependenceKeys); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
}
