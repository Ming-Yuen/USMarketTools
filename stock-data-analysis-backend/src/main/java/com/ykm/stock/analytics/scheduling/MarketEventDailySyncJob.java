package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.service.market.MarketEventSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component(TaskCode.MARKET_EVENT_DAILY_SYNC)
@DisallowConcurrentExecution
@RequiredArgsConstructor
@Slf4j
public class MarketEventDailySyncJob implements Job {
    private final MarketEventSyncService sync;

    @Override
    public void execute(JobExecutionContext context) {
        // 三個來源獨立保存；某來源失敗仍嘗試其他來源，最後把原始失敗回報 Quartz。
        LocalDate from = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate through = from.plusDays(45);
        RuntimeException failure = null;
        try { sync.syncMacroCalendar(from, through); }
        catch (RuntimeException ex) { failure = ex; }
        try { sync.syncFederalReserveCalendar(from, through); }
        catch (RuntimeException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        try { sync.syncEarningsCalendar(from, through); }
        catch (RuntimeException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        if (failure != null) throw failure;
        log.info("Market calendars saved | from={} through={}", from, through);
    }
}
