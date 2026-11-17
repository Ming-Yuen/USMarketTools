package com.ykm.stock.analytics.model.projection;

import com.ykm.stock.analytics.model.entity.Stock;
import java.time.LocalDate;

/** ACTIVE 股票主檔及實際已保存的最新行情日期。 */
public record ActiveStockQuoteDate(Stock stock, LocalDate quoteDate) {}
