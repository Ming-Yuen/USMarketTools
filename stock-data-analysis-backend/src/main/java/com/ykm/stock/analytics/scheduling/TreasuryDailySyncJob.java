package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.TreasuryDirectClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.service.market.TreasuryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Quartz 美國國債殖利率同步工作；無水位時自 sync_history_start_date 逐年全量回補，已有水位時同步當月及重疊月份。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.TREASURY_DAILY_SYNC)
public class TreasuryDailySyncJob implements Job {
    private static final ZoneId SOURCE_ZONE = ZoneId.of("America/New_York");
    private static final long HISTORY_LOOKBACK_DAYS = 60L;
    private static final long INCREMENTAL_OVERLAP_DAYS = 45L;
    private static final long MIN_VALID_TEN_YEAR_OBSERVATIONS = 25L;
    private static final int MAX_BACKFILL_PRIOR_MONTHS = 2;

    private final TreasuryDirectClient treasuryDirectClient;
    private final TreasuryService treasuryService;
    private final TreasuryParYieldDailyRepository treasuryParYieldDailyRepository;
    private final DateTimeService dateTimeService;
    @Autowired(required = false)
    private JobConfigRepository jobConfigRepository;
    @Autowired(required = false)
    private PortfolioSysConfigRepository sysConfigRepository;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            LocalDate todayNy = dateTimeService.nowZonedDateTime(SOURCE_ZONE).toLocalDate();
            YearMonth currentMonth = YearMonth.from(todayNy);
            LocalDate watermark = jobConfigRepository == null
                    ? null
                    : jobConfigRepository.findWatermarkDateByTaskCode(TaskCode.TREASURY_DAILY_SYNC).orElse(null);

