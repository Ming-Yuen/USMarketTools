package com.ykm.stock.analytics.service.report;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.AppliedFiltersDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.MarketBackgroundDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.ScopeSummaryDto;
import com.ykm.stock.analytics.service.report.BuyRankingReportService.CachedReportSnapshot;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建議買入排行榜報表匯出服務：
 * 1. Excel (.xlsx)：Apache POI 雙分頁（Sheet 1 排行榜含凍結標題列、真實數值格式與四梯隊配色；Sheet 2 篩選條件與市場背景）。
 * 2. CSV (.csv)：UTF-8 with BOM（EF BB BF）、CRLF、RFC 4180 雙引號跳脫與純文字欄位公式注入防禦。
 * 所有資料皆取自 BuyRankingReportService 單一不可變快照，確保與前端畫面 100% 一致。
 */
@Service
public class ReportExportService {
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter HK_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'HKT'");
    private static final ZoneId HK_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DateTimeService dateTimeService;

    @Autowired
    public ReportExportService(DateTimeService dateTimeService) {
        this.dateTimeService = dateTimeService;
    }

    public ReportExportService() {
        this(null);
    }

    public record ExportedFile(String fileName, String contentType, byte[] content) {}

    private record TierStyleBundle(
            CellStyle text,
            CellStyle wrapText,
            CellStyle integer,
            CellStyle currency,
            CellStyle percent,
            CellStyle decimal2
    ) {}

    public ExportedFile export(CachedReportSnapshot snapshot, String format) {
        String normalizedFormat = format == null ? "" : format.trim().toUpperCase(java.util.Locale.ROOT);
        if (BuyRankingDtos.EXPORT_CSV.equals(normalizedFormat)) {
            byte[] bytes = generateCsv(snapshot);
            String fileName = buildFileName(snapshot, "csv");
            return new ExportedFile(fileName, "text/csv; charset=UTF-8", bytes);
        }
        byte[] bytes = generateXlsx(snapshot);
        String fileName = buildFileName(snapshot, "xlsx");
        return new ExportedFile(fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    public String buildFileName(CachedReportSnapshot snapshot, String extension) {
        String rawScope = snapshot.scopeSummary() != null ? snapshot.scopeSummary().scopeLabel() : "Report";
        String sanitized = sanitizeScopeSlug(rawScope);
        String datePart = snapshot.asOfDate() != null ? snapshot.asOfDate().format(FILE_DATE_FMT) : "00000000";
        return "USMarketTools_BuyRanking_" + sanitized + "_" + datePart + "." + extension;
    }

    static String sanitizeScopeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Report";
        }
        String ascii = raw.trim().replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_");
        ascii = ascii.replaceAll("^_+|_+$", "");
        if (ascii.isEmpty()) {
            return "Report";
        }
        return ascii.length() > 40 ? ascii.substring(0, 40) : ascii;
    }

    /** 產生雙分頁 Excel (.xlsx) 檔案。 */
    public byte[] generateXlsx(CachedReportSnapshot snapshot) {
        String loc = BuyRankingReasonFormatter.normalizeLocale(snapshot.resolvedLocale(), null);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat df = wb.createDataFormat();
            short fmtInt = df.getFormat("#,##0");
            short fmtCurrency = df.getFormat("$#,##0.00");
            short fmtPercent = df.getFormat("0.0%");
            short fmtPercent2 = df.getFormat("0.00%");
            short fmtDecimal2 = df.getFormat("0.00");
            short fmtBps = df.getFormat("+0.0\" bps\";-0.0\" bps\";0.0\" bps\"");

            XSSFCellStyle headerStyle = createHeaderStyle(wb);
            Map<String, TierStyleBundle> tierStyles = new HashMap<>();
            tierStyles.put(BuyRankingDtos.TIER_1, createTierStyleBundle(wb, "#F0FDF4", "#166534", fmtInt, fmtCurrency, fmtPercent, fmtDecimal2));
            tierStyles.put(BuyRankingDtos.TIER_2, createTierStyleBundle(wb, "#EFF6FF", "#1E40AF", fmtInt, fmtCurrency, fmtPercent, fmtDecimal2));
            tierStyles.put(BuyRankingDtos.TIER_3, createTierStyleBundle(wb, "#FEFCE8", "#854D0E", fmtInt, fmtCurrency, fmtPercent, fmtDecimal2));
            tierStyles.put(BuyRankingDtos.TIER_4, createTierStyleBundle(wb, "#FEE2E2", "#991B1B", fmtInt, fmtCurrency, fmtPercent, fmtDecimal2));

            // Sheet 1: 建議買入排行榜
            String sheet1Name = pick(loc, "建議買入排行榜", "建议买入排行榜", "Buy Ranking");
            Sheet sheet1 = wb.createSheet(sheet1Name);
            sheet1.createFreezePane(0, 1);

            String[] headers = columnHeaders(loc);
            Row headerRow = sheet1.createRow(0);
            headerRow.setHeightInPoints(28f);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(headerStyle);
            }

