import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next, { i18n as I18nInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import zhHK from "../locales/zh-HK.json";
import zhCN from "../locales/zh-CN.json";
import en from "../locales/en.json";
import { DcfValuationPanel } from "./DcfValuationPanel";
import { FundamentalsPanel } from "./FundamentalsPanel";
import { StockAnalysisPage } from "./StockAnalysis";
import { StockScreenerPage } from "./StockScreener";
import { apiClient } from "../services/apiClient";
import { DcfValuation, FundamentalSnapshot, StockAnalysisReport } from "../services/types/dto/analysis";

let mockFigiParam = "FIGI_QQQ";
const mockNavigate = jest.fn();

jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ figi: mockFigiParam }),
}), { virtual: true });

jest.mock("../services/apiClient", () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock("../services/portfolioApi", () => ({
  fetchPortfolioOverview: jest.fn().mockResolvedValue({ items: [] }),
  PORTFOLIO_UPDATED_EVENT: "portfolio-updated",
}));

jest.mock("./TechnicalChartWorkbench", () => ({
  InteractivePriceWorkbench: () => <div data-testid="mock-workbench">MockWorkbench</div>,
  DecisionSnapshotStrip: () => <div data-testid="mock-snapshot">MockSnapshot</div>,
  ActionableLevelsAndPlansPanel: () => <div data-testid="mock-plans">MockPlans</div>,
}));

jest.mock("./ValuationPanels", () => ({
  FinancialsPanel: () => <div data-testid="mock-financials">MockFinancials</div>,
  ValuationHistoryPanel: () => <div data-testid="mock-history">MockHistory</div>,
}));

jest.mock("./FutuObservationPanels", () => ({
  FutuObservationPanels: () => <div data-testid="mock-futu">MockFutu</div>,
}));

jest.mock("./IndustryValuationProposalPanel", () => ({
  IndustryValuationProposalPanel: () => <div data-testid="mock-proposal">MockProposal</div>,
}));

const mockedApi = apiClient as jest.Mocked<typeof apiClient>;

async function createRealI18n(lng: "zh-HK" | "zh-CN" | "en"): Promise<I18nInstance> {
  const inst = i18next.createInstance();
  await inst.use(initReactI18next).init({
    lng,
    fallbackLng: "zh-HK",
    interpolation: { escapeValue: false },
    resources: {
      "zh-HK": { translation: zhHK },
      "zh-CN": { translation: zhCN },
      en: { translation: en },
    },
  });
  return inst;
}

function collectLeaves(obj: Record<string, unknown>, prefix = ""): string[] {
  const keys: string[] = [];
  for (const [k, v] of Object.entries(obj)) {
    const p = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) {
      keys.push(...collectLeaves(v as Record<string, unknown>, p));
    } else {
      keys.push(p);
    }
  }
  return keys.sort();
}

const avail = (value: number) => ({ status: "AVAILABLE" as const, value, reasons: [] });

