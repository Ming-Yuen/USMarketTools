package com.ykm.stock.analytics.service.analysis;

import com.google.common.collect.Lists;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PeerRelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RelativeStrengthWindowDto;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockCustomPeer;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository.QuoteBarRow;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.analysis.calculator.QuoteNormalizer;
import com.ykm.stock.analytics.service.analysis.calculator.StockSplitCalculator;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基於既有 OHLCV 日線與拆股紀錄計算全市場各產業板塊之成交額加權量價資金流（CMF）、市場成交額佔比變化、
 * 內部均線廣度（SMA50 / SMA200）與橫截面 20D/60D 報酬中位數（統計母體為 USD 追蹤營運股）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SectorMoneyFlowService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int BATCH_SIZE = 250;
    private static final int REQUIRED_SMA50 = 50;
    private static final int REQUIRED_SMA200 = 200;
    private static final int RETURN_20D = 20;
    private static final int RETURN_60D = 60;
    private static final BigDecimal RELATIVE_THRESHOLD = new BigDecimal("0.01");
    private static final BigDecimal OUTPERFORM_THRESHOLD = new BigDecimal("0.02");

    /** 板塊廣度崩壞統一門檻（僅由同日有效產業樣本觸發，與 CMF / flow_state 脫鉤）。 */
    public static final int BREADTH_BREAKDOWN_MIN_SAMPLE = 8;
    public static final BigDecimal BREADTH_BREAKDOWN_MAX_SMA50_PCT = new BigDecimal("0.20");
    public static final BigDecimal BREADTH_BREAKDOWN_MAX_SMA200_PCT = new BigDecimal("0.35");
    public static final BigDecimal BREADTH_BREAKDOWN_MAX_RET20_MEDIAN = new BigDecimal("-0.05");

    /** 單一產業單日廣度崩壞評估結果；eligible=false 代表樣本不足或有效覆蓋率低於 70%（不觸發崩壞，亦不計為確認修復）。 */
    public record SectorBreadthStatus(boolean eligible, boolean breakdown) {
        public static final SectorBreadthStatus INSUFFICIENT = new SectorBreadthStatus(false, false);
        public static final SectorBreadthStatus HEALTHY = new SectorBreadthStatus(true, false);
        public static final SectorBreadthStatus BREAKDOWN = new SectorBreadthStatus(true, true);
    }

    private final StockRepository stocks;
    private final StockStatusRepository statuses;
    private final StockQuoteRepository quotes;
    private final StockSplitRepository splits;
    private final StockIndustryRepository industries;
    private final StockIndustryHistoryRepository industryHistories;
    private final StockCustomPeerRepository customPeers;
    private final SectorMoneyFlowDailyRepository sectorMoneyFlows;
    private final MarketStatusService calendar;
    private final DateTimeService dateTimeService;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    private static final class IndustryAccumulator {
        final String code;
        final String name;
        int stockCount;
        BigDecimal dollarVol1d = BigDecimal.ZERO;
        BigDecimal dollarVol5d = BigDecimal.ZERO;
        BigDecimal dollarVol20d = BigDecimal.ZERO;
        BigDecimal mfv5d = BigDecimal.ZERO;
        BigDecimal mfv20d = BigDecimal.ZERO;
        int aboveSma50Count;
        int sma50Total;
        int aboveSma200Count;
        int sma200Total;
        final List<BigDecimal> returns20d = new ArrayList<>();
        final List<BigDecimal> returns60d = new ArrayList<>();

        IndustryAccumulator(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    /** 單股單日量價資金流乘數 MFM；high <= low 時固定回傳 0 防除以零，保留成交額分母權重。 */
    public static BigDecimal moneyFlowMultiplier(BigDecimal high, BigDecimal low, BigDecimal close) {
        if (high == null || low == null || close == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal range = high.subtract(low, MC);
        if (range.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal numerator = close.subtract(low, MC).subtract(high.subtract(close, MC), MC);
        BigDecimal mfm = numerator.divide(range, MC);
        if (mfm.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        if (mfm.compareTo(BigDecimal.ONE.negate()) < 0) return BigDecimal.ONE.negate();
        return mfm;
    }

    /** 依 5D 與 20D CMF 判斷資金流向標籤；任一為 NULL 則回傳 NULL。 */
    public static String classifyFlowState(BigDecimal cmf5d, BigDecimal cmf20d) {
        if (cmf5d == null || cmf20d == null) {
            return null;
        }
        if (cmf5d.signum() > 0 && cmf20d.signum() > 0) {
            return SectorMoneyFlowDaily.FLOW_INFLOW;
        }
        if (cmf5d.signum() < 0 && cmf20d.signum() < 0) {
            return SectorMoneyFlowDaily.FLOW_OUTFLOW;
        }
        return SectorMoneyFlowDaily.FLOW_NEUTRAL;
    }

    /**
     * 全系統單一入口：判定產業單日快照是否通過樣本覆蓋門檻並觸發板塊廣度崩壞。
     * 資格門檻：stockCount >= 8，且 SMA50 / SMA200 / 20D 報酬有效分母皆 >= 8 且 >= 70% stockCount（整數精確判定 denom * 10 >= stockCount * 7）。
     * 觸發條件：breadthAboveSma50Pct <= 0.20 且 breadthAboveSma200Pct <= 0.35 且 return20dMedian <= -0.05。
     */
    public static SectorBreadthStatus evaluateBreadthBreakdown(SectorMoneyFlowDaily snapshot) {
        if (snapshot == null) {
            return SectorBreadthStatus.INSUFFICIENT;
        }
        int stockCount = snapshot.getStockCount();
        int sma50Total = snapshot.getBreadthSma50Total();
        int sma200Total = snapshot.getBreadthSma200Total();
        int ret20Count = snapshot.getReturn20dCount();
        if (stockCount < BREADTH_BREAKDOWN_MIN_SAMPLE
                || sma50Total < BREADTH_BREAKDOWN_MIN_SAMPLE
                || sma200Total < BREADTH_BREAKDOWN_MIN_SAMPLE
                || ret20Count < BREADTH_BREAKDOWN_MIN_SAMPLE) {
            return SectorBreadthStatus.INSUFFICIENT;
        }
        long minCoveredTimesTen = stockCount * 7L;
        if (sma50Total * 10L < minCoveredTimesTen
                || sma200Total * 10L < minCoveredTimesTen
                || ret20Count * 10L < minCoveredTimesTen) {
            return SectorBreadthStatus.INSUFFICIENT;
        }
        if (snapshot.getBreadthAboveSma50Pct() == null
                || snapshot.getBreadthAboveSma200Pct() == null
                || snapshot.getReturn20dMedian() == null) {
            return SectorBreadthStatus.INSUFFICIENT;
        }
        boolean breakdown = snapshot.getBreadthAboveSma50Pct().compareTo(BREADTH_BREAKDOWN_MAX_SMA50_PCT) <= 0
                && snapshot.getBreadthAboveSma200Pct().compareTo(BREADTH_BREAKDOWN_MAX_SMA200_PCT) <= 0
                && snapshot.getReturn20dMedian().compareTo(BREADTH_BREAKDOWN_MAX_RET20_MEDIAN) <= 0;
        return breakdown ? SectorBreadthStatus.BREAKDOWN : SectorBreadthStatus.HEALTHY;
    }

    /** 嚴格驗證快照與評估日同日一致後再判定廣度崩壞；跨日回退快照僅供展示，絕不觸發風控縮倉。 */
    public static boolean isSameDayBreadthBreakdown(SectorMoneyFlowDaily snapshot, LocalDate expectedTradeDate) {
        if (snapshot == null || expectedTradeDate == null || !expectedTradeDate.equals(snapshot.getTradeDate())) {
            return false;
        }
        return evaluateBreadthBreakdown(snapshot).breakdown();
    }

    /** 供單股即時分析查詢所屬產業於評估日是否處於板塊廣度崩壞狀態（純唯讀，嚴格同日）。 */
    @Transactional(readOnly = true)
    public boolean isIndustryBreadthBreakdown(String industryCode, LocalDate tradeDate) {
        if (industryCode == null || industryCode.isBlank() || tradeDate == null) {
            return false;
        }
        Optional<SectorMoneyFlowDaily> snapshot = sectorMoneyFlows.findByTradeDateAndIndustryCode(tradeDate, industryCode);
        return snapshot.isPresent() && isSameDayBreadthBreakdown(snapshot.get(), tradeDate);
    }

    /** 依個股、同業中位數與 SPY 報酬對照分類相對強弱標籤。 */
    public static String classifyPeerRelativeLabel(
            BigDecimal stockRet20, BigDecimal peerRet20, BigDecimal spyRet20,
            BigDecimal stockRet60, BigDecimal peerRet60, BigDecimal spyRet60) {
        BigDecimal stockRet = stockRet20 != null && peerRet20 != null && spyRet20 != null ? stockRet20 : stockRet60;
        BigDecimal peerRet = stockRet20 != null && peerRet20 != null && spyRet20 != null ? peerRet20 : peerRet60;
        BigDecimal spyRet = stockRet20 != null && peerRet20 != null && spyRet20 != null ? spyRet20 : spyRet60;
        if (stockRet == null || peerRet == null || spyRet == null) {
            return null;
        }
        BigDecimal peerVsSpy = peerRet.subtract(spyRet, MC);
        BigDecimal stockVsPeer = stockRet.subtract(peerRet, MC);
        if (peerVsSpy.compareTo(RELATIVE_THRESHOLD) >= 0) {
            if (stockVsPeer.compareTo(RELATIVE_THRESHOLD) >= 0) {
                return PeerRelativeStrengthDto.LABEL_SECTOR_STRONG_STOCK_LEADING;
            }
            if (stockVsPeer.compareTo(RELATIVE_THRESHOLD.negate()) <= 0) {
                return PeerRelativeStrengthDto.LABEL_SECTOR_STRONG_STOCK_LAGGING;
            }
            return PeerRelativeStrengthDto.LABEL_NEUTRAL_INLINE;
        }
        if (peerVsSpy.compareTo(RELATIVE_THRESHOLD.negate()) <= 0) {
            if (stockVsPeer.compareTo(OUTPERFORM_THRESHOLD) >= 0 && stockRet.compareTo(spyRet) >= 0) {
                return PeerRelativeStrengthDto.LABEL_SECTOR_WEAK_STOCK_OUTPERFORMING;
            }
            if (stockVsPeer.signum() <= 0 || stockRet.compareTo(spyRet) < 0) {
                return PeerRelativeStrengthDto.LABEL_SECTOR_WEAK_COLLECTIVE_DECLINE;
            }
            return PeerRelativeStrengthDto.LABEL_NEUTRAL_INLINE;
        }
        return PeerRelativeStrengthDto.LABEL_NEUTRAL_INLINE;
    }

    /**
     * 刷新指定已收市交易日之全市場板塊量價資金流、成交額佔比、SMA50/SMA200 均線廣度與 20D/60D 中位報酬。
     * 分批按 FIGI 走 `(figi, quote_date)` 主鍵索引查詢，不掃描無條件全表，短交易原子發布。
     */
    public List<SectorMoneyFlowDaily> refreshDaily(LocalDate tradeDate, Instant observationCutoff) {
        if (tradeDate == null) {
            return List.of();
        }
        Instant calculatedAt = dateTimeService.now();
        Instant cutoff = observationCutoff != null ? observationCutoff : calendar.getSessionCloseAt(tradeDate);

        List<LocalDate> sessions = calendar.getTradingDays(tradeDate.minusDays(365), tradeDate);
        if (sessions.isEmpty() || !sessions.getLast().equals(tradeDate)) {
            log.warn("Sector money flow skipped: tradeDate={} is not a valid session", tradeDate);
            return List.of();
        }
        int totalSessions = sessions.size();
        LocalDate date5Start = sessions.get(Math.max(0, totalSessions - 5));
        LocalDate date20Start = sessions.get(Math.max(0, totalSessions - 20));
        LocalDate date50Start = totalSessions >= REQUIRED_SMA50 ? sessions.get(totalSessions - REQUIRED_SMA50) : null;
        LocalDate date200Start = totalSessions >= REQUIRED_SMA200 ? sessions.get(totalSessions - REQUIRED_SMA200) : null;
        LocalDate date20RetBase = totalSessions > RETURN_20D ? sessions.get(totalSessions - 1 - RETURN_20D) : null;
        LocalDate date60RetBase = totalSessions > RETURN_60D ? sessions.get(totalSessions - 1 - RETURN_60D) : null;
        LocalDate loadFrom = date200Start != null ? date200Start : sessions.getFirst();

        Set<LocalDate> sma50Window = date50Start == null ? Set.of()
                : new HashSet<>(sessions.subList(totalSessions - REQUIRED_SMA50, totalSessions));
        Set<LocalDate> sma200Window = date200Start == null ? Set.of()
                : new HashSet<>(sessions.subList(totalSessions - REQUIRED_SMA200, totalSessions));
        Set<LocalDate> ret20Window = date20RetBase == null ? Set.of()
                : new HashSet<>(sessions.subList(totalSessions - 1 - RETURN_20D, totalSessions));
        Set<LocalDate> ret60Window = date60RetBase == null ? Set.of()
                : new HashSet<>(sessions.subList(totalSessions - 1 - RETURN_60D, totalSessions));

        List<ActiveStockWithStatus> universe = stocks.findOperatingValuationStocks(ActiveStatus.ACTIVE).stream()
                .filter(row -> row.stock() != null && "USD".equalsIgnoreCase(row.stock().getCurrency()))
                .toList();
        if (universe.isEmpty()) {
            return List.of();
        }

        Map<String, String> industryNames = industries.findAll().stream()
                .collect(Collectors.toMap(StockIndustry::getCode, StockIndustry::getIndustry, (a, b) -> a));
        Map<String, IndustryAccumulator> accumulators = new LinkedHashMap<>();

        BigDecimal marketDollarVol1d = BigDecimal.ZERO;
        BigDecimal marketDollarVol5d = BigDecimal.ZERO;
        BigDecimal marketDollarVol20d = BigDecimal.ZERO;
        int marketTotalStockCount = 0;
        int classifiedStockCount = 0;

        Instant historyWindowStart = calendar.getSessionOpenAt(date20Start);
        Map<LocalDate, Instant> sessionCloseCache = new HashMap<>();
        for (int i = Math.max(0, totalSessions - 20); i < totalSessions; i++) {
            LocalDate d = sessions.get(i);
            sessionCloseCache.put(d, calendar.getSessionCloseAt(d));
        }

        for (List<ActiveStockWithStatus> batch : Lists.partition(universe, BATCH_SIZE)) {
            List<String> batchFigis = batch.stream().map(row -> row.stock().getFigi()).toList();
            Map<String, StockStatus> statusByFigi = batch.stream()
                    .collect(Collectors.toMap(row -> row.stock().getFigi(), ActiveStockWithStatus::status, (a, b) -> a));
            Map<String, List<QuoteBarRow>> quotesByFigi = new HashMap<>();
            for (QuoteBarRow bar : quotes.findQuoteBarsOrdered(batchFigis, loadFrom, tradeDate)) {
                quotesByFigi.computeIfAbsent(bar.figi(), k -> new ArrayList<>()).add(bar);
            }
            Map<String, List<StockSplit>> splitsByFigi = new HashMap<>();
            for (StockSplit sp : splits.findByFigiInOrderByExecutionDateAsc(batchFigis)) {
                splitsByFigi.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
            }
            Map<String, List<StockIndustryHistory>> historyByFigi = new HashMap<>();
            for (StockIndustryHistory h : industryHistories.findOverlappingByFigis(batchFigis, historyWindowStart, cutoff)) {
                historyByFigi.computeIfAbsent(h.getFigi(), k -> new ArrayList<>()).add(h);
            }

            for (ActiveStockWithStatus item : batch) {
                String figi = item.stock().getFigi();
                List<QuoteBarRow> stockBars = quotesByFigi.get(figi);
                if (stockBars == null || stockBars.isEmpty()) {
                    continue;
                }
                List<StockIndustryHistory> stockIndHistory = historyByFigi.getOrDefault(figi, List.of());
                Instant tradeDateCutoff = sessionCloseCache.getOrDefault(tradeDate, cutoff);
                if (tradeDateCutoff.isAfter(cutoff)) {
                    tradeDateCutoff = cutoff;
                }
                StockIndustry todayInd = resolveIndustryAt(stockIndHistory, tradeDateCutoff);
                boolean hasValidTradeDateBar = false;
                BigDecimal latestClose = null;

                for (QuoteBarRow bar : stockBars) {
                    LocalDate qDate = bar.quoteDate();
                    if (!QuoteNormalizer.validOhlcv(bar.openPrice(), bar.highPrice(), bar.lowPrice(), bar.closePrice(), bar.volume())) {
                        continue;
                    }
                    if (tradeDate.equals(qDate)) {
                        hasValidTradeDateBar = true;
                        latestClose = bar.closePrice();
                    }
                    if (qDate.isBefore(date20Start) || bar.volume() == null || bar.volume().signum() <= 0) {
                        continue;
                    }
                    BigDecimal dollarVol = bar.closePrice().multiply(bar.volume(), MC);
                    BigDecimal mfm = moneyFlowMultiplier(bar.highPrice(), bar.lowPrice(), bar.closePrice());
                    BigDecimal mfv = mfm.multiply(dollarVol, MC);

                    marketDollarVol20d = marketDollarVol20d.add(dollarVol, MC);
                    boolean in5d = !qDate.isBefore(date5Start);
                    boolean is1d = tradeDate.equals(qDate);
                    if (in5d) marketDollarVol5d = marketDollarVol5d.add(dollarVol, MC);
                    if (is1d) marketDollarVol1d = marketDollarVol1d.add(dollarVol, MC);

                    Instant barCutoff = sessionCloseCache.getOrDefault(qDate, cutoff);
                    if (barCutoff.isAfter(cutoff)) {
                        barCutoff = cutoff;
                    }
                    StockIndustry barInd = resolveIndustryAt(stockIndHistory, barCutoff);
                    if (barInd != null && barInd.getCode() != null) {
                        String code = barInd.getCode();
                        String name = industryNames.getOrDefault(code, barInd.getIndustry() != null ? barInd.getIndustry() : code);
                        IndustryAccumulator acc = accumulators.computeIfAbsent(code, k -> new IndustryAccumulator(code, name));
                        acc.dollarVol20d = acc.dollarVol20d.add(dollarVol, MC);
                        acc.mfv20d = acc.mfv20d.add(mfv, MC);
                        if (in5d) {
                            acc.dollarVol5d = acc.dollarVol5d.add(dollarVol, MC);
                            acc.mfv5d = acc.mfv5d.add(mfv, MC);
                        }
                        if (is1d) {
                            acc.dollarVol1d = acc.dollarVol1d.add(dollarVol, MC);
                            acc.stockCount++;
                        }
                    }
                }

                if (!hasValidTradeDateBar || latestClose == null) {
                    continue;
                }
                marketTotalStockCount++;
                if (todayInd == null || todayInd.getCode() == null) {
                    continue;
                }
                classifiedStockCount++;

                String todayCode = todayInd.getCode();
                String todayName = industryNames.getOrDefault(todayCode, todayInd.getIndustry() != null ? todayInd.getIndustry() : todayCode);
                IndustryAccumulator todayAcc = accumulators.computeIfAbsent(todayCode, k -> new IndustryAccumulator(todayCode, todayName));

                StockStatus status = statusByFigi.get(figi);
                if (!hasValidSplitCoverageThrough(status, tradeDate)) {
                    continue;
                }
                List<StockSplit> stockSplits = splitsByFigi.getOrDefault(figi, List.of());

                // 拆股對齊收盤價序列，計算 SMA50 / SMA200 廣度與 20D / 60D 完整交易日窗口報酬
                BigDecimal sum50 = BigDecimal.ZERO;
                int count50 = 0;
                BigDecimal sum200 = BigDecimal.ZERO;
                int count200 = 0;
                int countRet20 = 0;
                int countRet60 = 0;
                BigDecimal adjBase20 = null;
                BigDecimal adjBase60 = null;

                for (QuoteBarRow bar : stockBars) {
                    if (!QuoteNormalizer.validOhlcv(bar.openPrice(), bar.highPrice(), bar.lowPrice(), bar.closePrice(), bar.volume())) {
                        continue;
                    }
                    LocalDate d = bar.quoteDate();
                    if (ret20Window.contains(d)) {
                        countRet20++;
                    }
                    if (ret60Window.contains(d)) {
                        countRet60++;
                    }
                    boolean need50 = sma50Window.contains(d);
                    boolean need200 = sma200Window.contains(d);
                    boolean needRet20 = date20RetBase != null && date20RetBase.equals(d);
                    boolean needRet60 = date60RetBase != null && date60RetBase.equals(d);
                    if (!need50 && !need200 && !needRet20 && !needRet60) {
                        continue;
                    }
                    BigDecimal adjClose = bar.closePrice().multiply(StockSplitCalculator.priceFactor(stockSplits, d, tradeDate), MC);
                    if (need50) {
                        sum50 = sum50.add(adjClose, MC);
                        count50++;
                    }
                    if (need200) {
                        sum200 = sum200.add(adjClose, MC);
                        count200++;
                    }
                    if (needRet20) adjBase20 = adjClose;
                    if (needRet60) adjBase60 = adjClose;
                }

                if (date50Start != null && count50 == REQUIRED_SMA50 && !status.getSplitFromDate().isAfter(date50Start)) {
                    BigDecimal sma50 = sum50.divide(BigDecimal.valueOf(REQUIRED_SMA50), MC);
                    todayAcc.sma50Total++;
                    if (latestClose.compareTo(sma50) >= 0) {
                        todayAcc.aboveSma50Count++;
                    }
                }
                if (date200Start != null && count200 == REQUIRED_SMA200 && !status.getSplitFromDate().isAfter(date200Start)) {
                    BigDecimal sma200 = sum200.divide(BigDecimal.valueOf(REQUIRED_SMA200), MC);
                    todayAcc.sma200Total++;
                    if (latestClose.compareTo(sma200) >= 0) {
                        todayAcc.aboveSma200Count++;
                    }
                }
                if (date20RetBase != null && countRet20 == RETURN_20D + 1
                        && adjBase20 != null && adjBase20.signum() > 0 && !status.getSplitFromDate().isAfter(date20RetBase)) {
                    todayAcc.returns20d.add(latestClose.divide(adjBase20, MC).subtract(BigDecimal.ONE, MC));
                }
                if (date60RetBase != null && countRet60 == RETURN_60D + 1
                        && adjBase60 != null && adjBase60.signum() > 0 && !status.getSplitFromDate().isAfter(date60RetBase)) {
                    todayAcc.returns60d.add(latestClose.divide(adjBase60, MC).subtract(BigDecimal.ONE, MC));
                }
            }
        }

        BigDecimal coveragePct = marketTotalStockCount == 0 ? null
                : BigDecimal.valueOf(classifiedStockCount).divide(BigDecimal.valueOf(marketTotalStockCount), 6, RoundingMode.HALF_UP);

        List<SectorMoneyFlowDaily> rows = new ArrayList<>(accumulators.size());
        for (IndustryAccumulator acc : accumulators.values()) {
            BigDecimal share1d = safeRatio(acc.dollarVol1d, marketDollarVol1d, 6);
            BigDecimal share5d = safeRatio(acc.dollarVol5d, marketDollarVol5d, 6);
            BigDecimal share20d = safeRatio(acc.dollarVol20d, marketDollarVol20d, 6);
            BigDecimal shareDelta = share5d != null && share20d != null
                    ? share5d.subtract(share20d, MC).setScale(6, RoundingMode.HALF_UP) : null;
            BigDecimal cmf5d = safeRatio(acc.mfv5d, acc.dollarVol5d, 6);
            BigDecimal cmf20d = safeRatio(acc.mfv20d, acc.dollarVol20d, 6);
            BigDecimal breadth50 = acc.sma50Total == 0 ? null
                    : BigDecimal.valueOf(acc.aboveSma50Count).divide(BigDecimal.valueOf(acc.sma50Total), 6, RoundingMode.HALF_UP);
            BigDecimal breadth200 = acc.sma200Total == 0 ? null
                    : BigDecimal.valueOf(acc.aboveSma200Count).divide(BigDecimal.valueOf(acc.sma200Total), 6, RoundingMode.HALF_UP);

            SectorMoneyFlowDaily entity = new SectorMoneyFlowDaily();
            entity.setTradeDate(tradeDate);
            entity.setIndustryCode(acc.code);
            entity.setIndustryName(acc.name);
            entity.setStockCount(acc.stockCount);
            entity.setMarketTotalStockCount(marketTotalStockCount);
            entity.setClassifiedCoveragePct(coveragePct);
            entity.setDollarVolume1d(acc.dollarVol1d.setScale(2, RoundingMode.HALF_UP));
            entity.setDollarVolume5d(acc.dollarVol5d.setScale(2, RoundingMode.HALF_UP));
            entity.setDollarVolume20d(acc.dollarVol20d.setScale(2, RoundingMode.HALF_UP));
            entity.setMarketVolumeShare1d(share1d);
            entity.setMarketVolumeShare5d(share5d);
            entity.setMarketVolumeShare20d(share20d);
            entity.setVolumeShareDelta5dVs20d(shareDelta);
            entity.setCmf5d(cmf5d);
            entity.setCmf20d(cmf20d);
            entity.setBreadthAboveSma50Count(acc.aboveSma50Count);
            entity.setBreadthSma50Total(acc.sma50Total);
            entity.setBreadthAboveSma50Pct(breadth50);
            entity.setBreadthAboveSma200Count(acc.aboveSma200Count);
            entity.setBreadthSma200Total(acc.sma200Total);
            entity.setBreadthAboveSma200Pct(breadth200);
            entity.setReturn20dMedian(median(acc.returns20d));
            entity.setReturn20dCount(acc.returns20d.size());
            entity.setReturn60dMedian(median(acc.returns60d));
            entity.setReturn60dCount(acc.returns60d.size());
            entity.setFlowState(classifyFlowState(cmf5d, cmf20d));
            entity.setCalculatedAt(calculatedAt);
            rows.add(entity);
        }

        transactions.executeWithoutResult(tx -> {
            sectorMoneyFlows.deleteByTradeDate(tradeDate);
            sectorMoneyFlows.flush();
            for (SectorMoneyFlowDaily row : rows) {
                entityManager.persist(row);
            }
            entityManager.flush();
        });

        log.info("Sector money flow refreshed | tradeDate={} industries={} marketStocks={} classified={}",
                tradeDate, rows.size(), marketTotalStockCount, classifiedStockCount);
        return rows;
    }

    /**
     * 供個股分析報告組裝「個股 vs 同業中位數 vs SPY 20D/60D 相對強弱標籤」。
     * 預設 O(1) 讀取同日全產業快照；若存在自訂對標名單且為最新日查詢，則按自訂對標 FIGI 批次計算中位數。
     */
    @Transactional(readOnly = true)
    public PeerRelativeStrengthDto evaluatePeerRelativeStrength(
            String figi,
            String industryCode,
            LocalDate tradeDate,
            boolean isHistoricalQuery,
            RelativeStrengthDto relativeStrength) {
        if (tradeDate == null || relativeStrength == null) {
            return null;
        }
        RelativeStrengthWindowDto win20 = extractWindow(relativeStrength, 20);
        RelativeStrengthWindowDto win60 = extractWindow(relativeStrength, 60);
        BigDecimal stockRet20 = win20 == null ? null : scale6(win20.stockReturn());
        BigDecimal spyRet20 = win20 == null ? null : scale6(win20.benchmarkReturn());
        BigDecimal stockRet60 = win60 == null ? null : scale6(win60.stockReturn());
        BigDecimal spyRet60 = win60 == null ? null : scale6(win60.benchmarkReturn());

        // 嚴格只取同日產業快照，禁止跨日回退拿舊日產業報酬與當日個股／SPY 相減
        Optional<SectorMoneyFlowDaily> sectorSnap = industryCode == null || industryCode.isBlank()
                ? Optional.empty()
                : sectorMoneyFlows.findByTradeDateAndIndustryCode(tradeDate, industryCode);

        String peerBasis = PeerRelativeStrengthDto.BASIS_INDUSTRY_MEDIAN;
        BigDecimal peerRet20 = sectorSnap.map(SectorMoneyFlowDaily::getReturn20dMedian).orElse(null);
        Integer peerCnt20 = sectorSnap.map(SectorMoneyFlowDaily::getReturn20dCount).orElse(null);
        BigDecimal peerRet60 = sectorSnap.map(SectorMoneyFlowDaily::getReturn60dMedian).orElse(null);
        Integer peerCnt60 = sectorSnap.map(SectorMoneyFlowDaily::getReturn60dCount).orElse(null);

        if (!isHistoricalQuery && figi != null && !figi.isBlank()) {
            List<StockCustomPeer> configuredPeers = customPeers.findByFigiOrderBySortOrderAsc(figi);
            if (configuredPeers != null && configuredPeers.size() >= 3) {
                CustomPeerReturns custom = computeCustomPeerReturns(
                        configuredPeers.stream().map(StockCustomPeer::getPeerFigi).toList(), tradeDate);
                if (custom.count20() >= 3 || custom.count60() >= 3) {
                    peerBasis = PeerRelativeStrengthDto.BASIS_CUSTOM_PEERS_MEDIAN;
                    // 20D 與 60D 兩個窗口各自獨立判定滿 3 檔；不足 3 檔者留空，避免以 1-2 檔湊數或混口徑
                    peerRet20 = custom.count20() >= 3 ? custom.median20() : null;
                    peerCnt20 = custom.count20();
                    peerRet60 = custom.count60() >= 3 ? custom.median60() : null;
                    peerCnt60 = custom.count60();
                }
            }
        }

        if (peerRet20 == null && peerRet60 == null && sectorSnap.isEmpty()) {
            return null;
        }

        BigDecimal stockVsPeer20 = stockRet20 != null && peerRet20 != null ? scale6(stockRet20.subtract(peerRet20, MC)) : null;
        BigDecimal peerVsSpy20 = peerRet20 != null && spyRet20 != null ? scale6(peerRet20.subtract(spyRet20, MC)) : null;
        BigDecimal stockVsPeer60 = stockRet60 != null && peerRet60 != null ? scale6(stockRet60.subtract(peerRet60, MC)) : null;
        BigDecimal peerVsSpy60 = peerRet60 != null && spyRet60 != null ? scale6(peerRet60.subtract(spyRet60, MC)) : null;
        String label = classifyPeerRelativeLabel(stockRet20, peerRet20, spyRet20, stockRet60, peerRet60, spyRet60);

        return new PeerRelativeStrengthDto(
                peerBasis,
                industryCode,
                sectorSnap.map(SectorMoneyFlowDaily::getIndustryName).orElse(null),
                sectorSnap.map(SectorMoneyFlowDaily::getTradeDate).orElse(tradeDate),
                stockRet20,
                peerRet20,
                spyRet20,
                stockVsPeer20,
                peerVsSpy20,
                peerCnt20,
                stockRet60,
                peerRet60,
                spyRet60,
                stockVsPeer60,
                peerVsSpy60,
                peerCnt60,
                sectorSnap.map(SectorMoneyFlowDaily::getCmf20d).orElse(null),
                sectorSnap.map(SectorMoneyFlowDaily::getFlowState).orElse(null),
                sectorSnap.map(SectorMoneyFlowDaily::getBreadthAboveSma50Pct).orElse(null),
                sectorSnap.map(SectorMoneyFlowDaily::getBreadthAboveSma200Pct).orElse(null),
                label
        );
    }

    private record CustomPeerReturns(BigDecimal median20, int count20, BigDecimal median60, int count60) {}

    private CustomPeerReturns computeCustomPeerReturns(Collection<String> peerFigis, LocalDate tradeDate) {
        List<LocalDate> sessions = calendar.getTradingDays(tradeDate.minusDays(140), tradeDate);
        if (sessions.isEmpty() || !sessions.getLast().equals(tradeDate)) {
            return new CustomPeerReturns(null, 0, null, 0);
        }
        int total = sessions.size();
        LocalDate base20 = total > RETURN_20D ? sessions.get(total - 1 - RETURN_20D) : null;
        LocalDate base60 = total > RETURN_60D ? sessions.get(total - 1 - RETURN_60D) : null;
        LocalDate loadFrom = base60 != null ? base60 : (base20 != null ? base20 : tradeDate);
        Set<LocalDate> ret20Window = base20 == null ? Set.of()
                : new HashSet<>(sessions.subList(total - 1 - RETURN_20D, total));
        Set<LocalDate> ret60Window = base60 == null ? Set.of()
                : new HashSet<>(sessions.subList(total - 1 - RETURN_60D, total));

        Map<String, StockStatus> statusMap = statuses.findAllById(peerFigis).stream()
                .collect(Collectors.toMap(StockStatus::getFigi, s -> s, (a, b) -> a));
        Map<String, List<StockSplit>> splitMap = new HashMap<>();
        for (StockSplit sp : splits.findByFigiInOrderByExecutionDateAsc(peerFigis)) {
            splitMap.computeIfAbsent(sp.getFigi(), k -> new ArrayList<>()).add(sp);
        }
        Map<String, Map<LocalDate, BigDecimal>> closeByFigi = new HashMap<>();
        Map<String, Integer> count20ByFigi = new HashMap<>();
        Map<String, Integer> count60ByFigi = new HashMap<>();
        for (QuoteBarRow bar : quotes.findQuoteBarsOrdered(peerFigis, loadFrom, tradeDate)) {
            if (!QuoteNormalizer.validOhlcv(bar.openPrice(), bar.highPrice(), bar.lowPrice(), bar.closePrice(), bar.volume())) {
                continue;
            }
            LocalDate qd = bar.quoteDate();
            if (ret20Window.contains(qd)) {
                count20ByFigi.merge(bar.figi(), 1, Integer::sum);
            }
            if (ret60Window.contains(qd)) {
                count60ByFigi.merge(bar.figi(), 1, Integer::sum);
            }
            if (tradeDate.equals(qd) || (base20 != null && base20.equals(qd))
                    || (base60 != null && base60.equals(qd))) {
                closeByFigi.computeIfAbsent(bar.figi(), k -> new HashMap<>()).put(qd, bar.closePrice());
            }
        }
        List<BigDecimal> rets20 = new ArrayList<>();
        List<BigDecimal> rets60 = new ArrayList<>();
        for (String pFigi : peerFigis) {
            StockStatus st = statusMap.get(pFigi);
            if (!hasValidSplitCoverageThrough(st, tradeDate)) {
                continue;
            }
            Map<LocalDate, BigDecimal> closes = closeByFigi.get(pFigi);
            if (closes == null || closes.get(tradeDate) == null) {
                continue;
            }
            BigDecimal closeD = closes.get(tradeDate);
            List<StockSplit> spList = splitMap.getOrDefault(pFigi, List.of());
            if (base20 != null && count20ByFigi.getOrDefault(pFigi, 0) == RETURN_20D + 1
                    && closes.get(base20) != null && !st.getSplitFromDate().isAfter(base20)) {
                BigDecimal adj20 = closes.get(base20).multiply(StockSplitCalculator.priceFactor(spList, base20, tradeDate), MC);
                if (adj20.signum() > 0) rets20.add(closeD.divide(adj20, MC).subtract(BigDecimal.ONE, MC));
            }
            if (base60 != null && count60ByFigi.getOrDefault(pFigi, 0) == RETURN_60D + 1
                    && closes.get(base60) != null && !st.getSplitFromDate().isAfter(base60)) {
                BigDecimal adj60 = closes.get(base60).multiply(StockSplitCalculator.priceFactor(spList, base60, tradeDate), MC);
                if (adj60.signum() > 0) rets60.add(closeD.divide(adj60, MC).subtract(BigDecimal.ONE, MC));
            }
        }
        return new CustomPeerReturns(median(rets20), rets20.size(), median(rets60), rets60.size());
    }

    private RelativeStrengthWindowDto extractWindow(RelativeStrengthDto rs, int days) {
        if (rs == null || rs.windows() == null) return null;
        MetricResult<RelativeStrengthWindowDto> metric = rs.windows().get(days);
        return metric != null && metric.status() == MetricStatus.AVAILABLE ? metric.value() : null;
    }

    private StockIndustry resolveIndustryAt(List<StockIndustryHistory> history, Instant asOf) {
        if (history == null || history.isEmpty() || asOf == null) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            StockIndustryHistory h = history.get(i);
            if (h.getObservedFrom() != null && !h.getObservedFrom().isAfter(asOf)
                    && (h.getObservedTo() == null || h.getObservedTo().isAfter(asOf))) {
                return h.getIndustry();
            }
        }
        return null;
    }

    private boolean hasValidSplitCoverageThrough(StockStatus status, LocalDate tradeDate) {
        if (status == null || status.getSplitFromDate() == null || status.getSplitDate() == null) {
            return false;
        }
        if (status.getSplitDate().isBefore(tradeDate)) {
            return false;
        }
        String msg = status.getSplitMessage();
        return msg == null || msg.isBlank() || msg.toUpperCase(Locale.ROOT).startsWith("WARNING:");
    }

    private BigDecimal safeRatio(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_UP);
    }

    private BigDecimal scale6(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        BigDecimal result = sorted.size() % 2 == 1
                ? sorted.get(mid)
                : sorted.get(mid - 1).add(sorted.get(mid), MC).divide(BigDecimal.valueOf(2), MC);
        return result.setScale(6, RoundingMode.HALF_UP);
    }
}
