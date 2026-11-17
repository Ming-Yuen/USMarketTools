package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.client.TreasuryDirectClient;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.service.market.TreasuryService;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/** Quartz 美國國債殖利率同步工作；取得 Treasury feed、保存最新觀測並回報資料日期。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.TREASURY_DAILY_SYNC)
public class TreasuryDailySyncJob implements Job {

    private final TreasuryDirectClient treasuryDirectClient;
    private final TreasuryService treasuryService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            // 1. 下載 Treasury 殖利率來源 feed。
            log.info("Treasury daily sync started | phase=DOWNLOAD");
            TreasuryYieldCurveFeed treasuryYieldCurveFeed = treasuryDirectClient.getCurrentMonthYieldCurve();

            // 2. 核驗來源觀測日期；缺日期時回報未解決，不保存不完整來源。
            LocalDate sourceDataThrough = latestObservationDate(treasuryYieldCurveFeed);
            if (sourceDataThrough == null) {
                log.info("Treasury daily sync completed | completed=1/1 saved=0 unresolved=1 reason=NO_OBSERVATION_DATE");
                return;
            }

            // 3. 保存完整 feed，成功後才回報本輪結果及來源日期。
            log.info("Treasury daily sync progress | phase=PERSIST sourceThrough={}", sourceDataThrough);
            treasuryService.saveOrUpdateTreasuryParYieldDailies(treasuryYieldCurveFeed);
            log.info("Treasury daily sync completed | completed=1/1 sourceThrough={}", sourceDataThrough);
        } catch (Exception ex) {
            log.error("Treasury daily nominal par yield curve synchronization failed.", ex);

            throw new JobExecutionException("Treasury daily nominal par yield curve synchronization failed.", ex, false);
        }
    }

    // 用途：從 Treasury feed 中找出最新有效觀測日期，作為本次同步的 sourceDataThrough。
    private LocalDate latestObservationDate(TreasuryYieldCurveFeed feed) {
        if (feed == null || feed.getEntries() == null) return null;
        return feed.getEntries().stream()
                .filter(java.util.Objects::nonNull)
                .filter(entry -> entry.getContent() != null && entry.getContent().getProperties() != null
                        && entry.getContent().getProperties().getDate() != null)
                .map(entry -> entry.getContent().getProperties().getDate().toLocalDate())
                .max(LocalDate::compareTo)
                .orElse(null);
    }

}
