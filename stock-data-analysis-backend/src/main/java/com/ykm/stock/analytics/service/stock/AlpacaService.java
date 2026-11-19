package com.ykm.stock.analytics.service.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.google.common.util.concurrent.RateLimiter;
import com.ykm.stock.analytics.model.dto.alpaca.AlpacaBarsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;

/** Alpaca 未復權日線、期權合約／快照與即時新聞；共用帳戶限流，不執行資料庫寫入。 */
@Slf4j
@Service
public class AlpacaService {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final RestClient client;
    private final RestClient tradingClient;
    private final String apiKey;
    private final String secretKey;
    private final String optionsFeed;
    private final RateLimiter limiter;
    private final MarketStatusService marketStatusService;

    public record OptionContractRecord(
            String contractSymbol,
            String type,
            LocalDate expirationDate,
            BigDecimal strikePrice,
            Long openInterest,
            LocalDate openInterestDate,
            BigDecimal closePrice,
            LocalDate closePriceDate
    ) {}

    public record OptionSnapshotRecord(
            String contractSymbol,
            BigDecimal impliedVolatility,
            BigDecimal delta,
            BigDecimal gamma,
            BigDecimal theta,
            BigDecimal vega,
            BigDecimal bidPrice,
            BigDecimal askPrice,
            BigDecimal lastTradePrice,
            BigDecimal dailyClose,
            Long dailyVolume,
            Instant quoteTimestamp,
            LocalDate volumeSessionDate
    ) {}

    public record AlpacaNewsItem(
            String id,
            String headline,
            String summary,
            String source,
            String url,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public AlpacaService(String baseUrl, String apiKey, String secretKey,
                         double requestsPerMinute, MarketStatusService marketStatusService) {
        this(baseUrl, "https://paper-api.alpaca.markets", "indicative", apiKey, secretKey, requestsPerMinute, marketStatusService);
    }

    /** 建立 API 客戶端；缺少憑證時延遲至實際呼叫才報錯，不影響其他 Job 啟動。 */
    @Autowired
    public AlpacaService(@Value("${alpaca.base-url:https://data.alpaca.markets}") String baseUrl,
                         @Value("${alpaca.trading-base-url:https://paper-api.alpaca.markets}") String tradingBaseUrl,
                         @Value("${alpaca.options.feed:indicative}") String optionsFeed,
                         @Value("${alpaca.apiKey:${ALPACA_API_KEY:}}") String apiKey,
                         @Value("${alpaca.apiSecret:${ALPACA_SECRET_KEY:}}") String secretKey,
                         @Value("${alpaca.requests-per-minute:180}") double requestsPerMinute,
                         MarketStatusService marketStatusService) {
        this.marketStatusService = marketStatusService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.optionsFeed = optionsFeed == null || optionsFeed.isBlank() ? "indicative" : optionsFeed.trim();
        if (requestsPerMinute <= 0) throw new IllegalArgumentException("Invalid Alpaca request rate");
        this.limiter = RateLimiter.create(requestsPerMinute / 60.0);
        HttpComponentsClientHttpRequestFactory http = new HttpComponentsClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(15));
        http.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(http).build();
        this.tradingClient = RestClient.builder().baseUrl(tradingBaseUrl).requestFactory(http).build();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !secretKey.isBlank();
    }

    public String getOptionsFeed() {
        return optionsFeed;
    }

