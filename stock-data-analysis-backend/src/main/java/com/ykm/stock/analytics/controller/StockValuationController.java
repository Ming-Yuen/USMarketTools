package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.service.futu.FutuStockDataService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("valuation")
@RequiredArgsConstructor
public class StockValuationController {
    private final StockValuationService valuations;
    private final FutuStockDataService futu;

    public record RecalculateRequest(String figi, LocalDate from, LocalDate through) {}

    @GetMapping("/{figi}/history")
    public List<ValuationResultDto> history(
            @PathVariable String figi,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through,
            @RequestParam(defaultValue = "MONTH_END") String frequency,
            @RequestParam(defaultValue = "LOCAL") String source) {
        if ("LOCAL".equalsIgnoreCase(source)) {
            return valuations.history(figi, from, through, frequency);
        }
        if ("FUTU".equalsIgnoreCase(source)) {
            return futu.history(figi, from, through, frequency);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must be LOCAL or FUTU");
    }

    @PostMapping("/recalculate")
    public List<ValuationResultDto> recalculate(@RequestBody RecalculateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recalculate request body is required");
        }
        return valuations.recalculate(request.figi(), request.from(), request.through());
    }
}
