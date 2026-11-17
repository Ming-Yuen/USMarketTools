import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { useQuery } from "@tanstack/react-query";
import { StockAnalysisPage } from "./StockAnalysis";
import { apiClient } from "../services/apiClient";
import { SeriesChart } from "./ValuationPanels";
import { formatNumber } from "./AnalysisPanels";

jest.mock("@tanstack/react-query", () => ({ useQuery: jest.fn() }));
jest.mock("react-router-dom", () => ({ useNavigate: () => jest.fn(), useParams: () => ({ figi: "AAPL-FIGI" }) }), { virtual: true });
jest.mock("react-i18next", () => ({ useTranslation: () => ({ t: (key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? key }) }));
jest.mock("../services/apiClient", () => ({ apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn() } }));
jest.mock("./IndustryValuationProposalPanel", () => ({ IndustryValuationProposalPanel: () => null }));

const unavailable = (code: string) => ({ status: "INSUFFICIENT_DATA", value: null, reasons: [{ code, component: "TEST", message: code }] });
const metric = (value: number | null) => ({ value, status: value == null ? "INSUFFICIENT_DATA" : "AVAILABLE", reasonCode: value == null ? "MISSING_INPUT" : null });
const reference = (kind: string, low: number, high: number) => ({
  kind, metricCode: "PE", status: "AVAILABLE", reasonCode: null, multipleLow: 10, multipleMedian: 15, multipleHigh: 20,
  priceLow: low, priceHigh: high, sampleCount: 24,
});
let report: any;

beforeEach(() => {
  jest.clearAllMocks();
  report = {
    figi: "AAPL-FIGI", symbol: "AAPL", name: "Apple", valuationDate: "2026-11-05",
    valuation: { figi: "AAPL-FIGI", valuationDate: "2026-11-05", analysisAsOf: "2026-11-05T21:00:00Z", calculatedAt: "2026-11-06T00:00:00Z",
      currency: "USD", price: 200, industryCode: "I43", modelCode: "PE", primaryMetric: "PE", status: "PARTIAL", reasonCode: "SOME_INPUTS_MISSING",
      metrics: { pe: metric(25), pb: metric(null), ps: metric(7), pfcf: metric(null), evEbitda: metric(null) },
      historicalReference: reference("HISTORICAL_MARKET", 100, 150), peerReference: reference("PEER_MARKET", 120, 160),
      industryAssumption: reference("INDUSTRY_ASSUMPTION", 130, 170) },
    financials: null, history: [], priceStructure: unavailable("NO_PRICE"), supportResistance: unavailable("NO_LEVELS"),
    marketContext: unavailable("NO_MARKET"), tradePlans: unavailable("NO_PLAN"), futuObservations: null, reasons: [],
  };
  (useQuery as jest.Mock).mockImplementation((options: any) => ({ data: options.queryKey[0] === "analysis-report" ? report : undefined,
    isLoading: false, isFetching: false, isError: false, refetch: jest.fn() }));
});

test("reads the plain report API and sends only FIGI and optional valuation date", async () => {
  render(<StockAnalysisPage />);
  const query = (useQuery as jest.Mock).mock.calls.find(([options]) => options.queryKey[0] === "analysis-report")![0];
  (apiClient.post as jest.Mock).mockResolvedValue({ data: report });
  expect(await query.queryFn()).toBe(report);
  expect(apiClient.post).toHaveBeenCalledWith("analysis/report", { figi: "AAPL-FIGI" });
  fireEvent.change(screen.getByLabelText("估值日期（留空為最新）"), { target: { value: "2026-10-30" } });
  fireEvent.click(screen.getByRole("button", { name: "查詢" }));
  const calls = (useQuery as jest.Mock).mock.calls.filter(([options]) => options.queryKey[0] === "analysis-report");
  await calls[calls.length - 1][0].queryFn();
  expect(apiClient.post).toHaveBeenLastCalledWith("analysis/report", { figi: "AAPL-FIGI", valuationDate: "2026-10-30" });
});

