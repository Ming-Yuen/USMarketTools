package com.ykm.stock.analytics.service.analysis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Massive 決定主檔範圍；Finnhub 詳細類型決定各排程與估值的證券適用範圍。 */
@Component
public class SecurityTypePolicy {
    /** 普通營運股（排除 ADR，供多股類判定、Futu 季度預測與收盤行情就緒門檻沿用，避免與 ADR 混淆）。 */
    public static final List<String> OPERATING_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part");
    /** 支援財報估值、同業比較與每日機會雷達的證券類型（含已核實換股比例的 ADR）。 */
    public static final List<String> VALUATION_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR");
    /** 支援純技術與資金分析的 ETF／ETP 類型（財報估值標記 NOT_APPLICABLE_FOR_ETF）。 */
    public static final List<String> ETF_STOCK_TYPES = List.of("ETP", "ETF");
    public static final List<String> SEC_FILING_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR");
    public static final List<String> FINANCIAL_REPORT_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR", "Closed-End Fund", "Royalty Trst");
    public static final List<String> MARKET_DATA_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR", "ETP", "ETF", "Closed-End Fund", "Royalty Trst", "Unit");
    public static final List<String> BENCHMARK_ETF_SYMBOLS = List.of(
            "SPY", "QQQ", "SOXX", "VOO", "URA", "IWM", "DIA",
            "XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLY", "XLP", "XLU", "XLB", "XLRE");
    public static final List<String> SECTOR_ETF_SYMBOLS = List.of(
            "XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLY", "XLP", "XLU", "XLB", "XLRE");

    private static final Set<String> STOCK_MASTER_TYPES = Set.of("CS", "OS", "ADRC", "ETF");
    private static final Set<String> OPERATING_COMPANY_TYPES_UPPER = OPERATING_STOCK_TYPES.stream()
            .map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> VALUATION_STOCK_TYPES_UPPER = VALUATION_STOCK_TYPES.stream()
            .map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> ETF_TYPES_UPPER = ETF_STOCK_TYPES.stream()
            .map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

    public boolean supportsStockMaster(String massiveType) {
        return contains(STOCK_MASTER_TYPES, massiveType);
    }

    public boolean supportsSimplePerShareValuation(String finnhubType) {
        return isOperatingCompanyType(finnhubType);
    }

    public static boolean isOperatingCompanyType(String finnhubType) {
        return contains(OPERATING_COMPANY_TYPES_UPPER, finnhubType);
    }

    public static boolean supportsValuation(String finnhubType) {
        return contains(VALUATION_STOCK_TYPES_UPPER, finnhubType);
    }

    public static boolean isAdrType(String finnhubType) {
        return finnhubType != null && "ADR".equalsIgnoreCase(finnhubType.trim());
    }

    public static boolean isEtfType(String finnhubType) {
        return contains(ETF_TYPES_UPPER, finnhubType);
    }

    public static boolean isReitType(String finnhubType) {
        return finnhubType != null && "REIT".equalsIgnoreCase(finnhubType.trim());
    }

    public static boolean isAnalyzableType(String finnhubType) {
        return supportsValuation(finnhubType) || isEtfType(finnhubType);
    }

    private static boolean contains(Set<String> types, String value) {
        if (value == null || value.isBlank()) return false;
        return types.contains(value.trim().toUpperCase(Locale.ROOT));
    }
}
