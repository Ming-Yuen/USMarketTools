package com.ykm.stock.analytics.service;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReportRequest;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AtrPointDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceAdjustment;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceBasisDescriptor;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRelation;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SessionScope;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ShareUnit;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StockAnalysisReportDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StructureTrendDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TrendState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.StockAnalysisService;
import com.ykm.stock.analytics.service.analysis.StockObservationService;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.analysis.calculator.ExecutionCostCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.TradePlanCalculator;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import com.ykm.stock.analytics.service.valuation.StockValuationService.ReportValuationBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockAnalysisReadOnlyTests {
    private static final LocalDate DAY = LocalDate.parse("2025-05-02");
    private static final Instant CLOSE = Instant.parse("2025-05-02T20:00:00Z");
    private static final Instant FIXED_READ_AT = Instant.parse("2025-05-03T12:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final MarketEventRepository marketEvents = mock(MarketEventRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final QuoteNormalizationService prices = mock(QuoteNormalizationService.class);
    private final PriceStructureService structures = mock(PriceStructureService.class);
    private final SupportResistanceService levels = mock(SupportResistanceService.class);
    private final TradePlanService plans = mock(TradePlanService.class);
    private final MarketContextService market = mock(MarketContextService.class);
    private final StockObservationService stockObservations = mock(StockObservationService.class);
    private final StockValuationService stockValuations = mock(StockValuationService.class);
    private final com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService sectorMoneyFlowService =
            mock(com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService.class);
    private final com.ykm.stock.analytics.service.portfolio.PortfolioManagementService portfolioManagementService =
            mock(com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.class);
    private final com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator positionSizingCalculator =
            new com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator();
    private final StockAnalysisService service = new StockAnalysisService(
            stocks, industries, marketEvents, calendar, clock, prices, structures, levels, plans, market,
            stockObservations, stockValuations, sectorMoneyFlowService, portfolioManagementService, positionSizingCalculator);

    private Stock testStock;

    @BeforeEach
    void setup() {
        testStock = new Stock();
        testStock.setFigi("TEST_FIGI");
        testStock.setSymbol("TEST");
        testStock.setLongName("Test issuer");
        testStock.setCurrency("USD");
        when(clock.now()).thenReturn(FIXED_READ_AT);
        when(stocks.findById("TEST_FIGI")).thenReturn(Optional.of(testStock));
        when(calendar.isTradingDay(DAY)).thenReturn(true);
        when(calendar.getSessionCloseAt(DAY)).thenReturn(CLOSE);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(DAY);
        when(prices.normalize(any(), any(), any())).thenReturn(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("PRICE_MISSING", "PRICE", "No stored price")));
        when(stockObservations.readWithForward(any())).thenReturn(new FutuObservationsDto(null, List.of()));
        when(stockValuations.evaluateForReport(any(), any(), any(), any(), any()))
                .thenReturn(new ReportValuationBundle(null, null, List.of()));
    }

    @Test
    void missingValuationDoesNotTriggerHiddenCalculationOrWrite() {
        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, AnalysisHorizon.POSITION));
        assertThat(report.valuation()).isNull();
        assertThat(report.financials()).isNull();
        assertThat(report.reasons()).extracting(AnalysisReasonDto::code).contains("VALUATION_NOT_CALCULATED", "PRICE_MISSING");
        verify(stocks, never()).save(any());
        verifyNoInteractions(structures, levels, plans, market, marketEvents);
    }

    @Test
    void defaultDateKeepsFinancialCloseButReadsLatestSupplementalObservations() {
        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", null, null));
        assertThat(report.valuationDate()).isEqualTo(DAY);
        verify(stockObservations).readWithForward(argThat(ctx -> "TEST_FIGI".equals(ctx.figi())
                && DAY.equals(ctx.valuationDate())
                && FIXED_READ_AT.equals(ctx.asOf())));
        verify(prices).normalize(argThat(context -> context.analysisAsOf().equals(CLOSE)
                && FIXED_READ_AT.equals(context.observationCutoff())), eq(DAY.minusYears(5)), eq(DAY));
    }

    @Test
    void defaultDateFallbackInValuationRebuildsTechnicalAnalysisContextAtEffectiveLatestSession() {
        LocalDate fallbackDay = LocalDate.parse("2025-05-01");
        Instant fallbackClose = Instant.parse("2025-05-01T20:00:00Z");
        when(calendar.getSessionCloseAt(fallbackDay)).thenReturn(fallbackClose);
        ValuationResultDto fallbackValuation = new ValuationResultDto(
                "TEST_FIGI", fallbackDay, fallbackClose, "FINANCIAL_REPORT_AS_OBSERVED", "I1", "AVAILABLE", null,
                null, new BigDecimal("150"), "USD", fallbackClose, null, null, null, null);
        when(stockValuations.evaluateForReport(same(testStock), eq(DAY), eq(CLOSE), any(), any()))
                .thenReturn(new ReportValuationBundle(fallbackValuation, null, List.of(), null));

        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", null, AnalysisHorizon.POSITION));

        assertThat(report.valuationDate()).isEqualTo(fallbackDay);
        verify(prices).normalize(argThat(context -> context.analysisAsOf().equals(fallbackClose)),
                eq(fallbackDay.minusYears(5)), eq(fallbackDay));
        assertThat(report.summary()).isNotNull();
        assertThat(report.summary().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(report.summary().value().verdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void explicitlyDatedReportKeepsSupplementalObservationsAtHistoricalClose() {
        service.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, null));
        verify(stockObservations).readWithForward(argThat(ctx -> "TEST_FIGI".equals(ctx.figi())
                && DAY.equals(ctx.valuationDate()) && CLOSE.equals(ctx.asOf())));
        verify(stockValuations).evaluateForReport(same(testStock), eq(DAY), eq(CLOSE), eq(CLOSE), eq(null));
        verify(prices).normalize(argThat(context -> context.analysisAsOf().equals(CLOSE)
                && context.observationCutoff().equals(CLOSE)), eq(DAY.minusYears(5)), eq(DAY));
    }

    @Test
    void reportUsesTheIndustryClassificationKnownAtTheAnalysisCutoffAndReturnsFinancials() {
        StockIndustry industry = new StockIndustry();
        industry.setCode("I1");
        StockIndustryHistory history = new StockIndustryHistory();
        history.setIndustry(industry);
        when(industries.findAt("TEST_FIGI", CLOSE)).thenReturn(Optional.of(history));
        FinancialsDto.FinancialValue rev = FinancialsDto.FinancialValue.available(new BigDecimal("1000"), "Futu TTM", LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), CLOSE);
        FinancialsDto financials = new FinancialsDto(LocalDate.of(2025, 3, 31), CLOSE, "USD",
                rev, rev, rev, rev, rev, rev, rev, rev, rev, rev, rev, rev, rev, rev);
        ValuationResultDto valDto = new ValuationResultDto(
                "TEST_FIGI", DAY, CLOSE, "FINANCIAL_REPORT_AS_OBSERVED", "I1", "PARTIAL", "VALUATION_INPUTS_INCOMPLETE",
                null, null, null, null, null, null, null, null);
        when(stockValuations.evaluateForReport(same(testStock), eq(DAY), eq(CLOSE), eq(CLOSE), eq("I1")))
                .thenReturn(new ReportValuationBundle(valDto, financials, List.of()));

        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, null));

        assertThat(report.valuation().industryCode()).isEqualTo("I1");
        assertThat(report.valuation().source()).isEqualTo("FINANCIAL_REPORT_AS_OBSERVED");
        assertThat(report.financials()).isNotNull();
        assertThat(report.financials().revenueTtm().value()).isEqualByComparingTo("1000");
    }

    @Test
    void nonTradingDateCannotBeSilentlyReplaced() {
        LocalDate weekend = LocalDate.parse("2025-05-03");
        when(calendar.isTradingDay(weekend)).thenReturn(false);
        assertThatThrownBy(() -> service.analyze(new AnalysisReportRequest("TEST_FIGI", weekend, null)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(prices, stockObservations, stockValuations);
    }

    @Test
    void storedFinancialMultiplesAndTechnicalEntryStopTargetsAreReturnedTogether() {
        StockAnalysisReportDto report = assembledReport(true, true);
        assertThat(report.valuation().source()).isEqualTo("FINANCIAL_REPORT_AS_OBSERVED");
        assertThat(report.valuation().metrics().pe().value()).isEqualByComparingTo("20");
        assertThat(report.tradePlans().status()).isEqualTo(MetricStatus.AVAILABLE);
        TradePlanDto plan = report.tradePlans().value().plans().getFirst();
        assertThat(plan.entryRange().lower()).isEqualByComparingTo("95");
        assertThat(plan.entryRange().upper()).isEqualByComparingTo("100");
        assertThat(plan.stop().protectivePrice()).isEqualByComparingTo("92.5");
        assertThat(plan.targets()).singleElement().satisfies(target -> assertThat(target.price()).isEqualByComparingTo("129.5"));
        assertThat(plan.status()).isEqualTo(TradePlanStatus.ACTIVE);
        assertThat(report.supportResistance().value().supports()).hasSize(1);
        assertThat(report.supportResistance().value().resistances()).hasSize(1);
        verify(stocks, never()).save(any());
    }

    @Test
    void missingFinancialValuationDoesNotSuppressAnIndependentlyQualifiedTechnicalPlan() {
        StockAnalysisReportDto report = assembledReport(false, true);
        assertThat(report.valuation()).isNull();
        assertThat(report.reasons()).extracting(AnalysisReasonDto::code).contains("VALUATION_NOT_CALCULATED");
        assertThat(report.tradePlans().value().plans().getFirst().status()).isEqualTo(TradePlanStatus.ACTIVE);
        assertThat(report.tradePlans().value().plans().getFirst().entryRange().lower()).isEqualByComparingTo("95");
    }

    @Test
    void availableFinancialMultipleCannotInventATechnicalSellTargetWithoutResistance() {
        StockAnalysisReportDto report = assembledReport(true, false);
        assertThat(report.valuation().metrics().pe().status()).isEqualTo("AVAILABLE");
        TradePlanDto plan = report.tradePlans().value().plans().getFirst();
        assertThat(plan.entryRange()).isNotNull();
        assertThat(plan.stop()).isNotNull();
        assertThat(plan.targets()).isEmpty();
        assertThat(plan.status()).isEqualTo(TradePlanStatus.WAITING_TRIGGER);
        assertThat(plan.reasons()).extracting(AnalysisReasonDto::code).contains("NO_FIXED_TARGET");
    }

    @Test
    void unverifiedExistingHoldingBlocksActionableSizingOnSameFigi() {
        com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto cfg =
                new com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto(
                        new BigDecimal("100000.00"), new BigDecimal("0.0100"),
                        new BigDecimal("0.1500"), new BigDecimal("0.3000"), "USD");
        when(portfolioManagementService.loadExposureSnapshot(any(), any())).thenReturn(
                new com.ykm.stock.analytics.service.portfolio.PortfolioManagementService.PortfolioExposureSnapshot(
                        cfg, java.util.Map.of(), java.util.Map.of(), java.util.Set.of("TEST_FIGI"), 1, List.of("SPLIT_SYNC_ERROR:TEST")));

        ValuationResultDto valuation = new ValuationResultDto(
                "TEST_FIGI", DAY, CLOSE, "FINANCIAL_REPORT_AS_OBSERVED", null, "AVAILABLE", null,
                new ValuationResultDto.Metrics(
                        ValuationResultDto.value(new BigDecimal("20"), null),
                        ValuationResultDto.value(null, "VALUATION_DATE_SHARES_MISSING"),
                        ValuationResultDto.value(null, "VALUATION_DATE_SHARES_MISSING"),
                        ValuationResultDto.value(null, "VALUATION_DATE_SHARES_MISSING"),
                        ValuationResultDto.value(null, "EV_COMPONENTS_UNRESOLVED")),
                new BigDecimal("98"), "USD", CLOSE, null, null, null, null);
        when(stockValuations.evaluateForReport(any(), eq(DAY), eq(CLOSE), any(), any()))
                .thenReturn(new ReportValuationBundle(valuation, null, List.of()));
        ExecutionCostCalculator costs = new ExecutionCostCalculator();
        TradePlanService realPlans = new TradePlanService(new TradePlanCalculator(costs), costs, calendar);
        StockAnalysisService reportService = new StockAnalysisService(
                stocks, industries, marketEvents, calendar, clock, prices, structures, levels, realPlans, market,
                stockObservations, stockValuations, sectorMoneyFlowService, portfolioManagementService, positionSizingCalculator);
        Instant confirmedAt = CLOSE.minusSeconds(86400);
        when(calendar.getNextTradingDay(DAY.minusDays(1))).thenReturn(DAY);
        NormalizedBarDto bar = new NormalizedBarDto(DAY, new BigDecimal("98"), new BigDecimal("99"), new BigDecimal("97"), new BigDecimal("98"),
                MetricResult.available(BigDecimal.ONE), CLOSE.minusSeconds(23400), CLOSE, CLOSE, CLOSE, null, "BASIS");
        PriceBasisDescriptor basis = new PriceBasisDescriptor("BASIS", "STORED_QUOTES", PriceAdjustment.SPLIT_ADJUSTED, DAY,
                null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, null);
        NormalizedPriceSeriesDto price = new NormalizedPriceSeriesDto("TEST_FIGI", "DAILY", List.of(bar), basis, null, MetricStatus.AVAILABLE, List.of(), null);
        MetricResult<StructureTrendDto> trend = MetricResult.available(new StructureTrendDto(TrendState.UPTREND, confirmedAt, PriceRelation.HH, PriceRelation.HL, List.of(), confirmedAt));
        AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("5"), null, DAY.minusYears(1), 14, 100);
        TimeframeStructureDto frame = new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(bar), MetricResult.available(atr), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL), trend, trend, List.of(), List.of());
        PriceStructureDto structure = new PriceStructureDto("TEST_FIGI", null, DAY, List.of(frame), List.of(), List.of());
        SupportResistanceDto zones = new SupportResistanceDto(List.of(zone("S", "95", "100", ZoneRole.SUPPORT, confirmedAt)),
                List.of(zone("R", "130", "135", ZoneRole.RESISTANCE, confirmedAt)), List.of(), List.of(), List.of(), List.of());
        MarketContextDto background = new MarketContextDto(null, null, null, List.of(), new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()), List.of(), List.of());
        when(prices.normalize(any(), any(), any())).thenReturn(MetricResult.available(price));
        when(structures.analyze(any(), same(price))).thenReturn(MetricResult.available(structure));
        when(marketEvents.findVisiblePastEarnings(eq("TEST_FIGI"), eq(DAY), any())).thenReturn(List.of());
        when(market.evaluate(any(), same(price), same(structure), any())).thenReturn(MetricResult.available(background));
        when(levels.analyze(any(), same(price), same(structure), same(background))).thenReturn(MetricResult.available(zones));

        StockAnalysisReportDto report = reportService.analyze(new AnalysisReportRequest("TEST_FIGI", null, AnalysisHorizon.SWING));
        TradePlanDto plan = report.tradePlans().value().plans().getFirst();
        assertThat(plan.positionSizing()).isNotNull();
        assertThat(plan.positionSizing().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(plan.positionSizing().reasonCode()).isEqualTo("EXISTING_HOLDING_VALUE_UNVERIFIED");
        assertThat(plan.positionSizing().suggestedShares()).isNull();
    }

    @Test
    void etfAnalysisSkipsValuationAndCompanyEarningsLockWhileReturningTechnicalPlanAndSummary() {
        Stock etf = new Stock();
        etf.setFigi("ETF_QQQ");
        etf.setSymbol("QQQ");
        etf.setLongName("Invesco QQQ Trust");
        etf.setCurrency("USD");
        etf.setStockType("ETF");
        when(stocks.findById("ETF_QQQ")).thenReturn(Optional.of(etf));

        Instant confirmedAt = CLOSE.minusSeconds(86400);
        when(calendar.getNextTradingDay(DAY.minusDays(1))).thenReturn(DAY);
        NormalizedBarDto bar = new NormalizedBarDto(DAY, new BigDecimal("500"), new BigDecimal("505"), new BigDecimal("498"), new BigDecimal("502"),
                MetricResult.available(BigDecimal.ONE), CLOSE.minusSeconds(23400), CLOSE, CLOSE, CLOSE, null, "BASIS");
        PriceBasisDescriptor basis = new PriceBasisDescriptor("BASIS", "STORED_QUOTES", PriceAdjustment.SPLIT_ADJUSTED, DAY,
                null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, null);
        NormalizedPriceSeriesDto price = new NormalizedPriceSeriesDto("ETF_QQQ", "DAILY", List.of(bar), basis, null, MetricStatus.AVAILABLE, List.of(), null);
        MetricResult<StructureTrendDto> trend = MetricResult.available(new StructureTrendDto(TrendState.UPTREND, confirmedAt, PriceRelation.HH, PriceRelation.HL, List.of(), confirmedAt));
        AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("8"), null, DAY.minusYears(1), 14, 100);
        TimeframeStructureDto frame = new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(bar), MetricResult.available(atr), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL), trend, trend, List.of(), List.of());
        PriceStructureDto structure = new PriceStructureDto("ETF_QQQ", null, DAY, List.of(frame), List.of(), List.of());
        SupportResistanceDto zones = new SupportResistanceDto(List.of(zone("S_ETF", "495", "505", ZoneRole.SUPPORT, confirmedAt)),
                List.of(zone("R_ETF", "535", "540", ZoneRole.RESISTANCE, confirmedAt)), List.of(), List.of(), List.of(), List.of());
        MarketContextDto background = new MarketContextDto(null, null, null, List.of(), new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()), List.of(), List.of());

        when(prices.normalize(any(), any(), any())).thenReturn(MetricResult.available(price));
        when(structures.analyze(any(), same(price))).thenReturn(MetricResult.available(structure));
        when(market.evaluate(any(), same(price), same(structure), eq(List.of()), eq(true))).thenReturn(MetricResult.available(background));
        when(levels.analyze(any(), same(price), same(structure), same(background))).thenReturn(MetricResult.available(zones));

        ExecutionCostCalculator costs = new ExecutionCostCalculator();
        TradePlanService realPlans = new TradePlanService(new TradePlanCalculator(costs), costs, calendar);
        StockAnalysisService etfService = new StockAnalysisService(
                stocks, industries, marketEvents, calendar, clock, prices, structures, levels, realPlans, market,
                stockObservations, stockValuations, sectorMoneyFlowService, portfolioManagementService, positionSizingCalculator);

        StockAnalysisReportDto report = etfService.analyze(new AnalysisReportRequest("ETF_QQQ", DAY, AnalysisHorizon.SWING));

        assertThat(report.stockType()).isEqualTo("ETF");
        assertThat(report.valuation().status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(report.valuation().reasonCode()).isEqualTo("NOT_APPLICABLE_FOR_ETF");
        assertThat(report.dcf().status()).isEqualTo(MetricStatus.NOT_APPLICABLE.name());
        assertThat(report.dcf().reasonCode()).isEqualTo("NOT_APPLICABLE_FOR_ETF");
        assertThat(report.summary().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(report.summary().value().verdict()).isEqualTo("NOT_APPLICABLE_FOR_ETF");
        assertThat(report.tradePlans().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(report.tradePlans().value().plans()).isNotEmpty();
        verifyNoInteractions(stockValuations);
        verify(marketEvents, never()).findVisiblePastEarnings(any(), any(), any());
        verify(market).evaluate(any(), same(price), same(structure), eq(List.of()), eq(true));
    }

    @Test
    void etfWithIncompleteFiveYearPriceStillPreservesTargetCloseAndReturnsInsufficientDataSummary() {
        Stock etf = new Stock();
        etf.setFigi("ETF_GAP");
        etf.setSymbol("SOXX");
        etf.setLongName("iShares Semiconductor ETF");
        etf.setCurrency("USD");
        etf.setStockType("ETF");
        when(stocks.findById("ETF_GAP")).thenReturn(Optional.of(etf));
        when(prices.normalize(any(), any(), any())).thenReturn(
                MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("PRICE_SESSIONS_MISSING", "PRICE", "gap")));
        when(prices.findStoredClosePrice("ETF_GAP", DAY)).thenReturn(new BigDecimal("218.40"));

        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("ETF_GAP", DAY, AnalysisHorizon.POSITION));

        assertThat(report.valuation().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(report.valuation().price()).isEqualByComparingTo("218.40");
        assertThat(report.dcf().currentPrice()).isEqualByComparingTo("218.40");
        assertThat(report.summary().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
    }

    private StockAnalysisReportDto assembledReport(boolean hasValuation, boolean hasResistance) {
        ValuationResultDto valuation = new ValuationResultDto(
                "TEST_FIGI", DAY, CLOSE, "FINANCIAL_REPORT_AS_OBSERVED", null, "AVAILABLE", null,
                new ValuationResultDto.Metrics(
                        ValuationResultDto.value(new BigDecimal("20"), null),
                        ValuationResultDto.value(null, "VALUATION_DATE_SHARES_MISSING"),
                        ValuationResultDto.value(null, "VALUATION_DATE_SHARES_MISSING"),
                        ValuationResultDto.value(null, "VALUATION_DATE_SHARES_MISSING"),
                        ValuationResultDto.value(null, "EV_COMPONENTS_UNRESOLVED")),
                new BigDecimal("98"), "USD", CLOSE, null, null, null, null);
        StockValuationService localValuation = mock(StockValuationService.class);
        when(localValuation.evaluateForReport(any(), eq(DAY), eq(CLOSE), eq(CLOSE), any()))
                .thenReturn(hasValuation ? new ReportValuationBundle(valuation, null, List.of()) : new ReportValuationBundle(null, null, List.of()));
        ExecutionCostCalculator costs = new ExecutionCostCalculator();
        TradePlanService realPlans = new TradePlanService(new TradePlanCalculator(costs), costs, calendar);
        StockAnalysisService reportService = new StockAnalysisService(
                stocks, industries, marketEvents, calendar, clock, prices, structures, levels, realPlans, market,
                stockObservations, localValuation, sectorMoneyFlowService, portfolioManagementService, positionSizingCalculator);
        Instant confirmedAt = CLOSE.minusSeconds(86400);
        when(calendar.getNextTradingDay(DAY.minusDays(1))).thenReturn(DAY);
        NormalizedBarDto bar = new NormalizedBarDto(DAY, new BigDecimal("98"), new BigDecimal("99"), new BigDecimal("97"), new BigDecimal("98"),
                MetricResult.available(BigDecimal.ONE), CLOSE.minusSeconds(23400), CLOSE, CLOSE, CLOSE, null, "BASIS");
        PriceBasisDescriptor basis = new PriceBasisDescriptor("BASIS", "STORED_QUOTES", PriceAdjustment.SPLIT_ADJUSTED, DAY,
                null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, null);
        NormalizedPriceSeriesDto price = new NormalizedPriceSeriesDto("TEST_FIGI", "DAILY", List.of(bar), basis, null, MetricStatus.AVAILABLE, List.of(), null);
        MetricResult<StructureTrendDto> trend = MetricResult.available(new StructureTrendDto(TrendState.UPTREND, confirmedAt, PriceRelation.HH, PriceRelation.HL, List.of(), confirmedAt));
        AtrPointDto atr = new AtrPointDto(DAY, 14, new BigDecimal("5"), null, DAY.minusYears(1), 14, 100);
        TimeframeStructureDto frame = new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(bar), MetricResult.available(atr), List.of(), List.of(),
                MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL), trend, trend, List.of(), List.of());
        PriceStructureDto structure = new PriceStructureDto("TEST_FIGI", null, DAY, List.of(frame), List.of(), List.of());
        SupportResistanceDto zones = new SupportResistanceDto(List.of(zone("S", "95", "100", ZoneRole.SUPPORT, confirmedAt)),
                hasResistance ? List.of(zone("R", "130", "135", ZoneRole.RESISTANCE, confirmedAt)) : List.of(), List.of(), List.of(), List.of(), List.of());
        MarketContextDto background = new MarketContextDto(null, null, null, List.of(), new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()), List.of(), List.of());
        MarketEvent pastEarnings = new MarketEvent();
        pastEarnings.setEventKey("EARNINGS|TEST_FIGI|2025-Q1");
        pastEarnings.setFigi("TEST_FIGI");
        pastEarnings.setEventType("EARNINGS");
        pastEarnings.setEventDate(DAY.minusDays(20));
        pastEarnings.setStatus("SCHEDULED");
        when(prices.normalize(any(), any(), any())).thenReturn(MetricResult.available(price));
        when(structures.analyze(any(), same(price))).thenReturn(MetricResult.available(structure));
        when(marketEvents.findVisiblePastEarnings("TEST_FIGI", DAY, CLOSE)).thenReturn(List.of(pastEarnings));
        when(market.evaluate(any(), same(price), same(structure), eq(List.of(pastEarnings)))).thenReturn(MetricResult.available(background));
        when(levels.analyze(any(), same(price), same(structure), same(background))).thenReturn(MetricResult.available(zones));
        StockAnalysisReportDto result = reportService.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, AnalysisHorizon.SWING));
        verify(marketEvents).findVisiblePastEarnings("TEST_FIGI", DAY, CLOSE);
        return result;
    }

    private LevelZoneDto zone(String id, String low, String high, ZoneRole role, Instant confirmedAt) {
        BigDecimal lower = new BigDecimal(low);
        BigDecimal upper = new BigDecimal(high);
        return new LevelZoneDto(id, AnalysisTimeframe.DAILY, lower, upper, role, new BigDecimal("70"), confirmedAt, 1, confirmedAt,
                ZoneState.ACTIVE, lower.add(upper).divide(new BigDecimal("2")), new BigDecimal("5"), 1, ZoneStrengthLabel.HIGH,
                new BigDecimal("70"), 1, confirmedAt, null, List.of(), List.of(), 1, 0, 1, null, null, RetestState.NONE, false, List.of());
    }
}
