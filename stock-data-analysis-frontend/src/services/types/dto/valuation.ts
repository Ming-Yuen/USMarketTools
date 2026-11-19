export interface ValuationMetric {
  value: number | null;
  status: string;
  reasonCode: string | null;
  financialSource?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
}

export interface ValuationReference {
  kind: "HISTORICAL_MARKET" | "PEER_MARKET" | "INDUSTRY_ASSUMPTION";
  metricCode: string | null;
  status: string;
  reasonCode: string | null;
  multipleLow: number | null;
  multipleMedian: number | null;
  multipleHigh: number | null;
  priceLow: number | null;
  priceHigh: number | null;
  sampleCount: number | null;
  windowSampleCount?: number | null;
  excludedSampleCount?: number | null;
  sampleStartDate?: string | null;
  sampleEndDate?: string | null;
  currentMultiple?: number | null;
  currentPercentile?: number | null;
  priceMedian?: number | null;
  peerScope?: "CUSTOM_PEERS" | "SAME_TIER" | "ADJACENT_TIER" | "ALL_INDUSTRY" | string | null;
  targetMarketCapTier?: "MEGA_LARGE" | "MID" | "SMALL" | string | null;
  peerFallbackReason?: string | null;
  industryAllMetricCode?: string | null;
  industryAllMedianMultiple?: number | null;
  industryAllSampleCount?: number | null;
  peerRevenueGrowthYoyMedian?: number | null;
  peerRevenueGrowthCount?: number | null;
  peerRoeMedian?: number | null;
  peerRoeCount?: number | null;
  peerFcfMarginMedian?: number | null;
  peerFcfMarginCount?: number | null;
  warningCodes?: string[];
}

export interface CustomPeerItem {
  figi: string;
  symbol: string;
  name: string | null;
  industryCode: string | null;
  active: boolean;
  tracked: boolean;
  valuationDate: string | null;
  currency: string | null;
  marketCap: number | null;
  marketCapTier: string | null;
  issuerKey?: string | null;
  pe?: number | null;
  pb?: number | null;
  ps?: number | null;
  pfcf?: number | null;
  evEbitda?: number | null;
}

export interface CustomPeerGroup {
  figi: string;
  preferredMetric: string | null;
  supportedMetrics: string[];
  peers: CustomPeerItem[];
  targetMarketCap?: number | null;
  targetCurrency?: string | null;
  targetValuationDate?: string | null;
  targetPrimaryMetric?: string | null;
  targetIssuerKey?: string | null;
}

export interface ValuationResult {
  figi: string;
  valuationDate: string;
  analysisAsOf: string;
  calculatedAt: string;
  financialObservedAt?: string | null;
  sharesObservedAt?: string | null;
  marketCapSource?: string | null;
  secFinancialAvailableFromDate?: string | null;
  currency: string | null;
  price: number | null;
  industryCode: string | null;
  modelCode: string | null;
  primaryMetric: string | null;
  status: string;
  reasonCode: string | null;
  metrics: { pe: ValuationMetric; pb: ValuationMetric; ps: ValuationMetric; pfcf: ValuationMetric; evEbitda: ValuationMetric };
  historicalReference: ValuationReference | null;
  peerReference: ValuationReference | null;
  industryAssumption: ValuationReference | null;
}

export interface FinancialValue {
  value: number | null;
  status: string;
  reasonCode: string | null;
  calculation: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  availableAt: string | null;
}

export interface Financials {
  periodEnd: string | null;
  availableAt: string | null;
  reportingCurrency: string | null;
  revenueTtm: FinancialValue;
  netIncomeCommonTtm: FinancialValue;
  epsTtm: FinancialValue;
  operatingCashFlowTtm: FinancialValue;
  capitalExpenditureTtm: FinancialValue;
  freeCashFlowTtm: FinancialValue;
  ebitdaTtm: FinancialValue;
  commonEquity: FinancialValue;
  sharesOutstanding: FinancialValue;
  bookValuePerShare: FinancialValue;
  totalDebt: FinancialValue;
  cashLikeAssets: FinancialValue;
  preferredEquity: FinancialValue;
  noncontrollingInterest: FinancialValue;
}
