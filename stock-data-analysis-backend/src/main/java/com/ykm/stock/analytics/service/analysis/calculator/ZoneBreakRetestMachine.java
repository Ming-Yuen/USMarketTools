package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;

@Component
// 用途：以單一逐 bar 時間流程處理區域測試、突破、回測及角色互換，避免後見資料污染。
public class ZoneBreakRetestMachine {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BREAK_BUFFER_ATR = new BigDecimal("0.25");
    private static final BigDecimal RETEST_CONFIRM_ATR = new BigDecimal("0.50");
    private static final int RETEST_WINDOW_BARS = 20;
    private static final int RETEST_CONFIRM_BARS = 3;

    public void evaluate(AtrZoneClusterer.ZoneCandidate zone, List<NormalizedBarDto> bars) {
        if (zone == null || bars == null || bars.isEmpty()) return;

        int pendingDirection = 0;
        int pendingIndex = -1;
        BigDecimal pendingThreshold = null;
        int breakDirection = 0;
        int breakIndex = -1;
        int retestStartIndex = -1;
        String retestEpisodeId = null;

        boolean testEpisodeOpen = false;
        int testBarsSinceEnd = Integer.MAX_VALUE;
        boolean departed = false;
        int rejectionDeadline = -1;
        boolean penetrationRecorded = false;
        String testEpisodeId = null;

        for (int i = 0; i < bars.size(); i++) {
            NormalizedBarDto bar = bars.get(i);
            if (bar.availableAt() == null || !bar.availableAt().isAfter(zone.confirmedAt)) continue;

            if (zone.state == ZoneState.BROKEN) {
                if (breakIndex < 0) breakIndex = i - 1;
                int retestWindowEnd = breakIndex + RETEST_WINDOW_BARS;

                if (retestStartIndex < 0) {
                    if (i > retestWindowEnd) {
                        expireRetest(zone, bars.get(Math.min(bars.size() - 1, retestWindowEnd)));
                        return;
                    }
                    NormalizedBarDto previous = i > 0 ? bars.get(i - 1) : null;
                    boolean fromCorrectSide = previous != null && (breakDirection > 0
                            ? previous.close().compareTo(zone.upper) > 0
                            : previous.close().compareTo(zone.lower) < 0);
                    boolean intersects = bar.high().compareTo(zone.lower) >= 0 && bar.low().compareTo(zone.upper) <= 0;
                    if (!fromCorrectSide || !intersects) continue;
                    retestStartIndex = i;
                    zone.retestState = RetestState.PENDING;
                    retestEpisodeId = zone.id + ":retest:" + zone.roleVersion + ":" + bar.sessionDate();
                    zone.recordEvent("RETEST_STARTED", bar.availableAt(), bar.availableAt(), retestEpisodeId, Map.of(), zone.seedIds);
                }

                BigDecimal failBuffer = zone.anchorAtr.multiply(BREAK_BUFFER_ATR, MC);
                boolean failed = breakDirection > 0
                        ? bar.close().compareTo(zone.lower.subtract(failBuffer, MC)) < 0
                        : bar.close().compareTo(zone.upper.add(failBuffer, MC)) > 0;
                if (failed) {
                    zone.retestState = RetestState.FAILED;
                    zone.recordEvent("RETEST_FAILED", bars.get(retestStartIndex).availableAt(), bar.availableAt(),
                            retestEpisodeId, Map.of("close", bar.close()), zone.seedIds);
                    return;
                }

                BigDecimal confirm = zone.anchorAtr.multiply(RETEST_CONFIRM_ATR, MC);
                boolean succeeded = breakDirection > 0
                        ? bar.close().compareTo(zone.upper.add(confirm, MC)) > 0
                        : bar.close().compareTo(zone.lower.subtract(confirm, MC)) < 0;
                if (succeeded) {
                    zone.retestState = RetestState.SUCCEEDED;
                    zone.state = ZoneState.ACTIVE;
                    zone.role = breakDirection > 0 ? ZoneRole.SUPPORT : ZoneRole.RESISTANCE;
                    zone.roleVersion++;
                    zone.roleConfirmedAt = bar.availableAt();
                    zone.roleConfirmationPrice = bar.close();
                    zone.recordEvent(breakDirection > 0 ? "RESISTANCE_TO_SUPPORT" : "SUPPORT_TO_RESISTANCE",
                            bars.get(retestStartIndex).availableAt(), bar.availableAt(), retestEpisodeId,
                            Map.of("close", bar.close()), zone.seedIds);

                    // Continue forward with the new role; a later break must still be observable.
                    pendingDirection = 0;
                    pendingIndex = -1;
                    pendingThreshold = null;
                    breakDirection = 0;
                    breakIndex = -1;
                    retestStartIndex = -1;
                    retestEpisodeId = null;
                    zone.breakConfirmedAt = null;
                    testEpisodeOpen = false;
                    testBarsSinceEnd = Integer.MAX_VALUE;
                    departed = false;
                    rejectionDeadline = -1;
                    penetrationRecorded = false;
                    testEpisodeId = null;
                    continue;
                }

                if (i - retestStartIndex + 1 >= RETEST_CONFIRM_BARS) {
                    zone.retestState = RetestState.FAILED;
                    zone.recordEvent("RETEST_FAILED", bars.get(retestStartIndex).availableAt(), bar.availableAt(),
                            retestEpisodeId, Map.of("reason", "CONFIRMATION_WINDOW_EXHAUSTED"), zone.seedIds);
                    return;
                }
                continue;
            }

            BigDecimal breakBuffer = zone.anchorAtr.multiply(BREAK_BUFFER_ATR, MC);
            int direction = 0;
            BigDecimal candidateThreshold = null;
            if (zone.role == ZoneRole.RESISTANCE && bar.close().compareTo(zone.upper.add(breakBuffer, MC)) > 0) {
                direction = 1;
                candidateThreshold = zone.upper.add(breakBuffer, MC);
            } else if (zone.role == ZoneRole.SUPPORT && bar.close().compareTo(zone.lower.subtract(breakBuffer, MC)) < 0) {
                direction = -1;
                candidateThreshold = zone.lower.subtract(breakBuffer, MC);
            }

            if (direction != 0 && pendingDirection == direction && pendingIndex == i - 1
                    && pendingThreshold != null
                    && (direction > 0 ? bar.close().compareTo(pendingThreshold) > 0
                    : bar.close().compareTo(pendingThreshold) < 0)) {
                zone.state = ZoneState.BROKEN;
                zone.retestState = RetestState.PENDING;
                zone.breakConfirmedAt = bar.availableAt();
                breakDirection = direction;
                breakIndex = i;
                testEpisodeOpen = false;
                zone.recordEvent(direction > 0 ? "BREAKOUT" : "BREAKDOWN",
                        bars.get(pendingIndex).availableAt(), bar.availableAt(), null,
                        Map.of("threshold", pendingThreshold), zone.seedIds);
                continue;
            }
            if (direction == 0) {
                pendingDirection = 0;
                pendingIndex = -1;
                pendingThreshold = null;
                zone.state = ZoneState.ACTIVE;
            } else {
                pendingDirection = direction;
                pendingIndex = i;
                pendingThreshold = candidateThreshold;
                zone.state = ZoneState.BREAK_PENDING;
            }

            // Test/rejection episodes use only the role and bounds that exist at this bar.
            boolean endedThisBar = false;
            boolean touches = bar.high().compareTo(zone.lower) >= 0 && bar.low().compareTo(zone.upper) <= 0;
            if (!testEpisodeOpen) {
                if (departed) testBarsSinceEnd++;
                if (touches && (zone.testCount == 0 || departed && testBarsSinceEnd >= 3)) {
                    testEpisodeOpen = true;
                    zone.testCount++;
                    testEpisodeId = zone.id + ":episode:" + zone.roleVersion + ":" + zone.testCount;
                    penetrationRecorded = false;
                    zone.lastTestAt = bar.availableAt();
                    rejectionDeadline = i + 2;
                    departed = false;
                    zone.recordEvent("TEST_STARTED", bar.availableAt(), bar.availableAt(), testEpisodeId,
                            Map.of("role", zone.role.name(), "lower", zone.lower, "upper", zone.upper), zone.seedIds);
                }
            } else if (touches) {
                zone.lastTestAt = bar.availableAt();
                boolean penetration = zone.role == ZoneRole.SUPPORT
                        ? bar.low().compareTo(zone.lower) < 0 && bar.close().compareTo(zone.lower) >= 0
                        : bar.high().compareTo(zone.upper) > 0 && bar.close().compareTo(zone.upper) <= 0;
                if (penetration && !penetrationRecorded) {
                    zone.penetrationCount++;
                    penetrationRecorded = true;
                    zone.recordEvent("PENETRATION_RECOVERED", bar.availableAt(), bar.availableAt(), testEpisodeId,
                            Map.of("close", bar.close()), zone.seedIds);
                }
            }

            if (testEpisodeOpen && i <= rejectionDeadline) {
                BigDecimal rejectDistance = zone.anchorAtr.multiply(RETEST_CONFIRM_ATR, MC);
                boolean rejection = zone.role == ZoneRole.SUPPORT
                        ? bar.close().compareTo(zone.upper.add(rejectDistance, MC)) > 0
                        : bar.close().compareTo(zone.lower.subtract(rejectDistance, MC)) < 0;
                if (rejection) {
                    zone.rejectionCount++;
                    zone.recordEvent("REJECTION", zone.lastTestAt, bar.availableAt(), testEpisodeId,
                            Map.of("close", bar.close()), zone.seedIds);
                    testEpisodeOpen = false;
                    endedThisBar = true;
                    testBarsSinceEnd = 0;
                }
            } else if (testEpisodeOpen && i > rejectionDeadline) {
                zone.recordEvent("UNRESOLVED_PRESSURE", zone.lastTestAt, bar.availableAt(), testEpisodeId, Map.of(), zone.seedIds);
                testEpisodeOpen = false;
                endedThisBar = true;
                testBarsSinceEnd = 0;
            }

            if (!testEpisodeOpen && zone.testCount > 0 && !endedThisBar) {
                BigDecimal departure = zone.anchorAtr;
                boolean farAway = zone.role == ZoneRole.SUPPORT
                        ? bar.close().compareTo(zone.upper.add(departure, MC)) > 0
                        : bar.close().compareTo(zone.lower.subtract(departure, MC)) < 0;
                if (farAway) departed = true;
            }
        }

        // Incomplete future observation is not a failure. Keep PENDING until a full window exists.
        if (zone.state == ZoneState.BROKEN && breakIndex >= 0) {
            int observedAfterBreak = bars.size() - 1 - breakIndex;
            if (retestStartIndex < 0 && observedAfterBreak >= RETEST_WINDOW_BARS) {
                expireRetest(zone, bars.get(Math.min(bars.size() - 1, breakIndex + RETEST_WINDOW_BARS)));
            } else {
                zone.retestState = RetestState.PENDING;
            }
        }
    }

    private void expireRetest(AtrZoneClusterer.ZoneCandidate zone, NormalizedBarDto lastObserved) {
        zone.retestState = RetestState.EXPIRED;
        zone.state = ZoneState.EXPIRED;
        zone.recordEvent("RETEST_EXPIRED", zone.breakConfirmedAt, lastObserved.availableAt(), null, Map.of(), zone.seedIds);
    }
}
