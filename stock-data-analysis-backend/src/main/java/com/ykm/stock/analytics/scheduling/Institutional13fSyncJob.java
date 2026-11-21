package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService.Sync13fResult;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Quartz 精選知名機構 13F-HR 季度持倉週頻同步工作（僅 ~15 家機構最近兩季，嚴守 SEC 5 req/s 限流）。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.INSTITUTIONAL_13F_SYNC)
public class Institutional13fSyncJob implements Job {
    private final SecInsiderAndInstitutionalService insider13fService;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            log.info("Institutional 13F sync started");
            Sync13fResult result = insider13fService.syncFlagship13fManagers(0);
            int expectedManagers = SecInsiderAndInstitutionalService.FLAGSHIP_13F_MANAGERS.size();
            if (result != null && result.managersSynced() >= expectedManagers) {
                LocalDate businessDate = dateTimeService != null
                        ? dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate()
                        : LocalDate.now(MarketStatusService.NY_ZONE);
                JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.INSTITUTIONAL_13F_SYNC, businessDate);
            }
            log.info("Institutional 13F sync completed | managers={}/{} filings={} holdings={}",
                    result == null ? 0 : result.managersSynced(), expectedManagers,
                    result == null ? 0 : result.filingsParsed(), result == null ? 0 : result.holdingsSaved());
        } catch (RuntimeException ex) {
            log.warn("Institutional 13F sync failed (non-blocking) | reason={}", ex.getMessage());
        }
    }
}
