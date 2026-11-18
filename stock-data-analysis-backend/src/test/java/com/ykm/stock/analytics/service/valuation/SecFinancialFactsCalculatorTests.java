package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.ReportValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class SecFinancialFactsCalculatorTests {
    @Test
    void historicalPriceSelectsItsActualFiscalQuarterAndFallsBackUntilThatQuarterArrives() {
        SecFinancialFact annual = fact("Revenues", "2023-10-01", "2024-09-28", "1000");
        SecFinancialFact first = fact("Revenues", "2024-09-29", "2024-12-28", "200");
        SecFinancialFact second = fact("Revenues", "2024-12-29", "2025-03-29", "300");
        List<SecFinancialFact> facts = List.of(annual, first, second);
        LocalDate priceDay = LocalDate.of(2024, 11, 12);
        SecFinancialFactsCalculator.PeriodSelection selected = calculator.selectPeriods(List.of(), facts, priceDay, NOW);
        assertThat(selected.facts()).containsExactly(annual, first);
        first.setObservedAt(NOW.plusSeconds(1));
        assertThat(calculator.selectPeriods(List.of(), facts, priceDay, NOW).facts()).containsExactly(annual);
        first.setObservedAt(NOW);
        assertThat(calculator.selectPeriods(List.of(), facts, priceDay, NOW).facts()).containsExactly(annual, first);
    }

    @Test
    void formerGaapBasicAndDilutedTagCanSupplyHistoricalDilutedEpsWithoutReplacingExplicitLatestTag() {
        SecFinancialFact combined = fact("EarningsPerShareBasicAndDiluted", "2016-01-01", "2016-12-31", "2.50");
        combined.setUnit("USD/shares");
        SecFinancialFactsCalculator.Flow result = calculate(List.of(combined)).dilutedEpsTtm();
        assertThat(result.value()).isEqualByComparingTo("2.50");
        assertThat(result.epsBasisFrom()).isEqualTo(LocalDate.of(2016, 12, 31));

        SecFinancialFact separate = fact("EarningsPerShareDiluted", "2016-01-01", "2016-12-31", "2.60");
        separate.setUnit("USD/shares");
        assertThat(calculate(List.of(combined, separate)).dilutedEpsTtm().value()).isEqualByComparingTo("2.60");
    }

    @Test
    void combinedLegacyEpsCannotBeDerivedFromYearToDateOrOverrideNewerIncompleteReportingPeriod() {
        SecFinancialFact previous = fact("EarningsPerShareBasicAndDiluted", "2025-01-01", "2025-12-31", "2.50");
        previous.setUnit("USD/shares");
        SecFinancialFact newest = fact("EarningsPerShareDiluted", "2026-07-01", "2026-09-30", "0.40");
        newest.setUnit("USD/shares");
        SecFinancialFactsCalculator.Flow result = calculate(List.of(previous, newest)).dilutedEpsTtm();
        assertThat(result.value()).isNull();
        assertThat(result.periodEnd()).isEqualTo(newest.getPeriodEnd());

        SecFinancialFact ytd = fact("EarningsPerShareBasicAndDiluted", "2026-01-01", "2026-09-30", "1.70");
        ytd.setUnit("USD/shares");
        assertThat(calculate(List.of(previous, ytd)).dilutedEpsTtm().value()).isNull();
    }

    @Test
    void legacyCombinedTagCanFillMatchingHistoricalFutuQuarterWithoutCrossingPeriods() {
        SecFinancialFact combined = fact("EarningsPerShareBasicAndDiluted", "2016-10-01", "2016-12-31", "0.12");
        combined.setUnit("USD/shares");
        ReportValues quarter = new ReportValues("TEST", 1, combined.getPeriodEnd(), 4, 2016,
                "USD", "US_GAAP", Map.of(), List.of(), List.of());
        SecFinancialFactsCalculator.Flows flows = calculator.calculate(List.of(combined), CIK, null, "USD", DAY, NOW, List.of(quarter));
        assertThat(flows.input(FinancialConcept.DILUTED_EPS, quarter).value()).isEqualByComparingTo("0.12");

        ReportValues differentQuarter = new ReportValues("TEST", 1, LocalDate.of(2017, 3, 31), 1, 2017,
                "USD", "US_GAAP", Map.of(), List.of(), List.of());
        assertThat(calculator.calculate(List.of(combined), CIK, null, "USD", DAY, NOW, List.of(differentQuarter))
                .input(FinancialConcept.DILUTED_EPS, differentQuarter).value()).isNull();
    }

    @Test
    void latestIncomeStatementSetsThePeriodEvenIfItsEpsAndRevenueAreUnavailable() {
        SecFinancialFact priorEps = fact("EarningsPerShareDiluted", "2025-01-01", "2025-12-31", "4");
        priorEps.setUnit("USD/shares");
        SecFinancialFact priorRevenue = fact("Revenues", "2025-01-01", "2025-12-31", "1000");
        SecFinancialFact netIncome = fact("NetIncomeLoss", "2026-07-01", "2026-09-30", "70");
        SecFinancialFactsCalculator.Flows result = calculate(List.of(priorEps, priorRevenue, netIncome));

        assertThat(result.dilutedEpsTtm().value()).isNull();
        assertThat(result.dilutedEpsTtm().periodEnd()).isEqualTo(netIncome.getPeriodEnd());
        assertThat(result.dilutedEpsTtm().reason()).isEqualTo("SEC_EPS_TTM_PERIODS_MISSING");
        assertThat(result.revenue().value()).isNull();
        assertThat(result.revenue().periodEnd()).isEqualTo(netIncome.getPeriodEnd());
        assertThat(result.revenue().reason()).isEqualTo("SEC_REVENUE_MISSING");
        assertThat(result.ebitda().periodEnd()).isEqualTo(netIncome.getPeriodEnd());
    }

    @Test
    void latestBalanceSheetDoesNotReuseOlderEquityOrEvButLeavesCashFlowIndependent() {
        String oldEnd = "2025-12-31";
        SecFinancialFact equity = fact("StockholdersEquity", null, oldEnd, "100");
        SecFinancialFact preferredShares = fact("PreferredStockSharesOutstanding", null, oldEnd, "0");
        preferredShares.setUnit("shares");
        preferredShares.setAccession(equity.getAccession());
        SecFinancialFact assets = fact("Assets", null, "2026-09-30", "120");
        List<SecFinancialFact> facts = List.of(equity, preferredShares, assets,
                fact("CashAndCashEquivalentsAtCarryingValue", null, oldEnd, "30"),
                fact("MinorityInterest", null, oldEnd, "1"), fact("DebtAndCapitalLeaseObligations", null, oldEnd, "50"),
                fact("NetCashProvidedByUsedInOperatingActivities", "2025-01-01", oldEnd, "200"),
                fact("PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", oldEnd, "20"));
        SecFinancialFactsCalculator.Flows result = calculate(facts);

        assertThat(result.equity().value()).isNull();
        assertThat(result.equity().periodEnd()).isEqualTo(assets.getPeriodEnd());
        assertThat(result.equity().reason()).isEqualTo("SEC_COMMON_EQUITY_UNRESOLVED");
        assertThat(result.enterpriseValueAdjustment().value()).isNull();
        assertThat(result.enterpriseValueAdjustment().periodEnd()).isEqualTo(assets.getPeriodEnd());
        assertThat(result.freeCashFlow().value()).isEqualByComparingTo("180");
        assertThat(result.freeCashFlow().periodEnd()).isEqualTo(LocalDate.parse(oldEnd));
    }

    @Test
    void softwareCapitalizationDetailAloneDoesNotAnchorOrConflictWithCompleteCapitalExpenditure() {
        String oldEnd = "2025-12-31";
        List<SecFinancialFact> facts = List.of(fact("NetCashProvidedByUsedInOperatingActivities", "2025-01-01", oldEnd, "200"),
                fact("PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", oldEnd, "20"),
                fact("PaymentsToDevelopSoftware", "2026-01-01", "2026-09-30", "7"));
        SecFinancialFactsCalculator.Flow fcf = calculate(facts).freeCashFlow();

        assertThat(fcf.value()).isEqualByComparingTo("180");
        assertThat(fcf.periodEnd()).isEqualTo(LocalDate.parse(oldEnd));
    }

    @Test
    void noNewerAnchorFromUnpublishedOrNonPeriodicDisclosure() {
        SecFinancialFact annual = fact("Revenues", "2025-01-01", "2025-12-31", "1000");
        SecFinancialFact notYetPublic = fact("NetIncomeLoss", "2026-07-01", "2026-09-30", "70");
        notYetPublic.setAvailableFromDate(DAY.plusDays(1));
        notYetPublic.setFiled(NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1));
        SecFinancialFact interimRegistration = fact("NetIncomeLoss", "2026-07-01", "2026-09-30", "70");
        interimRegistration.setForm("S-1");
        SecFinancialFact partialMonth = fact("NetIncomeLoss", "2026-08-01", "2026-08-31", "70");

        SecFinancialFactsCalculator.Flow revenue = calculate(List.of(annual, notYetPublic, interimRegistration, partialMonth)).revenue();

        assertThat(revenue.value()).isEqualByComparingTo("1000");
        assertThat(revenue.periodEnd()).isEqualTo(annual.getPeriodEnd());
    }

    @Test
    void separateCommonParAndPaidInCapitalReconcileToReportedParentEquityWithoutGuessingPreferred() {
        String end = "2026-09-30";
        List<SecFinancialFact> parts = List.of(
                fact("StockholdersEquity", null, end, "1186346000"),
                fact("CommonStockValue", null, end, "1211000"),
                fact("AdditionalPaidInCapitalCommonStock", null, end, "159795000"),
                fact("RetainedEarningsAccumulatedDeficit", null, end, "1095594000"),
                fact("AccumulatedOtherComprehensiveIncomeLossNetOfTax", null, end, "-56328000"),
                fact("TreasuryStockCommonValue", null, end, "13926000"));
        parts.forEach(part -> part.setAccession(parts.getFirst().getAccession()));
        assertThat(calculate(parts).equity().value()).isEqualByComparingTo("1186346000");

        List<SecFinancialFact> noTreasury = List.of(
                fact("StockholdersEquity", null, end, "215749000"),
                fact("CommonStockValue", null, end, "7000"),
                fact("AdditionalPaidInCapital", null, end, "966572000"),
                fact("RetainedEarningsAccumulatedDeficit", null, end, "-749834000"),
                fact("AccumulatedOtherComprehensiveIncomeLossNetOfTax", null, end, "-996000"));
        noTreasury.forEach(part -> part.setAccession(noTreasury.getFirst().getAccession()));
        assertThat(calculate(noTreasury).equity().value()).isEqualByComparingTo("215749000");
    }

    @Test
    void commonEquityDirectlyUsesStockholdersEquityWhenPreferredAbsentOrZeroAndDeductsPositivePreferredBookValue() {
        String end = "2026-09-30";
        SecFinancialFact parentOnly = fact("StockholdersEquity", null, end, "531451000");
        assertThat(calculate(List.of(parentOnly)).equity().value()).isEqualByComparingTo("531451000");

        SecFinancialFact preferred = fact("PreferredStockValue", null, end, "100");
        assertThat(calculate(List.of(parentOnly, preferred)).equity().value()).isEqualByComparingTo("531450900");
    }

    @Test
    void treasuryStockDoesNotRequireSubcomponentReconciliationToAcceptParentEquity() {
        String end = "2026-09-30";
        List<SecFinancialFact> facts = List.of(fact("StockholdersEquity", null, end, "35537"),
                fact("TreasuryStockCommonValue", null, end, "171191"));
        assertThat(calculate(facts).equity().value()).isEqualByComparingTo("35537");
    }

    @Test
    void derivesCurrentOperatingLeasesFromTheSameDisclosure() {
        String end = "2026-09-30";
        SecFinancialFact total = fact("OperatingLeaseLiability", null, end, "27477");
        SecFinancialFact noncurrent = fact("OperatingLeaseLiabilityNoncurrent", null, end, "21719");
        noncurrent.setAccession(total.getAccession());
        assertThat(calculate(List.of(total, noncurrent)).inputs().get(FinancialConcept.CURRENT_OPERATING_LEASES).value())
                .isEqualByComparingTo("5758");
        noncurrent.setAccession("0000000001-26-999999");
        assertThat(calculate(List.of(total, noncurrent)).inputs().get(FinancialConcept.CURRENT_OPERATING_LEASES).value()).isNull();
    }

    @Test
    void connectsQuartersWhenTheTotalRevenueTagChanges() {
        List<SecFinancialFact> facts = List.of(fact("SalesRevenueNet", "2025-10-01", "2025-12-31", "100"),
                fact("SalesRevenueNet", "2026-01-01", "2026-03-31", "200"),
                fact("Revenues", "2026-04-01", "2026-06-30", "300"),
                fact("Revenues", "2026-07-01", "2026-09-30", "400"));
        assertThat(calculate(facts).revenue().value()).isEqualByComparingTo("1000");
    }

    @Test
    void exposesReportedOtherComprehensiveEquityForSamePeriodFutuReconciliation() {
        SecFinancialFact equity = fact("StockholdersEquity", null, "2026-09-30", "1113234000");
        SecFinancialFact aoci = fact("AccumulatedOtherComprehensiveIncomeLossNetOfTax", null, "2026-09-30", "-3067000");
        aoci.setAccession(equity.getAccession());
        assertThat(calculate(List.of(equity, aoci)).inputs().get(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED).value())
                .isEqualByComparingTo("-3067000");
    }

    @Test
    void usesReportedParentEquityDirectlyWithoutRequiringPreferredLineInSameFiling() {
        SecFinancialFact equity = fact("StockholdersEquity", null, "2026-09-30", "19285");
        assertThat(calculate(List.of(equity)).equity().value()).isEqualByComparingTo("19285");
    }

    @Test
    void totalEquityLessNoncontrollingInterestDerivesParentCommonEquityAndDeductsPreferredBookValue() {
        SecFinancialFact total = fact("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", null, "2026-09-30", "1000");
        SecFinancialFact minority = fact("MinorityInterest", null, "2026-09-30", "100");
        assertThat(calculate(List.of(total, minority)).equity().value()).isEqualByComparingTo("900");

        SecFinancialFact preferredVal = fact("PreferredStockValue", null, "2026-09-30", "40");
        assertThat(calculate(List.of(total, minority, preferredVal)).equity().value()).isEqualByComparingTo("860");
    }

    @Test
    void positivePreferredBookValueIsDeductedForCommonEquityAndAddedInEvAdjustment() {
        String end = "2026-09-30";
        SecFinancialFact equity = fact("StockholdersEquity", null, end, "56695000");
        SecFinancialFact preferredValue = fact("PreferredStockValue", null, end, "10");
        SecFinancialFact cash = fact("CashAndCashEquivalentsAtCarryingValue", null, end, "1000");
        SecFinancialFact ltDebt = fact("LongTermDebt", null, end, "5000");
        SecFinancialFactsCalculator.Flows flows = calculate(List.of(equity, preferredValue, cash, ltDebt));
        assertThat(flows.equity().value()).isEqualByComparingTo("56694990");
        // EV 調整項 = 5000(Debt) + 10(Preferred) + 0(NCI) - 1000(Cash) = 4010
        assertThat(flows.enterpriseValueAdjustment().value()).isEqualByComparingTo("4010");
    }

    @Test
    void usesPreferredCarryingValueFromSameFilingForCommonEquity() {
        SecFinancialFact equity = fact("StockholdersEquity", null, "2026-09-30", "254190");
        SecFinancialFact preferred = fact("PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount", null, "2026-09-30", "26068");
        preferred.setAccession(equity.getAccession());
        assertThat(calculate(List.of(equity, preferred)).equity().value()).isEqualByComparingTo("228122");
    }

    @Test
    void apple2015DecemberEbitdaDirectlyAcceptsStandardCashFlowDepreciationWithoutQuarterlyZip() {
        List<SecFinancialFact> facts = apple2015DecemberFacts();
        SecFinancialFactsCalculator.Flow ebitda = calculate(facts).ebitda();
        assertThat(ebitda.value()).isEqualByComparingTo("82791000000");
        assertThat(ebitda.note()).contains("DepreciationAmortizationAndAccretionNet");
    }

    @Test
    void standardDdaPriorityOrderHandlesMiningDepletionAndUtilityAccretionWithCalculationNotes() {
        String start = "2025-01-01";
        String end = "2025-12-31";
        // 優先序：DepreciationDepletionAndAmortization > DepreciationAmortizationAndAccretionNet > DepreciationAndAmortization
        List<SecFinancialFact> rsgFacts = List.of(
                fact("OperatingIncomeLoss", start, end, "500000000"),
                fact("DepreciationAmortizationAndAccretionNet", start, end, "332100000"),
                fact("DepreciationDepletionAndAmortization", start, end, "310400000"),
                fact("DepreciationAndAmortization", start, end, "290000000"));
        SecFinancialFactsCalculator.Flows rsg = calculate(rsgFacts);
        assertThat(rsg.ebitda().value()).isEqualByComparingTo("810400000");
        assertThat(rsg.ebitda().note()).contains("DepreciationDepletionAndAmortization");

        // 公用事業（如 NEE）僅列示 DepreciationAmortizationAndAccretionNet 時直接採用並標註
        List<SecFinancialFact> utilityFacts = List.of(
                fact("OperatingIncomeLoss", start, end, "8020000000"),
                fact("DepreciationAmortizationAndAccretionNet", start, end, "6941000000"));
        SecFinancialFactsCalculator.Flows utility = calculate(utilityFacts);
        assertThat(utility.ebitda().value()).isEqualByComparingTo("14961000000");
        assertThat(utility.ebitda().note()).contains("DepreciationAmortizationAndAccretionNet");

        // 礦業（如 NEM）列示 DepreciationDepletionAndAmortization 時直接採用含折耗總額
        List<SecFinancialFact> miningFacts = List.of(
                fact("OperatingIncomeLoss", start, end, "11023000000"),
                fact("DepreciationDepletionAndAmortization", start, end, "2521000000"));
        SecFinancialFactsCalculator.Flows mining = calculate(miningFacts);
        assertThat(mining.ebitda().value()).isEqualByComparingTo("13544000000");
        assertThat(mining.ebitda().note()).contains("DepreciationDepletionAndAmortization");
    }

    @Test
    void appleQuarterBalanceResolvesPureBorrowingsWithoutRequiringLiabilitySublineZeroErrorReconciliation() {
        String end = "2015-12-26";
        List<SecFinancialFact> facts = new ArrayList<>(List.of(
                fact("CommercialPaper", null, end, "7259"),
                fact("LongTermDebtCurrent", null, end, "2500"),
                fact("LongTermDebtNoncurrent", null, end, "53204"),
                fact("CashAndCashEquivalentsAtCarryingValue", null, end, "16689"),
                fact("StockholdersEquity", null, end, "128267")));
        assertThat(calculate(facts).enterpriseValueAdjustment().value()).isEqualByComparingTo("46274");
    }

    @Test
    void evUsesDisclosedCurrentAndNoncurrentDebtTotalsWithoutCountingTheirChildren() {
        String end = "2025-12-31";
        List<SecFinancialFact> facts = List.of(fact("DebtCurrent", null, end, "30"),
                fact("LongTermDebtAndCapitalLeaseObligations", null, end, "100"),
                fact("ShortTermBorrowings", null, end, "20"), fact("FinanceLeaseLiabilityCurrent", null, end, "5"),
                fact("CashAndCashEquivalentsAtCarryingValue", null, end, "40"),
                fact("StockholdersEquity", null, end, "90"),
                fact("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", null, end, "93"),
                fact("PreferredStockValue", null, end, "0"));
        assertThat(calculate(facts).enterpriseValueAdjustment().value()).isEqualByComparingTo("93");
    }

    @Test
    void convertiblePreferredCarryingValueIsSupported() {
        List<SecFinancialFact> facts = List.of(fact("StockholdersEquity", null, "2025-12-31", "100"),
                fact("CommonStocksIncludingAdditionalPaidInCapital", null, "2025-12-31", "60"),
                fact("RetainedEarningsAccumulatedDeficit", null, "2025-12-31", "25"),
                fact("AccumulatedOtherComprehensiveIncomeLossNetOfTax", null, "2025-12-31", "5"),
                fact("ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue", null, "2025-12-31", "10"));
        assertThat(calculate(facts).equity().value()).isEqualByComparingTo("90");
    }

    @Test
    void secEbitdaUsesOperatingIncomeAndPureDepreciationAndAmortization() {
        List<SecFinancialFact> facts = List.of(fact("NetIncomeLoss", "2025-01-01", "2025-12-31", "100"),
                fact("IncomeTaxExpenseBenefit", "2025-01-01", "2025-12-31", "20"),
                fact("InterestExpense", "2025-01-01", "2025-12-31", "10"),
                fact("Depreciation", "2025-01-01", "2025-12-31", "30"),
                fact("AmortizationOfIntangibleAssets", "2025-01-01", "2025-12-31", "10"),
                fact("OperatingIncomeLoss", "2025-01-01", "2025-12-31", "90"));
        matchDepreciationFiling(facts);
        assertThat(calculate(facts).ebitda().value()).isEqualByComparingTo("130");
    }

    @Test
    void secEvAdjustmentUsesCashOnlyAndDoesNotRequireOperatingLeasesOrInvestmentBreakdown() {
        String end = "2025-12-31";
        List<SecFinancialFact> facts = List.of(fact("LongTermDebt", null, end, "100"),
                fact("LongTermDebtCurrent", null, end, "10"), fact("LongTermDebtNoncurrent", null, end, "90"),
                fact("CommercialPaper", null, end, "5"), fact("FinanceLeaseLiability", null, end, "20"),
                fact("CashAndCashEquivalentsAtCarryingValue", null, end, "30"), fact("MinorityInterest", null, end, "2"),
                fact("PreferredStockValue", null, end, "0"));
        assertThat(calculate(facts).enterpriseValueAdjustment().value()).isEqualByComparingTo("97");
    }

    @Test
    void apple2015CashFlowUsesTheCurrentAnnualContinuingOperationsTag() {
        List<SecFinancialFact> facts = List.of(
                fact("NetCashProvidedByUsedInOperatingActivities", "2013-09-29", "2014-09-27", "59713000000"),
                fact("NetCashProvidedByUsedInOperatingActivitiesContinuingOperations", "2014-09-28", "2015-09-26", "81266000000"),
                fact("PaymentsToAcquirePropertyPlantAndEquipment", "2014-09-28", "2015-09-26", "11247000000"));
        assertThat(calculate(facts).freeCashFlow().value()).isEqualByComparingTo("70019000000");
    }

    @Test
    void reportedTotalOperatingCashFlowTakesPrecedenceWithinTheSamePeriod() {
        List<SecFinancialFact> facts = List.of(
                fact("NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-12-31", "200"),
                fact("NetCashProvidedByUsedInOperatingActivitiesContinuingOperations", "2025-01-01", "2025-12-31", "250"),
                fact("PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", "2025-12-31", "20"));
        assertThat(calculate(facts).freeCashFlow().value()).isEqualByComparingTo("180");
    }

    private List<SecFinancialFact> apple2015DecemberFacts() {
        List<SecFinancialFact> facts = new ArrayList<>();
        for (String[] amounts : List.of(
                new String[]{"OperatingIncomeLoss", "71230000000", "24171000000", "24246000000"},
                new String[]{"DepreciationAmortizationAndAccretionNet", "11257000000", "2954000000", "2575000000"})) {
            facts.add(fact(amounts[0], "2014-09-28", "2015-09-26", amounts[1]));
            facts.add(fact(amounts[0], "2015-09-27", "2015-12-26", amounts[2]));
            facts.add(fact(amounts[0], "2014-09-28", "2014-12-27", amounts[3]));
        }
        SecFinancialFact note = fact("DepreciationAndAmortization", "2014-09-28", "2015-09-26", "9200000000");
        facts.add(note);
        for (SecFinancialFact fact : facts) {
            boolean annual = fact.getPeriodEnd().equals(LocalDate.of(2015, 9, 26));
            fact.setAccession(annual ? "0000320193-15-000113" : "0001193125-16-439878");
            fact.setFiled(annual ? LocalDate.of(2015, 10, 28) : LocalDate.of(2016, 1, 27));
            fact.setAvailableFromDate(fact.getFiled().plusDays(1));
            fact.setForm(annual ? "10-K" : "10-Q");
        }
        return facts;
    }

    @Test
    void ppeCapitalSpendingUsesCompletePpeTagWithoutAddingSeparateSoftwareLine() {
        List<SecFinancialFact> facts = List.of(fact("NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-12-31", "200"),
                fact("PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", "2025-12-31", "20"),
                fact("PaymentsToDevelopSoftware", "2025-01-01", "2025-12-31", "5"));
        assertThat(calculate(facts).freeCashFlow().value()).isEqualByComparingTo("180");
    }

    private static final String CIK = "0000000001";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    private final SecFinancialFactsCalculator calculator = new SecFinancialFactsCalculator();
    private int accession;

    @Test
    void appleAnnualInputsUseDisclosedScopesAndNeverCountDebtTwice() {
        String start = "2025-09-28";
        String end = "2026-09-26";
        List<SecFinancialFact> facts = List.of(
                fact("EarningsPerShareDiluted", start, end, "8.98"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", start, end, "478834"),
                fact("GrossProfit", start, end, "233602"),
                fact("CostOfGoodsAndServicesSold", start, end, "245232"),
                fact("NetCashProvidedByUsedInOperatingActivities", start, end, "145097"),
                fact("PaymentsToAcquirePropertyPlantAndEquipment", start, end, "9041"),
                fact("OperatingIncomeLoss", start, end, "158063"),
                fact("DepreciationDepletionAndAmortization", start, end, "13423"),
                fact("StockholdersEquity", null, end, "112093"),
                fact("CommonStocksIncludingAdditionalPaidInCapital", null, end, "104815"),
                fact("RetainedEarningsAccumulatedDeficit", null, end, "13700"),
                fact("AccumulatedOtherComprehensiveIncomeLossNetOfTax", null, end, "-6422"),
                fact("Assets", null, end, "404548"), fact("Liabilities", null, end, "292455"),
                fact("LongTermDebt", null, end, "78114"),
                fact("LongTermDebtCurrent", null, end, "9906"),
                fact("LongTermDebtNoncurrent", null, end, "68208"),
                fact("CommercialPaper", null, end, "1997"),
                fact("FinanceLeaseLiability", null, end, "1456"),
                fact("OperatingLeaseLiability", null, end, "12912"),
                fact("CashAndCashEquivalentsAtCarryingValue", null, end, "36305"),
                fact("MarketableSecuritiesCurrent", null, end, "20417"),
                fact("MarketableSecuritiesNoncurrent", null, end, "80213"));
        for (SecFinancialFact fact : facts) {
            fact.setCik("0000320193");
            fact.setAccession("0000320193-26-000026");
            fact.setFiled(LocalDate.parse("2026-11-03"));
            fact.setAvailableFromDate(LocalDate.parse("2026-11-04"));
        }
        facts.getFirst().setUnit("USD/shares");
        SecFinancialFactsCalculator.Flows result = calculator.calculate(facts, "0000320193", null, "USD", DAY, NOW, List.of());
        assertThat(result.dilutedEpsTtm().value()).isEqualByComparingTo("8.98");
        assertThat(result.revenue().value()).isEqualByComparingTo("478834");
        assertThat(result.freeCashFlow().value()).isEqualByComparingTo("136056");
        assertThat(result.equity().value()).isEqualByComparingTo("112093");
        assertThat(result.ebitda().value()).isEqualByComparingTo("171486");
        assertThat(result.enterpriseValueAdjustment().value()).isEqualByComparingTo("45262");

        SecFinancialFactsCalculator.Flows historical = calculator.calculate(facts, "0000320193", null, "USD", LocalDate.parse("2026-11-03"), NOW, List.of());
        assertThat(historical.dilutedEpsTtm().value()).isEqualByComparingTo("8.98");
        assertThat(historical.revenue().value()).isEqualByComparingTo("478834");
        assertThat(historical.enterpriseValueAdjustment().value()).isEqualByComparingTo("45262");

        List<SecFinancialFact> missingLease = facts.stream().filter(fact -> !"OperatingLeaseLiability".equals(fact.getTag())).toList();
        assertThat(calculator.calculate(missingLease, "0000320193", null, "USD", DAY, NOW, List.of()).enterpriseValueAdjustment().value()).isEqualByComparingTo("45262");
    }

    @Test
    void annualPlusYtdMinusPriorYtdBuildsRevenueAndCompleteProductiveAssetFcf() {
        List<SecFinancialFact> facts = new ArrayList<>();
        addYtd(facts, "Revenues", "1000", "900", "600");
        addYtd(facts, "NetCashProvidedByUsedInOperatingActivities", "200", "180", "120");
        addYtd(facts, "PaymentsToAcquireProductiveAssets", "75", "60", "45");
        facts.add(fact("PaymentsToAcquirePropertyPlantAndEquipment", "2026-01-01", "2026-09-30", "999"));

        SecFinancialFactsCalculator.Flows result = calculate(facts);

        assertThat(result.revenue().value()).isEqualByComparingTo("1300");
        assertThat(result.freeCashFlow().value()).isEqualByComparingTo("170");
        assertThat(result.freeCashFlow().periodStart()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(result.freeCashFlow().periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(result.freeCashFlow().observedAt()).isAfter(DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
    }

    @Test
    void reportedTotalRevenueCanBuildTtmWithoutSubstitutingNetOfInterestRevenue() {
        List<SecFinancialFact> facts = new ArrayList<>();
        addYtd(facts, "Revenues", "1000", "900", "600");
        addYtd(facts, "RevenuesNetOfInterestExpense", "800", "750", "550");

        SecFinancialFactsCalculator.Flow revenue = calculate(facts).revenue();
        assertThat(revenue.value()).isEqualByComparingTo("1300");
        assertThat(revenue.periodStart()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(revenue.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));

        facts.removeIf(fact -> "Revenues".equals(fact.getTag()));
        assertThat(calculate(facts).revenue().reason()).isEqualTo("SEC_REVENUE_NET_SCOPE_UNVERIFIED");
    }

    @Test
    void fourConsecutiveQuartersWorkButNineMonthCashFlowIsNotOneQuarter() {
        List<SecFinancialFact> facts = List.of(
                fact("Revenues", "2025-10-01", "2025-12-31", "100"), fact("Revenues", "2026-01-01", "2026-03-31", "200"),
                fact("Revenues", "2026-04-01", "2026-06-30", "300"), fact("Revenues", "2026-07-01", "2026-09-30", "400"),
                fact("NetCashProvidedByUsedInOperatingActivities", "2025-09-01", "2026-05-31", "9267947000"));
        assertThat(calculate(facts).revenue().value()).isEqualByComparingTo("1000");
        assertThat(calculate(facts).freeCashFlow().value()).isNull();
    }

    @Test
    void epsUsesFourDisclosedSingleQuartersWithoutSubtractingCumulativeEps() {
        List<SecFinancialFact> quarters = List.of(
                fact("EarningsPerShareDiluted", "2025-10-01", "2025-12-31", "1"),
                fact("EarningsPerShareDiluted", "2026-01-01", "2026-03-31", "2"),
                fact("EarningsPerShareDiluted", "2026-04-01", "2026-06-30", "3"),
                fact("EarningsPerShareDiluted", "2026-07-01", "2026-09-30", "4"));
        quarters.forEach(fact -> fact.setUnit("USD/shares"));

        assertThat(calculate(quarters).dilutedEpsTtm().value()).isEqualByComparingTo("10");

        List<SecFinancialFact> ytdOnly = new ArrayList<>();
        addYtd(ytdOnly, "EarningsPerShareDiluted", "10", "9", "6");
        ytdOnly.forEach(fact -> fact.setUnit("USD/shares"));
        assertThat(calculate(ytdOnly).dilutedEpsTtm().value()).isNull();
    }

    @Test
    void aTenKContainingOnlyQuarterlySalesDoesNotBecomeAnAnnualFact() {
        SecFinancialFact quarterly = fact("SalesRevenueNet", "2026-07-01", "2026-09-30", "200");
        quarterly.setForm("10-K");
        assertThat(calculate(List.of(quarterly)).revenue().value()).isNull();
    }

    @Test
    void supportsFiftyThreeWeekYearWithoutCalendarQuarterGuessing() {
        List<SecFinancialFact> facts = List.of(fact("Revenues", "2024-09-29", "2025-10-04", "1000"),
                fact("Revenues", "2025-10-05", "2026-07-04", "900"), fact("Revenues", "2024-09-29", "2025-06-28", "600"));
        SecFinancialFactsCalculator.Flow revenue = calculate(facts).revenue();
        assertThat(revenue.value()).isEqualByComparingTo("1300");
        assertThat(revenue.periodStart()).isEqualTo(LocalDate.of(2025, 6, 29));
        assertThat(revenue.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 4));
    }

    @Test
    void latestVersionsPublishedAndObtainedByCalculationAreEligibleForHistoricalDates() {
        SecFinancialFact original = fact("Revenues", "2025-01-01", "2025-12-31", "100");
        original.setFiled(LocalDate.of(2026, 2, 1));
        original.setAvailableFromDate(LocalDate.of(2026, 2, 2));
        SecFinancialFact futureAmendment = fact("Revenues", "2025-01-01", "2025-12-31", "120");
        futureAmendment.setFiled(DAY);
        futureAmendment.setAvailableFromDate(DAY.plusDays(1));
        assertThat(calculate(List.of(original, futureAmendment)).revenue().value()).isEqualByComparingTo("120");
        futureAmendment.setAvailableFromDate(DAY);
        futureAmendment.setObservedAt(NOW.plusSeconds(1));
        assertThat(calculate(List.of(original, futureAmendment)).revenue().value()).isEqualByComparingTo("100");
        futureAmendment.setObservedAt(NOW);
        assertThat(calculate(List.of(original, futureAmendment)).revenue().value()).isEqualByComparingTo("120");
    }

    @Test
    void nonFinancialContractRevenueIsAcceptedDirectlyWhileFinancialInstitutionSicExcludesContractOnlyRevenue() {
        List<SecFinancialFact> appleShape = List.of(fact("Revenues", "2017-10-01", "2018-09-30", "100"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2025-10-01", "2026-09-30", "200"));
        assertThat(calculator.calculate(appleShape, CIK, "3571", "USD", DAY, NOW, List.of()).revenue().value()).isEqualByComparingTo("200");
        // 銀行（6021）、券商（6211）、保險（6311）不得將合約收入單獨當作總收入
        for (String financialSic : List.of("6021", "6211", "6311")) {
            assertThat(calculator.calculate(appleShape, CIK, financialSic, "USD", DAY, NOW, List.of()).revenue().value()).isNull();
        }
        List<SecFinancialFact> metShape = List.of(fact("Revenues", "2025-01-01", "2025-12-31", "77084000000"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2025-01-01", "2025-12-31", "2436000000"));
        assertThat(calculator.calculate(metShape, CIK, "6311", "USD", DAY, NOW, List.of()).revenue().value()).isEqualByComparingTo("77084000000");
    }

    @Test
    void corroboratedContractRevenueCorrectsMisScopedTotalAcrossTtmPeriods() {
        List<SecFinancialFact> facts = new ArrayList<>();
        addYtd(facts, "Revenues", "185971", "16860823", "19489178");
        addYtd(facts, "RevenueFromContractWithCustomerExcludingAssessedTax", "22402188", "16860823", "19489178");
        addYtd(facts, "GrossProfit", "15202626", "10883568", "13527383");
        addYtd(facts, "CostOfGoodsAndServicesSold", "7199562", "5977255", "5961795");

        SecFinancialFactsCalculator.Flow result = calculate(facts).revenue();

        assertThat(result.value()).isEqualByComparingTo("19773833");
        assertThat(result.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(calculate(List.of(fact("Revenues", "2025-01-01", "2025-12-31", "100"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2025-01-01", "2025-12-31", "200")))
                .revenue().value()).isEqualByComparingTo("100");
    }

    @Test
    void contractRevenueDirectlyUsesFourConsecutiveQuartersWithoutRequiringCostOfSalesReconciliation() {
        List<SecFinancialFact> facts = List.of(
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2025-10-01", "2025-12-31", "199434000"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2026-01-01", "2026-03-31", "214952000"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2026-04-01", "2026-06-30", "249962000"),
                fact("RevenueFromContractWithCustomerExcludingAssessedTax", "2026-07-01", "2026-09-30", "308636000"));
        SecFinancialFactsCalculator.Flow result = calculate(facts).revenue();
        assertThat(result.value()).isEqualByComparingTo("972984000");
        assertThat(result.periodStart()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(result.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void cashFlowMissingQuartersExtendTrailingFcfAndSplitCrossingEpsVerifiesViaDilutedWeightedShares() {
        ReportValues q3Cash = new ReportValues("NVDA", 3, LocalDate.of(2024, 4, 28), 1, 2025, "USD", "US_GAAP", Map.of(), List.of(), List.of());
        SecFinancialFact q2Cfo = fact("NetCashProvidedByUsedInOperatingActivities", "2024-04-29", "2024-07-28", "14489000000");
        SecFinancialFact q2Capex = fact("PaymentsToAcquirePropertyPlantAndEquipment", "2024-04-29", "2024-07-28", "977000000");
        SecFinancialFactsCalculator.Flows flows = calculator.calculate(List.of(q2Cfo, q2Capex), CIK, "3674", "USD", DAY, NOW, List.of(q3Cash));
        assertThat(flows.missingQuarters()).anySatisfy(missing -> {
            assertThat(missing.statementType()).isEqualTo(3);
            assertThat(missing.periodEnd()).isEqualTo(LocalDate.of(2024, 7, 28));
        });

        // NVDA 2024-06-10 10拆1：Q1 FY25（2024-04-28）Futu 重述後 EPS 0.60，以原申報稀釋加權平均股數 2.489B × 拆股因子 10 驗證通過
        com.ykm.stock.analytics.model.entity.StockSplit nvdaSplit = new com.ykm.stock.analytics.model.entity.StockSplit();
        nvdaSplit.setSourceEventId("NVDA:2024-06-10");
        nvdaSplit.setFigi("NVDA");
        nvdaSplit.setExecutionDate(LocalDate.of(2024, 6, 10));
        nvdaSplit.setSplitFrom(BigDecimal.ONE);
        nvdaSplit.setSplitTo(BigDecimal.TEN);
        SecFinancialFact dilutedShares = fact("WeightedAverageNumberOfDilutedSharesOutstanding", "2024-01-29", "2024-04-28", "2489000000");
        dilutedShares.setUnit("shares");
        dilutedShares.setFiled(LocalDate.of(2024, 5, 29));
        dilutedShares.setAvailableFromDate(LocalDate.of(2024, 5, 30));

        // 拆股後交易日（2024-06-14）：重述後 EPS 0.60 通過驗證，未重述之舊值 5.98 不通過
        assertThat(calculator.isEpsSplitBasisVerified(new BigDecimal("0.60"), new BigDecimal("14881000000"),
                LocalDate.of(2024, 4, 27), false, List.of(dilutedShares), "USD", List.of(nvdaSplit),
                LocalDate.of(2024, 6, 14), NOW)).isTrue();
        assertThat(calculator.isEpsSplitBasisVerified(new BigDecimal("5.98"), new BigDecimal("14881000000"),
                LocalDate.of(2024, 4, 27), false, List.of(dilutedShares), "USD", List.of(nvdaSplit),
                LocalDate.of(2024, 6, 14), NOW)).isFalse();

        // 拆股前交易日（2024-05-31，未復權股價 ~$1,096）：重述後 EPS 0.60 被攔截，避免算出 10 倍錯誤 P/E
        assertThat(calculator.isEpsSplitBasisVerified(new BigDecimal("0.60"), new BigDecimal("14881000000"),
                LocalDate.of(2024, 4, 27), false, List.of(dilutedShares), "USD", List.of(nvdaSplit),
                LocalDate.of(2024, 5, 31), NOW)).isFalse();
    }

    @Test
    void differentTotalScopesNetRevenueAndCurrencyAreNotBlindlySubstituted() {
        SecFinancialFact total = fact("Revenues", "2025-01-01", "2025-12-31", "100");
        SecFinancialFact sales = fact("SalesRevenueNet", "2025-01-01", "2025-12-31", "90");
        assertThat(calculate(List.of(total, sales)).revenue().reason()).isEqualTo("SEC_REVENUE_SCOPE_CONFLICT");
        SecFinancialFact net = fact("RevenuesNetOfInterestExpense", "2025-01-01", "2025-12-31", "90");
        assertThat(calculate(List.of(total, net)).revenue().value()).isEqualByComparingTo("100");
        assertThat(calculate(List.of(net)).revenue().reason()).isEqualTo("SEC_REVENUE_NET_SCOPE_UNVERIFIED");
        total.setUnit("EUR");
        assertThat(calculate(List.of(total)).revenue().value()).isNull();
    }

    @Test
    void ppeFallbackUsesDisclosedCapitalSpendingAndStillRequiresMatchingPeriods() {
        SecFinancialFact cfo = fact("NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-12-31", "200");
        SecFinancialFact ppe = fact("PaymentsToAcquirePropertyPlantAndEquipment", "2025-01-01", "2025-12-31", "20");
        assertThat(calculate(List.of(cfo, ppe)).freeCashFlow().value()).isEqualByComparingTo("180");
        SecFinancialFact differentYear = fact("PaymentsToAcquireProductiveAssets", "2024-01-01", "2024-12-31", "20");
        assertThat(calculate(List.of(cfo, differentYear)).freeCashFlow().reason()).isEqualTo("SEC_FCF_PERIOD_MISMATCH");
    }

    @Test
    void negativeCfoStaysNegativeAndNegativeAcquisitionPaymentIsUnverified() {
        SecFinancialFact cfo = fact("NetCashProvidedByUsedInOperatingActivities", "2025-01-01", "2025-12-31", "-100");
        SecFinancialFact capex = fact("PaymentsToAcquireProductiveAssets", "2025-01-01", "2025-12-31", "20");
        assertThat(calculate(List.of(cfo, capex)).freeCashFlow().value()).isEqualByComparingTo("-120");
        capex.setValue(new BigDecimal("-20"));
        assertThat(calculate(List.of(cfo, capex)).freeCashFlow().value()).isNull();
    }

    @Test
    void sameDayConflictingVersionsAndOtherIssuersAreNotCombined() {
        SecFinancialFact a = fact("Revenues", "2025-01-01", "2025-12-31", "100");
        SecFinancialFact b = fact("Revenues", "2025-01-01", "2025-12-31", "120");
        assertThat(calculate(List.of(a, b)).revenue().value()).isNull();
        b.setCik("0000000002");
        assertThat(calculate(List.of(a, b)).revenue().value()).isEqualByComparingTo("100");
    }

    @Test
    void samePeriodParentEquityConflictDerivesFromTotalEquityAndExplicitMinorityInterest() {
        String end = "2026-09-30";
        SecFinancialFact p1 = fact("StockholdersEquity", null, end, "80");
        SecFinancialFact p2 = fact("StockholdersEquity", null, end, "90");
        SecFinancialFact total = fact("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", null, end, "100");
        SecFinancialFact minorityZero = fact("MinorityInterest", null, end, "0");

        SecFinancialFactsCalculator.Flows resolved = calculate(List.of(p1, p2, total, minorityZero));
        assertThat(resolved.inputs().get(FinancialConcept.STOCKHOLDERS_EQUITY).value()).isEqualByComparingTo("100");
        assertThat(resolved.equity().value()).isEqualByComparingTo("100");

        // 同季母公司權益衝突且未披露 MinorityInterest 時，不假設 NCI 為 0，維持衝突拒絕
        SecFinancialFactsCalculator.Flows withoutMinority = calculate(List.of(p1, p2, total));
        assertThat(withoutMinority.inputs().get(FinancialConcept.STOCKHOLDERS_EQUITY).value()).isNull();
        assertThat(withoutMinority.equity().reason()).isEqualTo("SEC_FACT_VERSION_CONFLICT");

        // 負母公司權益（如 TZOO 2022-03-31：3135 - 4604 = -1469）亦按實保留
        SecFinancialFact negP1 = fact("StockholdersEquity", null, "2022-03-31", "-1469");
        SecFinancialFact negP2 = fact("StockholdersEquity", null, "2022-03-31", "3135");
        SecFinancialFact negTot = fact("StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", null, "2022-03-31", "3135");
        SecFinancialFact negMin = fact("MinorityInterest", null, "2022-03-31", "4604");
        assertThat(calculate(List.of(negP1, negP2, negTot, negMin)).equity().value()).isEqualByComparingTo("-1469");
    }

    @Test
    void futuInferredQuarterStartDoesNotOverwriteUniqueDirectSecQuarterStart() {
        SecFinancialFact secQ2 = fact("Revenues", "2025-04-06", "2025-07-05", "300");
        secQ2.setReportFiscalYear(2025);
        secQ2.setReportFiscalPeriod("Q2");
        ReportValues futuQ1 = new ReportValues("TEST", 1, LocalDate.of(2025, 3, 31), 1, 2025,
                "USD", "US_GAAP", Map.of(), List.of(), List.of());
        ReportValues futuQ2 = new ReportValues("TEST", 1, LocalDate.of(2025, 6, 30), 2, 2025,
                "USD", "US_GAAP", Map.of(), List.of(), List.of());

        SecFinancialFactsCalculator.PeriodSelection atApril2 = calculator.selectPeriods(
                List.of(futuQ1, futuQ2), List.of(secQ2), LocalDate.of(2025, 4, 2), NOW);
        assertThat(atApril2.reports()).extracting(ReportValues::periodEnd)
                .containsExactly(LocalDate.of(2025, 3, 31));
        assertThat(atApril2.facts()).isEmpty();

        // 1) 前九月僅由 Depreciation 佐證時，全年減前九月仍推導出 Q4 起日 2025-09-29（2025-06-01 不得提前採用 Q4/年報 revFy）
        SecFinancialFact dep9m = fact("Depreciation", "2024-12-30", "2025-09-28", "50");
        SecFinancialFact revFy = fact("Revenues", "2024-12-30", "2025-12-28", "1000");
        revFy.setForm("10-K");
        assertThat(calculator.exactQuarterStart(List.of(dep9m, revFy), LocalDate.of(2025, 12, 28)))
                .isEqualTo(LocalDate.of(2025, 9, 29));
        assertThat(calculator.selectPeriods(List.of(), List.of(dep9m, revFy), LocalDate.of(2025, 6, 1), NOW).facts())
                .isEmpty();
        assertThat(calculator.selectPeriods(List.of(), List.of(dep9m, revFy), LocalDate.of(2025, 10, 15), NOW).facts())
                .containsExactly(dep9m, revFy);

        // 2) 16 週 Q4 有唯一明報（2025-09-08..12-28）且與同財年兩份前九月（09-07、09-21）中唯一一份相接時，採用明報 09-08
        SecFinancialFact nineA = fact("Revenues", "2024-12-30", "2025-09-07", "3400");
        SecFinancialFact nineB = fact("Revenues", "2024-12-30", "2025-09-21", "3500");
        SecFinancialFact direct16wQ4 = fact("Revenues", "2025-09-08", "2025-12-28", "1540");
        direct16wQ4.setForm("10-K");
        assertThat(calculator.exactQuarterStart(List.of(nineA, nineB, revFy, direct16wQ4), LocalDate.of(2025, 12, 28)))
                .isEqualTo(LocalDate.of(2025, 9, 8));

        // 3) 已確認的 16 週 Q4（2025-12-28）可作為下一季（2026-03-22）的唯一 SEC 前季期末
        assertThat(calculator.uniquePriorSecQuarterEnd(List.of(nineA, revFy, direct16wQ4), LocalDate.of(2026, 3, 22)))
                .isEqualTo(LocalDate.of(2025, 12, 28));

        // 4) 全年同起日佐證的 77 天 Q1（2025-10-13..12-28）直接採納明報起日 2025-10-13
        SecFinancialFact q1Short = fact("Revenues", "2025-10-13", "2025-12-28", "200");
        SecFinancialFact fySameStart = fact("Revenues", "2025-10-13", "2026-10-11", "900");
        fySameStart.setForm("10-K");
        assertThat(calculator.exactQuarterStart(List.of(q1Short, fySameStart), LocalDate.of(2025, 12, 28)))
                .isEqualTo(LocalDate.of(2025, 10, 13));

        // 5) 附註攤銷標籤（如 BYRN AmortizationOfIntangibleAssets 2022-08-30）不得單獨建立錯誤的獨立財季列搶走 2022-08-31 主表配對
        SecFinancialFact byrnRev = fact("Revenues", "2022-06-01", "2022-08-31", "11000");
        SecFinancialFact byrnAmortNote = fact("AmortizationOfIntangibleAssets", "2022-06-01", "2022-08-30", "120");
        SecFinancialFactsCalculator.PeriodAlignment byrnAligned = calculator.alignReportPeriods(
                List.of(), List.of(byrnRev, byrnAmortNote), NOW);
        assertThat(byrnAligned.windows()).extracting(SecFinancialFactsCalculator.PeriodWindow::periodEnd)
                .containsExactly(LocalDate.of(2022, 8, 31));
    }

    private SecFinancialFactsCalculator.Flows calculate(List<SecFinancialFact> facts) {
        return calculator.calculate(facts, CIK, null, "USD", DAY, NOW, List.of());
    }

    private void matchDepreciationFiling(List<SecFinancialFact> facts) {
        facts.stream().filter(fact -> List.of("Depreciation", "AmortizationOfIntangibleAssets").contains(fact.getTag()))
                .forEach(fact -> fact.setAccession(facts.getFirst().getAccession()));
    }

    private void addYtd(List<SecFinancialFact> facts, String tag, String annual, String current, String previous) {
        facts.add(fact(tag, "2025-01-01", "2025-12-31", annual));
        facts.add(fact(tag, "2026-01-01", "2026-09-30", current));
        facts.add(fact(tag, "2025-01-01", "2025-09-30", previous));
    }

    private SecFinancialFact fact(String tag, String start, String end, String value) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik(CIK);
        fact.setTaxonomy("us-gaap");
        fact.setTag(tag);
        fact.setUnit("USD");
        fact.setPeriodStart(start == null ? null : LocalDate.parse(start));
        fact.setPeriodEnd(LocalDate.parse(end));
        fact.setValue(new BigDecimal(value));
        fact.setFiled(LocalDate.of(2026, 11, 1));
        fact.setAvailableFromDate(LocalDate.of(2026, 11, 2));
        fact.setObservedAt(NOW.minusSeconds(60));
        fact.setAccession(String.format("0000000001-26-%06d", ++accession));
        fact.setForm("10-Q");
        return fact;
    }
}
