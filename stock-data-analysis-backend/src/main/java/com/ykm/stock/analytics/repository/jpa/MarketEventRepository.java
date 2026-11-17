package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.MarketEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface MarketEventRepository extends JpaRepository<MarketEvent, String> {
    List<MarketEvent> findByEventDateBetweenOrderByEventDateAscOccurrenceAtAsc(LocalDate from, LocalDate through);

    @Query("""
            select distinct e.figi from MarketEvent e
            where e.eventType = 'EARNINGS' and e.status <> 'CANCELLED'
              and e.figi is not null and e.eventDate between :from and :through
            """)
    Set<String> findEarningsFigisBetween(@Param("from") LocalDate from, @Param("through") LocalDate through);

    @Query("""
            select e from MarketEvent e where e.eventDate between :from and :through
              and (e.figi is null or e.figi = :figi) and e.observedAt <= :cutoff
              and (e.sourcePublishedAt is null or e.sourcePublishedAt <= :cutoff)
              and e.status <> 'CANCELLED'
            order by e.eventDate, e.occurrenceAt
            """)
    List<MarketEvent> findVisible(@Param("figi") String figi, @Param("from") LocalDate from,
                                  @Param("through") LocalDate through, @Param("cutoff") Instant cutoff);
}
