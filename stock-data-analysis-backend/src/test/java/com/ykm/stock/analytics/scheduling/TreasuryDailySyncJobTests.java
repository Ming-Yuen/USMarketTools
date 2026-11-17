package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.client.TreasuryDirectClient;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.service.market.TreasuryService;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TreasuryDailySyncJobTests {
    // Scenario：來源有 10/16 與 10/17 兩筆，完整保存 feed 的所有觀測日期。
    @Test
    void savesDatedFeed() throws Exception {
        TreasuryDirectClient client = mock(TreasuryDirectClient.class);
        TreasuryService service = mock(TreasuryService.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        TreasuryYieldCurveFeed feed = feed(
                LocalDateTime.of(2026, 10, 16, 0, 0),
                LocalDateTime.of(2026, 10, 17, 0, 0));
        when(client.getCurrentMonthYieldCurve()).thenReturn(feed);

        new TreasuryDailySyncJob(client, service).execute(context);

        verify(service).saveOrUpdateTreasuryParYieldDailies(feed);
    }

    // Scenario：XML 缺少 NEW_DATE 時不落庫，等待下一輪重新取得有效來源。
    @Test
    void undatedFeedDoesNotSave() throws Exception {
        TreasuryDirectClient client = mock(TreasuryDirectClient.class);
        TreasuryService service = mock(TreasuryService.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(client.getCurrentMonthYieldCurve()).thenReturn(feed((LocalDateTime) null));

        new TreasuryDailySyncJob(client, service).execute(context);

        verify(service, never()).saveOrUpdateTreasuryParYieldDailies(org.mockito.ArgumentMatchers.any());
    }

    // Scenario：Treasury 下載或寫庫失敗，必須拋出例外，不能偽造成功的資料。
    @Test
    void fetchOrSaveFailurePropagatesWithoutPersistingInvalidData() {
        TreasuryDirectClient client = mock(TreasuryDirectClient.class);
        TreasuryService service = mock(TreasuryService.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(client.getCurrentMonthYieldCurve()).thenThrow(new IllegalStateException("treasury down"));

        JobExecutionException fetchFailure = assertThrows(JobExecutionException.class,
                () -> new TreasuryDailySyncJob(client, service).execute(context));
        assertThat(fetchFailure.refireImmediately()).isFalse();
        verify(service, never()).saveOrUpdateTreasuryParYieldDailies(org.mockito.ArgumentMatchers.any());

        TreasuryYieldCurveFeed feed = feed(LocalDateTime.of(2026, 10, 17, 0, 0));
        org.mockito.Mockito.doReturn(feed).when(client).getCurrentMonthYieldCurve();
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(service).saveOrUpdateTreasuryParYieldDailies(feed);
        JobExecutionContext saveContext = mock(JobExecutionContext.class);
        assertThrows(JobExecutionException.class, () -> new TreasuryDailySyncJob(client, service).execute(saveContext));
    }

    private TreasuryYieldCurveFeed feed(LocalDateTime... dates) {
        TreasuryYieldCurveFeed feed = new TreasuryYieldCurveFeed();
        List<TreasuryYieldCurveFeed.Entry> entries = new ArrayList<>();
        for (LocalDateTime date : dates) {
            TreasuryYieldCurveFeed.Entry entry = new TreasuryYieldCurveFeed.Entry();
            if (date != null) {
                TreasuryYieldCurveFeed.Properties properties = new TreasuryYieldCurveFeed.Properties();
                properties.setDate(date);
                TreasuryYieldCurveFeed.Content content = new TreasuryYieldCurveFeed.Content();
                content.setProperties(properties);
                entry.setContent(content);
            }
            entries.add(entry);
        }
        feed.setEntries(entries);
        return feed;
    }
}
