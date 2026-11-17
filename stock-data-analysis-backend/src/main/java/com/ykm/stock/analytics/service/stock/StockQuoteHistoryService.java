package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.key.StockQuoteId;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.calculator.QuoteNormalizer;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class StockQuoteHistoryService {
    private final StockQuoteRepository stockQuoteRepository;
    private final StockStatusRepository stockStatusRepository;
    private final MarketStatusService marketStatusService;
    private final EntityManager entityManager;

    public List<StockQuote> getStoredQuotes(Collection<String> figis, LocalDate date) {
        return figis.isEmpty() ? List.of() : stockQuoteRepository.findStoredQuotes(figis, date);
    }

    public List<StockQuote> getStoredQuotes(Collection<String> figis, LocalDate from, LocalDate through) {
        return figis.isEmpty() ? List.of() : stockQuoteRepository.findStoredQuotes(figis, from, through);
    }

    /** 以 FIGI + 日期合併保存。 */
    @Transactional
    public void updateStockQuote(List<StockQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return;
        Instant observedAt = Instant.now();
        for (StockQuote quote : quotes) {
            if (quote == null || quote.getFigi() == null || quote.getFigi().isBlank()
                    || quote.getQuoteDate() == null || !QuoteNormalizer.validForStorage(quote))
                throw new IllegalArgumentException("INVALID_STOCK_QUOTE");
            if (quote.getObservedAt() == null) quote.setObservedAt(observedAt);
        }
        // 先按固定 FIGI 順序鎖定狀態，避免並行寫入同一批行情。
        List<String> figis = quotes.stream().map(StockQuote::getFigi).distinct().sorted().toList();
        if (stockStatusRepository.findAllByIdForUpdate(figis).size() != figis.size())
            throw new IllegalStateException("QUOTE_STOCK_STATUS_MISSING");
        // 一次讀取本批範圍，避免 assigned ID 的 merge 為每筆行情另外 SELECT。
        LocalDate from = quotes.stream().map(StockQuote::getQuoteDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate through = quotes.stream().map(StockQuote::getQuoteDate).max(LocalDate::compareTo).orElseThrow();
        Map<StockQuoteId, StockQuote> existing = stockQuoteRepository.findStoredQuotes(figis, from, through).stream()
                .collect(Collectors.toMap(quote -> new StockQuoteId(quote.getFigi(), quote.getQuoteDate()), Function.identity()));
        for (StockQuote quote : quotes) {
            StockQuoteId id = new StockQuoteId(quote.getFigi(), quote.getQuoteDate());
            StockQuote previous = existing.get(id);
            if (previous == null) {
                entityManager.persist(quote);
                existing.put(id, quote);
            } else {
                previous.setOpenPrice(quote.getOpenPrice());
                previous.setHighPrice(quote.getHighPrice());
                previous.setLowPrice(quote.getLowPrice());
                previous.setClosePrice(quote.getClosePrice());
                previous.setVolume(quote.getVolume());
                previous.setObservedAt(quote.getObservedAt());
                previous.setDataSource(quote.getDataSource());
            }
        }
        entityManager.flush();
    }

    public StockQuote findFirstByFigiOrderByQuoteDateDesc(String figi) {
        return stockQuoteRepository.findFirstByFigiOrderByQuoteDateDesc(figi);
    }

    public StockQuote getCurrentQuote(String figi) {
        return stockQuoteRepository.findByFigiAndQuoteDate(figi, marketStatusService.getLastClosedTradingDay());
    }

    public List<StockQuote> getLatestQuotes(String figi, int period) {
        return period <= 0 ? List.of() : stockQuoteRepository.findByFigiOrderByQuoteDateDesc(figi, Limit.of(period));
    }

    public List<StockQuote> getQuotes(String figi, LocalDate quoteDate, int period) {
        return period <= 0 ? List.of() : stockQuoteRepository.findByFigiAndQuoteDateLessThanEqualOrderByQuoteDateDesc(
                figi, quoteDate, Limit.of(period));
    }

    public List<StockQuote> getQuotesBetweenAsc(String figi, LocalDate from, LocalDate to) {
        return stockQuoteRepository.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(figi, from, to);
    }
}
