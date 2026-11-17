package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.PriceStructureCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceStructureCalculatorTests {
    // 例子：高點 100 到 110、低點 90 再升高時判定上升趨勢。
    @Test
    void higherHighAndHigherLowProduceUptrend() {
        PriceStructureCalculator calculator = new PriceStructureCalculator();
        List<ConfirmedPivotDto> pivots = List.of(
                pivot(PivotKind.HIGH, LocalDate.of(2026,1,2), "100", "5"),
                pivot(PivotKind.LOW, LocalDate.of(2026,1,3), "90", "5"),
                pivot(PivotKind.HIGH, LocalDate.of(2026,1,4), "110", "5"),
                pivot(PivotKind.LOW, LocalDate.of(2026,1,5), "100", "5"));

        MetricResult<PriceRelation> high = calculator.relation(pivots, PivotKind.HIGH);
        MetricResult<PriceRelation> low = calculator.relation(pivots, PivotKind.LOW);
        MetricResult<StructureTrendDto> trend = calculator.trend(pivots, high, low, Instant.parse("2026-01-06T00:00:00Z"));

        assertThat(high.value()).isEqualTo(PriceRelation.HH);
        assertThat(low.value()).isEqualTo(PriceRelation.HL);
        assertThat(trend.value().state()).isEqualTo(TrendState.UPTREND);
    }

    // 例子：後高 110 缺少 ATR 時，不得退回零容忍。
    @Test
    void missingAtrDoesNotFallBackToZeroTolerance() {
        PriceStructureCalculator calculator = new PriceStructureCalculator();
        List<ConfirmedPivotDto> pivots = List.of(
                pivot(PivotKind.HIGH, LocalDate.of(2026,1,2), "100", "5"),
                pivot(PivotKind.HIGH, LocalDate.of(2026,1,4), "110", null));
        MetricResult<PriceRelation> result = calculator.relation(pivots, PivotKind.HIGH);
        assertThat(result.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(result.value()).isNull();
    }

    // 例子：2026-01-05T20:00:00Z 才確認的高點 100，只用於其後開始的 K 線。
    @Test
    void wickProbeAndCloseBreachUseOnlyPivotConfirmedBeforeBarStarts() {
        PriceStructureCalculator calculator = new PriceStructureCalculator();
        LocalDate pivotDate = LocalDate.of(2026, 1, 2);
        Instant pivotConfirmed = Instant.parse("2026-01-05T20:00:00Z");
        ConfirmedPivotDto high = new ConfirmedPivotDto("H", PivotKind.HIGH, PivotScale.MINOR, AnalysisTimeframe.DAILY,
                new BigDecimal("100"), pivotDate, pivotConfirmed.minusSeconds(100), pivotConfirmed, pivotConfirmed,
                new BigDecimal("4"), new BigDecimal("4"), List.of("vH"), "dep-H");
        NormalizedBarDto wick = bar(LocalDate.of(2026,1,6), "100", "102", "99", "100.5", Instant.parse("2026-01-06T14:30:00Z"));
        NormalizedBarDto breach = bar(LocalDate.of(2026,1,7), "100", "103", "99", "102", Instant.parse("2026-01-07T14:30:00Z"));
        List<AtrPointDto> atr = List.of(
                new AtrPointDto(wick.sessionDate(),14,new BigDecimal("4"),null,pivotDate,14,1),
                new AtrPointDto(breach.sessionDate(),14,new BigDecimal("4"),null,pivotDate,14,2));

        List<StructureEventDto> events = calculator.structureEvents(List.of(wick, breach), atr, List.of(high));

        assertThat(events).extracting(StructureEventDto::code).containsExactly("WICK_PROBE", "STRUCTURE_HIGH_BREACH");
        assertThat(events).allMatch(e -> e.threshold().compareTo(new BigDecimal("101")) == 0);
    }

    private NormalizedBarDto bar(LocalDate d, String o, String h, String l, String c, Instant openAt) {
        Instant closeAt = openAt.plusSeconds(6 * 3600);
        return new NormalizedBarDto(d, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c),
                MetricResult.available(BigDecimal.ONE), openAt, closeAt, closeAt, closeAt, "v-" + d, "BASIS");
    }

    private ConfirmedPivotDto pivot(PivotKind kind, LocalDate d, String price, String atr) {
        Instant t = d.atTime(21,0).toInstant(ZoneOffset.UTC);
        BigDecimal a = atr == null ? null : new BigDecimal(atr);
        return new ConfirmedPivotDto(kind+"-"+d, kind, PivotScale.MINOR, AnalysisTimeframe.DAILY, new BigDecimal(price), d, t, t, t, a, a, List.of("v-"+d), "dep-"+kind);
    }
}
