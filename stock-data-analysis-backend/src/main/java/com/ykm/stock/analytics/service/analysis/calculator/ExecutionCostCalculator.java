package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

@Component
// 用途：負責「成交成本計算」的純計算或判定邏輯，避免上層服務重複實作相同規則。
public class ExecutionCostCalculator {
    public static final BigDecimal FEE = new BigDecimal("0.001");
    public static final BigDecimal SLIPPAGE = new BigDecimal("0.0005");
    private static final MathContext MC = MathContext.DECIMAL128;

    // 用途：處理buy成本，作為「成交成本計算」流程中的處理步驟。
    public BigDecimal buyCost(BigDecimal entry) { return buyUnitCost(marketBuyFill(entry)); }
    // 用途：取得或整理市場 buy fill的內部表示，供本服務其他分析步驟重用。
    public BigDecimal marketBuyFill(BigDecimal base) { return base.multiply(BigDecimal.ONE.add(SLIPPAGE, MC), MC); }
    // 用途：處理limit buy fill的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    public BigDecimal limitBuyFill(BigDecimal base, BigDecimal limit) { return limit.min(marketBuyFill(base)); }
    // 用途：處理buy unit 成本的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    public BigDecimal buyUnitCost(BigDecimal fill) { return fill.multiply(BigDecimal.ONE.add(FEE, MC), MC); }

    // 用途：處理sellValue，作為「成交成本計算」流程中的處理步驟。
    public BigDecimal sellValue(BigDecimal price) { return sellUnitValue(marketSellFill(price)); }
    // 用途：取得或整理市場 sell fill的內部表示，供本服務其他分析步驟重用。
    public BigDecimal marketSellFill(BigDecimal base) { return base.multiply(BigDecimal.ONE.subtract(SLIPPAGE, MC), MC); }
    // 用途：處理limit sell fill的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    public BigDecimal limitSellFill(BigDecimal limit) { return limit; }
    // 用途：處理sell unit 數值的內部流程，統一輸入、輸出與資料品質規則供後續步驟使用。
    public BigDecimal sellUnitValue(BigDecimal fill) { return fill.multiply(BigDecimal.ONE.subtract(FEE, MC), MC); }

    // 用途：計算相關資料，作為「成交成本計算」流程中的處理步驟。
    public MetricResult<RiskRewardDto> calculate(BigDecimal entry, BigDecimal stop, List<TargetDto> targets) {
        if (entry == null || stop == null || entry.signum() <= 0 || stop.signum() <= 0 || stop.compareTo(entry) >= 0
                || targets == null || targets.isEmpty()) {
            return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                    AnalysisReasonDto.of("INVALID_RISK_REWARD_INPUT", "TRADE_PLAN", "Entry、stop 或 target 無效。"));
        }
        BigDecimal weight = targets.stream().map(TargetDto::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (weight.compareTo(BigDecimal.ONE) != 0 || targets.stream().anyMatch(t -> t.weight() == null || t.weight().signum() < 0 || t.price() == null || t.price().compareTo(entry) <= 0)) {
            return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                    AnalysisReasonDto.of("INVALID_TARGET_WEIGHTS", "TRADE_PLAN", "Target 權重或價格無效。"));
        }
        BigDecimal weightedTarget = targets.stream().map(t -> t.price().multiply(t.weight(), MC)).reduce(BigDecimal.ZERO, (a,b)->a.add(b, MC));
        BigDecimal grossRisk = entry.subtract(stop, MC);
        BigDecimal grossReward = weightedTarget.subtract(entry, MC);
        if (grossRisk.signum() <= 0) return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                AnalysisReasonDto.of("NON_POSITIVE_RISK", "TRADE_PLAN", "風險分母必須大於0。"));
        BigDecimal c = buyCost(entry);
        BigDecimal netStop = sellValue(stop);
        BigDecimal weightedNetTarget = targets.stream().map(t -> sellValue(t.price()).multiply(t.weight(), MC)).reduce(BigDecimal.ZERO, (a,b)->a.add(b, MC));
        BigDecimal netRisk = c.subtract(netStop, MC);
        BigDecimal netReward = weightedNetTarget.subtract(c, MC);
        if (netRisk.signum() <= 0) return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                AnalysisReasonDto.of("NON_POSITIVE_NET_RISK", "TRADE_PLAN", "成本後風險分母必須大於0。"));
        BigDecimal grossRR = grossReward.divide(grossRisk, MC);
        BigDecimal netRR = netReward.divide(netRisk, MC);
        BigDecimal upside = weightedTarget.divide(entry, MC).subtract(BigDecimal.ONE, MC).multiply(BigDecimal.valueOf(100), MC);
        BigDecimal downside = stop.divide(entry, MC).subtract(BigDecimal.ONE, MC).multiply(BigDecimal.valueOf(100), MC);
        return MetricResult.available(new RiskRewardDto(entry, stop, grossRisk, grossReward, grossRR, netRisk, netReward, netRR, upside, downside));
    }
}
