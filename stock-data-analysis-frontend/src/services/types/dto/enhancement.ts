export interface CreditSpreadSummary {
  seriesId: string;
  oasPct: number | null;
  observationDate: string | null;
  prior20MeanPct: number | null;
  deltaVsPrior20Bps: number | null;
  status: string;
  macroCautionTriggered: boolean;
  stale: boolean;
  observedAt: string | null;
}

export interface OptionsSummary {
  figi: string;
  symbol: string;
  snapshotDate: string | null;
  source: string | null;
  feed: string | null;
  status: string;
  reasonCode: string | null;
  spotPrice: number | null;
  quoteTimestamp: string | null;
  nextEarningsDate: string | null;
  daysToNextEarnings: number | null;
  straddleExpiration: string | null;
  atmStrike: number | null;
  atmCallPrice: number | null;
  atmPutPrice: number | null;
  impliedMovePct: number | null;
  impliedMoveStatus: string;
  impliedMoveReason: string | null;
  protectiveStopDistancePct: number | null;
  earningsGapExceedsStopRisk: boolean;
  atmIv: number | null;
  hv20: number | null;
  ivToHvRatio: number | null;
  ivHistorySampleCount: number | null;
  ivShortWindowPercentile: number | null;
  putCallOiRatio: number | null;
  putCallVolRatio: number | null;
  otmPutIv: number | null;
  otmCallIv: number | null;
  ivSkewPutMinusCall: number | null;
  oiWallExpiration: string | null;
  maxCallOiStrike: number | null;
  maxCallOi: number | null;
  maxPutOiStrike: number | null;
  maxPutOi: number | null;
  totalCallOi: number | null;
  totalPutOi: number | null;
  openInterestDate: string | null;
  volumeSessionDate: string | null;
  nonDirectionalDisclaimerCode: string;
  observedAt: string | null;
}

export interface Form4Transaction {
  id: number | null;
  accession: string;
  formType: string;
  filingDate: string;
  acceptedAt: string | null;
  transactionDate: string | null;
  securityTitle: string | null;
  primaryOwnerName: string;
  primaryOwnerTitle: string | null;
  reportingOwnerCount: number;
  officer: boolean;
  director: boolean;
  tenPercentOwner: boolean;
  transactionCode: string;
  acquiredDisposed: string | null;
  aff10b5One: boolean | null;
  shares: number | null;
  pricePerShare: number | null;
  transactionValueUsd: number | null;
  sharesOwnedAfter: number | null;
  openMarketBuyCodeP: boolean;
  routineOrPlanTrade: boolean;
  filingUrl: string | null;
}

export interface InsiderSummary {
  status: string;
  reasonCode: string | null;
  cik: string | null;
  insiderBuyUndervaluedConfirm: boolean;
  codePBuyCount60d: number;
  codePDistinctInsiders60d: number;
  codePTotalValueUsd60d: number | null;
  routineOrSaleCount60d: number;
  latestFilingDate: string | null;
  recentTransactions: Form4Transaction[];
}

export interface Institutional13fRow {
  managerCik: string;
  managerName: string;
  reportPeriod: string;
  filingDate: string;
  formType: string;
  cusip: string;
  shareType: string;
  putCall: string;
  shares: number;
  prevShares: number | null;
  shareChangePct: number | null;
  marketValueUsd: number;
  actionType: string;
}

export interface Institutional13fSummary {
  status: string;
  reasonCode: string | null;
  latestReportPeriod: string | null;
  trackedManagersHoldingCount: number;
  managersAddedOrNewCount: number;
  managersReducedOrExitedCount: number;
  holdings: Institutional13fRow[];
}

export interface ShortInterestSummary {
  status: string;
  reasonCode: string | null;
  settlementDate: string | null;
  shortInterestShares: number | null;
  shortPctOfSharesOutstanding: number | null;
  shortPctStatus: string;
  shortPctReason: string | null;
  avgDailyVolumeSource: number | null;
  daysToCoverSource: number | null;
  daysToCover20d: number | null;
  avgVolume20dAsOf: string | null;
  shortChangeVsPriorPct: number | null;
  signalCode: string;
  source: string | null;
  observedAt: string | null;
}

