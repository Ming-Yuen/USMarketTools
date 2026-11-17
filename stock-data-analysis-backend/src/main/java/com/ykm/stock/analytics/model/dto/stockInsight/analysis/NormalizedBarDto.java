package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record NormalizedBarDto(LocalDate sessionDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                               MetricResult<BigDecimal> volume, Instant sessionOpenAt, Instant sessionCloseAt,
                               Instant availableAt, Instant observedAt, String versionRef, String basisId) {}
