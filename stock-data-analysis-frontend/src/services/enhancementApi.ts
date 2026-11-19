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

export interface EnhancementReportContext {
  protectiveStopPrice?: number | null;
  closePrice?: number | null;
  verdict?: string | null;
  fundamentalBacking?: string | null;
  resistanceBreakout?: boolean | null;
}

const buildOptionalParams = (entries: Record<string, string | number | boolean | null | undefined>) => {
  const params: Record<string, string | number | boolean> = {};
  for (const [k, v] of Object.entries(entries)) {
    if (v !== undefined && v !== null && v !== "") {
      params[k] = v;
    }
  }
  return Object.keys(params).length > 0 ? params : undefined;
};

export const enhancementApi = {
  getOverview: async (
    figi: string,
    asOfDate?: string,
    context?: EnhancementReportContext,
  ): Promise<StockEnhancementOverview> => {
    const params = buildOptionalParams({
      asOfDate,
      protectiveStopPrice: context?.protectiveStopPrice,
      closePrice: context?.closePrice,
      verdict: context?.verdict,
      fundamentalBacking: context?.fundamentalBacking,
      resistanceBreakout: context?.resistanceBreakout,
    });
    return (await apiClient.get<StockEnhancementOverview>(`enhancements/stocks/${encodeURIComponent(figi)}`, { params })).data;
  },
  refreshOptions: async (
    figi: string,
    context?: Pick<EnhancementReportContext, "protectiveStopPrice" | "closePrice"> & { contextDate?: string | null },
  ): Promise<OptionsSummary> => {
    const params = buildOptionalParams({
      contextDate: context?.contextDate,
      protectiveStopPrice: context?.protectiveStopPrice,
      closePrice: context?.closePrice,
    });
    return (
      await apiClient.post<OptionsSummary>(`enhancements/stocks/${encodeURIComponent(figi)}/options/refresh`, null, {
        params,
      })
    ).data;
  },
  refreshInsiders: async (
    figi: string,
    context?: Pick<EnhancementReportContext, "verdict"> & { contextDate?: string | null },
  ) => {
    const params = buildOptionalParams({
      contextDate: context?.contextDate,
      verdict: context?.verdict,
    });
    return (
      await apiClient.post(`enhancements/stocks/${encodeURIComponent(figi)}/insiders/refresh`, null, { params })
    ).data;
  },
  refreshShortInterest: async (
    figi: string,
    context?: Pick<EnhancementReportContext, "fundamentalBacking" | "resistanceBreakout"> & {
      contextDate?: string | null;
    },
  ) => {
    const params = buildOptionalParams({
      contextDate: context?.contextDate,
      fundamentalBacking: context?.fundamentalBacking,
      resistanceBreakout: context?.resistanceBreakout,
    });
    return (
      await apiClient.post(`enhancements/stocks/${encodeURIComponent(figi)}/short-interest/refresh`, null, { params })
    ).data;
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
