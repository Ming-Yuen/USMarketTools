package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.service.stock.StockService.BatchTrackedUpdateResult;
import com.ykm.stock.analytics.service.stock.StockService.ScreenCriteria;
import com.ykm.stock.analytics.service.stock.StockService.TrackedUpdateResult;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("stock")
public class StockController {
    private final StockService stockService;
    private final StockValuationService stockValuationService;

    public record TrackedRequest(Boolean tracked) {}

    public record BatchTrackedRequest(List<String> figis, Boolean tracked) {}

    @PostMapping("classification")
    public StockClassificationResponse stockClassification(StockClassificationRequest request) {
        return stockService.getStockClassification(request);
    }

    @GetMapping("screen")
    public StockScreenResponse screen(@RequestParam(required = false) String symbol,
                                      @RequestParam(required = false) String industry,
                                      @RequestParam(required = false) String conceptCode,
                                      @RequestParam(required = false) String marketCapTier,
                                      @RequestParam(required = false) String verdict,
                                      @RequestParam(required = false) BigDecimal minRevenueGrowthYoy,
                                      @RequestParam(required = false) BigDecimal minRoe,
                                      @RequestParam(required = false) BigDecimal minFcfMargin,
                                      @RequestParam(required = false) BigDecimal minHistoricalPercentile,
                                      @RequestParam(required = false) BigDecimal maxHistoricalPercentile,
                                      @RequestParam(required = false) String sortBy,
                                      @RequestParam(required = false) String sortDir,
                                      @RequestParam(required = false) String assetType,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return stockService.screen(new ScreenCriteria(
                symbol, industry, conceptCode, marketCapTier, verdict,
                minRevenueGrowthYoy, minRoe, minFcfMargin,
                minHistoricalPercentile, maxHistoricalPercentile,
                sortBy, sortDir, page, size, assetType));
    }

    @Transactional
    @PatchMapping("{figi}/tracked")
    public TrackedUpdateResult updateTracked(@PathVariable String figi, @RequestBody TrackedRequest request) {
        TrackedUpdateResult result = stockService.updateTracked(figi, request == null ? null : request.tracked());
        if (result.changed()) {
            stockValuationService.onTrackedChanged(List.of(result.figi()));
        }
        return result;
    }

    @Transactional
    @PostMapping("tracked/batch")
    public BatchTrackedUpdateResult updateTrackedBatch(@RequestBody BatchTrackedRequest request) {
        BatchTrackedUpdateResult result = stockService.updateTrackedBatch(
                request == null ? null : request.figis(),
                request == null ? null : request.tracked());
        if (!result.changedFigis().isEmpty()) {
            stockValuationService.onTrackedChanged(result.changedFigis());
        }
        return result;
    }
}
