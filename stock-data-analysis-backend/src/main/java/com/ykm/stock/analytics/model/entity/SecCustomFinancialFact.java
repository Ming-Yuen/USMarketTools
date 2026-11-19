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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 僅保存「已獲人工確認映射」之公司自訂／非預設標準 XBRL 原始無維度合併報表事實，與標準 sec_financial_fact 嚴格實體隔離。 */
@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "sec_custom_financial_fact",
        uniqueConstraints = @UniqueConstraint(name = "uk_sec_custom_fact_disclosure_key",
                columnNames = {"disclosure_key"}),
        indexes = {
                @Index(name = "idx_sec_custom_fact_cik_end", columnList = "cik,period_end")
        })
@Comment("已確認 SEC 自訂標籤映射之確定性抽取事實表，與標準 sec_financial_fact 實體隔離；僅於估值重建時補足缺漏分母，不覆寫標準事實。")
public class SecCustomFinancialFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @Comment("自訂事實流水號主鍵。")
    private Long id;

    @Column(name = "disclosure_key", nullable = false, length = 64)
    @Comment("披露唯一鍵 SHA-256（含可空 period_start 之 INSTANT 標記），確保存量與流量項目在 PostgreSQL 併發下皆嚴格唯一。")
    private String disclosureKey;

    @Column(name = "cik", nullable = false, length = 10)
    @Comment("SEC 發行人 10 碼 CIK。")
    private String cik;

    @Column(name = "taxonomy", nullable = false, length = 64)
    @Comment("原始 XBRL taxonomy／namespace 名稱。")
    private String taxonomy;

    @Column(name = "tag", nullable = false, length = 160)
    @Comment("原始 XBRL 標籤名稱。")
    private String tag;

    @Column(name = "unit", nullable = false, length = 64)
    @Comment("原始 XBRL 單位（例如 USD、EUR）。")
    private String unit;

    @Column(name = "period_start")
    @Comment("流量期間起始日；資產負債表存量項目為 NULL。")
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    @Comment("財務期間結束日。")
    private LocalDate periodEnd;

    @Column(name = "accession", nullable = false, length = 24)
    @Comment("SEC 原始申報編號。")
    private String accession;

    @Column(name = "value", nullable = false, columnDefinition = "numeric")
    @Comment("SEC 原始披露數值（未套用符號倍數，重建時依已確認映射規則之 sign_multiplier 套用）。")
    private BigDecimal value;

    @Column(name = "filed", nullable = false)
    @Comment("SEC 原始申報日期。")
    private LocalDate filed;

    @Column(name = "form", nullable = false, length = 32)
    @Comment("SEC 原始申報表單（例如 10-K、10-Q、20-F）。")
    private String form;

    @Column(name = "report_fiscal_year")
    @Comment("當期對應之財年標籤；歷史比較期為 NULL。")
    private Integer reportFiscalYear;

    @Column(name = "report_fiscal_period", length = 4)
    @Comment("當期對應之財季標籤（Q1、Q2、Q3、FY）；歷史比較期為 NULL。")
    private String reportFiscalPeriod;

    @Column(name = "available_from_date", nullable = false)
    @Comment("申報日後下一美股交易日，維持與標準事實一致之可見日期基準。")
    private LocalDate availableFromDate;

    @Column(name = "observed_at", nullable = false)
    @Comment("首次寫入此自訂事實之 UTC 時間。")
    private Instant observedAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("最後更新此自訂事實之 UTC 時間。")
    private Instant updatedAt;
}
