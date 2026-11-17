package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PriceStructureServiceTests {
    // 例子：可用週期方向衝突時必須如實報告，不得強行合成單一方向。
    @Test
    void conflictingAvailableTimeframesAreReportedInsteadOfForcedIntoOneDirection() {
        TimeframeAggregator aggregator = mock(TimeframeAggregator.class);
        WilderAtrCalculator atr = mock(WilderAtrCalculator.class);
        ConfirmedSwingDetector swing = mock(ConfirmedSwingDetector.class);
        PriceStructureCalculator structure = mock(PriceStructureCalculator.class);
        PriceStructureService service = new PriceStructureService(aggregator, atr, swing, structure);
        NormalizedBarDto bar = bar(LocalDate.of(2026, 10, 15));
        when(aggregator.aggregate(anyList(), any())).thenReturn(List.of(bar));
        when(atr.calculate(anyList(), eq(14))).thenReturn(List.of(new AtrPointDto(bar.sessionDate(),14,BigDecimal.ONE,BigDecimal.ONE,bar.sessionDate(),14,0)));
        when(swing.detect(anyString(), any(), anyList(), anyList(), any())).thenReturn(List.of());
        when(structure.relation(anyList(), any())).thenReturn(MetricResult.available(PriceRelation.EH), MetricResult.available(PriceRelation.EL));
        MetricResult<StructureTrendDto> up = MetricResult.available(new StructureTrendDto(TrendState.UPTREND, bar.availableAt(), PriceRelation.HH, PriceRelation.HL, List.of(), bar.availableAt()));
        MetricResult<StructureTrendDto> down = MetricResult.available(new StructureTrendDto(TrendState.DOWNTREND, bar.availableAt(), PriceRelation.LH, PriceRelation.LL, List.of(), bar.availableAt()));
        MetricResult<StructureTrendDto> side = MetricResult.available(new StructureTrendDto(TrendState.SIDEWAYS, bar.availableAt(), PriceRelation.EH, PriceRelation.EL, List.of(), bar.availableAt()));
        when(structure.trend(anyList(), any(), any(), any())).thenReturn(up, up, down, down, side, side);
        when(structure.structureEvents(anyList(), anyList(), anyList())).thenReturn(List.of());
        AnalysisContext context = new AnalysisContext("FIGI", Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-16T00:00:00Z"), AnalysisHorizon.SWING);
        NormalizedPriceSeriesDto input = new NormalizedPriceSeriesDto("FIGI", "DAILY", List.of(bar), null, null, MetricStatus.AVAILABLE, List.of(), "hash");

        MetricResult<PriceStructureDto> result = service.analyze(context, input);

        assertThat(result.status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(result.value().reasons()).extracting(AnalysisReasonDto::code).containsExactly("TIMEFRAME_CONFLICT");
    }

    private NormalizedBarDto bar(LocalDate d) {
        Instant t = d.atTime(20,0).toInstant(ZoneOffset.UTC);
        return new NormalizedBarDto(d, BigDecimal.TEN, new BigDecimal("11"), new BigDecimal("9"), BigDecimal.TEN,
                MetricResult.available(BigDecimal.ONE), t.minusSeconds(6*3600), t, t, t, "v", "BASIS");
    }
}
