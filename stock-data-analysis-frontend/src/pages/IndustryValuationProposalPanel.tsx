import React, { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  FormControl,
  Grid,
  InputLabel,
  Link,
  MenuItem,
  Paper,
  Select,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { industryValuationApi } from "../services/industryValuationApi";
import { IndustryValuationDraft, IndustryValuationReview } from "../services/types/dto/industryValuation";
import { StockAnalysisReport } from "../services/types/dto/analysis";
import { formatHongKongDateTime } from "../utils/hongKongTime";
import { formatNumber, metricLabel } from "./AnalysisPanels";

export interface ExternalAiProposalState {
  status: "LOADING" | "READY" | "ERROR";
  baseEffectiveAt?: string | null;
  draft?: IndustryValuationDraft | null;
  error?: string | null;
  errorKey?: string | null;
}

interface Props {
  industryCode?: string | null;
  figi?: string;
  valuationDate?: string | null;
  reloadReport?: () => Promise<StockAnalysisReport | null>;
  onSaved?: (saved: IndustryValuationReview) => void | Promise<void>;
  defaultEditOpen?: boolean;
  externalProposal?: ExternalAiProposalState | null;
  embedded?: boolean;
}

interface DraftForm {
  baseEffectiveAt: string | null;
  modelCode: string;
  primaryMetric: string;
  low: string;
  high: string;
  rationale: string;
  sourceUrl: string;
}

type LocalizedText = { key: string; defaultValue: string } | { raw: string };

const KNOWN_MODEL_CODES = new Set(["NON_FINANCIAL_OPERATING", "BANK", "INSURANCE", "REIT", "UNKNOWN"]);

const isHttpStatus = (error: unknown, status: number): boolean => {
  const value = error as { response?: { status?: number }; status?: number } | null | undefined;
  return value?.response?.status === status || value?.status === status;
};

const extractErrorText = (error: unknown): LocalizedText => {
  const value = error as { response?: { data?: { message?: string } }; message?: string } | null | undefined;
  const raw = value?.response?.data?.message || value?.message;
  if (raw) return { raw };
  return { key: "maintenance.industryValuation.error.operationFailed", defaultValue: "操作失敗。" };
};

const safeUrl = (value: string) => {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password;
  } catch {
    return false;
  }
};

const formFromReview = (review: IndustryValuationReview): DraftForm => ({
  baseEffectiveAt: review.current?.effectiveAt ?? null,
  modelCode: review.current?.modelCode ?? "",
  primaryMetric: review.current?.primaryMetric ?? "",
  low: review.current?.assumedMultipleLow?.toString() ?? "",
  high: review.current?.assumedMultipleHigh?.toString() ?? "",
  rationale: review.current?.rationale ?? "",
  sourceUrl: review.current?.sourceUrl ?? "",
});

const formFromDraft = (draft: IndustryValuationDraft, baseEffectiveAt: string | null): DraftForm => ({
  baseEffectiveAt,
  modelCode: draft.modelCode,
  primaryMetric: draft.primaryMetric,
  low: draft.assumedMultipleLow?.toString() ?? "",
  high: draft.assumedMultipleHigh?.toString() ?? "",
  rationale: draft.rationale ?? "",
  sourceUrl: draft.sources[0]?.url ?? "",
});

