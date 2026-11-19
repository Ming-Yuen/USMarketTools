package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 個股 vs 同業（產業或自訂對標）中位數 vs SPY 之 20D／60D 相對強弱對照，供個股頁同業面板展示。
 */
public record PeerRelativeStrengthDto(
        String peerBasis,
        String industryCode,
        String industryName,
        LocalDate tradeDate,
        BigDecimal stockReturn20d,
        BigDecimal peerReturn20d,
        BigDecimal spyReturn20d,
        BigDecimal stockVsPeer20d,
        BigDecimal peerVsSpy20d,
        Integer peerSampleCount20d,
        BigDecimal stockReturn60d,
        BigDecimal peerReturn60d,
        BigDecimal spyReturn60d,
        BigDecimal stockVsPeer60d,
        BigDecimal peerVsSpy60d,
        Integer peerSampleCount60d,
        BigDecimal sectorCmf20d,
        String sectorFlowState,
        BigDecimal breadthAboveSma50Pct,
        BigDecimal breadthAboveSma200Pct,
        String relativeLabelCode
) {
    public static final String BASIS_INDUSTRY_MEDIAN = "INDUSTRY_MEDIAN";
    public static final String BASIS_CUSTOM_PEERS_MEDIAN = "CUSTOM_PEERS_MEDIAN";

    public static final String LABEL_SECTOR_STRONG_STOCK_LEADING = "SECTOR_STRONG_STOCK_LEADING";
    public static final String LABEL_SECTOR_STRONG_STOCK_LAGGING = "SECTOR_STRONG_STOCK_LAGGING";
    public static final String LABEL_SECTOR_WEAK_STOCK_OUTPERFORMING = "SECTOR_WEAK_STOCK_OUTPERFORMING";
    public static final String LABEL_SECTOR_WEAK_COLLECTIVE_DECLINE = "SECTOR_WEAK_COLLECTIVE_DECLINE";
    public static final String LABEL_NEUTRAL_INLINE = "NEUTRAL_INLINE";
}
