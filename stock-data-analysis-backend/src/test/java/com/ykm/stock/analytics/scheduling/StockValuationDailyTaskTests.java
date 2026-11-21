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
    private final com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository customPeerRepo =
            mock(com.ykm.stock.analytics.repository.jpa.StockCustomPeerRepository.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final FutuService futu = mock(FutuService.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private final Map<String, List<StockFinancialPeriod>> storedPeriods = new LinkedHashMap<>();
    private final Map<String, StockValuationLatest> storedLatest = new LinkedHashMap<>();
    private final Map<String, StockStatus> storedStatuses = new LinkedHashMap<>();
    private final List<FinancialReport> currentReports = new ArrayList<>();

    private StockValuationService valuationService;
    private StockValuationDailyTask task;
    private com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService sectorMoneyFlowService;
    private com.ykm.stock.analytics.service.analysis.DailyOpportunityRadarService dailyOpportunityRadarService;
    private com.ykm.stock.analytics.service.analysis.SignalTrackingService signalTrackingService;
    private com.ykm.stock.analytics.service.portfolio.PortfolioClosingService portfolioClosingService;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
        valuationService = new StockValuationService(
                stocks, statuses, quotes, splits, reports, secFacts, shareObservations,
                industries, industryConfigs, periodRepo, latestRepo, customPeerRepo, calendar, clock,
                new SecIssuerHistory(""), tx, entityManager);
        sectorMoneyFlowService = mock(com.ykm.stock.analytics.service.analysis.SectorMoneyFlowService.class);
        dailyOpportunityRadarService = mock(com.ykm.stock.analytics.service.analysis.DailyOpportunityRadarService.class);
        signalTrackingService = mock(com.ykm.stock.analytics.service.analysis.SignalTrackingService.class);
        portfolioClosingService = mock(com.ykm.stock.analytics.service.portfolio.PortfolioClosingService.class);
        when(portfolioClosingService.publishDailyClosing(any(), any()))
                .thenReturn(Optional.of(mock(com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto.class)));
        task = new StockValuationDailyTask(stocks, statuses, shareObservations, futu, valuationService, calendar, clock, tx, entityManager,
                latestRepo, sectorMoneyFlowService, dailyOpportunityRadarService, signalTrackingService, portfolioClosingService);

        when(clock.now()).thenReturn(NOW);
        when(clock.nowLocalDateTime()).thenReturn(LocalDateTime.of(2026, 11, 13, 18, 0));
        when(calendar.isTradingDay(any())).thenReturn(true);
        when(calendar.getLastClosedTradingDay(any())).thenReturn(DAY_NOV_12);
        when(calendar.getSessionCloseAt(any())).thenReturn(CLOSE_NOV_12);
        when(calendar.getSessionOpenAt(any())).thenReturn(CLOSE_NOV_12.minusSeconds(6 * 3600));

        // 預設大盤基準股 SPY 與 QQQ 主檔與當日拆股狀態均就緒，個別測試可覆寫模擬基準未到齊
        Stock defaultSpy = new Stock();
        defaultSpy.setFigi("DEFAULT_SPY");
        defaultSpy.setSymbol("SPY");
        defaultSpy.setStockType("ETF");
        defaultSpy.setActiveStatus(ActiveStatus.ACTIVE);
        Stock defaultQqq = new Stock();
        defaultQqq.setFigi("DEFAULT_QQQ");
        defaultQqq.setSymbol("QQQ");
        defaultQqq.setStockType("ETF");
        defaultQqq.setActiveStatus(ActiveStatus.ACTIVE);
        StockStatus defaultSpyStatus = new StockStatus();
        defaultSpyStatus.setFigi("DEFAULT_SPY");
        defaultSpyStatus.setSplitFromDate(LocalDate.of(2014, 1, 1));
        defaultSpyStatus.setSplitDate(DAY_NOV_12);
        StockStatus defaultQqqStatus = new StockStatus();
        defaultQqqStatus.setFigi("DEFAULT_QQQ");
        defaultQqqStatus.setSplitFromDate(LocalDate.of(2014, 1, 1));
        defaultQqqStatus.setSplitDate(DAY_NOV_12);
        storedStatuses.clear();
        storedStatuses.put("DEFAULT_SPY", defaultSpyStatus);
        storedStatuses.put("DEFAULT_QQQ", defaultQqqStatus);
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of("SPY", "QQQ")))
                .thenReturn(List.of(defaultSpy, defaultQqq));
        when(statuses.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            List<StockStatus> out = new ArrayList<>();
            for (String id : ids) {
                StockStatus st = storedStatuses.get(id);
                if (st != null) out.add(st);
            }
            return out;
        });

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
        facts.add(secEpsFact("0001045810", "2025-01-01", "2025-03-31", "2025-05-01", "10-Q", "2.00"));
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

    /**
     * Scenario：手動指定歷史 valuationDate 或局部 figis 重跑估值排程時，
     * 絕不得觸發步驟 4（板塊資金流、每日機會雷達、歷史訊號追蹤），避免倒序覆寫快照或污染訊號帳本。
     */
    @Test
    void manualHistoricalOrScopedValuationRunSkipsDownstreamRadarAndSignalTracking() throws Exception {
        ActiveStockWithStatus target = stockWithStatus("F_OK", "OK");
        stubStock(target, "80", 10000000L);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(target));
        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of());
        currentReports.addAll(fourConsecutiveQuarters("F_OK",
                new String[]{"2025-09-30", "3", "2025", "1.00"},
                new String[]{"2025-12-31", "4", "2025", "1.00"},
                new String[]{"2026-03-31", "1", "2026", "1.00"},
                new String[]{"2026-06-30", "2", "2026", "1.00"}));

        JobDataMap historicalParams = new JobDataMap();
        historicalParams.put("valuationDate", DAY_NOV_12.toString());
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(historicalParams);

        task.execute(ctx);

        org.mockito.Mockito.verifyNoInteractions(sectorMoneyFlowService, dailyOpportunityRadarService, signalTrackingService);
    }

    /**
     * Scenario：StockSplitDailyTask 寫入 ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:... 時，
     * 共用的 StockValuationService.resolveDayEps 必須與 evaluateSingleDay 保持一致的拆股錯誤豁免語意，正常回傳拆股對齊 EPS。
     */
    @Test
    void resolveDayEpsPreservesStoredSplitEventErrorExemptionSemantics() {
        StockStatus status = new StockStatus();
        status.setFigi("F_SPLIT_WARN");
        status.setSplitFromDate(LocalDate.of(2015, 1, 1));
        status.setSplitDate(DAY_NOV_12);
        status.setSplitMessage("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:2026-06-10");

        StockFinancialPeriod period = new StockFinancialPeriod();
        period.setFigi("F_SPLIT_WARN");
        period.setReportingCurrency("USD");
        period.setEpsTtm(new BigDecimal("10.00"));
        period.setEpsTtmStatus("AVAILABLE");
        period.setEpsShareBasisDate(LocalDate.of(2026, 3, 31));

        com.ykm.stock.analytics.model.entity.StockSplit split = new com.ykm.stock.analytics.model.entity.StockSplit();
        split.setSourceEventId("WARN-2-1");
        split.setFigi("F_SPLIT_WARN");
        split.setExecutionDate(LocalDate.of(2026, 6, 10));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(new BigDecimal("2"));

        StockValuationService.DayEpsResolution res = StockValuationService.resolveDayEps(
                "USD", status, period, List.of(split), DAY_NOV_12);
        assertThat(res.peReason()).isNull();
        assertThat(res.epsAtDay()).isEqualByComparingTo("5.00");

        // 其他實質拆股同步失敗仍須回傳 SPLIT_SYNC_ERROR
        status.setSplitMessage("ERROR:FUTU_TIMEOUT");
        StockValuationService.DayEpsResolution errRes = StockValuationService.resolveDayEps(
                "USD", status, period, List.of(split), DAY_NOV_12);
        assertThat(errRes.epsAtDay()).isNull();
        assertThat(errRes.peReason()).isEqualTo("SPLIT_SYNC_ERROR");
    }

    /**
     * Scenario：行情分批同步尚未完成時（第一批已到 D 日、第二批仍停在 D-1，覆蓋率 50% < 95%），
     * 即使已有部分 D 日估值，絕不得提前觸發雷達發布與凍結；待第二批行情補齊至 D 日（覆蓋率 100%）後重跑，才一併發布下游。
     */
    @Test
    void downstreamRadarDefersWhenMarketQuoteBatchIncompleteAndPublishesOnceComplete() throws Exception {
        ActiveStockWithStatus batch1 = stockWithStatus("F_B1", "BONE");
        ActiveStockWithStatus batch2 = stockWithStatus("F_B2", "BTWO");
        batch1.status().setQuoteDate(DAY_NOV_12);
        batch2.status().setQuoteDate(DAY_NOV_12.minusDays(1));

        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(batch1, batch2));
        when(stocks.findAllById(anyCollection())).thenReturn(List.of(batch1.stock(), batch2.stock()));
        storedStatuses.put("F_B1", batch1.status());
        storedStatuses.put("F_B2", batch2.status());
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(batch1.status(), batch2.status()));
        when(latestRepo.findMaxValuationDateAtOrBefore(DAY_NOV_12)).thenReturn(Optional.of(DAY_NOV_12));
        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of());

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(new JobDataMap());

        // 1) 第二批行情仍在 D-1：覆蓋率 1/2 = 50% < 95% -> 延後下游，不凍結也不記訊號
        task.execute(ctx);
        org.mockito.Mockito.verifyNoInteractions(sectorMoneyFlowService, dailyOpportunityRadarService, signalTrackingService);

        // 2) 第二批行情補齊至 D 日：覆蓋率 2/2 = 100% -> 觸發板塊資金流、雷達發布、歷史訊號結算與持倉收盤簡報
        batch2.status().setQuoteDate(DAY_NOV_12);
        task.execute(ctx);
        org.mockito.Mockito.verify(sectorMoneyFlowService).refreshDaily(eq(DAY_NOV_12), any());
        org.mockito.Mockito.verify(dailyOpportunityRadarService).refreshDaily(eq(DAY_NOV_12), any());
        org.mockito.Mockito.verify(signalTrackingService).settlePendingSignals(DAY_NOV_12);
        org.mockito.Mockito.verify(portfolioClosingService).publishDailyClosing(eq(DAY_NOV_12), any());
    }

    /**
     * Scenario：排程容錯隔離 — 收盤持倉體檢或站內簡報寫入拋出異常時，
     * 排程僅記錄警告日誌並正常完成，絕不中斷也不影響當日已完成的估值、板塊資金流、雷達與訊號結算。
     */
    @Test
    void portfolioClosingFailureIsIsolatedAndDoesNotAbortDailyValuationTask() throws Exception {
        ActiveStockWithStatus target = stockWithStatus("F_ISO", "ISO");
        stubStock(target, "100", 10000000L);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(target));
        when(latestRepo.findMaxValuationDateAtOrBefore(DAY_NOV_12)).thenReturn(Optional.of(DAY_NOV_12));
        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of());
        currentReports.addAll(fourConsecutiveQuarters("F_ISO",
                new String[]{"2025-09-30", "3", "2025", "1.00"},
                new String[]{"2025-12-31", "4", "2025", "1.00"},
                new String[]{"2026-03-31", "1", "2026", "1.00"},
                new String[]{"2026-06-30", "2", "2026", "1.00"}));

        when(portfolioClosingService.publishDailyClosing(eq(DAY_NOV_12), any()))
                .thenThrow(new RuntimeException("SIMULATED_BRIEFING_DB_FAILURE"));

        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getMergedJobDataMap()).thenReturn(new JobDataMap());

        // 不應拋出任何例外，且上游估值、板塊資金流、雷達、訊號結算皆已順利執行完成
        task.execute(ctx);

        assertThat(storedLatest.get("F_ISO")).isNotNull();
        org.mockito.Mockito.verify(sectorMoneyFlowService).refreshDaily(eq(DAY_NOV_12), any());
        org.mockito.Mockito.verify(dailyOpportunityRadarService).refreshDaily(eq(DAY_NOV_12), any());
        org.mockito.Mockito.verify(signalTrackingService).settlePendingSignals(DAY_NOV_12);
        org.mockito.Mockito.verify(portfolioClosingService).publishDailyClosing(eq(DAY_NOV_12), any());
    }

    @Test
    void sameDayRerunSkipsFrozenRadarAndSectorFlowWhileStillRunningSettlementAndBriefing() {
        ActiveStockWithStatus target = stockWithStatus("FIGI_FROZEN", "FRZN");
        stubStock(target, "100", 10000000L);
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(target));
        when(latestRepo.findMaxValuationDateAtOrBefore(DAY_NOV_12)).thenReturn(Optional.of(DAY_NOV_12));
        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of());
        com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository radarRunRepo =
                mock(com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository.class);
        when(radarRunRepo.existsById(DAY_NOV_12)).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "radarRunRepository", radarRunRepo);

        task.execute(null);

        org.mockito.Mockito.verify(sectorMoneyFlowService, org.mockito.Mockito.never()).refreshDaily(any(), any());
        org.mockito.Mockito.verify(dailyOpportunityRadarService, org.mockito.Mockito.never()).refreshDaily(any(), any());
        org.mockito.Mockito.verify(signalTrackingService).settlePendingSignals(DAY_NOV_12);
        org.mockito.Mockito.verify(portfolioClosingService).publishDailyClosing(eq(DAY_NOV_12), any());
    }

    /**
     * Scenario：拆股覆蓋未達 95%（含 ADR）、基準缺 QQQ 或 SPY/QQQ 拆股未到齊時，延後下游並寫入冪等 EOD_PIPELINE_DEFERRED 警告；
     * 發布前重讀最新 StockStatus 避免過期快照誤判；待補齊後重跑，自動發布下游、將當日及先前未讀延後警告標為已讀，
     * 並安全以 TaskGroup.SCHEDULE 帶 tradeDate 觸發 CHIP_EVENT_DAILY_SYNC；
     * 排程已停用（無 trigger）或排程器未啟動時安全略過觸發且不拋例外。
     */
    @Test
    void splitReadinessGuardEmitsDeferredWarningAutoMarksReadAndTriggersChipSyncSafely() throws Exception {
        ActiveStockWithStatus op1 = stockWithStatus("F_OP1", "OPONE");
        op1.status().setSplitDate(DAY_NOV_12.minusDays(1));
        StockStatus freshOp1 = new StockStatus();
        freshOp1.setFigi("F_OP1");
        freshOp1.setSplitFromDate(LocalDate.of(2014, 1, 1));
        freshOp1.setSplitDate(DAY_NOV_12.minusDays(1));
        freshOp1.setQuoteDate(DAY_NOV_12);

        Stock spy = new Stock();
        spy.setFigi("F_SPY");
        spy.setSymbol("SPY");
        spy.setStockType("ETF");
        spy.setActiveStatus(ActiveStatus.ACTIVE);
        Stock qqq = new Stock();
        qqq.setFigi("F_QQQ");
        qqq.setSymbol("QQQ");
        qqq.setStockType("ETF");
        qqq.setActiveStatus(ActiveStatus.ACTIVE);
        StockStatus spyStatus = new StockStatus();
        spyStatus.setFigi("F_SPY");
        spyStatus.setSplitFromDate(LocalDate.of(2014, 1, 1));
        spyStatus.setSplitDate(DAY_NOV_12);
        StockStatus qqqStatus = new StockStatus();
        qqqStatus.setFigi("F_QQQ");
        qqqStatus.setSplitFromDate(LocalDate.of(2014, 1, 1));
        qqqStatus.setSplitDate(DAY_NOV_12);

        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(op1));
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of("SPY", "QQQ"))).thenReturn(List.of(spy, qqq));
        when(stocks.findAllById(anyCollection())).thenReturn(List.of(op1.stock()));
        when(statuses.findAllById(anyCollection())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(0);
            List<StockStatus> out = new ArrayList<>();
            if (ids.contains("F_SPY")) out.add(spyStatus);
            if (ids.contains("F_QQQ")) out.add(qqqStatus);
            if (ids.contains("F_OP1")) out.add(freshOp1);
            return out;
        });
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(op1.status()));
        when(latestRepo.findMaxValuationDateAtOrBefore(DAY_NOV_12)).thenReturn(Optional.of(DAY_NOV_12));
        FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of());

        com.ykm.stock.analytics.repository.jpa.InAppNotificationRepository notifRepo =
                mock(com.ykm.stock.analytics.repository.jpa.InAppNotificationRepository.class);
        org.quartz.Scheduler scheduler = mock(org.quartz.Scheduler.class);
        org.quartz.Trigger activeTrigger = mock(org.quartz.Trigger.class);
        org.quartz.JobKey chipKey = org.quartz.JobKey.jobKey(
                com.ykm.stock.analytics.constant.TaskCode.CHIP_EVENT_DAILY_SYNC,
                com.ykm.stock.analytics.enums.TaskGroup.SCHEDULE.name());
        when(scheduler.isStarted()).thenReturn(true);
        when(scheduler.checkExists(chipKey)).thenReturn(true);
        org.mockito.Mockito.doReturn(List.of(activeTrigger)).when(scheduler).getTriggersOfJob(chipKey);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "notificationRepository", notifRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(task, "scheduler", scheduler);

        // 1) 營運股 split_date 停在 D-1 -> 延後下游並發 EOD_PIPELINE_DEFERRED
        when(notifRepo.existsByDedupKey("EOD_DEFERRED:" + DAY_NOV_12)).thenReturn(false);
        task.execute(null);
        org.mockito.Mockito.verifyNoInteractions(sectorMoneyFlowService, dailyOpportunityRadarService, portfolioClosingService);
        org.mockito.ArgumentCaptor<com.ykm.stock.analytics.model.entity.InAppNotification> notifCaptor =
                org.mockito.ArgumentCaptor.forClass(com.ykm.stock.analytics.model.entity.InAppNotification.class);
        org.mockito.Mockito.verify(notifRepo).save(notifCaptor.capture());
        assertThat(notifCaptor.getValue().getAlertCode()).isEqualTo(StockValuationDailyTask.ALERT_EOD_PIPELINE_DEFERRED);
        assertThat(notifCaptor.getValue().getPayload().get("reason")).isEqualTo("SPLITS_NOT_READY");
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.never()).triggerJob(any(), any());

        // 2) 營運股最新 DB 狀態已補齊（即使入口快照 op1.status 仍是 D-1），但基準缺 QQQ -> 仍延後（BENCHMARK_SPLITS_NOT_READY）
        freshOp1.setSplitDate(DAY_NOV_12);
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of("SPY", "QQQ"))).thenReturn(List.of(spy));
        when(notifRepo.existsByDedupKey("EOD_DEFERRED:" + DAY_NOV_12)).thenReturn(true);
        task.execute(null);
        org.mockito.Mockito.verifyNoInteractions(sectorMoneyFlowService, dailyOpportunityRadarService);
        org.mockito.Mockito.verify(notifRepo, org.mockito.Mockito.times(1)).save(any());

        // 3) SPY 與 QQQ 皆存在且就緒，但收盤簡報暫時拋錯或回傳 empty -> 不得靜默將 EOD_PIPELINE_DEFERRED 標為已讀（籌碼同步照常觸發）
        when(stocks.findByActiveStatusAndSymbolIn(ActiveStatus.ACTIVE, List.of("SPY", "QQQ"))).thenReturn(List.of(spy, qqq));
        when(portfolioClosingService.publishDailyClosing(eq(DAY_NOV_12), any()))
                .thenThrow(new RuntimeException("BRIEFING_LOCK_TIMEOUT"));
        task.execute(null);
        org.mockito.Mockito.verify(notifRepo, org.mockito.Mockito.never())
                .markReadByAlertCodeAndTradeDateLessThanEqualIfUnread(any(), any(), any());

        org.mockito.Mockito.doReturn(Optional.empty()).when(portfolioClosingService).publishDailyClosing(eq(DAY_NOV_12), any());
        task.execute(null);
        org.mockito.Mockito.verify(notifRepo, org.mockito.Mockito.never())
                .markReadByAlertCodeAndTradeDateLessThanEqualIfUnread(any(), any(), any());

        // 4) 收盤簡報成功回傳後重跑 -> 自動將 EOD_PIPELINE_DEFERRED 標為已讀並帶 tradeDate 觸發籌碼同步
        org.mockito.Mockito.doReturn(Optional.of(mock(com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto.class)))
                .when(portfolioClosingService).publishDailyClosing(eq(DAY_NOV_12), any());
        task.execute(null);
        org.mockito.Mockito.verify(sectorMoneyFlowService, org.mockito.Mockito.times(3)).refreshDaily(eq(DAY_NOV_12), any());
        org.mockito.Mockito.verify(dailyOpportunityRadarService, org.mockito.Mockito.times(3)).refreshDaily(eq(DAY_NOV_12), any());
        org.mockito.Mockito.verify(notifRepo).markReadByAlertCodeAndTradeDateLessThanEqualIfUnread(
                eq(StockValuationDailyTask.ALERT_EOD_PIPELINE_DEFERRED), eq(DAY_NOV_12), eq(NOW));
        org.mockito.ArgumentCaptor<JobDataMap> triggerDataCaptor = org.mockito.ArgumentCaptor.forClass(JobDataMap.class);
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.times(3)).triggerJob(eq(chipKey), triggerDataCaptor.capture());
        assertThat(triggerDataCaptor.getValue().getString("tradeDate")).isEqualTo(DAY_NOV_12.toString());

        // 5) 籌碼排程停用（無 active trigger）、尚未到達 startTime 或 app.schedules.enabled=false 時，重跑安全略過觸發且不拋例外
        org.quartz.Trigger futureTrigger = mock(org.quartz.Trigger.class);
        when(futureTrigger.getStartTime()).thenReturn(java.util.Date.from(NOW.plusSeconds(3600)));
        org.mockito.Mockito.doReturn(List.of(futureTrigger)).when(scheduler).getTriggersOfJob(chipKey);
        task.execute(null);
        org.mockito.Mockito.doReturn(List.of()).when(scheduler).getTriggersOfJob(chipKey);
        task.execute(null);
        when(scheduler.isStarted()).thenReturn(false);
        task.execute(null);
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.times(3)).triggerJob(eq(chipKey), any(JobDataMap.class));
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
        storedStatuses.put(figi, item.status());
        when(stocks.findById(figi)).thenReturn(Optional.of(item.stock()));
        when(stocks.findAllById(anyCollection())).thenReturn(List.of(item.stock()));
        when(statuses.findById(figi)).thenReturn(Optional.of(item.status()));
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
