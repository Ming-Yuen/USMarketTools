package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import com.ykm.stock.analytics.repository.jpa.StockValuationDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockObservationServiceTests {
    @Test
    void nativePartialMetricsAreUsableWithoutDemandingAllThree() {
        ValuationResultDto result = ValuationResultDto.fromFutu("F", LocalDate.of(2026, 11, 11),
                Instant.parse("2026-11-11T21:00:00Z"), null, null, new BigDecimal("2"), null);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.reasonCode()).isNull();
    }

    private static final String FIGI = "FIGI-AAPL";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final Instant CUTOFF = Instant.parse("2026-11-11T21:00:00Z");
    private final StockValuationDailyRepository valuations = mock(StockValuationDailyRepository.class);
    private final StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
    private final StockObservationService service = new StockObservationService(valuations, forecasts);

    @Test
    void futureCalculationTimeDoesNotHideQualifiedHistoricalResultOrRelabelItAsFutu() {
        StockValuationDaily row = row(DAY);
        row.setCalculatedAt(CUTOFF.plusSeconds(86400));
        when(valuations.findByFigiAndValuationDateAndValuationCutoffLessThanEqual(FIGI, DAY, CUTOFF))
                .thenReturn(Optional.of(row));
        ValuationResultDto result = service.valuation(FIGI, DAY, CUTOFF, "TECH");
        assertThat(result.source()).isEqualTo("FINANCIAL_REPORT_AS_OBSERVED");
        assertThat(result.analysisAsOf()).isEqualTo(CUTOFF);
        assertThat(result.valuationDate()).isEqualTo(DAY);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.reasonCode()).isNull();
        assertThat(result.metrics().pe().value()).isEqualByComparingTo("20");
        assertThat(result.metrics().pb().value()).isNull();
        assertThat(result.metrics().pfcf().reasonCode()).isEqualTo("VALUATION_DATE_SHARES_MISSING");
    }

    @Test
    void nativeForecastsRemainSeparateWithoutInventingReportPeriodDates() {
        StockEarningsForecast forecast = new StockEarningsForecast();
        forecast.setFiscalPeriod("2026Q4");
        forecast.setPeriodType("QUARTERLY");
        forecast.setCurrency("USD");
        forecast.setEps(new BigDecimal("-0.23"));
        when(forecasts.findLatestForStock(FIGI, CUTOFF)).thenReturn(List.of(forecast));
        FutuObservationsDto result = service.read(FIGI, CUTOFF);
        assertThat(result.valuation()).isNull();
        assertThat(result.earnings()).singleElement().satisfies(item -> {
            assertThat(item.eps()).isEqualByComparingTo("-0.23");
            assertThat(item.periodStart()).isNull();
            assertThat(item.periodEnd()).isNull();
        });
        verifyNoInteractions(valuations);
    }

    @Test
    void monthlyHistoryKeepsLatestAvailableDayAndSkipsEntirelyMissingResults() {
        StockValuationDaily earlier = row(DAY.minusDays(1));
        StockValuationDaily latest = row(DAY);
        StockValuationDaily empty = row(DAY.plusDays(1));
        empty.setPe(null);
        when(valuations.findByFigiAndValuationDateBetweenAndValuationCutoffLessThanEqualOrderByValuationDateAsc(FIGI, DAY.minusMonths(1), DAY.plusDays(1), CUTOFF))
                .thenReturn(List.of(earlier, latest, empty));
        assertThat(service.monthlyValuations(FIGI, DAY.minusMonths(1), DAY.plusDays(1), CUTOFF)).singleElement()
                .satisfies(value -> assertThat(value.valuationDate()).isEqualTo(DAY));
    }

    @Test
    void unverifiedFormulaRemainsDistinctFromMissingSharesInResponse() {
        StockValuationDaily row = row(DAY);
        row.setPe(null);
        row.setPeReason("EPS_SPLIT_BASIS_UNCONFIRMED");
        ValuationResultDto result = ValuationResultDto.fromCalculated(row, null);
        assertThat(result.metrics().pe().status()).isEqualTo("UNVERIFIED");
        assertThat(result.metrics().pe().reasonCode()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED");
        assertThat(result.metrics().pb().status()).isEqualTo("INSUFFICIENT_DATA");
    }

    private StockValuationDaily row(LocalDate day) {
        StockValuationDaily row = new StockValuationDaily();
        row.setFigi(FIGI);
        row.setValuationDate(day);
        row.setValuationCutoff(CUTOFF);
        row.setPe(new BigDecimal("20"));
        row.setPbReason("VALUATION_DATE_SHARES_MISSING");
        row.setPsReason("VALUATION_DATE_SHARES_MISSING");
        row.setPfcfReason("VALUATION_DATE_SHARES_MISSING");
        row.setEvEbitdaReason("ENTERPRISE_VALUE_INPUTS_UNRESOLVED");
        return row;
    }
}
