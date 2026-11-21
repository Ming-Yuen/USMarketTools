package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 聯準會 FRED 官方 Cboe VIX 收盤序列（VIXCLS）客戶端，供歷史缺口回補使用。
 * 對 HTTP 401/403 立即失敗不重試；僅解析有效正數收盤點數，不推斷盤後精確發布秒數。
 */
@Component
public class FredVixClient {
    public static final String SERIES_VIXCLS = "VIXCLS";
    public static final String SOURCE_FRED_VIXCLS = "FRED_CBOE_VIXCLS";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public record VixPoint(LocalDate observationDate, BigDecimal vixClose, LocalDate realtimeStart) {}

    public FredVixClient(
            ObjectMapper objectMapper,
            @Value("${fred.api.base-url:https://api.stlouisfed.org/fred}") String baseUrl,
            @Value("${fred.api.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public List<VixPoint> fetchVixCloses(LocalDate observationStart, LocalDate observationEnd) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY_NOT_CONFIGURED");
        }
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/series/observations")
                                .queryParam("series_id", SERIES_VIXCLS)
                                .queryParam("api_key", apiKey)
                                .queryParam("file_type", "json");
                        if (observationStart != null) {
                            uriBuilder.queryParam("observation_start", observationStart.toString());
                        }
                        if (observationEnd != null) {
                            uriBuilder.queryParam("observation_end", observationEnd.toString());
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new IllegalStateException("FRED_API_UNAUTHORIZED:" + status.value(), ex);
            }
            throw new IllegalStateException("FRED_VIXCLS_HTTP_ERROR:" + status.value(), ex);
        }
        return parseObservations(body);
    }

    public List<VixPoint> parseObservations(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBody);
        } catch (Exception ex) {
            throw new IllegalStateException("FRED_VIXCLS_JSON_PARSE_FAILED", ex);
        }
        JsonNode observations = root.path("observations");
        if (!observations.isArray() || observations.isEmpty()) {
            return List.of();
        }
        List<VixPoint> points = new ArrayList<>();
        for (JsonNode node : observations) {
            String dateText = node.path("date").asText("").trim();
            String valueText = node.path("value").asText("").trim();
            if (dateText.isBlank() || valueText.isBlank() || ".".equals(valueText) || "ND".equalsIgnoreCase(valueText)) {
                continue;
            }
            LocalDate date;
            BigDecimal close;
            LocalDate realtimeStart = null;
            try {
                date = LocalDate.parse(dateText);
                close = new BigDecimal(valueText).setScale(4, RoundingMode.HALF_UP);
                String rt = node.path("realtime_start").asText("").trim();
                if (!rt.isBlank()) {
                    realtimeStart = LocalDate.parse(rt);
                }
            } catch (Exception ex) {
                continue;
            }
            if (close.signum() <= 0) {
                continue;
            }
            points.add(new VixPoint(date, close, realtimeStart));
        }
        return List.copyOf(points);
    }
}
