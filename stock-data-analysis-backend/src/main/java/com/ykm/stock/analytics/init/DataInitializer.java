package com.ykm.stock.analytics.init;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.model.entity.Menu;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MenuRepository;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.scheduling.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Order(10)
public class DataInitializer implements CommandLineRunner {
    private final MenuRepository menuRepository;
    private final JobConfigRepository jobConfigRepository;
    private final AdrShareRatioRepository adrShareRatioRepository;
    private final DateTimeService dateTimeService;
    private final JdbcTemplate jdbcTemplate;
    private final String ddlAuto;

    public DataInitializer(MenuRepository menuRepository, JobConfigRepository jobConfigRepository) {
        this(menuRepository, jobConfigRepository, (AdrShareRatioRepository) null, null, null, "none");
    }

    public DataInitializer(MenuRepository menuRepository, JobConfigRepository jobConfigRepository,
                           AdrShareRatioRepository adrShareRatioRepository, DateTimeService dateTimeService) {
        this(menuRepository, jobConfigRepository, adrShareRatioRepository, dateTimeService, null, "none");
    }

    public DataInitializer(MenuRepository menuRepository, JobConfigRepository jobConfigRepository,
                           AdrShareRatioRepository adrShareRatioRepository, DateTimeService dateTimeService,
                           JdbcTemplate jdbcTemplate, String ddlAuto) {
        this.menuRepository = menuRepository;
        this.jobConfigRepository = jobConfigRepository;
        this.adrShareRatioRepository = adrShareRatioRepository;
        this.dateTimeService = dateTimeService;
        this.jdbcTemplate = jdbcTemplate;
        this.ddlAuto = ddlAuto == null ? "none" : ddlAuto.trim().toLowerCase(Locale.ROOT);
    }

    @Autowired
    public DataInitializer(MenuRepository menuRepository, JobConfigRepository jobConfigRepository,
                           ObjectProvider<AdrShareRatioRepository> adrShareRatioProvider,
                           ObjectProvider<DateTimeService> dateTimeServiceProvider,
                           ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
                           @Value("${spring.jpa.hibernate.ddl-auto:none}") String ddlAuto) {
        this(menuRepository, jobConfigRepository,
                adrShareRatioProvider == null ? null : adrShareRatioProvider.getIfAvailable(),
                dateTimeServiceProvider == null ? null : dateTimeServiceProvider.getIfAvailable(),
                jdbcTemplateProvider == null ? null : jdbcTemplateProvider.getIfAvailable(),
                ddlAuto);
    }

    @Override
    public void run(String... args) {
        dropStaleValuationReasonCheckConstraints();
        initializeMenuData();
        initializeJobConfigData();
        initializeVerifiedAdrRatios();
    }

