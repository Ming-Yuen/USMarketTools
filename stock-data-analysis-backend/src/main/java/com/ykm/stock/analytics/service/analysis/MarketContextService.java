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

    /** 預載之共用大盤背景（SPY/QQQ 基準序列、情緒、利率與宏觀事件），供兩階段雷達與儀表板零重複 DB 查詢共用。 */
    public record PreloadedMarketData(
            LocalDate through,
            Instant observationCutoff,
            MetricResult<NormalizedPriceSeriesDto> spy,
            MetricResult<NormalizedPriceSeriesDto> qqq,
            Map<String, List<Observation>> inputs,
            List<MarketEvent> marketWideEvents,
            MetricResult<MarketSnapshotDto> marketSnapshot,
            MetricResult<BigDecimal> vixClose
    ) {
        /** 取得預載視窗內截至 through 之最新一筆 10Y 美債殖利率（百分比數值，如 4.35）；缺值時回傳 NULL。 */
        public BigDecimal latestTreasury10y() {
            if (inputs == null) {
                return null;
            }
            List<Observation> list = inputs.get("TREASURY_10Y");
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.getLast().value();
        }
    }

    /** 一次載入 SPY/QQQ 272 個交易日基準日線（與單股 MarketRegime 視窗完全一致，避免 273+ 日舊缺口誤殺）、近 400 日情緒與美債殖利率及宏觀事件。 */
    @Transactional(readOnly = true)
    public PreloadedMarketData preloadMarketData(LocalDate through, Instant observationCutoff) {
        Instant cutoff = calendar.getSessionCloseAt(through);
        Instant obsCutoff = observationCutoff != null ? observationCutoff : cutoff;
        AnalysisContext baseContext = new AnalysisContext("MARKET_PRELOAD", cutoff, obsCutoff, AnalysisHorizon.POSITION);
        List<LocalDate> sessions = calendar.getTradingDays(through.minusDays(460), through);
        LocalDate from = sessions.size() >= MarketRegimeCalculator.REQUIRED_SESSIONS
                ? sessions.get(sessions.size() - MarketRegimeCalculator.REQUIRED_SESSIONS)
                : (sessions.isEmpty() ? through.minusYears(1) : sessions.getFirst());
        MetricResult<NormalizedPriceSeriesDto> spy = benchmark(baseContext, "SPY", from, through);
        MetricResult<NormalizedPriceSeriesDto> qqq = benchmark(baseContext, "QQQ", from, through);
        Map<String, List<Observation>> inputs = loadInputs(through);
        MetricResult<BigDecimal> vixClose = value("VIX_CLOSE", baseContext, inputs, 1);
        MetricResult<MarketSnapshotDto> snapshot = market(baseContext, spy, qqq, inputs, vixClose);
        List<MarketEvent> macroEvents = events.findVisibleMarketWide(through.minusDays(10), through.plusDays(20), obsCutoff);
        return new PreloadedMarketData(through, obsCutoff, spy, qqq, inputs, macroEvents, snapshot, vixClose);
    }

    /** 供兩階段雷達批次精算呼叫：完全複用預載大盤資料並嚴守與單股 evaluate(...) 一致之 272 日基準對齊視窗，零額外 DB 查詢。 */
    public MetricResult<MarketContextDto> evaluateWithPreloaded(
            AnalysisContext context,
            NormalizedPriceSeriesDto stockPrice,
            PriceStructureDto structures,
            List<MarketEvent> pastEarnings,
            List<MarketEvent> stockWindowEvents,
            PreloadedMarketData preloaded) {
        if (stockPrice == null || stockPrice.bars().isEmpty()) return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                AnalysisReasonDto.of("PRICE_REQUIRED", "MARKET_CONTEXT", "需要行情資料。"));
        if (stockPrice.quality() != MetricStatus.AVAILABLE) return MetricResult.unavailable(stockPrice.quality(),
                AnalysisReasonDto.of("PRICE_UNAVAILABLE", "MARKET_CONTEXT", "行情資料不足。"));
        List<NormalizedBarDto> bars = stockPrice.bars();
        LocalDate from = bars.get(Math.max(0, bars.size() - MarketRegimeCalculator.REQUIRED_SESSIONS)).sessionDate();
        LocalDate through = bars.getLast().sessionDate();
        MetricResult<NormalizedPriceSeriesDto> spy = slicePreloadedBenchmark(preloaded.spy(), from, through);
        MetricResult<NormalizedPriceSeriesDto> qqq = slicePreloadedBenchmark(preloaded.qqq(), from, through);
        Map<String, List<Observation>> inputs = preloaded.inputs();
        List<MarketEvent> knownEvents = new ArrayList<>();
        if (preloaded.marketWideEvents() != null) {
            knownEvents.addAll(preloaded.marketWideEvents());
        }
        if (stockWindowEvents != null) {
            knownEvents.addAll(stockWindowEvents);
        }
        knownEvents.sort(Comparator.comparing(MarketEvent::getEventDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MarketEvent::getOccurrenceAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return evaluateCore(context, stockPrice, structures, pastEarnings, spy, qqq, inputs, knownEvents);
    }

    /** 直接讀已入庫的行情、情緒、利率及已知日程；不存在原始檔案或證據再驗證步驟。 */
    @Transactional(readOnly = true)
    public MetricResult<MarketContextDto> evaluate(AnalysisContext context, NormalizedPriceSeriesDto stockPrice, PriceStructureDto structures) {
        return evaluate(context, stockPrice, structures, List.of());
    }

    /** 支援由報告入口傳入已查詢之過去財報列表以計算財報 Anchored VWAP，同時不破壞原有單次 findVisible 測試契約。 */
    @Transactional(readOnly = true)
    public MetricResult<MarketContextDto> evaluate(AnalysisContext context, NormalizedPriceSeriesDto stockPrice,
                                                   PriceStructureDto structures, List<MarketEvent> pastEarnings) {
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
        List<MarketEvent> knownEvents = events.findVisible(context.figi(), context.analysisAsOf().atZone(NY).toLocalDate().minusDays(10),
                context.analysisAsOf().atZone(NY).toLocalDate().plusDays(20), context.observationCutoff());
        return evaluateCore(context, stockPrice, structures, pastEarnings, spy, qqq, inputs, knownEvents);
    }

    private MetricResult<MarketContextDto> evaluateCore(
            AnalysisContext context,
            NormalizedPriceSeriesDto stockPrice,
            PriceStructureDto structures,
            List<MarketEvent> pastEarnings,
            MetricResult<NormalizedPriceSeriesDto> spy,
            MetricResult<NormalizedPriceSeriesDto> qqq,
            Map<String, List<Observation>> inputs,
            List<MarketEvent> knownEvents) {
        List<NormalizedBarDto> bars = stockPrice.bars();
        LocalDate through = bars.getLast().sessionDate();
        MetricResult<BigDecimal> vixClose = value("VIX_CLOSE", context, inputs, 1);
        MomentumAnalysisDto momentum = momentumCalculator.calculate(bars);
        EarningsAnchorSelection earningsAnchor = resolveEarningsAnchor(pastEarnings, through);
        VolumeAnalysisDto volume = volumeCalculator.calculate(bars, structures, earningsAnchor.eventDate(), earningsAnchor.anchorSession());
        DrawdownAnalysisDto drawdown = drawdownCalculator.calculate(bars, structures);
        RelativeStrengthDto relative = spy.status() == MetricStatus.AVAILABLE
                ? relativeStrengthCalculator.calculate(bars, spy.value().bars(), "SPY") : missingRelative(spy);
        MetricResult<MarketSnapshotDto> market = market(context, spy, qqq, inputs, vixClose);
        TreasuryContextDto treasury = treasury(context, inputs);
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
                List.copyOf(reasons), List.of(), momentum, volume, drawdown, treasury, freshness, vixClose);
        return MetricResult.available(result);
    }

    private MetricResult<NormalizedPriceSeriesDto> slicePreloadedBenchmark(
            MetricResult<NormalizedPriceSeriesDto> source, LocalDate from, LocalDate through) {
        if (source == null || source.status() != MetricStatus.AVAILABLE || source.value() == null) {
            return source;
        }
        List<NormalizedBarDto> sliced = source.value().bars().stream()
                .filter(b -> b != null && b.sessionDate() != null && !b.sessionDate().isBefore(from) && !b.sessionDate().isAfter(through))
                .toList();
        if (sliced.isEmpty()) {
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA,
                    AnalysisReasonDto.of("PRICE_MISSING", "PRICE", "基準行情於指定視窗內不足。"));
        }
        NormalizedPriceSeriesDto base = source.value();
        PriceSeriesCoverageDto coverage = new PriceSeriesCoverageDto(from, through,
                sliced.getFirst().sessionDate(), sliced.getLast().sessionDate(),
                sliced.size(), sliced.size(), List.of(), base.evidenceRefs());
        return MetricResult.available(new NormalizedPriceSeriesDto(
                base.figi(), base.timeframe(), sliced, base.priceBasis(), coverage,
                base.quality(), base.evidenceRefs(), base.inputHash()), base.evidenceRefs());
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
                                                  MetricResult<NormalizedPriceSeriesDto> qqq, Map<String, List<Observation>> inputs,
                                                  MetricResult<BigDecimal> vixClose) {
        if (spy.status() != MetricStatus.AVAILABLE || qqq.status() != MetricStatus.AVAILABLE)
            return MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, AnalysisReasonDto.of("MARKET_INDEX_SERIES_REQUIRED", "MARKET", "需要 SPY 與 QQQ 行情。"));
        MetricResult<MarketRegime> regime = regimeCalculator.classify(spy.value().bars(), qqq.value().bars(), vixClose);
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

    /** 合併所有同時命中的事件與大盤市況限制，避免宏觀事件蓋過 PANIC／BEAR 封鎖。 */
    private PlanConstraintDto constraint(AnalysisContext context, MetricResult<MarketSnapshotDto> market,
                                        List<MarketEvent> knownEvents, List<EventRiskDto> risks) {
        List<String> reasonCodes = new ArrayList<>();
        boolean blocked = false;
        boolean requireCloseConfirmation = false;
        boolean marketUnavailable = false;

        if (risks.stream().anyMatch(EventRiskDto::blocksNewEntry)) {
            blocked = true;
            reasonCodes.add("EARNINGS_EVENT_WINDOW");
        }
        boolean macroToday = knownEvents.stream().anyMatch(event -> "MACRO".equals(event.getEventType())
                && event.getEventDate().equals(context.analysisAsOf().atZone(NY).toLocalDate())
                && Set.of("FOMC", "CPI", "NONFARM_PAYROLLS").contains(event.getEventSubtype()));
        if (macroToday) {
            requireCloseConfirmation = true;
            reasonCodes.add("EVENT_REQUIRES_CLOSE_CONFIRMATION");
        }
        if (market.status() != MetricStatus.AVAILABLE || market.value() == null || market.value().regime() == MarketRegime.UNAVAILABLE) {
            marketUnavailable = true;
            reasonCodes.add("MARKET_CONTEXT_UNAVAILABLE");
        } else {
            MarketRegime regime = market.value().regime();
            if (regime == MarketRegime.PANIC) {
                blocked = true;
                reasonCodes.add("MARKET_PANIC");
            } else if (regime == MarketRegime.BEAR) {
                blocked = true;
                reasonCodes.add("MARKET_BEAR");
            } else if (regime == MarketRegime.CORRECTION) {
                requireCloseConfirmation = true;
                reasonCodes.add("MARKET_CORRECTION");
            }
        }

        if (blocked) {
            return new PlanConstraintDto(EntryPermission.BLOCK, List.copyOf(reasonCodes), List.of());
        }
        if (marketUnavailable) {
            return new PlanConstraintDto(EntryPermission.UNKNOWN, List.copyOf(reasonCodes), List.of());
        }
        if (requireCloseConfirmation) {
            return new PlanConstraintDto(EntryPermission.REQUIRE_CLOSE_CONFIRMATION, List.copyOf(reasonCodes), List.of());
        }
        return new PlanConstraintDto(EntryPermission.ALLOW, List.of("NO_KNOWN_BLOCKING_EVENT"), List.of());
    }

    /** 依交易日曆解析最近一次已完成反應交易日的財報錨點；盤後／未附時刻保守採用下一完整交易日，跳過尚未反應的事件。 */
    private EarningsAnchorSelection resolveEarningsAnchor(List<MarketEvent> pastEarnings, LocalDate through) {
        if (pastEarnings == null || pastEarnings.isEmpty() || through == null) {
            return new EarningsAnchorSelection(null, null);
        }
        List<MarketEvent> sorted = pastEarnings.stream()
                .filter(e -> e != null && "EARNINGS".equals(e.getEventType())
                        && !"CANCELLED".equals(e.getStatus()) && e.getEventDate() != null)
                .sorted(Comparator.comparing(MarketEvent::getEventDate, Comparator.reverseOrder())
                        .thenComparing(MarketEvent::getOccurrenceAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MarketEvent::getEventKey, Comparator.nullsLast(String::compareTo)))
                .toList();
        for (MarketEvent event : sorted) {
            LocalDate anchorSession = resolvePostEventSession(event);
            if (anchorSession != null && !anchorSession.isAfter(through)) {
                return new EarningsAnchorSelection(event.getEventDate(), anchorSession);
            }
        }
        return new EarningsAnchorSelection(null, null);
    }

    private LocalDate resolvePostEventSession(MarketEvent event) {
        LocalDate eventDate = event.getEventDate();
        String precision = event.getTimePrecision();
        if ("BEFORE_OPEN".equals(precision)) {
            return calendar.isTradingDay(eventDate) ? eventDate : calendar.getNextTradingDay(eventDate);
        }
        if ("EXACT".equals(precision) && event.getOccurrenceAt() != null) {
            LocalDate tradingDay = calendar.isTradingDay(eventDate) ? eventDate : calendar.getNextTradingDay(eventDate);
            if (!event.getOccurrenceAt().isAfter(calendar.getSessionOpenAt(tradingDay))) {
                return tradingDay;
            }
            return calendar.getNextTradingDay(tradingDay);
        }
        // AFTER_CLOSE、DATE_ONLY 或缺精確時刻皆保守採用事件後第一個完整正規交易日
        return calendar.getNextTradingDay(eventDate);
    }

    private record EarningsAnchorSelection(LocalDate eventDate, LocalDate anchorSession) {}

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