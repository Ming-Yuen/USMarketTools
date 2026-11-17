package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StockValuationLatestRepository extends JpaRepository<StockValuationLatest, String> {
    List<StockValuationLatest> findByFigiIn(Collection<String> figis);

    /** 讀取同產業有效且持續追蹤股票的最新估值快照，附帶發行人鍵 coalesce(cik, figi) 供同業去重。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.industryCode = :industryCode
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
            order by s.figi asc
            """)
    List<Object[]> findActiveTrackedPeerSnapshots(@Param("industryCode") String industryCode);
}
