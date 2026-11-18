package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 第三方觀測值與市場預期前瞻指標直接呈現日期與來源，與已公布財報分軌，不冒充公司實績或合理股價。 */
public record FutuObservationsDto(Valuation valuation, List<Earnings> earnings, ForwardMetrics forward) {
    public FutuObservationsDto {
        earnings = earnings == null ? List.of() : List.copyOf(earnings);
    }

    public FutuObservationsDto(Valuation valuation, List<Earnings> earnings) {
        this(valuation, earnings, null);
    }

    public record Valuation(LocalDate observationDate, Instant observedAt, BigDecimal pe, BigDecimal pb,
                            BigDecimal ps, BigDecimal forwardPe, BigDecimal forwardPs) {}

    public record Earnings(String fiscalPeriod, String periodType, LocalDate periodStart, LocalDate periodEnd,
                           String currency, BigDecimal eps, BigDecimal revenue, LocalDate observationDate,
                           Instant observedAt, String source) {}

    /** 基於 stock_earnings_forecast 市場共識預期衍生之前瞻估值倍數與 30D／90D 預期修正（純唯讀觀察，與原生快照及 TTM 實績隔離）。 */
    public record ForwardMetrics(
            LocalDate valuationDate,
            String targetFiscalPeriod,
            LocalDate forecastObservationDate,
            String forecastCurrency,
            MetricResult<BigDecimal> forwardPe,
            MetricResult<BigDecimal> forwardPs,
            MetricResult<BigDecimal> forwardPeg,
            String forwardPegBasis,
            MetricResult<BigDecimal> impliedEpsGrowth,
            String revisionFiscalPeriod,
            String revisionPeriodType,
            String revisionCurrency,
            LocalDate revisionObservationDate,
            LocalDate revisionBaseDate30d,
            LocalDate revisionBaseDate90d,
            MetricResult<BigDecimal> epsRevision30d,
            MetricResult<BigDecimal> epsRevision90d,
            MetricResult<BigDecimal> revenueRevision30d,
            MetricResult<BigDecimal> revenueRevision90d
    ) {
        public ForwardMetrics(
                LocalDate valuationDate,
                String targetFiscalPeriod,
                LocalDate forecastObservationDate,
                String forecastCurrency,
                MetricResult<BigDecimal> forwardPe,
                MetricResult<BigDecimal> forwardPs,
                MetricResult<BigDecimal> forwardPeg,
                String forwardPegBasis,
                MetricResult<BigDecimal> impliedEpsGrowth,
                String revisionFiscalPeriod,
                String revisionPeriodType,
                LocalDate revisionObservationDate,
                LocalDate revisionBaseDate30d,
                LocalDate revisionBaseDate90d,
                MetricResult<BigDecimal> epsRevision30d,
                MetricResult<BigDecimal> epsRevision90d,
                MetricResult<BigDecimal> revenueRevision30d,
                MetricResult<BigDecimal> revenueRevision90d) {
            this(valuationDate, targetFiscalPeriod, forecastObservationDate, forecastCurrency,
                    forwardPe, forwardPs, forwardPeg, forwardPegBasis, impliedEpsGrowth,
                    revisionFiscalPeriod, revisionPeriodType, forecastCurrency, revisionObservationDate,
                    revisionBaseDate30d, revisionBaseDate90d, epsRevision30d, epsRevision90d, revenueRevision30d, revenueRevision90d);
        }
    }
}
