package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

@Repository
public interface StockRepository extends JpaRepository<Stock, String>, JpaSpecificationExecutor<Stock> {
    /** 當前分類與概念只供目前查詢；歷史估值另外按分析時間取產業歷史。 */
    @Override
    @EntityGraph(attributePaths = {"classification", "concepts"})
    Optional<Stock> findById(String figi);

    @Override
    @EntityGraph(attributePaths = {"classification", "concepts"})
    List<Stock> findAllById(Iterable<String> figis);

    List<Stock> findByActiveStatus(ActiveStatus activeStatus);
    List<Stock> findByActiveStatusAndSymbolIn(ActiveStatus activeStatus, Collection<String> symbols);

    @Query("select s from Stock s where s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR','ETP','Closed-End Fund','Royalty Trst','Unit') order by s.symbol, s.figi")
    List<Stock> findSplitSyncStocks(@Param("status") ActiveStatus status);

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR','Closed-End Fund','Royalty Trst')
            order by s.figi
            """)
    List<ActiveStockWithStatus> findFinancialReportSyncStocks(@Param("status") ActiveStatus status);

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part')
            order by s.figi
            """)
    List<ActiveStockWithStatus> findValuationStocks(@Param("status") ActiveStatus status);

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part') and ss.quoteDate >= :day
            order by s.figi
            """)
    List<ActiveStockWithStatus> findValuationStocksForDate(@Param("status") ActiveStatus status, @Param("day") LocalDate day);


    /** 直接使用已成功保存的行情日期判斷是否需要同步。 */
    @Query("select new com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate(s, ss.quoteDate) "
            + "from Stock s join StockStatus ss on ss.figi = s.figi "
            + "where s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR','ETP','Closed-End Fund','Royalty Trst','Unit') "
            + "and (ss.quoteDate is null or ss.quoteDate < :lastClosedTradingDay)")
    List<ActiveStockQuoteDate> findAllActiveStockQuoteDates(@Param("status") ActiveStatus status,
                                                         @Param("lastClosedTradingDay") LocalDate lastClosedTradingDay);

    @Query("select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss) "
            + "from Stock s join StockStatus ss on ss.figi = s.figi where s.activeStatus = :status")
    List<ActiveStockWithStatus> findAllActiveStocksWithStatus(@Param("status") ActiveStatus status);

    Stock findFirstBySymbol(String symbol);
    List<Stock> findBySymbol(String symbol);
    @Query("""
            select s.cik from Stock s
            where s.cik in :ciks and s.activeStatus=:status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR')
            group by s.cik having count(s)>1
            """)
    java.util.Set<String> findMultiSecurityIssuers(@Param("ciks") Collection<String> ciks, @Param("status") ActiveStatus status);

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.cik in :ciks and s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR')
            order by s.figi
            """)
    List<ActiveStockWithStatus> findIssuerSecurities(Collection<String> ciks, ActiveStatus status);

    List<Stock> findBySymbolIn(Collection<String> symbols);
    List<Stock> findByShareClassFigi(String shareClassFigi);
    Stock findFirstBySymbolAndActiveStatus(String symbol, ActiveStatus activeStatus);

    @Query("select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss) from Stock s join StockStatus ss on ss.figi = s.figi where s.activeStatus = :status and s.stockType in ('Common Stock','REIT','MLP','NY Reg Shrs','Ltd Part','ADR') and s.cik is not null order by s.cik, s.figi")
    List<ActiveStockWithStatus> findSecFinancialFactsSyncStocks(@Param("status") ActiveStatus status);
}
