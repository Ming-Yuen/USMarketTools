package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.util.List;

public record SupportResistanceDto(
        List<LevelZoneDto> supports,
        List<LevelZoneDto> resistances,
        List<LevelZoneDto> containingZones,
        List<ZoneOverlapGroupDto> overlapGroups,
        List<ZoneEventDto> events,
        List<AnalysisReasonDto> reasons,
        BreakoutDiagnosisDto breakoutDiagnosis,
        List<BreakoutDiagnosisDto> zoneDiagnoses
) {
    public SupportResistanceDto {
        supports = supports == null ? List.of() : List.copyOf(supports);
        resistances = resistances == null ? List.of() : List.copyOf(resistances);
        containingZones = containingZones == null ? List.of() : List.copyOf(containingZones);
        overlapGroups = overlapGroups == null ? List.of() : List.copyOf(overlapGroups);
        events = events == null ? List.of() : List.copyOf(events);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        zoneDiagnoses = zoneDiagnoses == null ? List.of() : List.copyOf(zoneDiagnoses);
    }

    public SupportResistanceDto(
            List<LevelZoneDto> supports,
            List<LevelZoneDto> resistances,
            List<LevelZoneDto> containingZones,
            List<ZoneOverlapGroupDto> overlapGroups,
            List<ZoneEventDto> events,
            List<AnalysisReasonDto> reasons) {
        this(supports, resistances, containingZones, overlapGroups, events, reasons, null, List.of());
    }
}
