package com.ykm.stock.analytics.service.market;

import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.client.FredVixClient;
import com.ykm.stock.analytics.client.FredVixClient.VixPoint;
import com.ykm.stock.analytics.client.YahooVixClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.thirdparty.response.YahooVixChartResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.service.analysis.VixObservationSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VixObservationSyncServiceTests {
    // Scenario：以前虛設16:15發布時間；現在只保留實際觀測時間，CNN VIX分數與真VIX不混用。
    @Test
    void writesActualVixToTypedDailyTableWithoutInventedPublicationTime() {
        YahooVixClient client = mock(YahooVixClient.class);
        MarketSentimentService sentiment = mock(MarketSentimentService.class);
        MarketStatusService market = mock(MarketStatusService.class);
        LocalDate date = LocalDate.of(2026, 10, 15);
        when(market.getLastClosedTradingDay()).thenReturn(date);
        when(client.fetchDaily()).thenReturn(response(date, 24.75));
        Instant observedAt = Instant.parse("2026-10-16T12:30:00Z");

        VixObservationSyncService service = new VixObservationSyncService(client, sentiment, market);
        List<MarketSentimentDaily> rows = service.downloadObservations(observedAt);
        verifyNoInteractions(sentiment);
        service.saveObservations(rows);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getMarketDate()).isEqualTo(date);
            assertThat(row.getVixClose()).isEqualByComparingTo("24.75");
            assertThat(row.getVixSource()).isEqualTo("YAHOO_FINANCE_CBOE_VIX");
            assertThat(row.getVixSourceAt()).isNull();
            assertThat(row.getVixObservedAt()).isEqualTo(observedAt);
            assertThat(row.getMarketVolatilityVix()).isNull();
        });
        verify(sentiment).save(rows);
    }

    @Test
    void staleSourceFailsDuringDownloadWithoutSaving() {
        YahooVixClient client = mock(YahooVixClient.class);
        MarketSentimentService sentiment = mock(MarketSentimentService.class);
        MarketStatusService market = mock(MarketStatusService.class);
        LocalDate required = LocalDate.of(2026, 10, 16);
        when(market.getLastClosedTradingDay()).thenReturn(required);
        when(client.fetchDaily()).thenReturn(response(required.minusDays(1), 24.75));

        assertThatThrownBy(() -> new VixObservationSyncService(client, sentiment, market)
                .downloadObservations(Instant.parse("2026-10-17T12:30:00Z")))
                .hasMessageContaining("VIX_CLOSE_STALE_SOURCE");
        verifyNoInteractions(sentiment);
    }

    @Test
    void mismatchedTimestampsAndPricesAreRejected() {
        YahooVixClient client = mock(YahooVixClient.class);
        MarketSentimentService sentiment = mock(MarketSentimentService.class);
        MarketStatusService market = mock(MarketStatusService.class);
        LocalDate required = LocalDate.of(2026, 10, 16);
        when(market.getLastClosedTradingDay()).thenReturn(required);
        YahooVixChartResponse reply = response(required, 24.75);
        reply.getChart().getResult().getFirst().setTimestamp(List.of());
        when(client.fetchDaily()).thenReturn(reply);

        assertThatThrownBy(() -> new VixObservationSyncService(client, sentiment, market)
                .downloadObservations(Instant.parse("2026-10-17T12:30:00Z")))
                .hasMessage("VIX_BAR_LENGTH_MISMATCH");
        verifyNoInteractions(sentiment);
    }

    @Test
    @SuppressWarnings("unchecked")
    void backfillHistoricalIfNeededWritesOnlyMissingFredDatesAndSkipsWhenAlreadyComplete() {
        YahooVixClient yahoo = mock(YahooVixClient.class);
        MarketSentimentService sentiment = mock(MarketSentimentService.class);
        MarketStatusService market = mock(MarketStatusService.class);
        FredVixClient fred = mock(FredVixClient.class);
        MarketSentimentDailyRepository repository = mock(MarketSentimentDailyRepository.class);
        PortfolioSysConfigRepository sysConfigRepository = mock(PortfolioSysConfigRepository.class);
        JobConfigRepository jobConfigRepository = mock(JobConfigRepository.class);

        LocalDate start = LocalDate.of(2016, 1, 1);
        LocalDate through = LocalDate.of(2016, 2, 10);
        LocalDate prev = LocalDate.of(2016, 2, 9);
        List<LocalDate> sessions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sessions.add(LocalDate.of(2016, 1, 4).plusDays(i));
        }

        SysConfig startConfig = new SysConfig();
        startConfig.setConfigKey(PortfolioSysConfigRepository.KEY_SYNC_HISTORY_START_DATE);
        startConfig.setConfigValue("2016-01-01");
        startConfig.setEnabled(true);
        when(sysConfigRepository.findByConfigKeyInOrderByIdAsc(List.of(PortfolioSysConfigRepository.KEY_SYNC_HISTORY_START_DATE)))
                .thenReturn(List.of(startConfig));
        when(jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.MARKET_SENTIMENT_DAILY)).thenReturn(Optional.empty());
        when(fred.isConfigured()).thenReturn(true);
        when(market.getLastClosedTradingDay()).thenReturn(through);
        when(market.getPreviousTradingDay(through)).thenReturn(prev);
        when(market.getTradingDays(start, prev)).thenReturn(sessions);
        when(market.isTradingDay(LocalDate.of(2016, 1, 4))).thenReturn(true);
        when(market.isTradingDay(LocalDate.of(2016, 1, 18))).thenReturn(false);
        // Existing Yahoo row on 2016-01-05 must NOT be overwritten; non-NYSE holiday 2016-01-18 must be skipped
        when(repository.findDatesWithVixBetween(start, through)).thenReturn(List.of(LocalDate.of(2016, 1, 5)));
        when(fred.fetchVixCloses(start, through)).thenReturn(List.of(
                new VixPoint(LocalDate.of(2016, 1, 4), new BigDecimal("20.7000"), LocalDate.of(2026, 11, 20)),
                new VixPoint(LocalDate.of(2016, 1, 5), new BigDecimal("19.3400"), LocalDate.of(2026, 11, 20)),
                new VixPoint(LocalDate.of(2016, 1, 18), new BigDecimal("24.1000"), LocalDate.of(2026, 11, 20))
        ));

        VixObservationSyncService service = new VixObservationSyncService(yahoo, sentiment, market, fred, repository);
        ReflectionTestUtils.setField(service, "sysConfigRepository", sysConfigRepository);
        ReflectionTestUtils.setField(service, "jobConfigRepository", jobConfigRepository);
        Instant observedAt = Instant.parse("2026-11-21T10:00:00Z");
        int inserted = service.backfillHistoricalIfNeeded(observedAt);

        assertThat(inserted).isEqualTo(1);
        ArgumentCaptor<List<MarketSentimentDaily>> captor = ArgumentCaptor.forClass(List.class);
        verify(sentiment).save(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getMarketDate()).isEqualTo(LocalDate.of(2016, 1, 4));
            assertThat(row.getVixClose()).isEqualByComparingTo("20.7000");
            assertThat(row.getVixSource()).isEqualTo(FredVixClient.SOURCE_FRED_VIXCLS);
            assertThat(row.getVixSourceAt()).isNull();
            assertThat(row.getVixObservedAt()).isEqualTo(observedAt);
        });

        // Second call when all sessions are already present -> 0 network calls
        reset(fred, sentiment);
        when(fred.isConfigured()).thenReturn(true);
        when(repository.findDatesWithVixBetween(start, through)).thenReturn(sessions);
        assertThat(service.backfillHistoricalIfNeeded(observedAt)).isZero();
        verify(fred, never()).fetchVixCloses(any(), any());
        verifyNoInteractions(sentiment);

        // Incremental run with watermark W only checks [W - 45d, through]
        LocalDate watermark = LocalDate.of(2026, 11, 10);
        LocalDate latestDay = LocalDate.of(2026, 11, 20);
        LocalDate prevDay = LocalDate.of(2026, 11, 19);
        LocalDate overlapFrom = watermark.minusDays(45);
        when(jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.MARKET_SENTIMENT_DAILY)).thenReturn(Optional.of(watermark));
        when(market.getLastClosedTradingDay()).thenReturn(latestDay);
        when(market.getPreviousTradingDay(latestDay)).thenReturn(prevDay);
        when(market.getTradingDays(overlapFrom, prevDay)).thenReturn(List.of(LocalDate.of(2026, 10, 15)));
        when(market.isTradingDay(LocalDate.of(2026, 10, 15))).thenReturn(true);
        when(repository.findDatesWithVixBetween(overlapFrom, latestDay)).thenReturn(List.of());
        when(fred.fetchVixCloses(overlapFrom, latestDay)).thenReturn(List.of(
                new VixPoint(LocalDate.of(2026, 10, 15), new BigDecimal("18.5000"), latestDay)));

        assertThat(service.backfillHistoricalIfNeeded(observedAt)).isEqualTo(1);
        verify(fred).fetchVixCloses(overlapFrom, latestDay);
    }

    private YahooVixChartResponse response(LocalDate date, double close) {
        YahooVixChartResponse.QuoteWrapper quote = new YahooVixChartResponse.QuoteWrapper();
        quote.setClose(List.of(close));
        YahooVixChartResponse.Indicators indicators = new YahooVixChartResponse.Indicators();
        indicators.setQuote(List.of(quote));
        YahooVixChartResponse.Result result = new YahooVixChartResponse.Result();
        result.setTimestamp(List.of(date.atTime(9, 30).atZone(ZoneId.of("America/New_York")).toEpochSecond()));
        result.setIndicators(indicators);
        YahooVixChartResponse.Chart chart = new YahooVixChartResponse.Chart();
        chart.setResult(List.of(result));
        YahooVixChartResponse response = new YahooVixChartResponse();
        response.setChart(chart);
        return response;
    }
}
