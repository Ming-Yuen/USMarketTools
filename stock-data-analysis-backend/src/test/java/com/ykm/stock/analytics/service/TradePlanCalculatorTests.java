package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradePlanCalculatorTests {
    private final ExecutionCostCalculator costs = new ExecutionCostCalculator();
    private final TradePlanCalculator calculator = new TradePlanCalculator(costs);

    // NVDA 2026-11-02 兩個日線支撐及 ATR；保留不足判斷，只要求指出真正失敗的強度門檻。
    @ParameterizedTest
    @CsvSource({
            "195.78678754387303,198.65441245612698,10.43299214434372,5.735249824507922,2026-04-28T13:30:00Z",
            "188.03000481556154,191.56999518443845,27.821312384916588,7.07998073775381,2026-07-02T13:30:00Z"
    })
    void nvidiaWeakSupportExplainsExactStrengthGate(String lower, String upper, String strength, String anchorAtr, String confirmedAt) {
        BigDecimal effectiveStrength = new BigDecimal(strength);
        LevelZoneDto zone = zone("NVDA_DAILY_SUPPORT", new BigDecimal(lower), new BigDecimal(upper),
                ZoneRole.SUPPORT, new BigDecimal(anchorAtr), Instant.parse(confirmedAt));
        LevelZoneDto support = withSupportEligibility(zone, ZoneState.ACTIVE, effectiveStrength);
        AnalysisContext context = new AnalysisContext("BBG000BBJQV0", Instant.parse("2026-11-03T16:40:48.758322Z"),
                Instant.parse("2026-11-03T16:40:48.758322Z"), AnalysisHorizon.SWING);
        MetricResult<StructureTrendDto> trend = MetricResult.available(new StructureTrendDto(TrendState.DOWNTREND,
                context.analysisAsOf(), PriceRelation.LH, PriceRelation.LL, List.of(), Instant.parse("2026-11-02T14:30:00Z")));

        TradePlanDto plan = calculator.build(context, "BASIS", "hash", new BigDecimal("202.06"), LocalDate.of(2026, 11, 2),
                support, List.of(), trend, new BigDecimal("6.37303857051016"), LocalDate.of(2026, 7, 6), null,
                new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()));

        assertThat(plan.status()).isEqualTo(TradePlanStatus.UNAVAILABLE);
        assertThat(plan.entryRange()).isNull();
        assertThat(plan.stop()).isNull();
        assertThat(plan.targets()).isEmpty();
        assertThat(plan.reasons()).singleElement().satisfies(reason -> {
            assertThat(reason.code()).isEqualTo("PLAN_SUPPORT_STRENGTH_INSUFFICIENT");
            assertThat(reason.params()).containsEntry("effectiveStrength", effectiveStrength)
                    .containsEntry("minimumStrength", BigDecimal.valueOf(30));
            assertThat(reason.message()).contains("支撐有效強度", "30").doesNotContain("ATR");
        });
    }

    @ParameterizedTest
    @CsvSource({
            "MISSING_SUPPORT,PLAN_SUPPORT_MISSING",
            "INACTIVE_SUPPORT,PLAN_SUPPORT_NOT_ACTIVE",
            "MISSING_ATR,PLAN_ATR_MISSING",
            "ZERO_ATR,PLAN_ATR_NOT_POSITIVE"
    })
    void unavailableInputsIdentifyTheExistingGate(String scenario, String expectedCode) {
        LevelZoneDto support = support();
        BigDecimal atr = new BigDecimal("5");
        switch (scenario) {
            case "MISSING_SUPPORT" -> support = null;
            case "INACTIVE_SUPPORT" -> support = withSupportEligibility(support, ZoneState.BROKEN, new BigDecimal("70"));
            case "MISSING_ATR" -> atr = null;
            case "ZERO_ATR" -> atr = BigDecimal.ZERO;
            default -> throw new IllegalArgumentException(scenario);
        }

        TradePlanDto plan = calculator.build(context(), "BASIS", "hash", new BigDecimal("98"), LocalDate.of(2026, 10, 14),
                support, List.of(resistance()), uptrend(), atr, LocalDate.of(2026, 10, 14), null,
                new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()));

        assertThat(plan.status()).isEqualTo(TradePlanStatus.UNAVAILABLE);
        assertThat(plan.entryRange()).isNull();
        assertThat(plan.reasons()).singleElement().satisfies(reason -> assertThat(reason.code()).isEqualTo(expectedCode));
    }

    @Test
    void minimumSupportStrengthStillPermitsPlanConstruction() {
        LevelZoneDto support = withSupportEligibility(support(), ZoneState.ACTIVE, BigDecimal.valueOf(30));

        TradePlanDto plan = calculator.build(context(), "BASIS", "hash", new BigDecimal("98"), LocalDate.of(2026, 10, 14),
                support, List.of(resistance()), uptrend(), new BigDecimal("5"), LocalDate.of(2026, 10, 14), null,
                new PlanConstraintDto(EntryPermission.ALLOW, List.of(), List.of()));

        assertThat(plan.status()).isEqualTo(TradePlanStatus.ACTIVE);
        assertThat(plan.entryRange()).isNotNull();
        assertThat(plan.stop()).isNotNull();
    }

    // 例子：2026-10-13 的訊號日不能在當日生效，要到 10-14 才可啟用。
    @Test
    void signalSessionCannotBecomeActiveUntilNextRegularSession() {
        AnalysisContext ctx=context();
        LevelZoneDto support=support();
        LocalDate signal=LocalDate.of(2026,10,13);
        TradePlanDto plan=calculator.build(ctx,"BASIS","hash",new BigDecimal("98"),signal,support,List.of(resistance()),uptrend(),new BigDecimal("5"),
                LocalDate.of(2026,10,14),LocalDate.of(2026,10,20),new PlanConstraintDto(EntryPermission.ALLOW,List.of(),List.of()));

        assertThat(plan.status()).isEqualTo(TradePlanStatus.WAITING_TRIGGER);
        assertThat(plan.reasons()).extracting(AnalysisReasonDto::code).contains("SIGNAL_NOT_EXECUTABLE_YET");
        assertThat(plan.riskReward().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(plan.riskReward().value().netRR()).isGreaterThanOrEqualTo(new BigDecimal("2"));
    }

    // 例子：2026-10-14 下一時段可啟用，但約束 UNKNOWN 時必須維持等待。
    @Test
    void nextSessionCanBecomeActiveButUnknownConstraintMustStayWaiting() {
        AnalysisContext ctx=context();
        TradePlanDto allowed=calculator.build(ctx,"BASIS","hash",new BigDecimal("98"),LocalDate.of(2026,10,14),support(),List.of(resistance()),uptrend(),new BigDecimal("5"),
                LocalDate.of(2026,10,14),LocalDate.of(2026,10,20),new PlanConstraintDto(EntryPermission.ALLOW,List.of(),List.of()));
        TradePlanDto unknown=calculator.build(ctx,"BASIS","hash",new BigDecimal("98"),LocalDate.of(2026,10,14),support(),List.of(resistance()),uptrend(),new BigDecimal("5"),
                LocalDate.of(2026,10,14),LocalDate.of(2026,10,20),PlanConstraintDto.unevaluated());

        assertThat(allowed.status()).isEqualTo(TradePlanStatus.ACTIVE);
        assertThat(unknown.status()).isEqualTo(TradePlanStatus.WAITING_TRIGGER);
        assertThat(unknown.reasons()).extracting(AnalysisReasonDto::code).contains("ENTRY_CONSTRAINT");
    }

    // 例子：較近的週壓力 115 至 119 必須排在較遠的日壓力之前。
    @Test
    void nearerWeeklyResistanceMustPrecedeFartherDailyResistance() {
        LevelZoneDto nearest = zone("NEAR_WEEKLY", new BigDecimal("115"), new BigDecimal("119"),
                ZoneRole.RESISTANCE, new BigDecimal("5"), Instant.parse("2026-10-09T20:00:00Z"));
        nearest = withTimeframe(nearest, AnalysisTimeframe.WEEKLY);
        LevelZoneDto farther = resistance();
        TradePlanDto plan = calculator.build(context(), "BASIS", "hash", new BigDecimal("98"),
                LocalDate.of(2026,10,14), support(), List.of(farther,nearest), uptrend(),
                new BigDecimal("5"), LocalDate.of(2026,10,14), null,
                new PlanConstraintDto(EntryPermission.ALLOW,List.of(),List.of()));
        assertThat(plan.targets()).extracting(TargetDto::sourceZoneId).containsExactly("NEAR_WEEKLY","R");
        assertThat(plan.targets().get(0).price()).isLessThan(plan.targets().get(1).price());
    }

    // 例子：沒有已知壓力時，不臆造固定目標或風險報酬。
    @Test
    void noKnownResistanceDoesNotInventFixedTargetOrRiskReward() {
        TradePlanDto plan = calculator.build(context(), "BASIS", "hash", new BigDecimal("98"),
                LocalDate.of(2026,10,14), support(), List.of(), uptrend(),
                new BigDecimal("5"), LocalDate.of(2026,10,14), null,
                new PlanConstraintDto(EntryPermission.ALLOW,List.of(),List.of()));
        assertThat(plan.stop()).isNotNull();
        assertThat(plan.targets()).isEmpty();
        assertThat(plan.riskReward().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(plan.status()).isEqualTo(TradePlanStatus.WAITING_TRIGGER);
        assertThat(plan.reasons()).extracting(AnalysisReasonDto::code).contains("NO_FIXED_TARGET");
    }

    private LevelZoneDto withTimeframe(LevelZoneDto z, AnalysisTimeframe tf) {
        return new LevelZoneDto(z.id(), tf, z.lower(), z.upper(), z.role(), z.strength(),
                z.confirmedAt(), z.testCount(), z.lastTestAt(), z.state(), z.center(), z.anchorAtr(),
                z.boundsVersion(), z.strengthLabel(), z.effectiveStrength(), z.roleVersion(),
                z.roleConfirmedAt(), z.roleConfirmationPrice(), z.seedIds(), z.dependenceKeys(),
                z.rejectionCount(), z.penetrationCount(), z.ageBars(), z.distanceLowerPercent(),
                z.distanceUpperPercent(), z.retestState(), z.majorLevel(), z.evidenceRefs());
    }

    private LevelZoneDto withSupportEligibility(LevelZoneDto z, ZoneState state, BigDecimal effectiveStrength) {
        return new LevelZoneDto(z.id(), z.timeframe(), z.lower(), z.upper(), z.role(), effectiveStrength,
                z.confirmedAt(), z.testCount(), z.lastTestAt(), state, z.center(), z.anchorAtr(),
                z.boundsVersion(), z.strengthLabel(), effectiveStrength, z.roleVersion(),
                z.roleConfirmedAt(), z.roleConfirmationPrice(), z.seedIds(), z.dependenceKeys(),
                z.rejectionCount(), z.penetrationCount(), z.ageBars(), z.distanceLowerPercent(),
                z.distanceUpperPercent(), z.retestState(), z.majorLevel(), z.evidenceRefs());
    }

    private AnalysisContext context(){return new AnalysisContext("FIGI", Instant.parse("2026-10-14T21:00:00Z"), Instant.parse("2026-10-14T21:00:00Z"), AnalysisHorizon.SWING);}
    private MetricResult<StructureTrendDto> uptrend(){Instant t=Instant.parse("2026-10-13T21:00:00Z");return MetricResult.available(new StructureTrendDto(TrendState.UPTREND,t,PriceRelation.HH,PriceRelation.HL,List.of("p"),t));}
    private LevelZoneDto support(){return zone("S",new BigDecimal("95"),new BigDecimal("100"),ZoneRole.SUPPORT,new BigDecimal("5"),Instant.parse("2026-10-13T20:00:00Z"));}
    private LevelZoneDto resistance(){return zone("R",new BigDecimal("130"),new BigDecimal("135"),ZoneRole.RESISTANCE,new BigDecimal("5"),Instant.parse("2026-10-10T20:00:00Z"));}
    private LevelZoneDto zone(String id,BigDecimal lo,BigDecimal hi,ZoneRole role,BigDecimal atr,Instant confirmed){return new LevelZoneDto(id,AnalysisTimeframe.DAILY,lo,hi,role,new BigDecimal("70"),confirmed,1,confirmed,ZoneState.ACTIVE,lo.add(hi).divide(new BigDecimal("2")),atr,1,ZoneStrengthLabel.HIGH,new BigDecimal("70"),1,confirmed,null,List.of(id+"seed"),List.of("dep"),1,0,1,null,null,RetestState.NONE,false,List.of(id+"e"));}
}
