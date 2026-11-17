package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.FinancialReport;
import com.ykm.stock.analytics.model.entity.key.FinancialReportId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface FinancialReportRepository extends JpaRepository<FinancialReport, FinancialReportId> {
    /** 八份涵蓋目標季、前四季與同日年報；100 日僅為取數上界，實際財季另依期間篩選。 */
    @Query(value = """
            select r.* from (
              select f.*, row_number() over (partition by f.figi, f.statement_type
                order by f.period_end desc, f.period_type desc) as report_rank
              from financial_report f where f.figi in (:figis) and f.period_end <= cast(:day as date) + 100
                and f.period_end <= cast(cast(:knownAt as timestamp) as date) and f.observed_at <= :knownAt
            ) r where r.report_rank <= 8 order by r.figi, r.statement_type, r.period_end desc, r.period_type desc
            """, nativeQuery = true)
    List<FinancialReport> findValuationReports(@Param("figis") Collection<String> figis, @Param("day") LocalDate day,
                                              @Param("knownAt") Instant knownAt);

    List<FinancialReport> findByFigiAndPeriodEndBetween(String figi, LocalDate from, LocalDate through);

    List<FinancialReport> findByFigiOrderByPeriodEndAsc(String figi);

    List<FinancialReport> findByFigiInOrderByFigiAscPeriodEndAsc(Collection<String> figis);
}
