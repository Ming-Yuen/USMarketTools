import { PositionSizing } from "./analysis";

export type PortfolioItemType = "HOLDING" | "WATCHLIST";
export type NotificationSeverity = "CRITICAL" | "WARNING" | "INFO";

export interface PortfolioRiskConfig {
  totalCapital: number;
  riskPerTradePct: number;
  maxSinglePositionPct: number;
  maxSectorExposurePct: number;
  capitalCurrency: string;
}

export interface PortfolioRiskConfigUpdateRequest {
  totalCapital: number;
  riskPerTradePct: number;
  maxSinglePositionPct: number;
  maxSectorExposurePct: number;
}

export interface PortfolioItemUpsertRequest {
  itemType: PortfolioItemType;
  shares?: number | null;
  avgCost?: number | null;
  costBasisDate?: string | null;
  notes?: string | null;
}

export interface PortfolioItemView {
  figi: string;
  symbol: string;
  stockName?: string | null;
  currency?: string | null;
  industryCode?: string | null;
  industryName?: string | null;
  itemType: PortfolioItemType;
  rawShares?: number | null;
  rawAvgCost?: number | null;
  costBasisDate?: string | null;
  adjustedShares?: number | null;
  adjustedAvgCost?: number | null;
  splitShareFactor?: number | null;
  splitAdjusted: boolean;
  splitAlignmentStatus?: string | null;
  latestQuoteDate?: string | null;
  latestClosePrice?: number | null;
  marketValue?: number | null;
  costBasisTotal?: number | null;
  unrealizedPnlAmount?: number | null;
  unrealizedPnlPct?: number | null;
  capitalWeightPct?: number | null;
  notes?: string | null;
  updatedAt?: string | null;
}

export interface SectorExposure {
  industryCode: string;
  industryName?: string | null;
  holdingCount: number;
  marketValueUsd: number;
  exposurePct: number;
  maxSectorExposurePct: number;
  concentrationExceeded: boolean;
}

export interface PortfolioOverview {
  config: PortfolioRiskConfig;
  items: PortfolioItemView[];
  sectorExposures: SectorExposure[];
  holdingCount: number;
  watchlistCount: number;
  totalHoldingMarketValueUsd: number;
  totalHoldingCostUsd: number;
  totalUnrealizedPnlUsd: number;
  totalUnrealizedPnlPct?: number | null;
  excludedHoldingsCount: number;
  coverageWarnings: string[];
}

export interface PortfolioAlert {
  severity: NotificationSeverity | string;
  category: string;
  alertCode: string;
  figi?: string | null;
  symbol?: string | null;
  stockName?: string | null;
  payload: Record<string, unknown>;
}

export interface HoldingHealthItem {
  figi: string;
  positionId?: string | null;
  symbol: string;
  stockName?: string | null;
  currency?: string | null;
  industryCode?: string | null;
  industryName?: string | null;
  evalDate?: string | null;
  closePrice?: number | null;
  adjustedShares?: number | null;
  adjustedAvgCost?: number | null;
  marketValue?: number | null;
  unrealizedPnlAmount?: number | null;
  unrealizedPnlPct?: number | null;
  splitAlignmentStatus?: string | null;
  verdict?: string | null;
  fundamentalBacking?: string | null;
  stance?: string | null;
  breakoutDiagnosis?: string | null;
  referenceProtectiveStop?: number | null;
  referenceStopSource?: string | null;
  referenceTarget1?: number | null;
  referenceTarget2?: number | null;
  referenceTargetsDate?: string | null;
  nextEarningsDate?: string | null;
  daysToNextEarnings?: number | null;
  earningsBufferLevel?: string | null;
  highestSeverity: string;
  alerts: PortfolioAlert[];
  dataGapReasons: string[];
}

export interface WatchlistOpportunity {
  figi: string;
  symbol: string;
  stockName?: string | null;
  currency?: string | null;
  industryCode?: string | null;
  industryName?: string | null;
  triggerType: string;
  verdict?: string | null;
  fundamentalBacking?: string | null;
  stance?: string | null;
  closePrice?: number | null;
  entryLower?: number | null;
  entryUpper?: number | null;
  protectiveStop?: number | null;
  firstTargetPrice?: number | null;
  positionSizing?: PositionSizing | null;
}

export interface PortfolioHealthSummary {
  tradeDate?: string | null;
  sourceMode: string;
  generatedAt?: string | null;
  marketRegime?: string | null;
  holdingCount: number;
  watchlistCount: number;
  alertHoldingCount: number;
  criticalCount: number;
  warningCount: number;
  infoCount: number;
  structureHealthy: boolean;
  completeCoverage: boolean;
  excludedHoldingsCount: number;
  coverageWarnings: string[];
  holdings: HoldingHealthItem[];
  watchlistOpportunities: WatchlistOpportunity[];
  sectorExposures: SectorExposure[];
  sectorAlerts: PortfolioAlert[];
}

export interface BriefingSector {
  industryCode: string;
  industryName?: string | null;
  cmf20d?: number | null;
  dollarVolume1d?: number | null;
  breadthAboveSma50Pct?: number | null;
  flowState?: string | null;
}

export interface BriefingRadarItem {
  figi: string;
  symbol: string;
  stockName?: string | null;
  currency?: string | null;
  trackType: string;
  industryCode?: string | null;
  industryName?: string | null;
  verdict?: string | null;
  stance?: string | null;
  closePrice?: number | null;
  discountToMid?: number | null;
  entryLower?: number | null;
  entryUpper?: number | null;
  protectiveStop?: number | null;
  firstTargetPrice?: number | null;
  netRiskReward?: number | null;
  pullbackAnchor?: string | null;
  positionFraction?: string | null;
  planStatus?: string | null;
  planActionable?: boolean;
  suggestedShares?: number | null;
  positionCapitalPct?: number | null;
  sizingTags: string[];
}

export interface DailyClosingBriefing {
  tradeDate: string;
  marketRegime?: string | null;
  previousMarketRegime?: string | null;
  previousTradeDate?: string | null;
  regimeChanged: boolean;
  vixClose?: number | null;
  treasury10y?: number | null;
  topSectors: BriefingSector[];
  bottomSectors: BriefingSector[];
  portfolioHealth: PortfolioHealthSummary;
  watchlistOpportunities: WatchlistOpportunity[];
  radarTop5: BriefingRadarItem[];
  criticalCount: number;
  warningCount: number;
  infoCount: number;
  observationCutoff?: string | null;
  generatedAt?: string | null;
}

export interface InAppNotification {
  id: number;
  dedupKey: string;
  tradeDate: string;
  severity: NotificationSeverity | string;
  category: string;
  alertCode: string;
  figi?: string | null;
  symbol?: string | null;
  stockName?: string | null;
  targetRoute: string;
  payload: Record<string, unknown>;
  read: boolean;
  readAt?: string | null;
  createdAt: string;
}

export interface UnreadCountResponse {
  unreadCount: number;
}
