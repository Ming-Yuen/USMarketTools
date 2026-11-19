package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TradePlanServiceTests {
    // 例子：連續兩個日收盤跌破 2026-10-12 凍結門檻後，確認技術失效。
    @Test
    void twoConsecutiveDailyClosesBelowFrozenThresholdConfirmTechnicalInvalidation() {
        MarketStatusService calendar=new MarketStatusService(null);
        TradePlanService service=new TradePlanService(new TradePlanCalculator(new ExecutionCostCalculator()),new ExecutionCostCalculator(),calendar);
        Instant supportAt=Instant.parse("2026-10-12T20:00:00Z");
        LevelZoneDto support=zone("S","95","100",ZoneRole.SUPPORT,"5",supportAt);
        LevelZoneDto resistance=zone("R","130","135",ZoneRole.RESISTANCE,"5",Instant.parse("2026-10-09T20:00:00Z"));
        List<NormalizedBarDto> bars=List.of(bar(calendar,LocalDate.of(2026,10,14),"93.5"),bar(calendar,LocalDate.of(2026,10,15),"93"));
        PriceBasisDescriptor basis=new PriceBasisDescriptor("BASIS","MASSIVE",PriceAdjustment.SPLIT_ADJUSTED,LocalDate.of(2026,10,15),"split-state-a",ShareUnit.LISTED_SHARE,SessionScope.REGULAR,"USD",true,"e");
        NormalizedPriceSeriesDto price=new NormalizedPriceSeriesDto("FIGI","DAILY",bars,basis,null,MetricStatus.AVAILABLE,List.of("q"),"hash");
        MetricResult<StructureTrendDto> trend=MetricResult.available(new StructureTrendDto(TrendState.UPTREND,supportAt,PriceRelation.HH,PriceRelation.HL,List.of(),supportAt));
        AtrPointDto atr=new AtrPointDto(LocalDate.of(2026,10,15),14,new BigDecimal("5"),null,LocalDate.of(2026,9,1),14,20);
        TimeframeStructureDto daily=new TimeframeStructureDto(AnalysisTimeframe.DAILY,bars,MetricResult.available(atr),List.of(),List.of(),MetricResult.available(PriceRelation.HH),MetricResult.available(PriceRelation.HL),trend,trend,List.of(),List.of());
        PriceStructureDto structure=new PriceStructureDto("FIGI","hash",LocalDate.of(2026,10,15),List.of(daily),List.of(),List.of());
        SupportResistanceDto levels=new SupportResistanceDto(List.of(support),List.of(resistance),List.of(),List.of(),List.of(),List.of());
        AnalysisContext ctx=new AnalysisContext("FIGI", Instant.parse("2026-10-15T22:00:00Z"), Instant.parse("2026-10-15T22:00:00Z"), AnalysisHorizon.SWING);

        MetricResult<TradePlanResultDto> result=service.build(ctx,price,structure,levels,new PlanConstraintDto(EntryPermission.ALLOW,List.of(),List.of()));

        assertThat(result.status()).isEqualTo(MetricStatus.AVAILABLE);
        TradePlanDto plan=result.value().plans().getFirst();
        assertThat(plan.stop().protectivePrice()).isEqualByComparingTo("92.5");
        assertThat(plan.stop().closeThreshold()).isEqualByComparingTo("93.75");
        assertThat(plan.status()).isEqualTo(TradePlanStatus.INVALIDATED);
        assertThat(plan.reasons()).extracting(AnalysisReasonDto::code).contains("TECHNICAL_INVALIDATION_CONFIRMED");
    }

    // 例子：沒有約束時保留技術狀態，但把背景標成未評估。
    @Test
    void absentConstraintKeepsTechnicalStatusButMarksContextUnevaluated() {
        MarketStatusService calendar=new MarketStatusService(null);
        TradePlanService service=new TradePlanService(new TradePlanCalculator(new ExecutionCostCalculator()),new ExecutionCostCalculator(),calendar);
        Instant supportAt=Instant.parse("2026-10-12T20:00:00Z");
        LevelZoneDto support=zone("S","95","100",ZoneRole.SUPPORT,"5",supportAt);
        LevelZoneDto resistance=zone("R","130","135",ZoneRole.RESISTANCE,"5",Instant.parse("2026-10-09T20:00:00Z"));
        NormalizedBarDto b=bar(calendar,LocalDate.of(2026,10,14),"98");
        PriceBasisDescriptor basis=new PriceBasisDescriptor("BASIS","MASSIVE",PriceAdjustment.SPLIT_ADJUSTED,LocalDate.of(2026,10,14),"split-state-a",ShareUnit.LISTED_SHARE,SessionScope.REGULAR,"USD",true,"e");
        NormalizedPriceSeriesDto price=new NormalizedPriceSeriesDto("FIGI","DAILY",List.of(b),basis,null,MetricStatus.AVAILABLE,List.of("q"),"hash");
        MetricResult<StructureTrendDto> trend=MetricResult.available(new StructureTrendDto(TrendState.UPTREND,supportAt,PriceRelation.HH,PriceRelation.HL,List.of(),supportAt));
        AtrPointDto atr=new AtrPointDto(b.sessionDate(),14,new BigDecimal("5"),null,LocalDate.of(2026,9,1),14,20);
        TimeframeStructureDto daily=new TimeframeStructureDto(AnalysisTimeframe.DAILY,List.of(b),MetricResult.available(atr),List.of(),List.of(),MetricResult.available(PriceRelation.HH),MetricResult.available(PriceRelation.HL),trend,trend,List.of(),List.of());
        PriceStructureDto structure=new PriceStructureDto("FIGI","hash",b.sessionDate(),List.of(daily),List.of(),List.of());
        SupportResistanceDto levels=new SupportResistanceDto(List.of(support),List.of(resistance),List.of(),List.of(),List.of(),List.of());
        AnalysisContext ctx=new AnalysisContext("FIGI", Instant.parse("2026-10-14T22:00:00Z"), Instant.parse("2026-10-14T22:00:00Z"), AnalysisHorizon.SWING);

        MetricResult<TradePlanResultDto> result=service.build(ctx,price,structure,levels,null);

        assertThat(result.value().contextEvaluated()).isFalse();
        assertThat(result.value().reasons()).extracting(AnalysisReasonDto::code).containsExactly("CONTEXT_NOT_EVALUATED");
        assertThat(result.value().plans().getFirst().status()).isEqualTo(TradePlanStatus.ACTIVE);
    }

    // 例子：2026-08-20 確認的舊支撐，沒有近期短暫回測時不得過期。
    @Test
    void oldConfirmedSupportMustNotExpireWithoutRecentShortLivedRetest() {
        MarketStatusService calendar=new MarketStatusService(null);
        TradePlanService service=new TradePlanService(new TradePlanCalculator(new ExecutionCostCalculator()),
                new ExecutionCostCalculator(),calendar);
        Instant oldSupportAt=Instant.parse("2026-08-20T20:00:00Z");
        LevelZoneDto support=zone("OLD_SUPPORT","95","100",ZoneRole.SUPPORT,"5",oldSupportAt);
        LevelZoneDto resistance=zone("R","130","135",ZoneRole.RESISTANCE,"5",oldSupportAt);
        NormalizedBarDto bar=bar(calendar,LocalDate.of(2026,10,14),"98");
        PriceBasisDescriptor basis=new PriceBasisDescriptor("BASIS","MASSIVE",PriceAdjustment.SPLIT_ADJUSTED,
                bar.sessionDate(),"verified",ShareUnit.LISTED_SHARE,SessionScope.REGULAR,"USD",true,"e");
        NormalizedPriceSeriesDto price=new NormalizedPriceSeriesDto("FIGI","DAILY",List.of(bar),basis,null,
                MetricStatus.AVAILABLE,List.of("q"),"hash");
        MetricResult<StructureTrendDto> trend=MetricResult.available(
                new StructureTrendDto(TrendState.UPTREND,oldSupportAt,PriceRelation.HH,PriceRelation.HL,List.of(),oldSupportAt));
        AtrPointDto atr=new AtrPointDto(bar.sessionDate(),14,new BigDecimal("5"),null,LocalDate.of(2026,9,1),14,20);
        TimeframeStructureDto frame=new TimeframeStructureDto(AnalysisTimeframe.DAILY,List.of(bar),MetricResult.available(atr),
                List.of(),List.of(),MetricResult.available(PriceRelation.HH),MetricResult.available(PriceRelation.HL),trend,trend,List.of(),List.of());
        PriceStructureDto structure=new PriceStructureDto("FIGI","hash",bar.sessionDate(),List.of(frame),List.of(),List.of());
        SupportResistanceDto levels=new SupportResistanceDto(List.of(support),List.of(resistance),List.of(),List.of(),List.of(),List.of());
        AnalysisContext ctx=new AnalysisContext("FIGI",Instant.parse("2026-10-14T22:00:00Z"),
                Instant.parse("2026-10-14T22:00:00Z"),AnalysisHorizon.SWING);
        TradePlanDto plan=service.build(ctx,price,structure,levels,
                new PlanConstraintDto(EntryPermission.ALLOW,List.of(),List.of())).value().plans().getFirst();
        assertThat(plan.validThroughSession()).isNull();
        assertThat(plan.status()).isEqualTo(TradePlanStatus.ACTIVE);
        assertThat(plan.reasons()).extracting(AnalysisReasonDto::code).doesNotContain("ENTRY_EXPIRED");
    }

    /** Scenario：修改前檢查額外Eligibility；修改後直接依價格／結構輸入，缺少資料不產生可執行計畫。 */
    @Test
    void missingTechnicalInputsCannotProduceExecutablePlan() {
        MarketStatusService calendar=new MarketStatusService(null);
        TradePlanService service=new TradePlanService(new TradePlanCalculator(new ExecutionCostCalculator()),new ExecutionCostCalculator(),calendar);
        AnalysisContext context=new AnalysisContext("F", Instant.parse("2026-10-15T22:00:00Z"),
                Instant.parse("2026-10-15T22:00:00Z"), AnalysisHorizon.SWING);

        MetricResult<TradePlanResultDto> result=service.build(context,null,null,null,null);

        assertThat(result.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(result.reasons()).extracting(AnalysisReasonDto::code).containsExactly("TRADE_PLAN_INPUT_REQUIRED");
        assertThat(result.value()).isNull();
    }

    // 例子：同區舊 retest 已過期後出現新 Spring -> 採用新 Spring 訊號時間與期限，CORRECTION 可於次日 ACTIVE，
    // 但遇當日 CPI 仍不得沿用過期 retest 繞過宏觀確認；且阻力翻支撐前之低收不得錯殺，報告日 ATR 上升不得抹除既有兩收失效。
    @Test
    void expiredRetestDoesNotShadowFreshSpringAndFrozenInvalidationRespectsRoleConfirmAndAnchorAtr() {
        MarketStatusService calendar = new MarketStatusService(null);
        TradePlanService service = new TradePlanService(
                new TradePlanCalculator(new ExecutionCostCalculator()), new ExecutionCostCalculator(), calendar);
        LocalDate oldRetestDate = LocalDate.of(2026, 9, 10);
        Instant oldConfirmAt = calendar.getSessionCloseAt(LocalDate.of(2026, 8, 10));
        Instant oldRoleConfirmAt = calendar.getSessionCloseAt(oldRetestDate);
        LocalDate reclaimDate = LocalDate.of(2026, 10, 14);
        LocalDate nextSession = LocalDate.of(2026, 10, 15);
        LocalDate springValidThrough = LocalDate.of(2026, 10, 21);

        // 1) 同區舊 retest 已過期 + 今日新 Spring（reclaimDate=10-14, current=10-15）
        LevelZoneDto flippedZone = new LevelZoneDto("S_FLIP", AnalysisTimeframe.DAILY, new BigDecimal("100"), new BigDecimal("104"),
                ZoneRole.SUPPORT, new BigDecimal("70"), oldConfirmAt, 2, oldRoleConfirmAt, ZoneState.ACTIVE,
                new BigDecimal("102"), new BigDecimal("4"), 1, ZoneStrengthLabel.HIGH, new BigDecimal("70"),
                2, oldRoleConfirmAt, new BigDecimal("102"), List.of("seed"), List.of("dep"), 1, 0, 1,
                null, null, RetestState.SUCCEEDED, true, List.of("e"));
        LevelZoneDto resistance = zone("R", "130", "135", ZoneRole.RESISTANCE, "4", oldConfirmAt);
        BreakoutDiagnosisDto spring = new BreakoutDiagnosisDto(
                BreakoutDiagnosis.SPRING_RECLAIMED, "S_FLIP", 1, 2, AnalysisTimeframe.DAILY,
                new BigDecimal("100"), new BigDecimal("104"), LocalDate.of(2026, 10, 13), reclaimDate, springValidThrough, nextSession,
                2, new BigDecimal("0.8"), new BigDecimal("1.5"), new BigDecimal("1.0"), null, null, null, null, null, null,
                List.of("SPRING_RECLAIMED", "RECLAIM_VOLUME_CONFIRMED"), List.of());

        // 翻轉前（2026-09-01, 09-02）有兩次低收 98.5（當時還是阻力），不得錯殺 2026-09-10 翻轉後的支撐
        List<NormalizedBarDto> bars = List.of(
                bar(calendar, LocalDate.of(2026, 9, 1), "98.5"),
                bar(calendar, LocalDate.of(2026, 9, 2), "98.5"),
                bar(calendar, reclaimDate, "102"),
                bar(calendar, nextSession, "102"));
        PriceBasisDescriptor basis = new PriceBasisDescriptor("BASIS", "MASSIVE", PriceAdjustment.SPLIT_ADJUSTED,
                nextSession, "v", ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, "e");
        NormalizedPriceSeriesDto price = new NormalizedPriceSeriesDto("FIGI", "DAILY", bars, basis, null, MetricStatus.AVAILABLE, List.of(), "h");
        MetricResult<StructureTrendDto> trend = MetricResult.available(new StructureTrendDto(
                TrendState.UPTREND, oldRoleConfirmAt, PriceRelation.HH, PriceRelation.HL, List.of(), oldRoleConfirmAt));
        AtrPointDto atr = new AtrPointDto(nextSession, 14, new BigDecimal("5"), null, LocalDate.of(2026, 8, 1), 14, 20);
        TimeframeStructureDto daily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, bars, MetricResult.available(atr),
                List.of(), List.of(), MetricResult.available(PriceRelation.HH), MetricResult.available(PriceRelation.HL), trend, trend, List.of(), List.of());
        PriceStructureDto structure = new PriceStructureDto("FIGI", "h", nextSession, List.of(daily), List.of(), List.of());
        SupportResistanceDto levels = new SupportResistanceDto(List.of(), List.of(resistance), List.of(flippedZone),
                List.of(), List.of(), List.of(), spring, List.of(spring));
        AnalysisContext ctx = new AnalysisContext("FIGI", calendar.getSessionCloseAt(nextSession), calendar.getSessionCloseAt(nextSession), AnalysisHorizon.SWING);

        PlanConstraintDto correctionOnly = new PlanConstraintDto(EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_CORRECTION"), List.of());
        TradePlanDto planCorr = service.build(ctx, price, structure, levels, correctionOnly).value().plans().getFirst();
        assertThat(planCorr.status()).isEqualTo(TradePlanStatus.ACTIVE);
        assertThat(planCorr.setupType()).isEqualTo(SetupType.PULLBACK);
        assertThat(planCorr.validThroughSession()).isEqualTo(springValidThrough);
        assertThat(planCorr.createdSignalAt()).isEqualTo(calendar.getSessionCloseAt(reclaimDate));

        // 遇當日 CPI：已過期 retest 不得復活放行，Spring 須等待
        PlanConstraintDto correctionCpi = new PlanConstraintDto(EntryPermission.REQUIRE_CLOSE_CONFIRMATION,
                List.of("EVENT_REQUIRES_CLOSE_CONFIRMATION", "MARKET_CORRECTION"), List.of());
        TradePlanDto planCorrCpi = service.build(ctx, price, structure, levels, correctionCpi).value().plans().getFirst();
        assertThat(planCorrCpi.status()).isEqualTo(TradePlanStatus.WAITING_TRIGGER);
        assertThat(planCorrCpi.reasons()).extracting(AnalysisReasonDto::code).contains("CLOSE_CONFIRMATION_REQUIRED");

        // 2) 報告日 ATR 由 4 升至 5，但支撐 anchorAtr 凍結為 4（閾值 99.0）；角色翻轉後兩日收 98.90 仍必須維持 INVALIDATED
        List<NormalizedBarDto> invalidBars = List.of(
                bar(calendar, LocalDate.of(2026, 9, 14), "98.9"),
                bar(calendar, LocalDate.of(2026, 9, 15), "98.9"),
                bar(calendar, nextSession, "102"));
        NormalizedPriceSeriesDto invalidPrice = new NormalizedPriceSeriesDto("FIGI", "DAILY", invalidBars, basis, null, MetricStatus.AVAILABLE, List.of(), "h");
        TradePlanDto stillInvalid = service.build(ctx, invalidPrice, structure, levels, correctionOnly).value().plans().getFirst();
        assertThat(stillInvalid.stop().protectivePrice()).isEqualByComparingTo("97.50");
        assertThat(stillInvalid.stop().atrValue()).isEqualByComparingTo("5");
        assertThat(stillInvalid.stop().invalidationAtrValue()).isEqualByComparingTo("4");
        assertThat(stillInvalid.stop().closeThreshold()).isEqualByComparingTo("99.00");
        assertThat(stillInvalid.status()).isEqualTo(TradePlanStatus.INVALIDATED);
        assertThat(stillInvalid.reasons()).extracting(AnalysisReasonDto::code).contains("TECHNICAL_INVALIDATION_CONFIRMED");
    }

    private NormalizedBarDto bar(MarketStatusService c,LocalDate d,String close){Instant open=c.getSessionOpenAt(d), end=c.getSessionCloseAt(d);return new NormalizedBarDto(d,new BigDecimal(close),new BigDecimal(close).add(BigDecimal.ONE),new BigDecimal(close).subtract(BigDecimal.ONE),new BigDecimal(close),MetricResult.available(BigDecimal.ONE),open,end,end,end,"v-"+d,"BASIS");}
    private LevelZoneDto zone(String id,String lo,String hi,ZoneRole role,String atr,Instant confirmed){BigDecimal l=new BigDecimal(lo),h=new BigDecimal(hi);return new LevelZoneDto(id,AnalysisTimeframe.DAILY,l,h,role,new BigDecimal("70"),confirmed,1,confirmed,ZoneState.ACTIVE,l.add(h).divide(new BigDecimal("2")),new BigDecimal(atr),1,ZoneStrengthLabel.HIGH,new BigDecimal("70"),1,confirmed,null,List.of(id+"seed"),List.of("dep"),1,0,1,null,null,RetestState.NONE,false,List.of(id+"e"));}
}
