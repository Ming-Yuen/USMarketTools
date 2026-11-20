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

    @Query("select h from StockIndustryHistory h join fetch h.industry where h.figi in :figis and h.observedFrom <= :asOf and (h.observedTo is null or h.observedTo > :asOf) order by h.figi asc, h.observedFrom desc, h.id desc")
    List<StockIndustryHistory> findByFigisAt(@Param("figis") Collection<String> figis, @Param("asOf") Instant asOf);

    /** 批次讀取與 [windowStart, windowEnd] 有重疊之歷史產業歸屬，供板塊量價資金流按各交易日當時已知產業彙總。 */
    @Query("select h from StockIndustryHistory h join fetch h.industry where h.figi in :figis and h.observedFrom <= :windowEnd and (h.observedTo is null or h.observedTo > :windowStart) order by h.figi asc, h.observedFrom asc")
    List<StockIndustryHistory> findOverlappingByFigis(@Param("figis") Collection<String> figis,
                                                      @Param("windowStart") Instant windowStart,
                                                      @Param("windowEnd") Instant windowEnd);

    /** 輕量候選股元資料投影：先完成資產類型／REIT 資格篩選與 300 檔截斷，再僅針對最終候選載入完整 Stock 實體。 */
    record SectorCandidateMeta(String figi, String stockType, String modelCode) {}

    /** 依指定產業代碼集合於截止時間點篩選活躍追蹤候選股（輕量投影，保留 ETF 與缺估值標的並按確定性規則排序）。 */
    @Query("""
            select new com.ykm.stock.analytics.repository.jpa.StockIndustryHistoryRepository$SectorCandidateMeta(
                s.figi, s.stockType, v.modelCode)
            from StockIndustryHistory h
            join Stock s on s.figi = h.figi
            left join StockValuationLatest v on v.figi = s.figi
            where h.industry.code in :industryCodes
              and h.observedFrom <= :asOf
              and (h.observedTo is null or h.observedTo > :asOf)
              and s.activeStatus = com.ykm.stock.analytics.enums.ActiveStatus.ACTIVE
              and s.tracked = true
            order by case when v.valuationDate = :valuationDate and upper(coalesce(v.currency, '')) = 'USD' and v.marketCap is not null then v.marketCap else -1 end desc,
                     s.symbol asc,
                     s.figi asc
            """)
    List<SectorCandidateMeta> findActiveTrackedCandidateMetaByIndustryCodesAt(@Param("industryCodes") Collection<String> industryCodes,
                                                                            @Param("asOf") Instant asOf,
                                                                            @Param("valuationDate") java.time.LocalDate valuationDate);
}
