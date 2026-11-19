package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.DailyOpportunityRadarId;
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
@IdClass(DailyOpportunityRadarId.class)
@Table(name = "daily_opportunity_radar", indexes = {
        @Index(name = "idx_daily_radar_date_track", columnList = "radar_date,track_type"),
        @Index(name = "idx_daily_radar_figi_date", columnList = "figi,radar_date")
})
@Comment("每日收市機會雷達快照表，按交易日與雙軌清單類型保存低估支撐低接（VALUE_SUPPORT）及強勢股趨勢回踩試單（TREND_PULLBACK_STARTER）結果，供首頁零延遲查詢與下游簡報讀取。")
public class DailyOpportunityRadar {
    public static final String TRACK_VALUE_SUPPORT = "VALUE_SUPPORT";
    public static final String TRACK_TREND_PULLBACK_STARTER = "TREND_PULLBACK_STARTER";

    @Id
    @Column(name = "radar_date", nullable = false)
    @Comment("雷達掃描對應之美東已收市交易日。")
    private LocalDate radarDate;

    @Id
    @Column(name = "track_type", nullable = false, length = 32)
    @Comment("雷達雙軌分類：VALUE_SUPPORT（低估支撐低接）或 TREND_PULLBACK_STARTER（強勢成長股趨勢回踩試單）。")
    private String trackType;

    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票 FIGI 識別碼。")
    private String figi;

    @Column(name = "symbol", nullable = false, length = 32)
    @Comment("股票最新交易代碼 ticker。")
    private String symbol;

    @Column(name = "stock_name", length = 255)
    @Comment("股票名稱。")
    private String stockName;

    @Column(name = "currency", length = 16)
    @Comment("股價交易幣別代碼（如 USD）。")
    private String currency;

    @Column(name = "industry_code", length = 96)
    @Comment("該交易日所屬產業代碼。")
    private String industryCode;

    @Column(name = "industry_name", length = 128)
    @Comment("該交易日所屬產業名稱。")
    private String industryName;

    @Column(name = "market_cap_tier", length = 16)
    @Comment("市值量級（MEGA_LARGE、MID、SMALL）；無有效 USD 市值時為 NULL。")
    private String marketCapTier;

    @Column(name = "market_regime", length = 32)
    @Comment("掃描當日大盤市況（RISK_ON、NEUTRAL、RECOVERY、CORRECTION、BEAR、PANIC）；大盤資料不足時為 NULL。")
    private String marketRegime;

    @Column(name = "verdict", nullable = false, length = 32)
    @Comment("客觀市場參考帶 [L, M, H] 估值結論。")
    private String verdict;

    @Column(name = "fundamental_backing", nullable = false, length = 32)
    @Comment("基本面支撐度結論（STRONG 或 MIXED）。")
    private String fundamentalBacking;

    @Column(name = "stance", nullable = false, length = 48)
    @Comment("與單股綜合摘要完全一致之最終觀察傾向（如 ACCUMULATE_AT_SUPPORT、TREND_PULLBACK_STARTER、WATCH_PULLBACK_TO_BAND、WAIT_FOR_MARKET_STABILIZATION）。")
    private String stance;

    @Column(name = "headwind_watch_only", nullable = false)
    @Comment("是否為大盤 BEAR／PANIC 逆風期間自動收緊後之僅觀察標的（不計入主動買入訊號）。")
    private boolean headwindWatchOnly;

    @Column(name = "macro_caution_active", nullable = false)
    @Comment("當日大盤／VIX／10Y 美債宏觀警戒是否生效。")
    private boolean macroCautionActive;

    @Column(name = "close_price", precision = 20, scale = 6)
    @Comment("掃描交易日未復權收盤價。")
    private BigDecimal closePrice;

    @Column(name = "fair_value_low", precision = 20, scale = 4)
    @Comment("客觀市場參考帶下緣價 L；無有效錨點時為 NULL。")
    private BigDecimal fairValueLow;

    @Column(name = "fair_value_mid", precision = 20, scale = 4)
    @Comment("客觀市場參考帶中位價 M；無有效錨點時為 NULL。")
    private BigDecimal fairValueMid;

    @Column(name = "fair_value_high", precision = 20, scale = 4)
    @Comment("客觀市場參考帶上緣價 H；無有效錨點時為 NULL。")
    private BigDecimal fairValueHigh;

    @Column(name = "discount_to_mid", precision = 12, scale = 6)
    @Comment("相對參考中位價 M 之折價比例（(M - close) / M，正值表示折價）；無中位價時為 NULL。")
    private BigDecimal discountToMid;

    @Column(name = "forward_peg", precision = 16, scale = 4)
    @Comment("由既有 PostgreSQL 年度共識計算之 Forward PEG；缺少年度共識或不適用時為 NULL。")
    private BigDecimal forwardPeg;

