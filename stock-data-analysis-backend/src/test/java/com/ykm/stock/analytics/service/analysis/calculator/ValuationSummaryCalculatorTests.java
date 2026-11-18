package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryMethod;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EventRiskDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MomentumAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PlanConstraintDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceRelation;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PriceStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RetestState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SetupType;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.StructureTrendDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.SupportResistanceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TimeframeStructureDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TradePlanStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TreasuryContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.TrendState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValuationSummaryCalculatorTests {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 13);
    private static final Instant CLOSE = Instant.parse("2026-11-13T21:00:00Z");

    private final ValuationSummaryCalculator calculator = new ValuationSummaryCalculator();

    @Test
    void compositeBandExcludesIndustryAssumptionAndEliminatesScoreVsPriceContradiction() {
        // 重現 Astra 反例：現價 100、PE 20、EPS 成長 +40%（PEG 0.50），歷史參考 [75, 90, 105]、同業參考 [80, 95, 110]、人工假設 [500, 550, 600]
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("15"), new BigDecimal("18"), new BigDecimal("21"),
                new BigDecimal("75"), new BigDecimal("105"), 48, 60, 12, DAY.minusYears(4), DAY,
                new BigDecimal("20"), new BigDecimal("70"), new BigDecimal("90"));
        ValuationReferenceDto peer = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_PEER_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("16"), new BigDecimal("19"), new BigDecimal("22"),
                new BigDecimal("80"), new BigDecimal("110"), 12, null, null, DAY, DAY,
                new BigDecimal("20"), new BigDecimal("55"), new BigDecimal("95"));
        ValuationReferenceDto assumption = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_INDUSTRY_ASSUMPTION, "PE", "AVAILABLE", null,
                new BigDecimal("100"), null, new BigDecimal("120"),
                new BigDecimal("500"), new BigDecimal("600"), null, null, null, null, null,
                new BigDecimal("20"), null, new BigDecimal("550"));

        ValuationResultDto valuation = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU_AND_SEC_FINANCIAL_FACTS", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("100"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, peer, assumption);

        FundamentalSnapshotDto strongGrowth = fundamentals(false, "0.25", "0.40", "0.35", "0.50", "0.22", "0.18", "-0.50", "0.05", "0.05");

        InvestmentSummaryDto summary = calculator.calculate(
                valuation, null, strongGrowth, structure(TrendState.UPTREND, "100"), null, null, marketContext("4.35", "90", EntryPermission.ALLOW, false)).value();

        // 1) [L, M, H] 僅由歷史與同業 50/50 合成：[77.50, 92.50, 107.50]，完全排除 500–600 的人工產業假設
        assertThat(summary.anchorSource()).isEqualTo("HISTORICAL_AND_PEER");
        assertThat(summary.fairValue().low()).isEqualByComparingTo("77.5000");
        assertThat(summary.fairValue().mid()).isEqualByComparingTo("92.5000");
        assertThat(summary.fairValue().high()).isEqualByComparingTo("107.5000");
        assertThat(summary.fairValue().assumptionLow()).isEqualByComparingTo("500");
        assertThat(summary.fairValue().assumptionHigh()).isEqualByComparingTo("600");

        // 2) 現價 100 高於綜合中樞 92.50（discountToMid = -8.1081%）-> 標籤必為 FAIR_UPPER（絕不因 PEG 0.50 誤報 UNDERVALUED）
        assertThat(summary.verdict()).isEqualTo("FAIR_UPPER");
        assertThat(summary.discountToMid()).isEqualByComparingTo("-0.081081");
        assertThat(summary.fundamentalBacking()).isEqualTo("STRONG");
        assertThat(summary.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");

        // 3) 10Y 美債 4.35（即 4.35% -> 0.0435）對 FCF 殖利率 0.05（5.00%）-> 利差精準為 +0.0065（+0.65%），無 100 倍量綱誤差
        assertThat(summary.yieldSpreadVs10y().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(summary.yieldSpreadVs10y().value()).isEqualByComparingTo("0.006500");
        assertThat(summary.yieldSpreadBasis()).isEqualTo("FCF_YIELD_MINUS_10Y");
    }

    @Test
    void highDebtNullPriceLowAnchorIsExcludedAndSingleValidAnchorIsLabeledClearly() {
        // 歷史錨點有效 [120, 150, 180]；同業錨點因高負債 EV/EBITDA 使 priceLow == null -> 僅採 HISTORICAL_ONLY
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "EV_EBITDA", "AVAILABLE", null,
                new BigDecimal("8"), new BigDecimal("10"), new BigDecimal("12"),
                new BigDecimal("120"), new BigDecimal("180"), 36, 40, 4, DAY.minusYears(3), DAY,
                new BigDecimal("7"), new BigDecimal("10"), new BigDecimal("150"));
        ValuationReferenceDto peerMissingLow = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_PEER_MARKET, "EV_EBITDA", "AVAILABLE", null,
                new BigDecimal("4"), new BigDecimal("9"), new BigDecimal("13"),
                null, null, 8, null, null, DAY, DAY,
                new BigDecimal("7"), new BigDecimal("35"), new BigDecimal("130"));
        ValuationResultDto valuation = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU_AND_SEC_FINANCIAL_FACTS", "I10", "NON_FINANCIAL_OPERATING", "EV_EBITDA",
                "AVAILABLE", null, null, new BigDecimal("100"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, peerMissingLow, null);

        InvestmentSummaryDto summary = calculator.calculate(valuation, null, null, null, null, null, null).value();
        assertThat(summary.anchorSource()).isEqualTo("HISTORICAL_ONLY");
        assertThat(summary.fairValue().low()).isEqualByComparingTo("120");
        assertThat(summary.fairValue().mid()).isEqualByComparingTo("150");
        assertThat(summary.fairValue().high()).isEqualByComparingTo("180");
        // 100 < 120 * 0.90 (108) -> DEEP_UNDERVALUED
        assertThat(summary.verdict()).isEqualTo("DEEP_UNDERVALUED");
    }

    @Test
    void financialAndNegativeBookEquityBlueChipQualifyForStrongWhileLowPegCannotOverrideRevenueDropOrNegativeFcf() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PB", "AVAILABLE", null,
                new BigDecimal("1.2"), new BigDecimal("1.6"), new BigDecimal("2.0"),
                new BigDecimal("120"), new BigDecimal("200"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("1.1"), new BigDecimal("15"), new BigDecimal("160"));
        ValuationResultDto valDto = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I_BANK", "BANK", "PB",
                "AVAILABLE", null, null, new BigDecimal("115"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);

        // 1) 銀行股豁免 FCF 與 NetDebt/EBITDA，依正營收成長、正 EPS 成長與 ROE >= 10% 獲評 STRONG，利差採盈餘殖利率
        FundamentalSnapshotDto bankFund = fundamentals(true, "0.08", "0.12", "0.10", null, "0.14", null, null, null, "0.075");
        InvestmentSummaryDto bankSummary = calculator.calculate(
                valDto, null, bankFund, null, null, null, marketContext("4.25", null, EntryPermission.ALLOW, false)).value();
        assertThat(bankSummary.fundamentalBacking()).isEqualTo("STRONG");
        assertThat(bankSummary.yieldSpreadBasis()).isEqualTo("EARNINGS_YIELD_MINUS_10Y");
        assertThat(bankSummary.yieldSpreadVs10y().value()).isEqualByComparingTo("0.032500");

        // 2) 高回購負帳面值藍籌（ROE = NOT_APPLICABLE，但 FCF Margin = 16%、NetDebt/EBITDA = 1.8x）仍獲評 STRONG
        FundamentalSnapshotDto buybackBlueChip = fundamentals(false, "0.07", "0.14", "0.12", "1.10", null, "0.16", "1.80", "0.045", "0.05");
        InvestmentSummaryDto blueChipSummary = calculator.calculate(valDto, null, buybackBlueChip, null, null, null, null).value();
        assertThat(blueChipSummary.fundamentalBacking()).isEqualTo("STRONG");

        // 3) 單憑低 PEG（0.80）絕不可覆蓋營收衰退（-3%）或負 FCF（-2%）進入 STRONG
        FundamentalSnapshotDto shrinkingRev = fundamentals(false, "-0.03", "0.20", "0.15", "0.80", "0.20", "0.12", "1.0", "0.04", "0.05");
        assertThat(calculator.calculate(valDto, null, shrinkingRev, null, null, null, null).value().fundamentalBacking())
                .isEqualTo("MIXED");
        FundamentalSnapshotDto negativeFcf = fundamentals(false, "0.08", "0.20", "0.15", "0.80", "0.20", "-0.02", "1.0", "-0.01", "0.05");
        assertThat(calculator.calculate(valDto, null, negativeFcf, null, null, null, null).value().fundamentalBacking())
                .isEqualTo("WEAKENING");
    }

    @Test
    void stanceRespectsProtectiveStopBreachReclaimEventBlockAndValueTrap() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("18"), new BigDecimal("22"), new BigDecimal("26"),
                new BigDecimal("180"), new BigDecimal("260"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("17"), new BigDecimal("12"), new BigDecimal("220"));
        ValuationResultDto undervalued = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("170"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        FundamentalSnapshotDto strong = fundamentals(false, "0.12", "0.18", "0.15", "0.95", "0.25", "0.20", "-0.40", "0.05", "0.058");

        LevelZoneDto activeZone = new LevelZoneDto("Z1", AnalysisTimeframe.WEEKLY, new BigDecimal("165"), new BigDecimal("172"),
                ZoneRole.SUPPORT, new BigDecimal("65"), CLOSE, 3, CLOSE, ZoneState.ACTIVE, new BigDecimal("168.5"), new BigDecimal("6"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("65"), 1, CLOSE, null, List.of(), List.of(), 2, 0, 5, null, null, RetestState.NONE, true, List.of());
        // 現價 170 位於 [165, 172] 區間內，SupportResistanceService 會放入 containingZones -> 必須正確配對 Z1
        MetricResult<SupportResistanceDto> sr = MetricResult.available(new SupportResistanceDto(List.of(), List.of(), List.of(activeZone), List.of(), List.of(), List.of()));

        // 1) 估值偏低 + STRONG + containingZones 有效週線支撐強度 65 + MarketContextService 正式 ALLOW ("NO_KNOWN_BLOCKING_EVENT") -> ACCUMULATE_AT_SUPPORT 且 eventGateBlocked=false
        InvestmentSummaryDto clean = calculator.calculate(
                undervalued, null, strong, structure(TrendState.UPTREND, "170"), sr, null,
                marketContext("4.10", "160", EntryPermission.ALLOW, false)).value();
        assertThat(clean.verdict()).isEqualTo("MODERATELY_UNDERVALUED");
        assertThat(clean.eventGateBlocked()).isFalse();
        assertThat(clean.stance()).isEqualTo("ACCUMULATE_AT_SUPPORT");
        assertThat(clean.technicalSupportZoneId()).isEqualTo("Z1");

        // 2) TradePlan 已判定 RECLAIM_REQUIRED 或 TECHNICAL_INVALIDATION_CONFIRMED -> Stance 強制退回 WAIT_FOR_STABILIZATION
        TradePlanDto reclaimPlan = new TradePlanDto("P1", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.WAITING_TRIGGER, List.of(AnalysisReasonDto.of("RECLAIM_REQUIRED", "TRADE_PLAN", "待收復入場區")),
                "Z1", 1, AnalysisTimeframe.WEEKLY, CLOSE, DAY.plusDays(5), List.of());
        MetricResult<TradePlanResultDto> reclaimPlans = MetricResult.available(
                new TradePlanResultDto(List.of(reclaimPlan), new BigDecimal("170"), DAY, null, List.of(), List.of(), true));
        InvestmentSummaryDto reclaimSummary = calculator.calculate(
                undervalued, null, strong, structure(TrendState.UPTREND, "170"), sr, reclaimPlans,
                marketContext("4.10", "160", EntryPermission.ALLOW, false)).value();
        assertThat(reclaimSummary.stance()).isEqualTo("WAIT_FOR_STABILIZATION");

        TradePlanDto invalidatedPlan = new TradePlanDto("P2", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.INVALIDATED, List.of(AnalysisReasonDto.of("TECHNICAL_INVALIDATION_CONFIRMED", "TRADE_PLAN", "連續兩日收於失效價下")),
                "Z1", 1, AnalysisTimeframe.WEEKLY, CLOSE, DAY.plusDays(5), List.of());
        MetricResult<TradePlanResultDto> invalidatedPlans = MetricResult.available(
                new TradePlanResultDto(List.of(invalidatedPlan), new BigDecimal("170"), DAY, null, List.of(), List.of(), true));
        InvestmentSummaryDto invalidatedSummary = calculator.calculate(
                undervalued, null, strong, structure(TrendState.UPTREND, "170"), sr, invalidatedPlans,
                marketContext("4.10", "160", EntryPermission.ALLOW, false)).value();
        assertThat(invalidatedSummary.technicalSupportZoneId()).isNull();
        assertThat(invalidatedSummary.stance()).isEqualTo("WAIT_FOR_STABILIZATION");

        // 3) 財報前事件門鎖 BLOCK -> WAIT_FOR_STABILIZATION 且標示 eventGateBlocked
        InvestmentSummaryDto eventBlocked = calculator.calculate(
                undervalued, null, strong, structure(TrendState.UPTREND, "170"), sr, null,
                marketContext("4.10", "160", EntryPermission.BLOCK, true)).value();
        assertThat(eventBlocked.eventGateBlocked()).isTrue();
        assertThat(eventBlocked.stance()).isEqualTo("WAIT_FOR_STABILIZATION");

        // 4) 基本面 WEAKENING -> CAUTION_VALUE_TRAP
        FundamentalSnapshotDto weakening = fundamentals(false, "-0.15", "-0.30", null, null, "0.03", "0.02", "4.50", "0.01", "0.04");
        InvestmentSummaryDto trap = calculator.calculate(
                undervalued, null, weakening, structure(TrendState.SIDEWAYS, "170"), sr, null,
                marketContext("4.10", "160", EntryPermission.ALLOW, false)).value();
        assertThat(trap.fundamentalBacking()).isEqualTo("WEAKENING");
        assertThat(trap.stance()).isEqualTo("CAUTION_VALUE_TRAP");
    }

    private FundamentalSnapshotDto fundamentals(
            boolean financial, String revYoy, String epsYoy, String epsCagr, String peg,
            String roe, String fcfMargin, String debtEbitda, String fcfYield, String earningsYield) {
        MetricResult<BigDecimal> na = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TEST", "NA"));
        return new FundamentalSnapshotDto(
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2025, 9, 30),
                LocalDate.of(2023, 9, 30),
                "USD",
                financial,
                revYoy == null ? na : MetricResult.available(new BigDecimal(revYoy)),
                epsYoy == null ? na : MetricResult.available(new BigDecimal(epsYoy)),
                na,
                na,
                epsCagr == null ? na : MetricResult.available(new BigDecimal(epsCagr)),
                peg == null ? na : MetricResult.available(new BigDecimal(peg)),
                peg == null ? null : "TRAILING_EPS_CAGR_3Y",
                roe == null ? na : MetricResult.available(new BigDecimal(roe)),
                fcfMargin == null ? na : MetricResult.available(new BigDecimal(fcfMargin)),
                na,
                debtEbitda == null ? na : MetricResult.available(new BigDecimal(debtEbitda)),
                fcfYield == null ? na : MetricResult.available(new BigDecimal(fcfYield)),
                earningsYield == null ? na : MetricResult.available(new BigDecimal(earningsYield)),
                na
        );
    }

    private MetricResult<PriceStructureDto> structure(TrendState weeklyState, String dailyClose) {
        NormalizedBarDto bar = new NormalizedBarDto(DAY, new BigDecimal(dailyClose), new BigDecimal(dailyClose),
                new BigDecimal(dailyClose), new BigDecimal(dailyClose), MetricResult.available(BigDecimal.ONE),
                CLOSE, CLOSE, CLOSE, CLOSE, null, "B1");
        MetricResult<StructureTrendDto> trend = MetricResult.available(
                new StructureTrendDto(weeklyState, CLOSE, PriceRelation.HH, PriceRelation.HL, List.of(), CLOSE));
        TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(bar), null, List.of(), List.of(),
                null, null, trend, trend, List.of(), List.of());
        TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, List.of(bar), null, List.of(), List.of(),
                null, null, trend, trend, List.of(), List.of());
        return MetricResult.available(new PriceStructureDto("FIGI", null, DAY, List.of(daily, weekly), List.of(), List.of()));
    }

    private MetricResult<MarketContextDto> marketContext(String tenYearPct, String sma200, EntryPermission perm, boolean earningsBlock) {
        TreasuryContextDto treasury = new TreasuryContextDto(
                MetricResult.available(new BigDecimal(tenYearPct)), null, null, null, List.of(), List.of());
        Map<String, MetricResult<BigDecimal>> inds = sma200 == null ? Map.of()
                : Map.of("SMA200", MetricResult.available(new BigDecimal(sma200)));
        MomentumAnalysisDto detail = new MomentumAnalysisDto(inds, null, List.of());
        List<EventRiskDto> events = earningsBlock
                ? List.of(new EventRiskDto("E1", "EARNINGS", "QUARTERLY", "Q4 Earnings", DAY.plusDays(1), null, "DATE_ONLY", CLOSE, "FUTU", true))
                : List.of();
        PlanConstraintDto constraint = new PlanConstraintDto(perm,
                earningsBlock ? List.of("EARNINGS_EVENT_WINDOW") : List.of("NO_KNOWN_BLOCKING_EVENT"), List.of());
        return MetricResult.available(new MarketContextDto(null, null, null, events, constraint, List.of(), List.of(),
                detail, null, null, treasury, Map.of()));
    }
}
