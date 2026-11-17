package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.entity.StockQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteNormalizerTests {

    // 例子：0.0001 與 99999999999.9999 的報價都可入庫。
    @Test
    void validForStorageAcceptsNumericBoundaries() {
        StockQuote min = quote("0.0001", "0.0001", "0.0001", "0.0001");
        StockQuote max = quote("99999999999.9999", "99999999999.9999", "99999999999.9999", "99999999999.9999");

        assertThat(QuoteNormalizer.validForStorage(min)).isTrue();
        assertThat(QuoteNormalizer.validForStorage(max)).isTrue();
        assertThat(QuoteNormalizer.validForStorage(quote("0.000001", "0.000001", "0.000001", "0.000001"))).isTrue();
        StockQuote micro = quote("0.00000001", "0.00000001", "0.00000001", "0.00000001");
        micro.setVolume(BigDecimal.ZERO);
        assertThat(QuoteNormalizer.validForStorage(micro)).isTrue();
    }

    // 例子：價格 0.000000001 雖為正數，但低於 NUMERIC(19,8) 可保存的 0.00000001，故拒絕。
    @Test
    void validForStorageRejectsPriceBelowPersistedScale() {
        StockQuote quote = quote("0.000000001", "0.000000001", "0.000000001", "0.000000001");

        assertThat(QuoteNormalizer.valid(quote)).isTrue();
        assertThat(QuoteNormalizer.validForStorage(quote)).isFalse();
    }

    // 例子：價格 100000000000.0000 超出數值容量，不可入庫。
    @Test
    void validForStorageRejectsPriceBeyondNumericCapacity() {
        StockQuote quote = quote("100000000000.0000", "100000000000.0000", "100000000000.0000", "100000000000.0000");

        assertThat(QuoteNormalizer.valid(quote)).isTrue();
        assertThat(QuoteNormalizer.validForStorage(quote)).isFalse();
    }

    @Test
    void rejectsInvalidOhlcRelationships() {
        assertThat(QuoteNormalizer.validForStorage(quote("0.000002", "0.000001", "0.000001", "0.000002"))).isFalse();
    }

    @Test
    void fractionalVolumeMustFitStorageWithoutRounding() {
        StockQuote quote = quote("10", "12", "9", "11");
        for (String volume : new String[]{"0", "0.00000001", "100.123456", "99999999999999999999.99999999"}) {
            quote.setVolume(new BigDecimal(volume));
            assertThat(QuoteNormalizer.validForStorage(quote)).as(volume).isTrue();
        }
        for (String volume : new String[]{"-0.1", "0.000000001", "100000000000000000000"}) {
            quote.setVolume(new BigDecimal(volume));
            assertThat(QuoteNormalizer.validForStorage(quote)).as(volume).isFalse();
        }
    }

    private StockQuote quote(String open, String high, String low, String close) {
        StockQuote quote = new StockQuote();
        quote.setOpenPrice(new BigDecimal(open));
        quote.setHighPrice(new BigDecimal(high));
        quote.setLowPrice(new BigDecimal(low));
        quote.setClosePrice(new BigDecimal(close));
        quote.setVolume(new BigDecimal("100"));
        return quote;
    }
}
