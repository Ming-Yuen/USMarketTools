package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
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
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
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
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockShareObservationRepository shareObservations = mock(StockShareObservationRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final IndustryValuationConfigRepository industryConfigs = mock(IndustryValuationConfigRepository.class);
    private final StockFinancialPeriodRepository periodRepo = mock(StockFinancialPeriodRepository.class);
    private final StockValuationLatestRepository latestRepo = mock(StockValuationLatestRepository.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final FutuService futu = mock(FutuService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private final Map<String, List<StockFinancialPeriod>> storedPeriods = new LinkedHashMap<>();
    private final Map<String, StockValuationLatest> storedLatest = new LinkedHashMap<>();
    private final List<StockShareObservation> savedObservations = new ArrayList<>();

    private StockValuationService valuationService;
    private StockValuationDailyTask task;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        valuationService = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, periodRepo, latestRepo, calendar, clock,
                new SecIssuerHistory(""), tx, entityManager);
        task = new StockValuationDailyTask(stocks, statuses, shareObservations, futu, valuationService, calendar, clock, tx, entityManager);

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
            if (arg instanceof StockFinancialPeriod p) {
                storedPeriods.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
            } else if (arg instanceof StockValuationLatest l) {
                storedLatest.put(l.getFigi(), l);
            } else if (arg instanceof StockShareObservation obs) {
                savedObservations.add(obs);
            }
            return null;
        }).when(entityManager).persist(any());

        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof StockValuationLatest l) storedLatest.put(l.getFigi(), l);
            return arg;
        }).when(entityManager).merge(any());

        doAnswer(invocation -> {
            Collection<String> figis = invocation.getArgument(0);
            for (String f : figis) storedPeriods.remove(f);
            return null;
        }).when(periodRepo).deleteByFigiIn(anyCollection());

        when(periodRepo.findByFigiOrderByPeriodEndAsc(anyString()))
                .thenAnswer(inv -> List.copyOf(storedPeriods.getOrDefault((String) inv.getArgument(0), List.of())));
        when(periodRepo.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<String> figis = inv.getArgument(0);
                    List<StockFinancialPeriod> all = new ArrayList<>();
                    for (String f : figis) all.addAll(storedPeriods.getOrDefault(f, List.of()));
                    return all;
                });
        when(periodRepo.findExistingFigis(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<String> figis = inv.getArgument(0);
                    return figis.stream().filter(storedPeriods::containsKey).collect(java.util.stream.Collectors.toSet());
                });
        when(latestRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(storedLatest.get((String) inv.getArgument(0))));
        when(latestRepo.findByFigiIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> figis = inv.getArgument(0);
            return figis.stream().map(storedLatest::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    @Test
    void singleListedClassAUsesOutstandingSharesWithoutNamedShareClassBlock() {
        ActiveStockWithStatus meta = target("BBG000MM2P62", "META", "0001326801", "Meta Platforms, Inc. Class A Common Stock", "7370");
        meta.status().setSplitFromDate(null);
        meta.status().setSplitDate(null);
        stubStocksAndStatuses(List.of(meta));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("BBG000MM2P62"), any(), any()))
                .thenReturn(List.of(quote("BBG000MM2P62", HIST_DAY, "500")));

        StockShareObservation obs = new StockShareObservation();
        obs.setFigi("BBG000MM2P62");
        obs.setObservedAt(HIST_CLOSE.minusSeconds(3600));
        obs.setIssuedShares(2700000000L);
        obs.setOutstandingShares(2500000000L);
        obs.setSource("FUTU");
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(obs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq("BBG000MM2P62"), eq("FUTU"), any()))
                .thenReturn(Optional.of(obs));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(
                operatingAnnualReports("BBG000MM2P62", "US_GAAP", "2025-12-31", "20", "125000000000", "250000000000", "50000000000", "60000000000", "15000000000", "20000000000", "40000000000"));

        List<ValuationResultDto> evaluated = valuationService.recalculate("BBG000MM2P62", HIST_DAY, HIST_DAY);

        assertThat(evaluated).singleElement().satisfies(row -> {
            assertThat(row.metrics().pe().value()).isEqualByComparingTo("25");
            // MarketCap = 500 * 2.5B (outstandingShares, not 2.7B issuedShares) = 1.25T; PB = 1.25T / 125B = 10
            assertThat(row.metrics().pb().value()).isEqualByComparingTo("10");
            assertThat(row.metrics().ps().value()).isEqualByComparingTo("5");
            assertThat(row.metrics().pfcf().value()).isEqualByComparingTo("25");
            assertThat(row.metrics().evEbitda().value()).isNotNull();
        });
    }

    @Test
    void multiSecurityOneToOneGooglComputesHistoricalValuationWhileNonOneToOneBrkBMarksUnverifiedHistorically() {
        ActiveStockWithStatus googl = target("GOOGL_FIGI", "GOOGL", "0001652044", "Alphabet Inc. Class A", "7370");
        ActiveStockWithStatus brkB = target("BRKB_FIGI", "BRK.B", "0001067983", "Berkshire Hathaway Inc. Class B", "6331");
        stubStocksAndStatuses(List.of(googl, brkB));
        when(stocks.findMultiSecurityIssuers(anyCollection(), eq(ActiveStatus.ACTIVE)))
                .thenReturn(Set.of("0001652044", "0001067983"));

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

        SecFinancialFact googlSecShares = secShareFact("0001652044", "2025-12-31", "2026-02-05", "12300000000");
        when(secFacts.findLatestIssuerShareFacts(eq(List.of("0001652044")), any())).thenReturn(List.of(googlSecShares));
        when(secFacts.findLatestIssuerShareFacts(eq(List.of("0001067983")), any())).thenReturn(List.of());
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of(googlSecShares));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(googlLatest, brkLatest));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq("GOOGL_FIGI"), eq("FUTU"), eq(NOW)))
                .thenReturn(Optional.of(googlLatest));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq("BRKB_FIGI"), eq("FUTU"), eq(NOW)))
                .thenReturn(Optional.of(brkLatest));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), eq(CLOSE), eq(NOW)))
                .thenReturn(List.of(googlLatest, brkLatest));

        List<FinancialReport> allReports = new ArrayList<>();
        allReports.addAll(operatingAnnualReports("GOOGL_FIGI", "US_GAAP", "2025-12-31", "10", "300000000000", "350000000000", "70000000000", "100000000000", "20000000000", "15000000000", "90000000000"));
        allReports.addAll(operatingAnnualReports("BRKB_FIGI", "US_GAAP", "2025-12-31", "25", "531129898500", "370000000000", "30000000000", "40000000000", "10000000000", "120000000000", "45000000000"));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(allReports);
        when(quotes.findStoredQuotes(anyCollection(), eq(LAST_CLOSED)))
                .thenReturn(List.of(quote("GOOGL_FIGI", LAST_CLOSED, "200"), quote("BRKB_FIGI", LAST_CLOSED, "500")));

        // A. 最新收市快照：GOOGL 與 BRK.B 均採用富途對應等值總市值算出最新估值
        valuationService.rebuildAndRefreshLatest(List.of("GOOGL_FIGI", "BRKB_FIGI"));

        StockValuationLatest googlLatestRow = storedLatest.get("GOOGL_FIGI");
        StockValuationLatest brkLatestRow = storedLatest.get("BRKB_FIGI");
        assertThat(googlLatestRow.getPe()).isEqualByComparingTo("20");
        assertThat(googlLatestRow.getPb()).isNotNull();
        assertThat(brkLatestRow.getPe()).isEqualByComparingTo("20");
        assertThat(brkLatestRow.getPb()).isEqualByComparingTo("2");
        assertThat(brkLatestRow.getPfcfReason()).isEqualTo(ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY);
        assertThat(brkLatestRow.getEvEbitdaReason()).isEqualTo(ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY);

        // B. 歷史交易日即時估值：GOOGL 通過 <=2% 驗證；BRK.B 未通過 1:1 驗證標為 SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED（但保留同股類單位 P/E）
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("GOOGL_FIGI"), eq(HIST_DAY), eq(HIST_DAY)))
                .thenReturn(List.of(quote("GOOGL_FIGI", HIST_DAY, "180")));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("BRKB_FIGI"), eq(HIST_DAY), eq(HIST_DAY)))
                .thenReturn(List.of(quote("BRKB_FIGI", HIST_DAY, "450")));

        ValuationResultDto googlHist = valuationService.history("GOOGL_FIGI", HIST_DAY, HIST_DAY, "DAILY").getFirst();
        ValuationResultDto brkHist = valuationService.history("BRKB_FIGI", HIST_DAY, HIST_DAY, "DAILY").getFirst();
        assertThat(googlHist.metrics().pb().value()).isNotNull();
        assertThat(googlHist.metrics().ps().value()).isNotNull();
        assertThat(brkHist.metrics().pe().value()).isEqualByComparingTo("18");
        assertThat(brkHist.metrics().pb().value()).isNull();
        assertThat(brkHist.metrics().pb().reasonCode()).isEqualTo("SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED");
    }

    @Test
    void bankConfigAndIfrsCrossNationalAndForeignCurrencyBehaveDeterministically() {
        ActiveStockWithStatus jpm = target("JPM_FIGI", "JPM", "0000019617", "JPMorgan Chase & Co.", "6021");
        ActiveStockWithStatus gold = target("GOLD_FIGI", "GOLD", null, "Barrick Mining Corp", "1040");
        ActiveStockWithStatus tsm = target("TSM_FIGI", "TSM", null, "TSMC", "3674");
        stubStocksAndStatuses(List.of(jpm, gold, tsm));

        StockIndustry bankInd = new StockIndustry();
        bankInd.setCode("I100");
        bankInd.setSourcePlateCode("US.LIST2481");
        StockIndustryHistory jpmHist = new StockIndustryHistory();
        jpmHist.setFigi("JPM_FIGI");
        jpmHist.setIndustry(bankInd);
        when(industries.findByFigisAt(anyCollection(), any())).thenReturn(List.of(jpmHist));
        IndustryValuationConfig bankCfg = new IndustryValuationConfig();
        bankCfg.setIndustryCode("I100");
        bankCfg.setModelCode("BANK");
        bankCfg.setPrimaryMetric("PB");
        when(industryConfigs.findByIndustryCodesAt(anyCollection(), any())).thenReturn(List.of(bankCfg));

        StockShareObservation jpmObs = simpleObs("JPM_FIGI", 2800000000L);
        StockShareObservation goldObs = simpleObs("GOLD_FIGI", 1750000000L);
        StockShareObservation tsmObs = simpleObs("TSM_FIGI", 5186000000L);
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(jpmObs, goldObs, tsmObs));
        when(quotes.findStoredQuotes(anyCollection(), eq(LAST_CLOSED))).thenReturn(List.of(
                quote("JPM_FIGI", LAST_CLOSED, "200"), quote("GOLD_FIGI", LAST_CLOSED, "20"), quote("TSM_FIGI", LAST_CLOSED, "180")));

        List<FinancialReport> batchReports = new ArrayList<>();
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

        batchReports.addAll(operatingAnnualReports("GOLD_FIGI", "NonUS_GAAP", "2025-12-31", "1.25", "25000000000", "14000000000", "2000000000", "4000000000", "1500000000", "5500000000", "4000000000"));
        List<FinancialReport> tsmReports = operatingAnnualReports("TSM_FIGI", "NonUS_GAAP", "2025-12-31", "45", "4000000000000", "2800000000000", "800000000000", "1200000000000", "500000000000", "900000000000", "2000000000000");
        tsmReports.forEach(r -> r.setCurrency("TWD"));
        batchReports.addAll(tsmReports);

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(batchReports);

        valuationService.rebuildAndRefreshLatest(List.of("JPM_FIGI", "GOLD_FIGI", "TSM_FIGI"));

        StockValuationLatest jpmRow = storedLatest.get("JPM_FIGI");
        assertThat(jpmRow.getPe()).isEqualByComparingTo("12.5");
        assertThat(jpmRow.getPb()).isEqualByComparingTo("1.75");
        assertThat(jpmRow.getPfcfReason()).isEqualTo(ValuationReason.PFCF_NOT_APPLICABLE_TO_INDUSTRY);
        assertThat(jpmRow.getEvEbitdaReason()).isEqualTo(ValuationReason.EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY);

        StockValuationLatest goldRow = storedLatest.get("GOLD_FIGI");
        assertThat(goldRow.getPe()).isEqualByComparingTo("16");
        assertThat(goldRow.getEvEbitda()).isNotNull();

        StockValuationLatest tsmRow = storedLatest.get("TSM_FIGI");
        assertThat(tsmRow.getPe()).isNull();
        assertThat(tsmRow.getPeReason()).isEqualTo(ValuationReason.REPORTING_CURRENCY_MISMATCH);
        assertThat(tsmRow.getPbReason()).isEqualTo(ValuationReason.REPORTING_CURRENCY_MISMATCH);
    }

    @Test
    void shareObservationSyncSkipsDuplicateInsertWhenSharesUnchangedAndClosingSnapshotAlreadyExists() {
        ActiveStockWithStatus aapl = target("AAPL_FIGI", "AAPL", "0000320193", "Apple Inc.", "3571");
        stubStocksAndStatuses(List.of(aapl));
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl));
        when(quotes.findStoredQuotes(anyCollection(), eq(LAST_CLOSED))).thenReturn(List.of(quote("AAPL_FIGI", LAST_CLOSED, "220")));

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

        JobExecutionContext incrementalCtx = mock(JobExecutionContext.class);
        when(incrementalCtx.getMergedJobDataMap()).thenReturn(new JobDataMap());
        task.execute(incrementalCtx);

        assertThat(savedObservations).isEmpty();
    }

    @Test
    void nvdaPreAndPostSplitValuationsUseSameFinancialPeriodRowViaEpsShareBasisDateAndExelDebtFreeSucceeds() {
        LocalDate preSplitDay = LocalDate.of(2024, 5, 31);
        LocalDate postSplitDay = LocalDate.of(2024, 6, 14);
        Instant postSplitClose = Instant.parse("2024-06-14T20:00:00Z");
        when(calendar.getSessionCloseAt(preSplitDay)).thenReturn(Instant.parse("2024-05-31T20:00:00Z"));
        when(calendar.getSessionCloseAt(postSplitDay)).thenReturn(postSplitClose);

        ActiveStockWithStatus nvda = target("NVDA_FIGI", "NVDA", "0001045810", "NVIDIA Corp", "3674");
        ActiveStockWithStatus exel = target("EXEL_FIGI", "EXEL", "0000939767", "Exelixis Inc", "2836");
        stubStocksAndStatuses(List.of(nvda, exel));

        StockSplit split = new StockSplit();
        split.setSourceEventId("NVDA:2024-06-10");
        split.setFigi("NVDA_FIGI");
        split.setExecutionDate(LocalDate.of(2024, 6, 10));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(BigDecimal.TEN);
        when(splits.findByFigiInOrderByExecutionDateAsc(eq(List.of("NVDA_FIGI")))).thenReturn(List.of(split));
        when(splits.findByFigiInOrderByExecutionDateAsc(eq(List.of("EXEL_FIGI")))).thenReturn(List.of());
        when(splits.findByFigiInOrderByExecutionDateAsc(eq(List.of("NVDA_FIGI", "EXEL_FIGI")))).thenReturn(List.of(split));

        StockShareObservation nvdaObs = simpleObs("NVDA_FIGI", 24600000000L);
        StockShareObservation exelObs = simpleObs("EXEL_FIGI", 280000000L);
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(nvdaObs, exelObs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq("NVDA_FIGI"), eq("FUTU"), any()))
                .thenReturn(Optional.of(nvdaObs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq("EXEL_FIGI"), eq("FUTU"), any()))
                .thenReturn(Optional.of(exelObs));

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

            // 亦加入損益週期錨點讓非曆年 1 天誤差自動正規化至 SEC 實際期末
            SecFinancialFact niAnchor = new SecFinancialFact();
            niAnchor.setCik("0001045810");
            niAnchor.setTaxonomy("us-gaap");
            niAnchor.setTag("NetIncomeLoss");
            niAnchor.setUnit("USD");
            niAnchor.setPeriodStart(LocalDate.parse(secRanges[i][0]));
            niAnchor.setPeriodEnd(LocalDate.parse(secRanges[i][1]));
            niAnchor.setFiled(LocalDate.parse(secRanges[i][2]));
            niAnchor.setAvailableFromDate(LocalDate.parse(secRanges[i][2]).plusDays(1));
            niAnchor.setObservedAt(postSplitClose.minusSeconds(600));
            niAnchor.setAccession("0001045810-24-00000" + (i + 1));
            niAnchor.setForm("10-Q");
            niAnchor.setValue(new BigDecimal(nvdaQuarters[i][4]));
            secList.add(niAnchor);
        }

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

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(nvdaAndExelReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(secList);

        valuationService.rebuildFinancialPeriods(List.of("NVDA_FIGI", "EXEL_FIGI"));

        // 驗證 NVDA 2024-04-27 已正規化至 SEC 實際期末 2024-04-28，無重複季度
        List<StockFinancialPeriod> nvdaPeriods = storedPeriods.get("NVDA_FIGI");
        assertThat(nvdaPeriods).extracting(StockFinancialPeriod::getPeriodEnd)
                .containsExactly(LocalDate.of(2023, 7, 30), LocalDate.of(2023, 10, 29), LocalDate.of(2024, 1, 28), LocalDate.of(2024, 4, 28));

        // 同一筆財季分母列同時支援拆股前（$1096.33 ÷ $17.10 = 64.1129）與拆股後（$131.88 ÷ $1.71 = 77.1228）即時估值！
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("NVDA_FIGI"), eq(preSplitDay), eq(postSplitDay)))
                .thenReturn(List.of(quote("NVDA_FIGI", preSplitDay, "1096.33"), quote("NVDA_FIGI", postSplitDay, "131.88")));
        List<ValuationResultDto> nvdaHist = valuationService.history("NVDA_FIGI", preSplitDay, postSplitDay, "DAILY");
        assertThat(nvdaHist).hasSize(2);
        assertThat(nvdaHist.get(0).metrics().pe().value()).isEqualByComparingTo("64.1129");
        assertThat(nvdaHist.get(1).metrics().pe().value()).isEqualByComparingTo("77.1228");

        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("EXEL_FIGI"), eq(postSplitDay), eq(postSplitDay)))
                .thenReturn(List.of(quote("EXEL_FIGI", postSplitDay, "22.50")));
        ValuationResultDto exelHist = valuationService.history("EXEL_FIGI", postSplitDay, postSplitDay, "DAILY").getFirst();
        assertThat(exelHist.metrics().pe().value()).isEqualByComparingTo("30");
        assertThat(exelHist.metrics().evEbitda().value()).isNotNull();
        assertThat(exelHist.metrics().evEbitda().reasonCode()).isNull();
    }

    private void stubStocksAndStatuses(List<ActiveStockWithStatus> items) {
        Map<String, Stock> sMap = new LinkedHashMap<>();
        Map<String, StockStatus> stMap = new LinkedHashMap<>();
        for (ActiveStockWithStatus item : items) {
            sMap.put(item.stock().getFigi(), item.stock());
            stMap.put(item.status().getFigi(), item.status());
            when(stocks.findById(item.stock().getFigi())).thenReturn(Optional.of(item.stock()));
            when(statuses.findById(item.status().getFigi())).thenReturn(Optional.of(item.status()));
        }
        when(stocks.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(sMap::get).filter(java.util.Objects::nonNull).toList();
        });
        when(statuses.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(stMap::get).filter(java.util.Objects::nonNull).toList();
        });
        when(statuses.findAllByIdForUpdate(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            return ids.stream().map(stMap::get).filter(java.util.Objects::nonNull).toList();
        });
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
        stock.setActiveStatus(ActiveStatus.ACTIVE);
        stock.setTracked(true);
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        status.setQuoteDate(LAST_CLOSED);
        status.setFinancialReportDate(LocalDate.of(2025, 12, 31));
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
