package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.ValuationReason;
import com.ykm.stock.analytics.model.dto.stockInsight.StockScreenResponse;
import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.IndustryValuationConfig;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.model.entity.StockCustomPeer;
import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockSplit;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.service.stock.StockService;
import com.ykm.stock.analytics.service.stock.StockService.ScreenCriteria;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator;
import com.ykm.stock.analytics.service.valuation.StockValuationService;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只使用顯式指定的本機 stock_valuation_test；不讀應用設定或回退到 DEV。 */
class ValuationBatchQueryJpaTests {
    private static final LocalDate DAY = LocalDate.of(2025, 5, 5);
    private static StandardServiceRegistry registry;
    private static SessionFactory factory;
    private static String url;
    private static String username;
    private static String password;
    private static String schema;

    @BeforeAll
    static void openIsolatedDatabase() throws Exception {
        url = System.getenv("STOCK_WATERMARK_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "Set STOCK_WATERMARK_TEST_JDBC_URL to an isolated stock_valuation_test database");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/stock_valuation_test"))
            throw new IllegalArgumentException("Only an explicitly configured local stock_valuation_test database is allowed");
        username = System.getenv().getOrDefault("STOCK_WATERMARK_TEST_DB_USER", "trading");
        password = System.getenv().getOrDefault("STOCK_WATERMARK_TEST_DB_PASSWORD", "TradingPass123!");
        schema = "valuation_batch_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, username, password); Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.connection.url", url + "?currentSchema=" + schema)
                .applySetting("hibernate.connection.username", username)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.generate_statistics", true)
                .applySetting("hibernate.jdbc.time_zone", "UTC")
                .applySetting("hibernate.jdbc.batch_size", 200)
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("jakarta.persistence.validation.mode", "none").build();
        MetadataSources metadata = new MetadataSources(registry);
        for (Class<?> type : List.of(Stock.class, StockStatus.class, StockQuote.class, FinancialReport.class,
                StockFinancialPeriod.class, StockValuationLatest.class, StockCustomPeer.class, StockEarningsForecast.class,
                StockIndustry.class, StockIndustryHistory.class, IndustryValuationConfig.class,
                StockConcept.class, StockShareObservation.class, SecFinancialFact.class, StockSplit.class)) {
            metadata.addAnnotatedClass(type);
        }
        factory = metadata.buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void closeIsolatedDatabase() throws Exception {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        if (schema != null) {
            try (Connection connection = DriverManager.getConnection(url, username, password); Statement statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    @Test
    void quoteCandidatesIncludeSupportedSecuritiesWithoutRequiringSecIdentity() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,cik,active_status,stock_type) values ('CS','CS',null,'ACTIVE','Common Stock'),('OS','OS',null,'ACTIVE','REIT'),('ADR','ADR',null,'ACTIVE','ADR'),('SOXX','SOXX',null,'ACTIVE','ETP'),('PFD','PFD',null,'ACTIVE','PFD'),('OFF','OFF',null,'INACTIVE','ETP'),('DONE','DONE',null,'ACTIVE','ETP')").executeUpdate();
            session.createNativeMutationQuery("insert into stock_status(figi,quote_date) values ('CS',null),('OS','2025-05-02'),('ADR',null),('SOXX',null),('PFD',null),('OFF',null),('DONE','2025-05-05')").executeUpdate();
            StockRepository stocks = new JpaRepositoryFactory(session).getRepository(StockRepository.class);
            factory.getStatistics().clear();

            assertThat(stocks.findAllActiveStockQuoteDates(ActiveStatus.ACTIVE, DAY))
                    .extracting(row -> row.stock().getFigi()).containsExactlyInAnyOrder("CS", "OS", "ADR", "SOXX");
            assertThat(factory.getStatistics().getPrepareStatementCount()).isEqualTo(1);
            session.getTransaction().rollback();
        }
    }

    @Test
    void valuationBatchReadsEightKnownCandidatesPerStockAndStatementInOneQuery() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,active_status,stock_type) values ('F1','AAPL','ACTIVE','Common Stock'),('F2','JPM','ACTIVE','Common Stock')").executeUpdate();
            session.createNativeMutationQuery("""
                    insert into financial_report(figi,statement_type,period_end,period_type,observed_at,items)
                    select 'F1',1,date '2025-05-01' - n,7,timestamp '2025-05-02 19:00:00','[]'::jsonb from generate_series(0,7) n
                    """).executeUpdate();
            session.createNativeMutationQuery("""
                    insert into financial_report(figi,statement_type,period_end,period_type,observed_at,items) values
                    ('F1',2,'2025-05-01',7,'2025-05-02 19:00:00','[]'),
                    ('F2',1,'2025-05-01',7,'2025-05-02 21:00:00','[]'),
                    ('F2',1,'2025-05-04',7,'2025-05-02 19:00:00','[]'),
                    ('F2',1,'2025-03-31',7,'2025-05-02 19:00:00','[]')
                    """).executeUpdate();
            FinancialReportRepository repository = new JpaRepositoryFactory(session).getRepository(FinancialReportRepository.class);
            factory.getStatistics().clear();
            List<FinancialReport> rows = repository.findValuationReports(List.of("F1", "F2"), LocalDate.of(2025, 5, 2), Instant.parse("2025-05-02T20:00:00Z"));
            assertThat(rows).hasSize(10);
            assertThat(rows.stream().filter(row -> row.getFigi().equals("F1") && row.getStatementType() == 1)).hasSize(8);
            assertThat(rows.stream().filter(row -> row.getFigi().equals("F2"))).singleElement()
                    .satisfies(row -> assertThat(row.getPeriodEnd()).isEqualTo(LocalDate.of(2025, 3, 31)));
            assertThat(factory.getStatistics().getPrepareStatementCount()).isEqualTo(1);
            session.getTransaction().rollback();
        }
    }

    @Test
    void selectsLatestPayloadForValuationsAfterFirstAvailability() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,active_status,stock_type) values ('F1','AAPL','ACTIVE','Common Stock')").executeUpdate();
            Instant originalTime = Instant.parse("2025-02-01T12:00:00Z");
            Instant revisionTime = Instant.parse("2025-03-01T12:00:00Z");
            FinancialReport original = report(7, "2024-12-31", originalTime, "10");
            FinancialReport revision = report(7, "2024-12-31", originalTime, "20");
            session.persist(original);
            session.persist(report(4, "2024-12-31", originalTime, "3"));
            session.flush();
            session.remove(original);
            session.flush();
            session.persist(revision);
            session.flush();
            session.clear();
            FinancialReportRepository repository = new JpaRepositoryFactory(session).getRepository(FinancialReportRepository.class);
            assertThat(repository.findValuationReports(List.of("F1"), DAY, originalTime.minusSeconds(1))).isEmpty();
            List<FinancialReport> rows = repository.findValuationReports(List.of("F1"), DAY, revisionTime.minusSeconds(1));
            assertThat(rows).extracting(FinancialReport::getPeriodType).containsExactly(7, 4);
            assertThat(rows.getFirst().getObservedAt()).isEqualTo(originalTime);
            assertThat(rows.getFirst().getItems().getFirst().value()).isEqualByComparingTo("20");
            assertThat(rows.getLast().getItems().getFirst().value()).isEqualByComparingTo("3");
            session.getTransaction().rollback();
        }
    }

    @Test
    void secHistoricalValuationUsesLatestPublishedFinancialsAndKeepsHistoricalShares() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("""
                    insert into sec_financial_fact
                    (id,cik,taxonomy,tag,unit,period_start,period_end,accession,value,filed,form,available_from_date,observed_at)
                    values
                    (1,'0000000001','us-gaap','Revenues','USD','2024-01-01','2024-12-31','0000000001-25-000001',100,'2025-02-03','10-K','2025-02-04','2025-05-01'),
                    (2,'0000000001','us-gaap','Revenues','USD','2024-01-01','2024-12-31','0000000001-25-000002',110,'2025-03-03','10-K/A','2025-03-04','2025-05-01'),
                    (3,'0000000001','us-gaap','Revenues','USD','2024-01-01','2024-12-31','0000000001-25-000003',120,'2025-04-03','10-Q','2025-04-04','2025-05-01'),
                    (4,'0000000001','us-gaap','Revenues','USD','2024-10-01','2024-12-31','0000000001-25-000002',30,'2025-03-03','10-K/A','2025-03-04','2025-05-01'),
                    (5,'0000000001','us-gaap','CommonStockSharesOutstanding','shares',null,'2024-12-31','0000000001-25-000001',10,'2025-02-03','10-K','2025-02-04','2025-05-01'),
                    (6,'0000000001','us-gaap','CommonStockSharesOutstanding','shares',null,'2024-12-31','0000000001-25-000002',20,'2025-03-03','10-K/A','2025-03-04','2025-05-01')
                    """).executeUpdate();
            SecFinancialFactRepository repository = new JpaRepositoryFactory(session).getRepository(SecFinancialFactRepository.class);
            Instant knownAt = Instant.parse("2025-05-05T20:00:00Z");
            List<String> ciks = List.of("0000000001");
            List<String> tags = List.of("Revenues", "CommonStockSharesOutstanding");
            assertThat(repository.findValuationFlows(ciks, tags, LocalDate.of(2025, 2, 3), knownAt))
                    .extracting(SecFinancialFact::getId).containsExactlyInAnyOrder(3L, 4L);
            LocalDate day = LocalDate.of(2025, 2, 18);
            List<SecFinancialFact> inputs = repository.findValuationFlows(ciks, tags, day, knownAt);
            assertThat(inputs).extracting(SecFinancialFact::getId).containsExactlyInAnyOrder(3L, 4L, 5L);
            SecFinancialFactsCalculator.Flows financials = new SecFinancialFactsCalculator()
                    .calculateSelectedFacts(inputs, ciks.getFirst(), null, "USD", day, knownAt, List.of());
            assertThat(financials.revenue().value()).isEqualByComparingTo("120");
            session.getTransaction().rollback();
        }
    }

    private FinancialReport report(int periodType, String periodEnd, Instant observedAt, String revenue) {
        FinancialReport report = new FinancialReport();
        report.setFigi("F1");
        report.setStatementType(1);
        report.setPeriodType(periodType);
        report.setPeriodEnd(LocalDate.parse(periodEnd));
        report.setObservedAt(observedAt);
        report.setCurrency("USD");
        report.setItems(List.of(new FinancialReport.Item(8001L, "Total Revenue as Reported", new BigDecimal(revenue), null, null)));
        return report;
    }

    @Test
    void shareObservationQueryNeverSelectsFutureSnapshotsAndChoosesLatestEligiblePerStock() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,active_status,stock_type) values ('F1','AAPL','ACTIVE','Common Stock'),('F2','JPM','ACTIVE','Common Stock')").executeUpdate();
            session.createNativeMutationQuery("""
                    insert into stock_share_observation(figi,observed_at,issued_shares,source) values
                    ('F1','2025-05-01 20:00:00',100,'FUTU'),('F1','2025-05-02 19:00:00',110,'FUTU'),
                    ('F1','2025-05-02 21:00:00',120,'FUTU'),('F2','2025-05-02 21:00:00',200,'FUTU')
                    """).executeUpdate();
            StockShareObservationRepository repository = new JpaRepositoryFactory(session).getRepository(StockShareObservationRepository.class);
            assertThat(repository.findLatestAt(List.of("F1", "F2"), Instant.parse("2025-05-02T20:00:00Z")))
                    .singleElement().satisfies(row -> {
                        assertThat(row.getFigi()).isEqualTo("F1");
                        assertThat(row.getIssuedShares()).isEqualTo(110);
                        assertThat(row.getObservedAt()).isEqualTo(Instant.parse("2025-05-02T19:00:00Z"));
                    });
            session.getTransaction().rollback();
        }
    }

    /** Scenario：stock_financial_period 新列批次寫入不觸發逐股 SELECT，並支援整股刪除與查詢。 */
    @Test
    void stockFinancialPeriodBatchInsertAndDeleteByFigiInExecuteCleanly() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            factory.getStatistics().clear();
            Instant now = Instant.parse("2025-05-06T00:00:00Z");
            for (int index = 1; index <= 40; index++) {
                StockFinancialPeriod row = new StockFinancialPeriod();
                row.setFigi("F1");
                row.setPeriodEnd(LocalDate.of(2015, 3, 31).plusMonths((index - 1) * 3L));
                row.setPeriodStart(row.getPeriodEnd().minusDays(89));
                row.setReportingCurrency("USD");
                row.setAccountingStandards("US_GAAP");
                row.setRevenueTtm(new BigDecimal("1000000"));
                row.setRevenueTtmStatus("AVAILABLE");
                row.setEpsTtmReason(ValuationReason.NON_POSITIVE_EPS);
                row.setUpdatedAt(now);
                session.persist(row);
            }
            session.flush();
            assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
            assertThat(factory.getStatistics().getPrepareStatementCount()).isEqualTo(1);

            StockFinancialPeriodRepository repo = new JpaRepositoryFactory(session).getRepository(StockFinancialPeriodRepository.class);
            assertThat(repo.findExistingFigis(List.of("F1", "F2"))).isEqualTo(Set.of("F1"));
            assertThat(repo.findByFigiOrderByPeriodEndAsc("F1")).hasSize(40);
            repo.deleteByFigiIn(List.of("F1"));
            session.flush();
            assertThat(repo.findByFigiOrderByPeriodEndAsc("F1")).isEmpty();
            session.getTransaction().rollback();
        }
    }

    /** Scenario：stock_valuation_latest 同業查詢僅回傳 ACTIVE 且 tracked=true 的股票，並回傳 coalesce(s.cik, s.figi) 供同發行人去重。 */
    @Test
    void stockValuationLatestPeerQueryFiltersActiveTrackedAndReturnsIssuerKey() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("""
                    insert into stock(figi,symbol,active_status,stock_type,cik,tracked) values
                    ('G_A','GOOGL','ACTIVE','Common Stock','0001652044',true),
                    ('G_C','GOOG','ACTIVE','Common Stock','0001652044',true),
                    ('SOLO','NOCIK','ACTIVE','Common Stock',null,true),
                    ('UNTRACKED','OFF1','ACTIVE','Common Stock','0000111111',false),
                    ('INACTIVE','OFF2','INACTIVE','Common Stock','0000222222',true)
                    """).executeUpdate();
            Instant close = Instant.parse("2025-05-05T20:00:00Z");
            for (String figi : List.of("G_A", "G_C", "SOLO", "UNTRACKED", "INACTIVE")) {
                StockValuationLatest snap = new StockValuationLatest();
                snap.setFigi(figi);
                snap.setValuationDate(DAY);
                snap.setAnalysisAsOf(close);
                snap.setCalculatedAt(close.plusSeconds(60));
                snap.setIndustryCode("I1");
                snap.setStatus("AVAILABLE");
                snap.setPe(new BigDecimal("22.5"));
                snap.setPeStatus("AVAILABLE");
                session.persist(snap);
            }
            session.flush();

            StockValuationLatestRepository repo = new JpaRepositoryFactory(session).getRepository(StockValuationLatestRepository.class);
            List<Object[]> peers = repo.findActiveTrackedPeerSnapshots("I1", DAY);
            assertThat(peers).hasSize(3);
            assertThat(peers).extracting(row -> (String) row[0])
                    .containsExactlyInAnyOrder("0001652044", "0001652044", "SOLO");
            session.getTransaction().rollback();
        }
    }

    /** Scenario：tracked=false 跳過財報／SEC／估值／分類，但保留同 CIK 兄弟股與大盤基準 ETF 行情拆股；多股類判定排除 ADR 且不受 tracked 影響。 */
    @Test
    void trackedSwitchRetainsSiblingCikAndBenchmarkMarketDataWhileExcludingAdrFromMultiClass() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("""
                    insert into stock(figi,symbol,active_status,stock_type,cik,tracked) values
                    ('G_A','GOOGL','ACTIVE','Common Stock','0001652044',true),
                    ('G_C','GOOG','ACTIVE','Common Stock','0001652044',false),
                    ('SOLO_OFF','XYZ','ACTIVE','Common Stock','0000999999',false),
                    ('ETF_SPY','SPY','ACTIVE','ETP',null,false),
                    ('ETF_OTHER','ARKK','ACTIVE','ETP',null,false),
                    ('DUAL_CS','BHP_CS','ACTIVE','Common Stock','0000888888',true),
                    ('DUAL_ADR','BHP','ACTIVE','ADR','0000888888',true)
                    """).executeUpdate();
            session.createNativeMutationQuery("""
                    insert into stock_status(figi) values
                    ('G_A'),('G_C'),('SOLO_OFF'),('ETF_SPY'),('ETF_OTHER'),('DUAL_CS'),('DUAL_ADR')
                    """).executeUpdate();

            StockRepository repository = new JpaRepositoryFactory(session).getRepository(StockRepository.class);

            assertThat(repository.findValuationStocks(ActiveStatus.ACTIVE))
                    .extracting(row -> row.stock().getFigi())
                    .containsExactlyInAnyOrder("G_A", "DUAL_CS");
            assertThat(repository.findFinancialReportSyncStocks(ActiveStatus.ACTIVE))
                    .extracting(row -> row.stock().getFigi())
                    .containsExactlyInAnyOrder("G_A", "DUAL_CS", "DUAL_ADR");
            assertThat(repository.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE))
                    .extracting(row -> row.stock().getFigi())
                    .containsExactlyInAnyOrder("G_A", "DUAL_CS", "DUAL_ADR");
            assertThat(repository.findAllActiveStocksWithStatus(ActiveStatus.ACTIVE))
                    .extracting(row -> row.stock().getFigi())
                    .containsExactlyInAnyOrder("G_A", "DUAL_CS", "DUAL_ADR");

            assertThat(repository.findSplitSyncStocks(ActiveStatus.ACTIVE))
                    .extracting(Stock::getFigi)
                    .containsExactlyInAnyOrder("G_A", "G_C", "ETF_SPY", "DUAL_CS", "DUAL_ADR");
            assertThat(repository.findAllActiveStockQuoteDates(ActiveStatus.ACTIVE, DAY))
                    .extracting(row -> row.stock().getFigi())
                    .containsExactlyInAnyOrder("G_A", "G_C", "ETF_SPY", "DUAL_CS", "DUAL_ADR");

            assertThat(repository.findMultiSecurityIssuers(
                    List.of("0001652044", "0000999999", "0000888888"), ActiveStatus.ACTIVE))
                    .containsExactly("0001652044");
            assertThat(repository.findIssuerSecurities(List.of("0001652044"), ActiveStatus.ACTIVE))
                    .extracting(row -> row.stock().getFigi())
                    .containsExactly("G_A", "G_C");

            session.getTransaction().rollback();
        }
    }

    @Test
    void peerSnapshotsReturnActiveTrackedRowsWithNormalizedIssuerKeys() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("""
                    insert into stock(figi,symbol,active_status,stock_type,cik,tracked) values
                    ('GOOGL_F','GOOGL','ACTIVE','Common Stock','0001652044',true),
                    ('GOOG_F','GOOG','ACTIVE','Common Stock','0001652044',true),
                    ('NOCIK_F','FERG','ACTIVE','Common Stock','',true),
                    ('STALE_F','UCFI','ACTIVE','Common Stock','0000333333',true),
                    ('UNTRACKED_F','OFF1','ACTIVE','Common Stock','0000111111',false),
                    ('INACTIVE_F','OFF2','INACTIVE','Common Stock','0000222222',true)
                    """).executeUpdate();
            Instant now = Instant.parse("2025-05-05T21:00:00Z");
            for (String figi : List.of("GOOGL_F", "GOOG_F", "NOCIK_F", "STALE_F", "UNTRACKED_F", "INACTIVE_F")) {
                StockValuationLatest snap = new StockValuationLatest();
                snap.setFigi(figi);
                snap.setValuationDate("STALE_F".equals(figi) ? DAY.minusDays(14) : DAY);
                snap.setAnalysisAsOf(now);
                snap.setCalculatedAt(now);
                snap.setIndustryCode("I78");
                snap.setStatus("AVAILABLE");
                snap.setPe(new BigDecimal("20.0000"));
                snap.setPeStatus("AVAILABLE");
                snap.setPbStatus("INSUFFICIENT_DATA");
                snap.setPsStatus("INSUFFICIENT_DATA");
                snap.setPfcfStatus("INSUFFICIENT_DATA");
                snap.setEvEbitdaStatus("INSUFFICIENT_DATA");
                session.persist(snap);
            }
            session.flush();
            StockValuationLatestRepository latestRepo = new JpaRepositoryFactory(session)
                    .getRepository(StockValuationLatestRepository.class);

            List<Object[]> rows = latestRepo.findActiveTrackedPeerSnapshots("I78", DAY);

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> (String) r[0])
                    .containsExactly("0001652044", "0001652044", "NOCIK_F");
            session.getTransaction().rollback();
        }
    }

    @Test
    void customPeersAndMonthEndQuoteQueriesWorkInIsolatedPostgres() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("""
                    insert into stock(figi,symbol,active_status,stock_type,cik,tracked) values
                    ('AMZN_F','AMZN','ACTIVE','Common Stock','0001018724',true),
                    ('MSFT_F','MSFT','ACTIVE','Common Stock','0000789019',true),
                    ('WMT_F','WMT','ACTIVE','Common Stock','0000104169',true),
                    ('OFF_F','OFF','ACTIVE','Common Stock','0000999999',false)
                    """).executeUpdate();
            session.createNativeMutationQuery("""
                    insert into stock_quote(figi,quote_date,open_price,high_price,low_price,close_price) values
                    ('AMZN_F','2025-04-15',178.0,182.0,177.0,180.0),
                    ('AMZN_F','2025-04-30',185.0,190.0,184.0,188.0),
                    ('AMZN_F','2025-05-01',188.0,192.0,187.0,190.0),
                    ('AMZN_F','2025-05-05',192.0,197.0,191.0,195.0)
                    """).executeUpdate();
            session.createNativeMutationQuery("""
                    insert into stock_earnings_forecast(figi,fiscal_period,period_type,observation_date,observed_at,currency,eps,revenue,source) values
                    ('AMZN_F','2025Q2','QUARTERLY','2025-04-04','2025-04-04 20:00:00','USD',1.20,150000000000,'FUTU'),
                    ('AMZN_F','2025Q2','QUARTERLY','2025-05-04','2025-05-04 20:00:00','USD',1.35,155000000000,'FUTU'),
                    ('AMZN_F','2025Q2','QUARTERLY','2025-05-05','2025-05-05 23:00:00','USD',9.99,999000000000,'FUTU')
                    """).executeUpdate();
            session.createNativeMutationQuery("""
                    insert into financial_report(figi,statement_type,period_end,period_type,fiscal_year,period,observed_at,items) values
                    ('AMZN_F',1,'2025-03-31',1,2025,'2025Q1','2025-05-01 20:00:00','[]'::jsonb),
                    ('AMZN_F',1,'2025-06-30',2,2025,'2025Q2','2025-08-01 20:00:00','[]'::jsonb)
                    """).executeUpdate();

            Instant now = Instant.parse("2025-05-05T21:00:00Z");
            for (String figi : List.of("MSFT_F", "WMT_F", "OFF_F")) {
                StockValuationLatest snap = new StockValuationLatest();
                snap.setFigi(figi);
                snap.setValuationDate(DAY);
                snap.setAnalysisAsOf(now);
                snap.setCalculatedAt(now);
                snap.setIndustryCode("I10");
                snap.setStatus("AVAILABLE");
                snap.setMarketCap(new BigDecimal("500000000000"));
                snap.setMarketCapTier("MEGA_LARGE");
                snap.setPe(new BigDecimal("28.0000"));
                snap.setPeStatus("AVAILABLE");
                snap.setPbStatus("INSUFFICIENT_DATA");
                snap.setPsStatus("INSUFFICIENT_DATA");
                snap.setPfcfStatus("INSUFFICIENT_DATA");
                snap.setEvEbitdaStatus("INSUFFICIENT_DATA");
                session.persist(snap);
            }
            session.persist(new StockCustomPeer("AMZN_F", "MSFT_F", "PE", 0, now));
            session.persist(new StockCustomPeer("AMZN_F", "WMT_F", "PE", 1, now));
            session.persist(new StockCustomPeer("AMZN_F", "OFF_F", "PE", 2, now));
            session.flush();

            JpaRepositoryFactory repoFactory = new JpaRepositoryFactory(session);
            StockCustomPeerRepository customRepo = repoFactory.getRepository(StockCustomPeerRepository.class);
            StockValuationLatestRepository latestRepo = repoFactory.getRepository(StockValuationLatestRepository.class);
            StockQuoteRepository quoteRepo = repoFactory.getRepository(StockQuoteRepository.class);
            StockEarningsForecastRepository forecastRepo = repoFactory.getRepository(StockEarningsForecastRepository.class);
            FinancialReportRepository reportRepo = repoFactory.getRepository(FinancialReportRepository.class);

            assertThat(customRepo.findByFigiOrderBySortOrderAsc("AMZN_F"))
                    .extracting(StockCustomPeer::getPeerFigi)
                    .containsExactly("MSFT_F", "WMT_F", "OFF_F");
            assertThat(customRepo.findTargetFigisByPeerFigiIn(List.of("WMT_F"))).containsExactly("AMZN_F");

            List<Object[]> customSnaps = latestRepo.findActiveTrackedCustomPeerSnapshots(
                    List.of("MSFT_F", "WMT_F", "OFF_F"), List.of(DAY));
            assertThat(customSnaps).hasSize(2);

            List<StockQuote> monthEnds = quoteRepo.findMonthEndQuotes(List.of("AMZN_F"), LocalDate.of(2025, 4, 1), DAY);
            assertThat(monthEnds).extracting(StockQuote::getQuoteDate)
                    .containsExactlyInAnyOrder(LocalDate.of(2025, 4, 30), DAY);

            // 驗證預期查詢遵守 observed_at <= cutoff（23:00Z 的未來觀察被過濾，返回 05-04 的 1.35）與歷史序列查詢
            List<StockEarningsForecast> latestForecasts = forecastRepo.findLatestForStock("AMZN_F", DAY, now);
            assertThat(latestForecasts).singleElement()
                    .satisfies(f -> assertThat(f.getEps()).isEqualByComparingTo("1.35"));
            List<StockEarningsForecast> historyForecasts = forecastRepo.findHistoryForPeriod(
                    "AMZN_F", "2025Q2", "QUARTERLY", LocalDate.of(2025, 1, 1), DAY, now);
            assertThat(historyForecasts).hasSize(2);

            // 驗證輕量財期投影不載入未來公布報期（2025Q2 公布於 8 月 > cutoff，僅返回 2025Q1）
            List<FinancialReportRepository.KnownReportPeriod> knownPeriods = reportRepo.findKnownReportPeriods("AMZN_F", now);
            assertThat(knownPeriods).extracting(FinancialReportRepository.KnownReportPeriod::period)
                    .containsExactly("2025Q1");

            customRepo.deleteByFigi("AMZN_F");
            session.flush();
            assertThat(customRepo.findByFigiOrderBySortOrderAsc("AMZN_F")).isEmpty();
            session.getTransaction().rollback();
        }
    }

    @Test
    void screenerCrossPageGlobalSortAndMultiHundredStockAtomicRollbackWorkInIsolatedPostgres() {
        DriverManagerDataSource ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, username, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        StockService screenService = new StockService(
                org.mockito.Mockito.mock(StockRepository.class),
                org.mockito.Mockito.mock(StockStatusRepository.class),
                org.mockito.Mockito.mock(StockIndustryRepository.class),
                org.mockito.Mockito.mock(StockClassificationConverter.class),
                org.mockito.Mockito.mock(StockConceptRepository.class),
                jdbc);

        try {
            // 1) 在真實 PostgreSQL 寫入 5 檔跨幣別／跨結論股票，驗證分頁前全域排序（marketCap 僅排 USD、verdict 排 1..6 且 INSUFFICIENT_EVIDENCE / NULL 置底）
            txTemplate.executeWithoutResult(tx -> {
                jdbc.getJdbcOperations().execute("""
                        insert into stock(figi,symbol,long_name,active_status,stock_type,currency,tracked) values
                        ('S_CAD','SHOPCA','CAD Giant','ACTIVE','Common Stock','CAD',true),
                        ('S_BIG','MSFT','US Big','ACTIVE','Common Stock','USD',true),
                        ('S_MID','MIDC','US Mid','ACTIVE','Common Stock','USD',true),
                        ('S_SML','SMLC','US Small','ACTIVE','Common Stock','USD',true),
                        ('S_NOV','NOVAL','No Valuation','ACTIVE','Common Stock','USD',true)
                        """);
                jdbc.getJdbcOperations().execute("""
                        insert into stock_valuation_latest(figi,valuation_date,analysis_as_of,calculated_at,currency,market_cap,market_cap_tier,verdict,status) values
                        ('S_CAD','2025-05-05','2025-05-05 21:00:00','2025-05-05 21:00:00','CAD',900000000000,null,'INSUFFICIENT_EVIDENCE','AVAILABLE'),
                        ('S_BIG','2025-05-05','2025-05-05 21:00:00','2025-05-05 21:00:00','USD',300000000000,'MEGA_LARGE','MODERATELY_OVERVALUED','AVAILABLE'),
                        ('S_MID','2025-05-05','2025-05-05 21:00:00','2025-05-05 21:00:00','USD',5000000000,'MID','FAIR_LOWER','AVAILABLE'),
                        ('S_SML','2025-05-05','2025-05-05 21:00:00','2025-05-05 21:00:00','USD',800000000,'SMALL','DEEP_UNDERVALUED','AVAILABLE')
                        """);
            });

            StockScreenResponse capPage0 = screenService.screen(new ScreenCriteria(
                    null, null, null, null, null, null, null, null, null, null, "marketCap", "desc", 0, 2));
            StockScreenResponse capPage1 = screenService.screen(new ScreenCriteria(
                    null, null, null, null, null, null, null, null, null, null, "marketCap", "desc", 1, 2));
            StockScreenResponse capPage2 = screenService.screen(new ScreenCriteria(
                    null, null, null, null, null, null, null, null, null, null, "marketCap", "desc", 2, 2));
            assertThat(capPage0.items()).extracting(StockScreenResponse.Item::figi).containsExactly("S_BIG", "S_MID");
            assertThat(capPage1.items()).extracting(StockScreenResponse.Item::figi).containsExactly("S_SML", "S_NOV");
            assertThat(capPage2.items()).extracting(StockScreenResponse.Item::figi).containsExactly("S_CAD");

            StockScreenResponse verdictPage0 = screenService.screen(new ScreenCriteria(
                    null, null, null, null, null, null, null, null, null, null, "verdict", "asc", 0, 2));
            StockScreenResponse verdictPage1 = screenService.screen(new ScreenCriteria(
                    null, null, null, null, null, null, null, null, null, null, "verdict", "asc", 1, 2));
            assertThat(verdictPage0.items()).extracting(StockScreenResponse.Item::figi).containsExactly("S_SML", "S_MID");
            assertThat(verdictPage1.items()).extracting(StockScreenResponse.Item::figi).containsExactly("S_BIG", "S_NOV");
        } finally {
            jdbc.getJdbcOperations().execute("delete from stock_valuation_latest");
            jdbc.getJdbcOperations().execute("delete from stock");
        }
    }

    @Test
    void realJpaTransactionsRollBackOver100StockRefreshFailureAndRespectConcurrentCustomPeerDelete() {
        DriverManagerDataSource verifyDs = new DriverManagerDataSource(url + "?currentSchema=" + schema, username, password);
        NamedParameterJdbcTemplate verifyJdbc = new NamedParameterJdbcTemplate(verifyDs);

        JpaTransactionManager jpaTx = new JpaTransactionManager(factory);
        TransactionTemplate jpaTxTemplate = new TransactionTemplate(jpaTx);
        EntityManager sharedEm = SharedEntityManagerCreator.createSharedEntityManager(factory);
        JpaRepositoryFactory repoFactory = new JpaRepositoryFactory(sharedEm);

        StockRepository stockRepo = repoFactory.getRepository(StockRepository.class);
        StockStatusRepository baseStatusRepo = repoFactory.getRepository(
                StockStatusRepository.class, new StockStatusLockRepositoryImpl(sharedEm));
        StockQuoteRepository quoteRepo = repoFactory.getRepository(StockQuoteRepository.class);
        StockSplitRepository splitRepo = repoFactory.getRepository(StockSplitRepository.class);
        FinancialReportRepository reportRepo = repoFactory.getRepository(FinancialReportRepository.class);
        SecFinancialFactRepository secRepo = repoFactory.getRepository(SecFinancialFactRepository.class);
        StockShareObservationRepository obsRepo = repoFactory.getRepository(StockShareObservationRepository.class);
        StockIndustryHistoryRepository indHistRepo = repoFactory.getRepository(StockIndustryHistoryRepository.class);
        IndustryValuationConfigRepository indCfgRepo = repoFactory.getRepository(IndustryValuationConfigRepository.class);
        StockFinancialPeriodRepository periodRepo = repoFactory.getRepository(StockFinancialPeriodRepository.class);
        StockValuationLatestRepository latestRepo = repoFactory.getRepository(StockValuationLatestRepository.class);
        StockCustomPeerRepository customRepo = repoFactory.getRepository(StockCustomPeerRepository.class);

        Instant closeAt = Instant.parse("2025-05-05T20:00:00Z");
        Instant now = Instant.parse("2025-05-05T21:00:00Z");
        MarketStatusService calendar = org.mockito.Mockito.mock(MarketStatusService.class);
        org.mockito.Mockito.when(calendar.getLastClosedTradingDay(org.mockito.ArgumentMatchers.any())).thenReturn(DAY);
        org.mockito.Mockito.when(calendar.getSessionOpenAt(org.mockito.ArgumentMatchers.any())).thenReturn(closeAt.minusSeconds(23400));
        org.mockito.Mockito.when(calendar.getSessionCloseAt(org.mockito.ArgumentMatchers.any())).thenReturn(closeAt);
        DateTimeService clock = org.mockito.Mockito.mock(DateTimeService.class);
        org.mockito.Mockito.when(clock.now()).thenReturn(now);
        SecIssuerHistory issuerHistory = new SecIssuerHistory("");

        java.util.concurrent.atomic.AtomicBoolean failSaveStatuses = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean deleteCustomOnLock = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean insertCrossIndustryPutOnLock = new java.util.concurrent.atomic.AtomicBoolean(false);

        StockStatusRepository hookedStatusRepo = (StockStatusRepository) java.lang.reflect.Proxy.newProxyInstance(
                StockStatusRepository.class.getClassLoader(),
                new Class<?>[]{StockStatusRepository.class},
                (proxy, method, args) -> {
                    if ("acquireValuationPublishLock".equals(method.getName()) && deleteCustomOnLock.compareAndSet(true, false)) {
                        // 模擬背景刷新完成 Pass 1 後、取得發布鎖前瞬間，另一獨立連線剛提交 DELETE 自訂對標組
                        verifyJdbc.getJdbcOperations().update("delete from stock_custom_peer where figi = 'T_MAIN'");
                    }
                    if ("acquireValuationPublishLock".equals(method.getName()) && insertCrossIndustryPutOnLock.compareAndSet(true, false)) {
                        // 模擬 CP_3 刷新完成 Pass 1 反查引用者後、取得發布鎖前瞬間，另一交易剛提交 PUT 將 CP_3 加入 T_MAIN 自訂清單並以舊倍數算成 DEEP_UNDERVALUED
                        verifyJdbc.getJdbcOperations().update("""
                                insert into stock_custom_peer(figi,peer_figi,preferred_metric,sort_order,updated_at) values
                                ('T_MAIN','CP_1','PE',0,'2025-05-05 21:00:00'),
                                ('T_MAIN','CP_2','PE',1,'2025-05-05 21:00:00'),
                                ('T_MAIN','CP_3','PE',2,'2025-05-05 21:00:00')
                                """);
                        verifyJdbc.getJdbcOperations().update("update stock_valuation_latest set verdict = 'DEEP_UNDERVALUED' where figi = 'T_MAIN'");
                    }
                    if ("saveAllAndFlush".equals(method.getName()) && failSaveStatuses.get()) {
                        java.util.Collection<?> items = (java.util.Collection<?>) args[0];
                        if (items.size() > 100) {
                            throw new IllegalStateException("SIMULATED_SECOND_BATCH_COMMIT_FAILURE");
                        }
                    }
                    return method.invoke(baseStatusRepo, args);
                });

        StockValuationService realValuationService = new StockValuationService(
                stockRepo, hookedStatusRepo, quoteRepo, splitRepo, reportRepo, secRepo, obsRepo,
                indHistRepo, indCfgRepo, periodRepo, latestRepo, customRepo,
                calendar, clock, issuerHistory, jpaTxTemplate, sharedEm);

        try {
            // 1) 超過 100 檔（105 檔）刷新時注入寫入失敗 -> 驗證單一寫入交易完整回滾，另一獨立連線讀到 0 筆快照殘留
            java.util.List<String> batch105 = new java.util.ArrayList<>();
            jpaTxTemplate.executeWithoutResult(tx -> {
                verifyJdbc.getJdbcOperations().execute("insert into stock_industry(code,industry) values ('I_TX','Tech')");
                for (int i = 1; i <= 105; i++) {
                    String f = String.format("B_%03d", i);
                    batch105.add(f);
                    verifyJdbc.getJdbcOperations().update(
                            "insert into stock(figi,symbol,active_status,stock_type,currency,cik,tracked) values (?,?,'ACTIVE','Common Stock','USD',?,true)",
                            f, "SYM" + i, String.format("%010d", 500000 + i));
                    verifyJdbc.getJdbcOperations().update(
                            "insert into stock_status(figi,quote_date) values (?,?)", f, java.sql.Date.valueOf(DAY));
                    verifyJdbc.getJdbcOperations().update(
                            "insert into stock_quote(figi,quote_date,open_price,high_price,low_price,close_price) values (?,?,100,100,100,100)",
                            f, java.sql.Date.valueOf(DAY));
                    verifyJdbc.getJdbcOperations().update(
                            "insert into stock_industry_history(id,figi,industry_code,observed_from) values (?,?,'I_TX','2020-01-01 00:00:00')", i, f);
                    verifyJdbc.getJdbcOperations().update("""
                            insert into stock_financial_period(
                              figi,period_end,period_start,reporting_currency,accounting_standards,available_at,updated_at,
                              eps_ttm,eps_ttm_status,eps_share_basis_date,
                              shares_outstanding,shares_outstanding_status,shares_basis_date,shares_outstanding_available_at
                            ) values (?,?,?,'USD','US_GAAP',?,?,5.0,'AVAILABLE',?,100000000,'AVAILABLE',?,?)
                            """,
                            f, java.sql.Date.valueOf("2025-03-31"), java.sql.Date.valueOf("2024-04-01"),
                            java.sql.Timestamp.from(closeAt.minusSeconds(3600)), java.sql.Timestamp.from(now),
                            java.sql.Date.valueOf("2025-03-31"),
                            java.sql.Date.valueOf("2025-03-31"), java.sql.Timestamp.from(closeAt.minusSeconds(3600)));
                }
            });

            failSaveStatuses.set(true);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> realValuationService.refreshLatestValuations(batch105, DAY))
                    .isInstanceOf(IllegalStateException.class);
            failSaveStatuses.set(false);

            Long committedAfterFailure = verifyJdbc.getJdbcOperations().queryForObject(
                    "select count(*) from stock_valuation_latest where figi like 'B_%'", Long.class);
            assertThat(committedAfterFailure).isZero();

            // 2) 讓「刷新 Pass 1 預讀 -> 另一連線提交 DELETE 自訂清單 -> 刷新 Pass 2 取鎖寫入」依序發生，驗證鎖後空清單生效，絕不回退舊自訂清單
            jpaTxTemplate.executeWithoutResult(tx -> {
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock(figi,symbol,active_status,stock_type,currency,cik,tracked) values ('T_MAIN','TMAIN','ACTIVE','Common Stock','USD','0000900000',true)");
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_status(figi,quote_date) values ('T_MAIN',?)", java.sql.Date.valueOf(DAY));
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_quote(figi,quote_date,open_price,high_price,low_price,close_price) values ('T_MAIN',?,100,100,100,100)",
                        java.sql.Date.valueOf(DAY));
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_industry_history(id,figi,industry_code,observed_from) values (999,'T_MAIN','I_TX','2020-01-01 00:00:00')");
                verifyJdbc.getJdbcOperations().update("""
                        insert into stock_financial_period(
                          figi,period_end,period_start,reporting_currency,accounting_standards,available_at,updated_at,
                          eps_ttm,eps_ttm_status,eps_share_basis_date,
                          shares_outstanding,shares_outstanding_status,shares_basis_date,shares_outstanding_available_at
                        ) values ('T_MAIN','2025-03-31','2024-04-01','USD','US_GAAP',?,?,5.0,'AVAILABLE','2025-03-31',200000000,'AVAILABLE','2025-03-31',?)
                        """,
                        java.sql.Timestamp.from(closeAt.minusSeconds(3600)), java.sql.Timestamp.from(now),
                        java.sql.Timestamp.from(closeAt.minusSeconds(3600)));

                // 5 家同產業低倍數對手（PE = 8 -> 參考價 40，使現價 100 的 T_MAIN 在無自訂組時為 OVERVALUED_RISK）
                for (int i = 1; i <= 5; i++) {
                    String ip = "IP_" + i;
                    verifyJdbc.getJdbcOperations().update(
                            "insert into stock(figi,symbol,active_status,stock_type,currency,cik,tracked) values (?,?,'ACTIVE','Common Stock','USD',?,true)",
                            ip, ip, String.format("%010d", 910000 + i));
                    verifyJdbc.getJdbcOperations().update("""
                            insert into stock_valuation_latest(figi,valuation_date,analysis_as_of,calculated_at,industry_code,currency,market_cap,market_cap_tier,pe,pe_status,status)
                            values (?,?,?,?,'I_TX','USD',20000000000,'MEGA_LARGE',8.0,'AVAILABLE','AVAILABLE')
                            """, ip, java.sql.Date.valueOf(DAY), java.sql.Timestamp.from(closeAt), java.sql.Timestamp.from(now));
                }
                // 3 家跨產業高倍數自訂對手（PE = 40 -> 參考價 200，若誤用舊自訂組會算出 DEEP_UNDERVALUED）
                for (int i = 1; i <= 3; i++) {
                    String cp = "CP_" + i;
                    verifyJdbc.getJdbcOperations().update(
                            "insert into stock(figi,symbol,active_status,stock_type,currency,cik,tracked) values (?,?,'ACTIVE','Common Stock','USD',?,true)",
                            cp, cp, String.format("%010d", 920000 + i));
                    verifyJdbc.getJdbcOperations().update("""
                            insert into stock_valuation_latest(figi,valuation_date,analysis_as_of,calculated_at,industry_code,currency,market_cap,market_cap_tier,pe,pe_status,status)
                            values (?,?,?,?,'I_OTHER','USD',20000000000,'MEGA_LARGE',40.0,'AVAILABLE','AVAILABLE')
                            """, cp, java.sql.Date.valueOf(DAY), java.sql.Timestamp.from(closeAt), java.sql.Timestamp.from(now));
                }
            });

            realValuationService.refreshLatestValuations(List.of("T_MAIN"), DAY);
            realValuationService.saveCustomPeers("T_MAIN", List.of("CP_1", "CP_2", "CP_3"), "PE");
            String verdictWithCustom = verifyJdbc.getJdbcOperations().queryForObject(
                    "select verdict from stock_valuation_latest where figi = 'T_MAIN'", String.class);
            assertThat(verdictWithCustom).isEqualTo("DEEP_UNDERVALUED");

            // 啟動刷新並在取鎖前瞬間由另一連線提交 DELETE，完成後另一交易讀到清單為空且 verdict 已還原為產業 OVERVALUED_RISK
            deleteCustomOnLock.set(true);
            realValuationService.refreshLatestValuations(List.of("T_MAIN"), DAY);
            Long remainingCustom = verifyJdbc.getJdbcOperations().queryForObject(
                    "select count(*) from stock_custom_peer where figi = 'T_MAIN'", Long.class);
            String verdictAfterConcurrentDelete = verifyJdbc.getJdbcOperations().queryForObject(
                    "select verdict from stock_valuation_latest where figi = 'T_MAIN'", String.class);
            assertThat(remainingCustom).isZero();
            assertThat(verdictAfterConcurrentDelete).isEqualTo("OVERVALUED_RISK");

            // 3) 讓「CP_3 刷新 Pass 1（倍數改為低倍數 6）-> 另一連線提交跨產業 PUT 將 CP_1(6), CP_2(6), CP_3 加入 T_MAIN -> CP_3 Pass 2 取鎖重查反向集合並重試」依序發生
            jpaTxTemplate.executeWithoutResult(tx -> {
                verifyJdbc.getJdbcOperations().update("insert into stock_industry(code,industry) values ('I_OTHER','Retail')");
                for (int i = 1; i <= 2; i++) {
                    verifyJdbc.getJdbcOperations().update("update stock_valuation_latest set pe = 6.0 where figi = ?", "CP_" + i);
                }
                verifyJdbc.getJdbcOperations().update("insert into stock_status(figi,quote_date) values ('CP_3',?)", java.sql.Date.valueOf(DAY));
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_quote(figi,quote_date,open_price,high_price,low_price,close_price) values ('CP_3',?,30,30,30,30)",
                        java.sql.Date.valueOf(DAY));
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_industry_history(id,figi,industry_code,observed_from) values (1003,'CP_3','I_OTHER','2020-01-01 00:00:00')");
                verifyJdbc.getJdbcOperations().update("""
                        insert into stock_financial_period(
                          figi,period_end,period_start,reporting_currency,accounting_standards,available_at,updated_at,
                          eps_ttm,eps_ttm_status,eps_share_basis_date,
                          shares_outstanding,shares_outstanding_status,shares_basis_date,shares_outstanding_available_at
                        ) values ('CP_3','2025-03-31','2024-04-01','USD','US_GAAP',?,?,5.0,'AVAILABLE','2025-03-31',200000000,'AVAILABLE','2025-03-31',?)
                        """,
                        java.sql.Timestamp.from(closeAt.minusSeconds(3600)), java.sql.Timestamp.from(now),
                        java.sql.Timestamp.from(closeAt.minusSeconds(3600)));
            });

            insertCrossIndustryPutOnLock.set(true);
            realValuationService.refreshLatestValuations(List.of("CP_3"), DAY);
            String cp3NewPe = verifyJdbc.getJdbcOperations().queryForObject(
                    "select pe::text from stock_valuation_latest where figi = 'CP_3'", String.class);
            String tMainUpdatedVerdict = verifyJdbc.getJdbcOperations().queryForObject(
                    "select verdict from stock_valuation_latest where figi = 'T_MAIN'", String.class);
            assertThat(new BigDecimal(cp3NewPe)).isEqualByComparingTo("6.0000");
            assertThat(tMainUpdatedVerdict).isEqualTo("OVERVALUED_RISK");
            // 4) 即使有無快照或已停追的 U_NOSNAP 自訂引用 CP_3，刷新 [CP_3, IP_1] 仍必須正常發布兩檔快照並傳播更新 T_MAIN；
            //    且在外層交易內停追 CP_3 時不發生 UnexpectedRollbackException，T_MAIN 因有效自訂樣本剩 2 家退回產業分層 OVERVALUED_RISK
            StockService verifyScreenService = new StockService(
                    org.mockito.Mockito.mock(StockRepository.class),
                    org.mockito.Mockito.mock(StockStatusRepository.class),
                    org.mockito.Mockito.mock(StockIndustryRepository.class),
                    org.mockito.Mockito.mock(StockClassificationConverter.class),
                    org.mockito.Mockito.mock(StockConceptRepository.class),
                    verifyJdbc);

            jpaTxTemplate.executeWithoutResult(tx -> {
                for (int i = 1; i <= 2; i++) {
                    verifyJdbc.getJdbcOperations().update("update stock_valuation_latest set pe = 40.0 where figi = ?", "CP_" + i);
                }
                verifyJdbc.getJdbcOperations().update(
                        "update stock_quote set open_price = 200, high_price = 200, low_price = 200, close_price = 200 where figi = 'CP_3'");
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock(figi,symbol,active_status,stock_type,currency,cik,tracked) values ('U_NOSNAP','UNOS','ACTIVE','Common Stock','USD','0000999111',false)");
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_custom_peer(figi,peer_figi,preferred_metric,sort_order,updated_at) values ('U_NOSNAP','CP_3','PE',0,'2025-05-05 21:00:00')");
                verifyJdbc.getJdbcOperations().update("insert into stock_status(figi,quote_date) values ('IP_1',?)", java.sql.Date.valueOf(DAY));
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_quote(figi,quote_date,open_price,high_price,low_price,close_price) values ('IP_1',?,45,45,45,45)",
                        java.sql.Date.valueOf(DAY));
                verifyJdbc.getJdbcOperations().update(
                        "insert into stock_industry_history(id,figi,industry_code,observed_from) values (1004,'IP_1','I_TX','2020-01-01 00:00:00')");
                verifyJdbc.getJdbcOperations().update("""
                        insert into stock_financial_period(
                          figi,period_end,period_start,reporting_currency,accounting_standards,available_at,updated_at,
                          eps_ttm,eps_ttm_status,eps_share_basis_date,
                          shares_outstanding,shares_outstanding_status,shares_basis_date,shares_outstanding_available_at
                        ) values ('IP_1','2025-03-31','2024-04-01','USD','US_GAAP',?,?,5.0,'AVAILABLE','2025-03-31',200000000,'AVAILABLE','2025-03-31',?)
                        """,
                        java.sql.Timestamp.from(closeAt.minusSeconds(3600)), java.sql.Timestamp.from(now),
                        java.sql.Timestamp.from(closeAt.minusSeconds(3600)));
            });

            realValuationService.refreshLatestValuations(List.of("CP_3", "IP_1"), DAY);
            String ip1NewPe = verifyJdbc.getJdbcOperations().queryForObject(
                    "select pe::text from stock_valuation_latest where figi = 'IP_1'", String.class);
            String tMainAfterRefresh = verifyJdbc.getJdbcOperations().queryForObject(
                    "select verdict from stock_valuation_latest where figi = 'T_MAIN'", String.class);
            String ip1VerdictAfterRefresh = verifyJdbc.getJdbcOperations().queryForObject(
                    "select verdict from stock_valuation_latest where figi = 'IP_1'", String.class);
            assertThat(new BigDecimal(ip1NewPe)).isEqualByComparingTo("9.0000");
            assertThat(tMainAfterRefresh).isEqualTo("DEEP_UNDERVALUED");
            assertThat(ip1VerdictAfterRefresh).isEqualTo("MODERATELY_OVERVALUED");

            // 模擬 Controller 外層 @Transactional 內執行停追並傳播更新
            jpaTxTemplate.executeWithoutResult(outerTx -> {
                Stock cp3Stock = stockRepo.findById("CP_3").orElseThrow();
                cp3Stock.setTracked(false);
                stockRepo.saveAndFlush(cp3Stock);
                realValuationService.onTrackedChanged(List.of("CP_3"));
            });
            Boolean cp3Tracked = verifyJdbc.getJdbcOperations().queryForObject(
                    "select tracked from stock where figi = 'CP_3'", Boolean.class);
            assertThat(cp3Tracked).isFalse();

            // 停追 CP_3 後，T_MAIN 自訂組有效樣本跌至 2 家，自動退回 I_TX 5 家產業同業池（OVERVALUED_RISK），且選股器與正式報告結論 100% 一致
            StockScreenResponse afterUntrackScreen = verifyScreenService.screen(new ScreenCriteria(
                    null, "I_TX", null, null, null, null, null, null, null, null, "symbol", "asc", 0, 200));
            java.util.Map<String, String> screenVerdicts = afterUntrackScreen.items().stream()
                    .collect(java.util.stream.Collectors.toMap(StockScreenResponse.Item::figi,
                            item -> item.verdict() == null ? "NULL" : item.verdict()));
            assertThat(screenVerdicts.get("T_MAIN")).isEqualTo("OVERVALUED_RISK");
            assertThat(screenVerdicts.get("IP_1")).isEqualTo("MODERATELY_OVERVALUED");

            for (String checkFigi : List.of("T_MAIN", "IP_1")) {
                Stock checkStock = stockRepo.findById(checkFigi).orElseThrow();
                StockValuationService.ReportValuationBundle bundle = realValuationService.evaluateForReport(
                        checkStock, DAY, closeAt, now, "I_TX");
                String reportVerdict = com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.computeObjectiveVerdict(
                        bundle.valuation().price(), bundle.valuation().historicalReference(), bundle.valuation().peerReference());
                assertThat(screenVerdicts.get(checkFigi)).isEqualTo(reportVerdict);
            }
        } finally {
            verifyJdbc.getJdbcOperations().execute("delete from stock_custom_peer");
            verifyJdbc.getJdbcOperations().execute("delete from stock_valuation_latest");
            verifyJdbc.getJdbcOperations().execute("delete from stock_financial_period");
            verifyJdbc.getJdbcOperations().execute("delete from stock_industry_history");
            verifyJdbc.getJdbcOperations().execute("delete from stock_quote");
            verifyJdbc.getJdbcOperations().execute("delete from stock_status");
            verifyJdbc.getJdbcOperations().execute("delete from stock");
            verifyJdbc.getJdbcOperations().execute("delete from stock_industry");
        }
    }

    @Test
    void twoIndependentPostgresConnectionsSerializeInterleavedRefreshAndEmptyPoolFirstPublishWithMatchingVerdicts() throws Exception {
        DriverManagerDataSource verifyDs = new DriverManagerDataSource(url + "?currentSchema=" + schema, username, password);
        NamedParameterJdbcTemplate verifyJdbc = new NamedParameterJdbcTemplate(verifyDs);

        Instant closeAt = Instant.parse("2025-05-05T20:00:00Z");
        Instant now = Instant.parse("2025-05-05T21:00:00Z");
        MarketStatusService calendar = org.mockito.Mockito.mock(MarketStatusService.class);
        org.mockito.Mockito.when(calendar.getLastClosedTradingDay(org.mockito.ArgumentMatchers.any())).thenReturn(DAY);
        org.mockito.Mockito.when(calendar.getSessionOpenAt(org.mockito.ArgumentMatchers.any())).thenReturn(closeAt.minusSeconds(23400));
        org.mockito.Mockito.when(calendar.getSessionCloseAt(org.mockito.ArgumentMatchers.any())).thenReturn(closeAt);
        DateTimeService clock = org.mockito.Mockito.mock(DateTimeService.class);
        org.mockito.Mockito.when(clock.now()).thenReturn(now);
        SecIssuerHistory issuerHistory = new SecIssuerHistory("");

        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CyclicBarrier> pass2EntryBarrier =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> conn1EnteredPass2 =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> releaseConn1Pass2 =
                new java.util.concurrent.atomic.AtomicReference<>();

        StockValuationService svcConn1 = createIsolatedValuationService(
                calendar, clock, issuerHistory, pass2EntryBarrier, conn1EnteredPass2, releaseConn1Pass2);
        StockValuationService svcConn2 = createIsolatedValuationService(
                calendar, clock, issuerHistory, pass2EntryBarrier, null, null);

        JpaTransactionManager setupTxMgr = new JpaTransactionManager(factory);
        TransactionTemplate setupTx = new TransactionTemplate(setupTxMgr);
        EntityManager readEm = SharedEntityManagerCreator.createSharedEntityManager(factory);
        StockRepository readStockRepo = new JpaRepositoryFactory(readEm).getRepository(StockRepository.class);

        StockService screenService = new StockService(
                org.mockito.Mockito.mock(StockRepository.class),
                org.mockito.Mockito.mock(StockStatusRepository.class),
                org.mockito.Mockito.mock(StockIndustryRepository.class),
                org.mockito.Mockito.mock(StockClassificationConverter.class),
                org.mockito.Mockito.mock(StockConceptRepository.class),
                verifyJdbc);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // 案例 A（Codex Item 1）：A_STK 與 M_STK 同屬 I_CONC_1，Z_STK 屬 I_CONC_2，M_STK 自訂引用 [C_1, C_2, Z_STK]；
            // 兩條獨立 PostgreSQL 連線同時進入 Pass 2 分別刷新 Z_STK 與 A_STK，零死鎖且最終選股器與正式報告結論完全一致
            setupTx.executeWithoutResult(tx -> {
                verifyJdbc.getJdbcOperations().execute("insert into stock_industry(code,industry) values ('I_CONC_1','Cloud'),('I_CONC_2','Chips'),('I_EMPTY','NewPool')");
                insertFullValuableStock(verifyJdbc, 2001, "A_STK", "0000700001", "I_CONC_1", 100, closeAt, now);
                insertFullValuableStock(verifyJdbc, 2002, "M_STK", "0000700002", "I_CONC_1", 100, closeAt, now);
                for (int i = 1; i <= 4; i++) {
                    insertFullValuableStock(verifyJdbc, 2010 + i, "P_" + i, String.format("%010d", 700010 + i), "I_CONC_1", 40, closeAt, now);
                }
                insertFullValuableStock(verifyJdbc, 2021, "C_1", "0000700021", "I_CONC_2", 30, closeAt, now);
                insertFullValuableStock(verifyJdbc, 2022, "C_2", "0000700022", "I_CONC_2", 30, closeAt, now);
                insertFullValuableStock(verifyJdbc, 2023, "Z_STK", "0000700023", "I_CONC_2", 200, closeAt, now);
            });

            svcConn1.refreshLatestValuations(List.of("A_STK", "M_STK", "P_1", "P_2", "P_3", "P_4", "C_1", "C_2", "Z_STK"), DAY);
            svcConn1.saveCustomPeers("M_STK", List.of("C_1", "C_2", "Z_STK"), "PE");

            // 將 Z_STK 行情調低為 30（PE = 6），A_STK 行情調低為 35（PE = 7），讓兩條連線同時完成 Pass 1 後齊步進入 Pass 2
            setupTx.executeWithoutResult(tx -> {
                verifyJdbc.getJdbcOperations().update(
                        "update stock_quote set open_price = 30, high_price = 30, low_price = 30, close_price = 30 where figi = 'Z_STK'");
                verifyJdbc.getJdbcOperations().update(
                        "update stock_quote set open_price = 35, high_price = 35, low_price = 35, close_price = 35 where figi = 'A_STK'");
            });

            pass2EntryBarrier.set(new java.util.concurrent.CyclicBarrier(2));
            java.util.concurrent.Future<?> fZ = pool.submit(() -> svcConn1.refreshLatestValuations(List.of("Z_STK"), DAY));
            java.util.concurrent.Future<?> fA = pool.submit(() -> svcConn2.refreshLatestValuations(List.of("A_STK"), DAY));
            fZ.get(15, java.util.concurrent.TimeUnit.SECONDS);
            fA.get(15, java.util.concurrent.TimeUnit.SECONDS);
            pass2EntryBarrier.set(null);

            StockScreenResponse concScreen1 = screenService.screen(new ScreenCriteria(
                    null, null, null, null, null, null, null, null, null, null, "symbol", "asc", 0, 50));
            java.util.Map<String, String> concVerdicts1 = concScreen1.items().stream()
                    .collect(java.util.stream.Collectors.toMap(StockScreenResponse.Item::figi,
                            item -> item.verdict() == null ? "NULL" : item.verdict()));
            assertThat(concVerdicts1.get("M_STK")).isEqualTo("OVERVALUED_RISK");
            for (String figi : List.of("A_STK", "M_STK", "Z_STK")) {
                String ind = "Z_STK".equals(figi) ? "I_CONC_2" : "I_CONC_1";
                Stock st = readStockRepo.findById(figi).orElseThrow();
                StockValuationService.ReportValuationBundle rep = svcConn1.evaluateForReport(st, DAY, closeAt, now, ind);
                String expectedVerdict = com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.computeObjectiveVerdict(
                        rep.valuation().price(), rep.valuation().historicalReference(), rep.valuation().peerReference());
                assertThat(concVerdicts1.get(figi)).isEqualTo(expectedVerdict);
            }

            // 案例 B（Codex Item 2）：同產業 I_EMPTY 空池首次併發發布（連線 1 發布 E_1..E_3，連線 2 發布 E_4..E_6，Pass 1 雙方皆讀到 0 筆同業）
            setupTx.executeWithoutResult(tx -> {
                insertFullValuableStock(verifyJdbc, 3001, "E_1", "0000710001", "I_EMPTY", 200, closeAt, now);
                for (int i = 2; i <= 6; i++) {
                    insertFullValuableStock(verifyJdbc, 3000 + i, "E_" + i, String.format("%010d", 710000 + i), "I_EMPTY", 50, closeAt, now);
                }
            });

            pass2EntryBarrier.set(new java.util.concurrent.CyclicBarrier(2));
            java.util.concurrent.Future<?> fBatch1 = pool.submit(
                    () -> svcConn1.refreshLatestValuations(List.of("E_1", "E_2", "E_3"), DAY));
            java.util.concurrent.Future<?> fBatch2 = pool.submit(
                    () -> svcConn2.refreshLatestValuations(List.of("E_4", "E_5", "E_6"), DAY));
            fBatch1.get(15, java.util.concurrent.TimeUnit.SECONDS);
            fBatch2.get(15, java.util.concurrent.TimeUnit.SECONDS);
            pass2EntryBarrier.set(null);

            StockScreenResponse emptyPoolScreen = screenService.screen(new ScreenCriteria(
                    null, "I_EMPTY", null, null, null, null, null, null, null, null, "symbol", "asc", 0, 50));
            java.util.Map<String, String> emptyPoolVerdicts = emptyPoolScreen.items().stream()
                    .collect(java.util.stream.Collectors.toMap(StockScreenResponse.Item::figi,
                            item -> item.verdict() == null ? "NULL" : item.verdict()));
            assertThat(emptyPoolVerdicts).hasSize(6);
            assertThat(emptyPoolVerdicts.get("E_1")).isEqualTo("OVERVALUED_RISK");

            // 逐股核對 E_1..E_6：無任何一檔殘留為 INSUFFICIENT_EVIDENCE，且選股器與正式報告（sampleCount = 5）結論 100% 一致
            for (int i = 1; i <= 6; i++) {
                String figi = "E_" + i;
                Stock st = readStockRepo.findById(figi).orElseThrow();
                StockValuationService.ReportValuationBundle rep = svcConn1.evaluateForReport(st, DAY, closeAt, now, "I_EMPTY");
                assertThat(rep.valuation().peerReference().status()).isEqualTo("AVAILABLE");
                assertThat(rep.valuation().peerReference().sampleCount()).isEqualTo(5);
                String reportVerdict = com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.computeObjectiveVerdict(
                        rep.valuation().price(), rep.valuation().historicalReference(), rep.valuation().peerReference());
                assertThat(emptyPoolVerdicts.get(figi))
                        .isNotEqualTo("INSUFFICIENT_EVIDENCE")
                        .isEqualTo(reportVerdict);
            }

            // 案例 C（Codex Round 5 P1）：部分重疊刷新——池內原有 4 家（O_1..O_4），慢批完成 [O_A, O_B] 的 Pass 1 後，快批先以較新行情發布 O_A（當時僅 4 家同業故為 INSUFFICIENT_EVIDENCE）；
            // 慢批隨後取得發布鎖，須保留 O_A 較新的快照與倍數、發布 O_B，並將 O_A 降級為反向目標更新 verdict 為 OVERVALUED_RISK
            setupTx.executeWithoutResult(tx -> {
                verifyJdbc.getJdbcOperations().execute("insert into stock_industry(code,industry) values ('I_OVERLAP','OverlapPool')");
                for (int i = 1; i <= 4; i++) {
                    insertFullValuableStock(verifyJdbc, 4000 + i, "O_" + i, String.format("%010d", 720000 + i), "I_OVERLAP", 50, closeAt, now);
                }
                insertFullValuableStock(verifyJdbc, 4010, "O_A", "0000720010", "I_OVERLAP", 200, closeAt, now);
                insertFullValuableStock(verifyJdbc, 4011, "O_B", "0000720011", "I_OVERLAP", 50, closeAt, now);
            });
            svcConn1.refreshLatestValuations(List.of("O_1", "O_2", "O_3", "O_4"), DAY);

            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            conn1EnteredPass2.set(entered);
            releaseConn1Pass2.set(release);

            java.util.concurrent.Future<?> slowOverlap = pool.submit(
                    () -> svcConn1.refreshLatestValuations(List.of("O_A", "O_B"), DAY));
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            Instant newerCalcAt = now.plusSeconds(30);
            org.mockito.Mockito.when(clock.now()).thenReturn(newerCalcAt);
            setupTx.executeWithoutResult(tx -> verifyJdbc.getJdbcOperations().update(
                    "update stock_quote set open_price = 210, high_price = 210, low_price = 210, close_price = 210 where figi = 'O_A'"));
            svcConn2.refreshLatestValuations(List.of("O_A"), DAY);

            String oAVerdictBeforeSlow = verifyJdbc.getJdbcOperations().queryForObject(
                    "select verdict from stock_valuation_latest where figi = 'O_A'", String.class);
            assertThat(oAVerdictBeforeSlow).isEqualTo("INSUFFICIENT_EVIDENCE");

            conn1EnteredPass2.set(null);
            releaseConn1Pass2.set(null);
            release.countDown();
            slowOverlap.get(15, java.util.concurrent.TimeUnit.SECONDS);

            String oAPeFinal = verifyJdbc.getJdbcOperations().queryForObject(
                    "select pe::text from stock_valuation_latest where figi = 'O_A'", String.class);
            assertThat(new BigDecimal(oAPeFinal)).isEqualByComparingTo("42.0000");

            StockScreenResponse overlapScreen = screenService.screen(new ScreenCriteria(
                    null, "I_OVERLAP", null, null, null, null, null, null, null, null, "symbol", "asc", 0, 50));
            java.util.Map<String, String> overlapVerdicts = overlapScreen.items().stream()
                    .collect(java.util.stream.Collectors.toMap(StockScreenResponse.Item::figi,
                            item -> item.verdict() == null ? "NULL" : item.verdict()));
            assertThat(overlapVerdicts.get("O_A")).isEqualTo("OVERVALUED_RISK");

            for (String figi : List.of("O_1", "O_2", "O_3", "O_4", "O_A", "O_B")) {
                Stock st = readStockRepo.findById(figi).orElseThrow();
                StockValuationService.ReportValuationBundle rep = svcConn1.evaluateForReport(st, DAY, closeAt, newerCalcAt, "I_OVERLAP");
                String reportVerdict = com.ykm.stock.analytics.service.analysis.calculator.ValuationSummaryCalculator.computeObjectiveVerdict(
                        rep.valuation().price(), rep.valuation().historicalReference(), rep.valuation().peerReference());
                assertThat(overlapVerdicts.get(figi))
                        .isNotEqualTo("INSUFFICIENT_EVIDENCE")
                        .isEqualTo(reportVerdict);
            }
        } finally {
            pool.shutdownNow();
            verifyJdbc.getJdbcOperations().execute("delete from stock_custom_peer");
            verifyJdbc.getJdbcOperations().execute("delete from stock_valuation_latest");
            verifyJdbc.getJdbcOperations().execute("delete from stock_financial_period");
            verifyJdbc.getJdbcOperations().execute("delete from stock_industry_history");
            verifyJdbc.getJdbcOperations().execute("delete from stock_quote");
            verifyJdbc.getJdbcOperations().execute("delete from stock_status");
            verifyJdbc.getJdbcOperations().execute("delete from stock");
            verifyJdbc.getJdbcOperations().execute("delete from stock_industry");
        }
    }

    private StockValuationService createIsolatedValuationService(
            MarketStatusService calendar,
            DateTimeService clock,
            SecIssuerHistory issuerHistory,
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CyclicBarrier> pass2EntryBarrier,
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> enteredLatchRef,
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> releaseLatchRef) {
        JpaTransactionManager txManager = new JpaTransactionManager(factory);
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        EntityManager sharedEm = SharedEntityManagerCreator.createSharedEntityManager(factory);
        JpaRepositoryFactory repoFactory = new JpaRepositoryFactory(sharedEm);

        StockRepository stockRepo = repoFactory.getRepository(StockRepository.class);
        StockStatusRepository baseStatusRepo = repoFactory.getRepository(
                StockStatusRepository.class, new StockStatusLockRepositoryImpl(sharedEm));
        StockStatusRepository barrierStatusRepo = (StockStatusRepository) java.lang.reflect.Proxy.newProxyInstance(
                StockStatusRepository.class.getClassLoader(),
                new Class<?>[]{StockStatusRepository.class},
                (proxy, method, args) -> {
                    if ("acquireValuationPublishLock".equals(method.getName())) {
                        java.util.concurrent.CyclicBarrier barrier = pass2EntryBarrier.get();
                        if (barrier != null) {
                            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        if (enteredLatchRef != null && releaseLatchRef != null) {
                            java.util.concurrent.CountDownLatch entered = enteredLatchRef.get();
                            java.util.concurrent.CountDownLatch release = releaseLatchRef.get();
                            if (entered != null && release != null) {
                                entered.countDown();
                                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            }
                        }
                    }
                    return method.invoke(baseStatusRepo, args);
                });

        return new StockValuationService(
                stockRepo,
                barrierStatusRepo,
                repoFactory.getRepository(StockQuoteRepository.class),
                repoFactory.getRepository(StockSplitRepository.class),
                repoFactory.getRepository(FinancialReportRepository.class),
                repoFactory.getRepository(SecFinancialFactRepository.class),
                repoFactory.getRepository(StockShareObservationRepository.class),
                repoFactory.getRepository(StockIndustryHistoryRepository.class),
                repoFactory.getRepository(IndustryValuationConfigRepository.class),
                repoFactory.getRepository(StockFinancialPeriodRepository.class),
                repoFactory.getRepository(StockValuationLatestRepository.class),
                repoFactory.getRepository(StockCustomPeerRepository.class),
                calendar,
                clock,
                issuerHistory,
                txTemplate,
                sharedEm
        );
    }

    private void insertFullValuableStock(
            NamedParameterJdbcTemplate jdbc, int histId, String figi, String cik, String industryCode,
            int closePrice, Instant closeAt, Instant now) {
        jdbc.getJdbcOperations().update(
                "insert into stock(figi,symbol,active_status,stock_type,currency,cik,tracked) values (?,?,'ACTIVE','Common Stock','USD',?,true)",
                figi, figi, cik);
        jdbc.getJdbcOperations().update(
                "insert into stock_status(figi,quote_date) values (?,?)", figi, java.sql.Date.valueOf(DAY));
        jdbc.getJdbcOperations().update(
                "insert into stock_quote(figi,quote_date,open_price,high_price,low_price,close_price) values (?,?,?,?,?,?)",
                figi, java.sql.Date.valueOf(DAY), closePrice, closePrice, closePrice, closePrice);
        jdbc.getJdbcOperations().update(
                "insert into stock_industry_history(id,figi,industry_code,observed_from) values (?,?,?,'2020-01-01 00:00:00')",
                histId, figi, industryCode);
        jdbc.getJdbcOperations().update("""
                insert into stock_financial_period(
                  figi,period_end,period_start,reporting_currency,accounting_standards,available_at,updated_at,
                  eps_ttm,eps_ttm_status,eps_share_basis_date,
                  shares_outstanding,shares_outstanding_status,shares_basis_date,shares_outstanding_available_at
                ) values (?,?,?,'USD','US_GAAP',?,?,5.0,'AVAILABLE',?,200000000,'AVAILABLE',?,?)
                """,
                figi, java.sql.Date.valueOf("2025-03-31"), java.sql.Date.valueOf("2024-04-01"),
                java.sql.Timestamp.from(closeAt.minusSeconds(3600)), java.sql.Timestamp.from(now),
                java.sql.Date.valueOf("2025-03-31"),
                java.sql.Date.valueOf("2025-03-31"), java.sql.Timestamp.from(closeAt.minusSeconds(3600)));
    }
}
