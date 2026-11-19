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
 * 以已載入的標準化財季分母與拆股紀錄，純記憶體計算當期成長率、獲利品質、SBC 實質現金流、
 * 利潤率趨勢、ROIC、P/TBV、償債與流動性覆蓋及殖利率快照。不觸發任何資料庫查詢或外部 API。
 */
public class FundamentalSnapshotCalculator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal MIN_PEG_EPS_GROWTH = new BigDecimal("0.05");
    private static final BigDecimal MAX_UNVERIFIED_YOY_PEG_GROWTH = BigDecimal.ONE;
    private static final BigDecimal MAX_EFFECTIVE_TAX_RATE = new BigDecimal("0.35");
    private static final BigDecimal STATUTORY_TAX_RATE = new BigDecimal("0.21");
    private static final long QOQ_MIN_DAYS = 70L;
    private static final long QOQ_MAX_DAYS = 125L;
    private static final long QOQ_TARGET_DAYS = 91L;
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
            String splitSyncError,
            BigDecimal fxRateToQuote,
            String fxFailureReason,
            boolean adrCompanyLevel
    ) {
        public Inputs(
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
                String splitSyncError) {
            this(periods, matchedPeriod, splits, valuationDate, quoteCurrency, closePrice, marketCapAtDay,
                    sharesReason, epsAtDay, peReason, peAtDay, financialInstitution, splitSyncError,
                    null, null, false);
        }
    }

    private record ComparablePeriodMatch(StockFinancialPeriod period, MetricStatus failStatus, String failReason) {}

    private record PegSelection(MetricResult<BigDecimal> result, String basis) {}

    private record FxContext(BigDecimal multiplier, String failReason) {
        boolean usable() {
            return multiplier != null && multiplier.signum() > 0 && failReason == null;
        }
    }

    public FundamentalSnapshotDto calculate(Inputs in) {
        if (in == null || in.matchedPeriod() == null) {
            return null;
        }
        StockFinancialPeriod matched = in.matchedPeriod();
        List<StockFinancialPeriod> periods = in.periods() == null ? List.of() : in.periods();
        List<StockSplit> splits = in.splits() == null ? List.of() : in.splits();

        ComparablePeriodMatch prior1q = findComparablePrior(periods, matched, QOQ_MIN_DAYS, QOQ_MAX_DAYS, QOQ_TARGET_DAYS);
        ComparablePeriodMatch prior2q = prior1q.period() != null
                ? findComparablePrior(periods, prior1q.period(), QOQ_MIN_DAYS, QOQ_MAX_DAYS, QOQ_TARGET_DAYS)
                : new ComparablePeriodMatch(null, MetricStatus.INSUFFICIENT_DATA, "PRIOR_COMPARABLE_PERIOD_MISSING");
        ComparablePeriodMatch prior1y = findComparablePrior(periods, matched, YOY_MIN_DAYS, YOY_MAX_DAYS, YOY_TARGET_DAYS);
        ComparablePeriodMatch prior3y = findComparablePrior(periods, matched, THREE_YEAR_MIN_DAYS, THREE_YEAR_MAX_DAYS, THREE_YEAR_TARGET_DAYS);

        FxContext fx = resolveFx(in.quoteCurrency(), matched.getReportingCurrency(), in.fxRateToQuote(), in.fxFailureReason());

        MetricResult<BigDecimal> revYoy = computeRevenueGrowth(matched, prior1y, false);
        MetricResult<BigDecimal> revCagr3y = computeRevenueGrowth(matched, prior3y, true);
        MetricResult<BigDecimal> epsYoy = computeEpsGrowth(
                matched, prior1y, splits, in.valuationDate(), in.splitSyncError(), false, in.adrCompanyLevel(), in.fxFailureReason());
        MetricResult<BigDecimal> epsCagr3y = computeEpsGrowth(
                matched, prior3y, splits, in.valuationDate(), in.splitSyncError(), true, in.adrCompanyLevel(), in.fxFailureReason());

        MetricResult<BigDecimal> fcfYoy;
        MetricResult<BigDecimal> fcfCagr3y;
        MetricResult<BigDecimal> fcfMargin;
        MetricResult<BigDecimal> fcfConversion;
        MetricResult<BigDecimal> netDebtToEbitda;
        MetricResult<BigDecimal> fcfYield;
        MetricResult<BigDecimal> sbcTtm = computeSbcTtm(matched);
        MetricResult<BigDecimal> sbcToRevenue = computeSbcToRevenue(matched, sbcTtm);
        MetricResult<BigDecimal> adjFcfTtm;
        MetricResult<BigDecimal> pToAdjFcf;
        MetricResult<BigDecimal> grossMargin;
        MetricResult<BigDecimal> grossMarginYoyPpt;
        MetricResult<BigDecimal> grossMarginQoqPpt;
        MetricResult<BigDecimal> operatingMargin;
        MetricResult<BigDecimal> operatingMarginYoyPpt;
        MetricResult<BigDecimal> operatingMarginQoqPpt;
        boolean marginCompressionConsecutive = false;
        MetricResult<BigDecimal> evToSales;
        MetricResult<BigDecimal> evToGrossProfit;
        MetricResult<BigDecimal> ruleOf40;
        MetricResult<BigDecimal> nopat;
        MetricResult<BigDecimal> investedCapital;
        MetricResult<BigDecimal> roic;
        MetricResult<BigDecimal> interestCoverage;
        MetricResult<BigDecimal> shortTermLiquidityCoverage;

        if (in.financialInstitution()) {
            fcfYoy = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流成長率。");
            fcfCagr3y = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用三年自由現金流複合成長率。");
            fcfMargin = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流利潤率。");
            fcfConversion = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流轉換率。");
            netDebtToEbitda = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用淨負債／EBITDA。");
            fcfYield = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用自由現金流殖利率。");
            adjFcfTtm = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用扣 SBC 自由現金流。");
            pToAdjFcf = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用 P/Adj. FCF。");
            grossMargin = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "金融機構不適用一般毛利率。");
            grossMarginYoyPpt = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "金融機構不適用一般毛利率變動。");
            grossMarginQoqPpt = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "金融機構不適用一般毛利率變動。");
            operatingMargin = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "金融機構不適用一般營業利潤率。");
            operatingMarginYoyPpt = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "金融機構不適用一般營業利潤率變動。");
            operatingMarginQoqPpt = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "金融機構不適用一般營業利潤率變動。");
            evToSales = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用 EV/Sales。");
            evToGrossProfit = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用 EV/Gross Profit。");
            ruleOf40 = unavailable(MetricStatus.NOT_APPLICABLE, "PFCF_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用 Rule of 40。");
            nopat = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用一般營運 NOPAT。");
            investedCapital = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用一般投入資本。");
            roic = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業請改以 ROE 與 P/TBV 評估。");
            interestCoverage = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用一般利息保障倍數。");
            shortTermLiquidityCoverage = unavailable(MetricStatus.NOT_APPLICABLE, "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY", "銀行與保險業不適用一般短期流動性覆蓋率。");
        } else {
            fcfYoy = computeFcfGrowthYoy(matched, prior1y);
            fcfCagr3y = computeFcfCagr3y(matched, prior3y);
            fcfMargin = computeFcfMargin(matched);
            fcfConversion = computeFcfConversion(matched);
            netDebtToEbitda = computeNetDebtToEbitda(matched);
            fcfYield = computeFcfYield(matched, fx, in.marketCapAtDay(), in.sharesReason());
            adjFcfTtm = computeAdjFcfTtm(matched, sbcTtm);
            pToAdjFcf = computePToAdjFcf(adjFcfTtm, fx, in.marketCapAtDay(), in.sharesReason());
            grossMargin = computeMargin(matched.getGrossProfitTtm(), matched.getGrossProfitTtmReason(), "GROSS_PROFIT_MISSING", matched);
            grossMarginYoyPpt = computeMarginDeltaPpt(grossMargin, prior1y, true);
            grossMarginQoqPpt = computeMarginDeltaPpt(grossMargin, prior1q, true);
            operatingMargin = computeMargin(matched.getOperatingProfitTtm(), matched.getOperatingProfitTtmReason(), "OPERATING_PROFIT_MISSING", matched);
            operatingMarginYoyPpt = computeMarginDeltaPpt(operatingMargin, prior1y, false);
            operatingMarginQoqPpt = computeMarginDeltaPpt(operatingMargin, prior1q, false);
            marginCompressionConsecutive = isConsecutiveMarginCompression(grossMarginQoqPpt, prior1q, prior2q, true)
                    || isConsecutiveMarginCompression(operatingMarginQoqPpt, prior1q, prior2q, false);
            BigDecimal evInQuote = computeEvInQuoteCurrency(matched, fx, in.marketCapAtDay());
            evToSales = computeEvMultiple(evInQuote, matched, fx, in.marketCapAtDay(), in.sharesReason(),
                    isAvailable(matched.getRevenueTtmStatus(), matched.getRevenueTtm()) ? matched.getRevenueTtm() : null,
                    matched.getRevenueTtmReason(), "REVENUE_TTM_MISSING", "NON_POSITIVE_REVENUE");
            evToGrossProfit = computeEvMultiple(evInQuote, matched, fx, in.marketCapAtDay(), in.sharesReason(),
                    matched.getGrossProfitTtm(), matched.getGrossProfitTtmReason(), "GROSS_PROFIT_MISSING", "NON_POSITIVE_GROSS_PROFIT");
            ruleOf40 = computeRuleOf40(revYoy, fcfMargin);
            nopat = computeNopat(matched);
            investedCapital = computeInvestedCapital(matched);
            roic = computeRoic(nopat, investedCapital, prior1y);
            interestCoverage = computeInterestCoverage(matched);
            shortTermLiquidityCoverage = computeShortTermLiquidityCoverage(matched);
        }

        MetricResult<BigDecimal> roe = computeRoe(matched, prior1y);
        MetricResult<BigDecimal> earningsYield = computeEarningsYield(matched, fx, in.closePrice(), in.marketCapAtDay(),
                in.sharesReason(), in.epsAtDay(), in.peReason(), in.adrCompanyLevel());
        MetricResult<BigDecimal> netShareChangeYoy = computeNetShareChangeYoy(matched, prior1y, splits, in.splitSyncError());
        MetricResult<BigDecimal> totalShareholderYield = computeTotalShareholderYield(matched, fx, in.marketCapAtDay(), in.sharesReason());
        MetricResult<BigDecimal> tangibleBookValue = computeTangibleBookValue(matched);
        MetricResult<BigDecimal> pToTbv = computePToTbv(tangibleBookValue, fx, in.marketCapAtDay(), in.sharesReason());
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
                netShareChangeYoy,
                fcfCagr3y,
                sbcTtm,
                sbcToRevenue,
                adjFcfTtm,
                pToAdjFcf,
                totalShareholderYield,
                grossMargin,
                grossMarginYoyPpt,
                grossMarginQoqPpt,
                operatingMargin,
                operatingMarginYoyPpt,
                operatingMarginQoqPpt,
                marginCompressionConsecutive,
                evToSales,
                evToGrossProfit,
                ruleOf40,
                nopat,
                investedCapital,
                roic,
                tangibleBookValue,
                pToTbv,
                interestCoverage,
                shortTermLiquidityCoverage
        );
    }

    private FxContext resolveFx(String quoteCurrency, String reportingCurrency, BigDecimal fxRateToQuote, String fxFailureReason) {
        if (fxFailureReason != null && !fxFailureReason.isBlank()) {
            return new FxContext(null, fxFailureReason);
        }
        if (sameCurrency(quoteCurrency, reportingCurrency)) {
            return new FxContext(BigDecimal.ONE, null);
        }
        if (fxRateToQuote != null && fxRateToQuote.signum() > 0) {
            return new FxContext(fxRateToQuote, null);
        }
        return new FxContext(null, "REPORTING_CURRENCY_MISMATCH");
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

    private MetricResult<BigDecimal> computeSbcTtm(StockFinancialPeriod matched) {
        if (matched.getSbcTtm() != null) {
            return MetricResult.available(matched.getSbcTtm());
        }
        return fromReasonUnavailable(matched.getSbcTtmReason(), "SBC_MISSING", "缺少已核實的 TTM 股票薪酬（SBC）。");
    }

    private MetricResult<BigDecimal> computeSbcToRevenue(StockFinancialPeriod matched, MetricResult<BigDecimal> sbcTtm) {
        if (sbcTtm.status() != MetricStatus.AVAILABLE || sbcTtm.value() == null) {
            return sbcTtm;
        }
        if (!isAvailable(matched.getRevenueTtmStatus(), matched.getRevenueTtm())) {
            return fromPeriodUnavailable(matched.getRevenueTtmStatus(), matched.getRevenueTtmReason(), "REVENUE_TTM_MISSING");
        }
        if (matched.getRevenueTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_REVENUE", "營業收入非正，不適用 SBC 佔營收比。");
        }
        return MetricResult.available(sbcTtm.value().divide(matched.getRevenueTtm(), 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeAdjFcfTtm(StockFinancialPeriod matched, MetricResult<BigDecimal> sbcTtm) {
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (sbcTtm.status() != MetricStatus.AVAILABLE || sbcTtm.value() == null) {
            return sbcTtm;
        }
        return MetricResult.available(matched.getFreeCashFlowTtm().subtract(sbcTtm.value(), MC).setScale(4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computePToAdjFcf(
            MetricResult<BigDecimal> adjFcfTtm, FxContext fx, BigDecimal marketCapAtDay, String sharesReason) {
        if (!fx.usable()) {
            return unavailable(statusOfCode(fx.failReason()), fx.failReason(), "缺少有效匯率或幣別未對齊，無法計算 P/Adj. FCF。");
        }
        if (adjFcfTtm.status() != MetricStatus.AVAILABLE || adjFcfTtm.value() == null) {
            return adjFcfTtm;
        }
        if (adjFcfTtm.value().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_ADJ_FCF", "扣 SBC 實質自由現金流非正，P/Adj. FCF 不適用（N.M.）。");
        }
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日市值，無法計算 P/Adj. FCF。");
        }
        BigDecimal adjFcfInQuote = adjFcfTtm.value().multiply(fx.multiplier(), MC);
        return MetricResult.available(marketCapAtDay.divide(adjFcfInQuote, 4, RoundingMode.HALF_UP));
    }

    /** Total Shareholder Yield = (普通股現金股息流出 - 淨普通股發行) / 市值；保留增發負號，絕不以 SEC gross 回購冒充淨回購。 */
    private MetricResult<BigDecimal> computeTotalShareholderYield(
            StockFinancialPeriod matched, FxContext fx, BigDecimal marketCapAtDay, String sharesReason) {
        if (!fx.usable()) {
            return unavailable(statusOfCode(fx.failReason()), fx.failReason(), "缺少有效匯率或幣別未對齊，無法計算總股東回報率。");
        }
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日市值，無法計算總股東回報率。");
        }
        if (matched.getCashDividendsPaidTtmReason() != null
                && MetricStatus.UNVERIFIED.name().equals(matched.getCashDividendsPaidTtmReason().statusName())) {
            return fromReasonUnavailable(matched.getCashDividendsPaidTtmReason(),
                    "SHAREHOLDER_RETURN_MISSING", "現金股息資料未核實，無法計算總股東回報率。");
        }
        if (matched.getNetCommonStockIssuanceTtmReason() != null
                && MetricStatus.UNVERIFIED.name().equals(matched.getNetCommonStockIssuanceTtmReason().statusName())) {
            return fromReasonUnavailable(matched.getNetCommonStockIssuanceTtmReason(),
                    "SHAREHOLDER_RETURN_MISSING", "淨普通股發行資料未核實，無法計算總股東回報率。");
        }
        BigDecimal dividends = matched.getCashDividendsPaidTtm();
        BigDecimal netIssuance = matched.getNetCommonStockIssuanceTtm();
        // 必須有淨發行／淨回購現金流（或富途完整現金流量表僅派息無股票發行），不將單一 SEC gross 回購視為淨回購
        if (netIssuance == null && (dividends == null || !"FUTU".equals(matched.getCashDividendsPaidTtmSource()))) {
            return fromReasonUnavailable(matched.getNetCommonStockIssuanceTtmReason(),
                    "SHAREHOLDER_RETURN_MISSING", "缺少已核實的現金股息與淨回購現金流。");
        }
        BigDecimal divVal = dividends != null ? dividends : BigDecimal.ZERO;
        BigDecimal netBuyback = netIssuance != null ? netIssuance.negate() : BigDecimal.ZERO;
        BigDecimal totalReturnInQuote = divVal.add(netBuyback, MC).multiply(fx.multiplier(), MC);
        return MetricResult.available(totalReturnInQuote.divide(marketCapAtDay, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeMargin(
            BigDecimal profit, ValuationReason profitReason, String missingCode, StockFinancialPeriod period) {
        if (profit == null) {
            return fromReasonUnavailable(profitReason, missingCode, "缺少已核實的利潤總額。");
        }
        if (!isAvailable(period.getRevenueTtmStatus(), period.getRevenueTtm())) {
            return fromPeriodUnavailable(period.getRevenueTtmStatus(), period.getRevenueTtmReason(), "REVENUE_TTM_MISSING");
        }
        if (period.getRevenueTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_REVENUE", "營業收入非正，不適用利潤率。");
        }
        return MetricResult.available(profit.divide(period.getRevenueTtm(), 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeMarginDeltaPpt(
            MetricResult<BigDecimal> currentMargin, ComparablePeriodMatch priorMatch, boolean gross) {
        if (currentMargin.status() != MetricStatus.AVAILABLE || currentMargin.value() == null) {
            return currentMargin;
        }
        if (priorMatch.period() == null) {
            return unavailable(priorMatch.failStatus(), priorMatch.failReason(), "缺少可比歷史財季以對照利潤率變動。");
        }
        StockFinancialPeriod prior = priorMatch.period();
        MetricResult<BigDecimal> priorMargin = gross
                ? computeMargin(prior.getGrossProfitTtm(), prior.getGrossProfitTtmReason(), "GROSS_PROFIT_MISSING", prior)
                : computeMargin(prior.getOperatingProfitTtm(), prior.getOperatingProfitTtmReason(), "OPERATING_PROFIT_MISSING", prior);
        if (priorMargin.status() != MetricStatus.AVAILABLE || priorMargin.value() == null) {
            return priorMargin;
        }
        return MetricResult.available(currentMargin.value().subtract(priorMargin.value(), MC).setScale(6, RoundingMode.HALF_UP));
    }

    private boolean isConsecutiveMarginCompression(
            MetricResult<BigDecimal> latestQoqDelta, ComparablePeriodMatch prior1q, ComparablePeriodMatch prior2q, boolean gross) {
        if (latestQoqDelta.status() != MetricStatus.AVAILABLE || latestQoqDelta.value() == null
                || latestQoqDelta.value().signum() >= 0) {
            return false;
        }
        if (prior1q.period() == null || prior2q.period() == null) {
            return false;
        }
        MetricResult<BigDecimal> m1 = gross
                ? computeMargin(prior1q.period().getGrossProfitTtm(), prior1q.period().getGrossProfitTtmReason(), "GROSS_PROFIT_MISSING", prior1q.period())
                : computeMargin(prior1q.period().getOperatingProfitTtm(), prior1q.period().getOperatingProfitTtmReason(), "OPERATING_PROFIT_MISSING", prior1q.period());
        MetricResult<BigDecimal> m2 = gross
                ? computeMargin(prior2q.period().getGrossProfitTtm(), prior2q.period().getGrossProfitTtmReason(), "GROSS_PROFIT_MISSING", prior2q.period())
                : computeMargin(prior2q.period().getOperatingProfitTtm(), prior2q.period().getOperatingProfitTtmReason(), "OPERATING_PROFIT_MISSING", prior2q.period());
        if (m1.status() != MetricStatus.AVAILABLE || m2.status() != MetricStatus.AVAILABLE
                || m1.value() == null || m2.value() == null) {
            return false;
        }
        return m1.value().subtract(m2.value(), MC).signum() < 0;
    }

    private BigDecimal computeEvInQuoteCurrency(StockFinancialPeriod matched, FxContext fx, BigDecimal marketCapAtDay) {
        if (!fx.usable() || marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            return null;
        }
        if (!MetricStatus.AVAILABLE.name().equals(matched.getEvAdjustmentStatus()) || matched.getEvAdjustment() == null) {
            return null;
        }
        return marketCapAtDay.add(matched.getEvAdjustment().multiply(fx.multiplier(), MC), MC);
    }

    private MetricResult<BigDecimal> computeEvMultiple(
            BigDecimal evInQuote, StockFinancialPeriod matched, FxContext fx, BigDecimal marketCapAtDay, String sharesReason,
            BigDecimal denominatorOrig, ValuationReason denomReason, String denomMissingCode, String nonPositiveCode) {
        if (!fx.usable()) {
            return unavailable(statusOfCode(fx.failReason()), fx.failReason(), "缺少有效匯率或幣別未對齊，無法計算 EV 倍數。");
        }
        if (denominatorOrig != null && denominatorOrig.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, nonPositiveCode, "分母非正，EV 倍數不適用（N.M.）。");
        }
        if (denominatorOrig == null) {
            return fromReasonUnavailable(denomReason, denomMissingCode, "缺少已核實的 EV 倍數分母。");
        }
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日市值，無法計算 EV 倍數。");
        }
        if (evInQuote == null) {
            return fromPeriodUnavailable(matched.getEvAdjustmentStatus(), matched.getEvAdjustmentReason(), "EV_COMPONENTS_UNRESOLVED");
        }
        BigDecimal denomInQuote = denominatorOrig.multiply(fx.multiplier(), MC);
        return MetricResult.available(evInQuote.divide(denomInQuote, 4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeRuleOf40(MetricResult<BigDecimal> revYoy, MetricResult<BigDecimal> fcfMargin) {
        if (revYoy.status() != MetricStatus.AVAILABLE || revYoy.value() == null) {
            return revYoy;
        }
        if (fcfMargin.status() != MetricStatus.AVAILABLE || fcfMargin.value() == null) {
            return fcfMargin;
        }
        return MetricResult.available(revYoy.value().add(fcfMargin.value(), MC).setScale(6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeNopat(StockFinancialPeriod matched) {
        if (matched.getOperatingProfitTtm() == null) {
            return fromReasonUnavailable(matched.getOperatingProfitTtmReason(), "OPERATING_PROFIT_MISSING", "缺少 TTM 營業利益，無法計算 NOPAT。");
        }
        BigDecimal effectiveTaxRate;
        if (matched.getPretaxProfitTtm() != null && matched.getIncomeTaxExpenseTtm() != null
                && matched.getPretaxProfitTtm().signum() > 0) {
            BigDecimal raw = matched.getIncomeTaxExpenseTtm().divide(matched.getPretaxProfitTtm(), 6, RoundingMode.HALF_UP);
            if (raw.signum() >= 0 && raw.compareTo(MAX_EFFECTIVE_TAX_RATE) <= 0) {
                effectiveTaxRate = raw;
            } else {
                // 異常稅率（負稅率或 > 35%）依規格以美國法定稅率 21% 近似
                effectiveTaxRate = STATUTORY_TAX_RATE;
            }
        } else if (matched.getPretaxProfitTtm() != null && matched.getPretaxProfitTtm().signum() <= 0) {
            effectiveTaxRate = STATUTORY_TAX_RATE;
        } else {
            return fromReasonUnavailable(matched.getIncomeTaxExpenseTtmReason(), "PRETAX_OR_TAX_MISSING", "缺少稅前盈利或所得稅費用，無法計算 NOPAT。");
        }
        BigDecimal nopat = matched.getOperatingProfitTtm().multiply(BigDecimal.ONE.subtract(effectiveTaxRate, MC), MC);
        return MetricResult.available(nopat.setScale(4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeInvestedCapital(StockFinancialPeriod period) {
        if (!isAvailable(period.getCommonEquityStatus(), period.getCommonEquity())) {
            return fromPeriodUnavailable(period.getCommonEquityStatus(), period.getCommonEquityReason(), "COMMON_EQUITY_MISSING");
        }
        if (!MetricStatus.AVAILABLE.name().equals(period.getEvAdjustmentStatus()) || period.getEvAdjustment() == null) {
            return fromPeriodUnavailable(period.getEvAdjustmentStatus(), period.getEvAdjustmentReason(), "EV_COMPONENTS_UNRESOLVED");
        }
        // Invested Capital = TotalDebt + CommonEquity + PreferredEquity + NCI - CashLikeAssets = CommonEquity + evAdjustment
        return MetricResult.available(period.getCommonEquity().add(period.getEvAdjustment(), MC).setScale(4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeRoic(
            MetricResult<BigDecimal> nopat, MetricResult<BigDecimal> currentIc, ComparablePeriodMatch prior1y) {
        if (nopat.status() != MetricStatus.AVAILABLE || nopat.value() == null) {
            return nopat;
        }
        if (currentIc.status() != MetricStatus.AVAILABLE || currentIc.value() == null) {
            return currentIc;
        }
        BigDecimal denom = currentIc.value();
        if (prior1y.period() != null) {
            MetricResult<BigDecimal> priorIc = computeInvestedCapital(prior1y.period());
            if (priorIc.status() == MetricStatus.AVAILABLE && priorIc.value() != null
                    && priorIc.value().signum() > 0 && currentIc.value().signum() > 0) {
                denom = currentIc.value().add(priorIc.value(), MC).divide(TWO, MC);
            }
        }
        if (denom.signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_INVESTED_CAPITAL", "投入資本非正，ROIC 不適用（N.M.）。");
        }
        return MetricResult.available(nopat.value().divide(denom, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeTangibleBookValue(StockFinancialPeriod matched) {
        if (!isAvailable(matched.getCommonEquityStatus(), matched.getCommonEquity())) {
            return fromPeriodUnavailable(matched.getCommonEquityStatus(), matched.getCommonEquityReason(), "COMMON_EQUITY_MISSING");
        }
        if (matched.getGoodwillAndIntangibles() == null) {
            return fromReasonUnavailable(matched.getGoodwillAndIntangiblesReason(), "GOODWILL_INTANGIBLES_MISSING", "缺少商譽與無形資產資料，無法計算有形帳面值。");
        }
        return MetricResult.available(matched.getCommonEquity().subtract(matched.getGoodwillAndIntangibles(), MC).setScale(4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computePToTbv(
            MetricResult<BigDecimal> tbv, FxContext fx, BigDecimal marketCapAtDay, String sharesReason) {
        if (!fx.usable()) {
            return unavailable(statusOfCode(fx.failReason()), fx.failReason(), "缺少有效匯率或幣別未對齊，無法計算 P/TBV。");
        }
        if (tbv.status() != MetricStatus.AVAILABLE || tbv.value() == null) {
            return tbv;
        }
        if (tbv.value().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_TBV", "有形普通股帳面值非正，P/TBV 不適用（N.M.）。");
        }
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日市值，無法計算 P/TBV。");
        }
        BigDecimal tbvInQuote = tbv.value().multiply(fx.multiplier(), MC);
        return MetricResult.available(marketCapAtDay.divide(tbvInQuote, 4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeInterestCoverage(StockFinancialPeriod matched) {
        if (matched.getOperatingProfitTtm() == null) {
            return fromReasonUnavailable(matched.getOperatingProfitTtmReason(), "OPERATING_PROFIT_MISSING", "缺少 TTM 營業利益，無法計算利息保障倍數。");
        }
        if (matched.getInterestExpenseTtm() == null) {
            return fromReasonUnavailable(matched.getInterestExpenseTtmReason(), "INTEREST_EXPENSE_MISSING", "缺少 TTM 利息費用，無法計算利息保障倍數。");
        }
        if (matched.getInterestExpenseTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_INTEREST_EXPENSE", "TTM 利息費用為零或非正，利息保障倍數不適用（N.M.）。");
        }
        return MetricResult.available(matched.getOperatingProfitTtm().divide(matched.getInterestExpenseTtm(), 4, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeShortTermLiquidityCoverage(StockFinancialPeriod matched) {
        if (!isAvailable(matched.getCashLikeAssetsStatus(), matched.getCashLikeAssets())) {
            return fromPeriodUnavailable(matched.getCashLikeAssetsStatus(), matched.getCashLikeAssetsReason(), "BALANCE_INPUT_MISSING");
        }
        if (!isAvailable(matched.getOperatingCashFlowTtmStatus(), matched.getOperatingCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getOperatingCashFlowTtmStatus(), matched.getOperatingCashFlowTtmReason(), "OPERATING_CASH_FLOW_MISSING");
        }
        if (matched.getCurrentDebt() == null) {
            return fromReasonUnavailable(matched.getCurrentDebtReason(), "CURRENT_DEBT_MISSING", "缺少顯式一年內到期計息債務，無法計算短期流動性覆蓋率。");
        }
        if (matched.getCurrentDebt().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_CURRENT_DEBT", "一年內到期短期計息債務為零，短期債務覆蓋倍數不適用（無短期償債壓力）。");
        }
        BigDecimal numerator = matched.getCashLikeAssets().add(matched.getOperatingCashFlowTtm(), MC);
        return MetricResult.available(numerator.divide(matched.getCurrentDebt(), 4, RoundingMode.HALF_UP));
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
            List<StockSplit> splits, LocalDate valuationDate, String splitSyncError, boolean threeYearCagr,
            boolean adrCompanyLevel, String blockReason) {
        if ("ADR_RATIO_UNVERIFIED".equals(blockReason)) {
            return unavailable(MetricStatus.UNVERIFIED, "ADR_RATIO_UNVERIFIED", "ADR 換股比例未核實，暫不計算跨期 EPS 成長。");
        }
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
        if (adrCompanyLevel) {
            String currSrc = matched.getEpsTtmSource();
            String priorSrc = prior.getEpsTtmSource();
            if (currSrc == null || priorSrc == null || !currSrc.equals(priorSrc) || currSrc.contains("AND")) {
                return unavailable(MetricStatus.UNVERIFIED, "ADR_SHARES_BASIS_UNVERIFIED", "ADR 跨期 EPS 來源口徑不一致（每 ADR vs 每原股），暫不計算 EPS 成長。");
            }
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

    private MetricResult<BigDecimal> computeFcfCagr3y(StockFinancialPeriod matched, ComparablePeriodMatch prior3y) {
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (prior3y.period() == null) {
            return unavailable(prior3y.failStatus(), prior3y.failReason(), "缺少三年前可比自由現金流報期。");
        }
        StockFinancialPeriod prior = prior3y.period();
        if (!isAvailable(prior.getFreeCashFlowTtmStatus(), prior.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(prior.getFreeCashFlowTtmStatus(), prior.getFreeCashFlowTtmReason(), "PRIOR_FCF_TTM_MISSING");
        }
        if (prior.getFreeCashFlowTtm().signum() <= 0 || matched.getFreeCashFlowTtm().signum() <= 0) {
            return unavailable(MetricStatus.NOT_APPLICABLE, "NON_POSITIVE_FCF", "基期或當期自由現金流非正，不適用三年複合成長率。");
        }
        return MetricResult.available(cagr3y(prior.getFreeCashFlowTtm(), matched.getFreeCashFlowTtm()));
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
            StockFinancialPeriod matched, FxContext fx, BigDecimal marketCapAtDay, String sharesReason) {
        if (!fx.usable()) {
            return unavailable(statusOfCode(fx.failReason()), fx.failReason(), "財報幣別與股價幣別缺少有效匯率換算，暫不計算自由現金流殖利率。");
        }
        if (!isAvailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtm())) {
            return fromPeriodUnavailable(matched.getFreeCashFlowTtmStatus(), matched.getFreeCashFlowTtmReason(), "FREE_CASH_FLOW_TTM_MISSING");
        }
        if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
            String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
            return unavailable(statusOfCode(code), code, "缺少已核實的當日市值，無法計算自由現金流殖利率。");
        }
        BigDecimal fcfInQuote = matched.getFreeCashFlowTtm().multiply(fx.multiplier(), MC);
        return MetricResult.available(fcfInQuote.divide(marketCapAtDay, 6, RoundingMode.HALF_UP));
    }

    private MetricResult<BigDecimal> computeEarningsYield(
            StockFinancialPeriod matched, FxContext fx, BigDecimal closePrice, BigDecimal marketCapAtDay,
            String sharesReason, BigDecimal epsAtDay, String peReason, boolean adrCompanyLevel) {
        if (!fx.usable()) {
            return unavailable(statusOfCode(fx.failReason()), fx.failReason(), "財報幣別與股價幣別缺少有效匯率換算，暫不計算盈餘殖利率。");
        }
        if (adrCompanyLevel) {
            if (!isAvailable(matched.getNetIncomeCommonTtmStatus(), matched.getNetIncomeCommonTtm())) {
                return fromPeriodUnavailable(matched.getNetIncomeCommonTtmStatus(), matched.getNetIncomeCommonTtmReason(), "NET_INCOME_COMMON_MISSING");
            }
            if (marketCapAtDay == null || marketCapAtDay.signum() <= 0) {
                String code = sharesReason != null ? sharesReason : "MARKET_CAP_MISSING";
                return unavailable(statusOfCode(code), code, "缺少已核實的當日 ADR 公司市值，無法計算盈餘殖利率。");
            }
            BigDecimal netIncomeInQuote = matched.getNetIncomeCommonTtm().multiply(fx.multiplier(), MC);
            return MetricResult.available(netIncomeInQuote.divide(marketCapAtDay, 6, RoundingMode.HALF_UP));
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

    private MetricResult<BigDecimal> fromReasonUnavailable(ValuationReason reason, String fallbackCode, String message) {
        String code = reason != null ? reason.name() : fallbackCode;
        MetricStatus status = reason != null ? reason.metricStatus() : statusOfCode(code);
        return unavailable(status, code, message);
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
