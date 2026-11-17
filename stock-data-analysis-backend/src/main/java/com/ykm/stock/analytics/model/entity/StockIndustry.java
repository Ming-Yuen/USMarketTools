package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;

@Data
@Entity
@Table(name = "stock_industry", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_industry_name", columnNames = "industry"),
        @UniqueConstraint(name = "uk_stock_industry_source_plate", columnNames = "source_plate_code")
})
@DynamicUpdate
@Comment("產業分類字典；估值設定在 industry_valuation_config，市場倍數由估值結果計算。")
public class StockIndustry {
    @Id
    @Column(length = 96, updatable = false)
    @Comment("穩定產業代碼，不隨名稱調整。")
    private String code;

    @Column(name = "source_plate_code", length = 64)
    @Comment("富途來源產業代碼；尚未核對的匯入分類可為空。")
    private String sourcePlateCode;

    @Column(name = "industry", nullable = false, length = 128)
    @Comment("產業名稱；概念與產業分開。")
    private String industry;
}
