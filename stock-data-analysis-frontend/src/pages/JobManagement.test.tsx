import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { JobManagementPage } from "./JobManagement";

const mockMutate = jest.fn();
const mockApiGet = jest.fn();

jest.mock("../hooks/api/useApi", () => ({
  useFetch: () => ({
    data: {
      jobTaskList: [
        {
          id: 99,
          taskCode: "SIGNAL_REPLAY_AUDIT",
          taskGroup: "SCHEDULE",
          jobParams: {},
          jobClassPath: "com.ykm.stock.analytics.scheduling.SignalReplayAuditJob",
          cronExpression: null,
          activeStatus: "ACTIVE",
        },
      ],
    },
    isLoading: false,
    isError: false,
    error: null,
    refetch: jest.fn(),
  }),
  useMutate: () => ({
    mutate: mockMutate,
    isPending: false,
  }),
}));

jest.mock("../services/apiClient", () => ({
  apiClient: {
    get: (...args: any[]) => mockApiGet(...args),
  },
}));

jest.mock("../components/DynamicFormTable/DynamicFormTable", () => () => <div data-testid="jobs-table" />);
jest.mock("react-i18next", () => ({
  useTranslation: () => ({
    t: (key: string, opts?: { defaultValue?: string }) => opts?.defaultValue ?? key,
  }),
}));

const menu = { menuId: "jobConfig", name: "jobConfig", path: "/jobConfig" };

beforeEach(() => {
  jest.clearAllMocks();
});

test("renders signal replay status panel, triggers manual run, and downloads uncompressed CSV directly", async () => {
  mockApiGet.mockImplementation((url: string, config?: { responseType?: string }) => {
    if (url === "job/signal-replay/status") {
      return Promise.resolve({
        data: {
          status: "COMPLETED",
          runId: "20261121_220000",
          startDate: "2019-01-01",
          endDate: "2026-11-20",
          totalDays: 1986,
          totalSymbols: 26,
          emittedEvents: 420,
          progressPct: 100,
          files: [
            { type: "events", fileName: "signal_replay_events_20261121_220000.csv", sizeBytes: 4096, rowCount: 420 },
            { type: "summary", fileName: "signal_replay_summary_20261121_220000.csv", sizeBytes: 2048, rowCount: 45 },
          ],
        },
      });
    }
    if (url === "job/signal-replay/download" && config?.responseType === "blob") {
      return Promise.resolve({ data: new Blob(["col1,col2\n1,2\n"], { type: "text/csv" }) });
    }
    return Promise.reject(new Error(`Unexpected URL: ${url}`));
  });

  const createObjectURL = jest.fn(() => "blob:csv-download");
  const revokeObjectURL = jest.fn();
  Object.defineProperty(window.URL, "createObjectURL", { value: createObjectURL, writable: true });
  Object.defineProperty(window.URL, "revokeObjectURL", { value: revokeObjectURL, writable: true });
  const clickSpy = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <JobManagementPage menuTree={menu} />
    </QueryClientProvider>,
  );

  await waitFor(() => {
    expect(screen.getByText(/job\.signalReplay\.download\.events|signal_replay_events/)).toBeTruthy();
  });

  fireEvent.click(screen.getByRole("button", { name: "job.signalReplay.runAction" }));
  expect(mockMutate).toHaveBeenCalledWith({
    taskCode: "SIGNAL_REPLAY_AUDIT",
    jobParams: {},
    taskGroup: "SCHEDULE",
  });

  fireEvent.click(screen.getByRole("button", { name: /signal_replay_events|job\.signalReplay\.download\.events/ }));
  await waitFor(() => {
    expect(mockApiGet).toHaveBeenCalledWith("job/signal-replay/download", {
      params: { type: "events" },
      responseType: "blob",
    });
  });
  expect(createObjectURL).toHaveBeenCalled();
  expect(clickSpy).toHaveBeenCalled();
  expect(revokeObjectURL).toHaveBeenCalledWith("blob:csv-download");
  clickSpy.mockRestore();
});
