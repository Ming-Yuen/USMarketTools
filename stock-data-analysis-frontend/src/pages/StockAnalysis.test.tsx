import React from "react";
import "@testing-library/jest-dom";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { useQuery } from "@tanstack/react-query";
import i18next from "i18next";
import { StockAnalysisPage } from "./StockAnalysis";
import { apiClient } from "../services/apiClient";
import { SeriesChart } from "./ValuationPanels";
import { formatNumber } from "./AnalysisPanels";
import enLocale from "../locales/en.json";
import zhHkLocale from "../locales/zh-HK.json";
import zhCnLocale from "../locales/zh-CN.json";

const mockInvalidateQueries = jest.fn().mockResolvedValue(undefined);
const defaultT = (key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? key;
const mockUseTranslation = jest.fn(() => ({ t: defaultT }));
jest.mock("@tanstack/react-query", () => ({
  useQuery: jest.fn(),
  useQueryClient: () => ({ invalidateQueries: mockInvalidateQueries }),
}));
jest.mock("react-router-dom", () => ({ useNavigate: () => jest.fn(), useParams: () => ({ figi: "AAPL-FIGI" }) }), { virtual: true });
jest.mock("react-i18next", () => ({ useTranslation: () => mockUseTranslation() }));
jest.mock("../services/apiClient", () => ({ apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() } }));
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
  mockUseTranslation.mockImplementation(() => ({ t: defaultT }));
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

test("does not clamp 1Y preset backward across multi-year gaps and preserves 1Y window while editing history date draft", () => {
  const sparsePoints = [
    { date: "2022-04-28", value: 12 },
    { date: "2022-07-28", value: 14 },
    { date: "2022-10-28", value: 13 },
    { date: "2023-01-28", value: 15 },
    { date: "2023-04-28", value: 16 },
    { date: "2026-03-31", value: 20 },
  ];
  const { unmount } = render(<SeriesChart title="稀疏歷史 P/E" points={sparsePoints} />);
  fireEvent.click(screen.getByRole("button", { name: "1年" }));
  expect(screen.getByText(/顯示區間：2026-03-31 ～ 2026-03-31/)).toBeInTheDocument();
  expect(screen.getByText(/所選區間內可用資料不足/)).toBeInTheDocument();
  expect(screen.queryByRole("img", { name: "稀疏歷史 P/E" })).not.toBeInTheDocument();
  unmount();

  report.history = Array.from({ length: 12 }, (_, i) => {
    const year = i < 6 ? 2024 : 2026;
    const month = String((i % 6) + 1).padStart(2, "0");
    const valid = i < 5;
    return {
      ...report.valuation,
      valuationDate: `${year}-${month}-28`,
      price: 100 + i,
      status: valid ? "AVAILABLE" : "INSUFFICIENT_DATA",
      metrics: {
        ...report.valuation.metrics,
        pe: valid
          ? { value: 18 + i, status: "AVAILABLE", reasonCode: null, source: "FUTU", periodStart: "2023-01-01", periodEnd: "2023-12-31" }
          : { value: null, status: "NOT_APPLICABLE", reasonCode: "NON_POSITIVE_EPS", source: "FUTU", periodStart: "2025-01-01", periodEnd: "2025-12-31" },
      },
    };
  });
  render(<StockAnalysisPage />);
  const historySection = screen.getByRole("region", { name: "歷史估值" });
  fireEvent.click(within(historySection).getByRole("button", { name: "1年" }));
  expect(within(historySection).getByText(/所選區間內可用資料不足/)).toBeInTheDocument();

  fireEvent.change(within(historySection).getByLabelText("開始日期"), { target: { value: "2024-02-01" } });
  expect(within(historySection).getByText(/所選區間內可用資料不足/)).toBeInTheDocument();
});

