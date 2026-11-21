package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import lombok.RequiredArgsConstructor;
import org.quartz.*;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

@Service
@RequiredArgsConstructor
// 用途：負責「排程任務排程校準」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class JobScheduleReconciler {
    public static final String SCHEDULE_TIMEZONE = "Asia/Hong_Kong";

    private final Scheduler scheduler;

    // 用途：驗證相關資料，作為「排程任務排程校準」流程中的處理步驟。
    public void validate(JobConfig config) {
        if (config == null || config.getTaskCode() == null || config.getTaskCode().isBlank())
            throw new IllegalArgumentException("Task code is required");
        if (config.getTaskGroup() != TaskGroup.SCHEDULE)
            throw new IllegalArgumentException("Only Quartz SCHEDULE tasks are supported: " + config.getTaskGroup());
        if (config.getTriggerType() != JobConfig.TriggerType.CRON)
            throw new IllegalArgumentException("Unsupported triggerType: " + config.getTriggerType());
        if (config.getCronExpression() != null && !config.getCronExpression().isBlank()
                && !CronExpression.isValidExpression(config.getCronExpression()))
            throw new IllegalArgumentException("Invalid cron expression: " + config.getCronExpression());
        if (config.getStartTime() != null && config.getEndTime() != null
                && config.getStartTime().isAfter(config.getEndTime()))
            throw new IllegalArgumentException("startTime must be <= endTime");
        resolveJobClass(config);
    }

    // 用途：校準相關資料，作為「排程任務排程校準」流程中的處理步驟。
    public void reconcile(JobConfig config) {
        reconcile(config, new Date());
    }

    /** 重新註冊 Cron 時只保留之後的時段。 */
    public void reconcile(JobConfig config, Date resumeAfter) {
        validate(config);
        if (config.getTaskGroup() != TaskGroup.SCHEDULE) return;
        JobKey jobKey = JobKey.jobKey(config.getTaskCode(), TaskGroup.SCHEDULE.name());
        TriggerKey triggerKey = TriggerKey.triggerKey(config.getTaskCode() + "-trigger", TaskGroup.SCHEDULE + "-triggers");
        try {
            if (config.getActiveStatus() != ActiveStatus.ACTIVE) {
                if (scheduler.checkExists(triggerKey)) scheduler.unscheduleJob(triggerKey);
                return;
            }
            Class<? extends Job> jobClass = resolveJobClass(config);
            JobDataMap data = new JobDataMap();
            Map<String,Object> cfg = config.getConfig();
            if (cfg != null) data.putAll(cfg);
            JobDetail detail = JobBuilder.newJob(jobClass).withIdentity(jobKey).storeDurably().usingJobData(data).build();
            // 空白 Cron 代表手動觸發型 Ad-hoc 排程：只註冊 durable JobDetail，不掛任何定時 Trigger。
            if (config.getCronExpression() == null || config.getCronExpression().isBlank()) {
                scheduler.addJob(detail, true);
                if (scheduler.checkExists(triggerKey)) {
                    scheduler.unscheduleJob(triggerKey);
                }
                return;
            }
            CronScheduleBuilder schedule = CronScheduleBuilder.cronSchedule(config.getCronExpression())
                    .inTimeZone(TimeZone.getTimeZone(SCHEDULE_TIMEZONE))
                    .withMisfireHandlingInstructionIgnoreMisfires();
            TriggerBuilder<CronTrigger> builder = TriggerBuilder.newTrigger().withIdentity(triggerKey).forJob(detail).withSchedule(schedule);
            ZoneId zone = ZoneId.of(SCHEDULE_TIMEZONE);
            Date start = Date.from(resumeAfter.toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1));
            if (config.getStartTime() != null && config.getStartTime().atZone(zone).toInstant().isAfter(start.toInstant()))
                start = Date.from(config.getStartTime().atZone(zone).toInstant());
            if (config.getEndTime() != null && config.getEndTime().atZone(zone).toInstant().isBefore(start.toInstant())) {
                scheduler.addJob(detail, true);
                scheduler.unscheduleJob(triggerKey);
                return;
            }
            builder.startAt(start);
            if (config.getEndTime() != null) builder.endAt(Date.from(config.getEndTime().atZone(zone).toInstant()));
            CronTrigger trigger = builder.build();
            // 剩餘有效期內沒有下一個 Cron 時，只保留可手動觸發的 Job。
            if (trigger.getFireTimeAfter(new Date(start.getTime() - 1000)) == null) {
                scheduler.addJob(detail, true);
                scheduler.unscheduleJob(triggerKey);
                return;
            }
            if (scheduler.checkExists(jobKey)) {
                scheduler.addJob(detail, true);
                if (scheduler.checkExists(triggerKey)) scheduler.rescheduleJob(triggerKey, trigger);
                else scheduler.scheduleJob(trigger);
            } else {
                scheduler.scheduleJob(detail, trigger);
            }
        } catch (SchedulerException ex) {
            throw new IllegalStateException("RECONCILE_FAILED: " + config.getTaskCode(), ex);
        }
    }

    // 用途：刪除指定 schedule 的 Quartz job/trigger，供新增設定 reconcile 失敗時執行補償。
    public void remove(String taskCode) {
        if (taskCode == null || taskCode.isBlank()) return;
        try { scheduler.deleteJob(JobKey.jobKey(taskCode, TaskGroup.SCHEDULE.name())); }
        catch (SchedulerException ex) { throw new IllegalStateException("REMOVE_FAILED: " + taskCode, ex); }
    }

    // 用途：依 JobConfig 中的 class 設定解析實際 Quartz Job 類別，並驗證其可被排程器載入。
    private Class<? extends Job> resolveJobClass(JobConfig config) {
        String className = config.getJobClassName();
        if (className == null || className.isBlank())
            throw new IllegalArgumentException("Job class name is required for task code: " + config.getTaskCode());
        try {
            Class<?> jobClass = ClassUtils.forName(className.trim(), ClassUtils.getDefaultClassLoader());
            if (!Job.class.isAssignableFrom(jobClass))
                throw new IllegalArgumentException("Configured class does not implement Quartz Job: " + className);
            return jobClass.asSubclass(Job.class);
        } catch (ClassNotFoundException | LinkageError ex) {
            throw new IllegalArgumentException("Scheduled Job class not found: " + className, ex);
        }
    }

}
