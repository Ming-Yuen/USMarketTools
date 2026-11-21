package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 美國高收益債期權調整信用利差（FRED BAMLH0A0HYM2）原始日頻觀測；衍生均線與擴寬幅度於讀取時即時計算。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "macro_credit_spread_daily")
@Comment("聯準會 FRED 高收益債信用利差（ICE BofA US High Yield Index OAS，BAMLH0A0HYM2）日頻原始觀測表。")
public class MacroCreditSpreadDaily {
    public static final String SERIES_HY_OAS = "BAMLH0A0HYM2";

    @Id
    @Column(name = "observation_date", nullable = false, updatable = false)
    @Comment("FRED 公布的市場觀測日期（月底可能包含非股票交易日）；絕不將舊值複製為無新數據日。")
    private LocalDate observationDate;

    @Column(name = "series_id", nullable = false, length = 32)
    @Comment("FRED 官方序列代碼，預設為 BAMLH0A0HYM2。")
    private String seriesId;

    @Column(name = "oas_pct", nullable = false, precision = 8, scale = 4)
    @Comment("高收益債期權調整利差原值，單位為百分點（例如 3.4500 代表 3.45% 即 345 bps）。")
    private BigDecimal oasPct;

    @Column(name = "source_realtime_start")
    @Comment("FRED 回應標示的 realtime_start 日期，用於追溯來源修訂與發佈時間窗口。")
    private LocalDate sourceRealtimeStart;

    @Column(name = "observed_at")
    @Comment("本系統首次下載或確認近期觀測值變動的 UTC 時間；歷史回補舊日期為 NULL（代表官方日終序列，未知精確盤後抓取秒數，允許歷史查詢讀取）。")
    private Instant observedAt;
}
