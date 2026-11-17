package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;

public record MomentumVolumeDto(
        MetricResult<BigDecimal> rsi14,
        MetricResult<BigDecimal> momentumSignal,
        MetricResult<BigDecimal> macd,
        MetricResult<BigDecimal> macdSignal,
        MetricResult<BigDecimal> volumeVs20Day,
        MetricResult<BigDecimal> volumeSignal,
        MetricResult<BigDecimal> drawdownFrom252High,
        MetricResult<BigDecimal> return20,
        MetricResult<BigDecimal> return60) {}
