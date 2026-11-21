package com.ykm.stock.analytics.controller;

import com.ykm.common.common_lib.model.ApiResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.request.EnquiryBatchJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.LaunchJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.UpdateJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.response.EnquiryJobResponse;
import com.ykm.stock.analytics.scheduling.SignalReplayAuditJob;
import com.ykm.stock.analytics.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("job")
@RequiredArgsConstructor
@Validated
public class JobController {
    private final JobService jobService;
    @Autowired(required = false)
    private SignalReplayAuditJob signalReplayAuditJob;

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

    /** 查詢歷史信號回放排程的即時執行進度、狀態與可下載獨立 CSV 檔案清單。 */
    @GetMapping("signal-replay/status")
    public Map<String, Object> signalReplayStatus() {
        if (signalReplayAuditJob == null) {
            return Map.of("status", "IDLE", "progressPct", 0, "files", java.util.List.of());
        }
        return signalReplayAuditJob.getStatusSnapshot();
    }

    /** 直接下載未壓縮之獨立 .csv 檔案（events 或 summary），不打包 ZIP。 */
    @GetMapping("signal-replay/download")
    public ResponseEntity<Resource> downloadSignalReplayCsv(@RequestParam("type") String type) {
        if (signalReplayAuditJob == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SIGNAL_REPLAY_NOT_AVAILABLE");
        }
        Path file;
        try {
            file = signalReplayAuditJob.resolveDownloadFile(type);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SIGNAL_REPLAY_CSV_NOT_FOUND");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
    }
}
