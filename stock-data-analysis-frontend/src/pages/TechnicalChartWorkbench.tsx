import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Box, Button, ButtonGroup, Chip, Divider, Grid, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { useTranslation } from "react-i18next";
import {
  AnalysisHorizon, IndicatorSeriesPoint, LevelZone, MarketContext, MetricResult,
  NormalizedBar, PriceStructure, SupportResistance, TradePlanResult,
} from "../services/types/dto/analysis";
import { ValuationReference, ValuationResult } from "../services/types/dto/valuation";
import { formatNumber, readableReason, readableState } from "./AnalysisPanels";

type RangeCode = "1M" | "3M" | "6M" | "YTD" | "1Y" | "3Y" | "5Y";
type TimeframeCode = "DAILY" | "WEEKLY" | "MONTHLY";
type ChartMode = "CANDLE" | "LINE";
type BrushDragMode = "MOVE" | "LEFT" | "RIGHT";

const RANGE_OPTIONS: Array<[RangeCode, string]> = [
  ["1M", "1個月"], ["3M", "3個月"], ["6M", "6個月"], ["YTD", "年初至今"], ["1Y", "1年"], ["3Y", "3年"], ["5Y", "5年"],
];

const metricVal = (r?: MetricResult<number> | null): number | null =>
  r?.status === "AVAILABLE" && r.value != null && Number.isFinite(r.value) ? r.value : null;

const formatCompactVolume = (vol?: number | null): string => {
  if (vol == null || !Number.isFinite(vol)) return "—";
  if (vol >= 1e8) return (vol / 1e8).toFixed(2) + " 億";
  if (vol >= 1e4) return (vol / 1e4).toFixed(1) + " 萬";
  return Math.round(vol).toLocaleString("zh-HK");
};

const formatSignedPct = (pct?: number | null): string => {
  if (pct == null || !Number.isFinite(pct)) return "—";
  return (pct >= 0 ? "+" : "") + pct.toFixed(2) + "%";
};

const clampInt = (val: number, min: number, max: number): number => Math.max(min, Math.min(max, val));

function cutoffDateForRange(endDateStr: string, range: RangeCode): string {
  const end = new Date(endDateStr + "T00:00:00Z");
  if (Number.isNaN(end.getTime())) return "";
  if (range === "YTD") return `${end.getUTCFullYear()}-01-01`;
  const d = new Date(end.getTime());
  if (range === "1M") d.setUTCMonth(d.getUTCMonth() - 1);
  else if (range === "3M") d.setUTCMonth(d.getUTCMonth() - 3);
  else if (range === "6M") d.setUTCMonth(d.getUTCMonth() - 6);
  else if (range === "1Y") d.setUTCFullYear(d.getUTCFullYear() - 1);
  else if (range === "3Y") d.setUTCFullYear(d.getUTCFullYear() - 3);
  else d.setUTCFullYear(d.getUTCFullYear() - 5);
  return d.toISOString().slice(0, 10);
}

function computePresetWindow(bars: NormalizedBar[], range: RangeCode): [number, number] {
  const total = bars.length;
  if (total <= 2) return [0, total];
  const cutoff = cutoffDateForRange(bars[total - 1].sessionDate, range);
  if (!cutoff) return [0, total];
  const firstIdx = bars.findIndex(b => b.sessionDate >= cutoff);
  if (firstIdx < 0) return [Math.max(0, total - Math.min(total, 30)), total];
  const start = Math.min(firstIdx, Math.max(0, total - 2));
  return [start, total];
}

function ReferencePositionBar({ label, price, reference, currency }: {
  label: string; price?: number | null; reference?: ValuationReference | null; currency?: string | null;
}) {
  if (!price || !reference || reference.status !== "AVAILABLE" || reference.priceLow == null || reference.priceHigh == null) {
    return <Typography variant="body2" color="text.secondary" sx={{ mt: 0.6 }}>{label}：暫無可換算價格區間</Typography>;
  }
  const low = reference.priceLow;
  const high = reference.priceHigh;
  const minBound = Math.min(price, low) * 0.92;
  const maxBound = Math.max(price, high) * 1.08;
  const span = maxBound - minBound || 1;
  const bandLeft = ((low - minBound) / span) * 100;
  const bandWidth = Math.max(2, ((high - low) / span) * 100);
  const pinPos = Math.min(98, Math.max(2, ((price - minBound) / span) * 100));
  const diffPct = price < low ? ((price - low) / low) * 100 : price > high ? ((price - high) / high) * 100 : 0;
  const tagText = price < low ? `低於下緣 ${formatSignedPct(diffPct)}`
    : price > high ? `高於上緣 ${formatSignedPct(diffPct)}` : "位於參考區間內";
  const tagColor = price < low ? "success.main" : price > high ? "warning.main" : "info.main";

  return <Box sx={{ mt: 0.8 }}>
    <Stack direction="row" justifyContent="space-between" alignItems="baseline">
      <Typography variant="caption" color="text.secondary">{label}（{formatNumber(low)}–{formatNumber(high)} {currency ?? ""}）</Typography>
      <Typography variant="caption" fontWeight={700} color={tagColor}>{tagText}</Typography>
    </Stack>
    <Box sx={{ position: "relative", height: 10, bgcolor: "action.hover", borderRadius: 5, mt: 0.5, overflow: "hidden" }}>
      <Box sx={{ position: "absolute", left: `${bandLeft}%`, width: `${bandWidth}%`, top: 0, bottom: 0, bgcolor: "rgba(25, 118, 210, 0.28)", borderRadius: 5 }} />
      <Box sx={{ position: "absolute", left: `calc(${pinPos}% - 4px)`, top: 1, width: 8, height: 8, borderRadius: "50%", bgcolor: price < low ? "#16a34a" : price > high ? "#ed6c02" : "#1976d2", border: "1.5px solid #fff" }} />
    </Box>
  </Box>;
}

