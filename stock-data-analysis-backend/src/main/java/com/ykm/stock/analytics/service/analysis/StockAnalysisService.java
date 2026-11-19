package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PeerRelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.StockObservationService.ForwardObservationContext;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
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
    private final MarketEventRepository marketEvents;
    private final MarketStatusService calendar;
    private final QuoteNormalizationService prices;
    private final PriceStructureService structures;
    private final SupportResistanceService levels;
    private final TradePlanService plans;
    private final MarketContextService market;
    private final StockObservationService stockObservations;
    private final StockValuationService stockValuations;
    private final SectorMoneyFlowService sectorMoneyFlowService;
    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();

    /** 只讀已入庫行情與財季分母即時組裝估值、14 項財務原料、5 年月末歷史、前瞻預期觀察、技術交易計畫與綜合估值摘要；不觸發外部下載或寫庫。 */
    @Transactional(readOnly = true)
    public StockAnalysisReportDto analyze(AnalysisReportRequest request) {
        Stock stock = stocks.findById(request.figi())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        Instant readAt = Instant.now();
        LocalDate day = request.valuationDate() == null ? calendar.getLastClosedTradingDay(readAt) : request.valuationDate();
        if (!calendar.isTradingDay(day) || calendar.getSessionCloseAt(day).isAfter(readAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "valuationDate must be a completed trading session");
        }
        AnalysisHorizon horizon = request.horizon() == null ? AnalysisHorizon.POSITION : request.horizon();
        Instant cutoff = calendar.getSessionCloseAt(day);
        // 預設報告讀取目前已取得的觀察；歷史報告限定在所選交易日收市時間。
        Instant supplementalCutoff = request.valuationDate() == null ? readAt : cutoff;

        String industryCode = industries.findAt(stock.getFigi(), cutoff).map(row -> row.getIndustry().getCode()).orElse(null);
        ReportValuationBundle bundle = stockValuations.evaluateForReport(stock, day, cutoff, supplementalCutoff, industryCode);
        ValuationResultDto valuation = bundle.valuation();

        // 當預設最新日查詢因收市後行情尚未同步而由 evaluateForReport 回退至 effectiveLatest 時，同步對齊前瞻、技術與市場分析錨點
        LocalDate resolvedReportDate = request.valuationDate() == null
                ? (bundle.resolvedValuationDate() != null ? bundle.resolvedValuationDate()
                        : (valuation != null && valuation.valuationDate() != null ? valuation.valuationDate() : day))
                : day;
        if (!resolvedReportDate.equals(day)) {
            cutoff = calendar.getSessionCloseAt(resolvedReportDate);
        }
        AnalysisContext context = new AnalysisContext(stock.getFigi(), cutoff, supplementalCutoff, horizon);

        FutuObservationsDto observations = stockObservations.readWithForward(new ForwardObservationContext(
                stock.getFigi(),
                resolvedReportDate,
                supplementalCutoff,
                stock.getCurrency(),
                bundle.closePriceAtDay() != null ? bundle.closePriceAtDay() : (valuation == null ? null : valuation.price()),
                bundle.marketCapAtDay(),
                bundle.sharesReasonAtDay(),
                bundle.epsAtDay(),
                bundle.peReasonAtDay(),
                bundle.splits(),
                bundle.splitSyncError(),
                bundle.storedPeriods()
        ));

        List<AnalysisReasonDto> reasons = new ArrayList<>();
        if (valuation == null) {
            reasons.add(AnalysisReasonDto.of("VALUATION_NOT_CALCULATED", "VALUATION", "尚無符合交易日及輸入時間資格的估值。"));
        } else if (valuation.reasonCode() != null) {
            reasons.add(AnalysisReasonDto.of(valuation.reasonCode(), "VALUATION", "部分估值輸入不足，詳見各指標原因。"));
        }

        MetricResult<NormalizedPriceSeriesDto> price = prices.normalize(context, resolvedReportDate.minusYears(5), resolvedReportDate);
        MetricResult<PriceStructureDto> structure = unavailable("TECHNICAL_PRICE_MISSING");
        MetricResult<SupportResistanceDto> support = unavailable("TECHNICAL_STRUCTURE_MISSING");
        MetricResult<MarketContextDto> marketResult = unavailable("TECHNICAL_PRICE_MISSING");
        MetricResult<TradePlanResultDto> tradePlans = unavailable("TECHNICAL_STRUCTURE_MISSING");
        if (price.status() == MetricStatus.AVAILABLE) {
            structure = structures.analyze(context, price.value());
            LocalDate lastQuoteDate = price.value().bars().isEmpty() ? resolvedReportDate : price.value().bars().getLast().sessionDate();
            List<MarketEvent> pastEarnings = marketEvents.findVisiblePastEarnings(stock.getFigi(), lastQuoteDate, supplementalCutoff);
            marketResult = market.evaluate(context, price.value(), structure.value(), pastEarnings);
            MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
            if (structure.status() == MetricStatus.AVAILABLE) {
                support = levels.analyze(context, price.value(), structure.value(), marketValue);
            }
            if (support.status() == MetricStatus.AVAILABLE) {
                PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
                tradePlans = plans.build(context, price.value(), structure.value(), support.value(), constraint);
            }
        }
        reasons.addAll(price.reasons());
        MetricResult<InvestmentSummaryDto> summary = summaryCalculator.calculate(
                horizon, valuation, bundle.financials(), bundle.fundamentals(), observations,
                structure, support, tradePlans, marketResult);
        PeerRelativeStrengthDto peerRelativeStrength = null;
        if (sectorMoneyFlowService != null && marketResult.status() == MetricStatus.AVAILABLE && marketResult.value() != null) {
            peerRelativeStrength = sectorMoneyFlowService.evaluatePeerRelativeStrength(
                    stock.getFigi(),
                    industryCode,
                    resolvedReportDate,
                    request.valuationDate() != null,
                    marketResult.value().relativeStrength()
            );
        }
        return new StockAnalysisReportDto(stock.getFigi(), stock.getSymbol(), stock.getLongName(), resolvedReportDate,
                valuation, bundle.financials(), bundle.history(),
                structure, support, tradePlans, marketResult, observations, reasons,
                bundle.fundamentals(), summary, peerRelativeStrength);
    }

    private <T> MetricResult<T> unavailable(String code) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(code, "TECHNICAL", "缺少相應技術分析輸入。"));
    }
}
