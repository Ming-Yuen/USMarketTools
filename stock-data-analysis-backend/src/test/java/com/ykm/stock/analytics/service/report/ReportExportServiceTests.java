package com.ykm.stock.analytics.service.report;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.AppliedFiltersDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyRankingItemDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.BuyZoneDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.MarketBackgroundDto;
import com.ykm.stock.analytics.model.dto.stockInsight.report.BuyRankingDtos.ScopeSummaryDto;
import com.ykm.stock.analytics.service.report.BuyRankingReportService.CachedReportSnapshot;
import com.ykm.stock.analytics.service.report.ReportExportService.ExportedFile;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportExportServiceTests {
    private final DateTimeService dateTimeService = mock(DateTimeService.class);
    private final ReportExportService exportService = new ReportExportService(dateTimeService);

    @Test
    void testCase2XlsxGeneratesTwoSheetsWithFreezePaneNumericFormatsAndTierColors() throws Exception {
        when(dateTimeService.now()).thenReturn(Instant.parse("2026-11-20T04:05:30Z"));
        CachedReportSnapshot snapshot = sampleSnapshot("zh-HK");
        ExportedFile exported = exportService.export(snapshot, BuyRankingDtos.EXPORT_XLSX);

        assertThat(exported.fileName()).isEqualTo("USMarketTools_BuyRanking_Semiconductors_20261119.xlsx");
        assertThat(exported.contentType()).contains("spreadsheetml.sheet");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(exported.content()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);

            XSSFSheet sheet1 = wb.getSheetAt(0);
            assertThat(sheet1.getSheetName()).isEqualTo("建議買入排行榜");
            assertThat(sheet1.getPaneInformation()).isNotNull();
            assertThat(sheet1.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet1.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);

            // 標題列 + 2 筆資料
            assertThat(sheet1.getPhysicalNumberOfRows()).isEqualTo(3);

            Row row1 = sheet1.getRow(1);
            assertThat(row1.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row1.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("NVDA");
            assertThat(row1.getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row1.getCell(6).getNumericCellValue()).isEqualTo(135.20);
            assertThat(row1.getCell(6).getCellStyle().getDataFormatString()).isEqualTo("$#,##0.00");
            assertThat(row1.getCell(15).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row1.getCell(15).getNumericCellValue()).isEqualTo(45.0);
            assertThat(row1.getCell(15).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            // Col 17: 倉位狀態與風控提示（即時可執行 + 超過設定上限警告，不寫死 30%）
            assertThat(row1.getCell(17).getStringCellValue()).contains("即時可執行").contains("超過設定上限");
            // Col 18: 相對估值中位價
            assertThat(row1.getCell(18).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row1.getCell(18).getNumericCellValue()).isEqualTo(-0.152);
            assertThat(row1.getCell(18).getCellStyle().getDataFormatString()).isEqualTo("0.0%");

            XSSFCellStyle tier1Style = (XSSFCellStyle) row1.getCell(1).getCellStyle();
            assertThat(tier1Style.getFillForegroundXSSFColor().getARGBHex()).endsWith("F0FDF4");
            assertThat(tier1Style.getFont().getXSSFColor().getARGBHex()).endsWith("166534");

            Row row2 = sheet1.getRow(2);
            assertThat(row2.getCell(2).getStringCellValue()).isEqualTo("TRAP");
            XSSFCellStyle tier4Style = (XSSFCellStyle) row2.getCell(1).getCellStyle();
            assertThat(tier4Style.getFillForegroundXSSFColor().getARGBHex()).endsWith("FEE2E2");
            assertThat(tier4Style.getFont().getXSSFColor().getARGBHex()).endsWith("991B1B");

            XSSFSheet sheet2 = wb.getSheetAt(1);
            assertThat(sheet2.getSheetName()).isEqualTo("篩選條件與市場背景");
            assertThat(sheet2.getPhysicalNumberOfRows()).isGreaterThan(15);
            StringBuilder s2Text = new StringBuilder();
            for (int i = 0; i <= sheet2.getLastRowNum(); i++) {
                Row r = sheet2.getRow(i);
                if (r != null && r.getCell(0) != null) {
                    s2Text.append(r.getCell(0).getStringCellValue()).append('|');
                    if (r.getCell(1) != null && r.getCell(1).getCellType() == CellType.STRING) {
                        s2Text.append(r.getCell(1).getStringCellValue());
                    }
                    s2Text.append('\n');
                }
            }
            assertThat(s2Text.toString())
                    .contains("RISK_ON")
                    .contains("2026-11-19")
                    .contains("2026-11-20 12:00:00 HKT")
                    .contains("2026-11-20 12:05:30 HKT");
        }
    }

    @Test
    void testCase2CsvStartsWithUtf8BomEscapesSpecialCharsAndNeutralizesFormulaInjection() {
        CachedReportSnapshot snapshot = sampleSnapshot("zh-HK");
        ExportedFile exported = exportService.export(snapshot, BuyRankingDtos.EXPORT_CSV);

        assertThat(exported.fileName()).isEqualTo("USMarketTools_BuyRanking_Semiconductors_20261119.csv");
        byte[] bytes = exported.content();
        assertThat(bytes.length).isGreaterThan(3);
        // UTF-8 BOM: EF BB BF
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);

        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).contains("排名,梯隊,股票代碼");
        // 負數百分比維持純數值輸出，不誤加單引號；且保留倉位可執行狀態與板塊上限警示
        assertThat(lines[1]).contains(",-15.2%,");
        assertThat(lines[1]).contains(",-5.6%,");
        assertThat(lines[1]).contains("即時可執行").contains("超過設定上限");
        // 公式樣文字欄位（如 "=CMD|..." 與含逗號雙引號）須被中和並正確跳脫
        assertThat(lines[2]).contains("\"'=HYPERLINK(\"\"bad\"\"), Corp\"");
    }

    @Test
    void filenameSanitizesNonAsciiAndControlChars() {
        assertThat(ReportExportService.sanitizeScopeSlug("Semiconductors")).isEqualTo("Semiconductors");
        assertThat(ReportExportService.sanitizeScopeSlug("Software—Application / Cloud\r\n")).isEqualTo("Software_Application_Cloud");
        assertThat(ReportExportService.sanitizeScopeSlug("半導體")).isEqualTo("Report");
    }

    @Test
    void zeroShareCapExhaustedDoesNotExportActionableNowAndStaleFallbackPropagatesToTier4() {
        BuyRankingItemDto zeroCap = new BuyRankingItemDto(
                1, 1, BuyRankingDtos.TIER_1, new BigDecimal("85.00"),
                "FIGI_Z", "CAP0", "Capped Corp", "USD", "SEMI", "Semiconductors",
                BuyRankingDtos.ASSET_COMMON_STOCK, "Common Stock", "DEEP_UNDERVALUED", "STRONG",
                "ACCUMULATE_AT_SUPPORT", null, new BigDecimal("100.00"),
                new BuyZoneDto(new BigDecimal("98.00"), new BigDecimal("99.50"), new BigDecimal("101.00")),
                BigDecimal.ZERO, true, "TRADE_PLAN_ENTRY",
                new BigDecimal("94.00"), new BigDecimal("-0.060"), new BigDecimal("125.00"),
                new BigDecimal("3.80"), new BigDecimal("3.80"),
                0L, "FULL", "AVAILABLE", true, "SINGLE_POSITION_CAP_EXHAUSTED", true, List.of(),
                new BigDecimal("-0.150"), new BigDecimal("1.10"), new BigDecimal("20.0"),
                new BigDecimal("0.22"), new BigDecimal("1000000000"), new BigDecimal("0.10"),
                "SBC_ADJUSTED_FCF", "AVAILABLE", null, null, null,
                null, null, "INFLOW", new BigDecimal("0.12"),
                "ACCUMULATE_AT_SUPPORT_ACTIVE", Map.of(), "ok");
        BuyRankingItemDto tier4EmptyTags = new BuyRankingItemDto(
                2, 2, BuyRankingDtos.TIER_4, null,
                "FIGI_T4", "T4", "Trap", "USD", "SEMI", "Semiconductors",
                BuyRankingDtos.ASSET_COMMON_STOCK, "Common Stock", "INSUFFICIENT_EVIDENCE", "INSUFFICIENT_DATA",
                "INSUFFICIENT_DATA", null, null, null, null, false, "NONE",
                null, null, null, null, null,
                0L, "NOT_RECOMMENDED", "NOT_RECOMMENDED", false, "TIER_4_NOT_RECOMMENDED", false, List.of(),
                null, null, null, null, null, null, null, "NOT_APPLICABLE",
                null, null, null, null, null, null, null, "QUOTE_MISSING", Map.of(), "missing");

        String capTextEn = BuyRankingReasonFormatter.formatSizingConditions(zeroCap, true, "en");
        assertThat(capTextEn).doesNotContain("Actionable Now")
                .contains("Zero Shares")
                .contains("SINGLE_POSITION_CAP_EXHAUSTED")
                .contains("Configured Cap")
                .doesNotContain("30%")
                .contains("Stale Data Date Fallback");

        String tier4TextHk = BuyRankingReasonFormatter.formatSizingConditions(tier4EmptyTags, true, "zh-HK");
        assertThat(tier4TextHk).contains("暫不建議").contains("資料日回退");

        BuyRankingItemDto tier2Item = new BuyRankingItemDto(
                3, 3, BuyRankingDtos.TIER_2, null,
                "FIGI_T2", "T2", "Wait Pullback Corp", "USD", "SEMI", "Semiconductors",
                BuyRankingDtos.ASSET_COMMON_STOCK, "Common Stock", "MODERATELY_UNDERVALUED", "STRONG",
                "WATCH_PULLBACK_TO_BAND", null, new BigDecimal("108.00"),
                new BuyZoneDto(new BigDecimal("95.00"), new BigDecimal("97.50"), new BigDecimal("100.00")),
                new BigDecimal("0.080000"), false, "TRADE_PLAN_ENTRY",
                new BigDecimal("90.00"), new BigDecimal("-0.1667"), new BigDecimal("122.00"),
                new BigDecimal("0.75"), new BigDecimal("2.15"),
                100L, "FULL", "AVAILABLE", false, null, false, List.of(),
                new BigDecimal("-0.100"), new BigDecimal("1.10"), new BigDecimal("20.0"),
                new BigDecimal("0.22"), new BigDecimal("1000000000"), new BigDecimal("0.10"),
                "SBC_ADJUSTED_FCF", "AVAILABLE", null, null, null,
                null, null, "INFLOW", new BigDecimal("0.12"),
                "WAIT_PULLBACK_TO_BUY_ZONE", Map.of("distancePct", new BigDecimal("0.08")), "wait");
        assertThat(BuyRankingReasonFormatter.formatSizingConditions(tier2Item, false, "zh-HK"))
                .contains("觸發後試算").contains("計畫 R/R 2.15");
        assertThat(BuyRankingReasonFormatter.formatSizingConditions(tier2Item, false, "zh-CN"))
                .contains("触发后试算").contains("计划 R/R 2.15");
        assertThat(BuyRankingReasonFormatter.formatSizingConditions(tier2Item, false, "en"))
                .contains("On-Trigger Preview").contains("Plan R/R 2.15");
    }

    private CachedReportSnapshot sampleSnapshot(String locale) {
        BuyRankingItemDto tier1 = new BuyRankingItemDto(
                1, 1, BuyRankingDtos.TIER_1, new BigDecimal("86.40"),
                "FIGI_NVDA", "NVDA", "NVIDIA Corp", "USD", "SEMI", "Semiconductors",
                BuyRankingDtos.ASSET_COMMON_STOCK, "Common Stock", "DEEP_UNDERVALUED", "STRONG",
                "ACCUMULATE_AT_SUPPORT", null, new BigDecimal("135.20"),
                new BuyZoneDto(new BigDecimal("128.00"), new BigDecimal("129.75"), new BigDecimal("131.50")),
                BigDecimal.ZERO, true, "TRADE_PLAN_ENTRY",
                new BigDecimal("127.63"), new BigDecimal("-0.056"), new BigDecimal("162.00"),
                new BigDecimal("3.45"), new BigDecimal("3.50"),
                45L, "1/3", "AVAILABLE", true, null, true, List.of("TREND_PULLBACK_STARTER_ONE_THIRD", "SECTOR_CONCENTRATION_WARNING"),
                new BigDecimal("-0.152"), new BigDecimal("1.10"), new BigDecimal("28.5"),
                new BigDecimal("0.285"), new BigDecimal("50000000000"), new BigDecimal("0.142"),
                "SBC_ADJUSTED_FCF", "AVAILABLE", null, null, null,
                LocalDate.of(2026, 12, 21), 32, "INFLOW", new BigDecimal("0.18"),
                "ACCUMULATE_AT_SUPPORT_ACTIVE", Map.of("netRr", new BigDecimal("3.45")),
                "估值偏低、基本面穩健且現價處於有效支撐買區（淨賺賠比 3.45），可優先關注");

        BuyRankingItemDto tier4 = new BuyRankingItemDto(
                2, 2, BuyRankingDtos.TIER_4, null,
                "FIGI_TRAP", "TRAP", "=HYPERLINK(\"bad\"), Corp", "USD", "SEMI", "Semiconductors",
                BuyRankingDtos.ASSET_COMMON_STOCK, "Common Stock", "DEEP_UNDERVALUED", "WEAKENING",
                "CAUTION_VALUE_TRAP", "HIGH_RISK_BREAKDOWN", new BigDecimal("22.50"),
                null, null, false, "NONE",
                null, null, null, null, null,
                0L, "NOT_RECOMMENDED", "NOT_RECOMMENDED", false, "TIER_4_NOT_RECOMMENDED", false, List.of(),
                new BigDecimal("-0.400"), null, null, null, null, null, null, "NOT_APPLICABLE",
                null, null, null, null, null, "OUTFLOW", new BigDecimal("-0.12"),
                "WEAKENING_AND_BREAKDOWN", Map.of(),
                "基本面轉弱且同支撐區出現高機率真破位，屬高風險價值陷阱");

        return new CachedReportSnapshot(
                "rep-001",
                Instant.parse("2026-11-20T04:00:00Z"),
                LocalDate.of(2026, 11, 19),
                "2026-11-20 12:00:00 HKT",
                Instant.parse("2026-11-20T04:00:00Z"),
                locale,
                new MarketBackgroundDto("RISK_ON", new BigDecimal("15.40"), new BigDecimal("4.25"),
                        new BigDecimal("3.10"), new BigDecimal("5.0"), "NORMAL", false, true,
                        LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 19)),
                new ScopeSummaryDto(BuyRankingDtos.SCOPE_SECTOR, "Semiconductors", List.of("SEMI"),
                        List.of("Semiconductors"), List.of(), 2, 2, 2, 300, false, List.of(), List.of(), List.of()),
                Map.of(BuyRankingDtos.TIER_1, 1, BuyRankingDtos.TIER_2, 0, BuyRankingDtos.TIER_3, 0, BuyRankingDtos.TIER_4, 1),
                new AppliedFiltersDto(BuyRankingDtos.SCOPE_SECTOR, List.of("SEMI"), List.of(), List.of(),
                        List.of(BuyRankingDtos.ASSET_COMMON_STOCK, BuyRankingDtos.ASSET_ADR), true,
                        List.of(BuyRankingDtos.TIER_1, BuyRankingDtos.TIER_2, BuyRankingDtos.TIER_3, BuyRankingDtos.TIER_4),
                        BuyRankingDtos.GRADE_ALL, new BigDecimal("2.0"), 14, BuyRankingDtos.SORT_TIER_COMPOSITE, "POSITION"),
                List.of(tier1, tier4)
        );
    }
}
