package com.ykm.stock.analytics.converter.jpa;

import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockConceptDto;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface StockClassificationConverter {
    /** CSV 只建立分類；不自行推測任何估值模型或合理倍數。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "industry", source = "industry")
    StockIndustry fromCsv(String code, String industry);

    StockClassificationResponse.Classification[] toClassificationResponse(List<StockIndustry> classifications);

    StockConceptDto[] toConceptResponse(List<StockConcept> concepts);
}
