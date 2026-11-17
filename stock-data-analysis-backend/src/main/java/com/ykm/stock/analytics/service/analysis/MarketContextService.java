package com.ykm.stock.analytics.service.analysis;

import com.ykm.stock.analytics.service.market.MarketStatusService;

import com.ykm.stock.analytics.model.dto.stockInsight.analysis.*;
import com.ykm.stock.analytics.model.entity.MarketEvent;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily.SentimentMetric;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.service.analysis.calculator.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.*;
import java.util.*;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class MarketContextService {
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final MathContext MC = MathContext.DECIMAL128;
    private final MomentumCalculator momentumCalculator;
    private final VolumeAnalysisCalculator volumeCalculator;
    private final RelativeStrengthCalculator relativeStrengthCalculator;
    private final DrawdownCalculator drawdownCalculator;
    private final MarketRegimeCalculator regimeCalculator;
    private final MarketSentimentDailyRepository sentiments;
    private final TreasuryParYieldDailyRepository treasuries;
    private final MarketEventRepository events;
    private final StockRepository stocks;
    private final QuoteNormalizationService quotes;
    private final MarketStatusService calendar;

    /** 直接讀已入庫的行情、情緒、利率及已知日程；不存在原始檔案或證據再驗證步驟。 */
    @Transactional(readOnly = true)
    public MetricResult<MarketContextDto> evaluate(AnalysisContext context, NormalizedPriceSeriesDto stockPrice, PriceStructureDto structures) {
        if (stockPrice == null || stockPrice.bars().isEmpty()) return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("PRICE_REQUIRED", "MARKET_CONTEXT", "需要行情資料。"));
        if (stockPrice.quality() != MetricStatus.AVAILABLE) return MetricResult.unavailable(stockPrice.quality(),
                AnalysisReasonDto.of("PRICE_UNAVAILABLE", "MARKET_CONTEXT", "行情資料不足。"));
        List<NormalizedBarDto> bars = stockPrice.bars();
        LocalDate from = bars.get(Math.max(0, bars.size() - MarketRegimeCalculator.REQUIRED_SESSIONS)).sessionDate();
        LocalDate through = bars.getLast().sessionDate();
        MetricResult<NormalizedPriceSeriesDto> spy = benchmark(context, "SPY", from, through);
        MetricResult<NormalizedPriceSeriesDto> qqq = benchmark(context, "QQQ", from, through);
        Map<String, List<Observation>> inputs = loadInputs(through);
        MomentumAnalysisDto momentum = momentumCalculator.calculate(bars);
        VolumeAnalysisDto volume = volumeCalculator.calculate(bars, structures);
        DrawdownAnalysisDto drawdown = drawdownCalculator.calculate(bars, structures);
        RelativeStrengthDto relative = spy.status() == MetricStatus.AVAILABLE
                ? relativeStrengthCalculator.calculate(bars, spy.value().bars(), "SPY") : missingRelative(spy);
        MetricResult<MarketSnapshotDto> market = market(context, spy, qqq, inputs);
        TreasuryContextDto treasury = treasury(context, inputs);
        List<MarketEvent> knownEvents = events.findVisible(context.figi(), context.analysisAsOf().atZone(NY).toLocalDate().minusDays(10),
                context.analysisAsOf().atZone(NY).toLocalDate().plusDays(20), context.observationCutoff());
        List<EventRiskDto> risks = knownEvents.stream().map(event -> new EventRiskDto(event.getEventKey(), event.getEventType(),
                event.getEventSubtype(), event.getTitle(), event.getEventDate(), event.getOccurrenceAt(), event.getTimePrecision(),
                event.getObservedAt(), event.getSource(), "EARNINGS".equals(event.getEventType()) && earningsBlock(context, event.getEventDate()))).toList();
        List<AnalysisReasonDto> reasons = new ArrayList<>(market.reasons());
        reasons.addAll(treasury.reasons());
        if (relative.excessReturn60().status() != MetricStatus.AVAILABLE) reasons.addAll(relative.excessReturn60().reasons());
        reasons.add(AnalysisReasonDto.of("KNOWN_EVENTS_ONLY", "EVENT", "只列已取得的已知日程，不保證沒有其他事件；不保存全部歷史改期。"));
        MomentumVolumeDto summary = new MomentumVolumeDto(metric(momentum, "RSI14"), momentum.primarySignal(),
                metric(momentum, "MACD"), metric(momentum, "MACD_SIGNAL"), volume.rvol20(), volume.primarySignal(),
                drawdown.drawdown252(), returnN(bars, 20), returnN(bars, 60));
        Map<String, SourceFreshnessDto> freshness = new LinkedHashMap<>();
        freshness.put("STOCK_PRICE", seriesFreshness("STOCK_PRICE", stockPrice));
        freshness.put("SPY", spy.status() == MetricStatus.AVAILABLE ? seriesFreshness("SPY", spy.value())
                : new SourceFreshnessDto("SPY", null, null, null, spy.status(), spy.reasons(), List.of()));
        freshness.put("QQQ", qqq.status() == MetricStatus.AVAILABLE ? seriesFreshness("QQQ", qqq.value())
                : new SourceFreshnessDto("QQQ", null, null, null, qqq.status(), qqq.reasons(), List.of()));
        for (String source : List.of("TREASURY_10Y", "TREASURY_2Y", "VIX_CLOSE", "CNN_FG"))
            freshness.put(source, freshness(source, context, inputs, source.startsWith("TREASURY") ? 3 : 1));
        MarketContextDto result = new MarketContextDto(summary, relative, market, risks, constraint(context, market, knownEvents, risks),
                List.copyOf(reasons), List.of(), momentum, volume, drawdown, treasury, freshness);
        return MetricResult.available(result);
    }

    private MetricResult<NormalizedPriceSeriesDto> benchmark(AnalysisContext context, String symbol, LocalDate from, LocalDate through) {
        List<Stock> found = stocks.findBySymbol(symbol);
        if (found.size() != 1) return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("BENCHMARK_" + symbol + "_MISSING", "MARKET", "基準證券未唯一對應主檔。"));
        return quotes.normalize(context.withFigi(found.getFirst().getFigi()), from, through);
    }

    /** 日事實按行情所屬交易日選取；收市後抓取不表示該日沒有利率/VIX，來源更新時間也不是首次發布證明。 */
    private Map<String, List<Observation>> loadInputs(LocalDate through) {
        Map<String, List<Observation>> result = new LinkedHashMap<>();
        for (String code : List.of("CNN_FG", "CNN_BREADTH_SCORE", "CNN_VIX_SCORE", "VIX_CLOSE", "TREASURY_10Y", "TREASURY_2Y"))
            result.put(code, new ArrayList<>());
        for (MarketSentimentDaily row : sentiments.findByMarketDateBetweenOrderByMarketDateAsc(through.minusDays(400), through)) {
            addSentiment(result, "CNN_FG", row.getMarketDate(), row.getFearAndGreed());
            addSentiment(result, "CNN_BREADTH_SCORE", row.getMarketDate(), row.getStockPriceBreadth());
            addSentiment(result, "CNN_VIX_SCORE", row.getMarketDate(), row.getMarketVolatilityVix());
            add(result, "VIX_CLOSE", row.getMarketDate(), row.getVixClose(), row.getVixSourceAt(), row.getVixObservedAt());
        }
        for (TreasuryParYieldDaily row : treasuries.findByObservationDateBetweenOrderByObservationDateAsc(through.minusDays(400), through)) {
            Instant sourceAt = row.getSourceUpdatedAtUtc() == null ? null : row.getSourceUpdatedAtUtc().toInstant(ZoneOffset.UTC);
            add(result, "TREASURY_10Y", row.getObservationDate(), row.getTenYear(), sourceAt, row.getObservedAt());
            add(result, "TREASURY_2Y", row.getObservationDate(), row.getTwoYear(), sourceAt, row.getObservedAt());
        }
        return result;
    }

    private void addSentiment(Map<String, List<Observation>> inputs, String code, LocalDate date, SentimentMetric metric) {
        if (metric != null) add(inputs, code, date, metric.getScore(), metric.getCapturedAt(), metric.getObservedAt());
    }

    private void add(Map<String, List<Observation>> inputs, String code, LocalDate date, BigDecimal value,
                     Instant sourceAt, Instant observedAt) {
        if (value == null) return;
        inputs.get(code).add(new Observation(date, value, sourceAt, observedAt));
    }

    private MetricResult<BigDecimal> value(String code, AnalysisContext context, Map<String, List<Observation>> inputs, int maxLag) {
        List<Observation> history = inputs.get(code);
        if (history.isEmpty()) return missing(code + "_UNAVAILABLE", "所選交易日前沒有 " + code + " 資料。");
        Observation latest = history.getLast();
        LocalDate through = context.analysisAsOf().atZone(NY).toLocalDate();
        if (Math.max(0, calendar.getTradingDays(latest.date(), through).size() - 1) > maxLag)
            return MetricResult.unavailable(MetricStatus.STALE, AnalysisReasonDto.of(code + "_STALE", "MARKET", code + " 資料已過期。"));
        return MetricResult.available(latest.value());
    }

    private MetricResult<MarketSnapshotDto> market(AnalysisContext context, MetricResult<NormalizedPriceSeriesDto> spy,
                                                  MetricResult<NormalizedPriceSeriesDto> qqq, Map<String, List<Observation>> inputs) {
        if (spy.status() != MetricStatus.AVAILABLE || qqq.status() != MetricStatus.AVAILABLE)
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("MARKET_INDEX_SERIES_REQUIRED", "MARKET", "需要 SPY 與 QQQ 行情。"));
        MetricResult<MarketRegime> regime = regimeCalculator.classify(spy.value().bars(), qqq.value().bars(), value("VIX_CLOSE", context, inputs, 1));
        if (regime.status() != MetricStatus.AVAILABLE) return MetricResult.unavailable(regime.status(), regime.reasons(), List.of());
        MetricResult<BigDecimal> fear = value("CNN_FG", context, inputs, 1);
        MetricResult<BigDecimal> breadth = value("CNN_BREADTH_SCORE", context, inputs, 1);
        MetricResult<BigDecimal> cnnVix = value("CNN_VIX_SCORE", context, inputs, 1);
        List<AnalysisReasonDto> reasons = new ArrayList<>(fear.reasons());
        reasons.addAll(breadth.reasons());
        reasons.addAll(cnnVix.reasons());
        NormalizedBarDto last = spy.value().bars().getLast();
        return MetricResult.available(new MarketSnapshotDto(regime.value(), fear, breadth, cnnVix,
                last.sessionDate(), last.availableAt(), reasons, List.of()));
    }

    private TreasuryContextDto treasury(AnalysisContext context, Map<String, List<Observation>> inputs) {
        MetricResult<BigDecimal> ten = value("TREASURY_10Y", context, inputs, 3);
        MetricResult<BigDecimal> two = value("TREASURY_2Y", context, inputs, 3);
        List<Observation> tenHistory = inputs.get("TREASURY_10Y");
        List<Observation> twoHistory = inputs.get("TREASURY_2Y");
        MetricResult<BigDecimal> curve = ten.status() == MetricStatus.AVAILABLE && two.status() == MetricStatus.AVAILABLE
                && tenHistory.getLast().date().equals(twoHistory.getLast().date())
                ? MetricResult.available(ten.value().subtract(two.value(), MC)) : missing("TREASURY_CURVE_DATE_MISMATCH", "10Y 與 2Y 缺少同日資料。");
        MetricResult<BigDecimal> change = ten.status() == MetricStatus.AVAILABLE && tenHistory.size() >= 21
                ? MetricResult.available(ten.value().subtract(tenHistory.get(tenHistory.size() - 21).value(), MC))
                : missing("TREASURY_10Y_CHANGE20_REQUIRED", "10Y 的 20 期變化需要 21 筆有效觀測。");
        List<AnalysisReasonDto> reasons = new ArrayList<>(ten.reasons());
        reasons.addAll(two.reasons());
        reasons.addAll(curve.reasons());
        reasons.addAll(change.reasons());
        return new TreasuryContextDto(ten, two, change, curve, reasons, List.of());
    }

    private boolean earningsBlock(AnalysisContext context, LocalDate eventDate) {
        LocalDate session = calendar.getTradingDays(eventDate, eventDate).contains(eventDate) ? eventDate : calendar.getNextTradingDay(eventDate.minusDays(1));
        LocalDate start = previousSession(previousSession(session));
        return !context.analysisAsOf().isBefore(calendar.getSessionOpenAt(start))
                && context.analysisAsOf().isBefore(calendar.getSessionCloseAt(calendar.getNextTradingDay(session)));
    }

    private LocalDate previousSession(LocalDate date) {
        List<LocalDate> sessions = calendar.getTradingDays(date.minusDays(15), date.minusDays(1));
        if (sessions.isEmpty()) throw new IllegalStateException("NYSE_SESSION_REQUIRED");
        return sessions.getLast();
    }

    private PlanConstraintDto constraint(AnalysisContext context, MetricResult<MarketSnapshotDto> market,
                                        List<MarketEvent> knownEvents, List<EventRiskDto> risks) {
        if (risks.stream().anyMatch(EventRiskDto::blocksNewEntry)) return new PlanConstraintDto(EntryPermission.BLOCK, List.of("EARNINGS_EVENT_WINDOW"), List.of());
        boolean macroToday = knownEvents.stream().anyMatch(event -> "MACRO".equals(event.getEventType())
                && event.getEventDate().equals(context.analysisAsOf().atZone(NY).toLocalDate())
                && Set.of("FOMC", "CPI", "NONFARM_PAYROLLS").contains(event.getEventSubtype()));
        if (macroToday) return new PlanConstraintDto(EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("EVENT_REQUIRES_CLOSE_CONFIRMATION"), List.of());
        if (market.status() != MetricStatus.AVAILABLE) return new PlanConstraintDto(EntryPermission.UNKNOWN, List.of("MARKET_CONTEXT_UNAVAILABLE"), List.of());
        return switch (market.value().regime()) {
            case PANIC -> new PlanConstraintDto(EntryPermission.BLOCK, List.of("MARKET_PANIC"), List.of());
            case BEAR, CORRECTION -> new PlanConstraintDto(EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.of("MARKET_" + market.value().regime()), List.of());
            case UNAVAILABLE -> new PlanConstraintDto(EntryPermission.UNKNOWN, List.of("MARKET_CONTEXT_UNAVAILABLE"), List.of());
            default -> new PlanConstraintDto(EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), List.of());
        };
    }

    private SourceFreshnessDto freshness(String code, AnalysisContext context, Map<String, List<Observation>> inputs, int maxLag) {
        MetricResult<BigDecimal> metric = value(code, context, inputs, maxLag);
        List<Observation> history = inputs.get(code);
        Observation latest = history.isEmpty() ? null : history.getLast();
        return new SourceFreshnessDto(code, latest == null ? null : latest.date(), latest == null ? null : latest.availableAt(),
                latest == null ? null : latest.observedAt(), metric.status(), metric.reasons(), List.of());
    }

    private SourceFreshnessDto seriesFreshness(String source, NormalizedPriceSeriesDto series) {
        NormalizedBarDto last = series.bars().getLast();
        return new SourceFreshnessDto(source, last.sessionDate(), last.availableAt(), last.observedAt(), series.quality(), List.of(), List.of());
    }

    private RelativeStrengthDto missingRelative(MetricResult<?> source) {
        MetricResult<BigDecimal> missing = MetricResult.unavailable(source.status(), source.reasons(), List.of());
        return new RelativeStrengthDto("SPY", missing, missing, missing, Map.of(), missing, source.reasons());
    }

    private MetricResult<BigDecimal> metric(MomentumAnalysisDto momentum, String code) {
        return momentum.indicators().getOrDefault(code, missing(code + "_MISSING", "指標窗口不足。"));
    }

    private MetricResult<BigDecimal> returnN(List<NormalizedBarDto> bars, int days) {
        if (bars.size() <= days) return missing("RETURN" + days + "_REQUIRED", "回報窗口不足。");
        BigDecimal prior = bars.get(bars.size() - 1 - days).close();
        if (prior.signum() <= 0) return MetricResult.unavailable(MetricStatus.NOT_APPLICABLE,
                AnalysisReasonDto.of("RETURN_BASE_NON_POSITIVE", "MOMENTUM", "回報基準非正。"));
        return MetricResult.available(bars.getLast().close().divide(prior, MC).subtract(BigDecimal.ONE, MC));
    }

    private MetricResult<BigDecimal> missing(String code, String message) {
        return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of(code, "MARKET", message));
    }

    private record Observation(LocalDate date, BigDecimal value, Instant availableAt, Instant observedAt) {}
}