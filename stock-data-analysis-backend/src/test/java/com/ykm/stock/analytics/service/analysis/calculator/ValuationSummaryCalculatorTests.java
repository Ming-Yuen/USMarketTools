package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisTimeframe;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosis;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.BreakoutDiagnosisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryMethod;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EntryPermission;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.EventRiskDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FxConversionDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.InvestmentSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.LevelZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketContextDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
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
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.VolumeAnalysisDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneRole;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneState;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ZoneStrengthLabel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
        // 被剔除之殘缺錨點不得殘留 peerMid 或觸發 ANCHOR_DIVERGENCE
        assertThat(summary.fairValue().peerMid()).isNull();
        assertThat(summary.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code).doesNotContain("ANCHOR_DIVERGENCE");
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

        // 5) 週期對齊與待確認兜底：POSITION 只配對 WEEKLY 支撐；即使存在強度更高的 DAILY 支撐 D1，若 WEEKLY 方案處於 STRUCTURE_CONFIRMATION_REQUIRED，絕不誤報 ACCUMULATE_AT_SUPPORT
        LevelZoneDto strongerDailyZone = new LevelZoneDto("D1", AnalysisTimeframe.DAILY, new BigDecimal("166"), new BigDecimal("171"),
                ZoneRole.SUPPORT, new BigDecimal("85"), CLOSE, 4, CLOSE, ZoneState.ACTIVE, new BigDecimal("168.5"), new BigDecimal("4"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("85"), 1, CLOSE, null, List.of(), List.of(), 3, 0, 2, null, null, RetestState.NONE, false, List.of());
        MetricResult<SupportResistanceDto> mixedZones = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(activeZone, strongerDailyZone), List.of(), List.of(), List.of()));
        TradePlanDto weeklyPendingPlan = new TradePlanDto("PW", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.WAITING_TRIGGER, List.of(AnalysisReasonDto.of("STRUCTURE_CONFIRMATION_REQUIRED", "TRADE_PLAN", "結構待確認")),
                "Z1", 1, AnalysisTimeframe.WEEKLY, CLOSE, DAY.plusDays(5), List.of());
        MetricResult<TradePlanResultDto> weeklyPendingPlans = MetricResult.available(
                new TradePlanResultDto(List.of(weeklyPendingPlan), new BigDecimal("170"), DAY, null, List.of(), List.of(), true));

        InvestmentSummaryDto positionPending = calculator.calculate(
                AnalysisHorizon.POSITION, undervalued, null, strong, structure(TrendState.UPTREND, "170"), mixedZones, weeklyPendingPlans,
                marketContext("4.10", "160", EntryPermission.ALLOW, false)).value();
        assertThat(positionPending.technicalSupportZoneId()).isEqualTo("Z1");
        assertThat(positionPending.stance()).isEqualTo("WAIT_FOR_STABILIZATION");

        InvestmentSummaryDto swingPaired = calculator.calculate(
                AnalysisHorizon.SWING, undervalued, null, strong, structure(TrendState.UPTREND, "170"), mixedZones, null,
                marketContext("4.10", "160", EntryPermission.ALLOW, false)).value();
        assertThat(swingPaired.technicalSupportZoneId()).isEqualTo("D1");
        assertThat(swingPaired.technicalSupportTimeframe()).isEqualTo("DAILY");
        assertThat(swingPaired.stance()).isEqualTo("ACCUMULATE_AT_SUPPORT");
    }

    @Test
    void forwardContractionOr90dDowngradeTriggersValueTrapWhilePreservingEventGateAndAllHighFlags() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("18"), new BigDecimal("22"), new BigDecimal("26"),
                new BigDecimal("180"), new BigDecimal("260"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("16"), new BigDecimal("10"), new BigDecimal("220"));
        ValuationResultDto undervalued = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("160"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        FundamentalSnapshotDto trailingStrong = fundamentals(false, "0.15", "0.20", "0.18", "0.90", "0.25", "0.18", "0.50", "0.06", "0.06");
        MetricResult<BigDecimal> na = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("NA", "TEST", "NA"));

        // 1) TTM 看似低估且 STRONG，但 FY1 共識 EPS 較 TTM 恰好衰退 -20.00% -> 觸發 CAUTION_VALUE_TRAP 與 FORWARD_EARNINGS_CONTRACTION
        ForwardMetrics contraction20 = new ForwardMetrics(
                DAY, "2027", DAY, "USD",
                MetricResult.available(new BigDecimal("20.0000")), na, na, null,
                MetricResult.available(new BigDecimal("-0.200000")),
                "2027", "ANNUAL", DAY, null, null, na, na, na, na);
        InvestmentSummaryDto trapByContraction = calculator.calculate(
                AnalysisHorizon.POSITION, undervalued, null, trailingStrong,
                new FutuObservationsDto(null, List.of(), contraction20),
                structure(TrendState.UPTREND, "160"), null, null,
                marketContext("4.10", "150", EntryPermission.ALLOW, false)).value();
        assertThat(trapByContraction.verdict()).isEqualTo("DEEP_UNDERVALUED");
        assertThat(trapByContraction.stance()).isEqualTo("CAUTION_VALUE_TRAP");
        assertThat(trapByContraction.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("FORWARD_EARNINGS_CONTRACTION");

        // 2) 近 90 日預期 EPS 恰好下修 -10.00%，且同時存在事件門鎖與破位、以及負 FCF 等多個 HIGH 風險 -> Stance 優先保留 WAIT_FOR_STABILIZATION，且前瞻風險不被截斷
        ForwardMetrics downgrade10 = new ForwardMetrics(
                DAY, null, null, null, na, na, na, null, na,
                "2026Q4", "QUARTERLY", DAY, null, DAY.minusDays(90),
                na, MetricResult.available(new BigDecimal("-0.100000")), na, na);
        FundamentalSnapshotDto multipleHighRisks = fundamentals(false, "-0.15", "-0.25", null, null, "0.02", "-0.05", "5.0", "-0.02", "0.04");
        TradePlanDto breachedPlan = new TradePlanDto("P_STOP", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.INVALIDATED, List.of(AnalysisReasonDto.of("PROTECTIVE_STOP_BREACHED", "TRADE_PLAN", "破止損")),
                "Z1", 1, AnalysisTimeframe.WEEKLY, CLOSE, DAY.plusDays(5), List.of());
        MetricResult<TradePlanResultDto> breachedPlans = MetricResult.available(
                new TradePlanResultDto(List.of(breachedPlan), new BigDecimal("160"), DAY, null, List.of(), List.of(), true));

        InvestmentSummaryDto gatedWithForward = calculator.calculate(
                AnalysisHorizon.POSITION, undervalued, null, multipleHighRisks,
                new FutuObservationsDto(null, List.of(), downgrade10),
                structure(TrendState.DOWNTREND, "160"), null, breachedPlans,
                marketContext("4.10", "180", EntryPermission.BLOCK, true)).value();
        assertThat(gatedWithForward.stance()).isEqualTo("WAIT_FOR_STABILIZATION");
        assertThat(gatedWithForward.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("EARNINGS_IMMINENT_BLOCK", "PROTECTIVE_STOP_OR_RECLAIM_PENDING", "EARNINGS_FORECAST_DOWNGRADE_90D");

        // 3) 保持簽核範圍外 stance 不變：FAIR_UPPER + STRONG + UPTREND 遇前瞻下修維持 HOLD_TRAIL_NO_CHASE（僅於風險旗標提示）；
        //    且無前瞻訊號時 DEEP_UNDERVALUED + WEAKENING + confirmationPending 維持原有 CAUTION_VALUE_TRAP
        ValuationResultDto fairUpperVal = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("240"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        InvestmentSummaryDto fairUpperWithTrap = calculator.calculate(
                AnalysisHorizon.POSITION, fairUpperVal, null, trailingStrong,
                new FutuObservationsDto(null, List.of(), downgrade10),
                structure(TrendState.UPTREND, "240"), null, null,
                marketContext("4.10", "200", EntryPermission.ALLOW, false)).value();
        assertThat(fairUpperWithTrap.verdict()).isEqualTo("FAIR_UPPER");
        assertThat(fairUpperWithTrap.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(fairUpperWithTrap.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("EARNINGS_FORECAST_DOWNGRADE_90D");

        TradePlanDto confirmPendingPlan = new TradePlanDto("P_CONF", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.WAITING_TRIGGER, List.of(AnalysisReasonDto.of("STRUCTURE_CONFIRMATION_REQUIRED", "TRADE_PLAN", "待確認")),
                "Z1", 1, AnalysisTimeframe.WEEKLY, CLOSE, DAY.plusDays(5), List.of());
        MetricResult<TradePlanResultDto> confirmPendingPlans = MetricResult.available(
                new TradePlanResultDto(List.of(confirmPendingPlan), new BigDecimal("160"), DAY, null, List.of(), List.of(), true));
        InvestmentSummaryDto weakeningWithConfirmPending = calculator.calculate(
                AnalysisHorizon.POSITION, undervalued, null, multipleHighRisks, null,
                structure(TrendState.UPTREND, "160"), null, confirmPendingPlans,
                marketContext("4.10", "150", EntryPermission.ALLOW, false)).value();
        assertThat(weakeningWithConfirmPending.stance()).isEqualTo("CAUTION_VALUE_TRAP");

        // 4) DEEP_UNDERVALUED + WEAKENING + 前瞻陷阱 + 僅有事件門鎖（無破位）-> 優先為 WAIT_FOR_STABILIZATION；
        //    而僅有一般 confirmationPending + 前瞻陷阱（無破位、無事件封鎖）-> 返回 CAUTION_VALUE_TRAP
        InvestmentSummaryDto weakeningForwardEventOnly = calculator.calculate(
                AnalysisHorizon.POSITION, undervalued, null, multipleHighRisks,
                new FutuObservationsDto(null, List.of(), downgrade10),
                structure(TrendState.UPTREND, "160"), null, null,
                marketContext("4.10", "150", EntryPermission.BLOCK, true)).value();
        assertThat(weakeningForwardEventOnly.stance()).isEqualTo("WAIT_FOR_STABILIZATION");

        InvestmentSummaryDto forwardTrapWithConfirmPending = calculator.calculate(
                AnalysisHorizon.POSITION, undervalued, null, trailingStrong,
                new FutuObservationsDto(null, List.of(), downgrade10),
                structure(TrendState.UPTREND, "160"), null, confirmPendingPlans,
                marketContext("4.10", "150", EntryPermission.ALLOW, false)).value();
        assertThat(forwardTrapWithConfirmPending.stance()).isEqualTo("CAUTION_VALUE_TRAP");
    }

    @Test
    void marketRegimeHeadwindDowngradesUndervaluedAndPreservesFairValueBandAndMacroCautionTriggers() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("18"), new BigDecimal("22"), new BigDecimal("26"),
                new BigDecimal("180"), new BigDecimal("260"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("15"), new BigDecimal("5"), new BigDecimal("220"));
        ValuationResultDto deepUnder = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("150"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        FundamentalSnapshotDto strong = fundamentals(false, "0.18", "0.22", "0.20", "0.95", "0.24", "0.18", "0.40", "0.06", "0.065");
        LevelZoneDto weeklySupport = new LevelZoneDto("ZW", AnalysisTimeframe.WEEKLY, new BigDecimal("146"), new BigDecimal("152"),
                ZoneRole.SUPPORT, new BigDecimal("70"), CLOSE, 2, CLOSE, ZoneState.ACTIVE, new BigDecimal("149"), new BigDecimal("5"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("70"), 1, CLOSE, null, List.of(), List.of(), 2, 0, 2, null, null, RetestState.NONE, true, List.of());
        MetricResult<SupportResistanceDto> sr = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(weeklySupport), List.of(), List.of(), List.of()));

        // 1) BEAR 與 PANIC 一票降級：即使 DEEP_UNDERVALUED + STRONG + 強支撐，必須輸出 WAIT_FOR_MARKET_STABILIZATION，且 [L,M,H] 不變
        for (MarketRegime badRegime : List.of(MarketRegime.BEAR, MarketRegime.PANIC)) {
            InvestmentSummaryDto res = calculator.calculate(
                    AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                    structure(TrendState.UPTREND, "150"), sr, null,
                    richMarketContext(badRegime, "4.10", null, null, "140", null, null, null, null, null,
                            EntryPermission.BLOCK, List.of("MARKET_" + badRegime.name()), false)).value();
            assertThat(res.verdict()).isEqualTo("DEEP_UNDERVALUED");
            assertThat(res.stance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");
            assertThat(res.fairValue().low()).isEqualByComparingTo("180");
            assertThat(res.fairValue().mid()).isEqualByComparingTo("220");
            assertThat(res.fairValue().high()).isEqualByComparingTo("260");
            assertThat(res.macroCaution().active()).isTrue();
            assertThat(res.macroCaution().suggestedMarginBufferMin()).isEqualByComparingTo("0.05");
            assertThat(res.macroCaution().suggestedMarginBufferMax()).isEqualByComparingTo("0.10");
            assertThat(res.macroCaution().suggestedPositionScale()).isEqualByComparingTo("0.50");
            assertThat(res.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code).contains("MARKET_REGIME_HEADWIND");
        }

        // 2) BEAR + WEAKENING 保留 CAUTION_VALUE_TRAP 並並列宏觀警戒橫幅
        FundamentalSnapshotDto weak = fundamentals(false, "-0.15", "-0.30", null, null, "0.02", "-0.02", "4.50", "-0.01", "0.03");
        InvestmentSummaryDto bearTrap = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, weak, null,
                structure(TrendState.UPTREND, "150"), sr, null,
                richMarketContext(MarketRegime.BEAR, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.BLOCK, List.of("MARKET_BEAR"), false)).value();
        assertThat(bearTrap.stance()).isEqualTo("CAUTION_VALUE_TRAP");
        assertThat(bearTrap.macroCaution().active()).isTrue();

        // 3) CORRECTION：一般回踩 -> WAIT_FOR_MARKET_STABILIZATION；同區 SPRING_RECLAIMED -> WATCH_PULLBACK_TO_BAND；
        //    CORRECTION + CPI + 僅 Spring -> WAIT_FOR_MARKET_STABILIZATION；CORRECTION + CPI + CONFIRMED_RETEST -> WATCH_PULLBACK_TO_BAND
        InvestmentSummaryDto corrPullback = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), sr, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_CORRECTION"), false)).value();
        assertThat(corrPullback.stance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");

        BreakoutDiagnosisDto springOnZw = new BreakoutDiagnosisDto(
                BreakoutDiagnosis.SPRING_RECLAIMED, "ZW", 1, 1, AnalysisTimeframe.WEEKLY,
                new BigDecimal("146"), new BigDecimal("152"), DAY.minusDays(2), DAY.minusDays(1), DAY.plusDays(4), DAY,
                2, new BigDecimal("0.8"), new BigDecimal("1.5"), null, null, null, null, null, null,
                List.of("SPRING_RECLAIMED"), List.of());
        MetricResult<SupportResistanceDto> srSpring = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(weeklySupport), List.of(), List.of(), List.of(), springOnZw, List.of(springOnZw)));
        InvestmentSummaryDto corrSpring = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), srSpring, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_CORRECTION"), false)).value();
        assertThat(corrSpring.stance()).isEqualTo("WATCH_PULLBACK_TO_BAND");
        assertThat(corrSpring.rightSideConfirmationBasis()).isEqualTo("SPRING_RECLAIMED");

        InvestmentSummaryDto corrCpiSpring = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), srSpring, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("EVENT_REQUIRES_CLOSE_CONFIRMATION", "MARKET_CORRECTION"), false)).value();
        assertThat(corrCpiSpring.stance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");

        LevelZoneDto retestZone = new LevelZoneDto("ZW", AnalysisTimeframe.WEEKLY, new BigDecimal("146"), new BigDecimal("152"),
                ZoneRole.SUPPORT, new BigDecimal("70"), CLOSE, 2, CLOSE, ZoneState.ACTIVE, new BigDecimal("149"), new BigDecimal("5"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("70"), 2, CLOSE, new BigDecimal("150"), List.of(), List.of(), 2, 0, 2, null, null, RetestState.SUCCEEDED, true, List.of());
        MetricResult<SupportResistanceDto> srRetest = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(retestZone), List.of(), List.of(), List.of()));
        InvestmentSummaryDto corrCpiRetest = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), srRetest, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("EVENT_REQUIRES_CLOSE_CONFIRMATION", "MARKET_CORRECTION"), false)).value();
        assertThat(corrCpiRetest.stance()).isEqualTo("WATCH_PULLBACK_TO_BAND");
        assertThat(corrCpiRetest.rightSideConfirmationBasis()).isEqualTo("CONFIRMED_RETEST");

        // 4) regime 缺失但 VIX 恰 25.0 或 10Y 變動恰 +0.30 皆獨立觸發 MacroCaution；STALE VIX 不觸發
        InvestmentSummaryDto vix25Only = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), sr, null,
                richMarketContext(null, "4.10", null, MetricResult.available(new BigDecimal("25.0")), "140", null, null, null, null, null,
                        EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false)).value();
        assertThat(vix25Only.macroCaution().active()).isTrue();
        assertThat(vix25Only.macroCaution().triggerCodes()).containsExactly("VIX_ELEVATED");

        InvestmentSummaryDto rate30Only = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), sr, null,
                richMarketContext(null, "4.50", MetricResult.available(new BigDecimal("0.30")),
                        MetricResult.unavailable(MetricStatus.STALE, AnalysisReasonDto.of("VIX_CLOSE_STALE", "MARKET", "stale")),
                        "140", null, null, null, null, null,
                        EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false)).value();
        assertThat(rate30Only.macroCaution().active()).isTrue();
        assertThat(rate30Only.macroCaution().triggerCodes()).containsExactly("TREASURY_10Y_SURGE_20P");
    }

    @Test
    void trendPullbackStarterHandlesForwardPegHyperGrowthCapConsensusMissingAndBoundaries() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("18"), new BigDecimal("22"), new BigDecimal("26"),
                new BigDecimal("150"), new BigDecimal("200"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("28"), new BigDecimal("85"), new BigDecimal("175"));
        // 現價 210（位於 [200, 230] -> MODERATELY_OVERVALUED）
        ValuationResultDto modOver = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("210"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        // 現價 240（> 200 * 1.15 = 230 -> OVERVALUED_RISK）
        ValuationResultDto overRisk = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("240"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        FundamentalSnapshotDto strongHighGrowth = fundamentals(false, "0.28", "0.35", "0.30", "1.10", "0.26", "0.22", "-0.30", "0.04", "0.045");
        MetricResult<PriceStructureDto> pullbackStructure = pullbackDailyStructure(TrendState.UPTREND, "220", "211", "209", "212", "208", "210");
        MetricResult<MarketContextDto> riskOnCtx = richMarketContext(MarketRegime.RISK_ON, "4.10", null,
                MetricResult.available(new BigDecimal("16")), "185", "208", "207", "206", "5", "0.85",
                EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false);
        MetricResult<BigDecimal> na = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("NA", "TEST", "NA"));

        // 1) Forward PEG = 1.50（含等號邊界）+ RISK_ON + 回踩 EMA21 (208 -> +0.96%) + 縮量 RVOL 0.85 -> TREND_PULLBACK_STARTER (1/3)
        ForwardMetrics peg150 = new ForwardMetrics(DAY, "2027", DAY, "USD",
                MetricResult.available(new BigDecimal("28")), na, MetricResult.available(new BigDecimal("1.50")), "FORWARD_FY1_VS_TTM",
                MetricResult.available(new BigDecimal("0.25")), null, null, null, null, null, na, na, na, na);
        InvestmentSummaryDto starterOk = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), peg150),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(starterOk.verdict()).isEqualTo("MODERATELY_OVERVALUED");
        assertThat(starterOk.stance()).isEqualTo("TREND_PULLBACK_STARTER");
        assertThat(starterOk.trendPullbackStarter().eligible()).isTrue();
        assertThat(starterOk.trendPullbackStarter().matchedAnchor()).isEqualTo("EMA21");
        assertThat(starterOk.trendPullbackStarter().positionFraction()).isEqualTo("1/3");
        assertThat(starterOk.trendPullbackStarter().suggestedStop()).isEqualByComparingTo("205.5000");

        // 2) 中性市但 VIX=26 觸發宏觀警戒 -> 試單倉位明示減半為 1/6
        MetricResult<MarketContextDto> neutralHighVix = richMarketContext(MarketRegime.NEUTRAL, "4.10", null,
                MetricResult.available(new BigDecimal("26")), "185", "208", "207", "206", "5", "0.85",
                EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false);
        InvestmentSummaryDto starterHalved = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), peg150),
                pullbackStructure, null, null, neutralHighVix).value();
        assertThat(starterHalved.stance()).isEqualTo("TREND_PULLBACK_STARTER");
        assertThat(starterHalved.trendPullbackStarter().positionFraction()).isEqualTo("1/6");

        // 3) Forward PEG = 1.51 -> 維持 HOLD_TRAIL_NO_CHASE
        ForwardMetrics peg151 = new ForwardMetrics(DAY, "2027", DAY, "USD",
                MetricResult.available(new BigDecimal("32")), na, MetricResult.available(new BigDecimal("1.51")), "FORWARD_FY1_VS_TTM",
                MetricResult.available(new BigDecimal("0.21")), null, null, null, null, null, na, na, na, na);
        InvestmentSummaryDto pegTooHigh = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), peg151),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(pegTooHigh.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(pegTooHigh.trendPullbackStarter().eligible()).isFalse();
        assertThat(pegTooHigh.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("FORWARD_PEG_ABOVE_THRESHOLD");
        assertThat(pegTooHigh.trendPullbackStarter().suggestedStop()).isNull();
        assertThat(pegTooHigh.trendPullbackStarter().positionFraction()).isNull();

        // 4) FORWARD_EPS_GROWTH_ABOVE_PEG_CAP + Forward P/E = 35.0 + 真高成長 -> 放行；P/E = 35.1 或低基期 revYoy = 0.05 -> 擋下
        MetricResult<BigDecimal> pegCapNa = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                AnalysisReasonDto.of("FORWARD_EPS_GROWTH_ABOVE_PEG_CAP", "FORWARD_VALUATION", "超過 100% 上限"));
        ForwardMetrics hyperPe35 = new ForwardMetrics(DAY, "2027", DAY, "USD",
                MetricResult.available(new BigDecimal("35.0")), na, pegCapNa, null,
                MetricResult.available(new BigDecimal("1.20")), null, null, null, null, null, na, na, na, na);
        InvestmentSummaryDto hyperPass = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), hyperPe35),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(hyperPass.stance()).isEqualTo("TREND_PULLBACK_STARTER");
        assertThat(hyperPass.trendPullbackStarter().forwardGrowthBasis()).isEqualTo("HYPER_GROWTH_FORWARD_PE");

        ForwardMetrics hyperPe351 = new ForwardMetrics(DAY, "2027", DAY, "USD",
                MetricResult.available(new BigDecimal("35.1")), na, pegCapNa, null,
                MetricResult.available(new BigDecimal("1.20")), null, null, null, null, null, na, na, na, na);
        InvestmentSummaryDto hyperFailPe = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), hyperPe351),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(hyperFailPe.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(hyperFailPe.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("FORWARD_PE_ABOVE_HYPER_GROWTH_CAP");

        FundamentalSnapshotDto lowBaseStrong = fundamentals(false, "0.05", "0.35", "0.10", "1.00", "0.20", "0.15", "0.50", "0.04", "0.045");
        InvestmentSummaryDto hyperLowBase = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, lowBaseStrong, new FutuObservationsDto(null, List.of(), hyperPe35),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(hyperLowBase.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(hyperLowBase.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("HYPER_GROWTH_LOW_BASE_UNVERIFIED");

        // 5) 缺少年度共識 -> 維持 HOLD_TRAIL_NO_CHASE 並保留原始原因碼
        MetricResult<BigDecimal> noAnnual = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("ANNUAL_EARNINGS_FORECAST_MISSING", "FORWARD_VALUATION", "缺少年度預期"));
        ForwardMetrics missingAnnual = new ForwardMetrics(DAY, null, null, null, noAnnual, noAnnual, noAnnual, null, noAnnual,
                null, null, null, null, null, na, na, na, na);
        InvestmentSummaryDto noConsensus = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), missingAnnual),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(noConsensus.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(noConsensus.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("ANNUAL_EARNINGS_FORECAST_MISSING");

        // 6) 跳空重挫日（前收 220 -> 今日跳空開 207 收 210，雖收紅但單日跌 -4.5%）-> 不得誤放行；OVERVALUED_RISK 維持 CAUTION_OVEREXTENSION
        MetricResult<PriceStructureDto> gapDownStructure = pullbackDailyStructure(TrendState.UPTREND, "222", "220", "207", "211", "206", "210");
        InvestmentSummaryDto gapDownRejected = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), peg150),
                gapDownStructure, null, null, riskOnCtx).value();
        assertThat(gapDownRejected.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(gapDownRejected.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("TREND_PULLBACK_NOT_STABILIZED");

        InvestmentSummaryDto overRiskBlocked = calculator.calculate(
                AnalysisHorizon.SWING, overRisk, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), peg150),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(overRiskBlocked.verdict()).isEqualTo("OVERVALUED_RISK");
        assertThat(overRiskBlocked.stance()).isEqualTo("CAUTION_OVEREXTENSION");

        // 7) 金融股 ROE=11%（雖獲 STRONG 評級，但低於高成長豁免統一 12% 門檻）與平盤十字收最低（open==close==low）皆須擋下
        FundamentalSnapshotDto bankRoe11 = fundamentals(true, "0.25", "0.35", "0.25", "1.00", "0.11", null, null, null, "0.06");
        InvestmentSummaryDto bankRoe11Summary = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, bankRoe11, new FutuObservationsDto(null, List.of(), hyperPe35),
                pullbackStructure, null, null, riskOnCtx).value();
        assertThat(bankRoe11Summary.fundamentalBacking()).isEqualTo("STRONG");
        assertThat(bankRoe11Summary.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(bankRoe11Summary.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("HYPER_GROWTH_LOW_BASE_UNVERIFIED");

        MetricResult<PriceStructureDto> dojiAtLowStructure = pullbackDailyStructure(TrendState.UPTREND, "220", "211", "210", "214", "210", "210");
        InvestmentSummaryDto dojiBlocked = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, new FutuObservationsDto(null, List.of(), peg150),
                dojiAtLowStructure, null, null, riskOnCtx).value();
        assertThat(dojiBlocked.stance()).isEqualTo("HOLD_TRAIL_NO_CHASE");
        assertThat(dojiBlocked.trendPullbackStarter().ineligibleReasonCode()).isEqualTo("TREND_PULLBACK_NOT_STABILIZED");
    }

    @Test
    void expiredRetestZoneIsolationAndFairUpperMixedCorrectionEventGatesAreEnforced() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("18"), new BigDecimal("22"), new BigDecimal("26"),
                new BigDecimal("180"), new BigDecimal("260"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("16"), new BigDecimal("10"), new BigDecimal("220"));
        ValuationResultDto deepUnder = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("150"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        ValuationResultDto fairUpper = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("240"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        FundamentalSnapshotDto strong = fundamentals(false, "0.18", "0.22", "0.20", "0.95", "0.24", "0.18", "0.40", "0.06", "0.065");
        FundamentalSnapshotDto mixed = fundamentals(false, "0.04", "0.02", null, null, "0.09", "0.05", "1.50", "0.03", "0.04");

        // 1) 距離第四、數週前（21 天前）已過期之 retest 區域即使不在 plans 前三名中，於 CORRECTION+CPI 仍須降級 WAIT_FOR_MARKET_STABILIZATION
        Instant threeWeeksAgo = CLOSE.minusSeconds(21L * 86400);
        LevelZoneDto expiredRetestZone = new LevelZoneDto("Z4_EXPIRED", AnalysisTimeframe.WEEKLY, new BigDecimal("146"), new BigDecimal("152"),
                ZoneRole.SUPPORT, new BigDecimal("85"), threeWeeksAgo, 2, threeWeeksAgo, ZoneState.ACTIVE, new BigDecimal("149"), new BigDecimal("5"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("85"), 2, threeWeeksAgo, new BigDecimal("150"), List.of(), List.of(),
                2, 0, 2, null, null, RetestState.SUCCEEDED, true, List.of());
        MetricResult<SupportResistanceDto> srExpired = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(expiredRetestZone), List.of(), List.of(), List.of()));
        InvestmentSummaryDto expiredRetestSummary = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), srExpired, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("EVENT_REQUIRES_CLOSE_CONFIRMATION", "MARKET_CORRECTION"), false)).value();
        assertThat(expiredRetestSummary.stance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");
        assertThat(expiredRetestSummary.rightSideConfirmationBasis()).isNull();

        // 2) 選定健康支撐 ZB（本身方案 WAITING_TRIGGER 無破位）時，他區 ZA 的 PROTECTIVE_STOP_BREACHED 不得壓掉 ZB
        LevelZoneDto zoneB = new LevelZoneDto("ZB", AnalysisTimeframe.WEEKLY, new BigDecimal("146"), new BigDecimal("152"),
                ZoneRole.SUPPORT, new BigDecimal("80"), CLOSE, 2, CLOSE, ZoneState.ACTIVE, new BigDecimal("149"), new BigDecimal("5"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("80"), 1, CLOSE, null, List.of(), List.of(), 2, 0, 2, null, null, RetestState.NONE, true, List.of());
        MetricResult<SupportResistanceDto> srB = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(zoneB), List.of(), List.of(), List.of()));
        TradePlanDto planABreached = new TradePlanDto("PA", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.INVALIDATED, List.of(AnalysisReasonDto.of("PROTECTIVE_STOP_BREACHED", "TRADE_PLAN", "A 區破止損")),
                "ZA", 1, AnalysisTimeframe.WEEKLY, CLOSE, null, List.of());
        TradePlanDto planBHealthy = new TradePlanDto("PB", AnalysisHorizon.POSITION, SetupType.PULLBACK, null, EntryMethod.ZONE_LIMIT,
                null, null, List.of(), MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TP", "NA")),
                TradePlanStatus.WAITING_TRIGGER, List.of(AnalysisReasonDto.of("NO_FIXED_TARGET", "TRADE_PLAN", "無上方固定目標")),
                "ZB", 1, AnalysisTimeframe.WEEKLY, CLOSE, null, List.of());
        MetricResult<TradePlanResultDto> mixedPlans = MetricResult.available(
                new TradePlanResultDto(List.of(planABreached, planBHealthy), new BigDecimal("150"), DAY, null, List.of(), List.of(), true));
        InvestmentSummaryDto zoneBIsolated = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), srB, mixedPlans,
                richMarketContext(MarketRegime.RISK_ON, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false)).value();
        assertThat(zoneBIsolated.technicalSupportZoneId()).isEqualTo("ZB");
        assertThat(zoneBIsolated.stance()).isEqualTo("ACCUMULATE_AT_SUPPORT");

        // 3) FAIR_UPPER + MIXED 遇 CORRECTION -> WAIT_FOR_MARKET_STABILIZATION；遇財報 BLOCK -> WAIT_FOR_STABILIZATION
        InvestmentSummaryDto fairUpperCorr = calculator.calculate(
                AnalysisHorizon.POSITION, fairUpper, null, mixed, null,
                structure(TrendState.UPTREND, "240"), null, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "200", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_CORRECTION"), false)).value();
        assertThat(fairUpperCorr.stance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");

        InvestmentSummaryDto fairUpperEarnings = calculator.calculate(
                AnalysisHorizon.POSITION, fairUpper, null, mixed, null,
                structure(TrendState.UPTREND, "240"), null, null,
                richMarketContext(MarketRegime.RISK_ON, "4.10", null, null, "200", null, null, null, null, null,
                        EntryPermission.BLOCK, List.of("EARNINGS_EVENT_WINDOW"), true)).value();
        assertThat(fairUpperEarnings.stance()).isEqualTo("WAIT_FOR_STABILIZATION");

        // 4) 第四支撐區 Z4 不在 TradePlanService 前三名計畫內：
        //    (a) Z4 自身無失效，ZA 失效 -> ZA 不得壓掉 Z4，仍為 ACCUMULATE_AT_SUPPORT；
        //    (b) Z4 自身曾連續兩日收於凍結閾值下（146 - 0.25*4 = 145）今日站回 -> 摘要偵測自身失效，絕不誤採 Z4 或其 Spring！
        LevelZoneDto zone4 = new LevelZoneDto("Z4_STRONG", AnalysisTimeframe.WEEKLY, new BigDecimal("146"), new BigDecimal("152"),
                ZoneRole.SUPPORT, new BigDecimal("90"), CLOSE.minusSeconds(10 * 86400L), 2, CLOSE.minusSeconds(10 * 86400L), ZoneState.ACTIVE,
                new BigDecimal("149"), new BigDecimal("4"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("90"), 1,
                CLOSE.minusSeconds(10 * 86400L), null, List.of(), List.of(), 2, 0, 2, null, null, RetestState.NONE, true, List.of());
        MetricResult<SupportResistanceDto> srZ4 = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(zone4), List.of(), List.of(), List.of()));
        MetricResult<TradePlanResultDto> onlyOtherPlans = MetricResult.available(
                new TradePlanResultDto(List.of(planABreached), new BigDecimal("150"), DAY, null, List.of(), List.of(), true));
        InvestmentSummaryDto z4Healthy = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                structure(TrendState.UPTREND, "150"), srZ4, onlyOtherPlans,
                richMarketContext(MarketRegime.RISK_ON, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false)).value();
        assertThat(z4Healthy.technicalSupportZoneId()).isEqualTo("Z4_STRONG");
        assertThat(z4Healthy.stance()).isEqualTo("ACCUMULATE_AT_SUPPORT");

        // Z4 自身曾於 2026-11-10 與 2026-11-11 連續兩日收 144.5 (< 145) 失效，即使 2026-11-13 站回 150 亦不可選入；
        // 且即使 tradePlans 裡沒有任何他區破位（僅無破位之其他計畫或空清單），最後候選支撐因自身失效被排除後仍須落回 WAIT_FOR_STABILIZATION
        NormalizedBarDto b1 = new NormalizedBarDto(LocalDate.of(2026, 11, 10), new BigDecimal("145"), new BigDecimal("145"), new BigDecimal("144"), new BigDecimal("144.5"),
                MetricResult.available(BigDecimal.ONE), Instant.parse("2026-11-10T14:30:00Z"), Instant.parse("2026-11-10T21:00:00Z"),
                Instant.parse("2026-11-10T21:00:00Z"), Instant.parse("2026-11-10T21:00:00Z"), null, "B1");
        NormalizedBarDto b2 = new NormalizedBarDto(LocalDate.of(2026, 11, 11), new BigDecimal("144.5"), new BigDecimal("145"), new BigDecimal("144"), new BigDecimal("144.5"),
                MetricResult.available(BigDecimal.ONE), Instant.parse("2026-11-11T14:30:00Z"), Instant.parse("2026-11-11T21:00:00Z"),
                Instant.parse("2026-11-11T21:00:00Z"), Instant.parse("2026-11-11T21:00:00Z"), null, "B1");
        NormalizedBarDto b3 = new NormalizedBarDto(DAY, new BigDecimal("149"), new BigDecimal("151"), new BigDecimal("148"), new BigDecimal("150"),
                MetricResult.available(BigDecimal.ONE), Instant.parse("2026-11-13T14:30:00Z"), CLOSE, CLOSE, CLOSE, null, "B1");
        MetricResult<StructureTrendDto> upTrend = MetricResult.available(
                new StructureTrendDto(TrendState.UPTREND, CLOSE, PriceRelation.HH, PriceRelation.HL, List.of(), CLOSE));
        MetricResult<PriceStructureDto> invalidatedDailyStruct = MetricResult.available(new PriceStructureDto("FIGI", null, DAY, List.of(
                new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(b1, b2, b3), null, List.of(), List.of(), null, null, upTrend, upTrend, List.of(), List.of()),
                new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, List.of(b3), null, List.of(), List.of(), null, null, upTrend, upTrend, List.of(), List.of())
        ), List.of(), List.of()));
        MetricResult<TradePlanResultDto> nonBreachedOtherPlans = MetricResult.available(
                new TradePlanResultDto(List.of(planBHealthy), new BigDecimal("150"), DAY, null, List.of(), List.of(), true));
        InvestmentSummaryDto z4SelfInvalidated = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null,
                invalidatedDailyStruct, srZ4, nonBreachedOtherPlans,
                richMarketContext(MarketRegime.RISK_ON, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false)).value();
        assertThat(z4SelfInvalidated.technicalSupportZoneId()).isNull();
        assertThat(z4SelfInvalidated.stance()).isEqualTo("WAIT_FOR_STABILIZATION");
        assertThat(z4SelfInvalidated.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("PROTECTIVE_STOP_OR_RECLAIM_PENDING");

        // 5) 精確 NYSE 交易日曆 5 交易日視窗（2012-10-25 跨桑迪休市 10-29/10-30 -> 第 5 交易日為 2012-11-05，相隔 11 日曆日仍有效；11-06 過期）
        LocalDate sandyDay5 = LocalDate.of(2012, 11, 5);
        LocalDate sandyDay6 = LocalDate.of(2012, 11, 6);
        Instant sandyRetestAt = Instant.parse("2012-10-25T20:00:00Z");
        LevelZoneDto sandyZone = new LevelZoneDto("Z_SANDY", AnalysisTimeframe.WEEKLY, new BigDecimal("146"), new BigDecimal("152"),
                ZoneRole.SUPPORT, new BigDecimal("85"), sandyRetestAt, 2, sandyRetestAt, ZoneState.ACTIVE, new BigDecimal("149"), new BigDecimal("5"),
                1, ZoneStrengthLabel.HIGH, new BigDecimal("85"), 2, sandyRetestAt, new BigDecimal("150"), List.of(), List.of(),
                2, 0, 2, null, null, RetestState.SUCCEEDED, true, List.of());
        MetricResult<SupportResistanceDto> srSandy = MetricResult.available(
                new SupportResistanceDto(List.of(), List.of(), List.of(sandyZone), List.of(), List.of(), List.of()));
        NormalizedBarDto barDay5 = new NormalizedBarDto(sandyDay5, new BigDecimal("150"), new BigDecimal("151"), new BigDecimal("149"), new BigDecimal("150"),
                MetricResult.available(BigDecimal.ONE), Instant.parse("2012-11-05T14:30:00Z"), Instant.parse("2012-11-05T21:00:00Z"),
                Instant.parse("2012-11-05T21:00:00Z"), Instant.parse("2012-11-05T21:00:00Z"), null, "B1");
        NormalizedBarDto barDay6 = new NormalizedBarDto(sandyDay6, new BigDecimal("150"), new BigDecimal("151"), new BigDecimal("149"), new BigDecimal("150"),
                MetricResult.available(BigDecimal.ONE), Instant.parse("2012-11-06T14:30:00Z"), Instant.parse("2012-11-06T21:00:00Z"),
                Instant.parse("2012-11-06T21:00:00Z"), Instant.parse("2012-11-06T21:00:00Z"), null, "B1");
        MetricResult<PriceStructureDto> structDay5 = MetricResult.available(new PriceStructureDto("FIGI", null, sandyDay5, List.of(
                new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(barDay5), null, List.of(), List.of(), null, null, upTrend, upTrend, List.of(), List.of()),
                new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, List.of(barDay5), null, List.of(), List.of(), null, null, upTrend, upTrend, List.of(), List.of())
        ), List.of(), List.of()));
        MetricResult<PriceStructureDto> structDay6 = MetricResult.available(new PriceStructureDto("FIGI", null, sandyDay6, List.of(
                new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(barDay5, barDay6), null, List.of(), List.of(), null, null, upTrend, upTrend, List.of(), List.of()),
                new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, List.of(barDay6), null, List.of(), List.of(), null, null, upTrend, upTrend, List.of(), List.of())
        ), List.of(), List.of()));
        InvestmentSummaryDto sandyValid = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null, structDay5, srSandy, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_CORRECTION"), false)).value();
        assertThat(sandyValid.stance()).isEqualTo("WATCH_PULLBACK_TO_BAND");
        assertThat(sandyValid.rightSideConfirmationBasis()).isEqualTo("CONFIRMED_RETEST");

        InvestmentSummaryDto sandyExpired = calculator.calculate(
                AnalysisHorizon.POSITION, deepUnder, null, strong, null, structDay6, srSandy, null,
                richMarketContext(MarketRegime.CORRECTION, "4.10", null, null, "140", null, null, null, null, null,
                        EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_CORRECTION"), false)).value();
        assertThat(sandyExpired.stance()).isEqualTo("WAIT_FOR_MARKET_STABILIZATION");
    }

    @Test
    void verifiedLossMakingAdrTriggersWeakeningAndLowInterestCoverageRespects2xThreshold() {
        FxConversionDto verifiedAdrFx = new FxConversionDto(
                true, "CNY", "USD", DAY, new BigDecimal("0.14"), "FRED", true, new BigDecimal("8"), true, "9988.HK");
        ValuationResultDto adrVal = new ValuationResultDto(
                "FIGI_ADR", DAY, CLOSE, "FUTU_AND_SEC_FINANCIAL_FACTS", "TECH", "NON_FINANCIAL_OPERATING", "PS",
                "AVAILABLE", null,
                new ValuationResultDto.Metrics(
                        ValuationResultDto.value(null, "NON_POSITIVE_EPS"),
                        ValuationResultDto.value(new BigDecimal("2.0"), null),
                        ValuationResultDto.value(new BigDecimal("3.0"), null),
                        ValuationResultDto.value(new BigDecimal("18.0"), null),
                        ValuationResultDto.value(null, "NON_POSITIVE_EBITDA")),
                new BigDecimal("50.00"), "USD", CLOSE, CLOSE, CLOSE, "SEC_FILING_SHARES", DAY,
                null, null, null, verifiedAdrFx);

        FinancialsDto.FinancialValue unavailEps = FinancialsDto.FinancialValue.unavailable(
                "UNVERIFIED", "SEC_SHARE_CLASS_EPS_UNVERIFIED", null, null, DAY, CLOSE);
        FinancialsDto.FinancialValue lossNetIncome = FinancialsDto.FinancialValue.available(
                new BigDecimal("-500000000"), "TTM Net Income", DAY.minusYears(1), DAY, CLOSE);
        FinancialsDto adrFinancials = new FinancialsDto(
                DAY, CLOSE, "CNY", null, lossNetIncome, unavailEps,
                null, null, null, null, null, null, null, null, null, null, null);

        MetricResult<BigDecimal> na = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("NA", "TEST", "NA"));
        // 淨負債/EBITDA = 4.5 > 4.0，利息保障 = 1.8 < 2.0 -> 觸發 LOW_INTEREST_COVERAGE
        FundamentalSnapshotDto lowCovFundamentals = new FundamentalSnapshotDto(
                DAY, DAY.minusYears(1), DAY.minusYears(3), "CNY", false,
                MetricResult.available(new BigDecimal("0.05")), na, na, na, na, na, null,
                na, MetricResult.available(new BigDecimal("0.06")), na,
                MetricResult.available(new BigDecimal("4.5")), na, na, na,
                na, na, na, na, na, na, na, na, na, na, na, na, false, na, na, na, na, na, na, na, na,
                MetricResult.available(new BigDecimal("1.8")), na);

        InvestmentSummaryDto adrSummary = calculator.calculate(
                AnalysisHorizon.POSITION, adrVal, adrFinancials, lowCovFundamentals, null,
                structure(TrendState.SIDEWAYS, "50"), null, null,
                marketContext("4.20", "48", EntryPermission.ALLOW, false)).value();
        assertThat(adrSummary.fundamentalBacking()).isEqualTo("WEAKENING");
        assertThat(adrSummary.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("NEGATIVE_TRAILING_EARNINGS", "LOW_INTEREST_COVERAGE");

        // 利息保障 = 2.5 (>= 2.0) 即使淨負債/EBITDA = 4.5 也只觸發 HIGH_LEVERAGE_DEBT_EBITDA，不應觸發 LOW_INTEREST_COVERAGE
        FundamentalSnapshotDto midCovFundamentals = new FundamentalSnapshotDto(
                DAY, DAY.minusYears(1), DAY.minusYears(3), "CNY", false,
                MetricResult.available(new BigDecimal("0.05")), na, na, na, na, na, null,
                na, MetricResult.available(new BigDecimal("0.06")), na,
                MetricResult.available(new BigDecimal("4.5")), na, na, na,
                na, na, na, na, na, na, na, na, na, na, na, na, false, na, na, na, na, na, na, na, na,
                MetricResult.available(new BigDecimal("2.5")), na);
        InvestmentSummaryDto midCovSummary = calculator.calculate(
                AnalysisHorizon.POSITION, adrVal, adrFinancials, midCovFundamentals, null,
                structure(TrendState.SIDEWAYS, "50"), null, null,
                marketContext("4.20", "48", EntryPermission.ALLOW, false)).value();
        assertThat(midCovSummary.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("HIGH_LEVERAGE_DEBT_EBITDA")
                .doesNotContain("LOW_INTEREST_COVERAGE");

        // 未核實 ADR 維持阻斷語意，isCurrentTrailingLoss 不混用未核實口徑
        assertThat(ValuationSummaryCalculator.isCurrentTrailingLoss(adrFinancials, true, false)).isFalse();
        assertThat(ValuationSummaryCalculator.isCurrentTrailingLoss(adrFinancials, true, true)).isTrue();
    }

    /** Scenario：大盤友善且無宏觀警戒，但所屬板塊廣度崩壞時，趨勢回踩試單仍自動由 1/3 縮為 1/6 並附帶 SECTOR_BREADTH_BREAKDOWN 風險旗標。 */
    @Test
    void sectorBreadthBreakdownScalesTrendPullbackStarterToOneSixthAndEmitsRiskFlag() {
        ValuationReferenceDto hist = new ValuationReferenceDto(
                ValuationReferenceDto.KIND_HISTORICAL_MARKET, "PE", "AVAILABLE", null,
                new BigDecimal("18"), new BigDecimal("22"), new BigDecimal("26"),
                new BigDecimal("150"), new BigDecimal("200"), 48, 48, 0, DAY.minusYears(4), DAY,
                new BigDecimal("28"), new BigDecimal("85"), new BigDecimal("175"));
        ValuationResultDto modOver = new ValuationResultDto(
                "FIGI", DAY, CLOSE, "FUTU", "I10", "NON_FINANCIAL_OPERATING", "PE",
                "AVAILABLE", null, null, new BigDecimal("210"), "USD", CLOSE, CLOSE, CLOSE, "OBSERVED_SHARES", null,
                hist, null, null);
        FundamentalSnapshotDto strongHighGrowth = fundamentals(false, "0.28", "0.35", "0.30", "1.10", "0.26", "0.22", "-0.30", "0.04", "0.045");
        MetricResult<BigDecimal> na = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("NA", "TEST", "NA"));
        ForwardMetrics peg150 = new ForwardMetrics(DAY, "2027", DAY, "USD",
                MetricResult.available(new BigDecimal("28")), na, MetricResult.available(new BigDecimal("1.50")), "FORWARD_FY1_VS_TTM",
                MetricResult.available(new BigDecimal("0.25")), null, null, null, null, null, na, na, na, na);
        FutuObservationsDto obs = new FutuObservationsDto(null, List.of(), peg150);
        MetricResult<PriceStructureDto> ps = pullbackDailyStructure(TrendState.UPTREND, "220", "211", "209", "212", "208", "210");
        MetricResult<MarketContextDto> mc = richMarketContext(MarketRegime.RISK_ON, "4.10", null,
                MetricResult.available(new BigDecimal("16")), "185", "208", "207", "206", "5", "0.85",
                EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), false);

        InvestmentSummaryDto normal = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, obs, ps, null, null, mc, false).value();
        assertThat(normal.stance()).isEqualTo("TREND_PULLBACK_STARTER");
        assertThat(normal.trendPullbackStarter().positionFraction()).isEqualTo("1/3");
        assertThat(normal.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .doesNotContain("SECTOR_BREADTH_BREAKDOWN");

        InvestmentSummaryDto broken = calculator.calculate(
                AnalysisHorizon.SWING, modOver, null, strongHighGrowth, obs, ps, null, null, mc, true).value();
        assertThat(broken.stance()).isEqualTo("TREND_PULLBACK_STARTER");
        assertThat(broken.macroCaution().active()).isFalse();
        assertThat(broken.trendPullbackStarter().positionFraction()).isEqualTo("1/6");
        assertThat(broken.risks()).extracting(InvestmentSummaryDto.SummaryFlagDto::code)
                .contains("SECTOR_BREADTH_BREAKDOWN");
    }

    private MetricResult<PriceStructureDto> pullbackDailyStructure(
            TrendState weeklyState, String highRecent, String prevClose, String open, String high, String low, String close) {
        NormalizedBarDto bar1 = new NormalizedBarDto(DAY.minusDays(3), new BigDecimal(highRecent), new BigDecimal(highRecent),
                new BigDecimal(prevClose), new BigDecimal(prevClose), MetricResult.available(BigDecimal.ONE),
                CLOSE.minusSeconds(259200), CLOSE.minusSeconds(259200), CLOSE.minusSeconds(259200), CLOSE.minusSeconds(259200), null, "B1");
        NormalizedBarDto bar2 = new NormalizedBarDto(DAY.minusDays(1), new BigDecimal(prevClose), new BigDecimal(prevClose),
                new BigDecimal(prevClose), new BigDecimal(prevClose), MetricResult.available(BigDecimal.ONE),
                CLOSE.minusSeconds(86400), CLOSE.minusSeconds(86400), CLOSE.minusSeconds(86400), CLOSE.minusSeconds(86400), null, "B1");
        NormalizedBarDto bar3 = new NormalizedBarDto(DAY, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), MetricResult.available(BigDecimal.ONE),
                CLOSE, CLOSE, CLOSE, CLOSE, null, "B1");
        MetricResult<StructureTrendDto> trend = MetricResult.available(
                new StructureTrendDto(weeklyState, CLOSE, PriceRelation.HH, PriceRelation.HL, List.of(), CLOSE));
        TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, List.of(bar1, bar2, bar3), null, List.of(), List.of(),
                null, null, trend, trend, List.of(), List.of());
        TimeframeStructureDto weekly = new TimeframeStructureDto(AnalysisTimeframe.WEEKLY, List.of(bar3), null, List.of(), List.of(),
                null, null, trend, trend, List.of(), List.of());
        return MetricResult.available(new PriceStructureDto("FIGI", null, DAY, List.of(daily, weekly), List.of(), List.of()));
    }

    private MetricResult<MarketContextDto> richMarketContext(
            MarketRegime regime, String tenYearPct, MetricResult<BigDecimal> tenYearChange20, MetricResult<BigDecimal> vixClose,
            String sma200, String ema21, String sma50, String earningsAvwap, String atr14, String rvol20,
            EntryPermission perm, List<String> constraintReasons, boolean earningsBlock) {
        TreasuryContextDto treasury = new TreasuryContextDto(
                MetricResult.available(new BigDecimal(tenYearPct)), null, tenYearChange20, null, List.of(), List.of());
        Map<String, MetricResult<BigDecimal>> inds = new LinkedHashMap<>();
        if (sma200 != null) inds.put("SMA200", MetricResult.available(new BigDecimal(sma200)));
        if (ema21 != null) inds.put("EMA21", MetricResult.available(new BigDecimal(ema21)));
        if (sma50 != null) inds.put("SMA50", MetricResult.available(new BigDecimal(sma50)));
        if (atr14 != null) inds.put("ATR14", MetricResult.available(new BigDecimal(atr14)));
        MomentumAnalysisDto detail = new MomentumAnalysisDto(inds, null, List.of());
        MetricResult<MarketSnapshotDto> snap = regime == null ? null : MetricResult.available(
                new MarketSnapshotDto(regime, null, null, null, DAY, CLOSE, List.of(), List.of()));
        VolumeAnalysisDto vol = (rvol20 != null || earningsAvwap != null)
                ? new VolumeAnalysisDto(
                        rvol20 == null ? null : MetricResult.available(new BigDecimal(rvol20)),
                        null, null, null, null, null, List.of(), List.of(),
                        earningsAvwap == null ? null : DAY.minusDays(15),
                        earningsAvwap == null ? null : DAY.minusDays(14),
                        earningsAvwap == null ? null : MetricResult.available(new BigDecimal(earningsAvwap)),
                        List.of())
                : null;
        List<EventRiskDto> evs = earningsBlock
                ? List.of(new EventRiskDto("E1", "EARNINGS", "QUARTERLY", "Q4 Earnings", DAY.plusDays(1), null, "DATE_ONLY", CLOSE, "FUTU", true))
                : List.of();
        PlanConstraintDto constraint = new PlanConstraintDto(perm, constraintReasons, List.of());
        return MetricResult.available(new MarketContextDto(null, null, snap, evs, constraint, List.of(), List.of(),
                detail, vol, null, treasury, Map.of(), vixClose));
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
