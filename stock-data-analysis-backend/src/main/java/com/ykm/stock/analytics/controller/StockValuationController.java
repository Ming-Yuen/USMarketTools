package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.service.futu.FutuStockDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("valuation")
@RequiredArgsConstructor
public class StockValuationController {
    private final FutuStockDataService futu;

    @GetMapping("/{figi}/history")
    public List<ValuationResultDto> history(@PathVariable String figi,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through,
            @RequestParam(defaultValue = "MONTH_END") String frequency) {
        return futu.history(figi, from, through, frequency);
    }
}
