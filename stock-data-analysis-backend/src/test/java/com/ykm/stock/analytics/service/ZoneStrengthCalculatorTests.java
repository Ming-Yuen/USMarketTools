package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeBinDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeProfileDto;
import com.ykm.stock.analytics.service.analysis.calculator.ZoneStrengthCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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

    // 例子：POC +15 優先於 HVN +10（雙重命中只計一次）、加在 raw 後照原週期衰減；零成交量箱與僅邊界相接不加分。
    @Test
    void volumeProfileConfluenceScoresPocOverHvnDecaysNormallyAndRejectsZeroVolumeOrEdgeTouch() {
        ZoneStrengthCalculator c = new ZoneStrengthCalculator();
        VolumeBinDto poc = new VolumeBinDto(10, new BigDecimal("100"), new BigDecimal("105"), new BigDecimal("50000"));
        VolumeBinDto hvn = new VolumeBinDto(15, new BigDecimal("110"), new BigDecimal("115"), new BigDecimal("30000"));
        VolumeBinDto zeroBin = new VolumeBinDto(20, new BigDecimal("120"), new BigDecimal("125"), BigDecimal.ZERO);
        VolumeProfileDto profile = new VolumeProfileDto(List.of(poc, hvn, zeroBin), poc, List.of(poc, hvn, zeroBin), List.of(), "DAILY_OHLCV_APPROXIMATION");

        // 1) 同時重疊 POC 與 HVN -> 僅計 POC +15，且加入 raw (30+15=45) 後於 126 根衰減為 22.5
        ZoneStrengthCalculator.VolumeProfileMatch bothMatch = c.evaluateVolumeProfile(new BigDecimal("102"), new BigDecimal("112"), profile);
        assertThat(bothMatch.level()).isEqualTo("POC");
        assertThat(bothMatch.bonus()).isEqualTo(15);
        BigDecimal rawWithPoc = c.raw(false, AnalysisTimeframe.DAILY, 2, 0, 2, 0, bothMatch.bonus());
        assertThat(rawWithPoc).isEqualByComparingTo("45");
        assertThat(c.decayed(rawWithPoc, AnalysisTimeframe.DAILY, 126)).isEqualByComparingTo("22.5");

        // 2) 僅重疊 HVN -> +10
        ZoneStrengthCalculator.VolumeProfileMatch hvnMatch = c.evaluateVolumeProfile(new BigDecimal("111"), new BigDecimal("114"), profile);
        assertThat(hvnMatch.level()).isEqualTo("HVN");
        assertThat(hvnMatch.bonus()).isEqualTo(10);

        // 3) 僅邊界相接（zone.upper == poc.lower）-> 不算正寬度重疊，不加分
        ZoneStrengthCalculator.VolumeProfileMatch edgeOnly = c.evaluateVolumeProfile(new BigDecimal("95"), new BigDecimal("100"), profile);
        assertThat(edgeOnly.matched()).isFalse();
        assertThat(edgeOnly.bonus()).isZero();

        // 4) 零成交量箱 -> 不加分
        ZoneStrengthCalculator.VolumeProfileMatch zeroMatch = c.evaluateVolumeProfile(new BigDecimal("121"), new BigDecimal("124"), profile);
        assertThat(zeroMatch.matched()).isFalse();
        assertThat(zeroMatch.bonus()).isZero();
    }
}
