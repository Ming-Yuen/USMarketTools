package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.ConfirmedSwingDetector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmedSwingDetectorTests {
    // 例子：2026-03-02 起的 minor 樞軸要等右側兩根 K 線才可見。
    @Test
    void minorPivotIsInvisibleUntilTwoRightBarsExistAndUsesTheirAvailability() {
        ConfirmedSwingDetector detector = new ConfirmedSwingDetector();
        LocalDate start = LocalDate.of(2026, 3, 2);
        List<NormalizedBarDto> bars = new ArrayList<>();
        bars.add(bar(start, "1"));
        bars.add(bar(start.plusDays(1), "2"));
        bars.add(bar(start.plusDays(2), "5"));
        bars.add(bar(start.plusDays(3), "2"));
        bars.add(bar(start.plusDays(4), "1"));

        assertThat(detector.detect("FIGI", AnalysisTimeframe.DAILY, bars.subList(0, 4), List.of(), PivotScale.MINOR)).isEmpty();
        List<ConfirmedPivotDto> confirmed = detector.detect("FIGI", AnalysisTimeframe.DAILY, bars, List.of(), PivotScale.MINOR);

        assertThat(confirmed).hasSize(1);
        ConfirmedPivotDto p = confirmed.getFirst();
        assertThat(p.kind()).isEqualTo(PivotKind.HIGH);
        assertThat(p.occurrenceSession()).isEqualTo(start.plusDays(2));
        assertThat(p.confirmationTime()).isEqualTo(bars.get(4).availableAt());
        assertThat(p.occurrenceTime()).isEqualTo(bars.get(2).sessionCloseAt());
    }

    // 例子：2026-04-01 高點 20 的 major 樞軸需右側五根 K 線才可見。
    @Test
    void majorPivotNeedsFiveRightBarsBeforeItBecomesVisible() {
        ConfirmedSwingDetector detector = new ConfirmedSwingDetector();
        LocalDate start = LocalDate.of(2026, 4, 1);
        List<NormalizedBarDto> bars = new ArrayList<>();
        for (int i = 0; i < 11; i++) bars.add(bar(start.plusDays(i), i == 5 ? "20" : String.valueOf(10 - Math.abs(5-i))));
        assertThat(detector.detect("FIGI", AnalysisTimeframe.DAILY, bars.subList(0, 10), List.of(), PivotScale.MAJOR)).isEmpty();
        List<ConfirmedPivotDto> confirmed = detector.detect("FIGI", AnalysisTimeframe.DAILY, bars, List.of(), PivotScale.MAJOR);
        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.getFirst().occurrenceSession()).isEqualTo(start.plusDays(5));
        assertThat(confirmed.getFirst().confirmationTime()).isEqualTo(bars.get(10).availableAt());
    }

    // 例子：2026-03-02 起連續兩根高點 5 的平台不任意選一個樞軸。
    @Test
    void equalHighPlateauDoesNotChooseAnArbitraryPivot() {
        ConfirmedSwingDetector detector = new ConfirmedSwingDetector();
        LocalDate start = LocalDate.of(2026, 3, 2);
        List<NormalizedBarDto> bars = List.of(bar(start,"1"), bar(start.plusDays(1),"5"), bar(start.plusDays(2),"5"), bar(start.plusDays(3),"2"), bar(start.plusDays(4),"1"));
        assertThat(detector.detect("FIGI", AnalysisTimeframe.DAILY, bars, List.of(), PivotScale.MINOR)).isEmpty();
    }

    // 原本：來源 bar 沒有 versionRef 時 List.copyOf(null) 令整份分析報告回傳 500。
    // 修正後：仍然能偵測已確認樞軸，只略過缺失的證據參考。
    @Test
    void missingBarVersionReferenceDoesNotCrashConfirmedPivot() {
        ConfirmedSwingDetector detector = new ConfirmedSwingDetector();
        LocalDate start = LocalDate.of(2026, 11, 2);
        List<NormalizedBarDto> bars = new ArrayList<>();
        bars.add(bar(start, "1"));
        bars.add(bar(start.plusDays(1), "2"));
        bars.add(bar(start.plusDays(2), "5"));
        bars.add(bar(start.plusDays(3), "2"));
        bars.add(bar(start.plusDays(4), "1"));

        NormalizedBarDto source = bars.get(2);
        bars.set(2, new NormalizedBarDto(source.sessionDate(), source.open(), source.high(), source.low(),
                source.close(), source.volume(), source.sessionOpenAt(), source.sessionCloseAt(),
                source.availableAt(), source.observedAt(), null, source.basisId()));

        List<ConfirmedPivotDto> pivots = detector.detect("FIGI", AnalysisTimeframe.DAILY, bars, List.of(), PivotScale.MINOR);
        assertThat(pivots).hasSize(1);
        assertThat(pivots.getFirst().evidenceRefs()).hasSize(4).doesNotContainNull();
    }

    private NormalizedBarDto bar(LocalDate d, String high) {
        BigDecimal h = new BigDecimal(high);
        BigDecimal low = BigDecimal.ZERO;
        Instant close = d.atTime(21, 0).toInstant(ZoneOffset.UTC);
        Instant available = close.plusSeconds(30);
        return new NormalizedBarDto(d, BigDecimal.ONE, h, low, BigDecimal.ONE, MetricResult.available(BigDecimal.ONE), close.minusSeconds(6*3600), close, available, available, "v-"+d, "BASIS");
    }
}
