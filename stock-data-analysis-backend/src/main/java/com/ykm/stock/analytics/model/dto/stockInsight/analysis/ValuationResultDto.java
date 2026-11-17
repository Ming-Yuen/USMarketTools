package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import static com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator.DEPRECIATION_SCOPE_UNRESOLVED;

public record ValuationResultDto(String figi, LocalDate valuationDate, Instant analysisAsOf, String source, String industryCode,
                                 String status, String reasonCode, Metrics metrics, BigDecimal price, String currency, Instant calculatedAt,
                                 Instant financialObservedAt, Instant sharesObservedAt, String marketCapSource, LocalDate secFinancialAvailableFromDate) {
    private static final Set<String> UNVERIFIED_REASONS = Set.of("FINANCIAL_CONCEPT_VALUE_CONFLICT", "FINANCIAL_PERIOD_CONFLICT", "COMMON_EQUITY_COMPONENTS_MISMATCH",
            "EPS_SPLIT_BASIS_UNCONFIRMED", "NON_ADDITIVE_FINANCIAL_CONCEPT", "UNMAPPED_FINANCIAL_ITEM", "EV_FINANCING_SCOPE_UNRESOLVED",
            "EV_LEASE_SCOPE_UNRESOLVED", "EV_ACCOUNTING_STANDARD_UNCONFIRMED", "EBITDA_FINANCE_COST_SCOPE_UNRESOLVED",
            "SEC_REVENUE_TOTAL_SCOPE_UNVERIFIED", "SEC_REVENUE_SCOPE_CONFLICT", "SEC_FACT_VERSION_CONFLICT", "SEC_TTM_PERIOD_CONFLICT",
            "SEC_SHARE_CLASS_EPS_UNVERIFIED", "ISSUER_MARKET_CAP_UNRESOLVED", "SEC_SHARE_SPLIT_BASIS_UNCONFIRMED", "SEC_FCF_PERIOD_MISMATCH", "SEC_FCF_SCOPE_CONFLICT",
            "SEC_REVENUE_NET_SCOPE_UNVERIFIED", "SEC_CAPITAL_EXPENDITURE_SIGN_UNVERIFIED",
            "SEC_COMMON_EQUITY_UNRESOLVED", "SEC_EV_COMPONENTS_UNRESOLVED", "SEC_EBITDA_COMPONENTS_UNRESOLVED",
            DEPRECIATION_SCOPE_UNRESOLVED);
    private static final Set<String> NOT_APPLICABLE_REASONS = Set.of("NON_POSITIVE_FCF", "NON_POSITIVE_EPS", "NON_POSITIVE_EBITDA", "NON_POSITIVE_COMMON_EQUITY",
            "NON_POSITIVE_REVENUE", "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");

    public record Value(BigDecimal value, String status, String reasonCode, String financialSource, LocalDate periodStart, LocalDate periodEnd) {
        public Value(BigDecimal value, String status, String reasonCode) { this(value, status, reasonCode, "FUTU", null, null); }
    }
    public record Metrics(Value pe, Value pb, Value ps, Value pfcf, Value evEbitda) {
        public Metrics(Value pe, Value pb, Value ps) {
            this(pe, pb, ps, value(null, "NATIVE_METRIC_NOT_PROVIDED"), value(null, "NATIVE_METRIC_NOT_PROVIDED"));
        }
    }

    /** 富途歷史 API 的原生三倍數，來源仍標示 FUTU；不可與本地計算值混拼。 */
    public static ValuationResultDto fromFutu(String figi, LocalDate date, Instant observedAt, String industryCode,
                                              BigDecimal pe, BigDecimal pb, BigDecimal ps) {
        boolean empty = pe == null && pb == null && ps == null;
        return new ValuationResultDto(figi, date, observedAt, "FUTU", industryCode,
                empty ? "INSUFFICIENT_DATA" : "AVAILABLE", empty ? "FUTU_VALUATION_FIELDS_MISSING" : null,
                new Metrics(value(pe, "FUTU_VALUATION_FIELD_MISSING"), value(pb, "FUTU_VALUATION_FIELD_MISSING"), value(ps, "FUTU_VALUATION_FIELD_MISSING")),
                null, null, null, null, null, null, null);
    }

    public static ValuationResultDto fromCalculated(StockValuationDaily row, String industryCode) {
        Value pb = row.getPb() != null && row.getPb().signum() <= 0
                ? financialValue(null, "NON_POSITIVE_COMMON_EQUITY", row.getPbFinancialSource(), null, row.getBalancePeriodEnd())
                : financialValue(row.getPb(), row.getPbReason(), row.getPbFinancialSource(), null, row.getBalancePeriodEnd());
        boolean available = row.hasAvailableMetric();
        String source = row.getSecFinancialCik() != null ? "FUTU_AND_SEC_FINANCIAL_FACTS" : "FINANCIAL_REPORT_AS_OBSERVED";
        return new ValuationResultDto(row.getFigi(), row.getValuationDate(), row.getValuationCutoff(), source, industryCode,
                available ? "AVAILABLE" : "INSUFFICIENT_DATA", available ? null : "VALUATION_INPUTS_INCOMPLETE",
                new Metrics(financialValue(row.getPe(), row.getPeReason(), row.getPeFinancialSource(), null, row.getIncomePeriodEnd()),
                        pb,
                        financialValue(row.getPs(), row.getPsReason(), row.getPsFinancialSource(), row.getPsPeriodStart(), row.getPsPeriodEnd()),
                        financialValue(row.getPfcf(), row.getPfcfReason(), row.getPfcfFinancialSource(), row.getPfcfPeriodStart(), row.getPfcfPeriodEnd()),
                        financialValue(row.getEvEbitda(), row.getEvEbitdaReason(), row.getEvEbitdaFinancialSource(), null, row.getEvEbitdaPeriodEnd())),
                row.getClosePrice(), row.getCurrency(), row.getCalculatedAt(), row.getFinancialObservedAt(), row.getSharesObservedAt(),
                row.getSecShareAccession() == null ? row.getMarketCapSource() : "SEC_FILING_SHARES", row.getSecFinancialAvailableFromDate());
    }

    private static Value financialValue(BigDecimal amount, String reason, String source, LocalDate start, LocalDate end) {
        Value value = value(amount, reason);
        return new Value(value.value(), value.status(), value.reasonCode(), source == null ? "FUTU" : source, start, end);
    }

    private static Value value(BigDecimal amount, String reason) {
        if (amount != null) return new Value(amount, "AVAILABLE", null);
        if (reason != null && NOT_APPLICABLE_REASONS.contains(reason)) return new Value(null, "NOT_APPLICABLE", reason);
        return new Value(null, reason != null && UNVERIFIED_REASONS.contains(reason) ? "UNVERIFIED" : "INSUFFICIENT_DATA", reason);
    }
}
