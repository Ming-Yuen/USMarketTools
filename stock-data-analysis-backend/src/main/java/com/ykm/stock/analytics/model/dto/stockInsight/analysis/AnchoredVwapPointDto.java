package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 關鍵事件 Anchored VWAP 每日累計成本點，供前端 K 線圖按交易日繪製曲線。 */
public record AnchoredVwapPointDto(LocalDate sessionDate, BigDecimal value) {}
