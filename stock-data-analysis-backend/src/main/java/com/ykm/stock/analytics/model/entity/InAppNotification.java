package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "in_app_notification", indexes = {
        @Index(name = "idx_in_app_notif_read_created", columnList = "is_read,created_at,id"),
        @Index(name = "idx_in_app_notif_trade_severity", columnList = "trade_date,severity"),
        @Index(name = "idx_in_app_notif_category_created", columnList = "category,created_at,id")
})
@Comment("站內主動提醒中心持久化紀錄表，保存每日收盤後產出之持倉異常、自選進場、雷達精選、大盤切換與簡報通知。")
public class InAppNotification {
    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_INFO = "INFO";

    public static final String CATEGORY_PORTFOLIO_HOLDING = "PORTFOLIO_HOLDING";
    public static final String CATEGORY_WATCHLIST_ENTRY = "WATCHLIST_ENTRY";
    public static final String CATEGORY_RADAR_OPPORTUNITY = "RADAR_OPPORTUNITY";
    public static final String CATEGORY_MACRO_REGIME = "MACRO_REGIME";
    public static final String CATEGORY_CLOSING_BRIEFING = "CLOSING_BRIEFING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("站內提醒流水號主鍵。")
    private Long id;

    @Column(name = "dedup_key", nullable = false, unique = true, length = 160)
    @Comment("冪等去重鍵（例如 HOLDING:2026-11-18:BBG...:HOLDING_STOP_BREACHED），防止同日重複排程寫入重複通知。")
    private String dedupKey;

    @Column(name = "trade_date", nullable = false)
    @Comment("觸發此通知所對應之美東已收市交易日。")
    private LocalDate tradeDate;

    @Column(name = "severity", nullable = false, length = 16)
    @Comment("提醒嚴重度：CRITICAL、WARNING 或 INFO。")
    private String severity;

    @Column(name = "category", nullable = false, length = 32)
    @Comment("提醒分類：PORTFOLIO_HOLDING、WATCHLIST_ENTRY、RADAR_OPPORTUNITY、MACRO_REGIME 或 CLOSING_BRIEFING。")
    private String category;

    @Column(name = "alert_code", nullable = false, length = 64)
    @Comment("結構化提醒代碼，供前端依 zh-HK／zh-CN／en 詞典本地化渲染。")
    private String alertCode;

    @Column(name = "figi", length = 32)
    @Comment("關聯股票 FIGI；全市場或大盤通知為 NULL。")
    private String figi;

    @Column(name = "symbol", length = 32)
    @Comment("關聯股票代碼 ticker；全市場或大盤通知為 NULL。")
    private String symbol;

    @Column(name = "stock_name", length = 255)
    @Comment("關聯股票名稱快照；全市場或大盤通知為 NULL。")
    private String stockName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @Comment("結構化提醒參數（如收盤價、保護止損、倒數天數、建議股數、破位診斷代碼等），供多語系插值與稽核。")
    private Map<String, Object> payload;

    @Column(name = "is_read", nullable = false)
    @Comment("使用者是否已閱讀此提醒（預設 false）。")
    private boolean read;

    @Column(name = "read_at")
    @Comment("使用者首次標記已讀之 UTC 時間；未讀時為 NULL，重複標記已讀不覆寫。")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    @Comment("提醒寫入資料庫之 UTC 時間。")
    private Instant createdAt;
}
