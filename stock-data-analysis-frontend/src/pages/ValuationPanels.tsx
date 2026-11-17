import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Alert, Box, Button, ButtonGroup, FormControl, InputLabel, MenuItem, Paper, Select, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from "@mui/material";
import { apiClient } from "../services/apiClient";
import { Financials, FinancialValue, ValuationResult } from "../services/types/dto/valuation";
import { formatHongKongDateTime } from "../utils/hongKongTime";
import { formatNumber, metricLabel, reasonLabel, statusLabel } from "./AnalysisPanels";

const clampIdx = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

const SVG_W = 960;
const SVG_PAD_L = 68;
const SVG_PLOT_W = 868;

/** 將瀏覽器可視座標精準換算為 SVG viewBox X 座標，修正寬螢幕 maxHeight 留白造成的偏移。 */
export function svgXFromClient(svg: SVGSVGElement, clientX: number, clientY: number, viewBoxW: number): { svgX: number; scaleX: number } {
  const ctm = typeof svg.getScreenCTM === "function" ? svg.getScreenCTM() : null;
  if (ctm && Number.isFinite(ctm.a) && ctm.a > 0) {
    if (typeof DOMPoint !== "undefined") {
      const pt = new DOMPoint(clientX, clientY).matrixTransform(ctm.inverse());
      return { svgX: pt.x, scaleX: ctm.a };
    }
    return { svgX: (clientX - ctm.e) / ctm.a, scaleX: ctm.a };
  }
  const rect = svg.getBoundingClientRect();
  const widthPx = rect.width || viewBoxW;
  return { svgX: ((clientX - rect.left) / widthPx) * viewBoxW, scaleX: widthPx / viewBoxW };
}

