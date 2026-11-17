package com.ykm.stock.analytics.scheduling;

import com.ykm.StockAnalyticsApplication;
import com.ykm.stock.analytics.config.QuartzConfig;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.init.DataInitializer;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 明確人工啟用的真實 DEV 驗收：只寫 SEC 新表，測試限制 CIK 不加入正式 Job 參數。 */
@EnabledIfEnvironmentVariable(named = "RUN_SEC_FINANCIAL_FACTS_DEV_ACCEPTANCE", matches = "true")
@ActiveProfiles("dev")
@SpringBootTest(classes = StockAnalyticsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=none", "spring.quartz.auto-startup=false", "app.schedules.enabled=false",
        "app.quartz.schema-bootstrap.enabled=false", "system.date.offset-days=0"})
class SecFinancialFactsDevAcceptanceTests {
    @MockitoBean private StockRepository stocks;
    @MockitoBean private DataInitializer initializer;
    @MockitoBean private QuartzConfig quartz;
    @Autowired private SecFinancialFactsDailySyncJob job;
    @Autowired private SecFinancialFactRepository facts;
    @Autowired private StockStatusRepository statuses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @Test
    void officialZipPersistsRepresentativeVersionsAndRerunIsIdempotent() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo("jdbc:postgresql://localhost:5433/trading");
        }
        List<String> ciks = List.of("0000320193", "0001652044", "0001564408", "0001326801", "0001559720", "0001577552", "0000019617", "0000789019", "0001790169", "0000797468", "0001354866", "0001402436");
        List<ActiveStockWithStatus> targets = jdbc.query("select s.figi,s.cik from stock s join stock_status ss using(figi) where s.active_status='ACTIVE' and s.stock_type in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR') and s.cik in ('0000320193','0001652044','0001564408','0001326801','0001559720','0001577552','0000019617','0000789019','0001790169','0000797468','0001354866','0001402436') order by s.figi", (row, index) -> {
            Stock stock = new Stock();
            stock.setFigi(row.getString(1));
            stock.setCik(row.getString(2));
            StockStatus status = new StockStatus();
            status.setFigi(stock.getFigi());
            return new ActiveStockWithStatus(stock, status);
        });
        assertThat(targets.stream().map(target -> target.stock().getCik()).distinct().toList()).containsExactlyInAnyOrderElementsOf(ciks);
        when(stocks.findSecFinancialFactsSyncStocks(ActiveStatus.ACTIVE)).thenReturn(targets);
        job.execute(null);
        List<SecFinancialFact> first = facts.findByCik("0000320193");
        assertThat(first).isNotEmpty().allSatisfy(fact -> {
            assertThat(fact.getAvailableFromDate()).isAfter(fact.getFiled());
            assertThat(fact.getValue()).isNotNull();
        });
        assertThat(first).extracting(SecFinancialFact::getTaxonomy).contains("dei", "us-gaap");
        assertThat(facts.findDisclosedFacts("0000320193", LocalDate.parse("2026-11-03")))
                .noneSatisfy(fact -> assertThat(fact.getFiled()).isEqualTo(LocalDate.parse("2026-11-03")));
        long total = facts.count();
        java.util.Map<Long, java.time.Instant> firstObserved = first.stream().collect(java.util.stream.Collectors.toMap(SecFinancialFact::getId, SecFinancialFact::getObservedAt));
        job.execute(null);
        assertThat(facts.count()).isEqualTo(total);
        assertThat(facts.findByCik("0000320193").stream().collect(java.util.stream.Collectors.toMap(SecFinancialFact::getId, SecFinancialFact::getObservedAt))).isEqualTo(firstObserved);
        assertThat(statuses.findAllById(targets.stream().map(target -> target.stock().getFigi()).toList()))
                .hasSize(targets.size()).allSatisfy(status -> assertThat(status.getSecFinancialFactsDate()).isNotNull());
        System.out.println("SEC_FINANCIAL_DEV_ACCEPTANCE ciks=" + ciks.size() + " totalFacts=" + total + " appleFacts=" + first.size() + " rerun=UNCHANGED");
    }
}
