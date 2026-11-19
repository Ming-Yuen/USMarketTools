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
}
