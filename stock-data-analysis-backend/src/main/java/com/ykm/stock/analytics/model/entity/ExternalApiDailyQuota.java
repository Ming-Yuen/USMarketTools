package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.ExternalApiDailyQuotaId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Comment;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Getter
@Setter
@NoArgsConstructor
@IdClass(ExternalApiDailyQuotaId.class)
@Table(name = "external_api_daily_quota")
@Comment("低配額外部資料源的每日請求額度與熔斷狀態持久化表，防止服務重啟或空結果／逾時導致配額超支。")
public class ExternalApiDailyQuota {
    @Id
    @Column(name = "provider", length = 32, nullable = false)
    @Comment("外部資料供應商代碼，例如 ALPHAVANTAGE。")
    private String provider;

    @Id
    @Column(name = "quota_date", nullable = false)
    @Comment("按紐約時區計算的配額起算日期。")
    private LocalDate quotaDate;

    @Column(name = "attempts_used", nullable = false)
    @Comment("當日已預留並發出的外部請求次數（包含空回應、逾時與被拒絕請求）。")
    private int attemptsUsed;

    @Column(name = "quota_exhausted", nullable = false)
    @Comment("當日是否已觸發供應商額度上限或 401/403/429 熔斷，為 true 時當日不再發送請求。")
    private boolean quotaExhausted;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp")
    @Comment("最後一次更新配額或熔斷狀態之 UTC 時間。")
    private Instant updatedAt;
}
