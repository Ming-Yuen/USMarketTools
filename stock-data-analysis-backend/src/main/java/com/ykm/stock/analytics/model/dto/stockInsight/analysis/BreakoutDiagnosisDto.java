package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 綁定至特定支撐區域與版本之破位真假診斷證據，比率欄位皆採小數比例口徑。 */
public record BreakoutDiagnosisDto(
        BreakoutDiagnosis diagnosis,
        String zoneId,
        int zoneBoundsVersion,
        int zoneRoleVersion,
        AnalysisTimeframe zoneTimeframe,
        BigDecimal supportLower,
        BigDecimal supportUpper,
        LocalDate breachDate,
        LocalDate reclaimDate,
        LocalDate validThroughSession,
        LocalDate diagnosisDate,
        Integer barsSinceBreach,
        BigDecimal rvolAtBreach,
        BigDecimal rvolAtReclaim,
        BigDecimal rvolLatest,
        BigDecimal lowerShadowRatio,
        BigDecimal bodyRatio,
        BigDecimal closePositionInRange,
        BigDecimal stockReturnOnBreach,
        BigDecimal spyReturnOnBreach,
        BigDecimal excessReturnOnBreach,
        List<String> evidenceCodes,
        List<AnalysisReasonDto> reasons
) {
    public BreakoutDiagnosisDto {
        diagnosis = diagnosis == null ? BreakoutDiagnosis.NONE : diagnosis;
        evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public BreakoutDiagnosisDto(
            BreakoutDiagnosis diagnosis, String zoneId, int zoneBoundsVersion, int zoneRoleVersion,
            AnalysisTimeframe zoneTimeframe, BigDecimal supportLower, BigDecimal supportUpper,
            LocalDate breachDate, LocalDate reclaimDate, LocalDate validThroughSession, LocalDate diagnosisDate,
            Integer barsSinceBreach, BigDecimal rvolAtBreach, BigDecimal rvolLatest,
            BigDecimal lowerShadowRatio, BigDecimal bodyRatio, BigDecimal closePositionInRange,
            BigDecimal stockReturnOnBreach, BigDecimal spyReturnOnBreach, BigDecimal excessReturnOnBreach,
            List<String> evidenceCodes, List<AnalysisReasonDto> reasons) {
        this(diagnosis, zoneId, zoneBoundsVersion, zoneRoleVersion, zoneTimeframe, supportLower, supportUpper,
                breachDate, reclaimDate, validThroughSession, diagnosisDate, barsSinceBreach,
                rvolAtBreach, null, rvolLatest, lowerShadowRatio, bodyRatio, closePositionInRange,
                stockReturnOnBreach, spyReturnOnBreach, excessReturnOnBreach, evidenceCodes, reasons);
    }

    public boolean matchesZone(LevelZoneDto zone) {
        return zone != null
                && zoneId != null
                && zoneId.equals(zone.id())
                && zoneBoundsVersion == zone.boundsVersion()
                && zoneRoleVersion == zone.roleVersion();
    }
}
