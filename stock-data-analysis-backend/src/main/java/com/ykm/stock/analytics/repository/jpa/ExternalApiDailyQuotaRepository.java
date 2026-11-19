package com.ykm.stock.analytics.repository.jpa;

import com.ykm.stock.analytics.model.entity.ExternalApiDailyQuota;
import com.ykm.stock.analytics.model.entity.key.ExternalApiDailyQuotaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;

public interface ExternalApiDailyQuotaRepository extends JpaRepository<ExternalApiDailyQuota, ExternalApiDailyQuotaId> {
    @Modifying
    @Query(value = """
            insert into external_api_daily_quota(provider, quota_date, attempts_used, quota_exhausted, updated_at)
            values (:provider, :quotaDate, 1, false, :now)
            on conflict (provider, quota_date) do update
            set attempts_used = external_api_daily_quota.attempts_used + 1,
                updated_at = excluded.updated_at
            where external_api_daily_quota.quota_exhausted = false
              and external_api_daily_quota.attempts_used < :dailyLimit
            """, nativeQuery = true)
    int tryIncrementDailyQuota(@Param("provider") String provider,
                               @Param("quotaDate") LocalDate quotaDate,
                               @Param("dailyLimit") int dailyLimit,
                               @Param("now") Instant now);

    @Modifying
    @Query(value = """
            insert into external_api_daily_quota(provider, quota_date, attempts_used, quota_exhausted, updated_at)
            values (:provider, :quotaDate, :dailyLimit, true, :now)
            on conflict (provider, quota_date) do update
            set quota_exhausted = true,
                updated_at = excluded.updated_at
            """, nativeQuery = true)
    int markQuotaExhausted(@Param("provider") String provider,
                           @Param("quotaDate") LocalDate quotaDate,
                           @Param("dailyLimit") int dailyLimit,
                           @Param("now") Instant now);
}
