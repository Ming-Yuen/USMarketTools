package com.ykm.stock.analytics.init;

import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.config.QuartzConfig;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.model.entity.Menu;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MenuRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.scheduling.ChipEventDailySyncJob;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.quartz.Scheduler;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataInitializerTests {
    private static final List<String> CURRENT_JOBS = List.of(
            "TREASURY_DAILY_SYNC", "FX_RATE_DAILY_SYNC", "CREDIT_SPREAD_DAILY_SYNC",
            "STOCK_DAILY", "STOCK_QUOTE_DAILY", "STOCK_FUNDAMENTAL_DAILY_SYNC",
            "STOCK_CLASSIFICATION_DAILY_SYNC", "SHORT_INTEREST_SYNC", "MARKET_SENTIMENT_DAILY",
            "MARKET_EVENT_DAILY_SYNC", "EARNINGS_FORECAST_DAILY_SYNC", "STOCK_VALUATION_DAILY",
            "CHIP_EVENT_DAILY_SYNC", "INSTITUTIONAL_13F_SYNC");

    @Test
    void seedsOnlyTheCurrentJobs() {
        JobConfigRepository jobs = mock(JobConfigRepository.class);

        new DataInitializer(mock(MenuRepository.class), jobs).initializeJobConfigData();

        ArgumentCaptor<JobConfig> saved = ArgumentCaptor.forClass(JobConfig.class);
        verify(jobs, times(CURRENT_JOBS.size())).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(JobConfig::getTaskCode).containsExactlyElementsOf(CURRENT_JOBS);
        assertThat(saved.getAllValues()).allSatisfy(job -> {
            assertThat(job.getTaskGroup()).isEqualTo(TaskGroup.SCHEDULE);
            assertThat(job.getTriggerType()).isEqualTo(JobConfig.TriggerType.CRON);
            assertThat(job.getActiveStatus()).isEqualTo(ActiveStatus.ACTIVE);
            assertThat(job.getStartTime()).isNotNull();
            assertThat(job.getConfig()).isNull();
            assertThat(job.getRetryCount()).isZero();
            assertThat(job.getMaxRetryCount()).isEqualTo(3);
            assertThat(job.getJobClassName()).doesNotContain("Proposal", "HistoricalIdentity", "Backfill", "SecEtf");
        });
        ArgumentCaptor<String> lookedUp = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(CURRENT_JOBS.size() + TaskCode.PREVIOUS_SCHEDULE_CODES.size())).findByTaskCode(lookedUp.capture());
        assertThat(lookedUp.getAllValues()).containsAll(TaskCode.PREVIOUS_SCHEDULE_CODES.values());
        assertThat(lookedUp.getAllValues()).filteredOn(code -> !TaskCode.PREVIOUS_SCHEDULE_CODES.containsValue(code))
                .containsExactlyElementsOf(CURRENT_JOBS);
        verify(jobs, never()).delete(any());
        verify(jobs, never()).flush();
    }

    @Test
    void initializationNeedsOnlyRepositoriesAndNoQuartzBeans() {
        MenuRepository menus = mock(MenuRepository.class);
        JobConfigRepository jobs = mock(JobConfigRepository.class);
        new ApplicationContextRunner().withUserConfiguration(DataInitializer.class)
                .withBean(MenuRepository.class, () -> menus).withBean(JobConfigRepository.class, () -> jobs)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DataInitializer.class)
                            .doesNotHaveBean(Scheduler.class).doesNotHaveBean(QuartzConfig.class);
                    context.getBean(DataInitializer.class).run();
                    verify(jobs).findByTaskCode("STOCK_VALUATION_DAILY");
                });
    }

    @Test
    void existingUserSchedulesAreNotOverwrittenOrResaved() {
        JobConfigRepository jobs = mock(JobConfigRepository.class);
        Map<String, JobConfig> existing = new LinkedHashMap<>();
        for (String taskCode : CURRENT_JOBS) {
            JobConfig job = new JobConfig();
            job.setTaskCode(taskCode);
            job.setCronExpression("0 0 12 * * ?");
            job.setJobClassName("configured.Job");
            job.setActiveStatus(ActiveStatus.INACTIVE);
            job.setStartTime(LocalDateTime.of(2020, 1, 1, 12, 0));
            job.setConfig(Map.of("batchSize", 37));
            existing.put(taskCode, job);
        }
        when(jobs.findByTaskCode(anyString())).thenAnswer(invocation -> existing.get(invocation.getArgument(0)));

        new DataInitializer(mock(MenuRepository.class), jobs).initializeJobConfigData();

        assertThat(existing.values()).allSatisfy(job -> {
            assertThat(job.getCronExpression()).isEqualTo("0 0 12 * * ?");
            assertThat(job.getJobClassName()).isEqualTo("configured.Job");
            assertThat(job.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
            assertThat(job.getStartTime()).isEqualTo(LocalDateTime.of(2020, 1, 1, 12, 0));
            assertThat(job.getConfig()).containsExactlyEntriesOf(Map.of("batchSize", 37));
        });
        verify(jobs, never()).save(any());
        verify(jobs, never()).delete(any());
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void upgradesExactPreviousDefaultCronsForTreasuryAndChipEventWithoutTouchingCustomCrons() {
        JobConfigRepository jobs = mock(JobConfigRepository.class);
        JobConfig treasuryOld = new JobConfig();
        treasuryOld.setTaskCode(TaskCode.TREASURY_DAILY_SYNC);
        treasuryOld.setCronExpression("0 0 5 * * ?");

        JobConfig chipOld = new JobConfig();
        chipOld.setTaskCode(TaskCode.CHIP_EVENT_DAILY_SYNC);
        chipOld.setCronExpression("0 25 10 ? * TUE-SAT");

        Map<String, JobConfig> existing = new LinkedHashMap<>();
        for (String taskCode : CURRENT_JOBS) {
            if (TaskCode.TREASURY_DAILY_SYNC.equals(taskCode)) {
                existing.put(taskCode, treasuryOld);
            } else if (TaskCode.CHIP_EVENT_DAILY_SYNC.equals(taskCode)) {
                existing.put(taskCode, chipOld);
            } else {
                JobConfig custom = new JobConfig();
                custom.setTaskCode(taskCode);
                custom.setCronExpression("0 0 12 * * ?");
                existing.put(taskCode, custom);
            }
        }
        when(jobs.findByTaskCode(anyString())).thenAnswer(invocation -> existing.get(invocation.getArgument(0)));

        new DataInitializer(mock(MenuRepository.class), jobs).initializeJobConfigData();

        assertThat(treasuryOld.getCronExpression()).isEqualTo("0 15 7 * * ?");
        assertThat(chipOld.getCronExpression()).isEqualTo("0 0 11 ? * TUE-SAT");
        verify(jobs).save(same(treasuryOld));
        verify(jobs).save(same(chipOld));
        verify(jobs, times(2)).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "FUTU_CLASSIFICATION_DAILY_SYNC, STOCK_CLASSIFICATION_DAILY_SYNC, StockClassificationDailySyncJob",
            "FUTU_EARNINGS_DAILY_SYNC, EARNINGS_FORECAST_DAILY_SYNC, EarningsForecastDailySyncJob"
    })
    void renamesJobsWithoutReplacingExistingSchedules(String oldCode, String newCode, String newClass) {
        JobConfigRepository jobs = mock(JobConfigRepository.class);
        JobConfig existing = new JobConfig();
        existing.setTaskCode(oldCode);
        existing.setJobClassName("com.ykm.stock.analytics.scheduling.LegacyJob");
        existing.setCronExpression("0 0 12 * * ?");
        existing.setActiveStatus(ActiveStatus.INACTIVE);
        existing.setConfig(Map.of("figis", "F1"));
        when(jobs.findByTaskCode(existing.getTaskCode())).thenReturn(existing);

        new DataInitializer(mock(MenuRepository.class), jobs).initializeJobConfigData();

        assertThat(existing.getTaskCode()).isEqualTo(newCode);
        assertThat(existing.getJobClassName()).isEqualTo("com.ykm.stock.analytics.scheduling." + newClass);
        assertThat(existing.getCronExpression()).isEqualTo("0 0 12 * * ?");
        assertThat(existing.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
        assertThat(existing.getConfig()).containsExactlyEntriesOf(Map.of("figis", "F1"));
        verify(jobs).save(same(existing));
    }

    @Test
    void createsStockScreenMenuButDoesNotReplaceExistingMenusOnAnotherRun() {
        MenuRepository menus = mock(MenuRepository.class);
        DataInitializer initializer = new DataInitializer(menus, mock(JobConfigRepository.class));
        initializer.run();
        ArgumentCaptor<Menu> saved = ArgumentCaptor.forClass(Menu.class);
        verify(menus, times(8)).saveAndFlush(saved.capture());
        Menu screen = saved.getAllValues().stream().filter(menu -> menu.getMenuId() == 5).findFirst().orElseThrow();
        assertThat(screen.getName()).isEqualTo("股票篩選");
        assertThat(screen.getPath()).isEqualTo("/stockAnalysis");
        assertThat(screen.getIcon()).isEqualTo("filter_list");
        Menu industryValuation = saved.getAllValues().stream().filter(menu -> menu.getMenuId() == 7).findFirst().orElseThrow();
        assertThat(industryValuation.getParentId()).isEqualTo(3);
        assertThat(industryValuation.getName()).isEqualTo("Industry Valuation");
        assertThat(industryValuation.getName().length()).isLessThanOrEqualTo(32);
        assertThat(industryValuation.getIcon()).isEqualTo("tune");
        assertThat(industryValuation.getIcon().length()).isLessThanOrEqualTo(16);
        assertThat(industryValuation.getPath()).isEqualTo("/maintenance/industry-valuation");
        Menu buyRanking = saved.getAllValues().stream().filter(menu -> menu.getMenuId() == 8).findFirst().orElseThrow();
        assertThat(buyRanking.getParentId()).isEqualTo(2);
        assertThat(buyRanking.getName()).isEqualTo("Buy Ranking");
        assertThat(buyRanking.getName().length()).isLessThanOrEqualTo(32);
        assertThat(buyRanking.getIcon()).isEqualTo("leaderboard");
        assertThat(buyRanking.getIcon().length()).isLessThanOrEqualTo(16);
        assertThat(buyRanking.getPath()).isEqualTo("/reports/buy-ranking");
        assertThat(saved.getAllValues()).extracting(Menu::getPath).doesNotContain("/watchlist");

        clearInvocations(menus);
        when(menus.existsById(anyInt())).thenReturn(true);
        initializer.run();
        verify(menus, never()).saveAndFlush(any());
    }

    @Test
    void failedSeedPropagatesTheDatabaseErrorWithoutStartingJobInitialization() {
        MenuRepository menus = mock(MenuRepository.class);
        JobConfigRepository jobs = mock(JobConfigRepository.class);
        when(menus.existsById(anyInt())).thenThrow(new IllegalStateException("seed failed"));

        assertThatThrownBy(() -> new DataInitializer(menus, jobs).run())
                .isInstanceOf(IllegalStateException.class).hasMessage("seed failed");
        verifyNoInteractions(jobs);
    }

    @Test
    void seedsStartDatesIdempotentlyAndMigratesLegacySysConfigWatermarks() {
        JobConfigRepository jobs = mock(JobConfigRepository.class);
        PortfolioSysConfigRepository sysConfigs = mock(PortfolioSysConfigRepository.class);

        SysConfig existingHistStart = new SysConfig();
        existingHistStart.setConfigKey(PortfolioSysConfigRepository.KEY_SYNC_HISTORY_START_DATE);
        existingHistStart.setConfigValue("2018-01-01");
        existingHistStart.setEnabled(true);

        SysConfig legacyChip = new SysConfig();
        legacyChip.setConfigKey(ChipEventDailySyncJob.CONFIG_KEY_SYNCED_TRADE_DATE);
        legacyChip.setConfigValue("2026-11-20");
        legacyChip.setEnabled(true);

        SysConfig legacyShort = new SysConfig();
        legacyShort.setConfigKey(ShortInterestService.CONFIG_KEY_MARKET_SYNCED_DATE);
        legacyShort.setConfigValue("2026-10-30");
        legacyShort.setEnabled(true);

        when(sysConfigs.findByConfigKeyInOrderByIdAsc(List.of(PortfolioSysConfigRepository.KEY_SYNC_HISTORY_START_DATE)))
                .thenReturn(List.of(existingHistStart));
        when(sysConfigs.findByConfigKeyInOrderByIdAsc(List.of(PortfolioSysConfigRepository.KEY_FX_SYNC_START_DATE)))
                .thenReturn(List.of());
        when(sysConfigs.findByConfigKeyInOrderByIdAsc(List.of(ChipEventDailySyncJob.CONFIG_KEY_SYNCED_TRADE_DATE)))
                .thenReturn(List.of(legacyChip));
        when(sysConfigs.findByConfigKeyInOrderByIdAsc(List.of(ShortInterestService.CONFIG_KEY_MARKET_SYNCED_DATE)))
                .thenReturn(List.of(legacyShort));

        JobConfig chipJob = new JobConfig();
        chipJob.setTaskCode(TaskCode.CHIP_EVENT_DAILY_SYNC);
        JobConfig shortJob = new JobConfig();
        shortJob.setTaskCode(TaskCode.SHORT_INTEREST_SYNC);
        shortJob.setWatermarkDate(LocalDate.of(2026, 11, 15));
        when(jobs.findByTaskCode(TaskCode.CHIP_EVENT_DAILY_SYNC)).thenReturn(chipJob);
        when(jobs.findByTaskCode(TaskCode.SHORT_INTEREST_SYNC)).thenReturn(shortJob);

        DataInitializer initializer = new DataInitializer(mock(MenuRepository.class), jobs, null, sysConfigs, null, null, "none");
        initializer.initializeSyncSysConfigData();

        // User's customized sync_history_start_date is untouched; only missing fx_sync_start_date is seeded
        ArgumentCaptor<SysConfig> savedSysConfig = ArgumentCaptor.forClass(SysConfig.class);
        verify(sysConfigs, times(1)).save(savedSysConfig.capture());
        assertThat(savedSysConfig.getValue().getConfigKey()).isEqualTo(PortfolioSysConfigRepository.KEY_FX_SYNC_START_DATE);
        assertThat(savedSysConfig.getValue().getConfigValue()).isEqualTo("2014-01-01");
        assertThat(existingHistStart.getConfigValue()).isEqualTo("2018-01-01");

        // CHIP had null watermark -> migrated from sys_config; SHORT already had watermark -> not overwritten; both legacy keys deleted
        verify(jobs).updateWatermarkDate(TaskCode.CHIP_EVENT_DAILY_SYNC, LocalDate.of(2026, 11, 20));
        verify(jobs, never()).updateWatermarkDate(eq(TaskCode.SHORT_INTEREST_SYNC), any());
        verify(sysConfigs).deleteAll(List.of(legacyChip));
        verify(sysConfigs).deleteAll(List.of(legacyShort));
    }
}
