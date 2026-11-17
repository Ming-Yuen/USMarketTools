package com.ykm.stock.analytics.model.dto.stockInsight.response;

import com.ykm.stock.analytics.model.dto.stockInsight.JobTask;
import com.ykm.common.common_lib.model.ApiResponse;
import lombok.Data;

import java.util.List;

@Data
public class EnquiryJobResponse extends ApiResponse {
    private List<JobTask> jobTaskList;
}
