package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockShortInterest;
import com.ykm.stock.analytics.model.entity.key.StockShortInterestId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockShortInterestRepository extends JpaRepository<StockShortInterest, StockShortInterestId> {

    Optional<StockShortInterest> findTopByFigiAndSettlementDateLessThanEqualOrderBySettlementDateDesc(String figi, LocalDate date);

    List<StockShortInterest> findTop6ByFigiAndSettlementDateLessThanEqualOrderBySettlementDateDesc(String figi, LocalDate date);

    @Query("select max(s.settlementDate) from StockShortInterest s")
    Optional<LocalDate> findLatestSettlementDate();

    @Query("select count(s) from StockShortInterest s where s.settlementDate = :settlementDate")
    long countBySettlementDate(@Param("settlementDate") LocalDate settlementDate);

    @Query("""
            select s from StockShortInterest s
            where s.figi in :figis
              and s.settlementDate = (
                  select max(s2.settlementDate) from StockShortInterest s2
                  where s2.figi = s.figi and s2.settlementDate <= :asOfDate
              )
            """)
    List<StockShortInterest> findLatestAtOrBeforeByFigiIn(@Param("figis") Collection<String> figis,
                                                         @Param("asOfDate") LocalDate asOfDate);
}
