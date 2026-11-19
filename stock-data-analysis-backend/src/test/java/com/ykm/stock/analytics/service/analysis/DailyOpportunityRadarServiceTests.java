package com.ykm.stock.analytics.service.analysis;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MomentumAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MomentumVolumeDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceBasisDescriptor;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRelation;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RiskRewardDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SetupType;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StopRuleDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StructureTrendDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetAction;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TargetDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TrendState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 驗收每日雙軌機會雷達兩階段掃描、BEAR/PANIC 逆風自動收緊、Batch 02 強勢股高成長豁免銜接、
 * 11 大板塊 ETF 納入基準清單，以及純唯讀 PostgreSQL 快取（絕不呼叫外部 API）。
 */
class DailyOpportunityRadarServiceTests {
    private static final LocalDate RADAR_DATE = LocalDate.of(2026, 11, 17);
    private static final Instant NOW = Instant.parse("2026-11-17T22:00:00Z");

    private final StockValuationLatestRepository latestValuations = mock(StockValuationLatestRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockFinancialPeriodRepository financialPeriods = mock(StockFinancialPeriodRepository.class);
    private final FinancialReportRepository financialReports = mock(FinancialReportRepository.class);
    private final StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
    private final MarketEventRepository marketEvents = mock(MarketEventRepository.class);
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final SectorMoneyFlowDailyRepository sectorMoneyFlows = mock(SectorMoneyFlowDailyRepository.class);
    private final DailyOpportunityRadarRepository radarRepository = mock(DailyOpportunityRadarRepository.class);
    private final DailyOpportunityRadarRunRepository radarRunRepository = mock(DailyOpportunityRadarRunRepository.class);
    private final QuoteNormalizationService quoteNormalization = mock(QuoteNormalizationService.class);
    private final PriceStructureService priceStructures = mock(PriceStructureService.class);
    private final MarketContextService marketContextService = mock(MarketContextService.class);
    private final SupportResistanceService supportResistanceService = mock(SupportResistanceService.class);
    private final TradePlanService tradePlanService = mock(TradePlanService.class);
    private final SignalTrackingService signalTrackingService = mock(SignalTrackingService.class);
    private final MarketStatusService calendar = new MarketStatusService(null);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private DailyOpportunityRadarService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        service = new DailyOpportunityRadarService(
                latestValuations, quotes, splits, statuses, financialPeriods, financialReports,
                forecasts, marketEvents, industries, sectorMoneyFlows, radarRepository, radarRunRepository,
                quoteNormalization, priceStructures, marketContextService, supportResistanceService,
                tradePlanService, signalTrackingService, calendar, clock, tx, entityManager);
        when(clock.now()).thenReturn(NOW);
        when(radarRunRepository.existsById(any())).thenReturn(false);
        StockIndustry ind = new StockIndustry();
        ind.setCode("I41");
        ind.setIndustry("Semiconductors");
        when(industries.findAll()).thenReturn(List.of(ind));
        SectorMoneyFlowDaily flow = new SectorMoneyFlowDaily();
        flow.setIndustryCode("I41");
        flow.setCmf20d(new BigDecimal("0.150000"));
        flow.setFlowState(SectorMoneyFlowDaily.FLOW_INFLOW);
        when(sectorMoneyFlows.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(RADAR_DATE)).thenReturn(List.of(flow));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        when(marketEvents.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of());
        when(financialReports.findKnownReportPeriodsForFigis(anyCollection(), any())).thenReturn(List.of());
        when(financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
    }

    @Test
    void benchmarkEtfSymbolsIncludeAllElevenSectorEtfs() {
        assertThat(SecurityTypePolicy.BENCHMARK_ETF_SYMBOLS)
                .contains("SPY", "QQQ", "XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLY", "XLP", "XLU", "XLB", "XLRE");
        assertThat(SecurityTypePolicy.SECTOR_ETF_SYMBOLS)
                .containsExactly("XLK", "XLF", "XLE", "XLV", "XLI", "XLC", "XLY", "XLP", "XLU", "XLB", "XLRE");
    }

    @Test
    void refreshDailyPublishesValueSupportAndHeadwindTightensCandidates() {
        // 1) RISK_ON 正常環境：收錄 ACCUMULATE_AT_SUPPORT 主清單
        stubPreloadedMarket(MarketRegime.RISK_ON);
        Stock stock = operatingStock("F_VAL", "VALU");
        StockValuationLatest latest = valuationSnapshot("F_VAL", "DEEP_UNDERVALUED", "STRONG", "100", "110", "130", "150");
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{latest, stock}));
        when(latestValuations.findStarterRadarCandidates(eq(RADAR_DATE), any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());

        stubTechnicalStack("F_VAL", "100", "98", "99.5", "94", "125", MarketRegime.RISK_ON, EntryPermission.ALLOW, "UPTREND", null);
        when(forecasts.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of());

        List<DailyOpportunityRadar> normalPublished = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(normalPublished).hasSize(1);
        DailyOpportunityRadar row = normalPublished.getFirst();
        assertThat(row.getTrackType()).isEqualTo(DailyOpportunityRadar.TRACK_VALUE_SUPPORT);
        assertThat(row.getStance()).isEqualTo("ACCUMULATE_AT_SUPPORT");
        assertThat(row.isHeadwindWatchOnly()).isFalse();
        assertThat(row.getProtectiveStop()).isEqualByComparingTo("94.0000");
        assertThat(row.getFirstTargetPrice()).isEqualByComparingTo("125.0000");
        assertThat(row.getSectorFlowState()).isEqualTo(SectorMoneyFlowDaily.FLOW_INFLOW);
        verify(signalTrackingService).recordSignalsInCurrentTx(eq(RADAR_DATE), eq(normalPublished), any());

        // 2) BEAR 大盤逆風環境：收緊初篩僅查 DEEP_UNDERVALUED + STRONG，不查次清單，且標記 headwindWatchOnly = true
        stubPreloadedMarket(MarketRegime.BEAR);
        stubTechnicalStack("F_VAL", "100", "98", "99.5", "94", "125", MarketRegime.BEAR, EntryPermission.BLOCK, "UPTREND", null);

        List<DailyOpportunityRadar> bearPublished = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(bearPublished).hasSize(1);
        assertThat(bearPublished.getFirst().getStance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");
        assertThat(bearPublished.getFirst().isHeadwindWatchOnly()).isTrue();
        verify(latestValuations).findValueRadarCandidates(
                eq(RADAR_DATE), any(), any(), anyCollection(), eq(List.of("DEEP_UNDERVALUED")), eq(List.of("STRONG")));
    }

    @Test
    void refreshDailyPublishesTrendPullbackStarterUsingReadOnlyAnnualForecastCache() {
        stubPreloadedMarket(MarketRegime.RISK_ON);
        Stock stock = operatingStock("F_GROW", "GROW");
        StockValuationLatest latest = valuationSnapshot("F_GROW", "FAIR_UPPER", "STRONG", "100", "85", "95", "105");
        latest.setPe(new BigDecimal("40"));
        latest.setRevenueGrowthYoy(new BigDecimal("0.28"));
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.of());
        when(latestValuations.findStarterRadarCandidates(eq(RADAR_DATE), any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{latest, stock}));

        // 既有 PostgreSQL ANNUAL 共識：FY1 EPS = 4.00 (Forward P/E = 25 <= 35)，TTM EPS = 1.50 (成長 > 100% 觸發高成長豁免)
        StockFinancialPeriod priorPeriod = new StockFinancialPeriod();
        priorPeriod.setFigi("F_GROW");
        priorPeriod.setReportingCurrency("USD");
        priorPeriod.setPeriodStart(LocalDate.of(2024, 10, 1));
        priorPeriod.setPeriodEnd(LocalDate.of(2025, 9, 30));
        priorPeriod.setRevenueTtm(new BigDecimal("1000"));
        priorPeriod.setRevenueTtmStatus("AVAILABLE");
        priorPeriod.setEpsTtm(new BigDecimal("1.00"));
        priorPeriod.setEpsTtmStatus("AVAILABLE");
        priorPeriod.setEpsShareBasisDate(LocalDate.of(2025, 9, 30));

        StockFinancialPeriod currentPeriod = new StockFinancialPeriod();
        currentPeriod.setFigi("F_GROW");
        currentPeriod.setReportingCurrency("USD");
        currentPeriod.setPeriodStart(LocalDate.of(2025, 10, 1));
        currentPeriod.setPeriodEnd(LocalDate.of(2026, 9, 30));
        currentPeriod.setRevenueTtm(new BigDecimal("1280"));
        currentPeriod.setRevenueTtmStatus("AVAILABLE");
        currentPeriod.setEpsTtm(new BigDecimal("1.50"));
        currentPeriod.setEpsTtmStatus("AVAILABLE");
        currentPeriod.setEpsShareBasisDate(LocalDate.of(2026, 9, 30));
        currentPeriod.setFreeCashFlowTtm(new BigDecimal("200"));
        currentPeriod.setFreeCashFlowTtmStatus("AVAILABLE");
        currentPeriod.setCommonEquity(new BigDecimal("800"));
        currentPeriod.setCommonEquityStatus("AVAILABLE");
        currentPeriod.setNetIncomeCommonTtm(new BigDecimal("150"));
        currentPeriod.setNetIncomeCommonTtmStatus("AVAILABLE");
        when(financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection()))
                .thenReturn(List.of(priorPeriod, currentPeriod));

