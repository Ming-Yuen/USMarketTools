export interface IndustryValuationConfig {
  effectiveAt: string;
  modelCode: string;
  primaryMetric: string | null;
  assumedMultipleLow: number | null;
  assumedMultipleHigh: number | null;
  rationale: string | null;
  sourceUrl: string | null;
}
export interface IndustryValuationOverviewItem {
  industryCode: string;
  industryName: string;
  modelCode: string | null;
  primaryMetric: string | null;
  assumedMultipleLow: number | null;
  assumedMultipleHigh: number | null;
  effectiveAt: string | null;
  sourceUrl: string | null;
  configured: boolean;
  canPropose: boolean;
  activeStockCount: number;
}
export interface IndustryValuationOverview {
  items: IndustryValuationOverviewItem[];
  supportedMetrics: Record<string, string[]>;
}
export interface IndustryValuationReview {
  industryCode: string;
  industry: string;
  current: IndustryValuationConfig | null;
  supportedMetrics: Record<string, string[]>;
  canPropose: boolean;
}
export interface IndustryValuationDraft {
  modelCode: string;
  primaryMetric: string;
  assumedMultipleLow: number | null;
  assumedMultipleHigh: number | null;
  asOf: string;
  rationale: string | null;
  sources: Array<{ url: string; title: string; publishedDate: string | null; evidence: string | null }>;
}
export interface IndustryValuationUpdate {
  baseEffectiveAt: string | null;
  modelCode: string;
  primaryMetric: string;
  assumedMultipleLow: number | null;
  assumedMultipleHigh: number | null;
  rationale: string | null;
  sourceUrl: string | null;
}
