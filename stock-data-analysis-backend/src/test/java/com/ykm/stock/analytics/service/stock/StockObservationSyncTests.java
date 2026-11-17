package com.ykm.stock.analytics.service.stock;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetEarningsCalendar;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockObservationSyncTests {
    private final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
    private final EarningsForecastSyncService earningsSync = new EarningsForecastSyncService(stocks, forecasts);

    // Scenario：缺少財政期或幣別不能當成已確認預期；發布日期也不能推成財季起止日。
    @Test
    void earningsSplitsSevenDayInclusiveWindowsAndRetainsSourceFiscalPeriod() {
        LocalDate start = LocalDate.of(2026, 11, 2);
        LocalDate last = start.plusDays(7);
        QotGetEarningsCalendar.EarningsCalendarItem entry = entry(last, "2026Q4", "USD");
        when(session.earningsCalendar(start, last.minusDays(1))).thenReturn(QotGetEarningsCalendar.S2C.getDefaultInstance());
        when(session.earningsCalendar(last, last)).thenReturn(QotGetEarningsCalendar.S2C.newBuilder().addItemList(entry).build());
        matchStock();

        List<StockEarningsForecast> result = earningsSync.download(session, new EarningsForecastSyncService.Window(start, last));

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.getFigi()).isEqualTo("FIGI-AAPL");
            assertThat(row.getFiscalPeriod()).isEqualTo("2026Q4");
            assertThat(row.getPeriodType()).isEqualTo("QUARTERLY");
            assertThat(row.getEps()).isEqualByComparingTo("-0.23");
            assertThat(row.getCurrency()).isEqualTo("USD");
            assertThat(row.getPeriodStart()).isNull();
            assertThat(row.getPeriodEnd()).isNull();
            assertThat(row.getSource()).isEqualTo("FUTU");
        });
        verify(session).earningsCalendar(start, last.minusDays(1));
        verify(session).earningsCalendar(last, last);
        verifyNoInteractions(forecasts);
    }

    @Test
    void missingCurrencyOrFiscalPeriodIsNotStoredAsUsableForecast() {
        matchStock();
        Instant observedAt = Instant.parse("2026-11-02T12:00:00Z");
        List<QotGetEarningsCalendar.EarningsCalendarItem> entries = List.of(
                entry(LocalDate.of(2026, 11, 3), "2026Q4", ""),
                entry(LocalDate.of(2026, 11, 3), "", "USD"));

        assertThat(earningsSync.matchExpectations(entries, observedAt)).isEmpty();
        verifyNoInteractions(forecasts);
    }

    @Test
    void earningsRejectsRowsOutsideTheirProviderWindow() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        when(session.earningsCalendar(day, day)).thenReturn(QotGetEarningsCalendar.S2C.newBuilder()
                .addItemList(entry(day.minusDays(1), "2026Q4", "USD")).build());

        assertThatThrownBy(() -> earningsSync.download(session, new EarningsForecastSyncService.Window(day, day)))
                .hasMessageStartingWith("EARNINGS_FORECAST_DATE_OUTSIDE_WINDOW");
        verifyNoInteractions(stocks, forecasts);
    }

    @Test
    void earningsAcceptsOperatingCompanyTypesWithCik() {
        Instant observedAt = Instant.parse("2026-11-02T12:00:00Z");
        for (String stockType : List.of("Common Stock", "REIT")) {
            Stock stock = earningsStock(stockType, "0000320193");
            when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("AAPL"))).thenReturn(List.of(stock));

            assertThat(earningsSync.matchExpectations(List.of(entry(LocalDate.of(2026, 11, 3), "2026Q4", "USD")), observedAt))
                    .as("ACTIVE %s with CIK", stockType).singleElement()
                    .satisfies(value -> assertThat(value.getFigi()).isEqualTo("FIGI-AAPL"));
        }
        verify(stocks, times(2)).findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("AAPL"));
        verifyNoInteractions(forecasts);
    }

    @Test
    void earningsExcludesEtfAdrWarrantAndUnknownType() {
        Instant observedAt = Instant.parse("2026-11-02T12:00:00Z");
        String[][] excluded = {
                {"ETP", "0000320193"}, {"Closed-End Fund", "0000320193"}, {"ADR", "0000320193"},
                {"WARRANT", "0000320193"}, {null, "0000320193"}
        };
        for (String[] candidate : excluded) {
            when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("AAPL")))
                    .thenReturn(List.of(earningsStock(candidate[0], candidate[1])));

            assertThat(earningsSync.matchExpectations(List.of(entry(LocalDate.of(2026, 11, 3), "2026Q4", "USD")), observedAt))
                    .as("Excluded type=%s cik=%s", candidate[0], candidate[1]).isEmpty();
        }
        verifyNoInteractions(forecasts);
    }

    @Test
    void excludedSecurityDoesNotMakeEligibleOrdinaryStockSymbolAmbiguous() {
        Stock ordinary = earningsStock("Common Stock", "0000320193");
        Stock warrant = earningsStock("WARRANT", "0000320193");
        warrant.setFigi("FIGI-WARRANT");
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("AAPL"))).thenReturn(List.of(ordinary, warrant));

        assertThat(earningsSync.matchExpectations(List.of(entry(LocalDate.of(2026, 11, 3), "2026Q4", "USD")),
                Instant.parse("2026-11-02T12:00:00Z"))).singleElement()
                .satisfies(value -> assertThat(value.getFigi()).isEqualTo("FIGI-AAPL"));
        verifyNoInteractions(forecasts);
    }

    @Test
    void earningsAcceptsOrdinaryStocksWithNullEmptyOrBlankCik() {
        for (String cik : new String[] {null, "", "   "}) {
            for (String type : List.of("Common Stock", "REIT")) {
                Stock stock = earningsStock(type, cik);
                stock.setSymbol("NBN");
                stock.setFigi("FIGI-NBN");
                when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("NBN"))).thenReturn(List.of(stock));
                QotGetEarningsCalendar.EarningsCalendarItem forecast = entry(LocalDate.of(2026, 11, 3), "2026Q4", "USD")
                        .toBuilder().setSecurity(security("NBN")).build();

                assertThat(earningsSync.matchExpectations(List.of(forecast), Instant.parse("2026-11-02T12:00:00Z")))
                        .as("type=%s cik=%s", type, cik).singleElement()
                        .satisfies(value -> assertThat(value.getFigi()).isEqualTo("FIGI-NBN"));
            }
        }
    }

    @Test
    void missingCikDoesNotHideAmbiguousEarningsSymbol() {
        Stock verified = earningsStock("Common Stock", "0000320193");
        Stock unresolved = earningsStock("Common Stock", null);
        unresolved.setFigi("FIGI-OTHER");
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("AAPL"))).thenReturn(List.of(verified, unresolved));

        assertThat(earningsSync.matchExpectations(List.of(entry(LocalDate.of(2026, 11, 3), "2026Q4", "USD")),
                Instant.parse("2026-11-02T12:00:00Z"))).isEmpty();
    }

    private static Stock earningsStock(String stockType, String cik) {
        Stock stock = new Stock();
        stock.setFigi("FIGI-AAPL");
        stock.setSymbol("AAPL");
        stock.setStockType(stockType);
        stock.setCik(cik);
        stock.setActiveStatus(ActiveStatus.ACTIVE);
        return stock;
    }

    private void matchStock() {
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, Set.of("AAPL")))
                .thenReturn(List.of(earningsStock("Common Stock", "0000320193")));
    }

    private static QotGetEarningsCalendar.EarningsCalendarItem entry(LocalDate date, String period, String currency) {
        return QotGetEarningsCalendar.EarningsCalendarItem.newBuilder().setSecurity(security("AAPL"))
                .setEarningsDate(date.toString()).setPeriodText(period).setPubType(2)
                .addEstimateList(QotGetEarningsCalendar.EstimateData.newBuilder().setEstimateType(1)
                        .setPeriodType(1).setPredictValue(-0.23).setCurrency(currency)).build();
    }

    private static QotCommon.Security security(String symbol) {
        return QotCommon.Security.newBuilder().setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setCode(symbol).build();
    }
}
