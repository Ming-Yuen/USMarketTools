package com.ykm.stock.analytics.service.upload;

import com.ykm.stock.analytics.converter.jpa.UploadJobConverter;
import com.ykm.stock.analytics.enums.ProcessStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadJobResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;
import com.ykm.stock.analytics.model.entity.UploadJob;
import com.ykm.stock.analytics.repository.jpa.UploadJobRepository;
import com.ykm.stock.analytics.service.upload.UploadHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UploadJobService {
    private final UploadJobRepository jobs;
    private final Map<String, UploadHandler> handlers;
    private final UploadJobConverter converter;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;
    private final LocalDateTime startup = LocalDateTime.now();

    public UploadJobService(UploadJobRepository jobs, List<UploadHandler> handlers, UploadJobConverter converter,
                            TransactionTemplate transactions, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.jobs = jobs;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(UploadHandler::type, handler -> handler));
        this.converter = converter;
        this.transactions = transactions;
        this.executor = executor;
    }

    /** 前端可用功能與實際 Handler 註冊表共用同一來源，不另外維護固定清單。 */
    public List<String> types() {
        return handlers.keySet().stream().sorted().toList();
    }

    /** 只保存輸入及排入背景執行；請求 ID 可在接收回應 timeout 後重新查詢或安全重送。 */
    public UploadJobResponse submit(UUID id, String type, MultipartFile file) throws IOException {
        if (type == null || !handlers.containsKey(type)) throw new IllegalArgumentException("UNSUPPORTED_UPLOAD_TYPE");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("CSV_FILE_REQUIRED");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("CSV_FILE_TOO_LARGE");
        byte[] input = file.getBytes();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
        UploadJob existing = jobs.findById(id).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getInputHash(), hash) || !Objects.equals(existing.getType(), type))
                throw new IllegalArgumentException("UPLOAD_ID_CONTENT_MISMATCH");
            return response(existing);
        }
        String name = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        if (name.length() > 255) throw new IllegalArgumentException("CSV_FILENAME_TOO_LONG");
        UploadJob job = converter.newUploadJob(id, type, name, hash, input);
        transactions.executeWithoutResult(status -> jobs.saveAndFlush(job));
        try { executor.execute(() -> process(id)); }
        catch (RuntimeException ex) { fail(id, "UPLOAD_QUEUE_UNAVAILABLE"); throw ex; }
        return get(id);
    }

    public UploadJobResponse get(UUID id) {
        UploadJobRepository.Summary job = jobs.findSummaryById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND"));
        UploadResult result = job.getStatus() == ProcessStatus.COMPLETED ? new UploadResult(job.getTotalRows(), job.getUpdated(),
                job.getUnchanged(), job.getCreatedIndustries(), job.getMissingIndustry(), job.getUnmatched(), job.getAmbiguous(), job.getIssues()) : null;
        return new UploadJobResponse(job.getId(), job.getFileName(), job.getStatus(), job.getStartedAt(), job.getFinishedAt(), job.getError(), result);
    }

    /** 重啟不自動重試；舊的未完成交易由 DB 回滾，只把本次啟動前的任務標示為失敗。 */
    @EventListener(ApplicationReadyEvent.class)
    public void interrupted() {
        transactions.executeWithoutResult(status -> jobs.failInterrupted(startup, Instant.now(), ProcessStatus.FAILED,
                List.of(ProcessStatus.PENDING, ProcessStatus.PROCESSING)));
    }

    void process(UUID id) {
        try {
            transactions.executeWithoutResult(status -> {
                UploadJob job = jobs.findById(id).orElseThrow();
                job.setStatus(ProcessStatus.PROCESSING);
                job.setStartedAt(Instant.now());
                jobs.saveAndFlush(job);
            });
            // 按類型呼叫功能 Handler；資料更新與 COMPLETED 在同一交易提交。
            transactions.executeWithoutResult(status -> {
                UploadJob job = jobs.findById(id).orElseThrow();
                UploadHandler handler = handlers.get(job.getType());
                if (handler == null) throw new IllegalArgumentException("UNSUPPORTED_UPLOAD_TYPE");
                UploadResult result = handler.handle(job.getInputData());
                converter.applyUploadResult(result, job);
                job.setStatus(ProcessStatus.COMPLETED);
                job.setFinishedAt(Instant.now());
                job.setInputData(null);
                jobs.saveAndFlush(job);
            });
        } catch (Exception ex) {
            log.error("Upload failed | id={}", id, ex);
            fail(id, ex instanceof IllegalArgumentException ? ex.getMessage() : "IMPORT_FAILED");
        }
    }

    private void fail(UUID id, String reason) {
        transactions.executeWithoutResult(status -> {
            UploadJob job = jobs.findById(id).orElseThrow();
            job.setStatus(ProcessStatus.FAILED);
            job.setFinishedAt(Instant.now());
            job.setInputData(null);
            job.setError(reason == null ? "IMPORT_FAILED" : reason.substring(0, Math.min(1000, reason.length())));
            jobs.saveAndFlush(job);
        });
    }

    private UploadJobResponse response(UploadJob job) {
        UploadResult result = job.getStatus() == ProcessStatus.COMPLETED ? new UploadResult(job.getTotalRows(), job.getUpdated(),
                job.getUnchanged(), job.getCreatedIndustries(), job.getMissingIndustry(), job.getUnmatched(), job.getAmbiguous(), job.getIssues()) : null;
        return new UploadJobResponse(job.getId(), job.getFileName(), job.getStatus(), job.getStartedAt(), job.getFinishedAt(), job.getError(), result);
    }
}
