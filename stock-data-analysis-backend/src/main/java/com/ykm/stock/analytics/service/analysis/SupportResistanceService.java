package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
// 用途：負責「支撐阻力服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class SupportResistanceService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private final AtrZoneClusterer clusterer;
    private final ZoneBreakRetestMachine breakRetestMachine;
    private final ZoneStrengthCalculator strengthCalculator;
    private final ZoneOverlapCalculator overlapCalculator;

    // 用途：分析相關資料，作為「支撐阻力服務」流程中的處理步驟。
    public MetricResult<SupportResistanceDto> analyze(AnalysisContext context,
                                                       NormalizedPriceSeriesDto normalizedSeries,
                                                       PriceStructureDto structure) {
        if (normalizedSeries == null || structure == null || normalizedSeries.bars().isEmpty()) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("PRICE_STRUCTURE_REQUIRED", "STRUCTURE", "需要有效行情及價格結構。"));
        }
        BigDecimal currentClose = normalizedSeries.bars().get(normalizedSeries.bars().size() - 1).close();
        List<LevelZoneDto> all = new ArrayList<>();
        List<ZoneEventDto> events = new ArrayList<>();
        List<AnalysisReasonDto> reasons = new ArrayList<>();

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
                BigDecimal raw = strengthCalculator.raw(candidate.major, frame.timeframe(), candidate.rejectionCount,
                        candidate.roleVersion > 1 ? 1 : 0, candidate.testCount, candidate.penetrationCount);
                BigDecimal decayed = strengthCalculator.decayed(raw, frame.timeframe(), candidate.ageBars);
                BigDecimal effective = candidate.state == ZoneState.BROKEN || candidate.state == ZoneState.EXPIRED
                        ? BigDecimal.ZERO : decayed;
                BigDecimal dLow = percent(candidate.lower, currentClose);
                BigDecimal dHigh = percent(candidate.upper, currentClose);
                events.addAll(candidate.events);
                all.add(new LevelZoneDto(candidate.id, frame.timeframe(), candidate.lower, candidate.upper, candidate.role,
                        decayed, candidate.confirmedAt, candidate.testCount, candidate.lastTestAt, candidate.state,
                        candidate.center, candidate.anchorAtr, candidate.boundsVersion, strengthCalculator.label(decayed),
                        effective, candidate.roleVersion, candidate.roleConfirmedAt, candidate.roleConfirmationPrice, candidate.seedIds, candidate.dependenceKeys,
                        candidate.rejectionCount, candidate.penetrationCount, candidate.ageBars, dLow, dHigh,
                        candidate.retestState, candidate.major, candidate.seedIds));
            }
        }

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

        if (supports.isEmpty() && resistances.isEmpty() && containing.isEmpty()) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    List.of(AnalysisReasonDto.of("NO_ACTIVE_LEVEL_ZONE", "STRUCTURE", "沒有有效支撐／阻力區域。")),
                    structure.evidenceRefs());
        }
        events.sort(Comparator.comparing(ZoneEventDto::confirmedAt, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(ZoneEventDto::id));
        return MetricResult.available(new SupportResistanceDto(supports, resistances, containing, overlap, events, reasons),
                structure.evidenceRefs());
    }

    // 用途：處理percent，作為「支撐阻力服務」流程中的處理步驟。
    private BigDecimal percent(BigDecimal value, BigDecimal current) {
        if (value == null || current == null || current.signum() <= 0) return null;
        return value.divide(current, MC).subtract(BigDecimal.ONE, MC).multiply(BigDecimal.valueOf(100), MC);
    }

    // 用途：處理distanceTo區域，作為「支撐阻力服務」流程中的處理步驟。
    private BigDecimal distanceToZone(LevelZoneDto zone, BigDecimal current) {
        if (current.compareTo(zone.lower()) < 0) return zone.lower().subtract(current, MC).abs();
        if (current.compareTo(zone.upper()) > 0) return current.subtract(zone.upper(), MC).abs();
        return BigDecimal.ZERO;
    }

    // 用途：處理latest，作為「支撐阻力服務」流程中的處理步驟。
    private Instant latest(Instant... values) {
        return Arrays.stream(values).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }
}
