package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record StructureEventDto(String code, PivotKind levelKind, LocalDate sessionDate, Instant availableAt,
                                BigDecimal pivotPrice, BigDecimal threshold, String pivotId, String dependenceKey) {}
