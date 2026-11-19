package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

/** 外部預期值與已公布財報分開；缺少確切財政期間時不建立可用預期。 */
@Entity
@Getter
@Setter
@Table(name = "stock_earnings_forecast")
@IdClass(StockEarningsForecast.Key.class)
public class StockEarningsForecast {
    @Id
    @Column(length = 32, nullable = false)
    @Comment("外部財報預期所屬股票 FIGI。")
    private String figi;
    @Id
    @Column(length = 64, nullable = false)
    @Comment("外部來源提供的財政期間識別碼，不能由日曆季度猜測。")
    private String fiscalPeriod;
    @Id
    @Column(length = 16, nullable = false)
    @Comment("預期涵蓋的財務期間類型，例如季度或年度。")
    private String periodType;
    @Id
    @Column(nullable = false)
    @Comment("市場預期值被觀察到的日期，不是財報結束日期。")
    private LocalDate observationDate;
    @Comment("來源明確提供的預測期間開始日期，未知為 NULL。")
    private LocalDate periodStart;
    @Comment("來源明確提供的預測期間截止日期，未知為 NULL。")
    private LocalDate periodEnd;
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("本系統實際取得這份市場預期的時刻。")
    private Instant observedAt;
    @Column(length = 3, nullable = false)
    @Comment("預測營收或 EPS 使用的報告貨幣。")
    private String currency;
    @Column(columnDefinition = "numeric")
    @Comment("來源報告的每股盈餘預期值，不得混作已公布實績。")
    private BigDecimal eps;
    @Column(columnDefinition = "numeric")
    @Comment("來源直接提供的 30 日前同財期 EPS 共識預期值（已與當日同拆股基準），未知為 NULL。")
    private BigDecimal eps30dAgo;
    @Column(columnDefinition = "numeric")
    @Comment("來源直接提供的 90 日前同財期 EPS 共識預期值（已與當日同拆股基準），未知為 NULL。")
    private BigDecimal eps90dAgo;
    @Column(columnDefinition = "numeric")
    @Comment("來源報告的營收預期值，不得混作已公布實績。")
    private BigDecimal revenue;
    @Id
    @Column(length = 32, nullable = false)
    @Comment("本份預測的外部資料供應商。")
    private String source;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String figi;
        private String fiscalPeriod;
        private String periodType;
        private LocalDate observationDate;
        private String source;
    }
}
