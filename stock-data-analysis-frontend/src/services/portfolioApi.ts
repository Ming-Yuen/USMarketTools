import { apiClient } from "./apiClient";
import {
  DailyClosingBriefing,
  InAppNotification,
  PortfolioHealthSummary,
  PortfolioItemUpsertRequest,
  PortfolioItemView,
  PortfolioOverview,
  PortfolioRiskConfig,
  PortfolioRiskConfigUpdateRequest,
  UnreadCountResponse,
} from "./types/dto/portfolio";

export const PORTFOLIO_UPDATED_EVENT = "portfolio:updated";

export function dispatchPortfolioUpdated(): void {
  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(PORTFOLIO_UPDATED_EVENT));
  }
}

export async function fetchPortfolioOverview(): Promise<PortfolioOverview> {
  return (await apiClient.get<PortfolioOverview>("portfolio")).data;
}

export async function upsertPortfolioItem(
  figi: string,
  payload: PortfolioItemUpsertRequest,
): Promise<PortfolioItemView> {
  return (await apiClient.put<PortfolioItemView>(`portfolio/${encodeURIComponent(figi)}`, payload)).data;
}

export async function deletePortfolioItem(figi: string): Promise<void> {
  await apiClient.delete(`portfolio/${encodeURIComponent(figi)}`);
}

export async function fetchPortfolioRiskConfig(): Promise<PortfolioRiskConfig> {
  return (await apiClient.get<PortfolioRiskConfig>("portfolio/config")).data;
}

export async function updatePortfolioRiskConfig(
  payload: PortfolioRiskConfigUpdateRequest,
): Promise<PortfolioRiskConfig> {
  return (await apiClient.put<PortfolioRiskConfig>("portfolio/config", payload)).data;
}

export async function fetchPortfolioHealth(live = false): Promise<PortfolioHealthSummary> {
  return (await apiClient.get<PortfolioHealthSummary>("portfolio/health", { params: { live } })).data;
}

export async function fetchNotifications(params?: {
  severity?: string;
  category?: string;
  unreadOnly?: boolean;
  limit?: number;
}): Promise<InAppNotification[]> {
  return (await apiClient.get<InAppNotification[]>("notifications", { params })).data;
}

export async function fetchUnreadNotificationCount(): Promise<UnreadCountResponse> {
  return (await apiClient.get<UnreadCountResponse>("notifications/unread-count")).data;
}

export async function markNotificationRead(id: number): Promise<{ id: number; updated: boolean; unreadCount: number }> {
  return (await apiClient.post<{ id: number; updated: boolean; unreadCount: number }>(`notifications/${id}/read`)).data;
}

export async function markAllNotificationsRead(): Promise<{ updatedCount: number; unreadCount: number }> {
  return (await apiClient.post<{ updatedCount: number; unreadCount: number }>("notifications/read-all")).data;
}

export async function fetchLatestClosingBriefing(): Promise<DailyClosingBriefing | null> {
  const res = await apiClient.get<DailyClosingBriefing>("notifications/briefing/latest");
  if (res.status === 204 || !res.data) {
    return null;
  }
  return res.data;
}
