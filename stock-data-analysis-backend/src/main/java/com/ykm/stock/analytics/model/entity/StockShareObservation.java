package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.StockShareObservationId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Comment;
import java.time.Instant;
import java.math.BigDecimal;

@Entity
@Getter
@Setter
@IdClass(StockShareObservationId.class)
@Table(name = "stock_share_observation")
@Check(constraints = "issued_shares > 0")
public class StockShareObservation {
    @Id
    @Column(length = 32, nullable = false)
    @Comment("股數所屬的證券 FIGI；目前只採有效普通股的富途當前快照。")
    private String figi;
    @Id
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("本系統取得快照股數的時間；不是報表公告日、股數生效日或行情更新時間，不得倒填歷史。")
    private Instant observedAt;
    @Column(nullable = false)
    @Comment("富途 issuedShares 公司總股數；不是流通股數，用於同股份基準的公司市值估算。")
    private long issuedShares;
    @Column(precision = 19, scale = 8)
    @Comment("富途同份快照的當前價，以交易幣別計；最新收市估算須與該日已存收盤價核對。")
    private BigDecimal snapshotPrice;
    @Column(columnDefinition = "numeric")
    @Comment("富途 issuedMarketVal 公司總市值；僅供同價格基準的最新收市估算，不是當日精確歷史市值。")
    private BigDecimal snapshotMarketCap;
    @Column(columnDefinition = "timestamp")
    @Comment("富途基本行情更新時間；不是股數生效時間，舊快照未知為 NULL。")
    private Instant snapshotQuoteTime;
    @Column(nullable = false, length = 16)
    @Comment("股數來源，目前為 FUTU；與財報稀釋加權平均股數不同。")
    private String source;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figi", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_share_observation_stock"))
    private Stock stock;
}
