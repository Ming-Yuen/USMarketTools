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
}
