package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemUpsertRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioItemViewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioOverviewDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigUpdateRequest;
import com.ykm.stock.analytics.service.portfolio.PortfolioClosingService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService;
import com.ykm.stock.analytics.service.portfolio.PortfolioRiskConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("portfolio")
@RequiredArgsConstructor
public class PortfolioController {
    private final PortfolioManagementService portfolioService;
    private final PortfolioRiskConfigService riskConfigService;
    private final PortfolioClosingService closingService;

    @GetMapping
    public PortfolioOverviewDto overview() {
        return portfolioService.getOverview();
    }

    @PutMapping("/{figi}")
    public PortfolioItemViewDto upsert(@PathVariable String figi,
                                       @RequestBody PortfolioItemUpsertRequest request) {
        return portfolioService.upsertItem(figi, request);
    }

    @DeleteMapping("/{figi}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String figi) {
        portfolioService.deleteItem(figi);
    }

    @GetMapping("/config")
    public PortfolioRiskConfigDto getConfig() {
        return riskConfigService.getConfig();
    }

    @PutMapping("/config")
    public PortfolioRiskConfigDto updateConfig(@RequestBody PortfolioRiskConfigUpdateRequest request) {
        return riskConfigService.updateConfig(request);
    }

    @GetMapping("/health")
    public PortfolioHealthSummaryDto health(@RequestParam(defaultValue = "false") boolean live) {
        return closingService.getHealthSummary(live);
    }
}
