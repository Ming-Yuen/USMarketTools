package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.KnownReportPeriod;
import com.ykm.stock.analytics.repository.jpa.StockEarningsForecastRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator;
import com.ykm.stock.analytics.service.valuation.ForwardValuationCalculator.SelectedRevisionTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockObservationService {
    private final StockEarningsForecastRepository forecasts;
    private final StockShareObservationRepository shareObservations;
    private final FinancialReportRepository financialReports;
    private final ForwardValuationCalculator forwardCalculator = new ForwardValuationCalculator();

    public record ForwardObservationContext(
            String figi,
            LocalDate valuationDate,
            Instant asOf,
            String quoteCurrency,
            BigDecimal closePrice,
            BigDecimal marketCapAtDay,
            String sharesReason,
            BigDecimal epsAtDay,
            String peReason,
            List<StockSplit> splits,
            String splitSyncError,
            List<StockFinancialPeriod> storedPeriods
    ) {}

    /** 讀取富途第三方原生快照倍數與市場共識預期，並計算分軌隔離之前瞻估值與 30D／90D 預期修正。 */
    @Transactional(readOnly = true)
    public FutuObservationsDto readWithForward(ForwardObservationContext ctx) {
        LocalDate effectiveDay = ctx.valuationDate() != null ? ctx.valuationDate()
                : ctx.asOf().atZone(MarketStatusService.NY_ZONE).toLocalDate();
        LocalDate maxObservationDate = ctx.asOf() != null
                ? ctx.asOf().atZone(MarketStatusService.NY_ZONE).toLocalDate()
                : effectiveDay;
        FutuObservationsDto.Valuation valuation = shareObservations
                .findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(ctx.figi(), "FUTU", ctx.asOf())
                .filter(obs -> obs.getPeTtmRate() != null || obs.getPbRate() != null)
                .map(this::toValuationObservation)
                .orElse(null);

        List<StockEarningsForecast> latestForecasts = forecasts.findLatestForStock(ctx.figi(), maxObservationDate, ctx.asOf());
        List<FutuObservationsDto.Earnings> earnings = latestForecasts.stream()
                .map(forecast -> new FutuObservationsDto.Earnings(forecast.getFiscalPeriod(), forecast.getPeriodType(),
                        forecast.getPeriodStart(), forecast.getPeriodEnd(), forecast.getCurrency(), forecast.getEps(),
                        forecast.getRevenue(), forecast.getObservationDate(), forecast.getObservedAt(), forecast.getSource()))
                .toList();

        List<KnownReportPeriod> knownReports = financialReports.findKnownReportPeriods(ctx.figi(), ctx.asOf());
        ForwardValuationCalculator.Inputs calcInputs = new ForwardValuationCalculator.Inputs(
                effectiveDay,
                ctx.asOf(),
                ctx.quoteCurrency(),
                ctx.closePrice(),
                ctx.marketCapAtDay(),
                ctx.sharesReason(),
                ctx.epsAtDay(),
                ctx.peReason(),
                ctx.splits(),
                ctx.splitSyncError(),
                latestForecasts,
                knownReports,
                ctx.storedPeriods()
        );

        SelectedRevisionTarget revTarget = forwardCalculator.selectRevisionTarget(calcInputs);
        List<StockEarningsForecast> revHistory = List.of();
        if (revTarget != null && revTarget.latest().getObservationDate() != null) {
            LocalDate anchor = revTarget.latest().getObservationDate();
            revHistory = forecasts.findHistoryForPeriod(
                    ctx.figi(),
                    revTarget.source(),
                    revTarget.fiscalPeriod(),
                    revTarget.periodType(),
                    anchor.minusDays(ForwardValuationCalculator.REV_90D_MAX_DAYS + 5L),
                    anchor,
                    ctx.asOf());
        }

        ForwardMetrics forwardMetrics = forwardCalculator.calculate(calcInputs, revHistory);
        return new FutuObservationsDto(valuation, earnings, forwardMetrics);
    }

    /** 純快照與預期列表查詢入口（無收盤價／市值上下文時仍依觀察日與時間截點嚴格過濾）。 */
    @Transactional(readOnly = true)
    public FutuObservationsDto read(String figi, LocalDate valuationDate, Instant asOf) {
        return readWithForward(new ForwardObservationContext(
                figi, valuationDate, asOf, null, null, null, null, null, null, List.of(), null, List.of()));
    }

    private FutuObservationsDto.Valuation toValuationObservation(StockShareObservation obs) {
        Instant quoteTimestamp = obs.getSnapshotQuoteTime() != null ? obs.getSnapshotQuoteTime() : obs.getObservedAt();
        LocalDate observationDate = quoteTimestamp.atZone(MarketStatusService.NY_ZONE).toLocalDate();
        return new FutuObservationsDto.Valuation(observationDate, obs.getObservedAt(),
                obs.getPeTtmRate(), obs.getPbRate(), null, null, null);
    }
}
