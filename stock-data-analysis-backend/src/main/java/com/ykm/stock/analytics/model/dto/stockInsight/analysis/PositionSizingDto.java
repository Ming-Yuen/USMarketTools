package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 基於單筆風險預算（預設 1%）、單檔資金上限（預設 15%）、Batch 02 縮倉係數（1/3、1/2、1/6、暫緩開倉）與同產業集中度上限（預設 30%）之倉位風控計算結果。
 * 僅支援 USD 報價標的，絕不跨幣別自動匯兌；金額為按止損價估算之模型毛虧損與名義倉位（不含隔夜跳空保證）。
 */
public record PositionSizingDto(
        String status,
        String reasonCode,
        boolean planActionable,
        Long suggestedShares,
        BigDecimal entryReferencePrice,
        BigDecimal protectiveStopPrice,
        BigDecimal riskPerShare,
        BigDecimal estimatedPositionAmount,
        BigDecimal positionCapitalPct,
        BigDecimal maxEstimatedLossAmount,
        BigDecimal maxEstimatedLossPct,
        BigDecimal totalCapital,
        BigDecimal riskPerTradePct,
        BigDecimal maxSinglePositionPct,
        BigDecimal maxSectorExposurePct,
        int scaleNumerator,
        int scaleDenominator,
        String scaleLabel,
        BigDecimal existingHoldingMarketValue,
        String industryCode,
        BigDecimal currentSectorExposurePct,
        BigDecimal projectedSectorExposurePct,
        boolean sectorConcentrationWarning,
        int excludedHoldingsCount,
        List<String> sizingTags
) {
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";

    public static final String TAG_TREND_PULLBACK_STARTER_ONE_THIRD = "TREND_PULLBACK_STARTER_ONE_THIRD";
    public static final String TAG_MACRO_HEADWIND_HALF_SIZE = "MACRO_HEADWIND_HALF_SIZE";
    public static final String TAG_SECTOR_BREADTH_HEADWIND_HALF_SIZE = "SECTOR_BREADTH_HEADWIND_HALF_SIZE";
    public static final String TAG_MARKET_REGIME_PAUSE_NEW_ENTRY = "MARKET_REGIME_PAUSE_NEW_ENTRY";
    public static final String TAG_SECTOR_CONCENTRATION_WARNING = "SECTOR_CONCENTRATION_WARNING";
    public static final String TAG_EXISTING_HOLDING_CAPPED = "EXISTING_HOLDING_CAPPED";
    public static final String TAG_WAITING_TRIGGER_ESTIMATE = "WAITING_TRIGGER_ESTIMATE";
    public static final String TAG_STALE_QUOTE_DATE_FALLBACK = "STALE_QUOTE_DATE_FALLBACK";
    public static final String TAG_BUDGET_BELOW_ONE_SHARE = "BUDGET_BELOW_ONE_SHARE";
    public static final String TAG_PARTIAL_PORTFOLIO_COVERAGE = "PARTIAL_PORTFOLIO_COVERAGE";

    public PositionSizingDto {
        sizingTags = sizingTags == null ? List.of() : List.copyOf(sizingTags);
    }

    public static PositionSizingDto notApplicable(String reasonCode,
                                                  BigDecimal totalCapital,
                                                  BigDecimal riskPerTradePct,
                                                  BigDecimal maxSinglePositionPct,
                                                  BigDecimal maxSectorExposurePct,
                                                  String industryCode) {
        return new PositionSizingDto(
                STATUS_NOT_APPLICABLE, reasonCode, false, null,
                null, null, null, null, null, null, null,
                totalCapital, riskPerTradePct, maxSinglePositionPct, maxSectorExposurePct,
                1, 1, "NONE", null, industryCode, null, null, false, 0, List.of());
    }
}