test("keeps actual historical and peer references separate from industry assumptions", () => {
  render(<StockAnalysisPage />);
  expect(within(screen.getByRole("region", { name: "歷史市場估值參考" })).getByText("100.00–150.00")).toBeTruthy();
  expect(within(screen.getByRole("region", { name: "同業市場估值參考" })).getByText("120.00–160.00")).toBeTruthy();
  expect(within(screen.getByRole("region", { name: "產業估值假設" })).getByText("130.00–170.00")).toBeTruthy();
  expect(screen.getByText("25.00")).toBeTruthy();
  expect(screen.queryByText("勝率")).toBeNull();
});

test("does not display unavailable values or coerce missing values into zero", () => {
  report.valuation.metrics.pe = { value: 99999, status: "INSUFFICIENT_DATA", reasonCode: "EPS_BASIS_MISSING" };
  render(<StockAnalysisPage />);
  expect(screen.queryByText("99,999.00")).toBeNull();
  expect(screen.getByText(/EPS_BASIS_MISSING/)).toBeTruthy();
  expect(formatNumber(null)).toBe("—");
  expect(formatNumber(undefined)).toBe("—");
  expect(formatNumber(0)).toBe("0.00");
});

test("displays after-close share observation time on the single daily valuation", () => {
  report.valuation.analysisAsOf = "2026-11-12T21:00:00Z";
  report.valuation.sharesObservedAt = "2026-11-13T02:10:32Z";
  render(<StockAnalysisPage />);
  expect(screen.getByText(/股數取得時間（香港）/)).toHaveTextContent("10:10");
});

test("does not show an after-close label when shares were already observed", () => {
  report.valuation.analysisAsOf = "2026-11-12T21:00:00Z";
  report.valuation.sharesObservedAt = "2026-11-12T20:00:00Z";
  render(<StockAnalysisPage />);
  expect(screen.queryByText(/收市估算使用收市後取得的股數/)).not.toBeInTheDocument();
});

test("nonpositive free cash flow displays not meaningful instead of an available negative multiple", () => {
  report.valuation.metrics.pfcf = { value: null, status: "NOT_APPLICABLE", reasonCode: "NON_POSITIVE_FCF" };
  render(<StockAnalysisPage />);
  expect(screen.getByText(/自由現金流非正，P\/FCF 不適用（N.M.）/)).toBeInTheDocument();
  expect(screen.getByText("25.00")).toBeInTheDocument();
});

test("nonpositive earnings displays loss or zero earnings as not applicable", () => {
  report.valuation.metrics.pe = { value: null, status: "NOT_APPLICABLE", reasonCode: "NON_POSITIVE_EPS" };
  render(<StockAnalysisPage />);
  expect(screen.getByText(/每股盈利非正（虧損或零盈利），PE 不適用（N.M.）/)).toBeInTheDocument();
});

test("history chart leaves gaps rather than inventing observations", () => {
  render(<SeriesChart title="測試歷史" points={[
    { date: "2026-01-01", value: 10 }, { date: "2026-02-01", value: null }, { date: "2026-03-01", value: 20 },
  ]} />);
  const path = screen.getByRole("img", { name: "測試歷史" }).querySelector("path")!.getAttribute("d")!;
  expect(path.split("M").length - 1).toBe(2);
  expect(path.includes("L")).toBe(false);
});

test("shows SEC financial source and actual TTM interval without replacing valuation date", () => {
  report.valuation.metrics.ps = { ...metric(7), financialSource: "SEC", periodStart: "2025-07-01", periodEnd: "2026-06-30" };
  render(<StockAnalysisPage />);
  expect(screen.getByText(/SEC 財務.*2025-07-01～2026-06-30/)).toBeInTheDocument();
  expect(screen.getByText("7.00")).toBeInTheDocument();
});

