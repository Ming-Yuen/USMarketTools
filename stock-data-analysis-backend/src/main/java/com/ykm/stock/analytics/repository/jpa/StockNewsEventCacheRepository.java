package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockNewsEventCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockNewsEventCacheRepository extends JpaRepository<StockNewsEventCache, Long> {

    Optional<StockNewsEventCache> findByFigiAndSourceTypeAndExternalId(String figi, String sourceType, String externalId);

    List<StockNewsEventCache> findTop30ByFigiOrderByPublishedAtDescIdDesc(String figi);

    List<StockNewsEventCache> findTop30ByFigiAndPublishedAtLessThanEqualOrderByPublishedAtDescIdDesc(String figi, Instant publishedCutoff);

    List<StockNewsEventCache> findByFigiAndPublishedAtGreaterThanEqualOrderByPublishedAtDescIdDesc(String figi, Instant since);
}
