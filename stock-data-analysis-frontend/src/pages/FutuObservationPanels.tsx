import React from "react";
import { Accordion, AccordionDetails, AccordionSummary, Box, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { ExpandMore } from "@mui/icons-material";
import { FutuObservations } from "../services/types/dto/analysis";
import { formatNumber } from "./AnalysisPanels";
import { formatHongKongDateTime } from "../utils/hongKongTime";

export function FutuObservationPanels({ observations }: { observations: FutuObservations }) {
  const valuation = observations.valuation;
  return <Paper component="section" aria-label="富途市場觀察" variant="outlined" sx={{ mt: 2, borderRadius: 1, overflow: "hidden" }}>
    <Accordion disableGutters elevation={0} defaultExpanded={false} sx={{ "&:before": { display: "none" } }}>
      <AccordionSummary expandIcon={<ExpandMore />} sx={{ px: 2.5, py: 0.5 }}>
        <Box>
          <Typography fontWeight={700}>富途市場觀察與分析師預期（點擊展開）</Typography>
          <Typography variant="caption" color="text.secondary">
            供應商原生倍數與盈利預期分開呈現，不替代 SEC／Futu 已公布財報分母
          </Typography>
        </Box>
      </AccordionSummary>
      <AccordionDetails sx={{ px: 2.5, pt: 0, pb: 2.5 }}>
        {valuation ? <>
          <Typography variant="body2" sx={{ mb: 1 }}>觀察日期：{valuation.observationDate} · 取得時間（香港）：{formatHongKongDateTime(valuation.observedAt, "zh-HK")}</Typography>
          <Stack direction="row" spacing={3} useFlexGap flexWrap="wrap">
            {([["P/E", valuation.pe], ["P/B", valuation.pb], ["P/S", valuation.ps], ["預期 P/E", valuation.forwardPe], ["預期 P/S", valuation.forwardPs]] as const)
              .map(([label, value]) => <Typography key={label}>{label}：{formatNumber(value)}</Typography>)}
          </Stack>
        </> : <Typography color="text.secondary" sx={{ my: 1 }}>尚無供應商估值觀察。</Typography>}
        <Typography variant="subtitle1" fontWeight={700} sx={{ mt: 2 }}>盈利預期</Typography>
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
