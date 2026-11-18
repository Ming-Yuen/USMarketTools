package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 個股分析置頂綜合估值位置與多維合流觀察摘要（客觀市場比較基準，不等同內在價值或買賣指令）。
 * 單位約定：
 * - discountToMid（(mid - price) / mid）、upsideToMid（(mid - price) / price）與 yieldSpreadVs10y 均為小數比例
 *   （例如 0.0065 代表 +0.65% / +65 bps；TreasuryParYieldDaily.tenYear 的百分比數值如 4.35 已先除以 100 對齊為 0.0435）。
 * - fairValue.[low, mid, high] 僅由客觀市場錨點（HISTORICAL_MARKET 與 PEER_MARKET）合成，嚴格排除 INDUSTRY_ASSUMPTION。
 */
public record InvestmentSummaryDto(
        String verdict,
        String fundamentalBacking,
        String stance,
        String primaryMetric,
        String anchorSource,
        FairValueBandDto fairValue,
        BigDecimal discountToMid,
        BigDecimal upsideToMid,
        MetricResult<BigDecimal> yieldSpreadVs10y,
        String yieldSpreadBasis,
        String technicalSupportZoneId,
        PriceRangeDto technicalSupportRange,
        String technicalSupportTimeframe,
        BigDecimal technicalSupportStrength,
        String weeklyTrend,
        Boolean aboveSma200,
        String entryPermission,
        boolean eventGateBlocked,
        LocalDate nextBlockingEventDate,
        List<SummaryFlagDto> risks,
        List<WatchItemDto> watchItems,
        List<AnalysisReasonDto> reasons
) {
    public InvestmentSummaryDto {
        risks = risks == null ? List.of() : List.copyOf(risks);
        watchItems = watchItems == null ? List.of() : List.copyOf(watchItems);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** 客觀市場綜合參考帶 [low, mid, high] 與三組原始錨點並列數值（產業假設僅作獨立對照，不混入合成）。 */
    public record FairValueBandDto(
            BigDecimal low,
            BigDecimal mid,
            BigDecimal high,
            String currency,
            BigDecimal historicalLow,
            BigDecimal historicalMid,
            BigDecimal historicalHigh,
            BigDecimal peerLow,
            BigDecimal peerMid,
            BigDecimal peerHigh,
            BigDecimal assumptionLow,
            BigDecimal assumptionMid,
            BigDecimal assumptionHigh
    ) {}

    public record SummaryFlagDto(
            String code,
            String severity,
            Map<String, Object> params
    ) {
        public SummaryFlagDto {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    public record WatchItemDto(
            String code,
            Map<String, Object> params
    ) {
        public WatchItemDto {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
