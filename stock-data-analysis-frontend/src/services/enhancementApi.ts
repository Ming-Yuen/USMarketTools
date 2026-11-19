import { apiClient } from "./apiClient";
import {
  CustomTagCandidate,
  CustomTagConfirmItemRequest,
  CustomTagConfirmResult,
  CustomTagProposalPreview,
  EventAiSummary,
  ManualPrompt,
  OptionsSummary,
  StockEnhancementOverview,
} from "./types/dto/enhancement";

export const enhancementApi = {
  getOverview: async (figi: string, asOfDate?: string): Promise<StockEnhancementOverview> => {
    const params = asOfDate ? { asOfDate } : undefined;
    return (await apiClient.get<StockEnhancementOverview>(`enhancements/stocks/${encodeURIComponent(figi)}`, { params })).data;
  },
  refreshOptions: async (figi: string): Promise<OptionsSummary> => {
    return (await apiClient.post<OptionsSummary>(`enhancements/stocks/${encodeURIComponent(figi)}/options/refresh`)).data;
  },
  refreshInsiders: async (figi: string) => {
    return (await apiClient.post(`enhancements/stocks/${encodeURIComponent(figi)}/insiders/refresh`)).data;
  },
  refreshShortInterest: async (figi: string) => {
    return (await apiClient.post(`enhancements/stocks/${encodeURIComponent(figi)}/short-interest/refresh`)).data;
  },
  refreshNews: async (figi: string) => {
    return (await apiClient.post(`enhancements/stocks/${encodeURIComponent(figi)}/news/refresh`)).data;
  },
  summarizeNewsAutomated: async (figi: string): Promise<EventAiSummary> => {
    return (await apiClient.post<EventAiSummary>(`enhancements/stocks/${encodeURIComponent(figi)}/news/summarize`)).data;
  },
  getNewsPrompt: async (figi: string): Promise<ManualPrompt> => {
    return (await apiClient.get<ManualPrompt>(`enhancements/stocks/${encodeURIComponent(figi)}/news/prompt`)).data;
  },
  importNewsManual: async (figi: string, rawJson: string, inputFingerprint?: string): Promise<EventAiSummary> => {
    return (await apiClient.post<EventAiSummary>(`enhancements/stocks/${encodeURIComponent(figi)}/news/import`, {
      rawJson,
      inputFingerprint: inputFingerprint ?? null,
    })).data;
  },
  getCustomTagCandidates: async (figi: string): Promise<CustomTagCandidate[]> => {
    return (await apiClient.get<CustomTagCandidate[]>(`enhancements/stocks/${encodeURIComponent(figi)}/custom-tags/candidates`)).data;
  },
  getCustomTagPrompt: async (figi: string): Promise<ManualPrompt> => {
    return (await apiClient.get<ManualPrompt>(`enhancements/stocks/${encodeURIComponent(figi)}/custom-tags/prompt`)).data;
  },
  proposeCustomTagsAutomated: async (figi: string): Promise<CustomTagProposalPreview> => {
    return (await apiClient.post<CustomTagProposalPreview>(`enhancements/stocks/${encodeURIComponent(figi)}/custom-tags/propose`)).data;
  },
  importCustomTagsManual: async (figi: string, rawJson: string): Promise<CustomTagProposalPreview> => {
    return (await apiClient.post<CustomTagProposalPreview>(`enhancements/stocks/${encodeURIComponent(figi)}/custom-tags/import`, {
      rawJson,
    })).data;
  },
  confirmCustomTags: async (
    figi: string,
    proposalProvider: string,
    items: CustomTagConfirmItemRequest[],
  ): Promise<CustomTagConfirmResult> => {
    return (await apiClient.post<CustomTagConfirmResult>(`enhancements/stocks/${encodeURIComponent(figi)}/custom-tags/confirm`, {
      proposalProvider,
      items,
    })).data;
  },
  disableCustomTag: async (figi: string, mappingId: number): Promise<CustomTagConfirmResult> => {
    return (await apiClient.delete<CustomTagConfirmResult>(`enhancements/stocks/${encodeURIComponent(figi)}/custom-tags/${mappingId}`)).data;
  },
};
