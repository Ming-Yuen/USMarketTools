package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 每日重讀 Futu 復權資料，逐股保存已生效的股份調整。 */
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.STOCK_SPLIT_DAILY)
@Slf4j
public class StockSplitDailyTask implements Job {
    private final StockRepository stocks;
    private final FutuService futu;
    private final StockSplitRepository splitRepository;
    private final StockStatusRepository statusRepository;
    private final FutuQuoteConverter converter;
    private final TransactionTemplate transactionTemplate;
    private final DateTimeService dateTimeService;
    private final MarketStatusService marketStatusService;

    @Value("${split.history-start-date:2014-01-01}")
    private LocalDate historyStartDate = LocalDate.of(2014, 1, 1);

    @Override
    public void execute(JobExecutionContext context) {
        // 決定已收市範圍及有效 CS／OS／ADRC／ETF，不按舊水位跳過歷史修訂。
        LocalDate through = marketStatusService.getLastClosedTradingDay();
        if (historyStartDate.isAfter(through)) throw new IllegalArgumentException("INVALID_SPLIT_SYNC_RANGE");
        List<Stock> targets = stocks.findSplitSyncStocks(ActiveStatus.ACTIVE);
        if (targets.isEmpty()) return;
        Map<String, Long> symbolCounts = targets.stream().collect(Collectors.groupingBy(Stock::getSymbol, Collectors.counting()));
        int failures = 0;

        // 一輪共用連線及限流；每股下載、轉換、提交完成才處理下一股。
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            for (Stock stock : targets) {
                try {
                    if (symbolCounts.get(stock.getSymbol()) != 1) throw new IllegalStateException("SPLIT_CURRENT_SYMBOL_AMBIGUOUS");
                    List<QotCommon.Rehab> source = session.rehab(stock.getSymbol());
                    List<StockSplit> splits = convertSplits(stock, source, historyStartDate, through);
                    saveSplits(stock, splits, historyStartDate, through);
                } catch (RuntimeException failure) {
                    recordFailure(stock, failure);
                    failures++;
                    // 斷線、逾時或回覆錯配不再重用連線；個股資料錯誤則繼續下一股。
                    String reason = String.valueOf(failure.getMessage());
                    if (reason.startsWith("FUTU_DISCONNECTED") || reason.endsWith("_TIMEOUT") || reason.endsWith("_SEND_FAILED")
                            || reason.endsWith("_REPLY_MISMATCH") || reason.endsWith("_INTERRUPTED")) throw failure;
                }
            }
        }
        log.info("Split sync finished | from={} through={} stocks={} exceptions={}", historyStartDate, through, targets.size(), failures);
    }

    private List<StockSplit> convertSplits(Stock stock, List<QotCommon.Rehab> source, LocalDate from, LocalDate through) {
        if (from == null || through == null || from.isAfter(through)) throw new IllegalArgumentException("INVALID_SPLIT_SYNC_RANGE");
        if (source == null) throw new IllegalStateException("SPLIT_SOURCE_DATA_MISSING");
        Map<LocalDate, StockSplit> events = new TreeMap<>();
        Instant observedAt = dateTimeService.now();
        for (QotCommon.Rehab input : source) {
            StockSplit row = converter.toStockSplit(input, stock.getFigi(), from, through, observedAt);
            if (row == null) continue;
            if (events.putIfAbsent(row.getExecutionDate(), row) != null)
                throw new IllegalStateException("SPLIT_DUPLICATE_SOURCE_DATE:" + row.getExecutionDate());
        }
        return List.copyOf(events.values());
    }

    private void saveSplits(Stock stock, List<StockSplit> downloaded, LocalDate from, LocalDate through) {
        transactionTemplate.executeWithoutResult(tx -> {
            StockStatus status = lockedStatus(stock.getFigi());
            Map<LocalDate, List<StockSplit>> stored = splitRepository.findByFigiAndExecutionDateBetween(stock.getFigi(), from, through)
                    .stream().collect(Collectors.groupingBy(StockSplit::getExecutionDate));
            List<String> replaced = new ArrayList<>();
            List<StockSplit> changed = new ArrayList<>();
            for (StockSplit row : downloaded) {
                List<StockSplit> previous = stored.remove(row.getExecutionDate());
                if (previous != null && previous.size() == 1 && samePayload(previous.getFirst(), row)) continue;
                // 同 FIGI 同日只有一個股份調整，切換來源不能保留兩份比例供下游累乘。
                if (previous != null) {
                    for (StockSplit old : previous) {
                        // 同一 Futu ID 由 saveAll 更新；bulk delete 只清除已被新來源取代的其他 ID。
                        if (!old.getSourceEventId().equals(row.getSourceEventId())) replaced.add(old.getSourceEventId());
                    }
                }
                changed.add(row);
            }
            if (!replaced.isEmpty()) splitRepository.deleteAllByIdInBatch(replaced);
            if (!changed.isEmpty()) splitRepository.saveAll(changed);
            status.setSplitFromDate(from);
            if (stored.isEmpty()) {
                status.setSplitDate(through);
                status.setSplitMessage(null);
            } else {
                // 缺席不是撤回；保留舊事件並停止聲稱該日期以後已核對成功。
                LocalDate firstMissing = stored.keySet().stream().min(LocalDate::compareTo).orElseThrow();
                status.setSplitDate(firstMissing.minusDays(1));
                status.setSplitMessage("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:" + firstMissing + ":dates=" + stored.size());
            }
            statusRepository.save(status);
            log.info("Split stock saved | symbol={} figi={} events={} changed={} missingDates={}",
                    stock.getSymbol(), stock.getFigi(), downloaded.size(), changed.size(), stored.size());
        });
    }

    private void recordFailure(Stock stock, RuntimeException failure) {
        String message = "ERROR:" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        transactionTemplate.executeWithoutResult(tx -> {
            StockStatus status = lockedStatus(stock.getFigi());
            status.setSplitMessage(message.substring(0, Math.min(1000, message.length())));
            statusRepository.save(status);
        });
        log.warn("Split stock failed | symbol={} figi={} reason={}", stock.getSymbol(), stock.getFigi(), message);
    }

    private StockStatus lockedStatus(String figi) {
        List<StockStatus> locked = statusRepository.findAllByIdForUpdate(List.of(figi));
        if (locked.size() != 1) throw new IllegalStateException("SPLIT_STOCK_STATUS_MISSING:" + figi);
        return locked.getFirst();
    }

    private boolean samePayload(StockSplit left, StockSplit right) {
        return left.getSourceEventId().equals(right.getSourceEventId())
                && left.getSplitFrom().compareTo(right.getSplitFrom()) == 0 && left.getSplitTo().compareTo(right.getSplitTo()) == 0;
    }
}
