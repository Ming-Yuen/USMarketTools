package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.service.stock.StockQuoteHistoryService;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 美股现金股票常规时段的离线契约测试；不启动 Spring、数据库、Quartz 或真实 HTTP。
 * 日期依据 NYSE/ICE 已发布日历与临时休市公告，来源汇总在 .ai-workflow/MarketStatusService-review-20261019.md。
 * 驗收每年重複假日／半日市，以及已核實的臨時休市（含 Sandy 兩日）。
 * 其他历史临时休市不属于本次支持范围；详细证据保留在原审查报告中。
 */
class MarketStatusServiceTests {
    @Test
    void secFridayFilingsWaitUntilExchangeReopensAfterSandy() {
        // Nasdaq 官方 ETA2012-44／45：2012-10-29 及 10-30 因 Sandy 休市。
        MarketStatusService calendar = new MarketStatusService(mock(DateTimeService.class));
        assertThat(calendar.getNextTradingDay(LocalDate.of(2012, 10, 26))).isEqualTo(LocalDate.of(2012, 10, 31));
    }

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private final DateTimeService clock = mock(DateTimeService.class);
    private final MarketStatusService market = new MarketStatusService(clock);

    // 完整枚举已公布年度：既检查遗漏休市，也检查不应休市却被排除的平日。
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2024|01-01 01-15 02-19 03-29 05-27 06-19 07-04 09-02 11-28 12-25",
            "2025|01-01 01-09 01-20 02-17 04-18 05-26 06-19 07-04 09-01 11-27 12-25",
            "2026|01-01 01-19 02-16 04-03 05-25 06-19 07-03 09-07 11-26 12-25",
            "2027|01-01 01-18 02-15 03-26 05-31 06-18 07-05 09-06 11-25 12-24",
            "2028|01-17 02-21 04-14 05-29 06-19 07-04 09-04 11-23 12-25"
    })
    void publishedFullDayClosuresMatchEveryDateInYear(int year, String dates) {
        Set<LocalDate> closed = Arrays.stream(dates.split(" ")).map(day -> LocalDate.parse(year + "-" + day)).collect(Collectors.toSet());
        for (LocalDate date = LocalDate.of(year, 1, 1); date.getYear() == year; date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            assertThat(market.isTradingDay(date)).as("NYSE regular session on %s", date).isEqualTo(!weekend && !closed.contains(date));
        }
    }

    // 13:00 为股票常规时段收盘；不能混用 eligible options 的 13:15。
    @ParameterizedTest
    @ValueSource(strings = {"2024-07-03", "2025-07-03", "2028-07-03", "2024-11-29", "2025-11-28",
            "2026-11-27", "2027-11-26", "2028-11-24", "2024-12-24", "2025-12-24", "2026-12-24"})
    void publishedEarlyCloseSessionsEndAtOnePm(String date) {
        LocalDate day = LocalDate.parse(date);
        assertThat(market.getSessionOpenTime(day)).isEqualTo(LocalTime.of(9, 30));
        assertThat(market.getSessionCloseTime(day)).isEqualTo(LocalTime.of(13, 0));
    }

    // 银行假期不一定是股票休市；7/4 落周六／周日，也不能擅自把 7/2 标为半日市。
    @ParameterizedTest
    @ValueSource(strings = {"2021-12-31", "2027-12-31", "2026-07-02", "2027-07-02", "2026-10-12", "2026-11-11"})
    void normalEquitySessionsMustNotBeReplacedByBankHolidayRules(String date) {
        LocalDate day = LocalDate.parse(date);
        assertThat(market.isTradingDay(day)).isTrue();
        assertThat(market.getSessionCloseTime(day)).isEqualTo(LocalTime.of(16, 0));
    }

    @ParameterizedTest
    // 現有程式已明確處理的臨時休市保留回歸；其他一次性歷史事件不屬於本次修復範圍。
    @ValueSource(strings = {"2018-12-05", "2025-01-09"})
    void exceptionalExchangeWideClosuresAreNotTradingDays(String date) {
        assertThat(market.isTradingDay(LocalDate.parse(date))).isFalse();
    }

    // 新增假期不能向过去套用；MLK 于 1998 开始，Juneteenth 于 2022 开始。
    @ParameterizedTest
    @CsvSource({"1997-01-20,true", "1998-01-19,false", "2021-06-18,true", "2022-06-20,false"})
    void historicalHolidayAdoptionDoesNotRewriteEarlierSessions(String date, boolean open) {
        assertThat(market.isTradingDay(LocalDate.parse(date))).isEqualTo(open);
    }

    @ParameterizedTest
    @ValueSource(ints = {2022, 2028})
    void saturdayNewYearMustNotAddPreviousYearTradingDayToHolidayList(int year) {
        LocalDate precedingFriday = LocalDate.of(year, 1, 1).minusDays(1);
        assertThat(market.isTradingDay(precedingFriday)).isTrue();
        assertThat(market.getUSStockMarketHolidays(year)).doesNotContain(precedingFriday);
    }

    // 允许 defensive copy 或不可变 list；调用者不能通过返回值改写服务内部日历。
    @Test
    void returnedHolidayListCannotMutateFutureTradingDayDecisions() {
        LocalDate holiday = LocalDate.of(2026, 1, 1);
        assertThat(market.isTradingDay(holiday)).isFalse();
        List<LocalDate> returned = market.getUSStockMarketHolidays(2026);
        try {
            returned.remove(holiday);
        } catch (UnsupportedOperationException expectedForImmutableList) {
            // 不可变返回值同样符合契约；核心断言是后续判断不可被修改。
        }
        assertThat(market.isTradingDay(holiday)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2026-10-19T13:29:59Z,false,2026-10-16", "2026-10-19T13:30:00Z,true,2026-10-16",
            "2026-10-19T19:59:59Z,true,2026-10-16", "2026-10-19T20:00:00Z,false,2026-10-19",
            "2026-11-27T17:59:59Z,true,2026-11-25", "2026-11-27T18:00:00Z,false,2026-11-27",
            "2025-01-09T17:00:00Z,false,2025-01-08", "2026-10-18T16:00:00Z,false,2026-10-16",
            "2026-03-06T14:30:00Z,true,2026-03-05", "2026-03-09T13:30:00Z,true,2026-03-06",
            "2026-11-02T14:30:00Z,true,2026-10-30", "2027-01-01T02:00:00Z,false,2026-12-31"})
    void regularEarlyCloseDstAndUtcDateBoundariesUseNewYork(String timestamp, boolean open, String lastClosed) {
        setNow(timestamp);
        assertThat(market.isMarketOpen()).isEqualTo(open);
        assertThat(market.getLastClosedTradingDay()).isEqualTo(LocalDate.parse(lastClosed));
    }

    @Test
    void independenceEarlyCloseMustStopReportingMarketOpenAfterOnePm() {
        setNow("2025-07-03T17:00:00Z");
        assertThat(market.isMarketOpen()).isFalse();
    }

    @Test
    void independenceEarlyCloseMustAdvanceLastClosedSessionAtOnePm() {
        setNow("2025-07-03T17:00:00Z");
        assertThat(market.getLastClosedTradingDay()).isEqualTo(LocalDate.of(2025, 7, 3));
    }

    @Test
    void latestQuoteLookupUsesJustClosedIndependenceSession() {
        setNow("2025-07-03T17:05:00Z");
        StockQuoteRepository repository = mock(StockQuoteRepository.class);
        new StockQuoteHistoryService(repository, mock(StockStatusRepository.class), market, mock(jakarta.persistence.EntityManager.class)).getCurrentQuote("F1");
        verify(repository).findByFigiAndQuoteDate("F1", LocalDate.of(2025, 7, 3));
    }

    @ParameterizedTest
    @CsvSource({"2025-01-08,2025-01-10", "2026-12-24,2026-12-28", "2027-12-31,2028-01-03"})
    void previousAndNextSessionsCrossWholeClosureIntervals(String before, String after) {
        LocalDate left = LocalDate.parse(before), right = LocalDate.parse(after);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(market.getNextTradingDay(left)).isEqualTo(right),
                () -> assertThat(market.getPreviousTradingDay(right)).isEqualTo(left));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-18", "2026-12-25", "2025-01-09"})
    void sessionTimeAccessRejectsKnownClosedDates(String date) {
        LocalDate day = LocalDate.parse(date);
        assertThatThrownBy(() -> market.getSessionOpenTime(day)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> market.getSessionCloseTime(day)).isInstanceOf(IllegalArgumentException.class);
    }

    // 行为刻画而非业务批准：无参数方法返回“最近已收市日再向前一日”，生产暂无外部调用。
    @Test
    void noArgumentPreviousMeansSessionBeforeLastClosedRatherThanBeforeToday() {
        setNow("2026-10-19T15:00:00Z");
        assertThat(market.getLastClosedTradingDay()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(market.getPreviousTradingDay(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(market.getPreviousTradingDay()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2021-12-31", "2027-12-31"})
    void analysisCalendarAlsoKeepsYearEndSessionWhenSaturdayNewYearIsNotObserved(String date) {
        LocalDate day = LocalDate.parse(date);
        assertThat(market.isTradingDay(day)).isTrue();
        MarketStatusService analysis = market;
        assertThat(analysis.isTradingDay(day)).as("分析日历不能遗漏该真实交易日").isTrue();
    }

    @Test
    void sessionInstantsAndAdjacentDaysUseTheSameMarketRules() {
        LocalDate day = LocalDate.of(2026, 11, 27);
        assertThat(market.isTradingDay(day)).isTrue();
        assertThat(market.getSessionOpenAt(day)).isEqualTo(day.atTime(9, 30).atZone(NY).toInstant());
        assertThat(market.getSessionCloseAt(day)).isEqualTo(day.atTime(13, 0).atZone(NY).toInstant());
        assertThat(market.getPreviousTradingDay(day)).isEqualTo(day.minusDays(2));
        assertThat(market.getNextTradingDay(day)).isEqualTo(day.plusDays(3));
    }
    @ParameterizedTest
    @CsvSource({
            "2026-11-27T17:59:59Z,2026-11-25",
            "2026-11-27T18:00:00Z,2026-11-27",
            "2026-11-26T22:00:00Z,2026-11-25",
            "2026-11-28T22:00:00Z,2026-11-27",
            "2026-03-06T21:00:00Z,2026-03-06",
            "2026-03-09T19:59:59Z,2026-03-06",
            "2026-03-09T20:00:00Z,2026-03-09"
    })
    void analysisUsesRequestedCutoffAcrossEarlyCloseHolidaysAndDaylightSaving(Instant cutoff, LocalDate expected) {
        MarketStatusService analysis = market;
        assertThat(analysis.getLastClosedTradingDay(cutoff)).isEqualTo(expected);
        org.mockito.Mockito.verifyNoInteractions(clock);
    }

    private void setNow(String timestamp) {
        when(clock.nowZonedDateTime(NY)).thenReturn(Instant.parse(timestamp).atZone(NY));
    }
}
