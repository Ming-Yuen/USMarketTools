package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

/** CNN 分數與實際 VIX 收市值只存一次，兩者保留不同來源及時間語意。 */
@Entity
@Data
@Table(name = "market_sentiment_daily")
public class MarketSentimentDaily {
    @Id
    @Column(nullable = false)
    @Comment("市場情緒所屬的美國市場日期。")
    private LocalDate marketDate;
    @Column(length = 64)
    @Comment("CNN Fear & Greed 指數資料來源標識。")
    private String cnnSource;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "fear_and_greed_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "fear_and_greed_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "fear_and_greed_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric fearAndGreed;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "market_momentum_sp500_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "market_momentum_sp500_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "market_momentum_sp500_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric marketMomentumSp500;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "stock_price_strength_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "stock_price_strength_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "stock_price_strength_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric stockPriceStrength;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "stock_price_breadth_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "stock_price_breadth_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "stock_price_breadth_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric stockPriceBreadth;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "put_call_options_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "put_call_options_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "put_call_options_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric putCallOptions;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "market_volatility_vix_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "market_volatility_vix_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "market_volatility_vix_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric marketVolatilityVix;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "junk_bond_demand_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "junk_bond_demand_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "junk_bond_demand_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric junkBondDemand;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "score", column = @Column(name = "safe_haven_demand_score", columnDefinition = "numeric")),
        @AttributeOverride(name = "capturedAt", column = @Column(name = "safe_haven_demand_source_at", columnDefinition = "timestamp")),
        @AttributeOverride(name = "observedAt", column = @Column(name = "safe_haven_demand_observed_at", columnDefinition = "timestamp"))
    })
    private SentimentMetric safeHavenDemand;

    @Column(columnDefinition = "numeric")
    @Comment("該市場日期實際 VIX 收市指數值，不是 CNN 的恐慌分項分數。")
    private BigDecimal vixClose;
    @Column(length = 64)
    @Comment("實際 VIX 收盤數值的外部來源。")
    private String vixSource;
    @Column(columnDefinition = "timestamp")
    @Comment("實際 VIX 收盤值來源的發布／更新時間，未知為 NULL。")
    private Instant vixSourceAt;
    @Column(columnDefinition = "timestamp")
    @Comment("本系統取得 VIX 收盤值的時間。")
    private Instant vixObservedAt;

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentimentMetric {
        @Comment("CNN Fear & Greed 該分項的指數分數，不是股價或 VIX 收市值。")
        private BigDecimal score;
        @Comment("CNN 來源標示該分項數值的時間；非本地同步時間。")
        private Instant capturedAt;
        @Comment("本系統取得該 CNN 分項數值的時間。")
        private Instant observedAt;
    }
}
