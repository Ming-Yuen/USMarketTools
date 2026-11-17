package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 每輪主檔只下載一次官方完整 ticker 對照；不以公司名稱或其他 provider 的 CIK 猜測。 */
@Component
public class SecTickerClient {
    static final String URL = "https://www.sec.gov/files/company_tickers.json";
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String userAgent;

    public SecTickerClient(RestClient.Builder builder, ObjectMapper objectMapper,
                           @Value("${sec.user-agent}") String userAgent) {
        this.client = builder.clone().defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
        this.objectMapper = objectMapper;
        this.userAgent = userAgent;
    }

    public Map<String, String> getTickerCiks() {
        String contact = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        if (contact.isBlank() || !contact.contains("@") || contact.contains("@localhost") || contact.contains("example.com"))
            throw new IllegalStateException("SEC_USER_AGENT must contain the application name and a real contact email address");
        String body = client.get().uri(URL).retrieve().onStatus(code -> code.value() != 200, (request, response) -> {
            throw new IllegalStateException("SEC_TICKERS_HTTP_STATUS: " + response.getStatusCode().value());
        }).body(String.class);
        try {
            return parse(body == null ? null : objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("SEC_TICKERS_INVALID", ex);
        }
    }

    /** 點號與連字號只在整份對照中唯一指向同一 CIK 時視為同一股票代碼。 */
    public static String tickerKey(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    static Map<String, String> parse(JsonNode root) {
        if (root == null || !root.isObject() || root.isEmpty()) throw new IllegalStateException("SEC_TICKERS_INVALID");
        Map<String, String> matches = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (JsonNode row : root) {
            JsonNode cik = row.path("cik_str");
            JsonNode ticker = row.path("ticker");
            if (!row.isObject() || !cik.isIntegralNumber() || !cik.canConvertToLong()
                    || cik.longValue() <= 0 || cik.longValue() > 9_999_999_999L
                    || !ticker.isTextual() || ticker.asText().isBlank()
                    || !row.path("title").isTextual() || row.path("title").asText().isBlank())
                throw new IllegalStateException("SEC_TICKERS_INVALID");
            String key = tickerKey(ticker.asText());
            String normalized = String.format(Locale.ROOT, "%010d", cik.longValue());
            String previous = matches.putIfAbsent(key, normalized);
            if (previous != null && !previous.equals(normalized)) ambiguous.add(key);
        }
        for (String key : ambiguous) matches.remove(key);
        return Map.copyOf(matches);
    }
}
