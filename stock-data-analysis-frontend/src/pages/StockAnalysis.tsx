import React, { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Chip, CircularProgress, Grid, Stack, TextField, Typography } from "@mui/material";
import { AccountBalanceWalletOutlined, ArrowBack, ExpandMore } from "@mui/icons-material";
import { apiClient } from "../services/apiClient";
import { apiConfig } from "../apiConfig";
import { fetchPortfolioOverview, PORTFOLIO_UPDATED_EVENT } from "../services/portfolioApi";
import { PortfolioDrawer } from "../components/PortfolioDrawer";
import { PortfolioItemView } from "../services/types/dto/portfolio";
import { AnalysisHorizon, AnalysisReportRequest, MetricResult, StockAnalysisReport } from "../services/types/dto/analysis";
import { readableReason, reasonLabel, statusLabel, TechnicalIndicatorsPanel, ValuationReferencePanel } from "./AnalysisPanels";
import { ExecutiveSummaryPanel } from "./ExecutiveSummaryPanel";
import { FundamentalsPanel } from "./FundamentalsPanel";
import { DcfValuationPanel } from "./DcfValuationPanel";
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
  const [portfolioDrawerOpen, setPortfolioDrawerOpen] = useState(false);
  const [currentPortfolioItem, setCurrentPortfolioItem] = useState<PortfolioItemView | null>(null);
  const portfolioSeqRef = React.useRef(0);
  const reloadPortfolioBadge = useCallback(async () => {
    const seq = ++portfolioSeqRef.current;
    if (!figi) {
      setCurrentPortfolioItem(null);
      return;
    }
    try {
      const overview = await fetchPortfolioOverview();
      if (portfolioSeqRef.current === seq) {
        setCurrentPortfolioItem(overview?.items?.find((it) => it.figi === figi) ?? null);
      }
    } catch {
      if (portfolioSeqRef.current === seq) {
        setCurrentPortfolioItem(null);
      }
    }
  }, [figi]);
  useEffect(() => {
    setCurrentPortfolioItem(null);
    reloadPortfolioBadge();
  }, [reloadPortfolioBadge]);

  useEffect(() => {
    const handlePortfolioUpdated = () => {
      reloadPortfolioBadge();
    };
    window.addEventListener(PORTFOLIO_UPDATED_EVENT, handlePortfolioUpdated);
    return () => {
      window.removeEventListener(PORTFOLIO_UPDATED_EVENT, handlePortfolioUpdated);
    };
  }, [reloadPortfolioBadge]);
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
  const fxConversion = valuation?.fxConversion ?? null;
  const isEtf = report?.stockType === "ETF" || report?.stockType === "ETP" || valuation?.reasonCode === "NOT_APPLICABLE_FOR_ETF";
  const structure = valueOf(report?.priceStructure);
  const market = valueOf(report?.marketContext);
  const levels = valueOf(report?.supportResistance);
  const tradePlans = valueOf(report?.tradePlans);
  const summary = valueOf(report?.summary);
  const error = reportQuery.error as { response?: { data?: { message?: string } }; message?: string } | null;

  return <Box sx={{ width: "100%", minWidth: 0 }}>
    <Stack direction={{ xs: "column", md: "row" }} justifyContent="space-between" alignItems={{ md: "center" }} spacing={2} sx={{ mb: 2 }}>
      <Box>
        <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
          <Typography variant="h5" fontWeight={800}>
            {report?.symbol ?? figi} · {isEtf ? t("analysisReport.etf.pageTitleSuffix") : "股票分析"}
          </Typography>
          {isEtf && <Chip size="small" color="info" label={t("analysisReport.etf.badge")} />}
          {!isEtf && fxConversion?.adr && (
            <Chip
              size="small"
              color={fxConversion.adrRatioVerified ? "primary" : "warning"}
              variant="outlined"
              label={fxConversion.adrRatioVerified
                ? t("analysisReport.fxBanner.adrVerifiedChip", { ratio: fxConversion.adrRatio ?? "—" })
                : t("analysisReport.fxBanner.adrUnverifiedChip")}
            />
          )}
        </Stack>
        <Typography color="text.secondary">{report?.name ?? ""} · FIGI {figi}</Typography>
        {report && <Typography variant="caption" color="text.secondary">估值日：{report.valuationDate}
          {valuation?.analysisAsOf ? " · 分析切點（香港）：" + formatHongKongDateTime(valuation.analysisAsOf, "zh-HK") : ""}
          {valuation?.calculatedAt ? " · 計算時間（香港）：" + formatHongKongDateTime(valuation.calculatedAt, "zh-HK") : ""}
        </Typography>}
      </Box>
      <Stack component="form" direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center" onSubmit={event => { event.preventDefault(); setValuationDate(dateInput); }}>
        <TextField label="估值日期（留空為最新）" type="date" size="small" value={dateInput} InputLabelProps={{ shrink: true }} onChange={event => setDateInput(event.target.value)} />
        <Button type="submit" variant="contained" disabled={reportQuery.isFetching}>查詢</Button>
        <Button
          startIcon={<AccountBalanceWalletOutlined />}
          variant={currentPortfolioItem ? "contained" : "outlined"}
          color={currentPortfolioItem?.itemType === "HOLDING" ? "success" : "primary"}
          onClick={() => setPortfolioDrawerOpen(true)}
          data-testid="stock-portfolio-btn"
        >
          {currentPortfolioItem
            ? (currentPortfolioItem.itemType === "HOLDING" ? t("portfolio.inHoldingBadge") : t("portfolio.inWatchlistBadge"))
            : t("portfolio.manageCurrent")}
        </Button>
        {currentPortfolioItem?.itemType === "HOLDING" && currentPortfolioItem.adjustedShares != null && (
          <Chip
            size="small"
            color="success"
            variant="outlined"
            label={t("portfolio.sizing.sharesUnit", { shares: currentPortfolioItem.adjustedShares })}
          />
        )}
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
      {!isEtf && fxConversion && (fxConversion.converted || fxConversion.adr) && (
        <Alert
          severity={
            (fxConversion.adr && !fxConversion.adrRatioVerified)
            || (!fxConversion.converted && fxConversion.reportingCurrency && fxConversion.targetCurrency
              && fxConversion.reportingCurrency !== fxConversion.targetCurrency)
              ? "warning"
              : "info"
          }
          sx={{ mb: 2 }}
        >
          {fxConversion.adr && !fxConversion.adrRatioVerified ? (
            t("analysisReport.fxBanner.adrUnverifiedNotice", { symbol: report.symbol })
          ) : fxConversion.converted ? (
            t("analysisReport.fxBanner.convertedNotice", {
              reportingCurrency: fxConversion.reportingCurrency ?? "—",
              targetCurrency: fxConversion.targetCurrency ?? "USD",
              rate: fxConversion.usdPerUnit != null ? fxConversion.usdPerUnit.toFixed(6) : "—",
              rateDate: fxConversion.rateDate ?? "—",
              source: fxConversion.fxSource ?? "—",
              adrSuffix: fxConversion.adr && fxConversion.adrRatio != null
                ? t("analysisReport.fxBanner.adrRatioSuffix", {
                  ratio: fxConversion.adrRatio,
                  underlying: fxConversion.underlyingSymbol ? ` (${fxConversion.underlyingSymbol})` : "",
                })
                : "",
            })
          ) : fxConversion.reportingCurrency && fxConversion.targetCurrency
            && fxConversion.reportingCurrency !== fxConversion.targetCurrency ? (
              t("analysisReport.fxBanner.fxUnavailableNotice", {
                reportingCurrency: fxConversion.reportingCurrency,
                targetCurrency: fxConversion.targetCurrency,
              })
            ) : (
              t("analysisReport.fxBanner.adrUsdNotice", {
                symbol: report.symbol,
                reportingCurrency: fxConversion.reportingCurrency ?? fxConversion.targetCurrency ?? "USD",
                ratio: fxConversion.adrRatio ?? "—",
                underlying: fxConversion.underlyingSymbol ? ` (${fxConversion.underlyingSymbol})` : "",
              })
            )}
        </Alert>
      )}
      {!isEtf && valuation?.status !== "AVAILABLE" && <Alert severity="info" sx={{ mb: 2 }}>
        估值狀態：{statusLabel(valuation?.status)} · {reasonLabel(t, valuation?.reasonCode)}。個別可用指標仍會顯示。
      </Alert>}
      {isEtf && <Alert severity="info" sx={{ mb: 2 }}>
        {t("analysisReport.etf.bannerNotice")}
      </Alert>}

      {/* L1. 置頂首屏：綜合估值位置、參考價帶標尺、觀察傾向與當期核心估值倍數 */}
      <ExecutiveSummaryPanel
        valuation={valuation}
        summaryResult={report.summary}
        breakoutDiagnosis={levels?.breakoutDiagnosis}
        starterPositionSizing={report.starterPositionSizing}
      />

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

      {/* L3. 估值與基本面證據層（ETF 模式不顯示單一公司財報與估值面板） */}
      {!isEtf && <>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, lg: 4 }}><ValuationReferencePanel title="歷史市場估值參考" description="比較這隻股票過去實際市場給予的估值倍數。" reference={valuation?.historicalReference} currency={valuation?.currency} /></Grid>
          <Grid size={{ xs: 12, lg: 4 }}><ValuationReferencePanel title="同業市場估值參考" description="比較可比公司的實際市場倍數；優先採用自訂對標或同市值量級對手，樣本不足時依序放寬。" reference={valuation?.peerReference} currency={valuation?.currency} figi={figi} symbol={report?.symbol} fundamentals={report.fundamentals} isHistoricalDate={Boolean(valuationDate)} peerRelativeStrength={report.peerRelativeStrength} /></Grid>
          <Grid size={{ xs: 12, lg: 4 }}><ValuationReferencePanel title="產業估值假設" description="以人工確認的產業模型及倍數建立假設情境，與實際市場參考分開。" reference={valuation?.industryAssumption} currency={valuation?.currency} /></Grid>
        </Grid>
        <FundamentalsPanel fundamentals={report.fundamentals} summary={summary} forward={report.futuObservations?.forward} />
        <DcfValuationPanel dcf={report.dcf} />
        <ValuationHistoryPanel key={figi + ":" + report.valuationDate} figi={figi} initial={report.history ?? []} />

        {/* L4. 稽核明細折疊區（財務原料、富途觀察預期） */}
        <FinancialsPanel financials={report.financials} />
        {report.futuObservations && <FutuObservationPanels observations={report.futuObservations} />}
      </>}

      <Accordion variant="outlined" disableGutters sx={{ mt: 2, borderRadius: 1, "&:before": { display: "none" } }}>
        <AccordionSummary expandIcon={<ExpandMore />}>
          <Typography fontWeight={700}>完整技術指標明細與原始解說（點擊展開）</Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ pt: 0 }}>
          <TechnicalIndicatorsPanel detail={market?.momentumDetail} dataThrough={structure?.priceDataThrough} momentum={market?.momentum} />
        </AccordionDetails>
      </Accordion>

      {!isEtf && <Accordion variant="outlined" disableGutters sx={{ mt: 2, borderRadius: 1, "&:before": { display: "none" } }}>
        <AccordionSummary expandIcon={<ExpandMore />}>
          <Typography fontWeight={700}>產業估值假設設定與 AI 建議（點擊展開）</Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ pt: 0 }}>
          <IndustryValuationProposalPanel key={figi + ":" + (valuation?.industryCode ?? "")} industryCode={valuation?.industryCode} figi={figi} valuationDate={report.valuationDate}
            reloadReport={async () => (await reportQuery.refetch()).data ?? null} />
        </AccordionDetails>
      </Accordion>}

      {!!report.reasons?.length && <Alert severity="info" sx={{ mt: 2 }}>{report.reasons.map(reason => readableReason(t, reason)).join("；")}</Alert>}
    </>}
    <PortfolioDrawer
      open={portfolioDrawerOpen}
      onClose={() => setPortfolioDrawerOpen(false)}
      initialStock={figi ? {
        figi,
        symbol: report?.symbol,
        stockName: report?.name,
        latestClosePrice: valuationDate ? null : (valuation?.price ?? null),
        latestQuoteDate: valuationDate ? null : (report?.valuationDate ?? null),
      } : null}
    />
  </Box>;
}
