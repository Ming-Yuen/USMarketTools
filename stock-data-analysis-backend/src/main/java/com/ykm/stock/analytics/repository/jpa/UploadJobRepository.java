package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.enums.ProcessStatus;
import com.ykm.stock.analytics.model.entity.UploadJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public interface UploadJobRepository extends JpaRepository<UploadJob, UUID> {
    // Closed projection 只讀狀態及結果，定時查詢不重讀可能達 10 MB 的 CSV。
    java.util.Optional<Summary> findSummaryById(UUID id);
    interface Summary {
        UUID getId(); String getFileName(); ProcessStatus getStatus();
        Instant getStartedAt(); Instant getFinishedAt(); String getError();
        Integer getTotalRows(); Integer getUpdated(); Integer getUnchanged(); Integer getCreatedIndustries();
        Integer getMissingIndustry(); Integer getUnmatched(); Integer getAmbiguous();
        java.util.List<com.ykm.stock.analytics.model.dto.stockInsight.UploadResult.Issue> getIssues();
    }
    @Modifying
    @Query("update UploadJob j set j.status = :failed, j.error = 'BACKEND_RESTARTED', j.inputData = null, j.finishedAt = :now "
            + "where j.createdAt < :startup and j.status in :unfinished")
    int failInterrupted(@Param("startup") LocalDateTime startup, @Param("now") Instant now,
                        @Param("failed") ProcessStatus failed, @Param("unfinished") java.util.Collection<ProcessStatus> unfinished);
}
