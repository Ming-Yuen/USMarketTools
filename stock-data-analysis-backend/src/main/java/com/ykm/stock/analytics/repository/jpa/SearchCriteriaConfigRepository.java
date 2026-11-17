package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.SearchCriteriaConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchCriteriaConfigRepository extends JpaRepository<SearchCriteriaConfig, Long> {
    SearchCriteriaConfig findByUserIdAndPageKey(String userId, String pageKey);
}
