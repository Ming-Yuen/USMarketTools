package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketContextBenchmarkWindowTests {
    private final MarketStatusService calendar = new MarketStatusService(null);
    private final LocalDate through = LocalDate.of(2026, 11, 2);
    private final Instant cutoff = Instant.parse("2026-11-03T15:00:00Z");
    private final AnalysisContext context = new AnalysisContext("STOCK-FIGI", cutoff, cutoff, AnalysisHorizon.SWING);
    private final QuoteNormalizationService quotes = mock(QuoteNormalizationService.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final List<LocalDate> sessions = last273Sessions();
    private final MarketContextService service = new MarketContextService(
            new MomentumCalculator(new WilderAtrCalculator()), new VolumeAnalysisCalculator(), new RelativeStrengthCalculator(),
            new DrawdownCalculator(), new MarketRegimeCalculator(), mock(MarketSentimentDailyRepository.class),
            mock(TreasuryParYieldDailyRepository.class), mock(MarketEventRepository.class), stocks, quotes, calendar);

    @Test
    void marketRegimeUses272SessionsEvenIfAnOlderQqqQuoteIsMissing() {
        MarketContextDto result = evaluate(sessions.getFirst(), MetricStatus.INSUFFICIENT_DATA, "PRICE_SESSIONS_MISSING");

        assertThat(result.market().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(result.market().value().regime()).isEqualTo(MarketRegime.RISK_ON);
        assertThat(result.relativeStrength().excessReturn120().status()).isEqualTo(MetricStatus.AVAILABLE);
        verify(quotes).normalize(context.withFigi("SPY-FIGI"), sessions.get(1), through);
        verify(quotes).normalize(context.withFigi("QQQ-FIGI"), sessions.get(1), through);
    }

    @Test
    void missingQqqQuoteInRequiredWindowStillPreventsMarketRegime() {
        MarketContextDto result = evaluate(sessions.get(1), MetricStatus.INSUFFICIENT_DATA, "PRICE_SESSIONS_MISSING");

        assertThat(result.market().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(result.market().reasons()).extracting(AnalysisReasonDto::code).containsExactly("MARKET_INDEX_SERIES_REQUIRED");
        SourceFreshnessDto qqq = result.sourceFreshness().get("QQQ");
        assertThat(qqq.status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(qqq.reasons()).extracting(AnalysisReasonDto::code).containsExactly("PRICE_SESSIONS_MISSING");
    }

    private MarketContextDto evaluate(LocalDate qqqBadSession, MetricStatus qqqStatus, String qqqReason) {
        Stock spy = new Stock();
        spy.setFigi("SPY-FIGI");
        Stock qqq = new Stock();
        qqq.setFigi("QQQ-FIGI");
        when(stocks.findBySymbol("SPY")).thenReturn(List.of(spy));
        when(stocks.findBySymbol("QQQ")).thenReturn(List.of(qqq));
        when(quotes.normalize(any(), any(), any())).thenAnswer(call -> {
            AnalysisContext benchmark = call.getArgument(0);
            LocalDate from = call.getArgument(1);
            LocalDate to = call.getArgument(2);
            if (benchmark.figi().equals(qqq.getFigi()) && !qqqBadSession.isBefore(from) && !qqqBadSession.isAfter(to)) {
                return MetricResult.unavailable(qqqStatus, AnalysisReasonDto.of(qqqReason, "PRICE", "缺少已核驗行情"));
            }
            return MetricResult.available(series(benchmark.figi(), from, to));
        });
        return service.evaluate(context, series(context.figi(), sessions.getFirst(), through), null).value();
    }

    private List<LocalDate> last273Sessions() {
        List<LocalDate> history = calendar.getTradingDays(through.minusYears(2), through);
        return history.subList(history.size() - 273, history.size());
    }

    private NormalizedPriceSeriesDto series(String figi, LocalDate from, LocalDate to) {
        String basisId = figi + "-BASIS";
        PriceBasisDescriptor basis = new PriceBasisDescriptor(basisId, "MASSIVE", PriceAdjustment.SPLIT_ADJUSTED, through,
                "UNIT", ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, "PROOF");
        List<NormalizedBarDto> bars = sessions.stream()
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .map(date -> {
                    BigDecimal close = BigDecimal.valueOf(100 + ChronoUnit.DAYS.between(sessions.getFirst(), date));
                    Instant closeAt = calendar.getSessionCloseAt(date);
                    return new NormalizedBarDto(date, close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                            MetricResult.available(BigDecimal.TEN), calendar.getSessionOpenAt(date), closeAt, closeAt, closeAt,
                            figi + ":" + date, basisId);
                }).toList();
        return new NormalizedPriceSeriesDto(figi, "DAILY", bars, basis, null, MetricStatus.AVAILABLE, List.of(), null);
    }
}
