package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.time.Instant; import java.util.List; import java.util.Map;
public record ZoneEventDto(String id,String zoneId,String type,Instant startAt,Instant confirmedAt,int boundsVersion,String episodeId,Map<String,Object> metrics,List<String> evidenceRefs){
 public ZoneEventDto { metrics=metrics==null?Map.of():Map.copyOf(metrics); evidenceRefs=evidenceRefs==null?List.of():List.copyOf(evidenceRefs); }
}
