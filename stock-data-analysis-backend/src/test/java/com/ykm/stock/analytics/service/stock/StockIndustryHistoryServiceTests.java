package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.model.entity.StockIndustry;
import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StockIndustryHistoryServiceTests {
    private final StockIndustryHistoryRepository histories = mock(StockIndustryHistoryRepository.class);
    private final StockIndustryHistoryService service = new StockIndustryHistoryService(histories);
    private final List<List<StockIndustryHistory>> writes = new ArrayList<>();

    private StockIndustry industry(String code) {
        StockIndustry value = new StockIndustry();
        value.setCode(code);
        return value;
    }

    private StockIndustryHistory observed(String figi, StockIndustry classification, Instant from) {
        StockIndustryHistory record = new StockIndustryHistory();
        record.setFigi(figi);
        record.setIndustry(classification);
        record.setObservedFrom(from);
        return record;
    }

    private void captureWrites() {
        when(histories.saveAllAndFlush(any())).thenAnswer(invocation -> {
            Iterable<StockIndustryHistory> entities = invocation.getArgument(0);
            List<StockIndustryHistory> batch = new ArrayList<>();
            entities.forEach(batch::add);
            writes.add(batch);
            return batch;
        });
    }

    @Test
    void firstObservationCreatesOneOpenPeriod() {
        captureWrites();
        StockIndustry selected = industry("I1");

        assertThat(service.assignIndustries(Map.of("FIGI", selected))).isEqualTo(1);

        StockIndustryHistory inserted = writes.getFirst().getFirst();
        assertThat(inserted.getFigi()).isEqualTo("FIGI");
        assertThat(inserted.getIndustry()).isSameAs(selected);
        assertThat(inserted.getObservedFrom()).isNotNull();
        assertThat(inserted.getObservedTo()).isNull();
        verify(histories).findCurrentByFigis(java.util.Set.of("FIGI"));
        verify(histories, times(1)).saveAllAndFlush(any());
    }

    @Test
    void sameIndustryIsIdempotentAndDoesNotCreateAnotherPeriod() {
        StockIndustry selected = industry("I1");
        StockIndustryHistory current = observed("FIGI", selected, Instant.parse("2026-10-01T00:00:00Z"));
        when(histories.findCurrentByFigis(any())).thenReturn(List.of(current));

        assertThat(service.assignIndustries(Map.of("FIGI", selected))).isZero();
        assertThat(current.getObservedTo()).isNull();
        verify(histories, never()).saveAllAndFlush(any());
    }

    @Test
    void changedIndustryClosesOldPeriodAndOpensNonOverlappingNewPeriod() {
        captureWrites();
        StockIndustryHistory old = observed("FIGI", industry("I1"), Instant.parse("2026-10-01T00:00:00Z"));
        StockIndustry replacement = industry("I2");
        when(histories.findCurrentByFigis(any())).thenReturn(List.of(old));

        assertThat(service.assignIndustries(Map.of("FIGI", replacement))).isEqualTo(1);

        assertThat(writes).hasSize(2);
        StockIndustryHistory next = writes.get(1).getFirst();
        assertThat(old.getObservedTo()).isEqualTo(next.getObservedFrom());
        assertThat(old.getObservedFrom()).isBefore(old.getObservedTo());
        assertThat(next.getIndustry()).isSameAs(replacement);
        assertThat(next.getObservedTo()).isNull();
    }

    @Test
    void nonMonotonicObservationFailsInsteadOfOverlappingHistory() {
        StockIndustryHistory future = observed("FIGI", industry("I1"), Instant.now().plusSeconds(3600));
        when(histories.findCurrentByFigis(any())).thenReturn(List.of(future));

        assertThatThrownBy(() -> service.assignIndustries(Map.of("FIGI", industry("I2"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INDUSTRY_HISTORY_OBSERVATION_NOT_AFTER_PREVIOUS");
        verify(histories, never()).saveAllAndFlush(any());
    }

    @Test
    void emptyInputDoesNotQueryDatabase() {
        assertThat(service.assignIndustries(Map.of())).isZero();
        verifyNoInteractions(histories);
    }
}
