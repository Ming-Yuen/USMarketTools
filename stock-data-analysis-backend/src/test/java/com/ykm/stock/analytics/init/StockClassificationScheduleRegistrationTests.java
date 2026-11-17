package com.ykm.stock.analytics.init;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MenuRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class StockClassificationScheduleRegistrationTests {
    // 新系統只註冊來源同步；AI 提議由使用者要求，不以啟動清理或 durable job 維護。
    @Test
    void registersFutuSourceJobsWithoutRecreatingRetiredClassificationOrAiProposalTasks() {
        JobConfigRepository repository = mock(JobConfigRepository.class);
        new DataInitializer(mock(MenuRepository.class), repository).initializeJobConfigData();

        verify(repository).save(argThat(config -> TaskCode.STOCK_CLASSIFICATION_DAILY_SYNC.equals(config.getTaskCode())
                && "0 0 7 ? * TUE-SAT".equals(config.getCronExpression())));
        verify(repository).save(argThat(config -> TaskCode.EARNINGS_FORECAST_DAILY_SYNC.equals(config.getTaskCode())
                && "0 50 8 * * ?".equals(config.getCronExpression())));
        verify(repository).save(argThat(config -> TaskCode.FINANCIAL_REPORT_DAILY_SYNC.equals(config.getTaskCode())
                && "0 30 6 ? * TUE-SAT".equals(config.getCronExpression())));
        verify(repository).save(argThat(config -> TaskCode.STOCK_VALUATION_DAILY.equals(config.getTaskCode())
                && "0 0 10 * * ?".equals(config.getCronExpression())));
        verify(repository, never()).findByTaskCode("FUTU_VALUATION_DAILY_SYNC");
        verify(repository, never()).findByTaskCode("SEC_FINANCIAL_FACT_DAILY");
        verify(repository, never()).findByTaskCode("STOCK_INDUSTRY_CLASSIFICATION_DAILY");
        verify(repository, never()).findByTaskCode("INDUSTRY_VALUATION_PROPOSAL");
        verify(repository, never()).save(argThat(config -> "STOCK_INDUSTRY_CLASSIFICATION_DAILY".equals(config.getTaskCode())
                || config.getTaskCode().contains("PROPOSAL")));
        verify(repository, never()).delete(any(JobConfig.class));
    }
}
