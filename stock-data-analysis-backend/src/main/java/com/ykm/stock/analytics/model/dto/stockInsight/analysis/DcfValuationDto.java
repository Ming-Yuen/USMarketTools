package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 兩階段股權現金流（FCFE 近似）DCF 與 Reverse DCF 獨立估值對照模型。
 * 嚴格獨立於客觀市場相對估值帶 [L, M, H] 之外，絕不參與估值帶平均、verdict 或雷達篩選。
 */
public record DcfValuationDto(
        String status,
        String reasonCode,
        String methodologyNote,
        LocalDate valuationDate,
        String currency,
        BigDecimal currentPrice,
        BigDecimal marketCapUsd,
        BigDecimal sharesAtDay,
        BigDecimal riskFreeRate,
        LocalDate riskFreeRateDate,
        BigDecimal equityRiskPremium,
        BigDecimal discountRate,
        BigDecimal terminalGrowthRate,
        BigDecimal historicalRevenueCagr3y,
        BigDecimal historicalFcfCagr3y,
        BigDecimal forwardExpectedGrowth,
        String growthBasisNote,
        DcfBranchDto standardFcf,
        DcfBranchDto sbcAdjustedFcf
) {
    public record DcfScenarioDto(
            BigDecimal stage1GrowthRate,
            BigDecimal intrinsicEquityValue,
            BigDecimal intrinsicPricePerShare,
            BigDecimal upsideDownsidePct
    ) {}

    public record ReverseDcfDto(
            String status,
            String reasonCode,
            BigDecimal impliedStage1GrowthRate,
            BigDecimal benchmarkGrowthRate,
            String benchmarkGrowthSource,
            BigDecimal expectationGapPpt,
            boolean expectationStretched
    ) {}

    public record DcfBranchDto(
            String basis,
            String status,
            String reasonCode,
            BigDecimal baseFcfUsd,
            BigDecimal baseStage1GrowthRate,
            DcfScenarioDto conservative,
            DcfScenarioDto base,
            DcfScenarioDto optimistic,
            ReverseDcfDto reverseDcf
    ) {}

    public static DcfValuationDto unavailable(String status, String reasonCode, LocalDate valuationDate, String currency, BigDecimal currentPrice) {
        return new DcfValuationDto(
                status, reasonCode,
                "兩階段股權現金流折現模型（採 FCF 近似 FCFE，折現率 = 10Y 美債 + ERP，假設淨借款變動為零；嚴格獨立於市場相對估值帶 [L, M, H]）。",
                valuationDate, currency, currentPrice, null, null,
                null, null, null, null, null,
                null, null, null, null,
                null, null
        );
    }
}
