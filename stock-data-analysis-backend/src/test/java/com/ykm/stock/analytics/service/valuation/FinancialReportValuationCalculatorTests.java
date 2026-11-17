package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialReportValuationCalculatorTests {
    @Test
    void nonPositiveEbitdaIsNotApplicableEvenWhenEnterpriseValueIsMissing() {
        for (String amount : List.of("0", "-10")) {
            FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs("1000", "5", "500", "1000", "50", null, amount, "USD"));
            assertThat(result.evEbitda().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
            assertReason(result.evEbitda(), "NON_POSITIVE_EBITDA");
        }
    }

    @Test
    void nonPositiveRevenueNeverCreatesAvailablePs() {
        for (String amount : List.of("0", "-10")) {
            FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs("1000", "5", "500", amount, "50", "1200", "100", "USD"));
            assertThat(result.ps().value()).isNull();
            assertThat(result.ps().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
            assertReason(result.ps(), "NON_POSITIVE_REVENUE");
        }
    }

    private final FinancialReportValuationCalculator calculator = new FinancialReportValuationCalculator();

    @Test
    void rawNamesAndStatementsStaySeparateAndUnknownSubItemsAreIgnoredQuietly() {
        FinancialReport first = report("A", 1, 2025, 7, "2025-12-30", "USD", item("Diluted EPS", "5"),
                item("Known pending", "10"), item("Known skipped", "20"));
        FinancialReport second = report("B", 2, 2025, 7, "2025-12-30", "USD", item("Diluted EPS", "7"), item("new name", null));

        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(first, second));

        assertThat(rows.getFirst().value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("5");
        assertThat(rows.getFirst().unknownNames()).isEmpty();
        assertThat(rows.getLast().values()).doesNotContainKey(FinancialConcept.DILUTED_EPS);
        assertThat(rows.getLast().unknownNames()).isEmpty();
        assertThat(rows.getFirst().values()).doesNotContainKey(FinancialConcept.NET_PROFIT);
    }

    /** Scenario：靜態規則以 (statement_type, fieldId) 複合鍵優先匹配重號 ID（如 8046、8035、10072 缺名），無對應 fieldId 時退回英文名，未使用細項忽略不報警。 */
    @Test
    void staticExtractResolvesCollidingFieldIdsAcrossStatementsAndIgnoresUnusedItems() {
        FinancialReport income = report("A", 1, 2026, 7, "2026-09-26", "USD",
                new FinancialReport.Item(8046L, "Net Income to Common Stockholders", new BigDecimal("95000"), null, null),
                new FinancialReport.Item(8035L, "Tax", new BigDecimal("15000"), null, null),
                new FinancialReport.Item(9051L, "Diluted EPS", new BigDecimal("18.21"), null, null),
                new FinancialReport.Item(8007L, "Selling and Admin Expenses", new BigDecimal("5000"), null, null));
        FinancialReport balance = report("A", 2, 2026, 7, "2026-09-26", "USD",
                new FinancialReport.Item(8035L, "Financial Assets", new BigDecimal("12000"), null, null),
                new FinancialReport.Item(10072L, "", new BigDecimal("3000"), null, null),
                new FinancialReport.Item(0L, "Stockholders' Equity", new BigDecimal("60000"), null, null));
        FinancialReport cashFlow = report("A", 3, 2026, 7, "2026-09-26", "USD",
                new FinancialReport.Item(8046L, "Net PPE Purchase and Sale", new BigDecimal("-9000"), null, null),
                new FinancialReport.Item(10078L, "Free Cash Flow", new BigDecimal("85000"), null, null));

        List<FinancialReportValuationCalculator.ReportValues> extracted = calculator.extract(List.of(income, balance, cashFlow));

        assertThat(extracted.get(0).value(FinancialConcept.NET_INCOME_COMMON).value()).isEqualByComparingTo("95000");
        assertThat(extracted.get(0).value(FinancialConcept.INCOME_TAX_EXPENSE).value()).isEqualByComparingTo("15000");
        assertThat(extracted.get(0).value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("18.21");
        assertThat(extracted.get(0).unknownNames()).isEmpty();

        assertThat(extracted.get(1).values()).doesNotContainKey(FinancialConcept.INCOME_TAX_EXPENSE);
        assertThat(extracted.get(1).value(FinancialConcept.TREASURY_STOCK_AS_REPORTED).value()).isEqualByComparingTo("3000");
        assertThat(extracted.get(1).value(FinancialConcept.STOCKHOLDERS_EQUITY).value()).isEqualByComparingTo("60000");
        assertThat(extracted.get(1).unknownNames()).isEmpty();

        assertThat(extracted.get(2).value(FinancialConcept.NET_PPE_CASH_FLOW).value()).isEqualByComparingTo("-9000");
        assertThat(extracted.get(2).values()).doesNotContainKey(FinancialConcept.NET_INCOME_COMMON);
        assertThat(extracted.get(2).value(FinancialConcept.FREE_CASH_FLOW).value()).isEqualByComparingTo("85000");
    }

    @Test
    void equivalentAliasesAreNotAddedAndConflictsDoNotSelectAnArbitraryValue() {
        FinancialReport same = report("A", 1, 2025, 7, "2025-12-30", "USD",
                new FinancialReport.Item(8048L, "Diluted EPS", decimal("5"), null, null),
                new FinancialReport.Item(9051L, "Diluted EPS", decimal("5.00"), null, null));
        FinancialReport conflict = report("B", 1, 2025, 7, "2025-12-30", "USD",
                new FinancialReport.Item(8048L, "Diluted EPS", decimal("5"), null, null),
                new FinancialReport.Item(9051L, "Diluted EPS", decimal("6"), null, null),
                new FinancialReport.Item(10052L, "Diluted EPS", decimal("5"), null, null));

        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(same, conflict));

        assertThat(rows.getFirst().value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("5");
        assertThat(rows.getLast().value(FinancialConcept.DILUTED_EPS).status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertReason(rows.getLast().value(FinancialConcept.DILUTED_EPS), "FINANCIAL_CONCEPT_VALUE_CONFLICT");
    }

    @Test
    void nullIsNotZeroAndKnownAliasCanSupplyTheSameConcept() {
        FinancialReport empty = report("A", 1, 2025, 7, "2025-12-30", "USD", item("Diluted EPS", null));
        FinancialReport alias = report("B", 1, 2025, 7, "2025-12-30", "USD",
                item("Diluted EPS", null), new FinancialReport.Item(9051L, "Diluted EPS", decimal("0"), null, null));

        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(empty, alias));

        assertThat(rows.getFirst().value(FinancialConcept.DILUTED_EPS).value()).isNull();
        assertThat(rows.getLast().value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("0");
        assertThat(rows.getLast().value(FinancialConcept.DILUTED_EPS).status()).isEqualTo(MetricStatus.AVAILABLE);
    }

    @Test
    void reportedAnnualEpsWinsOverQuarterSumAndQuarterOnlyTtmRemainsUsable() {
        List<FinancialReport> reports = epsQuarters();
        reports.add(report("A", 1, 2025, 7, "2025-09-26", "USD", item("Diluted EPS", "7.46")));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(reports);

        MetricResult<BigDecimal> annual = calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.DILUTED_EPS, LocalDate.parse("2025-09-26"), "USD");

        assertThat(annual.value()).isEqualByComparingTo("7.46");
        List<FinancialReportValuationCalculator.ReportValues> quarterOnly = rows.stream().filter(row -> row.periodType() != 7).toList();
        assertThat(calculator.trailingTwelveMonths(quarterOnly, "A", 1, FinancialConcept.DILUTED_EPS,
                LocalDate.parse("2025-09-26"), "USD").value()).isEqualByComparingTo("7.47");
        assertThat(calculator.trailingBasisFromDate(quarterOnly, "A", 1, LocalDate.parse("2025-09-26"))).isEqualTo(LocalDate.parse("2024-12-27"));
    }

    @Test
    void recentQuarterTtmUsesFourConsecutiveReportedDilutedEps() {
        List<FinancialReport> reports = List.of(report("A", 1, 2025, 4, "2025-12-30", "USD", item("Diluted EPS", "4.63")),
                report("A", 1, 2025, 7, "2025-12-30", "USD", item("Diluted EPS", "20.02")),
                report("A", 1, 2026, 1, "2026-03-30", "USD", item("Diluted EPS", "5.94")),
                report("A", 1, 2026, 2, "2026-06-29", "USD", item("Diluted EPS", "7.70")),
                report("A", 1, 2026, 3, "2026-09-29", "USD", item("Diluted EPS", "5.57")));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(reports);
        assertThat(calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.DILUTED_EPS,
                LocalDate.parse("2026-09-29"), "USD").value()).isEqualByComparingTo("23.84");
    }

    @Test
    void annualMissingValueDoesNotSilentlyFallBackAndBalancesAreNotTtmFlows() {
        FinancialReport annual = report("A", 1, 2025, 7, "2025-09-26", "USD", item("Diluted EPS", null));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(annual));
        assertReason(calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.DILUTED_EPS,
                LocalDate.parse("2025-09-26"), "USD"), "FINANCIAL_INPUT_MISSING");
        assertReason(calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.STOCKHOLDERS_EQUITY,
                LocalDate.parse("2025-09-26"), "USD"), "NON_ADDITIVE_FINANCIAL_CONCEPT");
    }

    @Test
    void fourConsecutiveSingleQuarterCashFlowsSumAcrossFiscalYears() {
        List<FinancialReport> reports = List.of(flow(2025, 2, "2025-06-29", "20"), flow(2025, 3, "2025-09-29", "30"),
                flow(2025, 4, "2025-12-30", "40"), flow(2026, 1, "2026-03-30", "10"));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(reports);

        assertThat(calculator.trailingTwelveMonths(rows, "A", 3, FinancialConcept.OPERATING_CASH_FLOW,
                LocalDate.parse("2026-03-30"), "USD").value()).isEqualByComparingTo("100");
        assertReason(calculator.trailingTwelveMonths(rows.subList(1, 4), "A", 3, FinancialConcept.OPERATING_CASH_FLOW,
                LocalDate.parse("2026-03-30"), "USD"), "TTM_QUARTERS_MISSING");
    }

    @Test
    void unknownNamesAreWarningsWhileCurrencyMismatchStillBlocksTtm() {
        FinancialReport annual = report("A", 3, 2025, 7, "2025-12-30", "EUR", item("Operating Cash Flow", "100"));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(annual));
        assertReason(calculator.trailingTwelveMonths(rows, "A", 3, FinancialConcept.OPERATING_CASH_FLOW,
                LocalDate.parse("2025-12-30"), "USD"), "REPORTING_CURRENCY_MISMATCH");
        annual.setItems(List.of(item("Operating Cash Flow", "100"), item("new name", null)));
        rows = calculator.extract(List.of(annual));
        assertThat(calculator.trailingTwelveMonths(rows, "A", 3, FinancialConcept.OPERATING_CASH_FLOW, LocalDate.parse("2025-12-30"), "EUR").value()).isEqualByComparingTo("100");
    }

    @Test
    void suppliedAlignedInputsProduceFiveMultiplesForThePriceDate() {
        FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs("10", "5", "500", "1000", "50", "1200", "100", "USD"));
        assertThat(result.valuationDate()).isEqualTo(LocalDate.parse("2026-11-05"));
        assertThat(result.pe().value()).isEqualByComparingTo("20");
        assertThat(result.pb().value()).isEqualByComparingTo("2");
        assertThat(result.ps().value()).isEqualByComparingTo("1");
        assertThat(result.pfcf().value()).isEqualByComparingTo("20");
        assertThat(result.evEbitda().value()).isEqualByComparingTo("12");
    }

    @Test
    void missingSharesAndEvDoNotPreventIndependentPeOrGetInferredFromEps() {
        FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs(null, "5", "500", "1000", "50", null, "100", "USD"));
        assertThat(result.pe().value()).isEqualByComparingTo("20");
        assertReason(result.pb(), "MARKET_CAP_MISSING");
        assertReason(result.ps(), "MARKET_CAP_MISSING");
        assertReason(result.pfcf(), "MARKET_CAP_MISSING");
        assertReason(result.evEbitda(), "ENTERPRISE_VALUE_INPUTS_UNRESOLVED");
    }

    @Test
    void explicitlyProvidedMarketCapitalizationIsNotMultipliedByPriceAgain() {
        FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(new FinancialReportValuationCalculator.ValuationInputs(
                LocalDate.parse("2026-11-05"), decimal("100"), "USD", "USD", decimal("1000"), decimal("5"),
                decimal("500"), decimal("1000"), decimal("50"), decimal("1200"), decimal("100")));
        assertThat(result.pb().value()).isEqualByComparingTo("2");
        assertThat(result.ps().value()).isEqualByComparingTo("1");
        assertThat(result.pfcf().value()).isEqualByComparingTo("20");
    }

    @Test
    void missingOrInvalidPriceDoesNotBlockAnExplicitlyResolvedEnterpriseValueRatio() {
        for (BigDecimal price : new BigDecimal[]{null, BigDecimal.ZERO}) {
            FinancialReportValuationCalculator.ValuationInputs inputs = new FinancialReportValuationCalculator.ValuationInputs(
                    LocalDate.parse("2026-11-05"), price, "USD", "USD", decimal("10"), decimal("5"), decimal("500"),
                    decimal("1000"), decimal("50"), decimal("1200"), decimal("100"));
            FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs);
            assertReason(result.pe(), "CLOSING_PRICE_MISSING_OR_INVALID");
            assertReason(result.pb(), "CLOSING_PRICE_MISSING_OR_INVALID");
            assertThat(result.evEbitda().value()).isEqualByComparingTo("12");
        }
    }

    @Test
    void currencyAndZeroDenominatorsStayMissingAndNonPositiveFcfIsNotMeaningful() {
        FinancialReportValuationCalculator.MarketMultiples wrongCurrency = calculator.calculate(inputs("10", "5", "500", "1000", "50", "1200", "100", "EUR"));
        assertReason(wrongCurrency.pe(), "REPORTING_CURRENCY_MISMATCH");
        assertReason(wrongCurrency.pb(), "REPORTING_CURRENCY_MISMATCH");
        FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs("10", "-5", "500", "1000", "-50", "1200", "0", "USD"));
        assertReason(result.pe(), "NON_POSITIVE_EPS");
        assertReason(result.pfcf(), "NON_POSITIVE_FCF");
        assertReason(result.evEbitda(), "NON_POSITIVE_EBITDA");
    }

    private FinancialReportValuationCalculator.ValuationInputs inputs(String shares, String eps, String equity, String revenue,
                                                                       String fcf, String ev, String ebitda, String currency) {
        return new FinancialReportValuationCalculator.ValuationInputs(LocalDate.parse("2026-11-05"), decimal("100"), "USD", currency,
                shares == null ? null : decimal(shares).multiply(decimal("100")), decimal(eps), decimal(equity), decimal(revenue), decimal(fcf), decimal(ev), decimal(ebitda));
    }

    @Test
    void nonPositiveFcfHasNoMeaningfulMultipleAndDoesNotBlockOtherMetrics() {
        for (String fcf : List.of("-50", "0")) {
            FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs("10", "5", "500", "1000", fcf, "1200", "100", "USD"));
            assertThat(result.pfcf().value()).isNull();
            assertThat(result.pfcf().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
            assertReason(result.pfcf(), "NON_POSITIVE_FCF");
            assertThat(result.pe().value()).isEqualByComparingTo("20");
            assertThat(result.pb().value()).isEqualByComparingTo("2");
            assertThat(result.ps().value()).isEqualByComparingTo("1");
            assertThat(result.evEbitda().value()).isEqualByComparingTo("12");
            assertReason(calculator.calculate(inputs(null, "5", "500", "1000", fcf, null, "100", "USD")).pfcf(), "NON_POSITIVE_FCF");
        }
        assertReason(calculator.calculate(inputs("10", "5", "500", "1000", null, "1200", "100", "USD")).pfcf(), "FREE_CASH_FLOW_TTM_MISSING");
    }

    @Test
    void nonPositiveEpsUsesExistingPePolicyRatherThanProducingNegativePe() {
        for (String eps : List.of("-5", "0")) {
            FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(inputs("10", eps, "500", "1000", "50", null, null, "USD"));
            assertReason(result.pe(), "NON_POSITIVE_EPS");
        }
    }

    private List<FinancialReport> epsQuarters() {
        return new ArrayList<>(List.of(report("A", 1, 2025, 1, "2024-12-27", "USD", item("Diluted EPS", "2.40")),
                report("A", 1, 2025, 2, "2025-03-28", "USD", item("Diluted EPS", "1.65")),
                report("A", 1, 2025, 3, "2025-06-27", "USD", item("Diluted EPS", "1.57")),
                report("A", 1, 2025, 4, "2025-09-26", "USD", item("Diluted EPS", "1.85"))));
    }

    @Test
    void commonEquityDeductsPositivePreferredBookValueAndAcceptsStockholdersEquityDirectlyWhenPreferredAbsentOrZero() {
        FinancialReportValuationCalculator.ReportValues balance = values(2, java.util.Map.of(
                FinancialConcept.STOCKHOLDERS_EQUITY, "500", FinancialConcept.PREFERRED_STOCK_CAPITAL, "20",
                FinancialConcept.CURRENT_DEBT, "100", FinancialConcept.LONG_TERM_DEBT, "150",
                FinancialConcept.CASH_AND_EQUIVALENTS, "50"));
        assertThat(calculator.commonEquity(balance).value()).isEqualByComparingTo("480");
        // EV 以優先股帳面值（20）作標準近似，缺少數股東權益視為 0：1000 + (100+150) + 20 + 0 - 50 = 1220。
        assertThat(calculator.enterpriseValue(decimal("1000"), balance).value()).isEqualByComparingTo("1220");
        assertThat(calculator.commonEquity(values(2, java.util.Map.of(FinancialConcept.STOCKHOLDERS_EQUITY, "500"))).value())
                .isEqualByComparingTo("500");
    }

    @Test
    void commonEquityDoesNotRejectAuthoritativeStockholdersEquityOverSubcomponentRoundingOrMissingLines() {
        java.util.Map<FinancialConcept, String> components = new java.util.EnumMap<>(FinancialConcept.class);
        components.put(FinancialConcept.STOCKHOLDERS_EQUITY, "107520000000");
        components.put(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "100702000000");
        components.put(FinancialConcept.RETAINED_EARNINGS, "1");
        components.put(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED, "-4508000000");
        assertThat(calculator.commonEquity(values(2, components)).value()).isEqualByComparingTo("107520000000");
    }

    @Test
    void commonEquityFallbackUsesTotalEquityMinusMinorityWhenStockholdersEquityMissing() {
        java.util.Map<FinancialConcept, String> components = new java.util.EnumMap<>(FinancialConcept.class);
        components.put(FinancialConcept.TOTAL_EQUITY, "1100");
        components.put(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "100");
        components.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, "50");
        assertThat(calculator.commonEquity(values(2, components)).value()).isEqualByComparingTo("950");
    }

    @Test
    void mappedFutuEquityComponentsReconcileThroughTheNormalExtractPath() {
        FinancialReport report = report("ACLS", 2, 2026, 3, "2026-09-30", "USD",
                item("Stockholders' Equity", "1113234000"), item("-Common Stock", "31000"),
                item("Additional Paid-In Capital", "542467000"), item("Retained Earnings", "573803000"),
                item("Gains/Losses Not Affecting Retained Earnings", "-3067000"));
        FinancialReportValuationCalculator.ReportValues extracted = calculator.extract(List.of(report)).getFirst();
        assertThat(calculator.commonEquity(extracted).value()).isEqualByComparingTo("1113234000");
    }

    @Test
    void disclosedNullPreferredCapitalAcceptsStockholdersEquityWithoutBlocking() {
        FinancialReportValuationCalculator.ReportValues base = values(2, java.util.Map.of(
                FinancialConcept.STOCKHOLDERS_EQUITY, "500"));
        java.util.Map<FinancialConcept, MetricResult<BigDecimal>> inputs = new java.util.EnumMap<>(base.values());
        inputs.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto.of("FINANCIAL_INPUT_MISSING", "VALUATION", "null")));
        FinancialReportValuationCalculator.ReportValues disclosed = new FinancialReportValuationCalculator.ReportValues(base.figi(), 2,
                base.periodEnd(), base.periodType(), base.fiscalYear(), base.currency(), base.accountingStandards(), inputs, List.of(), List.of());
        assertThat(calculator.commonEquity(disclosed).value()).isEqualByComparingTo("500");
    }

    @Test
    void enterpriseValueUsesDisclosedMinorityBookValueAsDocumentedEstimate() {
        FinancialReportValuationCalculator.ReportValues balance = values(2, java.util.Map.of(
                FinancialConcept.PREFERRED_STOCK_CAPITAL, "0", FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "10",
                FinancialConcept.CURRENT_DEBT_AND_LEASES, "20", FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "30",
                FinancialConcept.CURRENT_CAPITAL_LEASES, "0", FinancialConcept.LONG_TERM_CAPITAL_LEASES, "0",
                FinancialConcept.CASH_AND_EQUIVALENTS, "5"));
        assertThat(calculator.enterpriseValue(decimal("1000"), balance).value()).isEqualByComparingTo("1055");
    }

    @Test
    void usGaapPureBorrowingsFallbackResolvesLeaseUnresolvedSamplesWhileUnsplitTotalWithoutPureDebtBlocks() {
        java.util.Map<FinancialConcept, String> inputs = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        inputs.put(FinancialConcept.CURRENT_DEBT, "100");
        inputs.put(FinancialConcept.CURRENT_CAPITAL_LEASES, "20");
        inputs.put(FinancialConcept.LONG_TERM_DEBT, "200");
        inputs.put(FinancialConcept.LONG_TERM_CAPITAL_LEASES, "30");
        // 有純借款（100 + 200）時直接採純借款計算 US_GAAP EV：1000 + 300 - 50 = 1250。
        assertThat(calculator.enterpriseValue(decimal("1000"), values(2, inputs)).value()).isEqualByComparingTo("1250");

        // 若僅有未拆分的含租賃債務總額且無純借款、無租賃拆分，維持 EV_LEASE_SCOPE_UNRESOLVED。
        java.util.Map<FinancialConcept, String> unsplitOnly = new java.util.EnumMap<>(FinancialConcept.class);
        unsplitOnly.put(FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        unsplitOnly.put(FinancialConcept.CURRENT_DEBT_AND_LEASES, "120");
        unsplitOnly.put(FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "230");
        assertReason(calculator.enterpriseValue(decimal("1000"), values(2, unsplitOnly)), "EV_LEASE_SCOPE_UNRESOLVED");
    }

    @Test
    void genericOtherLiabilitiesDoNotBlockOtherwiseIdentifiedBorrowings() {
        java.util.Map<FinancialConcept, String> inputs = leaseInputs("100", "100", "0", "200", "200", "0");
        inputs.put(FinancialConcept.OTHER_LIABILITIES_AS_REPORTED, "6500");
        assertThat(calculator.enterpriseValue(decimal("1000"), values(2, inputs)).value()).isEqualByComparingTo("1300");
        inputs.put(FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, "6500");
        assertReason(calculator.enterpriseValue(decimal("1000"), values(2, inputs)), "EV_FINANCING_SCOPE_UNRESOLVED");
    }

    @Test
    void usesTotalFinanceLeasesWithSeparatelyReportedBorrowings() {
        java.util.Map<FinancialConcept, String> inputs = leaseInputs("4391", "4391", null, "52809", "31090", "21719");
        inputs.put(FinancialConcept.CURRENT_OPERATING_LEASES, "5758");
        inputs.put(FinancialConcept.LONG_TERM_OPERATING_LEASES, "21719");
        inputs.put(FinancialConcept.TOTAL_FINANCE_LEASES, "70973");
        assertThat(calculator.enterpriseValue(decimal("1000000"), values(2, inputs)).value()).isEqualByComparingTo("1106454");
    }

    @Test
    void totalFinanceLeasesDoesNotDropCombinedDebtWhenPureBorrowingsAreUnreported() {
        java.util.Map<FinancialConcept, String> inputs = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.CURRENT_DEBT_AND_LEASES, "120");
        inputs.put(FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "230");
        inputs.put(FinancialConcept.CURRENT_OPERATING_LEASES, "20");
        inputs.put(FinancialConcept.LONG_TERM_OPERATING_LEASES, "30");
        inputs.put(FinancialConcept.TOTAL_FINANCE_LEASES, "10");
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        FinancialReportValuationCalculator.ReportValues balance = values(2, inputs);

        assertThat(calculator.totalDebt(balance).value()).isEqualByComparingTo("300");
        assertThat(calculator.enterpriseValue(decimal("1000"), balance).value()).isEqualByComparingTo("1250");
    }

    @Test
    void unresolvedFinanceCostDoesNotAffectOperatingEbitdaOrEps() {
        java.util.Map<FinancialConcept, String> amounts = new java.util.EnumMap<>(FinancialConcept.class);
        amounts.put(FinancialConcept.OPERATING_PROFIT, "80");
        amounts.put(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, "20");
        amounts.put(FinancialConcept.DILUTED_EPS, "5");
        FinancialReportValuationCalculator.ReportValues base = values(1, amounts);
        for (String value : new String[]{"0.708", "-0.708", null, "0"}) {
            FinancialReportValuationCalculator.ReportValues income = new FinancialReportValuationCalculator.ReportValues(base.figi(),
                    base.statementType(), base.periodEnd(), base.periodType(), base.fiscalYear(), base.currency(), base.accountingStandards(),
                    base.values(), List.of(), List.of(item("-Total Other Finance Cost", value)));
            assertThat(calculator.trailingEbitda(List.of(income), income).value()).isEqualByComparingTo("100");
            assertThat(calculator.trailingTwelveMonths(List.of(income), income.figi(), 1, FinancialConcept.DILUTED_EPS,
                    income.periodEnd(), income.currency()).value()).isEqualByComparingTo("5");
        }
    }

    @Test
    void ebitdaFallsBackToCashFlowStatementDdaWhenIncomeStatementLacksDepreciation() {
        java.util.Map<FinancialConcept, String> incomeInputs = new java.util.EnumMap<>(FinancialConcept.class);
        incomeInputs.put(FinancialConcept.OPERATING_PROFIT, "158063");
        FinancialReportValuationCalculator.ReportValues income = values(1, incomeInputs);

        java.util.Map<FinancialConcept, String> cashFlowInputs = new java.util.EnumMap<>(FinancialConcept.class);
        cashFlowInputs.put(FinancialConcept.CASH_FLOW_DEPRECIATION_AMORTIZATION_DEPLETION, "13423");
        FinancialReportValuationCalculator.ReportValues cashFlow = values(3, cashFlowInputs);

        assertThat(calculator.trailingEbitda(List.of(income, cashFlow), income).value()).isEqualByComparingTo("171486");
    }

    @Test
    void conflictingFinancingConceptCannotBeTreatedAsAbsentAndProduceEv() {
        java.util.Map<FinancialConcept, MetricResult<BigDecimal>> inputs = new java.util.EnumMap<>(values(2, java.util.Map.of(
                FinancialConcept.PREFERRED_STOCK_CAPITAL, "0", FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "0",
                FinancialConcept.CURRENT_DEBT_AND_LEASES, "10", FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "20",
                FinancialConcept.CASH_AND_EQUIVALENTS, "5")).values());
        inputs.put(FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, MetricResult.unavailable(MetricStatus.UNVERIFIED,
                com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto.of("FINANCIAL_CONCEPT_VALUE_CONFLICT", "VALUATION", "conflict")));
        FinancialReportValuationCalculator.ReportValues balance = new FinancialReportValuationCalculator.ReportValues("A", 2,
                LocalDate.parse("2025-12-30"), 7, 2025, "USD", "US_GAAP", inputs, List.of(), List.of());
        assertReason(calculator.enterpriseValue(decimal("1000"), balance), "FINANCIAL_CONCEPT_VALUE_CONFLICT");
    }

    @Test
    void convertiblePreferredAndDerivativePendingLinesDoNotBlockOperatingCompanyEv() {
        assertThat(calculator.enterpriseValue(decimal("1000"), mixedSecurityBalance("109453000")).value()).isEqualByComparingTo("1200");
        assertThat(calculator.enterpriseValue(decimal("1000"), mixedSecurityBalance("0")).value()).isEqualByComparingTo("1200");
    }

    private FinancialReportValuationCalculator.ReportValues mixedSecurityBalance(String amount) {
        java.util.Map<FinancialConcept, String> inputs = java.util.Map.of(FinancialConcept.PREFERRED_STOCK_CAPITAL, "0",
                FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "0", FinancialConcept.STOCKHOLDERS_EQUITY, "500",
                FinancialConcept.CURRENT_DEBT_AND_LEASES, "100", FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "150",
                FinancialConcept.CURRENT_CAPITAL_LEASES, "0", FinancialConcept.LONG_TERM_CAPITAL_LEASES, "0",
                FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        List<FinancialReport.Item> items = new ArrayList<>();
        inputs.forEach((concept, value) -> items.add(item(concept.name(), value)));
        items.add(new FinancialReport.Item(8077, "Convertible preferred securities", decimal(amount), null, null));
        return calculator.extract(List.of(report("A", 2, 2026, 3, "2026-09-29", "USD", items.toArray(FinancialReport.Item[]::new)))).getFirst();
    }

    @Test
    void myrgAndLiiLeaseSamplesResolveViaPureBorrowingsUnderUsGaapAndIncludeLeasesUnderIfrs() {
        java.util.Map<FinancialConcept, String> myrg = leaseInputs("18377000", "4554000", "13823000", "85132000", "54483000", "30649000");
        // US_GAAP 採純借款：1000M + 4,554,000 + 54,483,000 = 1,059,037,000
        assertThat(calculator.enterpriseValue(decimal("1000000000"), extractedBalance(myrg, "US_GAAP")).value())
                .isEqualByComparingTo("1059037000");
        // IFRS（含 Futu NonUS_GAAP）納入全部租賃負債：1000M + 18,377,000 + 85,132,000 = 1,103,509,000
        assertThat(calculator.enterpriseValue(decimal("1000000000"), extractedBalance(myrg, "IFRS")).value())
                .isEqualByComparingTo("1103509000");
        assertThat(calculator.enterpriseValue(decimal("1000000000"), extractedBalance(myrg, "NonUS_GAAP")).value())
                .isEqualByComparingTo("1103509000");
    }

    @Test
    void unknownAccountingStandardBlocksEvWhileIfrsAndUsGaapSucceedWhenConsistent() {
        java.util.Map<FinancialConcept, String> inputs = leaseInputs("120", "100", "20", "230", "200", "30");
        assertReason(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, null)), "EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        assertReason(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "")), "EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        assertThat(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")).value()).isEqualByComparingTo("1300");
        assertThat(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "IFRS")).value()).isEqualByComparingTo("1350");
        assertThat(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "NonUS_GAAP")).value()).isEqualByComparingTo("1350");
    }

    @Test
    void negativeLeaseBlocksWhileNoUnidentifiedInvestmentIsDeductedAsCash() {
        java.util.Map<FinancialConcept, String> inputs = leaseInputs("100", "100", "0", "200", "200", "0");
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        inputs.put(FinancialConcept.CASH_AND_SHORT_TERM_INVESTMENTS, "350");
        inputs.put(FinancialConcept.SHORT_TERM_INVESTMENTS, "300");
        inputs.put(FinancialConcept.TOTAL_INVESTMENTS_AS_REPORTED, "500");
        assertThat(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")).value()).isEqualByComparingTo("1250");
        inputs.remove(FinancialConcept.CURRENT_CAPITAL_LEASES);
        assertThat(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")).value()).isEqualByComparingTo("1250");
        inputs.put(FinancialConcept.CURRENT_CAPITAL_LEASES, "-1");
        assertReason(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")), "EV_LEASE_SCOPE_UNRESOLVED");
    }

    @Test
    void ifrsIncomeSuppliesEbitdaWhenConsistentAcrossTtmAndBlocksMixedStandards() {
        FinancialReport q1 = report("A", 1, 2025, 1, "2025-03-31", "USD",
                new FinancialReport.Item(8017L, "Operating Profit", decimal("50"), null, null),
                new FinancialReport.Item(8011L, "Depreciation & Amortization & Depletion", decimal("10"), null, null));
        FinancialReport q2 = report("A", 1, 2025, 2, "2025-06-30", "USD",
                new FinancialReport.Item(8017L, "Operating Profit", decimal("60"), null, null),
                new FinancialReport.Item(8011L, "Depreciation & Amortization & Depletion", decimal("12"), null, null));
        FinancialReport q3 = report("A", 1, 2025, 3, "2025-09-30", "USD",
                new FinancialReport.Item(8017L, "Operating Profit", decimal("70"), null, null),
                new FinancialReport.Item(8011L, "Depreciation & Amortization & Depletion", decimal("14"), null, null));
        FinancialReport q4 = report("A", 1, 2025, 4, "2025-12-31", "USD",
                new FinancialReport.Item(8017L, "Operating Profit", decimal("80"), null, null),
                new FinancialReport.Item(8011L, "Depreciation & Amortization & Depletion", decimal("16"), null, null));
        for (FinancialReport q : List.of(q1, q2, q3, q4)) q.setAccountingStandards("NonUS_GAAP");
        List<FinancialReportValuationCalculator.ReportValues> values = calculator.extract(List.of(q1, q2, q3, q4));
        assertThat(calculator.trailingEbitda(values, values.getLast()).value()).isEqualByComparingTo("312");

        // 若同一 TTM 期間混用 NonUS_GAAP（IFRS）與 US_GAAP，必須阻擋以避免口徑混算。
        q4.setAccountingStandards("US_GAAP");
        List<FinancialReportValuationCalculator.ReportValues> mixed = calculator.extract(List.of(q1, q2, q3, q4));
        assertReason(calculator.trailingEbitda(mixed, mixed.getLast()), "EV_ACCOUNTING_STANDARD_UNCONFIRMED");
    }

    @Test
    void ssncSourceFcfAlreadyIncludesEquipmentAndSoftwareWithoutDeductingEitherAgain() {
        FinancialReport cash = report("A", 3, 2025, 7, "2025-12-30", "USD",
                new FinancialReport.Item(8015, "Operating Cash Flow", decimal("1744800000"), null, null),
                new FinancialReport.Item(8044, "Capital Expenditure", decimal("-221900000"), null, null),
                new FinancialReport.Item(8046, "Net PPE Purchase and Sale", decimal("-63000000"), null, null),
                new FinancialReport.Item(8072, "Free Cash Flow", decimal("1442100000"), null, null));
        List<FinancialReportValuationCalculator.ReportValues> financials = calculator.extract(List.of(cash));
        BigDecimal fcf = calculator.trailingTwelveMonths(financials, "A", 3, FinancialConcept.FREE_CASH_FLOW, cash.getPeriodEnd(), "USD").value();
        assertThat(fcf).isEqualByComparingTo(decimal("1744800000").subtract(decimal("80800000")).subtract(decimal("221900000")));
        FinancialReportValuationCalculator.MarketMultiples result = calculator.calculate(new FinancialReportValuationCalculator.ValuationInputs(
                LocalDate.parse("2026-11-05"), decimal("100"), "USD", "USD", decimal("14421000000"), null, null, null, fcf, null, null));
        assertThat(result.pfcf().value()).isEqualByComparingTo("10");
    }

    private java.util.Map<FinancialConcept, String> leaseInputs(String currentTotal, String currentDebt, String currentLease,
                                                               String longTotal, String longDebt, String longLease) {
        java.util.Map<FinancialConcept, String> inputs = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, "0");
        inputs.put(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "0");
        inputs.put(FinancialConcept.STOCKHOLDERS_EQUITY, "500");
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, "0");
        inputs.put(FinancialConcept.CURRENT_DEBT_AND_LEASES, currentTotal);
        inputs.put(FinancialConcept.CURRENT_DEBT, currentDebt);
        inputs.put(FinancialConcept.CURRENT_CAPITAL_LEASES, currentLease);
        inputs.put(FinancialConcept.LONG_TERM_DEBT_AND_LEASES, longTotal);
        inputs.put(FinancialConcept.LONG_TERM_DEBT, longDebt);
        inputs.put(FinancialConcept.LONG_TERM_CAPITAL_LEASES, longLease);
        return inputs;
    }

    private FinancialReportValuationCalculator.ReportValues extractedBalance(java.util.Map<FinancialConcept, String> inputs, String standard) {
        List<FinancialReport.Item> items = new ArrayList<>();
        inputs.forEach((concept, value) -> items.add(item(concept.name(), value)));
        FinancialReport balance = report("A", 2, 2025, 7, "2025-12-30", "USD", items.toArray(FinancialReport.Item[]::new));
        balance.setAccountingStandards(standard);
        return calculator.extract(List.of(balance)).getFirst();
    }

    private FinancialReportValuationCalculator.ReportValues values(int type, java.util.Map<FinancialConcept, String> inputs) {
        java.util.Map<FinancialConcept, MetricResult<BigDecimal>> values = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.forEach((concept, value) -> values.put(concept, MetricResult.available(decimal(value))));
        return new FinancialReportValuationCalculator.ReportValues("A", type, LocalDate.parse("2025-12-30"), 7, 2025, "USD", "US_GAAP", values, List.of(), List.of());
    }

    private FinancialReport flow(int year, int quarter, String date, String value) {
        return report("A", 3, year, quarter, date, "USD", item("Operating Cash Flow", value));
    }

    private FinancialReport report(String figi, int type, int year, int periodType, String date, String currency, FinancialReport.Item... items) {
        FinancialReport report = new FinancialReport();
        report.setFigi(figi);
        report.setStatementType(type);
        report.setFiscalYear(year);
        report.setPeriodType(periodType);
        report.setPeriodEnd(LocalDate.parse(date));
        report.setCurrency(currency);
        report.setAccountingStandards("US_GAAP");
        report.setItems(List.of(items));
        return report;
    }

    private FinancialReport.Item item(String name, String value) { return new FinancialReport.Item(0, name, decimal(value), null, null); }
    private BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private void assertReason(MetricResult<BigDecimal> result, String code) {
        assertThat(result.value()).isNull();
        assertThat(result.reasons()).extracting(reason -> reason.code()).contains(code);
    }
}
