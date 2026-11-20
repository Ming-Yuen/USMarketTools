package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

@Component
// 用途：以單一逐 bar 時間流程處理區域測試、突破、回測及角色互換，避免後見資料污染。
public class ZoneBreakRetestMachine {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BREAK_BUFFER_ATR = new BigDecimal("0.25");
    private static final BigDecimal RETEST_CONFIRM_ATR = new BigDecimal("0.50");
    private static final BigDecimal MINOR_BREACH_PCT = new BigDecimal("0.02");
    private static final BigDecimal RVOL_12 = new BigDecimal("1.2");
    private static final BigDecimal RVOL_15 = new BigDecimal("1.5");
    private static final BigDecimal SOLID_BODY_MIN = new BigDecimal("0.50");
    private static final BigDecimal NEAR_LOW_MAX = new BigDecimal("0.25");
    private static final BigDecimal CLOSE_POS_60 = new BigDecimal("0.60");
    private static final BigDecimal INTRADAY_SHADOW_MIN = new BigDecimal("0.45");
    private static final BigDecimal CLOSE_BELOW_SHADOW_MIN = new BigDecimal("0.40");
    private static final BigDecimal SPY_SELLOFF_THRESHOLD = new BigDecimal("-0.012");
    private static final BigDecimal MAX_MARKET_DRAG_UNDERPERF = new BigDecimal("-0.015");
    private static final BigDecimal SPY_FLAT_THRESHOLD = new BigDecimal("-0.005");
    private static final BigDecimal INDEPENDENT_WEAKNESS_EXCESS = new BigDecimal("-0.020");
    private static final int RETEST_WINDOW_BARS = 20;
    private static final int RETEST_CONFIRM_BARS = 3;
    private static final int SPRING_RECLAIM_MAX_BARS = 3;
    private static final int SPRING_VALID_SESSIONS = 5;
    private static final int MAX_BREACH_LOOKBACK_BARS = 5;
    private static final int MAX_SPRING_SCAN_BARS = 8;

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

        BigDecimal breakBuffer = zone.anchorAtr.multiply(BREAK_BUFFER_ATR, MC);
        BigDecimal upperBreakThreshold = zone.upper.add(breakBuffer, MC);
        BigDecimal lowerBreakThreshold = zone.lower.subtract(breakBuffer, MC);
        BigDecimal confirmDistance = zone.anchorAtr.multiply(RETEST_CONFIRM_ATR, MC);
        BigDecimal upperConfirmThreshold = zone.upper.add(confirmDistance, MC);
        BigDecimal lowerConfirmThreshold = zone.lower.subtract(confirmDistance, MC);
        BigDecimal upperDepartureThreshold = zone.upper.add(zone.anchorAtr, MC);
        BigDecimal lowerDepartureThreshold = zone.lower.subtract(zone.anchorAtr, MC);

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

                boolean failed = breakDirection > 0
                        ? bar.close().compareTo(lowerBreakThreshold) < 0
                        : bar.close().compareTo(upperBreakThreshold) > 0;
                if (failed) {
                    zone.retestState = RetestState.FAILED;
                    zone.recordEvent("RETEST_FAILED", bars.get(retestStartIndex).availableAt(), bar.availableAt(),
                            retestEpisodeId, Map.of("close", bar.close()), zone.seedIds);
                    return;
                }

                boolean succeeded = breakDirection > 0
                        ? bar.close().compareTo(upperConfirmThreshold) > 0
                        : bar.close().compareTo(lowerConfirmThreshold) < 0;
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

