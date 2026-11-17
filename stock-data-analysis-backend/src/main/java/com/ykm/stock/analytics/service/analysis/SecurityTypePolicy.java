package com.ykm.stock.analytics.service.analysis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Massive 決定主檔範圍；Finnhub 詳細類型決定各排程與估值的證券適用範圍。 */
@Component
public class SecurityTypePolicy {
    public static final List<String> OPERATING_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part");
    public static final List<String> SEC_FILING_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR");
    public static final List<String> FINANCIAL_REPORT_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR", "Closed-End Fund", "Royalty Trst");
    public static final List<String> MARKET_DATA_STOCK_TYPES = List.of(
            "Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", "ADR", "ETP", "Closed-End Fund", "Royalty Trst", "Unit");
    public static final List<String> BENCHMARK_ETF_SYMBOLS = List.of(
            "SPY", "QQQ", "SOXX", "VOO", "URA", "IWM", "DIA");

    private static final Set<String> STOCK_MASTER_TYPES = Set.of("CS", "OS", "ADRC", "ETF");
    private static final Set<String> OPERATING_COMPANY_TYPES_UPPER = OPERATING_STOCK_TYPES.stream()
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

    private static boolean contains(Set<String> types, String value) {
        if (value == null || value.isBlank()) return false;
        return types.contains(value.trim().toUpperCase(Locale.ROOT));
    }
}
