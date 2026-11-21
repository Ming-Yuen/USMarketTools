package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.service.analysis.calculator.MarketRegimeCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRegimeCalculatorTests {
    private final MarketRegimeCalculator calculator = new MarketRegimeCalculator();

    // 例子：SPY 與 QQQ 同向上升且無 VIX 時為 RISK_ON。
    @Test
    void risingSpyAndQqqAreRiskOn() {
        assertThat(calculator.classify(trend(false), trend(false), noVix()).value()).isEqualTo(MarketRegime.RISK_ON);
    }

    // 例子：SPY 與 QQQ 同向下跌且無 VIX 時為 BEAR。
    @Test
    void fallingSpyAndQqqAreBear() {
        assertThat(calculator.classify(trend(true), trend(true), noVix()).value()).isEqualTo(MarketRegime.BEAR);
    }

    // 例子：單一指數跌至前收 0.86 倍但無實際 VIX 時只是 CORRECTION。
    @Test
    void oneIndexCorrectionWithoutActualVixCannotBeCalledPanic() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        s.set(271, withClose(s.get(271), s.get(270).close().multiply(new BigDecimal("0.86"))));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.CORRECTION);
    }

    // 例子：指數跌至 0.86 倍且 VIX 為 40 時可判定 PANIC。
    @Test
    void actualVixPlusDrawdownCanDeclarePanic() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        s.set(271, withClose(s.get(271), s.get(270).close().multiply(new BigDecimal("0.86"))));
        assertThat(calculator.classify(s, q, MetricResult.available(new BigDecimal("40"))).value()).isEqualTo(MarketRegime.PANIC);
    }

    // 例子：指數曾跌至 0.82 倍後回升，在回到 RISK_ON 前先標 RECOVERY。
    @Test
    void recoveredIndexesWithRecentStressAreRecoveryBeforeRiskOn() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        s.set(260, withClose(s.get(260), s.get(259).close().multiply(new BigDecimal("0.82"))));
        q.set(260, withClose(q.get(260), q.get(259).close().multiply(new BigDecimal("0.82"))));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.RECOVERY);
    }

    // 例子：熊市反彈站上上彎 SMA50，但雙指數仍低於下彎 SMA200（如 2022-07/08），必須維持 BEAR 而非 RECOVERY。
    @Test
    void bearMarketRallyAboveRisingMa50WhileBelowFallingMa200RemainsBear() {
        List<NormalizedBarDto> s = trend(true);
        List<NormalizedBarDto> q = trend(true);
        // 末端 12 日短線反彈站上 SMA50（約 177 -> 拉升至 192），但仍遠低於下彎的 SMA200（約 212）且雙回撤逾 -20%
        for (int i = 260; i < 272; i++) {
            BigDecimal bounce = BigDecimal.valueOf(182 + (i - 260));
            s.set(i, withClose(s.get(i), bounce));
            q.set(i, withClose(q.get(i), bounce));
        }
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.BEAR);
    }

    // 例子：跌破 SMA50 進入 CORRECTION 後，單日或僅 +0.03% 微幅站上 SMA50 不得翻多；須連收 3 日且末日 >= +0.3% 緩衝才轉 RECOVERY。
    @Test
    void microPokeAboveMa50RemainsCorrectionUntilThreeDayBufferedReclaim() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        // i=268 雙雙跌破 SMA50（SMA50 約 221.75）觸發 CORRECTION
        s.set(268, withClose(s.get(268), new BigDecimal("218.00")));
        q.set(268, withClose(q.get(268), new BigDecimal("218.00")));
        // i=269..271 連收 3 日站上 SMA50，但第 3 日（i=271）QQQ 僅高於 SMA50 約 +0.03%（低於 +0.3% 緩衝）
        s.set(269, withClose(s.get(269), new BigDecimal("225.00")));
        q.set(269, withClose(q.get(269), new BigDecimal("223.00")));
        s.set(270, withClose(s.get(270), new BigDecimal("226.00")));
        q.set(270, withClose(q.get(270), new BigDecimal("223.20")));
        BigDecimal qMa50At271 = computeSma50WithLastClose(q, 271, new BigDecimal("223.00"));
        BigDecimal tinyPoke = qMa50At271.multiply(new BigDecimal("1.0003"));
        s.set(271, withClose(s.get(271), new BigDecimal("227.00")));
        q.set(271, withClose(q.get(271), tinyPoke));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.CORRECTION);

        // 當第 3 日收盤明確高於 SMA50 逾 +0.5% 時，正式轉為 RECOVERY
        q.set(271, withClose(q.get(271), qMa50At271.multiply(new BigDecimal("1.006"))));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.RECOVERY);
    }

    // 例子：多頭趨勢中單一指數微幅回測 SMA50（-0.3%，未跌破 -0.8% 保護帶），維持 RISK_ON 不反覆切回 NEUTRAL。
    @Test
    void shallowSingleIndexMa50TagInBullTrendStaysRiskOn() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        BigDecimal qMa50 = computeSma50WithLastClose(q, 271, new BigDecimal("223.00"));
        q.set(271, withClose(q.get(271), qMa50.multiply(new BigDecimal("0.997"))));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.RISK_ON);
    }

    // 例子：272 日裁切視窗與 400 日完整歷史序列判定結果必須 100% 一致（視窗不變性）。
    @Test
    void trailing272SliceMatchesLongerHistorySeries() {
        List<NormalizedBarDto> sLong = trendOfLength(400);
        List<NormalizedBarDto> qLong = trendOfLength(400);
        sLong.set(390, withClose(sLong.get(390), sLong.get(389).close().multiply(new BigDecimal("0.85"))));
        qLong.set(390, withClose(qLong.get(390), qLong.get(389).close().multiply(new BigDecimal("0.85"))));
        List<NormalizedBarDto> sSlice = sLong.subList(sLong.size() - MarketRegimeCalculator.REQUIRED_SESSIONS, sLong.size());
        List<NormalizedBarDto> qSlice = qLong.subList(qLong.size() - MarketRegimeCalculator.REQUIRED_SESSIONS, qLong.size());
        assertThat(calculator.classify(sSlice, qSlice, noVix()).value())
                .isEqualTo(calculator.classify(sLong, qLong, noVix()).value())
                .isEqualTo(MarketRegime.RECOVERY);
    }

    // 例子：2020-09-08 反例回歸——前段拋物線大漲後首次急殺達 -10.8% 回撤（即使仍收於上行 SMA50 之上），當日必須即時切入 CORRECTION，絕不可沿用跌前收復誤判為 RECOVERY。
    @Test
    void freshTenPercentDrawdownFromExtendedTopAboveRisingMa50EntersCorrectionImmediately() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        // 模擬 i=245..269 QQQ 急漲拉離 SMA50（峰值 290），隨後 i=270..271 急殺至 258（較峰值回撤 -11.03%，但仍高於約 232 的上彎 SMA50）
        for (int i = 245; i <= 269; i++) {
            BigDecimal meltUp = BigDecimal.valueOf(225).add(BigDecimal.valueOf(i - 245).multiply(new BigDecimal("2.6")));
            q.set(i, withClose(q.get(i), meltUp));
        }
        q.set(270, withClose(q.get(270), new BigDecimal("272.00")));
        q.set(271, withClose(q.get(271), new BigDecimal("258.00")));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.CORRECTION);
    }

    // 例子：2020-05-22 視窗左端邊界回歸——視窗起點（i=251）僅微高於 SMA50 +0.1%，但視窗內後續交易日已完成 +0.3% 三連收且未曾跌破 -0.5% 保護帶時，維持 RECOVERY 不退回 CORRECTION。
    @Test
    void recoveryPersistsWhenWindowStartWasBarelyAboveMa50ButSubsequentSessionsConfirmedReclaim() {
        List<NormalizedBarDto> s = trend(false);
        List<NormalizedBarDto> q = trend(false);
        // i=240 設高點 320 使視窗內維持 -10% 以上 252 日回撤（recentStress = true），但近 21 日全程站穩上行 SMA50
        q.set(240, withClose(q.get(240), new BigDecimal("320.00")));
        BigDecimal qMa50At251 = computeSma50WithLastClose(q, 251, new BigDecimal("214.00"));
        q.set(251, withClose(q.get(251), qMa50At251.multiply(new BigDecimal("1.001"))));
        assertThat(calculator.classify(s, q, noVix()).value()).isEqualTo(MarketRegime.RECOVERY);
    }

    private BigDecimal computeSma50WithLastClose(List<NormalizedBarDto> bars, int end, BigDecimal provisionalLastClose) {
        BigDecimal sum = provisionalLastClose;
        for (int i = end - 49; i < end; i++) {
            sum = sum.add(bars.get(i).close());
        }
        return sum.divide(BigDecimal.valueOf(50), MathContext.DECIMAL128);
    }

    private MetricResult<BigDecimal> noVix() {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("NO_VIX", "MARKET", "none"));
    }

    private List<NormalizedBarDto> trend(boolean down) {
        List<NormalizedBarDto> x = new ArrayList<>();
        for (int i = 0; i < 272; i++) {
            BigDecimal c = down
                    ? BigDecimal.valueOf(300).subtract(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5")))
                    : BigDecimal.valueOf(100).add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5")));
            LocalDate d = LocalDate.of(2025, 1, 1).plusDays(i);
            Instant t = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            x.add(new NormalizedBarDto(d, c, c, c, c, MetricResult.available(BigDecimal.ONE), t, t.plusSeconds(1), t.plusSeconds(2), t.plusSeconds(2), "Q" + i, "B"));
        }
        return x;
    }

    private List<NormalizedBarDto> trendOfLength(int length) {
        List<NormalizedBarDto> x = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            BigDecimal c = BigDecimal.valueOf(100).add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5")));
            LocalDate d = LocalDate.of(2024, 1, 1).plusDays(i);
            Instant t = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            x.add(new NormalizedBarDto(d, c, c, c, c, MetricResult.available(BigDecimal.ONE), t, t.plusSeconds(1), t.plusSeconds(2), t.plusSeconds(2), "Q" + i, "B"));
        }
        return x;
    }

    private NormalizedBarDto withClose(NormalizedBarDto b, BigDecimal c) {
        return new NormalizedBarDto(b.sessionDate(), c, c, c, c, b.volume(), b.sessionOpenAt(), b.sessionCloseAt(), b.availableAt(), b.observedAt(), b.versionRef(), b.basisId());
    }
}
