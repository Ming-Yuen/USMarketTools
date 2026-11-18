package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.valuation.FinancialReportValuationCalculator.ReportValues;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.DEPRECIATION_AMORTIZATION;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.DEPRECIATION_AMORTIZATION_ACCRETION;
import static com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter.DEPRECIATION_DEPLETION_AMORTIZATION;

/** SEC 原幣、同 tag 的可加總流量；涵蓋全市場非金融營運企業與金融機構標準標籤。 */
public class SecFinancialFactsCalculator {
    public static final List<String> DEPRECIATION_PRIORITY_TAGS = List.of(
            DEPRECIATION_DEPLETION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION, DEPRECIATION_AMORTIZATION);
    public static final Set<String> DEPRECIATION_TOTAL_TAGS = Set.copyOf(DEPRECIATION_PRIORITY_TAGS);
    private static final String DILUTED_WEIGHTED_SHARES = "WeightedAverageNumberOfDilutedSharesOutstanding";
    private static final String BASIC_AND_DILUTED_WEIGHTED_SHARES = "WeightedAverageNumberOfShareOutstandingBasicAndDiluted";
    private static final String OPERATING_CASH_FLOW = "NetCashProvidedByUsedInOperatingActivities";
    private static final String CONTINUING_OPERATING_CASH_FLOW = "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations";
    private static final Set<String> PERIODIC_REPORTS = Set.of("10-K", "10-K/A", "10-Q", "10-Q/A", "20-F", "20-F/A", "40-F", "40-F/A");
    private static final Set<String> FOREIGN_INTERIM_REPORTS = Set.of("6-K", "6-K/A");
    private static final Set<String> INCOME_PERIOD_TAGS = Set.of("Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenuesNetOfInterestExpense", "GrossProfit", "OperatingIncomeLoss", "NetIncomeLoss", "ProfitLoss",
            "NetIncomeLossAvailableToCommonStockholdersBasic", "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted");
    private static final Set<String> BALANCE_PERIOD_TAGS = Set.of("Assets", "Liabilities", "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "CashAndCashEquivalentsAtCarryingValue", "DebtCurrent", "ShortTermBorrowings", "CommercialPaper",
            "LongTermDebt", "LongTermDebtCurrent", "LongTermDebtNoncurrent", "DebtAndCapitalLeaseObligations",
            "LongTermDebtAndCapitalLeaseObligations", "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities");
    private static final Set<String> CASH_FLOW_PERIOD_TAGS = Set.of(OPERATING_CASH_FLOW, CONTINUING_OPERATING_CASH_FLOW,
            "PaymentsToAcquireProductiveAssets", "PaymentsToAcquirePropertyPlantAndEquipment");

    public static final List<String> INPUT_TAGS = List.of("Revenues", "SalesRevenueNet",
            "RevenueFromContractWithCustomerExcludingAssessedTax", "RevenuesNetOfInterestExpense",
            OPERATING_CASH_FLOW, CONTINUING_OPERATING_CASH_FLOW, "PaymentsToAcquireProductiveAssets", "PaymentsToAcquirePropertyPlantAndEquipment",
            "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted",
            DILUTED_WEIGHTED_SHARES, BASIC_AND_DILUTED_WEIGHTED_SHARES,
            "StockholdersEquity", "OperatingIncomeLoss",
            DEPRECIATION_DEPLETION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION, DEPRECIATION_AMORTIZATION,
            "Depreciation", "AmortizationOfIntangibleAssets",
            "CashAndCashEquivalentsAtCarryingValue", "MarketableSecuritiesCurrent", "MarketableSecuritiesNoncurrent",
            "CommercialPaper", "ShortTermBorrowings", "DebtCurrent",
            "LongTermDebt", "LongTermDebtCurrent", "LongTermDebtNoncurrent",
            "DebtAndCapitalLeaseObligations", "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities",
            "LongTermDebtAndCapitalLeaseObligations", "LongTermDebtAndCapitalLeaseObligationsCurrent",
            "FinanceLeaseLiability", "FinanceLeaseLiabilityCurrent", "FinanceLeaseLiabilityNoncurrent",
            "CapitalLeaseObligations", "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent",
            "OperatingLeaseLiability", "OperatingLeaseLiabilityCurrent", "OperatingLeaseLiabilityNoncurrent",
            "PreferredStockValue", "PreferredStockValueOutstanding",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount",
            "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue",
            "PreferredStockSharesOutstanding", "PreferredStockSharesIssued",
            "MinorityInterest", "RedeemableNoncontrollingInterestEquityCarryingAmount",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "AccumulatedOtherComprehensiveIncomeLossNetOfTax",
            "GrossProfit", "CostOfGoodsAndServicesSold", "Assets", "Liabilities",
            "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic", "EarningsPerShareBasic",
            "CommonStockSharesOutstanding");

    public record Flow(BigDecimal value, LocalDate periodStart, LocalDate periodEnd, LocalDate availableFrom,
                       Instant observedAt, String reason, LocalDate epsBasisFrom, LocalDate earliestFiled, String note) {
        public Flow(BigDecimal value, LocalDate periodStart, LocalDate periodEnd, LocalDate availableFrom,
                    Instant observedAt, String reason, LocalDate epsBasisFrom) {
            this(value, periodStart, periodEnd, availableFrom, observedAt, reason, epsBasisFrom, null, null);
        }
    }

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
    public record PeriodWindow(LocalDate periodStart, LocalDate periodEnd, Integer fiscalYear, Integer fiscalQuarter) {}
    public record PeriodAlignment(List<ReportValues> reports, List<SecFinancialFact> facts, List<PeriodWindow> windows) {}
    private record FiscalQuarter(int year, int quarter) {}

