package com.ykm.common.common_lib.converter;

import org.mapstruct.Named;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;


/**
 * CommonTypeMapper offers conversion methods between various basic data types,
 * currently including bidirectional conversion between Date and OffsetDateTime.
 */
public interface CommonTypeConverter {

    /**
     * Convert a java.util.Date to java.time.OffsetDateTime.
     *
     * @param date the Date instance to convert; may be null
     * @return an OffsetDateTime representing the same instant in UTC,
     *         or null if the input date is null
     */
    @Named("toOffsetDateTime")
    default public OffsetDateTime toOffsetDateTime(Date date) {
        if (date == null) {
            return null;
        }
        // Use UTC as the zone offset for consistency
        return OffsetDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    /**
     * Convert a datetime string with timezone to java.time.OffsetDateTime.
     * * This method parses a datetime string according to the specified format and timezone,
     * automatically handling daylight saving time transitions to determine the correct
     * UTC offset for the given timezone at the parsed date.
     *
     * @param datetime the datetime string to parse (e.g., "2025-07-28 14:30:00"); may be null
     * @param format   the datetime format pattern (e.g., "yyyy-MM-dd HH:mm:ss"); may be null
     * @param timezone the timezone identifier (e.g., "America/New_York", "Asia/Hong_Kong"); may be null
     * @return an OffsetDateTime representing the parsed datetime in the specified timezone,
     *         or null if any input parameter is null
     * @throws DateTimeParseException if the datetime string cannot be parsed with the given format
     * @throws DateTimeException      if the timezone identifier is invalid
     */
    @Named("toOffsetDateTimeWithFormatTimezone")
    default public OffsetDateTime toOffsetDateTime(String datetime, String format, String timezone) {
        if (datetime == null || format == null || timezone == null) {
            return null;
        }

        // Create formatter based on the provided pattern
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);

        // Parse into LocalDateTime (no timezone)
        LocalDateTime localDateTime = LocalDateTime.parse(datetime, formatter);

        // Obtain ZoneId and create ZonedDateTime (handles DST)
        ZoneId zoneId = ZoneId.of(timezone);
        ZonedDateTime zonedDateTime = localDateTime.atZone(zoneId);

        // Convert to OffsetDateTime
        return zonedDateTime.toOffsetDateTime();
    }


    /**
     * Convert a java.time.OffsetDateTime to java.util.Date.
     *
     * @param odt the OffsetDateTime instance to convert; may be null
     * @return a Date representing the same instant,
     *         or null if the input OffsetDateTime is null
     */
    @Named("fromOffsetDateTime")
    default public Date fromOffsetDateTime(OffsetDateTime odt) {
        if (odt == null) {
            return null;
        }
        // Convert the Instant part of OffsetDateTime back to Date
        return Date.from(odt.toInstant());
    }
    @Named("intToIsActive")
    default public boolean intToIsActive(Integer value) {
        return value != null && value == 1;
    }

    @Named("longToDate")
    default Date longToDate(Long time) {
        return new Date(time);
    }
//    @Named("doubleToBigDecimal")
    default BigDecimal doubleToBigDecimal(double value) {
        return BigDecimal.valueOf(value);
    }
    default LocalDate parseToLocalDate(String text, String pattern) {
        return java.time.LocalDate.parse(
                text,
                java.time.format.DateTimeFormatter.ofPattern(pattern)
        );
    }

    default String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    default LocalDate toLocalDate(String value) {
        String normalized = blankToNull(value);

        return normalized == null ? null : LocalDate.parse(normalized);
    }

    default LocalDate toLocalDate(LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }
}
