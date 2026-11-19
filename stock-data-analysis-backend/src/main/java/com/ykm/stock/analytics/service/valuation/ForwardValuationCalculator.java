package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.KnownReportPeriod;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.market.MarketStatusService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基於已入庫市場共識預期（stock_earnings_forecast，支援多來源如 ALPHAVANTAGE 與 FUTU）的純記憶體前瞻倍數與預期修正計算器。
 * 嚴格遵守分軌隔離原則：不寫入財季分母表，不將單季預期乘數放大冒充年度，超過 45 天未更新標為 STALE。
 */
public class ForwardValuationCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_PEG_GROWTH = new BigDecimal("0.05");
    private static final BigDecimal MAX_PEG_GROWTH = BigDecimal.ONE;
    public static final long MAX_FORECAST_STALE_DAYS = 45L;
    public static final long REV_30D_MIN_DAYS = 20L;
    public static final long REV_30D_TARGET_DAYS = 30L;
    public static final long REV_30D_MAX_DAYS = 45L;
    public static final long REV_90D_MIN_DAYS = 65L;
    public static final long REV_90D_TARGET_DAYS = 90L;
    public static final long REV_90D_MAX_DAYS = 120L;
    private static final long FISCAL_END_TOLERANCE_DAYS = 7L;
    private static final long MAX_UNANCHORED_FY1_SPAN_DAYS = 375L;

    private static final Pattern PURE_ANNUAL_PATTERN = Pattern.compile("^(?:FY\\s*)?(20\\d{2})(?:\\s*FY)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUARTER_PATTERN_1 = Pattern.compile("^(?:FY\\s*)?(20\\d{2})\\s*Q([1-4])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUARTER_PATTERN_2 = Pattern.compile("^Q([1-4])\\s*(?:FY\\s*)?(20\\d{2})$", Pattern.CASE_INSENSITIVE);

    public record Inputs(
            LocalDate valuationDate,
            Instant cutoff,
            String quoteCurrency,
            BigDecimal closePrice,
            BigDecimal marketCapAtDay,
            String sharesReason,
            BigDecimal epsAtDay,
            String peReason,
            List<StockSplit> splits,
            String splitSyncError,
            List<StockEarningsForecast> latestForecasts,
            List<KnownReportPeriod> knownFutuReports,
            List<StockFinancialPeriod> storedPeriods
    ) {}

    public record SelectedRevisionTarget(
            StockEarningsForecast latest,
            String source,
            String fiscalPeriod,
            String periodType
    ) {}

    private record FiscalQuarterKey(int year, int quarter) implements Comparable<FiscalQuarterKey> {
        @Override
        public int compareTo(FiscalQuarterKey other) {
            int cmp = Integer.compare(this.year, other.year);
            return cmp != 0 ? cmp : Integer.compare(this.quarter, other.quarter);
        }
    }

    private record AnnualAnchor(int fiscalYear, LocalDate periodEnd) {}

    private record QualifiedAnnual(StockEarningsForecast forecast, int fiscalYear, LocalDate effectiveEnd) {}

    private record QualifiedQuarter(StockEarningsForecast forecast, FiscalQuarterKey key) {}

    private record AnnualSelection(
            QualifiedAnnual fy1,
            QualifiedAnnual fy2,
            String unavailableReasonCode
    ) {}

    private record ReportedHistoryContext(
            Set<Integer> reportedAnnualYears,
            List<LocalDate> reportedAnnualEnds,
            Set<FiscalQuarterKey> reportedQuarters,
            FiscalQuarterKey maxReportedQuarter,
            AnnualAnchor latestAnnualAnchor
    ) {}

    public static final String REV_BASIS_LOCAL_HISTORY = "LOCAL_HISTORY";
    public static final String REV_BASIS_PROVIDER_TRAILING = "PROVIDER_TRAILING";

    private record AlignedEpsResult(BigDecimal value, MetricStatus failStatus, String failCode, String failMessage) {}

    private record EpsRevisionOutcome(MetricResult<BigDecimal> result, LocalDate baseDate, String basis) {}

    /** 第一步：挑出需要載入歷史序列的單一未公布修正追蹤目標（優先未陳舊之未結束 FY1，次選未陳舊單季，僅剩陳舊列時回傳供標記 STALE）。 */
    public SelectedRevisionTarget selectRevisionTarget(Inputs in) {
        if (in == null || in.valuationDate() == null || in.latestForecasts() == null || in.latestForecasts().isEmpty()) {
            return null;
        }
        LocalDate maxObsDay = resolveMaxObservationDate(in.valuationDate(), in.cutoff());
        ReportedHistoryContext historyCtx = buildReportedContext(in.valuationDate(), in.cutoff(), in.knownFutuReports(), in.storedPeriods());
        AnnualSelection annualSel = selectAnnualForecasts(in.valuationDate(), maxObsDay, in.latestForecasts(), historyCtx);
        StockEarningsForecast chosen = chooseRevisionForecast(in.valuationDate(), maxObsDay, annualSel, in.latestForecasts(), historyCtx);
        if (chosen != null) {
            return new SelectedRevisionTarget(chosen, chosen.getSource(), chosen.getFiscalPeriod(), chosen.getPeriodType());
        }
        return null;
    }

    /** 第二步：結合已載入之修正目標歷史觀察列，純記憶體產出完整 ForwardMetrics。 */
    public ForwardMetrics calculate(Inputs in, List<StockEarningsForecast> revisionHistory) {
        if (in == null || in.valuationDate() == null) {
            return null;
        }
        LocalDate day = in.valuationDate();
        LocalDate maxObsDay = resolveMaxObservationDate(day, in.cutoff());
        List<StockEarningsForecast> latestList = in.latestForecasts() == null ? List.of() : in.latestForecasts();
        List<StockSplit> splits = in.splits() == null ? List.of() : in.splits();

        if (latestList.isEmpty()) {
            MetricResult<BigDecimal> missing = unavailable(MetricStatus.INSUFFICIENT_DATA,
                    "EARNINGS_FORECAST_MISSING", "尚無已入庫的市場盈利預期紀錄。");
            return new ForwardMetrics(day, null, null, null, null,
                    missing, missing, missing, null, missing,
                    null, null, null, null, null, null, null, null, null, null, null,
                    missing, missing, missing, missing);
        }

        ReportedHistoryContext historyCtx = buildReportedContext(day, in.cutoff(), in.knownFutuReports(), in.storedPeriods());
        AnnualSelection annualSel = selectAnnualForecasts(day, maxObsDay, latestList, historyCtx);

        String targetFiscalPeriod = null;
        LocalDate forecastObsDate = null;
        String forecastCurrency = null;
        String forecastSource = null;
        MetricResult<BigDecimal> forwardPe;
        MetricResult<BigDecimal> forwardPs;
        MetricResult<BigDecimal> forwardPeg;
        String forwardPegBasis = null;
        MetricResult<BigDecimal> impliedEpsGrowth;

        if (annualSel.fy1() == null) {
            String code = annualSel.unavailableReasonCode() != null
                    ? annualSel.unavailableReasonCode() : "ANNUAL_EARNINGS_FORECAST_MISSING";
            MetricStatus status = statusOfCode(code);
            String msg = "FORECAST_FISCAL_PERIOD_UNVERIFIED".equals(code)
                    ? "年度預期缺少明確期末日且無法由已知年報核實尚未結束，暫不計算前瞻倍數。"
                    : "目前僅有單季財報日曆預期或無未結束年度預期，不以單季推算年度前瞻倍數。";
            MetricResult<BigDecimal> noAnnual = unavailable(status, code, msg);
            forwardPe = noAnnual;
            forwardPs = noAnnual;
            forwardPeg = noAnnual;
            impliedEpsGrowth = noAnnual;
        } else {
            StockEarningsForecast fy1 = annualSel.fy1().forecast();
            targetFiscalPeriod = fy1.getFiscalPeriod();
            forecastObsDate = fy1.getObservationDate();
            forecastCurrency = fy1.getCurrency();
            forecastSource = fy1.getSource();
            long ageDays = ChronoUnit.DAYS.between(fy1.getObservationDate(), day);

            if (ageDays > MAX_FORECAST_STALE_DAYS) {
                MetricResult<BigDecimal> stale = unavailable(MetricStatus.STALE,
                        "EARNINGS_FORECAST_STALE", "市場預期觀察距估值日已逾 45 天未更新，不作為當期前瞻估值依據。");
                forwardPe = stale;
                forwardPs = stale;
                forwardPeg = stale;
                impliedEpsGrowth = stale;
            } else if (!sameCurrency(in.quoteCurrency(), fy1.getCurrency())) {
                MetricResult<BigDecimal> mismatch = unavailable(MetricStatus.UNVERIFIED,
                        "REPORTING_CURRENCY_MISMATCH", "市場預期幣別與股價交易幣別不一致，不自動換算匯率。");
                forwardPe = mismatch;
                forwardPs = mismatch;
                forwardPeg = mismatch;
                impliedEpsGrowth = mismatch;
            } else {
                AlignedEpsResult alignedFy1 = alignForecastEps(fy1.getEps(), fy1.getObservationDate(), day, splits, in.splitSyncError());
                forwardPe = computeForwardPe(in.closePrice(), alignedFy1);
                forwardPs = computeForwardPs(in.marketCapAtDay(), in.sharesReason(), fy1.getRevenue());
                impliedEpsGrowth = computeImpliedGrowthVsTtm(alignedFy1, in.epsAtDay(), in.peReason());

                PegOutcome pegOutcome = computeForwardPeg(
                        forwardPe, impliedEpsGrowth, alignedFy1,
                        annualSel.fy2(), day, fy1.getCurrency(), splits, in.splitSyncError());
                forwardPeg = pegOutcome.result();
                forwardPegBasis = pegOutcome.basis();
            }
        }

        // 計算 30D / 90D 同財期預期修正（優先未陳舊之未結束 FY1，否則取最近未陳舊單季）
        StockEarningsForecast revTarget = chooseRevisionForecast(day, maxObsDay, annualSel, latestList, historyCtx);

        if (revTarget == null) {
            MetricResult<BigDecimal> noUnreported = unavailable(MetricStatus.INSUFFICIENT_DATA,
                    "NO_UNREPORTED_FORECAST_PERIOD", "目前無合資格未公布財期可供修正比較。");
            return new ForwardMetrics(day, targetFiscalPeriod, forecastObsDate, forecastCurrency, forecastSource,
                    forwardPe, forwardPs, forwardPeg, forwardPegBasis, impliedEpsGrowth,
                    null, null, null, null, null, null, null, null, null, null, null,
                    noUnreported, noUnreported, noUnreported, noUnreported);
        }

        LocalDate revObsDate = revTarget.getObservationDate();
        String revCurrency = revTarget.getCurrency();
        String revSource = revTarget.getSource();
        long revAgeDays = ChronoUnit.DAYS.between(revObsDate, day);
        if (revAgeDays > MAX_FORECAST_STALE_DAYS) {
            MetricResult<BigDecimal> staleRev = unavailable(MetricStatus.STALE,
                    "EARNINGS_FORECAST_STALE", "該財期最新預期觀察距估值日已逾 45 天，暫不計算近期預期修正。");
            return new ForwardMetrics(day, targetFiscalPeriod, forecastObsDate, forecastCurrency, forecastSource,
                    forwardPe, forwardPs, forwardPeg, forwardPegBasis, impliedEpsGrowth,
                    revTarget.getFiscalPeriod(), revTarget.getPeriodType(), revCurrency, revSource, revObsDate,
                    null, null, null, null, null, null,
                    staleRev, staleRev, staleRev, staleRev);
        }

        List<StockEarningsForecast> dedupHistory = deduplicateDailyHistory(
                revisionHistory, revSource, revTarget.getFiscalPeriod(), revTarget.getPeriodType(), revCurrency, revObsDate);
        StockEarningsForecast base30Eps = findClosestBaseline(
                dedupHistory, revObsDate, REV_30D_MIN_DAYS, REV_30D_MAX_DAYS, REV_30D_TARGET_DAYS, row -> row.getEps() != null);
        StockEarningsForecast base90Eps = findClosestBaseline(
                dedupHistory, revObsDate, REV_90D_MIN_DAYS, REV_90D_MAX_DAYS, REV_90D_TARGET_DAYS, row -> row.getEps() != null);
        StockEarningsForecast base30Rev = findClosestBaseline(
                dedupHistory, revObsDate, REV_30D_MIN_DAYS, REV_30D_MAX_DAYS, REV_30D_TARGET_DAYS, row -> row.getRevenue() != null);
        StockEarningsForecast base90Rev = findClosestBaseline(
                dedupHistory, revObsDate, REV_90D_MIN_DAYS, REV_90D_MAX_DAYS, REV_90D_TARGET_DAYS, row -> row.getRevenue() != null);

        EpsRevisionOutcome epsRev30 = computeEpsRevision(
                revTarget, base30Eps, revTarget.getEps30dAgo(), REV_30D_TARGET_DAYS, splits, in.splitSyncError(), "30");
        EpsRevisionOutcome epsRev90 = computeEpsRevision(
                revTarget, base90Eps, revTarget.getEps90dAgo(), REV_90D_TARGET_DAYS, splits, in.splitSyncError(), "90");
        MetricResult<BigDecimal> revRev30 = computeRevenueRevision(revTarget, base30Rev, "30");
        MetricResult<BigDecimal> revRev90 = computeRevenueRevision(revTarget, base90Rev, "90");

        LocalDate epsBaseDate30 = epsRev30.baseDate();
        LocalDate epsBaseDate90 = epsRev90.baseDate();
        LocalDate revBaseDate30 = base30Rev != null ? base30Rev.getObservationDate() : null;
        LocalDate revBaseDate90 = base90Rev != null ? base90Rev.getObservationDate() : null;

        return new ForwardMetrics(
                day,
                targetFiscalPeriod,
                forecastObsDate,
                forecastCurrency,
                forecastSource,
                forwardPe,
                forwardPs,
                forwardPeg,
                forwardPegBasis,
                impliedEpsGrowth,
                revTarget.getFiscalPeriod(),
                revTarget.getPeriodType(),
                revCurrency,
                revSource,
                revObsDate,
                epsBaseDate30,
                epsBaseDate90,
                revBaseDate30,
                revBaseDate90,
                epsRev30.basis(),
                epsRev90.basis(),
                epsRev30.result(),
                epsRev90.result(),
                revRev30,
                revRev90
        );
    }

    private MetricResult<BigDecimal> computeForwardPe(BigDecimal closePrice, AlignedEpsResult alignedEps) {
        if (closePrice == null || closePrice.signum() <= 0) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "CLOSING_PRICE_MISSING", "缺少有效收盤價，無法計算 Forward P/E。");
        }
        if (alignedEps.value() == null) {
            return unavailable(alignedEps.failStatus(), alignedEps.failCode(), alignedEps.failMessage());
        }
        if (alignedEps.value().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_FORWARD_EPS", "市場預期年度 EPS 非正（預期虧損或零利潤），Forward P/E 不適用（N.M.）。");
        }
        return MetricResult.available(closePrice.divide(alignedEps.value(), 4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeForwardPs(BigDecimal marketCapAtDay, String sharesReason, BigDecimal forwardRevenue) {
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日總市值，無法計算 Forward P/S。");
        }
        if (forwardRevenue == null) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "FORWARD_REVENUE_MISSING", "市場預期缺少年度營業收入預估值。");
        }
        if (forwardRevenue.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_FORWARD_REVENUE", "市場預期年度營業收入非正，Forward P/S 不適用（N.M.）。");
        }
        return MetricResult.available(marketCapAtDay.divide(forwardRevenue, 4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeImpliedGrowthVsTtm(AlignedEpsResult alignedFy1, BigDecimal epsAtDay, String peReason) {
        if (alignedFy1.value() == null) {
            return unavailable(alignedFy1.failStatus(), alignedFy1.failCode(), alignedFy1.failMessage());
        }
        if (epsAtDay == null) {
            String code = peReason != null ? peReason : "DILUTED_EPS_TTM_MISSING";
            return unavailable(statusOfCode(code), code, "缺少當期拆股對齊 TTM EPS，無法計算預期隱含成長率。");
        }
        if (epsAtDay.signum() > 0 && alignedFy1.value().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "FORWARD_SWUNG_TO_LOSS", "當期 TTM 獲利但市場預期年度轉為虧損或零盈利。");
        }
        if (epsAtDay.signum() <= 0 && alignedFy1.value().signum() > 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "TURNAROUND_FROM_LOSS", "當期 TTM 非正、預期轉盈，基期非正不計算百分比成長率。");
        }
        if (epsAtDay.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_BASE_EPS", "當期 TTM 與預期年度 EPS 皆非正，不適用成長率。");
        }
        BigDecimal growth = alignedFy1.value().subtract(epsAtDay, MC).divide(epsAtDay, 6, RoundingMode.HALF_UP);
        return MetricResult.available(growth);
    }

    private record PegOutcome(MetricResult<BigDecimal> result, String basis) {}

    private PegOutcome computeForwardPeg(
            MetricResult<BigDecimal> forwardPe,
            MetricResult<BigDecimal> impliedVsTtm,
            AlignedEpsResult alignedFy1,
            QualifiedAnnual fy2Candidate,
            LocalDate valuationDate,
            String fy1Currency,
            List<StockSplit> splits,
            String splitSyncError) {
        if (forwardPe.status() != MetricStatus.AVAILABLE || forwardPe.value() == null || forwardPe.value().signum() <= 0) {
            String code = !forwardPe.reasons().isEmpty() ? forwardPe.reasons().getFirst().code() : "NON_POSITIVE_FORWARD_EPS";
            return new PegOutcome(unavailable(forwardPe.status(), code, "Forward P/E 非正或不可用，不計算 Forward PEG。"), null);
        }

        BigDecimal chosenGrowth = null;
        String chosenBasis = null;

        if (impliedVsTtm.status() == MetricStatus.AVAILABLE && impliedVsTtm.value() != null) {
            chosenGrowth = impliedVsTtm.value();
            chosenBasis = "FORWARD_FY1_VS_TTM";
        }

        // 若當期 TTM 虧損轉盈（或缺少 TTM EPS）使 FY1 vs TTM 不可用，嘗試使用已核實之同幣別未陳舊 FY2 vs FY1 成長率
        if (chosenGrowth == null && fy2Candidate != null && alignedFy1.value() != null && alignedFy1.value().signum() > 0) {
            StockEarningsForecast fy2 = fy2Candidate.forecast();
            long fy2Age = ChronoUnit.DAYS.between(fy2.getObservationDate(), valuationDate);
            if (fy2Age <= MAX_FORECAST_STALE_DAYS && sameCurrency(fy1Currency, fy2.getCurrency())) {
                AlignedEpsResult alignedFy2 = alignForecastEps(fy2.getEps(), fy2.getObservationDate(), valuationDate, splits, splitSyncError);
                if (alignedFy2.value() != null && alignedFy2.value().signum() > 0) {
                    chosenGrowth = alignedFy2.value().subtract(alignedFy1.value(), MC)
                            .divide(alignedFy1.value(), 6, RoundingMode.HALF_UP);
                    chosenBasis = "FORWARD_FY2_VS_FY1";
                }
            }
        }

        if (chosenGrowth == null) {
            String code = !impliedVsTtm.reasons().isEmpty() ? impliedVsTtm.reasons().getFirst().code() : "DILUTED_EPS_TTM_MISSING";
            return new PegOutcome(unavailable(impliedVsTtm.status(), code, "缺少可比預期 EPS 成長率基準，無法計算 Forward PEG。"), null);
        }
        if (chosenGrowth.compareTo(MIN_PEG_GROWTH) < 0) {
            return new PegOutcome(unavailable(MetricStatus.NOT_APPLICABLE, "FORWARD_EPS_GROWTH_BELOW_PEG_THRESHOLD",
                    "預期 EPS 隱含成長率低於 5% 門檻，為防極小分母扭曲倍數不計算 Forward PEG。"), null);
        }
        if (chosenGrowth.compareTo(MAX_PEG_GROWTH) > 0) {
            return new PegOutcome(unavailable(MetricStatus.NOT_APPLICABLE, "FORWARD_EPS_GROWTH_ABOVE_PEG_CAP",
                    "預期 EPS 隱含成長率超過 100% 上限（常見於低基期復甦），不計算 Forward PEG。"), null);
        }
        BigDecimal peg = forwardPe.value().divide(chosenGrowth.multiply(HUNDRED, MC), 4, RoundingMode.HALF_UP);
        return new PegOutcome(MetricResult.available(peg), chosenBasis);
    }

    private EpsRevisionOutcome computeEpsRevision(
            StockEarningsForecast latest, StockEarningsForecast baseline, BigDecimal providerBaselineEps,
            long windowDays, List<StockSplit> splits, String splitSyncError, String windowLabel) {
        if (baseline == null) {
            if (providerBaselineEps == null) {
                return new EpsRevisionOutcome(unavailable(MetricStatus.INSUFFICIENT_DATA, "FORECAST_REVISION_BASE_MISSING",
                        "此財期尚無滿 " + windowLabel + " 日區間之可比歷史觀察基準。"), null, null);
            }
            if (latest.getEps() == null) {
                return new EpsRevisionOutcome(unavailable(MetricStatus.INSUFFICIENT_DATA, "FORWARD_EPS_MISSING",
                        "最新觀察缺少預期 EPS 數值。"), null, null);
            }
            LocalDate approxBaseDate = latest.getObservationDate().minusDays(windowDays);
            if (splitSyncError != null) {
                return new EpsRevisionOutcome(unavailable(statusOfCode(splitSyncError), splitSyncError,
                        "拆股紀錄同步異常，無法核實來源 " + windowLabel + " 日前預期 EPS 拆股口徑。"), null, null);
            }
            if (hasSplitInWindow(splits, approxBaseDate, latest.getObservationDate())) {
                return new EpsRevisionOutcome(unavailable(MetricStatus.UNVERIFIED, "EPS_SPLIT_BASIS_UNCONFIRMED",
                        "近 " + windowLabel + " 日內發生拆股且來源回溯預期 EPS 之拆股口徑未核實，暫不計算修正率。"), null, null);
            }
            if (providerBaselineEps.signum() <= 0) {
                return new EpsRevisionOutcome(unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_PRIOR_FORECAST_EPS",
                        "基期預期 EPS 為虧損或零，不適用百分比修正率。"), approxBaseDate, REV_BASIS_PROVIDER_TRAILING);
            }
            BigDecimal rev = latest.getEps().subtract(providerBaselineEps, MC)
                    .divide(providerBaselineEps, 6, RoundingMode.HALF_UP);
            return new EpsRevisionOutcome(MetricResult.available(rev), approxBaseDate, REV_BASIS_PROVIDER_TRAILING);
        }
        if (latest.getEps() == null || baseline.getEps() == null) {
            return new EpsRevisionOutcome(unavailable(MetricStatus.INSUFFICIENT_DATA, "FORWARD_EPS_MISSING",
                    "最新或基期觀察缺少預期 EPS 數值。"), baseline.getObservationDate(), REV_BASIS_LOCAL_HISTORY);
        }
        AlignedEpsResult alignedBase = alignForecastEps(
                baseline.getEps(), baseline.getObservationDate(), latest.getObservationDate(), splits, splitSyncError);
        if (alignedBase.value() == null) {
            return new EpsRevisionOutcome(unavailable(alignedBase.failStatus(), alignedBase.failCode(), alignedBase.failMessage()),
                    baseline.getObservationDate(), REV_BASIS_LOCAL_HISTORY);
        }
        if (alignedBase.value().signum() <= 0) {
            return new EpsRevisionOutcome(unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_PRIOR_FORECAST_EPS",
                    "基期預期 EPS 為虧損或零，不適用百分比修正率。"), baseline.getObservationDate(), REV_BASIS_LOCAL_HISTORY);
        }
        BigDecimal rev = latest.getEps().subtract(alignedBase.value(), MC)
                .divide(alignedBase.value(), 6, RoundingMode.HALF_UP);
        return new EpsRevisionOutcome(MetricResult.available(rev), baseline.getObservationDate(), REV_BASIS_LOCAL_HISTORY);
    }

    private boolean hasSplitInWindow(List<StockSplit> splits, LocalDate fromExclusive, LocalDate toInclusive) {
        if (splits == null || splits.isEmpty() || fromExclusive == null || toInclusive == null) {
            return false;
        }
        for (StockSplit split : splits) {
            if (split != null && split.getExecutionDate() != null
                    && split.getExecutionDate().isAfter(fromExclusive)
                    && !split.getExecutionDate().isAfter(toInclusive)) {
                return true;
            }
        }
        return false;
    }

    private MetricResult<BigDecimal> computeRevenueRevision(
            StockEarningsForecast latest, StockEarningsForecast baseline, String windowLabel) {
        if (baseline == null) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "FORECAST_REVISION_BASE_MISSING",
                    "此財期尚無滿 " + windowLabel + " 日區間之可比歷史觀察基準。");
        }
        if (latest.getRevenue() == null || baseline.getRevenue() == null) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "FORWARD_REVENUE_MISSING", "最新或基期觀察缺少預期營業收入數值。");
        }
        if (baseline.getRevenue().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_PRIOR_FORECAST_REVENUE",
                    "基期預期營業收入非正，不適用百分比修正率。");
        }
        BigDecimal rev = latest.getRevenue().subtract(baseline.getRevenue(), MC)
                .divide(baseline.getRevenue(), 6, RoundingMode.HALF_UP);
        return MetricResult.available(rev);
    }

    private AlignedEpsResult alignForecastEps(
            BigDecimal rawEps, LocalDate fromDate, LocalDate targetDate,
            List<StockSplit> splits, String splitSyncError) {
        if (rawEps == null) {
            return new AlignedEpsResult(null, MetricStatus.INSUFFICIENT_DATA, "FORWARD_EPS_MISSING", "市場預期缺少 EPS 預估值。");
        }
        if (fromDate == null || targetDate == null || fromDate.equals(targetDate)) {
            return new AlignedEpsResult(rawEps, null, null, null);
        }
        if (splitSyncError != null) {
            return new AlignedEpsResult(null, MetricStatus.INSUFFICIENT_DATA, splitSyncError,
                    "拆股紀錄同步異常且預期觀察日與目標基準日不同，暫不換算跨日預期 EPS。");
        }
        BigDecimal factor = StockSplitCalculator.shareFactor(splits, fromDate, targetDate);
        if (factor.signum() <= 0) {
            return new AlignedEpsResult(null, MetricStatus.UNVERIFIED, "EPS_SPLIT_BASIS_UNCONFIRMED", "跨日拆股因子無效。");
        }
        return new AlignedEpsResult(rawEps.divide(factor, MC), null, null, null);
    }

    private List<StockEarningsForecast> deduplicateDailyHistory(
            List<StockEarningsForecast> history, String source, String fiscalPeriod, String periodType,
            String currency, LocalDate latestObsDate) {
        if (history == null || history.isEmpty()) return List.of();
        Map<LocalDate, StockEarningsForecast> byDate = new LinkedHashMap<>();
        for (StockEarningsForecast row : history) {
            if (row == null || row.getObservationDate() == null || !row.getObservationDate().isBefore(latestObsDate)) {
                continue;
            }
            if (!sameSource(source, row.getSource())
                    || !Objects.equals(fiscalPeriod, row.getFiscalPeriod())
                    || !Objects.equals(periodType, row.getPeriodType())
                    || !sameCurrency(currency, row.getCurrency())) {
                continue;
            }
            StockEarningsForecast existing = byDate.get(row.getObservationDate());
            if (existing == null || (row.getObservedAt() != null
                    && (existing.getObservedAt() == null || row.getObservedAt().isAfter(existing.getObservedAt())))) {
                byDate.put(row.getObservationDate(), row);
            }
        }
        return List.copyOf(byDate.values());
    }

    private StockEarningsForecast findClosestBaseline(
            List<StockEarningsForecast> candidates, LocalDate anchorDate,
            long minDays, long maxDays, long targetDays, Predicate<StockEarningsForecast> filter) {
        StockEarningsForecast best = null;
        long bestDiff = Long.MAX_VALUE;
        for (StockEarningsForecast row : candidates) {
            if (!filter.test(row)) continue;
            long gap = ChronoUnit.DAYS.between(row.getObservationDate(), anchorDate);
            if (gap < minDays || gap > maxDays) continue;
            long diff = Math.abs(gap - targetDays);
            if (diff < bestDiff || (diff == bestDiff && best != null && row.getObservationDate().isBefore(best.getObservationDate()))) {
                best = row;
                bestDiff = diff;
            }
        }
        return best;
    }

    private ReportedHistoryContext buildReportedContext(
            LocalDate valuationDate, Instant cutoff,
            List<KnownReportPeriod> knownFutuReports, List<StockFinancialPeriod> storedPeriods) {
        Set<Integer> annualYears = new HashSet<>();
        List<LocalDate> annualEnds = new ArrayList<>();
        Set<FiscalQuarterKey> quarters = new HashSet<>();
        FiscalQuarterKey maxQuarter = null;
        AnnualAnchor latestAnchor = null;

        if (knownFutuReports != null) {
            for (KnownReportPeriod r : knownFutuReports) {
                if (r == null || r.periodEnd() == null) continue;
                if (cutoff != null && r.observedAt() != null && r.observedAt().isAfter(cutoff)) continue;
                Integer fy = r.fiscalYear();
                FiscalQuarterKey parsedQ = parseQuarterKey(r.period());
                if (fy == null && parsedQ != null) fy = parsedQ.year();

                if (r.periodType() == 7 || r.periodType() == 4) {
                    annualEnds.add(r.periodEnd());
                    if (fy != null) {
                        annualYears.add(fy);
                        if (latestAnchor == null || fy > latestAnchor.fiscalYear()
                                || (fy == latestAnchor.fiscalYear() && r.periodEnd().isAfter(latestAnchor.periodEnd()))) {
                            latestAnchor = new AnnualAnchor(fy, r.periodEnd());
                        }
                    }
                }
                int qNum = r.periodType() >= 1 && r.periodType() <= 4 ? r.periodType() : (parsedQ != null ? parsedQ.quarter() : 0);
                if (fy != null && qNum >= 1 && qNum <= 4) {
                    FiscalQuarterKey qk = new FiscalQuarterKey(fy, qNum);
                    quarters.add(qk);
                    if (maxQuarter == null || qk.compareTo(maxQuarter) > 0) maxQuarter = qk;
                }
            }
        }

        if (storedPeriods != null) {
            for (StockFinancialPeriod p : storedPeriods) {
                if (!isPeriodReportedAtCutoff(p, valuationDate, cutoff)) continue;
                Integer fy = p.getFiscalYear();
                Integer fq = p.getFiscalQuarter();
                boolean annualSpan = Integer.valueOf(4).equals(fq)
                        || (p.getPeriodStart() != null && ChronoUnit.DAYS.between(p.getPeriodStart(), p.getPeriodEnd()) >= 350);
                if (annualSpan) {
                    annualEnds.add(p.getPeriodEnd());
                    if (fy != null) {
                        annualYears.add(fy);
                        if (latestAnchor == null || fy > latestAnchor.fiscalYear()
                                || (fy == latestAnchor.fiscalYear() && p.getPeriodEnd().isAfter(latestAnchor.periodEnd()))) {
                            latestAnchor = new AnnualAnchor(fy, p.getPeriodEnd());
                        }
                    }
                }
                if (fy != null && fq != null && fq >= 1 && fq <= 4) {
                    FiscalQuarterKey qk = new FiscalQuarterKey(fy, fq);
                    quarters.add(qk);
                    if (maxQuarter == null || qk.compareTo(maxQuarter) > 0) maxQuarter = qk;
                }
            }
        }
        return new ReportedHistoryContext(annualYears, annualEnds, quarters, maxQuarter, latestAnchor);
    }

    /** 判定標準化財季列於指定估值日與觀察截點是否已公開披露（供前瞻估值與歷史股數配對共用）。 */
    public static boolean isPeriodReportedAtCutoff(StockFinancialPeriod p, LocalDate valuationDate, Instant cutoff) {
        if (p == null || p.getPeriodEnd() == null || p.getPeriodEnd().isAfter(valuationDate)) {
            return false;
        }
        boolean wholeRowObserved = p.getAvailableAt() != null && (cutoff == null || !p.getAvailableAt().isAfter(cutoff));
        boolean wholeRowSecEffective = p.getSecAvailableFromDate() == null || !p.getSecAvailableFromDate().isAfter(valuationDate);
        if (wholeRowObserved && wholeRowSecEffective) {
            return true;
        }
        if (p.getSecPeriodicObservedAt() != null
                && (cutoff == null || !p.getSecPeriodicObservedAt().isAfter(cutoff))
                && p.getSecPeriodicAvailableFromDate() != null
                && !p.getSecPeriodicAvailableFromDate().isAfter(valuationDate)) {
            return true;
        }
        if (p.getEpsTtmAvailableAt() != null && (cutoff == null || !p.getEpsTtmAvailableAt().isAfter(cutoff))) {
            boolean epsUsesSec = p.getEpsTtmSource() != null && p.getEpsTtmSource().contains("SEC");
            if (!epsUsesSec) {
                return true;
            }
            LocalDate epsSecFrom = p.getEpsTtmSecAvailableFromDate() != null
                    ? p.getEpsTtmSecAvailableFromDate() : p.getSecAvailableFromDate();
            if (epsSecFrom == null || !epsSecFrom.isAfter(valuationDate)) {
                return true;
            }
        }
        return isNonSecComponentObserved(p.getRevenueTtmAvailableAt(), p.getRevenueTtmSource(), cutoff)
                || isNonSecComponentObserved(p.getNetIncomeCommonTtmAvailableAt(), p.getNetIncomeCommonTtmSource(), cutoff)
                || isNonSecComponentObserved(p.getOperatingCashFlowTtmAvailableAt(), p.getOperatingCashFlowTtmSource(), cutoff)
                || isNonSecComponentObserved(p.getFreeCashFlowTtmAvailableAt(), p.getFreeCashFlowTtmSource(), cutoff)
                || isNonSecComponentObserved(p.getEbitdaTtmAvailableAt(), p.getEbitdaTtmSource(), cutoff)
                || isNonSecComponentObserved(p.getCommonEquityAvailableAt(), p.getCommonEquitySource(), cutoff);
    }

    private static boolean isNonSecComponentObserved(Instant availableAt, String source, Instant cutoff) {
        if (availableAt == null || (cutoff != null && availableAt.isAfter(cutoff))) {
            return false;
        }
        return source == null || !source.contains("SEC");
    }

    private LocalDate resolveMaxObservationDate(LocalDate valuationDate, Instant cutoff) {
        return cutoff == null ? valuationDate : cutoff.atZone(MarketStatusService.NY_ZONE).toLocalDate();
    }

    private QualifiedAnnual pickBestForTargetFiscalYear(List<QualifiedAnnual> candidates, LocalDate valuationDate, Integer expectedFy) {
        int targetFy = expectedFy != null ? expectedFy
                : candidates.stream().min(annualCandidateComparator()).orElseThrow().fiscalYear();
        List<QualifiedAnnual> sameFy = candidates.stream()
                .filter(q -> q.fiscalYear() == targetFy)
                .toList();
        List<QualifiedAnnual> pool = sameFy.isEmpty() ? candidates : sameFy;
        Comparator<QualifiedAnnual> freshnessFirst = Comparator
                .comparing((QualifiedAnnual q) -> q.forecast().getObservationDate() != null
                        && ChronoUnit.DAYS.between(q.forecast().getObservationDate(), valuationDate) <= MAX_FORECAST_STALE_DAYS,
                        Comparator.reverseOrder())
                .thenComparing(q -> q.forecast().getObservationDate(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(q -> q.forecast().getObservedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(QualifiedAnnual::effectiveEnd)
                .thenComparing(q -> q.forecast().getSource(), Comparator.nullsLast(String::compareToIgnoreCase));
        return pool.stream().min(freshnessFirst).orElseThrow();
    }

    private Comparator<QualifiedAnnual> annualCandidateComparator() {
        return Comparator.comparing(QualifiedAnnual::effectiveEnd)
                .thenComparingInt(QualifiedAnnual::fiscalYear)
                .thenComparing(q -> q.forecast().getObservationDate(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(q -> q.forecast().getObservedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(q -> q.forecast().getSource(), Comparator.nullsLast(String::compareToIgnoreCase));
    }

    private Comparator<QualifiedQuarter> quarterCandidateComparator() {
        return Comparator.comparing(QualifiedQuarter::key)
                .thenComparing(q -> q.forecast().getObservationDate(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(q -> q.forecast().getObservedAt(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(q -> q.forecast().getSource(), Comparator.nullsLast(String::compareToIgnoreCase));
    }

    private StockEarningsForecast chooseRevisionForecast(
            LocalDate valuationDate, LocalDate maxObsDay, AnnualSelection annualSel,
            List<StockEarningsForecast> latestList, ReportedHistoryContext ctx) {
        StockEarningsForecast fy1 = annualSel.fy1() != null ? annualSel.fy1().forecast() : null;
        if (fy1 != null && fy1.getObservationDate() != null
                && ChronoUnit.DAYS.between(fy1.getObservationDate(), valuationDate) <= MAX_FORECAST_STALE_DAYS) {
            return fy1;
        }
        QualifiedQuarter nextQ = selectNextUnreportedQuarter(valuationDate, maxObsDay, latestList, ctx);
        if (nextQ != null && nextQ.forecast().getObservationDate() != null
                && ChronoUnit.DAYS.between(nextQ.forecast().getObservationDate(), valuationDate) <= MAX_FORECAST_STALE_DAYS) {
            return nextQ.forecast();
        }
        if (fy1 != null) {
            return fy1;
        }
        return nextQ != null ? nextQ.forecast() : null;
    }

    private AnnualSelection selectAnnualForecasts(
            LocalDate valuationDate, LocalDate maxObsDay, List<StockEarningsForecast> latestList, ReportedHistoryContext ctx) {
        boolean hasAnyAnnual = false;
        List<QualifiedAnnual> explicitSpanning = new ArrayList<>();
        Map<Integer, StockEarningsForecast> explicitAnnualByFy = new LinkedHashMap<>();
        for (StockEarningsForecast f : latestList) {
            if (f == null || !"ANNUAL".equalsIgnoreCase(f.getPeriodType())) continue;
            if (f.getObservationDate() == null || f.getObservationDate().isAfter(maxObsDay)) continue;
            hasAnyAnnual = true;
            Integer fy = parsePureAnnualYear(f.getFiscalPeriod());
            if (f.getPeriodEnd() != null) {
                if (fy != null) {
                    explicitAnnualByFy.putIfAbsent(fy, f);
                }
                if (f.getPeriodStart() != null
                        && !valuationDate.isBefore(f.getPeriodStart())
                        && valuationDate.isBefore(f.getPeriodEnd())
                        && !isAlreadyReportedEnd(f.getPeriodEnd(), ctx.reportedAnnualEnds())
                        && (fy == null || !isAnnualAlreadyReported(fy, ctx))) {
                    int sortFy = fy != null ? fy : f.getPeriodEnd().getYear();
                    explicitSpanning.add(new QualifiedAnnual(f, sortFy, f.getPeriodEnd()));
                }
            }
        }
        if (!hasAnyAnnual) {
            return new AnnualSelection(null, null, "ANNUAL_EARNINGS_FORECAST_MISSING");
        }

        // 若存在明示起迄涵蓋當日之合格年度預期，優先選為當前 FY1（避免改年結日公司被舊財年錨點推估列搶走 FY1），同財年優先選新鮮來源
        if (!explicitSpanning.isEmpty()) {
            QualifiedAnnual first = pickBestForTargetFiscalYear(explicitSpanning, valuationDate, null);
            QualifiedAnnual second = selectFollowUpFy2(maxObsDay, first, latestList, ctx);
            return new AnnualSelection(first, second, null);
        }

        Integer expectedCurrentFy = null;
        boolean currentFyAmbiguous = false;
        if (ctx.latestAnnualAnchor() != null) {
            AnnualAnchor anchor = ctx.latestAnnualAnchor();
            int maxProbeFy = Math.max(anchor.fiscalYear(), valuationDate.getYear()) + 3;
            for (int fy = anchor.fiscalYear() + 1; fy <= maxProbeFy; fy++) {
                if (isAnnualAlreadyReported(fy, ctx)) {
                    continue;
                }
                StockEarningsForecast explicitRow = explicitAnnualByFy.get(fy);
                if (explicitRow != null && explicitRow.getPeriodEnd() != null) {
                    LocalDate explicitEnd = explicitRow.getPeriodEnd();
                    if (isAlreadyReportedEnd(explicitEnd, ctx.reportedAnnualEnds()) || !explicitEnd.isAfter(valuationDate)) {
                        continue;
                    }
                    expectedCurrentFy = fy;
                    break;
                }
                LocalDate estimatedEnd = anchor.periodEnd().plusYears(fy - anchor.fiscalYear());
                if (!valuationDate.isBefore(estimatedEnd.plusDays(FISCAL_END_TOLERANCE_DAYS))) {
                    continue;
                }
                if (!valuationDate.isBefore(estimatedEnd.minusDays(FISCAL_END_TOLERANCE_DAYS))) {
                    currentFyAmbiguous = true;
                    break;
                }
                expectedCurrentFy = fy;
                break;
            }
        }

        if (currentFyAmbiguous) {
            return new AnnualSelection(null, null, "FORECAST_FISCAL_PERIOD_UNVERIFIED");
        }

        List<QualifiedAnnual> qualified = new ArrayList<>();
        boolean sawUnverifiedAnnual = false;

        for (StockEarningsForecast f : latestList) {
            if (f == null || !"ANNUAL".equalsIgnoreCase(f.getPeriodType())) continue;
            if (f.getObservationDate() == null || f.getObservationDate().isAfter(maxObsDay)) continue;

            Integer parsedFy = parsePureAnnualYear(f.getFiscalPeriod());
            LocalDate explicitStart = f.getPeriodStart();
            LocalDate explicitEnd = f.getPeriodEnd();

            if (explicitEnd != null && isAlreadyReportedEnd(explicitEnd, ctx.reportedAnnualEnds())) {
                continue;
            }
            if (parsedFy != null && isAnnualAlreadyReported(parsedFy, ctx)) {
                continue;
            }

            if (explicitEnd != null) {
                if (explicitEnd.isAfter(valuationDate)) {
                    int sortFy = parsedFy != null ? parsedFy : explicitEnd.getYear();
                    boolean spansValuationDay = explicitStart != null && !valuationDate.isBefore(explicitStart);
                    if (spansValuationDay || expectedCurrentFy == null || sortFy >= expectedCurrentFy) {
                        qualified.add(new QualifiedAnnual(f, sortFy, explicitEnd));
                    }
                }
                continue;
            }

            if (parsedFy == null || ctx.latestAnnualAnchor() == null) {
                sawUnverifiedAnnual = true;
                continue;
            }
            if (expectedCurrentFy != null && parsedFy < expectedCurrentFy) {
                continue;
            }
            int yearDiff = parsedFy - ctx.latestAnnualAnchor().fiscalYear();
            LocalDate estimatedEnd = ctx.latestAnnualAnchor().periodEnd().plusYears(yearDiff);
            if (valuationDate.isBefore(estimatedEnd.minusDays(FISCAL_END_TOLERANCE_DAYS))) {
                qualified.add(new QualifiedAnnual(f, parsedFy, estimatedEnd));
            } else if (valuationDate.isBefore(estimatedEnd.plusDays(FISCAL_END_TOLERANCE_DAYS))) {
                sawUnverifiedAnnual = true;
            }
        }

        if (qualified.isEmpty()) {
            return new AnnualSelection(null, null,
                    sawUnverifiedAnnual ? "FORECAST_FISCAL_PERIOD_UNVERIFIED" : "ANNUAL_EARNINGS_FORECAST_MISSING");
        }

        // 先定目標財年，再於該財年內優先選取未陳舊的新鮮來源（防止舊來源的推估期末早數日而遮蔽新鮮來源）
        QualifiedAnnual first = pickBestForTargetFiscalYear(qualified, valuationDate, expectedCurrentFy);
        LocalDate firstStart = first.forecast().getPeriodStart();

        // 若明示財年起始日晚於估值日，代表該財年尚未開始（屬於遠期 FY2 而非當期 FY1）
        if (firstStart != null && firstStart.isAfter(valuationDate)) {
            return new AnnualSelection(null, null,
                    sawUnverifiedAnnual ? "FORECAST_FISCAL_PERIOD_UNVERIFIED" : "ANNUAL_EARNINGS_FORECAST_MISSING");
        }

        // 若未明示涵蓋當日之起始日，須核對是否為最近未結束財年（防止將孤立遠期 FY2 冒充為 FY1）
        if (expectedCurrentFy != null) {
            if (first.fiscalYear() != expectedCurrentFy) {
                return new AnnualSelection(null, null,
                        sawUnverifiedAnnual ? "FORECAST_FISCAL_PERIOD_UNVERIFIED" : "ANNUAL_EARNINGS_FORECAST_MISSING");
            }
        } else {
            long daysUntilEnd = ChronoUnit.DAYS.between(valuationDate, first.effectiveEnd());
            if (daysUntilEnd <= 0 || daysUntilEnd > MAX_UNANCHORED_FY1_SPAN_DAYS) {
                return new AnnualSelection(null, null,
                        sawUnverifiedAnnual ? "FORECAST_FISCAL_PERIOD_UNVERIFIED" : "ANNUAL_EARNINGS_FORECAST_MISSING");
            }
        }

        QualifiedAnnual second = selectFollowUpFy2(maxObsDay, first, latestList, ctx);
        return new AnnualSelection(first, second, null);
    }

    private QualifiedAnnual selectFollowUpFy2(
            LocalDate maxObsDay, QualifiedAnnual first, List<StockEarningsForecast> latestList, ReportedHistoryContext ctx) {
        List<QualifiedAnnual> candidates = new ArrayList<>();
        for (StockEarningsForecast f : latestList) {
            if (f == null || f == first.forecast() || !"ANNUAL".equalsIgnoreCase(f.getPeriodType())) continue;
            if (!sameSource(first.forecast().getSource(), f.getSource())) continue;
            if (f.getObservationDate() == null || f.getObservationDate().isAfter(maxObsDay)) continue;

            Integer parsedFy = parsePureAnnualYear(f.getFiscalPeriod());
            LocalDate explicitStart = f.getPeriodStart();
            LocalDate explicitEnd = f.getPeriodEnd();
            if (explicitEnd != null && isAlreadyReportedEnd(explicitEnd, ctx.reportedAnnualEnds())) continue;
            if (parsedFy != null && isAnnualAlreadyReported(parsedFy, ctx)) continue;

            if (explicitEnd != null) {
                long dayGap = ChronoUnit.DAYS.between(first.effectiveEnd(), explicitEnd);
                boolean startValid = explicitStart == null
                        || !explicitStart.isBefore(first.effectiveEnd().minusDays(FISCAL_END_TOLERANCE_DAYS));
                if (startValid && dayGap >= 330 && dayGap <= 400) {
                    int sortFy = parsedFy != null ? parsedFy : explicitEnd.getYear();
                    candidates.add(new QualifiedAnnual(f, sortFy, explicitEnd));
                }
            } else if (parsedFy != null && parsedFy == first.fiscalYear() + 1) {
                candidates.add(new QualifiedAnnual(f, parsedFy, first.effectiveEnd().plusYears(1)));
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(annualCandidateComparator());
        return candidates.getFirst();
    }

    private QualifiedQuarter selectNextUnreportedQuarter(
            LocalDate valuationDate, LocalDate maxObsDay, List<StockEarningsForecast> latestList, ReportedHistoryContext ctx) {
        List<QualifiedQuarter> freshCandidates = new ArrayList<>();
        List<QualifiedQuarter> staleCandidates = new ArrayList<>();
        for (StockEarningsForecast f : latestList) {
            if (f == null || !"QUARTERLY".equalsIgnoreCase(f.getPeriodType())) continue;
            if (f.getObservationDate() == null || f.getObservationDate().isAfter(maxObsDay)) continue;
            if (f.getPeriodEnd() != null && !f.getPeriodEnd().isAfter(valuationDate)
                    && isAlreadyReportedEnd(f.getPeriodEnd(), ctx.reportedAnnualEnds())) {
                continue;
            }
            FiscalQuarterKey qk = parseQuarterKey(f.getFiscalPeriod());
            if (qk == null) continue;
            if (ctx.reportedQuarters().contains(qk)) continue;
            if (ctx.reportedAnnualYears().contains(qk.year())) continue;
            if (ctx.maxReportedQuarter() != null && qk.compareTo(ctx.maxReportedQuarter()) <= 0) continue;

            QualifiedQuarter qq = new QualifiedQuarter(f, qk);
            long ageDays = ChronoUnit.DAYS.between(f.getObservationDate(), valuationDate);
            if (ageDays <= MAX_FORECAST_STALE_DAYS) {
                freshCandidates.add(qq);
            } else {
                staleCandidates.add(qq);
            }
        }
        if (!freshCandidates.isEmpty()) {
            freshCandidates.sort(quarterCandidateComparator());
            return freshCandidates.getFirst();
        }
        if (!staleCandidates.isEmpty()) {
            staleCandidates.sort(quarterCandidateComparator());
            return staleCandidates.getFirst();
        }
        return null;
    }

    private boolean isAnnualAlreadyReported(int fy, ReportedHistoryContext ctx) {
        if (ctx.reportedAnnualYears().contains(fy)) return true;
        if (ctx.reportedQuarters().contains(new FiscalQuarterKey(fy, 4))) return true;
        return ctx.maxReportedQuarter() != null && ctx.maxReportedQuarter().year() > fy;
    }

    private boolean isAlreadyReportedEnd(LocalDate end, List<LocalDate> reportedEnds) {
        for (LocalDate r : reportedEnds) {
            if (Math.abs(ChronoUnit.DAYS.between(r, end)) <= FISCAL_END_TOLERANCE_DAYS) return true;
        }
        return false;
    }

    static Integer parsePureAnnualYear(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = PURE_ANNUAL_PATTERN.matcher(text.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }

    static FiscalQuarterKey parseQuarterKey(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        Matcher m1 = QUARTER_PATTERN_1.matcher(trimmed);
        if (m1.matches()) {
            return new FiscalQuarterKey(Integer.parseInt(m1.group(1)), Integer.parseInt(m1.group(2)));
        }
        Matcher m2 = QUARTER_PATTERN_2.matcher(trimmed);
        if (m2.matches()) {
            return new FiscalQuarterKey(Integer.parseInt(m2.group(2)), Integer.parseInt(m2.group(1)));
        }
        return null;
    }

    private boolean sameCurrency(String first, String second) {
        return first != null && !first.isBlank() && second != null && first.trim().equalsIgnoreCase(second.trim());
    }

    private boolean sameSource(String first, String second) {
        return first != null && !first.isBlank() && second != null && first.trim().equalsIgnoreCase(second.trim());
    }

    private MetricStatus statusOfCode(String reasonCode) {
        String statusName = ValuationReason.statusNameFor(reasonCode);
        try {
            return MetricStatus.valueOf(statusName);
        } catch (IllegalArgumentException ex) {
            return MetricStatus.INSUFFICIENT_DATA;
        }
    }

    private MetricResult<BigDecimal> unavailable(MetricStatus status, String code, String message) {
        MetricStatus resolved = status == null ? MetricStatus.INSUFFICIENT_DATA : status;
        return MetricResult.unavailable(resolved, AnalysisReasonDto.of(code, "FORWARD_VALUATION", message));
    }
}
