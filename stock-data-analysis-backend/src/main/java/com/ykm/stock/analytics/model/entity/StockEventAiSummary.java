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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 異動／8-K 財測事件摘要紀錄；以 (figi, input_fingerprint) 於外呼 AI 前原子搶佔執行權，杜絕併發或跨日重複計費。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stock_event_ai_summary",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_event_ai_fingerprint", columnNames = {"figi", "input_fingerprint"}),
        indexes = {
                @Index(name = "idx_stock_event_ai_figi_created", columnList = "figi,created_at")
        })
@Comment("個股異動原因與 8-K 官方財測結構化摘要表，支援 OPENAI_API、CODEX_CLI 與零成本 MANUAL_IMPORT 三通道；僅作輔助解釋，絕不覆寫量化估值或止損。")
public class StockEventAiSummary {
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";

    public static final String PROVIDER_OPENAI_API = "OPENAI_API";
    public static final String PROVIDER_CODEX_CLI = "CODEX_CLI";
    public static final String PROVIDER_MANUAL_IMPORT = "MANUAL_IMPORT";

    public static final String TRIGGER_PRICE_SHOCK_5PCT = "PRICE_SHOCK_5PCT";
    public static final String TRIGGER_EARNINGS_WINDOW = "EARNINGS_WINDOW";
    public static final String TRIGGER_FILING_8K = "FILING_8K";
    public static final String TRIGGER_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("事件摘要流水號主鍵。")
    private Long id;

    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票或 ETF 的 OpenFIGI。")
    private String figi;

    @Column(name = "symbol", nullable = false, length = 32)
    @Comment("摘要產生時之股票交易代碼 ticker。")
    private String symbol;

    @Column(name = "trigger_date", nullable = false)
    @Comment("觸發摘要對應之美東交易日。")
    private LocalDate triggerDate;

    @Column(name = "trigger_reason", nullable = false, length = 32)
    @Comment("觸發原因：PRICE_SHOCK_5PCT、EARNINGS_WINDOW、FILING_8K 或 MANUAL。")
    private String triggerReason;

    @Column(name = "status", nullable = false, length = 24)
    @Comment("執行狀態：IN_PROGRESS（外呼前原子鎖定）、COMPLETED、FAILED 或 TIMED_OUT（防止自動重跑重複付費）。")
    private String status;

    @Column(name = "ai_provider", nullable = false, length = 24)
    @Comment("摘要來源通道：OPENAI_API、CODEX_CLI 或 MANUAL_IMPORT。")
    private String aiProvider;

    @Column(name = "model_name", length = 64)
    @Comment("實際執行摘要之輕量模型名稱（手動匯入時為 MANUAL_WEB_AI）。")
    private String modelName;

    @Column(name = "event_nature", length = 32)
    @Comment("事件性質判定：ONE_OFF_SHOCK、FUNDAMENTAL_DETERIORATION、POSITIVE_CATALYST、NEUTRAL_NOISE 或 UNCLEAR。")
    private String eventNature;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bullet_points_json", columnDefinition = "jsonb")
    @Comment("最多 3 點繁體中文異動原因摘要清單（證據不足時允許少於 3 點，不強迫編造）。")
    private List<String> bulletPointsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guidance_json", columnDefinition = "jsonb")
    @Comment("自 8-K／6-K 官方 EX-99.1 抽取並核對原文之下一季／全年白紙黑字財測結構化資訊；無官方財測時為 NULL。")
    private Map<String, Object> guidanceJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_urls_json", columnDefinition = "jsonb")
    @Comment("本次摘要實際引用之受控來源網址清單（由後端依已入庫快取白名單核對填入）。")
    private List<String> sourceUrlsJson;

    @Column(name = "input_fingerprint", nullable = false, length = 64)
    @Comment("依排序後穩定來源 ID、實質內容與 Schema 版本計算之 SHA-256 指紋，確保相同事件組不重複呼叫 AI。")
    private String inputFingerprint;

    @Column(name = "error_message", length = 512)
    @Comment("失敗或逾時之精簡安全錯誤摘要（不洩漏密鑰）；成功時為 NULL。")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    @Comment("摘要紀錄建立之 UTC 時間。")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("摘要紀錄最後更新之 UTC 時間。")
    private Instant updatedAt;
}
