package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockCustomPeer;
import com.ykm.stock.analytics.model.entity.key.StockCustomPeerId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StockCustomPeerRepository extends JpaRepository<StockCustomPeer, StockCustomPeerId> {
    List<StockCustomPeer> findByFigiOrderBySortOrderAsc(String figi);

    List<StockCustomPeer> findByFigiInOrderByFigiAscSortOrderAsc(Collection<String> figis);

    @Query("select distinct p.figi from StockCustomPeer p where p.peerFigi in :peerFigis")
    List<String> findTargetFigisByPeerFigiIn(@Param("peerFigis") Collection<String> peerFigis);

    @Query("""
            select distinct p.figi from StockCustomPeer p
            join Stock s on s.figi = p.figi
            join StockValuationLatest v on v.figi = p.figi
            where p.peerFigi in :peerFigis
              and s.tracked = true
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and v.valuationDate is not null
            """)
    List<String> findEligibleTargetFigisByPeerFigiIn(@Param("peerFigis") Collection<String> peerFigis);

    @Modifying
    @Query("delete from StockCustomPeer p where p.figi = :figi")
    void deleteByFigi(@Param("figi") String figi);
}
