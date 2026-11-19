import React, { useState } from "react";
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, CircularProgress,
  Dialog, DialogActions, DialogContent, DialogTitle, Divider, FormControl, InputLabel,
  MenuItem, Paper, Select, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from "@mui/material";
import { ExpandMore } from "@mui/icons-material";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { TFunction } from "i18next";
import { apiClient } from "../services/apiClient";
import { apiConfig } from "../apiConfig";
import { AnalysisReason, FundamentalSnapshot, MetricResult, MomentumAnalysis, MomentumVolume, PeerRelativeStrength } from "../services/types/dto/analysis";
import { StockScreenItem, StockScreenResponse } from "../services/types/dto/stock";
import { CustomPeerGroup, CustomPeerItem, ValuationReference } from "../services/types/dto/valuation";

export const formatNumber = (value?: number | null, digits = 2) => value == null || !Number.isFinite(value)
  ? "—" : value.toLocaleString("zh-HK", { minimumFractionDigits: digits, maximumFractionDigits: digits });
const number = formatNumber;

export const formatCompactMarketCap = (value?: number | null, currency?: string | null) => {
  if (value == null || !Number.isFinite(value) || value <= 0) return "—";
  const cur = currency?.toUpperCase() ?? "";
  const prefix = cur === "USD" ? "$" : "";
  const suffix = cur && cur !== "USD" ? ` ${cur}` : "";
  if (value >= 1e12) return `${prefix}${(value / 1e12).toFixed(2)}T${suffix}`;
  if (value >= 1e9) return `${prefix}${(value / 1e9).toFixed(2)}B${suffix}`;
  if (value >= 1e6) return `${prefix}${(value / 1e6).toFixed(1)}M${suffix}`;
  return `${prefix}${formatNumber(value, 0)}${suffix}`;
};

const statusLabels: Record<string, string> = {
  AVAILABLE: "可用", PARTIAL: "部分可用", STALE: "資料過期", INSUFFICIENT_DATA: "資料不足",
  UNVERIFIED: "尚未確認", NOT_APPLICABLE: "不適用", UNAVAILABLE: "暫不可用", UNRESOLVED: "資料待補",
};
export const statusLabel = (code?: string | null) => code ? statusLabels[code] ?? code : "尚未計算";
export const readableState = (t: TFunction, code?: string | null) => code
  ? t("analysisReport.states." + code, { defaultValue: statusLabel(code) }) : "—";
export const readableReason = (t: TFunction, reason: AnalysisReason) => t("analysisReport.reasons." + reason.code,
  { ...reason.params, defaultValue: reason.message || reason.code });

const reasonFallbacks: Record<string, string> = {
  NON_POSITIVE_FCF: "自由現金流非正，P/FCF 不適用（N.M.）",
  NON_POSITIVE_EPS: "每股盈利非正（虧損或零盈利），PE 不適用（N.M.）",
  NON_POSITIVE_EBITDA: "營運 EBITDA 非正，EV/EBITDA 不適用（N.M.）",
  NON_POSITIVE_COMMON_EQUITY: "普通股權益非正，P/B 不適用（N.M.）",
  NON_POSITIVE_REVENUE: "營業收入非正，P/S 不適用（N.M.）",
  PFCF_NOT_APPLICABLE_TO_INDUSTRY: "銀行與保險業不適用自由現金流倍數（P/FCF）",
  EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY: "銀行與保險業不適用企業價值倍數（EV/EBITDA）",
  HISTORICAL_DATE_NOT_APPLICABLE: "查詢指定歷史日期時不套用最新同業快照或當前產業假設設定",
  HISTORICAL_SAMPLES_INSUFFICIENT: "過去五年主指標有效月度樣本不足，暫不計算歷史分位區間",
  INDUSTRY_PEER_COMPANIES_INSUFFICIENT: "主要指標不足五家不同發行人的有效同業樣本",
  INDUSTRY_CLASSIFICATION_MISSING: "此股票尚未分配產業分類，暫無法比較同業或套用產業假設",
  SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED: "多股類歷史經濟權比例未經 1:1 準則確認，不推算歷史市值倍數",
  REPORTING_CURRENCY_MISMATCH: "財報或預期幣別與股價交易幣別不一致，不自動換算匯率",
  CUSTOM_PEERS_INSUFFICIENT: "自訂對標有效樣本未滿 3 家，已自動退回產業分層比較",
  EARNINGS_FORECAST_MISSING: "目前尚無已入庫的市場盈利預期紀錄",
  NO_UNREPORTED_FORECAST_PERIOD: "目前無合資格財期可供修正比較",
  ANNUAL_EARNINGS_FORECAST_MISSING: "缺少可用的未結束年度預期",
  FORECAST_FISCAL_PERIOD_UNVERIFIED: "年度預期缺少明確結算日且無法核實尚未結束",
  EARNINGS_FORECAST_STALE: "市場預期距估值日已逾 45 天未更新（資料過期）",
  FORECAST_REVISION_BASE_MISSING: "此財期缺少滿對應天數之可比歷史觀察基準",
  NON_POSITIVE_FORWARD_EPS: "市場預期年度 EPS 為虧損或零，Forward P/E 不適用（N.M.）",
  NON_POSITIVE_FORWARD_REVENUE: "市場預期年度營收非正，Forward P/S 不適用（N.M.）",
  FORWARD_EPS_GROWTH_BELOW_PEG_THRESHOLD: "預期 EPS 隱含成長率低於 5% 門檻，不計算 Forward PEG",
  FORWARD_EPS_GROWTH_ABOVE_PEG_CAP: "預期 EPS 隱含成長率逾 100% 上限，不計算 Forward PEG",
  NON_POSITIVE_PRIOR_FORECAST_EPS: "基期預期 EPS 為虧損或零，不計算百分比修正",
  NON_POSITIVE_PRIOR_FORECAST_REVENUE: "基期預期營收非正，不計算百分比修正",
};
export const reasonLabel = (t: TFunction, code?: string | null) => {
  if (!code) return "沒有可用資料";
  const fallback = reasonFallbacks[code] ?? code;
  return t("analysisReport.reasons." + code, { defaultValue: fallback });
};

