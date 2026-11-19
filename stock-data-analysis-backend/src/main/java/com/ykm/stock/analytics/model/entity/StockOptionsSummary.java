package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.StockOptionsSummaryId;
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

/** 個股／ETF 輕量期權籌碼輔助摘要；按目標標的按需或每日快照落庫，絕不掃描全市場。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@IdClass(StockOptionsSummaryId.class)
@Table(name = "stock_options_summary", indexes = {
        @Index(name = "idx_stock_options_figi_date", columnList = "figi,snapshot_date")
})
@Comment("個股與 ETF 輕量期權輔助摘要表，保存 ATM 跨式隱含波動幅度、IV／HV 對照、虛值 IV 偏度、Put/Call 比例及主力到期日最大未平倉量行權價牆。")
public class StockOptionsSummary {
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String STATUS_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票或 ETF 的 OpenFIGI 主鍵。")
    private String figi;

    @Id
    @Column(name = "snapshot_date", nullable = false)
    @Comment("此期權摘要所屬之美東交易日。")
    private LocalDate snapshotDate;

    @Column(name = "symbol", nullable = false, length = 32)
    @Comment("同步當下之交易代碼 ticker。")
    private String symbol;

    @Column(name = "source", nullable = false, length = 32)
    @Comment("期權資料來源識別，例如 ALPACA。")
    private String source;

    @Column(name = "feed", length = 24)
    @Comment("期權報價 feed 類型，例如 indicative 或 opra。")
    private String feed;

    @Column(name = "spot_price", precision = 20, scale = 6)
    @Comment("計算跨式隱含波動幅度與虛值距離時所採用之標的現價（USD）。")
    private BigDecimal spotPrice;

    @Column(name = "quote_timestamp")
    @Comment("所選主力期權合約最新報價時間（UTC）；無法取得時為 NULL。")
    private Instant quoteTimestamp;

    @Column(name = "next_earnings_date")
    @Comment("計算財報前跨式隱含波動時所參考之下一個已知財報日期；ETF 或無已知財報為 NULL。")
    private LocalDate nextEarningsDate;

    @Column(name = "straddle_expiration")
    @Comment("計算至到期 ATM 跨式隱含波動幅度所選之最近有效到期日（財報情境須嚴格覆蓋公告時刻）。")
    private LocalDate straddleExpiration;

    @Column(name = "atm_strike", precision = 20, scale = 4)
    @Comment("ATM 跨式期權所選之最接近現價行權價。")
    private BigDecimal atmStrike;

    @Column(name = "atm_call_price", precision = 20, scale = 6)
    @Comment("ATM Call 參考中價或收盤價（每股權利金）。")
    private BigDecimal atmCallPrice;

    @Column(name = "atm_put_price", precision = 20, scale = 6)
    @Comment("ATM Put 參考中價或收盤價（每股權利金）。")
    private BigDecimal atmPutPrice;

    @Column(name = "implied_move_pct", precision = 12, scale = 6)
    @Comment("至選定到期日之 ATM 跨式隱含漲跌幅度（小數比例，如 0.0850 代表 ±8.50%，計法為 (callMid + putMid) / spot）；資料不足或雙邊無有效報價時為 NULL。")
    private BigDecimal impliedMovePct;

    @Column(name = "implied_move_status", nullable = false, length = 24)
    @Comment("跨式隱含波動可用狀態：AVAILABLE、NOT_APPLICABLE 或 INSUFFICIENT_DATA。")
    private String impliedMoveStatus;

    @Column(name = "implied_move_reason", length = 64)
    @Comment("跨式隱含波動不可用或補充說明代碼（如 EARNINGS_STRADDLE、NEAREST_EXPIRY_REFERENCE、NOT_APPLICABLE_FOR_ETF）。")
    private String impliedMoveReason;

    @Column(name = "atm_iv", precision = 12, scale = 6)
    @Comment("ATM 跨式雙腿合成隱含波動率（年化小數，如 0.3500 代表 35%）；缺腿時為 NULL。")
    private BigDecimal atmIv;

    @Column(name = "hv20", precision = 12, scale = 6)
    @Comment("以近 21 個拆股對齊收盤價計算之 20 日年化歷史波動率（小數）；行情不足時為 NULL。")
    private BigDecimal hv20;

    @Column(name = "iv_to_hv_ratio", precision = 12, scale = 4)
    @Comment("ATM IV 與 HV20 之比值（>1.2 代表期權定價高於近期實現波動）；任一分母缺失或為 0 時為 NULL。")
    private BigDecimal ivToHvRatio;

    @Column(name = "put_call_oi_ratio", precision = 12, scale = 4)
    @Comment("選定主力到期日全行權價之 Put 未平倉量除以 Call 未平倉量；分母為 0 或資料不完整時為 NULL。")
    private BigDecimal putCallOiRatio;

    @Column(name = "put_call_vol_ratio", precision = 12, scale = 4)
    @Comment("選定主力到期日之 Put 當日成交量除以 Call 當日成交量；分母為 0 或成交量不足時為 NULL。")
    private BigDecimal putCallVolRatio;

    @Column(name = "otm_put_iv", precision = 12, scale = 6)
    @Comment("同到期等距虛值（優先約 -25Δ 或約 5%–10% 虛值）Put 隱含波動率（小數）；缺合約時為 NULL。")
    private BigDecimal otmPutIv;

    @Column(name = "otm_call_iv", precision = 12, scale = 6)
    @Comment("同到期等距虛值（優先約 +25Δ 或約 5%–10% 虛值）Call 隱含波動率（小數）；缺合約時為 NULL。")
    private BigDecimal otmCallIv;

    @Column(name = "iv_skew_put_minus_call", precision = 12, scale = 6)
    @Comment("等距虛值 Put IV 減去 Call IV 之偏度（正值顯著擴大代表下行避險溢價升溫）；缺任一腿為 NULL。")
    private BigDecimal ivSkewPutMinusCall;

    @Column(name = "oi_wall_expiration")
    @Comment("計算最大 Call／Put 未平倉量行權價牆所統計之主力近月到期日。")
    private LocalDate oiWallExpiration;

    @Column(name = "max_call_oi_strike", precision = 20, scale = 4)
    @Comment("主力到期日最大 Call 未平倉量行權價（僅作短期籌碼密集帶參考，非看漲方向訊號）。")
    private BigDecimal maxCallOiStrike;

    @Column(name = "max_call_oi")
    @Comment("主力到期日最大 Call 行權價對應之未平倉合約口數。")
    private Long maxCallOi;

    @Column(name = "max_put_oi_strike", precision = 20, scale = 4)
    @Comment("主力到期日最大 Put 未平倉量行權價（僅作短期籌碼密集帶參考，非看跌方向訊號）。")
    private BigDecimal maxPutOiStrike;

    @Column(name = "max_put_oi")
    @Comment("主力到期日最大 Put 行權價對應之未平倉合約口數。")
    private Long maxPutOi;

    @Column(name = "total_call_oi")
    @Comment("主力到期日統計範圍內 Call 總未平倉合約口數。")
    private Long totalCallOi;

    @Column(name = "total_put_oi")
    @Comment("主力到期日統計範圍內 Put 總未平倉合約口數。")
    private Long totalPutOi;

    @Column(name = "open_interest_date")
    @Comment("資料商回報未平倉量（Open Interest）所對應之官方結算日。")
    private LocalDate openInterestDate;

    @Column(name = "volume_session_date")
    @Comment("期權成交量統計所屬之美東交易日。")
    private LocalDate volumeSessionDate;

    @Column(name = "status", nullable = false, length = 24)
    @Comment("整體期權摘要可用狀態：AVAILABLE、NOT_APPLICABLE 或 INSUFFICIENT_DATA。")
    private String status;

    @Column(name = "reason_code", length = 64)
    @Comment("不可用或部分缺項之結構化原因碼。")
    private String reasonCode;

    @Column(name = "observed_at", nullable = false)
    @Comment("本次期權摘要寫入資料庫之 UTC 時間。")
    private Instant observedAt;
}
