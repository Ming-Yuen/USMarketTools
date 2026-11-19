import React, { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, CircularProgress, Grid, Stack, TextField, Typography } from "@mui/material";
import { ArrowBack, ExpandMore } from "@mui/icons-material";
import { apiClient } from "../services/apiClient";
import { apiConfig } from "../apiConfig";
import { AnalysisHorizon, AnalysisReportRequest, MetricResult, StockAnalysisReport } from "../services/types/dto/analysis";
import { readableReason, reasonLabel, statusLabel, TechnicalIndicatorsPanel, ValuationReferencePanel } from "./AnalysisPanels";
import { ExecutiveSummaryPanel } from "./ExecutiveSummaryPanel";
import { FundamentalsPanel } from "./FundamentalsPanel";
import { FinancialsPanel, ValuationHistoryPanel } from "./ValuationPanels";
import { FutuObservationPanels } from "./FutuObservationPanels";
import { IndustryValuationProposalPanel } from "./IndustryValuationProposalPanel";
import { ActionableLevelsAndPlansPanel, DecisionSnapshotStrip, InteractivePriceWorkbench } from "./TechnicalChartWorkbench";
import { formatHongKongDateTime } from "../utils/hongKongTime";

const valueOf = <T,>(result?: MetricResult<T> | null): T | null => result?.status === "AVAILABLE" ? result.value : null;

