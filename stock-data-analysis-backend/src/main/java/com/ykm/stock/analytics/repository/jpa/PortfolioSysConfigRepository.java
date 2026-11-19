package com.ykm.stock.analytics.repository.jpa;

import com.ykm.orm.entity.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PortfolioSysConfigRepository extends JpaRepository<SysConfig, Long> {
    List<SysConfig> findByConfigKeyInOrderByIdAsc(Collection<String> configKeys);
}
