package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Comment;

/** 估值無風險利率與 10Y-2Y 曲線所需資料；利率單位為百分比。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "treasury_par_yield_daily")
public class TreasuryParYieldDaily {
    @Id
    @Column(name = "observation_date", nullable = false, updatable = false)
    @Comment("美國國債殖利率資料所屬的市場日期。")
    private LocalDate observationDate;

    @Column(name = "rate_2y", precision = 8, scale = 5)
    @Comment("美國 2 年期國債殖利率，單位為百分比而非小數比例。")
    private BigDecimal twoYear;

    @Column(name = "rate_10y", precision = 8, scale = 5)
    @Comment("美國 10 年期國債殖利率，單位為百分比而非小數比例。")
    private BigDecimal tenYear;

    @Column(name = "source_updated_at_utc")
    @Comment("外部來源標示的數據最後更新時間，按 UTC 儲存。")
    private LocalDateTime sourceUpdatedAtUtc;

    @Column(name = "observed_at")
    @Comment("本系統實際下載或確認當日國債數據的時間。")
    private Instant observedAt;
}
