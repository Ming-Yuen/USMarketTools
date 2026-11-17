package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Component
/** 負責由成交量與價格序列計算 RVOL、OBV、量價背離、volume profile 與 anchored VWAP 等量能證據。 */
public class VolumeAnalysisCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal RVOL_15 = new BigDecimal("1.5");
    private static final BigDecimal RVOL_20 = new BigDecimal("2.0");

    // 用途：根據輸入資料計算相關資料，產生可供分析或交易決策使用的標準結果。
    public VolumeAnalysisDto calculate(List<NormalizedBarDto> bars, PriceStructureDto structure) {
        if (bars == null || bars.isEmpty()) return unavailableResult("VOLUME_PRICE_REQUIRED");
        MetricResult<BigDecimal> rvol = rvolAt(bars, bars.size()-1, 20);
        List<BigDecimal> obvSeries = obvSeries(bars);
        MetricResult<BigDecimal> obv = obvSeries.isEmpty() ? insufficient("VOLUME_REQUIRED") : MetricResult.available(obvSeries.get(obvSeries.size()-1));
        MetricResult<BigDecimal> netDays = netVolumeDays(bars);
        BreakoutDirection breakout = breakoutVolume(bars, structure, rvol);
        Divergence divergence = divergence(bars, structure, obvSeries);
        MetricResult<BigDecimal> primary = primarySignal(breakout, divergence, netDays);
        MetricResult<VolumeProfileDto> profile = volumeProfile(bars);
        MetricResult<BigDecimal> avwap = anchoredVwap(bars, structure);
        return new VolumeAnalysisDto(rvol, obv, netDays, primary, profile, avwap, List.of(), evidence(structure));
    }

    // 用途：處理rvol at的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> rvolAt(List<NormalizedBarDto> bars, int index, int period) {
        if (index < period) return insufficient("RVOL20_WARMUP_REQUIRED");
        BigDecimal current = volume(bars.get(index));
        if (current == null) return unverified("VOLUME_BASIS_OR_VALUE_UNVERIFIED");
        BigDecimal sum = BigDecimal.ZERO;
        for (int i=index-period;i<index;i++) {
            BigDecimal value = volume(bars.get(i));
            if (value == null) return unverified("VOLUME_BASIS_OR_VALUE_UNVERIFIED");
            sum = sum.add(value, MC);
        }
        BigDecimal average = sum.divide(BigDecimal.valueOf(period), MC);
        return average.signum()==0 ? unverified("AVERAGE_VOLUME_ZERO") : MetricResult.available(current.divide(average, MC));
    }

    // 用途：比較或計算net 成交量 days之間的關係，供評分、風險或排序規則使用。
    private MetricResult<BigDecimal> netVolumeDays(List<NormalizedBarDto> bars) {
        if (bars.size() < 40) return insufficient("VOLUME_SIGNAL_WARMUP_REQUIRED");
        int net=0;
        for (int i=bars.size()-20;i<bars.size();i++) {
            MetricResult<BigDecimal> r = rvolAt(bars,i,20);
            if (r.status()!=MetricStatus.AVAILABLE) return MetricResult.unavailable(r.status(),r.reasons(),r.evidenceRefs());
            if (r.value().compareTo(RVOL_15)<0) continue;
            int direction=bars.get(i).close().compareTo(bars.get(i-1).close());
            if(direction>0)net++; else if(direction<0)net--;
        }
        return MetricResult.available(BigDecimal.valueOf(net));
    }

    // 用途：處理breakout 成交量的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BreakoutDirection breakoutVolume(List<NormalizedBarDto> bars, PriceStructureDto structure,
                                             MetricResult<BigDecimal> currentRvol) {
        if (currentRvol.status() != MetricStatus.AVAILABLE || currentRvol.value().compareTo(RVOL_15) < 0
                || structure == null) return BreakoutDirection.NONE;
        LocalDate last = bars.get(bars.size() - 1).sessionDate();
        boolean up = false;
        boolean down = false;
        for (TimeframeStructureDto frame : structure.timeframes()) {
            if (frame.timeframe() != AnalysisTimeframe.DAILY) continue;
            for (StructureEventDto event : frame.structureEvents()) {
                if (!last.equals(event.sessionDate())) continue;
                if ("STRUCTURE_HIGH_BREACH".equals(event.code())) up = true;
                if ("STRUCTURE_LOW_BREACH".equals(event.code())) down = true;
            }
        }
        if (up && down) return BreakoutDirection.CONFLICT;
        if (up) return BreakoutDirection.BULLISH;
        if (down) return BreakoutDirection.BEARISH;
        return BreakoutDirection.NONE;
    }

    /** 放量上破與放量下破必須使用相反方向；互相矛盾的證據不得加分。 */
    private MetricResult<BigDecimal> primarySignal(BreakoutDirection breakout, Divergence divergence,
                                                    MetricResult<BigDecimal> netDays) {
        if (breakout == BreakoutDirection.CONFLICT || divergence == Divergence.CONFLICT) {
            return MetricResult.available(BigDecimal.ZERO);
        }
        boolean bullish = breakout == BreakoutDirection.BULLISH || divergence == Divergence.BULLISH;
        boolean bearish = breakout == BreakoutDirection.BEARISH || divergence == Divergence.BEARISH;
        if (bullish && bearish) return MetricResult.available(BigDecimal.ZERO);
        if (bullish) return MetricResult.available(BigDecimal.ONE);
        if (bearish) return MetricResult.available(BigDecimal.ONE.negate());
        if (netDays.status() != MetricStatus.AVAILABLE)
            return MetricResult.unavailable(netDays.status(), netDays.reasons(), netDays.evidenceRefs());
        if (netDays.value().compareTo(BigDecimal.valueOf(3)) >= 0) return MetricResult.available(new BigDecimal("0.5"));
        if (netDays.value().compareTo(BigDecimal.valueOf(-3)) <= 0) return MetricResult.available(new BigDecimal("-0.5"));
        return MetricResult.available(BigDecimal.ZERO);
    }

    // 用途：處理obv series的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private List<BigDecimal> obvSeries(List<NormalizedBarDto> bars) {
        if (bars.isEmpty()) return List.of();
        for (NormalizedBarDto bar:bars) if(volume(bar)==null) return List.of();
        List<BigDecimal> out=new ArrayList<>(); BigDecimal obv=BigDecimal.ZERO; out.add(obv);
        for(int i=1;i<bars.size();i++) {
            int c=bars.get(i).close().compareTo(bars.get(i-1).close());
            if(c>0)obv=obv.add(volume(bars.get(i)),MC); else if(c<0)obv=obv.subtract(volume(bars.get(i)),MC);
            out.add(obv);
        }
        return List.copyOf(out);
    }

    // 用途：處理divergence的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private Divergence divergence(List<NormalizedBarDto> bars, PriceStructureDto structure, List<BigDecimal> obv) {
        if (structure==null || obv.size()!=bars.size()) return Divergence.NONE;
        TimeframeStructureDto daily=structure.timeframes().stream().filter(t->t.timeframe()==AnalysisTimeframe.DAILY).findFirst().orElse(null);
        if(daily==null)return Divergence.NONE;
        Divergence bullish=comparePivots(bars,obv,daily.minorPivots(),PivotKind.LOW);
        Divergence bearish=comparePivots(bars,obv,daily.minorPivots(),PivotKind.HIGH);
        if(bullish==Divergence.BULLISH && bearish==Divergence.BEARISH)return Divergence.CONFLICT;
        return bullish!=Divergence.NONE?bullish:bearish;
    }

    // 用途：比較或計算pivots之間的關係，供評分、風險或排序規則使用。
    private Divergence comparePivots(List<NormalizedBarDto> bars,List<BigDecimal> obv,List<ConfirmedPivotDto> pivots,PivotKind kind) {
        List<ConfirmedPivotDto> same=pivots.stream().filter(p->p.kind()==kind).sorted(Comparator.comparing(ConfirmedPivotDto::occurrenceSession)).toList();
        if(same.size()<2)return Divergence.NONE;
        ConfirmedPivotDto a=same.get(same.size()-2),b=same.get(same.size()-1);
        int ia=indexOf(bars,a.occurrenceSession()),ib=indexOf(bars,b.occurrenceSession());
        if(ia<0||ib<0||ib-ia<5)return Divergence.NONE;
        BigDecimal atr=b.atrAtOccurrence()!=null?b.atrAtOccurrence():a.atrAtOccurrence(); if(atr==null)return Divergence.NONE;
        BigDecimal priceDiff=b.price().subtract(a.price(),MC).abs();
        if(priceDiff.compareTo(atr.multiply(new BigDecimal("0.25"),MC))<=0)return Divergence.NONE;
        BigDecimal avg=averagePriorVolume(bars,ib,20); if(avg==null)return Divergence.NONE;
        BigDecimal obvDiff=obv.get(ib).subtract(obv.get(ia),MC);
        if(kind==PivotKind.LOW && b.price().compareTo(a.price())<0 && obvDiff.compareTo(avg)>0)return Divergence.BULLISH;
        if(kind==PivotKind.HIGH && b.price().compareTo(a.price())>0 && obvDiff.compareTo(avg.negate())<0)return Divergence.BEARISH;
        return Divergence.NONE;
    }

    // 用途：處理average prior 成交量的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal averagePriorVolume(List<NormalizedBarDto> bars,int index,int n) {
        if(index<n)return null; BigDecimal sum=BigDecimal.ZERO;
        for(int i=index-n;i<index;i++){BigDecimal v=volume(bars.get(i));if(v==null)return null;sum=sum.add(v,MC);}return sum.divide(BigDecimal.valueOf(n),MC);
    }

    // 用途：處理成交量 分布的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<VolumeProfileDto> volumeProfile(List<NormalizedBarDto> all) {
        if(all.size()<252)return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of("VOLUME_PROFILE_252_REQUIRED","VOLUME","近似成交分布需要252根完整日線。"));
        List<NormalizedBarDto> bars=all.subList(all.size()-252,all.size());
        BigDecimal low=bars.stream().map(NormalizedBarDto::low).min(BigDecimal::compareTo).orElse(null), high=bars.stream().map(NormalizedBarDto::high).max(BigDecimal::compareTo).orElse(null);
        if(low==null||high==null||high.compareTo(low)<=0)return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,AnalysisReasonDto.of("VOLUME_PROFILE_RANGE_ZERO","VOLUME","價格範圍不可建立。"));
        for(NormalizedBarDto b:bars)if(volume(b)==null)return unverifiedProfile("VOLUME_BASIS_OR_VALUE_UNVERIFIED");
        int count=50; BigDecimal width=high.subtract(low,MC).divide(BigDecimal.valueOf(count),MC); BigDecimal[] volumes=new BigDecimal[count];Arrays.fill(volumes,BigDecimal.ZERO);
        for(NormalizedBarDto b:bars){BigDecimal v=volume(b),range=b.high().subtract(b.low(),MC);if(range.signum()==0){volumes[binIndex(b.close(),low,width,count)]=volumes[binIndex(b.close(),low,width,count)].add(v,MC);continue;}for(int i=0;i<count;i++){BigDecimal bl=low.add(width.multiply(BigDecimal.valueOf(i),MC),MC),bu=i==count-1?high:bl.add(width,MC);BigDecimal overlap=b.high().min(bu).subtract(b.low().max(bl),MC);if(overlap.signum()>0)volumes[i]=volumes[i].add(v.multiply(overlap,MC).divide(range,MC),MC);}}
        List<VolumeBinDto> bins=new ArrayList<>();for(int i=0;i<count;i++){BigDecimal bl=low.add(width.multiply(BigDecimal.valueOf(i),MC),MC),bu=i==count-1?high:bl.add(width,MC);bins.add(new VolumeBinDto(i,bl,bu,volumes[i]));}
        int poc=0;for(int i=1;i<count;i++)if(volumes[i].compareTo(volumes[poc])>0)poc=i;
        List<BigDecimal> sorted=Arrays.stream(volumes).sorted().toList();BigDecimal q25=quantileType7(sorted,new BigDecimal("0.25")),q75=quantileType7(sorted,new BigDecimal("0.75"));
        List<VolumeBinDto> hvn=new ArrayList<>(),lvn=new ArrayList<>();for(int i=0;i<count;i++){BigDecimal left=i==0?null:volumes[i-1],right=i==count-1?null:volumes[i+1];boolean max=(left==null||volumes[i].compareTo(left)>=0)&&(right==null||volumes[i].compareTo(right)>=0);boolean min=(left==null||volumes[i].compareTo(left)<=0)&&(right==null||volumes[i].compareTo(right)<=0);if(max&&volumes[i].compareTo(q75)>=0&&!samePlateauEarlier(volumes,i,true))hvn.add(bins.get(i));if(min&&volumes[i].compareTo(q25)<=0&&!samePlateauEarlier(volumes,i,false))lvn.add(bins.get(i));}
        return MetricResult.available(new VolumeProfileDto(bins,bins.get(poc),hvn,lvn,"DAILY_OHLCV_APPROXIMATION"));
    }

    // 用途：處理same plateau earlier的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private boolean samePlateauEarlier(BigDecimal[] x,int i,boolean ignored){return i>0&&x[i].compareTo(x[i-1])==0;}
    // 用途：處理bin index的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private int binIndex(BigDecimal p,BigDecimal low,BigDecimal width,int count){int i=p.subtract(low,MC).divide(width,0,RoundingMode.FLOOR).intValue();return Math.max(0,Math.min(count-1,i));}
    // 用途：處理quantile type 7的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal quantileType7(List<BigDecimal>x,BigDecimal p){if(x.isEmpty())return null;if(x.size()==1)return x.get(0);BigDecimal h=BigDecimal.valueOf(x.size()-1L).multiply(p,MC);int j=h.setScale(0,RoundingMode.FLOOR).intValue();BigDecimal f=h.subtract(BigDecimal.valueOf(j),MC);return j>=x.size()-1?x.get(x.size()-1):x.get(j).add(x.get(j+1).subtract(x.get(j),MC).multiply(f,MC),MC);}

    // 用途：處理anchored VWAP的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<BigDecimal> anchoredVwap(List<NormalizedBarDto> bars,PriceStructureDto structure){if(structure==null)return insufficient("AVWAP_MAJOR_PIVOT_REQUIRED");TimeframeStructureDto daily=structure.timeframes().stream().filter(t->t.timeframe()==AnalysisTimeframe.DAILY).findFirst().orElse(null);if(daily==null||daily.majorPivots().isEmpty())return insufficient("AVWAP_MAJOR_PIVOT_REQUIRED");ConfirmedPivotDto pivot=daily.majorPivots().stream().filter(p->p.confirmationTime()!=null).max(Comparator.comparing(ConfirmedPivotDto::confirmationTime)).orElse(null);if(pivot==null)return insufficient("AVWAP_MAJOR_PIVOT_REQUIRED");int start=indexOf(bars,pivot.occurrenceSession());if(start<0)return insufficient("AVWAP_ANCHOR_OUTSIDE_WINDOW");BigDecimal pv=BigDecimal.ZERO,vsum=BigDecimal.ZERO;for(int i=start;i<bars.size();i++){BigDecimal v=volume(bars.get(i));if(v==null)return unverified("VOLUME_BASIS_OR_VALUE_UNVERIFIED");BigDecimal typical=bars.get(i).high().add(bars.get(i).low(),MC).add(bars.get(i).close(),MC).divide(BigDecimal.valueOf(3),MC);pv=pv.add(typical.multiply(v,MC),MC);vsum=vsum.add(v,MC);}return vsum.signum()==0?unverified("AVWAP_VOLUME_ZERO"):MetricResult.available(pv.divide(vsum,MC));}
    // 用途：處理of的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private int indexOf(List<NormalizedBarDto>b,LocalDate d){for(int i=0;i<b.size();i++)if(d.equals(b.get(i).sessionDate()))return i;return -1;}
    // 用途：處理成交量的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal volume(NormalizedBarDto b){return b!=null&&b.volume()!=null&&b.volume().status()==MetricStatus.AVAILABLE&&b.volume().value()!=null&&b.volume().value().signum()>=0?b.volume().value():null;}
    // 用途：處理證據的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private List<String> evidence(PriceStructureDto s){return s==null?List.of():s.evidenceRefs();}
    // 用途：建立結果不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private VolumeAnalysisDto unavailableResult(String c){MetricResult<BigDecimal>x=insufficient(c);return new VolumeAnalysisDto(x,x,x,x,MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of(c,"VOLUME","成交量輸入不足。")),x,x.reasons(),List.of());}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<BigDecimal> insufficient(String c){return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of(c,"VOLUME","成交量窗口不足。"));}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<BigDecimal> unverified(String c){return MetricResult.unavailable(MetricStatus.UNVERIFIED,AnalysisReasonDto.of(c,"VOLUME","成交量輸入未核驗。"));}
    // 用途：建立分布不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<VolumeProfileDto> unverifiedProfile(String c){return MetricResult.unavailable(MetricStatus.UNVERIFIED,AnalysisReasonDto.of(c,"VOLUME","成交量輸入未核驗。"));}
    private enum BreakoutDirection { NONE, BULLISH, BEARISH, CONFLICT }
    private enum Divergence{NONE,BULLISH,BEARISH,CONFLICT}
}