test("displays NOT_APPLICABLE peer and industry assumption references on historical dates and renders Futu valuation observations", () => {
  report.valuation.peerReference = {
    kind: "PEER_MARKET", metricCode: "PE", status: "NOT_APPLICABLE", reasonCode: "HISTORICAL_DATE_NOT_APPLICABLE",
    multipleLow: null, multipleMedian: null, multipleHigh: null, priceLow: null, priceHigh: null, sampleCount: null,
  };
  report.valuation.industryAssumption = {
    kind: "INDUSTRY_ASSUMPTION", metricCode: "PE", status: "NOT_APPLICABLE", reasonCode: "HISTORICAL_DATE_NOT_APPLICABLE",
    multipleLow: null, multipleMedian: null, multipleHigh: null, priceLow: null, priceHigh: null, sampleCount: null,
  };
  report.futuObservations = {
    valuation: { observationDate: "2026-11-05", observedAt: "2026-11-05T21:05:00Z", pe: 36.42, pb: 49.18, ps: null, forwardPe: null, forwardPs: null },
    earnings: [],
  };
  render(<StockAnalysisPage />);
  expect(within(screen.getByRole("region", { name: "同業市場估值參考" })).getByText("狀態：不適用")).toBeInTheDocument();
  expect(within(screen.getByRole("region", { name: "產業估值假設" })).getByText(/查詢指定歷史日期時不套用最新同業快照或當前產業假設設定/)).toBeInTheDocument();
  const futuSection = screen.getByRole("region", { name: "富途市場觀察" });
  expect(within(futuSection).getByText("P/E：36.42")).toBeInTheDocument();
  expect(within(futuSection).getByText("P/B：49.18")).toBeInTheDocument();
  expect(within(futuSection).getByText("P/S：—")).toBeInTheDocument();
});

