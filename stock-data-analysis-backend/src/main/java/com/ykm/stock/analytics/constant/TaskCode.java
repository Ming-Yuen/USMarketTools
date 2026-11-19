package com.ykm.stock.analytics.constant;

import java.util.Map;

public final class TaskCode {

    private TaskCode() {}

    public static final String STOCK_DAILY = "STOCK_DAILY";
    public static final String STOCK_CLASSIFICATION_DAILY_SYNC = "STOCK_CLASSIFICATION_DAILY_SYNC";
    public static final String EARNINGS_FORECAST_DAILY_SYNC = "EARNINGS_FORECAST_DAILY_SYNC";
    public static final String FINANCIAL_REPORT_DAILY_SYNC = "FINANCIAL_REPORT_DAILY_SYNC";
    public static final String LEGACY_FINANCIAL_REPORT_DAILY_SYNC = "FUTU_FINANCIAL_REPORT_DAILY_SYNC";
    public static final String STOCK_QUOTE_DAILY = "STOCK_QUOTE_DAILY";
    public static final String MARKET_SENTIMENT_DAILY = "MARKET_SENTIMENT_DAILY";
    public static final String TREASURY_DAILY_SYNC = "TREASURY_DAILY_SYNC";
    public static final String MARKET_EVENT_DAILY_SYNC = "MARKET_EVENT_DAILY_SYNC";
    public static final String STOCK_SPLIT_DAILY = "STOCK_SPLIT_DAILY";
    public static final String STOCK_VALUATION_DAILY = "STOCK_VALUATION_DAILY";
    public static final String SEC_FINANCIAL_FACTS_DAILY_SYNC = "SEC_FINANCIAL_FACTS_DAILY_SYNC";
    public static final String FX_RATE_DAILY_SYNC = "FX_RATE_DAILY_SYNC";

    public static final Map<String, String> PREVIOUS_SCHEDULE_CODES = Map.of(
            SEC_FINANCIAL_FACTS_DAILY_SYNC, "SEC_SHARES_DAILY_SYNC",
            FINANCIAL_REPORT_DAILY_SYNC, LEGACY_FINANCIAL_REPORT_DAILY_SYNC,
            STOCK_CLASSIFICATION_DAILY_SYNC, "FUTU_CLASSIFICATION_DAILY_SYNC",
            EARNINGS_FORECAST_DAILY_SYNC, "FUTU_EARNINGS_DAILY_SYNC");
}
