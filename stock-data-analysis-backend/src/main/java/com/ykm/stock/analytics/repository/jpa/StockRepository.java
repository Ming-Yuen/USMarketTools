package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.service.analysis.SecurityTypePolicy;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** 行情與拆股保留 tracked=true、同發行人兄弟股及大盤基準 ETF，避免雙股類市值與大盤指標斷鏈。 */
    @Query("""
            select s from Stock s
            where s.activeStatus = :status and s.stockType in :types
              and (s.tracked = true or s.symbol in :benchmarks
                   or (s.cik is not null and exists (
                       select 1 from Stock sibling
                       where sibling.cik = s.cik and sibling.activeStatus = :status and sibling.tracked = true)))
            order by s.symbol, s.figi
            """)
    List<Stock> findSplitSyncStocks(@Param("status") ActiveStatus status,
                                    @Param("types") Collection<String> types,
                                    @Param("benchmarks") Collection<String> benchmarks);

    default List<Stock> findSplitSyncStocks(ActiveStatus status) {
        return findSplitSyncStocks(status, SecurityTypePolicy.MARKET_DATA_STOCK_TYPES, SecurityTypePolicy.BENCHMARK_ETF_SYMBOLS);
    }

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.tracked = true and s.stockType in :types
            order by s.figi
            """)
    List<ActiveStockWithStatus> findTrackedStocksByTypes(@Param("status") ActiveStatus status,
                                                         @Param("types") Collection<String> types);

    default List<ActiveStockWithStatus> findFinancialReportSyncStocks(ActiveStatus status) {
        return findTrackedStocksByTypes(status, SecurityTypePolicy.FINANCIAL_REPORT_STOCK_TYPES);
    }

    default List<ActiveStockWithStatus> findValuationStocks(ActiveStatus status) {
        return findTrackedStocksByTypes(status, SecurityTypePolicy.VALUATION_STOCK_TYPES);
    }

    /** 僅含一般營運普通股（不含 ADR／ETF），供板塊資金流維持既定全市場統計母體。 */
    default List<ActiveStockWithStatus> findOperatingValuationStocks(ActiveStatus status) {
        return findTrackedStocksByTypes(status, SecurityTypePolicy.OPERATING_STOCK_TYPES);
    }

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.tracked = true and s.stockType in :types and ss.quoteDate >= :day
            order by s.figi
            """)
    List<ActiveStockWithStatus> findValuationStocksForDate(@Param("status") ActiveStatus status,
                                                           @Param("types") Collection<String> types,
                                                           @Param("day") LocalDate day);

    default List<ActiveStockWithStatus> findValuationStocksForDate(ActiveStatus status, LocalDate day) {
        return findValuationStocksForDate(status, SecurityTypePolicy.VALUATION_STOCK_TYPES, day);
    }

    /** 直接使用已成功保存的行情日期判斷是否需要同步；納入同 CIK 兄弟股與大盤基準 ETF。 */
    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockQuoteDate(s, ss.quoteDate)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.stockType in :types
              and (s.tracked = true or s.symbol in :benchmarks
                   or (s.cik is not null and exists (
                       select 1 from Stock sibling
                       where sibling.cik = s.cik and sibling.activeStatus = :status and sibling.tracked = true)))
              and (ss.quoteDate is null or ss.quoteDate < :lastClosedTradingDay)
            """)
    List<ActiveStockQuoteDate> findAllActiveStockQuoteDates(@Param("status") ActiveStatus status,
                                                            @Param("types") Collection<String> types,
                                                            @Param("benchmarks") Collection<String> benchmarks,
                                                            @Param("lastClosedTradingDay") LocalDate lastClosedTradingDay);

    default List<ActiveStockQuoteDate> findAllActiveStockQuoteDates(ActiveStatus status, LocalDate lastClosedTradingDay) {
        return findAllActiveStockQuoteDates(status, SecurityTypePolicy.MARKET_DATA_STOCK_TYPES,
                SecurityTypePolicy.BENCHMARK_ETF_SYMBOLS, lastClosedTradingDay);
    }

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.tracked = true
            """)
    List<ActiveStockWithStatus> findAllActiveStocksWithStatus(@Param("status") ActiveStatus status);

    Stock findFirstBySymbol(String symbol);

    List<Stock> findBySymbol(String symbol);

    /** 多股類判定排除 ADR，且不加 tracked 過濾，避免停追其中一類股或雙重掛牌 ADR 造成誤判。 */
    @Query("""
            select s.cik from Stock s
            where s.cik in :ciks and s.activeStatus = :status and s.stockType in :types
            group by s.cik having count(s) > 1
            """)
    Set<String> findMultiSecurityIssuers(@Param("ciks") Collection<String> ciks,
                                         @Param("status") ActiveStatus status,
                                         @Param("types") Collection<String> types);

    default Set<String> findMultiSecurityIssuers(Collection<String> ciks, ActiveStatus status) {
        return findMultiSecurityIssuers(ciks, status, SecurityTypePolicy.OPERATING_STOCK_TYPES);
    }

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.cik in :ciks and s.activeStatus = :status and s.stockType in :types
            order by s.figi
            """)
    List<ActiveStockWithStatus> findIssuerSecurities(@Param("ciks") Collection<String> ciks,
                                                     @Param("status") ActiveStatus status,
                                                     @Param("types") Collection<String> types);

    default List<ActiveStockWithStatus> findIssuerSecurities(Collection<String> ciks, ActiveStatus status) {
        return findIssuerSecurities(ciks, status, SecurityTypePolicy.OPERATING_STOCK_TYPES);
    }

    List<Stock> findBySymbolIn(Collection<String> symbols);

    List<Stock> findByShareClassFigi(String shareClassFigi);

    Stock findFirstBySymbolAndActiveStatus(String symbol, ActiveStatus activeStatus);

    @Query("""
            select new com.ykm.stock.analytics.model.projection.ActiveStockWithStatus(s, ss)
            from Stock s join StockStatus ss on ss.figi = s.figi
            where s.activeStatus = :status and s.tracked = true and s.stockType in :types and s.cik is not null
            order by s.cik, s.figi
            """)
    List<ActiveStockWithStatus> findSecFinancialFactsSyncStocks(@Param("status") ActiveStatus status,
                                                                @Param("types") Collection<String> types);

    default List<ActiveStockWithStatus> findSecFinancialFactsSyncStocks(ActiveStatus status) {
        return findSecFinancialFactsSyncStocks(status, SecurityTypePolicy.SEC_FILING_STOCK_TYPES);
    }

    @Query("""
            select s.figi from Stock s
            join s.classification c
            where s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and c.industryCode = :industryCode
            order by s.figi asc
            """)
    List<String> findActiveFigisByIndustryCode(@Param("industryCode") String industryCode);
}
