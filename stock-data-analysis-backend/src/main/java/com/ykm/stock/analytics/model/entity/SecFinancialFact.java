package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@Table(name = "sec_financial_fact",
        indexes = @Index(name = "idx_sec_financial_disclosed", columnList = "cik, available_from_date, period_end"),
        uniqueConstraints = @UniqueConstraint(name = SecFinancialFact.DISCLOSURE_CONSTRAINT,
                columnNames = {"cik", "taxonomy", "tag", "unit", "period_start", "period_end", "accession"}))
@Check(name = "ck_sec_financial_fact_scope", constraints = "taxonomy in ('dei','us-gaap') and length(unit) > 0 and period_start <= period_end")
@Check(constraints = "cik ~ '^[0-9]{10}$' and accession ~ '^[0-9]{10}-[0-9]{2}-[0-9]{6}$' and available_from_date > filed")
public class SecFinancialFact {
    public static final String DISCLOSURE_CONSTRAINT = "uq_sec_financial_fact_disclosure";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SEC_FINANCIAL_FACT_SEQ")
    @SequenceGenerator(name = "SEC_FINANCIAL_FACT_SEQ", sequenceName = "sec_financial_fact_id_seq", allocationSize = 200)
    @Comment("技術主鍵；披露唯一性另由 CIK、tag、單位、期間及 accession 約束。")
    private Long id;
    @Column(length = 10, nullable = false)
    @Comment("SEC 發行人 CIK；不是單一證券或股份類別識別。")
    private String cik;
    @Column(length = 16, nullable = false)
    @Comment("SEC 標準 taxonomy：dei 或 us-gaap；兩者不互相覆蓋。")
    private String taxonomy;
    @Column(length = 128, nullable = false)
    @Comment("已核實來源定義的標準財務 tag；不同 tag 不自動視為同義或相加。")
    private String tag;
    @Column(nullable = false, length = 64)
    @Comment("SEC units 原始單位，例如 shares、USD、USD/shares；不自動匯兌或縮放。")
    private String unit;
    @Comment("原始 start；即時存量項目為 NULL，不以期末日或其他日期補成流量期間。")
    private LocalDate periodStart;
    @Column(nullable = false)
    @Comment("原始 end；後續申報可修訂舊期間或重列拆股比較數，不是公開日。")
    private LocalDate periodEnd;
    @Column(length = 20, nullable = false)
    @Comment("原始 SEC accession；同期間不同申報分別保留。")
    private String accession;
    @Column(nullable = false, columnDefinition = "numeric")
    @Comment("來源原值；負數保留，來源異常不自動修正，亦不代表估值適用性已確認。")
    private BigDecimal value;
    @Column(nullable = false)
    @Comment("SEC 原始 filed 日期；沒有宣稱此日收市前已接受。")
    private LocalDate filed;
    @Column(nullable = false, length = 32)
    @Comment("SEC 原始申報表格，例如 10-K、10-Q、20-F 及修訂版。")
    private String form;
    @Comment("SEC fy 核對為原申報當期期末時的財年；歷史比較期或來源未提供為 NULL，不套用申報財年。")
    private Integer reportFiscalYear;
    @Column(length = 2)
    @Comment("SEC fp 核對為原申報當期期末時的 Q1／Q2／Q3／FY；歷史比較期為 NULL，FY 仍須區分單季與全年。")
    private String reportFiscalPeriod;
    @Column(nullable = false)
    @Comment("filed 後下一紐約交易日；股數依此檢查披露資格，財報回算另依本輪已公開且已取得資料，不證明股類基準。")
    private LocalDate availableFromDate;
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("本系統首次取得此披露的真實 UTC 時間；重跑不刷新。")
    private Instant observedAt;
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Comment("本列最近入庫或重建的香港時間；舊列尚未重建時為 NULL。")
    private LocalDateTime updatedAt;
}
