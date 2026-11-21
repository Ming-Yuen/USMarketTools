package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ShortInterestSummaryDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockShortInterest;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockShortInterestRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortInterestServiceTest {
    @Mock private MassiveService massiveService;
    @Mock private StockRepository stockRepository;
    @Mock private StockShortInterestRepository shortInterestRepository;
    @Mock private StockShareObservationRepository shareObservationRepository;
    @Mock private StockFinancialPeriodRepository financialPeriodRepository;
    @Mock private StockQuoteRepository quoteRepository;
    @Mock private MarketStatusService calendar;
    @Mock private DateTimeService dateTimeService;
    @Mock private TransactionTemplate transactionTemplate;

    private ShortInterestService service;

    @BeforeEach
    void setUp() {
        service = new ShortInterestService(
                massiveService, stockRepository, shortInterestRepository,
                shareObservationRepository, financialPeriodRepository, quoteRepository, calendar, dateTimeService, transactionTemplate);
        lenient().when(dateTimeService.now()).thenReturn(Instant.parse("2026-11-19T22:00:00Z"));
        lenient().when(calendar.getSessionCloseAt(any())).thenReturn(Instant.parse("2026-10-30T20:00:00Z"));
        lenient().when(quoteRepository.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void detectsShortTrapAndShortSqueezeForVerifiedSingleClassStock() {
        Stock stock = new Stock();
        stock.setFigi("FIGI_SINGLE");
        stock.setSymbol("TEST");
        stock.setStockType("Common Stock");
        stock.setCik("0000123456");

        LocalDate asOf = LocalDate.of(2026, 11, 19);
        StockShortInterest latest = new StockShortInterest(
                "FIGI_SINGLE", LocalDate.of(2026, 10, 30), "TEST", 20_000_000L, 4_000_000L,
                new BigDecimal("5.00"), StockShortInterest.SOURCE_MASSIVE_FINRA, Instant.now());
        StockShareObservation obs = new StockShareObservation();
        obs.setFigi("FIGI_SINGLE");
        obs.setOutstandingShares(100_000_000L); // 20% short!

        when(shortInterestRepository.findTop6ByFigiAndSettlementDateLessThanEqualOrderBySettlementDateDesc("FIGI_SINGLE", asOf))
                .thenReturn(List.of(latest));
        when(stockRepository.findMultiSecurityIssuers(List.of("0000123456"), ActiveStatus.ACTIVE)).thenReturn(Set.of());
        when(shareObservationRepository.findLatestAt(eq(List.of("FIGI_SINGLE")), any())).thenReturn(List.of(obs));

        ShortInterestSummaryDto trap = service.evaluateStock(stock, asOf, "WEAKENING", false);
        assertThat(trap.shortPctOfSharesOutstanding()).isEqualByComparingTo("0.200000");
        assertThat(trap.signalCode()).isEqualTo(ShortInterestService.SIGNAL_SHORT_TRAP_WARNING);

        ShortInterestSummaryDto squeeze = service.evaluateStock(stock, asOf, "STRONG", true);
        assertThat(squeeze.signalCode()).isEqualTo(ShortInterestService.SIGNAL_SHORT_SQUEEZE_POTENTIAL);
    }

    @Test
    void blocksShortPctDenominatorForAdrAndMultiClassWhilePreservingRawShortShares() {
        LocalDate asOf = LocalDate.of(2026, 11, 19);
        Stock adr = new Stock();
        adr.setFigi("FIGI_ADR");
        adr.setSymbol("TSM");
        adr.setStockType("ADR");
        StockShortInterest adrSi = new StockShortInterest(
                "FIGI_ADR", LocalDate.of(2026, 10, 30), "TSM", 15_000_000L, 10_000_000L,
                new BigDecimal("1.50"), StockShortInterest.SOURCE_MASSIVE_FINRA, Instant.now());
        when(shortInterestRepository.findTop6ByFigiAndSettlementDateLessThanEqualOrderBySettlementDateDesc("FIGI_ADR", asOf))
                .thenReturn(List.of(adrSi));

        ShortInterestSummaryDto adrDto = service.evaluateStock(adr, asOf, "STRONG", true);
        assertThat(adrDto.status()).isEqualTo("AVAILABLE");
        assertThat(adrDto.shortInterestShares()).isEqualTo(15_000_000L);
        assertThat(adrDto.shortPctOfSharesOutstanding()).isNull();
        assertThat(adrDto.shortPctStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(adrDto.shortPctReason()).isEqualTo("ADR_ADS_FLOAT_UNVERIFIED");

        Stock etf = new Stock();
        etf.setFigi("FIGI_ETF");
        etf.setSymbol("SPY");
        etf.setStockType("ETP");
        StockShortInterest etfSi = new StockShortInterest(
                "FIGI_ETF", LocalDate.of(2026, 10, 30), "SPY", 50_000_000L, 60_000_000L,
                new BigDecimal("0.83"), StockShortInterest.SOURCE_MASSIVE_FINRA, Instant.now());
        when(shortInterestRepository.findTop6ByFigiAndSettlementDateLessThanEqualOrderBySettlementDateDesc("FIGI_ETF", asOf))
                .thenReturn(List.of(etfSi));

        ShortInterestSummaryDto etfDto = service.evaluateStock(etf, asOf, "WEAKENING", false);
        assertThat(etfDto.signalCode()).isEqualTo(ShortInterestService.SIGNAL_ETF_INFO_ONLY);
        assertThat(etfDto.shortPctReason()).isEqualTo("NOT_APPLICABLE_FOR_ETF");
    }

    @Test
    void marketBatchUsesDedicatedGlobalWatermarkInsteadOfSingleStockMaxSettlementDate() {
        com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository sysConfigRepo =
                org.mockito.Mockito.mock(com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sysConfigRepository", sysConfigRepo);

        com.ykm.orm.entity.SysConfig storedWatermark = new com.ykm.orm.entity.SysConfig();
        storedWatermark.setConfigKey(ShortInterestService.CONFIG_KEY_MARKET_SYNCED_DATE);
        storedWatermark.setConfigValue("2026-09-15");
        storedWatermark.setEnabled(true);
        when(sysConfigRepo.findByConfigKeyInOrderByIdAsc(List.of(ShortInterestService.CONFIG_KEY_MARKET_SYNCED_DATE)))
                .thenReturn(List.of(storedWatermark));
        when(massiveService.isConfigured()).thenReturn(true);
        // 即使單股刷新曾把表內 MAX(settlement_date) 推到 2026-11-15，全市場批次仍從 2026-09-15 往前 21 天抓
        when(massiveService.getShortInterestFrom(LocalDate.of(2026, 8, 25))).thenReturn(List.of(
                new MassiveService.MassiveShortInterestRow("AAPL", LocalDate.of(2026, 10, 30), 1000L, 500L, BigDecimal.TWO)));
        Stock aapl = new Stock();
        aapl.setFigi("F_AAPL");
        aapl.setSymbol("AAPL");
        aapl.setActiveStatus(ActiveStatus.ACTIVE);
        when(stockRepository.findAll()).thenReturn(List.of(aapl));
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        });

        ShortInterestService.ShortSyncResult result = service.syncMarketBatch();

        assertThat(result.savedRows()).isEqualTo(1);
        assertThat(storedWatermark.getConfigValue()).isEqualTo("2026-10-30");
        org.mockito.Mockito.verify(massiveService).getShortInterestFrom(LocalDate.of(2026, 8, 25));
    }

    @Test
    void migratesLegacySysConfigWatermarkIntoJobConfigAndCleansOldKey() {
        com.ykm.stock.analytics.repository.jpa.JobConfigRepository jobConfigRepo =
                org.mockito.Mockito.mock(com.ykm.stock.analytics.repository.jpa.JobConfigRepository.class);
        com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository sysConfigRepo =
                org.mockito.Mockito.mock(com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jobConfigRepository", jobConfigRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sysConfigRepository", sysConfigRepo);

        com.ykm.orm.entity.SysConfig legacy = new com.ykm.orm.entity.SysConfig();
        legacy.setConfigKey(ShortInterestService.CONFIG_KEY_MARKET_SYNCED_DATE);
        legacy.setConfigValue("2026-09-15");
        legacy.setEnabled(true);
        com.ykm.stock.analytics.model.entity.JobConfig jobRow = new com.ykm.stock.analytics.model.entity.JobConfig();
        jobRow.setTaskCode(com.ykm.stock.analytics.constant.TaskCode.SHORT_INTEREST_SYNC);
        when(jobConfigRepo.findByTaskCode(com.ykm.stock.analytics.constant.TaskCode.SHORT_INTEREST_SYNC)).thenReturn(jobRow);
        when(jobConfigRepo.findWatermarkDateByTaskCode(com.ykm.stock.analytics.constant.TaskCode.SHORT_INTEREST_SYNC))
                .thenReturn(java.util.Optional.empty(), java.util.Optional.of(LocalDate.of(2026, 10, 30)));
        when(sysConfigRepo.findByConfigKeyInOrderByIdAsc(List.of(ShortInterestService.CONFIG_KEY_MARKET_SYNCED_DATE)))
                .thenReturn(List.of(legacy), List.of());
        when(massiveService.isConfigured()).thenReturn(true);
        when(massiveService.getShortInterestFrom(LocalDate.of(2026, 8, 25))).thenReturn(List.of(
                new MassiveService.MassiveShortInterestRow("AAPL", LocalDate.of(2026, 10, 30), 1000L, 500L, BigDecimal.TWO)));
        Stock aapl = new Stock();
        aapl.setFigi("F_AAPL");
        aapl.setSymbol("AAPL");
        aapl.setActiveStatus(ActiveStatus.ACTIVE);
        when(stockRepository.findAll()).thenReturn(List.of(aapl));
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(new org.springframework.transaction.support.SimpleTransactionStatus());
        });

        ShortInterestService.ShortSyncResult result = service.syncMarketBatch();

        assertThat(result.savedRows()).isEqualTo(1);
        org.mockito.Mockito.verify(jobConfigRepo).updateWatermarkDate(
                com.ykm.stock.analytics.constant.TaskCode.SHORT_INTEREST_SYNC, LocalDate.of(2026, 9, 15));
        org.mockito.Mockito.verify(sysConfigRepo).deleteAll(List.of(legacy));
        org.mockito.Mockito.verify(jobConfigRepo).updateWatermarkDate(
                com.ykm.stock.analytics.constant.TaskCode.SHORT_INTEREST_SYNC, LocalDate.of(2026, 10, 30));
    }
}
