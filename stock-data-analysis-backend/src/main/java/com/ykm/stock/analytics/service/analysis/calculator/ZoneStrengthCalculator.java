package com.ykm.stock.analytics.service.analysis.calculator;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*; import org.springframework.stereotype.Component; import java.math.*;
// 用途：負責「區域強度計算」的純計算或判定邏輯，避免上層服務重複實作相同規則。
@Component public class ZoneStrengthCalculator {
 private static final MathContext MC=MathContext.DECIMAL128;
 // 用途：處理原始，作為「區域強度計算」流程中的處理步驟。
 public BigDecimal raw(boolean major, AnalysisTimeframe tf,int rejections,int flips,int tests,int penetrations){
  double v=(major?20:10)+(tf==AnalysisTimeframe.WEEKLY?10:tf==AnalysisTimeframe.MONTHLY?15:0)+Math.min(30,rejections*10)+(flips>0?10:0)-Math.min(20,Math.max(0,tests-3)*5)-Math.min(20,penetrations*5); return BigDecimal.valueOf(Math.max(0,Math.min(100,v)));
 }
 // 用途：處理decayed，作為「區域強度計算」流程中的處理步驟。
 public BigDecimal decayed(BigDecimal raw,AnalysisTimeframe tf,int ageBars){ double half=tf==AnalysisTimeframe.DAILY?126:tf==AnalysisTimeframe.WEEKLY?26:12; return raw.multiply(BigDecimal.valueOf(Math.pow(2.0,-ageBars/half)),MC); }
 // 用途：處理label，作為「區域強度計算」流程中的處理步驟。
 public ZoneStrengthLabel label(BigDecimal v){ return v.compareTo(BigDecimal.valueOf(60))>=0?ZoneStrengthLabel.HIGH:v.compareTo(BigDecimal.valueOf(30))>=0?ZoneStrengthLabel.MEDIUM:ZoneStrengthLabel.LOW; }
}
