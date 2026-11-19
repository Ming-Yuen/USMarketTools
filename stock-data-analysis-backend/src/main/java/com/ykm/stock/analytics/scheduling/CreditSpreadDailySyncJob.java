package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.service.enhancement.MacroCreditSpreadService;
import com.ykm.stock.analytics.service.enhancement.MacroCreditSpreadService.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

/** Quartz 每日高收益債信用利差（FRED BAMLH0A0HYM2）同步工作；假日無更新時沿用最近有效日，絕不中斷估值排程。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.CREDIT_SPREAD_DAILY_SYNC)
public class CreditSpreadDailySyncJob implements Job {
    private final MacroCreditSpreadService creditSpreadService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            log.info("Credit spread daily sync started | series=BAMLH0A0HYM2");
            SyncResult result = creditSpreadService.syncLatest();
            log.info("Credit spread daily sync completed | fetched={} saved={} latestObservationDate={}",
                    result.fetchedCount(), result.savedCount(), result.latestObservationDate());
        } catch (RuntimeException ex) {
            // 隔離總經外部來源短暫失敗，記錄警告而不阻斷下游每日估值任務。
            log.warn("Credit spread daily sync failed (non-blocking) | reason={}", ex.getMessage());
        }
    }
}
