package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockQuoteHistoryServiceObservationTests {
    private final StockQuoteRepository repository = mock(StockQuoteRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final StockQuoteHistoryService service = new StockQuoteHistoryService(repository, statuses, mock(MarketStatusService.class), entityManager);

    @BeforeEach
    void setUp() {
        StockStatus status = new StockStatus();
        status.setFigi("F1");
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
    }

    /** Scenario：新行情寫入實際UTC觀測時間，不誤填為歷史交易日。 */
    @Test
    void firstPersistenceRecordsUtcObservationTime() {
        StockQuote quote = quote();
        Instant before = Instant.now();
        service.updateStockQuote(List.of(quote));
        assertThat(quote.getObservedAt()).isBetween(before, Instant.now());
        verify(entityManager).persist(quote);
        verify(entityManager).flush();
    }

    /** Scenario：原已知觀測時間保留，不因資料庫重存而改成今天。 */
    @Test
    void knownObservationTimeIsPreserved() {
        StockQuote quote = quote();
        Instant observed = Instant.parse("2026-10-16T23:00:00Z");
        quote.setObservedAt(observed);
        service.updateStockQuote(List.of(quote));
        assertThat(quote.getObservedAt()).isEqualTo(observed);
    }

    @Test
    void invalidPriceDoesNotReachRepository() {
        StockQuote quote = quote();
        quote.setClosePrice(new BigDecimal("-1"));
        assertThatThrownBy(() -> service.updateStockQuote(List.of(quote))).hasMessage("INVALID_STOCK_QUOTE");
        verifyNoInteractions(repository);
    }

    private StockQuote quote() {
        StockQuote quote = new StockQuote();
        quote.setFigi("F1");
        quote.setQuoteDate(LocalDate.of(2026, 10, 16));
        quote.setOpenPrice(BigDecimal.TEN);
        quote.setHighPrice(BigDecimal.TEN);
        quote.setLowPrice(BigDecimal.TEN);
        quote.setClosePrice(BigDecimal.TEN);
        quote.setVolume(BigDecimal.TEN);
        return quote;
    }
}
