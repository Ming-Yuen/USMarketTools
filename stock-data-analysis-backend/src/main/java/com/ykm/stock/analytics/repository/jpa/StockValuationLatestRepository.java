package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockValuationLatest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface StockValuationLatestRepository extends JpaRepository<StockValuationLatest, String> {
    List<StockValuationLatest> findByFigiIn(Collection<String> figis);

    /** 讀取同產業、同估值交易日且有效持續追蹤股票的最新估值快照，附帶原始發行人鍵 coalesce(cik, figi) 供同業去重。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.industryCode = :industryCode
              and v.valuationDate = :valuationDate
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
            order by s.figi asc
            """)
    List<Object[]> findActiveTrackedPeerSnapshots(@Param("industryCode") String industryCode,
                                                  @Param("valuationDate") LocalDate valuationDate);

    /** 批次讀取多產業與多估值交易日的有效追蹤股快照，供兩階段批次刷新計算同業參考與 verdict。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.industryCode in :industryCodes
              and v.valuationDate in :valuationDates
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
            order by s.figi asc
            """)
    List<Object[]> findActiveTrackedPeerSnapshotsIn(@Param("industryCodes") Collection<String> industryCodes,
                                                    @Param("valuationDates") Collection<LocalDate> valuationDates);

    /** 讀取自訂對標清單中有效且持續追蹤、估值日吻合之對手快照與原始發行人鍵。 */
    @Query("""
            select case when s.cik is not null and s.cik <> '' then s.cik else s.figi end, v
            from StockValuationLatest v
            join Stock s on s.figi = v.figi
            where v.figi in :peerFigis
              and v.valuationDate in :valuationDates
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
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
}
