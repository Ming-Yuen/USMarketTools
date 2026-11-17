package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Component
/** 負責由歷史行情計算回撤幅度、主要高點與回撤區段，供風險與市場狀態分析使用。 */
public class DrawdownCalculator {
    private static final MathContext MC= MathContext.DECIMAL128;
    private static final BigDecimal CORRECTION=new BigDecimal("-0.10");

    // 用途：根據輸入資料計算相關資料，產生可供分析或交易決策使用的標準結果。
    public DrawdownAnalysisDto calculate(List<NormalizedBarDto> bars, PriceStructureDto structure) {
        if(bars==null||bars.isEmpty())return new DrawdownAnalysisDto(insufficient("DRAWDOWN_PRICE_REQUIRED"),insufficient("DRAWDOWN_252_REQUIRED"),insufficient("MAJOR_HIGH_REQUIRED"),insufficient("DRAWDOWN_PRICE_REQUIRED"),insufficient("CORRECTION_EPISODES_REQUIRED"),List.of(),null,List.of());
        BigDecimal current=bars.get(bars.size()-1).close();
        BigDecimal historyHigh=bars.stream().map(NormalizedBarDto::high).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
        MetricResult<BigDecimal> available=ratio(current,historyHigh,"AVAILABLE_HISTORY_HIGH_REQUIRED");
        MetricResult<BigDecimal> dd252=bars.size()<252?insufficient("DRAWDOWN_252_REQUIRED"):ratio(current,bars.subList(bars.size()-252,bars.size()).stream().map(NormalizedBarDto::high).max(BigDecimal::compareTo).orElse(null),"DRAWDOWN_252_HIGH_REQUIRED");
        BigDecimal majorHigh=latestMajorHigh(structure);
        MetricResult<BigDecimal> major=ratio(current,majorHigh,"MAJOR_HIGH_REQUIRED");
        EpisodeResult episodes=episodes(bars);
        MetricResult<BigDecimal> max=episodes.maximumDrawdown==null?insufficient("DRAWDOWN_PRICE_REQUIRED"):MetricResult.available(episodes.maximumDrawdown);
        MetricResult<BigDecimal> median=episodes.completed.size()<5?insufficient("CORRECTION_EPISODES_REQUIRED"):MetricResult.available(median(episodes.completed.stream().map(CorrectionEpisodeDto::troughDrawdown).toList()));
        return new DrawdownAnalysisDto(available,dd252,major,max,median,episodes.completed,episodes.current,List.of());
    }

    // 用途：處理major high的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal latestMajorHigh(PriceStructureDto s){if(s==null)return null;return s.timeframes().stream().filter(t->t.timeframe()==AnalysisTimeframe.DAILY).flatMap(t->t.majorPivots().stream()).filter(p->p.kind()==PivotKind.HIGH&&p.confirmationTime()!=null).max(Comparator.comparing(ConfirmedPivotDto::confirmationTime)).map(ConfirmedPivotDto::price).orElse(null);}
    // 用途：比較或計算ratio之間的關係，供評分、風險或排序規則使用。
    private MetricResult<BigDecimal> ratio(BigDecimal current,BigDecimal peak,String code){if(current==null||peak==null||peak.signum()<=0)return insufficient(code);return MetricResult.available(current.divide(peak,MC).subtract(BigDecimal.ONE,MC));}

    // 用途：處理episodes的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private EpisodeResult episodes(List<NormalizedBarDto> bars){
        BigDecimal peak=null,maxDd=BigDecimal.ZERO;LocalDate peakDate=null;EpisodeBuilder active=null;List<CorrectionEpisodeDto> done=new ArrayList<>();
        for(NormalizedBarDto b:bars){BigDecimal close=b.close();if(close==null||close.signum()<=0)continue;
            if(peak==null||close.compareTo(peak)>0){
                if(active!=null&&close.compareTo(active.peak)>=0){done.add(active.complete(b.sessionDate()));active=null;}
                peak=close;peakDate=b.sessionDate();continue;
            }
            BigDecimal dd=close.divide(peak,MC).subtract(BigDecimal.ONE,MC);if(dd.compareTo(maxDd)<0)maxDd=dd;
            if(active==null&&dd.compareTo(CORRECTION)<=0)active=new EpisodeBuilder(peak,peakDate,b.sessionDate(),b.sessionDate(),dd);
            else if(active!=null&&dd.compareTo(active.trough)<0){active.trough=dd;active.troughDate=b.sessionDate();}
            if(active!=null&&close.compareTo(active.peak)>=0){done.add(active.complete(b.sessionDate()));active=null;peak=close;peakDate=b.sessionDate();}
        }
        CorrectionEpisodeDto current=active==null?null:active.open();return new EpisodeResult(List.copyOf(done),current,maxDd);
    }
    // 用途：處理median的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal median(List<BigDecimal>x){List<BigDecimal>s=new ArrayList<>(x);s.sort(BigDecimal::compareTo);int n=s.size();return n%2==1?s.get(n/2):s.get(n/2-1).add(s.get(n/2),MC).divide(BigDecimal.valueOf(2),MC);}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<BigDecimal> insufficient(String c){return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of(c,"DRAWDOWN","回撤資料不足。"));}
    private static final class EpisodeBuilder{final BigDecimal peak;final LocalDate peakDate,startDate;LocalDate troughDate;BigDecimal trough;EpisodeBuilder(BigDecimal p,LocalDate pd,LocalDate sd,LocalDate td,BigDecimal t){peak=p;peakDate=pd;startDate=sd;troughDate=td;trough=t;}CorrectionEpisodeDto complete(LocalDate end){return new CorrectionEpisodeDto(peakDate,startDate,troughDate,end,trough,true);}CorrectionEpisodeDto open(){return new CorrectionEpisodeDto(peakDate,startDate,troughDate,null,trough,false);}}
    private record EpisodeResult(List<CorrectionEpisodeDto> completed,CorrectionEpisodeDto current,BigDecimal maximumDrawdown){}
}
