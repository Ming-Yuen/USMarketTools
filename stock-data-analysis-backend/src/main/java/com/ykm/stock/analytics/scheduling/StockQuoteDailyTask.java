package com.ykm.stock.analytics.scheduling;

import com.google.common.collect.Lists;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.jpa.StockQuoteConverter;
import com.ykm.stock.analytics.model.dto.alpaca.AlpacaBar;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate;
import com.ykm.stock.analytics.service.stock.AlpacaService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.StockQuoteHistoryService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.service.analysis.calculator.QuoteNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

/** 按股票行情水位增量下載 Alpaca 日線；首次由歷史設定日期開始。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.STOCK_QUOTE_DAILY)
public class StockQuoteDailyTask implements Job {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int SYMBOL_BATCH_SIZE = 500;
    private static final int HISTORICAL_BATCH_SIZE = 10;
    private static final int RECENT_LOOKBACK_SESSIONS = 5;

    @Value("${market-history.start-date:2016-01-01}")
    private LocalDate historyStartDate = LocalDate.of(2016, 1, 1);

    private final StockService stockService;
    private final StockQuoteHistoryService stockQuoteHistoryService;
    private final StockQuoteConverter stockQuoteConverter;
    private final MarketStatusService marketStatusService;
    private final AlpacaService alpacaService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void execute(JobExecutionContext context) {
        LocalDate latestClosedTradingDay = marketStatusService.getLastClosedTradingDay();
        List<LocalDate> recentSessions = marketStatusService.getTradingDays(latestClosedTradingDay.minusDays(15), latestClosedTradingDay);
        LocalDate lookbackStart = recentSessions != null && recentSessions.size() >= RECENT_LOOKBACK_SESSIONS
                ? recentSessions.get(recentSessions.size() - RECENT_LOOKBACK_SESSIONS) : null;
        Map<LocalDate, List<Stock>> stocksByFrom = new LinkedHashMap<>();
        Map<String, String> symbolFigis = new LinkedHashMap<>();
        Map<String, String> ambiguous = new LinkedHashMap<>();
        for (ActiveStockQuoteDate target : stockService.findActiveStockQuoteDates(latestClosedTradingDay)) {
            Stock stock = target.stock();
            String previousFigi = symbolFigis.putIfAbsent(stock.getSymbol(), stock.getFigi());
            if (previousFigi != null && !previousFigi.equals(stock.getFigi())) {
                String reason = "AMBIGUOUS_ACTIVE_SYMBOL:" + stock.getSymbol();
                ambiguous.put(previousFigi, reason);
                ambiguous.put(stock.getFigi(), reason);
            }
            LocalDate from;
            if (target.quoteDate() == null) {
                from = historyStartDate;
            } else {
                LocalDate nextDay = target.quoteDate().plusDays(1);
                from = lookbackStart != null && lookbackStart.isBefore(nextDay) ? lookbackStart : nextDay;
            }
            if (from.isBefore(historyStartDate)) from = historyStartDate;
            if (!from.isAfter(latestClosedTradingDay)) stocksByFrom.computeIfAbsent(from, date -> new ArrayList<>()).add(stock);
        }
        if (!ambiguous.isEmpty()) stockService.recordQuoteFailures(ambiguous);
        stocksByFrom.values().forEach(group -> group.removeIf(stock -> ambiguous.containsKey(stock.getFigi())));
        int failedStocks = ambiguous.size();
        for (Map.Entry<LocalDate, List<Stock>> group : stocksByFrom.entrySet()) {
            LocalDate from = group.getKey();
            // 多年歷史日線分小批下載，避免把數百隻股票的全部歷史同時放入記憶體。
            int batchSize = from.isBefore(latestClosedTradingDay.minusDays(30)) ? HISTORICAL_BATCH_SIZE : SYMBOL_BATCH_SIZE;
            for (List<Stock> batch : Lists.partition(group.getValue(), batchSize)) {
                Map<String, String> failures = new LinkedHashMap<>();
                List<StockQuote> quotes = downloadQuotes(batch, from, latestClosedTradingDay, failures);
                if (!failures.isEmpty()) stockService.recordQuoteFailures(failures);
                failedStocks += failures.size();
                if (!quotes.isEmpty()) {
                    Map<String, LocalDate> latestDates = new HashMap<>();
                    for (StockQuote quote : quotes) latestDates.merge(quote.getFigi(), quote.getQuoteDate(), BinaryOperator.maxBy(LocalDate::compareTo));
                    // 行情與每隻股票最後實際儲存的日期一併提交，缺少 bar 不推進水位。
                    transactionTemplate.executeWithoutResult(tx -> {
                        stockQuoteHistoryService.updateStockQuote(quotes);
                        List<StockStatus> statuses = stockService.findStockStatuses(latestDates.keySet());
                        if (statuses.size() != latestDates.size()) throw new IllegalStateException("QUOTE_STOCK_STATUS_MISSING");
                        for (StockStatus status : statuses) {
                            status.setQuoteDate(latestDates.get(status.getFigi()));
                            status.setQuoteMessage(null);
                        }
                        stockService.updateStockStatuses(statuses);
                    });
                }
                log.info("Alpaca quotes | from={} through={} requested={} saved={}",
                        from, latestClosedTradingDay, batch.size(), quotes.size());
            }
        }
        if (failedStocks > 0) throw new IllegalStateException("QUOTE_SYNC_INCOMPLETE:failedStocks=" + failedStocks);
    }

    private List<StockQuote> downloadQuotes(List<Stock> targets, LocalDate from, LocalDate through, Map<String, String> failures) {
        List<String> symbols = targets.stream().map(Stock::getSymbol).toList();
        Set<String> receivedBars = new HashSet<>();
        List<StockQuote> quotes = new ArrayList<>();
        alpacaService.readHistoricalBars(symbols, from, through, page -> {
            if (page == null || page.getBars() == null) throw new IllegalStateException("ALPACA_BARS_PAGE_MISSING");
            for (Stock stock : targets) {
                if (failures.containsKey(stock.getFigi())) continue;
                try {
                    List<AlpacaBar> bars = page.getBars().get(stock.getSymbol());
                    if (bars == null) continue;
                    for (AlpacaBar bar : bars) {
                        if (bar == null || bar.getTimestamp() == null)
                            throw new IllegalStateException("ALPACA_BAR_DATE_MISMATCH:" + stock.getSymbol());
                        LocalDate actualDate = bar.getTimestamp().atZone(NEW_YORK).toLocalDate();
                        if (actualDate.isBefore(from) || actualDate.isAfter(through))
                            throw new IllegalStateException("ALPACA_BAR_DATE_MISMATCH:" + stock.getSymbol());
                        StockQuote quote = stockQuoteConverter.convertAlpacaBarToStockQuote(bar, stock.getFigi(), actualDate);
                        if (quote.getVolume() == null || !QuoteNormalizer.validForStorage(quote))
                            throw new IllegalStateException("INVALID_ALPACA_OHLCV:" + stock.getFigi() + ":" + actualDate);
                        if (!receivedBars.add(stock.getFigi() + ":" + actualDate))
                            throw new IllegalStateException("DUPLICATE_ALPACA_BAR:" + stock.getSymbol() + ":" + actualDate);
                        quotes.add(quote);
                    }
                } catch (RuntimeException ex) {
                    String reason = ex.getClass().getSimpleName() + ":" + ex.getMessage();
                    failures.put(stock.getFigi(), reason.substring(0, Math.min(reason.length(), 3000)));
                    log.error("Alpaca stock data failed | figi={} symbol={}", stock.getFigi(), stock.getSymbol(), ex);
                }
            }
        });
        quotes.removeIf(quote -> failures.containsKey(quote.getFigi()));
        return quotes;
    }
}
