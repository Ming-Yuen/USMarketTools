package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.service.analysis.calculator.TimeframeAggregator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TimeframeAggregatorTests {
    // 例子：2026 感恩節週只使用必要的 NYSE 時段，並套用提前收市。
    @Test
    void thanksgivingWeekUsesOnlyRequiredNyseSessionsAndEarlyClose() {
        MarketStatusService calendar = new MarketStatusService(null);
        TimeframeAggregator aggregator = new TimeframeAggregator(calendar);
        List<NormalizedBarDto> daily = List.of(
                bar(calendar, LocalDate.of(2026, 11, 23), "10", "12", "9", "11", "100"),
                bar(calendar, LocalDate.of(2026, 11, 24), "11", "13", "10", "12", "110"),
                bar(calendar, LocalDate.of(2026, 11, 25), "12", "14", "11", "13", "120"),
                bar(calendar, LocalDate.of(2026, 11, 27), "13", "15", "12", "14", "130"));

        List<NormalizedBarDto> weekly = aggregator.aggregate(daily, AnalysisTimeframe.WEEKLY);

        assertThat(weekly).hasSize(1);
        NormalizedBarDto w = weekly.getFirst();
        assertThat(w.sessionDate()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(w.open()).isEqualByComparingTo("10");
        assertThat(w.high()).isEqualByComparingTo("15");
        assertThat(w.low()).isEqualByComparingTo("9");
        assertThat(w.close()).isEqualByComparingTo("14");
        assertThat(w.volume().value()).isEqualByComparingTo("460");
        assertThat(w.sessionCloseAt()).isEqualTo(calendar.getSessionCloseAt(LocalDate.of(2026, 11, 27)));
        assertThat(w.versionRef()).isNull(); // Weekly OHLCV has no synthetic evidence hash.
    }

    // 例子：只有 2026-11-23 與 11-24 兩日時，不完整週不得升成完整週 K。
    @Test
    void incompleteWeekIsNotPromotedToCompleteBar() {
        MarketStatusService calendar = new MarketStatusService(null);
        TimeframeAggregator aggregator = new TimeframeAggregator(calendar);
        List<NormalizedBarDto> partial = List.of(
                bar(calendar, LocalDate.of(2026, 11, 23), "10", "12", "9", "11", "100"),
                bar(calendar, LocalDate.of(2026, 11, 24), "11", "13", "10", "12", "110"));

        assertThat(aggregator.aggregate(partial, AnalysisTimeframe.WEEKLY)).isEmpty();
    }

    private NormalizedBarDto bar(MarketStatusService c, LocalDate d, String o, String h, String l, String close, String volume) {
        Instant available = c.getSessionCloseAt(d).plusSeconds(60);
        return new NormalizedBarDto(d, bd(o), bd(h), bd(l), bd(close), MetricResult.available(bd(volume)),
                c.getSessionOpenAt(d), c.getSessionCloseAt(d), available, available, "v-" + d, "BASIS");
    }
    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
