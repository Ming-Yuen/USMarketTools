package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.WilderAtrCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WilderAtrCalculatorTests {
    // 例子：2026-01-02 起前 14 個真實波幅構成首個 ATR，下一根才用 Wilder 遞迴。
    @Test
    void firstAtrUsesFourteenTrueRangesAndNextValueUsesWilderRecurrence() {
        WilderAtrCalculator calculator = new WilderAtrCalculator();
        List<NormalizedBarDto> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 2);
        for (int i = 0; i < 15; i++) bars.add(bar(start.plusDays(i), new BigDecimal("10"), new BigDecimal("11"), new BigDecimal("9"), new BigDecimal("10")));
        bars.add(bar(start.plusDays(15), new BigDecimal("10"), new BigDecimal("14"), new BigDecimal("10"), new BigDecimal("12")));

        List<AtrPointDto> atr = calculator.calculate(bars, 14);

        assertThat(atr).hasSize(2);
        assertThat(atr.get(0).sessionDate()).isEqualTo(start.plusDays(14));
        assertThat(atr.get(0).atr()).isEqualByComparingTo("2");
        assertThat(atr.get(0).trCount()).isEqualTo(14);
        assertThat(atr.get(0).warmupCount()).isZero();
        assertThat(atr.get(1).atr()).isEqualByComparingTo(new BigDecimal("2.142857142857142857142857142857143"));
        assertThat(atr.get(1).warmupCount()).isEqualTo(1);
    }

    // 例子：2026-01-02 起只有 14 根仍在暖機，不得回傳假的 0 ATR。
    @Test
    void fourteenBarsAreStillWarmupAndDoNotReturnFakeZeroAtr() {
        WilderAtrCalculator calculator = new WilderAtrCalculator();
        List<NormalizedBarDto> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 2);
        for (int i = 0; i < 14; i++) bars.add(bar(start.plusDays(i), BigDecimal.TEN, new BigDecimal("11"), new BigDecimal("9"), BigDecimal.TEN));
        assertThat(calculator.calculate(bars, 14)).isEmpty();
    }

    private NormalizedBarDto bar(LocalDate d, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c) {
        Instant t = d.atTime(21, 0).toInstant(ZoneOffset.UTC);
        return new NormalizedBarDto(d, o, h, l, c, MetricResult.available(BigDecimal.ONE), t, t, t, t, "v-" + d, "BASIS");
    }
}