            // 1. 無同步水位時（job_config.watermark_date IS NULL）：自 sync_history_start_date 逐年全量拉取至今年
            if (jobConfigRepository != null && watermark == null) {
                LocalDate historyStart = PortfolioSysConfigRepository.resolveSyncHistoryStartDate(sysConfigRepository);
                if (historyStart.isAfter(todayNy)) {
                    log.warn("Treasury full historical sync skipped: startDate after todayNy | startDate={} todayNy={}", historyStart, todayNy);
                    return;
                }
                int startYear = historyStart.getYear();
                int endYear = todayNy.getYear();
                int savedFeeds = 0;
                boolean missingPriorYear = false;
                LocalDate sourceDataThrough = null;
                log.info("Treasury full historical sync started | startYear={} endYear={}", startYear, endYear);
                for (int year = startYear; year <= endYear; year++) {
                    TreasuryYieldCurveFeed yearFeed = treasuryDirectClient.getYieldCurveForYear(year);
                    LocalDate yearThrough = latestObservationDate(yearFeed);
                    if (yearThrough != null) {
                        treasuryService.saveOrUpdateTreasuryParYieldDailies(yearFeed);
                        savedFeeds++;
                        if (sourceDataThrough == null || yearThrough.isAfter(sourceDataThrough)) {
                            sourceDataThrough = yearThrough;
                        }
                    } else if (year < endYear) {
                        missingPriorYear = true;
                        log.warn("Treasury full historical sync returned empty prior year | year={}", year);
                    }
                }
                long validCount = treasuryParYieldDailyRepository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(
                        todayNy.minusDays(HISTORY_LOOKBACK_DAYS));
                if (savedFeeds == 0 || sourceDataThrough == null || missingPriorYear || validCount < MIN_VALID_TEN_YEAR_OBSERVATIONS) {
                    log.warn("Treasury full historical sync incomplete; watermark not advanced | savedFeeds={} sourceThrough={} missingPriorYear={} validTenYearCount={} required={}",
                            savedFeeds, sourceDataThrough, missingPriorYear, validCount, MIN_VALID_TEN_YEAR_OBSERVATIONS);
                    return;
                }
                JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.TREASURY_DAILY_SYNC, sourceDataThrough);
                log.info("Treasury full historical sync completed | savedFeeds={} sourceThrough={} validTenYearCount={}",
                        savedFeeds, sourceDataThrough, validCount);
                return;
            }

            // 2. 日常增量模式：下載並保存當月 feed，再同步重疊月份（watermark - 45d）與近 60 日有效 10Y 觀測數保護
            log.info("Treasury daily sync started | phase=DOWNLOAD month={} watermark={}", currentMonth, watermark);
            TreasuryYieldCurveFeed currentFeed = treasuryDirectClient.getCurrentMonthYieldCurve();
            LocalDate sourceDataThrough = latestObservationDate(currentFeed);
            int savedFeeds = 0;
            Set<YearMonth> fetchedMonths = new HashSet<>();
            fetchedMonths.add(currentMonth);
            if (sourceDataThrough != null) {
                log.info("Treasury daily sync progress | phase=PERSIST month={} sourceThrough={}", currentMonth, sourceDataThrough);
                treasuryService.saveOrUpdateTreasuryParYieldDailies(currentFeed);
                savedFeeds++;
            } else {
                log.info("Treasury current-month feed has no dated entries yet | month={}", currentMonth);
            }

            int backfilledMonths = 0;
            boolean missingOverlapMonth = false;
            if (watermark != null) {
                YearMonth overlapStart = YearMonth.from(watermark.minusDays(INCREMENTAL_OVERLAP_DAYS));
                YearMonth cursor = currentMonth.minusMonths(1);
                while (!cursor.isBefore(overlapStart)) {
                    fetchedMonths.add(cursor);
                    TreasuryYieldCurveFeed overlapFeed = treasuryDirectClient.getYieldCurveForMonth(cursor);
                    LocalDate overlapThrough = latestObservationDate(overlapFeed);
                    if (overlapThrough != null) {
                        treasuryService.saveOrUpdateTreasuryParYieldDailies(overlapFeed);
                        savedFeeds++;
                        backfilledMonths++;
                        if (sourceDataThrough == null || overlapThrough.isAfter(sourceDataThrough)) {
                            sourceDataThrough = overlapThrough;
                        }
                    } else {
                        missingOverlapMonth = true;
                        log.warn("Treasury overlap month returned empty feed | month={}", cursor);
                    }
                    cursor = cursor.minusMonths(1);
                }
            }

            // 3. 核對近 60 日有效 10Y 觀測數；不足 25 筆時依序補齊尚未抓取的前 1 與前 2 個月（防月初空月、2 月 19 交易日與跨年斷鏈）
            LocalDate lookbackFrom = todayNy.minusDays(HISTORY_LOOKBACK_DAYS);
            long validCount = treasuryParYieldDailyRepository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(lookbackFrom);
            for (int offset = 1; offset <= MAX_BACKFILL_PRIOR_MONTHS && validCount < MIN_VALID_TEN_YEAR_OBSERVATIONS; offset++) {
                YearMonth priorMonth = currentMonth.minusMonths(offset);
                if (!fetchedMonths.add(priorMonth)) {
                    continue;
                }
                log.info("Treasury history backfill started | month={} validTenYearCount={}", priorMonth, validCount);
                TreasuryYieldCurveFeed priorFeed = treasuryDirectClient.getYieldCurveForMonth(priorMonth);
                LocalDate priorThrough = latestObservationDate(priorFeed);
                if (priorThrough != null) {
                    treasuryService.saveOrUpdateTreasuryParYieldDailies(priorFeed);
                    savedFeeds++;
                    backfilledMonths++;
                    if (sourceDataThrough == null || priorThrough.isAfter(sourceDataThrough)) {
                        sourceDataThrough = priorThrough;
                    }
                    validCount = treasuryParYieldDailyRepository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(lookbackFrom);
                }
            }

            if (savedFeeds == 0) {
                log.warn("Treasury daily sync completed with zero persisted feeds | validTenYearCount={} required={} reason=NO_OBSERVABLE_RATE",
                        validCount, MIN_VALID_TEN_YEAR_OBSERVATIONS);
                return;
            }
            if (validCount < MIN_VALID_TEN_YEAR_OBSERVATIONS || missingOverlapMonth) {
                log.warn("Treasury daily sync incomplete; watermark not advanced | savedFeeds={} backfilledMonths={} sourceThrough={} missingOverlapMonth={} validTenYearCount={} required={}",
                        savedFeeds, backfilledMonths, sourceDataThrough, missingOverlapMonth, validCount, MIN_VALID_TEN_YEAR_OBSERVATIONS);
                return;
            }
            JobConfigRepository.advanceWatermarkDate(jobConfigRepository, TaskCode.TREASURY_DAILY_SYNC, sourceDataThrough);
            log.info("Treasury daily sync completed | savedFeeds={} backfilledMonths={} sourceThrough={} validTenYearCount={}",
                    savedFeeds, backfilledMonths, sourceDataThrough, validCount);
        } catch (Exception ex) {
            log.error("Treasury daily nominal par yield curve synchronization failed.", ex);
            throw new JobExecutionException("Treasury daily nominal par yield curve synchronization failed.", ex, false);
        }
    }

    // 用途：從 Treasury feed 中找出最新具實際 2Y 或 10Y 殖利率之觀測日期，與 TreasuryService 寫入條件對齊。
    private LocalDate latestObservationDate(TreasuryYieldCurveFeed feed) {
        if (feed == null || feed.getEntries() == null) return null;
        return feed.getEntries().stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.getContent() != null && entry.getContent().getProperties() != null
                        && entry.getContent().getProperties().getDate() != null
                        && (entry.getContent().getProperties().getTenYear() != null
                            || entry.getContent().getProperties().getTwoYear() != null))
                .map(entry -> entry.getContent().getProperties().getDate().toLocalDate())
                .max(LocalDate::compareTo)
                .orElse(null);
    }

}
