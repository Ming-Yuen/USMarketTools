import React from "react";
import { Alert, Box, Chip, Divider, Grid, Paper, Stack, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import { InvestmentSummary, MetricResult, SummaryFlag, WatchItem } from "../services/types/dto/analysis";
import { ValuationResult } from "../services/types/dto/valuation";
import { formatNumber, metricLabel, readableReason, readableState, reasonLabel, statusLabel } from "./AnalysisPanels";

const VERDICT_DEFAULTS: Record<string, string> = {
  DEEP_UNDERVALUED: "顯著低於市場參考帶（嚴重偏低）",
  MODERATELY_UNDERVALUED: "低於市場參考下緣（合理偏低）",
  FAIR_LOWER: "位於參考帶下半部（合理偏下緣）",
  FAIR_UPPER: "位於參考帶上半部（合理偏上緣）",
  MODERATELY_OVERVALUED: "高於市場參考上緣（估值偏貴）",
  OVERVALUED_RISK: "顯著高於市場參考帶（高估風險）",
  INSUFFICIENT_EVIDENCE: "估值參考帶證據不足",
};

const BACKING_DEFAULTS: Record<string, string> = {
  STRONG: "基本面支撐強勁",
  MIXED: "基本面表現分化",
  WEAKENING: "基本面轉弱警戒",
  INSUFFICIENT_DATA: "基本面比較待補",
};

const STANCE_DEFAULTS: Record<string, string> = {
  ACCUMULATE_AT_SUPPORT: "估值偏低且具備結構支撐 · 可留意支撐區分批觀察",
  WATCH_PULLBACK_TO_BAND: "對照市場參考下緣與支撐區分批觀察，不追高",
  HOLD_TRAIL_NO_CHASE: "基本面與趨勢強勁但估值偏高 · 宜沿趨勢觀察、不追高",
  CAUTION_VALUE_TRAP: "基本面轉弱警戒 · 提防價值陷阱或盈利下修，暫緩承接",
  CAUTION_OVEREXTENSION: "估值高於市場參考上緣 · 留意溢價收斂風險",
  WAIT_FOR_STABILIZATION: "技術破位待收復、低於年線或事件受限 · 等待止穩與條件明朗",
  INSUFFICIENT_EVIDENCE: "核心錨點不足 · 僅展示單項可用指標",
};

const ANCHOR_DEFAULTS: Record<string, string> = {
  HISTORICAL_AND_PEER: "自身 5 年歷史 ＋ 同業市場（50/50 客觀合成）",
  HISTORICAL_ONLY: "單源：自身 5 年歷史分位（同業不足或不適用）",
  PEER_ONLY: "單源：同日同業市場分位（歷史有效樣本不足）",
  NONE: "尚無可換算之客觀市場價格錨點",
};

const RISK_DEFAULTS: Record<string, string> = {
  EARNINGS_IMMINENT_BLOCK: "財報或重大事件臨近，新入場條件受限",
  PROTECTIVE_STOP_OR_RECLAIM_PENDING: "股價已跌穿保護止損或低於入場區，須先收復止穩",
  FORWARD_EARNINGS_CONTRACTION: "市場共識預期年度 EPS 較當期 TTM 顯著下滑或轉虧（提防價值陷阱，共識口徑僅供對照）",
  EARNINGS_FORECAST_DOWNGRADE_90D: "近 90 日市場共識預期 EPS 顯著下修逾 10%",
  SWUNG_TO_LOSS: "TTM 每股盈利較去年同期由盈轉虧",
  NEGATIVE_TRAILING_EARNINGS: "當期 TTM 每股盈利為負（虧損狀態）",
  NEGATIVE_FCF_BURN: "當期 TTM 自由現金流為負（現金消耗）",
  HIGH_LEVERAGE_DEBT_EBITDA: "淨負債／EBITDA 偏高，財務槓桿壓力較大",
  REVENUE_CONTRACTION: "TTM 營業收入較去年同期衰退",
  SHARE_DILUTION_YOY: "流通股數較去年同期淨增稀釋逾 3%",
  VALUATION_ABOVE_UPPER_BAND: "現價高於客觀市場參考帶上緣",
  BELOW_SMA200_DOWNTREND: "週線處於下降趨勢且日線收盤低於 SMA200 年線",
  ANCHOR_DIVERGENCE: "自身歷史中位價與同業中位價差距較大，請一併對照下方分項",
};

const INDUSTRY_NA_REASON_CODES = new Set([
  "PFCF_NOT_APPLICABLE_TO_INDUSTRY",
  "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY",
]);

const formatSignedRatioPct = (ratio?: number | null): string => {
  if (ratio == null || !Number.isFinite(ratio)) return "—";
  const pct = ratio * 100;
  return (pct >= 0 ? "+" : "") + pct.toFixed(2) + "%";
};

function verdictChipColor(verdict?: string | null): "success" | "info" | "warning" | "error" | "default" {
  if (verdict === "DEEP_UNDERVALUED" || verdict === "MODERATELY_UNDERVALUED") return "success";
  if (verdict === "FAIR_LOWER" || verdict === "FAIR_UPPER") return "info";
  if (verdict === "MODERATELY_OVERVALUED") return "warning";
  if (verdict === "OVERVALUED_RISK") return "error";
  return "default";
}

function backingChipColor(backing?: string | null): "success" | "warning" | "error" | "default" {
  if (backing === "STRONG") return "success";
  if (backing === "WEAKENING") return "error";
  return "default";
}

function isPrimaryCodeMatch(cardKey: string, primaryMetric?: string | null): boolean {
  if (!primaryMetric) return false;
  const upper = primaryMetric.toUpperCase();
  if (cardKey === "evEbitda") return upper === "EV_EBITDA";
  return cardKey.toUpperCase() === upper;
}

export function ExecutiveSummaryPanel({ valuation, summaryResult }: {
  valuation?: ValuationResult | null;
  summaryResult?: MetricResult<InvestmentSummary> | null;
}) {
  const { t } = useTranslation();
  const summary = summaryResult?.status === "AVAILABLE" ? summaryResult.value : null;
  const price = valuation?.price ?? null;
  const currency = summary?.fairValue?.currency ?? valuation?.currency ?? "";
  const band = summary?.fairValue ?? null;
  const hasBand = price != null && band != null && band.low != null && band.mid != null && band.high != null && band.mid > 0;

  const metricEntries = Object.entries(valuation?.metrics ?? {});
  const visibleMetrics = metricEntries.filter(([, m]) => !(m.status !== "AVAILABLE" && m.reasonCode != null && INDUSTRY_NA_REASON_CODES.has(m.reasonCode)));
  const collapsedIndustryNa = metricEntries.filter(([, m]) => m.status !== "AVAILABLE" && m.reasonCode != null && INDUSTRY_NA_REASON_CODES.has(m.reasonCode));

  const renderRiskText = (flag: SummaryFlag) => {
    let fallback = RISK_DEFAULTS[flag.code] ?? flag.code;
    if (flag.code === "EARNINGS_IMMINENT_BLOCK" && flag.params?.eventDate) {
      fallback = `財報或重大事件臨近（${String(flag.params.eventDate)}），新入場條件受限`;
    } else if (flag.code === "FORWARD_EARNINGS_CONTRACTION") {
      const fp = flag.params?.fiscalPeriod ? `（${String(flag.params.fiscalPeriod)}）` : "";
      if (flag.params?.swungToLoss) {
        fallback = `市場共識預期年度 EPS${fp}轉為虧損或零盈利（共識預期口徑對比，提防價值陷阱）`;
      } else if (flag.params?.impliedEpsGrowth != null) {
        const pct = (Number(flag.params.impliedEpsGrowth) * 100).toFixed(2);
        fallback = `市場共識預期年度 EPS${fp}較當期 TTM 低 ${Math.abs(Number(pct)).toFixed(2)}%（共識預期口徑對比）`;
      }
    } else if (flag.code === "EARNINGS_FORECAST_DOWNGRADE_90D" && flag.params?.epsRevision90d != null) {
      const fp = flag.params?.fiscalPeriod ? `（${String(flag.params.fiscalPeriod)}）` : "";
      const pct = (Number(flag.params.epsRevision90d) * 100).toFixed(2);
      fallback = `近 90 日市場共識預期 EPS${fp}下修 ${pct}%`;
    }
    return t(`analysisReport.executiveSummary.risks.${flag.code}`, { ...(flag.params ?? {}), defaultValue: fallback });
  };

  const renderWatchText = (item: WatchItem) => {
    const p = item.params ?? {};
    let fallback = item.code;
    if (item.code === "WATCH_REFERENCE_LOW_BAND") {
      fallback = `低接觀察門檻（參考下緣）：${formatNumber(Number(p.priceLow))} ${String(p.currency ?? currency)}（中樞 ${formatNumber(Number(p.priceMid))}）`;
    } else if (item.code === "WATCH_TECHNICAL_SUPPORT") {
      const tfLabel = readableState(t, String(p.timeframe ?? "DAILY"));
      fallback = `配對${tfLabel}支撐區：${formatNumber(Number(p.lower))}–${formatNumber(Number(p.upper))} ${String(p.currency ?? currency)}`;
    } else if (item.code === "WATCH_RECLAIM_STABILIZATION") {
      fallback = "觀察股價重新站回支撐區並確認止跌結構";
    } else if (item.code === "WATCH_EVENT_WINDOW") {
      fallback = `留意 ${String(p.eventDate ?? "")} 事件落地後的波動與財報變化`;
    } else if (item.code === "WATCH_FUNDAMENTAL_STABILIZATION") {
      fallback = "追蹤下季營收、現金流或槓桿指標是否止跌回穩";
    }
    return t(`analysisReport.executiveSummary.watchItems.${item.code}`, {
      ...p,
      priceLow: p.priceLow != null ? formatNumber(Number(p.priceLow)) : undefined,
      priceMid: p.priceMid != null ? formatNumber(Number(p.priceMid)) : undefined,
      lower: p.lower != null ? formatNumber(Number(p.lower)) : undefined,
      upper: p.upper != null ? formatNumber(Number(p.upper)) : undefined,
      timeframe: p.timeframe ? readableState(t, String(p.timeframe)) : undefined,
      defaultValue: fallback,
    });
  };

  const spreadVal = summary?.yieldSpreadVs10y?.status === "AVAILABLE" ? summary.yieldSpreadVs10y.value : null;

  return <Paper component="section" aria-label="綜合估值與觀察結論" variant="outlined" sx={{ p: 2.5 }}>
    <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={1.5}>
      <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
        <Typography variant="subtitle1" fontWeight={800}>
          {t("analysisReport.executiveSummary.title", { defaultValue: "綜合估值與觀察結論" })}
        </Typography>
        {summary && (
          <Chip
            size="small"
            color={verdictChipColor(summary.verdict)}
            label={t(`analysisReport.executiveSummary.verdicts.${summary.verdict}`, {
              defaultValue: VERDICT_DEFAULTS[summary.verdict] ?? summary.verdict,
            })}
            sx={{ fontWeight: 700 }}
          />
        )}
        {summary && summary.fundamentalBacking && (
          <Chip
            size="small"
            variant="outlined"
            color={backingChipColor(summary.fundamentalBacking)}
            label={t(`analysisReport.executiveSummary.backing.${summary.fundamentalBacking}`, {
              defaultValue: BACKING_DEFAULTS[summary.fundamentalBacking] ?? summary.fundamentalBacking,
            })}
          />
        )}
        {summary?.eventGateBlocked && (
          <Chip
            size="small"
            color="warning"
            label={summary.nextBlockingEventDate ? `事件門鎖：${summary.nextBlockingEventDate}` : "事件門鎖受限"}
          />
        )}
      </Stack>
      {valuation?.primaryMetric && (
        <Typography variant="caption" color="text.secondary">
          主估值指標：<b>{metricLabel(valuation.primaryMetric)}</b>
          {summary ? " · 錨點來源：" + t(`analysisReport.executiveSummary.anchorSources.${summary.anchorSource}`, {
            defaultValue: ANCHOR_DEFAULTS[summary.anchorSource] ?? summary.anchorSource,
          }) : ""}
        </Typography>
      )}
    </Stack>

    {summary && (
      <Box sx={{ mt: 1.5, p: 1.8, bgcolor: "action.hover", borderRadius: 1.5 }}>
        {hasBand && band ? (() => {
          const low = band.low as number;
          const mid = band.mid as number;
          const high = band.high as number;
          const minBound = Math.min(price, low) * 0.9;
          const maxBound = Math.max(price, high) * 1.1;
          const span = maxBound - minBound || 1;
          const bandLeft = ((low - minBound) / span) * 100;
          const bandWidth = Math.max(2, ((high - low) / span) * 100);
          const midPos = ((mid - minBound) / span) * 100;
          const pinPos = Math.min(98, Math.max(2, ((price - minBound) / span) * 100));
          const discount = summary.discountToMid;
          return <>
            <Grid container spacing={2} alignItems="center">
              <Grid size={{ xs: 12, sm: 4 }}>
                <Typography variant="caption" color="text.secondary">低接觀察門檻（參考下緣）</Typography>
                <Typography variant="subtitle1" fontWeight={700} color="success.main">
                  {formatNumber(low)} {currency}
                </Typography>
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <Typography variant="caption" color="text.secondary">客觀市場參考中樞</Typography>
                <Typography variant="subtitle1" fontWeight={800}>
                  {formatNumber(mid)} {currency}{" "}
                  {discount != null && (
                    <Typography component="span" variant="caption" fontWeight={700}
                      color={discount > 0 ? "success.main" : discount < 0 ? "warning.main" : "text.secondary"}>
                      （{discount >= 0 ? `較中樞折價 ${formatSignedRatioPct(discount)}` : `較中樞溢價 ${(Math.abs(discount) * 100).toFixed(2)}%`}）
                    </Typography>
                  )}
                </Typography>
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <Typography variant="caption" color="text.secondary">上檔參考上限（參考上緣）</Typography>
                <Typography variant="subtitle1" fontWeight={700} color="warning.main">
                  {formatNumber(high)} {currency}
                </Typography>
              </Grid>
            </Grid>

            <Box sx={{ position: "relative", height: 12, bgcolor: "background.paper", borderRadius: 6, mt: 1.2, border: "1px solid", borderColor: "divider", overflow: "hidden" }}>
              <Box sx={{ position: "absolute", left: `${bandLeft}%`, width: `${bandWidth}%`, top: 0, bottom: 0, bgcolor: "rgba(25, 118, 210, 0.22)" }} />
              <Box sx={{ position: "absolute", left: `${midPos}%`, top: 0, bottom: 0, width: 2, bgcolor: "#1976d2" }} />
              <Box sx={{
                position: "absolute", left: `calc(${pinPos}% - 5px)`, top: 1, width: 10, height: 10, borderRadius: "50%",
                bgcolor: price < low ? "#16a34a" : price > high ? "#dc2626" : "#1976d2", border: "2px solid #fff",
              }} />
            </Box>

            <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap" sx={{ mt: 1 }}>
              {band.historicalMid != null && (
                <Typography variant="caption" color="text.secondary">
                  自身歷史中位價：{formatNumber(band.historicalMid)} {currency}
                </Typography>
              )}
              {band.peerMid != null && (
                <Typography variant="caption" color="text.secondary">
                  同業市場中位價：{formatNumber(band.peerMid)} {currency}
                </Typography>
              )}
              {band.assumptionLow != null && band.assumptionHigh != null && (
                <Typography variant="caption" color="text.secondary">
                  人工產業假設（獨立對照，未混入參考帶）：{formatNumber(band.assumptionLow)}–{formatNumber(band.assumptionHigh)} {currency}
                </Typography>
              )}
              {spreadVal != null && (
                <Typography variant="caption" color="text.secondary">
                  {summary.yieldSpreadBasis === "EARNINGS_YIELD_MINUS_10Y" ? "盈餘殖利率減 10Y 美債" : "FCF 殖利率減 10Y 美債"}：
                  <b>{formatSignedRatioPct(spreadVal)}</b>
                </Typography>
              )}
            </Stack>
          </>;
        })() : (
          <Typography variant="body2" color="text.secondary">
            目前無法合成客觀市場價格參考帶
            {summary.reasons.length > 0 ? `（${summary.reasons.map(r => readableReason(t, r)).join("；")}）` : ""}
            ；請參考下方單項可用指標與原始數據。
          </Typography>
        )}

        <Divider sx={{ my: 1.2 }} />
        <Typography variant="body2" fontWeight={700}>
          觀察傾向：{t(`analysisReport.executiveSummary.stances.${summary.stance}`, {
            defaultValue: STANCE_DEFAULTS[summary.stance] ?? summary.stance,
          })}
        </Typography>

        {(summary.risks.length > 0 || summary.watchItems.length > 0) && (
          <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
            {summary.risks.length > 0 && (
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="caption" fontWeight={700} color="error.main">主要風險提醒</Typography>
                <Stack spacing={0.3} sx={{ mt: 0.3 }}>
                  {summary.risks.map(flag => (
                    <Typography key={flag.code} variant="caption" color="text.secondary">• {renderRiskText(flag)}</Typography>
                  ))}
                </Stack>
              </Grid>
            )}
            {summary.watchItems.length > 0 && (
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="caption" fontWeight={700} color="primary.main">關鍵觀察座標</Typography>
                <Stack spacing={0.3} sx={{ mt: 0.3 }}>
                  {summary.watchItems.map(item => (
                    <Typography key={item.code} variant="caption" color="text.secondary">• {renderWatchText(item)}</Typography>
                  ))}
                </Stack>
              </Grid>
            )}
          </Grid>
        )}
      </Box>
    )}

    {!summary && summaryResult && summaryResult.reasons?.length > 0 && (
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1.2 }}>
        {summaryResult.reasons.map(r => readableReason(t, r)).join("；")}
      </Typography>
    )}

    {summary?.eventGateBlocked && (
      <Alert severity="warning" sx={{ mt: 1.5 }}>
        財報或重大事件窗口受限{summary.nextBlockingEventDate ? `（事件日：${summary.nextBlockingEventDate}）` : ""}，估值區間僅供對照，暫不啟動新入場觀察。
      </Alert>
    )}

    {/* 當日股價與核心估值倍數列（僅收起銀行／保險不適用的 P/FCF 與 EV/EBITDA，保留虧損或負 FCF 提示） */}
    <Grid container spacing={2} sx={{ mt: 0.5 }}>
      <Grid size={{ xs: 6, md: visibleMetrics.length <= 3 ? 3 : 2 }}>
        <Paper variant="outlined" sx={{ p: 1.8, height: "100%" }}>
          <Typography variant="caption" color="text.secondary">估值使用股價</Typography>
          <Typography variant="h6">{formatNumber(valuation?.price)} {valuation?.currency ?? ""}</Typography>
        </Paper>
      </Grid>
      {visibleMetrics.map(([code, result]) => {
        const isPrimary = isPrimaryCodeMatch(code, valuation?.primaryMetric);
        return (
          <Grid key={code} size={{ xs: 6, md: visibleMetrics.length <= 3 ? 3 : 2 }}>
            <Paper variant="outlined" sx={{ p: 1.8, height: "100%", borderColor: isPrimary ? "primary.main" : undefined }}>
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography variant="caption" color="text.secondary">{metricLabel(code)}</Typography>
                {isPrimary && <Chip size="small" label="主指標" color="primary" variant="outlined" sx={{ height: 18, fontSize: "0.68rem" }} />}
              </Stack>
              <Typography variant="h6">{result.status === "AVAILABLE" ? formatNumber(result.value) : "—"}</Typography>
              {result.financialSource === "SEC" && (
                <Typography variant="caption" color="text.secondary">
                  SEC 財務{result.periodEnd && ` · ${result.periodStart ? `${result.periodStart}～` : ""}${result.periodEnd}`}
                </Typography>
              )}
              {result.status !== "AVAILABLE" && (
                <Typography variant="caption" color="text.secondary">{statusLabel(result.status)} · {reasonLabel(t, result.reasonCode)}</Typography>
              )}
            </Paper>
          </Grid>
        );
      })}
    </Grid>

    {collapsedIndustryNa.length > 0 && (
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
        已自動收起金融機構模型不適用倍數：{collapsedIndustryNa.map(([code]) => metricLabel(code)).join("、")}（銀行與保險業核心採用 P/E 與 P/B）。
      </Typography>
    )}

    <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
      {t("analysisReport.executiveSummary.disclaimer", {
        defaultValue: "以自身歷史與同業市場實際成交分位合成參考價帶；僅反映市場相對估值位置，不等同內在價值、價格預測或買賣指令。",
      })}
    </Typography>
  </Paper>;
}
