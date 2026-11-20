package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockEventAiSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockEventAiSummaryRepository extends JpaRepository<StockEventAiSummary, Long> {

    Optional<StockEventAiSummary> findByFigiAndInputFingerprint(String figi, String inputFingerprint);

    List<StockEventAiSummary> findTop10ByFigiOrderByCreatedAtDescIdDesc(String figi);

    List<StockEventAiSummary> findTop10ByFigiAndUpdatedAtLessThanEqualOrderByCreatedAtDescIdDesc(String figi, Instant updatedAtCutoff);

    Optional<StockEventAiSummary> findTopByFigiAndStatusOrderByCreatedAtDescIdDesc(String figi, String status);

    long countByTriggerDateAndAiProviderNot(LocalDate triggerDate, String excludedProvider);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StockEventAiSummary s set s.status = :inProgress, s.aiProvider = :provider, s.errorMessage = null, s.updatedAt = :now "
            + "where s.id = :id and s.status = :failed")
    int reclaimFailedSlot(@Param("id") Long id,
                          @Param("failed") String failedStatus,
                          @Param("inProgress") String inProgressStatus,
                          @Param("provider") String provider,
                          @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StockEventAiSummary s set s.status = :timedOut, s.errorMessage = :errorMessage, s.updatedAt = :now "
            + "where s.id = :id and s.status = :inProgress and s.updatedAt = :expectedUpdatedAt")
    int markStaleInProgressTimedOut(@Param("id") Long id,
                                    @Param("inProgress") String inProgressStatus,
                                    @Param("expectedUpdatedAt") Instant expectedUpdatedAt,
                                    @Param("timedOut") String timedOutStatus,
                                    @Param("errorMessage") String errorMessage,
                                    @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update StockEventAiSummary s set s.status = :terminalStatus, s.errorMessage = :errorMessage, s.updatedAt = :now "
            + "where s.id = :id and s.status = :inProgress and s.updatedAt = :expectedUpdatedAt")
    int finalizeClaimedFailure(@Param("id") Long id,
                               @Param("inProgress") String inProgressStatus,
                               @Param("expectedUpdatedAt") Instant expectedUpdatedAt,
                               @Param("terminalStatus") String terminalStatus,
                               @Param("errorMessage") String errorMessage,
                               @Param("now") Instant now);
}
