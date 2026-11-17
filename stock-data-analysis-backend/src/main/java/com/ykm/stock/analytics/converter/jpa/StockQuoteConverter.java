package com.ykm.stock.analytics.converter.jpa;

import com.ykm.common.common_lib.converter.CommonTypeConverter;
import com.ykm.stock.analytics.model.dto.alpaca.AlpacaBar;
import com.ykm.stock.analytics.model.entity.StockQuote;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDate;

@Mapper(componentModel = "spring")
public interface StockQuoteConverter extends CommonTypeConverter {
    @Mapping(target = "figi", source = "figi")
    @Mapping(target = "quoteDate", source = "quoteDate")
    @Mapping(target = "openPrice", source = "bar.open")
    @Mapping(target = "highPrice", source = "bar.high")
    @Mapping(target = "lowPrice", source = "bar.low")
    @Mapping(target = "closePrice", source = "bar.close")
    @Mapping(target = "volume", source = "bar.volume")
    @Mapping(target = "observedAt", ignore = true)
    @Mapping(target = "dataSource", constant = "ALPACA")
    StockQuote convertAlpacaBarToStockQuote(AlpacaBar bar, String figi, LocalDate quoteDate);

}
