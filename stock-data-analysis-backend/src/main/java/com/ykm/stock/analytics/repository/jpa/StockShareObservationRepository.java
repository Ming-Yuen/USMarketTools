package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockShareObservation;
import com.ykm.stock.analytics.model.entity.key.StockShareObservationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockShareObservationRepository extends JpaRepository<StockShareObservation, StockShareObservationId> {
    Optional<StockShareObservation> findFirstByFigiAndSourceAndObservedAtLessThanEqualOrderByObservedAtDesc(
            String figi, String source, Instant cutoff);

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
