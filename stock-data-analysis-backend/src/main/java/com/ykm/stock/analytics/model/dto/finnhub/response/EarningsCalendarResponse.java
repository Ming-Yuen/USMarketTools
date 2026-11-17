package com.ykm.stock.analytics.model.dto.finnhub.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class EarningsCalendarResponse {
    private List<EarningsCalendarEntry> earningsCalendar;

    @Data
    public static class EarningsCalendarEntry {
        private LocalDate date;
        private String symbol;
        private String hour;
        private Integer quarter;
        private Integer year;
        private BigDecimal epsEstimate;
        private BigDecimal revenueEstimate;
    }
}
