package com.ykm.stock.analytics.converter.jpa;

import com.ykm.common.common_lib.converter.CommonTypeConverter;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TreasuryParYieldDailyConverter extends CommonTypeConverter {
    @Mapping(target = "observationDate", source = "date")
    @Mapping(target = "sourceUpdatedAtUtc", ignore = true)
    @Mapping(target = "observedAt", ignore = true)
    TreasuryParYieldDaily toEntity(TreasuryYieldCurveFeed.Properties properties);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "observationDate", ignore = true)
    @Mapping(target = "sourceUpdatedAtUtc", ignore = true)
    @Mapping(target = "observedAt", ignore = true)
    void updateEntity(TreasuryYieldCurveFeed.Properties properties, @MappingTarget TreasuryParYieldDaily entity);

    default LocalDateTime atomUpdatedToUtcLocalDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
