package com.ykm.stock.analytics.converter.jpa;

import com.ykm.common.common_lib.converter.CommonTypeConverter;
import com.ykm.common.common_lib.converter.JsonTypeConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.entity.Stock;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.BeanMapping;
import org.mapstruct.Condition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.Locale;

@Mapper(componentModel = "spring", uses = JsonTypeConverter.class, imports = ActiveStatus.class)
public interface StockConverter extends CommonTypeConverter {
    /** 將 Massive 股票資料寫入主檔；不修改 FIGI、審計欄位或本地產業與概念關聯。 */
    @BeanMapping(ignoreByDefault = true, nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "symbol", source = "ticker", qualifiedByName = "trimText", conditionQualifiedByName = "hasText")
    @Mapping(target = "longName", source = "name", qualifiedByName = "trimText")
    @Mapping(target = "mic", source = "primaryExchange", qualifiedByName = "trimText")
    @Mapping(target = "stockType", ignore = true)
    @Mapping(target = "currency", source = "currencyName", qualifiedByName = "normalizeCurrency")
    @Mapping(target = "cik", ignore = true)
    @Mapping(target = "shareClassFigi", source = "shareClassFigi", qualifiedByName = "trimText")
    @Mapping(target = "listDate", source = "listDate")
    @Mapping(target = "sourceUpdatedAt", source = "lastUpdatedUtc")
    @Mapping(target = "activeStatus", expression = "java(Boolean.FALSE.equals(ticker.getActive()) ? ActiveStatus.INACTIVE : ActiveStatus.ACTIVE)")
    Stock updateFromMassive(MassiveTickerDetails ticker, @MappingTarget Stock stock);

    /** 來源缺少顯示代碼時不覆蓋既有 Symbol。 */
    @Condition
    @Named("hasText")
    default boolean hasText(String value) {
        return StringUtils.isNotBlank(value);
    }

    /** 將來源文字前後空白去除。 */
    @Named("trimText")
    default String trimText(String value) {
        return StringUtils.trimToNull(value);
    }

    /** 將 Massive 貨幣代碼正規化為大寫。 */
    @Named("normalizeCurrency")
    default String normalizeCurrency(String value) {
        String normalized = trimText(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

}
