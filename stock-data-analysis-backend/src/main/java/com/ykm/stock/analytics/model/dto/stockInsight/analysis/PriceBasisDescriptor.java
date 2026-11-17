package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.time.LocalDate;

public record PriceBasisDescriptor(String basisId, String source, PriceAdjustment priceAdjustment, LocalDate referenceDate,
                                   String splitVersion, ShareUnit shareUnit, SessionScope sessionScope, String currency,
                                   boolean verified, String evidenceRef) {}
