package com.ykm.stock.analytics.repository.jpa;

import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.model.entity.JobConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PortfolioSysConfigRepository extends JpaRepository<SysConfig, Long> {
    String KEY_SYNC_HISTORY_START_DATE = "sync_history_start_date";
    String KEY_FX_SYNC_START_DATE = "fx_sync_start_date";
    LocalDate DEFAULT_SYNC_HISTORY_START_DATE = LocalDate.of(2016, 1, 1);
    LocalDate DEFAULT_FX_SYNC_START_DATE = LocalDate.of(2014, 1, 1);

    List<SysConfig> findByConfigKeyInOrderByIdAsc(Collection<String> configKeys);

    /** 直接即時讀取啟用中之日期設定（繞過 SysConfigServiceImpl 的 @Cacheable("sysConfig") 舊快取）。 */
    static LocalDate resolveDateConfig(PortfolioSysConfigRepository repository, String configKey, LocalDate fallback) {
        if (repository == null || configKey == null || configKey.isBlank()) {
            return fallback;
        }
        List<SysConfig> rows = repository.findByConfigKeyInOrderByIdAsc(List.of(configKey));
        if (rows == null || rows.isEmpty()) {
            return fallback;
        }
        for (SysConfig row : rows) {
            if (row == null || !row.isEnabled() || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
                continue;
            }
            try {
                return LocalDate.parse(row.getConfigValue().trim());
            } catch (RuntimeException ignored) {
            }
        }
        return fallback;
    }

    /** 讀取全系統歷史行情與總經同步開始日（預設 2016-01-01）。 */
    static LocalDate resolveSyncHistoryStartDate(PortfolioSysConfigRepository repository) {
        return resolveDateConfig(repository, KEY_SYNC_HISTORY_START_DATE, DEFAULT_SYNC_HISTORY_START_DATE);
    }

    /** 讀取外幣匯率同步開始日（預設 2014-01-01；未設定時回退 sync_history_start_date）。 */
    static LocalDate resolveFxSyncStartDate(PortfolioSysConfigRepository repository) {
        LocalDate fxStart = resolveDateConfig(repository, KEY_FX_SYNC_START_DATE, null);
        if (fxStart != null) {
            return fxStart;
        }
        return resolveDateConfig(repository, KEY_SYNC_HISTORY_START_DATE, DEFAULT_FX_SYNC_START_DATE);
    }

    /**
     * 將舊 sys_config 同步水位平滑遷移至 job_config.watermark_date 後才清理舊鍵：
     * 僅採用 enabled=true 之有效日期，且必須確認目標 JobConfig 存在（或成功寫入）後才刪除舊鍵。
     */
    static LocalDate migrateLegacyWatermark(
            PortfolioSysConfigRepository sysConfigRepository,
            JobConfigRepository jobConfigRepository,
            String legacyKey,
            String taskCode) {
        if (sysConfigRepository == null || jobConfigRepository == null || legacyKey == null || taskCode == null) {
            return null;
        }
        List<SysConfig> matches = sysConfigRepository.findByConfigKeyInOrderByIdAsc(List.of(legacyKey));
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        JobConfig job = jobConfigRepository.findByTaskCode(taskCode);
        if (job == null) {
            return null;
        }
        if (job.getWatermarkDate() != null) {
            sysConfigRepository.deleteAll(matches);
            return job.getWatermarkDate();
        }
        LocalDate legacyDate = null;
        for (SysConfig row : matches) {
            if (row == null || !row.isEnabled() || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
                continue;
            }
            try {
                legacyDate = LocalDate.parse(row.getConfigValue().trim());
                break;
            } catch (RuntimeException ignored) {
            }
        }
        if (legacyDate == null) {
            return null;
        }
        jobConfigRepository.updateWatermarkDate(taskCode, legacyDate);
        sysConfigRepository.deleteAll(matches);
        return legacyDate;
    }
}
