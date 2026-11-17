package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockIndustry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface StockIndustryRepository extends JpaRepository<StockIndustry, String>, JpaSpecificationExecutor<StockIndustry> {
    /** 分類同步與 CSV 匯入共用交易級鎖，鎖後才重查字典。 */
    @Query(value = "select 1 from pg_advisory_xact_lock(7310914269032101)", nativeQuery = true)
    int lockForIndustryImport();

    @Query("select distinct c from StockIndustryHistory h join h.industry c join Stock s on s.figi = h.figi where h.observedTo is null and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE order by c.code")
    List<StockIndustry> findAssignedToActiveStocks();
}
