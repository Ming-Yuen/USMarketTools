package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.analysis.VixObservationSyncService;
import lombok.RequiredArgsConstructor;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Component(TaskCode.MARKET_SENTIMENT_DAILY)
@DisallowConcurrentExecution
@RequiredArgsConstructor
public class MarketSentimentDailyTask implements Job {
    private final MarketSentimentService sentiment;
    private final VixObservationSyncService vix;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;

    @Override
    public void execute(JobExecutionContext context) {
        // 兩個獨立來源均嘗試更新；MARKET_SENTIMENT_DAILY 水位專責反映 VIX 成功進度，不受 CNN 異常影響。
        RuntimeException cnnFailure = null;
        try {
            List<MarketSentimentDaily> rows = sentiment.downloadDailySnapshot();
            sentiment.save(rows);
        } catch (RuntimeException ex) { cnnFailure = ex; }

        RuntimeException vixFailure = null;
        List<MarketSentimentDaily> vixRows = null;
        Instant observedAt = Instant.now();
        try {
            vix.backfillHistoricalIfNeeded(observedAt);
        } catch (RuntimeException ex) {
            vixFailure = ex;
        }
        try {
            vixRows = vix.downloadObservations(observedAt);
            vix.saveObservations(vixRows);
        } catch (RuntimeException ex) {
            if (vixFailure == null) vixFailure = ex;
            else vixFailure.addSuppressed(ex);
        }

        if (vixFailure == null && jobConfigRepository != null && vixRows != null) {
            LocalDate latestVixDate = vixRows.stream()
                    .filter(Objects::nonNull)
                    .map(MarketSentimentDaily::getMarketDate)
                    .filter(Objects::nonNull)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            if (latestVixDate != null && vix.isHistoryReadyForWatermark()) {
                JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.MARKET_SENTIMENT_DAILY, latestVixDate);
            }
        }

        if (cnnFailure != null) {
            if (vixFailure != null) cnnFailure.addSuppressed(vixFailure);
            throw cnnFailure;
        }
        if (vixFailure != null) throw vixFailure;
    }
}
