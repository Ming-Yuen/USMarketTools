package com.ykm.stock.analytics.controller;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetEarningsCalendar.EarningsCalendarEstimateType;
import com.futu.openapi.pb.QotGetEarningsCalendar.EarningsCalendarItem;
import com.futu.openapi.pb.QotGetEarningsCalendar.EarningsCalendarPeriodType;
import com.futu.openapi.pb.QotGetEarningsCalendar.EstimateData;
import com.futu.openapi.pb.QotGetEarningsCalendar.S2C;
import com.ykm.stock.analytics.controller.StockController.BatchTrackedRequest;
import com.ykm.stock.analytics.controller.StockController.TrackedRequest;
import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.repository.jpa.StockConceptRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.FinnhubService;
import com.ykm.stock.analytics.service.stock.EarningsForecastSyncService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.service.stock.StockService.BatchTrackedUpdateResult;
import com.ykm.stock.analytics.service.stock.StockService.TrackedUpdateResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockTrackedAndControllerTests {
    private final StockRepository stockRepository = mock(StockRepository.class);
    private final StockStatusRepository statusRepository = mock(StockStatusRepository.class);
    private final StockIndustryRepository industryRepository = mock(StockIndustryRepository.class);
    private final StockClassificationConverter converter = mock(StockClassificationConverter.class);
    private final StockConceptRepository conceptRepository = mock(StockConceptRepository.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StockService stockService = new StockService(
            stockRepository, statusRepository, industryRepository, converter, conceptRepository, jdbc);
    private final StockController controller = new StockController(stockService);

    /** Scenario：PATCH /stock/{figi}/tracked 與 POST /stock/tracked/batch 支援單筆與批量停追／恢復，未知 FIGI 整批拒絕。 */
    @Test
    void singleAndBatchTrackedEndpointsUpdateTrackedStateAndRejectUnknownFigis() {
        Stock aapl = stock("F1", "AAPL", true);
        Stock msft = stock("F2", "MSFT", true);
        when(stockRepository.findById("F1")).thenReturn(Optional.of(aapl));
        when(stockRepository.findAllById(anyCollection())).thenReturn(List.of(aapl, msft));

        TrackedUpdateResult single = controller.updateTracked("F1", new TrackedRequest(false));
        assertThat(single).isEqualTo(new TrackedUpdateResult("F1", "AAPL", false));
        assertThat(aapl.isTracked()).isFalse();
        verify(stockRepository).saveAndFlush(aapl);

        BatchTrackedUpdateResult batch = controller.updateTrackedBatch(new BatchTrackedRequest(List.of("F1", "F2"), true));
        assertThat(batch.updatedCount()).isEqualTo(2);
        assertThat(aapl.isTracked()).isTrue();
        assertThat(msft.isTracked()).isTrue();
        verify(stockRepository).saveAllAndFlush(List.of(aapl, msft));

        when(stockRepository.findAllById(anyCollection())).thenReturn(List.of(aapl));
        assertThatThrownBy(() -> controller.updateTrackedBatch(new BatchTrackedRequest(List.of("F1", "MISSING"), false)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** Scenario：tracked = false 的股票在盈餘預測同步中被排除，不寫入預測表。 */
    @Test
    void earningsForecastSkipsUntrackedStock() {
        Stock untracked = stock("F1", "AAPL", false);
        Stock tracked = stock("F2", "MSFT", true);
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(untracked, tracked));
        EarningsForecastSyncService forecastService = new EarningsForecastSyncService(
                stockRepository, mock(StockEarningsForecastRepository.class));
        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(session.earningsCalendar(any(), any())).thenReturn(S2C.newBuilder()
                .addItemList(calendarItem("AAPL", "2026-11-18"))
                .addItemList(calendarItem("MSFT", "2026-11-18")).build());

        List<StockEarningsForecast> forecasts = forecastService.download(
                session, new EarningsForecastSyncService.Window(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 20)));

        assertThat(forecasts).singleElement().satisfies(row -> assertThat(row.getFigi()).isEqualTo("F2"));
    }

    /** Scenario：Finnhub HTTP 403 無權限錯誤必須立即失敗，不再掛在 @Retryable 重試清單中。 */
    @Test
    void finnhubServiceDoesNotRetryHttpForbidden() throws Exception {
        Method getMethod = FinnhubService.class.getMethod("get", String.class, Map.class, ParameterizedTypeReference.class);
        Retryable retryable = getMethod.getAnnotation(Retryable.class);
        assertThat(retryable).isNotNull();
        assertThat(Arrays.asList(retryable.retryFor())).doesNotContain(HttpClientErrorException.Forbidden.class);
        assertThat(Arrays.asList(retryable.value())).doesNotContain(HttpClientErrorException.Forbidden.class);
        verify(stockRepository, never()).save(any());
    }

    private Stock stock(String figi, String symbol, boolean tracked) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setStockType("Common Stock");
        stock.setActiveStatus(ActiveStatus.ACTIVE);
        stock.setTracked(tracked);
        return stock;
    }

    private EarningsCalendarItem calendarItem(String symbol, String date) {
        return EarningsCalendarItem.newBuilder()
                .setSecurity(QotCommon.Security.newBuilder()
                        .setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE)
                        .setCode(symbol))
                .setEarningsDate(date)
                .setPeriodText("2026Q4")
                .addEstimateList(EstimateData.newBuilder()
                        .setEstimateType(EarningsCalendarEstimateType.EarningsCalendarEstimateType_EPS_VALUE)
                        .setPeriodType(EarningsCalendarPeriodType.EarningsCalendarPeriodType_Quarterly_VALUE)
                        .setCurrency("USD")
                        .setPredictValue(2.5))
                .build();
    }
}
