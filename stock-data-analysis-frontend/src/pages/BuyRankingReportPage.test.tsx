import React from "react";
import "@testing-library/jest-dom";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import zhHK from "../locales/zh-HK.json";
import zhCN from "../locales/zh-CN.json";
import en from "../locales/en.json";
import { BuyRankingReportPage } from "./BuyRankingReportPage";
import { apiClient } from "../services/apiClient";
import {
  buyRankingApi,
  extractDownloadFilename,
  parseBlobErrorMessage,
} from "../services/buyRankingApi";
import { BuyRankingResponse } from "../services/types/dto/buyRanking";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({ useNavigate: () => mockNavigate }), { virtual: true });

jest.mock("../services/apiClient", () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
  },
}));

const flattenKeys = (obj: Record<string, unknown>, prefix = ""): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) {
      Object.assign(out, flattenKeys(v as Record<string, unknown>, key));
    } else {
      out[key] = String(v);
    }
  }
  return out;
};

const createI18n = async (lng: "zh-HK" | "zh-CN" | "en") => {
  const instance = i18next.createInstance();
  await instance.use(initReactI18next).init({
    lng,
    fallbackLng: "zh-HK",
    interpolation: { escapeValue: false },
    resources: {
      "zh-HK": { translation: zhHK },
      "zh-CN": { translation: zhCN },
      en: { translation: en },
    },
  });
  return instance;
};