export function StockAnalysisPage() {
  const { t } = useTranslation();
  const { figi = "" } = useParams<{ figi: string }>();
  const navigate = useNavigate();
  const [dateInput, setDateInput] = useState("");
  const [valuationDate, setValuationDate] = useState("");
  const [horizon, setHorizon] = useState<AnalysisHorizon>("POSITION");
  const reportQuery = useQuery<StockAnalysisReport>({
    queryKey: horizon === "POSITION" ? ["analysis-report", figi, valuationDate] : ["analysis-report", figi, valuationDate, horizon],
    enabled: Boolean(figi),
    queryFn: async () => {
      const request: AnalysisReportRequest = {
        figi,
        ...(valuationDate ? { valuationDate } : {}),
        ...(horizon !== "POSITION" ? { horizon } : {}),
      };
      return (await apiClient.post<StockAnalysisReport>(apiConfig.analysisReport.url, request)).data;
    },
  });
  const report = reportQuery.data;
  const valuation = report?.valuation;
  const structure = valueOf(report?.priceStructure);
  const market = valueOf(report?.marketContext);
  const levels = valueOf(report?.supportResistance);
  const tradePlans = valueOf(report?.tradePlans);
  const summary = valueOf(report?.summary);
  const error = reportQuery.error as { response?: { data?: { message?: string } }; message?: string } | null;

  return <Box sx={{ width: "100%", minWidth: 0 }}>
    <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={2} sx={{ mb: 2 }}>
      <Box>
        <Typography variant="h5" fontWeight={800}>{report?.symbol ?? figi} · 股票分析</Typography>
        <Typography color="text.secondary">{report?.name ?? ""} · FIGI {figi}</Typography>
        {report && <Typography variant="caption" color="text.secondary">估值日：{report.valuationDate}
          {valuation?.analysisAsOf ? " · 分析切點（香港）：" + formatHongKongDateTime(valuation.analysisAsOf, "zh-HK") : ""}
          {valuation?.calculatedAt ? " · 計算時間（香港）：" + formatHongKongDateTime(valuation.calculatedAt, "zh-HK") : ""}
        </Typography>}
      </Box>
      <Stack component="form" direction="row" spacing={1} useFlexGap flexWrap="wrap" onSubmit={event => { event.preventDefault(); setValuationDate(dateInput); }}>
        <TextField label="估值日期（留空為最新）" type="date" size="small" value={dateInput} InputLabelProps={{ shrink: true }} onChange={event => setDateInput(event.target.value)} />
        <Button type="submit" variant="contained" disabled={reportQuery.isFetching}>查詢</Button>
        <Button startIcon={<ArrowBack />} variant="outlined" onClick={() => navigate("/stockAnalysis")}>股票篩選</Button>
      </Stack>
    </Stack>
    {reportQuery.isFetching && <Stack direction="row" spacing={1} alignItems="center" sx={{ my: 2 }}><CircularProgress size={22} /><Typography>正在載入分析…</Typography></Stack>}
    {reportQuery.isError && <Alert severity="error">{error?.response?.data?.message || error?.message || "股票分析載入失敗。"}</Alert>}
    {report && <>
      {valuation?.sharesObservedAt && valuation.analysisAsOf
        && Date.parse(valuation.sharesObservedAt) > Date.parse(valuation.analysisAsOf) && <Alert severity="info" sx={{ mb: 2 }}>
        收市估算使用收市後取得的股數。股數取得時間（香港）：{formatHongKongDateTime(valuation.sharesObservedAt, "zh-HK")}。
      </Alert>}
      {valuation?.status !== "AVAILABLE" && <Alert severity="info" sx={{ mb: 2 }}>
        估值狀態：{statusLabel(valuation?.status)} · {reasonLabel(t, valuation?.reasonCode)}。個別可用指標仍會顯示。
      </Alert>}

      {/* L1. 置頂首屏：綜合估值位置、參考價帶標尺、觀察傾向與當期核心估值倍數 */}
      <ExecutiveSummaryPanel valuation={valuation} summaryResult={report.summary} breakoutDiagnosis={levels?.breakoutDiagnosis} />

      {/* L2. 看圖與時機工作台（嚴格保持位於估值參考三面板之前） */}
      <InteractivePriceWorkbench structure={structure} levels={levels} market={market} valuation={valuation} summary={summary} />
      <DecisionSnapshotStrip valuation={valuation} structure={structure} levels={levels} market={market} />
      <ActionableLevelsAndPlansPanel
        levels={levels}
        tradePlans={tradePlans}
        reportPlansReasons={report.tradePlans?.reasons}
        horizon={horizon}
        onHorizonChange={setHorizon}
      />

      {/* L3. 估值與基本面證據層（歷史參考、同業參考與人工產業假設三個獨立面板並列，緊接基本面與歷史走勢圖） */}
      <Grid container spacing={2} sx={{ mt: 0.5 }}>
        <Grid size={{ xs: 12, lg: 4 }}><ValuationReferencePanel title="歷史市場估值參考" description="比較這隻股票過去實際市場給予的估值倍數。" reference={valuation?.historicalReference} currency={valuation?.currency} /></Grid>
        <Grid size={{ xs: 12, lg: 4 }}><ValuationReferencePanel title="同業市場估值參考" description="比較可比公司的實際市場倍數；優先採用自訂對標或同市值量級對手，樣本不足時依序放寬。" reference={valuation?.peerReference} currency={valuation?.currency} figi={figi} symbol={report?.symbol} fundamentals={report.fundamentals} isHistoricalDate={Boolean(valuationDate)} peerRelativeStrength={report.peerRelativeStrength} /></Grid>
        <Grid size={{ xs: 12, lg: 4 }}><ValuationReferencePanel title="產業估值假設" description="以人工確認的產業模型及倍數建立假設情境，與實際市場參考分開。" reference={valuation?.industryAssumption} currency={valuation?.currency} /></Grid>
      </Grid>
      <FundamentalsPanel fundamentals={report.fundamentals} summary={summary} forward={report.futuObservations?.forward} />
      <ValuationHistoryPanel key={figi + ":" + report.valuationDate} figi={figi} initial={report.history ?? []} />

      {/* L4. 稽核明細折疊區（14 項財務原料、富途觀察預期、29 項完整技術指標與產業假設設定預設收合） */}
      <FinancialsPanel financials={report.financials} />
      {report.futuObservations && <FutuObservationPanels observations={report.futuObservations} />}

      <Accordion variant="outlined" disableGutters sx={{ mt: 2, borderRadius: 1, "&:before": { display: "none" } }}>
        <AccordionSummary expandIcon={<ExpandMore />}>
          <Typography fontWeight={700}>完整技術指標明細與原始解說（點擊展開）</Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ pt: 0 }}>
          <TechnicalIndicatorsPanel detail={market?.momentumDetail} dataThrough={structure?.priceDataThrough} momentum={market?.momentum} />
        </AccordionDetails>
      </Accordion>

      <Accordion variant="outlined" disableGutters sx={{ mt: 2, borderRadius: 1, "&:before": { display: "none" } }}>
        <AccordionSummary expandIcon={<ExpandMore />}>
          <Typography fontWeight={700}>產業估值假設設定與 AI 建議（點擊展開）</Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ pt: 0 }}>
          <IndustryValuationProposalPanel key={figi + ":" + (valuation?.industryCode ?? "")} industryCode={valuation?.industryCode} figi={figi} valuationDate={report.valuationDate}
            reloadReport={async () => (await reportQuery.refetch()).data ?? null} />
        </AccordionDetails>
      </Accordion>

      {!!report.reasons?.length && <Alert severity="info" sx={{ mt: 2 }}>{report.reasons.map(reason => readableReason(t, reason)).join("；")}</Alert>}
    </>}
  </Box>;
}
