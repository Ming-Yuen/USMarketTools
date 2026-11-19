import React, { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  Typography,
} from "@mui/material";
import { AccountBalanceWalletOutlined, HealthAndSafetyOutlined, Refresh } from "@mui/icons-material";
import { fetchPortfolioHealth } from "../services/portfolioApi";
import { PortfolioHealthSummary } from "../services/types/dto/portfolio";
import { formatAlertMessage, formatDecimal, formatPct, severityColor } from "./portfolioFormatters";
import { PortfolioDrawer } from "./PortfolioDrawer";

export const PortfolioHealthCard: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [health, setHealth] = useState<PortfolioHealthSummary | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [drawerOpen, setDrawerOpen] = useState<boolean>(false);

  const mountedRef = React.useRef(true);
  const reqSeqRef = React.useRef(0);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const loadHealth = useCallback(async (live: boolean) => {
    const seq = ++reqSeqRef.current;
    setLoading(true);
    try {
      const data = await fetchPortfolioHealth(live);
      if (mountedRef.current && reqSeqRef.current === seq) {
        setHealth(data);
      }
    } catch {
      if (mountedRef.current && reqSeqRef.current === seq) {
        setHealth(null);
      }
    } finally {
      if (mountedRef.current && reqSeqRef.current === seq) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    loadHealth(false);
  }, [loadHealth]);

  const holdingAlerts = (health?.holdings ?? []).filter((h) => h.alerts.length > 0 || h.dataGapReasons.length > 0);
  const watchlistEntries = health?.watchlistOpportunities ?? [];
  const sectorAlerts = health?.sectorAlerts ?? [];
  const hasAnyItems = (health?.holdingCount ?? 0) + (health?.watchlistCount ?? 0) > 0;

  return (
    <>
      <Paper
        elevation={0}
        sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
        data-testid="portfolio-health-card"
      >
        <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={1}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <HealthAndSafetyOutlined color={health && health.criticalCount > 0 ? "error" : "primary"} />
            <Typography variant="subtitle1" fontWeight={800}>{t("portfolio.health.cardTitle")}</Typography>
            {health?.tradeDate && (
              <Typography variant="caption" color="text.secondary">
                {t("portfolio.health.tradeDate", { date: health.tradeDate })}
              </Typography>
            )}
            {health && (
              <Chip
                size="small"
                variant="outlined"
                color={health.sourceMode === "LIVE" ? "info" : "default"}
                label={health.sourceMode === "LIVE" ? t("portfolio.health.sourceLive") : t("portfolio.health.sourceSnapshot")}
              />
            )}
            {health && hasAnyItems && (
              <>
                <Chip size="small" color="error" variant="outlined" label={t("portfolio.health.criticalCount", { count: health.criticalCount })} />
                <Chip size="small" color="warning" variant="outlined" label={t("portfolio.health.warningCount", { count: health.warningCount })} />
                <Chip size="small" color="info" variant="outlined" label={t("portfolio.health.infoCount", { count: health.infoCount })} />
              </>
            )}
          </Stack>

          <Stack direction="row" spacing={1}>
            <Button
              size="small"
              variant="outlined"
              startIcon={loading ? <CircularProgress size={14} /> : <Refresh />}
              disabled={loading}
              onClick={() => loadHealth(true)}
              data-testid="portfolio-health-recalc-btn"
            >
              {t("portfolio.health.recalculateLive")}
            </Button>
            <Button
              size="small"
              variant="contained"
              startIcon={<AccountBalanceWalletOutlined />}
              onClick={() => setDrawerOpen(true)}
              data-testid="portfolio-health-manage-btn"
            >
              {t("portfolio.openDrawer")}
            </Button>
          </Stack>
        </Stack>

        {!health ? null : !hasAnyItems ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
            {t("portfolio.health.noItems")}
          </Typography>
        ) : (
          <Stack spacing={1.25} sx={{ mt: 1.5 }}>
            {!health.completeCoverage && (
              <Alert severity="warning" sx={{ py: 0.4 }} data-testid="portfolio-coverage-warning">
                {t("portfolio.health.coverageWarning", { count: health.coverageWarnings.length || health.excludedHoldingsCount })}
              </Alert>
            )}

            {health.structureHealthy && watchlistEntries.length === 0 && (
              <Alert severity="success" sx={{ py: 0.4 }} data-testid="portfolio-structure-healthy">
                {t("portfolio.health.structureHealthy")}
              </Alert>
            )}

            {sectorAlerts.length > 0 && (
              <Box>
                <Typography variant="caption" fontWeight={700} color="warning.main">
                  {t("portfolio.health.sectorAlertsTitle")}
                </Typography>
                <Stack spacing={0.5} sx={{ mt: 0.5 }}>
                  {sectorAlerts.map((sa, idx) => (
                    <Alert key={idx} severity="warning" sx={{ py: 0.25 }}>
                      {formatAlertMessage(t, sa.alertCode, sa.symbol, sa.payload)}
                    </Alert>
                  ))}
                </Stack>
              </Box>
            )}

            {holdingAlerts.length > 0 && (
              <Box>
                <Typography variant="caption" fontWeight={700} color="text.secondary">
                  {t("portfolio.health.holdingsAlertsTitle")}
                </Typography>
                <Stack spacing={0.75} sx={{ mt: 0.5 }}>
                  {holdingAlerts.map((h) => (
                    <Box
                      key={h.figi}
                      sx={{ p: 1.1, border: 1, borderColor: "divider", borderRadius: 1 }}
                      data-testid={`holding-health-row-${h.symbol}`}
                    >
                      <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={0.75}>
                        <Stack direction="row" spacing={0.75} alignItems="center">
                          <Button
                            size="small"
                            sx={{ p: 0, minWidth: 0, fontWeight: 800, textTransform: "none" }}
                            onClick={() => navigate(`/stockAnalysis/${encodeURIComponent(h.figi)}`)}
                          >
                            {h.symbol}
                          </Button>
                          <Typography variant="caption" color="text.secondary">{h.stockName ?? ""}</Typography>
                          {h.highestSeverity !== "NONE" && (
                            <Chip
                              size="small"
                              color={severityColor(h.highestSeverity)}
                              label={t(`notifications.filters.${h.highestSeverity}`, { defaultValue: h.highestSeverity })}
                              sx={{ height: 18, fontSize: "0.66rem" }}
                            />
                          )}
                        </Stack>
                        <Stack direction="row" spacing={1.25}>
                          <Typography variant="caption" color="text.secondary">
                            {t("portfolio.table.close")}: <strong>{formatDecimal(h.closePrice)}</strong>
                          </Typography>
                          {h.unrealizedPnlPct != null && (
                            <Typography
                              variant="caption"
                              fontWeight={700}
                              color={h.unrealizedPnlPct >= 0 ? "success.main" : "error.main"}
                            >
                              {formatPct(h.unrealizedPnlPct, 1, true)}
                            </Typography>
                          )}
                        </Stack>
                      </Stack>
                      <Stack spacing={0.35} sx={{ mt: 0.6 }}>
                        {h.alerts.map((al, idx) => (
                          <Typography key={idx} variant="body2" sx={{ fontSize: 13 }}>
                            · {formatAlertMessage(t, al.alertCode, h.symbol, al.payload)}
                          </Typography>
                        ))}
                        {h.alerts.length === 0 && h.dataGapReasons.length > 0 && (
                          <Typography variant="caption" color="warning.main">
                            · {t("portfolio.health.dataGapNote", { count: h.dataGapReasons.length })}
                          </Typography>
                        )}
                      </Stack>
                    </Box>
                  ))}
                </Stack>
              </Box>
            )}

            {watchlistEntries.length > 0 && (
              <>
                <Divider />
                <Box>
                  <Typography variant="caption" fontWeight={700} color="success.main">
                    {t("portfolio.health.watchlistEntriesTitle")}
                  </Typography>
                  <Stack spacing={0.6} sx={{ mt: 0.5 }}>
                    {watchlistEntries.map((w) => (
                      <Box
                        key={w.figi}
                        sx={{
                          p: 1,
                          border: 1,
                          borderColor: "success.light",
                          borderRadius: 1,
                          display: "flex",
                          justifyContent: "space-between",
                          alignItems: "center",
                          flexWrap: "wrap",
                          gap: 1,
                        }}
                        data-testid={`watchlist-opportunity-${w.symbol}`}
                      >
                        <Stack direction="row" spacing={1} alignItems="center">
                          <Button
                            size="small"
                            sx={{ p: 0, minWidth: 0, fontWeight: 800, textTransform: "none" }}
                            onClick={() => navigate(`/stockAnalysis/${encodeURIComponent(w.figi)}`)}
                          >
                            {w.symbol}
                          </Button>
                          <Typography variant="body2" sx={{ fontSize: 13 }}>
                            {formatAlertMessage(t, w.triggerType, w.symbol, {
                              closePrice: w.closePrice,
                              protectiveStop: w.protectiveStop,
                              suggestedShares: w.positionSizing?.suggestedShares,
                            })}
                          </Typography>
                        </Stack>
                        {w.positionSizing?.suggestedShares != null && (
                          <Chip
                            size="small"
                            color="success"
                            label={t("portfolio.sizing.sharesUnit", { shares: w.positionSizing.suggestedShares })}
                          />
                        )}
                      </Box>
                    ))}
                  </Stack>
                </Box>
              </>
            )}
          </Stack>
        )}
      </Paper>

      <PortfolioDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => loadHealth(true)}
      />
    </>
  );
};
