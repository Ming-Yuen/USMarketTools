package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.enums.FinancialItemMappingStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.FinancialReportItemMapping;
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
    void rawNamesAndStatementsStaySeparateAndPendingIsNotANewName() {
        List<FinancialReportItemMapping> mappings = List.of(mapping(1, "Diluted EPS", FinancialConcept.DILUTED_EPS),
                state(1, "Known pending", FinancialItemMappingStatus.PENDING), state(1, "Known skipped", FinancialItemMappingStatus.SKIPPED));
        FinancialReport first = report("A", 1, 2025, 7, "2025-12-30", "USD", item("Diluted EPS", "5"),
                item("Known pending", "10"), item("Known skipped", "20"));
        FinancialReport second = report("B", 2, 2025, 7, "2025-12-30", "USD", item("Diluted EPS", "7"), item("new name", null));

        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(first, second), mappings);

        assertThat(rows.getFirst().value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("5");
        assertThat(rows.getFirst().pendingNames()).containsExactly("Known pending");
        assertThat(rows.getFirst().unknownNames()).isEmpty();
        assertThat(rows.getLast().unknownNames()).containsExactly("Diluted EPS", "new name");
        assertThat(rows.getLast().unknownNames()).isNotEmpty();
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
        List<FinancialReportItemMapping> mappings = List.of(mapping(1, "EPS", FinancialConcept.DILUTED_EPS),
                mapping(1, "Alias", FinancialConcept.DILUTED_EPS), mapping(1, "Third", FinancialConcept.DILUTED_EPS));
        FinancialReport same = report("A", 1, 2025, 7, "2025-12-30", "USD", item("EPS", "5"), item("Alias", "5.00"));
        FinancialReport conflict = report("B", 1, 2025, 7, "2025-12-30", "USD", item("EPS", "5"), item("Alias", "6"), item("Third", "5"));

        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(same, conflict), mappings);

        assertThat(rows.getFirst().value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("5");
        assertThat(rows.getLast().value(FinancialConcept.DILUTED_EPS).status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertReason(rows.getLast().value(FinancialConcept.DILUTED_EPS), "FINANCIAL_CONCEPT_VALUE_CONFLICT");
    }

    @Test
    void nullIsNotZeroAndKnownAliasCanSupplyTheSameConcept() {
        List<FinancialReportItemMapping> mappings = List.of(mapping(1, "EPS", FinancialConcept.DILUTED_EPS), mapping(1, "Alias", FinancialConcept.DILUTED_EPS));
        FinancialReport empty = report("A", 1, 2025, 7, "2025-12-30", "USD", item("EPS", null));
        FinancialReport alias = report("B", 1, 2025, 7, "2025-12-30", "USD", item("EPS", null), item("Alias", "0"));

        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(empty, alias), mappings);

        assertThat(rows.getFirst().value(FinancialConcept.DILUTED_EPS).value()).isNull();
        assertThat(rows.getLast().value(FinancialConcept.DILUTED_EPS).value()).isEqualByComparingTo("0");
        assertThat(rows.getLast().value(FinancialConcept.DILUTED_EPS).status()).isEqualTo(MetricStatus.AVAILABLE);
    }

    @Test
    void reportedAnnualEpsWinsOverQuarterSumAndQuarterOnlyTtmRemainsUsable() {
        List<FinancialReport> reports = epsQuarters();
        reports.add(report("A", 1, 2025, 7, "2025-09-26", "USD", item("EPS", "7.46")));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(reports, List.of(mapping(1, "EPS", FinancialConcept.DILUTED_EPS)));

        MetricResult<BigDecimal> annual = calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.DILUTED_EPS, LocalDate.parse("2025-09-26"), "USD");

        assertThat(annual.value()).isEqualByComparingTo("7.46");
        List<FinancialReportValuationCalculator.ReportValues> quarterOnly = rows.stream().filter(row -> row.periodType() != 7).toList();
        assertThat(calculator.trailingTwelveMonths(quarterOnly, "A", 1, FinancialConcept.DILUTED_EPS,
                LocalDate.parse("2025-09-26"), "USD").value()).isEqualByComparingTo("7.47");
        assertThat(calculator.trailingBasisFromDate(quarterOnly, "A", 1, LocalDate.parse("2025-09-26"))).isEqualTo(LocalDate.parse("2024-12-27"));
    }

    @Test
    void recentQuarterTtmUsesFourConsecutiveReportedDilutedEps() {
        List<FinancialReport> reports = List.of(report("A", 1, 2025, 4, "2025-12-30", "USD", item("EPS", "4.63")),
                report("A", 1, 2025, 7, "2025-12-30", "USD", item("EPS", "20.02")),
                report("A", 1, 2026, 1, "2026-03-30", "USD", item("EPS", "5.94")),
                report("A", 1, 2026, 2, "2026-06-29", "USD", item("EPS", "7.70")),
                report("A", 1, 2026, 3, "2026-09-29", "USD", item("EPS", "5.57")));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(reports, List.of(mapping(1, "EPS", FinancialConcept.DILUTED_EPS)));
        assertThat(calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.DILUTED_EPS,
                LocalDate.parse("2026-09-29"), "USD").value()).isEqualByComparingTo("23.84");
    }

    @Test
    void annualMissingValueDoesNotSilentlyFallBackAndBalancesAreNotTtmFlows() {
        FinancialReport annual = report("A", 1, 2025, 7, "2025-09-26", "USD", item("EPS", null));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(annual), List.of(mapping(1, "EPS", FinancialConcept.DILUTED_EPS)));
        assertReason(calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.DILUTED_EPS,
                LocalDate.parse("2025-09-26"), "USD"), "FINANCIAL_INPUT_MISSING");
        assertReason(calculator.trailingTwelveMonths(rows, "A", 1, FinancialConcept.STOCKHOLDERS_EQUITY,
                LocalDate.parse("2025-09-26"), "USD"), "NON_ADDITIVE_FINANCIAL_CONCEPT");
    }

    @Test
    void fourConsecutiveSingleQuarterCashFlowsSumAcrossFiscalYears() {
        List<FinancialReport> reports = List.of(flow(2025, 2, "2025-06-29", "20"), flow(2025, 3, "2025-09-29", "30"),
                flow(2025, 4, "2025-12-30", "40"), flow(2026, 1, "2026-03-30", "10"));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(reports, List.of(mapping(3, "CFO", FinancialConcept.OPERATING_CASH_FLOW)));

        assertThat(calculator.trailingTwelveMonths(rows, "A", 3, FinancialConcept.OPERATING_CASH_FLOW,
                LocalDate.parse("2026-03-30"), "USD").value()).isEqualByComparingTo("100");
        assertReason(calculator.trailingTwelveMonths(rows.subList(1, 4), "A", 3, FinancialConcept.OPERATING_CASH_FLOW,
                LocalDate.parse("2026-03-30"), "USD"), "TTM_QUARTERS_MISSING");
    }

    @Test
    void unknownNamesAreWarningsWhileCurrencyMismatchStillBlocksTtm() {
        FinancialReport annual = report("A", 3, 2025, 7, "2025-12-30", "EUR", item("CFO", "100"));
        List<FinancialReportValuationCalculator.ReportValues> rows = calculator.extract(List.of(annual), List.of(mapping(3, "CFO", FinancialConcept.OPERATING_CASH_FLOW)));
        assertReason(calculator.trailingTwelveMonths(rows, "A", 3, FinancialConcept.OPERATING_CASH_FLOW,
                LocalDate.parse("2025-12-30"), "USD"), "REPORTING_CURRENCY_MISMATCH");
        annual.setItems(List.of(item("CFO", "100"), item("new name", null)));
        rows = calculator.extract(List.of(annual), List.of(mapping(3, "CFO", FinancialConcept.OPERATING_CASH_FLOW)));
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
        return new ArrayList<>(List.of(report("A", 1, 2025, 1, "2024-12-27", "USD", item("EPS", "2.40")),
                report("A", 1, 2025, 2, "2025-03-28", "USD", item("EPS", "1.65")),
                report("A", 1, 2025, 3, "2025-06-27", "USD", item("EPS", "1.57")),
                report("A", 1, 2025, 4, "2025-09-26", "USD", item("EPS", "1.85"))));
    }

    @Test
    void commonEquityRequiresPreferredBookAmountAndEvRequiresItsMarketAmountWhenNonZero() {
        FinancialReportValuationCalculator.ReportValues balance = values(2, java.util.Map.of(
                FinancialConcept.STOCKHOLDERS_EQUITY, "500", FinancialConcept.PREFERRED_STOCK_CAPITAL, "20"));
        assertThat(calculator.commonEquity(balance).value()).isEqualByComparingTo("480");
        assertReason(calculator.enterpriseValue(decimal("1000"), balance), "PREFERRED_EQUITY_MARKET_VALUE_MISSING");
        assertReason(calculator.commonEquity(values(2, java.util.Map.of(FinancialConcept.STOCKHOLDERS_EQUITY, "500"))),
                "PREFERRED_EQUITY_BOOK_VALUE_MISSING");
    }

    @Test
    void commonEquityCanBeReconciledFromCommonComponentsWithoutAnAbsentPreferredLine() {
        java.util.Map<FinancialConcept, String> components = new java.util.EnumMap<>(FinancialConcept.class);
        components.put(FinancialConcept.STOCKHOLDERS_EQUITY, "107520000000");
        components.put(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "100702000000");
        components.put(FinancialConcept.RETAINED_EARNINGS, "11326000000");
        components.put(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED, "-4508000000");
        assertThat(calculator.commonEquity(values(2, components)).value()).isEqualByComparingTo("107520000000");
        components.put(FinancialConcept.RETAINED_EARNINGS, "1");
        assertReason(calculator.commonEquity(values(2, components)), "COMMON_EQUITY_COMPONENTS_MISMATCH");
    }

    @Test
    void commonEquityReconcilesSeparatePaidInCapitalWithoutDoubleCountingExistingCombinedCapital() {
        java.util.Map<FinancialConcept, String> components = new java.util.EnumMap<>(FinancialConcept.class);
        components.put(FinancialConcept.STOCKHOLDERS_EQUITY, "1113234000");
        components.put(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "31000");
        components.put(FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED, "542467000");
        components.put(FinancialConcept.RETAINED_EARNINGS, "573803000");
        components.put(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED, "-3067000");
        assertThat(calculator.commonEquity(values(2, components)).value()).isEqualByComparingTo("1113234000");

        components.put(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "542498000");
        assertThat(calculator.commonEquity(values(2, components)).value()).isEqualByComparingTo("1113234000");

        components.put(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "31000");
        components.put(FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED, "542467001");
        assertReason(calculator.commonEquity(values(2, components)), "COMMON_EQUITY_COMPONENTS_MISMATCH");
    }

    @Test
    void mappedFutuEquityComponentsReconcileThroughTheNormalExtractPath() {
        FinancialReport report = report("ACLS", 2, 2026, 3, "2026-09-30", "USD",
                item("Stockholders' Equity", "1113234000"), item("-Common Stock", "31000"),
                item("Additional Paid-In Capital", "542467000"), item("Retained Earnings", "573803000"),
                item("Gains/Losses Not Affecting Retained Earnings", "-3067000"));
        List<FinancialReportItemMapping> catalog = List.of(
                mapping(2, "Stockholders' Equity", FinancialConcept.STOCKHOLDERS_EQUITY),
                mapping(2, "-Common Stock", FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED),
                mapping(2, "Additional Paid-In Capital", FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED),
                mapping(2, "Retained Earnings", FinancialConcept.RETAINED_EARNINGS),
                mapping(2, "Gains/Losses Not Affecting Retained Earnings", FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED));
        FinancialReportValuationCalculator.ReportValues extracted = calculator.extract(List.of(report), catalog).getFirst();
        assertThat(calculator.commonEquity(extracted).value()).isEqualByComparingTo("1113234000");
    }

    @Test
    void commonEquityReconcilesTreasuryStockAsAContraEquityComponent() {
        java.util.Map<FinancialConcept, String> components = new java.util.EnumMap<>(FinancialConcept.class);
        components.put(FinancialConcept.STOCKHOLDERS_EQUITY, "558400000");
        components.put(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "100000");
        components.put(FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED, "1267800000");
        components.put(FinancialConcept.RETAINED_EARNINGS, "-214000000");
        components.put(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED, "-392800000");
        components.put(FinancialConcept.TREASURY_STOCK_AS_REPORTED, "102700000");
        assertThat(calculator.commonEquity(values(2, components)).value()).isEqualByComparingTo("558400000");

        components.put(FinancialConcept.TREASURY_STOCK_AS_REPORTED, "112700000");
        assertReason(calculator.commonEquity(values(2, components)), "COMMON_EQUITY_COMPONENTS_MISMATCH");
    }

    @Test
    void disclosedNullPreferredCapitalIsNotTheSameAsAnAbsentLine() {
        FinancialReportValuationCalculator.ReportValues base = values(2, java.util.Map.of(
                FinancialConcept.STOCKHOLDERS_EQUITY, "500", FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "300",
                FinancialConcept.RETAINED_EARNINGS, "220", FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED, "-20"));
        java.util.Map<FinancialConcept, MetricResult<BigDecimal>> inputs = new java.util.EnumMap<>(base.values());
        inputs.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto.of("FINANCIAL_INPUT_MISSING", "VALUATION", "null")));
        FinancialReportValuationCalculator.ReportValues disclosed = new FinancialReportValuationCalculator.ReportValues(base.figi(), 2,
                base.periodEnd(), base.periodType(), base.fiscalYear(), base.currency(), base.accountingStandards(), inputs, List.of(), List.of());
        assertReason(calculator.commonEquity(disclosed), "PREFERRED_EQUITY_BOOK_VALUE_MISSING");
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
    void onlyExplicitZeroLeasesPermitDebtAggregatesWithoutDoubleCounting() {
        java.util.Map<FinancialConcept, String> inputs = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, "0");
        inputs.put(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "0");
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        inputs.put(FinancialConcept.CURRENT_DEBT, "100");
        inputs.put(FinancialConcept.CURRENT_CAPITAL_LEASES, "20");
        inputs.put(FinancialConcept.LONG_TERM_DEBT, "200");
        inputs.put(FinancialConcept.LONG_TERM_CAPITAL_LEASES, "30");
        assertReason(calculator.enterpriseValue(decimal("1000"), values(2, inputs)), "EV_LEASE_SCOPE_UNRESOLVED");
        inputs.put(FinancialConcept.CURRENT_CAPITAL_LEASES, "0");
        inputs.put(FinancialConcept.LONG_TERM_CAPITAL_LEASES, "0");
        assertThat(calculator.enterpriseValue(decimal("1000"), values(2, inputs)).value()).isEqualByComparingTo("1250");
        inputs.put(FinancialConcept.CURRENT_DEBT_AND_LEASES, "100");
        inputs.put(FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "200");
        assertThat(calculator.enterpriseValue(decimal("1000"), values(2, inputs)).value()).isEqualByComparingTo("1250");
        inputs.remove(FinancialConcept.CURRENT_DEBT_AND_LEASES);
        inputs.remove(FinancialConcept.CURRENT_CAPITAL_LEASES);
        assertReason(calculator.enterpriseValue(decimal("1000"), values(2, inputs)), "EV_LEASE_SCOPE_UNRESOLVED");
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
    void ebitdaUsesOperatingProfitAndPrefersThePureTotalInTheSameReport() {
        java.util.Map<FinancialConcept, String> inputs = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.OPERATING_PROFIT, "80");
        inputs.put(FinancialConcept.INCOME_DEPRECIATION, "16");
        inputs.put(FinancialConcept.INCOME_AMORTIZATION, "5");
        inputs.put(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, "20");
        FinancialReportValuationCalculator.ReportValues income = values(1, inputs);
        assertThat(calculator.trailingEbitda(List.of(income), income).value()).isEqualByComparingTo("100");
        inputs.remove(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION);
        inputs.remove(FinancialConcept.INCOME_DEPRECIATION);
        inputs.put(FinancialConcept.CASH_FLOW_DEPRECIATION_AMORTIZATION_DEPLETION, "99");
        inputs.put(FinancialConcept.EBITDA_MARGIN, "99");
        income = values(1, inputs);
        assertReason(calculator.trailingEbitda(List.of(income), income), "FINANCIAL_INPUT_MISSING");
        inputs.put(FinancialConcept.INCOME_DEPRECIATION, "15");
        inputs.put(FinancialConcept.INCOME_AMORTIZATION, "5");
        income = values(1, inputs);
        assertThat(calculator.trailingEbitda(List.of(income), income).value()).isEqualByComparingTo("100");
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
    void redeemableMixedSecurityAmountCannotBeOmittedFromEvButDoesNotBlockOtherMultiples() {
        // AMRX 2026/Q3 的 8077 樣本金額；其餘輸入為合成資料，隔離資本橋接缺口。
        FinancialReportValuationCalculator.ReportValues balance = mixedSecurityBalance("109453000");
        MetricResult<BigDecimal> ev = calculator.enterpriseValue(decimal("1000"), balance);
        assertReason(ev, "EV_FINANCING_SCOPE_UNRESOLVED");
        assertThat(balance.unknownNames()).isEmpty();
        FinancialReportValuationCalculator.MarketMultiples multiples = calculator.calculate(inputs("10", "5",
                calculator.commonEquity(balance).value().toPlainString(), "1000", "50", null, "100", "USD"));
        assertThat(multiples.pe().value()).isEqualByComparingTo("20");
        assertThat(multiples.pb().value()).isEqualByComparingTo("2");
        assertThat(multiples.ps().value()).isEqualByComparingTo("1");
        assertThat(multiples.pfcf().value()).isEqualByComparingTo("20");
        assertThat(multiples.evEbitda().value()).isNull();
    }

    @Test
    void explicitlyZeroMixedSecurityDoesNotBlockEvButUnknownAmountIsNotZero() {
        assertThat(calculator.enterpriseValue(decimal("1000"), mixedSecurityBalance("0")).value()).isEqualByComparingTo("1200");
        assertReason(calculator.enterpriseValue(decimal("1000"), mixedSecurityBalance(null)), "EV_FINANCING_SCOPE_UNRESOLVED");
    }

    private FinancialReportValuationCalculator.ReportValues mixedSecurityBalance(String amount) {
        java.util.Map<FinancialConcept, String> inputs = java.util.Map.of(FinancialConcept.PREFERRED_STOCK_CAPITAL, "0",
                FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "0", FinancialConcept.STOCKHOLDERS_EQUITY, "500",
                FinancialConcept.CURRENT_DEBT_AND_LEASES, "100", FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "150",
                FinancialConcept.CURRENT_CAPITAL_LEASES, "0", FinancialConcept.LONG_TERM_CAPITAL_LEASES, "0",
                FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport.Item> items = new ArrayList<>();
        inputs.forEach((concept, value) -> {
            catalog.add(mapping(2, concept.name(), concept));
            items.add(item(concept.name(), value));
        });
        catalog.add(state(2, "Convertible preferred securities", FinancialItemMappingStatus.PENDING));
        items.add(new FinancialReport.Item(8077, "Convertible preferred securities", decimal(amount), null, null));
        return calculator.extract(List.of(report("A", 2, 2026, 3, "2026-09-29", "USD", items.toArray(FinancialReport.Item[]::new))), catalog).getFirst();
    }

    @Test
    void myrgAndLiiLeaseSamplesCannotBeCountedWhollyOrDroppedFromUsGaapEv() {
        for (java.util.Map<FinancialConcept, String> sample : List.of(
                leaseInputs("18377000", "4554000", "13823000", "85132000", "54483000", "30649000"),
                leaseInputs("333200000", "226000000", "107200000", "1437500000", "1093500000", "344000000"))) {
            assertReason(calculator.enterpriseValue(decimal("1000000000"), extractedBalance(sample, "US_GAAP")), "EV_LEASE_SCOPE_UNRESOLVED");
            java.util.Map<FinancialConcept, String> withoutAggregates = new java.util.EnumMap<>(sample);
            withoutAggregates.remove(FinancialConcept.CURRENT_DEBT_AND_LEASES);
            withoutAggregates.remove(FinancialConcept.LONG_TERM_DEBT_AND_LEASES);
            assertReason(calculator.enterpriseValue(decimal("1000000000"), extractedBalance(withoutAggregates, "US_GAAP")), "EV_LEASE_SCOPE_UNRESOLVED");
        }
    }

    @Test
    void unknownOrNonUsAccountingDoesNotUseTheUsGaapEvPolicy() {
        java.util.Map<FinancialConcept, String> inputs = leaseInputs("100", "100", "0", "200", "200", "0");
        for (String standard : new String[]{null, "NonUS_GAAP"}) {
            FinancialReportValuationCalculator.ReportValues balance = extractedBalance(inputs, standard);
            assertReason(calculator.enterpriseValue(decimal("1000"), balance), "EV_ACCOUNTING_STANDARD_UNCONFIRMED");
            assertThat(calculator.commonEquity(balance).value()).isEqualByComparingTo("500");
        }
    }

    @Test
    void zeroLeaseConfirmationIsRequiredAndNoUnidentifiedInvestmentIsDeductedAsCash() {
        java.util.Map<FinancialConcept, String> inputs = leaseInputs("100", "100", "0", "200", "200", "0");
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, "50");
        inputs.put(FinancialConcept.CASH_AND_SHORT_TERM_INVESTMENTS, "350");
        inputs.put(FinancialConcept.SHORT_TERM_INVESTMENTS, "300");
        inputs.put(FinancialConcept.TOTAL_INVESTMENTS_AS_REPORTED, "500");
        assertThat(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")).value()).isEqualByComparingTo("1250");
        inputs.remove(FinancialConcept.CURRENT_CAPITAL_LEASES);
        assertReason(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")), "EV_LEASE_SCOPE_UNRESOLVED");
        inputs.put(FinancialConcept.CURRENT_CAPITAL_LEASES, "-1");
        assertReason(calculator.enterpriseValue(decimal("1000"), extractedBalance(inputs, "US_GAAP")), "EV_LEASE_SCOPE_UNRESOLVED");
    }

    @Test
    void nonUsIncomeCannotSupplyEbitdaUnderTheUsGaapEvPolicy() {
        FinancialReport income = report("A", 1, 2025, 7, "2025-12-30", "USD", item("EPS", "5"));
        income.setAccountingStandards("NonUS_GAAP");
        List<FinancialReportValuationCalculator.ReportValues> values = calculator.extract(List.of(income), List.of(mapping(1, "EPS", FinancialConcept.DILUTED_EPS)));
        assertReason(calculator.trailingEbitda(values, values.getFirst()), "EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        assertThat(calculator.trailingTwelveMonths(values, "A", 1, FinancialConcept.DILUTED_EPS, income.getPeriodEnd(), "USD").value()).isEqualByComparingTo("5");
    }

    @Test
    void ssncSourceFcfAlreadyIncludesEquipmentAndSoftwareWithoutDeductingEitherAgain() {
        FinancialReport cash = report("A", 3, 2025, 7, "2025-12-30", "USD",
                new FinancialReport.Item(8015, "Operating Cash Flow", decimal("1744800000"), null, null),
                new FinancialReport.Item(8044, "Capital Expenditure", decimal("-221900000"), null, null),
                new FinancialReport.Item(8046, "Net PPE Purchase and Sale", decimal("-63000000"), null, null),
                new FinancialReport.Item(8072, "Free Cash Flow", decimal("1442100000"), null, null));
        List<FinancialReportValuationCalculator.ReportValues> financials = calculator.extract(List.of(cash), List.of(
                mapping(3, "Operating Cash Flow", FinancialConcept.OPERATING_CASH_FLOW), mapping(3, "Capital Expenditure", FinancialConcept.CAPITAL_EXPENDITURE),
                mapping(3, "Net PPE Purchase and Sale", FinancialConcept.NET_PPE_CASH_FLOW), mapping(3, "Free Cash Flow", FinancialConcept.FREE_CASH_FLOW)));
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
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport.Item> items = new ArrayList<>();
        inputs.forEach((concept, value) -> {
            catalog.add(mapping(2, concept.name(), concept));
            items.add(item(concept.name(), value));
        });
        FinancialReport balance = report("A", 2, 2025, 7, "2025-12-30", "USD", items.toArray(FinancialReport.Item[]::new));
        balance.setAccountingStandards(standard);
        return calculator.extract(List.of(balance), catalog).getFirst();
    }

    private FinancialReportValuationCalculator.ReportValues values(int type, java.util.Map<FinancialConcept, String> inputs) {
        java.util.Map<FinancialConcept, MetricResult<BigDecimal>> values = new java.util.EnumMap<>(FinancialConcept.class);
        inputs.forEach((concept, value) -> values.put(concept, MetricResult.available(decimal(value))));
        return new FinancialReportValuationCalculator.ReportValues("A", type, LocalDate.parse("2025-12-30"), 7, 2025, "USD", "US_GAAP", values, List.of(), List.of());
    }

    private FinancialReport flow(int year, int quarter, String date, String value) {
        return report("A", 3, year, quarter, date, "USD", item("CFO", value));
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

    private FinancialReportItemMapping mapping(int type, String name, FinancialConcept concept) {
        FinancialReportItemMapping mapping = state(type, name, FinancialItemMappingStatus.MAPPED);
        mapping.setFinancialConcept(concept);
        return mapping;
    }

    private FinancialReportItemMapping state(int type, String name, FinancialItemMappingStatus status) {
        FinancialReportItemMapping mapping = new FinancialReportItemMapping();
        mapping.setStatementType(type);
        mapping.setItemName(name);
        mapping.setMappingStatus(status);
        return mapping;
    }

    private FinancialReport.Item item(String name, String value) { return new FinancialReport.Item(1, name, decimal(value), null, null); }
    private BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private void assertReason(MetricResult<BigDecimal> result, String code) {
        assertThat(result.value()).isNull();
        assertThat(result.reasons()).extracting(reason -> reason.code()).contains(code);
    }
}
