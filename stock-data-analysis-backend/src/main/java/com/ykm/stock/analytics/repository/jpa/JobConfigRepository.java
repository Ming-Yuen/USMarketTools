package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface JobConfigRepository extends JpaRepository<JobConfig, Long> {

    JobConfig findByTaskCode(String taskCode);

    boolean existsByTaskCode(String taskCode);

    JobConfig[] findByActiveStatus(ActiveStatus activeStatus);

    JobConfig[] findByActiveStatusAndTaskGroup(ActiveStatus activeStatus, TaskGroup taskGroup);

    @Query("select j.watermarkDate from JobConfig j where j.taskCode = :taskCode")
    Optional<LocalDate> findWatermarkDateByTaskCode(@Param("taskCode") String taskCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update JobConfig j set j.watermarkDate = :watermarkDate where j.taskCode = :taskCode and (j.watermarkDate is null or j.watermarkDate < :watermarkDate)")
    int updateWatermarkDate(@Param("taskCode") String taskCode, @Param("watermarkDate") LocalDate watermarkDate);

    /** 單條原子 SQL 單向推進 job_config.watermark_date，避免並發水位倒退或覆蓋 Cron／啟停設定。 */
    static boolean advanceWatermarkDate(JobConfigRepository repository, String taskCode, LocalDate candidate) {
        if (repository == null || taskCode == null || candidate == null) {
            return false;
        }
        return repository.updateWatermarkDate(taskCode, candidate) > 0;
    }
}
