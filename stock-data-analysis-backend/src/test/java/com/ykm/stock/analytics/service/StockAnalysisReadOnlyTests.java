package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.service.market.MarketStatusService;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.service.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.ExecutionCostCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.TradePlanCalculator;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockAnalysisReadOnlyTests {
    private static final LocalDate DAY = LocalDate.parse("2025-05-02");
    private static final Instant CLOSE = Instant.parse("2025-05-02T20:00:00Z");
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final QuoteNormalizationService prices = mock(QuoteNormalizationService.class);
    private final PriceStructureService structures = mock(PriceStructureService.class);
    private final SupportResistanceService levels = mock(SupportResistanceService.class);
    private final TradePlanService plans = mock(TradePlanService.class);
    private final MarketContextService market = mock(MarketContextService.class);
    private final StockObservationService stockObservations = mock(StockObservationService.class);
    private final StockAnalysisService service = new StockAnalysisService(stocks, industries, calendar, prices, structures, levels, plans, market, stockObservations);

    @BeforeEach
    void setup() {
        Stock stock = new Stock();
        stock.setFigi("TEST_FIGI");
        stock.setSymbol("TEST");
        stock.setLongName("Test issuer");
        when(stocks.findById("TEST_FIGI")).thenReturn(Optional.of(stock));
        when(calendar.isTradingDay(DAY)).thenReturn(true);
        when(calendar.getSessionCloseAt(DAY)).thenReturn(CLOSE);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(DAY);
        when(prices.normalize(any(), any(), any())).thenReturn(MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("PRICE_MISSING", "PRICE", "No stored price")));
        when(stockObservations.read(eq("TEST_FIGI"), any())).thenReturn(new FutuObservationsDto(null, List.of()));
    }

    // Before: report assembly could ingest/recalculate behind a read API. After: missing stored valuation stays missing with a reason.
    @Test
    void missingValuationDoesNotTriggerHiddenCalculationOrWrite() {
        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, AnalysisHorizon.POSITION));
        assertThat(report.valuation()).isNull();
        assertThat(report.reasons()).extracting(AnalysisReasonDto::code).contains("VALUATION_NOT_CALCULATED", "PRICE_MISSING");
        verify(stocks, never()).save(any());
        verifyNoInteractions(structures, levels, plans, market);
    }

    // Before: default latest report hid observations downloaded after the close.
    // After: financial valuation still uses the completed session, while supplemental Futu/forecasts use the actual read time.
    @Test
    void defaultDateKeepsFinancialCloseButReadsLatestSupplementalObservations() {
        Instant before = Instant.now();
        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", null, null));
        Instant after = Instant.now();
        assertThat(report.valuationDate()).isEqualTo(DAY);
        verify(stockObservations).read(eq("TEST_FIGI"), argThat(cutoff -> !cutoff.isBefore(before) && !cutoff.isAfter(after)));
        verify(prices).normalize(argThat(context -> context.analysisAsOf().equals(CLOSE)
                && !context.observationCutoff().isBefore(before) && !context.observationCutoff().isAfter(after)), eq(DAY.minusYears(5)), eq(DAY));
    }

    // Before: relaxing latest observations could accidentally leak today's forecasts into a dated report.
    // After: explicitly selected historical dates keep a historical observation cutoff.
    @Test
    void explicitlyDatedReportKeepsSupplementalObservationsAtHistoricalClose() {
        service.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, null));
        verify(stockObservations).read("TEST_FIGI", CLOSE);
        verify(stockObservations).monthlyValuations("TEST_FIGI", DAY.minusYears(5), DAY, CLOSE);
        verify(prices).normalize(argThat(context -> context.analysisAsOf().equals(CLOSE)
                && context.observationCutoff().equals(CLOSE)), eq(DAY.minusYears(5)), eq(DAY));
    }

    @Test
    void reportUsesTheIndustryClassificationKnownAtTheAnalysisCutoff() {
        StockIndustry industry = new StockIndustry();
        industry.setCode("I1");
        StockIndustryHistory history = new StockIndustryHistory();
        history.setIndustry(industry);
        when(industries.findAt("TEST_FIGI", CLOSE)).thenReturn(Optional.of(history));
        when(stockObservations.valuation("TEST_FIGI", DAY, CLOSE, "I1")).thenReturn(new ValuationResultDto(
                "TEST_FIGI", DAY, CLOSE, "FINANCIAL_REPORT_AS_OBSERVED", "I1", "PARTIAL", "VALUATION_INPUTS_INCOMPLETE", null, null, null, null, null, null, null, null));

        StockAnalysisReportDto report = service.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, null));

        assertThat(report.valuation().industryCode()).isEqualTo("I1");
        assertThat(report.valuation().source()).isEqualTo("FINANCIAL_REPORT_AS_OBSERVED");
    }

    // Before: a future/unclosed date might reuse today's inputs. After: invalid requested dates are rejected before any valuation read.
    @Test
    void nonTradingDateCannotBeSilentlyReplaced() {
        LocalDate weekend = LocalDate.parse("2025-05-03");
        when(calendar.isTradingDay(weekend)).thenReturn(false);
        assertThatThrownBy(() -> service.analyze(new AnalysisReportRequest("TEST_FIGI", weekend, null)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(prices, stockObservations);
    }

    /** 已入庫倍數與合資格技術輸入經正式 reader／plan 組裝；測試數字不是實際股票買賣建議。 */
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

    private StockAnalysisReportDto assembledReport(boolean hasValuation, boolean hasResistance) {
        StockValuationDailyRepository savedValuations = mock(StockValuationDailyRepository.class);
        StockEarningsForecastRepository forecasts = mock(StockEarningsForecastRepository.class);
        StockValuationDaily valuation = new StockValuationDaily();
        valuation.setFigi("TEST_FIGI");
        valuation.setValuationDate(DAY);
        valuation.setValuationCutoff(CLOSE);
        valuation.setClosePrice(new BigDecimal("98"));
        valuation.setPe(new BigDecimal("20"));
        when(savedValuations.findByFigiAndValuationDateAndValuationCutoffLessThanEqual("TEST_FIGI", DAY, CLOSE))
                .thenReturn(hasValuation ? Optional.of(valuation) : Optional.empty());
        StockObservationService stored = new StockObservationService(savedValuations, forecasts);
        ExecutionCostCalculator costs = new ExecutionCostCalculator();
        TradePlanService realPlans = new TradePlanService(new TradePlanCalculator(costs), costs, calendar);
        StockAnalysisService reportService = new StockAnalysisService(stocks, industries, calendar, prices, structures, levels, realPlans, market, stored);
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
        when(prices.normalize(any(), any(), any())).thenReturn(MetricResult.available(price));
        when(structures.analyze(any(), same(price))).thenReturn(MetricResult.available(structure));
        when(levels.analyze(any(), same(price), same(structure))).thenReturn(MetricResult.available(zones));
        when(market.evaluate(any(), same(price), same(structure))).thenReturn(MetricResult.available(background));
        StockAnalysisReportDto report = reportService.analyze(new AnalysisReportRequest("TEST_FIGI", DAY, AnalysisHorizon.SWING));
        verify(savedValuations).findByFigiAndValuationDateAndValuationCutoffLessThanEqual("TEST_FIGI", DAY, CLOSE);
        verify(savedValuations, never()).save(any());
        verify(savedValuations, never()).saveAll(any());
        return report;
    }

    private LevelZoneDto zone(String id, String low, String high, ZoneRole role, Instant confirmedAt) {
        BigDecimal lower = new BigDecimal(low);
        BigDecimal upper = new BigDecimal(high);
        return new LevelZoneDto(id, AnalysisTimeframe.DAILY, lower, upper, role, new BigDecimal("70"), confirmedAt, 1, confirmedAt,
                ZoneState.ACTIVE, lower.add(upper).divide(new BigDecimal("2")), new BigDecimal("5"), 1, ZoneStrengthLabel.HIGH,
                new BigDecimal("70"), 1, confirmedAt, null, List.of(), List.of(), 1, 0, 1, null, null, RetestState.NONE, false, List.of());
    }
}
