package com.ykm.stock.analytics.client;

import com.ykm.stock.analytics.model.dto.thirdparty.response.YahooVixChartResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;

/** Public Yahoo chart client used only for the daily ^VIX close series. */
@Component
public class YahooVixClient {
    private static final String URL = "https://query1.finance.yahoo.com/v8/finance/chart/%5EVIX"
            + "?range=1mo&interval=1d&includePrePost=false&events=history";
    private final RestClient restClient;

    public YahooVixClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public YahooVixChartResponse fetchDaily() {
        YahooVixChartResponse response = restClient.get()
                .uri(URI.create(URL))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 stock-data-analysis/1.0")
                .retrieve()
                .body(YahooVixChartResponse.class);
        if (response == null || response.getChart() == null || response.getChart().getResult() == null
                || response.getChart().getResult().isEmpty()) {
            throw new IllegalStateException("Yahoo ^VIX chart returned no result");
        }
        return response;
    }
}
