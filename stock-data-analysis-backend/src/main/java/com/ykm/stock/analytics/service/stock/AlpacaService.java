package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.google.common.util.concurrent.RateLimiter;
import com.ykm.stock.analytics.model.dto.alpaca.AlpacaBarsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;

/** Alpaca 未復權日線；不執行資料庫寫入。 */
@Slf4j
@Service
public class AlpacaService {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final RestClient client;
    private final String apiKey;
    private final String secretKey;
    private final RateLimiter limiter;
    private final MarketStatusService marketStatusService;

    /** 建立 API 客戶端；缺少憑證時延遲至實際呼叫才報錯，不影響其他 Job 啟動。 */
    public AlpacaService(@Value("${alpaca.base-url:https://data.alpaca.markets}") String baseUrl,
                         @Value("${alpaca.apiKey:${ALPACA_API_KEY:}}") String apiKey,
                         @Value("${alpaca.apiSecret:${ALPACA_SECRET_KEY:}}") String secretKey,
                         @Value("${alpaca.requests-per-minute:180}") double requestsPerMinute,
                         MarketStatusService marketStatusService) {
        this.marketStatusService = marketStatusService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        if (requestsPerMinute <= 0) throw new IllegalArgumentException("Invalid Alpaca request rate");
        this.limiter = RateLimiter.create(requestsPerMinute / 60.0);
        HttpComponentsClientHttpRequestFactory http = new HttpComponentsClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(15));
        http.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(http).build();
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
}