test("places interactive price workbench at the top and supports preset switching, wheel zoom, button zoom, and reset", () => {
  const bars = Array.from({ length: 40 }, (_, i) => {
    const day = String((i % 28) + 1).padStart(2, "0");
    const month = i < 20 ? "09" : "10";
    const price = 150 + i;
    return {
      sessionDate: `2026-${month}-${day}`,
      open: price - 1, high: price + 2, low: price - 2, close: price,
      volume: { status: "AVAILABLE", value: 12000000, reasons: [] },
    };
  });
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: {
      priceDataThrough: "2026-10-20",
      timeframes: [{ timeframe: "DAILY", bars, majorTrend: { status: "AVAILABLE", value: { state: "DOWNTREND" }, reasons: [] }, atr14: { status: "AVAILABLE", value: { atr: 9.85 }, reasons: [] } }],
    },
  };
  report.marketContext = {
    status: "AVAILABLE", reasons: [],
    value: {
      momentum: { volumeVs20Day: metric(1.2), return20: metric(-0.12), return60: metric(-0.18), drawdownFrom252High: metric(-0.34) },
      momentumDetail: {
        indicators: { SMA20: metric(195.64), SMA50: metric(201.1), SMA200: metric(204.02), RSI14: metric(22.72), MACD: metric(-15.38), MACD_HISTOGRAM: metric(-4.22), ATR14: metric(9.85) },
        interpretations: {},
        series: bars.map(b => ({ sessionDate: b.sessionDate, sma20: b.close - 2, sma50: b.close - 4, sma200: b.close + 10, bollingerUpper: b.close + 6, bollingerLower: b.close - 6, rsi14: 28, macd: -5, macdSignal: -3, macdHistogram: -2 })),
      },
    },
  };
  render(<StockAnalysisPage />);

  const chartSection = screen.getByRole("region", { name: "股價與技術指標互動圖表" });
  const histRefSection = screen.getByRole("region", { name: "歷史市場估值參考" });
  // 驗證股價與技術指標圖表位於估值參考區與歷史表之前（置頂呈現）
  expect(chartSection.compareDocumentPosition(histRefSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

  expect(within(chartSection).getByText(/共 40／40 根/)).toBeInTheDocument();

  // 點擊「放大 ＋」按鈕可縮小可視日期視窗
  fireEvent.click(within(chartSection).getByRole("button", { name: "放大日期區間" }));
  expect(within(chartSection).getByText(/共 29／40 根/)).toBeInTheDocument();

  // 在主圖觸發滑鼠滾輪向上（deltaY < 0）進一步放大日期區間
  const mainSvg = within(chartSection).getByRole("img", { name: "每日收盤價" });
  fireEvent.wheel(mainSvg, { deltaY: -120, clientX: 500 });
  expect(within(chartSection).getByText(/共 23／40 根/)).toBeInTheDocument();

  // 點擊「重設」恢復預設完整區間
  fireEvent.click(within(chartSection).getByRole("button", { name: "重設圖表縮放" }));
  expect(within(chartSection).getByText(/共 40／40 根/)).toBeInTheDocument();
});

test("separates vertically adjacent support and resistance labels on the chart so text never overlaps", () => {
  const bars = Array.from({ length: 20 }, (_, i) => ({
    sessionDate: `2026-10-${String(i + 1).padStart(2, "0")}`,
    open: 340 + i, high: 365, low: 325, close: 350 + (i % 3),
    volume: { status: "AVAILABLE", value: 5000000, reasons: [] },
  }));
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: {
      priceDataThrough: "2026-10-20",
      timeframes: [{ timeframe: "DAILY", bars, majorTrend: { status: "AVAILABLE", value: { state: "UPTREND" }, reasons: [] }, atr14: { status: "AVAILABLE", value: { atr: 5 }, reasons: [] } }],
    },
  };
  report.supportResistance = {
    status: "AVAILABLE", reasons: [],
    value: {
      supports: [
        { id: "sup-1", timeframe: "DAILY", role: "SUPPORT", lower: 334.5, upper: 335.5, center: 335.0, effectiveStrength: 40, strengthLabel: "MEDIUM", state: "ACTIVE", distanceLowerPercent: -4, distanceUpperPercent: -3.5 },
        { id: "sup-2", timeframe: "WEEKLY", role: "SUPPORT", lower: 334.4, upper: 335.4, center: 334.9, effectiveStrength: 35, strengthLabel: "MEDIUM", state: "ACTIVE", distanceLowerPercent: -4.1, distanceUpperPercent: -3.6 },
        { id: "sup-3", timeframe: "MONTHLY", role: "SUPPORT", lower: 334.2, upper: 335.2, center: 334.7, effectiveStrength: 30, strengthLabel: "MEDIUM", state: "ACTIVE", distanceLowerPercent: -4.2, distanceUpperPercent: -3.7 },
      ],
      resistances: [],
    },
  };
  render(<StockAnalysisPage />);
  const chartSection = screen.getByRole("region", { name: "股價與技術指標互動圖表" });
  const labels = ["sup-1", "sup-2", "sup-3"].map(id => {
    const g = chartSection.querySelector(`g[data-zone-label="${id}"]`);
    const text = g?.querySelector("text");
    return Number(text?.getAttribute("y"));
  }).sort((a, b) => a - b);
  expect(labels[1] - labels[0]).toBeGreaterThanOrEqual(14);
  expect(labels[2] - labels[1]).toBeGreaterThanOrEqual(14);
});

