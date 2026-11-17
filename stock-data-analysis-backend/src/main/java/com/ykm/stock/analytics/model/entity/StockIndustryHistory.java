package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Comment;

import java.time.Instant;

/** 依 FIGI 保存實際觀察到的產業分類區間；不推測來源未覆蓋的歷史。 */
@Data
@Entity
@Table(name = "stock_industry_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_stock_industry_history_figi_from", columnNames = {"figi", "observed_from"}),
        indexes = @Index(name = "idx_stock_industry_history_figi_period", columnList = "figi,observed_from,observed_to"))
@Check(name = "ck_stock_industry_history_range", constraints = "observed_to is null or observed_to > observed_from")
@Comment("股票產業分類的觀察歷史；FIGI 對應單一產業，概念仍在獨立多對多表。")
public class StockIndustryHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "stock_industry_history_seq")
    @SequenceGenerator(name = "stock_industry_history_seq", sequenceName = "stock_industry_history_seq", allocationSize = 50)
    @Comment("產業分類歷史主鍵。")
    private Long id;

    @Column(name = "figi", length = 32, nullable = false, updatable = false)
    @Comment("股票 FIGI；不依 Symbol 識別股票。")
    private String figi;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "industry_code", nullable = false, foreignKey = @ForeignKey(name = "fk_stock_industry_history_industry"))
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private StockIndustry industry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figi", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_stock_industry_history_stock"))
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Stock stock;

    @Column(name = "observed_from", nullable = false, updatable = false)
    @Comment("首次確認這個分類的時間，包含此刻；不冒充更早的產業有效日期。")
    private Instant observedFrom;

    @Column(name = "observed_to")
    @Comment("此分類最後可適用的排他時間上界；NULL 表示目前分類。")
    private Instant observedTo;
}
