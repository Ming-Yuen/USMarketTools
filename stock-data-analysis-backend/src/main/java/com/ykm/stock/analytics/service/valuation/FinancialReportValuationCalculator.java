package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.service.stock.StockIndicatorService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 財報取數與純數值計算；不推定公開時間、股數或股份基準，不下載、寫庫或推進水位。 */
public class FinancialReportValuationCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Set<FinancialConcept> ADDITIVE_FLOWS = Set.of(FinancialConcept.REVENUE_AS_REPORTED,
            FinancialConcept.OPERATING_REVENUE, FinancialConcept.NET_INCOME_COMMON, FinancialConcept.NET_INCOME_PARENT,
            FinancialConcept.NET_PROFIT, FinancialConcept.OPERATING_PROFIT, FinancialConcept.OPERATING_CASH_FLOW, FinancialConcept.CAPITAL_EXPENDITURE,
            FinancialConcept.FREE_CASH_FLOW, FinancialConcept.INCOME_TAX_EXPENSE, FinancialConcept.INTEREST_EXPENSE_AS_REPORTED,
            FinancialConcept.NONOPERATING_INTEREST_EXPENSE, FinancialConcept.INCOME_DEPRECIATION,
            FinancialConcept.INCOME_AMORTIZATION, FinancialConcept.INCOME_DEPLETION,
            FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION_DEPLETION,
            FinancialConcept.CASH_FLOW_DEPRECIATION_AMORTIZATION_DEPLETION);

    private record ItemKey(int statementType, String name) {}

    private record FieldKey(int statementType, long fieldId) {}

    private static final Map<FieldKey, FinancialConcept> STATIC_FIELD_CONCEPTS = buildStaticFieldConcepts();
    private static final Map<ItemKey, FinancialConcept> STATIC_NAME_CONCEPTS = buildStaticNameConcepts();

    private static Map<FieldKey, FinancialConcept> buildStaticFieldConcepts() {
        Map<FieldKey, FinancialConcept> map = new HashMap<>();
        // 1: 損益表（一般工商 8xxx、銀行 9xxx、保險 10xxx）
        registerFields(map, 1, FinancialConcept.REVENUE_AS_REPORTED, 8001L, 9001L, 10001L);
        registerFields(map, 1, FinancialConcept.OPERATING_REVENUE, 8002L, 9002L, 10002L);
        registerFields(map, 1, FinancialConcept.OPERATING_PROFIT, 8017L);
        registerFields(map, 1, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION_DEPLETION, 8011L, 9022L);
        registerFields(map, 1, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, 8012L, 9023L, 10023L);
        registerFields(map, 1, FinancialConcept.INCOME_DEPLETION, 8013L);
        registerFields(map, 1, FinancialConcept.INCOME_DEPRECIATION, 10024L);
        registerFields(map, 1, FinancialConcept.INCOME_AMORTIZATION, 10025L);
        registerFields(map, 1, FinancialConcept.NONOPERATING_INTEREST_EXPENSE, 8020L, 10028L);
        registerFields(map, 1, FinancialConcept.INTEREST_EXPENSE_AS_REPORTED, 9005L);
        registerFields(map, 1, FinancialConcept.PRETAX_PROFIT, 8034L, 9037L, 10038L);
        registerFields(map, 1, FinancialConcept.INCOME_TAX_EXPENSE, 8035L, 9038L, 10039L);
        registerFields(map, 1, FinancialConcept.NET_PROFIT, 8037L, 9040L, 10041L);
        registerFields(map, 1, FinancialConcept.NET_INCOME_PARENT, 8043L, 9046L, 10047L);
        registerFields(map, 1, FinancialConcept.PREFERRED_DIVIDENDS, 8044L, 9047L, 10048L);
        registerFields(map, 1, FinancialConcept.OTHER_PREFERRED_DIVIDENDS, 8045L, 9048L, 10049L);
        registerFields(map, 1, FinancialConcept.NET_INCOME_COMMON, 8046L, 9049L, 10050L);
        registerFields(map, 1, FinancialConcept.BASIC_EPS, 8047L, 9050L, 10051L);
        registerFields(map, 1, FinancialConcept.DILUTED_EPS, 8048L, 9051L, 10052L);

        // 2: 資產負債表（注意 fieldId 與損益表／現金流量表會重號，必須搭配 statementType）
        registerFields(map, 2, FinancialConcept.TOTAL_ASSETS, 8001L, 9001L, 10001L);
        registerFields(map, 2, FinancialConcept.CASH_AND_SHORT_TERM_INVESTMENTS, 8003L);
        registerFields(map, 2, FinancialConcept.CASH_AND_FEDERAL_FUNDS_SOLD, 9002L);
        registerFields(map, 2, FinancialConcept.CASH_AND_EQUIVALENTS, 8004L, 9003L, 10002L);
        registerFields(map, 2, FinancialConcept.SHORT_TERM_INVESTMENTS, 8005L, 9010L, 10005L);
        registerFields(map, 2, FinancialConcept.MONEY_MARKET_INVESTMENTS, 9004L);
        registerFields(map, 2, FinancialConcept.RESTRICTED_CASH, 8018L);
        registerFields(map, 2, FinancialConcept.RESTRICTED_CASH_AND_INVESTMENTS, 9005L);
        registerFields(map, 2, FinancialConcept.RESTRICTED_CASH_AND_EQUIVALENTS, 10003L);
        registerFields(map, 2, FinancialConcept.TOTAL_LIABILITIES, 8048L, 9040L, 10037L);
        registerFields(map, 2, FinancialConcept.BANK_DEPOSITS, 9041L, 10039L);
        registerFields(map, 2, FinancialConcept.FEDERAL_FUNDS_AND_REPO_LIABILITIES, 9042L);
        registerFields(map, 2, FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, 10058L);
        registerFields(map, 2, FinancialConcept.CURRENT_DEBT_AND_LEASES, 8057L, 9044L, 10041L);
        registerFields(map, 2, FinancialConcept.CURRENT_DEBT, 8058L, 9045L, 10042L);
        registerFields(map, 2, FinancialConcept.CURRENT_CAPITAL_LEASES, 8060L, 9046L, 10043L);
        registerFields(map, 2, FinancialConcept.LONG_TERM_DEBT_AND_LEASES, 8068L, 9059L, 10053L);
        registerFields(map, 2, FinancialConcept.LONG_TERM_DEBT, 8069L, 9060L, 10054L);
        registerFields(map, 2, FinancialConcept.LONG_TERM_CAPITAL_LEASES, 8070L, 9061L, 10055L);
        registerFields(map, 2, FinancialConcept.TOTAL_EQUITY, 8081L, 9069L, 10064L);
        registerFields(map, 2, FinancialConcept.STOCKHOLDERS_EQUITY, 8085L, 9070L, 10065L);
        registerFields(map, 2, FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, 8087L, 9072L, 10067L);
        registerFields(map, 2, FinancialConcept.PREFERRED_STOCK_CAPITAL, 8088L, 9073L, 10068L);
        registerFields(map, 2, FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED, 8090L, 9075L, 10070L);
        registerFields(map, 2, FinancialConcept.RETAINED_EARNINGS, 8091L, 9076L, 10071L);
        registerFields(map, 2, FinancialConcept.TREASURY_STOCK_AS_REPORTED, 8092L, 9077L, 10072L);
        registerFields(map, 2, FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED, 8093L, 9078L, 10073L);
        registerFields(map, 2, FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, 8095L, 9080L, 10075L);

        // 3: 現金流量表
        registerFields(map, 3, FinancialConcept.OPERATING_CASH_FLOW, 8015L, 9016L, 10011L);
        registerFields(map, 3, FinancialConcept.CONTINUING_OPERATING_CASH_FLOW, 8016L, 9017L, 10012L);
        registerFields(map, 3, FinancialConcept.CASH_FLOW_DEPRECIATION_AMORTIZATION_DEPLETION, 8019L, 9020L);
        registerFields(map, 3, FinancialConcept.CAPITAL_EXPENDITURE, 8044L, 9049L, 10050L);
        registerFields(map, 3, FinancialConcept.NET_PPE_CASH_FLOW, 8046L, 9050L, 10051L);
        registerFields(map, 3, FinancialConcept.NET_INTANGIBLES_CASH_FLOW, 8047L, 9051L, 10052L);
        registerFields(map, 3, FinancialConcept.FREE_CASH_FLOW, 8072L, 9078L, 10078L);
        return Map.copyOf(map);
    }

    private static Map<ItemKey, FinancialConcept> buildStaticNameConcepts() {
        Map<ItemKey, FinancialConcept> map = new HashMap<>();
        registerNames(map, 1, FinancialConcept.REVENUE_AS_REPORTED, "Total Revenue as Reported");
        registerNames(map, 1, FinancialConcept.OPERATING_REVENUE, "Total Operating Revenue");
        registerNames(map, 1, FinancialConcept.OPERATING_PROFIT, "Operating Profit");
        registerNames(map, 1, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION_DEPLETION, "Depreciation & Amortization & Depletion");
        registerNames(map, 1, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, "-Depreciation & Amortization", "Depreciation & Amortization");
        registerNames(map, 1, FinancialConcept.INCOME_DEPLETION, "-Depletion");
        registerNames(map, 1, FinancialConcept.INCOME_DEPRECIATION, "-Depreciation");
        registerNames(map, 1, FinancialConcept.INCOME_AMORTIZATION, "-Amortization");
        registerNames(map, 1, FinancialConcept.NONOPERATING_INTEREST_EXPENSE, "Non-Operating Interest Expense", "-Interest Expense Non-Operating");
        registerNames(map, 1, FinancialConcept.INTEREST_EXPENSE_AS_REPORTED, "-Interest Expense");
        registerNames(map, 1, FinancialConcept.PRETAX_PROFIT, "Pretax Profit");
        registerNames(map, 1, FinancialConcept.INCOME_TAX_EXPENSE, "Tax");
        registerNames(map, 1, FinancialConcept.NET_PROFIT, "Net Profit");
        registerNames(map, 1, FinancialConcept.NET_INCOME_PARENT, "Net Income to Parent Company");
        registerNames(map, 1, FinancialConcept.PREFERRED_DIVIDENDS, "Preferred Stock Dividends");
        registerNames(map, 1, FinancialConcept.OTHER_PREFERRED_DIVIDENDS, "Other Preferred Stock Dividends");
        registerNames(map, 1, FinancialConcept.NET_INCOME_COMMON, "Net Income to Common Stockholders");
        registerNames(map, 1, FinancialConcept.BASIC_EPS, "Basic EPS");
        registerNames(map, 1, FinancialConcept.DILUTED_EPS, "Diluted EPS");

        registerNames(map, 2, FinancialConcept.TOTAL_ASSETS, "Total Assets");
        registerNames(map, 2, FinancialConcept.CASH_AND_SHORT_TERM_INVESTMENTS, "Cash and Cash Equivalents & Short-Term Investments");
        registerNames(map, 2, FinancialConcept.CASH_AND_FEDERAL_FUNDS_SOLD, "Cash, Cash Equivalents & Federal Funds Sold");
        registerNames(map, 2, FinancialConcept.CASH_AND_EQUIVALENTS, "-Cash and Cash Equivalents", "Cash and Cash Equivalents");
        registerNames(map, 2, FinancialConcept.SHORT_TERM_INVESTMENTS, "-Short Term Investments");
        registerNames(map, 2, FinancialConcept.MONEY_MARKET_INVESTMENTS, "-Money Market Investments");
        registerNames(map, 2, FinancialConcept.RESTRICTED_CASH, "Restricted Cash");
        registerNames(map, 2, FinancialConcept.RESTRICTED_CASH_AND_INVESTMENTS, "-Restricted Cash and Investments");
        registerNames(map, 2, FinancialConcept.RESTRICTED_CASH_AND_EQUIVALENTS, "Restricted Cash and Cash Equivalents");
        registerNames(map, 2, FinancialConcept.TOTAL_LIABILITIES, "Total Liabilities");
        registerNames(map, 2, FinancialConcept.BANK_DEPOSITS, "Total Deposits");
        registerNames(map, 2, FinancialConcept.FEDERAL_FUNDS_AND_REPO_LIABILITIES,
                "Federal Funds Purchased and Securities Sold Under Agreement to Repurchase");
        registerNames(map, 2, FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, "Unrepurchased Pre-Sale Securities");
        registerNames(map, 2, FinancialConcept.CURRENT_DEBT_AND_LEASES, "Short-Term Debt and Capital Lease Obligation");
        registerNames(map, 2, FinancialConcept.CURRENT_DEBT, "-Current Debt");
        registerNames(map, 2, FinancialConcept.CURRENT_CAPITAL_LEASES, "-Current Capital Lease Obligation");
        registerNames(map, 2, FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "Long Term Debt and Capital Lease Obligation");
        registerNames(map, 2, FinancialConcept.LONG_TERM_DEBT, "-Long Term Debt");
        registerNames(map, 2, FinancialConcept.LONG_TERM_CAPITAL_LEASES, "-Long Term Capital Lease Obligation");
        registerNames(map, 2, FinancialConcept.TOTAL_EQUITY, "Total Equity");
        registerNames(map, 2, FinancialConcept.STOCKHOLDERS_EQUITY, "Stockholders' Equity");
        registerNames(map, 2, FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED, "-Common Stock");
        registerNames(map, 2, FinancialConcept.PREFERRED_STOCK_CAPITAL, "-Preferred Stock");
        registerNames(map, 2, FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED, "Additional Paid-In Capital");
        registerNames(map, 2, FinancialConcept.RETAINED_EARNINGS, "Retained Earnings");
        registerNames(map, 2, FinancialConcept.TREASURY_STOCK_AS_REPORTED, "Less: Treasury Stock");
        registerNames(map, 2, FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED,
                "Gains/Losses Not Affecting Retained Earnings", "Other Composite Income");
        registerNames(map, 2, FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "Minority Interests");

        registerNames(map, 3, FinancialConcept.OPERATING_CASH_FLOW, "Operating Cash Flow");
        registerNames(map, 3, FinancialConcept.CONTINUING_OPERATING_CASH_FLOW, "Net cash flow from continuing operations");
        registerNames(map, 3, FinancialConcept.CASH_FLOW_DEPRECIATION_AMORTIZATION_DEPLETION, "Depreciation & Depletion & Amortization");
        registerNames(map, 3, FinancialConcept.CAPITAL_EXPENDITURE, "Capital Expenditure");
        registerNames(map, 3, FinancialConcept.NET_PPE_CASH_FLOW, "Net PPE Purchase and Sale");
        registerNames(map, 3, FinancialConcept.NET_INTANGIBLES_CASH_FLOW, "Net Intangibles Purchase and Sale");
        registerNames(map, 3, FinancialConcept.FREE_CASH_FLOW, "Free Cash Flow");
        for (FinancialConcept concept : FinancialConcept.values()) {
            for (int statementType : new int[]{1, 2, 3}) {
                map.putIfAbsent(new ItemKey(statementType, concept.name()), concept);
            }
        }
        return Map.copyOf(map);
    }

    private static void registerFields(Map<FieldKey, FinancialConcept> map, int statementType, FinancialConcept concept, long... fieldIds) {
        for (long fieldId : fieldIds) {
            if (map.putIfAbsent(new FieldKey(statementType, fieldId), concept) != null)
                throw new IllegalStateException("DUPLICATE_STATIC_FIELD_MAPPING:" + statementType + ":" + fieldId);
        }
    }

    private static void registerNames(Map<ItemKey, FinancialConcept> map, int statementType, FinancialConcept concept, String... names) {
        for (String name : names) {
            if (map.putIfAbsent(new ItemKey(statementType, name), concept) != null)
                throw new IllegalStateException("DUPLICATE_STATIC_NAME_MAPPING:" + statementType + ":" + name);
        }
    }

    public static FinancialConcept resolveStaticConcept(int statementType, long fieldId, String itemName) {
        if (fieldId > 0) {
            FinancialConcept byField = STATIC_FIELD_CONCEPTS.get(new FieldKey(statementType, fieldId));
            if (byField != null) return byField;
        }
        if (itemName != null && !itemName.isBlank()) {
            return STATIC_NAME_CONCEPTS.get(new ItemKey(statementType, itemName.trim()));
        }
        return null;
    }

    /** 依內建多模板靜態規則提取標準概念：優先匹配 (statementType, fieldId)，無對應 fieldId 時 fallback (statementType, itemName)，未使用細項忽略不報警。 */
    public List<ReportValues> extract(Collection<FinancialReport> reports) {
        List<ReportValues> result = new ArrayList<>();
        for (FinancialReport report : reports) {
            Map<FinancialConcept, MetricResult<BigDecimal>> values = new EnumMap<>(FinancialConcept.class);
            for (FinancialReport.Item item : report.getItems()) {
                FinancialConcept concept = resolveStaticConcept(report.getStatementType(), item.fieldId(), item.name());
                if (concept == null) continue;
                MetricResult<BigDecimal> previous = values.get(concept);
                if (previous != null && previous.status() == MetricStatus.UNVERIFIED) continue;
                if (item.value() == null) {
                    values.putIfAbsent(concept, missing("FINANCIAL_INPUT_MISSING"));
                } else if (previous == null || previous.value() == null) {
                    values.put(concept, MetricResult.available(item.value()));
                } else if (previous.value().compareTo(item.value()) != 0) {
                    values.put(concept, unverified("FINANCIAL_CONCEPT_VALUE_CONFLICT"));
                }
            }
            result.add(new ReportValues(report.getFigi(), report.getStatementType(), report.getPeriodEnd(), report.getPeriodType(),
                    report.getFiscalYear(), report.getCurrency(), report.getAccountingStandards(), values, List.of(), List.of()));
        }
        return List.copyOf(result);
    }

    public record ReportValues(String figi, int statementType, LocalDate periodEnd, int periodType, Integer fiscalYear,
                               String currency, String accountingStandards, Map<FinancialConcept, MetricResult<BigDecimal>> values,
                               List<String> unknownNames, List<FinancialReport.Item> pendingItems) {
        public ReportValues {
            values = Map.copyOf(values);
            unknownNames = List.copyOf(unknownNames);
            pendingItems = List.copyOf(pendingItems);
        }

        public List<String> pendingNames() { return pendingItems.stream().map(FinancialReport.Item::name).distinct().toList(); }

        public MetricResult<BigDecimal> value(FinancialConcept concept) {
            return values.getOrDefault(concept, missing("FINANCIAL_INPUT_MISSING"));
        }

    }

    /** 呼叫端須對齊報期、幣別、TTM 與股份基準；市值可來自股價乘股數或同價快照，不反推股數。 */
    public record ValuationInputs(LocalDate valuationDate, BigDecimal closePrice, String quoteCurrency, String reportingCurrency,
                                  BigDecimal marketCapitalization, BigDecimal dilutedEpsTtm, BigDecimal commonEquity,
                                  BigDecimal revenueTtm, BigDecimal freeCashFlowTtm, BigDecimal enterpriseValue,
                                  BigDecimal ebitdaTtm) {}

    public record MarketMultiples(LocalDate valuationDate, MetricResult<BigDecimal> pe, MetricResult<BigDecimal> pb,
                                  MetricResult<BigDecimal> ps, MetricResult<BigDecimal> pfcf,
                                  MetricResult<BigDecimal> evEbitda) {}

    /** 流量按四連季加總；EPS 採單季數值之和，不等於依 TTM 加權股數與稀釋性重算的 GAAP EPS。 */
    public MetricResult<BigDecimal> trailingTwelveMonths(List<ReportValues> reports, String figi, int statementType,
                                                        FinancialConcept concept, LocalDate periodEnd, String currency) {
        boolean eps = concept == FinancialConcept.DILUTED_EPS || concept == FinancialConcept.BASIC_EPS;
        if (!eps && !ADDITIVE_FLOWS.contains(concept)) return unverified("NON_ADDITIVE_FINANCIAL_CONCEPT");
        List<ReportValues> periods = trailingPeriods(reports, figi, statementType, periodEnd);
        // 年末使用直接申報年度值，不把四季 EPS 相加當成精確相同的年度 EPS。
        if (!periods.isEmpty() && periods.getFirst().periodType() == 7) {
            if (periods.size() > 1) return unverified("FINANCIAL_PERIOD_CONFLICT");
            ReportValues row = periods.getFirst();
            return sameCurrency(currency, row.currency()) ? flowInput(row, concept) : missing("REPORTING_CURRENCY_MISMATCH");
        }
        List<ReportValues> quarters = periods;
        if (quarters.size() != 4 || !quarters.getFirst().periodEnd().equals(periodEnd)) return missing("TTM_QUARTERS_MISSING");
        BigDecimal total = BigDecimal.ZERO;
        Integer previousQuarter = null;
        for (ReportValues row : quarters) {
            if (row.fiscalYear() == null) return missing("FISCAL_YEAR_MISSING");
            int quarter = row.fiscalYear() * 4 + row.periodType();
            if (previousQuarter != null && quarter != previousQuarter - 1) return missing("TTM_QUARTERS_MISSING");
            if (!sameCurrency(currency, row.currency())) return missing("REPORTING_CURRENCY_MISMATCH");
            MetricResult<BigDecimal> value = flowInput(row, concept);
            if (value.status() != MetricStatus.AVAILABLE) return value;
            total = total.add(value.value(), MC);
            previousQuarter = quarter;
        }
        return MetricResult.available(total);
    }

    /** 年度優先申報值；四季 EPS 受各季加權股數、稀釋性及四捨五入影響，不保證與年度 EPS 相同。 */
    public List<ReportValues> trailingPeriods(List<ReportValues> reports, String figi, int statementType, LocalDate periodEnd) {
        List<ReportValues> selected = reports.stream().filter(row -> row.figi().equals(figi) && row.statementType() == statementType
                && !row.periodEnd().isAfter(periodEnd)).toList();
        List<ReportValues> annual = selected.stream().filter(row -> row.periodType() == 7 && row.periodEnd().equals(periodEnd)).toList();
        if (!annual.isEmpty()) return annual;
        return selected.stream().filter(row -> row.periodType() >= 1 && row.periodType() <= 4)
                .sorted(Comparator.comparing(ReportValues::periodEnd).reversed()).limit(4).toList();
    }

    public LocalDate trailingBasisFromDate(List<ReportValues> reports, String figi, int statementType, LocalDate periodEnd) {
        return trailingPeriods(reports, figi, statementType, periodEnd).stream().map(ReportValues::periodEnd).min(LocalDate::compareTo).orElse(null);
    }

    /** 正規化財報會計準則：支援 US_GAAP 與 IFRS（含富途對跨國 IFRS 報表標記的 NonUS_GAAP）；空白或未知準則回傳 null。 */
    public static String normalizeAccountingStandard(String accountingStandards) {
        if (accountingStandards == null || accountingStandards.isBlank()) return null;
        String trimmed = accountingStandards.trim();
        if ("US_GAAP".equalsIgnoreCase(trimmed)) return "US_GAAP";
        if ("IFRS".equalsIgnoreCase(trimmed) || "NonUS_GAAP".equalsIgnoreCase(trimmed)) return "IFRS";
        return null;
    }

    /** 優先股帳面值缺失或為 0 時直接採母公司股東權益（缺母公司權益時採總權益減非控權益）；優先股 > 0 時扣除帳面值。 */
    public MetricResult<BigDecimal> commonEquity(ReportValues balance) {
        MetricResult<BigDecimal> equity = balance.value(FinancialConcept.STOCKHOLDERS_EQUITY);
        if (equity.status() == MetricStatus.UNVERIFIED) return equity;
        if (equity.value() == null) {
            MetricResult<BigDecimal> total = balance.value(FinancialConcept.TOTAL_EQUITY);
            MetricResult<BigDecimal> minority = balance.value(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY);
            if (total.status() == MetricStatus.UNVERIFIED) return total;
            if (minority.status() == MetricStatus.UNVERIFIED) return minority;
            if (total.value() == null) return equity;
            BigDecimal minorityVal = minority.value() == null ? BigDecimal.ZERO : minority.value();
            equity = MetricResult.available(total.value().subtract(minorityVal, MC));
        }
        MetricResult<BigDecimal> preferred = balance.value(FinancialConcept.PREFERRED_STOCK_CAPITAL);
        if (preferred.status() == MetricStatus.UNVERIFIED) return preferred;
        if (preferred.value() == null || preferred.value().signum() == 0) return equity;
        if (preferred.value().signum() < 0) return unverified("PREFERRED_EQUITY_NEGATIVE");
        return MetricResult.available(equity.value().subtract(preferred.value(), MC));
    }

    /** 同一財報期間取數：營收缺報告總額時退回營業收入；普通股淨利缺專列時退回母公司淨利扣優先股股利；資本支出正規化為正數規模；損益 D&A 優先採 DD&A 總額或 D&A 合計。 */
    public MetricResult<BigDecimal> flowInput(ReportValues report, FinancialConcept concept) {
        if (concept == FinancialConcept.REVENUE_AS_REPORTED) {
            MetricResult<BigDecimal> reported = report.value(FinancialConcept.REVENUE_AS_REPORTED);
            if (reported.value() != null || reported.status() == MetricStatus.UNVERIFIED) return reported;
            MetricResult<BigDecimal> operating = report.value(FinancialConcept.OPERATING_REVENUE);
            return operating.value() != null || operating.status() == MetricStatus.UNVERIFIED ? operating : reported;
        }
        if (concept == FinancialConcept.NET_INCOME_COMMON) {
            MetricResult<BigDecimal> common = report.value(FinancialConcept.NET_INCOME_COMMON);
            if (common.value() != null || common.status() == MetricStatus.UNVERIFIED) return common;
            MetricResult<BigDecimal> parent = report.value(FinancialConcept.NET_INCOME_PARENT);
            if (parent.status() == MetricStatus.UNVERIFIED || parent.value() == null) return parent.status() == MetricStatus.UNVERIFIED ? parent : common;
            MetricResult<BigDecimal> prefDiv = report.value(FinancialConcept.PREFERRED_DIVIDENDS);
            MetricResult<BigDecimal> otherPrefDiv = report.value(FinancialConcept.OTHER_PREFERRED_DIVIDENDS);
            if (prefDiv.status() == MetricStatus.UNVERIFIED) return prefDiv;
            if (otherPrefDiv.status() == MetricStatus.UNVERIFIED) return otherPrefDiv;
            BigDecimal deduction = (prefDiv.value() != null && prefDiv.value().signum() > 0 ? prefDiv.value() : BigDecimal.ZERO)
                    .add(otherPrefDiv.value() != null && otherPrefDiv.value().signum() > 0 ? otherPrefDiv.value() : BigDecimal.ZERO, MC);
            return MetricResult.available(parent.value().subtract(deduction, MC));
        }
        if (concept == FinancialConcept.OPERATING_CASH_FLOW) {
            MetricResult<BigDecimal> total = report.value(FinancialConcept.OPERATING_CASH_FLOW);
            if (total.value() != null || total.status() == MetricStatus.UNVERIFIED) return total;
            MetricResult<BigDecimal> continuing = report.value(FinancialConcept.CONTINUING_OPERATING_CASH_FLOW);
            return continuing.value() != null || continuing.status() == MetricStatus.UNVERIFIED ? continuing : total;
        }
        if (concept == FinancialConcept.CAPITAL_EXPENDITURE) {
            MetricResult<BigDecimal> capex = report.value(FinancialConcept.CAPITAL_EXPENDITURE);
            if (capex.value() == null) return capex;
            return MetricResult.available(capex.value().abs());
        }
        if (concept != FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION) return report.value(concept);
        MetricResult<BigDecimal> dda = report.value(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION_DEPLETION);
        if (dda.status() == MetricStatus.UNVERIFIED || dda.value() != null) return dda;
        MetricResult<BigDecimal> value = report.value(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION);
        if (value.status() != MetricStatus.INSUFFICIENT_DATA) return value;
        MetricResult<BigDecimal> depreciation = report.value(FinancialConcept.INCOME_DEPRECIATION);
        MetricResult<BigDecimal> amortization = report.value(FinancialConcept.INCOME_AMORTIZATION);
        if (depreciation.status() == MetricStatus.UNVERIFIED) return depreciation;
        if (amortization.status() == MetricStatus.UNVERIFIED) return amortization;
        return depreciation.value() != null && amortization.value() != null
                ? MetricResult.available(depreciation.value().add(amortization.value(), MC)) : value;
    }

    /** 營運口徑 EBITDA 為 TTM 營業利益加同期間 DD&A（損益表缺 D&A 時讀取富途同期間現金流量表 DD&A），支援 US_GAAP 與 IFRS。 */
    public MetricResult<BigDecimal> trailingEbitda(List<ReportValues> reports, ReportValues income) {
        String standard = normalizeAccountingStandard(income.accountingStandards());
        List<ReportValues> incomePeriods = trailingPeriods(reports, income.figi(), 1, income.periodEnd());
        if (standard == null || incomePeriods.isEmpty() || incomePeriods.stream()
                .anyMatch(period -> !standard.equals(normalizeAccountingStandard(period.accountingStandards())))) {
            return unverified("EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        }
        MetricResult<BigDecimal> operatingProfit = trailingTwelveMonths(reports, income.figi(), 1,
                FinancialConcept.OPERATING_PROFIT, income.periodEnd(), income.currency());
        if (operatingProfit.value() == null) return operatingProfit;
        MetricResult<BigDecimal> depreciation = trailingTwelveMonths(reports, income.figi(), 1,
                FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, income.periodEnd(), income.currency());
        if (depreciation.value() == null && depreciation.status() == MetricStatus.INSUFFICIENT_DATA) {
            List<ReportValues> cashFlowPeriods = trailingPeriods(reports, income.figi(), 3, income.periodEnd());
            if (!cashFlowPeriods.isEmpty() && cashFlowPeriods.stream()
                    .allMatch(period -> standard.equals(normalizeAccountingStandard(period.accountingStandards())))) {
                MetricResult<BigDecimal> cashFlowDda = trailingTwelveMonths(reports, income.figi(), 3,
                        FinancialConcept.CASH_FLOW_DEPRECIATION_AMORTIZATION_DEPLETION, income.periodEnd(), income.currency());
                if (cashFlowDda.value() != null || cashFlowDda.status() == MetricStatus.UNVERIFIED) depreciation = cashFlowDda;
            }
        }
        return depreciation.value() == null ? depreciation : MetricResult.available(operatingProfit.value().add(depreciation.value(), MC));
    }

    /** 營運企業 EV：US_GAAP 優先排除營運租賃（缺拆分時退回純借款），IFRS 納入全部租賃負債；優先股與非控權益採帳面近似，缺值視為 0。 */
    public MetricResult<BigDecimal> enterpriseValue(BigDecimal marketCap, ReportValues balance) {
        MetricResult<BigDecimal> adjustment = enterpriseValueAdjustment(balance);
        if (adjustment.status() != MetricStatus.AVAILABLE || adjustment.value() == null) return adjustment;
        if (marketCap == null) return missing("VALUATION_DATE_SHARES_MISSING");
        return MetricResult.available(marketCap.add(adjustment.value(), MC));
    }

    /** 營運企業 EV 非市值調整淨額（有息債務＋優先股帳面值＋非控權益帳面值－現金），供財季分母表與即時估值共用。 */
    public MetricResult<BigDecimal> enterpriseValueAdjustment(ReportValues balance) {
        String standard = normalizeAccountingStandard(balance.accountingStandards());
        if (standard == null) return unverified("EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        // 存款、回購與證券借貸屬於金融機構負債結構，不套用一般營運企業 EV 公式。
        for (FinancialConcept financing : List.of(FinancialConcept.FEDERAL_FUNDS_AND_REPO_LIABILITIES,
                FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, FinancialConcept.BANK_DEPOSITS)) {
            MetricResult<BigDecimal> value = balance.value(financing);
            if (value.status() == MetricStatus.UNVERIFIED) return value;
            if (value.value() != null && value.value().signum() != 0) return missing("EV_FINANCING_SCOPE_UNRESOLVED");
        }
        MetricResult<BigDecimal> preferred = preferredEquity(balance);
        if (preferred.status() == MetricStatus.UNVERIFIED) return preferred;
        BigDecimal preferredVal = preferred.value() == null ? BigDecimal.ZERO : preferred.value();

        MetricResult<BigDecimal> minority = noncontrollingInterest(balance);
        if (minority.status() == MetricStatus.UNVERIFIED) return minority;
        BigDecimal minorityVal = minority.value() == null ? BigDecimal.ZERO : minority.value();

        MetricResult<BigDecimal> debt = totalDebt(balance, "IFRS".equals(standard));
        if (debt.status() == MetricStatus.UNVERIFIED) return debt;
        MetricResult<BigDecimal> cash = balance.value(FinancialConcept.CASH_AND_EQUIVALENTS);
        if (cash.status() == MetricStatus.UNVERIFIED) return cash;
        if (debt.value() == null) return debt;
        if (cash.value() == null) return cash;
        return MetricResult.available(debt.value().add(preferredVal, MC).add(minorityVal, MC).subtract(cash.value(), MC));
    }

    /** 按資產負債表準則計算期末有息債務（US_GAAP 排除營運租賃或採純借款；IFRS 納入全部租賃負債）。 */
    public MetricResult<BigDecimal> totalDebt(ReportValues balance) {
        String standard = normalizeAccountingStandard(balance.accountingStandards());
        if (standard == null) return unverified("EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        for (FinancialConcept financing : List.of(FinancialConcept.FEDERAL_FUNDS_AND_REPO_LIABILITIES,
                FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, FinancialConcept.BANK_DEPOSITS)) {
            MetricResult<BigDecimal> value = balance.value(financing);
            if (value.status() == MetricStatus.UNVERIFIED) return value;
            if (value.value() != null && value.value().signum() != 0) return missing("EV_FINANCING_SCOPE_UNRESOLVED");
        }
        return totalDebt(balance, "IFRS".equals(standard));
    }

    public MetricResult<BigDecimal> preferredEquity(ReportValues balance) {
        MetricResult<BigDecimal> preferred = balance.value(FinancialConcept.PREFERRED_STOCK_CAPITAL);
        if (preferred.status() == MetricStatus.UNVERIFIED) return preferred;
        if (preferred.value() != null && preferred.value().signum() < 0) return unverified("PREFERRED_EQUITY_NEGATIVE");
        if (preferred.value() != null) return preferred;
        return hasBalanceAnchor(balance) ? MetricResult.available(BigDecimal.ZERO) : preferred;
    }

    public MetricResult<BigDecimal> noncontrollingInterest(ReportValues balance) {
        MetricResult<BigDecimal> minority = balance.value(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY);
        if (minority.status() == MetricStatus.UNVERIFIED || minority.value() != null) return minority;
        MetricResult<BigDecimal> total = balance.value(FinancialConcept.TOTAL_EQUITY);
        MetricResult<BigDecimal> parent = balance.value(FinancialConcept.STOCKHOLDERS_EQUITY);
        if (total.status() == MetricStatus.UNVERIFIED) return total;
        if (parent.status() == MetricStatus.UNVERIFIED) return parent;
        if (total.value() != null && parent.value() != null) {
            return MetricResult.available(total.value().subtract(parent.value(), MC));
        }
        return hasBalanceAnchor(balance) ? MetricResult.available(BigDecimal.ZERO) : minority;
    }

    private MetricResult<BigDecimal> totalDebt(ReportValues balance, boolean ifrs) {
        boolean anchored = hasBalanceAnchor(balance);
        if (ifrs) {
            MetricResult<BigDecimal> current = ifrsDebt(balance, FinancialConcept.CURRENT_DEBT_AND_LEASES,
                    FinancialConcept.CURRENT_DEBT, FinancialConcept.CURRENT_CAPITAL_LEASES,
                    FinancialConcept.CURRENT_FINANCE_LEASES, FinancialConcept.CURRENT_OPERATING_LEASES, anchored);
            MetricResult<BigDecimal> longTerm = ifrsDebt(balance, FinancialConcept.LONG_TERM_DEBT_AND_LEASES,
                    FinancialConcept.LONG_TERM_DEBT, FinancialConcept.LONG_TERM_CAPITAL_LEASES,
                    FinancialConcept.LONG_TERM_FINANCE_LEASES, FinancialConcept.LONG_TERM_OPERATING_LEASES, anchored);
            if (current.status() == MetricStatus.UNVERIFIED) return current;
            if (longTerm.status() == MetricStatus.UNVERIFIED) return longTerm;
            if (current.value() == null) return current;
            if (longTerm.value() == null) return longTerm;
            return MetricResult.available(current.value().add(longTerm.value(), MC));
        }
        MetricResult<BigDecimal> finance = balance.value(FinancialConcept.TOTAL_FINANCE_LEASES);
        MetricResult<BigDecimal> borrowingCurrent = balance.value(FinancialConcept.CURRENT_DEBT);
        MetricResult<BigDecimal> borrowingLongTerm = balance.value(FinancialConcept.LONG_TERM_DEBT);
        MetricResult<BigDecimal> combinedCurrent = balance.value(FinancialConcept.CURRENT_DEBT_AND_LEASES);
        MetricResult<BigDecimal> combinedLongTerm = balance.value(FinancialConcept.LONG_TERM_DEBT_AND_LEASES);
        if (finance.status() == MetricStatus.UNVERIFIED) return finance;
        if (borrowingCurrent.status() == MetricStatus.UNVERIFIED) return borrowingCurrent;
        if (borrowingLongTerm.status() == MetricStatus.UNVERIFIED) return borrowingLongTerm;
        if (combinedCurrent.status() == MetricStatus.UNVERIFIED) return combinedCurrent;
        if (combinedLongTerm.status() == MetricStatus.UNVERIFIED) return combinedLongTerm;
        boolean currentPureResolved = borrowingCurrent.value() != null || (combinedCurrent.value() == null && anchored);
        boolean longTermPureResolved = borrowingLongTerm.value() != null || (combinedLongTerm.value() == null && anchored);
        if (finance.value() != null && finance.value().signum() >= 0 && currentPureResolved && longTermPureResolved) {
            BigDecimal cur = borrowingCurrent.value() == null ? BigDecimal.ZERO : borrowingCurrent.value();
            BigDecimal lt = borrowingLongTerm.value() == null ? BigDecimal.ZERO : borrowingLongTerm.value();
            return MetricResult.available(cur.add(lt, MC).add(finance.value(), MC));
        }
        MetricResult<BigDecimal> current = usGaapDebt(balance, FinancialConcept.CURRENT_DEBT_AND_LEASES,
                FinancialConcept.CURRENT_DEBT, FinancialConcept.CURRENT_CAPITAL_LEASES, anchored);
        MetricResult<BigDecimal> longTerm = usGaapDebt(balance, FinancialConcept.LONG_TERM_DEBT_AND_LEASES,
                FinancialConcept.LONG_TERM_DEBT, FinancialConcept.LONG_TERM_CAPITAL_LEASES, anchored);
        if (current.status() == MetricStatus.UNVERIFIED) return current;
        if (longTerm.status() == MetricStatus.UNVERIFIED) return longTerm;
        if (current.value() == null) return current;
        if (longTerm.value() == null) return longTerm;
        return MetricResult.available(current.value().add(longTerm.value(), MC));
    }

    private boolean hasBalanceAnchor(ReportValues balance) {
        for (FinancialConcept anchor : List.of(FinancialConcept.TOTAL_ASSETS, FinancialConcept.TOTAL_LIABILITIES,
                FinancialConcept.STOCKHOLDERS_EQUITY, FinancialConcept.TOTAL_EQUITY, FinancialConcept.CASH_AND_EQUIVALENTS,
                FinancialConcept.CURRENT_DEBT_AND_LEASES, FinancialConcept.LONG_TERM_DEBT_AND_LEASES,
                FinancialConcept.CURRENT_DEBT, FinancialConcept.LONG_TERM_DEBT)) {
            if (balance.value(anchor).value() != null) return true;
        }
        return false;
    }

    private MetricResult<BigDecimal> ifrsDebt(ReportValues balance, FinancialConcept aggregate, FinancialConcept debt,
                                              FinancialConcept capitalLeases, FinancialConcept financeLeases,
                                              FinancialConcept operatingLeases, boolean anchored) {
        MetricResult<BigDecimal> combined = balance.value(aggregate);
        if (combined.status() == MetricStatus.UNVERIFIED || combined.value() != null) return combined;
        MetricResult<BigDecimal> borrowing = balance.value(debt);
        MetricResult<BigDecimal> leaseTotal = balance.value(capitalLeases);
        MetricResult<BigDecimal> finance = balance.value(financeLeases);
        MetricResult<BigDecimal> operating = balance.value(operatingLeases);
        for (MetricResult<BigDecimal> item : List.of(borrowing, leaseTotal, finance, operating)) {
            if (item.status() == MetricStatus.UNVERIFIED) return item;
        }
        if (borrowing.value() == null && leaseTotal.value() == null && finance.value() == null && operating.value() == null) {
            return anchored ? MetricResult.available(BigDecimal.ZERO) : combined;
        }
        BigDecimal debtVal = borrowing.value() == null ? BigDecimal.ZERO : borrowing.value();
        BigDecimal leaseVal = leaseTotal.value() != null ? leaseTotal.value()
                : (finance.value() == null ? BigDecimal.ZERO : finance.value())
                        .add(operating.value() == null ? BigDecimal.ZERO : operating.value(), MC);
        return MetricResult.available(debtVal.add(leaseVal, MC));
    }

    private MetricResult<BigDecimal> usGaapDebt(ReportValues balance, FinancialConcept aggregate, FinancialConcept debt,
                                                FinancialConcept leases, boolean anchored) {
        MetricResult<BigDecimal> combined = balance.value(aggregate);
        boolean current = debt == FinancialConcept.CURRENT_DEBT;
        MetricResult<BigDecimal> operating = balance.value(current ? FinancialConcept.CURRENT_OPERATING_LEASES : FinancialConcept.LONG_TERM_OPERATING_LEASES);
        MetricResult<BigDecimal> finance = balance.value(current ? FinancialConcept.CURRENT_FINANCE_LEASES : FinancialConcept.LONG_TERM_FINANCE_LEASES);
        MetricResult<BigDecimal> borrowing = balance.value(debt);
        MetricResult<BigDecimal> lease = balance.value(leases);
        for (MetricResult<BigDecimal> item : List.of(combined, operating, finance, borrowing, lease)) {
            if (item.status() == MetricStatus.UNVERIFIED) return item;
        }
        if (lease.value() != null && lease.value().signum() < 0) return unverified("EV_LEASE_SCOPE_UNRESOLVED");
        if (borrowing.value() != null && finance.value() != null)
            return MetricResult.available(borrowing.value().add(finance.value(), MC));
        if (combined.value() != null && operating.value() != null && combined.value().compareTo(operating.value()) >= 0) {
            BigDecimal net = combined.value().subtract(operating.value(), MC);
            if (borrowing.value() == null || net.compareTo(borrowing.value()) >= 0) return MetricResult.available(net);
        }
        if (lease.value() != null && lease.value().signum() == 0) {
            if (combined.value() != null) return combined;
            if (borrowing.value() != null) return borrowing;
            return anchored ? MetricResult.available(BigDecimal.ZERO) : combined;
        }
        // 缺租賃拆分明細時，優先採不含租賃之純借款；若僅列總額且等於／大於租賃項（如無借款僅有租賃），扣除租賃得純借款。
        if (borrowing.value() != null) return borrowing;
        if (combined.value() != null && lease.value() != null && combined.value().compareTo(lease.value()) >= 0) {
            return MetricResult.available(combined.value().subtract(lease.value(), MC));
        }
        if (combined.value() == null && lease.value() == null) {
            if (finance.value() != null) return finance;
            return anchored ? MetricResult.available(BigDecimal.ZERO) : borrowing;
        }
        return unverified("EV_LEASE_SCOPE_UNRESOLVED");
    }

    /** 只計算市場倍數；輸入不足各自留空，不回推股數、不推算未知 EV，也不產生買賣訊號。 */
    public MarketMultiples calculate(ValuationInputs inputs) {
        if (inputs.valuationDate() == null) throw new IllegalArgumentException("valuationDate is required");
        if (!sameCurrency(inputs.quoteCurrency(), inputs.reportingCurrency())) return unavailable(inputs.valuationDate(), "REPORTING_CURRENCY_MISMATCH");
        boolean priceAvailable = inputs.closePrice() != null && inputs.closePrice().signum() > 0;
        BigDecimal marketCap = !priceAvailable || inputs.marketCapitalization() == null || inputs.marketCapitalization().signum() <= 0
                ? null : inputs.marketCapitalization();
        String marketCapMissing = priceAvailable ? "MARKET_CAP_MISSING" : "CLOSING_PRICE_MISSING_OR_INVALID";
        MetricResult<BigDecimal> pe;
        if (!priceAvailable) pe = missing("CLOSING_PRICE_MISSING_OR_INVALID");
        else if (inputs.dilutedEpsTtm() == null) pe = missing("DILUTED_EPS_TTM_MISSING");
        else if (inputs.dilutedEpsTtm().signum() <= 0) pe = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                AnalysisReasonDto.of("NON_POSITIVE_EPS", "VALUATION", "EPS 非正（虧損或零盈利），PE 不適用（N.M.）。"));
        else pe = MetricResult.available(StockIndicatorService.calculatePe(inputs.closePrice(), inputs.dilutedEpsTtm()));
        MetricResult<BigDecimal> pb;
        if (inputs.commonEquity() != null && inputs.commonEquity().signum() <= 0)
            pb = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NON_POSITIVE_COMMON_EQUITY", "VALUATION", "普通股帳面權益非正，P/B 不適用（N.M.）。"));
        else pb = marketCap == null ? missing(marketCapMissing) : divide(marketCap, inputs.commonEquity(), "COMMON_EQUITY_MISSING");
        MetricResult<BigDecimal> ps;
        if (inputs.revenueTtm() != null && inputs.revenueTtm().signum() <= 0)
            ps = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NON_POSITIVE_REVENUE", "VALUATION", "營收非正，P/S 不具可比意義（N.M.）。"));
        else ps = marketCap == null ? missing(marketCapMissing) : divide(marketCap, inputs.revenueTtm(), "REVENUE_TTM_MISSING");
        MetricResult<BigDecimal> pfcf;
        if (inputs.freeCashFlowTtm() != null && inputs.freeCashFlowTtm().signum() <= 0)
            pfcf = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NON_POSITIVE_FCF", "VALUATION", "FCF 非正，P/FCF 不具可比意義（N.M.）。"));
        else pfcf = marketCap == null ? missing(marketCapMissing) : divide(marketCap, inputs.freeCashFlowTtm(), "FREE_CASH_FLOW_TTM_MISSING");
        MetricResult<BigDecimal> evEbitda;
        if (inputs.ebitdaTtm() != null && inputs.ebitdaTtm().signum() <= 0)
            evEbitda = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NON_POSITIVE_EBITDA", "VALUATION", "EBITDA 非正，EV/EBITDA 不具可比意義（N.M.）。"));
        else evEbitda = inputs.enterpriseValue() == null ? missing("ENTERPRISE_VALUE_INPUTS_UNRESOLVED")
                : divide(inputs.enterpriseValue(), inputs.ebitdaTtm(), "EBITDA_TTM_MISSING");
        return new MarketMultiples(inputs.valuationDate(), pe, pb, ps, pfcf, evEbitda);
    }

    private MarketMultiples unavailable(LocalDate date, String reason) {
        MetricResult<BigDecimal> value = missing(reason);
        return new MarketMultiples(date, value, value, value, value, value);
    }

    private MetricResult<BigDecimal> divide(BigDecimal numerator, BigDecimal denominator, String missingReason) {
        if (denominator == null) return missing(missingReason);
        if (denominator.signum() == 0) return missing("VALUATION_DENOMINATOR_ZERO");
        return MetricResult.available(numerator.divide(denominator, 4, RoundingMode.HALF_UP));
    }

    private static boolean sameCurrency(String first, String second) {
        return first != null && !first.isBlank() && second != null && first.equalsIgnoreCase(second);
    }

    private static MetricResult<BigDecimal> missing(String reason) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(reason, "VALUATION", "缺少已核實的估值輸入。"));
    }

    private static MetricResult<BigDecimal> unverified(String reason) {
        return MetricResult.unavailable(MetricStatus.UNVERIFIED, AnalysisReasonDto.of(reason, "VALUATION", "來源範圍或組合方式尚未確認。"));
    }
}
