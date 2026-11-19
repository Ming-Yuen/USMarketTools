package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.service.market.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** 同步官方日頻外幣匯率（FRED H.10），供非美元財報公司與 ADR 估值換算使用。 */
@Component(TaskCode.FX_RATE_DAILY_SYNC)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class FxRateDailySyncJob implements Job {
    private final FxRateService fxRateService;

    @Override
    public void execute(JobExecutionContext context) {
        JobDataMap params = context == null ? new JobDataMap() : context.getMergedJobDataMap();
        List<String> currencies = parseCurrencies(params.getString("currencies"));
        LocalDate fromDate = parseDate(params.getString("fromDate"));
        LocalDate throughDate = parseDate(params.getString("throughDate"));
        int saved = fxRateService.syncFredRates(currencies, fromDate, throughDate);
        log.info("FX_RATE_DAILY_SYNC finished | savedRows={}", saved);
    }

    private List<String> parseCurrencies(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return LocalDate.parse(raw.trim());
    }
}
