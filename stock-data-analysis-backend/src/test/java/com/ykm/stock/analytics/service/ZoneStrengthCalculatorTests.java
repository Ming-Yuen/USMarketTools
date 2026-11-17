package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.service.analysis.calculator.ZoneStrengthCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneStrengthCalculatorTests {
    // 例子：疲勞與穿透降低強度，日線區經過 126 日後也會衰減。
    @Test
    void fatigueAndPenetrationReduceStrengthAndAgeDecaysIt() {
        ZoneStrengthCalculator c = new ZoneStrengthCalculator();
        BigDecimal clean = c.raw(false, AnalysisTimeframe.DAILY, 2, 0, 2, 0);
        BigDecimal fatigued = c.raw(false, AnalysisTimeframe.DAILY, 2, 0, 6, 2);
        assertThat(clean).isEqualByComparingTo("30");
        assertThat(fatigued).isEqualByComparingTo("5");
        assertThat(c.decayed(clean, AnalysisTimeframe.DAILY, 126)).isEqualByComparingTo("15");
        assertThat(fatigued).isLessThan(clean);
        assertThat(c.decayed(clean, AnalysisTimeframe.DAILY, 126)).isLessThan(clean);
    }
}
