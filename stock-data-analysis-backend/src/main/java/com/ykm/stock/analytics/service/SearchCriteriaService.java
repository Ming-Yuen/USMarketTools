package com.ykm.stock.analytics.service;

import com.ykm.orm.config.AuditingConfig;
import com.ykm.stock.analytics.converter.jpa.SearchCriteriaConfigConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.request.SearchCriteriaConfigRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.UpdateCriteriaConfigRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.response.SearchCriteriaConfigResponse;
import com.ykm.stock.analytics.model.entity.SearchCriteriaConfig;
import com.ykm.stock.analytics.model.dto.stockInsight.response.UpdateCriteriaConfigResponse;
import com.ykm.stock.analytics.repository.jpa.SearchCriteriaConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 用途：負責「搜尋條件服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class SearchCriteriaService {

    private final SearchCriteriaConfigRepository searchCriteriaConfigRepository;
    private final SearchCriteriaConfigConverter searchCriteriaConfigConverter;

    private final AuditingConfig auditingConfig;

    // 用途：處理enquiry，作為「搜尋條件服務」流程中的處理步驟。
    public SearchCriteriaConfigResponse enquiry(SearchCriteriaConfigRequest searchCriteriaConfigRequest) {
        SearchCriteriaConfig searchCriteriaConfig = searchCriteriaConfigRepository.findByUserIdAndPageKey(auditingConfig.getCurrentUser(), searchCriteriaConfigRequest.getPageKey());

        if (searchCriteriaConfig == null) {
            return new SearchCriteriaConfigResponse();
        }

        SearchCriteriaConfigResponse searchCriteriaConfigResponse = searchCriteriaConfigConverter.convert(searchCriteriaConfig);
        return searchCriteriaConfigResponse;
    }

    // 用途：更新相關資料，作為「搜尋條件服務」流程中的處理步驟。
    @Transactional
    public UpdateCriteriaConfigResponse update(UpdateCriteriaConfigRequest updateCriteriaConfigRequest) {
        SearchCriteriaConfig searchCriteriaConfig = searchCriteriaConfigRepository.findByUserIdAndPageKey(auditingConfig.getCurrentUser(), updateCriteriaConfigRequest.getPageKey());
        if(searchCriteriaConfig == null){
            searchCriteriaConfig = searchCriteriaConfigConverter.instance(auditingConfig.getCurrentUser(), updateCriteriaConfigRequest);
        }
        searchCriteriaConfig.setDisabledFields(updateCriteriaConfigRequest.getDisabledFields());
        searchCriteriaConfigRepository.save(searchCriteriaConfig);
        return new UpdateCriteriaConfigResponse();
    }
}
