package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.converter.jpa.ScheduleConverter;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.dto.stockInsight.JobTask;
import com.ykm.stock.analytics.model.dto.stockInsight.request.EnquiryBatchJobRequest;
import com.ykm.stock.analytics.model.entity.JobConfig;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JobServiceLifecycleTests {
    /** 排程管理只回傳目前設定，不再查詢每次執行的歷史結果。 */
    @Test
    void enquiryOnlyReadsScheduleConfiguration() {
        JobConfigRepository configs = mock(JobConfigRepository.class);
        ScheduleConverter converter = mock(ScheduleConverter.class);
        JobService service = new JobService(mock(Scheduler.class), configs, converter, mock(JobScheduleReconciler.class));
        JobConfig stock = new JobConfig();
        stock.setTaskCode("STOCK_DAILY");
        stock.setTaskGroup(TaskGroup.SCHEDULE);
        JobTask task = new JobTask();
        task.setTaskCode(stock.getTaskCode());
        when(configs.findAll()).thenReturn(List.of(stock));
        when(converter.convertJobTask(stock)).thenReturn(task);

        List<JobTask> rows = service.enquiryJobTask(new EnquiryBatchJobRequest()).getJobTaskList();

        assertThat(rows).containsExactly(task);
        verify(configs).findAll();
        verify(converter).convertJobTask(stock);
    }
}
