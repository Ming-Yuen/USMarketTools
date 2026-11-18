import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { useQuery } from "@tanstack/react-query";
import { StockScreenerPage } from "./StockScreener";
import { apiClient } from "../services/apiClient";

const mockNavigate = jest.fn();
jest.mock("@tanstack/react-query", () => ({ useQuery: jest.fn() }));
jest.mock("react-router-dom", () => ({ useNavigate: () => mockNavigate }), { virtual: true });
jest.mock("../services/apiClient", () => ({ apiClient: { get: jest.fn(), post: jest.fn() } }));

beforeEach(() => {
  jest.clearAllMocks();
  (useQuery as jest.Mock).mockImplementation((options: any) => ({
    isFetching: false, isError: false,
    data: options.queryKey[0] === "stock-classifications" ? {
      classifications: [{ code: "I1", industry: "科技", sourcePlateCode: null }, { code: "I2", industry: "銀行", sourcePlateCode: null }],
      concepts: [{ code: "AI", name: "人工智能" }, { code: "CLOUD", name: "雲端" }],
      marketCapTiers: ["MEGA_LARGE", "MID", "SMALL"],
      verdicts: ["DEEP_UNDERVALUED", "MODERATELY_UNDERVALUED", "FAIR_LOWER", "FAIR_UPPER", "MODERATELY_OVERVALUED", "OVERVALUED_RISK", "INSUFFICIENT_EVIDENCE"],
      sortFields: ["symbol", "marketCap", "marketCapTier", "verdict", "historicalPercentile", "revenueGrowthYoy", "roe", "fcfMargin", "pe", "pb", "ps", "pfcf", "evEbitda"],
    } : { items: [{ figi: "TEST-FIGI", symbol: "TEST", name: "測試公司", stockType: "CS", industryCode: "I1", industry: "科技",
      concepts: [{ code: "AI", name: "人工智能" }, { code: "CLOUD", name: "雲端" }],
      quoteDate: "2026-11-04", closePrice: 100, currency: "USD", valuationDate: "2026-11-05",
      marketCap: 2500000000000, marketCapTier: "MEGA_LARGE",
      pe: 24, pb: 2, ps: null, pfcf: null, evEbitda: null,
      revenueGrowthYoy: 0.18, roe: 0.35, fcfMargin: 0.22, historicalPercentile: 28.6, primaryMetric: "PE",
      verdict: "MODERATELY_UNDERVALUED",
      status: "AVAILABLE", reasonCode: null }], total: 1, page: 0, size: 50 },
  }));
  (apiClient.get as jest.Mock).mockResolvedValue({ data: { items: [], total: 0, page: 0, size: 50 } });
});

test("shows the whole-market screener with tier, verdict, fundamental quality columns, quote date, and no personal watchlist controls", () => {
  render(<StockScreenerPage />);
  expect(screen.getByText("人工智能、雲端")).toBeTruthy();
  expect(screen.getByText(/\$2\.50T \(大型股\)/)).toBeTruthy();
  expect(screen.getByText("合理偏低")).toBeTruthy();
  expect(screen.getByText("P29 (P/E)")).toBeTruthy();
  expect(screen.getByText("+18.0%")).toBeTruthy();
  expect(screen.getByText("35.0%")).toBeTruthy();
  expect(screen.getByText("22.0%")).toBeTruthy();
  expect(screen.getByText("2026-11-04")).toBeTruthy();
  expect(screen.getByText(/2026-11-05 · 可用/)).toBeTruthy();
  expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
  fireEvent.click(screen.getByRole("button", { name: "TEST" }));
  expect(mockNavigate).toHaveBeenCalledWith("/stockAnalysis/TEST-FIGI");
});

test("sends server-side filters using concept code and resets to zero-based first page", async () => {
  render(<StockScreenerPage />);
  fireEvent.change(screen.getByLabelText("股票代碼"), { target: { value: " aapl " } });
  fireEvent.mouseDown(screen.getByLabelText("概念"));
  fireEvent.click(screen.getByRole("option", { name: "人工智能" }));
  fireEvent.click(screen.getByRole("button", { name: "搜尋" }));
  const calls = (useQuery as jest.Mock).mock.calls.filter(([options]) => options.queryKey[0] === "stock-screen");
  await calls[calls.length - 1][0].queryFn();
  expect(apiClient.get).toHaveBeenLastCalledWith("stock/screen", { params: {
    symbol: "AAPL", industry: undefined, conceptCode: "AI",
    marketCapTier: undefined, verdict: undefined,
    minRevenueGrowthYoy: undefined, minRoe: undefined, minFcfMargin: undefined,
    minHistoricalPercentile: undefined, maxHistoricalPercentile: undefined,
    sortBy: undefined, sortDir: undefined,
    page: 0, size: 50,
  } });
});

test("converts percentage inputs including zero/negative values, enforces percentile bounds, and sends authoritative tier/verdict/sort params", async () => {
  render(<StockScreenerPage />);

  // 先驗證歷史分位 min > max 時顯示前端警告且不送出新查詢
  fireEvent.change(screen.getByLabelText("最低歷史分位"), { target: { value: "60" } });
  fireEvent.change(screen.getByLabelText("最高歷史分位"), { target: { value: "30" } });
  fireEvent.click(screen.getByRole("button", { name: "搜尋" }));
  expect(screen.getByText("最低歷史分位不可大於最高歷史分位。")).toBeTruthy();

  // 設定量級、結論、百分比（含 0% 與負數不被當作空白丟失）、分位與全域排序
  fireEvent.mouseDown(screen.getByLabelText("市值量級"));
  fireEvent.click(screen.getByRole("option", { name: /大型／巨型股/ }));
  fireEvent.mouseDown(screen.getByLabelText("估值結論"));
  fireEvent.click(screen.getByRole("option", { name: "合理偏低" }));
  fireEvent.change(screen.getByLabelText("最低營收 YoY (%)"), { target: { value: "15" } });
  fireEvent.change(screen.getByLabelText("最低 ROE (%)"), { target: { value: "0" } });
  fireEvent.change(screen.getByLabelText("最低 FCF 率 (%)"), { target: { value: "-5" } });
  fireEvent.change(screen.getByLabelText("最低歷史分位"), { target: { value: "0" } });
  fireEvent.change(screen.getByLabelText("最高歷史分位"), { target: { value: "35" } });
  fireEvent.mouseDown(screen.getByLabelText("排序欄位"));
  fireEvent.click(screen.getByRole("option", { name: "ROE" }));
  fireEvent.click(screen.getByRole("button", { name: "搜尋" }));

  expect(screen.queryByText("最低歷史分位不可大於最高歷史分位。")).toBeNull();
  const calls = (useQuery as jest.Mock).mock.calls.filter(([options]) => options.queryKey[0] === "stock-screen");
  await calls[calls.length - 1][0].queryFn();
  expect(apiClient.get).toHaveBeenLastCalledWith("stock/screen", { params: {
    symbol: undefined, industry: undefined, conceptCode: undefined,
    marketCapTier: "MEGA_LARGE", verdict: "MODERATELY_UNDERVALUED",
    minRevenueGrowthYoy: 0.15, minRoe: 0, minFcfMargin: -0.05,
    minHistoricalPercentile: 0, maxHistoricalPercentile: 35,
    sortBy: "roe", sortDir: "desc",
    page: 0, size: 50,
  } });
});
