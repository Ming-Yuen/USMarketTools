package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneBreakRetestMachineTests {
    // 例子：2026-01-06 收盤 111 只進入待確認，次日再收盤站上才確認突破。
    @Test
    void firstCloseOnlyPendsAndSecondConsecutiveCloseConfirmsBreak() {
        ZoneBreakRetestMachine machine = new ZoneBreakRetestMachine();
        AtrZoneClusterer.ZoneCandidate z = resistanceZone();
        List<NormalizedBarDto> one = List.of(bar(LocalDate.of(2026,1,6),"108","112","107","111"));
        machine.evaluate(z, one);
        assertThat(z.state).isEqualTo(ZoneState.BREAK_PENDING);
        assertThat(z.role).isEqualTo(ZoneRole.RESISTANCE);

        z = resistanceZone();
        List<NormalizedBarDto> two = List.of(
                bar(LocalDate.of(2026,1,6),"108","112","107","111"),
                bar(LocalDate.of(2026,1,7),"111","113","110","112"));
        machine.evaluate(z, two);
        assertThat(z.state).isEqualTo(ZoneState.BROKEN);
        assertThat(z.role).isEqualTo(ZoneRole.RESISTANCE);
        assertThat(z.events).extracting(ZoneEventDto::type).contains("BREAKOUT");
    }

    // 例子：壓力區要回測成功後，才由阻力轉成支撐。
    @Test
    void successfulRetestIsRequiredBeforeResistanceBecomesSupport() {
        ZoneBreakRetestMachine machine = new ZoneBreakRetestMachine();
        AtrZoneClusterer.ZoneCandidate z = resistanceZone();
        List<NormalizedBarDto> bars = List.of(
                bar(LocalDate.of(2026,1,6),"108","112","107","111"),
                bar(LocalDate.of(2026,1,7),"111","113","110","112"),
                bar(LocalDate.of(2026,1,8),"112","113","103","106"),
                bar(LocalDate.of(2026,1,9),"106","110","105","109"));

        machine.evaluate(z, bars);

        assertThat(z.retestState).isEqualTo(RetestState.SUCCEEDED);
        assertThat(z.state).isEqualTo(ZoneState.ACTIVE);
        assertThat(z.role).isEqualTo(ZoneRole.SUPPORT);
        assertThat(z.roleVersion).isEqualTo(2);
        assertThat(z.events).extracting(ZoneEventDto::type).contains("BREAKOUT","RETEST_STARTED","RESISTANCE_TO_SUPPORT");
    }


    // 例子：回測觀察未完成時維持待定，不失敗也不過期。
    @Test
    void incompleteRetestObservationStaysPendingInsteadOfFailingOrExpiring() {
        ZoneBreakRetestMachine machine = new ZoneBreakRetestMachine();
        AtrZoneClusterer.ZoneCandidate z = resistanceZone();
        List<NormalizedBarDto> bars = List.of(
                bar(LocalDate.of(2026,1,6),"108","112","107","111"),
                bar(LocalDate.of(2026,1,7),"111","113","110","112"),
                bar(LocalDate.of(2026,1,8),"112","113","103","106"));

        machine.evaluate(z, bars);

        assertThat(z.state).isEqualTo(ZoneState.BROKEN);
        assertThat(z.retestState).isEqualTo(RetestState.PENDING);
        assertThat(z.events).extracting(ZoneEventDto::type).contains("BREAKOUT", "RETEST_STARTED");
        assertThat(z.events).extracting(ZoneEventDto::type).doesNotContain("RETEST_FAILED", "RETEST_EXPIRED");
    }

    // 例子：角色轉換成功後，之後仍可再次突破。
    @Test
    void successfulRoleSwitchCanLaterBreakAgain() {
        ZoneBreakRetestMachine machine = new ZoneBreakRetestMachine();
        AtrZoneClusterer.ZoneCandidate z = resistanceZone();
        List<NormalizedBarDto> bars = List.of(
                bar(LocalDate.of(2026,1,6),"108","112","107","111"),
                bar(LocalDate.of(2026,1,7),"111","113","110","112"),
                bar(LocalDate.of(2026,1,8),"112","113","103","106"),
                bar(LocalDate.of(2026,1,9),"106","110","105","109"),
                bar(LocalDate.of(2026,1,12),"96","97","92","93"),
                bar(LocalDate.of(2026,1,13),"93","94","91","92"));

        machine.evaluate(z, bars);

        assertThat(z.role).isEqualTo(ZoneRole.SUPPORT);
        assertThat(z.roleVersion).isEqualTo(2);
        assertThat(z.state).isEqualTo(ZoneState.BROKEN);
        assertThat(z.events).extracting(ZoneEventDto::type)
                .contains("RESISTANCE_TO_SUPPORT", "BREAKDOWN");
    }

    // 例子：2026-01-10 的新種子加強區間，但不改寫 2026-01-05 已確認邊界。
    @Test
    void laterSeedStrengthensZoneWithoutRewritingEarlierConfirmedBounds() {
        AtrZoneClusterer clusterer = new AtrZoneClusterer();
        Instant firstTime = Instant.parse("2026-01-05T21:00:00Z");
        Instant laterTime = Instant.parse("2026-01-10T21:00:00Z");
        ConfirmedPivotDto first = new ConfirmedPivotDto("P1", PivotKind.LOW, PivotScale.MINOR, AnalysisTimeframe.DAILY,
                new BigDecimal("100"), LocalDate.of(2026,1,5), firstTime, firstTime, firstTime,
                new BigDecimal("4"), new BigDecimal("4"), List.of("P1"), "D1");
        ConfirmedPivotDto later = new ConfirmedPivotDto("P2", PivotKind.LOW, PivotScale.MAJOR, AnalysisTimeframe.DAILY,
                new BigDecimal("101"), LocalDate.of(2026,1,10), laterTime, laterTime, laterTime,
                new BigDecimal("4"), new BigDecimal("4"), List.of("P2"), "D2");

        AtrZoneClusterer.ZoneCandidate zone = clusterer.cluster("FIGI", AnalysisTimeframe.DAILY, List.of(first, later)).getFirst();

        assertThat(zone.center).isEqualByComparingTo("100");
        assertThat(zone.lower).isEqualByComparingTo("99");
        assertThat(zone.upper).isEqualByComparingTo("101");
        assertThat(zone.boundsVersion).isEqualTo(1);
        assertThat(zone.major).isTrue();
        assertThat(zone.events).extracting(ZoneEventDto::type).contains("ZONE_CONFIRMED", "SEED_ADDED");
    }

    // 例子：破位真假診斷區分放量實體長黑（HIGH_RISK_BREAKDOWN）、大盤重挫下影收回（SUSPECTED_SHAKEOUT）、
    // 隔日放量收回並凍結 5 交易日視窗（SPRING_RECLAIMED）、模糊單日跌破（BREACH_OBSERVING）、防穿越與零振幅保護。
    @Test
    void breakoutDiagnosisDistinguishesBreakdownShakeoutSpringObservingAndPreventsLookAhead() {
        ZoneBreakRetestMachine machine = new ZoneBreakRetestMachine();
        VolumeAnalysisCalculator volumeCalc = new VolumeAnalysisCalculator();
        MarketStatusService calendar = new MarketStatusService(null);

        // 基礎 20 根量=100、價位在支撐 100-104 之上的日線
        List<NormalizedBarDto> base = warmupBarsAboveSupport(20, LocalDate.of(2026, 2, 2), "106", "100");
        AtrZoneClusterer.ZoneCandidate sup = supportZone(base.get(0).availableAt().minusSeconds(3600));

        // 1) 放量實體長黑（rvol=2.0）且大盤平穩 -> HIGH_RISK_BREAKDOWN
        List<NormalizedBarDto> breakdownBars = new ArrayList<>(base);
        LocalDate dBreak = calendar.getNextTradingDay(base.getLast().sessionDate());
        breakdownBars.add(barWithVol(dBreak, "102", "102.2", "96.0", "96.4", "200"));
        BreakoutDiagnosisDto highRisk = machine.diagnoseSupport(sup, breakdownBars, volumeCalc,
                Map.of(dBreak, new BigDecimal("0.002")), calendar::getNextTradingDay);
        assertThat(highRisk).isNotNull();
        assertThat(highRisk.diagnosis()).isEqualTo(BreakoutDiagnosis.HIGH_RISK_BREAKDOWN);
        assertThat(highRisk.zoneId()).isEqualTo("SUP");
        assertThat(highRisk.evidenceCodes()).contains("SOLID_BEARISH_CLOSE_NEAR_LOW", "HIGH_VOLUME_BREAKDOWN", "INDEPENDENT_WEAKNESS_VS_SPY");

        // 2) 大盤重挫日（SPY -2.2%）個股盤中跌破下緣但留長下影線收回 -> SUSPECTED_SHAKEOUT
        List<NormalizedBarDto> shakeoutBars = new ArrayList<>(base);
        shakeoutBars.add(barWithVol(dBreak, "103", "104", "97", "102.5", "90"));
        BreakoutDiagnosisDto shakeout = machine.diagnoseSupport(sup, shakeoutBars, volumeCalc,
                Map.of(dBreak, new BigDecimal("-0.022")), calendar::getNextTradingDay);
        assertThat(shakeout).isNotNull();
        assertThat(shakeout.diagnosis()).isEqualTo(BreakoutDiagnosis.SUSPECTED_SHAKEOUT);
        assertThat(shakeout.evidenceCodes()).contains("INTRADAY_BELOW_SUPPORT_RECOVERED", "LONG_LOWER_SHADOW", "MARKET_WIDE_SELLOFF_DRAG");

        // 3) 先跌破次日放量收復（rvol=1.6）-> SPRING_RECLAIMED；T+2 縮量續站上方不重設有效期、T+6 過期
        List<NormalizedBarDto> springBars = new ArrayList<>(base);
        LocalDate dReclaim = calendar.getNextTradingDay(dBreak);
        springBars.add(barWithVol(dBreak, "101", "101.5", "98.8", "99.2", "90"));
        springBars.add(barWithVol(dReclaim, "99.5", "103.5", "99.0", "103.0", "160"));
        BreakoutDiagnosisDto springT0 = machine.diagnoseSupport(sup, springBars, volumeCalc, Map.of(), calendar::getNextTradingDay);
        assertThat(springT0).isNotNull();
        assertThat(springT0.diagnosis()).isEqualTo(BreakoutDiagnosis.SPRING_RECLAIMED);
        assertThat(springT0.breachDate()).isEqualTo(dBreak);
        assertThat(springT0.reclaimDate()).isEqualTo(dReclaim);
        assertThat(springT0.rvolAtReclaim()).isEqualByComparingTo("1.6080");
        LocalDate expectedValidThrough = dReclaim;
        for (int i = 0; i < 5; i++) expectedValidThrough = calendar.getNextTradingDay(expectedValidThrough);
        assertThat(springT0.validThroughSession()).isEqualTo(expectedValidThrough);

        // 非紅棒收復且原始 RVOL = 1.19996（四捨五入為 1.2000 但原始 < 1.2）不得通過 Spring 放量門檻
        List<NormalizedBarDto> borderVolBars = new ArrayList<>(base);
        borderVolBars.add(barWithVol(dBreak, "101", "101.5", "98.8", "99.2", "100"));
        borderVolBars.add(barWithVol(dReclaim, "103.5", "104.0", "100.0", "101.0", "119.996"));
        assertThat(machine.diagnoseSupport(sup, borderVolBars, volumeCalc, Map.of(), calendar::getNextTradingDay)).isNull();

        // T+2 續站支撐上（縮量 70）：仍維持同一凍結有效期與收復日量比 rvolAtReclaim=1.6，不重設
        LocalDate dT1 = calendar.getNextTradingDay(dReclaim);
        LocalDate dT2 = calendar.getNextTradingDay(dT1);
        springBars.add(barWithVol(dT1, "103", "104", "102", "103.2", "70"));
        springBars.add(barWithVol(dT2, "103", "104.5", "102.5", "103.8", "70"));
        BreakoutDiagnosisDto springT2 = machine.diagnoseSupport(sup, springBars, volumeCalc, Map.of(), calendar::getNextTradingDay);
        assertThat(springT2).isNotNull();
        assertThat(springT2.diagnosis()).isEqualTo(BreakoutDiagnosis.SPRING_RECLAIMED);
        assertThat(springT2.reclaimDate()).isEqualTo(dReclaim);
        assertThat(springT2.rvolAtReclaim()).isEqualByComparingTo("1.6080");
        assertThat(springT2.validThroughSession()).isEqualTo(expectedValidThrough);

        // BROKEN 區於破位後第 2 日收復，在 breakConfirmedAt 後第 6 日（但仍在 reclaimDate+5 有效期內）不得提前消失
        AtrZoneClusterer.ZoneCandidate brokenThenSpring = supportZone(base.get(0).availableAt().minusSeconds(3600));
        brokenThenSpring.state = ZoneState.BROKEN;
        brokenThenSpring.breakConfirmedAt = dBreak.atTime(21, 0).toInstant(ZoneOffset.UTC);
        List<NormalizedBarDto> brokenSpringBars = new ArrayList<>(springBars);
        LocalDate dT3 = calendar.getNextTradingDay(dT2);
        LocalDate dT4 = calendar.getNextTradingDay(dT3);
        LocalDate dT5 = calendar.getNextTradingDay(dT4);
        brokenSpringBars.add(barWithVol(dT3, "103", "104", "102", "103.5", "70"));
        brokenSpringBars.add(barWithVol(dT4, "103", "104", "102", "103.5", "70"));
        brokenSpringBars.add(barWithVol(dT5, "103", "104", "102", "103.5", "70"));
        BreakoutDiagnosisDto springDay5 = machine.diagnoseSupport(brokenThenSpring, brokenSpringBars, volumeCalc, Map.of(), calendar::getNextTradingDay);
        assertThat(springDay5).isNotNull();
        assertThat(springDay5.diagnosis()).isEqualTo(BreakoutDiagnosis.SPRING_RECLAIMED);
        assertThat(springDay5.validThroughSession()).isEqualTo(expectedValidThrough);

        // 陳舊 BROKEN 區（十多個交易日前即已破位）在近期再次上下穿越舊下緣，不得誕生全新的 Spring 訊號
        AtrZoneClusterer.ZoneCandidate staleBroken = supportZone(base.get(0).availableAt().minusSeconds(3600));
        staleBroken.state = ZoneState.BROKEN;
        staleBroken.breakConfirmedAt = base.get(5).availableAt();
        assertThat(machine.diagnoseSupport(staleBroken, springBars, volumeCalc, Map.of(), calendar::getNextTradingDay)).isNull();

        // T+6 超過 5 個交易日有效期 -> 不再輸出過期 Spring
        LocalDate cursor = dT2;
        for (int i = 0; i < 4; i++) {
            cursor = calendar.getNextTradingDay(cursor);
            springBars.add(barWithVol(cursor, "104", "105", "103", "104", "80"));
        }
        assertThat(machine.diagnoseSupport(sup, springBars, volumeCalc, Map.of(), calendar::getNextTradingDay)).isNull();

        // 4) 均量 1.1x、單日收在下緣下且無洗盤／高風險特徵 -> BREACH_OBSERVING
        List<NormalizedBarDto> obsBars = new ArrayList<>(base);
        obsBars.add(barWithVol(dBreak, "100.5", "101.0", "98.5", "99.3", "110"));
        BreakoutDiagnosisDto observing = machine.diagnoseSupport(sup, obsBars, volumeCalc,
                Map.of(dBreak, new BigDecimal("-0.002")), calendar::getNextTradingDay);
        assertThat(observing).isNotNull();
        assertThat(observing.diagnosis()).isEqualTo(BreakoutDiagnosis.BREACH_OBSERVING);

        // 5) 連續 2 日收於下緣下方且第二日反抽區內失敗、量縮 -> HIGH_RISK_BREAKDOWN (FAILED_RECLAIM_BOUNCE)
        obsBars.add(barWithVol(dReclaim, "99.2", "100.4", "98.4", "99.0", "80"));
        BreakoutDiagnosisDto failedBounce = machine.diagnoseSupport(sup, obsBars, volumeCalc, Map.of(), calendar::getNextTradingDay);
        assertThat(failedBounce).isNotNull();
        assertThat(failedBounce.diagnosis()).isEqualTo(BreakoutDiagnosis.HIGH_RISK_BREAKDOWN);
        assertThat(failedBounce.evidenceCodes()).contains("FAILED_RECLAIM_BOUNCE", "WEAK_REBOUND_VOLUME");

        // 6) 週五才確認的週線支撐不得回頭診斷週三；零振幅 K 線之形態比率應為 null
        AtrZoneClusterer.ZoneCandidate futureConfirmed = supportZone(dReclaim.atTime(21, 0).toInstant(ZoneOffset.UTC));
        assertThat(machine.diagnoseSupport(futureConfirmed, breakdownBars, volumeCalc, Map.of(), calendar::getNextTradingDay)).isNull();

        List<NormalizedBarDto> flatBars = new ArrayList<>(base);
        flatBars.add(barWithVol(dBreak, "99.5", "99.5", "99.5", "99.5", "110"));
        BreakoutDiagnosisDto flatDiag = machine.diagnoseSupport(sup, flatBars, volumeCalc, Map.of(), calendar::getNextTradingDay);
        assertThat(flatDiag).isNotNull();
        assertThat(flatDiag.lowerShadowRatio()).isNull();
        assertThat(flatDiag.bodyRatio()).isNull();
        assertThat(flatDiag.closePositionInRange()).isNull();
    }

    // 例子：最後一道達 30 分支撐跌破成 BREAK_PENDING、零有效支撐時，SupportResistanceService 仍回傳 AVAILABLE 並攜帶破位診斷；
    // 低於 30 分之弱支撐則不得進入診斷（避免誤壓試單）。
    @Test
    void supportResistanceStillDeliversBreakoutDiagnosisWhenZeroActiveZonesRemainAndSkipsWeakZonesBelow30() {
        MarketStatusService calendar = new MarketStatusService(null);
        SupportResistanceService srService = new SupportResistanceService(
                new AtrZoneClusterer(), new ZoneBreakRetestMachine(), new ZoneStrengthCalculator(),
                new ZoneOverlapCalculator(), new VolumeAnalysisCalculator(), calendar);
        List<NormalizedBarDto> bars = warmupBarsAboveSupport(21, LocalDate.of(2026, 3, 2), "105", "100");
        LocalDate breachDay = calendar.getNextTradingDay(bars.getLast().sessionDate());
        bars.add(barWithVol(breachDay, "101", "101.2", "94.0", "94.5", "220"));
        Instant seedConfirm = bars.get(2).availableAt();
        // 1) 僅單一 DAILY MAJOR 種子且無 rejection／POC -> decayed < 30 -> 不進入診斷
        ConfirmedPivotDto weakSeed = new ConfirmedPivotDto("P_WEAK", PivotKind.LOW, PivotScale.MAJOR, AnalysisTimeframe.DAILY,
                new BigDecimal("101"), bars.get(1).sessionDate(), bars.get(1).availableAt(), seedConfirm, seedConfirm,
                new BigDecimal("4"), new BigDecimal("4"), List.of("P_WEAK"), "D1");
        TimeframeStructureDto weakDaily = new TimeframeStructureDto(AnalysisTimeframe.DAILY, bars, null, List.of(), List.of(weakSeed),
                null, null, null, null, List.of(), List.of());
        PriceStructureDto weakStructure = new PriceStructureDto("FIGI", "h", breachDay, List.of(weakDaily), List.of(), List.of());
        NormalizedPriceSeriesDto series = new NormalizedPriceSeriesDto("FIGI", "DAILY", bars, null, null, MetricStatus.AVAILABLE, List.of(), "h");
        AnalysisContext ctx = new AnalysisContext("FIGI", bars.getLast().availableAt(), bars.getLast().availableAt(), AnalysisHorizon.SWING);
        assertThat(srService.analyze(ctx, series, weakStructure, null).status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);

        // 2) 搭配 POC 籌碼共振（raw=20+15=35，衰減後仍 >= 30）-> 零有效支撐時仍交付 HIGH_RISK_BREAKDOWN 診斷與 NO_ACTIVE_LEVEL_ZONE
        VolumeBinDto poc = new VolumeBinDto(1, new BigDecimal("99"), new BigDecimal("103"), new BigDecimal("90000"));
        VolumeProfileDto vp = new VolumeProfileDto(List.of(poc), poc, List.of(poc), List.of(), "DAILY_OHLCV_APPROXIMATION");
        VolumeAnalysisDto vol = new VolumeAnalysisDto(null, null, null, null, MetricResult.available(vp), null, List.of(), List.of());
        MarketContextDto mc = new MarketContextDto(null, null, null, List.of(), null, List.of(), List.of(), null, vol, null, null, Map.of(), null);

        MetricResult<SupportResistanceDto> result = srService.analyze(ctx, series, weakStructure, mc);

        assertThat(result.status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(result.value().supports()).isEmpty();
        assertThat(result.value().containingZones()).isEmpty();
        assertThat(result.value().reasons()).extracting(AnalysisReasonDto::code).contains("NO_ACTIVE_LEVEL_ZONE");
        assertThat(result.value().breakoutDiagnosis()).isNotNull();
        assertThat(result.value().breakoutDiagnosis().diagnosis()).isEqualTo(BreakoutDiagnosis.HIGH_RISK_BREAKDOWN);
    }

    private AtrZoneClusterer.ZoneCandidate supportZone(Instant confirmedAt) {
        AtrZoneClusterer.ZoneCandidate z = new AtrZoneClusterer.ZoneCandidate();
        z.id = "SUP";
        z.timeframe = AnalysisTimeframe.DAILY;
        z.role = ZoneRole.SUPPORT;
        z.lower = new BigDecimal("100");
        z.upper = new BigDecimal("104");
        z.center = new BigDecimal("102");
        z.anchorAtr = new BigDecimal("4");
        z.confirmedAt = confirmedAt;
        z.roleConfirmedAt = confirmedAt;
        z.seedIds.add("S");
        return z;
    }

    private List<NormalizedBarDto> warmupBarsAboveSupport(int count, LocalDate startDate, String price, String vol) {
        MarketStatusService calendar = new MarketStatusService(null);
        List<NormalizedBarDto> list = new ArrayList<>();
        LocalDate d = calendar.isTradingDay(startDate) ? startDate : calendar.getNextTradingDay(startDate);
        for (int i = 0; i < count; i++) {
            list.add(barWithVol(d, price, new BigDecimal(price).add(BigDecimal.ONE).toPlainString(),
                    new BigDecimal(price).subtract(BigDecimal.ONE).toPlainString(), price, vol));
            d = calendar.getNextTradingDay(d);
        }
        return list;
    }

    private NormalizedBarDto barWithVol(LocalDate d, String o, String h, String l, String c, String vol) {
        Instant t = d.atTime(21, 0).toInstant(ZoneOffset.UTC);
        return new NormalizedBarDto(d, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c),
                MetricResult.available(new BigDecimal(vol)), t.minusSeconds(6 * 3600), t, t, t, "v-" + d, "B");
    }

    private AtrZoneClusterer.ZoneCandidate resistanceZone(){
        AtrZoneClusterer.ZoneCandidate z=new AtrZoneClusterer.ZoneCandidate();
        z.id="Z";z.role=ZoneRole.RESISTANCE;z.lower=new BigDecimal("95");z.upper=new BigDecimal("105");z.center=new BigDecimal("100");z.anchorAtr=new BigDecimal("4");z.confirmedAt=Instant.parse("2026-01-05T21:00:00Z");z.roleConfirmedAt=z.confirmedAt;z.seedIds.add("S");return z;
    }
    private NormalizedBarDto bar(LocalDate d,String o,String h,String l,String c){Instant t=d.atTime(21,0).toInstant(ZoneOffset.UTC);return new NormalizedBarDto(d,new BigDecimal(o),new BigDecimal(h),new BigDecimal(l),new BigDecimal(c),MetricResult.available(BigDecimal.ONE),t.minusSeconds(6*3600),t,t,t,"v-"+d,"B");}
}
