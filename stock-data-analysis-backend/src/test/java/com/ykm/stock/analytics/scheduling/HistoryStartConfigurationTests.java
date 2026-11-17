package com.ykm.stock.analytics.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;

class HistoryStartConfigurationTests {
    @Test
    void quoteMayStartLaterWithoutMovingSplitPreparation() {
        checkDates(Map.of("history.start-date", "2019-01-01", "market-history.start-date", "2022-01-01"),
                "2022-01-01", "2016-01-01");
    }

    @Test
    void absentMarketOverrideDefaultsTo2016IndependentOfGenericHistory() {
        checkDates(Map.of("history.start-date", "2020-01-01"), "2016-01-01", "2016-01-01");
    }

    @Test
    void devRetainsSourceHistoryAndStartsMarketHistoryIn2016() throws Exception {
        Properties properties = new Properties();
        try (java.io.InputStream input = getClass().getResourceAsStream("/application-dev.properties")) {
            properties.load(input);
        }
        assertThat(properties.getProperty("history.start-date")).isEqualTo("2019-01-01");
        assertThat(properties.getProperty("market-history.start-date")).isEqualTo("2016-01-01");
    }

    @Test
    void splitConfigurationStartsIn2016AndAllowsExplicitOverride() throws Exception {
        Properties properties = new Properties();
        try (java.io.InputStream input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        assertThat(properties.getProperty("market-history.start-date")).isEqualTo("2016-01-01");
        assertThat(properties.getProperty("split.history-start-date")).isEqualTo("2016-01-01");
        checkDates(Map.of("history.start-date", "2022-01-01", "split.history-start-date", "2018-01-01"),
                "2016-01-01", "2018-01-01");
    }

    private void checkDates(Map<String, Object> properties, String marketStart, String sourceStart) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.getBeanFactory().addEmbeddedValueResolver(context.getEnvironment()::resolveRequiredPlaceholders);
            DefaultConversionService conversion = new DefaultConversionService();
            conversion.addConverter(String.class, LocalDate.class, LocalDate::parse);
            context.getBeanFactory().setConversionService(conversion);
            context.registerBean(StockQuoteDailyTask.class, () -> new StockQuoteDailyTask(null, null, null, null, null, null));
            context.registerBean(StockSplitDailyTask.class, () -> new StockSplitDailyTask(null, null, null, null, null, null, null, null));
            context.refresh();
            assertThat(ReflectionTestUtils.getField(context.getBean(StockQuoteDailyTask.class), "historyStartDate")).isEqualTo(LocalDate.parse(marketStart));
            assertThat(ReflectionTestUtils.getField(context.getBean(StockSplitDailyTask.class), "historyStartDate")).isEqualTo(LocalDate.parse(sourceStart));
        }
    }
}
