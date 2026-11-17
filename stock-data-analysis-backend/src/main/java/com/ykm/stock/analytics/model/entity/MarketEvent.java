package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

/** 已知財報與宏觀日程的目前資料；不宣稱完整歷史改期重播。 */
@Entity
@Data
@EqualsAndHashCode(exclude = "observedAt")
@Table(name = "market_event")
public class MarketEvent {
    @Id
    @Column(length = 240, nullable = false)
    @Comment("事件唯一鍵，用於避免相同外部事件重複保存。")
    private String eventKey;
    @Column(length = 32)
    @Comment("股票相關事件的 FIGI；宏觀市場事件可為 NULL。")
    private String figi;
    @Column(length = 16, nullable = false)
    @Comment("事件大類，例如公司財報或宏觀經濟。")
    private String eventType;
    @Column(length = 32)
    @Comment("事件細分類別，保留來源提供的細節。")
    private String eventSubtype;
    @Column(length = 255, nullable = false)
    @Comment("事件的簡短標題，供估值分析與介面顯示。")
    private String title;
    @Column(nullable = false)
    @Comment("事件預計發生的日曆日期，不代表已知精確時刻。")
    private LocalDate eventDate;
    @Column(columnDefinition = "timestamp")
    @Comment("事件確切發生時間；來源只有日期時必須為 NULL。")
    private Instant occurrenceAt;
    @Column(length = 20, nullable = false)
    @Comment("來源所提供的時間精度，區分日期、時段與精確時間。")
    private String timePrecision;
    @Column(length = 64, nullable = false)
    @Comment("事件日曆的外部資料來源。")
    private String source;
    @Column(columnDefinition = "text")
    @Comment("外部事件資料的來源網址。")
    private String sourceUrl;
    @Column(length = 255)
    @Comment("外部供應商自己的事件識別碼。")
    private String externalId;
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("本系統實際觀察到此事件資料的時間。")
    private Instant observedAt;
    @Column(columnDefinition = "timestamp")
    @Comment("來源明確給出的事件消息發布時間；未知為 NULL。")
    private Instant sourcePublishedAt;
    @Column(length = 16, nullable = false)
    @Comment("事件目前狀態，例如已排定、已取消。")
    private String status;
}