/** 買賣觀察四維速覽卡：整合估值水位、均線趨勢、RSI/MACD 動能與最近支撐阻力。 */
export function DecisionSnapshotStrip({ valuation, structure, levels, market }: {
  valuation?: ValuationResult | null;
  structure?: PriceStructure | null;
  levels?: SupportResistance | null;
  market?: MarketContext | null;
}) {
  const { t } = useTranslation();
  const price = valuation?.price ?? null;
  const currency = valuation?.currency ?? "USD";
  const ind = market?.momentumDetail?.indicators ?? {};
  const sma20 = metricVal(ind.SMA20);
  const sma50 = metricVal(ind.SMA50);
  const sma200 = metricVal(ind.SMA200);
  const rsi14 = metricVal(ind.RSI14);
  const macd = metricVal(ind.MACD);
  const macdHist = metricVal(ind.MACD_HISTOGRAM);
  const atr14 = metricVal(ind.ATR14);
  const ret20 = metricVal(market?.momentum?.return20);
  const dd252 = metricVal(market?.momentum?.drawdownFrom252High);
  const nearestSupport = levels?.supports?.[0];
  const nearestResistance = levels?.resistances?.[0];

  const maDiff = (ma: number | null) => (price != null && ma != null && ma > 0 ? ((price - ma) / ma) * 100 : null);

  return <Grid container spacing={2} sx={{ mt: 0.5 }}>
    <Grid size={{ xs: 12, md: 6, lg: 3 }}>
      <Paper variant="outlined" sx={{ p: 2, height: "100%" }}>
        <Typography variant="subtitle2" fontWeight={700}>估值水位比較</Typography>
        <ReferencePositionBar label="自身5年歷史 P20–P80" price={price} reference={valuation?.historicalReference} currency={currency} />
        <ReferencePositionBar label="同業市場 P25–P75" price={price} reference={valuation?.peerReference} currency={currency} />
      </Paper>
    </Grid>
    <Grid size={{ xs: 12, md: 6, lg: 3 }}>
      <Paper variant="outlined" sx={{ p: 2, height: "100%" }}>
        <Typography variant="subtitle2" fontWeight={700}>均線結構與多週期趨勢</Typography>
        <Stack direction="row" spacing={0.8} useFlexGap flexWrap="wrap" sx={{ mt: 1 }}>
          {(structure?.timeframes ?? []).map(tf => {
            const state = tf.majorTrend?.status === "AVAILABLE" ? tf.majorTrend.value?.state : tf.majorTrend?.status;
            const color = state === "UPTREND" ? "success" : state === "DOWNTREND" ? "error" : "default";
            return <Chip key={tf.timeframe} size="small" color={color} variant="outlined"
              label={`${readableState(t, tf.timeframe)}：${readableState(t, state)}`} />;
          })}
        </Stack>
        <Stack spacing={0.4} sx={{ mt: 1 }}>
          {([["SMA20", sma20], ["SMA50", sma50], ["SMA200 年線", sma200]] as const).map(([label, val]) => {
            const pct = maDiff(val);
            return <Stack key={label} direction="row" justifyContent="space-between">
              <Typography variant="caption" color="text.secondary">{label}：{formatNumber(val)}</Typography>
              <Typography variant="caption" fontWeight={600} color={pct == null ? "text.secondary" : pct >= 0 ? "success.main" : "error.main"}>
                {pct == null ? "—" : (pct >= 0 ? "高於 " : "低於 ") + formatSignedPct(pct)}
              </Typography>
            </Stack>;
          })}
        </Stack>
      </Paper>
    </Grid>
    <Grid size={{ xs: 12, md: 6, lg: 3 }}>
      <Paper variant="outlined" sx={{ p: 2, height: "100%" }}>
        <Typography variant="subtitle2" fontWeight={700}>動能與超買超賣（日線）</Typography>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline" sx={{ mt: 0.8 }}>
          <Typography variant="body2">RSI(14)：<b>{formatNumber(rsi14)}</b></Typography>
          <Typography variant="caption" fontWeight={700} color={rsi14 == null ? "text.secondary" : rsi14 <= 30 ? "success.main" : rsi14 >= 70 ? "warning.main" : "text.secondary"}>
            {rsi14 == null ? "—" : rsi14 <= 30 ? "超賣低位區（≤30）" : rsi14 >= 70 ? "超買高位區（≥70）" : "中性區間（30–70）"}
          </Typography>
        </Stack>
        {rsi14 != null && <Box sx={{ position: "relative", height: 8, bgcolor: "action.hover", borderRadius: 4, my: 0.7 }}>
          <Box sx={{ position: "absolute", left: "30%", width: "40%", top: 0, bottom: 0, borderLeft: "1px dashed #16a34a", borderRight: "1px dashed #dc2626" }} />
          <Box sx={{ position: "absolute", left: `calc(${Math.min(98, Math.max(2, rsi14))}% - 4px)`, top: 0, width: 8, height: 8, borderRadius: "50%", bgcolor: rsi14 <= 30 ? "#16a34a" : rsi14 >= 70 ? "#dc2626" : "#1976d2" }} />
        </Box>}
        <Typography variant="caption" display="block" color="text.secondary">
          MACD：{formatNumber(macd)} · 柱狀：{formatNumber(macdHist)}（{macdHist == null ? "—" : macdHist >= 0 ? "多方柱" : "空方柱"}）
        </Typography>
        <Typography variant="caption" display="block" color="text.secondary">
          20日報酬：{ret20 != null ? formatSignedPct(ret20 * 100) : "—"} · 距52週高點：{dd252 != null ? formatSignedPct(dd252 * 100) : "—"}
        </Typography>
      </Paper>
    </Grid>
    <Grid size={{ xs: 12, md: 6, lg: 3 }}>
      <Paper variant="outlined" sx={{ p: 2, height: "100%" }}>
        <Typography variant="subtitle2" fontWeight={700}>最近關鍵防守與壓力</Typography>
        <Stack spacing={0.6} sx={{ mt: 0.8 }}>
          <Box>
            <Typography variant="caption" color="success.main" fontWeight={700}>最近下方支撐</Typography>
            <Typography variant="body2">
              {nearestSupport ? `${formatNumber(nearestSupport.lower)}–${formatNumber(nearestSupport.upper)}（${formatSignedPct(nearestSupport.distanceUpperPercent)}）` : "暫無下方支撐紀錄"}
            </Typography>
          </Box>
          <Box>
            <Typography variant="caption" color="error.main" fontWeight={700}>最近上方阻力</Typography>
            <Typography variant="body2">
              {nearestResistance ? `${formatNumber(nearestResistance.lower)}–${formatNumber(nearestResistance.upper)}（${formatSignedPct(nearestResistance.distanceLowerPercent)}）` : "暫無上方阻力紀錄"}
            </Typography>
          </Box>
          <Typography variant="caption" color="text.secondary">日線波動幅度 ATR(14)：{formatNumber(atr14)} {currency}</Typography>
        </Stack>
      </Paper>
    </Grid>
  </Grid>;
}