test("renders L1 executive summary before chart and reference panels, collapses bank N/A cards only, and supports L4 accordion expansion", () => {
  const bars = Array.from({ length: 15 }, (_, i) => ({
    sessionDate: `2026-10-${String(i + 1).padStart(2, "0")}`,
    open: 170 + i, high: 175 + i, low: 168 + i, close: 172 + i,
    volume: { status: "AVAILABLE", value: 8000000, reasons: [] },
  }));
  report.valuation = {
    ...report.valuation,
    price: 170,
    modelCode: "BANK",
    primaryMetric: "PB",
    status: "AVAILABLE",
    reasonCode: null,
    metrics: {
      pe: { value: 12.5, status: "AVAILABLE", reasonCode: null },
      pb: { value: 1.65, status: "AVAILABLE", reasonCode: null },
      ps: { value: 3.1, status: "AVAILABLE", reasonCode: null },
      pfcf: { value: null, status: "NOT_APPLICABLE", reasonCode: "PFCF_NOT_APPLICABLE_TO_INDUSTRY" },
      evEbitda: { value: null, status: "NOT_APPLICABLE", reasonCode: "EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY" },
    },
    historicalReference: {
      kind: "HISTORICAL_MARKET", metricCode: "PB", status: "AVAILABLE", reasonCode: null,
      multipleLow: 1.4, multipleMedian: 1.8, multipleHigh: 2.2, priceLow: 150, priceMedian: 190, priceHigh: 230,
      sampleCount: 48, windowSampleCount: 48, excludedSampleCount: 0, currentMultiple: 1.65, currentPercentile: 35,
    },
    peerReference: {
      kind: "PEER_MARKET", metricCode: "PB", status: "AVAILABLE", reasonCode: null,
      multipleLow: 1.3, multipleMedian: 1.7, multipleHigh: 2.1, priceLow: 140, priceMedian: 180, priceHigh: 220,
      sampleCount: 8, sampleStartDate: "2026-11-05", sampleEndDate: "2026-11-05", currentMultiple: 1.65, currentPercentile: 44,
    },
    industryAssumption: {
      kind: "INDUSTRY_ASSUMPTION", metricCode: "PB", status: "AVAILABLE", reasonCode: null,
      multipleLow: 1.5, multipleMedian: null, multipleHigh: 2.0, priceLow: 160, priceMedian: 185, priceHigh: 210,
      sampleCount: null, currentMultiple: 1.65, currentPercentile: null,
    },
  };
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: { priceDataThrough: "2026-10-15", timeframes: [{ timeframe: "DAILY", bars, majorTrend: { status: "AVAILABLE", value: { state: "UPTREND" }, reasons: [] } }] },
  };
  report.marketContext = {
    status: "AVAILABLE", reasons: [],
    value: {
      momentum: { volumeVs20Day: metric(1.1), return20: metric(0.04), return60: metric(0.09), drawdownFrom252High: metric(-0.08) },
      relativeStrength: { benchmark: "SPY", excessReturn20: metric(0.025), excessReturn60: metric(0.05), reasons: [] },
      treasury: { tenYear: metric(4.35), curve10yMinus2y: metric(0.22), reasons: [] },
      tradeConstraint: { entryPermission: "ALLOW", reasonCodes: [] },
      reasons: [],
      momentumDetail: { indicators: { SMA20: metric(168), SMA50: metric(162), SMA200: metric(155), RSI14: metric(54), MACD: metric(1.2), MACD_HISTOGRAM: metric(0.4), ATR14: metric(3.2) }, interpretations: {}, series: [] },
    },
  };
  report.fundamentals = {
    periodEnd: "2026-09-30", priorYearPeriodEnd: "2025-09-30", threeYearAgoPeriodEnd: "2023-09-30",
    reportingCurrency: "USD", financialInstitution: true,
    revenueGrowthYoy: metric(0.085), epsGrowthYoy: metric(0.124),
    fcfGrowthYoy: { status: "NOT_APPLICABLE", value: null, reasons: [{ code: "PFCF_NOT_APPLICABLE_TO_INDUSTRY", component: "FUNDAMENTALS", message: "N/A" }] },
    revenueCagr3y: metric(0.072), epsCagr3y: metric(0.11),
    trailingPeg: metric(1.14), pegGrowthBasis: "TRAILING_EPS_CAGR_3Y",
    roe: metric(0.148),
    fcfMargin: { status: "NOT_APPLICABLE", value: null, reasons: [] },
    fcfConversion: { status: "NOT_APPLICABLE", value: null, reasons: [] },
    netDebtToEbitda: { status: "NOT_APPLICABLE", value: null, reasons: [] },
    fcfYield: { status: "NOT_APPLICABLE", value: null, reasons: [] },
    earningsYield: metric(0.08),
    netShareChangeYoy: metric(-0.024),
  };
  report.summary = {
    status: "AVAILABLE",
    reasons: [],
    value: {
      verdict: "FAIR_LOWER",
      fundamentalBacking: "STRONG",
      stance: "WATCH_PULLBACK_TO_BAND",
      primaryMetric: "PB",
      anchorSource: "HISTORICAL_AND_PEER",
      fairValue: {
        low: 145, mid: 185, high: 225, currency: "USD",
        historicalLow: 150, historicalMid: 190, historicalHigh: 230,
        peerLow: 140, peerMid: 180, peerHigh: 220,
        assumptionLow: 160, assumptionMid: 185, assumptionHigh: 210,
      },
      discountToMid: 0.081081,
      upsideToMid: 0.088235,
      yieldSpreadVs10y: metric(0.0365),
      yieldSpreadBasis: "EARNINGS_YIELD_MINUS_10Y",
      technicalSupportZoneId: "Z1",
      technicalSupportRange: { lower: 162, upper: 166 },
      technicalSupportTimeframe: "WEEKLY",
      technicalSupportStrength: 62,
      weeklyTrend: "UPTREND",
      aboveSma200: true,
      entryPermission: "ALLOW",
      eventGateBlocked: true,
      nextBlockingEventDate: "2026-11-07",
      risks: [{ code: "EARNINGS_IMMINENT_BLOCK", severity: "HIGH", params: { eventDate: "2026-11-07" } }],
      watchItems: [{ code: "WATCH_REFERENCE_LOW_BAND", params: { priceLow: 145, priceMid: 185, priceHigh: 225, currency: "USD" } }],
      reasons: [],
    },
  };
  report.financials = {
    periodEnd: "2026-09-30", availableAt: "2026-11-01T00:00:00Z", reportingCurrency: "USD",
    sharesOutstanding: { value: 2850000000, status: "AVAILABLE", reasonCode: null, calculation: "SEC 申報流通股數", periodStart: null, periodEnd: "2026-09-30", availableAt: "2026-11-05T21:00:00Z" },
  };
  report.futuObservations = {
    valuation: { observationDate: "2026-11-05", observedAt: "2026-11-05T21:05:00Z", pe: 12.6, pb: 1.66, ps: 3.12, forwardPe: 11.8, forwardPs: 2.95 },
    earnings: [],
  };

  render(<StockAnalysisPage />);

  const summarySection = screen.getByRole("region", { name: "綜合估值與觀察結論" });
  const chartSection = screen.getByRole("region", { name: "股價與技術指標互動圖表" });
  const histRefSection = screen.getByRole("region", { name: "歷史市場估值參考" });
  const fundSection = screen.getByRole("region", { name: "基本面成長與品質指標" });
  const finSection = screen.getByRole("region", { name: "估值使用的財務數據" });
  const futuSection = screen.getByRole("region", { name: "富途市場觀察" });

  // 1) 驗證嚴格四層 DOM 順序：L1 置頂結論 -> L2 互動圖表 -> L3 參考與基本面 -> L4 折疊明細
  expect(summarySection.compareDocumentPosition(chartSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(chartSection.compareDocumentPosition(histRefSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(histRefSection.compareDocumentPosition(fundSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(fundSection.compareDocumentPosition(finSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

  // 2) L1 顯示綜合參考帶 [145, 185, 225]、獨立對照假設區間、10Y 利差 +3.65%、事件門鎖，且收起銀行不適用的 P/FCF 與 EV/EBITDA 大卡
  expect(within(summarySection).getByText(/位於參考帶下半部（合理偏下緣）/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/基本面支撐強勁/)).toBeInTheDocument();
  expect(within(summarySection).getByText("145.00 USD")).toBeInTheDocument();
  expect(within(summarySection).getByText(/較中樞折價 \+8\.11%/)).toBeInTheDocument();
  expect(within(summarySection).getByText("225.00 USD")).toBeInTheDocument();
  expect(within(summarySection).getByText(/低接觀察門檻（參考下緣）：145\.00 USD（中樞 185\.00）/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/人工產業假設（獨立對照，未混入參考帶）：160\.00–210\.00 USD/)).toBeInTheDocument();
  expect(within(summarySection).getByText("+3.65%")).toBeInTheDocument();
  expect(within(summarySection).getByText(/已自動收起金融機構模型不適用倍數：P\/FCF、EV\/EBITDA/)).toBeInTheDocument();
  expect(screen.queryByText("勝率")).toBeNull();

  // 3) L2 速覽顯示相對 SPY 超額報酬與 10Y 美債殖利率；L3 顯示經驗分位排名 P35、中位倍數參考價與基本面淨回購
  expect(screen.getByText(/4\.35%/)).toBeInTheDocument();
  expect(within(histRefSection).getByText(/經驗分位排名：P35/)).toBeInTheDocument();
  expect(within(histRefSection).getByText(/中位倍數參考價：190\.00 USD/)).toBeInTheDocument();
  expect(within(screen.getByRole("region", { name: "產業估值假設" })).getByText(/假設區間中點：185\.00 USD/)).toBeInTheDocument();
  expect(within(fundSection).getByText(/\+12\.40%/)).toBeInTheDocument();
  expect(within(fundSection).getByText(/14\.80%/)).toBeInTheDocument();
  expect(within(fundSection).getByText(/-2\.40%（淨回購）/)).toBeInTheDocument();

  // 4) L4 折疊抽屜預設為收合（aria-expanded="false"），點擊標題後正常展開（aria-expanded="true"）
  const finExpandBtn = within(finSection).getByRole("button", { name: /估值使用的財務數據/ });
  expect(finExpandBtn).toHaveAttribute("aria-expanded", "false");
  fireEvent.click(finExpandBtn);
  expect(finExpandBtn).toHaveAttribute("aria-expanded", "true");
  expect(within(finSection).getByText("SEC 申報流通股數")).toBeVisible();

  const futuExpandBtn = within(futuSection).getByRole("button", { name: /第三方市場觀察與分析師預期/ });
  expect(futuExpandBtn).toHaveAttribute("aria-expanded", "false");
  fireEvent.click(futuExpandBtn);
  expect(futuExpandBtn).toHaveAttribute("aria-expanded", "true");
  expect(within(futuSection).getByText("P/E：12.60")).toBeVisible();
});

test("renders containingZones support across K-line band, decision snapshot strip, and support ladder when price is inside zone", () => {
  const bars = Array.from({ length: 12 }, (_, i) => ({
    sessionDate: `2026-10-${String(i + 1).padStart(2, "0")}`,
    open: 168, high: 173, low: 166, close: 170,
    volume: { status: "AVAILABLE", value: 6000000, reasons: [] },
  }));
  report.valuation.price = 170;
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: { priceDataThrough: "2026-10-12", timeframes: [{ timeframe: "DAILY", bars, majorTrend: { status: "AVAILABLE", value: { state: "UPTREND" }, reasons: [] } }] },
  };
  report.supportResistance = {
    status: "AVAILABLE", reasons: [],
    value: {
      supports: [],
      resistances: [],
      containingZones: [
        { id: "contain-sup-1", timeframe: "WEEKLY", role: "SUPPORT", lower: 165, upper: 172, center: 168.5, effectiveStrength: 65, strengthLabel: "HIGH", state: "ACTIVE", distanceLowerPercent: -2.94, distanceUpperPercent: 1.18 },
      ],
    },
  };
  render(<StockAnalysisPage />);

  const chartSection = screen.getByRole("region", { name: "股價與技術指標互動圖表" });
  expect(chartSection.querySelector('g[data-zone-label="contain-sup-1"]')).not.toBeNull();
  expect(screen.getByText(/165\.00–172\.00（處於支撐區內）/)).toBeInTheDocument();

  const levelsSection = screen.getByRole("region", { name: "關鍵價位與技術觀察方案" });
  expect(within(levelsSection).getByText("165.00–172.00")).toBeInTheDocument();
  expect(within(levelsSection).getByText("處於區間內")).toBeInTheDocument();
});

test("renders peer tiering scope, full-industry subtitle, peer fundamental medians, custom peer dialog save & cache invalidation, forward multiples, and value-trap alerts", async () => {
  const refetchMock = jest.fn();
  (useQuery as jest.Mock).mockImplementation((options: any) => ({
    data: options.queryKey[0] === "analysis-report" ? report : undefined,
    isLoading: false, isFetching: false, isError: false, refetch: refetchMock,
  }));

  report.valuation.peerReference = {
    kind: "PEER_MARKET", metricCode: "PE", status: "AVAILABLE", reasonCode: null,
    multipleLow: 18, multipleMedian: 22, multipleHigh: 28, priceLow: 144, priceMedian: 176, priceHigh: 224,
    sampleCount: 6, sampleStartDate: "2026-11-05", sampleEndDate: "2026-11-05",
    currentMultiple: 14, currentPercentile: 18,
    peerScope: "SAME_TIER", targetMarketCapTier: "MEGA_LARGE", peerFallbackReason: null,
    industryAllMetricCode: "PE", industryAllMedianMultiple: 16.5, industryAllSampleCount: 42,
    peerRevenueGrowthYoyMedian: 0.142, peerRoeMedian: 0.245, peerFcfMarginMedian: 0.185,
    peerRevenueGrowthCount: 6, peerRoeCount: 6, peerFcfMarginCount: 6,
    warningCodes: ["CUSTOM_PEER_GROWTH_DIVERGENCE"],
  };
  report.fundamentals = {
    periodEnd: "2026-09-30", priorYearPeriodEnd: "2025-09-30", threeYearAgoPeriodEnd: "2023-09-30",
    reportingCurrency: "USD", financialInstitution: false,
    revenueGrowthYoy: metric(0.05), epsGrowthYoy: metric(0.04), fcfGrowthYoy: metric(0.02),
    revenueCagr3y: metric(0.08), epsCagr3y: metric(0.09), trailingPeg: metric(1.55), pegGrowthBasis: "TRAILING_EPS_CAGR_3Y",
    roe: metric(0.19), fcfMargin: metric(0.14), fcfConversion: metric(0.95), netDebtToEbitda: metric(0.8),
    fcfYield: metric(0.05), earningsYield: metric(0.0714), netShareChangeYoy: metric(-0.01),
  };
  report.summary = {
    status: "AVAILABLE",
    reasons: [],
    value: {
      verdict: "MODERATELY_UNDERVALUED",
      fundamentalBacking: "WEAKENING",
      stance: "CAUTION_VALUE_TRAP",
      primaryMetric: "PE",
      anchorSource: "HISTORICAL_AND_PEER",
      fairValue: { low: 122, mid: 163, high: 187, currency: "USD", historicalLow: 100, historicalMid: 150, historicalHigh: 150, peerLow: 144, peerMid: 176, peerHigh: 224 },
      discountToMid: 0.1411, upsideToMid: 0.1643,
      yieldSpreadVs10y: metric(0.008), yieldSpreadBasis: "FCF_YIELD_MINUS_10Y",
      aboveSma200: true, entryPermission: "ALLOW", eventGateBlocked: false,
      risks: [
        { code: "FORWARD_EARNINGS_CONTRACTION", severity: "HIGH", params: { impliedEpsGrowth: -0.24, fiscalPeriod: "2026" } },
        { code: "EARNINGS_FORECAST_DOWNGRADE_90D", severity: "HIGH", params: { epsRevision90d: -0.135, fiscalPeriod: "2026Q4" } },
      ],
      watchItems: [],
      reasons: [],
    },
  };
  report.futuObservations = {
    valuation: { observationDate: "2026-11-05", observedAt: "2026-11-05T21:05:00Z", pe: 14.1, pb: 5.2, ps: 3.8, forwardPe: null, forwardPs: null },
    earnings: [],
    forward: {
      valuationDate: "2026-11-05",
      targetFiscalPeriod: null,
      forecastObservationDate: null,
      forecastCurrency: null,
      forwardPe: unavailable("ANNUAL_EARNINGS_FORECAST_MISSING"),
      forwardPs: unavailable("ANNUAL_EARNINGS_FORECAST_MISSING"),
      impliedEpsGrowth: unavailable("ANNUAL_EARNINGS_FORECAST_MISSING"),
      forwardPeg: unavailable("ANNUAL_EARNINGS_FORECAST_MISSING"),
      forwardPegBasis: null,
      revisionFiscalPeriod: "2026Q4",
      revisionPeriodType: "QUARTERLY",
      revisionCurrency: "USD",
      revisionObservationDate: "2026-11-05",
      revisionBaseDate30d: "2026-10-06",
      revisionBaseDate90d: "2026-08-08",
      revenueRevisionBaseDate30d: "2026-10-10",
      revenueRevisionBaseDate90d: null,
      epsRevision30dBasis: "LOCAL_HISTORY",
      epsRevision90dBasis: "LOCAL_HISTORY",
      epsRevision30d: { status: "AVAILABLE", value: -0.045, reasons: [] },
      epsRevision90d: { status: "AVAILABLE", value: -0.135, reasons: [] },
      revenueRevision30d: { status: "AVAILABLE", value: -0.02, reasons: [] },
      revenueRevision90d: unavailable("FORECAST_REVISION_BASE_MISSING"),
    },
  };

  let failFirstCustomLoad = true;
  (apiClient.get as jest.Mock).mockImplementation(async (url: string) => {
    if (url.includes("/custom-peers")) {
      if (failFirstCustomLoad) {
        failFirstCustomLoad = false;
        throw new Error("NETWORK_ERROR");
      }
      return {
        data: {
          figi: "AAPL-FIGI", preferredMetric: "PE",
          supportedMetrics: ["PE", "PS", "PFCF", "EV_EBITDA", "PB"],
          targetMarketCap: 3500000000000,
          targetCurrency: "USD",
          targetValuationDate: "2026-11-05",
          targetPrimaryMetric: "PE",
          targetIssuerKey: "0000320193",
          peers: [
            { figi: "AAPL-SIB", symbol: "AAPLB", name: "Apple Sibling", industryCode: "I43", active: true, tracked: true, valuationDate: "2026-11-05", currency: "USD", marketCap: 3500000000000, marketCapTier: "MEGA_LARGE", issuerKey: "0000320193", pe: 30, pb: 40, ps: 8, pfcf: 32, evEbitda: 25 },
            { figi: "GOOG-F", symbol: "GOOG", name: "Alphabet C", industryCode: "I43", active: true, tracked: true, valuationDate: "2026-11-05", currency: "USD", marketCap: 2200000000000, marketCapTier: "MEGA_LARGE", issuerKey: "0001652044", pe: 22, pb: 6, ps: 6, pfcf: 28, evEbitda: 15 },
            { figi: "GOOGL-F", symbol: "GOOGL", name: "Alphabet A", industryCode: "I43", active: true, tracked: true, valuationDate: "2026-11-05", currency: "USD", marketCap: 2220000000000, marketCapTier: "MEGA_LARGE", issuerKey: "0001652044", pe: 22.2, pb: 6.1, ps: 6.1, pfcf: 28.2, evEbitda: 15.1 },
            { figi: "SMALL-F", symbol: "SMCO", name: "Small Co", industryCode: "I43", active: true, tracked: true, valuationDate: "2026-11-05", currency: "USD", marketCap: 5000000000, marketCapTier: "MID", issuerKey: "0000009999", pe: 18, pb: 2, ps: 2, pfcf: null, evEbitda: 12 },
            { figi: "CAD-F", symbol: "CADC", name: "Cad Co", industryCode: "I43", active: true, tracked: true, valuationDate: "2026-11-04", currency: "CAD", marketCap: 100000000, marketCapTier: "SMALL", issuerKey: "0000008888", pe: 14, pb: 2, ps: 2, pfcf: 15, evEbitda: 9 },
          ],
        },
      };
    }
    return {
      data: {
        items: [{ figi: "AMZN-F", symbol: "AMZN", name: "Amazon", stockType: "CS", industryCode: "I20", industry: "零售", concepts: [], quoteDate: "2026-11-05", closePrice: 210, currency: "USD", valuationDate: "2026-11-05", marketCap: 2100000000000, marketCapTier: "MEGA_LARGE", pe: 35, pb: 8, ps: 3.5, pfcf: 40, evEbitda: 18, status: "AVAILABLE", reasonCode: null, issuerKey: "0001018724" }],
        total: 1, page: 0, size: 10,
      },
    };
  });
  (apiClient.put as jest.Mock).mockResolvedValue({ data: {} });

  render(<StockAnalysisPage />);

  // 1) L1 顯示價值陷阱警訊（CAUTION_VALUE_TRAP + 前瞻共識衰退與 90 日預期下修）
  const summarySection = screen.getByRole("region", { name: "綜合估值與觀察結論" });
  expect(within(summarySection).getByText(/提防價值陷阱或盈利下修，暫緩承接/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/市場共識預期年度 EPS（2026）較當期 TTM 低 24\.00%（共識預期口徑對比）/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/近 90 日市場共識預期 EPS（2026Q4）下修 -13\.50%/)).toBeInTheDocument();

  // 2) 同業參考面板顯示「同規模同業（大型股）」標籤、全產業中位數副標、同業基本面中位數對照與成長落差提示
  const peerPanel = screen.getByRole("region", { name: "同業市場估值參考" });
  expect(within(peerPanel).getByText("同規模同業（大型股）")).toBeInTheDocument();
  expect(within(peerPanel).getByText(/全產業 P\/E 中位數副標：16\.50 倍（共 42 家，僅供對照未混入參考帶）/)).toBeInTheDocument();
  expect(within(peerPanel).getByText(/\+14\.20%/)).toBeInTheDocument();
  expect(within(peerPanel).getByText(/本股：\+5\.00%/)).toBeInTheDocument();

  // 3) 基本面顯示「缺少可用的未結束年度預期」優雅降級、單季 revisionCurrency (USD) 與單季 30D/90D 修正率
  const fundSection = screen.getByRole("region", { name: "基本面成長與品質指標" });
  expect(within(fundSection).getByText("市場預期觀察（與已公布財報分軌）")).toBeInTheDocument();
  expect(within(fundSection).getAllByText("缺少可用的未結束年度預期").length).toBeGreaterThanOrEqual(2);
  expect(within(fundSection).getByText(/2026Q4 · 單季 · USD/)).toBeInTheDocument();
  expect(within(fundSection).getByText(/-4\.50%（對比 2026-10-06）/)).toBeInTheDocument();
  expect(within(fundSection).getByText(/-2\.00%（對比 2026-10-10）/)).toBeInTheDocument();
  expect(within(fundSection).getByText(/-13\.50%（對比 2026-08-08）/)).toBeInTheDocument();

  // 4) 開啟「設定自訂對標」對話框：首次 GET 失敗時必須禁用儲存與清除按鈕，點擊「重試載入」成功後才允許編輯並透過單次 invalidateQueries 刷新
  fireEvent.click(within(peerPanel).getByRole("button", { name: "設定自訂對標" }));
  const dialog = await screen.findByRole("dialog");
  expect(await within(dialog).findByText(/為避免誤覆寫現有名單/)).toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "儲存自訂對標" })).toBeDisabled();
  expect(within(dialog).getByRole("button", { name: "清除並還原預設分層" })).toBeDisabled();

  fireEvent.click(within(dialog).getByRole("button", { name: "重試載入" }));
  expect(await within(dialog).findByText("GOOG")).toBeInTheDocument();
  // 編輯當下即時防呆提醒：AAPLB 屬同發行人兄弟股類（明確報錯且不計入有效發行人）、GOOG+GOOGL 合併計為 1 家、CADC 非當日排除 -> 僅 2 家有效獨立發行人；
  // USD 市值 10 倍警示僅列 SMCO，不誤列非 USD／不同日之 CADC
  const tooFewPattern = /目前僅有 \d+ 家當日有效獨立發行人/;
  expect(within(dialog).getByText(/所選對手（AAPLB）與本股屬同一發行人/)).toBeInTheDocument();
  expect(within(dialog).getByText(tooFewPattern)).toBeInTheDocument();
  expect(within(dialog).getByText(/目前僅有 2 家當日有效獨立發行人（P\/E）（未滿 3 家）/)).toBeInTheDocument();
  expect(within(dialog).getByText(/所選對手（SMCO）與本股 USD 市值相差逾 10 倍/)).toBeInTheDocument();
  expect(within(dialog).queryByText(/CADC.*市值相差逾 10 倍/)).not.toBeInTheDocument();

  // 存在同發行人兄弟股 AAPLB 時，「儲存自訂對標」必須禁用（不送無效 PUT），「清除並還原預設分層」維持可用
  expect(within(dialog).getByRole("button", { name: "儲存自訂對標" })).toBeDisabled();
  expect(within(dialog).getByRole("button", { name: "清除並還原預設分層" })).toBeEnabled();

  fireEvent.change(within(dialog).getByLabelText("搜尋對標股票代碼或名稱"), { target: { value: "AMZN" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "搜尋" }));
  fireEvent.click(await within(dialog).findByRole("button", { name: "加入" }));
  // 加入第 3 家獨立發行人 AMZN 後「未滿 3 家」警示即時消失，但未移除 AAPLB 前仍禁用儲存
  expect(within(dialog).queryByText(tooFewPattern)).not.toBeInTheDocument();
  expect(within(dialog).getByText(/所選對手（SMCO）與本股 USD 市值相差逾 10 倍/)).toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "儲存自訂對標" })).toBeDisabled();

  // 切換為 P/FCF（SMCO 的 pfcf 為 null）-> 有效獨立發行人降為 2 家，未滿 3 家警示即時重新出現
  fireEvent.mouseDown(within(dialog).getByLabelText("對標估值指標（選填覆寫）"));
  fireEvent.click(await screen.findByRole("option", { name: "P/FCF" }));
  expect(within(dialog).getByText(/目前僅有 2 家當日有效獨立發行人（P\/FCF）（未滿 3 家）/)).toBeInTheDocument();
  fireEvent.mouseDown(within(dialog).getByLabelText("對標估值指標（選填覆寫）"));
  fireEvent.click(await screen.findByRole("option", { name: "P/E" }));
  expect(within(dialog).queryByText(tooFewPattern)).not.toBeInTheDocument();

  // 移除同發行人兄弟股 AAPLB（清單第一筆）後錯誤警示消失、儲存按鈕恢復可用，並只送出合法清單
  fireEvent.click(within(dialog).getAllByRole("button", { name: "移除" })[0]);
  expect(within(dialog).queryByText(/所選對手（AAPLB）與本股屬同一發行人/)).not.toBeInTheDocument();
  expect(within(dialog).getByRole("button", { name: "儲存自訂對標" })).toBeEnabled();
  fireEvent.click(within(dialog).getByRole("button", { name: "儲存自訂對標" }));

  await screen.findByRole("region", { name: "同業市場估值參考" });
  expect(apiClient.put).toHaveBeenCalledWith("valuation/AAPL-FIGI/custom-peers", {
    peerFigis: ["GOOG-F", "GOOGL-F", "SMALL-F", "CAD-F", "AMZN-F"],
    preferredMetric: "PE",
  });
  expect(mockInvalidateQueries).toHaveBeenCalledWith({ queryKey: ["analysis-report", "AAPL-FIGI"] });
  expect(mockInvalidateQueries).toHaveBeenCalledWith({ queryKey: ["stock-screen"] });
  expect(refetchMock).not.toHaveBeenCalled();
});

