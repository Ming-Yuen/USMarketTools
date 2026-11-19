package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.service.analysis.StockAnalysisService;
import com.ykm.stock.analytics.service.stock.AnnualEarningsForecastSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("analysis")
@RequiredArgsConstructor
public class AnalysisController {
    private final StockAnalysisService analysisService;
    private final AnnualEarningsForecastSyncService annualForecastSync;

    @PostMapping("/report")
    public StockAnalysisReportDto report(@Valid @RequestBody AnalysisReportRequest request) {
        if (request.valuationDate() == null) {
            annualForecastSync.ensureFreshAnnualForecast(request.figi());
        }
        return analysisService.analyze(request);
    }
}
