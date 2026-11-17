package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.service.stock.StockService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真實 PostgreSQL 測試，只接受顯式指定的本機隔離測試庫；未設定即明確 skip，不讀應用程式 DB 設定。 */
class StockStatusWatermarkLockJpaTests {
    private static final LocalDate INITIAL = LocalDate.of(2026, 11, 5);
    private static final LocalDate REWOUND = LocalDate.of(2026, 11, 2);
    private static StandardServiceRegistry registry;
    private static SessionFactory factory;
    private static String url;
    private static String username;
    private static String password;
    private static String schema;

    @BeforeAll
    static void openIsolatedDatabase() throws Exception {
        url = System.getenv("STOCK_WATERMARK_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "Set STOCK_WATERMARK_TEST_JDBC_URL to an explicitly isolated local stock_valuation_test database");
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/stock_valuation_test"))
            throw new IllegalArgumentException("Refusing any database except explicitly configured local stock_valuation_test");
        username = System.getenv().getOrDefault("STOCK_WATERMARK_TEST_DB_USER", "postgres");
        password = System.getenv().getOrDefault("STOCK_WATERMARK_TEST_DB_PASSWORD", "");
        schema = "watermark_lock_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", username)
                .applySetting("hibernate.connection.password", password)
                .applySetting("hibernate.connection.pool_size", 4)
                .applySetting("hibernate.default_schema", schema)
                .applySetting("hibernate.generate_statistics", true)
                .applySetting("hibernate.jdbc.time_zone", "UTC")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .applySetting("jakarta.persistence.validation.mode", "none").build();
        factory = new MetadataSources(registry).addAnnotatedClass(StockStatus.class).buildMetadata().buildSessionFactory();
    }

    @AfterAll
    static void closeIsolatedDatabase() throws Exception {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
        if (schema != null) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    /** Scenario：修改前鎖查詢仍返回 OSIV 舊實體；修改後鎖定再批次重讀取得已提交回退，只用兩次 SQL。 */
    @Test
    void lockingReloadsAnAlreadyManagedStaleWatermark() {
        seed("STALE");
        try (Session first = factory.openSession()) {
            first.getTransaction().begin();
            StockStatus stale = first.find(StockStatus.class, "STALE");
            assertThat(stale.getSplitDate()).isEqualTo(INITIAL);
            try (Session second = factory.openSession()) {
                second.getTransaction().begin();
                StockStatus current = repository(second).findAllByIdForUpdate(List.of("STALE")).getFirst();
                current.setSplitDate(REWOUND);
                current.setSplitFromDate(REWOUND.minusDays(1));
                second.getTransaction().commit();
            }
            assertThat(stale.getSplitDate()).isEqualTo(INITIAL);
            long statementsBefore = factory.getStatistics().getPrepareStatementCount();

            StockStatus reloaded = repository(first).findAllByIdForUpdate(List.of("STALE")).getFirst();

            assertThat(factory.getStatistics().getPrepareStatementCount() - statementsBefore).isEqualTo(2);
            assertThat(reloaded).isNotSameAs(stale);
            assertThat(first.contains(stale)).isFalse();
            assertThat(reloaded.getSplitDate()).isEqualTo(REWOUND);
            assertThat(reloaded.getSplitFromDate()).isEqualTo(REWOUND.minusDays(1));
            reloaded.setQuoteDate(INITIAL.plusDays(1));
            first.getTransaction().commit();
        }
        try (Session verify = factory.openSession()) {
            StockStatus saved = verify.find(StockStatus.class, "STALE");
            assertThat(saved.getSplitDate()).isEqualTo(REWOUND);
            assertThat(saved.getQuoteDate()).isEqualTo(INITIAL.plusDays(1));
            assertThat(saved.getSplitFromDate()).isEqualTo(REWOUND.minusDays(1));
        }
    }

    /** Scenario：輸入 FIGI 順序相反，repository 仍以固定 FIGI 次序取鎖。 */
    @Test
    void lockQueryUsesStableFigiOrder() {
        seed("ORDER_A");
        seed("ORDER_B");
        try (Session session = factory.openSession()) {
            session.getTransaction().begin();
            assertThat(repository(session).findAllByIdForUpdate(List.of("ORDER_B", "ORDER_A")))
                    .extracting(StockStatus::getFigi).containsExactly("ORDER_A", "ORDER_B");
            session.getTransaction().commit();
        }
    }

    /** Scenario：不是手動拼接 fragment；Spring 自動發現實作、注入共享 EntityManager，MANDATORY 只准在外層 JPA 交易內執行。 */
    @Test
    void springRepositoryScanningDiscoversTheFragmentAndJoinsTheJpaTransaction() {
        seed("SPRING_WIRED");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(RepositoryWiringConfiguration.class)) {
            assertThat(context.getBeansOfType(JpaRepository.class)).containsOnlyKeys("stockStatusRepository");
            StockStatusRepository repository = context.getBean(StockStatusRepository.class);
            assertThatThrownBy(() -> repository.findAllByIdForUpdate(List.of("SPRING_WIRED")))
                    .isInstanceOf(IllegalTransactionStateException.class);

            TransactionTemplate transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
            transaction.executeWithoutResult(tx -> {
                StockStatus locked = repository.findAllByIdForUpdate(List.of("SPRING_WIRED")).getFirst();
                assertThat(locked.getSplitDate()).isEqualTo(INITIAL);
                locked.setSplitDate(REWOUND);
                locked.setQuoteDate(INITIAL.plusDays(1));
            });
        }
        // 小型測試 context 不擁有此工廠，關閉 context 不能破壞同類別其他隔離 DB 測試。
        assertThat(factory.isOpen()).isTrue();
        try (Session verify = factory.openSession()) {
            StockStatus persisted = verify.find(StockStatus.class, "SPRING_WIRED");
            assertThat(persisted.getSplitDate()).isEqualTo(REWOUND);
            assertThat(persisted.getQuoteDate()).isEqualTo(INITIAL.plusDays(1));
            assertThat(persisted.getSplitFromDate()).isEqualTo(INITIAL);
        }
    }

