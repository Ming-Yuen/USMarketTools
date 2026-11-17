package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ValuationResultDto(
        String figi,
        LocalDate valuationDate,
        Instant analysisAsOf,
        String source,
        String industryCode,
        String modelCode,
        String primaryMetric,
        String status,
        String reasonCode,
        Metrics metrics,
        BigDecimal price,
        String currency,
        Instant calculatedAt,
        Instant financialObservedAt,
        Instant sharesObservedAt,
        String marketCapSource,
        LocalDate secFinancialAvailableFromDate,
        ValuationReferenceDto historicalReference,
        ValuationReferenceDto peerReference,
        ValuationReferenceDto industryAssumption
) {
    public ValuationResultDto(String figi, LocalDate valuationDate, Instant analysisAsOf, String source, String industryCode,
                              String status, String reasonCode, Metrics metrics, BigDecimal price, String currency, Instant calculatedAt,
                              Instant financialObservedAt, Instant sharesObservedAt, String marketCapSource, LocalDate secFinancialAvailableFromDate) {
        this(figi, valuationDate, analysisAsOf, source, industryCode, null, null, status, reasonCode, metrics, price, currency,
                calculatedAt, financialObservedAt, sharesObservedAt, marketCapSource, secFinancialAvailableFromDate, null, null, null);
    }

    public ValuationResultDto withReferences(String resolvedModelCode, String resolvedPrimaryMetric,
                                             ValuationReferenceDto historical, ValuationReferenceDto peer, ValuationReferenceDto assumption) {
        return new ValuationResultDto(figi, valuationDate, analysisAsOf, source, industryCode, resolvedModelCode, resolvedPrimaryMetric,
                status, reasonCode, metrics, price, currency, calculatedAt, financialObservedAt, sharesObservedAt,
                marketCapSource, secFinancialAvailableFromDate, historical, peer, assumption);
    }

    public record Value(BigDecimal value, String status, String reasonCode, String financialSource, LocalDate periodStart, LocalDate periodEnd) {
        public Value(BigDecimal value, String status, String reasonCode) {
            this(value, status, reasonCode, "FUTU", null, null);
        }
    }

    public record Metrics(Value pe, Value pb, Value ps, Value pfcf, Value evEbitda) {
        public Metrics(Value pe, Value pb, Value ps) {
            this(pe, pb, ps, value(null, "NATIVE_METRIC_NOT_PROVIDED"), value(null, "NATIVE_METRIC_NOT_PROVIDED"));
        }

        public Value byMetricCode(String metricCode) {
            if (metricCode == null) return null;
            return switch (metricCode.toUpperCase()) {
                case "PE" -> pe;
                case "PB" -> pb;
                case "PS" -> ps;
                case "PFCF" -> pfcf;
                case "EV_EBITDA" -> evEbitda;
                default -> null;
            };
        }
    }

    /** 富途歷史 API 的原生三倍數，來源仍標示 FUTU；不可與本地計算值混拼。 */
    public static ValuationResultDto fromFutu(String figi, LocalDate date, Instant observedAt, String industryCode,
                                              BigDecimal pe, BigDecimal pb, BigDecimal ps) {
        boolean empty = pe == null && pb == null && ps == null;
        return new ValuationResultDto(figi, date, observedAt, "FUTU", industryCode,
                empty ? "INSUFFICIENT_DATA" : "AVAILABLE", empty ? "FUTU_VALUATION_FIELDS_MISSING" : null,
                new Metrics(value(pe, "FUTU_VALUATION_FIELD_MISSING"), value(pb, "FUTU_VALUATION_FIELD_MISSING"), value(ps, "FUTU_VALUATION_FIELD_MISSING")),
                null, null, null, null, null, null, null);
    }

    public static ValuationResultDto fromLatest(StockValuationLatest row) {
        return new ValuationResultDto(
                row.getFigi(),
                row.getValuationDate(),
                row.getAnalysisAsOf(),
                row.getFinancialSource() == null ? "FINANCIAL_REPORT_AS_OBSERVED" : row.getFinancialSource(),
                row.getIndustryCode(),
                row.getModelCode(),
                row.getPrimaryMetric(),
                row.getStatus(),
                row.getReasonCode() == null ? null : row.getReasonCode().name(),
                new Metrics(
                        financialValue(row.getPe(), row.getPeStatus(), row.getPeReason(), row.getPeFinancialSource(), row.getPePeriodStart(), row.getPePeriodEnd()),
                        financialValue(row.getPb(), row.getPbStatus(), row.getPbReason(), row.getPbFinancialSource(), null, row.getPbPeriodEnd()),
                        financialValue(row.getPs(), row.getPsStatus(), row.getPsReason(), row.getPsFinancialSource(), row.getPsPeriodStart(), row.getPsPeriodEnd()),
                        financialValue(row.getPfcf(), row.getPfcfStatus(), row.getPfcfReason(), row.getPfcfFinancialSource(), row.getPfcfPeriodStart(), row.getPfcfPeriodEnd()),
                        financialValue(row.getEvEbitda(), row.getEvEbitdaStatus(), row.getEvEbitdaReason(), row.getEvEbitdaFinancialSource(), row.getEvEbitdaPeriodStart(), row.getEvEbitdaPeriodEnd())
                ),
                row.getClosePrice(),
                row.getCurrency(),
                row.getCalculatedAt(),
                row.getFinancialObservedAt(),
                row.getSharesObservedAt(),
                row.getMarketCapSource(),
                row.getSecFinancialAvailableFromDate(),
                null,
                null,
                null
        );
    }

    public static Value financialValue(BigDecimal amount, String reason, String source, LocalDate start, LocalDate end) {
        Value resolved = value(amount, reason);
        return new Value(resolved.value(), resolved.status(), resolved.reasonCode(), source == null ? "FUTU" : source, start, end);
    }

    private static Value financialValue(BigDecimal amount, String status, ValuationReason reason, String source, LocalDate start, LocalDate end) {
        String reasonCode = reason == null ? null : reason.name();
        if (amount != null) {
            return new Value(amount, "AVAILABLE", null, source == null ? "FUTU" : source, start, end);
        }
        String resolvedStatus = status != null ? status : ValuationReason.statusNameFor(reasonCode);
        return new Value(null, resolvedStatus, reasonCode, source == null ? "FUTU" : source, start, end);
    }

    public static Value value(BigDecimal amount, String reason) {
        if (amount != null) return new Value(amount, "AVAILABLE", null);
        String normalized = ValuationReason.normalizeAlias(reason);
        return new Value(null, ValuationReason.statusNameFor(normalized), normalized);
    }
}
