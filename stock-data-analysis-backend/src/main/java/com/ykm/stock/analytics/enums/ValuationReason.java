package com.ykm.stock.analytics.enums;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;

import java.util.HashMap;
import java.util.Map;

/** 估值倍數與財務指標的標準狀態原因碼，集中定義對應的 MetricStatus 分類。 */
public enum ValuationReason {
    // NOT_APPLICABLE（N.M. 或模型／歷史日期不適用）
    NON_POSITIVE_EPS(MetricStatus.NOT_APPLICABLE),
    NON_POSITIVE_COMMON_EQUITY(MetricStatus.NOT_APPLICABLE),
    NON_POSITIVE_REVENUE(MetricStatus.NOT_APPLICABLE),
    NON_POSITIVE_FCF(MetricStatus.NOT_APPLICABLE),
    NON_POSITIVE_EBITDA(MetricStatus.NOT_APPLICABLE),
    PFCF_NOT_APPLICABLE_TO_INDUSTRY(MetricStatus.NOT_APPLICABLE),
    EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY(MetricStatus.NOT_APPLICABLE),
    HISTORICAL_DATE_NOT_APPLICABLE(MetricStatus.NOT_APPLICABLE),

    // UNVERIFIED（幣別、準則、拆股基準、多股類經濟權或披露衝突未確認）
    REPORTING_CURRENCY_MISMATCH(MetricStatus.UNVERIFIED),
    FINANCIAL_CONCEPT_VALUE_CONFLICT(MetricStatus.UNVERIFIED),
    FINANCIAL_PERIOD_CONFLICT(MetricStatus.UNVERIFIED),
    NON_ADDITIVE_FINANCIAL_CONCEPT(MetricStatus.UNVERIFIED),
    PREFERRED_EQUITY_NEGATIVE(MetricStatus.UNVERIFIED),
    EV_ACCOUNTING_STANDARD_UNCONFIRMED(MetricStatus.UNVERIFIED),
    EV_LEASE_SCOPE_UNRESOLVED(MetricStatus.UNVERIFIED),
    EV_FINANCING_SCOPE_UNRESOLVED(MetricStatus.UNVERIFIED),
    EPS_SPLIT_BASIS_UNCONFIRMED(MetricStatus.UNVERIFIED),
    SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED(MetricStatus.UNVERIFIED),
    SEC_SHARE_CLASS_EPS_UNVERIFIED(MetricStatus.UNVERIFIED),
    SEC_SHARE_SPLIT_BASIS_UNCONFIRMED(MetricStatus.UNVERIFIED),
    SEC_FACT_VERSION_CONFLICT(MetricStatus.UNVERIFIED),
    SEC_FACT_PERIOD_CONFLICT(MetricStatus.UNVERIFIED),
    SEC_TTM_PERIOD_CONFLICT(MetricStatus.UNVERIFIED),
    SEC_REVENUE_TOTAL_SCOPE_UNVERIFIED(MetricStatus.UNVERIFIED),
    SEC_REVENUE_SCOPE_CONFLICT(MetricStatus.UNVERIFIED),
    SEC_REVENUE_NET_SCOPE_UNVERIFIED(MetricStatus.UNVERIFIED),
    SEC_FCF_PERIOD_MISMATCH(MetricStatus.UNVERIFIED),
    SEC_FCF_SCOPE_CONFLICT(MetricStatus.UNVERIFIED),
    SEC_CAPITAL_EXPENDITURE_SIGN_UNVERIFIED(MetricStatus.UNVERIFIED),
    SEC_COMMON_EQUITY_UNRESOLVED(MetricStatus.UNVERIFIED),
    SEC_EV_COMPONENTS_UNRESOLVED(MetricStatus.UNVERIFIED),
    SEC_EBITDA_COMPONENTS_UNRESOLVED(MetricStatus.UNVERIFIED),
    SEC_DEPRECIATION_AMORTIZATION_SCOPE_UNRESOLVED(MetricStatus.UNVERIFIED),

