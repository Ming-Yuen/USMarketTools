package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto.DcfBranchDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto.DcfScenarioDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.DcfValuationDto.ReverseDcfDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 兩階段股權現金流（FCF 近似 FCFE）DCF 與 Reverse DCF 純記憶體計算器。
 * 嚴格獨立於客觀市場參考帶 [L, M, H] 之外：
 * - 第 1–5 年以常數成長率 h 複利成長；
 * - 第 6–10 年線性收斂至永續成長率 g = 2.5%：g_t = h + (g - h) * (t - 5) / 5（第 10 年恰為 g）；
 * - 第 10 年終值 TV_10 = FCF_10 * (1 + g) / (r - g)，折現率 r = 10Y 美債 + ERP（5.0%）；
 * - 單維度情境：保守（h - 3%）、基準（h）、樂觀（h + 3%），上限 25%、下限 -10%；
 * - Reverse DCF：共用同一現值函式，於 [-30%, +100%] 進行嚴格端點驗證之二分搜尋反解市場隱含第 1–5 年 FCF CAGR。
 */
public class DcfValuationCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    public static final BigDecimal DEFAULT_ERP = new BigDecimal("0.0500");
    public static final BigDecimal TERMINAL_GROWTH = new BigDecimal("0.0250");
    public static final BigDecimal STAGE1_GROWTH_CAP = new BigDecimal("0.2500");
    public static final BigDecimal STAGE1_GROWTH_FLOOR = new BigDecimal("-0.1000");
    public static final BigDecimal SCENARIO_STEP = new BigDecimal("0.0300");
    public static final BigDecimal DEFAULT_FALLBACK_GROWTH = new BigDecimal("0.0500");
    public static final BigDecimal REVERSE_SEARCH_LOW = new BigDecimal("-0.3000");
    public static final BigDecimal REVERSE_SEARCH_HIGH = new BigDecimal("1.0000");
    public static final BigDecimal EXPECTATION_STRETCHED_GAP = new BigDecimal("0.0500");
    private static final int BISECTION_ITERATIONS = 80;
    private static final BigDecimal CONVERGENCE_TOL = new BigDecimal("0.0000001");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String METHODOLOGY_NOTE =
            "兩階段股權現金流折現模型（採 FCF 近似 FCFE、折現率 = 10Y 美債 + 5.0% ERP、永續成長 2.5%、假設淨借款變動為零；嚴格獨立於市場相對估值帶 [L, M, H]）。";

    public record Inputs(
            LocalDate valuationDate,
            String currency,
            BigDecimal currentPrice,
            BigDecimal marketCapUsd,
            BigDecimal sharesAtDay,
            boolean financialInstitution,
            boolean reit,
            boolean etf,
            String valuationBlockedReason,
            BigDecimal freeCashFlowTtmUsd,
            BigDecimal adjFreeCashFlowTtmUsd,
            BigDecimal tenYearYieldPercent,
            LocalDate tenYearYieldDate,
            BigDecimal historicalRevenueCagr3y,
            BigDecimal historicalFcfCagr3y,
            BigDecimal forwardExpectedGrowth
    ) {}

    public DcfValuationDto calculate(Inputs in) {
        if (in == null) {
            return null;
        }
        if (in.etf()) {
            return DcfValuationDto.unavailable(MetricStatus.NOT_APPLICABLE.name(), "NOT_APPLICABLE_FOR_ETF",
                    in.valuationDate(), in.currency(), in.currentPrice());
        }
        if (in.financialInstitution()) {
            return DcfValuationDto.unavailable(MetricStatus.NOT_APPLICABLE.name(), "DCF_NOT_APPLICABLE_TO_INDUSTRY",
                    in.valuationDate(), in.currency(), in.currentPrice());
        }
        if (in.reit()) {
            return DcfValuationDto.unavailable(MetricStatus.NOT_APPLICABLE.name(), "DCF_NOT_APPLICABLE_TO_REIT",
                    in.valuationDate(), in.currency(), in.currentPrice());
        }
        if (in.valuationBlockedReason() != null && !in.valuationBlockedReason().isBlank()) {
            return DcfValuationDto.unavailable(MetricStatus.UNVERIFIED.name(), in.valuationBlockedReason(),
                    in.valuationDate(), in.currency(), in.currentPrice());
        }
        if (in.currentPrice() == null || in.currentPrice().signum() <= 0
                || in.marketCapUsd() == null || in.marketCapUsd().signum() <= 0
                || in.sharesAtDay() == null || in.sharesAtDay().signum() <= 0) {
            return DcfValuationDto.unavailable(MetricStatus.INSUFFICIENT_DATA.name(), "MARKET_CAP_MISSING",
                    in.valuationDate(), in.currency(), in.currentPrice());
        }
        if (in.tenYearYieldPercent() == null) {
            return DcfValuationDto.unavailable(MetricStatus.INSUFFICIENT_DATA.name(), "DCF_DISCOUNT_RATE_UNAVAILABLE",
                    in.valuationDate(), in.currency(), in.currentPrice());
        }

        BigDecimal riskFree = in.tenYearYieldPercent().divide(HUNDRED, 6, RoundingMode.HALF_UP);
        BigDecimal discountRate = riskFree.add(DEFAULT_ERP, MC).setScale(6, RoundingMode.HALF_UP);
        if (discountRate.compareTo(TERMINAL_GROWTH) <= 0) {
            return DcfValuationDto.unavailable(MetricStatus.NOT_APPLICABLE.name(), "DCF_INVALID_TERMINAL_SPREAD",
                    in.valuationDate(), in.currency(), in.currentPrice());
        }

        GrowthSelection growthSelection = selectStage1Growth(in.historicalRevenueCagr3y(), in.historicalFcfCagr3y(), in.forwardExpectedGrowth());

        DcfBranchDto standardBranch = computeBranch(
                "STANDARD_FCF",
                in.freeCashFlowTtmUsd(),
                in.marketCapUsd(),
                in.sharesAtDay(),
                in.currentPrice(),
                discountRate,
                growthSelection
        );
        DcfBranchDto adjBranch = computeBranch(
                "SBC_ADJUSTED_FCF",
                in.adjFreeCashFlowTtmUsd(),
                in.marketCapUsd(),
                in.sharesAtDay(),
                in.currentPrice(),
                discountRate,
                growthSelection
        );

        boolean anyAvailable = MetricStatus.AVAILABLE.name().equals(standardBranch.status())
                || MetricStatus.AVAILABLE.name().equals(adjBranch.status());
        String overallStatus = anyAvailable ? MetricStatus.AVAILABLE.name() : standardBranch.status();
        String overallReason = anyAvailable ? null : standardBranch.reasonCode();

        return new DcfValuationDto(
                overallStatus,
                overallReason,
                METHODOLOGY_NOTE,
                in.valuationDate(),
                in.currency(),
                in.currentPrice(),
                in.marketCapUsd(),
                in.sharesAtDay(),
                riskFree,
                in.tenYearYieldDate(),
                DEFAULT_ERP,
                discountRate,
                TERMINAL_GROWTH,
                in.historicalRevenueCagr3y(),
                in.historicalFcfCagr3y(),
                in.forwardExpectedGrowth(),
                growthSelection.basisNote(),
                standardBranch,
                adjBranch
        );
    }

    /** 兩階段現金流現值核心函式（正反向 DCF 共用同一數值定義）：FCF0 > 0，r > g，h > -1。 */
    public static BigDecimal presentValueTwoStage(BigDecimal baseFcf, BigDecimal stage1Growth,
                                                  BigDecimal discountRate, BigDecimal terminalGrowth) {
        if (baseFcf == null || baseFcf.signum() <= 0 || stage1Growth == null
                || discountRate == null || terminalGrowth == null
                || discountRate.compareTo(terminalGrowth) <= 0) {
            return null;
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(discountRate, MC);
        BigDecimal discountFactor = BigDecimal.ONE;
        BigDecimal fcf = baseFcf;
        BigDecimal pvTotal = BigDecimal.ZERO;
        BigDecimal five = BigDecimal.valueOf(5);

        for (int year = 1; year <= 10; year++) {
            BigDecimal gYear;
            if (year <= 5) {
                gYear = stage1Growth;
            } else {
                BigDecimal step = BigDecimal.valueOf(year - 5L).divide(five, MC);
                gYear = stage1Growth.add(terminalGrowth.subtract(stage1Growth, MC).multiply(step, MC), MC);
            }
            BigDecimal growthMult = BigDecimal.ONE.add(gYear, MC);
            if (growthMult.signum() <= 0) {
                return null;
            }
            fcf = fcf.multiply(growthMult, MC);
            discountFactor = discountFactor.multiply(onePlusR, MC);
            pvTotal = pvTotal.add(fcf.divide(discountFactor, MC), MC);
        }

        BigDecimal terminalFcf = fcf.multiply(BigDecimal.ONE.add(terminalGrowth, MC), MC);
        BigDecimal terminalValue = terminalFcf.divide(discountRate.subtract(terminalGrowth, MC), MC);
        pvTotal = pvTotal.add(terminalValue.divide(discountFactor, MC), MC);
        return pvTotal;
    }

    /** 以二分搜尋反解使兩階段現值等於目標市值的第 1–5 年隱含 FCF 年複合成長率；嚴格驗證端點，不在區間內回傳 null。 */
    public static BigDecimal solveImpliedStage1Growth(BigDecimal baseFcf, BigDecimal targetEquityValue,
                                                      BigDecimal discountRate, BigDecimal terminalGrowth) {
        if (baseFcf == null || baseFcf.signum() <= 0
                || targetEquityValue == null || targetEquityValue.signum() <= 0
                || discountRate == null || terminalGrowth == null
                || discountRate.compareTo(terminalGrowth) <= 0) {
            return null;
        }
        BigDecimal lo = REVERSE_SEARCH_LOW;
        BigDecimal hi = REVERSE_SEARCH_HIGH;
        BigDecimal pvLo = presentValueTwoStage(baseFcf, lo, discountRate, terminalGrowth);
        BigDecimal pvHi = presentValueTwoStage(baseFcf, hi, discountRate, terminalGrowth);
        if (pvLo == null || pvHi == null) {
            return null;
        }
        int cmpLo = pvLo.compareTo(targetEquityValue);
        if (cmpLo == 0) {
            return lo.setScale(6, RoundingMode.HALF_UP);
        }
        if (cmpLo > 0) {
            return null;
        }
        int cmpHi = pvHi.compareTo(targetEquityValue);
        if (cmpHi == 0) {
            return hi.setScale(6, RoundingMode.HALF_UP);
        }
        if (cmpHi < 0) {
            return null;
        }
        BigDecimal two = BigDecimal.valueOf(2);
        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            BigDecimal mid = lo.add(hi, MC).divide(two, MC);
            BigDecimal pvMid = presentValueTwoStage(baseFcf, mid, discountRate, terminalGrowth);
            if (pvMid == null) {
                return null;
            }
            int cmp = pvMid.compareTo(targetEquityValue);
            if (cmp == 0 || hi.subtract(lo, MC).compareTo(CONVERGENCE_TOL) <= 0) {
                return mid.setScale(6, RoundingMode.HALF_UP);
            }
            if (cmp < 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo.add(hi, MC).divide(two, 6, RoundingMode.HALF_UP);
    }

    private record GrowthSelection(BigDecimal stage1Growth, BigDecimal rawBenchmark, String benchmarkSource, String basisNote) {}

    private GrowthSelection selectStage1Growth(BigDecimal revCagr3y, BigDecimal fcfCagr3y, BigDecimal forwardGrowth) {
        BigDecimal historical = fcfCagr3y != null && revCagr3y != null
                ? fcfCagr3y.min(revCagr3y)
                : (fcfCagr3y != null ? fcfCagr3y : revCagr3y);
        String histSource = fcfCagr3y != null && revCagr3y != null
                ? (fcfCagr3y.compareTo(revCagr3y) <= 0 ? "HISTORICAL_FCF_CAGR_3Y" : "HISTORICAL_REV_CAGR_3Y")
                : (fcfCagr3y != null ? "HISTORICAL_FCF_CAGR_3Y" : (revCagr3y != null ? "HISTORICAL_REV_CAGR_3Y" : null));

        BigDecimal rawBenchmark = null;
        String benchmarkSource = null;
        if (historical != null && forwardGrowth != null) {
            if (historical.compareTo(forwardGrowth) <= 0) {
                rawBenchmark = historical;
                benchmarkSource = histSource;
            } else {
                rawBenchmark = forwardGrowth;
                benchmarkSource = "FORWARD_EXPECTED_GROWTH";
            }
        } else if (historical != null) {
            rawBenchmark = historical;
            benchmarkSource = histSource;
        } else if (forwardGrowth != null) {
            rawBenchmark = forwardGrowth;
            benchmarkSource = "FORWARD_EXPECTED_GROWTH";
        }

        if (rawBenchmark == null) {
            return new GrowthSelection(DEFAULT_FALLBACK_GROWTH, null, "DEFAULT_CONSERVATIVE_FALLBACK",
                    "缺少 3 年歷史複合成長與分析師預期，暫以保守基準 5.0% 估算。");
        }
        BigDecimal clamped = clamp(rawBenchmark, STAGE1_GROWTH_FLOOR, STAGE1_GROWTH_CAP).setScale(4, RoundingMode.HALF_UP);
        String note = "取歷史 3 年 CAGR 與前瞻預期成長之保守值（原值 "
                + rawBenchmark.multiply(HUNDRED, MC).setScale(2, RoundingMode.HALF_UP) + "%，區間限制 -10%～25%）。";
        return new GrowthSelection(clamped, rawBenchmark.setScale(6, RoundingMode.HALF_UP), benchmarkSource, note);
    }

    private DcfBranchDto computeBranch(String basis, BigDecimal baseFcfUsd, BigDecimal marketCapUsd,
                                       BigDecimal sharesAtDay, BigDecimal currentPrice,
                                       BigDecimal discountRate, GrowthSelection growth) {
        if (baseFcfUsd == null) {
            String reason = "SBC_ADJUSTED_FCF".equals(basis) ? "SBC_MISSING" : "FREE_CASH_FLOW_TTM_MISSING";
            return new DcfBranchDto(basis, MetricStatus.INSUFFICIENT_DATA.name(), reason,
                    null, null, null, null, null, null);
        }
        if (baseFcfUsd.signum() <= 0) {
            String reason = "SBC_ADJUSTED_FCF".equals(basis) ? "NON_POSITIVE_ADJ_FCF" : "DCF_NON_POSITIVE_FCF";
            return new DcfBranchDto(basis, MetricStatus.NOT_APPLICABLE.name(), reason,
                    baseFcfUsd.setScale(2, RoundingMode.HALF_UP), null, null, null, null, null);
        }

        BigDecimal baseG = growth.stage1Growth();
        BigDecimal consG = clamp(baseG.subtract(SCENARIO_STEP, MC), STAGE1_GROWTH_FLOOR, STAGE1_GROWTH_CAP);
        BigDecimal optG = clamp(baseG.add(SCENARIO_STEP, MC), STAGE1_GROWTH_FLOOR, STAGE1_GROWTH_CAP);

        DcfScenarioDto conservative = buildScenario(baseFcfUsd, consG, discountRate, sharesAtDay, currentPrice);
        DcfScenarioDto base = buildScenario(baseFcfUsd, baseG, discountRate, sharesAtDay, currentPrice);
        DcfScenarioDto optimistic = buildScenario(baseFcfUsd, optG, discountRate, sharesAtDay, currentPrice);

        ReverseDcfDto reverse = computeReverse(baseFcfUsd, marketCapUsd, discountRate, growth);

        return new DcfBranchDto(
                basis,
                MetricStatus.AVAILABLE.name(),
                null,
                baseFcfUsd.setScale(2, RoundingMode.HALF_UP),
                baseG.setScale(4, RoundingMode.HALF_UP),
                conservative,
                base,
                optimistic,
                reverse
        );
    }

    private DcfScenarioDto buildScenario(BigDecimal baseFcfUsd, BigDecimal stage1Growth, BigDecimal discountRate,
                                         BigDecimal sharesAtDay, BigDecimal currentPrice) {
        BigDecimal pv = presentValueTwoStage(baseFcfUsd, stage1Growth, discountRate, TERMINAL_GROWTH);
        if (pv == null) {
            return null;
        }
        BigDecimal perShare = pv.divide(sharesAtDay, 4, RoundingMode.HALF_UP);
        BigDecimal upside = perShare.subtract(currentPrice, MC).divide(currentPrice, 6, RoundingMode.HALF_UP);
        return new DcfScenarioDto(
                stage1Growth.setScale(4, RoundingMode.HALF_UP),
                pv.setScale(2, RoundingMode.HALF_UP),
                perShare,
                upside
        );
    }

    private ReverseDcfDto computeReverse(BigDecimal baseFcfUsd, BigDecimal marketCapUsd,
                                         BigDecimal discountRate, GrowthSelection growth) {
        BigDecimal implied = solveImpliedStage1Growth(baseFcfUsd, marketCapUsd, discountRate, TERMINAL_GROWTH);
        if (implied == null) {
            return new ReverseDcfDto(MetricStatus.NOT_APPLICABLE.name(), "REVERSE_DCF_OUT_OF_RANGE",
                    null, growth.rawBenchmark(), growth.benchmarkSource(), null, false);
        }
        BigDecimal gap = null;
        boolean stretched = false;
        if (growth.rawBenchmark() != null) {
            gap = implied.subtract(growth.rawBenchmark(), MC).setScale(6, RoundingMode.HALF_UP);
            stretched = gap.compareTo(EXPECTATION_STRETCHED_GAP) > 0;
        }
        return new ReverseDcfDto(
                MetricStatus.AVAILABLE.name(),
                null,
                implied,
                growth.rawBenchmark(),
                growth.benchmarkSource(),
                gap,
                stretched
        );
    }

    private static BigDecimal clamp(BigDecimal val, BigDecimal min, BigDecimal max) {
        if (val.compareTo(min) < 0) return min;
        if (val.compareTo(max) > 0) return max;
        return val;
    }
}
