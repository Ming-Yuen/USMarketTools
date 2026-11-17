package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.service.market.MarketEventService;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.market.TreasuryService;
import com.ykm.stock.analytics.service.stock.StockIndicatorService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.config.AppProperties;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.EtfRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.EventItem;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.ExecutionBias;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.FearGreedComponent;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.MarketSummary;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.MessageItem;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.IndexRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.SignalRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.TapeRow;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import com.ykm.stock.analytics.util.PercentChangeResult;
import com.ykm.stock.analytics.util.PercentChange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
// 用途：負責「總覽服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class OverviewService {

    private final StockService stockService;
    private final StockIndicatorService stockIndicatorService;
    private final QuoteNormalizationService quoteNormalizationService;
    private final TreasuryService treasuryService;
    private final MarketSentimentService marketSentimentService;
    private final MarketEventService marketEventService;
    private final AppProperties appProperties;

    private static final String FIGI_SPX = "BBG000H4FSM0";
    private static final String FIGI_NDX = "BBG000KKFC45";
    private static final String FIGI_SOX = "BBG000KN8GQ4";
    private static final String FIGI_RUT = "BBG000HS1999";

//  SPY, QQQ, SOXX, VOO, URA
    private static final List<String> etfList = List.of("BBG000BSWKH7", "BBG0015VYNT4", "BBG000BDTBL9", "BBG000JP3063", "BBG0018JWV58");

    // 用途：整合行情、估值、情緒與其他摘要資料，建立首頁 dashboard response。
    public DashboardResponse getDashboard() {
        Map<String, List<NormalizedBarDto>> quoteCache = new LinkedHashMap<>();
        AppProperties.Analysis analysis = appProperties.getOverview().getAnalysis();

        // 指數層觀察
        List<IndexRow> indexRows = buildIndexRows(quoteCache);

        // ETF 觀察列表
        List<EtfRow> etfRows = buildEtfRows(quoteCache);

        // 行情走勢帶
        List<TapeRow> tapeRows = buildTapeRows(quoteCache);
        Optional<MarketSentimentDaily> sentiment = marketSentimentService.getLatestSnapshot();

        // 直接使用已核驗行情水位，不能用排程執行時間冒充資料更新時間。
        LocalDate marketDate = stockService.findMaxQuoteDate();

        List<String> sources = new ArrayList<>();
        if (!quoteCache.isEmpty()) sources.add("INTERNAL_MARKET_SNAPSHOT");
        if (sentiment.isPresent()) sources.add("CNN_FEAR_GREED");
        if (tapeRows.stream().anyMatch(row -> "US10Y".equals(row.getSymbol()))) sources.add("US_TREASURY");

        DashboardResponse.SnapshotMeta snapshotMeta = new DashboardResponse.SnapshotMeta(
                marketDate == null ? null : marketDate.toString(), sources);

        // 風險規則提醒／量價判斷規則
        List<MessageItem> alerts = new ArrayList<>();
        int bullishEtfCount = 0;
        int bearishEtfCount = 0;
        int riskScore = 0;
        for (EtfRow row : etfRows) {
            BigDecimal dayChange = row.getDay() == null ? null : new BigDecimal(row.getDay().replace("+", "").replace("%", ""));
            BigDecimal rsi = row.getRsi() == null ? null : new BigDecimal(row.getRsi());
            BigDecimal deviation = row.getMa20Deviation() == null ? null : new BigDecimal(row.getMa20Deviation().replace("+", "").replace("%", ""));
            BigDecimal volumeRatio = row.getVolumeRatio5d() == null ? null : new BigDecimal(row.getVolumeRatio5d().replace("x", ""));

            if (dayChange != null && dayChange.signum() > 0) bullishEtfCount++;
            if (dayChange != null && dayChange.signum() < 0) bearishEtfCount++;

            if (rsi != null && deviation != null
                    && rsi.compareTo(analysis.getRsiOverbought()) >= 0
                    && deviation.compareTo(analysis.getMa20ExtendedPercent()) >= 0) {
                alerts.add(message("ETF_OVERHEATED", Map.of(
                        "ticker", row.getTicker(), "rsi", formatDecimal(rsi), "deviation", formatPercent(deviation))));
                riskScore += 2;
            } else if (rsi != null && rsi.compareTo(analysis.getRsiOversold()) <= 0) {
                alerts.add(message("ETF_OVERSOLD", Map.of("ticker", row.getTicker(), "rsi", formatDecimal(rsi))));
                riskScore++;
            }

            if (dayChange != null && dayChange.signum() > 0 && volumeRatio != null
                    && volumeRatio.compareTo(analysis.getHighVolumeRatio()) >= 0) {
                alerts.add(message("ETF_VOLUME_CONFIRMED", Map.of(
                        "ticker", row.getTicker(), "volumeRatio", formatRatio(volumeRatio))));
            }

            if (deviation != null && deviation.compareTo(analysis.getMa20ExtendedPercent()) >= 0
                    && volumeRatio != null && volumeRatio.compareTo(analysis.getLowVolumeRatio()) < 0) {
                alerts.add(message("ETF_LOW_VOLUME_EXTENSION", Map.of(
                        "ticker", row.getTicker(), "deviation", formatPercent(deviation),
                        "volumeRatio", formatRatio(volumeRatio))));
                riskScore += 2;
            }
        }

        BigDecimal fearGreedScore = sentiment
                .map(MarketSentimentDaily::getFearAndGreed)
                .map(MarketSentimentDaily.SentimentMetric::getScore)
                .orElse(null);
        if (fearGreedScore != null && fearGreedScore.compareTo(analysis.getExtremeGreed()) >= 0) {
            alerts.add(message("EXTREME_GREED", Map.of("score", formatDecimal(fearGreedScore))));
            riskScore += 2;
        } else if (fearGreedScore != null && fearGreedScore.compareTo(analysis.getExtremeFear()) <= 0) {
            alerts.add(message("EXTREME_FEAR", Map.of("score", formatDecimal(fearGreedScore))));
            riskScore += 2;
        }
        if (alerts.isEmpty()) {
            alerts.add(message("NO_ALERTS", Map.of()));
        }

        // 即將發生
        List<EventItem> events = marketEventService.getUpcomingEvents();

        // 信號矩陣
        List<SignalRow> signalRows = buildSignalRows(sentiment);

        // 貪婪恐慌七因子
        List<FearGreedComponent> fearGreedComponents = buildFearGreedComponents(sentiment);

        // 市場狀態
        int observedEtfCount = bullishEtfCount + bearishEtfCount;
        String marketTitleCode;
        if (fearGreedScore != null && fearGreedScore.compareTo(analysis.getExtremeGreed()) >= 0) {
            marketTitleCode = bullishEtfCount >= bearishEtfCount ? "STRONG_BUT_OVERHEATED" : "HOT_BUT_DIVERGING";
        } else if (fearGreedScore != null && fearGreedScore.compareTo(analysis.getExtremeFear()) <= 0) {
            marketTitleCode = bullishEtfCount > bearishEtfCount ? "FEAR_WITH_RECOVERY" : "WEAK_AND_FEARFUL";
        } else if (bullishEtfCount > bearishEtfCount) {
            marketTitleCode = "BULLISH_NON_EXTREME";
        } else if (bearishEtfCount > bullishEtfCount) {
            marketTitleCode = "BEARISH";
        } else {
            marketTitleCode = "DIVERGING";
        }
        MarketSummary marketSummary = new MarketSummary(
                marketTitleCode,
                message(fearGreedScore == null ? "SUMMARY_WITHOUT_SENTIMENT" : "SUMMARY_WITH_SENTIMENT", Map.of(
                        "score", fearGreedScore == null ? "--" : formatDecimal(fearGreedScore),
                        "up", bullishEtfCount, "down", bearishEtfCount, "observed", observedEtfCount)),
                message(marketDate == null ? "DATE_UNAVAILABLE" : "MARKET_CLOSE", Map.of(
                        "date", marketDate == null ? "--" : marketDate.toString()))
        );

        // 執行傾向
        String executionDescriptionCode;
        if (riskScore >= 4) {
            executionDescriptionCode = "HIGH_RISK";
        } else if (riskScore > 0) {
            executionDescriptionCode = "SELECTIVE";
        } else if (bullishEtfCount > bearishEtfCount) {
            executionDescriptionCode = "BULLISH";
        } else if (bearishEtfCount > bullishEtfCount) {
            executionDescriptionCode = "DEFENSIVE";
        } else {
            executionDescriptionCode = "NEUTRAL";
        }
        ExecutionBias executionBias = new ExecutionBias("EXECUTION_BIAS", executionDescriptionCode);

        return new DashboardResponse(
                snapshotMeta,
                tapeRows,
                indexRows,
                etfRows,
                alerts,
                events,
                signalRows,
                fearGreedComponents,
                marketSummary,
                executionBias
        );
    }

    // 用途：建立IndexRows，作為「總覽服務」流程中的處理步驟。
    private List<IndexRow> buildIndexRows(Map<String, List<NormalizedBarDto>> quoteCache) {
        Map<String, String> indexNames = new LinkedHashMap<>();
        indexNames.put(FIGI_SPX, "SP500");
        indexNames.put(FIGI_NDX, "NASDAQ100");
        indexNames.put(FIGI_SOX, "PHLX_SEMICONDUCTOR");
        indexNames.put(FIGI_RUT, "RUSSELL2000");
        List<IndexRow> rows = new ArrayList<>();
        indexNames.forEach((figi, name) -> loadStockAndQuotes(figi, 2, quoteCache).ifPresent(data -> {
            PercentChangeResult change = PercentChange.calculate(data.previous().close(), data.latest().close());
            rows.add(new IndexRow(name, data.stock().getSymbol(), formatPrice(data.latest().close()),
                    change.formattedPercentageChange(), change.direction().toString(), buildIndexNote(change)));
        }));
        return rows;
    }

    // 用途：建立ETFRows，作為「總覽服務」流程中的處理步驟。
    private List<EtfRow> buildEtfRows(Map<String, List<NormalizedBarDto>> quoteCache) {
        List<EtfRow> rows = new ArrayList<>();
        for (String figi : etfList) {
            loadStockAndQuotes(figi, 20, quoteCache).ifPresent(data -> {
                PercentChangeResult change = PercentChange.calculate(data.previous().close(), data.latest().close());
                BigDecimal rsi = stockIndicatorService.calculateLatestRsiFromNormalizedBars(data.quotes(), 14);
                BigDecimal deviation = stockIndicatorService.calculateDistanceToSma20FromNormalizedBars(data.latest().close(), data.quotes());
                BigDecimal volumeRatio = calculateVolumeRatio(data.quotes());
                rows.add(new EtfRow(data.stock().getSymbol(), formatPrice(data.latest().close()),
                        change.formattedPercentageChange(), formatDecimal(rsi), rsiBias(rsi), formatPercent(deviation),
                        formatRatio(volumeRatio), buildEtfNote(rsi, deviation, volumeRatio), "INTERNAL_MARKET_SNAPSHOT",
                        data.latest().sessionDate().toString()));
            });
        }
        return rows;
    }

    // 用途：建立TapeRows，作為「總覽服務」流程中的處理步驟。
    private List<TapeRow> buildTapeRows(Map<String, List<NormalizedBarDto>> quoteCache) {
        List<TapeRow> rows = new ArrayList<>();
        for (String figi : etfList.subList(0, 3)) {
            loadStockAndQuotes(figi, 2, quoteCache).ifPresent(data -> {
                PercentChangeResult change = PercentChange.calculate(data.previous().close(), data.latest().close());
                rows.add(new TapeRow(data.stock().getSymbol(), formatPrice(data.latest().close()),
                        change.formattedPercentageChange(), change.percentageChange().signum() >= 0));
            });
        }
        try {
            List<TreasuryParYieldDaily> yields = treasuryService.getLatestTwoTreasuryParYieldDailies();
            TreasuryParYieldDaily latest = yields.stream().max(Comparator.comparing(TreasuryParYieldDaily::getObservationDate)).orElseThrow();
            PercentChangeResult change = PercentChange.calculateLatest(yields, TreasuryParYieldDaily::getObservationDate, TreasuryParYieldDaily::getTenYear);
            rows.add(new TapeRow("US10Y", latest.getTenYear().toPlainString(), change.formattedPercentageChange(), change.percentageChange().signum() >= 0));
        } catch (RuntimeException ex) {
            log.warn("Unable to add US10Y to dashboard: {}", ex.getMessage());
        }
        return rows;
    }

    // 用途：載入股票AndQuotes，作為「總覽服務」流程中的處理步驟。
    private Optional<QuoteData> loadStockAndQuotes(String figi, int limit, Map<String, List<NormalizedBarDto>> quoteCache) {
        Stock stock = stockService.findByFigi(figi);
        if (stock == null) return Optional.empty();
        List<NormalizedBarDto> normalized = quoteCache.computeIfAbsent(figi, this::loadNormalizedBars);
        List<NormalizedBarDto> valid = normalized.stream()
                .filter(bar -> bar != null && bar.sessionDate() != null && bar.close() != null)
                .sorted(Comparator.comparing(NormalizedBarDto::sessionDate).reversed())
                .limit(Math.max(2, limit))
                .toList();
        if (valid.size() < 2) return Optional.empty();
        return Optional.of(new QuoteData(stock, valid.get(0), valid.get(1), valid));
    }

    // 用途：取得目前 OBSERVED 模式下完整、已核驗且 basis 一致的行情；任何 gap／basis 問題都不回退到 raw STOCK_QUOTE。
    private List<NormalizedBarDto> loadNormalizedBars(String figi) {
        java.time.Instant now = java.time.Instant.now();
        AnalysisContext context = new AnalysisContext(figi, now, now, AnalysisHorizon.SWING);
        LocalDate cutoff = context.analysisAsOf().atZone(ZoneId.of("America/New_York")).toLocalDate();
        MetricResult<NormalizedPriceSeriesDto> normalized = quoteNormalizationService.normalize(
                context, cutoff.minusYears(1), cutoff);
        return normalized.status() == MetricStatus.AVAILABLE && normalized.value() != null
                ? normalized.value().bars() : List.of();
    }

    // 用途：使用 normalized volume 計算 5 日成交量比率；volume 未核驗時直接回空值。
    private BigDecimal calculateVolumeRatio(List<NormalizedBarDto> quotes) {
        if (quotes == null || quotes.size() < 6) return null;
        BigDecimal latest = availableVolume(quotes.get(0));
        if (latest == null) return null;
        List<BigDecimal> previousVolumes = quotes.subList(1, 6).stream()
                .map(this::availableVolume).filter(Objects::nonNull).toList();
        if (previousVolumes.size() < 5) return null;
        BigDecimal average = previousVolumes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(5), 6, RoundingMode.HALF_UP);
        return average.signum() == 0 ? null : latest.divide(average, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal availableVolume(NormalizedBarDto bar) {
        if (bar == null || bar.volume() == null || bar.volume().status() != MetricStatus.AVAILABLE) return null;
        return bar.volume().value();
    }

    // 用途：建立SignalRows，作為「總覽服務」流程中的處理步驟。
    private List<SignalRow> buildSignalRows(Optional<MarketSentimentDaily> sentiment) {
        List<SignalRow> rows = new ArrayList<>();
        sentiment.ifPresent(value -> {
            if (value.getFearAndGreed() != null) {
                rows.add(new SignalRow("FEAR_GREED", formatDecimal(value.getFearAndGreed().getScore()),
                        sentimentStatusCode(value.getFearAndGreed().getScore()), "CNN"));
            }
            if (value.getMarketVolatilityVix() != null) {
                rows.add(new SignalRow("CNN_VIX_SCORE", formatDecimal(value.getMarketVolatilityVix().getScore()),
                        sentimentStatusCode(value.getMarketVolatilityVix().getScore()), "CNN_SENTIMENT_FACTOR"));
            }
            if (value.getPutCallOptions() != null) {
                rows.add(new SignalRow("CNN_PUT_CALL_SCORE", formatDecimal(value.getPutCallOptions().getScore()),
                        sentimentStatusCode(value.getPutCallOptions().getScore()), "CNN_SENTIMENT_FACTOR"));
            }
            if (value.getStockPriceBreadth() != null) {
                rows.add(new SignalRow("CNN_MARKET_BREADTH_SCORE", formatDecimal(value.getStockPriceBreadth().getScore()),
                        sentimentStatusCode(value.getStockPriceBreadth().getScore()), "CNN_SENTIMENT_FACTOR"));
            }
        });
        return rows;
    }

    // 用途：建立FearGreedComponents，作為「總覽服務」流程中的處理步驟。
    private List<FearGreedComponent> buildFearGreedComponents(Optional<MarketSentimentDaily> sentiment) {
        if (sentiment.isEmpty()) return List.of();
        MarketSentimentDaily value = sentiment.get();
        List<FearGreedComponent> rows = new ArrayList<>();
        addComponent(rows, "MARKET_MOMENTUM", value.getMarketMomentumSp500());
        addComponent(rows, "STOCK_PRICE_STRENGTH", value.getStockPriceStrength());
        addComponent(rows, "STOCK_PRICE_BREADTH", value.getStockPriceBreadth());
        addComponent(rows, "PUT_CALL_OPTIONS", value.getPutCallOptions());
        addComponent(rows, "MARKET_VOLATILITY", value.getMarketVolatilityVix());
        addComponent(rows, "SAFE_HAVEN_DEMAND", value.getSafeHavenDemand());
        addComponent(rows, "JUNK_BOND_DEMAND", value.getJunkBondDemand());
        return rows;
    }

    // 用途：處理addComponent，作為「總覽服務」流程中的處理步驟。
    private void addComponent(List<FearGreedComponent> rows, String name, MarketSentimentDaily.SentimentMetric metric) {
        if (metric != null && metric.getScore() != null) {
            rows.add(new FearGreedComponent(name, formatDecimal(metric.getScore()), sentimentStatusCode(metric.getScore())));
        }
    }

    // 用途：建立IndexNote，作為「總覽服務」流程中的處理步驟。
    private String buildIndexNote(PercentChangeResult change) {
        return switch (change.direction()) { case up -> "UP"; case down -> "DOWN"; case flat -> "FLAT"; };
    }

    // 用途：處理rsiBias，作為「總覽服務」流程中的處理步驟。
    private String rsiBias(BigDecimal rsi) {
        if (rsi == null) return "INSUFFICIENT_DATA";
        if (rsi.compareTo(BigDecimal.valueOf(70)) >= 0) return "HOT";
        if (rsi.compareTo(BigDecimal.valueOf(30)) <= 0) return "WEAK";
        if (rsi.compareTo(BigDecimal.valueOf(50)) >= 0) return "STRONG";
        return "NEUTRAL_WEAK";
    }

    // 用途：建立ETFNote，作為「總覽服務」流程中的處理步驟。
    private String buildEtfNote(BigDecimal rsi, BigDecimal deviation, BigDecimal volumeRatio) {
        if (rsi != null && rsi.compareTo(BigDecimal.valueOf(70)) >= 0) return "RSI_HOT";
        if (deviation != null && deviation.abs().compareTo(BigDecimal.valueOf(3)) >= 0) return "MA20_EXTENDED";
        if (volumeRatio != null && volumeRatio.compareTo(BigDecimal.valueOf(1.2)) >= 0) return "HIGH_VOLUME";
        return "NORMAL";
    }

    // 用途：處理情緒狀態Code，作為「總覽服務」流程中的處理步驟。
    private String sentimentStatusCode(BigDecimal score) {
        if (score == null) return "INSUFFICIENT_DATA";
        if (score.compareTo(BigDecimal.valueOf(25)) <= 0) return "EXTREME_FEAR";
        if (score.compareTo(BigDecimal.valueOf(45)) < 0) return "FEAR";
        if (score.compareTo(BigDecimal.valueOf(55)) < 0) return "NEUTRAL";
        if (score.compareTo(BigDecimal.valueOf(75)) < 0) return "GREED";
        return "EXTREME_GREED";
    }

    // 用途：處理message，作為「總覽服務」流程中的處理步驟。
    private MessageItem message(String code, Map<String, Object> params) {
        return new MessageItem(code, params);
    }

    // 用途：處理format價格，作為「總覽服務」流程中的處理步驟。
    private String formatPrice(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    // 用途：處理formatDecimal，作為「總覽服務」流程中的處理步驟。
    private String formatDecimal(BigDecimal value) { return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    // 用途：處理formatPercent，作為「總覽服務」流程中的處理步驟。
    private String formatPercent(BigDecimal value) { return value == null ? null : (value.signum() > 0 ? "+" : "") + value.toPlainString() + "%"; }
    // 用途：處理format比率，作為「總覽服務」流程中的處理步驟。
    private String formatRatio(BigDecimal value) { return value == null ? null : value.toPlainString() + "x"; }

    // 用途：負責「行情資料」相關的業務協調與處理，集中管理此領域的服務邏輯。
    // 用途：處理行情資料，作為「總覽服務」流程中的處理步驟。
    private record QuoteData(Stock stock, NormalizedBarDto latest, NormalizedBarDto previous, List<NormalizedBarDto> quotes) {}

}
