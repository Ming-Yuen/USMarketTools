package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.enums.FinancialItemMappingStatus;
import com.ykm.stock.analytics.model.entity.key.FinancialReportItemMappingId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Setter
@Table(name = "financial_report_item_mapping")
@IdClass(FinancialReportItemMappingId.class)
@Check(name = "ck_financial_item_mapping_state", constraints = "statement_type in (1,2,3,4) and length(trim(item_name)) > 0 and length(trim(review_note)) > 0 and "
        + "((mapping_status = 'MAPPED' and financial_concept is not null) or (mapping_status in ('SKIPPED','PENDING') and financial_concept is null))")
@Comment("富途財報原樣項目名稱的語意映射；不修改 financial_report.items，也不代表輸入已符合估值計算條件。")
public class FinancialReportItemMapping {
    @Id
    @Column(name = "statement_type", nullable = false, updatable = false)
    @Comment("富途報表類型：1 利潤表、2 資產負債表、3 現金流量表、4 關鍵指標；同名跨表分開審核。")
    private int statementType;

    @Id
    @Column(name = "item_name", nullable = false, updatable = false, length = 256)
    @Comment("items.name 的原樣名稱，保留大小寫及前置連字號；與報表類型組成主鍵，不按股票建立規則。")
    private String itemName;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    @Column(name = "mapping_status", nullable = false, length = 16)
    @Comment("MAPPED 已核實財務概念；SKIPPED 已確認不作本輪估值原料；PENDING 尚待確認，不能視為跳過。")
    private FinancialItemMappingStatus mappingStatus = FinancialItemMappingStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "financial_concept", length = 64)
    @Comment("已核實的來源財務概念，僅 MAPPED 可填；合計、明細、金額及百分點不可混用，非 MAPPED 為 NULL。")
    private FinancialConcept financialConcept;

    @Column(name = "review_note", nullable = false, columnDefinition = "text")
    @Comment("審核依據、口徑限制、跳過範圍或待確認事項；映射不取代期間、幣別、股份基準及公開時間檢查。")
    private String reviewNote;
}
