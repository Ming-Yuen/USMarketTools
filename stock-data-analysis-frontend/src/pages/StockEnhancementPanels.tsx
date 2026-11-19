import React, { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Divider,
  Grid,
  Link,
  Paper,
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
import { ContentCopy, Refresh, AutoAwesome, CheckCircleOutline, DeleteOutline } from "@mui/icons-material";
import { enhancementApi, EnhancementReportContext } from "../services/enhancementApi";
import {
  CustomTagCandidate,
  CustomTagConfirmItemRequest,
  CustomTagProposalItem,
  CustomTagProposalPreview,
  ManualPrompt,
  StockEnhancementOverview,
} from "../services/types/dto/enhancement";
import { formatHongKongDateTime } from "../utils/hongKongTime";

interface Props {
  figi: string;
  asOfDate?: string | null;
  reportContext?: EnhancementReportContext;
  onValuationRebuilt?: () => Promise<unknown>;
}

const fmtPct = (v?: number | null, digits = 2, signed = false) => {
  if (v == null || !Number.isFinite(v)) return "—";
  const pct = v * 100;
  return `${signed && pct > 0 ? "+" : ""}${pct.toFixed(digits)}%`;
};

const fmtNum = (v?: number | null, digits = 2) => {
  if (v == null || !Number.isFinite(v)) return "—";
  return v.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: 0 });
};

const fmtCompactUsd = (v?: number | null) => {
  if (v == null || !Number.isFinite(v)) return "—";
  if (Math.abs(v) >= 1e9) return `$${(v / 1e9).toFixed(2)}B`;
  if (Math.abs(v) >= 1e6) return `$${(v / 1e6).toFixed(2)}M`;
  if (Math.abs(v) >= 1e3) return `$${(v / 1e3).toFixed(1)}K`;
  return `$${v.toFixed(0)}`;
};

const errMsg = (
  err: unknown,
  t: (key: string, opts?: Record<string, unknown>) => string,
  fallback: string,
) => {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  const raw = e?.response?.data?.message || e?.message || "";
  if (raw && /^[A-Z][A-Z0-9_]+$/.test(raw)) {
    const translated = t(`enhancements.errors.${raw}`, { defaultValue: "" });
    if (translated) return translated;
  }
  return raw || fallback;
};

const safeUrl = (url?: string | null) => {
  if (!url) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "http:" || parsed.protocol === "https:";
  } catch {
    return false;
  }
};

