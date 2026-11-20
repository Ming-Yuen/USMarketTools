package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.valuation.AdrShareRatioService;
import com.ykm.stock.analytics.service.valuation.AdrShareRatioService.AdrShareRatioRequest;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("valuation/adr-ratios")
@RequiredArgsConstructor
public class AdrShareRatioController {
    private final AdrShareRatioService adrShareRatioService;
    private final StockRepository stockRepository;
    private final StockValuationService stockValuationService;

    @GetMapping
    public List<AdrShareRatio> listAll() {
        return adrShareRatioService.findAll();
    }

    @GetMapping("/{symbol}")
    public AdrShareRatio getBySymbol(@PathVariable String symbol) {
        return adrShareRatioService.findBySymbol(symbol);
    }

    @PutMapping
    public AdrShareRatio upsert(@RequestBody AdrShareRatioRequest request) {
        AdrShareRatio saved = adrShareRatioService.upsert(request);
        List<Stock> matching = stockRepository.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of(saved.getSymbol()));
        if (!matching.isEmpty()) {
            List<String> figis = matching.stream().map(Stock::getFigi).toList();
            stockValuationService.refreshLatestValuations(figis, null);
        }
        return saved;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", ex.getReason() == null ? "ADR_RATIO_OPERATION_FAILED" : ex.getReason()));
    }
}
