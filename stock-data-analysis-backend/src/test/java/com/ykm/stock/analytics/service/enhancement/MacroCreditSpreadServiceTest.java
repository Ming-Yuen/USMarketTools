package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.orm.entity.SysConfig;
import com.ykm.stock.analytics.client.FredCreditSpreadClient;
import com.ykm.stock.analytics.client.FredCreditSpreadClient.CreditSpreadPoint;
import com.ykm.stock.analytics.constant.TaskCode;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.entity.MacroCreditSpreadDaily;
import com.ykm.stock.analytics.repository.jpa.JobConfigRepository;
import com.ykm.stock.analytics.repository.jpa.MacroCreditSpreadDailyRepository;
import com.ykm.stock.analytics.repository.jpa.PortfolioSysConfigRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MacroCreditSpreadServiceTest {
    @Mock private FredCreditSpreadClient fredClient;
    @Mock private MacroCreditSpreadDailyRepository repository;
    @Mock private MarketStatusService calendar;
    @Mock private DateTimeService dateTimeService;
    @Mock private TransactionTemplate transactionTemplate;

    private MacroCreditSpreadService service;

    @BeforeEach
    void setUp() {
        service = new MacroCreditSpreadService(fredClient, repository, calendar, dateTimeService, transactionTemplate);
        lenient().when(calendar.getTradingDays(any(), any())).thenReturn(List.of(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 19)));
    }

    @Test
    void elevatedDoesNotTriggerMacroCautionHalvingWhileStressAndWideningDo() {
        LocalDate asOf = LocalDate.of(2026, 11, 19);
        // 4.65% with prior 20 mean 4.55% (+10bps): ELEVATED only -> display warning, do NOT halve position
        List<MacroCreditSpreadDaily> elevatedRows = buildDescendingRows(asOf, new BigDecimal("4.65"), new BigDecimal("4.55"));
        CreditSpreadSummaryDto elevated = service.evaluateFromDescendingRows(elevatedRows, asOf);
        assertThat(elevated.status()).isEqualTo(MacroCreditSpreadService.STATUS_ELEVATED);
        assertThat(elevated.macroCautionTriggered()).isFalse();
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(elevated)).isEmpty();

        // 5.15% with prior 20 mean 4.90%: STRESS -> triggers HY_OAS_STRESS
        List<MacroCreditSpreadDaily> stressRows = buildDescendingRows(asOf, new BigDecimal("5.15"), new BigDecimal("4.90"));
        CreditSpreadSummaryDto stress = service.evaluateFromDescendingRows(stressRows, asOf);
        assertThat(stress.status()).isEqualTo(MacroCreditSpreadService.STATUS_STRESS);
        assertThat(stress.macroCautionTriggered()).isTrue();
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(stress))
                .containsExactly(MacroCreditSpreadService.TRIGGER_HY_OAS_STRESS);

        // 3.90% with prior 20 mean 3.30% (+60bps): WIDENING -> triggers HY_OAS_WIDENING
        List<MacroCreditSpreadDaily> wideningRows = buildDescendingRows(asOf, new BigDecimal("3.90"), new BigDecimal("3.30"));
        CreditSpreadSummaryDto widening = service.evaluateFromDescendingRows(wideningRows, asOf);
        assertThat(widening.status()).isEqualTo(MacroCreditSpreadService.STATUS_WIDENING);
        assertThat(widening.deltaVsPrior20Bps()).isEqualByComparingTo("60.0");
        assertThat(widening.macroCautionTriggered()).isTrue();
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(widening))
                .containsExactly(MacroCreditSpreadService.TRIGGER_HY_OAS_WIDENING);

        // 3.7995% with prior 20 mean 3.30% (+49.95 bps -> rounds to 50.0 for display, raw < 50 -> must stay NORMAL)
        List<MacroCreditSpreadDaily> boundaryRows = buildDescendingRows(asOf, new BigDecimal("3.7995"), new BigDecimal("3.30"));
        CreditSpreadSummaryDto boundary = service.evaluateFromDescendingRows(boundaryRows, asOf);
        assertThat(boundary.status()).isEqualTo(MacroCreditSpreadService.STATUS_NORMAL);
        assertThat(boundary.macroCautionTriggered()).isFalse();
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(boundary)).isEmpty();
    }

    @Test
    void resolveMacroCautionTriggersFollowsUnroundedStatusAtRoundingBoundaries() {
        LocalDate asOf = LocalDate.of(2026, 11, 19);
        // 4.996% rounds to 5.00% for display, but raw < 5.00% -> status is WIDENING (+69.6 bps), must NOT emit HY_OAS_STRESS
        List<MacroCreditSpreadDaily> nearStressRows = buildDescendingRows(asOf, new BigDecimal("4.996"), new BigDecimal("4.30"));
        CreditSpreadSummaryDto nearStress = service.evaluateFromDescendingRows(nearStressRows, asOf);
        assertThat(nearStress.oasPct()).isEqualByComparingTo("5.00");
        assertThat(nearStress.status()).isEqualTo(MacroCreditSpreadService.STATUS_WIDENING);
        assertThat(nearStress.macroCautionTriggered()).isTrue();
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(nearStress))
                .containsExactly(MacroCreditSpreadService.TRIGGER_HY_OAS_WIDENING);

        // 5.20% vs prior 20 mean 4.50% (+70 bps): both STRESS and WIDENING must be emitted simultaneously
        List<MacroCreditSpreadDaily> dualRows = buildDescendingRows(asOf, new BigDecimal("5.20"), new BigDecimal("4.50"));
        CreditSpreadSummaryDto dual = service.evaluateFromDescendingRows(dualRows, asOf);
        assertThat(dual.status()).isEqualTo(MacroCreditSpreadService.STATUS_STRESS);
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(dual))
                .containsExactly(MacroCreditSpreadService.TRIGGER_HY_OAS_STRESS, MacroCreditSpreadService.TRIGGER_HY_OAS_WIDENING);

        // 5.10% vs prior 20 mean 4.6004% (+49.96 bps rounds to 50.0, raw < 50): STRESS only, must NOT emit HY_OAS_WIDENING
        List<MacroCreditSpreadDaily> stressNearWideningRows = buildDescendingRows(asOf, new BigDecimal("5.10"), new BigDecimal("4.6004"));
        CreditSpreadSummaryDto stressNearWidening = service.evaluateFromDescendingRows(stressNearWideningRows, asOf);
        assertThat(stressNearWidening.deltaVsPrior20Bps()).isEqualByComparingTo("50.0");
        assertThat(stressNearWidening.status()).isEqualTo(MacroCreditSpreadService.STATUS_STRESS);
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(stressNearWidening))
                .containsExactly(MacroCreditSpreadService.TRIGGER_HY_OAS_STRESS);
    }

    @Test
    void staleObservationShowsDateWithoutTriggeringFreshMacroCaution() {
        LocalDate obsDate = LocalDate.of(2026, 11, 1);
        LocalDate asOf = LocalDate.of(2026, 11, 19);
        lenient().when(calendar.getTradingDays(obsDate, asOf)).thenReturn(List.of(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 10)));
        List<MacroCreditSpreadDaily> staleRows = buildDescendingRows(obsDate, new BigDecimal("5.50"), new BigDecimal("4.50"));
        CreditSpreadSummaryDto dto = service.evaluateFromDescendingRows(staleRows, asOf);
        assertThat(dto.stale()).isTrue();
        assertThat(dto.status()).isEqualTo(MacroCreditSpreadService.STATUS_STALE);
        assertThat(dto.macroCautionTriggered()).isFalse();
        assertThat(MacroCreditSpreadService.resolveMacroCautionTriggers(dto)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullWatermarkBackfillsFromSysConfigAndSetsHistoricalObservedAtNullForHistoricalVisibility() {
        JobConfigRepository jobConfigRepo = mock(JobConfigRepository.class);
        PortfolioSysConfigRepository sysConfigRepo = mock(PortfolioSysConfigRepository.class);
        ReflectionTestUtils.setField(service, "jobConfigRepository", jobConfigRepo);
        ReflectionTestUtils.setField(service, "sysConfigRepository", sysConfigRepo);

        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        when(dateTimeService.now()).thenReturn(now);
        when(fredClient.isConfigured()).thenReturn(true);
        when(jobConfigRepo.findWatermarkDateByTaskCode(TaskCode.CREDIT_SPREAD_DAILY_SYNC)).thenReturn(Optional.empty());
        SysConfig startCfg = new SysConfig();
        startCfg.setConfigKey(PortfolioSysConfigRepository.KEY_SYNC_HISTORY_START_DATE);
        startCfg.setConfigValue("2016-01-01");
        startCfg.setEnabled(true);
        when(sysConfigRepo.findByConfigKeyInOrderByIdAsc(List.of(PortfolioSysConfigRepository.KEY_SYNC_HISTORY_START_DATE)))
                .thenReturn(List.of(startCfg));

        LocalDate histDate = LocalDate.of(2020, 3, 23);
        LocalDate recentDate = LocalDate.of(2026, 11, 19);
        when(fredClient.fetchHighYieldOas(LocalDate.of(2016, 1, 1), LocalDate.of(2026, 11, 21))).thenReturn(List.of(
                new CreditSpreadPoint(histDate, new BigDecimal("10.8700"), histDate, MacroCreditSpreadDaily.SERIES_HY_OAS),
                new CreditSpreadPoint(recentDate, new BigDecimal("3.1000"), recentDate, MacroCreditSpreadDaily.SERIES_HY_OAS)));
        when(repository.findAllById(any())).thenReturn(List.of());
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(new SimpleTransactionStatus());
        });

        MacroCreditSpreadService.SyncResult result = service.syncLatest();

        assertThat(result.fetchedCount()).isEqualTo(2);
        assertThat(result.savedCount()).isEqualTo(2);
        assertThat(result.latestObservationDate()).isEqualTo(recentDate);
        verify(jobConfigRepo).updateWatermarkDate(TaskCode.CREDIT_SPREAD_DAILY_SYNC, recentDate);

        ArgumentCaptor<Collection<MacroCreditSpreadDaily>> savedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(repository).saveAll(savedCaptor.capture());
        List<MacroCreditSpreadDaily> savedRows = new ArrayList<>(savedCaptor.getValue());
        MacroCreditSpreadDaily savedHist = savedRows.stream().filter(r -> histDate.equals(r.getObservationDate())).findFirst().orElseThrow();
        MacroCreditSpreadDaily savedRecent = savedRows.stream().filter(r -> recentDate.equals(r.getObservationDate())).findFirst().orElseThrow();
        assertThat(savedHist.getObservedAt()).isNull();
        assertThat(savedRecent.getObservedAt()).isEqualTo(now);

        // Historical query with 2020-03-23 observationCutoff must read the backfilled historical row (observedAt == null)
        when(repository.findTop30ByObservationDateLessThanEqualOrderByObservationDateDesc(histDate))
                .thenReturn(List.of(savedHist));
        CreditSpreadSummaryDto histSummary = service.evaluateAt(histDate, Instant.parse("2020-03-23T20:00:00Z"));
        assertThat(histSummary.status()).isEqualTo(MacroCreditSpreadService.STATUS_STRESS);
        assertThat(histSummary.oasPct()).isEqualByComparingTo("10.87");

        // Re-syncing a >7d row whose observedAt was captured live (within 7d of observationDate) must preserve its observedAt
        LocalDate liveHistDate = LocalDate.of(2026, 11, 1);
        Instant liveObservedAt = Instant.parse("2026-11-02T12:00:00Z");
        MacroCreditSpreadDaily existingLive = new MacroCreditSpreadDaily(
                liveHistDate, MacroCreditSpreadDaily.SERIES_HY_OAS, new BigDecimal("3.2000"), liveHistDate, liveObservedAt);
        when(repository.findAllById(any())).thenReturn(List.of(existingLive));
        int changed = service.persistPoints(List.of(
                new CreditSpreadPoint(liveHistDate, new BigDecimal("3.2000"), liveHistDate, MacroCreditSpreadDaily.SERIES_HY_OAS)));
        assertThat(changed).isZero();
        assertThat(existingLive.getObservedAt()).isEqualTo(liveObservedAt);
    }

    private List<MacroCreditSpreadDaily> buildDescendingRows(LocalDate latestDate, BigDecimal latestVal, BigDecimal priorVal) {
        List<MacroCreditSpreadDaily> rows = new ArrayList<>();
        rows.add(new MacroCreditSpreadDaily(latestDate, MacroCreditSpreadDaily.SERIES_HY_OAS, latestVal, latestDate, Instant.parse("2026-11-19T12:00:00Z")));
        for (int i = 1; i <= 20; i++) {
            LocalDate d = latestDate.minusDays(i);
            rows.add(new MacroCreditSpreadDaily(d, MacroCreditSpreadDaily.SERIES_HY_OAS, priorVal, d, Instant.parse("2026-11-19T12:00:00Z")));
        }
        return rows;
    }
}
