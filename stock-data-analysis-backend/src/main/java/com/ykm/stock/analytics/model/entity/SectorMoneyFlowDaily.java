package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.SectorMoneyFlowDailyId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@IdClass(SectorMoneyFlowDailyId.class)
@Table(name = "sector_money_flow_daily", indexes = {
        @Index(name = "idx_sector_money_flow_date", columnList = "trade_date"),
        @Index(name = "idx_sector_money_flow_industry_date", columnList = "industry_code,trade_date")
})
@Comment("各產業板塊每日成交額加權量價資金流（CMF）、市場成交額佔比變化、內部均線廣度與同業橫截面報酬中位數快照（統計母體為 USD 追蹤營運股）。")
public class SectorMoneyFlowDaily {
    public static final String FLOW_INFLOW = "INFLOW";
    public static final String FLOW_NEUTRAL = "NEUTRAL";
    public static final String FLOW_OUTFLOW = "OUTFLOW";

    @Id
    @Column(name = "trade_date", nullable = false)
    @Comment("統計對應之美東已收市交易日。")
    private LocalDate tradeDate;

    @Id
    @Column(name = "industry_code", nullable = false, length = 96)
    @Comment("產業分類代碼（對應 stock_industry.code）。")
    private String industryCode;

    @Column(name = "industry_name", nullable = false, length = 128)
    @Comment("產業分類名稱。")
    private String industryName;

    @Column(name = "stock_count", nullable = false)
    @Comment("當日具有效量價之該產業成分股檔數（USD 追蹤營運股）。")
    private int stockCount;

    @Column(name = "market_total_stock_count", nullable = false)
    @Comment("當日全市場 USD 追蹤營運股總有效檔數（含未分類標的）。")
    private int marketTotalStockCount;

    @Column(name = "classified_coverage_pct", precision = 8, scale = 6)
    @Comment("當日全市場有效營運股中已完成產業分類之檔數覆蓋率（0 至 1）。")
    private BigDecimal classifiedCoveragePct;

    @Column(name = "dollar_volume_1d", precision = 24, scale = 2)
    @Comment("該產業當日總成交額（USD，raw close × raw volume 加總）。")
    private BigDecimal dollarVolume1d;

    @Column(name = "dollar_volume_5d", precision = 24, scale = 2)
    @Comment("該產業近 5 個交易日累積總成交額（USD）。")
    private BigDecimal dollarVolume5d;

    @Column(name = "dollar_volume_20d", precision = 24, scale = 2)
    @Comment("該產業近 20 個交易日累積總成交額（USD）。")
    private BigDecimal dollarVolume20d;

    @Column(name = "market_volume_share_1d", precision = 10, scale = 6)
    @Comment("該產業當日成交額佔全市場 USD 追蹤營運股總成交額比例（0 至 1）。")
    private BigDecimal marketVolumeShare1d;

    @Column(name = "market_volume_share_5d", precision = 10, scale = 6)
    @Comment("該產業近 5 個交易日累積成交額佔全市場同期總成交額比例（0 至 1）。")
    private BigDecimal marketVolumeShare5d;

    @Column(name = "market_volume_share_20d", precision = 10, scale = 6)
    @Comment("該產業近 20 個交易日累積成交額佔全市場同期總成交額比例（0 至 1）。")
    private BigDecimal marketVolumeShare20d;

    @Column(name = "volume_share_delta_5d_vs_20d", precision = 10, scale = 6)
    @Comment("近 5 日成交額市場佔比減近 20 日市場佔比（正值代表大資金加速流入該板塊）。")
    private BigDecimal volumeShareDelta5dVs20d;

    @Column(name = "cmf_5d", precision = 10, scale = 6)
    @Comment("近 5 個交易日成交額加權量價流向 Σ(MFM × DollarVolume) ÷ Σ(DollarVolume)，範圍 -1 至 +1；總成交額為 0 時為 NULL。")
    private BigDecimal cmf5d;

    @Column(name = "cmf_20d", precision = 10, scale = 6)
    @Comment("近 20 個交易日成交額加權量價流向，範圍 -1 至 +1；總成交額為 0 時為 NULL。")
    private BigDecimal cmf20d;

    @Column(name = "breadth_above_sma50_count", nullable = false)
    @Comment("該產業內經拆股對齊後收盤價站上 SMA50（close >= SMA50）之檔數。")
    private int breadthAboveSma50Count;

    @Column(name = "breadth_sma50_total", nullable = false)
    @Comment("該產業內具備完整 50 個交易日拆股對齊日線之有效分母檔數。")
    private int breadthSma50Total;

    @Column(name = "breadth_above_sma50_pct", precision = 8, scale = 6)
    @Comment("站上 SMA50 廣度比例（0 至 1）；分母為 0 時為 NULL。")
    private BigDecimal breadthAboveSma50Pct;

    @Column(name = "breadth_above_sma200_count", nullable = false)
    @Comment("該產業內經拆股對齊後收盤價站上 SMA200（close >= SMA200）之檔數。")
    private int breadthAboveSma200Count;

    @Column(name = "breadth_sma200_total", nullable = false)
    @Comment("該產業內具備完整 200 個交易日拆股對齊日線之有效分母檔數。")
    private int breadthSma200Total;

    @Column(name = "breadth_above_sma200_pct", precision = 8, scale = 6)
    @Comment("站上 SMA200 廣度比例（0 至 1）；分母為 0 時為 NULL。")
    private BigDecimal breadthAboveSma200Pct;

    @Column(name = "return_20d_median", precision = 12, scale = 6)
    @Comment("該產業成分股經拆股對齊之近 20 個交易日報酬橫截面中位數（小數比例）；無有效樣本時為 NULL。")
    private BigDecimal return20dMedian;

    @Column(name = "return_20d_count", nullable = false)
    @Comment("參與計算 20D 報酬中位數之有效成分股檔數。")
    private int return20dCount;

    @Column(name = "return_60d_median", precision = 12, scale = 6)
    @Comment("該產業成分股經拆股對齊之近 60 個交易日報酬橫截面中位數（小數比例）；無有效樣本時為 NULL。")
    private BigDecimal return60dMedian;

    @Column(name = "return_60d_count", nullable = false)
    @Comment("參與計算 60D 報酬中位數之有效成分股檔數。")
    private int return60dCount;

    @Column(name = "flow_state", length = 16)
    @Comment("資金流向狀態（INFLOW：5D與20D CMF皆正；OUTFLOW：皆負；NEUTRAL：分歧或為零；任一缺值時為 NULL）。")
    private String flowState;

    @Column(name = "calculated_at", nullable = false)
    @Comment("本筆板塊資金流快照之計算與寫入時間（UTC）。")
    private Instant calculatedAt;
}
