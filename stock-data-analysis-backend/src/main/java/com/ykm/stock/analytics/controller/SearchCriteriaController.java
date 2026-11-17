package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.request.SearchCriteriaConfigRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.UpdateCriteriaConfigRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.response.SearchCriteriaConfigResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.response.UpdateCriteriaConfigResponse;
import com.ykm.stock.analytics.service.SearchCriteriaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("searchCriteria")
@RequiredArgsConstructor
public class SearchCriteriaController {

    private final SearchCriteriaService searchConfigService;

    @PostMapping("enquiry")
    public SearchCriteriaConfigResponse enquiry(@Valid @RequestBody SearchCriteriaConfigRequest searchCriteriaConfigRequest) {
        return searchConfigService.enquiry(searchCriteriaConfigRequest);
    }

    @PostMapping("update")
    public UpdateCriteriaConfigResponse update(@Valid @RequestBody UpdateCriteriaConfigRequest updateCriteriaConfigRequest) {
        return searchConfigService.update(updateCriteriaConfigRequest);
    }
}
