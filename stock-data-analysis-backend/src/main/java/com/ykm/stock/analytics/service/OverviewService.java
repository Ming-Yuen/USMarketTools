package com.ykm.stock.analytics.service;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.config.AppProperties;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisContext;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.AnalysisHorizon;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedBarDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.NormalizedPriceSeriesDto;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.EtfRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.EventItem;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.ExecutionBias;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.FearGreedComponent;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.IndexRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.MarketRegimeBanner;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.MarketSummary;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.MessageItem;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.OpportunityRadarItem;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.OpportunityRadarSummary;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.SectorEtfRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.SectorMoneyFlowRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.SectorMoneyFlowSummary;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.SignalRow;
import com.ykm.stock.analytics.model.dto.stockInsight.response.DashboardResponse.TapeRow;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService;
import com.ykm.stock.analytics.service.analysis.SignalTrackingService.SignalTrackingStatsSummary;
import com.ykm.stock.analytics.service.market.MarketEventService;
import com.ykm.stock.analytics.service.market.MarketSentimentService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.market.TreasuryService;
import com.ykm.stock.analytics.service.stock.StockIndicatorService;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.util.PercentChange;
import com.ykm.stock.analytics.util.PercentChangeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
// 用途：負責「總覽服務」相關的業務協調與處理，集中管理首頁儀表板、大盤市況、11 大板塊 ETF、板塊資金流、每日機會雷達與訊號追蹤統計。
public class OverviewService {
    private static final MathContext MC = MathContext.DECIMAL128;

    private final StockService stockService;
    private final StockIndicatorService stockIndicatorService;
    private final QuoteNormalizationService quoteNormalizationService;
    private final TreasuryService treasuryService;
    private final MarketSentimentService marketSentimentService;
    private final MarketEventService marketEventService;
    private final MarketContextService marketContextService;
    private final MarketStatusService marketStatusService;
    private final DateTimeService dateTimeService;
    private final StockRepository stockRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final StockSplitRepository stockSplitRepository;
    private final StockStatusRepository stockStatusRepository;
    private final SectorMoneyFlowDailyRepository sectorMoneyFlowRepository;
    private final DailyOpportunityRadarRepository radarRepository;
    private final DailyOpportunityRadarRunRepository radarRunRepository;
    private final SignalTrackingService signalTrackingService;
    private final AppProperties appProperties;

    private static final String FIGI_SPX = "BBG000H4FSM0";
    private static final String FIGI_NDX = "BBG000KKFC45";
    private static final String FIGI_SOX = "BBG000KN8GQ4";
    private static final String FIGI_RUT = "BBG000HS1999";

    // SPY, QQQ, SOXX, VOO, URA
    private static final List<String> etfList = List.of("BBG000BSWKH7", "BBG0015VYNT4", "BBG000BDTBL9", "BBG000JP3063", "BBG0018JWV58");

    private static final Map<String, String> SECTOR_ETF_CODES = buildSectorEtfCodeMap();

