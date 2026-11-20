package com.ykm.stock.analytics.service.market;

import com.ykm.common.common_lib.service.DateTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
// 用途：負責「市場狀態服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class MarketStatusService {

    public static final ZoneId NY_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime EARLY_CLOSE = LocalTime.of(13, 0);
    private static final java.util.Set<LocalDate> EXCEPTIONAL_CLOSURES = java.util.Set.of(
            LocalDate.of(2012, 10, 29),
            LocalDate.of(2012, 10, 30),
            LocalDate.of(2018, 12, 5),
            LocalDate.of(2025, 1, 9)
    );

    private final DateTimeService dateTimeService;

    private final Map<Integer, List<LocalDate>> holidayCache = new ConcurrentHashMap<>();
    private final Map<Integer, java.util.Set<LocalDate>> holidaySetCache = new ConcurrentHashMap<>();
    private final Map<Integer, List<LocalDate>> earlyCloseCache = new ConcurrentHashMap<>();
    private final Map<LocalDate, Instant> sessionOpenCache = new ConcurrentHashMap<>();
    private final Map<LocalDate, Instant> sessionCloseCache = new ConcurrentHashMap<>();

    // 用途：判斷市場Open，作為「市場狀態服務」流程中的處理步驟。
    public boolean isMarketOpen() {
        ZonedDateTime nowNY = dateTimeService.nowZonedDateTime(NY_ZONE);
        LocalDate today = nowNY.toLocalDate();

        if (!isTradingDay(today)) {
            return false;
        }

        LocalTime closeTime = getMarketCloseTime(today);
        LocalTime now = nowNY.toLocalTime();

        return !now.isBefore(MARKET_OPEN) && now.isBefore(closeTime);
    }

    // 用途：取得LastClosed交易日期，作為「市場狀態服務」流程中的處理步驟。
    public LocalDate getLastClosedTradingDay() {
        return getLastClosedTradingDay(dateTimeService.nowZonedDateTime(NY_ZONE).toInstant());
    }

    /** 依指定歷史截點取最後已收市交易日；收市當刻即視為完成。 */
    public LocalDate getLastClosedTradingDay(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("analysisAsOf is required");
        LocalDate date = cutoff.atZone(NY_ZONE).toLocalDate();
        if (isTradingDay(date) && !cutoff.isBefore(getSessionCloseAt(date))) return date;
        return getPreviousTradingDay(date);
    }

    public Instant getSessionOpenAt(LocalDate date) {
        return sessionOpenCache.computeIfAbsent(date, d -> ZonedDateTime.of(d, getSessionOpenTime(d), NY_ZONE).toInstant());
    }

    public Instant getSessionCloseAt(LocalDate date) {
        return sessionCloseCache.computeIfAbsent(date, d -> ZonedDateTime.of(d, getSessionCloseTime(d), NY_ZONE).toInstant());
    }

    public List<LocalDate> getTradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (isTradingDay(date)) days.add(date);
        }
        return List.copyOf(days);
    }

    // 用途：判斷交易日期，作為「市場狀態服務」流程中的處理步驟。
    public boolean isTradingDay(LocalDate date) {
        return !isNonTradingDay(date);
    }

    // 用途：取得SessionOpenTime，作為「市場狀態服務」流程中的處理步驟。
    public LocalTime getSessionOpenTime(LocalDate date) {
        if (!isTradingDay(date)) throw new IllegalArgumentException("Not a trading day: " + date);
        return MARKET_OPEN;
    }

    // 用途：取得SessionCloseTime，作為「市場狀態服務」流程中的處理步驟。
    public LocalTime getSessionCloseTime(LocalDate date) {
        if (!isTradingDay(date)) throw new IllegalArgumentException("Not a trading day: " + date);
        return getMarketCloseTime(date);
    }

    // 用途：取得Previous交易日期，作為「市場狀態服務」流程中的處理步驟。
    public LocalDate getPreviousTradingDay() {
        LocalDate lastClosedTradingDay = getLastClosedTradingDay();
        return getPreviousTradingDay(lastClosedTradingDay);
    }

    // 用途：取得Previous交易日期，作為「市場狀態服務」流程中的處理步驟。
    public LocalDate getPreviousTradingDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        int attempts = 0;

        while (isNonTradingDay(d) && attempts < 30) {
            d = d.minusDays(1);
            attempts++;
        }

        if (attempts >= 30) {
            throw new IllegalStateException("30天內無法找到交易日: " + d);
        }

        return d;
    }

    // 用途：取得Next交易日期，作為「市場狀態服務」流程中的處理步驟。
    public LocalDate getNextTradingDay(LocalDate currentDate) {
        LocalDate d = currentDate.plusDays(1);

        while (isNonTradingDay(d)) {
            d = d.plusDays(1);
        }

        return d;
    }

    /** 返回依年度固定規則計算的 NYSE 假日（不含單次臨時休市），不可由調用者修改。 */
    public List<LocalDate> getUSStockMarketHolidays(int year) {
        return holidayCache.computeIfAbsent(year, this::calculateHolidays);
    }

    // 用途：判斷Non交易日期，作為「市場狀態服務」流程中的處理步驟。
    private boolean isNonTradingDay(LocalDate date) {
        DayOfWeek wd = date.getDayOfWeek();

        if (wd == DayOfWeek.SATURDAY || wd == DayOfWeek.SUNDAY) {
            return true;
        }

        // Exchange-wide exceptional closures that are not derivable from recurring holiday rules.
        if (EXCEPTIONAL_CLOSURES.contains(date)) {
            return true;
        }

        return holidaySetCache.computeIfAbsent(date.getYear(), y -> java.util.Set.copyOf(getUSStockMarketHolidays(y))).contains(date);
    }

    // 用途：取得市場CloseTime，作為「市場狀態服務」流程中的處理步驟。
    private LocalTime getMarketCloseTime(LocalDate date) {
        return getEarlyCloseDates(date.getYear()).contains(date) ? EARLY_CLOSE : MARKET_CLOSE;
    }

    // 用途：取得EarlyCloseDates，作為「市場狀態服務」流程中的處理步驟。
    private List<LocalDate> getEarlyCloseDates(int year) {
        return earlyCloseCache.computeIfAbsent(year, this::calculateEarlyCloseDates);
    }

    // 用途：計算Holidays，作為「市場狀態服務」流程中的處理步驟。
    private List<LocalDate> calculateHolidays(int year) {
        List<LocalDate> hs = new ArrayList<>();
        // NYSE 元旦落在周六時，不會提前到上一年的星期五補休。
        LocalDate newYear = LocalDate.of(year, 1, 1);
        if (newYear.getDayOfWeek() != DayOfWeek.SATURDAY) hs.add(adjustWeekend(newYear));
        // MLK Day 自 1998 年起才適用於 NYSE。
        if (year >= 1998) hs.add(LocalDate.of(year, 1, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY)));
        hs.add(LocalDate.of(year, 2, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.MONDAY)));
        hs.add(calculateEaster(year).minusDays(2));
        hs.add(LocalDate.of(year, 5, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)));
        if (year >= 2022) hs.add(adjustWeekend(LocalDate.of(year, 6, 19)));
        hs.add(adjustWeekend(LocalDate.of(year, 7, 4)));
        hs.add(LocalDate.of(year, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY)));
        hs.add(LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)));
        hs.add(adjustWeekend(LocalDate.of(year, 12, 25)));
        Collections.sort(hs);
        return List.copyOf(hs);
    }

    // 用途：計算EarlyCloseDates，作為「市場狀態服務」流程中的處理步驟。
    private List<LocalDate> calculateEarlyCloseDates(int year) {
        List<LocalDate> dates = new ArrayList<>();

        LocalDate thanksgiving = LocalDate.of(year, 11, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY));
        dates.add(thanksgiving.plusDays(1));

        // 獨立日前一日若正常開市，NYSE 股票市場 13:00 提前收盤；休市時不適用。
        LocalDate independenceEve = LocalDate.of(year, 7, 3);
        if (isTradingDay(independenceEve)) dates.add(independenceEve);

        LocalDate christmasEve = LocalDate.of(year, 12, 24);
        if (christmasEve.getDayOfWeek() != DayOfWeek.SATURDAY && christmasEve.getDayOfWeek() != DayOfWeek.SUNDAY) {
            dates.add(christmasEve);
        }

        Collections.sort(dates);
        return dates;
    }

    // 用途：處理adjustWeekend，作為「市場狀態服務」流程中的處理步驟。
    private LocalDate adjustWeekend(LocalDate d) {
        if (d.getDayOfWeek() == DayOfWeek.SATURDAY) return d.minusDays(1);
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY) return d.plusDays(1);
        return d;
    }

    // 用途：計算Easter，作為「市場狀態服務」流程中的處理步驟。
    private LocalDate calculateEaster(int year) {
        int a = year % 19, b = year / 100, c = year % 100;
        int d = b / 4, e = b % 4, f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
