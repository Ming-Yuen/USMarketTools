import { TFunction } from "i18next";

export function formatMoney(value?: number | null, digits = 2): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return `$${value.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

export function formatDecimal(value?: number | null, digits = 2): string {
  if (value == null || !Number.isFinite(value)) return "—";
  return value.toFixed(digits);
}

export function formatPct(value?: number | null, digits = 1, signed = false): string {
  if (value == null || !Number.isFinite(value)) return "—";
  const pct = value * 100;
  const sign = signed && pct > 0 ? "+" : "";
  return `${sign}${pct.toFixed(digits)}%`;
}

function asNum(v: unknown): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function asStr(v: unknown, fallback = "—"): string {
  if (v == null || v === "") return fallback;
  return String(v);
}

/** 依結構化 alertCode 與 payload 透過真實 i18n 詞典渲染三語提醒文案，不硬編碼單語文字。 */
export function formatAlertMessage(
  t: TFunction,
  alertCode: string,
  symbol?: string | null,
  payload?: Record<string, unknown> | null,
): string {
  const p = payload ?? {};
  const sym = symbol ?? asStr(p.symbol, "");

  if (alertCode === "HOLDING_OVERVALUED_PROFIT_TAKING" && p.inProfit === false) {
    return t("notifications.alerts.HOLDING_OVERVALUED_RISK_LOSS", {
      symbol: sym,
      closePrice: formatDecimal(asNum(p.closePrice)),
    });
  }

  const timingCode = asStr(p.timingCode, "DATE_ONLY");
  const bufferCode = asStr(p.bufferLevel, "UNKNOWN");
  const prevRegimeRaw = asStr(p.previousRegime, "—");
  const currRegimeRaw = asStr(p.currentRegime, "—");
  const stanceRaw = asStr(p.stance, "—");
  const trackTypeRaw = asStr(p.trackType, "—");
  const deferReasonRaw = asStr(p.reason, "UNKNOWN");
  const affectedSymbols = Array.isArray(p.affectedSymbols)
    ? p.affectedSymbols.map((s) => String(s)).join(", ")
    : asStr(p.affectedSymbols);

  const sharesNum = asNum(p.suggestedShares);

  const params: Record<string, string | number> = {
    symbol: sym,
    closePrice: formatDecimal(asNum(p.closePrice)),
    protectiveStop: formatDecimal(asNum(p.protectiveStop)),
    closeThreshold: formatDecimal(asNum(p.closeThreshold)),
    eventDate: asStr(p.eventDate),
    daysToEarnings: asNum(p.daysToEarnings) ?? 0,
    timing: String(t(`portfolio.health.earningsTimings.${timingCode}`, { defaultValue: timingCode })),
    buffer: String(t(`portfolio.health.bufferLevels.${bufferCode}`, { defaultValue: bufferCode })),
    stance: String(t(`analysisReport.states.${stanceRaw}`, { defaultValue: stanceRaw })),
    targetLevel: asStr(p.targetLevel, "T1"),
    targetPrice: formatDecimal(asNum(p.targetPrice)),
    resistanceLower: formatDecimal(asNum(p.resistanceLower)),
    resistanceUpper: formatDecimal(asNum(p.resistanceUpper)),
    industryName: asStr(p.industryName, asStr(p.industryCode)),
    holdingCount: asNum(p.holdingCount) ?? 0,
    affectedSymbols,
    breadthAboveSma50Pct: formatPct(asNum(p.breadthAboveSma50Pct), 1),
    breadthAboveSma200Pct: formatPct(asNum(p.breadthAboveSma200Pct), 1),
    return20dMedian: formatPct(asNum(p.return20dMedian), 1, true),
    exposurePct: formatPct(asNum(p.exposurePct), 1),
    maxPct: formatPct(asNum(p.maxSectorExposurePct), 0),
    suggestedShares: sharesNum != null ? sharesNum : "—",
    trackType: String(t(`notifications.radarTracks.${trackTypeRaw}`, { defaultValue: trackTypeRaw })),
    previousRegime: String(t(`analysisReport.states.${prevRegimeRaw}`, { defaultValue: prevRegimeRaw })),
    currentRegime: String(t(`analysisReport.states.${currRegimeRaw}`, { defaultValue: currRegimeRaw })),
    previousTradeDate: asStr(p.previousTradeDate),
    tradeDate: asStr(p.tradeDate),
    criticalCount: asNum(p.criticalCount) ?? 0,
    warningCount: asNum(p.warningCount) ?? 0,
    radarTop5Count: asNum(p.radarTop5Count) ?? 0,
    oasPct: formatDecimal(asNum(p.oasPct), 2),
    deltaBps: formatDecimal(asNum(p.deltaBps), 1),
    observationDate: asStr(p.observationDate),
    impliedMovePct: formatPct(asNum(p.impliedMovePct), 1),
    stopDistancePct: formatPct(asNum(p.stopDistancePct), 1),
    deferReason: String(t(`notifications.deferReasons.${deferReasonRaw}`, { defaultValue: deferReasonRaw })),
    readyQuotes: asNum(p.readyQuotes) ?? 0,
    totalTargets: asNum(p.totalTargets) ?? 0,
    readySplits: asNum(p.readySplits) ?? 0,
    splitTargets: asNum(p.splitTargets) ?? 0,
  };

  return String(t(`notifications.alerts.${alertCode}`, {
    ...params,
    defaultValue: `${sym ? `${sym}: ` : ""}${alertCode}`,
  }));
}

export function severityColor(severity?: string | null): "error" | "warning" | "info" | "default" {
  if (severity === "CRITICAL") return "error";
  if (severity === "WARNING") return "warning";
  if (severity === "INFO") return "info";
  return "default";
}
