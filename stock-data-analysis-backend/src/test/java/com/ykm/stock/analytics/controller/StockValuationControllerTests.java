package com.ykm.stock.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.CustomPeerGroupDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.service.futu.FutuStockDataService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockValuationControllerTests {
    private final StockValuationService localValuations = mock(StockValuationService.class);
    private final FutuStockDataService futuData = mock(FutuStockDataService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StockValuationController(localValuations, futuData))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setControllerAdvice(new AnalysisExceptionHandler())
            .build();

    @Test
    void historyDefaultsToLocalFiveMultiplesAndSupportsExplicitFutuSource() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate through = LocalDate.of(2026, 1, 31);
        ValuationResultDto localRow = new ValuationResultDto(
                "F1", through, Instant.parse("2026-01-31T21:00:00Z"), "FINANCIAL_REPORT_AS_OBSERVED", "I1",
                "AVAILABLE", null,
                new ValuationResultDto.Metrics(
                        ValuationResultDto.value(new BigDecimal("22"), null),
                        ValuationResultDto.value(new BigDecimal("5"), null),
                        ValuationResultDto.value(new BigDecimal("4"), null),
                        ValuationResultDto.value(new BigDecimal("18"), null),
                        ValuationResultDto.value(new BigDecimal("14"), null)),
                new BigDecimal("110"), "USD", Instant.parse("2026-02-01T00:00:00Z"), null, null, "SEC_FILING_SHARES", null);
        when(localValuations.history("F1", from, through, "MONTH_END")).thenReturn(List.of(localRow));

        mvc.perform(get("/valuation/{figi}/history", "F1")
                        .param("from", from.toString())
                        .param("through", through.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("FINANCIAL_REPORT_AS_OBSERVED"))
                .andExpect(jsonPath("$[0].metrics.pfcf.value").value(18))
                .andExpect(jsonPath("$[0].metrics.evEbitda.value").value(14));
        verify(localValuations).history("F1", from, through, "MONTH_END");
        verifyNoInteractions(futuData);

        ValuationResultDto futuRow = ValuationResultDto.fromFutu("F1", through, null, null,
                new BigDecimal("21"), new BigDecimal("4.8"), new BigDecimal("3.9"));
        when(futuData.history("F1", from, through, "DAILY")).thenReturn(List.of(futuRow));

        mvc.perform(get("/valuation/{figi}/history", "F1")
                        .param("from", from.toString())
                        .param("through", through.toString())
                        .param("frequency", "DAILY")
                        .param("source", "FUTU")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value("FUTU"))
                .andExpect(jsonPath("$[0].metrics.pe.value").value(21))
                .andExpect(jsonPath("$[0].metrics.pfcf.reasonCode").value("NATIVE_METRIC_NOT_PROVIDED"));
        verify(futuData).history("F1", from, through, "DAILY");
    }

    @Test
    void recalculateEndpointRebuildsPeriodsAndReturnsEvaluatedRange() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 12);
        ValuationResultDto recalculated = new ValuationResultDto(
                "F1", day, Instant.parse("2026-11-12T21:00:00Z"), "FUTU_AND_SEC_FINANCIAL_FACTS", "I1",
                "AVAILABLE", null,
                new ValuationResultDto.Metrics(
                        ValuationResultDto.value(new BigDecimal("25"), null),
                        ValuationResultDto.value(new BigDecimal("6"), null),
                        ValuationResultDto.value(new BigDecimal("5"), null),
                        ValuationResultDto.value(new BigDecimal("20"), null),
                        ValuationResultDto.value(new BigDecimal("15"), null)),
                new BigDecimal("125"), "USD", Instant.parse("2026-11-13T00:00:00Z"), null, null, "OBSERVED_SHARES", null);
        when(localValuations.recalculate("F1", day, day)).thenReturn(List.of(recalculated));

        mvc.perform(post("/valuation/recalculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"figi\":\"F1\",\"from\":\"2026-11-12\",\"through\":\"2026-11-12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].figi").value("F1"))
                .andExpect(jsonPath("$[0].metrics.pe.value").value(25));

        verify(localValuations).recalculate("F1", day, day);
    }

    @Test
    void customPeersEndpointsSupportReadReplaceClearAndRejectEmptyPutBody() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 17);
        CustomPeerGroupDto saved = new CustomPeerGroupDto(
                "AMZN-FIGI",
                "EV_EBITDA",
                List.of("PE", "PB", "PS", "PFCF", "EV_EBITDA"),
                List.of(new CustomPeerGroupDto.PeerItem(
                        "MSFT-FIGI", "MSFT", "Microsoft", "I10", true, true, day,
                        "USD", new BigDecimal("3000000000000"), "MEGA_LARGE"))
        );
        when(localValuations.getCustomPeers("AMZN-FIGI")).thenReturn(saved);
        when(localValuations.saveCustomPeers("AMZN-FIGI", List.of("MSFT-FIGI"), "EV_EBITDA")).thenReturn(saved);
        CustomPeerGroupDto cleared = new CustomPeerGroupDto(
                "AMZN-FIGI", null, List.of("PE", "PB", "PS", "PFCF", "EV_EBITDA"), List.of());
        when(localValuations.clearCustomPeers("AMZN-FIGI")).thenReturn(cleared);

        mvc.perform(get("/valuation/{figi}/custom-peers", "AMZN-FIGI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredMetric").value("EV_EBITDA"))
                .andExpect(jsonPath("$.peers[0].symbol").value("MSFT"));

        mvc.perform(put("/valuation/{figi}/custom-peers", "AMZN-FIGI")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"peerFigis\":[\"MSFT-FIGI\"],\"preferredMetric\":\"EV_EBITDA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peers[0].figi").value("MSFT-FIGI"));
        verify(localValuations).saveCustomPeers("AMZN-FIGI", List.of("MSFT-FIGI"), "EV_EBITDA");

        mvc.perform(put("/valuation/{figi}/custom-peers", "AMZN-FIGI")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/valuation/{figi}/custom-peers", "AMZN-FIGI")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredMetric\":\"PE\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/valuation/{figi}/custom-peers", "AMZN-FIGI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.peers").isEmpty());
        verify(localValuations).clearCustomPeers("AMZN-FIGI");
    }

    @Test
    void unknownSourceReturnsBadRequest() throws Exception {
        mvc.perform(get("/valuation/{figi}/history", "F1")
                        .param("from", "2026-01-01")
                        .param("through", "2026-01-31")
                        .param("source", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }
}
