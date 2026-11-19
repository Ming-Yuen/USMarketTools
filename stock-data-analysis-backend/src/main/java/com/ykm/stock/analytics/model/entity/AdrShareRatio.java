package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "adr_share_ratio")
@Comment("ADR 換股比例對照表，用於將非美企業原股總股數與美股 ADR 股數及總市值對齊；未核實 ADR 攔截不估值。")
public class AdrShareRatio {
    @Id
    @Column(name = "symbol", nullable = false, length = 16)
    @Comment("美股 ADR 交易代碼（大寫主鍵，如 TSM、BABA、NVO）。")
    private String symbol;

    @Column(name = "underlying_symbol", length = 16)
    @Comment("原上市地普通股代碼（如 2330.TW、9988.HK）。")
    private String underlyingSymbol;

    @Column(name = "adr_ratio", nullable = false, precision = 10, scale = 4)
    @Comment("1 單位美股 ADR 對應之原股普通股數量（如 TSM 為 5.0000、BABA 為 8.0000、SNY 為 0.5000）。")
    private BigDecimal adrRatio;

    @Column(name = "reporting_currency", length = 3)
    @Comment("參考財報申報幣別（ISO-4217 三碼）；實際估值仍以當期原始財報幣別為準。")
    private String reportingCurrency;

    @Column(name = "is_verified", nullable = false)
    @Comment("換股比例是否已獲發行人申報或人工確認；FALSE 或查無紀錄時攔截並標註 ADR_RATIO_UNVERIFIED。")
    private boolean verified;

    @Column(name = "note", length = 256)
    @Comment("比例核實來源或人工備註（如 20-F 存託協議比例）。")
    private String note;

    @Column(name = "updated_at", nullable = false)
    @Comment("此筆 ADR 換股比例最近更新時間（UTC）。")
    private Instant updatedAt;
}
