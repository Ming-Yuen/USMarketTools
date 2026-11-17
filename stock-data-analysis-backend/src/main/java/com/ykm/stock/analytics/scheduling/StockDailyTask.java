package com.ykm.stock.analytics.scheduling;

import com.google.common.collect.Lists;
import com.ykm.stock.analytics.client.SecTickerClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.converter.jpa.StockConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.dto.finnhub.response.CompanyProfile2;
import com.ykm.stock.analytics.model.dto.finnhub.response.Symbol;
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
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Massive 主檔一次批量取得；尚無 Finnhub 產業的營運公司逐股補取分類。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.STOCK_DAILY)
public class StockDailyTask implements Job {
    private static final int STOCK_BATCH_SIZE = 200;
    private final MassiveService massiveService;
    private final FinnhubService finnhubService;
    private final SecTickerClient secTickerClient;
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
        // 未有 Finnhub 分類的營運公司才查簡介，與富途產業是否存在無關；網路呼叫不放入交易。
        Map<String, String> finnhubIndustries = resolveFinnhubIndustries(current, finnhubTypes);
        // 三個主檔來源均已取得後才開始交易；公司簡介失敗僅影響對應股票的分類。
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
                        existing.put(figi, stock);
                        newFigis.add(figi);
                    }
                    String resolvedCik = secCiks.get(SecTickerClient.tickerKey(ticker.getTicker()));
                    stockConverter.updateFromMassive(ticker, stock);
                    stock.setStockType(finnhubTypes.get(figi));
                    if (StringUtils.isBlank(stock.getFinnhubIndustry()) && finnhubIndustries.containsKey(figi))
                        stock.setFinnhubIndustry(finnhubIndustries.get(figi));
                    stock.setCik(resolvedCik); // 明確清除未知值，不受 MapStruct IGNORE 的保留語意影響。
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

    /** Finnhub 詳細類型限定營運公司；已有 Finnhub 產業值不重複查詢。 */
    private Map<String, String> resolveFinnhubIndustries(Map<String, MassiveTickerDetails> current, Map<String, String> finnhubTypes) {
        Map<String, Stock> existing = stockService.findAllStocks().stream()
                .collect(Collectors.toMap(Stock::getFigi, stock -> stock));
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, MassiveTickerDetails> entry : current.entrySet()) {
            String figi = entry.getKey();
            if (!SecurityTypePolicy.isOperatingCompanyType(finnhubTypes.get(figi))) continue;
            Stock stock = existing.get(figi);
            if (stock != null && StringUtils.isNotBlank(stock.getFinnhubIndustry())) continue;
            String symbol = entry.getValue().getTicker().trim();
            try {
                CompanyProfile2 profile = finnhubService.getCompanyProfile2(symbol);
                if (profile == null || !symbol.equalsIgnoreCase(profile.getTicker())
                        || StringUtils.isBlank(profile.getFinnhubIndustry())) continue;
                result.put(figi, profile.getFinnhubIndustry().trim());
            } catch (RestClientException e) {
                log.warn("Finnhub industry profile failed | figi={} symbol={} cause={}", figi, symbol,
                        e.getClass().getSimpleName());
            }
        }
        return result;
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
