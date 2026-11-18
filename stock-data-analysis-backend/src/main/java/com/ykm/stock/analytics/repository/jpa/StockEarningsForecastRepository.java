package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface StockEarningsForecastRepository extends JpaRepository<StockEarningsForecast, StockEarningsForecast.Key> {
    @Query("""
            select f from StockEarningsForecast f
            where f.figi = :figi and f.source = 'FUTU'
              and f.observationDate <= :valuationDate and f.observedAt <= :cutoff
              and not exists (select newer from StockEarningsForecast newer
                  where newer.figi = f.figi and newer.source = 'FUTU'
                    and newer.fiscalPeriod = f.fiscalPeriod and newer.periodType = f.periodType
                    and newer.observationDate <= :valuationDate and newer.observedAt <= :cutoff
                    and (newer.observationDate > f.observationDate
                         or (newer.observationDate = f.observationDate and newer.observedAt > f.observedAt)))
            order by f.periodEnd desc nulls last, f.fiscalPeriod desc, f.periodType
            """)
    List<StockEarningsForecast> findLatestForStock(@Param("figi") String figi,
                                                   @Param("valuationDate") LocalDate valuationDate,
                                                   @Param("cutoff") Instant cutoff);

    @Query("""
            select f from StockEarningsForecast f
            where f.figi = :figi and f.source = 'FUTU'
              and f.fiscalPeriod = :fiscalPeriod and f.periodType = :periodType
              and f.observationDate between :fromDate and :throughDate
              and f.observedAt <= :cutoff
            order by f.observationDate asc, f.observedAt desc
            """)
    List<StockEarningsForecast> findHistoryForPeriod(@Param("figi") String figi,
                                                     @Param("fiscalPeriod") String fiscalPeriod,
                                                     @Param("periodType") String periodType,
                                                     @Param("fromDate") LocalDate fromDate,
                                                     @Param("throughDate") LocalDate throughDate,
                                                     @Param("cutoff") Instant cutoff);
}
