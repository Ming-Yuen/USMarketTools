package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Component
/** 負責依均線、斜率、回撤等市場指標判定市場 regime，供情境與風險評估使用。 */
public class MarketRegimeCalculator {
    private static final MathContext MC= MathContext.DECIMAL128;
    public static final int REQUIRED_SESSIONS = 272;

    // 用途：根據輸入資料計算相關資料，產生可供分析或交易決策使用的標準結果。
    public MetricResult<MarketRegime> classify(List<NormalizedBarDto> spy,List<NormalizedBarDto> qqq,MetricResult<BigDecimal> actualVix) {
        Pair aligned=aligned(spy,qqq);
        if(aligned.spy.size()<REQUIRED_SESSIONS)return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of("MARKET_REGIME_272_SESSIONS_REQUIRED","MARKET","SPY/QQQ需至少272個完整同日session。"));
        int last=aligned.spy.size()-1;
        Raw current=raw(aligned.spy,aligned.qqq,last);
        BigDecimal vix=actualVix!=null&&actualVix.status()==MetricStatus.AVAILABLE?actualVix.value():null;
        if(vix!=null&&vix.compareTo(BigDecimal.valueOf(35))>=0&&(current.spyDd.compareTo(new BigDecimal("-0.10"))<=0||current.qqqDd.compareTo(new BigDecimal("-0.10"))<=0))return MetricResult.available(MarketRegime.PANIC);
        boolean recentStress=false;for(int i=Math.max(251,last-20);i<last;i++){Raw r=raw(aligned.spy,aligned.qqq,i);if(r.bear||r.correction){recentStress=true;break;}}
        if(recentStress&&lastThreeAboveMa50(aligned.spy,last)&&lastThreeAboveMa50(aligned.qqq,last)&&slope(aligned.spy,last,50).signum()>0&&slope(aligned.qqq,last,50).signum()>0)return MetricResult.available(MarketRegime.RECOVERY);
        if(current.bear)return MetricResult.available(MarketRegime.BEAR);
        if(current.correction)return MetricResult.available(MarketRegime.CORRECTION);
        if(current.riskOn)return MetricResult.available(MarketRegime.RISK_ON);
        return MetricResult.available(MarketRegime.NEUTRAL);
    }

    // 用途：判斷aligned是否符合指定條件，供上層流程決定是否繼續使用該資料。
    private Pair aligned(List<NormalizedBarDto>a,List<NormalizedBarDto>b){if(a==null||b==null)return new Pair(List.of(),List.of());Map<LocalDate,NormalizedBarDto> bm=new HashMap<>();for(NormalizedBarDto x:b)if(valid(x))bm.put(x.sessionDate(),x);List<NormalizedBarDto> aa=new ArrayList<>(),bb=new ArrayList<>();for(NormalizedBarDto x:a)if(valid(x)&&bm.containsKey(x.sessionDate())){aa.add(x);bb.add(bm.get(x.sessionDate()));}return new Pair(List.copyOf(aa),List.copyOf(bb));}
    // 用途：判斷valid是否符合指定條件，供上層流程決定是否繼續使用該資料。
    private boolean valid(NormalizedBarDto b){return b!=null&&b.sessionDate()!=null&&b.close()!=null&&b.close().signum()>0;}
    // 用途：取得或整理相關資料的內部表示，供本服務其他分析步驟重用。
    private Raw raw(List<NormalizedBarDto>s,List<NormalizedBarDto>q,int i){BigDecimal sc=s.get(i).close(),qc=q.get(i).close(),s50=sma(s,i,50),q50=sma(q,i,50),s200=sma(s,i,200),q200=sma(q,i,200),ss200=slope(s,i,200),qs200=slope(q,i,200),sdd=drawdown252(s,i),qdd=drawdown252(q,i);boolean bear=(sc.compareTo(s200)<0&&qc.compareTo(q200)<0&&ss200.signum()<0&&qs200.signum()<0)||(sdd.compareTo(new BigDecimal("-0.20"))<=0&&qdd.compareTo(new BigDecimal("-0.20"))<=0);boolean correction=sdd.compareTo(new BigDecimal("-0.10"))<=0||qdd.compareTo(new BigDecimal("-0.10"))<=0||(sc.compareTo(s50)<0&&qc.compareTo(q50)<0);boolean risk=sc.compareTo(s50)>0&&s50.compareTo(s200)>0&&qc.compareTo(q50)>0&&q50.compareTo(q200)>0&&ss200.signum()>0&&qs200.signum()>0;return new Raw(bear,correction,risk,sdd,qdd);}
    // 用途：處理SMA的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal sma(List<NormalizedBarDto>x,int end,int n){BigDecimal sum=BigDecimal.ZERO;for(int i=end-n+1;i<=end;i++)sum=sum.add(x.get(i).close(),MC);return sum.divide(BigDecimal.valueOf(n),MC);}
    // 用途：處理slope的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal slope(List<NormalizedBarDto>x,int end,int n){BigDecimal now=sma(x,end,n),old=sma(x,end-5,n);return now.divide(old,MC).subtract(BigDecimal.ONE,MC).multiply(BigDecimal.valueOf(100),MC).divide(BigDecimal.valueOf(5),MC);}
    // 用途：處理回撤 252的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private BigDecimal drawdown252(List<NormalizedBarDto>x,int end){BigDecimal peak=BigDecimal.ZERO;for(int i=end-251;i<=end;i++)peak=peak.max(x.get(i).close());return x.get(end).close().divide(peak,MC).subtract(BigDecimal.ONE,MC);}
    // 用途：處理last three above ma 50的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private boolean lastThreeAboveMa50(List<NormalizedBarDto>x,int end){for(int i=end-2;i<=end;i++)if(x.get(i).close().compareTo(sma(x,i,50))<=0)return false;return true;}
    private record Pair(List<NormalizedBarDto> spy,List<NormalizedBarDto> qqq){}
    private record Raw(boolean bear,boolean correction,boolean riskOn,BigDecimal spyDd,BigDecimal qqqDd){}
}
