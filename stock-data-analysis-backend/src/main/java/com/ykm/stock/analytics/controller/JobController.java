package com.ykm.stock.analytics.controller;

import com.ykm.common.common_lib.model.ApiResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.request.EnquiryBatchJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.LaunchJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.UpdateJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.response.EnquiryJobResponse;
import com.ykm.stock.analytics.service.JobService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("job")
@AllArgsConstructor
@Validated
public class JobController {
    private final JobService jobService;

    @PostMapping("launch")
    public ApiResponse launchBatchJob(@Valid @RequestBody LaunchJobRequest launchJobRequest){
        jobService.launchJob(launchJobRequest);
        return new ApiResponse();
    }

    @PostMapping("enquiry")
    public EnquiryJobResponse enquiry(EnquiryBatchJobRequest enquiryBatchJobRequest){
        return jobService.enquiryJobTask(enquiryBatchJobRequest);
    }

    @PostMapping("update")
    public ApiResponse update(@Valid @RequestBody UpdateJobRequest request) {
        jobService.updateJob(request);
        return new ApiResponse();
    }
}
