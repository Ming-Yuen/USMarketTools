package com.ykm.stock.analytics.service;

import com.ykm.orm.config.AuditingConfig;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.exception.ApplicationException;
import com.ykm.stock.analytics.listener.ScheduleJobListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ScheduleJobListenerTests {
    private Scheduler scheduler;
    private ScheduleJobListener listener;

    @BeforeEach
    void setUp() {
        scheduler = mock(Scheduler.class);
        listener = new ScheduleJobListener(scheduler);
    }

    @AfterEach
    void clearAuditSource() {
        AuditingConfig.clearOperationSource();
    }

    /** 排程只維護操作來源，不再保存執行紀錄。 */
    @Test
    void scheduledJobKeepsAuditSourceDuringExecutionAndClearsItAfterwards() throws Exception {
        JobExecutionContext context = context(TaskCode.STOCK_DAILY, "SCHEDULE");
        AuditingConfig auditor = new AuditingConfig();

        listener.jobToBeExecuted(context);
        assertThat(auditor.getCurrentAuditor()).hasValue(TaskCode.STOCK_DAILY);

        listener.jobWasExecuted(context, null);
        assertThat(auditor.getCurrentAuditor()).hasValue("SYSTEM");
        verify(scheduler, never()).deleteJob(any(JobKey.class));
    }

    /** 被 veto 的 Job 同樣不能把 ThreadLocal 審計來源留給後續 Job。 */
    @Test
    void vetoClearsAuditSource() {
        JobExecutionContext context = context("VETO_JOB", "SCHEDULE");

        listener.jobToBeExecuted(context);
        listener.jobExecutionVetoed(context);

        assertThat(new AuditingConfig().getCurrentAuditor()).hasValue("SYSTEM");
    }

    /** 臨時 Job 完成或失敗後都須移除 Quartz 註冊，不建立執行歷史。 */
    @Test
    void temporaryJobIsDeletedAfterFailure() throws Exception {
        JobExecutionContext context = context("ONE_OFF", "TEMP");
        listener.jobToBeExecuted(context);

        listener.jobWasExecuted(context, new JobExecutionException("source unavailable"));

        verify(scheduler).deleteJob(new JobKey("ONE_OFF", "TEMP"));
        assertThat(new AuditingConfig().getCurrentAuditor()).hasValue("SYSTEM");
    }

    @Test
    void quartzCleanupFailureStillClearsAuditSource() throws Exception {
        JobExecutionContext context = context("ONE_OFF", "TEMP");
        when(scheduler.deleteJob(new JobKey("ONE_OFF", "TEMP"))).thenThrow(new SchedulerException("cleanup failed"));
        listener.jobToBeExecuted(context);

        assertThatThrownBy(() -> listener.jobWasExecuted(context, null))
                .isInstanceOf(ApplicationException.class);
        assertThat(new AuditingConfig().getCurrentAuditor()).hasValue("SYSTEM");
    }

    private JobExecutionContext context(String taskCode, String group) {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getKey()).thenReturn(new JobKey(taskCode, group));
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(detail);
        return context;
    }
}
