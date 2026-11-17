package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.FinancialReportId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@Getter
@Setter
@Table(name = "financial_report")
@IdClass(FinancialReportId.class)
public class FinancialReport {
    @Id
    @Column(length = 32, nullable = false)
    @Comment("富途財報所屬股票 FIGI。")
    private String figi;
    @Id
    @Comment("富途報表類型，1 為利潤表、2 為資產負債表、3 為現金流量表、4 為關鍵指標。")
    private int statementType;
    @Id
    @Comment("財報業務期末日；富途香港零時編碼取原日期，不轉成美東前一天。")
    private LocalDate periodEnd;
    @Id
    @Comment("富途財報期間類型，1 至 4 為單季，7 為年報。同日的第四季和年報各自保存。")
    private int periodType;
    @Comment("富途提供的財務年度，未知為 NULL。")
    private Integer fiscalYear;
    @Column(columnDefinition = "text")
    @Comment("富途提供的報期標籤。")
    private String period;
    @Column(length = 3)
    @Comment("來源原幣代碼，富途未提供時為 NULL。")
    private String currency;
    @Column(columnDefinition = "text")
    @Comment("富途提供的會計準則，未知為 NULL。")
    private String accountingStandards;
    @Column(columnDefinition = "text")
    @Comment("富途提供的審計意見，未知為 NULL。")
    private String auditorReport;
    @Column(nullable = false, columnDefinition = "timestamp")
    @Comment("本系統首次取得同報期報表的時間；用於歷史報期可用性，非公告時間。")
    private Instant observedAt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Comment("本報表原生欄位清單，含欄位 ID、名稱、原幣值及同比和環比百分點。缺值保存 NULL。")
    private List<Item> items;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figi", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_financial_report_stock"))
    private Stock stock;

    public record Item(long fieldId, String name, BigDecimal value, BigDecimal yearOverYearPercent, BigDecimal quarterOverQuarterPercent) {}
}
