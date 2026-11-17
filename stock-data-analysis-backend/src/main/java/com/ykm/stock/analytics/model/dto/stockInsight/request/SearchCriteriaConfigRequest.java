package com.ykm.stock.analytics.model.dto.stockInsight.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SearchCriteriaConfigRequest {

    @NotBlank
    private String pageKey;
}