export function StockEnhancementPanels({ figi, asOfDate, reportContext, onValuationRebuilt }: Props) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState(0);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // News manual prompt / import state
  const [newsPrompt, setNewsPrompt] = useState<ManualPrompt | null>(null);
  const [newsImportJson, setNewsImportJson] = useState("");

  // Custom tags state (pure client preview until explicit user confirm!)
  const [candidates, setCandidates] = useState<CustomTagCandidate[] | null>(null);
  const [tagPrompt, setTagPrompt] = useState<ManualPrompt | null>(null);
  const [tagImportJson, setTagImportJson] = useState("");
  const [tagPreview, setTagPreview] = useState<CustomTagProposalPreview | null>(null);
  const [selectedTags, setSelectedTags] = useState<Record<string, boolean>>({});

  const queryKey = [
    "stock-enhancements",
    figi,
    asOfDate ?? "",
    reportContext?.protectiveStopPrice ?? null,
    reportContext?.closePrice ?? null,
    reportContext?.verdict ?? "",
    reportContext?.fundamentalBacking ?? "",
    reportContext?.resistanceBreakout ?? false,
  ];
  const invalidateEnhancementsForFigi = () =>
    queryClient.invalidateQueries({ queryKey: ["stock-enhancements", figi] }, { throwOnError: true });
  const syncAfterRefresh = async () => {
    const tasks: Promise<unknown>[] = [invalidateEnhancementsForFigi()];
    if (onValuationRebuilt) {
      tasks.push(onValuationRebuilt());
    }
    const results = await Promise.allSettled(tasks);
    const rejected = results.find((r): r is PromiseRejectedResult => r.status === "rejected");
    if (rejected) {
      throw rejected.reason;
    }
  };
  const overviewQuery = useQuery<StockEnhancementOverview>({
    queryKey,
    enabled: Boolean(figi),
    queryFn: () => enhancementApi.getOverview(figi, asOfDate || undefined, reportContext),
  });
  const data = overviewQuery.data;

  const runAction = async (key: string, fn: () => Promise<void>) => {
    setBusy(key);
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setError(errMsg(e, t, t("enhancements.common.operationFailed")));
    } finally {
      setBusy(null);
    }
  };

  const copyToClipboard = async (text: string) => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
        setNotice(t("enhancements.common.copiedPrompt"));
      }
    } catch {
      setNotice(t("enhancements.common.promptReadyCopyManually"));
    }
  };

  const proposalKey = (item: CustomTagProposalItem) => `${item.taxonomy}|${item.customTag}|${item.unit}`;

  const loadTagPreview = (preview: CustomTagProposalPreview) => {
    setTagPreview(preview);
    const initial: Record<string, boolean> = {};
    for (const item of preview.proposals) {
      initial[proposalKey(item)] = true;
    }
    setSelectedTags(initial);
  };

  const handleConfirmSelectedTags = () =>
    runAction("CONFIRM_TAGS", async () => {
      if (!tagPreview) return;
      const chosen: CustomTagConfirmItemRequest[] = tagPreview.proposals
        .filter((item) => selectedTags[proposalKey(item)])
        .map((item) => ({
          sourceType: item.sourceType,
          taxonomy: item.taxonomy,
          customTag: item.customTag,
          unit: item.unit,
          targetConcept: item.targetConcept,
          signMultiplier: item.signMultiplier,
          rationale: item.rationale,
        }));
      if (chosen.length === 0) {
        setError(t("enhancements.customTags.selectAtLeastOne"));
        return;
      }
      const res = await enhancementApi.confirmCustomTags(figi, tagPreview.provider, chosen);
      setTagPreview(null);
      setTagImportJson("");
      await syncAfterRefresh();
      setNotice(
        t("enhancements.customTags.confirmSuccess", {
          mappings: res.confirmedMappingCount,
          facts: res.backfilledFactCount,
          stocks: res.rebuiltStockCount,
        }),
      );
    });

  if (!figi) return null;

  return (
    <Paper component="section" aria-label={t("enhancements.title")} variant="outlined" sx={{ p: 2.5, mt: 2 }}>
      <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={1}>
        <Box>
          <Typography variant="h6" fontWeight={700}>{t("enhancements.title")}</Typography>
          <Typography variant="caption" color="text.secondary">{t("enhancements.subtitle")}</Typography>
        </Box>
        {data?.aiChannel && (
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
            <Chip
              size="small"
              color={data.aiChannel.automatedAvailable ? "primary" : "default"}
              variant="outlined"
              label={t("enhancements.aiChannel.badge", {
                provider: t(`enhancements.aiChannel.providers.${data.aiChannel.provider}`, {
                  defaultValue: data.aiChannel.provider,
                }),
              })}
            />
            <Chip size="small" color="success" variant="outlined" label={t("enhancements.aiChannel.manualAlwaysReady")} />
          </Stack>
        )}
      </Stack>

      {overviewQuery.isLoading && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ my: 2 }}>
          <CircularProgress size={20} />
          <Typography variant="body2">{t("enhancements.common.loading")}</Typography>
        </Stack>
      )}
      {error && <Alert severity="error" sx={{ mt: 1.5 }}>{error}</Alert>}
      {notice && <Alert severity="success" sx={{ mt: 1.5 }}>{notice}</Alert>}

      {data && (
        <>
          <Tabs
            value={tab}
            onChange={(_, next) => setTab(next)}
            variant="scrollable"
            scrollButtons="auto"
            sx={{ borderBottom: 1, borderColor: "divider", mt: 1.5, mb: 2 }}
          >
            <Tab label={t("enhancements.tabs.options")} />
            <Tab label={t("enhancements.tabs.insiders13f")} />
            <Tab label={t("enhancements.tabs.shortInterest")} />
            <Tab label={t("enhancements.tabs.newsGuidance")} />
            {!data.etf && <Tab label={t("enhancements.tabs.customTags")} />}
          </Tabs>

          {/* Tab 0: Lightweight Options */}
          {tab === 0 && (
            <Box data-testid="enh-tab-options">
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
                <Typography variant="subtitle1" fontWeight={700}>{t("enhancements.options.title")}</Typography>
                <Button
                  size="small"
                  variant="outlined"
                  startIcon={<Refresh />}
                  disabled={busy !== null}
                  onClick={() =>
                    runAction("REFRESH_OPTIONS", async () => {
                      await enhancementApi.refreshOptions(figi, {
                        contextDate: asOfDate || undefined,
                        protectiveStopPrice: reportContext?.protectiveStopPrice,
                        closePrice: reportContext?.closePrice,
                      });
                      await syncAfterRefresh();
                      setNotice(t("enhancements.options.refreshed"));
                    })
                  }
                >
                  {t("enhancements.options.refreshBtn")}
                </Button>
              </Stack>

              <Alert severity="info" sx={{ mb: 1.5 }} data-testid="oi-nondirectional-disclaimer">
                {t("enhancements.options.oiDisclaimer")}
              </Alert>

              {data.options?.earningsGapExceedsStopRisk && (
                <Alert severity="warning" sx={{ mb: 1.5 }}>
                  {t("enhancements.options.earningsGapWarning", {
                    impliedMove: fmtPct(data.options.impliedMovePct, 1),
                    stopDist: fmtPct(data.options.protectiveStopDistancePct, 1),
                    days: data.options.daysToNextEarnings ?? "—",
                  })}
                </Alert>
              )}

              {data.options?.status !== "AVAILABLE" ? (
                <Alert severity="info">
                  {t("enhancements.options.unavailable", {
                    status: t(`enhancements.status.${data.options?.status ?? "INSUFFICIENT_DATA"}`, {
                      defaultValue: data.options?.status ?? "INSUFFICIENT_DATA",
                    }),
                    reason: t(`enhancements.reasons.${data.options?.reasonCode ?? "OPTIONS_NOT_SYNCED"}`, {
                      defaultValue: data.options?.reasonCode ?? "OPTIONS_NOT_SYNCED",
                    }),
                  })}
                </Alert>
              ) : (
                <Grid container spacing={1.5}>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.options.impliedMoveLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>
                        {data.options.impliedMovePct != null ? `±${fmtPct(data.options.impliedMovePct, 2)}` : "—"}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {data.options.impliedMoveReason
                          ? t(`enhancements.reasons.${data.options.impliedMoveReason}`, { defaultValue: data.options.impliedMoveReason })
                          : "—"}
                        {data.options.straddleExpiration ? ` · ${data.options.straddleExpiration}` : ""}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.options.ivHvLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>
                        {fmtPct(data.options.atmIv, 1)} / {fmtPct(data.options.hv20, 1)}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {t("enhancements.options.ivToHvRatio", { value: fmtNum(data.options.ivToHvRatio, 2) })}
                        {data.options.ivHistorySampleCount ? ` · n=${data.options.ivHistorySampleCount}` : ""}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.options.skewAndPcLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>
                        {fmtPct(data.options.ivSkewPutMinusCall, 2, true)}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {t("enhancements.options.putCallOi", { value: fmtNum(data.options.putCallOiRatio, 2) })} ·{" "}
                        {t("enhancements.options.putCallVol", { value: fmtNum(data.options.putCallVolRatio, 2) })}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.options.oiWallsLabel")}</Typography>
                      <Typography variant="body2" fontWeight={700}>
                        {t("enhancements.options.maxCallOiWall", {
                          strike: fmtNum(data.options.maxCallOiStrike, 2),
                          oi: fmtNum(data.options.maxCallOi, 0),
                        })}
                      </Typography>
                      <Typography variant="body2" fontWeight={700}>
                        {t("enhancements.options.maxPutOiWall", {
                          strike: fmtNum(data.options.maxPutOiStrike, 2),
                          oi: fmtNum(data.options.maxPutOi, 0),
                        })}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {data.options.oiWallExpiration ?? "—"} · {data.options.feed ?? ""}
                      </Typography>
                    </Paper>
                  </Grid>
                </Grid>
              )}
            </Box>
          )}

          {/* Tab 1: Form 4 & 13F */}
          {tab === 1 && (
            <Box data-testid="enh-tab-insiders">
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
                <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
                  <Typography variant="subtitle1" fontWeight={700}>{t("enhancements.insiders.title")}</Typography>
                  {data.insiders?.insiderBuyUndervaluedConfirm && (
                    <Chip size="small" color="success" label={t("enhancements.insiders.undervaluedConfirmBadge")} />
                  )}
                </Stack>
                {!data.etf && (
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={<Refresh />}
                    disabled={busy !== null}
                    onClick={() =>
                      runAction("REFRESH_INSIDERS", async () => {
                        await enhancementApi.refreshInsiders(figi, {
                          contextDate: asOfDate || undefined,
                          verdict: reportContext?.verdict,
                        });
                        await syncAfterRefresh();
                        setNotice(t("enhancements.insiders.refreshed"));
                      })
                    }
                  >
                    {t("enhancements.insiders.refreshBtn")}
                  </Button>
                )}
              </Stack>

              {data.insiders?.status !== "AVAILABLE" ? (
                <Alert severity="info" sx={{ mb: 2 }}>
                  {t(`enhancements.reasons.${data.insiders?.reasonCode ?? "NO_FORM4_FILINGS_SYNCED"}`, {
                    defaultValue: data.insiders?.reasonCode ?? "NO_FORM4_FILINGS_SYNCED",
                  })}
                </Alert>
              ) : (
                <>
                  <Alert severity="info" sx={{ mb: 1.5 }}>
                    {t("enhancements.insiders.summaryLine", {
                      codePCount: data.insiders.codePBuyCount60d,
                      insiders: data.insiders.codePDistinctInsiders60d,
                      valueUsd: fmtCompactUsd(data.insiders.codePTotalValueUsd60d),
                      routineCount: data.insiders.routineOrSaleCount60d,
                    })}
                  </Alert>
                  <Box sx={{ overflowX: "auto", mb: 2.5 }}>
                    <Table size="small">
                      <TableHead>
                        <TableRow>
                          <TableCell>{t("enhancements.insiders.colDate")}</TableCell>
                          <TableCell>{t("enhancements.insiders.colOwner")}</TableCell>
                          <TableCell>{t("enhancements.insiders.colCode")}</TableCell>
                          <TableCell align="right">{t("enhancements.insiders.colShares")}</TableCell>
                          <TableCell align="right">{t("enhancements.insiders.colPrice")}</TableCell>
                          <TableCell align="right">{t("enhancements.insiders.colValue")}</TableCell>
                          <TableCell>{t("enhancements.insiders.colTag")}</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {data.insiders.recentTransactions.map((tx) => (
                          <TableRow key={`${tx.accession}-${tx.id ?? tx.transactionDate}`}>
                            <TableCell>{tx.transactionDate ?? tx.filingDate}</TableCell>
                            <TableCell>
                              <Typography variant="body2" fontWeight={600}>{tx.primaryOwnerName}</Typography>
                              <Typography variant="caption" color="text.secondary">{tx.primaryOwnerTitle ?? ""}</Typography>
                            </TableCell>
                            <TableCell>
                              <Chip
                                size="small"
                                color={tx.openMarketBuyCodeP ? "success" : "default"}
                                label={`${tx.transactionCode}${tx.acquiredDisposed ? ` (${tx.acquiredDisposed})` : ""}`}
                              />
                            </TableCell>
                            <TableCell align="right">{fmtNum(tx.shares, 0)}</TableCell>
                            <TableCell align="right">{fmtNum(tx.pricePerShare, 2)}</TableCell>
                            <TableCell align="right">{fmtCompactUsd(tx.transactionValueUsd)}</TableCell>
                            <TableCell>
                              <Stack direction="row" spacing={0.5} useFlexGap flexWrap="wrap">
                                {tx.openMarketBuyCodeP && (
                                  <Chip size="small" color="success" variant="outlined" label={t("enhancements.insiders.codePBuyTag")} />
                                )}
                                {tx.aff10b5One && (
                                  <Chip size="small" variant="outlined" label={t("enhancements.insiders.plan10b51Tag")} />
                                )}
                                {tx.routineOrPlanTrade && !tx.openMarketBuyCodeP && (
                                  <Chip size="small" variant="outlined" label={t("enhancements.insiders.routineTag")} />
                                )}
                                {safeUrl(tx.filingUrl) && (
                                  <Link href={tx.filingUrl!} target="_blank" rel="noopener noreferrer" variant="caption">
                                    SEC
                                  </Link>
                                )}
                              </Stack>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </Box>
                </>
              )}

              <Divider sx={{ my: 2 }} />
              <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1 }}>{t("enhancements.inst13f.title")}</Typography>
              {data.institutional13f?.status !== "AVAILABLE" ? (
                <Alert severity="info">
                  {t(`enhancements.reasons.${data.institutional13f?.reasonCode ?? "NO_13F_HOLDINGS_MATCHED"}`, {
                    defaultValue: data.institutional13f?.reasonCode ?? "NO_13F_HOLDINGS_MATCHED",
                  })}
                </Alert>
              ) : (
                <Box sx={{ overflowX: "auto" }}>
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }}>
                    {t("enhancements.inst13f.summaryLine", {
                      period: data.institutional13f.latestReportPeriod ?? "—",
                      holding: data.institutional13f.trackedManagersHoldingCount,
                      added: data.institutional13f.managersAddedOrNewCount,
                      reduced: data.institutional13f.managersReducedOrExitedCount,
                    })}
                  </Typography>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>{t("enhancements.inst13f.colManager")}</TableCell>
                        <TableCell>{t("enhancements.inst13f.colPeriod")}</TableCell>
                        <TableCell>{t("enhancements.inst13f.colAction")}</TableCell>
                        <TableCell align="right">{t("enhancements.inst13f.colShares")}</TableCell>
                        <TableCell align="right">{t("enhancements.inst13f.colChange")}</TableCell>
                        <TableCell align="right">{t("enhancements.inst13f.colValueUsd")}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {data.institutional13f.holdings.map((h) => (
                        <TableRow key={`${h.managerCik}-${h.reportPeriod}-${h.putCall}`}>
                          <TableCell>{h.managerName}</TableCell>
                          <TableCell>{h.reportPeriod}</TableCell>
                          <TableCell>
                            <Chip
                              size="small"
                              color={
                                h.actionType === "NEW" || h.actionType === "ADD"
                                  ? "success"
                                  : h.actionType === "REDUCE" || h.actionType === "NOT_LISTED"
                                    ? "warning"
                                    : "default"
                              }
                              label={t(`enhancements.inst13f.actions.${h.actionType}`, { defaultValue: h.actionType })}
                            />
                          </TableCell>
                          <TableCell align="right">{fmtNum(h.shares, 0)}</TableCell>
                          <TableCell align="right">{fmtPct(h.shareChangePct, 1, true)}</TableCell>
                          <TableCell align="right">{fmtCompactUsd(h.marketValueUsd)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Box>
              )}
            </Box>
          )}

          {/* Tab 2: Short Interest */}
          {tab === 2 && (
            <Box data-testid="enh-tab-short">
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography variant="subtitle1" fontWeight={700}>{t("enhancements.shortInterest.title")}</Typography>
                  {data.shortInterest?.signalCode && data.shortInterest.signalCode !== "UNAVAILABLE" && (
                    <Chip
                      size="small"
                      color={
                        data.shortInterest.signalCode === "SHORT_TRAP_WARNING"
                          ? "error"
                          : data.shortInterest.signalCode === "SHORT_SQUEEZE_POTENTIAL"
                            ? "warning"
                            : "default"
                      }
                      label={t(`enhancements.shortInterest.signals.${data.shortInterest.signalCode}`, {
                        defaultValue: data.shortInterest.signalCode,
                      })}
                    />
                  )}
                </Stack>
                <Button
                  size="small"
                  variant="outlined"
                  startIcon={<Refresh />}
                  disabled={busy !== null}
                  onClick={() =>
                    runAction("REFRESH_SHORT", async () => {
                      await enhancementApi.refreshShortInterest(figi, {
                        contextDate: asOfDate || undefined,
                        fundamentalBacking: reportContext?.fundamentalBacking,
                        resistanceBreakout: reportContext?.resistanceBreakout,
                      });
                      await syncAfterRefresh();
                      setNotice(t("enhancements.shortInterest.refreshed"));
                    })
                  }
                >
                  {t("enhancements.shortInterest.refreshBtn")}
                </Button>
              </Stack>

              {data.shortInterest?.status !== "AVAILABLE" ? (
                <Alert severity="info">
                  {t(`enhancements.reasons.${data.shortInterest?.reasonCode ?? "SHORT_INTEREST_NOT_SYNCED"}`, {
                    defaultValue: data.shortInterest?.reasonCode ?? "SHORT_INTEREST_NOT_SYNCED",
                  })}
                </Alert>
              ) : (
                <Grid container spacing={1.5}>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.shortInterest.shortPctLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>
                        {data.shortInterest.shortPctStatus === "AVAILABLE"
                          ? fmtPct(data.shortInterest.shortPctOfSharesOutstanding, 2)
                          : "—"}
                      </Typography>
                      {data.shortInterest.shortPctReason && (
                        <Typography variant="caption" color="text.secondary" display="block">
                          {t(`enhancements.reasons.${data.shortInterest.shortPctReason}`, {
                            defaultValue: data.shortInterest.shortPctReason,
                          })}
                        </Typography>
                      )}
                    </Paper>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.shortInterest.shortSharesLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>{fmtNum(data.shortInterest.shortInterestShares, 0)}</Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {t("enhancements.shortInterest.biweeklyChange", { value: fmtPct(data.shortInterest.shortChangeVsPriorPct, 1, true) })}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.shortInterest.dtcLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>
                        {fmtNum(data.shortInterest.daysToCoverSource, 2)} / {fmtNum(data.shortInterest.daysToCover20d, 2)}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {t("enhancements.shortInterest.dtcCaption")}
                      </Typography>
                    </Paper>
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                    <Paper variant="outlined" sx={{ p: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">{t("enhancements.shortInterest.settlementLabel")}</Typography>
                      <Typography variant="h6" fontWeight={800}>{data.shortInterest.settlementDate ?? "—"}</Typography>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {data.shortInterest.source ?? ""}
                      </Typography>
                    </Paper>
                  </Grid>
                </Grid>
              )}
            </Box>
          )}

          {/* Tab 3: News, 8-K Guidance & 3-Channel AI / Manual Summary */}
          {tab === 3 && (
            <Box data-testid="enh-tab-news">
              <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" spacing={1} sx={{ mb: 1.5 }}>
                <Typography variant="subtitle1" fontWeight={700}>{t("enhancements.news.title")}</Typography>
                <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={<Refresh />}
                    disabled={busy !== null}
                    onClick={() =>
                      runAction("REFRESH_NEWS", async () => {
                        await enhancementApi.refreshNews(figi);
                        await invalidateEnhancementsForFigi();
                        setNotice(t("enhancements.news.refreshed"));
                      })
                    }
                  >
                    {t("enhancements.news.refreshBtn")}
                  </Button>
                  {data.aiChannel.automatedAvailable && (
                    <Button
                      size="small"
                      variant="contained"
                      startIcon={<AutoAwesome />}
                      disabled={busy !== null}
                      onClick={() =>
                        runAction("SUMMARIZE_NEWS", async () => {
                          const res = await enhancementApi.summarizeNewsAutomated(figi);
                          await invalidateEnhancementsForFigi();
                          if (res.status === "COMPLETED") {
                            setNotice(t("enhancements.news.summarized"));
                          } else if (res.status === "IN_PROGRESS") {
                            setNotice(t("enhancements.news.statusNoticeInProgress"));
                          } else {
                            setError(t(`enhancements.news.summaryStatuses.${res.status}`, { defaultValue: res.status }));
                          }
                        })
                      }
                    >
                      {t("enhancements.news.aiSummarizeBtn")}
                    </Button>
                  )}
                  <Button
                    size="small"
                    variant="outlined"
                    color="secondary"
                    startIcon={<ContentCopy />}
                    disabled={busy !== null}
                    onClick={() =>
                      runAction("NEWS_PROMPT", async () => {
                        const p = await enhancementApi.getNewsPrompt(figi);
                        setNewsPrompt(p);
                        await copyToClipboard(p.promptText);
                      })
                    }
                  >
                    {t("enhancements.news.copyManualPromptBtn")}
                  </Button>
                </Stack>
              </Stack>

              <Alert severity="info" sx={{ mb: 1.5 }}>{t("enhancements.news.advisoryDisclaimer")}</Alert>

              {newsPrompt && (
                <Paper variant="outlined" sx={{ p: 1.5, mb: 2 }}>
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                    {t("enhancements.news.manualBoxTitle")}
                  </Typography>
                  <TextField
                    fullWidth
                    multiline
                    minRows={3}
                    maxRows={6}
                    size="small"
                    value={newsPrompt.promptText}
                    InputProps={{ readOnly: true }}
                    sx={{ mb: 1 }}
                  />
                  <TextField
                    fullWidth
                    multiline
                    minRows={3}
                    size="small"
                    label={t("enhancements.news.pasteJsonLabel")}
                    value={newsImportJson}
                    onChange={(e) => setNewsImportJson(e.target.value)}
                    sx={{ mb: 1 }}
                  />
                  <Button
                    size="small"
                    variant="contained"
                    disabled={busy !== null || !newsImportJson.trim()}
                    onClick={() =>
                      runAction("IMPORT_NEWS_JSON", async () => {
                        await enhancementApi.importNewsManual(figi, newsImportJson, newsPrompt.inputFingerprint);
                        setNewsImportJson("");
                        setNewsPrompt(null);
                        await invalidateEnhancementsForFigi();
                        setNotice(t("enhancements.news.manualImported"));
                      })
                    }
                  >
                    {t("enhancements.news.importJsonBtn")}
                  </Button>
                </Paper>
              )}

              {data.eventSummaries.length > 0 && (
                <Stack spacing={1.5} sx={{ mb: 2 }}>
                  {data.eventSummaries.slice(0, 3).map((s) => (
                    <Paper key={s.id ?? s.inputFingerprint} variant="outlined" sx={{ p: 1.5 }}>
                      <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap" sx={{ mb: 1 }}>
                        {s.status !== "COMPLETED" && (
                          <Chip
                            size="small"
                            color={s.status === "IN_PROGRESS" ? "warning" : "error"}
                            label={t(`enhancements.news.summaryStatuses.${s.status}`, { defaultValue: s.status })}
                          />
                        )}
                        {s.eventNature && (
                          <Chip
                            size="small"
                            color={
                              s.eventNature === "FUNDAMENTAL_DETERIORATION"
                                ? "error"
                                : s.eventNature === "POSITIVE_CATALYST"
                                  ? "success"
                                  : "info"
                            }
                            label={t(`enhancements.news.natures.${s.eventNature}`, {
                              defaultValue: s.eventNature,
                            })}
                          />
                        )}
                        <Chip size="small" variant="outlined" label={`${s.aiProvider} · ${s.modelName ?? ""}`} />
                        <Typography variant="caption" color="text.secondary">
                          {formatHongKongDateTime(s.createdAt, i18n.language)}
                        </Typography>
                      </Stack>
                      {s.status !== "COMPLETED" && s.errorMessage && (
                        <Alert severity={s.status === "IN_PROGRESS" ? "info" : "warning"} sx={{ mb: 1 }}>
                          {t(`enhancements.errors.${s.errorMessage}`, { defaultValue: s.errorMessage })}
                        </Alert>
                      )}
                      <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
                        {s.bulletPoints.map((b, idx) => (
                          <li key={idx}><Typography variant="body2">{b}</Typography></li>
                        ))}
                      </Box>
                      {s.guidance && (
                        <Alert severity="success" sx={{ mt: 1 }}>
                          <Typography variant="subtitle2" fontWeight={700}>
                            {t("enhancements.news.guidanceTitle", {
                              period: s.guidance.periodLabel ?? "—",
                              action: s.guidance.action ?? "",
                            })}
                          </Typography>
                          <Typography variant="body2">
                            {t("enhancements.news.guidanceNumbers", {
                              revLow: fmtNum(s.guidance.revenueLow, 2),
                              revHigh: fmtNum(s.guidance.revenueHigh, 2),
                              epsLow: fmtNum(s.guidance.epsLow, 2),
                              epsHigh: fmtNum(s.guidance.epsHigh, 2),
                              currency: s.guidance.currency ?? "",
                              scale: s.guidance.unitScale ?? "",
                            })}
                          </Typography>
                          {s.guidance.quoteExcerpt && (
                            <Typography variant="caption" display="block" color="text.secondary">
                              “{s.guidance.quoteExcerpt}”
                            </Typography>
                          )}
                        </Alert>
                      )}
                    </Paper>
                  ))}
                </Stack>
              )}

              <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                {t("enhancements.news.cachedListTitle", { count: data.recentNewsEvents.length })}
              </Typography>
              {data.recentNewsEvents.length === 0 ? (
                <Typography variant="body2" color="text.secondary">{t("enhancements.news.emptyCache")}</Typography>
              ) : (
                <Stack spacing={1}>
                  {data.recentNewsEvents.slice(0, 10).map((ev) => (
                    <Box key={`${ev.sourceType}-${ev.externalId}`} sx={{ pb: 0.75, borderBottom: "1px solid", borderColor: "divider" }}>
                      <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
                        <Chip size="small" variant="outlined" label={ev.sourceType} />
                        {ev.secItems && <Chip size="small" color="primary" variant="outlined" label={`Items ${ev.secItems}`} />}
                        <Typography variant="caption" color="text.secondary">
                          {ev.publisher ?? ""} · {formatHongKongDateTime(ev.publishedAt, i18n.language)}
                        </Typography>
                      </Stack>
                      {safeUrl(ev.url) ? (
                        <Link href={ev.url!} target="_blank" rel="noopener noreferrer" variant="body2" fontWeight={600}>
                          {ev.headline}
                        </Link>
                      ) : (
                        <Typography variant="body2" fontWeight={600}>{ev.headline}</Typography>
                      )}
                      {ev.exhibit991Url && safeUrl(ev.exhibit991Url) && (
                        <Box>
                          <Link href={ev.exhibit991Url} target="_blank" rel="noopener noreferrer" variant="caption">
                            {t("enhancements.news.exhibit991Link")} ({ev.exhibitExtractionStatus ?? ""})
                          </Link>
                        </Box>
                      )}
                    </Box>
                  ))}
                </Stack>
              )}
            </Box>
          )}

          {/* Tab 4: SEC Custom Tag Explicit Mapping (Human Confirmation Required) */}
          {tab === 4 && !data.etf && (
            <Box data-testid="enh-tab-custom-tags">
              <Alert severity="warning" sx={{ mb: 1.5 }} data-testid="custom-tag-isolation-warning">
                {t("enhancements.customTags.isolationWarning")}
              </Alert>

              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 2 }}>
                <Button
                  size="small"
                  variant="outlined"
                  disabled={busy !== null}
                  onClick={() =>
                    runAction("SAMPLE_TAGS", async () => {
                      const list = await enhancementApi.getCustomTagCandidates(figi);
                      setCandidates(list);
                      setNotice(t("enhancements.customTags.sampledCount", { count: list.length }));
                    })
                  }
                >
                  {t("enhancements.customTags.sampleBtn")}
                </Button>
                {data.aiChannel.automatedAvailable && (
                  <Button
                    size="small"
                    variant="contained"
                    startIcon={<AutoAwesome />}
                    disabled={busy !== null}
                    onClick={() =>
                      runAction("PROPOSE_TAGS", async () => {
                        const preview = await enhancementApi.proposeCustomTagsAutomated(figi);
                        loadTagPreview(preview);
                      })
                    }
                  >
                    {t("enhancements.customTags.aiProposeBtn")}
                  </Button>
                )}
                <Button
                  size="small"
                  variant="outlined"
                  color="secondary"
                  startIcon={<ContentCopy />}
                  disabled={busy !== null}
                  onClick={() =>
                    runAction("TAG_PROMPT", async () => {
                      const p = await enhancementApi.getCustomTagPrompt(figi);
                      setTagPrompt(p);
                      await copyToClipboard(p.promptText);
                    })
                  }
                >
                  {t("enhancements.customTags.copyManualPromptBtn")}
                </Button>
              </Stack>

              {tagPrompt && (
                <Paper variant="outlined" sx={{ p: 1.5, mb: 2 }}>
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                    {t("enhancements.customTags.manualBoxTitle")}
                  </Typography>
                  <TextField fullWidth multiline minRows={3} maxRows={6} size="small" value={tagPrompt.promptText} InputProps={{ readOnly: true }} sx={{ mb: 1 }} />
                  <TextField
                    fullWidth
                    multiline
                    minRows={3}
                    size="small"
                    label={t("enhancements.customTags.pasteJsonLabel")}
                    value={tagImportJson}
                    onChange={(e) => setTagImportJson(e.target.value)}
                    sx={{ mb: 1 }}
                  />
                  <Button
                    size="small"
                    variant="contained"
                    disabled={busy !== null || !tagImportJson.trim()}
                    onClick={() =>
                      runAction("IMPORT_TAG_PREVIEW", async () => {
                        const preview = await enhancementApi.importCustomTagsManual(figi, tagImportJson);
                        loadTagPreview(preview);
                        setNotice(t("enhancements.customTags.previewReadyNotSaved"));
                      })
                    }
                  >
                    {t("enhancements.customTags.importPreviewBtn")}
                  </Button>
                </Paper>
              )}

              {tagPreview && (
                <Paper variant="outlined" sx={{ p: 1.5, mb: 2, borderColor: "warning.main" }} data-testid="custom-tag-preview-box">
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                    {t("enhancements.customTags.previewTitle", { count: tagPreview.proposals.length })}
                  </Typography>
                  {tagPreview.proposals.length === 0 ? (
                    <Typography variant="body2" color="text.secondary">{t("enhancements.customTags.previewEmpty")}</Typography>
                  ) : (
                    <>
                      <Box sx={{ overflowX: "auto", mb: 1.5 }}>
                        <Table size="small">
                          <TableHead>
                            <TableRow>
                              <TableCell padding="checkbox" />
                              <TableCell>{t("enhancements.customTags.colSourceTag")}</TableCell>
                              <TableCell>{t("enhancements.customTags.colConcept")}</TableCell>
                              <TableCell>{t("enhancements.customTags.colSign")}</TableCell>
                              <TableCell>{t("enhancements.customTags.colRationale")}</TableCell>
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {tagPreview.proposals.map((item) => {
                              const k = proposalKey(item);
                              return (
                                <TableRow key={k}>
                                  <TableCell padding="checkbox">
                                    <Checkbox
                                      size="small"
                                      checked={Boolean(selectedTags[k])}
                                      onChange={(e) => setSelectedTags((prev) => ({ ...prev, [k]: e.target.checked }))}
                                    />
                                  </TableCell>
                                  <TableCell>
                                    <Typography variant="body2" fontWeight={600}>{item.taxonomy}:{item.customTag}</Typography>
                                    <Typography variant="caption" color="text.secondary">{item.unit} · {item.labelEn ?? ""}</Typography>
                                  </TableCell>
                                  <TableCell><Chip size="small" color="primary" label={item.targetConcept} /></TableCell>
                                  <TableCell>{item.signMultiplier > 0 ? "+1" : "-1"}</TableCell>
                                  <TableCell>{item.rationale ?? ""}</TableCell>
                                </TableRow>
                              );
                            })}
                          </TableBody>
                        </Table>
                      </Box>
                      <Stack direction="row" spacing={1}>
                        <Button
                          size="small"
                          variant="contained"
                          color="success"
                          startIcon={<CheckCircleOutline />}
                          disabled={busy !== null}
                          onClick={handleConfirmSelectedTags}
                        >
                          {t("enhancements.customTags.confirmSaveBtn")}
                        </Button>
                        <Button size="small" onClick={() => setTagPreview(null)} disabled={busy !== null}>
                          {t("enhancements.customTags.discardPreviewBtn")}
                        </Button>
                      </Stack>
                    </>
                  )}
                </Paper>
              )}

              {candidates && candidates.length > 0 && (
                <Box sx={{ mb: 2 }}>
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.75 }}>
                    {t("enhancements.customTags.candidatesTitle", { count: candidates.length })}
                  </Typography>
                  <Box sx={{ maxHeight: 220, overflow: "auto" }}>
                    <Table size="small" stickyHeader>
                      <TableHead>
                        <TableRow>
                          <TableCell>{t("enhancements.customTags.colSourceTag")}</TableCell>
                          <TableCell>{t("enhancements.customTags.colUnit")}</TableCell>
                          <TableCell align="right">{t("enhancements.customTags.colLatestVal")}</TableCell>
                          <TableCell>{t("enhancements.customTags.colPeriod")}</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {candidates.map((c) => (
                          <TableRow key={`${c.taxonomy}-${c.customTag}-${c.unit}`}>
                            <TableCell>
                              <Typography variant="caption" fontWeight={600}>{c.taxonomy}:{c.customTag}</Typography>
                              <Typography variant="caption" color="text.secondary" display="block">{c.labelEn ?? ""}</Typography>
                            </TableCell>
                            <TableCell>{c.unit}</TableCell>
                            <TableCell align="right">{fmtCompactUsd(c.latestValue)}</TableCell>
                            <TableCell>{c.latestPeriodEnd ?? "—"}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </Box>
                </Box>
              )}

              <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 1 }}>
                {t("enhancements.customTags.confirmedTitle", { count: data.confirmedCustomTags.length })}
              </Typography>
              {data.confirmedCustomTags.length === 0 ? (
                <Typography variant="body2" color="text.secondary">{t("enhancements.customTags.noConfirmedMappings")}</Typography>
              ) : (
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>{t("enhancements.customTags.colSourceTag")}</TableCell>
                      <TableCell>{t("enhancements.customTags.colConcept")}</TableCell>
                      <TableCell>{t("enhancements.customTags.colStatus")}</TableCell>
                      <TableCell align="right">{t("enhancements.customTags.colAction")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.confirmedCustomTags.map((m) => (
                      <TableRow key={m.id}>
                        <TableCell>{m.taxonomy}:{m.customTag} ({m.unit})</TableCell>
                        <TableCell>{m.targetConcept} ({m.signMultiplier > 0 ? "+1" : "-1"})</TableCell>
                        <TableCell>
                          <Chip size="small" color={m.status === "CONFIRMED" ? "success" : "default"} label={m.status} />
                        </TableCell>
                        <TableCell align="right">
                          {m.status === "CONFIRMED" && (
                            <Button
                              size="small"
                              color="error"
                              startIcon={<DeleteOutline />}
                              disabled={busy !== null}
                              onClick={() =>
                                runAction(`DISABLE_${m.id}`, async () => {
                                  await enhancementApi.disableCustomTag(figi, m.id);
                                  await syncAfterRefresh();
                                  setNotice(t("enhancements.customTags.disabledSuccess"));
                                })
                              }
                            >
                              {t("enhancements.customTags.disableBtn")}
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Box>
          )}
        </>
      )}
    </Paper>
  );
}
