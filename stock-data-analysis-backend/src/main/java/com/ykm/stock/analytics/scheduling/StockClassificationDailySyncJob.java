package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.classification.StockClassificationSnapshot;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.StockClassificationSyncService;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.stock.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.STOCK_CLASSIFICATION_DAILY_SYNC)
public class StockClassificationDailySyncJob implements Job {
    private static final int BATCH_SIZE = 200;

    private final StockService stockService;
    private final FutuService futuService;
    private final StockClassificationSyncService classificationSyncService;
    private final TransactionTemplate transactionTemplate;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) {
        // 1. 僅分析 ACTIVE 普通股，富途分類不依賴 SEC CIK，再以 STOCK_STATUS 判斷待同步 FIGI。
        List<ActiveStockWithStatus> active = stockService.findActiveStocksWithStatus().stream()
                .filter(item -> SecurityTypePolicy.isOperatingCompanyType(item.stock().getStockType()))
                .toList();
        if (active.isEmpty()) throw new IllegalStateException("STOCK_CLASSIFICATION_UNIVERSE_EMPTY");

        // 2. 手動指定 FIGI 優先；週六原排程完整刷新，其餘只處理尚未同步目前代碼的股票。
        JobDataMap parameters = context == null ? null : context.getMergedJobDataMap();
        String requestedFigis = parameters == null ? null : parameters.getString("figis");
        boolean manualFigisMode = requestedFigis != null && !requestedFigis.isBlank();
        List<Stock> candidates;
        if (manualFigisMode) {
            Set<String> selected = Arrays.stream(requestedFigis.split(",")).map(String::trim).collect(Collectors.toSet());
            candidates = active.stream().map(ActiveStockWithStatus::stock).filter(stock -> selected.contains(stock.getFigi())).toList();
            if (candidates.isEmpty()) throw new IllegalStateException("STOCK_CLASSIFICATION_UNIVERSE_EMPTY");
        } else if (isWeeklyRefresh(context)) {
            candidates = active.stream().map(ActiveStockWithStatus::stock).toList();
        } else {
            candidates = active.stream()
                    .filter(item -> !Objects.equals(item.stock().getSymbol(), item.status().getClassificationSyncedSymbol()))
                    .map(ActiveStockWithStatus::stock).toList();
        }
        if (candidates.isEmpty()) {
            if (!manualFigisMode) {
                advanceJobWatermark();
            }
            log.info("Stock classification skipped | reason=NO_PENDING_STOCKS");
            return;
        }

        // 3. 載入富途正股清單及字典；用本次分析範圍內所有 ACTIVE FIGI 判斷代碼是否唯一。
        Map<String, Long> activeSymbolCounts = active.stream().map(ActiveStockWithStatus::stock)
                .collect(Collectors.groupingBy(Stock::getSymbol, Collectors.counting()));
        int targetCount;
        int matched = 0;
        try (FutuService.ClassificationSession futu = futuService.openSession()) {
            List<Stock> targets = candidates.stream().filter(stock -> futu.supportsStock(stock.getSymbol()))
                    .filter(stock -> activeSymbolCounts.get(stock.getSymbol()) == 1L).toList();
            targetCount = targets.size();
            int unsupported = (int) candidates.stream().filter(stock -> !futu.supportsStock(stock.getSymbol())).count();
            int ambiguous = candidates.size() - unsupported - targets.size();
            log.info("Stock classification started | candidates={} supportedUnique={} unsupported={} ambiguous={}",
                    candidates.size(), targets.size(), unsupported, ambiguous);
            for (int offset = 0; offset < targets.size(); offset += BATCH_SIZE) {
                List<Stock> batch = targets.subList(offset, Math.min(offset + BATCH_SIZE, targets.size()));
                // 4. 交易外取得本批關聯，成功後原子保存字典、關聯與同步代碼。
                Map<String, StockClassificationSnapshot> downloaded = futu.getStockClassifications(batch.stream().map(Stock::getSymbol).toList());
                matched += transactionTemplate.execute(status -> classificationSyncService.updateClassifications(batch, downloaded));
                log.info("Stock classification progress | committed={}/{} matched={}", offset + batch.size(), targets.size(), matched);
            }
        }
        if (!manualFigisMode && (targetCount == 0 || matched > 0)) {
            advanceJobWatermark();
        }
    }

    private void advanceJobWatermark() {
        LocalDate businessDate = dateTimeService != null
                ? dateTimeService.now().atZone(MarketStatusService.NY_ZONE).toLocalDate()
                : LocalDate.now(MarketStatusService.NY_ZONE);
        JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.STOCK_CLASSIFICATION_DAILY_SYNC, businessDate);
    }

    /** 只在週六的 Cron 觸發時完整刷新。 */
    private boolean isWeeklyRefresh(JobExecutionContext context) {
        return context != null && context.getTrigger() instanceof CronTrigger && context.getScheduledFireTime() != null && context.getScheduledFireTime().toInstant()
                .atZone(ZoneId.of(JobScheduleReconciler.SCHEDULE_TIMEZONE)).getDayOfWeek() == DayOfWeek.SATURDAY;
    }
}
