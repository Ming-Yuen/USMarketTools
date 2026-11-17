package com.ykm.stock.analytics.controller;

import com.ykm.stock.analytics.model.dto.stockInsight.UploadJobResponse;
import com.ykm.stock.analytics.service.upload.UploadJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("upload")
@RequiredArgsConstructor
public class DataUploadController {
    private final UploadJobService service;

    @GetMapping(value = "/types", produces = "application/json")
    public List<String> types() {
        return service.types();
    }

    @PostMapping(consumes = "multipart/form-data", produces = "application/json")
    public ResponseEntity<UploadJobResponse> upload(@RequestParam UUID id, @RequestParam String type, @RequestParam MultipartFile file) throws IOException {
        return ResponseEntity.accepted().body(service.submit(id, type, file));
    }

    @GetMapping(value = "/{id}", produces = "application/json")
    public UploadJobResponse status(@PathVariable UUID id) {
        return service.get(id);
    }

    @ExceptionHandler({IllegalArgumentException.class, java.nio.charset.CharacterCodingException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception ex) {
        String message = ex instanceof java.nio.charset.CharacterCodingException ? "CSV_INVALID_UTF8" : ex.getMessage();
        return ResponseEntity.badRequest().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("message", message == null ? "CSV_INVALID" : message));
    }
}
