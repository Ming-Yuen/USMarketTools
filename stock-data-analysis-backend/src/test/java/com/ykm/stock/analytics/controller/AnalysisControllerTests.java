package com.ykm.stock.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.service.analysis.StockAnalysisService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisControllerTests {
    // 修改前：eligibility BLOCKED 包在 data.report。修改後：缺資料仍是 200，直接回傳報告及欄位原因。
    @Test
    void unavailableFinancialInputsReturnHttp200WithDirectReportAndNullMetrics() throws Exception {
        StockAnalysisService service = mock(StockAnalysisService.class);
        LocalDate day = LocalDate.of(2026, 10, 15);
        AnalysisReasonDto reason = AnalysisReasonDto.of("FUTU_VALUATION_FIELDS_MISSING", "VALUATION", "富途估值倍數有缺值");
        ValuationResultDto valuation = ValuationResultDto.fromFutu("FIGI", day, null, null, null, null, null);
        StockAnalysisReportDto report = new StockAnalysisReportDto("FIGI", "TEST", "Test Company", day,
                valuation, List.of(),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                        AnalysisReasonDto.of("TECHNICAL_PRICE_MISSING", "TECHNICAL", "缺少行情")),
                null, null, null, null, List.of(reason));
        when(service.analyze(any(AnalysisReportRequest.class))).thenReturn(report);

        mvc(service).perform(post("/analysis/report").contentType(MediaType.APPLICATION_JSON).content("""
                {"figi":"FIGI","valuationDate":"2026-10-15","horizon":"SWING"}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.figi").value("FIGI"))
                .andExpect(jsonPath("$.valuationDate").value("2026-10-15"))
                .andExpect(jsonPath("$.valuation.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.valuation.metrics.pe.value").value(nullValue()))
                .andExpect(jsonPath("$.valuation.metrics.pe.reasonCode").value("FUTU_VALUATION_FIELD_MISSING"))
                .andExpect(jsonPath("$.reasons[0].code").value("FUTU_VALUATION_FIELDS_MISSING"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.eligibility").doesNotExist());

        verify(service).analyze(new AnalysisReportRequest("FIGI", day, AnalysisHorizon.SWING));
        verifyNoMoreInteractions(service);
    }

    @Test
    void unknownFigiIs404AndUnknownHorizonIs400BeforeCallingService() throws Exception {
        StockAnalysisService service = mock(StockAnalysisService.class);
        when(service.analyze(any(AnalysisReportRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND"));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/analysis/report").contentType(MediaType.APPLICATION_JSON).content("""
                {"figi":"NOPE","horizon":"SWING"}
                """)).andExpect(status().isNotFound()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("STOCK_NOT_FOUND"));
        mvc.perform(post("/analysis/report").contentType(MediaType.APPLICATION_JSON).content("""
                {"figi":"FIGI","horizon":"MAGIC"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));

        verify(service).analyze(new AnalysisReportRequest("NOPE", null, AnalysisHorizon.SWING));
        verifyNoMoreInteractions(service);
    }

    // 日期改用 LocalDate valuationDate；是否已收市交由 service 判斷，API 正確傳遞日期與 400。
    @Test
    void futureValuationDateIsPassedToServiceAndItsBadRequestIsPreserved() throws Exception {
        StockAnalysisService service = mock(StockAnalysisService.class);
        when(service.analyze(any(AnalysisReportRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "valuationDate must be a completed trading session"));

        mvc(service).perform(post("/analysis/report").contentType(MediaType.APPLICATION_JSON).content("""
                {"figi":"FIGI","horizon":"SWING","valuationDate":"2099-01-01"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));

        ArgumentCaptor<AnalysisReportRequest> request = ArgumentCaptor.forClass(AnalysisReportRequest.class);
        verify(service).analyze(request.capture());
        assertThat(request.getValue().valuationDate()).isEqualTo(LocalDate.of(2099, 1, 1));
        assertThat(request.getValue().figi()).isEqualTo("FIGI");
        assertThat(request.getValue().horizon()).isEqualTo(AnalysisHorizon.SWING);
    }

    private MockMvc mvc(StockAnalysisService service) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new AnalysisController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new AnalysisExceptionHandler()).build();
    }
}
