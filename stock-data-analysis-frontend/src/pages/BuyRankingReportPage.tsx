import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Collapse,
  Divider,
  FormControl,
  FormControlLabel,
  FormGroup,
  FormLabel,
  IconButton,
  InputLabel,
  Link,
  MenuItem,
  Paper,
  Radio,
  RadioGroup,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from "@mui/material";
import { Download, KeyboardArrowDown, KeyboardArrowUp, PlayArrow, TableChart } from "@mui/icons-material";
import { apiClient } from "../services/apiClient";
import {
  buyRankingApi,
  parseBlobErrorMessage,
  triggerBrowserDownload,
} from "../services/buyRankingApi";
import {
  BuyRankingAssetType,
  BuyRankingExportFormat,
  BuyRankingFundamentalGrade,
  BuyRankingHorizon,
  BuyRankingItemDto,
  BuyRankingPortfolioType,
  BuyRankingRequest,
  BuyRankingResponse,
  BuyRankingScopeMode,
  BuyRankingSortBy,
  BuyRankingTier,
} from "../services/types/dto/buyRanking";

interface ClassificationOption {
  code: string;
  industry: string;
  sourcePlateCode?: string | null;
}

interface StockClassificationApiResponse {
  classifications?: ClassificationOption[];
}

const TIER_STYLES: Record<BuyRankingTier, { bg: string; color: string; border: string }> = {
  TIER_1: { bg: "#F0FDF4", color: "#166534", border: "#BBF7D0" },
  TIER_2: { bg: "#EFF6FF", color: "#1E40AF", border: "#BFDBFE" },
  TIER_3: { bg: "#FEFCE8", color: "#854D0E", border: "#FEF08A" },
  TIER_4: { bg: "#FEE2E2", color: "#991B1B", border: "#FECACA" },
};

const TIER_I18N_MAP: Record<BuyRankingTier, string> = {
  TIER_1: "reports.buyRanking.tiers.tier1",
  TIER_2: "reports.buyRanking.tiers.tier2",
  TIER_3: "reports.buyRanking.tiers.tier3",
  TIER_4: "reports.buyRanking.tiers.tier4",
};

const SORT_I18N_MAP: Record<BuyRankingSortBy, string> = {
  TIER_COMPOSITE: "reports.buyRanking.filters.sortTierComposite",
  NET_RR_DESC: "reports.buyRanking.filters.sortNetRrDesc",
  DISCOUNT_DESC: "reports.buyRanking.filters.sortDiscountDesc",
  DISTANCE_TO_BUY_ZONE_ASC: "reports.buyRanking.filters.sortDistanceAsc",
  ROIC_DESC: "reports.buyRanking.filters.sortRoicDesc",
  SYMBOL_ASC: "reports.buyRanking.filters.sortSymbolAsc",
};

const GRADE_I18N_MAP: Record<BuyRankingFundamentalGrade, string> = {
  ALL: "reports.buyRanking.filters.gradeAll",
  MIXED: "reports.buyRanking.filters.gradeMixed",
  STRONG: "reports.buyRanking.filters.gradeStrong",
};

const ASSET_I18N_MAP: Record<BuyRankingAssetType, string> = {
  COMMON_STOCK: "reports.buyRanking.filters.assetCommonStock",
  ADR: "reports.buyRanking.filters.assetAdr",
  ETF: "reports.buyRanking.filters.assetEtf",
  REIT: "reports.buyRanking.filters.assetReit",
};

const SCOPE_I18N_MAP: Record<BuyRankingScopeMode, string> = {
  SECTOR: "reports.buyRanking.filters.scopeSector",
  PORTFOLIO: "reports.buyRanking.filters.scopePortfolio",
  CUSTOM: "reports.buyRanking.filters.scopeCustom",
};

const PORTFOLIO_I18N_MAP: Record<BuyRankingPortfolioType, string> = {
  WATCHLIST: "reports.buyRanking.filters.portfolioWatchlist",
  HOLDING: "reports.buyRanking.filters.portfolioHolding",
};

