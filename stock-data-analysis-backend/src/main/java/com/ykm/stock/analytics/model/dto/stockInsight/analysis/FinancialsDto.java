package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 個股分析報告展示的 14 項當期財務原料與 TTM 分母。 */
public record FinancialsDto(
        LocalDate periodEnd,
        Instant availableAt,
        String reportingCurrency,
        FinancialValue revenueTtm,
        FinancialValue netIncomeCommonTtm,
        FinancialValue epsTtm,
        FinancialValue operatingCashFlowTtm,
        FinancialValue capitalExpenditureTtm,
        FinancialValue freeCashFlowTtm,
        FinancialValue ebitdaTtm,
        FinancialValue commonEquity,
        FinancialValue sharesOutstanding,
        FinancialValue bookValuePerShare,
        FinancialValue totalDebt,
        FinancialValue cashLikeAssets,
        FinancialValue preferredEquity,
        FinancialValue noncontrollingInterest
) {
    public record FinancialValue(
            BigDecimal value,
            String status,
            String reasonCode,
            String calculation,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant availableAt
    ) {
        public static FinancialValue available(BigDecimal value, String calculation, LocalDate periodStart, LocalDate periodEnd, Instant availableAt) {
            return new FinancialValue(value, MetricStatus.AVAILABLE.name(), null, calculation, periodStart, periodEnd, availableAt);
        }

        public static FinancialValue unavailable(String status, String reasonCode, String calculation, LocalDate periodStart, LocalDate periodEnd, Instant availableAt) {
            return new FinancialValue(null, status == null ? MetricStatus.INSUFFICIENT_DATA.name() : status,
                    reasonCode, calculation, periodStart, periodEnd, availableAt);
        }
    }
}
