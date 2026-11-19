package com.ykm.stock.analytics.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.FredFxClient;
import com.ykm.stock.analytics.client.FredFxClient.RatePoint;
import com.ykm.stock.analytics.client.FredFxClient.SeriesSpec;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.FxRateDaily;
import com.ykm.stock.analytics.repository.jpa.FxRateDailyRepository;
import com.ykm.stock.analytics.service.market.FxRateService.FxRateLookup;
import com.ykm.stock.analytics.service.market.FxRateService.FxResolution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FxRateServiceTests {

    @Test
    void parsesAndNormalizesFredObservationsForDirectAndInvertedPairs() {
        FredFxClient client = new FredFxClient(new ObjectMapper(), "https://api.stlouisfed.org/fred", "test-key");
        SeriesSpec eurSpec = FredFxClient.SUPPORTED_SERIES.get("EUR");
        SeriesSpec twdSpec = FredFxClient.SUPPORTED_SERIES.get("TWD");
        assertThat(eurSpec.usdPerForeign()).isTrue();
        assertThat(twdSpec.usdPerForeign()).isFalse();

        String eurJson = "{\"observations\":[{\"date\":\"2026-11-12\",\"value\":\".\"},{\"date\":\"2026-11-13\",\"value\":\"1.0850\"}]}";
        List<RatePoint> eurPoints = client.parseObservations(eurSpec, eurJson);
        assertThat(eurPoints).hasSize(1);
        assertThat(eurPoints.getFirst().usdPerUnit()).isEqualByComparingTo("1.08500000");

        String twdJson = "{\"observations\":[{\"date\":\"2026-11-11\",\"value\":\"ND\"},{\"date\":\"2026-11-12\",\"value\":\"0\"},{\"date\":\"2026-11-13\",\"value\":\"32.0000\"}]}";
        List<RatePoint> twdPoints = client.parseObservations(twdSpec, twdJson);
        assertThat(twdPoints).hasSize(1);
        assertThat(twdPoints.getFirst().usdPerUnit()).isEqualByComparingTo("0.03125000");
    }

    @Test
    void resolvesFloorEntryWithinFreshnessWindowAndBlocksStaleOrMissingRates() {
        FxRateDailyRepository repo = mock(FxRateDailyRepository.class);
        FredFxClient client = mock(FredFxClient.class);
        DateTimeService dateTime = mock(DateTimeService.class);
        when(dateTime.now()).thenReturn(Instant.parse("2026-11-19T00:00:00Z"));

        FxRateDaily twdRow = new FxRateDaily();
        twdRow.setCurrency("TWD");
        twdRow.setRateDate(LocalDate.of(2026, 11, 13)); // Friday
        twdRow.setUsdPerUnit(new BigDecimal("0.03125000"));
        twdRow.setSource("FRED_H10:DEXTAUS");

        when(repo.findByCurrenciesBetween(anyCollection(), any(), any())).thenReturn(List.of(twdRow));

        FxRateService service = new FxRateService(repo, client, dateTime, 14L);
        FxRateLookup lookup = service.preload(List.of("TWD", "USD"), LocalDate.of(2026, 11, 13), LocalDate.of(2026, 12, 10));

        // Same-currency identity
        FxResolution usdIdentity = lookup.resolve("USD", "USD", LocalDate.of(2026, 11, 16));
        assertThat(usdIdentity.isUsable()).isTrue();
        assertThat(usdIdentity.converted()).isFalse();
        assertThat(usdIdentity.usdPerUnit()).isEqualByComparingTo(BigDecimal.ONE);

        // Weekend/holiday fallback within 14 days (Mon 2026-11-16 uses Fri 2026-11-13)
        FxResolution twdMon = lookup.resolve("TWD", "USD", LocalDate.of(2026, 11, 16));
        assertThat(twdMon.isUsable()).isTrue();
        assertThat(twdMon.converted()).isTrue();
        assertThat(twdMon.usdPerUnit()).isEqualByComparingTo("0.03125000");
        assertThat(twdMon.rateDate()).isEqualTo(LocalDate.of(2026, 11, 13));

        // Stale > 14 days (2026-11-30 is 17 days after 2026-11-13)
        FxResolution twdStale = lookup.resolve("TWD", "USD", LocalDate.of(2026, 11, 30));
        assertThat(twdStale.isUsable()).isFalse();
        assertThat(twdStale.status()).isEqualTo(MetricStatus.STALE.name());
        assertThat(twdStale.reason()).isEqualTo(ValuationReason.FX_RATE_STALE);

        // Missing currency
        FxResolution dkkMissing = lookup.resolve("DKK", "USD", LocalDate.of(2026, 11, 16));
        assertThat(dkkMissing.isUsable()).isFalse();
        assertThat(dkkMissing.reason()).isEqualTo(ValuationReason.FX_RATE_UNAVAILABLE);
    }

    @Test
    void preloadReturnsStaleConsistentlyWhenLatestRatePrecedesFreshnessWindow() {
        FxRateDailyRepository repo = mock(FxRateDailyRepository.class);
        FredFxClient client = mock(FredFxClient.class);
        DateTimeService dateTime = mock(DateTimeService.class);
        when(dateTime.now()).thenReturn(Instant.parse("2026-11-19T00:00:00Z"));

        LocalDate valuationDay = LocalDate.of(2026, 11, 19);
        FxRateDaily staleTwd = new FxRateDaily();
        staleTwd.setCurrency("TWD");
        staleTwd.setRateDate(valuationDay.minusDays(15));
        staleTwd.setUsdPerUnit(new BigDecimal("0.03125000"));
        staleTwd.setSource("FRED_H10:DEXTAUS");

        when(repo.findByCurrenciesBetween(anyCollection(), any(), any())).thenReturn(List.of());
        when(repo.findLatestBeforeByCurrencies(anyCollection(), any())).thenReturn(List.of(staleTwd));
        when(repo.findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("TWD", valuationDay))
                .thenReturn(java.util.Optional.of(staleTwd));

        FxRateService service = new FxRateService(repo, client, dateTime, 14L);
        FxResolution preloaded = service.preload(List.of("TWD"), valuationDay, valuationDay)
                .resolve("TWD", "USD", valuationDay);
        FxResolution single = service.resolveSingle("TWD", "USD", valuationDay);

        assertThat(preloaded.isUsable()).isFalse();
        assertThat(preloaded.reason()).isEqualTo(ValuationReason.FX_RATE_STALE);
        assertThat(single.reason()).isEqualTo(ValuationReason.FX_RATE_STALE);
    }
}
