package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;
import java.math.*; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.time.Instant; import java.util.*;

@Component
// 用途：負責「ATR區域聚類」的純計算或判定邏輯，避免上層服務重複實作相同規則。
public class AtrZoneClusterer {
    private static final MathContext MC=MathContext.DECIMAL128;
    // 用途：聚類相關資料，作為「ATR區域聚類」流程中的處理步驟。
    public List<ZoneCandidate> cluster(String figi, AnalysisTimeframe timeframe, List<ConfirmedPivotDto> pivots) {
        List<ConfirmedPivotDto> seeds=pivots.stream().filter(p->p.atrAtConfirmation()!=null&&p.atrAtConfirmation().signum()>0)
                .sorted(Comparator.comparing(ConfirmedPivotDto::confirmationTime).thenComparing(ConfirmedPivotDto::occurrenceSession)
                        .thenComparing(p->p.kind().name()).thenComparing(p->p.scale().name()).thenComparing(ConfirmedPivotDto::id)).toList();
        List<ZoneCandidate> zones=new ArrayList<>();
        Map<String,ZoneCandidate> logicalSeedZone=new HashMap<>();
        for(ConfirmedPivotDto seed:seeds){
            String logicalKey=seed.occurrenceSession()+"|"+seed.kind();
            ZoneCandidate existingSeedZone=logicalSeedZone.get(logicalKey);
            if(existingSeedZone!=null){
                if(seed.scale()==PivotScale.MAJOR && !existingSeedZone.major){
                    existingSeedZone.major=true;
                    if(!existingSeedZone.seedIds.contains(seed.id())) existingSeedZone.seedIds.add(seed.id());
                    if(seed.dependenceKey()!=null && !existingSeedZone.dependenceKeys.contains(seed.dependenceKey())) existingSeedZone.dependenceKeys.add(seed.dependenceKey());
                    existingSeedZone.recordEvent("MAJOR_SEED_UPGRADE",seed.confirmationTime(),seed.confirmationTime(),null,
                            Map.of("pivotId",seed.id()),List.of(seed.id()));
                }
                continue;
            }
            ZoneRole role=seed.kind()==PivotKind.LOW?ZoneRole.SUPPORT:ZoneRole.RESISTANCE;
            ZoneCandidate best=null; BigDecimal bestDistance=null;
            for(ZoneCandidate z:zones){
                if(z.role!=role || z.state!=ZoneState.ACTIVE) continue;
                BigDecimal distance=seed.price().subtract(z.center,MC).abs();
                if(distance.compareTo(z.anchorAtr.multiply(new BigDecimal("0.50"),MC))>0) continue;
                List<BigDecimal> candidate=new ArrayList<>(z.seedPrices); candidate.add(seed.price());
                BigDecimal min=candidate.stream().min(BigDecimal::compareTo).orElseThrow(), max=candidate.stream().max(BigDecimal::compareTo).orElseThrow();
                if(max.subtract(min,MC).compareTo(z.anchorAtr)>0) continue;
                BigDecimal normalized=distance.divide(z.anchorAtr,MC);
                if(best==null || normalized.compareTo(bestDistance)<0 || normalized.compareTo(bestDistance)==0 && z.id.compareTo(best.id)<0){ best=z; bestDistance=normalized; }
            }
            if(best==null){
                BigDecimal half=BigDecimal.valueOf(0.01).max(seed.atrAtConfirmation().multiply(new BigDecimal("0.25"),MC));
                ZoneCandidate z=new ZoneCandidate();
                z.id=hash(figi+"|"+timeframe+"|"+seed.id()); z.timeframe=timeframe; z.role=role; z.center=seed.price(); z.anchorAtr=seed.atrAtConfirmation(); z.halfWidth=half; z.lower=z.center.subtract(half,MC); z.upper=z.center.add(half,MC); z.confirmedAt=seed.confirmationTime(); z.roleConfirmedAt=seed.confirmationTime(); z.seedPrices.add(seed.price()); z.seedIds.add(seed.id()); if(seed.dependenceKey()!=null)z.dependenceKeys.add(seed.dependenceKey()); z.major=seed.scale()==PivotScale.MAJOR;
                z.recordEvent("ZONE_CONFIRMED",seed.confirmationTime(),seed.confirmationTime(),null,Map.of("pivotId",seed.id()),List.of(seed.id()));
                zones.add(z); best=z;
            } else if(!best.seedIds.contains(seed.id())){
                // A later-confirmed pivot may strengthen an existing zone, but it must not
                // rewrite the geometry that earlier bars were evaluated against. Keeping the
                // original confirmed bounds removes look-ahead contamination from replay.
                best.seedIds.add(seed.id());
                best.seedPrices.add(seed.price());
                if(seed.dependenceKey()!=null&&!best.dependenceKeys.contains(seed.dependenceKey()))best.dependenceKeys.add(seed.dependenceKey());
                best.major|=seed.scale()==PivotScale.MAJOR;
                best.recordEvent("SEED_ADDED",seed.confirmationTime(),seed.confirmationTime(),null,
                        Map.of("pivotId",seed.id(),"center",best.center,"lower",best.lower,"upper",best.upper),List.of(seed.id()));
            }
            logicalSeedZone.put(logicalKey,best);
        }
        zones.removeIf(z->z.lower.signum()<=0);
        return zones;
    }
    // 用途：處理median，作為「ATR區域聚類」流程中的處理步驟。
    private BigDecimal median(List<BigDecimal> xs){ List<BigDecimal> s=new ArrayList<>(new LinkedHashSet<>(xs)); s.sort(BigDecimal::compareTo); int n=s.size(); return n%2==1?s.get(n/2):s.get(n/2-1).add(s.get(n/2),MC).divide(BigDecimal.valueOf(2),MC); }
    // 用途：判斷h，作為「ATR區域聚類」流程中的處理步驟。
    private String hash(String s){try{MessageDigest d=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    // 用途：負責「區域Candidate」相關的業務協調與處理，集中管理此領域的服務邏輯。
    public static final class ZoneCandidate {
        public String id; public AnalysisTimeframe timeframe; public ZoneRole role; public BigDecimal center,anchorAtr,halfWidth,lower,upper,roleConfirmationPrice; public Instant confirmedAt,roleConfirmedAt,lastTestAt,breakConfirmedAt; public ZoneState state=ZoneState.ACTIVE; public RetestState retestState=RetestState.NONE; public int boundsVersion=1,roleVersion=1,testCount,rejectionCount,penetrationCount,ageBars; public boolean major; public final List<BigDecimal> seedPrices=new ArrayList<>(); public final List<String> seedIds=new ArrayList<>(); public final List<String> dependenceKeys=new ArrayList<>(); public final List<ZoneEventDto> events=new ArrayList<>();
        // 用途：處理記錄事件，作為「區域Candidate」流程中的處理步驟。
        public void recordEvent(String type, Instant startAt, Instant confirmedAt, String episodeId, Map<String,Object> metrics, List<String> evidenceRefs) {
            String raw=id+"|"+type+"|"+startAt+"|"+confirmedAt+"|"+boundsVersion+"|"+episodeId;
            events.add(new ZoneEventDto(eventHash(raw),id,type,startAt,confirmedAt,boundsVersion,episodeId,metrics,evidenceRefs));
        }
        // 用途：處理事件Hash，作為「區域Candidate」流程中的處理步驟。
        private static String eventHash(String value){try{MessageDigest d=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(d.digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    }
}
