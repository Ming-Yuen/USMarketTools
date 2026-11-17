package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.converter.jpa.ScheduleConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.exception.ApplicationException;
import com.ykm.stock.analytics.model.dto.stockInsight.JobTask;
import com.ykm.stock.analytics.model.dto.stockInsight.request.EnquiryBatchJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.LaunchJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.request.UpdateJobRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.response.EnquiryJobResponse;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RequiredArgsConstructor
@Service
// 用途：負責「排程任務服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class JobService {
    private final Scheduler scheduler;
    private final JobConfigRepository jobConfigRepository;
    private final ScheduleConverter scheduleConverter;
    private final JobScheduleReconciler jobScheduleReconciler;

    // 用途：查找ByActive排程，作為「排程任務服務」流程中的處理步驟。
    public JobConfig[] findByActiveSchedule() {
        return jobConfigRepository.findByActiveStatusAndTaskGroup(ActiveStatus.ACTIVE, TaskGroup.SCHEDULE);
    }

    public void retireSchedule(String taskCode) {
        jobScheduleReconciler.remove(taskCode);
        JobConfig existing = jobConfigRepository.findByTaskCode(taskCode);
        if (existing != null) jobConfigRepository.delete(existing);
    }

    // 用途：處理enquiry排程任務任務，作為「排程任務服務」流程中的處理步驟。
    public EnquiryJobResponse enquiryJobTask(EnquiryBatchJobRequest enquiryBatchJobRequest) {
        EnquiryJobResponse response = new EnquiryJobResponse();
        List<JobConfig> jobConfigs = jobConfigRepository.findAll();
        List<JobTask> jobTaskList = new ArrayList<>();

        for (JobConfig jobConfig : jobConfigs) {
            JobTask jobTask = scheduleConverter.convertJobTask(jobConfig);
            jobTaskList.add(jobTask);
        }
        response.setJobTaskList(jobTaskList);
        return response;
    }

    // 用途：處理launch排程任務，作為「排程任務服務」流程中的處理步驟。
    public void launchJob(LaunchJobRequest launchJobRequest) throws ValidationException {
        launchJob(launchJobRequest.getTaskGroup(), launchJobRequest.getTaskCode(), launchJobRequest.getJobParams());
    }

    // 用途：處理launch排程任務，作為「排程任務服務」流程中的處理步驟。
    public void launchJob(JobConfig jobConfig) throws ValidationException {
        launchJob(jobConfig.getTaskGroup(), jobConfig.getTaskCode(), jobConfig.getConfig());
    }

    // 用途：處理launch排程任務，作為「排程任務服務」流程中的處理步驟。
    public void launchJob(TaskGroup taskGroup, String taskCode, Map<String, Object> config) throws ValidationException {
        try {
            if (TaskGroup.BATCH == taskGroup) {
                throw new ApplicationException("Legacy Spring Batch jobs are retired; migrate task to Quartz SCHEDULE: " + taskCode);
            } else if (TaskGroup.SCHEDULE == taskGroup) {
                JobKey jobKey = JobKey.jobKey(taskCode, taskGroup.name());
                if (!scheduler.checkExists(jobKey)) {
                    throw new ApplicationException("Job not exists: " + taskCode);
                }
                JobDataMap data = new JobDataMap();
                if (config != null) {
                    for (Map.Entry<String,Object> entry : config.entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        if (key == null || key.isBlank()) throw new ApplicationException("Job parameter key is blank");
                        if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean))
                            throw new ApplicationException("Unsupported job parameter type for " + key);
                        data.put(key, value);
                    }
                }
                scheduler.triggerJob(jobKey, data);
            }
        } catch (SchedulerException e) {
            throw new ApplicationException(e);
        }
    }

    // 用途：查找By排程任務Config，作為「排程任務服務」流程中的處理步驟。
    public JobConfig findByJobConfig(String taskCode) {
        return jobConfigRepository.findByTaskCode(taskCode);
    }

    // 用途：處理排程任務ConfigUpdate，作為「排程任務服務」流程中的處理步驟。
    public void jobConfigUpdate(JobConfig jobConfig) {
        jobConfigRepository.saveAndFlush(jobConfig);
    }

    // 用途：更新排程任務；DB 與 Quartz reconcile 任一步失敗都盡力恢復原設定，避免兩邊永久不一致。
    public synchronized void updateJob(UpdateJobRequest request) {
        JobConfig existing = request.getId() == null ? null : jobConfigRepository.findById(request.getId())
                .orElseThrow(() -> new ApplicationException("Job config not exists: " + request.getId()));
        if (existing == null && jobConfigRepository.existsByTaskCode(request.getTaskCode()))
            throw new ApplicationException("Task code already exists: " + request.getTaskCode());
        if (existing != null && !Objects.equals(existing.getTaskCode(), request.getTaskCode()))
            throw new ApplicationException("Task code cannot be changed after creation: " + existing.getTaskCode());

        JobConfig before = existing == null ? null : copyScheduleFields(existing);
        JobConfig candidate = existing == null ? new JobConfig() : existing;
        candidate.setTaskCode(request.getTaskCode());
        candidate.setTaskGroup(request.getTaskGroup());
        candidate.setJobClassName(request.getJobClassPath());
        candidate.setCronExpression(request.getCronExpression());
        candidate.setConfig(request.getJobParams());
        candidate.setActiveStatus(request.getActiveStatus());
        candidate.setStartTime(request.getStartTime());
        candidate.setEndTime(request.getEndTime());
        jobScheduleReconciler.validate(candidate);
        JobConfig saved = jobConfigRepository.saveAndFlush(candidate);
        try {
            jobScheduleReconciler.reconcile(saved);
        } catch (RuntimeException reconcileFailure) {
            try {
                if (before == null) {
                    jobConfigRepository.delete(saved);
                    jobConfigRepository.flush();
                    jobScheduleReconciler.remove(saved.getTaskCode());
                } else {
                    restoreScheduleFields(saved, before);
                    JobConfig restored = jobConfigRepository.saveAndFlush(saved);
                    jobScheduleReconciler.reconcile(restored);
                }
            } catch (RuntimeException compensationFailure) {
                reconcileFailure.addSuppressed(compensationFailure);
            }
            throw reconcileFailure;
        }
    }

    // 用途：複製可由 Job update 修改的排程欄位，供 reconcile 失敗時補償恢復。
    private JobConfig copyScheduleFields(JobConfig source) {
        JobConfig copy = new JobConfig();
        copy.setTaskCode(source.getTaskCode()); copy.setTaskGroup(source.getTaskGroup()); copy.setJobClassName(source.getJobClassName());
        copy.setCronExpression(source.getCronExpression()); copy.setTriggerType(source.getTriggerType());
        copy.setConfig(source.getConfig() == null ? null : Map.copyOf(source.getConfig())); copy.setActiveStatus(source.getActiveStatus());
        copy.setStartTime(source.getStartTime()); copy.setEndTime(source.getEndTime()); copy.setRetryCount(source.getRetryCount()); copy.setMaxRetryCount(source.getMaxRetryCount());
        return copy;
    }

    // 用途：把先前快照欄位恢復到同一 persistent JobConfig entity。
    private void restoreScheduleFields(JobConfig target, JobConfig source) {
        target.setTaskCode(source.getTaskCode()); target.setTaskGroup(source.getTaskGroup()); target.setJobClassName(source.getJobClassName());
        target.setCronExpression(source.getCronExpression()); target.setTriggerType(source.getTriggerType());
        target.setConfig(source.getConfig()); target.setActiveStatus(source.getActiveStatus()); target.setStartTime(source.getStartTime()); target.setEndTime(source.getEndTime());
        target.setRetryCount(source.getRetryCount()); target.setMaxRetryCount(source.getMaxRetryCount());
    }

}
