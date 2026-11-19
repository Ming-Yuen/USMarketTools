package com.ykm.stock.analytics.model.dto.stockInsight.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 非美元財報匯率換算與 ADR 換股比例對齊基準（供前端清楚標註估值換算口徑）。 */
public record FxConversionDto(
        boolean converted,
        String reportingCurrency,
        String targetCurrency,
        LocalDate rateDate,
        BigDecimal usdPerUnit,
        String fxSource,
        boolean adr,
        BigDecimal adrRatio,
        Boolean adrRatioVerified,
        String underlyingSymbol
) {}
