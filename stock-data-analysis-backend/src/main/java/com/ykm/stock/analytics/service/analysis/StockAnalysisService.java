package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.ReportValuationBundle;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockAnalysisService {
    private final StockRepository stocks;
    private final StockIndustryHistoryRepository industries;
    private final MarketStatusService calendar;
    private final QuoteNormalizationService prices;
    private final PriceStructureService structures;
    private final SupportResistanceService levels;
    private final TradePlanService plans;
    private final MarketContextService market;
    private final StockObservationService stockObservations;
    private final StockValuationService stockValuations;

    /** 只讀已入庫行情與財季分母即時組裝估值、14 項財務原料、5 年月末歷史與技術交易計畫；不觸發外部下載或寫庫。 */
    @Transactional(readOnly = true)
    public StockAnalysisReportDto analyze(AnalysisReportRequest request) {
        Stock stock = stocks.findById(request.figi())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        Instant readAt = Instant.now();
        LocalDate day = request.valuationDate() == null ? calendar.getLastClosedTradingDay(readAt) : request.valuationDate();
        if (!calendar.isTradingDay(day) || calendar.getSessionCloseAt(day).isAfter(readAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valuationDate must be a completed trading session");
        }
        Instant cutoff = calendar.getSessionCloseAt(day);
        // 預設報告讀取目前已取得的觀察；歷史報告限定在所選交易日收市時間。
        Instant supplementalCutoff = request.valuationDate() == null ? readAt : cutoff;
        AnalysisContext context = new AnalysisContext(stock.getFigi(), cutoff, supplementalCutoff,
                request.horizon() == null ? AnalysisHorizon.POSITION : request.horizon());

        FutuObservationsDto observations = stockObservations.read(stock.getFigi(), supplementalCutoff);
        String industryCode = industries.findAt(stock.getFigi(), cutoff).map(row -> row.getIndustry().getCode()).orElse(null);
        ReportValuationBundle bundle = stockValuations.evaluateForReport(stock, day, cutoff, supplementalCutoff, industryCode);
        ValuationResultDto valuation = bundle.valuation();

        List<AnalysisReasonDto> reasons = new ArrayList<>();
        if (valuation == null) {
            reasons.add(AnalysisReasonDto.of("VALUATION_NOT_CALCULATED", "VALUATION", "尚無符合交易日及輸入時間資格的估值。"));
        } else if (valuation.reasonCode() != null) {
            reasons.add(AnalysisReasonDto.of(valuation.reasonCode(), "VALUATION", "部分估值輸入不足，詳見各指標原因。"));
        }

        MetricResult<NormalizedPriceSeriesDto> price = prices.normalize(context, day.minusYears(5), day);
        MetricResult<PriceStructureDto> structure = unavailable("TECHNICAL_PRICE_MISSING");
        MetricResult<SupportResistanceDto> support = unavailable("TECHNICAL_STRUCTURE_MISSING");
        MetricResult<MarketContextDto> marketResult = unavailable("TECHNICAL_PRICE_MISSING");
        MetricResult<TradePlanResultDto> tradePlans = unavailable("TECHNICAL_STRUCTURE_MISSING");
        if (price.status() == MetricStatus.AVAILABLE) {
            structure = structures.analyze(context, price.value());
            if (structure.status() == MetricStatus.AVAILABLE) {
                support = levels.analyze(context, price.value(), structure.value());
            }
            marketResult = market.evaluate(context, price.value(), structure.value());
            if (support.status() == MetricStatus.AVAILABLE) {
                PlanConstraintDto constraint = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value().tradeConstraint() : null;
                tradePlans = plans.build(context, price.value(), structure.value(), support.value(), constraint);
            }
        }
        reasons.addAll(price.reasons());
        return new StockAnalysisReportDto(stock.getFigi(), stock.getSymbol(), stock.getLongName(), day,
                valuation, bundle.financials(), bundle.history(),
                structure, support, tradePlans, marketResult, observations, reasons);
    }

    private <T> MetricResult<T> unavailable(String code) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(code, "TECHNICAL", "缺少相應技術分析輸入。"));
    }
}
