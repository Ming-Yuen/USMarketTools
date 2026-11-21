package com.ykm.stock.analytics.client;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class TreasuryDirectClient {

    private static final ZoneId SOURCE_ZONE = ZoneId.of("America/New_York");

    private final RestClient restClient;
    private final DateTimeService dateTimeService;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMM");

    public TreasuryYieldCurveFeed getCurrentMonthYieldCurve() {
        YearMonth currentMonth = YearMonth.from(dateTimeService.nowZonedDateTime(SOURCE_ZONE));
        return getYieldCurveForMonth(currentMonth);
    }

    public TreasuryYieldCurveFeed getYieldCurveForMonth(YearMonth month) {
        if (month == null) {
            throw new IllegalArgumentException("TREASURY_MONTH_REQUIRED");
        }
        String sourceMonth = month.format(formatter);
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("home.treasury.gov")
                        .path("/resource-center/data-chart-center/interest-rates/pages/xml")
                        .queryParam("data", "daily_treasury_yield_curve")
                        .queryParam("field_tdr_date_value_month", sourceMonth)
                        .build())
                .retrieve()
                .body(TreasuryYieldCurveFeed.class);
    }
}
