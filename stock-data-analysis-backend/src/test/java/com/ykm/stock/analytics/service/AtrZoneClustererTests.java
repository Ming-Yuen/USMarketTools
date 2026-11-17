package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.AtrZoneClusterer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AtrZoneClustererTests {
    // 例子：2026-01-05 的 minor 區於 2026-01-12 升為 major，但不改寫原歷史。
    @Test
    void laterMajorConfirmationUpgradesExistingMinorZoneWithoutRewritingItsHistory() {
        AtrZoneClusterer clusterer = new AtrZoneClusterer();
        LocalDate d = LocalDate.of(2026, 1, 5);
        Instant minorConfirmed = Instant.parse("2026-01-07T21:00:00Z");
        Instant majorConfirmed = Instant.parse("2026-01-12T21:00:00Z");
        ConfirmedPivotDto minor = pivot("minor", PivotScale.MINOR, d, minorConfirmed);
        ConfirmedPivotDto major = pivot("major", PivotScale.MAJOR, d, majorConfirmed);

        List<AtrZoneClusterer.ZoneCandidate> zones = clusterer.cluster("FIGI", AnalysisTimeframe.DAILY, List.of(minor, major));

        assertThat(zones).hasSize(1);
        var z = zones.getFirst();
        assertThat(z.confirmedAt).isEqualTo(minorConfirmed);
        assertThat(z.major).isTrue();
        assertThat(z.boundsVersion).isEqualTo(1);
        assertThat(z.center).isEqualByComparingTo("100");
        assertThat(z.events).extracting(ZoneEventDto::type).containsExactly("ZONE_CONFIRMED", "MAJOR_SEED_UPGRADE");
    }

    // 例子：2026-01-02 起的 100、104 與 109 不得因鏈接併成同一區。
    @Test
    void chainBridgeBeyondOneAnchorAtrDoesNotCollapseFarSeedsIntoOneZone() {
        AtrZoneClusterer clusterer = new AtrZoneClusterer();
        Instant t = Instant.parse("2026-01-10T21:00:00Z");
        ConfirmedPivotDto a = pivot("a", PivotScale.MINOR, LocalDate.of(2026,1,2), t, "100");
        ConfirmedPivotDto b = pivot("b", PivotScale.MINOR, LocalDate.of(2026,1,3), t.plusSeconds(1), "104");
        ConfirmedPivotDto c = pivot("c", PivotScale.MINOR, LocalDate.of(2026,1,4), t.plusSeconds(2), "109");

        List<AtrZoneClusterer.ZoneCandidate> zones = clusterer.cluster("FIGI", AnalysisTimeframe.DAILY, List.of(a,b,c));

        assertThat(zones).hasSize(2);
    }

    private ConfirmedPivotDto pivot(String id, PivotScale scale, LocalDate d, Instant confirmed) {
        return pivot(id, scale, d, confirmed, "100");
    }
    private ConfirmedPivotDto pivot(String id, PivotScale scale, LocalDate d, Instant confirmed, String price) {
        return new ConfirmedPivotDto(id, PivotKind.LOW, scale, AnalysisTimeframe.DAILY, new BigDecimal(price), d,
                confirmed.minusSeconds(100), confirmed, confirmed, new BigDecimal("10"), new BigDecimal("10"),
                List.of("v-"+id), "dep-"+d);
    }
}