    /** 正規化整股所有報期：以 period_end 相差 <= 7 天且同期間類型（單季／年度）配對 Futu 與 SEC，統一至 SEC 實際期末日並推導財季起訖。 */
    public PeriodAlignment alignReportPeriods(Collection<ReportValues> reports, List<SecFinancialFact> input, Instant knownAt) {
        LocalDate knownDay = knownAt.atZone(ZoneId.of("America/New_York")).toLocalDate();
        List<SecFinancialFact> known = input.stream().filter(fact -> fact.getObservedAt() != null && !fact.getObservedAt().isAfter(knownAt)
                && fact.getFiled() != null && !fact.getFiled().isAfter(knownDay) && fact.getPeriodEnd() != null
                && !fact.getPeriodEnd().isAfter(fact.getFiled())).toList();

        Map<FiscalQuarter, Set<LocalDate>> fiscalEnds = new HashMap<>();
        Map<LocalDate, FiscalQuarter> secFiscalByEnd = new HashMap<>();
        Map<LocalDate, Set<LocalDate>> futuInferredQuarterStartsByEnd = new HashMap<>();
        Map<LocalDate, Set<LocalDate>> secAnnualStartsByEnd = new HashMap<>();
        Set<LocalDate> secAnnualEnds = new HashSet<>();
        Set<LocalDate> secBalanceEnds = new HashSet<>();

        SecQuarterEvidence secQuarters = collectSecQuarters(known);
        Set<LocalDate> secQuarterEnds = secQuarters.confirmedQuarterEnds();

        for (SecFinancialFact fact : known) {
            String form = Objects.toString(fact.getForm(), "");
            boolean standardPeriodic = PERIODIC_REPORTS.contains(form);
            boolean qualifiedInterim6K = FOREIGN_INTERIM_REPORTS.contains(form)
                    && fact.getReportFiscalYear() != null && fact.getReportFiscalPeriod() != null;
            if (!"us-gaap".equals(fact.getTaxonomy()) || (!standardPeriodic && !qualifiedInterim6K)
                    || !(INCOME_PERIOD_TAGS.contains(fact.getTag()) || BALANCE_PERIOD_TAGS.contains(fact.getTag())
                    || CASH_FLOW_PERIOD_TAGS.contains(fact.getTag()))) continue;
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            if (annual(period)) {
                secAnnualEnds.add(period.end());
                secAnnualStartsByEnd.computeIfAbsent(period.end(), ignored -> new HashSet<>()).add(period.start());
            } else if (period.start() == null && BALANCE_PERIOD_TAGS.contains(fact.getTag())) {
                secBalanceEnds.add(period.end());
            }
            if (fact.getReportFiscalYear() != null && fact.getReportFiscalPeriod() != null) {
                int q = switch (fact.getReportFiscalPeriod()) { case "Q1" -> 1; case "Q2" -> 2; case "Q3" -> 3; case "FY" -> 4; default -> 0; };
                if (q > 0) {
                    FiscalQuarter fq = new FiscalQuarter(fact.getReportFiscalYear(), q);
                    fiscalEnds.computeIfAbsent(fq, ignored -> new HashSet<>()).add(period.end());
                    secFiscalByEnd.putIfAbsent(period.end(), fq);
                }
            }
        }

        List<ReportValues> aligned = new ArrayList<>();
        Map<FiscalQuarter, Set<LocalDate>> reportEnds = new HashMap<>();
        Map<LocalDate, FiscalQuarter> futuFiscalByEnd = new HashMap<>();
        Set<LocalDate> allPeriodEnds = new HashSet<>();
        allPeriodEnds.addAll(secQuarterEnds);
        allPeriodEnds.addAll(secAnnualEnds);
        allPeriodEnds.addAll(secBalanceEnds);

        for (ReportValues report : reports) {
            if (report.periodEnd().isAfter(knownDay)) continue;
            int quarter = report.periodType() == 7 ? 4 : report.periodType();
            LocalDate resolvedEnd = resolveMatchingSecEnd(report, quarter, fiscalEnds, secQuarterEnds, secAnnualEnds, secBalanceEnds);
            ReportValues normalized = new ReportValues(report.figi(), report.statementType(), resolvedEnd, report.periodType(), report.fiscalYear(),
                    report.currency(), report.accountingStandards(), report.values(), report.unknownNames(), report.pendingItems());
            aligned.add(normalized);
            allPeriodEnds.add(resolvedEnd);
            if (report.fiscalYear() != null && quarter >= 1 && quarter <= 4) {
                FiscalQuarter fq = new FiscalQuarter(report.fiscalYear(), quarter);
                reportEnds.computeIfAbsent(fq, ignored -> new HashSet<>()).add(resolvedEnd);
                futuFiscalByEnd.putIfAbsent(resolvedEnd, fq);
            }
        }
        for (Map.Entry<FiscalQuarter, Set<LocalDate>> entry : reportEnds.entrySet()) {
            FiscalQuarter key = entry.getKey();
            FiscalQuarter previous = key.quarter() == 1 ? new FiscalQuarter(key.year() - 1, 4) : new FiscalQuarter(key.year(), key.quarter() - 1);
            Set<LocalDate> starts = reportEnds.getOrDefault(previous, Set.of());
            if (entry.getValue().size() != 1 || starts.size() != 1) continue;
            Period period = new Period(starts.iterator().next().plusDays(1), entry.getValue().iterator().next());
            if (quarter(period)) {
                futuInferredQuarterStartsByEnd.computeIfAbsent(period.end(), ignored -> new HashSet<>()).add(period.start());
            }
        }

        List<LocalDate> sortedEnds = allPeriodEnds.stream().filter(end -> !end.isAfter(knownDay)).sorted().toList();
        List<PeriodWindow> windows = new ArrayList<>();
        LocalDate prevEnd = null;
        for (LocalDate end : sortedEnds) {
            FiscalQuarter label = futuFiscalByEnd.getOrDefault(end, secFiscalByEnd.get(end));
            LocalDate start = resolveQuarterWindowStart(
                    end, secQuarters.directStartsByEnd(), secQuarters.derivedFourthStartsByEnd(), futuInferredQuarterStartsByEnd);
            if (start == null && prevEnd != null) {
                long gap = ChronoUnit.DAYS.between(prevEnd, end);
                if (gap >= 75 && gap <= 200) {
                    start = prevEnd.plusDays(1);
                }
            }
            if (start == null) {
                LocalDate priorSecQuarterEnd = uniqueAdjacentPriorQuarterEnd(secQuarterEnds, end);
                if (priorSecQuarterEnd != null) {
                    start = priorSecQuarterEnd.plusDays(1);
                }
            }
            if (start == null) {
                LocalDate annualStart = singleDate(secAnnualStartsByEnd.get(end));
                if (annualStart != null && (prevEnd == null || !prevEnd.isAfter(annualStart))) {
                    start = annualStart;
                } else if (label != null) {
                    YearMonth ym = YearMonth.from(end);
                    if (end.equals(ym.atEndOfMonth())
                            && (allPeriodEnds.contains(ym.plusMonths(3).atEndOfMonth())
                                    || allPeriodEnds.contains(ym.minusMonths(3).atEndOfMonth()))) {
                        start = ym.minusMonths(2).atDay(1);
                    } else {
                        start = end.minusDays(90);
                    }
                }
            }
            windows.add(new PeriodWindow(start, end, label == null ? null : label.year(), label == null ? null : label.quarter()));
            prevEnd = end;
        }
        return new PeriodAlignment(List.copyOf(aligned), known, List.copyOf(windows));
    }

    private record SecQuarterEvidence(
            Map<LocalDate, Set<LocalDate>> directStartsByEnd,
            Map<LocalDate, Set<LocalDate>> derivedFourthStartsByEnd,
            Set<LocalDate> confirmedQuarterEnds) {}

    private SecQuarterEvidence collectSecQuarters(List<SecFinancialFact> facts) {
        Map<LocalDate, Set<LocalDate>> directStarts = new HashMap<>();
        Map<LocalDate, Set<LocalDate>> boundaryCandidateStarts = new HashMap<>();
        Set<LocalDate> annualStarts = new HashSet<>();
        Map<LocalDate, Set<LocalDate>> annualStartsByEnd = new HashMap<>();
        Map<LocalDate, Set<LocalDate>> nineMonthEnds = new HashMap<>();

        for (SecFinancialFact fact : facts) {
            if (!isPeriodicFlowFact(fact)) continue;
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            long days = length(period);
            if (days >= 240 && days <= 300) {
                nineMonthEnds.computeIfAbsent(period.start(), ignored -> new HashSet<>()).add(period.end());
                continue;
            }
            if (!isStatementPeriodFlowTag(fact.getTag())) continue;
            if (annual(period)) {
                annualStarts.add(period.start());
                annualStartsByEnd.computeIfAbsent(period.end(), ignored -> new HashSet<>()).add(period.start());
            } else if (quarter(period)) {
                directStarts.computeIfAbsent(period.end(), ignored -> new HashSet<>()).add(period.start());
            } else if (days >= 70 && days <= 125) {
                boundaryCandidateStarts.computeIfAbsent(period.end(), ignored -> new HashSet<>()).add(period.start());
            }
        }

        Map<LocalDate, Set<LocalDate>> derivedFourthStarts = new HashMap<>();
        for (Map.Entry<LocalDate, Set<LocalDate>> entry : annualStartsByEnd.entrySet()) {
            LocalDate annualEnd = entry.getKey();
            for (LocalDate annualStart : entry.getValue()) {
                for (LocalDate prior : nineMonthEnds.getOrDefault(annualStart, Set.of())) {
                    Period fourth = new Period(prior.plusDays(1), annualEnd);
                    long fourthDays = length(fourth);
                    if (fourthDays >= 70 && fourthDays <= 125) {
                        derivedFourthStarts.computeIfAbsent(annualEnd, ignored -> new HashSet<>()).add(fourth.start());
                    }
                }
            }
        }

        for (Map.Entry<LocalDate, Set<LocalDate>> entry : boundaryCandidateStarts.entrySet()) {
            LocalDate end = entry.getKey();
            Set<LocalDate> fourthAtEnd = derivedFourthStarts.getOrDefault(end, Set.of());
            for (LocalDate start : entry.getValue()) {
                if (annualStarts.contains(start) || fourthAtEnd.contains(start)) {
                    directStarts.computeIfAbsent(end, ignored -> new HashSet<>()).add(start);
                }
            }
        }

        Set<LocalDate> confirmedEnds = new HashSet<>(directStarts.keySet());
        confirmedEnds.addAll(derivedFourthStarts.keySet());
        return new SecQuarterEvidence(directStarts, derivedFourthStarts, confirmedEnds);
    }

    private LocalDate resolveQuarterWindowStart(
            LocalDate end,
            Map<LocalDate, Set<LocalDate>> directSec,
            Map<LocalDate, Set<LocalDate>> derivedSecFourth,
            Map<LocalDate, Set<LocalDate>> futuInferred) {
        Set<LocalDate> direct = directSec.get(end);
        if (direct != null && !direct.isEmpty()) return singleDate(direct);
        Set<LocalDate> derived = derivedSecFourth.get(end);
        if (derived != null && !derived.isEmpty()) return singleDate(derived);
        return singleDate(futuInferred.get(end));
    }

    /** 回傳同發行人在指定期末已核實且唯一的 SEC 單季起日（含全年邊界佐證之 70～125 天單季）；衝突或不存在時回傳 null。 */
    public LocalDate exactQuarterStart(List<SecFinancialFact> facts, LocalDate periodEnd) {
        if (facts == null || facts.isEmpty() || periodEnd == null) return null;
        SecQuarterEvidence evidence = collectSecQuarters(facts);
        return resolveQuarterWindowStart(periodEnd, evidence.directStartsByEnd(), evidence.derivedFourthStartsByEnd(), Map.of());
    }

