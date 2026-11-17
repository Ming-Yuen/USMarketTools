package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.IndustryValuationReview;
import com.ykm.stock.analytics.service.valuation.IndustryValuationProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping(value = "industry-valuation", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class IndustryValuationController {
    private final IndustryValuationProposalService service;

    @GetMapping("/{code}")
    public IndustryValuationReview review(@PathVariable String code) { return service.get(code); }

    @PostMapping("/{code}/proposals")
    public IndustryValuationReview.Draft propose(@PathVariable String code) { return service.propose(code); }

    @PutMapping("/{code}/config")
    public IndustryValuationReview update(@PathVariable String code, @RequestBody IndustryValuationReview.Update input) {
        return service.save(code, input);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> invalid(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", ex.getReason() == null ? "產業假設操作失敗。" : ex.getReason()));
    }
}
