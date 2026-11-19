package com.ykm.stock.analytics.service.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.ExternalApiDailyQuota;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.key.ExternalApiDailyQuotaId;
import com.ykm.stock.analytics.repository.jpa.ExternalApiDailyQuotaRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnualEarningsForecastSyncServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-19T15:00:00Z");
    private static final LocalDate TODAY_NY = LocalDate.of(2026, 11, 19);

    @Test
    void parsesAndPersistsAnnualFy1Fy2With52And53WeekCalendarsAndMidnightObservationDate() {
        StockRepository stocks = mock(StockRepository.class);
        StockFinancialPeriodRepository periods = mock(StockFinancialPeriodRepository.class);
        FinancialReportRepository reports = mock(FinancialReportRepository.class);
        SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
        StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
        ExternalApiDailyQuotaRepository quotas = inMemoryQuotaRepo();
        DateTimeService clock = mock(DateTimeService.class);
        TransactionTemplate tx = mockTransactionTemplate();

        // 模擬請求跨越紐約午夜：預留配額時為 11-19 23:59:59 NY，收到回應時為 11-20 00:00:02 NY
        AtomicReference<Instant> currentClock = new AtomicReference<>(Instant.parse("2026-11-20T04:59:59Z"));
        when(clock.now()).thenAnswer(inv -> currentClock.get());

        // 1) Apple（年結 09-26 非月末）：AlphaVantage 回傳 09-30 應保留 periodEnd = null
        Stock aapl = operatingStock("FIGI-AAPL", "AAPL", "Common Stock", "USD", "0000320193");
        when(stocks.findById("FIGI-AAPL")).thenReturn(Optional.of(aapl));
        when(reports.findLatestReportCurrencies(eq("FIGI-AAPL"), any())).thenReturn(List.of("USD"));
        when(periods.findByFigiOrderByPeriodEndAsc("FIGI-AAPL"))
                .thenReturn(List.of(period("FIGI-AAPL", LocalDate.of(2026, 9, 26), 2026, 4, "USD")));
        when(forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(any(), any(), any(), any()))
                .thenReturn(false);

        AtomicInteger httpCalls = new AtomicInteger();
        String json = """
                {
                  "symbol": "AAPL",
                  "estimates": [
                    {
                      "date": "2028-09-30",
                      "horizon": "fiscal year",
                      "eps_estimate_average": "10.5980",
                      "eps_estimate_average_30_days_ago": "10.8420",
                      "eps_estimate_average_90_days_ago": "10.7218",
                      "revenue_estimate_average": "571042817750.00"
                    },
                    {
                      "date": "2027-09-30",
                      "horizon": "fiscal year",
                      "eps_estimate_average": "9.4294",
                      "eps_estimate_average_30_days_ago": "9.3000",
                      "eps_estimate_average_90_days_ago": "9.1000",
                      "revenue_estimate_average": "520000000000.00"
                    },
                    {
                      "date": "2027-03-31",
                      "horizon": "fiscal quarter",
                      "eps_estimate_average": "2.20",
                      "revenue_estimate_average": "110000000000.00"
                    }
                  ]
                }
                """;
        AnnualEarningsForecastSyncService service = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, quotas, clock, tx, new ObjectMapper(),
                symbol -> {
                    httpCalls.incrementAndGet();
                    currentClock.set(Instant.parse("2026-11-20T05:00:02Z"));
                    return json;
                },
                true, "test-key", 20, 7, 0L);

        assertThat(service.ensureFreshAnnualForecast("FIGI-AAPL")).isTrue();
        assertThat(httpCalls.get()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StockEarningsForecast>> captor = ArgumentCaptor.forClass(List.class);
        verify(forecasts).saveAllAndFlush(captor.capture());
        List<StockEarningsForecast> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getFiscalPeriod()).isEqualTo("FY2027");
        assertThat(saved.get(0).getPeriodEnd()).isNull();
        // 觀察日應與實際回應時間 observedAt（2026-11-20 NY）一致
        assertThat(saved.get(0).getObservationDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(saved.get(0).getEps()).isEqualByComparingTo("9.4294");
        assertThat(saved.get(0).getEps30dAgo()).isEqualByComparingTo("9.3000");
        assertThat(saved.get(0).getEps90dAgo()).isEqualByComparingTo("9.1000");
        assertThat(saved.get(1).getFiscalPeriod()).isEqualTo("FY2028");
        assertThat(saved.get(1).getPeriodEnd()).isNull();

        // 2) Wendy's 52/53 週財年（FY2022 2023-01-01 非月末、FY2023 2023-12-31 恰逢月末）：
        //    仍應辨識為週曆公司，AlphaVantage 模擬月末 2024-12-31 必須保留 periodEnd = null
        Stock wen = operatingStock("FIGI-WEN", "WEN", "Common Stock", "USD", "0000030697");
        AnnualEarningsForecastSyncService.ParseOutcome wenOutcome = service.parseAnnualEstimates(
                wen,
                """
                {"symbol":"WEN","estimates":[{"date":"2024-12-31","horizon":"fiscal year","eps_estimate_average":"1.00","revenue_estimate_average":"2200000000"}]}
                """,
                LocalDate.of(2024, 12, 30), Instant.parse("2024-12-30T15:00:00Z"),
                List.of(
                        period("FIGI-WEN", LocalDate.of(2023, 1, 1), 2022, 4, "USD"),
                        period("FIGI-WEN", LocalDate.of(2023, 12, 31), 2023, 4, "USD")
                ));
        assertThat(wenOutcome.rows()).singleElement().satisfies(r -> {
            assertThat(r.getFiscalPeriod()).isEqualTo("FY2024");
            assertThat(r.getPeriodEnd()).isNull();
        });

        // 3) 首次跨曆年之 53 週財年（FY2025 截於 2025-12-28，下一財年截於 2027-01-03 應標為 FY2026）
        Stock week53 = operatingStock("FIGI-W53", "W53", "Common Stock", "USD", "0000000053");
        AnnualEarningsForecastSyncService.ParseOutcome crossed = service.parseAnnualEstimates(
                week53,
                """
                {"symbol":"W53","estimates":[{"date":"2027-01-03","horizon":"fiscal year","eps_estimate_average":"4.20","revenue_estimate_average":"5000000000"}]}
                """,
                TODAY_NY, NOW,
                List.of(period("FIGI-W53", LocalDate.of(2025, 12, 28), 2025, 4, "USD")));
        assertThat(crossed.rows()).singleElement().satisfies(r -> {
            assertThat(r.getFiscalPeriod()).isEqualTo("FY2026");
            assertThat(r.getPeriodEnd()).isEqualTo(LocalDate.of(2027, 1, 3));
        });
    }

    @Test
    void persistsEmptyAttemptsAndCircuitBreakerAcrossRestartsStrictlyWithin20DailyLimit() {
        StockRepository stocks = mock(StockRepository.class);
        StockFinancialPeriodRepository periods = mock(StockFinancialPeriodRepository.class);
        FinancialReportRepository reports = mock(FinancialReportRepository.class);
        SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
        StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
        ExternalApiDailyQuotaRepository sharedQuotas = inMemoryQuotaRepo();
        DateTimeService clock = mock(DateTimeService.class);
        TransactionTemplate tx = mockTransactionTemplate();
        when(clock.now()).thenReturn(NOW);

        for (int i = 1; i <= 21; i++) {
            String f1 = "FIGI-R1-" + i;
            String f2 = "FIGI-R2-" + i;
            when(stocks.findById(f1)).thenReturn(Optional.of(operatingStock(f1, "R1S" + i, "Common Stock", "USD", "00010000" + String.format("%02d", i))));
            when(stocks.findById(f2)).thenReturn(Optional.of(operatingStock(f2, "R2S" + i, "Common Stock", "USD", "00020000" + String.format("%02d", i))));
        }
        when(reports.findLatestReportCurrencies(any(), any())).thenReturn(List.of("USD"));
        when(periods.findByFigiOrderByPeriodEndAsc(any())).thenReturn(List.of());
        when(forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(any(), any(), any(), any())).thenReturn(false);

        AtomicInteger totalHttpCalls = new AtomicInteger();
        Function<String, String> emptyFetcher = symbol -> {
            totalHttpCalls.incrementAndGet();
            return "{\"symbol\":\"" + symbol + "\",\"estimates\":[]}";
        };

        // 第一輪實例：21 個合格標的皆回空 estimates -> 發出 20 次後達上限停止
        AnnualEarningsForecastSyncService instance1 = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, sharedQuotas, clock, tx, new ObjectMapper(),
                emptyFetcher, true, "test-key", 20, 7, 0L);
        for (int i = 1; i <= 21; i++) {
            instance1.ensureFreshAnnualForecast("FIGI-R1-" + i);
        }
        assertThat(totalHttpCalls.get()).isEqualTo(20);

        // 模擬同日服務重啟（全新實例、清空所有記憶體狀態）：再跑 21 個新標的 -> 總計仍停在 20 次（絕不變成 40 次）
        AnnualEarningsForecastSyncService instance2 = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, sharedQuotas, clock, tx, new ObjectMapper(),
                emptyFetcher, true, "test-key", 20, 7, 0L);
        for (int i = 1; i <= 21; i++) {
            instance2.ensureFreshAnnualForecast("FIGI-R2-" + i);
        }
        assertThat(totalHttpCalls.get()).isEqualTo(20);

        // HTTP 403 觸發之熔斷亦跨重啟持久化，不重試也不再發 HTTP
        ExternalApiDailyQuotaRepository forbiddenQuotas = inMemoryQuotaRepo();
        AtomicInteger forbiddenCalls = new AtomicInteger();
        Function<String, String> forbiddenFetcher = symbol -> {
            forbiddenCalls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.FORBIDDEN);
        };
        AnnualEarningsForecastSyncService f1 = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, forbiddenQuotas, clock, tx, new ObjectMapper(),
                forbiddenFetcher, true, "test-key", 20, 7, 0L);
        assertThat(f1.ensureFreshAnnualForecast("FIGI-R1-1")).isFalse();
        AnnualEarningsForecastSyncService f2 = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, forbiddenQuotas, clock, tx, new ObjectMapper(),
                forbiddenFetcher, true, "test-key", 20, 7, 0L);
        assertThat(f2.ensureFreshAnnualForecast("FIGI-R2-1")).isFalse();
        assertThat(forbiddenCalls.get()).isEqualTo(1);
    }

    @Test
    void enforcesAuthoritativeUsdEvidenceReenablementAndGracefulDbFailure() {
        StockRepository stocks = mock(StockRepository.class);
        StockFinancialPeriodRepository periods = mock(StockFinancialPeriodRepository.class);
        FinancialReportRepository reports = mock(FinancialReportRepository.class);
        SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
        StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
        ExternalApiDailyQuotaRepository quotas = inMemoryQuotaRepo();
        DateTimeService clock = mock(DateTimeService.class);
        TransactionTemplate tx = mockTransactionTemplate();
        when(clock.now()).thenReturn(NOW);

        Stock unverifiedUsd = operatingStock("FIGI-NOEVID", "FGN", "Common Stock", "USD", "0000999999");
        when(stocks.findById("FIGI-NOEVID")).thenReturn(Optional.of(unverifiedUsd));
        when(reports.findLatestReportCurrencies(eq("FIGI-NOEVID"), any())).thenReturn(List.of());
        when(secFacts.findMonetaryReportingUnitsByCik("0000999999")).thenReturn(Set.of());
        when(periods.findByFigiOrderByPeriodEndAsc("FIGI-NOEVID"))
                .thenReturn(List.of(period("FIGI-NOEVID", LocalDate.of(2026, 9, 30), 2026, 3, "USD")));

        Stock toggled = operatingStock("FIGI-TOG", "TOG", "Common Stock", "USD", "0000777777");
        toggled.setTracked(false);
        when(stocks.findById("FIGI-TOG")).thenReturn(Optional.of(toggled));
        when(reports.findLatestReportCurrencies(eq("FIGI-TOG"), any())).thenReturn(List.of());
        when(secFacts.findMonetaryReportingUnitsByCik("0000777777")).thenReturn(Set.of("USD"));
        when(periods.findByFigiOrderByPeriodEndAsc("FIGI-TOG")).thenReturn(List.of());
        when(forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(any(), any(), any(), any())).thenReturn(false);

        AtomicInteger calls = new AtomicInteger();
        AnnualEarningsForecastSyncService service = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, quotas, clock, tx, new ObjectMapper(),
                symbol -> {
                    calls.incrementAndGet();
                    return "{\"symbol\":\"TOG\",\"estimates\":[{\"date\":\"2027-12-31\",\"horizon\":\"fiscal year\",\"eps_estimate_average\":\"2.50\",\"revenue_estimate_average\":\"1000000000\"}]}";
                },
                true, "test-key", 20, 7, 0L);

        assertThat(service.ensureFreshAnnualForecast("FIGI-NOEVID")).isFalse();
        assertThat(service.ensureFreshAnnualForecast("FIGI-TOG")).isFalse();
        assertThat(calls.get()).isZero();

        toggled.setTracked(true);
        assertThat(service.ensureFreshAnnualForecast("FIGI-TOG")).isTrue();
        assertThat(calls.get()).isEqualTo(1);

        // DB 寫入異常時應優雅回傳 false 而不拋例外阻斷報告
        TransactionTemplate failSaveTx = mockTransactionTemplate();
        doThrow(new DataAccessResourceFailureException("db down")).when(failSaveTx).executeWithoutResult(any());
        AnnualEarningsForecastSyncService dbFailService = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, inMemoryQuotaRepo(), clock, failSaveTx, new ObjectMapper(),
                symbol -> "{\"symbol\":\"TOG\",\"estimates\":[{\"date\":\"2027-12-31\",\"horizon\":\"fiscal year\",\"eps_estimate_average\":\"2.50\"}]}",
                true, "test-key", 20, 7, 0L);
        assertThat(dbFailService.ensureFreshAnnualForecast("FIGI-TOG")).isFalse();
        verify(forecasts, never()).saveAll(any());
    }

    @Test
    void deductsQuotaUsingPostPacingMidnightDateAndHonorsExhaustionSetDuringPacing() {
        StockRepository stocks = mock(StockRepository.class);
        StockFinancialPeriodRepository periods = mock(StockFinancialPeriodRepository.class);
        FinancialReportRepository reports = mock(FinancialReportRepository.class);
        SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
        StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
        ExternalApiDailyQuotaRepository quotas = inMemoryQuotaRepo();
        DateTimeService clock = mock(DateTimeService.class);
        TransactionTemplate tx = mockTransactionTemplate();

        for (int i = 1; i <= 4; i++) {
            String f = "FIGI-M" + i;
            when(stocks.findById(f)).thenReturn(Optional.of(operatingStock(f, "M" + i, "Common Stock", "USD", "000300000" + i)));
        }
        when(reports.findLatestReportCurrencies(any(), any())).thenReturn(List.of("USD"));
        when(periods.findByFigiOrderByPeriodEndAsc(any())).thenReturn(List.of());
        when(forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(any(), any(), any(), any())).thenReturn(false);

        // 模擬最後一次快取 exists 查詢期間跨過紐約午夜：改以扣額當下時間決定配額日，並在 HTTP 替身按實際發送日計數，新日嚴格最多 2 次
        AtomicReference<Instant> midClock = new AtomicReference<>(Instant.parse("2026-11-20T04:59:59Z"));
        when(clock.now()).thenAnswer(inv -> midClock.get());
        AtomicInteger existsCount = new AtomicInteger();
        when(forecasts.existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (existsCount.incrementAndGet() == 3) {
                        midClock.set(Instant.parse("2026-11-20T05:00:01Z")); // 最後一次快取查詢期間跨入 11-20 NY
                    }
                    return false;
                });

        Map<LocalDate, AtomicInteger> sentByDispatchDay = new HashMap<>();
        AnnualEarningsForecastSyncService service = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, quotas, clock, tx, new ObjectMapper(),
                symbol -> {
                    LocalDate actualDay = clock.now().atZone(com.ykm.stock.analytics.service.market.MarketStatusService.NY_ZONE).toLocalDate();
                    sentByDispatchDay.computeIfAbsent(actualDay, d -> new AtomicInteger()).incrementAndGet();
                    return "{\"symbol\":\"" + symbol + "\",\"estimates\":[]}";
                },
                true, "test-key", 2, 7, 10L);

        service.ensureFreshAnnualForecast("FIGI-M1");
        service.ensureFreshAnnualForecast("FIGI-M2");
        service.ensureFreshAnnualForecast("FIGI-M3");
        assertThat(sentByDispatchDay.getOrDefault(LocalDate.of(2026, 11, 20), new AtomicInteger()).get()).isEqualTo(2);

        // 模擬扣額提交瞬間慢查詢（150ms）與節流中斷：兩次 HTTP 實際間距仍 >= minIntervalMs，且被中斷的執行緒不會扣額也不發送
        ExternalApiDailyQuotaRepository paceQuotas = inMemoryQuotaRepo();
        DateTimeService paceClock = mock(DateTimeService.class);
        when(paceClock.now()).thenReturn(NOW);
        TransactionTemplate slowCommitTx = mock(TransactionTemplate.class);
        AtomicInteger commitCount = new AtomicInteger();
        doAnswer(inv -> {
            if (commitCount.incrementAndGet() == 1) {
                Thread.sleep(120L);
            }
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(slowCommitTx).execute(any());
        List<Long> dispatchNanos = new java.util.ArrayList<>();
        AnnualEarningsForecastSyncService paceService = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, paceQuotas, paceClock, slowCommitTx, new ObjectMapper(),
                symbol -> {
                    dispatchNanos.add(System.nanoTime());
                    return "{\"symbol\":\"" + symbol + "\",\"estimates\":[]}";
                },
                true, "test-key", 20, 7, 150L);

        paceService.ensureFreshAnnualForecast("FIGI-M1");
        paceService.ensureFreshAnnualForecast("FIGI-M2");
        assertThat(dispatchNanos).hasSize(2);
        long actualGapMs = (dispatchNanos.get(1) - dispatchNanos.get(0)) / 1_000_000L;
        assertThat(actualGapMs).isGreaterThanOrEqualTo(130L);

        // 模擬第二次節流等待時執行緒被中斷 -> 立即返回 false，不得扣額也不得發送 HTTP
        Thread.currentThread().interrupt();
        try {
            assertThat(paceService.ensureFreshAnnualForecast("FIGI-M3")).isFalse();
            assertThat(dispatchNanos).hasSize(2);
        } finally {
            Thread.interrupted();
        }

        // 模擬節流等待期間另一個請求已將當日標記熔斷 -> 醒來後不得發送 HTTP
        ExternalApiDailyQuotaRepository exhaustedQuotas = inMemoryQuotaRepo();
        DateTimeService fixedClock = mock(DateTimeService.class);
        AtomicInteger callsAfterMidPacingTrip = new AtomicInteger();
        AtomicInteger midClockStep = new AtomicInteger();
        when(fixedClock.now()).thenAnswer(inv -> {
            if (midClockStep.incrementAndGet() == 4) {
                exhaustedQuotas.markQuotaExhausted("ALPHAVANTAGE", TODAY_NY, 20, NOW);
            }
            return NOW;
        });
        AnnualEarningsForecastSyncService tripService = new AnnualEarningsForecastSyncService(
                stocks, periods, reports, secFacts, forecasts, exhaustedQuotas, fixedClock, tx, new ObjectMapper(),
                symbol -> {
                    callsAfterMidPacingTrip.incrementAndGet();
                    return "{\"symbol\":\"" + symbol + "\",\"estimates\":[]}";
                },
                true, "test-key", 20, 7, 10L);
        assertThat(tripService.ensureFreshAnnualForecast("FIGI-M4")).isFalse();
        assertThat(callsAfterMidPacingTrip.get()).isZero();
    }

    private static ExternalApiDailyQuotaRepository inMemoryQuotaRepo() {
        Map<ExternalApiDailyQuotaId, ExternalApiDailyQuota> store = new HashMap<>();
        ExternalApiDailyQuotaRepository repo = mock(ExternalApiDailyQuotaRepository.class);
        when(repo.tryIncrementDailyQuota(any(), any(), any(Integer.class), any())).thenAnswer(inv -> {
            String provider = inv.getArgument(0);
            LocalDate day = inv.getArgument(1);
            int limit = inv.getArgument(2);
            Instant now = inv.getArgument(3);
            ExternalApiDailyQuotaId id = new ExternalApiDailyQuotaId(provider, day);
            synchronized (store) {
                ExternalApiDailyQuota row = store.get(id);
                if (row == null) {
                    if (limit <= 0) return 0;
                    row = new ExternalApiDailyQuota();
                    row.setProvider(provider);
                    row.setQuotaDate(day);
                    row.setAttemptsUsed(1);
                    row.setQuotaExhausted(false);
                    row.setUpdatedAt(now);
                    store.put(id, row);
                    return 1;
                }
                if (row.isQuotaExhausted() || row.getAttemptsUsed() >= limit) {
                    return 0;
                }
                row.setAttemptsUsed(row.getAttemptsUsed() + 1);
                row.setUpdatedAt(now);
                return 1;
            }
        });
        when(repo.markQuotaExhausted(any(), any(), any(Integer.class), any())).thenAnswer(inv -> {
            String provider = inv.getArgument(0);
            LocalDate day = inv.getArgument(1);
            int limit = inv.getArgument(2);
            Instant now = inv.getArgument(3);
            ExternalApiDailyQuotaId id = new ExternalApiDailyQuotaId(provider, day);
            synchronized (store) {
                ExternalApiDailyQuota row = store.get(id);
                if (row == null) {
                    row = new ExternalApiDailyQuota();
                    row.setProvider(provider);
                    row.setQuotaDate(day);
                    row.setAttemptsUsed(limit);
                }
                row.setQuotaExhausted(true);
                row.setUpdatedAt(now);
                store.put(id, row);
                return 1;
            }
        });
        return repo;
    }

    private static Stock operatingStock(String figi, String symbol, String type, String currency, String cik) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setStockType(type);
        s.setCurrency(currency);
        s.setCik(cik);
        s.setActiveStatus(ActiveStatus.ACTIVE);
        s.setTracked(true);
        return s;
    }

    private static StockFinancialPeriod period(String figi, LocalDate end, int fy, int fq, String currency) {
        StockFinancialPeriod p = new StockFinancialPeriod();
        p.setFigi(figi);
        p.setPeriodEnd(end);
        p.setFiscalYear(fy);
        p.setFiscalQuarter(fq);
        p.setReportingCurrency(currency);
        return p;
    }

    private static TransactionTemplate mockTransactionTemplate() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(tx).executeWithoutResult(any());
        when(tx.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return tx;
    }
}
