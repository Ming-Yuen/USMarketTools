package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 個股當期拆股對齊之可比成長、獲利品質、資本回報、槓桿、流動性與殖利率快照（純唯讀計算，不入庫）。
 * 單位約定：
 * - 比例、利潤率與成長率欄位統一採小數比例（如 0.05 代表 5%；毛利率／營益率變動 0.0150 代表 +1.50 ppt）。
 * - 倍數欄位（trailingPeg、netDebtToEbitda、pToAdjFcf、evToSales、evToGrossProfit、pToTbv、interestCoverage、shortTermLiquidityCoverage）為倍數（x）。
 * - 金額欄位（sbcTtm、adjFcfTtm、nopat、investedCapital、tangibleBookValue）採財報原幣金額（reportingCurrency）。
 */
public record FundamentalSnapshotDto(
        LocalDate periodEnd,
        LocalDate priorYearPeriodEnd,
        LocalDate threeYearAgoPeriodEnd,
        String reportingCurrency,
        boolean financialInstitution,
        MetricResult<BigDecimal> revenueGrowthYoy,
        MetricResult<BigDecimal> epsGrowthYoy,
        MetricResult<BigDecimal> fcfGrowthYoy,
        MetricResult<BigDecimal> revenueCagr3y,
        MetricResult<BigDecimal> epsCagr3y,
        MetricResult<BigDecimal> trailingPeg,
        String pegGrowthBasis,
        MetricResult<BigDecimal> roe,
        MetricResult<BigDecimal> fcfMargin,
        MetricResult<BigDecimal> fcfConversion,
        MetricResult<BigDecimal> netDebtToEbitda,
        MetricResult<BigDecimal> fcfYield,
        MetricResult<BigDecimal> earningsYield,
        MetricResult<BigDecimal> netShareChangeYoy,
        // Batch 05 財務品質深化擴充
        MetricResult<BigDecimal> fcfCagr3y,
        MetricResult<BigDecimal> sbcTtm,
        MetricResult<BigDecimal> sbcToRevenue,
        MetricResult<BigDecimal> adjFcfTtm,
        MetricResult<BigDecimal> pToAdjFcf,
        MetricResult<BigDecimal> totalShareholderYield,
        MetricResult<BigDecimal> grossMargin,
        MetricResult<BigDecimal> grossMarginYoyPpt,
        MetricResult<BigDecimal> grossMarginQoqPpt,
        MetricResult<BigDecimal> operatingMargin,
        MetricResult<BigDecimal> operatingMarginYoyPpt,
        MetricResult<BigDecimal> operatingMarginQoqPpt,
        boolean marginCompressionConsecutive,
        MetricResult<BigDecimal> evToSales,
        MetricResult<BigDecimal> evToGrossProfit,
        MetricResult<BigDecimal> ruleOf40,
        MetricResult<BigDecimal> nopat,
        MetricResult<BigDecimal> investedCapital,
        MetricResult<BigDecimal> roic,
        MetricResult<BigDecimal> tangibleBookValue,
        MetricResult<BigDecimal> pToTbv,
        MetricResult<BigDecimal> interestCoverage,
        MetricResult<BigDecimal> shortTermLiquidityCoverage
) {
    public FundamentalSnapshotDto(
            LocalDate periodEnd,
            LocalDate priorYearPeriodEnd,
            LocalDate threeYearAgoPeriodEnd,
            String reportingCurrency,
            boolean financialInstitution,
            MetricResult<BigDecimal> revenueGrowthYoy,
            MetricResult<BigDecimal> epsGrowthYoy,
            MetricResult<BigDecimal> fcfGrowthYoy,
            MetricResult<BigDecimal> revenueCagr3y,
            MetricResult<BigDecimal> epsCagr3y,
            MetricResult<BigDecimal> trailingPeg,
            String pegGrowthBasis,
            MetricResult<BigDecimal> roe,
            MetricResult<BigDecimal> fcfMargin,
            MetricResult<BigDecimal> fcfConversion,
            MetricResult<BigDecimal> netDebtToEbitda,
            MetricResult<BigDecimal> fcfYield,
            MetricResult<BigDecimal> earningsYield,
            MetricResult<BigDecimal> netShareChangeYoy) {
        this(periodEnd, priorYearPeriodEnd, threeYearAgoPeriodEnd, reportingCurrency, financialInstitution,
                revenueGrowthYoy, epsGrowthYoy, fcfGrowthYoy, revenueCagr3y, epsCagr3y,
                trailingPeg, pegGrowthBasis, roe, fcfMargin, fcfConversion, netDebtToEbitda,
                fcfYield, earningsYield, netShareChangeYoy,
                null, null, null, null, null, null, null, null, null, null, null, null, false,
                null, null, null, null, null, null, null, null, null, null);
    }
}
