package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenFIGI 官方免費批次映射客戶端（無需 API Key 支援每批次最多 10 筆、20 req/min 限流）。
 * 僅用於 13F 缺 FIGI 且 Massive CUSIP 未命中的去重 CUSIP 補查；多筆歧義時回傳空值不誤配。
 */
@Component
@Slf4j
public class OpenFigiClient {
    private static final int FREE_BATCH_LIMIT = 10;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter = RateLimiter.create(20.0 / 60.0);

    public record FigiMatch(String compositeFigi, String shareClassFigi, String ticker) {}

    public OpenFigiClient(
            ObjectMapper objectMapper,
            @Value("${openfigi.base-url:https://api.openfigi.com}") String baseUrl) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** 批次將美國證券 CUSIP 對應至唯一 compositeFIGI；若有歧義或無法核實則不包含於結果 Map。 */
    public Map<String, FigiMatch> mapUsCusips(List<String> cusips) {
        if (cusips == null || cusips.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = cusips.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase())
                .distinct()
                .toList();
        Map<String, FigiMatch> resolved = new LinkedHashMap<>();
        for (int start = 0; start < distinct.size(); start += FREE_BATCH_LIMIT) {
            List<String> batch = distinct.subList(start, Math.min(start + FREE_BATCH_LIMIT, distinct.size()));
            List<Map<String, String>> payload = new ArrayList<>(batch.size());
            for (String cusip : batch) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("idType", "ID_CUSIP");
                item.put("idValue", cusip);
                item.put("exchCode", "US");
                payload.add(item);
            }
            rateLimiter.acquire();
            String body;
            try {
                body = restClient.post()
                        .uri("/v3/mapping")
                        .body(payload)
                        .retrieve()
                        .body(String.class);
            } catch (HttpStatusCodeException ex) {
                HttpStatusCode status = ex.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    throw new IllegalStateException("OPENFIGI_UNAUTHORIZED:" + status.value(), ex);
                }
                log.warn("OpenFIGI batch mapping HTTP error | status={} batchSize={}", status.value(), batch.size());
                continue;
            } catch (RuntimeException ex) {
                log.warn("OpenFIGI batch mapping failed | batchSize={} reason={}", batch.size(), ex.getMessage());
                continue;
            }
            parseBatchResponse(batch, body, resolved);
        }
        return resolved;
    }

    private void parseBatchResponse(List<String> batch, String body, Map<String, FigiMatch> target) {
        if (body == null || body.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            log.warn("OpenFIGI response parse failed: {}", ex.getMessage());
            return;
        }
        if (!root.isArray()) {
            return;
        }
        int limit = Math.min(batch.size(), root.size());
        for (int i = 0; i < limit; i++) {
            JsonNode data = root.get(i).path("data");
            if (!data.isArray() || data.isEmpty()) {
                continue;
            }
            Set<String> composites = new LinkedHashSet<>();
            String shareClass = null;
            String ticker = null;
            for (JsonNode entry : data) {
                String comp = entry.path("compositeFIGI").asText("").trim();
                if (!comp.isBlank()) {
                    composites.add(comp);
                    if (shareClass == null) {
                        String sc = entry.path("shareClassFIGI").asText("").trim();
                        if (!sc.isBlank()) {
                            shareClass = sc;
                        }
                    }
                    if (ticker == null) {
                        String tk = entry.path("ticker").asText("").trim();
                        if (!tk.isBlank()) {
                            ticker = tk;
                        }
                    }
                }
            }
            // 僅接受唯一 compositeFIGI，杜絕同一 CUSIP 歧義誤配。
            if (composites.size() == 1) {
                target.put(batch.get(i), new FigiMatch(composites.iterator().next(), shareClass, ticker));
            }
        }
    }
}
