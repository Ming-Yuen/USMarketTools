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
import com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository;
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
    private final StockCustomPeerRepository customPeerRepo = mock(StockCustomPeerRepository.class);
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
                industries, industryConfigs, periodRepo, latestRepo, customPeerRepo, calendar, clock,
                new SecIssuerHistory(""), tx, entityManager);
        task = new StockValuationDailyTask(stocks, statuses, shareObservations, futu, valuationService, calendar, clock, tx, entityManager,
                latestRepo,
                mock(com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService.class),
                mock(com.ykm.stock.analytics.service.analysis.DailyOpportunityRadarService.class),
                mock(com.ykm.stock.analytics.service.analysis.SignalTrackingService.class),
                mock(com.ykm.stock.analytics.service.portfolio.PortfolioClosingService.class));

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
        when(periodRepo.findPeriodSignaturesByFigiIn(anyCollection()))
                .thenAnswer(inv -> {
                    Collection<String> figis = inv.getArgument(0);
                    List<StockFinancialPeriodRepository.FigiPeriodSignature> sigs = new ArrayList<>();
                    for (String f : figis) {
                        List<StockFinancialPeriod> rows = storedPeriods.get(f);
                        if (rows != null && !rows.isEmpty()) {
                            Instant maxUpdated = rows.stream().map(StockFinancialPeriod::getUpdatedAt)
                                    .filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).orElse(null);
                            sigs.add(new StockFinancialPeriodRepository.FigiPeriodSignature(f, rows.size(), maxUpdated));
                        }
                    }
                    return sigs;
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
        existing.setPeTtmRate(new BigDecimal("31.5"));
        existing.setPbRate(new BigDecimal("45.0"));
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

    @Test
    void newerDeiFilingSharesBeatStaleBalanceSheetSharesAndRetainActualShareBasisDate() {
        ActiveStockWithStatus algt = target("BBG000LJS6B8", "ALGT", "0001362468", "Allegiant Travel Co", "4512");
        stubStocksAndStatuses(List.of(algt));

        SecFinancialFact staleBalanceShares = secShareFact("0001362468", "2021-12-31", "2022-03-01", "18111381");
        SecFinancialFact newerDeiShares = new SecFinancialFact();
        newerDeiShares.setCik("0001362468");
        newerDeiShares.setTaxonomy("dei");
        newerDeiShares.setTag("EntityCommonStockSharesOutstanding");
        newerDeiShares.setUnit("shares");
        newerDeiShares.setPeriodStart(null);
        newerDeiShares.setPeriodEnd(LocalDate.of(2026, 7, 31));
        newerDeiShares.setFiled(LocalDate.of(2026, 8, 10));
        newerDeiShares.setAvailableFromDate(LocalDate.of(2026, 8, 11));
        newerDeiShares.setObservedAt(HIST_CLOSE.minusSeconds(3600));
        newerDeiShares.setAccession("0001362468-26-000010");
        newerDeiShares.setForm("10-Q");
        newerDeiShares.setValue(new BigDecimal("27337755"));

        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of(staleBalanceShares, newerDeiShares));
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(
                operatingAnnualReports("BBG000LJS6B8", "US_GAAP", "2026-09-30", "4.00", "1765000000", "3293000000", "150000000", "300000000", "100000000", "2000000000", "500000000"));

        valuationService.rebuildFinancialPeriods(List.of("BBG000LJS6B8"));

        StockFinancialPeriod built = storedPeriods.get("BBG000LJS6B8").getFirst();
        assertThat(built.getSharesOutstanding()).isEqualByComparingTo("27337755");
        assertThat(built.getSharesBasisDate()).isEqualTo(LocalDate.of(2026, 7, 31));

        LocalDate evalDay = LocalDate.of(2026, 9, 30);
        when(calendar.getSessionCloseAt(evalDay)).thenReturn(Instant.parse("2026-09-30T20:00:00Z"));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("BBG000LJS6B8"), eq(evalDay), eq(evalDay)))
                .thenReturn(List.of(quote("BBG000LJS6B8", evalDay, "75.79")));
        ValuationResultDto dayVal = valuationService.history("BBG000LJS6B8", evalDay, evalDay, "DAILY").getFirst();
        // 27,337,755 * 75.79 / 1,765,000,000 = 1.173897... -> 1.1739 (而非舊股數 18,111,381 算出的 0.7777)
        assertThat(dayVal.metrics().pb().value()).isEqualByComparingTo("1.1739");
    }

    @Test
    void newQuarterDoesNotStuffStalePriorPeriodCashFlowOrBalanceIntoCurrentPeriodRow() {
        ActiveStockWithStatus reg = target("BBG000BL46Q4", "REG", "0000910606", "Regency Centers Corp", "6798");
        stubStocksAndStatuses(List.of(reg));

        List<FinancialReport> regReports = new ArrayList<>();
        // 2025 年度完整三表（2025-12-31：FCF = 80, Equity = 1000）＋ 2025Q2..Q4 與 2026Q1 損益表（無 2026Q1 資產負債表與現金流量表）
        regReports.addAll(operatingAnnualReports("BBG000BL46Q4", "US_GAAP", "2025-12-31", "4.00", "1000", "400", "80", "160", "40", "300", "50"));
        String[][] incQuarters = {
                {"2025-06-30", "2", "2025", "1.00", "100"},
                {"2025-09-30", "3", "2025", "1.00", "100"},
                {"2025-12-31", "4", "2025", "1.00", "100"},
                {"2026-03-31", "1", "2026", "1.20", "120"}
        };
        for (String[] q : incQuarters) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("BBG000BL46Q4");
            inc.setStatementType(1);
            inc.setPeriodType(Integer.parseInt(q[1]));
            inc.setFiscalYear(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("US_GAAP");
            inc.setObservedAt(HIST_CLOSE.minusSeconds(600));
            inc.setItems(List.of(
                    new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal(q[3]), null, null),
                    new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[4]), null, null)));
            regReports.add(inc);
        }
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(regReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(List.of());

        valuationService.rebuildFinancialPeriods(List.of("BBG000BL46Q4"));

        StockFinancialPeriod q1Row = storedPeriods.get("BBG000BL46Q4").stream()
                .filter(p -> LocalDate.of(2026, 3, 31).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(q1Row.getRevenueTtm()).isEqualByComparingTo("420");
        assertThat(q1Row.getEpsTtm()).isEqualByComparingTo("4.20");
        assertThat(q1Row.getFreeCashFlowTtm()).isNull();
        assertThat(q1Row.getFreeCashFlowTtmStatus()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(q1Row.getCommonEquity()).isNull();
        assertThat(q1Row.getCommonEquityStatus()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void evComponentsUsGaapOverrideCorruptedFutuEquityFcfSynthesisAnd52WeekStartStayConsistent() {
        ActiveStockWithStatus qxo = target("QXO_FIGI", "QXO", "0001236275", "QXO Inc", "5030");
        ActiveStockWithStatus ferg = target("FERG_FIGI", "FERG", "0002011641", "Ferguson Enterprises Inc", "5070");
        ActiveStockWithStatus iart = target("IART_FIGI", "IART", "0000917520", "Integra LifeSciences", "3841");
        ActiveStockWithStatus aapl = target("AAPL52_FIGI", "AAPL52", null, "52 Week Filer", "3571");
        stubStocksAndStatuses(List.of(qxo, ferg, iart, aapl));

        List<FinancialReport> batchReports = new ArrayList<>();
        List<SecFinancialFact> batchSec = new ArrayList<>();

        // 1) QXO：Futu 權益 1000、債務 200、現金 50（且 accountingStandards 空白），SEC 同報期補優先股 100、NCI 20 -> 普通股權益 900、EV 調整 270
        List<FinancialReport> qxoReports = operatingAnnualReports("QXO_FIGI", "", "2026-09-30", "2.00", "1000", "500", "60", "80", "20", "200", "50");
        batchReports.addAll(qxoReports);
        batchSec.add(secBalanceFact("0001236275", "2026-09-30", "10-Q", "PreferredStockValue", "100"));
        batchSec.add(secBalanceFact("0001236275", "2026-09-30", "10-Q", "MinorityInterest", "20"));
        batchSec.add(secBalanceFact("0001236275", "2026-09-30", "10-Q", "Assets", "1500"));

        // 2) FERG：Futu 誤標 NonUS_GAAP 且僅列長債 5571＋營運租賃 1521＝7092，SEC 10-Q us-gaap 證明 US_GAAP 並補入短債 599 -> 總債 6170
        FinancialReport fergInc = new FinancialReport();
        fergInc.setFigi("FERG_FIGI");
        fergInc.setStatementType(1);
        fergInc.setPeriodType(7);
        fergInc.setFiscalYear(2026);
        fergInc.setPeriodEnd(LocalDate.of(2026, 9, 30));
        fergInc.setCurrency("USD");
        fergInc.setAccountingStandards("NonUS_GAAP");
        fergInc.setObservedAt(HIST_CLOSE.minusSeconds(600));
        fergInc.setItems(List.of(
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("30000"), null, null),
                new FinancialReport.Item(8017L, "Operating Profit", new BigDecimal("2800"), null, null),
                new FinancialReport.Item(8012L, "Depreciation & Amortization", new BigDecimal("400"), null, null)));
        FinancialReport fergBal = new FinancialReport();
        fergBal.setFigi("FERG_FIGI");
        fergBal.setStatementType(2);
        fergBal.setPeriodType(7);
        fergBal.setFiscalYear(2026);
        fergBal.setPeriodEnd(LocalDate.of(2026, 9, 30));
        fergBal.setCurrency("USD");
        fergBal.setAccountingStandards("NonUS_GAAP");
        fergBal.setObservedAt(HIST_CLOSE.minusSeconds(600));
        fergBal.setItems(List.of(
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("5800"), null, null),
                new FinancialReport.Item(8068L, "Long Term Debt and Capital Lease Obligation", new BigDecimal("7092"), null, null),
                new FinancialReport.Item(8069L, "-Long Term Debt", new BigDecimal("5571"), null, null),
                new FinancialReport.Item(8070L, "-Long Term Capital Lease Obligation", new BigDecimal("1521"), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal("476"), null, null)));
        batchReports.add(fergInc);
        batchReports.add(fergBal);
        batchSec.add(secBalanceFact("0002011641", "2026-09-30", "10-Q", "DebtCurrent", "599"));
        batchSec.add(secBalanceFact("0002011641", "2026-09-30", "10-Q", "LongTermDebtNoncurrent", "5571"));
        batchSec.add(secBalanceFact("0002011641", "2026-09-30", "10-Q", "OperatingLeaseLiabilityCurrent", "492"));
        batchSec.add(secBalanceFact("0002011641", "2026-09-30", "10-Q", "OperatingLeaseLiabilityNoncurrent", "1521"));
        batchSec.add(secBalanceFact("0002011641", "2026-09-30", "10-Q", "Assets", "18000"));

        // 3) IART：Futu Total Equity = 1066、Stockholders' Equity 錯填 2468（缺 8095），SEC StockholdersEquity = 1066 -> 普通股權益 1066、NCI 0
        FinancialReport iartBal = new FinancialReport();
        iartBal.setFigi("IART_FIGI");
        iartBal.setStatementType(2);
        iartBal.setPeriodType(7);
        iartBal.setFiscalYear(2026);
        iartBal.setPeriodEnd(LocalDate.of(2026, 9, 30));
        iartBal.setCurrency("USD");
        iartBal.setAccountingStandards("US_GAAP");
        iartBal.setObservedAt(HIST_CLOSE.minusSeconds(600));
        iartBal.setItems(List.of(
                new FinancialReport.Item(8081L, "Total Equity", new BigDecimal("1066"), null, null),
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("2468"), null, null),
                new FinancialReport.Item(8069L, "-Long Term Debt", new BigDecimal("500"), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal("100"), null, null)));
        batchReports.add(iartBal);
        batchSec.add(secBalanceFact("0000917520", "2026-09-30", "10-Q", "StockholdersEquity", "1066"));

        // 4) Futu-only 52 週財年（2025-09-27 -> 2026-09-26）且僅有 CFO=100、CapEx=-20（無直接 FCF、無 SEC）-> FCF=80，起日為 2025-09-28
        batchReports.addAll(operatingAnnualReports("AAPL52_FIGI", "US_GAAP", "2025-09-27", "7.00", "90", "400", "70", "120", "10", "80", "30"));
        FinancialReport fy26Inc = new FinancialReport();
        fy26Inc.setFigi("AAPL52_FIGI");
        fy26Inc.setStatementType(1);
        fy26Inc.setPeriodType(7);
        fy26Inc.setFiscalYear(2026);
        fy26Inc.setPeriodEnd(LocalDate.of(2026, 9, 26));
        fy26Inc.setCurrency("USD");
        fy26Inc.setAccountingStandards("US_GAAP");
        fy26Inc.setObservedAt(HIST_CLOSE.minusSeconds(600));
        fy26Inc.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("478"), null, null)));
        FinancialReport fy26Cf = new FinancialReport();
        fy26Cf.setFigi("AAPL52_FIGI");
        fy26Cf.setStatementType(3);
        fy26Cf.setPeriodType(7);
        fy26Cf.setFiscalYear(2026);
        fy26Cf.setPeriodEnd(LocalDate.of(2026, 9, 26));
        fy26Cf.setCurrency("USD");
        fy26Cf.setAccountingStandards("US_GAAP");
        fy26Cf.setObservedAt(HIST_CLOSE.minusSeconds(600));
        fy26Cf.setItems(List.of(
                new FinancialReport.Item(8015L, "Operating Cash Flow", new BigDecimal("100"), null, null),
                new FinancialReport.Item(8044L, "Capital Expenditure", new BigDecimal("-20"), null, null)));
        batchReports.add(fy26Inc);
        batchReports.add(fy26Cf);

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(batchReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(batchSec);

        valuationService.rebuildFinancialPeriods(List.of("QXO_FIGI", "FERG_FIGI", "IART_FIGI", "AAPL52_FIGI"));

        StockFinancialPeriod qxoRow = storedPeriods.get("QXO_FIGI").getFirst();
        assertThat(qxoRow.getCommonEquity()).isEqualByComparingTo("900");
        assertThat(qxoRow.getPreferredEquity()).isEqualByComparingTo("100");
        assertThat(qxoRow.getNoncontrollingInterest()).isEqualByComparingTo("20");
        assertThat(qxoRow.getEvAdjustment()).isEqualByComparingTo("270");

        StockFinancialPeriod fergRow = storedPeriods.get("FERG_FIGI").getFirst();
        assertThat(fergRow.getAccountingStandards()).isEqualTo("US_GAAP");
        assertThat(fergRow.getTotalDebt()).isEqualByComparingTo("6170");
        assertThat(fergRow.getEvAdjustment()).isEqualByComparingTo("5694");

        StockFinancialPeriod iartRow = storedPeriods.get("IART_FIGI").getFirst();
        assertThat(iartRow.getCommonEquity()).isEqualByComparingTo("1066");
        assertThat(iartRow.getNoncontrollingInterest()).isEqualByComparingTo("0");

        StockFinancialPeriod aapl52Row = storedPeriods.get("AAPL52_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 9, 26).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(aapl52Row.getFreeCashFlowTtm()).isEqualByComparingTo("80");
        assertThat(aapl52Row.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 9, 28));
    }

    @Test
    void giprRedeemableNciAndAmznAdditiveCurrentBorrowingsAndFergTransitionStayConsistent() {
        ActiveStockWithStatus gipr = target("GIPR_FIGI", "GIPR", "0001651721", "Generation Income Properties Inc", "6798");
        ActiveStockWithStatus amzn = target("AMZN_FIGI", "AMZN", "0001018724", "Amazon.com Inc", "5961");
        ActiveStockWithStatus ferg = target("FERG2_FIGI", "FERG", "0002011641", "Ferguson Enterprises Inc", "5070");
        stubStocksAndStatuses(List.of(gipr, amzn, ferg));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(
                simpleObs("GIPR_FIGI", 7274682L), simpleObs("AMZN_FIGI", 10811000000L), simpleObs("FERG2_FIGI", 193648602L)));

        List<FinancialReport> batchReports = new ArrayList<>();
        List<SecFinancialFact> batchSec = new ArrayList<>();

        // 1) GIPR：無 Futu 2026-09-30 資產負債表，SEC 同時列示永久 NCI 5,711,585 與可贖回 NCI 8,580,000 -> 面板與 EV 同步計入 14,291,585，普通股權益維持 16,767
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "Assets", "74032858"));
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "Liabilities", "59724506"));
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "CashAndCashEquivalentsAtCarryingValue", "867743"));
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "StockholdersEquity", "16767"));
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "MinorityInterest", "5711585"));
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "5728352"));
        batchSec.add(secBalanceFact("0001651721", "2026-09-30", "10-Q", "RedeemableNoncontrollingInterestEquityCarryingAmount", "8580000"));

        // 2) AMZN：Futu 漏列短期借款，SEC 同時列示 ShortTermBorrowings = 380 與 LongTermDebtCurrent = 6827 -> 短期純借款合計 7207，加長期 173125 與融資租賃 14495 = 194827
        FinancialReport amznBal = new FinancialReport();
        amznBal.setFigi("AMZN_FIGI");
        amznBal.setStatementType(2);
        amznBal.setPeriodType(3);
        amznBal.setFiscalYear(2026);
        amznBal.setPeriodEnd(LocalDate.of(2026, 9, 30));
        amznBal.setCurrency("USD");
        amznBal.setAccountingStandards("US_GAAP");
        amznBal.setObservedAt(HIST_CLOSE.minusSeconds(600));
        amznBal.setItems(List.of(
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("350000"), null, null),
                new FinancialReport.Item(8068L, "Long Term Debt and Capital Lease Obligation", new BigDecimal("271949"), null, null),
                new FinancialReport.Item(8069L, "-Long Term Debt", new BigDecimal("173125"), null, null),
                new FinancialReport.Item(8070L, "-Long Term Capital Lease Obligation", new BigDecimal("98824"), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal("55783"), null, null)));
        batchReports.add(amznBal);
        batchSec.add(secBalanceFact("0001018724", "2026-09-30", "10-Q", "ShortTermBorrowings", "380"));
        batchSec.add(secBalanceFact("0001018724", "2026-09-30", "10-Q", "LongTermDebtCurrent", "6827"));
        batchSec.add(secBalanceFact("0001018724", "2026-09-30", "10-Q", "LongTermDebtNoncurrent", "173125"));
        batchSec.add(secBalanceFact("0001018724", "2026-09-30", "10-Q", "FinanceLeaseLiability", "14495"));
        batchSec.add(secBalanceFact("0001018724", "2026-09-30", "10-Q", "OperatingLeaseLiabilityCurrent", "14093"));
        batchSec.add(secBalanceFact("0001018724", "2026-09-30", "10-Q", "OperatingLeaseLiabilityNoncurrent", "86195"));

        // 3) FERG 改財年：舊財年 Q4（2025-07-31）與新財年 Q1..Q3（2026-03-31..09-30）間隔 5 個月過渡期，不得跨過渡期硬湊 TTM FCF
        String[][] fergCfRows = {
                {"2025-07-31", "2025", "4", "471"},
                {"2025-12-31", "2025", "7", "674"},
                {"2026-03-31", "2026", "1", "680"},
                {"2026-06-30", "2026", "2", "-198"},
                {"2026-09-30", "2026", "3", "648"}
        };
        for (String[] item : fergCfRows) {
            FinancialReport cf = new FinancialReport();
            cf.setFigi("FERG2_FIGI");
            cf.setStatementType(3);
            cf.setFiscalYear(Integer.parseInt(item[1]));
            cf.setPeriodType(Integer.parseInt(item[2]));
            cf.setPeriodEnd(LocalDate.parse(item[0]));
            cf.setCurrency("USD");
            cf.setAccountingStandards("US_GAAP");
            cf.setObservedAt(HIST_CLOSE.minusSeconds(600));
            cf.setItems(List.of(new FinancialReport.Item(8072L, "Free Cash Flow", new BigDecimal(item[3]), null, null)));
            batchReports.add(cf);
        }

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(batchReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(batchSec);

        valuationService.rebuildFinancialPeriods(List.of("GIPR_FIGI", "AMZN_FIGI", "FERG2_FIGI"));

        StockFinancialPeriod giprRow = storedPeriods.get("GIPR_FIGI").getFirst();
        assertThat(giprRow.getCommonEquity()).isEqualByComparingTo("16767");
        assertThat(giprRow.getNoncontrollingInterest()).isEqualByComparingTo("14291585");
        assertThat(giprRow.getEvAdjustment()).isEqualByComparingTo("13423842");

        StockFinancialPeriod amznRow = storedPeriods.get("AMZN_FIGI").getFirst();
        assertThat(amznRow.getTotalDebt()).isEqualByComparingTo("194827");
        assertThat(amznRow.getEvAdjustment()).isEqualByComparingTo("139044");

        StockFinancialPeriod fergLatest = storedPeriods.get("FERG2_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 9, 30).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(fergLatest.getFreeCashFlowTtm()).isNull();
        assertThat(fergLatest.getFreeCashFlowTtmReason()).isEqualTo(ValuationReason.TTM_QUARTERS_MISSING);
        assertThat(fergLatest.getFreeCashFlowTtmPeriodStart()).isNotEqualTo(LocalDate.of(2025, 5, 1));
    }

    @Test
    void short10KtTransitionAnnualIsNotTreatedAsFullYearAnd16WeekRetailQuarterUsesExactStartDate() {
        ActiveStockWithStatus ferg = target("FERG_10KT_FIGI", "FERG", "0002011641", "Ferguson Enterprises Inc", "5070");
        ActiveStockWithStatus cost = target("COST_FIGI", "COST", "0000909832", "Costco Wholesale Corp", "5331");
        stubStocksAndStatuses(List.of(ferg, cost));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(
                simpleObs("FERG_10KT_FIGI", 195545957L), simpleObs("COST_FIGI", 443000000L)));

        List<FinancialReport> batchReports = new ArrayList<>();
        List<SecFinancialFact> batchSec = new ArrayList<>();

        // 1) FERG 2025-12-31：5 個月 10-KT 過渡報表（2025-08-01..2025-12-31）被富途標為 periodType=7，不得當成 12 個月年報輸出 FCF 674
        FinancialReport fergBal = new FinancialReport();
        fergBal.setFigi("FERG_10KT_FIGI");
        fergBal.setStatementType(2);
        fergBal.setPeriodType(7);
        fergBal.setFiscalYear(2025);
        fergBal.setPeriodEnd(LocalDate.of(2025, 12, 31));
        fergBal.setCurrency("USD");
        fergBal.setAccountingStandards("US_GAAP");
        fergBal.setObservedAt(HIST_CLOSE.minusSeconds(600));
        fergBal.setItems(List.of(
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("5700"), null, null),
                new FinancialReport.Item(8069L, "-Long Term Debt", new BigDecimal("3500"), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal("557"), null, null)));
        FinancialReport fergStubCf = new FinancialReport();
        fergStubCf.setFigi("FERG_10KT_FIGI");
        fergStubCf.setStatementType(3);
        fergStubCf.setPeriodType(7);
        fergStubCf.setFiscalYear(2025);
        fergStubCf.setPeriodEnd(LocalDate.of(2025, 12, 31));
        fergStubCf.setCurrency("USD");
        fergStubCf.setAccountingStandards("US_GAAP");
        fergStubCf.setObservedAt(HIST_CLOSE.minusSeconds(600));
        fergStubCf.setItems(List.of(new FinancialReport.Item(8072L, "Free Cash Flow", new BigDecimal("674"), null, null)));
        batchReports.add(fergBal);
        batchReports.add(fergStubCf);

        SecFinancialFact stubCfo = new SecFinancialFact();
        stubCfo.setCik("0002011641");
        stubCfo.setTaxonomy("us-gaap");
        stubCfo.setTag("NetCashProvidedByUsedInOperatingActivities");
        stubCfo.setUnit("USD");
        stubCfo.setPeriodStart(LocalDate.of(2025, 8, 1));
        stubCfo.setPeriodEnd(LocalDate.of(2025, 12, 31));
        stubCfo.setFiled(LocalDate.of(2026, 2, 27));
        stubCfo.setAvailableFromDate(LocalDate.of(2026, 2, 28));
        stubCfo.setObservedAt(HIST_CLOSE.minusSeconds(3600));
        stubCfo.setAccession("0002011641-26-000010");
        stubCfo.setForm("10-KT");
        stubCfo.setValue(new BigDecimal("859"));
        batchSec.add(stubCfo);

        // 2) COST：12+12+12+16 週財年（Q3 2025-05-11 -> Q4 2025-08-31 相隔 112 天），截至 2026-05-10 的 TTM 起日應為 2025-05-12 而非 2025-06-02
        String[][] costQuarters = {
                {"2025-05-11", "2025", "3", "63000"},
                {"2025-08-31", "2025", "4", "86000"},
                {"2025-11-23", "2026", "1", "67000"},
                {"2026-02-15", "2026", "2", "69000"},
                {"2026-05-10", "2026", "3", "71587"}
        };
        for (String[] q : costQuarters) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("COST_FIGI");
            inc.setStatementType(1);
            inc.setFiscalYear(Integer.parseInt(q[1]));
            inc.setPeriodType(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("US_GAAP");
            inc.setObservedAt(HIST_CLOSE.minusSeconds(600));
            inc.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[3]), null, null)));
            batchReports.add(inc);
        }

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(batchReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(batchSec);

        valuationService.rebuildFinancialPeriods(List.of("FERG_10KT_FIGI", "COST_FIGI"));

        StockFinancialPeriod ferg10KtRow = storedPeriods.get("FERG_10KT_FIGI").stream()
                .filter(p -> LocalDate.of(2025, 12, 31).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(ferg10KtRow.getFreeCashFlowTtm()).isNull();
        assertThat(ferg10KtRow.getCommonEquity()).isEqualByComparingTo("5700");

        StockFinancialPeriod costQ3Row = storedPeriods.get("COST_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 5, 10).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(costQ3Row.getRevenueTtm()).isEqualByComparingTo("293587");
        assertThat(costQ3Row.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 5, 12));
    }

    @Test
    void fergPostTransitionRevenueTtmStartAndParentEquityConflictPbStayAccurate() {
        ActiveStockWithStatus ferg = target("FERG_REV_FIGI", "FERG", "0002011641", "Ferguson Enterprises Inc", "5070");
        ActiveStockWithStatus dpz = target("DPZ_FIGI", "DPZ", "0001286681", "Domino's Pizza Inc", "5812");
        ActiveStockWithStatus eqConflict = target("EQ_CONF_FIGI", "EQCF", "0001999999", "Equity Conflict Corp", "7370");
        eqConflict.status().setSplitFromDate(null);
        eqConflict.status().setSplitDate(null);
        stubStocksAndStatuses(List.of(ferg, dpz, eqConflict));

        StockShareObservation eqObs = simpleObs("EQ_CONF_FIGI", 100L);
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(
                simpleObs("FERG_REV_FIGI", 195545957L), simpleObs("DPZ_FIGI", 34000000L), eqObs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq("EQ_CONF_FIGI"), eq("FUTU"), any()))
                .thenReturn(Optional.of(eqObs));
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq("EQ_CONF_FIGI"), any(), any()))
                .thenReturn(List.of(quote("EQ_CONF_FIGI", LAST_CLOSED, "3")));

        List<FinancialReport> batchReports = new ArrayList<>();
        List<SecFinancialFact> batchSec = new ArrayList<>();

        // 1) FERG：舊財年 Q3 (2025-04-30)、SEC 新曆比較期 (2025-07-01..09-30)、舊曆 Q1 (2025-08-01..10-31)、5 個月 10-KT (2025-08-01..12-31)
        //    與富途重述 Q4 (2025-12-31) + 2026 Q1..Q3：2025-12-31 單季與 2026-09-30 TTM 起日皆應為 2025-10-01（非 2025-10-02）
        String[][] fergIncomes = {
                {"2025-04-30", "2025", "3", "7621", "2.10"},
                {"2025-12-31", "2025", "4", "7495", "2.05"},
                {"2025-12-31", "2025", "7", "12833", "3.40"},
                {"2026-03-31", "2026", "1", "7472", "2.00"},
                {"2026-06-30", "2026", "2", "8751", "2.75"},
                {"2026-09-30", "2026", "3", "9026", "2.95"}
        };
        for (String[] q : fergIncomes) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("FERG_REV_FIGI");
            inc.setStatementType(1);
            inc.setFiscalYear(Integer.parseInt(q[1]));
            inc.setPeriodType(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("NonUS_GAAP");
            inc.setObservedAt(CLOSE.minusSeconds(600));
            inc.setItems(List.of(
                    new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[3]), null, null),
                    new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal(q[4]), null, null)));
            batchReports.add(inc);
        }
        String[][] fergSecRevs = {
                {"2025-07-01", "2025-09-30", "10-Q", "2026-11-09", "8245"},
                {"2025-08-01", "2025-10-31", "10-Q", "2025-12-09", "8169"},
                {"2025-08-01", "2025-12-31", "10-KT", "2026-02-27", "12833"},
                {"2026-01-01", "2026-03-31", "10-Q", "2026-05-05", "7472"},
                {"2026-04-01", "2026-06-30", "10-Q", "2026-08-10", "8751"},
                {"2026-07-01", "2026-09-30", "10-Q", "2026-11-09", "9026"}
        };
        int fergAcc = 0;
        for (String[] s : fergSecRevs) {
            SecFinancialFact f = new SecFinancialFact();
            f.setCik("0002011641");
            f.setTaxonomy("us-gaap");
            f.setTag("Revenues");
            f.setUnit("USD");
            f.setPeriodStart(LocalDate.parse(s[0]));
            f.setPeriodEnd(LocalDate.parse(s[1]));
            f.setForm(s[2]);
            f.setFiled(LocalDate.parse(s[3]));
            f.setAvailableFromDate(LocalDate.parse(s[3]).plusDays(1));
            f.setObservedAt(CLOSE.minusSeconds(3600));
            f.setAccession(String.format("0002011641-26-%06d", ++fergAcc));
            f.setValue(new BigDecimal(s[4]));
            batchSec.add(f);
        }

        // 2) DPZ：12+12+12+16 週財年（2025-09-07 -> 2025-12-28 相隔 112 天），截至 2026-09-06 TTM 起日維持 2025-09-08
        String[][] dpzQuarters = {
                {"2025-09-07", "2025", "3", "1140"},
                {"2025-12-28", "2025", "4", "1540"},
                {"2026-03-22", "2026", "1", "1110"},
                {"2026-06-14", "2026", "2", "1130"},
                {"2026-09-06", "2026", "3", "1286"}
        };
        for (String[] q : dpzQuarters) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("DPZ_FIGI");
            inc.setStatementType(1);
            inc.setFiscalYear(Integer.parseInt(q[1]));
            inc.setPeriodType(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("US_GAAP");
            inc.setObservedAt(CLOSE.minusSeconds(600));
            inc.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[3]), null, null)));
            batchReports.add(inc);
        }

        // 3) 832d947 母公司權益回歸：同季兩份 SEC StockholdersEquity (80, 90)、SEC 總權益 100、SEC NCI 0；
        //    富途總權益 100、母公司權益 150、缺 NCI；市值 300 -> 普通股權益應為 100，P/B 應為 3.0（非 2.0）
        FinancialReport eqBal = new FinancialReport();
        eqBal.setFigi("EQ_CONF_FIGI");
        eqBal.setStatementType(2);
        eqBal.setPeriodType(3);
        eqBal.setFiscalYear(2026);
        eqBal.setPeriodEnd(LocalDate.of(2026, 9, 30));
        eqBal.setCurrency("USD");
        eqBal.setAccountingStandards("US_GAAP");
        eqBal.setObservedAt(CLOSE.minusSeconds(600));
        eqBal.setItems(List.of(
                new FinancialReport.Item(8084L, "Total Equity", new BigDecimal("100"), null, null),
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("150"), null, null)));
        batchReports.add(eqBal);

        SecFinancialFact se80 = secBalanceFact("0001999999", "2026-09-30", "10-Q", "StockholdersEquity", "80");
        se80.setAccession("0001999999-26-000001");
        SecFinancialFact se90 = secBalanceFact("0001999999", "2026-09-30", "10-Q", "StockholdersEquity", "90");
        se90.setAccession("0001999999-26-000002");
        SecFinancialFact seTot = secBalanceFact("0001999999", "2026-09-30", "10-Q",
                "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest", "100");
        seTot.setAccession("0001999999-26-000001");
        SecFinancialFact seMin = secBalanceFact("0001999999", "2026-09-30", "10-Q", "MinorityInterest", "0");
        seMin.setAccession("0001999999-26-000001");
        batchSec.addAll(List.of(se80, se90, seTot, seMin));

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(batchReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(batchSec);

        valuationService.rebuildFinancialPeriods(List.of("FERG_REV_FIGI", "DPZ_FIGI", "EQ_CONF_FIGI"));

        StockFinancialPeriod fergQ4 = storedPeriods.get("FERG_REV_FIGI").stream()
                .filter(p -> LocalDate.of(2025, 12, 31).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(fergQ4.getPeriodStart()).isEqualTo(LocalDate.of(2025, 10, 1));

        StockFinancialPeriod fergLatest = storedPeriods.get("FERG_REV_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 9, 30).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(fergLatest.getRevenueTtm()).isEqualByComparingTo("32744");
        assertThat(fergLatest.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(fergLatest.getEpsTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 10, 1));

        StockFinancialPeriod dpzLatest = storedPeriods.get("DPZ_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 9, 6).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(dpzLatest.getRevenueTtm()).isEqualByComparingTo("5066");
        assertThat(dpzLatest.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 9, 8));

        StockFinancialPeriod eqPeriod = storedPeriods.get("EQ_CONF_FIGI").getFirst();
        assertThat(eqPeriod.getCommonEquity()).isEqualByComparingTo("100");
        List<ValuationResultDto> eqEvaluated = valuationService.recalculate("EQ_CONF_FIGI", LAST_CLOSED, LAST_CLOSED);
        assertThat(eqEvaluated).singleElement().satisfies(r ->
                assertThat(r.metrics().pb().value()).isEqualByComparingTo("3.0"));
    }

    @Test
    void ambiguousFutuPriorsAndSecVerified16WeekQuartersResolveExactTtmStartDates() {
        ActiveStockWithStatus amb = target("AMB_PRIOR_FIGI", "AMBP", "0001888881", "Ambiguous Prior Corp", "5812");
        ActiveStockWithStatus sec16w = target("SEC_16W_FIGI", "S16W", "0001888882", "Sec 16W Corp", "5812");
        stubStocksAndStatuses(List.of(amb, sec16w));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(
                simpleObs("AMB_PRIOR_FIGI", 10000000L), simpleObs("SEC_16W_FIGI", 10000000L)));

        List<FinancialReport> batchReports = new ArrayList<>();
        List<SecFinancialFact> batchSec = new ArrayList<>();

        // 1) 富途前季同時有 09-07 與 09-21 兩個合規候選，SEC 唯一單季支持 09-21 -> TTM 起日應為 2025-09-22
        String[][] ambQuarters = {
                {"2025-09-07", "2025", "3", "100"},
                {"2025-09-21", "2025", "3", "105"},
                {"2025-12-28", "2025", "4", "120"},
                {"2026-03-29", "2026", "1", "110"},
                {"2026-06-28", "2026", "2", "115"},
                {"2026-09-27", "2026", "3", "125"}
        };
        for (String[] q : ambQuarters) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("AMB_PRIOR_FIGI");
            inc.setStatementType(1);
            inc.setFiscalYear(Integer.parseInt(q[1]));
            inc.setPeriodType(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("US_GAAP");
            inc.setObservedAt(CLOSE.minusSeconds(600));
            inc.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[3]), null, null)));
            batchReports.add(inc);
        }
        SecFinancialFact secPriorQ3 = new SecFinancialFact();
        secPriorQ3.setCik("0001888881");
        secPriorQ3.setTaxonomy("us-gaap");
        secPriorQ3.setTag("Revenues");
        secPriorQ3.setUnit("USD");
        secPriorQ3.setPeriodStart(LocalDate.of(2025, 6, 23));
        secPriorQ3.setPeriodEnd(LocalDate.of(2025, 9, 21));
        secPriorQ3.setForm("10-Q");
        secPriorQ3.setFiled(LocalDate.of(2025, 10, 30));
        secPriorQ3.setAvailableFromDate(LocalDate.of(2025, 10, 31));
        secPriorQ3.setObservedAt(CLOSE.minusSeconds(3600));
        secPriorQ3.setAccession("0001888881-25-000001");
        secPriorQ3.setValue(new BigDecimal("105"));
        batchSec.add(secPriorQ3);

        // 2) 富途前季 (09-07) 缺 fiscalYear，且 SEC 全年 (2024-12-30..2025-12-28) 與前九月 (2024-12-30..2025-09-07) 佐證 16 週 Q4 起日 2025-09-08
        String[][] s16wQuarters = {
                {"2025-09-07", null, "3", "1140"},
                {"2025-12-28", "2025", "4", "1540"},
                {"2026-03-22", "2026", "1", "1110"},
                {"2026-06-14", "2026", "2", "1130"},
                {"2026-09-06", "2026", "3", "1286"}
        };
        for (String[] q : s16wQuarters) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("SEC_16W_FIGI");
            inc.setStatementType(1);
            inc.setFiscalYear(q[1] == null ? null : Integer.parseInt(q[1]));
            inc.setPeriodType(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("US_GAAP");
            inc.setObservedAt(CLOSE.minusSeconds(600));
            inc.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[3]), null, null)));
            batchReports.add(inc);
        }
        String[][] s16wSec = {
                {"2024-12-30", "2025-09-07", "10-Q", "2025-10-15", "3400"},
                {"2024-12-30", "2025-12-28", "10-K", "2026-02-20", "4940"},
                {"2025-09-08", "2025-12-28", "10-K", "2026-02-20", "1540"}
        };
        int acc = 0;
        for (String[] s : s16wSec) {
            SecFinancialFact f = new SecFinancialFact();
            f.setCik("0001888882");
            f.setTaxonomy("us-gaap");
            f.setTag("Revenues");
            f.setUnit("USD");
            f.setPeriodStart(LocalDate.parse(s[0]));
            f.setPeriodEnd(LocalDate.parse(s[1]));
            f.setForm(s[2]);
            f.setFiled(LocalDate.parse(s[3]));
            f.setAvailableFromDate(LocalDate.parse(s[3]).plusDays(1));
            f.setObservedAt(CLOSE.minusSeconds(3600));
            f.setAccession(String.format("0001888882-26-%06d", ++acc));
            f.setValue(new BigDecimal(s[4]));
            batchSec.add(f);
        }

        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenReturn(batchReports);
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(batchSec);

        valuationService.rebuildFinancialPeriods(List.of("AMB_PRIOR_FIGI", "SEC_16W_FIGI"));

        StockFinancialPeriod ambLatest = storedPeriods.get("AMB_PRIOR_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 9, 27).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(ambLatest.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 9, 22));

        StockFinancialPeriod s16wLatest = storedPeriods.get("SEC_16W_FIGI").stream()
                .filter(p -> LocalDate.of(2026, 9, 6).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(s16wLatest.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2025, 9, 8));

        // 3) 已確認的 16 週 Q4（2024-12-29）在富途前季有歧義（2024-12-15 與 2024-12-29）時，仍可支撐後續四季（2025 Q1..Q4 至 2025-12-28）純 FUTU TTM 起日 2024-12-30
        ActiveStockWithStatus nextFrom16w = target("NEXT_FROM_16W_FIGI", "NX16", "0001888883", "Next From 16W Corp", "5812");
        stubStocksAndStatuses(List.of(amb, sec16w, nextFrom16w));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(
                simpleObs("AMB_PRIOR_FIGI", 10000000L), simpleObs("SEC_16W_FIGI", 10000000L), simpleObs("NEXT_FROM_16W_FIGI", 10000000L)));
        String[][] fy25After16w = {
                {"2024-12-15", "2024", "4", "1400"},
                {"2024-12-29", "2024", "4", "1450"},
                {"2025-03-23", "2025", "1", "1080"},
                {"2025-06-15", "2025", "2", "1100"},
                {"2025-09-07", "2025", "3", "1140"},
                {"2025-12-28", "2025", "4", "1540"}
        };
        for (String[] q : fy25After16w) {
            FinancialReport inc = new FinancialReport();
            inc.setFigi("NEXT_FROM_16W_FIGI");
            inc.setStatementType(1);
            inc.setFiscalYear(Integer.parseInt(q[1]));
            inc.setPeriodType(Integer.parseInt(q[2]));
            inc.setPeriodEnd(LocalDate.parse(q[0]));
            inc.setCurrency("USD");
            inc.setAccountingStandards("US_GAAP");
            inc.setObservedAt(CLOSE.minusSeconds(600));
            inc.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(q[3]), null, null)));
            batchReports.add(inc);
        }
        String[][] fy24Sec16w = {
                {"2024-01-01", "2024-09-08", "10-Q", "2024-10-15", "3200"},
                {"2024-01-01", "2024-12-29", "10-K", "2025-02-20", "4650"},
                {"2024-09-09", "2024-12-29", "10-K", "2025-02-20", "1450"}
        };
        for (String[] s : fy24Sec16w) {
            SecFinancialFact f = new SecFinancialFact();
            f.setCik("0001888883");
            f.setTaxonomy("us-gaap");
            f.setTag("Revenues");
            f.setUnit("USD");
            f.setPeriodStart(LocalDate.parse(s[0]));
            f.setPeriodEnd(LocalDate.parse(s[1]));
            f.setForm(s[2]);
            f.setFiled(LocalDate.parse(s[3]));
            f.setAvailableFromDate(LocalDate.parse(s[3]).plusDays(1));
            f.setObservedAt(CLOSE.minusSeconds(3600));
            f.setAccession(String.format("0001888883-25-%06d", ++acc));
            f.setValue(new BigDecimal(s[4]));
            batchSec.add(f);
        }

        valuationService.rebuildFinancialPeriods(List.of("NEXT_FROM_16W_FIGI"));
        StockFinancialPeriod after16w = storedPeriods.get("NEXT_FROM_16W_FIGI").stream()
                .filter(p -> LocalDate.of(2025, 12, 28).equals(p.getPeriodEnd())).findFirst().orElseThrow();
        assertThat(after16w.getRevenueTtmSource()).isEqualTo("FUTU");
        assertThat(after16w.getRevenueTtm()).isEqualByComparingTo("4860");
        assertThat(after16w.getRevenueTtmPeriodStart()).isEqualTo(LocalDate.of(2024, 12, 30));
    }

    private SecFinancialFact secBalanceFact(String cik, String periodEnd, String form, String tag, String val) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik(cik);
        fact.setTaxonomy("us-gaap");
        fact.setTag(tag);
        fact.setUnit("USD");
        fact.setPeriodStart(null);
        fact.setPeriodEnd(LocalDate.parse(periodEnd));
        fact.setFiled(LocalDate.parse(periodEnd).plusDays(35));
        fact.setAvailableFromDate(LocalDate.parse(periodEnd).plusDays(36));
        fact.setObservedAt(HIST_CLOSE.minusSeconds(3600));
        fact.setAccession(cik + "-26-000099");
        fact.setForm(form);
        fact.setValue(new BigDecimal(val));
        return fact;
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