export function SeriesChart({ title, points }: { title: string; points: Array<{ date: string; value: number | null }> }) {
  const ordered = useMemo(
    () => [...points].filter(point => Number.isFinite(Date.parse(point.date))).sort((a, b) => a.date.localeCompare(b.date)),
    [points],
  );
  const dateKey = useMemo(() => ordered.map(point => point.date).join(","), [ordered]);
  const seriesKey = useMemo(() => ordered.map(point => `${point.date}:${point.value ?? ""}`).join("|"), [ordered]);
  const total = ordered.length;
  const minZoom = Math.min(total, 4);
  const [win, setWin] = useState<[number, number] | null>(null);
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement | null>(null);
  const dragRef = useRef<{ clientX: number; start: number; end: number } | null>(null);

  useEffect(() => {
    setWin(null);
  }, [dateKey]);

  useEffect(() => {
    setHoverIdx(null);
  }, [seriesKey]);

  const [winStart, winEnd] = useMemo<[number, number]>(() => {
    if (!win || total === 0) return [0, total];
    const span = clampIdx(win[1] - win[0], 1, total);
    const s = clampIdx(win[0], 0, total - span);
    return [s, s + span];
  }, [win, total]);

  const zoomBy = useCallback((factor: number, ratio = 0.8) => {
    if (total <= minZoom) return;
    const curSpan = winEnd - winStart;
    let nextSpan = Math.round(curSpan * factor);
    if (factor < 1 && nextSpan === curSpan) nextSpan = curSpan - 1;
    if (factor > 1 && nextSpan === curSpan) nextSpan = curSpan + 1;
    nextSpan = clampIdx(nextSpan, minZoom, total);
    const r = Math.max(0, Math.min(1, ratio));
    const anchor = winStart + r * curSpan;
    const nextStart = clampIdx(Math.round(anchor - r * nextSpan), 0, total - nextSpan);
    setWin(nextStart === 0 && nextSpan === total ? null : [nextStart, nextStart + nextSpan]);
    setHoverIdx(null);
  }, [total, minZoom, winStart, winEnd]);

  useEffect(() => {
    const el = svgRef.current;
    if (!el || total < 6) return;
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY === 0) return;
      event.preventDefault();
      const { svgX } = svgXFromClient(el, event.clientX, event.clientY, SVG_W);
      zoomBy(event.deltaY < 0 ? 0.78 : 1.28, (svgX - SVG_PAD_L) / SVG_PLOT_W);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [total, zoomBy]);

  const applyYearsPreset = (years: number | null) => {
    if (years == null || total < 4) { setWin(null); setHoverIdx(null); return; }
    const last = new Date(ordered[total - 1].date + "T00:00:00Z");
    last.setUTCFullYear(last.getUTCFullYear() - years);
    const cutoff = last.toISOString().slice(0, 10);
    const idx = ordered.findIndex(p => p.date >= cutoff);
    if (idx <= 0) { setWin(null); } else { setWin([idx, total]); }
    setHoverIdx(null);
  };

  const sliced = ordered.slice(winStart, winEnd);
  const valid = sliced.filter(point => point.value != null && Number.isFinite(point.value));
  const allValidCount = ordered.filter(point => point.value != null && Number.isFinite(point.value)).length;
  if (allValidCount < 2) return <Typography color="text.secondary" sx={{ my: 2 }}>可用歷史資料不足，暫時無法繪製{title}。</Typography>;
  const windowInsufficient = valid.length < 2;
  const renderValid = windowInsufficient ? valid : valid;
  const renderOrdered = sliced;

  const minX = windowInsufficient ? 0 : Date.parse(renderValid[0].date);
  const maxX = windowInsufficient ? 1 : Date.parse(renderValid[renderValid.length - 1].date);
  const values = renderValid.map(point => point.value as number);
  const minY = windowInsufficient ? 0 : Math.min(...values);
  const maxY = windowInsufficient ? 1 : Math.max(...values);
  const spanY = maxY - minY || Math.abs(maxY) * 0.01 || 1;
  const midY = (minY + maxY) / 2;
  const x = (date: string) => SVG_PAD_L + (Date.parse(date) - minX) / (maxX - minX || 1) * SVG_PLOT_W;
  const y = (value: number) => 175 - (value - minY) / spanY * 145;
  let connected = false;
  const path = windowInsufficient ? "" : renderOrdered.map(point => {
    if (point.value == null || !Number.isFinite(point.value)) { connected = false; return ""; }
    const command = connected ? "L" : "M";
    connected = true;
    return command + x(point.date) + "," + y(point.value);
  }).join(" ");

  const activePoint = windowInsufficient
    ? null
    : hoverIdx != null && hoverIdx >= 0 && hoverIdx < renderValid.length ? renderValid[hoverIdx] : renderValid[renderValid.length - 1];
  const hoveredPoint = !windowInsufficient && hoverIdx != null && hoverIdx >= 0 && hoverIdx < renderValid.length ? renderValid[hoverIdx] : null;

  return <Box sx={{ width: "100%", overflowX: "auto", my: 1, userSelect: "none" }}>
    {total >= 6 && (
      <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} spacing={1} sx={{ mb: 1 }}>
        <Typography variant="caption" color="text.secondary">
          顯示區間：{renderOrdered[0]?.date} ～ {renderOrdered[renderOrdered.length - 1]?.date}
          {activePoint ? ` · ${activePoint.date}：${formatNumber(activePoint.value)}` : ""}
        </Typography>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
          <ButtonGroup size="small" aria-label={`${title}預設區間`}>
            <Button size="small" variant="outlined" onClick={() => applyYearsPreset(1)}>1年</Button>
            <Button size="small" variant="outlined" onClick={() => applyYearsPreset(3)}>3年</Button>
            <Button size="small" variant={win == null ? "contained" : "outlined"} onClick={() => applyYearsPreset(null)}>全部</Button>
          </ButtonGroup>
          <ButtonGroup size="small" aria-label={`${title}縮放控制`}>
            <Button size="small" disabled={renderOrdered.length <= minZoom} onClick={() => zoomBy(0.72)}>放大 ＋</Button>
            <Button size="small" disabled={win == null} onClick={() => zoomBy(1.38)}>縮小 －</Button>
            <Button size="small" disabled={win == null} onClick={() => { setWin(null); setHoverIdx(null); }}>重設</Button>
          </ButtonGroup>
        </Stack>
      </Stack>
    )}
    {windowInsufficient ? (
      <Typography color="text.secondary" variant="body2" sx={{ my: 2 }}>
        所選區間內可用資料不足，請切換其他區間或按「全部」查看歷史數據。
      </Typography>
    ) : (
    <svg ref={svgRef} role="img" aria-label={title} viewBox={`0 0 ${SVG_W} 215`}
      style={{ width: "100%", minWidth: 360, maxHeight: 280, display: "block", cursor: total >= 6 ? "crosshair" : "default" }}
      onMouseDown={event => {
        if (total < 6 || event.button !== 0) return;
        dragRef.current = { clientX: event.clientX, start: winStart, end: winEnd };
      }}
      onMouseMove={event => {
        if (total < 6) return;
        const { svgX, scaleX } = svgXFromClient(event.currentTarget, event.clientX, event.clientY, SVG_W);
        if (dragRef.current) {
          const d = dragRef.current;
          const span = d.end - d.start;
          const dxSvg = (event.clientX - d.clientX) / scaleX;
          const delta = Math.round((dxSvg / SVG_PLOT_W) * span);
          const nextStart = clampIdx(d.start - delta, 0, total - span);
          setWin([nextStart, nextStart + span]);
          return;
        }
        let bestIdx = 0;
        let bestDist = Infinity;
        renderValid.forEach((pt, i) => {
          const dist = Math.abs(x(pt.date) - svgX);
          if (dist < bestDist) { bestDist = dist; bestIdx = i; }
        });
        setHoverIdx(bestIdx);
      }}
      onMouseUp={() => { dragRef.current = null; }}
      onMouseLeave={() => { dragRef.current = null; setHoverIdx(null); }}>
      <title>{title}</title>
      <line x1={SVG_PAD_L} x2={SVG_PAD_L + SVG_PLOT_W} y1="175" y2="175" stroke="#b6c0cf" />
      <line x1={SVG_PAD_L} x2={SVG_PAD_L + SVG_PLOT_W} y1="102.5" y2="102.5" stroke="#eef2f6" strokeDasharray="3,3" />
      <line x1={SVG_PAD_L} x2={SVG_PAD_L + SVG_PLOT_W} y1="30" y2="30" stroke="#e1e6ed" />
      {hoveredPoint && (
        <line x1={x(hoveredPoint.date)} x2={x(hoveredPoint.date)} y1="30" y2="175" stroke="#94a3b8" strokeDasharray="3,3" pointerEvents="none" />
      )}
      <text x={SVG_PAD_L - 8} y="35" textAnchor="end" fontSize="11" fill="currentColor">{formatNumber(maxY)}</text>
      <text x={SVG_PAD_L - 8} y="106" textAnchor="end" fontSize="10" fill="#6b7280">{formatNumber(midY)}</text>
      <text x={SVG_PAD_L - 8} y="180" textAnchor="end" fontSize="11" fill="currentColor">{formatNumber(minY)}</text>
      <path d={path} fill="none" stroke="#1976d2" strokeWidth="2" />
      {renderValid.length <= 90 && renderValid.map((point, idx) => <circle key={point.date} cx={x(point.date)} cy={y(point.value as number)}
        r={hoverIdx === idx ? "4.5" : "2.5"} fill={hoverIdx === idx ? "#0d47a1" : "#1976d2"}>
        <title>{point.date + "：" + formatNumber(point.value)}</title>
      </circle>)}
      <text x={SVG_PAD_L} y="205" fontSize="11" fill="currentColor">{renderValid[0].date}</text>
      {renderValid.length >= 5 && (
        <text x={SVG_PAD_L + SVG_PLOT_W / 2} y="205" textAnchor="middle" fontSize="11" fill="#6b7280">{renderValid[Math.floor(renderValid.length / 2)].date}</text>
      )}
      <text x={SVG_PAD_L + SVG_PLOT_W} y="205" textAnchor="end" fontSize="11" fill="currentColor">{renderValid[renderValid.length - 1].date}</text>
    </svg>
    )}
  </Box>;
}

