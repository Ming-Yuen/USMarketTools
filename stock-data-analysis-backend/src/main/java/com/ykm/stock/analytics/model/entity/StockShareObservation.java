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
    @Comment("富途 issuedShares 已發行總股數（多股類為該證券單位等值總股數）。")
    private long issuedShares;
    @Comment("富途 outstandingShares 扣除庫藏股後的流通在外股數；舊快照未記錄時為 NULL。")
    private Long outstandingShares;
    @Column(precision = 19, scale = 8)
    @Comment("富途同份快照的當前價，以交易幣別計；最新收市估算須與該日已存收盤價核對。")
    private BigDecimal snapshotPrice;
    @Column(columnDefinition = "numeric")
    @Comment("富途 issuedMarketVal 已發行總市值；舊快照或無 outstandingMarketVal 時作為市值備援。")
    private BigDecimal snapshotMarketCap;
    @Column(columnDefinition = "numeric")
    @Comment("富途 outstandingMarketVal 扣除庫藏股後的流通總市值（多股類為等值總市值）；舊快照未記錄時為 NULL。")
    private BigDecimal outstandingMarketCap;
    @Column(columnDefinition = "numeric")
    @Comment("富途同份快照原生滾動市盈率 peTTMRate；未提供時為 NULL。")
    private BigDecimal peTtmRate;
    @Column(columnDefinition = "numeric")
    @Comment("富途同份快照原生市帳率 pbRate；未提供時為 NULL。")
    private BigDecimal pbRate;
    @Column(columnDefinition = "timestamp")
    @Comment("富途基本行情更新時間；不是股數生效時間，舊快照未知為 NULL。")
    private Instant snapshotQuoteTime;
    @Column(nullable = false, length = 16)
    @Comment("股數來源，目前為 FUTU；與財報稀釋加權平均股數不同。")
    private String source;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figi", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_share_observation_stock"))
    private Stock stock;

    public long effectiveShares() {
        return outstandingShares != null && outstandingShares > 0 ? outstandingShares : issuedShares;
    }

    public BigDecimal effectiveMarketCap() {
        return outstandingMarketCap != null && outstandingMarketCap.signum() > 0 ? outstandingMarketCap : snapshotMarketCap;
    }
}