    /** 當指定期末缺直接單季 SEC 標籤時，若同發行人存在唯一一個與該期末構成合規單季長度的前一已確認 SEC 單季期末，回傳該前一期末。 */
    public LocalDate uniquePriorSecQuarterEnd(List<SecFinancialFact> facts, LocalDate periodEnd) {
        if (facts == null || facts.isEmpty() || periodEnd == null) return null;
        SecQuarterEvidence evidence = collectSecQuarters(facts);
        return uniqueAdjacentPriorQuarterEnd(evidence.confirmedQuarterEnds(), periodEnd);
    }

    private boolean isPeriodicFlowFact(SecFinancialFact fact) {
        String form = Objects.toString(fact.getForm(), "");
        boolean qualifiedInterim6K = FOREIGN_INTERIM_REPORTS.contains(form)
                && fact.getReportFiscalYear() != null && fact.getReportFiscalPeriod() != null;
        return fact.getPeriodStart() != null && fact.getPeriodEnd() != null
                && "us-gaap".equals(fact.getTaxonomy())
                && (PERIODIC_REPORTS.contains(form) || qualifiedInterim6K);
    }

    private boolean isStatementPeriodFlowTag(String tag) {
        return INCOME_PERIOD_TAGS.contains(tag) || CASH_FLOW_PERIOD_TAGS.contains(tag);
    }

