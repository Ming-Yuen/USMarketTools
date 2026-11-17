package com.ykm.stock.analytics.config;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.hibernate.mapping.PrimaryKey;
import org.hibernate.mapping.Table;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

@Configuration
public class HibernatePrimaryKeyNamingConfig {

    @Bean
    HibernatePropertiesCustomizer primaryKeyConstraintNamingCustomizer() {
        return properties -> properties.put(
                JpaSettings.INTEGRATOR_PROVIDER,
                (IntegratorProvider) () -> List.of(new PrimaryKeyConstraintNamingIntegrator()));
    }

    static String primaryKeyName(String tableName) {
        return "PK_" + tableName.toUpperCase(Locale.ROOT);
    }

    static final class PrimaryKeyConstraintNamingIntegrator implements Integrator {

        @Override
        public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                              SessionFactoryImplementor sessionFactory) {
            for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
                for (Table table : namespace.getTables()) {
                    PrimaryKey primaryKey = table.getPrimaryKey();
                    if (primaryKey != null) {
                        primaryKey.setName(primaryKeyName(table.getName()));
                    }
                }
            }
        }

        @Override
        public void disintegrate(SessionFactoryImplementor sessionFactory,
                                 SessionFactoryServiceRegistry serviceRegistry) {
        }
    }
}
