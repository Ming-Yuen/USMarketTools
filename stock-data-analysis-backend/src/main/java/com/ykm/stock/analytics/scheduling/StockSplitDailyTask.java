package com.ykm.stock.analytics.scheduling;

import com.futu.openapi.pb.QotCommon;
import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplit;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.JobScheduleReconciler;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronTrigger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 平日以 Massive /stocks/v1/splits 批量增量同步拆股；僅對 splitDate 為空的新股或週六對帳時呼叫富途 requestRehab。 */
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.STOCK_SPLIT_DAILY)
@Slf4j
public class StockSplitDailyTask implements Job {
    private static final int STATUS_BATCH_SIZE = 200;
    private static final int INCREMENTAL_LOOKBACK_DAYS = 7;

    private final StockRepository stocks;
    private final MassiveService massiveService;
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
        LocalDate through = marketStatusService.getLastClosedTradingDay();
        if (historyStartDate.isAfter(through)) throw new IllegalArgumentException("INVALID_SPLIT_SYNC_RANGE");
        List<Stock> targets = stocks.findSplitSyncStocks(ActiveStatus.ACTIVE);
        if (targets.isEmpty()) return;

        Map<String, Long> symbolCounts = targets.stream()
                .collect(Collectors.groupingBy(Stock::getSymbol, Collectors.counting()));
        List<Stock> validTargets = new ArrayList<>();
        int failures = 0;
        for (Stock stock : targets) {
            if (symbolCounts.get(stock.getSymbol()) != 1L) {
                recordFailure(stock, new IllegalStateException("SPLIT_CURRENT_SYMBOL_AMBIGUOUS"));
                failures++;
            } else {
                validTargets.add(stock);
            }
        }
        if (validTargets.isEmpty()) return;

        boolean weeklyReconcile = isWeeklyRefresh(context);
        Map<String, StockStatus> initialStatuses = new HashMap<>();
        for (StockStatus row : statusRepository.findAllById(validTargets.stream().map(Stock::getFigi).toList())) {
            initialStatuses.put(row.getFigi(), row);
        }

        List<Stock> futuTargets = new ArrayList<>();
        List<Stock> massiveTargets = new ArrayList<>();
        for (Stock stock : validTargets) {
            StockStatus current = initialStatuses.get(stock.getFigi());
            if (weeklyReconcile || current == null || current.getSplitDate() == null) {
                futuTargets.add(stock);
            } else {
                massiveTargets.add(stock);
            }
        }

