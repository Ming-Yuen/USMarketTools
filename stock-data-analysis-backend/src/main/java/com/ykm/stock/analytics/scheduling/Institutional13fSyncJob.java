package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.Sync13fResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

/** Quartz 精選知名機構 13F-HR 季度持倉週頻同步工作（僅 ~15 家機構最近兩季，嚴守 SEC 5 req/s 限流）。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.INSTITUTIONAL_13F_SYNC)
public class Institutional13fSyncJob implements Job {
    private final SecInsiderAndInstitutionalService insider13fService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            log.info("Institutional 13F sync started");
            Sync13fResult result = insider13fService.syncFlagship13fManagers(0);
            log.info("Institutional 13F sync completed | managers={} filings={} holdings={}",
                    result.managersSynced(), result.filingsParsed(), result.holdingsSaved());
        } catch (RuntimeException ex) {
            log.warn("Institutional 13F sync failed (non-blocking) | reason={}", ex.getMessage());
        }
    }
}
