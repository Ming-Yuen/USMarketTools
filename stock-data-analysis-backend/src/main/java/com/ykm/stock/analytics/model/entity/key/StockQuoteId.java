package com.ykm.stock.analytics.model.entity.key;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
// 用途：表示「股票行情Id」資料的持久化實體，保存相關業務狀態與可追溯欄位。
public class StockQuoteId {

    @NotBlank
    // 用途：股票 FIGI 識別碼，用於關聯對應的證券主檔。
    private String figi;

    @NotNull
    // 用途：最近成功處理的行情日期，用於追蹤個股行情同步進度。
    private LocalDate quoteDate;
}
