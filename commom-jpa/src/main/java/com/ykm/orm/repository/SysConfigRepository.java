package com.ykm.orm.repository;

import com.ykm.orm.entity.SysConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SysConfigRepository extends JpaRepository<SysConfig, Long> {
    SysConfig findByConfigKey(String key);
}
