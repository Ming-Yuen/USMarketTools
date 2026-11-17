package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive;
import com.ykm.stock.analytics.client.SecSubmissionsArchive.CompanySubmissions;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.model.entity.SecFinancialFact;
import com.ykm.stock.analytics.model.entity.Stock;
import com.ykm.stock.analytics.model.entity.StockStatus;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.SecFinancialFactRepository;
import com.ykm.stock.analytics.repository.jpa.StockRepository;
import com.ykm.stock.analytics.repository.jpa.StockStatusRepository;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobExecutionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecFinancialFactsDailySyncJobTests {
    private static final LocalDate BACKFILL_START = LocalDate.of(2014, 1, 1);

    private final StockRepository stocks = mock(StockRepository.class);
    private final SecFinancialFactRepository facts = mock(SecFinancialFactRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final SecCompanyFactsArchive archives = mock(SecCompanyFactsArchive.class);
    private final SecCompanyFactsArchive.Snapshot archive = mock(SecCompanyFactsArchive.Snapshot.class);
    private final SecSubmissionsArchive submissionsArchive = mock(SecSubmissionsArchive.class);
    private final SecSubmissionsArchive.Snapshot submissions = mock(SecSubmissionsArchive.Snapshot.class);
    private final SecFinancialFactConverter converter = mock(SecFinancialFactConverter.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final LocalDateTime writeTime = LocalDateTime.parse("2026-11-14T03:06:08.156");
    private final LocalDate reportPeriod = LocalDate.parse("2026-09-30");
    private final StockStatus first = status("F1");
    private final StockStatus secondClass = status("F2");
    private final StockStatus other = status("F3");
    private final ActiveStockWithStatus firstTarget = target("0000320193", first);
    private final ActiveStockWithStatus secondTarget = target("0000320193", secondClass);
    private final ActiveStockWithStatus otherTarget = target("0000000002", other);
    private final MarketStatusService market = new MarketStatusService(clock);
    private final SecFinancialFactsDailySyncJob job = new SecFinancialFactsDailySyncJob(
            stocks, facts, statuses, archives, submissionsArchive, converter, transactions,
            entityManager, clock, new SecIssuerHistory(""));

    @BeforeEach
    void setup() {
        when(clock.now()).thenReturn(Instant.parse("2026-11-13T19:06:08.156Z"));
        when(clock.nowLocalDateTime()).thenReturn(writeTime);
        when(stocks.findSecFinancialFactsSyncStocks(any())).thenReturn(List.of(firstTarget, secondTarget, otherTarget));
        when(statuses.findAllByIdForUpdate(List.of("F1", "F2"))).thenReturn(List.of(first, secondClass));
        when(statuses.findAllByIdForUpdate(List.of("F3"))).thenReturn(List.of(other));
        when(archives.prepare()).thenReturn(archive);
        when(archive.version()).thenReturn("v1");
        when(submissionsArchive.prepare()).thenReturn(submissions);
        when(submissions.findCompany(anyString())).thenReturn(Optional.empty());
        when(converter.convert(anyString(), any(), any(), any(LocalDate.class), anyMap())).thenReturn(List.of());
        when(transactions.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    /** Scenario：整份 CIK JSON 比對能抓到早於已入庫水位的舊季 10-Q/A 修訂，不再因 periodFrom 截斷漏讀。 */
    @Test
    void wholeCikDiffCapturesOlderQuarterAmendmentEvenAfterWatermarkAdvanced() throws Exception {
        String cik = "0000320193";
        LocalDate nextPeriod = LocalDate.parse("2026-12-31");
        LocalDate olderAmendedPeriod = LocalDate.parse("2025-06-30");
        first.setSecFinancialFactsDate(reportPeriod);
        secondClass.setSecFinancialFactsDate(reportPeriod);
        SecFinancialFact existing = fact("0000320193-26-000001", "100");
        existing.setAvailableFromDate(LocalDate.of(2026, 11, 4));
        when(facts.findByCikAndPeriodEndGreaterThanEqual(cik, BACKFILL_START)).thenReturn(List.of(existing));
        when(archive.read(cik)).thenReturn(new ObjectMapper().readTree("""
                {"cik":320193,"facts":{"us-gaap":{"Assets":{"units":{"USD":[
                  {"end":"2026-09-30","val":100,"filed":"2026-11-03","accn":"0000320193-26-000001","form":"10-Q"},
                  {"end":"2026-12-31","val":120,"filed":"2027-01-28","accn":"0000320193-27-000001","form":"10-Q"},
                  {"end":"2025-06-30","val":95,"filed":"2027-01-29","accn":"0000320193-27-000002","form":"10-Q/A"}
                ]}}}}}
                """));
        SecFinancialFactConverter realConverter = new SecFinancialFactConverter(market);
        when(converter.convert(eq(cik), any(JsonNode.class), any(Instant.class), eq(BACKFILL_START), anyMap())).thenAnswer(call ->
                realConverter.convert(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));

        job.execute(null);

        ArgumentCaptor<SecFinancialFact> inserted = ArgumentCaptor.forClass(SecFinancialFact.class);
        verify(entityManager, times(2)).persist(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(SecFinancialFact::getAccession, SecFinancialFact::getPeriodEnd)
                .containsExactlyInAnyOrder(
                        tuple("0000320193-27-000001", nextPeriod),
                        tuple("0000320193-27-000002", olderAmendedPeriod));
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(nextPeriod);
        assertThat(secondClass.getSecFinancialFactsDate()).isEqualTo(nextPeriod);
    }

    /** Scenario：SEC 重編同一 accession 數值或補齊財季標記時直接原地更新並記錄 updatedAt，不再拋 SEC_FACT_EXISTING_CONFLICT。 */
    @Test
    void sameAccessionRevisionUpdatesExistingRowInPlaceWithoutThrowingConflict() throws Exception {
        String cik = "0000320193";
        first.setSecFinancialFactsDate(reportPeriod);
        secondClass.setSecFinancialFactsDate(reportPeriod);
        SecFinancialFact existing = fact("0000320193-26-000001", "100");
        existing.setObservedAt(Instant.parse("2026-11-04T08:00:00Z"));
        SecFinancialFact revised = fact("0000320193-26-000001", "105");
        revised.setReportFiscalYear(2026);
        revised.setReportFiscalPeriod("Q3");
        when(facts.findByCikAndPeriodEndGreaterThanEqual(cik, BACKFILL_START)).thenReturn(List.of(existing));
        when(converter.convert(eq(cik), any(), any(), eq(BACKFILL_START), anyMap())).thenReturn(List.of(revised));

        job.execute(null);

        verify(entityManager, never()).persist(any());
        assertThat(existing.getValue()).isEqualByComparingTo("105");
        assertThat(existing.getReportFiscalYear()).isEqualTo(2026);
        assertThat(existing.getReportFiscalPeriod()).isEqualTo("Q3");
        assertThat(existing.getUpdatedAt()).isEqualTo(writeTime);
        assertThat(existing.getObservedAt()).isEqualTo(Instant.parse("2026-11-04T08:00:00Z"));
    }

    /** Scenario：內容完全未變更的 CIK 零寫入且不獲取狀態鎖。 */
    @Test
    void unchangedCikPerformsZeroWritesAndSkipsLocking() throws Exception {
        String cik = "0000320193";
        first.setSecFinancialFactsDate(reportPeriod);
        secondClass.setSecFinancialFactsDate(reportPeriod);
        firstTarget.stock().setSic("3571");
        secondTarget.stock().setSic("3571");
        SecFinancialFact existing = fact("0000320193-26-000001", "100");
        SecFinancialFact incoming = fact("0000320193-26-000001", "100.00");
        when(submissions.findCompany(cik)).thenReturn(Optional.of(new CompanySubmissions("3571", Map.of(), reportPeriod)));
        when(facts.findByCikAndPeriodEndGreaterThanEqual(cik, BACKFILL_START)).thenReturn(List.of(existing));
        when(converter.convert(eq(cik), any(), any(), eq(BACKFILL_START), anyMap())).thenReturn(List.of(incoming));

        job.execute(null);

        verify(statuses, never()).findAllByIdForUpdate(List.of("F1", "F2"));
        verifyNoInteractions(entityManager);
        assertThat(existing.getUpdatedAt()).isNull();
    }

    /** Scenario：同步財務事實時一併將 submissions.zip 的 4 碼 SIC 更新至同發行人股票主檔。 */
    @Test
    void syncUpdatesStockSicFromSubmissionsArchive() throws Exception {
        String cik = "0000320193";
        SecFinancialFact incoming = fact("0000320193-26-000001", "100");
        when(submissions.findCompany(cik)).thenReturn(Optional.of(new CompanySubmissions("3571", Map.of(), reportPeriod)));
        when(stocks.findAllById(List.of("F1", "F2"))).thenReturn(List.of(firstTarget.stock(), secondTarget.stock()));
        when(converter.convert(eq(cik), any(), any(), eq(BACKFILL_START), anyMap())).thenReturn(List.of(incoming));

        job.execute(null);

        assertThat(firstTarget.stock().getSic()).isEqualTo("3571");
        assertThat(secondTarget.stock().getSic()).isEqualTo("3571");
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
    }

    @Test
    void importsPredecessorFromTheSameArchiveWithoutAdvancingItsSuccessorWatermark() throws Exception {
        String current = "0000320193";
        String previous = "0000000004";
        SecFinancialFactsDailySyncJob withTransitions = new SecFinancialFactsDailySyncJob(
                stocks, facts, statuses, archives, submissionsArchive, converter, transactions, entityManager, clock,
                new SecIssuerHistory(current + ":" + previous + ":2026-11-10:0000320193-26-000001"));
        JsonNode disclosure = new ObjectMapper().createObjectNode();
        SecFinancialFact historic = fact("0000000004-17-000001", "100");
        historic.setCik(previous);
        historic.setPeriodEnd(LocalDate.of(2016, 12, 31));
        when(archive.read(previous)).thenReturn(disclosure);
        when(converter.convert(eq(previous), eq(disclosure), any(), eq(BACKFILL_START), anyMap())).thenReturn(List.of(historic));

        withTransitions.execute(null);

        verify(entityManager).persist(historic);
        assertThat(first.getSecFinancialFactsDate()).isNull();
        assertThat(secondClass.getSecFinancialFactsDate()).isNull();
    }

    @Test
    void absentCompanyEntryAndSingleCompanyFailureIsolateProperly() throws Exception {
        LocalDate oldDate = LocalDate.parse("2026-11-10");
        first.setSecFinancialFactsDate(oldDate);
        when(archive.read("0000320193")).thenThrow(new SecCompanyFactsArchive.EntryMissingException("0000320193"));
        job.execute(null);
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(oldDate);

        doThrow(new IllegalStateException("bad company")).when(archive).read("0000320193");
        assertThatThrownBy(() -> job.execute(null)).isInstanceOf(JobExecutionException.class).hasMessageContaining("1/2");
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(oldDate);
    }

    private StockStatus status(String figi) {
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        status.setFinancialReportDate(LocalDate.parse("2026-09-30"));
        return status;
    }

    private ActiveStockWithStatus target(String cik, StockStatus status) {
        Stock stock = new Stock();
        stock.setFigi(status.getFigi());
        stock.setCik(cik);
        return new ActiveStockWithStatus(stock, status);
    }

    private SecFinancialFact fact(String accession, String value) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik("0000320193");
        fact.setTaxonomy("us-gaap");
        fact.setUnit("USD");
        fact.setTag("Assets");
        fact.setPeriodEnd(LocalDate.parse("2026-09-30"));
        fact.setFiled(LocalDate.parse("2026-11-03"));
        fact.setAvailableFromDate(LocalDate.parse("2026-11-04"));
        fact.setForm("10-Q");
        fact.setAccession(accession);
        fact.setValue(new BigDecimal(value));
        return fact;
    }
}