    private static Map<String, String> buildSectorEtfCodeMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("XLK", "TECHNOLOGY");
        map.put("XLF", "FINANCIALS");
        map.put("XLE", "ENERGY");
        map.put("XLV", "HEALTH_CARE");
        map.put("XLI", "INDUSTRIALS");
        map.put("XLC", "COMMUNICATION_SERVICES");
        map.put("XLY", "CONSUMER_DISCRETIONARY");
        map.put("XLP", "CONSUMER_STAPLES");
        map.put("XLU", "UTILITIES");
        map.put("XLB", "MATERIALS");
        map.put("XLRE", "REAL_ESTATE");
        return Map.copyOf(map);
    }

    // 用途：整合行情、估值、情緒、板塊輪動、每日機會雷達與訊號追蹤統計，建立首頁 dashboard response。
    public DashboardResponse getDashboard() {
        Instant now = dateTimeService.now();
        Map<String, List<NormalizedBarDto>> quoteCache = new LinkedHashMap<>();
        AppProperties.Analysis analysis = appProperties.getOverview().getAnalysis();

        // 指數層觀察
        List<IndexRow> indexRows = buildIndexRows(quoteCache, now);

        // ETF 觀察列表
        List<EtfRow> etfRows = buildEtfRows(quoteCache, now);

        // 行情走勢帶
        List<TapeRow> tapeRows = buildTapeRows(quoteCache, now);
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

        // Batch 03：大盤 MarketRegime、11 大板塊 ETF、板塊量價資金流、每日機會雷達快照與訊號勝率統計
        // 大盤與各 ETF 各自封頂於已入庫交易日，避免行情分批同步期間因單檔推進至 T 日導致尚未同步的基準／ETF 暫時變 UNAVAILABLE 或消失
        LocalDate benchmarkDate = resolveBenchmarkDate(marketDate);
        PreloadedMarketData preloaded = benchmarkDate == null ? null : marketContextService.preloadMarketData(benchmarkDate, now);
        MarketRegimeBanner marketRegimeBanner = buildMarketRegimeBanner(benchmarkDate, preloaded);
        List<SectorEtfRow> sectorEtfs = buildSectorEtfRows(marketDate, now, preloaded);
        SectorMoneyFlowSummary sectorMoneyFlow = buildSectorMoneyFlowSummary();
        OpportunityRadarSummary opportunityRadar = buildOpportunityRadarSummary(marketRegimeBanner);
        SignalTrackingStatsSummary signalTrackingStats = signalTrackingService.buildStatsSummary();

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
                executionBias,
                marketRegimeBanner,
                sectorEtfs,
                sectorMoneyFlow,
                opportunityRadar,
                signalTrackingStats
        );
    }

    private MarketRegimeBanner buildMarketRegimeBanner(LocalDate marketDate, PreloadedMarketData preloaded) {
        if (preloaded == null || preloaded.marketSnapshot() == null
                || preloaded.marketSnapshot().status() != MetricStatus.AVAILABLE
                || preloaded.marketSnapshot().value() == null) {
            return new MarketRegimeBanner(MarketRegime.UNAVAILABLE.name(), null, false, false,
                    marketDate == null ? null : marketDate.toString());
        }
        MarketRegime regime = preloaded.marketSnapshot().value().regime();
        BigDecimal vix = preloaded.vixClose() != null && preloaded.vixClose().status() == MetricStatus.AVAILABLE
                ? preloaded.vixClose().value() : null;
        boolean headwind = regime == MarketRegime.BEAR || regime == MarketRegime.PANIC;
        boolean correction = regime == MarketRegime.CORRECTION;
        LocalDate asOf = preloaded.marketSnapshot().value().dataDate() != null
                ? preloaded.marketSnapshot().value().dataDate() : marketDate;
        return new MarketRegimeBanner(regime.name(), vix, headwind, correction, asOf == null ? null : asOf.toString());
    }

    /** 取 SPY 與 QQQ 共同最新已入庫交易日並封頂於 marketDate，避免行情分批同步期間因單一股票先推進至 T 日導致大盤市況暫時 UNAVAILABLE。 */
    private LocalDate resolveBenchmarkDate(LocalDate marketDate) {
        if (marketDate == null) {
            return null;
        }
        List<Stock> benchmarks = stockRepository.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of("SPY", "QQQ"));
        if (benchmarks == null || benchmarks.isEmpty()) {
            return marketDate;
        }
        List<String> figis = benchmarks.stream().map(Stock::getFigi).distinct().toList();
        LocalDate capped = null;
        for (StockStatus st : stockStatusRepository.findAllById(figis)) {
            if (st != null && st.getQuoteDate() != null) {
                LocalDate effective = st.getQuoteDate().isBefore(marketDate) ? st.getQuoteDate() : marketDate;
                capped = capped == null || effective.isBefore(capped) ? effective : capped;
            }
        }
        return capped != null ? capped : marketDate;
    }

    /** 批次載入 11 大板塊代表 ETF 與 SPY，各自按已入庫最新交易日（封頂於 marketDate）計算 5D/20D/60D 漲跌幅、RVOL20 與同日對齊相對 SPY 超額報酬。 */
    private List<SectorEtfRow> buildSectorEtfRows(LocalDate marketDate, Instant now, PreloadedMarketData preloaded) {
        if (marketDate == null) {
            return List.of();
        }
        List<String> symbols = SecurityTypePolicy.SECTOR_ETF_SYMBOLS;
        List<Stock> found = stockRepository.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, symbols);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<String, Stock> stockBySymbol = new LinkedHashMap<>();
        for (Stock s : found) {
            stockBySymbol.putIfAbsent(s.getSymbol(), s);
        }
        List<String> figis = stockBySymbol.values().stream().map(Stock::getFigi).toList();
        Map<String, StockStatus> statusByFigi = stockStatusRepository.findAllById(figis).stream()
                .collect(Collectors.toMap(StockStatus::getFigi, row -> row, (a, b) -> a));

        LocalDate queryFrom = marketDate.minusDays(140);
        Map<String, LocalDate> throughByFigi = new HashMap<>();
        for (String figi : figis) {
            StockStatus st = statusByFigi.get(figi);
            LocalDate etfThrough = st != null && st.getQuoteDate() != null && st.getQuoteDate().isBefore(marketDate)
                    ? st.getQuoteDate() : marketDate;
            throughByFigi.put(figi, etfThrough);
            LocalDate candidateFrom = etfThrough.minusDays(140);
            if (candidateFrom.isBefore(queryFrom)) {
                queryFrom = candidateFrom;
            }
        }

        Map<String, List<QuoteBarRow>> quotesByFigi = new HashMap<>();
        for (QuoteBarRow bar : stockQuoteRepository.findQuoteBarsOrdered(figis, queryFrom, marketDate)) {
            quotesByFigi.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
        }
        Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
        for (StockSplit sp : stockSplitRepository.findByFigiInOrderByExecutionDateAsc(figis)) {
            splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
        }

        List<NormalizedBarDto> spyBars = preloaded != null && preloaded.spy() != null
                && preloaded.spy().status() == MetricStatus.AVAILABLE && preloaded.spy().value() != null
                ? preloaded.spy().value().bars() : List.of();
        Map<LocalDate, BigDecimal> spyCloseByDate = new HashMap<>();
        for (NormalizedBarDto bar : spyBars) {
            if (bar != null && bar.sessionDate() != null && bar.close() != null && bar.close().signum() > 0) {
                spyCloseByDate.put(bar.sessionDate(), bar.close());
            }
        }

        List<SectorEtfRow> rows = new ArrayList<>();
        for (String symbol : symbols) {
            Stock stock = stockBySymbol.get(symbol);
            if (stock == null) continue;
            LocalDate etfThrough = throughByFigi.getOrDefault(stock.getFigi(), marketDate);
            LocalDate etfFrom = etfThrough.minusDays(140);
            Instant sessionCutoff = marketStatusService.getSessionCloseAt(etfThrough);
            AnalysisContext ctx = new AnalysisContext(stock.getFigi(), sessionCutoff, now, AnalysisHorizon.SWING);
            MetricResult<NormalizedPriceSeriesDto> norm = quoteNormalizationService.normalizeFromLoaded(
                    ctx, etfFrom, etfThrough, stock, statusByFigi.get(stock.getFigi()),
                    quotesByFigi.get(stock.getFigi()), splitsByFigi.getOrDefault(stock.getFigi(), List.of()));
            if (norm.status() != MetricStatus.AVAILABLE || norm.value() == null || norm.value().bars().isEmpty()) {
                continue;
            }
            List<NormalizedBarDto> bars = norm.value().bars();
            NormalizedBarDto latest = bars.getLast();
            BigDecimal ret5 = computeBarReturn(bars, 5);
            BigDecimal ret20 = computeBarReturn(bars, 20);
            BigDecimal ret60 = computeBarReturn(bars, 60);
            BigDecimal rvol20 = computeRvol20(bars);
            BigDecimal excess5 = computeExcessVsSpy(bars, 5, ret5, spyCloseByDate);
            BigDecimal excess20 = computeExcessVsSpy(bars, 20, ret20, spyCloseByDate);
            BigDecimal excess60 = computeExcessVsSpy(bars, 60, ret60, spyCloseByDate);

            rows.add(new SectorEtfRow(
                    symbol,
                    SECTOR_ETF_CODES.getOrDefault(symbol, symbol),
                    latest.close().setScale(2, RoundingMode.HALF_UP),
                    ret5,
                    ret20,
                    ret60,
                    rvol20,
                    excess5,
                    excess20,
                    excess60,
                    latest.sessionDate().toString()
            ));
        }
        rows.sort(Comparator.comparing(SectorEtfRow::getExcessVsSpy20d, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SectorEtfRow::getSymbol));
        return rows;
    }

    private SectorMoneyFlowSummary buildSectorMoneyFlowSummary() {
        Optional<LocalDate> latestDate = sectorMoneyFlowRepository.findLatestTradeDate();
        if (latestDate.isEmpty()) {
            return new SectorMoneyFlowSummary(null, 0, null, List.of());
        }
        LocalDate tradeDate = latestDate.get();
        List<SectorMoneyFlowDaily> stored = sectorMoneyFlowRepository.findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(tradeDate);
        if (stored.isEmpty()) {
            return new SectorMoneyFlowSummary(tradeDate.toString(), 0, null, List.of());
        }
        SectorMoneyFlowDaily first = stored.getFirst();
        List<SectorMoneyFlowRow> rows = stored.stream()
                .map(s -> new SectorMoneyFlowRow(
                        s.getIndustryCode(),
                        s.getIndustryName(),
                        s.getStockCount(),
                        s.getDollarVolume1d(),
                        s.getDollarVolume5d(),
                        s.getMarketVolumeShare1d(),
                        s.getMarketVolumeShare5d(),
                        s.getMarketVolumeShare20d(),
                        s.getVolumeShareDelta5dVs20d(),
                        s.getCmf5d(),
                        s.getCmf20d(),
                        s.getBreadthAboveSma50Count(),
                        s.getBreadthSma50Total(),
                        s.getBreadthAboveSma50Pct(),
                        s.getBreadthAboveSma200Count(),
                        s.getBreadthSma200Total(),
                        s.getBreadthAboveSma200Pct(),
                        s.getReturn20dMedian(),
                        s.getReturn60dMedian(),
                        s.getFlowState()))
                .toList();
        return new SectorMoneyFlowSummary(
                tradeDate.toString(),
                first.getMarketTotalStockCount(),
                first.getClassifiedCoveragePct(),
                rows
        );
    }

    private OpportunityRadarSummary buildOpportunityRadarSummary(MarketRegimeBanner banner) {
        Optional<DailyOpportunityRadarRun> latestRun = radarRunRepository.findTopByOrderByRadarDateDesc();
        if (latestRun.isEmpty()) {
            return new OpportunityRadarSummary(
                    null,
                    banner == null ? null : banner.getRegime(),
                    banner != null && banner.isHeadwindActive(),
                    0,
                    0,
                    List.of(),
                    List.of()
            );
        }
        DailyOpportunityRadarRun run = latestRun.get();
        LocalDate radarDate = run.getRadarDate();
        // 即使當日為 0 入選，也必須嚴格回傳最新發布日空清單，絕不回退復活前一日名單
        List<DailyOpportunityRadar> all = radarRepository.findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(radarDate);
        List<OpportunityRadarItem> valueList = new ArrayList<>();
        List<OpportunityRadarItem> starterList = new ArrayList<>();
        String snapshotRegime = run.getMarketRegime() != null ? run.getMarketRegime()
                : (banner == null ? null : banner.getRegime());
        boolean headwind = run.isHeadwindActive();
        for (DailyOpportunityRadar r : all) {
            OpportunityRadarItem item = toRadarItem(r);
            if (DailyOpportunityRadar.TRACK_VALUE_SUPPORT.equals(r.getTrackType())) {
                valueList.add(item);
            } else if (DailyOpportunityRadar.TRACK_TREND_PULLBACK_STARTER.equals(r.getTrackType())) {
                starterList.add(item);
            }
        }
        return new OpportunityRadarSummary(
                radarDate.toString(),
                snapshotRegime,
                headwind,
                valueList.size(),
                starterList.size(),
                valueList,
                starterList
        );
    }

    private OpportunityRadarItem toRadarItem(DailyOpportunityRadar r) {
        return new OpportunityRadarItem(
                r.getFigi(),
                r.getSymbol(),
                r.getStockName(),
                r.getCurrency(),
                r.getTrackType(),
                r.getIndustryCode(),
                r.getIndustryName(),
                r.getMarketCapTier(),
                r.getVerdict(),
                r.getFundamentalBacking(),
                r.getStance(),
                r.isHeadwindWatchOnly(),
                r.isMacroCautionActive(),
                r.getClosePrice(),
                r.getFairValueLow(),
                r.getFairValueMid(),
                r.getFairValueHigh(),
                r.getDiscountToMid(),
                r.getForwardPeg(),
                r.getForwardPe(),
                r.getRevenueGrowthYoy(),
                r.getForwardGrowthBasis(),
                r.getSupportLower(),
                r.getSupportUpper(),
                r.getSupportDistancePct(),
                r.getPullbackAnchor(),
                r.getPullbackAnchorPrice(),
                r.getProtectiveStop(),
                r.getFirstTargetPrice(),
                r.getNetRiskReward(),
                r.getPlanStatus(),
                r.getPositionFraction(),
                r.getNextEarningsDate() == null ? null : r.getNextEarningsDate().toString(),
                r.getDaysToNextEarnings(),
                r.getSectorCmf20d(),
                r.getSectorFlowState()
        );
    }

    private BigDecimal computeBarReturn(List<NormalizedBarDto> bars, int sessions) {
        if (bars == null || bars.size() <= sessions) return null;
        BigDecimal prior = bars.get(bars.size() - 1 - sessions).close();
        BigDecimal latest = bars.getLast().close();
        if (prior == null || prior.signum() <= 0 || latest == null) return null;
        return latest.divide(prior, MC).subtract(BigDecimal.ONE, MC).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeExcessVsSpy(List<NormalizedBarDto> bars, int sessions, BigDecimal etfRet, Map<LocalDate, BigDecimal> spyCloseByDate) {
        if (etfRet == null || bars == null || bars.size() <= sessions) return null;
        LocalDate startDate = bars.get(bars.size() - 1 - sessions).sessionDate();
        LocalDate endDate = bars.getLast().sessionDate();
        BigDecimal spyStart = spyCloseByDate.get(startDate);
        BigDecimal spyEnd = spyCloseByDate.get(endDate);
        if (spyStart == null || spyStart.signum() <= 0 || spyEnd == null) return null;
        BigDecimal spyRet = spyEnd.divide(spyStart, MC).subtract(BigDecimal.ONE, MC);
        return etfRet.subtract(spyRet, MC).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal computeRvol20(List<NormalizedBarDto> bars) {
        if (bars == null || bars.size() < 21) return null;
        BigDecimal latestVol = availableVolume(bars.getLast());
        if (latestVol == null) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int start = bars.size() - 21;
        for (int i = start; i < bars.size() - 1; i++) {
            BigDecimal v = availableVolume(bars.get(i));
            if (v == null) return null;
            sum = sum.add(v, MC);
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(20), MC);
        if (avg.signum() <= 0) return null;
        return latestVol.divide(avg, 2, RoundingMode.HALF_UP);
    }

    // 用途：建立IndexRows，作為「總覽服務」流程中的處理步驟。
    private List<IndexRow> buildIndexRows(Map<String, List<NormalizedBarDto>> quoteCache, Instant now) {
        Map<String, String> indexNames = new LinkedHashMap<>();
        indexNames.put(FIGI_SPX, "SP500");
        indexNames.put(FIGI_NDX, "NASDAQ100");
        indexNames.put(FIGI_SOX, "PHLX_SEMICONDUCTOR");
        indexNames.put(FIGI_RUT, "RUSSELL2000");
        List<IndexRow> rows = new ArrayList<>();
        indexNames.forEach((figi, name) -> loadStockAndQuotes(figi, 2, quoteCache, now).ifPresent(data -> {
            PercentChangeResult change = PercentChange.calculate(data.previous().close(), data.latest().close());
            rows.add(new IndexRow(name, data.stock().getSymbol(), formatPrice(data.latest().close()),
                    change.formattedPercentageChange(), change.direction().toString(), buildIndexNote(change)));
        }));
        return rows;
    }

    // 用途：建立ETFRows，作為「總覽服務」流程中的處理步驟。
    private List<EtfRow> buildEtfRows(Map<String, List<NormalizedBarDto>> quoteCache, Instant now) {
        List<EtfRow> rows = new ArrayList<>();
        for (String figi : etfList) {
            loadStockAndQuotes(figi, 20, quoteCache, now).ifPresent(data -> {
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
    private List<TapeRow> buildTapeRows(Map<String, List<NormalizedBarDto>> quoteCache, Instant now) {
        List<TapeRow> rows = new ArrayList<>();
        for (String figi : etfList.subList(0, 3)) {
            loadStockAndQuotes(figi, 2, quoteCache, now).ifPresent(data -> {
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
    private Optional<QuoteData> loadStockAndQuotes(String figi, int limit, Map<String, List<NormalizedBarDto>> quoteCache, Instant now) {
        Stock stock = stockService.findByFigi(figi);
        if (stock == null) return Optional.empty();
        List<NormalizedBarDto> normalized = quoteCache.computeIfAbsent(figi, key -> loadNormalizedBars(key, now));
        List<NormalizedBarDto> valid = normalized.stream()
                .filter(bar -> bar != null && bar.sessionDate() != null && bar.close() != null)
                .sorted(Comparator.comparing(NormalizedBarDto::sessionDate).reversed())
                .limit(Math.max(2, limit))
                .toList();
        if (valid.size() < 2) return Optional.empty();
        return Optional.of(new QuoteData(stock, valid.get(0), valid.get(1), valid));
    }

    // 用途：取得目前 OBSERVED 模式下完整、已核驗且 basis 一致的行情；任何 gap／basis 問題都不回退到 raw STOCK_QUOTE。
    private List<NormalizedBarDto> loadNormalizedBars(String figi, Instant now) {
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

    private String formatPrice(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private String formatDecimal(BigDecimal value) { return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private String formatPercent(BigDecimal value) { return value == null ? null : (value.signum() > 0 ? "+" : "") + value.toPlainString() + "%"; }
    private String formatRatio(BigDecimal value) { return value == null ? null : value.toPlainString() + "x"; }

    private record QuoteData(Stock stock, NormalizedBarDto latest, NormalizedBarDto previous, List<NormalizedBarDto> quotes) {}
}