    private LocalDate uniqueAdjacentPriorQuarterEnd(Set<LocalDate> candidateEnds, LocalDate end) {
        List<LocalDate> matches = candidateEnds.stream()
                .filter(prior -> {
                    if (!prior.isBefore(end)) return false;
                    long days = length(new Period(prior.plusDays(1), end));
                    return days >= 70 && days <= 125;
                })
                .sorted().toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private LocalDate singleDate(Set<LocalDate> dates) {
        return dates != null && dates.size() == 1 ? dates.iterator().next() : null;
    }

    private LocalDate resolveMatchingSecEnd(ReportValues report, int quarter, Map<FiscalQuarter, Set<LocalDate>> fiscalEnds,
                                            Set<LocalDate> secQuarterEnds, Set<LocalDate> secAnnualEnds, Set<LocalDate> secBalanceEnds) {
        LocalDate futuEnd = report.periodEnd();
        if (report.fiscalYear() != null && quarter >= 1 && quarter <= 4) {
            Set<LocalDate> exactFiscal = fiscalEnds.getOrDefault(new FiscalQuarter(report.fiscalYear(), quarter), Set.of());
            if (exactFiscal.size() == 1) {
                LocalDate candidate = exactFiscal.iterator().next();
                if (Math.abs(ChronoUnit.DAYS.between(futuEnd, candidate)) <= 7) return candidate;
            }
        }
        Set<LocalDate> candidates = new HashSet<>();
        if (report.periodType() == 7) {
            candidates.addAll(secAnnualEnds);
            if (report.statementType() == 2) candidates.addAll(secBalanceEnds);
        } else {
            candidates.addAll(secQuarterEnds);
            if (report.periodType() == 4) candidates.addAll(secAnnualEnds);
            if (report.statementType() == 2) candidates.addAll(secBalanceEnds);
        }
        List<LocalDate> nearby = candidates.stream()
                .filter(secEnd -> Math.abs(ChronoUnit.DAYS.between(futuEnd, secEnd)) <= 7)
                .sorted().toList();
        return nearby.size() == 1 ? nearby.getFirst() : futuEnd;
    }

    /** 先辨認交易日所屬的實際財季，再交回原有公式；財年數字取自當期申報，不取比較期的 fy。 */
    public PeriodSelection selectPeriods(Collection<ReportValues> reports, List<SecFinancialFact> input, LocalDate day, Instant knownAt) {
        PeriodAlignment alignment = alignReportPeriods(reports, input, knownAt);
        Set<LocalDate> targetEnds = alignment.windows().stream()
                .filter(window -> window.periodStart() != null && !day.isBefore(window.periodStart()) && !day.isAfter(window.periodEnd()))
                .map(PeriodWindow::periodEnd).collect(Collectors.toSet());
        return new PeriodSelection(
                alignment.reports().stream().filter(report -> !report.periodEnd().isAfter(day) || targetEnds.contains(report.periodEnd())).toList(),
                alignment.facts().stream().filter(fact -> !fact.getPeriodEnd().isAfter(day) || targetEnds.contains(fact.getPeriodEnd())).toList());
    }

    public Flows calculate(List<SecFinancialFact> input, String cik, String sic, String currency, LocalDate day, Instant calculatedAt,
                           Collection<ReportValues> reports) {
        PeriodSelection selection = selectPeriods(reports, input, day, calculatedAt);
        return calculateSelectedFacts(selection.facts(), cik, sic, currency, day, calculatedAt, selection.reports());
    }

    /** 財季已配對的資料沿用全年／TTM 公式，不再以原股價日封鎖後續取得的財報。 */
    public Flows calculateSelectedFacts(List<SecFinancialFact> input, String cik, String sic, String currency, LocalDate day, Instant calculatedAt,
                                        Collection<ReportValues> reports) {
        List<SecFinancialFact> eligible = eligible(input, cik, currency, calculatedAt);
        Flows latest = calculateEligible(eligible, cik, sic);
        Map<LocalDate, List<SecFinancialFact>> byEnd = new HashMap<>();
        for (SecFinancialFact fact : eligible) byEnd.computeIfAbsent(fact.getPeriodEnd(), ignored -> new ArrayList<>()).add(fact);
        List<ReportValues> sameCurrency = reports.stream().filter(report -> Objects.equals(currency, report.currency())).toList();
        List<ReportValues> missingQuarters = missingQuarters(sameCurrency, byEnd);
        List<ReportValues> periodsToMatch = new ArrayList<>(sameCurrency);
        periodsToMatch.addAll(missingQuarters);
        Map<ReportPeriod, Map<FinancialConcept, Flow>> periods = new HashMap<>();
        for (ReportValues report : periodsToMatch) {
            ReportPeriod period = new ReportPeriod(report.periodEnd(), report.periodType(), report.statementType());
            periods.computeIfAbsent(period, key -> reportedInputs(byEnd.getOrDefault(key.end(), List.of()), key, sic));
        }
        return new Flows(cik, latest.revenue(), latest.freeCashFlow(), latest.dilutedEpsTtm(), latest.equity(), latest.ebitda(),
                latest.enterpriseValueAdjustment(), latest.inputs(), Map.copyOf(periods), missingQuarters);
    }

    /** 兩側富途報期確認季序，SEC 真實日期確認缺季，同時支援損益表（statement == 1）與現金流量表（statement == 3）。 */
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
                String standard = after.accountingStandards() != null && !after.accountingStandards().isBlank()
                        ? after.accountingStandards() : "US_GAAP";
                missing.add(new ReportValues(after.figi(), statement, end, quarter % 4 + 1, quarter / 4,
                        after.currency(), standard, Map.of(), List.of(), List.of()));
            }
            // 末尾尚未取得的富途季度，在 SEC 實際單季緊接已知期末時為損益表與現金流量表建立補缺報期。
            if (quarters.isEmpty()) continue;
            ReportValues last = quarters.getLast();
            LocalDate lastEnd = last.periodEnd();
            int sequence = last.fiscalYear() * 4 + last.periodType();
            String standard = last.accountingStandards() != null && !last.accountingStandards().isBlank()
                    ? last.accountingStandards() : "US_GAAP";
            for (int offset = 0; offset < 3; offset++) {
                LocalDate priorEnd = lastEnd;
                List<LocalDate> possible = byEnd.keySet().stream().filter(end -> end.isAfter(priorEnd)
                        && quarterStarts(byEnd, end).equals(Set.of(priorEnd.plusDays(1)))).sorted().toList();
                if (possible.size() != 1) break;
                LocalDate end = possible.getFirst();
                sequence++;
                missing.add(new ReportValues(last.figi(), statement, end, (sequence - 1) % 4 + 1, (sequence - 1) / 4,
                        last.currency(), standard, Map.of(), List.of(), List.of()));
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

    /** 驗證跨越拆股日的單期稀釋 EPS：接受拆股後提交的直接 SEC EPS，或經加權平均稀釋股數與對應盈利交叉驗證一致。 */
    public boolean isEpsSplitBasisVerified(BigDecimal reportedEps, BigDecimal futuNetIncome, LocalDate periodEnd, boolean annualPeriod,
                                           List<SecFinancialFact> allCikFacts, String currency, List<StockSplit> splits,
                                           LocalDate valuationDay, Instant calculatedAt) {
        if (reportedEps == null || periodEnd == null || currency == null) return false;
        LocalDate knownNyDay = calculatedAt.atZone(ZoneId.of("America/New_York")).toLocalDate();
        List<SecFinancialFact> matchingPeriod = allCikFacts.stream().filter(fact -> "us-gaap".equals(fact.getTaxonomy())
                && fact.getPeriodStart() != null && fact.getPeriodEnd() != null
                && Math.abs(ChronoUnit.DAYS.between(periodEnd, fact.getPeriodEnd())) <= 7
                && fact.getFiled() != null && !fact.getFiled().isAfter(knownNyDay)
                && !fact.getPeriodEnd().isAfter(fact.getFiled())
                && fact.getObservedAt() != null && !fact.getObservedAt().isAfter(calculatedAt)
                && (annualPeriod ? annual(new Period(fact.getPeriodStart(), fact.getPeriodEnd()))
                        : quarter(new Period(fact.getPeriodStart(), fact.getPeriodEnd())))).toList();

        // ① 拆股後提交之直接申報 SEC 稀釋 EPS，或拆股前原申報（期末至申報日間無拆股）按已知拆股因子對齊後一致
        LocalDate latestSplitUpToDay = splits.stream().map(StockSplit::getExecutionDate)
                .filter(exec -> exec.isAfter(periodEnd) && !exec.isAfter(valuationDay))
                .max(LocalDate::compareTo).orElse(null);
        List<SecFinancialFact> directEps = matchingPeriod.stream()
                .filter(fact -> ("EarningsPerShareDiluted".equals(fact.getTag()) || "EarningsPerShareBasicAndDiluted".equals(fact.getTag()))
                        && (currency + "/shares").equals(fact.getUnit()) && fact.getValue() != null
                        && (latestSplitUpToDay == null || !fact.getFiled().isBefore(latestSplitUpToDay))
                        && StockSplitCalculator.shareFactor(splits, fact.getFiled(), valuationDay).compareTo(BigDecimal.ONE) == 0)
                .sorted(Comparator.comparing(SecFinancialFact::getFiled).reversed()).toList();
        if (!directEps.isEmpty()) {
            BigDecimal secPostSplitEps = directEps.getFirst().getValue();
            if (reportedEps.subtract(secPostSplitEps).abs().compareTo(new BigDecimal("0.02")) <= 0) return true;
        }
        List<SecFinancialFact> unambiguousEps = matchingPeriod.stream()
                .filter(fact -> ("EarningsPerShareDiluted".equals(fact.getTag()) || "EarningsPerShareBasicAndDiluted".equals(fact.getTag()))
                        && (currency + "/shares").equals(fact.getUnit()) && fact.getValue() != null
                        && splits.stream().noneMatch(s -> s.getExecutionDate().isAfter(fact.getPeriodEnd())
                                && !s.getExecutionDate().isAfter(fact.getFiled())))
                .sorted(Comparator.comparing(SecFinancialFact::getFiled).reversed()).toList();
        if (!unambiguousEps.isEmpty()) {
            SecFinancialFact baseFact = unambiguousEps.getFirst();
            BigDecimal factor = StockSplitCalculator.shareFactor(splits, baseFact.getFiled(), valuationDay);
            if (factor.signum() > 0) {
                BigDecimal adjustedSecEps = baseFact.getValue().divide(factor, MathContext.DECIMAL128);
                BigDecimal tol = adjustedSecEps.abs().multiply(new BigDecimal("0.03")).max(new BigDecimal("0.02"));
                if (reportedEps.subtract(adjustedSecEps).abs().compareTo(tol) <= 0) return true;
            }
        }

        // ② 同報期盈利分子除以拆股對齊後之「加權平均稀釋股數」交叉驗證（絕不用期末股本猜測）
        List<SecFinancialFact> shareFacts = matchingPeriod.stream()
                .filter(fact -> DILUTED_WEIGHTED_SHARES.equals(fact.getTag()) && "shares".equals(fact.getUnit())
                        && fact.getValue() != null && fact.getValue().signum() > 0)
                .sorted(Comparator.comparing(SecFinancialFact::getFiled).reversed()).toList();
        if (shareFacts.isEmpty()) {
            shareFacts = matchingPeriod.stream()
                    .filter(fact -> BASIC_AND_DILUTED_WEIGHTED_SHARES.equals(fact.getTag()) && "shares".equals(fact.getUnit())
                            && fact.getValue() != null && fact.getValue().signum() > 0)
                    .sorted(Comparator.comparing(SecFinancialFact::getFiled).reversed()).toList();
        }
        if (shareFacts.isEmpty()) return false;
        SecFinancialFact chosenShares = shareFacts.getFirst();
        BigDecimal dilutedSharesAtDay = chosenShares.getValue()
                .multiply(StockSplitCalculator.shareFactor(splits, chosenShares.getFiled(), valuationDay), MathContext.DECIMAL128);
        if (dilutedSharesAtDay.signum() <= 0) return false;

        BigDecimal numerator = futuNetIncome;
        if (numerator == null) {
            numerator = matchingPeriod.stream()
                    .filter(fact -> "NetIncomeLossAvailableToCommonStockholdersBasic".equals(fact.getTag())
                            && currency.equals(fact.getUnit()) && fact.getValue() != null)
                    .max(Comparator.comparing(SecFinancialFact::getFiled))
                    .map(SecFinancialFact::getValue)
                    .orElseGet(() -> matchingPeriod.stream()
                            .filter(fact -> "NetIncomeLoss".equals(fact.getTag())
                                    && currency.equals(fact.getUnit()) && fact.getValue() != null)
                            .max(Comparator.comparing(SecFinancialFact::getFiled))
                            .map(SecFinancialFact::getValue).orElse(null));
        }
        if (numerator == null) return false;
        BigDecimal impliedEps = numerator.divide(dilutedSharesAtDay, MathContext.DECIMAL128);
        BigDecimal tolerance = impliedEps.abs().multiply(new BigDecimal("0.03")).max(new BigDecimal("0.02"));
        return reportedEps.subtract(impliedEps).abs().compareTo(tolerance) <= 0;
    }

    private List<SecFinancialFact> eligible(List<SecFinancialFact> input, String cik, String currency, Instant calculatedAt) {
        LocalDate knownNyDay = calculatedAt.atZone(ZoneId.of("America/New_York")).toLocalDate();
        return input.stream().filter(fact -> Objects.equals(cik, fact.getCik()) && "us-gaap".equals(fact.getTaxonomy())
                && currency != null && expectedUnit(fact.getTag(), currency).equals(fact.getUnit())
                && fact.getAvailableFromDate() != null
                && fact.getFiled() != null && !fact.getFiled().isAfter(knownNyDay)
                && fact.getPeriodEnd() != null && !fact.getPeriodEnd().isAfter(fact.getFiled())
                && fact.getObservedAt() != null && !fact.getObservedAt().isAfter(calculatedAt)).toList();
    }

    private String expectedUnit(String tag, String currency) {
        return switch (tag) {
            case "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted" -> currency + "/shares";
            case "PreferredStockSharesOutstanding", "PreferredStockSharesIssued", "CommonStockSharesOutstanding",
                 DILUTED_WEIGHTED_SHARES, BASIC_AND_DILUTED_WEIGHTED_SHARES -> "shares";
            default -> currency;
        };
    }

    private Flows calculateEligible(List<SecFinancialFact> eligible, String cik, String sic) {
        LocalDate incomeEnd = latestReportEnd(eligible, INCOME_PERIOD_TAGS, false);
        LocalDate balanceEnd = latestReportEnd(eligible, BALANCE_PERIOD_TAGS, true);
        LocalDate cashFlowEnd = latestReportEnd(eligible, CASH_FLOW_PERIOD_TAGS, false);
        Flow revenue = revenue(eligible, this::trailing, sic);
        Flow cfo = operatingCashFlow(eligible, this::trailing);
        Flow capex = capitalExpenditure(eligible);
        Flow fcf;
        if (cfo.value() == null || capex.value() == null) {
            String conflict = cfo.reason() != null && cfo.reason().contains("CONFLICT") ? cfo.reason()
                    : capex.reason() != null && capex.reason().contains("CONFLICT") ? capex.reason() : "SEC_FCF_COMPONENTS_MISSING";
            fcf = missing(conflict, later(cfo.periodEnd(), capex.periodEnd()));
        } else if (!Objects.equals(cfo.periodEnd(), capex.periodEnd())) {
            fcf = missing("SEC_FCF_PERIOD_MISMATCH", later(cfo.periodEnd(), capex.periodEnd()));
        } else if (!Objects.equals(cfo.periodStart(), capex.periodStart())) {
            fcf = missing("SEC_FCF_SCOPE_CONFLICT", cfo.periodEnd());
        } else if (capex.value().signum() < 0) {
            fcf = missing("SEC_CAPITAL_EXPENDITURE_SIGN_UNVERIFIED", capex.periodEnd());
        } else {
            String fcfNote = "OperatingCashFlow - " + (capex.note() == null ? "CapitalExpenditure" : capex.note());
            fcf = new Flow(cfo.value().subtract(capex.value()), cfo.periodStart(), cfo.periodEnd(),
                    later(cfo.availableFrom(), capex.availableFrom()), later(cfo.observedAt(), capex.observedAt()),
                    null, null, null, fcfNote);
        }
        Flow eps = dilutedEps(eligible, this::trailing);
        Flow netIncome = netIncomeCommon(eligible, this::trailing);
        Flow equity = commonEquity(eligible);
        Flow depreciation = depreciationAndAmortization(eligible, this::trailing);
        Map<FinancialConcept, Flow> inputs = new EnumMap<>(FinancialConcept.class);
        inputs.put(FinancialConcept.NET_INCOME_COMMON, requireReportEnd(netIncome, incomeEnd, "SEC_FINANCIAL_INPUT_MISSING"));
        inputs.put(FinancialConcept.OPERATING_CASH_FLOW, requireReportEnd(cfo, cashFlowEnd, "SEC_FINANCIAL_INPUT_MISSING"));
        inputs.put(FinancialConcept.CAPITAL_EXPENDITURE, requireReportEnd(capex, cashFlowEnd, "SEC_FINANCIAL_INPUT_MISSING"));
        inputs.put(FinancialConcept.CURRENT_FINANCE_LEASES, leasePart(eligible, "FinanceLeaseLiability", "Current", "Noncurrent"));
        inputs.put(FinancialConcept.LONG_TERM_FINANCE_LEASES, leasePart(eligible, "FinanceLeaseLiability", "Noncurrent", "Current"));
        inputs.put(FinancialConcept.TOTAL_FINANCE_LEASES, balance(eligible, "FinanceLeaseLiability"));
        inputs.put(FinancialConcept.CURRENT_OPERATING_LEASES, leasePart(eligible, "OperatingLeaseLiability", "Current", "Noncurrent"));
        inputs.put(FinancialConcept.LONG_TERM_OPERATING_LEASES, leasePart(eligible, "OperatingLeaseLiability", "Noncurrent", "Current"));
        inputs.put(FinancialConcept.CASH_AND_EQUIVALENTS, balance(eligible, "CashAndCashEquivalentsAtCarryingValue"));

        LocalDate preferredEnd = equity.periodEnd() != null ? equity.periodEnd() : balanceEnd;
        List<SecFinancialFact> sameBalancePeriod = preferredEnd == null ? List.of()
                : eligible.stream().filter(fact -> preferredEnd.equals(fact.getPeriodEnd())).toList();
        Flow preferred = preferredEquityBookValue(sameBalancePeriod, preferredEnd);
        inputs.put(FinancialConcept.PREFERRED_STOCK_CAPITAL, preferred);
        inputs.put(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, resolveSecNoncontrollingInterest(sameBalancePeriod, preferredEnd));
        inputs.put(FinancialConcept.STOCKHOLDERS_EQUITY, parentStockholdersEquity(eligible));
        inputs.put(FinancialConcept.TOTAL_EQUITY, balance(eligible, "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"));
        inputs.put(FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED,
                balance(eligible, "AccumulatedOtherComprehensiveIncomeLossNetOfTax"));
        if (preferredEnd != null) {
            inputs.put(FinancialConcept.CURRENT_DEBT_AND_LEASES, resolveSecDebt(sameBalancePeriod, preferredEnd));
            inputs.put(FinancialConcept.CURRENT_DEBT, resolveSecCurrentBorrowing(sameBalancePeriod));
            inputs.put(FinancialConcept.LONG_TERM_DEBT, balance(sameBalancePeriod, "LongTermDebtNoncurrent"));
        }

        Flow profit = trailing(eligible, "OperatingIncomeLoss");
        inputs.put(FinancialConcept.OPERATING_PROFIT, profit);
        inputs.put(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, depreciation);
        LocalDate ebitdaEnd = later(profit.periodEnd(), depreciation.periodEnd());
        Flow ebitda;
        if (aligned(profit, depreciation)) {
            String note = "OperatingIncomeLoss + " + (depreciation.note() == null ? "DD&A" : depreciation.note());
            ebitda = combineWithNote(profit.value().add(depreciation.value()), List.of(profit, depreciation), note);
        } else {
            ebitda = missing("SEC_EBITDA_COMPONENTS_UNRESOLVED", ebitdaEnd);
        }
        return new Flows(cik, requireReportEnd(revenue, incomeEnd, "SEC_REVENUE_MISSING"),
                requireReportEnd(fcf, cashFlowEnd, "SEC_FCF_COMPONENTS_MISSING"),
                requireReportEnd(eps, incomeEnd, "SEC_EPS_TTM_PERIODS_MISSING"),
                requireReportEnd(equity, balanceEnd, "SEC_COMMON_EQUITY_UNRESOLVED"),
                requireReportEnd(ebitda, incomeEnd, "SEC_EBITDA_COMPONENTS_UNRESOLVED"),
                requireReportEnd(enterpriseValueAdjustment(eligible), balanceEnd, "SEC_EV_COMPONENTS_UNRESOLVED"),
                Map.copyOf(inputs), Map.of(), List.of());
    }

    private LocalDate latestReportEnd(List<SecFinancialFact> facts, Set<String> tags, boolean balanceSheet) {
        LocalDate latest = null;
        for (SecFinancialFact fact : facts) {
            if (!tags.contains(fact.getTag()) || fact.getForm() == null || !PERIODIC_REPORTS.contains(fact.getForm())
                    || fact.getPeriodEnd().isAfter(fact.getFiled())) continue;
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            long days = length(period);
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
    private Map<FinancialConcept, Flow> reportedInputs(List<SecFinancialFact> facts, ReportPeriod report, String sic) {
        List<SecFinancialFact> matching = facts.stream().filter(fact -> matchesReportPeriod(fact, report)).toList();
        if (report.statementType() == 2) return calculateEligible(matching, null, sic).inputs();
        Map<FinancialConcept, Flow> inputs = new EnumMap<>(FinancialConcept.class);
        if (report.statementType() == 1) {
            inputs.put(FinancialConcept.REVENUE_AS_REPORTED, revenue(matching, this::reported, sic));
            inputs.put(FinancialConcept.DILUTED_EPS, dilutedEps(matching, this::reported));
            inputs.put(FinancialConcept.NET_INCOME_COMMON, netIncomeCommon(matching, this::reported));
            inputs.put(FinancialConcept.OPERATING_PROFIT, reported(matching, "OperatingIncomeLoss"));
            inputs.put(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, depreciationAndAmortization(matching, this::reported));
        } else if (report.statementType() == 3) {
            inputs.put(FinancialConcept.OPERATING_CASH_FLOW, operatingCashFlow(matching, this::reported));
            Flow capex = reported(matching, "PaymentsToAcquireProductiveAssets");
            if (capex.periodEnd() == null) capex = reported(matching, "PaymentsToAcquirePropertyPlantAndEquipment");
            inputs.put(FinancialConcept.CAPITAL_EXPENDITURE, capex);
        }
        return Map.copyOf(inputs);
    }

    private Flow netIncomeCommon(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read) {
        Flow common = read.apply(facts, "NetIncomeLossAvailableToCommonStockholdersBasic");
        if (common.value() != null || (common.reason() != null && common.reason().contains("CONFLICT"))) return common;
        return read.apply(facts, "NetIncomeLoss");
    }

    /** 依序直接接受標準現金流／損益折舊攤銷總額標籤（含礦業 Depletion 與公用事業 Accretion），缺總額才用 Depreciation + AmortizationOfIntangibleAssets。 */
    private Flow depreciationAndAmortization(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read) {
        Map<Period, Map<String, List<SecFinancialFact>>> byPeriodAndTag = new LinkedHashMap<>();
        for (SecFinancialFact fact : facts) {
            if (fact.getPeriodStart() == null || fact.getPeriodEnd() == null || fact.getValue() == null) continue;
            String tag = fact.getTag();
            if (!DEPRECIATION_TOTAL_TAGS.contains(tag) && !"Depreciation".equals(tag) && !"AmortizationOfIntangibleAssets".equals(tag)) {
                continue;
            }
            Period period = new Period(fact.getPeriodStart(), fact.getPeriodEnd());
            byPeriodAndTag.computeIfAbsent(period, ignored -> new HashMap<>())
                    .computeIfAbsent(tag, ignored -> new ArrayList<>()).add(fact);
        }
        List<SecFinancialFact> normalized = new ArrayList<>();
        Set<String> usedTags = new LinkedHashSet<>();
        for (Map.Entry<Period, Map<String, List<SecFinancialFact>>> entry : byPeriodAndTag.entrySet()) {
            Map<String, List<SecFinancialFact>> tagFacts = entry.getValue();
            String chosenTotalTag = null;
            for (String candidate : DEPRECIATION_PRIORITY_TAGS) {
                if (tagFacts.containsKey(candidate)) {
                    chosenTotalTag = candidate;
                    break;
                }
            }
            if (chosenTotalTag != null) {
                usedTags.add(chosenTotalTag);
                for (SecFinancialFact fact : tagFacts.get(chosenTotalTag)) {
                    if (fact.getValue().signum() >= 0) normalized.add(copyWithTag(fact, DEPRECIATION_AMORTIZATION));
                }
                continue;
            }
            // 缺總額標籤時，採同申報同期間的 Depreciation + AmortizationOfIntangibleAssets。
            List<SecFinancialFact> depreciations = tagFacts.getOrDefault("Depreciation", List.of());
            List<SecFinancialFact> amortizations = tagFacts.getOrDefault("AmortizationOfIntangibleAssets", List.of());
            Map<String, SecFinancialFact> amortByAccn = new HashMap<>();
            for (SecFinancialFact amort : amortizations) {
                if (amort.getValue().signum() >= 0) amortByAccn.putIfAbsent(amort.getAccession(), amort);
            }
            for (SecFinancialFact dep : depreciations) {
                if (dep.getValue().signum() < 0) continue;
                SecFinancialFact amort = amortByAccn.get(dep.getAccession());
                if (amort != null) {
                    usedTags.add("Depreciation + AmortizationOfIntangibleAssets");
                    normalized.add(depreciationTotal(dep, amort));
                } else if (amortizations.isEmpty()) {
                    usedTags.add("Depreciation");
                    normalized.add(copyWithTag(dep, DEPRECIATION_AMORTIZATION));
                }
            }
        }
        Flow result = read.apply(normalized, DEPRECIATION_AMORTIZATION);
        if (result.value() == null) return result;
        String note = usedTags.isEmpty() ? DEPRECIATION_AMORTIZATION : String.join(", ", usedTags);
        return new Flow(result.value(), result.periodStart(), result.periodEnd(), result.availableFrom(),
                result.observedAt(), null, null, result.earliestFiled(), note);
    }

    private SecFinancialFact copyWithTag(SecFinancialFact source, String tag) {
        SecFinancialFact copy = new SecFinancialFact();
        copy.setCik(source.getCik());
        copy.setTaxonomy(source.getTaxonomy());
        copy.setTag(tag);
        copy.setUnit(source.getUnit());
        copy.setAccession(source.getAccession());
        copy.setPeriodStart(source.getPeriodStart());
        copy.setPeriodEnd(source.getPeriodEnd());
        copy.setFiled(source.getFiled());
        copy.setForm(source.getForm());
        copy.setValue(source.getValue());
        copy.setAvailableFromDate(source.getAvailableFromDate());
        copy.setObservedAt(source.getObservedAt());
        return copy;
    }

    private SecFinancialFact depreciationTotal(SecFinancialFact depreciation, SecFinancialFact amortization) {
        SecFinancialFact total = copyWithTag(depreciation, DEPRECIATION_AMORTIZATION);
        total.setFiled(later(depreciation.getFiled(), amortization.getFiled()));
        total.setValue(depreciation.getValue().add(amortization.getValue()));
        total.setAvailableFromDate(later(depreciation.getAvailableFromDate(), amortization.getAvailableFromDate()));
        total.setObservedAt(later(depreciation.getObservedAt(), amortization.getObservedAt()));
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
        return flow(selected.fact().getValue(), selected.fact().getPeriodStart(), selected.fact().getPeriodEnd(), List.of(selected), tag);
    }

    /** 完整資本支出：優先採 PaymentsToAcquireProductiveAssets，缺總額時採 PaymentsToAcquirePropertyPlantAndEquipment，不拼湊單一軟件明細。 */
    private Flow capitalExpenditure(List<SecFinancialFact> facts) {
        Flow total = trailing(facts, "PaymentsToAcquireProductiveAssets");
        Flow ppe = trailing(facts, "PaymentsToAcquirePropertyPlantAndEquipment");
        if (total.periodEnd() != null && (ppe.periodEnd() == null || !total.periodEnd().isBefore(ppe.periodEnd()))) return total;
        return ppe;
    }

    /** 優先股帳面值缺失或為 0 時直接採母公司股東權益（缺母公司權益時採總權益減非控權益）；優先股 > 0 時扣除帳面值。 */
    private Flow commonEquity(List<SecFinancialFact> facts) {
        Flow parent = parentStockholdersEquity(facts);
        if (parent.value() == null) return parent;
        LocalDate selectedEnd = parent.periodEnd();
        List<SecFinancialFact> samePeriod = facts.stream().filter(fact -> selectedEnd.equals(fact.getPeriodEnd())).toList();
        Flow preferred = preferredEquityBookValue(samePeriod, selectedEnd);
        if (preferred.reason() != null && preferred.reason().contains("CONFLICT")) return preferred;
        BigDecimal preferredVal = preferred.value() == null ? BigDecimal.ZERO : preferred.value();
        if (preferredVal.signum() < 0) return missing("SEC_COMMON_EQUITY_UNRESOLVED", selectedEnd);
        if (preferredVal.signum() == 0) return parent;
        return combineWithNote(parent.value().subtract(preferredVal), List.of(parent, preferred),
                "StockholdersEquity - PreferredStock");
    }

    private Flow parentStockholdersEquity(List<SecFinancialFact> facts) {
        Flow parent = balance(facts, "StockholdersEquity");
        Flow total = balance(facts, "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
        boolean missingAtLatest = parent.periodEnd() == null
                || (total.periodEnd() != null && total.periodEnd().isAfter(parent.periodEnd()));
        boolean samePeriodConflict = parent.value() == null
                && total.periodEnd() != null && total.periodEnd().equals(parent.periodEnd());
        if (missingAtLatest && total.reason() != null && total.reason().contains("CONFLICT")) return total;
        if (total.value() != null && (missingAtLatest || samePeriodConflict)) {
            List<SecFinancialFact> sameTotalPeriod = facts.stream()
                    .filter(fact -> total.periodEnd().equals(fact.getPeriodEnd())).toList();
            Flow minority = balance(sameTotalPeriod, "MinorityInterest");
            if (minority.reason() != null && minority.reason().contains("CONFLICT")) return minority;
            if (samePeriodConflict && minority.value() == null) return parent;
            BigDecimal minorityVal = minority.value() == null ? BigDecimal.ZERO : minority.value();
            List<Flow> parts = minority.value() == null ? List.of(total) : List.of(total, minority);
            return combineWithNote(total.value().subtract(minorityVal), parts, "TotalEquity - MinorityInterest");
        }
        return parent;
    }

    private Flow preferredEquityBookValue(List<SecFinancialFact> samePeriod, LocalDate end) {
        if (end == null || samePeriod.isEmpty()) return new Flow(BigDecimal.ZERO, null, end, null, null, null, null);
        Flow carrying = balance(samePeriod, "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount");
        Flow outstandingVal = balance(samePeriod, "PreferredStockValueOutstanding");
        Flow parVal = balance(samePeriod, "PreferredStockValue");
        Flow convertible = balance(samePeriod, "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue");
        Flow outstandingShares = balance(samePeriod, "PreferredStockSharesOutstanding");
        for (Flow item : List.of(carrying, outstandingVal, parVal, convertible, outstandingShares)) {
            if (item.reason() != null && item.reason().contains("CONFLICT")) return item;
        }
        Flow primary = carrying.value() != null ? carrying : outstandingVal.value() != null ? outstandingVal : parVal;
        // 若同期間明示流通優先股股數為 0 且無帳面優先股資本，視為 0。
        if (outstandingShares.value() != null && outstandingShares.value().signum() == 0
                && (carrying.value() == null || carrying.value().signum() == 0)
                && (outstandingVal.value() == null || outstandingVal.value().signum() == 0)
                && (parVal.value() == null || parVal.value().signum() == 0)
                && (convertible.value() == null || convertible.value().signum() == 0)) {
            return combineWithNote(BigDecimal.ZERO, List.of(outstandingShares), "PreferredStock = 0");
        }
        List<Flow> used = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        if (primary.value() != null) {
            amount = amount.add(primary.value());
            used.add(primary);
        }
        if (convertible.value() != null && convertible.value().signum() > 0) {
            amount = amount.add(convertible.value());
            used.add(convertible);
        }
        if (used.isEmpty()) {
            Instant latestObserved = samePeriod.stream().map(SecFinancialFact::getObservedAt).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
            LocalDate latestAvail = samePeriod.stream().map(SecFinancialFact::getAvailableFromDate).filter(Objects::nonNull)
                    .max(LocalDate::compareTo).orElse(null);
            return new Flow(BigDecimal.ZERO, null, end, latestAvail, latestObserved, null, null, null, "PreferredStock default 0");
        }
        return combineWithNote(amount, used, "PreferredStock");
    }

    /** US GAAP EV 調整項：債務（優先排除營運租賃，無短債／融資租賃視為 0）＋優先股帳面值＋非控權益帳面值－現金。 */
    private Flow enterpriseValueAdjustment(List<SecFinancialFact> facts) {
        Flow cash = balance(facts, "CashAndCashEquivalentsAtCarryingValue");
        LocalDate end = cash.periodEnd();
        if (cash.value() == null || cash.value().signum() < 0) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        List<SecFinancialFact> samePeriod = facts.stream().filter(fact -> end.equals(fact.getPeriodEnd())).toList();
        List<Flow> components = new ArrayList<>(List.of(cash));

        Flow preferred = preferredEquityBookValue(samePeriod, end);
        if (preferred.reason() != null && preferred.reason().contains("CONFLICT")) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        BigDecimal preferredVal = preferred.value() != null && preferred.value().signum() > 0 ? preferred.value() : BigDecimal.ZERO;
        if (preferred.observedAt() != null && preferred.availableFrom() != null) components.add(preferred);

        Flow nci = resolveSecNoncontrollingInterest(samePeriod, end);
        if (nci.reason() != null && nci.reason().contains("CONFLICT")) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        BigDecimal minorityVal = nci.value() != null ? nci.value() : BigDecimal.ZERO;
        if (nci.observedAt() != null && nci.availableFrom() != null && !"NoncontrollingInterest default 0".equals(nci.note())) {
            components.add(nci);
        }

        Flow debt = resolveSecDebt(samePeriod, end);
        if (debt.value() == null || debt.value().signum() < 0) return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
        if (debt.observedAt() != null && debt.availableFrom() != null) components.add(debt);

        BigDecimal adjustment = debt.value().add(preferredVal).add(minorityVal).subtract(cash.value());
        return combineWithNote(adjustment, components, "TotalDebt + Preferred + NCI - Cash");
    }

    private Flow resolveSecNoncontrollingInterest(List<SecFinancialFact> samePeriod, LocalDate end) {
        if (end == null || samePeriod.isEmpty()) return missing("SEC_BALANCE_INPUT_MISSING", end);
        Flow minority = balance(samePeriod, "MinorityInterest");
        if (minority.reason() != null && minority.reason().contains("CONFLICT")) return minority;
        List<Flow> used = new ArrayList<>();
        BigDecimal amount = null;
        if (minority.value() != null) {
            amount = minority.value();
            used.add(minority);
        } else {
            Flow parent = balance(samePeriod, "StockholdersEquity");
            Flow total = balance(samePeriod, "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest");
            if (parent.reason() != null && parent.reason().contains("CONFLICT")) return parent;
            if (total.reason() != null && total.reason().contains("CONFLICT")) return total;
            if (parent.value() != null && total.value() != null) {
                amount = total.value().subtract(parent.value());
                used.add(parent);
                used.add(total);
            }
        }
        Flow redeemable = balance(samePeriod, "RedeemableNoncontrollingInterestEquityCarryingAmount");
        if (redeemable.reason() != null && redeemable.reason().contains("CONFLICT")) return redeemable;
        if (redeemable.value() != null && redeemable.value().signum() > 0) {
            amount = (amount == null ? BigDecimal.ZERO : amount).add(redeemable.value());
            used.add(redeemable);
        }
        if (amount != null) return combineWithNote(amount, used, "NoncontrollingInterest");
        for (String anchorTag : List.of("StockholdersEquity",
                "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "Liabilities", "Assets")) {
            Flow anchor = balance(samePeriod, anchorTag);
            if (anchor.value() != null) return combineWithNote(BigDecimal.ZERO, List.of(anchor), "NoncontrollingInterest default 0");
        }
        return missing("SEC_BALANCE_INPUT_MISSING", end);
    }

    private Flow resolveSecDebt(List<SecFinancialFact> samePeriod, LocalDate end) {
        Flow combinedAll = balance(samePeriod, "DebtAndCapitalLeaseObligations");
        if (combinedAll.value() != null) return combinedAll;

        Flow debtCurrent = balance(samePeriod, "DebtCurrent");
        Flow shortBorrowings = balance(samePeriod, "ShortTermBorrowings");
        Flow commercialPaper = balance(samePeriod, "CommercialPaper");
        Flow ltAndCapLeases = balance(samePeriod, "LongTermDebtAndCapitalLeaseObligations");
        if (ltAndCapLeases.value() != null) {
            Flow shortLeg = debtCurrent.value() != null ? debtCurrent
                    : shortBorrowings.value() != null ? shortBorrowings : commercialPaper;
            if (shortLeg.value() != null) return combine(shortLeg.value().add(ltAndCapLeases.value()), List.of(shortLeg, ltAndCapLeases));
            return ltAndCapLeases;
        }

        Flow ltInclCurrent = balance(samePeriod, "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities");
        if (ltInclCurrent.value() != null) {
            Flow shortPure = shortBorrowings.value() != null ? shortBorrowings : commercialPaper;
            if (shortPure.value() != null) return combine(ltInclCurrent.value().add(shortPure.value()), List.of(ltInclCurrent, shortPure));
            return ltInclCurrent;
        }

        Flow ltTotal = balance(samePeriod, "LongTermDebt");
        Flow ltCurrent = balance(samePeriod, "LongTermDebtCurrent");
        Flow ltNoncurrent = balance(samePeriod, "LongTermDebtNoncurrent");
        List<Flow> debtParts = new ArrayList<>();
        BigDecimal borrowingAmount = BigDecimal.ZERO;
        if (ltTotal.value() != null) {
            borrowingAmount = borrowingAmount.add(ltTotal.value());
            debtParts.add(ltTotal);
            Flow shortPure = shortBorrowings.value() != null ? shortBorrowings : commercialPaper;
            if (shortPure.value() != null) {
                borrowingAmount = borrowingAmount.add(shortPure.value());
                debtParts.add(shortPure);
            }
        } else if (ltCurrent.value() != null || ltNoncurrent.value() != null) {
            if (ltCurrent.value() != null) {
                borrowingAmount = borrowingAmount.add(ltCurrent.value());
                debtParts.add(ltCurrent);
            }
            if (ltNoncurrent.value() != null) {
                borrowingAmount = borrowingAmount.add(ltNoncurrent.value());
                debtParts.add(ltNoncurrent);
            }
            Flow shortPure = ltCurrent.value() == null && debtCurrent.value() != null ? debtCurrent
                    : shortBorrowings.value() != null ? shortBorrowings : commercialPaper;
            if (shortPure.value() != null) {
                borrowingAmount = borrowingAmount.add(shortPure.value());
                debtParts.add(shortPure);
            }
        } else {
            Flow shortLeg = debtCurrent.value() != null ? debtCurrent
                    : shortBorrowings.value() != null ? shortBorrowings : commercialPaper;
            if (shortLeg.value() != null) {
                borrowingAmount = borrowingAmount.add(shortLeg.value());
                debtParts.add(shortLeg);
            }
        }

        Flow financeLease = balance(samePeriod, "FinanceLeaseLiability");
        if (financeLease.value() == null) financeLease = sumPresentBalances(samePeriod, "FinanceLeaseLiabilityCurrent", "FinanceLeaseLiabilityNoncurrent");
        if (financeLease.value() == null) financeLease = balance(samePeriod, "CapitalLeaseObligations");
        if (financeLease.value() == null) financeLease = sumPresentBalances(samePeriod, "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent");
        if (financeLease.value() != null) {
            borrowingAmount = borrowingAmount.add(financeLease.value());
            debtParts.add(financeLease);
        }

        if (!debtParts.isEmpty()) return combine(borrowingAmount, debtParts);
        // 若當期資產負債表主項已存在且未列任何借款標籤，代表無計息債務（視為 0）。
        for (String anchorTag : List.of("Liabilities", "Assets", "StockholdersEquity",
                "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest")) {
            Flow anchor = balance(samePeriod, anchorTag);
            if (anchor.value() != null) return combineWithNote(BigDecimal.ZERO, List.of(anchor), "ZeroDebt");
        }
        return missing("SEC_EV_COMPONENTS_UNRESOLVED", end);
    }

    private Flow resolveSecCurrentBorrowing(List<SecFinancialFact> samePeriod) {
        Flow debtCurrent = balance(samePeriod, "DebtCurrent");
        Flow ltCurrent = balance(samePeriod, "LongTermDebtCurrent");
        Flow shortBorrowings = balance(samePeriod, "ShortTermBorrowings");
        Flow commercialPaper = balance(samePeriod, "CommercialPaper");
        for (Flow item : List.of(debtCurrent, ltCurrent, shortBorrowings, commercialPaper)) {
            if (item.reason() != null && item.reason().contains("CONFLICT")) return item;
        }
        Flow shortPure = shortBorrowings.value() != null ? shortBorrowings : commercialPaper;
        if (debtCurrent.value() != null) {
            BigDecimal partsSum = (ltCurrent.value() != null ? ltCurrent.value() : BigDecimal.ZERO)
                    .add(shortPure.value() != null ? shortPure.value() : BigDecimal.ZERO);
            if (debtCurrent.value().compareTo(partsSum) >= 0) return debtCurrent;
        }
        if (ltCurrent.value() != null && shortPure.value() != null) {
            return combine(ltCurrent.value().add(shortPure.value()), List.of(ltCurrent, shortPure));
        }
        if (ltCurrent.value() != null) return ltCurrent;
        if (shortPure.value() != null) return shortPure;
        if (debtCurrent.value() != null) return debtCurrent;
        return missing("SEC_BALANCE_INPUT_MISSING", null);
    }

    private Flow sumPresentBalances(List<SecFinancialFact> facts, String firstTag, String secondTag) {
        Flow first = balance(facts, firstTag);
        Flow second = balance(facts, secondTag);
        if (first.value() != null && second.value() != null) return combine(first.value().add(second.value()), List.of(first, second));
        if (first.value() != null) return first;
        if (second.value() != null) return second;
        return missing("SEC_BALANCE_INPUT_MISSING", later(first.periodEnd(), second.periodEnd()));
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
        return flow(value, null, end, versions, tag);
    }

    private boolean aligned(Flow first, Flow second) {
        return first.value() != null && second.value() != null
                && Objects.equals(first.periodStart(), second.periodStart()) && Objects.equals(first.periodEnd(), second.periodEnd());
    }

    private Flow combine(BigDecimal value, List<Flow> components) {
        return combineWithNote(value, components, null);
    }

    private Flow combineWithNote(BigDecimal value, List<Flow> components, String note) {
        Flow first = components.getFirst();
        LocalDate available = components.stream().map(Flow::availableFrom).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        Instant observed = components.stream().map(Flow::observedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        LocalDate earliestFiled = components.stream().map(Flow::earliestFiled).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        return new Flow(value, first.periodStart(), first.periodEnd(), available, observed, null, null, earliestFiled, note);
    }

    private Flow operatingCashFlow(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read) {
        Flow total = read.apply(facts, OPERATING_CASH_FLOW);
        Flow continuing = read.apply(facts, CONTINUING_OPERATING_CASH_FLOW);
        if (continuing.periodEnd() == null || total.periodEnd() != null && !continuing.periodEnd().isAfter(total.periodEnd())) return total;
        return continuing;
    }

    /** 非金融業（排除 SIC 60xx-64xx、6712 與列示利息淨收入者）缺 Revenues／SalesRevenueNet 時直接採用完整期間的合約收入。 */
    private Flow revenue(List<SecFinancialFact> facts, BiFunction<List<SecFinancialFact>, String, Flow> read, String sic) {
        LocalDate latest = facts.stream().filter(fact -> List.of("Revenues", "SalesRevenueNet",
                        "RevenueFromContractWithCustomerExcludingAssessedTax", "RevenuesNetOfInterestExpense").contains(fact.getTag()))
                .map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        if (latest == null) return missing("SEC_REVENUE_MISSING", null);
        boolean hasNetRevenue = facts.stream().anyMatch(fact ->
                "RevenuesNetOfInterestExpense".equals(fact.getTag()) && latest.equals(fact.getPeriodEnd()));
        boolean excludedFinancial = IndustryValuationProposalService.isFinancialSicExcludedFromContractRevenue(sic);
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
                Flow combined = trailing(totals, false, "Revenues/SalesRevenueNet");
                if (combined.value() != null && latest.equals(combined.periodEnd())) return combined;
            }
            if (!excludedFinancial && contractRevenue.value() != null && latest.equals(contractRevenue.periodEnd())) {
                return contractRevenue;
            }
            if (!excludedFinancial && latest.equals(contractRevenue.periodEnd())
                    && contractRevenue.reason() != null && contractRevenue.reason().contains("CONFLICT")) {
                return contractRevenue;
            }
            if (incompleteTotal != null) return incompleteTotal;
            return missing("SEC_REVENUE_TOTAL_SCOPE_UNVERIFIED", latest);
        }
        Flow chosen = candidates.getFirst();
        for (Flow candidate : candidates) {
            if (chosen.value().compareTo(candidate.value()) != 0 || !Objects.equals(chosen.periodStart(), candidate.periodStart()))
                return missing("SEC_REVENUE_SCOPE_CONFLICT", latest);
        }
        if (verifiedContract && chosen.value().compareTo(contractRevenue.value()) < 0) {
            return combineWithNote(contractRevenue.value(), List.of(contractRevenue, grossProfit, costOfSales),
                    "RevenueFromContractWithCustomerExcludingAssessedTax");
        }
        return chosen;
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
        return trailing(facts.stream().filter(fact -> tag.equals(fact.getTag())).toList(),
                "EarningsPerShareDiluted".equals(tag) || "EarningsPerShareBasicAndDiluted".equals(tag), tag);
    }

    private Flow trailing(List<SecFinancialFact> tagged, boolean eps, String note) {
        LocalDate end = tagged.stream().map(SecFinancialFact::getPeriodEnd).max(LocalDate::compareTo).orElse(null);
        if (end == null) return missing("SEC_TTM_PERIODS_MISSING", null);
        Map<Period, Point> periods = revisions(tagged);
        List<Point> annuals = periods.entrySet().stream().filter(entry -> end.equals(entry.getKey().end()) && annual(entry.getKey()))
                .map(Map.Entry::getValue).toList();
        if (!annuals.isEmpty()) return aggregateAnnual(annuals, end, note);

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
            return flow(total, through.plusDays(1), end, quarters, note);
        }

        if (eps) return missing("SEC_EPS_TTM_PERIODS_MISSING", end);

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
                    BigDecimal total = annual.getValue().fact().getValue().add(current.getValue().fact().getValue())
                            .subtract(previous.getValue().fact().getValue());
                    derived.add(flow(total, prior.end().plusDays(1), end, points, note));
                }
            }
        }
        if (derived.isEmpty()) return missing("SEC_TTM_PERIODS_MISSING", end);
        Flow chosen = derived.getFirst();
        for (Flow value : derived) {
            if (!chosen.periodStart().equals(value.periodStart()) || chosen.value().compareTo(value.value()) != 0)
                return missing("SEC_TTM_PERIOD_CONFLICT", end);
        }
        return chosen;
    }

