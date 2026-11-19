import React, { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Box,
  Chip,
  Divider,
  FormControl,
  InputLabel,
  Link,
  MenuItem,
  Paper,
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
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from "@mui/material";
import Grid from "@mui/material/Grid";
import {
  WarningAmber,
  EventNote,
  ShowChart,
  North,
  South,
  InfoOutlined,
  Schedule,
  Language,
  Timeline,
  BarChart,
  Radar,
  AccountBalance,
  Analytics,
} from "@mui/icons-material";
import MetricCard from "../components/MetricCard";
import { useFetch } from "../hooks/api/useApi";
import {
  DashboardResponse,
  OpportunityRadarItem,
  SectorMoneyFlowRow,
  SignalTrackingGroupStats,
} from "../types/overview";
import { apiConfig } from "../apiConfig";
import { useTranslation } from "react-i18next";

const getEventColor = (level: string): "error" | "warning" | "default" => {
  if (level === "HIGH") return "error";
  if (level === "MEDIUM") return "warning";
  return "default";
};

const getTrendColor = (trend: string) =>
  trend === "up" ? "success.main" : trend === "down" ? "error.main" : "text.secondary";

const getDeviationColor = (value: string) =>
  value.startsWith("+") ? "success.main" : value.startsWith("-") ? "error.main" : "text.secondary";

const getVolumeRatioColor = (value: string) => {
  const numeric = parseFloat(value.replace("x", ""));
  if (numeric > 1.2) return "success.main";
  if (numeric < 1.0) return "error.main";
  return "text.primary";
};

const formatSignedPct = (value?: number | null, digits = 2) => {
  if (value == null || !Number.isFinite(value)) return "—";
  const pct = value * 100;
  return `${pct > 0 ? "+" : ""}${pct.toFixed(digits)}%`;
};

const formatPlainPct = (value?: number | null, digits = 1) => {
  if (value == null || !Number.isFinite(value)) return "—";
  return `${(value * 100).toFixed(digits)}%`;
};

const formatDec = (value?: number | null, digits = 2) => {
  if (value == null || !Number.isFinite(value)) return "—";
  return value.toFixed(digits);
};

const formatCompactUsd = (value?: number | null) => {
  if (value == null || !Number.isFinite(value)) return "—";
  if (value >= 1e12) return `$${(value / 1e12).toFixed(2)}T`;
  if (value >= 1e9) return `$${(value / 1e9).toFixed(2)}B`;
  if (value >= 1e6) return `$${(value / 1e6).toFixed(1)}M`;
  return `$${value.toFixed(0)}`;
};

const signedColor = (value?: number | null) =>
  value == null ? "text.secondary" : value > 0 ? "success.main" : value < 0 ? "error.main" : "text.secondary";

const flowStateColor = (state?: string | null): "success" | "error" | "default" =>
  state === "INFLOW" ? "success" : state === "OUTFLOW" ? "error" : "default";

const regimeChipColor = (regime?: string | null): "success" | "info" | "warning" | "error" | "default" => {
  if (regime === "RISK_ON") return "success";
  if (regime === "RECOVERY") return "info";
  if (regime === "CORRECTION") return "warning";
  if (regime === "BEAR" || regime === "PANIC") return "error";
  return "default";
};

const HomeDashboard: React.FC = () => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { data, isLoading, isError, error } = useFetch<DashboardResponse>(apiConfig.overview_dashboard, {});

  const [radarTab, setRadarTab] = useState<"VALUE" | "STARTER">("VALUE");
  const [radarSearch, setRadarSearch] = useState("");
  const [radarIndustry, setRadarIndustry] = useState("ALL");
  const [radarTier, setRadarTier] = useState("ALL");
  const [radarMaxDistPct, setRadarMaxDistPct] = useState("");
  const [radarMinRr, setRadarMinRr] = useState("");
  const [flowSort, setFlowSort] = useState<"cmf20d" | "shareDelta" | "breadth50" | "dollarVol1d">("cmf20d");
  const [flowOrder, setFlowOrder] = useState<"TOP" | "BOTTOM">("TOP");
  const [statsTab, setStatsTab] = useState<"TYPE" | "REGIME" | "INDUSTRY" | "TIER">("TYPE");

  const opportunityRadar = data?.opportunityRadar ?? null;
  const sectorFlow = data?.sectorMoneyFlow ?? null;
  const sectorEtfs = data?.sectorEtfs ?? [];
  const signalStats = data?.signalTrackingStats ?? null;
  const marketRegime = data?.marketRegime ?? null;

  const activeRadarRaw: OpportunityRadarItem[] = useMemo(() => {
    if (!opportunityRadar) return [];
    return radarTab === "VALUE"
      ? (opportunityRadar.valueOpportunities ?? [])
      : (opportunityRadar.starterOpportunities ?? []);
  }, [opportunityRadar, radarTab]);

  const radarIndustryOptions = useMemo(() => {
    const map = new Map<string, string>();
    const all = [
      ...(opportunityRadar?.valueOpportunities ?? []),
      ...(opportunityRadar?.starterOpportunities ?? []),
    ];
    for (const item of all) {
      if (item.industryCode) {
        map.set(item.industryCode, item.industryName ?? item.industryCode);
      }
    }
    return Array.from(map.entries()).sort((a, b) => a[1].localeCompare(b[1]));
  }, [opportunityRadar]);

  const translateIndustry = (name?: string | null, fallbackCode?: string | null) => {
    const raw = name ?? fallbackCode;
    if (!raw) return "—";
    return t(raw, { defaultValue: raw, nsSeparator: false, keySeparator: false });
  };

  const filteredRadar = useMemo(() => {
    const kw = radarSearch.trim().toLowerCase();
    const maxDistNum = radarMaxDistPct.trim() === "" ? null : parseFloat(radarMaxDistPct);
    const minRrNum = radarMinRr.trim() === "" ? null : parseFloat(radarMinRr);
    return activeRadarRaw.filter((item) => {
      if (kw) {
        const localizedIndustry = item.industryName
          ? t(item.industryName, { defaultValue: item.industryName, nsSeparator: false, keySeparator: false })
          : "";
        const hay = `${item.symbol} ${item.stockName ?? ""} ${item.industryName ?? ""} ${localizedIndustry}`.toLowerCase();
        if (!hay.includes(kw)) return false;
      }
      if (radarIndustry !== "ALL" && item.industryCode !== radarIndustry) return false;
      if (radarTier !== "ALL" && item.marketCapTier !== radarTier) return false;
      if (maxDistNum != null && Number.isFinite(maxDistNum)) {
        if (item.supportDistancePct == null || item.supportDistancePct * 100 > maxDistNum) return false;
      }
      if (radarTab === "VALUE" && minRrNum != null && Number.isFinite(minRrNum)) {
        if (item.netRiskReward == null || item.netRiskReward < minRrNum) return false;
      }
      return true;
    });
  }, [activeRadarRaw, radarTab, radarSearch, radarIndustry, radarTier, radarMaxDistPct, radarMinRr, t]);

  const sortedSectorFlows: SectorMoneyFlowRow[] = useMemo(() => {
    const rows = [...(sectorFlow?.rows ?? [])];
    const numCompare = (a?: number | null, b?: number | null) => {
      if (a == null && b == null) return 0;
      if (a == null) return 1;
      if (b == null) return -1;
      return flowOrder === "BOTTOM" ? a - b : b - a;
    };
    rows.sort((a, b) => {
      if (flowSort === "shareDelta") return numCompare(a.volumeShareDelta5dVs20d, b.volumeShareDelta5dVs20d);
      if (flowSort === "breadth50") return numCompare(a.breadthAboveSma50Pct, b.breadthAboveSma50Pct);
      if (flowSort === "dollarVol1d") return numCompare(a.dollarVolume1d, b.dollarVolume1d);
      return numCompare(a.cmf20d, b.cmf20d);
    });
    return rows.slice(0, 20);
  }, [sectorFlow, flowSort, flowOrder]);

  const activeStatsGroups: SignalTrackingGroupStats[] = useMemo(() => {
    if (!signalStats) return [];
    if (statsTab === "REGIME") return signalStats.byMarketRegime ?? [];
    if (statsTab === "INDUSTRY") return signalStats.byIndustry ?? [];
    if (statsTab === "TIER") return signalStats.byMarketCapTier ?? [];
    return signalStats.bySignalType ?? [];
  }, [signalStats, statsTab]);

  const formatStatsGroupLabel = (row: SignalTrackingGroupStats) => {
    if (row.groupKey === "UNKNOWN") return t("dashboard.signalStats.unknownGroup");
    if (statsTab === "TYPE") {
      return t(`dashboard.signalStats.signalTypes.${row.groupKey}`, { defaultValue: row.groupLabel });
    }
    if (statsTab === "REGIME") {
      return t(`analysisReport.states.${row.groupKey}`, { defaultValue: row.groupLabel });
    }
    if (statsTab === "TIER") {
      return t(`dashboard.radar.tiers.${row.groupKey}`, { defaultValue: row.groupLabel });
    }
    if (statsTab === "INDUSTRY") {
      return translateIndustry(row.groupLabel, row.groupKey);
    }
    return row.groupLabel;
  };

  if (isLoading) {
    return (
      <Paper
        elevation={0}
        sx={{ p: 3, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
      >
        <Typography sx={{ fontSize: 14, color: "text.secondary" }}>{t("dashboard.loading")}</Typography>
      </Paper>
    );
  }

  if (isError || !data) {
    return (
      <Paper
        elevation={0}
        sx={{ p: 3, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
      >
        <Typography sx={{ fontSize: 14, color: "error.main" }}>
          {t("dashboard.loadError")}{error instanceof Error ? `: ${error.message}` : ""}
        </Typography>
      </Paper>
    );
  }

  const {
    snapshotMeta,
    tapeRows,
    indexRows,
    etfRows,
    alerts,
    events,
    signalRows,
    fearGreedComponents,
    marketSummary,
    executionBias,
  } = data;

  const fearGreedSignal = signalRows.find((item) => item.code === "FEAR_GREED");
  const cnnVixScore = signalRows.find((item) => item.code === "CNN_VIX_SCORE");
  const cnnPutCallScore = signalRows.find((item) => item.code === "CNN_PUT_CALL_SCORE");
  const cnnMarketBreadthScore = signalRows.find((item) => item.code === "CNN_MARKET_BREADTH_SCORE");
  const translateMessage = (message: { code: string; params: Record<string, string | number> }) =>
    t(`dashboard.messages.${message.code}`, message.params);

  const regimeLabel = marketRegime?.regime
    ? t(`analysisReport.states.${marketRegime.regime}`, { defaultValue: marketRegime.regime })
    : "—";

  return (
    <Stack spacing={2}>
      {marketRegime?.headwindActive && (
        <Alert severity="error" variant="filled" data-testid="market-headwind-banner">
          {t("dashboard.marketRegime.headwindBanner", { regime: regimeLabel })}
        </Alert>
      )}
      {!marketRegime?.headwindActive && marketRegime?.correctionActive && (
        <Alert severity="warning" variant="outlined" data-testid="market-correction-banner">
          {t("dashboard.marketRegime.correctionBanner")}
        </Alert>
      )}

      <Paper
        elevation={0}
        sx={{ px: 2, py: 1.25, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
      >
        <Stack direction={{ xs: "column", md: "row" }} spacing={1.2} justifyContent="space-between" alignItems={{ md: "center" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
              <Schedule sx={{ fontSize: 18, color: "text.secondary" }} />
              <Typography sx={{ fontSize: 13, color: "text.secondary" }}>
                {t("dashboard.marketDate")}：{snapshotMeta.marketDate}
              </Typography>
            </Box>
            {marketRegime && (
              <Box sx={{ display: "flex", alignItems: "center", gap: 0.75 }}>
                <Typography sx={{ fontSize: 13, color: "text.secondary" }}>
                  {t("dashboard.marketRegime.title")}：
                </Typography>
                <Chip
                  label={regimeLabel}
                  size="small"
                  color={regimeChipColor(marketRegime.regime)}
                />
                {marketRegime.vixClose != null && (
                  <Typography sx={{ fontSize: 12, color: "text.secondary" }}>
                    {t("dashboard.marketRegime.vixLabel")} {formatDec(marketRegime.vixClose, 2)}
                  </Typography>
                )}
              </Box>
            )}
          </Box>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Language sx={{ fontSize: 18, color: "text.secondary" }} />
            <Typography sx={{ fontSize: 13, color: "text.secondary" }}>{t("dashboard.dataSource")}：</Typography>
            {snapshotMeta.sources.map((source) => (
              <Chip key={source} label={t(`dashboard.source.${source}`)} size="small" variant="outlined" />
            ))}
          </Box>
        </Stack>
      </Paper>

      <Paper
        elevation={0}
        sx={{
          px: 2,
          py: 1.25,
          borderRadius: 1,
          bgcolor: "background.paper",
          border: 1,
          borderColor: "divider",
          overflowX: "auto",
        }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: 3, minWidth: 900 }}>
          {tapeRows.map((item) => (
            <Box key={item.symbol} sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              <Typography sx={{ fontSize: 12, fontWeight: 700, color: "text.secondary" }}>{item.symbol}</Typography>
              <Typography sx={{ fontSize: 14, fontWeight: 700, color: "text.primary" }}>{item.value}</Typography>
              <Typography
                sx={{
                  fontSize: 13,
                  fontWeight: 700,
                  color: item.positive ? "success.main" : "error.main",
                }}
              >
                {item.change}
              </Typography>
            </Box>
          ))}
        </Box>
      </Paper>

      {/* Batch 03 核心一：每日雙軌機會雷達（置頂實戰入口） */}
      <Paper
        component="section"
        aria-label={t("dashboard.radar.title")}
        elevation={0}
        sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
      >
        <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={1} sx={{ mb: 1.5 }}>
          <Box>
            <Stack direction="row" spacing={1} alignItems="center">
              <Radar fontSize="small" color="primary" />
              <Typography sx={{ fontSize: 17, fontWeight: 800, color: "text.primary" }}>
                {t("dashboard.radar.title")}
              </Typography>
              {opportunityRadar?.radarDate && (
                <Chip label={opportunityRadar.radarDate} size="small" variant="outlined" />
              )}
            </Stack>
            <Typography sx={{ fontSize: 12, color: "text.secondary", mt: 0.4 }}>
              {t("dashboard.radar.subtitle")}
            </Typography>
          </Box>
        </Stack>

        {opportunityRadar?.headwindActive && (
          <Alert severity="warning" sx={{ mb: 1.5 }}>
            {t("dashboard.radar.headwindNotice")}
          </Alert>
        )}

        <Tabs
          value={radarTab}
          onChange={(_e, val) => setRadarTab(val)}
          sx={{ borderBottom: 1, borderColor: "divider", mb: 1.5 }}
        >
          <Tab
            value="VALUE"
            label={t("dashboard.radar.tabValue", { count: opportunityRadar?.valueCount ?? 0 })}
          />
          <Tab
            value="STARTER"
            label={t("dashboard.radar.tabStarter", { count: opportunityRadar?.starterCount ?? 0 })}
          />
        </Tabs>

        <Stack direction={{ xs: "column", md: "row" }} spacing={1.2} sx={{ mb: 1.5 }} useFlexGap flexWrap="wrap">
          <TextField
            size="small"
            label={t("dashboard.radar.filterSearch")}
            value={radarSearch}
            onChange={(e) => setRadarSearch(e.target.value)}
            sx={{ minWidth: 180 }}
          />
          <FormControl size="small" sx={{ minWidth: 180 }}>
            <InputLabel>{t("dashboard.radar.filterIndustry")}</InputLabel>
            <Select
              label={t("dashboard.radar.filterIndustry")}
              value={radarIndustry}
              onChange={(e) => setRadarIndustry(e.target.value)}
            >
              <MenuItem value="ALL">{t("dashboard.radar.filterIndustryAll")}</MenuItem>
              {radarIndustryOptions.map(([code, name]) => (
                <MenuItem key={code} value={code}>{translateIndustry(name, code)}</MenuItem>
              ))}
            </Select>
          </FormControl>
          <FormControl size="small" sx={{ minWidth: 140 }}>
            <InputLabel>{t("dashboard.radar.filterTier")}</InputLabel>
            <Select
              label={t("dashboard.radar.filterTier")}
              value={radarTier}
              onChange={(e) => setRadarTier(e.target.value)}
            >
              <MenuItem value="ALL">{t("dashboard.radar.filterTierAll")}</MenuItem>
              <MenuItem value="MEGA_LARGE">{t("dashboard.radar.tiers.MEGA_LARGE")}</MenuItem>
              <MenuItem value="MID">{t("dashboard.radar.tiers.MID")}</MenuItem>
              <MenuItem value="SMALL">{t("dashboard.radar.tiers.SMALL")}</MenuItem>
            </Select>
          </FormControl>
          <TextField
            size="small"
            type="number"
            label={t("dashboard.radar.filterMaxDist")}
            value={radarMaxDistPct}
            onChange={(e) => setRadarMaxDistPct(e.target.value)}
            sx={{ width: 155 }}
          />
          {radarTab === "VALUE" && (
            <TextField
              size="small"
              type="number"
              label={t("dashboard.radar.filterMinRr")}
              value={radarMinRr}
              onChange={(e) => setRadarMinRr(e.target.value)}
              sx={{ width: 140 }}
            />
          )}
        </Stack>

        {filteredRadar.length === 0 ? (
          <Typography sx={{ py: 2, fontSize: 13, color: "text.secondary", textAlign: "center" }}>
            {radarTab === "VALUE" ? t("dashboard.radar.emptyValue") : t("dashboard.radar.emptyStarter")}
          </Typography>
        ) : (
          <Box sx={{ overflowX: "auto" }}>
            <Table size="small" sx={{ minWidth: 1120 }}>
              <TableHead>
                <TableRow sx={{ bgcolor: "action.hover" }}>
                  <TableCell>{t("dashboard.radar.cols.symbol")}</TableCell>
                  <TableCell>{t("dashboard.radar.cols.industry")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.close")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.refBand")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.anchorZone")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.discountOrPeg")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.stop")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.targetRr")}</TableCell>
                  <TableCell align="right">{t("dashboard.radar.cols.earningsDays")}</TableCell>
                  <TableCell>{t("dashboard.radar.cols.stance")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredRadar.map((row) => (
                  <TableRow key={`${row.trackType}-${row.figi}`} hover>
                    <TableCell>
                      <Link
                        component="button"
                        type="button"
                        underline="hover"
                        onClick={() => navigate(`/stockAnalysis/${encodeURIComponent(row.figi)}`)}
                        sx={{ fontWeight: 800, fontSize: 14, textAlign: "left" }}
                      >
                        {row.symbol}
                      </Link>
                      {row.stockName && (
                        <Typography variant="caption" display="block" color="text.secondary" noWrap sx={{ maxWidth: 160 }}>
                          {row.stockName}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Typography variant="body2">{translateIndustry(row.industryName, row.industryCode)}</Typography>
                        {row.sectorFlowState && (
                          <Chip
                            size="small"
                            color={flowStateColor(row.sectorFlowState)}
                            variant="outlined"
                            label={t(`dashboard.sectorFlow.flowStates.${row.sectorFlowState}`, { defaultValue: row.sectorFlowState })}
                            sx={{ height: 20, fontSize: 11 }}
                          />
                        )}
                      </Stack>
                    </TableCell>
                    <TableCell align="right" sx={{ fontWeight: 700 }}>
                      {formatDec(row.closePrice, 2)}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                      {row.fairValueLow != null && row.fairValueMid != null && row.fairValueHigh != null
                        ? `${formatDec(row.fairValueLow, 2)} / ${formatDec(row.fairValueMid, 2)} / ${formatDec(row.fairValueHigh, 2)}`
                        : "—"}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                      {row.trackType === "TREND_PULLBACK_STARTER" && row.pullbackAnchor ? (
                        <Box>
                          <Typography variant="body2" fontWeight={700}>
                            {t(`analysisReport.executiveSummary.starterAnchors.${row.pullbackAnchor}`, { defaultValue: row.pullbackAnchor })} {formatDec(row.pullbackAnchorPrice, 2)}
                          </Typography>
                          {row.supportDistancePct != null && (
                            <Typography variant="caption" color="text.secondary">
                              {formatSignedPct(row.supportDistancePct, 2)}
                            </Typography>
                          )}
                        </Box>
                      ) : row.supportLower != null && row.supportUpper != null ? (
                        <Box>
                          <Typography variant="body2" fontWeight={700}>
                            {formatDec(row.supportLower, 2)}–{formatDec(row.supportUpper, 2)}
                          </Typography>
                          {row.supportDistancePct != null && (
                            <Typography variant="caption" color="text.secondary">
                              {formatSignedPct(row.supportDistancePct, 2)}
                            </Typography>
                          )}
                        </Box>
                      ) : "—"}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                      {row.trackType === "TREND_PULLBACK_STARTER" ? (
                        row.forwardGrowthBasis === "HYPER_GROWTH_FORWARD_PE" && row.forwardPe != null ? (
                          <Chip
                            size="small"
                            color="secondary"
                            variant="outlined"
                            label={t("dashboard.radar.hyperGrowthTag", { pe: formatDec(row.forwardPe, 1) })}
                          />
                        ) : row.forwardPeg != null ? (
                          <Chip
                            size="small"
                            color="primary"
                            variant="outlined"
                            label={t("dashboard.radar.pegTag", { peg: formatDec(row.forwardPeg, 2) })}
                          />
                        ) : "—"
                      ) : (
                        <Typography variant="body2" fontWeight={700} color={signedColor(row.discountToMid)}>
                          {formatSignedPct(row.discountToMid, 1)}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell align="right" sx={{ fontWeight: 600 }}>
                      {formatDec(row.protectiveStop, 2)}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                      {row.firstTargetPrice != null || row.netRiskReward != null ? (
                        <Box>
                          <Typography variant="body2">{formatDec(row.firstTargetPrice, 2)}</Typography>
                          {row.netRiskReward != null && (
                            <Typography variant="caption" color="primary.main" fontWeight={700}>
                              R/R {formatDec(row.netRiskReward, 2)}x
                            </Typography>
                          )}
                        </Box>
                      ) : row.positionFraction ? (
                        <Chip size="small" label={row.positionFraction} variant="outlined" />
                      ) : "—"}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                      {row.daysToNextEarnings != null
                        ? t("dashboard.radar.daysUnit", { days: row.daysToNextEarnings })
                        : t("dashboard.radar.noEarningsDate")}
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Chip
                          size="small"
                          color={row.headwindWatchOnly ? "warning" : row.stance === "ACCUMULATE_AT_SUPPORT" || row.stance === "TREND_PULLBACK_STARTER" ? "success" : "default"}
                          label={t(`analysisReport.executiveSummary.stances.${row.stance}`, { defaultValue: row.stance })}
                          sx={{ maxWidth: 220 }}
                        />
                        {row.headwindWatchOnly && (
                          <Chip size="small" color="error" variant="outlined" label={t("dashboard.radar.headwindWatchTag")} />
                        )}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Paper>

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, lg: 9 }}>
          <Stack spacing={2}>
            {/* Batch 03 核心二：11 大板塊代表 ETF 對照 */}
            <Paper
              component="section"
              aria-label={t("dashboard.sectorEtfs.title")}
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} sx={{ mb: 1.5 }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <AccountBalance fontSize="small" color="primary" />
                  <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>
                    {t("dashboard.sectorEtfs.title")}
                  </Typography>
                </Stack>
                <Typography sx={{ fontSize: 12, color: "text.secondary" }}>
                  {t("dashboard.sectorEtfs.subtitle")}
                </Typography>
              </Stack>
              {sectorEtfs.length === 0 ? (
                <Typography sx={{ py: 2, fontSize: 13, color: "text.secondary", textAlign: "center" }}>
                  {t("dashboard.sectorEtfs.empty")}
                </Typography>
              ) : (
                <Box sx={{ overflowX: "auto" }}>
                  <Table size="small" sx={{ minWidth: 820 }}>
                    <TableHead>
                      <TableRow sx={{ bgcolor: "action.hover" }}>
                        <TableCell>{t("dashboard.sectorEtfs.symbol")}</TableCell>
                        <TableCell>{t("dashboard.sectorEtfs.sector")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.close")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.ret5d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.ret20d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.ret60d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.rvol20")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.excess5d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.excess20d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorEtfs.excess60d")}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {sectorEtfs.map((row) => (
                        <TableRow key={row.symbol} hover>
                          <TableCell sx={{ fontWeight: 800 }}>{row.symbol}</TableCell>
                          <TableCell>
                            {t(`dashboard.sectorEtfs.sectors.${row.sectorCode}`, { defaultValue: row.sectorCode })}
                          </TableCell>
                          <TableCell align="right">{formatDec(row.closePrice, 2)}</TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.return5d), fontWeight: 600 }}>
                            {formatSignedPct(row.return5d, 2)}
                          </TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.return20d), fontWeight: 600 }}>
                            {formatSignedPct(row.return20d, 2)}
                          </TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.return60d), fontWeight: 600 }}>
                            {formatSignedPct(row.return60d, 2)}
                          </TableCell>
                          <TableCell align="right">{row.rvol20 != null ? `${formatDec(row.rvol20, 2)}x` : "—"}</TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.excessVsSpy5d) }}>
                            {formatSignedPct(row.excessVsSpy5d, 2)}
                          </TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.excessVsSpy20d), fontWeight: 700 }}>
                            {formatSignedPct(row.excessVsSpy20d, 2)}
                          </TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.excessVsSpy60d) }}>
                            {formatSignedPct(row.excessVsSpy60d, 2)}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Box>
              )}
            </Paper>

            {/* Batch 03 核心三：板塊量價資金流與內部均線廣度 */}
            <Paper
              component="section"
              aria-label={t("dashboard.sectorFlow.title")}
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={1} sx={{ mb: 1.5 }}>
                <Box>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <BarChart fontSize="small" color="primary" />
                    <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>
                      {t("dashboard.sectorFlow.title")}
                    </Typography>
                    {sectorFlow?.tradeDate && <Chip label={sectorFlow.tradeDate} size="small" variant="outlined" />}
                  </Stack>
                  <Typography sx={{ fontSize: 12, color: "text.secondary", mt: 0.4 }}>
                    {t("dashboard.sectorFlow.subtitle")}
                    {sectorFlow && sectorFlow.marketTotalStockCount > 0
                      ? ` · ${t("dashboard.sectorFlow.coverage", {
                          total: sectorFlow.marketTotalStockCount,
                          coverage: formatPlainPct(sectorFlow.classifiedCoveragePct, 1),
                        })}`
                      : ""}
                  </Typography>
                </Box>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <ToggleButtonGroup
                    size="small"
                    exclusive
                    value={flowOrder}
                    onChange={(_e, val) => {
                      if (val === "TOP" || val === "BOTTOM") setFlowOrder(val);
                    }}
                  >
                    <ToggleButton value="TOP">
                      {flowSort === "dollarVol1d"
                        ? t("dashboard.sectorFlow.orderTopDollarVol")
                        : t("dashboard.sectorFlow.orderTop")}
                    </ToggleButton>
                    <ToggleButton value="BOTTOM">
                      {flowSort === "dollarVol1d"
                        ? t("dashboard.sectorFlow.orderBottomDollarVol")
                        : t("dashboard.sectorFlow.orderBottom")}
                    </ToggleButton>
                  </ToggleButtonGroup>
                  <FormControl size="small" sx={{ minWidth: 190 }}>
                    <InputLabel>{t("dashboard.sectorFlow.sortBy")}</InputLabel>
                    <Select
                      label={t("dashboard.sectorFlow.sortBy")}
                      value={flowSort}
                      onChange={(e) => setFlowSort(e.target.value as typeof flowSort)}
                    >
                      <MenuItem value="cmf20d">{t("dashboard.sectorFlow.sortCmf20d")}</MenuItem>
                      <MenuItem value="shareDelta">{t("dashboard.sectorFlow.sortShareDelta")}</MenuItem>
                      <MenuItem value="breadth50">{t("dashboard.sectorFlow.sortBreadth50")}</MenuItem>
                      <MenuItem value="dollarVol1d">{t("dashboard.sectorFlow.sortDollarVol1d")}</MenuItem>
                    </Select>
                  </FormControl>
                </Stack>
              </Stack>

              {sortedSectorFlows.length === 0 ? (
                <Typography sx={{ py: 2, fontSize: 13, color: "text.secondary", textAlign: "center" }}>
                  {t("dashboard.sectorFlow.empty")}
                </Typography>
              ) : (
                <Box sx={{ overflowX: "auto" }}>
                  <Table size="small" sx={{ minWidth: 920 }}>
                    <TableHead>
                      <TableRow sx={{ bgcolor: "action.hover" }}>
                        <TableCell>{t("dashboard.sectorFlow.industry")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.stockCount")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.cmf5d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.cmf20d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.share1d")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.shareDelta")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.breadth50")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.breadth200")}</TableCell>
                        <TableCell align="right">{t("dashboard.sectorFlow.ret20dMedian")}</TableCell>
                        <TableCell>{t("dashboard.sectorFlow.flowState")}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {sortedSectorFlows.map((row) => (
                        <TableRow key={row.industryCode} hover>
                          <TableCell>
                            <Typography variant="body2" fontWeight={700}>{translateIndustry(row.industryName, row.industryCode)}</Typography>
                            <Typography variant="caption" color="text.secondary">
                              {formatCompactUsd(row.dollarVolume1d)}
                            </Typography>
                          </TableCell>
                          <TableCell align="right">{row.stockCount}</TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.cmf5d), fontWeight: 600 }}>
                            {formatDec(row.cmf5d, 3)}
                          </TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.cmf20d), fontWeight: 700 }}>
                            {formatDec(row.cmf20d, 3)}
                          </TableCell>
                          <TableCell align="right">{formatPlainPct(row.marketVolumeShare1d, 2)}</TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.volumeShareDelta5dVs20d), fontWeight: 600 }}>
                            {formatSignedPct(row.volumeShareDelta5dVs20d, 2)}
                          </TableCell>
                          <TableCell align="right">
                            {formatPlainPct(row.breadthAboveSma50Pct, 1)}
                            {row.breadthSma50Total > 0 && (
                              <Typography variant="caption" display="block" color="text.secondary">
                                {row.breadthAboveSma50Count}/{row.breadthSma50Total}
                              </Typography>
                            )}
                          </TableCell>
                          <TableCell align="right">
                            {formatPlainPct(row.breadthAboveSma200Pct, 1)}
                            {row.breadthSma200Total > 0 && (
                              <Typography variant="caption" display="block" color="text.secondary">
                                {row.breadthAboveSma200Count}/{row.breadthSma200Total}
                              </Typography>
                            )}
                          </TableCell>
                          <TableCell align="right" sx={{ color: signedColor(row.return20dMedian), fontWeight: 600 }}>
                            {formatSignedPct(row.return20dMedian, 2)}
                          </TableCell>
                          <TableCell>
                            {row.flowState ? (
                              <Chip
                                size="small"
                                color={flowStateColor(row.flowState)}
                                variant="outlined"
                                label={t(`dashboard.sectorFlow.flowStates.${row.flowState}`, { defaultValue: row.flowState })}
                              />
                            ) : "—"}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Box>
              )}
            </Paper>

            {/* Batch 03 核心四：訊號事後追蹤與勝率驗證 */}
            <Paper
              component="section"
              aria-label={t("dashboard.signalStats.title")}
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                <Analytics fontSize="small" color="primary" />
                <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>
                  {t("dashboard.signalStats.title")}
                </Typography>
              </Stack>
              <Typography sx={{ fontSize: 12, color: "text.secondary", mb: 1.5 }}>
                {t("dashboard.signalStats.subtitle")}
              </Typography>

              {!signalStats || signalStats.totalSignals === 0 ? (
                <Typography sx={{ py: 2, fontSize: 13, color: "text.secondary", textAlign: "center" }}>
                  {t("dashboard.signalStats.empty")}
                </Typography>
              ) : (
                <>
                  <Grid container spacing={1.5} sx={{ mb: 1.5 }}>
                    <Grid size={{ xs: 6, sm: 3 }}>
                      <Box sx={{ p: 1.2, borderRadius: 1, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                        <Typography variant="caption" color="text.secondary">{t("dashboard.signalStats.totalSignals")}</Typography>
                        <Typography variant="h6" fontWeight={800}>
                          {signalStats.totalSignals} ({t("dashboard.signalStats.pendingCount")} {signalStats.pendingCount})
                        </Typography>
                      </Box>
                    </Grid>
                    <Grid size={{ xs: 6, sm: 3 }}>
                      <Box sx={{ p: 1.2, borderRadius: 1, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                        <Typography variant="caption" color="text.secondary">{t("dashboard.signalStats.winRate20d")}</Typography>
                        <Typography variant="h6" fontWeight={800} color="primary.main">
                          {formatPlainPct(signalStats.overallWinRate20d, 1)}
                        </Typography>
                      </Box>
                    </Grid>
                    <Grid size={{ xs: 6, sm: 3 }}>
                      <Box sx={{ p: 1.2, borderRadius: 1, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                        <Typography variant="caption" color="text.secondary">{t("dashboard.signalStats.avgReturn20d")}</Typography>
                        <Typography variant="h6" fontWeight={800} color={signedColor(signalStats.overallAvgReturn20d)}>
                          {formatSignedPct(signalStats.overallAvgReturn20d, 2)}
                        </Typography>
                      </Box>
                    </Grid>
                    <Grid size={{ xs: 6, sm: 3 }}>
                      <Box sx={{ p: 1.2, borderRadius: 1, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                        <Typography variant="caption" color="text.secondary">{t("dashboard.signalStats.targetHitRate")}</Typography>
                        <Typography variant="h6" fontWeight={800}>
                          {formatPlainPct(signalStats.overallTargetHitRate, 1)}
                        </Typography>
                      </Box>
                    </Grid>
                  </Grid>

                  <Tabs
                    value={statsTab}
                    onChange={(_e, val) => setStatsTab(val)}
                    sx={{ borderBottom: 1, borderColor: "divider", mb: 1.2 }}
                  >
                    <Tab value="TYPE" label={t("dashboard.signalStats.tabByType")} />
                    <Tab value="REGIME" label={t("dashboard.signalStats.tabByRegime")} />
                    <Tab value="INDUSTRY" label={t("dashboard.signalStats.tabByIndustry")} />
                    <Tab value="TIER" label={t("dashboard.signalStats.tabByTier")} />
                  </Tabs>

                  <Box sx={{ overflowX: "auto" }}>
                    <Table size="small" sx={{ minWidth: 820 }}>
                      <TableHead>
                        <TableRow sx={{ bgcolor: "action.hover" }}>
                          <TableCell>{t("dashboard.signalStats.cols.group")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.total")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.settled20d")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.winRate20d")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.avg5d")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.avg20d")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.avg60d")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.targetHitRate")}</TableCell>
                          <TableCell align="right">{t("dashboard.signalStats.cols.mfeMae")}</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {activeStatsGroups.map((row) => (
                          <TableRow key={row.groupKey} hover>
                            <TableCell sx={{ fontWeight: 700 }}>{formatStatsGroupLabel(row)}</TableCell>
                            <TableCell align="right">{row.totalSignals}</TableCell>
                            <TableCell align="right">{row.settled20dCount}</TableCell>
                            <TableCell align="right" sx={{ fontWeight: 700 }}>{formatPlainPct(row.winRate20d, 1)}</TableCell>
                            <TableCell align="right" sx={{ color: signedColor(row.avgReturn5d) }}>
                              {formatSignedPct(row.avgReturn5d, 2)}
                            </TableCell>
                            <TableCell align="right" sx={{ color: signedColor(row.avgReturn20d), fontWeight: 700 }}>
                              {formatSignedPct(row.avgReturn20d, 2)}
                            </TableCell>
                            <TableCell align="right" sx={{ color: signedColor(row.avgReturn60d) }}>
                              {formatSignedPct(row.avgReturn60d, 2)}
                            </TableCell>
                            <TableCell align="right">
                              {row.resolvedOutcomeCount > 0
                                ? `${formatPlainPct(row.targetHitRate, 1)} (${row.targetHitFirstCount}/${row.resolvedOutcomeCount})`
                                : "—"}
                            </TableCell>
                            <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                              {row.avgMfe60d != null || row.avgMae60d != null
                                ? `${formatSignedPct(row.avgMfe60d, 1)} / ${formatSignedPct(row.avgMae60d, 1)}`
                                : "—"}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </Box>
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
                    {t("dashboard.signalStats.disclaimer")}
                  </Typography>
                </>
              )}
            </Paper>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Box
                sx={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "flex-start",
                  gap: 2,
                  flexWrap: "wrap",
                }}
              >
                <Box>
                  <Typography
                    sx={{
                      fontSize: 12,
                      fontWeight: 700,
                      color: "text.secondary",
                      textTransform: "uppercase",
                      letterSpacing: 0.6,
                    }}
                  >
                    {t("dashboard.section.marketStatus")}
                  </Typography>
                  <Typography sx={{ fontSize: 30, lineHeight: 1.2, fontWeight: 800, color: "text.primary", mt: 0.5 }}>
                    {t(`dashboard.marketSummary.${marketSummary.titleCode}`)}
                  </Typography>
                  <Typography sx={{ fontSize: 14, color: "text.secondary", mt: 1, maxWidth: 780 }}>
                    {translateMessage(marketSummary.description)}
                  </Typography>
                </Box>
                <Chip label={translateMessage(marketSummary.tag)} size="small" variant="outlined" />
              </Box>
            </Paper>

            <Grid container spacing={2}>
              <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
                <MetricCard
                  title={t("dashboard.signal.FEAR_GREED")}
                  value={fearGreedSignal?.value ?? "--"}
                  status={fearGreedSignal ? t(`dashboard.sentiment.${fearGreedSignal.statusCode}`) : "--"}
                  trend="up"
                  change=""
                  subtitle={`${t("dashboard.sourceLabel")}：${fearGreedSignal ? t(`dashboard.source.${fearGreedSignal.sourceCode}`) : "--"}`}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
                <MetricCard
                  title={t("dashboard.signal.CNN_VIX_SCORE")}
                  value={cnnVixScore?.value ?? "--"}
                  status={cnnVixScore ? t(`dashboard.sentiment.${cnnVixScore.statusCode}`) : "--"}
                  trend="down"
                  change=""
                  subtitle={`${t("dashboard.sourceLabel")}：${cnnVixScore ? t(`dashboard.source.${cnnVixScore.sourceCode}`) : "--"}`}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
                <MetricCard
                  title={t("dashboard.signal.CNN_PUT_CALL_SCORE")}
                  value={cnnPutCallScore?.value ?? "--"}
                  status={cnnPutCallScore ? t(`dashboard.sentiment.${cnnPutCallScore.statusCode}`) : "--"}
                  trend="neutral"
                  change=""
                  subtitle={`${t("dashboard.sourceLabel")}：${cnnPutCallScore ? t(`dashboard.source.${cnnPutCallScore.sourceCode}`) : "--"}`}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
                <MetricCard
                  title={t("dashboard.signal.CNN_MARKET_BREADTH_SCORE")}
                  value={cnnMarketBreadthScore?.value ?? "--"}
                  status={cnnMarketBreadthScore ? t(`dashboard.sentiment.${cnnMarketBreadthScore.statusCode}`) : "--"}
                  trend="up"
                  change=""
                  subtitle={`${t("dashboard.sourceLabel")}：${cnnMarketBreadthScore ? t(`dashboard.source.${cnnMarketBreadthScore.sourceCode}`) : "--"}`}
                />
              </Grid>
            </Grid>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
                <ShowChart fontSize="small" />
                <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>{t("dashboard.section.indexObservation")}</Typography>
              </Box>
              <Grid container spacing={1.2}>
                {indexRows.map((item) => (
                  <Grid key={item.symbol} size={{ xs: 12, sm: 6 }}>
                    <Box sx={{ p: 1.5, borderRadius: 0.75, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                      <Stack spacing={0.6}>
                        <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1 }}>
                          <Typography sx={{ fontSize: 13, color: "text.secondary" }}>
                            {t(`dashboard.index.${item.nameCode}`)}（{item.symbol}）
                          </Typography>
                          <Typography sx={{ fontSize: 13, fontWeight: 700, color: getTrendColor(item.trend) }}>
                            {item.day}
                          </Typography>
                        </Box>
                        <Typography sx={{ fontSize: 22, fontWeight: 800, color: "text.primary" }}>{item.value}</Typography>
                        <Typography sx={{ fontSize: 12, color: "text.secondary" }}>{t(`dashboard.indexNote.${item.noteCode}`)}</Typography>
                      </Stack>
                    </Box>
                  </Grid>
                ))}
              </Grid>
            </Paper>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
                <InfoOutlined fontSize="small" />
                <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>{t("dashboard.section.fearGreedFactors")}</Typography>
              </Box>
              <Grid container spacing={1.2}>
                {fearGreedComponents.map((item) => (
                  <Grid key={item.code} size={{ xs: 12, sm: 6, md: 4 }}>
                    <Box
                      sx={{
                        p: 1.5,
                        borderRadius: 0.75,
                        bgcolor: "background.default",
                        border: 1,
                        borderColor: "divider",
                        height: "100%",
                      }}
                    >
                      <Typography sx={{ fontSize: 13, fontWeight: 700, color: "text.primary", mb: 0.6 }}>
                        {t(`dashboard.factor.${item.code}`)}
                      </Typography>
                      <Typography sx={{ fontSize: 12, fontWeight: 700, color: "text.secondary", mb: 0.6 }}>
                        {t("dashboard.currentAssessment")}：{t(`dashboard.sentiment.${item.statusCode}`)}
                      </Typography>
                      <Typography sx={{ fontSize: 12, lineHeight: 1.55, color: "text.secondary" }}>
                        {t("dashboard.indexValue", { value: item.value ?? "--" })}
                      </Typography>
                    </Box>
                  </Grid>
                ))}
              </Grid>
            </Paper>

            <Paper
              elevation={0}
              sx={{ borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider", overflow: "hidden" }}
            >
              <Box
                sx={{
                  px: 2,
                  py: 1.5,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "space-between",
                  borderBottom: 1,
                  borderColor: "divider",
                }}
              >
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  <ShowChart fontSize="small" />
                  <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>{t("dashboard.section.etfObservation")}</Typography>
                </Box>
                <Typography sx={{ fontSize: 12, color: "text.secondary" }}>{t("dashboard.etfSubtitle")}</Typography>
              </Box>

              <Box sx={{ overflowX: "auto" }}>
                <Box sx={{ minWidth: 1360 }}>
                  <Box
                    sx={{
                      display: "grid",
                      gridTemplateColumns: "90px 100px 100px 80px 140px 120px 120px 180px 110px 100px",
                      px: 2,
                      py: 1.2,
                      bgcolor: "action.hover",
                      color: "text.secondary",
                      fontSize: 12,
                      fontWeight: 700,
                      textTransform: "uppercase",
                      letterSpacing: 0.4,
                    }}
                  >
                    <Box>{t("dashboard.table.symbol")}</Box>
                    <Box>{t("dashboard.table.price")}</Box>
                    <Box>{t("dashboard.table.dailyChange")}</Box>
                    <Box>RSI</Box>
                    <Box>{t("dashboard.table.assessment")}</Box>
                    <Box>{t("dashboard.table.ma20Distance")}</Box>
                    <Box>{t("dashboard.table.volumeRatio5d")}</Box>
                    <Box>{t("dashboard.table.note")}</Box>
                    <Box>{t("dashboard.table.source")}</Box>
                    <Box>{t("dashboard.table.time")}</Box>
                  </Box>
                  <Divider />
                  {etfRows.map((row) => (
                    <React.Fragment key={row.ticker}>
                      <Box
                        sx={{
                          display: "grid",
                          gridTemplateColumns: "90px 100px 100px 80px 140px 120px 120px 180px 110px 100px",
                          px: 2,
                          py: 1.5,
                          alignItems: "center",
                          fontSize: 14,
                        }}
                      >
                        <Box sx={{ fontWeight: 800, color: "text.primary" }}>{row.ticker}</Box>
                        <Box sx={{ color: "text.primary", fontWeight: 600 }}>{row.price}</Box>
                        <Box
                          sx={{
                            display: "flex",
                            alignItems: "center",
                            gap: 0.5,
                            color: row.day.startsWith("-") ? "error.main" : "success.main",
                            fontWeight: 700,
                          }}
                        >
                          {row.day.startsWith("-") ? <South sx={{ fontSize: 15 }} /> : <North sx={{ fontSize: 15 }} />}
                          {row.day}
                        </Box>
                        <Box sx={{ color: "text.primary", fontWeight: 600 }}>{row.rsi}</Box>
                        <Box>
                          <Chip label={t(`dashboard.bias.${row.biasCode}`)} size="small" variant="outlined" />
                        </Box>
                        <Box
                          sx={{
                            display: "flex",
                            alignItems: "center",
                            gap: 0.5,
                            color: getDeviationColor(row.ma20Deviation),
                            fontWeight: 700,
                          }}
                        >
                          <Timeline sx={{ fontSize: 15 }} />
                          {row.ma20Deviation}
                        </Box>
                        <Box
                          sx={{
                            display: "flex",
                            alignItems: "center",
                            gap: 0.5,
                            color: getVolumeRatioColor(row.volumeRatio5d),
                            fontWeight: 700,
                          }}
                        >
                          <BarChart sx={{ fontSize: 15 }} />
                          {row.volumeRatio5d}
                        </Box>
                        <Box sx={{ color: "text.secondary" }}>{t(`dashboard.etfNote.${row.noteCode}`)}</Box>
                        <Box sx={{ color: "text.secondary" }}>{t(`dashboard.source.${row.sourceCode}`)}</Box>
                        <Box sx={{ color: "text.secondary" }}>{row.time}</Box>
                      </Box>
                      <Divider />
                    </React.Fragment>
                  ))}
                </Box>
              </Box>
            </Paper>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary", mb: 1.5 }}>
                {t("dashboard.section.priceVolumeRules")}
              </Typography>
              <Grid container spacing={1.2}>
                <Grid size={{ xs: 12, md: 4 }}>
                  <Box sx={{ p: 1.5, borderRadius: 0.75, bgcolor: "background.default", border: 1, borderColor: "divider", height: "100%" }}>
                    <Typography sx={{ fontSize: 13, fontWeight: 700, color: "text.primary", mb: 0.8 }}>{t("dashboard.rules.maTitle")}</Typography>
                    <Typography sx={{ fontSize: 12, lineHeight: 1.55, color: "text.secondary" }}>
                      {t("dashboard.rules.maDescription")}
                    </Typography>
                  </Box>
                </Grid>
                <Grid size={{ xs: 12, md: 4 }}>
                  <Box sx={{ p: 1.5, borderRadius: 0.75, bgcolor: "background.default", border: 1, borderColor: "divider", height: "100%" }}>
                    <Typography sx={{ fontSize: 13, fontWeight: 700, color: "text.primary", mb: 0.8 }}>{t("dashboard.rules.volumeTitle")}</Typography>
                    <Typography sx={{ fontSize: 12, lineHeight: 1.55, color: "text.secondary" }}>
                      {t("dashboard.rules.volumeDescription")}
                    </Typography>
                  </Box>
                </Grid>
                <Grid size={{ xs: 12, md: 4 }}>
                  <Box sx={{ p: 1.5, borderRadius: 0.75, bgcolor: "background.default", border: 1, borderColor: "divider", height: "100%" }}>
                    <Typography sx={{ fontSize: 13, fontWeight: 700, color: "text.primary", mb: 0.8 }}>{t("dashboard.rules.combinationTitle")}</Typography>
                    <Typography sx={{ fontSize: 12, lineHeight: 1.55, color: "text.secondary" }}>
                      {t("dashboard.rules.combinationDescription")}
                    </Typography>
                  </Box>
                </Grid>
              </Grid>
            </Paper>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary", mb: 1.5 }}>{t("dashboard.section.signalMatrix")}</Typography>
              <Grid container spacing={1.2}>
                {signalRows.map((item) => (
                  <Grid key={item.code} size={{ xs: 12, sm: 6, md: 4 }}>
                    <Box sx={{ p: 1.5, borderRadius: 0.75, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                      <Typography sx={{ fontSize: 12, color: "text.secondary", mb: 0.4 }}>{t(`dashboard.signal.${item.code}`)}</Typography>
                      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
                        <Typography sx={{ fontSize: 20, fontWeight: 800, color: "text.primary" }}>{item.value}</Typography>
                        <Chip label={t(`dashboard.sentiment.${item.statusCode}`)} size="small" variant="outlined" />
                      </Box>
                      <Typography sx={{ fontSize: 11, color: "text.secondary", mt: 0.8 }}>
                        {t("dashboard.sourceLabel")}：{t(`dashboard.source.${item.sourceCode}`)}
                      </Typography>
                    </Box>
                  </Grid>
                ))}
              </Grid>
            </Paper>
          </Stack>
        </Grid>

        <Grid size={{ xs: 12, lg: 3 }}>
          <Stack spacing={2}>
            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
                <WarningAmber fontSize="small" />
                <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>{t("dashboard.section.riskAlerts")}</Typography>
              </Box>
              <Stack spacing={1.2}>
                {alerts.map((item, index) => (
                  <Box key={index} sx={{ p: 1.25, borderRadius: 0.75, bgcolor: "background.default", border: 1, borderColor: "divider" }}>
                    <Typography sx={{ fontSize: 13, lineHeight: 1.45, color: "text.primary" }}>{translateMessage(item)}</Typography>
                  </Box>
                ))}
              </Stack>
            </Paper>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 1.5 }}>
                <EventNote fontSize="small" />
                <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary" }}>{t("dashboard.section.upcoming")}</Typography>
              </Box>
              <Stack spacing={1.2}>
                {events.map((item) => (
                  <Box
                    key={item.id}
                    sx={{
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "flex-start",
                      gap: 1,
                      p: 1.2,
                      borderRadius: 0.75,
                      bgcolor: "background.default",
                      border: 1,
                      borderColor: "divider",
                    }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Box sx={{ display: "flex", alignItems: "center", gap: 0.75, flexWrap: "wrap" }}>
                        <Chip
                          label={t(`dashboard.eventCategory.${item.categoryCode}`, { defaultValue: item.categoryCode })}
                          size="small"
                          sx={{ height: 20, fontSize: 10 }}
                        />
                        <Typography sx={{ fontSize: 13, fontWeight: 700, color: "text.primary" }}>
                          {item.titleCode
                            ? t(`dashboard.event.${item.titleCode}`, {
                                defaultValue: item.title ?? item.titleCode,
                                symbol: item.symbol,
                              })
                            : item.title}
                        </Typography>
                      </Box>
                      <Typography sx={{ fontSize: 12, color: "text.secondary", mt: 0.5 }}>
                        {item.allDay && /^\d{4}-\d{2}-\d{2}$/.test(item.startsAt) ? item.startsAt : new Intl.DateTimeFormat(i18n.resolvedLanguage ?? i18n.language, {
                          dateStyle: "medium",
                          timeStyle: item.allDay ? undefined : "short",
                          timeZone: "Asia/Hong_Kong",
                        }).format(new Date(item.startsAt))}
                        {!item.allDay && ` · ${t("dashboard.hongKongTime")}`}
                      </Typography>
                      {item.source && (
                        <Typography sx={{ fontSize: 11, color: "text.secondary", mt: 0.4 }}>
                          {t("dashboard.sourceLabel")}：{item.sourceUrl ? (
                            <Link href={item.sourceUrl} target="_blank" rel="noreferrer" underline="hover">
                              {item.source}
                            </Link>
                          ) : item.source}
                        </Typography>
                      )}
                    </Box>
                    <Chip label={t(`dashboard.eventLevel.${item.levelCode}`)} size="small" color={getEventColor(item.levelCode)} variant="outlined" />
                  </Box>
                ))}
                {events.length === 0 && (
                  <Typography sx={{ py: 2, fontSize: 12, color: "text.secondary", textAlign: "center" }}>
                    {t("dashboard.noUpcomingEvents")}
                  </Typography>
                )}
              </Stack>
            </Paper>

            <Paper
              elevation={0}
              sx={{ p: 2, borderRadius: 1, bgcolor: "background.paper", border: 1, borderColor: "divider" }}
            >
              <Typography sx={{ fontSize: 16, fontWeight: 700, color: "text.primary", mb: 1 }}>
                {t(`dashboard.execution.${executionBias.titleCode}`)}
              </Typography>
              <Typography sx={{ fontSize: 14, lineHeight: 1.6, color: "text.secondary" }}>
                {t(`dashboard.execution.${executionBias.descriptionCode}`)}
              </Typography>
            </Paper>
          </Stack>
        </Grid>
      </Grid>
    </Stack>
  );
};

export default HomeDashboard;