        if (!massiveTargets.isEmpty()) {
            failures += syncIncrementalFromMassive(massiveTargets, initialStatuses, through);
        }
        if (!futuTargets.isEmpty()) {
            failures += syncFullHistoryFromFutu(futuTargets, through);
        }
        log.info("Split sync finished | weeklyReconcile={} through={} massiveStocks={} futuStocks={} exceptions={}",
                weeklyReconcile, through, massiveTargets.size(), futuTargets.size(), failures);
    }

    private int syncIncrementalFromMassive(List<Stock> targets, Map<String, StockStatus> initialStatuses, LocalDate through) {
        LocalDate minFrom = through;
        for (Stock stock : targets) {
            LocalDate stockFrom = incrementalFrom(initialStatuses.get(stock.getFigi()));
            if (stockFrom.isBefore(minFrom)) minFrom = stockFrom;
        }
        List<MassiveSplit> allSplits = massiveService.getSplitsFrom(minFrom);
        Map<String, List<MassiveSplit>> byTicker = new LinkedHashMap<>();
        for (MassiveSplit split : allSplits) {
            if (split.getTicker() == null || split.getTicker().isBlank()) continue;
            byTicker.computeIfAbsent(split.getTicker().trim(), ignored -> new ArrayList<>()).add(split);
        }

        int failures = 0;
        List<String> quietStatusFigis = new ArrayList<>();
        Instant observedAt = dateTimeService.now();
        for (Stock stock : targets) {
            StockStatus snapshot = initialStatuses.get(stock.getFigi());
            LocalDate stockFrom = incrementalFrom(snapshot);
            List<MassiveSplit> rawEvents = byTicker.getOrDefault(stock.getSymbol(), List.of());
            try {
                List<StockSplit> converted = convertMassiveSplits(stock, rawEvents, stockFrom, through, observedAt);
                if (converted.isEmpty()) {
                    if (!through.equals(snapshot.getSplitDate()) || snapshot.getSplitMessage() != null) {
                        quietStatusFigis.add(stock.getFigi());
                    }
                    continue;
                }
                saveIncrementalSplits(stock, converted, stockFrom, through);
            } catch (RuntimeException failure) {
                recordFailure(stock, failure);
                failures++;
            }
        }
        for (List<String> batch : Lists.partition(quietStatusFigis, STATUS_BATCH_SIZE)) {
            transactionTemplate.executeWithoutResult(tx -> {
                List<StockStatus> locked = statusRepository.findAllByIdForUpdate(batch);
                if (locked.size() != batch.size()) throw new IllegalStateException("SPLIT_STOCK_STATUS_MISSING");
                for (StockStatus status : locked) {
                    if (status.getSplitFromDate() == null) status.setSplitFromDate(historyStartDate);
                    status.setSplitDate(through);
                    status.setSplitMessage(null);
                }
                statusRepository.saveAll(locked);
            });
        }
        return failures;
    }

    private LocalDate incrementalFrom(StockStatus status) {
        LocalDate candidate = status.getSplitDate().minusDays(INCREMENTAL_LOOKBACK_DAYS);
        return candidate.isBefore(historyStartDate) ? historyStartDate : candidate;
    }

    private List<StockSplit> convertMassiveSplits(Stock stock, List<MassiveSplit> source, LocalDate from, LocalDate through, Instant observedAt) {
        Map<LocalDate, StockSplit> events = new TreeMap<>();
        for (MassiveSplit item : source) {
            LocalDate date = item.getExecutionDate();
            if (date == null) throw new IllegalStateException("SPLIT_SOURCE_DATE_MISSING");
            if (date.isBefore(from) || date.isAfter(through)) continue;
            if (item.getSplitFrom() == null || item.getSplitTo() == null
                    || item.getSplitFrom().signum() <= 0 || item.getSplitTo().signum() <= 0)
                throw new IllegalStateException("SPLIT_SOURCE_VALUE_INVALID:" + date);
            if (item.getSplitFrom().compareTo(item.getSplitTo()) == 0) continue;
            StockSplit row = new StockSplit();
            String idSuffix = item.getId() != null && !item.getId().isBlank()
                    ? item.getId().trim() : stock.getFigi() + ":" + date;
            row.setSourceEventId("MASSIVE:" + idSuffix);
            row.setFigi(stock.getFigi());
            row.setExecutionDate(date);
            row.setSplitFrom(item.getSplitFrom());
            row.setSplitTo(item.getSplitTo());
            row.setObservedAt(observedAt);
            if (events.putIfAbsent(date, row) != null)
                throw new IllegalStateException("SPLIT_DUPLICATE_SOURCE_DATE:" + date);
        }
        return List.copyOf(events.values());
    }

    private void saveIncrementalSplits(Stock stock, List<StockSplit> downloaded, LocalDate from, LocalDate through) {
        transactionTemplate.executeWithoutResult(tx -> {
            StockStatus status = lockedStatus(stock.getFigi());
            Map<LocalDate, List<StockSplit>> stored = splitRepository.findByFigiAndExecutionDateBetween(stock.getFigi(), from, through)
                    .stream().collect(Collectors.groupingBy(StockSplit::getExecutionDate));
            List<String> replaced = new ArrayList<>();
            List<StockSplit> changed = new ArrayList<>();
            for (StockSplit row : downloaded) {
                List<StockSplit> previous = stored.remove(row.getExecutionDate());
                if (previous != null && previous.size() == 1 && sameRatio(previous.getFirst(), row)) continue;
                if (previous != null) {
                    for (StockSplit old : previous) {
                        if (!old.getSourceEventId().equals(row.getSourceEventId())) replaced.add(old.getSourceEventId());
                    }
                }
                changed.add(row);
            }
            if (!replaced.isEmpty()) splitRepository.deleteAllByIdInBatch(replaced);
            if (!changed.isEmpty()) splitRepository.saveAll(changed);
            if (status.getSplitFromDate() == null) status.setSplitFromDate(historyStartDate);
            status.setSplitDate(through);
            status.setSplitMessage(null);
            statusRepository.save(status);
        });
    }

    private int syncFullHistoryFromFutu(List<Stock> targets, LocalDate through) {
        int failures = 0;
        try (FutuService.ObservationSession session = futu.openObservationSession()) {
            for (Stock stock : targets) {
                try {
                    List<QotCommon.Rehab> source = session.rehab(stock.getSymbol());
                    List<StockSplit> splits = convertSplits(stock, source, historyStartDate, through);
                    saveSplits(stock, splits, historyStartDate, through);
                } catch (RuntimeException failure) {
                    recordFailure(stock, failure);
                    failures++;
                    String reason = String.valueOf(failure.getMessage());
                    if (reason.startsWith("FUTU_DISCONNECTED") || reason.endsWith("_TIMEOUT") || reason.endsWith("_SEND_FAILED")
                            || reason.endsWith("_REPLY_MISMATCH") || reason.endsWith("_INTERRUPTED")) throw failure;
                }
            }
        }
        return failures;
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
                if (previous != null) {
                    for (StockSplit old : previous) {
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

    private boolean isWeeklyRefresh(JobExecutionContext context) {
        return context != null && context.getTrigger() instanceof CronTrigger && context.getScheduledFireTime() != null
                && context.getScheduledFireTime().toInstant().atZone(ZoneId.of(JobScheduleReconciler.SCHEDULE_TIMEZONE)).getDayOfWeek() == DayOfWeek.SATURDAY;
    }

    private boolean samePayload(StockSplit left, StockSplit right) {
        return left.getSourceEventId().equals(right.getSourceEventId()) && sameRatio(left, right);
    }

    private boolean sameRatio(StockSplit left, StockSplit right) {
        return left.getSplitFrom().compareTo(right.getSplitFrom()) == 0 && left.getSplitTo().compareTo(right.getSplitTo()) == 0;
    }
}
