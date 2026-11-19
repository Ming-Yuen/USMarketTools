package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
// 用途：負責「支撐阻力服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class SupportResistanceService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MIN_DIAGNOSIS_STRENGTH = BigDecimal.valueOf(30);
    private final AtrZoneClusterer clusterer;
    private final ZoneBreakRetestMachine breakRetestMachine;
    private final ZoneStrengthCalculator strengthCalculator;
    private final ZoneOverlapCalculator overlapCalculator;
    private final VolumeAnalysisCalculator volumeCalculator;
    private final MarketStatusService calendar;

    // 用途：分析支撐阻力（無市場量價背景時仍維持原有純結構分析）。
    public MetricResult<SupportResistanceDto> analyze(AnalysisContext context,
                                                       NormalizedPriceSeriesDto normalizedSeries,
                                                       PriceStructureDto structure) {
        return analyze(context, normalizedSeries, structure, null);
    }

    /** 整合 252 日 Volume Profile 籌碼密集區加權與日線破位真假診斷之支撐阻力分析。 */
    public MetricResult<SupportResistanceDto> analyze(AnalysisContext context,
                                                       NormalizedPriceSeriesDto normalizedSeries,
                                                       PriceStructureDto structure,
                                                       MarketContextDto marketContext) {
        if (normalizedSeries == null || structure == null || normalizedSeries.bars().isEmpty()) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("PRICE_STRUCTURE_REQUIRED", "STRUCTURE", "需要有效行情及價格結構。"));
        }
        BigDecimal currentClose = normalizedSeries.bars().get(normalizedSeries.bars().size() - 1).close();
        VolumeProfileDto volumeProfile = extractVolumeProfile(marketContext);
        Map<LocalDate, BigDecimal> spyDailyReturns = marketContext != null && marketContext.relativeStrength() != null
                ? marketContext.relativeStrength().recentBenchmarkDailyReturns() : Map.of();
        AnalysisTimeframe horizonTimeframe = context.horizon() == AnalysisHorizon.SWING
                ? AnalysisTimeframe.DAILY : AnalysisTimeframe.WEEKLY;

        List<LevelZoneDto> all = new ArrayList<>();
        List<ZoneEventDto> events = new ArrayList<>();
        List<AnalysisReasonDto> reasons = new ArrayList<>();
        List<BreakoutDiagnosisDto> zoneDiagnoses = new ArrayList<>();

        for (TimeframeStructureDto frame : structure.timeframes()) {
            List<ConfirmedPivotDto> pivots = new ArrayList<>();
            pivots.addAll(frame.minorPivots());
            pivots.addAll(frame.majorPivots());
            if (pivots.isEmpty()) {
                reasons.add(AnalysisReasonDto.of("ZONE_SEED_INSUFFICIENT", "STRUCTURE",
                        frame.timeframe() + " 沒有已確認 pivot 可建立支阻。"));
                continue;
            }
            List<AtrZoneClusterer.ZoneCandidate> candidates = clusterer.cluster(context.figi(), frame.timeframe(), pivots);
            for (AtrZoneClusterer.ZoneCandidate candidate : candidates) {
                // Break/retest and test episodes are advanced together in bar order so a future
                // role/boundary cannot be replayed backwards over older bars.
                breakRetestMachine.evaluate(candidate, frame.bars());
                Instant freshnessAnchor = latest(candidate.confirmedAt, candidate.lastTestAt, candidate.roleConfirmedAt);
                candidate.ageBars = (int) frame.bars().stream()
                        .filter(b -> b.availableAt() != null && freshnessAnchor != null && b.availableAt().isAfter(freshnessAnchor)).count();
                ZoneStrengthCalculator.VolumeProfileMatch vpMatch = strengthCalculator.evaluateVolumeProfile(
                        candidate.lower, candidate.upper, volumeProfile);
                BigDecimal raw = strengthCalculator.raw(candidate.major, frame.timeframe(), candidate.rejectionCount,
                        candidate.roleVersion > 1 ? 1 : 0, candidate.testCount, candidate.penetrationCount, vpMatch.bonus());
                BigDecimal decayed = strengthCalculator.decayed(raw, frame.timeframe(), candidate.ageBars);
                BigDecimal effective = candidate.state == ZoneState.BROKEN || candidate.state == ZoneState.EXPIRED
                        ? BigDecimal.ZERO : decayed;
                BigDecimal dLow = percent(candidate.lower, currentClose);
                BigDecimal dHigh = percent(candidate.upper, currentClose);
                List<String> confluenceTags = vpMatch.matched() ? List.of("VOLUME_PROFILE_CONFLUENCE") : List.of();
                events.addAll(candidate.events);
                all.add(new LevelZoneDto(candidate.id, frame.timeframe(), candidate.lower, candidate.upper, candidate.role,
                        decayed, candidate.confirmedAt, candidate.testCount, candidate.lastTestAt, candidate.state,
                        candidate.center, candidate.anchorAtr, candidate.boundsVersion, strengthCalculator.label(decayed),
                        effective, candidate.roleVersion, candidate.roleConfirmedAt, candidate.roleConfirmationPrice, candidate.seedIds, candidate.dependenceKeys,
                        candidate.rejectionCount, candidate.penetrationCount, candidate.ageBars, dLow, dHigh,
                        candidate.retestState, candidate.major, candidate.seedIds, vpMatch.level(), confluenceTags));

                // 依持有週期（POSITION -> 週線支撐；SWING -> 日線支撐）僅針對強度達 30 分以上之已確認支撐區執行日線破位診斷
                if (frame.timeframe() == horizonTimeframe && candidate.role == ZoneRole.SUPPORT
                        && decayed.compareTo(MIN_DIAGNOSIS_STRENGTH) >= 0) {
                    BreakoutDiagnosisDto diagnosis = breakRetestMachine.diagnoseSupport(
                            candidate, normalizedSeries.bars(), volumeCalculator, spyDailyReturns, calendar::getNextTradingDay);
                    if (diagnosis != null && diagnosis.diagnosis() != BreakoutDiagnosis.NONE) {
                        zoneDiagnoses.add(diagnosis);
                    }
                }
            }
        }

        zoneDiagnoses.sort(diagnosisOrder(currentClose));
        BreakoutDiagnosisDto primaryDiagnosis = zoneDiagnoses.isEmpty() ? null : zoneDiagnoses.getFirst();

        List<LevelZoneDto> active = all.stream().filter(z -> z.state() == ZoneState.ACTIVE).toList();
        Comparator<LevelZoneDto> order = Comparator
                .comparing((LevelZoneDto z) -> distanceToZone(z, currentClose))
                .thenComparing(LevelZoneDto::effectiveStrength, Comparator.reverseOrder())
                .thenComparing(z -> z.timeframe().ordinal())
                .thenComparing(LevelZoneDto::id);
        List<LevelZoneDto> supports = active.stream()
                .filter(z -> z.role() == ZoneRole.SUPPORT && currentClose.compareTo(z.upper()) > 0)
                .sorted(order).limit(6).toList();
        List<LevelZoneDto> resistances = active.stream()
                .filter(z -> z.role() == ZoneRole.RESISTANCE && currentClose.compareTo(z.lower()) < 0)
                .sorted(order).limit(6).toList();
        List<LevelZoneDto> containing = active.stream()
                .filter(z -> currentClose.compareTo(z.lower()) >= 0 && currentClose.compareTo(z.upper()) <= 0)
                .sorted(order).limit(6).toList();
        List<ZoneOverlapGroupDto> overlap = overlapCalculator.calculate(active);

        events.sort(Comparator.comparing(ZoneEventDto::confirmedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ZoneEventDto::id));
        if (supports.isEmpty() && resistances.isEmpty() && containing.isEmpty()) {
            if (primaryDiagnosis != null) {
                reasons.add(AnalysisReasonDto.of("NO_ACTIVE_LEVEL_ZONE", "STRUCTURE", "沒有有效支撐／阻力區域（已保留最近支撐破位診斷）。"));
                return MetricResult.available(new SupportResistanceDto(
                        supports, resistances, containing, overlap, events, reasons, primaryDiagnosis, zoneDiagnoses),
                        structure.evidenceRefs());
            }
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    List.of(AnalysisReasonDto.of("NO_ACTIVE_LEVEL_ZONE", "STRUCTURE", "沒有有效支撐／阻力區域。")),
                    structure.evidenceRefs());
        }
        return MetricResult.available(new SupportResistanceDto(
                supports, resistances, containing, overlap, events, reasons, primaryDiagnosis, zoneDiagnoses),
                structure.evidenceRefs());
    }

    private VolumeProfileDto extractVolumeProfile(MarketContextDto marketContext) {
        if (marketContext == null || marketContext.volume() == null || marketContext.volume().profile252() == null) {
            return null;
        }
        MetricResult<VolumeProfileDto> profile = marketContext.volume().profile252();
        return profile.status() == MetricStatus.AVAILABLE ? profile.value() : null;
    }

    private Comparator<BreakoutDiagnosisDto> diagnosisOrder(BigDecimal currentClose) {
        return Comparator
                .comparingInt((BreakoutDiagnosisDto d) -> diagnosisPriority(d.diagnosis()))
                .thenComparing(BreakoutDiagnosisDto::breachDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(d -> distanceToBounds(d.supportLower(), d.supportUpper(), currentClose))
                .thenComparing(BreakoutDiagnosisDto::zoneId, Comparator.nullsLast(String::compareTo));
    }

    private int diagnosisPriority(BreakoutDiagnosis diagnosis) {
        if (diagnosis == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN) return 0;
        if (diagnosis == BreakoutDiagnosis.BREACH_OBSERVING) return 1;
        if (diagnosis == BreakoutDiagnosis.SUSPECTED_SHAKEOUT) return 2;
        if (diagnosis == BreakoutDiagnosis.SPRING_RECLAIMED) return 3;
        return 4;
    }

    private BigDecimal distanceToBounds(BigDecimal lower, BigDecimal upper, BigDecimal current) {
        if (lower == null || upper == null || current == null) return BigDecimal.ZERO;
        if (current.compareTo(lower) < 0) return lower.subtract(current, MC).abs();
        if (current.compareTo(upper) > 0) return current.subtract(upper, MC).abs();
        return BigDecimal.ZERO;
    }

    // 用途：處理percent，作為「支撐阻力服務」流程中的處理步驟。
    private BigDecimal percent(BigDecimal value, BigDecimal current) {
        if (value == null || current == null || current.signum() <= 0) return null;
        return value.divide(current, MC).subtract(BigDecimal.ONE, MC).multiply(BigDecimal.valueOf(100), MC);
    }

    // 用途：處理distanceTo區域，作為「支撐阻力服務」流程中的處理步驟。
    private BigDecimal distanceToZone(LevelZoneDto zone, BigDecimal current) {
        return distanceToBounds(zone.lower(), zone.upper(), current);
    }

    // 用途：處理latest，作為「支撐阻力服務」流程中的處理步驟。
    private Instant latest(Instant... values) {
        return Arrays.stream(values).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }
}
