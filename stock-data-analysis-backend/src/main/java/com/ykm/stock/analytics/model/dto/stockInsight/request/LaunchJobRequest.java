package com.ykm.stock.analytics.model.dto.stockInsight.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.ykm.stock.analytics.enums.TaskGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class LaunchJobRequest {
    @JsonAlias("jobName")
    @NotBlank
    private String taskCode;

    private Map<String, Object> jobParams;
    @NotNull
    private TaskGroup taskGroup;
}
