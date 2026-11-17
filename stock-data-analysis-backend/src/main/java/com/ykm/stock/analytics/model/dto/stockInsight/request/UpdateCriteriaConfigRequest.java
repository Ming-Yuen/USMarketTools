package com.ykm.stock.analytics.model.dto.stockInsight.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class UpdateCriteriaConfigRequest {
    @NotBlank
    private String pageKey;
    private List<String> disabledFields;
}
