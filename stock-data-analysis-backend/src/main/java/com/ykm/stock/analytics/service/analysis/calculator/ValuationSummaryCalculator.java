package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosis;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EventRiskDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.FairValueBandDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.MacroCautionDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.SummaryFlagDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.TrendPullbackStarterDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.WatchItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRangeDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.service.market.MarketStatusService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 單軌客觀市場參考帶 [L, M, H] 估值位置、基本面支撐度與技術／事件四維合流觀察傾向計算器。
 * 嚴格遵守：
 * 1. [L, M, H] 僅由客觀市場錨點（HISTORICAL_MARKET、PEER_MARKET）合成，絕不混入 INDUSTRY_ASSUMPTION。
 * 2. 美債 10Y 殖利率（百分比數值如 4.35）先除以 100 轉小數比例（0.0435）再計算利差。
 * 3. 與 TradePlanResultDto 止損跌穿／待收復狀態及財報事件鎖合流一致，不產生買賣指令。
 */
public class ValuationSummaryCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DEEP_DISCOUNT_FACTOR = new BigDecimal("0.90");
    private static final BigDecimal OVERVALUED_RISK_FACTOR = new BigDecimal("1.15");
    private static final BigDecimal MIN_SUPPORT_STRENGTH = BigDecimal.valueOf(30);
    private static final BigDecimal HALF_ATR = new BigDecimal("0.50");
    private static final BigDecimal QUARTER_ATR = new BigDecimal("0.25");

    private static final BigDecimal WEAK_REV_YOY = new BigDecimal("-0.10");
    private static final BigDecimal WEAK_EPS_YOY = new BigDecimal("-0.20");
    private static final BigDecimal HIGH_GROWTH_REV_EXEMPTION = new BigDecimal("0.20");
    private static final BigDecimal WEAK_DEBT_EBITDA = new BigDecimal("4.0");
    private static final BigDecimal STRONG_MAX_DEBT_EBITDA = new BigDecimal("3.0");
    private static final BigDecimal STRONG_FCF_MARGIN = new BigDecimal("0.08");
    private static final BigDecimal STRONG_NON_FIN_ROE = new BigDecimal("0.12");
    private static final BigDecimal STRONG_FIN_ROE = new BigDecimal("0.10");
    private static final BigDecimal WEAK_FIN_ROE = new BigDecimal("0.04");
    private static final BigDecimal STRONG_PEG_MAX = new BigDecimal("1.20");
    private static final BigDecimal ANCHOR_DIVERGENCE_THRESHOLD = new BigDecimal("0.35");
    private static final BigDecimal SHARE_DILUTION_WARN = new BigDecimal("0.03");
    private static final BigDecimal REV_CONTRACTION_WARN = new BigDecimal("-0.05");
    private static final BigDecimal FORWARD_EPS_TRAP_CONTRACTION = new BigDecimal("-0.20");
    private static final BigDecimal FORWARD_REVISION_90D_TRAP = new BigDecimal("-0.10");
    private static final BigDecimal VIX_CAUTION_THRESHOLD = new BigDecimal("25");
    private static final BigDecimal TEN_YEAR_SURGE_20_THRESHOLD = new BigDecimal("0.30");
    private static final BigDecimal MACRO_MARGIN_BUFFER_MIN = new BigDecimal("0.05");
    private static final BigDecimal MACRO_MARGIN_BUFFER_MAX = new BigDecimal("0.10");
    private static final BigDecimal MACRO_POSITION_SCALE = new BigDecimal("0.50");
    private static final BigDecimal STARTER_FORWARD_PEG_MAX = new BigDecimal("1.50");
    private static final BigDecimal STARTER_HYPER_GROWTH_PE_MAX = new BigDecimal("35.0");
    private static final BigDecimal STARTER_HYPER_GROWTH_MIN_REV_YOY = new BigDecimal("0.15");
    private static final BigDecimal STARTER_PULLBACK_DIST_MIN = new BigDecimal("-0.01");
    private static final BigDecimal STARTER_PULLBACK_DIST_MAX = new BigDecimal("0.03");
    private static final BigDecimal STARTER_RVOL_MAX = BigDecimal.ONE;
    private static final BigDecimal STARTER_DAY_RET_MIN = new BigDecimal("-0.015");
    private static final BigDecimal STARTER_CLOSE_POS_MIN = new BigDecimal("0.40");
    private static final int STARTER_PULLBACK_HIGH_LOOKBACK = 10;
    private static final int RETEST_VALID_SESSIONS = 5;
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private final MarketStatusService marketCalendar = new MarketStatusService(null);

    private record YieldSpreadSelection(MetricResult<BigDecimal> spread, String basis) {}

    private record SupportSelection(LevelZoneDto zone, boolean excludedByBreachOrInvalidation) {}

    private record BandSynthesis(String anchorSource, FairValueBandDto band, List<AnalysisReasonDto> anchorReasons) {}

    private record ForwardTrapSignals(boolean earningsContraction, boolean swungToLoss, BigDecimal impliedGrowth,
                                      String targetFiscalPeriod, boolean downgrade90d, BigDecimal revision90d,
                                      String revisionFiscalPeriod, String revisionPeriodType) {
        boolean anyTrap() {
            return earningsContraction || downgrade90d;
        }
    }

    /** 客觀市場參考帶 [L, M, H] 與估值位置結論（供最新估值快照刷新、自訂同業與單股摘要共用同一份邏輯）。 */
    public record ObjectiveBandVerdict(
            String verdict,
            String anchorSource,
            BigDecimal low,
            BigDecimal mid,
            BigDecimal high
    ) {}

    /** 每日機會雷達精算結果：與單股 InvestmentSummaryDto 共用同一套 stance、支撐選取、真假破位與強勢回踩試單判定。 */
    public record RadarEvaluationResult(
            String verdict,
            String fundamentalBacking,
            String stance,
            boolean forwardValueTrap,
            LevelZoneDto supportZone,
            boolean stopBreachedOrReclaim,
            boolean highRiskBreakdown,
            boolean confirmationPending,
            MarketRegime marketRegime,
            MacroCautionDto macroCaution,
            TrendPullbackStarterDto trendPullbackStarter
    ) {}

    /** 供最新估值快照批次刷新、自訂對標更新與個股報告共用之純記憶體客觀市場參考帶與結論判定。 */
    public static ObjectiveBandVerdict computeObjectiveBandAndVerdict(BigDecimal price, ValuationReferenceDto hist, ValuationReferenceDto peer) {
        boolean histValid = isAnchorValidStatic(hist);
        boolean peerValid = isAnchorValidStatic(peer);
        BigDecimal low = null;
        BigDecimal mid = null;
        BigDecimal high = null;
        String anchorSource = "NONE";
        if (histValid && peerValid) {
            low = hist.priceLow().add(peer.priceLow(), MC).divide(TWO, 4, RoundingMode.HALF_UP);
            mid = hist.priceMedian().add(peer.priceMedian(), MC).divide(TWO, 4, RoundingMode.HALF_UP);
            high = hist.priceHigh().add(peer.priceHigh(), MC).divide(TWO, 4, RoundingMode.HALF_UP);
            anchorSource = "HISTORICAL_AND_PEER";
        } else if (histValid) {
            low = hist.priceLow();
            mid = hist.priceMedian();
            high = hist.priceHigh();
            anchorSource = "HISTORICAL_ONLY";
        } else if (peerValid) {
            low = peer.priceLow();
            mid = peer.priceMedian();
            high = peer.priceHigh();
            anchorSource = "PEER_ONLY";
        }
        String verdict = classifyPriceAgainstBand(price, low, mid, high);
        return new ObjectiveBandVerdict(verdict, anchorSource, low, mid, high);
    }

    /** 供最新估值快照批次刷新與個股報告共用之純記憶體客觀市場參考帶結論判定（確保選股器與個股頁 verdict 完全一致）。 */
    public static String computeObjectiveVerdict(BigDecimal price, ValuationReferenceDto hist, ValuationReferenceDto peer) {
        return computeObjectiveBandAndVerdict(price, hist, peer).verdict();
    }

    private static String classifyPriceAgainstBand(BigDecimal price, BigDecimal low, BigDecimal mid, BigDecimal high) {
        if (price == null || price.signum() <= 0 || low == null || mid == null || high == null || mid.signum() <= 0) {
            return "INSUFFICIENT_EVIDENCE";
        }
        BigDecimal deepFloor = low.multiply(DEEP_DISCOUNT_FACTOR, MC);
        BigDecimal riskCeiling = high.multiply(OVERVALUED_RISK_FACTOR, MC);
        if (price.compareTo(deepFloor) < 0) {
            return "DEEP_UNDERVALUED";
        }
        if (price.compareTo(low) <= 0) {
            return "MODERATELY_UNDERVALUED";
        }
        if (price.compareTo(mid) <= 0) {
            return "FAIR_LOWER";
        }
        if (price.compareTo(high) <= 0) {
            return "FAIR_UPPER";
        }
        if (price.compareTo(riskCeiling) <= 0) {
            return "MODERATELY_OVERVALUED";
        }
        return "OVERVALUED_RISK";
    }

    public MetricResult<InvestmentSummaryDto> calculate(
            ValuationResultDto valuation,
            FinancialsDto financials,
            FundamentalSnapshotDto fundamentals,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<SupportResistanceDto> supportResistance,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketContext) {
        return calculate(AnalysisHorizon.POSITION, valuation, financials, fundamentals, null,
                priceStructure, supportResistance, tradePlans, marketContext);
    }

    public MetricResult<InvestmentSummaryDto> calculate(
            AnalysisHorizon horizon,
            ValuationResultDto valuation,
            FinancialsDto financials,
            FundamentalSnapshotDto fundamentals,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<SupportResistanceDto> supportResistance,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketContext) {
        return calculate(horizon, valuation, financials, fundamentals, null,
                priceStructure, supportResistance, tradePlans, marketContext);
    }

    /** 專供 ETF／ETP 產出純技術與資金面綜合摘要（估值與基本面標示 NOT_APPLICABLE_FOR_ETF，保留大盤逆風、真假破位與支撐阻力觀察）。 */
    public MetricResult<InvestmentSummaryDto> calculateForEtf(
            AnalysisHorizon horizon,
            BigDecimal currentPrice,
            String currency,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<SupportResistanceDto> supportResistance,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketContext) {
        AnalysisHorizon effectiveHorizon = horizon == null ? AnalysisHorizon.POSITION : horizon;
        boolean structureAvailable = priceStructure != null && priceStructure.status() == MetricStatus.AVAILABLE && priceStructure.value() != null;
        boolean supportAvailable = supportResistance != null && supportResistance.status() == MetricStatus.AVAILABLE && supportResistance.value() != null;
        if (!structureAvailable && !supportAvailable) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("TECHNICAL_PRICE_MISSING", "SUMMARY", "缺少有效技術結構與支撐阻力資料，暫不產生 ETF 技術摘要。"));
        }
        String weeklyTrend = extractWeeklyMajorTrend(priceStructure);
        List<TradePlanDto> planList = tradePlans != null && tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();
        List<NormalizedBarDto> dailyBars = extractDailyBars(priceStructure);
        BigDecimal dailyLatestClose = extractLatestDailyClose(priceStructure);
        if (dailyLatestClose == null) {
            dailyLatestClose = dailyBars.isEmpty() ? currentPrice : dailyBars.getLast().close();
        }
        BigDecimal sma200 = extractSma200(marketContext);
        Boolean aboveSma200 = dailyLatestClose != null && sma200 != null ? dailyLatestClose.compareTo(sma200) >= 0 : null;
        SupportSelection supportSelection = selectTechnicalSupportZone(
                effectiveHorizon, supportResistance, planList, dailyLatestClose, dailyBars, currentPrice, null);
        LevelZoneDto supportZone = supportSelection.zone();
        PlanConstraintDto constraint = extractConstraint(marketContext);
        EntryPermission permission = constraint != null ? constraint.entryPermission() : null;
        MarketRegime marketRegime = extractMarketRegime(marketContext);
        BigDecimal vixClose = extractVixClose(marketContext);
        BigDecimal tenYearChange20 = extractTenYearChange20(marketContext);
        MacroCautionDto macroCaution = buildMacroCaution(marketRegime, vixClose, tenYearChange20);
        BreakoutDiagnosisDto primaryDiagnosis = extractPrimaryDiagnosis(supportResistance);
        BreakoutDiagnosisDto matchedZoneDiagnosis = findDiagnosisForZone(supportResistance, supportZone);
        BreakoutDiagnosisDto breakoutDiagnosis = matchedZoneDiagnosis != null ? matchedZoneDiagnosis : primaryDiagnosis;
        boolean highRiskBreakdown = (matchedZoneDiagnosis != null && matchedZoneDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN)
                || (supportZone == null && primaryDiagnosis != null && primaryDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN);
        boolean stopBreachedOrReclaim = supportSelection.excludedByBreachOrInvalidation()
                || highRiskBreakdown
                || hasStopBreachedOrReclaim(planList, supportZone, dailyLatestClose, dailyBars);

        String stance;
        if (marketRegime == MarketRegime.BEAR || marketRegime == MarketRegime.PANIC) {
            stance = "WAIT_FOR_MARKET_STABILIZATION";
        } else if (stopBreachedOrReclaim || permission == EntryPermission.BLOCK) {
            stance = "WAIT_FOR_STABILIZATION";
        } else if ("UPTREND".equals(weeklyTrend) && Boolean.TRUE.equals(aboveSma200) && supportZone != null) {
            stance = "ETF_TECHNICAL_BULLISH_SUPPORT";
        } else if ("DOWNTREND".equals(weeklyTrend) && Boolean.FALSE.equals(aboveSma200)) {
            stance = "WAIT_FOR_STABILIZATION";
        } else {
            stance = "ETF_TECHNICAL_NEUTRAL";
        }

        List<SummaryFlagDto> risks = new ArrayList<>();
        if (macroCaution != null && macroCaution.marketRegime() != null
                && (macroCaution.marketRegime() == MarketRegime.PANIC
                || macroCaution.marketRegime() == MarketRegime.BEAR
                || macroCaution.marketRegime() == MarketRegime.CORRECTION)) {
            String sev = macroCaution.marketRegime() == MarketRegime.CORRECTION ? "MEDIUM" : "HIGH";
            risks.add(new SummaryFlagDto("MARKET_REGIME_HEADWIND", sev, Map.of("regime", macroCaution.marketRegime().name())));
        }
        if (macroCaution != null && (macroCaution.triggerCodes().contains("VIX_ELEVATED")
                || macroCaution.triggerCodes().contains("TREASURY_10Y_SURGE_20P"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (macroCaution.vixClose() != null) params.put("vixClose", macroCaution.vixClose());
            if (macroCaution.tenYearChange20() != null) params.put("tenYearChange20", macroCaution.tenYearChange20());
            risks.add(new SummaryFlagDto("MACRO_VOLATILITY_OR_RATE_STRESS", "MEDIUM", params));
        }
        if (highRiskBreakdown) {
            risks.add(new SummaryFlagDto("SUPPORT_HIGH_RISK_BREAKDOWN", "HIGH", Map.of()));
        }
        if (stopBreachedOrReclaim) {
            risks.add(new SummaryFlagDto("PROTECTIVE_STOP_OR_RECLAIM_PENDING", "HIGH", Map.of()));
        }
        if ("DOWNTREND".equals(weeklyTrend) && Boolean.FALSE.equals(aboveSma200)) {
            risks.add(new SummaryFlagDto("BELOW_SMA200_DOWNTREND", "MEDIUM", Map.of()));
        }

        List<WatchItemDto> watchItems = new ArrayList<>();
        if (supportZone != null && supportZone.lower() != null && supportZone.upper() != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("lower", supportZone.lower());
            params.put("upper", supportZone.upper());
            if (supportZone.timeframe() != null) params.put("timeframe", supportZone.timeframe().name());
            if (currency != null) params.put("currency", currency);
            watchItems.add(new WatchItemDto("WATCH_TECHNICAL_SUPPORT", params));
        }

        PriceRangeDto supportRange = supportZone == null ? null : new PriceRangeDto(supportZone.lower(), supportZone.upper(), null);
        InvestmentSummaryDto dto = new InvestmentSummaryDto(
                "NOT_APPLICABLE_FOR_ETF",
                "NOT_APPLICABLE",
                stance,
                null,
                "ETF_TECHNICAL_ONLY",
                null,
                null,
                null,
                null,
                null,
                supportZone == null ? null : supportZone.id(),
                supportRange,
                supportZone == null || supportZone.timeframe() == null ? null : supportZone.timeframe().name(),
                supportZone == null ? null : supportZone.effectiveStrength(),
                weeklyTrend,
                aboveSma200,
                permission == null ? null : permission.name(),
                false,
                null,
                List.copyOf(risks),
                List.copyOf(watchItems),
                List.of(AnalysisReasonDto.of("NOT_APPLICABLE_FOR_ETF", "SUMMARY", "ETF／ETP 不適用單一公司財報估值模型，僅提供技術結構、資金流與交易計畫。")),
                macroCaution,
                null,
                breakoutDiagnosis,
                null
        );
        return MetricResult.available(dto);
    }

    public MetricResult<InvestmentSummaryDto> calculate(
            AnalysisHorizon horizon,
            ValuationResultDto valuation,
            FinancialsDto financials,
            FundamentalSnapshotDto fundamentals,
            FutuObservationsDto futuObservations,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<SupportResistanceDto> supportResistance,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketContext) {
        if (valuation == null) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("VALUATION_NOT_CALCULATED", "SUMMARY", "尚無估值結果，無法產生綜合估值觀察摘要。"));
        }

        BandSynthesis synthesis = synthesizeObjectiveBand(valuation);
        FairValueBandDto band = synthesis.band();
        BigDecimal price = valuation.price();

        String verdict;
        BigDecimal discountToMid = null;
        BigDecimal upsideToMid = null;
        List<AnalysisReasonDto> summaryReasons = new ArrayList<>(synthesis.anchorReasons());

        if (price == null || price.signum() <= 0) {
            verdict = "INSUFFICIENT_EVIDENCE";
            addUniqueReason(summaryReasons, valuation.reasonCode() != null ? valuation.reasonCode() : "CLOSING_PRICE_MISSING",
                    "SUMMARY", "缺少有效收盤價，無法判定估值區間位置。");
        } else if (band.low() == null || band.mid() == null || band.high() == null || band.mid().signum() <= 0) {
            verdict = "INSUFFICIENT_EVIDENCE";
            if (valuation.reasonCode() != null) {
                addUniqueReason(summaryReasons, valuation.reasonCode(), "SUMMARY", "估值核心輸入不足，暫不產生價格參考帶結論。");
            }
        } else {
            discountToMid = band.mid().subtract(price, MC).divide(band.mid(), 6, RoundingMode.HALF_UP);
            upsideToMid = band.mid().subtract(price, MC).divide(price, 6, RoundingMode.HALF_UP);
            verdict = classifyPriceAgainstBand(price, band.low(), band.mid(), band.high());
        }

        String fundamentalBacking = evaluateFundamentalBacking(fundamentals, financials);
        ForwardTrapSignals forwardTrap = extractForwardTrapSignals(futuObservations);
        YieldSpreadSelection yieldSpread = computeYieldSpreadVs10y(fundamentals, marketContext);

        String weeklyTrend = extractWeeklyMajorTrend(priceStructure);
        BigDecimal dailyLatestClose = extractLatestDailyClose(priceStructure);
        BigDecimal sma200 = extractSma200(marketContext);
        Boolean aboveSma200 = dailyLatestClose != null && sma200 != null ? dailyLatestClose.compareTo(sma200) >= 0 : null;

        PlanConstraintDto constraint = extractConstraint(marketContext);
        EntryPermission permission = constraint != null ? constraint.entryPermission() : null;
        LocalDate nextBlockingEventDate = extractNextBlockingEventDate(marketContext);
        // 僅當存在實際封鎖新入場之事件或明確回傳 EARNINGS_EVENT_WINDOW 封鎖時標記 eventGateBlocked（排除 NO_KNOWN_BLOCKING_EVENT）
        boolean eventGateBlocked = nextBlockingEventDate != null
                || (constraint != null && constraint.entryPermission() == EntryPermission.BLOCK
                && constraint.reasonCodes().contains("EARNINGS_EVENT_WINDOW"));

        List<NormalizedBarDto> dailyBars = extractDailyBars(priceStructure);
        List<TradePlanDto> planList = tradePlans != null && tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();
        AnalysisHorizon effectiveHorizon = horizon == null ? AnalysisHorizon.POSITION : horizon;
        SupportSelection supportSelection = selectTechnicalSupportZone(effectiveHorizon, supportResistance, planList, dailyLatestClose, dailyBars, price, band.low());
        LevelZoneDto supportZone = supportSelection.zone();
        boolean stopBreachedOrReclaim = supportSelection.excludedByBreachOrInvalidation()
                || hasStopBreachedOrReclaim(planList, supportZone, dailyLatestClose, dailyBars);
        boolean confirmationPending = hasConfirmationPending(planList, supportZone, permission);
        boolean structureConfirmationPending = hasStructureConfirmationPending(planList, supportZone);

        MarketRegime marketRegime = extractMarketRegime(marketContext);
        BigDecimal vixClose = extractVixClose(marketContext);
        BigDecimal tenYearChange20 = extractTenYearChange20(marketContext);
        MacroCautionDto macroCaution = buildMacroCaution(marketRegime, vixClose, tenYearChange20);

        BreakoutDiagnosisDto primaryDiagnosis = extractPrimaryDiagnosis(supportResistance);
        BreakoutDiagnosisDto matchedZoneDiagnosis = findDiagnosisForZone(supportResistance, supportZone);
        boolean matchedHighRiskBreakdown = matchedZoneDiagnosis != null
                && matchedZoneDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN;
        boolean anyHighRiskBreakdown = matchedHighRiskBreakdown
                || (supportZone == null && primaryDiagnosis != null
                && primaryDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN);

        LocalDate currentSession = extractLatestDailySession(priceStructure);
        boolean validConfirmedRetest = isValidConfirmedRetest(supportZone, planList, currentSession);
        boolean validSpringReclaimed = matchedZoneDiagnosis != null
                && matchedZoneDiagnosis.diagnosis() == BreakoutDiagnosis.SPRING_RECLAIMED
                && matchedZoneDiagnosis.reclaimDate() != null
                && (matchedZoneDiagnosis.validThroughSession() == null || currentSession == null
                || !currentSession.isAfter(matchedZoneDiagnosis.validThroughSession()))
                && !isPlanInvalidated(planList, supportZone)
                && !hasTwoConsecutiveClosesBelowFrozenThreshold(supportZone, dailyBars);
        boolean macroConfirmRequired = constraint != null
                && constraint.reasonCodes().contains("EVENT_REQUIRES_CLOSE_CONFIRMATION");
        String rightSideConfirmationBasis = validConfirmedRetest ? "CONFIRMED_RETEST"
                : (validSpringReclaimed && !macroConfirmRequired ? "SPRING_RECLAIMED" : null);
        boolean rightSideConfirmedForCorrection = rightSideConfirmationBasis != null && !structureConfirmationPending;

        TrendPullbackStarterDto trendPullbackStarter = evaluateTrendPullbackStarter(
                effectiveHorizon, verdict, fundamentalBacking, fundamentals, futuObservations, forwardTrap,
                weeklyTrend, aboveSma200, marketRegime, permission, eventGateBlocked,
                stopBreachedOrReclaim || anyHighRiskBreakdown, priceStructure, marketContext, macroCaution);

        String stance = resolveStance(
                verdict, fundamentalBacking, forwardTrap.anyTrap(), weeklyTrend, aboveSma200,
                marketRegime, permission, eventGateBlocked, stopBreachedOrReclaim || anyHighRiskBreakdown,
                confirmationPending, supportZone != null, rightSideConfirmedForCorrection, trendPullbackStarter);

        List<SummaryFlagDto> risks = buildRiskFlags(
                verdict, fundamentals, financials, forwardTrap, band, weeklyTrend, aboveSma200,
                eventGateBlocked, nextBlockingEventDate, stopBreachedOrReclaim, macroCaution, primaryDiagnosis);
        List<WatchItemDto> watchItems = buildWatchItems(
                verdict, stance, fundamentalBacking, forwardTrap.anyTrap(), band, supportZone,
                nextBlockingEventDate, stopBreachedOrReclaim, marketRegime, rightSideConfirmationBasis, trendPullbackStarter);

        PriceRangeDto supportRange = supportZone == null ? null : new PriceRangeDto(supportZone.lower(), supportZone.upper(), null);
        String supportTf = supportZone == null || supportZone.timeframe() == null ? null : supportZone.timeframe().name();

        InvestmentSummaryDto dto = new InvestmentSummaryDto(
                verdict,
                fundamentalBacking,
                stance,
                valuation.primaryMetric(),
                synthesis.anchorSource(),
                band,
                discountToMid,
                upsideToMid,
                yieldSpread.spread(),
                yieldSpread.basis(),
                supportZone == null ? null : supportZone.id(),
                supportRange,
                supportTf,
                supportZone == null ? null : supportZone.effectiveStrength(),
                weeklyTrend,
                aboveSma200,
                permission == null ? null : permission.name(),
                eventGateBlocked,
                nextBlockingEventDate,
                risks,
                watchItems,
                summaryReasons,
                macroCaution,
                trendPullbackStarter,
                primaryDiagnosis,
                marketRegime == MarketRegime.CORRECTION && "WATCH_PULLBACK_TO_BAND".equals(stance) ? rightSideConfirmationBasis : null
        );
        return MetricResult.available(dto);
    }

    /**
     * 供每日機會雷達第二階段使用：以估值快照已合成之客觀參考帶 [L, M, H]、verdict 與 fundamentalBacking，
     * 共用同一套前瞻陷阱、技術支撐選取、真假破位診斷、大盤逆風降級與強勢股回踩試單判定，確保雷達與單股頁口徑 100% 一致。
     */
    public RadarEvaluationResult evaluateForRadar(
            AnalysisHorizon horizon,
            BigDecimal currentPrice,
            String verdict,
            BigDecimal bandLow,
            String fundamentalBacking,
            FundamentalSnapshotDto fundamentals,
            FutuObservationsDto futuObservations,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<SupportResistanceDto> supportResistance,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketContext) {
        String effectiveVerdict = verdict == null || verdict.isBlank() ? "INSUFFICIENT_EVIDENCE" : verdict;
        String effectiveBacking = fundamentalBacking == null || fundamentalBacking.isBlank()
                ? "INSUFFICIENT_DATA" : fundamentalBacking;
        ForwardTrapSignals forwardTrap = extractForwardTrapSignals(futuObservations);

        String weeklyTrend = extractWeeklyMajorTrend(priceStructure);
        BigDecimal dailyLatestClose = extractLatestDailyClose(priceStructure);
        BigDecimal sma200 = extractSma200(marketContext);
        Boolean aboveSma200 = dailyLatestClose != null && sma200 != null ? dailyLatestClose.compareTo(sma200) >= 0 : null;

        PlanConstraintDto constraint = extractConstraint(marketContext);
        EntryPermission permission = constraint != null ? constraint.entryPermission() : null;
        LocalDate nextBlockingEventDate = extractNextBlockingEventDate(marketContext);
        boolean eventGateBlocked = nextBlockingEventDate != null
                || (constraint != null && constraint.entryPermission() == EntryPermission.BLOCK
                && constraint.reasonCodes().contains("EARNINGS_EVENT_WINDOW"));

        List<NormalizedBarDto> dailyBars = extractDailyBars(priceStructure);
        List<TradePlanDto> planList = tradePlans != null && tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();
        AnalysisHorizon effectiveHorizon = horizon == null ? AnalysisHorizon.POSITION : horizon;
        SupportSelection supportSelection = selectTechnicalSupportZone(
                effectiveHorizon, supportResistance, planList, dailyLatestClose, dailyBars, currentPrice, bandLow);
        LevelZoneDto supportZone = supportSelection.zone();
        boolean stopBreachedOrReclaim = supportSelection.excludedByBreachOrInvalidation()
                || hasStopBreachedOrReclaim(planList, supportZone, dailyLatestClose, dailyBars);
        boolean confirmationPending = hasConfirmationPending(planList, supportZone, permission);
        boolean structureConfirmationPending = hasStructureConfirmationPending(planList, supportZone);

        MarketRegime marketRegime = extractMarketRegime(marketContext);
        BigDecimal vixClose = extractVixClose(marketContext);
        BigDecimal tenYearChange20 = extractTenYearChange20(marketContext);
        MacroCautionDto macroCaution = buildMacroCaution(marketRegime, vixClose, tenYearChange20);

        BreakoutDiagnosisDto primaryDiagnosis = extractPrimaryDiagnosis(supportResistance);
        BreakoutDiagnosisDto matchedZoneDiagnosis = findDiagnosisForZone(supportResistance, supportZone);
        boolean matchedHighRiskBreakdown = matchedZoneDiagnosis != null
                && matchedZoneDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN;
        boolean anyHighRiskBreakdown = matchedHighRiskBreakdown
                || (supportZone == null && primaryDiagnosis != null
                && primaryDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN);

        LocalDate currentSession = extractLatestDailySession(priceStructure);
        boolean validConfirmedRetest = isValidConfirmedRetest(supportZone, planList, currentSession);
        boolean validSpringReclaimed = matchedZoneDiagnosis != null
                && matchedZoneDiagnosis.diagnosis() == BreakoutDiagnosis.SPRING_RECLAIMED
                && matchedZoneDiagnosis.reclaimDate() != null
                && (matchedZoneDiagnosis.validThroughSession() == null || currentSession == null
                || !currentSession.isAfter(matchedZoneDiagnosis.validThroughSession()))
                && !isPlanInvalidated(planList, supportZone)
                && !hasTwoConsecutiveClosesBelowFrozenThreshold(supportZone, dailyBars);
        boolean macroConfirmRequired = constraint != null
                && constraint.reasonCodes().contains("EVENT_REQUIRES_CLOSE_CONFIRMATION");
        String rightSideConfirmationBasis = validConfirmedRetest ? "CONFIRMED_RETEST"
                : (validSpringReclaimed && !macroConfirmRequired ? "SPRING_RECLAIMED" : null);
        boolean rightSideConfirmedForCorrection = rightSideConfirmationBasis != null && !structureConfirmationPending;

        TrendPullbackStarterDto trendPullbackStarter = evaluateTrendPullbackStarter(
                effectiveHorizon, effectiveVerdict, effectiveBacking, fundamentals, futuObservations, forwardTrap,
                weeklyTrend, aboveSma200, marketRegime, permission, eventGateBlocked,
                stopBreachedOrReclaim || anyHighRiskBreakdown, priceStructure, marketContext, macroCaution);

        String stance = resolveStance(
                effectiveVerdict, effectiveBacking, forwardTrap.anyTrap(), weeklyTrend, aboveSma200,
                marketRegime, permission, eventGateBlocked, stopBreachedOrReclaim || anyHighRiskBreakdown,
                confirmationPending, supportZone != null, rightSideConfirmedForCorrection, trendPullbackStarter);

        return new RadarEvaluationResult(
                effectiveVerdict,
                effectiveBacking,
                stance,
                forwardTrap.anyTrap(),
                supportZone,
                stopBreachedOrReclaim,
                anyHighRiskBreakdown,
                confirmationPending,
                marketRegime,
                macroCaution,
                trendPullbackStarter
        );
    }

    private BandSynthesis synthesizeObjectiveBand(ValuationResultDto valuation) {
        ValuationReferenceDto hist = valuation.historicalReference();
        ValuationReferenceDto peer = valuation.peerReference();
        ValuationReferenceDto assumption = valuation.industryAssumption();
        List<AnalysisReasonDto> reasons = new ArrayList<>();

        boolean histValid = isObjectiveAnchorValid(hist);
        boolean peerValid = isObjectiveAnchorValid(peer);

        if (!histValid && hist != null) {
            if ("AVAILABLE".equals(hist.status())) {
                addUniqueReason(reasons, "HISTORICAL_ANCHOR_PRICE_NON_POSITIVE", "SUMMARY",
                        "歷史倍數雖可用，但低位或中位換算股權價格非正（如高淨負債 EV/EBITDA），不納入價格參考帶合成。");
            } else if (hist.reasonCode() != null) {
                addUniqueReason(reasons, hist.reasonCode(), "SUMMARY", "歷史市場估值參考暫不可用。");
            }
        }
        if (!peerValid && peer != null) {
            if ("AVAILABLE".equals(peer.status())) {
                addUniqueReason(reasons, "PEER_ANCHOR_PRICE_NON_POSITIVE", "SUMMARY",
                        "同業倍數雖可用，但低位或中位換算股權價格非正，不納入價格參考帶合成。");
            } else if (peer.reasonCode() != null && !"HISTORICAL_DATE_NOT_APPLICABLE".equals(peer.reasonCode())) {
                addUniqueReason(reasons, peer.reasonCode(), "SUMMARY", "同業市場估值參考暫不可用。");
            }
        }

        BigDecimal low = null;
        BigDecimal mid = null;
        BigDecimal high = null;
        String anchorSource = "NONE";

        if (histValid && peerValid) {
            low = average(hist.priceLow(), peer.priceLow());
            mid = average(hist.priceMedian(), peer.priceMedian());
            high = average(hist.priceHigh(), peer.priceHigh());
            anchorSource = "HISTORICAL_AND_PEER";
            reasons.clear();
        } else if (histValid) {
            low = hist.priceLow();
            mid = hist.priceMedian();
            high = hist.priceHigh();
            anchorSource = "HISTORICAL_ONLY";
            reasons.clear();
        } else if (peerValid) {
            low = peer.priceLow();
            mid = peer.priceMedian();
            high = peer.priceHigh();
            anchorSource = "PEER_ONLY";
            reasons.clear();
        }

        boolean assumptionAvailable = assumption != null && "AVAILABLE".equals(assumption.status())
                && assumption.priceLow() != null && assumption.priceHigh() != null
                && assumption.priceLow().signum() > 0 && assumption.priceHigh().compareTo(assumption.priceLow()) >= 0;

        FairValueBandDto band = new FairValueBandDto(
                low,
                mid,
                high,
                valuation.currency(),
                histValid ? hist.priceLow() : null,
                histValid ? hist.priceMedian() : null,
                histValid ? hist.priceHigh() : null,
                peerValid ? peer.priceLow() : null,
                peerValid ? peer.priceMedian() : null,
                peerValid ? peer.priceHigh() : null,
                assumptionAvailable ? assumption.priceLow() : null,
                assumptionAvailable ? assumption.priceMedian() : null,
                assumptionAvailable ? assumption.priceHigh() : null
        );
        return new BandSynthesis(anchorSource, band, reasons);
    }

    private boolean isObjectiveAnchorValid(ValuationReferenceDto ref) {
        return isAnchorValidStatic(ref);
    }

    private static boolean isAnchorValidStatic(ValuationReferenceDto ref) {
        return ref != null
                && "AVAILABLE".equals(ref.status())
                && ref.priceLow() != null
                && ref.priceMedian() != null
                && ref.priceHigh() != null
                && ref.priceLow().signum() > 0
                && ref.priceMedian().compareTo(ref.priceLow()) >= 0
                && ref.priceHigh().compareTo(ref.priceMedian()) >= 0;
    }

    private ForwardTrapSignals extractForwardTrapSignals(FutuObservationsDto futuObservations) {
        if (futuObservations == null || futuObservations.forward() == null) {
            return new ForwardTrapSignals(false, false, null, null, false, null, null, null);
        }
        ForwardMetrics fm = futuObservations.forward();
        boolean swungToLoss = hasReason(fm.impliedEpsGrowth(), "FORWARD_SWUNG_TO_LOSS");
        BigDecimal impliedGrowth = val(fm.impliedEpsGrowth());
        boolean contraction = swungToLoss
                || (impliedGrowth != null && impliedGrowth.compareTo(FORWARD_EPS_TRAP_CONTRACTION) <= 0);

        BigDecimal rev90 = val(fm.epsRevision90d());
        boolean downgrade90 = rev90 != null && rev90.compareTo(FORWARD_REVISION_90D_TRAP) <= 0;

        return new ForwardTrapSignals(
                contraction,
                swungToLoss,
                impliedGrowth,
                fm.targetFiscalPeriod(),
                downgrade90,
                rev90,
                fm.revisionFiscalPeriod(),
                fm.revisionPeriodType()
        );
    }

    public static String evaluateFundamentalBacking(FundamentalSnapshotDto f, FinancialsDto financials) {
        boolean currentLoss = financials != null && financials.epsTtm() != null
                && "AVAILABLE".equals(financials.epsTtm().status())
                && financials.epsTtm().value() != null && financials.epsTtm().value().signum() <= 0;
        return evaluateFundamentalBacking(f, currentLoss);
    }

    /** 供估值快照批次刷新與單股摘要共用之基本面支撐度判定。 */
    public static String evaluateFundamentalBacking(FundamentalSnapshotDto f, boolean currentLoss) {
        if (f == null) {
            return "INSUFFICIENT_DATA";
        }
        BigDecimal revYoy = val(f.revenueGrowthYoy());
        BigDecimal epsYoy = val(f.epsGrowthYoy());
        BigDecimal epsCagr3y = val(f.epsCagr3y());
        BigDecimal peg = val(f.trailingPeg());
        BigDecimal roe = val(f.roe());
        BigDecimal fcfMargin = val(f.fcfMargin());
        BigDecimal debtEbitda = val(f.netDebtToEbitda());

        boolean swungToLoss = hasReason(f.epsGrowthYoy(), "SWUNG_TO_LOSS");
        boolean highRevGrowth = revYoy != null && revYoy.compareTo(HIGH_GROWTH_REV_EXEMPTION) >= 0;

        if (f.financialInstitution()) {
            int availableCount = countAvailable(f.revenueGrowthYoy(), f.epsGrowthYoy(), f.roe());
            if (availableCount == 0 && !swungToLoss && !currentLoss) {
                return "INSUFFICIENT_DATA";
            }
            boolean weakening = (revYoy != null && revYoy.compareTo(WEAK_REV_YOY) <= 0)
                    || (epsYoy != null && epsYoy.compareTo(WEAK_EPS_YOY) <= 0)
                    || swungToLoss
                    || (currentLoss && !highRevGrowth)
                    || (roe != null && roe.compareTo(WEAK_FIN_ROE) < 0);
            if (weakening) {
                return "WEAKENING";
            }
            if (revYoy != null && revYoy.signum() > 0
                    && epsYoy != null && epsYoy.signum() > 0
                    && roe != null && roe.compareTo(STRONG_FIN_ROE) >= 0) {
                return "STRONG";
            }
            return "MIXED";
        }

        int availableCount = countAvailable(f.revenueGrowthYoy(), f.epsGrowthYoy(), f.roe(), f.fcfMargin(), f.netDebtToEbitda());
        if (availableCount < 2 && !swungToLoss && !currentLoss && !f.marginCompressionConsecutive()) {
            return "INSUFFICIENT_DATA";
        }

        boolean negativeFcf = fcfMargin != null && fcfMargin.signum() <= 0;
        boolean weakening = (revYoy != null && revYoy.compareTo(WEAK_REV_YOY) <= 0)
                || (epsYoy != null && epsYoy.compareTo(WEAK_EPS_YOY) <= 0)
                || swungToLoss
                || (negativeFcf && !highRevGrowth)
                || (currentLoss && !(highRevGrowth && fcfMargin != null && fcfMargin.signum() > 0))
                || (debtEbitda != null && debtEbitda.compareTo(WEAK_DEBT_EBITDA) > 0)
                || f.marginCompressionConsecutive();
        if (weakening) {
            return "WEAKENING";
        }

        // STRONG 必須同時具備正營收成長與正自由現金流；低 PEG 絕不覆蓋營收衰退或負 FCF，負帳面值回購藍籌不因 ROE N/A 被取消資格
        boolean positiveRev = revYoy != null && revYoy.signum() > 0;
        boolean positiveFcf = fcfMargin != null && fcfMargin.signum() > 0;
        boolean solidProfitability = (fcfMargin != null && fcfMargin.compareTo(STRONG_FCF_MARGIN) >= 0)
                || (positiveFcf && roe != null && roe.compareTo(STRONG_NON_FIN_ROE) >= 0);
        boolean positiveEarningsTrack = (epsYoy != null && epsYoy.signum() > 0)
                || (peg != null && peg.compareTo(STRONG_PEG_MAX) <= 0 && epsCagr3y != null && epsCagr3y.signum() > 0);
        boolean safeLeverage = debtEbitda != null && debtEbitda.compareTo(STRONG_MAX_DEBT_EBITDA) <= 0;

        if (positiveRev && positiveFcf && solidProfitability && positiveEarningsTrack && safeLeverage) {
            return "STRONG";
        }
        return "MIXED";
    }

    private YieldSpreadSelection computeYieldSpreadVs10y(
            FundamentalSnapshotDto fundamentals, MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE
                || marketContext.value() == null || marketContext.value().treasury() == null
                || marketContext.value().treasury().tenYear() == null) {
            return new YieldSpreadSelection(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("TREASURY_10Y_UNAVAILABLE", "SUMMARY", "缺少 10Y 美債殖利率，暫不計算殖利率利差。")), null);
        }
        MetricResult<BigDecimal> tenYear = marketContext.value().treasury().tenYear();
        if (tenYear.status() != MetricStatus.AVAILABLE || tenYear.value() == null) {
            AnalysisReasonDto reason = !tenYear.reasons().isEmpty()
                    ? tenYear.reasons().getFirst()
                    : AnalysisReasonDto.of("TREASURY_10Y_UNAVAILABLE", "SUMMARY", "10Y 美債殖利率不可用。");
            return new YieldSpreadSelection(MetricResult.unavailable(tenYear.status(), reason), null);
        }
        if (fundamentals == null) {
            return new YieldSpreadSelection(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("YIELD_INPUT_UNAVAILABLE", "SUMMARY", "缺少當期殖利率輸入。")), null);
        }

        // TreasuryParYieldDaily.tenYear 單位為百分比數值（如 4.35 代表 4.35%），須先除以 100 統一轉小數比例（0.0435）
        BigDecimal tenYearDecimal = tenYear.value().divide(HUNDRED, MC);

        if (!fundamentals.financialInstitution()
                && fundamentals.fcfYield() != null
                && fundamentals.fcfYield().status() == MetricStatus.AVAILABLE
                && fundamentals.fcfYield().value() != null) {
            BigDecimal spread = fundamentals.fcfYield().value().subtract(tenYearDecimal, MC).setScale(6, RoundingMode.HALF_UP);
            return new YieldSpreadSelection(MetricResult.available(spread), "FCF_YIELD_MINUS_10Y");
        }
        if (fundamentals.earningsYield() != null
                && fundamentals.earningsYield().status() == MetricStatus.AVAILABLE
                && fundamentals.earningsYield().value() != null) {
            BigDecimal spread = fundamentals.earningsYield().value().subtract(tenYearDecimal, MC).setScale(6, RoundingMode.HALF_UP);
            return new YieldSpreadSelection(MetricResult.available(spread), "EARNINGS_YIELD_MINUS_10Y");
        }

        MetricResult<BigDecimal> fallback = !fundamentals.financialInstitution() && fundamentals.fcfYield() != null
                ? fundamentals.fcfYield() : fundamentals.earningsYield();
        if (fallback != null && !fallback.reasons().isEmpty()) {
            return new YieldSpreadSelection(MetricResult.unavailable(fallback.status(), fallback.reasons().getFirst()), null);
        }
        return new YieldSpreadSelection(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("YIELD_INPUT_UNAVAILABLE", "SUMMARY", "缺少可比殖利率分母。")), null);
    }

    private SupportSelection selectTechnicalSupportZone(
            AnalysisHorizon horizon,
            MetricResult<SupportResistanceDto> supportResistance,
            List<TradePlanDto> plans,
            BigDecimal dailyLatestClose,
            List<NormalizedBarDto> dailyBars,
            BigDecimal currentPrice,
            BigDecimal bandLow) {
        if (supportResistance == null || supportResistance.status() != MetricStatus.AVAILABLE || supportResistance.value() == null) {
            return new SupportSelection(null, false);
        }
        AnalysisTimeframe wantedTimeframe = horizon == AnalysisHorizon.SWING ? AnalysisTimeframe.DAILY : AnalysisTimeframe.WEEKLY;
        BigDecimal ceiling = null;
        if (currentPrice != null && bandLow != null) {
            ceiling = currentPrice.max(bandLow).multiply(new BigDecimal("1.03"), MC);
        } else if (currentPrice != null) {
            ceiling = currentPrice.multiply(new BigDecimal("1.03"), MC);
        } else if (bandLow != null) {
            ceiling = bandLow.multiply(new BigDecimal("1.03"), MC);
        }

        List<LevelZoneDto> candidates = new ArrayList<>(supportResistance.value().supports());
        for (LevelZoneDto containing : supportResistance.value().containingZones()) {
            if (containing != null && containing.role() == ZoneRole.SUPPORT) {
                candidates.add(containing);
            }
        }
        Set<String> seenIds = new LinkedHashSet<>();
        List<LevelZoneDto> eligible = new ArrayList<>();
        boolean anyExcludedByBreach = false;
        for (LevelZoneDto z : candidates) {
            if (z == null || z.id() == null || !seenIds.add(z.id())) {
                continue;
            }
            if (z.timeframe() != wantedTimeframe || z.state() != ZoneState.ACTIVE || z.lower() == null || z.upper() == null) {
                continue;
            }
            if (z.effectiveStrength() == null || z.effectiveStrength().compareTo(MIN_SUPPORT_STRENGTH) < 0) {
                continue;
            }
            if (isZoneStopBreached(z, plans, dailyLatestClose, dailyBars)) {
                anyExcludedByBreach = true;
                continue;
            }
            eligible.add(z);
        }
        if (eligible.isEmpty()) {
            return new SupportSelection(null, anyExcludedByBreach);
        }
        BigDecimal finalCeiling = ceiling;
        eligible.sort((a, b) -> {
            boolean aNearOrBelow = finalCeiling == null || a.lower().compareTo(finalCeiling) <= 0;
            boolean bNearOrBelow = finalCeiling == null || b.lower().compareTo(finalCeiling) <= 0;
            if (aNearOrBelow != bNearOrBelow) {
                return aNearOrBelow ? -1 : 1;
            }
            int strengthCmp = b.effectiveStrength().compareTo(a.effectiveStrength());
            if (strengthCmp != 0) {
                return strengthCmp;
            }
            int aTfRank = a.timeframe() == AnalysisTimeframe.WEEKLY ? 2 : a.timeframe() == AnalysisTimeframe.DAILY ? 1 : 0;
            int bTfRank = b.timeframe() == AnalysisTimeframe.WEEKLY ? 2 : b.timeframe() == AnalysisTimeframe.DAILY ? 1 : 0;
            return Integer.compare(bTfRank, aTfRank);
        });
        return new SupportSelection(eligible.getFirst(), false);
    }

    private boolean isZoneStopBreached(LevelZoneDto zone, List<TradePlanDto> plans,
                                       BigDecimal dailyLatestClose, List<NormalizedBarDto> dailyBars) {
        for (TradePlanDto plan : plans) {
            if (matchesPlanZone(plan, zone)
                    && (hasPlanReason(plan, "PROTECTIVE_STOP_BREACHED") || hasPlanReason(plan, "TECHNICAL_INVALIDATION_CONFIRMED"))) {
                return true;
            }
        }
        if (dailyLatestClose != null && zone.anchorAtr() != null && zone.anchorAtr().signum() > 0) {
            BigDecimal protectiveStop = zone.lower().subtract(zone.anchorAtr().multiply(HALF_ATR, MC), MC);
            if (protectiveStop.signum() > 0 && dailyLatestClose.compareTo(protectiveStop) <= 0) {
                return true;
            }
        }
        return hasTwoConsecutiveClosesBelowFrozenThreshold(zone, dailyBars);
    }

    /** 對未納入前 3 名交易計畫之支撐區（例如距離第 4 之強支撐），仍依凍結 anchorAtr 與角色生效日判定是否已兩收盤技術失效。 */
    private boolean hasTwoConsecutiveClosesBelowFrozenThreshold(LevelZoneDto zone, List<NormalizedBarDto> dailyBars) {
        if (zone == null || zone.lower() == null || zone.anchorAtr() == null || zone.anchorAtr().signum() <= 0
                || dailyBars == null || dailyBars.size() < 2) {
            return false;
        }
        BigDecimal threshold = zone.lower().subtract(zone.anchorAtr().multiply(QUARTER_ATR, MC), MC);
        Instant effectiveConfirmedAt = zone.roleConfirmedAt() != null ? zone.roleConfirmedAt() : zone.confirmedAt();
        int consecutive = 0;
        LocalDate previous = null;
        for (NormalizedBarDto bar : dailyBars) {
            if (bar == null || bar.availableAt() == null || bar.sessionDate() == null) {
                continue;
            }
            if (effectiveConfirmedAt != null && !bar.availableAt().isAfter(effectiveConfirmedAt)) {
                continue;
            }
            if (previous != null) {
                LocalDate expected = marketCalendar.getNextTradingDay(previous);
                if (!expected.equals(bar.sessionDate())) {
                    consecutive = 0;
                }
            }
            if (bar.close() != null && bar.close().compareTo(threshold) < 0) {
                consecutive++;
                if (consecutive >= 2) {
                    return true;
                }
            } else {
                consecutive = 0;
            }
            previous = bar.sessionDate();
        }
        return false;
    }

    private boolean hasStopBreachedOrReclaim(List<TradePlanDto> plans, LevelZoneDto selectedZone,
                                             BigDecimal dailyLatestClose, List<NormalizedBarDto> dailyBars) {
        if (selectedZone != null) {
            if (plans != null) {
                boolean foundForSelected = false;
                for (TradePlanDto plan : plans) {
                    if (matchesPlanZone(plan, selectedZone)) {
                        foundForSelected = true;
                        if (hasPlanReason(plan, "PROTECTIVE_STOP_BREACHED")
                                || hasPlanReason(plan, "RECLAIM_REQUIRED")
                                || hasPlanReason(plan, "TECHNICAL_INVALIDATION_CONFIRMED")) {
                            return true;
                        }
                    }
                }
                if (foundForSelected) {
                    return false;
                }
            }
            // 若選定支撐（例如距離第 4）未出現在前 3 名交易計畫中，直接檢核該支撐自身之兩收盤失效與收復門檻，不繼承他區破位。
            if (hasTwoConsecutiveClosesBelowFrozenThreshold(selectedZone, dailyBars)) {
                return true;
            }
            return dailyLatestClose != null && selectedZone.lower() != null
                    && dailyLatestClose.compareTo(selectedZone.lower()) < 0;
        }
        if (plans == null || plans.isEmpty()) {
            return false;
        }
        boolean anyActive = false;
        boolean anyBreachOrReclaim = false;
        for (TradePlanDto plan : plans) {
            if (plan == null) continue;
            if (plan.status() == TradePlanStatus.ACTIVE) {
                anyActive = true;
            }
            if (hasPlanReason(plan, "PROTECTIVE_STOP_BREACHED")
                    || hasPlanReason(plan, "RECLAIM_REQUIRED")
                    || hasPlanReason(plan, "TECHNICAL_INVALIDATION_CONFIRMED")) {
                anyBreachOrReclaim = true;
            }
        }
        return !anyActive && anyBreachOrReclaim;
    }

    private boolean matchesPlanZone(TradePlanDto plan, LevelZoneDto zone) {
        return plan != null && zone != null && zone.id() != null
                && zone.id().equals(plan.sourceZoneId())
                && (plan.sourceBoundsVersion() <= 0 || plan.sourceBoundsVersion() == zone.boundsVersion());
    }

    private boolean hasConfirmationPending(List<TradePlanDto> plans, LevelZoneDto selectedZone, EntryPermission permission) {
        if (plans != null && !plans.isEmpty()) {
            boolean anyActive = false;
            boolean anyConfirmationRequired = false;
            for (TradePlanDto plan : plans) {
                if (plan == null) continue;
                if (plan.status() == TradePlanStatus.ACTIVE) {
                    anyActive = true;
                }
                boolean pending = hasPlanReason(plan, "CLOSE_CONFIRMATION_REQUIRED")
                        || hasPlanReason(plan, "STRUCTURE_CONFIRMATION_REQUIRED");
                if (pending) {
                    if (selectedZone != null && selectedZone.id().equals(plan.sourceZoneId())) {
                        return true;
                    }
                    anyConfirmationRequired = true;
                }
            }
            if (anyActive) {
                return false;
            }
            if (anyConfirmationRequired) {
                return true;
            }
        }
        return permission == EntryPermission.REQUIRE_CLOSE_CONFIRMATION;
    }

    private boolean hasStructureConfirmationPending(List<TradePlanDto> plans, LevelZoneDto selectedZone) {
        if (plans == null || plans.isEmpty()) {
            return false;
        }
        for (TradePlanDto plan : plans) {
            if (plan == null) continue;
            if (selectedZone != null && selectedZone.id().equals(plan.sourceZoneId())
                    && hasPlanReason(plan, "STRUCTURE_CONFIRMATION_REQUIRED")) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlanInvalidated(List<TradePlanDto> plans, LevelZoneDto selectedZone) {
        if (plans == null || plans.isEmpty() || selectedZone == null) {
            return false;
        }
        for (TradePlanDto plan : plans) {
            if (matchesPlanZone(plan, selectedZone) && plan.status() == TradePlanStatus.INVALIDATED) {
                return true;
            }
        }
        return false;
    }

    /** 與 TradePlanService 共用同一 NYSE 交易日曆 5 交易日視窗及同區版本計畫檢核，徹底消除 5 交易日與日曆日近似偏差。 */
    private boolean isValidConfirmedRetest(LevelZoneDto supportZone, List<TradePlanDto> plans, LocalDate currentSession) {
        if (supportZone == null || supportZone.retestState() != RetestState.SUCCEEDED
                || supportZone.roleVersion() <= 1
                || supportZone.roleConfirmationPrice() == null
                || supportZone.roleConfirmationPrice().signum() <= 0
                || supportZone.roleConfirmedAt() == null) {
            return false;
        }
        LocalDate retestDate = supportZone.roleConfirmedAt().atZone(NY).toLocalDate();
        LocalDate validThrough = advanceTradingDays(retestDate, RETEST_VALID_SESSIONS);
        if (currentSession != null && currentSession.isAfter(validThrough)) {
            return false;
        }
        if (plans != null) {
            for (TradePlanDto plan : plans) {
                if (matchesPlanZone(plan, supportZone)) {
                    if (plan.status() == TradePlanStatus.INVALIDATED || hasPlanReason(plan, "ENTRY_EXPIRED")) {
                        return false;
                    }
                    if (currentSession != null && plan.validThroughSession() != null
                            && currentSession.isAfter(plan.validThroughSession())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private LocalDate advanceTradingDays(LocalDate start, int sessions) {
        LocalDate cursor = start;
        for (int i = 0; i < sessions; i++) {
            cursor = marketCalendar.getNextTradingDay(cursor);
        }
        return cursor;
    }

    private String resolveStance(
            String verdict,
            String fundamentalBacking,
            boolean forwardValueTrap,
            String weeklyTrend,
            Boolean aboveSma200,
            MarketRegime marketRegime,
            EntryPermission permission,
            boolean eventGateBlocked,
            boolean stopBreachedOrReclaim,
            boolean confirmationPending,
            boolean hasTechnicalSupport,
            boolean rightSideConfirmedForCorrection,
            TrendPullbackStarterDto starter) {
        if ("INSUFFICIENT_EVIDENCE".equals(verdict)) {
            return "INSUFFICIENT_EVIDENCE";
        }

        boolean undervalued = "DEEP_UNDERVALUED".equals(verdict) || "MODERATELY_UNDERVALUED".equals(verdict);
        boolean downtrendBelowSma200 = "DOWNTREND".equals(weeklyTrend) && Boolean.FALSE.equals(aboveSma200);
        boolean marketBearOrPanic = marketRegime == MarketRegime.BEAR || marketRegime == MarketRegime.PANIC;
        boolean marketCorrection = marketRegime == MarketRegime.CORRECTION;
        boolean entryBlocked = eventGateBlocked
                || permission == EntryPermission.BLOCK
                || permission == EntryPermission.UNKNOWN;

        if (undervalued) {
            // 若下方交易方案已判定跌破保護止損、技術失效或高機率真破位，強制退回等待止穩，避免上下矛盾
            if (stopBreachedOrReclaim) {
                return "WAIT_FOR_STABILIZATION";
            }
            if (forwardValueTrap) {
                if (downtrendBelowSma200 || eventGateBlocked) {
                    return "WAIT_FOR_STABILIZATION";
                }
                if (marketBearOrPanic) {
                    return "CAUTION_VALUE_TRAP";
                }
                if (entryBlocked) {
                    return "WAIT_FOR_STABILIZATION";
                }
                return "CAUTION_VALUE_TRAP";
            }
            if ("WEAKENING".equals(fundamentalBacking)) {
                return "CAUTION_VALUE_TRAP";
            }
            if (eventGateBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            if (marketBearOrPanic) {
                return "WAIT_FOR_MARKET_STABILIZATION";
            }
            if (downtrendBelowSma200 || entryBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            if (marketCorrection) {
                if (hasTechnicalSupport && rightSideConfirmedForCorrection) {
                    return "WATCH_PULLBACK_TO_BAND";
                }
                return "WAIT_FOR_MARKET_STABILIZATION";
            }
            if (confirmationPending) {
                return "WAIT_FOR_STABILIZATION";
            }
            return hasTechnicalSupport ? "ACCUMULATE_AT_SUPPORT" : "WATCH_PULLBACK_TO_BAND";
        }

        if ("FAIR_LOWER".equals(verdict)) {
            if (stopBreachedOrReclaim || downtrendBelowSma200 || eventGateBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            if ("WEAKENING".equals(fundamentalBacking) || forwardValueTrap) {
                return "CAUTION_VALUE_TRAP";
            }
            if (marketBearOrPanic || marketCorrection) {
                return "WAIT_FOR_MARKET_STABILIZATION";
            }
            if (entryBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            return "WATCH_PULLBACK_TO_BAND";
        }

        if ("FAIR_UPPER".equals(verdict)) {
            if ("STRONG".equals(fundamentalBacking) && "UPTREND".equals(weeklyTrend)) {
                if (starter != null && starter.eligible()) {
                    return "TREND_PULLBACK_STARTER";
                }
                return "HOLD_TRAIL_NO_CHASE";
            }
            if ("WEAKENING".equals(fundamentalBacking)) {
                return "CAUTION_VALUE_TRAP";
            }
            if (stopBreachedOrReclaim || downtrendBelowSma200 || eventGateBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            if (marketBearOrPanic || marketCorrection) {
                return "WAIT_FOR_MARKET_STABILIZATION";
            }
            if (entryBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            return "WATCH_PULLBACK_TO_BAND";
        }

        if ("MODERATELY_OVERVALUED".equals(verdict)) {
            if ("STRONG".equals(fundamentalBacking) && "UPTREND".equals(weeklyTrend)) {
                if (starter != null && starter.eligible()) {
                    return "TREND_PULLBACK_STARTER";
                }
                return "HOLD_TRAIL_NO_CHASE";
            }
            return "CAUTION_OVEREXTENSION";
        }

        return "CAUTION_OVEREXTENSION";
    }

    private MacroCautionDto buildMacroCaution(MarketRegime regime, BigDecimal vixClose, BigDecimal tenYearChange20) {
        boolean evaluated = regime != null || vixClose != null || tenYearChange20 != null;
        List<String> triggers = new ArrayList<>();
        if (regime == MarketRegime.PANIC) {
            triggers.add("MARKET_REGIME_PANIC");
        } else if (regime == MarketRegime.BEAR) {
            triggers.add("MARKET_REGIME_BEAR");
        } else if (regime == MarketRegime.CORRECTION) {
            triggers.add("MARKET_REGIME_CORRECTION");
        }
        if (vixClose != null && vixClose.compareTo(VIX_CAUTION_THRESHOLD) >= 0) {
            triggers.add("VIX_ELEVATED");
        }
        if (tenYearChange20 != null && tenYearChange20.compareTo(TEN_YEAR_SURGE_20_THRESHOLD) >= 0) {
            triggers.add("TREASURY_10Y_SURGE_20P");
        }
        boolean active = !triggers.isEmpty();
        return new MacroCautionDto(
                active,
                evaluated,
                regime,
                vixClose,
                tenYearChange20,
                triggers,
                active ? MACRO_MARGIN_BUFFER_MIN : null,
                active ? MACRO_MARGIN_BUFFER_MAX : null,
                active ? MACRO_POSITION_SCALE : null
        );
    }

    /**
     * 強勢成長股「趨勢回踩試單」判定：
     * 僅適用 FAIR_UPPER / MODERATELY_OVERVALUED，要求 STRONG + UPTREND + 站上年線 + 友善大盤 + 合理前瞻成長支撐 + 回踩均線／財報 AVWAP 縮量止穩。
     */
    private TrendPullbackStarterDto evaluateTrendPullbackStarter(
            AnalysisHorizon horizon,
            String verdict,
            String fundamentalBacking,
            FundamentalSnapshotDto fundamentals,
            FutuObservationsDto futuObservations,
            ForwardTrapSignals forwardTrap,
            String weeklyTrend,
            Boolean aboveSma200,
            MarketRegime marketRegime,
            EntryPermission permission,
            boolean eventGateBlocked,
            boolean blockedByBreachOrStop,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<MarketContextDto> marketContext,
            MacroCautionDto macroCaution) {
        if ((!"FAIR_UPPER".equals(verdict) && !"MODERATELY_OVERVALUED".equals(verdict))
                || !"STRONG".equals(fundamentalBacking)
                || !"UPTREND".equals(weeklyTrend)) {
            return null;
        }
        if (forwardTrap != null && forwardTrap.anyTrap()) {
            return ineligibleStarter(null, null, null, "TREND_PULLBACK_FORWARD_TRAP", "前瞻盈餘預期下滑或近 90 日顯著下修，維持不追高。");
        }
        if (!Boolean.TRUE.equals(aboveSma200)) {
            return ineligibleStarter(null, null, null, "TREND_PULLBACK_BELOW_SMA200", "日線收盤尚未站上 SMA200 年線，維持不追高。");
        }
        if (marketRegime != MarketRegime.RISK_ON && marketRegime != MarketRegime.NEUTRAL && marketRegime != MarketRegime.RECOVERY) {
            return ineligibleStarter(null, null, null, "TREND_PULLBACK_MARKET_HEADWIND", "大盤市況非風險偏好／中性／修復期或尚未評估，暫不啟動強勢股回踩試單。");
        }
        if (eventGateBlocked || permission != EntryPermission.ALLOW || blockedByBreachOrStop) {
            return ineligibleStarter(null, null, null, "TREND_PULLBACK_EVENT_OR_BREACH_BLOCKED", "受財報事件門鎖、外部限制或破位止損約束，維持不追高。");
        }

        // 1) 前瞻成長合理性檢查（零外部 API 呼叫，嚴格相容 ForwardValuationCalculator 規則）
        if (futuObservations == null || futuObservations.forward() == null) {
            return ineligibleStarter(null, null, null, "ANNUAL_EARNINGS_FORECAST_MISSING", "缺少年度市場共識預期，無法驗證前瞻成長支撐，維持不追高。");
        }
        ForwardMetrics fm = futuObservations.forward();
        BigDecimal forwardPeg = val(fm.forwardPeg());
        BigDecimal forwardPe = val(fm.forwardPe());
        String forwardGrowthBasis;

        if (forwardPeg != null) {
            if (forwardPeg.signum() <= 0 || forwardPeg.compareTo(STARTER_FORWARD_PEG_MAX) > 0) {
                return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                        "FORWARD_PEG_ABOVE_THRESHOLD", "Forward PEG 高於 1.50 合理區間，維持不追高。");
            }
            forwardGrowthBasis = "FORWARD_PEG";
        } else if (fm.forwardPeg() != null
                && fm.forwardPeg().status() == MetricStatus.NOT_APPLICABLE
                && hasReason(fm.forwardPeg(), "FORWARD_EPS_GROWTH_ABOVE_PEG_CAP")) {
            if (forwardPe == null || forwardPe.signum() <= 0 || forwardPe.compareTo(STARTER_HYPER_GROWTH_PE_MAX) > 0) {
                return ineligibleStarter(null, fm.forwardPegBasis(), forwardPe,
                        "FORWARD_PE_ABOVE_HYPER_GROWTH_CAP", "預期 EPS 成長率逾 100% 觸發 PEG 上限，但 Forward P/E 不可用或超過 35 倍合理上限，維持不追高。");
            }
            if (!passesNonLowBaseHighGrowthCheck(fundamentals)) {
                return ineligibleStarter(null, fm.forwardPegBasis(), forwardPe,
                        "HYPER_GROWTH_LOW_BASE_UNVERIFIED", "預期 EPS 成長率逾 100%，但營收年增率未達 15% 或獲利品質未達真高成長門檻（疑似低基期復甦），維持不追高。");
            }
            forwardGrowthBasis = "HYPER_GROWTH_FORWARD_PE";
        } else {
            String upstreamCode = fm.forwardPeg() != null && !fm.forwardPeg().reasons().isEmpty()
                    ? fm.forwardPeg().reasons().getFirst().code()
                    : "ANNUAL_EARNINGS_FORECAST_MISSING";
            String upstreamMsg = fm.forwardPeg() != null && !fm.forwardPeg().reasons().isEmpty()
                    ? fm.forwardPeg().reasons().getFirst().message()
                    : "缺少可用年度市場共識預期，維持不追高。";
            return ineligibleStarter(null, fm.forwardPegBasis(), forwardPe, upstreamCode, upstreamMsg);
        }

        // 2) 回踩 EMA21 / SMA50 / 財報日 AVWAP（-1% ～ +3%）+ 近期回落 + 縮量止穩 + 緊貼止損
        List<NormalizedBarDto> dailyBars = extractDailyBars(priceStructure);
        if (dailyBars.size() < 2) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_BARS_INSUFFICIENT", "缺少完整日線行情，無法判定回踩止穩結構。");
        }
        NormalizedBarDto lastBar = dailyBars.getLast();
        NormalizedBarDto prevBar = dailyBars.get(dailyBars.size() - 2);
        BigDecimal dailyClose = lastBar.close();
        if (dailyClose == null || dailyClose.signum() <= 0 || prevBar.close() == null || prevBar.close().signum() <= 0) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_BARS_INSUFFICIENT", "缺少有效日線收盤價，維持不追高。");
        }

        AnchorCandidate matchedAnchor = matchPullbackAnchor(horizon, dailyClose, marketContext);
        if (matchedAnchor == null) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_NOT_IN_ZONE", "股價尚未回踩至 EMA21／SMA50／財報成本線（-1%～+3%）區間內，維持不追高。");
        }
        if (!hasRecentPullbackFromHigherPrice(dailyBars, dailyClose)) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_NO_RETRACEMENT", "近 10 日未見自較高價位回落結構，維持不追高。");
        }
        BigDecimal rvol20 = extractRvol20(marketContext);
        if (rvol20 == null || rvol20.signum() <= 0 || rvol20.compareTo(STARTER_RVOL_MAX) > 0) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_VOLUME_NOT_CONTRACTED", "回踩量能尚未收斂（須 0 < RVOL20 ≤ 1.0），暫不啟動試單。");
        }
        BigDecimal dayReturn = dailyClose.divide(prevBar.close(), MC).subtract(BigDecimal.ONE, MC);
        BigDecimal closePosition = computeClosePosition(lastBar);
        boolean stabilizedCandle = dayReturn.compareTo(STARTER_DAY_RET_MIN) >= 0
                && (dailyClose.compareTo(lastBar.open()) > 0
                || (closePosition != null && closePosition.compareTo(STARTER_CLOSE_POS_MIN) >= 0));
        if (!stabilizedCandle) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_NOT_STABILIZED", "當日 K 線跌幅偏大或收在當日低位，尚未量縮止穩，維持不追高。");
        }
        BigDecimal atr14 = extractIndicator(marketContext, "ATR14");
        if (atr14 == null || atr14.signum() <= 0) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_ATR_UNAVAILABLE", "缺少有效日線 ATR14，無法設定緊貼均線保護止損。");
        }
        BigDecimal suggestedStop = matchedAnchor.price().subtract(atr14.multiply(HALF_ATR, MC), MC).setScale(4, RoundingMode.HALF_UP);
        if (suggestedStop.signum() <= 0 || suggestedStop.compareTo(dailyClose) >= 0) {
            return ineligibleStarter(forwardPeg, fm.forwardPegBasis(), forwardPe,
                    "TREND_PULLBACK_STOP_INVALID", "計算得出的試單止損價無效，暫不輸出試單建議。");
        }

        String positionFraction = macroCaution != null && macroCaution.active() ? "1/6" : "1/3";
        return new TrendPullbackStarterDto(
                true,
                matchedAnchor.name(),
                matchedAnchor.price().setScale(4, RoundingMode.HALF_UP),
                matchedAnchor.distanceRatio().setScale(6, RoundingMode.HALF_UP),
                suggestedStop,
                positionFraction,
                forwardGrowthBasis,
                forwardPeg,
                fm.forwardPegBasis(),
                forwardPe,
                null,
                null
        );
    }

    private TrendPullbackStarterDto ineligibleStarter(
            BigDecimal forwardPeg, String forwardPegBasis, BigDecimal forwardPe,
            String reasonCode, String reasonMessage) {
        return new TrendPullbackStarterDto(
                false, null, null, null, null, null, null,
                forwardPeg, forwardPegBasis, forwardPe, reasonCode, reasonMessage);
    }

    private boolean passesNonLowBaseHighGrowthCheck(FundamentalSnapshotDto f) {
        if (f == null) {
            return false;
        }
        BigDecimal revYoy = val(f.revenueGrowthYoy());
        BigDecimal epsYoy = val(f.epsGrowthYoy());
        BigDecimal fcfMargin = val(f.fcfMargin());
        BigDecimal roe = val(f.roe());
        boolean solidRevenue = revYoy != null && revYoy.compareTo(STARTER_HYPER_GROWTH_MIN_REV_YOY) >= 0;
        boolean positiveTrailingEps = epsYoy != null && epsYoy.signum() > 0;
        boolean qualityProfitability = f.financialInstitution()
                ? (roe != null && roe.compareTo(STRONG_NON_FIN_ROE) >= 0)
                : ((fcfMargin != null && fcfMargin.compareTo(STRONG_FCF_MARGIN) >= 0)
                        || (roe != null && roe.compareTo(STRONG_NON_FIN_ROE) >= 0));
        return solidRevenue && positiveTrailingEps && qualityProfitability;
    }

    private AnchorCandidate matchPullbackAnchor(AnalysisHorizon horizon, BigDecimal dailyClose,
                                                MetricResult<MarketContextDto> marketContext) {
        BigDecimal ema21 = extractIndicator(marketContext, "EMA21");
        BigDecimal sma50 = extractIndicator(marketContext, "SMA50");
        BigDecimal earningsAvwap = extractEarningsAvwap(marketContext);
        List<AnchorCandidate> ordered = new ArrayList<>();
        if (horizon == AnalysisHorizon.SWING) {
            addAnchorIfPresent(ordered, "EMA21", ema21, dailyClose);
            addAnchorIfPresent(ordered, "EARNINGS_AVWAP", earningsAvwap, dailyClose);
            addAnchorIfPresent(ordered, "SMA50", sma50, dailyClose);
        } else {
            addAnchorIfPresent(ordered, "SMA50", sma50, dailyClose);
            addAnchorIfPresent(ordered, "EARNINGS_AVWAP", earningsAvwap, dailyClose);
            addAnchorIfPresent(ordered, "EMA21", ema21, dailyClose);
        }
        for (AnchorCandidate candidate : ordered) {
            if (candidate.distanceRatio().compareTo(STARTER_PULLBACK_DIST_MIN) >= 0
                    && candidate.distanceRatio().compareTo(STARTER_PULLBACK_DIST_MAX) <= 0) {
                return candidate;
            }
        }
        return null;
    }

    private void addAnchorIfPresent(List<AnchorCandidate> list, String name, BigDecimal anchorPrice, BigDecimal dailyClose) {
        if (anchorPrice != null && anchorPrice.signum() > 0) {
            BigDecimal dist = dailyClose.subtract(anchorPrice, MC).divide(anchorPrice, MC);
            list.add(new AnchorCandidate(name, anchorPrice, dist));
        }
    }

    private boolean hasRecentPullbackFromHigherPrice(List<NormalizedBarDto> dailyBars, BigDecimal dailyClose) {
        int lastIdx = dailyBars.size() - 1;
        int start = Math.max(0, lastIdx - STARTER_PULLBACK_HIGH_LOOKBACK);
        for (int i = start; i < lastIdx; i++) {
            NormalizedBarDto bar = dailyBars.get(i);
            if (bar != null && bar.high() != null && bar.high().compareTo(dailyClose) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 保留原始高精度供 0.40 門檻比較，避免 0.39996 四捨五入誤判通過。 */
    private BigDecimal computeClosePosition(NormalizedBarDto bar) {
        if (bar == null || bar.high() == null || bar.low() == null || bar.close() == null) {
            return null;
        }
        BigDecimal range = bar.high().subtract(bar.low(), MC);
        if (range.signum() <= 0) {
            return null;
        }
        return bar.close().subtract(bar.low(), MC).max(BigDecimal.ZERO).divide(range, MC);
    }

    private record AnchorCandidate(String name, BigDecimal price, BigDecimal distanceRatio) {}

    private List<SummaryFlagDto> buildRiskFlags(
            String verdict,
            FundamentalSnapshotDto f,
            FinancialsDto financials,
            ForwardTrapSignals forwardTrap,
            FairValueBandDto band,
            String weeklyTrend,
            Boolean aboveSma200,
            boolean eventGateBlocked,
            LocalDate nextBlockingEventDate,
            boolean stopBreachedOrReclaim,
            MacroCautionDto macroCaution,
            BreakoutDiagnosisDto primaryDiagnosis) {
        List<SummaryFlagDto> candidates = new ArrayList<>();

        if (eventGateBlocked) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (nextBlockingEventDate != null) {
                params.put("eventDate", nextBlockingEventDate.toString());
            }
            candidates.add(new SummaryFlagDto("EARNINGS_IMMINENT_BLOCK", "HIGH", params));
        }
        if (macroCaution != null && macroCaution.marketRegime() != null
                && (macroCaution.marketRegime() == MarketRegime.PANIC
                || macroCaution.marketRegime() == MarketRegime.BEAR
                || macroCaution.marketRegime() == MarketRegime.CORRECTION)) {
            String sev = macroCaution.marketRegime() == MarketRegime.CORRECTION ? "MEDIUM" : "HIGH";
            candidates.add(new SummaryFlagDto("MARKET_REGIME_HEADWIND", sev,
                    Map.of("regime", macroCaution.marketRegime().name())));
        }
        if (macroCaution != null && (macroCaution.triggerCodes().contains("VIX_ELEVATED")
                || macroCaution.triggerCodes().contains("TREASURY_10Y_SURGE_20P"))) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (macroCaution.vixClose() != null) params.put("vixClose", macroCaution.vixClose());
            if (macroCaution.tenYearChange20() != null) params.put("tenYearChange20", macroCaution.tenYearChange20());
            candidates.add(new SummaryFlagDto("MACRO_VOLATILITY_OR_RATE_STRESS", "MEDIUM", params));
        }
        if (primaryDiagnosis != null && primaryDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (primaryDiagnosis.breachDate() != null) params.put("breachDate", primaryDiagnosis.breachDate().toString());
            if (primaryDiagnosis.rvolAtBreach() != null) params.put("rvol", primaryDiagnosis.rvolAtBreach());
            candidates.add(new SummaryFlagDto("SUPPORT_HIGH_RISK_BREAKDOWN", "HIGH", params));
        }
        if (stopBreachedOrReclaim) {
            candidates.add(new SummaryFlagDto("PROTECTIVE_STOP_OR_RECLAIM_PENDING", "HIGH", Map.of()));
        }
        if (forwardTrap != null && forwardTrap.earningsContraction()) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (forwardTrap.impliedGrowth() != null) params.put("impliedEpsGrowth", forwardTrap.impliedGrowth());
            if (forwardTrap.targetFiscalPeriod() != null) params.put("fiscalPeriod", forwardTrap.targetFiscalPeriod());
            params.put("swungToLoss", forwardTrap.swungToLoss());
            candidates.add(new SummaryFlagDto("FORWARD_EARNINGS_CONTRACTION", "HIGH", params));
        }
        if (forwardTrap != null && forwardTrap.downgrade90d()) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (forwardTrap.revision90d() != null) params.put("epsRevision90d", forwardTrap.revision90d());
            if (forwardTrap.revisionFiscalPeriod() != null) params.put("fiscalPeriod", forwardTrap.revisionFiscalPeriod());
            if (forwardTrap.revisionPeriodType() != null) params.put("periodType", forwardTrap.revisionPeriodType());
            candidates.add(new SummaryFlagDto("EARNINGS_FORECAST_DOWNGRADE_90D", "HIGH", params));
        }
        if (f != null && hasReason(f.epsGrowthYoy(), "SWUNG_TO_LOSS")) {
            candidates.add(new SummaryFlagDto("SWUNG_TO_LOSS", "HIGH", Map.of()));
        } else if (financials != null && financials.epsTtm() != null && "AVAILABLE".equals(financials.epsTtm().status())
                && financials.epsTtm().value() != null && financials.epsTtm().value().signum() <= 0) {
            candidates.add(new SummaryFlagDto("NEGATIVE_TRAILING_EARNINGS", "MEDIUM", Map.of()));
        }
        if (f != null && !f.financialInstitution()) {
            BigDecimal fcfMargin = val(f.fcfMargin());
            if (fcfMargin != null && fcfMargin.signum() <= 0) {
                candidates.add(new SummaryFlagDto("NEGATIVE_FCF_BURN", "HIGH", Map.of("fcfMargin", fcfMargin)));
            }
            BigDecimal debtEbitda = val(f.netDebtToEbitda());
            BigDecimal interestCov = val(f.interestCoverage());
            boolean lowInterestCov = debtEbitda != null && debtEbitda.compareTo(WEAK_DEBT_EBITDA) > 0
                    && interestCov != null && interestCov.compareTo(TWO) < 0;
            if (lowInterestCov) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("netDebtToEbitda", debtEbitda);
                params.put("interestCoverage", interestCov);
                candidates.add(new SummaryFlagDto("LOW_INTEREST_COVERAGE", "HIGH", params));
            } else if (debtEbitda != null && debtEbitda.compareTo(STRONG_MAX_DEBT_EBITDA) > 0) {
                String sev = debtEbitda.compareTo(WEAK_DEBT_EBITDA) > 0 ? "HIGH" : "MEDIUM";
                candidates.add(new SummaryFlagDto("HIGH_LEVERAGE_DEBT_EBITDA", sev, Map.of("netDebtToEbitda", debtEbitda)));
            }
            if (f.marginCompressionConsecutive()) {
                candidates.add(new SummaryFlagDto("MARGIN_COMPRESSION_CONSECUTIVE", "MEDIUM", Map.of()));
            }
        }
        if (f != null) {
            BigDecimal revYoy = val(f.revenueGrowthYoy());
            if (revYoy != null && revYoy.compareTo(REV_CONTRACTION_WARN) <= 0) {
                String sev = revYoy.compareTo(WEAK_REV_YOY) <= 0 ? "HIGH" : "MEDIUM";
                candidates.add(new SummaryFlagDto("REVENUE_CONTRACTION", sev, Map.of("revenueGrowthYoy", revYoy)));
            }
            BigDecimal shareChange = val(f.netShareChangeYoy());
            if (shareChange != null && shareChange.compareTo(SHARE_DILUTION_WARN) >= 0) {
                candidates.add(new SummaryFlagDto("SHARE_DILUTION_YOY", "MEDIUM", Map.of("netShareChangeYoy", shareChange)));
            }
        }
        if ("OVERVALUED_RISK".equals(verdict)) {
            candidates.add(new SummaryFlagDto("VALUATION_ABOVE_UPPER_BAND", "HIGH", Map.of()));
        } else if ("MODERATELY_OVERVALUED".equals(verdict)) {
            candidates.add(new SummaryFlagDto("VALUATION_ABOVE_UPPER_BAND", "MEDIUM", Map.of()));
        }
        if ("DOWNTREND".equals(weeklyTrend) && Boolean.FALSE.equals(aboveSma200)) {
            candidates.add(new SummaryFlagDto("BELOW_SMA200_DOWNTREND", "MEDIUM", Map.of()));
        }
        if (band.historicalMid() != null && band.peerMid() != null
                && band.historicalMid().signum() > 0 && band.peerMid().signum() > 0) {
            BigDecimal minMid = band.historicalMid().min(band.peerMid());
            BigDecimal diffRatio = band.historicalMid().subtract(band.peerMid(), MC).abs().divide(minMid, 4, RoundingMode.HALF_UP);
            if (diffRatio.compareTo(ANCHOR_DIVERGENCE_THRESHOLD) >= 0) {
                candidates.add(new SummaryFlagDto("ANCHOR_DIVERGENCE", "LOW", Map.of(
                        "historicalMid", band.historicalMid(),
                        "peerMid", band.peerMid()
                )));
            }
        }

        candidates.sort(Comparator.comparingInt(flag -> severityRank(flag.severity())));
        int highCount = 0;
        for (SummaryFlagDto flag : candidates) {
            if ("HIGH".equals(flag.severity())) highCount++;
        }
        int cap = Math.max(3, highCount);
        return candidates.size() <= cap ? List.copyOf(candidates) : List.copyOf(candidates.subList(0, cap));
    }

    private List<WatchItemDto> buildWatchItems(
            String verdict,
            String stance,
            String fundamentalBacking,
            boolean forwardValueTrap,
            FairValueBandDto band,
            LevelZoneDto supportZone,
            LocalDate nextBlockingEventDate,
            boolean stopBreachedOrReclaim,
            MarketRegime marketRegime,
            String rightSideConfirmationBasis,
            TrendPullbackStarterDto starter) {
        List<WatchItemDto> items = new ArrayList<>();
        if (starter != null && starter.eligible()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("anchor", starter.matchedAnchor());
            params.put("anchorPrice", starter.anchorPrice());
            params.put("suggestedStop", starter.suggestedStop());
            params.put("positionFraction", starter.positionFraction());
            if (band.currency() != null) params.put("currency", band.currency());
            items.add(new WatchItemDto("WATCH_TREND_PULLBACK_STARTER", params));
        }
        if (marketRegime == MarketRegime.CORRECTION && rightSideConfirmationBasis != null
                && "WATCH_PULLBACK_TO_BAND".equals(stance)) {
            items.add(new WatchItemDto("WATCH_CORRECTION_RIGHT_SIDE", Map.of(
                    "basis", rightSideConfirmationBasis,
                    "positionScale", MACRO_POSITION_SCALE
            )));
        }
        if ("WAIT_FOR_MARKET_STABILIZATION".equals(stance) && marketRegime != null) {
            items.add(new WatchItemDto("WATCH_MARKET_STABILIZATION", Map.of("regime", marketRegime.name())));
        }
        if (band.low() != null && band.currency() != null) {
            items.add(new WatchItemDto("WATCH_REFERENCE_LOW_BAND", Map.of(
                    "priceLow", band.low(),
                    "priceMid", band.mid(),
                    "priceHigh", band.high(),
                    "currency", band.currency()
            )));
        }
        if (supportZone != null) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("lower", supportZone.lower());
            params.put("upper", supportZone.upper());
            params.put("timeframe", supportZone.timeframe() == null ? "DAILY" : supportZone.timeframe().name());
            if (band.currency() != null) params.put("currency", band.currency());
            items.add(new WatchItemDto("WATCH_TECHNICAL_SUPPORT", params));
        }
        if (stopBreachedOrReclaim) {
            items.add(new WatchItemDto("WATCH_RECLAIM_STABILIZATION", Map.of()));
        }
        if (nextBlockingEventDate != null) {
            items.add(new WatchItemDto("WATCH_EVENT_WINDOW", Map.of("eventDate", nextBlockingEventDate.toString())));
        }
        if (("WEAKENING".equals(fundamentalBacking) || forwardValueTrap) && !"INSUFFICIENT_EVIDENCE".equals(verdict)) {
            items.add(new WatchItemDto("WATCH_FUNDAMENTAL_STABILIZATION", Map.of()));
        }
        return items.size() <= 3 ? List.copyOf(items) : List.copyOf(items.subList(0, 3));
    }

    private String extractWeeklyMajorTrend(MetricResult<PriceStructureDto> priceStructure) {
        if (priceStructure == null || priceStructure.status() != MetricStatus.AVAILABLE || priceStructure.value() == null) {
            return null;
        }
        for (TimeframeStructureDto tf : priceStructure.value().timeframes()) {
            if (tf != null && tf.timeframe() == AnalysisTimeframe.WEEKLY
                    && tf.majorTrend() != null && tf.majorTrend().status() == MetricStatus.AVAILABLE
                    && tf.majorTrend().value() != null && tf.majorTrend().value().state() != null) {
                return tf.majorTrend().value().state().name();
            }
        }
        return null;
    }

    private List<NormalizedBarDto> extractDailyBars(MetricResult<PriceStructureDto> priceStructure) {
        if (priceStructure == null || priceStructure.status() != MetricStatus.AVAILABLE || priceStructure.value() == null) {
            return List.of();
        }
        for (TimeframeStructureDto tf : priceStructure.value().timeframes()) {
            if (tf != null && tf.timeframe() == AnalysisTimeframe.DAILY && !tf.bars().isEmpty()) {
                return tf.bars();
            }
        }
        return List.of();
    }

    private BigDecimal extractLatestDailyClose(MetricResult<PriceStructureDto> priceStructure) {
        List<NormalizedBarDto> bars = extractDailyBars(priceStructure);
        return bars.isEmpty() || bars.getLast() == null ? null : bars.getLast().close();
    }

    private LocalDate extractLatestDailySession(MetricResult<PriceStructureDto> priceStructure) {
        List<NormalizedBarDto> bars = extractDailyBars(priceStructure);
        return bars.isEmpty() || bars.getLast() == null ? null : bars.getLast().sessionDate();
    }

    private BigDecimal extractSma200(MetricResult<MarketContextDto> marketContext) {
        return extractIndicator(marketContext, "SMA200");
    }

    private BigDecimal extractIndicator(MetricResult<MarketContextDto> marketContext, String code) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE
                || marketContext.value() == null || marketContext.value().momentumDetail() == null) {
            return null;
        }
        return val(marketContext.value().momentumDetail().indicators().get(code));
    }

    private BigDecimal extractEarningsAvwap(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE
                || marketContext.value() == null || marketContext.value().volume() == null) {
            return null;
        }
        return val(marketContext.value().volume().earningsAnchoredVwap());
    }

    private BigDecimal extractRvol20(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE || marketContext.value() == null) {
            return null;
        }
        if (marketContext.value().volume() != null) {
            BigDecimal v = val(marketContext.value().volume().rvol20());
            if (v != null) return v;
        }
        if (marketContext.value().momentum() != null) {
            return val(marketContext.value().momentum().volumeVs20Day());
        }
        return null;
    }

    private MarketRegime extractMarketRegime(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE
                || marketContext.value() == null || marketContext.value().market() == null
                || marketContext.value().market().status() != MetricStatus.AVAILABLE
                || marketContext.value().market().value() == null) {
            return null;
        }
        MarketRegime regime = marketContext.value().market().value().regime();
        return regime == MarketRegime.UNAVAILABLE ? null : regime;
    }

    private BigDecimal extractVixClose(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE || marketContext.value() == null) {
            return null;
        }
        return val(marketContext.value().vixClose());
    }

    private BigDecimal extractTenYearChange20(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE
                || marketContext.value() == null || marketContext.value().treasury() == null) {
            return null;
        }
        return val(marketContext.value().treasury().tenYearChange20());
    }

    private BreakoutDiagnosisDto extractPrimaryDiagnosis(MetricResult<SupportResistanceDto> supportResistance) {
        if (supportResistance == null || supportResistance.status() != MetricStatus.AVAILABLE
                || supportResistance.value() == null) {
            return null;
        }
        BreakoutDiagnosisDto diag = supportResistance.value().breakoutDiagnosis();
        return diag != null && diag.diagnosis() != BreakoutDiagnosis.NONE ? diag : null;
    }

    private BreakoutDiagnosisDto findDiagnosisForZone(MetricResult<SupportResistanceDto> supportResistance, LevelZoneDto zone) {
        if (supportResistance == null || supportResistance.status() != MetricStatus.AVAILABLE
                || supportResistance.value() == null || zone == null) {
            return null;
        }
        for (BreakoutDiagnosisDto diag : supportResistance.value().zoneDiagnoses()) {
            if (diag != null && diag.matchesZone(zone)) {
                return diag;
            }
        }
        BreakoutDiagnosisDto primary = supportResistance.value().breakoutDiagnosis();
        return primary != null && primary.matchesZone(zone) ? primary : null;
    }

    private PlanConstraintDto extractConstraint(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE || marketContext.value() == null) {
            return null;
        }
        return marketContext.value().tradeConstraint();
    }

    private LocalDate extractNextBlockingEventDate(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE || marketContext.value() == null) {
            return null;
        }
        LocalDate earliest = null;
        for (EventRiskDto event : marketContext.value().events()) {
            if (event != null && event.blocksNewEntry() && event.eventDate() != null) {
                if (earliest == null || event.eventDate().isBefore(earliest)) {
                    earliest = event.eventDate();
                }
            }
        }
        return earliest;
    }

    private BigDecimal average(BigDecimal first, BigDecimal second) {
        return first.add(second, MC).divide(TWO, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal val(MetricResult<BigDecimal> metric) {
        return metric != null && metric.status() == MetricStatus.AVAILABLE ? metric.value() : null;
    }

    private static boolean hasReason(MetricResult<?> metric, String code) {
        return metric != null && metric.reasons().stream().anyMatch(r -> code.equals(r.code()));
    }

    private boolean hasPlanReason(TradePlanDto plan, String code) {
        return plan != null && plan.reasons().stream().anyMatch(r -> code.equals(r.code()));
    }

    private static int countAvailable(MetricResult<?>... metrics) {
        int count = 0;
        for (MetricResult<?> m : metrics) {
            if (m != null && m.status() == MetricStatus.AVAILABLE && m.value() != null) {
                count++;
            }
        }
        return count;
    }

    private int severityRank(String severity) {
        if ("HIGH".equals(severity)) return 0;
        if ("MEDIUM".equals(severity)) return 1;
        return 2;
    }

    private void addUniqueReason(List<AnalysisReasonDto> list, String code, String component, String message) {
        if (code == null || code.isBlank()) return;
        Set<String> seen = new LinkedHashSet<>();
        for (AnalysisReasonDto existing : list) {
            seen.add(existing.code());
        }
        if (!seen.contains(code)) {
            list.add(AnalysisReasonDto.of(code, component, message));
        }
    }
}
