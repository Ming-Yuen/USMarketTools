package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockObservationService {
    private final StockEarningsForecastRepository forecasts;
    private final StockShareObservationRepository shareObservations;

    /** 讀取富途第三方觀察資料；快照原生倍數與盈利預測均按觀察時間資格過濾，不與本地財報估值混淆。 */
    @Transactional(readOnly = true)
    public FutuObservationsDto read(String figi, Instant asOf) {
        FutuObservationsDto.Valuation valuation = shareObservations
                .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(figi, "FUTU", asOf)
                .filter(obs -> obs.getPeTtmRate() != null || obs.getPbRate() != null)
                .map(this::toValuationObservation)
                .orElse(null);
        List<FutuObservationsDto.Earnings> earnings = forecasts.findLatestForStock(figi, asOf).stream()
                .map(forecast -> new FutuObservationsDto.Earnings(forecast.getFiscalPeriod(), forecast.getPeriodType(),
                        forecast.getPeriodStart(), forecast.getPeriodEnd(), forecast.getCurrency(), forecast.getEps(),
                        forecast.getRevenue(), forecast.getObservationDate(), forecast.getObservedAt(), forecast.getSource())).toList();
        return new FutuObservationsDto(valuation, earnings);
    }

    private FutuObservationsDto.Valuation toValuationObservation(StockShareObservation obs) {
        Instant quoteTimestamp = obs.getSnapshotQuoteTime() != null ? obs.getSnapshotQuoteTime() : obs.getObservedAt();
        LocalDate observationDate = quoteTimestamp.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        return new FutuObservationsDto.Valuation(observationDate, obs.getObservedAt(),
                obs.getPeTtmRate(), obs.getPbRate(), null, null, null);
    }
}