        StockEarningsForecast annual = new StockEarningsForecast();
        annual.setFigi("F_GROW");
        annual.setSource("ALPHAVANTAGE");
        annual.setPeriodType("ANNUAL");
        annual.setFiscalPeriod("FY2027");
        annual.setPeriodEnd(LocalDate.of(2027, 9, 30));
        annual.setCurrency("USD");
        annual.setEps(new BigDecimal("4.00"));
        annual.setObservationDate(RADAR_DATE.minusDays(2));
        annual.setObservedAt(NOW.minusSeconds(3600));
        when(forecasts.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of(annual));

        stubTechnicalStack("F_GROW", "100", "90", "92", "88", "120", MarketRegime.RISK_ON, EntryPermission.ALLOW, "UPTREND", "99.0");

        List<DailyOpportunityRadar> published = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(published).hasSize(1);
        DailyOpportunityRadar starter = published.getFirst();
        assertThat(starter.getTrackType()).isEqualTo(DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER);
        assertThat(starter.getStance()).isEqualTo("TREND_PULLBACK_STARTER");
        assertThat(starter.getForwardGrowthBasis()).isEqualTo("HYPER_GROWTH_FORWARD_PE");
        assertThat(starter.getForwardPe()).isEqualByComparingTo("25.0000");
        assertThat(starter.getPullbackAnchor()).isEqualTo("SMA50");
        assertThat(starter.getPositionFraction()).isEqualTo("1/3");
        // 次清單不編造假目標價
        assertThat(starter.getFirstTargetPrice()).isNull();
    }

    @Test
    void computeSupportDistancePctDistinguishesInZoneAboveAndBelow() {
        LevelZoneDto zone = new LevelZoneDto("z1", AnalysisTimeframe.WEEKLY, new BigDecimal("95"), new BigDecimal("100"),
                ZoneRole.SUPPORT, new BigDecimal("60"), NOW, 2, NOW, ZoneState.ACTIVE, new BigDecimal("97.5"),
                new BigDecimal("2"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("60"), 1, null, null, List.of(), List.of(),
                2, 0, 1, BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, true, List.of(), null, List.of());

        assertThat(DailyOpportunityRadarService.computeSupportDistancePct(new BigDecimal("98"), zone))
                .isEqualByComparingTo("0.000000");
        assertThat(DailyOpportunityRadarService.computeSupportDistancePct(new BigDecimal("102"), zone))
                .isEqualByComparingTo("0.020000");
        assertThat(DailyOpportunityRadarService.computeSupportDistancePct(new BigDecimal("92"), zone))
                .isNegative();
    }

    @Test
    void refreshDailyFreezesPublishedSnapshotAndPersistsZeroHitRun() {
        // 1) 當日 0 入選仍須寫入 DailyOpportunityRadarRun 發布紀錄（避免首頁回退復活前一日名單）
        stubPreloadedMarket(MarketRegime.BEAR);
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.of());
        List<DailyOpportunityRadarRun> persistedRuns = new ArrayList<>();
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof DailyOpportunityRadarRun run) {
                persistedRuns.add(run);
            }
            return null;
        }).when(entityManager).persist(any());

        List<DailyOpportunityRadar> zeroHit = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(zeroHit).isEmpty();
        assertThat(persistedRuns).hasSize(1);
        assertThat(persistedRuns.getFirst().getRadarDate()).isEqualTo(RADAR_DATE);
        assertThat(persistedRuns.getFirst().getMarketRegime()).isEqualTo("BEAR");
        assertThat(persistedRuns.getFirst().isHeadwindActive()).isTrue();
        assertThat(persistedRuns.getFirst().getValueCount()).isZero();
        assertThat(persistedRuns.getFirst().getStarterCount()).isZero();

        // 2) 已存在發布紀錄時，同日重跑直接沿用已凍結快照，不重刪也不覆寫
        DailyOpportunityRadar frozen = new DailyOpportunityRadar();
        frozen.setRadarDate(RADAR_DATE);
        frozen.setFigi("F_FROZEN");
        frozen.setSymbol("FRZN");
        when(radarRunRepository.existsById(RADAR_DATE)).thenReturn(true);
        when(radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(RADAR_DATE))
                .thenReturn(List.of(frozen));

        List<DailyOpportunityRadar> rerun = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(rerun).containsExactly(frozen);
        verify(radarRepository, never()).deleteByRadarDate(RADAR_DATE.plusDays(1));
    }

    @Test
    void refreshDailyDefersWithoutFreezingWhenMarketContextNotReadyForRadarDate() {
        // 當 SPY/QQQ 尚未同步至 RADAR_DATE（dataDate 落後一天）時，絕不得寫入 DailyOpportunityRadarRun 提前凍結空清單
        MarketSnapshotDto laggingSnap = new MarketSnapshotDto(
                MarketRegime.RISK_ON, MetricResult.available(new BigDecimal("50")), MetricResult.available(new BigDecimal("50")),
                MetricResult.available(new BigDecimal("50")), RADAR_DATE.minusDays(1), NOW, List.of(), List.of());
        PreloadedMarketData laggingPreload = new PreloadedMarketData(
                RADAR_DATE, NOW, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(), MetricResult.available(laggingSnap), MetricResult.available(new BigDecimal("18")));
        when(marketContextService.preloadMarketData(eq(RADAR_DATE), any())).thenReturn(laggingPreload);

        List<DailyOpportunityRadar> deferred = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(deferred).isEmpty();
        verify(entityManager, never()).persist(any(DailyOpportunityRadarRun.class));
        verify(signalTrackingService, never()).recordSignalsInCurrentTx(any(), any(), any());
    }

    @Test
    void refreshDailyLoadsFull175DayForecastWindowToCatch90dDownwardRevisionTrap() {
        stubPreloadedMarket(MarketRegime.RISK_ON);
        Stock stock = operatingStock("F_TRAP", "TRAP");
        StockValuationLatest latest = valuationSnapshot("F_TRAP", "DEEP_UNDERVALUED", "STRONG", "100", "110", "130", "150");
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{latest, stock}));
        when(latestValuations.findStarterRadarCandidates(eq(RADAR_DATE), any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        stubTechnicalStack("F_TRAP", "100", "98", "99.5", "94", "125", MarketRegime.RISK_ON, EntryPermission.ALLOW, "UPTREND", null);

        // 最新預期在 D-40（合法 <= 45 天內），90D 基期在 D-150（距 D-40 為 110 天，落於 [75,120]），EPS 下修 -20% -> 必須攔截價值陷阱
        StockEarningsForecast baseline = new StockEarningsForecast();
        baseline.setFigi("F_TRAP");
        baseline.setSource("ALPHAVANTAGE");
        baseline.setPeriodType("ANNUAL");
        baseline.setFiscalPeriod("FY2027");
        baseline.setPeriodEnd(LocalDate.of(2027, 9, 30));
        baseline.setCurrency("USD");
        baseline.setEps(new BigDecimal("5.00"));
        baseline.setObservationDate(RADAR_DATE.minusDays(150));
        baseline.setObservedAt(NOW.minusSeconds(150L * 86400L));

        StockEarningsForecast current = new StockEarningsForecast();
        current.setFigi("F_TRAP");
        current.setSource("ALPHAVANTAGE");
        current.setPeriodType("ANNUAL");
        current.setFiscalPeriod("FY2027");
        current.setPeriodEnd(LocalDate.of(2027, 9, 30));
        current.setCurrency("USD");
        current.setEps(new BigDecimal("4.00"));
        current.setObservationDate(RADAR_DATE.minusDays(40));
        current.setObservedAt(NOW.minusSeconds(40L * 86400L));

        when(forecasts.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenAnswer(inv -> {
            LocalDate fromDate = inv.getArgument(1);
            // 驗證查詢下界至少涵蓋 D-165（實際為 D-175），否則 D-150 基期會被截斷
            assertThat(fromDate).isBeforeOrEqualTo(RADAR_DATE.minusDays(165));
            return List.of(baseline, current);
        });

        List<DailyOpportunityRadar> published = service.refreshDaily(RADAR_DATE, NOW);
        // 觸發 90D 下修 -20% <= -10% 價值陷阱攔截 -> 降為 WATCH_VALUE_TRAP，不得收錄進主清單
        assertThat(published).isEmpty();
    }

    @Test
    void refreshDailyExcludesAccumulateAtSupportWhenCloseFarAboveSupportZone() {
        // 低估股收盤 100，最近有效週線支撐區為 78–80（距支撐上緣 +25% > 3%）-> 不得放行進主清單，也不得入帳訊號
        stubPreloadedMarket(MarketRegime.RISK_ON);
        Stock stock = operatingStock("F_FAR", "FARR");
        StockValuationLatest latest = valuationSnapshot("F_FAR", "DEEP_UNDERVALUED", "STRONG", "100", "110", "130", "150");
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{latest, stock}));
        when(latestValuations.findStarterRadarCandidates(eq(RADAR_DATE), any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(forecasts.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of());
        stubTechnicalStack("F_FAR", "100", "78", "80", "75", "125", MarketRegime.RISK_ON, EntryPermission.ALLOW, "UPTREND", null);

        List<DailyOpportunityRadar> published = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(published).isEmpty();
    }

    @Test
    void refreshDailyComputesFirstTargetAndNetRrAgainstEvalPriceWhenSlightlyAboveSupport() {
        // 1) 現價 102 距支撐 98–100 為 +2%（<= 3%），但第一目標 T1=101.90 <= 102（已頂入第一道阻力前置緩衝區）、T2=120.00：
        //    不得跳過 T1 改取 T2 充當第一目標價，firstTargetPrice 與 netRiskReward 必須同步為 NULL
        stubPreloadedMarket(MarketRegime.RISK_ON);
        Stock stock = operatingStock("F_NEAR", "NEAR");
        StockValuationLatest latest = valuationSnapshot("F_NEAR", "DEEP_UNDERVALUED", "STRONG", "102", "110", "130", "150");
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{latest, stock}));
        when(latestValuations.findStarterRadarCandidates(eq(RADAR_DATE), any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(forecasts.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of());
        stubTechnicalStack("F_NEAR", "102", "98", "100", "94", "101.90", MarketRegime.RISK_ON, EntryPermission.ALLOW, "UPTREND", null);

        TradePlanDto blockedT1Plan = new TradePlanDto("p-near-blocked", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, null, null,
                new StopRuleDto(
                        new BigDecimal("94"), new BigDecimal("94"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("2"), AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), List.of()),
                List.of(
                        new TargetDto(new BigDecimal("101.90"), new BigDecimal("0.5"), TargetAction.PARTIAL_SELL, "res-1", "RESISTANCE", List.of()),
                        new TargetDto(new BigDecimal("120.00"), new BigDecimal("0.5"), TargetAction.TAKE_PROFIT, "res-2", "RESISTANCE", List.of())),
                MetricResult.available(new RiskRewardDto(new BigDecimal("100"), new BigDecimal("94"), new BigDecimal("6"), new BigDecimal("10.95"),
                        new BigDecimal("1.82"), new BigDecimal("6.3"), new BigDecimal("10.5"), new BigDecimal("1.66"),
                        new BigDecimal("10.95"), new BigDecimal("6"))),
                TradePlanStatus.WAITING_TRIGGER, List.of(), "sup-w-1", 1, AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), null, List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(List.of(blockedT1Plan), new BigDecimal("102"), RADAR_DATE,
                        blockedT1Plan.riskReward(), List.of(), List.of(), true)));

        List<DailyOpportunityRadar> blockedPublished = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(blockedPublished).hasSize(1);
        assertThat(blockedPublished.getFirst().getFirstTargetPrice()).isNull();
        assertThat(blockedPublished.getFirst().getNetRiskReward()).isNull();

        // 2) 當 T1=120.00 > evalPrice=102 時：寫入 firstTargetPrice=120.0000，且 netRiskReward 按收盤價 102（而非支撐上緣 100）重算
        TradePlanDto validT1Plan = new TradePlanDto("p-near-valid", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, null, null,
                new StopRuleDto(
                        new BigDecimal("94"), new BigDecimal("94"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("2"), AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), List.of()),
                List.of(new TargetDto(new BigDecimal("120.00"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "res-2", "RESISTANCE", List.of())),
                MetricResult.available(new RiskRewardDto(new BigDecimal("100"), new BigDecimal("94"), new BigDecimal("6"), new BigDecimal("20"),
                        new BigDecimal("3.33"), new BigDecimal("6.3"), new BigDecimal("19.5"), new BigDecimal("3.10"),
                        new BigDecimal("20"), new BigDecimal("6"))),
                TradePlanStatus.WAITING_TRIGGER, List.of(), "sup-w-1", 1, AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), null, List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(List.of(validT1Plan), new BigDecimal("102"), RADAR_DATE,
                        validT1Plan.riskReward(), List.of(), List.of(), true)));

        List<DailyOpportunityRadar> validPublished = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(validPublished).hasSize(1);
        assertThat(validPublished.getFirst().getFirstTargetPrice()).isEqualByComparingTo("120.0000");
        // 收盤 102 / 止損 94 / 目標 120：毛 R/R = 18/8 = 2.25，成本後淨 R/R 應接近 2.13（遠低於支撐上緣 100 起算的 3.10）
        assertThat(validPublished.getFirst().getNetRiskReward()).isBetween(new BigDecimal("2.05"), new BigDecimal("2.20"));
    }

    @Test
    void refreshDailyExcludesFairLowerWhenConfirmationPending() {
        // FAIR_LOWER + STRONG 距支撐 0%，但 matchedPlan 處於 STRUCTURE_CONFIRMATION_REQUIRED（confirmationPending = true）：
        // 不可比 DEEP_UNDERVALUED（退回 WAIT_FOR_STABILIZATION）更寬鬆而入選雷達主清單
        stubPreloadedMarket(MarketRegime.RISK_ON);
        Stock stock = operatingStock("F_FAIR", "FAIR");
        StockValuationLatest latest = valuationSnapshot("F_FAIR", "FAIR_LOWER", "STRONG", "100", "90", "110", "130");
        when(latestValuations.findValueRadarCandidates(eq(RADAR_DATE), any(), any(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{latest, stock}));
        when(latestValuations.findStarterRadarCandidates(eq(RADAR_DATE), any(), any(), any(), any(), anyCollection()))
                .thenReturn(List.of());
        when(forecasts.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of());
        stubTechnicalStack("F_FAIR", "100", "98", "100", "94", "125", MarketRegime.RISK_ON, EntryPermission.ALLOW, "UPTREND", null);

        TradePlanDto pendingPlan = new TradePlanDto("p-fair", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, null, null,
                new StopRuleDto(
                        new BigDecimal("94"), new BigDecimal("94"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("2"), AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), List.of()),
                List.of(new TargetDto(new BigDecimal("125"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "res-1", "RESISTANCE", List.of())),
                MetricResult.available(new RiskRewardDto(new BigDecimal("100"), new BigDecimal("94"), new BigDecimal("6"), new BigDecimal("25"),
                        new BigDecimal("4.16"), new BigDecimal("6.3"), new BigDecimal("24"), new BigDecimal("3.8"),
                        new BigDecimal("25"), new BigDecimal("6"))),
                TradePlanStatus.WAITING_TRIGGER,
                List.of(com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto.of(
                        "STRUCTURE_CONFIRMATION_REQUIRED", "TRADE_PLAN", "結構尚未確認")),
                "sup-w-1", 1, AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), null, List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(List.of(pendingPlan), new BigDecimal("100"), RADAR_DATE,
                        pendingPlan.riskReward(), List.of(), List.of(), true)));

        List<DailyOpportunityRadar> published = service.refreshDaily(RADAR_DATE, NOW);
        assertThat(published).isEmpty();
    }

    private void stubPreloadedMarket(MarketRegime regime) {
        MarketSnapshotDto snap = new MarketSnapshotDto(
                regime, MetricResult.available(new BigDecimal("50")), MetricResult.available(new BigDecimal("50")),
                MetricResult.available(new BigDecimal("50")), RADAR_DATE, NOW, List.of(), List.of());
        PreloadedMarketData preloaded = new PreloadedMarketData(
                RADAR_DATE, NOW, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(), MetricResult.available(snap), MetricResult.available(new BigDecimal("18")));
        when(marketContextService.preloadMarketData(eq(RADAR_DATE), any())).thenReturn(preloaded);
    }

    private void stubTechnicalStack(
            String figi, String closeStr, String supLowStr, String supHighStr, String stopStr, String targetStr,
            MarketRegime regime, EntryPermission permission, String weeklyTrend, String sma50Str) {
        BigDecimal close = new BigDecimal(closeStr);
        LocalDate prevDay = calendar.getTradingDays(RADAR_DATE.minusDays(10), RADAR_DATE.minusDays(1)).getLast();
        QuoteBarRow prevRaw = new QuoteBarRow(figi, prevDay, new BigDecimal("103"), new BigDecimal("106"),
                new BigDecimal("99"), new BigDecimal("100.5"), new BigDecimal("200000"), NOW);
        QuoteBarRow lastRaw = new QuoteBarRow(figi, RADAR_DATE, new BigDecimal("99.2"), new BigDecimal("100.6"),
                new BigDecimal("98.8"), close, new BigDecimal("120000"), NOW);
        when(quotes.findQuoteBarsOrdered(anyCollection(), any(), eq(RADAR_DATE))).thenReturn(List.of(prevRaw, lastRaw));

        StockStatus st = new StockStatus();
        st.setFigi(figi);
        st.setSplitFromDate(LocalDate.of(2015, 1, 1));
        st.setSplitDate(RADAR_DATE);
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(st));

        NormalizedBarDto b1 = new NormalizedBarDto(prevDay, prevRaw.openPrice(), prevRaw.highPrice(), prevRaw.lowPrice(), prevRaw.closePrice(),
                MetricResult.available(prevRaw.volume()), NOW.minusSeconds(86400), NOW.minusSeconds(60000), NOW.minusSeconds(60000), NOW, null, "B");
        NormalizedBarDto b2 = new NormalizedBarDto(RADAR_DATE, lastRaw.openPrice(), lastRaw.highPrice(), lastRaw.lowPrice(), lastRaw.closePrice(),
                MetricResult.available(lastRaw.volume()), NOW.minusSeconds(23400), NOW, NOW, NOW, null, "B");
        NormalizedPriceSeriesDto series = new NormalizedPriceSeriesDto(figi, "DAILY", List.of(b1, b2),
                new PriceBasisDescriptor("B", "STORED_QUOTES", null, RADAR_DATE, null, null, null, "USD", true, null),
                null, MetricStatus.AVAILABLE, List.of(), null);
        when(quoteNormalization.normalizeFromLoaded(any(), any(), eq(RADAR_DATE), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(series));

        StructureTrendDto trendDto = new StructureTrendDto(TrendState.valueOf(weeklyTrend), NOW, PriceRelation.HH, PriceRelation.HL, List.of(), NOW);
        TimeframeStructureDto dailyFrame = new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(b1, b2),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                MetricResult.available(trendDto), MetricResult.available(trendDto), List.of(), List.of());
        TimeframeStructureDto weeklyFrame = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, List.of(b1, b2),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                MetricResult.available(trendDto), MetricResult.available(trendDto), List.of(), List.of());
        PriceStructureDto psDto = new PriceStructureDto(figi, null, RADAR_DATE, List.of(dailyFrame, weeklyFrame), List.of(), List.of());
        when(priceStructures.analyze(any(), any())).thenReturn(MetricResult.available(psDto));

        Map<String, MetricResult<BigDecimal>> indicators = new java.util.LinkedHashMap<>();
        indicators.put("SMA200", MetricResult.available(new BigDecimal("90")));
        indicators.put("ATR14", MetricResult.available(new BigDecimal("3")));
        if (sma50Str != null) {
            indicators.put("SMA50", MetricResult.available(new BigDecimal(sma50Str)));
        }
        MomentumAnalysisDto momDetail = new MomentumAnalysisDto(indicators, MetricResult.available(BigDecimal.ZERO), List.of());
        VolumeAnalysisDto volDto = new VolumeAnalysisDto(
                MetricResult.available(new BigDecimal("0.75")),
                MetricResult.available(BigDecimal.ZERO),
                MetricResult.available(BigDecimal.ZERO),
                MetricResult.available(BigDecimal.ZERO),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                List.of(),
                List.of());
        MarketSnapshotDto mSnap = new MarketSnapshotDto(regime, MetricResult.available(new BigDecimal("50")),
                MetricResult.available(new BigDecimal("50")), MetricResult.available(new BigDecimal("50")),
                RADAR_DATE, NOW, List.of(), List.of());
        PlanConstraintDto constraint = new PlanConstraintDto(permission, List.of("TEST"), List.of());
        MarketContextDto mcDto = new MarketContextDto(
                new MomentumVolumeDto(null, null, null, null, MetricResult.available(new BigDecimal("0.75")), null, null, null, null),
                new RelativeStrengthDto("SPY", null, null, null, Map.of(), null, List.of()),
                MetricResult.available(mSnap), List.of(), constraint, List.of(), List.of(),
                momDetail, volDto, null, null, Map.of(), MetricResult.available(new BigDecimal("18")));
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(mcDto));

        LevelZoneDto sup = new LevelZoneDto("sup-w-1", AnalysisTimeframe.WEEKLY, new BigDecimal(supLowStr), new BigDecimal(supHighStr),
                ZoneRole.SUPPORT, new BigDecimal("65"), NOW.minusSeconds(864000), 3, NOW, ZoneState.ACTIVE,
                new BigDecimal(supLowStr), new BigDecimal("2"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("65"), 1, null, null,
                List.of(), List.of(), 3, 0, 2, BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, true, List.of(), null, List.of());
        SupportResistanceDto srDto = new SupportResistanceDto(List.of(sup), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        when(supportResistanceService.analyze(any(), any(), any(), any())).thenReturn(MetricResult.available(srDto));

        TradePlanDto plan = new TradePlanDto("p1", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, null, null,
                new StopRuleDto(
                        new BigDecimal(stopStr), new BigDecimal(stopStr), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("2"), AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), List.of()),
                List.of(new TargetDto(new BigDecimal(targetStr), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "res-1", "RESISTANCE", List.of())),
                MetricResult.available(new RiskRewardDto(close, new BigDecimal(stopStr), new BigDecimal("6"), new BigDecimal("25"),
                        new BigDecimal("4.16"), new BigDecimal("6.3"), new BigDecimal("24"), new BigDecimal("3.8"),
                        new BigDecimal("25"), new BigDecimal("6"))),
                TradePlanStatus.ACTIVE, List.of(), "sup-w-1", 1, AnalysisTimeframe.WEEKLY, NOW.minusSeconds(864000), null, List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(List.of(plan), close, RADAR_DATE, plan.riskReward(), List.of(), List.of(), true)));
    }

    private Stock operatingStock(String figi, String symbol) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setLongName(symbol + " Inc");
        s.setCurrency("USD");
        s.setStockType("Common Stock");
        s.setActiveStatus(ActiveStatus.ACTIVE);
        s.setTracked(true);
        return s;
    }

    private StockValuationLatest valuationSnapshot(String figi, String verdict, String backing, String close, String low, String mid, String high) {
        StockValuationLatest v = new StockValuationLatest();
        v.setFigi(figi);
        v.setValuationDate(RADAR_DATE);
        v.setCurrency("USD");
        v.setClosePrice(new BigDecimal(close));
        v.setIndustryCode("I41");
        v.setModelCode("NON_FINANCIAL_OPERATING");
        v.setVerdict(verdict);
        v.setFundamentalBacking(backing);
        v.setFairValueLow(new BigDecimal(low));
        v.setFairValueMid(new BigDecimal(mid));
        v.setFairValueHigh(new BigDecimal(high));
        v.setMarketCap(new BigDecimal("50000000000"));
        v.setMarketCapTier("MEGA_LARGE");
        return v;
    }
}
