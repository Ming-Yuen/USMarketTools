package com.ykm.stock.analytics.service.market;

import com.ykm.stock.analytics.converter.jpa.TreasuryParYieldDailyConverter;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TreasuryServiceTests {
    /** Scenario：只保存2Y/10Y與真實日期，重跑不產生額外觀測副本或改寫既有觀測時間。 */
    @Test
    void storesTypedYieldAndPreservesObservationWhenValuesUnchanged() {
        TreasuryParYieldDailyRepository repo = mock(TreasuryParYieldDailyRepository.class);
        TreasuryService service = new TreasuryService(repo, Mappers.getMapper(TreasuryParYieldDailyConverter.class));
        Instant seen = Instant.parse("2026-10-16T23:00:00Z");
        TreasuryParYieldDaily stored = TreasuryParYieldDaily.builder().observationDate(LocalDate.of(2026, 10, 16))
                .twoYear(new BigDecimal("3.90")).tenYear(new BigDecimal("4.25")).observedAt(seen).build();
        when(repo.findAllById(any())).thenReturn(List.of(stored));

        service.saveOrUpdateTreasuryParYieldDailies(feed("4.25"));

        assertThat(stored.getObservedAt()).isEqualTo(seen);
        assertThat(stored.getSourceUpdatedAtUtc()).isEqualTo(LocalDateTime.of(2026, 10, 16, 22, 0));
        assertThat(stored.getTwoYear()).isEqualByComparingTo("3.90");
        verify(repo).saveAll(any());
        service.saveOrUpdateTreasuryParYieldDailies(feed("4.30"));
        assertThat(stored.getTenYear()).isEqualByComparingTo("4.30");
        assertThat(stored.getObservedAt()).isAfter(seen);
    }

    private TreasuryYieldCurveFeed feed(String tenYear) {
        TreasuryYieldCurveFeed.Properties properties = new TreasuryYieldCurveFeed.Properties();
        properties.setDate(LocalDateTime.of(2026, 10, 16, 0, 0));
        properties.setTwoYear(new BigDecimal("3.90"));
        properties.setTenYear(new BigDecimal(tenYear));
        TreasuryYieldCurveFeed.Content content = new TreasuryYieldCurveFeed.Content();
        content.setProperties(properties);
        TreasuryYieldCurveFeed.Entry entry = new TreasuryYieldCurveFeed.Entry();
        entry.setContent(content);
        entry.setUpdated("2026-10-16T18:00:00-04:00");
        TreasuryYieldCurveFeed feed = new TreasuryYieldCurveFeed();
        feed.setEntries(List.of(entry));
        return feed;
    }
}
