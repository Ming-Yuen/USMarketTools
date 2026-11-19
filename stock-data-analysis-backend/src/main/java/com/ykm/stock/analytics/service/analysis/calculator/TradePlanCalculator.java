package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Component
// 用途：負責「交易計劃計算」的純計算或判定邏輯，避免上層服務重複實作相同規則。
public class TradePlanCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MINIMUM_SUPPORT_STRENGTH = BigDecimal.valueOf(30);
    private final ExecutionCostCalculator costCalculator;

    // 用途：處理交易計劃計算，作為「交易計劃計算」流程中的處理步驟。
    public TradePlanCalculator(ExecutionCostCalculator costCalculator) { this.costCalculator = costCalculator; }

    // 用途：建立相關資料，作為「交易計劃計算」流程中的處理步驟。
    public TradePlanDto build(AnalysisContext context, String basisId, String normalizedInputHash,
                              BigDecimal currentPrice, LocalDate currentSession,
                              LevelZoneDto support, List<LevelZoneDto> resistances,
                              MetricResult<StructureTrendDto> trend, BigDecimal atr,
                              LocalDate earliestExecutableSession, LocalDate validThrough, PlanConstraintDto constraint) {
        return build(context, basisId, normalizedInputHash, currentPrice, currentSession, support, resistances,
                trend, atr, earliestExecutableSession, validThrough, constraint, null);
    }

    /** 整合單區破位真假診斷與大盤限制的交易計畫建立器；嚴格按同區 id + boundsVersion + roleVersion 套用診斷。 */
    public TradePlanDto build(AnalysisContext context, String basisId, String normalizedInputHash,
                              BigDecimal currentPrice, LocalDate currentSession,
                              LevelZoneDto support, List<LevelZoneDto> resistances,
                              MetricResult<StructureTrendDto> trend, BigDecimal atr,
                              LocalDate earliestExecutableSession, LocalDate validThrough,
                              PlanConstraintDto constraint, BreakoutDiagnosisDto zoneDiagnosis) {
        return build(context, basisId, normalizedInputHash, currentPrice, currentSession, support, resistances,
                trend, atr, earliestExecutableSession, validThrough, constraint, zoneDiagnosis, true, null);
    }

    /** 支援明確指定是否啟用 confirmedRetest 與實際訊號建立時間（例如過期 retest 後的新 Spring）。 */
    public TradePlanDto build(AnalysisContext context, String basisId, String normalizedInputHash,
                              BigDecimal currentPrice, LocalDate currentSession,
                              LevelZoneDto support, List<LevelZoneDto> resistances,
                              MetricResult<StructureTrendDto> trend, BigDecimal atr,
                              LocalDate earliestExecutableSession, LocalDate validThrough,
                              PlanConstraintDto constraint, BreakoutDiagnosisDto zoneDiagnosis,
                              boolean allowConfirmedRetest, java.time.Instant overrideSignalAt) {
        List<AnalysisReasonDto> reasons = new ArrayList<>();
        if (support == null) {
            reasons.add(AnalysisReasonDto.of("PLAN_SUPPORT_MISSING", "TRADE_PLAN", "缺少支撐區，無法建立技術計劃。"));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        if (support.state() != ZoneState.ACTIVE) {
            reasons.add(new AnalysisReasonDto("PLAN_SUPPORT_NOT_ACTIVE", "TRADE_PLAN", "支撐區並非有效狀態，暫不提供技術進出場價位。",
                    Map.of("supportState", Objects.toString(support.state(), "UNKNOWN"), "requiredState", ZoneState.ACTIVE.name()),
                    support.evidenceRefs()));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        if (support.effectiveStrength().compareTo(MINIMUM_SUPPORT_STRENGTH) < 0) {
            String strength = support.effectiveStrength().setScale(2, RoundingMode.DOWN).toPlainString();
            reasons.add(new AnalysisReasonDto("PLAN_SUPPORT_STRENGTH_INSUFFICIENT", "TRADE_PLAN",
                    "支撐有效強度 " + strength + "，低於最低要求 " + MINIMUM_SUPPORT_STRENGTH.toPlainString() + "，暫不提供技術進出場價位。",
                    Map.of("effectiveStrength", support.effectiveStrength(), "minimumStrength", MINIMUM_SUPPORT_STRENGTH),
                    support.evidenceRefs()));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        if (atr == null) {
            reasons.add(AnalysisReasonDto.of("PLAN_ATR_MISSING", "TRADE_PLAN", "本周期 ATR 缺失，無法計算技術入場與止損價位。"));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        if (atr.signum() <= 0) {
            reasons.add(new AnalysisReasonDto("PLAN_ATR_NOT_POSITIVE", "TRADE_PLAN", "本周期 ATR 必須大於零，無法計算技術入場與止損價位。",
                    Map.of("atr", atr, "exclusiveMinimumAtr", BigDecimal.ZERO), List.of()));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        AnalysisTimeframe timeframe = context.horizon() == AnalysisHorizon.SWING ? AnalysisTimeframe.DAILY : AnalysisTimeframe.WEEKLY;
        if (support.timeframe() != timeframe) {
            reasons.add(AnalysisReasonDto.of("HORIZON_TIMEFRAME_MISMATCH", "TRADE_PLAN", "支撐周期與持有期不一致。"));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        if (trend == null || trend.status() != MetricStatus.AVAILABLE || trend.value() == null) {
            reasons.add(AnalysisReasonDto.of("STRUCTURE_INSUFFICIENT", "TRADE_PLAN", "本周期結構趨勢不足，不能把未知當 SIDEWAYS。"));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }

        boolean confirmedRetest = allowConfirmedRetest && support.retestState() == RetestState.SUCCEEDED && support.roleVersion() > 1
                && support.roleConfirmationPrice() != null && support.roleConfirmationPrice().signum() > 0;
        boolean matchedSpringReclaimed = zoneDiagnosis != null && zoneDiagnosis.matchesZone(support)
                && zoneDiagnosis.diagnosis() == BreakoutDiagnosis.SPRING_RECLAIMED
                && zoneDiagnosis.reclaimDate() != null
                && (zoneDiagnosis.validThroughSession() == null || currentSession == null
                || !currentSession.isAfter(zoneDiagnosis.validThroughSession()));
        boolean matchedHighRiskBreakdown = zoneDiagnosis != null && zoneDiagnosis.matchesZone(support)
                && zoneDiagnosis.diagnosis() == BreakoutDiagnosis.HIGH_RISK_BREAKDOWN;
        SetupType setupType = confirmedRetest ? SetupType.CONFIRMED_RETEST : SetupType.PULLBACK;
        EntryMethod entryMethod = confirmedRetest ? EntryMethod.CONFIRMATION_MARKET : EntryMethod.ZONE_LIMIT;
        PriceRangeDto entry = confirmedRetest
                ? new PriceRangeDto(support.roleConfirmationPrice(),
                    support.roleConfirmationPrice().add(atr.multiply(new BigDecimal("0.25"), MC), MC), basisId)
                : new PriceRangeDto(support.lower(), support.upper(), basisId);

        BigDecimal stop = support.lower().subtract(atr.multiply(new BigDecimal("0.50"), MC), MC);
        // 技術收盤失效閾值凍結於區域確認時的 anchorAtr，並自支撐角色生效時間（roleConfirmedAt）起算，避免報告日 ATR 變動或翻轉前低收誤判。
        BigDecimal frozenAtr = support.anchorAtr() != null && support.anchorAtr().signum() > 0 ? support.anchorAtr() : atr;
        BigDecimal invalidation = support.lower().subtract(frozenAtr.multiply(new BigDecimal("0.25"), MC), MC);
        if (stop.signum() <= 0 || stop.compareTo(entry.lower()) >= 0) {
            reasons.add(AnalysisReasonDto.of("INVALID_STOP", "TRADE_PLAN", "保護止損無效。"));
            return unavailable(context, support, basisId, normalizedInputHash, reasons);
        }
        List<TargetDto> targets = targets(resistances, entry.upper(), timeframe);
        if (targets.isEmpty()) {
            // 已核驗支撐仍可展示，但沒有可靠阻力就不能虛構止盈價或固定 R/R。
            reasons.add(AnalysisReasonDto.of("NO_FIXED_TARGET", "TRADE_PLAN",
                    "尚無已核驗的上方固定止盈阻力；只保留支撐與止損參考，不產生可執行入場信號。"));
        }
        java.time.Instant stopConfirmedAt = support.roleConfirmedAt() != null ? support.roleConfirmedAt() : support.confirmedAt();
        StopRuleDto stopRule = new StopRuleDto(stop, invalidation, 2, AnalysisTimeframe.DAILY,
                new BigDecimal("0.50"), atr, timeframe, stopConfirmedAt, support.evidenceRefs(), frozenAtr);
        MetricResult<RiskRewardDto> rr = targets.isEmpty()
                ? MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                    AnalysisReasonDto.of("FIXED_RR_UNAVAILABLE", "TRADE_PLAN", "沒有固定止盈目標，不能計算固定 R/R。"))
                : costCalculator.calculate(entry.upper(), stop, targets);
        TradePlanStatus status = TradePlanStatus.WAITING_TRIGGER;

        boolean macroConfirmRequired = constraint != null && constraint.reasonCodes().contains("EVENT_REQUIRES_CLOSE_CONFIRMATION");
        boolean bearOrPanicConstraint = constraint != null
                && (constraint.reasonCodes().contains("MARKET_BEAR") || constraint.reasonCodes().contains("MARKET_PANIC"));

        if (currentSession != null && validThrough != null && currentSession.isAfter(validThrough)) {
            status = TradePlanStatus.INVALIDATED;
            reasons.add(AnalysisReasonDto.of("ENTRY_EXPIRED", "TRADE_PLAN", "計劃已超過信號後5個交易session。"));
        } else if (currentPrice != null && currentPrice.compareTo(stop) <= 0) {
            status = TradePlanStatus.INVALIDATED;
            reasons.add(AnalysisReasonDto.of("PROTECTIVE_STOP_BREACHED", "TRADE_PLAN", "最近收市價已低於保護止損。"));
        } else if (matchedHighRiskBreakdown) {
            reasons.add(AnalysisReasonDto.of("HIGH_RISK_BREAKDOWN_DETECTED", "TRADE_PLAN", "本支撐區已判定為高機率真破位，暫不允許新入場。"));
        } else if (currentSession != null && earliestExecutableSession != null && currentSession.isBefore(earliestExecutableSession)) {
            reasons.add(AnalysisReasonDto.of("SIGNAL_NOT_EXECUTABLE_YET", "TRADE_PLAN", "信號確認後最早由下一個 regular session 才可執行。"));
        } else if (constraint != null && (constraint.entryPermission() == EntryPermission.BLOCK
                || constraint.entryPermission() == EntryPermission.UNKNOWN || bearOrPanicConstraint)) {
            reasons.add(AnalysisReasonDto.of("ENTRY_CONSTRAINT", "TRADE_PLAN", "外部市場／事件條件尚不允許新入場。"));
        } else if (constraint != null && constraint.entryPermission() == EntryPermission.REQUIRE_CLOSE_CONFIRMATION
                && (macroConfirmRequired ? !confirmedRetest : (!confirmedRetest && !matchedSpringReclaimed))) {
            reasons.add(AnalysisReasonDto.of("CLOSE_CONFIRMATION_REQUIRED", "TRADE_PLAN",
                    macroConfirmRequired ? "宏觀事件限制要求已確認 retest；一般回踩或單純彈簧收回暫不可入場。"
                            : "外部限制要求已確認 retest 或有效彈簧收回；一般 pullback 暫不可入場。"));
        } else if (trend.value().state() == TrendState.DOWNTREND || trend.value().state() == TrendState.TRANSITION) {
            reasons.add(AnalysisReasonDto.of("STRUCTURE_CONFIRMATION_REQUIRED", "TRADE_PLAN", "結構尚未符合入場資格。"));
        } else if (currentPrice != null && currentPrice.compareTo(entry.lower()) >= 0 && currentPrice.compareTo(entry.upper()) <= 0) {
            status = TradePlanStatus.ACTIVE;
        } else if (currentPrice != null && currentPrice.compareTo(entry.lower()) < 0) {
            reasons.add(AnalysisReasonDto.of("RECLAIM_REQUIRED", "TRADE_PLAN", "價格已低於入場區，需先重新收復區域，不能把跌穿當折價買入。"));
        } else if (currentPrice != null && currentPrice.compareTo(entry.upper()) > 0) {
            reasons.add(AnalysisReasonDto.of("ENTRY_PRICE_ABOVE_CAP", "TRADE_PLAN", "價格高於保守入場上限，不追價。"));
        }

        if (rr.status() != MetricStatus.AVAILABLE || rr.value() == null || rr.value().netRR().compareTo(BigDecimal.valueOf(2)) < 0) {
            if (status != TradePlanStatus.INVALIDATED) status = TradePlanStatus.WAITING_TRIGGER;
            if (!targets.isEmpty()) reasons.add(AnalysisReasonDto.of("RR_BELOW_POLICY", "TRADE_PLAN",
                    "成本後 RR 未達 2 或不可計算。"));
        }

        java.time.Instant createdSignalAt = overrideSignalAt != null ? overrideSignalAt
                : (confirmedRetest ? support.roleConfirmedAt() : support.confirmedAt());
        String targetRefs = targets.stream().map(t -> Objects.toString(t.sourceZoneId(), "")).reduce((a,b)->a+","+b).orElse("");
        String id = hash(String.join("|",
                context.figi(), context.horizon().name(), context.analysisAsOf().toString(),
                Objects.toString(normalizedInputHash,""), Objects.toString(basisId,""), support.id(),
                String.valueOf(support.boundsVersion()), setupType.name(), Objects.toString(createdSignalAt, ""),
                entry.lower().toPlainString(), entry.upper().toPlainString(), stop.toPlainString(), targetRefs,
                constraint == null ? "NONE" : constraint.entryPermission().name(), Objects.toString(validThrough,"")));
        return new TradePlanDto(id, context.horizon(), setupType, entry, entryMethod,
                confirmedRetest ? "已確認 resistance-to-support retest；只在價格不超過確認上限時執行。"
                        : "價格回到已確認支撐區，並符合結構與外部限制。",
                stopRule, targets, rr, status, reasons, support.id(), support.boundsVersion(), timeframe,
                createdSignalAt, validThrough, support.evidenceRefs());
    }

    // 用途：處理targets，作為「交易計劃計算」流程中的處理步驟。
    private List<TargetDto> targets(List<LevelZoneDto> resistances, BigDecimal entry, AnalysisTimeframe sourceTimeframe) {
        // 跨週期阻力先按實際保守止盈價排序；不可讓相同週期優先規則跳過更近障礙。
        List<LevelZoneDto> sorted = resistances == null ? List.of() : resistances.stream()
                .filter(z -> z.state() == ZoneState.ACTIVE && z.lower() != null && z.upper() != null
                        && z.anchorAtr() != null && z.anchorAtr().signum() > 0
                        && z.timeframe().ordinal() >= sourceTimeframe.ordinal())
                .filter(z -> targetPrice(z).compareTo(entry) > 0)
                .sorted(Comparator.comparing(this::targetPrice)
                        .thenComparing(LevelZoneDto::effectiveStrength, Comparator.reverseOrder())
                        .thenComparing(LevelZoneDto::id))
                .toList();
        List<LevelZoneDto> candidates = new ArrayList<>();
        for (LevelZoneDto candidate : sorted) {
            boolean duplicate = candidates.stream().anyMatch(existing ->
                    targetPrice(candidate).compareTo(targetPrice(existing)) == 0
                    || (candidate.lower().compareTo(existing.upper()) <= 0
                        && candidate.upper().compareTo(existing.lower()) >= 0));
            if (!duplicate) candidates.add(candidate);
            if (candidates.size() == 3) break;
        }
        if (candidates.isEmpty()) return List.of();
        List<BigDecimal> weights = candidates.size() == 1 ? List.of(BigDecimal.ONE)
                : candidates.size() == 2 ? List.of(new BigDecimal("0.5"), new BigDecimal("0.5"))
                : List.of(new BigDecimal("0.5"), new BigDecimal("0.3"), new BigDecimal("0.2"));
        List<TargetDto> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            LevelZoneDto zone = candidates.get(i);
            out.add(new TargetDto(targetPrice(zone), weights.get(i),
                    i == candidates.size() - 1 ? TargetAction.TAKE_PROFIT : TargetAction.PARTIAL_SELL,
                    zone.id(), "上方已確認阻力區前置0.10 ATR", zone.evidenceRefs()));
        }
        return List.copyOf(out);
    }

    /** 固定目標必須保留阻力前置 ATR 緩衝，且在入場價之上。 */
    private BigDecimal targetPrice(LevelZoneDto zone) {
        return zone.lower().subtract(zone.anchorAtr().multiply(new BigDecimal("0.10"), MC), MC);
    }

    // 用途：處理unavailable，作為「交易計劃計算」流程中的處理步驟。
    private TradePlanDto unavailable(AnalysisContext context, LevelZoneDto zone, String basisId, String normalizedInputHash,
                                     List<AnalysisReasonDto> reasons) {
        String id=hash(context.figi()+"|UNAVAILABLE|"+(zone==null?"NONE":zone.id())+"|"+context.analysisAsOf()+"|"+normalizedInputHash);
        return new TradePlanDto(id,context.horizon(),SetupType.PULLBACK,null,EntryMethod.ZONE_LIMIT,null,null,List.of(),
                MetricResult.unavailable(MetricStatus.NOT_APPLICABLE, AnalysisReasonDto.of("RISK_REWARD_UNAVAILABLE","TRADE_PLAN","計劃不可評估。")),
                TradePlanStatus.UNAVAILABLE,reasons,zone==null?null:zone.id(),zone==null?0:zone.boundsVersion(),
                context.horizon()==AnalysisHorizon.SWING?AnalysisTimeframe.DAILY:AnalysisTimeframe.WEEKLY,
                zone==null?null:zone.confirmedAt(),null,zone==null?List.of():zone.evidenceRefs());
    }

    // 用途：判斷h，作為「交易計劃計算」流程中的處理步驟。
    private String hash(String s){ try { MessageDigest d=MessageDigest.getInstance("SHA-256"); return HexFormat.of().formatHex(d.digest(s.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){ throw new IllegalStateException(e); } }
}
