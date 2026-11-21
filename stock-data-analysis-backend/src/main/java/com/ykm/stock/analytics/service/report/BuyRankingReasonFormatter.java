package com.ykm.stock.analytics.service.report;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PositionSizingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 建議買入排行榜之三語（zh-HK / zh-CN / en）決策理由、梯隊名稱、篩選條件與匯出標題渲染器。
 * 供畫面 JSON 回應與 Excel / CSV 匯出共用，確保兩端文字與語意 100% 一致。
 */
public final class BuyRankingReasonFormatter {
    public static final String LOCALE_ZH_HK = "zh-HK";
    public static final String LOCALE_ZH_CN = "zh-CN";
    public static final String LOCALE_EN = "en";

    private BuyRankingReasonFormatter() {}

    /** 正規化請求或 Accept-Language 語系為系統三語之一（預設 zh-HK）。 */
    public static String normalizeLocale(String requestedLocale, String acceptLanguage) {
        String candidate = (requestedLocale != null && !requestedLocale.isBlank()) ? requestedLocale : acceptLanguage;
        if (candidate == null || candidate.isBlank()) {
            return LOCALE_ZH_HK;
        }
        String primary = candidate.split(",")[0].trim().toLowerCase(Locale.ROOT);
        if (primary.startsWith("en")) {
            return LOCALE_EN;
        }
        if (primary.contains("cn") || primary.contains("hans") || primary.equals("zh-sg")) {
            return LOCALE_ZH_CN;
        }
        if (primary.startsWith("zh")) {
            return LOCALE_ZH_HK;
        }
        return LOCALE_ZH_HK;
    }

    public static String tierLabel(String tier, String locale) {
        String loc = normalizeLocale(locale, null);
        if (BuyRankingDtos.TIER_1.equals(tier)) {
            return switch (loc) {
                case LOCALE_ZH_CN -> "Tier 1 (优先下手)";
                case LOCALE_EN -> "Tier 1 (Actionable Now)";
                default -> "Tier 1 (優先下手)";
            };
        }
        if (BuyRankingDtos.TIER_2.equals(tier)) {
            return switch (loc) {
                case LOCALE_ZH_CN -> "Tier 2 (回踩等待)";
                case LOCALE_EN -> "Tier 2 (Wait Pullback)";
                default -> "Tier 2 (回踩等待)";
            };
        }
        if (BuyRankingDtos.TIER_3.equals(tier)) {
            return switch (loc) {
                case LOCALE_ZH_CN -> "Tier 3 (事件观望)";
                case LOCALE_EN -> "Tier 3 (Event / Regime Wait)";
                default -> "Tier 3 (事件觀望)";
            };
        }
        return switch (loc) {
            case LOCALE_ZH_CN -> "Tier 4 (暂不建议)";
            case LOCALE_EN -> "Tier 4 (Avoid / Not Recommended)";
            default -> "Tier 4 (暫不建議)";
        };
    }

    public static String assetTypeLabel(String assetType, String locale) {
        String loc = normalizeLocale(locale, null);
        if (BuyRankingDtos.ASSET_COMMON_STOCK.equals(assetType)) {
            return switch (loc) {
                case LOCALE_ZH_CN -> "普通股";
                case LOCALE_EN -> "Common Stock";
                default -> "普通股";
            };
        }
        if (BuyRankingDtos.ASSET_ADR.equals(assetType)) {
            return "ADR";
        }
        if (BuyRankingDtos.ASSET_ETF.equals(assetType)) {
            return "ETF";
        }
        if (BuyRankingDtos.ASSET_REIT.equals(assetType)) {
            return "REIT";
        }
        return assetType == null ? "" : assetType;
    }

