package com.ykm.stock.analytics.model.dto.massive.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MassiveSplit {
    private String id;
    private String ticker;

    @JsonProperty("execution_date")
    private LocalDate executionDate;

    @JsonProperty("split_from")
    private BigDecimal splitFrom;

    @JsonProperty("split_to")
    private BigDecimal splitTo;

    @JsonProperty("adjustment_type")
    private String adjustmentType;

    @JsonProperty("historical_adjustment_factor")
    private BigDecimal historicalAdjustmentFactor;
}
