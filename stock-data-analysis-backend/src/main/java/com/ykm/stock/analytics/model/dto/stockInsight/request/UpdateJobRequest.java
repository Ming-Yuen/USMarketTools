package com.ykm.stock.analytics.model.dto.stockInsight.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class UpdateJobRequest {
    private Long id;
    @JsonAlias("jobName")
    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
    private String taskCode;
    @NotNull
    private TaskGroup taskGroup;
    @NotBlank
    private String jobClassPath;
    @NotBlank
    private String cronExpression;
    private Map<String, Object> jobParams;
    @NotNull
    private ActiveStatus activeStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
