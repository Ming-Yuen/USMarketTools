package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 個股當期拆股對齊之可比成長、獲利品質、槓桿與殖利率快照（純唯讀計算，不入庫）。
 * 單位約定：
 * - 比例與成長率欄位（revenueGrowthYoy、epsGrowthYoy、fcfGrowthYoy、revenueCagr3y、epsCagr3y、
 *   roe、fcfMargin、fcfConversion、fcfYield、earningsYield、netShareChangeYoy）統一採小數比例（如 0.05 代表 5%）。
 * - trailingPeg 與 netDebtToEbitda 為倍數（如 1.25 代表 1.25 倍）。
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
        MetricResult<BigDecimal> netShareChangeYoy
) {}
