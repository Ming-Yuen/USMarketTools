import React, { useState, useMemo } from "react";
import {
  Alert, Box, Button, Chip, IconButton, LinearProgress, Paper, Stack, Tooltip, Typography,
} from "@mui/material";
import { Add, Download, PlayArrow, Refresh } from "@mui/icons-material";
import { useQuery } from "@tanstack/react-query";
import { useFetch, useMutate } from "../hooks/api/useApi";
import { ApiResponse } from "../services/types/dto/apiResponse";
import { Job, EnquiryJobResponse, SignalReplayFile, SignalReplayStatus } from "../services/types/dto/batch";
import { apiConfig } from "../apiConfig";
import { apiClient } from "../services/apiClient";
import { ActiveStatus } from "../services/types/enums/ActiveStatus";
import { JobCreatePage } from "./JobCreate";
import { Column } from "../components/DynamicFormTable/DynamicFormTable.types";
import DynamicFormTable from "../components/DynamicFormTable/DynamicFormTable";
import { MenuTree } from "../services/types/dto/menu";
import { useTranslation } from "react-i18next";
import { formatQuartzCronExpression, getFixedQuartzTimeSeconds } from "../utils/cronExpression";

const SIGNAL_REPLAY_TASK_CODE = "SIGNAL_REPLAY_AUDIT";

interface JobManagementPageProps {
  menuTree: MenuTree;
}

function formatFileSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return "0 B";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function JobManagementPage({ menuTree }: JobManagementPageProps) {
  const { t } = useTranslation();
  const [showCreatePage, setShowCreatePage] = useState(false);
  const [selectedJob, setSelectedJob] = useState<Job | null>(null);
  const [downloadingType, setDownloadingType] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const { data, isLoading, isError, error, refetch } = useFetch<EnquiryJobResponse>(apiConfig.getJobList);
  const {
    data: replayStatus,
    refetch: refetchReplayStatus,
  } = useQuery<SignalReplayStatus>({
    queryKey: [apiConfig.getSignalReplayStatus.url],
    queryFn: async () => {
      const response = await apiClient.get<SignalReplayStatus>(apiConfig.getSignalReplayStatus.url);
      return response.data;
    },
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? 2500 : false),
  });
  const launchBatchJob = useMutate<ApiResponse>(apiConfig.launchJobList, undefined, {
    onSuccess: (_resp, variables) => {
      if (variables?.taskCode === SIGNAL_REPLAY_TASK_CODE) {
        setTimeout(() => { void refetchReplayStatus(); }, 400);
      }
    },
  });
  const jobs = useMemo(() => (data?.jobTaskList ?? [])
    .map((job) => ({
      job,
      time: job.taskGroup === "SCHEDULE" && job.activeStatus === ActiveStatus.ACTIVE
        ? getFixedQuartzTimeSeconds(job.cronExpression) ?? Number.MAX_SAFE_INTEGER
        : Number.MAX_SAFE_INTEGER,
    }))
    .sort((a, b) => a.time - b.time || a.job.taskCode.localeCompare(b.job.taskCode))
    .map(({ job }) => job), [data?.jobTaskList]);

  const replayJob = useMemo(
    () => jobs.find((job) => job.taskCode === SIGNAL_REPLAY_TASK_CODE) ?? null,
    [jobs],
  );

  const handleDownloadCsv = async (file: SignalReplayFile) => {
    setDownloadError(null);
    setDownloadingType(file.type);
    try {
      const response = await apiClient.get<Blob>("job/signal-replay/download", {
        params: { type: file.type },
        responseType: "blob",
      });
      const blob = new Blob([response.data], { type: "text/csv;charset=utf-8;" });
      const url = window.URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = file.fileName || `signal_replay_${file.type}.csv`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.URL.revokeObjectURL(url);
    } catch {
      setDownloadError(t("job.signalReplay.downloadFailed"));
    } finally {
      setDownloadingType(null);
    }
  };

  const handleLaunchReplay = () => {
    if (!replayJob) return;
    setDownloadError(null);
    launchBatchJob.mutate({
      taskCode: replayJob.taskCode,
      jobParams: replayJob.jobParams ?? {},
      taskGroup: replayJob.taskGroup,
    });
  };

  const columns: Column[] = useMemo(() => [
    { id: "taskCode", label: t("job.columns.taskCode"), width: 260 },
    {
      id: "description", label: t("job.columns.description"), width: 380,
      render: (value, row: Job) => t(`job.description.${row.taskCode}`, { defaultValue: "-" }),
    },
    { id: "taskGroup", label: t("job.columns.taskGroup"), width: 200 },
    {
      id: "activeStatus", label: t("job.columns.active"), type: "select", width: 120,
      render: (value) => value === ActiveStatus.ACTIVE
        ? <span style={{ color: "green" }}>{t("job.status.active")}</span>
        : value === ActiveStatus.INACTIVE
          ? <span style={{ color: "red" }}>{t("job.status.inactive")}</span>
          : <span style={{ color: "gray" }}>{value || "-"}</span>,
    },
    {
      id: "cronExpression", label: t("job.columns.scheduleTime"), width: 220,
      render: (value) => formatQuartzCronExpression(value, t),
    },
    {
      id: "actions", label: t("job.columns.run"), width: 120, isActionColumn: true,
      render: (value, row, index, { launchBatchJob, replayRunning }) => (
        <Tooltip title={t("job.actions.run")}>
          <span>
            <IconButton color="primary" size="small" onClick={() => launchBatchJob.mutate({
              taskCode: row.taskCode, jobParams: row.jobParams, taskGroup: row.taskGroup,
            })} disabled={launchBatchJob.isPending || (row.taskCode === SIGNAL_REPLAY_TASK_CODE && replayRunning)}>
              <PlayArrow />
            </IconButton>
          </span>
        </Tooltip>
      ),
    },
  ], [t]);

  const handleCreateClick = () => {
    setSelectedJob(null);
    setShowCreatePage(true);
  };

  const handleCreateClose = () => {
    setShowCreatePage(false);
    setSelectedJob(null);
    void refetch();
  };

  if (showCreatePage) {
    return <JobCreatePage menuTree={menuTree} job={selectedJob ?? undefined} onClose={handleCreateClose} />;
  }

  const replayStatusValue = replayStatus?.status ?? "IDLE";
  const replayRunning = replayStatusValue === "RUNNING";
  const replayChipColor: "default" | "info" | "success" | "error" =
    replayStatusValue === "RUNNING" ? "info"
      : replayStatusValue === "COMPLETED" ? "success"
        : replayStatusValue === "FAILED" ? "error" : "default";

  return (
    <Box
      sx={{
        width: "100%",
        display: "flex",
        flexDirection: "column",
        minWidth: 0,
        minHeight: 0,
        gap: 1.5,
      }}
    >
      {replayJob && (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Stack spacing={1.25}>
            <Stack
              direction={{ xs: "column", md: "row" }}
              spacing={1.5}
              alignItems={{ xs: "flex-start", md: "center" }}
              justifyContent="space-between"
            >
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                  {t("job.signalReplay.title")}
                </Typography>
                <Chip
                  size="small"
                  color={replayChipColor}
                  label={t(`job.signalReplay.status.${replayStatusValue}`, { defaultValue: replayStatusValue })}
                />
                {replayStatus?.startDate && replayStatus?.endDate && (
                  <Typography variant="caption" color="text.secondary">
                    {replayStatus.startDate} ~ {replayStatus.endDate}
                  </Typography>
                )}
              </Stack>

              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Button
                  variant="contained"
                  size="small"
                  startIcon={<PlayArrow />}
                  onClick={handleLaunchReplay}
                  disabled={launchBatchJob.isPending || replayRunning}
                >
                  {replayRunning ? t("job.signalReplay.runningAction") : t("job.signalReplay.runAction")}
                </Button>
                <Tooltip title={t("job.signalReplay.refresh")}>
                  <span>
                    <IconButton size="small" onClick={() => { void refetchReplayStatus(); }}>
                      <Refresh fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
                {(replayStatus?.files ?? []).map((file) => (
                  <Button
                    key={file.type}
                    variant="outlined"
                    size="small"
                    startIcon={<Download />}
                    disabled={downloadingType === file.type}
                    onClick={() => { void handleDownloadCsv(file); }}
                  >
                    {t(`job.signalReplay.download.${file.type}`, { defaultValue: file.fileName })}
                    {` (${formatFileSize(file.sizeBytes)})`}
                  </Button>
                ))}
              </Stack>
            </Stack>

            {replayRunning && (
              <Box>
                <LinearProgress
                  variant={replayStatus?.totalDays ? "determinate" : "indeterminate"}
                  value={replayStatus?.progressPct ?? 0}
                />
                <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: "block" }}>
                  {t("job.signalReplay.progressDetail", {
                    pct: replayStatus?.progressPct ?? 0,
                    processedDays: replayStatus?.processedDays ?? 0,
                    totalDays: replayStatus?.totalDays ?? 0,
                    currentDate: replayStatus?.currentDate ?? "-",
                    events: replayStatus?.emittedEvents ?? 0,
                  })}
                </Typography>
              </Box>
            )}

            {replayStatusValue === "COMPLETED" && (
              <Typography variant="caption" color="text.secondary">
                {t("job.signalReplay.completedDetail", {
                  symbols: replayStatus?.totalSymbols ?? 0,
                  days: replayStatus?.totalDays ?? 0,
                  events: replayStatus?.emittedEvents ?? 0,
                  runId: replayStatus?.runId ?? "-",
                })}
              </Typography>
            )}

            {replayStatusValue === "FAILED" && replayStatus?.errorMessage && (
              <Alert severity="error">{replayStatus.errorMessage}</Alert>
            )}
            {downloadError && <Alert severity="error">{downloadError}</Alert>}
          </Stack>
        </Paper>
      )}

      <DynamicFormTable
        pageKey={menuTree.name}
        columns={columns}
        data={jobs}
        loading={isLoading}
        error={isError ? error : null}
        enableInfiniteScroll={false}
        extraRenderProps={{ launchBatchJob, replayRunning }}
        onRowDoubleClick={(row) => {
          setSelectedJob(row as Job);
          setShowCreatePage(true);
        }}
        toolbarActions={
          // 這裡可以放多個按鈕，用 Stack/Box 包起來
          <Stack direction="row" spacing={1}>
            <Button variant="contained" color="primary" size="small" startIcon={<Add />} onClick={handleCreateClick}>
              {t("job.actions.create")}
            </Button>
            {/* 例子：第二個按鈕，以後要加其他動作可以直接塞這裡 */}
            {/* <Button size="small" variant="outlined">Export</Button> */}
          </Stack>
        }
      />
    </Box>
  );
}
