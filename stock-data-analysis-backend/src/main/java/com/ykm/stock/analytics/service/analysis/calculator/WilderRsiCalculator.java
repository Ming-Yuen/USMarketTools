package com.ykm.stock.analytics.service.analysis.calculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Shared Wilder RSI core used by legacy indicator code and analysis engine. */
public class WilderRsiCalculator {
    private static final int WORK_SCALE = 10;
    private static final int RESULT_SCALE = 4;

    // 用途：處理series的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    public List<BigDecimal> series(List<BigDecimal> closes, int period) {
        if (closes == null || period <= 0 || closes.size() < period + 1 || closes.stream().anyMatch(v -> v == null)) return List.of();
        BigDecimal p = BigDecimal.valueOf(period);
        BigDecimal priorWeight = BigDecimal.valueOf(period - 1L);
        BigDecimal gainSum = BigDecimal.ZERO;
        BigDecimal lossSum = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            if (change.signum() > 0) gainSum = gainSum.add(change);
            else if (change.signum() < 0) lossSum = lossSum.add(change.abs());
        }
        BigDecimal avgGain = gainSum.divide(p, WORK_SCALE, RoundingMode.HALF_UP);
        BigDecimal avgLoss = lossSum.divide(p, WORK_SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> out = new ArrayList<>();
        out.add(value(avgGain, avgLoss));
        for (int i = period + 1; i < closes.size(); i++) {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;
            avgGain = avgGain.multiply(priorWeight).add(gain).divide(p, WORK_SCALE, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(priorWeight).add(loss).divide(p, WORK_SCALE, RoundingMode.HALF_UP);
            out.add(value(avgGain, avgLoss));
        }
        return List.copyOf(out);
    }

    // 用途：處理相關資料的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    public BigDecimal latest(List<BigDecimal> closes, int period) {
        List<BigDecimal> values = series(closes, period);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    // 用途：取得或整理數值的內部表示，供本服務其他分析步驟重用。
    private BigDecimal value(BigDecimal gain, BigDecimal loss) {
        if (gain.signum() == 0 && loss.signum() == 0) return BigDecimal.valueOf(50).setScale(RESULT_SCALE);
        if (loss.signum() == 0) return BigDecimal.valueOf(100).setScale(RESULT_SCALE);
        if (gain.signum() == 0) return BigDecimal.ZERO.setScale(RESULT_SCALE);
        BigDecimal rs = gain.divide(loss, WORK_SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100)
                .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), WORK_SCALE, RoundingMode.HALF_UP))
                .setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }
}
