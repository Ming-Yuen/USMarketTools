package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto.DcfBranchDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DcfValuationCalculatorTests {
    private static final LocalDate VAL_DATE = LocalDate.of(2026, 11, 19);
    private final DcfValuationCalculator calculator = new DcfValuationCalculator();

    @Test
    void matchesClosedFormAndCodexBenchmarkForTwoStageDcfAndReverseDcf() {
        // FCF0 = 100, shares = 10, r = 5.0% (10Y) + 5.0% (ERP) = 10.0%, g = 2.5%, h = 8.0%
        // Benchmark PV = 1849.694921 -> per share = 184.9695; Reverse DCF implied h = 8.0000%
        BigDecimal targetMarketCap = new BigDecimal("1849.694921");
        BigDecimal targetPrice = new BigDecimal("184.9694921");
        DcfValuationDto dto = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", targetPrice, targetMarketCap, new BigDecimal("10"),
                false, false, false, null,
                new BigDecimal("100"), new BigDecimal("80"),
                new BigDecimal("5.00"), VAL_DATE,
                new BigDecimal("0.0800"), null, null
        ));

        assertThat(dto.status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(dto.discountRate()).isEqualByComparingTo("0.100000");
        assertThat(dto.terminalGrowthRate()).isEqualByComparingTo("0.0250");

        DcfBranchDto std = dto.standardFcf();
        assertThat(std.status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(std.base().intrinsicPricePerShare().doubleValue()).isCloseTo(184.9695, within(0.0005));
        assertThat(std.conservative().intrinsicPricePerShare()).isLessThan(std.base().intrinsicPricePerShare());
        assertThat(std.optimistic().intrinsicPricePerShare()).isGreaterThan(std.base().intrinsicPricePerShare());

        assertThat(std.reverseDcf().status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(std.reverseDcf().impliedStage1GrowthRate().doubleValue()).isCloseTo(0.0800, within(0.0002));
        assertThat(std.reverseDcf().expectationStretched()).isFalse();

        // Closed-form check when h = g = 2.5%: PV = 100 * 1.025 / (0.10 - 0.025) = 1366.666667
        BigDecimal flatPv = DcfValuationCalculator.presentValueTwoStage(
                new BigDecimal("100"), new BigDecimal("0.0250"), new BigDecimal("0.1000"), new BigDecimal("0.0250"));
        assertThat(flatPv.doubleValue()).isCloseTo(1366.666667, within(0.0005));
    }

    @Test
    void separatesStandardAndSbcAdjustedBranchesAndDegradesGracefullyWhenAdjFcfNonPositive() {
        DcfValuationDto dto = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("150"), new BigDecimal("15000"), new BigDecimal("100"),
                false, false, false, null,
                new BigDecimal("500"), new BigDecimal("-50"),
                new BigDecimal("4.25"), VAL_DATE,
                new BigDecimal("0.1200"), new BigDecimal("0.1000"), new BigDecimal("0.1400")
        ));

        assertThat(dto.status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(dto.standardFcf().status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(dto.sbcAdjustedFcf().status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(dto.sbcAdjustedFcf().reasonCode()).isEqualTo("NON_POSITIVE_ADJ_FCF");
    }

    @Test
    void flagsMarketExpectationStretchedWhenImpliedCagrExceedsBenchmarkByOverFivePpt() {
        // Price implies high Stage 1 growth well above historical 8% benchmark
        DcfValuationDto dto = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("400"), new BigDecimal("4000"), new BigDecimal("10"),
                false, false, false, null,
                new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("4.50"), VAL_DATE,
                new BigDecimal("0.0800"), new BigDecimal("0.0800"), null
        ));

        assertThat(dto.standardFcf().reverseDcf().status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(dto.standardFcf().reverseDcf().impliedStage1GrowthRate()).isGreaterThan(new BigDecimal("0.15"));
        assertThat(dto.standardFcf().reverseDcf().expectationStretched()).isTrue();
    }

    @Test
    void returnsNotApplicableForNegativeFcfBankReitEtfAndOutOfRangeReverseDcf() {
        DcfValuationDto bank = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("100"),
                true, false, false, null,
                new BigDecimal("500"), new BigDecimal("500"),
                new BigDecimal("4.50"), VAL_DATE, null, null, null));
        assertThat(bank.status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(bank.reasonCode()).isEqualTo("DCF_NOT_APPLICABLE_TO_INDUSTRY");

        DcfValuationDto reit = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("100"),
                false, true, false, null,
                new BigDecimal("500"), new BigDecimal("500"),
                new BigDecimal("4.50"), VAL_DATE, null, null, null));
        assertThat(reit.status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(reit.reasonCode()).isEqualTo("DCF_NOT_APPLICABLE_TO_REIT");

        DcfValuationDto etf = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("100"),
                false, false, true, null,
                new BigDecimal("500"), new BigDecimal("500"),
                new BigDecimal("4.50"), VAL_DATE, null, null, null));
        assertThat(etf.status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(etf.reasonCode()).isEqualTo("NOT_APPLICABLE_FOR_ETF");

        DcfValuationDto negFcf = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("100"),
                false, false, false, null,
                new BigDecimal("-200"), new BigDecimal("-300"),
                new BigDecimal("4.50"), VAL_DATE, null, null, null));
        assertThat(negFcf.status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(negFcf.reasonCode()).isEqualTo("DCF_NON_POSITIVE_FCF");

        // Extreme valuation (P/FCF = 10000x) exceeds +100% Stage 1 CAGR search bound -> REVERSE_DCF_OUT_OF_RANGE
        DcfValuationDto extreme = calculator.calculate(new DcfValuationCalculator.Inputs(
                VAL_DATE, "USD", new BigDecimal("100000"), new BigDecimal("1000000"), new BigDecimal("10"),
                false, false, false, null,
                new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("5.00"), VAL_DATE, new BigDecimal("0.10"), null, null));
        assertThat(extreme.standardFcf().status()).isEqualTo(MetricStatus.AVAILABLE.name());
        assertThat(extreme.standardFcf().reverseDcf().status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(extreme.standardFcf().reverseDcf().reasonCode()).isEqualTo("REVERSE_DCF_OUT_OF_RANGE");
    }
}
