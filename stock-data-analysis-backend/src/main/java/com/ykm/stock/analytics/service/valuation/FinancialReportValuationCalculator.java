package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.enums.FinancialItemMappingStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.FinancialReportItemMapping;
import com.ykm.stock.analytics.service.stock.StockIndicatorService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
            FinancialConcept.INCOME_AMORTIZATION, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION);

    private record ItemKey(int statementType, String name) {}

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

    /** 一次建立名稱索引；同概念別名不加總，不同報表類型不繼承映射。 */
    public List<ReportValues> extract(Collection<FinancialReport> reports, Collection<FinancialReportItemMapping> mappings) {
        Map<ItemKey, FinancialReportItemMapping> catalog = new HashMap<>();
        for (FinancialReportItemMapping mapping : mappings) {
            ItemKey key = new ItemKey(mapping.getStatementType(), mapping.getItemName());
            if (catalog.putIfAbsent(key, mapping) != null) throw new IllegalArgumentException("DUPLICATE_FINANCIAL_MAPPING:" + key);
        }
        List<ReportValues> result = new ArrayList<>();
        for (FinancialReport report : reports) {
            Map<FinancialConcept, MetricResult<BigDecimal>> values = new EnumMap<>(FinancialConcept.class);
            Set<String> unknown = new LinkedHashSet<>();
            List<FinancialReport.Item> pending = new ArrayList<>();
            for (FinancialReport.Item item : report.getItems()) {
                FinancialReportItemMapping mapping = catalog.get(new ItemKey(report.getStatementType(), item.name()));
                if (mapping == null) {
                    unknown.add(item.name() == null || item.name().isBlank() ? "<missing name>:" + item.fieldId() : item.name());
                    continue;
                }
                if (mapping.getMappingStatus() == FinancialItemMappingStatus.PENDING) pending.add(item);
                if (mapping.getMappingStatus() != FinancialItemMappingStatus.MAPPED) continue;
                FinancialConcept concept = mapping.getFinancialConcept();
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
                    report.getFiscalYear(), report.getCurrency(), report.getAccountingStandards(), values, List.copyOf(unknown), List.copyOf(pending)));
        }
        return List.copyOf(result);
    }

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

    /** 已列示優先股須扣除；未列示時，僅在完整普通股組成與母公司權益核對相符才採用。 */
    public MetricResult<BigDecimal> commonEquity(ReportValues balance) {
        MetricResult<BigDecimal> equity = balance.value(FinancialConcept.STOCKHOLDERS_EQUITY);
        MetricResult<BigDecimal> preferred = balance.value(FinancialConcept.PREFERRED_STOCK_CAPITAL);
        if (equity.value() == null) return equity;
        if (preferred.status() == MetricStatus.UNVERIFIED) return preferred;
        if (preferred.value() == null) {
            if (balance.values().containsKey(FinancialConcept.PREFERRED_STOCK_CAPITAL)) return missing("PREFERRED_EQUITY_BOOK_VALUE_MISSING");
            BigDecimal common = BigDecimal.ZERO;
            for (FinancialConcept component : List.of(FinancialConcept.COMMON_STOCK_CAPITAL_AS_REPORTED,
                    FinancialConcept.RETAINED_EARNINGS, FinancialConcept.OTHER_COMPREHENSIVE_EQUITY_AS_REPORTED)) {
                MetricResult<BigDecimal> value = balance.value(component);
                if (value.status() == MetricStatus.UNVERIFIED) return value;
                if (value.value() == null) return missing("PREFERRED_EQUITY_BOOK_VALUE_MISSING");
                common = common.add(value.value(), MC);
            }
            // 普通股股本可能已包含 APIC；原組合能對平時不再次加上資本公積或扣除庫藏股。
            if (common.compareTo(equity.value()) == 0) return equity;
            MetricResult<BigDecimal> paidIn = balance.value(FinancialConcept.ADDITIONAL_PAID_IN_CAPITAL_AS_REPORTED);
            MetricResult<BigDecimal> treasury = balance.value(FinancialConcept.TREASURY_STOCK_AS_REPORTED);
            if (paidIn.status() == MetricStatus.UNVERIFIED) return paidIn;
            if (treasury.status() == MetricStatus.UNVERIFIED) return treasury;
            if (paidIn.value() != null) common = common.add(paidIn.value(), MC);
            // 「Less: Treasury Stock」為權益抵減項，來源金額可能已帶負號。
            if (treasury.value() != null) common = common.subtract(treasury.value().abs(), MC);
            return common.compareTo(equity.value()) == 0 ? equity : unverified("COMMON_EQUITY_COMPONENTS_MISMATCH");
        }
        return MetricResult.available(equity.value().subtract(preferred.value(), MC));
    }

    /** 同一財報期間優先採純折舊攤銷合計，缺合計才加總該期的純折舊及純攤銷。 */
    public MetricResult<BigDecimal> flowInput(ReportValues report, FinancialConcept concept) {
        MetricResult<BigDecimal> value = report.value(concept);
        if (concept != FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION || value.status() != MetricStatus.INSUFFICIENT_DATA) return value;
        MetricResult<BigDecimal> depreciation = report.value(FinancialConcept.INCOME_DEPRECIATION);
        MetricResult<BigDecimal> amortization = report.value(FinancialConcept.INCOME_AMORTIZATION);
        return depreciation.value() != null && amortization.value() != null
                ? MetricResult.available(depreciation.value().add(amortization.value(), MC)) : value;
    }

    /** 營運口徑 EBITDA 為 TTM 營業利益加同期間純折舊攤銷。 */
    public MetricResult<BigDecimal> trailingEbitda(List<ReportValues> reports, ReportValues income) {
        if (trailingPeriods(reports, income.figi(), 1, income.periodEnd()).stream()
                .anyMatch(period -> !"US_GAAP".equals(period.accountingStandards()))) return unverified("EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        MetricResult<BigDecimal> operatingProfit = trailingTwelveMonths(reports, income.figi(), 1,
                FinancialConcept.OPERATING_PROFIT, income.periodEnd(), income.currency());
        if (operatingProfit.value() == null) return operatingProfit;
        MetricResult<BigDecimal> depreciation = trailingTwelveMonths(reports, income.figi(), 1,
                FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, income.periodEnd(), income.currency());
        return depreciation.value() == null ? depreciation : MetricResult.available(operatingProfit.value().add(depreciation.value(), MC));
    }

    /** US GAAP 現金口徑 EV，排除營運租賃，非控權益採帳面估算；未識別投資扣項不盲扣。 */
    public MetricResult<BigDecimal> enterpriseValue(BigDecimal marketCap, ReportValues balance) {
        if (!"US_GAAP".equals(balance.accountingStandards())) return unverified("EV_ACCOUNTING_STANDARD_UNCONFIRMED");
        // 此混合標籤實際含權益外可贖回 NCI；未確認資本歸屬及市值，不可遺漏或直接加上帳面值。
        for (FinancialReport.Item item : balance.pendingItems()) {
            if ("Convertible preferred securities".equalsIgnoreCase(item.name()) && (item.value() == null || item.value().signum() != 0))
                return missing("EV_FINANCING_SCOPE_UNRESOLVED");
        }
        // 存款、回購與證券借貸確實屬於待拆分的融資；泛用其他負債項目不據此判定。
        for (FinancialConcept financing : List.of(FinancialConcept.FEDERAL_FUNDS_AND_REPO_LIABILITIES,
                FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, FinancialConcept.BANK_DEPOSITS)) {
            MetricResult<BigDecimal> value = balance.value(financing);
            if (value.status() == MetricStatus.UNVERIFIED) return value;
            if (value.value() != null && value.value().signum() != 0) return missing("EV_FINANCING_SCOPE_UNRESOLVED");
        }
        if (balance.pendingNames().stream().anyMatch(name -> name.equalsIgnoreCase("Derivative liabilities")))
            return missing("EV_FINANCING_SCOPE_UNRESOLVED");
        MetricResult<BigDecimal> preferred = balance.value(FinancialConcept.PREFERRED_STOCK_CAPITAL);
        if (preferred.status() == MetricStatus.UNVERIFIED) return preferred;
        if (preferred.value() != null && preferred.value().signum() != 0) return missing("PREFERRED_EQUITY_MARKET_VALUE_MISSING");
        MetricResult<BigDecimal> debt = totalDebt(balance);
        if (debt.status() == MetricStatus.UNVERIFIED) return debt;
        if (preferred.value() == null) return missing("PREFERRED_EQUITY_MARKET_VALUE_UNRESOLVED");
        MetricResult<BigDecimal> minority = balance.value(FinancialConcept.NONCONTROLLING_INTEREST_EQUITY);
        if (minority.status() == MetricStatus.UNVERIFIED) return minority;
        if (minority.value() == null && minority.status() == MetricStatus.INSUFFICIENT_DATA) {
            MetricResult<BigDecimal> total = balance.value(FinancialConcept.TOTAL_EQUITY);
            MetricResult<BigDecimal> parent = balance.value(FinancialConcept.STOCKHOLDERS_EQUITY);
            if (total.status() == MetricStatus.UNVERIFIED) return total;
            if (parent.status() == MetricStatus.UNVERIFIED) return parent;
            if (total.value() != null && parent.value() != null) minority = MetricResult.available(total.value().subtract(parent.value(), MC));
        }
        if (minority.value() == null) return missing("NONCONTROLLING_INTEREST_BOOK_VALUE_MISSING");
        MetricResult<BigDecimal> cash = balance.value(FinancialConcept.CASH_AND_EQUIVALENTS);
        if (cash.status() == MetricStatus.UNVERIFIED) return cash;
        if (marketCap == null) return missing("VALUATION_DATE_SHARES_MISSING");
        if (debt.value() == null) return debt;
        if (cash.value() == null) return cash;
        return MetricResult.available(marketCap.add(debt.value(), MC).add(minority.value(), MC).subtract(cash.value(), MC));
    }

    private MetricResult<BigDecimal> totalDebt(ReportValues balance) {
        MetricResult<BigDecimal> finance = balance.value(FinancialConcept.TOTAL_FINANCE_LEASES);
        MetricResult<BigDecimal> borrowingCurrent = balance.value(FinancialConcept.CURRENT_DEBT);
        MetricResult<BigDecimal> borrowingLongTerm = balance.value(FinancialConcept.LONG_TERM_DEBT);
        if (finance.value() != null && finance.value().signum() >= 0 && borrowingCurrent.value() != null && borrowingLongTerm.value() != null)
            return MetricResult.available(borrowingCurrent.value().add(borrowingLongTerm.value(), MC).add(finance.value(), MC));
        MetricResult<BigDecimal> current = debt(balance, FinancialConcept.CURRENT_DEBT_AND_LEASES, FinancialConcept.CURRENT_DEBT, FinancialConcept.CURRENT_CAPITAL_LEASES);
        MetricResult<BigDecimal> longTerm = debt(balance, FinancialConcept.LONG_TERM_DEBT_AND_LEASES, FinancialConcept.LONG_TERM_DEBT, FinancialConcept.LONG_TERM_CAPITAL_LEASES);
        if (current.status() == MetricStatus.UNVERIFIED) return current;
        if (longTerm.status() == MetricStatus.UNVERIFIED) return longTerm;
        if (current.value() == null) return current;
        if (longTerm.value() == null) return longTerm;
        return MetricResult.available(current.value().add(longTerm.value(), MC));
    }

    private MetricResult<BigDecimal> debt(ReportValues balance, FinancialConcept aggregate, FinancialConcept debt, FinancialConcept leases) {
        MetricResult<BigDecimal> combined = balance.value(aggregate);
        if (combined.status() == MetricStatus.UNVERIFIED) return combined;
        boolean current = debt == FinancialConcept.CURRENT_DEBT;
        MetricResult<BigDecimal> operating = balance.value(current ? FinancialConcept.CURRENT_OPERATING_LEASES : FinancialConcept.LONG_TERM_OPERATING_LEASES);
        MetricResult<BigDecimal> finance = balance.value(current ? FinancialConcept.CURRENT_FINANCE_LEASES : FinancialConcept.LONG_TERM_FINANCE_LEASES);
        MetricResult<BigDecimal> borrowing = balance.value(debt);
        if (borrowing.value() != null && finance.value() != null)
            return MetricResult.available(borrowing.value().add(finance.value(), MC));
        if (combined.value() != null && operating.value() != null && combined.value().compareTo(operating.value()) >= 0) {
            BigDecimal net = combined.value().subtract(operating.value(), MC);
            if (borrowing.value() == null || net.compareTo(borrowing.value()) >= 0) return MetricResult.available(net);
        }
        MetricResult<BigDecimal> lease = balance.value(leases);
        if (lease.status() == MetricStatus.UNVERIFIED) return lease;
        // Futu 的 Capital Lease 樣本實為融資＋營運租賃；缺乏分拆時不能全加，也不能只取 Debt 漏掉融資租賃。
        if (lease.value() == null || lease.value().signum() != 0) return unverified("EV_LEASE_SCOPE_UNRESOLVED");
        return combined.value() != null ? combined : balance.value(debt);
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
        return MetricResult.available(numerator.divide(denominator, MC));
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
