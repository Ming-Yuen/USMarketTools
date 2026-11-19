package com.ykm.stock.analytics.service.portfolio;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.orm.config.AuditingConfig;
import com.ykm.orm.config.JpaConfig;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketRegime;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MarketSnapshotDto;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricResult;
import com.ykm.stock.analytics.model.dto.stockInsight.analysis.MetricStatus;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.DailyClosingBriefingDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.InAppNotificationDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioHealthSummaryDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigUpdateRequest;
import com.ykm.stock.analytics.model.entity.DailyClosingBriefing;
import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.repository.jpa.DailyClosingBriefingRepository;
import com.ykm.stock.analytics.repository.jpa.DailyOpportunityRadarRunRepository;
import com.ykm.stock.analytics.repository.jpa.InAppNotificationRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.repository.jpa.SectorMoneyFlowDailyRepository;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.service.analysis.MarketContextService;
import com.ykm.stock.analytics.service.analysis.MarketContextService.PreloadedMarketData;
import com.ykm.stock.analytics.service.analysis.PriceStructureService;
import com.ykm.stock.analytics.service.analysis.QuoteNormalizationService;
import com.ykm.stock.analytics.service.analysis.SupportResistanceService;
import com.ykm.stock.analytics.service.analysis.TradePlanService;
import com.ykm.stock.analytics.service.analysis.calculator.PositionSizingCalculator;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 隔離 PostgreSQL 16 真實資料庫整合測試（@Tag("heavy")）：
 * 1. 驗證 PortfolioRiskConfigService.updateConfig 雙連線併發時透過 pg_advisory_xact_lock 零重複鍵且保持快照一致；
 * 2. 驗證 PortfolioClosingService.publishDailyClosing 雙連線併發發布主鍵競態回讀勝方、已讀 read_at 保留及 REQUIRES_NEW 交易隔離；
 * 3. 於 125 萬筆真實結構日線（500 檔 × 2,500 日）量測 StockQuoteRepository.findLatestQuotesUpTo 實庫 EXPLAIN (ANALYZE, BUFFERS)。
 * 全程零外部 API 呼叫、不啟動 Quartz／排程，亦絕不觸及 DEV／UAT／PROD 資料庫。
 */
