package com.ykm.stock.analytics.scheduling;

import com.google.common.collect.Lists;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecTickerClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.jpa.StockConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.finnhub.response.Symbol;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.FinnhubService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.service.stock.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Massive 主檔一次批量取得；結合 Finnhub 證券類型與 SEC Ticker／submissions.zip 的 CIK 與 4 碼 SIC 同步主檔。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.STOCK_DAILY)
public class StockDailyTask implements Job {
    private static final int STOCK_BATCH_SIZE = 200;
    private final MassiveService massiveService;
    private final FinnhubService finnhubService;
    private final SecTickerClient secTickerClient;
    private final SecSubmissionsArchive secSubmissionsArchive;
    private final StockConverter stockConverter;
    private final StockService stockService;
    private final SecurityTypePolicy securityTypePolicy;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void execute(JobExecutionContext context) {
        List<MassiveTickerDetails> downloaded = massiveService.getAllStockTickers();
        Map<String, MassiveTickerDetails> current = new LinkedHashMap<>();
        for (MassiveTickerDetails ticker : downloaded) {
            if (StringUtils.isBlank(ticker.getCompositeFigi()) || StringUtils.isBlank(ticker.getTicker())) continue;
            if (!securityTypePolicy.supportsStockMaster(ticker.getType())) continue;
            String figi = ticker.getCompositeFigi().trim();
            MassiveTickerDetails previous = current.putIfAbsent(figi, ticker);
            if (previous != null && !previous.getTicker().equals(ticker.getTicker()))
                throw new IllegalStateException("AMBIGUOUS_MASTER_FIGI:" + figi);
        }
        if (current.isEmpty()) throw new IllegalStateException("EMPTY_STOCK_MASTER");
        Map<String, String> finnhubTypes = resolveFinnhubTypes(current, finnhubService.getStockSymbols("US"));
        Map<String, String> secCiks = secTickerClient.getTickerCiks();
        Set<String> resolvedCiks = new LinkedHashSet<>();
        for (MassiveTickerDetails ticker : current.values()) {
            String cik = secCiks.get(SecTickerClient.tickerKey(ticker.getTicker()));
            if (cik != null) resolvedCiks.add(cik);
        }
        Map<String, String> secSics = resolveSecSics(resolvedCiks);

        // 外部主檔來源均已取得後才開始交易；既有股票的 tracked 開關絕不覆寫。
        transactionTemplate.executeWithoutResult(status -> {
            Map<String, Stock> existing = stockService.findAllStocks().stream()
                    .collect(Collectors.toMap(Stock::getFigi, stock -> stock));
            for (List<MassiveTickerDetails> batch : Lists.partition(List.copyOf(current.values()), STOCK_BATCH_SIZE)) {
                Set<String> newFigis = new LinkedHashSet<>();
                List<Stock> stocks = batch.stream().map(ticker -> {
                    String figi = ticker.getCompositeFigi().trim();
                    Stock stock = existing.get(figi);
                    if (stock == null) {
                        stock = new Stock();
                        stock.setFigi(figi);
                        stock.setTracked(true);
                        existing.put(figi, stock);
                        newFigis.add(figi);
                    }
                    String resolvedCik = secCiks.get(SecTickerClient.tickerKey(ticker.getTicker()));
                    stockConverter.updateFromMassive(ticker, stock);
                    stock.setStockType(finnhubTypes.get(figi));
                    stock.setCik(resolvedCik);
                    stock.setSic(resolvedCik == null ? null : secSics.get(resolvedCik));
                    return stock;
                }).toList();
                stockService.updateStocks(stocks);
                if (!newFigis.isEmpty()) stockService.updateStockStatuses(newFigis);
            }
            List<Stock> inactive = existing.values().stream()
                    .filter(stock -> stock.getActiveStatus() == ActiveStatus.ACTIVE && !current.containsKey(stock.getFigi()))
                    .toList();
            for (Stock stock : inactive) stock.setActiveStatus(ActiveStatus.INACTIVE);
            for (List<Stock> batch : Lists.partition(inactive, STOCK_BATCH_SIZE)) stockService.updateStocks(batch);
        });
    }

    private Map<String, String> resolveSecSics(Collection<String> ciks) {
        if (ciks.isEmpty()) return Map.of();
        Map<String, String> sics = new LinkedHashMap<>();
        try (SecSubmissionsArchive.Snapshot snapshot = secSubmissionsArchive.prepare()) {
            if (snapshot == null) return Map.of();
            for (String cik : ciks) {
                snapshot.readSic(cik).ifPresent(sic -> sics.put(cik, sic));
            }
        }
        return sics;
    }

    /** 以同一證券的 Composite FIGI 為主；僅同股份類別、代碼與 MIC 全相符時補分類。 */
    private Map<String, String> resolveFinnhubTypes(Map<String, MassiveTickerDetails> current, List<Symbol> symbols) {
        if (symbols == null || symbols.isEmpty()) throw new IllegalStateException("EMPTY_FINNHUB_STOCK_TYPES");
        Map<String, Symbol> byFigi = new LinkedHashMap<>();
        Map<String, Symbol> byShareClassAndVenue = new LinkedHashMap<>();
        for (Symbol symbol : symbols) {
            if (StringUtils.isBlank(symbol.getType())) continue;
            if (StringUtils.isNotBlank(symbol.getFigiComposite()))
                byFigi.putIfAbsent(symbol.getFigiComposite().trim(), symbol);
            if (StringUtils.isNotBlank(symbol.getShareClassFIGI()) && StringUtils.isNotBlank(symbol.getMic())
                    && StringUtils.isNotBlank(symbol.getSymbol()))
                byShareClassAndVenue.putIfAbsent(symbol.getShareClassFIGI().trim() + "|" + symbol.getMic().trim()
                        + "|" + symbol.getSymbol().trim(), symbol);
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, MassiveTickerDetails> entry : current.entrySet()) {
            MassiveTickerDetails ticker = entry.getValue();
            Symbol matched = byFigi.get(entry.getKey());
            if (matched == null && StringUtils.isNotBlank(ticker.getShareClassFigi())
                    && StringUtils.isNotBlank(ticker.getPrimaryExchange()))
                matched = byShareClassAndVenue.get(ticker.getShareClassFigi().trim() + "|"
                        + ticker.getPrimaryExchange().trim() + "|" + ticker.getTicker().trim());
            result.put(entry.getKey(), matched == null ? null : matched.getType().trim());
        }
        List<String> unresolved = result.entrySet().stream().filter(entry -> entry.getValue() == null)
                .map(Map.Entry::getKey).toList();
        if (!unresolved.isEmpty())
            log.warn("Finnhub stock type unavailable | count={} figiSamples={}", unresolved.size(),
                    unresolved.subList(0, Math.min(10, unresolved.size())));
        return result;
    }
}