const metricLabels: Record<string, string> = {
  pe: "P/E", pb: "P/B", ps: "P/S", pfcf: "P/FCF", evEbitda: "EV/EBITDA",
  PE: "P/E", PB: "P/B", PS: "P/S", PFCF: "P/FCF", EV_EBITDA: "EV/EBITDA",
};
export const metricLabel = (code?: string | null) => code ? metricLabels[code] ?? code : "—";

export const TIER_LABELS: Record<string, string> = {
  MEGA_LARGE: "大型／巨型股（≥$10B）",
  MID: "中型股（$2B–$10B）",
  SMALL: "小型股（<$2B）",
};
export const TIER_SHORT_LABELS: Record<string, string> = {
  MEGA_LARGE: "大型股",
  MID: "中型股",
  SMALL: "小型股",
};
export const PEER_SCOPE_LABELS: Record<string, string> = {
  CUSTOM_PEERS: "自訂對標組",
  SAME_TIER: "同規模同業",
  ADJACENT_TIER: "擴展相鄰規模同業",
  ALL_INDUSTRY: "全產業同業",
};
const PEER_WARNING_LABELS: Record<string, string> = {
  CUSTOM_PEER_MARKET_CAP_MISMATCH: "自訂對標組內有對手市值與本股相差逾 10 倍，留意規模溢價差異。",
  CUSTOM_PEER_GROWTH_DIVERGENCE: "自訂對標組營收成長中位數與本股差距達 20 個百分點以上，留意成長階段差異。",
};

const formatPctValue = (ratio?: number | null, signed = false) => {
  if (ratio == null || !Number.isFinite(ratio)) return "—";
  const pct = ratio * 100;
  return (signed && pct > 0 ? "+" : "") + pct.toFixed(2) + "%";
};

const peerMetricValue = (p: CustomPeerItem, metricCode?: string | null): number | null | undefined => {
  switch (metricCode?.toUpperCase()) {
    case "PE": return p.pe;
    case "PB": return p.pb;
    case "PS": return p.ps;
    case "PFCF": return p.pfcf;
    case "EV_EBITDA": return p.evEbitda;
    default: return undefined;
  }
};

const medianOf = (values: number[]): number | null => {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};