            int direction = 0;
            BigDecimal candidateThreshold = null;
            if (zone.role == ZoneRole.RESISTANCE && bar.close().compareTo(upperBreakThreshold) > 0) {
                direction = 1;
                candidateThreshold = upperBreakThreshold;
            } else if (zone.role == ZoneRole.SUPPORT && bar.close().compareTo(lowerBreakThreshold) < 0) {
                direction = -1;
                candidateThreshold = lowerBreakThreshold;
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
                boolean rejection = zone.role == ZoneRole.SUPPORT
                        ? bar.close().compareTo(upperConfirmThreshold) > 0
                        : bar.close().compareTo(lowerConfirmThreshold) < 0;
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
                boolean farAway = zone.role == ZoneRole.SUPPORT
                        ? bar.close().compareTo(upperDepartureThreshold) > 0
                        : bar.close().compareTo(lowerDepartureThreshold) < 0;
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

    /**
     * 依日線 K 線形態、RVOL20 與同日 SPY 相對強弱，針對特定已確認支撐區輸出破位真假診斷。
     * 嚴格防穿越：只診斷 availableAt 晚於該區 roleConfirmedAt 的日線，且按 zoneId + boundsVersion + roleVersion 綁定。
     */
    public BreakoutDiagnosisDto diagnoseSupport(
            AtrZoneClusterer.ZoneCandidate zone,
            List<NormalizedBarDto> dailyBars,
            VolumeAnalysisCalculator volumeCalculator,
            Map<LocalDate, BigDecimal> spyDailyReturns,
            UnaryOperator<LocalDate> nextTradingDay) {
        if (zone == null || zone.role != ZoneRole.SUPPORT || zone.state == ZoneState.EXPIRED
                || zone.lower == null || zone.upper == null || zone.lower.signum() <= 0
                || dailyBars == null || dailyBars.isEmpty()) {
            return null;
        }
        int lastIdx = dailyBars.size() - 1;
        NormalizedBarDto lastBar = dailyBars.get(lastIdx);
        if (!isObservableAfterRoleConfirm(lastBar, zone)) {
            return null;
        }

        // 1) 優先檢查「從上方跌破後 1–3 日內放量首次收復」的 SPRING_RECLAIMED（嚴格依 reclaimDate + 5 交易日凍結有效期，不被舊 breakConfirmedAt 提前截斷）
        if (lastBar.close().compareTo(zone.lower) >= 0) {
            BreakoutDiagnosisDto spring = detectSpringReclaimed(zone, dailyBars, volumeCalculator, spyDailyReturns, nextTradingDay, lastIdx);
            if (spring != null) {
                return spring;
            }
        }

        // 已破位且確認時間超過 5 個交易日前的陳舊失效區不再重複觸發近期破位診斷
        if (zone.state == ZoneState.BROKEN && zone.breakConfirmedAt != null) {
            long barsAfterBreak = 0;
            for (int i = lastIdx; i >= 0; i--) {
                if (dailyBars.get(i).availableAt() != null && dailyBars.get(i).availableAt().isAfter(zone.breakConfirmedAt)) {
                    barsAfterBreak++;
                } else {
                    break;
                }
            }
            if (barsAfterBreak > MAX_BREACH_LOOKBACK_BARS) {
                return null;
            }
        }

        // 2) 若當日盤中跌穿支撐下緣但收盤拉回區內，檢核是否屬洗盤下影線／縮量／大盤拖累
        if (lastBar.close().compareTo(zone.lower) >= 0) {
            if (lastBar.low().compareTo(zone.lower) < 0) {
                return diagnoseIntradayPokeRecovered(zone, dailyBars, volumeCalculator, spyDailyReturns, lastIdx);
            }
            return null;
        }

        // 3) 最新收盤仍低於支撐下緣：定位本輪連續收於下緣以下的起點
        int runStart = lastIdx;
        while (runStart > 0) {
            NormalizedBarDto prev = dailyBars.get(runStart - 1);
            if (!isObservableAfterRoleConfirm(prev, zone) || prev.close().compareTo(zone.lower) >= 0) {
                break;
            }
            runStart--;
            if (lastIdx - runStart + 1 > MAX_BREACH_LOOKBACK_BARS + 1) {
                return null;
            }
        }
        int consecutiveBelow = lastIdx - runStart + 1;
        if (consecutiveBelow > MAX_BREACH_LOOKBACK_BARS) {
            return null;
        }
        // 必須確認是由支撐上方或區間內跌穿，排除開盤起即長期位於下方的歷史雜訊
        if (runStart > 0 && dailyBars.get(runStart - 1).close().compareTo(zone.lower) < 0) {
            return null;
        }
        if (runStart == 0 && dailyBars.get(0).high().compareTo(zone.lower) < 0) {
            return null;
        }

        NormalizedBarDto breachBar = dailyBars.get(runStart);
        BarGeometry breachGeom = geometry(breachBar);
        BarGeometry lastGeom = geometry(lastBar);
        List<AnalysisReasonDto> diagReasons = new ArrayList<>();
        BigDecimal rvolBreach = extractRvol(volumeCalculator, dailyBars, runStart, diagReasons);
        BigDecimal rvolLatest = runStart == lastIdx ? rvolBreach : extractRvol(volumeCalculator, dailyBars, lastIdx, diagReasons);
        BigDecimal stockRet = dailyReturn(dailyBars, runStart);
        BigDecimal spyRet = spyDailyReturns != null ? spyDailyReturns.get(breachBar.sessionDate()) : null;
        BigDecimal excessRet = stockRet != null && spyRet != null ? stockRet.subtract(spyRet, MC) : null;

        boolean marketDrag = spyRet != null && excessRet != null
                && spyRet.compareTo(SPY_SELLOFF_THRESHOLD) <= 0
                && excessRet.compareTo(MAX_MARKET_DRAG_UNDERPERF) >= 0;
        boolean independentWeakness = spyRet != null && excessRet != null
                && spyRet.compareTo(SPY_FLAT_THRESHOLD) >= 0
                && excessRet.compareTo(INDEPENDENT_WEAKNESS_EXCESS) <= 0;
        boolean solidBearishBreach = breachBar.close().compareTo(breachBar.open()) < 0
                && breachGeom.bodyRatio() != null && breachGeom.bodyRatio().compareTo(SOLID_BODY_MIN) >= 0
                && breachGeom.closePosition() != null && breachGeom.closePosition().compareTo(NEAR_LOW_MAX) <= 0;
        boolean heavyVolumeBreach = rvolBreach != null && rvolBreach.compareTo(RVOL_15) >= 0;
        boolean lowVolumeBreach = rvolBreach != null && rvolBreach.compareTo(BigDecimal.ONE) < 0;
        boolean longLowerShadowLatest = lastGeom.lowerShadowRatio() != null
                && lastGeom.lowerShadowRatio().compareTo(CLOSE_BELOW_SHADOW_MIN) >= 0;
        boolean bouncedIntoZone = consecutiveBelow >= 2 && hasIntradayBounceIntoZone(dailyBars, runStart + 1, lastIdx, zone.lower);
        boolean weakReboundLatest = (rvolLatest != null && rvolLatest.compareTo(BigDecimal.ONE) < 0)
                || lastBar.close().compareTo(lastBar.open()) < 0;

        List<String> evidence = new ArrayList<>();
        if (solidBearishBreach) evidence.add("SOLID_BEARISH_CLOSE_NEAR_LOW");
        if (heavyVolumeBreach) evidence.add("HIGH_VOLUME_BREAKDOWN");
        if (independentWeakness) evidence.add("INDEPENDENT_WEAKNESS_VS_SPY");
        if (lowVolumeBreach) evidence.add("LOW_VOLUME_BREACH");
        if (marketDrag) evidence.add("MARKET_WIDE_SELLOFF_DRAG");
        if (longLowerShadowLatest) evidence.add("LONG_LOWER_SHADOW");
        if (consecutiveBelow >= 2) {
            evidence.add(bouncedIntoZone ? "FAILED_RECLAIM_BOUNCE" : "PERSISTENT_BELOW_SUPPORT");
            if (rvolLatest != null && rvolLatest.compareTo(BigDecimal.ONE) < 0) {
                evidence.add("WEAK_REBOUND_VOLUME");
            }
        }

        BreakoutDiagnosis diagnosis;
        if ((solidBearishBreach && heavyVolumeBreach)
                || (heavyVolumeBreach && independentWeakness)
                || (consecutiveBelow >= 2 && weakReboundLatest)) {
            diagnosis = BreakoutDiagnosis.HIGH_RISK_BREAKDOWN;
        } else if (consecutiveBelow == 1 && isMinorCloseBreach(zone, lastBar.close())
                && (longLowerShadowLatest || lowVolumeBreach || marketDrag)) {
            diagnosis = BreakoutDiagnosis.SUSPECTED_SHAKEOUT;
        } else {
            diagnosis = BreakoutDiagnosis.BREACH_OBSERVING;
            if (evidence.isEmpty()) {
                evidence.add("SINGLE_CLOSE_BELOW_SUPPORT");
            }
        }

        return new BreakoutDiagnosisDto(
                diagnosis, zone.id, zone.boundsVersion, zone.roleVersion, zone.timeframe,
                zone.lower, zone.upper, breachBar.sessionDate(), null, null, lastBar.sessionDate(),
                lastIdx - runStart, scale4(rvolBreach), null, scale4(rvolLatest),
                lastGeom.lowerShadowRatio(), breachGeom.bodyRatio(), lastGeom.closePosition(),
                scale6(stockRet), scale6(spyRet), scale6(excessRet),
                evidence, diagReasons);
    }

    private BreakoutDiagnosisDto detectSpringReclaimed(
            AtrZoneClusterer.ZoneCandidate zone,
            List<NormalizedBarDto> dailyBars,
            VolumeAnalysisCalculator volumeCalculator,
            Map<LocalDate, BigDecimal> spyDailyReturns,
            UnaryOperator<LocalDate> nextTradingDay,
            int lastIdx) {
        int scanFloor = Math.max(0, lastIdx - MAX_SPRING_SCAN_BARS);
        int lastBreachIdx = -1;
        for (int i = lastIdx - 1; i >= scanFloor; i--) {
            NormalizedBarDto bar = dailyBars.get(i);
            if (!isObservableAfterRoleConfirm(bar, zone)) {
                break;
            }
            if (bar.close().compareTo(zone.lower) < 0) {
                lastBreachIdx = i;
                break;
            }
        }
        if (lastBreachIdx < 0) {
            return null;
        }
        int runStart = lastBreachIdx;
        while (runStart > 0) {
            NormalizedBarDto prev = dailyBars.get(runStart - 1);
            if (!isObservableAfterRoleConfirm(prev, zone) || prev.close().compareTo(zone.lower) >= 0) {
                break;
            }
            runStart--;
        }
        // 須確由支撐上方／區間內跌破
        if (runStart > 0 && dailyBars.get(runStart - 1).close().compareTo(zone.lower) < 0) {
            return null;
        }
        if (runStart == 0 && dailyBars.get(0).high().compareTo(zone.lower) < 0) {
            return null;
        }
        int reclaimIdx = lastBreachIdx + 1;
        int breachDays = reclaimIdx - runStart;
        if (breachDays < 1 || breachDays > SPRING_RECLAIM_MAX_BARS) {
            return null;
        }
        NormalizedBarDto breachBar = dailyBars.get(runStart);
        // 陳舊已破位區（例如十多日前即已確認 BROKEN 且 retest 失敗）後續若再次穿越舊下緣，不得誕生全新的 Spring 訊號；
        // 只有與原破位同期（breachBar 未晚於 breakConfirmedAt）的首次收復才屬有效 Spring。
        if (zone.state == ZoneState.BROKEN && zone.breakConfirmedAt != null
                && (breachBar.availableAt() == null || breachBar.availableAt().isAfter(zone.breakConfirmedAt))) {
            return null;
        }
        NormalizedBarDto reclaimBar = dailyBars.get(reclaimIdx);
        NormalizedBarDto lastBar = dailyBars.get(lastIdx);
        LocalDate validThrough = advanceTradingDays(reclaimBar.sessionDate(), SPRING_VALID_SESSIONS, nextTradingDay);
        if (lastBar.sessionDate().isAfter(validThrough)) {
            return null;
        }

        List<AnalysisReasonDto> diagReasons = new ArrayList<>();
        BigDecimal rvolBreach = extractRvol(volumeCalculator, dailyBars, runStart, diagReasons);
        BigDecimal rvolReclaim = extractRvol(volumeCalculator, dailyBars, reclaimIdx, diagReasons);
        BigDecimal rvolLatest = reclaimIdx == lastIdx ? rvolReclaim : extractRvol(volumeCalculator, dailyBars, lastIdx, diagReasons);
        BarGeometry reclaimGeom = geometry(reclaimBar);
        boolean reclaimVolOk = rvolReclaim != null && (rvolReclaim.compareTo(RVOL_12) >= 0
                || (rvolReclaim.compareTo(BigDecimal.ONE) >= 0
                        && reclaimBar.close().compareTo(reclaimBar.open()) > 0
                        && reclaimGeom.closePosition() != null
                        && reclaimGeom.closePosition().compareTo(CLOSE_POS_60) >= 0));
        if (!reclaimVolOk) {
            return null;
        }

        BigDecimal stockRet = dailyReturn(dailyBars, runStart);
        BigDecimal spyRet = spyDailyReturns != null ? spyDailyReturns.get(breachBar.sessionDate()) : null;
        BigDecimal excessRet = stockRet != null && spyRet != null ? stockRet.subtract(spyRet, MC) : null;
        List<String> evidence = new ArrayList<>();
        evidence.add("SPRING_RECLAIMED");
        evidence.add("RECLAIM_VOLUME_CONFIRMED");
        if (rvolBreach != null && rvolBreach.compareTo(BigDecimal.ONE) < 0) {
            evidence.add("LOW_VOLUME_BREACH");
        }
        if (spyRet != null && excessRet != null
                && spyRet.compareTo(SPY_SELLOFF_THRESHOLD) <= 0
                && excessRet.compareTo(MAX_MARKET_DRAG_UNDERPERF) >= 0) {
            evidence.add("MARKET_WIDE_SELLOFF_DRAG");
        }

        return new BreakoutDiagnosisDto(
                BreakoutDiagnosis.SPRING_RECLAIMED, zone.id, zone.boundsVersion, zone.roleVersion, zone.timeframe,
                zone.lower, zone.upper, breachBar.sessionDate(), reclaimBar.sessionDate(), validThrough, lastBar.sessionDate(),
                lastIdx - runStart, scale4(rvolBreach), scale4(rvolReclaim), scale4(rvolLatest),
                reclaimGeom.lowerShadowRatio(), reclaimGeom.bodyRatio(), reclaimGeom.closePosition(),
                scale6(stockRet), scale6(spyRet), scale6(excessRet),
                evidence, diagReasons);
    }

    private BreakoutDiagnosisDto diagnoseIntradayPokeRecovered(
            AtrZoneClusterer.ZoneCandidate zone,
            List<NormalizedBarDto> dailyBars,
            VolumeAnalysisCalculator volumeCalculator,
            Map<LocalDate, BigDecimal> spyDailyReturns,
            int lastIdx) {
        NormalizedBarDto lastBar = dailyBars.get(lastIdx);
        BarGeometry geom = geometry(lastBar);
        List<AnalysisReasonDto> diagReasons = new ArrayList<>();
        BigDecimal rvolLatest = extractRvol(volumeCalculator, dailyBars, lastIdx, diagReasons);
        BigDecimal stockRet = dailyReturn(dailyBars, lastIdx);
        BigDecimal spyRet = spyDailyReturns != null ? spyDailyReturns.get(lastBar.sessionDate()) : null;
        BigDecimal excessRet = stockRet != null && spyRet != null ? stockRet.subtract(spyRet, MC) : null;

        boolean longLowerShadow = geom.lowerShadowRatio() != null && geom.lowerShadowRatio().compareTo(INTRADAY_SHADOW_MIN) >= 0;
        boolean lowVol = rvolLatest != null && rvolLatest.compareTo(BigDecimal.ONE) < 0;
        boolean marketDrag = spyRet != null && excessRet != null
                && spyRet.compareTo(SPY_SELLOFF_THRESHOLD) <= 0
                && excessRet.compareTo(MAX_MARKET_DRAG_UNDERPERF) >= 0;
        if (!longLowerShadow && !lowVol && !marketDrag) {
            return null;
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("INTRADAY_BELOW_SUPPORT_RECOVERED");
        if (longLowerShadow) evidence.add("LONG_LOWER_SHADOW");
        if (lowVol) evidence.add("LOW_VOLUME_BREACH");
        if (marketDrag) evidence.add("MARKET_WIDE_SELLOFF_DRAG");

        return new BreakoutDiagnosisDto(
                BreakoutDiagnosis.SUSPECTED_SHAKEOUT, zone.id, zone.boundsVersion, zone.roleVersion, zone.timeframe,
                zone.lower, zone.upper, lastBar.sessionDate(), null, null, lastBar.sessionDate(),
                0, scale4(rvolLatest), null, scale4(rvolLatest),
                geom.lowerShadowRatio(), geom.bodyRatio(), geom.closePosition(),
                scale6(stockRet), scale6(spyRet), scale6(excessRet),
                evidence, diagReasons);
    }

    private boolean isMinorCloseBreach(AtrZoneClusterer.ZoneCandidate zone, BigDecimal close) {
        BigDecimal depth = zone.lower.subtract(close, MC);
        if (depth.signum() <= 0) {
            return true;
        }
        BigDecimal pctCap = zone.lower.multiply(MINOR_BREACH_PCT, MC);
        BigDecimal cap = zone.anchorAtr != null && zone.anchorAtr.signum() > 0
                ? zone.anchorAtr.multiply(RETEST_CONFIRM_ATR, MC).min(pctCap)
                : pctCap;
        return depth.compareTo(cap) <= 0;
    }

    private boolean hasIntradayBounceIntoZone(List<NormalizedBarDto> bars, int fromInclusive, int toInclusive, BigDecimal supportLower) {
        for (int i = fromInclusive; i <= toInclusive; i++) {
            if (bars.get(i).high().compareTo(supportLower) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isObservableAfterRoleConfirm(NormalizedBarDto bar, AtrZoneClusterer.ZoneCandidate zone) {
        return bar != null && bar.availableAt() != null && zone.roleConfirmedAt != null
                && bar.availableAt().isAfter(zone.roleConfirmedAt);
    }

    /** 保留原始高精度供門檻比較，避免四捨五入將 1.19996 誤抬為 1.2000；輸出 DTO 時再統一 scale4。 */
    private BigDecimal extractRvol(VolumeAnalysisCalculator volumeCalculator, List<NormalizedBarDto> bars,
                                   int idx, List<AnalysisReasonDto> reasons) {
        if (volumeCalculator == null) {
            return null;
        }
        MetricResult<BigDecimal> rvol = volumeCalculator.rvolAt(bars, idx, 20);
        if (rvol.status() == MetricStatus.AVAILABLE && rvol.value() != null) {
            return rvol.value();
        }
        if (reasons != null && !rvol.reasons().isEmpty()) {
            AnalysisReasonDto first = rvol.reasons().getFirst();
            boolean exists = reasons.stream().anyMatch(r -> first.code().equals(r.code()));
            if (!exists) {
                reasons.add(first);
            }
        }
        return null;
    }

    private BigDecimal scale4(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal dailyReturn(List<NormalizedBarDto> bars, int idx) {
        if (idx <= 0 || idx >= bars.size()) {
            return null;
        }
        BigDecimal prevClose = bars.get(idx - 1).close();
        BigDecimal currClose = bars.get(idx).close();
        if (prevClose == null || currClose == null || prevClose.signum() <= 0) {
            return null;
        }
        return currClose.divide(prevClose, MC).subtract(BigDecimal.ONE, MC);
    }

    private LocalDate advanceTradingDays(LocalDate start, int sessions, UnaryOperator<LocalDate> nextTradingDay) {
        LocalDate current = start;
        UnaryOperator<LocalDate> stepper = nextTradingDay != null ? nextTradingDay : d -> d.plusDays(1);
        for (int i = 0; i < sessions; i++) {
            current = stepper.apply(current);
        }
        return current;
    }

    private BarGeometry geometry(NormalizedBarDto bar) {
        if (bar == null || bar.high() == null || bar.low() == null || bar.open() == null || bar.close() == null) {
            return new BarGeometry(null, null, null);
        }
        BigDecimal range = bar.high().subtract(bar.low(), MC);
        if (range.signum() <= 0) {
            return new BarGeometry(null, null, null);
        }
        BigDecimal lowerShadow = bar.open().min(bar.close()).subtract(bar.low(), MC).max(BigDecimal.ZERO);
        BigDecimal body = bar.close().subtract(bar.open(), MC).abs();
        BigDecimal closeFromLow = bar.close().subtract(bar.low(), MC).max(BigDecimal.ZERO);
        return new BarGeometry(
                lowerShadow.divide(range, 4, RoundingMode.HALF_UP),
                body.divide(range, 4, RoundingMode.HALF_UP),
                closeFromLow.divide(range, 4, RoundingMode.HALF_UP));
    }

    private BigDecimal scale6(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private record BarGeometry(BigDecimal lowerShadowRatio, BigDecimal bodyRatio, BigDecimal closePosition) {}
}
