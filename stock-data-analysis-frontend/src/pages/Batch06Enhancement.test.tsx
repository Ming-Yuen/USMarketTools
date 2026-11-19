import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next, { i18n as I18nInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import zhHK from "../locales/zh-HK.json";
import zhCN from "../locales/zh-CN.json";
import en from "../locales/en.json";
import { StockEnhancementPanels } from "./StockEnhancementPanels";
import { ExecutiveSummaryPanel } from "./ExecutiveSummaryPanel";
import { formatAlertMessage } from "../components/portfolioFormatters";
import { enhancementApi } from "../services/enhancementApi";
import { OptionsSummary, ShortInterestSummary, StockEnhancementOverview } from "../services/types/dto/enhancement";

jest.mock("../services/apiClient", () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock("../services/enhancementApi", () => ({
  enhancementApi: {
    getOverview: jest.fn(),
    refreshOptions: jest.fn(),
    refreshInsiders: jest.fn(),
    refreshShortInterest: jest.fn(),
    refreshNews: jest.fn(),
    summarizeNewsAutomated: jest.fn(),
    getNewsPrompt: jest.fn(),
    importNewsManual: jest.fn(),
    getCustomTagCandidates: jest.fn(),
    getCustomTagPrompt: jest.fn(),
    proposeCustomTagsAutomated: jest.fn(),
    importCustomTagsManual: jest.fn(),
    confirmCustomTags: jest.fn(),
    disableCustomTag: jest.fn(),
  },
}));

const mockedEnhApi = enhancementApi as jest.Mocked<typeof enhancementApi>;

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

function buildOptions(overrides?: Partial<OptionsSummary>): OptionsSummary {
  return {
    figi: "BBG000B9XRY4",
    symbol: "AAPL",
    snapshotDate: "2026-11-18",
    source: "ALPACA",
    feed: "indicative",
    status: "AVAILABLE",
    reasonCode: null,
    spotPrice: 230.5,
    quoteTimestamp: "2026-11-18T20:00:00Z",
    nextEarningsDate: "2026-11-25",
    daysToNextEarnings: 7,
    straddleExpiration: "2026-11-27",
    atmStrike: 230,
    atmCallPrice: 8.0,
    atmPutPrice: 7.67,
    impliedMovePct: 0.068,
    impliedMoveStatus: "AVAILABLE",
    impliedMoveReason: "EARNINGS_STRADDLE",
    protectiveStopDistancePct: 0.045,
    earningsGapExceedsStopRisk: true,
    atmIv: 0.34,
    hv20: 0.22,
    ivToHvRatio: 1.54,
    ivHistorySampleCount: 12,
    ivShortWindowPercentile: 0.72,
    putCallOiRatio: 0.85,
    putCallVolRatio: 0.92,
    otmPutIv: 0.37,
    otmCallIv: 0.335,
    ivSkewPutMinusCall: 0.035,
    oiWallExpiration: "2026-12-18",
    maxCallOiStrike: 245,
    maxCallOi: 42000,
    maxPutOiStrike: 220,
    maxPutOi: 38000,
    totalCallOi: 200000,
    totalPutOi: 170000,
    openInterestDate: "2026-11-18",
    volumeSessionDate: "2026-11-18",
    nonDirectionalDisclaimerCode: "OI_WALL_NON_DIRECTIONAL_DISCLAIMER",
    observedAt: "2026-11-18T20:05:00Z",
    ...overrides,
  };
}

function buildShortInterest(overrides?: Partial<ShortInterestSummary>): ShortInterestSummary {
  return {
    status: "AVAILABLE",
    reasonCode: null,
    settlementDate: "2026-10-30",
    shortInterestShares: 12000000,
    shortPctOfSharesOutstanding: null,
    shortPctStatus: "INSUFFICIENT_DATA",
    shortPctReason: "ADR_ADS_FLOAT_UNVERIFIED",
    avgDailyVolumeSource: 2666666,
    daysToCoverSource: 4.5,
    daysToCover20d: 4.2,
    avgVolume20dAsOf: "2026-11-18",
    shortChangeVsPriorPct: 0.2,
    signalCode: "SHORT_TRAP_WARNING",
    source: "MASSIVE_FINRA",
    observedAt: "2026-11-18T20:05:00Z",
    ...overrides,
  };
}

function buildOverview(overrides?: Partial<StockEnhancementOverview>): StockEnhancementOverview {
  return {
    figi: "BBG000B9XRY4",
    symbol: "AAPL",
    stockType: "Common Stock",
    cik: "0000320193",
    etf: false,
    aiChannel: {
      provider: "NONE",
      automatedAvailable: false,
      activeModel: null,
      reasoningEffort: "low",
      manualModeAlwaysAvailable: true,
    },
    creditSpread: {
      seriesId: "BAMLH0A0HYM2",
      oasPct: 3.12,
      observationDate: "2026-11-18",
      prior20MeanPct: 3.05,
      deltaVsPrior20Bps: 7.0,
      status: "NORMAL",
      macroCautionTriggered: false,
      stale: false,
      observedAt: "2026-11-18T22:00:00Z",
    },
    options: buildOptions(),
    insiders: {
      status: "AVAILABLE",
      reasonCode: null,
      cik: "0000320193",
      codePBuyCount60d: 2,
      codePDistinctInsiders60d: 2,
      codePTotalValueUsd60d: 450000,
      routineOrSaleCount60d: 3,
      insiderBuyUndervaluedConfirm: true,
      latestFilingDate: "2026-11-10",
      recentTransactions: [
        {
          id: 1,
          accession: "0001-26-000001",
          formType: "4",
          filingDate: "2026-11-10",
          acceptedAt: "2026-11-10T21:00:00Z",
          transactionDate: "2026-11-09",
          securityTitle: "Common Stock",
          primaryOwnerName: "Jane Doe",
          primaryOwnerTitle: "CFO",
          reportingOwnerCount: 1,
          officer: true,
          director: false,
          tenPercentOwner: false,
          transactionCode: "P",
          acquiredDisposed: "A",
          shares: 1000,
          pricePerShare: 225,
          transactionValueUsd: 225000,
          sharesOwnedAfter: 50000,
          aff10b5One: false,
          openMarketBuyCodeP: true,
          routineOrPlanTrade: false,
          filingUrl: "https://www.sec.gov/Archives/edgar/data/320193/0001.xml",
        },
        {
          id: 2,
          accession: "0001-26-000002",
          formType: "4",
          filingDate: "2026-11-05",
          acceptedAt: "2026-11-05T21:00:00Z",
          transactionDate: "2026-11-04",
          securityTitle: "Common Stock",
          primaryOwnerName: "John Smith",
          primaryOwnerTitle: "SVP",
          reportingOwnerCount: 1,
          officer: true,
          director: false,
          tenPercentOwner: false,
          transactionCode: "S",
          acquiredDisposed: "D",
          shares: 500,
          pricePerShare: 228,
          transactionValueUsd: 114000,
          sharesOwnedAfter: 12000,
          aff10b5One: true,
          openMarketBuyCodeP: false,
          routineOrPlanTrade: true,
          filingUrl: "https://www.sec.gov/Archives/edgar/data/320193/0002.xml",
        },
      ],
    },
    institutional13f: {
      status: "AVAILABLE",
      reasonCode: null,
      latestReportPeriod: "2026-09-30",
      trackedManagersHoldingCount: 3,
      managersAddedOrNewCount: 2,
      managersReducedOrExitedCount: 1,
      holdings: [
        {
          managerCik: "0001067983",
          managerName: "Berkshire Hathaway",
          reportPeriod: "2026-09-30",
          filingDate: "2026-11-14",
          formType: "13F-HR",
          cusip: "037833100",
          shareType: "SH",
          putCall: "NONE",
          shares: 300000000,
          prevShares: 290000000,
          shareChangePct: 0.0345,
          marketValueUsd: 69000000000,
          actionType: "ADD",
        },
      ],
    },
    shortInterest: buildShortInterest(),
    recentNewsEvents: [
      {
        id: 10,
        sourceType: "SEC_8K",
        externalId: "0000320193-26-000101",
        publishedAt: "2026-11-18T14:30:00Z",
        headline: "SEC 8-K (Items 2.02, 9.01)",
        summarySnippet: "Q4 results and Q1 outlook",
        publisher: "SEC EDGAR",
        url: "https://www.sec.gov/Archives/edgar/data/320193/000032019326000101/index.htm",
        secForm: "8-K",
        secItems: "2.02,9.01",
        exhibit991Url: "https://www.sec.gov/Archives/edgar/data/320193/000032019326000101/ex991.htm",
        exhibitExtractionStatus: "EXTRACTED",
      },
    ],
    eventSummaries: [
      {
        id: 21,
        triggerDate: "2026-11-18",
        triggerReason: "MANUAL",
        status: "COMPLETED",
        inputFingerprint: "fp-1",
        aiProvider: "MANUAL_WEB_AI",
        modelName: "MANUAL_IMPORT",
        eventNature: "POSITIVE_CATALYST",
        bulletPoints: ["官方 8-K 財報營收優於預期", "管理層上調下季營收區間"],
        guidance: {
          periodLabel: "Q1 FY2027",
          action: "RAISED",
          basis: "GAAP",
          revenueLow: 124.0,
          revenueHigh: 128.0,
          epsLow: 2.35,
          epsHigh: 2.45,
          currency: "USD",
          unitScale: "BILLION",
          sourceFilingUrl: "https://www.sec.gov/Archives/edgar/data/320193/ex991.htm",
          quoteExcerpt: "We expect Q1 revenue between $124B and $128B.",
        },
        sourceUrls: ["https://www.sec.gov/Archives/edgar/data/320193/ex991.htm"],
        errorMessage: null,
        createdAt: "2026-11-18T15:00:00Z",
      },
    ],
    confirmedCustomTags: [],
    allowedCustomTagConcepts: ["REVENUE", "OPERATING_INCOME", "NET_INCOME_COMMON"],
    ...overrides,
  };
}

function renderWithProviders(ui: React.ReactElement, i18nInst: I18nInstance) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <I18nextProvider i18n={i18nInst}>{ui}</I18nextProvider>
    </QueryClientProvider>,
  );
}

