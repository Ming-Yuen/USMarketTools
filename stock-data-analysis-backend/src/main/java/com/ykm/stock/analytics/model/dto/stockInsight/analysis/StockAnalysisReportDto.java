package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.LocalDate;
import java.util.List;

public record StockAnalysisReportDto(
        String figi,
        String symbol,
        String name,
        LocalDate valuationDate,
        ValuationResultDto valuation,
        FinancialsDto financials,
        List<ValuationResultDto> history,
        MetricResult<PriceStructureDto> priceStructure,
        MetricResult<SupportResistanceDto> supportResistance,
        MetricResult<TradePlanResultDto> tradePlans,
        MetricResult<MarketContextDto> marketContext,
        FutuObservationsDto futuObservations,
        List<AnalysisReasonDto> reasons,
        FundamentalSnapshotDto fundamentals,
        MetricResult<InvestmentSummaryDto> summary
) {
    public StockAnalysisReportDto(String figi, String symbol, String name, LocalDate valuationDate,
                                  ValuationResultDto valuation, FinancialsDto financials, List<ValuationResultDto> history,
                                  MetricResult<PriceStructureDto> priceStructure,
                                  MetricResult<SupportResistanceDto> supportResistance,
                                  MetricResult<TradePlanResultDto> tradePlans,
                                  MetricResult<MarketContextDto> marketContext,
                                  FutuObservationsDto futuObservations, List<AnalysisReasonDto> reasons) {
        this(figi, symbol, name, valuationDate, valuation, financials, history,
                priceStructure, supportResistance, tradePlans, marketContext, futuObservations, reasons, null, null);
    }

    public StockAnalysisReportDto(String figi, String symbol, String name, LocalDate valuationDate,
                                  ValuationResultDto valuation, List<ValuationResultDto> history,
                                  MetricResult<PriceStructureDto> priceStructure,
                                  MetricResult<SupportResistanceDto> supportResistance,
                                  MetricResult<TradePlanResultDto> tradePlans,
                                  MetricResult<MarketContextDto> marketContext,
                                  FutuObservationsDto futuObservations, List<AnalysisReasonDto> reasons) {
        this(figi, symbol, name, valuationDate, valuation, null, history,
                priceStructure, supportResistance, tradePlans, marketContext, futuObservations, reasons, null, null);
    }
}
