package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockSplit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FundamentalSnapshotCalculatorTests {
    private static final String FIGI = "BBG000B9XRY4";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 13);

    private final FundamentalSnapshotCalculator calculator = new FundamentalSnapshotCalculator();

    @Test
    void splitAlignedEpsAndSecSharesReportZeroGrowthAndZeroDilutionWhileReusedFilingIsRejected() {
        // 2 拆 1 執行日 2026-06-01：拆前 EPS = 8.00、股數 = 100；拆後 EPS = 4.00、股數 = 200 -> 真實成長 0%、真實股數變化 0%
        StockSplit twoForOne = new StockSplit();
        twoForOne.setFigi(FIGI);
        twoForOne.setSourceEventId("SPLIT-1");
        twoForOne.setExecutionDate(LocalDate.of(2026, 6, 1));
        twoForOne.setSplitFrom(BigDecimal.ONE);
        twoForOne.setSplitTo(BigDecimal.valueOf(2));

        StockFinancialPeriod p2025 = basePeriod(LocalDate.of(2025, 9, 27), "4000", "400", "8.00", "500", "600", "800", "100");
        p2025.setSecShareAccession("0000320193-25-000010");
        StockFinancialPeriod p2026 = basePeriod(LocalDate.of(2026, 9, 26), "4400", "400", "4.00", "550", "660", "900", "200");
        p2026.setSecShareAccession("0000320193-26-000010");

        FundamentalSnapshotDto aligned = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2025, p2026), p2026, List.of(twoForOne), DAY, "USD",
                new BigDecimal("100"), new BigDecimal("20000"), null,
                new BigDecimal("4.00"), null, new BigDecimal("25.00"), false, null));

        // 52 週零售曆（364 天間距）成功配對，拆股對齊後 EPS YoY = 0%、淨股數變化 = 0%
        assertThat(aligned.priorYearPeriodEnd()).isEqualTo(LocalDate.of(2025, 9, 27));
        assertThat(aligned.revenueGrowthYoy().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(aligned.revenueGrowthYoy().value()).isEqualByComparingTo("0.100000");
        assertThat(aligned.epsGrowthYoy().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(aligned.epsGrowthYoy().value()).isEqualByComparingTo("0.000000");
        assertThat(aligned.netShareChangeYoy().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(aligned.netShareChangeYoy().value()).isEqualByComparingTo("0.000000");
        // EPS 成長率 0% < 5% 門檻 -> Trailing PEG 標記 NOT_APPLICABLE，防極小分母爆炸
        assertThat(aligned.trailingPeg().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(aligned.trailingPeg().reasons().getFirst().code()).isEqualTo("EPS_GROWTH_BELOW_PEG_THRESHOLD");

        // 當後期沿用同一份舊 SEC 申報（sharesBasisDate 相同或 accession 相同）時，不得謊報 0% 稀釋
        p2026.setSharesBasisDate(LocalDate.of(2025, 9, 27));
        p2026.setSecShareAccession("0000320193-25-000010");
        FundamentalSnapshotDto reused = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2025, p2026), p2026, List.of(twoForOne), DAY, "USD",
                new BigDecimal("100"), new BigDecimal("20000"), null,
                new BigDecimal("4.00"), null, new BigDecimal("25.00"), false, null));
        assertThat(reused.netShareChangeYoy().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(reused.netShareChangeYoy().reasons().getFirst().code()).isEqualTo("SHARE_BASIS_PERIOD_NOT_INDEPENDENT");
    }

    @Test
    void financialInstitutionExemptsFcfAndLeverageMetrics() {
        StockFinancialPeriod prior = basePeriod(LocalDate.of(2025, 9, 30), "50000", "12000", "15.00", "-2000", "0", "90000", "800");
        StockFinancialPeriod curr = basePeriod(LocalDate.of(2026, 9, 30), "55000", "14000", "18.00", "-3000", "0", "100000", "780");

        FundamentalSnapshotDto bank = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(prior, curr), curr, List.of(), DAY, "USD",
                new BigDecimal("225"), new BigDecimal("175500"), null,
                new BigDecimal("18.00"), null, new BigDecimal("12.50"), true, null));

        assertThat(bank.financialInstitution()).isTrue();
        assertThat(bank.fcfGrowthYoy().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(bank.fcfMargin().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(bank.fcfConversion().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(bank.fcfYield().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(bank.netDebtToEbitda().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        // ROE 使用平均普通股權益 (90000 + 100000)/2 = 95000 -> 14000 / 95000 = 0.147368
        assertThat(bank.roe().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(bank.roe().value()).isEqualByComparingTo("0.147368");
        assertThat(bank.earningsYield().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(bank.earningsYield().value()).isEqualByComparingTo("0.080000");
    }

    @Test
    void negativeBookEquityNegativeFcfAndSwungToLossHandleSignsWithoutFalsePositives() {
        StockFinancialPeriod prior = basePeriod(LocalDate.of(2025, 9, 30), "1000", "150", "2.00", "120", "250", "-300", "100");
        StockFinancialPeriod curr = basePeriod(LocalDate.of(2026, 9, 30), "1100", "-80", "-0.80", "-110", "200", "-500", "100");
        curr.setTotalDebt(new BigDecimal("600"));
        curr.setTotalDebtStatus(MetricStatus.AVAILABLE.name());
        curr.setCashLikeAssets(new BigDecimal("100"));
        curr.setCashLikeAssetsStatus(MetricStatus.AVAILABLE.name());
        curr.setEvAdjustmentStatus(MetricStatus.AVAILABLE.name());

        FundamentalSnapshotDto dto = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(prior, curr), curr, List.of(), DAY, "USD",
                new BigDecimal("20"), new BigDecimal("2000"), null,
                new BigDecimal("-0.80"), "NON_POSITIVE_EPS", null, false, null));

        // 負普通股權益 -> ROE N.M.
        assertThat(dto.roe().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(dto.roe().reasons().getFirst().code()).isEqualTo("NON_POSITIVE_COMMON_EQUITY");
        // 由盈轉虧 -> SWUNG_TO_LOSS；FCF 由正轉負 -> FCF_TURNED_NEGATIVE
        assertThat(dto.epsGrowthYoy().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(dto.epsGrowthYoy().reasons().getFirst().code()).isEqualTo("SWUNG_TO_LOSS");
        assertThat(dto.fcfGrowthYoy().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(dto.fcfGrowthYoy().reasons().getFirst().code()).isEqualTo("FCF_TURNED_NEGATIVE");
        // 負淨利與負 FCF 不可相除得出假正轉換率，但負 FCF margin 與負 FCF yield 必須保留真實負值
        assertThat(dto.fcfConversion().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(dto.fcfMargin().value()).isEqualByComparingTo("-0.100000");
        assertThat(dto.fcfYield().value()).isEqualByComparingTo("-0.055000");
        assertThat(dto.earningsYield().value()).isEqualByComparingTo("-0.040000");
        // 淨負債 (600 - 100) / EBITDA 200 = 2.5000x
        assertThat(dto.netDebtToEbitda().value()).isEqualByComparingTo("2.5000");
    }

    @Test
    void trailingPegGuardsSingleYearLowBaseSpikeViaThreeYearCagrAndBlocksForeignCurrencyYield() {
        // 3 年前 EPS = 3.00，去年低基期 EPS = 1.60，今年 EPS = 3.993 -> 1Y YoY 暴衝 +149.56%，但 3Y CAGR 實為 +10.00%
        StockFinancialPeriod p2023 = basePeriod(LocalDate.of(2023, 9, 30), "1000", "300", "3.00", "250", "400", "1500", "100");
        StockFinancialPeriod p2025 = basePeriod(LocalDate.of(2025, 9, 30), "1100", "160", "1.60", "200", "350", "1700", "100");
        StockFinancialPeriod p2026 = basePeriod(LocalDate.of(2026, 9, 30), "1331", "399", "3.993", "320", "480", "2000", "100");

        FundamentalSnapshotDto dto = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2023, p2025, p2026), p2026, List.of(), DAY, "USD",
                new BigDecimal("79.86"), new BigDecimal("7986"), null,
                new BigDecimal("3.993"), null, new BigDecimal("20.00"), false, null));

        assertThat(dto.epsCagr3y().value()).isEqualByComparingTo("0.100000");
        // PE = 20，採 3Y CAGR 10% 計算 -> PEG = 20 / 10 = 2.0000（而非被單年 +149.56% 誤導成 0.13）
        assertThat(dto.trailingPeg().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(dto.trailingPeg().value()).isEqualByComparingTo("2.0000");
        assertThat(dto.pegGrowthBasis()).isEqualTo("TRAILING_EPS_CAGR_3Y");

        // 當交易幣別 USD 與財報幣別 CAD 不同時，殖利率必須攔截為 UNVERIFIED
        p2026.setReportingCurrency("CAD");
        FundamentalSnapshotDto fxMismatch = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2026), p2026, List.of(), DAY, "USD",
                new BigDecimal("10"), new BigDecimal("1000"), null,
                null, "REPORTING_CURRENCY_MISMATCH", null, false, null));
        assertThat(fxMismatch.fcfYield().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(fxMismatch.fcfYield().reasons().getFirst().code()).isEqualTo("REPORTING_CURRENCY_MISMATCH");
        assertThat(fxMismatch.earningsYield().status()).isEqualTo(MetricStatus.UNVERIFIED);
        // 若 EV 調整項因租賃／準則未確認為 UNVERIFIED，netDebtToEbitda 同步標記 UNVERIFIED
        p2026.setEvAdjustmentStatus(MetricStatus.UNVERIFIED.name());
        p2026.setEvAdjustmentReason(ValuationReason.EV_ACCOUNTING_STANDARD_UNCONFIRMED);
        FundamentalSnapshotDto evUnverified = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2026), p2026, List.of(), DAY, "CAD",
                new BigDecimal("10"), new BigDecimal("1000"), null,
                new BigDecimal("3.993"), null, new BigDecimal("2.50"), false, null));
        assertThat(evUnverified.netDebtToEbitda().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(evUnverified.netDebtToEbitda().reasons().getFirst().code()).isEqualTo("EV_ACCOUNTING_STANDARD_UNCONFIRMED");
    }

    @Test
    void computesHighSbcQualityMetricsRuleOf40AndShareholderYield() {
        StockFinancialPeriod p2025 = basePeriod(LocalDate.of(2025, 9, 30), "800", "50", "0.50", "160", "180", "1000", "100");
        p2025.setGrossProfitTtm(new BigDecimal("600"));
        p2025.setOperatingProfitTtm(new BigDecimal("120"));

        StockFinancialPeriod p2026 = basePeriod(LocalDate.of(2026, 9, 30), "1000", "80", "0.80", "200", "240", "1200", "100");
        p2026.setGrossProfitTtm(new BigDecimal("780"));
        p2026.setOperatingProfitTtm(new BigDecimal("180"));
        p2026.setSbcTtm(new BigDecimal("120"));
        p2026.setCashDividendsPaidTtm(new BigDecimal("20"));
        p2026.setNetCommonStockIssuanceTtm(new BigDecimal("-80")); // net repurchase 80
        p2026.setInterestExpenseTtm(new BigDecimal("30"));
        p2026.setOperatingCashFlowTtm(new BigDecimal("200"));
        p2026.setOperatingCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
        p2026.setCurrentDebt(new BigDecimal("100"));
        p2026.setGoodwillAndIntangibles(new BigDecimal("200"));

        // Market cap = 4000, EV adjustment = -100 -> EV = 3900
        FundamentalSnapshotDto dto = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2025, p2026), p2026, List.of(), DAY, "USD",
                new BigDecimal("40"), new BigDecimal("4000"), null,
                new BigDecimal("0.80"), null, new BigDecimal("50.00"), false, null));

        // SBC% Revenue = 120 / 1000 = 12%, Adj. FCF = 200 - 120 = 80, P/Adj. FCF = 4000 / 80 = 50x
        assertThat(dto.sbcToRevenue().value()).isEqualByComparingTo("0.120000");
        assertThat(dto.adjFcfTtm().value()).isEqualByComparingTo("80.0000");
        assertThat(dto.pToAdjFcf().value()).isEqualByComparingTo("50.0000");
        // Total Shareholder Yield = (20 + 80) / 4000 = 2.5%
        assertThat(dto.totalShareholderYield().value()).isEqualByComparingTo("0.025000");
        // Gross margin = 78% vs 75% (+0.030000 = +3.00 ppt); Operating margin = 18% vs 15% (+0.030000 = +3.00 ppt)
        assertThat(dto.grossMargin().value()).isEqualByComparingTo("0.780000");
        assertThat(dto.grossMarginYoyPpt().value()).isEqualByComparingTo("0.030000");
        assertThat(dto.operatingMargin().value()).isEqualByComparingTo("0.180000");
        assertThat(dto.operatingMarginYoyPpt().value()).isEqualByComparingTo("0.030000");
        assertThat(dto.marginCompressionConsecutive()).isFalse();
        // EV/Sales = 3900 / 1000 = 3.9000, EV/Gross Profit = 3900 / 780 = 5.0000
        assertThat(dto.evToSales().value()).isEqualByComparingTo("3.9000");
        assertThat(dto.evToGrossProfit().value()).isEqualByComparingTo("5.0000");
        // Rule of 40 = Rev Growth (0.25) + FCF Margin (0.20) = 0.450000 (45%)
        assertThat(dto.ruleOf40().value()).isEqualByComparingTo("0.450000");
        // TBV = 1200 - 200 = 1000 -> P/TBV = 4000 / 1000 = 4.0000
        assertThat(dto.tangibleBookValue().value()).isEqualByComparingTo("1000.0000");
        assertThat(dto.pToTbv().value()).isEqualByComparingTo("4.0000");
        // Interest Coverage = 180 / 30 = 6.0000x; Short-term liquidity = (300 + 200) / 100 = 5.0000x
        assertThat(dto.interestCoverage().value()).isEqualByComparingTo("6.0000");
        assertThat(dto.shortTermLiquidityCoverage().value()).isEqualByComparingTo("5.0000");
    }

    @Test
    void computesRoicOnNegativeEquityBlueChipAndDetectsConsecutiveMarginCompressionAndFxAdrYield() {
        // Negative-equity blue chip (like MCD/SBUX): Equity = -500, Debt = 2000, Cash = 300 -> Invested Capital = 1200 > 0
        StockFinancialPeriod p2025Q2 = basePeriod(LocalDate.of(2025, 6, 30), "1000", "200", "2.00", "220", "320", "-400", "100");
        p2025Q2.setGrossProfitTtm(new BigDecimal("600"));
        p2025Q2.setOperatingProfitTtm(new BigDecimal("300"));

        StockFinancialPeriod p2025Q3 = basePeriod(LocalDate.of(2025, 9, 30), "1000", "200", "2.00", "220", "320", "-450", "100");
        p2025Q3.setGrossProfitTtm(new BigDecimal("600"));
        p2025Q3.setOperatingProfitTtm(new BigDecimal("300"));

        p2025Q3.setTotalDebt(new BigDecimal("2000"));
        p2025Q3.setCashLikeAssets(new BigDecimal("350"));
        p2025Q3.setEvAdjustment(new BigDecimal("1650")); // prior IC = -450 + 1650 = 1200

        StockFinancialPeriod p2026Q1 = basePeriod(LocalDate.of(2026, 3, 31), "1000", "190", "1.90", "210", "310", "-460", "100");
        p2026Q1.setGrossProfitTtm(new BigDecimal("590")); // 59%
        p2026Q1.setOperatingProfitTtm(new BigDecimal("280")); // 28%

        StockFinancialPeriod p2026Q2 = basePeriod(LocalDate.of(2026, 6, 30), "1000", "180", "1.80", "200", "300", "-480", "100");
        p2026Q2.setGrossProfitTtm(new BigDecimal("570")); // 57% (-2 ppt QoQ)
        p2026Q2.setOperatingProfitTtm(new BigDecimal("260")); // 26% (-2 ppt QoQ)

        StockFinancialPeriod p2026Q3 = basePeriod(LocalDate.of(2026, 9, 30), "1000", "170", "1.70", "190", "290", "-500", "100");
        p2026Q3.setGrossProfitTtm(new BigDecimal("550")); // 55% (-2 ppt QoQ, 2nd consecutive quarter!)
        p2026Q3.setOperatingProfitTtm(new BigDecimal("240")); // 24% (-2 ppt QoQ)
        p2026Q3.setPretaxProfitTtm(new BigDecimal("200"));
        p2026Q3.setIncomeTaxExpenseTtm(new BigDecimal("40")); // 20% effective tax rate
        p2026Q3.setTotalDebt(new BigDecimal("2000"));
        p2026Q3.setCashLikeAssets(new BigDecimal("300"));
        p2026Q3.setEvAdjustment(new BigDecimal("1700")); // current IC = -500 + 1700 = 1200
        p2026Q3.setInterestExpenseTtm(new BigDecimal("100")); // coverage = 2.4x < 3x
        p2026Q3.setGoodwillAndIntangibles(new BigDecimal("100"));

        FundamentalSnapshotDto dto = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(p2025Q2, p2025Q3, p2026Q1, p2026Q2, p2026Q3), p2026Q3, List.of(), DAY, "USD",
                new BigDecimal("50"), new BigDecimal("5000"), null,
                new BigDecimal("1.70"), null, new BigDecimal("29.41"), false, null));

        // Negative equity -> ROE N.M., P/TBV N.M., but ROIC is AVAILABLE!
        // NOPAT = 240 * (1 - 0.20) = 192; Invested Capital = 2000 + (-500) - 300 = 1200 -> ROIC = 192 / 1200 = 16%
        assertThat(dto.roe().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(dto.pToTbv().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(dto.nopat().value()).isEqualByComparingTo("192.0000");
        assertThat(dto.investedCapital().value()).isEqualByComparingTo("1200.0000");
        assertThat(dto.roic().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(dto.roic().value()).isEqualByComparingTo("0.160000");
        assertThat(dto.marginCompressionConsecutive()).isTrue();
        assertThat(dto.interestCoverage().value()).isEqualByComparingTo("2.4000");

        // FX-converted verified ADR uses company-level earningsYield = NetIncome_USD / MarketCap_USD
        StockFinancialPeriod twdPeriod = basePeriod(LocalDate.of(2026, 9, 30), "320000", "128000", "12.80", "96000", "160000", "640000", "10000");
        twdPeriod.setReportingCurrency("TWD");
        FundamentalSnapshotDto adrDto = calculator.calculate(new FundamentalSnapshotCalculator.Inputs(
                List.of(twdPeriod), twdPeriod, List.of(), DAY, "USD",
                new BigDecimal("200"), new BigDecimal("80000"), null,
                null, null, new BigDecimal("20.00"), false, null,
                new BigDecimal("0.03125"), null, true));
        // NetIncome_USD = 128000 * 0.03125 = 4000; MarketCap_USD = 80000 -> earningsYield = 4000 / 80000 = 5%
        // FCF_USD = 96000 * 0.03125 = 3000 -> fcfYield = 3000 / 80000 = 3.75%
        assertThat(adrDto.earningsYield().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(adrDto.earningsYield().value()).isEqualByComparingTo("0.050000");
        assertThat(adrDto.fcfYield().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(adrDto.fcfYield().value()).isEqualByComparingTo("0.037500");
    }

    private StockFinancialPeriod basePeriod(LocalDate end, String rev, String ni, String eps, String fcf, String ebitda, String equity, String shares) {
        StockFinancialPeriod p = new StockFinancialPeriod();
        p.setFigi(FIGI);
        p.setPeriodStart(end.minusMonths(3).plusDays(1));
        p.setPeriodEnd(end);
        p.setReportingCurrency("USD");
        p.setAccountingStandards("US_GAAP");
        p.setRevenueTtm(new BigDecimal(rev));
        p.setRevenueTtmStatus(MetricStatus.AVAILABLE.name());
        p.setNetIncomeCommonTtm(new BigDecimal(ni));
        p.setNetIncomeCommonTtmStatus(MetricStatus.AVAILABLE.name());
        p.setEpsTtm(new BigDecimal(eps));
        p.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
        p.setEpsShareBasisDate(end);
        p.setFreeCashFlowTtm(new BigDecimal(fcf));
        p.setFreeCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
        p.setEbitdaTtm(new BigDecimal(ebitda));
        p.setEbitdaTtmStatus(MetricStatus.AVAILABLE.name());
        p.setCommonEquity(new BigDecimal(equity));
        p.setCommonEquityStatus(MetricStatus.AVAILABLE.name());
        p.setSharesOutstanding(new BigDecimal(shares));
        p.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
        p.setSharesOutstandingSource("SEC_FILING_SHARES");
        p.setSharesBasisDate(end);
        p.setSecShareAccession("ACC-" + end);
        p.setTotalDebt(new BigDecimal("200"));
        p.setTotalDebtStatus(MetricStatus.AVAILABLE.name());
        p.setCashLikeAssets(new BigDecimal("300"));
        p.setCashLikeAssetsStatus(MetricStatus.AVAILABLE.name());
        p.setEvAdjustment(new BigDecimal("-100"));
        p.setEvAdjustmentStatus(MetricStatus.AVAILABLE.name());
        return p;
    }
}
