package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;
import java.math.BigDecimal; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.time.Instant; import java.util.*;

@Component
// 用途：負責「已確認波段偵測」的純計算或判定邏輯，避免上層服務重複實作相同規則。
public class ConfirmedSwingDetector {
    // 用途：偵測相關資料，作為「已確認波段偵測」流程中的處理步驟。
    public List<ConfirmedPivotDto> detect(String figi, AnalysisTimeframe timeframe, List<NormalizedBarDto> bars, List<AtrPointDto> atr, PivotScale scale) {
        int n=scale==PivotScale.MINOR?2:5;
        if(bars.size()<2*n+1) return List.of();
        Map<java.time.LocalDate,BigDecimal> atrByDate=new HashMap<>(); for(AtrPointDto a:atr) atrByDate.put(a.sessionDate(),a.atr());
        List<ConfirmedPivotDto> out=new ArrayList<>();
        for(int i=n;i<bars.size()-n;i++){
            NormalizedBarDto candidate=bars.get(i);
            boolean high=true, low=true;
            for(int j=i-n;j<=i+n;j++) if(j!=i){ high &= candidate.high().compareTo(bars.get(j).high())>0; low &= candidate.low().compareTo(bars.get(j).low())<0; }
            if(high) out.add(pivot(figi,timeframe,bars,i,n,atrByDate,scale,PivotKind.HIGH,candidate.high()));
            if(low) out.add(pivot(figi,timeframe,bars,i,n,atrByDate,scale,PivotKind.LOW,candidate.low()));
        }
        out.sort(Comparator.comparing(ConfirmedPivotDto::occurrenceSession).thenComparing(p->p.kind().name()).thenComparing(ConfirmedPivotDto::id));
        return List.copyOf(out);
    }
    // 用途：處理pivot，作為「已確認波段偵測」流程中的處理步驟。
    private ConfirmedPivotDto pivot(String figi, AnalysisTimeframe tf,List<NormalizedBarDto> bars,int i,int n,Map<java.time.LocalDate,BigDecimal> atr, PivotScale scale,PivotKind kind,BigDecimal price){
        NormalizedBarDto b=bars.get(i); List<NormalizedBarDto> window=bars.subList(i-n,i+n+1);
        Instant confirmed=window.stream().map(NormalizedBarDto::availableAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(b.availableAt());
        Instant observed=window.stream().map(NormalizedBarDto::observedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        String dep="STRUCTURE:"+figi+":"+tf+":"+b.sessionDate()+":"+kind;
        String raw=figi+"|"+tf+"|"+kind+"|"+scale+"|"+b.sessionDate()+"|"+b.versionRef();
        return new ConfirmedPivotDto(hash(raw),kind,scale,tf,price,b.sessionDate(),b.sessionCloseAt(),confirmed,observed,atr.get(b.sessionDate()),atr.get(bars.get(i+n).sessionDate()),window.stream().map(NormalizedBarDto::versionRef).filter(Objects::nonNull).toList(),dep);
    }
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    });
    // 用途：判斷h，作為「已確認波段偵測」流程中的處理步驟。
    private String hash(String s){ MessageDigest d=SHA256.get(); d.reset(); return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8))); }
}
