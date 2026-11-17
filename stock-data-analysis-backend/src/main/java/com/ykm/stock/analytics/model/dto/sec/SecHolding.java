package com.ykm.stock.analytics.model.dto.sec;

import java.math.BigDecimal;

public record SecHolding(String seriesId, String holdingId, String ticker, String name, String cusip, BigDecimal shares, BigDecimal marketValueUsd, BigDecimal weight, String currency, Integer year, Integer quarter) {
}