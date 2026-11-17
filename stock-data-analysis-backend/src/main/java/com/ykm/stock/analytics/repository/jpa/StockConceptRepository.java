package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.StockConcept;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockConceptRepository extends JpaRepository<StockConcept, String> {
}
