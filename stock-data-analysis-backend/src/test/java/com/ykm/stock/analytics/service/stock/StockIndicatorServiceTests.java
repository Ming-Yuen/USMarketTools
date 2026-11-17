package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class StockIndicatorServiceTests {

    private final StockIndicatorService service = new StockIndicatorService(new SecurityTypePolicy());

    // 例子：ADRC 價格 100、普通股 EPS 5 不得用來算本益比。
    @Test
    void adrProviderCodeDoesNotUseOrdinaryShareEpsForPe() {
        assertThat(service.getStockPe("ADR", new BigDecimal("100"), new BigDecimal("5"))).isNull();
    }

    // 例子：CS 價格 139.27、EPS 1.89 的本益比為 73.6878。
    @Test
    void calculatesPeFromBasisAlignedPriceAndEps() {
        assertThat(service.getStockPe("Common Stock", new BigDecimal("139.27"), new BigDecimal("1.89")))
                .isEqualByComparingTo("73.6878");
    }
}
