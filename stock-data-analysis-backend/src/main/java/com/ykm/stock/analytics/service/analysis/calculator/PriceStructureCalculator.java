package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;
import java.math.*; import java.time.Instant; import java.util.*;

@Component
// 用途：負責「價格結構計算」的純計算或判定邏輯，避免上層服務重複實作相同規則。
public class PriceStructureCalculator {
    private static final MathContext MC=MathContext.DECIMAL128;
    // 用途：處理relation，作為「價格結構計算」流程中的處理步驟。
    public MetricResult<PriceRelation> relation(List<ConfirmedPivotDto> pivots, PivotKind kind) {
        List<ConfirmedPivotDto> xs=pivots.stream().filter(p->p.kind()==kind).sorted(Comparator.comparing(ConfirmedPivotDto::occurrenceSession)).toList();
        if(xs.size()<2) return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("PIVOT_PAIR_REQUIRED","STRUCTURE","需要兩個同類已確認 pivot。"));
        ConfirmedPivotDto old=xs.get(xs.size()-2), cur=xs.get(xs.size()-1);
        if(cur.atrAtConfirmation()==null) return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("ATR_REQUIRED","STRUCTURE","結構關係需要確認時 ATR。"));
        BigDecimal tol=BigDecimal.valueOf(0.01).max(cur.atrAtConfirmation().multiply(new BigDecimal("0.10"),MC));
        int up=cur.price().compareTo(old.price().add(tol,MC)); int down=cur.price().compareTo(old.price().subtract(tol,MC));
        PriceRelation r;
        if(kind==PivotKind.HIGH) r=up>0?PriceRelation.HH:down<0?PriceRelation.LH:PriceRelation.EH;
        else r=up>0?PriceRelation.HL:down<0?PriceRelation.LL:PriceRelation.EL;
        return MetricResult.available(r,List.of(old.id(),cur.id()));
    }
    // 用途：處理結構Events，作為「價格結構計算」流程中的處理步驟。
    public List<StructureEventDto> structureEvents(List<NormalizedBarDto> bars, List<AtrPointDto> atr, List<ConfirmedPivotDto> pivots) {
        if (bars == null || bars.isEmpty() || atr == null || atr.isEmpty() || pivots == null || pivots.isEmpty()) return List.of();
        Map<java.time.LocalDate, BigDecimal> atrByDate = new HashMap<>();
        for (AtrPointDto point : atr) atrByDate.put(point.sessionDate(), point.atr());
        List<StructureEventDto> events = new ArrayList<>();
        for (NormalizedBarDto bar : bars) {
            BigDecimal currentAtr = atrByDate.get(bar.sessionDate());
            if (currentAtr == null || currentAtr.signum() <= 0 || bar.sessionOpenAt() == null) continue;
            ConfirmedPivotDto high = latestConfirmedBefore(pivots, PivotKind.HIGH, bar.sessionOpenAt());
            ConfirmedPivotDto low = latestConfirmedBefore(pivots, PivotKind.LOW, bar.sessionOpenAt());
            if (high != null) {
                BigDecimal threshold = high.price().add(currentAtr.multiply(new BigDecimal("0.25"), MC), MC);
                if (bar.close().compareTo(threshold) > 0) events.add(event("STRUCTURE_HIGH_BREACH", PivotKind.HIGH, bar, high, threshold));
                else if (bar.high().compareTo(threshold) > 0) events.add(event("WICK_PROBE", PivotKind.HIGH, bar, high, threshold));
            }
            if (low != null) {
                BigDecimal threshold = low.price().subtract(currentAtr.multiply(new BigDecimal("0.25"), MC), MC);
                if (bar.close().compareTo(threshold) < 0) events.add(event("STRUCTURE_LOW_BREACH", PivotKind.LOW, bar, low, threshold));
                else if (bar.low().compareTo(threshold) < 0) events.add(event("WICK_PROBE", PivotKind.LOW, bar, low, threshold));
            }
        }
        return List.copyOf(events);
    }

    // 用途：處理latest已確認Before，作為「價格結構計算」流程中的處理步驟。
    private ConfirmedPivotDto latestConfirmedBefore(List<ConfirmedPivotDto> pivots, PivotKind kind, Instant barStart) {
        return pivots.stream().filter(p -> p.kind() == kind && p.confirmationTime() != null && !p.confirmationTime().isAfter(barStart))
                .max(Comparator.comparing(ConfirmedPivotDto::confirmationTime).thenComparing(ConfirmedPivotDto::occurrenceSession)).orElse(null);
    }

    // 用途：處理事件，作為「價格結構計算」流程中的處理步驟。
    private StructureEventDto event(String code, PivotKind kind, NormalizedBarDto bar, ConfirmedPivotDto pivot, BigDecimal threshold) {
        Instant availableAt = bar.availableAt() == null ? bar.sessionCloseAt() : bar.availableAt();
        return new StructureEventDto(code, kind, bar.sessionDate(), availableAt, pivot.price(), threshold, pivot.id(), pivot.dependenceKey());
    }

    // 用途：處理trend，作為「價格結構計算」流程中的處理步驟。
    public MetricResult<StructureTrendDto> trend(List<ConfirmedPivotDto> pivots, MetricResult<PriceRelation> high, MetricResult<PriceRelation> low, Instant asOf) {
        if(high.status()!=MetricStatus.AVAILABLE || low.status()!=MetricStatus.AVAILABLE) return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("STRUCTURE_RELATIONS_REQUIRED","STRUCTURE","高低結構關係不足。"));
        PriceRelation h=high.value(), l=low.value(); TrendState state;
        if(h==PriceRelation.HH && l==PriceRelation.HL) state=TrendState.UPTREND;
        else if(h==PriceRelation.LH && l==PriceRelation.LL) state=TrendState.DOWNTREND;
        else if((h==PriceRelation.LH||h==PriceRelation.EH)&&(l==PriceRelation.HL||l==PriceRelation.EL)) state=TrendState.SIDEWAYS;
        else state=TrendState.TRANSITION;
        List<String> ids=pivots.stream().sorted(Comparator.comparing(ConfirmedPivotDto::occurrenceSession)).skip(Math.max(0,pivots.size()-4)).map(ConfirmedPivotDto::id).toList();
        Instant transition=pivots.stream().map(ConfirmedPivotDto::confirmationTime).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        return MetricResult.available(new StructureTrendDto(state,asOf,h,l,ids,transition),ids);
    }
}
