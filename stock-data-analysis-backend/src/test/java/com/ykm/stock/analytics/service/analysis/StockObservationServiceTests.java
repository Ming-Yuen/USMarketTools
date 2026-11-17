package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockObservationServiceTests {
    private static final String FIGI = "FIGI-AAPL";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);
    private static final Instant CUTOFF = Instant.parse("2026-11-11T21:00:00Z");
    private final StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
    private final StockObservationService service = new StockObservationService(forecasts);

    @Test
    void nativePartialMetricsAreUsableWithoutDemandingAllThree() {
        ValuationResultDto result = ValuationResultDto.fromFutu("F", DAY, CUTOFF, null, null, new BigDecimal("2"), null);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.reasonCode()).isNull();
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
    }

    @Test
    void latestSnapshotMappingPreservesUnverifiedAndInsufficientStatuses() {
        StockValuationLatest latest = new StockValuationLatest();
        latest.setFigi(FIGI);
        latest.setValuationDate(DAY);
        latest.setAnalysisAsOf(CUTOFF);
        latest.setCalculatedAt(CUTOFF.plusSeconds(60));
        latest.setStatus("AVAILABLE");
        latest.setPeReason(ValuationReason.EPS_SPLIT_BASIS_UNCONFIRMED);
        latest.setPeStatus("UNVERIFIED");
        latest.setPb(new BigDecimal("8.5"));
        latest.setPbStatus("AVAILABLE");
        latest.setPsReason(ValuationReason.VALUATION_DATE_SHARES_MISSING);
        latest.setPsStatus("INSUFFICIENT_DATA");
        latest.setPfcfReason(ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY);
        latest.setPfcfStatus("NOT_APPLICABLE");
        latest.setEvEbitdaReason(ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY);
        latest.setEvEbitdaStatus("NOT_APPLICABLE");

        ValuationResultDto dto = ValuationResultDto.fromLatest(latest);

        assertThat(dto.metrics().pe().status()).isEqualTo("UNVERIFIED");
        assertThat(dto.metrics().pe().reasonCode()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED");
        assertThat(dto.metrics().pb().value()).isEqualByComparingTo("8.5");
        assertThat(dto.metrics().ps().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(dto.metrics().pfcf().status()).isEqualTo("NOT_APPLICABLE");
    }
}
