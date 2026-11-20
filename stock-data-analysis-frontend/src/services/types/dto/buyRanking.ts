export type BuyRankingScopeMode = "SECTOR" | "PORTFOLIO" | "CUSTOM";
export type BuyRankingAssetType = "COMMON_STOCK" | "ADR" | "ETF" | "REIT";
export type BuyRankingTier = "TIER_1" | "TIER_2" | "TIER_3" | "TIER_4";
export type BuyRankingFundamentalGrade = "ALL" | "MIXED" | "STRONG";
export type BuyRankingSortBy =
  | "TIER_COMPOSITE"
  | "NET_RR_DESC"
  | "DISCOUNT_DESC"
  | "DISTANCE_TO_BUY_ZONE_ASC"
  | "ROIC_DESC"
  | "SYMBOL_ASC";
export type BuyRankingHorizon = "POSITION" | "SWING";
export type BuyRankingPortfolioType = "WATCHLIST" | "HOLDING";
export type BuyRankingExportFormat = "XLSX" | "CSV";

export interface BuyRankingRequest {
  scopeMode: BuyRankingScopeMode;
  industryCodes?: string[];
  sectors?: string[];
  portfolioTypes?: BuyRankingPortfolioType[];
  customSymbols?: string[] | string;
  assetTypes?: BuyRankingAssetType[];
  excludeReits?: boolean;
  tiers?: BuyRankingTier[];
  minFundamentalGrade?: BuyRankingFundamentalGrade;
  minNetRiskReward?: number | null;
  excludeEarningsWithinDays?: number | null;
  sortBy?: BuyRankingSortBy;
  horizon?: BuyRankingHorizon;
  locale?: string;
  page?: number;
  pageSize?: number;
}

export interface BuyRankingOptionsDto {
  scopeModes: BuyRankingScopeMode[];
  portfolioTypes: BuyRankingPortfolioType[];
  assetTypes: BuyRankingAssetType[];
  tiers: BuyRankingTier[];
  fundamentalGrades: BuyRankingFundamentalGrade[];
  sortOptions: BuyRankingSortBy[];
  horizons: BuyRankingHorizon[];
  maxCandidates: number;
}

export interface MarketBackgroundDto {
  marketRegime: string;
  vixClose: number | null;
  tenYearYieldPct: number | null;
  hyOasPct: number | null;
  hyOasDeltaBps: number | null;
  hyOasStatus: string | null;
  macroCautionActive: boolean;
  marketDataReady: boolean;
  marketDataDate: string | null;
  sectorMoneyFlowDate: string | null;
  lastClosedTradingDay?: string | null;
  staleDateFallback?: boolean;
}

export interface ScopeSummaryDto {
  scopeMode: BuyRankingScopeMode;
  scopeLabel: string;
  industryCodes: string[];
  industryNames: string[];
  portfolioTypes: BuyRankingPortfolioType[];
  matchedTotalCount: number;
  evaluatedCount: number;
  filteredTotalCount: number;
  maxCandidates: number;
  truncated: boolean;
  unmatchedSymbols: string[];
  untrackedSymbols: string[];
  excludedByAssetTypeSymbols: string[];
}

export interface AppliedFiltersDto {
  scopeMode: BuyRankingScopeMode;
  industryCodes: string[];
  portfolioTypes: BuyRankingPortfolioType[];
  customSymbols: string[];
  assetTypes: BuyRankingAssetType[];
  excludeReits: boolean;
  tiers: BuyRankingTier[];
  minFundamentalGrade: BuyRankingFundamentalGrade;
  minNetRiskReward: number | null;
  excludeEarningsWithinDays: number | null;
  sortBy: BuyRankingSortBy;
  horizon: BuyRankingHorizon;
}

export interface BuyZoneDto {
  low: number;
  mid: number;
  high: number;
}

export interface BuyRankingItemDto {
  rank: number;
  recommendedRank: number;
  tier: BuyRankingTier;
  tierScore: number | null;
  figi: string;
  symbol: string;
  stockName: string | null;
  currency: string | null;
  industryCode: string | null;
  sector: string | null;
  assetType: BuyRankingAssetType;
  rawStockType: string | null;
  verdict: string | null;
  fundamentalBacking: string | null;
  stance: string | null;
  breakoutDiagnosis: string | null;
  currentPrice: number | null;
  buyZone: BuyZoneDto | null;
  distanceToBuyZonePct: number | null;
  nearSupportWithin3Pct: boolean;
  buyZoneSource: string | null;
  stopLossPrice: number | null;
  stopLossDistancePct: number | null;
  firstTargetPrice: number | null;
  netRiskReward: number | null;
  planNetRiskReward: number | null;
  suggestedShares: number | null;
  positionScaleTag: string | null;
  positionSizingStatus: string | null;
  positionSizingActionable: boolean;
  positionSizingReasonCode: string | null;
  sectorConcentrationWarning: boolean;
  positionSizingTags: string[];
  valuationDiscountPct: number | null;
  forwardPeg: number | null;
  forwardPe: number | null;
  roic: number | null;
  adjFcfTtm: number | null;
  reverseDcfImpliedGrowth: number | null;
  reverseDcfBasis: string | null;
  reverseDcfStatus: string | null;
  etfTrendScore: number | null;
  etfCmf20d: number | null;
  etfRs60dVsSpy: number | null;
  nextEarningsDate: string | null;
  daysToEarnings: number | null;
  sectorFlowState: string | null;
  sectorCmf20d: number | null;
  reasonCode: string;
  reasonParams: Record<string, unknown>;
  actionReason: string;
}

export interface BuyRankingResponse {
  reportId: string;
  asOfDate: string;
  generatedAtHk: string;
  observationCutoff: string;
  resolvedLocale: string;
  marketRegime: string;
  marketContext: MarketBackgroundDto;
  scopeSummary: ScopeSummaryDto;
  totalCount: number;
  page: number;
  pageSize: number;
  tierCounts: Record<BuyRankingTier, number>;
  appliedFilters: AppliedFiltersDto;
  items: BuyRankingItemDto[];
}
