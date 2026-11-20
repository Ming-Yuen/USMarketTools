package com.ykm.stock.analytics.service.valuation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ykm.stock.analytics.controller.IndustryValuationController;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview;
import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview.Update;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IndustryValuationWorkflowApiTests {
    @Test
    void overviewEndpointReturnsDirectOverviewDtoWithoutCallingProposeOrSave() throws Exception {
        IndustryValuationProposalService service = mock(IndustryValuationProposalService.class);
        IndustryValuationReview.Overview overview = new IndustryValuationReview.Overview(
                List.of(
                        new IndustryValuationReview.OverviewItem(
                                "BIOTECH", "Biotechnology", "NON_FINANCIAL_OPERATING", "PE",
                                null, null, Instant.parse("2026-01-01T00:00:00Z"), null, false, true, 18L),
                        new IndustryValuationReview.OverviewItem(
                                "I43", "Semiconductors", "NON_FINANCIAL_OPERATING", "PE",
                                new BigDecimal("18"), new BigDecimal("22"), Instant.parse("2026-01-02T12:00:00Z"),
                                "https://example.com/research", true, true, 42L)
                ),
                Map.of("NON_FINANCIAL_OPERATING", List.of("PE", "PB", "PS", "PFCF", "EV_EBITDA"), "BANK", List.of("PE", "PB"))
        );
        when(service.listOverview()).thenReturn(overview);

        mvc(service).perform(get("/industry-valuation"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].industryCode").value("BIOTECH"))
                .andExpect(jsonPath("$.items[0].configured").value(false))
                .andExpect(jsonPath("$.items[0].canPropose").value(true))
                .andExpect(jsonPath("$.items[0].activeStockCount").value(18))
                .andExpect(jsonPath("$.items[1].industryCode").value("I43"))
                .andExpect(jsonPath("$.items[1].configured").value(true))
                .andExpect(jsonPath("$.items[1].assumedMultipleLow").value(18))
                .andExpect(jsonPath("$.supportedMetrics.BANK[1]").value("PB"));

        verify(service).listOverview();
        verify(service, never()).propose(any());
        verify(service, never()).save(any(), any());
        verifyNoMoreInteractions(service);
    }

    // 修改前：POST 建立 durable job；修改後：POST 直接回傳未保存草稿，PUT 才確認正式設定。
    @Test
    void reviewAndProposalReturnDirectDtosWithoutImplicitSave() throws Exception {
        IndustryValuationProposalService service = mock(IndustryValuationProposalService.class);
        when(service.get("I43")).thenReturn(review());
        when(service.propose("I43")).thenReturn(new IndustryValuationReview.Draft(
                "NON_FINANCIAL_OPERATING", "PE", new BigDecimal("18"), new BigDecimal("22"),
                LocalDate.of(2026, 1, 2), "待人工確認", List.of(new IndustryValuationReview.Source(
                "https://example.com/research", "研究來源", LocalDate.of(2026, 1, 1), "假設依據"))));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/industry-valuation/I43")).andExpect(status().isOk())
                .andExpect(jsonPath("$.industryCode").value("I43"))
                .andExpect(jsonPath("$.current.primaryMetric").value("PE"))
                .andExpect(jsonPath("$.canPropose").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(post("/industry-valuation/I43/proposals")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.assumedMultipleLow").value(18))
                .andExpect(jsonPath("$.asOf").value("2026-01-02"))
                .andExpect(jsonPath("$.sources[0].url").value("https://example.com/research"))
                .andExpect(jsonPath("$.proposalId").doesNotExist())
                .andExpect(jsonPath("$.taskCode").doesNotExist());

        verify(service).get("I43");
        verify(service).propose("I43");
        verify(service, never()).save(any(), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void explicitPutBindsBaseTimestampAndReturnsTheConfirmedSetting() throws Exception {
        IndustryValuationProposalService service = mock(IndustryValuationProposalService.class);
        when(service.save(eq("I43"), any(Update.class))).thenReturn(review());
        MockMvc mvc = mvc(service);

        mvc.perform(put("/industry-valuation/I43/config").contentType(MediaType.APPLICATION_JSON).content("""
                {"baseEffectiveAt":"2020-01-01T00:00:00Z","modelCode":"NON_FINANCIAL_OPERATING",
                 "primaryMetric":"PE","assumedMultipleLow":18,"assumedMultipleHigh":22,
                 "rationale":"人工確認依據","sourceUrl":"https://example.com/research"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.industryCode").value("I43"))
                .andExpect(jsonPath("$.current.effectiveAt").value("2026-01-02T12:00:00Z"))
                .andExpect(jsonPath("$.recalculationTaskCode").doesNotExist());

        ArgumentCaptor<Update> saved = ArgumentCaptor.forClass(Update.class);
        verify(service).save(eq("I43"), saved.capture());
        assertThat(saved.getValue().baseEffectiveAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(saved.getValue().assumedMultipleLow()).isEqualByComparingTo("18");
        assertThat(saved.getValue().sourceUrl()).isEqualTo("https://example.com/research");
        verify(service, never()).propose(any());
    }

    @Test
    void staleBaseReturnsConflictWithReadableMessage() throws Exception {
        IndustryValuationProposalService service = mock(IndustryValuationProposalService.class);
        when(service.save(eq("I43"), any(Update.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "正式設定已改變，請重新載入後確認。"));

        mvc(service).perform(put("/industry-valuation/I43/config").contentType(MediaType.APPLICATION_JSON).content("""
                {"baseEffectiveAt":"2020-01-01T00:00:00Z","modelCode":"BANK","primaryMetric":"PB"}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("正式設定已改變，請重新載入後確認。"));
    }

    private MockMvc mvc(IndustryValuationProposalService service) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return MockMvcBuilders.standaloneSetup(new IndustryValuationController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private IndustryValuationReview review() {
        return new IndustryValuationReview("I43", "Semiconductors",
                new IndustryValuationReview.Current(Instant.parse("2026-01-02T12:00:00Z"),
                        "NON_FINANCIAL_OPERATING", "PE", new BigDecimal("18"), new BigDecimal("22"),
                        "人工確認依據", "https://example.com/research"),
                Map.of("NON_FINANCIAL_OPERATING", List.of("PE", "PS", "PFCF", "EV_EBITDA")), true);
    }
}
