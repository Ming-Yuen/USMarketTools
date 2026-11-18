package com.ykm.stock.analytics.service.valuation;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.KnownReportPeriod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForwardValuationCalculatorTests {
    private static final LocalDate VAL_DAY = LocalDate.of(2026, 11, 17);
    private static final Instant CUTOFF = Instant.parse("2026-11-17T21:00:00Z");

    private final ForwardValuationCalculator calculator = new ForwardValuationCalculator();

    @Test
    void annualForecastComputesForwardPePsPegIncludingBoundaryGrowthThresholdsAndTurnaroundFy2Fallback() {
        KnownReportPeriod fy2025Annual = new KnownReportPeriod(LocalDate.of(2025, 12, 31), 7, 2025, "2025FY", CUTOFF.minusSeconds(86400));
        // 1) 現價 200，市值 200B，當期 TTM EPS = 8.00；FY2026 預期 EPS = 10.00 (+25% 隱含成長)，預期營收 50B
        // Forward P/E = 200 / 10 = 20.0000；Forward P/S = 200B / 50B = 4.0000；Forward PEG = 20 / 25 = 0.8000
        StockEarningsForecast fy2026 = forecast("2026", "ANNUAL", null, VAL_DAY.minusDays(2), "USD", "10.00", "50000000000");
        ForwardMetrics metrics = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("200"), new BigDecimal("200000000000"), null,
                new BigDecimal("8.00"), null, List.of(), null,
                List.of(fy2026), List.of(fy2025Annual), List.of()
        ), List.of());

        assertThat(metrics.targetFiscalPeriod()).isEqualTo("2026");
        assertThat(metrics.forwardPe().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(metrics.forwardPe().value()).isEqualByComparingTo("20.0000");
        assertThat(metrics.forwardPs().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(metrics.forwardPs().value()).isEqualByComparingTo("4.0000");
        assertThat(metrics.impliedEpsGrowth().value()).isEqualByComparingTo("0.250000");
        assertThat(metrics.forwardPeg().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(metrics.forwardPeg().value()).isEqualByComparingTo("0.8000");
        assertThat(metrics.forwardPegBasis()).isEqualTo("FORWARD_FY1_VS_TTM");

        // 2) 5% 與 100% 端點均納入，4.9% 與 101% 標為 NOT_APPLICABLE
        StockEarningsForecast fy5Pct = forecast("2026", "ANNUAL", null, VAL_DAY, "USD", "10.50", "50000000000"); // vs TTM 10.00 -> +5.0%
        ForwardMetrics at5Pct = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("210"), new BigDecimal("200000000000"), null,
                new BigDecimal("10.00"), null, List.of(), null, List.of(fy5Pct), List.of(fy2025Annual), List.of()), List.of());
        assertThat(at5Pct.forwardPeg().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(at5Pct.forwardPeg().value()).isEqualByComparingTo("4.0000");

        StockEarningsForecast fy100Pct = forecast("2026", "ANNUAL", null, VAL_DAY, "USD", "20.00", "50000000000"); // vs TTM 10.00 -> +100%
        ForwardMetrics at100Pct = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("200"), new BigDecimal("200000000000"), null,
                new BigDecimal("10.00"), null, List.of(), null, List.of(fy100Pct), List.of(fy2025Annual), List.of()), List.of());
        assertThat(at100Pct.forwardPeg().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(at100Pct.forwardPeg().value()).isEqualByComparingTo("0.1000");

        StockEarningsForecast fyLowGrowth = forecast("2026", "ANNUAL", null, VAL_DAY, "USD", "10.40", "50000000000"); // +4.0%
        ForwardMetrics below5Pct = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("208"), new BigDecimal("200000000000"), null,
                new BigDecimal("10.00"), null, List.of(), null, List.of(fyLowGrowth), List.of(fy2025Annual), List.of()), List.of());
        assertThat(below5Pct.forwardPeg().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(below5Pct.forwardPeg().reasons().getFirst().code()).isEqualTo("FORWARD_EPS_GROWTH_BELOW_PEG_THRESHOLD");

        // 3) 當期 TTM 虧損（-1.50），FY2026 轉盈 EPS = 5.00，FY2027 EPS = 6.50 (+30%) -> 自動採用 FORWARD_FY2_VS_FY1 算 PEG
        StockEarningsForecast fy26Turnaround = forecast("2026", "ANNUAL", null, VAL_DAY, "USD", "5.00", "20000000000");
        StockEarningsForecast fy27Next = forecast("2027", "ANNUAL", null, VAL_DAY, "USD", "6.50", "26000000000");
        ForwardMetrics turnaround = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("150"), new BigDecimal("150000000000"), null,
                new BigDecimal("-1.50"), "NON_POSITIVE_EPS", List.of(), null,
                List.of(fy26Turnaround, fy27Next), List.of(fy2025Annual), List.of()), List.of());
        assertThat(turnaround.forwardPe().value()).isEqualByComparingTo("30.0000");
        assertThat(turnaround.forwardPeg().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(turnaround.forwardPeg().value()).isEqualByComparingTo("1.0000");
        assertThat(turnaround.forwardPegBasis()).isEqualTo("FORWARD_FY2_VS_FY1");
        assertThat(turnaround.impliedEpsGrowth().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
    }

    @Test
    void handlesFiscalEndUncertaintyBandIsolatedFy2ReportedQuartersStaleObservationsAndCurrencyMismatch() {
        // 1) 12/31 年結公司在 2026-01-15（已過 2025-12-31 + 7 天）查詢，Q4 尚未入庫：FY2025 已結束須被排除，自動選 FY2026
        LocalDate jan15 = LocalDate.of(2026, 1, 15);
        Instant jan15Close = Instant.parse("2026-01-15T21:00:00Z");
        KnownReportPeriod fy2024Annual = new KnownReportPeriod(LocalDate.of(2024, 12, 31), 7, 2024, "2024FY", jan15Close.minusSeconds(86400 * 300));
        StockEarningsForecast endedFy25 = forecast("2025", "ANNUAL", null, jan15.minusDays(1), "USD", "4.00", "10000000000");
        StockEarningsForecast activeFy26 = forecast("2026", "ANNUAL", null, jan15.minusDays(1), "USD", "5.00", "12000000000");

        ForwardMetrics rolledToFy26 = calculator.calculate(new ForwardValuationCalculator.Inputs(
                jan15, jan15Close, "USD", new BigDecimal("100"), new BigDecimal("100000000000"), null,
                new BigDecimal("3.80"), null, List.of(), null,
                List.of(endedFy25, activeFy26), List.of(fy2024Annual), List.of()), List.of());
        assertThat(rolledToFy26.targetFiscalPeriod()).isEqualTo("2026");
        assertThat(rolledToFy26.forwardPe().value()).isEqualByComparingTo("20.0000");

        // 1b) 即使預測表未保留已結束的 FY2025（僅有 FY2026 一筆），只要已越過 FY2025 年結模糊帶，FY2026 仍獨立判定為當前 FY1
        ForwardMetrics fy26WithoutOldRow = calculator.calculate(new ForwardValuationCalculator.Inputs(
                jan15, jan15Close, "USD", new BigDecimal("100"), new BigDecimal("100000000000"), null,
                new BigDecimal("3.80"), null, List.of(), null,
                List.of(activeFy26), List.of(fy2024Annual), List.of()), List.of());
        assertThat(fy26WithoutOldRow.targetFiscalPeriod()).isEqualTo("2026");
        assertThat(fy26WithoutOldRow.forwardPe().value()).isEqualByComparingTo("20.0000");

        // 1c) 無年報錨點時，若唯一預期之明確期末遠在 409 天後（2027-12-31），不得將遠期 FY2 誤認作當前 FY1
        StockEarningsForecast farExplicitFy27 = forecast("2027", "ANNUAL", LocalDate.of(2027, 12, 31), VAL_DAY, "USD", "10.00", "50000000000");
        ForwardMetrics unanchoredFar = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(farExplicitFy27), List.of(), List.of()), List.of());
        assertThat(unanchoredFar.forwardPe().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(unanchoredFar.forwardPe().reasons().getFirst().code()).isEqualTo("ANNUAL_EARNINGS_FORECAST_MISSING");

        // 1d) 無年報錨點但已知舊單季 FY2025Q3 時，若 FY2026 明示涵蓋當日（2026-01-01..2026-12-31），仍須採用 FY2026
        KnownReportPeriod fy25Q3 = new KnownReportPeriod(LocalDate.of(2025, 9, 30), 3, 2025, "2025Q3", jan15Close.minusSeconds(86400 * 60));
        StockEarningsForecast explicitSpanningFy26 = forecast("2026", "ANNUAL", LocalDate.of(2026, 12, 31), jan15.minusDays(1), "USD", "5.00", "12000000000");
        explicitSpanningFy26.setPeriodStart(LocalDate.of(2026, 1, 1));
        ForwardMetrics spanningWithPriorQuarter = calculator.calculate(new ForwardValuationCalculator.Inputs(
                jan15, jan15Close, "USD", new BigDecimal("100"), new BigDecimal("100000000000"), null,
                new BigDecimal("3.80"), null, List.of(), null,
                List.of(explicitSpanningFy26), List.of(fy25Q3), List.of()), List.of());
        assertThat(spanningWithPriorQuarter.targetFiscalPeriod()).isEqualTo("2026");
        assertThat(spanningWithPriorQuarter.forwardPe().value()).isEqualByComparingTo("20.0000");

        // 1e) 估值日為 2026-12-29，唯一預期明示尚未開始之 2027-01-01..2027-12-31 -> 不得誤認作當前 FY1
        LocalDate dec29 = LocalDate.of(2026, 12, 29);
        Instant dec29Close = Instant.parse("2026-12-29T21:00:00Z");
        StockEarningsForecast unstartedFy27 = forecast("2027", "ANNUAL", LocalDate.of(2027, 12, 31), dec29, "USD", "10.00", "50000000000");
        unstartedFy27.setPeriodStart(LocalDate.of(2027, 1, 1));
        ForwardMetrics unstarted = calculator.calculate(new ForwardValuationCalculator.Inputs(
                dec29, dec29Close, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(unstartedFy27), List.of(), List.of()), List.of());
        assertThat(unstarted.forwardPe().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(unstarted.forwardPe().reasons().getFirst().code()).isEqualTo("ANNUAL_EARNINGS_FORECAST_MISSING");
        assertThat(unstarted.epsRevision30d().reasons().getFirst().code()).isEqualTo("NO_UNREPORTED_FORECAST_PERIOD");

        // 1g) D = 2027-01-04 雖處於推估 FY2026 年結 ±7 天內，但 FY2027 已明示 2027-01-01..2027-12-31 涵蓋當日 -> 直接採納 FY2027，不被推估模糊帶誤殺
        LocalDate jan4_2027 = LocalDate.of(2027, 1, 4);
        Instant jan4_2027Close = Instant.parse("2027-01-04T21:00:00Z");
        KnownReportPeriod fy2025Anchor = new KnownReportPeriod(LocalDate.of(2025, 12, 31), 7, 2025, "2025FY", jan4_2027Close.minusSeconds(86400 * 300));
        StockEarningsForecast explicitFy27 = forecast("2027", "ANNUAL", LocalDate.of(2027, 12, 31), jan4_2027.minusDays(1), "USD", "10.00", "50000000000");
        explicitFy27.setPeriodStart(LocalDate.of(2027, 1, 1));
        ForwardMetrics explicitOverridesBand = calculator.calculate(new ForwardValuationCalculator.Inputs(
                jan4_2027, jan4_2027Close, "USD", new BigDecimal("100"), new BigDecimal("100000000000"), null,
                new BigDecimal("8.00"), null, List.of(), null,
                List.of(explicitFy27), List.of(fy2025Anchor), List.of()), List.of());
        assertThat(explicitOverridesBand.targetFiscalPeriod()).isEqualTo("2027");
        assertThat(explicitOverridesBand.forwardPe().value()).isEqualByComparingTo("10.0000");

        // 1h) 公司由六月改為三月結算：4/15 查詢時，明示 2026-04-01..2027-03-31 的 FY2027 優先勝過仍新鮮但缺起迄日的舊 FY2026 推估列，且轉盈 FY2 獨立配對 FY2028
        LocalDate apr15 = LocalDate.of(2026, 4, 15);
        Instant apr15Close = Instant.parse("2026-04-15T20:00:00Z");
        KnownReportPeriod oldJuneAnchor = new KnownReportPeriod(LocalDate.of(2025, 6, 30), 7, 2025, "2025FY", apr15Close.minusSeconds(86400 * 250));
        StockEarningsForecast oldUnanchoredFy26 = forecast("2026", "ANNUAL", null, apr15.minusDays(5), "USD", "5.00", "25000000000");
        StockEarningsForecast explicitNewFy27 = forecast("2027", "ANNUAL", LocalDate.of(2027, 3, 31), apr15.minusDays(2), "USD", "10.00", "50000000000");
        explicitNewFy27.setPeriodStart(LocalDate.of(2026, 4, 1));
        StockEarningsForecast followUpFy28 = forecast("2028", "ANNUAL", null, apr15.minusDays(2), "USD", "12.50", "60000000000");
        StockEarningsForecast fy27Base30d = forecast("2027", "ANNUAL", LocalDate.of(2027, 3, 31), apr15.minusDays(32), "USD", "8.00", "45000000000");
        fy27Base30d.setPeriodStart(LocalDate.of(2026, 4, 1));

        ForwardValuationCalculator.Inputs fyChangeInputs = new ForwardValuationCalculator.Inputs(
                apr15, apr15Close, "USD", new BigDecimal("100"), new BigDecimal("100000000000"), null,
                new BigDecimal("-1.00"), "NON_POSITIVE_EPS", List.of(), null,
                List.of(oldUnanchoredFy26, explicitNewFy27, followUpFy28), List.of(oldJuneAnchor), List.of());
        assertThat(calculator.selectRevisionTarget(fyChangeInputs).fiscalPeriod()).isEqualTo("2027");
        ForwardMetrics fyChangeMetrics = calculator.calculate(fyChangeInputs, List.of(fy27Base30d));
        assertThat(fyChangeMetrics.targetFiscalPeriod()).isEqualTo("2027");
        assertThat(fyChangeMetrics.forwardPe().value()).isEqualByComparingTo("10.0000");
        assertThat(fyChangeMetrics.forwardPeg().value()).isEqualByComparingTo("0.4000");
        assertThat(fyChangeMetrics.forwardPegBasis()).isEqualTo("FORWARD_FY2_VS_FY1");
        assertThat(fyChangeMetrics.revisionFiscalPeriod()).isEqualTo("2027");
        assertThat(fyChangeMetrics.epsRevision30d().value()).isEqualByComparingTo("0.250000");

        // 1f) 估值日落在年結 ±7 天模糊帶但預期表全為 QUARTERLY -> 必須回 ANNUAL_EARNINGS_FORECAST_MISSING 而非 FORECAST_FISCAL_PERIOD_UNVERIFIED
        LocalDate feb2QOnly = LocalDate.of(2026, 2, 2);
        Instant feb2QOnlyClose = Instant.parse("2026-02-02T21:00:00Z");
        KnownReportPeriod janAnchor = new KnownReportPeriod(LocalDate.of(2025, 1, 31), 7, 2025, "2025FY", feb2QOnlyClose.minusSeconds(86400 * 300));
        StockEarningsForecast qOnlyInBand = forecast("2027Q1", "QUARTERLY", null, feb2QOnly.minusDays(1), "USD", "1.20", "3000000000");
        ForwardMetrics qOnlyBandResult = calculator.calculate(new ForwardValuationCalculator.Inputs(
                feb2QOnly, feb2QOnlyClose, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(qOnlyInBand), List.of(janAnchor), List.of()), List.of());
        assertThat(qOnlyBandResult.forwardPe().reasons().getFirst().code()).isEqualTo("ANNUAL_EARNINGS_FORECAST_MISSING");

        // 2) 1/31 非曆年制公司在 2026-02-02（落在推估年結日 2026-01-31 ±7 天模糊區間）且無明確 periodEnd -> 回傳 FORECAST_FISCAL_PERIOD_UNVERIFIED
        LocalDate feb2 = LocalDate.of(2026, 2, 2);
        Instant feb2Close = Instant.parse("2026-02-02T21:00:00Z");
        KnownReportPeriod janAnnual2025 = new KnownReportPeriod(LocalDate.of(2025, 1, 31), 7, 2025, "2025FY", feb2Close.minusSeconds(86400 * 300));
        StockEarningsForecast ambiguousFy26 = forecast("2026", "ANNUAL", null, feb2.minusDays(1), "USD", "5.00", "10000000000");
        ForwardMetrics ambiguous = calculator.calculate(new ForwardValuationCalculator.Inputs(
                feb2, feb2Close, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(ambiguousFy26), List.of(janAnnual2025), List.of()), List.of());
        assertThat(ambiguous.forwardPe().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(ambiguous.forwardPe().reasons().getFirst().code()).isEqualTo("FORECAST_FISCAL_PERIOD_UNVERIFIED");

        // 3) 缺少 FY2026 而僅有孤立遠期 FY2027 -> 不得把 FY2027 冒充為 FY1
        StockEarningsForecast isolatedFy27 = forecast("2027", "ANNUAL", null, VAL_DAY, "USD", "12.00", "60000000000");
        KnownReportPeriod fy2025Annual = new KnownReportPeriod(LocalDate.of(2025, 12, 31), 7, 2025, "2025FY", CUTOFF.minusSeconds(86400));
        ForwardMetrics isolated = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("120"), null, null, null, null,
                List.of(), null, List.of(isolatedFy27), List.of(fy2025Annual), List.of()), List.of());
        assertThat(isolated.forwardPe().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(isolated.forwardPe().reasons().getFirst().code()).isEqualTo("ANNUAL_EARNINGS_FORECAST_MISSING");

        // 4) 超過 45 天未更新 -> STALE；幣別不一致（CAD 預期 vs USD 股價） -> UNVERIFIED
        StockEarningsForecast staleFy26 = forecast("2026", "ANNUAL", null, VAL_DAY.minusDays(46), "USD", "10.00", "50000000000");
        ForwardMetrics stale = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("200"), null, null, null, null,
                List.of(), null, List.of(staleFy26), List.of(fy2025Annual), List.of()), List.of());
        assertThat(stale.forwardPe().status()).isEqualTo(MetricStatus.STALE);
        assertThat(stale.forwardPe().reasons().getFirst().code()).isEqualTo("EARNINGS_FORECAST_STALE");

        StockEarningsForecast cadFy26 = forecast("2026", "ANNUAL", null, VAL_DAY.minusDays(2), "CAD", "10.00", "50000000000");
        ForwardMetrics currencyMismatch = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("200"), null, null, null, null,
                List.of(), null, List.of(cadFy26), List.of(fy2025Annual), List.of()), List.of());
        assertThat(currencyMismatch.forwardPe().status()).isEqualTo(MetricStatus.UNVERIFIED);
        assertThat(currencyMismatch.forwardPe().reasons().getFirst().code()).isEqualTo("REPORTING_CURRENCY_MISMATCH");
    }

    @Test
    void quarterlyRevisionAnchorsOnLatestObservationHandlesSplitsNegativeBaseAndShortHistory() {
        // 已公布 2026Q3，預期表同時有已公布的 2026Q3 與未公布的 2026Q4 -> 必須排除 2026Q3，選定 2026Q4
        KnownReportPeriod reportedQ3 = new KnownReportPeriod(LocalDate.of(2026, 9, 30), 3, 2026, "2026Q3", CUTOFF.minusSeconds(86400 * 10));
        StockEarningsForecast staleReportedQ3 = forecast("2026Q3", "QUARTERLY", null, VAL_DAY.minusDays(20), "USD", "1.50", "1000000000");
        LocalDate latestObs = VAL_DAY.minusDays(2); // 2026-11-15
        StockEarningsForecast activeQ4 = forecast("2026Q4", "QUARTERLY", null, latestObs, "USD", "2.00", "2200000000");

        ForwardValuationCalculator.Inputs inputs = new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(staleReportedQ3, activeQ4), List.of(reportedQ3), List.of());
        assertThat(calculator.selectRevisionTarget(inputs).fiscalPeriod()).isEqualTo("2026Q4");

        // 1) 真實跨度僅 4 天（如 11/11 至 11/15），或兩筆陳舊觀察僅相隔 1 天（D-31 與 D-32）-> 皆不得誤報 30D 修正
        StockEarningsForecast fourDaysAgo = forecast("2026Q4", "QUARTERLY", null, latestObs.minusDays(4), "USD", "1.90", "2000000000");
        ForwardMetrics shortSpan = calculator.calculate(inputs, List.of(fourDaysAgo));
        assertThat(shortSpan.forwardPe().reasons().getFirst().code()).isEqualTo("ANNUAL_EARNINGS_FORECAST_MISSING");
        assertThat(shortSpan.revisionFiscalPeriod()).isEqualTo("2026Q4");
        assertThat(shortSpan.epsRevision30d().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(shortSpan.epsRevision30d().reasons().getFirst().code()).isEqualTo("FORECAST_REVISION_BASE_MISSING");

        LocalDate dMinus31 = VAL_DAY.minusDays(31);
        StockEarningsForecast oldLatest = forecast("2026Q4", "QUARTERLY", null, dMinus31, "USD", "2.00", "2000000000");
        StockEarningsForecast oneDayEarlier = forecast("2026Q4", "QUARTERLY", null, VAL_DAY.minusDays(32), "USD", "1.50", "1800000000");
        ForwardMetrics oneDayDiff = calculator.calculate(new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(oldLatest), List.of(reportedQ3), List.of()), List.of(oneDayEarlier));
        assertThat(oneDayDiff.epsRevision30d().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);

        // 2) 跨 2 拆 1 拆股：30 天前拆股前預期 EPS = 4.00、營收 = 2.0B；拆股後最新預期 EPS = 2.00、營收 = 2.2B
        // 對齊後 EPS 修正 = 0.000000 (0%)，絕不誤報 -50%；營收不作拆股調整 = +10.00%
        StockSplit twoForOne = new StockSplit();
        twoForOne.setFigi("FIGI");
        twoForOne.setSourceEventId("SPLIT-1");
        twoForOne.setExecutionDate(latestObs.minusDays(10));
        twoForOne.setSplitFrom(new BigDecimal("1"));
        twoForOne.setSplitTo(new BigDecimal("2"));

        StockEarningsForecast base30PreSplit = forecast("2026Q4", "QUARTERLY", null, latestObs.minusDays(30), "USD", "4.00", "2000000000");
        StockEarningsForecast base90Negative = forecast("2026Q4", "QUARTERLY", null, latestObs.minusDays(88), "USD", "-0.50", "2000000000");
        ForwardValuationCalculator.Inputs splitInputs = new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(twoForOne), null, List.of(activeQ4), List.of(reportedQ3), List.of());

        ForwardMetrics withSplitAndNegBase = calculator.calculate(splitInputs, List.of(base30PreSplit, base90Negative));
        assertThat(withSplitAndNegBase.revisionBaseDate30d()).isEqualTo(latestObs.minusDays(30));
        assertThat(withSplitAndNegBase.epsRevision30d().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(withSplitAndNegBase.epsRevision30d().value()).isEqualByComparingTo("0.000000");
        assertThat(withSplitAndNegBase.revenueRevision30d().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(withSplitAndNegBase.revenueRevision30d().value()).isEqualByComparingTo("0.100000");
        // 90D 基期 EPS 為負 (-0.50) -> NOT_APPLICABLE，而同筆正營收基期正常計算 +10%
        assertThat(withSplitAndNegBase.epsRevision90d().status()).isEqualTo(MetricStatus.NOT_APPLICABLE);
        assertThat(withSplitAndNegBase.epsRevision90d().reasons().getFirst().code()).isEqualTo("NON_POSITIVE_PRIOR_FORECAST_EPS");
        assertThat(withSplitAndNegBase.revenueRevision90d().value()).isEqualByComparingTo("0.100000");
        assertThat(withSplitAndNegBase.revisionCurrency()).isEqualTo("USD");

        // 3) 財報尚未覆蓋舊季度（2026Q2 已逾 45 天陳舊）且同時存在新鮮的 2026Q3（含 30D 基準）-> 自動略過陳舊 Q2，選用新鮮 Q3 計算修正
        StockEarningsForecast staleUnreportedQ2 = forecast("2026Q2", "QUARTERLY", null, VAL_DAY.minusDays(60), "USD", "1.10", "1500000000");
        StockEarningsForecast freshUnreportedQ3 = forecast("2026Q3", "QUARTERLY", null, latestObs, "USD", "1.65", "1800000000");
        StockEarningsForecast base30ForQ3 = forecast("2026Q3", "QUARTERLY", null, latestObs.minusDays(30), "USD", "1.50", "1700000000");
        ForwardValuationCalculator.Inputs stalePlusFreshInputs = new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(staleUnreportedQ2, freshUnreportedQ3), List.of(), List.of());
        assertThat(calculator.selectRevisionTarget(stalePlusFreshInputs).fiscalPeriod()).isEqualTo("2026Q3");
        ForwardMetrics freshSelected = calculator.calculate(stalePlusFreshInputs, List.of(base30ForQ3));
        assertThat(freshSelected.revisionFiscalPeriod()).isEqualTo("2026Q3");
        assertThat(freshSelected.epsRevision30d().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(freshSelected.epsRevision30d().value()).isEqualByComparingTo("0.100000");

        // 4) Q3 的 SEC EPS 在 10/30 已知且生效，但同季無關 SEC 負債項目晚至 11/20 才生效推高整列 availableAt / secAvailableFromDate；
        //    查詢 11/17 時須判定 2026Q3 已公布，自動追蹤 11/16 觀察到的 2026Q4（90D 回傳 INSUFFICIENT_DATA，不誤報 Q3 的 -20% 修正）
        com.ykm.stock.analytics.model.entity.StockFinancialPeriod q3Stored = new com.ykm.stock.analytics.model.entity.StockFinancialPeriod();
        q3Stored.setFigi("FIGI");
        q3Stored.setPeriodStart(LocalDate.of(2026, 7, 1));
        q3Stored.setPeriodEnd(LocalDate.of(2026, 9, 30));
        q3Stored.setFiscalYear(2026);
        q3Stored.setFiscalQuarter(3);
        q3Stored.setEpsTtm(new BigDecimal("6.00"));
        q3Stored.setEpsTtmStatus("AVAILABLE");
        q3Stored.setEpsTtmSource("SEC");
        q3Stored.setEpsTtmAvailableAt(Instant.parse("2026-10-30T20:00:00Z"));
        q3Stored.setEpsTtmSecAvailableFromDate(LocalDate.of(2026, 10, 30));
        q3Stored.setAvailableAt(Instant.parse("2026-11-20T20:00:00Z"));
        q3Stored.setSecAvailableFromDate(LocalDate.of(2026, 11, 20));

        StockEarningsForecast q3Latest = forecast("2026Q3", "QUARTERLY", null, LocalDate.of(2026, 10, 25), "USD", "1.60", "2000000000");
        StockEarningsForecast q4Fresh = forecast("2026Q4", "QUARTERLY", null, LocalDate.of(2026, 11, 16), "USD", "2.10", "2300000000");
        ForwardValuationCalculator.Inputs q3ReportedViaEpsInputs = new ForwardValuationCalculator.Inputs(
                VAL_DAY, CUTOFF, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(q3Latest, q4Fresh), List.of(), List.of(q3Stored));
        assertThat(calculator.selectRevisionTarget(q3ReportedViaEpsInputs).fiscalPeriod()).isEqualTo("2026Q4");
        ForwardMetrics q4Tracked = calculator.calculate(q3ReportedViaEpsInputs, List.of());
        assertThat(q4Tracked.revisionFiscalPeriod()).isEqualTo("2026Q4");
        assertThat(q4Tracked.epsRevision90d().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
    }

    private StockEarningsForecast forecast(String period, String type, LocalDate periodEnd, LocalDate obsDate,
                                           String currency, String eps, String revenue) {
        StockEarningsForecast f = new StockEarningsForecast();
        f.setFigi("FIGI");
        f.setFiscalPeriod(period);
        f.setPeriodType(type);
        f.setPeriodEnd(periodEnd);
        f.setObservationDate(obsDate);
        f.setObservedAt(obsDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plusSeconds(3600));
        f.setCurrency(currency);
        f.setSource("FUTU");
        if (eps != null) f.setEps(new BigDecimal(eps));
        if (revenue != null) f.setRevenue(new BigDecimal(revenue));
        return f;
    }
}
