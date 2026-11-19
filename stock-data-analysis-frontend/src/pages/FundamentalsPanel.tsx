import React from "react";
import { Box, Chip, Divider, Grid, Paper, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { ForwardMetrics, FundamentalSnapshot, InvestmentSummary, MetricResult } from "../services/types/dto/analysis";
import { formatNumber, readableState, reasonLabel } from "./AnalysisPanels";

const FUNDAMENTAL_REASON_DEFAULTS: Record<string, string> = {
  SWUNG_TO_LOSS: "由盈轉虧",
  FORWARD_SWUNG_TO_LOSS: "預期由盈轉虧",
  TURNAROUND_FROM_LOSS: "由虧轉盈（基期虧損）",
  NON_POSITIVE_BASE_EPS: "基期虧損不適用",
  FCF_TURNED_NEGATIVE: "由正轉負",
  FCF_TURNAROUND_FROM_NEGATIVE: "由負轉正（基期為負）",
  NON_POSITIVE_NET_INCOME: "淨利非正不適用",
  PRIOR_COMPARABLE_PERIOD_MISSING: "缺少可比前期財報",
  REPORTING_CURRENCY_CHANGED: "跨期財報幣別變更",
  ACCOUNTING_STANDARDS_CHANGED: "跨期會計準則變更",
  SEC_FILING_SHARES_REQUIRED: "需獨立 SEC 申報股數",
  SHARE_BASIS_PERIOD_NOT_INDEPENDENT: "前後期股數申報非獨立年度區間",
  EPS_GROWTH_BELOW_PEG_THRESHOLD: "成長率低於 5% 不算 PEG",
  EPS_GROWTH_LOW_BASE_SPIKE: "單年低基期暴衝不算 PEG",
  EARNINGS_FORECAST_MISSING: "尚無市場預期資料",
  NO_UNREPORTED_FORECAST_PERIOD: "目前無合資格財期可供修正比較",
  ANNUAL_EARNINGS_FORECAST_MISSING: "缺少可用的未結束年度預期",
  FORECAST_FISCAL_PERIOD_UNVERIFIED: "財期結算日待核實",
  EARNINGS_FORECAST_STALE: "預期觀察已逾 45 天",
  FORECAST_REVISION_BASE_MISSING: "此財期缺少可比歷史基準",
  NON_POSITIVE_FORWARD_EPS: "預期虧損（N.M.）",
  NON_POSITIVE_FORWARD_REVENUE: "預期營收非正（N.M.）",
  FORWARD_EPS_GROWTH_BELOW_PEG_THRESHOLD: "預期成長低於 5% 不算 PEG",
  FORWARD_EPS_GROWTH_ABOVE_PEG_CAP: "預期成長逾 100% 不算 PEG",
  NON_POSITIVE_PRIOR_FORECAST_EPS: "基期預期非正（N.M.）",
  NON_POSITIVE_PRIOR_FORECAST_REVENUE: "基期預期非正（N.M.）",
};

const formatRatioPct = (metric?: MetricResult<number> | null, signed = true): string | null => {
  if (!metric || metric.status !== "AVAILABLE" || metric.value == null || !Number.isFinite(metric.value)) {
    return null;
  }
  const pct = metric.value * 100;
  return (signed && pct > 0 ? "+" : "") + pct.toFixed(2) + "%";
};

const formatPpt = (metric?: MetricResult<number> | null): string | null => {
  if (!metric || metric.status !== "AVAILABLE" || metric.value == null || !Number.isFinite(metric.value)) {
    return null;
  }
  const ppt = metric.value * 100;
  return (ppt > 0 ? "+" : "") + ppt.toFixed(2) + " ppt";
};

const formatSignedCompactAmount = (value?: number | null, currency?: string | null): string => {
  if (value == null || !Number.isFinite(value)) return "—";
  const cur = currency?.toUpperCase() ?? "";
  const prefix = cur === "USD" ? "$" : "";
  const suffix = cur && cur !== "USD" ? ` ${cur}` : "";
  if (value === 0) return `${prefix}0${suffix}`;
  const sign = value < 0 ? "-" : "";
  const abs = Math.abs(value);
  if (abs >= 1e12) return `${sign}${prefix}${(abs / 1e12).toFixed(2)}T${suffix}`;
  if (abs >= 1e9) return `${sign}${prefix}${(abs / 1e9).toFixed(2)}B${suffix}`;
  if (abs >= 1e6) return `${sign}${prefix}${(abs / 1e6).toFixed(1)}M${suffix}`;
  return `${sign}${prefix}${formatNumber(abs, 0)}${suffix}`;
};

function MetricRow({ label, metric, mode = "signedPct", suffixNote, currency, warnBelow }: {
  label: string;
  metric?: MetricResult<number> | null;
  mode?: "signedPct" | "plainPct" | "multiple" | "ppt" | "amount";
  suffixNote?: string | null;
  currency?: string | null;
  warnBelow?: number;
}) {
  const { t } = useTranslation();
  const available = metric?.status === "AVAILABLE" && metric.value != null && Number.isFinite(metric.value);
  const firstCode = metric?.reasons?.[0]?.code ?? null;
  const fallbackReason = firstCode
    ? t(`analysisReport.reasons.${firstCode}`, {
      defaultValue: FUNDAMENTAL_REASON_DEFAULTS[firstCode] ?? reasonLabel(t, firstCode),
    })
    : readableState(t, metric?.status);

  let displayVal = "—";
  let color: string | undefined;
  if (available && metric?.value != null) {
    if (mode === "multiple") {
      displayVal = `${formatNumber(metric.value)}x`;
      if (warnBelow != null && metric.value < warnBelow) {
        color = "error.main";
      }
    } else if (mode === "ppt") {
      displayVal = formatPpt(metric) ?? "—";
      color = metric.value > 0 ? "success.main" : metric.value < 0 ? "error.main" : undefined;
    } else if (mode === "amount") {
      displayVal = formatSignedCompactAmount(metric.value, currency);
      if (metric.value < 0) color = "error.main";
    } else {
      displayVal = formatRatioPct(metric, mode === "signedPct") ?? "—";
      if (mode === "signedPct") {
        color = metric.value > 0 ? "success.main" : metric.value < 0 ? "error.main" : undefined;
      }
    }
  }

  return <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
    <Typography variant="body2" color="text.secondary">{label}</Typography>
    <Box sx={{ textAlign: "right" }}>
      {available ? (
        <Typography variant="body2" fontWeight={700} color={color}>
          {displayVal}{suffixNote ? `（${suffixNote}）` : ""}
        </Typography>
      ) : (
        <Typography variant="caption" color="text.secondary">{fallbackReason}</Typography>
      )}
    </Box>
  </Stack>;
}

export function FundamentalsPanel({ fundamentals, summary, forward }: {
  fundamentals?: FundamentalSnapshot | null;
  summary?: InvestmentSummary | null;
  forward?: ForwardMetrics | null;
}) {
  const { t } = useTranslation();
  if (!fundamentals && !forward) {
    return null;
  }

  const pegNote = fundamentals?.pegGrowthBasis
    ? t(`analysisReport.fundamentals.pegBasis.${fundamentals.pegGrowthBasis}`, {
      defaultValue: fundamentals.pegGrowthBasis === "TRAILING_EPS_CAGR_3Y" ? "按 3Y CAGR" : "按 1Y YoY",
    })
    : null;

  const debtVal = fundamentals?.netDebtToEbitda?.status === "AVAILABLE" ? fundamentals.netDebtToEbitda.value : null;
  const debtNote = debtVal != null
    ? (debtVal < 0
      ? t("analysisReport.fundamentals.notes.netCash", { defaultValue: "淨現金" })
      : debtVal > 3
        ? t("analysisReport.fundamentals.notes.highLeverage", { defaultValue: "偏高槓桿" })
        : null)
    : null;

  const shareVal = fundamentals?.netShareChangeYoy?.status === "AVAILABLE" ? fundamentals.netShareChangeYoy.value : null;
  const shareNote = shareVal != null
    ? (shareVal < -0.005
      ? t("analysisReport.fundamentals.notes.netBuyback", { defaultValue: "淨回購" })
      : shareVal > 0.005
        ? t("analysisReport.fundamentals.notes.netDilution", { defaultValue: "淨稀釋" })
        : t("analysisReport.fundamentals.notes.roughlyFlat", { defaultValue: "基本持平" }))
    : null;

  const fwdPegNote = forward?.forwardPegBasis
    ? t(`analysisReport.fundamentals.forwardPegBasis.${forward.forwardPegBasis}`, {
      defaultValue: forward.forwardPegBasis === "FORWARD_FY1_VS_TTM" ? "按 FY1 vs TTM" : "按 FY2 vs FY1",
    })
    : null;
  const revTypeText = forward?.revisionPeriodType
    ? t(`analysisReport.fundamentals.periodTypes.${forward.revisionPeriodType}`, {
      defaultValue: forward.revisionPeriodType === "ANNUAL" ? "年度" : forward.revisionPeriodType === "QUARTERLY" ? "單季" : "",
    })
    : "";

  const hasQualityBlock = Boolean(
    fundamentals && (
      fundamentals.grossMargin || fundamentals.operatingMargin || fundamentals.sbcTtm
      || fundamentals.adjFcfTtm || fundamentals.roic || fundamentals.ruleOf40
      || fundamentals.interestCoverage || fundamentals.pToTbv
    )
  );

  const repCcy = fundamentals?.reportingCurrency ?? null;

  return <Paper component="section" aria-label={t("analysisReport.fundamentals.sectionAria", { defaultValue: "基本面成長與品質指標" })} variant="outlined" sx={{ p: 2.5, mt: 2 }}>
    {fundamentals && <>
      <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "baseline" }} spacing={1}>
        <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
          <Typography variant="h6" fontWeight={700}>
            {t("analysisReport.fundamentals.title", { defaultValue: "基本面成長、獲利品質與資本回報（拆股對齊）" })}
          </Typography>
          {fundamentals.marginCompressionConsecutive && (
            <Chip size="small" color="error" label={t("analysisReport.fundamentals.quality.marginCompressionBadge", { defaultValue: "毛利／營益率連兩季壓縮警戒" })} />
          )}
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {t("analysisReport.fundamentals.periodMeta", {
            periodEnd: fundamentals.periodEnd ?? "—",
            defaultValue: `當期財報截至：${fundamentals.periodEnd ?? "—"}`,
          })}
          {fundamentals.priorYearPeriodEnd ? ` · ${t("analysisReport.fundamentals.priorYearPrefix", { defaultValue: "對比去年同期：" })}${fundamentals.priorYearPeriodEnd}` : ""}
          {fundamentals.threeYearAgoPeriodEnd ? ` · ${t("analysisReport.fundamentals.threeYearPrefix", { defaultValue: "三年前基期：" })}${fundamentals.threeYearAgoPeriodEnd}` : ""}
          {fundamentals.reportingCurrency ? ` · ${t("analysisReport.fundamentals.currencyPrefix", { defaultValue: "財報幣別：" })}${fundamentals.reportingCurrency}` : ""}
        </Typography>
      </Stack>
      <Divider sx={{ my: 1.5 }} />
      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 4 }}>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>{t("analysisReport.fundamentals.groups.growthAndPeg", { defaultValue: "成長動能與 PEG" })}</Typography>
          <Stack spacing={0.8}>
            <MetricRow label={t("analysisReport.fundamentals.metrics.revenueGrowthYoy", { defaultValue: "TTM 營業收入年增率（YoY）" })} metric={fundamentals.revenueGrowthYoy} />
            <MetricRow label={t("analysisReport.fundamentals.metrics.epsGrowthYoy", { defaultValue: "TTM 每股盈利年增率（EPS YoY）" })} metric={fundamentals.epsGrowthYoy} />
            {!fundamentals.financialInstitution && (
              <MetricRow label={t("analysisReport.fundamentals.metrics.fcfGrowthYoy", { defaultValue: "TTM 自由現金流年增率（FCF YoY）" })} metric={fundamentals.fcfGrowthYoy} />
            )}
            <MetricRow label={t("analysisReport.fundamentals.metrics.revenueCagr3y", { defaultValue: "3 年營收複合成長（CAGR）" })} metric={fundamentals.revenueCagr3y} />
            <MetricRow label={t("analysisReport.fundamentals.metrics.epsCagr3y", { defaultValue: "3 年 EPS 複合成長（CAGR）" })} metric={fundamentals.epsCagr3y} />
            {!fundamentals.financialInstitution && fundamentals.fcfCagr3y && (
              <MetricRow label={t("analysisReport.fundamentals.quality.fcfCagr3y", { defaultValue: "3 年 FCF 複合成長（CAGR）" })} metric={fundamentals.fcfCagr3y} />
            )}
            <MetricRow label="Trailing PEG" metric={fundamentals.trailingPeg} mode="multiple" suffixNote={pegNote} />
          </Stack>
        </Grid>

        <Grid size={{ xs: 12, md: 4 }}>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>{t("analysisReport.fundamentals.groups.profitabilityAndBalance", { defaultValue: "獲利品質與資產負債結構" })}</Typography>
          <Stack spacing={0.8}>
            <MetricRow label={t("analysisReport.fundamentals.metrics.roe", { defaultValue: "普通股權益報酬率（ROE）" })} metric={fundamentals.roe} mode="plainPct" />
            {!fundamentals.financialInstitution ? <>
              {fundamentals.roic && (
                <MetricRow label={t("analysisReport.fundamentals.quality.roic", { defaultValue: "投入資本回報率（ROIC）" })} metric={fundamentals.roic} mode="plainPct" />
              )}
              <MetricRow label={t("analysisReport.fundamentals.metrics.fcfMargin", { defaultValue: "自由現金流利潤率（FCF Margin）" })} metric={fundamentals.fcfMargin} mode="plainPct" />
              <MetricRow label={t("analysisReport.fundamentals.metrics.fcfConversion", { defaultValue: "淨利現金轉換率（FCF / 淨利）" })} metric={fundamentals.fcfConversion} mode="plainPct" />
              <MetricRow label={t("analysisReport.fundamentals.metrics.netDebtToEbitda", { defaultValue: "淨負債／EBITDA" })} metric={fundamentals.netDebtToEbitda} mode="multiple" suffixNote={debtNote} />
              {fundamentals.pToTbv && (
                <MetricRow label={t("analysisReport.fundamentals.quality.pToTbv", { defaultValue: "市價／有形淨值（P/TBV）" })} metric={fundamentals.pToTbv} mode="multiple" />
              )}
            </> : (
              <>
                {fundamentals.pToTbv && (
                  <MetricRow label={t("analysisReport.fundamentals.quality.pToTbv", { defaultValue: "市價／有形淨值（P/TBV）" })} metric={fundamentals.pToTbv} mode="multiple" />
                )}
                <Typography variant="caption" color="text.secondary">
                  {t("analysisReport.fundamentals.financialInstitutionNote", { defaultValue: "銀行與保險業資產負債結構不適用自由現金流利潤率、淨負債／EBITDA 與非金融品質指標。" })}
                </Typography>
              </>
            )}
          </Stack>
        </Grid>

        <Grid size={{ xs: 12, md: 4 }}>
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>{t("analysisReport.fundamentals.groups.yieldsAndShareholder", { defaultValue: "殖利率對照與股本變化" })}</Typography>
          <Stack spacing={0.8}>
            {!fundamentals.financialInstitution && (
              <MetricRow label={t("analysisReport.fundamentals.metrics.fcfYield", { defaultValue: "自由現金流殖利率（FCF Yield）" })} metric={fundamentals.fcfYield} mode="plainPct" />
            )}
            <MetricRow label={t("analysisReport.fundamentals.metrics.earningsYield", { defaultValue: "盈餘殖利率（Earnings Yield）" })} metric={fundamentals.earningsYield} mode="plainPct" />
            <MetricRow
              label={fundamentals.financialInstitution || summary?.yieldSpreadBasis === "EARNINGS_YIELD_MINUS_10Y"
                ? t("analysisReport.fundamentals.metrics.earningsYieldSpread10y", { defaultValue: "盈餘殖利率減 10Y 美債" })
                : t("analysisReport.fundamentals.metrics.fcfYieldSpread10y", { defaultValue: "FCF 殖利率減 10Y 美債" })}
              metric={summary?.yieldSpreadVs10y}
              mode="signedPct"
            />
            {fundamentals.totalShareholderYield && (
              <MetricRow label={t("analysisReport.fundamentals.quality.totalShareholderYield", { defaultValue: "總股東回報率（股息＋淨回購）" })} metric={fundamentals.totalShareholderYield} mode="signedPct" />
            )}
            <MetricRow label={t("analysisReport.fundamentals.metrics.netShareChangeYoy", { defaultValue: "流通股數年變化率（SEC 申報）" })} metric={fundamentals.netShareChangeYoy} suffixNote={shareNote} />
          </Stack>
        </Grid>
      </Grid>

      {hasQualityBlock && !fundamentals.financialInstitution && (
        <Box sx={{ mt: 2.5 }}>
          <Divider sx={{ mb: 2 }} />
          <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1.25 }}>
            {t("analysisReport.fundamentals.quality.sectionTitle", { defaultValue: "財務品質深化指標（SBC、毛利結構、ROIC 與償債覆蓋）" })}
          </Typography>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.8 }}>{t("analysisReport.fundamentals.quality.sbcAndAdjFcfTitle", { defaultValue: "SBC 稀釋與實質現金流" })}</Typography>
              <Stack spacing={0.8}>
                <MetricRow label={t("analysisReport.fundamentals.quality.sbcTtm", { defaultValue: "TTM 股票薪酬（SBC）" })} metric={fundamentals.sbcTtm} mode="amount" currency={repCcy} />
                <MetricRow label={t("analysisReport.fundamentals.quality.sbcToRevenue", { defaultValue: "SBC 佔營收比" })} metric={fundamentals.sbcToRevenue} mode="plainPct" />
                <MetricRow label={t("analysisReport.fundamentals.quality.adjFcfTtm", { defaultValue: "扣 SBC 實質自由現金流（Adj. FCF）" })} metric={fundamentals.adjFcfTtm} mode="amount" currency={repCcy} />
                <MetricRow label={t("analysisReport.fundamentals.quality.pToAdjFcf", { defaultValue: "P / Adj. FCF" })} metric={fundamentals.pToAdjFcf} mode="multiple" />
                <MetricRow label={t("analysisReport.fundamentals.quality.ruleOf40", { defaultValue: "Rule of 40（營收 YoY ＋ FCF 率）" })} metric={fundamentals.ruleOf40} mode="plainPct" />
              </Stack>
            </Grid>

            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.8 }}>{t("analysisReport.fundamentals.quality.marginsAndEvTitle", { defaultValue: "毛利率與營業利潤率趨勢" })}</Typography>
              <Stack spacing={0.8}>
                <MetricRow label={t("analysisReport.fundamentals.quality.grossMargin", { defaultValue: "TTM 毛利率（Gross Margin）" })} metric={fundamentals.grossMargin} mode="plainPct" />
                <MetricRow label={t("analysisReport.fundamentals.quality.grossMarginYoyPpt", { defaultValue: "毛利率年變動（YoY）" })} metric={fundamentals.grossMarginYoyPpt} mode="ppt" />
                <MetricRow label={t("analysisReport.fundamentals.quality.grossMarginQoqPpt", { defaultValue: "毛利率季變動（QoQ）" })} metric={fundamentals.grossMarginQoqPpt} mode="ppt" />
                <MetricRow label={t("analysisReport.fundamentals.quality.operatingMargin", { defaultValue: "TTM 營業利潤率（Operating Margin）" })} metric={fundamentals.operatingMargin} mode="plainPct" />
                <MetricRow label={t("analysisReport.fundamentals.quality.operatingMarginYoyPpt", { defaultValue: "營業利潤率年變動（YoY）" })} metric={fundamentals.operatingMarginYoyPpt} mode="ppt" />
                <MetricRow label={t("analysisReport.fundamentals.quality.operatingMarginQoqPpt", { defaultValue: "營業利潤率季變動（QoQ）" })} metric={fundamentals.operatingMarginQoqPpt} mode="ppt" />
              </Stack>
            </Grid>

            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.8 }}>{t("analysisReport.fundamentals.quality.capitalAndSolvencyTitle", { defaultValue: "資本效率、成長倍數與償債安全" })}</Typography>
              <Stack spacing={0.8}>
                <MetricRow label={t("analysisReport.fundamentals.quality.evToSales", { defaultValue: "EV / Sales" })} metric={fundamentals.evToSales} mode="multiple" />
                <MetricRow label={t("analysisReport.fundamentals.quality.evToGrossProfit", { defaultValue: "EV / Gross Profit" })} metric={fundamentals.evToGrossProfit} mode="multiple" />
                <MetricRow label={t("analysisReport.fundamentals.quality.nopat", { defaultValue: "稅後營業淨利（NOPAT）" })} metric={fundamentals.nopat} mode="amount" currency={repCcy} />
                <MetricRow label={t("analysisReport.fundamentals.quality.investedCapital", { defaultValue: "投入資本（Invested Capital）" })} metric={fundamentals.investedCapital} mode="amount" currency={repCcy} />
                <MetricRow label={t("analysisReport.fundamentals.quality.tangibleBookValue", { defaultValue: "有形普通股淨值（TBV）" })} metric={fundamentals.tangibleBookValue} mode="amount" currency={repCcy} />
                <MetricRow label={t("analysisReport.fundamentals.quality.interestCoverage", { defaultValue: "利息保障倍數（EBIT / 利息）" })} metric={fundamentals.interestCoverage} mode="multiple" warnBelow={3} />
                <MetricRow label={t("analysisReport.fundamentals.quality.shortTermLiquidityCoverage", { defaultValue: "短期流動性覆蓋（(現金＋OCF)/短債）" })} metric={fundamentals.shortTermLiquidityCoverage} mode="multiple" warnBelow={1} />
              </Stack>
            </Grid>
          </Grid>
        </Box>
      )}
    </>}

    {forward && (
      <Box sx={{ mt: fundamentals ? 2.5 : 0 }}>
        {fundamentals && <Divider sx={{ mb: 2 }} />}
        <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} spacing={1} sx={{ mb: 1.2 }}>
          <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
            <Typography variant="subtitle1" fontWeight={700}>{t("analysisReport.fundamentals.forward.title", { defaultValue: "前瞻估值與分析師預期修正" })}</Typography>
            <Chip size="small" variant="outlined" color="info" label={t("analysisReport.fundamentals.forward.badge", { defaultValue: "市場預期觀察（與已公布財報分軌）" })} />
          </Stack>
          <Typography variant="caption" color="text.secondary">
            {t("analysisReport.fundamentals.forward.valuationDatePrefix", { defaultValue: "估值日：" })}{forward.valuationDate}
            {forward.targetFiscalPeriod ? ` · ${t("analysisReport.fundamentals.forward.targetPeriodPrefix", { defaultValue: "年度預期財期：" })}${forward.targetFiscalPeriod}` : ""}
            {forward.forecastObservationDate ? `（${t("analysisReport.fundamentals.forward.obsDatePrefix", { defaultValue: "觀察日 " })}${forward.forecastObservationDate}${forward.forecastSource ? ` · ${forward.forecastSource}` : ""}）` : ""}
            {(forward.forecastCurrency || forward.revisionCurrency) ? ` · ${t("analysisReport.fundamentals.forward.currencyPrefix", { defaultValue: "預期幣別：" })}${forward.forecastCurrency ?? forward.revisionCurrency}` : ""}
          </Typography>
        </Stack>
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 6 }}>
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.8 }}>{t("analysisReport.fundamentals.forward.multiplesTitle", { defaultValue: "前瞻估值倍數（未結束年度共識）" })}</Typography>
            <Stack spacing={0.8}>
              <MetricRow label="Forward P/E" metric={forward.forwardPe} mode="multiple" suffixNote={forward.targetFiscalPeriod} />
              <MetricRow label="Forward P/S" metric={forward.forwardPs} mode="multiple" suffixNote={forward.targetFiscalPeriod} />
              <MetricRow label={t("analysisReport.fundamentals.forward.impliedEpsGrowth", { defaultValue: "預期年度 EPS 隱含成長（vs TTM）" })} metric={forward.impliedEpsGrowth} mode="signedPct" />
              <MetricRow label="Forward PEG" metric={forward.forwardPeg} mode="multiple" suffixNote={fwdPegNote} />
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.8 }}>
              {t("analysisReport.fundamentals.forward.revisionsTitle", { defaultValue: "同財期預期上修／下修幅度" })}
              {forward.revisionFiscalPeriod ? `（${forward.revisionFiscalPeriod}${revTypeText ? ` · ${revTypeText}` : ""}${forward.revisionCurrency ? ` · ${forward.revisionCurrency}` : ""}${forward.revisionSource ? ` · ${forward.revisionSource}` : ""}，${t("analysisReport.fundamentals.forward.obsDatePrefix", { defaultValue: "觀察日 " })}${forward.revisionObservationDate ?? "—"}）` : ""}
            </Typography>
            <Stack spacing={0.8}>
              <MetricRow
                label={t("analysisReport.fundamentals.forward.epsRevision30d", { defaultValue: "近 30 日預期 EPS 修正" })}
                metric={forward.epsRevision30d}
                suffixNote={forward.epsRevision30dBasis === "PROVIDER_TRAILING"
                  ? t("analysisReport.fundamentals.forward.providerTrailing30d", { defaultValue: "來源 30 日前回溯基準" })
                  : (forward.revisionBaseDate30d ? `${t("analysisReport.fundamentals.forward.vsDatePrefix", { defaultValue: "對比 " })}${forward.revisionBaseDate30d}` : null)}
              />
              <MetricRow
                label={t("analysisReport.fundamentals.forward.epsRevision90d", { defaultValue: "近 90 日預期 EPS 修正" })}
                metric={forward.epsRevision90d}
                suffixNote={forward.epsRevision90dBasis === "PROVIDER_TRAILING"
                  ? t("analysisReport.fundamentals.forward.providerTrailing90d", { defaultValue: "來源 90 日前回溯基準" })
                  : (forward.revisionBaseDate90d ? `${t("analysisReport.fundamentals.forward.vsDatePrefix", { defaultValue: "對比 " })}${forward.revisionBaseDate90d}` : null)}
              />
              <MetricRow
                label={t("analysisReport.fundamentals.forward.revenueRevision30d", { defaultValue: "近 30 日預期營收修正" })}
                metric={forward.revenueRevision30d}
                suffixNote={(forward.revenueRevisionBaseDate30d ?? (forward.epsRevision30dBasis !== "PROVIDER_TRAILING" ? forward.revisionBaseDate30d : null))
                  ? `${t("analysisReport.fundamentals.forward.vsDatePrefix", { defaultValue: "對比 " })}${forward.revenueRevisionBaseDate30d ?? forward.revisionBaseDate30d}` : null}
              />
              <MetricRow
                label={t("analysisReport.fundamentals.forward.revenueRevision90d", { defaultValue: "近 90 日預期營收修正" })}
                metric={forward.revenueRevision90d}
                suffixNote={(forward.revenueRevisionBaseDate90d ?? (forward.epsRevision90dBasis !== "PROVIDER_TRAILING" ? forward.revisionBaseDate90d : null))
                  ? `${t("analysisReport.fundamentals.forward.vsDatePrefix", { defaultValue: "對比 " })}${forward.revenueRevisionBaseDate90d ?? forward.revisionBaseDate90d}` : null}
              />
            </Stack>
          </Grid>
        </Grid>
      </Box>
    )}
  </Paper>;
}
