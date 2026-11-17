package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.scheduling.StockDailyTask;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobScheduleTimezoneTests {
    @ParameterizedTest
    @ValueSource(strings = {"UTC", "America/New_York"})
    void cronAndStartEndAlwaysUseHongKongRegardlessOfJvmTimezone(String jvmZone) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
            Scheduler scheduler = mock(Scheduler.class);
            JobConfig config = new JobConfig();
            config.setTaskCode("TIMEZONE_CHECK");
            config.setTaskGroup(TaskGroup.SCHEDULE);
            config.setJobClassName(StockDailyTask.class.getName());
            config.setActiveStatus(ActiveStatus.ACTIVE);
            config.setCronExpression("0 0 9 * * ?");
            config.setStartTime(LocalDateTime.of(2027, 1, 15, 0, 0));
            config.setEndTime(LocalDateTime.of(2027, 12, 31, 23, 59));

            new JobScheduleReconciler(scheduler).reconcile(config);

            ArgumentCaptor<CronTrigger> trigger = ArgumentCaptor.forClass(CronTrigger.class);
            verify(scheduler).scheduleJob(any(JobDetail.class), trigger.capture());
            assertThat(trigger.getValue().getTimeZone().getID()).isEqualTo("Asia/Hong_Kong");
            assertThat(trigger.getValue().getStartTime().toInstant()).isEqualTo(Instant.parse("2027-01-14T16:00:00Z"));
            assertThat(trigger.getValue().getEndTime().toInstant()).isEqualTo(Instant.parse("2027-12-31T15:59:00Z"));
            assertThat(trigger.getValue().getFireTimeAfter(Date.from(Instant.parse("2027-01-15T00:00:00Z"))).toInstant())
                    .isEqualTo(Instant.parse("2027-01-15T01:00:00Z"));
            assertThat(trigger.getValue().getFireTimeAfter(Date.from(Instant.parse("2027-07-15T00:00:00Z"))).toInstant())
                    .isEqualTo(Instant.parse("2027-07-15T01:00:00Z"));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
