package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecTickerClient;
import com.ykm.stock.analytics.converter.jpa.StockConverterImpl;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.finnhub.response.Symbol;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.market.FinnhubService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StockDailyTaskTests {
    private final SecTickerClient sec = mock(SecTickerClient.class);
    private final SecSubmissionsArchive submissionsArchive = mock(SecSubmissionsArchive.class);
    private final SecSubmissionsArchive.Snapshot submissionsSnapshot = mock(SecSubmissionsArchive.Snapshot.class);
    private final MassiveService massive = mock(MassiveService.class);
    private final FinnhubService finnhub = mock(FinnhubService.class);
    private final StockService stocks = mock(StockService.class);
    private final StockDailyTask task = new StockDailyTask(massive, finnhub, sec, submissionsArchive,
            new StockConverterImpl(), stocks, new SecurityTypePolicy(), new TransactionTemplate(new NoOpTransactionManager()));

    @BeforeEach
    void defaults() {
        when(stocks.findAllStocks()).thenReturn(List.of());
        when(sec.getTickerCiks()).thenReturn(Map.of("ABC", "0000123456"));
        when(submissionsArchive.prepare()).thenReturn(submissionsSnapshot);
        when(submissionsSnapshot.readSic(anyString())).thenReturn(Optional.empty());
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(finnhubSymbol("F1", "ABC", "Common Stock")));
    }

    /** Scenario：FIGI 為主鍵，新股票預設 tracked = true，並從 submissions.zip 寫入 4 碼 SIC，不再逐股打 Finnhub profile2。 */
    @Test
    void createsMasterWithDefaultTrackedTrueAndSecSicWithoutPerTickerRequests() {
        when(submissionsSnapshot.readSic("0000123456")).thenReturn(Optional.of("6021"));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1
                && "F1".equals(rows.getFirst().getFigi())
                && "ABC".equals(rows.getFirst().getSymbol())
                && "USD".equals(rows.getFirst().getCurrency())
                && "0000123456".equals(rows.getFirst().getCik())
                && "6021".equals(rows.getFirst().getSic())
                && rows.getFirst().isTracked()));
        verify(stocks).updateStockStatuses(Set.of("F1"));
        verify(massive).getAllStockTickers();
        verifyNoMoreInteractions(massive);
    }

    /** Scenario：使用者手動設為 tracked = false 的既有股票，在主檔更新時絕不被覆寫回 true。 */
    @Test
    void masterSyncNeverOverwritesExistingUntrackedFlag() {
        Stock existing = stock("F1", "ABC");
        existing.setTracked(false);
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));

        task.execute(null);

        assertThat(existing.isTracked()).isFalse();
        verify(stocks).updateStocks(List.of(existing));
        verify(stocks, never()).updateStockStatuses(anySet());
    }

    @Test
    void renameKeepsFigiAndStatus() {
        Stock existing = stock("F1", "OLD");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "NEW")));
        task.execute(null);
        assertThat(existing.getSymbol()).isEqualTo("NEW");
        verify(stocks).updateStocks(List.of(existing));
        verify(stocks, never()).updateStockStatuses(anySet());
    }

    @Test
    void completeMasterDeactivatesAbsentStock() {
        Stock present = stock("F1", "ABC");
        Stock absent = stock("F2", "OLD");
        when(stocks.findAllStocks()).thenReturn(List.of(present, absent));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        task.execute(null);
        assertThat(absent.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
        verify(stocks).updateStocks(List.of(absent));
    }

    @Test
    void missingMasterNeverMutatesDatabase() {
        when(massive.getAllStockTickers()).thenReturn(List.of());
        assertThatThrownBy(() -> task.execute(null)).hasMessage("EMPTY_STOCK_MASTER");
        verifyNoInteractions(stocks);
        when(massive.getAllStockTickers()).thenThrow(new IllegalStateException("SOURCE_FAILED"));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("SOURCE_FAILED");
        verifyNoInteractions(stocks);
    }

    @Test
    void conflictingFigiDoesNotReachPersistence() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "A"), ticker("F1", "B")));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("AMBIGUOUS_MASTER_FIGI:F1");
        verifyNoInteractions(stocks);
    }

    @Test
    void resolvesSameDayTickerRenameByLatestUpdatedTimestampAndRetainsPreRenameSecCik() {
        MassiveTickerDetails oldBkh = ticker("BBG000BD9ZM7", "BKH");
        oldBkh.setLastUpdatedUtc(java.time.Instant.parse("2026-11-16T07:08:23Z"));
        MassiveTickerDetails newHzn = ticker("BBG000BD9ZM7", "HZN");
        newHzn.setLastUpdatedUtc(java.time.Instant.parse("2026-11-17T07:10:15Z"));
        when(massive.getAllStockTickers()).thenReturn(List.of(oldBkh, newHzn));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(finnhubSymbol("BBG000BD9ZM7", "HZN", "Common Stock")));
        when(sec.getTickerCiks()).thenReturn(Map.of("BKH", "0001130464"));
        when(submissionsSnapshot.readSic("0001130464")).thenReturn(Optional.of("4931"));

        task.execute(null);

        verify(stocks).updateStocks(argThat(rows -> rows.size() == 1
                && "BBG000BD9ZM7".equals(rows.getFirst().getFigi())
                && "HZN".equals(rows.getFirst().getSymbol())
                && "0001130464".equals(rows.getFirst().getCik())
                && "4931".equals(rows.getFirst().getSic())));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void batchesMasterAndNewStatuses() {
        when(massive.getAllStockTickers()).thenReturn(IntStream.range(0, 401).mapToObj(i -> ticker("F" + i, "S" + i)).toList());
        task.execute(null);
        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(stocks, times(3)).updateStocks(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 200, 1);
        verify(stocks, times(3)).updateStockStatuses(anySet());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retainsCommonSharesCommonAdrAndEtfOnly() {
        MassiveTickerDetails ordinary = ticker("F2", "ORD");
        ordinary.setType("OS");
        MassiveTickerDetails adr = ticker("F3", "TSM");
        adr.setType("ADRC");
        MassiveTickerDetails etf = ticker("F4", "SPY");
        etf.setType("ETF");
        MassiveTickerDetails preferred = ticker("F5", "PREF");
        preferred.setType("PFD");
        MassiveTickerDetails warrant = ticker("F7", "WAR");
        warrant.setType("WARRANT");
        when(massive.getAllStockTickers()).thenReturn(List.of(
                ticker("F1", "COMMON"), ordinary, adr, etf, preferred, warrant));
        when(finnhub.getStockSymbols("US")).thenReturn(List.of(
                finnhubSymbol("F1", "COMMON", "Common Stock"), finnhubSymbol("F2", "ORD", "Common Stock"),
                finnhubSymbol("F3", "TSM", "ADR"), finnhubSymbol("F4", "SPY", "ETP")));

        task.execute(null);

        ArgumentCaptor<List> batches = ArgumentCaptor.forClass(List.class);
        verify(stocks).updateStocks(batches.capture());
        List<Stock> saved = (List<Stock>) batches.getValue();
        assertThat(saved).extracting(Stock::getFigi).containsExactly("F1", "F2", "F3", "F4");
        assertThat(saved).extracting(Stock::getStockType).containsExactly("Common Stock", "Common Stock", "ADR", "ETP");
        verify(stocks).updateStockStatuses(Set.of("F1", "F2", "F3", "F4"));
    }

    @Test
    void unknownCikClearsOldMassiveCikAndSicButRetainsActiveStock() {
        Stock existing = stock("F1", "NBN");
        existing.setCik("0000060519");
        existing.setSic("6021");
        when(stocks.findAllStocks()).thenReturn(List.of(existing));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "NBN")));
        when(sec.getTickerCiks()).thenReturn(Map.of("LPX", "0000060519"));
        task.execute(null);
        assertThat(existing.getCik()).isNull();
        assertThat(existing.getSic()).isNull();
        assertThat(existing.getActiveStatus()).isEqualTo(ActiveStatus.ACTIVE);
    }

    @Test
    void secFailureNeverStartsTransactionOrMutatesMaster() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        StockDailyTask guarded = new StockDailyTask(massive, finnhub, sec, submissionsArchive,
                new StockConverterImpl(), stocks, new SecurityTypePolicy(), transaction);
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        when(sec.getTickerCiks()).thenThrow(new IllegalStateException("SEC_TICKERS_INVALID"));
        assertThatThrownBy(() -> guarded.execute(null)).hasMessage("SEC_TICKERS_INVALID");
        verifyNoInteractions(stocks, transaction);
    }

    @Test
    void delistedShareClassCanBeReactivated() {
        Stock existing = stock("F1", "ABC");
        existing.setCik("0000123456");
        Stock sibling = stock("F2", "ABC-B");
        sibling.setCik("0000123456");
        when(stocks.findAllStocks()).thenReturn(List.of(existing, sibling));
        when(sec.getTickerCiks()).thenReturn(Map.of("ABC", "0000123456", "ABC-B", "0000123456"));
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        task.execute(null);
        assertThat(sibling.getActiveStatus()).isEqualTo(ActiveStatus.INACTIVE);
        clearInvocations(stocks);
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC"), ticker("F2", "ABC-B")));
        task.execute(null);
        assertThat(sibling.getActiveStatus()).isEqualTo(ActiveStatus.ACTIVE);
    }

    @Test
    void finnhubFailureNeverMutatesMaster() {
        when(massive.getAllStockTickers()).thenReturn(List.of(ticker("F1", "ABC")));
        when(finnhub.getStockSymbols("US")).thenThrow(new IllegalStateException("FINNHUB_FAILED"));
        assertThatThrownBy(() -> task.execute(null)).hasMessage("FINNHUB_FAILED");
        verifyNoInteractions(stocks);
    }

    private Symbol finnhubSymbol(String figi, String symbol, String type) {
        Symbol row = new Symbol();
        row.setFigiComposite(figi);
        row.setSymbol(symbol);
        row.setMic("XNAS");
        row.setType(type);
        return row;
    }

    private MassiveTickerDetails ticker(String figi, String symbol) {
        MassiveTickerDetails row = new MassiveTickerDetails();
        row.setCompositeFigi(figi);
        row.setTicker(symbol);
        row.setName(symbol);
        row.setType("CS");
        row.setCik("123456");
        row.setCurrencyName("usd");
        row.setActive(true);
        return row;
    }

    private Stock stock(String figi, String symbol) {
        Stock row = new Stock();
        row.setFigi(figi);
        row.setSymbol(symbol);
        row.setActiveStatus(ActiveStatus.ACTIVE);
        return row;
    }
}
