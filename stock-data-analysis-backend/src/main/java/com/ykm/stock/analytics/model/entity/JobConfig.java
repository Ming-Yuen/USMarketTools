package com.ykm.stock.analytics.model.entity;


import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import jakarta.persistence.*;
import lombok.Data;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Data

@Entity
@Table(name = "job_config", uniqueConstraints = @UniqueConstraint(name = "UK_JOB_CONFIG_TASK_CODE", columnNames = "task_code"))
@Comment("排程／批次工作設定，定義穩定任務代碼、實作類別、觸發方式、Cron、重試與啟用狀態。")
// 用途：表示「排程任務Config」資料的持久化實體，保存相關業務狀態與可追溯欄位。
public class JobConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "JOB_CONFIG_ID_SEQ_GEN")
    @SequenceGenerator(name = "JOB_CONFIG_ID_SEQ_GEN", sequenceName = "JOB_CONFIG_ID_SEQ", allocationSize = 1)
    @Column(name = "job_id", nullable = false, updatable = false)
    @Comment("工作設定代理主鍵；不承載業務語意。")
    // 用途：資料列的技術主鍵，用於唯一識別此實體記錄。
    private Long id;

    @Column(name = "task_code", nullable = false, length = 100)
    @Comment("穩定任務代碼，例如 STOCK_VALUATION_DAILY；不應隨 Java 類別改名而變更。")
    // 用途：儲存「任務Code」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private String taskCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_group", nullable = false, length = 50)
    @Comment("任務群組：TEMP（臨時）、SCHEDULE（排程）、BATCH（批次）、REPORT（報表）。")
    // 用途：儲存「任務Group」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private TaskGroup taskGroup;

    @Column(name = "job_class_name", nullable = false, length = 255)
    @Comment("實際執行 Job 的完整 Java 類別名稱。")
    // 用途：儲存「排程任務ClassName」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private String jobClassName;

    @Column(name = "cron_expression", length = 100)
    @Comment("Quartz Cron 表達式，統一以香港時間 Asia/Hong_Kong 執行；triggerType 為 CRON 時使用。")
    // 用途：儲存「cronExpression」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private String cronExpression;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    @Comment("觸發類型：CRON、SIMPLE、CALENDAR。")
    // 用途：儲存「triggerType」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private TriggerType triggerType = TriggerType.CRON;

    @Column(length = 3000)
    @JdbcTypeCode(SqlTypes.JSON)
    @Comment("任務額外 JSON 設定；各 key/value 的語意由對應 Job 解讀。")
    // 用途：儲存「config」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private Map<String, Object> config;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Comment("啟用狀態：ACTIVE、INACTIVE。")
    // 用途：股票或設定的啟用狀態，用於判斷是否納入目前處理範圍。
    private ActiveStatus activeStatus;

    @Column(name = "start_time")
    @Comment("任務設定的生效／開始時間，統一按香港時間 Asia/Hong_Kong 解讀。")
    // 用途：儲存「startTime」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private LocalDateTime startTime;

    @Column(name = "end_time")
    @Comment("任務設定的結束時間，統一按香港時間 Asia/Hong_Kong 解讀；未設定時可為空。")
    // 用途：儲存「endTime」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private LocalDateTime endTime;

    @Column(name = "retry_count", nullable = false)
    @Comment("目前已重試次數。")
    // 用途：儲存「retryCount」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private Integer retryCount = 0;

    @Column(name = "max_retry_count", nullable = false)
    @Comment("允許的最大重試次數。")
    // 用途：儲存「maxRetryCount」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private Integer maxRetryCount = 3;

    // 用途：表示「TriggerType」資料的持久化實體，保存相關業務狀態與可追溯欄位。
    public enum TriggerType { CRON, SIMPLE, CALENDAR }
}
