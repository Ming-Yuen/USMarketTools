package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.analysis.VixObservationSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

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
}
