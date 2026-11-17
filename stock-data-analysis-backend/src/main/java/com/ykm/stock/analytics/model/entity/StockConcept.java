package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

@Data
@Entity
@Table(name = "stock_concept", uniqueConstraints = @UniqueConstraint(name = "uk_stock_concept_source_plate", columnNames = "source_plate_code"))
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Comment("共用股票概念字典；同一股票可參與多個概念，概念不決定產業或估值模型。")
public class StockConcept {
    @Id
    @Column(length = 32, updatable = false)
    @EqualsAndHashCode.Include
    @Comment("概念識別代碼。")
    private String code;

    @Column(nullable = false, length = 128)
    @Comment("富途概念名稱；供顯示與篩選。")
    private String name;

    @Column(name = "source_plate_code", nullable = false, length = 64)
    @Comment("富途 CONCEPT 板塊代碼；作為來源概念的唯一識別。")
    private String sourcePlateCode;
}