describe("Batch 05: Real tri-lingual i18n, DCF/Reverse DCF, Financial Quality, ETF & ADR UI", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFigiParam = "FIGI_QQQ";
  });

  test("tri-lingual locales (zh-HK, zh-CN, en) have 100% key parity across all Batch 05 sections", async () => {
    const sections = [
      ["stockScreener"],
      ["analysisReport", "etf"],
      ["analysisReport", "fxBanner"],
      ["analysisReport", "dcf"],
      ["analysisReport", "fundamentals"],
      ["analysisReport", "executiveSummary", "verdicts"],
      ["analysisReport", "executiveSummary", "backing"],
      ["analysisReport", "executiveSummary", "stances"],
      ["analysisReport", "executiveSummary", "anchorSources"],
      ["analysisReport", "executiveSummary", "risks"],
      ["analysisReport", "reasons"],
    ];

    const getPath = (root: Record<string, unknown>, pathSegs: string[]): Record<string, unknown> => {
      let cur: unknown = root;
      for (const seg of pathSegs) {
        cur = (cur as Record<string, unknown>)[seg];
      }
      return cur as Record<string, unknown>;
    };

    const hkInst = await createRealI18n("zh-HK");
    const cnInst = await createRealI18n("zh-CN");
    const enInst = await createRealI18n("en");

    for (const segs of sections) {
      const prefix = segs.join(".");
      const hkKeys = collectLeaves(getPath(zhHK as unknown as Record<string, unknown>, segs), prefix);
      const cnKeys = collectLeaves(getPath(zhCN as unknown as Record<string, unknown>, segs), prefix);
      const enKeys = collectLeaves(getPath(en as unknown as Record<string, unknown>, segs), prefix);

      expect(cnKeys).toEqual(hkKeys);
      expect(enKeys).toEqual(hkKeys);

      for (const fullKey of hkKeys) {
        expect(hkInst.exists(fullKey)).toBe(true);
        expect(cnInst.exists(fullKey)).toBe(true);
        expect(enInst.exists(fullKey)).toBe(true);
        expect(String(hkInst.t(fullKey)).trim().length).toBeGreaterThan(0);
        expect(String(cnInst.t(fullKey)).trim().length).toBeGreaterThan(0);
        expect(String(enInst.t(fullKey)).trim().length).toBeGreaterThan(0);
      }
    }
  });

  test("DcfValuationPanel switches Standard vs SBC-Adjusted FCF, shows Reverse DCF stretched warning, and degrades gracefully", async () => {
    const inst = await createRealI18n("zh-HK");
    const dcf: DcfValuation = {
      status: "AVAILABLE",
      reasonCode: null,
      methodologyNote: "Standalone two-stage DCF",
      valuationDate: "2026-11-19",
      currency: "USD",
      currentPrice: 200,
      marketCapUsd: 200000000000,
      sharesAtDay: 1000000000,
      riskFreeRate: 0.045,
      riskFreeRateDate: "2026-11-18",
      equityRiskPremium: 0.05,
      discountRate: 0.095,
      terminalGrowthRate: 0.025,
      historicalRevenueCagr3y: 0.14,
      historicalFcfCagr3y: 0.12,
      forwardExpectedGrowth: null,
      growthBasisNote: null,
      standardFcf: {
        basis: "STANDARD_FCF",
        status: "AVAILABLE",
        reasonCode: null,
        baseFcfUsd: 10000000000,
        baseStage1GrowthRate: 0.12,
        conservative: { stage1GrowthRate: 0.09, intrinsicEquityValue: 170000000000, intrinsicPricePerShare: 170, upsideDownsidePct: -0.15 },
        base: { stage1GrowthRate: 0.12, intrinsicEquityValue: 210000000000, intrinsicPricePerShare: 210, upsideDownsidePct: 0.05 },
        optimistic: { stage1GrowthRate: 0.15, intrinsicEquityValue: 255000000000, intrinsicPricePerShare: 255, upsideDownsidePct: 0.275 },
        reverseDcf: {
          status: "AVAILABLE",
          reasonCode: null,
          impliedStage1GrowthRate: 0.195,
          benchmarkGrowthRate: 0.12,
          benchmarkGrowthSource: "HISTORICAL_FCF_CAGR_3Y",
          expectationGapPpt: 0.075,
          expectationStretched: true,
        },
      },
      sbcAdjustedFcf: {
        basis: "SBC_ADJUSTED_FCF",
        status: "NOT_APPLICABLE",
        reasonCode: "NON_POSITIVE_ADJ_FCF",
        baseFcfUsd: -500000000,
        baseStage1GrowthRate: 0.12,
        conservative: null,
        base: null,
        optimistic: null,
        reverseDcf: null,
      },
    };

    render(
      <I18nextProvider i18n={inst}>
        <DcfValuationPanel dcf={dcf} />
      </I18nextProvider>
    );

    expect(screen.getByText(/獨立模型（不併入 \[L, M, H\]）/)).toBeInTheDocument();
    expect(screen.getByText(/210\.00 USD/)).toBeInTheDocument();
    expect(screen.getByText("市場預期過度樂觀")).toBeInTheDocument();
    expect(screen.getByText("+19.50%")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "扣 SBC 實質 FCF" }));
    expect(screen.getByText(/扣 SBC 後實質自由現金流非正/)).toBeInTheDocument();
  });

  test("DcfValuationPanel English unavailable state has no Traditional Chinese leak and missing benchmark shows neutral hint", async () => {
    const enInst = await createRealI18n("en");
    const unavailableDcf: DcfValuation = {
      status: "NOT_APPLICABLE",
      reasonCode: "DCF_NON_POSITIVE_FCF",
      methodologyNote: null,
      valuationDate: "2026-11-19",
      currency: "USD",
      currentPrice: 120,
      marketCapUsd: 12000000000,
      sharesAtDay: 100000000,
      riskFreeRate: null,
      riskFreeRateDate: null,
      equityRiskPremium: null,
      discountRate: null,
      terminalGrowthRate: null,
      historicalRevenueCagr3y: null,
      historicalFcfCagr3y: null,
      forwardExpectedGrowth: null,
      growthBasisNote: null,
      standardFcf: null,
      sbcAdjustedFcf: null,
    };

    const { rerender } = render(
      <I18nextProvider i18n={enInst}>
        <DcfValuationPanel dcf={unavailableDcf} />
      </I18nextProvider>
    );
    const alertText = screen.getByRole("alert").textContent ?? "";
    expect(alertText).toContain("Not applicable");
    expect(alertText).not.toMatch(/[\u4e00-\u9fff]/);

    const noBenchmarkDcf: DcfValuation = {
      ...unavailableDcf,
      status: "AVAILABLE",
      reasonCode: null,
      riskFreeRate: 0.045,
      riskFreeRateDate: "2026-11-18",
      equityRiskPremium: 0.05,
      discountRate: 0.095,
      terminalGrowthRate: 0.025,
      standardFcf: {
        basis: "STANDARD_FCF",
        status: "AVAILABLE",
        reasonCode: null,
        baseFcfUsd: 500000000,
        baseStage1GrowthRate: 0.08,
        conservative: { stage1GrowthRate: 0.05, intrinsicEquityValue: 8000000000, intrinsicPricePerShare: 80, upsideDownsidePct: -0.33 },
        base: { stage1GrowthRate: 0.08, intrinsicEquityValue: 9500000000, intrinsicPricePerShare: 95, upsideDownsidePct: -0.21 },
        optimistic: { stage1GrowthRate: 0.11, intrinsicEquityValue: 11200000000, intrinsicPricePerShare: 112, upsideDownsidePct: -0.07 },
        reverseDcf: {
          status: "AVAILABLE",
          reasonCode: null,
          impliedStage1GrowthRate: 0.13,
          benchmarkGrowthRate: null,
          benchmarkGrowthSource: null,
          expectationGapPpt: null,
          expectationStretched: false,
        },
      },
      sbcAdjustedFcf: null,
    };
    rerender(
      <I18nextProvider i18n={enInst}>
        <DcfValuationPanel dcf={noBenchmarkDcf} />
      </I18nextProvider>
    );
    expect(screen.getByText(/No comparable historical or forward growth benchmark is available yet/)).toBeInTheDocument();
  });

  test("FundamentalsPanel renders Batch 05 quality metrics (SBC, Adj FCF, ROIC, Margin Compression, Interest Coverage)", async () => {
    const inst = await createRealI18n("en");
    const snapshot: FundamentalSnapshot = {
      periodEnd: "2026-09-30",
      priorYearPeriodEnd: "2025-09-30",
      threeYearAgoPeriodEnd: "2023-09-30",
      reportingCurrency: "USD",
      financialInstitution: false,
      revenueGrowthYoy: avail(0.25),
      epsGrowthYoy: avail(0.18),
      fcfGrowthYoy: avail(0.22),
      revenueCagr3y: avail(0.20),
      epsCagr3y: avail(0.15),
      trailingPeg: avail(1.8),
      pegGrowthBasis: "TRAILING_EPS_CAGR_3Y",
      roe: { status: "NOT_APPLICABLE", value: null, reasons: [{ code: "NON_POSITIVE_COMMON_EQUITY", component: "FUNDAMENTALS", message: "Negative equity" }] },
      fcfMargin: avail(0.20),
      fcfConversion: avail(1.1),
      netDebtToEbitda: avail(1.5),
      fcfYield: avail(0.04),
      earningsYield: avail(0.035),
      netShareChangeYoy: avail(-0.02),
      fcfCagr3y: avail(0.16),
      sbcTtm: avail(0),
      sbcToRevenue: avail(0),
      adjFcfTtm: avail(-500000000),
      pToAdjFcf: avail(42.5),
      totalShareholderYield: avail(0.032),
      grossMargin: avail(0.76),
      grossMarginYoyPpt: avail(-0.025),
      grossMarginQoqPpt: avail(-0.012),
      operatingMargin: avail(0.18),
      operatingMarginYoyPpt: avail(-0.015),
      operatingMarginQoqPpt: avail(-0.008),
      marginCompressionConsecutive: true,
      evToSales: avail(6.2),
      evToGrossProfit: avail(8.15),
      ruleOf40: avail(0.45),
      nopat: avail(1500000000),
      investedCapital: avail(8000000000),
      roic: avail(0.1875),
      tangibleBookValue: avail(-2500000000),
      pToTbv: avail(7.5),
      interestCoverage: avail(2.4),
      shortTermLiquidityCoverage: avail(3.8),
    };

    render(
      <I18nextProvider i18n={inst}>
        <FundamentalsPanel fundamentals={snapshot} />
      </I18nextProvider>
    );

    expect(screen.getByText("2-Quarter Consecutive Margin Compression")).toBeInTheDocument();
    expect(screen.getByText("Return on Invested Capital (ROIC)")).toBeInTheDocument();
    expect(screen.getByText("18.75%")).toBeInTheDocument();
    expect(screen.getByText("42.50x")).toBeInTheDocument();
    expect(screen.getByText("2.40x")).toBeInTheDocument();
    // 支援負值與零金額顯示（不可誤判為 "—"）
    expect(screen.getByText("$0")).toBeInTheDocument();
    expect(screen.getByText("-$500.0M")).toBeInTheDocument();
    expect(screen.getByText("-$2.50B")).toBeInTheDocument();
  });

  test("StockAnalysisPage renders ETF technical-only view and ADR FX conversion banner accurately", async () => {
    const inst = await createRealI18n("zh-HK");
    const etfReport: StockAnalysisReport = {
      figi: "FIGI_QQQ",
      symbol: "QQQ",
      name: "Invesco QQQ Trust",
      stockType: "ETF",
      valuationDate: "2026-11-19",
      valuation: {
        figi: "FIGI_QQQ",
        valuationDate: "2026-11-19",
        analysisAsOf: "2026-11-19T21:00:00Z",
        calculatedAt: "2026-11-19T21:05:00Z",
        currency: "USD",
        price: 512.3,
        industryCode: null,
        modelCode: null,
        primaryMetric: null,
        status: "NOT_APPLICABLE",
        reasonCode: "NOT_APPLICABLE_FOR_ETF",
        metrics: {
          pe: { value: null, status: "NOT_APPLICABLE", reasonCode: "NOT_APPLICABLE_FOR_ETF" },
          pb: { value: null, status: "NOT_APPLICABLE", reasonCode: "NOT_APPLICABLE_FOR_ETF" },
          ps: { value: null, status: "NOT_APPLICABLE", reasonCode: "NOT_APPLICABLE_FOR_ETF" },
          pfcf: { value: null, status: "NOT_APPLICABLE", reasonCode: "NOT_APPLICABLE_FOR_ETF" },
          evEbitda: { value: null, status: "NOT_APPLICABLE", reasonCode: "NOT_APPLICABLE_FOR_ETF" },
        },
        historicalReference: null,
        peerReference: null,
        industryAssumption: null,
      },
      financials: null,
      history: [],
      priceStructure: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      supportResistance: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      marketContext: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      futuObservations: null,
      tradePlans: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      reasons: [],
      fundamentals: null,
      dcf: {
        status: "NOT_APPLICABLE",
        reasonCode: "NOT_APPLICABLE_FOR_ETF",
        methodologyNote: null,
        valuationDate: "2026-11-19",
        currency: "USD",
        currentPrice: 512.3,
        marketCapUsd: null,
        sharesAtDay: null,
        riskFreeRate: null,
        riskFreeRateDate: null,
        equityRiskPremium: null,
        discountRate: null,
        terminalGrowthRate: null,
        historicalRevenueCagr3y: null,
        historicalFcfCagr3y: null,
        forwardExpectedGrowth: null,
        growthBasisNote: null,
        standardFcf: null,
        sbcAdjustedFcf: null,
      },
      summary: {
        status: "AVAILABLE",
        value: {
          verdict: "NOT_APPLICABLE_FOR_ETF",
          fundamentalBacking: "NOT_APPLICABLE",
          stance: "ETF_TECHNICAL_BULLISH_SUPPORT",
          primaryMetric: null,
          anchorSource: "ETF_TECHNICAL_ONLY",
          fairValue: null,
          discountToMid: null,
          upsideToMid: null,
          yieldSpreadVs10y: null,
          yieldSpreadBasis: null,
          technicalSupportZoneId: null,
          technicalSupportRange: null,
          technicalSupportTimeframe: null,
          technicalSupportStrength: null,
          weeklyTrend: "UPTREND",
          aboveSma200: true,
          entryPermission: "ALLOW",
          eventGateBlocked: false,
          nextBlockingEventDate: null,
          risks: [],
          watchItems: [],
          reasons: [],
        },
        reasons: [],
      },
    };
    mockedApi.post.mockResolvedValueOnce({ data: etfReport } as never);

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <I18nextProvider i18n={inst}>
        <QueryClientProvider client={qc}>
          <StockAnalysisPage />
        </QueryClientProvider>
      </I18nextProvider>
    );

    await waitFor(() => expect(screen.getByText(/QQQ · ETF 技術與資金分析/)).toBeInTheDocument());
    expect(screen.getByTestId("mock-workbench")).toBeInTheDocument();
    expect(screen.getByTestId("mock-plans")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-financials")).not.toBeInTheDocument();
    expect(screen.queryByText(/兩階段 DCF 與 Reverse DCF/)).not.toBeInTheDocument();
  });

  test("StockAnalysisPage renders USD-reporting verified ADR banner without false FX conversion or FRED text", async () => {
    const inst = await createRealI18n("zh-HK");
    mockFigiParam = "FIGI_ARM";
    const armReport: StockAnalysisReport = {
      figi: "FIGI_ARM",
      symbol: "ARM",
      name: "Arm Holdings plc",
      stockType: "ADR",
      valuationDate: "2026-11-19",
      valuation: {
        figi: "FIGI_ARM",
        valuationDate: "2026-11-19",
        analysisAsOf: "2026-11-19T21:00:00Z",
        calculatedAt: "2026-11-19T21:05:00Z",
        currency: "USD",
        price: 150,
        industryCode: "SEMI",
        modelCode: "GROWTH",
        primaryMetric: "PE",
        status: "AVAILABLE",
        reasonCode: null,
        metrics: {
          pe: { value: 80, status: "AVAILABLE", reasonCode: null },
          pb: { value: 20, status: "AVAILABLE", reasonCode: null },
          ps: { value: 30, status: "AVAILABLE", reasonCode: null },
          pfcf: { value: 90, status: "AVAILABLE", reasonCode: null },
          evEbitda: { value: 65, status: "AVAILABLE", reasonCode: null },
        },
        historicalReference: null,
        peerReference: null,
        industryAssumption: null,
        fxConversion: {
          converted: false,
          reportingCurrency: "USD",
          targetCurrency: "USD",
          usdPerUnit: 1,
          rateDate: null,
          fxSource: null,
          adr: true,
          adrRatio: 1,
          adrRatioVerified: true,
          underlyingSymbol: "ARM",
        },
      },
      financials: null,
      history: [],
      priceStructure: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      supportResistance: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      marketContext: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      futuObservations: null,
      tradePlans: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
      reasons: [],
      fundamentals: null,
      dcf: null,
      summary: { status: "INSUFFICIENT_DATA", value: null, reasons: [] },
    };
    mockedApi.post.mockResolvedValueOnce({ data: armReport } as never);

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <I18nextProvider i18n={inst}>
        <QueryClientProvider client={qc}>
          <StockAnalysisPage />
        </QueryClientProvider>
      </I18nextProvider>
    );

    const notice = await screen.findByText(/無須匯率換算/);
    expect(notice.textContent).toContain("1 ADR = 1 原股");
    expect(notice.textContent).not.toContain("FRED");
    expect(notice.textContent).not.toContain("已按官方匯率換算");
  });

  test("StockScreenerPage switches assetType to ETF using backend-provided assetTypes", async () => {
    const inst = await createRealI18n("zh-HK");
    mockedApi.post.mockResolvedValueOnce({
      data: {
        classifications: [],
        concepts: [],
        assetTypes: ["STOCK", "ETF", "ALL"],
        marketCapTiers: ["MEGA_LARGE"],
        verdicts: ["DEEP_UNDERVALUED"],
        sortFields: ["symbol", "marketCap"],
      },
    } as never);
    mockedApi.get
      .mockResolvedValueOnce({ data: { items: [], total: 0, page: 0, size: 50 } } as never)
      .mockResolvedValueOnce({
        data: {
          items: [{
            figi: "FIGI_QQQ",
            symbol: "QQQ",
            name: "Invesco QQQ Trust",
            stockType: "ETF",
            industryCode: null,
            industry: null,
            concepts: [],
            quoteDate: "2026-11-19",
            closePrice: 512.3,
            currency: "USD",
            valuationDate: null,
            pe: null,
            pb: null,
            ps: null,
            pfcf: null,
            evEbitda: null,
            status: "NOT_APPLICABLE",
            reasonCode: "NOT_APPLICABLE_FOR_ETF",
          }],
          total: 1,
          page: 0,
          size: 50,
        },
      } as never);

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <I18nextProvider i18n={inst}>
        <QueryClientProvider client={qc}>
          <StockScreenerPage />
        </QueryClientProvider>
      </I18nextProvider>
    );

    const etfToggle = await screen.findByRole("button", { name: "ETF／ETP" });
    fireEvent.click(etfToggle);

    await waitFor(() => expect(screen.getByText("QQQ")).toBeInTheDocument());
    expect(screen.getByText("ETF 技術分析")).toBeInTheDocument();
    expect(mockedApi.get).toHaveBeenLastCalledWith(
      expect.any(String),
      expect.objectContaining({ params: expect.objectContaining({ assetType: "ETF" }) })
    );
  });
});
