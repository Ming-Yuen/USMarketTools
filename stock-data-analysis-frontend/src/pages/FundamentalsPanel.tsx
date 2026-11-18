import React from "react";
import { Box, Divider, Grid, Paper, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { FundamentalSnapshot, InvestmentSummary, MetricResult } from "../services/types/dto/analysis";
import { formatNumber, reasonLabel, statusLabel } from "./AnalysisPanels";

const FUNDAMENTAL_REASON_LABELS: Record<string, string> = {
  SWUNG_TO_LOSS: "由盈轉虧",
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
};

const formatRatioPct = (metric?: MetricResult<number> | null, signed = true): string | null => {
  if (!metric || metric.status !== "AVAILABLE" || metric.value == null || !Number.isFinite(metric.value)) {
    return null;
  }
  const pct = metric.value * 100;
  return (signed && pct > 0 ? "+" : "") + pct.toFixed(2) + "%";
};

function MetricRow({ label, metric, mode = "signedPct", suffixNote }: {
  label: string;
  metric?: MetricResult<number> | null;
  mode?: "signedPct" | "plainPct" | "multiple";
  suffixNote?: string | null;
}) {
  const { t } = useTranslation();
  const available = metric?.status === "AVAILABLE" && metric.value != null && Number.isFinite(metric.value);
  const firstCode = metric?.reasons?.[0]?.code ?? null;
  const fallbackReason = firstCode ? (FUNDAMENTAL_REASON_LABELS[firstCode] ?? reasonLabel(t, firstCode)) : statusLabel(metric?.status);

  let displayVal = "—";
  let color: string | undefined;
  if (available && metric?.value != null) {
    if (mode === "multiple") {
      displayVal = `${formatNumber(metric.value)}x`;
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

export function FundamentalsPanel({ fundamentals, summary }: {
  fundamentals?: FundamentalSnapshot | null;
  summary?: InvestmentSummary | null;
}) {
  const { t } = useTranslation();
  if (!fundamentals) {
    return null;
  }

  const pegNote = fundamentals.pegGrowthBasis
    ? t(`analysisReport.fundamentals.pegBasis.${fundamentals.pegGrowthBasis}`, {
      defaultValue: fundamentals.pegGrowthBasis === "TRAILING_EPS_CAGR_3Y" ? "按 3Y CAGR" : "按 1Y YoY",
    })
    : null;

  const debtVal = fundamentals.netDebtToEbitda?.status === "AVAILABLE" ? fundamentals.netDebtToEbitda.value : null;
  const debtNote = debtVal != null ? (debtVal < 0 ? "淨現金" : debtVal > 3 ? "偏高槓桿" : null) : null;

  const shareVal = fundamentals.netShareChangeYoy?.status === "AVAILABLE" ? fundamentals.netShareChangeYoy.value : null;
  const shareNote = shareVal != null ? (shareVal < -0.005 ? "淨回購" : shareVal > 0.005 ? "淨稀釋" : "基本持平") : null;

  return <Paper component="section" aria-label="基本面成長與品質指標" variant="outlined" sx={{ p: 2.5, mt: 2 }}>
    <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "baseline" }} spacing={1}>
      <Typography variant="h6" fontWeight={700}>
        {t("analysisReport.fundamentals.title", { defaultValue: "基本面成長、獲利品質與資本回報（拆股對齊）" })}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        當期財報截至：{fundamentals.periodEnd ?? "—"}
        {fundamentals.priorYearPeriodEnd ? ` · 對比去年同期：${fundamentals.priorYearPeriodEnd}` : ""}
        {fundamentals.threeYearAgoPeriodEnd ? ` · 三年前基期：${fundamentals.threeYearAgoPeriodEnd}` : ""}
        {fundamentals.reportingCurrency ? ` · 財報幣別：${fundamentals.reportingCurrency}` : ""}
      </Typography>
    </Stack>
    <Divider sx={{ my: 1.5 }} />
    <Grid container spacing={3}>
      <Grid size={{ xs: 12, md: 4 }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>成長動能與 PEG</Typography>
        <Stack spacing={0.8}>
          <MetricRow label="TTM 營業收入年增率（YoY）" metric={fundamentals.revenueGrowthYoy} />
          <MetricRow label="TTM 每股盈利年增率（EPS YoY）" metric={fundamentals.epsGrowthYoy} />
          {!fundamentals.financialInstitution && (
            <MetricRow label="TTM 自由現金流年增率（FCF YoY）" metric={fundamentals.fcfGrowthYoy} />
          )}
          <MetricRow label="3 年營收複合成長（CAGR）" metric={fundamentals.revenueCagr3y} />
          <MetricRow label="3 年 EPS 複合成長（CAGR）" metric={fundamentals.epsCagr3y} />
          <MetricRow label="Trailing PEG" metric={fundamentals.trailingPeg} mode="multiple" suffixNote={pegNote} />
        </Stack>
      </Grid>

      <Grid size={{ xs: 12, md: 4 }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>獲利品質與資產負債結構</Typography>
        <Stack spacing={0.8}>
          <MetricRow label="普通股權益報酬率（ROE）" metric={fundamentals.roe} mode="plainPct" />
          {!fundamentals.financialInstitution ? <>
            <MetricRow label="自由現金流利潤率（FCF Margin）" metric={fundamentals.fcfMargin} mode="plainPct" />
            <MetricRow label="淨利現金轉換率（FCF / 淨利）" metric={fundamentals.fcfConversion} mode="plainPct" />
            <MetricRow label="淨負債／EBITDA" metric={fundamentals.netDebtToEbitda} mode="multiple" suffixNote={debtNote} />
          </> : (
            <Typography variant="caption" color="text.secondary">
              銀行與保險業資產負債結構不適用自由現金流利潤率與淨負債／EBITDA。
            </Typography>
          )}
        </Stack>
      </Grid>

      <Grid size={{ xs: 12, md: 4 }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>殖利率對照與股本變化</Typography>
        <Stack spacing={0.8}>
          {!fundamentals.financialInstitution && (
            <MetricRow label="自由現金流殖利率（FCF Yield）" metric={fundamentals.fcfYield} mode="plainPct" />
          )}
          <MetricRow label="盈餘殖利率（Earnings Yield）" metric={fundamentals.earningsYield} mode="plainPct" />
          <MetricRow
            label={fundamentals.financialInstitution || summary?.yieldSpreadBasis === "EARNINGS_YIELD_MINUS_10Y" ? "盈餘殖利率減 10Y 美債" : "FCF 殖利率減 10Y 美債"}
            metric={summary?.yieldSpreadVs10y}
            mode="signedPct"
          />
          <MetricRow label="流通股數年變化率（SEC 申報）" metric={fundamentals.netShareChangeYoy} suffixNote={shareNote} />
        </Stack>
      </Grid>
    </Grid>
  </Paper>;
}
