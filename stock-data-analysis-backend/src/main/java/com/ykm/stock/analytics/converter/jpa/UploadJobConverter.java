package com.ykm.stock.analytics.converter.jpa;

import com.ykm.stock.analytics.model.dto.stockInsight.UploadResult;
import com.ykm.stock.analytics.model.entity.UploadJob;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface UploadJobConverter {

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "id", source = "id")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "fileName", source = "fileName")
    @Mapping(target = "inputHash", source = "hash")
    @Mapping(target = "inputData", source = "input")
    @Mapping(target = "status", constant = "PENDING")
    UploadJob newUploadJob(UUID id, String type, String fileName, String hash, byte[] input);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "totalRows", source = "totalRows")
    @Mapping(target = "updated", source = "updated")
    @Mapping(target = "unchanged", source = "unchanged")
    @Mapping(target = "createdIndustries", source = "createdIndustries")
    @Mapping(target = "missingIndustry", source = "missingIndustry")
    @Mapping(target = "unmatched", source = "unmatched")
    @Mapping(target = "ambiguous", source = "ambiguous")
    @Mapping(target = "issues", source = "issues")
    void applyUploadResult(UploadResult result, @MappingTarget UploadJob job);
}
