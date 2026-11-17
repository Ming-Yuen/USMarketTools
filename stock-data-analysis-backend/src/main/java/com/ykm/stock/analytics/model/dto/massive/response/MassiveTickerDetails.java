package com.ykm.stock.analytics.model.dto.massive.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
public class MassiveTickerDetails {
    private Boolean active;
    private String cik;

    @JsonProperty("composite_figi")
    private String compositeFigi;

    @JsonProperty("currency_name")
    private String currencyName;

    @JsonProperty("last_updated_utc")
    private Instant lastUpdatedUtc;

    private String locale;
    private String market;
    private String name;

    @JsonProperty("primary_exchange")
    private String primaryExchange;

    @JsonProperty("share_class_figi")
    private String shareClassFigi;

    private String ticker;
    private String type;

    @JsonProperty("sic_code")
    private String sicCode;

    @JsonProperty("sic_description")
    private String sicDescription;

    @JsonProperty("list_date")
    private LocalDate listDate;

    private String description;

    @JsonProperty("market_cap")
    private BigDecimal marketCap;

    @JsonProperty("weighted_shares_outstanding")
    private BigDecimal weightedSharesOutstanding;

    @JsonProperty("share_class_shares_outstanding")
    private BigDecimal shareClassSharesOutstanding;
}
