package com.ykm.stock.analytics.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class CnnFearAndGreedClient {

    private static final String GRAPH_DATA_URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    private static final String REFERER = "https://www.cnn.com/markets/fear-and-greed";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/125.0.0.0 Safari/537.36";

    private final RestClient restClient;

    public String fetchRawJson() {
        String rawJson = restClient.get()
                .uri(GRAPH_DATA_URL)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .header(HttpHeaders.REFERER, REFERER)
                .retrieve()
                .body(String.class);

        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalStateException("CNN Fear & Greed API returned an empty response");
        }

        return rawJson;
    }
}
