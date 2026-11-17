package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.FinancialReportItemMapping;
import com.ykm.stock.analytics.model.entity.key.FinancialReportItemMappingId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialReportItemMappingRepository extends JpaRepository<FinancialReportItemMapping, FinancialReportItemMappingId> {
}