const financialLabels: Array<[keyof Financials, string, "money" | "perShare" | "shares"]> = [
  ["revenueTtm", "TTM 營業收入", "money"], ["netIncomeCommonTtm", "TTM 普通股淨利", "money"],
  ["epsTtm", "TTM 每股盈利", "perShare"], ["operatingCashFlowTtm", "TTM 營運現金流", "money"],
  ["capitalExpenditureTtm", "TTM 資本支出", "money"], ["freeCashFlowTtm", "TTM 自由現金流", "money"],
  ["ebitdaTtm", "TTM EBITDA", "money"], ["commonEquity", "普通股權益", "money"],
  ["sharesOutstanding", "流通在外股數", "shares"], ["bookValuePerShare", "每股帳面值", "perShare"],
  ["totalDebt", "有息債務", "money"], ["cashLikeAssets", "現金及可計入的投資", "money"],
  ["preferredEquity", "優先股權益", "money"], ["noncontrollingInterest", "非控制權益", "money"],
];

export function FinancialsPanel({ financials }: { financials: Financials | null }) {
  const { t } = useTranslation();
  return <Paper component="section" aria-label="估值使用的財務數據" variant="outlined" sx={{ p: 2.5, mt: 2 }}>
    <Typography variant="h6" fontWeight={700}>估值使用的財務數據</Typography>
    <Typography color="text.secondary" variant="body2" sx={{ mt: 1 }}>所有數值與期間由後端計算；缺失與不適用的資料保持空缺，不以零補值。</Typography>
    {!financials ? <Typography sx={{ mt: 2 }}>尚無財務數據</Typography> : <TableContainer sx={{ mt: 1 }}>
      <Table size="small">
        <TableHead><TableRow>{([
          ["指標", "left"], ["數值", "right"], ["單位", "left"], ["財務期間", "left"], ["可用時間（香港）", "left"], ["狀態／計算口徑", "left"],
        ] as const).map(([label, align]) => <TableCell key={label} align={align} sx={{ whiteSpace: "nowrap" }}>{label}</TableCell>)}</TableRow></TableHead>
        <TableBody>{financialLabels.map(([key, label, unit]) => {
          const value = financials[key] as FinancialValue | undefined;
          return <TableRow key={key}>
            <TableCell sx={{ whiteSpace: "nowrap" }}>{label}</TableCell>
            <TableCell align="right">{value?.status === "AVAILABLE" ? formatNumber(value.value) : "—"}</TableCell>
            <TableCell>{unit === "shares" ? "股" : financials.reportingCurrency ? financials.reportingCurrency + (unit === "perShare" ? "／股" : "") : "—"}</TableCell>
            <TableCell sx={{ whiteSpace: "nowrap" }}>{value?.periodStart && value.periodEnd && value.periodStart !== value.periodEnd
              ? value.periodStart + " 至 " + value.periodEnd : value?.periodEnd ?? "—"}</TableCell>
            <TableCell>{value?.availableAt ? formatHongKongDateTime(value.availableAt, "zh-HK") : "—"}</TableCell>
            <TableCell>{statusLabel(value?.status)}
              {value?.status !== "AVAILABLE" && <Typography variant="caption" display="block">{reasonLabel(t, value?.reasonCode)}</Typography>}
              {value?.calculation && <Typography variant="caption" display="block" color="text.secondary">{value.calculation}</Typography>}
            </TableCell>
          </TableRow>;
        })}</TableBody>
      </Table>
    </TableContainer>}
  </Paper>;
}

