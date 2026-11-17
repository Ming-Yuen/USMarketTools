package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

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

    private AtrZoneClusterer.ZoneCandidate resistanceZone(){
        AtrZoneClusterer.ZoneCandidate z=new AtrZoneClusterer.ZoneCandidate();
        z.id="Z";z.role=ZoneRole.RESISTANCE;z.lower=new BigDecimal("95");z.upper=new BigDecimal("105");z.center=new BigDecimal("100");z.anchorAtr=new BigDecimal("4");z.confirmedAt=Instant.parse("2026-01-05T21:00:00Z");z.roleConfirmedAt=z.confirmedAt;z.seedIds.add("S");return z;
    }
    private NormalizedBarDto bar(LocalDate d,String o,String h,String l,String c){Instant t=d.atTime(21,0).toInstant(ZoneOffset.UTC);return new NormalizedBarDto(d,new BigDecimal(o),new BigDecimal(h),new BigDecimal(l),new BigDecimal(c),MetricResult.available(BigDecimal.ONE),t.minusSeconds(6*3600),t,t,t,"v-"+d,"B");}
}
