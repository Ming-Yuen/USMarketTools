package com.ykm.stock.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.InsiderSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.Institutional13fSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.OptionsSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.ShortInterestSummaryDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway.AiChannelStatus;
import com.ykm.stock.analytics.service.enhancement.MacroCreditSpreadService;
import com.ykm.stock.analytics.service.enhancement.OptionsEnhancementService;
import com.ykm.stock.analytics.service.enhancement.SecCustomTagMappingService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService.ShortSyncResult;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StockEnhancementControllerTest {
    @Mock private StockRepository stockRepository;
    @Mock private StockValuationLatestRepository latestValuationRepository;
    @Mock private MacroCreditSpreadService creditSpreadService;
    @Mock private OptionsEnhancementService optionsService;
    @Mock private SecInsiderAndInstitutionalService insider13fService;
    @Mock private ShortInterestService shortInterestService;
    @Mock private StockEventSummaryService eventSummaryService;
    @Mock private SecCustomTagMappingService customTagMappingService;
    @Mock private EnhancementAiGateway aiGateway;
    @Mock private MarketStatusService calendar;
    @Mock private DateTimeService dateTimeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StockEnhancementController controller = new StockEnhancementController(
                stockRepository, latestValuationRepository, creditSpreadService, optionsService,
                insider13fService, shortInterestService, eventSummaryService, customTagMappingService,
                aiGateway, calendar, dateTimeService);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void returnsStandardMessageJsonOnNotFound() throws Exception {
        when(stockRepository.findById("MISSING_FIGI")).thenReturn(Optional.empty());
        mockMvc.perform(get("/enhancements/stocks/MISSING_FIGI"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("STOCK_NOT_FOUND"));
    }

    @Test
    void getStockEnhancementsPropagatesHistoricalReportContextToServices() throws Exception {
        Stock stock = new Stock();
        stock.setFigi("BBG000B9XRY4");
        stock.setSymbol("AAPL");
        stock.setStockType("Common Stock");
        stock.setCik("0000320193");

        // Latest valuation is AFTER asOfDate -> explicit query params from historical report must still pass through
        StockValuationLatest latest = new StockValuationLatest();
        latest.setFigi("BBG000B9XRY4");
        latest.setValuationDate(LocalDate.of(2026, 11, 18));
        latest.setVerdict("OVERVALUED");
        latest.setFundamentalBacking("STRONG");
        latest.setClosePrice(new BigDecimal("250.00"));

        LocalDate asOfDate = LocalDate.of(2026, 10, 15);
        Instant cutoff = Instant.parse("2026-10-15T20:00:00Z");

        when(stockRepository.findById("BBG000B9XRY4")).thenReturn(Optional.of(stock));
        when(latestValuationRepository.findById("BBG000B9XRY4")).thenReturn(Optional.of(latest));
        when(calendar.getSessionCloseAt(asOfDate)).thenReturn(cutoff);
        when(aiGateway.status()).thenReturn(new AiChannelStatus("NONE", false, null, "low", true));
        when(creditSpreadService.evaluateAt(asOfDate, cutoff)).thenReturn(CreditSpreadSummaryDto.unavailable("UNAVAILABLE"));
        when(optionsService.readAt(eq(stock), eq(asOfDate), eq(new BigDecimal("190.50")), eq(new BigDecimal("200.00"))))
                .thenReturn(OptionsSummaryDto.unavailable("BBG000B9XRY4", "AAPL", "INSUFFICIENT_DATA", "OPTIONS_NOT_SYNCED"));
        when(insider13fService.evaluateInsiders(stock, asOfDate, "DEEP_UNDERVALUED"))
                .thenReturn(InsiderSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_FORM4_FILINGS_SYNCED", "0000320193"));
        when(insider13fService.evaluate13fForStock(stock, asOfDate))
                .thenReturn(Institutional13fSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_13F_HOLDINGS_MATCHED"));
        when(shortInterestService.evaluateStock(stock, asOfDate, "WEAKENING", true))
                .thenReturn(ShortInterestSummaryDto.unavailable("INSUFFICIENT_DATA", "SHORT_INTEREST_NOT_SYNCED"));
        when(eventSummaryService.listRecentNewsEvents("BBG000B9XRY4", cutoff)).thenReturn(List.of());
        when(eventSummaryService.listSummaries("BBG000B9XRY4", cutoff)).thenReturn(List.of());
        when(customTagMappingService.listMappings(stock)).thenReturn(List.of());

        mockMvc.perform(get("/enhancements/stocks/BBG000B9XRY4")
                        .param("asOfDate", "2026-10-15")
                        .param("protectiveStopPrice", "190.50")
                        .param("closePrice", "200.00")
                        .param("verdict", "DEEP_UNDERVALUED")
                        .param("fundamentalBacking", "WEAKENING")
                        .param("resistanceBreakout", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figi").value("BBG000B9XRY4"));

        verify(optionsService).readAt(stock, asOfDate, new BigDecimal("190.50"), new BigDecimal("200.00"));
        verify(insider13fService).evaluateInsiders(stock, asOfDate, "DEEP_UNDERVALUED");
        verify(shortInterestService).evaluateStock(stock, asOfDate, "WEAKENING", true);

        // When GET effectiveDate (2026-11-19) is AFTER latestVal.valuationDate (2026-11-18) and closePrice is omitted,
        // GET must NOT pass yesterday's closePrice over today's spot (passes null so optionsService uses snapshot spot)
        LocalDate nextDay = LocalDate.of(2026, 11, 19);
        Instant nextCutoff = Instant.parse("2026-11-19T20:00:00Z");
        when(calendar.getSessionCloseAt(nextDay)).thenReturn(nextCutoff);
        when(creditSpreadService.evaluateAt(nextDay, nextCutoff)).thenReturn(CreditSpreadSummaryDto.unavailable("UNAVAILABLE"));
        when(optionsService.readAt(stock, nextDay, new BigDecimal("195.00"), null))
                .thenReturn(OptionsSummaryDto.unavailable("BBG000B9XRY4", "AAPL", "INSUFFICIENT_DATA", "OPTIONS_NOT_SYNCED"));
        when(insider13fService.evaluateInsiders(stock, nextDay, "OVERVALUED"))
                .thenReturn(InsiderSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_FORM4_FILINGS_SYNCED", "0000320193"));
        when(insider13fService.evaluate13fForStock(stock, nextDay))
                .thenReturn(Institutional13fSummaryDto.unavailable("INSUFFICIENT_DATA", "NO_13F_HOLDINGS_MATCHED"));
        when(shortInterestService.evaluateStock(stock, nextDay, "STRONG", false))
                .thenReturn(ShortInterestSummaryDto.unavailable("INSUFFICIENT_DATA", "SHORT_INTEREST_NOT_SYNCED"));
        when(eventSummaryService.listRecentNewsEvents("BBG000B9XRY4", nextCutoff)).thenReturn(List.of());
        when(eventSummaryService.listSummaries("BBG000B9XRY4", nextCutoff)).thenReturn(List.of());

        mockMvc.perform(get("/enhancements/stocks/BBG000B9XRY4")
                        .param("asOfDate", "2026-11-19")
                        .param("protectiveStopPrice", "195.00"))
                .andExpect(status().isOk());
        verify(optionsService).readAt(stock, nextDay, new BigDecimal("195.00"), null);
    }

    @Test
    void refreshEndpointsFallbackToLatestValuationAndPropagateStopAndBreakout() throws Exception {
        Stock stock = new Stock();
        stock.setFigi("BBG000B9XRY4");
        stock.setSymbol("AAPL");
        stock.setStockType("Common Stock");
        stock.setCik("0000320193");

        LocalDate today = LocalDate.of(2026, 11, 18);
        StockValuationLatest latest = new StockValuationLatest();
        latest.setFigi("BBG000B9XRY4");
        latest.setValuationDate(today);
        latest.setVerdict("DEEP_UNDERVALUED");
        latest.setFundamentalBacking("WEAKENING");
        latest.setClosePrice(new BigDecimal("210.00"));

        OptionsSummaryDto refreshedRaw = new OptionsSummaryDto(
                "BBG000B9XRY4", "AAPL", today, "ALPACA", "indicative", "AVAILABLE", null,
                new BigDecimal("210.00"), null, LocalDate.of(2026, 11, 25), 7, LocalDate.of(2026, 11, 27),
                new BigDecimal("210.00"), new BigDecimal("7.50"), new BigDecimal("7.50"), new BigDecimal("0.0714"),
                "AVAILABLE", "EARNINGS_STRADDLE", null, false,
                null, null, null, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "OI_WALL_NON_DIRECTIONAL_DISCLAIMER", Instant.parse("2026-11-18T21:00:00Z")
        );
        OptionsSummaryDto withStop = new OptionsSummaryDto(
                "BBG000B9XRY4", "AAPL", today, "ALPACA", "indicative", "AVAILABLE", null,
                new BigDecimal("210.00"), null, LocalDate.of(2026, 11, 25), 7, LocalDate.of(2026, 11, 27),
                new BigDecimal("210.00"), new BigDecimal("7.50"), new BigDecimal("7.50"), new BigDecimal("0.0714"),
                "AVAILABLE", "EARNINGS_STRADDLE", new BigDecimal("0.0476"), true,
                null, null, null, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "OI_WALL_NON_DIRECTIONAL_DISCLAIMER", Instant.parse("2026-11-18T21:00:00Z")
        );

        when(stockRepository.findById("BBG000B9XRY4")).thenReturn(Optional.of(stock));
        when(latestValuationRepository.findById("BBG000B9XRY4")).thenReturn(Optional.of(latest));
        when(optionsService.refreshStock(stock)).thenReturn(refreshedRaw);
        when(optionsService.readAt(stock, today, new BigDecimal("200.00"), new BigDecimal("210.00"))).thenReturn(withStop);

        mockMvc.perform(post("/enhancements/stocks/BBG000B9XRY4/options/refresh")
                        .param("protectiveStopPrice", "200.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earningsGapExceedsStopRisk").value(true))
                .andExpect(jsonPath("$.protectiveStopDistancePct").value(0.0476));

        verify(optionsService).readAt(stock, today, new BigDecimal("200.00"), new BigDecimal("210.00"));

        // Short interest refresh without query params must fall back to latestValuation.fundamentalBacking ("WEAKENING"), never null
        when(dateTimeService.now()).thenReturn(Instant.parse("2026-11-18T21:00:00Z"));
        when(calendar.getLastClosedTradingDay(any())).thenReturn(today);
        when(shortInterestService.refreshStock(stock)).thenReturn(new ShortSyncResult(2, 1, today));
        when(shortInterestService.evaluateStock(stock, today, "WEAKENING", false))
                .thenReturn(ShortInterestSummaryDto.unavailable("AVAILABLE", null));

        mockMvc.perform(post("/enhancements/stocks/BBG000B9XRY4/short-interest/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedRows").value(1));

        verify(shortInterestService).evaluateStock(stock, today, "WEAKENING", false);

        // Historical contextDate (2026-10-15 != today 2026-11-18) must NOT leak October fundamentalBacking/breakout into November refresh
        mockMvc.perform(post("/enhancements/stocks/BBG000B9XRY4/short-interest/refresh")
                        .param("contextDate", "2026-10-15")
                        .param("fundamentalBacking", "STRONG")
                        .param("resistanceBreakout", "true"))
                .andExpect(status().isOk());
        verify(shortInterestService, org.mockito.Mockito.times(2)).evaluateStock(stock, today, "WEAKENING", false);

        // Intraday options refresh (snapshotDate 2026-11-19 != latestVal 2026-11-18) must NOT pass yesterday's closePrice over today's spot
        LocalDate intradayDate = LocalDate.of(2026, 11, 19);
        OptionsSummaryDto intradayRaw = new OptionsSummaryDto(
                "BBG000B9XRY4", "AAPL", intradayDate, "ALPACA", "indicative", "AVAILABLE", null,
                new BigDecimal("218.00"), null, LocalDate.of(2026, 11, 25), 6, LocalDate.of(2026, 11, 27),
                new BigDecimal("220.00"), new BigDecimal("7.50"), new BigDecimal("7.50"), new BigDecimal("0.0688"),
                "AVAILABLE", "EARNINGS_STRADDLE", null, false,
                null, null, null, 0, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "OI_WALL_NON_DIRECTIONAL_DISCLAIMER", Instant.parse("2026-11-19T16:00:00Z")
        );
        when(optionsService.refreshStock(stock)).thenReturn(intradayRaw);
        when(optionsService.readAt(stock, intradayDate, new BigDecimal("205.00"), null)).thenReturn(intradayRaw);

        mockMvc.perform(post("/enhancements/stocks/BBG000B9XRY4/options/refresh")
                        .param("protectiveStopPrice", "205.00"))
                .andExpect(status().isOk());
        verify(optionsService).readAt(stock, intradayDate, new BigDecimal("205.00"), null);
    }
}
