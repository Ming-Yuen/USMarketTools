package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** SEC Form 4 非衍生性證券交易明細；按申報原始交易序號 (accession, transaction_index) 去重，避免聯名申報重複計入金額與人數。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "sec_form4_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uk_sec_form4_accession_tx_idx", columnNames = {"accession", "transaction_index"}),
        indexes = {
                @Index(name = "idx_sec_form4_figi_tx_date", columnList = "figi,transaction_date"),
                @Index(name = "idx_sec_form4_cik_filing_date", columnList = "cik,filing_date")
        })
@Comment("SEC 官方 Form 4 內部人交易明細表，精準區分 Code P 公開市場／私下買入與一般薪酬行權／贈與／10b5-1 例行交易。")
public class SecForm4Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("Form 4 交易明細流水號主鍵。")
    private Long id;

    @Column(name = "figi", length = 32)
    @Comment("歸屬之美股 OpenFIGI（多股類發行人僅在證券名稱可明確歸屬時填入，否則依 CIK 展示）。")
    private String figi;

    @Column(name = "cik", nullable = false, length = 10)
    @Comment("SEC 發行人 10 碼 CIK。")
    private String cik;

    @Column(name = "symbol", length = 32)
    @Comment("Form 4 申報之發行人交易代碼 ticker。")
    private String symbol;

    @Column(name = "accession", nullable = false, length = 24)
    @Comment("SEC 原始申報編號（例如 0001140361-26-044773）。")
    private String accession;

    @Column(name = "transaction_index", nullable = false)
    @Comment("該份 Form 4 XML 內非衍生性交易列之 0-based 原始順序索引，用於避免聯名申報雙重計算。")
    private int transactionIndex;

    @Column(name = "form_type", nullable = false, length = 16)
    @Comment("申報表單類型，例如 4 或 4/A。")
    private String formType;

    @Column(name = "filing_date", nullable = false)
    @Comment("SEC 官方申報日期。")
    private LocalDate filingDate;

    @Column(name = "accepted_at")
    @Comment("SEC EDGAR 接受申報時間（UTC）；用以核對可見時間防止前視偏差。")
    private Instant acceptedAt;

    @Column(name = "transaction_date")
    @Comment("Form 4 XML 內載明之實際交易日期。")
    private LocalDate transactionDate;

    @Column(name = "security_title", length = 160)
    @Comment("申報交易之證券類別名稱（例如 Common Stock、Class A Common Stock、ADS）。")
    private String securityTitle;

    @Column(name = "primary_owner_cik", length = 16)
    @Comment("第一申報內部人之 SEC CIK。")
    private String primaryOwnerCik;

    @Column(name = "primary_owner_name", nullable = false, length = 160)
    @Comment("第一申報內部人姓名或實體名稱。")
    private String primaryOwnerName;

    @Column(name = "primary_owner_title", length = 160)
    @Comment("第一申報內部人職稱（例如 Chief Executive Officer、CFO）。")
    private String primaryOwnerTitle;

    @Column(name = "reporting_owner_count", nullable = false)
    @Comment("此份申報列示之聯名申報內部人總數。")
    private int reportingOwnerCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reporting_owners_json", nullable = false, columnDefinition = "jsonb")
    @Comment("該份申報全部 reportingOwner 之結構化清單（含 cik、name、title、isOfficer、isDirector、isTenPercentOwner）。")
    private List<Map<String, Object>> reportingOwnersJson;

    @Column(name = "is_officer", nullable = false)
    @Comment("申報內部人是否包含公司高管（Officer）。")
    private boolean officer;

    @Column(name = "is_director", nullable = false)
    @Comment("申報內部人是否包含董事（Director）。")
    private boolean director;

    @Column(name = "is_ten_percent_owner", nullable = false)
    @Comment("申報內部人是否包含 10% 以上大股東。")
    private boolean tenPercentOwner;

    @Column(name = "transaction_code", nullable = false, length = 8)
    @Comment("SEC Form 4 交易代碼（P=公開市場或私下買入、S=賣出、M=期權行權、A=授予、G=贈與、F=繳稅扣股等）。")
    private String transactionCode;

    @Column(name = "acquired_disposed", length = 4)
    @Comment("取得或處分代碼：A（Acquired 取得）或 D（Disposed 處分）。")
    private String acquiredDisposed;

    @Column(name = "aff_10b5_1")
    @Comment("Form 4 申報層級之 Rule 10b5-1 預設計畫勾選狀態（true/false；舊版 XML 缺欄時為 NULL，不假設為非計畫）。")
    private Boolean aff10b5One;

    @Column(name = "shares", precision = 24, scale = 4)
    @Comment("本次交易股數；缺值時為 NULL。")
    private BigDecimal shares;

    @Column(name = "price_per_share", precision = 20, scale = 6)
    @Comment("本次申報每股成交價（USD）；贈與或無成交價註記時為 NULL。")
    private BigDecimal pricePerShare;

    @Column(name = "transaction_value_usd", precision = 24, scale = 2)
    @Comment("本次交易估算美元金額（shares × price_per_share）；缺股數或價格時為 NULL，不以 0 代替。")
    private BigDecimal transactionValueUsd;

    @Column(name = "shares_owned_after", precision = 24, scale = 4)
    @Comment("本次交易後申報持有股數；缺值時為 NULL。")
    private BigDecimal sharesOwnedAfter;

    @Column(name = "filing_url", length = 512)
    @Comment("SEC EDGAR 原文申報網址。")
    private String filingUrl;

    @Column(name = "observed_at", nullable = false)
    @Comment("本系統首次解析並寫入此筆 Form 4 明細的 UTC 時間。")
    private Instant observedAt;
}
