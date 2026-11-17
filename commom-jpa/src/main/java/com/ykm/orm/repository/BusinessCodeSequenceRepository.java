package com.ykm.orm.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class BusinessCodeSequenceRepository {
    private static final String NEXT_VALUE_SQL = """
            INSERT INTO business_code_sequence (prefix, last_value)
            VALUES (?, 1)
            ON CONFLICT (prefix) DO UPDATE
            SET last_value = business_code_sequence.last_value + 1
            RETURNING last_value
            """;

    private final JdbcTemplate jdbcTemplate;

    /** 同一個 statement 建立或遞增該 prefix，避免先讀後寫造成重複配置。 */
    public Long nextValue(String prefix) {
        return jdbcTemplate.queryForObject(NEXT_VALUE_SQL, Long.class, prefix);
    }
}
