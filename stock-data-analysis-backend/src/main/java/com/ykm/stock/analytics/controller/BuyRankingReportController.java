package com.ykm.stock.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingOptionsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingResponse;
import com.ykm.stock.analytics.service.report.BuyRankingReportService;
import com.ykm.stock.analytics.service.report.BuyRankingReportService.CachedReportSnapshot;
import com.ykm.stock.analytics.service.report.ReportExportService;
import com.ykm.stock.analytics.service.report.ReportExportService.ExportedFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 08_Enhancement：產業與自選股「建議買入排行榜」報表查詢與一鍵下載控制器。
 * 比照專案慣例不加 /api/v1 前綴，所有錯誤統一以 JSON {"message": "..."} 回應。
 */
@RestController
@RequestMapping("reports/buy-ranking")
@RequiredArgsConstructor
public class BuyRankingReportController {
    private final BuyRankingReportService reportService;
    private final ReportExportService exportService;
    private final ObjectMapper objectMapper;

    @GetMapping(value = "/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public BuyRankingOptionsDto options() {
        return reportService.getOptions();
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public BuyRankingResponse query(
            @RequestBody BuyRankingRequest request,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return reportService.generateReport(request, acceptLanguage);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam("format") String format,
            @RequestParam(value = "reportId", required = false) String reportId,
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        String normalizedFormat = format == null ? "" : format.trim().toUpperCase(Locale.ROOT);
        if (!BuyRankingDtos.EXPORT_XLSX.equals(normalizedFormat) && !BuyRankingDtos.EXPORT_CSV.equals(normalizedFormat)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_EXPORT_FORMAT");
        }
        BuyRankingRequest request = parseOptionalRequest(rawBody);
        CachedReportSnapshot snapshot = reportService.resolveForExport(reportId, request, acceptLanguage);
        ExportedFile exported = exportService.export(snapshot, normalizedFormat);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(exported.fileName())
                .build());
        headers.set(HttpHeaders.CONTENT_TYPE, exported.contentType());
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
        return ResponseEntity.ok().headers(headers).body(exported.content());
    }

    private BuyRankingRequest parseOptionalRequest(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return null;
        }
        String trimmed = new String(rawBody, StandardCharsets.UTF_8).trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(trimmed, BuyRankingRequest.class);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_REQUEST");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "BUY_RANKING_REPORT_FAILED";
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", "INVALID_REPORT_REQUEST"));
    }
}
