package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.FactKey;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.ReportValues;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.ACCRETION_EXPENSE;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.ADJUSTMENT_FOR_AMORTIZATION;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.DEPRECIATION_AMORTIZATION;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.DEPRECIATION_AMORTIZATION_ACCRETION;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.DEPRECIATION_DEPLETION_AMORTIZATION;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.key;

/** SEC 原幣、同 tag 的可加總流量；不推定股類、ADR、優先股或 EPS 基準。 */
public class SecFinancialFactsCalculator {
    public static final String DEPRECIATION_SCOPE_UNRESOLVED = "SEC_DEPRECIATION_AMORTIZATION_SCOPE_UNRESOLVED";
    public static final Set<String> DEPRECIATION_TOTAL_TAGS =
            Set.of(DEPRECIATION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION, DEPRECIATION_DEPLETION_AMORTIZATION);
    private static final Set<String> DEPRECIATION_COMPONENTS =
            Set.of(ACCRETION_EXPENSE, "Depletion", "Depreciation", ADJUSTMENT_FOR_AMORTIZATION, "AmortizationOfIntangibleAssets");
    private static final String OPERATING_CASH_FLOW = "NetCashProvidedByUsedInOperatingActivities";
    private static final String CONTINUING_OPERATING_CASH_FLOW = "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations";
    private static final Set<String> PERIODIC_REPORTS = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");
    private static final Set<String> INCOME_PERIOD_TAGS = Set.of("Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenuesNetOfInterestExpense", "GrossProfit", "OperatingIncomeLoss", "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic",
            "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted");
    private static final Set<String> BALANCE_PERIOD_TAGS = Set.of("Assets", "Liabilities", "StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
    private static final Set<String> CASH_FLOW_PERIOD_TAGS = Set.of(OPERATING_CASH_FLOW, CONTINUING_OPERATING_CASH_FLOW, "PaymentsToAcquireProductiveAssets",
            "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireSoftware", "PaymentsToDevelopSoftware");
    public static final List<String> INPUT_TAGS = List.of("Revenues", "SalesRevenueNet",
            "RevenueFromContractWithCustomerExcludingAssessedTax", "RevenuesNetOfInterestExpense",
            OPERATING_CASH_FLOW, CONTINUING_OPERATING_CASH_FLOW, "PaymentsToAcquireProductiveAssets", "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireSoftware", "PaymentsToDevelopSoftware",
            "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted", "StockholdersEquity", "OperatingIncomeLoss", DEPRECIATION_DEPLETION_AMORTIZATION, "Depletion",
            DEPRECIATION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION, ACCRETION_EXPENSE, ADJUSTMENT_FOR_AMORTIZATION,
            "CashAndCashEquivalentsAtCarryingValue", "MarketableSecuritiesCurrent", "MarketableSecuritiesNoncurrent",
            "CommercialPaper", "LongTermDebt", "LongTermDebtCurrent", "LongTermDebtNoncurrent",
            "FinanceLeaseLiability", "OperatingLeaseLiability", "PreferredStockValue", "PreferredStockValueOutstanding",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount",
            "MinorityInterest", "RedeemableNoncontrollingInterestEquityCarryingAmount", "GrossProfit", "CostOfGoodsAndServicesSold",
            "Assets", "Liabilities", "LiabilitiesCurrent", "AccountsPayableCurrent", "AccruedLiabilitiesCurrent",
            "DeferredRevenueCurrent", "DeferredRevenueNoncurrent", "OtherLiabilitiesNoncurrent",
            "CommonStocksIncludingAdditionalPaidInCapital", "CommonStockValue", "AdditionalPaidInCapital",
            "AdditionalPaidInCapitalCommonStock", "RetainedEarningsAccumulatedDeficit", "TreasuryStockValue", "TreasuryStockCommonValue",
            "AccumulatedOtherComprehensiveIncomeLossNetOfTax", "ShortTermBorrowings", "DebtCurrent",
            "FinanceLeaseLiabilityCurrent", "FinanceLeaseLiabilityNoncurrent", "OperatingLeaseLiabilityCurrent", "OperatingLeaseLiabilityNoncurrent",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "NetIncomeLoss", "ProfitLoss",
            "NetIncomeLossAvailableToCommonStockholdersBasic", "EarningsPerShareBasic", "IncomeTaxExpenseBenefit",
            "InterestExpenseNonoperating", "InterestExpense", "InterestAndDebtExpense", "Depreciation", "AmortizationOfIntangibleAssets",
            "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue",
            "DebtAndCapitalLeaseObligations", "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities", "LongTermDebtAndCapitalLeaseObligations",
            "CapitalLeaseObligations", "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent",
            "PreferredStockSharesOutstanding", "PreferredStockSharesIssued",
            "LongTermDebtAndCapitalLeaseObligationsCurrent", "CommonStockSharesOutstanding");
    public record Flow(BigDecimal value, LocalDate periodStart, LocalDate periodEnd, LocalDate availableFrom,
                       Instant observedAt, String reason, LocalDate epsBasisFrom) {}
    public record ReportPeriod(LocalDate end, int periodType, int statementType) {}
    public record Flows(String cik, Flow revenue, Flow freeCashFlow, Flow dilutedEpsTtm,
                        Flow equity, Flow ebitda, Flow enterpriseValueAdjustment, Map<FinancialConcept, Flow> inputs,
                        Map<ReportPeriod, Map<FinancialConcept, Flow>> periodInputs, List<ReportValues> missingQuarters) {
        public Flow input(FinancialConcept concept, ReportValues report) {
            return periodInputs.getOrDefault(new ReportPeriod(report.periodEnd(), report.periodType(), report.statementType()), Map.of()).get(concept);
        }
    }
    private record Period(LocalDate start, LocalDate end) {}
    private record Point(SecFinancialFact fact, boolean conflict) {}
    public record PeriodSelection(List<ReportValues> reports, List<SecFinancialFact> facts) {}
    private record FiscalQuarter(int year, int quarter) {}

    /** 先辨認交易日所屬的實際財季，再交回原有公式；財年數字取自當期申報，不取比較期的 fy。 */
    public PeriodSelection selectPeriods(Collection<ReportValues> reports, List<SecFinancialFact> input, LocalDate day, Instant knownAt) {
        LocalDate knownDay = knownAt.atZone(ZoneId.of("America/New_York")).toLocalDate();
        List<SecFinancialFact> known = input.stream().filter(fact -> fact.getObservedAt() != null && !fact.getObservedAt().isAfter(knownAt)
                && fact.getFiled() != null && !fact.getFiled().isAfter(knownDay) && fact.getPeriodEnd() != null
                && !fact.getPeriodEnd().isAfter(fact.getFiled())).toList();
        Map<FiscalQuarter, Set<LocalDate>> fiscalEnds = new HashMap<>();
        Set<Period> quarters = new HashSet<>();
        for (SecFinancialFact fact : known) {
            if (!"us-gaap".equals(fact.getTaxonomy()) || !PERIODIC_REPORTS.contains(Objects.toString(fact.getForm(), ""))
                    || !(INCOME_PERIOD_TAGS.contains(fact.getTag()) || BALANCE_PERIOD_TAGS.contains(fact.getTag())
                    || CASH_FLOW_PERIOD_TAGS.contains(fact.getTag()))) continue;
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            if (quarter(period)) quarters.add(period);
            if (fact.getReportFiscalYear() != null && fact.getReportFiscalPeriod() != null) {
                int quarter = switch (fact.getReportFiscalPeriod()) { case "Q1" -> 1; case "Q2" -> 2; case "Q3" -> 3; case "FY" -> 4; default -> 0; };
                if (quarter > 0) fiscalEnds.computeIfAbsent(new FiscalQuarter(fact.getReportFiscalYear(), quarter), ignored -> new HashSet<>()).add(period.end());
            }
        }
        // 全年起點與前三季累計可證明 Q4 的日期範圍；只用於報期配對，不相減 EPS。
        Map<LocalDate, Set<LocalDate>> nineMonthEnds = new HashMap<>();
        for (SecFinancialFact fact : known) {
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            if (length(period) >= 240 && length(period) <= 300)
                nineMonthEnds.computeIfAbsent(period.start(), ignored -> new HashSet<>()).add(period.end());
        }
        for (SecFinancialFact fact : known) {
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            if (!annual(period)) continue;
            for (LocalDate prior : nineMonthEnds.getOrDefault(period.start(), Set.of())) {
                Period fourth = new Period(prior.plusDays(1), period.end());
                if (quarter(fourth)) quarters.add(fourth);
            }
        }
        List<ReportValues> aligned = new ArrayList<>();
        Map<FiscalQuarter, Set<LocalDate>> reportEnds = new HashMap<>();
        for (ReportValues report : reports) {
            if (report.periodEnd().isAfter(knownDay)) continue;
            if (report.fiscalYear() == null) {
                aligned.add(report);
                continue;
            }
            int quarter = report.periodType() == 7 ? 4 : report.periodType();
            FiscalQuarter key = new FiscalQuarter(report.fiscalYear(), quarter);
            Set<LocalDate> actual = fiscalEnds.getOrDefault(key, Set.of());
            LocalDate end = actual.size() == 1 ? actual.iterator().next() : report.periodEnd();
            ReportValues normalized = new ReportValues(report.figi(), report.statementType(), end, report.periodType(), report.fiscalYear(),
                    report.currency(), report.accountingStandards(), report.values(), report.unknownNames(), report.pendingItems());
            aligned.add(normalized);
            if (quarter >= 1 && quarter <= 4) reportEnds.computeIfAbsent(key, ignored -> new HashSet<>()).add(end);
        }
        for (Map.Entry<FiscalQuarter, Set<LocalDate>> entry : reportEnds.entrySet()) {
            FiscalQuarter key = entry.getKey();
            FiscalQuarter previous = key.quarter() == 1 ? new FiscalQuarter(key.year() - 1, 4) : new FiscalQuarter(key.year(), key.quarter() - 1);
            Set<LocalDate> starts = reportEnds.getOrDefault(previous, Set.of());
            if (entry.getValue().size() != 1 || starts.size() != 1) continue;
            Period period = new Period(starts.iterator().next().plusDays(1), entry.getValue().iterator().next());
            if (quarter(period)) quarters.add(period);
        }
        Set<LocalDate> targetEnds = quarters.stream().filter(period -> !day.isBefore(period.start()) && !day.isAfter(period.end()))
                .map(Period::end).collect(java.util.stream.Collectors.toSet());
        return new PeriodSelection(aligned.stream().filter(report -> !report.periodEnd().isAfter(day) || targetEnds.contains(report.periodEnd())).toList(),
                known.stream().filter(fact -> !fact.getPeriodEnd().isAfter(day) || targetEnds.contains(fact.getPeriodEnd())).toList());
    }

    public Flows calculate(List<SecFinancialFact> input, String cik, String currency, LocalDate day, Instant calculatedAt,
                           Collection<ReportValues> reports) {
        return calculate(input, cik, currency, day, calculatedAt, reports, facts -> Set.of());
    }

    public Flows calculate(List<SecFinancialFact> input, String cik, String currency, LocalDate day, Instant calculatedAt,
                           Collection<ReportValues> reports, Function<List<SecFinancialFact>, Set<FactKey>> cashFlowDepreciation) {
        return calculate(input, cik, currency, day, calculatedAt, reports, cashFlowDepreciation, ignored -> {});
    }

    public Flows calculate(List<SecFinancialFact> input, String cik, String currency, LocalDate day, Instant calculatedAt,
                           Collection<ReportValues> reports, Function<List<SecFinancialFact>, Set<FactKey>> cashFlowDepreciation,
                           Consumer<SecFinancialFact> unresolvedDepreciation) {
        PeriodSelection selection = selectPeriods(reports, input, day, calculatedAt);
        return calculateSelectedFacts(selection.facts(), cik, currency, day, calculatedAt, selection.reports(), cashFlowDepreciation, unresolvedDepreciation);
    }

    /** 財季已配對的資料沿用原有全年／TTM 公式，不再以原股價日封鎖後續取得的財報。 */
    public Flows calculateSelectedFacts(List<SecFinancialFact> input, String cik, String currency, LocalDate day, Instant calculatedAt,
                                       Collection<ReportValues> reports, Function<List<SecFinancialFact>, Set<FactKey>> cashFlowDepreciation,
                                       Consumer<SecFinancialFact> unresolvedDepreciation) {
        List<SecFinancialFact> eligible = eligible(input, cik, currency, day, calculatedAt);
        Set<FactKey> disclosedDepreciation = cashFlowDepreciation.apply(eligible);
        Flows latest = calculateEligible(eligible, cik, disclosedDepreciation, unresolvedDepreciation);
        Map<LocalDate, List<SecFinancialFact>> byEnd = new HashMap<>();
        for (SecFinancialFact fact : eligible) byEnd.computeIfAbsent(fact.getPeriodEnd(), ignored -> new ArrayList<>()).add(fact);
        List<ReportValues> sameCurrency = reports.stream().filter(report -> Objects.equals(currency, report.currency())).toList();
        List<ReportValues> missingQuarters = missingQuarters(sameCurrency, byEnd);
        List<ReportValues> periodsToMatch = new ArrayList<>(sameCurrency);
        periodsToMatch.addAll(missingQuarters);
        Map<ReportPeriod, Map<FinancialConcept, Flow>> periods = new HashMap<>();
        for (ReportValues report : periodsToMatch) {
            ReportPeriod period = new ReportPeriod(report.periodEnd(), report.periodType(), report.statementType());
            periods.computeIfAbsent(period, key -> reportedInputs(byEnd.getOrDefault(key.end(), List.of()), key,
                    disclosedDepreciation, unresolvedDepreciation));
        }
        return new Flows(cik, latest.revenue(), latest.freeCashFlow(), latest.dilutedEpsTtm(), latest.equity(), latest.ebitda(),
                latest.enterpriseValueAdjustment(), latest.inputs(), Map.copyOf(periods), missingQuarters);
    }

    /** 兩側富途報期確認季序，SEC 的真實日期確認中間缺季，避免猜測曆年或 52/53 週財年。 */
    private List<ReportValues> missingQuarters(List<ReportValues> reports, Map<LocalDate, List<SecFinancialFact>> byEnd) {
        List<ReportValues> missing = new ArrayList<>();
        for (int statement : new int[]{1, 3}) {
            List<ReportValues> quarters = reports.stream().filter(row -> row.statementType() == statement
                            && row.periodType() >= 1 && row.periodType() <= 4 && row.fiscalYear() != null)
                    .sorted(Comparator.comparing(ReportValues::periodEnd)).toList();
            for (int index = 1; index < quarters.size(); index++) {
                ReportValues before = quarters.get(index - 1);
                ReportValues after = quarters.get(index);
                int quarter = before.fiscalYear() * 4 + before.periodType();
                if (after.fiscalYear() * 4 + after.periodType() != quarter + 2) continue;
                Set<LocalDate> starts = quarterStarts(byEnd, after.periodEnd());
                if (starts.size() != 1) continue;
                LocalDate end = starts.iterator().next().minusDays(1);
                if (!quarterStarts(byEnd, end).equals(Set.of(before.periodEnd().plusDays(1)))) continue;
                missing.add(new ReportValues(after.figi(), statement, end, quarter % 4 + 1, quarter / 4,
                        after.currency(), "US_GAAP", Map.of(), List.of(), List.of()));
            }
            // 末尾尚未取得的富途季度，只有在 SEC 實際單季緊接已知期末時才建立補缺報期。
            if (statement != 1 || quarters.isEmpty()) continue;
            ReportValues last = quarters.getLast();
            LocalDate lastEnd = last.periodEnd();
            int sequence = last.fiscalYear() * 4 + last.periodType();
            for (int offset = 0; offset < 3; offset++) {
                LocalDate priorEnd = lastEnd;
                List<LocalDate> possible = byEnd.keySet().stream().filter(end -> end.isAfter(priorEnd)
                        && quarterStarts(byEnd, end).equals(Set.of(priorEnd.plusDays(1)))).sorted().toList();
                if (possible.size() != 1) break;
                LocalDate end = possible.getFirst();
                sequence++;
                missing.add(new ReportValues(last.figi(), statement, end, (sequence - 1) % 4 + 1, (sequence - 1) / 4,
                        last.currency(), "US_GAAP", Map.of(), List.of(), List.of()));
                lastEnd = end;
            }
        }
        return List.copyOf(missing);
    }

    private Set<LocalDate> quarterStarts(Map<LocalDate, List<SecFinancialFact>> byEnd, LocalDate end) {
        Set<LocalDate> starts = new HashSet<>();
        for (SecFinancialFact fact : byEnd.getOrDefault(end, List.of())) {
            if (quarter(new Period(fact.getPeriodStart(), end))) starts.add(fact.getPeriodStart());
        }
        return starts;
    }

    public Flows calculate(List<SecFinancialFact> input, String cik, String currency, LocalDate day, Instant calculatedAt) {
        List<SecFinancialFact> eligible = eligible(selectPeriods(List.of(), input, day, calculatedAt).facts(), cik, currency, day, calculatedAt);
        return calculateEligible(eligible, cik, Set.of(), ignored -> {});
    }

    public Flow dilutedEps(List<SecFinancialFact> eligibleEps) {
        return trailing(eligibleEps, "EarningsPerShareDiluted");
    }

    private List<SecFinancialFact> eligible(List<SecFinancialFact> input, String cik, String currency, LocalDate day, Instant calculatedAt) {
        return input.stream().filter(fact -> Objects.equals(cik, fact.getCik()) && "us-gaap".equals(fact.getTaxonomy())
                && currency != null && expectedUnit(fact.getTag(), currency).equals(fact.getUnit())
                && fact.getAvailableFromDate() != null
                && fact.getFiled() != null && !fact.getFiled().isAfter(calculatedAt.atZone(ZoneId.of("America/New_York")).toLocalDate())
                && fact.getPeriodEnd() != null && !fact.getPeriodEnd().isAfter(fact.getFiled())
                && fact.getObservedAt() != null && !fact.getObservedAt().isAfter(calculatedAt)).toList();
    }

    private String expectedUnit(String tag, String currency) {
        return switch (tag) {
            case "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted" -> currency + "/shares";
            case "PreferredStockSharesOutstanding", "PreferredStockSharesIssued" -> "shares";
            default -> currency;
        };
    }

    private Flows calculateEligible(List<SecFinancialFact> eligible, String cik, Set<FactKey> disclosedDepreciation,
                                    Consumer<SecFinancialFact> unresolvedDepreciation) {
        // 先用定期申報的報表欄位證明報期，不以個別估值分母最後一次出現的期末代替。
        LocalDate incomeEnd = latestReportEnd(eligible, INCOME_PERIOD_TAGS, false);
        LocalDate balanceEnd = latestReportEnd(eligible, BALANCE_PERIOD_TAGS, true);
        LocalDate cashFlowEnd = latestReportEnd(eligible, CASH_FLOW_PERIOD_TAGS, false);
        Flow revenue = revenue(eligible, this::trailing);
        Flow cfo = operatingCashFlow(eligible, this::trailing);
        Flow capex = capitalExpenditure(eligible);
        Flow fcf;
        if (cfo.value() == null || capex.value() == null) {
            String conflict = cfo.reason() != null && cfo.reason().contains("CONFLICT") ? cfo.reason()
                    : capex.reason() != null && capex.reason().contains("CONFLICT") ? capex.reason() : "SEC_FCF_COMPONENTS_MISSING";
            fcf = missing(conflict, later(cfo.periodEnd(), capex.periodEnd()));
        }
        else if (!Objects.equals(cfo.periodEnd(), capex.periodEnd()))
            fcf = missing("SEC_FCF_PERIOD_MISMATCH", later(cfo.periodEnd(), capex.periodEnd()));
        else if (!Objects.equals(cfo.periodStart(), capex.periodStart())) fcf = missing("SEC_FCF_SCOPE_CONFLICT", cfo.periodEnd());
        else if (capex.value().signum() < 0) fcf = missing("SEC_CAPITAL_EXPENDITURE_SIGN_UNVERIFIED", capex.periodEnd());
        else fcf = new Flow(cfo.value().subtract(capex.value()), cfo.periodStart(), cfo.periodEnd(),
                    later(cfo.availableFrom(), capex.availableFrom()), later(cfo.observedAt(), capex.observedAt()), null, null);
        Flow eps = dilutedEps(eligible, this::trailing);
        Flow equity = commonEquity(eligible);
        Flow depreciation = depreciationAndAmortization(eligible, this::trailing, disclosedDepreciation, unresolvedDepreciation);
        Map<FinancialConcept, Flow> inputs = new EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.OPERATING_CASH_FLOW, cfo);
        inputs.put(FinancialConcept.CAPITAL_EXPENDITURE, capex);
        inputs.put(FinancialConcept.CURRENT_FINANCE_LEASES, leasePart(eligible, "FinanceLeaseLiability", "Current", "Noncurrent"));
        inputs.put(FinancialConcept.LONG_TERM_FINANCE_LEASES, leasePart(eligible, "FinanceLeaseLiability", "Noncurrent", "Current"));
        inputs.put(FinancialConcept.TOTAL_FINANCE_LEASES, balance(eligible, "FinanceLeaseLiability"));
        inputs.put(FinancialConcept.CURRENT_OPERATING_LEASES, leasePart(eligible, "OperatingLeaseLiability", "Current", "Noncurrent"));
        inputs.put(FinancialConcept.LONG_TERM_OPERATING_LEASES, leasePart(eligible, "OperatingLeaseLiability", "Noncurrent", "Current"));
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, balance(eligible, "CashAndCashEquivalentsAtCarryingValue"));
        Flow preferred = balance(eligible, "PreferredStockValue");
        // 此 SEC tag 是 par/stated value；非零面值不能當作優先股完整帳面價值扣除。
        Flow parent = balance(eligible, "StockholdersEquity");
        inputs.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, aligned(parent, equity)
                ? combine(parent.value().subtract(equity.value()), List.of(parent, equity))
                : preferred.value() != null && preferred.value().signum() == 0 ? preferred : missing("SEC_COMMON_EQUITY_UNRESOLVED", preferred.periodEnd()));
        inputs.put(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, balance(eligible, "MinorityInterest"));
        inputs.put(FinancialConcept.STOCKHOLDERS_EQUITY, balance(eligible, "StockholdersEquity"));
        inputs.put(FinancialConcept.TOTAL_EQUITY, balance(eligible, "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"));
        inputs.put(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED,
                balance(eligible, "AccumulatedOtherComprehensiveIncomeLossNetOfTax"));
        Flow profit = trailing(eligible, "OperatingIncomeLoss");
        inputs.put(FinancialConcept.OPERATING_PROFIT, profit);
        inputs.put(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, depreciation);
        LocalDate ebitdaEnd = later(profit.periodEnd(), depreciation.periodEnd());
        Flow ebitda = aligned(profit, depreciation) ? combine(profit.value().add(depreciation.value()), List.of(profit, depreciation))
                : missing(DEPRECIATION_SCOPE_UNRESOLVED.equals(depreciation.reason()) && Objects.equals(ebitdaEnd, depreciation.periodEnd())
                        ? DEPRECIATION_SCOPE_UNRESOLVED : "SEC_EBITDA_COMPONENTS_UNRESOLVED", ebitdaEnd);
        return new Flows(cik, requireReportEnd(revenue, incomeEnd, "SEC_REVENUE_MISSING"),
                requireReportEnd(fcf, cashFlowEnd, "SEC_FCF_COMPONENTS_MISSING"),
                requireReportEnd(eps, incomeEnd, "SEC_EPS_TTM_PERIODS_MISSING"),
                requireReportEnd(equity, balanceEnd, "SEC_COMMON_EQUITY_UNRESOLVED"),
                requireReportEnd(ebitda, incomeEnd, "SEC_EBITDA_COMPONENTS_UNRESOLVED"),
                requireReportEnd(enterpriseValueAdjustment(eligible, equity), balanceEnd, "SEC_EV_COMPONENTS_UNRESOLVED"),
                Map.copyOf(inputs), Map.of(), List.of());
    }

    private LocalDate latestReportEnd(List<SecFinancialFact> facts, Set<String> tags, boolean balanceSheet) {
        LocalDate latest = null;
        for (SecFinancialFact fact : facts) {
            if (!tags.contains(fact.getTag()) || fact.getForm() == null || !PERIODIC_REPORTS.contains(fact.getForm())
                    || fact.getPeriodEnd().isAfter(fact.getFiled())) continue;
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            long days = length(period);
            // 流量按完整單季、半年度／前三季累計或全年；局部月度、交易及封面資訊沒有整份報表的報期語意。
            boolean statementPeriod = balanceSheet ? period.start() == null
                    : quarter(period) || (days >= 160 && days <= 200) || (days >= 240 && days <= 300) || annual(period);
            if (statementPeriod) latest = later(latest, period.end());
        }
        return latest;
    }

    private Flow requireReportEnd(Flow flow, LocalDate reportEnd, String missingReason) {
        return reportEnd != null && (flow.periodEnd() == null || flow.periodEnd().isBefore(reportEnd))
                ? missing(missingReason, reportEnd) : flow;
    }

    /** 先配對一份財報：同一期末的單季與全年分開，累計值不直接塞進單季。 */
    private Map<FinancialConcept, Flow> reportedInputs(List<SecFinancialFact> facts, ReportPeriod report, Set<FactKey> disclosedDepreciation,
                                                       Consumer<SecFinancialFact> unresolvedDepreciation) {
        List<SecFinancialFact> matching = facts.stream().filter(fact -> matchesReportPeriod(fact, report)).toList();
        if (report.statementType() == 2) return calculateEligible(matching, null, Set.of(), ignored -> {}).inputs();
        Map<FinancialConcept, Flow> inputs = new EnumMap<>(FinancialConcept.class);
        if (report.statementType() == 1) {
            inputs.put(FinancialConcept.REVENUE_AS_REPORTED, revenue(matching, this::reported));
            inputs.put(FinancialConcept.DILUTED_EPS, dilutedEps(matching, this::reported));
            inputs.put(FinancialConcept.OPERATING_PROFIT, reported(matching, "OperatingIncomeLoss"));
            inputs.put(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, depreciationAndAmortization(matching, this::reported,
                    disclosedDepreciation, unresolvedDepreciation));
        } else if (report.statementType() == 3) {
            inputs.put(FinancialConcept.OPERATING_CASH_FLOW, operatingCashFlow(matching, this::reported));
            Flow capex = reported(matching, "PaymentsToAcquireProductiveAssets");
            if (capex.periodEnd() == null) {
                capex = reported(matching, "PaymentsToAcquirePropertyPlantAndEquipment");
                for (String tag : List.of("PaymentsToAcquireSoftware", "PaymentsToDevelopSoftware")) {
                    Flow software = reported(matching, tag);
                    if (software.periodEnd() == null) continue;
                    capex = aligned(capex, software) ? combine(capex.value().add(software.value()), List.of(capex, software))
                            : missing("SEC_FCF_SCOPE_CONFLICT", report.end());
                }
            }
            inputs.put(FinancialConcept.CAPITAL_EXPENDITURE, capex);
        }
        return Map.copyOf(inputs);
    }

    private Flow depreciationAndAmortization(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read,
                                            Set<FactKey> disclosedDepreciation, Consumer<SecFinancialFact> unresolvedDepreciation) {
        Map<FactKey, SecFinancialFact> components = new HashMap<>();
        for (SecFinancialFact fact : facts) {
            if (DEPRECIATION_COMPONENTS.contains(fact.getTag()) || DEPRECIATION_TOTAL_TAGS.contains(fact.getTag())) components.put(key(fact), fact);
        }
        Map<FactKey, SecFinancialFact> totals = new LinkedHashMap<>();
        List<SecFinancialFact> reportedTotals = new ArrayList<>();
        List<SecFinancialFact> unresolved = new ArrayList<>();
        for (SecFinancialFact fact : facts) {
            String tag = fact.getTag();
            if (!disclosedDepreciation.isEmpty() && disclosedDepreciation.contains(key(fact))) {
                reportedTotals.add(depreciationTotal(fact, null));
                continue;
            }
            if ("Depreciation".equals(tag)) {
                SecFinancialFact amortization = components.get(withTag(fact, "AmortizationOfIntangibleAssets"));
                // 已申報合計時，附註個別科目可能只涵蓋一部分，須用合計或已對平的拆分。
                if (nonNegative(fact, amortization) && DEPRECIATION_TOTAL_TAGS.stream().noneMatch(
                        totalTag -> components.containsKey(withTag(fact, totalTag))))
                    totals.putIfAbsent(withTag(fact, DEPRECIATION_AMORTIZATION), depreciationTotal(fact, amortization));
                continue;
            }
            String adjustmentTag = switch (tag) {
                case DEPRECIATION_AMORTIZATION_ACCRETION -> ACCRETION_EXPENSE;
                case DEPRECIATION_DEPLETION_AMORTIZATION -> "Depletion";
                default -> null;
            };
            if (adjustmentTag == null) {
                if (DEPRECIATION_AMORTIZATION.equals(tag)) unresolved.add(fact);
                continue;
            }
            SecFinancialFact adjustment = components.get(withTag(fact, adjustmentTag));
            SecFinancialFact depreciation = components.get(withTag(fact, "Depreciation"));
            SecFinancialFact amortization = components.get(withTag(fact, ADJUSTMENT_FOR_AMORTIZATION));
            // 同申報的扣除項可能屬於另一列，拆分後須與折舊加攤銷明細對平。
            if (nonNegative(fact, adjustment) && nonNegative(depreciation, amortization)
                    && fact.getValue().subtract(adjustment.getValue()).compareTo(depreciation.getValue().add(amortization.getValue())) == 0) {
                SecFinancialFact total = depreciationTotal(depreciation, amortization);
                total.setAvailableFromDate(later(total.getAvailableFromDate(), later(fact.getAvailableFromDate(), adjustment.getAvailableFromDate())));
                total.setObservedAt(later(total.getObservedAt(), later(fact.getObservedAt(), adjustment.getObservedAt())));
                totals.put(withTag(fact, DEPRECIATION_AMORTIZATION), total);
            } else unresolved.add(fact);
        }
        for (SecFinancialFact total : reportedTotals) {
            // 主表合計優先；若同一份申報有兩筆已確認但金額不同，交由版本衝突檢查處理。
            totals.remove(key(total));
        }
        reportedTotals.addAll(totals.values());
        Flow result = read.apply(reportedTotals, DEPRECIATION_AMORTIZATION);
        LocalDate unresolvedEnd = unresolved.stream().map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        if (unresolvedEnd != null && (result.periodEnd() == null || unresolvedEnd.isAfter(result.periodEnd())
                || unresolvedEnd.equals(result.periodEnd()) && result.value() == null && !result.reason().contains("CONFLICT"))) {
            LocalDate previousYear = unresolvedEnd.minusYears(1).minusDays(7);
            unresolved.stream().filter(fact -> fact.getPeriodStart() != null && fact.getValue().signum() >= 0
                    && !fact.getPeriodEnd().isBefore(previousYear)).forEach(unresolvedDepreciation);
            return missing(DEPRECIATION_SCOPE_UNRESOLVED, unresolvedEnd);
        }
        return result;
    }

    private FactKey withTag(SecFinancialFact fact, String tag) {
        return new FactKey(fact.getCik(), fact.getTaxonomy(), tag, fact.getUnit(), fact.getPeriodStart(), fact.getPeriodEnd(), fact.getAccession());
    }

    private boolean nonNegative(SecFinancialFact first, SecFinancialFact second) {
        return first != null && second != null && first.getValue().signum() >= 0 && second.getValue().signum() >= 0;
    }

    private SecFinancialFact depreciationTotal(SecFinancialFact depreciation, SecFinancialFact amortization) {
        SecFinancialFact total = new SecFinancialFact();
        total.setCik(depreciation.getCik());
        total.setTaxonomy(depreciation.getTaxonomy());
        total.setTag(DEPRECIATION_AMORTIZATION);
        total.setUnit(depreciation.getUnit());
        total.setAccession(depreciation.getAccession());
        total.setPeriodStart(depreciation.getPeriodStart());
        total.setPeriodEnd(depreciation.getPeriodEnd());
        total.setFiled(amortization == null ? depreciation.getFiled() : later(depreciation.getFiled(), amortization.getFiled()));
        total.setValue(amortization == null ? depreciation.getValue() : depreciation.getValue().add(amortization.getValue()));
        total.setAvailableFromDate(amortization == null ? depreciation.getAvailableFromDate() : later(depreciation.getAvailableFromDate(), amortization.getAvailableFromDate()));
        total.setObservedAt(amortization == null ? depreciation.getObservedAt() : later(depreciation.getObservedAt(), amortization.getObservedAt()));
        return total;
    }

    private boolean matchesReportPeriod(SecFinancialFact fact, ReportPeriod report) {
        if (!report.end().equals(fact.getPeriodEnd())) return false;
        if (report.statementType() == 2) return fact.getPeriodStart() == null;
        Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
        if (report.periodType() == 7) return annual(period);
        return report.periodType() >= 1 && report.periodType() <= 4 && quarter(period);
    }

    private Flow reported(List<SecFinancialFact> facts, String tag) {
        List<SecFinancialFact> tagged = facts.stream().filter(fact -> tag.equals(fact.getTag())).toList();
        if (tagged.isEmpty()) return missing("SEC_FINANCIAL_INPUT_MISSING", null);
        Map<Period, Point> periods = revisions(tagged);
        if (periods.size() != 1) return missing("SEC_FACT_PERIOD_CONFLICT", tagged.getFirst().getPeriodEnd());
        Point selected = periods.values().iterator().next();
        if (selected.conflict()) return missing("SEC_FACT_VERSION_CONFLICT", selected.fact().getPeriodEnd());
        return flow(selected.fact().getValue(), selected.fact().getPeriodStart(), selected.fact().getPeriodEnd(), List.of(selected));
    }

    private Flow capitalExpenditure(List<SecFinancialFact> facts) {
        Flow total = trailing(facts, "PaymentsToAcquireProductiveAssets");
        Flow ppe = trailing(facts, "PaymentsToAcquirePropertyPlantAndEquipment");
        if (total.periodEnd() != null && (ppe.periodEnd() == null || !total.periodEnd().isBefore(ppe.periodEnd()))) return total;
        if (ppe.value() == null) return ppe;
        List<Flow> components = new ArrayList<>(List.of(ppe));
        BigDecimal amount = ppe.value();
        // 沒有總額時採 PP&E 現金支出及另行披露的軟件資本支出，不與 productive-assets 總額重複相加。
        for (String tag : List.of("PaymentsToAcquireSoftware", "PaymentsToDevelopSoftware")) {
            if (facts.stream().noneMatch(fact -> tag.equals(fact.getTag()) && ppe.periodEnd().equals(fact.getPeriodEnd()))) continue;
            Flow software = trailing(facts, tag);
            if (!aligned(ppe, software)) return missing("SEC_FCF_SCOPE_CONFLICT", ppe.periodEnd());
            if (software.value().signum() < 0) return missing("SEC_CAPITAL_EXPENDITURE_SIGN_UNVERIFIED", ppe.periodEnd());
            amount = amount.add(software.value());
            components.add(software);
        }
        return combine(amount, components);
    }

    private Flow commonEquity(List<SecFinancialFact> facts) {
        String basisTag = "StockholdersEquity";
        Flow parent = balance(facts, basisTag);
        String totalTag = "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest";
        Flow total = balance(facts, totalTag);
        // 若最新申報未另列母公司權益，須以同份申報的總權益減非控權益，不能跨公告拼接。
        if (total.value() != null && (parent.periodEnd() == null || total.periodEnd().isAfter(parent.periodEnd()))) {
            List<SecFinancialFact> totals = facts.stream().filter(fact -> totalTag.equals(fact.getTag())
                    && fact.getPeriodStart() == null && total.periodEnd().equals(fact.getPeriodEnd())).toList();
            LocalDate filed = totals.stream().map(SecFinancialFact::getFiled).max(LocalDate::compareTo).orElseThrow();
            List<SecFinancialFact> latest = totals.stream().filter(fact -> filed.equals(fact.getFiled())).toList();
            if (latest.size() != 1) return missing("SEC_COMMON_EQUITY_UNRESOLVED", total.periodEnd());
            String accession = latest.getFirst().getAccession();
            List<SecFinancialFact> filing = facts.stream().filter(fact -> accession.equals(fact.getAccession())
                    && total.periodEnd().equals(fact.getPeriodEnd())).toList();
            Flow minority = balance(filing, "MinorityInterest");
            if (!aligned(total, minority)) return missing("SEC_COMMON_EQUITY_UNRESOLVED", total.periodEnd());
            parent = combine(total.value().subtract(minority.value()), List.of(total, minority));
            basisTag = totalTag;
        }
        if (parent.value() == null) return parent;
        LocalDate selectedEnd = parent.periodEnd();
        List<SecFinancialFact> samePeriod = facts.stream().filter(fact -> selectedEnd.equals(fact.getPeriodEnd())).toList();
        // 明示無已發行優先股時，母公司股東權益已是普通股權益。零面值本身不足以證明無優先股。
        String selectedTag = basisTag;
        List<SecFinancialFact> parentVersions = samePeriod.stream().filter(fact -> selectedTag.equals(fact.getTag())
                && fact.getPeriodStart() == null).toList();
        LocalDate filed = parentVersions.stream().map(SecFinancialFact::getFiled).max(LocalDate::compareTo).orElseThrow();
        List<SecFinancialFact> latestParents = parentVersions.stream().filter(fact -> filed.equals(fact.getFiled())).toList();
        if (latestParents.size() == 1) {
            String accession = latestParents.getFirst().getAccession();
            List<SecFinancialFact> filing = samePeriod.stream().filter(fact -> accession.equals(fact.getAccession())).toList();
            Flow outstanding = balance(filing, "PreferredStockSharesOutstanding");
            Flow issued = balance(filing, "PreferredStockSharesIssued");
            Flow zeroShares = outstanding.value() == null ? issued : outstanding;
            Flow carryingValue = balance(filing, "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount");
            if (carryingValue.value() != null && carryingValue.value().signum() > 0
                    && !nonZero(filing, "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue"))
                return combine(parent.value().subtract(carryingValue.value()), List.of(parent, carryingValue));
            // 已發行股數可能含庫藏股；同份申報確認流通數為零時不因已發行數非零而否決。
            if (zeroShares.value() != null && zeroShares.value().signum() == 0
                    && !nonZero(filing, "PreferredStockValue", "PreferredStockValueOutstanding", "PreferredStockSharesOutstanding",
                    "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue"))
                return combine(parent.value(), List.of(parent, zeroShares));
            // 未單列優先股時，僅在同份申報的普通股組成完整且精確對平母公司權益才補值。
            Flow complete = separateCommonEquity(filing, parent);
            if (complete.value() != null) return complete;
        }
        if (nonZero(samePeriod, "PreferredStockValue", "PreferredStockValueOutstanding"))
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", parent.periodEnd());
        List<SecFinancialFact> componentsSource = samePeriod;
        Flow treasury = balance(samePeriod, "TreasuryStockValue");
        Flow commonTreasury = balance(samePeriod, "TreasuryStockCommonValue");
        if (treasury.periodEnd() != null || commonTreasury.periodEnd() != null) {
            if (latestParents.size() != 1) return missing("SEC_COMMON_EQUITY_UNRESOLVED", parent.periodEnd());
            String accession = latestParents.getFirst().getAccession();
            componentsSource = samePeriod.stream().filter(fact -> accession.equals(fact.getAccession())).toList();
            treasury = balance(componentsSource, "TreasuryStockValue");
            if (treasury.periodEnd() == null) treasury = balance(componentsSource, "TreasuryStockCommonValue");
            if (treasury.value() == null || treasury.value().signum() < 0)
                return missing("SEC_COMMON_EQUITY_UNRESOLVED", parent.periodEnd());
        }
        Flow commonCapital = balance(componentsSource, "CommonStocksIncludingAdditionalPaidInCapital");
        Flow retained = balance(componentsSource, "RetainedEarningsAccumulatedDeficit");
        Flow comprehensive = balance(componentsSource, "AccumulatedOtherComprehensiveIncomeLossNetOfTax");
        Flow convertiblePreferred = balance(componentsSource, "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue");
        BigDecimal preferred = convertiblePreferred.value() == null ? BigDecimal.ZERO : convertiblePreferred.value();
        List<Flow> components = new ArrayList<>(List.of(parent, commonCapital, retained, comprehensive));
        if (convertiblePreferred.value() != null) components.add(convertiblePreferred);
        if (components.stream().anyMatch(component -> component.value() == null))
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", parent.periodEnd());
        BigDecimal reconciled = commonCapital.value().add(retained.value()).add(comprehensive.value()).add(preferred);
        if (treasury.value() != null) {
            reconciled = reconciled.subtract(treasury.value());
            components.add(treasury);
        }
        if (reconciled.compareTo(parent.value()) != 0) return missing("SEC_COMMON_EQUITY_UNRESOLVED", parent.periodEnd());
        return combine(parent.value().subtract(preferred), components);
    }

    /** 只有原始同份申報的普通股股本、資本公積及權益明細能精確對平，才視為無未拆出的優先股權益。 */
    private Flow separateCommonEquity(List<SecFinancialFact> filing, Flow parent) {
        LocalDate end = parent.periodEnd();
        if (nonZero(filing, "PreferredStockValue", "PreferredStockValueOutstanding", "PreferredStockSharesOutstanding",
                "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount",
                "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue"))
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);
        Flow issued = balance(filing, "PreferredStockSharesIssued");
        Flow outstanding = balance(filing, "PreferredStockSharesOutstanding");
        if (issued.value() != null && issued.value().signum() > 0
                && (outstanding.value() == null || outstanding.value().signum() != 0))
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);

        Flow common = balance(filing, "CommonStockValue");
        Flow paidIn = balance(filing, "AdditionalPaidInCapital");
        Flow paidInCommon = balance(filing, "AdditionalPaidInCapitalCommonStock");
        if (paidIn.value() != null && paidInCommon.value() != null && paidIn.value().compareTo(paidInCommon.value()) != 0)
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);
        Flow capital = paidIn.value() != null ? paidIn : paidInCommon;
        Flow retained = balance(filing, "RetainedEarningsAccumulatedDeficit");
        Flow comprehensive = balance(filing, "AccumulatedOtherComprehensiveIncomeLossNetOfTax");
        if (common.value() == null || capital.value() == null || retained.value() == null || comprehensive.value() == null)
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);

        Flow totalTreasury = balance(filing, "TreasuryStockValue");
        Flow commonTreasury = balance(filing, "TreasuryStockCommonValue");
        if (totalTreasury.value() != null && commonTreasury.value() != null
                && totalTreasury.value().compareTo(commonTreasury.value()) != 0)
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);
        Flow treasury = totalTreasury.value() != null ? totalTreasury : commonTreasury;
        if (treasury.value() != null && treasury.value().signum() < 0)
            return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);

        BigDecimal sum = common.value().add(capital.value()).add(retained.value()).add(comprehensive.value());
        if (treasury.value() != null) sum = sum.subtract(treasury.value());
        if (sum.compareTo(parent.value()) != 0) return missing("SEC_COMMON_EQUITY_UNRESOLVED", end);
        List<Flow> parts = new ArrayList<>(List.of(parent, common, capital, retained, comprehensive));
        if (treasury.value() != null) parts.add(treasury);
        return combine(parent.value(), parts);
    }

    private boolean nonZero(List<SecFinancialFact> facts, String... tags) {
        Set<String> concepts = Set.of(tags);
        return facts.stream().anyMatch(fact -> concepts.contains(fact.getTag()) && fact.getValue().signum() != 0);
    }

    /** 與 Futu 相同：債務含融資租賃＋非控權益－現金；不加營運租賃、不扣投資。 */
    private Flow enterpriseValueAdjustment(List<SecFinancialFact> facts, Flow equity) {
        Flow cash = balance(facts, "CashAndCashEquivalentsAtCarryingValue");
        LocalDate end = cash.periodEnd();
        if (cash.value() == null) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        List<SecFinancialFact> samePeriod = facts.stream().filter(fact -> end.equals(fact.getPeriodEnd())).toList();
        if (nonZero(samePeriod, "PreferredStockValue", "PreferredStockValueOutstanding", "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount",
                "PreferredStockSharesOutstanding", "PreferredStockSharesIssued", "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue", "RedeemableNoncontrollingInterestEquityCarryingAmount"))
            return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        List<Flow> components = new ArrayList<>(List.of(cash));
        Flow preferred = balance(samePeriod, "PreferredStockValue");
        if (preferred.value() == null) preferred = balance(samePeriod, "PreferredStockValueOutstanding");
        if (preferred.value() != null) components.add(preferred);
        else if (equity.value() != null && end.equals(equity.periodEnd())) components.add(equity);
        else return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);

        Flow minority = balance(samePeriod, "MinorityInterest");
        if (minority.value() == null) {
            Flow parent = balance(samePeriod, "StockholdersEquity");
            Flow total = balance(samePeriod, "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
            if (total.value() == null) {
                Flow assets = balance(samePeriod, "Assets");
                Flow liabilities = balance(samePeriod, "Liabilities");
                if (assets.value() != null && liabilities.value() != null)
                    total = combine(assets.value().subtract(liabilities.value()), List.of(assets, liabilities));
            }
            if (parent.value() == null || total.value() == null) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
            minority = combine(total.value().subtract(parent.value()), List.of(total, parent));
        }
        components.add(minority);

        Flow debt = balance(samePeriod, "DebtAndCapitalLeaseObligations");
        if (debt.value() == null) debt = addBalances(samePeriod, "DebtCurrent", "LongTermDebtAndCapitalLeaseObligations");
        if (debt.value() == null) debt = reconciledDebt(samePeriod);
        if (debt.value() == null) {
            Flow term = balance(samePeriod, "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities");
            if (term.value() == null) {
                Flow borrowing = balance(samePeriod, "LongTermDebt");
                if (borrowing.value() == null) borrowing = addBalances(samePeriod, "LongTermDebtCurrent", "LongTermDebtNoncurrent");
                Flow finance = balance(samePeriod, "FinanceLeaseLiability");
                if (finance.value() == null) finance = addBalances(samePeriod, "FinanceLeaseLiabilityCurrent", "FinanceLeaseLiabilityNoncurrent");
                if (finance.value() == null) finance = balance(samePeriod, "CapitalLeaseObligations");
                if (finance.value() == null) finance = addBalances(samePeriod, "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent");
                if (borrowing.value() == null || finance.value() == null) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
                term = combine(borrowing.value().add(finance.value()), List.of(borrowing, finance));
            }
            Flow shortTerm = balance(samePeriod, "ShortTermBorrowings");
            if (shortTerm.value() == null) shortTerm = balance(samePeriod, "CommercialPaper");
            if (shortTerm.value() == null) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
            debt = combine(term.value().add(shortTerm.value()), List.of(term, shortTerm));
        }
        components.add(debt);
        if (debt.value().signum() < 0 || cash.value().signum() < 0)
            return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        return combine(debt.value().add(minority.value()).subtract(cash.value()), components);
    }

    private Flow reconciledDebt(List<SecFinancialFact> facts) {
        // 舊表可能只列票據與長債。用同份報表對齊所有已列負債，確認流動長債和票據分列。
        Flow result = missing("SEC_EV_COMPONENTS_UNRESOLVED", facts.getFirst().getPeriodEnd());
        List<String> tags = List.of("Liabilities", "LiabilitiesCurrent", "AccountsPayableCurrent", "AccruedLiabilitiesCurrent",
                "DeferredRevenueCurrent", "DeferredRevenueNoncurrent", "OtherLiabilitiesNoncurrent",
                "LongTermDebtCurrent", "LongTermDebtNoncurrent", "CommercialPaper");
        Map<String, List<SecFinancialFact>> filings = new LinkedHashMap<>();
        for (SecFinancialFact fact : facts) {
            if ("Liabilities".equals(fact.getTag())) filings.put(fact.getAccession(), new ArrayList<>());
        }
        for (SecFinancialFact fact : facts) {
            List<SecFinancialFact> filing = filings.get(fact.getAccession());
            if (filing != null) filing.add(fact);
        }
        LocalDate latest = facts.stream().map(SecFinancialFact::getFiled).max(LocalDate::compareTo).orElseThrow();
        for (List<SecFinancialFact> filing : filings.values()) {
            if (!latest.equals(filing.getFirst().getFiled()) || nonZero(filing, "FinanceLeaseLiability", "FinanceLeaseLiabilityCurrent",
                    "FinanceLeaseLiabilityNoncurrent", "CapitalLeaseObligations", "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent",
                    "LongTermDebtAndCapitalLeaseObligationsCurrent", "LongTermDebtAndCapitalLeaseObligations")) continue;
            Map<String, Flow> lines = new LinkedHashMap<>();
            for (String tag : tags) lines.put(tag, balance(filing, tag));
            if (lines.values().stream().anyMatch(flow -> flow.value() == null || flow.value().signum() < 0)) continue;
            BigDecimal current = lines.get("LiabilitiesCurrent").value();
            BigDecimal debtCurrent = lines.get("LongTermDebtCurrent").value().add(lines.get("CommercialPaper").value());
            BigDecimal debtNoncurrent = lines.get("LongTermDebtNoncurrent").value();
            BigDecimal currentNonDebt = lines.get("AccountsPayableCurrent").value().add(lines.get("AccruedLiabilitiesCurrent").value())
                    .add(lines.get("DeferredRevenueCurrent").value());
            BigDecimal noncurrentNonDebt = lines.get("DeferredRevenueNoncurrent").value().add(lines.get("OtherLiabilitiesNoncurrent").value());
            if (currentNonDebt.add(debtCurrent).compareTo(current) != 0
                    || current.add(debtNoncurrent).add(noncurrentNonDebt).compareTo(lines.get("Liabilities").value()) != 0) continue;
            Flow candidate = combine(debtCurrent.add(debtNoncurrent), List.copyOf(lines.values()));
            if (result.value() != null && result.value().compareTo(candidate.value()) != 0)
                return missing("SEC_EV_COMPONENTS_UNRESOLVED", candidate.periodEnd());
            result = candidate;
        }
        return result;
    }

    private Flow addBalances(List<SecFinancialFact> facts, String firstTag, String secondTag) {
        Flow first = balance(facts, firstTag);
        Flow second = balance(facts, secondTag);
        if (!aligned(first, second)) return missing("SEC_EV_COMPONENTS_UNRESOLVED", later(first.periodEnd(), second.periodEnd()));
        return combine(first.value().add(second.value()), List.of(first, second));
    }

    private Flow leasePart(List<SecFinancialFact> facts, String tag, String part, String remainder) {
        Flow reported = balance(facts, tag + part);
        Flow total = balance(facts, tag);
        if (total.value() == null || reported.periodEnd() != null && !reported.periodEnd().isBefore(total.periodEnd())) return reported;
        List<SecFinancialFact> totals = facts.stream().filter(fact -> tag.equals(fact.getTag()) && fact.getPeriodStart() == null
                && total.periodEnd().equals(fact.getPeriodEnd())).toList();
        LocalDate filed = totals.stream().map(SecFinancialFact::getFiled).max(LocalDate::compareTo).orElseThrow();
        List<Flow> derived = new ArrayList<>();
        for (SecFinancialFact disclosure : totals) {
            if (!filed.equals(disclosure.getFiled())) continue;
            List<SecFinancialFact> filing = facts.stream().filter(fact -> disclosure.getAccession().equals(fact.getAccession())
                    && total.periodEnd().equals(fact.getPeriodEnd())).toList();
            Flow other = balance(filing, tag + remainder);
            if (!aligned(total, other) || other.value().signum() < 0 || other.value().compareTo(total.value()) > 0) continue;
            derived.add(combine(total.value().subtract(other.value()), List.of(total, other)));
        }
        if (derived.isEmpty()) return reported;
        BigDecimal value = derived.getFirst().value();
        if (derived.stream().anyMatch(flow -> flow.value().compareTo(value) != 0))
            return missing("SEC_FACT_VERSION_CONFLICT", total.periodEnd());
        return combine(value, derived);
    }

    private Flow balance(List<SecFinancialFact> facts, String tag) {
        List<SecFinancialFact> tagged = facts.stream().filter(fact -> tag.equals(fact.getTag()) && fact.getPeriodStart() == null).toList();
        LocalDate end = tagged.stream().map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        if (end == null) return missing("SEC_BALANCE_INPUT_MISSING", null);
        LocalDate filed = tagged.stream().filter(fact -> end.equals(fact.getPeriodEnd())).map(SecFinancialFact::getFiled).max(LocalDate::compareTo).orElseThrow();
        List<Point> versions = tagged.stream().filter(fact -> end.equals(fact.getPeriodEnd()) && filed.equals(fact.getFiled()))
                .map(fact -> new Point(fact, false)).toList();
        BigDecimal value = versions.getFirst().fact().getValue();
        if (versions.stream().anyMatch(point -> point.fact().getValue().compareTo(value) != 0)) return missing("SEC_FACT_VERSION_CONFLICT", end);
        return flow(value, null, end, versions);
    }

    private boolean aligned(Flow first, Flow second) {
        return first.value() != null && second.value() != null
                && Objects.equals(first.periodStart(), second.periodStart()) && Objects.equals(first.periodEnd(), second.periodEnd());
    }

    private Flow combine(BigDecimal value, List<Flow> components) {
        Flow first = components.getFirst();
        LocalDate available = components.stream().map(Flow::availableFrom).max(LocalDate::compareTo).orElseThrow();
        Instant observed = components.stream().map(Flow::observedAt).max(Instant::compareTo).orElseThrow();
        return new Flow(value, first.periodStart(), first.periodEnd(), available, observed, null, null);
    }

    private Flow operatingCashFlow(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read) {
        Flow total = read.apply(facts, OPERATING_CASH_FLOW);
        Flow continuing = read.apply(facts, CONTINUING_OPERATING_CASH_FLOW);
        if (continuing.periodEnd() == null || total.periodEnd() != null && !continuing.periodEnd().isAfter(total.periodEnd())) return total;
        return continuing;
    }

    private Flow revenue(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read) {
        LocalDate latest = facts.stream().filter(fact -> List.of("Revenues", "SalesRevenueNet",
                        "RevenueFromContractWithCustomerExcludingAssessedTax", "RevenuesNetOfInterestExpense").contains(fact.getTag()))
                .map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        if (latest == null) return missing("SEC_REVENUE_MISSING", null);
        // 合約收入可能僅為保險服務費；金融淨收入不能當總收入，但不阻擋另外明示的申報總收入。
        boolean hasNetRevenue = facts.stream().anyMatch(fact ->
                "RevenuesNetOfInterestExpense".equals(fact.getTag()) && latest.equals(fact.getPeriodEnd()));
        List<Flow> candidates = new ArrayList<>();
        Flow incompleteTotal = null;
        for (String tag : List.of("Revenues", "SalesRevenueNet")) {
            Flow value = read.apply(facts, tag);
            if (latest.equals(value.periodEnd()) && value.reason() != null && value.reason().contains("CONFLICT")) return value;
            if (value.value() != null && latest.equals(value.periodEnd())) candidates.add(value);
            else if (latest.equals(value.periodEnd())) incompleteTotal = value;
        }
        Flow contractRevenue = read.apply(facts, "RevenueFromContractWithCustomerExcludingAssessedTax");
        Flow grossProfit = read.apply(facts, "GrossProfit");
        Flow costOfSales = read.apply(facts, "CostOfGoodsAndServicesSold");
        boolean verifiedContract = latest.equals(contractRevenue.periodEnd()) && aligned(contractRevenue, grossProfit)
                && aligned(contractRevenue, costOfSales) && costOfSales.value().signum() > 0
                && grossProfit.value().add(costOfSales.value()).compareTo(contractRevenue.value()) == 0;
        if (candidates.isEmpty()) {
            if (hasNetRevenue) return missing("SEC_REVENUE_NET_SCOPE_UNVERIFIED", latest);
            if (incompleteTotal != null) {
                List<SecFinancialFact> totals = facts.stream().filter(fact -> "Revenues".equals(fact.getTag())
                        || "SalesRevenueNet".equals(fact.getTag())).toList();
                Flow combined = trailing(totals, false);
                if (combined.value() != null && latest.equals(combined.periodEnd())) return combined;
                return incompleteTotal;
            }
            if (verifiedContract) return combine(contractRevenue.value(), List.of(contractRevenue, grossProfit, costOfSales));
            // 若四季與累計口徑混用造成無法對帳，三個概念改以同一套 FY + YTD - prior YTD 逐項精確核實。
            Flow cumulativeRevenue = cumulativeTrailing(facts, "RevenueFromContractWithCustomerExcludingAssessedTax");
            Flow cumulativeGross = cumulativeTrailing(facts, "GrossProfit");
            Flow cumulativeCost = cumulativeTrailing(facts, "CostOfGoodsAndServicesSold");
            if (latest.equals(cumulativeRevenue.periodEnd()) && aligned(cumulativeRevenue, cumulativeGross)
                    && aligned(cumulativeRevenue, cumulativeCost) && cumulativeCost.value().signum() > 0
                    && cumulativeGross.value().add(cumulativeCost.value()).compareTo(cumulativeRevenue.value()) == 0)
                return combine(cumulativeRevenue.value(), List.of(cumulativeRevenue, cumulativeGross, cumulativeCost));
            return missing("SEC_REVENUE_TOTAL_SCOPE_UNVERIFIED", latest);
        }
        Flow chosen = candidates.getFirst();
        for (Flow candidate : candidates) {
            if (chosen.value().compareTo(candidate.value()) != 0 || !Objects.equals(chosen.periodStart(), candidate.periodStart()))
                return missing("SEC_REVENUE_SCOPE_CONFLICT", latest);
        }
        // 同期合約收入已與毛利、成本對帳，若大於「總收入」標籤，後者並非公司整體收入。
        if (verifiedContract && chosen.value().compareTo(contractRevenue.value()) < 0)
            return combine(contractRevenue.value(), List.of(contractRevenue, grossProfit, costOfSales));
        return chosen;
    }

    private Flow cumulativeTrailing(List<SecFinancialFact> facts, String tag) {
        List<SecFinancialFact> cumulative = facts.stream().filter(fact -> tag.equals(fact.getTag())
                && fact.getPeriodStart() != null
                && length(new Period(fact.getPeriodStart(), fact.getPeriodEnd())) > 100).toList();
        return trailing(cumulative, false);
    }

    /** 舊版 US-GAAP 將數值相同的基本與稀釋 EPS 合併申報；只在缺少合資格稀釋 EPS 時採用，不推導累計 EPS。 */
    private Flow dilutedEps(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read) {
        Flow explicit = read.apply(facts, "EarningsPerShareDiluted");
        if (explicit.reason() != null && explicit.reason().contains("CONFLICT")) return explicit;
        Flow combined = read.apply(facts, "EarningsPerShareBasicAndDiluted");
        if (combined.value() == null || combined.periodEnd() == null) return explicit;
        if (explicit.periodEnd() != null && (explicit.periodEnd().isAfter(combined.periodEnd())
                || explicit.value() != null && explicit.periodEnd().equals(combined.periodEnd()))) return explicit;
        return combined;
    }

    private Flow trailing(List<SecFinancialFact> facts, String tag) {
        return trailing(facts.stream().filter(fact -> tag.equals(fact.getTag())).toList(), "EarningsPerShareDiluted".equals(tag)
                || "EarningsPerShareBasicAndDiluted".equals(tag));
    }

    private Flow trailing(List<SecFinancialFact> tagged, boolean eps) {
        LocalDate end = tagged.stream().map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        if (end == null) return missing("SEC_TTM_PERIODS_MISSING", null);
        Map<Period, Point> periods = revisions(tagged);
        // 年度以真實日期長度判斷；10-K 內亦可能含單季，不能以 form 或 frame 代替期間。
        List<Point> annuals = periods.entrySet().stream().filter(entry -> end.equals(entry.getKey().end()) && annual(entry.getKey()))
                .map(Map.Entry::getValue).toList();
        if (!annuals.isEmpty()) return aggregateAnnual(annuals, end);

        List<Point> quarters = new ArrayList<>();
        LocalDate through = end;
        for (int index = 0; index < 4; index++) {
            LocalDate quarterEnd = through;
            List<Map.Entry<Period, Point>> matches = periods.entrySet().stream().filter(entry -> quarterEnd.equals(entry.getKey().end())
                    && quarter(entry.getKey())).toList();
            if (matches.size() != 1) break;
            Map.Entry<Period, Point> match = matches.getFirst();
            quarters.add(match.getValue());
            through = match.getKey().start().minusDays(1);
        }
        if (quarters.size() == 4 && annual(new Period(through.plusDays(1), end))) {
            if (quarters.stream().anyMatch(Point::conflict)) return missing("SEC_FACT_VERSION_CONFLICT", end);
            BigDecimal total = BigDecimal.ZERO;
            for (Point point : quarters) total = total.add(point.fact().getValue());
            return flow(total, through.plusDays(1), end, quarters);
        }

        // EPS 只能採年度披露或連續單季加總，累計 EPS 的加權股數不同。
        if (eps) return missing("SEC_EPS_TTM_PERIODS_MISSING", end);

        // CFO 常只有 YTD。年度＋本年累計－上年可比累計；每項同 tag／unit，並逐一通過披露門。
        List<Flow> derived = new ArrayList<>();
        for (Map.Entry<Period, Point> current : periods.entrySet()) {
            Period ytd = current.getKey();
            if (!end.equals(ytd.end()) || ytd.start() == null || annual(ytd)) continue;
            for (Map.Entry<Period, Point> annual : periods.entrySet()) {
                Period year = annual.getKey();
                if (!annual(year) || !year.end().plusDays(1).equals(ytd.start())) continue;
                for (Map.Entry<Period, Point> previous : periods.entrySet()) {
                    Period prior = previous.getKey();
                    if (!year.start().equals(prior.start()) || !prior.end().isBefore(year.end())
                            || !annual(new Period(prior.end().plusDays(1), end))) continue;
                    List<Point> points = List.of(annual.getValue(), current.getValue(), previous.getValue());
                    if (points.stream().anyMatch(Point::conflict)) return missing("SEC_FACT_VERSION_CONFLICT", end);
                    BigDecimal total = annual.getValue().fact().getValue().add(current.getValue().fact().getValue()).subtract(previous.getValue().fact().getValue());
                    derived.add(flow(total, prior.end().plusDays(1), end, points));
                }
            }
        }
        if (derived.isEmpty()) return missing("SEC_TTM_PERIODS_MISSING", end);
        Flow chosen = derived.getFirst();
        for (Flow value : derived) if (!chosen.periodStart().equals(value.periodStart()) || chosen.value().compareTo(value.value()) != 0)
            return missing("SEC_TTM_PERIOD_CONFLICT", end);
        return chosen;
    }

    private Flow aggregateAnnual(List<Point> annuals, LocalDate end) {
        Point chosen = annuals.getFirst();
        for (Point point : annuals) {
            if (point.conflict()) return missing("SEC_FACT_VERSION_CONFLICT", end);
            if (!Objects.equals(chosen.fact().getPeriodStart(), point.fact().getPeriodStart())
                    || chosen.fact().getValue().compareTo(point.fact().getValue()) != 0) return missing("SEC_TTM_PERIOD_CONFLICT", end);
        }
        return flow(chosen.fact().getValue(), chosen.fact().getPeriodStart(), end, annuals);
    }

    private Map<Period, Point> revisions(List<SecFinancialFact> facts) {
        Map<Period, Point> result = new LinkedHashMap<>();
        for (SecFinancialFact fact : facts) {
            if (fact.getPeriodStart() == null || fact.getValue() == null || fact.getFiled() == null) continue;
            Period key = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            Point previous = result.get(key);
            if (previous == null || fact.getFiled().isAfter(previous.fact().getFiled())) result.put(key, new Point(fact, false));
            else if (fact.getFiled().equals(previous.fact().getFiled()) && previous.fact().getValue().compareTo(fact.getValue()) != 0)
                result.put(key, new Point(previous.fact(), true));
        }
        return result;
    }

    private Flow flow(BigDecimal value, LocalDate start, LocalDate end, List<Point> points) {
        LocalDate available = points.stream().map(point -> point.fact().getAvailableFromDate()).max(LocalDate::compareTo).orElseThrow();
        Instant observed = points.stream().map(point -> point.fact().getObservedAt()).max(Instant::compareTo).orElseThrow();
        // 年度 EPS 已含年度內的股份調整；四季加總則須檢查最早一季期末以來的拆股。
        LocalDate epsBasisFrom = Set.of("EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted").contains(points.getFirst().fact().getTag())
                ? points.stream().map(point -> point.fact().getPeriodEnd()).min(LocalDate::compareTo).orElseThrow() : null;
        return new Flow(value, start, end, available, observed, null, epsBasisFrom);
    }

    private boolean annual(Period period) {
        long days = length(period);
        return days >= 364 && days <= 371;
    }

    private boolean quarter(Period period) {
        long days = length(period);
        return days >= 80 && days <= 100;
    }

    private long length(Period period) { return period.start() == null ? 0 : ChronoUnit.DAYS.between(period.start(), period.end()) + 1; }
    private Flow missing(String reason, LocalDate end) { return new Flow(null, null, end, null, null, reason, null); }
    private LocalDate later(LocalDate a, LocalDate b) { return a == null ? b : b == null || a.isAfter(b) ? a : b; }
    private Instant later(Instant a, Instant b) { return a == null ? b : b == null || a.isAfter(b) ? a : b; }
}
