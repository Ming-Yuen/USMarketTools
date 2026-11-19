package com.ykm.stock.analytics.service.analysis;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.PeerRelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RelativeStrengthDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.RelativeStrengthWindowDto;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.Stock;
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
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 驗收板塊量價資金流（CMF 防 high==low 除以零、拆股日成交額與均線廣度對齊、幣別隔離與個股同業強弱標籤）。
 */
class SectorMoneyFlowServiceTests {
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 17);
    private static final Instant NOW = Instant.parse("2026-11-17T22:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final StockIndustryRepository industries = mock(StockIndustryRepository.class);
    private final StockIndustryHistoryRepository industryHistories = mock(StockIndustryHistoryRepository.class);
    private final StockCustomPeerRepository customPeers = mock(StockCustomPeerRepository.class);
    private final SectorMoneyFlowDailyRepository sectorMoneyFlows = mock(SectorMoneyFlowDailyRepository.class);
    private final MarketStatusService calendar = new MarketStatusService(null);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private SectorMoneyFlowService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        service = new SectorMoneyFlowService(
                stocks, statuses, quotes, splits, industries, industryHistories, customPeers,
                sectorMoneyFlows, calendar, clock, tx, entityManager);
        when(clock.now()).thenReturn(NOW);
    }

    @Test
    void moneyFlowMultiplierHandlesFlatBarWithoutDivisionByZero() {
        BigDecimal flatMfm = SectorMoneyFlowService.moneyFlowMultiplier(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"));
        assertThat(flatMfm).isEqualByComparingTo("0");

        BigDecimal closeAtHigh = SectorMoneyFlowService.moneyFlowMultiplier(
                new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("110"));
        assertThat(closeAtHigh).isEqualByComparingTo("1");

        BigDecimal closeAtLow = SectorMoneyFlowService.moneyFlowMultiplier(
                new BigDecimal("110"), new BigDecimal("100"), new BigDecimal("100"));
        assertThat(closeAtLow).isEqualByComparingTo("-1");
    }

    @Test
    void refreshDailyComputesSplitAlignedBreadthAndFlatBarCmfAndExcludesNonUsd() {
        List<LocalDate> sessions = calendar.getTradingDays(TRADE_DATE.minusDays(365), TRADE_DATE);
        int total = sessions.size();
        List<LocalDate> last200 = sessions.subList(total - 200, total);
        LocalDate splitExecDate = last200.get(195);

        StockIndustry semi = new StockIndustry();
        semi.setCode("I41");
        semi.setIndustry("Semiconductors");
        when(industries.findAll()).thenReturn(List.of(semi));

        // 1) F_SPLIT：發生 1 拆 10，拆股前股價 100（拆後等價 10），拆股後漲至 11 -> 實質站上 SMA50 與 SMA200 且 20D 報酬為 +10%
        ActiveStockWithStatus splitStock = stockWithStatus("F_SPLIT", "SPLT", "USD", last200.getFirst(), TRADE_DATE);
        // 2) F_FLAT：全平價棒（high == low == close = 50）驗證不除以零，且拉低 CMF 權重
        ActiveStockWithStatus flatStock = stockWithStatus("F_FLAT", "FLAT", "USD", last200.getFirst(), TRADE_DATE);
        // 3) F_CAD：非 USD 報價，不得混入 USD 成交額與廣度
        ActiveStockWithStatus nonUsdStock = stockWithStatus("F_CAD", "CADX", "CAD", last200.getFirst(), TRADE_DATE);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(splitStock, flatStock, nonUsdStock));

        StockSplit split = new StockSplit();
        split.setSourceEventId("SPLT-10-1");
        split.setFigi("F_SPLIT");
        split.setExecutionDate(splitExecDate);
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(BigDecimal.TEN);
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));

        List<QuoteBarRow> allBars = new ArrayList<>();
        for (LocalDate d : last200) {
            if (d.isBefore(splitExecDate)) {
                // 拆股前：close=100, high=100, low=98 -> MFM=+1, volume=1000 -> dollarVol=100,000
                allBars.add(new QuoteBarRow("F_SPLIT", d, new BigDecimal("99"), new BigDecimal("100"),
                        new BigDecimal("98"), new BigDecimal("100"), new BigDecimal("1000"), NOW));
            } else {
                // 拆股後：close=11, high=11, low=10 -> MFM=+1, volume=10000 -> dollarVol=110,000
                allBars.add(new QuoteBarRow("F_SPLIT", d, new BigDecimal("10.5"), new BigDecimal("11"),
                        new BigDecimal("10"), new BigDecimal("11"), new BigDecimal("10000"), NOW));
            }
            // 平價棒：high == low == close = 50, volume=2000 -> dollarVol=100,000, MFM=0
            allBars.add(new QuoteBarRow("F_FLAT", d, new BigDecimal("50"), new BigDecimal("50"),
                    new BigDecimal("50"), new BigDecimal("50"), new BigDecimal("2000"), NOW));
        }
        when(quotes.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(allBars);

        StockIndustryHistory h1 = new StockIndustryHistory();
        h1.setFigi("F_SPLIT");
        h1.setIndustry(semi);
        h1.setObservedFrom(Instant.parse("2020-01-01T00:00:00Z"));
        StockIndustryHistory h2 = new StockIndustryHistory();
        h2.setFigi("F_FLAT");
        h2.setIndustry(semi);
        h2.setObservedFrom(Instant.parse("2020-01-01T00:00:00Z"));
        when(industryHistories.findOverlappingByFigis(anyCollection(), any(), any())).thenReturn(List.of(h1, h2));

        List<SectorMoneyFlowDaily> rows = service.refreshDaily(TRADE_DATE, NOW);
        assertThat(rows).hasSize(1);
        SectorMoneyFlowDaily semiRow = rows.getFirst();
        assertThat(semiRow.getIndustryCode()).isEqualTo("I41");
        assertThat(semiRow.getStockCount()).isEqualTo(2);
        assertThat(semiRow.getMarketTotalStockCount()).isEqualTo(2);
        // 拆股對齊後 F_SPLIT (11 >= 10.025) 與 F_FLAT (50 >= 50) 雙雙站上 SMA50 與 SMA200
        assertThat(semiRow.getBreadthAboveSma50Count()).isEqualTo(2);
        assertThat(semiRow.getBreadthSma50Total()).isEqualTo(2);
        assertThat(semiRow.getBreadthAboveSma200Count()).isEqualTo(2);
        assertThat(semiRow.getBreadthSma200Total()).isEqualTo(2);
        // 5D CMF：F_SPLIT 110,000 * (+1) + F_FLAT 100,000 * 0 = 110,000 / 210,000 = 0.523810
        assertThat(semiRow.getCmf5d()).isEqualByComparingTo("0.523810");
        assertThat(semiRow.getCmf20d()).isPositive();
        assertThat(semiRow.getFlowState()).isEqualTo(SectorMoneyFlowDaily.FLOW_INFLOW);
        // 20D 報酬中位數：F_SPLIT (+10%) 與 F_FLAT (0%) 中位數 = +5%
        assertThat(semiRow.getReturn20dMedian()).isEqualByComparingTo("0.050000");
        assertThat(semiRow.getReturn20dCount()).isEqualTo(2);
    }

    @Test
    void classifyPeerRelativeLabelCoversLeadingLaggingAndWeakSector() {
        assertThat(SectorMoneyFlowService.classifyPeerRelativeLabel(
                new BigDecimal("0.12"), new BigDecimal("0.06"), new BigDecimal("0.02"), null, null, null))
                .isEqualTo(PeerRelativeStrengthDto.LABEL_SECTOR_STRONG_STOCK_LEADING);

        assertThat(SectorMoneyFlowService.classifyPeerRelativeLabel(
                new BigDecimal("0.02"), new BigDecimal("0.07"), new BigDecimal("0.02"), null, null, null))
                .isEqualTo(PeerRelativeStrengthDto.LABEL_SECTOR_STRONG_STOCK_LAGGING);

        assertThat(SectorMoneyFlowService.classifyPeerRelativeLabel(
                new BigDecimal("-0.08"), new BigDecimal("-0.05"), new BigDecimal("0.01"), null, null, null))
                .isEqualTo(PeerRelativeStrengthDto.LABEL_SECTOR_WEAK_COLLECTIVE_DECLINE);

        assertThat(SectorMoneyFlowService.classifyPeerRelativeLabel(
                new BigDecimal("0.04"), new BigDecimal("-0.03"), new BigDecimal("0.01"), null, null, null))
                .isEqualTo(PeerRelativeStrengthDto.LABEL_SECTOR_WEAK_STOCK_OUTPERFORMING);
    }

    @Test
    void evaluatePeerRelativeStrengthReadsIndustrySnapshotAndAttachesLabel() {
        SectorMoneyFlowDaily snap = new SectorMoneyFlowDaily();
        snap.setTradeDate(TRADE_DATE);
        snap.setIndustryCode("I41");
        snap.setIndustryName("Semiconductors");
        snap.setReturn20dMedian(new BigDecimal("0.080000"));
        snap.setReturn20dCount(45);
        snap.setReturn60dMedian(new BigDecimal("0.150000"));
        snap.setReturn60dCount(45);
        snap.setCmf20d(new BigDecimal("0.180000"));
        snap.setFlowState(SectorMoneyFlowDaily.FLOW_INFLOW);
        snap.setBreadthAboveSma50Pct(new BigDecimal("0.720000"));
        snap.setBreadthAboveSma200Pct(new BigDecimal("0.680000"));
        when(sectorMoneyFlows.findByTradeDateAndIndustryCode(TRADE_DATE, "I41")).thenReturn(Optional.of(snap));
        when(customPeers.findByFigiOrderBySortOrderAsc("F_NVDA")).thenReturn(List.of());

        Map<Integer, MetricResult<RelativeStrengthWindowDto>> windows = new LinkedHashMap<>();
        windows.put(20, MetricResult.available(new RelativeStrengthWindowDto(
                20, new BigDecimal("0.03"), new BigDecimal("0.02"), new BigDecimal("0.01"), new BigDecimal("0.0098"))));
        windows.put(60, MetricResult.available(new RelativeStrengthWindowDto(
                60, new BigDecimal("0.12"), new BigDecimal("0.05"), new BigDecimal("0.07"), new BigDecimal("0.0667"))));
        RelativeStrengthDto rs = new RelativeStrengthDto(
                "SPY", MetricResult.available(new BigDecimal("0.01")), MetricResult.available(new BigDecimal("0.07")),
                MetricResult.available(BigDecimal.ZERO), windows, MetricResult.available(BigDecimal.ONE), List.of());

        PeerRelativeStrengthDto dto = service.evaluatePeerRelativeStrength("F_NVDA", "I41", TRADE_DATE, false, rs);
        assertThat(dto).isNotNull();
        assertThat(dto.peerBasis()).isEqualTo(PeerRelativeStrengthDto.BASIS_INDUSTRY_MEDIAN);
        assertThat(dto.relativeLabelCode()).isEqualTo(PeerRelativeStrengthDto.LABEL_SECTOR_STRONG_STOCK_LAGGING);
        assertThat(dto.peerReturn20d()).isEqualByComparingTo("0.080000");
        assertThat(dto.stockVsPeer20d()).isEqualByComparingTo("-0.050000");
    }

    @Test
    void evaluatePeerRelativeStrengthDoesNotFallbackToStaleIndustryDate() {
        when(sectorMoneyFlows.findByTradeDateAndIndustryCode(TRADE_DATE, "I41")).thenReturn(Optional.empty());
        when(customPeers.findByFigiOrderBySortOrderAsc("F_NVDA")).thenReturn(List.of());

        Map<Integer, MetricResult<RelativeStrengthWindowDto>> windows = new LinkedHashMap<>();
        windows.put(20, MetricResult.available(new RelativeStrengthWindowDto(
                20, new BigDecimal("0.03"), new BigDecimal("0.02"), new BigDecimal("0.01"), new BigDecimal("0.0098"))));
        RelativeStrengthDto rs = new RelativeStrengthDto(
                "SPY", MetricResult.available(new BigDecimal("0.01")), null,
                MetricResult.available(BigDecimal.ZERO), windows, MetricResult.available(BigDecimal.ONE), List.of());

        PeerRelativeStrengthDto dto = service.evaluatePeerRelativeStrength("F_NVDA", "I41", TRADE_DATE, false, rs);
        assertThat(dto).isNull();
    }

    @Test
    void refreshDailyExcludesStockWithMidWindowGapFrom20dMedianReturn() {
        List<LocalDate> sessions = calendar.getTradingDays(TRADE_DATE.minusDays(365), TRADE_DATE);
        int total = sessions.size();
        List<LocalDate> last25 = sessions.subList(total - 25, total);
        LocalDate missingMidDate = last25.get(last25.size() - 5);

        StockIndustry semi = new StockIndustry();
        semi.setCode("I41");
        semi.setIndustry("Semiconductors");
        when(industries.findAll()).thenReturn(List.of(semi));

        ActiveStockWithStatus fullStock = stockWithStatus("F_FULL", "FULL", "USD", last25.getFirst(), TRADE_DATE);
        ActiveStockWithStatus gappedStock = stockWithStatus("F_GAPP", "GAPP", "USD", last25.getFirst(), TRADE_DATE);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(fullStock, gappedStock));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());

        List<QuoteBarRow> bars = new ArrayList<>();
        for (int i = 0; i < last25.size(); i++) {
            LocalDate d = last25.get(i);
            BigDecimal closeFull = d.equals(TRADE_DATE) ? new BigDecimal("110") : new BigDecimal("100");
            bars.add(new QuoteBarRow("F_FULL", d, new BigDecimal("100"), closeFull, new BigDecimal("100"), closeFull, new BigDecimal("1000"), NOW));
            if (!d.equals(missingMidDate)) {
                BigDecimal closeGap = d.equals(TRADE_DATE) ? new BigDecimal("200") : new BigDecimal("100");
                bars.add(new QuoteBarRow("F_GAPP", d, new BigDecimal("100"), closeGap, new BigDecimal("100"), closeGap, new BigDecimal("1000"), NOW));
            }
        }
        when(quotes.findQuoteBarsOrdered(anyCollection(), any(), any())).thenReturn(bars);

        StockIndustryHistory h1 = new StockIndustryHistory();
        h1.setFigi("F_FULL");
        h1.setIndustry(semi);
        h1.setObservedFrom(Instant.parse("2020-01-01T00:00:00Z"));
        StockIndustryHistory h2 = new StockIndustryHistory();
        h2.setFigi("F_GAPP");
        h2.setIndustry(semi);
        h2.setObservedFrom(Instant.parse("2020-01-01T00:00:00Z"));
        when(industryHistories.findOverlappingByFigis(anyCollection(), any(), any())).thenReturn(List.of(h1, h2));

        List<SectorMoneyFlowDaily> rows = service.refreshDaily(TRADE_DATE, NOW);
        assertThat(rows).hasSize(1);
        // F_GAPP 缺 1 根交易日（僅 20 根而非 21 根），必須排除出 20D 中位報酬 -> 僅 F_FULL (+10%) 入選
        assertThat(rows.getFirst().getReturn20dCount()).isEqualTo(1);
        assertThat(rows.getFirst().getReturn20dMedian()).isEqualByComparingTo("0.100000");
    }

    private ActiveStockWithStatus stockWithStatus(String figi, String symbol, String currency, LocalDate splitFrom, LocalDate splitDate) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setCurrency(currency);
        stock.setStockType("Common Stock");
        stock.setActiveStatus(ActiveStatus.ACTIVE);
        stock.setTracked(true);
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        status.setQuoteDate(splitDate);
        status.setSplitFromDate(splitFrom);
        status.setSplitDate(splitDate);
        return new ActiveStockWithStatus(stock, status);
    }
}
