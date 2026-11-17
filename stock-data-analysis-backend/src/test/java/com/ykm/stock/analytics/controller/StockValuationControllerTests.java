package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.service.futu.FutuStockDataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockValuationControllerTests {
    private final FutuStockDataService data = mock(FutuStockDataService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StockValuationController(data)).build();

    @Test
    void valuationHistoryRemainsAvailable() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate through = LocalDate.of(2026, 1, 31);
        when(data.history("F1", from, through, "MONTH_END")).thenReturn(List.of());

        mvc.perform(get("/valuation/{figi}/history", "F1").param("from", from.toString()).param("through", through.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(data).history("F1", from, through, "MONTH_END");
    }
}
