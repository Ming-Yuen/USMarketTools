package com.ykm.stock.analytics.service.valuation;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationReferenceDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
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
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final SecIssuerHistory issuerHistory = mock(SecIssuerHistory.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private StockValuationService service;
    private Stock apple;
    private StockStatus status;

    @BeforeEach
    void setUp() {
        service = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, financialPeriods, latestValuations,
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
        when(issuerHistory.cikAt(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(stocks.findMultiSecurityIssuers(anyCollection(), eq(ActiveStatus.ACTIVE))).thenReturn(Set.of());
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of());
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
        // 僅計入 10 個正 PE 月份，2 個負 EPS 月份絕不切換 EV/EBITDA 混入
        assertThat(hist.sampleCount()).isEqualTo(10);
        // [10, 12, 14, 16, 18, 20, 22, 24, 26, 28]: P20 = 13.6000, P50 = 19.0000, P80 = 24.4000
        assertThat(hist.multipleLow()).isEqualByComparingTo("13.6000");
        assertThat(hist.multipleMedian()).isEqualByComparingTo("19.0000");
        assertThat(hist.multipleHigh()).isEqualByComparingTo("24.4000");
        // 當日 EPS = 10.00 -> priceLow = 136.0000, priceHigh = 244.0000
        assertThat(hist.priceLow()).isEqualByComparingTo("136.0000");
        assertThat(hist.priceHigh()).isEqualByComparingTo("244.0000");
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
    void peerReferenceDeduplicatesDualClassCikAndRequiresAtLeastFiveDistinctIssuers() {
        StockFinancialPeriod period = buildPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30),
                new BigDecimal("8.00"), new BigDecimal("500"), new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("100"));
        when(financialPeriods.findByFigiOrderByPeriodEndAsc(FIGI)).thenReturn(List.of(period));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(FIGI), any(), eq(LATEST_DAY)))
                .thenReturn(List.of(quote(LATEST_DAY, "200")));

        // 僅 4 家不同發行人（其中 GOOGL + GOOG 共用同一 CIK，共 5 隻股票但僅 4 家公司） -> INSUFFICIENT_DATA
        List<Object[]> fourIssuersFiveStocks = List.of(
                peerTuple("0001652044", "GOOGL", "15.0000"),
                peerTuple("0001652044", "GOOG", "17.0000"),
                peerTuple("0001326801", "META", "20.0000"),
                peerTuple("0001018724", "AMZN", "25.0000"),
                peerTuple("0001065280", "NFLX", "30.0000")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I78")).thenReturn(fourIssuersFiveStocks);

        ReportValuationBundle insufficient = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I78");
        assertThat(insufficient.valuation().peerReference().status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(insufficient.valuation().peerReference().reasonCode()).isEqualTo("INDUSTRY_PEER_COMPANIES_INSUFFICIENT");

        // 補上第 5 家獨立發行人 -> 5 家有效發行人，GOOGL/GOOG 中位數為 16.0000 -> [16, 20, 25, 30, 35]
        List<Object[]> fiveIssuersSixStocks = List.of(
                peerTuple("0001652044", "GOOGL", "15.0000"),
                peerTuple("0001652044", "GOOG", "17.0000"),
                peerTuple("0001326801", "META", "20.0000"),
                peerTuple("0001018724", "AMZN", "25.0000"),
                peerTuple("0001065280", "NFLX", "30.0000"),
                peerTuple("0000320193", FIGI, "35.0000")
        );
        when(latestValuations.findActiveTrackedPeerSnapshots("I78")).thenReturn(fiveIssuersSixStocks);

        ReportValuationBundle available = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I78");
        ValuationReferenceDto peer = available.valuation().peerReference();
        assertThat(peer.status()).isEqualTo("AVAILABLE");
        assertThat(peer.sampleCount()).isEqualTo(5);
        // [16, 20, 25, 30, 35]: P25 = 20.0000, P50 = 25.0000, P75 = 30.0000; EPS = 8.00 -> price 160.0000..240.0000
        assertThat(peer.multipleLow()).isEqualByComparingTo("20.0000");
        assertThat(peer.multipleMedian()).isEqualByComparingTo("25.0000");
        assertThat(peer.multipleHigh()).isEqualByComparingTo("30.0000");
        assertThat(peer.priceLow()).isEqualByComparingTo("160.0000");
        assertThat(peer.priceHigh()).isEqualByComparingTo("240.0000");
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

        // 1) 最新收市日查詢：industryAssumption 生效並換算假設價格 176.0000–224.0000
        ReportValuationBundle latestBundle = service.evaluateForReport(apple, LATEST_DAY, LATEST_CLOSE, NOW, "I10");
        ValuationReferenceDto latestAssumption = latestBundle.valuation().industryAssumption();
        assertThat(latestAssumption.status()).isEqualTo("AVAILABLE");
        assertThat(latestAssumption.metricCode()).isEqualTo("PE");
        assertThat(latestAssumption.multipleLow()).isEqualByComparingTo("22.0000");
        assertThat(latestAssumption.multipleHigh()).isEqualByComparingTo("28.0000");
        assertThat(latestAssumption.priceLow()).isEqualByComparingTo("176.0000");
        assertThat(latestAssumption.priceHigh()).isEqualByComparingTo("224.0000");

        // 2) 指定歷史日期查詢（2025-06-30 < 2026-11-13）：peerReference 與 industryAssumption 一律標 NOT_APPLICABLE，不讀同業快照
        ReportValuationBundle histBundle = service.evaluateForReport(apple, histDay, histClose, histClose, "I10");
        assertThat(histBundle.valuation().historicalReference().status()).isEqualTo("AVAILABLE");
        assertThat(histBundle.valuation().historicalReference().sampleCount()).isEqualTo(7);
        assertThat(histBundle.valuation().peerReference().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(histBundle.valuation().peerReference().reasonCode()).isEqualTo("HISTORICAL_DATE_NOT_APPLICABLE");
        assertThat(histBundle.valuation().industryAssumption().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(histBundle.valuation().industryAssumption().reasonCode()).isEqualTo("HISTORICAL_DATE_NOT_APPLICABLE");
        verify(latestValuations, never()).findActiveTrackedPeerSnapshots("I10_HIST");
    }

    private Object[] peerTuple(String issuerKey, String figi, String pe) {
        StockValuationLatest row = new StockValuationLatest();
        row.setFigi(figi);
        row.setValuationDate(LATEST_DAY);
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
