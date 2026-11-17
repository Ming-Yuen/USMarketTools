package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.*;
import com.ykm.stock.analytics.service.valuation.SecFinancialFactsCalculator;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
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
        username = System.getenv().getOrDefault("STOCK_WATERMARK_TEST_DB_USER", "postgres");
        password = System.getenv().getOrDefault("STOCK_WATERMARK_TEST_DB_PASSWORD", "");
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
        for (Class<?> type : List.of(Stock.class, StockStatus.class, StockQuote.class, FinancialReport.class, StockValuationDaily.class,
                StockIndustry.class, StockIndustryHistory.class, StockConcept.class, StockShareObservation.class,
                SecFinancialFact.class, StockSplit.class))
            metadata.addAnnotatedClass(type);
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

    /** 行情選股：支援 ADR／ETF／沒有 CIK 的普通股，但尊重 active 與各股水位。 */
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
            SecFinancialFactsCalculator.Flows financials = new SecFinancialFactsCalculator().calculateSelectedFacts(inputs, ciks.getFirst(), "USD", day,
                    knownAt, List.of(), ignored -> java.util.Set.of(), ignored -> {});
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
        report.setItems(List.of(new FinancialReport.Item(100, "Revenue", new BigDecimal(revenue), null, null)));
        return report;
    }

    @Test
    void latestSuccessUsesActualSavedDates() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,active_status,stock_type) values ('F1','AAPL','ACTIVE','Common Stock')").executeUpdate();
            session.createNativeMutationQuery("""
                    insert into stock_valuation_daily(figi,valuation_date,calculated_at,valuation_cutoff,pe,pe_reason,pb_reason) values
                    ('F1','2025-05-01','2025-05-06','2025-05-01 20:00:00',null,'NON_POSITIVE_EPS','SPLIT_SYNC_ERROR'),
                    ('F1','2025-05-02','2025-05-06','2025-05-02 20:00:00',20,null,null),
                    ('F1','2025-05-05','2025-05-06','2025-05-05 20:00:00',null,'NON_POSITIVE_EPS',null)
                    """).executeUpdate();
            StockValuationDailyRepository repository = new JpaRepositoryFactory(session).getRepository(StockValuationDailyRepository.class);
            assertThat(repository.findLatestAvailableDates(List.of("F1"), LocalDate.of(2025, 5, 1), DAY))
                    .singleElement().satisfies(row -> assertThat(row).containsExactly("F1", LocalDate.of(2025, 5, 2)));
            session.getTransaction().rollback();
        }
    }

    @Test
    void recordedSecCiksAreScopedToTheRequestedStocksAndDates() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,active_status,stock_type) values ('F1','AAPL','ACTIVE','Common Stock'),('F2','JPM','ACTIVE','Common Stock')").executeUpdate();
            session.createNativeMutationQuery("""
                    insert into stock_valuation_daily(figi,valuation_date,calculated_at,valuation_cutoff,sec_financial_cik) values
                    ('F1','2025-04-25','2025-05-06','2025-04-25 20:00:00','0000000001'),
                    ('F1','2025-04-29','2025-05-06','2025-04-29 20:00:00','0000320193'),
                    ('F1','2025-04-30','2025-05-06','2025-04-30 20:00:00','0000320193'),
                    ('F1','2025-05-05','2025-05-06','2025-05-05 20:00:00',null),
                    ('F2','2025-05-05','2025-05-06','2025-05-05 20:00:00','0000019617')
                    """).executeUpdate();
            StockValuationDailyRepository repository = new JpaRepositoryFactory(session).getRepository(StockValuationDailyRepository.class);
            assertThat(repository.findRecordedSecCiks(List.of("F1"), LocalDate.of(2025, 4, 28), DAY)).containsExactly("0000320193");
            session.getTransaction().rollback();
        }
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

    @Test
    void assignedNewValuationIdsDoNotTriggerPerStockSelectsBeforeTheInsertBatch() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.createNativeMutationQuery("insert into stock(figi,symbol,active_status,stock_type) select 'F'||n,'S'||n,'ACTIVE','Common Stock' from generate_series(1,50) n").executeUpdate();
            factory.getStatistics().clear();
            for (int index = 1; index <= 50; index++) {
                StockValuationDaily row = new StockValuationDaily();
                row.setFigi("F" + index);
                row.setValuationDate(DAY);
                row.setValuationCutoff(Instant.parse("2025-05-05T20:00:00Z"));
                row.setCalculatedAt(Instant.parse("2025-05-06T00:00:00Z"));
                row.setPe(java.math.BigDecimal.TEN);
                session.persist(row);
            }
            session.flush();
            assertThat(factory.getStatistics().getEntityLoadCount()).isZero();
            assertThat(factory.getStatistics().getPrepareStatementCount()).isEqualTo(1);
            session.getTransaction().rollback();
        }
    }
}
