package com.ykm.stock.analytics.converter.jpa;

import com.ykm.common.common_lib.converter.CommonTypeConverter;
import com.ykm.common.common_lib.converter.JsonTypeConverter;
import com.ykm.stock.analytics.model.dto.stockInsight.JobTask;
import com.ykm.stock.analytics.model.entity.JobConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = JsonTypeConverter.class)
public interface ScheduleConverter extends CommonTypeConverter {

    @Mapping(target = "jobParams", source = "config")
    @Mapping(target = "jobClassPath", source = "jobClassName")
    JobTask convertJobTask(JobConfig jobConfig);
}
