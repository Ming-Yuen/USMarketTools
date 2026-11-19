package com.ykm.stock.analytics.service.analysis;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.MacroCautionDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.TrendPullbackStarterDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PeerRelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.StockObservationService.ForwardObservationContext;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator.SizingInputs;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.PortfolioExposureSnapshot;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.ReportValuationBundle;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
    private final DateTimeService dateTimeService;
    private final QuoteNormalizationService prices;
    private final PriceStructureService structures;
    private final SupportResistanceService levels;
    private final TradePlanService plans;
    private final MarketContextService market;
    private final StockObservationService stockObservations;
    private final StockValuationService stockValuations;
    private final SectorMoneyFlowService sectorMoneyFlowService;
    private final PortfolioManagementService portfolioManagementService;
    private final PositionSizingCalculator positionSizingCalculator;
    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();

    /** 只讀已入庫行情與財季分母即時組裝估值、14 項財務原料、5 年月末歷史、前瞻預期觀察、技術交易計畫與綜合估值摘要；不觸發外部下載或寫庫。 */
    @Transactional(readOnly = true)
    public StockAnalysisReportDto analyze(AnalysisReportRequest request) {
        Stock stock = stocks.findById(request.figi())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        Instant readAt = dateTimeService.now();
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
        // 在技術計畫（含兩收盤失效判定）與綜合摘要全部完成後，一次性載入個人組合曝險並附加倉位風險計算
        boolean historicalQuery = request.valuationDate() != null;
        boolean quoteFellBack = !historicalQuery && !resolvedReportDate.equals(day);
        BigDecimal latestPriceClose = price.status() == MetricStatus.AVAILABLE && price.value() != null
                && !price.value().bars().isEmpty() ? price.value().bars().getLast().close()
                : (valuation != null ? valuation.price() : null);
        SizingAttachment sizingAttachment = attachPositionSizing(
                stock, industryCode, resolvedReportDate, readAt, historicalQuery, quoteFellBack,
                latestPriceClose, tradePlans, summary, marketResult);
        MetricResult<TradePlanResultDto> enrichedTradePlans = sizingAttachment.tradePlans();
        PositionSizingDto starterPositionSizing = sizingAttachment.starterPositionSizing();

        PeerRelativeStrengthDto peerRelativeStrength = null;
        if (sectorMoneyFlowService != null && marketResult.status() == MetricStatus.AVAILABLE && marketResult.value() != null) {
            peerRelativeStrength = sectorMoneyFlowService.evaluatePeerRelativeStrength(
                    stock.getFigi(),
                    industryCode,
                    resolvedReportDate,
                    historicalQuery,
                    marketResult.value().relativeStrength()
            );
        }
        return new StockAnalysisReportDto(stock.getFigi(), stock.getSymbol(), stock.getLongName(), resolvedReportDate,
                valuation, bundle.financials(), bundle.history(),
                structure, support, enrichedTradePlans, marketResult, observations, reasons,
                bundle.fundamentals(), summary, peerRelativeStrength, starterPositionSizing);
    }

    private record SizingAttachment(MetricResult<TradePlanResultDto> tradePlans, PositionSizingDto starterPositionSizing) {}

    private SizingAttachment attachPositionSizing(Stock stock,
                                                  String industryCode,
                                                  LocalDate resolvedReportDate,
                                                  Instant readAt,
                                                  boolean historicalQuery,
                                                  boolean quoteFellBack,
                                                  BigDecimal latestPriceClose,
                                                  MetricResult<TradePlanResultDto> tradePlans,
                                                  MetricResult<InvestmentSummaryDto> summary,
                                                  MetricResult<MarketContextDto> marketResult) {
        if (portfolioManagementService == null || positionSizingCalculator == null) {
            return new SizingAttachment(tradePlans, null);
        }
        boolean hasPlans = tradePlans != null && tradePlans.status() == MetricStatus.AVAILABLE
                && tradePlans.value() != null && !tradePlans.value().plans().isEmpty();
        InvestmentSummaryDto summaryValue = summary != null && summary.status() == MetricStatus.AVAILABLE
                ? summary.value() : null;
        TrendPullbackStarterDto starter = summaryValue != null ? summaryValue.trendPullbackStarter() : null;
        boolean hasStarter = starter != null && starter.eligible()
                && "TREND_PULLBACK_STARTER".equals(summaryValue.stance())
                && starter.anchorPrice() != null && starter.suggestedStop() != null;
        if (!hasPlans && !hasStarter) {
            return new SizingAttachment(tradePlans, null);
        }

        if (historicalQuery) {
            PositionSizingDto historicalNa = PositionSizingDto.notApplicable(
                    "HISTORICAL_DATE_NOT_APPLICABLE", null, null, null, null, industryCode);
            MetricResult<TradePlanResultDto> updatedPlans = tradePlans;
            if (hasPlans) {
                TradePlanResultDto raw = tradePlans.value();
                List<TradePlanDto> mapped = raw.plans().stream()
                        .map(p -> p.status() == TradePlanStatus.UNAVAILABLE ? p : p.withPositionSizing(historicalNa))
                        .toList();
                updatedPlans = MetricResult.available(new TradePlanResultDto(
                        mapped, raw.currentPrice(), raw.priceDataThrough(), raw.currentPriceRR(),
                        raw.reasons(), raw.evidenceRefs(), raw.contextEvaluated()), tradePlans.evidenceRefs());
            }
            return new SizingAttachment(updatedPlans, hasStarter ? historicalNa : null);
        }

        PortfolioExposureSnapshot exposure = portfolioManagementService.loadExposureSnapshot(resolvedReportDate, readAt);
        if (exposure == null || exposure.config() == null) {
            return new SizingAttachment(tradePlans, null);
        }
        boolean existingHoldingUnverified = exposure.unverifiedHoldingFigis().contains(stock.getFigi());
        if (existingHoldingUnverified && "USD".equalsIgnoreCase(stock.getCurrency())) {
            PositionSizingDto unverifiedNa = PositionSizingDto.notApplicable(
                    "EXISTING_HOLDING_VALUE_UNVERIFIED",
                    exposure.config().totalCapital(),
                    exposure.config().riskPerTradePct(),
                    exposure.config().maxSinglePositionPct(),
                    exposure.config().maxSectorExposurePct(),
                    industryCode);
            MetricResult<TradePlanResultDto> updatedPlans = tradePlans;
            if (hasPlans) {
                TradePlanResultDto raw = tradePlans.value();
                List<TradePlanDto> mapped = raw.plans().stream()
                        .map(p -> p.status() == TradePlanStatus.UNAVAILABLE ? p : p.withPositionSizing(unverifiedNa))
                        .toList();
                updatedPlans = MetricResult.available(new TradePlanResultDto(
                        mapped, raw.currentPrice(), raw.priceDataThrough(), raw.currentPriceRR(),
                        raw.reasons(), raw.evidenceRefs(), raw.contextEvaluated()), tradePlans.evidenceRefs());
            }
            return new SizingAttachment(updatedPlans, hasStarter ? unverifiedNa : null);
        }
        MarketRegime regime = marketResult != null && marketResult.status() == MetricStatus.AVAILABLE
                && marketResult.value() != null && marketResult.value().market() != null
                && marketResult.value().market().status() == MetricStatus.AVAILABLE
                && marketResult.value().market().value() != null
                ? marketResult.value().market().value().regime() : null;
        MacroCautionDto macroCaution = summaryValue != null ? summaryValue.macroCaution() : null;
        boolean macroCautionActive = macroCaution != null && macroCaution.active();
        BigDecimal existingHoldingMv = exposure.holdingMarketValueUsdByFigi().getOrDefault(stock.getFigi(), BigDecimal.ZERO);
        BigDecimal currentSectorMv = industryCode == null ? BigDecimal.ZERO
                : exposure.sectorMarketValueUsdByIndustry().getOrDefault(industryCode, BigDecimal.ZERO);

        MetricResult<TradePlanResultDto> updatedPlans = tradePlans;
        BigDecimal currentPrice = null;
        if (hasPlans) {
            TradePlanResultDto raw = tradePlans.value();
            currentPrice = raw.currentPrice();
            List<TradePlanDto> mapped = new ArrayList<>(raw.plans().size());
            for (TradePlanDto plan : raw.plans()) {
                if (plan.status() == TradePlanStatus.UNAVAILABLE || plan.status() == TradePlanStatus.INVALIDATED
                        || plan.entryRange() == null || plan.stop() == null) {
                    PositionSizingDto na = PositionSizingDto.notApplicable(
                            "PLAN_NOT_ELIGIBLE",
                            exposure.config().totalCapital(),
                            exposure.config().riskPerTradePct(),
                            exposure.config().maxSinglePositionPct(),
                            exposure.config().maxSectorExposurePct(),
                            industryCode);
                    mapped.add(plan.withPositionSizing(na));
                    continue;
                }
                boolean planActionable = plan.status() == TradePlanStatus.ACTIVE;
                PositionSizingDto sizing = positionSizingCalculator.calculate(new SizingInputs(
                        stock.getCurrency(),
                        industryCode,
                        plan.entryRange().upper(),
                        raw.currentPrice(),
                        plan.stop().protectivePrice(),
                        planActionable,
                        quoteFellBack,
                        false,
                        regime,
                        macroCautionActive,
                        exposure.config(),
                        existingHoldingMv,
                        currentSectorMv,
                        exposure.excludedHoldingsCount()
                ));
                mapped.add(plan.withPositionSizing(sizing));
            }
            updatedPlans = MetricResult.available(new TradePlanResultDto(
                    mapped, raw.currentPrice(), raw.priceDataThrough(), raw.currentPriceRR(),
                    raw.reasons(), raw.evidenceRefs(), raw.contextEvaluated()), tradePlans.evidenceRefs());
        }

        PositionSizingDto starterSizing = null;
        if (hasStarter) {
            BigDecimal starterCurrent = currentPrice != null ? currentPrice
                    : (latestPriceClose != null ? latestPriceClose : starter.anchorPrice());
            starterSizing = positionSizingCalculator.calculate(new SizingInputs(
                    stock.getCurrency(),
                    industryCode,
                    starter.anchorPrice(),
                    starterCurrent,
                    starter.suggestedStop(),
                    true,
                    quoteFellBack,
                    true,
                    regime,
                    macroCautionActive,
                    exposure.config(),
                    existingHoldingMv,
                    currentSectorMv,
                    exposure.excludedHoldingsCount()
            ));
        }
        return new SizingAttachment(updatedPlans, starterSizing);
    }

    private <T> MetricResult<T> unavailable(String code) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(code, "TECHNICAL", "缺少相應技術分析輸入。"));
    }
}
