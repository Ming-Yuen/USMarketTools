package com.ykm.stock.analytics.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.client.FredVixClient.VixPoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FredVixClientTests {

    @Test
    void fetchesAndParsesOfficialVixclsSkippingHolidayDots() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.stlouisfed.org/fred");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String payload = """
                {
                  "observations": [
                    {"realtime_start":"2026-11-20","date":"2020-03-16","value":"82.69"},
                    {"realtime_start":"2026-11-20","date":"2020-04-10","value":"."},
                    {"realtime_start":"2026-11-20","date":"2025-04-08","value":"52.33"}
                  ]
                }
                """;
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo("/fred/series/observations");
            assertThat(request.getURI().getQuery()).contains("series_id=VIXCLS")
                    .contains("observation_start=2020-03-01")
                    .contains("observation_end=2025-04-10");
        }).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        FredVixClient client = new FredVixClient(new ObjectMapper(), "https://api.stlouisfed.org/fred", "test-key");
        ReflectionTestUtils.setField(client, "restClient", builder.build());

        List<VixPoint> points = client.fetchVixCloses(LocalDate.of(2020, 3, 1), LocalDate.of(2025, 4, 10));
        assertThat(points).hasSize(2);
        assertThat(points.get(0).observationDate()).isEqualTo(LocalDate.of(2020, 3, 16));
        assertThat(points.get(0).vixClose()).isEqualByComparingTo("82.6900");
        assertThat(points.get(1).observationDate()).isEqualTo(LocalDate.of(2025, 4, 8));
        assertThat(points.get(1).vixClose()).isEqualByComparingTo("52.3300");
        server.verify();
    }

    @Test
    void unauthorizedStatusFailsImmediatelyWithoutRetry() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
            RestClient.Builder builder = RestClient.builder().baseUrl("https://api.stlouisfed.org/fred");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(request -> assertThat(request.getURI().getQuery()).contains("series_id=VIXCLS"))
                    .andRespond(withStatus(status));

            FredVixClient client = new FredVixClient(new ObjectMapper(), "https://api.stlouisfed.org/fred", "bad-key");
            ReflectionTestUtils.setField(client, "restClient", builder.build());

            assertThatThrownBy(() -> client.fetchVixCloses(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("FRED_API_UNAUTHORIZED:" + status.value());
            server.verify();
        }
    }

    @Test
    void unconfiguredApiKeyIsReportedCleanly() {
        FredVixClient client = new FredVixClient(new ObjectMapper(), "https://api.stlouisfed.org/fred", "   ");
        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.fetchVixCloses(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FRED_API_KEY_NOT_CONFIGURED");
    }
}