/** 互動式多窗格股價 K 線／折線 ＋ 日期縮放平移（滾輪／拖曳／時間軸滑桿）＋ 均線／布林／支撐阻力色帶 ＋ Volume / RSI / MACD 副圖。 */
export function InteractivePriceWorkbench({ structure, levels, market, valuation }: {
  structure?: PriceStructure | null;
  levels?: SupportResistance | null;
  market?: MarketContext | null;
  valuation?: ValuationResult | null;
}) {
  const [range, setRange] = useState<RangeCode>("1Y");
  const [timeframe, setTimeframe] = useState<TimeframeCode>("DAILY");
  const [mode, setMode] = useState<ChartMode>("CANDLE");
  const [customWindow, setCustomWindow] = useState<[number, number] | null>(null);
  const [showSma20, setShowSma20] = useState(true);
  const [showSma50, setShowSma50] = useState(true);
  const [showSma200, setShowSma200] = useState(true);
  const [showBollinger, setShowBollinger] = useState(false);
  const [showZones, setShowZones] = useState(true);
  const [showValBand, setShowValBand] = useState(false);
  const [showVolume, setShowVolume] = useState(true);
  const [showRsi, setShowRsi] = useState(true);
  const [showMacd, setShowMacd] = useState(true);
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);
  const [isPanning, setIsPanning] = useState(false);

  const mainSvgRef = useRef<SVGSVGElement | null>(null);
  const panOriginRef = useRef<{ clientX: number; start: number; end: number } | null>(null);
  const brushDragRef = useRef<{ mode: BrushDragMode; clientX: number; start: number; end: number } | null>(null);

  const tfData = structure?.timeframes?.find(tf => tf.timeframe === timeframe)
    ?? structure?.timeframes?.find(tf => tf.timeframe === "DAILY");
  const allBars: NormalizedBar[] = useMemo(() => tfData?.bars ?? [], [tfData]);
  const totalBars = allBars.length;
  const minZoomBars = Math.min(totalBars, 10);

  const presetWin = useMemo(() => computePresetWindow(allBars, range), [allBars, range]);
  const [winStart, winEnd] = useMemo<[number, number]>(() => {
    if (!customWindow || totalBars <= 2) return presetWin;
    const span = clampInt(customWindow[1] - customWindow[0], minZoomBars, totalBars);
    const s = clampInt(customWindow[0], 0, totalBars - span);
    return [s, s + span];
  }, [customWindow, presetWin, totalBars, minZoomBars]);

  // 當資料來源（如切換股票或估值日期）變動時重設自訂縮放視窗
  useEffect(() => {
    setCustomWindow(null);
    setHoverIdx(null);
  }, [allBars]);

  const W = 960;
  const padL = 64;
  const padR = 88;
  const plotW = W - padL - padR;

  const zoomByFactor = useCallback((factor: number, anchorRatio = 0.5) => {
    if (totalBars <= minZoomBars) return;
    const curSpan = winEnd - winStart;
    let nextSpan = Math.round(curSpan * factor);
    if (factor < 1 && nextSpan === curSpan) nextSpan = curSpan - 1;
    if (factor > 1 && nextSpan === curSpan) nextSpan = curSpan + 1;
    nextSpan = clampInt(nextSpan, minZoomBars, totalBars);
    const ratio = Math.max(0, Math.min(1, anchorRatio));
    const anchor = winStart + ratio * curSpan;
    const nextStart = clampInt(Math.round(anchor - ratio * nextSpan), 0, totalBars - nextSpan);
    setCustomWindow([nextStart, nextStart + nextSpan]);
    setHoverIdx(null);
  }, [totalBars, minZoomBars, winStart, winEnd]);

  // 滑鼠滾輪縮放（以游標所在日期為錨點放大／縮小，並阻止整頁捲動）
  useEffect(() => {
    const svgEl = mainSvgRef.current;
    if (!svgEl || totalBars <= 2) return;
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY === 0) return;
      event.preventDefault();
      const rect = svgEl.getBoundingClientRect();
      const svgX = rect.width > 0 ? ((event.clientX - rect.left) / rect.width) * W : padL + plotW * 0.8;
      const ratio = (svgX - padL) / plotW;
      zoomByFactor(event.deltaY < 0 ? 0.78 : 1.28, ratio);
    };
    svgEl.addEventListener("wheel", onWheel, { passive: false });
    return () => svgEl.removeEventListener("wheel", onWheel);
  }, [totalBars, zoomByFactor, plotW]);

  const indicatorByDate = useMemo(() => {
    const map = new Map<string, IndicatorSeriesPoint>();
    for (const pt of market?.momentumDetail?.series ?? []) {
      map.set(pt.sessionDate, pt);
    }
    return map;
  }, [market?.momentumDetail?.series]);

  const visibleBars = useMemo(() => allBars.slice(winStart, winEnd), [allBars, winStart, winEnd]);

  if (visibleBars.length < 2) {
    return null;
  }

  const isDaily = (tfData?.timeframe ?? "DAILY") === "DAILY";
  const hasIndicatorSeries = isDaily && indicatorByDate.size > 0;
  const activeRsi = showRsi && hasIndicatorSeries;
  const activeMacd = showMacd && hasIndicatorSeries;

  const priceTop = 16;
  const priceH = 270;
  let cursorY = priceTop + priceH;

  const volTop = showVolume ? cursorY + 18 : 0;
  const volH = showVolume ? 58 : 0;
  if (showVolume) cursorY = volTop + volH;

  const rsiTop = activeRsi ? cursorY + 22 : 0;
  const rsiH = activeRsi ? 80 : 0;
  if (activeRsi) cursorY = rsiTop + rsiH;

  const macdTop = activeMacd ? cursorY + 22 : 0;
  const macdH = activeMacd ? 88 : 0;
  if (activeMacd) cursorY = macdTop + macdH;

  const totalH = cursorY + 28;
  const count = visibleBars.length;
  const stepX = plotW / count;
  const xAt = (idx: number) => padL + (idx + 0.5) * stepX;

  let minP = Infinity;
  let maxP = -Infinity;
  visibleBars.forEach(b => {
    if (b.low < minP) minP = b.low;
    if (b.high > maxP) maxP = b.high;
    if (isDaily) {
      const pt = indicatorByDate.get(b.sessionDate);
      if (pt) {
        const extras = [
          showSma20 ? pt.sma20 : null,
          showSma50 ? pt.sma50 : null,
          showSma200 ? pt.sma200 : null,
          showBollinger ? pt.bollingerUpper : null,
          showBollinger ? pt.bollingerLower : null,
        ];
        for (const v of extras) {
          if (v != null && Number.isFinite(v)) {
            if (v < minP) minP = v;
            if (v > maxP) maxP = v;
          }
        }
      }
    }
  });
  const histRef = valuation?.historicalReference;
  if (showValBand && histRef?.status === "AVAILABLE" && histRef.priceLow != null && histRef.priceHigh != null) {
    minP = Math.min(minP, histRef.priceLow);
    maxP = Math.max(maxP, histRef.priceHigh);
  }
  const padP = (maxP - minP) * 0.06 || 1;
  const pLow = Math.max(0, minP - padP);
  const pHigh = maxP + padP;
  const yPrice = (val: number) => priceTop + (1 - (val - pLow) / (pHigh - pLow || 1)) * priceH;

  const buildPath = (getter: (bar: NormalizedBar) => number | null | undefined, yMapper: (v: number) => number): string => {
    let openSeg = false;
    const cmds: string[] = [];
    visibleBars.forEach((b, idx) => {
      const val = getter(b);
      if (val == null || !Number.isFinite(val)) {
        openSeg = false;
        return;
      }
      cmds.push(`${openSeg ? "L" : "M"}${xAt(idx).toFixed(1)},${yMapper(val).toFixed(1)}`);
      openSeg = true;
    });
    return cmds.join(" ");
  };

  const maxVol = Math.max(1, ...visibleBars.map(b => metricVal(b.volume) ?? 0));
  const yVol = (v: number) => volTop + volH - (v / maxVol) * volH;
  const yRsi = (v: number) => rsiTop + (1 - v / 100) * rsiH;

  let maxAbsMacd = 0.01;
  if (activeMacd) {
    visibleBars.forEach(b => {
      const pt = indicatorByDate.get(b.sessionDate);
      if (pt) {
        for (const v of [pt.macd, pt.macdSignal, pt.macdHistogram]) {
          if (v != null && Math.abs(v) > maxAbsMacd) maxAbsMacd = Math.abs(v);
        }
      }
    });
  }
  const macdBound = maxAbsMacd * 1.12;
  const yMacd = (v: number) => macdTop + (1 - (v + macdBound) / (2 * macdBound)) * macdH;
  const yMacdZero = yMacd(0);

  const selectedIdx = hoverIdx != null && hoverIdx >= 0 && hoverIdx < count ? hoverIdx : count - 1;
  const activeBar = visibleBars[selectedIdx];
  const prevBar = selectedIdx > 0 ? visibleBars[selectedIdx - 1] : winStart > 0 ? allBars[winStart - 1] : null;
  const dayChangePct = prevBar && prevBar.close > 0 ? ((activeBar.close - prevBar.close) / prevBar.close) * 100 : null;
  const activeInd = isDaily ? indicatorByDate.get(activeBar.sessionDate) : undefined;
  const candleWidth = Math.max(1.4, Math.min(10, stepX * 0.68));

  const priceTicks = [0, 0.25, 0.5, 0.75, 1].map(t => pLow + (pHigh - pLow) * t);
  const dateTickIndices = Array.from(new Set([0, Math.floor(count * 0.25), Math.floor(count * 0.5), Math.floor(count * 0.75), count - 1]));

  const visibleZones: LevelZone[] = [];
  if (showZones && levels) {
    for (const z of [...(levels.supports ?? []).slice(0, 3), ...(levels.resistances ?? []).slice(0, 3)]) {
      if (z.upper >= pLow && z.lower <= pHigh) visibleZones.push(z);
    }
  }

  // 右側價位標籤防重疊演算法：當多個支撐／阻力帶距離接近時，自動上下錯開標籤並畫出導引線
  const placedZoneLabels = (() => {
    const minY = priceTop + 9;
    const maxY = priceTop + priceH - 7;
    const minGap = 15;
    const items = visibleZones.map(zone => {
      const yTop = Math.max(priceTop, yPrice(Math.min(zone.upper, pHigh)));
      const yBot = Math.min(priceTop + priceH, yPrice(Math.max(zone.lower, pLow)));
      const anchorY = (yTop + yBot) / 2;
      return { zone, yTop, yBot, anchorY, labelY: Math.max(minY, Math.min(maxY, anchorY)) };
    }).sort((a, b) => a.anchorY - b.anchorY);
    for (let i = 1; i < items.length; i++) {
      if (items[i].labelY - items[i - 1].labelY < minGap) {
        items[i].labelY = items[i - 1].labelY + minGap;
      }
    }
    if (items.length > 0 && items[items.length - 1].labelY > maxY) {
      items[items.length - 1].labelY = maxY;
      for (let i = items.length - 2; i >= 0; i--) {
        if (items[i + 1].labelY - items[i].labelY < minGap) {
          items[i].labelY = items[i + 1].labelY - minGap;
        }
      }
    }
    return items;
  })();

  // 主圖滑鼠按住拖曳平移 ＋ 懸停十字準星
  const handleMainMouseDown = (event: React.MouseEvent<SVGSVGElement>) => {
    if (event.button !== 0) return;
    panOriginRef.current = { clientX: event.clientX, start: winStart, end: winEnd };
    setIsPanning(true);
    setHoverIdx(null);
  };

  const handleMainMouseMove = (event: React.MouseEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const widthPx = rect.width || W;
    if (panOriginRef.current) {
      const origin = panOriginRef.current;
      const span = origin.end - origin.start;
      const dxSvg = ((event.clientX - origin.clientX) / widthPx) * W;
      const deltaBars = Math.round((dxSvg / plotW) * span);
      const nextStart = clampInt(origin.start - deltaBars, 0, totalBars - span);
      setCustomWindow([nextStart, nextStart + span]);
      return;
    }
    const svgX = ((event.clientX - rect.left) / widthPx) * W;
    const rawIdx = Math.floor((svgX - padL) / stepX);
    setHoverIdx(rawIdx >= 0 && rawIdx < count ? rawIdx : null);
  };

  const stopMainPan = () => {
    if (panOriginRef.current) {
      panOriginRef.current = null;
      setIsPanning(false);
    }
  };

  // 底部 5 年全景時間軸滑桿（Overview Navigator Brush）計算與互動
  const navH = 44;
  const navTop = 6;
  const navPlotH = 30;
  let allMinClose = Infinity;
  let allMaxClose = -Infinity;
  for (const b of allBars) {
    if (b.close < allMinClose) allMinClose = b.close;
    if (b.close > allMaxClose) allMaxClose = b.close;
  }
  const allSpanClose = allMaxClose - allMinClose || 1;
  const overviewStep = Math.max(1, Math.floor(totalBars / 300));
  const overviewCmds: string[] = [];
  for (let i = 0; i < totalBars; i += overviewStep) {
    const ox = padL + (i / Math.max(1, totalBars - 1)) * plotW;
    const oy = navTop + (1 - (allBars[i].close - allMinClose) / allSpanClose) * navPlotH;
    overviewCmds.push(`${overviewCmds.length === 0 ? "M" : "L"}${ox.toFixed(1)},${oy.toFixed(1)}`);
  }
  if (totalBars > 1 && (totalBars - 1) % overviewStep !== 0) {
    const oy = navTop + (1 - (allBars[totalBars - 1].close - allMinClose) / allSpanClose) * navPlotH;
    overviewCmds.push(`L${(padL + plotW).toFixed(1)},${oy.toFixed(1)}`);
  }
  const brushX1 = padL + (winStart / totalBars) * plotW;
  const brushX2 = padL + (winEnd / totalBars) * plotW;
  const brushW = Math.max(8, brushX2 - brushX1);

  const handleBrushMouseDown = (event: React.MouseEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const widthPx = rect.width || W;
    const svgX = ((event.clientX - rect.left) / widthPx) * W;
    const handleTol = 10;
    if (Math.abs(svgX - brushX1) <= handleTol) {
      brushDragRef.current = { mode: "LEFT", clientX: event.clientX, start: winStart, end: winEnd };
    } else if (Math.abs(svgX - brushX2) <= handleTol) {
      brushDragRef.current = { mode: "RIGHT", clientX: event.clientX, start: winStart, end: winEnd };
    } else if (svgX >= brushX1 && svgX <= brushX2) {
      brushDragRef.current = { mode: "MOVE", clientX: event.clientX, start: winStart, end: winEnd };
    } else if (svgX >= padL && svgX <= W - padR) {
      const span = winEnd - winStart;
      const clickIdx = Math.round(((svgX - padL) / plotW) * totalBars);
      const nextStart = clampInt(clickIdx - Math.floor(span / 2), 0, totalBars - span);
      setCustomWindow([nextStart, nextStart + span]);
      brushDragRef.current = { mode: "MOVE", clientX: event.clientX, start: nextStart, end: nextStart + span };
    }
  };

  const handleBrushMouseMove = (event: React.MouseEvent<SVGSVGElement>) => {
    if (!brushDragRef.current) return;
    const rect = event.currentTarget.getBoundingClientRect();
    const widthPx = rect.width || W;
    const drag = brushDragRef.current;
    const deltaBars = Math.round((((event.clientX - drag.clientX) / widthPx) * W / plotW) * totalBars);
    if (drag.mode === "MOVE") {
      const span = drag.end - drag.start;
      const nextStart = clampInt(drag.start + deltaBars, 0, totalBars - span);
      setCustomWindow([nextStart, nextStart + span]);
    } else if (drag.mode === "LEFT") {
      const nextStart = clampInt(drag.start + deltaBars, 0, drag.end - minZoomBars);
      setCustomWindow([nextStart, drag.end]);
    } else {
      const nextEnd = clampInt(drag.end + deltaBars, drag.start + minZoomBars, totalBars);
      setCustomWindow([drag.start, nextEnd]);
    }
  };

  const stopBrushDrag = () => {
    brushDragRef.current = null;
  };

  const rangeStartLabel = visibleBars[0].sessionDate;
  const rangeEndLabel = visibleBars[count - 1].sessionDate;

  return <Paper component="section" aria-label="股價與技術指標互動圖表" variant="outlined" sx={{ p: 2.5, mt: 2 }}>
    <Stack direction={{ xs: "column", lg: "row" }} justifyContent="space-between" alignItems={{ lg: "center" }} spacing={1.5}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h6" fontWeight={700}>股價走勢與技術指標圖表</Typography>
        <Typography variant="caption" color="text.secondary" display="block">
          顯示區間：{rangeStartLabel} ～ {rangeEndLabel}（共 {count}／{totalBars} 根）· 滾輪縮放 · 拖曳平移 · 下方時間軸拉動
        </Typography>
      </Box>
      <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
        <ButtonGroup size="small" aria-label="圖表時間範圍">
          {RANGE_OPTIONS.map(([code, label]) => <Button key={code}
            variant={customWindow == null && range === code ? "contained" : "outlined"}
            onClick={() => { setRange(code); setCustomWindow(null); setHoverIdx(null); }}>{label}</Button>)}
        </ButtonGroup>
        <ButtonGroup size="small" aria-label="日期縮放控制">
          <Button aria-label="放大日期區間" disabled={count <= minZoomBars} onClick={() => zoomByFactor(0.72, 0.85)}>放大 ＋</Button>
          <Button aria-label="縮小日期區間" disabled={count >= totalBars} onClick={() => zoomByFactor(1.38, 0.85)}>縮小 －</Button>
          <Button aria-label="重設圖表縮放" disabled={customWindow == null} onClick={() => { setCustomWindow(null); setHoverIdx(null); }}>重設</Button>
        </ButtonGroup>
        <ButtonGroup size="small" aria-label="K線週期">
          {([["DAILY", "日線"], ["WEEKLY", "週線"], ["MONTHLY", "月線"]] as const).map(([code, label]) => (
            <Button key={code} variant={timeframe === code ? "contained" : "outlined"}
              onClick={() => { setTimeframe(code); setCustomWindow(null); setHoverIdx(null); }}>{label}</Button>
          ))}
        </ButtonGroup>
        <ButtonGroup size="small" aria-label="圖表樣式">
          <Button variant={mode === "CANDLE" ? "contained" : "outlined"} onClick={() => setMode("CANDLE")}>K線</Button>
          <Button variant={mode === "LINE" ? "contained" : "outlined"} onClick={() => setMode("LINE")}>收盤線</Button>
        </ButtonGroup>
      </Stack>
    </Stack>

    <Stack direction="row" spacing={0.8} useFlexGap flexWrap="wrap" alignItems="center" sx={{ mt: 1.5 }}>
      {isDaily && <>
        <Chip size="small" label="SMA20" color={showSma20 ? "warning" : "default"} variant={showSma20 ? "filled" : "outlined"} onClick={() => setShowSma20(v => !v)} />
        <Chip size="small" label="SMA50" color={showSma50 ? "primary" : "default"} variant={showSma50 ? "filled" : "outlined"} onClick={() => setShowSma50(v => !v)} />
        <Chip size="small" label="SMA200 年線" color={showSma200 ? "secondary" : "default"} variant={showSma200 ? "filled" : "outlined"} onClick={() => setShowSma200(v => !v)} />
        <Chip size="small" label="布林通道 BB20" color={showBollinger ? "info" : "default"} variant={showBollinger ? "filled" : "outlined"} onClick={() => setShowBollinger(v => !v)} />
      </>}
      <Chip size="small" label="支撐／阻力帶" color={showZones ? "success" : "default"} variant={showZones ? "filled" : "outlined"} onClick={() => setShowZones(v => !v)} />
      {histRef?.status === "AVAILABLE" && histRef.priceLow != null && histRef.priceHigh != null && (
        <Chip size="small" label="歷史估值參考帶" color={showValBand ? "info" : "default"} variant={showValBand ? "filled" : "outlined"} onClick={() => setShowValBand(v => !v)} />
      )}
      <Divider orientation="vertical" flexItem sx={{ mx: 0.5 }} />
      <Chip size="small" label="成交量副圖" variant={showVolume ? "filled" : "outlined"} onClick={() => setShowVolume(v => !v)} />
      {isDaily && <>
        <Chip size="small" label="RSI(14) 副圖" variant={showRsi ? "filled" : "outlined"} onClick={() => setShowRsi(v => !v)} />
        <Chip size="small" label="MACD 副圖" variant={showMacd ? "filled" : "outlined"} onClick={() => setShowMacd(v => !v)} />
      </>}
      {isDaily && !hasIndicatorSeries && market?.momentumDetail && (
        <Typography variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>
          （重啟後端服務後即可載入完整歷史均線／RSI／MACD 曲線）
        </Typography>
      )}
    </Stack>

    {/* 即時懸停讀數列 */}
    <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap" sx={{ mt: 1.5, px: 1.5, py: 0.8, bgcolor: "action.hover", borderRadius: 1 }}>
      <Typography variant="caption" fontWeight={700}>{activeBar.sessionDate}</Typography>
      <Typography variant="caption">開 <b>{formatNumber(activeBar.open)}</b></Typography>
      <Typography variant="caption">高 <b>{formatNumber(activeBar.high)}</b></Typography>
      <Typography variant="caption">低 <b>{formatNumber(activeBar.low)}</b></Typography>
      <Typography variant="caption" color={dayChangePct != null && dayChangePct < 0 ? "error.main" : "success.main"}>
        收 <b>{formatNumber(activeBar.close)}</b> {dayChangePct != null ? `(${formatSignedPct(dayChangePct)})` : ""}
      </Typography>
      <Typography variant="caption">量 <b>{formatCompactVolume(metricVal(activeBar.volume))}</b></Typography>
      {activeInd && <>
        {showSma20 && <Typography variant="caption" sx={{ color: "#d97706" }}>SMA20: <b>{formatNumber(activeInd.sma20)}</b></Typography>}
        {showSma50 && <Typography variant="caption" sx={{ color: "#2563eb" }}>SMA50: <b>{formatNumber(activeInd.sma50)}</b></Typography>}
        {showSma200 && <Typography variant="caption" sx={{ color: "#7c3aed" }}>SMA200: <b>{formatNumber(activeInd.sma200)}</b></Typography>}
        {activeRsi && <Typography variant="caption">RSI14: <b>{formatNumber(activeInd.rsi14)}</b></Typography>}
        {activeMacd && <Typography variant="caption">MACD: <b>{formatNumber(activeInd.macd)}</b> / 訊號 <b>{formatNumber(activeInd.macdSignal)}</b> / 柱 <b>{formatNumber(activeInd.macdHistogram)}</b></Typography>}
      </>}
    </Stack>

    <Box sx={{ width: "100%", overflowX: "auto", mt: 1, userSelect: "none" }}>
      <svg ref={mainSvgRef} role="img" aria-label="每日收盤價" viewBox={`0 0 ${W} ${totalH}`}
        style={{ width: "100%", minWidth: 560, display: "block", cursor: isPanning ? "grabbing" : "crosshair" }}
        onMouseDown={handleMainMouseDown}
        onMouseMove={handleMainMouseMove}
        onMouseUp={stopMainPan}
        onMouseLeave={() => { stopMainPan(); setHoverIdx(null); }}>
        <title>每日收盤價與技術指標</title>

        {/* 主圖水平格線與 Y 軸刻度 */}
        {priceTicks.map((tick, idx) => {
          const y = yPrice(tick);
          return <g key={idx}>
            <line x1={padL} x2={W - padR} y1={y} y2={y} stroke="#e5e7eb" strokeDasharray={idx === 0 || idx === 4 ? undefined : "3,3"} />
            <text x={padL - 6} y={y + 4} textAnchor="end" fontSize="11" fill="#6b7280">{formatNumber(tick)}</text>
          </g>;
        })}

        {/* 歷史估值參考價色帶 */}
        {showValBand && histRef?.status === "AVAILABLE" && histRef.priceLow != null && histRef.priceHigh != null && (() => {
          const yTop = Math.max(priceTop, yPrice(histRef.priceHigh));
          const yBot = Math.min(priceTop + priceH, yPrice(histRef.priceLow));
          if (yBot <= yTop) return null;
          return <g>
            <rect x={padL} width={plotW} y={yTop} height={yBot - yTop} fill="rgba(79, 70, 229, 0.08)" stroke="#6366f1" strokeDasharray="4,4" />
            <text x={padL + 6} y={Math.min(yBot - 3, yTop + 12)} fontSize="10" fill="#4f46e5">歷史參考 ({formatNumber(histRef.priceLow, 1)}–{formatNumber(histRef.priceHigh, 1)})</text>
          </g>;
        })()}

        {/* 支撐／阻力水平色帶（右側標籤已做防重疊錯開與導引線） */}
        {placedZoneLabels.map(({ zone, yTop, yBot, anchorY, labelY }) => {
          const h = Math.max(2, yBot - yTop);
          const isSup = zone.role === "SUPPORT";
          const lineColor = isSup ? "#16a34a" : "#dc2626";
          const textColor = isSup ? "#15803d" : "#b91c1c";
          const edgeX = W - padR;
          const labelX = edgeX + 7;
          const needsLeader = Math.abs(labelY - anchorY) > 1.5;
          return <g key={zone.id} data-zone-label={zone.id}>
            <rect x={padL} width={plotW} y={yTop} height={h}
              fill={isSup ? "rgba(22, 163, 74, 0.12)" : "rgba(220, 38, 38, 0.11)"} />
            <line x1={padL} x2={edgeX} y1={isSup ? yTop : yBot} y2={isSup ? yTop : yBot}
              stroke={lineColor} strokeWidth="1" strokeDasharray="4,3" opacity={0.75} />
            <line x1={edgeX} x2={labelX - 2} y1={anchorY} y2={labelY}
              stroke={lineColor} strokeWidth={needsLeader ? "1" : "0.8"} opacity={0.7} />
            <rect x={labelX - 2} y={labelY - 7} width={padR - 8} height={13} rx="2"
              fill={isSup ? "rgba(240, 253, 244, 0.94)" : "rgba(254, 242, 242, 0.94)"} />
            <text x={labelX} y={labelY + 3.5} fontSize="10" fontWeight={600} fill={textColor}>
              {isSup ? "支撐 " : "阻力 "}{formatNumber(zone.center ?? (zone.lower + zone.upper) / 2, 1)}
            </text>
          </g>;
        })}

        {/* 布林通道 */}
        {hasIndicatorSeries && showBollinger && <>
          <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.bollingerUpper, yPrice)} fill="none" stroke="#0891b2" strokeWidth="1.1" strokeDasharray="3,3" />
          <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.bollingerLower, yPrice)} fill="none" stroke="#0891b2" strokeWidth="1.1" strokeDasharray="3,3" />
        </>}

        {/* K 線或收盤折線 */}
        {mode === "LINE" ? (
          <path d={buildPath(b => b.close, yPrice)} fill="none" stroke="#1976d2" strokeWidth="2" />
        ) : (
          visibleBars.map((b, idx) => {
            const up = b.close >= b.open;
            const color = up ? "#16a34a" : "#dc2626";
            const x = xAt(idx);
            const yH = yPrice(b.high);
            const yL = yPrice(b.low);
            const yO = yPrice(b.open);
            const yC = yPrice(b.close);
            const bodyTop = Math.min(yO, yC);
            const bodyH = Math.max(1.2, Math.abs(yC - yO));
            return <g key={b.sessionDate}>
              <line x1={x} x2={x} y1={yH} y2={yL} stroke={color} strokeWidth={candleWidth > 3 ? 1.2 : 0.9} />
              <rect x={x - candleWidth / 2} y={bodyTop} width={candleWidth} height={bodyH} fill={color} />
            </g>;
          })
        )}

        {/* 均線 SMA20 / SMA50 / SMA200 */}
        {hasIndicatorSeries && showSma20 && <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.sma20, yPrice)} fill="none" stroke="#f59e0b" strokeWidth="1.5" />}
        {hasIndicatorSeries && showSma50 && <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.sma50, yPrice)} fill="none" stroke="#2563eb" strokeWidth="1.5" />}
        {hasIndicatorSeries && showSma200 && <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.sma200, yPrice)} fill="none" stroke="#7c3aed" strokeWidth="2" />}

        {/* 成交量副圖 */}
        {showVolume && <g>
          <line x1={padL} x2={W - padR} y1={volTop} y2={volTop} stroke="#e5e7eb" />
          <line x1={padL} x2={W - padR} y1={volTop + volH} y2={volTop + volH} stroke="#d1d5db" />
          <text x={padL - 6} y={volTop + 11} textAnchor="end" fontSize="10" fill="#6b7280">成交量</text>
          {visibleBars.map((b, idx) => {
            const v = metricVal(b.volume);
            if (v == null || v <= 0) return null;
            const up = b.close >= b.open;
            const top = yVol(v);
            return <rect key={b.sessionDate} x={xAt(idx) - candleWidth / 2} y={top}
              width={candleWidth} height={Math.max(1, volTop + volH - top)}
              fill={up ? "rgba(22, 163, 74, 0.55)" : "rgba(220, 38, 38, 0.55)"} />;
          })}
        </g>}

        {/* RSI(14) 副圖 */}
        {activeRsi && <g>
          <rect x={padL} width={plotW} y={yRsi(70)} height={yRsi(30) - yRsi(70)} fill="rgba(148, 163, 184, 0.08)" />
          <line x1={padL} x2={W - padR} y1={rsiTop} y2={rsiTop} stroke="#e5e7eb" />
          <line x1={padL} x2={W - padR} y1={yRsi(70)} y2={yRsi(70)} stroke="#dc2626" strokeDasharray="3,3" strokeWidth="0.9" />
          <line x1={padL} x2={W - padR} y1={yRsi(30)} y2={yRsi(30)} stroke="#16a34a" strokeDasharray="3,3" strokeWidth="0.9" />
          <line x1={padL} x2={W - padR} y1={rsiTop + rsiH} y2={rsiTop + rsiH} stroke="#d1d5db" />
          <text x={padL - 6} y={rsiTop + 11} textAnchor="end" fontSize="10" fill="#6b7280">RSI(14)</text>
          <text x={W - padR + 4} y={yRsi(70) + 3} fontSize="10" fill="#dc2626">70</text>
          <text x={W - padR + 4} y={yRsi(30) + 3} fontSize="10" fill="#16a34a">30</text>
          <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.rsi14, yRsi)} fill="none" stroke="#7c3aed" strokeWidth="1.6" />
        </g>}

        {/* MACD(12,26,9) 副圖 */}
        {activeMacd && <g>
          <line x1={padL} x2={W - padR} y1={macdTop} y2={macdTop} stroke="#e5e7eb" />
          <line x1={padL} x2={W - padR} y1={yMacdZero} y2={yMacdZero} stroke="#9ca3af" strokeDasharray="2,2" />
          <line x1={padL} x2={W - padR} y1={macdTop + macdH} y2={macdTop + macdH} stroke="#d1d5db" />
          <text x={padL - 6} y={macdTop + 11} textAnchor="end" fontSize="10" fill="#6b7280">MACD</text>
          {visibleBars.map((b, idx) => {
            const hVal = indicatorByDate.get(b.sessionDate)?.macdHistogram;
            if (hVal == null || !Number.isFinite(hVal)) return null;
            const yVal = yMacd(hVal);
            const top = Math.min(yVal, yMacdZero);
            const h = Math.max(1, Math.abs(yVal - yMacdZero));
            return <rect key={b.sessionDate} x={xAt(idx) - candleWidth / 2} y={top}
              width={candleWidth} height={h} fill={hVal >= 0 ? "rgba(22, 163, 74, 0.6)" : "rgba(220, 38, 38, 0.6)"} />;
          })}
          <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.macd, yMacd)} fill="none" stroke="#2563eb" strokeWidth="1.4" />
          <path d={buildPath(b => indicatorByDate.get(b.sessionDate)?.macdSignal, yMacd)} fill="none" stroke="#f59e0b" strokeWidth="1.4" />
        </g>}

        {/* 十字準星 */}
        {hoverIdx != null && !isPanning && (() => {
          const cx = xAt(selectedIdx);
          const cy = yPrice(activeBar.close);
          return <g pointerEvents="none">
            <line x1={cx} x2={cx} y1={priceTop} y2={cursorY} stroke="#6b7280" strokeDasharray="3,3" strokeWidth="1" />
            <line x1={padL} x2={W - padR} y1={cy} y2={cy} stroke="#6b7280" strokeDasharray="3,3" strokeWidth="1" />
            <circle cx={cx} cy={cy} r="3.5" fill="#1976d2" stroke="#fff" strokeWidth="1.5" />
          </g>;
        })()}

        {/* X 軸日期刻度 */}
        {dateTickIndices.map((idx, i) => (
          <text key={idx} x={xAt(idx)} y={totalH - 6}
            textAnchor={i === 0 ? "start" : i === dateTickIndices.length - 1 ? "end" : "middle"}
            fontSize="11" fill="#6b7280">
            {visibleBars[idx]?.sessionDate}
          </text>
        ))}
      </svg>

      {/* 底部 5 年全歷史縮放導覽滑桿（Navigator Brush） */}
      {totalBars > 10 && (
        <svg aria-label="歷史日期縮放時間軸" viewBox={`0 0 ${W} ${navH}`}
          style={{ width: "100%", minWidth: 560, display: "block", cursor: "ew-resize", marginTop: 2 }}
          onMouseDown={handleBrushMouseDown}
          onMouseMove={handleBrushMouseMove}
          onMouseUp={stopBrushDrag}
          onMouseLeave={stopBrushDrag}>
          <text x={padL - 6} y={navTop + 19} textAnchor="end" fontSize="10" fill="#6b7280">全區間</text>
          <rect x={padL} y={navTop} width={plotW} height={navPlotH} fill="#f8fafc" stroke="#e2e8f0" rx="3" />
          <path d={overviewCmds.join(" ")} fill="none" stroke="#94a3b8" strokeWidth="1.2" />
          {/* 左右遮罩與可視範圍拖曳窗 */}
          {brushX1 > padL && <rect x={padL} y={navTop} width={brushX1 - padL} height={navPlotH} fill="rgba(148, 163, 184, 0.22)" />}
          {brushX2 < padL + plotW && <rect x={brushX2} y={navTop} width={padL + plotW - brushX2} height={navPlotH} fill="rgba(148, 163, 184, 0.22)" />}
          <rect x={brushX1} y={navTop} width={brushW} height={navPlotH} fill="rgba(25, 118, 210, 0.14)" stroke="#1976d2" strokeWidth="1.2" />
          <rect x={brushX1 - 3} y={navTop + 6} width={6} height={navPlotH - 12} rx="2" fill="#1976d2" />
          <rect x={brushX1 + brushW - 3} y={navTop + 6} width={6} height={navPlotH - 12} rx="2" fill="#1976d2" />
        </svg>
      )}
    </Box>
  </Paper>;
}

