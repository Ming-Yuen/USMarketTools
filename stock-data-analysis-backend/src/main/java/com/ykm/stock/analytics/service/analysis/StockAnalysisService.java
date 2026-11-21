package com.ykm.stock.analytics.service.analysis;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
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
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SourceFreshnessDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.InsiderSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.OptionsSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ReportEnhancementHighlightsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ShortInterestSummaryDto;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.StockObservationService.ForwardObservationContext;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator.SizingInputs;
import com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator;
import com.ykm.stock.analytics.service.enhancement.OptionsEnhancementService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.PortfolioExposureSnapshot;
import com.ykm.stock.analytics.service.valuation.DcfValuationCalculator;
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
    @Autowired(required = false)
    private OptionsEnhancementService optionsEnhancementService;
    @Autowired(required = false)
    private SecInsiderAndInstitutionalService insiderAndInstitutionalService;
    @Autowired(required = false)
    private ShortInterestService shortInterestService;
    private final ValuationSummaryCalculator summaryCalculator = new ValuationSummaryCalculator();
    private final DcfValuationCalculator dcfCalculator = new DcfValuationCalculator();

    /** 只讀已入庫行情與財季分母即時組裝估值、財務品質、DCF／Reverse DCF、技術交易計畫與綜合摘要；ETF／ETP 走免財報技術分析路由。 */
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

        if (SecurityTypePolicy.isEtfType(stock.getStockType())) {
            return analyzeEtf(stock, request, day, cutoff, supplementalCutoff, readAt, horizon);
        }

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
            industryCode = industries.findAt(stock.getFigi(), cutoff).map(row -> row.getIndustry().getCode()).orElse(null);
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
        boolean sectorBreadthBreakdown = sectorMoneyFlowService != null
                && sectorMoneyFlowService.isIndustryBreadthBreakdown(industryCode, resolvedReportDate);
        MetricResult<InvestmentSummaryDto> summary = summaryCalculator.calculate(
                horizon, valuation, bundle.financials(), bundle.fundamentals(), observations,
                structure, support, tradePlans, marketResult, sectorBreadthBreakdown);
        // 在技術計畫（含兩收盤失效判定）與綜合摘要全部完成後，一次性載入個人組合曝險並附加倉位風險計算
        boolean historicalQuery = request.valuationDate() != null;
        boolean quoteFellBack = !historicalQuery && !resolvedReportDate.equals(day);
        BigDecimal latestPriceClose = price.status() == MetricStatus.AVAILABLE && price.value() != null
                && !price.value().bars().isEmpty() ? price.value().bars().getLast().close()
                : (valuation != null ? valuation.price() : null);
        SizingAttachment sizingAttachment = attachPositionSizing(
                stock, industryCode, resolvedReportDate, readAt, historicalQuery, quoteFellBack,
                sectorBreadthBreakdown, latestPriceClose, tradePlans, summary, marketResult);
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
        DcfValuationDto dcf = evaluateDcf(stock, resolvedReportDate, latestPriceClose, bundle, observations, marketResult);
        ReportEnhancementHighlightsDto enhancements = loadReadOnlyEnhancements(
                stock, resolvedReportDate, latestPriceClose, enrichedTradePlans, summary, marketResult, false);

        return new StockAnalysisReportDto(stock.getFigi(), stock.getSymbol(), stock.getLongName(), resolvedReportDate,
                valuation, bundle.financials(), bundle.history(),
                structure, support, enrichedTradePlans, marketResult, observations, reasons,
                bundle.fundamentals(), summary, peerRelativeStrength, starterPositionSizing,
                stock.getStockType(), dcf, enhancements);
    }

    /** ETF／ETP 專屬路由：跳過單一公司財報／估值／DCF／共識預測，完整輸出 K 線結構、支撐阻力、Volume Profile、AVWAP、CMF、相對 SPY 強弱與免財報交易計畫。 */
    private StockAnalysisReportDto analyzeEtf(
            Stock stock, AnalysisReportRequest request, LocalDate day, Instant cutoff,
            Instant supplementalCutoff, Instant readAt, AnalysisHorizon horizon) {
        LocalDate resolvedReportDate = day;
        if (request.valuationDate() == null) {
            LocalDate latestStored = prices.findLatestStoredQuoteDate(stock.getFigi(), day);
            if (latestStored != null && latestStored.isBefore(day)) {
                resolvedReportDate = latestStored;
                cutoff = calendar.getSessionCloseAt(resolvedReportDate);
            }
        }
        AnalysisContext initialContext = new AnalysisContext(stock.getFigi(), cutoff, supplementalCutoff, horizon);
        MetricResult<NormalizedPriceSeriesDto> price = prices.normalize(initialContext, resolvedReportDate.minusYears(5), resolvedReportDate);
        BigDecimal latestPriceClose = null;
        if (price.status() == MetricStatus.AVAILABLE && price.value() != null && !price.value().bars().isEmpty()) {
            LocalDate lastBarDate = price.value().bars().getLast().sessionDate();
            latestPriceClose = price.value().bars().getLast().close();
            if (request.valuationDate() == null && lastBarDate != null && lastBarDate.isBefore(resolvedReportDate)) {
                resolvedReportDate = lastBarDate;
                cutoff = calendar.getSessionCloseAt(resolvedReportDate);
            }
        } else {
            latestPriceClose = prices.findStoredClosePrice(stock.getFigi(), resolvedReportDate);
        }
        String industryCode = industries.findAt(stock.getFigi(), cutoff)
                .map(row -> row.getIndustry().getCode()).orElse(null);
        AnalysisContext context = new AnalysisContext(stock.getFigi(), cutoff, supplementalCutoff, horizon);
        ValuationResultDto etfValuation = ValuationResultDto.forEtf(
                stock.getFigi(), resolvedReportDate, cutoff, latestPriceClose, stock.getCurrency(), readAt);
        DcfValuationDto etfDcf = DcfValuationDto.unavailable(
                MetricStatus.NOT_APPLICABLE.name(), "NOT_APPLICABLE_FOR_ETF",
                resolvedReportDate, stock.getCurrency(), latestPriceClose);

        MetricResult<PriceStructureDto> structure = unavailable("TECHNICAL_PRICE_MISSING");
        MetricResult<SupportResistanceDto> support = unavailable("TECHNICAL_STRUCTURE_MISSING");
        MetricResult<MarketContextDto> marketResult = unavailable("TECHNICAL_PRICE_MISSING");
        MetricResult<TradePlanResultDto> tradePlans = unavailable("TECHNICAL_STRUCTURE_MISSING");
        if (price.status() == MetricStatus.AVAILABLE) {
            structure = structures.analyze(context, price.value());
            marketResult = market.evaluate(context, price.value(), structure.value(), List.of(), true);
            MarketContextDto marketValue = marketResult.status() == MetricStatus.AVAILABLE ? marketResult.value() : null;
            if (structure.status() == MetricStatus.AVAILABLE) {
                support = levels.analyze(context, price.value(), structure.value(), marketValue);
            }
            if (support.status() == MetricStatus.AVAILABLE) {
                PlanConstraintDto constraint = marketValue != null ? marketValue.tradeConstraint() : null;
                tradePlans = plans.build(context, price.value(), structure.value(), support.value(), constraint);
            }
        }
        List<AnalysisReasonDto> reasons = new ArrayList<>(price.reasons());
        MetricResult<InvestmentSummaryDto> summary = summaryCalculator.calculateForEtf(
                horizon, latestPriceClose, stock.getCurrency(), structure, support, tradePlans, marketResult);

        boolean historicalQuery = request.valuationDate() != null;
        boolean quoteFellBack = !historicalQuery && !resolvedReportDate.equals(day);
        boolean sectorBreadthBreakdown = sectorMoneyFlowService != null
                && sectorMoneyFlowService.isIndustryBreadthBreakdown(industryCode, resolvedReportDate);
        SizingAttachment sizingAttachment = attachPositionSizing(
                stock, industryCode, resolvedReportDate, readAt, historicalQuery, quoteFellBack,
                sectorBreadthBreakdown, latestPriceClose, tradePlans, summary, marketResult);

        ReportEnhancementHighlightsDto etfEnhancements = loadReadOnlyEnhancements(
                stock, resolvedReportDate, latestPriceClose, sizingAttachment.tradePlans(), summary, marketResult, true);

        return new StockAnalysisReportDto(
                stock.getFigi(), stock.getSymbol(), stock.getLongName(), resolvedReportDate,
                etfValuation, null, List.of(),
                structure, support, sizingAttachment.tradePlans(), marketResult, null, reasons,
                null, summary, null, sizingAttachment.starterPositionSizing(),
                stock.getStockType(), etfDcf, etfEnhancements
        );
    }

    /** 純資料庫唯讀掛載 06_Enhancement 籌碼亮點（0 次新增外部 API 呼叫，嚴格截斷於 resolvedReportDate 防穿越）。 */
    private ReportEnhancementHighlightsDto loadReadOnlyEnhancements(
            Stock stock, LocalDate resolvedReportDate, BigDecimal latestClose,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<InvestmentSummaryDto> summary,
            MetricResult<MarketContextDto> marketResult,
            boolean isEtf) {
        if (optionsEnhancementService == null && insiderAndInstitutionalService == null && shortInterestService == null) {
            return null;
        }
        CreditSpreadSummaryDto creditSpread = marketResult != null && marketResult.status() == MetricStatus.AVAILABLE
                && marketResult.value() != null ? marketResult.value().creditSpread() : null;
        BigDecimal activeStop = extractActiveProtectiveStop(tradePlans);
        OptionsSummaryDto options = optionsEnhancementService == null ? null
                : optionsEnhancementService.readAt(stock, resolvedReportDate, activeStop, latestClose);
        String verdict = summary != null && summary.status() == MetricStatus.AVAILABLE && summary.value() != null
                ? summary.value().verdict() : null;
        String fundamentalBacking = summary != null && summary.status() == MetricStatus.AVAILABLE && summary.value() != null
                ? summary.value().fundamentalBacking() : null;
        InsiderSummaryDto insiders = (!isEtf && insiderAndInstitutionalService != null)
                ? insiderAndInstitutionalService.evaluateInsiders(stock, resolvedReportDate, verdict) : null;
        boolean resistanceBreakout = isResistanceBreakout(summary);
        ShortInterestSummaryDto shortInterest = shortInterestService == null ? null
                : shortInterestService.evaluateStock(stock, resolvedReportDate, fundamentalBacking, resistanceBreakout);
        return new ReportEnhancementHighlightsDto(
                creditSpread,
                options,
                insiders != null && insiders.insiderBuyUndervaluedConfirm(),
                insiders == null ? 0 : insiders.codePBuyCount60d(),
                insiders == null ? 0 : insiders.codePDistinctInsiders60d(),
                insiders == null ? null : insiders.codePTotalValueUsd60d(),
                shortInterest
        );
    }

    private BigDecimal extractActiveProtectiveStop(MetricResult<TradePlanResultDto> tradePlans) {
        if (tradePlans == null || tradePlans.status() != MetricStatus.AVAILABLE || tradePlans.value() == null) {
            return null;
        }
        for (TradePlanDto plan : tradePlans.value().plans()) {
            if (plan != null && plan.stop() != null && plan.stop().protectivePrice() != null
                    && plan.stop().protectivePrice().signum() > 0
                    && (plan.status() == TradePlanStatus.ACTIVE || plan.status() == TradePlanStatus.WAITING_TRIGGER)) {
                return plan.stop().protectivePrice();
            }
        }
        return null;
    }

    private boolean isResistanceBreakout(MetricResult<InvestmentSummaryDto> summary) {
        if (summary == null || summary.status() != MetricStatus.AVAILABLE || summary.value() == null) {
            return false;
        }
        InvestmentSummaryDto v = summary.value();
        boolean trendStrong = "UPTREND".equalsIgnoreCase(v.weeklyTrend()) && Boolean.TRUE.equals(v.aboveSma200());
        boolean confirmedBreakout = "CONFIRMED_RETEST".equalsIgnoreCase(v.rightSideConfirmationBasis())
                || "SPRING_RECLAIMED".equalsIgnoreCase(v.rightSideConfirmationBasis())
                || (v.trendPullbackStarter() != null && v.trendPullbackStarter().eligible());
        return trendStrong && confirmedBreakout;
    }

    private DcfValuationDto evaluateDcf(
            Stock stock, LocalDate resolvedReportDate, BigDecimal latestPriceClose,
            ReportValuationBundle bundle, FutuObservationsDto observations, MetricResult<MarketContextDto> marketResult) {
        if (bundle == null) {
            return DcfValuationDto.unavailable(MetricStatus.INSUFFICIENT_DATA.name(), "NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE",
                    resolvedReportDate, stock.getCurrency(), latestPriceClose);
        }
        StockFinancialPeriod matched = StockValuationService.matchPeriod(bundle.storedPeriods(), resolvedReportDate);
        BigDecimal fxMult = bundle.fxRateToQuote() != null && bundle.fxRateToQuote().signum() > 0
                ? bundle.fxRateToQuote() : BigDecimal.ONE;
        BigDecimal fcfUsd = null;
        if (matched != null && MetricStatus.AVAILABLE.name().equals(matched.getFreeCashFlowTtmStatus())
                && matched.getFreeCashFlowTtm() != null) {
            fcfUsd = matched.getFreeCashFlowTtm().multiply(fxMult, java.math.MathContext.DECIMAL128);
        }
        FundamentalSnapshotDto fundamentals = bundle.fundamentals();
        BigDecimal adjFcfUsd = null;
        if (fundamentals != null && fundamentals.adjFcfTtm() != null
                && fundamentals.adjFcfTtm().status() == MetricStatus.AVAILABLE
                && fundamentals.adjFcfTtm().value() != null) {
            adjFcfUsd = fundamentals.adjFcfTtm().value().multiply(fxMult, java.math.MathContext.DECIMAL128);
        }
        BigDecimal tenYearPct = null;
        LocalDate tenYearDate = null;
        if (marketResult != null && marketResult.status() == MetricStatus.AVAILABLE && marketResult.value() != null
                && marketResult.value().treasury() != null && marketResult.value().treasury().tenYear() != null
                && marketResult.value().treasury().tenYear().status() == MetricStatus.AVAILABLE) {
            tenYearPct = marketResult.value().treasury().tenYear().value();
            SourceFreshnessDto tenYearFresh = marketResult.value().sourceFreshness() == null
                    ? null : marketResult.value().sourceFreshness().get("TREASURY_10Y");
            tenYearDate = tenYearFresh != null && tenYearFresh.dataDate() != null
                    ? tenYearFresh.dataDate() : resolvedReportDate;
        } else if ((marketResult == null || marketResult.status() != MetricStatus.AVAILABLE)
                && ((fcfUsd != null && fcfUsd.signum() > 0) || (adjFcfUsd != null && adjFcfUsd.signum() > 0))) {
            MarketContextService.TreasuryRateObservation directTenYear = market.resolveLatestTenYearYield(resolvedReportDate);
            if (directTenYear != null) {
                tenYearPct = directTenYear.tenYearPct();
                tenYearDate = directTenYear.observationDate();
            }
        }
        BigDecimal revCagr3y = extractMetricValue(fundamentals == null ? null : fundamentals.revenueCagr3y());
        BigDecimal fcfCagr3y = extractMetricValue(fundamentals == null ? null : fundamentals.fcfCagr3y());
        BigDecimal forwardGrowth = observations != null && observations.forward() != null
                ? extractMetricValue(observations.forward().impliedEpsGrowth()) : null;
        boolean reit = SecurityTypePolicy.isReitType(stock.getStockType())
                || (bundle.valuation() != null && "REIT".equalsIgnoreCase(bundle.valuation().modelCode()));
        BigDecimal priceForDcf = bundle.closePriceAtDay() != null ? bundle.closePriceAtDay() : latestPriceClose;
        BigDecimal dcfMarketCap = bundle.dcfMarketCapAtDay() != null ? bundle.dcfMarketCapAtDay() : bundle.marketCapAtDay();

        return dcfCalculator.calculate(new DcfValuationCalculator.Inputs(
                resolvedReportDate,
                stock.getCurrency(),
                priceForDcf,
                dcfMarketCap,
                bundle.sharesAtDay(),
                bundle.financialInstitution(),
                reit,
                false,
                bundle.valuationBlockedReason(),
                fcfUsd,
                adjFcfUsd,
                tenYearPct,
                tenYearDate,
                revCagr3y,
                fcfCagr3y,
                forwardGrowth
        ));
    }

    private static BigDecimal extractMetricValue(MetricResult<BigDecimal> metric) {
        return metric != null && metric.status() == MetricStatus.AVAILABLE ? metric.value() : null;
    }

    private record SizingAttachment(MetricResult<TradePlanResultDto> tradePlans, PositionSizingDto starterPositionSizing) {}

    private SizingAttachment attachPositionSizing(Stock stock,
                                                  String industryCode,
                                                  LocalDate resolvedReportDate,
                                                  Instant readAt,
                                                  boolean historicalQuery,
                                                  boolean quoteFellBack,
                                                  boolean sectorBreadthBreakdown,
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
                        sectorBreadthBreakdown,
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
                    sectorBreadthBreakdown,
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
