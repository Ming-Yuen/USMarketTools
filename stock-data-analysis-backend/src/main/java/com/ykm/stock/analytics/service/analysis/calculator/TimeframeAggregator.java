package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Component
@RequiredArgsConstructor
// 用途：負責「週期聚合」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class TimeframeAggregator {
    private final MarketStatusService calendar;

    // 用途：聚合相關資料，作為「週期聚合」流程中的處理步驟。
    public List<NormalizedBarDto> aggregate(List<NormalizedBarDto> daily, AnalysisTimeframe timeframe) {
        if (timeframe == AnalysisTimeframe.DAILY) return List.copyOf(daily);
        Map<String,List<NormalizedBarDto>> groups=new LinkedHashMap<>();
        for (NormalizedBarDto b: daily) groups.computeIfAbsent(key(b.sessionDate(), timeframe), k->new ArrayList<>()).add(b);
        List<NormalizedBarDto> out=new ArrayList<>();
        for (List<NormalizedBarDto> group: groups.values()) {
            group.sort(Comparator.comparing(NormalizedBarDto::sessionDate));
            LocalDate any=group.get(0).sessionDate();
            LocalDate start=timeframe==AnalysisTimeframe.WEEKLY?any.with(java.time.DayOfWeek.MONDAY):any.withDayOfMonth(1);
            LocalDate end=timeframe==AnalysisTimeframe.WEEKLY?start.plusDays(6):any.with(TemporalAdjusters.lastDayOfMonth());
            List<LocalDate> required;
            try { required=calendar.getTradingDays(start,end); } catch (IllegalArgumentException ex) { continue; }
            Set<LocalDate> actual=new HashSet<>(group.stream().map(NormalizedBarDto::sessionDate).toList());
            if (!actual.containsAll(required) || required.isEmpty()) continue;
            List<NormalizedBarDto> ordered=required.stream().map(d->group.stream().filter(b->b.sessionDate().equals(d)).findFirst().orElse(null)).toList();
            if (ordered.stream().anyMatch(Objects::isNull)) continue;
            NormalizedBarDto first=ordered.get(0), last=ordered.get(ordered.size()-1);
            Set<String> basisIds=new LinkedHashSet<>(ordered.stream().map(NormalizedBarDto::basisId).toList());
            if (basisIds.size()!=1 || basisIds.contains(null)) continue;
            BigDecimal high=ordered.stream().map(NormalizedBarDto::high).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low=ordered.stream().map(NormalizedBarDto::low).min(BigDecimal::compareTo).orElseThrow();
            boolean volumeAvailable=ordered.stream().allMatch(b->b.volume()!=null && b.volume().status()==MetricStatus.AVAILABLE && b.volume().value()!=null);
            MetricResult<BigDecimal> volume=volumeAvailable
                    ? MetricResult.available(ordered.stream().map(b->b.volume().value()).reduce(BigDecimal.ZERO,BigDecimal::add))
                    : MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("VOLUME_INCOMPLETE","VOLUME","聚合週/月存在缺失成交量。"));
            Instant available=ordered.stream().map(NormalizedBarDto::availableAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
            Instant observed=ordered.stream().map(NormalizedBarDto::observedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
            out.add(new NormalizedBarDto(last.sessionDate(),first.open(),high,low,last.close(),volume,first.sessionOpenAt(),last.sessionCloseAt(),available,observed,null,first.basisId()));
        }
        return List.copyOf(out);
    }

    // 用途：處理key，作為「週期聚合」流程中的處理步驟。
    private String key(LocalDate d, AnalysisTimeframe tf) {
        if (tf==AnalysisTimeframe.MONTHLY) return d.getYear()+"-"+d.getMonthValue();
        return d.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)+"-W"+d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