const sampleResponse: BuyRankingResponse = {
  reportId: "rep-xyz-001",
  asOfDate: "2026-11-19",
  generatedAtHk: "2026-11-20 12:00:00 HKT",
  observationCutoff: "2026-11-20T04:00:00Z",
  resolvedLocale: "zh-HK",
  marketRegime: "RISK_ON",
  marketContext: {
    marketRegime: "RISK_ON",
    vixClose: 16.2,
    tenYearYieldPct: 4.25,
    hyOasPct: 3.1,
    hyOasDeltaBps: 4.5,
    hyOasStatus: "NORMAL",
    macroCautionActive: false,
    marketDataReady: true,
    marketDataDate: "2026-11-19",
    sectorMoneyFlowDate: "2026-11-19",
  },
  scopeSummary: {
    scopeMode: "CUSTOM",
    scopeLabel: "Custom",
    industryCodes: [],
    industryNames: [],
    portfolioTypes: [],
    matchedTotalCount: 305,
    evaluatedCount: 300,
    filteredTotalCount: 2,
    maxCandidates: 300,
    truncated: true,
    unmatchedSymbols: ["NO_SUCH"],
    untrackedSymbols: [],
    excludedByAssetTypeSymbols: [],
  },
  totalCount: 2,
  page: 1,
  pageSize: 300,
  tierCounts: { TIER_1: 1, TIER_2: 0, TIER_3: 0, TIER_4: 1 },
  appliedFilters: {
    scopeMode: "CUSTOM",
    industryCodes: [],
    portfolioTypes: [],
    customSymbols: ["NVDA", "TRAP"],
    assetTypes: ["COMMON_STOCK", "ADR"],
    excludeReits: true,
    tiers: ["TIER_1", "TIER_2", "TIER_3", "TIER_4"],
    minFundamentalGrade: "ALL",
    minNetRiskReward: 2.0,
    excludeEarningsWithinDays: null,
    sortBy: "TIER_COMPOSITE",
    horizon: "POSITION",
  },
  items: [
    {
      rank: 1,
      recommendedRank: 1,
      tier: "TIER_1",
      tierScore: 86.5,
      figi: "BBG000BBJQV0",
      symbol: "NVDA",
      stockName: "NVIDIA Corp",
      currency: "USD",
      industryCode: "SEMI",
      sector: "Semiconductors",
      assetType: "COMMON_STOCK",
      rawStockType: "Common Stock",
      verdict: "DEEP_UNDERVALUED",
      fundamentalBacking: "STRONG",
      stance: "ACCUMULATE_AT_SUPPORT",
      breakoutDiagnosis: null,
      currentPrice: 135.2,
      buyZone: { low: 128.0, mid: 130.0, high: 131.5 },
      distanceToBuyZonePct: 0,
      nearSupportWithin3Pct: true,
      buyZoneSource: "TRADE_PLAN_ENTRY",
      stopLossPrice: 121.5,
      stopLossDistancePct: -0.101,
      firstTargetPrice: 175.0,
      netRiskReward: 3.45,
      planNetRiskReward: 3.5,
      suggestedShares: 45,
      positionScaleTag: "1/3",
      positionSizingStatus: "AVAILABLE",
      positionSizingActionable: true,
      positionSizingReasonCode: null,
      sectorConcentrationWarning: false,
      positionSizingTags: [],
      valuationDiscountPct: -0.152,
      forwardPeg: 1.1,
      forwardPe: 28.5,
      roic: 0.285,
      adjFcfTtm: 50000000000,
      reverseDcfImpliedGrowth: 0.142,
      reverseDcfBasis: "SBC_ADJUSTED_FCF",
      reverseDcfStatus: "AVAILABLE",
      etfTrendScore: null,
      etfCmf20d: null,
      etfRs60dVsSpy: null,
      nextEarningsDate: "2026-12-21",
      daysToEarnings: 32,
      sectorFlowState: "INFLOW",
      sectorCmf20d: 0.15,
      reasonCode: "ACCUMULATE_AT_SUPPORT_ACTIVE",
      reasonParams: {},
      actionReason: "估值偏低、基本面穩健且現價處於有效支撐買區（淨賺賠比 3.45），可優先關注",
    },
    {
      rank: 2,
      recommendedRank: 2,
      tier: "TIER_4",
      tierScore: null,
      figi: "BBG000TRAP00",
      symbol: "TRAP",
      stockName: "Trap Inc",
      currency: "USD",
      industryCode: "SEMI",
      sector: "Semiconductors",
      assetType: "COMMON_STOCK",
      rawStockType: "Common Stock",
      verdict: "DEEP_UNDERVALUED",
      fundamentalBacking: "WEAKENING",
      stance: "CAUTION_VALUE_TRAP",
      breakoutDiagnosis: "HIGH_RISK_BREAKDOWN",
      currentPrice: 20.0,
      buyZone: null,
      distanceToBuyZonePct: null,
      nearSupportWithin3Pct: false,
      buyZoneSource: "NONE",
      stopLossPrice: null,
      stopLossDistancePct: null,
      firstTargetPrice: null,
      netRiskReward: null,
      planNetRiskReward: null,
      suggestedShares: 0,
      positionScaleTag: "NOT_RECOMMENDED",
      positionSizingStatus: "NOT_RECOMMENDED",
      positionSizingActionable: false,
      positionSizingReasonCode: "TIER_4_NOT_RECOMMENDED",
      sectorConcentrationWarning: false,
      positionSizingTags: [],
      valuationDiscountPct: -0.4,
      forwardPeg: null,
      forwardPe: null,
      roic: null,
      adjFcfTtm: null,
      reverseDcfImpliedGrowth: null,
      reverseDcfBasis: null,
      reverseDcfStatus: "NOT_APPLICABLE",
      etfTrendScore: null,
      etfCmf20d: null,
      etfRs60dVsSpy: null,
      nextEarningsDate: null,
      daysToEarnings: null,
      sectorFlowState: null,
      sectorCmf20d: null,
      reasonCode: "WEAKENING_AND_BREAKDOWN",
      reasonParams: {},
      actionReason: "基本面轉弱且同支撐區出現高機率真破位，屬高風險價值陷阱",
    },
  ],
};

