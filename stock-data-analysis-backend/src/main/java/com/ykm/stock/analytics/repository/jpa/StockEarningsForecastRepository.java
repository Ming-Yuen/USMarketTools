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
            where f.figi = :figi
              and f.observationDate <= :maxObservationDate and f.observedAt <= :cutoff
              and not exists (select newer from StockEarningsForecast newer
                  where newer.figi = f.figi and newer.source = f.source
                    and newer.fiscalPeriod = f.fiscalPeriod and newer.periodType = f.periodType
                    and newer.observationDate <= :maxObservationDate and newer.observedAt <= :cutoff
                    and (newer.observationDate > f.observationDate
                         or (newer.observationDate = f.observationDate and newer.observedAt > f.observedAt)))
            order by f.periodEnd desc nulls last, f.fiscalPeriod desc, f.periodType, f.source
            """)
    List<StockEarningsForecast> findLatestForStock(@Param("figi") String figi,
                                                   @Param("maxObservationDate") LocalDate maxObservationDate,
                                                   @Param("cutoff") Instant cutoff);

    @Query("""
            select f from StockEarningsForecast f
            where f.figi = :figi and f.source = :source
              and f.fiscalPeriod = :fiscalPeriod and f.periodType = :periodType
              and f.observationDate between :fromDate and :throughDate
              and f.observedAt <= :cutoff
            order by f.observationDate asc, f.observedAt desc
            """)
    List<StockEarningsForecast> findHistoryForPeriod(@Param("figi") String figi,
                                                     @Param("source") String source,
                                                     @Param("fiscalPeriod") String fiscalPeriod,
                                                     @Param("periodType") String periodType,
                                                     @Param("fromDate") LocalDate fromDate,
                                                     @Param("throughDate") LocalDate throughDate,
                                                     @Param("cutoff") Instant cutoff);

    boolean existsByFigiAndSourceAndPeriodTypeAndObservationDateGreaterThanEqual(
            String figi, String source, String periodType, LocalDate minObservationDate);

    /** 批次讀取多檔候選股自指定觀察日起之可見盈利預期紀錄（純唯讀 PostgreSQL，供雷達批次前瞻與 90D 下修判定）。 */
    @Query("""
            select f from StockEarningsForecast f
            where f.figi in :figis
              and f.observationDate between :minObservationDate and :maxObservationDate
              and f.observedAt <= :cutoff
            order by f.figi asc, f.observationDate asc, f.observedAt desc
            """)
    List<StockEarningsForecast> findVisibleForStocksBetween(@Param("figis") java.util.Collection<String> figis,
                                                            @Param("minObservationDate") LocalDate minObservationDate,
                                                            @Param("maxObservationDate") LocalDate maxObservationDate,
                                                            @Param("cutoff") Instant cutoff);
}
