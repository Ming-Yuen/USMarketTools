package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.key.StockShareObservationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StockShareObservationRepository extends JpaRepository<StockShareObservation, StockShareObservationId> {
    @Query(value = """
            select s.* from stock_share_observation s
            join stock_valuation_daily v on v.figi=s.figi and v.shares_observed_at=s.observed_at
            where v.figi in (:figis) and v.valuation_date=:day and s.source='FUTU' and v.sec_share_accession is null
              and s.observed_at>v.valuation_cutoff and s.observed_at<=:calculatedAt
            """, nativeQuery = true)
    List<StockShareObservation> findPreviouslyUsedAfterClose(@Param("figis") Collection<String> figis, @Param("day") LocalDate day,
                                                              @Param("calculatedAt") Instant calculatedAt);

    @Query("""
            select s from StockShareObservation s where s.figi in :figis and s.source = 'FUTU'
            and s.observedAt = (select max(n.observedAt) from StockShareObservation n
              where n.figi = s.figi and n.source = 'FUTU' and n.observedAt <= :cutoff)
            """)
    List<StockShareObservation> findLatestAt(@Param("figis") Collection<String> figis, @Param("cutoff") Instant cutoff);

    @Query("""
            select s from StockShareObservation s where s.figi in :figis and s.source = 'FUTU'
            and s.observedAt = (select max(n.observedAt) from StockShareObservation n
              where n.figi = s.figi and n.source = 'FUTU' and n.observedAt <= :observedThrough
              and n.observedAt >= :close
              and n.snapshotQuoteTime between :windowStart and :close
              and n.snapshotPrice > 0 and n.snapshotMarketCap > 0)
            """)
    List<StockShareObservation> findLatestClosingSnapshotAt(@Param("figis") Collection<String> figis,
                                                           @Param("windowStart") Instant windowStart,
                                                           @Param("close") Instant close,
                                                           @Param("observedThrough") Instant observedThrough);
}
