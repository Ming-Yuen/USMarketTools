package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.MacroCreditSpreadDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MacroCreditSpreadDailyRepository extends JpaRepository<MacroCreditSpreadDaily, LocalDate> {

    List<MacroCreditSpreadDaily> findByObservationDateBetweenOrderByObservationDateAsc(LocalDate from, LocalDate through);

    Optional<MacroCreditSpreadDaily> findTopByOrderByObservationDateDesc();

    List<MacroCreditSpreadDaily> findTop30ByObservationDateLessThanEqualOrderByObservationDateDesc(LocalDate through);
}
