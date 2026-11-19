package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeBinDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeProfileDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

/** 負責支撐阻力區域強度、Volume Profile 籌碼密集區加權與時間衰減計算。 */
@Component
public class ZoneStrengthCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    public static final int VOLUME_PROFILE_POC_BONUS = 15;
    public static final int VOLUME_PROFILE_HVN_BONUS = 10;

    public record VolumeProfileMatch(String level, int bonus) {
        public boolean matched() {
            return level != null && bonus > 0;
        }
    }

    public BigDecimal raw(boolean major, AnalysisTimeframe tf, int rejections, int flips, int tests, int penetrations) {
        return raw(major, tf, rejections, flips, tests, penetrations, 0);
    }

    /** 計算未衰減原始區域強度（0–100），含 Volume Profile 籌碼密集區加權後統一 clamp，再依原週期衰減。 */
    public BigDecimal raw(boolean major, AnalysisTimeframe tf, int rejections, int flips,
                          int tests, int penetrations, int volumeProfileBonus) {
        double v = (major ? 20 : 10)
                + (tf == AnalysisTimeframe.WEEKLY ? 10 : tf == AnalysisTimeframe.MONTHLY ? 15 : 0)
                + Math.min(30, rejections * 10)
                + (flips > 0 ? 10 : 0)
                + Math.max(0, volumeProfileBonus)
                - Math.min(20, Math.max(0, tests - 3) * 5)
                - Math.min(20, penetrations * 5);
        return BigDecimal.valueOf(Math.max(0, Math.min(100, v)));
    }

    /** 判斷區域與 252 日成交量分布 POC / HVN 是否存在正寬度重疊（排除零成交量箱與僅邊界相接）。 */
    public VolumeProfileMatch evaluateVolumeProfile(BigDecimal lower, BigDecimal upper, VolumeProfileDto profile) {
        if (lower == null || upper == null || upper.compareTo(lower) <= 0 || profile == null) {
            return new VolumeProfileMatch(null, 0);
        }
        if (overlapsWithPositiveVolume(lower, upper, profile.poc())) {
            return new VolumeProfileMatch("POC", VOLUME_PROFILE_POC_BONUS);
        }
        for (VolumeBinDto hvn : profile.highVolumeNodes()) {
            if (overlapsWithPositiveVolume(lower, upper, hvn)) {
                return new VolumeProfileMatch("HVN", VOLUME_PROFILE_HVN_BONUS);
            }
        }
        return new VolumeProfileMatch(null, 0);
    }

    private boolean overlapsWithPositiveVolume(BigDecimal zoneLower, BigDecimal zoneUpper, VolumeBinDto bin) {
        if (bin == null || bin.lower() == null || bin.upper() == null
                || bin.volume() == null || bin.volume().signum() <= 0) {
            return false;
        }
        BigDecimal overlapLow = zoneLower.max(bin.lower());
        BigDecimal overlapHigh = zoneUpper.min(bin.upper());
        return overlapHigh.compareTo(overlapLow) > 0;
    }

    public BigDecimal decayed(BigDecimal raw, AnalysisTimeframe tf, int ageBars) {
        double half = tf == AnalysisTimeframe.DAILY ? 126 : tf == AnalysisTimeframe.WEEKLY ? 26 : 12;
        return raw.multiply(BigDecimal.valueOf(Math.pow(2.0, -ageBars / half)), MC);
    }

    public ZoneStrengthLabel label(BigDecimal v) {
        return v.compareTo(BigDecimal.valueOf(60)) >= 0
                ? ZoneStrengthLabel.HIGH
                : v.compareTo(BigDecimal.valueOf(30)) >= 0 ? ZoneStrengthLabel.MEDIUM : ZoneStrengthLabel.LOW;
    }
}
