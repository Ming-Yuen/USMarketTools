package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.converter.jpa.StockQuoteConverterImpl;
import com.ykm.stock.analytics.model.dto.alpaca.AlpacaBar;
import com.ykm.stock.analytics.model.dto.alpaca.AlpacaBarsResponse;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate;
import com.ykm.stock.analytics.service.stock.AlpacaService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.StockQuoteHistoryService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockQuoteDailyTaskTests {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private final StockService stocks = mock(StockService.class);
    private final StockQuoteHistoryService history = mock(StockQuoteHistoryService.class);
    private final MarketStatusService market = mock(MarketStatusService.class);
    private final AlpacaService alpaca = mock(AlpacaService.class);
    private final Stock stock = new Stock();
    private final StockStatus status = new StockStatus();
    private final StockQuoteDailyTask task = new StockQuoteDailyTask(stocks, history, new StockQuoteConverterImpl(),
            market, alpaca, new TransactionTemplate(new NoOpTransactionManager()));

    @BeforeEach
    void setup() {
        stock.setFigi("F1");
        stock.setSymbol("ABC");
        status.setFigi("F1");
        when(market.getLastClosedTradingDay()).thenReturn(DAY);
        when(stocks.findActiveStockQuoteDates(DAY)).thenAnswer(call -> List.of(new ActiveStockQuoteDate(stock, status.getQuoteDate())));
        when(stocks.findStockStatuses(anyCollection())).thenReturn(List.of(status));
    }

    /** Scenario：首次從 config 起始日補歷史；既有股票從水位次日補至最近收市日。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2016-01-01", "2021-12-31", "2026-10-13", "2026-10-15"})
    void downloadsFromWatermarkThroughLatestClosedDay(String previousDate) {
        status.setQuoteDate(previousDate == null ? null : LocalDate.parse(previousDate));
        LocalDate from = previousDate == null ? LocalDate.of(2016, 1, 1) : LocalDate.parse(previousDate).plusDays(1);
        if (from.isBefore(LocalDate.of(2016, 1, 1))) from = LocalDate.of(2016, 1, 1);
        respond(page(DAY));
        task.execute(null);
        verify(alpaca).readHistoricalBars(eq(List.of("ABC")), eq(from), eq(DAY), any());
        verify(history).updateStockQuote(argThat(rows -> rows.size() == 1 && DAY.equals(rows.getFirst().getQuoteDate())
                && "F1".equals(rows.getFirst().getFigi()) && "ALPACA".equals(rows.getFirst().getDataSource())));
        verify(history, never()).getStoredQuotes(anyCollection(), any(), any());
        assertThat(status.getQuoteDate()).isEqualTo(DAY);
    }

    /** Scenario：停跑多個交易日後，所有返回的歷史 bar 都必須入庫。 */
    @Test
    void savesEveryTradingDaySinceWatermark() {
        status.setQuoteDate(LocalDate.of(2026, 10, 13));
        respond(page(LocalDate.of(2026, 10, 14)), page(LocalDate.of(2026, 10, 15)), page(DAY));
        task.execute(null);
        verify(history).updateStockQuote(argThat(rows -> rows.size() == 3
                && rows.stream().map(StockQuote::getQuoteDate).toList().equals(
                        List.of(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), DAY))));
        assertThat(status.getQuoteDate()).isEqualTo(DAY);
    }

    /** Scenario：最新交易日沒有 bar，只推進至實際儲存的最後日期。 */
    @Test
    void advancesOnlyToLastSavedBarDate() {
        status.setQuoteDate(LocalDate.of(2026, 10, 13));
        respond(page(LocalDate.of(2026, 10, 14)), page(LocalDate.of(2026, 10, 15)));
        task.execute(null);
        assertThat(status.getQuoteDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    /** Scenario：首次同步時，配置起點不必固定為 2016 年。 */
    @Test
    void initialDownloadRespectsConfiguredStartDate() {
        ReflectionTestUtils.setField(task, "historyStartDate", LocalDate.of(2020, 1, 1));
        respond(page(DAY));
        task.execute(null);
        verify(alpaca).readHistoricalBars(eq(List.of("ABC")), eq(LocalDate.of(2020, 1, 1)), eq(DAY), any());
    }

    /** Scenario：不同股票有不同水位，分組請求各自所需的日期，不反覆下載其他股票的歷史。 */
    @Test
    void groupsStocksByWatermark() {
        status.setQuoteDate(DAY.minusDays(2));
        Stock second = new Stock();
        second.setFigi("F2");
        second.setSymbol("XYZ");
        StockStatus secondStatus = new StockStatus();
        secondStatus.setFigi("F2");
        secondStatus.setQuoteDate(DAY.minusDays(1));
        when(stocks.findActiveStockQuoteDates(DAY)).thenReturn(List.of(
                new ActiveStockQuoteDate(stock, status.getQuoteDate()),
                new ActiveStockQuoteDate(second, secondStatus.getQuoteDate())));
        when(stocks.findStockStatuses(anyCollection())).thenAnswer(call -> {
            Collection<String> figis = call.getArgument(0);
            return List.of(status, secondStatus).stream().filter(s -> figis.contains(s.getFigi())).toList();
        });
        doAnswer(call -> {
            List<String> symbols = call.getArgument(0);
            Consumer<AlpacaBarsResponse> consumer = call.getArgument(3);
            consumer.accept(page(symbols.getFirst(), DAY));
            return null;
        }).when(alpaca).readHistoricalBars(anyList(), any(), any(), any());
        task.execute(null);
        verify(alpaca).readHistoricalBars(eq(List.of("ABC")), eq(DAY.minusDays(1)), eq(DAY), any());
        verify(alpaca).readHistoricalBars(eq(List.of("XYZ")), eq(DAY), eq(DAY), any());
        assertThat(status.getQuoteDate()).isEqualTo(DAY);
        assertThat(secondStatus.getQuoteDate()).isEqualTo(DAY);
    }

    /** Scenario：儲存失敗不推進水位；重跑仍從失敗前的水位補抓。 */
    @Test
    void failedPersistenceRetainsDateUntilSuccessfulRerun() {
        status.setQuoteDate(DAY.minusDays(3));
        respond(page(DAY));
        doThrow(new IllegalStateException("QUOTE_SAVE_FAILED")).doNothing().when(history).updateStockQuote(any());
        assertThatThrownBy(() -> task.execute(null)).hasMessage("QUOTE_SAVE_FAILED");
        assertThat(status.getQuoteDate()).isEqualTo(DAY.minusDays(3));
        verify(stocks, never()).updateStockStatuses(anyList());
        task.execute(null);
        assertThat(status.getQuoteDate()).isEqualTo(DAY);
        verify(alpaca, times(2)).readHistoricalBars(eq(List.of("ABC")), eq(DAY.minusDays(2)), eq(DAY), any());
    }

    /** Scenario：沒返回價格，不寫入零值或假裝已完成。 */
    @Test
    void emptySourceDoesNotAdvanceDate() {
        AlpacaBarsResponse empty = new AlpacaBarsResponse();
        empty.setBars(Map.of());
        respond(empty);
        task.execute(null);
        verifyNoInteractions(history);
        assertThat(status.getQuoteDate()).isNull();
    }

    /** Scenario：來源返回其他日期或重複日線，該股拒絕保存並記錄原因。 */
    @Test
    void rejectsWrongDateAndDuplicatePage() {
        status.setQuoteDate(DAY.minusDays(1));
        respond(page(DAY.minusDays(1)));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("QUOTE_SYNC_INCOMPLETE:failedStocks=1");
        verify(stocks).recordQuoteFailures(argThat(errors -> errors.getOrDefault("F1", "").contains("ALPACA_BAR_DATE_MISMATCH")));
        respond(page(DAY), page(DAY));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("QUOTE_SYNC_INCOMPLETE:failedStocks=1");
        verify(stocks).recordQuoteFailures(argThat(errors -> errors.getOrDefault("F1", "").contains("DUPLICATE_ALPACA_BAR")));
        verifyNoInteractions(history);
    }

    /** Scenario：兩個active FIGI同名不能將同一日線複製給不同證券。 */
    @Test
    void ambiguousCurrentSymbolIsRejectedBeforeDownload() {
        Stock second = new Stock();
        second.setFigi("F2");
        second.setSymbol("ABC");
        when(stocks.findActiveStockQuoteDates(DAY)).thenReturn(List.of(
                new ActiveStockQuoteDate(stock, null), new ActiveStockQuoteDate(second, null)));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("QUOTE_SYNC_INCOMPLETE:failedStocks=2");
        verify(stocks).recordQuoteFailures(argThat(errors -> errors.size() == 2 && errors.containsKey("F1") && errors.containsKey("F2")));
        verifyNoInteractions(alpaca, history);
    }

    @Test
    void invalidStockDoesNotDiscardGoodStockOrStopNextGroup() {
        status.setQuoteDate(DAY.minusDays(1));
        Stock good = new Stock();
        good.setFigi("F2");
        good.setSymbol("GOOD");
        Stock later = new Stock();
        later.setFigi("F3");
        later.setSymbol("LATER");
        StockStatus goodStatus = new StockStatus();
        goodStatus.setFigi("F2");
        StockStatus laterStatus = new StockStatus();
        laterStatus.setFigi("F3");
        when(stocks.findActiveStockQuoteDates(DAY)).thenReturn(List.of(
                new ActiveStockQuoteDate(stock, DAY.minusDays(1)),
                new ActiveStockQuoteDate(good, DAY.minusDays(1)),
                new ActiveStockQuoteDate(later, DAY.minusDays(2))));
        when(stocks.findStockStatuses(anyCollection())).thenAnswer(call -> {
            Collection<String> figis = call.getArgument(0);
            return List.of(status, goodStatus, laterStatus).stream().filter(s -> figis.contains(s.getFigi())).toList();
        });
        doAnswer(call -> {
            List<String> symbols = call.getArgument(0);
            Consumer<AlpacaBarsResponse> consumer = call.getArgument(3);
            if (symbols.contains("ABC")) {
                consumer.accept(page("ABC", DAY));
                consumer.accept(page("GOOD", DAY));
                consumer.accept(page("ABC", DAY)); // A later duplicate invalidates all F1 bars.
            } else consumer.accept(page("LATER", DAY));
            return null;
        }).when(alpaca).readHistoricalBars(anyList(), any(), any(), any());
        assertThatThrownBy(() -> task.execute(null)).hasMessage("QUOTE_SYNC_INCOMPLETE:failedStocks=1");
        verify(history, times(2)).updateStockQuote(argThat(rows -> rows.size() == 1 && !"F1".equals(rows.getFirst().getFigi())));
        verify(stocks).recordQuoteFailures(argThat(errors -> errors.containsKey("F1")));
        assertThat(status.getQuoteDate()).isEqualTo(DAY.minusDays(1));
        assertThat(goodStatus.getQuoteDate()).isEqualTo(DAY);
        assertThat(laterStatus.getQuoteDate()).isEqualTo(DAY);
    }

    @Test
    void providerFailureStopsJobWithoutClaimingStockDataErrors() {
        doThrow(new IllegalStateException("PROVIDER_UNAVAILABLE")).when(alpaca).readHistoricalBars(anyList(), any(), any(), any());
        assertThatThrownBy(() -> task.execute(null)).hasMessage("PROVIDER_UNAVAILABLE");
        verifyNoInteractions(history);
        verify(stocks, never()).recordQuoteFailures(argThat(errors -> !errors.isEmpty()));
        assertThat(status.getQuoteDate()).isNull();
    }
    @Test
    void ambiguousSymbolsDoNotBlockUnrelatedStock() {
        Stock conflicting = new Stock();
        conflicting.setFigi("F2");
        conflicting.setSymbol("ABC");
        Stock good = new Stock();
        good.setFigi("F3");
        good.setSymbol("GOOD");
        StockStatus goodStatus = new StockStatus();
        goodStatus.setFigi("F3");
        when(stocks.findActiveStockQuoteDates(DAY)).thenReturn(List.of(
                new ActiveStockQuoteDate(stock, null), new ActiveStockQuoteDate(conflicting, null),
                new ActiveStockQuoteDate(good, null)));
        when(stocks.findStockStatuses(anyCollection())).thenReturn(List.of(goodStatus));
        respond(page("GOOD", DAY));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("QUOTE_SYNC_INCOMPLETE:failedStocks=2");
        verify(alpaca).readHistoricalBars(eq(List.of("GOOD")), any(), eq(DAY), any());
        verify(history).updateStockQuote(argThat(rows -> rows.size() == 1 && "F3".equals(rows.getFirst().getFigi())));
        assertThat(goodStatus.getQuoteDate()).isEqualTo(DAY);
    }
    private void respond(AlpacaBarsResponse... pages) {
        doAnswer(call -> {
            Consumer<AlpacaBarsResponse> consumer = call.getArgument(3);
            for (AlpacaBarsResponse page : pages) consumer.accept(page);
            return null;
        }).when(alpaca).readHistoricalBars(anyList(), any(), any(), any());
    }

    private AlpacaBarsResponse page(LocalDate date) { return page("ABC", date); }

    private AlpacaBarsResponse page(String symbol, LocalDate date) {
        AlpacaBar bar = new AlpacaBar();
        bar.setTimestamp(date.atStartOfDay(ZoneId.of("America/New_York")).toInstant());
        bar.setOpen(new BigDecimal("10"));
        bar.setHigh(new BigDecimal("12"));
        bar.setLow(new BigDecimal("9"));
        bar.setClose(new BigDecimal("11"));
        bar.setVolume(new BigDecimal("100"));
        AlpacaBarsResponse result = new AlpacaBarsResponse();
        result.setBars(Map.of(symbol, List.of(bar)));
        return result;
    }
}
