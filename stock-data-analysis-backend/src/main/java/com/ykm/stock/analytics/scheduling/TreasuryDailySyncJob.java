package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.TreasuryDirectClient;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.service.market.TreasuryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/** Quartz 美國國債殖利率同步工作；下載當月 feed 並於近 60 日有效 10Y 觀測不足 25 筆時自動回補前兩個月。 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
@Component(TaskCode.TREASURY_DAILY_SYNC)
public class TreasuryDailySyncJob implements Job {
    private static final ZoneId SOURCE_ZONE = ZoneId.of("America/New_York");
    private static final long HISTORY_LOOKBACK_DAYS = 60L;
    private static final long MIN_VALID_TEN_YEAR_OBSERVATIONS = 25L;
    private static final int MAX_BACKFILL_PRIOR_MONTHS = 2;

    private final TreasuryDirectClient treasuryDirectClient;
    private final TreasuryService treasuryService;
    private final TreasuryParYieldDailyRepository treasuryParYieldDailyRepository;
    private final DateTimeService dateTimeService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            LocalDate todayNy = dateTimeService.nowZonedDateTime(SOURCE_ZONE).toLocalDate();
            YearMonth currentMonth = YearMonth.from(todayNy);

            // 1. 下載並保存當月 Treasury 殖利率 feed（月初空白時不提前返回，保留後續跨月歷史回補檢查）
            log.info("Treasury daily sync started | phase=DOWNLOAD month={}", currentMonth);
            TreasuryYieldCurveFeed currentFeed = treasuryDirectClient.getCurrentMonthYieldCurve();
            LocalDate sourceDataThrough = latestObservationDate(currentFeed);
            int savedFeeds = 0;
            if (sourceDataThrough != null) {
                log.info("Treasury daily sync progress | phase=PERSIST month={} sourceThrough={}", currentMonth, sourceDataThrough);
                treasuryService.saveOrUpdateTreasuryParYieldDailies(currentFeed);
                savedFeeds++;
            } else {
                log.info("Treasury current-month feed has no dated entries yet | month={}", currentMonth);
            }

            // 2. 核對近 60 日有效 10Y 觀測數；不足 25 筆時依序回補前 1 與前 2 個月（防月初空月、2 月 19 交易日與跨年斷鏈）
            LocalDate lookbackFrom = todayNy.minusDays(HISTORY_LOOKBACK_DAYS);
            long validCount = treasuryParYieldDailyRepository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(lookbackFrom);
            int backfilledMonths = 0;
            for (int offset = 1; offset <= MAX_BACKFILL_PRIOR_MONTHS && validCount < MIN_VALID_TEN_YEAR_OBSERVATIONS; offset++) {
                YearMonth priorMonth = currentMonth.minusMonths(offset);
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
            if (validCount < MIN_VALID_TEN_YEAR_OBSERVATIONS) {
                log.warn("Treasury daily sync completed with insufficient 10Y history | savedFeeds={} backfilledMonths={} sourceThrough={} validTenYearCount={} required={}",
                        savedFeeds, backfilledMonths, sourceDataThrough, validCount, MIN_VALID_TEN_YEAR_OBSERVATIONS);
                return;
            }
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