test("displays historical 5-year sample coverage, excluded loss months, same-day peer date, and non-Point-in-Time disclosure", () => {
  report.valuation.historicalReference = {
    kind: "HISTORICAL_MARKET", metricCode: "PE", status: "AVAILABLE", reasonCode: null,
    multipleLow: 13.6, multipleMedian: 19.0, multipleHigh: 24.4, priceLow: 136.0, priceHigh: 244.0,
    sampleCount: 10, windowSampleCount: 12, excludedSampleCount: 2,
    sampleStartDate: "2025-02-28", sampleEndDate: "2026-11-05",
  };
  report.valuation.peerReference = {
    kind: "PEER_MARKET", metricCode: "PE", status: "AVAILABLE", reasonCode: null,
    multipleLow: 20.0, multipleMedian: 25.0, multipleHigh: 30.0, priceLow: 160.0, priceHigh: 240.0,
    sampleCount: 5, sampleStartDate: "2026-11-05", sampleEndDate: "2026-11-05",
  };
  render(<StockAnalysisPage />);
  const histPanel = screen.getByRole("region", { name: "歷史市場估值參考" });
  expect(within(histPanel).getByText(/有效月份：10／12 個月/)).toBeInTheDocument();
  expect(within(histPanel).getByText(/實際樣本起迄：2025-02-28 ～ 2026-11-05/)).toBeInTheDocument();
  expect(within(histPanel).getByText(/已排除月份：2 個月/)).toBeInTheDocument();
  expect(within(histPanel).getByText(/非當日投資者即時已知之 Point-in-Time 回測值/)).toBeInTheDocument();

  const peerPanel = screen.getByRole("region", { name: "同業市場估值參考" });
  expect(within(peerPanel).getByText(/同日有效公司數：5（2026-11-05）/)).toBeInTheDocument();
});

test("tracks SeriesChart hover dot via SVG screen CTM without leftward drift under wide maxHeight letterboxing", () => {
  const points = Array.from({ length: 10 }, (_, i) => ({
    date: `2026-0${i + 1}-15`.replace("010", "10"),
    value: 10 + i * 2,
  }));
  render(<SeriesChart title="字母框偏移測試" points={points} />);
  const svg = screen.getByRole("img", { name: "字母框偏移測試" }) as unknown as SVGSVGElement;
  // 模擬寬螢幕 maxHeight 造成 SVG 左側留白 e = 220px、真實縮放比例 a = 1.0，但 rect.width 達 1400px
  svg.getBoundingClientRect = () => ({ left: 0, width: 1400, top: 0, height: 280, right: 1400, bottom: 280, x: 0, y: 0, toJSON: () => ({}) } as DOMRect);
  (svg as any).getScreenCTM = () => ({ a: 1, b: 0, c: 0, d: 1, e: 220, f: 0 });

  // 滑鼠指向最後一點（SVG X = 68 + 868 = 936 -> clientX = 220 + 936 = 1156）
  fireEvent.mouseMove(svg, { clientX: 1156, clientY: 120 });
  const circles = Array.from(svg.querySelectorAll("circle"));
  expect(circles[circles.length - 1]).toHaveAttribute("r", "4.5");
  expect(circles[circles.length - 1]).toHaveAttribute("fill", "#0d47a1");
  expect(circles[5]).toHaveAttribute("r", "2.5");
});

test("hides blank UNAVAILABLE trade plan cards while surfacing active support/resistance ladder and allowing SWING horizon switch", async () => {
  report.supportResistance = {
    status: "AVAILABLE", reasons: [],
    value: {
      supports: [{ id: "sup-1", timeframe: "DAILY", role: "SUPPORT", lower: 151.53, upper: 154.25, center: 152.89, effectiveStrength: 19.78, strengthLabel: "LOW", state: "ACTIVE", distanceLowerPercent: -2.95, distanceUpperPercent: -1.21 }],
      resistances: [{ id: "res-1", timeframe: "WEEKLY", role: "RESISTANCE", lower: 205.83, upper: 218.55, center: 212.19, effectiveStrength: 42.1, strengthLabel: "MEDIUM", state: "ACTIVE", distanceLowerPercent: 31.82, distanceUpperPercent: 39.97 }],
    },
  };
  report.tradePlans = {
    status: "AVAILABLE", reasons: [],
    value: {
      contextEvaluated: true,
      plans: [{
        planId: "gated-1", horizon: "POSITION", setupType: "PULLBACK", entryRange: null, stop: null, targets: [],
        riskReward: { status: "NOT_APPLICABLE", value: null, reasons: [] }, status: "UNAVAILABLE",
        reasons: [{ code: "PLAN_SUPPORT_STRENGTH_INSUFFICIENT", component: "TRADE_PLAN", message: "支撐有效強度 1.55，低於最低要求 30，暫不提供技術進出場價位。" }],
      }],
    },
  };
  render(<StockAnalysisPage />);
  const levelsSection = screen.getByRole("region", { name: "關鍵價位與技術觀察方案" });
  expect(within(levelsSection).getByText(/目前無自動啟用之右側進場方案/)).toBeInTheDocument();
  expect(within(levelsSection).queryByText("進場區間：—")).not.toBeInTheDocument();
  expect(within(levelsSection).getByText("151.53–154.25")).toBeInTheDocument();
  expect(within(levelsSection).getByText("205.83–218.55")).toBeInTheDocument();

  fireEvent.click(within(levelsSection).getByRole("button", { name: "短線波段（日線支撐）" }));
  const calls = (useQuery as jest.Mock).mock.calls.filter(([options]) => options.queryKey[0] === "analysis-report");
  (apiClient.post as jest.Mock).mockResolvedValue({ data: report });
  await calls[calls.length - 1][0].queryFn();
  expect(apiClient.post).toHaveBeenLastCalledWith("analysis/report", { figi: "AAPL-FIGI", horizon: "SWING" });
});

