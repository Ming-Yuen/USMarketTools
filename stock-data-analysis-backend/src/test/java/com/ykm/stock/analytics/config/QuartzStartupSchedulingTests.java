package com.ykm.stock.analytics.config;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.listener.ScheduleJobListener;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.JobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuartzStartupSchedulingTests {
    @ParameterizedTest
    @ValueSource(strings = {TaskCode.FINANCIAL_REPORT_DAILY_SYNC, TaskCode.STOCK_CLASSIFICATION_DAILY_SYNC, TaskCode.EARNINGS_FORECAST_DAILY_SYNC})
    void renamedJobUsesOnlyItsNextCron(String newCode) throws Exception {
        Scheduler scheduler = scheduler();
        try {
            String oldCode = TaskCode.PREVIOUS_SCHEDULE_CODES.get(newCode);
            JobConfig renamed = config(oldCode, 6);
            seedOldCron(scheduler, renamed);
            renamed.setTaskCode(newCode);
            JobService jobs = mock(JobService.class);
            when(jobs.findByActiveSchedule()).thenReturn(new JobConfig[]{renamed});
            doAnswer(call -> scheduler.deleteJob(JobKey.jobKey(oldCode, "SCHEDULE"))).when(jobs).retireSchedule(oldCode);
            ScheduleJobListener listener = mock(ScheduleJobListener.class);
            when(listener.getName()).thenReturn("test-listener");
            new QuartzConfig(scheduler, jobs, listener, new JobScheduleReconciler(scheduler)).registerDatabaseTasks();

            assertThat(scheduler.checkExists(JobKey.jobKey(oldCode, "SCHEDULE"))).isFalse();
            assertThat(scheduler.getTriggersOfJob(JobKey.jobKey(newCode, "SCHEDULE"))).hasSize(1).allMatch(CronTrigger.class::isInstance);
            assertThat(scheduler.getTrigger(key(newCode)).getNextFireTime()).isAfter(new Date());
        } finally { scheduler.shutdown(true); }
    }

    @Test
    void startupDiscardsMissedCronTimesAndPreviouslyQueuedCatchups() throws Exception {
        Scheduler scheduler = scheduler();
        try {
            JobConfig early = config("EARLY", 4), fresh = config("FRESH", 9);
            seedOldCron(scheduler, early);
            Date original = scheduler.getTrigger(key("EARLY")).getNextFireTime();
            scheduler.scheduleJob(TriggerBuilder.newTrigger().withIdentity("EARLY-catchup", "SCHEDULE-catchups")
                    .forJob("EARLY", "SCHEDULE").startAt(original).build());
            QuartzConfig startup = startup(scheduler, fresh, early);
            startup.registerDatabaseTasks();
            assertThat(scheduler.getTrigger(TriggerKey.triggerKey("EARLY-catchup", "SCHEDULE-catchups"))).isNull();
            for (String name : List.of("EARLY", "FRESH")) {
                assertThat(scheduler.getTriggersOfJob(JobKey.jobKey(name, "SCHEDULE"))).hasSize(1).allMatch(CronTrigger.class::isInstance);
                assertThat(scheduler.getTrigger(key(name)).getNextFireTime()).isAfter(new Date());
            }
        } finally { scheduler.shutdown(true); }
    }

    @Test
    void cronAndManualJobsStillWaitWhenWorkerIsBusy() throws Exception {
        Scheduler scheduler = scheduler();
        RecordingJob.order.clear();
        RecordingJob.entered = new CountDownLatch(1);
        RecordingJob.release = new CountDownLatch(1);
        RecordingJob.done = new CountDownLatch(3);
        try {
            JobConfig early = config("EARLY", 4), normal = config("NORMAL", 6);
            ZonedDateTime target = ZonedDateTime.now(ZoneId.of("Asia/Hong_Kong")).plusSeconds(5).withNano(0);
            early.setStartTime(target.plusDays(1).toLocalDateTime());
            normal.setStartTime(target.toLocalDateTime());
            normal.setEndTime(target.plusSeconds(1).toLocalDateTime());
            normal.setCronExpression(target.getSecond()+" "+target.getMinute()+" "+target.getHour()+" "+target.getDayOfMonth()+" "+target.getMonthValue()+" ? "+target.getYear());
            startup(scheduler, early, normal).run();
            assertThat(scheduler.isStarted()).isTrue();
            scheduler.triggerJob(JobKey.jobKey("EARLY", "SCHEDULE"));
            assertThat(RecordingJob.entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(Math.max(1, Duration.between(Instant.now(), target.toInstant().plusMillis(300)).toMillis()));
            scheduler.addJob(JobBuilder.newJob(RecordingJob.class).withIdentity("MANUAL").storeDurably().build(), true);
            scheduler.triggerJob(JobKey.jobKey("MANUAL"));
            assertThat(RecordingJob.order).containsExactly("EARLY");
            RecordingJob.release.countDown();
            assertThat(RecordingJob.done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(RecordingJob.order).containsExactly("EARLY", "NORMAL", "MANUAL");
        } finally { RecordingJob.release.countDown(); scheduler.shutdown(true); }
    }

    @Test
    void futureAndExpiredSchedulesKeepTheirValidityWindows() throws Exception {
        Scheduler scheduler = scheduler();
        try {
            JobConfig future = config("FUTURE", 4), expired = config("EXPIRED", 6);
            future.setStartTime(LocalDate.now(ZoneId.of("Asia/Hong_Kong")).plusDays(1).atStartOfDay());
            expired.setEndTime(expired.getStartTime().plusHours(23));
            startup(scheduler, future, expired).registerDatabaseTasks();
            assertThat(scheduler.getTriggersOfJob(JobKey.jobKey("FUTURE", "SCHEDULE"))).hasSize(1).allMatch(t -> t instanceof CronTrigger);
            assertThat(scheduler.getTriggersOfJob(JobKey.jobKey("EXPIRED", "SCHEDULE"))).isEmpty();
        } finally { scheduler.shutdown(true); }
    }

    @Test
    void missedLastCronLeavesOnlyTheManualJob() throws Exception {
        Scheduler scheduler = scheduler();
        try {
            LocalDateTime missed = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")).minusHours(2);
            JobConfig last = config("LAST", missed.getHour());
            last.setEndTime(missed.plusHours(3));
            seedOldCron(scheduler, last);
            startup(scheduler, last).registerDatabaseTasks();
            assertThat(scheduler.getTriggersOfJob(JobKey.jobKey("LAST", "SCHEDULE"))).isEmpty();
            assertThat(scheduler.checkExists(JobKey.jobKey("LAST", "SCHEDULE"))).isTrue();
        } finally { scheduler.shutdown(true); }
    }

    private QuartzConfig startup(Scheduler scheduler, JobConfig... configs) {
        JobService jobs = mock(JobService.class);
        when(jobs.findByActiveSchedule()).thenReturn(configs);
        ScheduleJobListener listener = mock(ScheduleJobListener.class);
        when(listener.getName()).thenReturn("test-listener");
        QuartzConfig config = new QuartzConfig(scheduler, jobs, listener, new JobScheduleReconciler(scheduler));
        // 直接建構時沒有 Spring 的 @Value 注入。
        ReflectionTestUtils.setField(config, "schedulesEnabled", true);
        return config;
    }

    private Scheduler scheduler() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "startup-test-" + UUID.randomUUID());
        properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        properties.setProperty("org.quartz.threadPool.threadCount", "1");
        properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        properties.setProperty("org.quartz.jobStore.misfireThreshold", "100");
        return new StdSchedulerFactory(properties).getScheduler();
    }

    private TriggerKey key(String name) { return TriggerKey.triggerKey(name + "-trigger", "SCHEDULE-triggers"); }
    private void seedOldCron(Scheduler scheduler, JobConfig config) throws Exception {
        JobDetail detail = JobBuilder.newJob(RecordingJob.class).withIdentity(config.getTaskCode(), "SCHEDULE").storeDurably().build();
        scheduler.scheduleJob(detail, TriggerBuilder.newTrigger().withIdentity(key(config.getTaskCode())).forJob(detail)
                .startAt(Date.from(config.getStartTime().atZone(ZoneId.of("Asia/Hong_Kong")).toInstant()))
                .withSchedule(CronScheduleBuilder.cronSchedule(config.getCronExpression()).inTimeZone(TimeZone.getTimeZone("Asia/Hong_Kong"))
                        .withMisfireHandlingInstructionFireAndProceed()).build());
    }
    private JobConfig config(String code, int hour) {
        JobConfig config = new JobConfig();
        config.setTaskCode(code);
        config.setTaskGroup(TaskGroup.SCHEDULE);
        config.setActiveStatus(ActiveStatus.ACTIVE);
        config.setJobClassName(RecordingJob.class.getName());
        config.setCronExpression("0 0 " + hour + " * * ?");
        config.setStartTime(LocalDate.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1).atStartOfDay());
        return config;
    }

    public static class RecordingJob implements Job {
        static final List<String> order = new CopyOnWriteArrayList<>();
        static CountDownLatch entered, release, done;
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                String name = context.getJobDetail().getKey().getName();
                order.add(name);
                if (name.equals("EARLY")) { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new JobExecutionException("test release timed out"); }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new JobExecutionException(error); }
            finally { done.countDown(); }
        }
    }
}