function CustomPeerDialog({ open, onClose, figi, symbol }: {
  open: boolean;
  onClose: () => void;
  figi: string;
  symbol?: string | null;
}) {
  const queryClient = useQueryClient();
  const [loading, setLoading] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loadedFigi, setLoadedFigi] = useState<string | null>(null);
  const [reloadSeq, setReloadSeq] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [supportedMetrics, setSupportedMetrics] = useState<string[]>([]);
  const [preferredMetric, setPreferredMetric] = useState<string>("");
  const [peers, setPeers] = useState<CustomPeerItem[]>([]);
  const [targetMarketCap, setTargetMarketCap] = useState<number | null>(null);
  const [targetCurrency, setTargetCurrency] = useState<string | null>(null);
  const [targetValuationDate, setTargetValuationDate] = useState<string | null>(null);
  const [targetPrimaryMetric, setTargetPrimaryMetric] = useState<string | null>(null);
  const [targetIssuerKey, setTargetIssuerKey] = useState<string | null>(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [searchResults, setSearchResults] = useState<StockScreenItem[]>([]);
  const [searching, setSearching] = useState(false);

  React.useEffect(() => {
    if (!open || !figi) return;
    let cancelled = false;
    setLoading(true);
    setLoadFailed(false);
    setLoadedFigi(null);
    setError(null);
    setSupportedMetrics([]);
    setPreferredMetric("");
    setPeers([]);
    setTargetMarketCap(null);
    setTargetCurrency(null);
    setTargetValuationDate(null);
    setTargetPrimaryMetric(null);
    setTargetIssuerKey(null);
    setSearchKeyword("");
    setSearchResults([]);
    apiClient.get<CustomPeerGroup>(`valuation/${encodeURIComponent(figi)}/custom-peers`)
      .then(res => {
        if (cancelled) return;
        setSupportedMetrics(res.data.supportedMetrics ?? []);
        setPreferredMetric(res.data.preferredMetric ?? "");
        setPeers(res.data.peers ?? []);
        setTargetMarketCap(res.data.targetMarketCap ?? null);
        setTargetCurrency(res.data.targetCurrency ?? null);
        setTargetValuationDate(res.data.targetValuationDate ?? null);
        setTargetPrimaryMetric(res.data.targetPrimaryMetric ?? null);
        setTargetIssuerKey(res.data.targetIssuerKey ?? figi);
        setLoadedFigi(figi);
      })
      .catch(err => {
        if (!cancelled) {
          setLoadFailed(true);
          setError(err?.response?.data?.message || err?.message || "讀取自訂對標設定失敗。");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [open, figi, reloadSeq]);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    const q = searchKeyword.trim();
    if (!q) return;
    setSearching(true);
    setError(null);
    try {
      const res = await apiClient.get<StockScreenResponse>(apiConfig.getStockScreen.url, {
        params: { symbol: q, page: 0, size: 10 },
      });
      setSearchResults((res.data.items ?? []).filter(item => item.figi !== figi));
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || "搜尋股票失敗。");
    } finally {
      setSearching(false);
    }
  };

  const addPeer = (item: StockScreenItem) => {
    if (peers.some(p => p.figi === item.figi)) return;
    setPeers([...peers, {
      figi: item.figi,
      symbol: item.symbol,
      name: item.name,
      industryCode: item.industryCode,
      active: true,
      tracked: true,
      valuationDate: item.valuationDate,
      currency: item.currency ?? null,
      marketCap: item.marketCap ?? null,
      marketCapTier: item.marketCapTier ?? null,
      issuerKey: item.issuerKey ?? item.figi,
      pe: item.pe,
      pb: item.pb,
      ps: item.ps,
      pfcf: item.pfcf,
      evEbitda: item.evEbitda,
    }]);
  };

  const removePeer = (peerFigi: string) => {
    setPeers(peers.filter(p => p.figi !== peerFigi));
  };

  const invalidateRelatedCaches = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["analysis-report", figi] }),
      queryClient.invalidateQueries({ queryKey: ["custom-peers", figi] }),
      queryClient.invalidateQueries({ queryKey: ["stock-screen"] }),
    ]);
  };

  const canMutate = !loading && !saving && !loadFailed && loadedFigi === figi;
  const activeMetricCode = (preferredMetric || targetPrimaryMetric || "").toUpperCase() || null;
  const resolvedTargetIssuer = targetIssuerKey || figi;
  const selfOrSiblingPeers = peers.filter(p => p.figi === figi || (p.issuerKey || p.figi) === resolvedTargetIssuer);
  const canEvaluateDraft = Boolean(targetValuationDate && activeMetricCode);

  // 與後端 StockValuationService 對齊：排除自身／同發行人兄弟股類、按發行人鍵去重、檢查同日快照與目標指標有效正值，並以發行人 USD 中位市值判定 10 倍落差
  const issuerGroups = new Map<string, CustomPeerItem[]>();
  if (canEvaluateDraft) {
    for (const p of peers) {
      if (!p.active || !p.tracked) continue;
      if (p.figi === figi || (p.issuerKey || p.figi) === resolvedTargetIssuer) continue;
      if (p.valuationDate !== targetValuationDate) continue;
      const key = p.issuerKey || p.figi;
      const existing = issuerGroups.get(key);
      if (existing) existing.push(p);
      else issuerGroups.set(key, [p]);
    }
  }

  const validIssuers: Array<{ key: string; label: string; usdCapMedian: number | null }> = [];
  issuerGroups.forEach((group, key) => {
    const metricAvailable = group.some(p => {
      const val = peerMetricValue(p, activeMetricCode);
      return val != null && Number.isFinite(val) && val > 0;
    });
    if (!metricAvailable) return;
    const usdCaps = group
      .filter(p => p.currency?.toUpperCase() === "USD" && p.marketCap != null && Number.isFinite(p.marketCap) && p.marketCap > 0)
      .map(p => p.marketCap as number);
    validIssuers.push({
      key,
      label: group.map(p => p.symbol).join("/"),
      usdCapMedian: medianOf(usdCaps),
    });
  });

  const validIssuerCount = validIssuers.length;
  const tooFewEffectivePeers = canEvaluateDraft && peers.length > 0 && validIssuerCount < 3;
  const targetIsUsd = targetCurrency?.toUpperCase() === "USD";
  const capMismatchLabels = canEvaluateDraft && targetIsUsd && targetMarketCap != null && Number.isFinite(targetMarketCap) && targetMarketCap > 0
    ? validIssuers
      .filter(issuer => {
        if (issuer.usdCapMedian == null || issuer.usdCapMedian <= 0) return false;
        const larger = Math.max(targetMarketCap, issuer.usdCapMedian);
        const smaller = Math.min(targetMarketCap, issuer.usdCapMedian);
        return larger > smaller * 10;
      })
      .map(issuer => issuer.label)
    : [];

  const canSave = canMutate && selfOrSiblingPeers.length === 0;

  const handleSave = async () => {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    try {
      await apiClient.put<CustomPeerGroup>(`valuation/${encodeURIComponent(figi)}/custom-peers`, {
        peerFigis: peers.map(p => p.figi),
        preferredMetric: preferredMetric || null,
      });
      await invalidateRelatedCaches();
      onClose();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || "儲存自訂對標失敗。");
    } finally {
      setSaving(false);
    }
  };

  const handleClear = async () => {
    if (!canMutate) return;
    setSaving(true);
    setError(null);
    try {
      if (typeof apiClient.delete === "function") {
        await apiClient.delete<CustomPeerGroup>(`valuation/${encodeURIComponent(figi)}/custom-peers`);
      } else {
        await apiClient.put<CustomPeerGroup>(`valuation/${encodeURIComponent(figi)}/custom-peers`, {
          peerFigis: [],
          preferredMetric: null,
        });
      }
      await invalidateRelatedCaches();
      onClose();
    } catch (err: any) {
      setError(err?.response?.data?.message || err?.message || "清除自訂對標失敗。");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{symbol ? `${symbol} · ` : ""}設定多業務自訂對標同業</DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          針對跨界多業務公司（如 AMZN、TSLA），可指定至少 3 家不同發行人之有效對手作為同業比較基準；未滿 3 家有效樣本時將自動退回預設產業規模分層。
        </Typography>
        {error && (
          <Alert
            severity="error"
            sx={{ mb: 1.5 }}
            action={loadFailed ? <Button color="inherit" size="small" onClick={() => setReloadSeq(s => s + 1)}>重試載入</Button> : undefined}
          >
            {error}
          </Alert>
        )}
        {loading ? (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ py: 2 }}>
            <CircularProgress size={20} /><Typography>讀取設定中…</Typography>
          </Stack>
        ) : loadFailed ? (
          <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
            目前無法讀取既有自訂對標設定；為避免誤覆寫現有名單，請重試載入或先取消。
          </Typography>
        ) : (
          <Stack spacing={2}>
            <FormControl size="small" fullWidth>
              <InputLabel id="custom-peer-metric-label">對標估值指標（選填覆寫）</InputLabel>
              <Select
                labelId="custom-peer-metric-label"
                label="對標估值指標（選填覆寫）"
                value={preferredMetric}
                onChange={e => setPreferredMetric(e.target.value)}
              >
                <MenuItem value="">自動跟隨本股當日主指標</MenuItem>
                {supportedMetrics.map(code => (
                  <MenuItem key={code} value={code}>{metricLabel(code)}</MenuItem>
                ))}
              </Select>
            </FormControl>

            <Box component="form" onSubmit={e => void handleSearch(e)}>
              <Stack direction="row" spacing={1}>
                <TextField
                  size="small"
                  fullWidth
                  label="搜尋對標股票代碼或名稱"
                  value={searchKeyword}
                  onChange={e => setSearchKeyword(e.target.value)}
                />
                <Button type="submit" variant="outlined" disabled={searching}>搜尋</Button>
              </Stack>
            </Box>

            {searchResults.length > 0 && (
              <Paper variant="outlined" sx={{ p: 1, maxHeight: 160, overflowY: "auto" }}>
                <Stack spacing={0.5}>
                  {searchResults.map(item => {
                    const added = peers.some(p => p.figi === item.figi);
                    return (
                      <Stack key={item.figi} direction="row" justifyContent="space-between" alignItems="center">
                        <Typography variant="body2">
                          <b>{item.symbol}</b> · {item.name}
                          {item.marketCap != null ? ` · ${formatCompactMarketCap(item.marketCap, item.currency)}` : ""}
                        </Typography>
                        <Button size="small" disabled={added} onClick={() => addPeer(item)}>
                          {added ? "已加入" : "加入"}
                        </Button>
                      </Stack>
                    );
                  })}
                </Stack>
              </Paper>
            )}

            {selfOrSiblingPeers.length > 0 && (
              <Alert severity="error">
                所選對手（{selfOrSiblingPeers.map(p => p.symbol).join("、")}）與本股屬同一發行人，無法作為對標儲存，請先移除。
              </Alert>
            )}
            {!canEvaluateDraft && peers.length > 0 && (
              <Alert severity="info">
                本股尚無最新收市估值快照或主指標，暫無法預判對標有效發行人家數與市值落差。
              </Alert>
            )}
            {tooFewEffectivePeers && (
              <Alert severity="warning">
                目前僅有 {validIssuerCount} 家當日有效獨立發行人（{metricLabel(activeMetricCode)}）（未滿 3 家），儲存後將暫不生效並自動退回預設產業規模分層。
              </Alert>
            )}
            {capMismatchLabels.length > 0 && (
              <Alert severity="warning">
                所選對手（{capMismatchLabels.join("、")}）與本股 USD 市值相差逾 10 倍，儲存後仍可計算但請留意跨量級規模溢價差異。
              </Alert>
            )}

            <Box>
              <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.8 }}>
                已選對標名單（{peers.length} 檔，需至少 3 家有效發行人）
              </Typography>
              {peers.length === 0 ? (
                <Typography variant="body2" color="text.secondary">目前未設定自訂對標，採用預設產業規模分層。</Typography>
              ) : (
                <Stack spacing={0.8}>
                  {peers.map(p => (
                    <Stack key={p.figi} direction="row" justifyContent="space-between" alignItems="center"
                      sx={{ p: 0.8, border: "1px solid", borderColor: "divider", borderRadius: 1 }}>
                      <Box>
                        <Typography variant="body2">
                          <b>{p.symbol}</b> {p.name ? `· ${p.name}` : ""}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {p.marketCap != null ? `市值 ${formatCompactMarketCap(p.marketCap, p.currency)}` : "尚無市值快照"}
                          {!p.tracked || !p.active ? " · 已停追或非活躍（不計入有效樣本）" : ""}
                        </Typography>
                      </Box>
                      <Button size="small" color="error" onClick={() => removePeer(p.figi)}>移除</Button>
                    </Stack>
                  ))}
                </Stack>
              )}
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button color="warning" onClick={() => void handleClear()} disabled={!canMutate}>
          清除並還原預設分層
        </Button>
        <Button onClick={onClose} disabled={saving}>取消</Button>
        <Button variant="contained" onClick={() => void handleSave()} disabled={!canSave}>
          儲存自訂對標
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export function ValuationReferencePanel({
  title, description, reference, currency, figi, symbol, fundamentals, isHistoricalDate, peerRelativeStrength,
}: {
  title: string;
  description: string;
  reference: ValuationReference | null | undefined;
  currency: string | null | undefined;
  figi?: string;
  symbol?: string | null;
  fundamentals?: FundamentalSnapshot | null;
  isHistoricalDate?: boolean;
  peerRelativeStrength?: PeerRelativeStrength | null;
}) {
  const { t } = useTranslation();
  const [dialogOpen, setDialogOpen] = useState(false);
  const available = reference?.status === "AVAILABLE";
  const assumption = reference?.kind === "INDUSTRY_ASSUMPTION";
  const historical = reference?.kind === "HISTORICAL_MARKET";
  const peer = reference?.kind === "PEER_MARKET";

  const scopeLabel = peer && reference?.peerScope
    ? (PEER_SCOPE_LABELS[reference.peerScope] ?? reference.peerScope)
    : null;
  const tierShort = peer && reference?.targetMarketCapTier
    ? (TIER_SHORT_LABELS[reference.targetMarketCapTier] ?? reference.targetMarketCapTier)
    : null;

  const selfRevYoy = fundamentals?.revenueGrowthYoy?.status === "AVAILABLE" ? fundamentals.revenueGrowthYoy.value : null;
  const selfRoe = fundamentals?.roe?.status === "AVAILABLE" ? fundamentals.roe.value : null;
  const selfFcfMargin = fundamentals && !fundamentals.financialInstitution && fundamentals.fcfMargin?.status === "AVAILABLE"
    ? fundamentals.fcfMargin.value : null;

  const hasPeerFundamentals = peer && available && reference && (
    reference.peerRevenueGrowthYoyMedian != null
    || reference.peerRoeMedian != null
    || reference.peerFcfMarginMedian != null
  );

  return <Paper component="section" aria-label={title} variant="outlined" sx={{ p: 2.5, height: "100%" }}>
    <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
      <Box>
        <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
          <Typography variant="h6" fontWeight={700}>{title}</Typography>
          {scopeLabel && (
            <Chip
              size="small"
              color={reference?.peerScope === "CUSTOM_PEERS" ? "secondary" : "primary"}
              variant="outlined"
              label={reference?.peerScope === "SAME_TIER" && tierShort ? `${scopeLabel}（${tierShort}）` : scopeLabel}
            />
          )}
        </Stack>
      </Box>
      {peer && figi && !isHistoricalDate && reference?.status !== "NOT_APPLICABLE" && (
        <Button size="small" variant="outlined" onClick={() => setDialogOpen(true)}>
          設定自訂對標
        </Button>
      )}
    </Stack>

    <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 2 }}>{description}</Typography>
    <Stack spacing={0.7}>
      <Typography>狀態：{statusLabel(reference?.status)}</Typography>
      <Typography>指標：{metricLabel(reference?.metricCode)}</Typography>
      {peer && reference?.peerFallbackReason && (
        <Typography variant="caption" color="warning.main">
          {reasonLabel(t, reference.peerFallbackReason)}
        </Typography>
      )}
      {available && reference ? <>
        <Typography>{assumption ? "假設倍數" : "市場倍數區間"}：<b>{formatNumber(reference.multipleLow)}–{formatNumber(reference.multipleHigh)} 倍</b></Typography>
        {historical && <Typography>
          市場中位數：{formatNumber(reference.multipleMedian)} 倍 · {reference.windowSampleCount != null
            ? `有效月份：${reference.sampleCount ?? "—"}／${reference.windowSampleCount} 個月`
            : `樣本數：${reference.sampleCount ?? "—"}`}
        </Typography>}
        {peer && <Typography>
          市場中位數：{formatNumber(reference.multipleMedian)} 倍 · 同日有效公司數：{reference.sampleCount ?? "—"}
          {reference.sampleEndDate ? `（${reference.sampleEndDate}）` : ""}
        </Typography>}
        {peer && reference.industryAllMedianMultiple != null && reference.peerScope !== "ALL_INDUSTRY" && (
          <Typography variant="body2" color="text.secondary">
            全產業{reference.industryAllMetricCode ? ` ${metricLabel(reference.industryAllMetricCode)} ` : ""}中位數副標：
            {formatNumber(reference.industryAllMedianMultiple)} 倍
            {reference.industryAllSampleCount != null ? `（共 ${reference.industryAllSampleCount} 家，僅供對照未混入參考帶）` : "（僅供對照）"}
          </Typography>
        )}
        {!assumption && reference.currentMultiple != null && (
          <Typography variant="body2">
            當期倍數：{formatNumber(reference.currentMultiple)} 倍
            {reference.currentPercentile != null ? ` · 經驗分位排名：P${Math.round(reference.currentPercentile)}` : ""}
          </Typography>
        )}
        {historical && reference.sampleStartDate && reference.sampleEndDate && (
          <Typography variant="body2" color="text.secondary">
            實際樣本起迄：{reference.sampleStartDate} ～ {reference.sampleEndDate}
          </Typography>
        )}
        {historical && reference.excludedSampleCount != null && reference.excludedSampleCount > 0 && (
          <Typography variant="body2" color="text.secondary">
            已排除月份：{reference.excludedSampleCount} 個月（虧損／分母非正或缺資料月份不納入分位數）
          </Typography>
        )}
        <Typography>{assumption ? "假設價格區間" : "價格參考區間"}：<b>{formatNumber(reference.priceLow)}–{formatNumber(reference.priceHigh)}</b> {currency ?? ""}</Typography>
        {reference.priceMedian != null && (
          <Typography variant="body2" color="text.secondary">
            {assumption ? "假設區間中點" : "中位倍數參考價"}：{formatNumber(reference.priceMedian)} {currency ?? ""}
          </Typography>
        )}
        {reference.priceLow == null || reference.priceHigh == null ? <Typography variant="body2" color="text.secondary">倍數可用，但價格換算條件不足；不以零補值。</Typography> : null}

        {hasPeerFundamentals && (
          <Box sx={{ mt: 0.8, p: 1.2, bgcolor: "action.hover", borderRadius: 1 }}>
            <Typography variant="caption" fontWeight={700} display="block" sx={{ mb: 0.4 }}>
              同業成長與獲利中位數對照（分辨高成長溢價 vs 價值陷阱）
            </Typography>
            <Stack spacing={0.3}>
              {reference.peerRevenueGrowthYoyMedian != null && (
                <Typography variant="caption" color="text.secondary">
                  營收 YoY 中位數：<b>{formatPctValue(reference.peerRevenueGrowthYoyMedian, true)}</b>
                  {reference.peerRevenueGrowthCount ? `（${reference.peerRevenueGrowthCount}家）` : ""}
                  {selfRevYoy != null ? ` · 本股：${formatPctValue(selfRevYoy, true)}` : ""}
                </Typography>
              )}
              {reference.peerRoeMedian != null && (
                <Typography variant="caption" color="text.secondary">
                  ROE 中位數：<b>{formatPctValue(reference.peerRoeMedian)}</b>
                  {reference.peerRoeCount ? `（${reference.peerRoeCount}家）` : ""}
                  {selfRoe != null ? ` · 本股：${formatPctValue(selfRoe)}` : ""}
                </Typography>
              )}
              {reference.peerFcfMarginMedian != null && (
                <Typography variant="caption" color="text.secondary">
                  FCF Margin 中位數：<b>{formatPctValue(reference.peerFcfMarginMedian)}</b>
                  {reference.peerFcfMarginCount ? `（${reference.peerFcfMarginCount}家）` : ""}
                  {selfFcfMargin != null ? ` · 本股：${formatPctValue(selfFcfMargin)}` : ""}
                </Typography>
              )}
            </Stack>
          </Box>
        )}

        {peer && reference.warningCodes && reference.warningCodes.length > 0 && (
          <Alert severity="info" sx={{ py: 0.3, px: 1 }}>
            {reference.warningCodes.map(c => PEER_WARNING_LABELS[c] ?? c).join(" ")}
          </Alert>
        )}
      </> : <>
        <Typography color="text.secondary">{reasonLabel(t, reference?.reasonCode)}</Typography>
        {historical && reference?.windowSampleCount != null && reference.sampleCount != null && (
          <Typography variant="body2" color="text.secondary">
            視窗覆蓋：有效 {reference.sampleCount}／{reference.windowSampleCount} 個月
            {reference.excludedSampleCount ? `（已排除 ${reference.excludedSampleCount} 個虧損或缺資料月份）` : ""}
          </Typography>
        )}
      </>}
      {peerRelativeStrength && (
        <Box data-testid="peer-relative-strength-panel" sx={{ mt: 0.8, p: 1.2, bgcolor: "background.default", border: "1px solid", borderColor: "divider", borderRadius: 1 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 0.6 }}>
            <Typography variant="caption" fontWeight={700}>
              {t("analysisReport.peerRelativeStrength.title")}
            </Typography>
            {peerRelativeStrength.relativeLabelCode && (
              <Chip
                size="small"
                color={
                  peerRelativeStrength.relativeLabelCode === "SECTOR_STRONG_STOCK_LEADING" ? "success"
                    : peerRelativeStrength.relativeLabelCode === "SECTOR_STRONG_STOCK_LAGGING" ? "info"
                      : peerRelativeStrength.relativeLabelCode === "SECTOR_WEAK_COLLECTIVE_DECLINE" ? "error"
                        : peerRelativeStrength.relativeLabelCode === "SECTOR_WEAK_STOCK_OUTPERFORMING" ? "warning"
                          : "default"
                }
                label={t(`analysisReport.peerRelativeStrength.labels.${peerRelativeStrength.relativeLabelCode}`, {
                  defaultValue: peerRelativeStrength.relativeLabelCode,
                })}
              />
            )}
          </Stack>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
            {peerRelativeStrength.peerBasis === "CUSTOM_PEERS_MEDIAN"
              ? t("analysisReport.peerRelativeStrength.basisCustom")
              : t("analysisReport.peerRelativeStrength.basisIndustry")}
            {peerRelativeStrength.tradeDate ? ` · ${peerRelativeStrength.tradeDate}` : ""}
            {peerRelativeStrength.peerSampleCount20d != null
              ? ` · ${t("analysisReport.peerRelativeStrength.sampleCount", { count: peerRelativeStrength.peerSampleCount20d })}`
              : ""}
          </Typography>
          <Stack spacing={0.3}>
            <Typography variant="caption" color="text.secondary">
              {t("analysisReport.peerRelativeStrength.window20d")}：
              {t("analysisReport.peerRelativeStrength.stockLabel")} <b>{formatPctValue(peerRelativeStrength.stockReturn20d, true)}</b>
              {" · "}
              {t("analysisReport.peerRelativeStrength.peerLabel")} <b>{formatPctValue(peerRelativeStrength.peerReturn20d, true)}</b>
              {" · "}
              {t("analysisReport.peerRelativeStrength.spyLabel")} <b>{formatPctValue(peerRelativeStrength.spyReturn20d, true)}</b>
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {t("analysisReport.peerRelativeStrength.window60d")}：
              {t("analysisReport.peerRelativeStrength.stockLabel")} <b>{formatPctValue(peerRelativeStrength.stockReturn60d, true)}</b>
              {" · "}
              {t("analysisReport.peerRelativeStrength.peerLabel")} <b>{formatPctValue(peerRelativeStrength.peerReturn60d, true)}</b>
              {" · "}
              {t("analysisReport.peerRelativeStrength.spyLabel")} <b>{formatPctValue(peerRelativeStrength.spyReturn60d, true)}</b>
            </Typography>
            {(peerRelativeStrength.sectorCmf20d != null || peerRelativeStrength.breadthAboveSma50Pct != null) && (
              <Typography variant="caption" color="text.secondary">
                {peerRelativeStrength.sectorCmf20d != null
                  ? `${t("analysisReport.peerRelativeStrength.sectorCmf")}：${formatNumber(peerRelativeStrength.sectorCmf20d, 3)}`
                  : ""}
                {peerRelativeStrength.breadthAboveSma50Pct != null
                  ? ` · ${t("analysisReport.peerRelativeStrength.breadth50")} ${formatPctValue(peerRelativeStrength.breadthAboveSma50Pct)}`
                  : ""}
                {peerRelativeStrength.breadthAboveSma200Pct != null
                  ? ` · ${t("analysisReport.peerRelativeStrength.breadth200")} ${formatPctValue(peerRelativeStrength.breadthAboveSma200Pct)}`
                  : ""}
              </Typography>
            )}
          </Stack>
        </Box>
      )}
      <Typography variant="caption" color="text.secondary">{assumption
        ? "這是經確認的產業假設情境，不是市場實際成交估值。"
        : historical
          ? "歷史倍數以各月末收盤價配對同財季最新修訂財報計算（非當日投資者即時已知之 Point-in-Time 回測值），不等同內在價值或買賣指令。"
          : "這是同交易日實際市場資料的比較基準，不等同內在價值或買賣指令。"}</Typography>
    </Stack>

    {peer && figi && dialogOpen && (
      <CustomPeerDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        figi={figi}
        symbol={symbol}
      />
    )}
  </Paper>;
}

