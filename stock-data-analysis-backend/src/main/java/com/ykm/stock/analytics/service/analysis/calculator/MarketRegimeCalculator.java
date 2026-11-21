package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 負責依 SPY／QQQ 均線結構、斜率、252 日回撤與實際 VIX 收盤值判定市場體制（MarketRegime）。
 * 採「下行風險即時觸發、上行解除須緩衝與連收確認」之非對稱遲滯設計，並確保 272 日視窗與長序列結果完全一致。
 */
@Component
public class MarketRegimeCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    public static final int REQUIRED_SESSIONS = 272;

    private static final BigDecimal PANIC_VIX_THRESHOLD = BigDecimal.valueOf(35);
    private static final BigDecimal CORRECTION_DRAWDOWN = new BigDecimal("-0.10");
    private static final BigDecimal BEAR_DRAWDOWN = new BigDecimal("-0.20");
    private static final BigDecimal MA50_RECLAIM_FACTOR = new BigDecimal("1.003");
    private static final BigDecimal MA50_RECOVERY_HOLD_FACTOR = new BigDecimal("0.995");
    private static final BigDecimal MA50_RISK_ON_HOLD_FACTOR = new BigDecimal("0.992");
    private static final int STRESS_LOOKBACK_SESSIONS = 20;
    private static final int BEAR_EXIT_LOOKBACK_SESSIONS = 5;

    public MetricResult<MarketRegime> classify(
            List<NormalizedBarDto> spy,
            List<NormalizedBarDto> qqq,
            MetricResult<BigDecimal> actualVix) {
        Pair aligned = aligned(spy, qqq);
        if (aligned.spy.size() < REQUIRED_SESSIONS) {
            return MetricResult.unavailable(
                    MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("MARKET_REGIME_272_SESSIONS_REQUIRED", "MARKET", "SPY/QQQ需至少272個完整同日session。"));
        }
        int last = aligned.spy.size() - 1;
        Raw current = raw(aligned.spy, aligned.qqq, last);
        BigDecimal vix = actualVix != null && actualVix.status() == MetricStatus.AVAILABLE ? actualVix.value() : null;
        if (vix != null && vix.compareTo(PANIC_VIX_THRESHOLD) >= 0
                && (current.spyDd.compareTo(CORRECTION_DRAWDOWN) <= 0
                || current.qqqDd.compareTo(CORRECTION_DRAWDOWN) <= 0)) {
            return MetricResult.available(MarketRegime.PANIC);
        }

        // 1) BEAR 優先序嚴格高於 RECOVERY：雙指數仍處熊市結構或雙年線下彎未確認收復時，短線站上 SMA50 絕不誤放行 ALLOW
        if (current.bear || isUnconfirmedBearBounce(aligned.spy, aligned.qqq, last, current)) {
            return MetricResult.available(MarketRegime.BEAR);
        }

        int windowStart = Math.max(251, last - STRESS_LOOKBACK_SESSIONS);
        boolean recentStress = false;
        int lastStressBreakdown = -1;
        Raw prev = null;
        for (int i = windowStart; i <= last; i++) {
            Raw r = raw(aligned.spy, aligned.qqq, i);
            if (r.bear || r.correction) {
                recentStress = true;
            }
            boolean freshStressOnset = prev != null && r.correction && !prev.correction;
            if (r.bear || r.bothBelowMa50 || !r.bothAboveRecoveryHold || freshStressOnset) {
                lastStressBreakdown = i;
            }
            prev = r;
        }

        boolean reclaimedMa50 = hasReclaimedMa50(aligned.spy, aligned.qqq, windowStart, last, lastStressBreakdown);
        boolean risingMa50 = slope(aligned.spy, last, 50).signum() > 0 && slope(aligned.qqq, last, 50).signum() > 0;

        // 2) RECOVERY：脫離 PANIC／BEAR 後，雙指數連收 3 日站穩 SMA50（末日 >= +0.3% 緩衝）且未再跌破 -0.5% 保護帶
        if (recentStress && reclaimedMa50 && (!current.correction || risingMa50)) {
            return MetricResult.available(MarketRegime.RECOVERY);
        }

        // 3) CORRECTION：下行破位或回撤達 -10% 零延遲即時切入；近期曾觸發壓力但尚未完成 3 日緩衝收復前維持 CORRECTION
        if (current.correction || recentStress) {
            return MetricResult.available(MarketRegime.CORRECTION);
        }

        // 4) 無近期壓力視窗：於 SMA50 附近套用非對稱緩衝，消除單日微小震盪造成之 RISK_ON <-> NEUTRAL 反覆橫跳
        if (!current.bullStructure) {
            return MetricResult.available(MarketRegime.NEUTRAL);
        }
        boolean riskOnState = raw(aligned.spy, aligned.qqq, windowStart).riskOn;
        for (int i = windowStart + 1; i <= last; i++) {
            Raw r = raw(aligned.spy, aligned.qqq, i);
            if (!r.bullStructure) {
                riskOnState = false;
            } else if (riskOnState) {
                if (belowMa50Buffer(aligned.spy, i, MA50_RISK_ON_HOLD_FACTOR)
                        || belowMa50Buffer(aligned.qqq, i, MA50_RISK_ON_HOLD_FACTOR)) {
                    riskOnState = false;
                }
            } else if (confirmedThreeClosesAboveMa50(aligned.spy, i, MA50_RECLAIM_FACTOR)
                    && confirmedThreeClosesAboveMa50(aligned.qqq, i, MA50_RECLAIM_FACTOR)) {
                riskOnState = true;
            }
        }
        return MetricResult.available(riskOnState ? MarketRegime.RISK_ON : MarketRegime.NEUTRAL);
    }

    private boolean isUnconfirmedBearBounce(List<NormalizedBarDto> spy, List<NormalizedBarDto> qqq, int last, Raw current) {
        if (!current.bothSma200Falling) {
            return false;
        }
        int start = Math.max(251, last - BEAR_EXIT_LOOKBACK_SESSIONS);
        boolean recentBear = false;
        for (int i = start; i < last; i++) {
            if (raw(spy, qqq, i).bear) {
                recentBear = true;
                break;
            }
        }
        if (!recentBear) {
            return false;
        }
        return !lastThreeAboveMa(spy, last, 200) && !lastThreeAboveMa(qqq, last, 200);
    }

    private boolean hasReclaimedMa50(
            List<NormalizedBarDto> spy,
            List<NormalizedBarDto> qqq,
            int windowStart,
            int last,
            int lastStressBreakdown) {
        if (lastStressBreakdown >= last) {
            return false;
        }
        int searchStart = lastStressBreakdown < 0
                ? windowStart
                : Math.max(windowStart, lastStressBreakdown + 3);
        for (int i = searchStart; i <= last; i++) {
            if (confirmedThreeClosesAboveMa50(spy, i, MA50_RECLAIM_FACTOR)
                    && confirmedThreeClosesAboveMa50(qqq, i, MA50_RECLAIM_FACTOR)) {
                return true;
            }
        }
        return false;
    }

    private boolean confirmedThreeClosesAboveMa50(List<NormalizedBarDto> x, int end, BigDecimal lastDayMinMultiple) {
        for (int i = end - 2; i < end; i++) {
            if (x.get(i).close().compareTo(sma(x, i, 50)) <= 0) {
                return false;
            }
        }
        BigDecimal minClose = sma(x, end, 50).multiply(lastDayMinMultiple, MC);
        return x.get(end).close().compareTo(minClose) >= 0;
    }

    private boolean lastThreeAboveMa(List<NormalizedBarDto> x, int end, int period) {
        for (int i = end - 2; i <= end; i++) {
            if (x.get(i).close().compareTo(sma(x, i, period)) <= 0) {
                return false;
            }
        }
        return true;
    }

    private boolean belowMa50Buffer(List<NormalizedBarDto> x, int idx, BigDecimal factor) {
        return x.get(idx).close().compareTo(sma(x, idx, 50).multiply(factor, MC)) < 0;
    }

    private Pair aligned(List<NormalizedBarDto> a, List<NormalizedBarDto> b) {
        if (a == null || b == null) {
            return new Pair(List.of(), List.of());
        }
        Map<LocalDate, NormalizedBarDto> bm = new HashMap<>();
        for (NormalizedBarDto x : b) {
            if (valid(x)) {
                bm.put(x.sessionDate(), x);
            }
        }
        List<NormalizedBarDto> aa = new ArrayList<>();
        List<NormalizedBarDto> bb = new ArrayList<>();
        for (NormalizedBarDto x : a) {
            if (valid(x) && bm.containsKey(x.sessionDate())) {
                aa.add(x);
                bb.add(bm.get(x.sessionDate()));
            }
        }
        return new Pair(List.copyOf(aa), List.copyOf(bb));
    }

    private boolean valid(NormalizedBarDto b) {
        return b != null && b.sessionDate() != null && b.close() != null && b.close().signum() > 0;
    }

    private Raw raw(List<NormalizedBarDto> s, List<NormalizedBarDto> q, int i) {
        BigDecimal sc = s.get(i).close();
        BigDecimal qc = q.get(i).close();
        BigDecimal s50 = sma(s, i, 50);
        BigDecimal q50 = sma(q, i, 50);
        BigDecimal s200 = sma(s, i, 200);
        BigDecimal q200 = sma(q, i, 200);
        BigDecimal ss200 = slope(s, i, 200);
        BigDecimal qs200 = slope(q, i, 200);
        BigDecimal sdd = drawdown252(s, i);
        BigDecimal qdd = drawdown252(q, i);

        boolean bothSma200Falling = ss200.signum() < 0 && qs200.signum() < 0;
        boolean bear = (sc.compareTo(s200) < 0 && qc.compareTo(q200) < 0 && bothSma200Falling)
                || (sdd.compareTo(BEAR_DRAWDOWN) <= 0 && qdd.compareTo(BEAR_DRAWDOWN) <= 0);
        boolean bothBelowMa50 = sc.compareTo(s50) < 0 && qc.compareTo(q50) < 0;
        boolean bothAboveRecoveryHold = sc.compareTo(s50.multiply(MA50_RECOVERY_HOLD_FACTOR, MC)) >= 0
                && qc.compareTo(q50.multiply(MA50_RECOVERY_HOLD_FACTOR, MC)) >= 0;
        boolean correction = sdd.compareTo(CORRECTION_DRAWDOWN) <= 0
                || qdd.compareTo(CORRECTION_DRAWDOWN) <= 0
                || bothBelowMa50;
        boolean bullStructure = s50.compareTo(s200) > 0
                && q50.compareTo(q200) > 0
                && ss200.signum() > 0
                && qs200.signum() > 0;
        boolean riskOn = bullStructure && sc.compareTo(s50) > 0 && qc.compareTo(q50) > 0;
        return new Raw(bear, bothSma200Falling, correction, bothBelowMa50, bothAboveRecoveryHold, bullStructure, riskOn, sdd, qdd);
    }

    private BigDecimal sma(List<NormalizedBarDto> x, int end, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = end - n + 1; i <= end; i++) {
            sum = sum.add(x.get(i).close(), MC);
        }
        return sum.divide(BigDecimal.valueOf(n), MC);
    }

    private BigDecimal slope(List<NormalizedBarDto> x, int end, int n) {
        BigDecimal now = sma(x, end, n);
        BigDecimal old = sma(x, end - 5, n);
        return now.divide(old, MC).subtract(BigDecimal.ONE, MC).multiply(BigDecimal.valueOf(100), MC).divide(BigDecimal.valueOf(5), MC);
    }

    private BigDecimal drawdown252(List<NormalizedBarDto> x, int end) {
        BigDecimal peak = BigDecimal.ZERO;
        for (int i = end - 251; i <= end; i++) {
            peak = peak.max(x.get(i).close());
        }
        return x.get(end).close().divide(peak, MC).subtract(BigDecimal.ONE, MC);
    }

    private record Pair(List<NormalizedBarDto> spy, List<NormalizedBarDto> qqq) {}

    private record Raw(
            boolean bear,
            boolean bothSma200Falling,
            boolean correction,
            boolean bothBelowMa50,
            boolean bothAboveRecoveryHold,
            boolean bullStructure,
            boolean riskOn,
            BigDecimal spyDd,
            BigDecimal qqqDd) {}
}
