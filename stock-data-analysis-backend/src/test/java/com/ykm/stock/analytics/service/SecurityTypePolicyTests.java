package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityTypePolicyTests {
    private final SecurityTypePolicy policy = new SecurityTypePolicy();

    /** Scenario：主檔只收普通股、普通 ADR 和 ETF，拒絕優先股、債券及其他類型。 */
    @Test
    void stockMasterAcceptsOnlySelectedSecurityTypes() {
        assertThat(policy.supportsStockMaster("CS")).isTrue();
        assertThat(policy.supportsStockMaster("os")).isTrue();
        assertThat(policy.supportsStockMaster(" ADRC ")).isTrue();
        assertThat(policy.supportsStockMaster("ETF")).isTrue();
        for (String type : new String[]{"PFD", "ADRP", "BOND", "ETN", "WARRANT", "GDR", "NYRS", "ETS", "", "OTHER"}) {
            assertThat(policy.supportsStockMaster(type)).as(type).isFalse();
        }
        assertThat(policy.supportsStockMaster(null)).isFalse();
    }

    /** Scenario：Finnhub 細分基金、信託與權證後，只讓原有營運公司類型參與普通估值。 */
    @Test
    void simplePerShareValuationRequiresOperatingCompany() {
        for (String type : new String[]{"Common Stock", "REIT", "MLP", "NY Reg Shrs", "Ltd Part", " common stock "})
            assertThat(policy.supportsSimplePerShareValuation(type)).as(type).isTrue();
        for (String type : new String[]{"Closed-End Fund", "Royalty Trst", "ADR", "ETP", "Unit", "Equity WRT", "CS", "ETF"})
            assertThat(policy.supportsSimplePerShareValuation(type)).as(type).isFalse();
        assertThat(policy.supportsSimplePerShareValuation(null)).isFalse();
    }
}
