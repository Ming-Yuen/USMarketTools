package com.ykm.common.common_lib.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;

@Component
public class JsonTypeConverter {

    private final ObjectWriter includeNullWriter;
    private final ObjectWriter excludeNullWriter;
    private final ObjectMapper mapper;

    public JsonTypeConverter(ObjectMapper mapper) {
        this.mapper = mapper;
        // 构造时准备好两个复用的 ObjectWriter
        this.includeNullWriter = mapper
                .copy()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .writer();
        this.excludeNullWriter = mapper
                .copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .writer();
    }

    @Named("stringToMap")
    public Map<String, Object> stringToMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON config", e);
        }
    }

    @Named("toJsonStringIncludeNull")
    public String writeValueAsString(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return includeNullWriter.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @Named("toJsonStringExcludeNull")
    public String toJsonStringExcludeNull(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return excludeNullWriter.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @Named("readValue")
    public <T> T readValue(String json, Class<T> clazz) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON deserialization failed", e);
        }
    }

    public ObjectMapper getObjectMapper(){
        return this.mapper;
    }
}
