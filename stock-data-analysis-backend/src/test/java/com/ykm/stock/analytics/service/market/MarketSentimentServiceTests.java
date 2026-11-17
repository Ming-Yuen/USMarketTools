package com.ykm.stock.analytics.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.stock.analytics.client.CnnFearAndGreedClient;
import com.ykm.stock.analytics.converter.jpa.MarketSentimentDailyMapper;
import com.ykm.stock.analytics.model.dto.thirdparty.response.FearAndGreedResponse;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily;
import com.ykm.stock.analytics.model.entity.MarketSentimentDaily.SentimentMetric;
import com.ykm.stock.analytics.repository.jpa.MarketSentimentDailyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketSentimentServiceTests {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 15);
    private static final Instant SOURCE_AT = Instant.parse("2026-10-15T20:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2026-10-16T01:00:00Z");
    private static final String SOURCE = """
            {"fear_and_greed":{"score":42.5,"rating":"fear","timestamp":"2026-10-15T20:00:00Z"}}
            """;
    private final CnnFearAndGreedClient client = mock(CnnFearAndGreedClient.class);
    private final MarketSentimentDailyRepository repository = mock(MarketSentimentDailyRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MarketSentimentDailyMapper mapper = new MarketSentimentDailyMapper();
    private final MarketSentimentService service = new MarketSentimentService(objectMapper, mapper, repository, client);

    // Scenario：原先 FearAndGreed 的 And 被當作衍生查詢關鍵字，啟動失敗；現在必須使用明確 SQL，排除只有 VIX 的列。
    @Test
    void latestSnapshotUsesDeclaredQueryAndReturnsStoredCnnWithoutDownloading() throws Exception {
        Query query = MarketSentimentDailyRepository.class.getMethod("findLatestWithFearAndGreed").getAnnotation(Query.class);
        assertThat(query).as("使用明確查詢，不能再次從 FearAndGreed 方法名稱推導").isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT))
                .contains("from market_sentiment_daily", "where fear_and_greed_score is not null", "order by market_date desc")
                .endsWith("limit 1");
        MarketSentimentDaily latest = cnnRow("42.5", OBSERVED);
        when(repository.findLatestWithFearAndGreed()).thenReturn(Optional.of(latest));

        assertThat(service.getLatestSnapshot()).containsSame(latest);

        verify(repository).findLatestWithFearAndGreed();
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(client);
    }

    // Scenario：沒有已儲存 CNN 分數時維持空結果，不以其他指標或即時外部下載補成一筆快照。
    @Test
    void missingLatestCnnSnapshotRemainsEmptyWithoutDownloadingOrWriting() {
        when(repository.findLatestWithFearAndGreed()).thenReturn(Optional.empty());

        assertThat(service.getLatestSnapshot()).isEmpty();

        verify(repository).findLatestWithFearAndGreed();
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(client);
    }

    // Scenario：原先保存raw+daily+通用observation，現在下載只回傳typed列，保存只寫daily表。
    @Test
    void downloadValidatesProviderDateWithoutWriting() {
        when(client.fetchRawJson()).thenReturn(SOURCE);

        List<MarketSentimentDaily> rows = service.downloadDailySnapshot();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getMarketDate()).isEqualTo(DATE);
            assertThat(row.getFearAndGreed().getScore()).isEqualByComparingTo("42.5");
            assertThat(row.getFearAndGreed().getCapturedAt()).isEqualTo(SOURCE_AT);
            assertThat(row.getVixClose()).isNull();
        });
        verifyNoInteractions(repository);
    }

    @Test
    void missingProviderDateFailsBeforeSaving() {
        when(client.fetchRawJson()).thenReturn("{}");

        assertThatThrownBy(service::downloadDailySnapshot).hasRootCauseMessage("MARKET_SENTIMENT_TIMESTAMP_REQUIRED");
        verifyNoInteractions(repository);
    }

    @Test
    void sourceFailureKeepsOriginalCauseWithoutSaving() {
        IllegalStateException failure = new IllegalStateException("CNN_DOWN");
        when(client.fetchRawJson()).thenThrow(failure);

        assertThatThrownBy(service::downloadDailySnapshot).hasCause(failure);
        verifyNoInteractions(repository);
    }

    @Test
    void metricsWithDifferentSourceDatesDoNotGetAssignedToTheOverallIndexDate() throws Exception {
        long earlierTimestamp = SOURCE_AT.minusSeconds(86400).toEpochMilli();
        FearAndGreedResponse response = objectMapper.readValue(
                "{\"fear_and_greed\":{\"score\":42.5,\"timestamp\":\"2026-10-15T20:00:00Z\"},"
                        + "\"stock_price_breadth\":{\"score\":33,\"timestamp\":" + earlierTimestamp + "}}",
                FearAndGreedResponse.class);

        List<MarketSentimentDaily> rows = mapper.toDailyRows(response, OBSERVED);

        assertThat(rows).hasSize(2);
        MarketSentimentDaily breadth = rows.stream().filter(row -> row.getStockPriceBreadth() != null).findFirst().orElseThrow();
        assertThat(breadth.getMarketDate()).isEqualTo(DATE.minusDays(1));
        assertThat(breadth.getFearAndGreed()).isNull();
    }

    @Test
    void sourceTimestampAfterObservationIsRejected() throws Exception {
        FearAndGreedResponse response = objectMapper.readValue(SOURCE, FearAndGreedResponse.class);

        assertThatThrownBy(() -> mapper.toDailyRows(response, SOURCE_AT.minusSeconds(1)))
                .hasMessage("CNN_SOURCE_TIME_IN_FUTURE");
    }

    @Test
    void unchangedCnnMetricPreservesItsFirstObservationAndExistingVix() {
        MarketSentimentDaily existing = cnnRow("42.5", OBSERVED);
        existing.setVixClose(new BigDecimal("24.75"));
        existing.setVixObservedAt(OBSERVED);
        existing.setVixSource("YAHOO_FINANCE_CBOE_VIX");
        when(repository.findAllById(List.of(DATE))).thenReturn(List.of(existing));

        service.save(List.of(cnnRow("42.500", OBSERVED.plusSeconds(3600))));

        assertThat(existing.getFearAndGreed().getObservedAt()).isEqualTo(OBSERVED);
        assertThat(existing.getVixClose()).isEqualByComparingTo("24.75");
        assertThat(existing.getVixObservedAt()).isEqualTo(OBSERVED);
        verify(repository).saveAllAndFlush(any());
    }

    @Test
    void changedCnnMetricUsesNewObservationButVixUpdateCannotOverwriteCnn() {
        MarketSentimentDaily existing = cnnRow("42.5", OBSERVED);
        when(repository.findAllById(List.of(DATE))).thenReturn(List.of(existing));
        Instant changedAt = OBSERVED.plusSeconds(3600);

        service.save(List.of(cnnRow("43", changedAt)));
        MarketSentimentDaily vix = new MarketSentimentDaily();
        vix.setMarketDate(DATE);
        vix.setVixClose(new BigDecimal("25"));
        vix.setVixObservedAt(changedAt);
        vix.setVixSource("YAHOO_FINANCE_CBOE_VIX");
        service.save(List.of(vix));

        assertThat(existing.getFearAndGreed().getScore()).isEqualByComparingTo("43");
        assertThat(existing.getFearAndGreed().getObservedAt()).isEqualTo(changedAt);
        assertThat(existing.getVixClose()).isEqualByComparingTo("25");
        assertThat(existing.getVixSourceAt()).isNull();
    }

    private MarketSentimentDaily cnnRow(String score, Instant observedAt) {
        MarketSentimentDaily row = new MarketSentimentDaily();
        row.setMarketDate(DATE);
        row.setCnnSource("CNN_FEAR_AND_GREED");
        row.setFearAndGreed(new SentimentMetric(new BigDecimal(score), SOURCE_AT, observedAt));
        return row;
    }
}
