import { Financials, ValuationResult } from "./valuation";

export type MetricStatus = "AVAILABLE" | "STALE" | "INSUFFICIENT_DATA" | "UNVERIFIED" | "NOT_APPLICABLE";
export type AnalysisHorizon = "POSITION" | "SWING";
export interface AnalysisReportRequest { figi: string; valuationDate?: string; horizon?: AnalysisHorizon; }
export interface AnalysisReason { code: string; component: string; message: string; params?: Record<string, unknown>; }
export interface MetricResult<T> { status: MetricStatus; value: T | null; reasons: AnalysisReason[]; lastKnown?: T | null; }

export interface NormalizedBar {
  sessionDate: string; open: number; high: number; low: number; close: number; volume: MetricResult<number>;
}
export interface AtrPoint { sessionDate: string; period: number; atr: number; atrPercent: number; seedDate: string; trCount: number; warmupCount: number; }
export interface StructureTrend { state: string; asOf: string; highRelation?: string | null; lowRelation?: string | null; pivotIds: string[]; lastTransitionAt?: string | null; }
export interface TimeframeStructure {
  timeframe: "DAILY" | "WEEKLY" | "MONTHLY"; bars: NormalizedBar[]; atr14: MetricResult<AtrPoint>;
  highRelation: MetricResult<string>; lowRelation: MetricResult<string>; minorTrend: MetricResult<StructureTrend>; majorTrend: MetricResult<StructureTrend>;
  reasons: AnalysisReason[];
}
export interface PriceStructure { figi: string; priceDataThrough: string; timeframes: TimeframeStructure[]; reasons: AnalysisReason[]; }
export type BreakoutDiagnosisCode = "NONE" | "SUSPECTED_SHAKEOUT" | "SPRING_RECLAIMED" | "BREACH_OBSERVING" | "HIGH_RISK_BREAKDOWN";

export interface BreakoutDiagnosis {
  diagnosis: BreakoutDiagnosisCode;
  zoneId?: string | null;
  zoneBoundsVersion?: number;
  zoneRoleVersion?: number;
  zoneTimeframe?: string | null;
  supportLower?: number | null;
  supportUpper?: number | null;
  breachDate?: string | null;
  reclaimDate?: string | null;
  validThroughSession?: string | null;
  diagnosisDate?: string | null;
  barsSinceBreach?: number | null;
  rvolAtBreach?: number | null;
  rvolAtReclaim?: number | null;
  rvolLatest?: number | null;
  lowerShadowRatio?: number | null;
  bodyRatio?: number | null;
  closePositionInRange?: number | null;
  stockReturnOnBreach?: number | null;
  spyReturnOnBreach?: number | null;
  excessReturnOnBreach?: number | null;
  evidenceCodes?: string[];
  reasons?: AnalysisReason[];
}

export interface LevelZone {
  id: string; timeframe: string; lower: number; upper: number; role: "SUPPORT" | "RESISTANCE"; strength: number;
  confirmedAt?: string | null; testCount: number; lastTestAt?: string | null; state: string; center?: number | null; strengthLabel: string;
  effectiveStrength: number; distanceLowerPercent?: number | null; distanceUpperPercent?: number | null; retestState: string; majorLevel?: boolean;
  volumeProfileConfluence?: "POC" | "HVN" | string | null;
  confluenceTags?: string[];
}
export interface SupportResistance {
  supports: LevelZone[];
  resistances: LevelZone[];
  containingZones: LevelZone[];
  reasons: AnalysisReason[];
  breakoutDiagnosis?: BreakoutDiagnosis | null;
  zoneDiagnoses?: BreakoutDiagnosis[];
}
export interface RiskReward { entry: number; stop: number; grossRR: number; netRR: number; potentialUpsidePercent: number; potentialDownsidePercent: number; }
export interface PriceRange { lower: number; upper: number; }
export interface TradePlan {
  planId: string; setupType: string; entryRange?: PriceRange | null; entryMethod?: string | null; trigger?: string | null;
  stop?: { protectivePrice: number; closeThreshold: number | null; requiredCloses?: number; evaluationTimeframe?: string } | null;
  targets: { price: number; weight: number; reason: string; sourceZoneId?: string }[];
  riskReward: MetricResult<RiskReward>; status: string; reasons: AnalysisReason[]; validThroughSession?: string | null;
}
export interface TradePlanResult { plans: TradePlan[]; currentPrice: number; priceDataThrough: string; currentPriceRR: MetricResult<RiskReward>; reasons: AnalysisReason[]; contextEvaluated: boolean; }
export interface MomentumVolume { rsi14: MetricResult<number>; momentumSignal: MetricResult<number>; macd: MetricResult<number>; macdSignal: MetricResult<number>; volumeVs20Day: MetricResult<number>; volumeSignal: MetricResult<number>; drawdownFrom252High: MetricResult<number>; return20: MetricResult<number>; return60: MetricResult<number>; }
export interface IndicatorSeriesPoint {
  sessionDate: string;
  sma20: number | null;
  sma50: number | null;
  sma200: number | null;
  bollingerUpper: number | null;
  bollingerLower: number | null;
  rsi14: number | null;
  macd: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
}
export interface IndicatorInterpretation { code: string; params: Record<string, string | number>; status: MetricStatus; reasons: AnalysisReason[]; }
export interface MomentumAnalysis {
  indicators: Record<string, MetricResult<number>>;
  interpretations?: Record<string, IndicatorInterpretation>;
  series?: IndicatorSeriesPoint[];
  reasons: AnalysisReason[];
}
export interface RelativeStrength {
  benchmark: string;
  excessReturn20: MetricResult<number>;
  excessReturn60: MetricResult<number>;
  excessReturn120?: MetricResult<number>;
  primarySignal?: MetricResult<number>;
  reasons: AnalysisReason[];
}
export interface DrawdownAnalysis {
  availableHistoryHighDrawdown?: MetricResult<number>;
  drawdown252?: MetricResult<number>;
  majorHighDrawdown?: MetricResult<number>;
  maximumCloseDrawdown?: MetricResult<number>;
  completedCorrectionMedian?: MetricResult<number>;
  reasons: AnalysisReason[];
}
export interface TreasuryContext {
  tenYear: MetricResult<number>;
  twoYear?: MetricResult<number>;
  tenYearChange20?: MetricResult<number>;
  curve10yMinus2y?: MetricResult<number>;
  reasons: AnalysisReason[];
}
export interface EventRisk {
  eventId: string;
  type: string;
  subtype?: string | null;
  title?: string | null;
  eventDate?: string | null;
  occurrenceTime?: string | null;
  timePrecision?: string | null;
  observedAt?: string | null;
  source?: string | null;
  blocksNewEntry: boolean;
}
export interface AnchoredVwapPoint {
  sessionDate: string;
  value: number;
}