            List<BuyRankingItemDto> items = snapshot.fullSortedItems();
            boolean staleDateFallback = snapshot.marketContext() != null && snapshot.marketContext().staleDateFallback();
            for (int i = 0; i < items.size(); i++) {
                BuyRankingItemDto item = items.get(i);
                TierStyleBundle styles = tierStyles.getOrDefault(item.tier(), tierStyles.get(BuyRankingDtos.TIER_4));
                Row row = sheet1.createRow(i + 1);
                writeRankingRow(row, item, staleDateFallback, loc, styles);
            }

            int[] widths = new int[]{
                    8, 22, 11, 24, 20, 13, 12,
                    12, 12, 12, 12, 12,
                    12, 12, 11,
                    12, 16, 28, 13, 12, 11, 12,
                    12, 46
            };
            for (int c = 0; c < widths.length; c++) {
                sheet1.setColumnWidth(c, widths[c] * 256);
            }

            // Sheet 2: 篩選條件與市場背景
            String sheet2Name = pick(loc, "篩選條件與市場背景", "筛选条件与市场背景", "Filters & Market Context");
            Sheet sheet2 = wb.createSheet(sheet2Name);
            sheet2.createFreezePane(0, 1);
            writeSummarySheet(wb, sheet2, snapshot, loc, headerStyle, fmtInt, fmtPercent2, fmtDecimal2, fmtBps);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate XLSX export", ex);
        }
    }

    private void writeRankingRow(Row row, BuyRankingItemDto item, boolean staleDateFallback, String loc, TierStyleBundle s) {
        int col = 0;
        setIntCell(row, col++, item.rank(), s.integer());
        setTextCell(row, col++, BuyRankingReasonFormatter.tierLabel(item.tier(), loc), s.text());
        setTextCell(row, col++, item.symbol(), s.text());
        setTextCell(row, col++, item.stockName(), s.wrapText());
        setTextCell(row, col++, item.sector(), s.text());
        setTextCell(row, col++, BuyRankingReasonFormatter.assetTypeLabel(item.assetType(), loc), s.text());
        setCurrencyCell(row, col++, item.currentPrice(), s.currency());

        BuyZoneDto bz = item.buyZone();
        setCurrencyCell(row, col++, bz == null ? null : bz.low(), s.currency());
        setCurrencyCell(row, col++, bz == null ? null : bz.mid(), s.currency());
        setCurrencyCell(row, col++, bz == null ? null : bz.high(), s.currency());
        setPercentCell(row, col++, item.distanceToBuyZonePct(), s.percent());
        setCurrencyCell(row, col++, item.stopLossPrice(), s.currency());

        setPercentCell(row, col++, item.stopLossDistancePct(), s.percent());
        setDecimalCell(row, col++, item.netRiskReward(), s.decimal2());
        setDecimalCell(row, col++, item.tierScore(), s.decimal2());

        setLongCell(row, col++, item.suggestedShares(), s.integer());
        setTextCell(row, col++, BuyRankingReasonFormatter.scaleTagLabel(item.positionScaleTag(), item.positionSizingTags(), loc), s.text());
        setTextCell(row, col++, BuyRankingReasonFormatter.formatSizingConditions(item, staleDateFallback, loc), s.wrapText());
        setPercentCell(row, col++, item.valuationDiscountPct(), s.percent());
        setDecimalCell(row, col++, item.forwardPeg(), s.decimal2());
        setPercentCell(row, col++, item.roic(), s.percent());
        setPercentCell(row, col++, item.reverseDcfImpliedGrowth(), s.percent());

        setIntCell(row, col++, item.daysToEarnings(), s.integer());
        setTextCell(row, col, item.actionReason(), s.wrapText());
    }

    private void writeSummarySheet(
            XSSFWorkbook wb,
            Sheet sheet,
            CachedReportSnapshot snapshot,
            String loc,
            XSSFCellStyle headerStyle,
            short fmtInt,
            short fmtPercent2,
            short fmtDecimal2,
            short fmtBps) {
        XSSFCellStyle labelStyle = wb.createCellStyle();
        XSSFFont boldFont = wb.createFont();
        boldFont.setBold(true);
        labelStyle.setFont(boldFont);
        labelStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        XSSFCellStyle valText = wb.createCellStyle();
        valText.setWrapText(true);
        valText.setVerticalAlignment(VerticalAlignment.CENTER);

        XSSFCellStyle valInt = wb.createCellStyle();
        valInt.setDataFormat(fmtInt);

        XSSFCellStyle valPct2 = wb.createCellStyle();
        valPct2.setDataFormat(fmtPercent2);

        XSSFCellStyle valDec2 = wb.createCellStyle();
        valDec2.setDataFormat(fmtDecimal2);

        XSSFCellStyle valBps = wb.createCellStyle();
        valBps.setDataFormat(fmtBps);

        Row header = sheet.createRow(0);
        header.setHeightInPoints(24f);
        Cell h0 = header.createCell(0);
        h0.setCellValue(pick(loc, "項目", "项目", "Item"));
        h0.setCellStyle(headerStyle);
        Cell h1 = header.createCell(1);
        h1.setCellValue(pick(loc, "數值／設定內容", "数值／设置内容", "Value / Applied Setting"));
        h1.setCellStyle(headerStyle);

        int r = 1;
        MarketBackgroundDto bg = snapshot.marketContext();
        ScopeSummaryDto scope = snapshot.scopeSummary();
        AppliedFiltersDto f = snapshot.appliedFilters();

        String exportedAtHk = dateTimeService != null
                ? dateTimeService.now().atZone(HK_ZONE).format(HK_TS_FMT)
                : snapshot.generatedAtHk();
        r = addTextKv(sheet, r, pick(loc, "報表快照產生時間（香港時間）", "报表快照产生时间（香港时间）", "Snapshot Generated At (HKT)"), snapshot.generatedAtHk(), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "檔案下載匯出時間（香港時間）", "档案下载导出时间（香港时间）", "File Exported At (HKT)"), exportedAtHk, labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "基準美東交易日 (As-Of Date)", "基准美东交易日 (As-Of Date)", "Benchmark US Trading Day (As-Of)"),
                snapshot.asOfDate() == null ? "" : snapshot.asOfDate().toString(), labelStyle, valText);
        if (bg != null && bg.lastClosedTradingDay() != null) {
            r = addTextKv(sheet, r, pick(loc, "最新已收市美東交易日", "最新已收市美东交易日", "Latest Closed US Trading Day"),
                    bg.lastClosedTradingDay().toString(), labelStyle, valText);
            r = addTextKv(sheet, r, pick(loc, "資料日是否回退（僅供試算參考）", "资料日是否回退（仅供试算参考）", "Stale Date Fallback (Reference Only)"),
                    bg.staleDateFallback() ? "YES" : "NO", labelStyle, valText);
        }
        r = addTextKv(sheet, r, pick(loc, "觀察截點 (UTC)", "观察截点 (UTC)", "Observation Cutoff (UTC)"),
                snapshot.observationCutoff() == null ? "" : snapshot.observationCutoff().toString(), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "報表編號 (Report ID)", "报表编号 (Report ID)", "Report ID"), snapshot.reportId(), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "匯出語系", "导出语系", "Locale"), snapshot.resolvedLocale(), labelStyle, valText);

        r = addTextKv(sheet, r, pick(loc, "當日大盤市況 (MarketRegime)", "当日大盘市况 (MarketRegime)", "Market Regime"),
                bg == null ? "" : bg.marketRegime(), labelStyle, valText);
        r = addNumKv(sheet, r, pick(loc, "VIX 恐慌指數", "VIX 恐慌指数", "VIX Close"), bg == null ? null : bg.vixClose(), labelStyle, valDec2);
        BigDecimal tenYrRatio = bg != null && bg.tenYearYieldPct() != null
                ? bg.tenYearYieldPct().divide(HUNDRED, 6, RoundingMode.HALF_UP) : null;
        r = addNumKv(sheet, r, pick(loc, "10 年期美債殖利率", "10 年期美债收益率", "US 10Y Treasury Yield"), tenYrRatio, labelStyle, valPct2);
        BigDecimal hyOasRatio = bg != null && bg.hyOasPct() != null
                ? bg.hyOasPct().divide(HUNDRED, 6, RoundingMode.HALF_UP) : null;
        r = addNumKv(sheet, r, pick(loc, "高收益債信用利差 (HY OAS)", "高收益债信用利差 (HY OAS)", "HY OAS Credit Spread"), hyOasRatio, labelStyle, valPct2);
        r = addNumKv(sheet, r, pick(loc, "HY OAS 20 日均線變化", "HY OAS 20 日均线变化", "HY OAS Delta vs 20D Mean"),
                bg == null ? null : bg.hyOasDeltaBps(), labelStyle, valBps);
        r = addTextKv(sheet, r, pick(loc, "HY OAS 狀態", "HY OAS 状态", "HY OAS Status"),
                bg == null || bg.hyOasStatus() == null ? "—" : bg.hyOasStatus(), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "宏觀警戒縮倉", "宏观警戒缩仓", "Macro Caution Active"),
                bg != null && bg.macroCautionActive() ? "YES" : "NO", labelStyle, valText);

        r = addTextKv(sheet, r, pick(loc, "篩選範圍模式", "筛选范围模式", "Scope Mode"), f.scopeMode(), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "產業代碼", "行业代码", "Industry Codes"), joinOrDash(f.industryCodes()), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "個人清單類型", "个人清单类型", "Portfolio Types"), joinOrDash(f.portfolioTypes()), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "自訂代碼", "自订代码", "Custom Symbols"), joinOrDash(f.customSymbols()), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "標的類型", "标的类型", "Asset Types"), joinOrDash(f.assetTypes()), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "排除 REITs", "排除 REITs", "Exclude REITs"), f.excludeReits() ? "YES" : "NO", labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "納入梯隊", "纳入梯队", "Included Tiers"), joinOrDash(f.tiers()), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "最低基本面門檻", "最低基本面门槛", "Min Fundamental Grade"), f.minFundamentalGrade(), labelStyle, valText);
        r = addNumKv(sheet, r, pick(loc, "最低淨賺賠比 (Net R/R)", "最低净赚赔比 (Net R/R)", "Min Net R/R"), f.minNetRiskReward(), labelStyle, valDec2);
        r = addIntKv(sheet, r, pick(loc, "排除財報天數上限", "排除财报天数上限", "Exclude Earnings Within Days"),
                f.excludeEarningsWithinDays(), labelStyle, valInt);
        r = addTextKv(sheet, r, pick(loc, "排序方式", "排序方式", "Sort By"), f.sortBy(), labelStyle, valText);
        r = addTextKv(sheet, r, pick(loc, "分析週期", "分析周期", "Horizon"), f.horizon(), labelStyle, valText);

        r = addIntKv(sheet, r, pick(loc, "命中總標的數", "命中总标的数", "Matched Total Candidates"),
                scope == null ? 0 : scope.matchedTotalCount(), labelStyle, valInt);
        r = addIntKv(sheet, r, pick(loc, "實際評估標的數（上限 300）", "实际评估标的数（上限 300）", "Evaluated Candidates (Max 300)"),
                scope == null ? 0 : scope.evaluatedCount(), labelStyle, valInt);
        r = addTextKv(sheet, r, pick(loc, "是否觸發 300 檔截斷", "是否触发 300 档截断", "Truncated at 300 Cap"),
                scope != null && scope.truncated() ? "YES" : "NO", labelStyle, valText);
        r = addIntKv(sheet, r, pick(loc, "篩選後匯出總筆數", "筛选后导出总笔数", "Exported Total Rows"),
                snapshot.fullSortedItems().size(), labelStyle, valInt);

        Map<String, Integer> tc = snapshot.tierCounts();
        r = addIntKv(sheet, r, BuyRankingReasonFormatter.tierLabel(BuyRankingDtos.TIER_1, loc), tc.getOrDefault(BuyRankingDtos.TIER_1, 0), labelStyle, valInt);
        r = addIntKv(sheet, r, BuyRankingReasonFormatter.tierLabel(BuyRankingDtos.TIER_2, loc), tc.getOrDefault(BuyRankingDtos.TIER_2, 0), labelStyle, valInt);
        r = addIntKv(sheet, r, BuyRankingReasonFormatter.tierLabel(BuyRankingDtos.TIER_3, loc), tc.getOrDefault(BuyRankingDtos.TIER_3, 0), labelStyle, valInt);
        addIntKv(sheet, r, BuyRankingReasonFormatter.tierLabel(BuyRankingDtos.TIER_4, loc), tc.getOrDefault(BuyRankingDtos.TIER_4, 0), labelStyle, valInt);

        sheet.setColumnWidth(0, 34 * 256);
        sheet.setColumnWidth(1, 56 * 256);
    }

    /** 產生 UTF-8 with BOM CSV（CRLF、RFC 4180 跳脫與純文字欄位公式注入防護）。 */
    public byte[] generateCsv(CachedReportSnapshot snapshot) {
        String loc = BuyRankingReasonFormatter.normalizeLocale(snapshot.resolvedLocale(), null);
        StringBuilder sb = new StringBuilder();
        String[] headers = columnHeaders(loc);
        for (int i = 0; i < headers.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvTextCell(headers[i]));
        }
        sb.append("\r\n");

        boolean staleDateFallback = snapshot.marketContext() != null && snapshot.marketContext().staleDateFallback();
        for (BuyRankingItemDto item : snapshot.fullSortedItems()) {
            BuyZoneDto bz = item.buyZone();
            String[] cols = new String[]{
                    csvNumInt(item.rank()),
                    csvTextCell(BuyRankingReasonFormatter.tierLabel(item.tier(), loc)),
                    csvTextCell(item.symbol()),
                    csvTextCell(item.stockName()),
                    csvTextCell(item.sector()),
                    csvTextCell(BuyRankingReasonFormatter.assetTypeLabel(item.assetType(), loc)),
                    csvNumPrice(item.currentPrice()),
                    csvNumPrice(bz == null ? null : bz.low()),
                    csvNumPrice(bz == null ? null : bz.mid()),
                    csvNumPrice(bz == null ? null : bz.high()),
                    csvNumPercent(item.distanceToBuyZonePct()),
                    csvNumPrice(item.stopLossPrice()),
                    csvNumPercent(item.stopLossDistancePct()),
                    csvNumDecimal2(item.netRiskReward()),
                    csvNumDecimal2(item.tierScore()),
                    csvNumLong(item.suggestedShares()),
                    csvTextCell(BuyRankingReasonFormatter.scaleTagLabel(item.positionScaleTag(), item.positionSizingTags(), loc)),
                    csvTextCell(BuyRankingReasonFormatter.formatSizingConditions(item, staleDateFallback, loc)),
                    csvNumPercent(item.valuationDiscountPct()),
                    csvNumDecimal2(item.forwardPeg()),
                    csvNumPercent(item.roic()),
                    csvNumPercent(item.reverseDcfImpliedGrowth()),
                    csvNumInt(item.daysToEarnings()),
                    csvTextCell(item.actionReason())
            };
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(cols[i]);
            }
            sb.append("\r\n");
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    /** 純文字欄位防範 CSV 公式注入（含前導空白／控制字元與全形公式起始符），完成後套用 RFC 4180 雙引號跳脫。 */
    static String csvTextCell(String raw) {
        if (raw == null) {
            return "";
        }
        String safe = neutralizeFormulaText(raw);
        boolean needsQuotes = false;
        for (int i = 0; i < safe.length(); i++) {
            char ch = safe.charAt(i);
            if (ch == ',' || ch == '"' || ch == '\r' || ch == '\n') {
                needsQuotes = true;
                break;
            }
        }
        if (!needsQuotes) {
            return safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    static String neutralizeFormulaText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (isDangerousFormulaLead(first)) {
            return "'" + value;
        }
        int idx = 0;
        while (idx < value.length() && (Character.isWhitespace(value.charAt(idx)) || Character.isISOControl(value.charAt(idx)))) {
            idx++;
        }
        if (idx < value.length() && isDangerousFormulaLead(value.charAt(idx))) {
            return "'" + value;
        }
        return value;
    }

    private static boolean isDangerousFormulaLead(char ch) {
        return ch == '=' || ch == '+' || ch == '-' || ch == '@' || ch == '\t' || ch == '\r' || ch == '\n'
                || ch == '＝' || ch == '＋' || ch == '－' || ch == '＠';
    }

    private static String csvNumInt(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String csvNumLong(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String csvNumPrice(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String csvNumDecimal2(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String csvNumPercent(BigDecimal ratio) {
        if (ratio == null) {
            return "";
        }
        return ratio.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String[] columnHeaders(String loc) {
        if (BuyRankingReasonFormatter.LOCALE_ZH_CN.equals(loc)) {
            return new String[]{
                    "排名", "梯队", "股票代码", "公司名称", "行业分类", "标的类型", "现价",
                    "买区下缘", "买区中位", "买区上缘", "距买区距离", "保护止损价",
                    "止损幅度", "净赚赔比 (Net R/R)", "梯队评分",
                    "建议买入股数", "仓位缩放标记", "仓位状态与风控提示", "相对估值中位价", "Forward PEG", "ROIC", "Reverse DCF 隐含成长",
                    "距财报天数", "评估决策理由"
            };
        }
        if (BuyRankingReasonFormatter.LOCALE_EN.equals(loc)) {
            return new String[]{
                    "Rank", "Tier", "Symbol", "Name", "Sector", "Asset Type", "Price",
                    "Buy Low", "Buy Mid", "Buy High", "Dist to Buy Zone", "Stop Loss",
                    "Stop Dist %", "Net R/R", "Tier Score",
                    "Suggested Shares", "Position Scale", "Sizing Status & Warnings", "Valuation vs Mid", "Forward PEG", "ROIC", "Reverse DCF Growth",
                    "Days to Earnings", "Decision / Exclusion Reason"
            };
        }
        return new String[]{
                "排名", "梯隊", "股票代碼", "公司名稱", "產業分類", "標的類型", "現價",
                "買區下緣", "買區中位", "買區上緣", "距買區距離", "保護止損價",
                "止損幅度", "淨賺賠比 (Net R/R)", "梯隊評分",
                "建議買入股數", "倉位縮放標記", "倉位狀態與風控提示", "相對估值中位價", "Forward PEG", "ROIC", "Reverse DCF 隱含成長",
                "距財報天數", "評估決策理由"
        };
    }

    private XSSFCellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(hexColor("#1E293B"));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont font = wb.createFont();
        font.setBold(true);
        font.setColor(hexColor("#FFFFFF"));
        style.setFont(font);
        style.setWrapText(true);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorder(style);
        return style;
    }

    private TierStyleBundle createTierStyleBundle(
            XSSFWorkbook wb, String bgHex, String fgHex,
            short fmtInt, short fmtCurrency, short fmtPercent, short fmtDecimal2) {
        return new TierStyleBundle(
                buildStyledCell(wb, bgHex, fgHex, null, false, HorizontalAlignment.LEFT),
                buildStyledCell(wb, bgHex, fgHex, null, true, HorizontalAlignment.LEFT),
                buildStyledCell(wb, bgHex, fgHex, fmtInt, false, HorizontalAlignment.RIGHT),
                buildStyledCell(wb, bgHex, fgHex, fmtCurrency, false, HorizontalAlignment.RIGHT),
                buildStyledCell(wb, bgHex, fgHex, fmtPercent, false, HorizontalAlignment.RIGHT),
                buildStyledCell(wb, bgHex, fgHex, fmtDecimal2, false, HorizontalAlignment.RIGHT)
        );
    }

    private XSSFCellStyle buildStyledCell(
            XSSFWorkbook wb, String bgHex, String fgHex, Short dataFormat, boolean wrap, HorizontalAlignment align) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(hexColor(bgHex));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        XSSFFont font = wb.createFont();
        font.setColor(hexColor(fgHex));
        style.setFont(font);
        style.setWrapText(wrap);
        style.setAlignment(align);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        if (dataFormat != null) {
            style.setDataFormat(dataFormat);
        }
        applyThinBorder(style);
        return style;
    }

    private void applyThinBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private XSSFColor hexColor(String hex) {
        String clean = hex.startsWith("#") ? hex.substring(1) : hex;
        byte r = (byte) Integer.parseInt(clean.substring(0, 2), 16);
        byte g = (byte) Integer.parseInt(clean.substring(2, 4), 16);
        byte b = (byte) Integer.parseInt(clean.substring(4, 6), 16);
        return new XSSFColor(new byte[]{r, g, b}, null);
    }

    private void setTextCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    private void setIntCell(Row row, int col, Integer value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private void setLongCell(Row row, int col, Long value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private void setCurrencyCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private void setPercentCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private void setDecimalCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private int addTextKv(Sheet sheet, int rowIdx, String label, String value, CellStyle kStyle, CellStyle vStyle) {
        Row row = sheet.createRow(rowIdx);
        Cell k = row.createCell(0);
        k.setCellValue(label);
        k.setCellStyle(kStyle);
        Cell v = row.createCell(1);
        v.setCellValue(value == null ? "" : value);
        v.setCellStyle(vStyle);
        return rowIdx + 1;
    }

    private int addNumKv(Sheet sheet, int rowIdx, String label, BigDecimal value, CellStyle kStyle, CellStyle vStyle) {
        Row row = sheet.createRow(rowIdx);
        Cell k = row.createCell(0);
        k.setCellValue(label);
        k.setCellStyle(kStyle);
        Cell v = row.createCell(1);
        if (value != null) {
            v.setCellValue(value.doubleValue());
        }
        v.setCellStyle(vStyle);
        return rowIdx + 1;
    }

    private int addIntKv(Sheet sheet, int rowIdx, String label, Integer value, CellStyle kStyle, CellStyle vStyle) {
        Row row = sheet.createRow(rowIdx);
        Cell k = row.createCell(0);
        k.setCellValue(label);
        k.setCellStyle(kStyle);
        Cell v = row.createCell(1);
        if (value != null) {
            v.setCellValue(value.doubleValue());
        }
        v.setCellStyle(vStyle);
        return rowIdx + 1;
    }

    private String joinOrDash(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "—";
        }
        return String.join(", ", values);
    }

    private String pick(String locale, String hk, String cn, String en) {
        return switch (locale) {
            case BuyRankingReasonFormatter.LOCALE_ZH_CN -> cn;
            case BuyRankingReasonFormatter.LOCALE_EN -> en;
            default -> hk;
        };
    }
}
