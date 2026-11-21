package com.ykm.stock.analytics.scheduling;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.TreasuryDirectClient;
import com.ykm.stock.analytics.model.dto.treasury.TreasuryYieldCurveFeed;
import com.ykm.stock.analytics.repository.jpa.TreasuryParYieldDailyRepository;
import com.ykm.stock.analytics.service.market.TreasuryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TreasuryDailySyncJobTests {
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private TreasuryDirectClient client;
    private TreasuryService service;
    private TreasuryParYieldDailyRepository repository;
    private DateTimeService clock;
    private TreasuryDailySyncJob job;

    @BeforeEach
    void setUp() {
        client = mock(TreasuryDirectClient.class);
        service = mock(TreasuryService.class);
        repository = mock(TreasuryParYieldDailyRepository.class);
        clock = mock(DateTimeService.class);
        when(clock.nowZonedDateTime(NY)).thenReturn(ZonedDateTime.of(2026, 10, 17, 19, 15, 0, 0, NY));
        when(repository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(any())).thenReturn(30L);
        job = new TreasuryDailySyncJob(client, service, repository, clock);
    }

    // Scenario：來源有 10/16 與 10/17 兩筆，且近 60 日已有 >=25 筆有效 10Y 觀測時，只保存當月 feed 不額外回補前月。
    @Test
    void savesDatedFeedWithoutBackfillWhenHistorySufficient() throws Exception {
        JobExecutionContext context = mock(JobExecutionContext.class);
        TreasuryYieldCurveFeed feed = feed(
                LocalDateTime.of(2026, 10, 16, 0, 0),
                LocalDateTime.of(2026, 10, 17, 0, 0));
        when(client.getCurrentMonthYieldCurve()).thenReturn(feed);

        job.execute(context);

        verify(service).saveOrUpdateTreasuryParYieldDailies(feed);
        verify(client, never()).getYieldCurveForMonth(any());
    }

    // Scenario：XML 缺少 NEW_DATE 且歷史觀測充足時不落庫，等待下一輪重新取得有效來源。
    @Test
    void undatedFeedDoesNotSaveWhenHistorySufficient() throws Exception {
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(client.getCurrentMonthYieldCurve()).thenReturn(feed((LocalDateTime) null));

        job.execute(context);

        verify(service, never()).saveOrUpdateTreasuryParYieldDailies(any());
        verify(client, never()).getYieldCurveForMonth(any());
    }

    // Scenario：月初空月（如 1/2 跨年或 3/1 緊接 2 月 19 個交易日）當月 XML 為空，不得提前返回；必須依序回補前 1 與前 2 個月直到近 60 日有效 10Y >= 25 筆。
    @Test
    void emptyCurrentMonthStillBackfillsPriorTwoMonthsAcrossYearRollover() throws Exception {
        when(clock.nowZonedDateTime(NY)).thenReturn(ZonedDateTime.of(2026, 1, 2, 18, 15, 0, 0, NY));
        JobExecutionContext context = mock(JobExecutionContext.class);
        TreasuryYieldCurveFeed emptyJan = feed();
        TreasuryYieldCurveFeed decFeed = feed(LocalDateTime.of(2025, 12, 31, 0, 0));
        TreasuryYieldCurveFeed novFeed = feed(LocalDateTime.of(2025, 11, 28, 0, 0));

        when(client.getCurrentMonthYieldCurve()).thenReturn(emptyJan);
        when(client.getYieldCurveForMonth(YearMonth.of(2025, 12))).thenReturn(decFeed);
        when(client.getYieldCurveForMonth(YearMonth.of(2025, 11))).thenReturn(novFeed);
        when(repository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(LocalDate.of(2025, 11, 3)))
                .thenReturn(0L, 19L, 38L);

        job.execute(context);

        verify(service, never()).saveOrUpdateTreasuryParYieldDailies(emptyJan);
        verify(client).getYieldCurveForMonth(YearMonth.of(2025, 12));
        verify(client).getYieldCurveForMonth(YearMonth.of(2025, 11));
        verify(service).saveOrUpdateTreasuryParYieldDailies(decFeed);
        verify(service).saveOrUpdateTreasuryParYieldDailies(novFeed);
    }

    // Scenario：僅回補前 1 個月即達 >=25 筆時，不再額外抓取前 2 個月。
    @Test
    void stopsBackfillAfterPriorOneMonthOnceValidCountReachesThreshold() throws Exception {
        when(clock.nowZonedDateTime(NY)).thenReturn(ZonedDateTime.of(2026, 11, 21, 18, 15, 0, 0, NY));
        JobExecutionContext context = mock(JobExecutionContext.class);
        TreasuryYieldCurveFeed novFeed = feed(LocalDateTime.of(2026, 11, 20, 0, 0));
        TreasuryYieldCurveFeed octFeed = feed(LocalDateTime.of(2026, 10, 30, 0, 0));

        when(client.getCurrentMonthYieldCurve()).thenReturn(novFeed);
        when(client.getYieldCurveForMonth(YearMonth.of(2026, 10))).thenReturn(octFeed);
        when(repository.countByObservationDateGreaterThanEqualAndTenYearIsNotNull(LocalDate.of(2026, 9, 22)))
                .thenReturn(14L, 35L);

        job.execute(context);

        verify(service).saveOrUpdateTreasuryParYieldDailies(novFeed);
        verify(client).getYieldCurveForMonth(YearMonth.of(2026, 10));
        verify(service).saveOrUpdateTreasuryParYieldDailies(octFeed);
        verify(client, never()).getYieldCurveForMonth(YearMonth.of(2026, 9));
    }

    // Scenario：Treasury 下載或寫庫失敗，必須拋出例外，不能偽造成功的資料。
    @Test
    void fetchOrSaveFailurePropagatesWithoutPersistingInvalidData() {
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(client.getCurrentMonthYieldCurve()).thenThrow(new IllegalStateException("treasury down"));

        JobExecutionException fetchFailure = assertThrows(JobExecutionException.class,
                () -> job.execute(context));
        assertThat(fetchFailure.refireImmediately()).isFalse();
        verify(service, never()).saveOrUpdateTreasuryParYieldDailies(any());

        TreasuryYieldCurveFeed feed = feed(LocalDateTime.of(2026, 10, 17, 0, 0));
        org.mockito.Mockito.doReturn(feed).when(client).getCurrentMonthYieldCurve();
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(service).saveOrUpdateTreasuryParYieldDailies(feed);
        JobExecutionContext saveContext = mock(JobExecutionContext.class);
        assertThrows(JobExecutionException.class, () -> job.execute(saveContext));
    }

    private TreasuryYieldCurveFeed feed(LocalDateTime... dates) {
        TreasuryYieldCurveFeed feed = new TreasuryYieldCurveFeed();
        List<TreasuryYieldCurveFeed.Entry> entries = new ArrayList<>();
        for (LocalDateTime date : dates) {
            TreasuryYieldCurveFeed.Entry entry = new TreasuryYieldCurveFeed.Entry();
            if (date != null) {
                TreasuryYieldCurveFeed.Properties properties = new TreasuryYieldCurveFeed.Properties();
                properties.setDate(date);
                properties.setTenYear(new java.math.BigDecimal("4.25"));
                TreasuryYieldCurveFeed.Content content = new TreasuryYieldCurveFeed.Content();
                content.setProperties(properties);
                entry.setContent(content);
            }
            entries.add(entry);
        }
        feed.setEntries(entries);
        return feed;
    }
}
