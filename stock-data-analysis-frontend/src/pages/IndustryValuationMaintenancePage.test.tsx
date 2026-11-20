import React from "react";
import "@testing-library/jest-dom";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next, { i18n as I18nInstance } from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import zhHK from "../locales/zh-HK.json";
import zhCN from "../locales/zh-CN.json";
import en from "../locales/en.json";
import { IndustryValuationMaintenancePage } from "./IndustryValuationMaintenancePage";
import { IndustryValuationProposalPanel } from "./IndustryValuationProposalPanel";
import { industryValuationApi } from "../services/industryValuationApi";
import {
  IndustryValuationDraft,
  IndustryValuationOverview,
  IndustryValuationOverviewItem,
  IndustryValuationReview,
} from "../services/types/dto/industryValuation";

jest.mock("../services/apiClient", () => ({
  apiClient: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock("../services/industryValuationApi", () => ({
  industryValuationApi: {
    list: jest.fn(),
    review: jest.fn(),
    propose: jest.fn(),
    save: jest.fn(),
    recalculate: jest.fn(),
  },
}));

const mockedApi = industryValuationApi as jest.Mocked<typeof industryValuationApi>;

const SUPPORTED_METRICS: Record<string, string[]> = {
  NON_FINANCIAL_OPERATING: ["PE", "PB", "PS", "PFCF", "EV_EBITDA"],
  BANK: ["PE", "PB"],
  INSURANCE: ["PE", "PB"],
};

async function createRealI18n(lng: "zh-HK" | "zh-CN" | "en"): Promise<I18nInstance> {
  const inst = i18next.createInstance();
  await inst.use(initReactI18next).init({
    lng,
    fallbackLng: false,
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

function makeItem(overrides: Partial<IndustryValuationOverviewItem> & Pick<IndustryValuationOverviewItem, "industryCode" | "industryName">): IndustryValuationOverviewItem {
  return {
    modelCode: null,
    primaryMetric: null,
    assumedMultipleLow: null,
    assumedMultipleHigh: null,
    effectiveAt: null,
    sourceUrl: null,
    configured: false,
    canPropose: false,
    activeStockCount: 0,
    ...overrides,
  };
}

function makeOverview(items: IndustryValuationOverviewItem[]): IndustryValuationOverview {
  return { items, supportedMetrics: SUPPORTED_METRICS };
}

async function renderPage(lng: "zh-HK" | "zh-CN" | "en" = "zh-HK") {
  const i18nInst = await createRealI18n(lng);
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 5 * 60 * 1000 },
    },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <I18nextProvider i18n={i18nInst}>
        <IndustryValuationMaintenancePage />
      </I18nextProvider>
    </QueryClientProvider>,
  );
  return { ...view, queryClient, i18nInst };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("3.3 Tri-lingual i18n coverage & Drawer localization", () => {
  const REQUIRED_KEYS = [
    "maintenance.industryValuation.title",
    "maintenance.industryValuation.status.configured",
    "maintenance.industryValuation.status.unconfigured",
    "maintenance.industryValuation.actions.triggerAi",
    "maintenance.industryValuation.error.conflict409",
  ];

  test.each(["zh-HK", "zh-CN", "en"] as const)("real i18next instance resolves all required keys in %s", async (lng) => {
    const inst = await createRealI18n(lng);
    for (const key of REQUIRED_KEYS) {
      const value = inst.t(key);
      expect(value).toBeTruthy();
      expect(value.trim().length).toBeGreaterThan(0);
      expect(value).not.toBe(key);
      expect(value).not.toContain("maintenance.industryValuation");
    }
  });

  test("zh-HK, zh-CN, and en have 100% identical maintenance.industryValuation & industryProposal.codes keys", () => {
    const hkMaint = collectLeaves(zhHK.maintenance.industryValuation as unknown as Record<string, unknown>);
    const cnMaint = collectLeaves(zhCN.maintenance.industryValuation as unknown as Record<string, unknown>);
    const enMaint = collectLeaves(en.maintenance.industryValuation as unknown as Record<string, unknown>);
    expect(cnMaint).toEqual(hkMaint);
    expect(enMaint).toEqual(hkMaint);

    const hkCodes = collectLeaves(zhHK.industryProposal.codes as unknown as Record<string, unknown>);
    const cnCodes = collectLeaves(zhCN.industryProposal.codes as unknown as Record<string, unknown>);
    const enCodes = collectLeaves(en.industryProposal.codes as unknown as Record<string, unknown>);
    expect(hkCodes).toContain("PFCF");
    expect(cnCodes).toEqual(hkCodes);
    expect(enCodes).toEqual(hkCodes);
  });

  test("English mode renders page and Drawer with zero raw i18n keys and zero CJK characters", async () => {
    const item = makeItem({
      industryCode: "SEMICONDUCTOR",
      industryName: "Semiconductors",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      assumedMultipleLow: 15,
      assumedMultipleHigh: 25,
      effectiveAt: "2026-01-15T08:00:00Z",
      configured: true,
      canPropose: true,
      activeStockCount: 42,
    });
    mockedApi.list.mockResolvedValueOnce(makeOverview([item]));
    mockedApi.review.mockResolvedValueOnce({
      industryCode: "SEMICONDUCTOR",
      industry: "Semiconductors",
      current: {
        effectiveAt: "2026-01-15T08:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 15,
        assumedMultipleHigh: 25,
        rationale: "Industry cycle benchmark",
        sourceUrl: "https://example.com/semi",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    });

    await renderPage("en");
    const editBtn = await screen.findByRole("button", { name: "Edit parameters" });
    expect(document.body.textContent).not.toContain("maintenance.industryValuation");

    fireEvent.click(editBtn);
    await screen.findByText("Edit industry valuation settings");
    await screen.findByDisplayValue("Industry cycle benchmark");

    const presentationText = document.body.textContent ?? "";
    expect(presentationText).not.toContain("maintenance.industryValuation");
    expect(/[\u4e00-\u9fff]/.test(presentationText)).toBe(false);
    expect(mockedApi.propose).not.toHaveBeenCalled();
  });
});

describe("3.1 Zero background AI consumption across search, filter, sort, pagination, and drawer open", () => {
  test("searching, status/model filtering, sorting, paginating, and opening quick setup never trigger AI or extra overview GET", async () => {
    const manyItems: IndustryValuationOverviewItem[] = [
      makeItem({
        industryCode: "SEMI",
        industryName: "半導體產業",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 15,
        assumedMultipleHigh: 25,
        effectiveAt: "2026-01-15T08:00:00Z",
        configured: true,
        canPropose: true,
        activeStockCount: 42,
      }),
      makeItem({
        industryCode: "BANK01",
        industryName: "區域銀行",
        modelCode: "BANK",
        primaryMetric: "PB",
        configured: false,
        canPropose: true,
        activeStockCount: 18,
      }),
      ...Array.from({ length: 12 }, (_, idx) =>
        makeItem({
          industryCode: `IND_${String(idx + 1).padStart(2, "0")}`,
          industryName: `產業_${idx + 1}`,
          configured: false,
          canPropose: false,
          activeStockCount: 10 - (idx % 5),
        }),
      ),
    ];

    mockedApi.list.mockResolvedValueOnce(makeOverview(manyItems));
    mockedApi.review.mockResolvedValueOnce({
      industryCode: "BANK01",
      industry: "區域銀行",
      current: {
        effectiveAt: "2026-01-10T00:00:00Z",
        modelCode: "BANK",
        primaryMetric: "PB",
        assumedMultipleLow: null,
        assumedMultipleHigh: null,
        rationale: null,
        sourceUrl: null,
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    });

    await renderPage("zh-HK");
    await screen.findByTestId("kpi-total-count");

    expect(screen.getByTestId("kpi-total-count")).toHaveTextContent("14");
    expect(screen.getByTestId("kpi-configured-count")).toHaveTextContent("1");
    expect(screen.getByTestId("kpi-unconfigured-count")).toHaveTextContent("13");

    // 切換每頁 10 筆並翻至第 2 頁（驗證中文輔助按鈕名稱）
    fireEvent.mouseDown(screen.getByRole("combobox", { name: /每頁筆數/i }));
    fireEvent.click(screen.getByRole("option", { name: "10" }));
    fireEvent.click(screen.getByRole("button", { name: "下一頁" }));
    expect(screen.getByText(/第 11–14 筆/)).toBeInTheDocument();

    // 切回第 1 頁並驗證表頭升降序實際改變資料列順序
    fireEvent.click(screen.getByRole("button", { name: "上一頁" }));
    fireEvent.click(screen.getByRole("button", { name: "活躍個股數" }));
    const firstRowDesc = screen.getAllByTestId(/^industry-row-/)[0];
    expect(firstRowDesc).toHaveAttribute("data-testid", "industry-row-SEMI");

    fireEvent.click(screen.getByRole("button", { name: "活躍個股數" }));
    const firstRowAsc = screen.getAllByTestId(/^industry-row-/)[0];
    expect(firstRowAsc).not.toHaveAttribute("data-testid", "industry-row-SEMI");

    fireEvent.click(screen.getByRole("button", { name: "活躍個股數" }));
    expect(screen.getAllByTestId(/^industry-row-/)[0]).toHaveAttribute("data-testid", "industry-row-SEMI");

    // 切換模型下拉篩選（來自後端 supportedMetrics）
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "估值模型" }));
    fireEvent.click(screen.getByRole("option", { name: "銀行" }));
    expect(screen.getByTestId("industry-row-BANK01")).toBeInTheDocument();
    expect(screen.queryByTestId("industry-row-SEMI")).not.toBeInTheDocument();

    // 重設並點擊 KPI 待補齊卡片過濾
    fireEvent.click(screen.getByRole("button", { name: "重設篩選" }));
    fireEvent.click(screen.getByTestId("kpi-unconfigured-card"));
    expect(screen.queryByTestId("industry-row-SEMI")).not.toBeInTheDocument();
    expect(screen.getByTestId("industry-row-BANK01")).toBeInTheDocument();

    // 驗證 partial config (BANK/PB, 倍數空) 顯示待設定、保留模型與主指標，且 AI 可用時有單筆提議按鈕
    const bankRow = screen.getByTestId("industry-row-BANK01");
    expect(within(bankRow).getByText("銀行")).toBeInTheDocument();
    expect(within(bankRow).getByText("P/B")).toBeInTheDocument();
    expect(within(bankRow).getByText("待設定")).toBeInTheDocument();
    expect(within(bankRow).getByRole("button", { name: "AI 提議" })).toBeInTheDocument();

    // 搜尋關鍵字 "半導體"（重設篩選後）
    fireEvent.click(screen.getByRole("button", { name: "重設篩選" }));
    fireEvent.change(screen.getByLabelText("搜尋產業名稱或代碼"), { target: { value: "半導體" } });
    expect(screen.getByTestId("industry-row-SEMI")).toBeInTheDocument();
    expect(screen.queryByTestId("industry-row-BANK01")).not.toBeInTheDocument();

    // 重設並打開 BANK01 快速設定抽屜（不觸發 AI）
    fireEvent.click(screen.getByRole("button", { name: "重設篩選" }));
    fireEvent.click(within(screen.getByTestId("industry-row-BANK01")).getByRole("button", { name: "快速設定" }));
    await screen.findByText("產業估值設定編輯");
    expect(screen.queryByRole("button", { name: "重算此股估值" })).not.toBeInTheDocument();

    expect(mockedApi.list).toHaveBeenCalledTimes(1);
    expect(mockedApi.propose).not.toHaveBeenCalled();
    expect(mockedApi.save).not.toHaveBeenCalled();
  });

  test("handles empty industry dictionary cleanly with 0% coverage and zero AI calls", async () => {
    mockedApi.list.mockResolvedValueOnce(makeOverview([]));
    await renderPage("zh-HK");
    await screen.findByTestId("kpi-total-count");

    expect(screen.getByTestId("kpi-total-count")).toHaveTextContent("0");
    expect(screen.getByTestId("kpi-configured-pct")).toHaveTextContent("0%");
    expect(screen.getByText("系統尚未建立產業分類字典。")).toBeInTheDocument();
    expect(mockedApi.propose).not.toHaveBeenCalled();
  });
});

describe("3.2 Concurrent edit lock (409 Conflict), single-click AI race guard, and drawer save refresh", () => {
  test("displays localized 409 warning without overwriting newer DB config and recovers on reload", async () => {
    const semiItem = makeItem({
      industryCode: "SEMICONDUCTOR",
      industryName: "半導體產業",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      assumedMultipleLow: 15,
      assumedMultipleHigh: 25,
      effectiveAt: "2024-01-01T00:00:00.123456Z",
      configured: true,
      canPropose: true,
      activeStockCount: 42,
    });
    const oldReview: IndustryValuationReview = {
      industryCode: "SEMICONDUCTOR",
      industry: "半導體產業",
      current: {
        effectiveAt: "2024-01-01T00:00:00.123456Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 15,
        assumedMultipleHigh: 25,
        rationale: "舊理由",
        sourceUrl: "https://example.com/old",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    };
    const newerReview: IndustryValuationReview = {
      ...oldReview,
      current: {
        effectiveAt: "2024-01-02T10:00:00.654321Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 18,
        assumedMultipleHigh: 28,
        rationale: "使用者 A 新理由",
        sourceUrl: "https://example.com/user-a",
      },
    };

    mockedApi.list
      .mockResolvedValueOnce(makeOverview([semiItem]))
      .mockResolvedValueOnce(
        makeOverview([
          {
            ...semiItem,
            assumedMultipleLow: 19,
            assumedMultipleHigh: 29,
            effectiveAt: "2024-01-03T00:00:00.000000Z",
          },
        ]),
      );
    mockedApi.review.mockResolvedValueOnce(oldReview).mockResolvedValueOnce(newerReview);
    mockedApi.save
      .mockRejectedValueOnce({ response: { status: 409, data: { message: "正式設定已改變，請重新載入後確認。" } } })
      .mockResolvedValueOnce({
        ...newerReview,
        current: {
          ...newerReview.current!,
          effectiveAt: "2024-01-03T00:00:00.000000Z",
          assumedMultipleLow: 19,
          assumedMultipleHigh: 29,
        },
      });

    await renderPage("zh-HK");
    await screen.findByTestId("industry-row-SEMICONDUCTOR");

    fireEvent.click(within(screen.getByTestId("industry-row-SEMICONDUCTOR")).getByRole("button", { name: "編輯參數" }));
    await screen.findByDisplayValue("舊理由");

    fireEvent.change(screen.getByLabelText("假設倍數下限"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));

    // 409 警告顯示，保留草稿供比對，並停用舊稿送出按鈕
    await screen.findByText(zhHK.maintenance.industryValuation.error.conflict409);
    expect(screen.getByDisplayValue("20")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "確認保存正式設定" })).toBeDisabled();
    expect(mockedApi.save).toHaveBeenCalledWith(
      "SEMICONDUCTOR",
      expect.objectContaining({ baseEffectiveAt: "2024-01-01T00:00:00.123456Z" }),
    );

    // 點擊「捨棄草稿並載入最新設定」重新取得最新版本並原樣保留微秒時間戳
    fireEvent.click(screen.getByRole("button", { name: "捨棄草稿並載入最新設定" }));
    await screen.findByDisplayValue("使用者 A 新理由");
    expect(screen.getByRole("button", { name: "確認保存正式設定" })).not.toBeDisabled();

    fireEvent.change(screen.getByLabelText("假設倍數下限"), { target: { value: "19" } });
    fireEvent.change(screen.getByLabelText("假設倍數上限"), { target: { value: "29" } });
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));

    await waitFor(() =>
      expect(mockedApi.save).toHaveBeenLastCalledWith(
        "SEMICONDUCTOR",
        expect.objectContaining({ baseEffectiveAt: "2024-01-02T10:00:00.654321Z", assumedMultipleLow: 19, assumedMultipleHigh: 29 }),
      ),
    );
    await screen.findByText(zhHK.maintenance.industryValuation.notice.savedAndRefreshed);
    expect(mockedApi.list).toHaveBeenCalledTimes(2);
    expect(mockedApi.propose).not.toHaveBeenCalled();
  });

  test("late AI proposal response for closed row A does not pollute newly opened row B", async () => {
    let resolveProposeA!: (draft: IndustryValuationDraft) => void;
    const pendingProposeA = new Promise<IndustryValuationDraft>((resolve) => {
      resolveProposeA = resolve;
    });

    const rowA = makeItem({
      industryCode: "IND_A",
      industryName: "產業 A",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      configured: false,
      canPropose: true,
      activeStockCount: 30,
    });
    const rowB = makeItem({
      industryCode: "IND_B",
      industryName: "產業 B",
      modelCode: "BANK",
      primaryMetric: "PB",
      assumedMultipleLow: 1.0,
      assumedMultipleHigh: 1.8,
      effectiveAt: "2026-01-05T00:00:00Z",
      configured: true,
      canPropose: true,
      activeStockCount: 20,
    });

    mockedApi.list.mockResolvedValueOnce(makeOverview([rowA, rowB]));
    mockedApi.review.mockImplementation(async (code: string) => {
      if (code === "IND_A") {
        return {
          industryCode: "IND_A",
          industry: "產業 A",
          current: {
            effectiveAt: "2026-01-01T00:00:00Z",
            modelCode: "NON_FINANCIAL_OPERATING",
            primaryMetric: "PE",
            assumedMultipleLow: null,
            assumedMultipleHigh: null,
            rationale: null,
            sourceUrl: null,
          },
          supportedMetrics: SUPPORTED_METRICS,
          canPropose: true,
        };
      }
      return {
        industryCode: "IND_B",
        industry: "產業 B",
        current: {
          effectiveAt: "2026-01-05T00:00:00Z",
          modelCode: "BANK",
          primaryMetric: "PB",
          assumedMultipleLow: 1.0,
          assumedMultipleHigh: 1.8,
          rationale: "銀行原有理由",
          sourceUrl: "https://example.com/bank",
        },
        supportedMetrics: SUPPORTED_METRICS,
        canPropose: true,
      };
    });
    mockedApi.propose.mockReturnValueOnce(pendingProposeA);

    await renderPage("zh-HK");
    await screen.findByTestId("industry-row-IND_A");

    // 觸發 A 的單筆 AI 提議
    fireEvent.click(within(screen.getByTestId("industry-row-IND_A")).getByRole("button", { name: "AI 提議" }));
    await waitFor(() => expect(mockedApi.propose).toHaveBeenCalledTimes(1));
    expect(mockedApi.propose).toHaveBeenCalledWith("IND_A");

    // 在 A 尚未回傳時關閉抽屜並改開 B
    fireEvent.click(screen.getByRole("button", { name: "關閉面板" }));
    fireEvent.click(within(screen.getByTestId("industry-row-IND_B")).getByRole("button", { name: "編輯參數" }));
    await screen.findByDisplayValue("銀行原有理由");

    // A 的晚回應到達：絕不污染 B 的表單，也不會重送 AI
    await act(async () => {
      resolveProposeA({
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 99,
        assumedMultipleHigh: 120,
        asOf: "2026-11-20",
        rationale: "來自 A 的延遲草稿",
        sources: [{ url: "https://example.com/a", title: "A", publishedDate: "2026-11-20", evidence: "E" }],
      });
      await Promise.resolve();
    });

    expect(screen.getByDisplayValue("銀行原有理由")).toBeInTheDocument();
    expect(screen.queryByDisplayValue("來自 A 的延遲草稿")).not.toBeInTheDocument();
    expect(mockedApi.propose).toHaveBeenCalledTimes(1);
    expect(mockedApi.save).not.toHaveBeenCalled();
  });

  test("clamps page automatically after saving last unconfigured item on page 2 and warns if overview refetch fails", async () => {
    const items: IndustryValuationOverviewItem[] = Array.from({ length: 11 }, (_, idx) =>
      makeItem({
        industryCode: `U_${String(idx + 1).padStart(2, "0")}`,
        industryName: `待設_${idx + 1}`,
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        configured: false,
        canPropose: false,
        activeStockCount: 11 - idx,
      }),
    );
    const updatedItems = items.map((it) =>
      it.industryCode === "U_11" ? { ...it, assumedMultipleLow: 12, assumedMultipleHigh: 18, configured: true } : it,
    );

    mockedApi.list
      .mockResolvedValueOnce(makeOverview(items))
      .mockResolvedValueOnce(makeOverview(updatedItems));
    mockedApi.review.mockResolvedValueOnce({
      industryCode: "U_11",
      industry: "待設_11",
      current: {
        effectiveAt: "2026-01-01T00:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: null,
        assumedMultipleHigh: null,
        rationale: null,
        sourceUrl: null,
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: false,
    });
    mockedApi.save.mockResolvedValueOnce({
      industryCode: "U_11",
      industry: "待設_11",
      current: {
        effectiveAt: "2026-11-20T03:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 12,
        assumedMultipleHigh: 18,
        rationale: "完成設定",
        sourceUrl: "https://example.com/u11",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: false,
    });

    await renderPage("zh-HK");
    await screen.findByTestId("kpi-total-count");

    // 先套用「僅看待設定」過濾，再切每頁 10 筆並翻到第 2 頁（只有 U_11）
    fireEvent.click(screen.getByTestId("kpi-unconfigured-card"));
    fireEvent.mouseDown(screen.getByRole("combobox", { name: /每頁筆數/i }));
    fireEvent.click(screen.getByRole("option", { name: "10" }));
    fireEvent.click(screen.getByRole("button", { name: "下一頁" }));
    expect(screen.getByTestId("industry-row-U_11")).toBeInTheDocument();

    fireEvent.click(within(screen.getByTestId("industry-row-U_11")).getByRole("button", { name: "快速設定" }));
    await screen.findByLabelText("假設倍數下限");
    fireEvent.change(screen.getByLabelText("假設倍數下限"), { target: { value: "12" } });
    fireEvent.change(screen.getByLabelText("假設倍數上限"), { target: { value: "18" } });
    fireEvent.change(screen.getByLabelText("假設理由"), { target: { value: "完成設定" } });
    fireEvent.change(screen.getByLabelText("正式設定來源網址"), { target: { value: "https://example.com/u11" } });
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));

    // 儲存後 U_11 離開待設定集合，無需手動切換篩選即自動夾回第 1 頁
    await screen.findByText(zhHK.maintenance.industryValuation.notice.savedAndRefreshed);
    expect(screen.getByTestId("kpi-configured-count")).toHaveTextContent("1");
    expect(screen.getByText(/第 1–10 筆，共 10 筆/)).toBeInTheDocument();
  });

  test("warns without asking to resubmit when post-save overview refresh fails and updates language dynamically", async () => {
    const item = makeItem({
      industryCode: "SEMI",
      industryName: "Semiconductors",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      configured: false,
      canPropose: false,
      activeStockCount: 10,
    });
    mockedApi.list
      .mockResolvedValueOnce(makeOverview([item]))
      .mockRejectedValueOnce(new Error("NETWORK_TEMP_FAIL"));
    mockedApi.review.mockResolvedValueOnce({
      industryCode: "SEMI",
      industry: "Semiconductors",
      current: {
        effectiveAt: "2026-01-01T00:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: null,
        assumedMultipleHigh: null,
        rationale: null,
        sourceUrl: null,
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: false,
    });
    mockedApi.save.mockResolvedValueOnce({
      industryCode: "SEMI",
      industry: "Semiconductors",
      current: {
        effectiveAt: "2026-11-20T03:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 15,
        assumedMultipleHigh: 22,
        rationale: "Confirmed",
        sourceUrl: "https://example.com/semi",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: false,
    });

    const { i18nInst } = await renderPage("en");
    const setupBtn = await screen.findByRole("button", { name: "Quick setup" });
    fireEvent.click(setupBtn);
    await screen.findByLabelText("Assumed multiple low");
    fireEvent.change(screen.getByLabelText("Assumed multiple low"), { target: { value: "15" } });
    fireEvent.change(screen.getByLabelText("Assumed multiple high"), { target: { value: "22" } });
    fireEvent.change(screen.getByLabelText("Rationale"), { target: { value: "Confirmed" } });
    fireEvent.change(screen.getByLabelText("Official source URL"), { target: { value: "https://example.com/semi" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm & save official settings" }));

    await screen.findByText(en.maintenance.industryValuation.notice.savedRefreshFailed);
    expect(mockedApi.save).toHaveBeenCalledTimes(1);

    // 切換語系至 zh-HK，已顯示的提示文案應即時重新翻譯
    await act(async () => {
      await i18nInst.changeLanguage("zh-HK");
    });
    expect(screen.getByText(zhHK.maintenance.industryValuation.notice.savedRefreshFailed)).toBeInTheDocument();
  });

  test("unmounting maintenance page while row AI detail check is pending prevents POST /proposals", async () => {
    let resolveDetail!: (review: IndustryValuationReview) => void;
    const pendingDetail = new Promise<IndustryValuationReview>((resolve) => {
      resolveDetail = resolve;
    });
    const rowA = makeItem({
      industryCode: "IND_A",
      industryName: "產業 A",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      configured: false,
      canPropose: true,
      activeStockCount: 30,
    });
    mockedApi.list.mockResolvedValueOnce(makeOverview([rowA]));
    mockedApi.review.mockReturnValue(pendingDetail);

    const { unmount } = await renderPage("zh-HK");
    const aiBtn = await screen.findByRole("button", { name: "AI 提議" });
    fireEvent.click(aiBtn);

    unmount();

    await act(async () => {
      resolveDetail({
        industryCode: "IND_A",
        industry: "產業 A",
        current: {
          effectiveAt: "2026-01-01T00:00:00Z",
          modelCode: "NON_FINANCIAL_OPERATING",
          primaryMetric: "PE",
          assumedMultipleLow: null,
          assumedMultipleHigh: null,
          rationale: null,
          sourceUrl: null,
        },
        supportedMetrics: SUPPORTED_METRICS,
        canPropose: true,
      });
      await Promise.resolve();
    });

    expect(mockedApi.propose).not.toHaveBeenCalled();
  });

  test("closing drawer while PUT is in flight still updates detail cache and invalidates overview", async () => {
    let resolveSave!: (saved: IndustryValuationReview) => void;
    const pendingSave = new Promise<IndustryValuationReview>((resolve) => {
      resolveSave = resolve;
    });
    const item = makeItem({
      industryCode: "SEMI",
      industryName: "半導體",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      assumedMultipleLow: 15,
      assumedMultipleHigh: 25,
      effectiveAt: "2026-01-01T00:00:00Z",
      configured: true,
      canPropose: true,
      activeStockCount: 40,
    });
    const versionA: IndustryValuationReview = {
      industryCode: "SEMI",
      industry: "半導體",
      current: {
        effectiveAt: "2026-01-01T00:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 15,
        assumedMultipleHigh: 25,
        rationale: "版本 A",
        sourceUrl: "https://example.com/a",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    };
    const versionB: IndustryValuationReview = {
      ...versionA,
      current: {
        ...versionA.current!,
        effectiveAt: "2026-11-20T05:00:00Z",
        assumedMultipleLow: 18,
        assumedMultipleHigh: 28,
        rationale: "版本 B",
      },
    };

    mockedApi.list
      .mockResolvedValueOnce(makeOverview([item]))
      .mockResolvedValueOnce(makeOverview([{ ...item, assumedMultipleLow: 18, assumedMultipleHigh: 28, effectiveAt: "2026-11-20T05:00:00Z" }]));
    mockedApi.review.mockResolvedValueOnce(versionA);
    mockedApi.save.mockReturnValueOnce(pendingSave);

    const { queryClient } = await renderPage("zh-HK");
    await screen.findByTestId("industry-row-SEMI");

    fireEvent.click(within(screen.getByTestId("industry-row-SEMI")).getByRole("button", { name: "編輯參數" }));
    await screen.findByDisplayValue("版本 A");
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));
    // PUT 進行中立即關閉 Drawer
    fireEvent.click(screen.getByRole("button", { name: "關閉面板" }));

    await act(async () => {
      resolveSave(versionB);
      await Promise.resolve();
    });

    await waitFor(() => expect(mockedApi.list).toHaveBeenCalledTimes(2));
    const cachedDetail = queryClient.getQueryData<IndustryValuationReview>(["industry-valuation-review", "SEMI"]);
    expect(cachedDetail?.current?.effectiveAt).toBe("2026-11-20T05:00:00Z");
  });

  test("in-flight detail GET started before PUT cannot overwrite freshly saved version B", async () => {
    let resolveSlowGet!: (review: IndustryValuationReview) => void;
    const slowGet = new Promise<IndustryValuationReview>((resolve) => {
      resolveSlowGet = resolve;
    });
    const item = makeItem({
      industryCode: "SEMI",
      industryName: "半導體",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      assumedMultipleLow: 15,
      assumedMultipleHigh: 25,
      effectiveAt: "2026-01-01T00:00:00Z",
      configured: true,
      canPropose: true,
      activeStockCount: 40,
    });
    const versionA: IndustryValuationReview = {
      industryCode: "SEMI",
      industry: "半導體",
      current: {
        effectiveAt: "2026-01-01T00:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 15,
        assumedMultipleHigh: 25,
        rationale: "版本 A",
        sourceUrl: "https://example.com/a",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    };
    const versionB: IndustryValuationReview = {
      ...versionA,
      current: {
        ...versionA.current!,
        effectiveAt: "2026-11-20T06:00:00Z",
        assumedMultipleLow: 20,
        assumedMultipleHigh: 30,
        rationale: "版本 B",
      },
    };

    mockedApi.list
      .mockResolvedValueOnce(makeOverview([item]))
      .mockResolvedValueOnce(makeOverview([{ ...item, assumedMultipleLow: 20, assumedMultipleHigh: 30, effectiveAt: "2026-11-20T06:00:00Z" }]));
    mockedApi.review.mockResolvedValueOnce(versionA).mockReturnValueOnce(slowGet);
    mockedApi.save.mockResolvedValueOnce(versionB);

    const { queryClient } = await renderPage("zh-HK");
    await screen.findByTestId("industry-row-SEMI");

    fireEvent.click(within(screen.getByTestId("industry-row-SEMI")).getByRole("button", { name: "編輯參數" }));
    await screen.findByDisplayValue("版本 A");

    // 觸發一次慢速重讀 GET，隨後立即儲存成版本 B
    fireEvent.click(screen.getByRole("button", { name: "重新讀取設定" }));
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));
    await screen.findByText(zhHK.maintenance.industryValuation.notice.savedAndRefreshed);

    // 較早發出的慢速 GET 晚到達，不應把 detail cache 蓋回舊版本 A
    await act(async () => {
      resolveSlowGet(versionA);
      await Promise.resolve();
    });

    const cachedDetail = queryClient.getQueryData<IndustryValuationReview>(["industry-valuation-review", "SEMI"]);
    expect(cachedDetail?.current?.effectiveAt).toBe("2026-11-20T06:00:00Z");
  });

  test("cancels stale in-flight cold overview GET when PUT succeeds from another entry so KPI reflects version B", async () => {
    let resolveColdOverview!: (data: IndustryValuationOverview) => void;
    const staleColdOverview = new Promise<IndustryValuationOverview>((resolve) => {
      resolveColdOverview = resolve;
    });
    let resolvePut!: (saved: IndustryValuationReview) => void;
    const pendingPut = new Promise<IndustryValuationReview>((resolve) => {
      resolvePut = resolve;
    });

    const unconfiguredA = makeItem({
      industryCode: "SEMI",
      industryName: "半導體",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      configured: false,
      canPropose: true,
      activeStockCount: 40,
    });
    const configuredB = {
      ...unconfiguredA,
      assumedMultipleLow: 16,
      assumedMultipleHigh: 24,
      effectiveAt: "2026-11-20T07:00:00Z",
      configured: true,
    };
    const detailA: IndustryValuationReview = {
      industryCode: "SEMI",
      industry: "半導體",
      current: {
        effectiveAt: "2026-01-01T00:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: null,
        assumedMultipleHigh: null,
        rationale: null,
        sourceUrl: null,
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    };
    const detailB: IndustryValuationReview = {
      ...detailA,
      current: {
        effectiveAt: "2026-11-20T07:00:00Z",
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: 16,
        assumedMultipleHigh: 24,
        rationale: "新版 B",
        sourceUrl: "https://example.com/b",
      },
    };

    mockedApi.review.mockResolvedValueOnce(detailA);
    mockedApi.save.mockReturnValueOnce(pendingPut);
    mockedApi.list.mockReturnValueOnce(staleColdOverview).mockResolvedValueOnce(makeOverview([configuredB]));

    const i18nInst = await createRealI18n("zh-HK");
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 5 * 60 * 1000 } },
    });

    // 1. 個股頁入口發起 PUT（尚未完成）
    const view = render(
      <QueryClientProvider client={queryClient}>
        <I18nextProvider i18n={i18nInst}>
          <IndustryValuationProposalPanel industryCode="SEMI" figi="FIGI-1" />
        </I18nextProvider>
      </QueryClientProvider>,
    );
    await screen.findByRole("button", { name: "手動設定" });
    fireEvent.click(screen.getByRole("button", { name: "手動設定" }));
    fireEvent.change(screen.getByLabelText("假設倍數下限"), { target: { value: "16" } });
    fireEvent.change(screen.getByLabelText("假設倍數上限"), { target: { value: "24" } });
    fireEvent.change(screen.getByLabelText("假設理由"), { target: { value: "新版 B" } });
    fireEvent.change(screen.getByLabelText("正式設定來源網址"), { target: { value: "https://example.com/b" } });
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));

    // 2. 切到首次開啟的維護頁（總覽快取仍為空，冷載入 GET 掛在途中）
    view.rerender(
      <QueryClientProvider client={queryClient}>
        <I18nextProvider i18n={i18nInst}>
          <IndustryValuationMaintenancePage />
        </I18nextProvider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(mockedApi.list).toHaveBeenCalledTimes(1));

    // 3. PUT 成功返回 B，取消在途冷載入 GET 並觸發新 GET 取得 B；舊冷載入 GET 晚到也不得蓋回 A
    await act(async () => {
      resolvePut(detailB);
      await Promise.resolve();
    });
    await act(async () => {
      resolveColdOverview(makeOverview([unconfiguredA]));
      await Promise.resolve();
    });

    await waitFor(() => expect(mockedApi.list).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByTestId("kpi-configured-count")).toHaveTextContent("1"));
    expect(queryClient.getQueryData<IndustryValuationReview>(["industry-valuation-review", "SEMI"])?.current?.effectiveAt).toBe(
      "2026-11-20T07:00:00Z",
    );
  });

  test("out-of-order earlier PUT response B cannot regress detail cache from newer version C", async () => {
    let resolveFirstPut!: (saved: IndustryValuationReview) => void;
    const firstPut = new Promise<IndustryValuationReview>((resolve) => {
      resolveFirstPut = resolve;
    });

    const tsA = "2026-11-15T12:00:00.123456Z";
    const tsB = "2026-11-15T12:31:00.234567Z";
    const tsC = "2026-11-15T13:05:00.345678Z";

    const buildDetail = (ts: string, low: number, high: number, rationale: string): IndustryValuationReview => ({
      industryCode: "SEMI",
      industry: "半導體",
      current: {
        effectiveAt: ts,
        modelCode: "NON_FINANCIAL_OPERATING",
        primaryMetric: "PE",
        assumedMultipleLow: low,
        assumedMultipleHigh: high,
        rationale,
        sourceUrl: "https://example.com/semi",
      },
      supportedMetrics: SUPPORTED_METRICS,
      canPropose: true,
    });
    const detailA = buildDetail(tsA, 15, 25, "版本 A");
    const detailB = buildDetail(tsB, 16, 26, "版本 B");
    const detailC = buildDetail(tsC, 18, 28, "版本 C");

    const rowBase = makeItem({
      industryCode: "SEMI",
      industryName: "半導體",
      modelCode: "NON_FINANCIAL_OPERATING",
      primaryMetric: "PE",
      assumedMultipleLow: 15,
      assumedMultipleHigh: 25,
      effectiveAt: tsA,
      configured: true,
      canPropose: true,
      activeStockCount: 40,
    });

    mockedApi.list
      .mockResolvedValueOnce(makeOverview([rowBase]))
      .mockResolvedValue(makeOverview([{ ...rowBase, assumedMultipleLow: 18, assumedMultipleHigh: 28, effectiveAt: tsC }]));
    mockedApi.review
      .mockResolvedValueOnce(detailA)
      .mockResolvedValueOnce(detailB)
      .mockResolvedValue(detailC);
    mockedApi.save.mockReturnValueOnce(firstPut).mockResolvedValueOnce(detailC);

    const { queryClient } = await renderPage("zh-HK");
    await screen.findByTestId("industry-row-SEMI");

    // 1. 第一次 PUT (A -> B) 掛在途中並關閉抽屜
    fireEvent.click(within(screen.getByTestId("industry-row-SEMI")).getByRole("button", { name: "編輯參數" }));
    await screen.findByDisplayValue("版本 A");
    fireEvent.change(screen.getByLabelText("假設理由"), { target: { value: "版本 B" } });
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));
    fireEvent.click(screen.getByRole("button", { name: "關閉面板" }));

    // 2. 重開抽屜、重新讀取到 B，再以 B 成功保存成 C
    fireEvent.click(within(screen.getByTestId("industry-row-SEMI")).getByRole("button", { name: "編輯參數" }));
    fireEvent.click(await screen.findByRole("button", { name: "重新讀取設定" }));
    await waitFor(() =>
      expect(queryClient.getQueryData<IndustryValuationReview>(["industry-valuation-review", "SEMI"])?.current?.effectiveAt).toBe(tsB),
    );
    fireEvent.click(screen.getByRole("button", { name: "手動設定" }));
    await screen.findByDisplayValue("版本 B");
    fireEvent.change(screen.getByLabelText("假設理由"), { target: { value: "版本 C" } });
    fireEvent.click(screen.getByRole("button", { name: "確認保存正式設定" }));
    await screen.findByText(zhHK.maintenance.industryValuation.notice.savedAndRefreshed);
    expect(queryClient.getQueryData<IndustryValuationReview>(["industry-valuation-review", "SEMI"])?.current?.effectiveAt).toBe(tsC);

    // 3. 放行較早第一次 PUT 的延遲回應 B：detail cache 不得從 C 退回 B
    await act(async () => {
      resolveFirstPut(detailB);
      await Promise.resolve();
    });
    expect(queryClient.getQueryData<IndustryValuationReview>(["industry-valuation-review", "SEMI"])?.current?.effectiveAt).toBe(tsC);
  });
});
