package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface SecFinancialFactRepository extends JpaRepository<SecFinancialFact, Long> {
    List<SecFinancialFact> findByCik(String cik);
    List<SecFinancialFact> findByCikAndPeriodEndGreaterThanEqual(String cik, LocalDate periodEnd);

    @Query("""
            select f from SecFinancialFact f
            where f.cik in :ciks and f.periodEnd >= :periodEnd
              and ((f.taxonomy = 'us-gaap' and f.tag in :tags)
                or (f.taxonomy = 'dei' and f.tag = 'EntityCommonStockSharesOutstanding' and f.unit = 'shares'))
            order by f.cik asc, f.periodEnd asc, f.filed asc, f.accession asc
            """)
    List<SecFinancialFact> findPeriodRebuildFacts(@Param("ciks") Collection<String> ciks,
                                                  @Param("tags") Collection<String> tags,
                                                  @Param("periodEnd") LocalDate periodEnd);
    List<SecFinancialFact> findByCikAndTagInAndPeriodEndLessThan(String cik, Collection<String> tags, LocalDate periodEnd);

    @Query("""
            select distinct f.cik from SecFinancialFact f
            where f.cik in :ciks and f.filed >= :filedFrom
              and f.form in ('10-K','10-K/A','10-Q','10-Q/A','20-F','20-F/A','40-F','40-F/A','6-K','6-K/A')
            """)
    Set<String> findRecentPeriodicFilingCiks(@Param("ciks") Collection<String> ciks,
                                             @Param("filedFrom") LocalDate filedFrom);

    @Query("""
            select f.cik, max(f.periodEnd) from SecFinancialFact f
            where f.cik in :ciks and f.taxonomy = 'us-gaap' and f.tag <> 'CommonStockSharesOutstanding'
              and f.periodEnd <= f.filed
            group by f.cik
            """)
    List<Object[]> findLatestFinancialPeriodEnds(Collection<String> ciks);

    /** 只通過披露時間門；仍須按單位、完整期間、股份基準與財務口徑選擇，不保證可直接作公式輸入。 */
    @Query("select f from SecFinancialFact f where f.cik = :cik and f.availableFromDate <= :date and f.periodEnd <= :date order by f.periodEnd desc, f.filed desc, f.accession desc")
    List<SecFinancialFact> findDisclosedFacts(String cik, LocalDate date);

    /** 財務按本輪已取得版本取數，目標財季由計算器篩選；市值股數仍守原交易日基準。 */
    @Query(value = """
            with eligible as (
              select f.*, (
                f.form in ('10-K','10-K/A','10-Q','10-Q/A','20-F','20-F/A','40-F','40-F/A')
                and f.tag in ('Revenues','SalesRevenueNet','RevenueFromContractWithCustomerExcludingAssessedTax','RevenuesNetOfInterestExpense',
                  'GrossProfit','OperatingIncomeLoss','NetIncomeLoss','ProfitLoss','NetIncomeLossAvailableToCommonStockholdersBasic',
                  'EarningsPerShareBasic','EarningsPerShareDiluted','EarningsPerShareBasicAndDiluted','Assets','Liabilities','StockholdersEquity',
                  'StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest','NetCashProvidedByUsedInOperatingActivities',
                  'NetCashProvidedByUsedInOperatingActivitiesContinuingOperations','PaymentsToAcquireProductiveAssets',
                  'PaymentsToAcquirePropertyPlantAndEquipment')
              ) as statement_evidence
              from sec_financial_fact f
              where cik in (:ciks) and ((taxonomy='us-gaap' and tag in (:tags))
                or (taxonomy='dei' and tag='EntityCommonStockSharesOutstanding' and unit='shares'))
                and observed_at<=:knownAt and period_end<=cast(:day as date)+100
                and filed<=cast((cast(:knownAt as timestamptz) at time zone 'America/New_York') as date)
                and (tag not in ('CommonStockSharesOutstanding','EntityCommonStockSharesOutstanding')
                  or (available_from_date<=:day and period_end<=:day))
            )
            select r.* from (
              select f.*,
                dense_rank() over (partition by cik,taxonomy,tag,unit,period_start,period_end order by filed desc) as revision_rank,
                dense_rank() over (partition by cik,taxonomy,tag,unit order by period_end desc) as period_rank,
                max(filed) filter (where statement_evidence) over (partition by cik,taxonomy,tag,unit,period_start,period_end) as statement_filed,
                max(filed) filter (where tag in ('EarningsPerShareDiluted','EarningsPerShareBasicAndDiluted') and available_from_date<=:day)
                  over (partition by cik,taxonomy,tag,unit,period_start,period_end) as historical_eps_filed
              from eligible f
            ) r where period_rank<=32 and (revision_rank=1 or (statement_evidence and filed=statement_filed)
              or (statement_evidence and report_fiscal_year is not null and report_fiscal_period is not null)
              or (available_from_date<=:day and filed=historical_eps_filed))
            order by cik,tag,period_end desc,period_start,filed desc,accession
            """, nativeQuery = true)
    List<SecFinancialFact> findValuationFlows(Collection<String> ciks, Collection<String> tags, LocalDate day, Instant knownAt);

    @Query("""
            select f from SecFinancialFact f
            where f.cik in :ciks
              and ((f.taxonomy = 'us-gaap' and f.tag = 'CommonStockSharesOutstanding')
                or (f.taxonomy = 'dei' and f.tag = 'EntityCommonStockSharesOutstanding'))
              and f.unit = 'shares' and f.periodStart is null
              and f.periodEnd <= f.filed and f.observedAt <= :knownAt
            order by f.cik asc, f.periodEnd desc, f.filed desc, f.accession asc
            """)
    List<SecFinancialFact> findLatestIssuerShareFacts(@Param("ciks") Collection<String> ciks,
                                                      @Param("knownAt") Instant knownAt);
}
