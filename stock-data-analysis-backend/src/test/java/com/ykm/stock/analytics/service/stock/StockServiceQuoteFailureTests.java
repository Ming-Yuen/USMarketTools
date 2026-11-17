package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.converter.jpa.StockClassificationConverter;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.repository.jpa.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockServiceQuoteFailureTests {
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockService service = new StockService(mock(StockRepository.class), statuses,
            mock(StockIndustryRepository.class), mock(StockClassificationConverter.class), mock(StockConceptRepository.class), mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class));

    @Test
    void locksThenUpdatesOnlyErrorWithoutMovingDates() {
        StockStatus status = new StockStatus();
        status.setFigi("F1");
        status.setQuoteDate(LocalDate.of(2026, 10, 1));
        status.setSplitDate(LocalDate.of(2026, 10, 3));
        StockStatus before = new StockStatus();
        org.springframework.beans.BeanUtils.copyProperties(status, before);
        Map<String, String> errors = Map.of("F1", "INVALID_ALPACA_OHLCV");
        when(statuses.findAllByIdForUpdate(errors.keySet())).thenReturn(List.of(status));

        service.recordQuoteFailures(errors);

        assertThat(status.getQuoteMessage()).isEqualTo("INVALID_ALPACA_OHLCV");
        assertThat(status).usingRecursiveComparison().ignoringFields("quoteMessage").isEqualTo(before);
        InOrder order = inOrder(statuses);
        order.verify(statuses).findAllByIdForUpdate(errors.keySet());
        order.verify(statuses).saveAll(List.of(status));
        verifyNoMoreInteractions(statuses);
    }

    @Test
    void missingStatusDoesNotWritePartialErrors() {
        Map<String, String> errors = Map.of("F1", "INVALID_ALPACA_OHLCV");
        when(statuses.findAllByIdForUpdate(errors.keySet())).thenReturn(List.of());
        assertThatThrownBy(() -> service.recordQuoteFailures(errors)).hasMessage("QUOTE_STOCK_STATUS_MISSING");
        verify(statuses, never()).saveAll(any());
    }

    @Test
    void emptyErrorsNeedNoDatabaseWork() {
        service.recordQuoteFailures(Map.of());
        verifyNoInteractions(statuses);
    }
}