test("keeps selected 1Y window when recent points are null, labels outstanding shares accurately, and renders zero SMA/MACD diffs neutrally", () => {
  // 1) SeriesChart：36 個月（前 24 月有值、近 12 月為 null），點擊「1年」後不得偷偷退回繪製整段 36 個月舊資料
  const points36 = Array.from({ length: 36 }, (_, i) => {
    const year = 2024 + Math.floor(i / 12);
    const month = String((i % 12) + 1).padStart(2, "0");
    return { date: `${year}-${month}-28`, value: i < 24 ? 15 + (i % 5) : null };
  });
  const { unmount } = render(<SeriesChart title="歷史 P/E" points={points36} />);
  fireEvent.click(screen.getByRole("button", { name: "1年" }));
  expect(screen.getByText(/所選區間內可用資料不足/)).toBeInTheDocument();
  expect(screen.queryByRole("img", { name: "歷史 P/E" })).not.toBeInTheDocument();
  unmount();

  // 2) FinancialsPanel 顯示「流通在外股數」，且 TechnicalChartWorkbench 對股價 == SMA 與 MACD 柱 == 0 顯示中性文案
  report.valuation.price = 100;
  report.financials = {
    periodEnd: "2026-09-30", availableAt: "2026-11-01T00:00:00Z", reportingCurrency: "USD",
    sharesOutstanding: { value: 14532182000, status: "AVAILABLE", reasonCode: null, calculation: "富途最新流通在外股數", periodStart: null, periodEnd: "2026-09-30", availableAt: "2026-11-05T21:00:00Z" },
  };
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: { priceDataThrough: "2026-11-05", timeframes: [{ timeframe: "DAILY", bars: [], majorTrend: { status: "AVAILABLE", value: { state: "RANGE" }, reasons: [] } }] },
  };
  report.marketContext = {
    status: "AVAILABLE", reasons: [],
    value: {
      momentum: { volumeVs20Day: metric(1), return20: metric(0), return60: metric(0), drawdownFrom252High: metric(0) },
      momentumDetail: {
        indicators: { SMA20: metric(100), SMA50: metric(100), SMA200: metric(100), RSI14: metric(50), MACD: metric(0), MACD_HISTOGRAM: metric(0), ATR14: metric(2) },
        interpretations: {},
        series: [],
      },
    },
  };
  render(<StockAnalysisPage />);
  expect(within(screen.getByRole("region", { name: "估值使用的財務數據" })).getByText("流通在外股數")).toBeInTheDocument();
  expect(screen.queryByText(/高於 \+0\.00%/)).not.toBeInTheDocument();
  expect(screen.getAllByText(/持平 0\.00%/).length).toBeGreaterThanOrEqual(1);
  expect(screen.queryByText(/多方柱/)).not.toBeInTheDocument();
  expect(screen.getByText(/柱狀：0\.00（中性）/)).toBeInTheDocument();
});
