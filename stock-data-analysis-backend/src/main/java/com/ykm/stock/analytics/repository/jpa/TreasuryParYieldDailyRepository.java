package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.TreasuryParYieldDaily;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface TreasuryParYieldDailyRepository extends JpaRepository<TreasuryParYieldDaily, LocalDate> {
    TreasuryParYieldDaily findFirstByOrderByObservationDateDesc();
    List<TreasuryParYieldDaily> findTop2ByOrderByObservationDateDesc();
    List<TreasuryParYieldDaily> findByObservationDateBetweenOrderByObservationDateAsc(LocalDate from, LocalDate through);
}
