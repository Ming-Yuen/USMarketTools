package com.ykm.stock.analytics.model.dto.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

/** Alpaca SIP 日線原始欄位；價格與成交量皆採 raw 基準。 */
@Data
public class AlpacaBar {
    @JsonProperty("o") private BigDecimal open;
    @JsonProperty("h") private BigDecimal high;
    @JsonProperty("l") private BigDecimal low;
    @JsonProperty("c") private BigDecimal close;
    @JsonProperty("v") private BigDecimal volume;
    @JsonProperty("t") private Instant timestamp;
}
