package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisApiError;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisApiResponse;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestControllerAdvice(assignableTypes = AnalysisController.class)
public class AnalysisExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<AnalysisApiResponse<Void>> responseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String fallback = status == null ? "REQUEST_FAILED" : status.name();
        return error(ex.getStatusCode(), codeFrom(ex.getReason(), fallback), "Request failed", Map.of(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AnalysisApiResponse<Void>> unreadable(HttpMessageNotReadableException ex) {
        String code = codeFrom(ex.getMostSpecificCause() == null ? null : ex.getMostSpecificCause().getMessage(), "INVALID_REQUEST");
        return error(HttpStatus.BAD_REQUEST, code, "Invalid request", Map.of(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AnalysisApiResponse<Void>> badRequest(IllegalArgumentException ex) {
        String code = codeFrom(ex.getMessage(), "INVALID_REQUEST");
        HttpStatus status = Set.of("STOCK_NOT_FOUND", "SNAPSHOT_NOT_FOUND").contains(code)
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return error(status, code, status == HttpStatus.NOT_FOUND ? "Resource not found" : "Invalid request", Map.of(), null);
    }


    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<AnalysisApiResponse<Void>> storage(DataAccessException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_STORAGE_UNAVAILABLE", "Analysis storage unavailable", Map.of(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AnalysisApiResponse<Void>> state(IllegalStateException ex) {
        String code = codeFrom(ex.getMessage(), "ANALYSIS_STATE_ERROR");
        if (Set.of("DETERMINISM_VIOLATION").contains(code))
            return error(HttpStatus.CONFLICT, code, "State conflict", Map.of(), null);
        if (code.startsWith("ARTIFACT_"))
            return error(HttpStatus.SERVICE_UNAVAILABLE, code, "Analysis storage unavailable", Map.of(), null);
        return error(HttpStatus.UNPROCESSABLE_ENTITY, code, "Analysis cannot satisfy the requested contract", Map.of(), null);
    }

    private ResponseEntity<AnalysisApiResponse<Void>> error(HttpStatusCode status, String code, String message,
                                                              Map<String,String> fields, String retryAfter) {
        String requestId = UUID.randomUUID().toString();
        AnalysisApiResponse<Void> body = AnalysisApiResponse.error(message, new AnalysisApiError(code, fields, requestId));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (retryAfter != null) builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        return builder.body(body);
    }

    private String codeFrom(String value, String fallback) {
        if (value == null) return fallback;
        String candidate = value.trim();
        int colon = candidate.indexOf(':');
        if (colon > 0) candidate = candidate.substring(0, colon).trim();
        return candidate.matches("[A-Z][A-Z0-9_]{2,80}") ? candidate : fallback;
    }
}
