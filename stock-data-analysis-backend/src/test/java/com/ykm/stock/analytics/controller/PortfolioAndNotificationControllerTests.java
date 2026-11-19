package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.InAppNotificationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemViewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioOverviewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.service.portfolio.PortfolioClosingService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService;
import com.ykm.stock.analytics.service.portfolio.PortfolioRiskConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortfolioAndNotificationControllerTests {
    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T22:00:00Z");

    private PortfolioManagementService portfolioService;
    private PortfolioRiskConfigService riskConfigService;
    private PortfolioClosingService closingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        portfolioService = mock(PortfolioManagementService.class);
        riskConfigService = mock(PortfolioRiskConfigService.class);
        closingService = mock(PortfolioClosingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new PortfolioController(portfolioService, riskConfigService, closingService),
                new NotificationController(closingService)
        ).build();
    }

    @Test
    void portfolioEndpointsSupportOverviewUpsertDeleteConfigAndHealth() throws Exception {
        PortfolioRiskConfigDto cfg = new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD");
        when(portfolioService.getOverview()).thenReturn(new PortfolioOverviewDto(
                cfg, List.of(), List.of(), 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, List.of()));
        when(portfolioService.upsertItem(eq("F_NVDA"), any())).thenReturn(new PortfolioItemViewDto(
                "F_NVDA", "NVDA", "NVIDIA", "USD", "SEMI", "Semiconductors", "HOLDING",
                new BigDecimal("50"), new BigDecimal("120"), LocalDate.of(2026, 11, 18),
                new BigDecimal("50"), new BigDecimal("120"), BigDecimal.ONE, false, "SAME_BASIS",
                LocalDate.of(2026, 11, 18), new BigDecimal("125"), new BigDecimal("6250"), new BigDecimal("6000"),
                new BigDecimal("250"), new BigDecimal("0.041667"), new BigDecimal("0.062500"), "core", FIXED_NOW));
        when(riskConfigService.getConfig()).thenReturn(cfg);
        when(closingService.getHealthSummary(true)).thenReturn(new PortfolioHealthSummaryDto(
                LocalDate.of(2026, 11, 18), "LIVE", FIXED_NOW, "RISK_ON",
                1, 0, 0, 0, 0, 0, true, true, 0, List.of(), List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/portfolio").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.totalCapital").value(100000.0));

        mockMvc.perform(put("/portfolio/F_NVDA")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"HOLDING\",\"shares\":50,\"avgCost\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("NVDA"));

        mockMvc.perform(delete("/portfolio/F_NVDA").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());
        verify(portfolioService).deleteItem("F_NVDA");

        mockMvc.perform(get("/portfolio/health").param("live", "true").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceMode").value("LIVE"))
                .andExpect(jsonPath("$.structureHealthy").value(true));
    }

    @Test
    void notificationEndpointsSupportListUnreadMarkReadAndLatestBriefing() throws Exception {
        InAppNotificationDto notif = new InAppNotificationDto(
                10L, "HOLDING:2026-11-18:F_NVDA:HOLDING_STOP_BREACHED", LocalDate.of(2026, 11, 18),
                "CRITICAL", "PORTFOLIO_HOLDING", "HOLDING_STOP_BREACHED", "F_NVDA", "NVDA", "NVIDIA",
                "/stockAnalysis/F_NVDA", Map.of("closePrice", 105), false, null, FIXED_NOW);
        when(closingService.listNotifications("CRITICAL", null, true, 20)).thenReturn(List.of(notif));
        when(closingService.countUnread()).thenReturn(2L);
        when(closingService.markRead(10L)).thenReturn(true);
        when(closingService.markAllRead()).thenReturn(2);
        when(closingService.getLatestBriefing()).thenReturn(Optional.of(new DailyClosingBriefingDto(
                LocalDate.of(2026, 11, 18), "RISK_ON", "NEUTRAL", LocalDate.of(2026, 11, 17), true,
                new BigDecimal("15.2"), new BigDecimal("4.35"), List.of(), List.of(), null,
                List.of(), List.of(), 1, 0, 0, FIXED_NOW, FIXED_NOW)));

        mockMvc.perform(get("/notifications")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("severity", "CRITICAL")
                        .param("unreadOnly", "true")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].alertCode").value("HOLDING_STOP_BREACHED"))
                .andExpect(jsonPath("$[0].targetRoute").value("/stockAnalysis/F_NVDA"));

        mockMvc.perform(get("/notifications/unread-count").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(2));

        mockMvc.perform(post("/notifications/10/read").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(true));

        mockMvc.perform(post("/notifications/read-all").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(2));

        mockMvc.perform(get("/notifications/briefing/latest").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketRegime").value("RISK_ON"));
    }
}
