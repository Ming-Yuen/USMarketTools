package com.ykm.stock.analytics.service.market;

import com.google.common.util.concurrent.RateLimiter;
import com.ykm.stock.analytics.config.FinnhubProperties;
import com.ykm.stock.analytics.model.dto.finnhub.response.Quote;
import com.ykm.stock.analytics.model.dto.finnhub.response.Recommendation;
import com.ykm.stock.analytics.model.dto.finnhub.response.Symbol;
import com.ykm.stock.analytics.model.dto.finnhub.response.EarningsCalendarResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
// 用途：負責「Finnhub服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class FinnhubService {

    private final RestClient restClient;
    private final FinnhubProperties finnhubProperties;
    private final RateLimiter rateLimiter = RateLimiter.create(58.0 / 60.0);

    // 用途：處理Finnhub服務，作為「Finnhub服務」流程中的處理步驟。
    public FinnhubService(FinnhubProperties finnhubProperties) {
        this.finnhubProperties = finnhubProperties;
        this.restClient = RestClient.builder()
                .baseUrl(finnhubProperties.getBaseUrl())
                .requestFactory(createRequestFactory(finnhubProperties))
                .requestInterceptor(loggingInterceptor())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Retryable(
            retryFor = {
                    ResourceAccessException.class,
                    SocketTimeoutException.class,
                    HttpClientErrorException.TooManyRequests.class
            },
            maxAttempts = 5,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 16000)
    )
    // 用途：呼叫外部 provider endpoint 並依指定 response type 反序列化結果。
    public <T> T get(String path, Map<String, Object> queryParams, ParameterizedTypeReference<T> responseType) {
        rateLimiter.acquire();
        return restClient.get()
                .uri(uriBuilder -> buildUri(uriBuilder, path, queryParams))
                .retrieve()
                .body(responseType);
    }

    // 用途：建立Uri，作為「Finnhub服務」流程中的處理步驟。
    private java.net.URI buildUri(UriBuilder uriBuilder, String path, Map<String, Object> queryParams) {
        UriBuilder builder = uriBuilder.path(path);

        if (queryParams != null) {
            queryParams.forEach(builder::queryParam);
        }

        builder.queryParam("token", finnhubProperties.getApiKey());
        return builder.build();
    }

    // 用途：建立Request建立，作為「Finnhub服務」流程中的處理步驟。
    private HttpComponentsClientHttpRequestFactory createRequestFactory(FinnhubProperties properties) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getTimeout()));
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeout()));
        return factory;
    }

    // 用途：處理loggingInterceptor，作為「Finnhub服務」流程中的處理步驟。
    private ClientHttpRequestInterceptor loggingInterceptor() {
        return (request, body, execution) -> {
            String requestUrl = sanitizeUrl(request.getURI());
            try {
                log.info("Request: {} {}", request.getMethod(), requestUrl);
                ClientHttpResponse response = execution.execute(request, body);
                log.info("Response: {} {} {}", request.getMethod(), requestUrl, response.getStatusCode().value());
                return response;
            } catch (IOException e) {
                log.error("IO Error during request to {}: {}", requestUrl, e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error during request to {}: {}", requestUrl, e.getMessage(), e);
                throw e;
            }
        };
    }

    // 用途：處理sanitizeUrl，作為「Finnhub服務」流程中的處理步驟。
    private String sanitizeUrl(URI uri) {
        return uri.toString().replaceAll("([?&]token=)[^&]*", "$1***");
    }

    // 用途：取得股票Symbols，作為「Finnhub服務」流程中的處理步驟。
    public List<Symbol> getStockSymbols(String exchange) {
        return get("/stock/symbol", Map.of("exchange", exchange), new ParameterizedTypeReference<List<Symbol>>() {});
    }

    // 用途：取得股票行情，作為「Finnhub服務」流程中的處理步驟。
    public Quote getStockQuote(String symbol) {
        return get("/quote", Map.of("symbol", symbol), new ParameterizedTypeReference<Quote>() {});
    }

    // 用途：取得Recommendation，作為「Finnhub服務」流程中的處理步驟。
    public Recommendation getRecommendation(String symbol) {
        return get("/stock/recommendation", Map.of("symbol", symbol), new ParameterizedTypeReference<Recommendation>() {});
    }

    // 用途：取得Earnings日曆，作為「Finnhub服務」流程中的處理步驟。
    public EarningsCalendarResponse getEarningsCalendar(LocalDate from, LocalDate to) {
        return get("/calendar/earnings", Map.of("from", from, "to", to),
                new ParameterizedTypeReference<EarningsCalendarResponse>() {});
    }
}
