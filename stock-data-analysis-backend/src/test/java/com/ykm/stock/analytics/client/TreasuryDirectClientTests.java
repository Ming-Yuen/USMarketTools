package com.ykm.stock.analytics.client;

import com.ykm.common.common_lib.service.DateTimeService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

class TreasuryDirectClientTests {
    @Test
    void usesUsSourceMonthAfterHongKongHasCrossedIntoTheNextMonth() {
        Instant now = Instant.parse("2026-10-31T21:00:00Z");
        ZoneId newYork = ZoneId.of("America/New_York");
        DateTimeService clock = mock(DateTimeService.class);
        when(clock.nowLocalDate()).thenReturn(now.atZone(ZoneId.of("Asia/Hong_Kong")).toLocalDate());
        when(clock.nowZonedDateTime(newYork)).thenReturn(now.atZone(newYork));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer http = MockRestServiceServer.bindTo(builder).build();
        http.expect(requestTo("https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml"
                        + "?data=daily_treasury_yield_curve&field_tdr_date_value_month=202610"))
                .andRespond(withNoContent());

        new TreasuryDirectClient(builder.build(), clock).getCurrentMonthYieldCurve();

        http.verify();
    }
}
