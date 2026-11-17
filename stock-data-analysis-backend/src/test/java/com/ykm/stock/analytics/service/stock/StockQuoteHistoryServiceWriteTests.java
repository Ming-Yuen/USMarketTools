package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockQuoteHistoryServiceWriteTests {
    private static final LocalDate DAY = LocalDate.of(2026, 2, 5);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final StockQuoteHistoryService service = new StockQuoteHistoryService(quotes, statuses, mock(MarketStatusService.class), entityManager);

    /** 更正行情時先鎖定狀態，保留行情水位。 */
    @Test
    void closingPriceCorrectionLocksBeforeReadingAndPreservesQuoteWatermark() {
        StockStatus status = status("F1");
        status.setQuoteDate(DAY.plusDays(4));
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        when(quotes.findStoredQuotes(List.of("F1"), DAY, DAY)).thenReturn(List.of(quote("F1", DAY, "10")));
        StockQuote corrected = quote("F1", DAY, "11");

        service.updateStockQuote(List.of(corrected));

        assertThat(status.getQuoteDate()).isEqualTo(DAY.plusDays(4));
        InOrder order = inOrder(statuses, quotes, entityManager);
        order.verify(statuses).findAllByIdForUpdate(List.of("F1"));
        order.verify(quotes).findStoredQuotes(List.of("F1"), DAY, DAY);
        order.verify(entityManager).flush();
        verify(statuses, never()).saveAll(any());
    }

    /** Scenario：收盤價相同時仍更新其他行情欄位。 */
    @Test
    void updatesStoredBarEvenWhenTheCloseIsUnchanged() {
        StockStatus status = status("F1");
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        when(quotes.findStoredQuotes(List.of("F1"), DAY, DAY)).thenReturn(List.of(quote("F1", DAY, "10")));
        StockQuote incoming = quote("F1", DAY, "10.00000000");
        incoming.setOpenPrice(new BigDecimal("9"));
        incoming.setLowPrice(new BigDecimal("9"));
        incoming.setVolume(new BigDecimal("20"));
        incoming.setDataSource("ALPACA");

        service.updateStockQuote(List.of(incoming));

        verify(entityManager).flush();
    }

    /** Scenario：新交易日只查本批範圍。 */
    @Test
    void newTradingDayReadsOnlyTheBatchRange() {
        StockStatus status = status("F1");
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(status));
        StockQuote incoming = quote("F1", DAY, "10");

        service.updateStockQuote(List.of(incoming));

        verify(quotes).findStoredQuotes(List.of("F1"), DAY, DAY);
        verify(entityManager).flush();
    }

    /** Scenario：同批多股票與多日期，只批量讀一次舊行情。 */
    @Test
    void multiStockBatchReadsExistingQuotesOnce() {
        StockStatus first = status("F1");
        StockStatus second = status("F2");
        when(statuses.findAllByIdForUpdate(List.of("F1", "F2"))).thenReturn(List.of(first, second));
        LocalDate earlier = DAY.minusDays(2);
        when(quotes.findStoredQuotes(anyCollection(), eq(earlier), eq(DAY))).thenReturn(List.of(
                quote("F1", earlier, "10"), quote("F1", DAY, "10"), quote("F2", DAY, "20")));

        service.updateStockQuote(List.of(quote("F2", DAY, "20.00"), quote("F1", DAY, "12"), quote("F1", earlier, "11")));

        verify(quotes, times(1)).findStoredQuotes(anyCollection(), eq(earlier), eq(DAY));
    }

    /** Scenario：缺少 stock_status 時停止保存行情。 */
    @Test
    void missingStatusStopsBeforeSavingQuotes() {
        assertThatThrownBy(() -> service.updateStockQuote(List.of(quote("F1", DAY, "10"))))
                .hasMessage("QUOTE_STOCK_STATUS_MISSING");
        verifyNoInteractions(quotes);
    }

    @Test
    void emptyAndInvalidInputDoNotAcquireLocksOrWrite() {
        service.updateStockQuote(List.of());
        service.updateStockQuote(null);
        StockQuote invalid = quote("F1", DAY, "10");
        invalid.setClosePrice(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.updateStockQuote(List.of(invalid))).hasMessage("INVALID_STOCK_QUOTE");
        verifyNoInteractions(statuses, quotes);
    }

    private static StockStatus status(String figi) {
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        return status;
    }

    private static StockQuote quote(String figi, LocalDate day, String close) {
        StockQuote quote = new StockQuote();
        quote.setFigi(figi);
        quote.setQuoteDate(day);
        BigDecimal price = new BigDecimal(close);
        quote.setOpenPrice(price);
        quote.setHighPrice(price);
        quote.setLowPrice(price);
        quote.setClosePrice(price);
        quote.setVolume(BigDecimal.TEN);
        return quote;
    }
}
