package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.model.entity.MacroCreditSpreadDaily;
import lombok.extern.slf4j.Slf4j;
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
 * 聯準會 FRED 官方高收益債信用利差（BAMLH0A0HYM2）客戶端。
 * 直接保留原值百分點（例如 3.45 代表 3.45% 即 345 bps）；對 HTTP 401/403 立即失敗不重試。
 */
@Component
@Slf4j
public class FredCreditSpreadClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public record CreditSpreadPoint(LocalDate observationDate, BigDecimal oasPct, LocalDate realtimeStart, String seriesId) {}

    public FredCreditSpreadClient(
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

    public List<CreditSpreadPoint> fetchHighYieldOas(LocalDate observationStart, LocalDate observationEnd) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY_NOT_CONFIGURED");
        }
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/series/observations")
                                .queryParam("series_id", MacroCreditSpreadDaily.SERIES_HY_OAS)
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
            throw new IllegalStateException("FRED_HY_OAS_HTTP_ERROR:" + status.value(), ex);
        }
        return parseObservations(body);
    }

    public List<CreditSpreadPoint> parseObservations(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBody);
        } catch (Exception ex) {
            throw new IllegalStateException("FRED_HY_OAS_JSON_PARSE_FAILED", ex);
        }
        JsonNode observations = root.path("observations");
        if (!observations.isArray() || observations.isEmpty()) {
            return List.of();
        }
        List<CreditSpreadPoint> points = new ArrayList<>();
        for (JsonNode node : observations) {
            String dateText = node.path("date").asText("").trim();
            String valueText = node.path("value").asText("").trim();
            if (dateText.isBlank() || valueText.isBlank() || ".".equals(valueText) || "ND".equalsIgnoreCase(valueText)) {
                continue;
            }
            LocalDate date;
            BigDecimal oasPct;
            LocalDate realtimeStart = null;
            try {
                date = LocalDate.parse(dateText);
                oasPct = new BigDecimal(valueText).setScale(4, RoundingMode.HALF_UP);
                String rt = node.path("realtime_start").asText("").trim();
                if (!rt.isBlank()) {
                    realtimeStart = LocalDate.parse(rt);
                }
            } catch (Exception ex) {
                continue;
            }
            if (oasPct.signum() < 0) {
                continue;
            }
            points.add(new CreditSpreadPoint(date, oasPct, realtimeStart, MacroCreditSpreadDaily.SERIES_HY_OAS));
        }
        return List.copyOf(points);
    }
}
