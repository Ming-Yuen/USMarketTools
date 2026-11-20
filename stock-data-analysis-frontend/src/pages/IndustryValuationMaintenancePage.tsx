import React, { useEffect, useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Box,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  Drawer,
  FormControl,
  Grid,
  IconButton,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  TextField,
  Typography,
} from "@mui/material";
import { AutoAwesome, Close, EditOutlined, Refresh } from "@mui/icons-material";
import { industryValuationApi } from "../services/industryValuationApi";
import { IndustryValuationOverview, IndustryValuationOverviewItem } from "../services/types/dto/industryValuation";
import { ExternalAiProposalState, IndustryValuationProposalPanel } from "./IndustryValuationProposalPanel";
import { formatNumber, metricLabel } from "./AnalysisPanels";

type StatusFilter = "ALL" | "UNCONFIGURED" | "CONFIGURED";
type SortField = "default" | "industryName" | "modelCode" | "primaryMetric" | "multipleLow" | "activeStockCount" | "configured";
type SortDirection = "asc" | "desc";

interface DrawerState {
  code: string;
  industryName: string;
  mode: "EDIT" | "PROPOSE";
  sessionId: number;
}

const errorMessage = (error: unknown, fallback: string): string => {
  const value = error as { response?: { data?: { message?: string } }; message?: string } | null | undefined;
  return value?.response?.data?.message || value?.message || fallback;
};

const compareNullableString = (a: string | null | undefined, b: string | null | undefined, dir: SortDirection): number => {
  const aMissing = !a;
  const bMissing = !b;
  if (aMissing && bMissing) return 0;
  if (aMissing) return 1;
  if (bMissing) return -1;
  const cmp = a.localeCompare(b);
  return dir === "asc" ? cmp : -cmp;
};

const compareNullableNumber = (a: number | null | undefined, b: number | null | undefined, dir: SortDirection): number => {
  const aMissing = a == null || !Number.isFinite(a);
  const bMissing = b == null || !Number.isFinite(b);
  if (aMissing && bMissing) return 0;
  if (aMissing) return 1;
  if (bMissing) return -1;
  const cmp = a - b;
  return dir === "asc" ? cmp : -cmp;
};

