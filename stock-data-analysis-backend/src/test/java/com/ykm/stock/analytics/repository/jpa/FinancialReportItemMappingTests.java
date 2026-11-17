package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.FinancialReportItemMapping;
import com.ykm.stock.analytics.model.entity.key.FinancialReportItemMappingId;
import com.ykm.stock.analytics.enums.FinancialItemMappingStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialReportItemMappingTests {
    @Test
    void sourceNameIsScopedByStatementTypeAndRepositoryBootstrapsWithoutDatabase() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("jakarta.persistence.validation.mode", "none").build();
        try {
            Metadata metadata = new MetadataSources(registry).addAnnotatedClass(FinancialReportItemMapping.class).buildMetadata();
            PersistentClass mapping = metadata.getEntityBinding(FinancialReportItemMapping.class.getName());
            assertThat(mapping.getTable().getName()).isEqualTo("financial_report_item_mapping");
            assertThat(mapping.getIdentifier().getColumns()).extracting(Column::getName)
                    .containsExactlyInAnyOrder("statement_type", "item_name");
            assertThat(mapping.getProperty("mappingStatus").getValue().getColumns().getFirst().getDefaultValue()).isEqualTo("'PENDING'");
            assertThat(new FinancialReportItemMapping().getMappingStatus()).isEqualTo(FinancialItemMappingStatus.PENDING);
            try (SessionFactory factory = metadata.buildSessionFactory(); EntityManager entityManager = factory.createEntityManager()) {
                assertThat(JpaEntityInformationSupport.getEntityInformation(FinancialReportItemMapping.class, entityManager).getIdType())
                        .isEqualTo(FinancialReportItemMappingId.class);
                assertThat(new JpaRepositoryFactory(entityManager).getRepository(FinancialReportItemMappingRepository.class)).isNotNull();
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
