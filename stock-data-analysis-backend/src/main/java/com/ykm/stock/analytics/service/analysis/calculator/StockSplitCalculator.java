package com.ykm.stock.analytics.service.analysis.calculator;

import com.ykm.stock.analytics.model.entity.StockSplit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 只轉換已發生拆股的股數／每股單位；不推斷公告日，也不判斷資料是否齊備。 */
public final class StockSplitCalculator {
    private static final MathContext PRECISION = MathContext.DECIMAL128;

    private StockSplitCalculator() {}

    public static BigDecimal shareFactor(List<StockSplit> splits, LocalDate fromBasisDate, LocalDate toBasisDate) {
        return factor(splits, fromBasisDate, toBasisDate, true);
    }

    public static BigDecimal priceFactor(List<StockSplit> splits, LocalDate fromBasisDate, LocalDate toBasisDate) {
        return factor(splits, fromBasisDate, toBasisDate, false);
    }

    private static BigDecimal factor(List<StockSplit> splits, LocalDate from, LocalDate to, boolean shares) {
        Objects.requireNonNull(splits, "splits");
        Objects.requireNonNull(from, "fromBasisDate");
        Objects.requireNonNull(to, "toBasisDate");
        if (from.equals(to)) return BigDecimal.ONE;
        LocalDate low = from.isBefore(to) ? from : to;
        LocalDate high = from.isBefore(to) ? to : from;
        BigDecimal numerator = BigDecimal.ONE;
        BigDecimal denominator = BigDecimal.ONE;
        String figi = null;
        Set<String> eventIds = new HashSet<>();
        for (StockSplit split : splits) {
            if (split == null || split.getExecutionDate() == null || split.getFigi() == null
                    || split.getFigi().isBlank() || split.getSourceEventId() == null
                    || split.getSourceEventId().isBlank() || split.getSplitFrom() == null
                    || split.getSplitTo() == null || split.getSplitFrom().signum() <= 0
                    || split.getSplitTo().signum() <= 0)
                throw new IllegalArgumentException("INVALID_STOCK_SPLIT");
            if (figi != null && !figi.equals(split.getFigi()))
                throw new IllegalArgumentException("MIXED_SPLIT_FIGIS");
            figi = split.getFigi();
            if (!eventIds.add(split.getSourceEventId()))
                throw new IllegalArgumentException("DUPLICATE_SPLIT_EVENT:" + split.getSourceEventId());
            if (!split.getExecutionDate().isAfter(low) || split.getExecutionDate().isAfter(high)) continue;
            // 執行日已使用拆股後單位，所以轉換區間為 (from, to]。
            numerator = numerator.multiply(split.getSplitTo());
            denominator = denominator.multiply(split.getSplitFrom());
        }
        boolean forwardShares = from.isBefore(to) == shares;
        return forwardShares ? numerator.divide(denominator, PRECISION) : denominator.divide(numerator, PRECISION);
    }
}
