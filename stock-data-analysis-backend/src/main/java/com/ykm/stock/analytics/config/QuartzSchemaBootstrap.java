package com.ykm.stock.analytics.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 在 SchedulerFactoryBean 初始化前核對 Quartz 表；僅空庫建表，部分缺表必須人工修復。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.quartz.schema-bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class QuartzSchemaBootstrap implements SchedulerFactoryBeanCustomizer {
    private static final Set<String> REQUIRED_TABLES = Set.of("qrtz_blob_triggers", "qrtz_calendars", "qrtz_cron_triggers",
            "qrtz_fired_triggers", "qrtz_job_details", "qrtz_locks", "qrtz_paused_trigger_grps", "qrtz_scheduler_state",
            "qrtz_simple_triggers", "qrtz_simprop_triggers", "qrtz_triggers");
    private final DataSource dataSource;

    @Override
    public void customize(SchedulerFactoryBean factory) {
        try (Connection connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("Quartz schema bootstrap requires PostgreSQL");
            Set<String> tables = existingTables(connection);
            if (tables.isEmpty()) {
                createSchema(connection);
                log.info("Created Quartz schema in public; all eleven tables committed");
            } else if (!tables.containsAll(REQUIRED_TABLES)) {
                Set<String> missing = new HashSet<>(REQUIRED_TABLES);
                missing.removeAll(tables);
                throw new IllegalStateException("Incomplete Quartz schema; no changes applied. Missing tables: " + missing);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to initialize Quartz schema", ex);
        }
    }

    private Set<String> existingTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (ResultSet result = connection.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
            while (result.next()) {
                String name = result.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (name.startsWith("qrtz_")) tables.add(name);
            }
        }
        return tables;
    }

    /** PostgreSQL DDL 同一交易提交；失敗回滾，避免留下半套 QRTZ 表。 */
    private void createSchema(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/quartz/postgresql.sql"));
            connection.commit();
        } catch (SQLException | RuntimeException ex) {
            try { connection.rollback(); }
            catch (SQLException rollbackFailure) { ex.addSuppressed(rollbackFailure); }
            throw ex;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
}
