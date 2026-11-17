package com.ykm.stock.analytics.converter.sec;

import com.fasterxml.jackson.databind.JsonNode;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SecFinancialFactConverter {
    public static final String DEPRECIATION_AMORTIZATION = "DepreciationAndAmortization";
    public static final String DEPRECIATION_AMORTIZATION_ACCRETION = "DepreciationAmortizationAndAccretionNet";
    public static final String DEPRECIATION_DEPLETION_AMORTIZATION = "DepreciationDepletionAndAmortization";
    public static final String ACCRETION_EXPENSE = "AccretionExpense";
    public static final String ADJUSTMENT_FOR_AMORTIZATION = "AdjustmentForAmortization";
    public static final List<String> EQUITY_COMPONENT_TAGS = List.of("PreferredStockSharesOutstanding", "PreferredStockSharesIssued",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount", "TreasuryStockValue", "TreasuryStockCommonValue",
            "CommonStockValue", "AdditionalPaidInCapital", "AdditionalPaidInCapitalCommonStock");
    // 來源概念分開保存，不是公式別名或可直接相加的清單；期間／單位／口徑仍須消費端對齊。
    static final List<String> US_GAAP_TAGS = List.of(
            "CommonStockSharesOutstanding", "PreferredStockSharesOutstanding", "PreferredStockSharesIssued", "EarningsPerShareBasic", "EarningsPerShareDiluted", "EarningsPerShareBasicAndDiluted",
            "StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "MinorityInterest",
            "RedeemableNoncontrollingInterestEquityCarryingAmount", "PreferredStockValueOutstanding", "PreferredStockValue",
            "PreferredStockIncludingAdditionalPaidInCapitalNetOfDiscount", "ConvertiblePreferredStockNonredeemableOrRedeemableIssuerOptionValue",
            "RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet", "RevenuesNetOfInterestExpense",
            "NetCashProvidedByUsedInOperatingActivities", "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
            "PaymentsToAcquirePropertyPlantAndEquipment", "PaymentsToAcquireSoftware", "PaymentsToDevelopSoftware", "PaymentsToAcquireProductiveAssets",
            "CashAndCashEquivalentsAtCarryingValue", "MarketableSecuritiesCurrent", "MarketableSecuritiesNoncurrent",
            "ShortTermBorrowings", "CommercialPaper", "DebtCurrent", "LongTermDebtCurrent", "LongTermDebtNoncurrent", "LongTermDebt",
            "LongTermDebtAndCapitalLeaseObligationsCurrent", "LongTermDebtAndCapitalLeaseObligations", "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities", "DebtAndCapitalLeaseObligations",
            "FinanceLeaseLiability", "FinanceLeaseLiabilityCurrent", "FinanceLeaseLiabilityNoncurrent",
            "OperatingLeaseLiability", "OperatingLeaseLiabilityCurrent", "OperatingLeaseLiabilityNoncurrent",
            "CapitalLeaseObligations", "CapitalLeaseObligationsCurrent", "CapitalLeaseObligationsNoncurrent",
            "GrossProfit", "CostOfGoodsAndServicesSold", "Assets", "Liabilities", "LiabilitiesCurrent",
            "AccountsPayableCurrent", "AccruedLiabilitiesCurrent", "DeferredRevenueCurrent", "DeferredRevenueNoncurrent", "OtherLiabilitiesNoncurrent",
            "CommonStocksIncludingAdditionalPaidInCapital", "CommonStockValue", "AdditionalPaidInCapital",
            "AdditionalPaidInCapitalCommonStock", "RetainedEarningsAccumulatedDeficit", "AccumulatedOtherComprehensiveIncomeLossNetOfTax",
            "TreasuryStockValue", "TreasuryStockCommonValue",
            "OperatingIncomeLoss", "Depletion", "NetIncomeLoss", "ProfitLoss", "NetIncomeLossAvailableToCommonStockholdersBasic", "IncomeTaxExpenseBenefit", "InterestExpenseNonoperating", "InterestExpense", "InterestAndDebtExpense",
            "Depreciation", "AmortizationOfIntangibleAssets", DEPRECIATION_DEPLETION_AMORTIZATION,
            DEPRECIATION_AMORTIZATION, DEPRECIATION_AMORTIZATION_ACCRETION, ACCRETION_EXPENSE, ADJUSTMENT_FOR_AMORTIZATION);

    public record FactKey(String cik, String taxonomy, String tag, String unit, LocalDate periodStart, LocalDate periodEnd, String accession) {}
    private final MarketStatusService calendar;

    public List<SecFinancialFact> convert(String cik, JsonNode root, Instant observedAt) {
        return convert(cik, root, observedAt, LocalDate.MIN);
    }

    public List<SecFinancialFact> convert(String cik, JsonNode root, Instant observedAt, LocalDate periodEndFrom) {
        // CIK 來自精確匹配的官方 ZIP entry；根欄位存在時仍必須一致，不依公司名猜測。
        if (root == null || !root.isObject() || !root.path("facts").isObject()) throw new IllegalStateException("SEC_FACT_COMPANY_INVALID:" + cik);
        JsonNode embeddedCik = root.path("cik");
        if (!embeddedCik.isMissingNode() && (!embeddedCik.asText().matches("[0-9]{1,10}")
                || Long.parseLong(embeddedCik.asText()) != Long.parseLong(cik))) throw new IllegalStateException("SEC_FACT_COMPANY_INVALID:" + cik);
        Map<FactKey, SecFinancialFact> result = new LinkedHashMap<>();
        readTag(cik, root, "dei", "EntityCommonStockSharesOutstanding", observedAt, periodEndFrom, result);
        for (String tag : US_GAAP_TAGS) readTag(cik, root, "us-gaap", tag, observedAt, periodEndFrom, result);
        // fy/fp 描述申報本身。只有該申報真正當期期末保留財季標記，避免將比較期標成今年。
        Map<String, LocalDate> reportEnds = new LinkedHashMap<>();
        for (SecFinancialFact fact : result.values()) {
            if (!"us-gaap".equals(fact.getTaxonomy()) || "shares".equals(fact.getUnit()) || fact.getPeriodEnd().isAfter(fact.getFiled())) continue;
            reportEnds.merge(fact.getAccession(), fact.getPeriodEnd(), (first, second) -> first.isAfter(second) ? first : second);
        }
        for (SecFinancialFact fact : result.values()) {
            if (!fact.getPeriodEnd().equals(reportEnds.get(fact.getAccession()))) {
                fact.setReportFiscalYear(null);
                fact.setReportFiscalPeriod(null);
            }
        }
        return List.copyOf(result.values());
    }

    public List<SecFinancialFact> historicalEquityComponents(String cik, JsonNode root, Instant observedAt, LocalDate from, LocalDate before) {
        if (!from.isBefore(before)) return List.of();
        Map<FactKey, SecFinancialFact> result = new LinkedHashMap<>();
        for (String tag : EQUITY_COMPONENT_TAGS) readTag(cik, root, "us-gaap", tag, observedAt, from, result);
        return result.values().stream().filter(fact -> fact.getPeriodEnd().isBefore(before)).toList();
    }

    private void readTag(String cik, JsonNode root, String taxonomy, String tag, Instant observedAt,
                         LocalDate periodEndFrom, Map<FactKey, SecFinancialFact> result) {
        JsonNode concept = root.path("facts").path(taxonomy).path(tag);
        if (concept.isMissingNode()) return;
        if (!concept.isObject() || !concept.path("units").isObject()) throw new IllegalStateException("SEC_FACT_UNITS_INVALID:" + tag);
        Iterator<Map.Entry<String, JsonNode>> units = concept.path("units").fields();
        while (units.hasNext()) {
            Map.Entry<String, JsonNode> unit = units.next();
            // 股數僅收原始 shares；其他財務項保留實際幣別／每股單位，不匯兌、不猜尺度。
            if (isSharesTag(tag) && !"shares".equals(unit.getKey())) continue;
            if (unit.getKey().isBlank() || unit.getKey().length() > 64 || !unit.getValue().isArray()) throw new IllegalStateException("SEC_FACT_UNIT_INVALID:" + tag);
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
                    log.warn("SEC source negative shares retained as raw anomaly | cik={} tag={} accession={} end={} value={}", cik, tag, fact.getAccession(), fact.getPeriodEnd(), fact.getValue());
                fact.setAvailableFromDate(calendar.getNextTradingDay(fact.getFiled()));
                fact.setObservedAt(observedAt);
                FactKey key = key(fact);
                SecFinancialFact previous = result.putIfAbsent(key, fact);
                if (previous != null && !sameDisclosure(previous, fact)) throw new IllegalStateException("SEC_FACT_CONFLICT:" + key);
            }
        }
    }

    private boolean isSharesTag(String tag) {
        return "EntityCommonStockSharesOutstanding".equals(tag) || "CommonStockSharesOutstanding".equals(tag)
                || "PreferredStockSharesOutstanding".equals(tag) || "PreferredStockSharesIssued".equals(tag);
    }

    private String text(JsonNode value, String key) {
        JsonNode field = value.path(key);
        if (!field.isTextual() || field.asText().isBlank()) throw new IllegalStateException("SEC_FACT_FIELD_MISSING:" + key);
        return field.asText();
    }

    public static FactKey key(SecFinancialFact fact) {
        return new FactKey(fact.getCik(), fact.getTaxonomy(), fact.getTag(), fact.getUnit(), fact.getPeriodStart(), fact.getPeriodEnd(), fact.getAccession());
    }

    public static boolean sameDisclosure(SecFinancialFact first, SecFinancialFact second) {
        return first.getValue().compareTo(second.getValue()) == 0 && first.getFiled().equals(second.getFiled()) && first.getForm().equals(second.getForm());
    }
}
