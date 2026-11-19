package com.ykm.stock.analytics.service;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.config.AppProperties;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService.SignalTrackingStatsSummary;
import com.ykm.stock.analytics.service.market.MarketEventService;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.market.TreasuryService;
import com.ykm.stock.analytics.service.stock.StockIndicatorService;
import com.ykm.stock.analytics.service.stock.StockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗收首頁整合 Batch 03 之關鍵邊界：當最新交易日為零入選日（如 BEAR 逆風全排除）時，
 * 必須以 DailyOpportunityRadarRun 最新日為準回傳空名單，絕不回退復活前一日名單。
 */
class OverviewServiceBatch03Tests {
    @Test
    void dashboardReturnsEmptyRadarForLatestZeroHitRunInsteadOfRevivingPreviousDay() {
        LocalDate latestDay = LocalDate.of(2026, 11, 18);
        Instant now = Instant.parse("2026-11-18T22:00:00Z");

        StockService stockService = mock(StockService.class);
        StockIndicatorService indicatorService = mock(StockIndicatorService.class);
        QuoteNormalizationService quoteNorm = mock(QuoteNormalizationService.class);
        TreasuryService treasury = mock(TreasuryService.class);
        MarketSentimentService sentiment = mock(MarketSentimentService.class);
        MarketEventService events = mock(MarketEventService.class);
        MarketContextService marketContext = mock(MarketContextService.class);
        MarketStatusService calendar = new MarketStatusService(null);
        DateTimeService clock = mock(DateTimeService.class);
        StockRepository stockRepo = mock(StockRepository.class);
        StockQuoteRepository quoteRepo = mock(StockQuoteRepository.class);
        StockSplitRepository splitRepo = mock(StockSplitRepository.class);
        StockStatusRepository statusRepo = mock(StockStatusRepository.class);
        SectorMoneyFlowDailyRepository flowRepo = mock(SectorMoneyFlowDailyRepository.class);
        DailyOpportunityRadarRepository radarRepo = mock(DailyOpportunityRadarRepository.class);
        DailyOpportunityRadarRunRepository radarRunRepo = mock(DailyOpportunityRadarRunRepository.class);
        SignalTrackingService signalTracking = mock(SignalTrackingService.class);
        AppProperties props = new AppProperties();

        when(clock.now()).thenReturn(now);
        when(stockService.findMaxQuoteDate()).thenReturn(latestDay);
        when(sentiment.getLatestSnapshot()).thenReturn(Optional.empty());
        when(events.getUpcomingEvents()).thenReturn(List.of());
        when(stockRepo.findByActiveStatusAndSymbolIn(any(), any())).thenReturn(List.of());
        when(flowRepo.findLatestTradeDate()).thenReturn(Optional.empty());
        when(signalTracking.buildStatsSummary()).thenReturn(
                new SignalTrackingStatsSummary(0, 0, 0, null, null, null, List.of(), List.of(), List.of(), List.of()));

        MarketSnapshotDto snap = new MarketSnapshotDto(
                MarketRegime.BEAR, MetricResult.available(new BigDecimal("20")), MetricResult.available(new BigDecimal("20")),
                MetricResult.available(new BigDecimal("20")), latestDay, now, List.of(), List.of());
        when(marketContext.preloadMarketData(eq(latestDay), any())).thenReturn(new PreloadedMarketData(
                latestDay, now, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(), MetricResult.available(snap), MetricResult.available(new BigDecimal("29.5"))));

        DailyOpportunityRadarRun zeroRun = new DailyOpportunityRadarRun();
        zeroRun.setRadarDate(latestDay);
        zeroRun.setMarketRegime("BEAR");
        zeroRun.setHeadwindActive(true);
        zeroRun.setCandidateCount(12);
        zeroRun.setValueCount(0);
        zeroRun.setStarterCount(0);
        zeroRun.setObservationCutoff(now);
        zeroRun.setPublishedAt(now);
        when(radarRunRepo.findTopByOrderByRadarDateDesc()).thenReturn(Optional.of(zeroRun));
        when(radarRepo.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(latestDay)).thenReturn(List.of());

        OverviewService service = new OverviewService(
                stockService, indicatorService, quoteNorm, treasury, sentiment, events, marketContext, calendar, clock,
                stockRepo, quoteRepo, splitRepo, statusRepo, flowRepo, radarRepo, radarRunRepo, signalTracking, props);

        DashboardResponse response = service.getDashboard();
        assertThat(response.getOpportunityRadar()).isNotNull();
        assertThat(response.getOpportunityRadar().getRadarDate()).isEqualTo("2026-11-18");
        assertThat(response.getOpportunityRadar().getMarketRegime()).isEqualTo("BEAR");
        assertThat(response.getOpportunityRadar().isHeadwindActive()).isTrue();
        assertThat(response.getOpportunityRadar().getValueCount()).isZero();
        assertThat(response.getOpportunityRadar().getStarterCount()).isZero();
        assertThat(response.getOpportunityRadar().getValueOpportunities()).isEmpty();
        assertThat(response.getOpportunityRadar().getStarterOpportunities()).isEmpty();
        verify(radarRepo).findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(latestDay);
    }

