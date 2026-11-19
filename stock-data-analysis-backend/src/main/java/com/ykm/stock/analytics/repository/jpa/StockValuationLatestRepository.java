package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StockValuationLatestRepository extends JpaRepository<StockValuationLatest, String> {
    @Query("select max(v.valuationDate) from StockValuationLatest v where v.valuationDate <= :maxDate")
    java.util.Optional<LocalDate> findMaxValuationDateAtOrBefore(@Param("maxDate") LocalDate maxDate);

    List<StockValuationLatest> findByFigiIn(Collection<String> figis);

    /** 讀取同產業、同估值交易日且有效持續追蹤股票的最新估值快照（即時排除未核實 ADR），附帶原始發行人鍵 coalesce(cik, figi) 供同業去重。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.industryCode = :industryCode
              and v.valuationDate = :valuationDate
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
              and (upper(coalesce(s.stockType, '')) <> 'ADR'
                   or exists (select 1 from AdrShareRatio ar where ar.symbol = upper(s.symbol) and ar.verified = true and ar.adrRatio > 0))
            order by s.figi asc
            """)
    List<Object[]> findActiveTrackedPeerSnapshots(@Param("industryCode") String industryCode,
                                                  @Param("valuationDate") LocalDate valuationDate);

    /** 批次讀取多產業與多估值交易日的有效追蹤股快照（即時排除未核實 ADR），供兩階段批次刷新計算同業參考與 verdict。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.industryCode in :industryCodes
              and v.valuationDate in :valuationDates
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
              and (upper(coalesce(s.stockType, '')) <> 'ADR'
                   or exists (select 1 from AdrShareRatio ar where ar.symbol = upper(s.symbol) and ar.verified = true and ar.adrRatio > 0))
            order by s.figi asc
            """)
    List<Object[]> findActiveTrackedPeerSnapshotsIn(@Param("industryCodes") Collection<String> industryCodes,
                                                    @Param("valuationDates") Collection<LocalDate> valuationDates);

    /** 讀取自訂對標清單中有效且持續追蹤、估值日吻合之對手快照與原始發行人鍵（即時排除未核實 ADR）。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.figi in :peerFigis
              and v.valuationDate in :valuationDates
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
              and (upper(coalesce(s.stockType, '')) <> 'ADR'
                   or exists (select 1 from AdrShareRatio ar where ar.symbol = upper(s.symbol) and ar.verified = true and ar.adrRatio > 0))
            order by s.figi asc
            """)
    List<Object[]> findActiveTrackedCustomPeerSnapshots(@Param("peerFigis") Collection<String> peerFigis,
                                                        @Param("valuationDates") Collection<LocalDate> valuationDates);

    /** 查找屬於指定產業與估值日集合的有效追蹤股票 FIGI，供局部刷新反向更新同業 verdict。 */
    @Query("""
            select v.figi
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.industryCode in :industryCodes
              and v.valuationDate in :valuationDates
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
            """)
    List<String> findActiveTrackedFigisByIndustryCodesAndDates(@Param("industryCodes") Collection<String> industryCodes,
                                                               @Param("valuationDates") Collection<LocalDate> valuationDates);
    /**
     * 每日機會雷達第一階段 SQL 初篩（主清單：低估支撐低接）：
     * 1. 限定當日估值快照、ACTIVE + tracked=true + 正式營運股類型
     * 2. 雙重排除 REIT（同時檢查 stock.stock_type 與 stock_valuation_latest.model_code）
     * 3. 限定估值結論（DEEP_UNDERVALUED / MODERATELY_UNDERVALUED / FAIR_LOWER）與基本面支撐（STRONG / MIXED）
     * 4. 排除 [targetDate, earningsCutoffDate] 內已知可見未取消財報事件
     */
    @Query("""
            select v, s
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.valuationDate = :targetDate
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
              and s.stockType in :operatingTypes
              and upper(coalesce(s.stockType, '')) <> 'REIT'
              and upper(coalesce(v.modelCode, '')) <> 'REIT'
              and v.verdict in :verdicts
              and v.fundamentalBacking in :backings
              and not exists (
                  select 1 from MarketEvent e
                  where e.figi = v.figi
                    and e.eventType = 'EARNINGS'
                    and e.status <> 'CANCELLED'
                    and e.eventDate between :targetDate and :earningsCutoffDate
                    and e.observedAt <= :observationCutoff
                    and (e.sourcePublishedAt is null or e.sourcePublishedAt <= :observationCutoff)
              )
            order by v.figi asc
            """)
    List<Object[]> findValueRadarCandidates(@Param("targetDate") LocalDate targetDate,
                                            @Param("earningsCutoffDate") LocalDate earningsCutoffDate,
                                            @Param("observationCutoff") java.time.Instant observationCutoff,
                                            @Param("operatingTypes") Collection<String> operatingTypes,
                                            @Param("verdicts") Collection<String> verdicts,
                                            @Param("backings") Collection<String> backings);

    /**
     * 每日機會雷達第一階段 SQL 初篩（次清單：強勢成長股趨勢回踩試單）：
     * 嚴格只讀 PostgreSQL stock_earnings_forecast 既有 45 日內 ANNUAL 共識快取，絕不觸發外部 Alpha Vantage API。
     */
    @Query("""
            select v, s
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.valuationDate = :targetDate
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
              and s.stockType in :operatingTypes
              and upper(coalesce(s.stockType, '')) <> 'REIT'
              and upper(coalesce(v.modelCode, '')) <> 'REIT'
              and v.verdict in ('FAIR_UPPER', 'MODERATELY_OVERVALUED')
              and v.fundamentalBacking = 'STRONG'
              and exists (
                  select 1 from StockEarningsForecast f
                  where f.figi = v.figi
                    and upper(f.periodType) = 'ANNUAL'
                    and f.observationDate between :minAnnualObsDate and :maxAnnualObsDate
                    and f.observedAt <= :observationCutoff
              )
              and not exists (
                  select 1 from MarketEvent e
                  where e.figi = v.figi
                    and e.eventType = 'EARNINGS'
                    and e.status <> 'CANCELLED'
                    and e.eventDate between :targetDate and :earningsCutoffDate
                    and e.observedAt <= :observationCutoff
                    and (e.sourcePublishedAt is null or e.sourcePublishedAt <= :observationCutoff)
              )
            order by v.figi asc
            """)
    List<Object[]> findStarterRadarCandidates(@Param("targetDate") LocalDate targetDate,
                                              @Param("earningsCutoffDate") LocalDate earningsCutoffDate,
                                              @Param("minAnnualObsDate") LocalDate minAnnualObsDate,
                                              @Param("maxAnnualObsDate") LocalDate maxAnnualObsDate,
                                              @Param("observationCutoff") java.time.Instant observationCutoff,
                                              @Param("operatingTypes") Collection<String> operatingTypes);
}