    private Flow aggregateAnnual(List<Point> annuals, LocalDate end, String note) {
        Point chosen = annuals.getFirst();
        for (Point point : annuals) {
            if (point.conflict()) return missing("SEC_FACT_VERSION_CONFLICT", end);
            if (!Objects.equals(chosen.fact().getPeriodStart(), point.fact().getPeriodStart())
                    || chosen.fact().getValue().compareTo(point.fact().getValue()) != 0) return missing("SEC_TTM_PERIOD_CONFLICT", end);
        }
        return flow(chosen.fact().getValue(), chosen.fact().getPeriodStart(), end, annuals, note);
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

    private Flow flow(BigDecimal value, LocalDate start, LocalDate end, List<Point> points, String note) {
        LocalDate available = points.stream().map(point -> point.fact().getAvailableFromDate()).max(LocalDate::compareTo).orElseThrow();
        Instant observed = points.stream().map(point -> point.fact().getObservedAt()).max(Instant::compareTo).orElseThrow();
        LocalDate earliestFiled = points.stream().map(point -> point.fact().getFiled()).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate epsBasisFrom = Set.of("EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted").contains(points.getFirst().fact().getTag())
                ? points.stream().map(point -> point.fact().getPeriodEnd()).min(LocalDate::compareTo).orElseThrow() : null;
        return new Flow(value, start, end, available, observed, null, epsBasisFrom, earliestFiled, note);
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
    private Flow missing(String reason, LocalDate end) { return new Flow(null, null, end, null, null, reason, null, null, null); }
    private LocalDate later(LocalDate a, LocalDate b) { return a == null ? b : b == null || a.isAfter(b) ? a : b; }
    private Instant later(Instant a, Instant b) { return a == null ? b : b == null || a.isAfter(b) ? a : b; }
}
