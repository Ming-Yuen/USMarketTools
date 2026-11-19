package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.AdrShareRatio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface AdrShareRatioRepository extends JpaRepository<AdrShareRatio, String> {
    List<AdrShareRatio> findBySymbolIn(Collection<String> symbols);

    List<AdrShareRatio> findByVerifiedTrueOrderBySymbolAsc();
}
