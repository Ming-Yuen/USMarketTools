package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.enums.ActiveStatus;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import lombok.ToString;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JoinFormula;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Entity
@Table(name = "stock", indexes = {
        @Index(name = "idx_stock_symbol", columnList = "symbol"),
        @Index(name = "idx_stock_cik", columnList = "cik"),
        @Index(name = "idx_stock_share_class_figi", columnList = "share_class_figi"),
        @Index(name = "idx_stock_active_type", columnList = "active_status,stock_type")
})
@DynamicUpdate

@Comment("美國股票／證券主檔；Massive Reference Tickers 為主要識別資料來源。")
public class Stock {

    @Id
    @Column(length = 32, updatable = false)
    @Comment("Massive composite_figi；OpenFIGI Financial Instrument Global Identifier，股票主檔主鍵。")
    private String figi;

    @Column(nullable = false, length = 32)
    @Comment("目前最新交易代碼 ticker，只用於顯示及查詢 provider 資料；內部股票身份使用 FIGI。")
    private String symbol;

    @Column(name = "share_class_figi", length = 32)
    @Comment("Massive share_class_figi；同一股份類別的 OpenFIGI Share Class FIGI。")
    private String shareClassFigi;

    @Column(name = "mic", length = 16)
    @Comment("Massive primary_exchange；ISO 10383 主上市市場 MIC。")
    private String mic;

    @Column(name = "stock_type", length = 32)
    @Comment("Finnhub 美股證券類型原文，例如 Common Stock、ADR、ETP、Closed-End Fund、Royalty Trst；未能確認為 NULL。")
    private String stockType;

    @Column(name = "finnhub_industry", length = 128)
    @Comment("Finnhub companyProfile2 的目前產業名稱；富途產業缺失時供估值補充辨識，未知為 NULL，非歷史分類。")
    private String finnhubIndustry;

    @Column(name = "currency", length = 16)
    @Comment("Massive currency_name，正規化為大寫，例如 USD。")
    private String currency;

    @Column(length = 10)
    @Comment("SEC 官方 ticker 對照唯一解析的目前發行人 CIK；未知或歧義為 NULL，不代表歷史發行人延續。")
    private String cik;

    @Column(name = "long_name")
    @Comment("Massive reference ticker name。")
    private String longName;

    @Column(length = 32, nullable = false)
    @Enumerated(EnumType.STRING)
    @Comment("Massive active 映射後的狀態：ACTIVE、INACTIVE。")
    private ActiveStatus activeStatus;

    @Column(name = "source_updated_at")
    @Comment("Massive last_updated_utc；表示來源 reference metadata 的更新時間。")
    private Instant sourceUpdatedAt;

    @Column(name = "list_date")
    @Comment("Massive 最新 list_date，供價格序列排除上市前日期。")
    private java.time.LocalDate listDate;

    /** 只讀的當前產業投影；權威紀錄是 stock_industry_history，不在 stock 保存 industry_code。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinFormula(value = "(select h.industry_code from stock_industry_history h where h.figi = {alias}.figi and h.observed_to is null)", referencedColumnName = "code")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private StockIndustry classification;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "stock_concept_mapping",
            joinColumns = @JoinColumn(name = "figi", foreignKey = @ForeignKey(name = "fk_stock_concept_stock")),
            inverseJoinColumns = @JoinColumn(name = "concept_code", foreignKey = @ForeignKey(name = "fk_stock_concept_concept")),
            uniqueConstraints = @UniqueConstraint(name = "uk_stock_concept_mapping", columnNames = {"figi", "concept_code"}))
    @OrderBy("name ASC, code ASC")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Comment("股票參與的概念；與產業獨立，一隻股票可同時參與多個概念。")
    private Set<StockConcept> concepts = new LinkedHashSet<>();

}
