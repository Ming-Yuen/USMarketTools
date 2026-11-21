package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockCustomPeer;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationLatestRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.StockValuationService.ReportValuationBundle;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValuationReferencePhase4Tests {
    private static final String FIGI = "BBG000B9XRY4";
    private static final LocalDate LATEST_DAY = LocalDate.of(2026, 11, 13);
    private static final Instant LATEST_CLOSE = Instant.parse("2026-11-13T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockShareObservationRepository shareObservations = mock(StockShareObservationRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final IndustryValuationConfigRepository industryConfigs = mock(IndustryValuationConfigRepository.class);
    private final StockFinancialPeriodRepository financialPeriods = mock(StockFinancialPeriodRepository.class);
    private final StockValuationLatestRepository latestValuations = mock(StockValuationLatestRepository.class);
    private final StockCustomPeerRepository customPeers = mock(StockCustomPeerRepository.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final SecIssuerHistory issuerHistory = new SecIssuerHistory("0000320193:0000320100:2020-01-01:0001193125-20-000001");
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private StockValuationService service;
    private Stock apple;
    private StockStatus status;

    @BeforeEach
    void setUp() {
        service = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, financialPeriods, latestValuations, customPeers,
                calendar, clock, issuerHistory, transactions, entityManager);
        when(clock.now()).thenReturn(NOW);
        when(calendar.getLastClosedTradingDay(NOW)).thenReturn(LATEST_DAY);
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getSessionOpenAt(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plusSeconds(14 * 3600 + 30 * 60);
        });
        when(calendar.getSessionCloseAt(any())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).plusSeconds(21 * 3600);
        });

        apple = new Stock();
        apple.setFigi(FIGI);
        apple.setSymbol("AAPL");
        apple.setCik("0000320193");
        apple.setSic("3571");
        apple.setCurrency("USD");
        apple.setStockType("Common Stock");
        apple.setActiveStatus(ActiveStatus.ACTIVE);
        apple.setTracked(true);

        status = new StockStatus();
        status.setFigi(FIGI);
        status.setQuoteDate(LATEST_DAY);
        when(statuses.findById(FIGI)).thenReturn(Optional.of(status));
        when(stocks.findMultiSecurityIssuers(anyCollection(), eq(ActiveStatus.ACTIVE))).thenReturn(Set.of());
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
        when(customPeers.findByFigiOrderBySortOrderAsc(any())).thenReturn(List.of());
    }

    @Test
    void singleSelectedPrimaryMetricRunsThroughFiveYearHistoryWithoutMonthByMonthSwitching() {
        StockFinancialPeriod pLoss = buildPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31),
                new BigDecimal("-2.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        StockFinancialPeriod pProfit = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("10.00"), new BigDecimal("600"), new BigDecimal("1200"), new BigDecimal("250"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(pLoss, pProfit));

        List<StockQuote> monthlyQuotes = new ArrayList<>();
        // 2024 年虧損月份：PE 為 NON_POSITIVE_EPS，EV/EBITDA 與 PS 可用，絕不可被混入 PE 分位數樣本
        monthlyQuotes.add(quote(LocalDate.of(2024, 6, 28), "150"));
        monthlyQuotes.add(quote(LocalDate.of(2024, 9, 30), "160"));
        // 2025–2026 年 10 個月末：EPS = 10，收盤價 100..280 -> PE = 10, 12, 14, 16, 18, 20, 22, 24, 26, 28
        LocalDate[] profitMonths = {
                LocalDate.of(2025, 2, 28), LocalDate.of(2025, 4, 30), LocalDate.of(2025, 6, 30),
                LocalDate.of(2025, 8, 29), LocalDate.of(2025, 10, 31), LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 9, 30), LATEST_DAY
        };
        int price = 100;
        for (LocalDate d : profitMonths) {
            monthlyQuotes.add(quote(d, String.valueOf(price)));
            price += 20;
        }
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY))).thenReturn(monthlyQuotes);

        ReportValuationBundle bundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        ValuationReferenceDto hist = bundle.valuation().historicalReference();

        assertThat(bundle.valuation().primaryMetric()).isEqualTo("PE");
        assertThat(hist.status()).isEqualTo("AVAILABLE");
        assertThat(hist.metricCode()).isEqualTo("PE");
        // 僅計入 10 個正 PE 月份，2 個負 EPS 月份絕不切換 EV/EBITDA 混入，並完整揭露視窗覆蓋與排除月數
        assertThat(hist.sampleCount()).isEqualTo(10);
        assertThat(hist.windowSampleCount()).isEqualTo(12);
        assertThat(hist.excludedSampleCount()).isEqualTo(2);
        assertThat(hist.sampleStartDate()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(hist.sampleEndDate()).isEqualTo(LATEST_DAY);
        // [10, 12, 14, 16, 18, 20, 22, 24, 26, 28]: P20 = 13.6000, P50 = 19.0000, P80 = 24.4000
        assertThat(hist.multipleLow()).isEqualByComparingTo("13.6000");
        assertThat(hist.multipleMedian()).isEqualByComparingTo("19.0000");
        assertThat(hist.multipleHigh()).isEqualByComparingTo("24.4000");
        // 當日 EPS = 10.00 -> priceLow = 136.0000, priceMedian = 190.0000, priceHigh = 244.0000; 當期 PE = 28 -> mid-rank 95.00
        assertThat(hist.priceLow()).isEqualByComparingTo("136.0000");
        assertThat(hist.priceMedian()).isEqualByComparingTo("190.0000");
        assertThat(hist.priceHigh()).isEqualByComparingTo("244.0000");
        assertThat(hist.currentMultiple()).isEqualByComparingTo("28.0000");
        assertThat(hist.currentPercentile()).isEqualByComparingTo("95.00");
    }

    @Test
    void nonFinancialLossAtTargetDayFallsBackToEvEbitdaWhileBankStaysOnPbAndNeverUsesEvEbitda() {
        // 1) 一般營運企業當期虧損（EPS = -1.50），但 EBITDA = 200 正值 -> 自動選 EV_EBITDA 貫穿歷史並換算股價
        StockFinancialPeriod opPeriod = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("-1.50"), new BigDecimal("400"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        // EV adjustment = +100 (淨負債 100)，shares = 100，EBITDA = 200
        opPeriod.setEvAdjustment(new BigDecimal("100"));
        opPeriod.setEvAdjustmentStatus(MetricStatus.AVAILABLE.name());
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(opPeriod));

        List<StockQuote> quotes6m = List.of(
                quote(LocalDate.of(2026, 6, 30), "10"), // mktCap 1000, EV 1100, EV/EBITDA 5.5
                quote(LocalDate.of(2026, 7, 31), "12"), // EV 1300 -> 6.5
                quote(LocalDate.of(2026, 8, 31), "14"), // EV 1500 -> 7.5
                quote(LocalDate.of(2026, 9, 30), "16"), // EV 1700 -> 8.5
                quote(LocalDate.of(2026, 10, 30), "18"), // EV 1900 -> 9.5
                quote(LATEST_DAY, "20")                  // EV 2100 -> 10.5
        );
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY))).thenReturn(quotes6m);

        ReportValuationBundle opBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        assertThat(opBundle.valuation().primaryMetric()).isEqualTo("EV_EBITDA");
        assertThat(opBundle.valuation().historicalReference().metricCode()).isEqualTo("EV_EBITDA");
        assertThat(opBundle.valuation().historicalReference().status()).isEqualTo("AVAILABLE");
        // P20 of [5.5, 6.5, 7.5, 8.5, 9.5, 10.5] = 6.5 -> impliedMarketCap = 6.5*200 - 100 = 1200 -> priceLow = 12.0000
        assertThat(opBundle.valuation().historicalReference().multipleLow()).isEqualByComparingTo("6.5000");
        assertThat(opBundle.valuation().historicalReference().priceLow()).isEqualByComparingTo("12.0000");

        // 2) 銀行股（SIC 6021）即使未設 config 且當期虧損，固定選 PB，絕不退回 EV_EBITDA 或 PFCF
        Stock bank = new Stock();
        bank.setFigi("BANK_FIGI");
        bank.setSymbol("JPM");
        bank.setSic("6021");
        bank.setCurrency("USD");
        bank.setStockType("Common Stock");
        bank.setActiveStatus(ActiveStatus.ACTIVE);
        bank.setTracked(true);
        StockStatus bankStatus = new StockStatus();
        bankStatus.setFigi("BANK_FIGI");
        bankStatus.setQuoteDate(LATEST_DAY);
        when(statuses.findById("BANK_FIGI")).thenReturn(Optional.of(bankStatus));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc("BANK_FIGI")).thenReturn(List.of(opPeriod));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("BANK_FIGI"), any(), eq(LATEST_DAY)))
                .thenReturn(quotes6m.stream().map(q -> {
                    StockQuote bq = new StockQuote();
                    bq.setFigi("BANK_FIGI");
                    bq.setQuoteDate(q.getQuoteDate());
                    bq.setClosePrice(q.getClosePrice());
                    return bq;
                }).toList());

        ReportValuationBundle bankBundle = service.evaluateForReport(bank, LATEST_DAY, LATEST_CLOSE, NOW, "I_BANK");
        assertThat(bankBundle.valuation().modelCode()).isEqualTo("BANK");
        assertThat(bankBundle.valuation().primaryMetric()).isEqualTo("PB");
        assertThat(bankBundle.valuation().historicalReference().metricCode()).isEqualTo("PB");
        assertThat(bankBundle.valuation().historicalReference().status()).isEqualTo("AVAILABLE");
    }

    @Test
    void peerReferenceExcludesTargetIssuerIncludingPredecessorCikAndRequiresFiveExternalIssuers() {
        StockFinancialPeriod period = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("8.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(period));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "200")));

        // 4 家外部發行人（GOOGL/GOOG 同 CIK 去重）+ 目標股自身 AAPL + 掛前任舊 CIK (0000320100) 的兄弟股 -> 排除自身發行人後僅剩 4 家，必須為 INSUFFICIENT_DATA
        List<Object[]> fourExternalPlusSelfAndPredecessor = List.of(
                peerTuple("0001652044", "GOOGL", "15.0000"),
                peerTuple("0001652044", "GOOG", "17.0000"),
                peerTuple("0001326801", "META", "20.0000"),
                peerTuple("0001018724", "AMZN", "25.0000"),
                peerTuple("0001065280", "NFLX", "30.0000"),
                peerTuple("0000320193", FIGI, "35.0000"),
                peerTuple("0000320100", "AAPL_OLD_SIB", "99.0000")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I78", LATEST_DAY)).thenReturn(fourExternalPlusSelfAndPredecessor);

        ReportValuationBundle insufficient = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I78");
        assertThat(insufficient.valuation().peerReference().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(insufficient.valuation().peerReference().reasonCode()).isEqualTo("INDUSTRY_PEER_COMPANIES_INSUFFICIENT");

        // 補上第 5 家真正獨立的外部發行人 MSFT -> 排除自身與舊 CIK 兄弟後恰好 5 家有效外部發行人 [16, 20, 25, 30, 35]
        List<Object[]> fiveExternalIssuers = List.of(
                peerTuple("0001652044", "GOOGL", "15.0000"),
                peerTuple("0001652044", "GOOG", "17.0000"),
                peerTuple("0001326801", "META", "20.0000"),
                peerTuple("0001018724", "AMZN", "25.0000"),
                peerTuple("0001065280", "NFLX", "30.0000"),
                peerTuple("0000789019", "MSFT", "35.0000"),
                peerTuple("0000320193", FIGI, "99.0000")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I78", LATEST_DAY)).thenReturn(fiveExternalIssuers);

        ReportValuationBundle available = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I78");
        ValuationReferenceDto peer = available.valuation().peerReference();
        assertThat(peer.status()).isEqualTo("AVAILABLE");
        assertThat(peer.sampleCount()).isEqualTo(5);
        assertThat(peer.multipleLow()).isEqualByComparingTo("20.0000");
        assertThat(peer.multipleMedian()).isEqualByComparingTo("25.0000");
        assertThat(peer.multipleHigh()).isEqualByComparingTo("30.0000");
        assertThat(peer.priceLow()).isEqualByComparingTo("160.0000");
        assertThat(peer.priceHigh()).isEqualByComparingTo("240.0000");
    }

    @Test
    void peerReferenceExcludesStaleValuationDateSnapshotsBeforeFiveIssuerGate() {
        StockFinancialPeriod period = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("8.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(period));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "200")));

        LocalDate staleDate = LocalDate.of(2026, 10, 15);
        List<Object[]> fourCurrentPlusOneStale = List.of(
                peerTupleOnDate("0001652044", "GOOGL", LATEST_DAY, "16.0000"),
                peerTupleOnDate("0001326801", "META", LATEST_DAY, "20.0000"),
                peerTupleOnDate("0001018724", "AMZN", LATEST_DAY, "25.0000"),
                peerTupleOnDate("0001065280", "NFLX", LATEST_DAY, "30.0000"),
                peerTupleOnDate("0001999999", "UCFI", staleDate, "999.0000")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I78", LATEST_DAY)).thenReturn(fourCurrentPlusOneStale);

        ReportValuationBundle gated = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I78");
        assertThat(gated.valuation().peerReference().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(gated.valuation().peerReference().reasonCode()).isEqualTo("INDUSTRY_PEER_COMPANIES_INSUFFICIENT");

        List<Object[]> fiveCurrentPlusOneStale = List.of(
                peerTupleOnDate("0001652044", "GOOGL", LATEST_DAY, "16.0000"),
                peerTupleOnDate("0001326801", "META", LATEST_DAY, "20.0000"),
                peerTupleOnDate("0001018724", "AMZN", LATEST_DAY, "25.0000"),
                peerTupleOnDate("0001065280", "NFLX", LATEST_DAY, "30.0000"),
                peerTupleOnDate("0000789019", "MSFT", LATEST_DAY, "35.0000"),
                peerTupleOnDate("0001999999", "UCFI", staleDate, "999.0000")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I78", LATEST_DAY)).thenReturn(fiveCurrentPlusOneStale);

        ReportValuationBundle clean = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I78");
        ValuationReferenceDto peer = clean.valuation().peerReference();
        assertThat(peer.status()).isEqualTo("AVAILABLE");
        assertThat(peer.sampleCount()).isEqualTo(5);
        assertThat(peer.multipleMedian()).isEqualByComparingTo("25.0000");
        assertThat(peer.multipleHigh()).isEqualByComparingTo("30.0000");
    }

    @Test
    void industryAssumptionConvertsConfiguredRangeOnLatestDayAndMarksHistoricalDateNotApplicable() {
        StockFinancialPeriod period = buildPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("8.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(period));

        StockIndustry ind = new StockIndustry();
        ind.setCode("I10");
        StockIndustryHistory ih = new StockIndustryHistory();
        ih.setFigi(FIGI);
        ih.setIndustry(ind);
        when(industries.findAt(eq(FIGI), any())).thenReturn(Optional.of(ih));

        IndustryValuationConfig cfg = new IndustryValuationConfig();
        cfg.setIndustryCode("I10");
        cfg.setEffectiveAt(NOW.minusSeconds(10));
        cfg.setModelCode("NON_FINANCIAL_OPERATING");
        cfg.setPrimaryMetric("PE");
        cfg.setAssumedMultipleLow(new BigDecimal("22"));
        cfg.setAssumedMultipleHigh(new BigDecimal("28"));
        when(industryConfigs.findFirstByIndustryCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(eq("I10"), any()))
                .thenReturn(Optional.of(cfg));

        LocalDate histDay = LocalDate.of(2025, 6, 30);
        Instant histClose = Instant.parse("2025-06-30T20:00:00Z");
        List<StockQuote> quotesList = List.of(
                quote(LocalDate.of(2024, 12, 31), "160"),
                quote(LocalDate.of(2025, 1, 31), "168"),
                quote(LocalDate.of(2025, 2, 28), "176"),
                quote(LocalDate.of(2025, 3, 31), "184"),
                quote(LocalDate.of(2025, 4, 30), "192"),
                quote(LocalDate.of(2025, 5, 30), "200"),
                quote(histDay, "208"),
                quote(LATEST_DAY, "216")
        );
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY))).thenReturn(quotesList);
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(histDay)))
                .thenReturn(quotesList.stream().filter(q -> !q.getQuoteDate().isAfter(histDay)).toList());

        // 1) 最新收市日查詢：industryAssumption 生效並換算假設價格 176.0000–224.0000（中點 200.0000，currentPercentile 嚴格為 null）
        ReportValuationBundle latestBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        ValuationReferenceDto latestAssumption = latestBundle.valuation().industryAssumption();
        assertThat(latestAssumption.status()).isEqualTo("AVAILABLE");
        assertThat(latestAssumption.metricCode()).isEqualTo("PE");
        assertThat(latestAssumption.multipleLow()).isEqualByComparingTo("22.0000");
        assertThat(latestAssumption.multipleHigh()).isEqualByComparingTo("28.0000");
        assertThat(latestAssumption.priceLow()).isEqualByComparingTo("176.0000");
        assertThat(latestAssumption.priceMedian()).isEqualByComparingTo("200.0000");
        assertThat(latestAssumption.priceHigh()).isEqualByComparingTo("224.0000");
        assertThat(latestAssumption.currentMultiple()).isEqualByComparingTo("27.0000");
        assertThat(latestAssumption.currentPercentile()).isNull();

        // 2) 指定歷史日期查詢（2025-06-30 < 2026-11-13）：peerReference 與 industryAssumption 一律標 NOT_APPLICABLE，不讀同業快照
        ReportValuationBundle histBundle = service.evaluateForReport(apple, histDay, histClose, histClose, "I10");
        assertThat(histBundle.valuation().historicalReference().status()).isEqualTo("AVAILABLE");
        assertThat(histBundle.valuation().historicalReference().sampleCount()).isEqualTo(7);
        assertThat(histBundle.valuation().peerReference().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(histBundle.valuation().peerReference().reasonCode()).isEqualTo("HISTORICAL_DATE_NOT_APPLICABLE");
        assertThat(histBundle.valuation().industryAssumption().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(histBundle.valuation().industryAssumption().reasonCode()).isEqualTo("HISTORICAL_DATE_NOT_APPLICABLE");
        verify(latestValuations, never()).findActiveTrackedPeerSnapshots(any(), eq(histDay));
    }

    @Test
    void defaultLatestRequestFallsBackToEffectiveLatestQuoteDateBeforeDailyQuoteJobRuns() {
        LocalDate monday = LocalDate.of(2026, 11, 16);
        Instant mondayClose = Instant.parse("2026-11-16T21:00:00Z");
        Instant tuesdayMorning = Instant.parse("2026-11-17T00:30:00Z");
        when(clock.now()).thenReturn(tuesdayMorning);
        when(calendar.getLastClosedTradingDay(tuesdayMorning)).thenReturn(monday);
        // STOCK_QUOTE_DAILY 尚未同步週一 2026-11-16，stock_status.quote_date 與已入庫行情停在週五 2026-11-13
        status.setQuoteDate(LATEST_DAY);

        StockFinancialPeriod period = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("8.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(period));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(monday)))
                .thenReturn(List.of(quote(LATEST_DAY, "160")));

        ReportValuationBundle bundle = service.evaluateForReport(apple, monday, mondayClose, tuesdayMorning, "I10");

        assertThat(bundle.valuation().valuationDate()).isEqualTo(LATEST_DAY);
        assertThat(bundle.valuation().status()).isEqualTo("AVAILABLE");
        assertThat(bundle.valuation().metrics().pe().value()).isEqualByComparingTo("20.0000");
        assertThat(bundle.valuation().peerReference().status()).isNotEqualTo("NOT_APPLICABLE");
    }

    @Test
    void explicitHistoricalDateEqualToStaleQuoteDateRemainsHistoricalAndIssuedSharesFallbackIsLabeledAccurately() {
        LocalDate staleQuoteDay = LocalDate.of(2026, 9, 30);
        Instant staleClose = Instant.parse("2026-09-30T20:00:00Z");
        status.setQuoteDate(staleQuoteDay);

        StockFinancialPeriod period = buildPeriod(LocalDate.of(2026, 7, 1), staleQuoteDay,
                new BigDecimal("5.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(period));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(staleQuoteDay)))
                .thenReturn(List.of(quote(staleQuoteDay, "50")));

        com.ykm.stock.analytics.model.entity.StockShareObservation futureObs = new com.ykm.stock.analytics.model.entity.StockShareObservation();
        futureObs.setFigi(FIGI);
        futureObs.setObservedAt(LATEST_CLOSE);
        futureObs.setIssuedShares(999L);
        futureObs.setSource("FUTU");
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(FIGI, "FUTU", NOW))
                .thenReturn(Optional.of(futureObs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(FIGI, "FUTU", staleClose))
                .thenReturn(Optional.empty());

        // 1) 明確指定歷史日期 2026-09-30（supplementalCutoff == staleClose）：即使等於個股過期行情日，仍屬歷史查詢，且不使用未來 11-13 快照股數 999
        ReportValuationBundle explicitHist = service.evaluateForReport(apple, staleQuoteDay, staleClose, staleClose, "I10");
        assertThat(explicitHist.valuation().peerReference().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(explicitHist.valuation().peerReference().reasonCode()).isEqualTo("HISTORICAL_DATE_NOT_APPLICABLE");
        assertThat(explicitHist.valuation().industryAssumption().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(explicitHist.valuation().industryAssumption().reasonCode()).isEqualTo("HISTORICAL_DATE_NOT_APPLICABLE");
        assertThat(explicitHist.financials().sharesOutstanding().value()).isEqualByComparingTo("100");

        // 2) 最新收市日使用舊快照（outstandingShares 為 null，回退 issuedShares）時，口徑說明須如實標明已發行股數備援，不謊稱流通在外股數
        status.setQuoteDate(LATEST_DAY);
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "50")));
        ReportValuationBundle latestWithLegacyObs = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        assertThat(latestWithLegacyObs.financials().sharesOutstanding().calculation()).contains("已發行股數");
    }

    @Test
    void historicalReferenceCountsCompletelyMissingFinancialMonthsInWindowCoverage() {
        // 僅有 2025-01-01 起算的財報分母；2024 年 2 個月份完全無財報（整體狀態 INSUFFICIENT_DATA，五項倍數皆不可用）
        StockFinancialPeriod p2025 = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("10.00"), new BigDecimal("600"), new BigDecimal("1200"), new BigDecimal("250"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(p2025));

        List<StockQuote> twelveMonthsQuotes = new ArrayList<>();
        // 前 2 個月（2024-06、2024-09）完全缺財報 -> NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE，不得從應統計視窗刪除
        twelveMonthsQuotes.add(quote(LocalDate.of(2024, 6, 28), "150"));
        twelveMonthsQuotes.add(quote(LocalDate.of(2024, 9, 30), "160"));
        LocalDate[] tenValidMonths = {
                LocalDate.of(2025, 2, 28), LocalDate.of(2025, 4, 30), LocalDate.of(2025, 6, 30),
                LocalDate.of(2025, 8, 29), LocalDate.of(2025, 10, 31), LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 3, 31), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 9, 30), LATEST_DAY
        };
        int price = 100;
        for (LocalDate d : tenValidMonths) {
            twelveMonthsQuotes.add(quote(d, String.valueOf(price)));
            price += 20;
        }
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY))).thenReturn(twelveMonthsQuotes);

        ReportValuationBundle bundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        ValuationReferenceDto hist = bundle.valuation().historicalReference();

        // 月末歷史清單完整保留 12 個月（前 2 個月為 INSUFFICIENT_DATA，供圖表斷點與覆蓋率統計）
        assertThat(bundle.history()).hasSize(12);
        assertThat(bundle.history().get(0).status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(bundle.history().get(0).reasonCode()).isEqualTo("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        assertThat(bundle.history().get(1).status()).isEqualTo("INSUFFICIENT_DATA");

        // 必須揭露有效 10／12、排除 2（而非誤報 10／10、排除 0）
        assertThat(hist.status()).isEqualTo("AVAILABLE");
        assertThat(hist.sampleCount()).isEqualTo(10);
        assertThat(hist.windowSampleCount()).isEqualTo(12);
        assertThat(hist.excludedSampleCount()).isEqualTo(2);
        assertThat(hist.sampleStartDate()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(hist.sampleEndDate()).isEqualTo(LATEST_DAY);

        // 當 12 個月中有 8 個月完全缺財報、僅 4 個月可用（未滿 6 個月門檻）時，仍揭露 4／12、排除 8
        StockFinancialPeriod pLate = buildPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("10.00"), new BigDecimal("600"), new BigDecimal("1200"), new BigDecimal("250"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(pLate));

        ReportValuationBundle shortBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        ValuationReferenceDto shortHist = shortBundle.valuation().historicalReference();
        assertThat(shortHist.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(shortHist.reasonCode()).isEqualTo("HISTORICAL_SAMPLES_INSUFFICIENT");
        assertThat(shortHist.sampleCount()).isEqualTo(4);
        assertThat(shortHist.windowSampleCount()).isEqualTo(12);
        assertThat(shortHist.excludedSampleCount()).isEqualTo(8);
    }

    @Test
    void reportSharesEvaluationIsSharedAcrossValuationAndFinancialsDtoIncludingClosingSnapshotAndSplitError() {
        StockFinancialPeriod p = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("5.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("80"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(p));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "10")));

        // 同日收盤快照提供流通市值 900（收盤價 10 -> 90 股），而 cutoffObs 為舊股數 80 股 -> 兩者必須統一採用 90 股（P/B = 900/500 = 1.80，BVPS = 500/90）
        com.ykm.stock.analytics.model.entity.StockShareObservation cutoffObs = new com.ykm.stock.analytics.model.entity.StockShareObservation();
        cutoffObs.setFigi(FIGI);
        cutoffObs.setSource("FUTU");
        cutoffObs.setObservedAt(LATEST_CLOSE.minusSeconds(86400));
        cutoffObs.setOutstandingShares(80L);

        com.ykm.stock.analytics.model.entity.StockShareObservation closingSnap = new com.ykm.stock.analytics.model.entity.StockShareObservation();
        closingSnap.setFigi(FIGI);
        closingSnap.setSource("FUTU");
        closingSnap.setSnapshotPrice(new BigDecimal("10"));
        closingSnap.setSnapshotQuoteTime(LATEST_CLOSE.minusSeconds(60));
        closingSnap.setObservedAt(LATEST_CLOSE.plusSeconds(300));
        closingSnap.setOutstandingMarketCap(new BigDecimal("900"));
        closingSnap.setOutstandingShares(90L);

        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(FIGI, "FUTU", NOW))
                .thenReturn(Optional.of(cutoffObs));
        when(shareObservations.findLatestClosingSnapshotAt(eq(List.of(FIGI)), any(), eq(LATEST_CLOSE), eq(NOW)))
                .thenReturn(List.of(closingSnap));

        ReportValuationBundle snapBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        assertThat(snapBundle.valuation().marketCapSource()).isEqualTo("FUTU_CLOSE_SNAPSHOT");
        assertThat(snapBundle.valuation().metrics().pb().value()).isEqualByComparingTo("1.8000");
        assertThat(snapBundle.financials().sharesOutstanding().value()).isEqualByComparingTo("90");
        BigDecimal impliedPbFromDto = snapBundle.valuation().price().divide(snapBundle.financials().bookValuePerShare().value(), 4, java.math.RoundingMode.HALF_UP);
        assertThat(impliedPbFromDto).isEqualByComparingTo("1.8000");

        // 當發生 SPLIT_SYNC_ERROR 且跨基準日時，FinancialsDto 亦須同步攔截，不漏放舊快照股數
        when(shareObservations.findLatestClosingSnapshotAt(eq(List.of(FIGI)), any(), eq(LATEST_CLOSE), eq(NOW)))
                .thenReturn(List.of());
        status.setSplitMessage("ERROR:SPLIT_FETCH_FAILED");
        ReportValuationBundle splitErrBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        assertThat(splitErrBundle.valuation().metrics().pb().reasonCode()).isEqualTo("SPLIT_SYNC_ERROR");
        assertThat(splitErrBundle.financials().sharesOutstanding().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(splitErrBundle.financials().sharesOutstanding().reasonCode()).isEqualTo("SPLIT_SYNC_ERROR");
    }

    @Test
    void tierIsolationFallbackCustomPeerMetricOverrideAndWarningsBehaveAsSpecified() {
        // 目標股：shares = 1_000_000_000, price = 20 -> marketCap = 20B (MEGA_LARGE), EPS = 1.00, Equity = 5B (BVPS = 5.00)
        StockFinancialPeriod pPrior = buildPeriod(LocalDate.of(2024, 10, 1), LocalDate.of(2025, 9, 30),
                new BigDecimal("0.80"), new BigDecimal("4000000000"), new BigDecimal("10000000000"), new BigDecimal("2000000000"), new BigDecimal("1000000000"));
        StockFinancialPeriod pCurr = buildPeriod(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("1.00"), new BigDecimal("5000000000"), new BigDecimal("10500000000"), new BigDecimal("2200000000"), new BigDecimal("1000000000"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(pPrior, pCurr));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "20")));

        // 驗證市值量級切分邊界：USD 恰 10B -> MEGA_LARGE, 恰 2B -> MID, <2B -> SMALL, 外幣與零市值 -> null
        assertThat(StockValuationService.resolveMarketCapTier("USD", new BigDecimal("10000000000"))).isEqualTo("MEGA_LARGE");
        assertThat(StockValuationService.resolveMarketCapTier("USD", new BigDecimal("2000000000"))).isEqualTo("MID");
        assertThat(StockValuationService.resolveMarketCapTier("USD", new BigDecimal("1999999999"))).isEqualTo("SMALL");
        assertThat(StockValuationService.resolveMarketCapTier("CAD", new BigDecimal("50000000000"))).isNull();
        assertThat(StockValuationService.resolveMarketCapTier("USD", BigDecimal.ZERO)).isNull();

        // 1) 同產業含 6 家 MEGA_LARGE（PE 25..35，中位數 30）與 20 家 SMALL（PE 8..12）-> 龍頭股採 SAME_TIER，不被 20 家小盤股拉低
        List<Object[]> mixedIndustry = new ArrayList<>();
        int[] megaPes = {25, 27, 29, 31, 33, 35};
        for (int i = 0; i < megaPes.length; i++) {
            mixedIndustry.add(tieredPeerTuple("000900000" + i, "MEGA_" + i, "15000000000", "MEGA_LARGE",
                    String.valueOf(megaPes[i]), "4.0", "0.18", "0.22", "0.15"));
        }
        for (int i = 0; i < 20; i++) {
            String cik = String.format("00080000%02d", i);
            mixedIndustry.add(tieredPeerTuple(cik, "SML_" + i, "500000000", "SMALL",
                    "10", "1.5", "0.04", "0.06", "0.03"));
        }
        when(latestValuations.findActiveTrackedPeerSnapshots("I10", LATEST_DAY)).thenReturn(mixedIndustry);

        ReportValuationBundle sameTierBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        ValuationReferenceDto sameTierRef = sameTierBundle.valuation().peerReference();
        assertThat(sameTierRef.peerScope()).isEqualTo("SAME_TIER");
        assertThat(sameTierRef.targetMarketCapTier()).isEqualTo("MEGA_LARGE");
        assertThat(sameTierRef.sampleCount()).isEqualTo(6);
        assertThat(sameTierRef.multipleMedian()).isEqualByComparingTo("30.0000");
        assertThat(sameTierRef.priceMedian()).isEqualByComparingTo("30.0000");
        // 副標呈現全產業 26 家 PE 中位數（20 家 10x + 6 家 25..35x -> P50 = 10.0000），但不混入參考帶
        assertThat(sameTierRef.industryAllMetricCode()).isEqualTo("PE");
        assertThat(sameTierRef.industryAllSampleCount()).isEqualTo(26);
        assertThat(sameTierRef.industryAllMedianMultiple()).isEqualByComparingTo("10.0000");
        assertThat(sameTierRef.peerRevenueGrowthYoyMedian()).isEqualByComparingTo("0.180000");
        assertThat(sameTierRef.peerRoeMedian()).isEqualByComparingTo("0.220000");
        assertThat(sameTierRef.peerFcfMarginMedian()).isEqualByComparingTo("0.150000");

        // 2) 當 MEGA_LARGE 僅 3 家，併入 3 家 MID 共 6 家 -> ADJACENT_TIER；若連 MID 也為 0 家則退回 ALL_INDUSTRY
        List<Object[]> threeMegaThreeMid = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            threeMegaThreeMid.add(tieredPeerTuple("000900000" + i, "MEGA_" + i, "15000000000", "MEGA_LARGE", "30", "4.0", "0.15", "0.20", "0.12"));
            threeMegaThreeMid.add(tieredPeerTuple("000700000" + i, "MID_" + i, "5000000000", "MID", "20", "3.0", "0.10", "0.14", "0.10"));
        }
        threeMegaThreeMid.add(tieredPeerTuple("0008000001", "SML_1", "500000000", "SMALL", "8", "1.0", "0.02", "0.05", "0.02"));
        when(latestValuations.findActiveTrackedPeerSnapshots("I10", LATEST_DAY)).thenReturn(threeMegaThreeMid);

        ValuationReferenceDto adjacentRef = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(adjacentRef.peerScope()).isEqualTo("ADJACENT_TIER");
        assertThat(adjacentRef.sampleCount()).isEqualTo(6);
        assertThat(adjacentRef.multipleMedian()).isEqualByComparingTo("25.0000");

        List<Object[]> twoMegaFourSmall = new ArrayList<>();
        twoMegaFourSmall.add(tieredPeerTuple("0009000001", "MEGA_1", "15000000000", "MEGA_LARGE", "30", "4.0", "0.15", "0.20", "0.12"));
        twoMegaFourSmall.add(tieredPeerTuple("0009000002", "MEGA_2", "15000000000", "MEGA_LARGE", "28", "4.0", "0.15", "0.20", "0.12"));
        for (int i = 0; i < 4; i++) {
            twoMegaFourSmall.add(tieredPeerTuple("000800000" + i, "SML_" + i, "500000000", "SMALL", "12", "1.2", "0.05", "0.08", "0.04"));
        }
        when(latestValuations.findActiveTrackedPeerSnapshots("I10", LATEST_DAY)).thenReturn(twoMegaFourSmall);

        ValuationReferenceDto allIndRef = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(allIndRef.peerScope()).isEqualTo("ALL_INDUSTRY");
        assertThat(allIndRef.sampleCount()).isEqualTo(6);

        // 3) 自訂對標：主指標 PE，自訂覆寫 preferredMetric = PB；當僅 2 家有效時退回 PE 並附 CUSTOM_PEERS_INSUFFICIENT；滿 3 家時採 PB 並觸發 10x 市值差與 20% 成長差提醒
        StockCustomPeer cp1 = new StockCustomPeer(FIGI, "CP_1", "PB", 0, NOW);
        StockCustomPeer cp2 = new StockCustomPeer(FIGI, "CP_2", "PB", 1, NOW);
        StockCustomPeer cp3 = new StockCustomPeer(FIGI, "CP_3", "PB", 2, NOW);
        when(customPeers.findByFigiOrderBySortOrderAsc(FIGI)).thenReturn(List.of(cp1, cp2));
        List<Object[]> twoCustomRows = List.of(
                tieredPeerTuple("0006000001", "CP_1", "25000000000", "MEGA_LARGE", "25", "5.0", "0.30", "0.25", "0.18"),
                tieredPeerTuple("0006000002", "CP_2", "22000000000", "MEGA_LARGE", "24", "6.0", "0.32", "0.26", "0.19")
        );
        when(latestValuations.findActiveTrackedCustomPeerSnapshots(eq(List.of("CP_1", "CP_2")), eq(List.of(LATEST_DAY))))
                .thenReturn(twoCustomRows);

        ValuationReferenceDto fallbackFromCustom = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(fallbackFromCustom.peerScope()).isEqualTo("ALL_INDUSTRY");
        assertThat(fallbackFromCustom.metricCode()).isEqualTo("PE");
        assertThat(fallbackFromCustom.peerFallbackReason()).isEqualTo("CUSTOM_PEERS_INSUFFICIENT");

        // 補滿 3 家有效自訂對手（其中 CP_3 市值僅 1B 與目標股 20B 相差 20 倍 > 10x，且同業成長中位數 +30% 與目標股 +5% 差距 25pp >= 20pp）
        when(customPeers.findByFigiOrderBySortOrderAsc(FIGI)).thenReturn(List.of(cp1, cp2, cp3));
        List<Object[]> threeCustomRows = List.of(
                tieredPeerTuple("0006000001", "CP_1", "25000000000", "MEGA_LARGE", "25", "4.0", "0.28", "0.22", "0.16"),
                tieredPeerTuple("0006000002", "CP_2", "22000000000", "MEGA_LARGE", "24", "6.0", "0.30", "0.25", "0.18"),
                tieredPeerTuple("0006000003", "CP_3", "1000000000", "SMALL", "18", "8.0", "0.35", "0.28", "0.20")
        );
        when(latestValuations.findActiveTrackedCustomPeerSnapshots(eq(List.of("CP_1", "CP_2", "CP_3")), eq(List.of(LATEST_DAY))))
                .thenReturn(threeCustomRows);

        ValuationReferenceDto activeCustom = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(activeCustom.peerScope()).isEqualTo("CUSTOM_PEERS");
        assertThat(activeCustom.metricCode()).isEqualTo("PB");
        assertThat(activeCustom.sampleCount()).isEqualTo(3);
        // PB [4, 6, 8] 中位數 6.0000 * 目標股 BVPS 5.00 = 30.0000 USD；目標股當期 PB = 20 / 5 = 4.0000
        assertThat(activeCustom.multipleMedian()).isEqualByComparingTo("6.0000");
        assertThat(activeCustom.priceMedian()).isEqualByComparingTo("30.0000");
        assertThat(activeCustom.currentMultiple()).isEqualByComparingTo("4.0000");
        // 副標仍明確標示全產業主指標 PE 中位數
        assertThat(activeCustom.industryAllMetricCode()).isEqualTo("PE");
        assertThat(activeCustom.warningCodes())
                .containsExactlyInAnyOrder("CUSTOM_PEER_MARKET_CAP_MISMATCH", "CUSTOM_PEER_GROWTH_DIVERGENCE");

        // 清空自訂清單後立即還原產業預設分層
        when(customPeers.findByFigiOrderBySortOrderAsc(FIGI)).thenReturn(List.of());
        ValuationReferenceDto restored = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(restored.peerScope()).isEqualTo("ALL_INDUSTRY");
        assertThat(restored.metricCode()).isEqualTo("PE");
        assertThat(restored.peerFallbackReason()).isNull();
    }

    @Test
    void twoPassRefreshPopulatesSevenNewLatestColumnsPreventsDateRegressionAndPropagatesPeerVerdictUpdates() {
        TransactionTemplate realTx = new TransactionTemplate(new com.ykm.stock.analytics.support.NoOpTransactionManager());
        StockValuationService txService = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, financialPeriods, latestValuations, customPeers,
                calendar, clock, issuerHistory, realTx, entityManager);

        StockFinancialPeriod pPrior = buildPeriod(LocalDate.of(2024, 10, 1), LocalDate.of(2025, 9, 30),
                new BigDecimal("4.00"), new BigDecimal("4000000000"), new BigDecimal("10000000000"), new BigDecimal("2000000000"), new BigDecimal("1000000000"));
        pPrior.setNetIncomeCommonTtm(new BigDecimal("800000000"));
        pPrior.setNetIncomeCommonTtmStatus("AVAILABLE");
        StockFinancialPeriod pCurr = buildPeriod(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("5.00"), new BigDecimal("5000000000"), new BigDecimal("12000000000"), new BigDecimal("2500000000"), new BigDecimal("1000000000"));
        pCurr.setNetIncomeCommonTtm(new BigDecimal("1000000000"));
        pCurr.setNetIncomeCommonTtmStatus("AVAILABLE");
        pCurr.setFreeCashFlowTtm(new BigDecimal("1800000000"));

        Stock peerA = new Stock();
        peerA.setFigi("PEER_A");
        peerA.setSymbol("PEERA");
        peerA.setCik("0000111111");
        peerA.setCurrency("USD");
        peerA.setStockType("Common Stock");
        peerA.setActiveStatus(ActiveStatus.ACTIVE);
        peerA.setTracked(true);
        StockStatus statusA = new StockStatus();
        statusA.setFigi("PEER_A");
        statusA.setQuoteDate(LATEST_DAY);

        StockIndustry ind = new StockIndustry();
        ind.setCode("I10");
        StockIndustryHistory ihAapl = new StockIndustryHistory();
        ihAapl.setFigi(FIGI);
        ihAapl.setIndustry(ind);
        StockIndustryHistory ihPeerA = new StockIndustryHistory();
        ihPeerA.setFigi("PEER_A");
        ihPeerA.setIndustry(ind);

        when(stocks.findAllById(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            List<Stock> found = new ArrayList<>();
            if (ids.contains(FIGI)) found.add(apple);
            if (ids.contains("PEER_A")) found.add(peerA);
            return found;
        });
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(status, statusA));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(status, statusA));
        when(industries.findByFigisAt(anyCollection(), any())).thenReturn(List.of(ihAapl, ihPeerA));

        StockFinancialPeriod pCurrA = buildPeriod(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("5.00"), new BigDecimal("5000000000"), new BigDecimal("12000000000"), new BigDecimal("2500000000"), new BigDecimal("1000000000"));
        pCurrA.setFigi("PEER_A");
        when(financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection()))
                .thenReturn(List.of(pPrior, pCurr, pCurrA));

        List<StockQuote> sixMonths = List.of(
                quote(LocalDate.of(2026, 6, 30), "80"),
                quote(LocalDate.of(2026, 7, 31), "90"),
                quote(LocalDate.of(2026, 8, 31), "100"),
                quote(LocalDate.of(2026, 9, 30), "110"),
                quote(LocalDate.of(2026, 10, 30), "120"),
                quote(LATEST_DAY, "85")
        );
        StockQuote peerADayQuote = new StockQuote();
        peerADayQuote.setFigi("PEER_A");
        peerADayQuote.setQuoteDate(LATEST_DAY);
        peerADayQuote.setClosePrice(new BigDecimal("60")); // PE = 12
        when(quotes.findStoredQuotes(anyCollection(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "85"), peerADayQuote));
        when(quotes.findMonthEndQuotes(anyCollection(), any(), eq(LATEST_DAY))).thenReturn(sixMonths);

        // 其他 4 家 MEGA_LARGE 同業已在 DB 中（PE = 20, 22, 24, 26），加上 AAPL (PE=17) 對 PEER_A 湊滿 5 家同業！
        List<Object[]> dbPeers = List.of(
                tieredPeerTuple("0009000001", "M1", "20000000000", "MEGA_LARGE", "20", "4.0", "0.10", "0.15", "0.10"),
                tieredPeerTuple("0009000002", "M2", "20000000000", "MEGA_LARGE", "22", "4.0", "0.10", "0.15", "0.10"),
                tieredPeerTuple("0009000003", "M3", "20000000000", "MEGA_LARGE", "24", "4.0", "0.10", "0.15", "0.10"),
                tieredPeerTuple("0009000004", "M4", "20000000000", "MEGA_LARGE", "26", "4.0", "0.10", "0.15", "0.10")
        );
        when(latestValuations.findActiveTrackedPeerSnapshotsIn(anyCollection(), anyCollection())).thenReturn(dbPeers);

        java.util.Map<String, StockValuationLatest> savedLatest = new java.util.LinkedHashMap<>();
        org.mockito.Mockito.doAnswer(inv -> {
            StockValuationLatest arg = inv.getArgument(0);
            savedLatest.put(arg.getFigi(), arg);
            return null;
        }).when(entityManager).persist(any(StockValuationLatest.class));
        org.mockito.Mockito.doAnswer(inv -> {
            StockValuationLatest arg = inv.getArgument(0);
            savedLatest.put(arg.getFigi(), arg);
            return arg;
        }).when(entityManager).merge(any(StockValuationLatest.class));
        when(latestValuations.findByFigiIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            return savedLatest.values().stream().filter(v -> ids.contains(v.getFigi())).toList();
        });

        txService.refreshLatestValuations(List.of(FIGI, "PEER_A"), null);

        StockValuationLatest aaplSnap = savedLatest.get(FIGI);
        assertThat(aaplSnap).isNotNull();
        assertThat(aaplSnap.getMarketCap()).isEqualByComparingTo("85000000000");
        assertThat(aaplSnap.getMarketCapTier()).isEqualTo("MEGA_LARGE");
        assertThat(aaplSnap.getRevenueGrowthYoy()).isEqualByComparingTo("0.200000");
        assertThat(aaplSnap.getRoe()).isEqualByComparingTo("0.222222");
        assertThat(aaplSnap.getFcfMargin()).isEqualByComparingTo("0.150000");
        assertThat(aaplSnap.getHistoricalPercentile()).isNotNull();
        assertThat(aaplSnap.getVerdict()).isEqualTo("MODERATELY_UNDERVALUED");

        // PEER_A 僅有 1 個月行情（無歷史分位），依靠本輪剛刷新的 AAPL + 4 家 DB 同業湊滿 5 家 MEGA_LARGE，在同一次發布取得 DEEP_UNDERVALUED
        StockValuationLatest peerASnap = savedLatest.get("PEER_A");
        assertThat(peerASnap.getVerdict()).isEqualTo("DEEP_UNDERVALUED");

        // 驗證防日期倒退：以較早的 requestedDate 呼叫時，絕不倒退覆寫已存在的更新交易日快照
        txService.refreshLatestValuations(List.of(FIGI), LATEST_DAY.minusDays(5));
        assertThat(savedLatest.get(FIGI).getValuationDate()).isEqualTo(LATEST_DAY);

        // 當 AAPL 被停追（tracked=false）時，onTrackedChanged 自動將失去第 5 家同業且無歷史錨點的 PEER_A 更新為 INSUFFICIENT_EVIDENCE
        apple.setTracked(false);
        when(stocks.findById(FIGI)).thenReturn(Optional.of(apple));
        when(latestValuations.findActiveTrackedFigisByIndustryCodesAndDates(anyCollection(), anyCollection()))
                .thenReturn(List.of("PEER_A"));
        txService.onTrackedChanged(List.of(FIGI));
        assertThat(savedLatest.get("PEER_A").getVerdict()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void historicalForwardEpsAvoidsLookaheadAndPeerEdgeCasesPreserveCountExactCapRatioAndSkippedPeers() {
        // 1) 歷史日期 2026-08-14 查詢：Q2 (6/30, 7/31 公布, EPS=4.00) 與 Q3 (9/30, 11/01 公布, EPS=5.00)
        //    當日歷史估值沿用涵蓋該日之 Q3 (PE = 100 / 5 = 20)，但傳給前瞻對比的 epsAtDay 僅用截至 8/14 已公開之 Q2 (4.00)
        LocalDate aug14 = LocalDate.of(2026, 8, 14);
        Instant aug14Close = Instant.parse("2026-08-14T20:00:00Z");
        when(calendar.getSessionCloseAt(aug14)).thenReturn(aug14Close);

        StockFinancialPeriod q2Known = buildPeriod(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("4.00"), new BigDecimal("4000000000"), new BigDecimal("10000000000"), new BigDecimal("2000000000"), new BigDecimal("2000000000"));
        // 模擬 Q2 損益表 EPS 於 7/31 已公開，但現金流晚至 8/20 入庫使整列 availableAt = 8/20，且財季股數為 9/30 回填之 20 億股
        q2Known.setEpsTtmAvailableAt(Instant.parse("2026-07-31T20:00:00Z"));
        q2Known.setAvailableAt(Instant.parse("2026-08-20T20:00:00Z"));
        q2Known.setSharesBasisDate(LocalDate.of(2026, 9, 30));
        q2Known.setSharesOutstandingAvailableAt(Instant.parse("2026-09-30T20:00:00Z"));

        StockShareObservation preAug14Obs = new StockShareObservation();
        preAug14Obs.setFigi(FIGI);
        preAug14Obs.setSource("FUTU");
        preAug14Obs.setObservedAt(Instant.parse("2026-08-10T20:00:00Z"));
        preAug14Obs.setIssuedShares(1_000_000_000L);
        preAug14Obs.setOutstandingShares(1_000_000_000L);
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(FIGI, "FUTU", aug14Close))
                .thenReturn(Optional.of(preAug14Obs));

        StockFinancialPeriod q3FuturePublished = buildPeriod(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("5.00"), new BigDecimal("5000000000"), new BigDecimal("12000000000"), new BigDecimal("2500000000"), new BigDecimal("2000000000"));
        q3FuturePublished.setEpsTtmAvailableAt(Instant.parse("2026-11-01T20:00:00Z"));
        q3FuturePublished.setAvailableAt(Instant.parse("2026-11-01T20:00:00Z"));
        q3FuturePublished.setSharesOutstandingAvailableAt(Instant.parse("2026-11-01T20:00:00Z"));

        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(q2Known, q3FuturePublished));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(aug14)))
                .thenReturn(List.of(quote(aug14, "100")));

        StockValuationService.ReportValuationBundle histBundle = service.evaluateForReport(apple, aug14, aug14Close, aug14Close, "I10");
        assertThat(histBundle.valuation().metrics().pe().value()).isEqualByComparingTo("20.0000");
        // Q2 EPS (7/31) 不因晚到的現金流 (8/20) 消失；前瞻市值退回 8/10 已知的 10 億股（100B），不誤用 9/30 回填的 20 億股（200B）
        assertThat(histBundle.epsAtDay()).isEqualByComparingTo("4.00");
        assertThat(histBundle.marketCapAtDay()).isEqualByComparingTo("100000000000");

        // 2) 全產業僅 4 家有效同業時，industryAllSampleCount 仍回傳已知家數 4，中位數留空；
        //    且自訂組市值相差 10.00004 倍（100,000.4M vs 10,000M）精準觸發 CUSTOM_PEER_MARKET_CAP_MISMATCH
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(q3FuturePublished));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "5"))); // AAPL 市值 = 5 * 2B = 10B

        List<Object[]> fourIndustryPeers = List.of(
                tieredPeerTuple("0008000001", "IND_1", "20000000000", "MEGA_LARGE", "20", "4.0", "0.10", "0.15", "0.10"),
                tieredPeerTuple("0008000002", "IND_2", "20000000000", "MEGA_LARGE", "22", "4.0", "0.10", "0.15", "0.10"),
                tieredPeerTuple("0008000003", "IND_3", "20000000000", "MEGA_LARGE", "24", "4.0", "0.10", "0.15", "0.10"),
                tieredPeerTuple("0008000004", "IND_4", "20000000000", "MEGA_LARGE", "26", "4.0", "0.10", "0.15", "0.10")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I10", LATEST_DAY)).thenReturn(fourIndustryPeers);
        ValuationReferenceDto fourOnlyRef = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(fourOnlyRef.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(fourOnlyRef.industryAllSampleCount()).isEqualTo(4);
        assertThat(fourOnlyRef.industryAllMedianMultiple()).isNull();

        StockCustomPeer cp1 = new StockCustomPeer(FIGI, "CP_1", "PE", 0, NOW);
        StockCustomPeer cp2 = new StockCustomPeer(FIGI, "CP_2", "PE", 1, NOW);
        StockCustomPeer cp3 = new StockCustomPeer(FIGI, "CP_3", "PE", 2, NOW);
        when(customPeers.findByFigiOrderBySortOrderAsc(FIGI)).thenReturn(List.of(cp1, cp2, cp3));
        when(latestValuations.findActiveTrackedCustomPeerSnapshots(eq(List.of("CP_1", "CP_2", "CP_3")), eq(List.of(LATEST_DAY))))
                .thenReturn(List.of(
                        tieredPeerTuple("0007000001", "CP_1", "100000400000", "MEGA_LARGE", "20", "4.0", "0.10", "0.15", "0.10"),
                        tieredPeerTuple("0007000002", "CP_2", "15000000000", "MEGA_LARGE", "22", "4.0", "0.10", "0.15", "0.10"),
                        tieredPeerTuple("0007000003", "CP_3", "15000000000", "MEGA_LARGE", "24", "4.0", "0.10", "0.15", "0.10")
                ));
        ValuationReferenceDto boundaryRatioRef = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10").valuation().peerReference();
        assertThat(boundaryRatioRef.warningCodes()).contains("CUSTOM_PEER_MARKET_CAP_MISMATCH");
    }

    @Test
    void forwardHistoricalInputsGuardMultiClassFutureSecEvidencePriorQuarterStaleSharesAndUnrelatedSecDelays() {
        LocalDate aug14 = LocalDate.of(2026, 8, 14);
        Instant aug14Close = Instant.parse("2026-08-14T20:00:00Z");
        when(calendar.getSessionCloseAt(aug14)).thenReturn(aug14Close);

        // 1) 多股類歷史前瞻市值：8/14 富途股數 100、SEC 股數 80（不滿足 <=2%）；即使加入 11 月才觀察到且吻合的 SEC 股數 100，8/14 查詢仍須回傳 SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED
        Stock multiStock = new Stock();
        multiStock.setFigi("MULTI_A");
        multiStock.setSymbol("MULTIA");
        multiStock.setCik("0001652044");
        multiStock.setCurrency("USD");
        multiStock.setStockType("Common Stock");
        multiStock.setActiveStatus(ActiveStatus.ACTIVE);
        multiStock.setTracked(true);
        StockStatus multiStatus = new StockStatus();
        multiStatus.setFigi("MULTI_A");
        multiStatus.setQuoteDate(LATEST_DAY);
        when(statuses.findById("MULTI_A")).thenReturn(Optional.of(multiStatus));
        when(stocks.findMultiSecurityIssuers(eq(List.of("0001652044")), eq(ActiveStatus.ACTIVE)))
                .thenReturn(java.util.Set.of("0001652044"));

        StockShareObservation augObs100 = new StockShareObservation();
        augObs100.setFigi("MULTI_A");
        augObs100.setSource("FUTU");
        augObs100.setObservedAt(Instant.parse("2026-08-10T20:00:00Z"));
        augObs100.setIssuedShares(100L);
        augObs100.setOutstandingShares(100L);
        augObs100.setSnapshotPrice(new BigDecimal("50"));
        augObs100.setOutstandingMarketCap(new BigDecimal("5000"));
        augObs100.setSnapshotMarketCap(new BigDecimal("5000"));

        StockShareObservation novObs100 = new StockShareObservation();
        novObs100.setFigi("MULTI_A");
        novObs100.setSource("FUTU");
        novObs100.setObservedAt(Instant.parse("2026-11-10T20:00:00Z"));
        novObs100.setIssuedShares(100L);
        novObs100.setOutstandingShares(100L);
        novObs100.setSnapshotPrice(new BigDecimal("60"));
        novObs100.setOutstandingMarketCap(new BigDecimal("6000"));
        novObs100.setSnapshotMarketCap(new BigDecimal("6000"));

        com.ykm.stock.analytics.model.entity.SecFinancialFact julSec80 = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        julSec80.setCik("0001652044");
        julSec80.setTaxonomy("us-gaap");
        julSec80.setTag("CommonStockSharesOutstanding");
        julSec80.setUnit("shares");
        julSec80.setPeriodEnd(LocalDate.of(2026, 6, 30));
        julSec80.setFiled(LocalDate.of(2026, 7, 30));
        julSec80.setAvailableFromDate(LocalDate.of(2026, 7, 31));
        julSec80.setObservedAt(Instant.parse("2026-07-31T12:00:00Z"));
        julSec80.setAccession("0001652044-26-000001");
        julSec80.setForm("10-Q");
        julSec80.setValue(new BigDecimal("80"));

        com.ykm.stock.analytics.model.entity.SecFinancialFact novSec100 = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        novSec100.setCik("0001652044");
        novSec100.setTaxonomy("us-gaap");
        novSec100.setTag("CommonStockSharesOutstanding");
        novSec100.setUnit("shares");
        novSec100.setPeriodEnd(LocalDate.of(2026, 9, 30));
        novSec100.setFiled(LocalDate.of(2026, 11, 5));
        novSec100.setAvailableFromDate(LocalDate.of(2026, 11, 6));
        novSec100.setObservedAt(Instant.parse("2026-11-06T12:00:00Z"));
        novSec100.setAccession("0001652044-26-000002");
        novSec100.setForm("10-Q");
        novSec100.setValue(new BigDecimal("100"));

        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc("MULTI_A", "FUTU", NOW))
                .thenReturn(Optional.of(novObs100));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc("MULTI_A", "FUTU", aug14Close))
                .thenReturn(Optional.of(augObs100));
        when(secFacts.findLatestIssuerShareFacts(eq(List.of("0001652044")), eq(NOW)))
                .thenReturn(List.of(novSec100, julSec80));
        when(secFacts.findLatestIssuerShareFacts(eq(List.of("0001652044")), eq(aug14Close)))
                .thenReturn(List.of(novSec100, julSec80));

        StockFinancialPeriod multiQ2 = buildPeriod(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("4.00"), new BigDecimal("4000"), new BigDecimal("10000"), new BigDecimal("2000"), new BigDecimal("80"));
        multiQ2.setFigi("MULTI_A");
        multiQ2.setAvailableAt(Instant.parse("2026-07-31T12:00:00Z"));
        multiQ2.setSharesOutstandingAvailableAt(Instant.parse("2026-07-31T12:00:00Z"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc("MULTI_A")).thenReturn(List.of(multiQ2));

        StockQuote multiAugQuote = new StockQuote();
        multiAugQuote.setFigi("MULTI_A");
        multiAugQuote.setQuoteDate(aug14);
        multiAugQuote.setClosePrice(new BigDecimal("50"));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("MULTI_A"), any(), eq(aug14)))
                .thenReturn(List.of(multiAugQuote));

        StockValuationService.ReportValuationBundle multiBundle = service.evaluateForReport(
                multiStock, aug14, aug14Close, aug14Close, "I10");
        assertThat(multiBundle.marketCapAtDay()).isNull();
        assertThat(multiBundle.sharesReasonAtDay()).isEqualTo("SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED");

        // 1b) 同一次未通過 8/14 1:1 驗證的多股類查詢：若日後重建的財季已寫入發行人級 SEC EPS = 10.00，8/14 查詢亦不得沿用未來 1:1 資格給出 epsAtDay=10
        multiQ2.setEpsTtm(new BigDecimal("10.00"));
        multiQ2.setEpsTtmSource("SEC");
        multiQ2.setEpsTtmAvailableAt(Instant.parse("2026-07-31T12:00:00Z"));
        multiQ2.setEpsTtmSecAvailableFromDate(LocalDate.of(2026, 7, 31));
        StockValuationService.ReportValuationBundle multiSecEpsBlocked = service.evaluateForReport(
                multiStock, aug14, aug14Close, aug14Close, "I10");
        assertThat(multiSecEpsBlocked.epsAtDay()).isNull();
        assertThat(multiSecEpsBlocked.peReasonAtDay()).isEqualTo("SEC_SHARE_CLASS_EPS_UNVERIFIED");

        // 1c) 當 8/14 前富途等值股數與 SEC 股數均為 100（已通過截點 1:1 驗證），且 Q2 股數為 9/30 事後回填時：
        //     不論同時存在 Q1(50)、僅有 Q2、或完全無財季列，回退皆採用 8/10 合格等值觀察股數得出市值 5,000
        com.ykm.stock.analytics.model.entity.SecFinancialFact julSec100 = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        julSec100.setCik("0001652044");
        julSec100.setTaxonomy("us-gaap");
        julSec100.setTag("CommonStockSharesOutstanding");
        julSec100.setUnit("shares");
        julSec100.setPeriodEnd(LocalDate.of(2026, 6, 30));
        julSec100.setFiled(LocalDate.of(2026, 7, 30));
        julSec100.setAvailableFromDate(LocalDate.of(2026, 7, 31));
        julSec100.setObservedAt(Instant.parse("2026-07-31T12:00:00Z"));
        julSec100.setAccession("0001652044-26-000009");
        julSec100.setForm("10-Q");
        julSec100.setValue(new BigDecimal("100"));
        when(secFacts.findLatestIssuerShareFacts(eq(List.of("0001652044")), eq(aug14Close)))
                .thenReturn(List.of(julSec100));

        StockFinancialPeriod multiQ1_50 = buildPeriod(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31),
                new BigDecimal("3.00"), new BigDecimal("3000"), new BigDecimal("8000"), new BigDecimal("1600"), new BigDecimal("50"));
        multiQ1_50.setFigi("MULTI_A");
        multiQ1_50.setAvailableAt(Instant.parse("2026-05-01T12:00:00Z"));
        multiQ1_50.setSharesOutstandingAvailableAt(Instant.parse("2026-05-01T12:00:00Z"));
        multiQ1_50.setSharesSecAvailableFromDate(LocalDate.of(2026, 5, 1));

        multiQ2.setSharesBasisDate(LocalDate.of(2026, 9, 30));
        multiQ2.setSharesOutstandingAvailableAt(Instant.parse("2026-09-30T12:00:00Z"));

        when(financialPeriods.findByFigiOrderByPeriodEndAsc("MULTI_A")).thenReturn(List.of(multiQ1_50, multiQ2));
        StockValuationService.ReportValuationBundle multiVerifiedWithQ1 = service.evaluateForReport(
                multiStock, aug14, aug14Close, aug14Close, "I10");
        assertThat(multiVerifiedWithQ1.marketCapAtDay()).isEqualByComparingTo("5000");
        assertThat(multiVerifiedWithQ1.epsAtDay()).isEqualByComparingTo("10.00");

        when(financialPeriods.findByFigiOrderByPeriodEndAsc("MULTI_A")).thenReturn(List.of(multiQ2));
        StockValuationService.ReportValuationBundle multiVerifiedQ2Only = service.evaluateForReport(
                multiStock, aug14, aug14Close, aug14Close, "I10");
        assertThat(multiVerifiedQ2Only.marketCapAtDay()).isEqualByComparingTo("5000");

        when(financialPeriods.findByFigiOrderByPeriodEndAsc("MULTI_A")).thenReturn(List.of());
        when(quotes.findByFigiAndQuoteDate("MULTI_A", aug14)).thenReturn(multiAugQuote);
        StockValuationService.ReportValuationBundle multiVerifiedNoPeriod = service.evaluateForReport(
                multiStock, aug14, aug14Close, aug14Close, "I10");
        assertThat(multiVerifiedNoPeriod.marketCapAtDay()).isEqualByComparingTo("5000");

        // 2) 舊季股數不得蓋過較新的合格觀察：8/14 收盤價 50，8/10 已知股數 100，Q2 股數在 9/30 才回填；同時存在 Q1 股數 50 時，前瞻市值仍為 5,000（而非減半成 2,500）
        StockFinancialPeriod q1OldShares = buildPeriod(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31),
                new BigDecimal("3.00"), new BigDecimal("3000"), new BigDecimal("8000"), new BigDecimal("1600"), new BigDecimal("50"));
        q1OldShares.setAvailableAt(Instant.parse("2026-05-01T20:00:00Z"));
        q1OldShares.setEpsTtmAvailableAt(Instant.parse("2026-05-01T20:00:00Z"));
        q1OldShares.setSharesOutstandingAvailableAt(Instant.parse("2026-05-01T20:00:00Z"));

        StockFinancialPeriod q2BackfilledShares = buildPeriod(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("10.00"), new BigDecimal("4000"), new BigDecimal("10000"), new BigDecimal("2000"), new BigDecimal("100"));
        q2BackfilledShares.setAvailableAt(Instant.parse("2026-08-20T20:00:00Z"));
        q2BackfilledShares.setSharesBasisDate(LocalDate.of(2026, 9, 30));
        q2BackfilledShares.setSharesOutstandingAvailableAt(Instant.parse("2026-09-30T20:00:00Z"));
        // 3) 無關 SEC 項目（8/20 可用）推遲整列 secAvailableFromDate = 8/20，但構成 EPS 的 SEC 項目在 7/31 已可用 -> 8/14 查詢仍採用 Q2 EPS = 10.00 而非 Q1 EPS = 3.00
        q2BackfilledShares.setEpsTtmSource("SEC");
        q2BackfilledShares.setEpsTtmAvailableAt(Instant.parse("2026-07-31T20:00:00Z"));
        q2BackfilledShares.setEpsTtmSecAvailableFromDate(LocalDate.of(2026, 7, 31));
        q2BackfilledShares.setSecAvailableFromDate(LocalDate.of(2026, 8, 20));

        StockShareObservation aaplAugObs = new StockShareObservation();
        aaplAugObs.setFigi(FIGI);
        aaplAugObs.setSource("FUTU");
        aaplAugObs.setObservedAt(Instant.parse("2026-08-10T20:00:00Z"));
        aaplAugObs.setIssuedShares(100L);
        aaplAugObs.setOutstandingShares(100L);
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(FIGI, "FUTU", aug14Close))
                .thenReturn(Optional.of(aaplAugObs));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(aug14)))
                .thenReturn(List.of(quote(aug14, "50")));

        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(q2BackfilledShares));
        StockValuationService.ReportValuationBundle q2OnlyBundle = service.evaluateForReport(apple, aug14, aug14Close, aug14Close, "I10");
        assertThat(q2OnlyBundle.marketCapAtDay()).isEqualByComparingTo("5000");
        assertThat(q2OnlyBundle.epsAtDay()).isEqualByComparingTo("10.00");

        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(q1OldShares, q2BackfilledShares));
        StockValuationService.ReportValuationBundle q1PlusQ2Bundle = service.evaluateForReport(apple, aug14, aug14Close, aug14Close, "I10");
        assertThat(q1PlusQ2Bundle.marketCapAtDay()).isEqualByComparingTo("5000");
        assertThat(q1PlusQ2Bundle.epsAtDay()).isEqualByComparingTo("10.00");

        // 驗證 buildPeriodsForStock 正式重建路徑亦會將 SEC EPS 自有披露日獨立寫入 epsTtmSecAvailableFromDate，不受同季較晚 SEC 負債項目干擾
        com.ykm.stock.analytics.model.entity.SecFinancialFact secEpsFact = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        secEpsFact.setCik("0000320193");
        secEpsFact.setTaxonomy("us-gaap");
        secEpsFact.setTag("EarningsPerShareDiluted");
        secEpsFact.setUnit("USD/shares");
        secEpsFact.setPeriodStart(LocalDate.of(2025, 7, 1));
        secEpsFact.setPeriodEnd(LocalDate.of(2026, 6, 30));
        secEpsFact.setFiled(LocalDate.of(2026, 7, 30));
        secEpsFact.setAvailableFromDate(LocalDate.of(2026, 7, 31));
        secEpsFact.setObservedAt(Instant.parse("2026-07-31T12:00:00Z"));
        secEpsFact.setAccession("0000320193-26-000010");
        secEpsFact.setForm("10-K");
        secEpsFact.setReportFiscalYear(2026);
        secEpsFact.setReportFiscalPeriod("FY");
        secEpsFact.setValue(new BigDecimal("10.00"));

        com.ykm.stock.analytics.model.entity.SecFinancialFact lateDebtFact = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        lateDebtFact.setCik("0000320193");
        lateDebtFact.setTaxonomy("us-gaap");
        lateDebtFact.setTag("LongTermDebt");
        lateDebtFact.setUnit("USD");
        lateDebtFact.setPeriodStart(null);
        lateDebtFact.setPeriodEnd(LocalDate.of(2026, 6, 30));
        lateDebtFact.setFiled(LocalDate.of(2026, 8, 19));
        lateDebtFact.setAvailableFromDate(LocalDate.of(2026, 8, 20));
        lateDebtFact.setObservedAt(Instant.parse("2026-08-20T12:00:00Z"));
        lateDebtFact.setAccession("0000320193-26-000011");
        lateDebtFact.setForm("10-K/A");
        lateDebtFact.setValue(new BigDecimal("5000000000"));

        com.ykm.stock.analytics.model.entity.SecFinancialFact sameDaySecShares200 = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        sameDaySecShares200.setCik("0000320193");
        sameDaySecShares200.setTaxonomy("us-gaap");
        sameDaySecShares200.setTag("CommonStockSharesOutstanding");
        sameDaySecShares200.setUnit("shares");
        sameDaySecShares200.setPeriodStart(null);
        sameDaySecShares200.setPeriodEnd(LocalDate.of(2026, 6, 30));
        sameDaySecShares200.setFiled(LocalDate.of(2026, 8, 14));
        sameDaySecShares200.setAvailableFromDate(LocalDate.of(2026, 8, 17));
        sameDaySecShares200.setObservedAt(Instant.parse("2026-08-14T19:30:00Z"));
        sameDaySecShares200.setAccession("0000320193-26-000012");
        sameDaySecShares200.setForm("10-K");
        sameDaySecShares200.setValue(new BigDecimal("200"));

        List<StockFinancialPeriod> built = service.buildPeriodsForStock(
                apple, status, List.of(), List.of(secEpsFact, lateDebtFact, sameDaySecShares200), List.of(), aaplAugObs,
                false, false, false, LocalDate.of(2026, 8, 10), NOW);
        assertThat(built).singleElement().satisfies(row -> {
            assertThat(row.getEpsTtm()).isEqualByComparingTo("10.00");
            assertThat(row.getEpsTtmSecAvailableFromDate()).isEqualTo(LocalDate.of(2026, 7, 31));
            assertThat(row.getSharesSecAvailableFromDate()).isEqualTo(LocalDate.of(2026, 8, 17));
            assertThat(row.getSecAvailableFromDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        });

        // 4) SEC 股數 200 雖在 8/14 入庫（observedAt <= aug14Close），但其生效交易日 availableFromDate 為 8/17 -> 8/14 查詢前瞻市值必須採富途 100 股得 5,000，不得提前變成 10,000
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(built);
        StockValuationService.ReportValuationBundle secSharesBeforeEffectiveDay = service.evaluateForReport(
                apple, aug14, aug14Close, aug14Close, "I10");
        assertThat(secSharesBeforeEffectiveDay.marketCapAtDay()).isEqualByComparingTo("5000");
        assertThat(secSharesBeforeEffectiveDay.epsAtDay()).isEqualByComparingTo("10.00");

        // 5) 缺可用 EPS 的 SEC 定期申報（Q3 原始 10-Q 於 10/29 申報、10/30 生效披露普通股權益 500；後續 11/19 10-Q/A 於 11/20 生效補披露現金 20）：
        //    經正式 buildPeriodsForStock 重建前後，查 11/17 均須排除已公布的 2026Q3、一致選取 2026Q4，90D 均為 INSUFFICIENT_DATA
        LocalDate nov17 = LocalDate.of(2026, 11, 17);
        Instant nov17Close = Instant.parse("2026-11-17T21:00:00Z");
        Instant nov21RebuildAt = Instant.parse("2026-11-21T12:00:00Z");

        com.ykm.stock.analytics.model.entity.SecFinancialFact q3Equity10Q = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        q3Equity10Q.setCik("0000320193");
        q3Equity10Q.setTaxonomy("us-gaap");
        q3Equity10Q.setTag("StockholdersEquity");
        q3Equity10Q.setUnit("USD");
        q3Equity10Q.setPeriodStart(null);
        q3Equity10Q.setPeriodEnd(LocalDate.of(2026, 9, 30));
        q3Equity10Q.setFiled(LocalDate.of(2026, 10, 29));
        q3Equity10Q.setAvailableFromDate(LocalDate.of(2026, 10, 30));
        q3Equity10Q.setObservedAt(Instant.parse("2026-10-30T12:00:00Z"));
        q3Equity10Q.setAccession("0000320193-26-000021");
        q3Equity10Q.setForm("10-Q");
        q3Equity10Q.setReportFiscalYear(2026);
        q3Equity10Q.setReportFiscalPeriod("Q3");
        q3Equity10Q.setValue(new BigDecimal("500"));

        com.ykm.stock.analytics.model.entity.SecFinancialFact q3Cash10QA = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        q3Cash10QA.setCik("0000320193");
        q3Cash10QA.setTaxonomy("us-gaap");
        q3Cash10QA.setTag("CashAndCashEquivalentsAtCarryingValue");
        q3Cash10QA.setUnit("USD");
        q3Cash10QA.setPeriodStart(null);
        q3Cash10QA.setPeriodEnd(LocalDate.of(2026, 9, 30));
        q3Cash10QA.setFiled(LocalDate.of(2026, 11, 19));
        q3Cash10QA.setAvailableFromDate(LocalDate.of(2026, 11, 20));
        q3Cash10QA.setObservedAt(Instant.parse("2026-11-20T12:00:00Z"));
        q3Cash10QA.setAccession("0000320193-26-000022");
        q3Cash10QA.setForm("10-Q/A");
        q3Cash10QA.setReportFiscalYear(2026);
        q3Cash10QA.setReportFiscalPeriod("Q3");
        q3Cash10QA.setValue(new BigDecimal("20"));

        com.ykm.stock.analytics.model.entity.StockEarningsForecast q3Latest = new com.ykm.stock.analytics.model.entity.StockEarningsForecast();
        q3Latest.setFigi(FIGI);
        q3Latest.setFiscalPeriod("2026Q3");
        q3Latest.setPeriodType("QUARTERLY");
        q3Latest.setObservationDate(LocalDate.of(2026, 10, 25));
        q3Latest.setObservedAt(Instant.parse("2026-10-25T12:00:00Z"));
        q3Latest.setCurrency("USD");
        q3Latest.setSource("FUTU");
        q3Latest.setEps(new BigDecimal("1.60"));

        com.ykm.stock.analytics.model.entity.StockEarningsForecast q3Base90 = new com.ykm.stock.analytics.model.entity.StockEarningsForecast();
        q3Base90.setFigi(FIGI);
        q3Base90.setFiscalPeriod("2026Q3");
        q3Base90.setPeriodType("QUARTERLY");
        q3Base90.setObservationDate(LocalDate.of(2026, 7, 28));
        q3Base90.setObservedAt(Instant.parse("2026-07-28T12:00:00Z"));
        q3Base90.setCurrency("USD");
        q3Base90.setSource("FUTU");
        q3Base90.setEps(new BigDecimal("2.00"));

        com.ykm.stock.analytics.model.entity.StockEarningsForecast q4ObsNov16 = new com.ykm.stock.analytics.model.entity.StockEarningsForecast();
        q4ObsNov16.setFigi(FIGI);
        q4ObsNov16.setFiscalPeriod("2026Q4");
        q4ObsNov16.setPeriodType("QUARTERLY");
        q4ObsNov16.setObservationDate(LocalDate.of(2026, 11, 16));
        q4ObsNov16.setObservedAt(Instant.parse("2026-11-16T12:00:00Z"));
        q4ObsNov16.setCurrency("USD");
        q4ObsNov16.setSource("FUTU");
        q4ObsNov16.setEps(new BigDecimal("2.10"));

        ForwardValuationCalculator fwdCalc = new ForwardValuationCalculator();
        List<StockFinancialPeriod> builtBefore10QA = service.buildPeriodsForStock(
                apple, status, List.of(), List.of(q3Equity10Q), List.of(), aaplAugObs,
                false, false, false, LocalDate.of(2026, 8, 10), nov17Close);
        com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics before10QA =
                fwdCalc.calculate(new ForwardValuationCalculator.Inputs(
                        nov17, nov17Close, "USD", new BigDecimal("100"), null, null, null, null,
                        List.of(), null, List.of(q3Latest, q4ObsNov16), List.of(), builtBefore10QA), List.of());
        assertThat(before10QA.revisionFiscalPeriod()).isEqualTo("2026Q4");
        assertThat(before10QA.epsRevision90d().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);

        List<StockFinancialPeriod> builtAfter10QA = service.buildPeriodsForStock(
                apple, status, List.of(), List.of(q3Equity10Q, q3Cash10QA), List.of(), aaplAugObs,
                false, false, false, LocalDate.of(2026, 8, 10), nov21RebuildAt);
        assertThat(builtAfter10QA).singleElement().satisfies(row -> {
            assertThat(row.getSecPeriodicAvailableFromDate()).isEqualTo(LocalDate.of(2026, 10, 30));
            assertThat(row.getSecAvailableFromDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        });
        ForwardValuationCalculator.Inputs after10QAInputs = new ForwardValuationCalculator.Inputs(
                nov17, nov17Close, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(q3Latest, q4ObsNov16), List.of(), builtAfter10QA);
        assertThat(fwdCalc.selectRevisionTarget(after10QAInputs).fiscalPeriod()).isEqualTo("2026Q4");
        com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics after10QA =
                fwdCalc.calculate(after10QAInputs, List.of());
        assertThat(after10QA.revisionFiscalPeriod()).isEqualTo("2026Q4");
        assertThat(after10QA.epsRevision90d().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);

        // 5b) 外國發行人 6-K／6-K/A 具明確當期 FY/Q3 與期末時，同樣識別為已公布財季並選取 2026Q4（不誤發 Q3 的 -20% 下修）
        q3Equity10Q.setForm("6-K");
        q3Cash10QA.setForm("6-K/A");
        List<StockFinancialPeriod> built6K = service.buildPeriodsForStock(
                apple, status, List.of(), List.of(q3Equity10Q, q3Cash10QA), List.of(), aaplAugObs,
                false, false, false, LocalDate.of(2026, 8, 10), nov21RebuildAt);
        assertThat(built6K).hasSize(1);
        ForwardValuationCalculator.Inputs inputs6K = new ForwardValuationCalculator.Inputs(
                nov17, nov17Close, "USD", new BigDecimal("100"), null, null, null, null,
                List.of(), null, List.of(q3Latest, q4ObsNov16), List.of(), built6K);
        assertThat(fwdCalc.selectRevisionTarget(inputs6K).fiscalPeriod()).isEqualTo("2026Q4");
        com.ykm.stock.analytics.model.dto.stockInsight.analysis.FutuObservationsDto.ForwardMetrics metrics6K =
                fwdCalc.calculate(inputs6K, List.of());
        assertThat(metrics6K.revisionFiscalPeriod()).isEqualTo("2026Q4");
        assertThat(metrics6K.epsRevision90d().status()).isEqualTo(MetricStatus.INSUFFICIENT_DATA);

        // 5c) 合格 6-K／6-K/A 保留明示單季起日（缺 Q1 時，Q2 明示 2026-04-01..2026-06-30 不得被拉長成 2026-01-01，查詢 2026-02-02 正確回退至 2025-12-31）
        com.ykm.stock.analytics.model.entity.SecFinancialFact fy25Annual = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        fy25Annual.setCik("0000320193");
        fy25Annual.setTaxonomy("us-gaap");
        fy25Annual.setTag("Revenues");
        fy25Annual.setUnit("USD");
        fy25Annual.setPeriodStart(LocalDate.of(2025, 1, 1));
        fy25Annual.setPeriodEnd(LocalDate.of(2025, 12, 31));
        fy25Annual.setFiled(LocalDate.of(2026, 2, 20));
        fy25Annual.setAvailableFromDate(LocalDate.of(2026, 2, 23));
        fy25Annual.setObservedAt(Instant.parse("2026-02-23T12:00:00Z"));
        fy25Annual.setAccession("0000320193-26-000031");
        fy25Annual.setForm("20-F");
        fy25Annual.setReportFiscalYear(2025);
        fy25Annual.setReportFiscalPeriod("FY");
        fy25Annual.setValue(new BigDecimal("1000"));

        com.ykm.stock.analytics.model.entity.SecFinancialFact q2Rev6K = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        q2Rev6K.setCik("0000320193");
        q2Rev6K.setTaxonomy("us-gaap");
        q2Rev6K.setTag("Revenues");
        q2Rev6K.setUnit("USD");
        q2Rev6K.setPeriodStart(LocalDate.of(2026, 4, 1));
        q2Rev6K.setPeriodEnd(LocalDate.of(2026, 6, 30));
        q2Rev6K.setFiled(LocalDate.of(2026, 8, 10));
        q2Rev6K.setAvailableFromDate(LocalDate.of(2026, 8, 11));
        q2Rev6K.setObservedAt(Instant.parse("2026-08-11T12:00:00Z"));
        q2Rev6K.setAccession("0000320193-26-000032");
        q2Rev6K.setForm("6-K");
        q2Rev6K.setReportFiscalYear(2026);
        q2Rev6K.setReportFiscalPeriod("Q2");
        q2Rev6K.setValue(new BigDecimal("300"));

        List<StockFinancialPeriod> builtWithMissingQ1 = service.buildPeriodsForStock(
                apple, status, List.of(), List.of(fy25Annual, q2Rev6K), List.of(), aaplAugObs,
                false, false, false, LocalDate.of(2026, 8, 10), nov21RebuildAt);
        StockFinancialPeriod q2Built = builtWithMissingQ1.stream()
                .filter(p -> LocalDate.of(2026, 6, 30).equals(p.getPeriodEnd()))
                .findFirst().orElseThrow();
        assertThat(q2Built.getPeriodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        StockFinancialPeriod matchedAtFeb2 = StockValuationService.matchPeriod(builtWithMissingQ1, LocalDate.of(2026, 2, 2));
        assertThat(matchedAtFeb2).isNotNull();
        assertThat(matchedAtFeb2.getPeriodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
    }

    @Test
    void crossIndustryCustomPeerPropagationStaysSingleHopWithoutPerStockMonthEndQueries() {
        TransactionTemplate realTx = new TransactionTemplate(new com.ykm.stock.analytics.support.NoOpTransactionManager());
        StockValuationService txService = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, financialPeriods, latestValuations, customPeers,
                calendar, clock, issuerHistory, realTx, entityManager);

        Stock stockZ = new Stock();
        stockZ.setFigi("Z_FIGI");
        stockZ.setSymbol("ZZZ");
        stockZ.setCik("0000800001");
        stockZ.setCurrency("USD");
        stockZ.setStockType("Common Stock");
        stockZ.setActiveStatus(ActiveStatus.ACTIVE);
        stockZ.setTracked(true);
        StockStatus statusZ = new StockStatus();
        statusZ.setFigi("Z_FIGI");
        statusZ.setQuoteDate(LATEST_DAY);

        Stock stockM = new Stock();
        stockM.setFigi("M_FIGI");
        stockM.setSymbol("MMM");
        stockM.setCik("0000800002");
        stockM.setCurrency("USD");
        stockM.setStockType("Common Stock");
        stockM.setActiveStatus(ActiveStatus.ACTIVE);
        stockM.setTracked(true);
        StockStatus statusM = new StockStatus();
        statusM.setFigi("M_FIGI");
        statusM.setQuoteDate(LATEST_DAY);

        StockIndustry indZ = new StockIndustry();
        indZ.setCode("I_Z");
        StockIndustry indM = new StockIndustry();
        indM.setCode("I_M");
        StockIndustryHistory histZ = new StockIndustryHistory();
        histZ.setFigi("Z_FIGI");
        histZ.setIndustry(indZ);
        StockIndustryHistory histM = new StockIndustryHistory();
        histM.setFigi("M_FIGI");
        histM.setIndustry(indM);

        java.util.Map<String, Stock> allStocks = new java.util.LinkedHashMap<>();
        allStocks.put("Z_FIGI", stockZ);
        allStocks.put("M_FIGI", stockM);
        java.util.Map<String, StockStatus> allStatuses = new java.util.LinkedHashMap<>();
        allStatuses.put("Z_FIGI", statusZ);
        allStatuses.put("M_FIGI", statusM);
        java.util.Map<String, StockValuationLatest> dbLatest = new java.util.LinkedHashMap<>();

        StockValuationLatest snapM = new StockValuationLatest();
        snapM.setFigi("M_FIGI");
        snapM.setValuationDate(LATEST_DAY);
        snapM.setIndustryCode("I_M");
        snapM.setCurrency("USD");
        snapM.setMarketCap(new BigDecimal("20000000000"));
        snapM.setMarketCapTier("MEGA_LARGE");
        snapM.setPe(new BigDecimal("20"));
        snapM.setPeStatus("AVAILABLE");
        snapM.setStatus("AVAILABLE");
        dbLatest.put("M_FIGI", snapM);

        List<Object[]> indMPeerTuples = new ArrayList<>();
        indMPeerTuples.add(new Object[]{"0000800002", snapM});
        for (int i = 1; i <= 25; i++) {
            String uFigi = "U_" + i;
            Stock u = new Stock();
            u.setFigi(uFigi);
            u.setSymbol("U" + i);
            u.setCik(String.format("%010d", 810000 + i));
            u.setCurrency("USD");
            u.setStockType("Common Stock");
            u.setActiveStatus(ActiveStatus.ACTIVE);
            u.setTracked(true);
            allStocks.put(uFigi, u);
            StockStatus us = new StockStatus();
            us.setFigi(uFigi);
            us.setQuoteDate(LATEST_DAY);
            allStatuses.put(uFigi, us);
            StockValuationLatest uSnap = new StockValuationLatest();
            uSnap.setFigi(uFigi);
            uSnap.setValuationDate(LATEST_DAY);
            uSnap.setIndustryCode("I_M");
            uSnap.setCurrency("USD");
            uSnap.setMarketCap(new BigDecimal("20000000000"));
            uSnap.setMarketCapTier("MEGA_LARGE");
            uSnap.setPe(new BigDecimal("18"));
            uSnap.setPeStatus("AVAILABLE");
            uSnap.setStatus("AVAILABLE");
            dbLatest.put(uFigi, uSnap);
            indMPeerTuples.add(new Object[]{u.getCik(), uSnap});
        }

        when(stocks.findAllById(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(allStocks::get).filter(java.util.Objects::nonNull).toList();
        });
        when(statuses.findAllById(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(allStatuses::get).filter(java.util.Objects::nonNull).toList();
        });
        when(statuses.findAllByIdForUpdate(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(allStatuses::get).filter(java.util.Objects::nonNull).toList();
        });
        when(industries.findByFigisAt(anyCollection(), any())).thenReturn(List.of(histZ, histM));
        when(latestValuations.findByFigiIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(dbLatest::get).filter(java.util.Objects::nonNull).toList();
        });
        when(latestValuations.findActiveTrackedFigisByIndustryCodesAndDates(anyCollection(), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> inds = inv.getArgument(0);
            return dbLatest.values().stream()
                    .filter(v -> inds.contains(v.getIndustryCode()))
                    .map(StockValuationLatest::getFigi)
                    .toList();
        });
        when(latestValuations.findActiveTrackedPeerSnapshotsIn(anyCollection(), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> inds = inv.getArgument(0);
            return inds.contains("I_M") ? indMPeerTuples : List.of();
        });
        when(customPeers.findEligibleTargetFigisByPeerFigiIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> peers = inv.getArgument(0);
            return peers.contains("Z_FIGI") ? List.of("M_FIGI") : List.of();
        });
        StockCustomPeer mRefZ = new StockCustomPeer("M_FIGI", "Z_FIGI", "PE", 0, NOW);
        when(customPeers.findByFigiInOrderByFigiAscSortOrderAsc(anyCollection())).thenReturn(List.of(mRefZ));

        StockFinancialPeriod pZ = buildPeriod(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("5.00"), new BigDecimal("5000000000"), new BigDecimal("10000000000"), new BigDecimal("2000000000"), new BigDecimal("1000000000"));
        pZ.setFigi("Z_FIGI");
        StockFinancialPeriod pM = buildPeriod(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("5.00"), new BigDecimal("5000000000"), new BigDecimal("10000000000"), new BigDecimal("2000000000"), new BigDecimal("1000000000"));
        pM.setFigi("M_FIGI");
        when(financialPeriods.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(List.of(pZ, pM));
        when(financialPeriods.findPeriodSignaturesByFigiIn(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            List<com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository.FigiPeriodSignature> sigs = new ArrayList<>();
            if (ids.contains("Z_FIGI")) {
                sigs.add(new com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository.FigiPeriodSignature("Z_FIGI", 1L, pZ.getUpdatedAt()));
            }
            if (ids.contains("M_FIGI")) {
                sigs.add(new com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository.FigiPeriodSignature("M_FIGI", 1L, pM.getUpdatedAt()));
            }
            return sigs;
        });

        StockQuote qZ = new StockQuote();
        qZ.setFigi("Z_FIGI");
        qZ.setQuoteDate(LATEST_DAY);
        qZ.setClosePrice(new BigDecimal("50"));
        StockQuote qM = new StockQuote();
        qM.setFigi("M_FIGI");
        qM.setQuoteDate(LATEST_DAY);
        qM.setClosePrice(new BigDecimal("100"));
        when(quotes.findStoredQuotes(anyCollection(), eq(LATEST_DAY))).thenReturn(List.of(qZ, qM));
        java.util.List<java.util.List<String>> monthEndCallFigis = new ArrayList<>();
        when(quotes.findMonthEndQuotes(anyCollection(), any(), eq(LATEST_DAY))).thenAnswer(inv -> {
            java.util.Collection<String> requested = inv.getArgument(0);
            monthEndCallFigis.add(new ArrayList<>(requested));
            return List.of();
        });

        java.util.Set<String> writtenFigis = new java.util.LinkedHashSet<>();
        org.mockito.Mockito.doAnswer(inv -> {
            StockValuationLatest arg = inv.getArgument(0);
            writtenFigis.add(arg.getFigi());
            return null;
        }).when(entityManager).persist(any(StockValuationLatest.class));
        org.mockito.Mockito.doAnswer(inv -> {
            StockValuationLatest arg = inv.getArgument(0);
            writtenFigis.add(arg.getFigi());
            return arg;
        }).when(entityManager).merge(any(StockValuationLatest.class));

        txService.refreshLatestValuations(List.of("Z_FIGI"), null);

        // 僅評估直接刷新的 Z_FIGI 與一層自訂引用者 M_FIGI（共 2 次查詢），25 檔無關同業 U_1..U_25 零次歷史行情查詢、零次寫入
        assertThat(monthEndCallFigis).containsExactly(List.of("Z_FIGI"), List.of("M_FIGI"));
        assertThat(writtenFigis).containsExactlyInAnyOrder("Z_FIGI", "M_FIGI");

        // 驗證精確 (industryCode, valuationDate) 配對：同時刷新 (I_X, 11/12) 與 (I_Y, 11/13) 時，(I_X, 11/13) 與 (I_Y, 11/12) 的無關股票不被鎖定或重評
        LocalDate nov12 = LocalDate.of(2026, 11, 12);

        Stock dx = new Stock();
        dx.setFigi("DX_16");
        dx.setSymbol("DX16");
        dx.setCik("0000820001");
        dx.setCurrency("USD");
        dx.setStockType("Common Stock");
        dx.setActiveStatus(ActiveStatus.ACTIVE);
        dx.setTracked(true);
        StockStatus stDx = new StockStatus();
        stDx.setFigi("DX_16");
        stDx.setQuoteDate(nov12);

        Stock dy = new Stock();
        dy.setFigi("DY_17");
        dy.setSymbol("DY17");
        dy.setCik("0000820002");
        dy.setCurrency("USD");
        dy.setStockType("Common Stock");
        dy.setActiveStatus(ActiveStatus.ACTIVE);
        dy.setTracked(true);
        StockStatus stDy = new StockStatus();
        stDy.setFigi("DY_17");
        stDy.setQuoteDate(LATEST_DAY);

        Stock crossX17 = new Stock();
        crossX17.setFigi("CROSS_X_17");
        crossX17.setSymbol("CX17");
        crossX17.setCik("0000820003");
        crossX17.setCurrency("USD");
        crossX17.setStockType("Common Stock");
        crossX17.setActiveStatus(ActiveStatus.ACTIVE);
        crossX17.setTracked(true);
        StockStatus stCrossX17 = new StockStatus();
        stCrossX17.setFigi("CROSS_X_17");
        stCrossX17.setQuoteDate(LATEST_DAY);
        StockValuationLatest snapCrossX17 = new StockValuationLatest();
        snapCrossX17.setFigi("CROSS_X_17");
        snapCrossX17.setValuationDate(LATEST_DAY);
        snapCrossX17.setIndustryCode("I_X");
        snapCrossX17.setStatus("AVAILABLE");

        Stock crossY16 = new Stock();
        crossY16.setFigi("CROSS_Y_16");
        crossY16.setSymbol("CY16");
        crossY16.setCik("0000820004");
        crossY16.setCurrency("USD");
        crossY16.setStockType("Common Stock");
        crossY16.setActiveStatus(ActiveStatus.ACTIVE);
        crossY16.setTracked(true);
        StockStatus stCrossY16 = new StockStatus();
        stCrossY16.setFigi("CROSS_Y_16");
        stCrossY16.setQuoteDate(nov12);
        StockValuationLatest snapCrossY16 = new StockValuationLatest();
        snapCrossY16.setFigi("CROSS_Y_16");
        snapCrossY16.setValuationDate(nov12);
        snapCrossY16.setIndustryCode("I_Y");
        snapCrossY16.setStatus("AVAILABLE");

        allStocks.put("DX_16", dx);
        allStocks.put("DY_17", dy);
        allStocks.put("CROSS_X_17", crossX17);
        allStocks.put("CROSS_Y_16", crossY16);
        allStatuses.put("DX_16", stDx);
        allStatuses.put("DY_17", stDy);
        allStatuses.put("CROSS_X_17", stCrossX17);
        allStatuses.put("CROSS_Y_16", stCrossY16);
        dbLatest.put("CROSS_X_17", snapCrossX17);
        dbLatest.put("CROSS_Y_16", snapCrossY16);

        StockIndustry indX = new StockIndustry();
        indX.setCode("I_X");
        StockIndustry indY = new StockIndustry();
        indY.setCode("I_Y");
        StockIndustryHistory hDx = new StockIndustryHistory();
        hDx.setFigi("DX_16");
        hDx.setIndustry(indX);
        StockIndustryHistory hDy = new StockIndustryHistory();
        hDy.setFigi("DY_17");
        hDy.setIndustry(indY);
        when(industries.findByFigisAt(anyCollection(), any())).thenReturn(List.of(hDx, hDy));
        when(latestValuations.findActiveTrackedFigisByIndustryCodesAndDates(anyCollection(), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<String> inds = inv.getArgument(0);
            java.util.Collection<LocalDate> dates = inv.getArgument(1);
            return dbLatest.values().stream()
                    .filter(v -> inds.contains(v.getIndustryCode()) && dates.contains(v.getValuationDate()))
                    .map(StockValuationLatest::getFigi)
                    .toList();
        });

        StockQuote qDx = new StockQuote();
        qDx.setFigi("DX_16");
        qDx.setQuoteDate(nov12);
        qDx.setClosePrice(new BigDecimal("50"));
        StockQuote qDy = new StockQuote();
        qDy.setFigi("DY_17");
        qDy.setQuoteDate(LATEST_DAY);
        qDy.setClosePrice(new BigDecimal("60"));
        when(quotes.findStoredQuotes(anyCollection(), eq(nov12))).thenReturn(List.of(qDx));
        when(quotes.findStoredQuotes(anyCollection(), eq(LATEST_DAY))).thenReturn(List.of(qDy));

        monthEndCallFigis.clear();
        writtenFigis.clear();
        when(quotes.findMonthEndQuotes(anyCollection(), any(), any())).thenAnswer(inv -> {
            java.util.Collection<String> requested = inv.getArgument(0);
            monthEndCallFigis.add(new ArrayList<>(requested));
            return List.of();
        });

        txService.refreshLatestValuations(List.of("DX_16", "DY_17"), null);
        assertThat(monthEndCallFigis).containsExactly(List.of("DX_16"), List.of("DY_17"));
        assertThat(writtenFigis).containsExactlyInAnyOrder("DX_16", "DY_17");
    }

    private Object[] tieredPeerTuple(String issuerKey, String figi, String marketCap, String tier,
                                     String pe, String pb, String revGrowth, String roe, String fcfMargin) {
        StockValuationLatest row = new StockValuationLatest();
        row.setFigi(figi);
        row.setValuationDate(LATEST_DAY);
        row.setIndustryCode("I10");
        row.setModelCode("NON_FINANCIAL_OPERATING");
        row.setCurrency("USD");
        row.setMarketCap(new BigDecimal(marketCap));
        row.setMarketCapTier(tier);
        row.setStatus("AVAILABLE");
        row.setPe(new BigDecimal(pe));
        row.setPeStatus("AVAILABLE");
        row.setPb(new BigDecimal(pb));
        row.setPbStatus("AVAILABLE");
        row.setRevenueGrowthYoy(new BigDecimal(revGrowth));
        row.setRoe(new BigDecimal(roe));
        row.setFcfMargin(new BigDecimal(fcfMargin));
        return new Object[]{issuerKey, row};
    }

    private Object[] peerTuple(String issuerKey, String figi, String pe) {
        return peerTupleOnDate(issuerKey, figi, LATEST_DAY, pe);
    }

    private Object[] peerTupleOnDate(String issuerKey, String figi, LocalDate valuationDate, String pe) {
        StockValuationLatest row = new StockValuationLatest();
        row.setFigi(figi);
        row.setValuationDate(valuationDate);
        row.setIndustryCode("I78");
        row.setStatus("AVAILABLE");
        row.setPe(new BigDecimal(pe));
        row.setPeStatus("AVAILABLE");
        return new Object[]{issuerKey, row};
    }

    private StockQuote quote(LocalDate date, String close) {
        StockQuote q = new StockQuote();
        q.setFigi(FIGI);
        q.setQuoteDate(date);
        q.setClosePrice(new BigDecimal(close));
        return q;
    }

    private StockFinancialPeriod buildPeriod(LocalDate start, LocalDate end, BigDecimal eps, BigDecimal equity,
                                             BigDecimal revenue, BigDecimal ebitda, BigDecimal shares) {
        StockFinancialPeriod p = new StockFinancialPeriod();
        p.setFigi(FIGI);
        p.setPeriodStart(start);
        p.setPeriodEnd(end);
        p.setReportingCurrency("USD");
        p.setAccountingStandards("US_GAAP");
        p.setAvailableAt(LATEST_CLOSE.minusSeconds(3600));

        p.setEpsTtm(eps);
        p.setEpsTtmStatus(MetricStatus.AVAILABLE.name());
        p.setEpsShareBasisDate(end);
        p.setEpsTtmPeriodStart(start);

        p.setCommonEquity(equity);
        p.setCommonEquityStatus(MetricStatus.AVAILABLE.name());

        p.setRevenueTtm(revenue);
        p.setRevenueTtmStatus(MetricStatus.AVAILABLE.name());
        p.setRevenueTtmPeriodStart(start);

        p.setFreeCashFlowTtm(new BigDecimal("150"));
        p.setFreeCashFlowTtmStatus(MetricStatus.AVAILABLE.name());
        p.setFreeCashFlowTtmPeriodStart(start);

        p.setEbitdaTtm(ebitda);
        p.setEbitdaTtmStatus(MetricStatus.AVAILABLE.name());
        p.setEbitdaTtmPeriodStart(start);

        p.setEvAdjustment(BigDecimal.ZERO);
        p.setEvAdjustmentStatus(MetricStatus.AVAILABLE.name());

        p.setSharesOutstanding(shares);
        p.setSharesOutstandingStatus(MetricStatus.AVAILABLE.name());
        p.setSharesBasisDate(end);
        p.setSharesOutstandingAvailableAt(LATEST_CLOSE.minusSeconds(3600));
        p.setSecShareAccession("0000320193-26-000001");
        p.setBookValuePerShareStatus(MetricStatus.AVAILABLE.name());
        p.setNetIncomeCommonTtmStatus(MetricStatus.INSUFFICIENT_DATA.name());
        p.setNetIncomeCommonTtmReason(ValuationReason.NET_INCOME_COMMON_MISSING);
        p.setOperatingCashFlowTtmStatus(MetricStatus.INSUFFICIENT_DATA.name());
        p.setCapitalExpenditureTtmStatus(MetricStatus.INSUFFICIENT_DATA.name());
        p.setTotalDebtStatus(MetricStatus.INSUFFICIENT_DATA.name());
        p.setCashLikeAssetsStatus(MetricStatus.INSUFFICIENT_DATA.name());
        p.setPreferredEquityStatus(MetricStatus.AVAILABLE.name());
        p.setPreferredEquity(BigDecimal.ZERO);
        p.setNoncontrollingInterestStatus(MetricStatus.AVAILABLE.name());
        p.setNoncontrollingInterest(BigDecimal.ZERO);
        return p;
    }
}
