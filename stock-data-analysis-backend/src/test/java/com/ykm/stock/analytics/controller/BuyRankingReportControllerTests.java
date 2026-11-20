package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.AppliedFiltersDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingOptionsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.MarketBackgroundDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.ScopeSummaryDto;
import com.ykm.stock.analytics.service.report.BuyRankingReportService;
import com.ykm.stock.analytics.service.report.BuyRankingReportService.CachedReportSnapshot;
import com.ykm.stock.analytics.service.report.ReportExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuyRankingReportControllerTests {
    private BuyRankingReportService reportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reportService = mock(BuyRankingReportService.class);
        ReportExportService exportService = new ReportExportService();
        BuyRankingReportController controller = new BuyRankingReportController(
                reportService, exportService, new com.fasterxml.jackson.databind.ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void optionsEndpointReturnsAuthoritativeLists() throws Exception {
        when(reportService.getOptions()).thenReturn(new BuyRankingOptionsDto(
                List.of("SECTOR", "PORTFOLIO", "CUSTOM"),
                List.of("WATCHLIST", "HOLDING"),
                List.of("COMMON_STOCK", "ADR", "ETF", "REIT"),
                List.of("TIER_1", "TIER_2", "TIER_3", "TIER_4"),
                List.of("ALL", "MIXED", "STRONG"),
                List.of("TIER_COMPOSITE", "NET_RR_DESC"),
                List.of("POSITION", "SWING"),
                300));

        mockMvc.perform(get("/reports/buy-ranking/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCandidates").value(300))
                .andExpect(jsonPath("$.scopeModes[0]").value("SECTOR"));
    }

    @Test
    void queryAndExportEndpointsShareContractAndReturnJsonErrors() throws Exception {
        CachedReportSnapshot snapshot = new CachedReportSnapshot(
                "rep-123", Instant.parse("2026-11-20T04:00:00Z"), LocalDate.of(2026, 11, 19),
                "2026-11-20 12:00:00 HKT", Instant.parse("2026-11-20T04:00:00Z"), "zh-HK",
                new MarketBackgroundDto("RISK_ON", null, null, null, null, null, false, true, LocalDate.of(2026, 11, 19), null),
                new ScopeSummaryDto("CUSTOM", "Custom", List.of(), List.of(), List.of(), 0, 0, 0, 300, false, List.of(), List.of(), List.of()),
                Map.of("TIER_1", 0, "TIER_2", 0, "TIER_3", 0, "TIER_4", 0),
                new AppliedFiltersDto("CUSTOM", List.of(), List.of(), List.of("NVDA"), List.of("COMMON_STOCK"), true,
                        List.of("TIER_1", "TIER_2", "TIER_3", "TIER_4"), "ALL", null, null, "TIER_COMPOSITE", "POSITION"),
                List.of());
        when(reportService.generateReport(any(), any())).thenReturn(new BuyRankingResponse(
                "rep-123", LocalDate.of(2026, 11, 19), "2026-11-20 12:00:00 HKT",
                Instant.parse("2026-11-20T04:00:00Z"), "zh-HK", "RISK_ON",
                snapshot.marketContext(), snapshot.scopeSummary(), 0, 1, 300,
                snapshot.tierCounts(), snapshot.appliedFilters(), List.of()));
        when(reportService.resolveForExport(eq("rep-123"), any(), any())).thenReturn(snapshot);

        mockMvc.perform(post("/reports/buy-ranking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeMode\":\"CUSTOM\",\"customSymbols\":\"NVDA, AVGO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value("rep-123"));

        mockMvc.perform(post("/reports/buy-ranking/export")
                        .param("format", "CSV")
                        .param("reportId", "rep-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("USMarketTools_BuyRanking_Custom_20261119.csv")));

        mockMvc.perform(post("/reports/buy-ranking/export").param("format", "PDF"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("INVALID_EXPORT_FORMAT"));

        when(reportService.resolveForExport(eq("expired"), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.GONE, "REPORT_SNAPSHOT_EXPIRED"));
        mockMvc.perform(post("/reports/buy-ranking/export")
                        .param("format", "XLSX")
                        .param("reportId", "expired"))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("REPORT_SNAPSHOT_EXPIRED"));
    }
}
