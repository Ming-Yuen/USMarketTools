package com.ykm.stock.analytics.model.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import org.hibernate.annotations.Comment;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Data

@Entity
@Table(name = "search_criteria_config", uniqueConstraints = {
        @UniqueConstraint(name = "uk_search_criteria_config_user_page", columnNames = {"user_id", "page_key"})
})
@Comment("使用者各頁面搜尋條件的顯示設定。")
public class SearchCriteriaConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("搜尋條件設定的資料列主鍵。")
    private Long id;

    @Column(name = "user_id", nullable = false)
    @Comment("此搜尋偏好所屬的使用者識別碼。")
    private String userId;

    @Column(name = "page_key", nullable = false)
    @Comment("適用的前端頁面識別碼。")
    private String pageKey;

    // 使用 PostgreSQL 原生 TEXT[] 儲存前端停用的欄位名稱，保留原有 List<String> API。
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "disabled_fields", columnDefinition = "text[]")
    @Comment("該頁面被使用者停用的篩選欄位名稱列表。")
    private List<String> disabledFields;
}
