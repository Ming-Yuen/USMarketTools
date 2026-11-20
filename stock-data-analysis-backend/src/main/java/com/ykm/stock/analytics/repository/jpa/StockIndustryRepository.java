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

    interface IndustryActiveStockCount {
        String getIndustryCode();
        long getActiveStockCount();
    }

    /** 一次聚合各產業目前歸屬且狀態為 ACTIVE 之去重 FIGI 數，供產業估值維護中心總覽避免 N+1 查詢。 */
    @Query("select h.industry.code as industryCode, count(distinct h.figi) as activeStockCount "
            + "from StockIndustryHistory h join Stock s on s.figi = h.figi "
            + "where h.observedTo is null and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE "
            + "group by h.industry.code")
    List<IndustryActiveStockCount> countActiveStocksByIndustry();
}
