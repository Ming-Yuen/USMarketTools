import React, { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Alert, Box, Button, Chip, CircularProgress, FormControl, Grid, InputLabel,
  MenuItem, Paper, Select, Stack, Table, TableBody, TableCell, TableContainer,
  TableHead, TablePagination, TableRow, TextField, Typography,
} from "@mui/material";
import { apiClient } from "../services/apiClient";
import { apiConfig } from "../apiConfig";
import { StockClassificationResponse, StockScreenResponse } from "../services/types/dto/stock";
import { formatCompactMarketCap, formatNumber, metricLabel, statusLabel, TIER_LABELS, TIER_SHORT_LABELS } from "./AnalysisPanels";

interface ScreenerFilters {
  symbol: string;
  industry: string;
  conceptCode: string;
  marketCapTier: string;
  verdict: string;
  minRevenueGrowthPct: string;
  minRoePct: string;
  minFcfMarginPct: string;
  minHistoricalPercentile: string;
  maxHistoricalPercentile: string;
  sortBy: string;
  sortDir: "asc" | "desc";
}

const emptyFilters: ScreenerFilters = {
  symbol: "",
  industry: "",
  conceptCode: "",
  marketCapTier: "",
  verdict: "",
  minRevenueGrowthPct: "",
  minRoePct: "",
  minFcfMarginPct: "",
  minHistoricalPercentile: "",
  maxHistoricalPercentile: "",
  sortBy: "",
  sortDir: "desc",
};

const pageSize = 50;

const VERDICT_LABELS: Record<string, string> = {
  DEEP_UNDERVALUED: "嚴重偏低",
  MODERATELY_UNDERVALUED: "合理偏低",
  FAIR_LOWER: "合理偏下緣",
  FAIR_UPPER: "合理偏上緣",
  MODERATELY_OVERVALUED: "估值偏貴",
  OVERVALUED_RISK: "高估風險",
  INSUFFICIENT_EVIDENCE: "證據不足",
};

const SORT_FIELD_LABELS: Record<string, string> = {
  symbol: "股票代碼",
  marketCap: "市值（USD）",
  marketCapTier: "市值量級",
  verdict: "估值結論",
  historicalPercentile: "歷史分位（P0–P100）",
  revenueGrowthYoy: "營收年增率（YoY）",
  roe: "ROE",
  fcfMargin: "FCF Margin",
  pe: "P/E",
  pb: "P/B",
  ps: "P/S",
  pfcf: "P/FCF",
  evEbitda: "EV/EBITDA",
};

const verdictColor = (verdict?: string | null): "success" | "info" | "warning" | "error" | "default" => {
  if (verdict === "DEEP_UNDERVALUED" || verdict === "MODERATELY_UNDERVALUED") return "success";
  if (verdict === "FAIR_LOWER" || verdict === "FAIR_UPPER") return "info";
  if (verdict === "MODERATELY_OVERVALUED") return "warning";
  if (verdict === "OVERVALUED_RISK") return "error";
  return "default";
};

const parsePctToDecimal = (raw: string): number | undefined => {
  const trimmed = raw.trim();
  if (trimmed === "") return undefined;
  const num = Number(trimmed);
  return Number.isFinite(num) ? Number((num / 100).toFixed(6)) : undefined;
};

const parsePlainNumber = (raw: string): number | undefined => {
  const trimmed = raw.trim();
  if (trimmed === "") return undefined;
  const num = Number(trimmed);
  return Number.isFinite(num) ? num : undefined;
};

const formatScreenerPct = (val?: number | null, signed = false) => {
  if (val == null || !Number.isFinite(val)) return "—";
  const pct = val * 100;
  return (signed && pct > 0 ? "+" : "") + pct.toFixed(1) + "%";
};

