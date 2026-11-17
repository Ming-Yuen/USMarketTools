package com.ykm.orm.service;

import com.ykm.common.common_lib.service.SysConfigService;
import com.ykm.orm.entity.SysConfig;
import com.ykm.orm.repository.SysConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl implements SysConfigService {

    private final SysConfigRepository sysConfigRepository;

    @Override
    @Cacheable("sysConfig")
    public String getString(String key) {
        SysConfig sysConfig = sysConfigRepository.findByConfigKey(key);
        return sysConfig == null ? null : sysConfig.getConfigValue();
    }

    @Override
    public Integer getInteger(String key) {
        String value = getString(key);
        return value == null ? null : Integer.parseInt(value);
    }
}
