package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.service.market.MarketStatusService;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.ExecutionCostCalculator;
import com.ykm.stock.analytics.service.analysis.calculator.TradePlanCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
// 用途：負責「交易計劃服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class TradePlanService {
    private static final java.time.ZoneId NEW_YORK = java.time.ZoneId.of("America/New_York");
    private final TradePlanCalculator calculator;
    private final ExecutionCostCalculator costCalculator;
    private final MarketStatusService calendar;

    // 用途：建立相關資料，作為「交易計劃服務」流程中的處理步驟。
    public MetricResult<TradePlanResultDto> build(AnalysisContext context,
                                                   NormalizedPriceSeriesDto price,
                                                   PriceStructureDto structure,
                                                   SupportResistanceDto levels,
                                                   PlanConstraintDto constraints) {
        if (price == null || price.quality() != MetricStatus.AVAILABLE || price.priceBasis() == null
                || price.priceBasis().basisId() == null || price.priceBasis().basisId().isBlank()
                || price.bars().isEmpty() || structure == null || levels == null) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("TRADE_PLAN_INPUT_REQUIRED", "TRADE_PLAN", "缺少價格結構或支阻輸入。"));
        }
        AnalysisTimeframe wanted = context.horizon() == AnalysisHorizon.SWING ? AnalysisTimeframe.DAILY : AnalysisTimeframe.WEEKLY;
        TimeframeStructureDto frame = structure.timeframes().stream().filter(f -> f.timeframe() == wanted).findFirst().orElse(null);
        if (frame == null || frame.atr14().status() != MetricStatus.AVAILABLE) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("ATR_REQUIRED", "TRADE_PLAN", wanted + " ATR14 不可用。"));
        }
        BigDecimal current = price.bars().get(price.bars().size() - 1).close();
        LocalDate priceDate = price.bars().get(price.bars().size() - 1).sessionDate();
        PlanConstraintDto constraint = constraints;

        List<LevelZoneDto> supports = new ArrayList<>();
        supports.addAll(levels.supports());
        supports.addAll(levels.containingZones().stream().filter(z -> z.role() == ZoneRole.SUPPORT).toList());
        supports = supports.stream().filter(z -> z.timeframe() == wanted && z.state() == ZoneState.ACTIVE)
                .sorted(Comparator.comparing((LevelZoneDto z) -> distance(z,current)).thenComparing(LevelZoneDto::effectiveStrength, Comparator.reverseOrder()))
                .limit(3).toList();
        if (supports.isEmpty()) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("NO_ELIGIBLE_SUPPORT", "TRADE_PLAN", "沒有符合持有期的有效支撐。"));
        }
        List<TradePlanDto> plans = supports.stream().map(support -> {
            BreakoutDiagnosisDto zoneDiagnosis = findZoneDiagnosis(levels, support);
            boolean hasRetestMetadata = support.retestState() == RetestState.SUCCEEDED
                    && support.roleVersion() > 1
                    && support.roleConfirmationPrice() != null
                    && support.roleConfirmationPrice().signum() > 0;
            LocalDate retestSignalDate = hasRetestMetadata && support.roleConfirmedAt() != null
                    ? support.roleConfirmedAt().atZone(NEW_YORK).toLocalDate()
                    : (hasRetestMetadata ? priceDate : null);
            LocalDate retestValidThrough = retestSignalDate != null ? advanceTradingDays(retestSignalDate, 5) : null;
            boolean activeRetest = hasRetestMetadata && (retestValidThrough == null || !priceDate.isAfter(retestValidThrough));

            boolean hasSpring = zoneDiagnosis != null
                    && zoneDiagnosis.matchesZone(support)
                    && zoneDiagnosis.diagnosis() == BreakoutDiagnosis.SPRING_RECLAIMED
                    && zoneDiagnosis.reclaimDate() != null;
            LocalDate springValidThrough = hasSpring
                    ? (zoneDiagnosis.validThroughSession() != null
                            ? zoneDiagnosis.validThroughSession()
                            : advanceTradingDays(zoneDiagnosis.reclaimDate(), 5))
                    : null;
            boolean activeSpring = hasSpring && (springValidThrough == null || !priceDate.isAfter(springValidThrough));

            LocalDate earliestExecutable;
            LocalDate validThrough;
            boolean allowConfirmedRetest;
            java.time.Instant signalCreatedAt;
            if (activeRetest) {
                earliestExecutable = calendar.getNextTradingDay(retestSignalDate);
                validThrough = retestValidThrough;
                allowConfirmedRetest = true;
                signalCreatedAt = support.roleConfirmedAt();
            } else if (activeSpring) {
                // 同區舊 retest 已過期而今日出現新 Spring：採用新 Spring 的凍結訊號日與 5 交易日視窗，且不得沿用過期 retest 繞過宏觀 CPI 限制。
                earliestExecutable = calendar.getNextTradingDay(zoneDiagnosis.reclaimDate());
                validThrough = springValidThrough;
                allowConfirmedRetest = false;
                signalCreatedAt = calendar.getSessionCloseAt(zoneDiagnosis.reclaimDate());
            } else if (hasRetestMetadata) {
                // 過期 retest 且無新 Spring：保留原過期期限（ENTRY_EXPIRED），不復活舊訊號。
                earliestExecutable = calendar.getNextTradingDay(retestSignalDate);
                validThrough = retestValidThrough;
                allowConfirmedRetest = true;
                signalCreatedAt = support.roleConfirmedAt();
            } else {
                LocalDate baseDate = support.confirmedAt() == null ? priceDate : support.confirmedAt().atZone(NEW_YORK).toLocalDate();
                earliestExecutable = calendar.getNextTradingDay(baseDate);
                validThrough = null;
                allowConfirmedRetest = false;
                signalCreatedAt = support.confirmedAt();
            }
            TradePlanDto plan = calculator.build(context, price.priceBasis().basisId(), price.inputHash(), current, priceDate,
                    support, levels.resistances(), frame.minorTrend(), frame.atr14().value().atr(),
                    earliestExecutable, validThrough, constraint, zoneDiagnosis, allowConfirmedRetest, signalCreatedAt);
            return applyCloseInvalidation(plan, price.bars());
        }).toList();
        MetricResult<RiskRewardDto> currentRR = MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                AnalysisReasonDto.of("CURRENT_PRICE_RR_UNAVAILABLE", "TRADE_PLAN", "目前收市價不符合可評估計劃。"));
        TradePlanDto firstUsable = plans.stream().filter(p -> p.stop()!=null && !p.targets().isEmpty()).findFirst().orElse(null);
        if (firstUsable != null && current.compareTo(firstUsable.stop().protectivePrice()) > 0
                && current.compareTo(firstUsable.targets().get(0).price()) < 0) {
            currentRR = costCalculator.calculate(current, firstUsable.stop().protectivePrice(), firstUsable.targets());
        }
        List<AnalysisReasonDto> reasons = constraints == null
                ? List.of(AnalysisReasonDto.of("CONTEXT_NOT_EVALUATED", "TRADE_PLAN", "市場／事件限制尚未評估。")) : List.of();
        TradePlanResultDto result = new TradePlanResultDto(plans,current,priceDate,currentRR,reasons,price.evidenceRefs(),constraints!=null);
        return MetricResult.available(result,result.evidenceRefs());
    }

    // 用途：套用CloseInvalidation，作為「交易計劃服務」流程中的處理步驟。
    private TradePlanDto applyCloseInvalidation(TradePlanDto plan, List<NormalizedBarDto> bars) {
        if (plan == null || plan.stop() == null || plan.status() == TradePlanStatus.UNAVAILABLE
                || plan.stop().closeThreshold() == null || plan.stop().requiredCloses() <= 0) return plan;
        int consecutive = 0;
        LocalDate previous = null;
        java.time.Instant confirmedAt = null;
        for (NormalizedBarDto bar : bars.stream().sorted(Comparator.comparing(NormalizedBarDto::sessionDate)).toList()) {
            if (bar.availableAt() == null || plan.stop().confirmedAt() != null && !bar.availableAt().isAfter(plan.stop().confirmedAt())) continue;
            if (previous != null) {
                LocalDate expected;
                try { expected = calendar.getNextTradingDay(previous); }
                catch (IllegalArgumentException ex) { consecutive = 0; previous = bar.sessionDate(); continue; }
                if (!expected.equals(bar.sessionDate())) consecutive = 0;
            }
            if (bar.close() != null && bar.close().compareTo(plan.stop().closeThreshold()) < 0) {
                consecutive++;
                if (consecutive >= plan.stop().requiredCloses()) { confirmedAt = bar.availableAt(); break; }
            } else consecutive = 0;
            previous = bar.sessionDate();
        }
        if (confirmedAt == null) return plan;
        List<AnalysisReasonDto> reasons = new ArrayList<>(plan.reasons());
        reasons.add(new AnalysisReasonDto("TECHNICAL_INVALIDATION_CONFIRMED", "TRADE_PLAN",
                "已連續兩個完整 DAILY close 低於凍結的技術失效閾值。", Map.of("confirmedAt", confirmedAt.toString()), plan.stop().anchorEvidenceRefs()));
        return new TradePlanDto(plan.planId(), plan.horizon(), plan.setupType(), plan.entryRange(), plan.entryMethod(), plan.trigger(),
                plan.stop(), plan.targets(), plan.riskReward(), TradePlanStatus.INVALIDATED, reasons, plan.sourceZoneId(),
                plan.sourceBoundsVersion(), plan.structureTimeframe(), plan.createdSignalAt(), plan.validThroughSession(), plan.evidenceRefs());
    }

    private LocalDate advanceTradingDays(LocalDate start, int sessions) {
        LocalDate cursor = start;
        for (int i = 0; i < sessions; i++) {
            cursor = calendar.getNextTradingDay(cursor);
        }
        return cursor;
    }

    private BreakoutDiagnosisDto findZoneDiagnosis(SupportResistanceDto levels, LevelZoneDto support) {
        if (levels == null || support == null) return null;
        for (BreakoutDiagnosisDto diag : levels.zoneDiagnoses()) {
            if (diag != null && diag.matchesZone(support)) {
                return diag;
            }
        }
        if (levels.breakoutDiagnosis() != null && levels.breakoutDiagnosis().matchesZone(support)) {
            return levels.breakoutDiagnosis();
        }
        return null;
    }

    // 用途：處理distance，作為「交易計劃服務」流程中的處理步驟。
    private BigDecimal distance(LevelZoneDto z, BigDecimal p) {
        if (p.compareTo(z.lower()) < 0) return z.lower().subtract(p).abs();
        if (p.compareTo(z.upper()) > 0) return p.subtract(z.upper()).abs();
        return BigDecimal.ZERO;
    }
}
