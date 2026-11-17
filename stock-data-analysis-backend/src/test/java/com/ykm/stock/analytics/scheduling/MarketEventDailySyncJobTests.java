package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.service.market.MarketEventSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.quartz.JobExecutionContext;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketEventDailySyncJobTests {
    // Scenario：三個來源仍共用紐約日期窗口；不再建立 fire id、coverage 或批次結果。
    @Test
    void synchronizesThreeSourcesWithTheSameNewYorkDateWindow() {
        MarketEventSyncService service = mock(MarketEventSyncService.class);
        JobExecutionContext context = mock(JobExecutionContext.class);
        LocalDate before = LocalDate.now(ZoneId.of("America/New_York"));

        new MarketEventDailySyncJob(service).execute(context);

        LocalDate after = LocalDate.now(ZoneId.of("America/New_York"));
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> through = ArgumentCaptor.forClass(LocalDate.class);
        InOrder order = inOrder(service);
        order.verify(service).syncMacroCalendar(from.capture(), through.capture());
        assertThat(from.getValue()).isIn(before, after);
        assertThat(through.getValue()).isEqualTo(from.getValue().plusDays(45));
        order.verify(service).syncFederalReserveCalendar(from.getValue(), through.getValue());
        order.verify(service).syncEarningsCalendar(from.getValue(), through.getValue());
        order.verifyNoMoreInteractions();
    }

    // Scenario：一個來源失敗時仍更新其餘來源，最後向 Quartz 回報原始失敗，不靜默當作成功。
    @Test
    void firstSourceFailureStillSynchronizesRemainingSourcesAndPropagatesOriginalFailure() {
        MarketEventSyncService service = mock(MarketEventSyncService.class);
        IllegalStateException failure = new IllegalStateException("calendar down");
        doThrow(failure).when(service).syncMacroCalendar(any(), any());

        assertThatThrownBy(() -> new MarketEventDailySyncJob(service).execute(mock(JobExecutionContext.class)))
                .isSameAs(failure);

        InOrder order = inOrder(service);
        order.verify(service).syncMacroCalendar(any(), any());
        order.verify(service).syncFederalReserveCalendar(any(), any());
        order.verify(service).syncEarningsCalendar(any(), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void secondSourceFailureDoesNotPreventEarningsCalendarSynchronization() {
        MarketEventSyncService service = mock(MarketEventSyncService.class);
        IllegalStateException failure = new IllegalStateException("fed down");
        doThrow(failure).when(service).syncFederalReserveCalendar(any(), any());

        assertThatThrownBy(() -> new MarketEventDailySyncJob(service).execute(mock(JobExecutionContext.class)))
                .isSameAs(failure);

        verify(service).syncMacroCalendar(any(), any());
        verify(service).syncFederalReserveCalendar(any(), any());
        verify(service).syncEarningsCalendar(any(), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void multipleSourceFailuresAreRetainedWithoutRetryingSources() {
        MarketEventSyncService service = mock(MarketEventSyncService.class);
        IllegalStateException first = new IllegalStateException("macro down");
        IllegalStateException second = new IllegalStateException("fed down");
        IllegalStateException third = new IllegalStateException("earnings down");
        doThrow(first).when(service).syncMacroCalendar(any(), any());
        doThrow(second).when(service).syncFederalReserveCalendar(any(), any());
        doThrow(third).when(service).syncEarningsCalendar(any(), any());

        assertThatThrownBy(() -> new MarketEventDailySyncJob(service).execute(mock(JobExecutionContext.class)))
                .isSameAs(first);
        assertThat(first.getSuppressed()).containsExactly(second, third);
        verify(service).syncMacroCalendar(any(), any());
        verify(service).syncFederalReserveCalendar(any(), any());
        verify(service).syncEarningsCalendar(any(), any());
        verifyNoMoreInteractions(service);
    }
}