    /** 逐頁讀取指定股票與日期區間的 SIP raw 日線；每頁成功取得後交給呼叫端處理。 */
    public void readHistoricalBars(List<String> symbols, LocalDate from, LocalDate through,
                                   Consumer<AlpacaBarsResponse> onPage) {
        if (apiKey.isBlank() || secretKey.isBlank()) throw new IllegalStateException("ALPACA_API_CREDENTIALS_REQUIRED");
        if (symbols == null || symbols.isEmpty() || from == null || through == null || from.isAfter(through))
            throw new IllegalArgumentException("Invalid Alpaca historical bars request");
        String tickers = String.join(",", symbols);
        // Instant.toString() 輸出含秒的 RFC3339 UTC 格式，避免 Alpaca 拒絕 T00:00-05:00。
        String start = from.atStartOfDay(NEW_YORK).toInstant().toString();
        String end = through.atTime(marketStatusService.getSessionCloseTime(through))
                .atZone(NEW_YORK).toInstant().toString();
        String token = null;
        Set<String> seenTokens = new HashSet<>();
        int completedPages = 0;
        do {
            String pageToken = token;
            limiter.acquire();
            AlpacaBarsResponse response = client.get()
                    .uri(builder -> {
                        builder.path("/v2/stocks/bars")
                                .queryParam("symbols", tickers).queryParam("timeframe", "1Day")
                                .queryParam("start", start).queryParam("end", end)
                                .queryParam("feed", "sip").queryParam("adjustment", "raw")
                                // 沿用 asof 預設的當前證券映射，讓最新 Symbol 包含同一證券更名前的歷史。
                                .queryParam("limit", 10000).queryParam("sort", "asc");
                        if (pageToken != null) builder.queryParam("page_token", pageToken);
                        return builder.build();
                    })
                    .header("APCA-API-KEY-ID", apiKey)
                    .header("APCA-API-SECRET-KEY", secretKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve().body(AlpacaBarsResponse.class);
            if (response == null || response.getBars() == null)
                throw new IllegalStateException("Alpaca returned an incomplete historical bars page");
            onPage.accept(response);
            completedPages++;
            token = response.getNextPageToken();
            if (token != null && !token.isBlank() && !seenTokens.add(token))
                throw new IllegalStateException("Alpaca returned a repeated page token");
            if (completedPages % 10 == 0 || token == null || token.isBlank())
                log.info("Alpaca history page progress | completedPages={} symbols={} from={} through={} morePages={}",
                        completedPages, symbols.size(), from, through, token != null && !token.isBlank());
        } while (token != null && !token.isBlank());
    }

    /** 顯式指定到期區間拉取標的之活躍期權合約清單（含 open_interest 與收盤價），共用帳戶限流並翻頁至完整。 */
    public List<OptionContractRecord> getOptionContracts(String underlyingSymbol, LocalDate expirationFrom,
                                                         LocalDate expirationThrough, int maxPages) {
        requireConfigured();
        List<OptionContractRecord> results = new ArrayList<>();
        String token = null;
        Set<String> seenTokens = new HashSet<>();
        int pageCap = Math.max(1, maxPages);
        for (int page = 0; page < pageCap; page++) {
            String pageToken = token;
            limiter.acquire();
            JsonNode root;
            try {
                root = tradingClient.get()
                        .uri(builder -> {
                            builder.path("/v2/options/contracts")
                                    .queryParam("underlying_symbols", underlyingSymbol)
                                    .queryParam("status", "active")
                                    .queryParam("expiration_date_gte", expirationFrom.toString())
                                    .queryParam("expiration_date_lte", expirationThrough.toString())
                                    .queryParam("limit", 1000);
                            if (pageToken != null) {
                                builder.queryParam("page_token", pageToken);
                            }
                            return builder.build();
                        })
                        .header("APCA-API-KEY-ID", apiKey)
                        .header("APCA-API-SECRET-KEY", secretKey)
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpStatusCodeException ex) {
                throw mapAlpacaError("ALPACA_OPTIONS_CONTRACTS", ex);
            }
            if (root == null) {
                break;
            }
            JsonNode contracts = root.path("option_contracts");
            if (contracts.isArray()) {
                for (JsonNode node : contracts) {
                    String sym = textOrNull(node, "symbol");
                    String type = textOrNull(node, "type");
                    LocalDate exp = parseLocalDate(textOrNull(node, "expiration_date"));
                    BigDecimal strike = parseBigDecimal(node.path("strike_price"));
                    if (sym == null || type == null || exp == null || strike == null) {
                        continue;
                    }
                    results.add(new OptionContractRecord(
                            sym,
                            type.toLowerCase(),
                            exp,
                            strike,
                            parseLong(node.path("open_interest")),
                            parseLocalDate(textOrNull(node, "open_interest_date")),
                            parseBigDecimal(node.path("close_price")),
                            parseLocalDate(textOrNull(node, "close_price_date"))
                    ));
                }
            }
            token = textOrNull(root, "next_page_token");
            if (token == null || !seenTokens.add(token)) {
                break;
            }
        }
        return results;
    }

    /** 顯式指定 feed 與到期／行權價範圍拉取期權快照（含 IV、Greeks、雙邊報價與當日成交量），共用帳戶限流。 */
    public Map<String, OptionSnapshotRecord> getOptionSnapshots(String underlyingSymbol, LocalDate expirationFrom,
                                                                LocalDate expirationThrough, BigDecimal strikeMin,
                                                                BigDecimal strikeMax, int maxPages) {
        requireConfigured();
        Map<String, OptionSnapshotRecord> results = new LinkedHashMap<>();
        String token = null;
        Set<String> seenTokens = new HashSet<>();
        int pageCap = Math.max(1, maxPages);
        for (int page = 0; page < pageCap; page++) {
            String pageToken = token;
            limiter.acquire();
            JsonNode root;
            try {
                root = client.get()
                        .uri(builder -> {
                            builder.path("/v1beta1/options/snapshots/{symbol}")
                                    .queryParam("feed", optionsFeed)
                                    .queryParam("expiration_date_gte", expirationFrom.toString())
                                    .queryParam("expiration_date_lte", expirationThrough.toString())
                                    .queryParam("limit", 1000);
                            if (strikeMin != null) {
                                builder.queryParam("strike_price_gte", strikeMin.toPlainString());
                            }
                            if (strikeMax != null) {
                                builder.queryParam("strike_price_lte", strikeMax.toPlainString());
                            }
                            if (pageToken != null) {
                                builder.queryParam("page_token", pageToken);
                            }
                            return builder.build(underlyingSymbol);
                        })
                        .header("APCA-API-KEY-ID", apiKey)
                        .header("APCA-API-SECRET-KEY", secretKey)
                        .header(HttpHeaders.ACCEPT, "application/json")
                        .retrieve()
                        .body(JsonNode.class);
            } catch (HttpStatusCodeException ex) {
                throw mapAlpacaError("ALPACA_OPTIONS_SNAPSHOTS", ex);
            }
            if (root == null) {
                break;
            }
            JsonNode snapshots = root.path("snapshots");
            if (snapshots.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = snapshots.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String contractSym = entry.getKey();
                    JsonNode snap = entry.getValue();
                    JsonNode greeks = snap.path("greeks");
                    JsonNode quote = snap.path("latestQuote");
                    JsonNode trade = snap.path("latestTrade");
                    JsonNode dailyBar = snap.path("dailyBar");
                    Instant quoteTs = parseInstant(textOrNull(quote, "t"));
                    Instant barTs = parseInstant(textOrNull(dailyBar, "t"));
                    LocalDate volSessionDate = barTs == null ? null : barTs.atZone(NEW_YORK).toLocalDate();
                    results.put(contractSym, new OptionSnapshotRecord(
                            contractSym,
                            parseBigDecimal(snap.path("impliedVolatility")),
                            parseBigDecimal(greeks.path("delta")),
                            parseBigDecimal(greeks.path("gamma")),
                            parseBigDecimal(greeks.path("theta")),
                            parseBigDecimal(greeks.path("vega")),
                            parseBigDecimal(quote.path("bp")),
                            parseBigDecimal(quote.path("ap")),
                            parseBigDecimal(trade.path("p")),
                            parseBigDecimal(dailyBar.path("c")),
                            parseLong(dailyBar.path("v")),
                            quoteTs,
                            volSessionDate
                    ));
                }
            }
            token = textOrNull(root, "next_page_token");
            if (token == null || !seenTokens.add(token)) {
                break;
            }
        }
        return results;
    }

