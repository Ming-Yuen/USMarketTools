package com.ykm.common.common_lib.service.impl;

import com.ykm.common.common_lib.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SystemDateTimeServiceTests {
    @Test
    void localMethodsUseHongKongDateAndTimeWithConfiguredOffset() {
        ZoneId hongKong = ZoneId.of("Asia/Hong_Kong");
        ZonedDateTime hongKongTime = Instant.parse("2026-11-03T16:30:00Z").atZone(hongKong);
        SysConfigService config = mock(SysConfigService.class);
        when(config.getInteger("system.date.offset-days")).thenReturn(2);
        SystemDateTimeService service = new SystemDateTimeService(config);
        service.init();

        try (MockedStatic<ZonedDateTime> clock = mockStatic(ZonedDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(() -> ZonedDateTime.now(hongKong)).thenReturn(hongKongTime);

            assertAll(
                    () -> assertEquals(LocalDate.of(2026, 11, 6), service.nowLocalDate()),
                    () -> assertEquals(LocalDateTime.of(2026, 11, 6, 0, 30), service.nowLocalDateTime()));
        }
    }
}