export interface VolumeBin {
  index: number;
  lower: number;
  upper: number;
  volume: number;
}

export interface VolumeProfile {
  bins: VolumeBin[];
  poc?: VolumeBin | null;
  highVolumeNodes: VolumeBin[];
  lowVolumeNodes: VolumeBin[];
  approximationLabel?: string | null;
}

export interface VolumeAnalysis {
  rvol20?: MetricResult<number> | null;
  obv?: MetricResult<number> | null;
  netVolumeDays20?: MetricResult<number> | null;
  primarySignal?: MetricResult<number> | null;
  profile252?: MetricResult<VolumeProfile> | null;
  anchoredVwap?: MetricResult<number> | null;
  earningsEventDate?: string | null;
  earningsAnchorDate?: string | null;
  earningsAnchoredVwap?: MetricResult<number> | null;
  earningsAnchoredVwapSeries?: AnchoredVwapPoint[];
  reasons?: AnalysisReason[];
}

export interface MarketContext {
  momentum: MomentumVolume;
  relativeStrength?: RelativeStrength | null;
  market: MetricResult<{ regime: string; fearGreedScore: MetricResult<number>; breadthScore: MetricResult<number> }>;
  events?: EventRisk[];
  tradeConstraint: { entryPermission: string; reasonCodes: string[] };
  reasons: AnalysisReason[];
  momentumDetail?: MomentumAnalysis | null;
  volume?: VolumeAnalysis | null;
  drawdown?: DrawdownAnalysis | null;
  treasury?: TreasuryContext | null;
  vixClose?: MetricResult<number> | null;
}

export interface FundamentalSnapshot {
  periodEnd: string | null;
  priorYearPeriodEnd: string | null;
  threeYearAgoPeriodEnd: string | null;
  reportingCurrency: string | null;
  financialInstitution: boolean;
  revenueGrowthYoy: MetricResult<number>;
  epsGrowthYoy: MetricResult<number>;
  fcfGrowthYoy: MetricResult<number>;
  revenueCagr3y: MetricResult<number>;
  epsCagr3y: MetricResult<number>;
  trailingPeg: MetricResult<number>;
  pegGrowthBasis: string | null;
  roe: MetricResult<number>;
  fcfMargin: MetricResult<number>;
  fcfConversion: MetricResult<number>;
  netDebtToEbitda: MetricResult<number>;
  fcfYield: MetricResult<number>;
  earningsYield: MetricResult<number>;
  netShareChangeYoy: MetricResult<number>;
}

export interface FairValueBand {
  low: number | null;
  mid: number | null;
  high: number | null;
  currency: string | null;
  historicalLow?: number | null;
  historicalMid?: number | null;
  historicalHigh?: number | null;
  peerLow?: number | null;
  peerMid?: number | null;
  peerHigh?: number | null;
  assumptionLow?: number | null;
  assumptionMid?: number | null;
  assumptionHigh?: number | null;
}

export interface SummaryFlag {
  code: string;
  severity: "HIGH" | "MEDIUM" | "LOW" | string;
  params?: Record<string, unknown>;
}

export interface WatchItem {
  code: string;
  params?: Record<string, unknown>;
}

export interface MacroCaution {
  active: boolean;
  evaluated: boolean;
  marketRegime?: string | null;
  vixClose?: number | null;
  tenYearChange20?: number | null;
  triggerCodes: string[];
  suggestedMarginBufferMin?: number | null;
  suggestedMarginBufferMax?: number | null;
  suggestedPositionScale?: number | null;
}