describe("Batch 06: Positioning, Events, AI 3-Channel Isolation & Real Tri-Lingual i18n", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test("tri-lingual locales (zh-HK, zh-CN, en) have 100% key parity across all Batch 06 sections", async () => {
    const sections = [
      ["enhancements"],
      ["dashboard", "marketRegime"],
      ["dashboard", "messages"],
      ["notifications", "alerts"],
      ["analysisReport", "executiveSummary", "risks"],
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
      expect(hkKeys.length).toBeGreaterThan(0);

      for (const k of hkKeys) {
        expect(hkInst.t(k)).not.toEqual(k);
        expect(cnInst.t(k)).not.toEqual(k);
        expect(enInst.t(k)).not.toEqual(k);
      }
    }

    const requiredNewErrorKeys = [
      "enhancements.errors.NO_CACHED_NEWS_OR_FILINGS_FOR_SUMMARY",
      "enhancements.errors.NO_CUSTOM_OR_UNMAPPED_XBRL_TAGS_FOUND",
      "enhancements.errors.SEC_CIK_REQUIRED_FOR_CUSTOM_TAGS",
      "enhancements.errors.SUMMARY_GUIDANCE_REVENUE_RANGE_INVALID",
      "enhancements.errors.SUMMARY_GUIDANCE_EPS_RANGE_INVALID",
      "enhancements.errors.CUSTOM_TAG_MONETARY_UNIT_INVALID",
    ];
    for (const k of requiredNewErrorKeys) {
      expect(hkInst.t(k)).not.toEqual(k);
      expect(cnInst.t(k)).not.toEqual(k);
      expect(enInst.t(k)).not.toEqual(k);
    }
  });

  test("StockEnhancementPanels renders Options OI non-directional disclaimer, Form 4 Code P vs 10b5-1, Short Interest ADR block, and zero-cost manual News & Custom Tag flows", async () => {
    const i18nInst = await createRealI18n("zh-HK");
    mockedEnhApi.getOverview.mockResolvedValue(buildOverview());
    mockedEnhApi.getNewsPrompt.mockResolvedValue({
      taskType: "EVENT_NEWS_SUMMARY",
      inputFingerprint: "fp-manual-1",
      promptText: "COMPACT_NEWS_PROMPT",
      expectedJsonSchema: "{}",
      sourceItemCount: 1,
    });
    mockedEnhApi.importNewsManual.mockResolvedValue({
      id: 22,
      triggerDate: "2026-11-18",
      triggerReason: "MANUAL",
      status: "COMPLETED",
      inputFingerprint: "fp-manual-1",
      aiProvider: "MANUAL_WEB_AI",
      modelName: "MANUAL_IMPORT",
      eventNature: "NEUTRAL_NOISE",
      bulletPoints: ["測試匯入摘要"],
      guidance: null,
      sourceUrls: [],
      errorMessage: null,
      createdAt: "2026-11-18T16:00:00Z",
    });
    mockedEnhApi.getCustomTagPrompt.mockResolvedValue({
      taskType: "SEC_CUSTOM_TAG_MAPPING",
      inputFingerprint: "fp-tag-1",
      promptText: "COMPACT_TAG_PROMPT",
      expectedJsonSchema: "{}",
      sourceItemCount: 1,
    });
    mockedEnhApi.importCustomTagsManual.mockResolvedValue({
      cik: "0000320193",
      figi: "BBG000B9XRY4",
      symbol: "AAPL",
      provider: "MANUAL_WEB_AI",
      modelName: "MANUAL_IMPORT",
      persistedToDatabase: false,
      allowedTargetConcepts: ["REVENUE"],
      proposals: [
        {
          sourceType: "XBRL_EXTENSION",
          taxonomy: "aapl",
          customTag: "CustomServicesRevenue",
          unit: "USD",
          labelEn: "Services revenue",
          targetConcept: "REVENUE",
          signMultiplier: 1,
          rationale: "Matches total revenue line",
        },
      ],
    });
    mockedEnhApi.confirmCustomTags.mockResolvedValue({
      cik: "0000320193",
      confirmedMappingCount: 1,
      backfilledFactCount: 4,
      rebuiltStockCount: 1,
      mappings: [],
    });

    renderWithProviders(<StockEnhancementPanels figi="BBG000B9XRY4" />, i18nInst);

    // Tab 0: Options & AI channel NONE
    await waitFor(() => {
      expect(screen.getByTestId("oi-nondirectional-disclaimer")).toBeInTheDocument();
    });
    expect(screen.getByText(/僅手動模式（零 API 消耗）/)).toBeInTheDocument();
    expect(screen.getByText(/每張未平倉合約（OI）皆同時存在買方與賣方/)).toBeInTheDocument();
    expect(screen.getByText(/財報跳空風險提示/)).toBeInTheDocument();

    // Tab 1: Form 4 & 13F
    fireEvent.click(screen.getByRole("tab", { name: "Form 4 / 13F" }));
    expect(screen.getByText("高管逢低買進確認")).toBeInTheDocument();
    expect(screen.getByText("公開市場買進 (P)")).toBeInTheDocument();
    expect(screen.getByText("10b5-1 預定計劃")).toBeInTheDocument();
    expect(screen.getByText("Berkshire Hathaway")).toBeInTheDocument();

    // Tab 2: Short Interest
    fireEvent.click(screen.getByRole("tab", { name: "做空比例" }));
    expect(screen.getByText(/空頭地雷警示/)).toBeInTheDocument();
    expect(screen.getByText(/ADR 流通股換算倍數未核實/)).toBeInTheDocument();

    // Tab 3: News & 8-K Guidance (zero-API-cost manual mode)
    fireEvent.click(screen.getByRole("tab", { name: "異動新聞與財測" }));
    expect(screen.queryByRole("button", { name: "AI 生成 3 點摘要" })).not.toBeInTheDocument();
    expect(screen.getByText(/新聞摘要絕不覆寫量化估值/)).toBeInTheDocument();
    expect(screen.getByText(/Q1 FY2027/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "複製精簡 Prompt（零 API 成本）" }));
    await waitFor(() => {
      expect(screen.getByDisplayValue("COMPACT_NEWS_PROMPT")).toBeInTheDocument();
    });

    // Tab 4: SEC Custom Tag Mapping (strict preview-only isolation before user confirm)
    fireEvent.click(screen.getByRole("tab", { name: "SEC 自訂標籤映射" }));
    expect(screen.getByTestId("custom-tag-isolation-warning")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "複製映射 Prompt（零 API 成本）" }));
    await waitFor(() => {
      expect(screen.getByDisplayValue("COMPACT_TAG_PROMPT")).toBeInTheDocument();
    });
    const pasteBox = screen.getByLabelText(/貼上網頁版 AI 回傳之映射 JSON/);
    fireEvent.change(pasteBox, { target: { value: '{"proposals":[]}' } });
    fireEvent.click(screen.getByRole("button", { name: "解析為待確認預覽（不寫庫）" }));
    await waitFor(() => {
      expect(screen.getByText(/待人工確認映射預覽（1 項，尚未入庫）/)).toBeInTheDocument();
    });
    // Confirm was NOT called automatically during preview!
    expect(mockedEnhApi.confirmCustomTags).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "確認儲存並確定性重算" }));
    await waitFor(() => {
      expect(mockedEnhApi.confirmCustomTags).toHaveBeenCalledTimes(1);
    });
  });

  test("ETF mode hides SEC Custom Tag tab while retaining Options positioning panel", async () => {
    const i18nInst = await createRealI18n("en");
    mockedEnhApi.getOverview.mockResolvedValue(
      buildOverview({
        symbol: "SPY",
        etf: true,
        options: buildOptions({
          symbol: "SPY",
          impliedMoveStatus: "NOT_APPLICABLE",
          impliedMoveReason: "ETF_OPTIONS_SUMMARY_ONLY",
          earningsGapExceedsStopRisk: false,
        }),
      }),
    );

    renderWithProviders(<StockEnhancementPanels figi="BBG000BDTBL9" />, i18nInst);
    await waitFor(() => {
      expect(screen.getByTestId("oi-nondirectional-disclaimer")).toBeInTheDocument();
    });
    expect(screen.queryByRole("tab", { name: "SEC Custom Tag Mapping" })).not.toBeInTheDocument();
  });

  test("ExecutiveSummaryPanel renders HY OAS stress macro trigger and enhancement chips", async () => {
    const i18nInst = await createRealI18n("zh-HK");
    renderWithProviders(
      <ExecutiveSummaryPanel
        valuation={null}
        summaryResult={{
          status: "AVAILABLE",
          reasons: [],
          value: {
            verdict: "MODERATELY_UNDERVALUED",
            fundamentalBacking: "STRONG",
            stance: "ACCUMULATE_AT_SUPPORT",
            primaryMetric: "PE",
            anchorSource: "HISTORICAL_AND_PEER",
            fairValue: null,
            discountToMid: -0.166,
            upsideToMid: 0.2,
            yieldSpreadVs10y: null,
            yieldSpreadBasis: null,
            technicalSupportZoneId: null,
            technicalSupportRange: null,
            technicalSupportTimeframe: null,
            technicalSupportStrength: null,
            weeklyTrend: "UP",
            aboveSma200: true,
            entryPermission: "ALLOWED",
            eventGateBlocked: false,
            nextBlockingEventDate: null,
            macroCaution: {
              active: true,
              evaluated: true,
              triggerCodes: ["HY_OAS_STRESS"],
              marketRegime: "BULL",
              vixClose: 18,
              tenYearChange20: 0.05,
              suggestedMarginBufferMin: 0.1,
              suggestedMarginBufferMax: 0.15,
              suggestedPositionScale: 0.5,
              hyOasPct: 5.25,
              hyOasDate: "2026-11-18",
              hyOasDeltaVsPrior20Bps: 72.0,
              hyOasStatus: "STRESS",
            },
            risks: [
              { code: "HY_OAS_CREDIT_STRESS", severity: "HIGH", params: { oasPct: "5.25" } },
            ],
            watchItems: [],
            reasons: [],
          },
        }}
        enhancements={{
          creditSpread: null,
          insiderBuyUndervaluedConfirm: true,
          codePBuyCount60d: 2,
          codePDistinctInsiders60d: 2,
          codePTotalValueUsd60d: 450000,
          options: buildOptions({
            impliedMovePct: 0.072,
            earningsGapExceedsStopRisk: true,
          }),
          shortInterest: buildShortInterest({
            signalCode: "SHORT_TRAP_WARNING",
          }),
        }}
      />,
      i18nInst,
    );

    expect(screen.getByText("高管逢低買進確認")).toBeInTheDocument();
    expect(screen.getByText(/財報跳空風險（隱含波動 ±7.2% > 止損距離）/)).toBeInTheDocument();
    expect(screen.getByText(/空頭地雷警示/)).toBeInTheDocument();
    expect(screen.getByText(/高收益債信用利差（HY OAS）處於壓力區/)).toBeInTheDocument();
  });

  test("formatAlertMessage renders HY_OAS_CREDIT_STRESS and HOLDING_EARNINGS_GAP_EXCEEDS_STOP in all 3 locales", async () => {
    for (const lng of ["zh-HK", "zh-CN", "en"] as const) {
      const inst = await createRealI18n(lng);
      const hyMsg = formatAlertMessage(inst.t, "HY_OAS_CREDIT_STRESS", null, {
        oasPct: 5.25,
        deltaBps: 68.5,
        observationDate: "2026-11-18",
      });
      expect(hyMsg).toContain("5.25");
      expect(hyMsg).toContain("68.5");
      expect(hyMsg).not.toContain("HY_OAS_CREDIT_STRESS");

      const gapMsg = formatAlertMessage(inst.t, "HOLDING_EARNINGS_GAP_EXCEEDS_STOP", "AAPL", {
        eventDate: "2026-11-25",
        daysToEarnings: 5,
        impliedMovePct: 0.068,
        stopDistancePct: 0.045,
      });
      expect(gapMsg).toContain("AAPL");
      expect(gapMsg).toContain("6.8%");
      expect(gapMsg).toContain("4.5%");
      expect(gapMsg).not.toContain("HOLDING_EARNINGS_GAP_EXCEEDS_STOP");
    }
  });

  test("StockEnhancementPanels forwards reportContext and syncs parent report on Options/Insiders/Short refresh", async () => {
    const i18nInst = await createRealI18n("zh-HK");
    const onValuationRebuilt = jest.fn().mockResolvedValue(undefined);
    const reportContext = {
      protectiveStopPrice: 215,
      closePrice: 230.5,
      verdict: "DEEP_UNDERVALUED",
      fundamentalBacking: "WEAKENING",
      resistanceBreakout: true,
    };
    mockedEnhApi.getOverview.mockResolvedValue(buildOverview());
    mockedEnhApi.refreshOptions.mockResolvedValue(buildOptions());
    mockedEnhApi.refreshInsiders.mockResolvedValue({ scannedFilings: 1, savedTransactions: 1 });
    mockedEnhApi.refreshShortInterest.mockResolvedValue({ fetchedRows: 1, savedRows: 1 });

    renderWithProviders(
      <StockEnhancementPanels
        figi="BBG000B9XRY4"
        asOfDate="2026-10-15"
        reportContext={reportContext}
        onValuationRebuilt={onValuationRebuilt}
      />,
      i18nInst,
    );

    await waitFor(() => {
      expect(mockedEnhApi.getOverview).toHaveBeenCalledWith("BBG000B9XRY4", "2026-10-15", reportContext);
    });
    expect(await screen.findByText(/財報跳空風險提示/)).toBeInTheDocument();

    // Options refresh forwards contextDate/stop/close and invokes onValuationRebuilt
    fireEvent.click(screen.getByRole("button", { name: "即時更新期權" }));
    await waitFor(() => {
      expect(mockedEnhApi.refreshOptions).toHaveBeenCalledWith("BBG000B9XRY4", {
        contextDate: "2026-10-15",
        protectiveStopPrice: 215,
        closePrice: 230.5,
      });
      expect(onValuationRebuilt).toHaveBeenCalledTimes(1);
    });

    // Insiders refresh forwards contextDate/verdict and invokes onValuationRebuilt
    fireEvent.click(screen.getByRole("tab", { name: "Form 4 / 13F" }));
    fireEvent.click(screen.getByRole("button", { name: "更新 Form 4" }));
    await waitFor(() => {
      expect(mockedEnhApi.refreshInsiders).toHaveBeenCalledWith("BBG000B9XRY4", {
        contextDate: "2026-10-15",
        verdict: "DEEP_UNDERVALUED",
      });
      expect(onValuationRebuilt).toHaveBeenCalledTimes(2);
    });

    // Short interest refresh forwards contextDate/fundamentalBacking/resistanceBreakout and invokes onValuationRebuilt
    fireEvent.click(screen.getByRole("tab", { name: "做空比例" }));
    fireEvent.click(screen.getByRole("button", { name: "更新做空資料" }));
    await waitFor(() => {
      expect(mockedEnhApi.refreshShortInterest).toHaveBeenCalledWith("BBG000B9XRY4", {
        contextDate: "2026-10-15",
        fundamentalBacking: "WEAKENING",
        resistanceBreakout: true,
      });
      expect(onValuationRebuilt).toHaveBeenCalledTimes(3);
    });
  });
});