test("renders macro safety caution without altering fairValue band, breakout diagnosis alerts, earnings AVWAP curve, and Volume Profile confluence", () => {
  const dailyBars = Array.from({ length: 15 }, (_, i) => {
    const day = String(i + 1).padStart(2, "0");
    const close = 150 + i;
    return {
      sessionDate: `2026-10-${day}`,
      open: close - 1, high: close + 2, low: close - 2, close,
      volume: { status: "AVAILABLE", value: 10000000, reasons: [] },
    };
  });
  const weeklyBars = [
    { sessionDate: "2026-10-02", open: 150, high: 155, low: 148, close: 154, volume: { status: "AVAILABLE", value: 40000000, reasons: [] } },
    { sessionDate: "2026-10-09", open: 154, high: 162, low: 152, close: 160, volume: { status: "AVAILABLE", value: 42000000, reasons: [] } },
    { sessionDate: "2026-10-15", open: 160, high: 168, low: 158, close: 164, volume: { status: "AVAILABLE", value: 41000000, reasons: [] } },
  ];
  report.valuation.price = 164;
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: {
      priceDataThrough: "2026-10-15",
      timeframes: [
        { timeframe: "DAILY", bars: dailyBars, majorTrend: { status: "AVAILABLE", value: { state: "UPTREND" }, reasons: [] }, atr14: { status: "AVAILABLE", value: { atr: 4.5 }, reasons: [] } },
        { timeframe: "WEEKLY", bars: weeklyBars, majorTrend: { status: "AVAILABLE", value: { state: "UPTREND" }, reasons: [] }, atr14: { status: "AVAILABLE", value: { atr: 8.0 }, reasons: [] } },
      ],
    },
  };
  const diag = {
    diagnosis: "HIGH_RISK_BREAKDOWN",
    zoneId: "sup-poc",
    zoneBoundsVersion: 1,
    zoneRoleVersion: 1,
    zoneTimeframe: "DAILY",
    supportLower: 155,
    supportUpper: 159,
    breachDate: "2026-10-15",
    diagnosisDate: "2026-10-15",
    barsSinceBreach: 0,
    rvolAtBreach: 1.92,
    lowerShadowRatio: 0.08,
    excessReturnOnBreach: -0.032,
    evidenceCodes: ["SOLID_BEARISH_CLOSE_NEAR_LOW", "HIGH_VOLUME_BREAKDOWN", "INDEPENDENT_WEAKNESS_VS_SPY"],
  };
  report.supportResistance = {
    status: "AVAILABLE", reasons: [],
    value: {
      supports: [
        {
          id: "sup-poc", timeframe: "DAILY", role: "SUPPORT", lower: 155, upper: 159, center: 157,
          effectiveStrength: 75, strengthLabel: "HIGH", state: "ACTIVE", distanceLowerPercent: -5.4, distanceUpperPercent: -3.0,
          retestState: "NONE", volumeProfileConfluence: "POC", confluenceTags: ["VOLUME_PROFILE_CONFLUENCE"],
        },
      ],
      resistances: [],
      containingZones: [],
      breakoutDiagnosis: diag,
      zoneDiagnoses: [diag],
    },
  };
  report.marketContext = {
    status: "AVAILABLE", reasons: [],
    value: {
      momentum: { volumeVs20Day: metric(1.92), return20: metric(-0.04), return60: metric(0.05), drawdownFrom252High: metric(-0.08) },
      market: { status: "AVAILABLE", value: { regime: "BEAR", fearGreedScore: metric(25), breadthScore: metric(30) }, reasons: [] },
      vixClose: metric(27.5),
      treasury: { tenYear: metric(4.5), tenYearChange20: metric(0.35), curve10yMinus2y: metric(0.2), reasons: [] },
      volume: {
        rvol20: metric(1.92),
        earningsEventDate: "2026-10-09",
        earningsAnchorDate: "2026-10-10",
        earningsAnchoredVwap: metric(160.25),
        earningsAnchoredVwapSeries: [
          { sessionDate: "2026-10-10", value: 159.0 },
          { sessionDate: "2026-10-11", value: 159.4 },
          { sessionDate: "2026-10-12", value: 159.8 },
          { sessionDate: "2026-10-14", value: 160.1 },
          { sessionDate: "2026-10-15", value: 160.25 },
        ],
        profile252: {
          status: "AVAILABLE", reasons: [],
          value: {
            bins: [{ index: 1, lower: 155, upper: 159, volume: 90000000 }],
            poc: { index: 1, lower: 155, upper: 159, volume: 90000000 },
            highVolumeNodes: [{ index: 1, lower: 155, upper: 159, volume: 90000000 }],
            lowVolumeNodes: [],
            approximationLabel: "DAILY_OHLCV_APPROXIMATION",
          },
        },
      },
    },
  };
  report.summary = {
    status: "AVAILABLE", reasons: [],
    value: {
      verdict: "DEEP_UNDERVALUED",
      fundamentalBacking: "STRONG",
      stance: "WAIT_FOR_MARKET_STABILIZATION",
      primaryMetric: "PE",
      anchorSource: "HISTORICAL_ONLY",
      fairValue: { low: 180, mid: 210, high: 240, currency: "USD" },
      discountToMid: 0.219,
      upsideToMid: 0.28,
      yieldSpreadVs10y: metric(0.02),
      yieldSpreadBasis: "FCF_YIELD_MINUS_10Y",
      technicalSupportZoneId: "sup-poc",
      technicalSupportRange: { lower: 155, upper: 159 },
      technicalSupportTimeframe: "DAILY",
      technicalSupportStrength: 75,
      weeklyTrend: "UPTREND",
      aboveSma200: true,
      entryPermission: "BLOCK",
      eventGateBlocked: false,
      nextBlockingEventDate: null,
      risks: [{ code: "MARKET_REGIME_HEADWIND", severity: "HIGH", params: { regime: "BEAR" } }],
      watchItems: [],
      reasons: [],
      macroCaution: {
        active: true,
        evaluated: true,
        marketRegime: "BEAR",
        vixClose: 27.5,
        tenYearChange20: 0.35,
        triggerCodes: ["MARKET_REGIME_BEAR", "VIX_ELEVATED", "TREASURY_10Y_SURGE_20P"],
        suggestedMarginBufferMin: 0.05,
        suggestedMarginBufferMax: 0.10,
        suggestedPositionScale: 0.50,
      },
      breakoutDiagnosis: diag,
    },
  };

  render(<StockAnalysisPage />);

  // 1) 首屏摘要：顯示大盤逆風降級、宏觀安全邊際提示條、破位診斷標籤，且客觀 [180, 210, 240] 未被竄改
  const summarySection = screen.getByRole("region", { name: "綜合估值與觀察結論" });
  expect(within(summarySection).getByText(/180\.00 USD/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/210\.00 USD/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/240\.00 USD/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/大盤處於逆風環境/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/宏觀環境安全邊際提示/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/5%–10%/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/單筆建議倉位減半（50%）/)).toBeInTheDocument();
  expect(within(summarySection).getByText("破位診斷：高機率真破位")).toBeInTheDocument();
  expect(within(summarySection).getByText("支撐破位診斷：高機率真破位")).toBeInTheDocument();

  // 2) 圖表區：日線實際繪出財報 AVWAP 曲線（含缺日斷線兩段 M）、錨點圓點、hover 讀數與 POC/HVN 分布標註
  const chartSection = screen.getByRole("region", { name: "股價與技術指標互動圖表" });
  const avwapGroup = chartSection.querySelector('g[data-earnings-avwap="true"]');
  expect(avwapGroup).toBeTruthy();
  const avwapPath = avwapGroup!.querySelector("path")!.getAttribute("d")!;
  expect(avwapPath.split("M").length - 1).toBe(2); // 2026-10-13 缺失，曲線必斷開為兩段不硬連
  expect(avwapGroup!.querySelector('circle[data-earnings-anchor="2026-10-10"]')).toBeTruthy();
  expect(within(chartSection).getByText(/財報 AVWAP:/)).toBeInTheDocument();
  expect(within(chartSection).getByText(/160\.25/)).toBeInTheDocument();
  expect(within(chartSection).getByText(/報告日近 252 日日線近似分布/)).toBeInTheDocument();
  expect(chartSection.querySelector('g[data-vp-poc="true"]')).toBeTruthy();

  // 切換至週線後，日線財報 AVWAP 應隱藏不錯位繪製
  fireEvent.click(within(chartSection).getByRole("button", { name: "週線" }));
  expect(chartSection.querySelector('g[data-earnings-avwap="true"]')).toBeNull();

  // 3) 支撐阻力面板：顯示破位診斷詳細卡片與籌碼共振 POC 徽章
  const levelsSection = screen.getByRole("region", { name: "關鍵價位與技術觀察方案" });
  expect(within(levelsSection).getByText(/籌碼共振 · POC/)).toBeInTheDocument();
  expect(within(levelsSection).getByText(/實體長黑收在當日低位/)).toBeInTheDocument();
  expect(within(levelsSection).getByText(/1\.92x/)).toBeInTheDocument();
});

