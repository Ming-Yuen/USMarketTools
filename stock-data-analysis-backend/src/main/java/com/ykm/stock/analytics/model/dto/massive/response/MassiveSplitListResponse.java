package com.ykm.stock.analytics.model.dto.massive.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class MassiveSplitListResponse {
    private Integer count;

    @JsonProperty("next_url")
    private String nextUrl;

    @JsonProperty("request_id")
    private String requestId;

    private List<MassiveSplit> results;
    private String status;
}
