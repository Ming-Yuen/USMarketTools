package com.ykm.stock.analytics.service.futu;

import com.futu.openapi.pb.QotCommon.ValuationType;
import com.futu.openapi.pb.QotGetValuationDetail;
import com.futu.openapi.pb.QotGetValuationDetail.ValuationTrend;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.futu.openapi.pb.QotCommon.ValuationType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FutuStockDataServiceTests {
    private static final LocalDate MAY_30 = LocalDate.of(2025, 5, 30);
    private final StockRepository stocks = mock(StockRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final FutuService futu = mock(FutuService.class);
    private final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
    private final FutuStockDataService data = new FutuStockDataService(stocks, calendar, futu);

    @BeforeEach
    void stockAndSession() {
        Stock stock = new Stock();
        stock.setFigi("F1");
        stock.setSymbol("AAPL");
        when(stocks.findById("F1")).thenReturn(Optional.of(stock));
        when(futu.openObservationSession()).thenReturn(session);
    }

    @Test
    void returnsNativeMonthlyValuesAndPreservesMissingRatios() {
        when(calendar.getLastClosedTradingDay(any())).thenReturn(MAY_30);
        when(session.valuationDetail(eq("AAPL"), eq(ValuationType_PE), any())).thenReturn(
                series(ValuationType_PE, point("2025-04-29", 12), point("2025-04-30", 15),
                        point("2025-05-30", 21), point("2025-06-02", 30)));
        when(session.valuationDetail(eq("AAPL"), eq(ValuationType_PB), any())).thenReturn(
                series(ValuationType_PB, point("2025-04-30", 3)));
        when(session.valuationDetail(eq("AAPL"), eq(ValuationType_PS), any())).thenReturn(
                series(ValuationType_PS, point("2025-05-30", 2)));

        List<ValuationResultDto> rows = data.history("F1", MAY_30.minusMonths(2), MAY_30.plusDays(7), "MONTH_END");

        assertThat(rows).extracting(ValuationResultDto::valuationDate).containsExactly(LocalDate.of(2025, 4, 30), MAY_30);
        assertThat(rows).extracting(ValuationResultDto::source).containsOnly("FUTU");
        assertThat(rows.getFirst().metrics().pe().value()).isEqualByComparingTo("15");
        assertThat(rows.getLast().metrics().ps().value()).isEqualByComparingTo("2");
        assertThat(rows.getLast().metrics().pb().value()).isNull();
        assertThat(rows.getLast().status()).isEqualTo("AVAILABLE");
        assertThat(rows.getLast().reasonCode()).isNull();
        verify(session).close();
    }

    @Test
    void providerFailureIsReportedAndConnectionIsClosed() {
        when(calendar.getLastClosedTradingDay(any())).thenReturn(MAY_30);
        when(session.valuationDetail(eq("AAPL"), eq(ValuationType_PE), any()))
                .thenThrow(new IllegalStateException("FUTU_GET_VALUATION_DETAIL_TIMEOUT"));

        assertThatThrownBy(() -> data.history("F1", MAY_30.minusDays(1), MAY_30, "DAILY"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.getReason()).contains("FUTU_GET_VALUATION_DETAIL_TIMEOUT");
                });
        verify(session).close();
    }

    private static ValuationTrend.ValuationHistoricalItem point(String date, double value) {
        return ValuationTrend.ValuationHistoricalItem.newBuilder().setTimeStr(date).setValue(value).build();
    }

    private static QotGetValuationDetail.S2C series(ValuationType metric, ValuationTrend.ValuationHistoricalItem... points) {
        return QotGetValuationDetail.S2C.newBuilder().setValuationType(metric)
                .setTrend(ValuationTrend.newBuilder().addAllHistoricalItems(List.of(points))).build();
    }
}
