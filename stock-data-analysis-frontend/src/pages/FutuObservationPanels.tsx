import React from "react";
import { Accordion, AccordionDetails, AccordionSummary, Box, Divider, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { ExpandMore } from "@mui/icons-material";
import { useTranslation } from "react-i18next";
import { TFunction } from "i18next";
import { FutuObservations, MetricResult } from "../services/types/dto/analysis";
import { formatNumber, reasonLabel, statusLabel } from "./AnalysisPanels";
import { formatHongKongDateTime } from "../utils/hongKongTime";

const renderDerivedMetric = (t: TFunction, m?: MetricResult<number> | null, isPct = false) => {
  if (!m) return "—";
  if (m.status === "AVAILABLE" && m.value != null && Number.isFinite(m.value)) {
    if (isPct) {
      const pct = m.value * 100;
      return `${pct > 0 ? "+" : ""}${pct.toFixed(2)}%`;
    }
    return `${formatNumber(m.value)}x`;
  }
  const code = m.reasons?.[0]?.code;
  return code ? reasonLabel(t, code) : statusLabel(m.status);
};

export function FutuObservationPanels({ observations }: { observations: FutuObservations }) {
  const { t } = useTranslation();
  const valuation = observations.valuation;
  const forward = observations.forward;
  return <Paper component="section" aria-label="富途市場觀察" variant="outlined" sx={{ mt: 2, borderRadius: 1, overflow: "hidden" }}>
    <Accordion disableGutters elevation={0} defaultExpanded={false} sx={{ "&:before": { display: "none" } }}>
      <AccordionSummary expandIcon={<ExpandMore />} sx={{ px: 2.5, py: 0.5 }}>
        <Box>
          <Typography fontWeight={700}>富途市場觀察與分析師預期（點擊展開）</Typography>
          <Typography variant="caption" color="text.secondary">
            供應商原生快照倍數、衍生前瞻指標與盈利預期分開呈現，不替代 SEC／Futu 已公布財報分母
          </Typography>
        </Box>
      </AccordionSummary>
      <AccordionDetails sx={{ px: 2.5, pt: 0, pb: 2.5 }}>
        <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.5 }}>供應商原生快照倍數</Typography>
        {valuation ? <>
          <Typography variant="body2" sx={{ mb: 1 }}>觀察日期：{valuation.observationDate} · 取得時間（香港）：{formatHongKongDateTime(valuation.observedAt, "zh-HK")}</Typography>
          <Stack direction="row" spacing={3} useFlexGap flexWrap="wrap">
            {([["P/E", valuation.pe], ["P/B", valuation.pb], ["P/S", valuation.ps], ["預期 P/E", valuation.forwardPe], ["預期 P/S", valuation.forwardPs]] as const)
              .map(([label, value]) => <Typography key={label}>{label}：{formatNumber(value)}</Typography>)}
          </Stack>
        </> : <Typography color="text.secondary" sx={{ my: 1 }}>尚無供應商估值觀察。</Typography>}

        {forward && <>
          <Divider sx={{ my: 1.8 }} />
          <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.5 }}>
            衍生前瞻觀察摘要（估值日 {forward.valuationDate} · 來源 FUTU 預期）
          </Typography>
          <Stack direction="row" spacing={3} useFlexGap flexWrap="wrap" sx={{ mb: 0.5 }}>
            <Typography variant="body2">Forward P/E：{renderDerivedMetric(t, forward.forwardPe)}</Typography>
            <Typography variant="body2">Forward P/S：{renderDerivedMetric(t, forward.forwardPs)}</Typography>
            <Typography variant="body2">Forward PEG：{renderDerivedMetric(t, forward.forwardPeg)}</Typography>
            <Typography variant="body2">30D EPS 修正：{renderDerivedMetric(t, forward.epsRevision30d, true)}</Typography>
            <Typography variant="body2">90D EPS 修正：{renderDerivedMetric(t, forward.epsRevision90d, true)}</Typography>
          </Stack>
        </>}

        <Typography variant="subtitle1" fontWeight={700} sx={{ mt: 2 }}>盈利預期明細</Typography>
        {observations.earnings?.length ? <TableContainer><Table size="small" aria-label="盈利預期">
          <TableHead><TableRow>{([
            ["財政期間", "left"], ["期間類型", "left"], ["起迄日", "left"], ["預期 EPS", "right"], ["預期營收", "right"], ["幣別", "left"], ["觀察日期", "left"], ["來源", "left"],
          ] as const).map(([label, align]) => <TableCell key={label} align={align} sx={{ whiteSpace: "nowrap" }}>{label}</TableCell>)}</TableRow></TableHead>
          <TableBody>{observations.earnings.map((row, index) => <TableRow key={row.fiscalPeriod + ":" + row.periodType + ":" + row.observationDate + ":" + index}>
            <TableCell>{row.fiscalPeriod}</TableCell><TableCell>{row.periodType}</TableCell>
            <TableCell>{row.periodStart && row.periodEnd ? row.periodStart + " 至 " + row.periodEnd : row.periodEnd ?? "—"}</TableCell>
            <TableCell align="right">{formatNumber(row.eps)}</TableCell><TableCell align="right">{formatNumber(row.revenue)}</TableCell>
            <TableCell>{row.currency ?? "—"}</TableCell><TableCell>{row.observationDate}</TableCell><TableCell>{row.source}</TableCell>
          </TableRow>)}</TableBody>
        </Table></TableContainer> : <Typography color="text.secondary">尚無盈利預期。</Typography>}
      </AccordionDetails>
    </Accordion>
  </Paper>;
}
