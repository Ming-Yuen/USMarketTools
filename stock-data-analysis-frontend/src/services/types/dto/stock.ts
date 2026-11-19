export interface StockConcept { code: string; name: string; }

export interface StockClassification {
  code: string;
  industry: string;
  sourcePlateCode: string | null;
}

export interface StockClassificationResponse {
  classifications: StockClassification[];
  concepts: StockConcept[];
  assetTypes?: string[];
  marketCapTiers?: string[];
  verdicts?: string[];
  sortFields?: string[];
}

export interface StockScreenItem {
  figi: string;
  symbol: string;
  name: string;
  stockType: string;
  industryCode: string | null;
  industry: string | null;
  concepts: StockConcept[];
  quoteDate: string | null;
  closePrice: number | null;
  currency?: string | null;
  valuationDate: string | null;
  marketCap?: number | null;
  marketCapTier?: string | null;
  pe: number | null;
  pb: number | null;
  ps: number | null;
  pfcf: number | null;
  evEbitda: number | null;
  revenueGrowthYoy?: number | null;
  roe?: number | null;
  fcfMargin?: number | null;
  historicalPercentile?: number | null;
  primaryMetric?: string | null;
  verdict?: string | null;
  status: string | null;
  reasonCode: string | null;
  issuerKey?: string | null;
}

export interface StockScreenResponse {
  items: StockScreenItem[];
  total: number;
  page: number;
  size: number;
}
