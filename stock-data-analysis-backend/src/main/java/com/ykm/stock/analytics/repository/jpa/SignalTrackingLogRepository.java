package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SignalTrackingLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface SignalTrackingLogRepository extends JpaRepository<SignalTrackingLog, Long> {
    /** 雙向查詢指定股票於冷卻視窗 [minSignalDate, maxSignalDate] 內的既有訊號，防止同日或倒序寫入突破 20 個交易日去重。 */
    @Query("""
            select l from SignalTrackingLog l
            where l.figi in :figis
              and l.signalDate between :minSignalDate and :maxSignalDate
            order by l.signalDate desc
            """)
    List<SignalTrackingLog> findSignalsInDateRange(@Param("figis") Collection<String> figis,
                                                   @Param("minSignalDate") LocalDate minSignalDate,
                                                   @Param("maxSignalDate") LocalDate maxSignalDate);

    /** 查詢尚未完成 +5D/+20D/+60D、完整 60D 路徑或終態結算之歷史訊號（直接取 log 內所有 FIGI，不受停追／下市限制；限定合理追溯起點避免無限掃描老下市股）。 */
    @Query("""
            select l from SignalTrackingLog l
            where l.signalDate < :asOfDate
              and l.signalDate >= :minSignalDate
              and (l.settled5dDate is null
                   or l.settled20dDate is null
                   or l.settled60dDate is null
                   or l.settledPathDate is null
                   or l.outcomeStatus in ('PENDING', 'DATA_GAP'))
            order by l.signalDate asc, l.id asc
            """)
    List<SignalTrackingLog> findPendingSettlement(@Param("asOfDate") LocalDate asOfDate,
                                                  @Param("minSignalDate") LocalDate minSignalDate);

    List<SignalTrackingLog> findAllByOrderBySignalDateDescIdDesc();
}
