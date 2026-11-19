package com.ykm.stock.analytics.service.portfolio;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigDto;
import com.ykm.stock.analytics.model.dto.stockInsight.portfolio.PortfolioDtos.PortfolioRiskConfigUpdateRequest;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集中管理 sys_config 內之個人資金與倉位風控參數（皆為 USD 基準）。
 * 所有 Batch 04 路徑統一經由此服務讀寫，避免多餘重複鍵與陳舊快取。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioRiskConfigService {
    public static final String KEY_TOTAL_CAPITAL = "PORTFOLIO_TOTAL_CAPITAL";
    public static final String KEY_RISK_PER_TRADE_PCT = "PORTFOLIO_RISK_PER_TRADE_PCT";
    public static final String KEY_MAX_SINGLE_POSITION_PCT = "PORTFOLIO_MAX_SINGLE_POSITION_PCT";
    public static final String KEY_MAX_SECTOR_EXPOSURE_PCT = "PORTFOLIO_MAX_SECTOR_EXPOSURE_PCT";

    public static final BigDecimal DEFAULT_TOTAL_CAPITAL = new BigDecimal("100000.00");
    public static final BigDecimal DEFAULT_RISK_PER_TRADE_PCT = new BigDecimal("0.0100");
    public static final BigDecimal DEFAULT_MAX_SINGLE_POSITION_PCT = new BigDecimal("0.1500");
    public static final BigDecimal DEFAULT_MAX_SECTOR_EXPOSURE_PCT = new BigDecimal("0.3000");

    private static final BigDecimal MAX_RISK_PER_TRADE_PCT = new BigDecimal("0.25");
    private static final long CONFIG_WRITE_LOCK_KEY = 4041001L;
    private static final Object LOCAL_CONFIG_LOCK = new Object();
    private static final List<String> ALL_KEYS = List.of(
            KEY_TOTAL_CAPITAL,
            KEY_RISK_PER_TRADE_PCT,
            KEY_MAX_SINGLE_POSITION_PCT,
            KEY_MAX_SECTOR_EXPOSURE_PCT
    );

    private final PortfolioSysConfigRepository sysConfigRepository;
    private final DateTimeService dateTimeService;
    private final ObjectProvider<CacheManager> cacheManagerProvider;
    private final ObjectProvider<EntityManager> entityManagerProvider;

    @Transactional(readOnly = true)
    public PortfolioRiskConfigDto getConfig() {
        List<SysConfig> rows = sysConfigRepository.findByConfigKeyInOrderByIdAsc(ALL_KEYS);
        LocalDateTime now = dateTimeService.nowLocalDateTime();
        Map<String, String> activeValues = new LinkedHashMap<>();
        for (SysConfig row : rows) {
            if (isEffective(row, now) && !activeValues.containsKey(row.getConfigKey())) {
                activeValues.put(row.getConfigKey(), row.getConfigValue());
            }
        }
        BigDecimal totalCapital = parseOrDefault(activeValues.get(KEY_TOTAL_CAPITAL), DEFAULT_TOTAL_CAPITAL, null, 2);
        BigDecimal riskPct = parseOrDefault(activeValues.get(KEY_RISK_PER_TRADE_PCT), DEFAULT_RISK_PER_TRADE_PCT, MAX_RISK_PER_TRADE_PCT, 4);
        BigDecimal maxSinglePct = parseOrDefault(activeValues.get(KEY_MAX_SINGLE_POSITION_PCT), DEFAULT_MAX_SINGLE_POSITION_PCT, BigDecimal.ONE, 4);
        BigDecimal maxSectorPct = parseOrDefault(activeValues.get(KEY_MAX_SECTOR_EXPOSURE_PCT), DEFAULT_MAX_SECTOR_EXPOSURE_PCT, BigDecimal.ONE, 4);
        return new PortfolioRiskConfigDto(totalCapital, riskPct, maxSinglePct, maxSectorPct, "USD");
    }

    @Transactional
    public PortfolioRiskConfigDto updateConfig(PortfolioRiskConfigUpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PORTFOLIO_CONFIG_REQUIRED");
        }
        BigDecimal totalCapital = validatePositive(request.totalCapital(), "INVALID_TOTAL_CAPITAL", null, 2);
        BigDecimal riskPct = validatePositive(request.riskPerTradePct(), "INVALID_RISK_PER_TRADE_PCT", MAX_RISK_PER_TRADE_PCT, 4);
        BigDecimal maxSinglePct = validatePositive(request.maxSinglePositionPct(), "INVALID_MAX_SINGLE_POSITION_PCT", BigDecimal.ONE, 4);
        BigDecimal maxSectorPct = validatePositive(request.maxSectorExposurePct(), "INVALID_MAX_SECTOR_EXPOSURE_PCT", BigDecimal.ONE, 4);

        synchronized (LOCAL_CONFIG_LOCK) {
            acquirePostgresConfigLockIfActive();
            List<SysConfig> existing = sysConfigRepository.findByConfigKeyInOrderByIdAsc(ALL_KEYS);
            Map<String, List<SysConfig>> byKey = new LinkedHashMap<>();
            for (SysConfig row : existing) {
                byKey.computeIfAbsent(row.getConfigKey(), k -> new java.util.ArrayList<>()).add(row);
            }

            upsertSingleKey(byKey.get(KEY_TOTAL_CAPITAL), KEY_TOTAL_CAPITAL, totalCapital.toPlainString(),
                    "個人投資組合總資金（USD）");
            upsertSingleKey(byKey.get(KEY_RISK_PER_TRADE_PCT), KEY_RISK_PER_TRADE_PCT, riskPct.toPlainString(),
                    "單筆交易最大風險預算比例（預設 0.01 = 1%）");
            upsertSingleKey(byKey.get(KEY_MAX_SINGLE_POSITION_PCT), KEY_MAX_SINGLE_POSITION_PCT, maxSinglePct.toPlainString(),
                    "單一標的最高資金佔比上限（預設 0.15 = 15%）");
            upsertSingleKey(byKey.get(KEY_MAX_SECTOR_EXPOSURE_PCT), KEY_MAX_SECTOR_EXPOSURE_PCT, maxSectorPct.toPlainString(),
                    "單一產業最高持倉資金佔比上限（預設 0.30 = 30%）");
        }

        evictCacheAfterCommit();
        return new PortfolioRiskConfigDto(totalCapital, riskPct, maxSinglePct, maxSectorPct, "USD");
    }

    /** sys_config.config_key 無資料庫唯一約束，於 PostgreSQL 交易中以交易級 advisory lock 序列化首次併發寫入，避免重複鍵競態。 */
    private void acquirePostgresConfigLockIfActive() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || entityManagerProvider == null) {
            return;
        }
        EntityManager em = entityManagerProvider.getIfAvailable();
        if (em == null) {
            return;
        }
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", CONFIG_WRITE_LOCK_KEY)
                .getSingleResult();
    }

    private void upsertSingleKey(List<SysConfig> matches, String key, String value, String description) {
        if (matches == null || matches.isEmpty()) {
            SysConfig created = new SysConfig();
            created.setConfigKey(key);
            created.setConfigValue(value);
            created.setDescription(description);
            created.setEnabled(true);
            created.setEffectiveFrom(null);
            created.setEffectiveTo(null);
            sysConfigRepository.save(created);
            return;
        }
        SysConfig primary = matches.getFirst();
        primary.setConfigValue(value);
        primary.setDescription(description);
        primary.setEnabled(true);
        primary.setEffectiveFrom(null);
        primary.setEffectiveTo(null);
        sysConfigRepository.save(primary);
        // 若歷史資料存在重複 key 列，直接刪除次列，確保共用 SysConfigServiceImpl.findByConfigKey 亦維持單一結果
        for (int i = 1; i < matches.size(); i++) {
            sysConfigRepository.delete(matches.get(i));
        }
    }

    private void evictCacheAfterCommit() {
        CacheManager cacheManager = cacheManagerProvider.getIfAvailable();
        if (cacheManager == null) {
            return;
        }
        Runnable evict = () -> {
            Cache cache = cacheManager.getCache("sysConfig");
            if (cache != null) {
                for (String key : ALL_KEYS) {
                    cache.evict(key);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }

    private boolean isEffective(SysConfig row, LocalDateTime now) {
        if (row == null || !row.isEnabled() || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return false;
        }
        if (now == null) {
            return true;
        }
        if (row.getEffectiveFrom() != null && now.isBefore(row.getEffectiveFrom())) {
            return false;
        }
        return row.getEffectiveTo() == null || !now.isAfter(row.getEffectiveTo());
    }

    private BigDecimal parseOrDefault(String raw, BigDecimal fallback, BigDecimal maxInclusive, int scale) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw.trim());
            BigDecimal rounded = parsed.setScale(scale, RoundingMode.HALF_UP);
            if (parsed.signum() <= 0 || rounded.signum() <= 0) {
                return fallback;
            }
            if (maxInclusive != null && (parsed.compareTo(maxInclusive) > 0 || rounded.compareTo(maxInclusive) > 0)) {
                return fallback;
            }
            return rounded;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal validatePositive(BigDecimal value, String errorCode, BigDecimal maxInclusive, int scale) {
        if (value == null || value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorCode);
        }
        BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorCode);
        }
        if (maxInclusive != null && (value.compareTo(maxInclusive) > 0 || rounded.compareTo(maxInclusive) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorCode);
        }
        return rounded;
    }
}
