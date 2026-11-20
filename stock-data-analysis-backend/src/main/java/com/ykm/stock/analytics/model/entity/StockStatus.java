package com.ykm.stock.analytics.model.entity;

import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import org.hibernate.annotations.Comment;

@Data
@Entity
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Comment("各股票資料同步任務的最近處理狀態與訊息，用於記錄進度、錯誤並避免不必要的重複處理。")
// 用途：表示「股票狀態」資料的持久化實體，保存相關業務狀態與可追溯欄位。
public class StockStatus {
    @Id
    @Column(nullable = false, length = 32)
    @Comment("股票 FIGI；每檔股票一筆任務狀態紀錄。")
    // 用途：股票 FIGI 識別碼，用於關聯對應的證券主檔。
    private String figi;

    @Column(name = "classification_synced_symbol", length = 32)
    @Comment("最近成功保存富途分類與概念所使用的 Symbol；空值表示目前代碼尚未同步。")
    private String classificationSyncedSymbol;

    @Comment("富途財報已成功入庫的最新期末日。NULL 從 2014 年回補，後續包含水位當日重新同步。")
    private LocalDate financialReportDate;

    @Column(name = "fundamental_sync_date")
    @Comment("基本面與拆股三合一排程（STOCK_FUNDAMENTAL_DAILY_SYNC）已完成檢查的香港排程日；用於同日斷點續跑與跨日補完週六全市場巡檢。")
    private LocalDate fundamentalSyncDate;

    @Comment("SEC 財務項目已成功入庫的最新報表期末日；NULL 從歷史起點回補。")
    private LocalDate secFinancialFactsDate;

    @Comment("已成功保存的最新行情交易日；允許中間缺日，空結果不推進。")
    private LocalDate quoteDate;

    @Column(name = "valuation_date")
    @Comment("最近已保存至少一項可用倍數的交易日。日常估值從此日之後接續，空值由設定起點回補。")
    private LocalDate valuationDate;

    @Column(length = 3000)
    @Comment("按交易日保存最新估值摘要，可記錄水位之後的全缺值結果；較舊重算的原因見每日估值列。")
    private String valuationMessage;

    @Comment("拆股來源已成功檢查到的日期，不代表事件發布時間。")
    private LocalDate splitDate;

    @Comment("已成功檢查拆股資料的實際最早日期；更早期間不得假定沒有拆股。")
    private LocalDate splitFromDate;

    @Column(length = 1000)
    @Comment("最近拆股同步的失敗或需要關注的訊息；成功時清空。")
    private String splitMessage;

    @Column(length = 3000)
    @Comment("最近行情同步結果或錯誤訊息。")
    // 用途：儲存「行情Message」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private String quoteMessage;

}
