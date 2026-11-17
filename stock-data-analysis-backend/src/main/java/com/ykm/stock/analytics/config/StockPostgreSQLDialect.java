package com.ykm.stock.analytics.config;

import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.unique.CreateTableUniqueDelegate;
import org.hibernate.dialect.unique.UniqueDelegate;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.mapping.UniqueKey;

public class StockPostgreSQLDialect extends PostgreSQLDialect {
    private final UniqueDelegate uniqueDelegate = new CreateTableUniqueDelegate(this) {
        @Override
        protected String uniqueConstraintSql(UniqueKey key) {
            String sql = super.uniqueConstraintSql(key);
            // 同一存量披露的 period_start 為 NULL，仍須參與唯一性判斷。
            return SecFinancialFact.DISCLOSURE_CONSTRAINT.equals(key.getName())
                    ? "unique nulls not distinct" + sql.substring("unique".length()) : sql;
        }
    };

    public StockPostgreSQLDialect(DialectResolutionInfo info) {
        super(info);
    }

    @Override
    public UniqueDelegate getUniqueDelegate() {
        return uniqueDelegate;
    }
}
