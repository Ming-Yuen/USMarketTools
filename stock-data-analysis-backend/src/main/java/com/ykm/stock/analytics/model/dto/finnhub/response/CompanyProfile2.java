package com.ykm.stock.analytics.model.dto.finnhub.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompanyProfile2 {
    private String ticker;
    private String finnhubIndustry;
}
