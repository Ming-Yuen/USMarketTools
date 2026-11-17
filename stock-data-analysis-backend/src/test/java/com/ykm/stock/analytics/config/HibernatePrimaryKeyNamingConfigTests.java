package com.ykm.stock.analytics.config;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.mapping.PrimaryKey;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HibernatePrimaryKeyNamingConfigTests {

    // 例子：實體表名 STOCK_STATUS 的主鍵約束被命名為 PK_STOCK_STATUS。
    @Test
    void integratorNamesPrimaryKeysFromPhysicalTableNames() {
        Metadata metadata = mock(Metadata.class);
        Database database = mock(Database.class);
        Namespace namespace = mock(Namespace.class);
        Table stockStatus = mock(Table.class);
        PrimaryKey stockStatusPrimaryKey = mock(PrimaryKey.class);

        when(metadata.getDatabase()).thenReturn(database);
        when(database.getNamespaces()).thenReturn(List.of(namespace));
        when(namespace.getTables()).thenReturn(List.of(stockStatus));
        when(stockStatus.getName()).thenReturn("STOCK_STATUS");
        when(stockStatus.getPrimaryKey()).thenReturn(stockStatusPrimaryKey);

        new HibernatePrimaryKeyNamingConfig.PrimaryKeyConstraintNamingIntegrator()
                .integrate(metadata, (BootstrapContext) null, (SessionFactoryImplementor) null);

        verify(stockStatusPrimaryKey).setName("PK_STOCK_STATUS");
    }
}