    public static String scaleTagLabel(String scaleTag, List<String> sizingTags, String locale) {
        if (scaleTag == null || scaleTag.isBlank()) {
            return "";
        }
        String loc = normalizeLocale(locale, null);
        if ("1/2".equals(scaleTag)) {
            boolean hasMacro = sizingTags != null && sizingTags.contains(PositionSizingDto.TAG_MACRO_HEADWIND_HALF_SIZE);
            boolean hasSector = sizingTags != null && sizingTags.contains(PositionSizingDto.TAG_SECTOR_BREADTH_HEADWIND_HALF_SIZE);
            if (hasSector && !hasMacro) {
                return pick(loc, "1/2 板塊逆風縮倉", "1/2 板块逆风缩仓", "1/2 Sector Cut");
            }
            if (hasSector && hasMacro) {
                return pick(loc, "1/2 宏觀／板塊逆風縮倉", "1/2 宏观／板块逆风缩仓", "1/2 Macro/Sector Cut");
            }
            return pick(loc, "1/2 逆風縮倉", "1/2 逆风缩仓", "1/2 Headwind Cut");
        }
        return switch (scaleTag) {
            case "FULL" -> switch (loc) {
                case LOCALE_ZH_CN -> "标准仓位";
                case LOCALE_EN -> "Full Size";
                default -> "標準倉位";
            };
            case "1/3" -> switch (loc) {
                case LOCALE_ZH_CN -> "1/3 试单缩仓";
                case LOCALE_EN -> "1/3 Starter";
                default -> "1/3 試單縮倉";
            };
            case "1/6" -> switch (loc) {
                case LOCALE_ZH_CN -> "1/6 叠加缩仓";
                case LOCALE_EN -> "1/6 Combined Cut";
                default -> "1/6 疊加縮倉";
            };
            case "PAUSED" -> switch (loc) {
                case LOCALE_ZH_CN -> "大盘暂缓新仓";
                case LOCALE_EN -> "Paused (Market)";
                default -> "大盤暫緩新倉";
            };
            case "NOT_RECOMMENDED" -> switch (loc) {
                case LOCALE_ZH_CN -> "暂不建议新增";
                case LOCALE_EN -> "Not Recommended";
                default -> "暫不建議新增";
            };
            case "UNVERIFIED" -> switch (loc) {
                case LOCALE_ZH_CN -> "既有持仓未核实";
                case LOCALE_EN -> "Unverified Holding";
                default -> "既有持倉未核實";
            };
            default -> switch (loc) {
                case LOCALE_ZH_CN -> "暂不适用";
                case LOCALE_EN -> "N/A";
                default -> "暫不適用";
            };
        };
    }

