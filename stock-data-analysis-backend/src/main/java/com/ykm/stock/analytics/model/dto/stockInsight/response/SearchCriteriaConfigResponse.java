package com.ykm.stock.analytics.model.dto.stockInsight.response;

import com.ykm.common.common_lib.model.ApiResponse;
import lombok.Data;

import java.util.List;

@Data
public class SearchCriteriaConfigResponse extends ApiResponse {

    private List<String> disabledFields;
}