@Tag("heavy")
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = {"com.ykm.stock.analytics.model.entity", "com.ykm.orm.entity"})
@Import({
        JpaConfig.class,
        AuditingConfig.class,
        PortfolioRiskConfigService.class,
        PortfolioSplitAligner.class,
        PositionSizingCalculator.class,
        PortfolioHealthCheckService.class,
        PortfolioClosingService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=com.ykm.stock.analytics.config.StockPostgreSQLDialect",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.jpa.properties.hibernate.type.preferred_instant_jdbc_type=TIMESTAMP",
        "spring.datasource.hikari.maximum-pool-size=8",
        "spring.quartz.auto-startup=false",
        "app.quartz.schema-bootstrap.enabled=false"
})
class PortfolioPostgresConcurrencyAndPerformanceTests {
    private static final Logger log = LoggerFactory.getLogger(PortfolioPostgresConcurrencyAndPerformanceTests.class);
    private static final LocalDate PRIOR_DAY = LocalDate.of(2026, 11, 17);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);
    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T22:00:00Z");
    private static final LocalDateTime FIXED_LOCAL_NOW = LocalDateTime.of(2026, 11, 18, 22, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PortfolioRiskConfigService riskConfigService;
    @Autowired
    private PortfolioClosingService closingService;
    @Autowired
    private PortfolioSysConfigRepository sysConfigRepository;
    @Autowired
    private DailyClosingBriefingRepository briefingRepository;
    @Autowired
    private InAppNotificationRepository notificationRepository;
    @Autowired
    private DailyOpportunityRadarRunRepository radarRunRepository;
    @Autowired
    private SectorMoneyFlowDailyRepository sectorMoneyFlowRepository;
    @Autowired
    private StockQuoteRepository stockQuoteRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private QuoteNormalizationService quoteNormalizationService;
    @MockitoBean
    private PriceStructureService priceStructureService;
    @MockitoBean
    private MarketContextService marketContextService;
    @MockitoBean
    private SupportResistanceService supportResistanceService;
    @MockitoBean
    private TradePlanService tradePlanService;
    @MockitoBean
    private MarketStatusService marketStatusService;
    @MockitoBean
    private DateTimeService dateTimeService;

    private final AtomicReference<Instant> clockNow = new AtomicReference<>(FIXED_NOW);

    @BeforeEach
    void setUp() {
        clockNow.set(FIXED_NOW);
        when(dateTimeService.now()).thenAnswer(inv -> clockNow.get());
        when(dateTimeService.nowLocalDateTime()).thenReturn(FIXED_LOCAL_NOW);
        when(marketStatusService.getLastClosedTradingDay(any())).thenReturn(DAY);
        when(marketStatusService.getSessionOpenAt(any())).thenReturn(FIXED_NOW.minusSeconds(27000));
        when(marketStatusService.getSessionCloseAt(any())).thenReturn(FIXED_NOW.minusSeconds(3600));
    }

    /** Scenario 1：空 sys_config 下雙連線同時呼叫 updateConfig → pg_advisory_xact_lock 序列化成功、嚴格僅 4 列且歷史重複列可被清除。 */
    @Test
    void concurrentRiskConfigUpdatesUseAdvisoryLockWithoutDuplicateSysConfigRows() throws Exception {
        sysConfigRepository.deleteAll();

        PortfolioRiskConfigUpdateRequest reqA = new PortfolioRiskConfigUpdateRequest(
                new BigDecimal("120000"), new BigDecimal("0.0120"), new BigDecimal("0.1400"), new BigDecimal("0.2800"));
        PortfolioRiskConfigUpdateRequest reqB = new PortfolioRiskConfigUpdateRequest(
                new BigDecimal("250000"), new BigDecimal("0.0150"), new BigDecimal("0.1200"), new BigDecimal("0.2500"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<PortfolioRiskConfigDto> taskA = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return riskConfigService.updateConfig(reqA);
            };
            Callable<PortfolioRiskConfigDto> taskB = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return riskConfigService.updateConfig(reqB);
            };

            Future<PortfolioRiskConfigDto> futureA = pool.submit(taskA);
            Future<PortfolioRiskConfigDto> futureB = pool.submit(taskB);
            PortfolioRiskConfigDto resA = futureA.get(15, TimeUnit.SECONDS);
            PortfolioRiskConfigDto resB = futureB.get(15, TimeUnit.SECONDS);
            assertThat(resA.totalCapital()).isEqualByComparingTo("120000.00");
            assertThat(resB.totalCapital()).isEqualByComparingTo("250000.00");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        List<String> keys = List.of(
                PortfolioRiskConfigService.KEY_TOTAL_CAPITAL,
                PortfolioRiskConfigService.KEY_RISK_PER_TRADE_PCT,
                PortfolioRiskConfigService.KEY_MAX_SINGLE_POSITION_PCT,
                PortfolioRiskConfigService.KEY_MAX_SECTOR_EXPOSURE_PCT
        );
        List<SysConfig> storedRows = sysConfigRepository.findByConfigKeyInOrderByIdAsc(keys);
        assertThat(storedRows).hasSize(4);

        PortfolioRiskConfigDto finalCfg = riskConfigService.getConfig();
        boolean matchesA = finalCfg.totalCapital().compareTo(new BigDecimal("120000.00")) == 0
                && finalCfg.riskPerTradePct().compareTo(new BigDecimal("0.0120")) == 0
                && finalCfg.maxSinglePositionPct().compareTo(new BigDecimal("0.1400")) == 0
                && finalCfg.maxSectorExposurePct().compareTo(new BigDecimal("0.2800")) == 0;
        boolean matchesB = finalCfg.totalCapital().compareTo(new BigDecimal("250000.00")) == 0
                && finalCfg.riskPerTradePct().compareTo(new BigDecimal("0.0150")) == 0
                && finalCfg.maxSinglePositionPct().compareTo(new BigDecimal("0.1200")) == 0
                && finalCfg.maxSectorExposurePct().compareTo(new BigDecimal("0.2500")) == 0;
        assertThat(matchesA || matchesB).isTrue();

        // 人為插入歷史重複鍵後再次更新，確認多餘次列會被刪除而非僅停用
        SysConfig duplicateRow = new SysConfig();
        duplicateRow.setConfigKey(PortfolioRiskConfigService.KEY_TOTAL_CAPITAL);
        duplicateRow.setConfigValue("999999.00");
        duplicateRow.setDescription("legacy duplicate");
        duplicateRow.setEnabled(true);
        sysConfigRepository.saveAndFlush(duplicateRow);
        assertThat(sysConfigRepository.findByConfigKeyInOrderByIdAsc(keys)).hasSize(5);

        riskConfigService.updateConfig(reqB);
        assertThat(sysConfigRepository.findByConfigKeyInOrderByIdAsc(keys)).hasSize(4);
        assertThat(riskConfigService.getConfig().totalCapital()).isEqualByComparingTo("250000.00");
    }

    /** Scenario 2：雙連線併發發布收盤簡報 → 主鍵競態安全回讀勝方、僅一套簡報與通知、重跑保留首次 read_at，且失敗不影響上游交易。 */
    @Test
    void concurrentClosingBriefingPublishPreservesSingleRowReadAtAndIsolatesUpstreamTransaction() throws Exception {
        notificationRepository.deleteAll();
        briefingRepository.deleteAll();
        radarRunRepository.deleteAll();
        sectorMoneyFlowRepository.deleteAll();

        DailyClosingBriefing prior = new DailyClosingBriefing();
        prior.setTradeDate(PRIOR_DAY);
        prior.setMarketRegime(MarketRegime.RISK_ON.name());
        prior.setRegimeChanged(false);
        prior.setTopSectors(List.of());
        prior.setBottomSectors(List.of());
        prior.setPortfolioHealth(new PortfolioHealthSummaryDto(
                PRIOR_DAY, PortfolioHealthCheckService.SOURCE_MODE_CLOSING_SNAPSHOT, FIXED_NOW.minusSeconds(86400),
                MarketRegime.RISK_ON.name(), 0, 0, 0, 0, 0, 0, true, true, 0,
                List.of(), List.of(), List.of(), List.of(), List.of()));
        prior.setWatchlistOpportunities(List.of());
        prior.setRadarTop5(List.of());
        prior.setCriticalCount(0);
        prior.setWarningCount(0);
        prior.setInfoCount(0);
        prior.setObservationCutoff(FIXED_NOW.minusSeconds(86400));
        prior.setGeneratedAt(FIXED_NOW.minusSeconds(86400));
        briefingRepository.saveAndFlush(prior);

        DailyOpportunityRadarRun zeroRun = new DailyOpportunityRadarRun(
                DAY, MarketRegime.BEAR.name(), true, 0, 0, 0, FIXED_NOW, FIXED_NOW);
        radarRunRepository.saveAndFlush(zeroRun);

        SectorMoneyFlowDaily techFlow = sectorFlow(DAY, "TECH", "Technology", "0.220000", "5000000000.00", SectorMoneyFlowDaily.FLOW_INFLOW);
        SectorMoneyFlowDaily energyFlow = sectorFlow(DAY, "ENRG", "Energy", "-0.180000", "1000000000.00", SectorMoneyFlowDaily.FLOW_OUTFLOW);
        sectorMoneyFlowRepository.saveAllAndFlush(List.of(techFlow, energyFlow));

        // 讓兩條執行緒同時通過前置 findById 檢查後，在 preloadMarketData 處以 CyclicBarrier 同步放行衝撞真實 PostgreSQL PK 寫入
        CyclicBarrier raceBarrier = new CyclicBarrier(2);
        MetricResult<BigDecimal> naMetric = MetricResult.unavailable(MetricStatus.INSUFFICIENT_DATA, List.of(), List.of());
        MarketSnapshotDto bearSnap = new MarketSnapshotDto(MarketRegime.BEAR, naMetric, naMetric, naMetric, DAY, FIXED_NOW, List.of(), List.of());
        PreloadedMarketData preloaded = new PreloadedMarketData(
                DAY, FIXED_NOW, null, null, Map.of(), List.of(),
                MetricResult.available(bearSnap), MetricResult.available(new BigDecimal("29.5000")));
        when(marketContextService.preloadMarketData(DAY, FIXED_NOW)).thenAnswer(inv -> {
            raceBarrier.await(10, TimeUnit.SECONDS);
            return preloaded;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<DailyClosingBriefingDto>> f1 = pool.submit(() -> closingService.publishDailyClosing(DAY, FIXED_NOW));
            Future<Optional<DailyClosingBriefingDto>> f2 = pool.submit(() -> closingService.publishDailyClosing(DAY, FIXED_NOW));
            Optional<DailyClosingBriefingDto> r1 = f1.get(15, TimeUnit.SECONDS);
            Optional<DailyClosingBriefingDto> r2 = f2.get(15, TimeUnit.SECONDS);
            assertThat(r1).isPresent();
            assertThat(r2).isPresent();
            assertThat(r1.get().tradeDate()).isEqualTo(DAY);
            assertThat(r2.get().tradeDate()).isEqualTo(DAY);
            assertThat(r1.get().regimeChanged()).isTrue();
            assertThat(r2.get().regimeChanged()).isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // 驗證僅產生 1 筆當日簡報與 2 筆通知（MARKET_REGIME_SHIFT + DAILY_CLOSING_BRIEFING）
        assertThat(briefingRepository.findById(DAY)).isPresent();
        List<InAppNotificationDto> notifs = closingService.listNotifications(null, null, false, 50);
        assertThat(notifs).hasSize(2);
        assertThat(notifs).extracting(InAppNotificationDto::alertCode)
                .containsExactlyInAnyOrder(
                        PortfolioClosingService.ALERT_MARKET_REGIME_SHIFT,
                        PortfolioClosingService.ALERT_DAILY_CLOSING_BRIEFING);

        // 標記已讀後重跑：驗證首次 read_at 完整保留
        Long firstId = notifs.getFirst().id();
        Instant firstReadTime = FIXED_NOW.plusSeconds(120);
        clockNow.set(firstReadTime);
        assertThat(closingService.markRead(firstId)).isTrue();

        clockNow.set(FIXED_NOW.plusSeconds(600));
        assertThat(closingService.markRead(firstId)).isFalse();
        doReturn(preloaded).when(marketContextService).preloadMarketData(DAY, FIXED_NOW);
        assertThat(closingService.publishDailyClosing(DAY, FIXED_NOW)).isPresent();

        InAppNotificationDto reloaded = closingService.listNotifications(null, null, false, 50).stream()
                .filter(n -> firstId.equals(n.id()))
                .findFirst().orElseThrow();
        assertThat(reloaded.read()).isTrue();
        assertThat(reloaded.readAt()).isEqualTo(firstReadTime);

        // 驗證上游交易隔離：上游獨立交易提交之雷達紀錄不會因後續簡報 REQUIRES_NEW 階段異常而回滾
        LocalDate nextDay = DAY.plusDays(1);
        transactionTemplate.executeWithoutResult(status -> radarRunRepository.saveAndFlush(
                new DailyOpportunityRadarRun(nextDay, MarketRegime.RISK_ON.name(), false, 1, 1, 0, FIXED_NOW, FIXED_NOW)));
        doThrow(new IllegalStateException("simulated closing failure"))
                .when(marketContextService).preloadMarketData(nextDay, FIXED_NOW);
        try {
            closingService.publishDailyClosing(nextDay, FIXED_NOW);
        } catch (IllegalStateException expected) {
            // 排程端以 try/catch 吸收例外；此處確認異常不會回滾上游已提交的 radar_run
        }
        assertThat(radarRunRepository.findById(nextDay)).isPresent();
        assertThat(briefingRepository.findById(nextDay)).isEmpty();
    }

    /** Scenario 3：於 125 萬筆日線（500 檔 × 2,500 日）實測 findLatestQuotesUpTo 準確性與真實 PostgreSQL EXPLAIN (ANALYZE, BUFFERS)。 */
    @Test
    void findLatestQuotesUpToExplainAnalyzeOnRealisticVolume() {
        jdbcTemplate.execute("TRUNCATE TABLE stock_quote");
        // 灌入 500 檔股票 × 2,500 日 = 1,250,000 筆真實結構日線，並執行 ANALYZE 更新統計資訊
        jdbcTemplate.execute("""
                INSERT INTO stock_quote (figi, quote_date, open_price, high_price, low_price, close_price, volume, observed_at, data_source)
                SELECT
                    'FIGI_' || lpad(s::text, 4, '0'),
                    DATE '2020-01-01' + d,
                    100.0 + (d % 50),
                    102.0 + (d % 50),
                    99.0 + (d % 50),
                    101.0 + (d % 50) + (s * 0.01),
                    1000000,
                    TIMESTAMPTZ '2026-11-18T21:00:00Z',
                    'TEST_SEED'
                FROM generate_series(1, 500) AS s
                CROSS JOIN generate_series(0, 2499) AS d
                """);
        jdbcTemplate.execute("ANALYZE stock_quote");

        Long totalRows = jdbcTemplate.queryForObject("SELECT count(*) FROM stock_quote", Long.class);
        assertThat(totalRows).isEqualTo(1_250_000L);

        LocalDate targetDate = LocalDate.of(2026, 11, 4);
        List<String> figis10 = buildFigis(10);
        List<String> figis50 = buildFigis(50);

        List<StockQuote> result10 = stockQuoteRepository.findLatestQuotesUpTo(figis10, targetDate);
        assertThat(result10).hasSize(10);
        assertThat(result10).allSatisfy(q -> assertThat(q.getQuoteDate()).isEqualTo(targetDate));

        List<StockQuote> result50 = stockQuoteRepository.findLatestQuotesUpTo(figis50, targetDate);
        assertThat(result50).hasSize(50);
        assertThat(result50).allSatisfy(q -> assertThat(q.getQuoteDate()).isEqualTo(targetDate));

        // 驗證重複 FIGI 去重、未知 FIGI 略過、及查詢早於最早日線時安全回空
        List<StockQuote> deduped = stockQuoteRepository.findLatestQuotesUpTo(
                List.of("FIGI_0001", "FIGI_0001", "FIGI_UNKNOWN"), targetDate);
        assertThat(deduped).hasSize(1);
        assertThat(deduped.getFirst().getFigi()).isEqualTo("FIGI_0001");
        assertThat(deduped.getFirst().getQuoteDate()).isEqualTo(targetDate);
        assertThat(stockQuoteRepository.findLatestQuotesUpTo(List.of("FIGI_0001"), LocalDate.of(2019, 12, 31))).isEmpty();

        String plan10 = runExplainAnalyze(figis10, targetDate);
        String plan50 = runExplainAnalyze(figis50, targetDate);
        log.info("=== EXPLAIN (ANALYZE, BUFFERS) findLatestQuotesUpTo LATERAL (10 figis / 1.25M rows) ===\n{}", plan10);
        log.info("=== EXPLAIN (ANALYZE, BUFFERS) findLatestQuotesUpTo LATERAL (50 figis / 1.25M rows) ===\n{}", plan50);

        assertThat(plan10).contains("idx_stock_quote_figi_date").doesNotContain("Seq Scan on stock_quote");
        assertThat(plan50).contains("idx_stock_quote_figi_date").doesNotContain("Seq Scan on stock_quote");
    }

    private String runExplainAnalyze(List<String> figis, LocalDate targetDate) {
        String sql = """
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT q.*
                FROM unnest(cast(? as text[])) AS u(figi)
                CROSS JOIN LATERAL (
                    SELECT sq.*
                    FROM stock_quote sq
                    WHERE sq.figi = u.figi AND sq.quote_date <= ?
                    ORDER BY sq.quote_date DESC
                    LIMIT 1
                ) q
                """;
        List<String> lines = jdbcTemplate.query(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(sql);
                    ps.setArray(1, con.createArrayOf("text", figis.toArray(String[]::new)));
                    ps.setDate(2, Date.valueOf(targetDate));
                    return ps;
                },
                (rs, rowNum) -> rs.getString(1));
        return String.join("\n", lines);
    }

    private List<String> buildFigis(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(String.format("FIGI_%04d", i));
        }
        return list;
    }

    private SectorMoneyFlowDaily sectorFlow(LocalDate date, String code, String name, String cmf20d, String dv1d, String flowState) {
        SectorMoneyFlowDaily row = new SectorMoneyFlowDaily();
        row.setTradeDate(date);
        row.setIndustryCode(code);
        row.setIndustryName(name);
        row.setStockCount(10);
        row.setMarketTotalStockCount(100);
        row.setDollarVolume1d(new BigDecimal(dv1d));
        row.setCmf20d(new BigDecimal(cmf20d));
        row.setBreadthAboveSma50Count(6);
        row.setBreadthSma50Total(10);
        row.setBreadthAboveSma200Count(5);
        row.setBreadthSma200Total(10);
        row.setReturn20dCount(10);
        row.setReturn60dCount(10);
        row.setFlowState(flowState);
        row.setCalculatedAt(FIXED_NOW);
        return row;
    }
}
