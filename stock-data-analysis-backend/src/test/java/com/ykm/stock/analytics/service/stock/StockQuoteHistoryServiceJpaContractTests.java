package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.model.entity.StockQuote;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.key.StockQuoteId;
import com.ykm.stock.analytics.repository.jpa.StockQuoteRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusLockRepositoryImpl;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真實 PostgreSQL：只接受獨立本機測試庫，不讀應用設定或回退到 DEV。 */
class StockQuoteHistoryServiceJpaContractTests {
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
        schema = "quote_batch_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, username, password); Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.connection.url", url + "?currentSchema=" + schema)
                .applySetting("hibernate.connection.username", username)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.connection.pool_size", 4)
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.generate_statistics", true)
                .applySetting("hibernate.jdbc.batch_size", 200)
                .applySetting("hibernate.order_inserts", true)
                .applySetting("hibernate.order_updates", true)
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("jakarta.persistence.validation.mode", "none").build();
        factory = new MetadataSources(registry).addAnnotatedClass(StockQuote.class).addAnnotatedClass(StockStatus.class)
                .buildMetadata().buildSessionFactory();
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

    /** Scenario：新增、相同重跑、更正均只批查一次行情，不隨輸入筆數增加 SELECT。 */
    @Test
    void batchInsertReplayAndCorrectionUseOneQuoteSelect() {
        for (int count : List.of(10, 100)) {
            List<String> sql = new ArrayList<>();
            try (Session session = factory.withOptions().statementInspector(statement -> { sql.add(statement); return statement; }).openSession()) {
                session.beginTransaction();
                session.persist(status("COUNT"));
                session.flush();
                for (String phase : List.of("new", "replay", "changed")) {
                    session.clear();
                    List<StockQuote> rows = new ArrayList<>();
                    for (int i = 0; i < count; i++) rows.add(quote("COUNT", DAY.plusDays(i), phase.equals("changed") ? "11" : "10"));
                    sql.clear();
                    factory.getStatistics().clear();

                    service(session).updateStockQuote(rows);

                    long selects = sql.stream().filter(s -> s.startsWith("select")).count();
                    long quoteSelects = sql.stream().filter(s -> s.startsWith("select") && s.contains(" from stock_quote ")).count();
                    System.out.printf("QUOTE_BATCH phase=%s rows=%d select=%d quoteSelect=%d inserts=%d updates=%d%n",
                            phase, count, selects, quoteSelects, factory.getStatistics().getEntityInsertCount(), factory.getStatistics().getEntityUpdateCount());
                    assertThat(selects).isEqualTo(3);
                    assertThat(quoteSelects).isEqualTo(1);
                    assertThat(factory.getStatistics().getEntityInsertCount()).isEqualTo(phase.equals("new") ? count : 0);
                    assertThat(factory.getStatistics().getEntityUpdateCount()).isEqualTo(phase.equals("changed") ? count : 0);
                    session.clear();
                    List<StockQuote> saved = session.createQuery("from StockQuote q where q.figi = 'COUNT'", StockQuote.class).getResultList();
                    assertThat(saved).hasSize(count).allSatisfy(row -> assertThat(row.getClosePrice()).isEqualByComparingTo(phase.equals("changed") ? "11" : "10"));
                }
                session.getTransaction().rollback();
            }
        }
    }

    /** Scenario：混合新增與已有行情、同批重複鍵仍覆寫為一筆，且保留完整 OHLCV／觀測欄位及最早回退。 */
    @Test
    void mixedBatchOverwritesAllFieldsAndKeepsOneRowPerKey() {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            session.persist(status("MIX"));
            session.persist(quote("MIX", DAY, "10"));
            session.flush();
            session.clear();
            StockQuote correction = quote("MIX", DAY, "11");
            correction.setOpenPrice(new BigDecimal("9"));
            correction.setLowPrice(new BigDecimal("8"));
            correction.setHighPrice(new BigDecimal("12"));
            correction.setVolume(null);
            correction.setDataSource(null);
            correction.setObservedAt(Instant.parse("2026-01-02T00:00:00Z"));

            service(session).updateStockQuote(List.of(quote("MIX", DAY.plusDays(1), "10"), correction,
                    quote("MIX", DAY.plusDays(1), "12")));

            session.clear();
            StockQuote stored = session.find(StockQuote.class, new StockQuoteId("MIX", DAY));
            assertThat(stored).usingRecursiveComparison().ignoringFields("openPrice", "highPrice", "lowPrice", "closePrice").isEqualTo(correction);
            assertThat(stored.getOpenPrice()).isEqualByComparingTo("9");
            assertThat(stored.getHighPrice()).isEqualByComparingTo("12");
            assertThat(stored.getLowPrice()).isEqualByComparingTo("8");
            assertThat(stored.getClosePrice()).isEqualByComparingTo("11");
            assertThat(session.find(StockQuote.class, new StockQuoteId("MIX", DAY.plusDays(1))).getClosePrice()).isEqualByComparingTo("12");
            assertThat(session.createQuery("select count(q) from StockQuote q where q.figi = 'MIX'", Long.class).getSingleResult()).isEqualTo(2);
            session.getTransaction().rollback();
        }
    }

    /** Scenario：行情與已更新的行情／估值水位遇寫入失敗時一起回滾，不留下半完成狀態。 */
    @Test
    void persistenceFailureRollsBackQuotesAndQuoteWatermark() {
        seed("ROLLBACK");
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            service(session).updateStockQuote(List.of(quote("ROLLBACK", DAY, "11")));
            StockStatus status = session.find(StockStatus.class, "ROLLBACK");
            status.setQuoteDate(DAY);
            StockQuote invalid = quote("ROLLBACK", DAY.plusDays(1), "12");
            invalid.setDataSource("X".repeat(51));

            assertThatThrownBy(() -> service(session).updateStockQuote(List.of(invalid))).isInstanceOf(RuntimeException.class);
            session.getTransaction().rollback();
        }
        try (Session session = factory.openSession()) {
            assertThat(session.createQuery("select count(q) from StockQuote q where q.figi = 'ROLLBACK'", Long.class).getSingleResult()).isZero();
            StockStatus status = session.find(StockStatus.class, "ROLLBACK");
            assertThat(status.getQuoteDate()).isEqualTo(DAY.minusDays(2));
        }
    }

    /** Scenario：兩個寫入者同時新增同一主鍵，後者等狀態鎖後才查行情，更新一筆而非重複 INSERT。 */
    @Test
    void concurrentWritersReadExistingQuotesOnlyAfterTheStatusLock() throws Exception {
        seed("CONCURRENT");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch attempting = new CountDownLatch(1);
        try (Session first = factory.openSession()) {
            first.beginTransaction();
            try {
                service(first).updateStockQuote(List.of(quote("CONCURRENT", DAY, "10")));
                Future<?> second = executor.submit(() -> {
                    try (Session session = factory.openSession()) {
                        session.beginTransaction();
                        attempting.countDown();
                        service(session).updateStockQuote(List.of(quote("CONCURRENT", DAY, "11")));
                        session.getTransaction().commit();
                    }
                });
                assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                first.getTransaction().commit();
                second.get(10, TimeUnit.SECONDS);
            } finally {
                if (first.getTransaction().isActive()) first.getTransaction().rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        try (Session session = factory.openSession()) {
            assertThat(session.createQuery("select count(q) from StockQuote q where q.figi = 'CONCURRENT'", Long.class).getSingleResult()).isEqualTo(1);
            assertThat(session.find(StockQuote.class, new StockQuoteId("CONCURRENT", DAY)).getClosePrice()).isEqualByComparingTo("11");
        }
    }

    private static StockQuoteHistoryService service(Session session) {
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
        StockStatusRepository statuses = repositories.getRepository(StockStatusRepository.class,
                RepositoryFragments.just(new StockStatusLockRepositoryImpl(session)));
        return new StockQuoteHistoryService(repositories.getRepository(StockQuoteRepository.class), statuses, null, session);
    }

    private static void seed(String figi) {
        try (Session session = factory.openSession()) {
            session.beginTransaction();
            StockStatus status = status(figi);
            status.setQuoteDate(DAY.minusDays(2));
            session.persist(status);
            session.getTransaction().commit();
        }
    }

    private static StockStatus status(String figi) {
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        return status;
    }

    private static StockQuote quote(String figi, LocalDate day, String value) {
        StockQuote quote = new StockQuote();
        quote.setFigi(figi);
        quote.setQuoteDate(day);
        BigDecimal price = new BigDecimal(value);
        quote.setOpenPrice(price);
        quote.setHighPrice(price);
        quote.setLowPrice(price);
        quote.setClosePrice(price);
        quote.setVolume(BigDecimal.TEN);
        quote.setDataSource("TEST");
        quote.setObservedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return quote;
    }
}
