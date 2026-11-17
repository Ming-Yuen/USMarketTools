package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily.SentimentMetric;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.service.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketContextEventQueryTests {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);
    private static final Instant AS_OF = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant OLD = Instant.parse("2026-11-03T22:00:00Z");
    private final MarketStatusService calendar = new MarketStatusService(null);
    private final MarketEventRepository events = mock(MarketEventRepository.class);
    private final MarketSentimentDailyRepository sentiments = mock(MarketSentimentDailyRepository.class);
    private final TreasuryParYieldDailyRepository treasuries = mock(TreasuryParYieldDailyRepository.class);
    private final MarketContextService service = new MarketContextService(
            new MomentumCalculator(new WilderAtrCalculator()), new VolumeAnalysisCalculator(), new RelativeStrengthCalculator(),
            new DrawdownCalculator(), new MarketRegimeCalculator(), sentiments, treasuries, events,
            mock(StockRepository.class), mock(QuoteNormalizationService.class), calendar);

    // Scenario：原來先找ID再讀revision；現在一個有界、帶asOf的typed查詢，不再宣稱完整歷史日曆。
    @ParameterizedTest
    @EnumSource(AnalysisHorizon.class)
    void eventReadsUseOneBoundedQueryAndPreserveSourcePrecision(AnalysisHorizon horizon) {
        MarketEvent earnings = event("EARNINGS|FIGI|2026-Q4", "EARNINGS", "EARNINGS_REPORT", DAY.plusDays(1));
        when(events.findVisible("FIGI", DAY.minusDays(10), DAY.plusDays(20), AS_OF)).thenReturn(List.of(earnings));

        MarketContextDto result = evaluate(horizon);

        assertThat(result.events()).singleElement().satisfies(row -> {
            assertThat(row.eventId()).isEqualTo(earnings.getEventKey());
            assertThat(row.eventDate()).isEqualTo(DAY.plusDays(1));
            assertThat(row.timePrecision()).isEqualTo("DATE_ONLY");
            assertThat(row.occurrenceTime()).isNull();
            assertThat(row.observedAt()).isEqualTo(OLD);
            assertThat(row.source()).isEqualTo("Finnhub");
            assertThat(row.blocksNewEntry()).isTrue();
        });
        assertThat(result.tradeConstraint().entryPermission()).isEqualTo(EntryPermission.BLOCK);
        verify(events).findVisible("FIGI", DAY.minusDays(10), DAY.plusDays(20), AS_OF);
        verifyNoMoreInteractions(events);
    }

    @Test
    void emptyCalendarDoesNotClaimCompleteCoverageOrWriteAnything() {
        MarketContextDto result = evaluate(AnalysisHorizon.SWING);

        assertThat(result.events()).isEmpty();
        assertThat(result.reasons()).extracting(AnalysisReasonDto::code).contains("KNOWN_EVENTS_ONLY");
        assertThat(result.tradeConstraint().entryPermission()).isEqualTo(EntryPermission.UNKNOWN);
        verify(events).findVisible("FIGI", DAY.minusDays(10), DAY.plusDays(20), AS_OF);
        verifyNoMoreInteractions(events);
    }

    // Before: fetching after close incorrectly hid the day's VIX/Treasury/CNN data.
    // After: completed business dates select daily facts; real ingestion/source-update times are preserved, not used as close gates.
    @Test
    void lateDownloadedDailyFactsRemainAvailableOnTheirBusinessDate() {
        LocalDate marketDate = DAY.minusDays(1);
        Instant downloaded = AS_OF.plusSeconds(60);
        MarketSentimentDaily row = sentiment(marketDate);
        row.setFearAndGreed(new SentimentMetric(new BigDecimal("40"), downloaded.minusSeconds(1), downloaded));
        row.setVixClose(new BigDecimal("24"));
        row.setVixObservedAt(downloaded);
        TreasuryParYieldDaily treasury = new TreasuryParYieldDaily();
        treasury.setObservationDate(marketDate);
        treasury.setTenYear(new BigDecimal("4.2"));
        treasury.setTwoYear(new BigDecimal("4.0"));
        treasury.setObservedAt(downloaded);
        treasury.setSourceUpdatedAtUtc(java.time.LocalDateTime.ofInstant(downloaded, java.time.ZoneOffset.UTC));
        when(sentiments.findByMarketDateBetweenOrderByMarketDateAsc(marketDate.minusDays(400), marketDate)).thenReturn(List.of(row));
        when(treasuries.findByObservationDateBetweenOrderByObservationDateAsc(marketDate.minusDays(400), marketDate)).thenReturn(List.of(treasury));

        MarketContextDto result = evaluate(AnalysisHorizon.SWING);

        assertThat(result.sourceFreshness().get("CNN_FG").dataDate()).isEqualTo(marketDate);
        assertThat(result.sourceFreshness().get("CNN_FG").observedAt()).isEqualTo(downloaded);
        assertThat(result.sourceFreshness().get("VIX_CLOSE").dataDate()).isEqualTo(marketDate);
        assertThat(result.sourceFreshness().get("VIX_CLOSE").status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(result.sourceFreshness().get("VIX_CLOSE").availableAt()).isNull();
        assertThat(result.sourceFreshness().get("VIX_CLOSE").observedAt()).isEqualTo(downloaded);
        assertThat(result.treasury().tenYear().value()).isEqualByComparingTo("4.2");
        assertThat(result.treasury().curve10yMinus2y().value()).isEqualByComparingTo("0.2");
        verify(sentiments).findByMarketDateBetweenOrderByMarketDateAsc(marketDate.minusDays(400), marketDate);
        verify(treasuries).findByObservationDateBetweenOrderByObservationDateAsc(marketDate.minusDays(400), marketDate);
        verifyNoMoreInteractions(sentiments, treasuries);
    }

    // Before: a latest report stopped event visibility at the previous close.
    // After: latest observations use the explicit read cutoff; the same event does not leak into an explicitly historical cutoff.
    @Test
    void eventVisibilitySeparatesLatestReadFromExplicitHistoricalCutoff() {
        Instant readAt = AS_OF.plusSeconds(3600);
        MarketEvent event = event("EARNINGS|LATEST", "EARNINGS", "EARNINGS_REPORT", DAY.plusDays(1));
        event.setObservedAt(AS_OF.plusSeconds(1800));
        when(events.findVisible("FIGI", DAY.minusDays(10), DAY.plusDays(20), readAt)).thenReturn(List.of(event));

        MarketContextDto latest = evaluate(AnalysisHorizon.SWING, readAt);
        MarketContextDto historical = evaluate(AnalysisHorizon.SWING, AS_OF);

        assertThat(latest.events()).singleElement().satisfies(value -> assertThat(value.observedAt()).isEqualTo(event.getObservedAt()));
        assertThat(historical.events()).isEmpty();
        verify(events).findVisible("FIGI", DAY.minusDays(10), DAY.plusDays(20), readAt);
        verify(events).findVisible("FIGI", DAY.minusDays(10), DAY.plusDays(20), AS_OF);
        verifyNoMoreInteractions(events);
    }

    private MarketContextDto evaluate(AnalysisHorizon horizon) { return evaluate(horizon, AS_OF); }

    private MarketContextDto evaluate(AnalysisHorizon horizon, Instant knownAt) {
        List<LocalDate> dates = calendar.getTradingDays(DAY.minusYears(2), DAY.minusDays(1));
        dates = dates.subList(dates.size() - 273, dates.size());
        List<NormalizedBarDto> bars = dates.stream().map(day -> new NormalizedBarDto(day,
                BigDecimal.TEN, BigDecimal.valueOf(11), BigDecimal.valueOf(9), BigDecimal.TEN,
                MetricResult.available(BigDecimal.valueOf(100)), calendar.getSessionOpenAt(day), calendar.getSessionCloseAt(day),
                calendar.getSessionCloseAt(day), calendar.getSessionCloseAt(day), "QUOTE", "BASIS")).toList();
        PriceBasisDescriptor basis = new PriceBasisDescriptor("BASIS", "ALPACA", PriceAdjustment.SPLIT_ADJUSTED, DAY.minusDays(1),
                null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, null);
        NormalizedPriceSeriesDto prices = new NormalizedPriceSeriesDto("FIGI", "DAILY", bars, basis, null, MetricStatus.AVAILABLE, List.of(), null);
        return service.evaluate(new AnalysisContext("FIGI", AS_OF, knownAt, horizon), prices, null).value();
    }

    private MarketSentimentDaily sentiment(LocalDate date) {
        MarketSentimentDaily row = new MarketSentimentDaily();
        row.setMarketDate(date);
        return row;
    }

    private MarketEvent event(String key, String type, String subtype, LocalDate date) {
        MarketEvent row = new MarketEvent();
        row.setEventKey(key);
        row.setFigi("FIGI");
        row.setEventType(type);
        row.setEventSubtype(subtype);
        row.setTitle("Company earnings");
        row.setEventDate(date);
        row.setTimePrecision("DATE_ONLY");
        row.setSource("Finnhub");
        row.setObservedAt(OLD);
        row.setStatus("SCHEDULED");
        return row;
    }
}