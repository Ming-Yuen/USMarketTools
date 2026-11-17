package com.ykm.common.common_lib.service;

import java.time.*;

public interface DateTimeService {

    Instant now();

    /** Returns the current local date and time in Asia/Hong_Kong. */
    LocalDateTime nowLocalDateTime();

    ZonedDateTime nowZonedDateTime(ZoneId zoneId);

    /** Returns the current local date in Asia/Hong_Kong. */
    LocalDate nowLocalDate();
}
