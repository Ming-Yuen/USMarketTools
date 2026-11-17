package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.LocalDate;
import java.util.List;

public record StockAnalysisReportDto(String figi, String symbol, String name, LocalDate valuationDate,
                                     ValuationResultDto valuation, List<ValuationResultDto> history,
                                     MetricResult<PriceStructureDto> priceStructure,
                                     MetricResult<SupportResistanceDto> supportResistance,
                                     MetricResult<TradePlanResultDto> tradePlans,
                                     MetricResult<MarketContextDto> marketContext,
                                     FutuObservationsDto futuObservations, List<AnalysisReasonDto> reasons) {}