/** 關鍵支撐阻力階梯 ＆ 技術觀察方案面板（過濾未啟用之空白卡片，呈現可讀價位表）。 */
export function ActionableLevelsAndPlansPanel({ levels, tradePlans, reportPlansReasons, horizon, onHorizonChange }: {
  levels?: SupportResistance | null;
  tradePlans?: TradePlanResult | null;
  reportPlansReasons?: Array<{ code: string; component: string; message: string }>;
  horizon: AnalysisHorizon;
  onHorizonChange: (next: AnalysisHorizon) => void;
}) {
  const { t } = useTranslation();
  const allPlans = tradePlans?.plans ?? [];
  const usablePlans = allPlans.filter(p => p.status !== "UNAVAILABLE");
  const gatedPlans = allPlans.filter(p => p.status === "UNAVAILABLE");
  const gatedSummary = Array.from(new Set(
    gatedPlans.flatMap(p => (p.reasons ?? []).map(r => readableReason(t, r))),
  ));

  const renderZoneTable = (title: string, zones: LevelZone[] | undefined, color: string) => (
    <Box>
      <Typography variant="subtitle2" fontWeight={700} sx={{ color, mb: 0.8 }}>{title}</Typography>
      {zones?.length ? <TableContainer><Table size="small">
        <TableHead><TableRow>
          <TableCell sx={{ whiteSpace: "nowrap" }}>週期</TableCell>
          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>區間下緣–上緣</TableCell>
          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>距現價</TableCell>
          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>有效強度</TableCell>
        </TableRow></TableHead>
        <TableBody>{zones.map(z => <TableRow key={z.id}>
          <TableCell sx={{ whiteSpace: "nowrap" }}>{readableState(t, z.timeframe)}</TableCell>
          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{formatNumber(z.lower)}–{formatNumber(z.upper)}</TableCell>
          <TableCell align="right" sx={{ whiteSpace: "nowrap", color }}>
            {formatSignedPct(z.role === "SUPPORT" ? z.distanceUpperPercent : z.distanceLowerPercent)}
          </TableCell>
          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>{formatNumber(z.effectiveStrength, 1)}（{readableState(t, z.strengthLabel)}）</TableCell>
        </TableRow>)}</TableBody>
      </Table></TableContainer> : <Typography variant="body2" color="text.secondary">暫無有效區域紀錄。</Typography>}
    </Box>
  );

  return <Paper component="section" aria-label="關鍵價位與技術觀察方案" variant="outlined" sx={{ p: 2.5, mt: 2 }}>
    <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} spacing={1}>
      <Box>
        <Typography variant="h6" fontWeight={700}>關鍵支撐阻力階梯 ＆ 技術觀察方案</Typography>
        <Typography variant="body2" color="text.secondary">
          進場條件、止損與支撐阻力皆由後端計算，與估值參考分開呈現；未達風控門檻的候選區僅列為觀察參考，不冒充買賣指令。
        </Typography>
      </Box>
      <ButtonGroup size="small" aria-label="持有週期切換">
        <Button variant={horizon === "POSITION" ? "contained" : "outlined"} onClick={() => onHorizonChange("POSITION")}>中長線（週線支撐）</Button>
        <Button variant={horizon === "SWING" ? "contained" : "outlined"} onClick={() => onHorizonChange("SWING")}>短線波段（日線支撐）</Button>
      </ButtonGroup>
    </Stack>

    {tradePlans && !tradePlans.contextEvaluated && <Alert severity="info" sx={{ mt: 1.5 }}>市場環境判斷尚未完成，以下僅作技術觀察。</Alert>}

    {usablePlans.length > 0 ? (
      <Grid container spacing={2} sx={{ mt: 1 }}>
        {usablePlans.map(plan => {
          const rrVal = plan.riskReward?.status === "AVAILABLE" ? plan.riskReward.value : null;
          return <Grid key={plan.planId} size={{ xs: 12, md: 6 }}>
            <Stack spacing={0.6} sx={{ p: 2, border: "1px solid", borderColor: "divider", borderRadius: 1 }}>
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography fontWeight={700}>{readableState(t, plan.setupType)}</Typography>
                <Chip size="small" color={plan.status === "ACTIVE" ? "success" : "default"} label={readableState(t, plan.status)} />
              </Stack>
              <Typography>進場區間：<b>{plan.entryRange ? `${formatNumber(plan.entryRange.lower)}–${formatNumber(plan.entryRange.upper)}` : "—"}</b></Typography>
              <Typography variant="body2">觸發條件：{plan.trigger ?? "—"}</Typography>
              <Typography variant="body2">保護止損：{formatNumber(plan.stop?.protectivePrice)} · 收盤失效價：{formatNumber(plan.stop?.closeThreshold)}</Typography>
              {plan.stop?.requiredCloses != null && plan.stop?.evaluationTimeframe && (
                <Typography variant="caption" color="text.secondary">失效確認：連續 {plan.stop.requiredCloses} 個{readableState(t, plan.stop.evaluationTimeframe)}收盤低於失效價。</Typography>
              )}
              <Typography variant="body2">分批目標價：{plan.targets?.map(tg => formatNumber(tg.price)).join(" ／ ") || "—"}</Typography>
              <Typography variant="body2">扣除成本淨風報比（Net R/R）：<b>{formatNumber(rrVal?.netRR)}</b></Typography>
              {plan.validThroughSession && <Typography variant="caption">有效交易日截至：{plan.validThroughSession}</Typography>}
              {plan.reasons?.map((reason, idx) => <Typography key={idx} variant="caption" color="text.secondary">· {readableReason(t, reason)}</Typography>)}
            </Stack>
          </Grid>;
        })}
      </Grid>
    ) : gatedSummary.length > 0 ? (
      <Alert severity="info" sx={{ mt: 1.5 }}>
        <b>目前無自動啟用之右側進場方案</b>（共評估 {gatedPlans.length} 個候選支撐區，未啟用原因：{gatedSummary.join("；")}）。請參考下方實際支撐與阻力價位階梯觀察。
      </Alert>
    ) : (
      <Typography color="text.secondary" sx={{ mt: 1.5 }}>
        {reportPlansReasons?.map(r => readableReason(t, r)).join("；") || "暫無可用技術方案。"}
      </Typography>
    )}

    <Divider sx={{ my: 2 }} />
    <Grid container spacing={3}>
      <Grid size={{ xs: 12, md: 6 }}>{renderZoneTable("下方支撐防守階梯（由近至遠）", levels?.supports, "#15803d")}</Grid>
      <Grid size={{ xs: 12, md: 6 }}>{renderZoneTable("上方阻力減倉階梯（由近至遠）", levels?.resistances, "#b91c1c")}</Grid>
    </Grid>
  </Paper>;
}
