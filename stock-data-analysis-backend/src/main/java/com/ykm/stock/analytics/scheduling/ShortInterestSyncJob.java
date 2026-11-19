package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService.ShortSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

/** Quartz 雙週做空比例（Massive FINRA 官方結算資料）增量同步工作；免費已含額度，不新增長期付費訂閱。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.SHORT_INTEREST_SYNC)
public class ShortInterestSyncJob implements Job {
    private final ShortInterestService shortInterestService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            log.info("Short interest sync started");
            ShortSyncResult result = shortInterestService.syncMarketBatch();
            log.info("Short interest sync completed | fetched={} saved={} latestSettlementDate={}",
                    result.fetchedRows(), result.savedRows(), result.latestSettlementDate());
        } catch (RuntimeException ex) {
            log.warn("Short interest sync failed (non-blocking) | reason={}", ex.getMessage());
        }
    }
}
