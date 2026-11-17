package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
// 用途：負責「價格結構服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class PriceStructureService {
    private final TimeframeAggregator aggregator;
    private final WilderAtrCalculator atrCalculator;
    private final ConfirmedSwingDetector swingDetector;
    private final PriceStructureCalculator structureCalculator;

    // 用途：分析相關資料，作為「價格結構服務」流程中的處理步驟。
    public MetricResult<PriceStructureDto> analyze(AnalysisContext context, NormalizedPriceSeriesDto normalizedDailySeries) {
        if (normalizedDailySeries == null || normalizedDailySeries.quality()!=MetricStatus.AVAILABLE || normalizedDailySeries.bars().isEmpty())
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("NORMALIZED_PRICE_REQUIRED","STRUCTURE","需要完整已核驗的日線序列。"));
        List<TimeframeStructureDto> frames=new ArrayList<>();
        for (AnalysisTimeframe tf: AnalysisTimeframe.values()) {
            List<NormalizedBarDto> bars=aggregator.aggregate(normalizedDailySeries.bars(),tf);
            List<AtrPointDto> atr=atrCalculator.calculate(bars,14);
            List<ConfirmedPivotDto> minor=swingDetector.detect(context.figi(),tf,bars,atr,PivotScale.MINOR);
            List<ConfirmedPivotDto> major=swingDetector.detect(context.figi(),tf,bars,atr,PivotScale.MAJOR);
            MetricResult<AtrPointDto> latestAtr=atr.isEmpty()
                    ? MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("ATR_WARMUP_REQUIRED","STRUCTURE","ATR14 需要至少15根完整bar。"))
                    : MetricResult.available(atr.get(atr.size()-1));
            MetricResult<PriceRelation> hi=structureCalculator.relation(minor,PivotKind.HIGH);
            MetricResult<PriceRelation> lo=structureCalculator.relation(minor,PivotKind.LOW);
            MetricResult<StructureTrendDto> minorTrend=structureCalculator.trend(minor,hi,lo,context.analysisAsOf());
            MetricResult<PriceRelation> majorHi=structureCalculator.relation(major,PivotKind.HIGH);
            MetricResult<PriceRelation> majorLo=structureCalculator.relation(major,PivotKind.LOW);
            MetricResult<StructureTrendDto> majorTrend=structureCalculator.trend(major,majorHi,majorLo,context.analysisAsOf());
            List<StructureEventDto> events=structureCalculator.structureEvents(bars,atr,minor);
            List<AnalysisReasonDto> reasons=new ArrayList<>();
            if(bars.isEmpty()) reasons.add(AnalysisReasonDto.of("TIMEFRAME_INSUFFICIENT_DATA","STRUCTURE",tf+" 沒有完整期間。"));
            if(!atr.isEmpty() && atr.get(atr.size()-1).warmupCount()<100) reasons.add(new AnalysisReasonDto("ATR_SEED_SENSITIVE","STRUCTURE","ATR14 仍較接近初始 seed，解讀時需保留敏感度提示。",Map.of("warmupCount",atr.get(atr.size()-1).warmupCount()),List.of()));
            frames.add(new TimeframeStructureDto(tf,bars,latestAtr,minor,major,hi,lo,minorTrend,majorTrend,events,reasons));
        }
        List<AnalysisReasonDto> topReasons=timeframeConflictReasons(frames);
        PriceStructureDto dto=new PriceStructureDto(context.figi(),normalizedDailySeries.inputHash(),
                normalizedDailySeries.bars().get(normalizedDailySeries.bars().size()-1).sessionDate(),frames,normalizedDailySeries.evidenceRefs(),topReasons);
        return MetricResult.available(dto,dto.evidenceRefs());
    }
    // 用途：處理週期ConflictReasons，作為「價格結構服務」流程中的處理步驟。
    private List<AnalysisReasonDto> timeframeConflictReasons(List<TimeframeStructureDto> frames) {
        Set<TrendState> states=new LinkedHashSet<>();
        for(TimeframeStructureDto frame:frames) if(frame.minorTrend()!=null && frame.minorTrend().status()==MetricStatus.AVAILABLE && frame.minorTrend().value()!=null) states.add(frame.minorTrend().value().state());
        if(states.size()<=1) return List.of();
        return List.of(new AnalysisReasonDto("TIMEFRAME_CONFLICT","STRUCTURE","不同週期的已確認結構方向並不一致。",Map.of("states",states.stream().map(Enum::name).toList()),List.of()));
    }
}
