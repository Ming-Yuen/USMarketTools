package com.ykm.stock.analytics.service.stock;

import com.google.common.util.concurrent.RateLimiter;
import com.ykm.stock.analytics.config.MassiveSubscription;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplit;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplitListResponse;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Massive 提供股票主檔；拆股事件由 Futu 復權資料提供。 */
@Service
@Slf4j
public class MassiveService {
    private final RestClient restClient;
    private final URI baseUri;
    private final String apiKey;
    private final RateLimiter rateLimiter;

    public MassiveService(@Value("${massive.baseUrl:https://api.massive.com}") String baseUrl,
                          @Value("${massive.apiKey:${MASSIVE_API_KEY:}}") String apiKey,
                          @Value("${massive.subscription:FREE}") MassiveSubscription subscription) {
        this.baseUri = URI.create(baseUrl.replaceAll("/+$", ""));
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.rateLimiter = RateLimiter.create(subscription.requestsPerMinute() / 60.0);
        HttpComponentsClientHttpRequestFactory http = new HttpComponentsClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(30));
        http.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(http)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }

    @Retryable(retryFor = {ResourceAccessException.class, HttpClientErrorException.TooManyRequests.class,
            HttpServerErrorException.class}, maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30000))
    public List<MassiveTickerDetails> getAllStockTickers() {
        MassiveTickerListResponse page = get("/v3/reference/tickers",
                Map.of("market", "stocks", "locale", "us", "active", "true",
                        "order", "asc", "limit", "1000", "sort", "ticker"), MassiveTickerListResponse.class);
        List<MassiveTickerDetails> rows = new ArrayList<>();
        Set<String> pages = new HashSet<>();
        while (true) {
            if (page == null || !"OK".equals(page.getStatus()) || page.getResults() == null
                    || page.getResults().stream().anyMatch(java.util.Objects::isNull)
                    || (page.getCount() != null && page.getCount() != page.getResults().size()))
                throw new IllegalStateException("MASSIVE_STOCK_MASTER_INCOMPLETE");
            rows.addAll(page.getResults());
            if (page.getNextUrl() == null || page.getNextUrl().isBlank()) break;
            if (page.getResults().isEmpty() || !pages.add(page.getNextUrl()))
                throw new IllegalStateException("MASSIVE_STOCK_MASTER_PAGINATION_INVALID");
            page = next(page.getNextUrl(), MassiveTickerListResponse.class);
        }
        if (rows.isEmpty()) throw new IllegalStateException("MASSIVE_STOCK_MASTER_EMPTY");
        log.info("Massive stock master received | rows={}", rows.size());
        return rows;
    }

    /** 批量取得 execution_date >= from 的全市場拆股／反拆股／股票股利事件，不過濾 adjustment_type。 */
    @Retryable(retryFor = {ResourceAccessException.class, HttpClientErrorException.TooManyRequests.class,
            HttpServerErrorException.class}, maxAttempts = 5,
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30000))
    public List<MassiveSplit> getSplitsFrom(LocalDate executionDateFrom) {
        if (executionDateFrom == null) throw new IllegalArgumentException("MASSIVE_SPLIT_FROM_DATE_REQUIRED");
        MassiveSplitListResponse page = get("/stocks/v1/splits",
                Map.of("execution_date.gte", executionDateFrom.toString(),
                        "order", "asc", "limit", "1000", "sort", "execution_date"), MassiveSplitListResponse.class);
        List<MassiveSplit> rows = new ArrayList<>();
        Set<String> pages = new HashSet<>();
        while (true) {
            if (page == null || !"OK".equals(page.getStatus()) || page.getResults() == null
                    || page.getResults().stream().anyMatch(java.util.Objects::isNull)
                    || (page.getCount() != null && page.getCount() != page.getResults().size()))
                throw new IllegalStateException("MASSIVE_SPLITS_INCOMPLETE");
            rows.addAll(page.getResults());
            if (page.getNextUrl() == null || page.getNextUrl().isBlank()) break;
            if (page.getResults().isEmpty() || !pages.add(page.getNextUrl()))
                throw new IllegalStateException("MASSIVE_SPLITS_PAGINATION_INVALID");
            page = next(page.getNextUrl(), MassiveSplitListResponse.class);
        }
        log.info("Massive splits received | from={} rows={}", executionDateFrom, rows.size());
        return rows;
    }

    private <T> T get(String path, Map<String, String> query, Class<T> type) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(baseUri).path(path);
        query.forEach(uri::queryParam);
        return request(uri.build().encode().toUri(), type);
    }

    private <T> T next(String nextUrl, Class<T> type) {
        URI nextUri = URI.create(nextUrl);
        boolean configuredHost = java.util.Objects.equals(baseUri.getAuthority(), nextUri.getAuthority())
                && java.util.Objects.equals(baseUri.getScheme(), nextUri.getScheme());
        boolean officialHost = "https".equalsIgnoreCase(nextUri.getScheme())
                && Set.of("api.massive.com", "api.polygon.io").contains(nextUri.getHost());
        if (!configuredHost && !officialHost) throw new IllegalStateException("MASSIVE_UNEXPECTED_PAGINATION_HOST");
        return request(nextUri, type);
    }

    private <T> T request(URI uri, Class<T> type) {
        if (apiKey.isBlank()) throw new IllegalStateException("MASSIVE_API_KEY_REQUIRED");
        rateLimiter.acquire();
        return restClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).retrieve().body(type);
    }
}
