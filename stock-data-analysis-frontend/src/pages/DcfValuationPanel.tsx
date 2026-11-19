import React, { useState } from "react";
import {
  Alert, Box, Chip, Divider, Grid, Paper, Stack, ToggleButton, ToggleButtonGroup, Typography,
} from "@mui/material";
import { useTranslation } from "react-i18next";
import { DcfBranch, DcfScenario, DcfValuation } from "../services/types/dto/analysis";
import { formatCompactMarketCap, formatNumber, readableState, reasonLabel } from "./AnalysisPanels";

const formatPct = (ratio?: number | null, signed = false, digits = 2): string => {
  if (ratio == null || !Number.isFinite(ratio)) return "—";
  const pct = ratio * 100;
  return `${signed && pct > 0 ? "+" : ""}${pct.toFixed(digits)}%`;
};

const formatPpt = (ratio?: number | null): string => {
  if (ratio == null || !Number.isFinite(ratio)) return "—";
  const ppt = ratio * 100;
  return `${ppt > 0 ? "+" : ""}${ppt.toFixed(2)} ppt`;
};

function ScenarioCard({ title, scenario, currency, highlight = false }: {
  title: string;
  scenario?: DcfScenario | null;
  currency?: string | null;
  highlight?: boolean;
}) {
  const { t } = useTranslation();
  const upside = scenario?.upsideDownsidePct;
  const upsideColor = upside == null ? "text.secondary" : upside >= 0 ? "success.main" : "error.main";

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 1.75,
        height: "100%",
        borderColor: highlight ? "primary.main" : undefined,
        borderWidth: highlight ? 1.5 : 1,
      }}
    >
      <Typography variant="subtitle2" fontWeight={700}>{title}</Typography>
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }}>
        {t("analysisReport.dcf.stage1Growth", { value: formatPct(scenario?.stage1GrowthRate, false, 1) })}
      </Typography>
      <Typography variant="h6" fontWeight={800}>
        {scenario?.intrinsicPricePerShare != null
          ? `${formatNumber(scenario.intrinsicPricePerShare)} ${currency ?? "USD"}`
          : "—"}
      </Typography>
      <Typography variant="body2" fontWeight={700} color={upsideColor} sx={{ mt: 0.5 }}>
        {upside != null
          ? t("analysisReport.dcf.vsCurrentPrice", { value: formatPct(upside, true, 1) })
          : "—"}
      </Typography>
      {scenario?.intrinsicEquityValue != null && (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
          {t("analysisReport.dcf.impliedEquityValue", { value: formatCompactMarketCap(scenario.intrinsicEquityValue) })}
        </Typography>
      )}
    </Paper>
  );
}