    // INSUFFICIENT_DATA（缺少行情、股數、財報期間或個別財務原料）
    CLOSING_PRICE_MISSING(MetricStatus.INSUFFICIENT_DATA),
    CLOSING_PRICE_INVALID(MetricStatus.INSUFFICIENT_DATA),
    CLOSING_PRICE_MISSING_OR_INVALID(MetricStatus.INSUFFICIENT_DATA),
    NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE(MetricStatus.INSUFFICIENT_DATA),
    VALUATION_INPUTS_INCOMPLETE(MetricStatus.INSUFFICIENT_DATA),
    VALUATION_NOT_CALCULATED(MetricStatus.INSUFFICIENT_DATA),
    VALUATION_DATE_SHARES_MISSING(MetricStatus.INSUFFICIENT_DATA),
    MARKET_CAP_MISSING(MetricStatus.INSUFFICIENT_DATA),
    SPLIT_SYNC_ERROR(MetricStatus.INSUFFICIENT_DATA),
    VALUATION_DENOMINATOR_ZERO(MetricStatus.INSUFFICIENT_DATA),
    TTM_QUARTERS_MISSING(MetricStatus.INSUFFICIENT_DATA),
    TTM_PERIODS_MISSING(MetricStatus.INSUFFICIENT_DATA),
    FISCAL_YEAR_MISSING(MetricStatus.INSUFFICIENT_DATA),
    EPS_TTM_PERIODS_MISSING(MetricStatus.INSUFFICIENT_DATA),
    EPS_SOURCE_PERIOD_MISSING(MetricStatus.INSUFFICIENT_DATA),
    DILUTED_EPS_TTM_MISSING(MetricStatus.INSUFFICIENT_DATA),
    REVENUE_MISSING(MetricStatus.INSUFFICIENT_DATA),
    REVENUE_TTM_MISSING(MetricStatus.INSUFFICIENT_DATA),
    NET_INCOME_COMMON_MISSING(MetricStatus.INSUFFICIENT_DATA),
    OPERATING_CASH_FLOW_MISSING(MetricStatus.INSUFFICIENT_DATA),
    CAPITAL_EXPENDITURE_MISSING(MetricStatus.INSUFFICIENT_DATA),
    FCF_COMPONENTS_MISSING(MetricStatus.INSUFFICIENT_DATA),
    FREE_CASH_FLOW_TTM_MISSING(MetricStatus.INSUFFICIENT_DATA),
    EBITDA_COMPONENTS_UNRESOLVED(MetricStatus.INSUFFICIENT_DATA),
    EBITDA_TTM_MISSING(MetricStatus.INSUFFICIENT_DATA),
    BALANCE_SHEET_MISSING(MetricStatus.INSUFFICIENT_DATA),
    BALANCE_INPUT_MISSING(MetricStatus.INSUFFICIENT_DATA),
    COMMON_EQUITY_MISSING(MetricStatus.INSUFFICIENT_DATA),
    COMMON_EQUITY_UNRESOLVED(MetricStatus.INSUFFICIENT_DATA),
    EV_COMPONENTS_UNRESOLVED(MetricStatus.INSUFFICIENT_DATA),
    ENTERPRISE_VALUE_INPUTS_UNRESOLVED(MetricStatus.INSUFFICIENT_DATA),
    FINANCIAL_INPUT_MISSING(MetricStatus.INSUFFICIENT_DATA),
    FUTU_VALUATION_FIELDS_MISSING(MetricStatus.INSUFFICIENT_DATA),
    FUTU_VALUATION_FIELD_MISSING(MetricStatus.INSUFFICIENT_DATA),
    NATIVE_METRIC_NOT_PROVIDED(MetricStatus.INSUFFICIENT_DATA),
    HISTORICAL_SAMPLES_INSUFFICIENT(MetricStatus.INSUFFICIENT_DATA),
    INDUSTRY_PEER_COMPANIES_INSUFFICIENT(MetricStatus.INSUFFICIENT_DATA),
    INDUSTRY_PEER_INPUTS_INSUFFICIENT(MetricStatus.INSUFFICIENT_DATA),
    INDUSTRY_PEER_PRICE_NON_POSITIVE(MetricStatus.INSUFFICIENT_DATA),
    INDUSTRY_FAIR_RANGE_MISSING(MetricStatus.INSUFFICIENT_DATA),
    INDUSTRY_PRIMARY_METRIC_MISSING(MetricStatus.INSUFFICIENT_DATA),
    INDUSTRY_CLASSIFICATION_MISSING(MetricStatus.INSUFFICIENT_DATA);

    private static final Map<String, ValuationReason> BY_NAME = buildIndex();

    private final MetricStatus metricStatus;

    ValuationReason(MetricStatus metricStatus) {
        this.metricStatus = metricStatus;
    }

    public MetricStatus metricStatus() {
        return metricStatus;
    }

    public String statusName() {
        return metricStatus.name();
    }

    public static ValuationReason fromCode(String code) {
        if (code == null || code.isBlank()) return null;
        String normalized = normalizeAlias(code.trim());
        ValuationReason match = BY_NAME.get(normalized);
        return match != null ? match : FINANCIAL_INPUT_MISSING;
    }

    public static String statusNameFor(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) return MetricStatus.INSUFFICIENT_DATA.name();
        ValuationReason parsed = BY_NAME.get(normalizeAlias(reasonCode.trim()));
        return parsed != null ? parsed.statusName() : MetricStatus.INSUFFICIENT_DATA.name();
    }

    public static String normalizeAlias(String code) {
        if (code == null) return null;
        return switch (code) {
            case "SEC_REVENUE_MISSING" -> REVENUE_MISSING.name();
            case "SEC_FCF_COMPONENTS_MISSING" -> FCF_COMPONENTS_MISSING.name();
            case "SEC_EPS_TTM_PERIODS_MISSING" -> EPS_TTM_PERIODS_MISSING.name();
            case "SEC_TTM_PERIODS_MISSING" -> TTM_PERIODS_MISSING.name();
            case "SEC_FINANCIAL_INPUT_MISSING" -> FINANCIAL_INPUT_MISSING.name();
            case "SEC_BALANCE_INPUT_MISSING" -> BALANCE_INPUT_MISSING.name();
            default -> code;
        };
    }

    private static Map<String, ValuationReason> buildIndex() {
        Map<String, ValuationReason> map = new HashMap<>();
        for (ValuationReason value : values()) {
            map.put(value.name(), value);
        }
        return Map.copyOf(map);
    }
}
