package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.TaskGroup;
import com.ykm.stock.analytics.model.entity.JobConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobConfigRepository extends JpaRepository<JobConfig, Long> {

    JobConfig findByTaskCode(String taskCode);

    boolean existsByTaskCode(String taskCode);

    JobConfig[] findByActiveStatus(ActiveStatus activeStatus);

    JobConfig[] findByActiveStatusAndTaskGroup(ActiveStatus activeStatus, TaskGroup taskGroup);
}
