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
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.HoldingHealthItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioAlertDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.entity.DailyClosingBriefing;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.InAppNotification;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
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
