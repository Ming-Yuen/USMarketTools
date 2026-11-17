package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.client.YahooVixClient;
import com.ykm.stock.analytics.model.dto.thirdparty.response.YahooVixChartResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.service.analysis.VixObservationSyncService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