    @Test
    void dashboardCapsBenchmarkAndSectorEtfsToStoredQuoteDatesDuringBatchSync() {
        // 全市場 max(quote_date) 已推進到 T (11-18)，但 SPY/QQQ 與 XLE 仍在 T-1 (11-17)，XLK 已到 T (11-18)：
        // 首頁大盤應改以共同已入庫日 11-17 預載避免 UNAVAILABLE；XLE 應按 11-17 正規化保留不消失且有同日超額報酬；
        // XLK 按 11-18 正規化保留自身報酬，因 SPY 只到 11-17，XLK 的超額報酬安全為 NULL。
        LocalDate dayT = LocalDate.of(2026, 11, 18);
        LocalDate dayTMinus1 = LocalDate.of(2026, 11, 17);
        Instant now = Instant.parse("2026-11-18T22:00:00Z");

        StockService stockService = mock(StockService.class);
        StockIndicatorService indicatorService = mock(StockIndicatorService.class);
        QuoteNormalizationService quoteNorm = mock(QuoteNormalizationService.class);
        TreasuryService treasury = mock(TreasuryService.class);
        MarketSentimentService sentiment = mock(MarketSentimentService.class);
        MarketEventService events = mock(MarketEventService.class);
        MarketContextService marketContext = mock(MarketContextService.class);
        MarketStatusService calendar = new MarketStatusService(null);
        DateTimeService clock = mock(DateTimeService.class);
        StockRepository stockRepo = mock(StockRepository.class);
        StockQuoteRepository quoteRepo = mock(StockQuoteRepository.class);
        StockSplitRepository splitRepo = mock(StockSplitRepository.class);
        StockStatusRepository statusRepo = mock(StockStatusRepository.class);
        SectorMoneyFlowDailyRepository flowRepo = mock(SectorMoneyFlowDailyRepository.class);
        DailyOpportunityRadarRepository radarRepo = mock(DailyOpportunityRadarRepository.class);
        DailyOpportunityRadarRunRepository radarRunRepo = mock(DailyOpportunityRadarRunRepository.class);
        SignalTrackingService signalTracking = mock(SignalTrackingService.class);
        AppProperties props = new AppProperties();

        when(clock.now()).thenReturn(now);
        when(stockService.findMaxQuoteDate()).thenReturn(dayT);
        when(sentiment.getLatestSnapshot()).thenReturn(Optional.empty());
        when(events.getUpcomingEvents()).thenReturn(List.of());
        when(flowRepo.findLatestTradeDate()).thenReturn(Optional.empty());
        when(radarRunRepo.findTopByOrderByRadarDateDesc()).thenReturn(Optional.empty());
        when(signalTracking.buildStatsSummary()).thenReturn(
                new SignalTrackingStatsSummary(0, 0, 0, null, null, null, List.of(), List.of(), List.of(), List.of()));

        com.ykm.stock.analytics.model.entity.Stock spy = activeEtf("F_SPY", "SPY");
        com.ykm.stock.analytics.model.entity.Stock qqq = activeEtf("F_QQQ", "QQQ");
        com.ykm.stock.analytics.model.entity.Stock xlk = activeEtf("F_XLK", "XLK");
        com.ykm.stock.analytics.model.entity.Stock xle = activeEtf("F_XLE", "XLE");
        when(stockRepo.findByActiveStatusAndSymbolIn(eq(com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE), eq(List.of("SPY", "QQQ"))))
                .thenReturn(List.of(spy, qqq));
        when(stockRepo.findByActiveStatusAndSymbolIn(eq(com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE), eq(com.ykm.stock.analytics.service.analysis.SecurityTypePolicy.SECTOR_ETF_SYMBOLS)))
                .thenReturn(List.of(xlk, xle));

        com.ykm.stock.analytics.model.entity.StockStatus spyStatus = etfStatus("F_SPY", dayTMinus1);
        com.ykm.stock.analytics.model.entity.StockStatus qqqStatus = etfStatus("F_QQQ", dayTMinus1);
        com.ykm.stock.analytics.model.entity.StockStatus xlkStatus = etfStatus("F_XLK", dayT);
        com.ykm.stock.analytics.model.entity.StockStatus xleStatus = etfStatus("F_XLE", dayTMinus1);
        when(statusRepo.findAllById(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            java.util.List<com.ykm.stock.analytics.model.entity.StockStatus> list = new java.util.ArrayList<>();
            if (ids.contains("F_SPY")) list.add(spyStatus);
            if (ids.contains("F_QQQ")) list.add(qqqStatus);
            if (ids.contains("F_XLK")) list.add(xlkStatus);
            if (ids.contains("F_XLE")) list.add(xleStatus);
            return list;
        });

        List<LocalDate> sessionsToT = calendar.getTradingDays(dayT.minusDays(120), dayT);
        List<LocalDate> sessionsToTMinus1 = sessionsToT.subList(0, sessionsToT.size() - 1);

        List<com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto> spyBars = buildBars(sessionsToTMinus1, new BigDecimal("500"), new BigDecimal("510"), now);
        com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto spySeries =
                new com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto(
                        "F_SPY", "DAILY", spyBars, null, null, MetricStatus.AVAILABLE, List.of(), null);
        MarketSnapshotDto snap = new MarketSnapshotDto(
                MarketRegime.RISK_ON, MetricResult.available(new BigDecimal("60")), MetricResult.available(new BigDecimal("60")),
                MetricResult.available(new BigDecimal("60")), dayTMinus1, now, List.of(), List.of());
        when(marketContext.preloadMarketData(eq(dayTMinus1), any())).thenReturn(new PreloadedMarketData(
                dayTMinus1, now, MetricResult.available(spySeries), MetricResult.available(spySeries),
                Map.of(), List.of(), MetricResult.available(snap), MetricResult.available(new BigDecimal("16.5"))));
        // 若以未就緒的 dayT 呼叫，大盤回傳 UNAVAILABLE（重現原缺陷）
        when(marketContext.preloadMarketData(eq(dayT), any())).thenReturn(new PreloadedMarketData(
                dayT, now, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(), MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of())));

