package com.ykm.stock.analytics.service.report;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosis;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DrawdownAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryMethod;
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
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRangeDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRelation;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceSeriesCoverageDto;
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
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TreasuryContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TrendState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingResponse;
import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.PortfolioItem;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.AdrShareRatioRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioItemRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository.SectorCandidateMeta;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
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
import com.ykm.stock.analytics.service.market.FxRateService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService;
import com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.PortfolioExposureSnapshot;
import com.ykm.stock.analytics.service.report.BuyRankingReportService.CachedReportSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyRankingReportServiceTests {
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 19);
    private static final Instant NOW = Instant.parse("2026-11-20T04:00:00Z");
    private static final Instant SESSION_CLOSE = Instant.parse("2026-11-19T21:00:00Z");

    private StockRepository stockRepository;
    private StockStatusRepository stockStatusRepository;
    private StockValuationLatestRepository latestValuations;
    private StockIndustryRepository industryRepository;
    private StockIndustryHistoryRepository industryHistoryRepository;
    private PortfolioItemRepository portfolioItemRepository;
    private SectorMoneyFlowDailyRepository sectorMoneyFlowRepository;
    private StockQuoteRepository quoteRepository;
    private StockSplitRepository splitRepository;
    private MarketEventRepository marketEventRepository;
    private StockEarningsForecastRepository forecastRepository;
    private StockFinancialPeriodRepository financialPeriodRepository;
    private FinancialReportRepository financialReportRepository;
    private AdrShareRatioRepository adrShareRatioRepository;
    private MarketContextService marketContextService;
    private PortfolioManagementService portfolioManagementService;
    private QuoteNormalizationService quoteNormalizationService;
    private PriceStructureService priceStructureService;
    private SupportResistanceService supportResistanceService;
    private TradePlanService tradePlanService;
    private FxRateService fxRateService;
    private MarketStatusService calendar;
    private DateTimeService dateTimeService;
    private BuyRankingReportService service;

    @BeforeEach
    void setUp() {
        stockRepository = mock(StockRepository.class);
        stockStatusRepository = mock(StockStatusRepository.class);
        latestValuations = mock(StockValuationLatestRepository.class);
        industryRepository = mock(StockIndustryRepository.class);
        industryHistoryRepository = mock(StockIndustryHistoryRepository.class);
        portfolioItemRepository = mock(PortfolioItemRepository.class);
        sectorMoneyFlowRepository = mock(SectorMoneyFlowDailyRepository.class);
        quoteRepository = mock(StockQuoteRepository.class);
        splitRepository = mock(StockSplitRepository.class);
        marketEventRepository = mock(MarketEventRepository.class);
        forecastRepository = mock(StockEarningsForecastRepository.class);
        financialPeriodRepository = mock(StockFinancialPeriodRepository.class);
        financialReportRepository = mock(FinancialReportRepository.class);
        adrShareRatioRepository = mock(AdrShareRatioRepository.class);
        marketContextService = mock(MarketContextService.class);
        portfolioManagementService = mock(PortfolioManagementService.class);
        quoteNormalizationService = mock(QuoteNormalizationService.class);
        priceStructureService = mock(PriceStructureService.class);
        supportResistanceService = mock(SupportResistanceService.class);
        tradePlanService = mock(TradePlanService.class);
        fxRateService = mock(FxRateService.class);
        calendar = mock(MarketStatusService.class);
        dateTimeService = mock(DateTimeService.class);

        when(dateTimeService.now()).thenReturn(NOW);
        when(calendar.getLastClosedTradingDay(NOW)).thenReturn(AS_OF);
        when(calendar.getSessionCloseAt(AS_OF)).thenReturn(SESSION_CLOSE);

        StockIndustry semi = new StockIndustry();
        semi.setCode("SEMI");
        semi.setIndustry("Semiconductors");
        when(industryRepository.findAll()).thenReturn(List.of(semi));
        when(sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(AS_OF)).thenReturn(List.of());
        when(sectorMoneyFlowRepository.findLatestTradeDateAtOrBefore(AS_OF)).thenReturn(Optional.empty());

        PreloadedMarketData marketData = buildPreloadedMarket(MarketRegime.RISK_ON, new BigDecimal("16.50"));
        when(marketContextService.preloadMarketData(eq(AS_OF), eq(NOW))).thenReturn(marketData);
        PortfolioRiskConfigDto cfg = new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD");
        when(portfolioManagementService.loadExposureSnapshot(eq(AS_OF), eq(NOW)))
                .thenReturn(new PortfolioExposureSnapshot(cfg, Map.of(), Map.of(), Set.of(), 0, List.of()));

        when(stockStatusRepository.findAllById(any())).thenReturn(List.of());
        when(splitRepository.findByFigiInOrderByExecutionDateAsc(any())).thenReturn(List.of());
        when(marketEventRepository.findVisibleEarningsForFigis(any(), any())).thenReturn(List.of());
        when(forecastRepository.findVisibleForStocksBetween(any(), any(), any(), any())).thenReturn(List.of());
        when(financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(any())).thenReturn(List.of());
        when(financialReportRepository.findKnownReportPeriodsForFigis(any(), any())).thenReturn(List.of());
        when(adrShareRatioRepository.findBySymbolIn(any())).thenReturn(List.of());

        service = new BuyRankingReportService(
                stockRepository, stockStatusRepository, latestValuations, industryRepository, industryHistoryRepository,
                portfolioItemRepository, sectorMoneyFlowRepository, quoteRepository, splitRepository,
                marketEventRepository, forecastRepository, financialPeriodRepository, financialReportRepository,
                adrShareRatioRepository, marketContextService, portfolioManagementService, quoteNormalizationService,
                priceStructureService, supportResistanceService, tradePlanService, new PositionSizingCalculator(),
                fxRateService, calendar, dateTimeService, null);
    }

    @Test
    void testCase1ValueTrapIsInterceptedToTier4WhileQualitySupportRanksInTier1() {
        Stock stockA = stock("FIGI_A", "TRAPA", "Trap Corp", "Common Stock", "USD", true);
        Stock stockB = stock("FIGI_B", "QUALB", "Quality Semi", "Common Stock", "USD", true);
        StockValuationLatest valA = valuation("FIGI_A", "SEMI", new BigDecimal("60.00"),
                new BigDecimal("85.00"), new BigDecimal("100.00"), new BigDecimal("115.00"),
                "DEEP_UNDERVALUED", "WEAKENING");
        StockValuationLatest valB = valuation("FIGI_B", "SEMI", new BigDecimal("88.00"),
                new BigDecimal("90.00"), new BigDecimal("100.00"), new BigDecimal("110.00"),
                "DEEP_UNDERVALUED", "STRONG");

        when(industryHistoryRepository.findActiveTrackedCandidateMetaByIndustryCodesAt(eq(Set.of("SEMI")), eq(SESSION_CLOSE), eq(AS_OF)))
                .thenReturn(List.of(
                        new SectorCandidateMeta("FIGI_A", "Common Stock", "NON_FINANCIAL_OPERATING"),
                        new SectorCandidateMeta("FIGI_B", "Common Stock", "NON_FINANCIAL_OPERATING")));
        when(stockRepository.findAllById(any())).thenReturn(List.of(stockA, stockB));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valA, valB));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE)))
                .thenReturn(List.of(industryHistory("FIGI_A", "SEMI", "Semiconductors"),
                        industryHistory("FIGI_B", "SEMI", "Semiconductors")));
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_A", new BigDecimal("60.00")), quoteRow("FIGI_B", new BigDecimal("88.00"))));

        stubTechnicalStack("FIGI_A", new BigDecimal("60.00"), new BigDecimal("58.00"), new BigDecimal("61.00"),
                new BigDecimal("55.00"), new BigDecimal("76.00"), TradePlanStatus.WAITING_TRIGGER,
                BreakoutDiagnosis.HIGH_RISK_BREAKDOWN, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        stubTechnicalStack("FIGI_B", new BigDecimal("88.00"), new BigDecimal("86.00"), new BigDecimal("89.00"),
                new BigDecimal("82.00"), new BigDecimal("112.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_SECTOR, List.of("SEMI"), null, null, null,
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR), true,
                List.of(BuyRankingDtos.TIER_1, BuyRankingDtos.TIER_2, BuyRankingDtos.TIER_3, BuyRankingDtos.TIER_4),
                BuyRankingDtos.GRADE_ALL, null, null, BuyRankingDtos.SORT_TIER_COMPOSITE,
                "POSITION", "zh-HK", 1, 300);

        BuyRankingResponse resp = service.generateReport(req, "zh-HK");

        assertThat(resp.items()).hasSize(2);
        BuyRankingItemDto first = resp.items().get(0);
        BuyRankingItemDto second = resp.items().get(1);

        assertThat(first.symbol()).isEqualTo("QUALB");
        assertThat(first.tier()).isEqualTo(BuyRankingDtos.TIER_1);
        assertThat(first.valuationDiscountPct()).isEqualByComparingTo("-0.120000");
        assertThat(first.netRiskReward()).isGreaterThan(new BigDecimal("3.0"));
        assertThat(first.suggestedShares()).isGreaterThan(0L);

        assertThat(second.symbol()).isEqualTo("TRAPA");
        assertThat(second.tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(second.reasonCode()).isEqualTo("WEAKENING_AND_BREAKDOWN");
        assertThat(second.actionReason()).contains("基本面轉弱").contains("真破位");
        assertThat(second.suggestedShares()).isEqualTo(0L);
        assertThat(second.positionScaleTag()).isEqualTo("NOT_RECOMMENDED");
    }

    @Test
    void unverifiedAdrIsBlockedToTier4AndNeverRankedInTier1Or2() {
        Stock adr = stock("FIGI_ADR", "UNVER", "Unverified ADR", "ADR", "USD", true);
        StockValuationLatest val = valuation("FIGI_ADR", "SEMI", new BigDecimal("50.00"),
                new BigDecimal("60.00"), new BigDecimal("70.00"), new BigDecimal("80.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("UNVER"))))
                .thenReturn(List.of(adr));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(val));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_ADR", new BigDecimal("50.00"))));

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("UNVER"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, null, "zh-HK", 1, 300);

        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(resp.items().getFirst().reasonCode()).isEqualTo("ADR_RATIO_UNVERIFIED");
        assertThat(resp.items().getFirst().suggestedShares()).isEqualTo(0L);
    }

    @Test
    void etfUsesDedicatedTechnicalScoreAndIsNotKilledByMinFundamentalGrade() {
        Stock etf = stock("FIGI_ETF", "SMH", "VanEck Semiconductor ETF", "ETF", "USD", true);
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("SMH"))))
                .thenReturn(List.of(etf));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of());
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_ETF", new BigDecimal("240.00"))));

        stubTechnicalStack("FIGI_ETF", new BigDecimal("240.00"), new BigDecimal("235.00"), new BigDecimal("242.00"),
                new BigDecimal("228.00"), new BigDecimal("275.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("SMH"),
                List.of(BuyRankingDtos.ASSET_ETF), true,
                null, BuyRankingDtos.GRADE_STRONG, null, 14, null, "POSITION", "en", 1, 300);

        BuyRankingResponse resp = service.generateReport(req, "en");
        assertThat(resp.items()).hasSize(1);
        BuyRankingItemDto item = resp.items().getFirst();
        assertThat(item.assetType()).isEqualTo(BuyRankingDtos.ASSET_ETF);
        assertThat(item.tier()).isEqualTo(BuyRankingDtos.TIER_1);
        assertThat(item.tierScore()).isNotNull().isPositive();
        assertThat(item.reasonCode()).isEqualTo("ETF_BULLISH_SUPPORT_ACTIVE");
        assertThat(item.actionReason()).contains("ETF");
    }

    @Test
    void earningsDay0RoutesToTier3WhenNotExcludedAndRemovedWhenExcluded() {
        Stock s = stock("FIGI_E", "EARN", "Earnings Corp", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_E", "SEMI", new BigDecimal("88.00"),
                new BigDecimal("90.00"), new BigDecimal("100.00"), new BigDecimal("110.00"),
                "DEEP_UNDERVALUED", "STRONG");
        MarketEvent earningsToday = new MarketEvent();
        earningsToday.setFigi("FIGI_E");
        earningsToday.setEventType("EARNINGS");
        earningsToday.setEventDate(AS_OF);
        earningsToday.setStatus("SCHEDULED");

        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("EARN"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_E", new BigDecimal("88.00"))));
        when(marketEventRepository.findVisibleEarningsForFigis(anyCollection(), eq(NOW)))
                .thenReturn(List.of(earningsToday));
        stubTechnicalStack("FIGI_E", new BigDecimal("88.00"), new BigDecimal("86.00"), new BigDecimal("89.00"),
                new BigDecimal("82.00"), new BigDecimal("112.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest keepEarnings = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("EARN"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse respKeep = service.generateReport(keepEarnings, "zh-HK");
        assertThat(respKeep.items()).hasSize(1);
        assertThat(respKeep.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_3);
        assertThat(respKeep.items().getFirst().daysToEarnings()).isEqualTo(0);
        assertThat(respKeep.items().getFirst().reasonCode()).isEqualTo("EARNINGS_WITHIN_14D");

        BuyRankingRequest dropEarnings = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("EARN"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, 14, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse respDrop = service.generateReport(dropEarnings, "zh-HK");
        assertThat(respDrop.items()).isEmpty();
    }

    @Test
    void sectorBreadthBreakdownHalvesSharesOnlyWhenSameDayAndOfficialIndustryWhileStaleSectorFlowDoesNotHalve() {
        Stock s = stock("FIGI_NVDA", "NVDA", "NVIDIA", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_NVDA", "I41", new BigDecimal("100.00"),
                new BigDecimal("110.00"), new BigDecimal("130.00"), new BigDecimal("150.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("NVDA"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE)))
                .thenReturn(List.of(industryHistory("FIGI_NVDA", "I41", "Semiconductors")));
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_NVDA", new BigDecimal("100.00"))));
        stubTechnicalStack("FIGI_NVDA", new BigDecimal("100.00"), new BigDecimal("98.00"), new BigDecimal("100.00"),
                new BigDecimal("95.00"), new BigDecimal("125.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        SectorMoneyFlowDaily brokenSameDay = new SectorMoneyFlowDaily();
        brokenSameDay.setTradeDate(AS_OF);
        brokenSameDay.setIndustryCode("I41");
        brokenSameDay.setIndustryName("Semiconductors");
        brokenSameDay.setStockCount(45);
        brokenSameDay.setBreadthSma50Total(45);
        brokenSameDay.setBreadthSma200Total(44);
        brokenSameDay.setReturn20dCount(45);
        brokenSameDay.setBreadthAboveSma50Pct(new BigDecimal("0.15"));
        brokenSameDay.setBreadthAboveSma200Pct(new BigDecimal("0.30"));
        brokenSameDay.setReturn20dMedian(new BigDecimal("-0.08"));
        brokenSameDay.setFlowState(SectorMoneyFlowDaily.FLOW_OUTFLOW);
        when(sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(AS_OF))
                .thenReturn(List.of(brokenSameDay));

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("NVDA"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "en", 1, 300);
        BuyRankingResponse sameDayResp = service.generateReport(req, "en");
        assertThat(sameDayResp.items()).hasSize(1);
        BuyRankingItemDto sameDayItem = sameDayResp.items().getFirst();
        assertThat(sameDayItem.tier()).isEqualTo(BuyRankingDtos.TIER_1);
        assertThat(sameDayItem.positionScaleTag()).isEqualTo("1/2");
        assertThat(sameDayItem.positionSizingTags()).contains("SECTOR_BREADTH_HEADWIND_HALF_SIZE");
        assertThat(BuyRankingReasonFormatter.scaleTagLabel(sameDayItem.positionScaleTag(), sameDayItem.positionSizingTags(), "en"))
                .isEqualTo("1/2 Sector Cut");

        // 跨日回退之舊板塊快照僅供顯示，絕不觸發縮倉
        LocalDate priorDay = AS_OF.minusDays(1);
        SectorMoneyFlowDaily brokenStale = new SectorMoneyFlowDaily();
        brokenStale.setTradeDate(priorDay);
        brokenStale.setIndustryCode("I41");
        brokenStale.setIndustryName("Semiconductors");
        brokenStale.setStockCount(45);
        brokenStale.setBreadthSma50Total(45);
        brokenStale.setBreadthSma200Total(44);
        brokenStale.setReturn20dCount(45);
        brokenStale.setBreadthAboveSma50Pct(new BigDecimal("0.15"));
        brokenStale.setBreadthAboveSma200Pct(new BigDecimal("0.30"));
        brokenStale.setReturn20dMedian(new BigDecimal("-0.08"));
        when(sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(AS_OF))
                .thenReturn(List.of());
        when(sectorMoneyFlowRepository.findLatestTradeDateAtOrBefore(AS_OF)).thenReturn(Optional.of(priorDay));
        when(sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(priorDay))
                .thenReturn(List.of(brokenStale));

        BuyRankingResponse staleResp = service.generateReport(req, "en");
        BuyRankingItemDto staleItem = staleResp.items().getFirst();
        assertThat(staleItem.positionScaleTag()).isEqualTo("FULL");
        assertThat(staleItem.positionSizingTags()).doesNotContain("SECTOR_BREADTH_HEADWIND_HALF_SIZE");
        assertThat(staleItem.suggestedShares()).isEqualTo(sameDayItem.suggestedShares() * 2L);

        // 分類 ETF（具正式 StockIndustryHistory）與普通股／持倉體檢一致套用板塊半倉，未分類 ETF 維持 FULL
        Stock classifiedEtf = stock("FIGI_ETF_CLS", "SMHC", "Semi ETF Classified", "ETF", "USD", true);
        Stock unclassifiedEtf = stock("FIGI_ETF_RAW", "SMHU", "Semi ETF Unclassified", "ETP", "USD", true);
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("SMHC", "SMHU"))))
                .thenReturn(List.of(classifiedEtf, unclassifiedEtf));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE)))
                .thenReturn(List.of(industryHistory("FIGI_ETF_CLS", "I41", "Semiconductors")));
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(
                        quoteRow("FIGI_ETF_CLS", new BigDecimal("100.00")),
                        quoteRow("FIGI_ETF_RAW", new BigDecimal("100.00"))));
        stubTechnicalStack("FIGI_ETF_CLS", new BigDecimal("100.00"), new BigDecimal("98.00"), new BigDecimal("100.00"),
                new BigDecimal("95.00"), new BigDecimal("125.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        stubTechnicalStack("FIGI_ETF_RAW", new BigDecimal("100.00"), new BigDecimal("98.00"), new BigDecimal("100.00"),
                new BigDecimal("95.00"), new BigDecimal("125.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        when(sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(AS_OF))
                .thenReturn(List.of(brokenSameDay));

        BuyRankingRequest etfReq = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("SMHC", "SMHU"),
                List.of(BuyRankingDtos.ASSET_ETF), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "en", 1, 300);
        BuyRankingResponse etfResp = service.generateReport(etfReq, "en");
        BuyRankingItemDto clsItem = etfResp.items().stream().filter(i -> "SMHC".equals(i.symbol())).findFirst().orElseThrow();
        BuyRankingItemDto rawItem = etfResp.items().stream().filter(i -> "SMHU".equals(i.symbol())).findFirst().orElseThrow();
        assertThat(clsItem.positionScaleTag()).isEqualTo("1/2");
        assertThat(clsItem.positionSizingTags()).contains("SECTOR_BREADTH_HEADWIND_HALF_SIZE");
        assertThat(rawItem.positionScaleTag()).isEqualTo("FULL");
        assertThat(rawItem.positionSizingTags()).doesNotContain("SECTOR_BREADTH_HEADWIND_HALF_SIZE");
        assertThat(rawItem.suggestedShares()).isEqualTo(clsItem.suggestedShares() * 2L);
    }

    @Test
    void testCase3Enforces300CandidateCapBatchQueriesAndZeroExternalSyncDependency() {
        // 靜態結構斷言：BuyRankingReportService 嚴禁持有 AnnualEarningsForecastSyncService 或任何 AI Gateway 欄位
        for (Field field : BuyRankingReportService.class.getDeclaredFields()) {
            String typeName = field.getType().getSimpleName();
            assertThat(typeName).doesNotContain("AnnualEarningsForecastSyncService");
            assertThat(typeName).doesNotContain("AiGateway");
            assertThat(typeName).doesNotContain("AiClient");
        }

        List<SectorCandidateMeta> candidates = new ArrayList<>();
        Map<String, Stock> stockMap = new java.util.HashMap<>();
        for (int i = 1; i <= 305; i++) {
            String figi = String.format("FIGI_%03d", i);
            String sym = String.format("SYM%03d", i);
            candidates.add(new SectorCandidateMeta(figi, "Common Stock", "NON_FINANCIAL_OPERATING"));
            stockMap.put(figi, stock(figi, sym, "Stock " + i, "Common Stock", "USD", true));
        }
        when(industryHistoryRepository.findActiveTrackedCandidateMetaByIndustryCodesAt(eq(Set.of("SEMI")), eq(SESSION_CLOSE), eq(AS_OF)))
                .thenReturn(candidates);
        when(stockRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<String> ids = inv.getArgument(0);
            List<Stock> out = new ArrayList<>();
            for (String id : ids) {
                if (stockMap.containsKey(id)) out.add(stockMap.get(id));
            }
            return out;
        });
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of());
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF))).thenReturn(List.of());

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_SECTOR, List.of("SEMI"), null, null, null,
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);

        BuyRankingResponse resp = service.generateReport(req, "zh-HK");

        assertThat(resp.scopeSummary().matchedTotalCount()).isEqualTo(305);
        assertThat(resp.scopeSummary().evaluatedCount()).isEqualTo(300);
        assertThat(resp.scopeSummary().truncated()).isTrue();
        // 300 檔分批（每批 150 檔）最多 2 次 IN (...) 查詢，零 N+1
        verify(quoteRepository, atMost(2)).findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF));
        verify(latestValuations, atMost(2)).findByFigiIn(anyCollection());
    }

    @Test
    void snapshotByReportIdPreservesExactSharesEvenAfterRiskConfigChanges() {
        Stock s = stock("FIGI_S", "NVDA", "NVIDIA", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_S", "SEMI", new BigDecimal("130.00"),
                new BigDecimal("132.00"), new BigDecimal("150.00"), new BigDecimal("170.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("NVDA"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_S", new BigDecimal("130.00"))));
        stubTechnicalStack("FIGI_S", new BigDecimal("130.00"), new BigDecimal("128.00"), new BigDecimal("131.00"),
                new BigDecimal("121.00"), new BigDecimal("165.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("NVDA"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 50);

        BuyRankingResponse queryResp = service.generateReport(req, "zh-HK");
        Long originalShares = queryResp.items().getFirst().suggestedShares();

        // 模擬查詢後使用者修改總資金，以同 reportId 匯出應保持畫面原股數 100% 一致
        PortfolioRiskConfigDto doubled = new PortfolioRiskConfigDto(
                new BigDecimal("200000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD");
        when(portfolioManagementService.loadExposureSnapshot(eq(AS_OF), eq(NOW)))
                .thenReturn(new PortfolioExposureSnapshot(doubled, Map.of(), Map.of(), Set.of(), 0, List.of()));

        CachedReportSnapshot snapshot = service.resolveForExport(queryResp.reportId(), null, "zh-HK");
        assertThat(snapshot.fullSortedItems().getFirst().suggestedShares()).isEqualTo(originalShares);

        // 過期 reportId 回傳 410 GONE
        assertThatThrownBy(() -> service.resolveForExport("non-existent-id", null, "zh-HK"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("REPORT_SNAPSHOT_EXPIRED");
    }

    @Test
    void tier2SortsAscendingByDistanceToBuyZoneAndShakeoutRoutesToTier3() {
        Stock farStock = stock("FIGI_FAR", "FAR", "Far From Zone", "Common Stock", "USD", true);
        Stock nearStock = stock("FIGI_NEAR", "NEAR", "Near Zone", "Common Stock", "USD", true);
        Stock shakeStock = stock("FIGI_SHK", "SHK", "Shakeout Stock", "Common Stock", "USD", true);

        StockValuationLatest valFar = valuation("FIGI_FAR", "SEMI", new BigDecimal("115.00"),
                new BigDecimal("120.00"), new BigDecimal("140.00"), new BigDecimal("160.00"),
                "DEEP_UNDERVALUED", "STRONG");
        StockValuationLatest valNear = valuation("FIGI_NEAR", "SEMI", new BigDecimal("104.00"),
                new BigDecimal("105.00"), new BigDecimal("115.00"), new BigDecimal("125.00"),
                "MODERATELY_UNDERVALUED", "STRONG");
        StockValuationLatest valShake = valuation("FIGI_SHK", "SEMI", new BigDecimal("88.00"),
                new BigDecimal("95.00"), new BigDecimal("110.00"), new BigDecimal("125.00"),
                "DEEP_UNDERVALUED", "STRONG");

        when(industryHistoryRepository.findActiveTrackedCandidateMetaByIndustryCodesAt(eq(Set.of("SEMI")), eq(SESSION_CLOSE), eq(AS_OF)))
                .thenReturn(List.of(
                        new SectorCandidateMeta("FIGI_FAR", "Common Stock", "NON_FINANCIAL_OPERATING"),
                        new SectorCandidateMeta("FIGI_NEAR", "Common Stock", "NON_FINANCIAL_OPERATING"),
                        new SectorCandidateMeta("FIGI_SHK", "Common Stock", "NON_FINANCIAL_OPERATING")));
        when(stockRepository.findAllById(any())).thenReturn(List.of(farStock, nearStock, shakeStock));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valFar, valNear, valShake));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(
                        quoteRow("FIGI_FAR", new BigDecimal("115.00")),
                        quoteRow("FIGI_NEAR", new BigDecimal("104.00")),
                        quoteRow("FIGI_SHK", new BigDecimal("88.00"))));

        // FAR: 價 115, 買區 [96, 100] -> 距買區 +15%
        stubTechnicalStack("FIGI_FAR", new BigDecimal("115.00"), new BigDecimal("96.00"), new BigDecimal("100.00"),
                new BigDecimal("91.00"), new BigDecimal("145.00"), TradePlanStatus.WAITING_TRIGGER,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        // NEAR: 價 104, 買區 [96, 100] -> 距買區 +4%（應排在 FAR 前面）
        stubTechnicalStack("FIGI_NEAR", new BigDecimal("104.00"), new BigDecimal("96.00"), new BigDecimal("100.00"),
                new BigDecimal("91.00"), new BigDecimal("140.00"), TradePlanStatus.WAITING_TRIGGER,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        // SHK: 同區 SUSPECTED_SHAKEOUT -> Tier 3（不得誤入 Tier 4）
        stubTechnicalStack("FIGI_SHK", new BigDecimal("88.00"), new BigDecimal("86.00"), new BigDecimal("89.00"),
                new BigDecimal("82.00"), new BigDecimal("115.00"), TradePlanStatus.WAITING_TRIGGER,
                BreakoutDiagnosis.SUSPECTED_SHAKEOUT, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_SECTOR, List.of("SEMI"), null, null, null,
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, BuyRankingDtos.SORT_TIER_COMPOSITE,
                "POSITION", "zh-HK", 1, 300);

        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(3);
        assertThat(resp.items().get(0).symbol()).isEqualTo("NEAR");
        assertThat(resp.items().get(0).tier()).isEqualTo(BuyRankingDtos.TIER_2);
        assertThat(resp.items().get(1).symbol()).isEqualTo("FAR");
        assertThat(resp.items().get(1).tier()).isEqualTo(BuyRankingDtos.TIER_2);
        assertThat(resp.items().get(0).distanceToBuyZonePct()).isLessThan(resp.items().get(1).distanceToBuyZonePct());
        assertThat(resp.items().get(2).symbol()).isEqualTo("SHK");
        assertThat(resp.items().get(2).tier()).isEqualTo(BuyRankingDtos.TIER_3);
        assertThat(resp.items().get(2).reasonCode()).isEqualTo("SUSPECTED_SHAKEOUT_OBSERVING");
    }

    @Test
    void correctionRegimeRoutesUnconfirmedToTier3AndUnverifiedHoldingBlocksSizing() {
        PreloadedMarketData corrMarket = buildPreloadedMarket(MarketRegime.CORRECTION, new BigDecimal("22.50"));
        when(marketContextService.preloadMarketData(eq(AS_OF), eq(NOW))).thenReturn(corrMarket);
        PortfolioRiskConfigDto cfg = new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD");
        when(portfolioManagementService.loadExposureSnapshot(eq(AS_OF), eq(NOW)))
                .thenReturn(new PortfolioExposureSnapshot(cfg, Map.of(), Map.of(), Set.of("FIGI_CORR"), 0, List.of()));

        Stock s = stock("FIGI_CORR", "CORR", "Correction Corp", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_CORR", "SEMI", new BigDecimal("88.00"),
                new BigDecimal("92.00"), new BigDecimal("105.00"), new BigDecimal("118.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("CORR"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_CORR", new BigDecimal("88.00"))));

        stubTechnicalStack("FIGI_CORR", new BigDecimal("88.00"), new BigDecimal("86.00"), new BigDecimal("89.00"),
                new BigDecimal("82.00"), new BigDecimal("112.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.CORRECTION, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("CORR"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);

        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(1);
        BuyRankingItemDto item = resp.items().getFirst();
        assertThat(item.tier()).isEqualTo(BuyRankingDtos.TIER_3);
        assertThat(item.reasonCode()).isEqualTo("MARKET_CORRECTION_WAIT_CONFIRMATION");
        assertThat(item.suggestedShares()).isNull();
        assertThat(item.positionSizingStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(item.positionSizingReasonCode()).isEqualTo("EXISTING_HOLDING_VALUE_UNVERIFIED");
    }

    @Test
    void multiShareClassUnverifiedAndBlankReportingCurrencyAreInterceptedToTier4() {
        Stock sClass = stock("FIGI_SC", "GOOGC", "Multi Class Co", "Common Stock", "USD", true);
        StockValuationLatest vClass = valuation("FIGI_SC", "SEMI", new BigDecimal("100.00"),
                new BigDecimal("110.00"), new BigDecimal("125.00"), new BigDecimal("140.00"),
                "DEEP_UNDERVALUED", "STRONG");
        // PE 可用、總 reasonCode 為 null，但 PB 標記多股類經濟權未核實
        vClass.setReasonCode(null);
        vClass.setPbReason(ValuationReason.SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED);

        Stock sBlankFx = stock("FIGI_FX", "NOFX", "Blank Currency Co", "Common Stock", "USD", true);
        StockValuationLatest vBlankFx = valuation("FIGI_FX", "SEMI", new BigDecimal("80.00"),
                new BigDecimal("90.00"), new BigDecimal("105.00"), new BigDecimal("120.00"),
                "DEEP_UNDERVALUED", "STRONG");
        StockFinancialPeriod blankCurPeriod = new StockFinancialPeriod();
        blankCurPeriod.setFigi("FIGI_FX");
        blankCurPeriod.setPeriodEnd(AS_OF.minusDays(45));
        blankCurPeriod.setReportingCurrency("   ");
        blankCurPeriod.setFreeCashFlowTtmStatus("AVAILABLE");
        blankCurPeriod.setFreeCashFlowTtm(new BigDecimal("5000000000"));

        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("GOOGC", "NOFX"))))
                .thenReturn(List.of(sClass, sBlankFx));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(vClass, vBlankFx));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_SC", new BigDecimal("100.00")), quoteRow("FIGI_FX", new BigDecimal("80.00"))));
        when(financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection()))
                .thenReturn(List.of(blankCurPeriod));

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("GOOGC", "NOFX"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");

        assertThat(resp.items()).hasSize(2);
        Map<String, BuyRankingItemDto> bySym = new java.util.HashMap<>();
        for (BuyRankingItemDto it : resp.items()) {
            bySym.put(it.symbol(), it);
        }
        assertThat(bySym.get("GOOGC").tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(bySym.get("GOOGC").reasonCode()).isEqualTo("SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED");
        assertThat(bySym.get("GOOGC").suggestedShares()).isEqualTo(0L);
        assertThat(bySym.get("NOFX").tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(bySym.get("NOFX").reasonCode()).isEqualTo("REPORTING_CURRENCY_MISMATCH");
        assertThat(bySym.get("NOFX").suggestedShares()).isEqualTo(0L);
    }

    @Test
    void equityQuoteNormalizationFailureIsBlockedToTier4() {
        Stock s = stock("FIGI_BADQ", "BADQ", "Split Gap Corp", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_BADQ", "SEMI", new BigDecimal("90.00"),
                new BigDecimal("95.00"), new BigDecimal("110.00"), new BigDecimal("125.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("BADQ"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_BADQ", new BigDecimal("90.00"))));
        when(quoteNormalizationService.normalizeFromLoaded(any(AnalysisContext.class), any(), eq(AS_OF),
                eq(s), any(), any(), any()))
                .thenReturn(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                        AnalysisReasonDto.of("SPLIT_SYNC_RANGE_INCOMPLETE", "PRICE", "拆股資料未完整")));

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("BADQ"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(resp.items().getFirst().reasonCode()).isEqualTo("QUOTE_NORMALIZATION_FAILED");
        assertThat(resp.items().getFirst().suggestedShares()).isEqualTo(0L);
    }

    @Test
    void excludeReitsExcludesAdrTypedReitAcrossSectorPortfolioAndCustomScopes() {
        Stock adrReit = stock("FIGI_AR", "ADREIT", "ADR REIT Trust", "ADR", "USD", true);

        // 1. SECTOR scope
        when(industryHistoryRepository.findActiveTrackedCandidateMetaByIndustryCodesAt(eq(Set.of("SEMI")), eq(SESSION_CLOSE), eq(AS_OF)))
                .thenReturn(List.of(new SectorCandidateMeta("FIGI_AR", "ADR", "REIT")));
        BuyRankingRequest sectorReq = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_SECTOR, List.of("SEMI"), null, null, null,
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        assertThat(service.generateReport(sectorReq, "zh-HK").items()).isEmpty();

        // 2. PORTFOLIO scope
        PortfolioItem pi = new PortfolioItem();
        pi.setFigi("FIGI_AR");
        pi.setItemType(PortfolioItem.TYPE_WATCHLIST);
        StockValuationLatest valReit = valuation("FIGI_AR", "SEMI", new BigDecimal("50.00"),
                new BigDecimal("55.00"), new BigDecimal("60.00"), new BigDecimal("65.00"),
                "DEEP_UNDERVALUED", "STRONG");
        valReit.setModelCode("REIT");
        when(portfolioItemRepository.findAllByOrderByItemTypeAscUpdatedAtDescFigiAsc()).thenReturn(List.of(pi));
        when(stockRepository.findAllById(any())).thenReturn(List.of(adrReit));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valReit));
        BuyRankingRequest portReq = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_PORTFOLIO, null, null, List.of(PortfolioItem.TYPE_WATCHLIST), null,
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse portResp = service.generateReport(portReq, "zh-HK");
        assertThat(portResp.items()).isEmpty();
        assertThat(portResp.scopeSummary().excludedByAssetTypeSymbols()).containsExactly("ADREIT");

        // 3. CUSTOM scope
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("ADREIT"))))
                .thenReturn(List.of(adrReit));
        BuyRankingRequest customReq = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("ADREIT"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse customResp = service.generateReport(customReq, "zh-HK");
        assertThat(customResp.items()).isEmpty();
        assertThat(customResp.scopeSummary().excludedByAssetTypeSymbols()).containsExactly("ADREIT");
    }

    @Test
    void etfZoneAStaysTier1WhenOtherZoneBHasBreakdownAndHardStopNotMaskedByShakeout() {
        Stock etf = stock("FIGI_ETFA", "QQQ", "Invesco QQQ", "ETF", "USD", true);
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("QQQ"))))
                .thenReturn(List.of(etf));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of());
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_ETFA", new BigDecimal("500.00"))));

        stubTechnicalStack("FIGI_ETFA", new BigDecimal("500.00"), new BigDecimal("495.00"), new BigDecimal("502.00"),
                new BigDecimal("480.00"), new BigDecimal("560.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        // 覆寫 SupportResistanceDto：Zone A（Z_FIGI_ETFA）正常有效，另有 Zone B 破位作為 primaryDiagnosis
        LevelZoneDto zoneA = new LevelZoneDto(
                "Z_FIGI_ETFA", AnalysisTimeframe.WEEKLY, new BigDecimal("495.00"), new BigDecimal("502.00"),
                ZoneRole.SUPPORT, new BigDecimal("82"), SESSION_CLOSE.minusSeconds(864000),
                3, SESSION_CLOSE, ZoneState.ACTIVE, new BigDecimal("498.00"), new BigDecimal("4.0"), 1,
                ZoneStrengthLabel.HIGH, new BigDecimal("82"), 1, null, null,
                List.of(), List.of(), 3, 0, 2, BigDecimal.ZERO, BigDecimal.ZERO,
                RetestState.NONE, true, List.of(), null, List.of());
        BreakoutDiagnosisDto otherZoneBreakdown = new BreakoutDiagnosisDto(
                BreakoutDiagnosis.HIGH_RISK_BREAKDOWN, "Z_OTHER_B", 1, 1, AnalysisTimeframe.WEEKLY,
                new BigDecimal("510.00"), new BigDecimal("515.00"), AS_OF.minusDays(1), null, null, AS_OF, 1,
                new BigDecimal("2.0"), new BigDecimal("1.5"), new BigDecimal("0.1"),
                new BigDecimal("0.8"), new BigDecimal("0.1"), new BigDecimal("-0.04"),
                BigDecimal.ZERO, new BigDecimal("-0.04"), List.of(), List.of());
        SupportResistanceDto srWithOtherB = new SupportResistanceDto(
                List.of(zoneA), List.of(), List.of(), List.of(), List.of(), List.of(),
                otherZoneBreakdown, List.of(otherZoneBreakdown));
        when(supportResistanceService.analyze(any(), any(), any(), any()))
                .thenReturn(MetricResult.available(srWithOtherB));
        TargetDto etfTarget = new TargetDto(new BigDecimal("560.00"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "resistance", List.of());
        StopRuleDto etfStop = new StopRuleDto(new BigDecimal("480.00"), new BigDecimal("482.00"), 2, AnalysisTimeframe.DAILY,
                new BigDecimal("0.50"), new BigDecimal("4.0"), AnalysisTimeframe.WEEKLY, SESSION_CLOSE.minusSeconds(864000), List.of());
        TradePlanDto etfPlan = new TradePlanDto(
                "P_FIGI_ETFA", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("495.00"), new BigDecimal("502.00"), "b1"), EntryMethod.ZONE_LIMIT, "pullback",
                etfStop, List.of(etfTarget),
                MetricResult.available(new RiskRewardDto(new BigDecimal("500.00"), new BigDecimal("480.00"),
                        new BigDecimal("20"), new BigDecimal("60"), new BigDecimal("3.0"),
                        new BigDecimal("20.5"), new BigDecimal("59"), new BigDecimal("2.88"),
                        new BigDecimal("60"), new BigDecimal("20"))),
                TradePlanStatus.ACTIVE, List.of(), "Z_FIGI_ETFA", 1, AnalysisTimeframe.WEEKLY,
                SESSION_CLOSE.minusSeconds(864000), AS_OF.plusDays(5), List.of());
        when(tradePlanService.build(any(), any(), any(), eq(srWithOtherB), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(
                        List.of(etfPlan), new BigDecimal("500.00"), AS_OF, etfPlan.riskReward(),
                        List.of(), List.of(), true)));

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("QQQ"),
                List.of(BuyRankingDtos.ASSET_ETF), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_1);

        // 反方向：已確認跌破保護止損（price <= stopLoss）即使同區有 SUSPECTED_SHAKEOUT 亦必入 Tier 4
        Stock breached = stock("FIGI_BRK", "BRK1", "Breached Corp", "Common Stock", "USD", true);
        StockValuationLatest valBrk = valuation("FIGI_BRK", "SEMI", new BigDecimal("80.00"),
                new BigDecimal("95.00"), new BigDecimal("110.00"), new BigDecimal("125.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("BRK1"))))
                .thenReturn(List.of(breached));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valBrk));
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_BRK", new BigDecimal("80.00"))));
        stubTechnicalStack("FIGI_BRK", new BigDecimal("80.00"), new BigDecimal("86.00"), new BigDecimal("89.00"),
                new BigDecimal("82.00"), new BigDecimal("115.00"), TradePlanStatus.INVALIDATED,
                BreakoutDiagnosis.SUSPECTED_SHAKEOUT, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest reqBrk = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("BRK1"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse respBrk = service.generateReport(reqBrk, "zh-HK");
        assertThat(respBrk.items()).hasSize(1);
        assertThat(respBrk.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(respBrk.items().getFirst().reasonCode()).isEqualTo("STOP_BREACHED_INVALIDATED");
    }

    @Test
    void etfCmfReturnsNullWhenAnyBarLacksVolumeAndLargePageDoesNotOverflow() {
        List<NormalizedBarDto> bars = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            MetricResult<BigDecimal> vol = i == 5
                    ? MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of())
                    : MetricResult.available(new BigDecimal("1000000"));
            bars.add(new NormalizedBarDto(
                    AS_OF.minusDays(20 - i), new BigDecimal("100"), new BigDecimal("105"),
                    new BigDecimal("98"), new BigDecimal("104"), vol,
                    SESSION_CLOSE.minusSeconds(23400), SESSION_CLOSE, SESSION_CLOSE, NOW, "v1", "b1"));
        }
        assertThat(BuyRankingReportService.computeEtfCmf20d(bars)).isNull();

        // 大頁碼防整數溢位（page=7158280, pageSize=300）
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("NVDA"))))
                .thenReturn(List.of());
        BuyRankingRequest hugePageReq = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("NVDA"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 7_158_280, 300);
        BuyRankingResponse hugePageResp = service.generateReport(hugePageReq, "zh-HK");
        assertThat(hugePageResp.items()).isEmpty();
    }

    @Test
    void staleValuationDateFallbackFlagsMarketContextAndDisablesActionableSizing() {
        LocalDate staleDate = AS_OF.minusDays(2);
        Instant staleClose = Instant.parse("2026-11-17T21:00:00Z");
        when(latestValuations.findMaxValuationDateAtOrBefore(AS_OF)).thenReturn(Optional.of(staleDate));
        when(calendar.getSessionCloseAt(staleDate)).thenReturn(staleClose);
        PreloadedMarketData staleMarket = new PreloadedMarketData(staleDate, NOW,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(),
                MetricResult.available(new MarketSnapshotDto(MarketRegime.RISK_ON,
                        MetricResult.available(new BigDecimal("55")), MetricResult.available(new BigDecimal("55")),
                        MetricResult.available(new BigDecimal("55")), staleDate, staleClose, List.of(), List.of())),
                MetricResult.available(new BigDecimal("15.0")), null,
                new TreasuryContextDto(
                        MetricResult.available(new BigDecimal("4.50")),
                        MetricResult.available(new BigDecimal("4.20")),
                        MetricResult.available(new BigDecimal("0.35")),
                        MetricResult.available(new BigDecimal("0.30")),
                        List.of(), List.of()),
                staleDate);
        when(marketContextService.preloadMarketData(eq(staleDate), eq(NOW))).thenReturn(staleMarket);
        PortfolioRiskConfigDto cfg = new PortfolioRiskConfigDto(
                new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD");
        when(portfolioManagementService.loadExposureSnapshot(eq(staleDate), eq(NOW)))
                .thenReturn(new PortfolioExposureSnapshot(cfg, Map.of(), Map.of(), Set.of(), 0, List.of()));
        when(sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(staleDate)).thenReturn(List.of());

        Stock s = stock("FIGI_STALE", "STALE", "Stale Corp", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_STALE", "SEMI", new BigDecimal("88.00"),
                new BigDecimal("90.00"), new BigDecimal("100.00"), new BigDecimal("110.00"),
                "DEEP_UNDERVALUED", "STRONG");
        v.setValuationDate(staleDate);
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("STALE"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(staleClose))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(staleDate)))
                .thenReturn(List.of(new QuoteBarRow("FIGI_STALE", staleDate, new BigDecimal("88"), new BigDecimal("88"),
                        new BigDecimal("88"), new BigDecimal("88"), new BigDecimal("1000000"), NOW)));

        // 簡化 stub：讓 quoteNormalizationService 在 staleDate 回傳可用序列
        stubTechnicalStackAtDate("FIGI_STALE", staleDate, staleClose, new BigDecimal("88.00"),
                new BigDecimal("86.00"), new BigDecimal("89.00"), new BigDecimal("82.00"), new BigDecimal("112.00"),
                TradePlanStatus.ACTIVE, null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("STALE"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");

        assertThat(resp.asOfDate()).isEqualTo(staleDate);
        assertThat(resp.marketContext().lastClosedTradingDay()).isEqualTo(AS_OF);
        assertThat(resp.marketContext().staleDateFallback()).isTrue();
        // 10Y 20 期升幅 0.35 >= 0.30 應同步觸發市場背景宏觀警戒
        assertThat(resp.marketContext().macroCautionActive()).isTrue();
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().getFirst().positionSizingActionable()).isFalse();
        assertThat(resp.items().getFirst().positionSizingTags()).contains("STALE_QUOTE_DATE_FALLBACK");
    }

    @Test
    void tier2WithNonPullbackBlockingReasonRoutesToTier3() {
        Stock s = stock("FIGI_WAIT", "WAITC", "Wait Close Co", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_WAIT", "SEMI", new BigDecimal("108.00"),
                new BigDecimal("110.00"), new BigDecimal("125.00"), new BigDecimal("140.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("WAITC"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_WAIT", new BigDecimal("108.00"))));

        stubTechnicalStack("FIGI_WAIT", new BigDecimal("108.00"), new BigDecimal("96.00"), new BigDecimal("100.00"),
                new BigDecimal("91.00"), new BigDecimal("118.00"), TradePlanStatus.WAITING_TRIGGER,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        // 覆寫交易計畫，附帶 RR_BELOW_POLICY（除價高於買區外尚有非回踩阻擋原因，應入 Tier 3 而非 Tier 2）
        TargetDto tgt = new TargetDto(new BigDecimal("118.00"), BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "resistance", List.of());
        StopRuleDto stp = new StopRuleDto(new BigDecimal("91.00"), new BigDecimal("92.00"), 2, AnalysisTimeframe.DAILY,
                new BigDecimal("0.50"), new BigDecimal("3.0"), AnalysisTimeframe.WEEKLY, SESSION_CLOSE.minusSeconds(864000), List.of());
        TradePlanDto blockedPlan = new TradePlanDto(
                "P_FIGI_WAIT", AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(new BigDecimal("96.00"), new BigDecimal("100.00"), "b1"), EntryMethod.ZONE_LIMIT, "pullback",
                stp, List.of(tgt),
                MetricResult.available(new RiskRewardDto(new BigDecimal("100.00"), new BigDecimal("91.00"),
                        new BigDecimal("9"), new BigDecimal("18"), new BigDecimal("2.0"),
                        new BigDecimal("9.2"), new BigDecimal("17.5"), new BigDecimal("1.90"),
                        new BigDecimal("18"), new BigDecimal("9"))),
                TradePlanStatus.WAITING_TRIGGER,
                List.of(AnalysisReasonDto.of("ENTRY_PRICE_ABOVE_CAP", "TRADE_PLAN", "價高"),
                        AnalysisReasonDto.of("RR_BELOW_POLICY", "TRADE_PLAN", "RR < 2")),
                "Z_FIGI_WAIT", 1, AnalysisTimeframe.WEEKLY,
                SESSION_CLOSE.minusSeconds(864000), AS_OF.plusDays(5), List.of());
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.available(new TradePlanResultDto(
                        List.of(blockedPlan), new BigDecimal("108.00"), AS_OF, blockedPlan.riskReward(),
                        List.of(), List.of(), true)));

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("WAITC"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_3);
        assertThat(resp.items().getFirst().reasonCode()).isEqualTo("WAIT_SIGNAL_OR_CONFIRMATION");
    }

    @Test
    void missingTradePlanDoesNotEnterTier2AndHardStopBoundaryWithoutPlanBlocksShakeoutToTier4() {
        // 1) 無可用計畫（如缺週線 ATR）即使價高於支撐區上緣，也不得歸為 Tier 2「只等回踩」
        Stock noPlanStock = stock("FIGI_NOPLAN", "NOPLN", "No Plan Corp", "Common Stock", "USD", true);
        StockValuationLatest valNoPlan = valuation("FIGI_NOPLAN", "SEMI", new BigDecimal("104.00"),
                new BigDecimal("105.00"), new BigDecimal("115.00"), new BigDecimal("125.00"),
                "MODERATELY_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("NOPLN"))))
                .thenReturn(List.of(noPlanStock));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valNoPlan));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_NOPLAN", new BigDecimal("104.00"))));
        stubTechnicalStack("FIGI_NOPLAN", new BigDecimal("104.00"), new BigDecimal("96.00"), new BigDecimal("100.00"),
                new BigDecimal("91.00"), new BigDecimal("140.00"), TradePlanStatus.WAITING_TRIGGER,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()));

        BuyRankingRequest reqNoPlan = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("NOPLN"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse respNoPlan = service.generateReport(reqNoPlan, "zh-HK");
        assertThat(respNoPlan.items()).hasSize(1);
        assertThat(respNoPlan.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_3);
        assertThat(respNoPlan.items().getFirst().reasonCode()).isEqualTo("WAIT_SIGNAL_OR_CONFIRMATION");

        // 2) 等號邊界：支撐下緣 86、anchorAtr 3 -> 保護止損 84.50；收市價 84.50 且無可用計畫、同時有 SUSPECTED_SHAKEOUT -> 必入 Tier 4、0 股
        Stock edgeStop = stock("FIGI_EDGE", "EDGE", "Edge Stop Corp", "Common Stock", "USD", true);
        StockValuationLatest valEdge = valuation("FIGI_EDGE", "SEMI", new BigDecimal("84.50"),
                new BigDecimal("95.00"), new BigDecimal("110.00"), new BigDecimal("125.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("EDGE"))))
                .thenReturn(List.of(edgeStop));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valEdge));
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_EDGE", new BigDecimal("84.50"))));
        stubTechnicalStack("FIGI_EDGE", new BigDecimal("84.50"), new BigDecimal("86.00"), new BigDecimal("89.00"),
                new BigDecimal("84.50"), new BigDecimal("115.00"), TradePlanStatus.WAITING_TRIGGER,
                BreakoutDiagnosis.SUSPECTED_SHAKEOUT, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        when(tradePlanService.build(any(), any(), any(), any(), any()))
                .thenReturn(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()));

        BuyRankingRequest reqEdge = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("EDGE"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse respEdge = service.generateReport(reqEdge, "zh-HK");
        assertThat(respEdge.items()).hasSize(1);
        assertThat(respEdge.items().getFirst().tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(respEdge.items().getFirst().reasonCode()).isEqualTo("STOP_BREACHED_INVALIDATED");
        assertThat(respEdge.items().getFirst().suggestedShares()).isEqualTo(0L);
    }

    @Test
    void missingTargetDayQuoteClearsStalePriceAndExportRelocalizesFromBodyLocaleWithoutHeader() {
        Stock s = stock("FIGI_OLD", "OLDQ", "Old Quote Corp", "Common Stock", "USD", true);
        StockValuationLatest v = valuation("FIGI_OLD", "SEMI", new BigDecimal("66.00"),
                new BigDecimal("95.00"), new BigDecimal("110.00"), new BigDecimal("125.00"),
                "DEEP_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("OLDQ"))))
                .thenReturn(List.of(s));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        // 僅有前一交易日行情（缺 AS_OF 當日行情）
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(new QuoteBarRow("FIGI_OLD", AS_OF.minusDays(1),
                        new BigDecimal("66.00"), new BigDecimal("66.00"), new BigDecimal("66.00"),
                        new BigDecimal("66.00"), new BigDecimal("1000000"), NOW)));

        BuyRankingRequest reqEn = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("OLDQ"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "en", 1, 300);
        BuyRankingResponse respEn = service.generateReport(reqEn, "en");
        assertThat(respEn.items()).hasSize(1);
        BuyRankingItemDto item = respEn.items().getFirst();
        assertThat(item.tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(item.reasonCode()).isEqualTo("QUOTE_MISSING");
        assertThat(item.currentPrice()).isNull();
        assertThat(item.valuationDiscountPct()).isNull();

        // 僅以 body locale="zh-HK"（無 Accept-Language header）匯出原英文快照 -> 應重新本地化為 zh-HK
        BuyRankingRequest bodyOnlyLocale = new BuyRankingRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, "zh-HK", null, null);
        CachedReportSnapshot relocalized = service.resolveForExport(respEn.reportId(), bodyOnlyLocale, null);
        assertThat(relocalized.resolvedLocale()).isEqualTo("zh-HK");
        assertThat(relocalized.fullSortedItems().getFirst().actionReason()).contains("行情");
    }

    @Test
    void verifiedAdrComputesAlignedEpsForForwardTrapAndForwardPegAndAlignsAdjFcfToQuoteCurrency() {
        // 1) 已核實 USD 財報 ADR（ARM）：當期 TTM 淨利為正（epsAtDay = 2.00），估值偏低且在有效支撐買區（ACTIVE），
        //    但年度共識預估 FY1 EPS = -0.50（轉虧）且無 90D 修正 -> 必攔截為 TIER_4 (VALUE_TRAP_WARNING, 0 股)，不可誤進 TIER_1
        Stock arm = stock("FIGI_ARM", "ARM", "Arm Holdings ADR", "ADR", "USD", true);
        StockValuationLatest valArm = valuation("FIGI_ARM", "SEMI", new BigDecimal("100.00"),
                new BigDecimal("110.00"), new BigDecimal("130.00"), new BigDecimal("150.00"),
                "DEEP_UNDERVALUED", "STRONG");
        valArm.setMarketCap(new BigDecimal("100000000000"));
        AdrShareRatio armRatio = new AdrShareRatio();
        armRatio.setSymbol("ARM");
        armRatio.setAdrRatio(BigDecimal.ONE);
        armRatio.setVerified(true);

        StockFinancialPeriod armPeriod = new StockFinancialPeriod();
        armPeriod.setFigi("FIGI_ARM");
        armPeriod.setPeriodStart(LocalDate.of(2025, 10, 1));
        armPeriod.setPeriodEnd(LocalDate.of(2026, 9, 30));
        armPeriod.setReportingCurrency("USD");
        armPeriod.setNetIncomeCommonTtm(new BigDecimal("2000000000"));
        armPeriod.setNetIncomeCommonTtmStatus("AVAILABLE");
        armPeriod.setFreeCashFlowTtm(new BigDecimal("2500000000"));
        armPeriod.setFreeCashFlowTtmStatus("AVAILABLE");
        armPeriod.setSbcTtm(new BigDecimal("500000000"));

        StockEarningsForecast armFy1 = new StockEarningsForecast();
        armFy1.setFigi("FIGI_ARM");
        armFy1.setSource("ALPHAVANTAGE");
        armFy1.setPeriodType("ANNUAL");
        armFy1.setFiscalPeriod("FY2027");
        armFy1.setPeriodEnd(LocalDate.of(2027, 9, 30));
        armFy1.setCurrency("USD");
        armFy1.setEps(new BigDecimal("-0.50"));
        armFy1.setObservationDate(AS_OF.minusDays(2));
        armFy1.setObservedAt(SESSION_CLOSE.minusSeconds(3600));

        // 2) 已核實 TWD 財報 ADR（TSM）：netIncome = 1.28T TWD, adjFcf = 0.64T TWD, FX = 0.03125 USD/TWD
        //    -> netIncomeUsd = 40B, adjFcfUsd = 20B, marketCap = 1T, close = 200 -> shares = 5B, epsAtDay = 8.00
        //    FY1 EPS = 10.00 (+25% vs TTM, 無 FY2) -> forwardPe = 20.0000, forwardPeg = 0.8000, adjFcfTtm = 20B USD
        Stock tsm = stock("FIGI_TSM", "TSM", "TSMC ADR", "ADR", "USD", true);
        StockValuationLatest valTsm = valuation("FIGI_TSM", "SEMI", new BigDecimal("200.00"),
                new BigDecimal("210.00"), new BigDecimal("240.00"), new BigDecimal("270.00"),
                "MODERATELY_UNDERVALUED", "STRONG");
        valTsm.setMarketCap(new BigDecimal("1000000000000"));
        AdrShareRatio tsmRatio = new AdrShareRatio();
        tsmRatio.setSymbol("TSM");
        tsmRatio.setAdrRatio(new BigDecimal("5"));
        tsmRatio.setVerified(true);

        StockFinancialPeriod tsmPeriod = new StockFinancialPeriod();
        tsmPeriod.setFigi("FIGI_TSM");
        tsmPeriod.setPeriodStart(LocalDate.of(2025, 10, 1));
        tsmPeriod.setPeriodEnd(LocalDate.of(2026, 9, 30));
        tsmPeriod.setReportingCurrency("TWD");
        tsmPeriod.setNetIncomeCommonTtm(new BigDecimal("1280000000000"));
        tsmPeriod.setNetIncomeCommonTtmStatus("AVAILABLE");
        tsmPeriod.setFreeCashFlowTtm(new BigDecimal("700000000000"));
        tsmPeriod.setFreeCashFlowTtmStatus("AVAILABLE");
        tsmPeriod.setSbcTtm(new BigDecimal("60000000000"));

        StockEarningsForecast tsmFy1 = new StockEarningsForecast();
        tsmFy1.setFigi("FIGI_TSM");
        tsmFy1.setSource("FUTU");
        tsmFy1.setPeriodType("ANNUAL");
        tsmFy1.setFiscalPeriod("FY2027");
        tsmFy1.setPeriodEnd(LocalDate.of(2027, 9, 30));
        tsmFy1.setCurrency("USD");
        tsmFy1.setEps(new BigDecimal("10.00"));
        tsmFy1.setObservationDate(AS_OF.minusDays(2));
        tsmFy1.setObservedAt(SESSION_CLOSE.minusSeconds(3600));

        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(arm, tsm));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valArm, valTsm));
        when(adrShareRatioRepository.findBySymbolIn(anyCollection())).thenReturn(List.of(armRatio, tsmRatio));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(financialPeriodRepository.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection()))
                .thenReturn(List.of(armPeriod, tsmPeriod));
        when(forecastRepository.findVisibleForStocksBetween(anyCollection(), any(), any(), any()))
                .thenReturn(List.of(armFy1, tsmFy1));
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_ARM", new BigDecimal("100.00")),
                        quoteRow("FIGI_TSM", new BigDecimal("200.00"))));

        FxRateService.FxRateLookup fxLookup = mock(FxRateService.FxRateLookup.class);
        when(fxLookup.resolve(eq("TWD"), eq("USD"), eq(AS_OF)))
                .thenReturn(FxRateService.FxResolution.converted(new BigDecimal("0.03125"), "TWD", "USD", AS_OF, "FRED"));
        when(fxRateService.preload(anyCollection(), any(), any())).thenReturn(fxLookup);

        stubTechnicalStack("FIGI_ARM", new BigDecimal("100.00"), new BigDecimal("98.00"), new BigDecimal("101.00"),
                new BigDecimal("94.00"), new BigDecimal("130.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        stubTechnicalStack("FIGI_TSM", new BigDecimal("200.00"), new BigDecimal("196.00"), new BigDecimal("202.00"),
                new BigDecimal("188.00"), new BigDecimal("250.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("ARM", "TSM"),
                List.of(BuyRankingDtos.ASSET_ADR), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(2);

        BuyRankingItemDto armItem = resp.items().stream().filter(i -> "ARM".equals(i.symbol())).findFirst().orElseThrow();
        assertThat(armItem.tier()).isEqualTo(BuyRankingDtos.TIER_4);
        assertThat(armItem.reasonCode()).isEqualTo("VALUE_TRAP_WARNING");
        assertThat(armItem.suggestedShares()).isEqualTo(0L);

        BuyRankingItemDto tsmItem = resp.items().stream().filter(i -> "TSM".equals(i.symbol())).findFirst().orElseThrow();
        assertThat(tsmItem.tier()).isEqualTo(BuyRankingDtos.TIER_1);
        assertThat(tsmItem.forwardPe()).isEqualByComparingTo("20.0000");
        assertThat(tsmItem.forwardPeg()).isEqualByComparingTo("0.8000");
        // adjFcfTtm 必須對齊報價幣別 USD（640B TWD * 0.03125 = 20B USD），不可直接塞入原幣 TWD
        assertThat(tsmItem.adjFcfTtm()).isEqualByComparingTo("20000000000");
    }

    @Test
    void extractRegularBuyZonePreservesFirstTargetPriceWhenCurrentPriceExceedsFirstTarget() {
        Stock overTarget = stock("FIGI_OVERTGT", "OVRT", "Over Target Corp", "Common Stock", "USD", true);
        StockValuationLatest valOver = valuation("FIGI_OVERTGT", "SEMI", new BigDecimal("118.00"),
                new BigDecimal("100.00"), new BigDecimal("120.00"), new BigDecimal("135.00"),
                "FAIR_UPPER", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), eq(List.of("OVRT"))))
                .thenReturn(List.of(overTarget));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(valOver));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_OVERTGT", new BigDecimal("118.00"))));

        // 計畫買區 95..100、止損 90、第一目標價 115，而現價短線衝至 118（高於第一目標價 115）
        stubTechnicalStack("FIGI_OVERTGT", new BigDecimal("118.00"), new BigDecimal("95.00"), new BigDecimal("100.00"),
                new BigDecimal("90.00"), new BigDecimal("115.00"), TradePlanStatus.WAITING_TRIGGER,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("OVRT"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                null, BuyRankingDtos.GRADE_ALL, null, null, null, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");
        assertThat(resp.items()).hasSize(1);
        BuyRankingItemDto item = resp.items().getFirst();
        assertThat(item.firstTargetPrice()).isEqualByComparingTo("115.0000");
        assertThat(item.netRiskReward()).isNull();
        assertThat(item.planNetRiskReward()).isEqualByComparingTo("3.7900");
    }

    @Test
    void tier2MinNetRrUsesPlanNetRrWhileSortNetRrDescRemainsCurrentPrice() {
        // Tier 1 標的：現價 100 在買區內，現價 Net R/R = 2.50（目標 116、止損 94）
        // Tier 2 標的：買區 95..100、止損 90、目標 122 -> planNetRr = 3.79 (>= 2.0)，但現價 108 距買區 +8%，現價 Net R/R 約 0.75 (< 2.0)
        Stock t1 = stock("FIGI_T1RR", "T1RR", "Tier1 RR Corp", "Common Stock", "USD", true);
        Stock t2 = stock("FIGI_T2RR", "T2RR", "Tier2 RR Corp", "Common Stock", "USD", true);
        StockValuationLatest v1 = valuation("FIGI_T1RR", "SEMI", new BigDecimal("100.00"),
                new BigDecimal("110.00"), new BigDecimal("130.00"), new BigDecimal("150.00"),
                "DEEP_UNDERVALUED", "STRONG");
        StockValuationLatest v2 = valuation("FIGI_T2RR", "SEMI", new BigDecimal("108.00"),
                new BigDecimal("110.00"), new BigDecimal("130.00"), new BigDecimal("150.00"),
                "MODERATELY_UNDERVALUED", "STRONG");
        when(stockRepository.findByActiveStatusAndSymbolIn(eq(ActiveStatus.ACTIVE), anyCollection()))
                .thenReturn(List.of(t1, t2));
        when(latestValuations.findByFigiIn(anyCollection())).thenReturn(List.of(v1, v2));
        when(industryHistoryRepository.findByFigisAt(anyCollection(), eq(SESSION_CLOSE))).thenReturn(List.of());
        when(quoteRepository.findQuoteBarsOrdered(anyCollection(), any(), eq(AS_OF)))
                .thenReturn(List.of(quoteRow("FIGI_T1RR", new BigDecimal("100.00")),
                        quoteRow("FIGI_T2RR", new BigDecimal("108.00"))));

        stubTechnicalStack("FIGI_T1RR", new BigDecimal("100.00"), new BigDecimal("98.00"), new BigDecimal("101.00"),
                new BigDecimal("94.00"), new BigDecimal("116.00"), TradePlanStatus.ACTIVE,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);
        stubTechnicalStack("FIGI_T2RR", new BigDecimal("108.00"), new BigDecimal("95.00"), new BigDecimal("100.00"),
                new BigDecimal("90.00"), new BigDecimal("122.00"), TradePlanStatus.WAITING_TRIGGER,
                null, MarketRegime.RISK_ON, EntryPermission.ALLOW);

        BuyRankingRequest req = new BuyRankingRequest(
                BuyRankingDtos.SCOPE_CUSTOM, null, null, null, List.of("T1RR", "T2RR"),
                List.of(BuyRankingDtos.ASSET_COMMON_STOCK), true,
                List.of(BuyRankingDtos.TIER_1, BuyRankingDtos.TIER_2),
                BuyRankingDtos.GRADE_ALL, new BigDecimal("2.0"), null,
                BuyRankingDtos.SORT_NET_RR_DESC, "POSITION", "zh-HK", 1, 300);
        BuyRankingResponse resp = service.generateReport(req, "zh-HK");

        // TIER_2 因 planNetRiskReward (3.79) >= 2.0 應保留，不被現價 Net R/R (~0.75) 誤刪；
        // 且 SORT_NET_RR_DESC 仍以現價 Net R/R 降冪排序，故 T1RR 排在 T2RR 前面
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).symbol()).isEqualTo("T1RR");
        assertThat(resp.items().get(0).tier()).isEqualTo(BuyRankingDtos.TIER_1);
        assertThat(resp.items().get(1).symbol()).isEqualTo("T2RR");
        assertThat(resp.items().get(1).tier()).isEqualTo(BuyRankingDtos.TIER_2);
        assertThat(resp.items().get(1).netRiskReward()).isLessThan(new BigDecimal("2.0"));
        assertThat(resp.items().get(1).planNetRiskReward()).isEqualByComparingTo("3.7900");
    }

    private void stubTechnicalStackAtDate(
            String figi,
            LocalDate targetDate,
            Instant closeAt,
            BigDecimal close,
            BigDecimal zoneLow,
            BigDecimal zoneHigh,
            BigDecimal stopPrice,
            BigDecimal targetPrice,
            TradePlanStatus planStatus,
            BreakoutDiagnosis diagnosis,
            MarketRegime regime,
            EntryPermission permission) {
        List<NormalizedBarDto> bars = new ArrayList<>();
        for (int i = 25; i >= 0; i--) {
            LocalDate d = targetDate.minusDays(i);
            bars.add(new NormalizedBarDto(
                    d, close.subtract(BigDecimal.ONE), close.add(BigDecimal.ONE), close.subtract(new BigDecimal("2")),
                    close, MetricResult.available(new BigDecimal("1000000")),
                    closeAt.minusSeconds(23400), closeAt, closeAt, NOW, "v1", "b1"));
        }
        NormalizedPriceSeriesDto series = new NormalizedPriceSeriesDto(
                figi, "DAILY", bars,
                new PriceBasisDescriptor("b1", "STORED_QUOTES", null, targetDate, null, null, null, "USD", true, null),
                new PriceSeriesCoverageDto(targetDate.minusDays(25), targetDate, targetDate.minusDays(25), targetDate, 26, 26, List.of(), List.of()),
                MetricStatus.AVAILABLE, List.of(), "hash");
        when(quoteNormalizationService.normalizeFromLoaded(any(AnalysisContext.class), any(), eq(targetDate),
                org.mockito.ArgumentMatchers.argThat(s -> s != null && figi.equals(s.getFigi())), any(), any(), any()))
                .thenReturn(MetricResult.available(series));

        StructureTrendDto trend = new StructureTrendDto(TrendState.UPTREND, closeAt, PriceRelation.HH, PriceRelation.HL, List.of(), closeAt);
        TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, bars,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                MetricResult.available(trend), MetricResult.available(trend), List.of(), List.of());
        TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, bars,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                MetricResult.available(trend), MetricResult.available(trend), List.of(), List.of());
        PriceStructureDto structureDto = new PriceStructureDto(figi, null, targetDate, List.of(daily, weekly), List.of(), List.of());
        when(priceStructureService.analyze(org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())), eq(series)))
                .thenReturn(MetricResult.available(structureDto));

        MomentumAnalysisDto momentum = new MomentumAnalysisDto(
                Map.of("SMA200", MetricResult.available(close.multiply(new BigDecimal("0.85")))),
                MetricResult.available(new BigDecimal("0.5")), List.of());
        VolumeAnalysisDto vol = new VolumeAnalysisDto(
                MetricResult.available(BigDecimal.ONE), MetricResult.available(BigDecimal.TEN),
                MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                List.of(), List.of());
        RelativeStrengthDto rs = new RelativeStrengthDto("SPY",
                MetricResult.available(new BigDecimal("0.04")), MetricResult.available(new BigDecimal("0.08")),
                MetricResult.available(new BigDecimal("0.12")), Map.of(),
                MetricResult.available(new BigDecimal("0.60")), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(regime, MetricResult.available(new BigDecimal("55")),
                MetricResult.available(new BigDecimal("55")), MetricResult.available(new BigDecimal("55")),
                targetDate, closeAt, List.of(), List.of());
        TreasuryContextDto treasury = new TreasuryContextDto(
                MetricResult.available(new BigDecimal("4.50")), MetricResult.available(new BigDecimal("4.20")),
                MetricResult.available(new BigDecimal("0.35")), MetricResult.available(new BigDecimal("0.30")),
                List.of(), List.of());
        MarketContextDto marketDto = new MarketContextDto(
                new MomentumVolumeDto(MetricResult.available(new BigDecimal("50")), MetricResult.available(BigDecimal.ZERO),
                        MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO),
                        MetricResult.available(BigDecimal.ONE), MetricResult.available(BigDecimal.ZERO),
                        MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO)),
                rs, MetricResult.available(snap), List.of(),
                new PlanConstraintDto(permission, List.of("NO_KNOWN_BLOCKING_EVENT"), List.of()),
                List.of(), List.of(), momentum, vol, null, treasury, Map.of(),
                MetricResult.available(new BigDecimal("15.0")), null);
        when(marketContextService.evaluateWithPreloaded(
                org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())),
                eq(series), eq(structureDto), any(), any(), any()))
                .thenReturn(MetricResult.available(marketDto));

        LevelZoneDto zone = new LevelZoneDto(
                "Z_" + figi, AnalysisTimeframe.WEEKLY, zoneLow, zoneHigh,
                ZoneRole.SUPPORT, new BigDecimal("78"), closeAt.minusSeconds(864000),
                3, closeAt, ZoneState.ACTIVE, zoneLow, new BigDecimal("3.0"), 1,
                ZoneStrengthLabel.HIGH, new BigDecimal("78"), 1, null, null,
                List.of(), List.of(), 3, 0, 2, BigDecimal.ZERO, BigDecimal.ZERO,
                RetestState.NONE, true, List.of(), null, List.of());
        SupportResistanceDto srDto = new SupportResistanceDto(
                List.of(zone), List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());
        when(supportResistanceService.analyze(
                org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())),
                eq(series), eq(structureDto), eq(marketDto)))
                .thenReturn(MetricResult.available(srDto));

        TargetDto target = new TargetDto(targetPrice, BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "resistance", List.of());
        StopRuleDto stop = new StopRuleDto(stopPrice, stopPrice.add(BigDecimal.ONE), 2, AnalysisTimeframe.DAILY,
                new BigDecimal("0.50"), new BigDecimal("3.0"), AnalysisTimeframe.WEEKLY, closeAt.minusSeconds(864000), List.of());
        TradePlanDto plan = new TradePlanDto(
                "P_" + figi, AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(zoneLow, zoneHigh, "b1"), EntryMethod.ZONE_LIMIT, "pullback",
                stop, List.of(target),
                MetricResult.available(new RiskRewardDto(close, stopPrice, new BigDecimal("6"), new BigDecimal("24"),
                        new BigDecimal("4.0"), new BigDecimal("6.2"), new BigDecimal("23.5"), new BigDecimal("3.79"),
                        new BigDecimal("24"), new BigDecimal("6"))),
                planStatus, List.of(), "Z_" + figi, 1, AnalysisTimeframe.WEEKLY,
                closeAt.minusSeconds(864000), targetDate.plusDays(5), List.of());
        TradePlanResultDto planRes = new TradePlanResultDto(
                List.of(plan), close, targetDate, plan.riskReward(),
                List.of(), List.of(), true);
        when(tradePlanService.build(
                org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())),
                eq(series), eq(structureDto), eq(srDto), any()))
                .thenReturn(MetricResult.available(planRes));
    }

    private void stubTechnicalStack(
            String figi,
            BigDecimal close,
            BigDecimal zoneLow,
            BigDecimal zoneHigh,
            BigDecimal stopPrice,
            BigDecimal targetPrice,
            TradePlanStatus planStatus,
            BreakoutDiagnosis diagnosis,
            MarketRegime regime,
            EntryPermission permission) {
        List<NormalizedBarDto> bars = new ArrayList<>();
        for (int i = 25; i >= 0; i--) {
            LocalDate d = AS_OF.minusDays(i);
            bars.add(new NormalizedBarDto(
                    d, close.subtract(BigDecimal.ONE), close.add(BigDecimal.ONE), close.subtract(new BigDecimal("2")),
                    close, MetricResult.available(new BigDecimal("1000000")),
                    SESSION_CLOSE.minusSeconds(23400), SESSION_CLOSE, SESSION_CLOSE, NOW, "v1", "b1"));
        }
        NormalizedPriceSeriesDto series = new NormalizedPriceSeriesDto(
                figi, "DAILY", bars,
                new PriceBasisDescriptor("b1", "STORED_QUOTES", null, AS_OF, null, null, null, "USD", true, null),
                new PriceSeriesCoverageDto(AS_OF.minusDays(25), AS_OF, AS_OF.minusDays(25), AS_OF, 26, 26, List.of(), List.of()),
                MetricStatus.AVAILABLE, List.of(), "hash");
        when(quoteNormalizationService.normalizeFromLoaded(any(AnalysisContext.class), any(), eq(AS_OF),
                org.mockito.ArgumentMatchers.argThat(s -> s != null && figi.equals(s.getFigi())), any(), any(), any()))
                .thenReturn(MetricResult.available(series));

        StructureTrendDto trend = new StructureTrendDto(TrendState.UPTREND, SESSION_CLOSE, PriceRelation.HH, PriceRelation.HL, List.of(), SESSION_CLOSE);
        TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, bars,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                MetricResult.available(trend), MetricResult.available(trend), List.of(), List.of());
        TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, bars,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL),
                MetricResult.available(trend), MetricResult.available(trend), List.of(), List.of());
        PriceStructureDto structureDto = new PriceStructureDto(figi, null, AS_OF, List.of(daily, weekly), List.of(), List.of());
        when(priceStructureService.analyze(org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())), eq(series)))
                .thenReturn(MetricResult.available(structureDto));

        MomentumAnalysisDto momentum = new MomentumAnalysisDto(
                Map.of("SMA200", MetricResult.available(close.multiply(new BigDecimal("0.85")))),
                MetricResult.available(new BigDecimal("0.5")), List.of());
        VolumeAnalysisDto vol = new VolumeAnalysisDto(
                MetricResult.available(BigDecimal.ONE), MetricResult.available(BigDecimal.TEN),
                MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                List.of(), List.of());
        RelativeStrengthDto rs = new RelativeStrengthDto("SPY",
                MetricResult.available(new BigDecimal("0.04")), MetricResult.available(new BigDecimal("0.08")),
                MetricResult.available(new BigDecimal("0.12")), Map.of(),
                MetricResult.available(new BigDecimal("0.60")), List.of());
        MarketSnapshotDto snap = new MarketSnapshotDto(regime, MetricResult.available(new BigDecimal("55")),
                MetricResult.available(new BigDecimal("55")), MetricResult.available(new BigDecimal("55")),
                AS_OF, SESSION_CLOSE, List.of(), List.of());
        TreasuryContextDto treasury = new TreasuryContextDto(
                MetricResult.available(new BigDecimal("4.25")), MetricResult.available(new BigDecimal("4.10")),
                MetricResult.available(new BigDecimal("0.05")), MetricResult.available(new BigDecimal("0.15")),
                List.of(), List.of());
        MarketContextDto marketDto = new MarketContextDto(
                new MomentumVolumeDto(MetricResult.available(new BigDecimal("50")), MetricResult.available(BigDecimal.ZERO),
                        MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO),
                        MetricResult.available(BigDecimal.ONE), MetricResult.available(BigDecimal.ZERO),
                        MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO), MetricResult.available(BigDecimal.ZERO)),
                rs, MetricResult.available(snap), List.of(),
                new PlanConstraintDto(permission, List.of("NO_KNOWN_BLOCKING_EVENT"), List.of()),
                List.of(), List.of(), momentum, vol, null, treasury, Map.of(),
                MetricResult.available(new BigDecimal("16.50")), null);
        when(marketContextService.evaluateWithPreloaded(
                org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())),
                eq(series), eq(structureDto), any(), any(), any()))
                .thenReturn(MetricResult.available(marketDto));

        LevelZoneDto zone = new LevelZoneDto(
                "Z_" + figi, AnalysisTimeframe.WEEKLY, zoneLow, zoneHigh,
                ZoneRole.SUPPORT, new BigDecimal("78"), SESSION_CLOSE.minusSeconds(864000),
                3, SESSION_CLOSE, ZoneState.ACTIVE, zoneLow, new BigDecimal("3.0"), 1,
                ZoneStrengthLabel.HIGH, new BigDecimal("78"), 1, null, null,
                List.of(), List.of(), 3, 0, 2, BigDecimal.ZERO, BigDecimal.ZERO,
                RetestState.NONE, true, List.of(), null, List.of());
        BreakoutDiagnosisDto diagDto = diagnosis == null ? null : new BreakoutDiagnosisDto(
                diagnosis, "Z_" + figi, 1, 1, AnalysisTimeframe.WEEKLY, zoneLow, zoneHigh,
                AS_OF.minusDays(1), null, null, AS_OF, 1,
                new BigDecimal("1.8"), new BigDecimal("1.4"), new BigDecimal("0.15"),
                new BigDecimal("0.70"), new BigDecimal("0.15"), new BigDecimal("-0.04"),
                BigDecimal.ZERO, new BigDecimal("-0.04"), List.of(), List.of());
        SupportResistanceDto srDto = new SupportResistanceDto(
                List.of(zone), List.of(), List.of(), List.of(), List.of(), List.of(), diagDto,
                diagDto == null ? List.of() : List.of(diagDto));
        when(supportResistanceService.analyze(
                org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())),
                eq(series), eq(structureDto), eq(marketDto)))
                .thenReturn(MetricResult.available(srDto));

        TargetDto target = new TargetDto(targetPrice, BigDecimal.ONE, TargetAction.TAKE_PROFIT, "R1", "resistance", List.of());
        StopRuleDto stop = new StopRuleDto(stopPrice, stopPrice.add(BigDecimal.ONE), 2, AnalysisTimeframe.DAILY,
                new BigDecimal("0.50"), new BigDecimal("3.0"), AnalysisTimeframe.WEEKLY, SESSION_CLOSE.minusSeconds(864000), List.of());
        List<AnalysisReasonDto> planReasons = (planStatus == TradePlanStatus.WAITING_TRIGGER && close.compareTo(zoneHigh) > 0)
                ? List.of(AnalysisReasonDto.of("ENTRY_PRICE_ABOVE_CAP", "TRADE_PLAN", "價格高於入場上限"))
                : List.of();
        TradePlanDto plan = new TradePlanDto(
                "P_" + figi, AnalysisHorizon.POSITION, SetupType.PULLBACK,
                new PriceRangeDto(zoneLow, zoneHigh, "b1"), EntryMethod.ZONE_LIMIT, "pullback",
                stop, List.of(target),
                MetricResult.available(new RiskRewardDto(close, stopPrice, new BigDecimal("6"), new BigDecimal("24"),
                        new BigDecimal("4.0"), new BigDecimal("6.2"), new BigDecimal("23.5"), new BigDecimal("3.79"),
                        new BigDecimal("24"), new BigDecimal("6"))),
                planStatus, planReasons, "Z_" + figi, 1, AnalysisTimeframe.WEEKLY,
                SESSION_CLOSE.minusSeconds(864000), AS_OF.plusDays(5), List.of());
        TradePlanResultDto planRes = new TradePlanResultDto(
                List.of(plan), close, AS_OF, plan.riskReward(),
                List.of(), List.of(), true);
        when(tradePlanService.build(
                org.mockito.ArgumentMatchers.argThat(c -> c != null && figi.equals(c.figi())),
                eq(series), eq(structureDto), eq(srDto), any()))
                .thenReturn(MetricResult.available(planRes));
    }

    private PreloadedMarketData buildPreloadedMarket(MarketRegime regime, BigDecimal vix) {
        MarketSnapshotDto snap = new MarketSnapshotDto(regime, MetricResult.available(new BigDecimal("55")),
                MetricResult.available(new BigDecimal("55")), MetricResult.available(new BigDecimal("55")),
                AS_OF, SESSION_CLOSE, List.of(), List.of());
        return new PreloadedMarketData(AS_OF, NOW,
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of()),
                Map.of(), List.of(), MetricResult.available(snap), MetricResult.available(vix), null);
    }

    private Stock stock(String figi, String symbol, String name, String stockType, String currency, boolean tracked) {
        Stock s = new Stock();
        s.setFigi(figi);
        s.setSymbol(symbol);
        s.setLongName(name);
        s.setStockType(stockType);
        s.setCurrency(currency);
        s.setActiveStatus(ActiveStatus.ACTIVE);
        s.setTracked(tracked);
        return s;
    }

    private StockValuationLatest valuation(String figi, String industryCode, BigDecimal close,
                                           BigDecimal low, BigDecimal mid, BigDecimal high,
                                           String verdict, String backing) {
        StockValuationLatest v = new StockValuationLatest();
        v.setFigi(figi);
        v.setValuationDate(AS_OF);
        v.setAnalysisAsOf(SESSION_CLOSE);
        v.setCalculatedAt(NOW);
        v.setCurrency("USD");
        v.setClosePrice(close);
        v.setIndustryCode(industryCode);
        v.setModelCode("NON_FINANCIAL_OPERATING");
        v.setStatus("AVAILABLE");
        v.setMarketCap(new BigDecimal("100000000000"));
        v.setFairValueLow(low);
        v.setFairValueMid(mid);
        v.setFairValueHigh(high);
        v.setVerdict(verdict);
        v.setFundamentalBacking(backing);
        return v;
    }

    private StockIndustryHistory industryHistory(String figi, String code, String name) {
        StockIndustry ind = new StockIndustry();
        ind.setCode(code);
        ind.setIndustry(name);
        StockIndustryHistory h = new StockIndustryHistory();
        h.setFigi(figi);
        h.setIndustry(ind);
        return h;
    }

    private QuoteBarRow quoteRow(String figi, BigDecimal close) {
        return new QuoteBarRow(figi, AS_OF, close, close, close, close, new BigDecimal("1000000"), NOW);
    }
}
