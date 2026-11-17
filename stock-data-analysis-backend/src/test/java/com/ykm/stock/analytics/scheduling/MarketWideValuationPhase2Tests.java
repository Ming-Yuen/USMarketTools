package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecEdgarFeedArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportItemMappingRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.IndustryValuationConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockShareObservationRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockValuationDailyRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.support.NoOpTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketWideValuationPhase2Tests {
    private static final LocalDate LAST_CLOSED = LocalDate.of(2026, 11, 12);
    private static final LocalDate HIST_DAY = LocalDate.of(2026, 6, 15);
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    private static final Instant CLOSE = Instant.parse("2026-11-12T21:00:00Z");
    private static final Instant HIST_CLOSE = Instant.parse("2026-06-15T20:00:00Z");

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final FinancialReportItemMappingRepository mappings = mock(FinancialReportItemMappingRepository.class);
    private final StockValuationDailyRepository valuations = mock(StockValuationDailyRepository.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final StockShareObservationRepository shareObservations = mock(StockShareObservationRepository.class);
    private final FutuService futu = mock(FutuService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final IndustryValuationConfigRepository industryConfigs = mock(IndustryValuationConfigRepository.class);
    private final List<StockValuationDaily> savedValuations = new ArrayList<>();
    private final List<StockShareObservation> savedObservations = new ArrayList<>();

    private StockValuationDailyTask task;

    @BeforeEach
    void setUp() {
        task = new StockValuationDailyTask(stocks, statuses, quotes, splits, reports, mappings, valuations,
                new TransactionTemplate(new NoOpTransactionManager()), clock, calendar, shareObservations, futu,
                entityManager, secFacts, industries, mock(SecFinancialStatementArchive.class),
                mock(SecEdgarFeedArchive.class), new SecIssuerHistory(""), industryConfigs);
        when(clock.now()).thenReturn(NOW);
        when(clock.nowLocalDateTime()).thenReturn(LocalDateTime.of(2026, 11, 13, 16, 0));
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getNextTradingDay(any())).thenAnswer(inv -> ((LocalDate) inv.getArgument(0)).plusDays(1));
        when(calendar.getLastClosedTradingDay(NOW)).thenReturn(LAST_CLOSED);
        when(calendar.getSessionCloseAt(LAST_CLOSED)).thenReturn(CLOSE);
        when(calendar.getSessionOpenAt(LAST_CLOSED)).thenReturn(CLOSE.minusSeconds(6 * 3600));
        when(calendar.getSessionCloseAt(HIST_DAY)).thenReturn(HIST_CLOSE);
        when(calendar.getSessionOpenAt(HIST_DAY)).thenReturn(HIST_CLOSE.minusSeconds(6 * 3600));
        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof StockValuationDaily row) savedValuations.add(row);
            if (arg instanceof StockShareObservation obs) savedObservations.add(obs);
            return null;
        }).when(entityManager).persist(any());
        doAnswer(invocation -> {
            StockValuationDaily row = invocation.getArgument(0);
            savedValuations.add(row);
            return row;
        }).when(entityManager).merge(any(StockValuationDaily.class));
    }

    @Test
    void singleListedClassAUsesOutstandingSharesWithoutNamedShareClassBlock() {
        ActiveStockWithStatus meta = target("BBG000MM2P62", "META", "0001326801", "Meta Platforms, Inc. Class A Common Stock", "7370");
        meta.status().setSplitFromDate(null);
        meta.status().setSplitDate(null);
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, HIST_DAY)).thenReturn(List.of(meta));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(meta.status()));
        when(quotes.findStoredQuotes(anyCollection(), eq(HIST_DAY))).thenReturn(List.of(quote("BBG000MM2P62", HIST_DAY, "500")));
        StockShareObservation obs = new StockShareObservation();
        obs.setFigi("BBG000MM2P62");
        obs.setObservedAt(HIST_CLOSE.minusSeconds(3600));
        obs.setIssuedShares(2700000000L);
        obs.setOutstandingShares(2500000000L);
        obs.setSource("FUTU");
        when(shareObservations.findLatestAt(anyCollection(), eq(HIST_CLOSE))).thenReturn(List.of(obs));
        when(reports.findValuationReports(anyCollection(), eq(HIST_DAY), eq(NOW))).thenReturn(
                operatingAnnualReports("BBG000MM2P62", "US_GAAP", "2025-12-31", "20", "125000000000", "250000000000", "50000000000", "60000000000", "15000000000", "20000000000", "40000000000"));

        task.execute(context(HIST_DAY));

        assertThat(savedValuations).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("25");
            // MarketCap = 500 * 2.5B (outstandingShares, not 2.7B issuedShares) = 1.25T; PB = 1.25T / 125B = 10
            assertThat(row.getPb()).isEqualByComparingTo("10");
            assertThat(row.getPs()).isEqualByComparingTo("5");
            assertThat(row.getPfcf()).isEqualByComparingTo("25");
            assertThat(row.getEvEbitda()).isNotNull();
        });
    }

    @Test
    void multiSecurityOneToOneGooglComputesHistoricalValuationWhileNonOneToOneBrkBMarksUnverifiedHistorically() {
        // 1) GOOGL + GOOG（1:1 經濟權，等值股數 12.294B 與 SEC 申報 12.294B 誤差 0% <= 2%）
        ActiveStockWithStatus googl = target("GOOGL_FIGI", "GOOGL", "0001652044", "Alphabet Inc. Class A", "7370");
        ActiveStockWithStatus brkB = target("BRKB_FIGI", "BRK.B", "0001067983", "Berkshire Hathaway Inc. Class B", "6331");
        when(stocks.findMultiSecurityIssuers(anyCollection(), eq(ActiveStatus.ACTIVE)))
                .thenReturn(Set.of("0001652044", "0001067983"));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(googl.status(), brkB.status()));

        StockShareObservation googlLatest = new StockShareObservation();
        googlLatest.setFigi("GOOGL_FIGI");
        googlLatest.setObservedAt(CLOSE.plusSeconds(60));
        googlLatest.setIssuedShares(12294000000L);
        googlLatest.setOutstandingShares(12294000000L);
        googlLatest.setSnapshotPrice(new BigDecimal("200"));
        googlLatest.setOutstandingMarketCap(new BigDecimal("2458800000000"));
        googlLatest.setSnapshotMarketCap(new BigDecimal("2458800000000"));
        googlLatest.setSnapshotQuoteTime(CLOSE);
        googlLatest.setSource("FUTU");

        StockShareObservation brkLatest = new StockShareObservation();
        brkLatest.setFigi("BRKB_FIGI");
        brkLatest.setObservedAt(CLOSE.plusSeconds(60));
        brkLatest.setIssuedShares(2124519594L);
        brkLatest.setOutstandingShares(2124519594L);
        brkLatest.setSnapshotPrice(new BigDecimal("500"));
        brkLatest.setOutstandingMarketCap(new BigDecimal("1062259797000"));
        brkLatest.setSnapshotMarketCap(new BigDecimal("1062259797000"));
        brkLatest.setSnapshotQuoteTime(CLOSE);
        brkLatest.setSource("FUTU");

        SecFinancialFact googlSecShares = secShareFact("0001652044", "2026-03-31", "2026-04-25", "12300000000");
        when(secFacts.findLatestIssuerShareFacts(anyCollection(), eq(NOW))).thenReturn(List.of(googlSecShares));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), any(), eq(NOW))).thenReturn(List.of(googlSecShares));
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(googlLatest, brkLatest));

        // A. 最新收市日：GOOGL 與 BRK.B 均採用富途對應等值總市值算出最新估值，BRK.B 保險業 P/FCF 與 EV/EBITDA 為 NOT_APPLICABLE
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, LAST_CLOSED)).thenReturn(List.of(googl, brkB));
        when(quotes.findStoredQuotes(anyCollection(), eq(LAST_CLOSED)))
                .thenReturn(List.of(quote("GOOGL_FIGI", LAST_CLOSED, "200"), quote("BRKB_FIGI", LAST_CLOSED, "500")));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), eq(CLOSE), eq(NOW)))
                .thenReturn(List.of(googlLatest, brkLatest));
        List<FinancialReport> allReports = new ArrayList<>();
        allReports.addAll(operatingAnnualReports("GOOGL_FIGI", "US_GAAP", "2025-12-31", "10", "300000000000", "350000000000", "70000000000", "100000000000", "20000000000", "15000000000", "90000000000"));
        allReports.addAll(operatingAnnualReports("BRKB_FIGI", "US_GAAP", "2025-12-31", "25", "531129898500", "370000000000", "30000000000", "40000000000", "10000000000", "120000000000", "45000000000"));
        when(reports.findValuationReports(anyCollection(), any(), eq(NOW))).thenReturn(allReports);

        task.execute(context(LAST_CLOSED));

        StockValuationDaily googlLatestRow = savedValuations.stream().filter(r -> "GOOGL_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        StockValuationDaily brkLatestRow = savedValuations.stream().filter(r -> "BRKB_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        assertThat(googlLatestRow.getPe()).isEqualByComparingTo("20");
        assertThat(googlLatestRow.getPb()).isNotNull();
        assertThat(brkLatestRow.getPe()).isEqualByComparingTo("20");
        assertThat(brkLatestRow.getPb()).isEqualByComparingTo("2");
        assertThat(brkLatestRow.getPfcfReason()).isEqualTo("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
        assertThat(brkLatestRow.getEvEbitdaReason()).isEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");

        // B. 歷史交易日：GOOGL 通過 <=2% 驗證並採用 SEC 申報總股數；BRK.B 未通過 1:1 驗證，市值倍數標為 SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED（但保留同股類單位之 P/E）
        savedValuations.clear();
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, HIST_DAY)).thenReturn(List.of(googl, brkB));
        when(quotes.findStoredQuotes(anyCollection(), eq(HIST_DAY)))
                .thenReturn(List.of(quote("GOOGL_FIGI", HIST_DAY, "180"), quote("BRKB_FIGI", HIST_DAY, "450")));
        when(shareObservations.findLatestAt(anyCollection(), eq(HIST_CLOSE))).thenReturn(List.of());

        task.execute(context(HIST_DAY));

        StockValuationDaily googlHistRow = savedValuations.stream().filter(r -> "GOOGL_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        StockValuationDaily brkHistRow = savedValuations.stream().filter(r -> "BRKB_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        assertThat(googlHistRow.getPb()).isNotNull();
        assertThat(googlHistRow.getPs()).isNotNull();
        assertThat(brkHistRow.getPe()).isEqualByComparingTo("18");
        assertThat(brkHistRow.getPb()).isNull();
        assertThat(brkHistRow.getPbReason()).isEqualTo("SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED");
    }

    @Test
    void bankConfigAndIfrsCrossNationalAndForeignCurrencyBehaveDeterministically() {
        ActiveStockWithStatus jpm = target("JPM_FIGI", "JPM", "0000019617", "JPMorgan Chase & Co.", "6021");
        ActiveStockWithStatus gold = target("GOLD_FIGI", "GOLD", null, "Barrick Mining Corp", "1040");
        ActiveStockWithStatus tsm = target("TSM_FIGI", "TSM", null, "TSMC", "3674");
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, HIST_DAY)).thenReturn(List.of(jpm, gold, tsm));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(jpm.status(), gold.status(), tsm.status()));
        when(quotes.findStoredQuotes(anyCollection(), eq(HIST_DAY))).thenReturn(List.of(
                quote("JPM_FIGI", HIST_DAY, "200"), quote("GOLD_FIGI", HIST_DAY, "20"), quote("TSM_FIGI", HIST_DAY, "180")));

        StockIndustry bankInd = new StockIndustry();
        bankInd.setCode("I100");
        bankInd.setSourcePlateCode("US.LIST2481");
        StockIndustryHistory jpmHist = new StockIndustryHistory();
        jpmHist.setFigi("JPM_FIGI");
        jpmHist.setIndustry(bankInd);
        when(industries.findByFigisAt(anyCollection(), eq(HIST_CLOSE))).thenReturn(List.of(jpmHist));
        IndustryValuationConfig bankCfg = new IndustryValuationConfig();
        bankCfg.setIndustryCode("I100");
        bankCfg.setModelCode("BANK");
        bankCfg.setPrimaryMetric("PB");
        when(industryConfigs.findByIndustryCodesAt(anyCollection(), eq(HIST_CLOSE))).thenReturn(List.of(bankCfg));

        StockShareObservation jpmObs = simpleObs("JPM_FIGI", 2800000000L);
        StockShareObservation goldObs = simpleObs("GOLD_FIGI", 1750000000L);
        StockShareObservation tsmObs = simpleObs("TSM_FIGI", 5186000000L);
        when(shareObservations.findLatestAt(anyCollection(), eq(HIST_CLOSE))).thenReturn(List.of(jpmObs, goldObs, tsmObs));

        List<FinancialReport> batchReports = new ArrayList<>();
        // JPM：含 20B 優先股，普通股權益 = 340B - 20B = 320B
        FinancialReport jpmInc = new FinancialReport();
        jpmInc.setFigi("JPM_FIGI");
        jpmInc.setStatementType(1);
        jpmInc.setPeriodType(7);
        jpmInc.setFiscalYear(2025);
        jpmInc.setPeriodEnd(LocalDate.of(2025, 12, 31));
        jpmInc.setCurrency("USD");
        jpmInc.setAccountingStandards("US_GAAP");
        jpmInc.setObservedAt(HIST_CLOSE.minusSeconds(60));
        jpmInc.setItems(List.of(new FinancialReport.Item(9051L, "Diluted EPS", new BigDecimal("16"), null, null),
                new FinancialReport.Item(9001L, "Total Revenue as Reported", new BigDecimal("160000000000"), null, null)));
        FinancialReport jpmBal = new FinancialReport();
        jpmBal.setFigi("JPM_FIGI");
        jpmBal.setStatementType(2);
        jpmBal.setPeriodType(7);
        jpmBal.setFiscalYear(2025);
        jpmBal.setPeriodEnd(LocalDate.of(2025, 12, 31));
        jpmBal.setCurrency("USD");
        jpmBal.setAccountingStandards("US_GAAP");
        jpmBal.setObservedAt(HIST_CLOSE.minusSeconds(60));
        jpmBal.setItems(List.of(new FinancialReport.Item(9070L, "Stockholders' Equity", new BigDecimal("340000000000"), null, null),
                new FinancialReport.Item(9073L, "-Preferred Stock", new BigDecimal("20000000000"), null, null)));
        batchReports.add(jpmInc);
        batchReports.add(jpmBal);

        // GOLD：NonUS_GAAP（IFRS）同幣別 USD，EV 納入全部租賃負債
        batchReports.addAll(operatingAnnualReports("GOLD_FIGI", "NonUS_GAAP", "2025-12-31", "1.25", "25000000000", "14000000000", "2000000000", "4000000000", "1500000000", "5500000000", "4000000000"));

        // TSM：財報幣別 TWD 與股價幣別 USD 不一致 -> REPORTING_CURRENCY_MISMATCH
        List<FinancialReport> tsmReports = operatingAnnualReports("TSM_FIGI", "NonUS_GAAP", "2025-12-31", "45", "4000000000000", "2800000000000", "800000000000", "1200000000000", "500000000000", "900000000000", "2000000000000");
        tsmReports.forEach(r -> r.setCurrency("TWD"));
        batchReports.addAll(tsmReports);

        when(reports.findValuationReports(anyCollection(), eq(HIST_DAY), eq(NOW))).thenReturn(batchReports);

        task.execute(context(HIST_DAY));

        StockValuationDaily jpmRow = savedValuations.stream().filter(r -> "JPM_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        assertThat(jpmRow.getPe()).isEqualByComparingTo("12.5");
        // MarketCap = 200 * 2.8B = 560B; CommonEquity = 340B - 20B = 320B -> PB = 1.75
        assertThat(jpmRow.getPb()).isEqualByComparingTo("1.75");
        assertThat(jpmRow.getPfcfReason()).isEqualTo("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
        assertThat(jpmRow.getEvEbitdaReason()).isEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");

        StockValuationDaily goldRow = savedValuations.stream().filter(r -> "GOLD_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        // MarketCap = 20 * 1.75B = 35B; IFRS Debt = 5.5B - Cash 4B => EV = 36.5B; EBITDA = 4B + 1.5B = 5.5B
        assertThat(goldRow.getPe()).isEqualByComparingTo("16");
        assertThat(goldRow.getEvEbitda()).isNotNull();

        StockValuationDaily tsmRow = savedValuations.stream().filter(r -> "TSM_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        assertThat(tsmRow.getPe()).isNull();
        assertThat(tsmRow.getPeReason()).isEqualTo("REPORTING_CURRENCY_MISMATCH");
        assertThat(tsmRow.getPbReason()).isEqualTo("REPORTING_CURRENCY_MISMATCH");
    }

    @Test
    void shareObservationSyncSkipsDuplicateInsertWhenSharesUnchangedAndClosingSnapshotAlreadyExists() {
        ActiveStockWithStatus aapl = target("AAPL_FIGI", "AAPL", "0000320193", "Apple Inc.", "3571");
        aapl.status().setValuationDate(LAST_CLOSED);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl));
        when(quotes.findFirstQuoteDates(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"AAPL_FIGI", LocalDate.of(2016, 1, 4)}));
        aapl.status().setValuationDate(LAST_CLOSED.minusDays(1));
        when(quotes.findStoredQuotes(anyCollection(), eq(LAST_CLOSED))).thenReturn(List.of(quote("AAPL_FIGI", LAST_CLOSED, "220")));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(aapl.status()));

        FutuService.ObservationSession obsSession = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(obsSession);
        when(obsSession.supportedUsStocks()).thenReturn(Set.of("AAPL"));
        when(obsSession.shareCounts(List.of("AAPL"))).thenReturn(Map.of("AAPL",
                new FutuService.ShareSnapshot(15000000000L, 14800000000L, new BigDecimal("220"),
                        new BigDecimal("3300000000000"), new BigDecimal("3256000000000"),
                        new BigDecimal("31.5"), new BigDecimal("45.0"), CLOSE)));

        StockShareObservation existing = new StockShareObservation();
        existing.setFigi("AAPL_FIGI");
        existing.setObservedAt(CLOSE.plusSeconds(30));
        existing.setIssuedShares(15000000000L);
        existing.setOutstandingShares(14800000000L);
        existing.setSnapshotPrice(new BigDecimal("220"));
        existing.setSnapshotMarketCap(new BigDecimal("3300000000000"));
        existing.setOutstandingMarketCap(new BigDecimal("3256000000000"));
        existing.setSnapshotQuoteTime(CLOSE);
        existing.setSource("FUTU");

        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(existing));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), eq(CLOSE), any())).thenReturn(List.of(existing));
        when(reports.findValuationReports(anyCollection(), eq(LAST_CLOSED), eq(NOW))).thenReturn(List.of());

        JobExecutionContext incrementalCtx = mock(JobExecutionContext.class);
        when(incrementalCtx.getMergedJobDataMap()).thenReturn(new JobDataMap());
        task.execute(incrementalCtx);

        // 股數未變動且已有當日收市快照 -> 不重複寫入 StockShareObservation
        assertThat(savedObservations).isEmpty();
    }

    @Test
    void nvdaPostSplitCrossingQuarterEpsPassesViaDilutedWeightedSharesAndExelDebtFreeOperatingLeaseSucceeds() {
        LocalDate postSplitDay = LocalDate.of(2024, 6, 14);
        Instant postSplitClose = Instant.parse("2024-06-14T20:00:00Z");
        when(calendar.getSessionCloseAt(postSplitDay)).thenReturn(postSplitClose);
        when(calendar.getSessionOpenAt(postSplitDay)).thenReturn(postSplitClose.minusSeconds(6 * 3600));

        ActiveStockWithStatus nvda = target("NVDA_FIGI", "NVDA", "0001045810", "NVIDIA Corp", "3674");
        ActiveStockWithStatus exel = target("EXEL_FIGI", "EXEL", "0000939767", "Exelixis Inc", "2836");
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, postSplitDay)).thenReturn(List.of(nvda, exel));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(nvda.status(), exel.status()));
        when(quotes.findStoredQuotes(anyCollection(), eq(postSplitDay))).thenReturn(List.of(
                quote("NVDA_FIGI", postSplitDay, "131.88"), quote("EXEL_FIGI", postSplitDay, "22.50")));

        StockSplit split = new StockSplit();
        split.setSourceEventId("NVDA:2024-06-10");
        split.setFigi("NVDA_FIGI");
        split.setExecutionDate(LocalDate.of(2024, 6, 10));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(BigDecimal.TEN);
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));

        StockShareObservation nvdaObs = new StockShareObservation();
        nvdaObs.setFigi("NVDA_FIGI");
        nvdaObs.setObservedAt(postSplitClose.minusSeconds(300));
        nvdaObs.setIssuedShares(24600000000L);
        nvdaObs.setOutstandingShares(24600000000L);
        nvdaObs.setSource("FUTU");
        StockShareObservation exelObs = new StockShareObservation();
        exelObs.setFigi("EXEL_FIGI");
        exelObs.setObservedAt(postSplitClose.minusSeconds(300));
        exelObs.setIssuedShares(280000000L);
        exelObs.setOutstandingShares(280000000L);
        exelObs.setSource("FUTU");
        when(shareObservations.findLatestAt(anyCollection(), eq(postSplitClose))).thenReturn(List.of(nvdaObs, exelObs));

        List<FinancialReport> nvdaAndExelReports = new ArrayList<>();
        String[][] nvdaQuarters = {
                {"2023-07-29", "2", "2024", "0.25", "6188000000"},
                {"2023-10-28", "3", "2024", "0.37", "9243000000"},
                {"2024-01-27", "4", "2024", "0.49", "12285000000"},
                {"2024-04-27", "1", "2025", "0.60", "14881000000"}
        };
        List<SecFinancialFact> secList = new ArrayList<>();
        String[][] secRanges = {
                {"2023-05-01", "2023-07-30", "2023-08-28", "2499000000"},
                {"2023-07-31", "2023-10-29", "2023-11-21", "2494000000"},
                {"2023-10-30", "2024-01-28", "2024-02-21", "2490000000"},
                {"2024-01-29", "2024-04-28", "2024-05-29", "2489000000"}
        };
        for (int i = 0; i < 4; i++) {
            FinancialReport q = new FinancialReport();
            q.setFigi("NVDA_FIGI");
            q.setStatementType(1);
            q.setPeriodType(Integer.parseInt(nvdaQuarters[i][1]));
            q.setFiscalYear(Integer.parseInt(nvdaQuarters[i][2]));
            q.setPeriodEnd(LocalDate.parse(nvdaQuarters[i][0]));
            q.setCurrency("USD");
            q.setAccountingStandards("US_GAAP");
            q.setObservedAt(postSplitClose.minusSeconds(600));
            q.setItems(List.of(
                    new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal(nvdaQuarters[i][3]), null, null),
                    new FinancialReport.Item(8046L, "Net Income to Common Stockholders", new BigDecimal(nvdaQuarters[i][4]), null, null)));
            nvdaAndExelReports.add(q);

            SecFinancialFact wShares = new SecFinancialFact();
            wShares.setCik("0001045810");
            wShares.setTaxonomy("us-gaap");
            wShares.setTag("WeightedAverageNumberOfDilutedSharesOutstanding");
            wShares.setUnit("shares");
            wShares.setPeriodStart(LocalDate.parse(secRanges[i][0]));
            wShares.setPeriodEnd(LocalDate.parse(secRanges[i][1]));
            wShares.setFiled(LocalDate.parse(secRanges[i][2]));
            wShares.setAvailableFromDate(LocalDate.parse(secRanges[i][2]).plusDays(1));
            wShares.setObservedAt(postSplitClose.minusSeconds(600));
            wShares.setAccession("0001045810-24-00000" + (i + 1));
            wShares.setForm("10-Q");
            wShares.setValue(new BigDecimal(secRanges[i][3]));
            secList.add(wShares);
        }

        // EXEL：無短期借款、長債與租賃總額 162,201,000 等於租賃項 162,201,000（純借款 = 0）
        FinancialReport exelInc = new FinancialReport();
        exelInc.setFigi("EXEL_FIGI");
        exelInc.setStatementType(1);
        exelInc.setPeriodType(7);
        exelInc.setFiscalYear(2023);
        exelInc.setPeriodEnd(LocalDate.of(2023, 12, 31));
        exelInc.setCurrency("USD");
        exelInc.setAccountingStandards("US_GAAP");
        exelInc.setObservedAt(postSplitClose.minusSeconds(600));
        exelInc.setItems(List.of(
                new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal("0.75"), null, null),
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("1830000000"), null, null),
                new FinancialReport.Item(8017L, "Operating Profit", new BigDecimal("250000000"), null, null)));
        FinancialReport exelBal = new FinancialReport();
        exelBal.setFigi("EXEL_FIGI");
        exelBal.setStatementType(2);
        exelBal.setPeriodType(7);
        exelBal.setFiscalYear(2023);
        exelBal.setPeriodEnd(LocalDate.of(2023, 12, 31));
        exelBal.setCurrency("USD");
        exelBal.setAccountingStandards("US_GAAP");
        exelBal.setObservedAt(postSplitClose.minusSeconds(600));
        exelBal.setItems(List.of(
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("1833832000"), null, null),
                new FinancialReport.Item(8068L, "Long Term Debt and Capital Lease Obligation", new BigDecimal("162201000"), null, null),
                new FinancialReport.Item(8070L, "-Long Term Capital Lease Obligation", new BigDecimal("162201000"), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal("304774000"), null, null)));
        FinancialReport exelCf = new FinancialReport();
        exelCf.setFigi("EXEL_FIGI");
        exelCf.setStatementType(3);
        exelCf.setPeriodType(7);
        exelCf.setFiscalYear(2023);
        exelCf.setPeriodEnd(LocalDate.of(2023, 12, 31));
        exelCf.setCurrency("USD");
        exelCf.setAccountingStandards("US_GAAP");
        exelCf.setObservedAt(postSplitClose.minusSeconds(600));
        exelCf.setItems(List.of(
                new FinancialReport.Item(8019L, "Depreciation & Depletion & Amortization", new BigDecimal("50000000"), null, null),
                new FinancialReport.Item(8072L, "Free Cash Flow", new BigDecimal("200000000"), null, null)));
        nvdaAndExelReports.addAll(List.of(exelInc, exelBal, exelCf));

        when(reports.findValuationReports(anyCollection(), eq(postSplitDay), eq(NOW))).thenReturn(nvdaAndExelReports);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(postSplitDay), eq(NOW))).thenReturn(secList);

        task.execute(context(postSplitDay));

        StockValuationDaily nvdaRow = savedValuations.stream().filter(r -> "NVDA_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        // NVDA TTM EPS = 0.25 + 0.37 + 0.49 + 0.60 = 1.71；131.88 / 1.71 = 77.1228
        assertThat(nvdaRow.getPe()).isEqualByComparingTo("77.1228");
        assertThat(nvdaRow.getPeReason()).isNull();

        StockValuationDaily exelRow = savedValuations.stream().filter(r -> "EXEL_FIGI".equals(r.getFigi())).findFirst().orElseThrow();
        assertThat(exelRow.getPe()).isEqualByComparingTo("30");
        assertThat(exelRow.getEvEbitda()).isNotNull();
        assertThat(exelRow.getEvEbitdaReason()).isNull();
    }

    private JobExecutionContext context(LocalDate valuationDate) {
        JobDataMap map = new JobDataMap();
        map.put("valuationDate", valuationDate.toString());
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(map);
        return ctx;
    }

    private ActiveStockWithStatus target(String figi, String symbol, String cik, String longName, String sic) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setCik(cik);
        stock.setLongName(longName);
        stock.setSic(sic);
        stock.setCurrency("USD");
        stock.setStockType("Common Stock");
        stock.setTracked(true);
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        status.setQuoteDate(LAST_CLOSED);
        status.setSplitFromDate(LocalDate.of(2014, 1, 1));
        status.setSplitDate(LAST_CLOSED);
        return new ActiveStockWithStatus(stock, status);
    }

    private StockQuote quote(String figi, LocalDate day, String close) {
        StockQuote q = new StockQuote();
        q.setFigi(figi);
        q.setQuoteDate(day);
        q.setClosePrice(new BigDecimal(close));
        return q;
    }

    private StockShareObservation simpleObs(String figi, long outstandingShares) {
        StockShareObservation obs = new StockShareObservation();
        obs.setFigi(figi);
        obs.setObservedAt(HIST_CLOSE.minusSeconds(300));
        obs.setIssuedShares(outstandingShares);
        obs.setOutstandingShares(outstandingShares);
        obs.setSource("FUTU");
        return obs;
    }

    private SecFinancialFact secShareFact(String cik, String periodEnd, String filed, String shares) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik(cik);
        fact.setTaxonomy("us-gaap");
        fact.setTag("CommonStockSharesOutstanding");
        fact.setUnit("shares");
        fact.setPeriodStart(null);
        fact.setPeriodEnd(LocalDate.parse(periodEnd));
        fact.setFiled(LocalDate.parse(filed));
        fact.setAvailableFromDate(LocalDate.parse(filed).plusDays(1));
        fact.setObservedAt(HIST_CLOSE.minusSeconds(3600));
        fact.setAccession(cik + "-26-000001");
        fact.setForm("10-Q");
        fact.setValue(new BigDecimal(shares));
        return fact;
    }

    private List<FinancialReport> operatingAnnualReports(String figi, String standard, String periodEnd,
                                                         String eps, String equity, String revenue, String fcf,
                                                         String opProfit, String dda, String debtAndLeases, String cash) {
        LocalDate end = LocalDate.parse(periodEnd);
        FinancialReport income = new FinancialReport();
        income.setFigi(figi);
        income.setStatementType(1);
        income.setPeriodType(7);
        income.setFiscalYear(end.getYear());
        income.setPeriodEnd(end);
        income.setCurrency("USD");
        income.setAccountingStandards(standard);
        income.setObservedAt(HIST_CLOSE.minusSeconds(600));
        income.setItems(List.of(
                new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal(eps), null, null),
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(revenue), null, null),
                new FinancialReport.Item(8017L, "Operating Profit", new BigDecimal(opProfit), null, null)));

        FinancialReport balance = new FinancialReport();
        balance.setFigi(figi);
        balance.setStatementType(2);
        balance.setPeriodType(7);
        balance.setFiscalYear(end.getYear());
        balance.setPeriodEnd(end);
        balance.setCurrency("USD");
        balance.setAccountingStandards(standard);
        balance.setObservedAt(HIST_CLOSE.minusSeconds(600));
        balance.setItems(List.of(
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal(equity), null, null),
                new FinancialReport.Item(8068L, "Long Term Debt and Capital Lease Obligation", new BigDecimal(debtAndLeases), null, null),
                new FinancialReport.Item(8069L, "-Long Term Debt", new BigDecimal(debtAndLeases), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal(cash), null, null)));

        FinancialReport cashFlow = new FinancialReport();
        cashFlow.setFigi(figi);
        cashFlow.setStatementType(3);
        cashFlow.setPeriodType(7);
        cashFlow.setFiscalYear(end.getYear());
        cashFlow.setPeriodEnd(end);
        cashFlow.setCurrency("USD");
        cashFlow.setAccountingStandards(standard);
        cashFlow.setObservedAt(HIST_CLOSE.minusSeconds(600));
        cashFlow.setItems(List.of(
                new FinancialReport.Item(8019L, "Depreciation & Depletion & Amortization", new BigDecimal(dda), null, null),
                new FinancialReport.Item(8072L, "Free Cash Flow", new BigDecimal(fcf), null, null)));
        return List.of(income, balance, cashFlow);
    }
}
