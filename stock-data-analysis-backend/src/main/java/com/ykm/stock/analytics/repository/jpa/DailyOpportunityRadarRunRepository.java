package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.DailyOpportunityRadarRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DailyOpportunityRadarRunRepository extends JpaRepository<DailyOpportunityRadarRun, java.time.LocalDate> {
    Optional<DailyOpportunityRadarRun> findTopByOrderByRadarDateDesc();
}
