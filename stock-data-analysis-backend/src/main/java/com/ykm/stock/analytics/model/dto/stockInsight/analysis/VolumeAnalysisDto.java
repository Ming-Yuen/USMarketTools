package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record VolumeAnalysisDto(
        MetricResult<BigDecimal> rvol20,
        MetricResult<BigDecimal> obv,
        MetricResult<BigDecimal> netVolumeDays20,
        MetricResult<BigDecimal> primarySignal,
        MetricResult<VolumeProfileDto> profile252,
        MetricResult<BigDecimal> anchoredVwap,
        List<AnalysisReasonDto> reasons,
        List<String> evidenceRefs,
        LocalDate earningsEventDate,
        LocalDate earningsAnchorDate,
        MetricResult<BigDecimal> earningsAnchoredVwap,
        List<AnchoredVwapPointDto> earningsAnchoredVwapSeries
) {
    public VolumeAnalysisDto {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        earningsAnchoredVwapSeries = earningsAnchoredVwapSeries == null ? List.of() : List.copyOf(earningsAnchoredVwapSeries);
    }

    public VolumeAnalysisDto(
            MetricResult<BigDecimal> rvol20,
            MetricResult<BigDecimal> obv,
            MetricResult<BigDecimal> netVolumeDays20,
            MetricResult<BigDecimal> primarySignal,
            MetricResult<VolumeProfileDto> profile252,
            MetricResult<BigDecimal> anchoredVwap,
            List<AnalysisReasonDto> reasons,
            List<String> evidenceRefs) {
        this(rvol20, obv, netVolumeDays20, primarySignal, profile252, anchoredVwap, reasons, evidenceRefs,
                null, null,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                        AnalysisReasonDto.of("EARNINGS_EVENT_NOT_FOUND", "VOLUME", "缺少最近已發生的財報事件，暫無財報日 Anchored VWAP。")),
                List.of());
    }
}
