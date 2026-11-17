package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockObservationService {
    private final StockEarningsForecastRepository forecasts;

    /** 讀取富途第三方觀察資料；預測維持原有時間資格，不與本地財報估值混淆。 */
    @Transactional(readOnly = true)
    public FutuObservationsDto read(String figi, Instant asOf) {
        List<FutuObservationsDto.Earnings> earnings = forecasts.findLatestForStock(figi, asOf).stream()
                .map(forecast -> new FutuObservationsDto.Earnings(forecast.getFiscalPeriod(), forecast.getPeriodType(),
                        forecast.getPeriodStart(), forecast.getPeriodEnd(), forecast.getCurrency(), forecast.getEps(),
                        forecast.getRevenue(), forecast.getObservationDate(), forecast.getObservedAt(), forecast.getSource())).toList();
        return new FutuObservationsDto(null, earnings);
    }
}
