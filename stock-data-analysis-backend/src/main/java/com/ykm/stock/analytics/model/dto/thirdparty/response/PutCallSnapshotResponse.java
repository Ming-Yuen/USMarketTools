package com.ykm.stock.analytics.model.dto.thirdparty.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PutCallSnapshotResponse {

    @JsonProperty("as_of")
    private String asOf;

    private String source;

    @JsonProperty("source_url")
    private String sourceUrl;

    @JsonProperty("put_call")
    private PutCall putCall;

    @Data
    public static class PutCall {

        @JsonProperty("total_ratio")
        private Double totalRatio;

        @JsonProperty("equity_ratio")
        private Double equityRatio;

        @JsonProperty("index_ratio")
        private Double indexRatio;

        @JsonProperty("etp_ratio")
        private Double etpRatio;

        @JsonProperty("vix_ratio")
        private Double vixRatio;

        @JsonProperty("spx_ratio")
        private Double spxRatio;
    }
}