    public static String formatReason(String reasonCode, Map<String, Object> params, String locale) {
        String loc = normalizeLocale(locale, null);
        if (reasonCode == null) {
            return "";
        }
        return switch (reasonCode) {
            // Tier 4 — 資料未核實或缺失
            case "ADR_RATIO_UNVERIFIED" -> pick(loc,
                    "ADR 換股比例尚未核實，為免誤算估值暫不建議買入",
                    "ADR 换股比例尚未核实，为免误算估值暂不建议买入",
                    "ADR share ratio is unverified; excluded from buy candidates");
            case "FX_RATE_UNAVAILABLE" -> pick(loc,
                    "缺少對應財報幣別官方匯率，無法核實美元估值",
                    "缺少对应财报币别官方汇率，无法核实美元估值",
                    "Official FX rate unavailable for reporting currency; valuation unverified");
            case "FX_RATE_STALE" -> pick(loc,
                    "對應財報幣別匯率已逾期逾 14 日，暫不作買入評級",
                    "对应财报币别汇率已逾期逾 14 日，暂不作买入评级",
                    "FX rate is stale (>14 days); excluded from buy candidates");
            case "REPORTING_CURRENCY_MISMATCH" -> pick(loc,
                    "財報與報價幣別無法安全折算，暫不建議買入",
                    "财报与报价币别无法安全折算，暂不建议买入",
                    "Reporting and quote currencies cannot be safely reconciled");
            case "SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED" -> pick(loc,
                    "多股類經濟權股數基準未核實，為免估值失真暫不納入",
                    "多股类经济权股数基准未核实，为免估值失真暂不纳入",
                    "Multi-class share economic basis is unverified; excluded");
            case "QUOTE_MISSING" -> pick(loc,
                    "缺少最新交易日有效收盤行情或日線不足，無法評估技術支撐",
                    "缺少最新交易日有效收盘行情或日线不足，无法评估技术支撑",
                    "Missing latest valid daily quote or technical history");
            case "VALUATION_INSUFFICIENT" -> pick(loc,
                    "缺少最新有效估值快照或客觀參考帶，暫無法評級",
                    "缺少最新有效估值快照或客观参考带，暂无法评级",
                    "Latest valuation snapshot or objective reference band is insufficient");
            case "FUNDAMENTAL_INSUFFICIENT" -> pick(loc,
                    "基本面財務證據不足，暫不建議買入",
                    "基本面财务证据不足，暂不建议买入",
                    "Fundamental financial evidence is insufficient");

            // Tier 4 — 避雷與價值陷阱
            case "OVERVALUED_RISK" -> pick(loc,
                    "股價已顯著高於客觀估值上緣（高估風險），不宜追高",
                    "股价已显著高于客观估值上缘（高估风险），不宜追高",
                    "Price is significantly above objective fair band (overvalued risk)");
            case "WEAKENING_AND_BREAKDOWN" -> pick(loc,
                    "基本面轉弱且同支撐區出現高機率真破位，屬高風險價值陷阱",
                    "基本面转弱且同支撑区出现高概率真破位，属高风险价值陷阱",
                    "Weakening fundamentals combined with high-risk breakdown (value trap)");
            case "FUNDAMENTAL_WEAKENING" -> pick(loc,
                    "基本面評級轉弱（WEAKENING），即使估值折價亦應避開價值陷阱",
                    "基本面评级转弱（WEAKENING），即使估值折价亦应避开价值陷阱",
                    "Fundamentals are weakening; avoid potential value trap despite discount");
            case "VALUE_TRAP_WARNING" -> pick(loc,
                    "前瞻盈餘預期下滑或近 90 日顯著下修，觸發價值陷阱警戒",
                    "前瞻盈余预期下滑或近 90 日显著下修，触发价值陷阱警戒",
                    "Forward earnings contraction or 90d downgrade triggers value trap warning");
            case "HIGH_RISK_BREAKDOWN" -> pick(loc,
                    "選定支撐區觸發高機率真破位，建議嚴格避開接飛刀",
                    "选定支撑区触发高概率真破位，建议严格避开接飞刀",
                    "High-risk breakdown confirmed on selected support zone");
            case "STOP_BREACHED_INVALIDATED" -> pick(loc,
                    "收盤價已跌穿保護止損或技術失效，需先等待重新築底",
                    "收盘价已跌穿保护止损或技术失效，需先等待重新筑底",
                    "Protective stop breached or technical invalidation confirmed");
            case "HOLD_TRAIL_NO_CHASE_NEW_ENTRY" -> pick(loc,
                    "估值偏高（持有者可續守追蹤），本輪不建議新開倉追價",
                    "估值偏高（持有者可续守追踪），本轮不建议新开仓追价",
                    "Moderately overvalued (existing holders may trail); no new entry recommended");
            case "CAUTION_OVEREXTENSION" -> pick(loc,
                    "股價已明顯超漲且缺乏合理成長支撐，暫不建議新買入",
                    "股价已明显超涨且缺乏合理成长支撑，暂不建议新买入",
                    "Overextended above fair range without starter support");
            case "ETF_TECHNICAL_DOWNTREND" -> pick(loc,
                    "ETF 處於週線下跌趨勢且低於年線（SMA200），暫不建議買入",
                    "ETF 处于周线下跌趋势且低于年线（SMA200），暂不建议买入",
                    "ETF is in weekly downtrend below SMA200; avoid new entry");

            // Tier 3 — 事件／盤勢觀望
            case "EARNINGS_WITHIN_14D" -> {
                String days = strParam(params, "daysToEarnings", "0");
                yield pick(loc,
                        "未來 " + days + " 日內將發布財報，財報事件窗內先觀望避開跳空風險",
                        "未来 " + days + " 日内将发布财报，财报事件窗内先观望避开跳空风险",
                        "Earnings due in " + days + " calendar day(s); wait to avoid gap risk");
            }
            case "SUSPECTED_SHAKEOUT_OBSERVING" -> pick(loc,
                    "選定支撐區疑似洗盤假跌破，進入 1–3 個交易日收回觀察期",
                    "选定支撑区疑似洗盘假跌破，进入 1–3 个交易日收回观察期",
                    "Suspected shakeout on support zone; observing 1-3 sessions for reclaim");
            case "MARKET_REGIME_HEADWIND_PAUSE" -> {
                String regime = strParam(params, "marketRegime", "BEAR");
                yield pick(loc,
                        "大盤處於 " + regime + " 逆風封鎖階段，暫緩新增買入等待大盤止穩",
                        "大盘处于 " + regime + " 逆风封锁阶段，暂缓新增买入等待大盘止稳",
                        "Market regime is " + regime + "; pause new entries until stabilization");
            }
            case "MARKET_DATA_NOT_READY" -> pick(loc,
                    "大盤市況資料尚未就緒，暫不升級為當下可下手標的",
                    "大盘市况资料尚未就绪，暂不升级为当下可下手标的",
                    "Market context data not ready; hold off on immediate entry");
            case "MARKET_CORRECTION_WAIT_CONFIRMATION" -> pick(loc,
                    "大盤處於修正期（CORRECTION）且本區尚未完成右側確認，維持觀望",
                    "大盘处于修正期（CORRECTION）且本区尚未完成右侧确认，维持观望",
                    "Market in CORRECTION without right-side confirmation on this zone");
            case "WAIT_SIGNAL_OR_CONFIRMATION" -> pick(loc,
                    "基本面與估值合格，但支撐買點尚未形成可執行訊號或仍待收盤確認",
                    "基本面与估值合格，但支撑买点尚未形成可执行信号或仍待收盘确认",
                    "Fundamentals and valuation pass, awaiting actionable entry trigger or close confirmation");
            case "ETF_WAIT_TECHNICAL_CONFIRMATION" -> pick(loc,
                    "ETF 尚未出現明確可執行技術買點或支撐確認，暫維持觀望",
                    "ETF 尚未出现明确可执行技术买点或支撑确认，暂维持观望",
                    "ETF awaiting actionable technical entry signal or support confirmation");
            case "QUOTE_NORMALIZATION_FAILED" -> {
                String code = strParam(params, "code", "NORMALIZATION_FAILED");
                yield pick(loc,
                        "歷史行情正規化或拆股資料未通過完整性核驗（" + code + "），嚴禁作為買入候選",
                        "历史行情正规化或拆股资料未通过完整性核验（" + code + "），严禁作为买入候选",
                        "Quote normalization or split integrity check failed (" + code + "); excluded from buy candidates");
            }
            case "STALE_DATA_DATE_FALLBACK" -> pick(loc,
                    "本報表資料日落後最新已收市交易日，僅供歷史參考，不構成即時買入訊號",
                    "本报表资料日落后最新已收市交易日，仅供参考，不构成即时买入信号",
                    "Report data date lags the latest closed trading day; reference only, not immediately actionable");

            // Tier 2 — 基本面好、等價格回踩
            case "WAIT_PULLBACK_TO_BUY_ZONE" -> {
                String dist = pctParam(params, "distancePct");
                yield pick(loc,
                        "基本面穩健且估值合理／偏低，現價仍高於買區上緣 " + dist + "，等待回踩",
                        "基本面稳健且估值合理／偏低，现价仍高于买区上缘 " + dist + "，等待回踩",
                        "Solid fundamentals and valuation; price is " + dist + " above buy zone upper, wait for pullback");
            }
            case "ETF_WAIT_PULLBACK" -> {
                String dist = pctParam(params, "distancePct");
                yield pick(loc,
                        "ETF 技術結構偏強或中性，現價高於支撐買區 " + dist + "，等待回踩",
                        "ETF 技术结构偏强或中性，现价高于支撑买区 " + dist + "，等待回踩",
                        "ETF technical structure constructive; price is " + dist + " above support buy zone");
            }

            // Tier 1 — 當下優先可下手
            case "ACCUMULATE_AT_SUPPORT_ACTIVE" -> {
                String rr = numParam(params, "netRr", 2);
                yield pick(loc,
                        "估值偏低、基本面穩健且現價處於有效支撐買區（淨賺賠比 " + rr + "），可優先關注",
                        "估值偏低、基本面稳健且现价处于有效支撑买区（净赚赔比 " + rr + "），可优先关注",
                        "Undervalued with solid fundamentals in active support buy zone (Net R/R " + rr + ")");
            }
            case "CORRECTION_RIGHT_SIDE_CONFIRMED" -> {
                String basis = strParam(params, "confirmationBasis", "CONFIRMED_RETEST");
                yield pick(loc,
                        "大盤修正期內完成右側確認（" + basis + "）且計畫有效，依縮倉原則分批關注",
                        "大盘修正期内完成右侧确认（" + basis + "）且计划有效，依缩仓原则分批关注",
                        "Right-side confirmation (" + basis + ") active during market CORRECTION with reduced size");
            }
            case "TREND_PULLBACK_STARTER_ACTIVE" -> {
                String anchor = strParam(params, "anchor", "SMA50");
                String frac = strParam(params, "positionFraction", "1/3");
                yield pick(loc,
                        "強勢成長股縮量回踩 " + anchor + " 止穩且前瞻成長合理，適合 " + frac + " 底倉試單",
                        "强势成长股缩量回踩 " + anchor + " 止稳且前瞻成长合理，适合 " + frac + " 底仓试单",
                        "Strong growth pullback stabilized near " + anchor + "; eligible for " + frac + " starter entry");
            }
            case "ETF_BULLISH_SUPPORT_ACTIVE" -> {
                String score = numParam(params, "etfScore", 1);
                yield pick(loc,
                        "ETF 週線上升趨勢站上年線，量價資金流與支撐買區共振（技術評分 " + score + "）",
                        "ETF 周线上升趋势站上年线，量价资金流与支撑买区共振（技术评分 " + score + "）",
                        "ETF in weekly uptrend above SMA200 in active support zone (ETF score " + score + ")");
            }
            default -> reasonCode;
        };
    }

