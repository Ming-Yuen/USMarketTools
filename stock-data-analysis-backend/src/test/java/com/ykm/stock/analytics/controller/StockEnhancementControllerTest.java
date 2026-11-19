package com.ykm.stock.analytics.controller;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.enhancement.EnhancementAiGateway;
import com.ykm.stock.analytics.service.enhancement.MacroCreditSpreadService;
import com.ykm.stock.analytics.service.enhancement.OptionsEnhancementService;
import com.ykm.stock.analytics.service.enhancement.SecCustomTagMappingService;
import com.ykm.stock.analytics.service.enhancement.SecInsiderAndInstitutionalService;
import com.ykm.stock.analytics.service.enhancement.ShortInterestService;
import com.ykm.stock.analytics.service.enhancement.StockEventSummaryService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsStandardMessageJsonOnNotFound() throws Exception {
        when(stockRepository.findById("MISSING_FIGI")).thenReturn(Optional.empty());
        mockMvc.perform(get("/enhancements/stocks/MISSING_FIGI"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("STOCK_NOT_FOUND"));
    }
}
