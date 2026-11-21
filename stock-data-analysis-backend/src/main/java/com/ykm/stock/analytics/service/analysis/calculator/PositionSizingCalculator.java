package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 基於單筆風險預算（預設 1%）、單檔資金上限（預設 15%，含既有持倉剩餘空間約束）、
 * Batch 02 強勢試單 1/3 ＆ 大盤逆風／高波動 1/2 縮倉疊加、以及同產業集中度檢查（預設 30%）的純計算器。
 * 嚴禁跨幣別自動匯兌：非 USD 標的回傳 NON_USD_CURRENCY_NO_FX。
 */
@Component
public class PositionSizingCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;

    public record SizingInputs(
            String currency,
            String industryCode,
            BigDecimal entryUpperOrAnchor,
            BigDecimal currentPrice,
            BigDecimal protectiveStopPrice,
            boolean planActionable,
            boolean staleQuoteDateFallback,
            boolean starterSetup,
            MarketRegime marketRegime,
            boolean macroCautionActive,
            boolean sectorBreadthBreakdown,
            PortfolioRiskConfigDto config,
            BigDecimal existingHoldingMarketValueUsd,
            BigDecimal currentSectorMarketValueUsd,
            int excludedHoldingsCount
    ) {}

    public PositionSizingDto calculate(SizingInputs inputs) {
        if (inputs == null || inputs.config() == null) {
            return PositionSizingDto.notApplicable("PORTFOLIO_CONFIG_MISSING", null, null, null, null, null);
        }
        PortfolioRiskConfigDto cfg = inputs.config();
        BigDecimal totalCapital = cfg.totalCapital();
        BigDecimal riskPct = cfg.riskPerTradePct();
        BigDecimal maxSinglePct = cfg.maxSinglePositionPct();
        BigDecimal maxSectorPct = cfg.maxSectorExposurePct();
        String industryCode = inputs.industryCode();

        if (totalCapital == null || totalCapital.signum() <= 0
                || riskPct == null || riskPct.signum() <= 0
                || maxSinglePct == null || maxSinglePct.signum() <= 0
                || maxSectorPct == null || maxSectorPct.signum() <= 0) {
            return PositionSizingDto.notApplicable("INVALID_PORTFOLIO_CONFIG",
                    totalCapital, riskPct, maxSinglePct, maxSectorPct, industryCode);
        }
        if (inputs.currency() == null || inputs.currency().isBlank()) {
            return PositionSizingDto.notApplicable("CURRENCY_MISSING",
                    totalCapital, riskPct, maxSinglePct, maxSectorPct, industryCode);
        }
        if (!"USD".equalsIgnoreCase(inputs.currency().trim())) {
            return PositionSizingDto.notApplicable("NON_USD_CURRENCY_NO_FX",
                    totalCapital, riskPct, maxSinglePct, maxSectorPct, industryCode);
        }
        if (inputs.marketRegime() == null || inputs.marketRegime() == MarketRegime.UNAVAILABLE) {
            return PositionSizingDto.notApplicable("MARKET_REGIME_UNAVAILABLE",
                    totalCapital, riskPct, maxSinglePct, maxSectorPct, industryCode);
        }

        BigDecimal entryRef = resolveEntryReferencePrice(inputs.entryUpperOrAnchor(), inputs.currentPrice());
        BigDecimal stop = inputs.protectiveStopPrice();
        if (entryRef == null || entryRef.signum() <= 0 || stop == null || stop.signum() <= 0 || entryRef.compareTo(stop) <= 0) {
            return PositionSizingDto.notApplicable("INVALID_PRICE_OR_STOP",
                    totalCapital, riskPct, maxSinglePct, maxSectorPct, industryCode);
        }

        BigDecimal riskPerShare = entryRef.subtract(stop, MC);
        BigDecimal existingHoldingMv = inputs.existingHoldingMarketValueUsd() == null
                ? BigDecimal.ZERO : inputs.existingHoldingMarketValueUsd().max(BigDecimal.ZERO);
        BigDecimal currentSectorMv = inputs.currentSectorMarketValueUsd() == null
                ? BigDecimal.ZERO : inputs.currentSectorMarketValueUsd().max(BigDecimal.ZERO);
        BigDecimal currentSectorPct = industryCode == null ? null
                : currentSectorMv.divide(totalCapital, 6, RoundingMode.HALF_UP);

        // 大盤 BEAR / PANIC：暫緩新開倉（suggestedShares = 0）
        if (inputs.marketRegime() == MarketRegime.BEAR || inputs.marketRegime() == MarketRegime.PANIC) {
            List<String> pausedTags = new ArrayList<>();
            pausedTags.add(PositionSizingDto.TAG_MARKET_REGIME_PAUSE_NEW_ENTRY);
            boolean currentSectorExceeded = industryCode != null
                    && currentSectorMv.compareTo(totalCapital.multiply(maxSectorPct, MC)) > 0;
            if (currentSectorExceeded) {
                pausedTags.add(PositionSizingDto.TAG_SECTOR_CONCENTRATION_WARNING);
            }
            if (inputs.staleQuoteDateFallback()) {
                pausedTags.add(PositionSizingDto.TAG_STALE_QUOTE_DATE_FALLBACK);
            }
            if (inputs.excludedHoldingsCount() > 0) {
                pausedTags.add(PositionSizingDto.TAG_PARTIAL_PORTFOLIO_COVERAGE);
            }
            return new PositionSizingDto(
                    PositionSizingDto.STATUS_PAUSED,
                    PositionSizingDto.TAG_MARKET_REGIME_PAUSE_NEW_ENTRY,
                    false,
                    0L,
                    entryRef.setScale(4, RoundingMode.HALF_UP),
                    stop.setScale(4, RoundingMode.HALF_UP),
                    riskPerShare.setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                    totalCapital,
                    riskPct,
                    maxSinglePct,
                    maxSectorPct,
                    0,
                    1,
                    "PAUSED",
                    existingHoldingMv.setScale(2, RoundingMode.HALF_UP),
                    industryCode,
                    currentSectorPct,
                    currentSectorPct,
                    currentSectorExceeded,
                    inputs.excludedHoldingsCount(),
                    pausedTags
            );
        }

        // 縮倉疊加係數：大盤逆風／宏觀警戒與所屬板塊廣度崩壞合併於同一環境 1/2 縮倉層，不疊乘為 1/4（試單 1/3 疊加後為 1/6）
        List<String> tags = new ArrayList<>();
        int scaleDenominator = 1;
        if (inputs.starterSetup()) {
            scaleDenominator *= 3;
            tags.add(PositionSizingDto.TAG_TREND_PULLBACK_STARTER_ONE_THIRD);
        }
        boolean macroHalf = inputs.marketRegime() == MarketRegime.CORRECTION || inputs.macroCautionActive();
        boolean sectorHalf = inputs.sectorBreadthBreakdown();
        if (macroHalf) {
            tags.add(PositionSizingDto.TAG_MACRO_HEADWIND_HALF_SIZE);
        }
        if (sectorHalf) {
            tags.add(PositionSizingDto.TAG_SECTOR_BREADTH_HEADWIND_HALF_SIZE);
        }
        if (macroHalf || sectorHalf) {
            scaleDenominator *= 2;
        }
        String scaleLabel = scaleDenominator == 1 ? "FULL" : ("1/" + scaleDenominator);
        BigDecimal denomBd = BigDecimal.valueOf(scaleDenominator);

        // 1) 風險上限股數 = floor((totalCapital * riskPct) / (riskPerShare * scaleDenominator))
        BigDecimal baseRiskBudget = totalCapital.multiply(riskPct, MC);
        BigDecimal riskCapSharesBd = baseRiskBudget
                .divide(riskPerShare.multiply(denomBd, MC), 0, RoundingMode.FLOOR);

        // 2) 單檔上限股數（納入既有同 FIGI 持倉之剩餘空間限制，避免重複加倉突破單檔上限）
        BigDecimal fullSingleCap = totalCapital.multiply(maxSinglePct, MC);
        BigDecimal scaledSingleCap = fullSingleCap.divide(denomBd, MC);
        BigDecimal remainingSingleCap = fullSingleCap.subtract(existingHoldingMv, MC).max(BigDecimal.ZERO);
        boolean cappedByExisting = existingHoldingMv.signum() > 0 && remainingSingleCap.compareTo(scaledSingleCap) < 0;
        if (cappedByExisting) {
            tags.add(PositionSizingDto.TAG_EXISTING_HOLDING_CAPPED);
        }
        BigDecimal effectivePositionCap = scaledSingleCap.min(remainingSingleCap);
        BigDecimal positionCapSharesBd = effectivePositionCap.divide(entryRef, 0, RoundingMode.FLOOR);

        BigDecimal suggestedSharesBd = riskCapSharesBd.min(positionCapSharesBd).max(BigDecimal.ZERO);
        long suggestedShares = suggestedSharesBd.longValueExact();

        BigDecimal estimatedPosAmt = suggestedSharesBd.multiply(entryRef, MC);
        BigDecimal posCapitalPct = estimatedPosAmt.divide(totalCapital, 6, RoundingMode.HALF_UP);
        BigDecimal maxLossAmt = suggestedSharesBd.multiply(riskPerShare, MC);
        BigDecimal maxLossPct = maxLossAmt.divide(totalCapital, 6, RoundingMode.HALF_UP);

        BigDecimal projectedSectorPct = null;
        boolean sectorWarning = false;
        if (industryCode != null) {
            BigDecimal projectedSectorMv = currentSectorMv.add(estimatedPosAmt, MC);
            projectedSectorPct = projectedSectorMv.divide(totalCapital, 6, RoundingMode.HALF_UP);
            // 嚴格大於上限才觸發（剛好等於 30% 不觸發）
            sectorWarning = projectedSectorMv.compareTo(totalCapital.multiply(maxSectorPct, MC)) > 0;
            if (sectorWarning) {
                tags.add(PositionSizingDto.TAG_SECTOR_CONCENTRATION_WARNING);
            }
        }

        if (!inputs.planActionable()) {
            tags.add(PositionSizingDto.TAG_WAITING_TRIGGER_ESTIMATE);
        }
        if (inputs.staleQuoteDateFallback()) {
            tags.add(PositionSizingDto.TAG_STALE_QUOTE_DATE_FALLBACK);
        }
        boolean effectiveActionable = inputs.planActionable() && !inputs.staleQuoteDateFallback();
        String reasonCode = null;
        if (suggestedShares == 0L) {
            if (remainingSingleCap.signum() <= 0) {
                reasonCode = "SINGLE_POSITION_CAP_EXHAUSTED";
            } else {
                reasonCode = PositionSizingDto.TAG_BUDGET_BELOW_ONE_SHARE;
                tags.add(PositionSizingDto.TAG_BUDGET_BELOW_ONE_SHARE);
            }
        }
        if (inputs.excludedHoldingsCount() > 0) {
            tags.add(PositionSizingDto.TAG_PARTIAL_PORTFOLIO_COVERAGE);
        }

        return new PositionSizingDto(
                PositionSizingDto.STATUS_AVAILABLE,
                reasonCode,
                effectiveActionable,
                suggestedShares,
                entryRef.setScale(4, RoundingMode.HALF_UP),
                stop.setScale(4, RoundingMode.HALF_UP),
                riskPerShare.setScale(4, RoundingMode.HALF_UP),
                estimatedPosAmt.setScale(2, RoundingMode.HALF_UP),
                posCapitalPct,
                maxLossAmt.setScale(2, RoundingMode.HALF_UP),
                maxLossPct,
                totalCapital,
                riskPct,
                maxSinglePct,
                maxSectorPct,
                1,
                scaleDenominator,
                scaleLabel,
                existingHoldingMv.setScale(2, RoundingMode.HALF_UP),
                industryCode,
                currentSectorPct,
                projectedSectorPct,
                sectorWarning,
                inputs.excludedHoldingsCount(),
                tags
        );
    }

    /** 以計畫入場上限 entry.upper() 與現價之較高者為保守計算基準。 */
    private BigDecimal resolveEntryReferencePrice(BigDecimal entryUpperOrAnchor, BigDecimal currentPrice) {
        if (entryUpperOrAnchor != null && entryUpperOrAnchor.signum() > 0
                && currentPrice != null && currentPrice.signum() > 0) {
            return entryUpperOrAnchor.max(currentPrice);
        }
        if (entryUpperOrAnchor != null && entryUpperOrAnchor.signum() > 0) {
            return entryUpperOrAnchor;
        }
        return currentPrice;
    }
}
