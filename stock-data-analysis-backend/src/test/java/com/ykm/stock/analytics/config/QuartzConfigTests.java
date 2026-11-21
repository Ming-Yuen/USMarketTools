package com.ykm.stock.analytics.config;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.init.DataInitializer;
import com.ykm.stock.analytics.listener.ScheduleJobListener;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.JobService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.ListenerManager;
import org.quartz.Scheduler;
import org.springframework.core.annotation.Order;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuartzConfigTests {
    /** Scenario：DEV 驗收關閉自動排程時，不註冊、不補跑、不啟動任何 Job。 */
    @Test
    void disabledSchedulesOnlyRunRetiredJobCleanup() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        JobService jobs = mock(JobService.class);
        ScheduleJobListener listener = mock(ScheduleJobListener.class);
        JobScheduleReconciler reconciler = mock(JobScheduleReconciler.class);
        QuartzConfig config = new QuartzConfig(scheduler, jobs, listener, reconciler);
        ReflectionTestUtils.setField(config, "schedulesEnabled", false);

        config.run();

        for (String retiredCode : TaskCode.RETIRED_SCHEDULE_CODES) {
            verify(jobs).retireSchedule(retiredCode);
        }
        verifyNoMoreInteractions(jobs);
        verifyNoInteractions(scheduler, listener, reconciler);
    }

    @Test
    void enabledSchedulesRegisterExistingJobsAndListenerBeforeStarting() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        ListenerManager listeners = mock(ListenerManager.class);
        when(scheduler.getListenerManager()).thenReturn(listeners);
        JobService jobs = mock(JobService.class);
        JobConfig active = new JobConfig();
        active.setTaskCode("TEST");
        when(jobs.findByActiveSchedule()).thenReturn(new JobConfig[]{active});
        ScheduleJobListener listener = mock(ScheduleJobListener.class);
        JobScheduleReconciler reconciler = mock(JobScheduleReconciler.class);
        QuartzConfig config = new QuartzConfig(scheduler, jobs, listener, reconciler);
        ReflectionTestUtils.setField(config, "schedulesEnabled", true);

        config.run();

        InOrder order = inOrder(scheduler, listeners, jobs, reconciler);
        order.verify(jobs).retireSchedule("SEC_FINANCIAL_FACT_DAILY");
        order.verify(jobs).retireSchedule("FUTU_VALUATION_DAILY_SYNC");
        order.verify(scheduler).getListenerManager();
        order.verify(listeners).addJobListener(listener);
        order.verify(jobs).findByActiveSchedule();
        order.verify(reconciler).reconcile(eq(active), any(Date.class));
        order.verify(scheduler).start();
        verify(scheduler, never()).addJob(any(JobDetail.class), anyBoolean());
        verify(scheduler, never()).deleteJob(any(JobKey.class));
        assertThat(QuartzConfig.class.getAnnotation(Order.class).value())
                .isGreaterThan(DataInitializer.class.getAnnotation(Order.class).value());
    }

    /** Scenario：任一啟用 Job 的註冊失敗，必須保留原因且不啟動 Scheduler。 */
    @Test
    void registrationFailureStopsStartupAndPreservesCause() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getListenerManager()).thenReturn(mock(ListenerManager.class));
        JobService jobs = mock(JobService.class);
        JobConfig active = new JobConfig();
        active.setTaskCode("TEST");
        when(jobs.findByActiveSchedule()).thenReturn(new JobConfig[]{active});
        JobScheduleReconciler reconciler = mock(JobScheduleReconciler.class);
        IllegalStateException failure = new IllegalStateException("invalid schedule");
        doThrow(failure).when(reconciler).reconcile(eq(active), any(Date.class));
        QuartzConfig config = new QuartzConfig(scheduler, jobs, mock(ScheduleJobListener.class), reconciler);
        ReflectionTestUtils.setField(config, "schedulesEnabled", true);

        assertThatThrownBy(config::run).isInstanceOf(RuntimeException.class).hasCause(failure);

        verify(scheduler, never()).start();
    }
}