    @Column(name = "forward_pe", precision = 16, scale = 4)
    @Comment("由既有 PostgreSQL 年度共識計算之 Forward P/E；缺少年度共識或非正時為 NULL。")
    private BigDecimal forwardPe;

    @Column(name = "revenue_growth_yoy", precision = 16, scale = 6)
    @Comment("TTM 營業收入年增率（小數比例）；缺值時為 NULL。")
    private BigDecimal revenueGrowthYoy;

    @Column(name = "forward_growth_basis", length = 32)
    @Comment("次清單前瞻成長通過依據（FORWARD_PEG 或 HYPER_GROWTH_FORWARD_PE）；主清單為 NULL。")
    private String forwardGrowthBasis;

    @Column(name = "support_lower", precision = 20, scale = 4)
    @Comment("配對週線有效支撐區下緣價；次清單或無對應支撐時為 NULL。")
    private BigDecimal supportLower;

    @Column(name = "support_upper", precision = 20, scale = 4)
    @Comment("配對週線有效支撐區上緣價；次清單或無對應支撐時為 NULL。")
    private BigDecimal supportUpper;

    @Column(name = "plan_entry_lower", precision = 20, scale = 4)
    @Comment("匹配同區交易計畫之實際入場區間下緣價（區分區間限價與確認回踩上限）；無匹配計畫時為 NULL。")
    private BigDecimal planEntryLower;

    @Column(name = "plan_entry_upper", precision = 20, scale = 4)
    @Comment("匹配同區交易計畫之實際入場區間上緣價（供下游收盤簡報精準計算倉位股數）；無匹配計畫時為 NULL。")
    private BigDecimal planEntryUpper;

    @Column(name = "support_distance_pct", precision = 12, scale = 6)
    @Comment("收盤價距配對支撐上緣之距離比例（位於支撐區內為 0，高於上緣為 (close - upper) / upper）；無支撐時為 NULL。")
    private BigDecimal supportDistancePct;

    @Column(name = "pullback_anchor", length = 32)
    @Comment("次清單命中的回踩均線或成本線錨點（EMA21、SMA50 或 EARNINGS_AVWAP）；主清單為 NULL。")
    private String pullbackAnchor;

    @Column(name = "pullback_anchor_price", precision = 20, scale = 4)
    @Comment("次清單命中回踩錨點之對應價格；主清單為 NULL。")
    private BigDecimal pullbackAnchorPrice;

    @Column(name = "protective_stop", precision = 20, scale = 4)
    @Comment("保護止損價（主清單來自匹配支撐交易計畫或同區凍結止損，次清單來自回踩試單建議止損）；不可用時為 NULL。")
    private BigDecimal protectiveStop;

    @Column(name = "first_target_price", precision = 20, scale = 4)
    @Comment("匹配交易計畫之第一止盈阻力目標價（僅當第一目標嚴格高於當日收盤價 close_price 時寫入，不跳過未突破之第一阻力；否則與 net_risk_reward 同步為 NULL）。")
    private BigDecimal firstTargetPrice;

    @Column(name = "net_risk_reward", precision = 12, scale = 4)
    @Comment("以當日收盤價（close_price）、保護止損與匹配計畫目標組計算之成本後風險報酬比（Net R/R）；第一目標低於或等於收盤價或無有效雙邊計畫時同步為 NULL。")
    private BigDecimal netRiskReward;

    @Column(name = "plan_status", length = 32)
    @Comment("匹配同區交易計畫之狀態（ACTIVE、WAITING_TRIGGER、UNAVAILABLE 等）；無匹配計畫時為 NULL。")
    private String planStatus;

    @Column(name = "position_fraction", length = 16)
    @Comment("建議試單底倉比例（如 1/3、逆風警戒 1/6）；主清單為 NULL。")
    private String positionFraction;

    @Column(name = "next_earnings_date")
    @Comment("掃描截點已知之下一個未取消財報事件日期；無已知未來財報時為 NULL。")
    private LocalDate nextEarningsDate;

    @Column(name = "days_to_next_earnings")
    @Comment("距下一個已知財報事件之日曆天數；無已知未來財報時為 NULL。")
    private Integer daysToNextEarnings;

    @Column(name = "sector_cmf_20d", precision = 10, scale = 6)
    @Comment("所屬產業當日近 20 個交易日成交額加權量價流向（CMF，範圍 -1 至 +1）；資料不足時為 NULL。")
    private BigDecimal sectorCmf20d;

    @Column(name = "sector_flow_state", length = 16)
    @Comment("所屬產業資金流向標籤（INFLOW、NEUTRAL、OUTFLOW）；資料不足時為 NULL。")
    private String sectorFlowState;

    @Column(name = "observation_cutoff", nullable = false)
    @Comment("本輪掃描固定採用之資料觀察截點（UTC）。")
    private Instant observationCutoff;

    @Column(name = "calculated_at", nullable = false)
    @Comment("本次雷達快照計算與寫入時間（UTC）。")
    private Instant calculatedAt;
}
