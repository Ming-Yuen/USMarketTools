package com.ykm.stock.analytics.model.dto.stockInsight;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class JobTask {
    private Long id;
    private String taskCode;
    private TaskGroup taskGroup;
    private Map<String, Object> jobParams;
    private String jobClassPath;
    private String cronExpression;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private ActiveStatus activeStatus;
}
