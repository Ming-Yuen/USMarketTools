package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聯準會 FRED H.10 官方日頻匯率客戶端。
 * 統一換算為「1 單位外幣折合美元（usdPerUnit）」；對 HTTP 401/403 立即失敗不重試。
 */
@Component
@Slf4j
public class FredFxClient {
    public static final String SOURCE_FRED = "FRED";
    private static final MathContext MC = MathContext.DECIMAL128;

    public record SeriesSpec(String currency, String seriesId, boolean usdPerForeign) {}

    public record RatePoint(String currency, LocalDate rateDate, BigDecimal usdPerUnit, String seriesId) {}

    public static final Map<String, SeriesSpec> SUPPORTED_SERIES = buildSupportedSeries();

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public FredFxClient(
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

    public List<RatePoint> fetchSeries(SeriesSpec spec, LocalDate observationStart, LocalDate observationEnd) {
        if (spec == null) {
            throw new IllegalArgumentException("SeriesSpec is required");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY_NOT_CONFIGURED");
        }
        String body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/series/observations")
                                .queryParam("series_id", spec.seriesId())
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
            throw new IllegalStateException("FRED_API_HTTP_ERROR:" + spec.seriesId() + ":" + status.value(), ex);
        }
        return parseObservations(spec, body);
    }

    public List<RatePoint> parseObservations(SeriesSpec spec, String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonBody);
        } catch (Exception ex) {
            throw new IllegalStateException("FRED_JSON_PARSE_FAILED:" + spec.seriesId(), ex);
        }
        JsonNode observations = root.path("observations");
        if (!observations.isArray() || observations.isEmpty()) {
            return List.of();
        }
        List<RatePoint> points = new ArrayList<>();
        for (JsonNode node : observations) {
            String dateText = node.path("date").asText("");
            String valueText = node.path("value").asText("").trim();
            if (dateText.isBlank() || valueText.isBlank() || ".".equals(valueText) || "ND".equalsIgnoreCase(valueText)) {
                continue;
            }
            LocalDate date;
            BigDecimal raw;
            try {
                date = LocalDate.parse(dateText);
                raw = new BigDecimal(valueText);
            } catch (Exception ex) {
                continue;
            }
            if (raw.signum() <= 0) {
                continue;
            }
            BigDecimal usdPerUnit = spec.usdPerForeign()
                    ? raw.setScale(8, RoundingMode.HALF_UP)
                    : BigDecimal.ONE.divide(raw, 8, RoundingMode.HALF_UP);
            if (usdPerUnit.signum() <= 0) {
                continue;
            }
            points.add(new RatePoint(spec.currency(), date, usdPerUnit, spec.seriesId()));
        }
        return List.copyOf(points);
    }

    private static Map<String, SeriesSpec> buildSupportedSeries() {
        Map<String, SeriesSpec> map = new LinkedHashMap<>();
        register(map, "EUR", "DEXUSEU", true);
        register(map, "GBP", "DEXUSUK", true);
        register(map, "AUD", "DEXUSAL", true);
        register(map, "TWD", "DEXTAUS", false);
        register(map, "CNY", "DEXCHUS", false);
        register(map, "CAD", "DEXCAUS", false);
        register(map, "JPY", "DEXJPUS", false);
        register(map, "HKD", "DEXHKUS", false);
        register(map, "DKK", "DEXDNUS", false);
        register(map, "KRW", "DEXKOUS", false);
        register(map, "BRL", "DEXBZUS", false);
        register(map, "MXN", "DEXMXUS", false);
        register(map, "INR", "DEXINUS", false);
        register(map, "ZAR", "DEXSFUS", false);
        register(map, "SEK", "DEXSDUS", false);
        register(map, "CHF", "DEXSZUS", false);
        return Map.copyOf(map);
    }

    private static void register(Map<String, SeriesSpec> map, String currency, String seriesId, boolean usdPerForeign) {
        map.put(currency, new SeriesSpec(currency, seriesId, usdPerForeign));
    }
}
