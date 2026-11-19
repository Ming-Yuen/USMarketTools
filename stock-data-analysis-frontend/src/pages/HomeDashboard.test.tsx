import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen, within } from "@testing-library/react";
import i18next from "i18next";
import { I18nextProvider, initReactI18next } from "react-i18next";
import zhHK from "../locales/zh-HK.json";
import zhCN from "../locales/zh-CN.json";
import en from "../locales/en.json";
import HomeDashboard from "./HomeDashboard";
import { ValuationReferencePanel } from "./AnalysisPanels";
import { DashboardResponse } from "../types/overview";
import { PeerRelativeStrength } from "../services/types/dto/analysis";

const mockNavigate = jest.fn();
jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
}), { virtual: true });

let mockDashboardData: DashboardResponse;
jest.mock("../hooks/api/useApi", () => ({
  useFetch: () => ({
    data: mockDashboardData,
    isLoading: false,
    isError: false,
    error: null,
  }),
}));

jest.mock("../services/apiClient", () => ({
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

jest.mock("@tanstack/react-query", () => ({
  useQueryClient: () => ({ invalidateQueries: jest.fn() }),
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

function sampleDashboard(headwind = false): DashboardResponse {
  return {
    snapshotMeta: { marketDate: "2026-11-17", sources: ["INTERNAL_MARKET_SNAPSHOT", "CNN_FEAR_GREED"] },
    tapeRows: [{ symbol: "SPY", value: "590.5", change: "+0.85%", positive: true }],
    indexRows: [{ nameCode: "SP500", symbol: "SPX", value: "5900", day: "+0.85%", trend: "up", noteCode: "UP" }],
    etfRows: [],
    alerts: [{ code: "NO_ALERTS", params: {} }],
    events: [],
    signalRows: [{ code: "FEAR_GREED", value: "55", statusCode: "NEUTRAL", sourceCode: "CNN" }],
    fearGreedComponents: [],
    marketSummary: {
      titleCode: "BULLISH_NON_EXTREME",
      description: { code: "SUMMARY_WITH_SENTIMENT", params: { score: "55", up: 3, down: 1, observed: 4 } },
      tag: { code: "MARKET_CLOSE", params: { date: "2026-11-17" } },
    },
    executionBias: { titleCode: "EXECUTION_BIAS", descriptionCode: "BULLISH" },
    marketRegime: {
      regime: headwind ? "BEAR" : "RISK_ON",
      vixClose: headwind ? 28.4 : 15.2,
      headwindActive: headwind,
      correctionActive: false,
      asOfDate: "2026-11-17",
    },
    sectorEtfs: [
      {
        symbol: "XLK",
        sectorCode: "TECHNOLOGY",
        closePrice: 235.4,
        return5d: 0.025,
        return20d: 0.068,
        return60d: 0.142,
        rvol20: 1.35,
        excessVsSpy5d: 0.012,
        excessVsSpy20d: 0.031,
        excessVsSpy60d: 0.055,
        tradeDate: "2026-11-17",
      },
      {
        symbol: "XLE",
        sectorCode: "ENERGY",
        closePrice: 92.1,
        return5d: -0.014,
        return20d: -0.032,
        return60d: -0.041,
        rvol20: 0.88,
        excessVsSpy5d: -0.027,
        excessVsSpy20d: -0.069,
        excessVsSpy60d: -0.128,
        tradeDate: "2026-11-17",
      },
    ],
    sectorMoneyFlow: {
      tradeDate: "2026-11-17",
      marketTotalStockCount: 3944,
      classifiedCoveragePct: 0.962,
      rows: [
        {
          industryCode: "I41",
          industryName: "Semiconductors",
          stockCount: 50,
          dollarVolume1d: 85_000_000_000,
          dollarVolume5d: 410_000_000_000,
          marketVolumeShare1d: 0.145,
          marketVolumeShare5d: 0.142,
          marketVolumeShare20d: 0.121,
          volumeShareDelta5dVs20d: 0.021,
          cmf5d: 0.185,
          cmf20d: 0.142,
          breadthAboveSma50Count: 38,
          breadthSma50Total: 50,
          breadthAboveSma50Pct: 0.76,
          breadthAboveSma200Count: 34,
          breadthSma200Total: 50,
          breadthAboveSma200Pct: 0.68,
          return20dMedian: 0.075,
          return60dMedian: 0.15,
          flowState: "INFLOW",
        },
      ],
    },
    opportunityRadar: {
      radarDate: "2026-11-17",
      marketRegime: headwind ? "BEAR" : "RISK_ON",
      headwindActive: headwind,
      valueCount: 1,
      starterCount: headwind ? 0 : 1,
      valueOpportunities: [
        {
          figi: "FIGI_MSFT",
          symbol: "MSFT",
          stockName: "Microsoft Corp",
          currency: "USD",
          trackType: "VALUE_SUPPORT",
          industryCode: "I31",
          industryName: "Software - Infrastructure",
          marketCapTier: "MEGA_LARGE",
          verdict: "DEEP_UNDERVALUED",
          fundamentalBacking: "STRONG",
          stance: headwind ? "WAIT_FOR_MARKET_STABILIZATION" : "ACCUMULATE_AT_SUPPORT",
          headwindWatchOnly: headwind,
          macroCautionActive: headwind,
          closePrice: 410,
          fairValueLow: 430,
          fairValueMid: 470,
          fairValueHigh: 520,
          discountToMid: 0.1277,
          forwardPeg: 1.35,
          forwardPe: 28.5,
          revenueGrowthYoy: 0.16,
          forwardGrowthBasis: null,
          supportLower: 400,
          supportUpper: 408,
          supportDistancePct: 0.0049,
          pullbackAnchor: null,
          pullbackAnchorPrice: null,
          protectiveStop: 390,
          firstTargetPrice: 465,
          netRiskReward: 2.65,
          planStatus: "ACTIVE",
          positionFraction: null,
          nextEarningsDate: "2026-12-28",
          daysToNextEarnings: 41,
          sectorCmf20d: 0.12,
          sectorFlowState: "INFLOW",
        },
      ],
      starterOpportunities: headwind ? [] : [
        {
          figi: "FIGI_NVDA",
          symbol: "NVDA",
          stockName: "NVIDIA Corp",
          currency: "USD",
          trackType: "TREND_PULLBACK_STARTER",
          industryCode: "I41",
          industryName: "Semiconductors",
          marketCapTier: "MEGA_LARGE",
          verdict: "FAIR_UPPER",
          fundamentalBacking: "STRONG",
          stance: "TREND_PULLBACK_STARTER",
          headwindWatchOnly: false,
          macroCautionActive: false,
          closePrice: 142,
          fairValueLow: 110,
          fairValueMid: 130,
          fairValueHigh: 150,
          discountToMid: -0.0923,
          forwardPeg: null,
          forwardPe: 31.2,
          revenueGrowthYoy: 0.55,
          forwardGrowthBasis: "HYPER_GROWTH_FORWARD_PE",
          supportLower: null,
          supportUpper: null,
          supportDistancePct: 0.012,
          pullbackAnchor: "SMA50",
          pullbackAnchorPrice: 140.3,
          protectiveStop: 136.5,
          firstTargetPrice: null,
          netRiskReward: null,
          planStatus: null,
          positionFraction: "1/3",
          nextEarningsDate: "2027-02-20",
          daysToNextEarnings: 95,
          sectorCmf20d: 0.142,
          sectorFlowState: "INFLOW",
        },
      ],
    },
    signalTrackingStats: {
      totalSignals: 12,
      pendingCount: 2,
      settled20dCount: 10,
      overallWinRate20d: 0.7,
      overallAvgReturn20d: 0.064,
      overallTargetHitRate: 0.75,
      bySignalType: [
        {
          groupKey: "ACCUMULATE_AT_SUPPORT",
          groupLabel: "ACCUMULATE_AT_SUPPORT",
          totalSignals: 8,
          settled5dCount: 8,
          avgReturn5d: 0.021,
          settled20dCount: 7,
          avgReturn20d: 0.058,
          winRate20d: 0.714,
          settled60dCount: 5,
          avgReturn60d: 0.115,
          resolvedOutcomeCount: 6,
          targetHitFirstCount: 5,
          stopHitFirstCount: 1,
          targetHitRate: 0.8333,
          avgMfe60d: 0.16,
          avgMae60d: -0.04,
        },
      ],
      byMarketRegime: [
        {
          groupKey: "RISK_ON",
          groupLabel: "RISK_ON",
          totalSignals: 10,
          settled5dCount: 10,
          avgReturn5d: 0.025,
          settled20dCount: 8,
          avgReturn20d: 0.072,
          winRate20d: 0.75,
          settled60dCount: 6,
          avgReturn60d: 0.13,
          resolvedOutcomeCount: 8,
          targetHitFirstCount: 6,
          stopHitFirstCount: 2,
          targetHitRate: 0.75,
          avgMfe60d: 0.18,
          avgMae60d: -0.035,
        },
      ],
      byIndustry: [],
      byMarketCapTier: [],
    },
  };
}

describe("Batch 03 trilingual locales & HomeDashboard / PeerRelativeStrength rendering", () => {
  beforeEach(() => {
    mockNavigate.mockReset();
  });

  test("all Batch 03 locale namespaces have identical leaf keys and non-empty values across zh-HK, zh-CN, and en", () => {
    const sections = [
      "dashboard.marketRegime",
      "dashboard.sectorEtfs",
      "dashboard.sectorFlow",
      "dashboard.radar",
      "dashboard.signalStats",
      "analysisReport.peerRelativeStrength",
    ];
    for (const section of sections) {
      const hkObj = getByPath(zhHK as Record<string, unknown>, section);
      const cnObj = getByPath(zhCN as Record<string, unknown>, section);
      const enObj = getByPath(en as Record<string, unknown>, section);
      const hkKeys = collectLeafKeys(hkObj);
      const cnKeys = collectLeafKeys(cnObj);
      const enKeys = collectLeafKeys(enObj);
      expect(hkKeys.length).toBeGreaterThan(0);
      expect(cnKeys).toEqual(hkKeys);
      expect(enKeys).toEqual(hkKeys);
      for (const leaf of hkKeys) {
        expect(String(getByPath(hkObj as Record<string, unknown>, leaf) ?? "").trim().length).toBeGreaterThan(0);
        expect(String(getByPath(cnObj as Record<string, unknown>, leaf) ?? "").trim().length).toBeGreaterThan(0);
        expect(String(getByPath(enObj as Record<string, unknown>, leaf) ?? "").trim().length).toBeGreaterThan(0);
      }
    }
  });

  test.each(["zh-HK", "zh-CN", "en"] as const)(
    "renders dual-track radar, 11 sector ETFs, sector flow, and signal stats cleanly in %s without leaking raw enums",
    (lng) => {
      mockDashboardData = sampleDashboard(false);
      const i18nInstance = createRealI18n(lng);
      render(
        <I18nextProvider i18n={i18nInstance}>
          <HomeDashboard />
        </I18nextProvider>
      );

      // 1) Radar value tab + click navigation
      const radarSection = screen.getByRole("region", { name: String(i18nInstance.t("dashboard.radar.title")) });
      expect(within(radarSection).getByText("MSFT")).toBeInTheDocument();
      expect(within(radarSection).queryByText("ACCUMULATE_AT_SUPPORT")).not.toBeInTheDocument();
      expect(within(radarSection).queryByText("INFLOW")).not.toBeInTheDocument();
      fireEvent.click(within(radarSection).getByText("MSFT"));
      expect(mockNavigate).toHaveBeenCalledWith("/stockAnalysis/FIGI_MSFT");

      // 2) Setting min Net R/R on VALUE tab must NOT wipe out STARTER items when switching tabs
      const minRrInput = within(radarSection).getByLabelText(String(i18nInstance.t("dashboard.radar.filterMinRr")));
      fireEvent.change(minRrInput, { target: { value: "2" } });
      expect(within(radarSection).getByText("MSFT")).toBeInTheDocument();

      fireEvent.click(within(radarSection).getByRole("tab", {
        name: String(i18nInstance.t("dashboard.radar.tabStarter", { count: 1 })),
      }));
      expect(within(radarSection).getByText("NVDA")).toBeInTheDocument();
      expect(within(radarSection).getByText(String(i18nInstance.t("dashboard.radar.hyperGrowthTag", { pe: "31.2" })))).toBeInTheDocument();

      // 3) Sector ETFs & Money Flow translated cleanly (including localized industry name)
      const etfSection = screen.getByRole("region", { name: String(i18nInstance.t("dashboard.sectorEtfs.title")) });
      expect(within(etfSection).getByText("XLK")).toBeInTheDocument();
      expect(within(etfSection).getByText(String(i18nInstance.t("dashboard.sectorEtfs.sectors.TECHNOLOGY")))).toBeInTheDocument();
      expect(within(etfSection).queryByText("TECHNOLOGY")).not.toBeInTheDocument();

      const flowSection = screen.getByRole("region", { name: String(i18nInstance.t("dashboard.sectorFlow.title")) });
      const localizedSemi = String(i18nInstance.t("Semiconductors", { defaultValue: "Semiconductors", nsSeparator: false, keySeparator: false }));
      expect(within(flowSection).getByText(localizedSemi)).toBeInTheDocument();
      if (lng !== "en") {
        expect(within(flowSection).queryByText("Semiconductors")).not.toBeInTheDocument();
      }

      // 4) Signal tracking stats localized group labels
      const statsSection = screen.getByRole("region", { name: String(i18nInstance.t("dashboard.signalStats.title")) });
      expect(within(statsSection).getByText(String(i18nInstance.t("dashboard.signalStats.signalTypes.ACCUMULATE_AT_SUPPORT")))).toBeInTheDocument();
      expect(within(statsSection).queryByText("ACCUMULATE_AT_SUPPORT")).not.toBeInTheDocument();
    }
  );

  test("renders localized BEAR headwind alert banner without leaking raw regime enum", () => {
    mockDashboardData = sampleDashboard(true);
    const i18nInstance = createRealI18n("zh-HK");
    render(
      <I18nextProvider i18n={i18nInstance}>
        <HomeDashboard />
      </I18nextProvider>
    );
    const banner = screen.getByTestId("market-headwind-banner");
    expect(banner).toHaveTextContent("空頭");
    expect(banner).not.toHaveTextContent("BEAR");
  });

  test.each(["zh-HK", "zh-CN", "en"] as const)(
    "renders peer relative strength 20D/60D panel and localized strength chip in %s",
    (lng) => {
      const i18nInstance = createRealI18n(lng);
      const peerRel: PeerRelativeStrength = {
        peerBasis: "INDUSTRY_MEDIAN",
        industryCode: "I41",
        industryName: "Semiconductors",
        tradeDate: "2026-11-17",
        stockReturn20d: 0.03,
        peerReturn20d: 0.08,
        spyReturn20d: 0.02,
        stockVsPeer20d: -0.05,
        peerVsSpy20d: 0.06,
        peerSampleCount20d: 45,
        stockReturn60d: 0.12,
        peerReturn60d: 0.15,
        spyReturn60d: 0.05,
        stockVsPeer60d: -0.03,
        peerVsSpy60d: 0.10,
        peerSampleCount60d: 45,
        sectorCmf20d: 0.142,
        sectorFlowState: "INFLOW",
        breadthAboveSma50Pct: 0.76,
        breadthAboveSma200Pct: 0.68,
        relativeLabelCode: "SECTOR_STRONG_STOCK_LAGGING",
      };
      render(
        <I18nextProvider i18n={i18nInstance}>
          <ValuationReferencePanel
            title="Peer Reference"
            description="Peer comparison"
            reference={{
              kind: "PEER_MARKET",
              metricCode: "PE",
              status: "AVAILABLE",
              reasonCode: null,
              multipleLow: 20,
              multipleMedian: 25,
              multipleHigh: 32,
              priceLow: 100,
              priceMedian: 125,
              priceHigh: 160,
              sampleCount: 45,
            }}
            currency="USD"
            figi="FIGI_NVDA"
            symbol="NVDA"
            peerRelativeStrength={peerRel}
          />
        </I18nextProvider>
      );
      expect(screen.getByText(String(i18nInstance.t("analysisReport.peerRelativeStrength.labels.SECTOR_STRONG_STOCK_LAGGING")))).toBeInTheDocument();
      expect(screen.queryByText("SECTOR_STRONG_STOCK_LAGGING")).not.toBeInTheDocument();
      expect(screen.getByTestId("peer-relative-strength-panel")).toHaveTextContent("2026-11-17");
    }
  );

  test("renders peer relative strength panel independently even when peer valuation multiple is INSUFFICIENT_DATA", () => {
    const i18nInstance = createRealI18n("zh-HK");
    const peerRel: PeerRelativeStrength = {
      peerBasis: "INDUSTRY_MEDIAN",
      industryCode: "I41",
      industryName: "Semiconductors",
      tradeDate: "2026-11-17",
      stockReturn20d: 0.05,
      peerReturn20d: 0.02,
      spyReturn20d: 0.01,
      stockVsPeer20d: 0.03,
      peerVsSpy20d: 0.01,
      peerSampleCount20d: 45,
      stockReturn60d: 0.14,
      peerReturn60d: 0.10,
      spyReturn60d: 0.04,
      stockVsPeer60d: 0.04,
      peerVsSpy60d: 0.06,
      peerSampleCount60d: 45,
      sectorCmf20d: 0.11,
      sectorFlowState: "INFLOW",
      breadthAboveSma50Pct: 0.65,
      breadthAboveSma200Pct: 0.60,
      relativeLabelCode: "SECTOR_STRONG_STOCK_LEADING",
    };
    render(
      <I18nextProvider i18n={i18nInstance}>
        <ValuationReferencePanel
          title="Peer Reference"
          description="Peer comparison"
          reference={{
            kind: "PEER_MARKET",
            metricCode: "PE",
            status: "INSUFFICIENT_DATA",
            reasonCode: "PEER_SAMPLE_TOO_SMALL",
            multipleLow: null,
            multipleMedian: null,
            multipleHigh: null,
            priceLow: null,
            priceMedian: null,
            priceHigh: null,
            sampleCount: 1,
          }}
          currency="USD"
          figi="FIGI_TEST"
          symbol="TEST"
          peerRelativeStrength={peerRel}
        />
      </I18nextProvider>
    );
    const panel = screen.getByTestId("peer-relative-strength-panel");
    expect(panel).toBeInTheDocument();
    expect(panel).toHaveTextContent("2026-11-17");
    expect(within(panel).getByText(String(i18nInstance.t("analysisReport.peerRelativeStrength.labels.SECTOR_STRONG_STOCK_LEADING")))).toBeInTheDocument();
  });

  test("renders peer relative strength panel even when valuation reference is completely null", () => {
    const i18nInstance = createRealI18n("zh-HK");
    const peerRel: PeerRelativeStrength = {
      peerBasis: "INDUSTRY_MEDIAN",
      industryCode: "I41",
      industryName: "Semiconductors",
      tradeDate: "2026-11-17",
      stockReturn20d: 0.05,
      peerReturn20d: 0.02,
      spyReturn20d: 0.01,
      stockVsPeer20d: 0.03,
      peerVsSpy20d: 0.01,
      peerSampleCount20d: 45,
      stockReturn60d: 0.14,
      peerReturn60d: 0.10,
      spyReturn60d: 0.04,
      stockVsPeer60d: 0.04,
      peerVsSpy60d: 0.06,
      peerSampleCount60d: 45,
      sectorCmf20d: 0.11,
      sectorFlowState: "INFLOW",
      breadthAboveSma50Pct: 0.65,
      breadthAboveSma200Pct: 0.60,
      relativeLabelCode: "SECTOR_STRONG_STOCK_LEADING",
    };
    render(
      <I18nextProvider i18n={i18nInstance}>
        <ValuationReferencePanel
          title="Peer Reference"
          description="Peer comparison"
          reference={null}
          currency="USD"
          figi="FIGI_NOVAL"
          symbol="NOVAL"
          peerRelativeStrength={peerRel}
        />
      </I18nextProvider>
    );
    expect(screen.getByTestId("peer-relative-strength-panel")).toBeInTheDocument();
  });

  test("toggles sector money flow between Top 20 and Bottom 20 keeping nulls last and adapting dollar volume labels", () => {
    const base = sampleDashboard(false);
    base.sectorMoneyFlow = {
      tradeDate: "2026-11-17",
      marketTotalStockCount: 3944,
      classifiedCoveragePct: 0.962,
      rows: [
        {
          industryCode: "I_STRONG",
          industryName: "StrongSector",
          stockCount: 20,
          dollarVolume1d: 50_000_000_000,
          dollarVolume5d: 250_000_000_000,
          marketVolumeShare1d: 0.10,
          marketVolumeShare5d: 0.10,
          marketVolumeShare20d: 0.08,
          volumeShareDelta5dVs20d: 0.02,
          cmf5d: 0.20,
          cmf20d: 0.25,
          breadthAboveSma50Count: 18,
          breadthSma50Total: 20,
          breadthAboveSma50Pct: 0.9,
          breadthAboveSma200Count: 16,
          breadthSma200Total: 20,
          breadthAboveSma200Pct: 0.8,
          return20dMedian: 0.10,
          return60dMedian: 0.20,
          flowState: "INFLOW",
        },
        {
          industryCode: "I_WEAK",
          industryName: "WeakSector",
          stockCount: 15,
          dollarVolume1d: 10_000_000_000,
          dollarVolume5d: 50_000_000_000,
          marketVolumeShare1d: 0.02,
          marketVolumeShare5d: 0.02,
          marketVolumeShare20d: 0.05,
          volumeShareDelta5dVs20d: -0.03,
          cmf5d: -0.22,
          cmf20d: -0.31,
          breadthAboveSma50Count: 2,
          breadthSma50Total: 15,
          breadthAboveSma50Pct: 0.133,
          breadthAboveSma200Count: 1,
          breadthSma200Total: 15,
          breadthAboveSma200Pct: 0.067,
          return20dMedian: -0.12,
          return60dMedian: -0.18,
          flowState: "OUTFLOW",
        },
        {
          industryCode: "I_NULL",
          industryName: "NullCmfSector",
          stockCount: 3,
          dollarVolume1d: 500_000_000,
          dollarVolume5d: 2_000_000_000,
          marketVolumeShare1d: 0.001,
          marketVolumeShare5d: 0.001,
          marketVolumeShare20d: 0.001,
          volumeShareDelta5dVs20d: null,
          cmf5d: null,
          cmf20d: null,
          breadthAboveSma50Count: 0,
          breadthSma50Total: 0,
          breadthAboveSma50Pct: null,
          breadthAboveSma200Count: 0,
          breadthSma200Total: 0,
          breadthAboveSma200Pct: null,
          return20dMedian: null,
          return60dMedian: null,
          flowState: null,
        },
      ],
    };
    mockDashboardData = base;
    const i18nInstance = createRealI18n("zh-HK");
    render(
      <I18nextProvider i18n={i18nInstance}>
        <HomeDashboard />
      </I18nextProvider>
    );

    const flowSection = screen.getByRole("region", { name: String(i18nInstance.t("dashboard.sectorFlow.title")) });
    const getRowOrder = () => {
      const bodyRows = within(flowSection).getAllByRole("row").slice(1);
      return bodyRows.map((r) => r.textContent ?? "");
    };

    // 預設 Top 20（20D CMF 降冪）：StrongSector -> WeakSector -> NullCmfSector（null 固定置尾）
    const topOrder = getRowOrder();
    expect(topOrder[0]).toContain("StrongSector");
    expect(topOrder[1]).toContain("WeakSector");
    expect(topOrder[2]).toContain("NullCmfSector");

    // 切換至 Bottom 20（20D CMF 升冪）：WeakSector 排第 1，NullCmfSector 仍固定置尾
    fireEvent.click(within(flowSection).getByRole("button", {
      name: String(i18nInstance.t("dashboard.sectorFlow.orderBottom")),
    }));
    const bottomOrder = getRowOrder();
    expect(bottomOrder[0]).toContain("WeakSector");
    expect(bottomOrder[1]).toContain("StrongSector");
    expect(bottomOrder[2]).toContain("NullCmfSector");

    // 切換排序依據為當日成交額：Bottom 標籤切為「成交額最低 20」（不誤標為流出最多），且 NullCmfSector（最低成交額 $500M）排第 1
    fireEvent.mouseDown(within(flowSection).getByRole("combobox"));
    fireEvent.click(screen.getByRole("option", {
      name: String(i18nInstance.t("dashboard.sectorFlow.sortDollarVol1d")),
    }));
    expect(within(flowSection).getByRole("button", {
      name: String(i18nInstance.t("dashboard.sectorFlow.orderBottomDollarVol")),
    })).toBeInTheDocument();
    const bottomVolOrder = getRowOrder();
    expect(bottomVolOrder[0]).toContain("NullCmfSector");
  });
});
