package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.calculator.WilderRsiCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
// 用途：負責「股票指標服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class StockIndicatorService {

    private static final WilderRsiCalculator RSI_CALCULATOR = new WilderRsiCalculator();

    private final SecurityTypePolicy securityTypePolicy;

    // -------- 基本指標 --------
    // 用途：使用已完成 basis 對齊的現價與 EPS 計算 PE；不接受未核驗或非普通股的每股估值。
    public BigDecimal getStockPe(String stockType, BigDecimal closePrice, BigDecimal earningsPerShareTtm) {
        if (!securityTypePolicy.supportsSimplePerShareValuation(stockType)) return null;
        return calculatePe(closePrice, earningsPerShareTtm);
    }

    /** 已完成股份基準核對的共用 PE 算式；沿用非正 EPS 留空及四位小數口徑。 */
    public static BigDecimal calculatePe(BigDecimal closePrice, BigDecimal earningsPerShareTtm) {
        if (closePrice == null || earningsPerShareTtm == null || earningsPerShareTtm.signum() <= 0) return null;
        return closePrice.divide(earningsPerShareTtm, 4, RoundingMode.HALF_UP);
    }

    // 用途：取得Peg，作為「股票指標服務」流程中的處理步驟。
    public BigDecimal getPeg(Stock stock){
        // PEG requires a reliable forward or historical EPS growth rate.
        return null;
    }

    // 用途：只使用正式 normalized bars 計算 RSI；若上游 normalization 因 gap／basis 問題失敗，呼叫者應傳空列表並顯示空值。
    public BigDecimal calculateLatestRsiFromNormalizedBars(List<NormalizedBarDto> bars, int period) {
        if (bars == null || period <= 0) return null;
        List<BigDecimal> closes = bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> bar.sessionDate() != null && bar.close() != null)
                .sorted(Comparator.comparing(NormalizedBarDto::sessionDate))
                .map(NormalizedBarDto::close)
                .toList();
        return RSI_CALCULATOR.latest(closes, period);
    }

    // 用途：以 normalized bars 計算 SMA20 偏離，避免 raw STOCK_QUOTE 跨 gap 或 split basis。
    public BigDecimal calculateDistanceToSma20FromNormalizedBars(BigDecimal latestClose, List<NormalizedBarDto> bars) {
        if (latestClose == null || bars == null) return null;
        List<BigDecimal> closes = bars.stream()
                .filter(Objects::nonNull)
                .filter(bar -> bar.sessionDate() != null && bar.close() != null)
                .sorted(Comparator.comparing(NormalizedBarDto::sessionDate).reversed())
                .limit(20)
                .map(NormalizedBarDto::close)
                .toList();
        if (closes.size() < 20) return null;
        BigDecimal sma20 = closes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(20), 6, RoundingMode.HALF_UP);
        if (sma20.signum() == 0) return null;
        return latestClose.divide(sma20, 6, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

}