test("renders TREND_PULLBACK_STARTER 1/3 and 1/6 callout as well as HOLD_TRAIL_NO_CHASE ineligible reason", () => {
  report.summary = {
    status: "AVAILABLE", reasons: [],
    value: {
      verdict: "MODERATELY_OVERVALUED",
      fundamentalBacking: "STRONG",
      stance: "TREND_PULLBACK_STARTER",
      primaryMetric: "PE",
      anchorSource: "HISTORICAL_ONLY",
      fairValue: { low: 150, mid: 175, high: 200, currency: "USD" },
      discountToMid: -0.2,
      upsideToMid: -0.1667,
      yieldSpreadVs10y: metric(0.01),
      yieldSpreadBasis: "FCF_YIELD_MINUS_10Y",
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
      trendPullbackStarter: {
        eligible: true,
        matchedAnchor: "EMA21",
        anchorPrice: 208,
        distanceRatio: 0.0096,
        suggestedStop: 205.5,
        positionFraction: "1/6",
        forwardGrowthBasis: "HYPER_GROWTH_FORWARD_PE",
        forwardPe: 31.5,
      },
    },
  };

  const { rerender } = render(<StockAnalysisPage />);
  const summarySection = screen.getByRole("region", { name: "綜合估值與觀察結論" });
  expect(within(summarySection).getByText(/強勢股趨勢回踩試單指引/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/1\/6/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/205\.50 USD/)).toBeInTheDocument();
  expect(within(summarySection).getByText(/Forward P\/E 31\.50x/)).toBeInTheDocument();

  // 切換為缺少年度共識之 HOLD_TRAIL_NO_CHASE -> 清楚呈現原因且不顯示試單指引
  report.summary.value.stance = "HOLD_TRAIL_NO_CHASE";
  report.summary.value.trendPullbackStarter = {
    eligible: false,
    ineligibleReasonCode: "ANNUAL_EARNINGS_FORECAST_MISSING",
    ineligibleMessage: "缺少可用年度市場共識預期，維持不追高。",
  };
  rerender(<StockAnalysisPage />);
  expect(within(summarySection).queryByText(/強勢股趨勢回踩試單指引/)).not.toBeInTheDocument();
  expect(within(summarySection).getByText(/缺少可用年度市場共識預期，維持不追高/)).toBeInTheDocument();
});

