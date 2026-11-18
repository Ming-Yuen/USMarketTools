package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisReasonDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FundamentalSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 以已載入的標準化財季分母與拆股紀錄，純記憶體計算當期成長率、獲利品質、槓桿與殖利率快照。
 * 不觸發任何資料庫查詢或外部 API。
 */
public class FundamentalSnapshotCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal MIN_PEG_EPS_GROWTH = new BigDecimal("0.05");
    private static final BigDecimal MAX_UNVERIFIED_YOY_PEG_GROWTH = BigDecimal.ONE;
    private static final long YOY_MIN_DAYS = 350L;
    private static final long YOY_MAX_DAYS = 380L;
    private static final long YOY_TARGET_DAYS = 365L;
    private static final long THREE_YEAR_MIN_DAYS = 1070L;
    private static final long THREE_YEAR_MAX_DAYS = 1120L;
    private static final long THREE_YEAR_TARGET_DAYS = 1095L;
    private static final long SHARE_BASIS_MIN_DAYS = 300L;
    private static final long SHARE_BASIS_MAX_DAYS = 430L;

    public record Inputs(
            List<StockFinancialPeriod> periods,
            StockFinancialPeriod matchedPeriod,
            List<StockSplit> splits,
            LocalDate valuationDate,
            String quoteCurrency,
            BigDecimal closePrice,
            BigDecimal marketCapAtDay,
            String sharesReason,
            BigDecimal epsAtDay,
            String peReason,
            BigDecimal peAtDay,
            boolean financialInstitution,
            String splitSyncError
    ) {}

    private record ComparablePeriodMatch(StockFinancialPeriod period, MetricStatus failStatus, String failReason) {}

    private record PegSelection(MetricResult<BigDecimal> result, String basis) {}

    public FundamentalSnapshotDto calculate(Inputs in) {
        if (in == null || in.matchedPeriod() == null) {
            return null;
        }
        StockFinancialPeriod matched = in.matchedPeriod();
        List<StockFinancialPeriod> periods = in.periods() == null ? List.of() : in.periods();
        List<StockSplit> splits = in.splits() == null ? List.of() : in.splits();

        ComparablePeriodMatch prior1y = findComparablePrior(periods, matched, YOY_MIN_DAYS, YOY_MAX_DAYS, YOY_TARGET_DAYS);
        ComparablePeriodMatch prior3y = findComparablePrior(periods, matched, THREE_YEAR_MIN_DAYS, THREE_YEAR_MAX_DAYS, THREE_YEAR_TARGET_DAYS);

        MetricResult<BigDecimal> revYoy = computeRevenueGrowth(matched, prior1y, false);
        MetricResult<BigDecimal> revCagr3y = computeRevenueGrowth(matched, prior3y, true);
        MetricResult<BigDecimal> epsYoy = computeEpsGrowth(matched, prior1y, splits, in.valuationDate(), in.splitSyncError(), false);
        MetricResult<BigDecimal> epsCagr3y = computeEpsGrowth(matched, prior3y, splits, in.valuationDate(), in.splitSyncError(), true);

        MetricResult<BigDecimal> fcfYoy;
        MetricResult<BigDecimal> fcfMargin;
        MetricResult<BigDecimal> fcfConversion;
        MetricResult<BigDecimal> netDebtToEbitda;
        MetricResult<BigDecimal> fcfYield;

        if (in.financialInstitution()) {
            fcfYoy = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流成長率。");
            fcfMargin = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流利潤率。");
            fcfConversion = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流轉換率。");
            netDebtToEbitda = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用淨負債／EBITDA。");
            fcfYield = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流殖利率。");
        } else {
            fcfYoy = computeFcfGrowthYoy(matched, prior1y);
            fcfMargin = computeFcfMargin(matched);
            fcfConversion = computeFcfConversion(matched);
            netDebtToEbitda = computeNetDebtToEbitda(matched);
            fcfYield = computeFcfYield(matched, in.quoteCurrency(), in.marketCapAtDay(), in.sharesReason());
        }

        MetricResult<BigDecimal> roe = computeRoe(matched, prior1y);
        MetricResult<BigDecimal> earningsYield = computeEarningsYield(matched, in.quoteCurrency(), in.closePrice(), in.epsAtDay(), in.peReason());
        MetricResult<BigDecimal> netShareChangeYoy = computeNetShareChangeYoy(matched, prior1y, splits, in.splitSyncError());
        PegSelection peg = computeTrailingPeg(in.peAtDay(), in.peReason(), epsYoy, epsCagr3y);

        return new FundamentalSnapshotDto(
                matched.getPeriodEnd(),
                prior1y.period() == null ? null : prior1y.period().getPeriodEnd(),
                prior3y.period() == null ? null : prior3y.period().getPeriodEnd(),
                matched.getReportingCurrency(),
                in.financialInstitution(),
                revYoy,
                epsYoy,
                fcfYoy,
                revCagr3y,
                epsCagr3y,
                peg.result(),
                peg.basis(),
                roe,
                fcfMargin,
                fcfConversion,
                netDebtToEbitda,
                fcfYield,
                earningsYield,
                netShareChangeYoy
        );
    }

    private ComparablePeriodMatch findComparablePrior(
            List<StockFinancialPeriod> periods, StockFinancialPeriod matched,
            long minDays, long maxDays, long targetDays) {
        LocalDate end = matched.getPeriodEnd();
        if (end == null) {
            return new ComparablePeriodMatch(null, MetricStatus.INSUFFICIENT_DATA, "PRIOR_COMPARABLE_PERIOD_MISSING");
        }
        StockFinancialPeriod best = null;
        long bestDiff = Long.MAX_VALUE;
        for (StockFinancialPeriod candidate : periods) {
            if (candidate == null || candidate.getPeriodEnd() == null || !candidate.getPeriodEnd().isBefore(end)) {
                continue;
            }
            long gap = ChronoUnit.DAYS.between(candidate.getPeriodEnd(), end);
            if (gap < minDays || gap > maxDays) {
                continue;
            }
            long diff = Math.abs(gap - targetDays);
            if (diff < bestDiff) {
                best = candidate;
                bestDiff = diff;
            }
        }
        if (best == null) {
            return new ComparablePeriodMatch(null, MetricStatus.INSUFFICIENT_DATA, "PRIOR_COMPARABLE_PERIOD_MISSING");
        }
        if (!sameCurrency(best.getReportingCurrency(), matched.getReportingCurrency())) {
            return new ComparablePeriodMatch(null, MetricStatus.UNVERIFIED, "REPORTING_CURRENCY_CHANGED");
        }
        if (hasDifferentAccountingStandard(best.getAccountingStandards(), matched.getAccountingStandards())) {
            return new ComparablePeriodMatch(null, MetricStatus.UNVERIFIED, "ACCOUNTING_STANDARDS_CHANGED");
        }
        return new ComparablePeriodMatch(best, null, null);
    }

    private MetricResult<BigDecimal> computeRevenueGrowth(StockFinancialPeriod matched, ComparablePeriodMatch priorMatch, boolean threeYearCagr) {
        if (!isAvailable(matched.getRevenueTtmStatus(), matched.getRevenueTtm())) {
            return fromPeriodUnavailable(matched.getRevenueTtmStatus(), matched.getRevenueTtmReason(), "REVENUE_TTM_MISSING");
        }
        if (priorMatch.period() == null) {
            return unavailable(priorMatch.failStatus(), priorMatch.failReason(), "缺少可比歷史營收報期。");
        }
        StockFinancialPeriod prior = priorMatch.period();
        if (!isAvailable(prior.getRevenueTtmStatus(), prior.getRevenueTtm())) {
            return fromPeriodUnavailable(prior.getRevenueTtmStatus(), prior.getRevenueTtmReason(), "PRIOR_REVENUE_TTM_MISSING");
        }
        BigDecimal priorRev = prior.getRevenueTtm();
        BigDecimal currRev = matched.getRevenueTtm();
        if (priorRev.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_PRIOR_REVENUE", "基期營業收入非正，不適用成長率。");
        }
        if (threeYearCagr) {
            if (currRev.signum() <= 0) {
                return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_REVENUE", "當期營業收入非正，不適用三年複合成長率。");
            }
            return MetricResult.available(cagr3y(priorRev, currRev));
        }
        return MetricResult.available(currRev.subtract(priorRev, MC).divide(priorRev, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeEpsGrowth(
            StockFinancialPeriod matched, ComparablePeriodMatch priorMatch,
            List<StockSplit> splits, LocalDate valuationDate, String splitSyncError, boolean threeYearCagr) {
        if (!isAvailable(matched.getEpsTtmStatus(), matched.getEpsTtm())) {
            return fromPeriodUnavailable(matched.getEpsTtmStatus(), matched.getEpsTtmReason(), "DILUTED_EPS_TTM_MISSING");
        }
        if (matched.getEpsShareBasisDate() == null) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "EPS_SOURCE_PERIOD_MISSING", "缺少當期 EPS 股份基準日。");
        }
        if (priorMatch.period() == null) {
            return unavailable(priorMatch.failStatus(), priorMatch.failReason(), "缺少可比歷史 EPS 報期。");
        }
        StockFinancialPeriod prior = priorMatch.period();
        if (!isAvailable(prior.getEpsTtmStatus(), prior.getEpsTtm())) {
            return fromPeriodUnavailable(prior.getEpsTtmStatus(), prior.getEpsTtmReason(), "PRIOR_EPS_TTM_MISSING");
        }
        if (prior.getEpsShareBasisDate() == null) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "EPS_SOURCE_PERIOD_MISSING", "缺少基期 EPS 股份基準日。");
        }

        BigDecimal currEps;
        BigDecimal priorEps;
        if (prior.getEpsShareBasisDate().equals(matched.getEpsShareBasisDate())) {
            currEps = matched.getEpsTtm();
            priorEps = prior.getEpsTtm();
        } else {
            if (splitSyncError != null) {
                return unavailable(MetricStatus.INSUFFICIENT_DATA, splitSyncError, "拆股紀錄同步異常且跨期股份基準日不同，暫不計算跨期 EPS 成長率。");
            }
            LocalDate targetBasis = valuationDate != null ? valuationDate : matched.getEpsShareBasisDate();
            BigDecimal currFactor = StockSplitCalculator.shareFactor(splits, matched.getEpsShareBasisDate(), targetBasis);
            BigDecimal priorFactor = StockSplitCalculator.shareFactor(splits, prior.getEpsShareBasisDate(), targetBasis);
            if (currFactor.signum() <= 0 || priorFactor.signum() <= 0) {
                return unavailable(MetricStatus.UNVERIFIED, "EPS_SPLIT_BASIS_UNCONFIRMED", "跨期拆股因子無效。");
            }
            currEps = matched.getEpsTtm().divide(currFactor, MC);
            priorEps = prior.getEpsTtm().divide(priorFactor, MC);
        }

        if (priorEps.signum() > 0 && currEps.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "SWUNG_TO_LOSS", "由盈轉虧，不計算百分比成長率。");
        }
        if (priorEps.signum() <= 0 && currEps.signum() > 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "TURNAROUND_FROM_LOSS", "由虧轉盈，基期非正不計算百分比成長率。");
        }
        if (priorEps.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_BASE_EPS", "基期與當期每股盈利皆非正，不適用成長率。");
        }
        if (threeYearCagr) {
            return MetricResult.available(cagr3y(priorEps, currEps));
        }
        return MetricResult.available(currEps.subtract(priorEps, MC).divide(priorEps, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeFcfGrowthYoy(StockFinancialPeriod matched, ComparablePeriodMatch priorMatch) {
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (priorMatch.period() == null) {
            return unavailable(priorMatch.failStatus(), priorMatch.failReason(), "缺少可比歷史自由現金流報期。");
        }
        StockFinancialPeriod prior = priorMatch.period();
        if (!isAvailable(prior.getFreeCashFlowTtmStatus(), prior.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(prior.getFreeCashFlowTtmStatus(), prior.getFreeCashFlowTtmReason(), "PRIOR_FCF_TTM_MISSING");
        }
        BigDecimal priorFcf = prior.getFreeCashFlowTtm();
        BigDecimal currFcf = matched.getFreeCashFlowTtm();
        if (priorFcf.signum() > 0 && currFcf.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "FCF_TURNED_NEGATIVE", "自由現金流由正轉負，不計算百分比成長率。");
        }
        if (priorFcf.signum() <= 0 && currFcf.signum() > 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "FCF_TURNAROUND_FROM_NEGATIVE", "自由現金流由負轉正，基期非正不計算百分比成長率。");
        }
        if (priorFcf.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_FCF", "基期與當期自由現金流皆非正，不適用成長率。");
        }
        return MetricResult.available(currFcf.subtract(priorFcf, MC).divide(priorFcf, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeFcfMargin(StockFinancialPeriod matched) {
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (!isAvailable(matched.getRevenueTtmStatus(), matched.getRevenueTtm())) {
            return fromPeriodUnavailable(matched.getRevenueTtmStatus(), matched.getRevenueTtmReason(), "REVENUE_TTM_MISSING");
        }
        if (matched.getRevenueTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_REVENUE", "營業收入非正，不適用自由現金流利潤率。");
        }
        return MetricResult.available(matched.getFreeCashFlowTtm().divide(matched.getRevenueTtm(), 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeFcfConversion(StockFinancialPeriod matched) {
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (!isAvailable(matched.getNetIncomeCommonTtmStatus(), matched.getNetIncomeCommonTtm())) {
            return fromPeriodUnavailable(matched.getNetIncomeCommonTtmStatus(), matched.getNetIncomeCommonTtmReason(), "NET_INCOME_COMMON_MISSING");
        }
        if (matched.getNetIncomeCommonTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_NET_INCOME", "普通股淨利非正，不計算自由現金流轉換率。");
        }
        return MetricResult.available(matched.getFreeCashFlowTtm().divide(matched.getNetIncomeCommonTtm(), 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeNetDebtToEbitda(StockFinancialPeriod matched) {
        if (!MetricStatus.AVAILABLE.name().equals(matched.getEvAdjustmentStatus())) {
            return fromPeriodUnavailable(matched.getEvAdjustmentStatus(), matched.getEvAdjustmentReason(), "EV_COMPONENTS_UNRESOLVED");
        }
        if (!isAvailable(matched.getTotalDebtStatus(), matched.getTotalDebt())) {
            return fromPeriodUnavailable(matched.getTotalDebtStatus(), matched.getTotalDebtReason(), "EV_COMPONENTS_UNRESOLVED");
        }
        if (!isAvailable(matched.getCashLikeAssetsStatus(), matched.getCashLikeAssets())) {
            return fromPeriodUnavailable(matched.getCashLikeAssetsStatus(), matched.getCashLikeAssetsReason(), "EV_COMPONENTS_UNRESOLVED");
        }
        if (!isAvailable(matched.getEbitdaTtmStatus(), matched.getEbitdaTtm())) {
            return fromPeriodUnavailable(matched.getEbitdaTtmStatus(), matched.getEbitdaTtmReason(), "EBITDA_TTM_MISSING");
        }
        if (matched.getEbitdaTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_EBITDA", "TTM EBITDA 非正，淨負債／EBITDA 不適用（N.M.）。");
        }
        BigDecimal netDebt = matched.getTotalDebt().subtract(matched.getCashLikeAssets(), MC);
        return MetricResult.available(netDebt.divide(matched.getEbitdaTtm(), 4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeRoe(StockFinancialPeriod matched, ComparablePeriodMatch prior1y) {
        if (!isAvailable(matched.getCommonEquityStatus(), matched.getCommonEquity())) {
            return fromPeriodUnavailable(matched.getCommonEquityStatus(), matched.getCommonEquityReason(), "COMMON_EQUITY_MISSING");
        }
        if (matched.getCommonEquity().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_COMMON_EQUITY", "普通股權益非正（如高回購負帳面值），ROE 不適用（N.M.）。");
        }
        if (!isAvailable(matched.getNetIncomeCommonTtmStatus(), matched.getNetIncomeCommonTtm())) {
            return fromPeriodUnavailable(matched.getNetIncomeCommonTtmStatus(), matched.getNetIncomeCommonTtmReason(), "NET_INCOME_COMMON_MISSING");
        }
        BigDecimal equityDenom = matched.getCommonEquity();
        StockFinancialPeriod prior = prior1y.period();
        if (prior != null && isAvailable(prior.getCommonEquityStatus(), prior.getCommonEquity()) && prior.getCommonEquity().signum() > 0) {
            equityDenom = matched.getCommonEquity().add(prior.getCommonEquity(), MC).divide(TWO, MC);
        }
        return MetricResult.available(matched.getNetIncomeCommonTtm().divide(equityDenom, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeFcfYield(
            StockFinancialPeriod matched, String quoteCurrency, BigDecimal marketCapAtDay, String sharesReason) {
        if (!sameCurrency(quoteCurrency, matched.getReportingCurrency())) {
            return unavailable(MetricStatus.UNVERIFIED, "REPORTING_CURRENCY_MISMATCH", "財報幣別與股價交易幣別不一致，不自動換算殖利率。");
        }
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日市值，無法計算自由現金流殖利率。");
        }
        return MetricResult.available(matched.getFreeCashFlowTtm().divide(marketCapAtDay, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeEarningsYield(
            StockFinancialPeriod matched, String quoteCurrency, BigDecimal closePrice, BigDecimal epsAtDay, String peReason) {
        if (!sameCurrency(quoteCurrency, matched.getReportingCurrency())) {
            return unavailable(MetricStatus.UNVERIFIED, "REPORTING_CURRENCY_MISMATCH", "財報幣別與股價交易幣別不一致，不自動換算盈餘殖利率。");
        }
        if (closePrice == null || closePrice.signum() <= 0) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "CLOSING_PRICE_MISSING", "缺少有效收盤價，無法計算盈餘殖利率。");
        }
        if (epsAtDay == null) {
            String code = peReason != null ? peReason : "DILUTED_EPS_TTM_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已對齊股份基準之每股盈利。");
        }
        return MetricResult.available(epsAtDay.divide(closePrice, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeNetShareChangeYoy(
            StockFinancialPeriod matched, ComparablePeriodMatch priorMatch,
            List<StockSplit> splits, String splitSyncError) {
        if (priorMatch.period() == null) {
            return unavailable(priorMatch.failStatus(), priorMatch.failReason(), "缺少可比歷史財季，無法計算淨股數年變化率。");
        }
        StockFinancialPeriod prior = priorMatch.period();
        if (!isAvailable(matched.getSharesOutstandingStatus(), matched.getSharesOutstanding())
                || matched.getSharesOutstanding().signum() <= 0 || matched.getSharesBasisDate() == null) {
            return fromPeriodUnavailable(matched.getSharesOutstandingStatus(), matched.getSharesOutstandingReason(), "VALUATION_DATE_SHARES_MISSING");
        }
        if (!isAvailable(prior.getSharesOutstandingStatus(), prior.getSharesOutstanding())
                || prior.getSharesOutstanding().signum() <= 0 || prior.getSharesBasisDate() == null) {
            return fromPeriodUnavailable(prior.getSharesOutstandingStatus(), prior.getSharesOutstandingReason(), "VALUATION_DATE_SHARES_MISSING");
        }
        if (!"SEC_FILING_SHARES".equals(matched.getSharesOutstandingSource())
                || !"SEC_FILING_SHARES".equals(prior.getSharesOutstandingSource())) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "SEC_FILING_SHARES_REQUIRED", "跨期淨股數變化率僅採用獨立 SEC 定期申報股數計算。");
        }
        if (matched.getSecShareAccession() != null && prior.getSecShareAccession() != null
                && matched.getSecShareAccession().equals(prior.getSecShareAccession())) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "SHARE_BASIS_PERIOD_NOT_INDEPENDENT", "前後財季沿用同一份 SEC 股數申報，不計算股數變化率。");
        }
        long basisGap = ChronoUnit.DAYS.between(prior.getSharesBasisDate(), matched.getSharesBasisDate());
        if (basisGap < SHARE_BASIS_MIN_DAYS || basisGap > SHARE_BASIS_MAX_DAYS) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "SHARE_BASIS_PERIOD_NOT_INDEPENDENT", "前後財季股數基準日間距不在一年可比範圍（300–430 天）內。");
        }
        if (splitSyncError != null && !prior.getSharesBasisDate().equals(matched.getSharesBasisDate())) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, splitSyncError, "拆股紀錄同步異常，暫不計算跨期股數變化率。");
        }
        BigDecimal factor = StockSplitCalculator.shareFactor(splits, prior.getSharesBasisDate(), matched.getSharesBasisDate());
        BigDecimal priorAligned = prior.getSharesOutstanding().multiply(factor, MC);
        if (priorAligned.signum() <= 0) {
            return unavailable(MetricStatus.INSUFFICIENT_DATA, "VALUATION_DATE_SHARES_MISSING", "拆股對齊後基期股數無效。");
        }
        BigDecimal change = matched.getSharesOutstanding().subtract(priorAligned, MC).divide(priorAligned, 6, RoundingMode.HALF_UP);
        return MetricResult.available(change);
    }

    private PegSelection computeTrailingPeg(
            BigDecimal peAtDay, String peReason,
            MetricResult<BigDecimal> epsYoy, MetricResult<BigDecimal> epsCagr3y) {
        if (peAtDay == null || peAtDay.signum() <= 0) {
            String code = peReason != null ? peReason : "NON_POSITIVE_EPS";
            return new PegSelection(unavailable(statusOfCode(code), code, "當期 P/E 非正或不可用，不計算 Trailing PEG。"), null);
        }
        boolean hasYoy = epsYoy.status() == MetricStatus.AVAILABLE && epsYoy.value() != null;
        boolean hasCagr = epsCagr3y.status() == MetricStatus.AVAILABLE && epsCagr3y.value() != null;
        if (!hasYoy && !hasCagr) {
            String reason = !epsYoy.reasons().isEmpty() ? epsYoy.reasons().getFirst().code() : "EPS_GROWTH_UNAVAILABLE";
            MetricStatus st = epsYoy.status() != null && epsYoy.status() != MetricStatus.AVAILABLE
                    ? epsYoy.status() : MetricStatus.INSUFFICIENT_DATA;
            return new PegSelection(unavailable(st, reason, "缺少正值可比 EPS 成長率，不計算 Trailing PEG。"), null);
        }
        if (!hasYoy && hasReasonCode(epsYoy, "SWUNG_TO_LOSS")) {
            return new PegSelection(unavailable(MetricStatus.NOT_APPLICABLE, "SWUNG_TO_LOSS", "近期由盈轉虧，不計算 Trailing PEG。"), null);
        }

        BigDecimal chosenGrowth;
        String basis;
        if (hasYoy && hasCagr) {
            // 同時具備 1Y 與 3Y 成長率時取較保守一端，防止單年低基期暴衝或近期失速製造假低 PEG
            if (epsCagr3y.value().compareTo(epsYoy.value()) <= 0) {
                chosenGrowth = epsCagr3y.value();
                basis = "TRAILING_EPS_CAGR_3Y";
            } else {
                chosenGrowth = epsYoy.value();
                basis = "TRAILING_EPS_YOY";
            }
        } else if (hasYoy) {
            if (epsYoy.value().compareTo(MAX_UNVERIFIED_YOY_PEG_GROWTH) > 0) {
                return new PegSelection(unavailable(MetricStatus.NOT_APPLICABLE, "EPS_GROWTH_LOW_BASE_SPIKE",
                        "單年 EPS 成長率超過 100% 且缺三年複合基準驗證，避免低基期暴衝造成失真 PEG。"), null);
            }
            chosenGrowth = epsYoy.value();
            basis = "TRAILING_EPS_YOY";
        } else {
            chosenGrowth = epsCagr3y.value();
            basis = "TRAILING_EPS_CAGR_3Y";
        }

        if (chosenGrowth.compareTo(MIN_PEG_EPS_GROWTH) < 0) {
            return new PegSelection(unavailable(MetricStatus.NOT_APPLICABLE, "EPS_GROWTH_BELOW_PEG_THRESHOLD",
                    "可比 EPS 成長率低於 5% 門檻，為防極小分母扭曲倍數不計算 Trailing PEG。"), null);
        }
        BigDecimal peg = peAtDay.divide(chosenGrowth.multiply(HUNDRED, MC), 4, RoundingMode.HALF_UP);
        return new PegSelection(MetricResult.available(peg), basis);
    }

    private BigDecimal cagr3y(BigDecimal prior, BigDecimal current) {
        double ratio = current.divide(prior, MC).doubleValue();
        double cagr = Math.pow(ratio, 1.0d / 3.0d) - 1.0d;
        return BigDecimal.valueOf(cagr).setScale(6, RoundingMode.HALF_UP);
    }

    private boolean isAvailable(String status, BigDecimal value) {
        return value != null && MetricStatus.AVAILABLE.name().equals(status);
    }

    private boolean sameCurrency(String first, String second) {
        return first != null && !first.isBlank() && second != null && first.trim().equalsIgnoreCase(second.trim());
    }

    private boolean hasDifferentAccountingStandard(String first, String second) {
        return first != null && !first.isBlank() && second != null && !second.isBlank()
                && !first.trim().equalsIgnoreCase(second.trim());
    }

    private boolean hasReasonCode(MetricResult<?> result, String code) {
        return result != null && result.reasons().stream().anyMatch(r -> code.equals(r.code()));
    }

    private MetricStatus statusOfCode(String reasonCode) {
        String statusName = ValuationReason.statusNameFor(reasonCode);
        try {
            return MetricStatus.valueOf(statusName);
        } catch (IllegalArgumentException ex) {
            return MetricStatus.INSUFFICIENT_DATA;
        }
    }

    private MetricResult<BigDecimal> fromPeriodUnavailable(String rawStatus, ValuationReason reason, String fallbackCode) {
        String code = reason != null ? reason.name() : fallbackCode;
        MetricStatus status;
        if (rawStatus != null) {
            try {
                status = MetricStatus.valueOf(rawStatus);
            } catch (IllegalArgumentException ex) {
                status = statusOfCode(code);
            }
        } else {
            status = statusOfCode(code);
        }
        return unavailable(status, code, "缺少已核實的財務輸入。");
    }

    private MetricResult<BigDecimal> unavailable(MetricStatus status, String code, String message) {
        MetricStatus resolved = status == null ? MetricStatus.INSUFFICIENT_DATA : status;
        return MetricResult.unavailable(resolved, AnalysisReasonDto.of(code, "FUNDAMENTALS", message));
    }
}
