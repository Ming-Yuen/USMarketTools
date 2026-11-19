package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.DailyClosingBriefing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyClosingBriefingRepository extends JpaRepository<DailyClosingBriefing, LocalDate> {
    Optional<DailyClosingBriefing> findTopByOrderByTradeDateDesc();

    Optional<DailyClosingBriefing> findTopByTradeDateBeforeOrderByTradeDateDesc(LocalDate tradeDate);
}
