package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.ExecutionCostCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionCostCalculatorTests {
    // 例子：進場 200、止損 185、目標 250 時毛風險 15、毛報酬 50，淨風險報酬較低。
    @Test
    void exactGrossAndNetRiskRewardUseSharedFeeAndSlippageFormula() {
        ExecutionCostCalculator c = new ExecutionCostCalculator();
        MetricResult<RiskRewardDto> result = c.calculate(new BigDecimal("200"), new BigDecimal("185"),
                List.of(new TargetDto(new BigDecimal("250"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R", "target", List.of())));

        assertThat(result.status()).isEqualTo(MetricStatus.AVAILABLE);
        RiskRewardDto rr=result.value();
        assertThat(rr.grossRisk()).isEqualByComparingTo("15");
        assertThat(rr.grossReward()).isEqualByComparingTo("50");
        assertThat(rr.grossRR()).isEqualByComparingTo(new BigDecimal("50").divide(new BigDecimal("15"), java.math.MathContext.DECIMAL128));
        assertThat(rr.potentialUpsidePercent()).isEqualByComparingTo("25");
        assertThat(rr.potentialDownsidePercent()).isEqualByComparingTo("-7.5");
        assertThat(rr.netRR()).isLessThan(rr.grossRR());
        // 買入成本 200.3001，止損淨收入 184.7225925，目標淨收入 249.625125。
        assertThat(rr.netRisk()).isEqualByComparingTo("15.5775075");
        assertThat(rr.netReward()).isEqualByComparingTo("49.325025");
        assertThat(rr.netRR()).isEqualByComparingTo(new BigDecimal("49.325025").divide(new BigDecimal("15.5775075"), java.math.MathContext.DECIMAL128));
    }

    // 例子：目標權重 0.4 加 0.4 不等於 1，不得靜默正規化。
    @Test
    void targetWeightsMustEqualOneAndCannotBeSilentlyNormalized() {
        ExecutionCostCalculator c = new ExecutionCostCalculator();
        MetricResult<RiskRewardDto> result = c.calculate(new BigDecimal("100"), new BigDecimal("90"), List.of(
                new TargetDto(new BigDecimal("120"), new BigDecimal("0.4"), TargetAction.PARTIAL_SELL,"R1","",List.of()),
                new TargetDto(new BigDecimal("130"), new BigDecimal("0.4"), TargetAction.TAKE_PROFIT,"R2","",List.of())));
        assertThat(result.status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(result.value()).isNull();
    }
}
