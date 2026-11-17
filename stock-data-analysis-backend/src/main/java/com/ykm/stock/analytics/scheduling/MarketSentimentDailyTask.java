package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.analysis.VixObservationSyncService;
import lombok.RequiredArgsConstructor;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component(TaskCode.MARKET_SENTIMENT_DAILY)
@DisallowConcurrentExecution
@RequiredArgsConstructor
public class MarketSentimentDailyTask implements Job {
    private final MarketSentimentService sentiment;
    private final VixObservationSyncService vix;

    @Override
    public void execute(JobExecutionContext context) {
        // 兩個獨立來源均嘗試更新；失敗向上回報，不因 CNN 失敗而漏掉 VIX。
        RuntimeException failure = null;
        try {
            List<MarketSentimentDaily> rows = sentiment.downloadDailySnapshot();
            sentiment.save(rows);
        } catch (RuntimeException ex) { failure = ex; }
        try {
            List<MarketSentimentDaily> rows = vix.downloadObservations(Instant.now());
            vix.saveObservations(rows);
        } catch (RuntimeException ex) {
            if (failure == null) failure = ex;
            else failure.addSuppressed(ex);
        }
        if (failure != null) throw failure;
    }
}
