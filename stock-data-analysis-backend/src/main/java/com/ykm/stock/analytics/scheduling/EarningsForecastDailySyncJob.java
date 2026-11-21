package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.EarningsForecastSyncService;
import com.ykm.stock.analytics.service.futu.FutuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component(TaskCode.EARNINGS_FORECAST_DAILY_SYNC)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class EarningsForecastDailySyncJob implements Job {
    private final FutuService futuService;
    private final EarningsForecastSyncService sync;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) {
        // 先完成來源下載，再一次提交預期值；不建立執行批次或原始回應檔案。
        JobDataMap parameters = context == null ? new JobDataMap() : context.getMergedJobDataMap();
        String from = parameters.getString("from");
        String through = parameters.getString("through");
        if ((from == null) != (through == null)) throw new IllegalArgumentException("EARNINGS_FORECAST_SCOPE_INCOMPLETE");
        boolean defaultWindow = from == null;
        LocalDate defaultStart = dateTimeService != null
                ? dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate()
                : LocalDate.now(MarketStatusService.NY_ZONE);
        LocalDate start = defaultWindow ? defaultStart : LocalDate.parse(from);
        LocalDate end = defaultWindow ? start.plusDays(45) : LocalDate.parse(through);
        if (end.isBefore(start)) throw new IllegalArgumentException("EARNINGS_FORECAST_SCOPE_INVALID");
        List<StockEarningsForecast> rows;
        try (FutuService.ObservationSession session = futuService.openObservationSession()) {
            rows = sync.download(session, new EarningsForecastSyncService.Window(start, end));
        }
        sync.save(rows);
        if (defaultWindow) {
            JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.EARNINGS_FORECAST_DAILY_SYNC, start);
        }
        log.info("Earnings forecasts saved | from={} through={} forecasts={}", start, end, rows.size());
    }
}
