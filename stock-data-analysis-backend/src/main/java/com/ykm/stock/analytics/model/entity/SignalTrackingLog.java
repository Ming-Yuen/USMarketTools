package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(name = "signal_tracking_log",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_signal_tracking_figi_type_date", columnNames = {"figi", "signal_type", "signal_date"})
        },
        indexes = {
                @Index(name = "idx_signal_tracking_outcome_date", columnList = "outcome_status,signal_date"),
                @Index(name = "idx_signal_tracking_type_regime", columnList = "signal_type,market_regime")
        })
@Comment("歷史雷達訊號不可變帳本與事後到期結算表：紀錄 ACCUMULATE_AT_SUPPORT 與 TREND_PULLBACK_STARTER 原始入場價位，後續依拆股對齊日線條件式回填 +5D/+20D/+60D 報酬、MFE/MAE 與止盈止損結果，絕不篡改已寫入之歷史紀錄。")
public class SignalTrackingLog {
    public static final String SIGNAL_ACCUMULATE_AT_SUPPORT = "ACCUMULATE_AT_SUPPORT";
    public static final String SIGNAL_TREND_PULLBACK_STARTER = "TREND_PULLBACK_STARTER";

    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_TARGET_HIT_FIRST = "TARGET_HIT_FIRST";
    public static final String OUTCOME_STOP_HIT_FIRST = "STOP_HIT_FIRST";
    public static final String OUTCOME_AMBIGUOUS_SAME_BAR = "AMBIGUOUS_SAME_BAR";
    public static final String OUTCOME_EXPIRED_NEITHER = "EXPIRED_NEITHER";
    public static final String OUTCOME_STOP_ONLY_NO_TARGET = "STOP_ONLY_NO_TARGET";
    public static final String OUTCOME_DATA_GAP = "DATA_GAP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    @Comment("代理主鍵。")
    private Long id;

    @Column(name = "figi", nullable = false, length = 32, updatable = false)
    @Comment("股票 FIGI 識別碼。")
    private String figi;

    @Column(name = "symbol", nullable = false, length = 32, updatable = false)
    @Comment("訊號發出當日之股票交易代碼 ticker。")
    private String symbol;

    @Column(name = "signal_date", nullable = false, updatable = false)
    @Comment("訊號觸發之美東收市交易日。")
    private LocalDate signalDate;

    @Column(name = "signal_type", nullable = false, length = 32, updatable = false)
    @Comment("訊號類型：ACCUMULATE_AT_SUPPORT 或 TREND_PULLBACK_STARTER。")
    private String signalType;

    @Column(name = "signal_close", nullable = false, precision = 20, scale = 6, updatable = false)
    @Comment("訊號當日未復權收盤價（事後結算一律經 StockSplitCalculator 將後續行情換算回此訊號日價格基準）。")
    private BigDecimal signalClose;

    @Column(name = "protective_stop", precision = 20, scale = 4, updatable = false)
    @Comment("訊號當日凍結之保護止損價（訊號日價格基準）；無可用止損時為 NULL。")
    private BigDecimal protectiveStop;

    @Column(name = "first_target_price", precision = 20, scale = 4, updatable = false)
    @Comment("訊號當日凍結之第一阻力目標價（訊號日價格基準）；強勢回踩試單或無固定阻力目標時為 NULL。")
    private BigDecimal firstTargetPrice;

    @Column(name = "plan_status", length = 32, updatable = false)
    @Comment("訊號當日匹配交易計畫狀態；無匹配計畫時為 NULL。")
    private String planStatus;

    @Column(name = "industry_code", length = 96, updatable = false)
    @Comment("訊號當日所屬產業代碼。")
    private String industryCode;

    @Column(name = "industry_name", length = 128, updatable = false)
    @Comment("訊號當日所屬產業名稱。")
    private String industryName;

    @Column(name = "market_cap_tier", length = 16, updatable = false)
    @Comment("訊號當日市值量級（MEGA_LARGE、MID、SMALL）；無有效 USD 市值時為 NULL。")
    private String marketCapTier;

    @Column(name = "market_regime", length = 32, updatable = false)
    @Comment("訊號當日大盤市況（RISK_ON、NEUTRAL、RECOVERY 等）。")
    private String marketRegime;

    @Column(name = "return_5d", precision = 12, scale = 6)
    @Comment("訊號後第 5 個美股交易日經拆股對齊之累積報酬率（小數比例，不含股息與成本）；未到期或缺行情／拆股覆蓋時為 NULL。")
    private BigDecimal return5d;

    @Column(name = "settled_5d_date")
    @Comment("+5 個交易日到期結算之實際美股交易日。")
    private LocalDate settled5dDate;

    @Column(name = "return_20d", precision = 12, scale = 6)
    @Comment("訊號後第 20 個美股交易日經拆股對齊之累積報酬率（小數比例）；未到期或缺資料時為 NULL。")
    private BigDecimal return20d;

    @Column(name = "settled_20d_date")
    @Comment("+20 個交易日到期結算之實際美股交易日。")
    private LocalDate settled20dDate;

    @Column(name = "return_60d", precision = 12, scale = 6)
    @Comment("訊號後第 60 個美股交易日經拆股對齊之累積報酬率（小數比例）；未到期或缺資料時為 NULL。")
    private BigDecimal return60d;

    @Column(name = "settled_60d_date")
    @Comment("+60 個交易日到期結算之實際美股交易日。")
    private LocalDate settled60dDate;

    @Column(name = "mfe_60d", precision = 12, scale = 6)
    @Comment("完整 60 個美股交易日無缺口路徑內經拆股對齊最高價相對 signal_close 之最大順向漲幅（下限 0）；未滿 60 日或路徑缺口時為 NULL，避免短期暫算值污染 60D 統計。")
    private BigDecimal mfe60d;

    @Column(name = "mae_60d", precision = 12, scale = 6)
    @Comment("完整 60 個美股交易日無缺口路徑內經拆股對齊最低價相對 signal_close 之最大逆向回撤（上限 0）；未滿 60 日或路徑缺口時為 NULL。")
    private BigDecimal mae60d;

    @Column(name = "settled_path_date")
    @Comment("完成完整 60 個交易日無缺口 MFE／MAE 路徑結算之到期交易日；尚未滿期或仍有行情缺口時為 NULL。")
    private LocalDate settledPathDate;

    @Column(name = "outcome_status", nullable = false, length = 32)
    @Comment("目標／止損首次觸發結算狀態：PENDING、TARGET_HIT_FIRST、STOP_HIT_FIRST、AMBIGUOUS_SAME_BAR、EXPIRED_NEITHER、STOP_ONLY_NO_TARGET 或 DATA_GAP。")
    private String outcomeStatus;

    @Column(name = "outcome_date")
    @Comment("首次觸及目標／止損或滿 60 個交易日終結之交易日。")
    private LocalDate outcomeDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Comment("訊號初次寫入時間（UTC）。")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("最近一次結算回填時間（UTC）。")
    private Instant updatedAt;
}
