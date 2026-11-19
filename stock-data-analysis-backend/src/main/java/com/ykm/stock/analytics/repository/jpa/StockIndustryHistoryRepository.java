package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockIndustryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockIndustryHistoryRepository extends JpaRepository<StockIndustryHistory, Long> {
    /** 同一來源交易中批量取得當前分類，以 FIGI 查找，不依賴 Symbol。 */
    @Query("select h from StockIndustryHistory h join fetch h.industry where h.figi in :figis and h.observedTo is null")
    List<StockIndustryHistory> findCurrentByFigis(@Param("figis") Collection<String> figis);

    /** 截止時尚未被觀察到的分類不能回填至過去。 */
    @Query("select h from StockIndustryHistory h join fetch h.industry where h.figi = :figi and h.observedFrom <= :asOf and (h.observedTo is null or h.observedTo > :asOf)")
    Optional<StockIndustryHistory> findAt(@Param("figi") String figi, @Param("asOf") Instant asOf);

    @Query("select h from StockIndustryHistory h join fetch h.industry where h.figi in :figis and h.observedFrom <= :asOf and (h.observedTo is null or h.observedTo > :asOf)")
    List<StockIndustryHistory> findByFigisAt(@Param("figis") Collection<String> figis, @Param("asOf") Instant asOf);

    /** 批次讀取與 [windowStart, windowEnd] 有重疊之歷史產業歸屬，供板塊量價資金流按各交易日當時已知產業彙總。 */
    @Query("select h from StockIndustryHistory h join fetch h.industry where h.figi in :figis and h.observedFrom <= :windowEnd and (h.observedTo is null or h.observedTo > :windowStart) order by h.figi asc, h.observedFrom asc")
    List<StockIndustryHistory> findOverlappingByFigis(@Param("figis") Collection<String> figis,
                                                      @Param("windowStart") Instant windowStart,
                                                      @Param("windowEnd") Instant windowEnd);
}