    /**
     * Hibernate ddl-auto=update 不會自動放寬舊版 @Enumerated(EnumType.STRING) 產生的 PostgreSQL CHECK 約束，
     * 導致新增 ValuationReason 枚舉常數寫入既有表時觸發 23514 違規；
     * 僅針對兩張估值表上「單欄 reason 枚舉陣列 CHECK 且缺少最新枚舉值」之過期約束進行精準清理，不觸碰當版約束與任何業務 @Check。
     */
    private void dropStaleValuationReasonCheckConstraints() {
        if (jdbcTemplate == null || !("update".equals(ddlAuto) || "create".equals(ddlAuto) || "create-drop".equals(ddlAuto))) {
            return;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select c.conrelid::regclass::text as tbl, c.conname
                from pg_constraint c
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
                where c.contype = 'c'
                  and array_length(c.conkey, 1) = 1
                  and c.conrelid::regclass::text in ('stock_financial_period', 'stock_valuation_latest')
                  and (a.attname like '%\\_reason' escape '\\' or a.attname = 'reason_code')
                  and pg_get_constraintdef(c.oid) like '%= ANY (ARRAY[%'
                  and pg_get_constraintdef(c.oid) not like '%ADR_RATIO_UNVERIFIED%'
                """);
        for (Map<String, Object> row : rows) {
            String tbl = String.valueOf(row.get("tbl"));
            String con = String.valueOf(row.get("conname"));
            if (tbl.matches("[a-z0-9_]+") && con.matches("[a-z0-9_]+")) {
                jdbcTemplate.execute("ALTER TABLE " + tbl + " DROP CONSTRAINT IF EXISTS " + con);
            }
        }
    }

    private void initializeMenuData() {
        createMenu(1, "User", "account_circle", "/user", null);
        createMenu(2, "Stock Analysis", "analytics", "/stockAnalysis", null);
        createMenu(3, "Data Management", "storage", "/dataManagement", null);
        createMenu(4, "Job Configuration", "jobConfig", "/jobConfig", 3);
        createMenu(5, "股票篩選", "filter_list", "/stockAnalysis", 2);
        createMenu(6, "Data Upload", "upload_file", "/upload", 3);
        createMenu(7, "Industry Valuation", "tune", "/maintenance/industry-valuation", 3);
        createMenu(8, "Buy Ranking", "leaderboard", "/reports/buy-ranking", 2);
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
        // 香港時間先準備主檔、價格基準、匯率、信用利差及行情，再同步補充資料與計算估值。
        initializeScheduledJob(TaskCode.TREASURY_DAILY_SYNC, TreasuryDailySyncJob.class, "0 0 5 * * ?");
        initializeScheduledJob(TaskCode.FX_RATE_DAILY_SYNC, FxRateDailySyncJob.class, "0 5 5 * * ?");
        initializeScheduledJob(TaskCode.CREDIT_SPREAD_DAILY_SYNC, CreditSpreadDailySyncJob.class, "0 8 5 * * ?");
        initializeScheduledJob(TaskCode.STOCK_DAILY, StockDailyTask.class, "0 15 5 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.STOCK_SPLIT_DAILY, StockSplitDailyTask.class, "0 45 5 * * ?");
        initializeScheduledJob(TaskCode.STOCK_QUOTE_DAILY, StockQuoteDailyTask.class, "0 15 6 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.FINANCIAL_REPORT_DAILY_SYNC, FinancialReportDailySyncJob.class, "0 30 6 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.STOCK_CLASSIFICATION_DAILY_SYNC, StockClassificationDailySyncJob.class, "0 0 7 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.SHORT_INTEREST_SYNC, ShortInterestSyncJob.class, "0 20 8 ? * TUE,FRI");
        initializeScheduledJob(TaskCode.MARKET_SENTIMENT_DAILY, MarketSentimentDailyTask.class, "0 30 8 * * ?");
        initializeScheduledJob(TaskCode.MARKET_EVENT_DAILY_SYNC, MarketEventDailySyncJob.class, "0 40 8 * * ?");
        initializeScheduledJob(TaskCode.EARNINGS_FORECAST_DAILY_SYNC, EarningsForecastDailySyncJob.class, "0 50 8 * * ?");
        initializeScheduledJob(TaskCode.STOCK_VALUATION_DAILY, StockValuationDailyTask.class, "0 0 10 * * ?");
        initializeScheduledJob(TaskCode.CHIP_EVENT_DAILY_SYNC, ChipEventDailySyncJob.class, "0 25 10 ? * TUE-SAT");
        initializeScheduledJob(TaskCode.INSTITUTIONAL_13F_SYNC, Institutional13fSyncJob.class, "0 0 9 ? * SUN");
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

    /** 冪等寫入已核實主流龍頭 ADR 換股比例（不覆寫既有人工調整紀錄；未列示之冷門 ADR 維持 ADR_RATIO_UNVERIFIED 攔截）。 */
    void initializeVerifiedAdrRatios() {
        if (adrShareRatioRepository == null || dateTimeService == null) {
            return;
        }
        Instant now = dateTimeService.now();
        List<AdrShareRatio> seeds = List.of(
                new AdrShareRatio("TSM", "2330.TW", new BigDecimal("5.0000"), "TWD", true, "TSMC 20-F: 1 ADS = 5 common shares", now),
                new AdrShareRatio("NVO", "NOVO-B.CO", new BigDecimal("1.0000"), "DKK", true, "Novo Nordisk 20-F: 1 ADR = 1 B share", now),
                new AdrShareRatio("ARM", null, new BigDecimal("1.0000"), "USD", true, "Arm Holdings 20-F: 1 ADS = 1 ordinary share", now),
                new AdrShareRatio("BABA", "9988.HK", new BigDecimal("8.0000"), "CNY", true, "Alibaba 20-F: 1 ADS = 8 ordinary shares", now),
                new AdrShareRatio("ASML", "ASML.AS", new BigDecimal("1.0000"), "EUR", true, "ASML 20-F: 1 NY Registry Share = 1 ordinary share", now),
                new AdrShareRatio("SAP", "SAP.DE", new BigDecimal("1.0000"), "EUR", true, "SAP 20-F: 1 ADS = 1 ordinary share", now),
                new AdrShareRatio("SONY", "6758.T", new BigDecimal("1.0000"), "JPY", true, "Sony 20-F: 1 ADS = 1 common share (post 2024 5:1 split)", now),
                new AdrShareRatio("TM", "7203.T", new BigDecimal("10.0000"), "JPY", true, "Toyota 20-F: 1 ADS = 10 common shares", now),
                new AdrShareRatio("PDD", null, new BigDecimal("4.0000"), "CNY", true, "PDD Holdings 20-F: 1 ADS = 4 Class A ordinary shares", now),
                new AdrShareRatio("JD", "9618.HK", new BigDecimal("2.0000"), "CNY", true, "JD.com 20-F: 1 ADS = 2 Class A ordinary shares", now),
                new AdrShareRatio("BIDU", "9888.HK", new BigDecimal("8.0000"), "CNY", true, "Baidu 20-F: 1 ADS = 8 Class A ordinary shares", now),
                new AdrShareRatio("SE", null, new BigDecimal("1.0000"), "USD", true, "Sea Limited 20-F: 1 ADS = 1 Class A ordinary share", now),
                new AdrShareRatio("SNY", "SAN.PA", new BigDecimal("0.5000"), "EUR", true, "Sanofi 20-F: 2 ADS = 1 ordinary share (1 ADS = 0.5 share)", now),
                new AdrShareRatio("UL", "ULVR.L", new BigDecimal("1.0000"), "EUR", true, "Unilever 20-F: 1 ADS = 1 ordinary share", now),
                new AdrShareRatio("INFY", "INFY.NS", new BigDecimal("1.0000"), "USD", true, "Infosys 20-F: 1 ADS = 1 equity share", now),
                new AdrShareRatio("HDB", "HDFCBANK.NS", new BigDecimal("3.0000"), "INR", true, "HDFC Bank 20-F: 1 ADS = 3 equity shares", now),
                new AdrShareRatio("IBN", "ICICIBANK.NS", new BigDecimal("2.0000"), "INR", true, "ICICI Bank 20-F: 1 ADS = 2 equity shares", now),
                new AdrShareRatio("VALE", "VALE3.SA", new BigDecimal("1.0000"), "USD", true, "Vale 20-F: 1 ADS = 1 common share", now),
                new AdrShareRatio("ITUB", "ITUB4.SA", new BigDecimal("1.0000"), "BRL", true, "Itau Unibanco 20-F: 1 ADS = 1 preferred share", now)
        );
        for (AdrShareRatio seed : seeds) {
            if (!adrShareRatioRepository.existsById(seed.getSymbol())) {
                adrShareRatioRepository.save(seed);
            }
        }
    }
}
