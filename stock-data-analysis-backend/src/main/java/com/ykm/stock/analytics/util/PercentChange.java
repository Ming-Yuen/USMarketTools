package com.ykm.stock.analytics.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

public final class PercentChange {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private PercentChange() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static <T> PercentChangeResult calculateLatest(
            List<T> items,
            Function<T, LocalDate> dateExtractor,
            Function<T, BigDecimal> valueExtractor) {
        validateItems(items, dateExtractor, valueExtractor);

        Comparator<T> dateComparator = Comparator.comparing(dateExtractor);

        T latestItem = validItems(items, dateExtractor, valueExtractor)
                .max(dateComparator)
                .orElseThrow(() -> new IllegalArgumentException("No valid dated value found"));

        LocalDate latestDate = dateExtractor.apply(latestItem);

        T previousItem = validItems(items, dateExtractor, valueExtractor)
                .filter(item -> dateExtractor.apply(item).isBefore(latestDate))
                .max(dateComparator)
                .orElseThrow(() -> new IllegalArgumentException(
                        "At least two different observation dates are required"));

        return calculate(
                valueExtractor.apply(previousItem),
                valueExtractor.apply(latestItem));
    }

    public static PercentChangeResult calculate(
            BigDecimal previousValue,
            BigDecimal latestValue) {
        validateValues(previousValue, latestValue);

        if (previousValue.signum() == 0) {
            throw new IllegalArgumentException("previousValue cannot be zero");
        }

        BigDecimal percentageChange = latestValue.subtract(previousValue)
                .divide(previousValue, 8, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);

        PercentChangeResult.Direction direction =
                PercentChangeResult.Direction.from(
                        latestValue.compareTo(previousValue));

        String formattedPercentageChange =
                (percentageChange.signum() > 0 ? "+" : "")
                        + percentageChange.toPlainString()
                        + "%";

        return new PercentChangeResult(
                percentageChange,
                formattedPercentageChange,
                direction);
    }

    private static <T> Stream<T> validItems(
            List<T> items,
            Function<T, LocalDate> dateExtractor,
            Function<T, BigDecimal> valueExtractor) {
        return items.stream()
                .filter(Objects::nonNull)
                .filter(item -> dateExtractor.apply(item) != null)
                .filter(item -> valueExtractor.apply(item) != null);
    }

    private static <T> void validateItems(
            List<T> items,
            Function<T, LocalDate> dateExtractor,
            Function<T, BigDecimal> valueExtractor) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items cannot be null or empty");
        }

        Objects.requireNonNull(dateExtractor, "dateExtractor cannot be null");
        Objects.requireNonNull(valueExtractor, "valueExtractor cannot be null");
    }

    private static void validateValues(
            BigDecimal previousValue,
            BigDecimal latestValue) {
        Objects.requireNonNull(previousValue, "previousValue cannot be null");
        Objects.requireNonNull(latestValue, "latestValue cannot be null");
    }
}