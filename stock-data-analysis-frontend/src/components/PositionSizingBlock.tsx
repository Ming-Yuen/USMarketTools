import React from "react";
import { Alert, Box, Chip, Divider, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { PositionSizing } from "../services/types/dto/analysis";
import { formatDecimal, formatMoney, formatPct } from "./portfolioFormatters";

interface PositionSizingBlockProps {
  sizing?: PositionSizing | null;
  starterMode?: boolean;
}

const RISK_BUDGET_PCT_FORMATTER = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

export const PositionSizingBlock: React.FC<PositionSizingBlockProps> = ({ sizing, starterMode = false }) => {
  const { t } = useTranslation();
  if (!sizing) return null;

  const title = starterMode
    ? t("portfolio.sizing.starterSectionTitle")
    : (sizing.riskPerTradePct != null && Number.isFinite(sizing.riskPerTradePct)
      ? t("portfolio.sizing.sectionTitle", { pct: RISK_BUDGET_PCT_FORMATTER.format(sizing.riskPerTradePct) })
      : t("portfolio.sizing.sectionTitleNoPct"));

  if (sizing.status === "NOT_APPLICABLE") {
    const reasonText = sizing.reasonCode
      ? t(`portfolio.sizing.reasons.${sizing.reasonCode}`, { defaultValue: sizing.reasonCode })
      : "—";
    return (
      <Box sx={{ mt: 1, pt: 1, borderTop: "1px dashed", borderColor: "divider" }} data-testid="position-sizing-block">
        <Typography variant="caption" fontWeight={700} color="text.secondary">{title}</Typography>
        <Typography variant="caption" display="block" color="text.secondary">{reasonText}</Typography>
      </Box>
    );
  }

  const isPaused = sizing.status === "PAUSED";
  const isStaleFallback = sizing.sizingTags.includes("STALE_QUOTE_DATE_FALLBACK");
  const isWaitingTrigger = sizing.sizingTags.includes("WAITING_TRIGGER_ESTIMATE");
  const tagColor = (tag: string): "error" | "warning" | "info" | "default" => {
    if (tag === "SECTOR_CONCENTRATION_WARNING" || tag === "MARKET_REGIME_PAUSE_NEW_ENTRY") return "error";
    if (
      tag === "MACRO_HEADWIND_HALF_SIZE"
      || tag === "EXISTING_HOLDING_CAPPED"
      || tag === "PARTIAL_PORTFOLIO_COVERAGE"
      || tag === "STALE_QUOTE_DATE_FALLBACK"
    ) return "warning";
    if (tag === "TREND_PULLBACK_STARTER_ONE_THIRD") return "info";
    return "default";
  };

  return (
    <Box
      sx={{ mt: 1, pt: 1, borderTop: "1px dashed", borderColor: "divider" }}
      data-testid={starterMode ? "starter-position-sizing-block" : "position-sizing-block"}
    >
      <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={0.5}>
        <Typography variant="caption" fontWeight={700} color="text.primary">{title}</Typography>
        {!isPaused && sizing.scaleLabel && sizing.scaleLabel !== "FULL" && (
          <Chip
            size="small"
            color="warning"
            variant="outlined"
            label={t("portfolio.sizing.scaleBadge", { scale: sizing.scaleLabel })}
            sx={{ height: 20, fontSize: "0.7rem" }}
          />
        )}
      </Stack>

      {isPaused ? (
        <Alert severity="error" sx={{ mt: 0.75, py: 0.25 }} data-testid="position-sizing-paused">
          {t("portfolio.sizing.pausedNotice")}
        </Alert>
      ) : (
        <Stack spacing={0.4} sx={{ mt: 0.5 }}>
          <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap alignItems="baseline">
            <Typography variant="body2">
              {t("portfolio.sizing.suggestedShares")}：
              <strong data-testid="suggested-shares-value">
                {t("portfolio.sizing.sharesUnit", { shares: sizing.suggestedShares ?? 0 })}
              </strong>
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {t("portfolio.sizing.estimatedAmount")}：{formatMoney(sizing.estimatedPositionAmount)}（{t("portfolio.sizing.capitalPct", { pct: formatPct(sizing.positionCapitalPct, 1) })}）
            </Typography>
          </Stack>
          <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
            <Typography variant="caption" color="text.secondary">
              {t("portfolio.sizing.maxLoss")}：{formatMoney(sizing.maxEstimatedLossAmount)}（{t("portfolio.sizing.lossPct", { pct: formatPct(sizing.maxEstimatedLossPct, 2) })}）
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t("portfolio.sizing.entryRef", { price: formatDecimal(sizing.entryReferencePrice) })}
            </Typography>
          </Stack>
          {!sizing.planActionable && isStaleFallback && (
            <Typography variant="caption" color="warning.main">
              {t("portfolio.sizing.staleFallbackNote")}
            </Typography>
          )}
          {!sizing.planActionable && (isWaitingTrigger || !isStaleFallback) && (
            <Typography variant="caption" color="warning.main">
              {t("portfolio.sizing.waitingTriggerNote")}
            </Typography>
          )}
          {sizing.reasonCode && (
            <Typography variant="caption" color="warning.main">
              {t(`portfolio.sizing.reasons.${sizing.reasonCode}`, { defaultValue: sizing.reasonCode })}
            </Typography>
          )}
        </Stack>
      )}

      {sizing.sizingTags.length > 0 && (
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>
          {sizing.sizingTags.map((tag) => (
            <Chip
              key={tag}
              size="small"
              color={tagColor(tag)}
              variant={tag === "SECTOR_CONCENTRATION_WARNING" ? "filled" : "outlined"}
              label={t(`portfolio.sizing.tags.${tag}`, { defaultValue: tag })}
              sx={{ height: 20, fontSize: "0.68rem" }}
            />
          ))}
        </Stack>
      )}

      {sizing.sectorConcentrationWarning && (
        <>
          <Divider sx={{ my: 0.75 }} />
          <Alert severity="warning" sx={{ py: 0.25 }} data-testid="sector-concentration-warning">
            <Typography variant="caption" display="block" fontWeight={700}>
              {t("portfolio.sizing.tags.SECTOR_CONCENTRATION_WARNING")}
            </Typography>
            <Typography variant="caption" display="block">
              {t("portfolio.sizing.sectorExposure", {
                projected: formatPct(sizing.projectedSectorExposurePct, 1),
                current: formatPct(sizing.currentSectorExposurePct, 1),
                max: formatPct(sizing.maxSectorExposurePct, 0),
              })}
            </Typography>
          </Alert>
        </>
      )}
    </Box>
  );
};
