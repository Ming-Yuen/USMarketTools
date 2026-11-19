import React, { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Drawer,
  FormControl,
  IconButton,
  InputLabel,
  LinearProgress,
  MenuItem,
  Select,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from "@mui/material";
import { Close, DeleteOutline, EditOutlined } from "@mui/icons-material";
import {
  deletePortfolioItem,
  dispatchPortfolioUpdated,
  fetchPortfolioOverview,
  updatePortfolioRiskConfig,
  upsertPortfolioItem,
} from "../services/portfolioApi";
import { PortfolioItemType, PortfolioItemView, PortfolioOverview } from "../services/types/dto/portfolio";
import { formatDecimal, formatMoney, formatPct } from "./portfolioFormatters";

function extractApiErrorMessage(err: unknown): string {
  const apiErr = err as { response?: { data?: { message?: string; detail?: string } }; message?: string };
  return apiErr?.response?.data?.message
    ?? apiErr?.response?.data?.detail
    ?? (err instanceof Error ? err.message : String(err));
}

export interface PortfolioDrawerInitialStock {
  figi: string;
  symbol?: string;
  stockName?: string;
  latestClosePrice?: number | null;
  latestQuoteDate?: string | null;
}

interface PortfolioDrawerProps {
  open: boolean;
  onClose: () => void;
  initialStock?: PortfolioDrawerInitialStock | null;
  initialTab?: number;
  onSaved?: () => void;
}

const PortfolioDrawerInner: React.FC<Omit<PortfolioDrawerProps, "open">> = ({
  onClose,
  initialStock = null,
  initialTab,
  onSaved,
}) => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<number>(initialStock ? 1 : (initialTab ?? 0));
  const [overview, setOverview] = useState<PortfolioOverview | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [loadFailed, setLoadFailed] = useState<boolean>(false);
  const [configLoaded, setConfigLoaded] = useState<boolean>(false);
  const [submitting, setSubmitting] = useState<boolean>(false);

  const [figiInput, setFigiInput] = useState(initialStock?.figi ?? "");
  const [lockedFigi, setLockedFigi] = useState<string | null>(initialStock?.figi ?? null);
  const [formOwnerFigi, setFormOwnerFigi] = useState<string>(initialStock?.figi ?? "");
  const [itemType, setItemType] = useState<PortfolioItemType>("WATCHLIST");
  const [sharesInput, setSharesInput] = useState("");
  const [avgCostInput, setAvgCostInput] = useState(
    initialStock?.latestClosePrice != null ? String(initialStock.latestClosePrice) : "",
  );
  const [basisDateInput, setBasisDateInput] = useState(
    initialStock?.latestClosePrice != null && initialStock?.latestQuoteDate ? initialStock.latestQuoteDate : "",
  );
  const [notesInput, setNotesInput] = useState("");
  const [feedback, setFeedback] = useState<{ severity: "success" | "error"; text: string } | null>(null);

  const [capitalInput, setCapitalInput] = useState("");
  const [riskPctInput, setRiskPctInput] = useState("");
  const [maxSingleInput, setMaxSingleInput] = useState("");
  const [maxSectorInput, setMaxSectorInput] = useState("");

  const fillEditForm = useCallback((item: PortfolioItemView, lock = true) => {
    setFigiInput(item.figi);
    setFormOwnerFigi(item.figi);
    setLockedFigi(lock ? item.figi : null);
    setItemType(item.itemType);
    setSharesInput(item.rawShares != null ? String(item.rawShares) : "");
    setAvgCostInput(item.rawAvgCost != null ? String(item.rawAvgCost) : "");
    setBasisDateInput(item.costBasisDate ?? "");
    setNotesInput(item.notes ?? "");
  }, []);

  const handleFigiInputChange = (nextRaw: string) => {
    setFigiInput(nextRaw);
    const trimmed = nextRaw.trim();
    const matched = overview?.items?.find((it) => it.figi === trimmed);
    if (matched) {
      fillEditForm(matched, false);
      return;
    }
    if (trimmed !== formOwnerFigi) {
      setFormOwnerFigi(trimmed);
      setItemType("WATCHLIST");
      setSharesInput("");
      setAvgCostInput("");
      setBasisDateInput("");
      setNotesInput("");
    }
  };

  const reloadOverview = useCallback(async (syncInitialStock = false) => {
    setLoading(true);
    setLoadFailed(false);
    try {
      const data = await fetchPortfolioOverview();
      setOverview(data);
      if (data?.config) {
        setCapitalInput(String(data.config.totalCapital));
        setRiskPctInput(String(Number((data.config.riskPerTradePct * 100).toFixed(2))));
        setMaxSingleInput(String(Number((data.config.maxSinglePositionPct * 100).toFixed(2))));
        setMaxSectorInput(String(Number((data.config.maxSectorExposurePct * 100).toFixed(2))));
        setConfigLoaded(true);
      } else {
        setConfigLoaded(false);
      }
      if (syncInitialStock && initialStock?.figi) {
        const existing = data?.items?.find((it) => it.figi === initialStock.figi);
        if (existing) {
          fillEditForm(existing);
        }
      }
    } catch {
      setOverview(null);
      setConfigLoaded(false);
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, [initialStock?.figi, fillEditForm]);

  useEffect(() => {
    reloadOverview(true);
  }, [reloadOverview]);

  const resetEditFormForDeleted = (deletedFigi: string) => {
    if (deletedFigi !== formOwnerFigi) {
      return;
    }
    if (initialStock?.figi && deletedFigi === initialStock.figi) {
      setFigiInput(initialStock.figi);
      setFormOwnerFigi(initialStock.figi);
      setLockedFigi(initialStock.figi);
      setItemType("WATCHLIST");
      setSharesInput("");
      setAvgCostInput(initialStock.latestClosePrice != null ? String(initialStock.latestClosePrice) : "");
      setBasisDateInput(
        initialStock.latestClosePrice != null && initialStock.latestQuoteDate ? initialStock.latestQuoteDate : "",
      );
      setNotesInput("");
      return;
    }
    setFigiInput("");
    setFormOwnerFigi("");
    setLockedFigi(null);
    setItemType("WATCHLIST");
    setSharesInput("");
    setAvgCostInput("");
    setBasisDateInput("");
    setNotesInput("");
  };

  const notifySaved = () => {
    queryClient?.invalidateQueries?.({ queryKey: ["portfolio-overview"] });
    queryClient?.invalidateQueries?.({ queryKey: ["portfolio-health"] });
    queryClient?.invalidateQueries?.({ queryKey: ["analysis-report"] });
    dispatchPortfolioUpdated();
    onSaved?.();
  };

  const handleSaveItem = async (e: React.FormEvent) => {
    e.preventDefault();
    const targetFigi = figiInput.trim();
    if (!configLoaded || !targetFigi || targetFigi !== formOwnerFigi) {
      return;
    }
    setSubmitting(true);
    setFeedback(null);
    try {
      await upsertPortfolioItem(figiInput.trim(), {
        itemType,
        shares: itemType === "HOLDING" ? Number(sharesInput) : null,
        avgCost: itemType === "HOLDING" ? Number(avgCostInput) : null,
        costBasisDate: itemType === "HOLDING" && basisDateInput.trim() ? basisDateInput.trim() : null,
        notes: notesInput.trim() || null,
      });
      await reloadOverview(false);
      notifySaved();
      setFeedback({ severity: "success", text: t("portfolio.form.saved") });
      setLockedFigi(initialStock?.figi ?? null);
      setTab(0);
    } catch (err: unknown) {
      setFeedback({ severity: "error", text: t("portfolio.form.saveFailed", { message: extractApiErrorMessage(err) }) });
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeleteItem = async (figi: string) => {
    setSubmitting(true);
    setFeedback(null);
    try {
      await deletePortfolioItem(figi);
      resetEditFormForDeleted(figi);
      await reloadOverview(false);
      notifySaved();
      setFeedback({ severity: "success", text: t("portfolio.form.deleted") });
    } catch (err: unknown) {
      setFeedback({ severity: "error", text: t("portfolio.form.deleteFailed", { message: extractApiErrorMessage(err) }) });
    } finally {
      setSubmitting(false);
    }
  };

  const handleSaveConfig = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!configLoaded) {
      return;
    }
    setSubmitting(true);
    setFeedback(null);
    try {
      await updatePortfolioRiskConfig({
        totalCapital: Number(capitalInput),
        riskPerTradePct: Number(riskPctInput) / 100,
        maxSinglePositionPct: Number(maxSingleInput) / 100,
        maxSectorExposurePct: Number(maxSectorInput) / 100,
      });
      await reloadOverview(false);
      notifySaved();
      setFeedback({ severity: "success", text: t("portfolio.config.saved") });
    } catch (err: unknown) {
      setFeedback({ severity: "error", text: t("portfolio.config.saveFailed", { message: extractApiErrorMessage(err) }) });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Box sx={{ p: 2.5, display: "flex", flexDirection: "column", height: "100%" }} data-testid="portfolio-drawer">
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h6" fontWeight={800}>{t("portfolio.title")}</Typography>
        <IconButton onClick={onClose} size="small" aria-label={t("portfolio.closeDrawer")}>
          <Close />
        </IconButton>
      </Stack>

      <Tabs value={tab} onChange={(_, v) => { setTab(v); setFeedback(null); }} sx={{ borderBottom: 1, borderColor: "divider", mb: 2 }}>
        <Tab label={t("portfolio.tabs.list")} />
        <Tab label={t("portfolio.tabs.edit")} />
        <Tab label={t("portfolio.tabs.riskConfig")} />
      </Tabs>

      {feedback && (
        <Alert severity={feedback.severity} sx={{ mb: 1.5 }} onClose={() => setFeedback(null)}>
          {feedback.text}
        </Alert>
      )}

      <Box sx={{ flexGrow: 1, overflowY: "auto" }}>
        {tab === 0 && (
          <Stack spacing={2}>
            {loading ? (
              <Stack direction="row" spacing={1} alignItems="center"><CircularProgress size={20} /></Stack>
            ) : overview ? (
              <>
                <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
                  <Chip label={`${t("portfolio.summary.holdings")}: ${overview.holdingCount}`} color="primary" variant="outlined" />
                  <Chip label={`${t("portfolio.summary.watchlist")}: ${overview.watchlistCount}`} variant="outlined" />
                  <Chip label={`${t("portfolio.summary.totalMarketValue")}: ${formatMoney(overview.totalHoldingMarketValueUsd)}`} />
                  <Chip
                    color={overview.totalUnrealizedPnlUsd >= 0 ? "success" : "error"}
                    label={`${t("portfolio.summary.totalPnl")}: ${formatMoney(overview.totalUnrealizedPnlUsd)} (${formatPct(overview.totalUnrealizedPnlPct, 1, true)})`}
                  />
                </Stack>

                {overview.excludedHoldingsCount > 0 && (
                  <Alert severity="info">
                    {t("portfolio.summary.excludedNotice", { count: overview.excludedHoldingsCount })}
                  </Alert>
                )}

                {overview.sectorExposures.length > 0 && (
                  <Box sx={{ p: 1.5, border: 1, borderColor: "divider", borderRadius: 1 }}>
                    <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                      {t("portfolio.summary.sectorExposureTitle")}
                    </Typography>
                    <Stack spacing={1}>
                      {overview.sectorExposures.map((sec) => (
                        <Box key={sec.industryCode}>
                          <Stack direction="row" justifyContent="space-between" alignItems="center">
                            <Typography variant="caption" fontWeight={600}>
                              {sec.industryName ?? sec.industryCode} ({sec.holdingCount})
                            </Typography>
                            <Stack direction="row" spacing={0.75} alignItems="center">
                              <Typography variant="caption" fontWeight={700} color={sec.concentrationExceeded ? "error.main" : "text.primary"}>
                                {formatPct(sec.exposurePct, 1)}
                              </Typography>
                              {sec.concentrationExceeded && (
                                <Chip
                                  size="small"
                                  color="error"
                                  label={t("portfolio.summary.sectorExceeded", { max: Math.round(sec.maxSectorExposurePct * 100) })}
                                  sx={{ height: 18, fontSize: "0.66rem" }}
                                />
                              )}
                            </Stack>
                          </Stack>
                          <LinearProgress
                            variant="determinate"
                            value={Math.min(100, sec.exposurePct * 100)}
                            color={sec.concentrationExceeded ? "error" : "primary"}
                            sx={{ height: 6, borderRadius: 1, mt: 0.4 }}
                          />
                        </Box>
                      ))}
                    </Stack>
                  </Box>
                )}

                {overview.items.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">{t("portfolio.summary.noHoldings")}</Typography>
                ) : (
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>{t("portfolio.table.symbol")}</TableCell>
                        <TableCell>{t("portfolio.table.type")}</TableCell>
                        <TableCell align="right">{t("portfolio.table.shares")}</TableCell>
                        <TableCell align="right">{t("portfolio.table.avgCost")}</TableCell>
                        <TableCell align="right">{t("portfolio.table.pnl")}</TableCell>
                        <TableCell align="right">{t("portfolio.table.actions")}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {overview.items.map((item) => (
                        <TableRow key={item.figi} hover>
                          <TableCell>
                            <Button
                              size="small"
                              sx={{ p: 0, minWidth: 0, fontWeight: 700, textTransform: "none" }}
                              onClick={() => {
                                onClose();
                                navigate(`/stockAnalysis/${encodeURIComponent(item.figi)}`);
                              }}
                            >
                              {item.symbol}
                            </Button>
                            {item.splitAdjusted && (
                              <Chip
                                size="small"
                                label={t("portfolio.table.splitAdjustedTag")}
                                sx={{ ml: 0.5, height: 18, fontSize: "0.65rem" }}
                              />
                            )}
                          </TableCell>
                          <TableCell>
                            <Chip
                              size="small"
                              color={item.itemType === "HOLDING" ? "primary" : "default"}
                              variant="outlined"
                              label={t(`portfolio.types.${item.itemType}`)}
                            />
                          </TableCell>
                          <TableCell align="right">{formatDecimal(item.adjustedShares ?? item.rawShares, 2)}</TableCell>
                          <TableCell align="right">{formatDecimal(item.adjustedAvgCost ?? item.rawAvgCost, 2)}</TableCell>
                          <TableCell align="right">
                            <Typography
                              variant="caption"
                              fontWeight={700}
                              color={(item.unrealizedPnlPct ?? 0) >= 0 ? "success.main" : "error.main"}
                            >
                              {formatPct(item.unrealizedPnlPct, 1, true)}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">
                            <IconButton
                              size="small"
                              aria-label={t("portfolio.table.edit")}
                              onClick={() => {
                                fillEditForm(item);
                                setTab(1);
                              }}
                            >
                              <EditOutlined fontSize="small" />
                            </IconButton>
                            <IconButton
                              size="small"
                              color="error"
                              aria-label={t("portfolio.table.delete")}
                              onClick={() => handleDeleteItem(item.figi)}
                            >
                              <DeleteOutline fontSize="small" />
                            </IconButton>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </>
            ) : null}
          </Stack>
        )}

        {tab === 1 && (
          <Stack component="form" spacing={2} onSubmit={handleSaveItem}>
            {loadFailed && (
              <Alert severity="error" data-testid="portfolio-item-load-error">
                {t("portfolio.form.loadFailed")}
              </Alert>
            )}
            <TextField
              label={t("portfolio.form.figi")}
              value={figiInput}
              onChange={(e) => handleFigiInputChange(e.target.value)}
              disabled={!configLoaded || Boolean(lockedFigi)}
              required
              size="small"
              fullWidth
              data-testid="portfolio-item-figi-input"
            />
            <FormControl size="small" fullWidth disabled={!configLoaded}>
              <InputLabel id="portfolio-item-type-label">{t("portfolio.form.itemType")}</InputLabel>
              <Select
                labelId="portfolio-item-type-label"
                value={itemType}
                label={t("portfolio.form.itemType")}
                onChange={(e) => setItemType(e.target.value as PortfolioItemType)}
              >
                <MenuItem value="HOLDING">{t("portfolio.types.HOLDING")}</MenuItem>
                <MenuItem value="WATCHLIST">{t("portfolio.types.WATCHLIST")}</MenuItem>
              </Select>
            </FormControl>

            {itemType === "HOLDING" && (
              <>
                <TextField
                  label={t("portfolio.form.shares")}
                  type="number"
                  inputProps={{ step: "any", min: "0.000001" }}
                  value={sharesInput}
                  onChange={(e) => setSharesInput(e.target.value)}
                  disabled={!configLoaded}
                  required
                  size="small"
                  fullWidth
                />
                <TextField
                  label={t("portfolio.form.avgCost")}
                  type="number"
                  inputProps={{ step: "any", min: "0.000001" }}
                  value={avgCostInput}
                  onChange={(e) => setAvgCostInput(e.target.value)}
                  disabled={!configLoaded}
                  required
                  size="small"
                  fullWidth
                />
                <TextField
                  label={t("portfolio.form.costBasisDate")}
                  type="date"
                  InputLabelProps={{ shrink: true }}
                  helperText={t("portfolio.form.costBasisDateHelper")}
                  value={basisDateInput}
                  onChange={(e) => setBasisDateInput(e.target.value)}
                  disabled={!configLoaded}
                  size="small"
                  fullWidth
                />
              </>
            )}

            <TextField
              label={t("portfolio.form.notes")}
              value={notesInput}
              onChange={(e) => setNotesInput(e.target.value)}
              disabled={!configLoaded}
              multiline
              minRows={2}
              size="small"
              fullWidth
            />
            <Button type="submit" variant="contained" disabled={submitting || !configLoaded} data-testid="portfolio-item-save-btn">
              {submitting ? t("portfolio.form.saving") : t("portfolio.form.save")}
            </Button>
          </Stack>
        )}

        {tab === 2 && (
          <Stack component="form" spacing={2} onSubmit={handleSaveConfig}>
            <Typography variant="subtitle2" fontWeight={700}>{t("portfolio.config.title")}</Typography>
            <Divider />
            {loadFailed && (
              <Alert severity="error" data-testid="portfolio-config-load-error">
                {t("portfolio.config.loadFailed")}
              </Alert>
            )}
            <TextField
              label={t("portfolio.config.totalCapital")}
              type="number"
              inputProps={{ step: "any", min: "1" }}
              value={capitalInput}
              onChange={(e) => setCapitalInput(e.target.value)}
              disabled={!configLoaded}
              required
              size="small"
              fullWidth
            />
            <TextField
              label={t("portfolio.config.riskPerTradePct")}
              type="number"
              inputProps={{ step: "0.01", min: "0.01", max: "25" }}
              value={riskPctInput}
              onChange={(e) => setRiskPctInput(e.target.value)}
              disabled={!configLoaded}
              required
              size="small"
              fullWidth
            />
            <TextField
              label={t("portfolio.config.maxSinglePositionPct")}
              type="number"
              inputProps={{ step: "0.01", min: "0.01", max: "100" }}
              value={maxSingleInput}
              onChange={(e) => setMaxSingleInput(e.target.value)}
              disabled={!configLoaded}
              required
              size="small"
              fullWidth
            />
            <TextField
              label={t("portfolio.config.maxSectorExposurePct")}
              type="number"
              inputProps={{ step: "0.01", min: "0.01", max: "100" }}
              value={maxSectorInput}
              onChange={(e) => setMaxSectorInput(e.target.value)}
              disabled={!configLoaded}
              required
              size="small"
              fullWidth
            />
            <Button type="submit" variant="contained" disabled={submitting || !configLoaded} data-testid="portfolio-config-save-btn">
              {t("portfolio.config.save")}
            </Button>
          </Stack>
        )}
      </Box>
    </Box>
  );
};

export const PortfolioDrawer: React.FC<PortfolioDrawerProps> = ({ open, onClose, initialStock, initialTab, onSaved }) => {
  if (!open) return null;
  return (
    <Drawer anchor="right" open={open} onClose={onClose} PaperProps={{ sx: { width: { xs: "100%", sm: 580 } } }}>
      <PortfolioDrawerInner onClose={onClose} initialStock={initialStock} initialTab={initialTab} onSaved={onSaved} />
    </Drawer>
  );
};
