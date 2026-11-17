package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

/** 已確認歸屬證券的拆股／股票股利；observedAt 不是市場公告時間。 */
@Data
@Entity
@Table(name = "stock_split", indexes = @Index(name = "stock_split_figi_date_idx", columnList = "figi,execution_date"))
public class StockSplit {
    @Id
    @Column(name = "source_event_id", length = 256, nullable = false)
    @Comment("來源事件識別碼；Futu 無事件 ID，使用 FUTU:FIGI:生效日穩定識別同日股份調整。")
    private String sourceEventId;

    @Column(name = "figi", length = 32, nullable = false)
    @Comment("查詢當前股票所屬的 FIGI；歷史事件沿用 Futu 對該證券的復權序列。")
    private String figi;

    @Column(name = "execution_date", nullable = false)
    @Comment("拆股正式生效的市場日期，用於調整不同日期股價及股數基準。")
    private LocalDate executionDate;

    @Column(name = "split_from", nullable = false, columnDefinition = "numeric")
    @Comment("拆股前股份數量比例的分母，例如 1 拆 4 中的 1。")
    private BigDecimal splitFrom;

    @Column(name = "split_to", nullable = false, columnDefinition = "numeric")
    @Comment("拆股後股份數量比例的分子，例如 1 拆 4 中的 4。")
    private BigDecimal splitTo;

    @Column(name = "observed_at", nullable = false)
    @Comment("本系統確認並取得拆股事件的時間，並非事件公告時間。")
    private Instant observedAt;
}
