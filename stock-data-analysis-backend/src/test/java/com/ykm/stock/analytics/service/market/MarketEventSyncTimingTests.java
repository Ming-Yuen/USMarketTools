package com.ykm.stock.analytics.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MarketEventSyncTimingTests {
    private final MarketEventRepository repository = mock(MarketEventRepository.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final MarketEventSyncService service = new MarketEventSyncService(repository, clock,
            mock(StockRepository.class), mock(FinnhubService.class), new ObjectMapper(), transaction);

    // Scenario：同一日程不再保存revision/coverage；fallback仍標示真實來源，不冒充官方時間。
    @Test
    void fallbackEmploymentReleaseUsesCanonicalSubtypeAndHonestSource() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://compasseconomiccalendar.github.io/calendar.json"))
                .andRespond(withSuccess("""
                        {"events":[{"event_type":"macro_release_employment_situation","start_utc":"2026-11-06T13:30:00Z",
                        "source":"FRED","id":"employment-2026-11","title":"Employment Situation","approximate":true}]}
                        """, MediaType.APPLICATION_JSON));
        when(clock.nowZonedDateTime(any())).thenReturn(ZonedDateTime.parse("2026-10-26T00:00:00Z"));
        ReflectionTestUtils.setField(service, "publicRestClient", builder.build());

        List<MarketEvent> rows = ReflectionTestUtils.invokeMethod(service, "downloadCompass",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 12, 10));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getEventKey()).isEqualTo("MACRO|NONFARM_PAYROLLS|2026-11-06");
            assertThat(row.getEventSubtype()).isEqualTo("NONFARM_PAYROLLS");
            assertThat(row.getSource()).isEqualTo("COMPASS_FALLBACK");
            assertThat(row.getTimePrecision()).isEqualTo("DATE_ONLY");
            assertThat(row.getOccurrenceAt()).isNull();
            assertThat(row.getSourcePublishedAt()).isNull();
        });
        verifyNoInteractions(repository);
        server.verify();
    }

    @Test
    void unchangedEventDoesNotReplaceItsFirstObservation() {
        executeTransactionInline();
        Instant original = Instant.parse("2026-10-26T00:00:00Z");
        MarketEvent existing = event(original);
        MarketEvent repeated = event(original.plusSeconds(3600));
        when(repository.findAllById(List.of(existing.getEventKey()))).thenReturn(List.of(existing));

        service.save(List.of(repeated));

        verify(repository, never()).saveAllAndFlush(any());
        assertThat(existing.getObservedAt()).isEqualTo(original);
    }

    @Test
    void changedEventKeepsItsBusinessKeyAndNewObservation() {
        executeTransactionInline();
        Instant original = Instant.parse("2026-10-26T00:00:00Z");
        MarketEvent existing = event(original);
        MarketEvent changed = event(original.plusSeconds(3600));
        changed.setEventDate(existing.getEventDate().plusDays(1));
        when(repository.findAllById(List.of(existing.getEventKey()))).thenReturn(List.of(existing));

        service.save(List.of(changed));

        verify(repository).saveAllAndFlush(List.of(changed));
        assertThat(changed.getEventKey()).isEqualTo(existing.getEventKey());
        assertThat(changed.getObservedAt()).isAfter(existing.getObservedAt());
    }

    @Test
    void unknownEarningsClockStaysDateOnlyInsteadOfInventingNoon() {
        assertThat(service.earningsPrecision(null)).isEqualTo("DATE_ONLY");
        assertThat(service.earningsPrecision("")).isEqualTo("DATE_ONLY");
        assertThat(service.earningsPrecision("unknown")).isEqualTo("DATE_ONLY");
        assertThat(service.earningsPrecision("bmo")).isEqualTo("BEFORE_OPEN");
        assertThat(service.earningsPrecision("amc")).isEqualTo("AFTER_CLOSE");
    }

    private void executeTransactionInline() {
        doAnswer(call -> {
            Consumer<TransactionStatus> action = call.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
    }

    private MarketEvent event(Instant observedAt) {
        MarketEvent row = new MarketEvent();
        row.setEventKey("EARNINGS|FIGI-AAPL|2026-Q4");
        row.setFigi("FIGI-AAPL");
        row.setEventType("EARNINGS");
        row.setEventSubtype("EARNINGS_REPORT");
        row.setTitle("AAPL earnings");
        row.setEventDate(LocalDate.of(2026, 11, 3));
        row.setTimePrecision("DATE_ONLY");
        row.setSource("Finnhub");
        row.setStatus("SCHEDULED");
        row.setObservedAt(observedAt);
        return row;
    }
}
