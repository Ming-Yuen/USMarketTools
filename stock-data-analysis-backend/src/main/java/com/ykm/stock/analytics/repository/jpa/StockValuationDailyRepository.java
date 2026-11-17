package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockValuationDaily;
import com.ykm.stock.analytics.model.entity.key.StockValuationDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockValuationDailyRepository extends JpaRepository<StockValuationDaily, StockValuationDailyId> {
    @Query(value = """
            select v.* from stock_valuation_daily v where v.figi=:figi
              and v.valuation_date=:day and v.valuation_cutoff<=:cutoff
              and (v.pe_reason is distinct from 'CLOSING_PRICE_MISSING' or exists (
                  select 1 from stock_quote q where q.figi=v.figi and q.quote_date<=v.valuation_date))
            """, nativeQuery = true)
    Optional<StockValuationDaily> findByFigiAndValuationDateAndValuationCutoffLessThanEqual(
            @Param("figi") String figi, @Param("day") LocalDate day, @Param("cutoff") Instant cutoff);

    @Query(value = """
            select v.* from stock_valuation_daily v where v.figi=:figi
              and v.valuation_date between :from and :through and v.valuation_cutoff<=:cutoff
              and (v.pe_reason is distinct from 'CLOSING_PRICE_MISSING' or v.valuation_date >= (
                  select min(q.quote_date) from stock_quote q where q.figi=v.figi))
            order by v.valuation_date
            """, nativeQuery = true)
    List<StockValuationDaily> findByFigiAndValuationDateBetweenAndValuationCutoffLessThanEqualOrderByValuationDateAsc(
            @Param("figi") String figi, @Param("from") LocalDate from, @Param("through") LocalDate through, @Param("cutoff") Instant cutoff);

    List<StockValuationDaily> findByFigiInAndValuationDate(Collection<String> figis, LocalDate day);

    @Query("""
            select distinct v.secFinancialCik from StockValuationDaily v
            where v.figi in :figis and v.valuationDate between :from and :through and v.secFinancialCik is not null
            """)
    List<String> findRecordedSecCiks(@Param("figis") Collection<String> figis, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Query("""
            select v.figi, max(v.valuationDate) from StockValuationDaily v
            where v.figi in :figis and v.valuationDate between :from and :through
              and (v.pe is not null or v.pb > 0 or v.ps is not null or v.pfcf is not null or v.evEbitda is not null)
            group by v.figi
            """)
    List<Object[]> findLatestAvailableDates(@Param("figis") Collection<String> figis, @Param("from") LocalDate from,
                                           @Param("through") LocalDate through);

}
