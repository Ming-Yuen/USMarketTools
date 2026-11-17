package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.config.StockPostgreSQLDialect;
import com.ykm.stock.analytics.model.entity.FinancialReportItemMapping;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.model.entity.StockConcept;
import com.ykm.stock.analytics.model.entity.key.StockValuationDailyId;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockValuationDailyMappingTests {
    /** 業務實體使用一致的估值表名與複合主鍵，並可建立衍生查詢；不連線或變更資料庫。 */
    @Test
    void entityAndRepositoryBootstrapAgainstStockValuationTableWithoutDatabase() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", StockPostgreSQLDialect.class.getName())
                .applySetting("jakarta.persistence.database-product-name", "PostgreSQL")
                .applySetting("jakarta.persistence.database-major-version", 17)
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("jakarta.persistence.validation.mode", "none").build();
        try {
            Metadata metadata = new MetadataSources(registry).addAnnotatedClass(StockValuationDaily.class).addAnnotatedClass(StockStatus.class)
                    .addAnnotatedClass(Stock.class).addAnnotatedClass(StockIndustry.class).addAnnotatedClass(StockIndustryHistory.class)
                    .addAnnotatedClass(StockConcept.class).addAnnotatedClass(StockShareObservation.class)
                    .addAnnotatedClass(SecFinancialFact.class).addAnnotatedClass(FinancialReportItemMapping.class).buildMetadata();
            List<String> ddl = new SchemaCreatorImpl(registry).generateCreationCommands(metadata, false);
            assertThat(ddl).anySatisfy(sql -> assertThat(sql).startsWith("create table stock_valuation_daily ")
                    .contains("primary key (figi, valuation_date)", "ck_stock_valuation_market_cap_source")
                    .doesNotContain("valuation_basis", "ck_valuation_sec_availability"));
            assertThat(ddl).anySatisfy(sql -> assertThat(sql).startsWith("alter table if exists stock_valuation_daily ")
                    .contains("fk_stock_valuation_stock", "foreign key (figi) references stock"));
            assertThat(ddl).anySatisfy(sql -> assertThat(sql).startsWith("create table stock_share_observation ")
                    .contains("issued_shares > 0"));
            assertThat(ddl).anySatisfy(sql -> assertThat(sql).startsWith("create table sec_financial_fact ")
                    .contains("period_start date", "primary key (id)", "ck_sec_financial_fact_scope"));
            assertThat(ddl).anyMatch(sql -> sql.contains("uq_sec_financial_fact_disclosure unique nulls not distinct"));
            assertThat(ddl).anySatisfy(sql -> assertThat(sql).startsWith("create index idx_sec_financial_disclosed ")
                    .contains("(cik, available_from_date, period_end)"));
            assertThat(ddl).anySatisfy(sql -> assertThat(sql).startsWith("create table financial_report_item_mapping ")
                    .contains("primary key (item_name, statement_type)", "ck_financial_item_mapping_state"));
            PersistentClass mapping = metadata.getEntityBinding(StockValuationDaily.class.getName());
            assertThat(mapping.getTable().getName()).isEqualTo("stock_valuation_daily");
            assertThat(mapping.getIdentifier().getColumns()).extracting(Column::getName).containsExactlyInAnyOrder("figi", "valuation_date");
            PersistentClass status = metadata.getEntityBinding(StockStatus.class.getName());
            Column valuationDate = status.getProperty("valuationDate").getValue().getColumns().getFirst();
            assertThat(valuationDate.getName()).isEqualTo("valuation_date");
            assertThat(valuationDate.isNullable()).isTrue();
            assertThat(status.getProperties()).extracting(org.hibernate.mapping.Property::getName).doesNotContain("valuationFromDate");
            PersistentClass share = metadata.getEntityBinding(StockShareObservation.class.getName());
            assertThat(share.getIdentifier().getColumns()).extracting(Column::getName).containsExactlyInAnyOrder("figi", "observed_at");
            assertThat(mapping.getProperty("marketCapSource").getValue().getColumns()).extracting(Column::getName).containsExactly("market_cap_source");
            assertThat(share.getProperties()).extracting(org.hibernate.mapping.Property::getName).contains("snapshotPrice", "snapshotMarketCap", "snapshotQuoteTime");
            try (SessionFactory factory = metadata.buildSessionFactory(); EntityManager entityManager = factory.createEntityManager()) {
                assertThat(JpaEntityInformationSupport.getEntityInformation(StockValuationDaily.class, entityManager).getIdType()).isEqualTo(StockValuationDailyId.class);
                assertThat(new JpaRepositoryFactory(entityManager).getRepository(StockValuationDailyRepository.class)).isNotNull();
                assertThat(new JpaRepositoryFactory(entityManager).getRepository(StockRepository.class)).isNotNull();
                assertThat(new JpaRepositoryFactory(entityManager).getRepository(StockShareObservationRepository.class)).isNotNull();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
