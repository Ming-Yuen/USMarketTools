import { ApiResponse } from "./apiResponse";
import { ActiveStatus } from "../enums/ActiveStatus";

export type TaskGroup = "TEMP" | "SCHEDULE" | "BATCH" | "REPORT";

export interface LaunchJobRequest<T> {
  taskCode: string;
  jobParams: T;
  taskGroup: TaskGroup;
}

export interface EnquiryJobRequest extends Record<string, unknown> {
  taskCode?: string;
  page?: number;
  pageSize?: number;
}

export interface Job {
  id: number;
  taskCode: string;
  taskGroup: TaskGroup;
  jobParams: { [key: string]: any };
  jobClassPath: string;
  cronExpression?: string | null;
  startTime?: string;
  endTime?: string;
  activeStatus: ActiveStatus;
  watermarkDate?: string | null;
}

export interface EnquiryJobResponse extends ApiResponse {
  jobTaskList: Job[];
}

export interface SignalReplayFile {
  type: "events" | "summary" | string;
  fileName: string;
  sizeBytes: number;
  rowCount: number;
}

export interface SignalReplayStatus {
  taskCode?: string;
  status: "IDLE" | "RUNNING" | "COMPLETED" | "FAILED" | string;
  runId?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  processedDays?: number;
  totalDays?: number;
  totalSymbols?: number;
  currentDate?: string | null;
  processedSnapshots?: number;
  emittedEvents?: number;
  progressPct: number;
  errorMessage?: string | null;
  files: SignalReplayFile[];
}