export interface StockNewsEvent {
  id: number | null;
  sourceType: string;
  externalId: string;
  publisher: string | null;
  headline: string;
  summarySnippet: string | null;
  url: string | null;
  publishedAt: string;
  secForm: string | null;
  secItems: string | null;
  exhibit991Url: string | null;
  exhibitExtractionStatus: string | null;
}

export interface GuidanceExtract {
  periodLabel: string | null;
  action: string | null;
  basis: string | null;
  currency: string | null;
  unitScale: string | null;
  revenueLow: number | null;
  revenueHigh: number | null;
  epsLow: number | null;
  epsHigh: number | null;
  sourceFilingUrl: string | null;
  quoteExcerpt: string | null;
}

export interface EventAiSummary {
  id: number | null;
  triggerDate: string;
  triggerReason: string;
  status: string;
  aiProvider: string;
  modelName: string | null;
  eventNature: string | null;
  bulletPoints: string[];
  guidance: GuidanceExtract | null;
  sourceUrls: string[];
  inputFingerprint: string;
  errorMessage: string | null;
  createdAt: string;
}

export interface ManualPrompt {
  taskType: string;
  inputFingerprint: string;
  promptText: string;
  expectedJsonSchema: string;
  sourceItemCount: number;
}

export interface CustomTagCandidate {
  sourceType: string;
  taxonomy: string;
  customTag: string;
  unit: string;
  labelEn: string | null;
  latestValue: number | null;
  latestPeriodEnd: string | null;
  sampleForm: string | null;
  alreadyMapped: boolean;
  existingTargetConcept: string | null;
}

export interface CustomTagProposalItem {
  sourceType: string;
  taxonomy: string;
  customTag: string;
  unit: string;
  labelEn: string | null;
  targetConcept: string;
  signMultiplier: number;
  rationale: string | null;
}

export interface CustomTagProposalPreview {
  cik: string;
  figi: string;
  symbol: string;
  provider: string;
  modelName: string | null;
  persistedToDatabase: boolean;
  allowedTargetConcepts: string[];
  proposals: CustomTagProposalItem[];
}

export interface CustomTagMapping {
  id: number;
  cik: string;
  sourceType: string;
  taxonomy: string;
  customTag: string;
  unit: string;
  labelEn: string | null;
  targetConcept: string;
  signMultiplier: number;
  status: string;
  proposalProvider: string;
  rationale: string | null;
  confirmedAt: string;
  updatedAt: string;
}

export interface CustomTagConfirmItemRequest {
  sourceType: string;
  taxonomy: string;
  customTag: string;
  unit: string;
  targetConcept: string;
  signMultiplier: number;
  rationale?: string | null;
}

export interface CustomTagConfirmResult {
  cik: string;
  confirmedMappingCount: number;
  backfilledFactCount: number;
  rebuiltStockCount: number;
  mappings: CustomTagMapping[];
}

export interface AiChannelStatus {
  provider: string;
  automatedAvailable: boolean;
  activeModel: string | null;
  reasoningEffort: string | null;
  manualModeAlwaysAvailable: boolean;
}

export interface StockEnhancementOverview {
  figi: string;
  symbol: string;
  stockType: string | null;
  cik: string | null;
  etf: boolean;
  aiChannel: AiChannelStatus;
  creditSpread: CreditSpreadSummary | null;
  options: OptionsSummary | null;
  insiders: InsiderSummary | null;
  institutional13f: Institutional13fSummary | null;
  shortInterest: ShortInterestSummary | null;
  recentNewsEvents: StockNewsEvent[];
  eventSummaries: EventAiSummary[];
  confirmedCustomTags: CustomTagMapping[];
  allowedCustomTagConcepts: string[];
}

export interface ReportEnhancementHighlights {
  creditSpread: CreditSpreadSummary | null;
  options: OptionsSummary | null;
  insiderBuyUndervaluedConfirm: boolean;
  codePBuyCount60d: number;
  codePDistinctInsiders60d: number;
  codePTotalValueUsd60d: number | null;
  shortInterest: ShortInterestSummary | null;
}
