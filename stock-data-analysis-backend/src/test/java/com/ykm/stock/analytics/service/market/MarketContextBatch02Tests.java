package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketContextBatch02Tests {
    private final MarketStatusService calendar = new MarketStatusService(null);
    private final MarketEventRepository events = mock(MarketEventRepository.class);
    private final MarketSentimentDailyRepository sentiments = mock(MarketSentimentDailyRepository.class);
    private final TreasuryParYieldDailyRepository treasuries = mock(TreasuryParYieldDailyRepository.class);
    private final StockRepository stocks = mock(StockRepository.class);
    private final QuoteNormalizationService quotes = mock(QuoteNormalizationService.class);
    private final MomentumCalculator momentumCalculator = new MomentumCalculator(new WilderAtrCalculator());
    private final MarketContextService service = new MarketContextService(
            momentumCalculator, new VolumeAnalysisCalculator(), new RelativeStrengthCalculator(),
            new DrawdownCalculator(), new MarketRegimeCalculator(), sentiments, treasuries, events,
            stocks, quotes, calendar);

    @Test
    void constraintsMergePanicAndBearWithMacroEventsAndExposeVixEvenWhenIndexHistoryShort() {
        LocalDate through = LocalDate.of(2026, 11, 4);
        Instant asOf = calendar.getSessionCloseAt(through);
        List<LocalDate> sessions = lastSessions(through, 273);
        NormalizedPriceSeriesDto stockSeries = series("FIGI", sessions, false, null);

        Stock spy = new Stock();
        spy.setFigi("SPY-FIGI");
        Stock qqq = new Stock();
        qqq.setFigi("QQQ-FIGI");
        when(stocks.findBySymbol("SPY")).thenReturn(List.of(spy));
        when(stocks.findBySymbol("QQQ")).thenReturn(List.of(qqq));

        // 1) PANIC (VIX=40 + SPY 跌至 0.86 倍) + 當日 CPI -> BLOCK 且兩項原因並存（CPI 不得蓋掉 PANIC）
        MarketEvent cpiToday = macroEvent("MACRO|CPI|" + through, "CPI", through, asOf.minusSeconds(3600));
        when(events.findVisible("FIGI", through.minusDays(10), through.plusDays(20), asOf)).thenReturn(List.of(cpiToday));
        MarketSentimentDaily panicVix = new MarketSentimentDaily();
        panicVix.setMarketDate(through);
        panicVix.setVixClose(new BigDecimal("40"));
        when(sentiments.findByMarketDateBetweenOrderByMarketDateAsc(through.minusDays(400), through)).thenReturn(List.of(panicVix));
        doAnswer(call -> {
            AnalysisContext ctx = call.getArgument(0);
            List<LocalDate> benchDays = sessions.subList(1, sessions.size());
            if ("SPY-FIGI".equals(ctx.figi())) {
                return MetricResult.available(series("SPY-FIGI", benchDays, false, new BigDecimal("0.86")));
            }
            return MetricResult.available(series("QQQ-FIGI", benchDays, false, null));
        }).when(quotes).normalize(any(), any(), any());

        MarketContextDto panicWithCpi = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.POSITION), stockSeries, null, List.of()).value();
        assertThat(panicWithCpi.market().value().regime()).isEqualTo(MarketRegime.PANIC);
        assertThat(panicWithCpi.vixClose().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(panicWithCpi.vixClose().value()).isEqualByComparingTo("40");
        assertThat(panicWithCpi.tradeConstraint().entryPermission()).isEqualTo(EntryPermission.BLOCK);
        assertThat(panicWithCpi.tradeConstraint().reasonCodes()).contains("EVENT_REQUIRES_CLOSE_CONFIRMATION", "MARKET_PANIC");

        // 2) BEAR + 當日 FOMC -> BLOCK 且保留 MARKET_BEAR 與 EVENT_REQUIRES_CLOSE_CONFIRMATION
        MarketEvent fomcToday = macroEvent("MACRO|FOMC|" + through, "FOMC", through, asOf.minusSeconds(3600));
        when(events.findVisible("FIGI", through.minusDays(10), through.plusDays(20), asOf)).thenReturn(List.of(fomcToday));
        when(sentiments.findByMarketDateBetweenOrderByMarketDateAsc(through.minusDays(400), through)).thenReturn(List.of());
        doAnswer(call -> {
            AnalysisContext ctx = call.getArgument(0);
            List<LocalDate> benchDays = sessions.subList(1, sessions.size());
            return MetricResult.available(series(ctx.figi(), benchDays, true, null));
        }).when(quotes).normalize(any(), any(), any());

        MarketContextDto bearWithFomc = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.POSITION), stockSeries, null, List.of()).value();
        assertThat(bearWithFomc.market().value().regime()).isEqualTo(MarketRegime.BEAR);
        assertThat(bearWithFomc.tradeConstraint().entryPermission()).isEqualTo(EntryPermission.BLOCK);
        assertThat(bearWithFomc.tradeConstraint().reasonCodes()).contains("EVENT_REQUIRES_CLOSE_CONFIRMATION", "MARKET_BEAR");

        // 3) SPY/QQQ 缺失（market.value() == null），但 VIX_CLOSE=26 可用 -> 外層 vixClose 仍為 AVAILABLE
        MarketSentimentDaily elevatedVix = new MarketSentimentDaily();
        elevatedVix.setMarketDate(through);
        elevatedVix.setVixClose(new BigDecimal("26"));
        when(sentiments.findByMarketDateBetweenOrderByMarketDateAsc(through.minusDays(400), through)).thenReturn(List.of(elevatedVix));
        doReturn(MetricResult.unavailable(
                MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("PRICE_MISSING", "PRICE", "missing")))
                .when(quotes).normalize(any(), any(), any());
        MarketContextDto noIndexWithVix = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.POSITION), stockSeries, null, List.of()).value();
        assertThat(noIndexWithVix.market().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(noIndexWithVix.vixClose().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(noIndexWithVix.vixClose().value()).isEqualByComparingTo("26");
    }

    @Test
    void earningsAnchorSkipsUntradedAfterCloseAndResolvesBeforeOpenAndDateOnlyCorrectly() {
        LocalDate through = LocalDate.of(2026, 11, 4);
        Instant asOf = calendar.getSessionCloseAt(through);
        List<LocalDate> sessions = lastSessions(through, 40);
        NormalizedPriceSeriesDto stockSeries = series("FIGI", sessions, false, null);
        when(events.findVisible("FIGI", through.minusDays(10), through.plusDays(20), asOf)).thenReturn(List.of());

        // 同日兩筆盤後財報（尚未經過反應交易日）+ 上一季盤前財報 -> 應跳過當日盤後，回退錨定上一季盤前當日
        LocalDate priorEventDate = sessions.get(sessions.size() - 10);
        MarketEvent amcDup1 = earningsEvent("E|AMC1", through, "AFTER_CLOSE", asOf.minusSeconds(100));
        MarketEvent amcDup2 = earningsEvent("E|AMC2", through, "AFTER_CLOSE", asOf.minusSeconds(100));
        MarketEvent priorBmo = earningsEvent("E|PRIOR", priorEventDate, "BEFORE_OPEN", asOf.minusSeconds(10000));

        MarketContextDto ctx = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.SWING), stockSeries, null,
                List.of(amcDup1, amcDup2, priorBmo)).value();

        assertThat(ctx.volume().earningsEventDate()).isEqualTo(priorEventDate);
        assertThat(ctx.volume().earningsAnchorDate()).isEqualTo(priorEventDate);
        assertThat(ctx.volume().earningsAnchoredVwap().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(ctx.volume().earningsAnchoredVwapSeries()).hasSize(10);

        // DATE_ONLY 保守錨定至下一完整交易日
        LocalDate dateOnlyEvent = sessions.get(sessions.size() - 5);
        LocalDate expectedAnchor = calendar.getNextTradingDay(dateOnlyEvent);
        MarketEvent dateOnly = earningsEvent("E|DATEONLY", dateOnlyEvent, "DATE_ONLY", asOf.minusSeconds(1000));
        MarketContextDto ctxDateOnly = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.SWING), stockSeries, null, List.of(dateOnly)).value();
        assertThat(ctxDateOnly.volume().earningsEventDate()).isEqualTo(dateOnlyEvent);
        assertThat(ctxDateOnly.volume().earningsAnchorDate()).isEqualTo(expectedAnchor);
        assertThat(ctxDateOnly.volume().earningsAnchoredVwapSeries().getFirst().sessionDate()).isEqualTo(expectedAnchor);

        // EXACT 恰在開市時間（09:30 NY）錨定當日；開市後 1 分鐘（09:31 NY）錨定下一完整交易日
        LocalDate exactEventDay = sessions.get(sessions.size() - 6);
        MarketEvent exactAtOpen = earningsEvent("E|EXACT_OPEN", exactEventDay, "EXACT", asOf.minusSeconds(1000));
        exactAtOpen.setOccurrenceAt(calendar.getSessionOpenAt(exactEventDay));
        MarketContextDto ctxAtOpen = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.SWING), stockSeries, null, List.of(exactAtOpen)).value();
        assertThat(ctxAtOpen.volume().earningsAnchorDate()).isEqualTo(exactEventDay);

        MarketEvent exactAfterOpen = earningsEvent("E|EXACT_INTRADAY", exactEventDay, "EXACT", asOf.minusSeconds(1000));
        exactAfterOpen.setOccurrenceAt(calendar.getSessionOpenAt(exactEventDay).plusSeconds(60));
        MarketContextDto ctxAfterOpen = service.evaluate(
                new AnalysisContext("FIGI", asOf, asOf, AnalysisHorizon.SWING), stockSeries, null, List.of(exactAfterOpen)).value();
        assertThat(ctxAfterOpen.volume().earningsAnchorDate()).isEqualTo(calendar.getNextTradingDay(exactEventDay));
    }

    @Test
    void ema21Follows121BarWarmupRule() {
        LocalDate through = LocalDate.of(2026, 11, 4);
        List<NormalizedBarDto> bars120 = series("FIGI", lastSessions(through, 120), false, null).bars();
        List<NormalizedBarDto> bars121 = series("FIGI", lastSessions(through, 121), false, null).bars();
        assertThat(momentumCalculator.calculate(bars120).indicators().get("EMA21").status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);
        assertThat(momentumCalculator.calculate(bars121).indicators().get("EMA21").status()).isEqualTo(MetricStatus.AVAILABLE);
    }

    private List<LocalDate> lastSessions(LocalDate through, int count) {
        List<LocalDate> all = calendar.getTradingDays(through.minusYears(2), through);
        return all.subList(all.size() - count, all.size());
    }

    private NormalizedPriceSeriesDto series(String figi, List<LocalDate> dates, boolean downtrend, BigDecimal lastCloseFactor) {
        List<NormalizedBarDto> bars = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            LocalDate d = dates.get(i);
            BigDecimal close = downtrend
                    ? BigDecimal.valueOf(300).subtract(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5")))
                    : BigDecimal.valueOf(100).add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.5")));
            if (i == dates.size() - 1 && lastCloseFactor != null) {
                close = bars.get(i - 1).close().multiply(lastCloseFactor);
            }
            Instant openAt = calendar.getSessionOpenAt(d);
            Instant closeAt = calendar.getSessionCloseAt(d);
            bars.add(new NormalizedBarDto(d, close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                    MetricResult.available(new BigDecimal("1000")), openAt, closeAt, closeAt, closeAt, figi + ":" + d, "BASIS"));
        }
        PriceBasisDescriptor basis = new PriceBasisDescriptor("BASIS", "ALPACA", PriceAdjustment.SPLIT_ADJUSTED,
                dates.getLast(), null, ShareUnit.LISTED_SHARE, SessionScope.REGULAR, "USD", true, null);
        return new NormalizedPriceSeriesDto(figi, "DAILY", bars, basis, null, MetricStatus.AVAILABLE, List.of(), "hash");
    }

    private MarketEvent macroEvent(String key, String subtype, LocalDate date, Instant observedAt) {
        MarketEvent row = new MarketEvent();
        row.setEventKey(key);
        row.setEventType("MACRO");
        row.setEventSubtype(subtype);
        row.setTitle(subtype);
        row.setEventDate(date);
        row.setTimePrecision("DATE_ONLY");
        row.setSource("BLS");
        row.setObservedAt(observedAt);
        row.setStatus("SCHEDULED");
        return row;
    }

    private MarketEvent earningsEvent(String key, LocalDate date, String precision, Instant observedAt) {
        MarketEvent row = new MarketEvent();
        row.setEventKey(key);
        row.setFigi("FIGI");
        row.setEventType("EARNINGS");
        row.setEventSubtype("EARNINGS_REPORT");
        row.setTitle("Earnings");
        row.setEventDate(date);
        row.setTimePrecision(precision);
        row.setSource("Finnhub");
        row.setObservedAt(observedAt);
        row.setStatus("SCHEDULED");
        return row;
    }
}
