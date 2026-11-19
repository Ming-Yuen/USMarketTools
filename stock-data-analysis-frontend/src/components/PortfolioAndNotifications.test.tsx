import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import i18next from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import zhHK from "../locales/zh-HK.json";
import zhCN from "../locales/zh-CN.json";
import en from "../locales/en.json";
import { PositionSizingBlock } from "./PositionSizingBlock";
import { PortfolioDrawer } from "./PortfolioDrawer";
import { PortfolioHealthCard } from "./PortfolioHealthCard";
import { NotificationCenterPopover } from "./NotificationCenterPopover";
import { formatAlertMessage } from "./portfolioFormatters";
import * as portfolioApi from "../services/portfolioApi";
import { PositionSizing } from "../services/types/dto/analysis";
import {
  DailyClosingBriefing,
  InAppNotification,
  PortfolioHealthSummary,
} from "../services/types/dto/portfolio";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
}), { virtual: true });

jest.mock("../services/apiClient", () => ({
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

function createRealI18n(lng: "zh-HK" | "zh-CN" | "en") {
  const instance = i18next.createInstance();
  instance.use(initReactI18next).init({
    lng,
    fallbackLng: false,
    resources: {
      "zh-HK": { translation: zhHK },
      "zh-CN": { translation: zhCN },
      en: { translation: en },
    },
    interpolation: { escapeValue: false },
    initImmediate: false,
  });
  return instance;
}

function collectLeafKeys(obj: unknown, prefix = ""): string[] {
  if (obj == null || typeof obj !== "object" || Array.isArray(obj)) {
    return prefix ? [prefix] : [];
  }
  const keys: string[] = [];
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const next = prefix ? `${prefix}.${k}` : k;
    if (v != null && typeof v === "object" && !Array.isArray(v)) {
      keys.push(...collectLeafKeys(v, next));
    } else {
      keys.push(next);
    }
  }
  return keys.sort();
}

function getByPath(obj: Record<string, unknown>, path: string): unknown {
  return path.split(".").reduce<unknown>((acc, key) => {
    if (acc != null && typeof acc === "object" && key in (acc as Record<string, unknown>)) {
      return (acc as Record<string, unknown>)[key];
    }
    return undefined;
  }, obj);
}

function extractPlaceholders(template: string): string[] {
  return Array.from(template.matchAll(/\{\{\s*([^}]+?)\s*\}\}/g))
    .map((m) => m[1].trim())
    .sort();
}

function renderWithProviders(ui: React.ReactElement, lng: "zh-HK" | "zh-CN" | "en" = "zh-HK") {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const i18n = createRealI18n(lng);
  return render(
    <QueryClientProvider client={queryClient}>
      <I18nextProvider i18n={i18n}>{ui}</I18nextProvider>
    </QueryClientProvider>,
  );
}

describe("Batch 04 三語詞典對齊與核心介面驗證", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test("portfolio 與 notifications 命名空間在 zh-HK / zh-CN / en 三語鍵值與插值參數 100% 一致", () => {
    const roots = ["portfolio", "notifications"] as const;
    const dicts = {
      "zh-HK": zhHK as unknown as Record<string, unknown>,
      "zh-CN": zhCN as unknown as Record<string, unknown>,
      en: en as unknown as Record<string, unknown>,
    };

    for (const root of roots) {
      const hkKeys = collectLeafKeys(dicts["zh-HK"][root], root);
      const cnKeys = collectLeafKeys(dicts["zh-CN"][root], root);
      const enKeys = collectLeafKeys(dicts.en[root], root);

      expect(hkKeys.length).toBeGreaterThan(20);
      expect(cnKeys).toEqual(hkKeys);
      expect(enKeys).toEqual(hkKeys);

      for (const key of hkKeys) {
        const hkVal = String(getByPath(dicts["zh-HK"], key) ?? "");
        const cnVal = String(getByPath(dicts["zh-CN"], key) ?? "");
        const enVal = String(getByPath(dicts.en, key) ?? "");
        expect(hkVal.trim().length).toBeGreaterThan(0);
        expect(cnVal.trim().length).toBeGreaterThan(0);
        expect(enVal.trim().length).toBeGreaterThan(0);
        expect(extractPlaceholders(cnVal)).toEqual(extractPlaceholders(hkVal));
        expect(extractPlaceholders(enVal)).toEqual(extractPlaceholders(hkVal));
      }
    }

    // 以真實 payload 驗證全部 alertCode 在三語下皆無殘留 {{...}}
    const allAlertCodes = [
      "HOLDING_HIGH_RISK_BREAKDOWN",
      "HOLDING_STOP_BREACHED",
      "HOLDING_TECHNICAL_INVALIDATION",
      "HOLDING_SUSPECTED_SHAKEOUT",
      "HOLDING_BREACH_OBSERVING",
      "HOLDING_EARNINGS_COUNTDOWN",
      "HOLDING_FUNDAMENTAL_WEAKENING",
      "HOLDING_VALUE_TRAP",
      "HOLDING_OVERVALUED_PROFIT_TAKING",
      "HOLDING_TARGET_REACHED",
      "HOLDING_AT_RESISTANCE_ZONE",
      "HOLDING_SECTOR_CONCENTRATION",
      "WATCHLIST_ACCUMULATE_SUPPORT",
      "WATCHLIST_TREND_PULLBACK_STARTER",
      "RADAR_TOP_OPPORTUNITY",
      "MARKET_REGIME_SHIFT",
      "DAILY_CLOSING_BRIEFING",
    ];
    const samplePayload: Record<string, unknown> = {
      closePrice: 120.5,
      protectiveStop: 112.0,
      closeThreshold: 113.5,
      eventDate: "2026-11-21",
      daysToEarnings: 3,
      timingCode: "AFTER_CLOSE",
      bufferLevel: "THIN",
      stance: "CAUTION_VALUE_TRAP",
      inProfit: true,
      targetLevel: "T1",
      targetPrice: 118.0,
      resistanceLower: 118.0,
      resistanceUpper: 122.0,
      industryName: "Semiconductors",
      exposurePct: 0.34,
      maxSectorExposurePct: 0.3,
      suggestedShares: 50,
      trackType: "VALUE_SUPPORT",
      previousRegime: "RISK_ON",
      currentRegime: "CORRECTION",
      previousTradeDate: "2026-11-17",
      tradeDate: "2026-11-18",
      criticalCount: 1,
      warningCount: 2,
      radarTop5Count: 3,
    };

    for (const lng of ["zh-HK", "zh-CN", "en"] as const) {
      const inst = createRealI18n(lng);
      for (const code of allAlertCodes) {
        const text = formatAlertMessage(inst.t, code, "NVDA", samplePayload);
        expect(text).not.toContain("{{");
        expect(text).not.toContain("notifications.alerts.");
        expect(text).not.toContain("VALUE_SUPPORT");
      }
      // 缺 suggestedShares 時不可偽造 0 股
      const noSharesText = formatAlertMessage(inst.t, "WATCHLIST_ACCUMULATE_SUPPORT", "NVDA", {
        closePrice: 120,
        protectiveStop: 110,
      });
      expect(noSharesText).toContain("—");
      expect(noSharesText).not.toContain(" 0 ");
    }
  });

  test("PositionSizingBlock 正確呈現強勢試單 1/3、產業集中度超標警告與暫緩開倉", () => {
    const starterWithWarning: PositionSizing = {
      status: "AVAILABLE",
      reasonCode: null,
      planActionable: true,
      suggestedShares: 50,
      entryReferencePrice: 100,
      protectiveStopPrice: 95,
      riskPerShare: 5,
      estimatedPositionAmount: 5000,
      positionCapitalPct: 0.05,
      maxEstimatedLossAmount: 250,
      maxEstimatedLossPct: 0.0025,
      totalCapital: 100000,
      riskPerTradePct: 0.01,
      maxSinglePositionPct: 0.15,
      maxSectorExposurePct: 0.3,
      scaleNumerator: 1,
      scaleDenominator: 3,
      scaleLabel: "1/3",
      existingHoldingMarketValue: 0,
      industryCode: "SEMI",
      currentSectorExposurePct: 0.28,
      projectedSectorExposurePct: 0.33,
      sectorConcentrationWarning: true,
      excludedHoldingsCount: 0,
      sizingTags: ["TREND_PULLBACK_STARTER_ONE_THIRD", "SECTOR_CONCENTRATION_WARNING"],
    };

    const { rerender } = renderWithProviders(
      <PositionSizingBlock sizing={starterWithWarning} starterMode />,
      "zh-HK",
    );

    expect(screen.getByTestId("suggested-shares-value")).toHaveTextContent("50 股");
    expect(screen.getByTestId("sector-concentration-warning")).toBeInTheDocument();
    expect(screen.getByText(/33\.0%/)).toBeInTheDocument();

    const pausedSizing: PositionSizing = {
      ...starterWithWarning,
      status: "PAUSED",
      suggestedShares: 0,
      scaleLabel: "PAUSED",
      sectorConcentrationWarning: false,
      sizingTags: ["MARKET_REGIME_PAUSE_NEW_ENTRY"],
    };
    const enI18n = createRealI18n("en");
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <I18nextProvider i18n={enI18n}>
          <PositionSizingBlock sizing={pausedSizing} />
        </I18nextProvider>
      </QueryClientProvider>,
    );
    expect(screen.getByTestId("position-sizing-paused")).toHaveTextContent(/BEAR\/PANIC/i);
    expect(screen.queryByText(/Scale: PAUSED|縮倉係數：PAUSED|缩仓系数：PAUSED/i)).not.toBeInTheDocument();
  });

  test("PortfolioHealthCard 正確顯示異常持倉警示、自選買點、缺口警告與即時重算", async () => {
    const mockHealth: PortfolioHealthSummary = {
      tradeDate: "2026-11-18",
      sourceMode: "CLOSING_SNAPSHOT",
      generatedAt: "2026-11-18T22:00:00Z",
      marketRegime: "RISK_ON",
      holdingCount: 2,
      watchlistCount: 1,
      alertHoldingCount: 1,
      criticalCount: 1,
      warningCount: 1,
      infoCount: 1,
      structureHealthy: false,
      completeCoverage: false,
      excludedHoldingsCount: 1,
      coverageWarnings: ["NON_USD_EXCLUDED:HKEX"],
      holdings: [
        {
          figi: "F_BRK",
          symbol: "BRK",
          stockName: "Broken Corp",
          currency: "USD",
          industryCode: "TECH",
          industryName: "Tech",
          evalDate: "2026-11-18",
          closePrice: 42,
          adjustedShares: 200,
          adjustedAvgCost: 50,
          marketValue: 8400,
          unrealizedPnlAmount: -1600,
          unrealizedPnlPct: -0.16,
          splitAlignmentStatus: "ALIGNED",
          verdict: "MODERATELY_UNDERVALUED",
          fundamentalBacking: "WEAKENING",
          stance: "WAIT_FOR_STABILIZATION",
          breakoutDiagnosis: "HIGH_RISK_BREAKDOWN",
          highestSeverity: "CRITICAL",
          alerts: [
            {
              severity: "CRITICAL",
              category: "PORTFOLIO_HOLDING",
              alertCode: "HOLDING_HIGH_RISK_BREAKDOWN",
              figi: "F_BRK",
              symbol: "BRK",
              stockName: "Broken Corp",
              payload: { closePrice: 42 },
            },
          ],
          dataGapReasons: [],
        },
      ],
      watchlistOpportunities: [
        {
          figi: "F_ACT",
          symbol: "ACT",
          stockName: "Active Corp",
          currency: "USD",
          industryCode: "TECH",
          industryName: "Tech",
          triggerType: "WATCHLIST_ACCUMULATE_SUPPORT",
          verdict: "DEEP_UNDERVALUED",
          fundamentalBacking: "STRONG",
          stance: "ACCUMULATE_AT_SUPPORT",
          closePrice: 99,
          entryLower: 96,
          entryUpper: 100,
          protectiveStop: 95,
          firstTargetPrice: 115,
          positionSizing: {
            status: "AVAILABLE",
            planActionable: true,
            suggestedShares: 150,
            scaleNumerator: 1,
            scaleDenominator: 1,
            scaleLabel: "FULL",
            sectorConcentrationWarning: false,
            excludedHoldingsCount: 0,
            sizingTags: [],
          },
        },
      ],
      sectorExposures: [],
      sectorAlerts: [],
    };

    const fetchSpy = jest.spyOn(portfolioApi, "fetchPortfolioHealth").mockResolvedValue(mockHealth);
    renderWithProviders(<PortfolioHealthCard />, "zh-HK");

    await waitFor(() => {
      expect(screen.getByTestId("holding-health-row-BRK")).toBeInTheDocument();
    });
    expect(screen.getByTestId("portfolio-coverage-warning")).toBeInTheDocument();
    expect(screen.getByTestId("watchlist-opportunity-ACT")).toHaveTextContent("150 股");

    fireEvent.click(screen.getByTestId("portfolio-health-recalc-btn"));
    await waitFor(() => {
      expect(fetchSpy).toHaveBeenCalledWith(true);
    });
  });

  test("NotificationCenterPopover 顯示未讀紅點、支援標記已讀跳轉與展開當日收盤簡報", async () => {
    const mockNotifs: InAppNotification[] = [
      {
        id: 101,
        dedupKey: "HOLDING:2026-11-18:F_BRK:HOLDING_HIGH_RISK_BREAKDOWN",
        tradeDate: "2026-11-18",
        severity: "CRITICAL",
        category: "PORTFOLIO_HOLDING",
        alertCode: "HOLDING_HIGH_RISK_BREAKDOWN",
        figi: "F_BRK",
        symbol: "BRK",
        stockName: "Broken Corp",
        targetRoute: "/stockAnalysis/F_BRK",
        payload: { closePrice: 42 },
        read: false,
        readAt: null,
        createdAt: "2026-11-18T22:00:00Z",
      },
    ];
    const mockBriefing: DailyClosingBriefing = {
      tradeDate: "2026-11-18",
      marketRegime: "CORRECTION",
      previousMarketRegime: "RISK_ON",
      previousTradeDate: "2026-11-17",
      regimeChanged: true,
      vixClose: 23.4,
      treasury10y: 4.35,
      topSectors: [{ industryCode: "TECH", industryName: "Technology", cmf20d: 0.21, flowState: "INFLOW" }],
      bottomSectors: [{ industryCode: "ENRG", industryName: "Energy", cmf20d: -0.15, flowState: "OUTFLOW" }],
      portfolioHealth: {
        tradeDate: "2026-11-18",
        sourceMode: "CLOSING_SNAPSHOT",
        marketRegime: "CORRECTION",
        holdingCount: 1,
        watchlistCount: 0,
        alertHoldingCount: 0,
        criticalCount: 0,
        warningCount: 0,
        infoCount: 0,
        structureHealthy: true,
        completeCoverage: true,
        excludedHoldingsCount: 0,
        coverageWarnings: [],
        holdings: [],
        watchlistOpportunities: [],
        sectorExposures: [],
        sectorAlerts: [],
      },
      watchlistOpportunities: [],
      radarTop5: [
        {
          figi: "F_NVDA",
          symbol: "NVDA",
          stockName: "NVIDIA",
          currency: "USD",
          trackType: "VALUE_SUPPORT",
          closePrice: 120,
          protectiveStop: 110,
          suggestedShares: 50,
          sizingTags: [],
        },
      ],
      criticalCount: 1,
      warningCount: 0,
      infoCount: 0,
    };

    jest.spyOn(portfolioApi, "fetchUnreadNotificationCount").mockResolvedValue({ unreadCount: 3 });
    jest.spyOn(portfolioApi, "fetchNotifications").mockResolvedValue(mockNotifs);
    jest.spyOn(portfolioApi, "fetchLatestClosingBriefing").mockResolvedValue(mockBriefing);
    const markReadSpy = jest.spyOn(portfolioApi, "markNotificationRead").mockResolvedValue({
      id: 101,
      updated: true,
      unreadCount: 2,
    });

    renderWithProviders(<NotificationCenterPopover />, "zh-HK");

    await waitFor(() => {
      expect(screen.getByTestId("notification-bell-btn")).toHaveTextContent("3");
    });

    fireEvent.click(screen.getByTestId("notification-bell-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("notification-item-101")).toBeInTheDocument();
    });

    // 切換到「當日收盤簡報」分頁驗證市場切換、板塊與雷達前 5 名
    fireEvent.click(screen.getByRole("tab", { name: "當日收盤簡報" }));
    await waitFor(() => {
      expect(screen.getByTestId("closing-briefing-panel")).toHaveTextContent("NVDA");
      expect(screen.getByTestId("closing-briefing-panel")).toHaveTextContent("Technology");
    });

    // 切回提醒清單並點擊通知，確認標記已讀並跳轉個股頁
    fireEvent.click(screen.getByRole("tab", { name: "提醒清單" }));
    await waitFor(() => {
      expect(screen.getByTestId("notification-item-101")).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId("notification-item-101"));
    await waitFor(() => {
      expect(markReadSpy).toHaveBeenCalledWith(101);
    });
    expect(mockNavigate).toHaveBeenCalledWith("/stockAnalysis/F_BRK");
  });

  test("PortfolioDrawer 載入失敗時同時鎖定持倉表單與風控設定，防止誤把既有持倉覆寫為自選股", async () => {
    jest.spyOn(portfolioApi, "fetchPortfolioOverview").mockRejectedValue(new Error("network down"));
    const upsertSpy = jest.spyOn(portfolioApi, "upsertPortfolioItem").mockResolvedValue({} as never);

    const { unmount } = renderWithProviders(
      <PortfolioDrawer
        open
        onClose={jest.fn()}
        initialStock={{ figi: "F_NVDA", symbol: "NVDA", latestClosePrice: 120 }}
      />,
      "zh-HK",
    );

    await waitFor(() => {
      expect(screen.getByTestId("portfolio-item-load-error")).toBeInTheDocument();
    });
    const itemSaveBtn = screen.getByTestId("portfolio-item-save-btn");
    expect(itemSaveBtn).toBeDisabled();
    fireEvent.submit(itemSaveBtn.closest("form") as HTMLFormElement);
    expect(upsertSpy).not.toHaveBeenCalled();

    unmount();

    renderWithProviders(<PortfolioDrawer open onClose={jest.fn()} initialTab={2} />, "zh-HK");
    await waitFor(() => {
      expect(screen.getByTestId("portfolio-config-load-error")).toBeInTheDocument();
    });
    expect(screen.getByTestId("portfolio-config-save-btn")).toBeDisabled();
  });

  test("PortfolioDrawer 切換或編輯既有 FIGI 時鎖定識別碼並自動載入對應持倉，不將 A 的表單覆寫給 B", async () => {
    jest.spyOn(portfolioApi, "fetchPortfolioOverview").mockResolvedValue({
      config: {
        totalCapital: 100000,
        riskPerTradePct: 0.01,
        maxSinglePositionPct: 0.15,
        maxSectorExposurePct: 0.3,
        capitalCurrency: "USD",
      },
      items: [
        {
          figi: "FIGI_A",
          symbol: "AAA",
          itemType: "WATCHLIST",
          splitAdjusted: false,
          notes: "A-note",
        },
        {
          figi: "FIGI_B",
          symbol: "BBB",
          itemType: "HOLDING",
          rawShares: 100,
          rawAvgCost: 42,
          costBasisDate: "2026-11-18",
          adjustedShares: 100,
          adjustedAvgCost: 42,
          splitAdjusted: false,
          notes: "B-holding",
        },
      ],
      sectorExposures: [],
      holdingCount: 1,
      watchlistCount: 1,
      totalHoldingMarketValueUsd: 4200,
      totalHoldingCostUsd: 4200,
      totalUnrealizedPnlUsd: 0,
      excludedHoldingsCount: 0,
      coverageWarnings: [],
    });
    const upsertSpy = jest.spyOn(portfolioApi, "upsertPortfolioItem").mockResolvedValue({
      figi: "FIGI_B",
      symbol: "BBB",
      itemType: "HOLDING",
      splitAdjusted: false,
    });

    // 從無鎖定新增頁籤輸入 FIGI_A 後改輸入 FIGI_B：必須自動切回 FIGI_B 的 HOLDING 資料而非送出 A 的 WATCHLIST
    renderWithProviders(<PortfolioDrawer open onClose={jest.fn()} initialTab={1} />, "zh-HK");
    const figiInput = await waitFor(() => {
      const el = screen.getByTestId("portfolio-item-figi-input").querySelector("input") as HTMLInputElement;
      expect(el).not.toBeDisabled();
      return el;
    });

    fireEvent.change(figiInput, { target: { value: "FIGI_A" } });
    fireEvent.change(figiInput, { target: { value: "FIGI_B" } });
    fireEvent.submit(screen.getByTestId("portfolio-item-save-btn").closest("form") as HTMLFormElement);

    await waitFor(() => {
      expect(upsertSpy).toHaveBeenCalledWith("FIGI_B", expect.objectContaining({
        itemType: "HOLDING",
        shares: 100,
        avgCost: 42,
        notes: "B-holding",
      }));
    });
  });
});
