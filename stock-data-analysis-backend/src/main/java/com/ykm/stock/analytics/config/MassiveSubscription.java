package com.ykm.stock.analytics.config;

import com.ykm.stock.analytics.service.stock.MassiveService;
import java.time.LocalDate;

/** Massive Stocks 訂閱的存取範圍與限流。 */
public enum MassiveSubscription {
    FREE(2, 4.8),
    STARTER(5, Double.POSITIVE_INFINITY);

    private final int priceHistoryYears;
    private final double requestsPerMinute;

    /** FREE 官方上限為每分鐘 5 次，沿用稍低的本地節奏；付費 Stocks 不另設人工上限。 */
    MassiveSubscription(int priceHistoryYears, double requestsPerMinute) {
        this.priceHistoryYears = priceHistoryYears;
        this.requestsPerMinute = requestsPerMinute;
    }

    /** 行情按請求日往前計算方案可取得的年數。 */
    public LocalDate priceHistoryFrom(LocalDate today) { return today.minusYears(priceHistoryYears); }

    /** 保留 MassiveService 共用 RateLimiter；不另建限流框架。 */
    public double requestsPerMinute() { return requestsPerMinute; }
}
