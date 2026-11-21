package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotCommon.F10Type;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetFinancialsStatements.FinancialFieldInfo;
import com.futu.openapi.pb.QotGetFinancialsStatements.FinancialItem;
import com.futu.openapi.pb.QotGetFinancialsStatements.S2C;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.CompanySubmissions;
import com.ykm.stock.analytics.converter.futu.FutuQuoteConverter;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveSplit;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository;
import com.ykm.stock.analytics.repository.jpa.FinancialReportRepository.FigiStatementPeriodEnd;
import com.ykm.stock.analytics.repository.jpa.MarketEventRepository;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockFinancialPeriodRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockSplitRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.MassiveService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Annual;
import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Q2;
import static com.futu.openapi.pb.QotCommon.F10Type.F10Type_Q3;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow;
import static com.futu.openapi.pb.QotCommon.FinancialStatementsType.FinancialStatementsType_Income;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockFundamentalDailySyncJobTests {
    // 2026-11-20 為香港週五 06:30（UTC 2026-11-19T22:30:00Z）
    private static final Instant FRIDAY_HK_MORNING = Instant.parse("2026-11-19T22:30:00Z");
    private static final LocalDate FRIDAY_HK = LocalDate.of(2026, 11, 20);
    // 2026-11-21 為香港週六 06:30（UTC 2026-11-20T22:30:00Z）
    private static final Instant SATURDAY_HK_MORNING = Instant.parse("2026-11-20T22:30:00Z");
    private static final LocalDate SATURDAY_HK = LocalDate.of(2026, 11, 21);
    // 2026-11-22 為香港週日 06:30（UTC 2026-11-21T22:30:00Z）
    private static final Instant SUNDAY_HK_MORNING = Instant.parse("2026-11-21T22:30:00Z");
    private static final LocalDate SUNDAY_HK = LocalDate.of(2026, 11, 22);
    private static final LocalDate LAST_CLOSED_TRADING_DAY = LocalDate.of(2026, 11, 19);

    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockSplitRepository splitRepository = mock(StockSplitRepository.class);
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockFinancialPeriodRepository financialPeriods = mock(StockFinancialPeriodRepository.class);
    private final MarketEventRepository marketEvents = mock(MarketEventRepository.class);
    private final MassiveService massiveService = mock(MassiveService.class);
    private final FutuService futu = mock(FutuService.class);
    private final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
    private final SecCompanyFactsArchive companyFactsArchive = mock(SecCompanyFactsArchive.class);
    private final SecCompanyFactsArchive.Snapshot companyFactsSnapshot = mock(SecCompanyFactsArchive.Snapshot.class);
    private final SecSubmissionsArchive submissionsArchive = mock(SecSubmissionsArchive.class);
    private final SecSubmissionsArchive.Snapshot submissionsSnapshot = mock(SecSubmissionsArchive.Snapshot.class);
    private final MarketStatusService marketStatusService = mock(MarketStatusService.class);
    private final DateTimeService dateTimeService = mock(DateTimeService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final StockValuationService stockValuationService = mock(StockValuationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final StockFundamentalDailySyncJob job = new StockFundamentalDailySyncJob(
            stocks, statuses, splitRepository, reports, secFacts, financialPeriods, marketEvents,
            massiveService, futu, companyFactsArchive, submissionsArchive,
            Mappers.getMapper(FutuQuoteConverter.class), new SecFinancialFactConverter(marketStatusService),
            new SecIssuerHistory(""), marketStatusService, dateTimeService,
            new TransactionTemplate(transactionManager), entityManager, stockValuationService);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(dateTimeService.now()).thenReturn(FRIDAY_HK_MORNING);
        when(dateTimeService.nowLocalDateTime()).thenReturn(LocalDateTime.of(2026, 11, 19, 22, 30));
        when(marketStatusService.getLastClosedTradingDay()).thenReturn(LAST_CLOSED_TRADING_DAY);
        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of());
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of());
        when(stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of());
        when(marketEvents.findEarningsFigisBetween(any(), any())).thenReturn(Set.of());
        when(secFacts.findRecentPeriodicFilingCiks(anyCollection(), any())).thenReturn(Set.of());
        when(massiveService.getSplitsFrom(any())).thenReturn(List.of());
        when(reports.findLatestStatementPeriodEnds(anyCollection())).thenReturn(List.of());
        when(financialPeriods.findExistingFigis(anyCollection())).thenReturn(Set.of());
        when(submissionsArchive.prepare()).thenReturn(submissionsSnapshot);
        when(companyFactsArchive.prepare()).thenReturn(companyFactsSnapshot);
        when(submissionsSnapshot.findCompany(anyString())).thenReturn(Optional.empty());
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of());
    }

    /** Scenario：同一公司組完成拆股＋富途財報＋SEC 時，同交易重建財季分母並推進 fundamentalSyncDate；同日重跑直接跳過已做完股票。 */
    @Test
    void commitsGroupAtomicallyRebuildsPeriodsInSameTxAndSkipsOnSameDayRerun() throws Exception {
        Stock aapl = stock("F_AAPL", "AAPL", "Common Stock", "0000320193");
        StockStatus aaplStatus = status("F_AAPL", LocalDate.of(2026, 11, 18), null, null, null);
        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(new ActiveStockWithStatus(aapl, aaplStatus)));
        when(stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(new ActiveStockWithStatus(aapl, aaplStatus)));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(aaplStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_AAPL"))).thenReturn(List.of(aaplStatus));
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("AAPL"));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), anyInt())).thenReturn(page("-1"));
        when(session.financialStatements("AAPL", FinancialStatementsType_Income, null, 50))
                .thenReturn(page("-1", report("2026-09-30", F10Type_Annual)));
        when(companyFactsSnapshot.read("0000320193")).thenReturn(objectMapper.readTree("{\"facts\":{}}"));

        job.execute(null);

        assertThat(aaplStatus.getFundamentalSyncDate()).isEqualTo(FRIDAY_HK);
        assertThat(aaplStatus.getFinancialReportDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(aaplStatus.getSplitDate()).isEqualTo(LAST_CLOSED_TRADING_DAY);
        verify(stockValuationService).rebuildFinancialPeriodsInCurrentTx(List.of("F_AAPL"));
        verify(stockValuationService).refreshLatestValuations(Set.of("F_AAPL"), null);

        // 同日重跑：因 fundamentalSyncDate == FRIDAY_HK 直接跳過，不再發任何富途或 Massive 請求
        job.execute(null);
        verify(session, times(1)).financialStatements("AAPL", FinancialStatementsType_Income, null, 50);
    }

    /** Scenario：中途遇到 OpenD 錯誤或寫入失敗時，該組完全回滾且不推進 fundamentalSyncDate，前面已完成組保留斷點。 */
    @Test
    void failureRollsBackCurrentGroupWithoutAdvancingWatermarkAndPreservesCompletedGroups() {
        Stock etf = stock("F_SPY", "SPY", "ETF", null);
        Stock msft = stock("F_MSFT", "MSFT", "Common Stock", null);
        StockStatus spyStatus = status("F_SPY", LocalDate.of(2026, 11, 18), null, null, null);
        StockStatus msftStatus = status("F_MSFT", LocalDate.of(2026, 11, 18), null, null, null);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(etf, msft));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(new ActiveStockWithStatus(msft, msftStatus)));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(spyStatus, msftStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_SPY"))).thenReturn(List.of(spyStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_MSFT"))).thenReturn(List.of(msftStatus));
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("MSFT"));
        when(session.financialStatements("MSFT", FinancialStatementsType_Income, null, 50))
                .thenThrow(new IllegalStateException("FUTU_DISCONNECTED:-1"));

        assertThatThrownBy(() -> job.execute(null)).hasMessage("FUTU_DISCONNECTED:-1");

        // 快速通道已完成的 ETF 保留斷點，失敗的 MSFT 水位不推進
        assertThat(spyStatus.getFundamentalSyncDate()).isEqualTo(FRIDAY_HK);
        assertThat(msftStatus.getFundamentalSyncDate()).isNull();
    }

    /** Scenario：週六全市場巡檢中途停止，週日重跑時對尚未越過週六水位的股票自動補跑週六巡檢，完成後推進至週日日期。 */
    @Test
    void sundayRunResumesIncompleteSaturdayPatrolForStocksBehindSaturdayWatermark() {
        when(dateTimeService.now()).thenReturn(SUNDAY_HK_MORNING);
        Stock doneSat = stock("F_DONE", "DONE", "ETF", null);
        Stock missedSat = stock("F_MISS", "MISS", "ETF", null);
        StockStatus doneStatus = status("F_DONE", LocalDate.of(2026, 11, 19), null, null, SATURDAY_HK);
        StockStatus missedStatus = status("F_MISS", LocalDate.of(2026, 11, 19), null, null, FRIDAY_HK);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(doneSat, missedSat));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(doneStatus, missedStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_DONE"))).thenReturn(List.of(doneStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_MISS"))).thenReturn(List.of(missedStatus));
        when(session.rehab("MISS")).thenReturn(List.of());
        when(splitRepository.findByFigiAndExecutionDateBetween(eq("F_MISS"), any(), any())).thenReturn(List.of());

        job.execute(null);

        // F_MISS 尚未越過 SATURDAY_HK，自動補跑富途復權對帳；F_DONE 已在週六完成，僅走平日 Massive 快速通道
        verify(session, times(1)).rehab("MISS");
        verify(session, never()).rehab("DONE");
        assertThat(missedStatus.getFundamentalSyncDate()).isEqualTo(SUNDAY_HK);
        assertThat(doneStatus.getFundamentalSyncDate()).isEqualTo(SUNDAY_HK);
    }

    /** Scenario：三表各自查 DB 最新 period_end，損益表已到 Q3 時仍能拉回晚到的 Q2 現金流量表；缺少其中一張表回空照常完成。 */
    @Test
    void usesPerStatementWatermarksAndCompletesWhenOneStatementIsEmptyOrOtcUnsupported() {
        Stock s1 = stock("F_1", "ONE", "Common Stock", null);
        Stock otc = stock("F_OTC", "SCKT", "Common Stock", null);
        StockStatus st1 = status("F_1", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 9, 30), null, null);
        StockStatus stOtc = status("F_OTC", LocalDate.of(2026, 11, 18), null, null, null);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(s1, otc));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(s1, st1), new ActiveStockWithStatus(otc, stOtc)));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(st1, stOtc));
        when(statuses.findAllByIdForUpdate(List.of("F_1"))).thenReturn(List.of(st1));
        when(statuses.findAllByIdForUpdate(List.of("F_OTC"))).thenReturn(List.of(stOtc));
        when(marketEvents.findEarningsFigisBetween(any(), any())).thenReturn(Set.of("F_1"));
        when(reports.findLatestStatementPeriodEnds(anyCollection())).thenReturn(List.of(
                new FigiStatementPeriodEnd("F_1", FinancialStatementsType_Income.getNumber(), LocalDate.of(2026, 9, 30)),
                new FigiStatementPeriodEnd("F_1", FinancialStatementsType_CashFlow.getNumber(), LocalDate.of(2026, 3, 31))));
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("ONE", "SCKT"));
        when(session.financialStatements("ONE", FinancialStatementsType_Income, null, 10))
                .thenReturn(page("-1", report("2026-09-30", F10Type_Q3)));
        when(session.financialStatements("ONE", FinancialStatementsType_BalanceSheet, null, 50))
                .thenReturn(page("-1")); // 永遠缺資產負債表回空
        when(session.financialStatements("ONE", FinancialStatementsType_CashFlow, null, 10))
                .thenReturn(page("-1", report("2026-06-30", F10Type_Q2)));
        when(session.financialStatements("SCKT", FinancialStatementsType_Income, null, 50)).thenReturn(null);

        job.execute(null);

        // CashFlow 依自身 2026-03-31 水位成功寫入晚到的 2026-06-30 Q2，且缺 BalanceSheet 回空照常完成
        verify(entityManager, times(2)).persist(any(FinancialReport.class));
        assertThat(st1.getFundamentalSyncDate()).isEqualTo(FRIDAY_HK);
        // SCKT 遇 OTC null 跳過剩餘兩表且正常推進斷點
        verify(session, never()).financialStatements("SCKT", FinancialStatementsType_BalanceSheet, null, 50);
        assertThat(stOtc.getFundamentalSyncDate()).isEqualTo(FRIDAY_HK);
    }

    /** Scenario：週六富途拆股回空但 DB 有舊事件（SPLIT_STORED_EVENT_NOT_RETURNED）時，保留差異且不推進 fundamentalSyncDate；平日不清除該差異。 */
    @Test
    void splitDiscrepancyPreventsAdvancingFundamentalSyncDateAndWeekdayPreservesMessage() {
        when(dateTimeService.now()).thenReturn(SATURDAY_HK_MORNING);
        Stock etf = stock("F_SPLIT", "SPLT", "ETF", null);
        StockStatus st = status("F_SPLIT", LocalDate.of(2026, 11, 18), null, null, null);
        StockSplit stored = new StockSplit();
        stored.setSourceEventId("FUTU:1");
        stored.setFigi("F_SPLIT");
        stored.setExecutionDate(LocalDate.of(2025, 6, 1));
        stored.setSplitFrom(BigDecimal.ONE);
        stored.setSplitTo(BigDecimal.TWO);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(etf));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(st));
        when(statuses.findAllByIdForUpdate(List.of("F_SPLIT"))).thenReturn(List.of(st));
        when(session.rehab("SPLT")).thenReturn(List.of());
        when(splitRepository.findByFigiAndExecutionDateBetween(eq("F_SPLIT"), any(), any())).thenReturn(List.of(stored));

        job.execute(null);

        assertThat(st.getSplitMessage()).contains("SPLIT_STORED_EVENT_NOT_RETURNED");
        assertThat(st.getFundamentalSyncDate()).isNull();

        // 平日（週五）重跑時不應把既有的 SPLIT_STORED_EVENT_NOT_RETURNED 訊息清掉
        when(dateTimeService.now()).thenReturn(FRIDAY_HK_MORNING);
        job.execute(null);
        assertThat(st.getSplitMessage()).contains("SPLIT_STORED_EVENT_NOT_RETURNED");
        assertThat(st.getSplitDate()).isEqualTo(LocalDate.of(2025, 5, 31));
    }

    /** Scenario：手動指定 figis 強制補跑時繞過同日跳過判斷，且不改動全市場 fundamentalSyncDate 斷點。 */
    @Test
    void manualFigisBypassesSameDaySkipWithoutOverwritingDailyCheckpoint() {
        Stock aapl = stock("F_AAPL", "AAPL", "Common Stock", null);
        StockStatus st = status("F_AAPL", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 9, 30), null, FRIDAY_HK);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        JobDataMap map = new JobDataMap();
        map.put("figis", "F_AAPL");
        when(ctx.getMergedJobDataMap()).thenReturn(map);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(new ActiveStockWithStatus(aapl, st)));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(st));
        when(statuses.findAllByIdForUpdate(List.of("F_AAPL"))).thenReturn(List.of(st));
        when(session.supportedUsFinancialReportStocks()).thenReturn(Set.of("AAPL"));
        when(session.financialStatements(eq("AAPL"), any(), isNull(), anyInt())).thenReturn(page("-1"));

        job.execute(ctx);

        verify(session, times(3)).financialStatements(eq("AAPL"), any(), isNull(), anyInt());
        assertThat(st.getFundamentalSyncDate()).isEqualTo(FRIDAY_HK);
    }

    /** Scenario：週六富途拆股遇個別未知代碼（Unknown stock. ATTR）時記錄該股 splitMessage、不推水位並繼續處理後續股票；遇連線中斷仍立即中止。 */
    @Test
    void recordsPerStockSplitFailureWithoutAbortingJobAndStillAbortsOnTransportFailure() {
        when(dateTimeService.now()).thenReturn(SATURDAY_HK_MORNING);
        Stock unknown = stock("F_ATTR", "ATTR", "ETP", null);
        Stock ok = stock("F_SPY", "SPY", "ETF", null);
        StockStatus unknownStatus = status("F_ATTR", null, null, null, null);
        StockStatus okStatus = status("F_SPY", LocalDate.of(2026, 11, 18), null, null, null);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(unknown, ok));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(unknownStatus, okStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_ATTR"))).thenReturn(List.of(unknownStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_SPY"))).thenReturn(List.of(okStatus));
        when(session.rehab("ATTR")).thenThrow(new IllegalStateException("FUTU_REQUEST_REHAB_FAILED:-1:0:Unknown stock. ATTR"));
        when(session.rehab("SPY")).thenReturn(List.of());
        when(splitRepository.findByFigiAndExecutionDateBetween(eq("F_SPY"), any(), any())).thenReturn(List.of());

        job.execute(null);

        assertThat(unknownStatus.getSplitMessage()).contains("FUTU_REQUEST_REHAB_FAILED:-1:0:Unknown stock. ATTR");
        assertThat(unknownStatus.getSplitDate()).isNull();
        assertThat(unknownStatus.getFundamentalSyncDate()).isNull();
        assertThat(okStatus.getSplitDate()).isEqualTo(LAST_CLOSED_TRADING_DAY);
        assertThat(okStatus.getFundamentalSyncDate()).isEqualTo(SATURDAY_HK);

        // 連線中斷屬於會話級故障，仍必須中止整輪排程
        Stock disconnected = stock("F_DISC", "DISC", "ETF", null);
        StockStatus disconnectedStatus = status("F_DISC", null, null, null, null);
        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(disconnected));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(disconnectedStatus));
        when(session.rehab("DISC")).thenThrow(new IllegalStateException("FUTU_DISCONNECTED:-1"));

        assertThatThrownBy(() -> job.execute(null)).hasMessage("FUTU_DISCONNECTED:-1");
        assertThat(disconnectedStatus.getFundamentalSyncDate()).isNull();
    }

    /**
     * Scenario：週六凌晨已同步過基本面（fundamentalSyncDate == SATURDAY_HK，但 splitDate 停在週四 11-19），
     * 到週六 06:30（美股週五 11-20 已收市）重跑時：
     * - 必須走 splitOnlyCatchup 增量補推，預載 Massive 拆股且絕不開啟 Futu rehab 全量巡檢；
     * - 無拆股股走快速通道推進 splitDate 至 11-20；
     * - 真有週五新拆股的個股仍正常落庫、重建財季分母並刷新最新估值；
     * - 帶有 SPLIT_STORED_EVENT_NOT_RETURNED 差異標記者不參與補推且保留差異訊息。
     */
    @Test
    void saturdaySplitOnlyCatchupPreloadsMassiveWithoutFutuRehabAndRebuildsSplitChangedStock() {
        LocalDate fridayClosed = LocalDate.of(2026, 11, 20);
        when(dateTimeService.now()).thenReturn(SATURDAY_HK_MORNING);
        when(marketStatusService.getLastClosedTradingDay()).thenReturn(fridayClosed);

        Stock quietStock = stock("F_NVDA", "NVDA", "Common Stock", "0001045810");
        Stock warningStock = stock("F_WARN", "WARN", "Common Stock", "0001234567");
        Stock splitStock = stock("F_SPLT", "SPLT", "Common Stock", "0000999999");
        Stock discStock = stock("F_DISC", "DISC", "Common Stock", null);

        StockStatus quietStatus = status("F_NVDA", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), SATURDAY_HK);
        StockStatus warningStatus = status("F_WARN", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), SATURDAY_HK);
        warningStatus.setSplitMessage("WARNING:HISTORICAL_PROVIDER_NOTE");
        StockStatus splitStatus = status("F_SPLT", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), SATURDAY_HK);
        StockStatus discStatus = status("F_DISC", LocalDate.of(2025, 5, 31), null, null, SATURDAY_HK);
        discStatus.setSplitMessage("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:2025-06-01:dates=1");

        MassiveSplit fridaySplit = new MassiveSplit();
        fridaySplit.setId("MS-1");
        fridaySplit.setTicker("SPLT");
        fridaySplit.setExecutionDate(fridayClosed);
        fridaySplit.setSplitFrom(BigDecimal.ONE);
        fridaySplit.setSplitTo(new BigDecimal("2"));

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(quietStock, warningStock, splitStock, discStock));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(quietStock, quietStatus),
                new ActiveStockWithStatus(warningStock, warningStatus),
                new ActiveStockWithStatus(splitStock, splitStatus)));
        when(stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(quietStock, quietStatus),
                new ActiveStockWithStatus(warningStock, warningStatus),
                new ActiveStockWithStatus(splitStock, splitStatus)));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(quietStatus, warningStatus, splitStatus, discStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_NVDA", "F_WARN"))).thenReturn(List.of(quietStatus, warningStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_SPLT"))).thenReturn(List.of(splitStatus));
        when(massiveService.getSplitsFrom(LocalDate.of(2026, 11, 12))).thenReturn(List.of(fridaySplit));
        when(splitRepository.findByFigiAndExecutionDateBetween(eq("F_SPLT"), any(), any())).thenReturn(List.of());

        job.execute(null);

        // 週六 splitOnlyCatchup 強制非 fullPatrol：預載 Massive、絕不開 Futu、絕不重跑 SEC/財報
        verify(massiveService).getSplitsFrom(LocalDate.of(2026, 11, 12));
        verifyNoInteractions(futu, companyFactsArchive, submissionsArchive);

        // 無拆股股（含 WARNING: 訊息普通股）走快速批次通道推進 splitDate 至 11-20，且保留既有 WARNING: 不被清空
        assertThat(quietStatus.getSplitDate()).isEqualTo(fridayClosed);
        assertThat(warningStatus.getSplitDate()).isEqualTo(fridayClosed);
        assertThat(warningStatus.getSplitMessage()).isEqualTo("WARNING:HISTORICAL_PROVIDER_NOTE");
        // 真有拆股的 SPLT 落庫新拆股、重建財季分母並刷新最新估值
        assertThat(splitStatus.getSplitDate()).isEqualTo(fridayClosed);
        verify(splitRepository).saveAll(anyCollection());
        verify(stockValuationService).rebuildFinancialPeriodsInCurrentTx(List.of("F_SPLT"));
        verify(stockValuationService).refreshLatestValuations(Set.of("F_SPLT"), null);
        // 既有拆股對帳差異股完全不受影響
        assertThat(discStatus.getSplitDate()).isEqualTo(LocalDate.of(2025, 5, 31));
        assertThat(discStatus.getSplitMessage()).contains("SPLIT_STORED_EVENT_NOT_RETURNED");

        // 單股 Massive 異常拆股（splitTo=0）於 WARNING: 股不可中斷全批，應落入逐股隔離記錯而他股照常推進
        Stock badSplitStock = stock("F_BADS", "BADS", "Common Stock", "0005555555");
        Stock okPeerStock = stock("F_OK2", "OK2", "Common Stock", "0006666666");
        StockStatus badStatus = status("F_BADS", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), SATURDAY_HK);
        badStatus.setSplitMessage("WARNING:HISTORICAL_PROVIDER_NOTE");
        StockStatus okPeerStatus = status("F_OK2", LocalDate.of(2026, 11, 19), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30), SATURDAY_HK);
        MassiveSplit invalidSplit = new MassiveSplit();
        invalidSplit.setId("MS-BAD");
        invalidSplit.setTicker("BADS");
        invalidSplit.setExecutionDate(fridayClosed);
        invalidSplit.setSplitFrom(BigDecimal.ONE);
        invalidSplit.setSplitTo(BigDecimal.ZERO);

        when(stocks.findSplitSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(badSplitStock, okPeerStock));
        when(stocks.findFinancialReportSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(badSplitStock, badStatus),
                new ActiveStockWithStatus(okPeerStock, okPeerStatus)));
        when(stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(
                new ActiveStockWithStatus(badSplitStock, badStatus),
                new ActiveStockWithStatus(okPeerStock, okPeerStatus)));
        when(statuses.findAllById(anyCollection())).thenReturn(List.of(badStatus, okPeerStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_OK2"))).thenReturn(List.of(okPeerStatus));
        when(statuses.findAllByIdForUpdate(List.of("F_BADS"))).thenReturn(List.of(badStatus));
        when(massiveService.getSplitsFrom(LocalDate.of(2026, 11, 12))).thenReturn(List.of(invalidSplit));

        job.execute(null);
        assertThat(okPeerStatus.getSplitDate()).isEqualTo(fridayClosed);
        assertThat(badStatus.getSplitDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(badStatus.getSplitMessage()).contains("SPLIT_SOURCE_VALUE_INVALID");
    }

    private Stock stock(String figi, String symbol, String type, String cik) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setSymbol(symbol);
        stock.setStockType(type);
        stock.setCik(cik);
        stock.setActiveStatus(ActiveStatus.ACTIVE);
        stock.setTracked(true);
        return stock;
    }

    private StockStatus status(String figi, LocalDate splitDate, LocalDate frDate, LocalDate secDate, LocalDate syncDate) {
        StockStatus st = new StockStatus();
        st.setFigi(figi);
        st.setSplitDate(splitDate);
        st.setFinancialReportDate(frDate);
        st.setSecFinancialFactsDate(secDate);
        st.setFundamentalSyncDate(syncDate);
        return st;
    }

    private S2C page(String cursor, QotGetFinancialsStatements.FinancialReport... rows) {
        return S2C.newBuilder().setNextKey(cursor).addAllReportList(List.of(rows))
                .addStructureList(FinancialFieldInfo.newBuilder().setFieldId(100).setDisplayName("Total Revenue")).build();
    }

    private QotGetFinancialsStatements.FinancialReport report(String date, F10Type type) {
        return QotGetFinancialsStatements.FinancialReport.newBuilder().setDateTimeStr(date).setFinancialType(type).setCurrencyCode("USD")
                .addItemList(FinancialItem.newBuilder().setFieldId(100).setData(1000))
                .addItemList(FinancialItem.newBuilder().setFieldId(101)).build();
    }
}