export function IndustryValuationProposalPanel({
  industryCode,
  figi,
  valuationDate,
  reloadReport,
  onSaved,
  defaultEditOpen = false,
  externalProposal = null,
  embedded = false,
}: Props) {
  const { t, i18n } = useTranslation();
  const client = useQueryClient();
  const queryKey = ["industry-valuation-review", industryCode];
  const reviewQuery = useQuery<IndustryValuationReview>({
    queryKey,
    enabled: Boolean(industryCode),
    queryFn: () => industryValuationApi.review(industryCode!),
  });
  const review = reviewQuery.data;
  const [form, setForm] = useState<DraftForm | null>(null);
  const [draft, setDraft] = useState<IndustryValuationDraft | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<LocalizedText | null>(null);
  const [conflict409, setConflict409] = useState(false);
  const [message, setMessage] = useState<LocalizedText | null>(null);
  const mountedRef = useRef(true);
  const proposalSeqRef = useRef(0);
  const autoEditAppliedRef = useRef(false);
  const appliedExternalRef = useRef<ExternalAiProposalState | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      proposalSeqRef.current += 1;
    };
  }, []);

  useEffect(() => {
    if (defaultEditOpen && review && !form && !externalProposal && !autoEditAppliedRef.current) {
      autoEditAppliedRef.current = true;
      setForm(formFromReview(review));
    }
  }, [defaultEditOpen, review, form, externalProposal]);

  useEffect(() => {
    if (!externalProposal || appliedExternalRef.current === externalProposal) return;
    if (externalProposal.status === "READY" && externalProposal.draft) {
      appliedExternalRef.current = externalProposal;
      setDraft(externalProposal.draft);
      setForm(formFromDraft(externalProposal.draft, externalProposal.baseEffectiveAt ?? null));
      setConflict409(false);
      setError(null);
      setMessage(null);
    } else if (externalProposal.status === "ERROR") {
      appliedExternalRef.current = externalProposal;
      if (externalProposal.errorKey) {
        setError({ key: externalProposal.errorKey, defaultValue: "操作失敗。" });
      } else if (externalProposal.error) {
        setError({ raw: externalProposal.error });
      } else {
        setError({ key: "maintenance.industryValuation.error.operationFailed", defaultValue: "操作失敗。" });
      }
    }
  }, [externalProposal]);

  const effectiveBusy = busy || (externalProposal?.status === "LOADING" ? "PROPOSE" : null);
  const locale = i18n?.language || "zh-HK";
  const renderText = (text: LocalizedText | null): string | null => {
    if (!text) return null;
    return "raw" in text ? text.raw : t(text.key, { defaultValue: text.defaultValue });
  };
  const modelLabel = (code?: string | null) => {
    if (!code) return t("maintenance.industryValuation.table.unset", { defaultValue: "未設定" });
    return KNOWN_MODEL_CODES.has(code) ? t("industryProposal.codes." + code, { defaultValue: code }) : code;
  };

  const propose = async () => {
    if (!industryCode || !review?.canPropose || effectiveBusy || conflict409) return;
    const reqId = ++proposalSeqRef.current;
    const baseEffectiveAt = review.current?.effectiveAt ?? null;
    setBusy("PROPOSE");
    setError(null);
    setMessage(null);
    try {
      const result = await industryValuationApi.propose(industryCode);
      if (!mountedRef.current || proposalSeqRef.current !== reqId) return;
      setDraft(result);
      setForm(formFromDraft(result, baseEffectiveAt));
    } catch (failure) {
      if (!mountedRef.current || proposalSeqRef.current !== reqId) return;
      setError(extractErrorText(failure));
    } finally {
      if (mountedRef.current && proposalSeqRef.current === reqId) {
        setBusy(null);
      }
    }
  };

  const edit = () => {
    if (!review || effectiveBusy || conflict409) return;
    setDraft(null);
    setError(null);
    setMessage(null);
    setForm(formFromReview(review));
  };

  const reloadLatestAfterConflict = async () => {
    if (effectiveBusy) return;
    setBusy("RELOAD");
    setError(null);
    setMessage(null);
    try {
      const refreshed = await reviewQuery.refetch();
      if (!mountedRef.current) return;
      if (refreshed?.isError || !refreshed?.data) {
        setError({
          key: "maintenance.industryValuation.error.reloadFailed",
          defaultValue: "重新載入最新設定失敗，已保留目前草稿供您對照。",
        });
        return;
      }
      setConflict409(false);
      setError(null);
      setDraft(null);
      setForm(formFromReview(refreshed.data));
    } catch {
      if (!mountedRef.current) return;
      setError({
        key: "maintenance.industryValuation.error.reloadFailed",
        defaultValue: "重新載入最新設定失敗，已保留目前草稿供您對照。",
      });
    } finally {
      if (mountedRef.current) {
        setBusy(null);
      }
    }
  };

  const save = async () => {
    if (!industryCode || !review || !form || effectiveBusy || conflict409) return;
    const low = form.low.trim() ? Number(form.low) : null;
    const high = form.high.trim() ? Number(form.high) : null;
    if (!review.supportedMetrics[form.modelCode]?.includes(form.primaryMetric)) {
      setError({
        key: "maintenance.industryValuation.panel.validationUnsupported",
        defaultValue: "請選擇後端支援的模型及指標。",
      });
      return;
    }
    if (
      (low === null) !== (high === null) ||
      (low !== null && high !== null && (!Number.isFinite(low) || !Number.isFinite(high) || low <= 0 || high < low))
    ) {
      setError({
        key: "maintenance.industryValuation.panel.validationRange",
        defaultValue: "倍數上下限須同時留空，或同時填寫有效正數區間。",
      });
      return;
    }
    if (low !== null && (!form.rationale.trim() || !form.sourceUrl.trim())) {
      setError({
        key: "maintenance.industryValuation.panel.validationRationaleSource",
        defaultValue: "提供倍數假設時，必須填寫假設理由及主要來源網址。",
      });
      return;
    }
    if (form.sourceUrl.trim() && !safeUrl(form.sourceUrl.trim())) {
      setError({
        key: "maintenance.industryValuation.panel.validationSourceUrl",
        defaultValue: "來源必須是有效的 HTTP 或 HTTPS 網址。",
      });
      return;
    }
    setBusy("SAVE");
    setError(null);
    setMessage(null);
    const submittedBaseEffectiveAt = form.baseEffectiveAt;
    try {
      const saved = await industryValuationApi.save(industryCode, {
        baseEffectiveAt: submittedBaseEffectiveAt,
        modelCode: form.modelCode,
        primaryMetric: form.primaryMetric,
        assumedMultipleLow: low,
        assumedMultipleHigh: high,
        rationale: form.rationale.trim() || null,
        sourceUrl: form.sourceUrl.trim() || null,
      });
      // 先取消同產業在途 detail 讀取，僅在快取仍為本次基準版（或空）時寫入，避免倒序 PUT 舊回應把較新版本蓋回舊版。
      await client.cancelQueries?.({ queryKey, exact: true });
      const existingCached = client.getQueryData?.<IndustryValuationReview>(queryKey);
      const existingEffectiveAt = existingCached?.current?.effectiveAt ?? null;
      const savedEffectiveAt = saved.current?.effectiveAt ?? null;
      if (!existingCached || existingEffectiveAt === submittedBaseEffectiveAt || existingEffectiveAt === savedEffectiveAt) {
        client.setQueryData(queryKey, saved);
      } else {
        await client.invalidateQueries({ queryKey, exact: true });
      }
      // 取消尚未完成的舊版總覽 GET（特別是首次冷載入在途請求），確保總覽 KPI 必定反映 PUT 後最新版本。
      await client.cancelQueries?.({ queryKey: ["industry-valuation-overview"], exact: true });
      if (mountedRef.current && onSaved) {
        setForm(null);
        setDraft(null);
        setConflict409(false);
        setMessage({
          key: "maintenance.industryValuation.panel.savedNotice",
          defaultValue: "正式產業設定已保存；既有估值結果尚未重算。此設定適用整個產業，不只目前股票。",
        });
        await client.invalidateQueries({ queryKey: ["industry-valuation-overview"], refetchType: "none" });
        await onSaved(saved);
      } else {
        await client.invalidateQueries({ queryKey: ["industry-valuation-overview"] });
        if (mountedRef.current) {
          setForm(null);
          setDraft(null);
          setConflict409(false);
          setMessage({
            key: "maintenance.industryValuation.panel.savedNotice",
            defaultValue: "正式產業設定已保存；既有估值結果尚未重算。此設定適用整個產業，不只目前股票。",
          });
        }
      }
    } catch (failure) {
      if (!mountedRef.current) return;
      if (isHttpStatus(failure, 409)) {
        setConflict409(true);
        setError(null);
      } else {
        setError(extractErrorText(failure));
      }
    } finally {
      if (mountedRef.current) {
        setBusy(null);
      }
    }
  };

  const recalculate = async () => {
    if (!figi || !valuationDate || !reloadReport || effectiveBusy) return;
    setBusy("RECALCULATE");
    setError(null);
    setMessage(null);
    try {
      await industryValuationApi.recalculate(figi, valuationDate);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["valuation-history", figi] }),
        client.invalidateQueries({ queryKey: ["stock-screen"] }),
      ]);
      await reloadReport();
      if (!mountedRef.current) return;
      setMessage({
        key: "maintenance.industryValuation.panel.recalculatedNotice",
        defaultValue: "此股指定估值日已完成重算並重新載入；以畫面回傳的可用指標及狀態為準。",
      });
    } catch (failure) {
      if (!mountedRef.current) return;
      setError(extractErrorText(failure));
    } finally {
      if (mountedRef.current) {
        setBusy(null);
      }
    }
  };

  const displayedError = renderText(error) || (reviewQuery.isError ? renderText(extractErrorText(reviewQuery.error)) : null);
  const displayedMessage = renderText(message);

  const content = (
    <>
      {!embedded && (
        <Typography variant="h6" fontWeight={700}>
          {t("maintenance.industryValuation.panel.title", { defaultValue: "產業假設設定" })}
        </Typography>
      )}
      <Typography variant="body2" color="text.secondary" sx={{ my: 1 }}>
        {t("maintenance.industryValuation.panel.description", {
          defaultValue: "AI 只產生待確認草稿，不自動保存。模型與指標選項由後端提供。",
        })}
      </Typography>
      {!industryCode && (
        <Typography>
          {t("maintenance.industryValuation.panel.industryMissing", { defaultValue: "報告尚未提供產業代碼，暫不能設定產業假設。" })}
        </Typography>
      )}
      {reviewQuery.isLoading && industryCode && <CircularProgress size={22} />}
      {conflict409 && (
        <Alert
          severity="warning"
          sx={{ my: 1 }}
          action={
            <Button color="inherit" size="small" onClick={() => void reloadLatestAfterConflict()} disabled={!!effectiveBusy}>
              {t("maintenance.industryValuation.actions.reloadLatest", { defaultValue: "捨棄草稿並載入最新設定" })}
            </Button>
          }
        >
          {t("maintenance.industryValuation.error.conflict409", {
            defaultValue: "該產業設定已被其他使用者更新，請重新載入最新資料後再試。",
          })}
        </Alert>
      )}
      {displayedError && (
        <Alert severity="error" sx={{ my: 1 }}>
          {displayedError}
        </Alert>
      )}
      {displayedMessage && (
        <Alert severity="info" sx={{ my: 1 }}>
          {displayedMessage}
        </Alert>
      )}
      {figi && valuationDate && reloadReport && (
        <Button onClick={() => void recalculate()} disabled={!!effectiveBusy} sx={{ my: 1 }}>
          {t("maintenance.industryValuation.panel.recalculateStock", { defaultValue: "重算此股估值" })}
        </Button>
      )}
      {effectiveBusy && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ my: 1 }}>
          <CircularProgress size={18} />
          <Typography>
            {effectiveBusy === "PROPOSE"
              ? t("maintenance.industryValuation.panel.generatingDraft", { defaultValue: "正在產生草稿，可能需要幾分鐘…" })
              : t("maintenance.industryValuation.panel.processing", { defaultValue: "處理中…" })}
          </Typography>
        </Stack>
      )}
      {review && (
        <>
          <Typography fontWeight={600}>
            {review.industry} · {review.industryCode}
          </Typography>
          {review.current ? (
            <Stack spacing={0.5} sx={{ my: 1 }}>
              <Typography>
                {t("maintenance.industryValuation.panel.currentConfig", {
                  model: modelLabel(review.current.modelCode),
                  metric: metricLabel(review.current.primaryMetric),
                  low: formatNumber(review.current.assumedMultipleLow),
                  high: formatNumber(review.current.assumedMultipleHigh),
                  defaultValue: `正式設定：${modelLabel(review.current.modelCode)} · ${metricLabel(review.current.primaryMetric)} · ${formatNumber(review.current.assumedMultipleLow)}–${formatNumber(review.current.assumedMultipleHigh)} 倍`,
                })}
              </Typography>
              <Typography variant="body2">
                {t("maintenance.industryValuation.panel.effectiveAtHk", {
                  time: formatHongKongDateTime(review.current.effectiveAt, locale),
                  defaultValue: `生效時間（香港）：${formatHongKongDateTime(review.current.effectiveAt, locale)}`,
                })}
              </Typography>
              {review.current.rationale && (
                <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
                  {review.current.rationale}
                </Typography>
              )}
              {review.current.sourceUrl && safeUrl(review.current.sourceUrl) && (
                <Link href={review.current.sourceUrl} target="_blank" rel="noopener noreferrer">
                  {t("maintenance.industryValuation.panel.officialSource", { defaultValue: "正式設定來源" })}
                </Link>
              )}
            </Stack>
          ) : (
            <Typography color="text.secondary" sx={{ my: 1 }}>
              {t("maintenance.industryValuation.panel.noOfficialConfig", { defaultValue: "尚無正式設定。" })}
            </Typography>
          )}
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ my: 1 }}>
            {review.canPropose && (
              <Button onClick={() => void propose()} variant="outlined" disabled={!!effectiveBusy || conflict409}>
                {t("maintenance.industryValuation.panel.generateAiDraft", { defaultValue: "產生 AI 草稿" })}
              </Button>
            )}
            <Button onClick={edit} disabled={!!effectiveBusy || conflict409}>
              {t("maintenance.industryValuation.panel.manualEdit", { defaultValue: "手動設定" })}
            </Button>
            <Button onClick={() => void reviewQuery.refetch()} disabled={!!effectiveBusy}>
              {t("maintenance.industryValuation.panel.reloadConfig", { defaultValue: "重新讀取設定" })}
            </Button>
          </Stack>
          {form && (
            <Box sx={{ mt: 2 }}>
              <Alert severity="warning" sx={{ mb: 2 }}>
                {t("maintenance.industryValuation.panel.draftWarning", {
                  defaultValue: "確認保存後才會修改正式設定，影響整個產業。草稿只保留在本頁，離開頁面不會保存。",
                })}
              </Alert>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                {t("maintenance.industryValuation.panel.partialSaveHint", {
                  defaultValue: "可以先只保存模型及主要指標，倍數上下限同時留空；提供倍數假設時，理由及來源網址必須填寫。",
                })}
              </Typography>
              {draft && (
                <Typography variant="body2" sx={{ mb: 1 }}>
                  {t("maintenance.industryValuation.panel.draftAsOf", {
                    date: draft.asOf,
                    defaultValue: `草稿參考日：${draft.asOf}`,
                  })}
                </Typography>
              )}
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <FormControl fullWidth size="small">
                    <InputLabel id="industry-model-label">
                      {t("maintenance.industryValuation.panel.modelLabel", { defaultValue: "模型" })}
                    </InputLabel>
                    <Select
                      labelId="industry-model-label"
                      label={t("maintenance.industryValuation.panel.modelLabel", { defaultValue: "模型" })}
                      value={form.modelCode}
                      onChange={(event) => setForm({ ...form, modelCode: event.target.value, primaryMetric: "" })}
                    >
                      {Object.keys(review.supportedMetrics).map((code) => (
                        <MenuItem key={code} value={code}>
                          {modelLabel(code)}
                        </MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </Grid>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <FormControl fullWidth size="small">
                    <InputLabel id="industry-metric-label">
                      {t("maintenance.industryValuation.panel.primaryMetricLabel", { defaultValue: "主要指標" })}
                    </InputLabel>
                    <Select
                      labelId="industry-metric-label"
                      label={t("maintenance.industryValuation.panel.primaryMetricLabel", { defaultValue: "主要指標" })}
                      value={form.primaryMetric}
                      onChange={(event) => setForm({ ...form, primaryMetric: event.target.value })}
                    >
                      {(review.supportedMetrics[form.modelCode] ?? []).map((code) => (
                        <MenuItem key={code} value={code}>
                          {metricLabel(code)}
                        </MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </Grid>
                <Grid size={{ xs: 6 }}>
                  <TextField
                    fullWidth
                    size="small"
                    label={t("maintenance.industryValuation.panel.lowLabel", { defaultValue: "假設倍數下限" })}
                    type="number"
                    inputProps={{ step: "any" }}
                    value={form.low}
                    onChange={(event) => setForm({ ...form, low: event.target.value })}
                  />
                </Grid>
                <Grid size={{ xs: 6 }}>
                  <TextField
                    fullWidth
                    size="small"
                    label={t("maintenance.industryValuation.panel.highLabel", { defaultValue: "假設倍數上限" })}
                    type="number"
                    inputProps={{ step: "any" }}
                    value={form.high}
                    onChange={(event) => setForm({ ...form, high: event.target.value })}
                  />
                </Grid>
                <Grid size={{ xs: 12 }}>
                  <TextField
                    fullWidth
                    multiline
                    minRows={2}
                    label={t("maintenance.industryValuation.panel.rationaleLabel", { defaultValue: "假設理由" })}
                    value={form.rationale}
                    onChange={(event) => setForm({ ...form, rationale: event.target.value })}
                  />
                </Grid>
                <Grid size={{ xs: 12 }}>
                  <TextField
                    fullWidth
                    size="small"
                    label={t("maintenance.industryValuation.panel.sourceUrlLabel", { defaultValue: "正式設定來源網址" })}
                    value={form.sourceUrl}
                    onChange={(event) => setForm({ ...form, sourceUrl: event.target.value })}
                  />
                </Grid>
              </Grid>
              {draft?.sources?.map((source, index) => (
                <Box key={index} sx={{ my: 1 }}>
                  <Typography variant="subtitle2">
                    {source.title} {source.publishedDate ? " · " + source.publishedDate : ""}
                  </Typography>
                  {safeUrl(source.url) ? (
                    <Link href={source.url} target="_blank" rel="noopener noreferrer">
                      {source.url}
                    </Link>
                  ) : (
                    <Typography>{source.url}</Typography>
                  )}
                  <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
                    {source.evidence}
                  </Typography>
                </Box>
              ))}
              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mt: 2 }}>
                <Button variant="contained" onClick={() => void save()} disabled={!!effectiveBusy || conflict409}>
                  {t("maintenance.industryValuation.panel.confirmSave", { defaultValue: "確認保存正式設定" })}
                </Button>
                <Button
                  onClick={() => {
                    setForm(null);
                    setDraft(null);
                    setConflict409(false);
                    setError(null);
                  }}
                  disabled={!!effectiveBusy}
                >
                  {t("maintenance.industryValuation.panel.discardDraft", { defaultValue: "捨棄草稿" })}
                </Button>
              </Stack>
            </Box>
          )}
        </>
      )}
    </>
  );

  if (embedded) {
    return (
      <Box component="section" aria-label={t("maintenance.industryValuation.panel.title", { defaultValue: "產業假設設定" })}>
        {content}
      </Box>
    );
  }

  return (
    <Paper
      component="section"
      aria-label={t("maintenance.industryValuation.panel.title", { defaultValue: "產業假設設定" })}
      variant="outlined"
      sx={{ p: 2.5, mt: 2 }}
    >
      {content}
    </Paper>
  );
}
