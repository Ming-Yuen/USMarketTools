package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.StockClassificationResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.service.stock.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("stock")
public class StockController {
    private final StockService stockService;

    @PostMapping("classification")
    public StockClassificationResponse stockClassification(StockClassificationRequest request) {
        return stockService.getStockClassification(request);
    }

    @GetMapping("screen")
    public StockScreenResponse screen(@RequestParam(required = false) String symbol,
                                      @RequestParam(required = false) String industry,
                                      @RequestParam(required = false) String conceptCode,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return stockService.screen(symbol, industry, conceptCode, page, size);
    }
}
