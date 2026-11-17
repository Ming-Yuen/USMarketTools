package com.ykm.stock.analytics.service.analysis;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Massive 決定主檔範圍；Finnhub 詳細類型決定普通營運公司的估值適用性。 */
@Component
public class SecurityTypePolicy {
    private static final Set<String> STOCK_MASTER_TYPES = Set.of("CS", "OS", "ADRC", "ETF");
    private static final Set<String> OPERATING_COMPANY_TYPES = Set.of("COMMON STOCK", "REIT", "MLP", "NY REG SHRS", "LTD PART");

    public boolean supportsStockMaster(String massiveType) {
        return contains(STOCK_MASTER_TYPES, massiveType);
    }

    public boolean supportsSimplePerShareValuation(String finnhubType) {
        return isOperatingCompanyType(finnhubType);
    }

    public static boolean isOperatingCompanyType(String finnhubType) {
        return contains(OPERATING_COMPANY_TYPES, finnhubType);
    }

    private static boolean contains(Set<String> types, String value) {
        if (value == null || value.isBlank()) return false;
        return types.contains(value.trim().toUpperCase(Locale.ROOT));
    }
}
