package com.ykm.orm;

import com.ykm.orm.entity.BaseEntity;
import org.mapstruct.*;

public interface EntityConverter<T extends BaseEntity> {

    @BeanMapping(
            nullValueMappingStrategy = NullValueMappingStrategy.RETURN_NULL,
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
//            qualifiedByName = "isNotEmpty"
    )
//    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version",   ignore = true)
    T mergeFields(T source, @MappingTarget T target);

    default T mergeEntity(T source, @MappingTarget T target) {
        if (target == null) {
            return source;
        }
        mergeFields(source, target);
        return target;
    }


//    @Named("isNotEmpty")
    @Condition
    default boolean isNotEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