const formatPrice = (val: number | null | undefined): string => {
  if (val == null || !Number.isFinite(val)) return "—";
  return `$${val.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

const formatPct = (ratio: number | null | undefined, signed = false): string => {
  if (ratio == null || !Number.isFinite(ratio)) return "—";
  const pct = ratio * 100;
  const prefix = signed && pct > 0 ? "+" : "";
  return `${prefix}${pct.toFixed(1)}%`;
};

const formatNum = (val: number | null | undefined, digits = 2): string => {
  if (val == null || !Number.isFinite(val)) return "—";
  return val.toFixed(digits);
};

export function BuyRankingReportPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const activeLocale = i18n.language || "zh-HK";

  // 從後端權威來源載入產業分類與報表可用選項
  const classificationsQuery = useQuery<ClassificationOption[]>({
    queryKey: ["stock-classifications-authoritative"],
    queryFn: async () => {
      const res = await apiClient.post<StockClassificationApiResponse>("stock/classification", {});
      return res.data?.classifications ?? [];
    },
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  });

  const optionsQuery = useQuery({
    queryKey: ["buy-ranking-options"],
    queryFn: buyRankingApi.getOptions,
    staleTime: 10 * 60 * 1000,
    refetchOnWindowFocus: false,
  });

  const industries = useMemo(() => classificationsQuery.data ?? [], [classificationsQuery.data]);
  const options = optionsQuery.data;

  // 控制面板表單狀態
  const [scopeMode, setScopeMode] = useState<BuyRankingScopeMode>("SECTOR");
  const [selectedIndustries, setSelectedIndustries] = useState<ClassificationOption[]>([]);
  const [portfolioTypes, setPortfolioTypes] = useState<BuyRankingPortfolioType[]>(["WATCHLIST", "HOLDING"]);
  const [customSymbolsText, setCustomSymbolsText] = useState<string>("");
  const [assetTypes, setAssetTypes] = useState<BuyRankingAssetType[]>(["COMMON_STOCK", "ADR"]);
  const [tiers, setTiers] = useState<BuyRankingTier[]>(["TIER_1", "TIER_2", "TIER_3", "TIER_4"]);
  const [minFundamentalGrade, setMinFundamentalGrade] = useState<BuyRankingFundamentalGrade>("ALL");
  const [minNetRr, setMinNetRr] = useState<string>("");
  const minNetRrInputRef = useRef<HTMLInputElement | null>(null);
  const [excludeEarnings14d, setExcludeEarnings14d] = useState<boolean>(false);
  const [horizon, setHorizon] = useState<BuyRankingHorizon>("POSITION");
  const [sortBy, setSortBy] = useState<BuyRankingSortBy>("TIER_COMPOSITE");

  // 已套用送出查詢的請求狀態（含 runVersion，確保 410 快照過期後以相同條件重查必重新請求並換發新 reportId）
  const [appliedRequest, setAppliedRequest] = useState<BuyRankingRequest | null>(null);
  const [runVersion, setRunVersion] = useState<number>(0);
  const [expandedFigis, setExpandedFigis] = useState<Record<string, boolean>>({});
  const [validationError, setValidationError] = useState<string | null>(null);
  const [exportError, setExportError] = useState<string | null>(null);
  const [exportingFormat, setExportingFormat] = useState<BuyRankingExportFormat | null>(null);
  const [page, setPage] = useState<number>(0);
  const [rowsPerPage, setRowsPerPage] = useState<number>(50);

  const reportQuery = useQuery<BuyRankingResponse>({
    queryKey: ["buy-ranking-report", appliedRequest, activeLocale, runVersion],
    queryFn: () => buyRankingApi.query(appliedRequest!, activeLocale),
    enabled: appliedRequest !== null && runVersion > 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  const items = useMemo(() => reportQuery.data?.items ?? [], [reportQuery.data]);

  useEffect(() => {
    const maxPage = Math.max(0, Math.ceil(items.length / rowsPerPage) - 1);
    if (page > maxPage) {
      setPage(maxPage);
    }
  }, [items.length, rowsPerPage, page]);

  const buildCurrentRequest = (): BuyRankingRequest | null => {
    if (assetTypes.length === 0) {
      setValidationError(t("reports.buyRanking.notices.selectAssetTypeFirst"));
      return null;
    }
    if (scopeMode === "SECTOR" && selectedIndustries.length === 0) {
      setValidationError(t("reports.buyRanking.notices.selectSectorFirst"));
      return null;
    }
    if (scopeMode === "CUSTOM" && !customSymbolsText.trim()) {
      setValidationError(t("reports.buyRanking.notices.enterSymbolsFirst"));
      return null;
    }
    const minRrInputEl = minNetRrInputRef.current;
    if (minRrInputEl && (minRrInputEl.validity?.badInput || !minRrInputEl.checkValidity())) {
      setValidationError(t("reports.buyRanking.notices.invalidMinNetRr"));
      return null;
    }
    const trimmedMinRr = minNetRr.trim();
    let parsedMinRr: number | null = null;
    if (trimmedMinRr !== "") {
      const numericMinRr = Number(trimmedMinRr);
      if (!Number.isFinite(numericMinRr) || numericMinRr < 0) {
        setValidationError(t("reports.buyRanking.notices.invalidMinNetRr"));
        return null;
      }
      parsedMinRr = numericMinRr;
    }
    setValidationError(null);
    const excludeReits = !assetTypes.includes("REIT");
    return {
      scopeMode,
      industryCodes: scopeMode === "SECTOR" ? selectedIndustries.map((i) => i.code) : [],
      portfolioTypes: scopeMode === "PORTFOLIO" ? portfolioTypes : [],
      customSymbols: scopeMode === "CUSTOM" ? customSymbolsText.trim() : [],
      assetTypes,
      excludeReits,
      tiers,
      minFundamentalGrade,
      minNetRiskReward: parsedMinRr,
      excludeEarningsWithinDays: excludeEarnings14d ? 14 : null,
      sortBy,
      horizon,
      page: 1,
      pageSize: options?.maxCandidates ?? 300,
    };
  };

  const handleRunQuery = () => {
    setExportError(null);
    const req = buildCurrentRequest();
    if (!req) return;
    setPage(0);
    setExpandedFigis({});
    setAppliedRequest(req);
    setRunVersion((v) => v + 1);
  };

  const handleToggleRowExpand = (figi: string) => {
    setExpandedFigis((prev) => ({ ...prev, [figi]: !prev[figi] }));
  };

  const handleToggleAsset = (type: BuyRankingAssetType) => {
    setAssetTypes((prev) => (prev.includes(type) ? prev.filter((x) => x !== type) : [...prev, type]));
  };

  const handleToggleTier = (tier: BuyRankingTier) => {
    setTiers((prev) => (prev.includes(tier) ? prev.filter((x) => x !== tier) : [...prev, tier]));
  };

  const handleTogglePortfolioType = (pt: BuyRankingPortfolioType) => {
    setPortfolioTypes((prev) => {
      if (prev.includes(pt)) {
        const next = prev.filter((x) => x !== pt);
        return next.length === 0 ? prev : next;
      }
      return [...prev, pt];
    });
  };

  const handleExport = async (format: BuyRankingExportFormat) => {
    setExportError(null);
    const data = reportQuery.data;
    if (!data || !appliedRequest) return;
    setExportingFormat(format);
    try {
      const ext = format === "XLSX" ? "xlsx" : "csv";
      const dateSlug = (data.asOfDate || "").replace(/-/g, "") || "report";
      const fallback = `USMarketTools_BuyRanking_${data.scopeSummary.scopeLabel || "Report"}_${dateSlug}.${ext}`;
      const downloaded = await buyRankingApi.exportReport(
        format,
        data.reportId,
        appliedRequest,
        activeLocale,
        fallback
      );
      triggerBrowserDownload(downloaded.blob, downloaded.fileName);
    } catch (error) {
      const msg = await parseBlobErrorMessage(error, "EXPORT_FAILED");
      if (msg === "REPORT_SNAPSHOT_EXPIRED") {
        setExportError(t("reports.buyRanking.notices.snapshotExpired"));
      } else {
        setExportError(t("reports.buyRanking.notices.exportFailed", { message: msg }));
      }
    } finally {
      setExportingFormat(null);
    }
  };

  const paginatedItems = useMemo(() => {
    const start = page * rowsPerPage;
    return items.slice(start, start + rowsPerPage);
  }, [items, page, rowsPerPage]);

  const queryErrorText = useMemo(() => {
    if (!reportQuery.error) return null;
    const err = reportQuery.error as { response?: { data?: { message?: string } }; message?: string };
    return err?.response?.data?.message || err?.message || "UNKNOWN_ERROR";
  }, [reportQuery.error]);

  const data = reportQuery.data;
  const availableScopeModes: BuyRankingScopeMode[] = options?.scopeModes ?? [];
  const availablePortfolioTypes: BuyRankingPortfolioType[] = options?.portfolioTypes ?? [];
  const availableAssets: BuyRankingAssetType[] = options?.assetTypes ?? [];
  const availableTiers: BuyRankingTier[] = options?.tiers ?? [];
  const availableGrades: BuyRankingFundamentalGrade[] = options?.fundamentalGrades ?? [];
  const availableSorts: BuyRankingSortBy[] = options?.sortOptions ?? [];
  const availableHorizons: BuyRankingHorizon[] = options?.horizons ?? [];
  const optionsReady = !!options;
  const canExport = !!data && !reportQuery.isFetching && exportingFormat === null;

  return (
    <Stack spacing={2.5}>
      <Box sx={{ display: "flex", flexWrap: "wrap", justifyContent: "space-between", alignItems: "flex-start", gap: 1.5 }}>
        <Box>
          <Typography variant="h5" sx={{ fontWeight: 800 }}>
            {t("reports.buyRanking.title")}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            {t("reports.buyRanking.subtitle")}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.25}>
          <Button
            variant="outlined"
            color="success"
            startIcon={exportingFormat === "XLSX" ? <CircularProgress size={16} /> : <TableChart />}
            disabled={!canExport}
            onClick={() => handleExport("XLSX")}
            data-testid="export-xlsx-btn"
          >
            {exportingFormat === "XLSX" ? t("reports.buyRanking.exporting") : t("reports.buyRanking.exportXlsx")}
          </Button>
          <Button
            variant="outlined"
            startIcon={exportingFormat === "CSV" ? <CircularProgress size={16} /> : <Download />}
            disabled={!canExport}
            onClick={() => handleExport("CSV")}
            data-testid="export-csv-btn"
          >
            {exportingFormat === "CSV" ? t("reports.buyRanking.exporting") : t("reports.buyRanking.exportCsv")}
          </Button>
        </Stack>
      </Box>

      {/* 頂部篩選控制面板（所有枚舉選項完全取自後端 /reports/buy-ranking/options） */}
      <Paper elevation={0} sx={{ p: 2.5, border: 1, borderColor: "divider", borderRadius: 2 }}>
        {optionsQuery.isError ? (
          <Alert
            severity="error"
            data-testid="options-error-alert"
            action={
              <Button color="inherit" size="small" onClick={() => optionsQuery.refetch()} data-testid="retry-options-btn">
                {t("reports.buyRanking.notices.retryOptions")}
              </Button>
            }
          >
            {t("reports.buyRanking.notices.optionsLoadFailed")}
          </Alert>
        ) : !optionsReady ? (
          <Box sx={{ py: 2, display: "flex", justifyContent: "center" }}>
            <CircularProgress size={24} />
          </Box>
        ) : (
        <Stack spacing={2}>
          <Stack direction={{ xs: "column", md: "row" }} spacing={2} alignItems={{ md: "center" }}>
            <FormControl component="fieldset">
              <FormLabel sx={{ fontSize: 12, fontWeight: 700 }}>{t("reports.buyRanking.filters.scopeMode")}</FormLabel>
              <RadioGroup
                row
                value={scopeMode}
                onChange={(e) => {
                  setScopeMode(e.target.value as BuyRankingScopeMode);
                  setValidationError(null);
                }}
              >
                {availableScopeModes.map((mode) => (
                  <FormControlLabel
                    key={mode}
                    value={mode}
                    control={<Radio size="small" />}
                    label={t(SCOPE_I18N_MAP[mode], { defaultValue: mode })}
                  />
                ))}
              </RadioGroup>
            </FormControl>

            <Box sx={{ flex: 1, minWidth: 280 }}>
              {scopeMode === "SECTOR" && (
                <Autocomplete
                  multiple
                  size="small"
                  options={industries}
                  value={selectedIndustries}
                  onChange={(_, value) => setSelectedIndustries(value)}
                  getOptionLabel={(opt) => `${opt.industry} (${opt.code})`}
                  isOptionEqualToValue={(a, b) => a.code === b.code}
                  loading={classificationsQuery.isLoading}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label={t("reports.buyRanking.filters.industries")}
                      placeholder={t("reports.buyRanking.filters.industriesPlaceholder")}
                    />
                  )}
                />
              )}
              {scopeMode === "PORTFOLIO" && (
                <FormControl component="fieldset">
                  <FormLabel sx={{ fontSize: 12, fontWeight: 700 }}>{t("reports.buyRanking.filters.portfolioTypes")}</FormLabel>
                  <FormGroup row>
                    {availablePortfolioTypes.map((pt) => (
                      <FormControlLabel
                        key={pt}
                        control={
                          <Checkbox
                            size="small"
                            checked={portfolioTypes.includes(pt)}
                            onChange={() => handleTogglePortfolioType(pt)}
                          />
                        }
                        label={t(PORTFOLIO_I18N_MAP[pt], { defaultValue: pt })}
                      />
                    ))}
                  </FormGroup>
                </FormControl>
              )}
              {scopeMode === "CUSTOM" && (
                <TextField
                  fullWidth
                  size="small"
                  label={t("reports.buyRanking.filters.customSymbols")}
                  placeholder={t("reports.buyRanking.filters.customSymbolsPlaceholder")}
                  value={customSymbolsText}
                  onChange={(e) => setCustomSymbolsText(e.target.value)}
                />
              )}
            </Box>
          </Stack>

          <Divider />

          <Stack direction={{ xs: "column", lg: "row" }} spacing={2} alignItems={{ lg: "center" }} justifyContent="space-between">
            <FormControl component="fieldset">
              <FormLabel sx={{ fontSize: 12, fontWeight: 700 }}>{t("reports.buyRanking.filters.assetTypes")}</FormLabel>
              <FormGroup row>
                {availableAssets.map((at) => (
                  <FormControlLabel
                    key={at}
                    control={
                      <Checkbox
                        size="small"
                        checked={assetTypes.includes(at)}
                        onChange={() => handleToggleAsset(at)}
                      />
                    }
                    label={t(ASSET_I18N_MAP[at], { defaultValue: at })}
                  />
                ))}
              </FormGroup>
            </FormControl>

            <FormControl component="fieldset">
              <FormLabel sx={{ fontSize: 12, fontWeight: 700 }}>{t("reports.buyRanking.filters.tierFilter")}</FormLabel>
              <FormGroup row>
                {availableTiers.map((tier) => (
                  <FormControlLabel
                    key={tier}
                    control={
                      <Checkbox
                        size="small"
                        checked={tiers.includes(tier)}
                        onChange={() => handleToggleTier(tier)}
                      />
                    }
                    label={
                      <Stack direction="row" spacing={0.5} alignItems="center">
                        <span>{t(TIER_I18N_MAP[tier])}</span>
                        {data?.tierCounts && (
                          <Chip
                            size="small"
                            label={data.tierCounts[tier] ?? 0}
                            sx={{
                              height: 20,
                              fontSize: 11,
                              fontWeight: 700,
                              bgcolor: TIER_STYLES[tier].bg,
                              color: TIER_STYLES[tier].color,
                              border: `1px solid ${TIER_STYLES[tier].border}`,
                            }}
                          />
                        )}
                      </Stack>
                    }
                  />
                ))}
              </FormGroup>
            </FormControl>
          </Stack>

          <Stack direction={{ xs: "column", md: "row" }} spacing={1.5} alignItems={{ md: "center" }} flexWrap="wrap">
            <FormControl size="small" sx={{ minWidth: 165 }}>
              <InputLabel>{t("reports.buyRanking.filters.minFundamentalGrade")}</InputLabel>
              <Select
                label={t("reports.buyRanking.filters.minFundamentalGrade")}
                value={minFundamentalGrade}
                onChange={(e) => setMinFundamentalGrade(e.target.value as BuyRankingFundamentalGrade)}
              >
                {availableGrades.map((g) => (
                  <MenuItem key={g} value={g}>
                    {t(GRADE_I18N_MAP[g], { defaultValue: g })}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>

            <TextField
              size="small"
              type="number"
              sx={{ width: 175 }}
              label={t("reports.buyRanking.filters.minNetRr")}
              placeholder={t("reports.buyRanking.filters.minNetRrAll")}
              value={minNetRr}
              onChange={(e) => setMinNetRr(e.target.value)}
              inputRef={minNetRrInputRef}
              inputProps={{ min: 0, step: "any", "data-testid": "min-net-rr-input" }}
            />

            <FormControl size="small" sx={{ minWidth: 165 }}>
              <InputLabel>{t("reports.buyRanking.filters.horizon")}</InputLabel>
              <Select
                label={t("reports.buyRanking.filters.horizon")}
                value={horizon}
                onChange={(e) => setHorizon(e.target.value as BuyRankingHorizon)}
              >
                {availableHorizons.map((h) => (
                  <MenuItem key={h} value={h}>
                    {h === "SWING"
                      ? t("reports.buyRanking.filters.horizonSwing")
                      : t("reports.buyRanking.filters.horizonPosition")}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControl size="small" sx={{ minWidth: 230 }}>
              <InputLabel>{t("reports.buyRanking.filters.sortBy")}</InputLabel>
              <Select
                label={t("reports.buyRanking.filters.sortBy")}
                value={sortBy}
                onChange={(e) => setSortBy(e.target.value as BuyRankingSortBy)}
              >
                {availableSorts.map((s) => (
                  <MenuItem key={s} value={s}>
                    {t(SORT_I18N_MAP[s], { defaultValue: s })}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>

            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={excludeEarnings14d}
                  onChange={(e) => setExcludeEarnings14d(e.target.checked)}
                />
              }
              label={t("reports.buyRanking.filters.excludeEarnings14d")}
            />

            <Box sx={{ flexGrow: 1 }} />

            <Button
              variant="contained"
              startIcon={reportQuery.isFetching ? <CircularProgress size={16} color="inherit" /> : <PlayArrow />}
              onClick={handleRunQuery}
              disabled={reportQuery.isFetching || !optionsReady}
              data-testid="run-ranking-btn"
            >
              {t("reports.buyRanking.runQuery")}
            </Button>
          </Stack>
        </Stack>
        )}
      </Paper>

      {validationError && (
        <Alert severity="warning" data-testid="validation-error-alert">
          {validationError}
        </Alert>
      )}
      {queryErrorText && (
        <Alert severity="error">{t("reports.buyRanking.notices.queryFailed", { message: queryErrorText })}</Alert>
      )}
      {exportError && (
        <Alert severity="error" data-testid="export-error-alert">
          {exportError}
        </Alert>
      )}

      {/* 大盤市況橫幅與截斷／無效代碼警示 */}
      {data && (
        <Stack spacing={1.25}>
          <Paper elevation={0} sx={{ p: 1.75, border: 1, borderColor: "divider", borderRadius: 2, bgcolor: "#F8FAFC" }}>
            <Stack direction="row" spacing={1.25} flexWrap="wrap" alignItems="center" useFlexGap>
              <Chip size="small" variant="outlined" label={t("reports.buyRanking.market.asOfDate", { date: data.asOfDate })} />
              <Chip size="small" variant="outlined" label={t("reports.buyRanking.market.generatedAt", { time: data.generatedAtHk })} />
              <Chip
                size="small"
                color={
                  data.marketContext.marketRegime === "BEAR" || data.marketContext.marketRegime === "PANIC"
                    ? "error"
                    : data.marketContext.marketRegime === "CORRECTION"
                    ? "warning"
                    : "success"
                }
                label={`${t("reports.buyRanking.market.regime")}: ${data.marketContext.marketRegime}`}
              />
              {data.marketContext.vixClose != null && (
                <Chip size="small" variant="outlined" label={`${t("reports.buyRanking.market.vix")}: ${formatNum(data.marketContext.vixClose, 2)}`} />
              )}
              {data.marketContext.tenYearYieldPct != null && (
                <Chip
                  size="small"
                  variant="outlined"
                  label={`${t("reports.buyRanking.market.tenYear")}: ${formatNum(data.marketContext.tenYearYieldPct, 2)}%`}
                />
              )}
              {data.marketContext.hyOasPct != null && (
                <Chip
                  size="small"
                  variant="outlined"
                  color={data.marketContext.hyOasStatus === "STRESS" || data.marketContext.hyOasStatus === "WIDENING" ? "warning" : "default"}
                  label={`${t("reports.buyRanking.market.hyOas")}: ${formatNum(data.marketContext.hyOasPct, 2)}% (${data.marketContext.hyOasStatus ?? "—"})`}
                />
              )}
              {data.marketContext.macroCautionActive && (
                <Chip size="small" color="warning" label={t("reports.buyRanking.market.macroCaution")} />
              )}
              <Box sx={{ flexGrow: 1 }} />
              <Typography variant="caption" color="text.secondary">
                {t("reports.buyRanking.notices.coverageSummary", {
                  evaluated: data.scopeSummary.evaluatedCount,
                  matched: data.scopeSummary.matchedTotalCount,
                  filtered: data.scopeSummary.filteredTotalCount,
                })}
              </Typography>
            </Stack>
          </Paper>

          {!data.marketContext.marketDataReady && (
            <Alert severity="warning">{t("reports.buyRanking.market.marketNotReady")}</Alert>
          )}
          {data.marketContext.staleDateFallback && (
            <Alert severity="warning" data-testid="stale-date-alert">
              {t("reports.buyRanking.notices.staleDateFallback", {
                asOfDate: data.asOfDate,
                lastClosedDay: data.marketContext.lastClosedTradingDay || data.asOfDate,
              })}
            </Alert>
          )}
          {data.scopeSummary.truncated && (
            <Alert severity="warning" data-testid="truncation-warning">
              {t("reports.buyRanking.notices.truncated", {
                matched: data.scopeSummary.matchedTotalCount,
                evaluated: data.scopeSummary.evaluatedCount,
                max: data.scopeSummary.maxCandidates,
                filtered: data.scopeSummary.filteredTotalCount,
              })}
            </Alert>
          )}
          {data.scopeSummary.unmatchedSymbols.length > 0 && (
            <Alert severity="info">
              {t("reports.buyRanking.notices.unmatchedSymbols", {
                symbols: data.scopeSummary.unmatchedSymbols.join(", "),
              })}
            </Alert>
          )}
          {data.scopeSummary.untrackedSymbols.length > 0 && (
            <Alert severity="info">
              {t("reports.buyRanking.notices.untrackedSymbols", {
                symbols: data.scopeSummary.untrackedSymbols.join(", "),
              })}
            </Alert>
          )}
          {data.scopeSummary.excludedByAssetTypeSymbols.length > 0 && (
            <Alert severity="info">
              {t("reports.buyRanking.notices.excludedAssetSymbols", {
                symbols: data.scopeSummary.excludedByAssetTypeSymbols.join(", "),
              })}
            </Alert>
          )}
        </Stack>
      )}

      {/* 排行榜表格 */}
      <Paper elevation={0} sx={{ border: 1, borderColor: "divider", borderRadius: 2, overflow: "hidden" }}>
        {!appliedRequest ? (
          <Box sx={{ p: 5, textAlign: "center" }}>
            <Typography color="text.secondary">{t("reports.buyRanking.emptyPrompt")}</Typography>
          </Box>
        ) : reportQuery.isLoading ? (
          <Box sx={{ p: 5, textAlign: "center" }}>
            <CircularProgress size={28} />
            <Typography color="text.secondary" sx={{ mt: 1.5 }}>
              {t("reports.buyRanking.loading")}
            </Typography>
          </Box>
        ) : items.length === 0 ? (
          <Box sx={{ p: 5, textAlign: "center" }}>
            <Typography color="text.secondary">{t("reports.buyRanking.emptyResult")}</Typography>
          </Box>
        ) : (
          <>
            <TableContainer sx={{ maxHeight: 680 }}>
              <Table stickyHeader size="small">
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ fontWeight: 700, width: 42 }} />
                    <TableCell sx={{ fontWeight: 700, width: 76 }}>{t("reports.buyRanking.columns.rank")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 160 }}>{t("reports.buyRanking.columns.tier")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 150 }}>{t("reports.buyRanking.columns.symbol")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 170 }}>{t("reports.buyRanking.columns.priceAndBuyZone")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 120 }}>{t("reports.buyRanking.columns.stopLoss")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 95 }} align="right">{t("reports.buyRanking.columns.netRr")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 150 }}>{t("reports.buyRanking.columns.suggestedShares")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 195 }}>{t("reports.buyRanking.columns.valuationQuality")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 95 }} align="right">{t("reports.buyRanking.columns.daysToEarnings")}</TableCell>
                    <TableCell sx={{ fontWeight: 700, minWidth: 260 }}>{t("reports.buyRanking.columns.reason")}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {paginatedItems.map((item: BuyRankingItemDto) => {
                    const style = TIER_STYLES[item.tier] ?? TIER_STYLES.TIER_4;
                    const isEtf = item.assetType === "ETF";
                    const isExpanded = !!expandedFigis[item.figi];
                    const hasMacroHalf = item.positionSizingTags?.includes("MACRO_HEADWIND_HALF_SIZE");
                    const hasSectorHalf = item.positionSizingTags?.includes("SECTOR_BREADTH_HEADWIND_HALF_SIZE");
                    const resolvedScaleKey = item.positionScaleTag === "1/2"
                      ? (hasSectorHalf && hasMacroHalf ? "1/2_MACRO_SECTOR" : hasSectorHalf ? "1/2_SECTOR" : "1/2")
                      : item.positionScaleTag;
                    const scaleLabel = resolvedScaleKey
                      ? t(`reports.buyRanking.scaleTags.${resolvedScaleKey}`, { defaultValue: item.positionScaleTag ?? resolvedScaleKey })
                      : null;

                    return (
                      <React.Fragment key={item.figi}>
                      <TableRow hover sx={{ bgcolor: `${style.bg}40` }}>
                        <TableCell padding="checkbox">
                          <IconButton
                            size="small"
                            aria-label={isExpanded ? t("reports.buyRanking.detail.collapse") : t("reports.buyRanking.detail.expand")}
                            onClick={() => handleToggleRowExpand(item.figi)}
                            data-testid={`expand-row-${item.symbol}`}
                          >
                            {isExpanded ? <KeyboardArrowUp fontSize="small" /> : <KeyboardArrowDown fontSize="small" />}
                          </IconButton>
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" sx={{ fontWeight: 800 }}>
                            {item.rank}
                          </Typography>
                          {data?.appliedFilters.sortBy !== "TIER_COMPOSITE" && (
                            <Typography variant="caption" color="text.secondary" display="block">
                              {t("reports.buyRanking.cell.recommendedRank", { rank: item.recommendedRank })}
                            </Typography>
                          )}
                        </TableCell>

                        <TableCell>
                          <Chip
                            size="small"
                            label={t(TIER_I18N_MAP[item.tier])}
                            sx={{
                              bgcolor: style.bg,
                              color: style.color,
                              border: `1px solid ${style.border}`,
                              fontWeight: 700,
                              fontSize: 11,
                            }}
                          />
                          {item.tierScore != null && (
                            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.35 }}>
                              {t("reports.buyRanking.cell.score", { score: formatNum(item.tierScore, 1) })}
                            </Typography>
                          )}
                        </TableCell>

                        <TableCell>
                          <Stack direction="row" spacing={0.75} alignItems="center">
                            <Link
                              component="button"
                              underline="hover"
                              sx={{ fontWeight: 800, fontSize: 14, textAlign: "left" }}
                              onClick={() => navigate(`/stock-analysis/${encodeURIComponent(item.figi)}`)}
                            >
                              {item.symbol}
                            </Link>
                            {item.assetType !== "COMMON_STOCK" && (
                              <Chip size="small" variant="outlined" label={item.assetType} sx={{ height: 18, fontSize: 10 }} />
                            )}
                          </Stack>
                          <Typography variant="caption" color="text.secondary" display="block" noWrap sx={{ maxWidth: 180 }}>
                            {item.sector || item.stockName || "—"}
                          </Typography>
                        </TableCell>

                        <TableCell>
                          <Typography variant="body2" sx={{ fontWeight: 700 }}>
                            {formatPrice(item.currentPrice)}
                          </Typography>
                          {item.buyZone ? (
                            <Typography variant="caption" color="text.secondary" display="block">
                              [{formatPrice(item.buyZone.low)} – {formatPrice(item.buyZone.high)}]
                            </Typography>
                          ) : (
                            <Typography variant="caption" color="text.disabled" display="block">—</Typography>
                          )}
                          {item.distanceToBuyZonePct != null && (
                            <Typography variant="caption" color={item.distanceToBuyZonePct <= 0 ? "success.main" : "text.secondary"} display="block">
                              {item.distanceToBuyZonePct === 0
                                ? t("reports.buyRanking.cell.inBuyZone")
                                : t("reports.buyRanking.cell.distToBuy", { pct: formatPct(item.distanceToBuyZonePct, true) })}
                            </Typography>
                          )}
                        </TableCell>

                        <TableCell>
                          <Typography variant="body2">{formatPrice(item.stopLossPrice)}</Typography>
                          {item.stopLossDistancePct != null && (
                            <Typography variant="caption" color="error.main" display="block">
                              ({formatPct(item.stopLossDistancePct, true)})
                            </Typography>
                          )}
                        </TableCell>

                        <TableCell align="right">
                          <Typography
                            variant="body2"
                            sx={{
                              fontWeight: 700,
                              color: item.netRiskReward != null && item.netRiskReward >= 2 ? "success.main" : "text.primary",
                            }}
                          >
                            {formatNum(item.netRiskReward, 2)}
                          </Typography>
                          {item.tier === "TIER_2" && item.planNetRiskReward != null && (
                            <Typography
                              variant="caption"
                              color="text.secondary"
                              display="block"
                              data-testid={`tier2-plan-rr-${item.symbol}`}
                            >
                              {t("reports.buyRanking.cell.planNetRr", { rr: formatNum(item.planNetRiskReward, 2) })}
                            </Typography>
                          )}
                        </TableCell>

                        <TableCell>
                          <Typography variant="body2" sx={{ fontWeight: 700 }}>
                            {item.suggestedShares != null
                              ? t("reports.buyRanking.cell.sharesUnit", { shares: item.suggestedShares.toLocaleString() })
                              : "—"}
                          </Typography>
                          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.35 }}>
                            {scaleLabel && (
                              <Chip size="small" variant="outlined" label={scaleLabel} sx={{ height: 18, fontSize: 10 }} />
                            )}
                            {!item.positionSizingActionable && item.tier !== "TIER_4" && (item.suggestedShares ?? 0) > 0 && (
                              <Chip
                                size="small"
                                color="info"
                                variant="outlined"
                                label={t("reports.buyRanking.cell.sharesEstimateHint")}
                                sx={{ height: 18, fontSize: 10 }}
                              />
                            )}
                            {item.sectorConcentrationWarning && (
                              <Chip
                                size="small"
                                color="warning"
                                label={t("reports.buyRanking.cell.sectorWarn")}
                                sx={{ height: 18, fontSize: 10 }}
                              />
                            )}
                          </Stack>
                        </TableCell>

                        <TableCell>
                          {isEtf ? (
                            <Stack spacing={0.25}>
                              <Typography variant="caption">
                                {t("reports.buyRanking.cell.etfTrendLabel")}: {formatNum(item.etfTrendScore, 0)} |{" "}
                                {t("reports.buyRanking.cell.etfCmfLabel")}: {formatNum(item.etfCmf20d, 2)}
                              </Typography>
                              <Typography variant="caption" color="text.secondary">
                                {t("reports.buyRanking.cell.etfRsLabel")}: {formatPct(item.etfRs60dVsSpy, true)}
                              </Typography>
                            </Stack>
                          ) : (
                            <Stack spacing={0.25}>
                              <Typography variant="caption">
                                {t("reports.buyRanking.cell.discountLabel")}: {formatPct(item.valuationDiscountPct, true)} |{" "}
                                {t("reports.buyRanking.cell.pegLabel")}: {formatNum(item.forwardPeg, 2)}
                              </Typography>
                              <Typography variant="caption" color="text.secondary">
                                {t("reports.buyRanking.cell.roicLabel")}: {formatPct(item.roic)} |{" "}
                                {t("reports.buyRanking.cell.revDcfLabel")}: {formatPct(item.reverseDcfImpliedGrowth)}
                              </Typography>
                            </Stack>
                          )}
                        </TableCell>

                        <TableCell align="right">
                          {item.daysToEarnings == null ? (
                            <Typography variant="caption" color="text.disabled">—</Typography>
                          ) : item.daysToEarnings === 0 ? (
                            <Chip size="small" color="warning" label={t("reports.buyRanking.cell.todayEarnings")} />
                          ) : (
                            <Typography
                              variant="body2"
                              color={item.daysToEarnings <= 14 ? "warning.main" : "text.primary"}
                              sx={{ fontWeight: item.daysToEarnings <= 14 ? 700 : 400 }}
                            >
                              {t("reports.buyRanking.cell.daysUnit", { days: item.daysToEarnings })}
                            </Typography>
                          )}
                        </TableCell>

                        <TableCell>
                          <Tooltip title={item.actionReason} placement="top-start">
                            <Typography variant="body2" sx={{ color: style.color, fontSize: 12.5 }}>
                              {item.actionReason}
                            </Typography>
                          </Tooltip>
                        </TableCell>
                      </TableRow>
                      <TableRow>
                        <TableCell colSpan={11} sx={{ py: 0, borderBottom: isExpanded ? undefined : "none" }}>
                          <Collapse in={isExpanded} timeout="auto" unmountOnExit>
                            <Box
                              sx={{ p: 1.5, my: 0.75, bgcolor: "background.default", borderRadius: 1, border: 1, borderColor: "divider" }}
                              data-testid={`detail-panel-${item.symbol}`}
                            >
                              <Stack direction={{ xs: "column", md: "row" }} spacing={2} flexWrap="wrap" useFlexGap>
                                <Typography variant="caption">
                                  <strong>{t("reports.buyRanking.detail.verdict")}:</strong> {item.verdict || "—"} |{" "}
                                  <strong>{t("reports.buyRanking.detail.backing")}:</strong> {item.fundamentalBacking || "—"} |{" "}
                                  <strong>{t("reports.buyRanking.detail.stance")}:</strong> {item.stance || "—"}
                                </Typography>
                                <Typography variant="caption">
                                  <strong>{t("reports.buyRanking.detail.buyZoneSource")}:</strong> {item.buyZoneSource || "—"} |{" "}
                                  <strong>{t("reports.buyRanking.detail.planNetRr")}:</strong> {formatNum(item.planNetRiskReward, 2)} |{" "}
                                  <strong>{t("reports.buyRanking.detail.breakoutDiagnosis")}:</strong> {item.breakoutDiagnosis || "—"}
                                </Typography>
                                <Typography variant="caption">
                                  <strong>{t("reports.buyRanking.detail.sizingStatus")}:</strong> {item.positionSizingStatus || "—"}
                                  {item.positionSizingReasonCode ? ` (${item.positionSizingReasonCode})` : ""} |{" "}
                                  <strong>{t("reports.buyRanking.detail.sizingTags")}:</strong>{" "}
                                  {item.positionSizingTags?.length
                                    ? item.positionSizingTags
                                        .map((tag) => t(`portfolio.sizing.tags.${tag}`, { defaultValue: tag }))
                                        .join(", ")
                                    : "—"}
                                </Typography>
                                {!isEtf && (
                                  <Typography variant="caption">
                                    <strong>{t("reports.buyRanking.detail.dcfBasis")}:</strong> {item.reverseDcfBasis || "—"} |{" "}
                                    <strong>{t("reports.buyRanking.detail.dcfStatus")}:</strong> {item.reverseDcfStatus || "—"}
                                  </Typography>
                                )}
                                <Typography variant="caption">
                                  <strong>{t("reports.buyRanking.detail.sectorFlow")}:</strong> {item.sectorFlowState || "—"} (
                                  {formatNum(item.sectorCmf20d, 2)})
                                </Typography>
                              </Stack>
                            </Box>
                          </Collapse>
                        </TableCell>
                      </TableRow>
                      </React.Fragment>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination
              component="div"
              count={items.length}
              page={page}
              onPageChange={(_, nextPage) => setPage(nextPage)}
              rowsPerPage={rowsPerPage}
              onRowsPerPageChange={(e) => {
                setRowsPerPage(parseInt(e.target.value, 10));
                setPage(0);
              }}
              rowsPerPageOptions={[25, 50, 100, 300]}
            />
          </>
        )}
      </Paper>
    </Stack>
  );
}
export default BuyRankingReportPage;
