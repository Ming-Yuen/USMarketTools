package com.ykm.stock.analytics.constant;

import java.util.List;
import java.util.Map;

public final class TaskCode {

    private TaskCode() {}

    public static final String STOCK_DAILY = "STOCK_DAILY";
    public static final String STOCK_CLASSIFICATION_DAILY_SYNC = "STOCK_CLASSIFICATION_DAILY_SYNC";
    public static final String EARNINGS_FORECAST_DAILY_SYNC = "EARNINGS_FORECAST_DAILY_SYNC";
    public static final String STOCK_FUNDAMENTAL_DAILY_SYNC = "STOCK_FUNDAMENTAL_DAILY_SYNC";
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
    public static final String CREDIT_SPREAD_DAILY_SYNC = "CREDIT_SPREAD_DAILY_SYNC";
    public static final String SHORT_INTEREST_SYNC = "SHORT_INTEREST_SYNC";
    public static final String CHIP_EVENT_DAILY_SYNC = "CHIP_EVENT_DAILY_SYNC";
    public static final String INSTITUTIONAL_13F_SYNC = "INSTITUTIONAL_13F_SYNC";

    /** 已合併或淘汰之舊排程代碼；啟動 reconcile 前須先自 job_config 與 Quartz 退役清理。 */
    public static final List<String> RETIRED_SCHEDULE_CODES = List.of(
            "SEC_FINANCIAL_FACT_DAILY",
            "FUTU_VALUATION_DAILY_SYNC",
            "SEC_SHARES_DAILY_SYNC",
            LEGACY_FINANCIAL_REPORT_DAILY_SYNC,
            STOCK_SPLIT_DAILY,
            FINANCIAL_REPORT_DAILY_SYNC,
            SEC_FINANCIAL_FACTS_DAILY_SYNC);

    public static final Map<String, String> PREVIOUS_SCHEDULE_CODES = Map.of(
            STOCK_CLASSIFICATION_DAILY_SYNC, "FUTU_CLASSIFICATION_DAILY_SYNC",
            EARNINGS_FORECAST_DAILY_SYNC, "FUTU_EARNINGS_DAILY_SYNC");
}
