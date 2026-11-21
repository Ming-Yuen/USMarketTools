package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.analysis.VixObservationSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketSentimentDailyTaskTests {
    private final MarketSentimentService sentiment = mock(MarketSentimentService.class);
    private final VixObservationSyncService vix = mock(VixObservationSyncService.class);
    private final MarketSentimentDailyTask job = new MarketSentimentDailyTask(sentiment, vix);

    // Scenario：兩個來源各自保存typed欄位，單一來源失敗仍嘗試另一個，最後回報失敗。
    @Test
    void eachSourceIsSavedAfterItsDownloadCompletes() {
        List<MarketSentimentDaily> cnnRows = List.of(new MarketSentimentDaily());
        List<MarketSentimentDaily> vixRows = List.of(new MarketSentimentDaily());
        when(sentiment.downloadDailySnapshot()).thenReturn(cnnRows);
        when(vix.downloadObservations(any())).thenReturn(vixRows);

        job.execute(null);

        InOrder order = inOrder(sentiment, vix);
        order.verify(sentiment).downloadDailySnapshot();
        order.verify(sentiment).save(cnnRows);
        order.verify(vix).backfillHistoricalIfNeeded(any());
        order.verify(vix).downloadObservations(any());
        order.verify(vix).saveObservations(vixRows);
    }

    @Test
    void cnnFailureDoesNotPreventVixUpdateAndIsNotSwallowed() {
        IllegalStateException failure = new IllegalStateException("CNN_DOWN");
        when(sentiment.downloadDailySnapshot()).thenThrow(failure);
        List<MarketSentimentDaily> rows = List.of(new MarketSentimentDaily());
        when(vix.downloadObservations(any())).thenReturn(rows);

        assertThatThrownBy(() -> job.execute(null)).isSameAs(failure);
        verify(sentiment, never()).save(any());
        verify(vix).saveObservations(rows);
    }

    @Test
    void bothSourceFailuresAreReported() {
        IllegalStateException cnnFailure = new IllegalStateException("CNN_DOWN");
        IllegalStateException vixFailure = new IllegalStateException("VIX_DOWN");
        when(sentiment.downloadDailySnapshot()).thenThrow(cnnFailure);
        when(vix.downloadObservations(any())).thenThrow(vixFailure);

        assertThatThrownBy(() -> job.execute(null)).isSameAs(cnnFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(vixFailure));
        verify(vix, never()).saveObservations(any());
    }

    @Test
    void fredBackfillFailureDoesNotPreventYahooDailyUpdateAndIsStillPropagated() {
        List<MarketSentimentDaily> cnnRows = List.of(new MarketSentimentDaily());
        List<MarketSentimentDaily> yahooRows = List.of(new MarketSentimentDaily());
        IllegalStateException fredFailure = new IllegalStateException("FRED_API_UNAUTHORIZED:401");
        when(sentiment.downloadDailySnapshot()).thenReturn(cnnRows);
        when(vix.backfillHistoricalIfNeeded(any())).thenThrow(fredFailure);
        when(vix.downloadObservations(any())).thenReturn(yahooRows);

        assertThatThrownBy(() -> job.execute(null)).isSameAs(fredFailure);
        verify(sentiment).save(cnnRows);
        verify(vix).downloadObservations(any());
        verify(vix).saveObservations(yahooRows);
    }

    @Test
    void advancesVixWatermarkEvenWhenCnnFailsAndBlocksWatermarkOnVixFailure() {
        JobConfigRepository jobConfigRepository = mock(JobConfigRepository.class);
        ReflectionTestUtils.setField(job, "jobConfigRepository", jobConfigRepository);
        when(jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.MARKET_SENTIMENT_DAILY)).thenReturn(Optional.empty());
        when(vix.isHistoryReadyForWatermark()).thenReturn(true);

        MarketSentimentDaily vixRow = new MarketSentimentDaily();
        vixRow.setMarketDate(LocalDate.of(2026, 11, 20));
        IllegalStateException cnnFailure = new IllegalStateException("CNN_DOWN");
        when(sentiment.downloadDailySnapshot()).thenThrow(cnnFailure);
        when(vix.downloadObservations(any())).thenReturn(List.of(vixRow));

        assertThatThrownBy(() -> job.execute(null)).isSameAs(cnnFailure);
        verify(jobConfigRepository).updateWatermarkDate(TaskCode.MARKET_SENTIMENT_DAILY, LocalDate.of(2026, 11, 20));

        clearInvocations(jobConfigRepository);
        doReturn(List.of(new MarketSentimentDaily())).when(sentiment).downloadDailySnapshot();
        when(vix.backfillHistoricalIfNeeded(any())).thenThrow(new IllegalStateException("FRED_DOWN"));
        assertThatThrownBy(() -> job.execute(null)).hasMessage("FRED_DOWN");
        verify(jobConfigRepository, never()).updateWatermarkDate(anyString(), any());
    }
}
