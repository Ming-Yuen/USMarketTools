package com.ykm.stock.analytics.model.projection;

import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;

/** ACTIVE 股票及其已建立的同步狀態。 */
public record ActiveStockWithStatus(Stock stock, StockStatus status) {
}
