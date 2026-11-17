package com.ykm.stock.analytics.listener;

import com.ykm.orm.config.AuditingConfig;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.exception.ApplicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleJobListener implements JobListener {
    private final Scheduler scheduler;

    @Override
    public String getName() {
        return ScheduleJobListener.class.getSimpleName();
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        String taskCode = context.getJobDetail().getKey().getName();
        AuditingConfig.setOperationSource(taskCode);
        log.info("begin of {}", taskCode);
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        AuditingConfig.clearOperationSource();
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        JobKey jobKey = context.getJobDetail().getKey();
        try {
            if (jobException != null) log.error("Schedule failed | taskCode={}", jobKey.getName(), jobException);
            if (TaskGroup.TEMP.name().equals(jobKey.getGroup())) scheduler.deleteJob(jobKey);
        } catch (SchedulerException exception) {
            throw new ApplicationException(exception);
        } finally {
            AuditingConfig.clearOperationSource();
            log.info("end of {}", jobKey.getName());
        }
    }
}
