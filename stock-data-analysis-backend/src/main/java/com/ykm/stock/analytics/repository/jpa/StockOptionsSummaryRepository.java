package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockOptionsSummary;
import com.ykm.stock.analytics.model.entity.key.StockOptionsSummaryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockOptionsSummaryRepository extends JpaRepository<StockOptionsSummary, StockOptionsSummaryId> {

    Optional<StockOptionsSummary> findTopByFigiAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(String figi, LocalDate date);

    List<StockOptionsSummary> findByFigiAndSnapshotDateBetweenOrderBySnapshotDateAsc(String figi, LocalDate from, LocalDate through);

    @Query("""
            select s.figi from StockOptionsSummary s
            where s.figi in :figis
              and s.snapshotDate = :snapshotDate
              and s.status in ('AVAILABLE', 'NOT_APPLICABLE')
              and s.observedAt >= :sessionCloseAt
            """)
    List<String> findSettledFigisForSession(@Param("figis") Collection<String> figis,
                                            @Param("snapshotDate") LocalDate snapshotDate,
                                            @Param("sessionCloseAt") Instant sessionCloseAt);

    @Query("""
            select s from StockOptionsSummary s
            where s.figi in :figis
              and s.snapshotDate = (
                  select max(s2.snapshotDate) from StockOptionsSummary s2
                  where s2.figi = s.figi and s2.snapshotDate <= :asOfDate
              )
            """)
    List<StockOptionsSummary> findLatestAtOrBeforeByFigiIn(@Param("figis") Collection<String> figis,
                                                           @Param("asOfDate") LocalDate asOfDate);
}
