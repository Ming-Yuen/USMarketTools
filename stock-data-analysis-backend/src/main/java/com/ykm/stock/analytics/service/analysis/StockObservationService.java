package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import com.ykm.stock.analytics.repository.jpa.StockValuationDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockObservationService {
    private final StockValuationDailyRepository valuations;
    private final StockEarningsForecastRepository forecasts;

    /** 估值已改為本地財報計算，不把結果冒充富途原生觀察；預測仍維持原有時間資格。 */
    @Transactional(readOnly = true)
    public FutuObservationsDto read(String figi, Instant asOf) {
        List<FutuObservationsDto.Earnings> earnings = forecasts.findLatestForStock(figi, asOf).stream()
                .map(forecast -> new FutuObservationsDto.Earnings(forecast.getFiscalPeriod(), forecast.getPeriodType(),
                        forecast.getPeriodStart(), forecast.getPeriodEnd(), forecast.getCurrency(), forecast.getEps(),
                        forecast.getRevenue(), forecast.getObservationDate(), forecast.getObservedAt(), forecast.getSource())).toList();
        return new FutuObservationsDto(null, earnings);
    }

    /** 只讀所選交易日已保存的結果；估值時間及來源取得時間各自保留。 */
    @Transactional(readOnly = true)
    public ValuationResultDto valuation(String figi, LocalDate day, Instant cutoff, String industryCode) {
        return valuations.findByFigiAndValuationDateAndValuationCutoffLessThanEqual(figi, day, cutoff)
                .map(row -> ValuationResultDto.fromCalculated(row, industryCode)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<ValuationResultDto> monthlyValuations(String figi, LocalDate from, LocalDate through, Instant asOf) {
        Map<YearMonth, ValuationResultDto> months = new LinkedHashMap<>();
        for (StockValuationDaily row : valuations.findByFigiAndValuationDateBetweenAndValuationCutoffLessThanEqualOrderByValuationDateAsc(figi, from, through, asOf)) {
            if (!row.hasAvailableMetric()) continue;
            months.put(YearMonth.from(row.getValuationDate()), ValuationResultDto.fromCalculated(row, null));
        }
        return List.copyOf(months.values());
    }
}
