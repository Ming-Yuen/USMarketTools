package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SectorMoneyFlowDaily;
import com.ykm.stock.analytics.model.entity.key.SectorMoneyFlowDailyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SectorMoneyFlowDailyRepository extends JpaRepository<SectorMoneyFlowDaily, SectorMoneyFlowDailyId> {
    @Query("select max(s.tradeDate) from SectorMoneyFlowDaily s")
    Optional<LocalDate> findLatestTradeDate();

    @Query("select max(s.tradeDate) from SectorMoneyFlowDaily s where s.tradeDate <= :asOfDate")
    Optional<LocalDate> findLatestTradeDateAtOrBefore(@Param("asOfDate") LocalDate asOfDate);

    List<SectorMoneyFlowDaily> findByTradeDateOrderByCmf20dDescDollarVolume1dDesc(LocalDate tradeDate);

    Optional<SectorMoneyFlowDaily> findByTradeDateAndIndustryCode(LocalDate tradeDate, String industryCode);

    @Modifying
    @Query("delete from SectorMoneyFlowDaily s where s.tradeDate = :tradeDate")
    void deleteByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}