        when(quoteRepo.findQuoteBarsOrdered(any(), any(), any())).thenReturn(List.of());
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(any())).thenReturn(List.of());

        List<com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto> xlkBarsT = buildBars(sessionsToT, new BigDecimal("200"), new BigDecimal("210"), now);
        List<com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto> xleBarsTMinus1 = buildBars(sessionsToTMinus1, new BigDecimal("90"), new BigDecimal("99"), now);
        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LocalDate through = inv.getArgument(2);
            com.ykm.stock.analytics.model.entity.Stock s = inv.getArgument(3);
            if ("F_XLK".equals(s.getFigi()) && dayT.equals(through)) {
                return MetricResult.available(new com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto(
                        "F_XLK", "DAILY", xlkBarsT, null, null, MetricStatus.AVAILABLE, List.of(), null));
            }
            if ("F_XLE".equals(s.getFigi()) && dayTMinus1.equals(through)) {
                return MetricResult.available(new com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto(
                        "F_XLE", "DAILY", xleBarsTMinus1, null, null, MetricStatus.AVAILABLE, List.of(), null));
            }
            // 超出該股已入庫水位時回傳缺棒（模擬真實 QuoteNormalizationService 行為）
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        });

        OverviewService service = new OverviewService(
                stockService, indicatorService, quoteNorm, treasury, sentiment, events, marketContext, calendar, clock,
                stockRepo, quoteRepo, splitRepo, statusRepo, flowRepo, radarRepo, radarRunRepo, signalTracking, props);

        DashboardResponse response = service.getDashboard();
        assertThat(response.getMarketRegime().getRegime()).isEqualTo("RISK_ON");
        assertThat(response.getMarketRegime().getAsOfDate()).isEqualTo("2026-11-17");
        assertThat(response.getSectorEtfs()).hasSize(2);

        DashboardResponse.SectorEtfRow xleRow = response.getSectorEtfs().stream()
                .filter(r -> "XLE".equals(r.getSymbol())).findFirst().orElseThrow();
        DashboardResponse.SectorEtfRow xlkRow = response.getSectorEtfs().stream()
                .filter(r -> "XLK".equals(r.getSymbol())).findFirst().orElseThrow();
        assertThat(xleRow.getTradeDate()).isEqualTo("2026-11-17");
        assertThat(xleRow.getExcessVsSpy20d()).isNotNull();
        assertThat(xlkRow.getTradeDate()).isEqualTo("2026-11-18");
        assertThat(xlkRow.getReturn20d()).isNotNull();
        assertThat(xlkRow.getExcessVsSpy20d()).isNull();
    }

    private com.ykm.stock.analytics.model.entity.Stock activeEtf(String figi, String symbol) {
        com.ykm.stock.analytics.model.entity.Stock s = new com.ykm.stock.analytics.model.entity.Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setCurrency("USD");
        s.setStockType("ETP");
        s.setActiveStatus(com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE);
        s.setTracked(true);
        return s;
    }

    private com.ykm.stock.analytics.model.entity.StockStatus etfStatus(String figi, LocalDate quoteDate) {
        com.ykm.stock.analytics.model.entity.StockStatus st = new com.ykm.stock.analytics.model.entity.StockStatus();
        st.setFigi(figi);
        st.setQuoteDate(quoteDate);
        st.setSplitFromDate(LocalDate.of(2020, 1, 1));
        st.setSplitDate(quoteDate);
        return st;
    }

    private List<com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto> buildBars(
            List<LocalDate> sessions, BigDecimal baseClose, BigDecimal lastClose, Instant now) {
        List<com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto> bars = new java.util.ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            BigDecimal c = i == sessions.size() - 1 ? lastClose : baseClose;
            bars.add(new com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto(
                    sessions.get(i), c, c, c, c, MetricResult.available(new BigDecimal("1000000")),
                    now, now, now, now, null, "B"));
        }
        return bars;
    }
}
