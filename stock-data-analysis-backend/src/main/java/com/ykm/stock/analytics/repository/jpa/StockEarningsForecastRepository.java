package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockEarningsForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StockEarningsForecastRepository extends JpaRepository<StockEarningsForecast, StockEarningsForecast.Key> {
    @Query("""
            select f from StockEarningsForecast f
            where f.figi = :figi and f.observedAt <= :cutoff
              and not exists (select newer from StockEarningsForecast newer
                  where newer.figi = f.figi and newer.fiscalPeriod = f.fiscalPeriod and newer.periodType = f.periodType
                    and newer.observedAt <= :cutoff and newer.observedAt > f.observedAt)
            order by f.periodEnd desc nulls last, f.fiscalPeriod desc, f.periodType
            """)
    List<StockEarningsForecast> findLatestForStock(@Param("figi") String figi, @Param("cutoff") Instant cutoff);
}
