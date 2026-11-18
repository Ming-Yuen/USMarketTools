package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EventRiskDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.FairValueBandDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.SummaryFlagDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto.WatchItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRangeDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    private record YieldSpreadSelection(MetricResult<BigDecimal> spread, String basis) {}

    private record BandSynthesis(String anchorSource, FairValueBandDto band, List<AnalysisReasonDto> anchorReasons) {}

    public MetricResult<InvestmentSummaryDto> calculate(
            ValuationResultDto valuation,
            FinancialsDto financials,
            FundamentalSnapshotDto fundamentals,
            MetricResult<PriceStructureDto> priceStructure,
            MetricResult<SupportResistanceDto> supportResistance,
            MetricResult<TradePlanResultDto> tradePlans,
            MetricResult<MarketContextDto> marketContext) {
        return calculate(AnalysisHorizon.POSITION, valuation, financials, fundamentals,
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
            BigDecimal deepFloor = band.low().multiply(DEEP_DISCOUNT_FACTOR, MC);
            BigDecimal riskCeiling = band.high().multiply(OVERVALUED_RISK_FACTOR, MC);
            if (price.compareTo(deepFloor) < 0) {
                verdict = "DEEP_UNDERVALUED";
            } else if (price.compareTo(band.low()) <= 0) {
                verdict = "MODERATELY_UNDERVALUED";
            } else if (price.compareTo(band.mid()) <= 0) {
                verdict = "FAIR_LOWER";
            } else if (price.compareTo(band.high()) <= 0) {
                verdict = "FAIR_UPPER";
            } else if (price.compareTo(riskCeiling) <= 0) {
                verdict = "MODERATELY_OVERVALUED";
            } else {
                verdict = "OVERVALUED_RISK";
            }
        }

        String fundamentalBacking = evaluateFundamentalBacking(fundamentals, financials);
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

        List<TradePlanDto> planList = tradePlans != null && tradePlans.status() == MetricStatus.AVAILABLE && tradePlans.value() != null
                ? tradePlans.value().plans() : List.of();
        AnalysisHorizon effectiveHorizon = horizon == null ? AnalysisHorizon.POSITION : horizon;
        LevelZoneDto supportZone = selectTechnicalSupportZone(effectiveHorizon, supportResistance, planList, dailyLatestClose, price, band.low());
        boolean stopBreachedOrReclaim = hasStopBreachedOrReclaim(planList, supportZone);
        boolean confirmationPending = hasConfirmationPending(planList, supportZone, permission);

        String stance = resolveStance(
                verdict, fundamentalBacking, weeklyTrend, aboveSma200,
                permission, eventGateBlocked, stopBreachedOrReclaim, confirmationPending, supportZone != null);

        List<SummaryFlagDto> risks = buildRiskFlags(
                verdict, fundamentals, financials, band, weeklyTrend, aboveSma200,
                eventGateBlocked, nextBlockingEventDate, stopBreachedOrReclaim);
        List<WatchItemDto> watchItems = buildWatchItems(
                verdict, fundamentalBacking, band, supportZone, nextBlockingEventDate, stopBreachedOrReclaim);

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
                summaryReasons
        );
        return MetricResult.available(dto);
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
        return ref != null
                && "AVAILABLE".equals(ref.status())
                && ref.priceLow() != null
                && ref.priceMedian() != null
                && ref.priceHigh() != null
                && ref.priceLow().signum() > 0
                && ref.priceMedian().compareTo(ref.priceLow()) >= 0
                && ref.priceHigh().compareTo(ref.priceMedian()) >= 0;
    }

    private String evaluateFundamentalBacking(FundamentalSnapshotDto f, FinancialsDto financials) {
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
        boolean currentLoss = financials != null && financials.epsTtm() != null
                && "AVAILABLE".equals(financials.epsTtm().status())
                && financials.epsTtm().value() != null && financials.epsTtm().value().signum() <= 0;
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
        if (availableCount < 2 && !swungToLoss && !currentLoss) {
            return "INSUFFICIENT_DATA";
        }

        boolean negativeFcf = fcfMargin != null && fcfMargin.signum() <= 0;
        boolean weakening = (revYoy != null && revYoy.compareTo(WEAK_REV_YOY) <= 0)
                || (epsYoy != null && epsYoy.compareTo(WEAK_EPS_YOY) <= 0)
                || swungToLoss
                || (negativeFcf && !highRevGrowth)
                || (currentLoss && !(highRevGrowth && fcfMargin != null && fcfMargin.signum() > 0))
                || (debtEbitda != null && debtEbitda.compareTo(WEAK_DEBT_EBITDA) > 0);
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

    private LevelZoneDto selectTechnicalSupportZone(
            AnalysisHorizon horizon,
            MetricResult<SupportResistanceDto> supportResistance,
            List<TradePlanDto> plans,
            BigDecimal dailyLatestClose,
            BigDecimal currentPrice,
            BigDecimal bandLow) {
        if (supportResistance == null || supportResistance.status() != MetricStatus.AVAILABLE || supportResistance.value() == null) {
            return null;
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
            if (isZoneStopBreached(z, plans, dailyLatestClose)) {
                continue;
            }
            eligible.add(z);
        }
        if (eligible.isEmpty()) {
            return null;
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
        return eligible.getFirst();
    }

    private boolean isZoneStopBreached(LevelZoneDto zone, List<TradePlanDto> plans, BigDecimal dailyLatestClose) {
        for (TradePlanDto plan : plans) {
            if (plan != null && zone.id().equals(plan.sourceZoneId())
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
        return false;
    }

    private boolean hasStopBreachedOrReclaim(List<TradePlanDto> plans, LevelZoneDto selectedZone) {
        if (plans == null || plans.isEmpty()) {
            return false;
        }
        if (selectedZone != null) {
            for (TradePlanDto plan : plans) {
                if (plan != null && selectedZone.id().equals(plan.sourceZoneId())
                        && (hasPlanReason(plan, "PROTECTIVE_STOP_BREACHED")
                                || hasPlanReason(plan, "RECLAIM_REQUIRED")
                                || hasPlanReason(plan, "TECHNICAL_INVALIDATION_CONFIRMED"))) {
                    return true;
                }
            }
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

    private String resolveStance(
            String verdict,
            String fundamentalBacking,
            String weeklyTrend,
            Boolean aboveSma200,
            EntryPermission permission,
            boolean eventGateBlocked,
            boolean stopBreachedOrReclaim,
            boolean confirmationPending,
            boolean hasTechnicalSupport) {
        if ("INSUFFICIENT_EVIDENCE".equals(verdict)) {
            return "INSUFFICIENT_EVIDENCE";
        }

        boolean undervalued = "DEEP_UNDERVALUED".equals(verdict) || "MODERATELY_UNDERVALUED".equals(verdict);
        boolean downtrendBelowSma200 = "DOWNTREND".equals(weeklyTrend) && Boolean.FALSE.equals(aboveSma200);
        boolean entryBlocked = eventGateBlocked
                || permission == EntryPermission.BLOCK
                || permission == EntryPermission.UNKNOWN;

        if (undervalued) {
            // 若下方交易方案已判定跌破保護止損、技術失效或低於入場區待收復，強制退回等待止穩，避免上下矛盾
            if (stopBreachedOrReclaim) {
                return "WAIT_FOR_STABILIZATION";
            }
            if ("WEAKENING".equals(fundamentalBacking)) {
                return "CAUTION_VALUE_TRAP";
            }
            if (downtrendBelowSma200 || entryBlocked || confirmationPending) {
                return "WAIT_FOR_STABILIZATION";
            }
            return hasTechnicalSupport ? "ACCUMULATE_AT_SUPPORT" : "WATCH_PULLBACK_TO_BAND";
        }

        if ("FAIR_LOWER".equals(verdict)) {
            if (stopBreachedOrReclaim || downtrendBelowSma200 || entryBlocked) {
                return "WAIT_FOR_STABILIZATION";
            }
            if ("WEAKENING".equals(fundamentalBacking)) {
                return "CAUTION_VALUE_TRAP";
            }
            return "WATCH_PULLBACK_TO_BAND";
        }

        if ("FAIR_UPPER".equals(verdict)) {
            if ("STRONG".equals(fundamentalBacking) && "UPTREND".equals(weeklyTrend)) {
                return "HOLD_TRAIL_NO_CHASE";
            }
            if ("WEAKENING".equals(fundamentalBacking)) {
                return "CAUTION_VALUE_TRAP";
            }
            return "WATCH_PULLBACK_TO_BAND";
        }

        if ("MODERATELY_OVERVALUED".equals(verdict)) {
            if ("STRONG".equals(fundamentalBacking) && "UPTREND".equals(weeklyTrend)) {
                return "HOLD_TRAIL_NO_CHASE";
            }
            return "CAUTION_OVEREXTENSION";
        }

        return "CAUTION_OVEREXTENSION";
    }

    private List<SummaryFlagDto> buildRiskFlags(
            String verdict,
            FundamentalSnapshotDto f,
            FinancialsDto financials,
            FairValueBandDto band,
            String weeklyTrend,
            Boolean aboveSma200,
            boolean eventGateBlocked,
            LocalDate nextBlockingEventDate,
            boolean stopBreachedOrReclaim) {
        List<SummaryFlagDto> candidates = new ArrayList<>();

        if (eventGateBlocked) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (nextBlockingEventDate != null) {
                params.put("eventDate", nextBlockingEventDate.toString());
            }
            candidates.add(new SummaryFlagDto("EARNINGS_IMMINENT_BLOCK", "HIGH", params));
        }
        if (stopBreachedOrReclaim) {
            candidates.add(new SummaryFlagDto("PROTECTIVE_STOP_OR_RECLAIM_PENDING", "HIGH", Map.of()));
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
            if (debtEbitda != null && debtEbitda.compareTo(STRONG_MAX_DEBT_EBITDA) > 0) {
                String sev = debtEbitda.compareTo(WEAK_DEBT_EBITDA) > 0 ? "HIGH" : "MEDIUM";
                candidates.add(new SummaryFlagDto("HIGH_LEVERAGE_DEBT_EBITDA", sev, Map.of("netDebtToEbitda", debtEbitda)));
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
        return candidates.size() <= 3 ? List.copyOf(candidates) : List.copyOf(candidates.subList(0, 3));
    }

    private List<WatchItemDto> buildWatchItems(
            String verdict,
            String fundamentalBacking,
            FairValueBandDto band,
            LevelZoneDto supportZone,
            LocalDate nextBlockingEventDate,
            boolean stopBreachedOrReclaim) {
        List<WatchItemDto> items = new ArrayList<>();
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
        if ("WEAKENING".equals(fundamentalBacking) && !"INSUFFICIENT_EVIDENCE".equals(verdict)) {
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

    private BigDecimal extractLatestDailyClose(MetricResult<PriceStructureDto> priceStructure) {
        if (priceStructure == null || priceStructure.status() != MetricStatus.AVAILABLE || priceStructure.value() == null) {
            return null;
        }
        for (TimeframeStructureDto tf : priceStructure.value().timeframes()) {
            if (tf != null && tf.timeframe() == AnalysisTimeframe.DAILY && !tf.bars().isEmpty()) {
                NormalizedBarDto last = tf.bars().getLast();
                return last == null ? null : last.close();
            }
        }
        return null;
    }

    private BigDecimal extractSma200(MetricResult<MarketContextDto> marketContext) {
        if (marketContext == null || marketContext.status() != MetricStatus.AVAILABLE
                || marketContext.value() == null || marketContext.value().momentumDetail() == null) {
            return null;
        }
        MetricResult<BigDecimal> sma200 = marketContext.value().momentumDetail().indicators().get("SMA200");
        return val(sma200);
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

    private BigDecimal val(MetricResult<BigDecimal> metric) {
        return metric != null && metric.status() == MetricStatus.AVAILABLE ? metric.value() : null;
    }

    private boolean hasReason(MetricResult<?> metric, String code) {
        return metric != null && metric.reasons().stream().anyMatch(r -> code.equals(r.code()));
    }

    private boolean hasPlanReason(TradePlanDto plan, String code) {
        return plan != null && plan.reasons().stream().anyMatch(r -> code.equals(r.code()));
    }

    private int countAvailable(MetricResult<?>... metrics) {
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
