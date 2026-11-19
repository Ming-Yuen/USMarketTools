export interface DashboardResponse {
  snapshotMeta: SnapshotMeta;
  tapeRows: TapeRow[];
  indexRows: IndexRow[];
  etfRows: EtfRow[];
  alerts: MessageItem[];
  events: EventItem[];
  signalRows: SignalRow[];
  fearGreedComponents: FearGreedComponent[];
  marketSummary: MarketSummary;
  executionBias: ExecutionBias;
  marketRegime?: MarketRegimeBanner | null;
  sectorEtfs?: SectorEtfRow[];
  sectorMoneyFlow?: SectorMoneyFlowSummary | null;
  opportunityRadar?: OpportunityRadarSummary | null;
  signalTrackingStats?: SignalTrackingStatsSummary | null;
}

export interface SnapshotMeta {
  marketDate: string;
  sources: string[];
}

export interface TapeRow {
  symbol: string;
  value: string;
  change: string;
  positive: boolean;
}

export interface IndexRow {
  nameCode: string;
  symbol: string;
  value: string;
  day: string;
  trend: string;
  noteCode: string;
}

export interface EtfRow {
  ticker: string;
  price: string;
  day: string;
  rsi: string;
  biasCode: string;
  ma20Deviation: string;
  volumeRatio5d: string;
  noteCode: string;
  sourceCode: string;
  time: string;
}

export interface EventItem {
  id: string;
  title: string | null;
  titleCode: string | null;
  categoryCode: string;
  startsAt: string;
  levelCode: string;
  symbol: string | null;
  source: string | null;
  sourceUrl: string | null;
  statusCode: string;
  allDay: boolean;
}

export interface SignalRow {
  code: "FEAR_GREED" | "CNN_VIX_SCORE" | "CNN_PUT_CALL_SCORE" | "CNN_MARKET_BREADTH_SCORE";
  value: string;
  statusCode: string;
  sourceCode: string;
}

export interface FearGreedComponent {
  code: string;
  value: string;
  statusCode: string;
}

export interface MarketSummary {
  titleCode: string;
  description: MessageItem;
  tag: MessageItem;
}

export interface ExecutionBias {
  titleCode: string;
  descriptionCode: string;
}

export interface MessageItem {
  code: string;
  params: Record<string, string | number>;
}

export interface MarketRegimeBanner {
  regime: string;
  vixClose: number | null;
  headwindActive: boolean;
  correctionActive: boolean;
  asOfDate: string | null;
  hyOasPct?: number | null;
  hyOasDate?: string | null;
  hyOasDeltaVsPrior20Bps?: number | null;
  hyOasStatus?: string | null;
  hyOasAlertActive?: boolean;
}

export interface SectorEtfRow {
  symbol: string;
  sectorCode: string;
  closePrice: number | null;
  return5d: number | null;
  return20d: number | null;
  return60d: number | null;
  rvol20: number | null;
  excessVsSpy5d: number | null;
  excessVsSpy20d: number | null;
  excessVsSpy60d: number | null;
  tradeDate: string | null;
}

export interface SectorMoneyFlowRow {
  industryCode: string;
  industryName: string;
  stockCount: number;
  dollarVolume1d: number | null;
  dollarVolume5d: number | null;
  marketVolumeShare1d: number | null;
  marketVolumeShare5d: number | null;
  marketVolumeShare20d: number | null;
  volumeShareDelta5dVs20d: number | null;
  cmf5d: number | null;
  cmf20d: number | null;
  breadthAboveSma50Count: number;
  breadthSma50Total: number;
  breadthAboveSma50Pct: number | null;
  breadthAboveSma200Count: number;
  breadthSma200Total: number;
  breadthAboveSma200Pct: number | null;
  return20dMedian: number | null;
  return60dMedian: number | null;
  flowState: string | null;
}

export interface SectorMoneyFlowSummary {
  tradeDate: string | null;
  marketTotalStockCount: number;
  classifiedCoveragePct: number | null;
  rows: SectorMoneyFlowRow[];
}

export interface OpportunityRadarItem {
  figi: string;
  symbol: string;
  stockName: string | null;
  currency: string | null;
  trackType: string;
  industryCode: string | null;
  industryName: string | null;
  marketCapTier: string | null;
  verdict: string;
  fundamentalBacking: string;
  stance: string;
  headwindWatchOnly: boolean;
  macroCautionActive: boolean;
  closePrice: number | null;
  fairValueLow: number | null;
  fairValueMid: number | null;
  fairValueHigh: number | null;
  discountToMid: number | null;
  forwardPeg: number | null;
  forwardPe: number | null;
  revenueGrowthYoy: number | null;
  forwardGrowthBasis: string | null;
  supportLower: number | null;
  supportUpper: number | null;
  supportDistancePct: number | null;
  pullbackAnchor: string | null;
  pullbackAnchorPrice: number | null;
  protectiveStop: number | null;
  firstTargetPrice: number | null;
  netRiskReward: number | null;
  planStatus: string | null;
  positionFraction: string | null;
  nextEarningsDate: string | null;
  daysToNextEarnings: number | null;
  sectorCmf20d: number | null;
  sectorFlowState: string | null;
  insiderBuyUndervaluedConfirm?: boolean;
}

export interface OpportunityRadarSummary {
  radarDate: string | null;
  marketRegime: string | null;
  headwindActive: boolean;
  valueCount: number;
  starterCount: number;
  valueOpportunities: OpportunityRadarItem[];
  starterOpportunities: OpportunityRadarItem[];
}

export interface SignalTrackingGroupStats {
  groupKey: string;
  groupLabel: string;
  totalSignals: number;
  settled5dCount: number;
  avgReturn5d: number | null;
  settled20dCount: number;
  avgReturn20d: number | null;
  winRate20d: number | null;
  settled60dCount: number;
  avgReturn60d: number | null;
  resolvedOutcomeCount: number;
  targetHitFirstCount: number;
  stopHitFirstCount: number;
  targetHitRate: number | null;
  avgMfe60d: number | null;
  avgMae60d: number | null;
}

export interface SignalTrackingStatsSummary {
  totalSignals: number;
  pendingCount: number;
  settled20dCount: number;
  overallWinRate20d: number | null;
  overallAvgReturn20d: number | null;
  overallTargetHitRate: number | null;
  bySignalType: SignalTrackingGroupStats[];
  byMarketRegime: SignalTrackingGroupStats[];
  byIndustry: SignalTrackingGroupStats[];
  byMarketCapTier: SignalTrackingGroupStats[];
}
