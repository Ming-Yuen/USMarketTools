package com.ykm.stock.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "portfolio_item")
@Comment("使用者個人持倉與重點自選股清單（單組合模型，以 FIGI 為主鍵區分 HOLDING 或 WATCHLIST）。")
public class PortfolioItem {
    public static final String TYPE_HOLDING = "HOLDING";
    public static final String TYPE_WATCHLIST = "WATCHLIST";

    @Id
    @Column(name = "figi", nullable = false, length = 32)
    @Comment("股票 FIGI 識別碼，每檔股票在個人清單僅保留一筆。")
    private String figi;

    @Column(name = "item_type", nullable = false, length = 16)
    @Comment("項目類型：HOLDING（實質持倉）或 WATCHLIST（重點自選）。")
    private String itemType;

    @Column(name = "shares", precision = 20, scale = 6)
    @Comment("輸入基準日口徑之持倉股數（支援小數股數）；HOLDING 必填且須大於 0，WATCHLIST 固定為 NULL。")
    private BigDecimal shares;

    @Column(name = "avg_cost", precision = 20, scale = 6)
    @Comment("輸入基準日口徑之平均買入成本（原股價交易幣別）；HOLDING 必填且須大於 0，WATCHLIST 固定為 NULL。")
    private BigDecimal avgCost;

    @Column(name = "cost_basis_date")
    @Comment("記錄 shares 與 avg_cost 所屬之美東已收市交易日（拆股對齊基準日）；HOLDING 必填，WATCHLIST 固定為 NULL。")
    private LocalDate costBasisDate;

    @Column(name = "position_id", length = 64)
    @Comment("單次持倉生命週期識別碼（UUID）；新建 HOLDING 或由 WATCHLIST 轉為 HOLDING 時產生，持續持有更新保留，轉為 WATCHLIST 時清空。")
    private String positionId;

    @Column(name = "notes", length = 512)
    @Comment("使用者自訂觀察或交易備註說明；可為 NULL。")
    private String notes;

    @Column(name = "created_at", nullable = false)
    @Comment("首次建立此持倉或自選紀錄之 UTC 時間。")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Comment("最近一次更新此持倉或自選紀錄之 UTC 時間。")
    private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "figi", insertable = false, updatable = false, foreignKey = @ForeignKey(name = "fk_portfolio_item_stock"))
    private Stock stock;
}
