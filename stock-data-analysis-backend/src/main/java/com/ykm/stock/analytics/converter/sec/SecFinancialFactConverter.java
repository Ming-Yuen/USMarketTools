package com.ykm.stock.analytics.converter.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.FilingSubmission;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class SecFinancialFactConverter {
    public static final String DEPRECIATION_AMORTIZATION = "DepreciationAndAmortization";
    public static final String DEPRECIATION_AMORTIZATION_ACCRETION = "DepreciationAmortizationAndAccretionNet";
    public static final String DEPRECIATION_DEPLETION_AMORTIZATION = "DepreciationDepletionAndAmortization";
    public static final String ACCRETION_EXPENSE = "AccretionExpense";
    public static final String ADJUSTMENT_FOR_AMORTIZATION = "AdjustmentForAmortization";
    public static final String WEIGHTED_AVERAGE_DILUTED_SHARES = "WeightedAverageNumberOfDilutedSharesOutstanding";
    public static final String WEIGHTED_AVERAGE_BASIC_AND_DILUTED_SHARES = "WeightedAverageNumberOfShareOutstandingBasicAndDiluted";

    private static final Set<String> CORE_STATEMENT_PERIOD_TAGS = Set.of(
            "Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerExcludingAssessedTax", "RevenuesNetOfInterestExpense",
            "OperatingIncomeLoss", "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic",
            "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted",
            "Assets", "Liabilities", "StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "NetCashProvidedByUsedInOperatingActivities", "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations");

    // 精簡至估值與財務面板實際讀取的核心標準標籤，補入稀釋加權平均股數以驗證跨越拆股日的 EPS。
    public static final List<String> US_GAAP_TAGS = List.of(
            "CommonStockSharesOutstanding", WEIGHTED_AVERAGE_DILUTED_SHARES, WEIGHTED_AVERAGE_BASIC_AND_DILUTED_SHARES,
            "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted",
            "StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "MinorityInterest",
            "RedeemableNoncontrollingInterestEquityCarryingAmount", "PreferredStockValueOutstanding", "PreferredStockValue",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount", "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue",
            "RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet", "RevenuesNetOfInterestExpense",
            "NetCashProvidedByUsedInOperatingActivities", "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
            "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireProductiveAssets",
            "CashAndCashEquivalentsAtCarryingValue",
            "ShortTermBorrowings", "CommercialPaper", "DebtCurrent", "LongTermDebtCurrent", "LongTermDebtNoncurrent", "LongTermDebt",
            "LongTermDebtAndCapitalLeaseObligationsCurrent", "LongTermDebtAndCapitalLeaseObligations",
            "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities", "DebtAndCapitalLeaseObligations",
            "FinanceLeaseLiability", "FinanceLeaseLiabilityCurrent", "FinanceLeaseLiabilityNoncurrent",
            "OperatingLeaseLiability", "OperatingLeaseLiabilityCurrent", "OperatingLeaseLiabilityNoncurrent",
            "CapitalLeaseObligations", "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent",
            "Assets", "Liabilities",
            "OperatingIncomeLoss", "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic",
            "GrossProfit",
            "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "IncomeTaxExpenseBenefit",
            "InterestExpense", "InterestExpenseDebt",
            "ShareBasedCompensation", "AllocatedShareBasedCompensationExpense",
            "PaymentsOfDividendsCommonStock", "PaymentsOfDividends", "PaymentsForRepurchaseOfCommonStock",
            "Goodwill", "IntangibleAssetsNetExcludingGoodwill", "FiniteLivedIntangibleAssetsNet",
            "Depreciation", "AmortizationOfIntangibleAssets", DEPRECIATION_DEPLETION_AMORTIZATION,
            DEPRECIATION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION);

    public record FactKey(String cik, String taxonomy, String tag, String unit, LocalDate periodStart, LocalDate periodEnd, String accession) {}

    private final MarketStatusService calendar;

    public List<SecFinancialFact> convert(String cik, JsonNode root, Instant observedAt) {
        return convert(cik, root, observedAt, LocalDate.MIN, Map.of());
    }

    public List<SecFinancialFact> convert(String cik, JsonNode root, Instant observedAt, LocalDate periodEndFrom) {
        return convert(cik, root, observedAt, periodEndFrom, Map.of());
    }

    public List<SecFinancialFact> convert(String cik, JsonNode root, Instant observedAt, LocalDate periodEndFrom,
                                          Map<String, FilingSubmission> submissions) {
        if (root == null || !root.isObject() || !root.path("facts").isObject())
            throw new IllegalStateException("SEC_FACT_COMPANY_INVALID:" + cik);
        JsonNode embeddedCik = root.path("cik");
        if (!embeddedCik.isMissingNode() && (!embeddedCik.asText().matches("[0-9]{1,10}")
                || Long.parseLong(embeddedCik.asText()) != Long.parseLong(cik)))
            throw new IllegalStateException("SEC_FACT_COMPANY_INVALID:" + cik);

        Map<String, FilingSubmission> metadata = submissions == null ? Map.of() : submissions;
        Map<FactKey, SecFinancialFact> result = new LinkedHashMap<>();
        readTag(cik, root, "dei", "EntityCommonStockSharesOutstanding", observedAt, periodEndFrom, metadata, result);
        for (String tag : US_GAAP_TAGS) {
            readTag(cik, root, "us-gaap", tag, observedAt, periodEndFrom, metadata, result);
        }

        // 優先採用 submissions.zip 的精確 reportDate 對齊當期期末，避免期後事項日期抹除整份申報的財季標記。
        Map<String, LocalDate> reportEnds = resolveReportEnds(result, metadata);
        for (SecFinancialFact fact : result.values()) {
            if (!fact.getPeriodEnd().equals(reportEnds.get(fact.getAccession()))) {
                fact.setReportFiscalYear(null);
                fact.setReportFiscalPeriod(null);
            }
        }
        return List.copyOf(result.values());
    }

    private Map<String, LocalDate> resolveReportEnds(Map<FactKey, SecFinancialFact> facts, Map<String, FilingSubmission> submissions) {
        Map<String, Set<LocalDate>> candidateEnds = new LinkedHashMap<>();
        Map<String, LocalDate> fallbackStatementMax = new LinkedHashMap<>();
        Map<String, LocalDate> fallbackAnyMax = new LinkedHashMap<>();
        for (SecFinancialFact fact : facts.values()) {
            if (!"us-gaap".equals(fact.getTaxonomy()) || isSharesTag(fact.getTag()) || fact.getPeriodEnd().isAfter(fact.getFiled())) continue;
            String accession = fact.getAccession();
            candidateEnds.computeIfAbsent(accession, ignored -> new LinkedHashSet<>()).add(fact.getPeriodEnd());
            fallbackAnyMax.merge(accession, fact.getPeriodEnd(), (first, second) -> first.isAfter(second) ? first : second);
            if (CORE_STATEMENT_PERIOD_TAGS.contains(fact.getTag())) {
                fallbackStatementMax.merge(accession, fact.getPeriodEnd(), (first, second) -> first.isAfter(second) ? first : second);
            }
        }
        Map<String, LocalDate> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Set<LocalDate>> entry : candidateEnds.entrySet()) {
            String accession = entry.getKey();
            Set<LocalDate> ends = entry.getValue();
            FilingSubmission submission = submissions.get(accession);
            LocalDate reportDate = submission == null ? null : submission.reportDate();
            LocalDate matched = null;
            if (reportDate != null) {
                if (ends.contains(reportDate)) {
                    matched = reportDate;
                } else {
                    long bestDistance = Long.MAX_VALUE;
                    for (LocalDate candidate : ends) {
                        long distance = Math.abs(ChronoUnit.DAYS.between(candidate, reportDate));
                        if (distance <= 7 && (distance < bestDistance || (distance == bestDistance && candidate.isAfter(matched)))) {
                            bestDistance = distance;
                            matched = candidate;
                        }
                    }
                }
            }
            if (matched == null) {
                matched = fallbackStatementMax.getOrDefault(accession, fallbackAnyMax.get(accession));
            }
            if (matched != null) resolved.put(accession, matched);
        }
        return resolved;
    }

    private void readTag(String cik, JsonNode root, String taxonomy, String tag, Instant observedAt,
                         LocalDate periodEndFrom, Map<String, FilingSubmission> submissions, Map<FactKey, SecFinancialFact> result) {
        JsonNode concept = root.path("facts").path(taxonomy).path(tag);
        if (concept.isMissingNode()) return;
        if (!concept.isObject() || !concept.path("units").isObject()) throw new IllegalStateException("SEC_FACT_UNITS_INVALID:" + tag);
        Iterator<Map.Entry<String, JsonNode>> units = concept.path("units").fields();
        while (units.hasNext()) {
            Map.Entry<String, JsonNode> unit = units.next();
            if (isSharesTag(tag) && !"shares".equals(unit.getKey())) continue;
            if (unit.getKey().isBlank() || unit.getKey().length() > 64 || !unit.getValue().isArray())
                throw new IllegalStateException("SEC_FACT_UNIT_INVALID:" + tag);
            for (JsonNode value : unit.getValue()) {
                LocalDate periodEnd = LocalDate.parse(text(value, "end"));
                if (periodEnd.isBefore(periodEndFrom)) continue;
                LocalDate filed = LocalDate.parse(text(value, "filed"));
                SecFinancialFact fact = new SecFinancialFact();
                fact.setCik(cik);
                fact.setTaxonomy(taxonomy);
                fact.setTag(tag);
                fact.setUnit(unit.getKey());
                if (value.hasNonNull("start")) fact.setPeriodStart(LocalDate.parse(text(value, "start")));
                fact.setPeriodEnd(periodEnd);
                fact.setFiled(filed);
                fact.setAccession(text(value, "accn"));
                fact.setForm(text(value, "form"));
                if (value.path("fy").canConvertToInt()) fact.setReportFiscalYear(value.path("fy").intValue());
                String fiscalPeriod = value.path("fp").asText();
                if (List.of("Q1", "Q2", "Q3", "FY").contains(fiscalPeriod)) fact.setReportFiscalPeriod(fiscalPeriod);
                if (!fact.getAccession().matches("[0-9]{10}-[0-9]{2}-[0-9]{6}") || fact.getForm().length() > 32
                        || !value.path("val").isNumber() || fact.getPeriodStart() != null && fact.getPeriodStart().isAfter(fact.getPeriodEnd()))
                    throw new IllegalStateException("SEC_FACT_INVALID:" + tag + ":" + fact.getAccession() + ":" + fact.getPeriodEnd());
                fact.setValue(value.path("val").decimalValue());
                if (isSharesTag(tag) && fact.getValue().signum() < 0)
                    log.warn("SEC source negative shares retained as raw anomaly | cik={} tag={} accession={} end={} value={}",
                            cik, tag, fact.getAccession(), fact.getPeriodEnd(), fact.getValue());
                FilingSubmission submission = submissions.get(fact.getAccession());
                LocalDate acceptanceBase = filed;
                if (submission != null && submission.acceptedAt() != null) {
                    LocalDate acceptedNyDate = submission.acceptedAt().atZone(MarketStatusService.NY_ZONE).toLocalDate();
                    if (acceptedNyDate.isAfter(filed)) acceptanceBase = acceptedNyDate;
                }
                LocalDate nextSession = calendar.getNextTradingDay(acceptanceBase);
                fact.setAvailableFromDate(nextSession != null ? nextSession : acceptanceBase.plusDays(1));
                fact.setObservedAt(observedAt);
                FactKey key = key(fact);
                SecFinancialFact previous = result.get(key);
                if (previous == null || !fact.getFiled().isBefore(previous.getFiled())) {
                    result.put(key, fact);
                }
            }
        }
    }

    private boolean isSharesTag(String tag) {
        return "EntityCommonStockSharesOutstanding".equals(tag)
                || "CommonStockSharesOutstanding".equals(tag)
                || WEIGHTED_AVERAGE_DILUTED_SHARES.equals(tag)
                || WEIGHTED_AVERAGE_BASIC_AND_DILUTED_SHARES.equals(tag);
    }

    private String text(JsonNode value, String key) {
        JsonNode field = value.path(key);
        if (!field.isTextual() || field.asText().isBlank()) throw new IllegalStateException("SEC_FACT_FIELD_MISSING:" + key);
        return field.asText();
    }

    public static FactKey key(SecFinancialFact fact) {
        return new FactKey(fact.getCik(), fact.getTaxonomy(), fact.getTag(), fact.getUnit(),
                fact.getPeriodStart(), fact.getPeriodEnd(), fact.getAccession());
    }

    public static boolean sameDisclosure(SecFinancialFact first, SecFinancialFact second) {
        return first.getValue().compareTo(second.getValue()) == 0
                && first.getFiled().equals(second.getFiled())
                && first.getForm().equals(second.getForm())
                && Objects.equals(first.getAvailableFromDate(), second.getAvailableFromDate())
                && Objects.equals(first.getReportFiscalYear(), second.getReportFiscalYear())
                && Objects.equals(first.getReportFiscalPeriod(), second.getReportFiscalPeriod());
    }
}
