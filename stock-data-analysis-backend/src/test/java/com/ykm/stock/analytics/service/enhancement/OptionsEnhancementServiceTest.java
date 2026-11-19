package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.OptionsSummaryDto;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockOptionsSummary;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockOptionsSummaryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.AlpacaService;
import com.ykm.stock.analytics.service.stock.AlpacaService.OptionContractRecord;
import com.ykm.stock.analytics.service.stock.AlpacaService.OptionSnapshotRecord;
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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionsEnhancementServiceTest {
    @Mock private AlpacaService alpacaService;
    @Mock private StockOptionsSummaryRepository optionsRepository;
    @Mock private StockQuoteRepository stockQuoteRepository;
    @Mock private MarketEventRepository marketEventRepository;
    @Mock private QuoteNormalizationService quoteNormalizationService;
    @Mock private MarketStatusService calendar;
    @Mock private DateTimeService dateTimeService;
    @Mock private TransactionTemplate transactionTemplate;

    private OptionsEnhancementService service;

    @BeforeEach
    void setUp() {
        service = new OptionsEnhancementService(
                alpacaService, optionsRepository, stockQuoteRepository, marketEventRepository,
                quoteNormalizationService, calendar, dateTimeService, transactionTemplate);
    }

    @Test
    void afterCloseFridayEarningsSkipsSameDayExpiry() {
        LocalDate fri = LocalDate.of(2026, 11, 20);
        LocalDate nextFri = LocalDate.of(2026, 11, 27);
        TreeMap<LocalDate, List<OptionContractRecord>> byExpiry = new TreeMap<>();
        byExpiry.put(fri, List.of());
        byExpiry.put(nextFri, List.of());

        MarketEvent afterCloseEarnings = new MarketEvent();
        afterCloseEarnings.setEventDate(fri);
        afterCloseEarnings.setTimePrecision("AFTER_CLOSE");

        LocalDate selected = service.selectStraddleExpiration(byExpiry, afterCloseEarnings, LocalDate.of(2026, 11, 18), false);
        assertThat(selected).isEqualTo(nextFri);

        MarketEvent beforeOpenEarnings = new MarketEvent();
        beforeOpenEarnings.setEventDate(fri);
        beforeOpenEarnings.setTimePrecision("BEFORE_OPEN");
        LocalDate selectedBeforeOpen = service.selectStraddleExpiration(byExpiry, beforeOpenEarnings, LocalDate.of(2026, 11, 18), false);
        assertThat(selectedBeforeOpen).isEqualTo(fri);
    }

    @Test
    void readAtFlagsEarningsGapRiskAndPreservesNonDirectionalDisclaimer() {
        Stock aapl = new Stock();
        aapl.setFigi("BBG000B9XRY4");
        aapl.setSymbol("AAPL");
        aapl.setStockType("Common Stock");

        LocalDate snapDate = LocalDate.of(2026, 11, 19);
        StockOptionsSummary entity = new StockOptionsSummary();
        entity.setFigi("BBG000B9XRY4");
        entity.setSnapshotDate(snapDate);
        entity.setSymbol("AAPL");
        entity.setSource("ALPACA");
        entity.setFeed("indicative");
        entity.setSpotPrice(new BigDecimal("200.00"));
        entity.setNextEarningsDate(LocalDate.of(2026, 11, 26));
        entity.setStraddleExpiration(LocalDate.of(2026, 11, 27));
        entity.setImpliedMovePct(new BigDecimal("0.0850"));
        entity.setImpliedMoveStatus("AVAILABLE");
        entity.setImpliedMoveReason("EARNINGS_STRADDLE");
        entity.setMaxCallOiStrike(new BigDecimal("215"));
        entity.setMaxCallOi(18000L);
        entity.setMaxPutOiStrike(new BigDecimal("190"));
        entity.setMaxPutOi(22000L);
        entity.setStatus("AVAILABLE");
        entity.setObservedAt(Instant.parse("2026-11-19T22:00:00Z"));

        when(optionsRepository.findTopByFigiAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc("BBG000B9XRY4", snapDate))
                .thenReturn(Optional.of(entity));
        when(optionsRepository.findByFigiAndSnapshotDateBetweenOrderBySnapshotDateAsc(any(), any(), any()))
                .thenReturn(List.of(entity));

        // Stop at 190 => 5% distance < 8.5% implied move & earnings in 7 days => gap risk TRUE
        OptionsSummaryDto dto = service.readAt(aapl, snapDate, new BigDecimal("190.00"), new BigDecimal("200.00"));
        assertThat(dto.earningsGapExceedsStopRisk()).isTrue();
        assertThat(dto.protectiveStopDistancePct()).isEqualByComparingTo("0.050000");
        assertThat(dto.nonDirectionalDisclaimerCode()).isEqualTo(OptionsEnhancementService.OI_DISCLAIMER_CODE);
    }

    @Test
    void etfMarksImpliedMoveNotApplicableWhileComputingOiWallAndSkew() {
        when(alpacaService.getOptionsFeed()).thenReturn("indicative");
        Stock spy = new Stock();
        spy.setFigi("BBG000BSWKH7");
        spy.setSymbol("SPY");
        spy.setStockType("ETP");

        LocalDate snapDate = LocalDate.of(2026, 11, 19);
        LocalDate exp = LocalDate.of(2026, 12, 18);
        List<OptionContractRecord> contracts = List.of(
                new OptionContractRecord("SPY_C_600", "call", exp, new BigDecimal("600"), 15000L, snapDate, new BigDecimal("10"), snapDate),
                new OptionContractRecord("SPY_P_600", "put", exp, new BigDecimal("600"), 12000L, snapDate, new BigDecimal("9"), snapDate),
                new OptionContractRecord("SPY_C_620", "call", exp, new BigDecimal("620"), 45000L, snapDate, new BigDecimal("3"), snapDate),
                new OptionContractRecord("SPY_P_580", "put", exp, new BigDecimal("580"), 55000L, snapDate, new BigDecimal("4"), snapDate)
        );
        Map<String, OptionSnapshotRecord> snaps = Map.of(
                "SPY_C_600", new OptionSnapshotRecord("SPY_C_600", new BigDecimal("0.18"), new BigDecimal("0.51"), null, null, null, new BigDecimal("9.8"), new BigDecimal("10.2"), null, null, 500L, Instant.now(), snapDate),
                "SPY_P_600", new OptionSnapshotRecord("SPY_P_600", new BigDecimal("0.19"), new BigDecimal("-0.49"), null, null, null, new BigDecimal("8.8"), new BigDecimal("9.2"), null, null, 600L, Instant.now(), snapDate),
                "SPY_C_620", new OptionSnapshotRecord("SPY_C_620", new BigDecimal("0.15"), new BigDecimal("0.24"), null, null, null, new BigDecimal("2.9"), new BigDecimal("3.1"), null, null, 800L, Instant.now(), snapDate),
                "SPY_P_580", new OptionSnapshotRecord("SPY_P_580", new BigDecimal("0.23"), new BigDecimal("-0.26"), null, null, null, new BigDecimal("3.9"), new BigDecimal("4.1"), null, null, 900L, Instant.now(), snapDate)
        );
        StockOptionsSummary result = service.computeSummary(
                spy, snapDate, Instant.now(), true,
                new OptionsEnhancementService.PriceContext(new BigDecimal("600.00"), new BigDecimal("0.1500")),
                null, exp, contracts, exp, contracts, snaps);
        assertThat(result.getStatus()).isEqualTo(StockOptionsSummary.STATUS_AVAILABLE);
        assertThat(result.getImpliedMoveStatus()).isEqualTo(StockOptionsSummary.STATUS_NOT_APPLICABLE);
        assertThat(result.getImpliedMoveReason()).isEqualTo("NOT_APPLICABLE_FOR_ETF");
        assertThat(result.getMaxCallOiStrike()).isEqualByComparingTo("620");
        assertThat(result.getMaxPutOiStrike()).isEqualByComparingTo("580");
        assertThat(result.getPutCallOiRatio()).isEqualByComparingTo("1.1167");
        assertThat(result.getIvSkewPutMinusCall()).isEqualByComparingTo("0.080000");
        assertThat(result.getAtmIv()).isEqualByComparingTo("0.185000");
        assertThat(result.getIvToHvRatio()).isEqualByComparingTo("1.2333");
    }
}
