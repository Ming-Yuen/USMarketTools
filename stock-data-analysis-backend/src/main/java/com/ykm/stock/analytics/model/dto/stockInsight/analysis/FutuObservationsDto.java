package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 第三方觀測值直接呈現日期與來源，不冒充公司已公布財報或合理股價。 */
public record FutuObservationsDto(Valuation valuation, List<Earnings> earnings) {
    public record Valuation(LocalDate observationDate, Instant observedAt, BigDecimal pe, BigDecimal pb,
                            BigDecimal ps, BigDecimal forwardPe, BigDecimal forwardPs) {}
    public record Earnings(String fiscalPeriod, String periodType, LocalDate periodStart, LocalDate periodEnd,
                           String currency, BigDecimal eps, BigDecimal revenue, LocalDate observationDate,
                           Instant observedAt, String source) {}
}
