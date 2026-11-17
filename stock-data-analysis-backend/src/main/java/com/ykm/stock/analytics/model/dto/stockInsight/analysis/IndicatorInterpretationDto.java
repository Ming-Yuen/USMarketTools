package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.List;
import java.util.Map;

/** 同一批行情計算產生的確定性判讀；前端以 code／params 本地化，不代表預測或交易指令。 */
public record IndicatorInterpretationDto(String code, Map<String, Object> params, MetricStatus status,
                                         List<AnalysisReasonDto> reasons) {
    public IndicatorInterpretationDto {
        params = params == null ? Map.of() : Map.copyOf(params);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
