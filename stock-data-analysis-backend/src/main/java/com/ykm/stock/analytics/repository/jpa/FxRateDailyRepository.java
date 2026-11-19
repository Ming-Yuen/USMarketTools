package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.FxRateDaily;
import com.ykm.stock.analytics.model.entity.key.FxRateDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FxRateDailyRepository extends JpaRepository<FxRateDaily, FxRateDailyId> {
    Optional<FxRateDaily> findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String currency, LocalDate rateDate);

    @Query("""
            select f from FxRateDaily f
            where f.currency in :currencies
              and f.rateDate >= :fromDate
              and f.rateDate <= :throughDate
            order by f.currency asc, f.rateDate asc
            """)
    List<FxRateDaily> findByCurrenciesBetween(@Param("currencies") Collection<String> currencies,
                                              @Param("fromDate") LocalDate fromDate,
                                              @Param("throughDate") LocalDate throughDate);

    @Query("""
            select f from FxRateDaily f
            where f.currency in :currencies
              and f.rateDate = (
                  select max(f2.rateDate) from FxRateDaily f2
                  where f2.currency = f.currency and f2.rateDate < :beforeDate
              )
            """)
    List<FxRateDaily> findLatestBeforeByCurrencies(@Param("currencies") Collection<String> currencies,
                                                   @Param("beforeDate") LocalDate beforeDate);

    @Query("select max(f.rateDate) from FxRateDaily f where f.currency = :currency")
    Optional<LocalDate> findLatestRateDate(@Param("currency") String currency);
}
