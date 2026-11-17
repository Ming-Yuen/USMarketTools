package com.ykm.stock.analytics.util;

import java.math.BigDecimal;

public record PercentChangeResult(
        BigDecimal percentageChange,
        String formattedPercentageChange,
        Direction direction) {

    public enum Direction {
        up,
        down,
        flat;

        public static Direction from(int comparison) {
            if (comparison > 0) {
                return up;
            }

            if (comparison < 0) {
                return down;
            }

            return flat;
        }
    }
}