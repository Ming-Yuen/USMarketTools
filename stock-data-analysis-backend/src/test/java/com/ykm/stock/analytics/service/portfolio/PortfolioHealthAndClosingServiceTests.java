package com.ykm.stock.analytics.service.portfolio;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AtrPointDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosis;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryMethod;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MomentumAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceAdjustment;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceBasisDescriptor;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRangeDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRelation;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RiskRewardDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SessionScope;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SetupType;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ShareUnit;
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
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.HoldingHealthItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioAlertDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.DailyClosingBriefing;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.InAppNotification;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.DailyClosingBriefingRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.InAppNotificationRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioHealthAndClosingServiceTests {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);
    private static final LocalDate PRIOR_DAY = LocalDate.of(2026, 11, 17);
    private static final Instant NOW = Instant.parse("2026-11-18T22:00:00Z");

    private final PortfolioItemRepository itemRepo = mock(PortfolioItemRepository.class);
    private final PortfolioRiskConfigService configService = mock(PortfolioRiskConfigService.class);
    private final DailyClosingBriefingRepository briefingRepo = mock(DailyClosingBriefingRepository.class);
    private final StockRepository stockRepo = mock(StockRepository.class);
    private final StockStatusRepository statusRepo = mock(StockStatusRepository.class);
    private final StockQuoteRepository quoteRepo = mock(StockQuoteRepository.class);
    private final StockSplitRepository splitRepo = mock(StockSplitRepository.class);
    private final StockValuationLatestRepository latestValRepo = mock(StockValuationLatestRepository.class);
    private final StockFinancialPeriodRepository periodRepo = mock(StockFinancialPeriodRepository.class);
    private final FinancialReportRepository reportRepo = mock(FinancialReportRepository.class);
    private final StockEarningsForecastRepository forecastRepo = mock(StockEarningsForecastRepository.class);
    private final MarketEventRepository eventRepo = mock(MarketEventRepository.class);
    private final StockIndustryHistoryRepository industryRepo = mock(StockIndustryHistoryRepository.class);
    private final QuoteNormalizationService quoteNorm = mock(QuoteNormalizationService.class);
    private final PriceStructureService priceStructures = mock(PriceStructureService.class);
    private final MarketContextService marketContextService = mock(MarketContextService.class);
    private final SupportResistanceService supportResistanceService = mock(SupportResistanceService.class);
    private final TradePlanService tradePlanService = mock(TradePlanService.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final InAppNotificationRepository notifRepo = mock(InAppNotificationRepository.class);
    private final DailyOpportunityRadarRunRepository radarRunRepo = mock(DailyOpportunityRadarRunRepository.class);
    private final DailyOpportunityRadarRepository radarRepo = mock(DailyOpportunityRadarRepository.class);
    private final SectorMoneyFlowDailyRepository sectorFlowRepo = mock(SectorMoneyFlowDailyRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private final PositionSizingCalculator sizingCalculator = new PositionSizingCalculator();
    private final PortfolioSplitAligner splitAligner = new PortfolioSplitAligner();

    private PortfolioHealthCheckService healthService;
    private PortfolioClosingService closingService;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        healthService = new PortfolioHealthCheckService(
                itemRepo, configService, briefingRepo, stockRepo, statusRepo, quoteRepo, splitRepo,
                latestValRepo, periodRepo, reportRepo, forecastRepo, eventRepo, industryRepo,
                quoteNorm, priceStructures, marketContextService, supportResistanceService, tradePlanService,
                splitAligner, sizingCalculator, calendar, clock, tx);
        closingService = new PortfolioClosingService(
                healthService, briefingRepo, notifRepo, radarRunRepo, radarRepo, sectorFlowRepo,
                sizingCalculator, clock, tx, entityManager);

        when(clock.now()).thenReturn(NOW);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(DAY);
        when(calendar.getSessionCloseAt(any())).thenReturn(NOW.minusSeconds(3600));
        when(configService.getConfig()).thenReturn(new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD"));
        when(periodRepo.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of());
        when(reportRepo.findKnownReportPeriodsForFigis(anyCollection(), any())).thenReturn(List.of());
        when(forecastRepo.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of());
    }

    /**
     * Scenario：驗證 HOLDING 體檢（跨拆股前期 T1/T2 達標、高風險破位 CRITICAL、財報倒數 3 日、非 USD 與缺棒不宣告結構正常）
     * 及 WATCHLIST 嚴格門檻（WAITING_TRIGGER 高於入場上限不發通知、ACTIVE 計畫發建議股數通知）。
     */
    @Test
    void healthCheckHandlesHoldingAlertsPriorSplitTargetAndWatchlistActiveGate() {
        PortfolioItem holdingBroken = portfolioItem("F_BRK", PortfolioItem.TYPE_HOLDING, "200", "50", PRIOR_DAY);
        PortfolioItem holdingTargetSplit = portfolioItem("F_TGT", PortfolioItem.TYPE_HOLDING, "100", "80", PRIOR_DAY);
        PortfolioItem holdingNonUsd = portfolioItem("F_HK", PortfolioItem.TYPE_HOLDING, "500", "10", DAY);
        PortfolioItem watchWaiting = portfolioItem("F_WAIT", PortfolioItem.TYPE_WATCHLIST, null, null, null);
        PortfolioItem watchActive = portfolioItem("F_ACT", PortfolioItem.TYPE_WATCHLIST, null, null, null);

        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc())
                .thenReturn(List.of(holdingBroken, holdingTargetSplit, holdingNonUsd, watchWaiting, watchActive));

        Stock sBrk = stock("F_BRK", "BRK", "USD");
        Stock sTgt = stock("F_TGT", "TGT", "USD");
        Stock sHk = stock("F_HK", "HKEX", "HKD");
        Stock sWait = stock("F_WAIT", "WAIT", "USD");
        Stock sAct = stock("F_ACT", "ACT", "USD");
        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(sBrk, sTgt, sHk, sWait, sAct));

        List<StockStatus> statuses = List.of(
                splitStatus("F_BRK"), splitStatus("F_TGT"), splitStatus("F_HK"), splitStatus("F_WAIT"), splitStatus("F_ACT"));
        when(statusRepo.findAllById(anyCollection())).thenReturn(statuses);

        // F_TGT 今日執行 2 拆 1：昨日已知 T1 = 100 → 今日對齊 T1 = 50；今日收盤 52 達標，昨日持倉 100×80 → 200×40
        StockSplit split2 = new StockSplit();
        split2.setFigi("F_TGT");
        split2.setSourceEventId("TGT-2-1");
        split2.setExecutionDate(DAY);
        split2.setSplitFrom(BigDecimal.ONE);
        split2.setSplitTo(new BigDecimal("2"));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split2));

        HoldingHealthItemDto priorTgt = new HoldingHealthItemDto(
                "F_TGT", "POS-F_TGT", "TGT", "TGT Corp", "USD", "TECH", "Tech", PRIOR_DAY,
                new BigDecimal("96"), new BigDecimal("100"), new BigDecimal("80"),
                new BigDecimal("9600"), new BigDecimal("1600"), new BigDecimal("0.20"),
                "SAME_BASIS", "FAIR_UPPER", "STRONG", "HOLD_TRAIL_NO_CHASE", null,
                new BigDecimal("88.0000"), "PLAN_PROTECTIVE_STOP",
                new BigDecimal("100.0000"), new BigDecimal("120.0000"), PRIOR_DAY,
                null, null, "ADEQUATE", "NONE", List.of(), List.of());
        DailyClosingBriefing priorBriefing = new DailyClosingBriefing();
        priorBriefing.setTradeDate(PRIOR_DAY);
        priorBriefing.setMarketRegime(MarketRegime.RISK_ON.name());
        priorBriefing.setPortfolioHealth(new PortfolioHealthSummaryDto(
                PRIOR_DAY, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT, NOW.minusSeconds(86400),
                "RISK_ON", 1, 0, 0, 0, 0, 0, true, true, 0, List.of(),
                List.of(priorTgt), List.of(), List.of(), List.of()));
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.of(priorBriefing));

        // 報價：F_BRK 收 42、F_TGT 收 52（拆後 >= 50）、F_HK 收 10、F_WAIT 收 110（高於入場上限 100）、F_ACT 收 99（區間內 ACTIVE）
        when(quoteRepo.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(List.of(
                barRow("F_BRK", DAY, "42"),
                barRow("F_TGT", DAY, "52"),
                barRow("F_HK", DAY, "10"),
                barRow("F_WAIT", DAY, "110"),
                barRow("F_ACT", DAY, "99")
        ));

        // 估值快照
        when(latestValRepo.findByFigiIn(anyCollection())).thenReturn(List.of(
                valLatest("F_BRK", "MODERATELY_UNDERVALUED", "WEAKENING", "42"),
                valLatest("F_TGT", "OVERVALUED_RISK", "STRONG", "52"),
                valLatest("F_HK", "FAIR_LOWER", "STRONG", "10"),
                valLatest("F_WAIT", "DEEP_UNDERVALUED", "STRONG", "110"),
                valLatest("F_ACT", "DEEP_UNDERVALUED", "STRONG", "99")
        ));

        // 產業分類
        StockIndustry tech = new StockIndustry();
        tech.setCode("TECH");
        tech.setIndustry("Technology");
        StockIndustryHistory ihBrk = new StockIndustryHistory();
        ihBrk.setFigi("F_BRK");
        ihBrk.setIndustry(tech);
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(List.of(ihBrk));

        // F_HK 3 天後有財報（Finnhub 慣例：eventSubtype=EARNINGS_REPORT + timePrecision=AFTER_CLOSE）
        MarketEvent hkEarnings = new MarketEvent();
        hkEarnings.setEventKey("EV-HK");
        hkEarnings.setFigi("F_HK");
        hkEarnings.setEventType("EARNINGS");
        hkEarnings.setEventSubtype("EARNINGS_REPORT");
        hkEarnings.setEventDate(DAY.plusDays(3));
        hkEarnings.setTimePrecision("AFTER_CLOSE");
        hkEarnings.setStatus("SCHEDULED");
        when(eventRepo.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of(hkEarnings));

        // 預載大盤 RISK_ON
        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(MarketRegime.RISK_ON, naMetric, naMetric, naMetric, DAY, NOW, List.of(), List.of());
        PreloadedMarketData preloaded = new PreloadedMarketData(
                DAY, NOW, null, null, Map.of(), List.of(),
                MetricResult.available(snap), MetricResult.available(new BigDecimal("15.2")));
        when(marketContextService.preloadMarketData(DAY, NOW)).thenReturn(preloaded);

        // 技術鏈 Stub
        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Stock st = inv.getArgument(3);
            List<QuoteBarRow> rows = inv.getArgument(5);
            BigDecimal close = rows.getLast().closePrice();
            NormalizedBarDto b = new NormalizedBarDto(DAY, close, close, close, close,
                    MetricResult.available(BigDecimal.valueOf(1_000_000)), NOW, NOW, NOW, NOW, null, "B1");
            PriceBasisDescriptor desc = new PriceBasisDescriptor("B1", "STORED", PriceAdjustment.SPLIT_ADJUSTED,
                    DAY, null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, st.getCurrency(), true, null);
            return MetricResult.available(new NormalizedPriceSeriesDto(
                    st.getFigi(), "DAILY", List.of(b), desc, null, MetricStatus.AVAILABLE, List.of(), "H1"));
        });
        when(priceStructures.analyze(any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("2"), new BigDecimal("0.02"), DAY, 14, 14);
            StructureTrendDto up = new StructureTrendDto(TrendState.UPTREND, NOW, PriceRelation.HH, PriceRelation.HL, List.of(), NOW);
            TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            return MetricResult.available(new PriceStructureDto(s.figi(), "B1", DAY, List.of(daily, weekly), List.of(), List.of()));
        });
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), any(), any(), any())).thenReturn(
                MetricResult.available(new MarketContextDto(
                        null, null, MetricResult.available(snap), List.of(),
                        new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()),
                        List.of(), List.of(), null, null, null, null, Map.of(), MetricResult.available(new BigDecimal("15.2")))));

        LevelZoneDto supZone = new LevelZoneDto(
                "Z1", AnalysisTimeframe.WEEKLY, new BigDecimal("96"), new BigDecimal("100"),
                ZoneRole.SUPPORT, new BigDecimal("70"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("98"), new BigDecimal("2"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("70"),
                1, NOW, null, List.of(), List.of(), 2, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());
        BreakoutDiagnosisDto highRiskDiag = new BreakoutDiagnosisDto(
                BreakoutDiagnosis.HIGH_RISK_BREAKDOWN, "Z_BRK", 1, 1, AnalysisTimeframe.WEEKLY,
                new BigDecimal("46"), new BigDecimal("48"), DAY, null, null, DAY, 1,
                new BigDecimal("2.1"), null, new BigDecimal("2.1"),
                new BigDecimal("0.05"), new BigDecimal("0.85"), new BigDecimal("0.10"),
                new BigDecimal("-0.06"), new BigDecimal("0.005"), new BigDecimal("-0.065"),
                List.of("HIGH_RVOL_BREAK"), List.of());

        when(supportResistanceService.analyze(any(), any(), any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            if ("F_BRK".equals(s.figi())) {
                return MetricResult.available(new SupportResistanceDto(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), highRiskDiag, List.of(highRiskDiag)));
            }
            return MetricResult.available(new SupportResistanceDto(
                    List.of(supZone), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of()));
        });

        when(tradePlanService.build(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            BigDecimal close = s.bars().getLast().close();
            TradePlanStatus status = "F_ACT".equals(s.figi()) ? TradePlanStatus.ACTIVE : TradePlanStatus.WAITING_TRIGGER;
            TradePlanDto plan = new TradePlanDto(
                    "P-" + s.figi(), AnalysisHorizon.POSITION, SetupType.PULLBACK,
                    new PriceRangeDto(new BigDecimal("96"), new BigDecimal("100"), "B1"),
                    EntryMethod.ZONE_LIMIT, "trigger",
                    new StopRuleDto(new BigDecimal("95"), new BigDecimal("95.5"), 2, AnalysisTimeframe.DAILY,
                            new BigDecimal("0.5"), new BigDecimal("2"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("2")),
                    List.of(new TargetDto(new BigDecimal("115"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "r", List.of())),
                    MetricResult.available(new RiskRewardDto(new BigDecimal("100"), new BigDecimal("95"),
                            new BigDecimal("5"), new BigDecimal("15"), new BigDecimal("3"),
                            new BigDecimal("5"), new BigDecimal("14"), new BigDecimal("2.8"),
                            new BigDecimal("15"), new BigDecimal("-5"))),
                    status, List.of(), "Z1", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());
            return MetricResult.available(new TradePlanResultDto(List.of(plan), close, DAY,
                    MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()), List.of(), List.of(), true));
        });

        PortfolioHealthSummaryDto result = healthService.evaluate(DAY, NOW, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT).summary();

        assertThat(result.holdingCount()).isEqualTo(3);
        assertThat(result.watchlistCount()).isEqualTo(2);
        // 因有非 USD 排除，不得宣告 structureHealthy = true
        assertThat(result.structureHealthy()).isFalse();
        assertThat(result.excludedHoldingsCount()).isEqualTo(1);

        HoldingHealthItemDto brk = result.holdings().stream().filter(h -> "F_BRK".equals(h.figi())).findFirst().orElseThrow();
        assertThat(brk.highestSeverity()).isEqualTo(InAppNotification.SEVERITY_CRITICAL);
        assertThat(brk.alerts().stream().map(PortfolioAlertDto::alertCode))
                .contains(PortfolioHealthCheckService.ALERT_HOLDING_HIGH_RISK_BREAKDOWN,
                        PortfolioHealthCheckService.ALERT_HOLDING_FUNDAMENTAL_WEAKENING);

        HoldingHealthItemDto tgt = result.holdings().stream().filter(h -> "F_TGT".equals(h.figi())).findFirst().orElseThrow();
        assertThat(tgt.adjustedShares()).isEqualByComparingTo("200.000000");
        assertThat(tgt.adjustedAvgCost()).isEqualByComparingTo("40.000000");
        assertThat(tgt.alerts().stream().map(PortfolioAlertDto::alertCode))
                .contains(PortfolioHealthCheckService.ALERT_HOLDING_TARGET_REACHED,
                        PortfolioHealthCheckService.ALERT_HOLDING_OVERVALUED_PROFIT_TAKING);

        HoldingHealthItemDto hk = result.holdings().stream().filter(h -> "F_HK".equals(h.figi())).findFirst().orElseThrow();
        assertThat(hk.alerts().stream().map(PortfolioAlertDto::alertCode))
                .contains(PortfolioHealthCheckService.ALERT_HOLDING_EARNINGS_COUNTDOWN);
        PortfolioAlertDto hkEarnAlert = hk.alerts().stream()
                .filter(a -> PortfolioHealthCheckService.ALERT_HOLDING_EARNINGS_COUNTDOWN.equals(a.alertCode()))
                .findFirst().orElseThrow();
        assertThat(hkEarnAlert.payload().get("timingCode")).isEqualTo("AFTER_CLOSE");

        // 自選股：只有 ACTIVE 的 F_ACT 產生進場通知，WAITING_TRIGGER 的 F_WAIT 不應發通知
        assertThat(result.watchlistOpportunities()).hasSize(1);
        assertThat(result.watchlistOpportunities().getFirst().figi()).isEqualTo("F_ACT");
        assertThat(result.watchlistOpportunities().getFirst().positionSizing().suggestedShares()).isEqualTo(150L);
    }

    /**
     * Scenario：雷達未發布時延後生成簡報；雷達正式零入選時正常發布；同日重跑冪等不重複寫入；大盤市況切換觸發警報並帶 previousTradeDate。
     */
    @Test
    void closingBriefingDefersWithoutRadarRunPublishesZeroRadarAndIsIdempotent() {
        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc()).thenReturn(List.of());
        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto bearSnap = new MarketSnapshotDto(MarketRegime.BEAR, naMetric, naMetric, naMetric, DAY, NOW, List.of(), List.of());
        when(marketContextService.preloadMarketData(DAY, NOW)).thenReturn(new PreloadedMarketData(
                DAY, NOW, null, null, Map.of(), List.of(),
                MetricResult.available(bearSnap), MetricResult.available(new BigDecimal("29.5"))));

        // 1) 當日雷達 run 缺失（大盤未齊延後）→ 延後發布，不凍結簡報
        when(briefingRepo.findById(DAY)).thenReturn(Optional.empty());
        when(radarRunRepo.findById(DAY)).thenReturn(Optional.empty());
        assertThat(closingService.publishDailyClosing(DAY, NOW)).isEmpty();

        // 2) 當日雷達正式零入選 + 前期為 RISK_ON 切換至 BEAR → 正常發布簡報並寫入 CRITICAL 市況切換通知
        DailyOpportunityRadarRun zeroRun = new DailyOpportunityRadarRun(
                DAY, MarketRegime.BEAR.name(), true, 0, 0, 0, NOW, NOW);
        when(radarRunRepo.findById(DAY)).thenReturn(Optional.of(zeroRun));
        when(radarRepo.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(DAY)).thenReturn(List.of());

        SectorMoneyFlowDaily topFlow = new SectorMoneyFlowDaily();
        topFlow.setIndustryCode("TECH");
        topFlow.setIndustryName("Technology");
        topFlow.setCmf20d(new BigDecimal("0.22"));
        topFlow.setDollarVolume1d(new BigDecimal("5000000000"));
        topFlow.setFlowState("INFLOW");
        SectorMoneyFlowDaily botFlow = new SectorMoneyFlowDaily();
        botFlow.setIndustryCode("ENRG");
        botFlow.setIndustryName("Energy");
        botFlow.setCmf20d(new BigDecimal("-0.18"));
        botFlow.setDollarVolume1d(new BigDecimal("1000000000"));
        botFlow.setFlowState("OUTFLOW");
        when(sectorFlowRepo.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(DAY)).thenReturn(List.of(topFlow, botFlow));

        DailyClosingBriefing prior = new DailyClosingBriefing();
        prior.setTradeDate(PRIOR_DAY);
        prior.setMarketRegime(MarketRegime.RISK_ON.name());
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.of(prior));

        Map<LocalDate, DailyClosingBriefing> persistedBriefings = new LinkedHashMap<>();
        List<InAppNotification> persistedNotifs = new ArrayList<>();
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof DailyClosingBriefing b) {
                persistedBriefings.put(b.getTradeDate(), b);
            } else if (arg instanceof InAppNotification n) {
                persistedNotifs.add(n);
            }
            return null;
        }).when(entityManager).persist(any());
        when(briefingRepo.findById(DAY)).thenAnswer(inv -> Optional.ofNullable(persistedBriefings.get(DAY)));
        when(notifRepo.findExistingDedupKeys(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> keys = inv.getArgument(0);
            return persistedNotifs.stream()
                    .map(InAppNotification::getDedupKey)
                    .filter(keys::contains)
                    .toList();
        });

        Optional<DailyClosingBriefingDto> published = closingService.publishDailyClosing(DAY, NOW);
        assertThat(published).isPresent();
        assertThat(published.get().regimeChanged()).isTrue();
        assertThat(published.get().previousTradeDate()).isEqualTo(PRIOR_DAY);
        assertThat(published.get().criticalCount()).isEqualTo(1);
        assertThat(published.get().radarTop5()).isEmpty();
        assertThat(published.get().topSectors()).hasSize(1);
        assertThat(published.get().bottomSectors()).hasSize(1);
        assertThat(persistedNotifs.stream().map(InAppNotification::getAlertCode))
                .containsExactlyInAnyOrder(PortfolioClosingService.ALERT_MARKET_REGIME_SHIFT,
                        PortfolioClosingService.ALERT_DAILY_CLOSING_BRIEFING);

        // 3) 標記已讀後同日重跑 → 冪等不重複寫入，且已讀狀態保持不變
        persistedNotifs.getFirst().setRead(true);
        persistedNotifs.getFirst().setReadAt(NOW.plusSeconds(60));
        int countBefore = persistedNotifs.size();
        Optional<DailyClosingBriefingDto> rerun = closingService.publishDailyClosing(DAY, NOW);
        assertThat(rerun).isPresent();
        assertThat(persistedNotifs).hasSize(countBefore);
        assertThat(persistedNotifs.getFirst().isRead()).isTrue();

        // 4) 併發發布簡報遇資料庫主鍵重複（DataIntegrityViolationException 與 Hibernate ConstraintViolationException）→ 改讀勝出方快照而不失敗
        DailyClosingBriefing winner = persistedBriefings.get(DAY);
        when(briefingRepo.findById(DAY)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        doAnswer(inv -> {
            throw new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint");
        }).when(entityManager).flush();
        Optional<DailyClosingBriefingDto> concurrentWinner = closingService.publishDailyClosing(DAY, NOW);
        assertThat(concurrentWinner).isPresent();
        assertThat(concurrentWinner.get().tradeDate()).isEqualTo(DAY);

        when(briefingRepo.findById(DAY)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        doAnswer(inv -> {
            throw new org.hibernate.exception.ConstraintViolationException(
                    "duplicate key", new java.sql.SQLException("23505"), "daily_closing_briefing_pkey");
        }).when(entityManager).flush();
        Optional<DailyClosingBriefingDto> hibernateWinner = closingService.publishDailyClosing(DAY, NOW);
        assertThat(hibernateWinner).isPresent();
        assertThat(hibernateWinner.get().tradeDate()).isEqualTo(DAY);
    }

    /**
     * Scenario（Codex Review 回歸測試）：
     * 1. 平倉重買（positionId 變更）不可承接舊持倉止損與 T1/T2；
     * 2. TECHNICAL_SERIES_UNAVAILABLE 與 VALUATION_STALE 不得宣告 structureHealthy=true，且過期估值不可觸發高估提醒；
     * 3. TECHNICAL_INVALIDATION_CONFIRMED 獨立發出 HOLDING_TECHNICAL_INVALIDATION（不與 HOLDING_STOP_BREACHED 混稱）；
     * 4. 非 USD 自選股（sizing=NOT_APPLICABLE）絕不產出建議 0 股通知；
     * 5. 併發發布簡報遇主鍵衝突時改回傳勝出方快照。
     */
    @Test
    void regressionGuardsPositionContinuityCoverageWarningsTechnicalInvalidationWatchlistNaAndConcurrency() {
        PortfolioItem reopened = portfolioItem("F_REOP", PortfolioItem.TYPE_HOLDING, "100", "50", DAY);
        reopened.setPositionId("POS-NEW-2");
        PortfolioItem staleTech = portfolioItem("F_NOTEC", PortfolioItem.TYPE_HOLDING, "10", "100", DAY);
        PortfolioItem techInvalid = portfolioItem("F_INV", PortfolioItem.TYPE_HOLDING, "10", "100", DAY);
        PortfolioItem nonUsdWatch = portfolioItem("F_HKW", PortfolioItem.TYPE_WATCHLIST, null, null, null);

        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc())
                .thenReturn(List.of(reopened, staleTech, techInvalid, nonUsdWatch));

        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(
                stock("F_REOP", "REOP", "USD"),
                stock("F_NOTEC", "NOTEC", "USD"),
                stock("F_INV", "INV", "USD"),
                stock("F_HKW", "HKW", "HKD")
        ));
        when(statusRepo.findAllById(anyCollection())).thenReturn(List.of(
                splitStatus("F_REOP"), splitStatus("F_NOTEC"), splitStatus("F_INV"), splitStatus("F_HKW")));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        // 當日盤前已過的財報（BEFORE_OPEN）在收盤截點不應再觸發「尚餘 0 天」提醒
        when(calendar.getSessionOpenAt(DAY)).thenReturn(NOW.minusSeconds(27000));
        MarketEvent passedBmo = new MarketEvent();
        passedBmo.setEventKey("EV-REOP-BMO");
        passedBmo.setFigi("F_REOP");
        passedBmo.setEventType("EARNINGS");
        passedBmo.setEventSubtype("EARNINGS_REPORT");
        passedBmo.setEventDate(DAY);
        passedBmo.setTimePrecision("BEFORE_OPEN");
        passedBmo.setStatus("SCHEDULED");
        when(eventRepo.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of(passedBmo));

        StockIndustry tech = new StockIndustry();
        tech.setCode("TECH");
        tech.setIndustry("Technology");
        List<StockIndustryHistory> ihs = new ArrayList<>();
        for (String f : List.of("F_REOP", "F_NOTEC", "F_INV", "F_HKW")) {
            StockIndustryHistory ih = new StockIndustryHistory();
            ih.setFigi(f);
            ih.setIndustry(tech);
            ihs.add(ih);
        }
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(ihs);

        // 前期簡報中的 F_REOP 屬於舊 positionId（POS-OLD-1），止損 90、目標 55；重開倉收 60 不應誤觸舊 T1 或舊止損
        HoldingHealthItemDto priorReop = new HoldingHealthItemDto(
                "F_REOP", "POS-OLD-1", "REOP", "REOP Corp", "USD", "TECH", "Technology", PRIOR_DAY,
                new BigDecimal("92"), new BigDecimal("100"), new BigDecimal("85"),
                new BigDecimal("9200"), new BigDecimal("700"), new BigDecimal("0.08"),
                "SAME_BASIS", "FAIR_LOWER", "STRONG", "HOLD_TRAIL_NO_CHASE", null,
                new BigDecimal("90.0000"), "PLAN_PROTECTIVE_STOP",
                new BigDecimal("55.0000"), new BigDecimal("65.0000"), PRIOR_DAY,
                null, null, "THIN", "NONE", List.of(), List.of());
        DailyClosingBriefing priorBriefing = new DailyClosingBriefing();
        priorBriefing.setTradeDate(PRIOR_DAY);
        priorBriefing.setMarketRegime(MarketRegime.RISK_ON.name());
        priorBriefing.setPortfolioHealth(new PortfolioHealthSummaryDto(
                PRIOR_DAY, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT, NOW.minusSeconds(86400),
                "RISK_ON", 1, 0, 0, 0, 0, 0, true, true, 0, List.of(),
                List.of(priorReop), List.of(), List.of(), List.of()));
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.of(priorBriefing));

        when(quoteRepo.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(List.of(
                barRow("F_REOP", DAY, "60"),
                barRow("F_NOTEC", DAY, "100"),
                barRow("F_INV", DAY, "95"),
                barRow("F_HKW", DAY, "99")
        ));

        // F_REOP 的估值日期過期（PRIOR_DAY）且標記 OVERVALUED_RISK → 應記錄 VALUATION_STALE 且不誤發高估提醒
        StockValuationLatest staleVal = valLatest("F_REOP", "OVERVALUED_RISK", "STRONG", "60");
        staleVal.setValuationDate(PRIOR_DAY);
        when(latestValRepo.findByFigiIn(anyCollection())).thenReturn(List.of(
                staleVal,
                valLatest("F_NOTEC", "FAIR_LOWER", "STRONG", "100"),
                valLatest("F_INV", "FAIR_LOWER", "STRONG", "95"),
                valLatest("F_HKW", "DEEP_UNDERVALUED", "STRONG", "99")
        ));

        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(MarketRegime.RISK_ON, naMetric, naMetric, naMetric, DAY, NOW, List.of(), List.of());
        when(marketContextService.preloadMarketData(DAY, NOW)).thenReturn(new PreloadedMarketData(
                DAY, NOW, null, null, Map.of(), List.of(),
                MetricResult.available(snap), MetricResult.available(new BigDecimal("15.0"))));

        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Stock st = inv.getArgument(3);
            if ("F_NOTEC".equals(st.getFigi())) {
                return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
            }
            List<QuoteBarRow> rows = inv.getArgument(5);
            BigDecimal close = rows.getLast().closePrice();
            NormalizedBarDto b = new NormalizedBarDto(DAY, close, close, close, close,
                    MetricResult.available(BigDecimal.valueOf(1_000_000)), NOW, NOW, NOW, NOW, null, "B1");
            PriceBasisDescriptor desc = new PriceBasisDescriptor("B1", "STORED", PriceAdjustment.SPLIT_ADJUSTED,
                    DAY, null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, st.getCurrency(), true, null);
            return MetricResult.available(new NormalizedPriceSeriesDto(
                    st.getFigi(), "DAILY", List.of(b), desc, null, MetricStatus.AVAILABLE, List.of(), "H1"));
        });
        when(priceStructures.analyze(any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("2"), new BigDecimal("0.02"), DAY, 14, 14);
            StructureTrendDto up = new StructureTrendDto(TrendState.UPTREND, NOW, PriceRelation.HH, PriceRelation.HL, List.of(), NOW);
            TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            return MetricResult.available(new PriceStructureDto(s.figi(), "B1", DAY, List.of(daily), List.of(), List.of()));
        });
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), any(), any(), any())).thenReturn(
                MetricResult.available(new MarketContextDto(
                        null, null, MetricResult.available(snap), List.of(),
                        new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()),
                        List.of(), List.of(), null, null, null, null, Map.of(), MetricResult.available(new BigDecimal("15.0")))));

        LevelZoneDto supZone = new LevelZoneDto(
                "Z1", AnalysisTimeframe.WEEKLY, new BigDecimal("96"), new BigDecimal("100"),
                ZoneRole.SUPPORT, new BigDecimal("70"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("98"), new BigDecimal("2"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("70"),
                1, NOW, null, List.of(), List.of(), 2, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());
        when(supportResistanceService.analyze(any(), any(), any(), any())).thenReturn(
                MetricResult.available(new SupportResistanceDto(
                        List.of(supZone), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of())));

        when(tradePlanService.build(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            BigDecimal close = s.bars().getLast().close();
            List<AnalysisReasonDto> planReasons = "F_INV".equals(s.figi())
                    ? List.of(AnalysisReasonDto.of("TECHNICAL_INVALIDATION_CONFIRMED", "STOP", "2 closes below"))
                    : List.of();
            TradePlanStatus status = "F_INV".equals(s.figi()) ? TradePlanStatus.INVALIDATED : TradePlanStatus.ACTIVE;
            TradePlanDto plan = new TradePlanDto(
                    "P-" + s.figi(), AnalysisHorizon.POSITION, SetupType.PULLBACK,
                    new PriceRangeDto(new BigDecimal("96"), new BigDecimal("100"), "B1"),
                    EntryMethod.ZONE_LIMIT, "trigger",
                    new StopRuleDto(new BigDecimal("95"), new BigDecimal("95.5"), 2, AnalysisTimeframe.DAILY,
                            new BigDecimal("0.5"), new BigDecimal("2"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("2")),
                    List.of(new TargetDto(new BigDecimal("115"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "r", List.of())),
                    MetricResult.available(new RiskRewardDto(new BigDecimal("100"), new BigDecimal("95"),
                            new BigDecimal("5"), new BigDecimal("15"), new BigDecimal("3"),
                            new BigDecimal("5"), new BigDecimal("14"), new BigDecimal("2.8"),
                            new BigDecimal("15"), new BigDecimal("-5"))),
                    status, planReasons, "Z1", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());
            return MetricResult.available(new TradePlanResultDto(List.of(plan), close, DAY,
                    MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()), List.of(), List.of(), true));
        });

        PortfolioHealthSummaryDto summary = healthService.evaluate(DAY, NOW, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT).summary();
        assertThat(summary.completeCoverage()).isFalse();
        assertThat(summary.structureHealthy()).isFalse();
        assertThat(summary.coverageWarnings()).contains("TECHNICAL_SERIES_UNAVAILABLE:NOTEC", "VALUATION_STALE:REOP");

        HoldingHealthItemDto reopRes = summary.holdings().stream().filter(h -> "F_REOP".equals(h.figi())).findFirst().orElseThrow();
        assertThat(reopRes.alerts()).isEmpty();

        HoldingHealthItemDto invRes = summary.holdings().stream().filter(h -> "F_INV".equals(h.figi())).findFirst().orElseThrow();
        assertThat(invRes.alerts().stream().map(PortfolioAlertDto::alertCode))
                .containsExactly(PortfolioHealthCheckService.ALERT_HOLDING_TECHNICAL_INVALIDATION);

        // 非 USD 自選股（F_HKW）即使技術 ACTIVE，因 sizing = NOT_APPLICABLE 不得發出自選機會
        assertThat(summary.watchlistOpportunities()).isEmpty();
    }

    /**
     * Scenario（P1 隔日事前基準回歸測試）：
     * 1. 多道支撐（近端 192–195 強度 65 + 遠端大底 120–125 強度 88）：隔日事前止損與 T1/T2 必須選近端有效計畫（189.50 / 215 / 230），不誤綁遠端強支撐；
     * 2. 近端 WAITING_TRIGGER 計畫若帶 HIGH_RISK_BREAKDOWN_DETECTED 或 RECLAIM_REQUIRED 必須略過，且該失效區不可在 ATR 回退中被選回；
     * 3. 持倉處於第二軌 TREND_PULLBACK_STARTER（eligible=true 且緊貼均線止損高於水平支撐）時，事前止損收緊為 STARTER_ANCHOR_STOP 並保留上方 T1/T2；
     * 4. 創高無水平支撐但具備第二軌試單資格時，仍正確記下 STARTER_ANCHOR_STOP（不遺漏止損）。
     */
    @Test
    void holdingNextReferenceUsesNearestValidPlanSkipsDisqualifiedAndTightensWithStarterStop() {
        PortfolioItem hNear = portfolioItem("F_NEAR", PortfolioItem.TYPE_HOLDING, "20", "180", DAY);
        PortfolioItem hSkip = portfolioItem("F_SKIP", PortfolioItem.TYPE_HOLDING, "20", "180", DAY);
        PortfolioItem hOnlyBroken = portfolioItem("F_ONLY_BRK", PortfolioItem.TYPE_HOLDING, "20", "180", DAY);
        PortfolioItem hStarter = portfolioItem("F_STARTER", PortfolioItem.TYPE_HOLDING, "20", "180", DAY);
        PortfolioItem hStarterOnly = portfolioItem("F_STARTER_ONLY", PortfolioItem.TYPE_HOLDING, "20", "180", DAY);

        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc())
                .thenReturn(List.of(hNear, hSkip, hOnlyBroken, hStarter, hStarterOnly));
        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(
                stock("F_NEAR", "NEAR", "USD"),
                stock("F_SKIP", "SKIP", "USD"),
                stock("F_ONLY_BRK", "OBRK", "USD"),
                stock("F_STARTER", "STRT", "USD"),
                stock("F_STARTER_ONLY", "STOH", "USD")
        ));
        when(statusRepo.findAllById(anyCollection())).thenReturn(List.of(
                splitStatus("F_NEAR"), splitStatus("F_SKIP"), splitStatus("F_ONLY_BRK"),
                splitStatus("F_STARTER"), splitStatus("F_STARTER_ONLY")));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        when(eventRepo.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of());
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.empty());

        StockIndustry tech = new StockIndustry();
        tech.setCode("TECH");
        tech.setIndustry("Technology");
        List<StockIndustryHistory> ihs = new ArrayList<>();
        for (String f : List.of("F_NEAR", "F_SKIP", "F_ONLY_BRK", "F_STARTER", "F_STARTER_ONLY")) {
            StockIndustryHistory ih = new StockIndustryHistory();
            ih.setFigi(f);
            ih.setIndustry(tech);
            ihs.add(ih);
        }
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(ihs);

        when(quoteRepo.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(List.of(
                barRow("F_NEAR", PRIOR_DAY, "202"), barRow("F_NEAR", DAY, "200"),
                barRow("F_SKIP", PRIOR_DAY, "202"), barRow("F_SKIP", DAY, "200"),
                barRow("F_ONLY_BRK", PRIOR_DAY, "202"), barRow("F_ONLY_BRK", DAY, "200"),
                barRow("F_STARTER", PRIOR_DAY, "202"), barRow("F_STARTER", DAY, "200"),
                barRow("F_STARTER_ONLY", PRIOR_DAY, "202"), barRow("F_STARTER_ONLY", DAY, "200")
        ));

        List<StockValuationLatest> vals = new ArrayList<>();
        for (String f : List.of("F_NEAR", "F_SKIP", "F_ONLY_BRK", "F_STARTER", "F_STARTER_ONLY")) {
            StockValuationLatest v = valLatest(f, "FAIR_UPPER", "STRONG", "200");
            v.setFairValueLow(new BigDecimal("150"));
            v.setFairValueMid(new BigDecimal("180"));
            v.setFairValueHigh(new BigDecimal("220"));
            v.setPe(new BigDecimal("20"));
            vals.add(v);
        }
        when(latestValRepo.findByFigiIn(anyCollection())).thenReturn(vals);

        // 讓 F_STARTER 與 F_STARTER_ONLY 透過真實 ForwardValuationCalculator 與 ValuationSummaryCalculator 達成 starter.eligible()=true
        List<StockFinancialPeriod> starterPeriods = new ArrayList<>();
        List<StockEarningsForecast> starterForecasts = new ArrayList<>();
        for (String f : List.of("F_STARTER", "F_STARTER_ONLY")) {
            StockFinancialPeriod p = new StockFinancialPeriod();
            p.setFigi(f);
            p.setPeriodStart(LocalDate.of(2026, 7, 1));
            p.setPeriodEnd(LocalDate.of(2026, 9, 30));
            p.setReportingCurrency("USD");
            p.setEpsTtm(new BigDecimal("10.00"));
            p.setEpsTtmStatus("AVAILABLE");
            p.setEpsShareBasisDate(LocalDate.of(2026, 9, 30));
            starterPeriods.add(p);

            StockEarningsForecast ef = new StockEarningsForecast();
            ef.setFigi(f);
            ef.setSource("ALPHA_VANTAGE");
            ef.setFiscalPeriod("2027-09-30");
            ef.setPeriodType("ANNUAL");
            ef.setPeriodEnd(LocalDate.of(2027, 9, 30));
            ef.setObservationDate(DAY.minusDays(2));
            ef.setObservedAt(NOW.minusSeconds(172800));
            ef.setCurrency("USD");
            ef.setEps(new BigDecimal("12.50"));
            starterForecasts.add(ef);
        }
        when(periodRepo.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(starterPeriods);
        when(forecastRepo.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(starterForecasts);

        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(MarketRegime.RISK_ON, naMetric, naMetric, naMetric, DAY, NOW, List.of(), List.of());
        when(marketContextService.preloadMarketData(DAY, NOW)).thenReturn(new PreloadedMarketData(
                DAY, NOW, null, null, Map.of(), List.of(),
                MetricResult.available(snap), MetricResult.available(new BigDecimal("15.0"))));

        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Stock st = inv.getArgument(3);
            NormalizedBarDto prevBar = new NormalizedBarDto(PRIOR_DAY,
                    new BigDecimal("203"), new BigDecimal("205"), new BigDecimal("201"), new BigDecimal("202"),
                    MetricResult.available(BigDecimal.valueOf(1_200_000)), NOW.minusSeconds(86400), NOW, NOW, NOW, null, "B1");
            NormalizedBarDto curBar = new NormalizedBarDto(DAY,
                    new BigDecimal("199"), new BigDecimal("201"), new BigDecimal("198"), new BigDecimal("200"),
                    MetricResult.available(BigDecimal.valueOf(800_000)), NOW, NOW, NOW, NOW, null, "B1");
            PriceBasisDescriptor desc = new PriceBasisDescriptor("B1", "STORED", PriceAdjustment.SPLIT_ADJUSTED,
                    DAY, null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, st.getCurrency(), true, null);
            return MetricResult.available(new NormalizedPriceSeriesDto(
                    st.getFigi(), "DAILY", List.of(prevBar, curBar), desc, null, MetricStatus.AVAILABLE, List.of(), "H1"));
        });
        when(priceStructures.analyze(any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("2"), new BigDecimal("0.01"), DAY, 14, 14);
            StructureTrendDto up = new StructureTrendDto(TrendState.UPTREND, NOW, PriceRelation.HH, PriceRelation.HL, List.of(), NOW);
            TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            return MetricResult.available(new PriceStructureDto(s.figi(), "B1", DAY, List.of(daily, weekly), List.of(), List.of()));
        });
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            boolean starterCandidate = "F_STARTER".equals(s.figi()) || "F_STARTER_ONLY".equals(s.figi());
            MomentumAnalysisDto mom = starterCandidate
                    ? new MomentumAnalysisDto(Map.of(
                            "SMA200", MetricResult.available(new BigDecimal("170.00")),
                            "SMA50", MetricResult.available(new BigDecimal("198.00")),
                            "ATR14", MetricResult.available(new BigDecimal("2.00"))),
                            naMetric, List.of())
                    : null;
            VolumeAnalysisDto vol = starterCandidate
                    ? new VolumeAnalysisDto(MetricResult.available(new BigDecimal("0.75")),
                            naMetric, naMetric, naMetric,
                            MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                            naMetric, List.of(), List.of())
                    : null;
            return MetricResult.available(new MarketContextDto(
                    null, null, MetricResult.available(snap), List.of(),
                    new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()),
                    List.of(), List.of(), mom, vol, null, null, Map.of(), MetricResult.available(new BigDecimal("15.0"))));
        });

        LevelZoneDto zNear = new LevelZoneDto(
                "Z_NEAR", AnalysisTimeframe.WEEKLY, new BigDecimal("192"), new BigDecimal("195"),
                ZoneRole.SUPPORT, new BigDecimal("65"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("193.5"), new BigDecimal("5"), 1, ZoneStrengthLabel.MEDIUM, new BigDecimal("65"),
                1, NOW, null, List.of(), List.of(), 2, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());
        LevelZoneDto zFarStrong = new LevelZoneDto(
                "Z_FAR", AnalysisTimeframe.WEEKLY, new BigDecimal("120"), new BigDecimal("125"),
                ZoneRole.SUPPORT, new BigDecimal("88"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("122.5"), new BigDecimal("6"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("88"),
                1, NOW, null, List.of(), List.of(), 4, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());
        LevelZoneDto zBroken = new LevelZoneDto(
                "Z_BRK", AnalysisTimeframe.WEEKLY, new BigDecimal("193"), new BigDecimal("196"),
                ZoneRole.SUPPORT, new BigDecimal("70"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("194.5"), new BigDecimal("4"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("70"),
                1, NOW, null, List.of(), List.of(), 2, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());
        LevelZoneDto zOkSecond = new LevelZoneDto(
                "Z_OK2", AnalysisTimeframe.WEEKLY, new BigDecimal("180"), new BigDecimal("184"),
                ZoneRole.SUPPORT, new BigDecimal("60"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("182"), new BigDecimal("4"), 1, ZoneStrengthLabel.MEDIUM, new BigDecimal("60"),
                1, NOW, null, List.of(), List.of(), 2, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());

        when(supportResistanceService.analyze(any(), any(), any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            if ("F_STARTER_ONLY".equals(s.figi())) {
                return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
            }
            if ("F_SKIP".equals(s.figi())) {
                return MetricResult.available(new SupportResistanceDto(
                        List.of(zBroken, zOkSecond), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of()));
            }
            if ("F_ONLY_BRK".equals(s.figi())) {
                return MetricResult.available(new SupportResistanceDto(
                        List.of(zBroken), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of()));
            }
            return MetricResult.available(new SupportResistanceDto(
                    List.of(zNear, zFarStrong), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of()));
        });

        TradePlanDto nearPlan = new TradePlanDto(
                "P_NEAR", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("192"), new BigDecimal("195"), "B1"),
                EntryMethod.ZONE_LIMIT, "trigger",
                new StopRuleDto(new BigDecimal("189.50"), new BigDecimal("190.75"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("5"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("5")),
                List.of(
                        new TargetDto(new BigDecimal("215"), new BigDecimal("0.5"), TargetAction.TAKE_PROFIT, "R1", "r1", List.of()),
                        new TargetDto(new BigDecimal("230"), new BigDecimal("0.5"), TargetAction.TAKE_PROFIT, "R2", "r2", List.of())
                ),
                MetricResult.available(new RiskRewardDto(new BigDecimal("195"), new BigDecimal("189.50"),
                        new BigDecimal("5.5"), new BigDecimal("20"), new BigDecimal("3.6"),
                        new BigDecimal("5.5"), new BigDecimal("19.5"), new BigDecimal("3.5"),
                        new BigDecimal("20"), new BigDecimal("-5.5"))),
                TradePlanStatus.WAITING_TRIGGER,
                List.of(AnalysisReasonDto.of("ENTRY_PRICE_ABOVE_CAP", "TRADE_PLAN", "above cap")),
                "Z_NEAR", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());

        TradePlanDto brokenWaitingPlan = new TradePlanDto(
                "P_BRK", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("193"), new BigDecimal("196"), "B1"),
                EntryMethod.ZONE_LIMIT, "trigger",
                new StopRuleDto(new BigDecimal("191.00"), new BigDecimal("192.00"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("4"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("4")),
                List.of(new TargetDto(new BigDecimal("210"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "r1", List.of())),
                MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()),
                TradePlanStatus.WAITING_TRIGGER,
                List.of(AnalysisReasonDto.of("HIGH_RISK_BREAKDOWN_DETECTED", "TRADE_PLAN", "high risk breakdown")),
                "Z_BRK", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());

        TradePlanDto okSecondPlan = new TradePlanDto(
                "P_OK2", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("180"), new BigDecimal("184"), "B1"),
                EntryMethod.ZONE_LIMIT, "trigger",
                new StopRuleDto(new BigDecimal("178.00"), new BigDecimal("179.00"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("4"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("4")),
                List.of(new TargetDto(new BigDecimal("212"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "r1", List.of())),
                MetricResult.available(new RiskRewardDto(new BigDecimal("184"), new BigDecimal("178"),
                        new BigDecimal("6"), new BigDecimal("28"), new BigDecimal("4.6"),
                        new BigDecimal("6"), new BigDecimal("27"), new BigDecimal("4.5"),
                        new BigDecimal("28"), new BigDecimal("-6"))),
                TradePlanStatus.WAITING_TRIGGER,
                List.of(AnalysisReasonDto.of("ENTRY_PRICE_ABOVE_CAP", "TRADE_PLAN", "above cap")),
                "Z_OK2", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());

        when(tradePlanService.build(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            BigDecimal close = s.bars().getLast().close();
            if ("F_SKIP".equals(s.figi())) {
                return MetricResult.available(new TradePlanResultDto(List.of(brokenWaitingPlan, okSecondPlan), close, DAY,
                        MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()), List.of(), List.of(), true));
            }
            if ("F_ONLY_BRK".equals(s.figi())) {
                return MetricResult.available(new TradePlanResultDto(List.of(brokenWaitingPlan), close, DAY,
                        MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()), List.of(), List.of(), true));
            }
            // 注意：故意不在 planList 放入 Z_FAR（模擬 TradePlanService 只取最近 3 道，而 eval.supportZone() 選中遠端強支撐 Z_FAR）
            return MetricResult.available(new TradePlanResultDto(List.of(nearPlan), close, DAY,
                    MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()), List.of(), List.of(), true));
        });

        PortfolioHealthSummaryDto summary = healthService.evaluate(DAY, NOW, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT).summary();

        // 1) F_NEAR：選中近端計畫（189.50 / 215 / 230），而非遠端強支撐 Z_FAR（117 / null）
        HoldingHealthItemDto nearRes = summary.holdings().stream().filter(h -> "F_NEAR".equals(h.figi())).findFirst().orElseThrow();
        assertThat(nearRes.referenceProtectiveStop()).isEqualByComparingTo("189.5000");
        assertThat(nearRes.referenceStopSource()).isEqualTo("PLAN_PROTECTIVE_STOP");
        assertThat(nearRes.referenceTarget1()).isEqualByComparingTo("215.0000");
        assertThat(nearRes.referenceTarget2()).isEqualByComparingTo("230.0000");

        // 2) F_SKIP：略過帶 HIGH_RISK_BREAKDOWN_DETECTED 的 P_BRK，改選第二道有效計畫 P_OK2
        HoldingHealthItemDto skipRes = summary.holdings().stream().filter(h -> "F_SKIP".equals(h.figi())).findFirst().orElseThrow();
        assertThat(skipRes.referenceProtectiveStop()).isEqualByComparingTo("178.0000");
        assertThat(skipRes.referenceStopSource()).isEqualTo("PLAN_PROTECTIVE_STOP");
        assertThat(skipRes.referenceTarget1()).isEqualByComparingTo("212.0000");

        // 2b) F_ONLY_BRK：僅有失效支撐 Z_BRK 時，ATR 回退亦不得把剛排除的失效區選回
        HoldingHealthItemDto onlyBrkRes = summary.holdings().stream().filter(h -> "F_ONLY_BRK".equals(h.figi())).findFirst().orElseThrow();
        assertThat(onlyBrkRes.referenceProtectiveStop()).isNull();
        assertThat(onlyBrkRes.referenceStopSource()).isNull();

        // 3) F_STARTER：試單止損（198 - 0.5*2 = 197.00）緊於近端水平止損（189.50），升級為 STARTER_ANCHOR_STOP 並保留上方 T1/T2
        HoldingHealthItemDto starterRes = summary.holdings().stream().filter(h -> "F_STARTER".equals(h.figi())).findFirst().orElseThrow();
        assertThat(starterRes.referenceProtectiveStop()).isEqualByComparingTo("197.0000");
        assertThat(starterRes.referenceStopSource()).isEqualTo("STARTER_ANCHOR_STOP");
        assertThat(starterRes.referenceTarget1()).isEqualByComparingTo("215.0000");
        assertThat(starterRes.referenceTarget2()).isEqualByComparingTo("230.0000");

        // 4) F_STARTER_ONLY：創高無水平支撐，仍記錄第二軌緊貼均線止損 197.0000
        HoldingHealthItemDto starterOnlyRes = summary.holdings().stream().filter(h -> "F_STARTER_ONLY".equals(h.figi())).findFirst().orElseThrow();
        assertThat(starterOnlyRes.referenceProtectiveStop()).isEqualByComparingTo("197.0000");
        assertThat(starterOnlyRes.referenceStopSource()).isEqualTo("STARTER_ANCHOR_STOP");
        assertThat(starterOnlyRes.referenceTarget1()).isNull();
        assertThat(starterOnlyRes.referenceTarget2()).isNull();
    }

    @Test
    void etfHoldingAndWatchlistSkipValuationGapAndUseEtfTechnicalSummary() {
        PortfolioItem etfHolding = portfolioItem("F_QQQ", PortfolioItem.TYPE_HOLDING, "20", "480", LocalDate.of(2026, 6, 1));
        PortfolioItem etfWatch = portfolioItem("F_SPY", PortfolioItem.TYPE_WATCHLIST, null, null, null);
        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc()).thenReturn(List.of(etfHolding, etfWatch));

        Stock qqq = stock("F_QQQ", "QQQ", "USD");
        qqq.setStockType("ETF");
        Stock spy = stock("F_SPY", "SPY", "USD");
        spy.setStockType("ETP");
        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(qqq, spy));
        when(statusRepo.findAllById(anyCollection())).thenReturn(List.of(splitStatus("F_QQQ"), splitStatus("F_SPY")));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(List.of());
        when(latestValRepo.findByFigiIn(anyCollection())).thenReturn(List.of());
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.empty());

        // 即使資料表誤含事件，ETF 持倉也不應觸發公司財報倒數
        MarketEvent strayEvent = new MarketEvent();
        strayEvent.setFigi("F_QQQ");
        strayEvent.setEventType("EARNINGS");
        strayEvent.setStatus("SCHEDULED");
        strayEvent.setEventDate(DAY.plusDays(2));
        when(eventRepo.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of(strayEvent));

        when(quoteRepo.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(List.of(
                barRow("F_QQQ", PRIOR_DAY, "508"), barRow("F_QQQ", DAY, "512"),
                barRow("F_SPY", PRIOR_DAY, "580"), barRow("F_SPY", DAY, "585")
        ));

        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(MarketRegime.RISK_ON, naMetric, naMetric, naMetric, DAY, NOW, List.of(), List.of());
        when(marketContextService.preloadMarketData(DAY, NOW)).thenReturn(new PreloadedMarketData(
                DAY, NOW, null, null, Map.of(), List.of(),
                MetricResult.available(snap), MetricResult.available(new BigDecimal("14.5"))));

        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Stock st = inv.getArgument(3);
            NormalizedBarDto curBar = new NormalizedBarDto(DAY,
                    new BigDecimal("510"), new BigDecimal("514"), new BigDecimal("509"), new BigDecimal("512"),
                    MetricResult.available(BigDecimal.valueOf(10_000_000)), NOW, NOW, NOW, NOW, null, "B1");
            PriceBasisDescriptor desc = new PriceBasisDescriptor("B1", "STORED", PriceAdjustment.SPLIT_ADJUSTED,
                    DAY, null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, st.getCurrency(), true, null);
            return MetricResult.available(new NormalizedPriceSeriesDto(
                    st.getFigi(), "DAILY", List.of(curBar), desc, null, MetricStatus.AVAILABLE, List.of(), "H1"));
        });
        when(priceStructures.analyze(any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            StructureTrendDto up = new StructureTrendDto(TrendState.UPTREND, NOW, PriceRelation.HH, PriceRelation.HL, List.of(), NOW);
            TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, s.bars(),
                    null, List.of(), List.of(), null, null, MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, s.bars(),
                    null, List.of(), List.of(), null, null, MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            return MetricResult.available(new PriceStructureDto(s.figi(), "B1", DAY, List.of(daily, weekly), List.of(), List.of()));
        });
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), any(), any(), any())).thenReturn(
                MetricResult.available(new MarketContextDto(
                        null, null, MetricResult.available(snap), List.of(),
                        new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()),
                        List.of(), List.of(),
                        new MomentumAnalysisDto(Map.of("SMA200", MetricResult.available(new BigDecimal("470"))), naMetric, List.of()),
                        null, null, null, Map.of(), MetricResult.available(new BigDecimal("14.5")))));

        LevelZoneDto etfSupport = new LevelZoneDto(
                "Z_ETF", AnalysisTimeframe.WEEKLY, new BigDecimal("495"), new BigDecimal("502"),
                ZoneRole.SUPPORT, new BigDecimal("78"), NOW, 2, NOW, ZoneState.ACTIVE,
                new BigDecimal("498.5"), new BigDecimal("6"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("78"),
                1, NOW, null, List.of(), List.of(), 2, 0, 5,
                BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, false, List.of());
        when(supportResistanceService.analyze(any(), any(), any(), any())).thenReturn(
                MetricResult.available(new SupportResistanceDto(
                        List.of(etfSupport), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of())));
        TradePlanDto etfPlan = new TradePlanDto(
                "P_ETF", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("495"), new BigDecimal("502"), "B1"),
                EntryMethod.ZONE_LIMIT, "trigger",
                new StopRuleDto(new BigDecimal("490.00"), new BigDecimal("492.00"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("6"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("6")),
                List.of(new TargetDto(new BigDecimal("540"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "r1", List.of())),
                MetricResult.available(new RiskRewardDto(new BigDecimal("502"), new BigDecimal("490"),
                        new BigDecimal("12"), new BigDecimal("38"), new BigDecimal("3.1"),
                        new BigDecimal("12"), new BigDecimal("38"), new BigDecimal("3.1"),
                        new BigDecimal("28"), new BigDecimal("-22"))),
                TradePlanStatus.WAITING_TRIGGER, List.of(),
                "Z_ETF", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any())).thenReturn(
                MetricResult.available(new TradePlanResultDto(List.of(etfPlan), new BigDecimal("512"), DAY,
                        MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, List.of(), List.of()), List.of(), List.of(), true)));

        PortfolioHealthSummaryDto summary = healthService.evaluate(DAY, NOW, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT).summary();

        assertThat(summary.completeCoverage()).isTrue();
        assertThat(summary.structureHealthy()).isTrue();
        assertThat(summary.coverageWarnings()).isEmpty();
        assertThat(summary.watchlistOpportunities()).isEmpty();
        assertThat(summary.holdings()).hasSize(1);
        HoldingHealthItemDto qqqRes = summary.holdings().getFirst();
        assertThat(qqqRes.verdict()).isEqualTo("NOT_APPLICABLE_FOR_ETF");
        assertThat(qqqRes.fundamentalBacking()).isEqualTo("NOT_APPLICABLE");
        assertThat(qqqRes.stance()).isEqualTo("ETF_TECHNICAL_BULLISH_SUPPORT");
        assertThat(qqqRes.dataGapReasons()).isEmpty();
        assertThat(qqqRes.nextEarningsDate()).isNull();
        assertThat(qqqRes.referenceProtectiveStop()).isEqualByComparingTo("490.0000");
        assertThat(qqqRes.referenceTarget1()).isEqualByComparingTo("540.0000");
        org.mockito.Mockito.verify(marketContextService).evaluateWithPreloaded(
                any(), any(), any(), org.mockito.ArgumentMatchers.eq(List.of()), org.mockito.ArgumentMatchers.eq(List.of()), any());
    }

    @Test
    void verifiedAdrForwardSwingToLossTriggersHoldingValueTrapAndBlocksWatchlistBuyAlert() {
        AdrShareRatioRepository adrRepo = mock(AdrShareRatioRepository.class);
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        PortfolioHealthCheckService adrHealthService = new PortfolioHealthCheckService(
                itemRepo, configService, briefingRepo, stockRepo, statusRepo, quoteRepo, splitRepo,
                latestValRepo, periodRepo, reportRepo, forecastRepo, eventRepo, industryRepo,
                quoteNorm, priceStructures, marketContextService, supportResistanceService, tradePlanService,
                splitAligner, sizingCalculator, calendar, clock, tx, null, adrRepo);

        PortfolioItem holdArm = portfolioItem("F_ARM_H", PortfolioItem.TYPE_HOLDING, "10", "100", PRIOR_DAY);
        PortfolioItem watchArm = portfolioItem("F_ARM_W", PortfolioItem.TYPE_WATCHLIST, null, null, null);
        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc()).thenReturn(List.of(holdArm, watchArm));

        Stock sHold = stock("F_ARM_H", "ARM", "USD");
        sHold.setStockType("ADR");
        Stock sWatch = stock("F_ARM_W", "ARM2", "USD");
        sWatch.setStockType("ADR");
        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(sHold, sWatch));
        when(statusRepo.findAllById(anyCollection())).thenReturn(List.of(splitStatus("F_ARM_H"), splitStatus("F_ARM_W")));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.empty());
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(List.of());
        when(eventRepo.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of());

        AdrShareRatio r1 = new AdrShareRatio();
        r1.setSymbol("ARM");
        r1.setAdrRatio(BigDecimal.ONE);
        r1.setVerified(true);
        AdrShareRatio r2 = new AdrShareRatio();
        r2.setSymbol("ARM2");
        r2.setAdrRatio(BigDecimal.ONE);
        r2.setVerified(true);
        when(adrRepo.findBySymbolIn(anyCollection())).thenReturn(List.of(r1, r2));

        when(quoteRepo.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(List.of(
                barRow("F_ARM_H", DAY, "100"), barRow("F_ARM_W", DAY, "100")));

        StockValuationLatest vHold = valLatest("F_ARM_H", "DEEP_UNDERVALUED", "STRONG", "100");
        vHold.setMarketCap(new BigDecimal("100000000000"));
        vHold.setFairValueLow(new BigDecimal("110"));
        vHold.setFairValueMid(new BigDecimal("130"));
        vHold.setFairValueHigh(new BigDecimal("150"));
        StockValuationLatest vWatch = valLatest("F_ARM_W", "DEEP_UNDERVALUED", "STRONG", "100");
        vWatch.setMarketCap(new BigDecimal("100000000000"));
        vWatch.setFairValueLow(new BigDecimal("110"));
        vWatch.setFairValueMid(new BigDecimal("130"));
        vWatch.setFairValueHigh(new BigDecimal("150"));
        when(latestValRepo.findByFigiIn(anyCollection())).thenReturn(List.of(vHold, vWatch));

        StockFinancialPeriod pHold = new StockFinancialPeriod();
        pHold.setFigi("F_ARM_H");
        pHold.setReportingCurrency("USD");
        pHold.setPeriodStart(LocalDate.of(2025, 10, 1));
        pHold.setPeriodEnd(LocalDate.of(2026, 9, 30));
        pHold.setNetIncomeCommonTtm(new BigDecimal("2000000000"));
        pHold.setNetIncomeCommonTtmStatus("AVAILABLE");
        StockFinancialPeriod pWatch = new StockFinancialPeriod();
        pWatch.setFigi("F_ARM_W");
        pWatch.setReportingCurrency("USD");
        pWatch.setPeriodStart(LocalDate.of(2025, 10, 1));
        pWatch.setPeriodEnd(LocalDate.of(2026, 9, 30));
        pWatch.setNetIncomeCommonTtm(new BigDecimal("2000000000"));
        pWatch.setNetIncomeCommonTtmStatus("AVAILABLE");
        when(periodRepo.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(pHold, pWatch));

        StockEarningsForecast fHold = new StockEarningsForecast();
        fHold.setFigi("F_ARM_H");
        fHold.setSource("ALPHAVANTAGE");
        fHold.setPeriodType("ANNUAL");
        fHold.setFiscalPeriod("FY2027");
        fHold.setPeriodEnd(LocalDate.of(2027, 9, 30));
        fHold.setCurrency("USD");
        fHold.setEps(new BigDecimal("-0.50"));
        fHold.setObservationDate(DAY.minusDays(2));
        fHold.setObservedAt(NOW.minusSeconds(3600));
        StockEarningsForecast fWatch = new StockEarningsForecast();
        fWatch.setFigi("F_ARM_W");
        fWatch.setSource("ALPHAVANTAGE");
        fWatch.setPeriodType("ANNUAL");
        fWatch.setFiscalPeriod("FY2027");
        fWatch.setPeriodEnd(LocalDate.of(2027, 9, 30));
        fWatch.setCurrency("USD");
        fWatch.setEps(new BigDecimal("-0.50"));
        fWatch.setObservationDate(DAY.minusDays(2));
        fWatch.setObservedAt(NOW.minusSeconds(3600));
        when(forecastRepo.findVisibleForStocksBetween(anyCollection(), any(), any(), any())).thenReturn(List.of(fHold, fWatch));

        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(MarketRegime.RISK_ON, naMetric, naMetric, naMetric, DAY, NOW, List.of(), List.of());
        PreloadedMarketData preloaded = new PreloadedMarketData(
                DAY, NOW, null, null, Map.of(), List.of(),
                MetricResult.available(snap), MetricResult.available(new BigDecimal("15.2")));
        when(marketContextService.preloadMarketData(DAY, NOW)).thenReturn(preloaded);

        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            Stock st = inv.getArgument(3);
            NormalizedBarDto b = new NormalizedBarDto(DAY, new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"),
                    new BigDecimal("100"), MetricResult.available(BigDecimal.valueOf(1_000_000)), NOW, NOW, NOW, NOW, null, "B1");
            PriceBasisDescriptor desc = new PriceBasisDescriptor("B1", "STORED", PriceAdjustment.SPLIT_ADJUSTED,
                    DAY, null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, null);
            return MetricResult.available(new NormalizedPriceSeriesDto(
                    st.getFigi(), "DAILY", List.of(b), desc, null, MetricStatus.AVAILABLE, List.of(), "H1"));
        });
        when(priceStructures.analyze(any(), any())).thenAnswer(inv -> {
            NormalizedPriceSeriesDto s = inv.getArgument(1);
            AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("2"), new BigDecimal("0.02"), DAY, 14, 14);
            StructureTrendDto up = new StructureTrendDto(TrendState.UPTREND, NOW, PriceRelation.HH, PriceRelation.HL, List.of(), NOW);
            TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, s.bars(),
                    MetricResult.available(atr), List.of(), List.of(),
                    MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                    MetricResult.available(up), MetricResult.available(up), List.of(), List.of());
            return MetricResult.available(new PriceStructureDto(s.figi(), "B1", DAY, List.of(daily, weekly), List.of(), List.of()));
        });
        when(marketContextService.evaluateWithPreloaded(any(), any(), any(), any(), any(), any())).thenReturn(
                MetricResult.available(new MarketContextDto(
                        null, null, MetricResult.available(snap), List.of(),
                        new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()),
                        List.of(), List.of(),
                        new MomentumAnalysisDto(Map.of("SMA200", MetricResult.available(new BigDecimal("90"))), naMetric, List.of()),
                        null,
                        null, null, Map.of(), MetricResult.available(new BigDecimal("15.2")))));

        LevelZoneDto sup = new LevelZoneDto("Z_ARM", AnalysisTimeframe.WEEKLY, new BigDecimal("98"), new BigDecimal("101"),
                ZoneRole.SUPPORT, new BigDecimal("78"), NOW, 3, NOW, ZoneState.ACTIVE,
                new BigDecimal("99.5"), new BigDecimal("3"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("78"),
                1, NOW, null, List.of(), List.of(), 3, 0, 2, BigDecimal.ZERO, BigDecimal.ZERO, RetestState.NONE, true, List.of());
        when(supportResistanceService.analyze(any(), any(), any(), any())).thenReturn(
                MetricResult.available(new SupportResistanceDto(
                        List.of(sup), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of())));
        TradePlanDto activePlan = new TradePlanDto(
                "P_ARM", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("98"), new BigDecimal("101"), "B1"),
                EntryMethod.ZONE_LIMIT, "trigger",
                new StopRuleDto(new BigDecimal("94.00"), new BigDecimal("95.00"), 2, AnalysisTimeframe.DAILY,
                        new BigDecimal("0.5"), new BigDecimal("3"), AnalysisTimeframe.WEEKLY, NOW, List.of(), new BigDecimal("3")),
                List.of(new TargetDto(new BigDecimal("130"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "r1", List.of())),
                MetricResult.available(new RiskRewardDto(new BigDecimal("100"), new BigDecimal("94"),
                        new BigDecimal("6"), new BigDecimal("30"), new BigDecimal("5.0"),
                        new BigDecimal("6"), new BigDecimal("30"), new BigDecimal("5.0"),
                        new BigDecimal("30"), new BigDecimal("6"))),
                TradePlanStatus.ACTIVE, List.of(), "Z_ARM", 1, AnalysisTimeframe.WEEKLY, NOW, null, List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any())).thenReturn(
                MetricResult.available(new TradePlanResultDto(List.of(activePlan), new BigDecimal("100"), DAY,
                        activePlan.riskReward(), List.of(), List.of(), true)));

        PortfolioHealthSummaryDto summary = adrHealthService.evaluate(DAY, NOW, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT).summary();
        assertThat(summary.holdings()).hasSize(1);
        assertThat(summary.holdings().getFirst().stance()).isEqualTo("CAUTION_VALUE_TRAP");
        assertThat(summary.holdings().getFirst().alerts().stream().map(PortfolioAlertDto::alertCode))
                .contains(PortfolioHealthCheckService.ALERT_HOLDING_VALUE_TRAP);
        assertThat(summary.watchlistOpportunities()).isEmpty();
    }

    /**
     * Scenario：持倉板塊廣度崩壞（含未核實行情持倉仍須納入 positionIds 與 affectedSymbols、且與集中度超標雙重並存不同 dedupKey）
     * 及無狀態 S_0 通知抑制：
     * - 首次觸發發送 SECTOR_BREADTH:{D}:{ind}；
     * - 同波段滾動換倉（Day 1 [P1] -> 換成 [P2]）S_now 與 S_0 無交集時重新發送（防滾動交集鏈永久漏報）；
     * - 同波段僅缺日或低樣本列（< 8）不視為修復仍維持靜默，唯有 (T_0, D) 出現通過門檻且未崩壞日才重新觸發新波段。
     */
    @Test
    void sectorBreadthBreakdownEmitsHoldingAlertCoexistsWithConcentrationAndHandlesStatelessSuppression() {
        org.springframework.test.util.ReflectionTestUtils.setField(healthService, "sectorMoneyFlowRepository", sectorFlowRepo);

        StockIndustry semiInd = new StockIndustry();
        semiInd.setCode("I41");
        semiInd.setIndustry("Semiconductors");

        PortfolioItem nvdaItem = portfolioItem("F_NVDA", PortfolioItem.TYPE_HOLDING, "400", "90", PRIOR_DAY);
        nvdaItem.setPositionId("POS-P2");
        PortfolioItem amdMissingQuote = portfolioItem("F_AMD", PortfolioItem.TYPE_HOLDING, "100", "100", PRIOR_DAY);
        amdMissingQuote.setPositionId("POS-P3");

        when(itemRepo.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc()).thenReturn(List.of(nvdaItem, amdMissingQuote));
        when(stockRepo.findAllById(anyCollection())).thenReturn(List.of(
                stock("F_NVDA", "NVDA", "USD"),
                stock("F_AMD", "AMD", "USD")));
        when(statusRepo.findAllById(anyCollection())).thenReturn(List.of(splitStatus("F_NVDA"), splitStatus("F_AMD")));
        when(splitRepo.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        when(eventRepo.findVisibleEarningsForFigis(anyCollection(), any())).thenReturn(List.of());
        when(latestValRepo.findByFigiIn(anyCollection())).thenReturn(List.of(
                valLatest("F_NVDA", "FAIR_LOWER", "STRONG", "100")));
        // NVDA 有當日行情（市值 40,000 > 30% 上限），AMD 僅有昨日行情（掉入 unverifiedHoldingFigis）
        when(quoteRepo.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(List.of(
                barRow("F_NVDA", DAY, "100"),
                barRow("F_AMD", PRIOR_DAY, "100")));

        StockIndustryHistory hNvda = new StockIndustryHistory();
        hNvda.setFigi("F_NVDA");
        hNvda.setIndustry(semiInd);
        StockIndustryHistory hAmd = new StockIndustryHistory();
        hAmd.setFigi("F_AMD");
        hAmd.setIndustry(semiInd);
        when(industryRepo.findByFigisAt(anyCollection(), any())).thenReturn(List.of(hNvda, hAmd));

        SectorMoneyFlowDaily brokenToday = new SectorMoneyFlowDaily();
        brokenToday.setTradeDate(DAY);
        brokenToday.setIndustryCode("I41");
        brokenToday.setIndustryName("Semiconductors");
        brokenToday.setStockCount(45);
        brokenToday.setBreadthSma50Total(45);
        brokenToday.setBreadthSma200Total(44);
        brokenToday.setReturn20dCount(45);
        brokenToday.setBreadthAboveSma50Pct(new BigDecimal("0.155600"));
        brokenToday.setBreadthAboveSma200Pct(new BigDecimal("0.318200"));
        brokenToday.setReturn20dMedian(new BigDecimal("-0.085000"));
        brokenToday.setCmf20d(new BigDecimal("-0.120000"));
        brokenToday.setFlowState(SectorMoneyFlowDaily.FLOW_NEUTRAL);
        when(sectorFlowRepo.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(DAY)).thenReturn(List.of(brokenToday));

        MarketSnapshotDto riskOnSnap = new MarketSnapshotDto(MarketRegime.RISK_ON,
                MetricResult.available(BigDecimal.valueOf(50)),
                MetricResult.available(BigDecimal.valueOf(50)),
                MetricResult.available(BigDecimal.valueOf(50)),
                DAY, NOW, List.of(), List.of());
        when(marketContextService.preloadMarketData(any(), any())).thenReturn(new PreloadedMarketData(
                DAY, NOW, MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(), MetricResult.available(riskOnSnap),
                MetricResult.available(new BigDecimal("16.0")), null));
        when(quoteNorm.normalizeFromLoaded(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()));

        // 1) 體檢結果同時包含 CONCENTRATION 與 BREADTH_BREAKDOWN，且缺行情的 AMD 仍在 affectedSymbols 與 positionIds 中
        PortfolioHealthSummaryDto health = healthService.evaluate(DAY, NOW, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT).summary();
        assertThat(health.sectorAlerts()).extracting(PortfolioAlertDto::alertCode).containsExactlyInAnyOrder(
                PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_CONCENTRATION,
                PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_BREADTH_BREAKDOWN);
        PortfolioAlertDto breadthAlert = health.sectorAlerts().stream()
                .filter(a -> PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_BREADTH_BREAKDOWN.equals(a.alertCode()))
                .findFirst().orElseThrow();
        assertThat(breadthAlert.payload().get("affectedSymbols")).isEqualTo(List.of("AMD", "NVDA"));
        assertThat(breadthAlert.payload().get("positionIds")).isEqualTo(List.of("POS-P2", "POS-P3"));
        assertThat(breadthAlert.payload().get("unverifiedHoldingCount")).isEqualTo(1);

        // 2) 收盤通知：前一次 T_0 通知僅含 POS-P1（與今日 [POS-P2, POS-P3] 無交集，模擬滾動換倉後原倉已全清），且中間無修復日 -> 仍應重新發送且與集中度並存
        DailyOpportunityRadarRun run = new DailyOpportunityRadarRun();
        run.setRadarDate(DAY);
        run.setMarketRegime("RISK_ON");
        when(radarRunRepo.findById(DAY)).thenReturn(Optional.of(run));
        when(briefingRepo.findById(DAY)).thenReturn(Optional.empty());
        when(briefingRepo.findTopByTradeDateBeforeOrderByTradeDateDesc(DAY)).thenReturn(Optional.empty());
        when(radarRepo.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(DAY)).thenReturn(List.of());
        when(notifRepo.findExistingDedupKeys(anyCollection())).thenReturn(List.of());

        InAppNotification priorT0 = new InAppNotification();
        priorT0.setTradeDate(DAY.minusDays(5));
        priorT0.setAlertCode(PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_BREADTH_BREAKDOWN);
        priorT0.setPayload(Map.of("industryCode", "I41", "positionIds", List.of("POS-P1")));
        when(notifRepo.findTopByAlertCodeAndTradeDateLessThanAndDedupKeyEndingWithOrderByTradeDateDescIdDesc(
                PortfolioHealthCheckService.ALERT_HOLDING_SECTOR_BREADTH_BREAKDOWN, DAY, ":I41"))
                .thenReturn(Optional.of(priorT0));
        when(sectorFlowRepo.findByIndustryCodeAndTradeDateGreaterThanAndTradeDateLessThanOrderByTradeDateAsc(
                "I41", DAY.minusDays(5), DAY)).thenReturn(List.of());

        List<InAppNotification> persistedNotifs = new ArrayList<>();
        doAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof InAppNotification n) persistedNotifs.add(n);
            return null;
        }).when(entityManager).persist(any());

        closingService.publishDailyClosing(DAY, NOW);
        assertThat(persistedNotifs).extracting(InAppNotification::getDedupKey).contains(
                "SECTOR:" + DAY + ":I41",
                "SECTOR_BREADTH:" + DAY + ":I41");
        // 完全換倉命中時應直接放行，無需再查區間板塊修復列
        org.mockito.Mockito.verify(sectorFlowRepo, org.mockito.Mockito.never())
                .findByIndustryCodeAndTradeDateGreaterThanAndTradeDateLessThanOrderByTradeDateAsc(any(), any(), any());

        // 3) 若 T_0 已包含 POS-P2，且中間僅有樣本不足列（stockCount=5 < 8），則不計為確認修復 -> 靜默不重複推送 SECTOR_BREADTH
        persistedNotifs.clear();
        priorT0.setPayload(Map.of("industryCode", "I41", "positionIds", List.of("POS-P2")));
        SectorMoneyFlowDaily ineligibleMid = new SectorMoneyFlowDaily();
        ineligibleMid.setTradeDate(DAY.minusDays(2));
        ineligibleMid.setIndustryCode("I41");
        ineligibleMid.setStockCount(5);
        ineligibleMid.setBreadthSma50Total(5);
        ineligibleMid.setBreadthSma200Total(5);
        ineligibleMid.setReturn20dCount(5);
        ineligibleMid.setBreadthAboveSma50Pct(new BigDecimal("0.80"));
        ineligibleMid.setBreadthAboveSma200Pct(new BigDecimal("0.80"));
        ineligibleMid.setReturn20dMedian(new BigDecimal("0.05"));
        when(sectorFlowRepo.findByIndustryCodeAndTradeDateGreaterThanAndTradeDateLessThanOrderByTradeDateAsc(
                "I41", DAY.minusDays(5), DAY)).thenReturn(List.of(ineligibleMid));

        closingService.publishDailyClosing(DAY, NOW);
        assertThat(persistedNotifs).extracting(InAppNotification::getDedupKey)
                .contains("SECTOR:" + DAY + ":I41")
                .doesNotContain("SECTOR_BREADTH:" + DAY + ":I41");

        // 4) 若中間出現通過樣本門檻且未崩壞之確認修復日 -> 新波段重新推送 SECTOR_BREADTH
        persistedNotifs.clear();
        SectorMoneyFlowDaily recoveredMid = new SectorMoneyFlowDaily();
        recoveredMid.setTradeDate(DAY.minusDays(2));
        recoveredMid.setIndustryCode("I41");
        recoveredMid.setStockCount(45);
        recoveredMid.setBreadthSma50Total(45);
        recoveredMid.setBreadthSma200Total(44);
        recoveredMid.setReturn20dCount(45);
        recoveredMid.setBreadthAboveSma50Pct(new BigDecimal("0.42"));
        recoveredMid.setBreadthAboveSma200Pct(new BigDecimal("0.55"));
        recoveredMid.setReturn20dMedian(new BigDecimal("0.02"));
        when(sectorFlowRepo.findByIndustryCodeAndTradeDateGreaterThanAndTradeDateLessThanOrderByTradeDateAsc(
                "I41", DAY.minusDays(5), DAY)).thenReturn(List.of(recoveredMid));

        closingService.publishDailyClosing(DAY, NOW);
        assertThat(persistedNotifs).extracting(InAppNotification::getDedupKey)
                .contains("SECTOR_BREADTH:" + DAY + ":I41");

        // 5) 驗證收盤簡報 Top 5 雷達試算使用已載入之同日板塊快照套用 1/2 板塊縮倉（不新增 DB 欄位）
        DailyOpportunityRadar semiRadar = new DailyOpportunityRadar();
        semiRadar.setRadarDate(DAY);
        semiRadar.setTrackType(DailyOpportunityRadar.TRACK_VALUE_SUPPORT);
        semiRadar.setFigi("F_AVGO");
        semiRadar.setSymbol("AVGO");
        semiRadar.setStockName("Broadcom");
        semiRadar.setCurrency("USD");
        semiRadar.setIndustryCode("I41");
        semiRadar.setIndustryName("Semiconductors");
        semiRadar.setVerdict("DEEP_UNDERVALUED");
        semiRadar.setStance("ACCUMULATE_AT_SUPPORT");
        semiRadar.setClosePrice(new BigDecimal("100.00"));
        semiRadar.setPlanEntryLower(new BigDecimal("98.00"));
        semiRadar.setPlanEntryUpper(new BigDecimal("100.00"));
        semiRadar.setProtectiveStop(new BigDecimal("95.00"));
        semiRadar.setPlanStatus("ACTIVE");
        semiRadar.setMacroCautionActive(false);
        when(radarRepo.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(DAY))
                .thenReturn(List.of(semiRadar));

        DailyClosingBriefingDto briefingWithRadar = closingService.publishDailyClosing(DAY, NOW).orElseThrow();
        assertThat(briefingWithRadar.radarTop5()).hasSize(1);
        assertThat(briefingWithRadar.radarTop5().getFirst().suggestedShares()).isEqualTo(75L);
        assertThat(briefingWithRadar.radarTop5().getFirst().sizingTags())
                .contains(com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto.TAG_SECTOR_BREADTH_HEADWIND_HALF_SIZE);
    }

    private PortfolioItem portfolioItem(String figi, String type, String shares, String avgCost, LocalDate basisDate) {
        PortfolioItem item = new PortfolioItem();
        item.setFigi(figi);
        item.setItemType(type);
        item.setShares(shares == null ? null : new BigDecimal(shares));
        item.setAvgCost(avgCost == null ? null : new BigDecimal(avgCost));
        item.setCostBasisDate(basisDate);
        item.setPositionId(PortfolioItem.TYPE_HOLDING.equals(type) ? "POS-" + figi : null);
        item.setCreatedAt(NOW.minusSeconds(86400));
        item.setUpdatedAt(NOW.minusSeconds(86400));
        return item;
    }

    private Stock stock(String figi, String symbol, String currency) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setLongName(symbol + " Corp");
        s.setCurrency(currency);
        s.setStockType("Common Stock");
        return s;
    }

    private StockStatus splitStatus(String figi) {
        StockStatus st = new StockStatus();
        st.setFigi(figi);
        st.setSplitFromDate(LocalDate.of(2020, 1, 1));
        st.setSplitDate(DAY);
        return st;
    }

    private QuoteBarRow barRow(String figi, LocalDate date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new QuoteBarRow(figi, date, c, c, c, c, BigDecimal.valueOf(1_000_000), NOW);
    }

    private StockValuationLatest valLatest(String figi, String verdict, String backing, String close) {
        StockValuationLatest v = new StockValuationLatest();
        v.setFigi(figi);
        v.setValuationDate(DAY);
        v.setVerdict(verdict);
        v.setFundamentalBacking(backing);
        v.setClosePrice(new BigDecimal(close));
        v.setFairValueLow(new BigDecimal("80"));
        v.setFairValueMid(new BigDecimal("100"));
        v.setFairValueHigh(new BigDecimal("40"));
        v.setModelCode("NON_FINANCIAL_OPERATING");
        return v;
    }
}
