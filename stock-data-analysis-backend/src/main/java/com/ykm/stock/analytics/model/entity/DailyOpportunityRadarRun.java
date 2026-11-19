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

import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "daily_opportunity_radar_run")
@Comment("每日機會雷達發布紀錄表（每交易日一筆）：明確區分當日「已完成發布（含零入選日）」與「尚未發布」，避免逆風零入選日回退顯示前一日名單。")
public class DailyOpportunityRadarRun {
    @Id
    @Column(name = "radar_date", nullable = false)
    @Comment("已完成雷達發布之美東已收市交易日。")
    private LocalDate radarDate;

    @Column(name = "market_regime", length = 32)
    @Comment("發布當日評估之大盤市況（RISK_ON、NEUTRAL、RECOVERY、CORRECTION、BEAR、PANIC）。")
    private String marketRegime;

    @Column(name = "headwind_active", nullable = false)
    @Comment("發布當日是否處於 BEAR／PANIC 大盤逆風警戒狀態。")
    private boolean headwindActive;

    @Column(name = "candidate_count", nullable = false)
    @Comment("第一階段 SQL 初篩進入第二階段精算之候選檔數。")
    private int candidateCount;

    @Column(name = "value_count", nullable = false)
    @Comment("當日發布之低估支撐低接清單檔數（可為 0）。")
    private int valueCount;

    @Column(name = "starter_count", nullable = false)
    @Comment("當日發布之強勢成長股趨勢回踩試單清單檔數（可為 0）。")
    private int starterCount;

    @Column(name = "observation_cutoff", nullable = false)
    @Comment("本次雷達掃描固定採用之資料觀察截點（UTC）。")
    private Instant observationCutoff;

    @Column(name = "published_at", nullable = false)
    @Comment("本次雷達快照完成發布寫入時間（UTC）。")
    private Instant publishedAt;
}
