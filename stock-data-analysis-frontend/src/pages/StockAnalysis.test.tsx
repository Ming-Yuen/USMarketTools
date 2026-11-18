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

  const futuExpandBtn = within(futuSection).getByRole("button", { name: /富途市場觀察與分析師預期/ });
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
