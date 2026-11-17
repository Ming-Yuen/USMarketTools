package com.ykm.stock.analytics.converter.jpa;

import com.ykm.stock.analytics.model.dto.classification.ClassificationPlate;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockConcept;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface StockClassificationSyncConverter {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "industry", source = "plate.name", qualifiedByName = "trimPlateText")
    @Mapping(target = "sourcePlateCode", source = "plate.code", qualifiedByName = "trimPlateText")
    StockIndustry newIndustry(String code, ClassificationPlate plate);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "industry", source = "name", qualifiedByName = "trimPlateText")
    @Mapping(target = "sourcePlateCode", source = "code", qualifiedByName = "trimPlateText")
    void updateIndustry(ClassificationPlate plate, @MappingTarget StockIndustry industry);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "plate.name", qualifiedByName = "trimPlateText")
    @Mapping(target = "sourcePlateCode", source = "plate.code", qualifiedByName = "trimPlateText")
    StockConcept newConcept(String code, ClassificationPlate plate);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "name", source = "name", qualifiedByName = "trimPlateText")
    @Mapping(target = "sourcePlateCode", source = "code", qualifiedByName = "trimPlateText")
    void updateConcept(ClassificationPlate plate, @MappingTarget StockConcept concept);

    @Named("trimPlateText")
    default String trimPlateText(String value) {
        return value == null ? null : value.trim();
    }
}
