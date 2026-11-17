package com.ykm.stock.analytics.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Mockito JDBC only: no database, Spring context or scheduler is started. */
class QuartzSchemaBootstrapTests {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    private final ResultSet tables = mock(ResultSet.class);
    private final Statement statement = mock(Statement.class);
    private final SchedulerFactoryBean factory = mock(SchedulerFactoryBean.class);
    private final QuartzSchemaBootstrap bootstrap = new QuartzSchemaBootstrap(source);

    @BeforeEach
    void setUp() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getAutoCommit()).thenReturn(true);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getTables(isNull(), eq("public"), eq("%"), any(String[].class))).thenReturn(tables);
        when(connection.createStatement()).thenReturn(statement);
    }

    @Test
    void emptyQuartzSchemaCreatesElevenTablesBeforeFactoryInitializationAndCommits() throws Exception {
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("stock_status");

        bootstrap.customize(factory);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(sql.capture());
        assertThat(sql.getAllValues().stream().filter(value -> value.startsWith("CREATE TABLE ")).count()).isEqualTo(11);
        assertThat(String.join("\n", sql.getAllValues()).toUpperCase(Locale.ROOT)).doesNotContain("DROP ", "TRUNCATE ", "DELETE FROM");
        org.mockito.InOrder order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(statement, atLeastOnce()).execute(anyString());
        order.verify(connection).commit();
        order.verify(connection).setAutoCommit(true);
        verify(connection, never()).rollback();
        verifyNoInteractions(factory);
    }

    @Test
    void existingCompleteSchemaNeverExecutesDdl() throws Exception {
        List<String> names = quartzTableNames();
        java.util.Iterator<String> iterator = names.iterator();
        when(tables.next()).thenAnswer(invocation -> iterator.hasNext());
        when(tables.getString("TABLE_NAME")).thenAnswer(invocation -> iterator.next());

        bootstrap.customize(factory);

        verify(connection, never()).createStatement();
        verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).commit();
    }

    @Test
    void partialSchemaFailsWithoutCreatingOrRemovingAnything() throws Exception {
        when(tables.next()).thenReturn(true, false);
        when(tables.getString("TABLE_NAME")).thenReturn("qrtz_job_details");

        assertThatThrownBy(() -> bootstrap.customize(factory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete Quartz schema").hasMessageContaining("qrtz_triggers");

        verify(connection, never()).createStatement();
        verify(connection, never()).commit();
    }

    @Test
    void failedDdlRollsBackAndPreservesSqlFailure() throws Exception {
        SQLException failure = new SQLException("permission denied", "42501");
        when(statement.execute(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> bootstrap.customize(factory)).hasRootCause(failure);

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void metadataFailureDoesNotAssumeAnEmptySchema() throws Exception {
        SQLException failure = new SQLException("cannot read metadata");
        when(metadata.getTables(isNull(), eq("public"), eq("%"), any(String[].class))).thenThrow(failure);

        assertThatThrownBy(() -> bootstrap.customize(factory)).hasCause(failure);

        verify(connection, never()).createStatement();
    }

    @Test
    void onlyDevAllowsJpaUpdateAndAllProfilesDisableDestructiveQuartzInitialization() throws Exception {
        for (String resource : List.of("application.properties", "application-dev.properties", "application-uat.properties", "application-prod.properties")) {
            Properties properties = new Properties();
            try (java.io.InputStream input = new ClassPathResource(resource).getInputStream()) { properties.load(input); }
            String expectedDdlMode = resource.equals("application-dev.properties") ? "update" : "none";
            assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).as(resource).isEqualTo(expectedDdlMode);
            assertThat(properties.getProperty("spring.quartz.jdbc.initialize-schema")).as(resource).isEqualTo("never");
            assertThat(properties.stringPropertyNames()).noneMatch(name -> name.startsWith("spring.liquibase."));
        }
        assertThat(quartzTableNames()).hasSize(11).doesNotHaveDuplicates();
        assertThat(new ClassPathResource("db/changelog/db.changelog-master.yaml").exists()).isFalse();
        assertThatCode(() -> Class.forName("liquibase.Liquibase")).isInstanceOf(ClassNotFoundException.class);
    }

    private List<String> quartzTableNames() throws Exception {
        String sql = new ClassPathResource("db/quartz/postgresql.sql").getContentAsString(StandardCharsets.UTF_8);
        return Arrays.stream(sql.split("\n")).filter(line -> line.startsWith("CREATE TABLE public."))
                .map(line -> line.substring("CREATE TABLE public.".length(), line.indexOf(" ("))).toList();
    }
}
