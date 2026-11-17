package com.ykm.stock.analytics.model.dto.stockInsight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 全市場分頁資料，沒有使用者持倉或自選股狀態。 */
public record StockScreenResponse(List<Item> items, long total, int page, int size) {
    public record Concept(String code, String name) {}
    public record Item(String figi, String symbol, String name, String stockType,
                       String industryCode, String industry, List<Concept> concepts,
                       LocalDate quoteDate, BigDecimal closePrice, LocalDate valuationDate,
                       BigDecimal pe, BigDecimal pb, BigDecimal ps, BigDecimal pfcf,
                       BigDecimal evEbitda, String status, String reasonCode) {
        public Item withConcepts(List<Concept> values) {
            return new Item(figi, symbol, name, stockType, industryCode, industry, values,
                    quoteDate, closePrice, valuationDate, pe, pb, ps, pfcf, evEbitda, status, reasonCode);
        }
    }
}
