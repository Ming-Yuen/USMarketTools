package com.ykm.stock.analytics.init;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.model.entity.Menu;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MenuRepository;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.scheduling.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Component
@Order(10)
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final MenuRepository menuRepository;
    private final JobConfigRepository jobConfigRepository;

    @Override
    public void run(String... args) {
        initializeMenuData();
        initializeJobConfigData();
    }

    private void initializeMenuData() {
        createMenu(1, "User", "account_circle", "/user", null);
        createMenu(2, "Stock Analysis", "analytics", "/stockAnalysis", null);
        createMenu(3, "Data Management", "storage", "/dataManagement", null);
        createMenu(4, "Job Configuration", "jobConfig", "/jobConfig", 3);
        createMenu(5, "股票篩選", "filter_list", "/stockAnalysis", 2);
        createMenu(6, "Data Upload", "upload_file", "/upload", 3);
    }

    private void createMenu(Integer menuId, String name, String icon, String path, Integer parentId) {
        if (menuRepository.existsById(menuId)) {
            return;
        }

        Menu menu = new Menu();
        menu.setMenuId(menuId);
        menu.setName(name);
        menu.setIcon(icon);
        menu.setPath(path);
        menu.setParentId(parentId);
        menuRepository.saveAndFlush(menu);
    }

    void initializeJobConfigData() {
        // 香港時間先準備主檔、價格基準及行情，再同步補充資料與計算估值。
        initializeScheduledJob(TaskCode.TREASURY_DAILY_SYNC, TreasuryDailySyncJob.class, "0 0 5 * * ?");
        initializeScheduledJob(TaskCode.STOCK_DAILY, StockDailyTask.class, "0 15 5 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.STOCK_SPLIT_DAILY, StockSplitDailyTask.class, "0 45 5 * * ?");
        initializeScheduledJob(TaskCode.STOCK_QUOTE_DAILY, StockQuoteDailyTask.class, "0 15 6 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.FINANCIAL_REPORT_DAILY_SYNC, FinancialReportDailySyncJob.class, "0 30 6 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.STOCK_CLASSIFICATION_DAILY_SYNC, StockClassificationDailySyncJob.class, "0 0 7 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.MARKET_SENTIMENT_DAILY, MarketSentimentDailyTask.class, "0 30 8 * * ?");
        initializeScheduledJob(TaskCode.MARKET_EVENT_DAILY_SYNC, MarketEventDailySyncJob.class, "0 40 8 * * ?");
        initializeScheduledJob(TaskCode.EARNINGS_FORECAST_DAILY_SYNC, EarningsForecastDailySyncJob.class, "0 50 8 * * ?");
        initializeScheduledJob(TaskCode.STOCK_VALUATION_DAILY, StockValuationDailyTask.class, "0 0 10 * * ?");
        // 香港 08:00 在行情及富途財報之後、10:00 估值之前準備 SEC 資料。
        initializeScheduledJob(TaskCode.SEC_FINANCIAL_FACTS_DAILY_SYNC, SecFinancialFactsDailySyncJob.class, "0 0 8 * * ?");
    }

    private void initializeScheduledJob(String taskCode, Class<?> jobClass, String cronExpression) {
        initializeScheduledJob(taskCode, jobClass, cronExpression, null);
    }

    private void initializeScheduledJob(String taskCode, Class<?> jobClass, String cronExpression, Map<String, Object> config) {
        JobConfig existing = jobConfigRepository.findByTaskCode(taskCode);
        // 沿用舊排程的設定，只更新識別代碼及實作類別。
        String previousCode = TaskCode.PREVIOUS_SCHEDULE_CODES.get(taskCode);
        if (existing == null && previousCode != null) {
            existing = jobConfigRepository.findByTaskCode(previousCode);
            if (existing != null) {
                existing.setTaskCode(taskCode);
                existing.setJobClassName(jobClass.getName());
                jobConfigRepository.save(existing);
            }
        }
        if (existing == null) {
            JobConfig jobConfig = new JobConfig();
            jobConfig.setTaskCode(taskCode);
            jobConfig.setTaskGroup(TaskGroup.SCHEDULE);
            jobConfig.setJobClassName(jobClass.getName());
            jobConfig.setCronExpression(cronExpression);
            jobConfig.setTriggerType(JobConfig.TriggerType.CRON);
            jobConfig.setConfig(config);
            jobConfig.setActiveStatus(ActiveStatus.ACTIVE);
            jobConfig.setStartTime(LocalDateTime.now(ZoneId.of(JobScheduleReconciler.SCHEDULE_TIMEZONE)));
            jobConfig.setRetryCount(0);
            jobConfig.setMaxRetryCount(3);
            jobConfigRepository.save(jobConfig);
            return;
        }
        if (config == null || config.isEmpty()) return;
        Map<String, Object> merged = existing.getConfig() == null ? new HashMap<>() : new HashMap<>(existing.getConfig());
        boolean changed = false;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (merged.containsKey(entry.getKey())) continue;
            merged.put(entry.getKey(), entry.getValue());
            changed = true;
        }
        if (changed) {
            existing.setConfig(merged);
            jobConfigRepository.save(existing);
        }
    }
}
