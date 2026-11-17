package com.ykm.stock.analytics.model.dto.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

/** Alpaca 多股票日線分頁回應；next_page_token 為空表示已取完。 */
@Data
public class AlpacaBarsResponse {
    private Map<String, List<AlpacaBar>> bars;
    @JsonProperty("next_page_token") private String nextPageToken;
}