    /** 統一產生與前端一致之倉位使用條件與風控警示標籤（供 Excel / CSV 匯出共用，先處理零股上限原因，且跨全部列保留資料日回退提示）。 */
    public static String formatSizingConditions(BuyRankingDtos.BuyRankingItemDto item, boolean staleDateFallback, String locale) {
        String loc = normalizeLocale(locale, null);
        if (item == null) {
            return "";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        String status = item.positionSizingStatus();
        if ("NOT_RECOMMENDED".equals(status) || BuyRankingDtos.TIER_4.equals(item.tier())) {
            parts.add(pick(loc, "暫不建議（0股）", "暂不建议（0股）", "Not Recommended (0 sh)"));
        } else if ("PAUSED".equals(status)) {
            parts.add(pick(loc, "逆風暫緩開倉", "逆风暂缓开仓", "Paused (Headwind)"));
        } else if ("AVAILABLE".equals(status)) {
            if (item.suggestedShares() != null && item.suggestedShares() <= 0L) {
                String zeroReason = (item.positionSizingReasonCode() != null && !item.positionSizingReasonCode().isBlank())
                        ? " (" + item.positionSizingReasonCode() + ")" : "";
                parts.add(pick(loc, "無可用額度（0股）" + zeroReason,
                        "无可用额度（0股）" + zeroReason,
                        "Zero Shares / Cap Exhausted" + zeroReason));
            } else if (item.positionSizingActionable()) {
                parts.add(pick(loc, "即時可執行", "即时可执行", "Actionable Now"));
            } else {
                parts.add(pick(loc, "觸發後試算（尚未達買點）", "触发后试算（尚未达买点）", "On-Trigger Preview"));
            }
        } else if (item.positionSizingReasonCode() != null && !item.positionSizingReasonCode().isBlank()) {
            parts.add(pick(loc, "不可試算：" + item.positionSizingReasonCode(),
                    "不可试算：" + item.positionSizingReasonCode(),
                    "Unavailable: " + item.positionSizingReasonCode()));
        }
        if (BuyRankingDtos.TIER_2.equals(item.tier()) && item.planNetRiskReward() != null) {
            String planRr = item.planNetRiskReward().setScale(2, RoundingMode.HALF_UP).toPlainString();
            parts.add(pick(loc, "計畫 R/R " + planRr, "计划 R/R " + planRr, "Plan R/R " + planRr));
        }
        if (item.sectorConcentrationWarning()) {
            parts.add(pick(loc, "⚠ 產業曝險超過設定上限（股數未自動裁減）",
                    "⚠ 行业敞口超过设定上限（股数未自动裁减）",
                    "⚠ Sector Exposure Exceeds Configured Cap (Shares Not Auto-Trimmed)"));
        }
        if (item.positionSizingTags() != null
                && item.positionSizingTags().contains(PositionSizingDto.TAG_SECTOR_BREADTH_HEADWIND_HALF_SIZE)) {
            parts.add(pick(loc, "⚠ 所屬板塊廣度崩壞（新倉半倉控管）",
                    "⚠ 所属板块广度崩坏（新仓半仓控管）",
                    "⚠ Sector Breadth Breakdown (Half Size)"));
        }
        boolean hasStaleTag = staleDateFallback
                || (item.positionSizingTags() != null && item.positionSizingTags().contains("STALE_QUOTE_DATE_FALLBACK"));
        if (hasStaleTag) {
            parts.add(pick(loc, "⚠ 資料日回退（非最新收市日）",
                    "⚠ 资料日回退（非最新收市日）",
                    "⚠ Stale Data Date Fallback"));
        }
        return String.join(" | ", parts);
    }

    private static String pick(String locale, String hk, String cn, String en) {
        return switch (locale) {
            case LOCALE_ZH_CN -> cn;
            case LOCALE_EN -> en;
            default -> hk;
        };
    }

    private static String strParam(Map<String, Object> params, String key, String fallback) {
        if (params == null || params.get(key) == null) {
            return fallback;
        }
        return String.valueOf(params.get(key));
    }

    private static String numParam(Map<String, Object> params, String key, int scale) {
        if (params == null || params.get(key) == null) {
            return "—";
        }
        Object raw = params.get(key);
        if (raw instanceof BigDecimal bd) {
            return bd.setScale(scale, RoundingMode.HALF_UP).toPlainString();
        }
        return String.valueOf(raw);
    }

    private static String pctParam(Map<String, Object> params, String key) {
        if (params == null || params.get(key) == null) {
            return "0.0%";
        }
        Object raw = params.get(key);
        if (raw instanceof BigDecimal bd) {
            return bd.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
        }
        return String.valueOf(raw);
    }
}
