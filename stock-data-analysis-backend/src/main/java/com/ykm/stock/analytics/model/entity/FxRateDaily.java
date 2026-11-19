package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.FxRateDailyId;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@IdClass(FxRateDailyId.class)
@Table(name = "fx_rate_daily", indexes = {
        @Index(name = "idx_fx_rate_daily_currency_date", columnList = "currency, rate_date")
})
@Comment("官方日頻外幣對美元匯率表（FRED H.10／ECB），統一以 1 單位外幣折合美元表示，供非美元財報換算為 USD 估值。")
public class FxRateDaily {
    @Id
    @Column(name = "currency", nullable = false, length = 3)
    @Comment("ISO-4217 三碼外幣代碼（大寫，如 TWD、EUR、JPY、CNY、DKK、CAD）。")
    private String currency;

    @Id
    @Column(name = "rate_date", nullable = false)
    @Comment("官方匯率觀察日期。")
    private LocalDate rateDate;

    @Column(name = "usd_per_unit", nullable = false, precision = 18, scale = 8)
    @Comment("1 單位該外幣折合美元金額（USD per 1 unit of currency）；換算公式為 amountUsd = amountOrig * usdPerUnit。")
    private BigDecimal usdPerUnit;

    @Column(name = "source", nullable = false, length = 16)
    @Comment("官方匯率來源代碼（如 FRED、ECB）。")
    private String source;

    @Column(name = "series_id", length = 32)
    @Comment("來源序列代碼（如 FRED H.10 的 DEXTAUS、DEXUSEU）。")
    private String seriesId;

    @Column(name = "observed_at", nullable = false)
    @Comment("本機同步取得並寫入此筆匯率的時間（UTC）。")
    private Instant observedAt;
}