export function StockScreenerPage() {
  const navigate = useNavigate();
  const [draft, setDraft] = useState<ScreenerFilters>(emptyFilters);
  const [filters, setFilters] = useState<ScreenerFilters>(emptyFilters);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const classifications = useQuery<StockClassificationResponse>({
    queryKey: ["stock-classifications"],
    queryFn: async () => (await apiClient.post<StockClassificationResponse>(apiConfig.getStockClassifications.url, {})).data,
  });

  const stocks = useQuery<StockScreenResponse>({
    queryKey: ["stock-screen", filters, page],
    queryFn: async () => {
      const params: Record<string, unknown> = {
        symbol: filters.symbol || undefined,
        industry: filters.industry || undefined,
        conceptCode: filters.conceptCode || undefined,
        marketCapTier: filters.marketCapTier || undefined,
        verdict: filters.verdict || undefined,
        minRevenueGrowthYoy: parsePctToDecimal(filters.minRevenueGrowthPct),
        minRoe: parsePctToDecimal(filters.minRoePct),
        minFcfMargin: parsePctToDecimal(filters.minFcfMarginPct),
        minHistoricalPercentile: parsePlainNumber(filters.minHistoricalPercentile),
        maxHistoricalPercentile: parsePlainNumber(filters.maxHistoricalPercentile),
        sortBy: filters.sortBy || undefined,
        sortDir: filters.sortBy ? filters.sortDir : undefined,
        page,
        size: pageSize,
      };
      return (await apiClient.get<StockScreenResponse>(apiConfig.getStockScreen.url, { params })).data;
    },
  });

  const industries = useMemo(() => Array.from(new Set((classifications.data?.classifications ?? [])
    .map(row => row.industry).filter(Boolean))).sort((a, b) => a.localeCompare(b)), [classifications.data]);
  const concepts = classifications.data?.concepts ?? [];
  const tierOptions = classifications.data?.marketCapTiers ?? [];
  const verdictOptions = classifications.data?.verdicts ?? [];
  const sortFieldOptions = classifications.data?.sortFields ?? [];

  const apply = (event: React.FormEvent) => {
    event.preventDefault();
    const minP = parsePlainNumber(draft.minHistoricalPercentile);
    const maxP = parsePlainNumber(draft.maxHistoricalPercentile);
    if ((minP != null && (minP < 0 || minP > 100)) || (maxP != null && (maxP < 0 || maxP > 100))) {
      setValidationError("歷史分位範圍須介乎 0 至 100。");
      return;
    }
    if (minP != null && maxP != null && minP > maxP) {
      setValidationError("最低歷史分位不可大於最高歷史分位。");
      return;
    }
    setValidationError(null);
    setPage(0);
    setFilters({ ...draft, symbol: draft.symbol.trim().toUpperCase() });
  };

  const reset = () => {
    setValidationError(null);
    setDraft(emptyFilters);
    setFilters(emptyFilters);
    setPage(0);
  };

  return <Box sx={{ width: "100%", minWidth: 0 }}>
    <Typography variant="h5" fontWeight={800}>美股篩選</Typography>
    <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
      全市場股票資料；支援按代碼、產業、概念、市值量級、成長品質、歷史分位及估值結論篩選與全域排序。點選股票代碼或雙擊資料列開啟分析。
    </Typography>

    <Paper component="form" variant="outlined" onSubmit={apply} sx={{ p: 2, mb: 2 }}>
      <Grid container spacing={1.5} alignItems="center">
        <Grid size={{ xs: 12, sm: 6, md: 2.5 }}>
          <TextField fullWidth label="股票代碼" size="small" value={draft.symbol}
            onChange={event => setDraft({ ...draft, symbol: event.target.value })} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 2.5 }}>
          <FormControl fullWidth size="small">
            <InputLabel id="stock-industry-label">產業</InputLabel>
            <Select labelId="stock-industry-label" label="產業" value={draft.industry}
              onChange={event => setDraft({ ...draft, industry: event.target.value })}>
              <MenuItem value="">全部產業</MenuItem>
              {industries.map(industry => <MenuItem key={industry} value={industry}>{industry}</MenuItem>)}
            </Select>
          </FormControl>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 2.5 }}>
          <FormControl fullWidth size="small">
            <InputLabel id="stock-concept-label">概念</InputLabel>
            <Select labelId="stock-concept-label" label="概念" value={draft.conceptCode}
              onChange={event => setDraft({ ...draft, conceptCode: event.target.value })}>
              <MenuItem value="">全部概念</MenuItem>
              {concepts.map(concept => <MenuItem key={concept.code} value={concept.code}>{concept.name}</MenuItem>)}
            </Select>
          </FormControl>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 2.25 }}>
          <FormControl fullWidth size="small">
            <InputLabel id="stock-tier-label">市值量級</InputLabel>
            <Select labelId="stock-tier-label" label="市值量級" value={draft.marketCapTier}
              onChange={event => setDraft({ ...draft, marketCapTier: event.target.value })}>
              <MenuItem value="">全部量級</MenuItem>
              {tierOptions.map(tier => <MenuItem key={tier} value={tier}>{TIER_LABELS[tier] ?? tier}</MenuItem>)}
            </Select>
          </FormControl>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 2.25 }}>
          <FormControl fullWidth size="small">
            <InputLabel id="stock-verdict-label">估值結論</InputLabel>
            <Select labelId="stock-verdict-label" label="估值結論" value={draft.verdict}
              onChange={event => setDraft({ ...draft, verdict: event.target.value })}>
              <MenuItem value="">全部結論</MenuItem>
              {verdictOptions.map(v => <MenuItem key={v} value={v}>{VERDICT_LABELS[v] ?? v}</MenuItem>)}
            </Select>
          </FormControl>
        </Grid>

        <Grid size={{ xs: 6, sm: 4, md: 1.8 }}>
          <TextField fullWidth size="small" type="number" inputProps={{ step: "any" }} label="最低營收 YoY (%)"
            value={draft.minRevenueGrowthPct} onChange={e => setDraft({ ...draft, minRevenueGrowthPct: e.target.value })} />
        </Grid>
        <Grid size={{ xs: 6, sm: 4, md: 1.6 }}>
          <TextField fullWidth size="small" type="number" inputProps={{ step: "any" }} label="最低 ROE (%)"
            value={draft.minRoePct} onChange={e => setDraft({ ...draft, minRoePct: e.target.value })} />
        </Grid>
        <Grid size={{ xs: 6, sm: 4, md: 1.8 }}>
          <TextField fullWidth size="small" type="number" inputProps={{ step: "any" }} label="最低 FCF 率 (%)"
            value={draft.minFcfMarginPct} onChange={e => setDraft({ ...draft, minFcfMarginPct: e.target.value })} />
        </Grid>
        <Grid size={{ xs: 6, sm: 4, md: 1.6 }}>
          <TextField fullWidth size="small" type="number" inputProps={{ step: "any" }} label="最低歷史分位"
            value={draft.minHistoricalPercentile} onChange={e => setDraft({ ...draft, minHistoricalPercentile: e.target.value })} />
        </Grid>
        <Grid size={{ xs: 6, sm: 4, md: 1.6 }}>
          <TextField fullWidth size="small" type="number" inputProps={{ step: "any" }} label="最高歷史分位"
            value={draft.maxHistoricalPercentile} onChange={e => setDraft({ ...draft, maxHistoricalPercentile: e.target.value })} />
        </Grid>
        <Grid size={{ xs: 6, sm: 4, md: 2 }}>
          <FormControl fullWidth size="small">
            <InputLabel id="stock-sort-by-label">排序欄位</InputLabel>
            <Select labelId="stock-sort-by-label" label="排序欄位" value={draft.sortBy}
              onChange={e => setDraft({ ...draft, sortBy: e.target.value })}>
              <MenuItem value="">預設排序</MenuItem>
              {sortFieldOptions.map(field => <MenuItem key={field} value={field}>{SORT_FIELD_LABELS[field] ?? field}</MenuItem>)}
            </Select>
          </FormControl>
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 1.6 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            {draft.sortBy && (
              <FormControl size="small" sx={{ minWidth: 88 }}>
                <InputLabel id="stock-sort-dir-label">方向</InputLabel>
                <Select labelId="stock-sort-dir-label" label="方向" value={draft.sortDir}
                  onChange={e => setDraft({ ...draft, sortDir: e.target.value as "asc" | "desc" })}>
                  <MenuItem value="desc">高→低</MenuItem>
                  <MenuItem value="asc">低→高</MenuItem>
                </Select>
              </FormControl>
            )}
            <Button type="submit" variant="contained" disabled={stocks.isFetching}>搜尋</Button>
            <Button onClick={reset}>重設</Button>
          </Stack>
        </Grid>
      </Grid>
    </Paper>

    {validationError && <Alert severity="warning" sx={{ mb: 2 }}>{validationError}</Alert>}
    {classifications.isError && <Alert severity="warning" sx={{ mb: 2 }}>分類選項載入失敗，仍可按股票代碼搜尋。</Alert>}
    {stocks.isError && <Alert severity="error" sx={{ mb: 2 }}>股票資料載入失敗：{(stocks.error as Error).message}</Alert>}

    <Paper variant="outlined">
      {stocks.isFetching && <Stack direction="row" spacing={1} alignItems="center" sx={{ p: 2 }}><CircularProgress size={18} /><Typography>正在載入股票…</Typography></Stack>}
      <TableContainer sx={{ maxHeight: "65vh" }}>
        <Table size="small" stickyHeader aria-label="全市場股票篩選">
          <TableHead><TableRow>
            {([
              ["股票", "left"], ["名稱", "left"], ["產業", "left"], ["概念", "left"],
              ["市值／量級", "right"], ["估值結論", "left"], ["歷史分位", "right"],
              ["營收 YoY", "right"], ["ROE", "right"], ["FCF 率", "right"],
              ["收盤日", "left"], ["收盤價", "right"], ["P/E", "right"], ["P/B", "right"], ["P/S", "right"],
              ["P/FCF", "right"], ["EV/EBITDA", "right"], ["估值日／狀態", "left"],
            ] as const).map(([label, align]) => <TableCell key={label} align={align} sx={{ whiteSpace: "nowrap" }}>{label}</TableCell>)}
          </TableRow></TableHead>
          <TableBody>
            {(stocks.data?.items ?? []).map(row => <TableRow hover key={row.figi} onDoubleClick={() => navigate("/stockAnalysis/" + encodeURIComponent(row.figi))}>
              <TableCell><Button size="small" onClick={() => navigate("/stockAnalysis/" + encodeURIComponent(row.figi))}>{row.symbol}</Button></TableCell>
              <TableCell>{row.name}</TableCell>
              <TableCell sx={{ whiteSpace: "nowrap" }}>{row.industry ?? "—"}</TableCell>
              <TableCell sx={{ minWidth: 160 }}>{row.concepts?.map(concept => concept.name).join("、") || "—"}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                {row.marketCap != null ? (
                  <>{formatCompactMarketCap(row.marketCap, row.currency)}
                    {row.marketCapTier ? ` (${TIER_SHORT_LABELS[row.marketCapTier] ?? row.marketCapTier})` : ""}</>
                ) : "—"}
              </TableCell>
              <TableCell sx={{ whiteSpace: "nowrap" }}>
                {row.verdict ? (
                  <Chip size="small" color={verdictColor(row.verdict)} label={VERDICT_LABELS[row.verdict] ?? row.verdict} />
                ) : "—"}
              </TableCell>
              <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                {row.historicalPercentile != null
                  ? `P${Math.round(row.historicalPercentile)}${row.primaryMetric ? ` (${metricLabel(row.primaryMetric)})` : ""}`
                  : "—"}
              </TableCell>
              <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{formatScreenerPct(row.revenueGrowthYoy, true)}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{formatScreenerPct(row.roe)}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{formatScreenerPct(row.fcfMargin)}</TableCell>
              <TableCell sx={{ whiteSpace: "nowrap" }}>{row.quoteDate ?? "—"}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{formatNumber(row.closePrice)}</TableCell>
              {[row.pe, row.pb, row.ps, row.pfcf, row.evEbitda].map((value, index) => <TableCell align="right" key={index}>{formatNumber(value)}</TableCell>)}
              <TableCell title={row.reasonCode ?? undefined} sx={{ whiteSpace: "nowrap" }}>
                {row.valuationDate ? `${row.valuationDate} · ` : ""}{statusLabel(row.status)}
              </TableCell>
            </TableRow>)}
            {!stocks.isFetching && !stocks.isError && !stocks.data?.items.length && (
              <TableRow><TableCell colSpan={18} align="center">沒有符合條件的股票</TableCell></TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      <TablePagination component="div" count={stocks.data?.total ?? -1} page={page} rowsPerPage={pageSize} rowsPerPageOptions={[pageSize]}
        onPageChange={(_event, nextPage) => setPage(nextPage)} labelRowsPerPage="每頁"
        labelDisplayedRows={({ from, to, count }) => from + "–" + to + (count < 0 ? "，總數載入中" : "／" + count)} />
    </Paper>
  </Box>;
}
