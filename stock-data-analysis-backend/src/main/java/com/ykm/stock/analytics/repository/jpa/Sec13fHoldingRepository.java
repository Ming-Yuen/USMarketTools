package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.Sec13fHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface Sec13fHoldingRepository extends JpaRepository<Sec13fHolding, Long> {

    List<Sec13fHolding> findByFigiOrderByReportPeriodDescMarketValueUsdDesc(String figi);

    List<Sec13fHolding> findByFigiAndReportPeriodLessThanEqualOrderByReportPeriodDescMarketValueUsdDesc(String figi, LocalDate reportPeriod);

    List<Sec13fHolding> findByManagerCikAndReportPeriod(String managerCik, LocalDate reportPeriod);

    @Query("select distinct h.reportPeriod from Sec13fHolding h where h.managerCik = :managerCik and h.filingDate <= :asOfDate order by h.reportPeriod desc")
    List<LocalDate> findDistinctReportPeriodsByManagerCikVisibleAt(@Param("managerCik") String managerCik, @Param("asOfDate") LocalDate asOfDate);

    boolean existsByManagerCikAndReportPeriodAndCusipAndShareTypeAndPutCall(
            String managerCik, LocalDate reportPeriod, String cusip, String shareType, String putCall);

    @Modifying
    @Query("delete from Sec13fHolding h where h.managerCik = :managerCik and h.reportPeriod = :reportPeriod")
    void deleteByManagerCikAndReportPeriod(@Param("managerCik") String managerCik, @Param("reportPeriod") LocalDate reportPeriod);
}
