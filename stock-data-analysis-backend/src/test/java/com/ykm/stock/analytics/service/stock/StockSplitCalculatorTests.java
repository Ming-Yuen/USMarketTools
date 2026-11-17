package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StockSplitCalculatorTests {
    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);

    /** Scenario：修改前依賴proof；修改後四拆一直接令價格/股數互為反比。 */
    @Test
    void forwardSplitPreservesEquityValue() {
        List<StockSplit> rows = List.of(split("S1", "F1", "1", "4", DAY));
        BigDecimal shareFactor = StockSplitCalculator.shareFactor(rows, DAY.minusDays(1), DAY);
        BigDecimal priceFactor = StockSplitCalculator.priceFactor(rows, DAY.minusDays(1), DAY);
        assertThat(shareFactor).isEqualByComparingTo("4");
        assertThat(priceFactor).isEqualByComparingTo("0.25");
        assertThat(new BigDecimal("120").multiply(priceFactor).multiply(new BigDecimal("100").multiply(shareFactor)))
                .isEqualByComparingTo("12000");
    }

    /** Scenario：反向五合一，股數除五、每股值乘五；反向查詢還原原單位。 */
    @Test
    void reverseSplitAndBackwardDateConversion() {
        List<StockSplit> rows = List.of(split("S1", "F1", "5", "1", DAY));
        assertThat(StockSplitCalculator.shareFactor(rows, DAY.minusDays(1), DAY)).isEqualByComparingTo("0.2");
        assertThat(StockSplitCalculator.priceFactor(rows, DAY.minusDays(1), DAY)).isEqualByComparingTo("5");
        assertThat(StockSplitCalculator.shareFactor(rows, DAY, DAY.minusDays(1))).isEqualByComparingTo("5");
        assertThat(StockSplitCalculator.priceFactor(rows, DAY, DAY.minusDays(1))).isEqualByComparingTo("0.2");
    }

    /** Scenario：執行日已是新單位，只計算(minDate,maxDate]，不提前使用未來拆股。 */
    @Test
    void excludesFromDateAndFutureEvents() {
        List<StockSplit> rows = List.of(split("A", "F1", "1", "4", DAY),
                split("B", "F1", "1", "3", DAY.plusDays(2)));
        assertThat(StockSplitCalculator.priceFactor(rows, DAY, DAY.plusDays(1))).isEqualByComparingTo("1");
        assertThat(StockSplitCalculator.shareFactor(rows, DAY, DAY.plusDays(2))).isEqualByComparingTo("3");
        assertThat(StockSplitCalculator.priceFactor(rows, DAY, DAY)).isEqualByComparingTo("1");
    }

    /** Scenario：連續事件先乘分子分母再除，避免逐次小數四捨五入造成比例漂移。 */
    @Test
    void cumulativeRatioIsCalculatedOnce() {
        List<StockSplit> rows = List.of(split("A", "F1", "3", "1", DAY),
                split("B", "F1", "1", "3", DAY.plusDays(1)));
        assertThat(StockSplitCalculator.priceFactor(rows, DAY.minusDays(1), DAY.plusDays(1))).isEqualByComparingTo("1");
    }

    /** Scenario：不同FIGI或同source event重複，必須拒絕而非跨issuer或重複乘算。 */
    @Test
    void rejectsMixedSecuritiesAndDuplicateEvents() {
        assertThatThrownBy(() -> StockSplitCalculator.priceFactor(List.of(split("A", "F1", "1", "4", DAY),
                split("B", "F2", "1", "3", DAY)), DAY.minusDays(1), DAY))
                .hasMessage("MIXED_SPLIT_FIGIS");
        StockSplit split = split("A", "F1", "1", "4", DAY);
        assertThatThrownBy(() -> StockSplitCalculator.priceFactor(List.of(split, split), DAY.minusDays(1), DAY))
                .hasMessage("DUPLICATE_SPLIT_EVENT:A");
    }

    private StockSplit split(String id, String figi, String from, String to, LocalDate date) {
        StockSplit row = new StockSplit();
        row.setSourceEventId(id);
        row.setFigi(figi);
        row.setExecutionDate(date);
        row.setSplitFrom(new BigDecimal(from));
        row.setSplitTo(new BigDecimal(to));
        return row;
    }
}
