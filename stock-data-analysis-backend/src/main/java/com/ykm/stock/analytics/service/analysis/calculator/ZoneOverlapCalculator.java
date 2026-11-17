package com.ykm.stock.analytics.service.analysis.calculator;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*; import org.springframework.stereotype.Component; import java.math.BigDecimal; import java.util.*;
// 用途：負責「區域重疊計算」的純計算或判定邏輯，避免上層服務重複實作相同規則。
@Component public class ZoneOverlapCalculator {
 // 用途：計算相關資料，作為「區域重疊計算」流程中的處理步驟。
 public List<ZoneOverlapGroupDto> calculate(List<LevelZoneDto> zones){ List<LevelZoneDto> ordered=zones.stream().sorted(Comparator.comparing(LevelZoneDto::lower).thenComparing(LevelZoneDto::id)).toList(); List<ZoneOverlapGroupDto> out=new ArrayList<>(); int i=0; while(i<ordered.size()){ LevelZoneDto first=ordered.get(i); BigDecimal lo=first.lower(),hi=first.upper(); List<String> ids=new ArrayList<>(); ids.add(first.id()); int j=i+1; while(j<ordered.size()){ LevelZoneDto z=ordered.get(j); BigDecimal nlo=lo.max(z.lower()), nhi=hi.min(z.upper()); if(nlo.compareTo(nhi)>0) break; lo=nlo;hi=nhi;ids.add(z.id());j++; } if(ids.size()>1) out.add(new ZoneOverlapGroupDto("OVERLAP:"+ids.get(0),lo,hi,ids)); i=j; } return out; }
}
