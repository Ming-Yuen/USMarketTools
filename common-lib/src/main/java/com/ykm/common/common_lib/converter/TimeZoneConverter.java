package com.ykm.common.common_lib.converter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeZoneConverter {
    public static LocalDateTime toUTC(LocalDateTime localDateTime, ZoneId timeZone) {
        ZonedDateTime zonedDateTime = localDateTime.atZone(timeZone);
        ZonedDateTime utcZoned = zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));
        return utcZoned.toLocalDateTime();
    }

    public static LocalDateTime toUTC(LocalDateTime localDateTime, String timezoneId) {
        return toUTC(localDateTime, ZoneId.of(timezoneId));
    }
}