test("does not fabricate macro caution buffer/scale or zero-volume POC when backend omits them", () => {
  const dailyBars = Array.from({ length: 10 }, (_, i) => ({
    sessionDate: `2026-10-${String(i + 1).padStart(2, "0")}`,
    open: 100 + i, high: 102 + i, low: 99 + i, close: 101 + i,
    volume: { status: "AVAILABLE", value: 0, reasons: [] },
  }));
  report.priceStructure = {
    status: "AVAILABLE", reasons: [],
    value: { priceDataThrough: "2026-10-10", timeframes: [{ timeframe: "DAILY", bars: dailyBars }] },
  };
  report.marketContext = {
    status: "AVAILABLE", reasons: [],
    value: {
      momentum: {},
      volume: {
        profile252: {
          status: "AVAILABLE", reasons: [],
          value: {
            bins: [{ index: 0, lower: 99, upper: 101, volume: 0 }],
            poc: { index: 0, lower: 99, upper: 101, volume: 0 },
            highVolumeNodes: [{ index: 0, lower: 99, upper: 101, volume: 0 }],
            lowVolumeNodes: [],
            approximationLabel: "DAILY_OHLCV_APPROXIMATION",
          },
        },
      },
    },
  };
  report.summary = {
    status: "AVAILABLE", reasons: [],
    value: {
      verdict: "FAIR_LOWER", fundamentalBacking: "STRONG", stance: "WAIT_FOR_MARKET_STABILIZATION",
      primaryMetric: "PE", anchorSource: "HISTORICAL_ONLY",
      fairValue: { low: 100, mid: 120, high: 140, currency: "USD" },
      discountToMid: 0.1, upsideToMid: 0.11, yieldSpreadVs10y: null, yieldSpreadBasis: null,
      technicalSupportZoneId: null, technicalSupportRange: null, technicalSupportTimeframe: null,
      technicalSupportStrength: null, weeklyTrend: "UPTREND", aboveSma200: true,
      entryPermission: "BLOCK", eventGateBlocked: false, nextBlockingEventDate: null,
      risks: [], watchItems: [], reasons: [],
      macroCaution: {
        active: true, evaluated: true, marketRegime: "BEAR", triggerCodes: ["MARKET_REGIME_BEAR"],
        suggestedMarginBufferMin: null, suggestedMarginBufferMax: null, suggestedPositionScale: null,
      },
    },
  };
  render(<StockAnalysisPage />);
  const summarySection = screen.getByRole("region", { name: "綜合估值與觀察結論" });
  expect(within(summarySection).getByText(/宏觀環境安全邊際提示/)).toBeInTheDocument();
  expect(within(summarySection).queryByText(/5%–10%/)).not.toBeInTheDocument();
  expect(within(summarySection).queryByText(/50%/)).not.toBeInTheDocument();
  const chartSection = screen.getByRole("region", { name: "股價與技術指標互動圖表" });
  expect(chartSection.querySelector('g[data-vp-poc="true"]')).toBeNull();
  expect(within(chartSection).queryByText(/POC 價位約/)).not.toBeInTheDocument();
});

