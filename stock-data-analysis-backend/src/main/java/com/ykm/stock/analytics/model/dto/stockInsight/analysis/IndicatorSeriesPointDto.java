package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 供前端互動圖表繪製與日線對齊的歷史技術指標序列點（由後端權威計算）。 */
public record IndicatorSeriesPointDto(
        LocalDate sessionDate,
        BigDecimal sma20,
        BigDecimal sma50,
        BigDecimal sma200,
        BigDecimal bollingerUpper,
        BigDecimal bollingerLower,
        BigDecimal rsi14,
        BigDecimal macd,
        BigDecimal macdSignal,
        BigDecimal macdHistogram
) {}
