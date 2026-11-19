import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import {
  Alert,
  Badge,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  List,
  ListItemButton,
  ListItemText,
  Popover,
  Stack,
  Switch,
  Tab,
  Tabs,
  Tooltip,
  Typography,
} from "@mui/material";
import { AccountBalanceWalletOutlined, DoneAll, NotificationsNone } from "@mui/icons-material";
import {
  fetchLatestClosingBriefing,
  fetchNotifications,
  fetchUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
} from "../services/portfolioApi";
import { DailyClosingBriefing, InAppNotification } from "../services/types/dto/portfolio";
import { formatAlertMessage, formatDecimal, formatPct, severityColor } from "./portfolioFormatters";
import { PortfolioDrawer } from "./PortfolioDrawer";

export const NotificationCenterPopover: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<number>(0);
  const [severityFilter, setSeverityFilter] = useState<string>("ALL");
  const [unreadOnly, setUnreadOnly] = useState<boolean>(false);

  const open = Boolean(anchorEl);

  const unreadQuery = useQuery({
    queryKey: ["notifications-unread-count"],
    queryFn: fetchUnreadNotificationCount,
    refetchInterval: 60_000,
  });

  const listQuery = useQuery<InAppNotification[]>({
    queryKey: ["notifications-list", severityFilter, unreadOnly],
    queryFn: () =>
      fetchNotifications({
        severity: severityFilter === "ALL" ? undefined : severityFilter,
        unreadOnly,
        limit: 50,
      }),
    enabled: open && activeTab === 0,
  });

  const briefingQuery = useQuery<DailyClosingBriefing | null>({
    queryKey: ["notifications-briefing-latest"],
    queryFn: fetchLatestClosingBriefing,
    enabled: open && activeTab === 1,
  });

  const markReadMutation = useMutation({
    mutationFn: (id: number) => markNotificationRead(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["notifications-unread-count"] });
      queryClient.invalidateQueries({ queryKey: ["notifications-list"] });
    },
  });

  const markAllMutation = useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["notifications-unread-count"] });
      queryClient.invalidateQueries({ queryKey: ["notifications-list"] });
    },
  });

  const unreadCount = unreadQuery.data?.unreadCount ?? 0;

  const handleNotificationClick = (item: InAppNotification) => {
    if (!item.read) {
      markReadMutation.mutate(item.id);
    }
    setAnchorEl(null);
    navigate(item.targetRoute || "/");
  };

  const briefing = briefingQuery.data;

  return (
    <>
      <Stack direction="row" spacing={0.75} alignItems="center">
        <Tooltip title={t("portfolio.openDrawer")}>
          <IconButton
            size="small"
            onClick={() => setDrawerOpen(true)}
            aria-label={t("portfolio.openDrawer")}
            data-testid="topbar-portfolio-btn"
          >
            <AccountBalanceWalletOutlined />
          </IconButton>
        </Tooltip>

        <Tooltip title={t("notifications.bellTooltip")}>
          <IconButton
            size="small"
            onClick={(e) => setAnchorEl(e.currentTarget)}
            aria-label={t("notifications.bellTooltip")}
            data-testid="notification-bell-btn"
          >
            <Badge badgeContent={unreadCount} color="error" max={99}>
              <NotificationsNone />
            </Badge>
          </IconButton>
        </Tooltip>
      </Stack>

      <Popover
        open={open}
        anchorEl={anchorEl}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        PaperProps={{ sx: { width: { xs: 340, sm: 460 }, maxHeight: 620, display: "flex", flexDirection: "column" } }}
      >
        <Box sx={{ px: 2, pt: 1.5, pb: 1, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <Typography variant="subtitle1" fontWeight={800}>{t("notifications.title")}</Typography>
          {activeTab === 0 && unreadCount > 0 && (
            <Button
              size="small"
              startIcon={<DoneAll fontSize="small" />}
              onClick={() => markAllMutation.mutate()}
              data-testid="mark-all-read-btn"
            >
              {t("notifications.markAllRead")}
            </Button>
          )}
        </Box>

        <Tabs
          value={activeTab}
          onChange={(_, v) => setActiveTab(v)}
          sx={{ px: 1.5, borderBottom: 1, borderColor: "divider", minHeight: 38 }}
        >
          <Tab label={t("notifications.tabs.alerts")} sx={{ minHeight: 38, py: 0.5 }} />
          <Tab label={t("notifications.tabs.briefing")} sx={{ minHeight: 38, py: 0.5 }} />
        </Tabs>

        {activeTab === 0 && (
          <Box sx={{ display: "flex", flexDirection: "column", flexGrow: 1, minHeight: 0 }}>
            <Stack
              direction="row"
              justifyContent="space-between"
              alignItems="center"
              sx={{ px: 2, py: 1, borderBottom: 1, borderColor: "divider" }}
            >
              <Stack direction="row" spacing={0.5}>
                {(["ALL", "CRITICAL", "WARNING", "INFO"] as const).map((sev) => (
                  <Chip
                    key={sev}
                    size="small"
                    label={t(`notifications.filters.${sev}`)}
                    color={severityFilter === sev ? (sev === "ALL" ? "primary" : severityColor(sev)) : "default"}
                    variant={severityFilter === sev ? "filled" : "outlined"}
                    onClick={() => setSeverityFilter(sev)}
                  />
                ))}
              </Stack>
              <Stack direction="row" alignItems="center" spacing={0.25}>
                <Typography variant="caption" color="text.secondary">{t("notifications.unreadOnly")}</Typography>
                <Switch size="small" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} />
              </Stack>
            </Stack>

            <Box sx={{ overflowY: "auto", flexGrow: 1 }}>
              {listQuery.isLoading ? (
                <Stack direction="row" spacing={1} alignItems="center" justifyContent="center" sx={{ p: 2.5 }}>
                  <CircularProgress size={18} />
                  <Typography variant="body2" color="text.secondary">{t("notifications.loading")}</Typography>
                </Stack>
              ) : listQuery.isError ? (
                <Alert severity="error" sx={{ m: 2 }}>{t("notifications.loadFailed")}</Alert>
              ) : (listQuery.data ?? []).length === 0 ? (
                <Typography variant="body2" color="text.secondary" sx={{ p: 2.5, textAlign: "center" }}>
                  {t("notifications.empty")}
                </Typography>
              ) : (
                <List dense disablePadding>
                  {(listQuery.data ?? []).map((item) => (
                    <React.Fragment key={item.id}>
                      <ListItemButton
                        onClick={() => handleNotificationClick(item)}
                        sx={{
                          alignItems: "flex-start",
                          bgcolor: item.read ? "transparent" : "action.hover",
                          py: 1.1,
                          px: 2,
                        }}
                        data-testid={`notification-item-${item.id}`}
                      >
                        <ListItemText
                          primary={
                            <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mb: 0.35 }}>
                              <Chip
                                size="small"
                                color={severityColor(item.severity)}
                                label={t(`notifications.filters.${item.severity}`, { defaultValue: item.severity })}
                                sx={{ height: 18, fontSize: "0.66rem" }}
                              />
                              <Typography variant="caption" color="text.secondary">{item.tradeDate}</Typography>
                              {!item.read && (
                                <Box sx={{ width: 7, height: 7, borderRadius: "50%", bgcolor: "error.main" }} />
                              )}
                            </Stack>
                          }
                          secondary={
                            <Typography variant="body2" color="text.primary" sx={{ fontSize: 13 }}>
                              {formatAlertMessage(t, item.alertCode, item.symbol, item.payload)}
                            </Typography>
                          }
                        />
                      </ListItemButton>
                      <Divider component="li" />
                    </React.Fragment>
                  ))}
                </List>
              )}
            </Box>
          </Box>
        )}

        {activeTab === 1 && (
          <Box sx={{ p: 2, overflowY: "auto", flexGrow: 1 }} data-testid="closing-briefing-panel">
            {briefingQuery.isLoading ? (
              <Stack direction="row" spacing={1} alignItems="center" justifyContent="center" sx={{ py: 2 }}>
                <CircularProgress size={18} />
                <Typography variant="body2" color="text.secondary">{t("notifications.loading")}</Typography>
              </Stack>
            ) : briefingQuery.isError ? (
              <Alert severity="error">{t("notifications.loadFailed")}</Alert>
            ) : !briefing ? (
              <Typography variant="body2" color="text.secondary">{t("notifications.noBriefing")}</Typography>
            ) : (
              <Stack spacing={1.5}>
                <Box>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="subtitle2" fontWeight={800}>
                      {t("notifications.briefing.marketOverview")} · {briefing.tradeDate}
                    </Typography>
                    <Chip
                      size="small"
                      label={t(`analysisReport.states.${briefing.marketRegime ?? "UNAVAILABLE"}`, {
                        defaultValue: briefing.marketRegime ?? "—",
                      })}
                    />
                  </Stack>
                  {briefing.regimeChanged && briefing.previousMarketRegime && (
                    <Alert severity="warning" sx={{ mt: 0.75, py: 0.25 }}>
                      {t("notifications.briefing.regimeChanged", {
                        prevDate: briefing.previousTradeDate ?? "—",
                        prev: t(`analysisReport.states.${briefing.previousMarketRegime}`, {
                          defaultValue: briefing.previousMarketRegime,
                        }),
                        curr: t(`analysisReport.states.${briefing.marketRegime ?? ""}`, {
                          defaultValue: briefing.marketRegime ?? "—",
                        }),
                      })}
                    </Alert>
                  )}
                  <Stack direction="row" spacing={1.5} sx={{ mt: 0.75 }}>
                    <Typography variant="caption" color="text.secondary">
                      {t("notifications.briefing.vix")}: <strong>{formatDecimal(briefing.vixClose, 2)}</strong>
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {t("notifications.briefing.treasury10y")}: <strong>{briefing.treasury10y != null ? `${formatDecimal(briefing.treasury10y, 2)}%` : "—"}</strong>
                    </Typography>
                  </Stack>
                </Box>

                <Divider />

                <Box>
                  <Typography variant="caption" fontWeight={700} color="success.main">
                    {t("notifications.briefing.topSectors")}
                  </Typography>
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.4, mb: 0.8 }}>
                    {briefing.topSectors.map((sec) => (
                      <Chip
                        key={sec.industryCode}
                        size="small"
                        color="success"
                        variant="outlined"
                        label={`${sec.industryName ?? sec.industryCode} (${formatPct(sec.cmf20d, 1, true)})`}
                      />
                    ))}
                  </Stack>
                  <Typography variant="caption" fontWeight={700} color="error.main">
                    {t("notifications.briefing.bottomSectors")}
                  </Typography>
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.4 }}>
                    {briefing.bottomSectors.map((sec) => (
                      <Chip
                        key={sec.industryCode}
                        size="small"
                        color="error"
                        variant="outlined"
                        label={`${sec.industryName ?? sec.industryCode} (${formatPct(sec.cmf20d, 1, true)})`}
                      />
                    ))}
                  </Stack>
                </Box>

                <Divider />

                <Box>
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.5 }}>
                    {t("notifications.briefing.portfolioSection")}
                  </Typography>
                  {briefing.portfolioHealth?.structureHealthy ? (
                    <Alert severity="success" sx={{ py: 0.25 }}>{t("portfolio.health.structureHealthy")}</Alert>
                  ) : (
                    <Stack direction="row" spacing={0.75}>
                      <Chip size="small" color="error" label={t("portfolio.health.criticalCount", { count: briefing.criticalCount })} />
                      <Chip size="small" color="warning" label={t("portfolio.health.warningCount", { count: briefing.warningCount })} />
                      <Chip size="small" color="info" label={t("portfolio.health.infoCount", { count: briefing.infoCount })} />
                    </Stack>
                  )}
                </Box>

                <Divider />

                <Box>
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.5 }}>
                    {t("notifications.briefing.watchlistSection")} ({briefing.watchlistOpportunities.length})
                  </Typography>
                  {briefing.watchlistOpportunities.length === 0 ? (
                    <Typography variant="caption" color="text.secondary">{t("notifications.briefing.noWatchlist")}</Typography>
                  ) : (
                    <Stack spacing={0.5}>
                      {briefing.watchlistOpportunities.map((w) => (
                        <Button
                          key={w.figi}
                          size="small"
                          variant="outlined"
                          sx={{ justifyContent: "space-between", textTransform: "none" }}
                          onClick={() => {
                            setAnchorEl(null);
                            navigate(`/stockAnalysis/${encodeURIComponent(w.figi)}`);
                          }}
                        >
                          <span><strong>{w.symbol}</strong> · {formatDecimal(w.entryLower)}–{formatDecimal(w.entryUpper)}</span>
                          <span>
                            {t("notifications.briefing.stopPrice")} {formatDecimal(w.protectiveStop)}
                            {w.positionSizing?.suggestedShares != null ? ` · ${t("portfolio.sizing.sharesUnit", { shares: w.positionSizing.suggestedShares })}` : ""}
                          </span>
                        </Button>
                      ))}
                    </Stack>
                  )}
                </Box>

                <Divider />

                <Box>
                  <Typography variant="subtitle2" fontWeight={700} sx={{ mb: 0.5 }}>
                    {t("notifications.briefing.radarTop5Section")} ({briefing.radarTop5.length})
                  </Typography>
                  {briefing.radarTop5.length === 0 ? (
                    <Typography variant="caption" color="text.secondary">{t("notifications.briefing.noRadar")}</Typography>
                  ) : (
                    <Stack spacing={0.5}>
                      {briefing.radarTop5.map((r) => (
                        <Button
                          key={`${r.trackType}-${r.figi}`}
                          size="small"
                          variant="outlined"
                          sx={{ justifyContent: "space-between", textTransform: "none" }}
                          onClick={() => {
                            setAnchorEl(null);
                            navigate(`/stockAnalysis/${encodeURIComponent(r.figi)}`);
                          }}
                        >
                          <span>
                            <strong>{r.symbol}</strong> · {formatDecimal(r.closePrice)}
                            {(r.planActionable === false || r.sizingTags?.includes("WAITING_TRIGGER_ESTIMATE")) && (
                              <Chip
                                size="small"
                                variant="outlined"
                                color="warning"
                                label={t("portfolio.sizing.tags.WAITING_TRIGGER_ESTIMATE")}
                                sx={{ ml: 0.75, height: 18, fontSize: "0.64rem" }}
                              />
                            )}
                          </span>
                          <span>
                            {t("notifications.briefing.stopPrice")} {formatDecimal(r.protectiveStop)}
                            {r.suggestedShares != null ? ` · ${t("portfolio.sizing.sharesUnit", { shares: r.suggestedShares })}` : ""}
                          </span>
                        </Button>
                      ))}
                    </Stack>
                  )}
                </Box>
              </Stack>
            )}
          </Box>
        )}
      </Popover>

      <PortfolioDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} />
    </>
  );
};