export function DcfValuationPanel({ dcf }: { dcf?: DcfValuation | null }) {
  const { t } = useTranslation();
  const [basis, setBasis] = useState<"STANDARD_FCF" | "SBC_ADJUSTED_FCF">("STANDARD_FCF");

  if (!dcf) {
    return null;
  }

  const topAvailable = dcf.status === "AVAILABLE";
  const activeBranch: DcfBranch | null = basis === "SBC_ADJUSTED_FCF"
    ? (dcf.sbcAdjustedFcf ?? null)
    : (dcf.standardFcf ?? null);
  const reverse = activeBranch?.reverseDcf ?? null;

  const benchmarkSourceLabel = (source?: string | null) => {
    if (!source) return "";
    return t(`analysisReport.dcf.benchmarkSources.${source}`, { defaultValue: source });
  };

  return (
    <Paper component="section" aria-label={t("analysisReport.dcf.title")} variant="outlined" sx={{ p: 2.5, mt: 2 }}>
      <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={1.5}>
        <Box>
          <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
            <Typography variant="h6" fontWeight={700}>{t("analysisReport.dcf.title")}</Typography>
            <Chip size="small" color="secondary" variant="outlined" label={t("analysisReport.dcf.independentBadge")} />
          </Stack>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.4 }}>
            {t("analysisReport.dcf.independentDisclaimer")}
          </Typography>
        </Box>

        {topAvailable && (
          <ToggleButtonGroup
            size="small"
            exclusive
            value={basis}
            onChange={(_, next) => {
              if (next) setBasis(next);
            }}
            aria-label={t("analysisReport.dcf.basisToggleLabel")}
          >
            <ToggleButton value="STANDARD_FCF">{t("analysisReport.dcf.basisStandard")}</ToggleButton>
            <ToggleButton value="SBC_ADJUSTED_FCF">{t("analysisReport.dcf.basisSbcAdjusted")}</ToggleButton>
          </ToggleButtonGroup>
        )}
      </Stack>

      <Divider sx={{ my: 1.5 }} />

      {!topAvailable ? (
        <Alert severity="info">
          {t("analysisReport.dcf.unavailableNotice", {
            status: readableState(t, dcf.status),
            reason: reasonLabel(t, dcf.reasonCode),
          })}
        </Alert>
      ) : (
        <>
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 2 }}>
            <Chip
              size="small"
              variant="outlined"
              label={t("analysisReport.dcf.assumptions.discountRate", { value: formatPct(dcf.discountRate, false, 2) })}
            />
            <Chip
              size="small"
              variant="outlined"
              label={t("analysisReport.dcf.assumptions.riskFree", {
                value: formatPct(dcf.riskFreeRate, false, 2),
                date: dcf.riskFreeRateDate ?? "—",
              })}
            />
            <Chip
              size="small"
              variant="outlined"
              label={t("analysisReport.dcf.assumptions.erp", { value: formatPct(dcf.equityRiskPremium, false, 1) })}
            />
            <Chip
              size="small"
              variant="outlined"
              label={t("analysisReport.dcf.assumptions.terminalGrowth", { value: formatPct(dcf.terminalGrowthRate, false, 1) })}
            />
            {activeBranch?.baseFcfUsd != null && (
              <Chip
                size="small"
                variant="outlined"
                label={t("analysisReport.dcf.assumptions.baseFcf", { value: formatCompactMarketCap(activeBranch.baseFcfUsd) })}
              />
            )}
          </Stack>

          {!activeBranch || activeBranch.status !== "AVAILABLE" ? (
            <Alert severity="info">
              {t("analysisReport.dcf.branchUnavailable", {
                basis: basis === "SBC_ADJUSTED_FCF"
                  ? t("analysisReport.dcf.basisSbcAdjusted")
                  : t("analysisReport.dcf.basisStandard"),
                status: readableState(t, activeBranch?.status),
                reason: reasonLabel(t, activeBranch?.reasonCode),
              })}
            </Alert>
          ) : (
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, lg: 7 }}>
                <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                  {t("analysisReport.dcf.scenariosTitle")}
                </Typography>
                <Grid container spacing={1.5}>
                  <Grid size={{ xs: 12, sm: 4 }}>
                    <ScenarioCard title={t("analysisReport.dcf.conservative")} scenario={activeBranch.conservative} currency={dcf.currency} />
                  </Grid>
                  <Grid size={{ xs: 12, sm: 4 }}>
                    <ScenarioCard title={t("analysisReport.dcf.base")} scenario={activeBranch.base} currency={dcf.currency} highlight />
                  </Grid>
                  <Grid size={{ xs: 12, sm: 4 }}>
                    <ScenarioCard title={t("analysisReport.dcf.optimistic")} scenario={activeBranch.optimistic} currency={dcf.currency} />
                  </Grid>
                </Grid>
              </Grid>

              <Grid size={{ xs: 12, lg: 5 }}>
                <Paper
                  variant="outlined"
                  sx={{
                    p: 2,
                    height: "100%",
                    bgcolor: reverse?.expectationStretched ? "rgba(237, 108, 2, 0.05)" : "action.hover",
                    borderColor: reverse?.expectationStretched ? "warning.main" : undefined,
                  }}
                >
                  <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1} sx={{ mb: 1 }}>
                    <Typography variant="subtitle2" fontWeight={700}>{t("analysisReport.dcf.reverseTitle")}</Typography>
                    {reverse?.expectationStretched && (
                      <Chip size="small" color="warning" label={t("analysisReport.dcf.expectationStretchedBadge")} />
                    )}
                  </Stack>

                  {reverse?.status === "AVAILABLE" ? (
                    <Stack spacing={1}>
                      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                        <Typography variant="body2" color="text.secondary">{t("analysisReport.dcf.impliedCagrLabel")}</Typography>
                        <Typography variant="h6" fontWeight={800} color={reverse.expectationStretched ? "warning.dark" : "primary.main"}>
                          {formatPct(reverse.impliedStage1GrowthRate, true, 2)}
                        </Typography>
                      </Stack>
                      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                        <Typography variant="body2" color="text.secondary">
                          {t("analysisReport.dcf.benchmarkLabel")}
                          {reverse.benchmarkGrowthSource ? `（${benchmarkSourceLabel(reverse.benchmarkGrowthSource)}）` : ""}
                        </Typography>
                        <Typography variant="body2" fontWeight={700}>
                          {formatPct(reverse.benchmarkGrowthRate, true, 2)}
                        </Typography>
                      </Stack>
                      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                        <Typography variant="body2" color="text.secondary">{t("analysisReport.dcf.expectationGapLabel")}</Typography>
                        <Typography
                          variant="body2"
                          fontWeight={700}
                          color={reverse.expectationStretched ? "warning.dark" : "text.primary"}
                        >
                          {formatPpt(reverse.expectationGapPpt)}
                        </Typography>
                      </Stack>
                      <Divider />
                      <Typography variant="caption" color="text.secondary">
                        {reverse.benchmarkGrowthRate == null
                          ? t("analysisReport.dcf.reverseNoBenchmarkHint")
                          : reverse.expectationStretched
                            ? t("analysisReport.dcf.reverseStretchedHint")
                            : t("analysisReport.dcf.reverseAlignedHint")}
                      </Typography>
                    </Stack>
                  ) : (
                    <Typography variant="body2" color="text.secondary">
                      {t("analysisReport.dcf.reverseUnavailable", {
                        status: readableState(t, reverse?.status),
                        reason: reasonLabel(t, reverse?.reasonCode),
                      })}
                    </Typography>
                  )}
                </Paper>
              </Grid>
            </Grid>
          )}
        </>
      )}
    </Paper>
  );
}