    /** 按需拉取單股近期即時新聞（免費方案 200 req/min，不爬外部全文），共用帳戶限流。 */
    public List<AlpacaNewsItem> getNews(String symbol, Instant start, Instant end, int limit) {
        requireConfigured();
        limiter.acquire();
        int effectiveLimit = Math.max(1, Math.min(limit, 50));
        JsonNode root;
        try {
            root = client.get()
                    .uri(builder -> {
                        builder.path("/v1beta1/news")
                                .queryParam("symbols", symbol)
                                .queryParam("limit", effectiveLimit)
                                .queryParam("sort", "desc");
                        if (start != null) {
                            builder.queryParam("start", start.toString());
                        }
                        if (end != null) {
                            builder.queryParam("end", end.toString());
                        }
                        return builder.build();
                    })
                    .header("APCA-API-KEY-ID", apiKey)
                    .header("APCA-API-SECRET-KEY", secretKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException ex) {
            throw mapAlpacaError("ALPACA_NEWS", ex);
        }
        if (root == null || !root.path("news").isArray()) {
            return List.of();
        }
        List<AlpacaNewsItem> items = new ArrayList<>();
        for (JsonNode node : root.path("news")) {
            String id = node.path("id").asText("").trim();
            String headline = textOrNull(node, "headline");
            Instant createdAt = parseInstant(textOrNull(node, "created_at"));
            if (headline == null || createdAt == null) {
                continue;
            }
            items.add(new AlpacaNewsItem(
                    id.isBlank() ? null : id,
                    headline,
                    textOrNull(node, "summary"),
                    textOrNull(node, "source"),
                    textOrNull(node, "url"),
                    createdAt,
                    parseInstant(textOrNull(node, "updated_at"))
            ));
        }
        return items;
    }

    /** 取得個股／ETF 最新即時成交價（強制核對成交時間 trade.t >= minTradeTimestamp，避免盤中把昨日舊成交價誤當作今日現價）。 */
    public BigDecimal getLatestStockPrice(String symbol, Instant minTradeTimestamp) {
        requireConfigured();
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        limiter.acquire();
        JsonNode root;
        try {
            root = client.get()
                    .uri(builder -> builder.path("/v2/stocks/{symbol}/trades/latest")
                            .queryParam("feed", "iex")
                            .build(symbol.trim().toUpperCase()))
                    .header("APCA-API-KEY-ID", apiKey)
                    .header("APCA-API-SECRET-KEY", secretKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw mapAlpacaError("ALPACA_LATEST_TRADE", ex);
            }
            return null;
        }
        if (root == null) {
            return null;
        }
        JsonNode trade = root.path("trade");
        if (minTradeTimestamp != null) {
            Instant tradeTs = parseInstant(textOrNull(trade, "t"));
            if (tradeTs == null || tradeTs.isBefore(minTradeTimestamp)) {
                return null;
            }
        }
        BigDecimal price = parseBigDecimal(trade.path("p"));
        return price != null && price.signum() > 0 ? price : null;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("ALPACA_API_CREDENTIALS_REQUIRED");
        }
    }

    private RuntimeException mapAlpacaError(String prefix, HttpStatusCodeException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.value() == 401 || status.value() == 403) {
            return new IllegalStateException(prefix + "_UNAUTHORIZED:" + status.value(), ex);
        }
        return new IllegalStateException(prefix + "_HTTP_" + status.value(), ex);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode val = node.path(field);
        if (val.isMissingNode() || val.isNull()) {
            return null;
        }
        String text = val.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseLocalDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }
}
