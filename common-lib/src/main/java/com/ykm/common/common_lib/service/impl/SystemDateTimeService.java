package com.ykm.common.common_lib.service.impl;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.common.common_lib.service.SysConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;

@Component
@Primary
@RequiredArgsConstructor
public class SystemDateTimeService implements DateTimeService {

    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private final SysConfigService sysConfigService;

    private volatile long offsetDays = 0;

    @PostConstruct
    public void init() {
        refreshOffset();
    }

    public void refreshOffset() {
        Integer offset = sysConfigService.getInteger("system.date.offset-days");
        this.offsetDays = offset == null ? 0 : offset;
    }

    @Override
    public Instant now() {
        return Instant.now().plus(offsetDays, ChronoUnit.DAYS);
    }

    @Override
    public LocalDateTime nowLocalDateTime() {
        return nowZonedDateTime(HONG_KONG).toLocalDateTime();
    }

    @Override
    public ZonedDateTime nowZonedDateTime(ZoneId zoneId) {
        return ZonedDateTime.now(zoneId).plus(offsetDays, ChronoUnit.DAYS);
    }

    @Override
    public LocalDate nowLocalDate() {
        return nowZonedDateTime(HONG_KONG).toLocalDate();
    }
}
