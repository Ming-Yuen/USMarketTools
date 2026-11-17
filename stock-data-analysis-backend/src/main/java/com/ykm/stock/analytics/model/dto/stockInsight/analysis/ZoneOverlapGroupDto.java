package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.math.BigDecimal; import java.util.List;
public record ZoneOverlapGroupDto(String id, BigDecimal lower, BigDecimal upper, List<String> zoneIds) { public ZoneOverlapGroupDto { zoneIds=List.copyOf(zoneIds); } }