const COMMON_INDICATORS = ["SMA20", "SMA50", "SMA200", "RSI14", "MACD", "MACD_SIGNAL", "MACD_HISTOGRAM", "ATR14"];
export function TechnicalIndicatorsPanel({ detail, dataThrough, momentum }: { detail?: MomentumAnalysis | null; dataThrough?: string | null; momentum?: MomentumVolume | null }) {
  const { t } = useTranslation();
  const entries = Object.entries(detail?.indicators ?? {});
  const commonEntries = COMMON_INDICATORS
    .map(code => entries.find(([k]) => k === code))
    .filter((entry): entry is [string, MetricResult<number>] => Boolean(entry));
  const extraEntries = entries
    .filter(([code]) => !COMMON_INDICATORS.includes(code))
    .sort(([a], [b]) => a.localeCompare(b));
  const renderRows = (rows: Array<[string, MetricResult<number>]>) => <Table size="small">
    <TableHead><TableRow>
      <TableCell sx={{ whiteSpace: "nowrap" }}>{t("analysisReport.indicator")}</TableCell>
      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{t("analysisReport.indicatorValue")}</TableCell>
      <TableCell sx={{ whiteSpace: "nowrap" }}>{t("analysisReport.interpretation")}</TableCell>
    </TableRow></TableHead>
    <TableBody>{rows.map(([code, result]) => {
      const interpretation = detail?.interpretations?.[code];
      return <TableRow key={code}>
        <TableCell sx={{ minWidth: 150 }}><Typography variant="body2" sx={{ fontWeight: 700 }}>{t(`analysisReport.indicatorNames.${code}`, { defaultValue: code })}</Typography>
          <Typography variant="caption" color="text.secondary">{t(`analysisReport.indicatorDefinitions.${code}`, { defaultValue: t("analysisReport.technicalDefinition") })}</Typography>
          {interpretation?.params.period != null && <Typography variant="caption" display="block">{t("analysisReport.period")}: {interpretation.params.period}</Typography>}
          {interpretation?.params.requiredBars != null && <Typography variant="caption" display="block">{t("analysisReport.priceBars")}: {interpretation.params.availableBars ?? "—"} / {interpretation.params.requiredBars}</Typography>}
        </TableCell>
        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{result.status === "AVAILABLE" ? <>{number(result.value)} {interpretation?.params.unit ? t(`analysisReport.units.${interpretation.params.unit}`, { defaultValue: String(interpretation.params.unit) }) : ""}</> : readableState(t, result.status)}</TableCell>
        <TableCell><Typography variant="body2">{result.status === "AVAILABLE" && interpretation?.status === "AVAILABLE"
          ? t(`analysisReport.interpretations.${interpretation.code}`, { ...interpretation.params, defaultValue: t("analysisReport.interpretationUnavailable") })
          : result.status === "AVAILABLE" ? t("analysisReport.interpretationUnavailable")
            : result.reasons.map((reason) => readableReason(t, reason)).join(" · ") || t("analysisReport.unavailableExplanation")}</Typography>
        </TableCell>
      </TableRow>;
    })}</TableBody>
  </Table>;
  return <Paper variant="outlined" sx={{ p: 2.5 }}>
    <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t("analysisReport.technicalIndicators")}</Typography>
    <Typography variant="body2" color="text.secondary">{t("analysisReport.priceDataThrough")}: {dataThrough ?? "—"} · {t("analysisReport.indicatorTimeframe")}</Typography>
    <Typography variant="body2" color="text.secondary" sx={{ my: 1 }}>{t("analysisReport.indicatorLimit")}</Typography>
    <Divider sx={{ my: 1.5 }} />
    {entries.length ? <Box sx={{ overflowX: "auto" }}>
      {renderRows(commonEntries)}
      <Accordion disableGutters elevation={0}>
        <AccordionSummary expandIcon={<ExpandMore />}><Typography>{t("analysisReport.moreIndicators")}</Typography></AccordionSummary>
        <AccordionDetails sx={{ px: 0 }}>{renderRows(extraEntries)}</AccordionDetails>
      </Accordion>
    </Box> : <Typography color="text.secondary">{t("analysisReport.indicatorsUnavailable")}</Typography>}
    {momentum && <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap sx={{ mt: 2 }}>
      {[["volumeVs20Day", momentum.volumeVs20Day, "×"], ["return20", momentum.return20, "%"], ["return60", momentum.return60, "%"], ["drawdownFrom252High", momentum.drawdownFrom252High, "%"]].map(([key, source, unit]) => {
        const result = source as MetricResult<number>;
        return <Box key={String(key)}><Typography variant="caption">{t(`analysisReport.${key}`)}</Typography>
          <Typography>{result?.status === "AVAILABLE" && result.value != null
            ? unit === "%" ? result.value.toLocaleString(undefined, { style: "percent", minimumFractionDigits: 2, maximumFractionDigits: 2 }) : `${number(result.value)}${unit}`
            : readableState(t, result?.status)}</Typography></Box>;
      })}
    </Stack>}
  </Paper>;
}
