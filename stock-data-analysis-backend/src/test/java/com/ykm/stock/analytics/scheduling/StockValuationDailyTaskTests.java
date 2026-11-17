package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.FinancialsDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
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
import com.ykm.stock.analytics.service.valuation.StockValuationService.ReportValuationBundle;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 驗收測試：財季分母表（stock_financial_period）＋最新估值快照（stock_valuation_latest）＋即時歷史估值與財務面板。
 */
class StockValuationDailyTaskTests {
    private static final LocalDate DAY_NOV_12 = LocalDate.of(2026, 11, 12);
    private static final Instant CLOSE_NOV_12 = Instant.parse("2026-11-12T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

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
    private final List<FinancialReport> currentReports = new ArrayList<>();

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
        when(clock.nowLocalDateTime()).thenReturn(LocalDateTime.of(2026, 11, 13, 18, 0));
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(DAY_NOV_12);
        when(calendar.getSessionCloseAt(any())).thenReturn(CLOSE_NOV_12);
        when(calendar.getSessionOpenAt(any())).thenReturn(CLOSE_NOV_12.minusSeconds(6 * 3600));

        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof StockFinancialPeriod p) {
                storedPeriods.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
            } else if (arg instanceof StockValuationLatest l) {
                storedLatest.put(l.getFigi(), l);
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
        when(reports.findByFigiInOrderByFigiAscPeriodEndAsc(anyCollection())).thenAnswer(inv -> List.copyOf(currentReports));
    }

    /**
     * Scenario：11-12 收市時僅有 Q2，暫用 Q2 分母；11-13 入庫 Q3 後整股重建財季分母，查詢 11-12 自動改用 Q3；
     * 後續 Q4（10-01 至 12-31）入庫重建後，查詢 11-12 自動配對 Q4（period_start <= D <= period_end），無須重算每日估值表。
     */
    @Test
    void lateArrivingQ3AndQ4AutomaticallyUpdateHistoricalNov12ValuationWithoutDailyTableBackfill() {
        ActiveStockWithStatus target = stockWithStatus("F_TEST", "ACME");
        stubStock(target, "100", 10000000L);

        // 階段 1：僅有 2025Q3..2026Q2 四季（EPS 合計 = 1+1+1+1 = 4 -> PE = 100 / 4 = 25）
        currentReports.addAll(fourConsecutiveQuarters("F_TEST",
                new String[]{"2025-09-30", "3", "2025", "1.00"},
                new String[]{"2025-12-31", "4", "2025", "1.00"},
                new String[]{"2026-03-31", "1", "2026", "1.00"},
                new String[]{"2026-06-30", "2", "2026", "1.00"}));
        valuationService.rebuildAndRefreshLatest(List.of("F_TEST"));

        ReportValuationBundle stage1 = valuationService.evaluateForReport(target.stock(), DAY_NOV_12, CLOSE_NOV_12, NOW, null);
        assertThat(stage1.valuation().metrics().pe().value()).isEqualByComparingTo("25");
        assertThat(stage1.financials().periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));

        // 階段 2：11-13 補入 2026Q3（2026-09-30，EPS=2.00 -> TTM EPS = 1+1+1+2 = 5 -> PE = 100 / 5 = 20）
        currentReports.addAll(singleQuarter("F_TEST", "2026-09-30", 3, 2026, "2.00"));
        valuationService.rebuildAndRefreshLatest(List.of("F_TEST"));

