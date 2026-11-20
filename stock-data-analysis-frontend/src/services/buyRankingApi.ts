import { apiClient } from "./apiClient";
import {
  BuyRankingExportFormat,
  BuyRankingOptionsDto,
  BuyRankingRequest,
  BuyRankingResponse,
} from "./types/dto/buyRanking";

export interface DownloadedReportFile {
  blob: Blob;
  fileName: string;
}

export const extractDownloadFilename = (
  contentDisposition: string | undefined,
  fallback: string
): string => {
  if (!contentDisposition) {
    return fallback;
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(contentDisposition);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].trim());
    } catch {
      return utf8Match[1].trim();
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(contentDisposition);
  if (plainMatch?.[1]) {
    return plainMatch[1].trim();
  }
  return fallback;
};

const readBlobAsText = (blob: Blob): Promise<string> => {
  if (typeof blob.text === "function") {
    return blob.text();
  }
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : "");
    reader.onerror = () => reject(reader.error ?? new Error("BLOB_READ_FAILED"));
    reader.readAsText(blob);
  });
};

export const parseBlobErrorMessage = async (error: unknown, fallback: string): Promise<string> => {
  const err = error as {
    response?: { data?: unknown; status?: number };
    message?: string;
  } | null | undefined;
  const data = err?.response?.data;
  if (data instanceof Blob) {
    try {
      const text = await readBlobAsText(data);
      if (text) {
        const parsed = JSON.parse(text) as { message?: string };
        if (parsed?.message) {
          return parsed.message;
        }
        return text;
      }
    } catch {
      return err?.message || fallback;
    }
  }
  if (data && typeof data === "object" && "message" in data) {
    const msg = (data as { message?: string }).message;
    if (msg) {
      return msg;
    }
  }
  return err?.message || fallback;
};

export const buyRankingApi = {
  async getOptions(): Promise<BuyRankingOptionsDto> {
    return (await apiClient.get<BuyRankingOptionsDto>("reports/buy-ranking/options")).data;
  },

  async query(request: BuyRankingRequest, locale: string): Promise<BuyRankingResponse> {
    return (
      await apiClient.post<BuyRankingResponse>(
        "reports/buy-ranking",
        { ...request, locale },
        { headers: { "Accept-Language": locale } }
      )
    ).data;
  },

  async exportReport(
    format: BuyRankingExportFormat,
    reportId: string | null,
    request: BuyRankingRequest | null,
    locale: string,
    fallbackFileName: string
  ): Promise<DownloadedReportFile> {
    const params: Record<string, string> = { format };
    if (reportId) {
      params.reportId = reportId;
    }
    const response = await apiClient.post<Blob>(
      "reports/buy-ranking/export",
      request ? { ...request, locale } : undefined,
      {
        params,
        responseType: "blob",
        headers: { "Accept-Language": locale },
      }
    );
    const cd = response.headers?.["content-disposition"] as string | undefined;
    const fileName = extractDownloadFilename(cd, fallbackFileName);
    return { blob: response.data, fileName };
  },
};

export const triggerBrowserDownload = (blob: Blob, fileName: string): void => {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
  } finally {
    URL.revokeObjectURL(url);
  }
};