test("interpolates real zh-HK, zh-CN, and en locale templates without leaking raw enums or empty eventDate parens, and renders EMA21 & starter reason fallback", async () => {
  const realI18n = i18next.createInstance();
  await realI18n.init({
    resources: {
      en: { common: enLocale },
      "zh-HK": { common: zhHkLocale },
      "zh-CN": { common: zhCnLocale },
    },
    lng: "zh-HK",
    fallbackLng: "en",
    ns: ["common"],
    defaultNS: "common",
    interpolation: { escapeValue: false },
  });

  const cases = [
    {
      lng: "zh-HK",
      regimeChip: "修正",
      headwindText: "大盤市況處於逆風狀態（空頭），左側接刀風險偏高",
      earningsNoDate: "財報或重大事件臨近，新入場條件受限",
      rightSideWatch: "大盤修正期已完成右側確認（彈簧效應收回），建議採半倉小額分批觀察",
      stabilizationWatch: "等待大盤脫離空頭逆風，進入修復止穩或右側確認",
      starterWatch: "強勢回踩試單（財報日 AVWAP 185.00 USD）：建議 1/3 底倉試單，緊貼止損 180.00 USD",
      ema21Def: "21 期日線收盤價的指數移動平均。",
      priceStaleReason: "行情已過期，不能提供目前可執行的交易計劃。",
    },
    {
      lng: "zh-CN",
      regimeChip: "修正",
      headwindText: "大盘市况处于逆风状态（空头），左侧接刀风险偏高",
      earningsNoDate: "财报或重大事件临近，新入场条件受限",
      rightSideWatch: "大盘修正期已完成右侧确认（弹簧效应收回），建议采半仓小额分批观察",
      stabilizationWatch: "等待大盘脱离空头逆风，进入修复企稳或右侧确认",
      starterWatch: "强势回踩试单（财报日 AVWAP 185.00 USD）：建议 1/3 底仓试单，紧贴止损 180.00 USD",
      ema21Def: "21 期日线收盘价的指数移动平均。",
      priceStaleReason: zhCnLocale.analysisReport.reasons.PRICE_STALE,
    },
    {
      lng: "en",
      regimeChip: "Correction",
      headwindText: "Market regime headwind (Bear); avoid catching falling knives",
      earningsNoDate: "Upcoming earnings or event restricts new entries",
      rightSideWatch: "Correction right-side confirmation (Spring Reclaimed) · Observe with half sizing",
      stabilizationWatch: "Wait for market to exit Bear headwind into Recovery or right-side confirmation",
      starterWatch: "Trend pullback starter (Earnings AVWAP 185.00 USD): 1/3 tranche, tight stop 180.00 USD",
      ema21Def: "21-period exponential moving average of daily closes.",
      priceStaleReason: "Prices are out of date; an executable plan cannot be issued.",
    },
  ] as const;

  for (const c of cases) {
    await realI18n.changeLanguage(c.lng);
    mockUseTranslation.mockImplementation(() => ({ t: realI18n.t.bind(realI18n) as any }));

    report.marketContext = {
      status: "AVAILABLE",
      reasons: [],
      value: {
        momentum: {},
        market: { status: "AVAILABLE", value: { regime: "CORRECTION" }, reasons: [] },
        momentumDetail: {
          indicators: {
            EMA21: { status: "AVAILABLE", value: 192.5, reasons: [] },
          },
          interpretations: {
            EMA21: { status: "AVAILABLE", code: "MA_PRICE_ABOVE", params: { period: 21, unit: "PRICE" } },
          },
        },
      },
    };
    report.summary = {
      status: "AVAILABLE",
      reasons: [],
      value: {
        verdict: "MODERATELY_OVERVALUED",
        fundamentalBacking: "STRONG",
        stance: "HOLD_TRAIL_NO_CHASE",
        primaryMetric: "PE",
        anchorSource: "HISTORICAL_ONLY",
        fairValue: { low: 150, mid: 175, high: 200, currency: "USD" },
        discountToMid: -0.14,
        upsideToMid: -0.125,
        yieldSpreadVs10y: null,
        yieldSpreadBasis: null,
        technicalSupportZoneId: null,
        technicalSupportRange: null,
        technicalSupportTimeframe: null,
        technicalSupportStrength: null,
        weeklyTrend: "UPTREND",
        aboveSma200: true,
        entryPermission: "BLOCK",
        eventGateBlocked: true,
        nextBlockingEventDate: null,
        risks: [
          { code: "MARKET_REGIME_HEADWIND", severity: "HIGH", params: { regime: "BEAR" } },
          { code: "EARNINGS_IMMINENT_BLOCK", severity: "HIGH", params: {} },
        ],
        watchItems: [
          { code: "WATCH_CORRECTION_RIGHT_SIDE", params: { basis: "SPRING_RECLAIMED" } },
          { code: "WATCH_MARKET_STABILIZATION", params: { regime: "BEAR" } },
          {
            code: "WATCH_TREND_PULLBACK_STARTER",
            params: { anchor: "EARNINGS_AVWAP", anchorPrice: 185, suggestedStop: 180, positionFraction: "1/3", currency: "USD" },
          },
        ],
        reasons: [],
        trendPullbackStarter: {
          eligible: false,
          ineligibleReasonCode: "PRICE_STALE",
          ineligibleMessage: "後端預設訊息不應覆蓋字典",
        },
      },
    };

    const { unmount, rerender } = render(<StockAnalysisPage />);
    const summarySection = screen.getByRole("region", { name: "綜合估值與觀察結論" });

    expect(within(summarySection).getByText(`• ${c.headwindText}`)).toBeInTheDocument();
    expect(within(summarySection).getByText(`• ${c.earningsNoDate}`)).toBeInTheDocument();
    expect(within(summarySection).getByText(`• ${c.rightSideWatch}`)).toBeInTheDocument();
    expect(within(summarySection).getByText(`• ${c.stabilizationWatch}`)).toBeInTheDocument();
    expect(within(summarySection).getByText(`• ${c.starterWatch}`)).toBeInTheDocument();
    expect(within(summarySection).getByText(`未啟動回踩試單：${c.priceStaleReason}`)).toBeInTheDocument();

    // 確認無原始枚舉或空括號外洩
    expect(summarySection.textContent).not.toContain("SPRING_RECLAIMED");
    expect(summarySection.textContent).not.toContain("RECOVERY");
    expect(summarySection.textContent).not.toContain("（）");
    expect(summarySection.textContent).not.toContain("()");
    if (c.lng === "en") {
      expect(within(summarySection).getByText(`• ${c.starterWatch}`).textContent).not.toContain("財報日");
    }

    // 四維速覽卡大盤市況 Chip 應同步吃滿 states 本地化
    expect(screen.getByText(c.regimeChip)).toBeInTheDocument();

    // 展開 L4 技術指標與「更多指標」後，EMA21 顯示專屬三語定義（非通用 fallback）
    fireEvent.click(screen.getByText("完整技術指標明細與原始解說（點擊展開）"));
    fireEvent.click(screen.getByText(String(realI18n.t("analysisReport.moreIndicators"))));
    expect(screen.getByText(c.ema21Def)).toBeInTheDocument();

    // 當 ineligibleReasonCode 為空時直接顯示 ineligibleMessage，不查出 reasons.null
    report.summary.value.trendPullbackStarter = {
      eligible: false,
      ineligibleReasonCode: null,
      ineligibleMessage: "自訂後端說明文字",
    };
    rerender(<StockAnalysisPage />);
    expect(within(summarySection).getByText("未啟動回踩試單：自訂後端說明文字")).toBeInTheDocument();
    expect(summarySection.textContent).not.toContain("reasons.null");

    unmount();
  }
});