describe("BuyRankingReportPage & i18n", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (apiClient.get as jest.Mock).mockResolvedValue({
      data: {
        scopeModes: ["SECTOR", "PORTFOLIO", "CUSTOM"],
        portfolioTypes: ["WATCHLIST", "HOLDING"],
        assetTypes: ["COMMON_STOCK", "ADR", "ETF", "REIT"],
        tiers: ["TIER_1", "TIER_2", "TIER_3", "TIER_4"],
        fundamentalGrades: ["ALL", "MIXED", "STRONG"],
        sortOptions: [
          "TIER_COMPOSITE",
          "NET_RR_DESC",
          "DISCOUNT_DESC",
          "DISTANCE_TO_BUY_ZONE_ASC",
          "ROIC_DESC",
          "SYMBOL_ASC",
        ],
        horizons: ["POSITION", "SWING"],
        maxCandidates: 300,
      },
    });
    (apiClient.post as jest.Mock).mockImplementation((url: string) => {
      if (url === "stock/classification") {
        return Promise.resolve({
          data: {
            classifications: [
              { code: "SEMI", industry: "Semiconductors" },
              { code: "SOFT", industry: "Software—Application" },
            ],
          },
        });
      }
      if (url === "reports/buy-ranking") {
        return Promise.resolve({ data: sampleResponse });
      }
      return Promise.reject(new Error(`Unexpected post: ${url}`));
    });
  });

  test("tri-lingual reports.buyRanking keys are 100% aligned and en has zero Chinese characters", async () => {
    const hkKeys = flattenKeys((zhHK as Record<string, unknown>).reports as Record<string, unknown>, "reports");
    const cnKeys = flattenKeys((zhCN as Record<string, unknown>).reports as Record<string, unknown>, "reports");
    const enKeys = flattenKeys((en as Record<string, unknown>).reports as Record<string, unknown>, "reports");

    expect(Object.keys(hkKeys).sort()).toEqual(Object.keys(cnKeys).sort());
    expect(Object.keys(hkKeys).sort()).toEqual(Object.keys(enKeys).sort());

    const i18nEn = await createI18n("en");
    const i18nHk = await createI18n("zh-HK");
    const i18nCn = await createI18n("zh-CN");

    for (const key of Object.keys(enKeys)) {
      const enVal = i18nEn.t(key, {
        date: "2026-11-19",
        time: "12:00",
        matched: 305,
        evaluated: 300,
        max: 300,
        filtered: 2,
        symbols: "NVDA",
        rank: 1,
        score: "86.5",
        pct: "+2.0%",
        shares: "45",
        days: 10,
        message: "ERR",
        asOfDate: "2026-11-17",
        lastClosedDay: "2026-11-19",
      });
      expect(enVal).not.toBe(key);
      expect(/[\u4e00-\u9fff]/.test(enVal)).toBe(false);
      expect(i18nHk.t(key)).not.toBe(key);
      expect(i18nCn.t(key)).not.toBe(key);
    }
  });

  test("supports scope switching, renders ranking with truncation alert, expands row details, recovers from 410 with same filters, and navigates to /stock-analysis/:figi", async () => {
    const i18nInstance = await createI18n("zh-HK");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    let queryCount = 0;
    (apiClient.post as jest.Mock).mockImplementation((url: string) => {
      if (url === "stock/classification") {
        return Promise.resolve({
          data: {
            classifications: [
              { code: "SEMI", industry: "Semiconductors" },
              { code: "SOFT", industry: "Software—Application" },
            ],
          },
        });
      }
      if (url === "reports/buy-ranking") {
        queryCount += 1;
        return Promise.resolve({
          data: {
            ...sampleResponse,
            reportId: queryCount === 1 ? "rep-xyz-001" : "rep-xyz-002",
          },
        });
      }
      return Promise.reject(new Error(`Unexpected post: ${url}`));
    });

    const origCreateUrl = URL.createObjectURL;
    const origRevokeUrl = URL.revokeObjectURL;
    URL.createObjectURL = jest.fn(() => "blob:mock-url");
    URL.revokeObjectURL = jest.fn();

    try {
      render(
        <QueryClientProvider client={queryClient}>
          <I18nextProvider i18n={i18nInstance}>
            <BuyRankingReportPage />
          </I18nextProvider>
        </QueryClientProvider>
      );

      // 等待後端選項載入後切換至自訂標的模式並輸入代碼
      await waitFor(() => {
        expect(screen.getByLabelText("自訂標的")).toBeInTheDocument();
      });
      fireEvent.click(screen.getByLabelText("自訂標的"));
      const input = screen.getByLabelText("自訂標的代碼");
      fireEvent.change(input, { target: { value: "NVDA, TRAP, NO_SUCH" } });
      fireEvent.click(screen.getByTestId("run-ranking-btn"));

      await waitFor(() => {
        expect(screen.getByText("NVDA")).toBeInTheDocument();
      });
      expect(screen.getByTestId("truncation-warning")).toBeInTheDocument();
      expect(screen.getByText("TRAP")).toBeInTheDocument();
      expect(screen.getByText(/NO_SUCH/)).toBeInTheDocument();

      // 展開明細抽屜確認顯示買區來源、倉位狀態與反推 DCF 基準
      fireEvent.click(screen.getByTestId("expand-row-NVDA"));
      await waitFor(() => {
        expect(screen.getByTestId("detail-panel-NVDA")).toBeInTheDocument();
      });
      expect(screen.getByTestId("detail-panel-NVDA")).toHaveTextContent("TRADE_PLAN_ENTRY");
      expect(screen.getByTestId("detail-panel-NVDA")).toHaveTextContent("SBC_ADJUSTED_FCF");

      // 模擬首次以 rep-xyz-001 匯出遇到 410 JSON Blob 錯誤
      const blobError = new Blob([JSON.stringify({ message: "REPORT_SNAPSHOT_EXPIRED" })], {
        type: "application/json",
      });
      const exportSpy = jest.spyOn(buyRankingApi, "exportReport")
        .mockRejectedValueOnce({ response: { status: 410, data: blobError } })
        .mockResolvedValueOnce({
          blob: new Blob(["ok"], { type: "application/octet-stream" }),
          fileName: "USMarketTools_BuyRanking_Custom_20261119.xlsx",
        });

      fireEvent.click(screen.getByTestId("export-xlsx-btn"));
      await waitFor(() => {
        expect(screen.getByTestId("export-error-alert")).toHaveTextContent("快照已過期");
      });
      expect(exportSpy).toHaveBeenNthCalledWith(
        1,
        "XLSX",
        "rep-xyz-001",
        expect.anything(),
        "zh-HK",
        expect.any(String)
      );

      // 不更改任何篩選條件，直接重新點擊「產生排行榜」，確認發起第 2 次查詢取得 rep-xyz-002 並成功下載
      fireEvent.click(screen.getByTestId("run-ranking-btn"));
      await waitFor(() => {
        expect(queryCount).toBe(2);
      });
      await waitFor(() => {
        expect(screen.queryByTestId("export-error-alert")).not.toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId("export-xlsx-btn"));
      await waitFor(() => {
        expect(exportSpy).toHaveBeenCalledTimes(2);
      });
      expect(exportSpy).toHaveBeenNthCalledWith(
        2,
        "XLSX",
        "rep-xyz-002",
        expect.anything(),
        "zh-HK",
        expect.any(String)
      );

      // 點擊股票代碼跳轉 /stock-analysis/:figi
      fireEvent.click(screen.getByText("NVDA"));
      expect(mockNavigate).toHaveBeenCalledWith("/stock-analysis/BBG000BBJQV0");
    } finally {
      URL.createObjectURL = origCreateUrl;
      URL.revokeObjectURL = origRevokeUrl;
    }
  });

  test("shows retry alert when /reports/buy-ranking/options fails and recovers on retry", async () => {
    const i18nInstance = await createI18n("zh-HK");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    (apiClient.get as jest.Mock)
      .mockRejectedValueOnce(new Error("OPTIONS_NETWORK_ERROR"))
      .mockResolvedValueOnce({
        data: {
          scopeModes: ["SECTOR", "PORTFOLIO", "CUSTOM"],
          portfolioTypes: ["WATCHLIST", "HOLDING"],
          assetTypes: ["COMMON_STOCK", "ADR", "ETF", "REIT"],
          tiers: ["TIER_1", "TIER_2", "TIER_3", "TIER_4"],
          fundamentalGrades: ["ALL", "MIXED", "STRONG"],
          sortOptions: ["TIER_COMPOSITE", "NET_RR_DESC"],
          horizons: ["POSITION", "SWING"],
          maxCandidates: 300,
        },
      });

    render(
      <QueryClientProvider client={queryClient}>
        <I18nextProvider i18n={i18nInstance}>
          <BuyRankingReportPage />
        </I18nextProvider>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByTestId("options-error-alert")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("retry-options-btn"));
    await waitFor(() => {
      expect(screen.getByLabelText("自訂標的")).toBeInTheDocument();
    });
  });

  test("extractDownloadFilename and parseBlobErrorMessage handle headers and Blob errors", async () => {
    expect(
      extractDownloadFilename('attachment; filename="USMarketTools_BuyRanking_Semiconductors_20261119.xlsx"', "fallback.xlsx")
    ).toBe("USMarketTools_BuyRanking_Semiconductors_20261119.xlsx");

    const blob = new Blob([JSON.stringify({ message: "INVALID_EXPORT_FORMAT" })], { type: "application/json" });
    const parsed = await parseBlobErrorMessage({ response: { data: blob } }, "FALLBACK");
    expect(parsed).toBe("INVALID_EXPORT_FORMAT");
  });

  test("validates Min Net R/R input: blank sends null, 1.85 sends exact number with step=any, 1e and -0.5 block query", async () => {
    const i18nInstance = await createI18n("zh-HK");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const submittedBodies: Array<Record<string, unknown>> = [];
    (apiClient.post as jest.Mock).mockImplementation((url: string, body?: Record<string, unknown>) => {
      if (url === "stock/classification") {
        return Promise.resolve({ data: { classifications: [] } });
      }
      if (url === "reports/buy-ranking") {
        submittedBodies.push(body ?? {});
        return Promise.resolve({ data: sampleResponse });
      }
      return Promise.reject(new Error(`Unexpected post: ${url}`));
    });

    render(
      <QueryClientProvider client={queryClient}>
        <I18nextProvider i18n={i18nInstance}>
          <BuyRankingReportPage />
        </I18nextProvider>
      </QueryClientProvider>
    );

    await waitFor(() => {
      expect(screen.getByLabelText("自訂標的")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByLabelText("自訂標的"));
    fireEvent.change(screen.getByLabelText("自訂標的代碼"), { target: { value: "NVDA" } });

    const minRrInput = screen.getByTestId("min-net-rr-input") as HTMLInputElement;
    expect(minRrInput.step).toBe("any");
    expect(minRrInput.min).toBe("0");

    // 1. 留空送出 minNetRiskReward = null
    fireEvent.click(screen.getByTestId("run-ranking-btn"));
    await waitFor(() => {
      expect(submittedBodies).toHaveLength(1);
    });
    expect(submittedBodies[0].minNetRiskReward).toBeNull();

    // 2. 合法兩位小數 1.85 無 stepMismatch 並原值送出
    fireEvent.change(minRrInput, { target: { value: "1.85" } });
    expect(minRrInput.validity.stepMismatch).toBe(false);
    expect(minRrInput.checkValidity()).toBe(true);
    fireEvent.click(screen.getByTestId("run-ranking-btn"));
    await waitFor(() => {
      expect(submittedBodies).toHaveLength(2);
    });
    expect(submittedBodies[1].minNetRiskReward).toBe(1.85);

    // 3. 負數 -0.5 顯示提示並阻止查詢
    fireEvent.change(minRrInput, { target: { value: "-0.5" } });
    fireEvent.click(screen.getByTestId("run-ranking-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("validation-error-alert")).toHaveTextContent("最低淨賺賠比");
    });
    expect(submittedBodies).toHaveLength(2);

    // 4. 不完整數值輸入 1e（瀏覽器會清空 value="" 並設 validity.badInput = true）應阻止查詢，不可靜默送 null
    fireEvent.change(minRrInput, { target: { value: "" } });
    const originalValidity = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "validity");
    const badInputValidity = {
      ...minRrInput.validity,
      badInput: true,
      valid: false,
    };
    Object.defineProperty(minRrInput, "validity", {
      configurable: true,
      get: () => badInputValidity,
    });
    const origCheckValidity = minRrInput.checkValidity.bind(minRrInput);
    minRrInput.checkValidity = () => false;

    try {
      fireEvent.click(screen.getByTestId("run-ranking-btn"));
      await waitFor(() => {
        expect(screen.getByTestId("validation-error-alert")).toHaveTextContent("最低淨賺賠比");
      });
      expect(submittedBodies).toHaveLength(2);
    } finally {
      if (originalValidity) {
        Object.defineProperty(minRrInput, "validity", originalValidity);
      } else {
        delete (minRrInput as unknown as Record<string, unknown>).validity;
      }
      minRrInput.checkValidity = origCheckValidity;
    }

    // 5. 清空恢復合法狀態後可再次送出 null
    fireEvent.change(minRrInput, { target: { value: "" } });
    fireEvent.click(screen.getByTestId("run-ranking-btn"));
    await waitFor(() => {
      expect(submittedBodies).toHaveLength(3);
    });
    expect(submittedBodies[2].minNetRiskReward).toBeNull();
    expect(screen.queryByTestId("validation-error-alert")).not.toBeInTheDocument();
  });
});
