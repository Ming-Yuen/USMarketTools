package com.ykm.stock.analytics.converter.jpa;

import com.ykm.stock.analytics.model.dto.stockInsight.request.UpdateCriteriaConfigRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.response.SearchCriteriaConfigResponse;
import com.ykm.stock.analytics.model.entity.SearchCriteriaConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SearchCriteriaConfigConverter {
    SearchCriteriaConfigResponse convert(SearchCriteriaConfig searchCriteriaConfig);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", source = "currentUser")
    @Mapping(target = "disabledFields", source = "updateCriteriaConfigRequest.disabledFields")
    @Mapping(target = "pageKey", source = "updateCriteriaConfigRequest.pageKey")
    SearchCriteriaConfig instance(String currentUser, UpdateCriteriaConfigRequest updateCriteriaConfigRequest);
}
