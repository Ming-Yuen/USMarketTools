package com.ykm.stock.analytics.init;

import com.ykm.stock.analytics.config.QuartzConfig;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.model.entity.Menu;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MenuRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.quartz.Scheduler;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
            "TREASURY_DAILY_SYNC", "FX_RATE_DAILY_SYNC", "STOCK_DAILY", "STOCK_SPLIT_DAILY", "STOCK_QUOTE_DAILY", "FINANCIAL_REPORT_DAILY_SYNC",
            "STOCK_CLASSIFICATION_DAILY_SYNC", "MARKET_SENTIMENT_DAILY",
            "MARKET_EVENT_DAILY_SYNC", "EARNINGS_FORECAST_DAILY_SYNC", "STOCK_VALUATION_DAILY", "SEC_FINANCIAL_FACTS_DAILY_SYNC");

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

    @ParameterizedTest
    @CsvSource({
            "FUTU_FINANCIAL_REPORT_DAILY_SYNC, FINANCIAL_REPORT_DAILY_SYNC, FinancialReportDailySyncJob",
            "FUTU_CLASSIFICATION_DAILY_SYNC, STOCK_CLASSIFICATION_DAILY_SYNC, StockClassificationDailySyncJob",
            "FUTU_EARNINGS_DAILY_SYNC, EARNINGS_FORECAST_DAILY_SYNC, EarningsForecastDailySyncJob",
            "SEC_SHARES_DAILY_SYNC, SEC_FINANCIAL_FACTS_DAILY_SYNC, SecFinancialFactsDailySyncJob"
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
        verify(menus, times(6)).saveAndFlush(saved.capture());
        Menu screen = saved.getAllValues().stream().filter(menu -> menu.getMenuId() == 5).findFirst().orElseThrow();
        assertThat(screen.getName()).isEqualTo("股票篩選");
        assertThat(screen.getPath()).isEqualTo("/stockAnalysis");
        assertThat(screen.getIcon()).isEqualTo("filter_list");
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
}
