package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator.SizingInputs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSizingCalculatorTests {
    private final PositionSizingCalculator calculator = new PositionSizingCalculator();

    private PortfolioRiskConfigDto defaultConfig() {
        return new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"),
                new BigDecimal("0.0100"),
                new BigDecimal("0.1500"),
                new BigDecimal("0.3000"),
                "USD"
        );
    }

    /** Scenario 1：高波動自動少買 — 兩檔同價位股票，止損距離為 2 倍時建議買入股數嚴格減半，且最大預估虧損嚴格 <= 1%。 */
    @Test
    void higherStopDistanceHalvesSuggestedSharesAndKeepsLossWithinRiskBudget() {
        PositionSizingDto lowVol = calculator.calculate(new SizingInputs(
                "USD", "TECH", new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("90"),
                true, false, MarketRegime.RISK_ON, false, defaultConfig(), BigDecimal.ZERO, BigDecimal.ZERO, 0));
        PositionSizingDto highVol = calculator.calculate(new SizingInputs(
                "USD", "TECH", new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("80"),
                true, false, MarketRegime.RISK_ON, false, defaultConfig(), BigDecimal.ZERO, BigDecimal.ZERO, 0));

        assertThat(lowVol.status()).isEqualTo(PositionSizingDto.STATUS_AVAILABLE);
        assertThat(lowVol.suggestedShares()).isEqualTo(100L);
        assertThat(lowVol.maxEstimatedLossAmount()).isEqualByComparingTo("1000.00");
        assertThat(lowVol.maxEstimatedLossPct()).isEqualByComparingTo("0.010000");

        assertThat(highVol.status()).isEqualTo(PositionSizingDto.STATUS_AVAILABLE);
        assertThat(highVol.suggestedShares()).isEqualTo(50L);
        assertThat(highVol.maxEstimatedLossAmount()).isEqualByComparingTo("1000.00");
        assertThat(highVol.maxEstimatedLossPct()).isLessThanOrEqualTo(new BigDecimal("0.010000"));
    }

    /** Scenario 2：強勢股回踩 1/3 試單且同產業持倉已達 28%（加入後超標 30%）→ 自動套用 1/3 並觸發 SECTOR_CONCENTRATION_WARNING。 */
    @Test
    void starterOneThirdSizingAndSectorConcentrationWarning() {
        PositionSizingDto sizing = calculator.calculate(new SizingInputs(
                "USD", "SEMI", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, true, MarketRegime.RISK_ON, false, defaultConfig(),
                BigDecimal.ZERO, new BigDecimal("28000.00"), 0));

        // 1/3 單檔上限 = 15,000 / 3 = 5,000 -> 50 股；1/3 風險上限 = 1,000 / (5 * 3) = 66 股 -> 取較小值 50 股
        assertThat(sizing.status()).isEqualTo(PositionSizingDto.STATUS_AVAILABLE);
        assertThat(sizing.scaleLabel()).isEqualTo("1/3");
        assertThat(sizing.suggestedShares()).isEqualTo(50L);
        assertThat(sizing.estimatedPositionAmount()).isEqualByComparingTo("5000.00");
        assertThat(sizing.sectorConcentrationWarning()).isTrue();
        assertThat(sizing.projectedSectorExposurePct()).isEqualByComparingTo("0.330000");
        assertThat(sizing.sizingTags()).contains(
                PositionSizingDto.TAG_TREND_PULLBACK_STARTER_ONE_THIRD,
                PositionSizingDto.TAG_SECTOR_CONCENTRATION_WARNING
        );
    }

    /** Scenario 3：合格 starter + NEUTRAL + VIX=25（macroCaution=true）→ 1/6 精確整數分母計算（25 股、名義金額 2,500、估損 125）。 */
    @Test
    void starterWithMacroCautionProducesExactOneSixthShares() {
        PositionSizingDto sizing = calculator.calculate(new SizingInputs(
                "USD", "SEMI", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, true, MarketRegime.NEUTRAL, true, defaultConfig(),
                BigDecimal.ZERO, BigDecimal.ZERO, 0));

        assertThat(sizing.scaleLabel()).isEqualTo("1/6");
        assertThat(sizing.scaleDenominator()).isEqualTo(6);
        assertThat(sizing.suggestedShares()).isEqualTo(25L);
        assertThat(sizing.estimatedPositionAmount()).isEqualByComparingTo("2500.00");
        assertThat(sizing.maxEstimatedLossAmount()).isEqualByComparingTo("125.00");
        assertThat(sizing.sizingTags()).contains(
                PositionSizingDto.TAG_TREND_PULLBACK_STARTER_ONE_THIRD,
                PositionSizingDto.TAG_MACRO_HEADWIND_HALF_SIZE
        );
    }

    /** Scenario 4：單檔上限包含既有部位、剛好 30% 不觸發集中度警告、CORRECTION 半倉與 BEAR/PANIC 暫緩開倉。 */
    @Test
    void existingHoldingCapExactSectorBoundaryAndMarketRegimeRules() {
        // 已持有同股 14,000，單檔上限 15,000 -> 最多新增 1,000（10 股），且加上 29,000 剛好等於 30,000（30%）不觸發警告
        PositionSizingDto capped = calculator.calculate(new SizingInputs(
                "USD", "TECH", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, false, MarketRegime.RISK_ON, false, defaultConfig(),
                new BigDecimal("14000.00"), new BigDecimal("29000.00"), 0));
        assertThat(capped.suggestedShares()).isEqualTo(10L);
        assertThat(capped.sectorConcentrationWarning()).isFalse();
        assertThat(capped.sizingTags()).contains(PositionSizingDto.TAG_EXISTING_HOLDING_CAPPED);
        assertThat(capped.sizingTags()).doesNotContain(PositionSizingDto.TAG_SECTOR_CONCENTRATION_WARNING);

        // CORRECTION + macroCaution 同時觸發只乘一次 1/2
        PositionSizingDto correction = calculator.calculate(new SizingInputs(
                "USD", "TECH", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                false, false, MarketRegime.CORRECTION, true, defaultConfig(),
                BigDecimal.ZERO, BigDecimal.ZERO, 0));
        assertThat(correction.scaleLabel()).isEqualTo("1/2");
        assertThat(correction.suggestedShares()).isEqualTo(75L);
        assertThat(correction.planActionable()).isFalse();
        assertThat(correction.sizingTags()).contains(
                PositionSizingDto.TAG_MACRO_HEADWIND_HALF_SIZE,
                PositionSizingDto.TAG_WAITING_TRIGGER_ESTIMATE
        );

        // BEAR / PANIC 暫緩開倉 -> PAUSED, 0 股；且未取整前的板塊市值 30,000.04 > 30,000（30%）仍須觸發集中度警告
        PositionSizingDto bear = calculator.calculate(new SizingInputs(
                "USD", "TECH", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, false, MarketRegime.BEAR, true, defaultConfig(),
                BigDecimal.ZERO, new BigDecimal("30000.04"), 0));
        assertThat(bear.status()).isEqualTo(PositionSizingDto.STATUS_PAUSED);
        assertThat(bear.suggestedShares()).isEqualTo(0L);
        assertThat(bear.sectorConcentrationWarning()).isTrue();
        assertThat(bear.sizingTags()).contains(
                PositionSizingDto.TAG_MARKET_REGIME_PAUSE_NEW_ENTRY,
                PositionSizingDto.TAG_SECTOR_CONCENTRATION_WARNING);

        // 預設最新日行情回退至前一交易日時，須帶獨立標籤 STALE_QUOTE_DATE_FALLBACK 且 planActionable = false
        PositionSizingDto staleFallback = calculator.calculate(new SizingInputs(
                "USD", "TECH", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, true, false, MarketRegime.RISK_ON, false, defaultConfig(),
                BigDecimal.ZERO, BigDecimal.ZERO, 0));
        assertThat(staleFallback.status()).isEqualTo(PositionSizingDto.STATUS_AVAILABLE);
        assertThat(staleFallback.planActionable()).isFalse();
        assertThat(staleFallback.sizingTags()).contains(PositionSizingDto.TAG_STALE_QUOTE_DATE_FALLBACK);
        assertThat(staleFallback.sizingTags()).doesNotContain(PositionSizingDto.TAG_WAITING_TRIGGER_ESTIMATE);
    }

    /** Scenario 5：嚴禁跨幣別自動匯兌 — 非 USD 或缺幣別不計算股數。 */
    @Test
    void nonUsdAndMissingCurrencyReturnNotApplicableWithoutFx() {
        PositionSizingDto hkd = calculator.calculate(new SizingInputs(
                "HKD", "TECH", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, false, MarketRegime.RISK_ON, false, defaultConfig(),
                BigDecimal.ZERO, BigDecimal.ZERO, 0));
        assertThat(hkd.status()).isEqualTo(PositionSizingDto.STATUS_NOT_APPLICABLE);
        assertThat(hkd.reasonCode()).isEqualTo("NON_USD_CURRENCY_NO_FX");
        assertThat(hkd.suggestedShares()).isNull();

        PositionSizingDto missing = calculator.calculate(new SizingInputs(
                null, "TECH", new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("95"),
                true, false, MarketRegime.RISK_ON, false, defaultConfig(),
                BigDecimal.ZERO, BigDecimal.ZERO, 0));
        assertThat(missing.status()).isEqualTo(PositionSizingDto.STATUS_NOT_APPLICABLE);
        assertThat(missing.reasonCode()).isEqualTo("CURRENCY_MISSING");
    }
}