    /** 真實 Spring 交易：個股行情錯誤只提交訊息，保留拆股水位。 */
    @Test
    void quoteFailureServiceCommitsOnlyTheAffectedMessage() {
        seed("QUOTE_BAD");
        seed("QUOTE_GOOD");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(RepositoryWiringConfiguration.class)) {
            context.getBean(StockService.class).recordQuoteFailures(java.util.Map.of("QUOTE_BAD", "INVALID_ALPACA_OHLCV"));
        }
        try (Session verify = factory.openSession()) {
            StockStatus bad = verify.find(StockStatus.class, "QUOTE_BAD");
            StockStatus good = verify.find(StockStatus.class, "QUOTE_GOOD");
            assertThat(bad.getQuoteMessage()).isEqualTo("INVALID_ALPACA_OHLCV");
            assertThat(bad.getQuoteDate()).isNull();
            assertThat(bad.getSplitFromDate()).isEqualTo(INITIAL);
            assertThat(bad.getSplitDate()).isEqualTo(INITIAL);
            assertThat(good.getQuoteMessage()).isNull();
            assertThat(good.getSplitDate()).isEqualTo(INITIAL);
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = StockStatusRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = StockStatusRepository.class))
    static class RepositoryWiringConfiguration {
        @Bean
        StockService stockService(StockStatusRepository statuses) {
            return new StockService(null, statuses, null, null, null, null);
        }
        @Bean(destroyMethod = "")
        EntityManagerFactory entityManagerFactory() {
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }

    private static StockStatusRepository repository(EntityManager entityManager) {
        return new JpaRepositoryFactory(entityManager).getRepository(StockStatusRepository.class,
                RepositoryFragments.just(new StockStatusLockRepositoryImpl(entityManager)));
    }

    private static void seed(String figi) {
        try (Session session = factory.openSession()) {
            session.getTransaction().begin();
            StockStatus status = new StockStatus();
            status.setFigi(figi);
            status.setSplitDate(INITIAL);
            status.setSplitFromDate(INITIAL);
            session.persist(status);
            session.getTransaction().commit();
        }
    }
}
