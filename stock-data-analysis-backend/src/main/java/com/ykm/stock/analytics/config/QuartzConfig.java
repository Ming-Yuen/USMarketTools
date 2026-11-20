package com.ykm.stock.analytics.config;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.listener.ScheduleJobListener;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Slf4j
@Configuration
@Order(20)
@RequiredArgsConstructor
public class QuartzConfig implements CommandLineRunner {
    @Value("${app.schedules.enabled:true}")
    private boolean schedulesEnabled;

    private final Scheduler scheduler;
    private final JobService jobService;
    private final ScheduleJobListener scheduleJobListener;
    private final JobScheduleReconciler jobScheduleReconciler;

    /** DataInitializer 完成資料初始化後，才註冊排程及啟動 Quartz。 */
    @Override
    public void run(String... args) throws SchedulerException {
        // 升級既有資料庫時先退役已合併或淘汰的舊排程，避免 reconcile 嘗試載入已移除的 Job 類別。
        for (String retiredCode : TaskCode.RETIRED_SCHEDULE_CODES) {
            jobService.retireSchedule(retiredCode);
        }
        if (!schedulesEnabled) {
            log.info("Quartz disabled for controlled maintenance or API verification");
            return;
        }
        registerDatabaseTasks();
        scheduler.start();
    }

    public void registerDatabaseTasks() {
        try {
            scheduler.getListenerManager().addJobListener(scheduleJobListener);
            // 舊版本留下的啟動補跑，在 Scheduler 啟動前一併移除。
            List<TriggerKey> pendingCatchups = List.copyOf(scheduler.getTriggerKeys(GroupMatcher.triggerGroupEquals(TaskGroup.SCHEDULE + "-catchups")));
            if (!pendingCatchups.isEmpty()) scheduler.unscheduleJobs(pendingCatchups);
            JobConfig[] tasks = jobService.findByActiveSchedule();
            Date now = new Date();
            log.info("Loading {} enabled tasks from database", tasks.length);
            for (JobConfig task : tasks) {
                if (TaskCode.PREVIOUS_SCHEDULE_CODES.containsValue(task.getTaskCode())) continue;
                jobScheduleReconciler.reconcile(task, now);
            }
            for (String oldCode : TaskCode.PREVIOUS_SCHEDULE_CODES.values()) jobService.retireSchedule(oldCode);
            log.info("Successfully reconciled all database tasks");
        } catch (Exception e) {
            log.error("Failed to reconcile database tasks", e);
            throw new RuntimeException("Quartz initialization failed", e);
        }
    }

}
