package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.service.stock.StockClassificationSyncService;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.JobService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.CronTrigger;
import org.quartz.SimpleTrigger;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockClassificationDailySyncJobTests {
    private static final LocalDate TRADING_DAY = LocalDate.of(2026, 10, 29);
    private final StockService stockService = mock(StockService.class);
    private final FutuService futuService = mock(FutuService.class);
    private final FutuService.ClassificationSession futu = mock(FutuService.ClassificationSession.class);
    private final StockClassificationSyncService classification = mock(StockClassificationSyncService.class);
    private final JobExecutionContext context = mock(JobExecutionContext.class);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final StockClassificationDailySyncJob job = new StockClassificationDailySyncJob(
            stockService, futuService, classification, new TransactionTemplate(transactions));

    @BeforeEach
    void setUp() {
        when(futuService.openSession()).thenReturn(futu);
        when(futu.supportsStock(anyString())).thenAnswer(call -> !"ETF".equals(call.getArgument(0)));
        when(futu.getStockClassifications(anyList())).thenAnswer(call -> {
            assertThat(transactions.begins).isEqualTo(transactions.commits);
            return Map.of();
        });
        when(classification.updateClassifications(anyList(), anyMap())).thenAnswer(call -> {
            assertThat(transactions.begins).isEqualTo(transactions.commits + 1);
            return call.<List<Stock>>getArgument(0).size();
        });
    }

    @Test
    void supportedActiveStocksCommitInTwoHundredStockBatches() {
        List<Stock> active = new ArrayList<>(IntStream.range(0, 401).mapToObj(i -> stock("F" + i, "S" + i)).toList());
        active.add(stock("ETF_FIGI", "ETF"));
        when(stockService.findActiveStocksWithStatus()).thenReturn(active.stream().map(item -> snapshot(item, null)).toList());

        job.execute(context);

        assertThat(transactions.commits).isEqualTo(3);
        verify(futu, times(3)).getStockClassifications(anyList());
        verify(futu).getStockClassifications(List.of("S400"));
        verify(futu).close();
        verify(stockService, never()).updateStocks(anyList());
    }

    @Test
    void selectedFigiCannotBypassAnAmbiguousActiveTicker() {
        Stock selected = stock("F1", "LEU");
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(snapshot(selected, "LEU"), snapshot(stock("F2", "LEU"), "LEU")));
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap(Map.of("figis", "F1")));

        job.execute(context);

        verify(futu, never()).getStockClassifications(anyList());
        assertThat(transactions.begins).isZero();
    }

    @Test
    void secondClassificationWriteFailureRollsBackOnlyItsBatch() {
        when(stockService.findActiveStocksWithStatus()).thenReturn(IntStream.range(0, 401)
                .mapToObj(i -> snapshot(stock("F" + i, "S" + i), null)).toList());
        AtomicInteger batches = new AtomicInteger();
        doAnswer(call -> {
            if (batches.incrementAndGet() == 2) throw new IllegalStateException("CLASSIFICATION_WRITE_FAILED");
            return call.<List<Stock>>getArgument(0).size();
        }).when(classification).updateClassifications(anyList(), anyMap());

        assertThatThrownBy(() -> job.execute(context)).hasMessage("CLASSIFICATION_WRITE_FAILED");

        assertThat(transactions.commits).isEqualTo(1);
        assertThat(transactions.rollbacks).isEqualTo(1);
        verify(futu, times(2)).getStockClassifications(anyList());
        verify(futu).close();
    }

    @Test
    void providerFailurePreventsBatchTransaction() {
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(snapshot(stock("F1", "LEU"), null)));
        when(futu.getStockClassifications(List.of("LEU"))).thenThrow(new IllegalStateException("FUTU_API_ERROR:denied"));

        assertThatThrownBy(() -> job.execute(context)).hasMessage("FUTU_API_ERROR:denied");

        assertThat(transactions.begins).isZero();
        verifyNoInteractions(classification);
        verify(futu).close();
    }

    @Test
    void emptyCurrentStockStatusUniversePreventsProviderRequest() {
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of());

        assertThatThrownBy(() -> job.execute(context)).hasMessageContaining("STOCK_CLASSIFICATION_UNIVERSE_EMPTY");

        verifyNoInteractions(futuService, classification);
    }

    @Test
    void incrementalRunRequestsOnlyNewOrRenamedStocks() {
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(
                snapshot(stock("F1", "SAME"), "SAME"), snapshot(stock("F2", "NEW"), null),
                snapshot(stock("F3", "RENAMED"), "OLD")));

        job.execute(context);

        verify(futu).getStockClassifications(List.of("NEW", "RENAMED"));
    }

    @Test
    void incrementalRunWithoutPendingStocksDoesNotOpenProviderSession() {
        SimpleTrigger manual = mock(SimpleTrigger.class);
        when(context.getTrigger()).thenReturn(manual);
        when(context.getScheduledFireTime()).thenReturn(Date.from(Instant.parse("2026-10-31T01:00:00Z")));
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(snapshot(stock("F1", "SAME"), "SAME")));

        job.execute(context);

        verifyNoInteractions(futuService, classification);
    }

    @Test
    void delayedSaturdayCronRefreshesUsingItsScheduledHongKongDate() {
        when(context.getTrigger()).thenReturn(mock(CronTrigger.class));
        when(context.getScheduledFireTime()).thenReturn(Date.from(Instant.parse("2026-10-30T23:00:00Z")));
        when(context.getFireTime()).thenReturn(Date.from(Instant.parse("2026-11-02T01:00:00Z")));
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(
                snapshot(stock("F1", "SAME"), "SAME"), snapshot(stock("F2", "NEW"), null)));

        job.execute(context);

        verify(futu).getStockClassifications(List.of("SAME", "NEW"));
    }

    // Scenario：分類依普通股與富途識別，不要求 SEC CIK；其他證券類型仍排除。
    @Test
    void classificationRequestsOperatingCompanyTypesWithoutDependingOnCik() {
        Stock common = stock("CS1", "COMMON");
        Stock ordinary = stock("OS1", "ORDINARY");
        ordinary.setStockType("REIT");
        common.setCik(null);
        ordinary.setCik("   ");
        List<ActiveStockWithStatus> active = new ArrayList<>(List.of(snapshot(common, null), snapshot(ordinary, null)));
        String[][] excluded = {
                {"ETP", "0000000001"}, {"ADR", "0000000001"}, {"Equity WRT", "0000000001"},
                {null, "0000000001"}
        };
        for (int i = 0; i < excluded.length; i++) {
            Stock other = stock("OTHER" + i, "OTHER" + i);
            other.setStockType(excluded[i][0]);
            other.setCik(excluded[i][1]);
            active.add(snapshot(other, null));
        }
        when(stockService.findActiveStocksWithStatus()).thenReturn(active);
        when(futu.supportsStock(anyString())).thenReturn(true);

        job.execute(context);

        verify(futu).getStockClassifications(List.of("COMMON", "ORDINARY"));
        verify(futu, times(1)).getStockClassifications(anyList());
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    void explicitFigiCannotBypassOrdinaryStockUniverse() {
        Stock etf = stock("ETF1", "ETF");
        etf.setStockType("ETP");
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(
                snapshot(stock("CS1", "COMMON"), null), snapshot(etf, null)));
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap(Map.of("figis", "ETF1")));

        assertThatThrownBy(() -> job.execute(context)).hasMessage("STOCK_CLASSIFICATION_UNIVERSE_EMPTY");

        verifyNoInteractions(futuService, classification);
        assertThat(transactions.begins).isZero();
    }

    @Test
    void noEligibleStocksDoesNotOpenProviderSession() {
        Stock unsupported = stock("W1", "WARRANT");
        unsupported.setStockType("WARRANT");
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(snapshot(unsupported, null)));

        assertThatThrownBy(() -> job.execute(context)).hasMessage("STOCK_CLASSIFICATION_UNIVERSE_EMPTY");

        verifyNoInteractions(futuService, classification);
        assertThat(transactions.begins).isZero();
    }

    @Test
    void excludedWarrantDoesNotMakeOrdinaryStockSymbolAmbiguous() {
        Stock ordinary = stock("CS1", "LEU");
        Stock warrant = stock("W1", "LEU");
        warrant.setStockType("WARRANT");
        when(stockService.findActiveStocksWithStatus()).thenReturn(List.of(snapshot(ordinary, null), snapshot(warrant, null)));

        job.execute(context);

        verify(futu).getStockClassifications(List.of("LEU"));
        assertThat(transactions.commits).isEqualTo(1);
    }

    private Stock stock(String figi, String symbol) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setStockType("Common Stock");
        stock.setCik("0000000001");
        return stock;
    }

    private ActiveStockWithStatus snapshot(Stock stock, String syncedSymbol) {
        StockStatus status = new StockStatus();
        status.setFigi(stock.getFigi());
        status.setClassificationSyncedSymbol(syncedSymbol);
        return new ActiveStockWithStatus(stock, status);
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { commits++; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