        ReportValuationBundle stage2 = valuationService.evaluateForReport(target.stock(), DAY_NOV_12, CLOSE_NOV_12, NOW, null);
        assertThat(stage2.valuation().metrics().pe().value()).isEqualByComparingTo("20");
        assertThat(stage2.financials().periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));

        // 階段 3：後續 2026Q4（2026-10-01 至 2026-12-31，EPS=5.00 -> TTM EPS = 1+1+2+5 = 9 -> PE = 100 / 9 = 11.1111）到齊
        when(clock.now()).thenReturn(Instant.parse("2027-02-15T10:00:00Z"));
        currentReports.addAll(singleQuarter("F_TEST", "2026-12-31", 4, 2026, "5.00"));
        valuationService.rebuildFinancialPeriods(List.of("F_TEST"));

        ReportValuationBundle stage3 = valuationService.evaluateForReport(target.stock(), DAY_NOV_12, CLOSE_NOV_12, CLOSE_NOV_12, null);
        assertThat(stage3.valuation().metrics().pe().value()).isEqualByComparingTo("11.1111");
        assertThat(stage3.financials().periodEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    /**
     * Scenario：負盈利／負 FCF／負普通股權益在 stock_financial_period 與 FinancialsDto 保留真實負值供畫面顯示（AVAILABLE），
     * 但計算估值倍數時標為 NOT_APPLICABLE（N.M.）且倍數為 null。
     */
    @Test
    void negativeDenominatorsKeepActualFinancialValuesInPanelWhileMultiplesAreNotApplicable() {
        ActiveStockWithStatus target = stockWithStatus("F_LOSS", "LOSS");
        stubStock(target, "50", 20000000L);

        LocalDate end = LocalDate.of(2025, 12, 31);
        FinancialReport inc = new FinancialReport();
        inc.setFigi("F_LOSS");
        inc.setStatementType(1);
        inc.setPeriodType(7);
        inc.setFiscalYear(2025);
        inc.setPeriodEnd(end);
        inc.setCurrency("USD");
        inc.setAccountingStandards("US_GAAP");
        inc.setObservedAt(CLOSE_NOV_12.minusSeconds(300));
        inc.setItems(List.of(
                new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal("-2.50"), null, null),
                new FinancialReport.Item(8046L, "Net Income to Common Stockholders", new BigDecimal("-50000000"), null, null),
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("200000000"), null, null),
                new FinancialReport.Item(8017L, "Operating Profit", new BigDecimal("-40000000"), null, null)));

        FinancialReport bal = new FinancialReport();
        bal.setFigi("F_LOSS");
        bal.setStatementType(2);
        bal.setPeriodType(7);
        bal.setFiscalYear(2025);
        bal.setPeriodEnd(end);
        bal.setCurrency("USD");
        bal.setAccountingStandards("US_GAAP");
        bal.setObservedAt(CLOSE_NOV_12.minusSeconds(300));
        bal.setItems(List.of(
                new FinancialReport.Item(8085L, "Stockholders' Equity", new BigDecimal("-100000000"), null, null),
                new FinancialReport.Item(8069L, "-Long Term Debt", new BigDecimal("300000000"), null, null),
                new FinancialReport.Item(8004L, "-Cash and Cash Equivalents", new BigDecimal("80000000"), null, null)));

        FinancialReport cf = new FinancialReport();
        cf.setFigi("F_LOSS");
        cf.setStatementType(3);
        cf.setPeriodType(7);
        cf.setFiscalYear(2025);
        cf.setPeriodEnd(end);
        cf.setCurrency("USD");
        cf.setAccountingStandards("US_GAAP");
        cf.setObservedAt(CLOSE_NOV_12.minusSeconds(300));
        cf.setItems(List.of(
                new FinancialReport.Item(8015L, "Operating Cash Flow", new BigDecimal("-20000000"), null, null),
                new FinancialReport.Item(8044L, "Capital Expenditure", new BigDecimal("-15000000"), null, null),
                new FinancialReport.Item(8019L, "Depreciation & Depletion & Amortization", new BigDecimal("10000000"), null, null),
                new FinancialReport.Item(8072L, "Free Cash Flow", new BigDecimal("-35000000"), null, null)));

        currentReports.addAll(List.of(inc, bal, cf));
        valuationService.rebuildAndRefreshLatest(List.of("F_LOSS"));

        ReportValuationBundle bundle = valuationService.evaluateForReport(target.stock(), DAY_NOV_12, CLOSE_NOV_12, NOW, null);
        FinancialsDto fin = bundle.financials();
        assertThat(fin).isNotNull();
        assertThat(fin.epsTtm().status()).isEqualTo("AVAILABLE");
        assertThat(fin.epsTtm().value()).isEqualByComparingTo("-2.50");
        assertThat(fin.freeCashFlowTtm().status()).isEqualTo("AVAILABLE");
        assertThat(fin.freeCashFlowTtm().value()).isEqualByComparingTo("-35000000");
        assertThat(fin.commonEquity().status()).isEqualTo("AVAILABLE");
        assertThat(fin.commonEquity().value()).isEqualByComparingTo("-100000000");
        assertThat(fin.capitalExpenditureTtm().value()).isEqualByComparingTo("15000000");
        assertThat(fin.ebitdaTtm().value()).isEqualByComparingTo("-30000000");

        ValuationResultDto val = bundle.valuation();
        assertThat(val.metrics().pe().value()).isNull();
        assertThat(val.metrics().pe().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(val.metrics().pe().reasonCode()).isEqualTo("NON_POSITIVE_EPS");
        assertThat(val.metrics().pb().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(val.metrics().pb().reasonCode()).isEqualTo("NON_POSITIVE_COMMON_EQUITY");
        assertThat(val.metrics().pfcf().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(val.metrics().pfcf().reasonCode()).isEqualTo("NON_POSITIVE_FCF");
        assertThat(val.metrics().evEbitda().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(val.metrics().evEbitda().reasonCode()).isEqualTo("NON_POSITIVE_EBITDA");
        // P/S 仍正常可用（50 * 20M = 1B ÷ 200M = 5）
        assertThat(val.metrics().ps().value()).isEqualByComparingTo("5");
    }

    /**
     * Scenario：富途股數下載失敗時，排程仍使用既有可信股數保存最新估值快照，並在最後回報 SHARE_OBSERVATION_SYNC_FAILED。
     */
    @Test
    void dailyTaskSavesEligibleLatestValuationsBeforeReportingShareSyncFailure() {
        ActiveStockWithStatus target = stockWithStatus("F_OK", "OK");
        stubStock(target, "80", 10000000L);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(target));
        when(futu.openObservationSession()).thenThrow(new IllegalStateException("FUTU_DISCONNECTED"));
        currentReports.addAll(fourConsecutiveQuarters("F_OK",
                new String[]{"2025-09-30", "3", "2025", "1.00"},
                new String[]{"2025-12-31", "4", "2025", "1.00"},
                new String[]{"2026-03-31", "1", "2026", "1.00"},
                new String[]{"2026-06-30", "2", "2026", "1.00"}));

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(new JobDataMap());

        assertThatThrownBy(() -> task.execute(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SHARE_OBSERVATION_SYNC_FAILED");

        assertThat(storedLatest.get("F_OK")).isNotNull();
        assertThat(storedLatest.get("F_OK").getPe()).isEqualByComparingTo("20");
    }

    /**
     * Scenario：當股票最新入庫行情日（11-13）早於日曆最新已收市日（11-16）時，stock_valuation_latest 保存 11-13 有效倍數而非 CLOSING_PRICE_MISSING；
     * 且美國公司第四季（無單季 10-Q XBRL 標籤）跨越拆股時，透過同日 10-K 年度 EPS 與 Q1..Q4 加總一致性完成驗證。
     */
    @Test
    void latestSnapshotUsesLatestStoredQuoteSessionAndVerifiesSplitCrossingQ4ViaAnnual10K() {
        LocalDate calendarLastClosed = LocalDate.of(2026, 11, 16);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(calendarLastClosed);
        ActiveStockWithStatus target = stockWithStatus("F_SPLIT", "SPLT");
        target.stock().setCik("0001045810");
        target.status().setQuoteDate(DAY_NOV_12);
        stubStock(target, "100", 100000000L);

        com.ykm.stock.analytics.model.entity.StockSplit split = new com.ykm.stock.analytics.model.entity.StockSplit();
        split.setSourceEventId("SPLT:2026-06-10");
        split.setFigi("F_SPLIT");
        split.setExecutionDate(LocalDate.of(2026, 6, 10));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(BigDecimal.TEN);
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));

        // FY2025 Q1..Q4 + FY2025 年報（1.20）+ FY2026 Q1（0.50），SEC 僅有 10-Q 單季與 10-K 全年（無 Q4 單季標籤）
        currentReports.addAll(fourConsecutiveQuarters("F_SPLIT",
                new String[]{"2025-03-31", "1", "2025", "0.20"},
                new String[]{"2025-06-30", "2", "2025", "0.25"},
                new String[]{"2025-09-30", "3", "2025", "0.35"},
                new String[]{"2025-12-31", "4", "2025", "0.40"}));
        currentReports.addAll(singleQuarter("F_SPLIT", "2025-12-31", 7, 2025, "1.20"));
        currentReports.addAll(singleQuarter("F_SPLIT", "2026-03-31", 1, 2026, "0.50"));

        List<com.ykm.stock.analytics.model.entity.SecFinancialFact> facts = new ArrayList<>();
        facts.add(secEpsFact("0001045810", "2025-04-01", "2025-06-30", "2026-08-01", "10-Q", "0.25"));
        facts.add(secEpsFact("0001045810", "2025-07-01", "2025-09-30", "2026-11-01", "10-Q", "0.35"));
        facts.add(secEpsFact("0001045810", "2025-01-01", "2025-12-31", "2026-02-20", "10-K", "12.00"));
        facts.add(secEpsFact("0001045810", "2026-01-01", "2026-03-31", "2026-05-01", "10-Q", "5.00"));
        when(secFacts.findPeriodRebuildFacts(anyCollection(), anyCollection(), any())).thenReturn(facts);

        valuationService.rebuildAndRefreshLatest(List.of("F_SPLIT"));

        StockValuationLatest latest = storedLatest.get("F_SPLIT");
        assertThat(latest).isNotNull();
        assertThat(latest.getValuationDate()).isEqualTo(DAY_NOV_12);
        // 2026Q1 TTM EPS = 0.25 + 0.35 + 0.40 + 0.50 = 1.50；PE = 100 / 1.50 = 66.6667
        assertThat(latest.getPe()).isEqualByComparingTo("66.6667");
        assertThat(latest.getStatus()).isEqualTo("AVAILABLE");
    }

    /**
     * Scenario：同日已有收市快照且股數未變時，若舊快照僅有 PE=20、PB=null，新快照帶回 PE=20、PB=3（單一欄位缺值補齊），
     * 必須允許寫入新快照補齊 PB；當 PE 與 PB 皆已齊備且股數未變時，重跑應跳過不重複寫入。
     */
    @Test
    void refreshShareObservationsBackfillsSingleMissingNativeMultipleAndSkipsWhenComplete() {
        ActiveStockWithStatus target = stockWithStatus("F_OBS", "OBSV");
        long sameShares = 50_000_000L;
        stubStock(target, "120", sameShares);

        List<StockShareObservation> persistedObs = new ArrayList<>();
        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof StockShareObservation obs) {
                persistedObs.add(obs);
            } else if (arg instanceof StockFinancialPeriod p) {
                storedPeriods.computeIfAbsent(p.getFigi(), k -> new ArrayList<>()).add(p);
            } else if (arg instanceof StockValuationLatest l) {
                storedLatest.put(l.getFigi(), l);
            }
            return null;
        }).when(entityManager).persist(any());

        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of("OBSV"));

        // 既有同日收市快照：PE = 20.0 已有值，但 PB = null 缺失，股數未變
        StockShareObservation partialExisting = new StockShareObservation();
        partialExisting.setFigi("F_OBS");
        partialExisting.setObservedAt(CLOSE_NOV_12.plusSeconds(60));
        partialExisting.setIssuedShares(sameShares);
        partialExisting.setOutstandingShares(sameShares);
        partialExisting.setSnapshotPrice(new BigDecimal("120"));
        partialExisting.setSnapshotMarketCap(new BigDecimal("6000000000"));
        partialExisting.setPeTtmRate(new BigDecimal("20.0"));
        partialExisting.setPbRate(null);
        partialExisting.setSnapshotQuoteTime(CLOSE_NOV_12);
        partialExisting.setSource("FUTU");

        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(partialExisting));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), any(), any()))
                .thenReturn(List.of(partialExisting));

        FutuService.ShareSnapshot incomingComplete = new FutuService.ShareSnapshot(
                sameShares, sameShares, new BigDecimal("120"), new BigDecimal("6000000000"),
                new BigDecimal("6000000000"), new BigDecimal("20.0"), new BigDecimal("3.0"), CLOSE_NOV_12);
        when(session.shareCounts(List.of("OBSV"))).thenReturn(Map.of("OBSV", incomingComplete));

        RuntimeException err1 = task.refreshShareObservations(List.of(target));
        assertThat(err1).isNull();
        assertThat(persistedObs).hasSize(1);
        assertThat(persistedObs.getFirst().getPeTtmRate()).isEqualByComparingTo("20.0");
        assertThat(persistedObs.getFirst().getPbRate()).isEqualByComparingTo("3.0");

        // 反向單欄缺失：既有 PB = 3.0、PE = null -> 新快照帶回 PE = 20.0、PB = 3.0 亦須補寫
        persistedObs.clear();
        StockShareObservation missingPeOnly = new StockShareObservation();
        missingPeOnly.setFigi("F_OBS");
        missingPeOnly.setObservedAt(CLOSE_NOV_12.plusSeconds(120));
        missingPeOnly.setIssuedShares(sameShares);
        missingPeOnly.setOutstandingShares(sameShares);
        missingPeOnly.setPeTtmRate(null);
        missingPeOnly.setPbRate(new BigDecimal("3.0"));
        missingPeOnly.setSnapshotQuoteTime(CLOSE_NOV_12);
        missingPeOnly.setSource("FUTU");
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(missingPeOnly));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), any(), any()))
                .thenReturn(List.of(missingPeOnly));

        RuntimeException err2 = task.refreshShareObservations(List.of(target));
        assertThat(err2).isNull();
        assertThat(persistedObs).hasSize(1);
        assertThat(persistedObs.getFirst().getPeTtmRate()).isEqualByComparingTo("20.0");

        // 當 PE 與 PB 均已齊備且股數未變、同日收市快照已存在時，重跑應跳過不重複寫入
        persistedObs.clear();
        StockShareObservation completeExisting = persistedObsFrom(incomingComplete, sameShares);
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(completeExisting));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), any(), any()))
                .thenReturn(List.of(completeExisting));

        RuntimeException err3 = task.refreshShareObservations(List.of(target));
        assertThat(err3).isNull();
        assertThat(persistedObs).isEmpty();
    }

    private StockShareObservation persistedObsFrom(FutuService.ShareSnapshot snap, long shares) {
        StockShareObservation obs = new StockShareObservation();
        obs.setFigi("F_OBS");
        obs.setObservedAt(CLOSE_NOV_12.plusSeconds(180));
        obs.setIssuedShares(shares);
        obs.setOutstandingShares(shares);
        obs.setSnapshotPrice(snap.price());
        obs.setSnapshotMarketCap(snap.marketCap());
        obs.setPeTtmRate(snap.peTtmRate());
        obs.setPbRate(snap.pbRate());
        obs.setSnapshotQuoteTime(snap.quoteTime());
        obs.setSource("FUTU");
        return obs;
    }

    private com.ykm.stock.analytics.model.entity.SecFinancialFact secEpsFact(
            String cik, String start, String end, String filed, String form, String val) {
        com.ykm.stock.analytics.model.entity.SecFinancialFact f = new com.ykm.stock.analytics.model.entity.SecFinancialFact();
        f.setCik(cik);
        f.setTaxonomy("us-gaap");
        f.setTag("EarningsPerShareDiluted");
        f.setUnit("USD/shares");
        f.setPeriodStart(LocalDate.parse(start));
        f.setPeriodEnd(LocalDate.parse(end));
        f.setFiled(LocalDate.parse(filed));
        f.setAvailableFromDate(LocalDate.parse(filed).plusDays(1));
        f.setObservedAt(CLOSE_NOV_12.minusSeconds(300));
        f.setAccession(cik + "-" + end);
        f.setForm(form);
        f.setValue(new BigDecimal(val));
        return f;
    }

    private void stubStock(ActiveStockWithStatus item, String price, long shares) {
        String figi = item.stock().getFigi();
        when(stocks.findById(figi)).thenReturn(Optional.of(item.stock()));
        when(stocks.findAllById(anyCollection())).thenReturn(List.of(item.stock()));
        when(statuses.findById(figi)).thenReturn(Optional.of(item.status()));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(item.status()));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(item.status()));

        StockQuote q = new StockQuote();
        q.setFigi(figi);
        q.setQuoteDate(DAY_NOV_12);
        q.setClosePrice(new BigDecimal(price));
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY_NOV_12))).thenReturn(List.of(q));
        when(quotes.findByFigiAndQuoteDate(figi, DAY_NOV_12)).thenReturn(q);
        when(quotes.findByFigiAndQuoteDateBetweenOrderByQuoteDateAsc(eq(figi), any(), any())).thenReturn(List.of(q));

        StockShareObservation obs = new StockShareObservation();
        obs.setFigi(figi);
        obs.setObservedAt(CLOSE_NOV_12.minusSeconds(600));
        obs.setIssuedShares(shares);
        obs.setOutstandingShares(shares);
        obs.setSource("FUTU");
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(obs));
        when(shareObservations.findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(eq(figi), eq("FUTU"), any()))
                .thenReturn(Optional.of(obs));
    }

    private ActiveStockWithStatus stockWithStatus(String figi, String symbol) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setLongName(symbol + " Corp");
        stock.setCurrency("USD");
        stock.setStockType("Common Stock");
        stock.setActiveStatus(ActiveStatus.ACTIVE);
        stock.setTracked(true);
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        status.setQuoteDate(DAY_NOV_12);
        status.setFinancialReportDate(LocalDate.of(2026, 6, 30));
        status.setSplitFromDate(LocalDate.of(2014, 1, 1));
        status.setSplitDate(DAY_NOV_12);
        return new ActiveStockWithStatus(stock, status);
    }

    private List<FinancialReport> fourConsecutiveQuarters(String figi, String[]... rows) {
        List<FinancialReport> list = new ArrayList<>();
        for (String[] row : rows) {
            list.addAll(singleQuarter(figi, row[0], Integer.parseInt(row[1]), Integer.parseInt(row[2]), row[3]));
        }
        return list;
    }

    private List<FinancialReport> singleQuarter(String figi, String end, int qtr, int fy, String eps) {
        FinancialReport inc = new FinancialReport();
        inc.setFigi(figi);
        inc.setStatementType(1);
        inc.setPeriodType(qtr);
        inc.setFiscalYear(fy);
        inc.setPeriodEnd(LocalDate.parse(end));
        inc.setCurrency("USD");
        inc.setAccountingStandards("US_GAAP");
        inc.setObservedAt(CLOSE_NOV_12.minusSeconds(300));
        inc.setItems(List.of(
                new FinancialReport.Item(8048L, "Diluted EPS", new BigDecimal(eps), null, null),
                new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal("50000000"), null, null)));
        return List.of(inc);
    }
}
