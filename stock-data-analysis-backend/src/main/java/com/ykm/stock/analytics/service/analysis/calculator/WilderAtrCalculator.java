package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;
import java.math.*; import java.util.*;

@Component
// 用途：負責「WilderATR計算」的純計算或判定邏輯，避免上層服務重複實作相同規則。
public class WilderAtrCalculator {
    private static final MathContext MC=MathContext.DECIMAL128;
    // 用途：計算相關資料，作為「WilderATR計算」流程中的處理步驟。
    public List<AtrPointDto> calculate(List<NormalizedBarDto> bars, int period) {
        if (period<=0 || bars.size()<period+1) return List.of();
        List<BigDecimal> tr=new ArrayList<>();
        tr.add(null);
        for(int i=1;i<bars.size();i++){
            NormalizedBarDto b=bars.get(i), p=bars.get(i-1);
            BigDecimal a=b.high().subtract(b.low(),MC).abs();
            BigDecimal x=b.high().subtract(p.close(),MC).abs();
            BigDecimal y=b.low().subtract(p.close(),MC).abs();
            tr.add(a.max(x).max(y));
        }
        BigDecimal seed=BigDecimal.ZERO;
        for(int i=1;i<=period;i++) seed=seed.add(tr.get(i),MC);
        BigDecimal atr=seed.divide(BigDecimal.valueOf(period),MC);
        List<AtrPointDto> out=new ArrayList<>();
        for(int i=period;i<bars.size();i++){
            if(i>period) atr=atr.multiply(BigDecimal.valueOf(period-1),MC).add(tr.get(i),MC).divide(BigDecimal.valueOf(period),MC);
            BigDecimal pct=bars.get(i).close().signum()==0?null:atr.divide(bars.get(i).close(),MC).multiply(BigDecimal.valueOf(100),MC);
            out.add(new AtrPointDto(bars.get(i).sessionDate(),period,atr,pct,bars.get(period).sessionDate(),period,i-period));
        }
        return List.copyOf(out);
    }
}
