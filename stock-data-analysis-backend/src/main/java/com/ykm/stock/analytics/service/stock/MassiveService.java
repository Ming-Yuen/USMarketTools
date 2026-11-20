package com.ykm.stock.analytics.service.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.util.concurrent.RateLimiter;
import com.ykm.stock.analytics.config.MassiveSubscription;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplit;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplitListResponse;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Massive 提供股票主檔、雙週做空餘額、CUSIP 對照與新聞備援；拆股事件由 Futu 復權資料提供。 */
@Service
@Slf4j
public class MassiveService {
    private final RestClient restClient;
    private final URI baseUri;
    private final String apiKey;
    private final RateLimiter rateLimiter;

    public record MassiveShortInterestRow(
            String ticker,
            LocalDate settlementDate,
            Long shortInterest,
            Long avgDailyVolume,
            BigDecimal daysToCover
    ) {}

    public record MassiveNewsRow(
            String id,
            String title,
            String description,
            String publisher,
            String articleUrl,
            Instant publishedUtc
    ) {}

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

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** 批次拉取指定結算日（含）以後之官方雙週做空餘額，逐頁翻至 next_url 耗盡。 */
    public List<MassiveShortInterestRow> getShortInterestFrom(LocalDate settlementDateFrom) {
        if (settlementDateFrom == null) {
            throw new IllegalArgumentException("MASSIVE_SHORT_INTEREST_DATE_REQUIRED");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("settlement_date.gte", settlementDateFrom.toString());
        query.put("sort", "settlement_date.asc");
        query.put("limit", "1000");
        return fetchShortInterestPages(get("/stocks/v1/short-interest", query, JsonNode.class), 100);
    }

    /** 單股按需拉取最近數期官方雙週做空餘額（最新結算日優先）。 */
    public List<MassiveShortInterestRow> getShortInterestForTicker(String ticker, int limit) {
        if (ticker == null || ticker.isBlank()) {
            return List.of();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("ticker", ticker.trim().toUpperCase());
        query.put("sort", "settlement_date.desc");
        query.put("limit", String.valueOf(Math.max(1, Math.min(limit, 20))));
        return fetchShortInterestPages(get("/stocks/v1/short-interest", query, JsonNode.class), 1);
    }

    /** 依 9 碼 CUSIP 與選用報告日定點查詢 Massive 證券識別（回傳 composite_figi / share_class_figi），供 13F 精準對照。 */
    public List<MassiveTickerDetails> getTickersByCusip(String cusip, LocalDate reportDate) {
        if (cusip == null || cusip.isBlank()) {
            return List.of();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("cusip", cusip.trim().toUpperCase());
        query.put("market", "stocks");
        query.put("locale", "us");
        query.put("limit", "10");
        if (reportDate != null) {
            query.put("date", reportDate.toString());
        }
        MassiveTickerListResponse response = get("/v3/reference/tickers", query, MassiveTickerListResponse.class);
        if (response == null || response.getResults() == null) {
            return List.of();
        }
        return response.getResults();
    }

    /** 備援單股新聞查詢（不爬外部原文）。 */
    public List<MassiveNewsRow> getCompanyNews(String ticker, Instant publishedAfter, int limit) {
        if (ticker == null || ticker.isBlank()) {
            return List.of();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("ticker", ticker.trim().toUpperCase());
        query.put("order", "desc");
        query.put("sort", "published_utc");
        query.put("limit", String.valueOf(Math.max(1, Math.min(limit, 50))));
        if (publishedAfter != null) {
            query.put("published_utc.gte", publishedAfter.toString());
        }
        JsonNode root = get("/v2/reference/news", query, JsonNode.class);
        if (root == null || !root.path("results").isArray()) {
            return List.of();
        }
        List<MassiveNewsRow> rows = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String title = item.path("title").asText("").trim();
            String pubStr = item.path("published_utc").asText("").trim();
            if (title.isEmpty() || pubStr.isEmpty()) {
                continue;
            }
            Instant pub;
            try {
                pub = Instant.parse(pubStr);
            } catch (Exception ex) {
                continue;
            }
            rows.add(new MassiveNewsRow(
                    item.path("id").asText("").trim(),
                    title,
                    item.path("description").asText("").trim(),
                    item.path("publisher").path("name").asText("").trim(),
                    item.path("article_url").asText("").trim(),
                    pub
            ));
        }
        return rows;
    }

    private List<MassiveShortInterestRow> fetchShortInterestPages(JsonNode firstPage, int maxPages) {
        List<MassiveShortInterestRow> rows = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        JsonNode page = firstPage;
        for (int i = 0; i < maxPages && page != null; i++) {
            JsonNode results = page.path("results");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String ticker = item.path("ticker").asText("").trim();
                    String dateStr = item.path("settlement_date").asText("").trim();
                    if (ticker.isEmpty() || dateStr.isEmpty() || !item.path("short_interest").isNumber()) {
                        continue;
                    }
                    LocalDate settlement;
                    try {
                        settlement = LocalDate.parse(dateStr);
                    } catch (Exception ex) {
                        continue;
                    }
                    long shortShares = item.path("short_interest").longValue();
                    Long avgVol = item.path("avg_daily_volume").isNumber() ? item.path("avg_daily_volume").longValue() : null;
                    BigDecimal dtc = item.path("days_to_cover").isNumber() ? item.path("days_to_cover").decimalValue() : null;
                    rows.add(new MassiveShortInterestRow(ticker, settlement, shortShares, avgVol, dtc));
                }
            }
            String nextUrl = page.path("next_url").asText("").trim();
            if (nextUrl.isEmpty()) {
                break;
            }
            if (!seenUrls.add(nextUrl)) {
                throw new IllegalStateException("MASSIVE_SHORT_INTEREST_CURSOR_LOOP:" + nextUrl);
            }
            if (i + 1 >= maxPages) {
                throw new IllegalStateException("MASSIVE_SHORT_INTEREST_PAGE_LIMIT_EXCEEDED:" + maxPages);
            }
            page = next(nextUrl, JsonNode.class);
        }
        return rows;
    }

    private <T> T request(URI uri, Class<T> type) {
        if (apiKey.isBlank()) throw new IllegalStateException("MASSIVE_API_KEY_REQUIRED");
        rateLimiter.acquire();
        try {
            return restClient.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).retrieve().body(type);
        } catch (HttpStatusCodeException ex) {
            HttpStatusCode status = ex.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                throw new IllegalStateException("MASSIVE_API_UNAUTHORIZED:" + status.value(), ex);
            }
            throw ex;
        }
    }
}
