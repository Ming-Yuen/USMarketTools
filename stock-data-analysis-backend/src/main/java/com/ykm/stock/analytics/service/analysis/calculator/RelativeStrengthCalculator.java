package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Component
/** 負責比較個股與基準資產的期間表現，產生相對強弱證據與資料品質狀態。 */
public class RelativeStrengthCalculator {
    private static final MathContext MC= MathContext.DECIMAL128;

    // 用途：根據輸入資料計算相關資料，產生可供分析或交易決策使用的標準結果。
    public RelativeStrengthDto calculate(List<NormalizedBarDto> stock, List<NormalizedBarDto> benchmark, String benchmarkCode) {
        Map<Integer, MetricResult<RelativeStrengthWindowDto>> windows=new LinkedHashMap<>();
        for(int n:List.of(20,60,120)) windows.put(n,window(stock,benchmark,n));
        MetricResult<BigDecimal> e20=diff(windows.get(20)),e60=diff(windows.get(60)),e120=diff(windows.get(120));
        MetricResult<BigDecimal> signal=e60.status()==MetricStatus.AVAILABLE
                ? MetricResult.available(e60.value().divide(new BigDecimal("0.05"),MC).max(BigDecimal.ONE.negate()).min(BigDecimal.ONE))
                : MetricResult.unavailable(e60.status(),e60.reasons(),e60.evidenceRefs());
        return new RelativeStrengthDto(benchmarkCode,e20,e60,e120,windows,signal,List.of());
    }

    // 用途：處理window的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    private MetricResult<RelativeStrengthWindowDto> window(List<NormalizedBarDto> stock,List<NormalizedBarDto> benchmark,int n) {
        if(stock==null||benchmark==null||stock.size()<=n)return insufficient(n,"STOCK_WINDOW_REQUIRED");
        Map<LocalDate,NormalizedBarDto> bench=new HashMap<>();
        for(NormalizedBarDto b:benchmark) if(b!=null&&b.sessionDate()!=null) bench.put(b.sessionDate(),b);
        List<NormalizedBarDto> required=stock.subList(stock.size()-1-n,stock.size());
        for(NormalizedBarDto s:required){NormalizedBarDto b=bench.get(s.sessionDate());if(b==null)return insufficient(n,"BENCHMARK_SESSION_MISSING");if(!compatible(s,b))return unverified(n,"BENCHMARK_BASIS_OR_SESSION_UNVERIFIED");}
        NormalizedBarDto s0=required.get(0),s1=required.get(required.size()-1),b0=bench.get(s0.sessionDate()),b1=bench.get(s1.sessionDate());
        if(s0.close().signum()<=0||b0.close().signum()<=0)return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,AnalysisReasonDto.of("RELATIVE_STRENGTH_BASE_ZERO","RELATIVE_STRENGTH","相對強弱基準價格非正。"));
        BigDecimal sr=s1.close().divide(s0.close(),MC).subtract(BigDecimal.ONE,MC),br=b1.close().divide(b0.close(),MC).subtract(BigDecimal.ONE,MC);
        BigDecimal diff=sr.subtract(br,MC),ratio=BigDecimal.ONE.add(sr,MC).divide(BigDecimal.ONE.add(br,MC),MC).subtract(BigDecimal.ONE,MC);
        return MetricResult.available(new RelativeStrengthWindowDto(n,sr,br,diff,ratio));
    }
    // 用途：判斷compatible是否符合指定條件，供上層流程決定是否繼續使用該資料。
    private boolean compatible(NormalizedBarDto a,NormalizedBarDto b){return a.close()!=null&&b.close()!=null&&a.close().signum()>0&&b.close().signum()>0&&a.sessionCloseAt()!=null&&b.sessionCloseAt()!=null&&Objects.equals(a.sessionDate(),b.sessionDate());}
    // 用途：比較或計算diff之間的關係，供評分、風險或排序規則使用。
    private MetricResult<BigDecimal> diff(MetricResult<RelativeStrengthWindowDto> r){return r.status()==MetricStatus.AVAILABLE?MetricResult.available(r.value().returnDifference()):MetricResult.unavailable(r.status(),r.reasons(),r.evidenceRefs());}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<RelativeStrengthWindowDto> insufficient(int n,String c){return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,AnalysisReasonDto.of(c,"RELATIVE_STRENGTH",n+"日相對強弱需要完整同日session。"));}
    // 用途：建立相關資料不可用時的標準回傳結果，讓上層流程保留明確資料品質狀態。
    private MetricResult<RelativeStrengthWindowDto> unverified(int n,String c){return MetricResult.unavailable(MetricStatus.UNVERIFIED,AnalysisReasonDto.of(c,"RELATIVE_STRENGTH",n+"日相對強弱輸入未核驗。"));}
}