export function ValuationHistoryPanel({ figi, initial }: { figi: string; initial: ValuationResult[] }) {
  const sortedInitial = useMemo(() => [...initial].sort((a, b) => a.valuationDate.localeCompare(b.valuationDate)), [initial]);
  const [metric, setMetric] = useState<keyof ValuationResult["metrics"]>("pe");
  const [draft, setDraft] = useState({ from: sortedInitial[0]?.valuationDate ?? "", through: sortedInitial[sortedInitial.length - 1]?.valuationDate ?? "" });
  const [range, setRange] = useState<{ from: string; through: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const history = useQuery<ValuationResult[]>({
    queryKey: ["valuation-history", figi, range],
    enabled: range != null,
    queryFn: async () => (await apiClient.get<ValuationResult[]>("valuation/" + encodeURIComponent(figi) + "/history", {
      params: { ...range, frequency: "MONTH_END" },
    })).data,
  });
  const rows = useMemo(
    () => [...(range ? history.data ?? [] : initial)].sort((a, b) => a.valuationDate.localeCompare(b.valuationDate)),
    [range, history.data, initial],
  );
  const chartPoints = useMemo(
    () => rows.map(row => ({ date: row.valuationDate, value: row.metrics[metric]?.status === "AVAILABLE" ? row.metrics[metric].value : null })),
    [rows, metric],
  );
  const metricCodes = Object.keys(rows[0]?.metrics ?? initial[0]?.metrics ?? {}) as Array<keyof ValuationResult["metrics"]>;
  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!draft.from || !draft.through || draft.from > draft.through) { setError("請選擇有效的開始及結束日期。"); return; }
    setError(null);
    setRange({ ...draft });
  };
  return <Paper component="section" aria-label="歷史估值" variant="outlined" sx={{ p: 2.5, mt: 2 }}>
    <Typography variant="h6" fontWeight={700}>歷史估值</Typography>
    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>以當日收盤價配對同財季最新修訂財報計算（非當日投資者即時已知之 Point-in-Time 回測口徑）。日期區間採月末樣本，虧損或分母非正月份留空不補零。</Typography>
    <Stack component="form" onSubmit={submit} direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ my: 2 }}>
      <TextField label="開始日期" type="date" size="small" value={draft.from} InputLabelProps={{ shrink: true }} onChange={event => setDraft({ ...draft, from: event.target.value })} />
      <TextField label="結束日期" type="date" size="small" value={draft.through} InputLabelProps={{ shrink: true }} onChange={event => setDraft({ ...draft, through: event.target.value })} />
      <Button type="submit" variant="outlined" disabled={history.isFetching}>查詢歷史</Button>
      {metricCodes.length > 0 && <FormControl size="small" sx={{ minWidth: 130 }}>
        <InputLabel id="history-metric-label">圖表指標</InputLabel>
        <Select labelId="history-metric-label" label="圖表指標" value={metric} onChange={event => setMetric(event.target.value as keyof ValuationResult["metrics"])}>
          {metricCodes.map(code => <MenuItem key={code} value={code}>{metricLabel(code)}</MenuItem>)}
        </Select>
      </FormControl>}
    </Stack>
    {error && <Alert severity="warning">{error}</Alert>}
    {history.isError && <Alert severity="error">歷史估值載入失敗：{(history.error as Error).message}</Alert>}
    {history.isFetching ? <Typography>正在載入歷史估值…</Typography> : <>
      <SeriesChart title={metricLabel(metric) + " 歷史走勢"} points={chartPoints} />
      <TableContainer sx={{ maxHeight: 320 }}><Table size="small" stickyHeader aria-label="歷史估值數據">
        <TableHead><TableRow>{([
          ["估值日", "left"], ["股價", "right"], ["P/E", "right"], ["P/B", "right"], ["P/S", "right"], ["P/FCF", "right"], ["EV/EBITDA", "right"], ["狀態", "left"],
        ] as const).map(([label, align]) => <TableCell key={label} align={align} sx={{ whiteSpace: "nowrap" }}>{label}</TableCell>)}</TableRow></TableHead>
        <TableBody>{[...rows].reverse().map(row => <TableRow key={row.valuationDate}>
          <TableCell>{row.valuationDate}</TableCell><TableCell align="right">{formatNumber(row.price)} {row.currency ?? ""}</TableCell>
          {(["pe", "pb", "ps", "pfcf", "evEbitda"] as const).map(code => <TableCell key={code} align="right" title={row.metrics[code]?.reasonCode ?? undefined}>{row.metrics[code]?.status === "AVAILABLE" ? formatNumber(row.metrics[code].value) : "—"}</TableCell>)}
          <TableCell title={row.reasonCode ?? undefined}>{statusLabel(row.status)}</TableCell>
        </TableRow>)}</TableBody>
      </Table></TableContainer>
    </>}
  </Paper>;
}
