package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.converter.jpa.MarketSentimentDailyMapper;
import com.ykm.stock.analytics.model.dto.thirdparty.response.FearAndGreedResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class MarketSentimentAvailabilityTests {
    private final MarketSentimentDailyMapper mapper = new MarketSentimentDailyMapper();
    private static final Instant OBSERVED = Instant.parse("2026-10-16T01:00:00Z");

    // Scenario：來源無時間不得填今天；新typed mapper直接拒絕無可用來源時間的批次。
    @Test
    void missingProviderTimestampDoesNotBecomeToday() {
        assertThatThrownBy(() -> mapper.toDailyRows(new FearAndGreedResponse(), OBSERVED))
                .hasMessage("MARKET_SENTIMENT_TIMESTAMP_REQUIRED");
    }

    @Test
    void explicitProviderTimestampDefinesDateAndObservedTimeRemainsSeparate() {
        FearAndGreedResponse response = new FearAndGreedResponse();
        FearAndGreedResponse.SentimentSnapshot snapshot = new FearAndGreedResponse.SentimentSnapshot();
        snapshot.setScore(new BigDecimal("42"));
        snapshot.setTimestamp("2026-10-15T20:00:00Z");
        response.setFearAndGreed(snapshot);

        assertThat(mapper.toDailyRows(response, OBSERVED)).singleElement().satisfies(row -> {
            assertThat(row.getMarketDate()).isEqualTo(LocalDate.of(2026, 10, 15));
            assertThat(row.getFearAndGreed().getCapturedAt()).isEqualTo(Instant.parse("2026-10-15T20:00:00Z"));
            assertThat(row.getFearAndGreed().getObservedAt()).isEqualTo(OBSERVED);
        });
    }
}