export interface TrendPullbackStarter {
  eligible: boolean;
  matchedAnchor?: string | null;
  anchorPrice?: number | null;
  distanceRatio?: number | null;
  suggestedStop?: number | null;
  positionFraction?: string | null;
  forwardGrowthBasis?: string | null;
  forwardPeg?: number | null;
  forwardPegBasis?: string | null;
  forwardPe?: number | null;
  ineligibleReasonCode?: string | null;
  ineligibleMessage?: string | null;
}

export interface InvestmentSummary {
  verdict: string;
  fundamentalBacking: string;
  stance: string;
  primaryMetric: string | null;
  anchorSource: string;
  fairValue: FairValueBand | null;
  discountToMid: number | null;
  upsideToMid: number | null;
  yieldSpreadVs10y: MetricResult<number> | null;
  yieldSpreadBasis: string | null;
  technicalSupportZoneId: string | null;
  technicalSupportRange: PriceRange | null;
  technicalSupportTimeframe: string | null;
  technicalSupportStrength: number | null;
  weeklyTrend: string | null;
  aboveSma200: boolean | null;
  entryPermission: string | null;
  eventGateBlocked: boolean;
  nextBlockingEventDate: string | null;
  risks: SummaryFlag[];
  watchItems: WatchItem[];
  reasons: AnalysisReason[];
  macroCaution?: MacroCaution | null;
  trendPullbackStarter?: TrendPullbackStarter | null;
  breakoutDiagnosis?: BreakoutDiagnosis | null;
  rightSideConfirmationBasis?: string | null;
}

export interface FutuValuationObservation {
  observationDate: string; observedAt: string;
  pe: number | null; pb: number | null; ps: number | null; forwardPe: number | null; forwardPs: number | null;
}
export interface FutuEarningsExpectation {
  fiscalPeriod: string; periodType: string; periodStart: string | null; periodEnd: string | null;
  currency: string | null; eps: number | null; revenue: number | null;
  observationDate: string; observedAt: string; source: string;
}
export interface ForwardMetrics {
  valuationDate: string;
  targetFiscalPeriod: string | null;
  forecastObservationDate: string | null;
  forecastCurrency: string | null;
  forecastSource?: string | null;
  forwardPe: MetricResult<number>;
  forwardPs: MetricResult<number>;
  forwardPeg: MetricResult<number>;
  forwardPegBasis: string | null;
  impliedEpsGrowth: MetricResult<number>;
  revisionFiscalPeriod: string | null;
  revisionPeriodType: string | null;
  revisionCurrency?: string | null;
  revisionSource?: string | null;
  revisionObservationDate: string | null;
  revisionBaseDate30d: string | null;
  revisionBaseDate90d: string | null;
  revenueRevisionBaseDate30d?: string | null;
  revenueRevisionBaseDate90d?: string | null;
  epsRevision30dBasis?: string | null;
  epsRevision90dBasis?: string | null;
  epsRevision30d: MetricResult<number>;
  epsRevision90d: MetricResult<number>;
  revenueRevision30d: MetricResult<number>;
  revenueRevision90d: MetricResult<number>;
}
export interface FutuObservations {
  valuation: FutuValuationObservation | null;
  earnings: FutuEarningsExpectation[];
  forward?: ForwardMetrics | null;
}

export interface PeerRelativeStrength {
  peerBasis: "INDUSTRY_MEDIAN" | "CUSTOM_PEERS_MEDIAN" | string;
  industryCode?: string | null;
  industryName?: string | null;
  tradeDate?: string | null;
  stockReturn20d?: number | null;
  peerReturn20d?: number | null;
  spyReturn20d?: number | null;
  stockVsPeer20d?: number | null;
  peerVsSpy20d?: number | null;
  peerSampleCount20d?: number | null;
  stockReturn60d?: number | null;
  peerReturn60d?: number | null;
  spyReturn60d?: number | null;
  stockVsPeer60d?: number | null;
  peerVsSpy60d?: number | null;
  peerSampleCount60d?: number | null;
  sectorCmf20d?: number | null;
  sectorFlowState?: string | null;
  breadthAboveSma50Pct?: number | null;
  breadthAboveSma200Pct?: number | null;
  relativeLabelCode?: string | null;
}

export interface StockAnalysisReport {
  figi: string;
  symbol: string;
  name: string;
  valuationDate: string;
  valuation: ValuationResult | null;
  financials: Financials | null;
  history: ValuationResult[];
  priceStructure: MetricResult<PriceStructure>;
  supportResistance: MetricResult<SupportResistance>;
  marketContext: MetricResult<MarketContext>;
  futuObservations: FutuObservations | null;
  tradePlans: MetricResult<TradePlanResult>;
  reasons: AnalysisReason[];
  fundamentals?: FundamentalSnapshot | null;
  summary?: MetricResult<InvestmentSummary> | null;
  peerRelativeStrength?: PeerRelativeStrength | null;
}