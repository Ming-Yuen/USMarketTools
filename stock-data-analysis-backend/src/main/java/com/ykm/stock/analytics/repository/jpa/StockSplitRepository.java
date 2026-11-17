package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StockSplitRepository extends JpaRepository<StockSplit, String> {
    List<StockSplit> findByFigiOrderByExecutionDateAsc(String figi);
    List<StockSplit> findByFigiInOrderByExecutionDateAsc(Collection<String> figis);

    List<StockSplit> findByFigiAndExecutionDateGreaterThanAndExecutionDateLessThanEqualOrderByExecutionDateAsc(
            String figi, LocalDate fromExclusive, LocalDate throughInclusive);

    List<StockSplit> findByFigiAndExecutionDateBetween(String figi, LocalDate from, LocalDate through);
}
