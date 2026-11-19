package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.DailyOpportunityRadar;
import com.ykm.stock.analytics.model.entity.key.DailyOpportunityRadarId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyOpportunityRadarRepository extends JpaRepository<DailyOpportunityRadar, DailyOpportunityRadarId> {
    List<DailyOpportunityRadar> findByRadarDateOrderByTrackTypeAscDiscountToMidDescSymbolAsc(LocalDate radarDate);

    @Modifying
    @Query("delete from DailyOpportunityRadar r where r.radarDate = :radarDate")
    void deleteByRadarDate(@Param("radarDate") LocalDate radarDate);
}
