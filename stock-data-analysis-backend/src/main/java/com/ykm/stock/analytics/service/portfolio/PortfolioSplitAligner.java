package com.ykm.stock.analytics.service.portfolio;

import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 持倉成本、股數與前期參考價位之跨拆股對齊輔助器。
 * 跨基準日換算要求拆股同步狀態存在、涵蓋完整區間且無實質同步錯誤（沿用 StockValuationService.splitSyncError 規則）；
 * 無法驗證時明確標示 SPLIT_ALIGNMENT_UNVERIFIED，絕不以錯位單位混算損益或止損。
 */
@Component
public class PortfolioSplitAligner {
    public static final String STATUS_SAME_BASIS = "SAME_BASIS";
    public static final String STATUS_ALIGNED = "ALIGNED";
    public static final String STATUS_SPLIT_SYNC_ERROR = "SPLIT_SYNC_ERROR";
    public static final String STATUS_SPLIT_HISTORY_INCOMPLETE = "SPLIT_HISTORY_INCOMPLETE";
    public static final String STATUS_INVALID_BASIS = "INVALID_BASIS";

    private static final MathContext MC = MathContext.DECIMAL128;

    public record AlignedPosition(
            boolean verified,
            String status,
            BigDecimal shareFactor,
            BigDecimal priceFactor,
            BigDecimal rawAdjustedShares,
            BigDecimal rawAdjustedAvgCost,
            BigDecimal adjustedShares,
            BigDecimal adjustedAvgCost,
            boolean splitAdjusted
    ) {}

    public record AlignedPrice(
            boolean verified,
            String status,
            BigDecimal priceFactor,
            BigDecimal rawAdjustedPrice,
            BigDecimal adjustedPrice
    ) {}

    public AlignedPosition alignHolding(BigDecimal rawShares,
                                        BigDecimal rawAvgCost,
                                        LocalDate costBasisDate,
                                        LocalDate evalDate,
                                        StockStatus status,
                                        List<StockSplit> splits) {
        if (rawShares == null || rawAvgCost == null || costBasisDate == null || evalDate == null) {
            return new AlignedPosition(false, STATUS_INVALID_BASIS, null, null, null, null, null, null, false);
        }
        if (costBasisDate.equals(evalDate)) {
            return new AlignedPosition(true, STATUS_SAME_BASIS, BigDecimal.ONE, BigDecimal.ONE,
                    rawShares, rawAvgCost,
                    rawShares.setScale(6, RoundingMode.HALF_UP),
                    rawAvgCost.setScale(6, RoundingMode.HALF_UP),
                    false);
        }
        String coverageError = verifySplitCoverage(costBasisDate, evalDate, status);
        if (coverageError != null) {
            return new AlignedPosition(false, coverageError, null, null, null, null, null, null, false);
        }
        List<StockSplit> safeSplits = splits == null ? List.of() : splits;
        BigDecimal shareFactor = StockSplitCalculator.shareFactor(safeSplits, costBasisDate, evalDate);
        BigDecimal priceFactor = StockSplitCalculator.priceFactor(safeSplits, costBasisDate, evalDate);
        BigDecimal rawAdjShares = rawShares.multiply(shareFactor, MC);
        BigDecimal rawAdjAvgCost = rawAvgCost.multiply(priceFactor, MC);
        BigDecimal adjustedShares = rawAdjShares.setScale(6, RoundingMode.HALF_UP);
        BigDecimal adjustedAvgCost = rawAdjAvgCost.setScale(6, RoundingMode.HALF_UP);
        boolean splitAdjusted = shareFactor.compareTo(BigDecimal.ONE) != 0;
        return new AlignedPosition(true, STATUS_ALIGNED, shareFactor, priceFactor,
                rawAdjShares, rawAdjAvgCost, adjustedShares, adjustedAvgCost, splitAdjusted);
    }

    public AlignedPrice alignPrice(BigDecimal rawPrice,
                                   LocalDate fromBasisDate,
                                   LocalDate evalDate,
                                   StockStatus status,
                                   List<StockSplit> splits) {
        if (rawPrice == null || fromBasisDate == null || evalDate == null) {
            return new AlignedPrice(false, STATUS_INVALID_BASIS, null, null, null);
        }
        if (fromBasisDate.equals(evalDate)) {
            return new AlignedPrice(true, STATUS_SAME_BASIS, BigDecimal.ONE,
                    rawPrice, rawPrice.setScale(4, RoundingMode.HALF_UP));
        }
        String coverageError = verifySplitCoverage(fromBasisDate, evalDate, status);
        if (coverageError != null) {
            return new AlignedPrice(false, coverageError, null, null, null);
        }
        List<StockSplit> safeSplits = splits == null ? List.of() : splits;
        BigDecimal priceFactor = StockSplitCalculator.priceFactor(safeSplits, fromBasisDate, evalDate);
        BigDecimal rawAdjPrice = rawPrice.multiply(priceFactor, MC);
        BigDecimal adjustedPrice = rawAdjPrice.setScale(4, RoundingMode.HALF_UP);
        return new AlignedPrice(true, STATUS_ALIGNED, priceFactor, rawAdjPrice, adjustedPrice);
    }

    public String verifySplitCoverage(LocalDate fromBasisDate, LocalDate evalDate, StockStatus status) {
        if (status == null) {
            return STATUS_SPLIT_HISTORY_INCOMPLETE;
        }
        String syncErr = StockValuationService.splitSyncError(status);
        if (syncErr != null) {
            return STATUS_SPLIT_SYNC_ERROR;
        }
        LocalDate minDate = fromBasisDate.isBefore(evalDate) ? fromBasisDate : evalDate;
        LocalDate maxDate = fromBasisDate.isBefore(evalDate) ? evalDate : fromBasisDate;
        if (status.getSplitFromDate() == null || status.getSplitDate() == null) {
            return STATUS_SPLIT_HISTORY_INCOMPLETE;
        }
        if (status.getSplitFromDate().isAfter(minDate) || status.getSplitDate().isBefore(maxDate)) {
            return STATUS_SPLIT_HISTORY_INCOMPLETE;
        }
        return null;
    }
}
