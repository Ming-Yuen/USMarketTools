package com.ykm.stock.analytics.service.enhancement;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.FredCreditSpreadClient;
import com.ykm.stock.analytics.model.dto.stockInsight.enhancement.EnhancementDtos.CreditSpreadSummaryDto;
import com.ykm.stock.analytics.model.entity.MacroCreditSpreadDaily;
import com.ykm.stock.analytics.repository.jpa.MacroCreditSpreadDailyRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

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
