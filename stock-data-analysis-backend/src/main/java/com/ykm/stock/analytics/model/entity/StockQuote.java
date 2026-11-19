package com.ykm.stock.analytics.model.entity;

import com.ykm.stock.analytics.model.entity.key.StockQuoteId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Comment;

/** FIGI + 交易日的未復權 OHLCV；市值由股價與財報股數計算，不重複保存。 */
@Data
@Entity
@Table(name = "stock_quote", indexes = {
        @Index(name = "idx_stock_quote_figi_date", columnList = "figi,quote_date")
})
@IdClass(StockQuoteId.class)
public class StockQuote {
    @Id
    @Column(nullable = false, length = 32, updatable = false)
    @Comment("行情所屬的證券 FIGI，與交易日期組成主鍵。")
    private String figi;

    @Id
    @Column(name = "quote_date", nullable = false, updatable = false)
    @Comment("美國交易所實際交易日期；非本地下載日期。")
    private LocalDate quoteDate;

    @Column(name = "open_price", nullable = false, precision = 19, scale = 8)
    @Comment("該交易日的未復權開盤價，單位為股票交易幣別。")
    private BigDecimal openPrice;

    @Column(name = "high_price", nullable = false, precision = 19, scale = 8)
    @Comment("該交易日的未復權最高成交價。")
    private BigDecimal highPrice;

    @Column(name = "low_price", nullable = false, precision = 19, scale = 8)
    @Comment("該交易日的未復權最低成交價。")
    private BigDecimal lowPrice;

    @Column(name = "close_price", nullable = false, precision = 19, scale = 8)
    @Comment("該交易日的未復權收盤價，用於當日估值。")
    private BigDecimal closePrice;

    @Column(name = "volume", precision = 28, scale = 8)
    @Comment("該交易日成交股數；未復權時與 OHLC 價格基準一致。")
    private BigDecimal volume;

    @Column(name = "observed_at")
    @Comment("系統取得或更新此行情的時間，不等於交易日。")
    private Instant observedAt;

    @Column(name = "data_source", length = 50)
    @Comment("行情資料供應商，例如 Alpaca。")
    private String dataSource;
}