export function IndustryValuationMaintenancePage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const overviewQuery = useQuery<IndustryValuationOverview>({
    queryKey: ["industry-valuation-overview"],
    queryFn: industryValuationApi.list,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  const [keyword, setKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [modelFilter, setModelFilter] = useState<string>("ALL");
  const [sortField, setSortField] = useState<SortField>("default");
  const [sortDirection, setSortDirection] = useState<SortDirection>("asc");
  const [page, setPage] = useState(0);
  const [rowsPerPage, setRowsPerPage] = useState(25);
  const [drawer, setDrawer] = useState<DrawerState | null>(null);
  const [externalProposal, setExternalProposal] = useState<ExternalAiProposalState | null>(null);
  const [postSaveNotice, setPostSaveNotice] = useState<{
    severity: "success" | "warning";
    key: "maintenance.industryValuation.notice.savedAndRefreshed" | "maintenance.industryValuation.notice.savedRefreshFailed";
  } | null>(null);
  const mountedRef = useRef(true);
  const sessionSeqRef = useRef(0);
  const proposalSeqRef = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      proposalSeqRef.current += 1;
    };
  }, []);

  const items = useMemo(() => overviewQuery.data?.items ?? [], [overviewQuery.data]);
  const supportedModels = useMemo(() => Object.keys(overviewQuery.data?.supportedMetrics ?? {}), [overviewQuery.data]);

  const kpi = useMemo(() => {
    const total = items.length;
    const configured = items.filter((item) => item.configured).length;
    const unconfigured = total - configured;
    const pct = total === 0 ? "0%" : `${((configured / total) * 100).toFixed(1)}%`;
    return { total, configured, unconfigured, pct };
  }, [items]);

  const filteredAndSorted = useMemo(() => {
    const trimmed = keyword.trim().toLowerCase();
    const filtered = items.filter((item) => {
      if (trimmed) {
        const matchCode = item.industryCode.toLowerCase().includes(trimmed);
        const matchName = item.industryName.toLowerCase().includes(trimmed);
        if (!matchCode && !matchName) return false;
      }
      if (statusFilter === "CONFIGURED" && !item.configured) return false;
      if (statusFilter === "UNCONFIGURED" && item.configured) return false;
      if (modelFilter === "NONE" && item.modelCode != null) return false;
      if (modelFilter !== "ALL" && modelFilter !== "NONE" && item.modelCode !== modelFilter) return false;
      return true;
    });

    const sorted = [...filtered];
    sorted.sort((a, b) => {
      let cmp = 0;
      if (sortField === "default") {
        cmp = Number(a.configured) - Number(b.configured);
        if (cmp === 0) cmp = b.activeStockCount - a.activeStockCount;
      } else if (sortField === "industryName") {
        cmp = compareNullableString(a.industryName, b.industryName, sortDirection);
      } else if (sortField === "modelCode") {
        cmp = compareNullableString(a.modelCode, b.modelCode, sortDirection);
      } else if (sortField === "primaryMetric") {
        cmp = compareNullableString(a.primaryMetric, b.primaryMetric, sortDirection);
      } else if (sortField === "multipleLow") {
        cmp = compareNullableNumber(a.assumedMultipleLow, b.assumedMultipleLow, sortDirection);
      } else if (sortField === "activeStockCount") {
        cmp = compareNullableNumber(a.activeStockCount, b.activeStockCount, sortDirection);
      } else if (sortField === "configured") {
        const diff = Number(a.configured) - Number(b.configured);
        cmp = sortDirection === "asc" ? diff : -diff;
      }
      return cmp !== 0 ? cmp : a.industryCode.localeCompare(b.industryCode);
    });
    return sorted;
  }, [items, keyword, statusFilter, modelFilter, sortField, sortDirection]);

  const maxPage = Math.max(0, Math.ceil(filteredAndSorted.length / rowsPerPage) - 1);
  const safePage = Math.min(page, maxPage);

  useEffect(() => {
    if (page > maxPage) {
      setPage(maxPage);
    }
  }, [maxPage, page]);

  const pagedRows = useMemo(
    () => filteredAndSorted.slice(safePage * rowsPerPage, safePage * rowsPerPage + rowsPerPage),
    [filteredAndSorted, safePage, rowsPerPage],
  );

  const handleSort = (field: Exclude<SortField, "default">) => {
    if (sortField === field) {
      setSortDirection((prev) => (prev === "asc" ? "desc" : "asc"));
    } else {
      setSortField(field);
      setSortDirection(field === "activeStockCount" ? "desc" : "asc");
    }
  };

  const handleOpenEdit = (row: IndustryValuationOverviewItem) => {
    proposalSeqRef.current += 1;
    setExternalProposal(null);
    setDrawer({
      code: row.industryCode,
      industryName: row.industryName,
      mode: "EDIT",
      sessionId: ++sessionSeqRef.current,
    });
  };

  const handleTriggerAi = async (row: IndustryValuationOverviewItem) => {
    if (!row.canPropose) return;
    const reqId = ++proposalSeqRef.current;
    const sessionId = ++sessionSeqRef.current;
    setExternalProposal({ status: "LOADING" });
    setDrawer({
      code: row.industryCode,
      industryName: row.industryName,
      mode: "PROPOSE",
      sessionId,
    });
    try {
      const detail = await queryClient.fetchQuery({
        queryKey: ["industry-valuation-review", row.industryCode],
        queryFn: () => industryValuationApi.review(row.industryCode),
        staleTime: 0,
      });
      if (!mountedRef.current || proposalSeqRef.current !== reqId) return;
      if (!detail.canPropose) {
        setExternalProposal({
          status: "ERROR",
          errorKey: "maintenance.industryValuation.error.cannotPropose",
        });
        return;
      }
      const baseEffectiveAt = detail.current?.effectiveAt ?? null;
      const draft = await industryValuationApi.propose(row.industryCode);
      if (!mountedRef.current || proposalSeqRef.current !== reqId) return;
      setExternalProposal({ status: "READY", baseEffectiveAt, draft });
    } catch (failure) {
      if (!mountedRef.current || proposalSeqRef.current !== reqId) return;
      const value = failure as { response?: { data?: { message?: string } }; message?: string } | null | undefined;
      const raw = value?.response?.data?.message || value?.message;
      setExternalProposal({
        status: "ERROR",
        error: raw || null,
        errorKey: raw ? null : "maintenance.industryValuation.error.operationFailed",
      });
    }
  };

  const handleCloseDrawer = () => {
    proposalSeqRef.current += 1;
    setDrawer(null);
    setExternalProposal(null);
  };

  const handleDrawerSaved = async () => {
    handleCloseDrawer();
    const refreshed = await overviewQuery.refetch({ cancelRefetch: true });
    if (!mountedRef.current) return;
    if (refreshed.isError) {
      setPostSaveNotice({
        severity: "warning",
        key: "maintenance.industryValuation.notice.savedRefreshFailed",
      });
    } else {
      setPostSaveNotice({
        severity: "success",
        key: "maintenance.industryValuation.notice.savedAndRefreshed",
      });
    }
  };

  const resetFilters = () => {
    setKeyword("");
    setStatusFilter("ALL");
    setModelFilter("ALL");
    setSortField("default");
    setSortDirection("asc");
    setPage(0);
  };

  const modelDisplay = (code: string | null) =>
    code ? t("industryProposal.codes." + code, { defaultValue: code }) : t("maintenance.industryValuation.table.unset");

  const formatMultipleRange = (low: number | null, high: number | null) =>
    low != null && high != null ? `${formatNumber(low)}x – ${formatNumber(high)}x` : "—";

  return (
    <Box sx={{ width: "100%", minWidth: 0 }}>
      <Stack
        direction={{ xs: "column", md: "row" }}
        justifyContent="space-between"
        alignItems={{ md: "center" }}
        spacing={1.5}
        sx={{ mb: 2.5 }}
      >
        <Box>
          <Typography variant="h5" fontWeight={800}>
            {t("maintenance.industryValuation.title")}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            {t("maintenance.industryValuation.subtitle")}
          </Typography>
        </Box>
        <Button
          variant="outlined"
          startIcon={<Refresh />}
          onClick={() => {
            setPostSaveNotice(null);
            void overviewQuery.refetch();
          }}
          disabled={overviewQuery.isFetching}
        >
          {t("maintenance.industryValuation.filters.refresh")}
        </Button>
      </Stack>

      {postSaveNotice && (
        <Alert
          severity={postSaveNotice.severity}
          sx={{ mb: 2 }}
          onClose={() => setPostSaveNotice(null)}
          action={
            postSaveNotice.severity === "warning" ? (
              <Button
                color="inherit"
                size="small"
                onClick={() => {
                  setPostSaveNotice(null);
                  void overviewQuery.refetch();
                }}
              >
                {t("maintenance.industryValuation.actions.retry")}
              </Button>
            ) : undefined
          }
        >
          {t(postSaveNotice.key)}
        </Alert>
      )}

      {overviewQuery.isError && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => void overviewQuery.refetch()}>
              {t("maintenance.industryValuation.actions.retry")}
            </Button>
          }
        >
          {errorMessage(overviewQuery.error, t("maintenance.industryValuation.error.loadFailed"))}
        </Alert>
      )}

      {overviewQuery.isLoading ? (
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ py: 4 }}>
          <CircularProgress size={24} />
          <Typography color="text.secondary">{t("maintenance.industryValuation.panel.processing")}</Typography>
        </Stack>
      ) : (
        overviewQuery.data && (
          <>
            <Grid container spacing={2} sx={{ mb: 2.5 }}>
              <Grid size={{ xs: 12, md: 4 }}>
                <Card variant="outlined" sx={{ height: "100%" }}>
                  <CardContent>
                    <Typography variant="body2" color="text.secondary" fontWeight={600}>
                      {t("maintenance.industryValuation.kpi.totalIndustries")}
                    </Typography>
                    <Typography variant="h4" fontWeight={800} sx={{ mt: 0.75 }} data-testid="kpi-total-count">
                      {kpi.total}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {t("maintenance.industryValuation.kpi.totalHint")}
                    </Typography>
                  </CardContent>
                </Card>
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <Card variant="outlined" sx={{ height: "100%" }}>
                  <CardContent>
                    <Typography variant="body2" color="text.secondary" fontWeight={600}>
                      {t("maintenance.industryValuation.kpi.configured")}
                    </Typography>
                    <Stack direction="row" spacing={1.25} alignItems="baseline" sx={{ mt: 0.75 }}>
                      <Typography variant="h4" fontWeight={800} color="success.main" data-testid="kpi-configured-count">
                        {kpi.configured}
                      </Typography>
                      <Chip
                        size="small"
                        color="success"
                        variant="outlined"
                        label={t("maintenance.industryValuation.kpi.configuredShare", { pct: kpi.pct })}
                        data-testid="kpi-configured-pct"
                      />
                    </Stack>
                  </CardContent>
                </Card>
              </Grid>

              <Grid size={{ xs: 12, md: 4 }}>
                <Card
                  variant="outlined"
                  sx={{
                    height: "100%",
                    borderColor: statusFilter === "UNCONFIGURED" ? "warning.main" : undefined,
                    bgcolor: statusFilter === "UNCONFIGURED" ? "rgba(245, 158, 11, 0.06)" : undefined,
                  }}
                >
                  <CardActionArea
                    data-testid="kpi-unconfigured-card"
                    sx={{ height: "100%" }}
                    onClick={() => {
                      setStatusFilter((prev) => (prev === "UNCONFIGURED" ? "ALL" : "UNCONFIGURED"));
                      setPage(0);
                    }}
                  >
                    <CardContent>
                      <Typography variant="body2" color="text.secondary" fontWeight={600}>
                        {t("maintenance.industryValuation.kpi.unconfigured")}
                      </Typography>
                      <Typography variant="h4" fontWeight={800} color="warning.main" sx={{ mt: 0.75 }} data-testid="kpi-unconfigured-count">
                        {kpi.unconfigured}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {statusFilter === "UNCONFIGURED"
                          ? t("maintenance.industryValuation.kpi.unconfiguredActive")
                          : t("maintenance.industryValuation.kpi.unconfiguredHint")}
                      </Typography>
                    </CardContent>
                  </CardActionArea>
                </Card>
              </Grid>
            </Grid>

            <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
              <Grid container spacing={2} alignItems="center">
                <Grid size={{ xs: 12, md: 4 }}>
                  <TextField
                    fullWidth
                    size="small"
                    label={t("maintenance.industryValuation.filters.searchLabel")}
                    placeholder={t("maintenance.industryValuation.filters.searchPlaceholder")}
                    value={keyword}
                    onChange={(event) => {
                      setKeyword(event.target.value);
                      setPage(0);
                    }}
                  />
                </Grid>
                <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                  <FormControl fullWidth size="small">
                    <InputLabel id="maintenance-status-filter-label">
                      {t("maintenance.industryValuation.filters.statusLabel")}
                    </InputLabel>
                    <Select
                      labelId="maintenance-status-filter-label"
                      label={t("maintenance.industryValuation.filters.statusLabel")}
                      value={statusFilter}
                      onChange={(event) => {
                        setStatusFilter(event.target.value as StatusFilter);
                        setPage(0);
                      }}
                    >
                      <MenuItem value="ALL">{t("maintenance.industryValuation.filters.statusAll")}</MenuItem>
                      <MenuItem value="UNCONFIGURED">{t("maintenance.industryValuation.filters.statusUnconfigured")}</MenuItem>
                      <MenuItem value="CONFIGURED">{t("maintenance.industryValuation.filters.statusConfigured")}</MenuItem>
                    </Select>
                  </FormControl>
                </Grid>
                <Grid size={{ xs: 12, sm: 6, md: 3 }}>
                  <FormControl fullWidth size="small">
                    <InputLabel id="maintenance-model-filter-label">
                      {t("maintenance.industryValuation.filters.modelLabel")}
                    </InputLabel>
                    <Select
                      labelId="maintenance-model-filter-label"
                      label={t("maintenance.industryValuation.filters.modelLabel")}
                      value={modelFilter}
                      onChange={(event) => {
                        setModelFilter(event.target.value);
                        setPage(0);
                      }}
                    >
                      <MenuItem value="ALL">{t("maintenance.industryValuation.filters.modelAll")}</MenuItem>
                      <MenuItem value="NONE">{t("maintenance.industryValuation.filters.modelUnassigned")}</MenuItem>
                      {supportedModels.map((code) => (
                        <MenuItem key={code} value={code}>
                          {modelDisplay(code)}
                        </MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </Grid>
                <Grid size={{ xs: 12, md: 2 }}>
                  <Button fullWidth variant="text" onClick={resetFilters}>
                    {t("maintenance.industryValuation.filters.reset")}
                  </Button>
                </Grid>
              </Grid>
            </Paper>

            <Paper variant="outlined">
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell sortDirection={sortField === "industryName" ? sortDirection : false}>
                        <TableSortLabel
                          active={sortField === "industryName"}
                          direction={sortField === "industryName" ? sortDirection : "asc"}
                          onClick={() => handleSort("industryName")}
                        >
                          {t("maintenance.industryValuation.table.industry")}
                        </TableSortLabel>
                      </TableCell>
                      <TableCell sortDirection={sortField === "modelCode" ? sortDirection : false}>
                        <TableSortLabel
                          active={sortField === "modelCode"}
                          direction={sortField === "modelCode" ? sortDirection : "asc"}
                          onClick={() => handleSort("modelCode")}
                        >
                          {t("maintenance.industryValuation.table.model")}
                        </TableSortLabel>
                      </TableCell>
                      <TableCell sortDirection={sortField === "primaryMetric" ? sortDirection : false}>
                        <TableSortLabel
                          active={sortField === "primaryMetric"}
                          direction={sortField === "primaryMetric" ? sortDirection : "asc"}
                          onClick={() => handleSort("primaryMetric")}
                        >
                          {t("maintenance.industryValuation.table.primaryMetric")}
                        </TableSortLabel>
                      </TableCell>
                      <TableCell sortDirection={sortField === "multipleLow" ? sortDirection : false}>
                        <TableSortLabel
                          active={sortField === "multipleLow"}
                          direction={sortField === "multipleLow" ? sortDirection : "asc"}
                          onClick={() => handleSort("multipleLow")}
                        >
                          {t("maintenance.industryValuation.table.multipleRange")}
                        </TableSortLabel>
                      </TableCell>
                      <TableCell align="right" sortDirection={sortField === "activeStockCount" ? sortDirection : false}>
                        <TableSortLabel
                          active={sortField === "activeStockCount"}
                          direction={sortField === "activeStockCount" ? sortDirection : "desc"}
                          onClick={() => handleSort("activeStockCount")}
                        >
                          {t("maintenance.industryValuation.table.activeStockCount")}
                        </TableSortLabel>
                      </TableCell>
                      <TableCell sortDirection={sortField === "configured" ? sortDirection : false}>
                        <TableSortLabel
                          active={sortField === "configured"}
                          direction={sortField === "configured" ? sortDirection : "asc"}
                          onClick={() => handleSort("configured")}
                        >
                          {t("maintenance.industryValuation.table.status")}
                        </TableSortLabel>
                      </TableCell>
                      <TableCell align="right">{t("maintenance.industryValuation.table.actions")}</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {pagedRows.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={7} align="center" sx={{ py: 4 }}>
                          <Typography color="text.secondary">
                            {items.length === 0
                              ? t("maintenance.industryValuation.table.emptyDictionary")
                              : t("maintenance.industryValuation.table.empty")}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ) : (
                      pagedRows.map((row) => (
                        <TableRow key={row.industryCode} hover data-testid={`industry-row-${row.industryCode}`}>
                          <TableCell>
                            <Typography variant="body2" fontWeight={700}>
                              {row.industryName}
                            </Typography>
                            <Typography variant="caption" color="text.secondary">
                              {row.industryCode}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2" color={row.modelCode ? "text.primary" : "text.secondary"}>
                              {modelDisplay(row.modelCode)}
                            </Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2" color={row.primaryMetric ? "text.primary" : "text.secondary"}>
                              {row.primaryMetric ? metricLabel(row.primaryMetric) : t("maintenance.industryValuation.table.unset")}
                            </Typography>
                          </TableCell>
                          <TableCell>{formatMultipleRange(row.assumedMultipleLow, row.assumedMultipleHigh)}</TableCell>
                          <TableCell align="right">
                            {t("maintenance.industryValuation.table.activeStockCountValue", { count: row.activeStockCount })}
                          </TableCell>
                          <TableCell>
                            <Chip
                              size="small"
                              color={row.configured ? "success" : "warning"}
                              variant={row.configured ? "filled" : "outlined"}
                              label={
                                row.configured
                                  ? t("maintenance.industryValuation.status.configured")
                                  : t("maintenance.industryValuation.status.unconfigured")
                              }
                            />
                          </TableCell>
                          <TableCell align="right">
                            <Stack direction="row" spacing={1} justifyContent="flex-end">
                              <Button
                                size="small"
                                variant={row.configured ? "outlined" : "contained"}
                                startIcon={<EditOutlined />}
                                onClick={() => handleOpenEdit(row)}
                              >
                                {row.configured
                                  ? t("maintenance.industryValuation.actions.editParams")
                                  : t("maintenance.industryValuation.actions.quickConfigure")}
                              </Button>
                              {row.canPropose && (
                                <Button
                                  size="small"
                                  variant="outlined"
                                  color="secondary"
                                  startIcon={<AutoAwesome />}
                                  onClick={() => void handleTriggerAi(row)}
                                >
                                  {t("maintenance.industryValuation.actions.triggerAi")}
                                </Button>
                              )}
                            </Stack>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </TableContainer>
              <TablePagination
                component="div"
                count={filteredAndSorted.length}
                page={safePage}
                onPageChange={(_, nextPage) => setPage(nextPage)}
                rowsPerPage={rowsPerPage}
                onRowsPerPageChange={(event) => {
                  setRowsPerPage(parseInt(event.target.value, 10));
                  setPage(0);
                }}
                rowsPerPageOptions={[10, 25, 50, 100]}
                labelRowsPerPage={t("maintenance.industryValuation.table.rowsPerPage")}
                labelDisplayedRows={({ from, to, count }) =>
                  t("maintenance.industryValuation.table.displayedRows", { from, to, count })
                }
                getItemAriaLabel={(type) =>
                  type === "next"
                    ? t("maintenance.industryValuation.table.nextPage")
                    : t("maintenance.industryValuation.table.previousPage")
                }
              />
            </Paper>
          </>
        )
      )}

      <Drawer
        anchor="right"
        open={Boolean(drawer)}
        onClose={handleCloseDrawer}
        PaperProps={{ sx: { width: { xs: "100%", sm: 540 }, p: 2.5 } }}
      >
        {drawer && (
          <Box>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1} sx={{ mb: 1.5 }}>
              <Box>
                <Typography variant="h6" fontWeight={800}>
                  {t("maintenance.industryValuation.drawer.title")}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  {drawer.industryName} · {drawer.code}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {t("maintenance.industryValuation.drawer.subtitle")}
                </Typography>
              </Box>
              <IconButton
                aria-label={t("maintenance.industryValuation.actions.closeDrawer")}
                onClick={handleCloseDrawer}
                size="small"
              >
                <Close />
              </IconButton>
            </Stack>
            <Divider sx={{ mb: 2 }} />
            <IndustryValuationProposalPanel
              key={`${drawer.code}:${drawer.sessionId}`}
              industryCode={drawer.code}
              defaultEditOpen={drawer.mode === "EDIT"}
              externalProposal={drawer.mode === "PROPOSE" ? externalProposal : null}
              embedded
              onSaved={handleDrawerSaved}
            />
          </Box>
        )}
      </Drawer>
    </Box>
  );
}
