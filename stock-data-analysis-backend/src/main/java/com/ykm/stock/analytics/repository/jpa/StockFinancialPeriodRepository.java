package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockFinancialPeriod;
import com.ykm.stock.analytics.model.entity.key.StockFinancialPeriodId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface StockFinancialPeriodRepository extends JpaRepository<StockFinancialPeriod, StockFinancialPeriodId> {
    List<StockFinancialPeriod> findByFigiOrderByPeriodEndAsc(String figi);

    List<StockFinancialPeriod> findByFigiInOrderByFigiAscPeriodEndAsc(Collection<String> figis);

    @Query("select distinct p.figi from StockFinancialPeriod p where p.figi in :figis")
    Set<String> findExistingFigis(@Param("figis") Collection<String> figis);

    @Modifying
    @Query("delete from StockFinancialPeriod p where p.figi = :figi")
    void deleteByFigi(@Param("figi") String figi);

    @Modifying
    @Query("delete from StockFinancialPeriod p where p.figi in :figis")
    void deleteByFigiIn(@Param("figis") Collection<String> figis);
}
