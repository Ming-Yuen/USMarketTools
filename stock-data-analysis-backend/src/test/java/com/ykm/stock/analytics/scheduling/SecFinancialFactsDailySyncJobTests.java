package com.ykm.stock.analytics.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.client.SecCompanyFactsArchive;
import com.ykm.stock.analytics.client.SecEdgarFeedArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.model.entity.*;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.repository.jpa.*;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quartz.JobExecutionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecFinancialFactsDailySyncJobTests {
    @ParameterizedTest
    @CsvSource({"2026-06-30,2026-06-30", "2013-12-31,2014-01-01"})
    void predecessorWithStoredFactsResumesInclusivelyAndCanRerun(LocalDate storedPeriod, LocalDate expectedStart) throws Exception {
        String current = "0000320193";
        String previous = "0000000004";
        SecFinancialFactsDailySyncJob withTransitions = new SecFinancialFactsDailySyncJob(stocks, facts, statuses, archives,
                statementArchives, converter, transactions, entityManager, clock,
                new SecIssuerHistory(current + ":" + previous + ":2026-11-10:0000320193-26-000001"), valuation, edgarFeed);
        when(facts.findLatestFinancialPeriodEnds(List.of(previous))).thenReturn(List.<Object[]>of(new Object[]{previous, storedPeriod}));
        SecFinancialFact incoming = fact("0000000004-26-000001", "100");
        incoming.setCik(previous);
        incoming.setPeriodEnd(expectedStart);
        when(converter.convert(eq(previous), any(), any(), eq(expectedStart))).thenReturn(List.of(incoming));
        when(facts.findByCikAndPeriodEndGreaterThanEqual(previous, expectedStart)).thenReturn(List.of(), List.of(incoming));

        withTransitions.execute(null);
        withTransitions.execute(null);

        verify(converter, times(2)).convert(eq(previous), any(), any(), eq(expectedStart));
        verify(entityManager, times(1)).persist(incoming);
        verify(entityManager, never()).remove(any());
        verify(statuses, never()).findAllByIdForUpdate(List.of());
        verify(valuation, times(2)).pendingEdgarFilings(any(), any());
        assertThat(List.of(first, secondClass, other)).allSatisfy(status -> assertThat(status.getSecFinancialFactsDate()).isNull());
    }

    @Test
    void importsPredecessorFromTheSameArchiveWithoutAdvancingItsSuccessorWatermark() throws Exception {
        String current = "0000320193";
        String previous = "0000000004";
        SecFinancialFactsDailySyncJob withTransitions = new SecFinancialFactsDailySyncJob(stocks, facts, statuses, archives,
                statementArchives, converter, transactions, entityManager, clock,
                new SecIssuerHistory(current + ":" + previous + ":2026-11-10:0000320193-26-000001"), valuation, edgarFeed);
        JsonNode disclosure = new ObjectMapper().createObjectNode();
        SecFinancialFact historic = fact("0000000004-17-000001", "100");
        historic.setCik(previous);
        historic.setPeriodEnd(LocalDate.of(2016, 12, 31));
        when(archive.read(previous)).thenReturn(disclosure);
        when(converter.convert(eq(previous), eq(disclosure), any(), eq(LocalDate.of(2014, 1, 1)))).thenReturn(List.of(historic));
        withTransitions.execute(null);
        verify(entityManager).persist(historic);
        assertThat(first.getSecFinancialFactsDate()).isNull();
        assertThat(secondClass.getSecFinancialFactsDate()).isNull();
    }

    private final StockRepository stocks = mock(StockRepository.class);
    private final SecFinancialFactRepository facts = mock(SecFinancialFactRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final SecCompanyFactsArchive archives = mock(SecCompanyFactsArchive.class);
    private final SecCompanyFactsArchive.Snapshot archive = mock(SecCompanyFactsArchive.Snapshot.class);
    private final SecFinancialFactConverter converter = mock(SecFinancialFactConverter.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final LocalDateTime writeTime = LocalDateTime.parse("2026-11-14T03:06:08.156");
    private final LocalDate reportPeriod = LocalDate.parse("2026-09-30");
    private final StockStatus first = status("F1");
    private final StockStatus secondClass = status("F2");
    private final StockStatus other = status("F3");
    private final MarketStatusService market = new MarketStatusService(clock);
    private final SecFinancialStatementArchive statementArchives = mock(SecFinancialStatementArchive.class);
    private final StockValuationDailyTask valuation = mock(StockValuationDailyTask.class);
    private final SecEdgarFeedArchive edgarFeed = mock(SecEdgarFeedArchive.class);
    private final SecFinancialFactsDailySyncJob job = new SecFinancialFactsDailySyncJob(stocks, facts, statuses, archives, statementArchives, converter, transactions,
            entityManager, clock, new SecIssuerHistory(""), valuation, edgarFeed);

    @BeforeEach
    void setup() {
        when(clock.now()).thenReturn(Instant.parse("2026-11-13T19:06:08.156Z"));
        when(clock.nowLocalDateTime()).thenReturn(writeTime);
        when(stocks.findSecFinancialFactsSyncStocks(any())).thenReturn(List.of(target("0000320193", first), target("0000320193", secondClass), target("0000000002", other)));
        when(statuses.findAllByIdForUpdate(List.of("F1", "F2"))).thenReturn(List.of(first, secondClass));
        when(statuses.findAllByIdForUpdate(List.of("F3"))).thenReturn(List.of(other));
        when(archives.prepare()).thenReturn(archive);
        when(archive.version()).thenReturn("v1");
        when(converter.convert(anyString(), any(), any(), any(LocalDate.class))).thenReturn(List.of());
        when(transactions.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    }

    @Test
    void watermarkUsesLatestFinancialPeriodInsteadOfZipOrCoverDate() throws Exception {
        String cik = "0000320193";
        LocalDate period = LocalDate.parse("2026-09-30");
        SecFinancialFact report = fact("0000320193-26-000001", "100");
        report.setTaxonomy("us-gaap");
        report.setTag("Assets");
        SecFinancialFact cover = fact(report.getAccession(), "100");
        cover.setTaxonomy("dei");
        cover.setTag("EntityCommonStockSharesOutstanding");
        cover.setPeriodEnd(period.plusDays(10));
        when(converter.convert(eq(cik), any(), any(), eq(LocalDate.of(2014, 1, 1))))
                .thenReturn(List.of(report, cover));

        job.execute(null);

        assertThat(first.getSecFinancialFactsDate()).isEqualTo(period);
        assertThat(secondClass.getSecFinancialFactsDate()).isEqualTo(period);
        assertThat(other.getSecFinancialFactsDate()).isNull();
        verify(statementArchives).downloadMissing(2014);
        verify(edgarFeed, never()).downloadingSession();
    }

    @Test
    void preparesAllCandidateDatesWithOneSessionAfterCompanyFactsAreSaved() throws Exception {
        Instant close = Instant.parse("2026-11-13T21:00:00Z");
        Instant previousClose = Instant.parse("2026-11-12T21:00:00Z");
        LocalDate firstFeedDate = LocalDate.of(2026, 10, 1);
        SecFinancialFact incoming = fact("0000320193-26-000001", "100");
        SecFinancialFact older = fact("0000320193-26-000002", "200");
        when(converter.convert(eq("0000320193"), any(), any(), any())).thenReturn(List.of(incoming));
        when(edgarFeed.automaticFrom()).thenReturn(firstFeedDate);
        when(valuation.pendingEdgarFilings(any(), eq(firstFeedDate))).thenReturn(Map.of(previousClose, List.of(incoming), close, List.of(incoming, older)));
        SecEdgarFeedArchive.Session session = mock(SecEdgarFeedArchive.Session.class);
        when(edgarFeed.downloadingSession()).thenReturn(session);
        when(session.confirm(anyCollection(), any())).thenReturn(new SecEdgarFeedArchive.Confirmation(facts -> Set.of(), List.of()));

        job.execute(null);

        org.mockito.InOrder order = inOrder(entityManager, archive, statementArchives, valuation, edgarFeed);
        order.verify(entityManager).persist(incoming);
        order.verify(archive).close();
        order.verify(statementArchives).downloadMissing(2014);
        order.verify(valuation).pendingEdgarFilings(any(), eq(firstFeedDate));
        order.verify(edgarFeed).downloadingSession();
        verify(session).confirm(List.of(incoming), previousClose);
        verify(session).confirm(List.of(incoming, older), close);
    }

    @Test
    void nullWatermarkReplacesMatchingDisclosureAndKeepsItsFirstObservedTime() throws Exception {
        String cik = "0000320193";
        String accession = "0000320193-26-000001";
        LocalDate start = LocalDate.of(2014, 1, 1);
        SecFinancialFact existing = fact(accession, "100");
        existing.setObservedAt(Instant.parse("2026-11-04T08:00:00Z"));
        SecFinancialFact incoming = fact(accession, "100.0");
        SecFinancialFact corrected = fact("0000320193-26-000003", "200");
        SecFinancialFact stale = fact(corrected.getAccession(), "180");
        SecFinancialFact otherFiling = fact("0000320193-26-000002", "150");
        when(facts.findByCikAndPeriodEndGreaterThanEqual(cik, start)).thenReturn(List.of(existing, otherFiling, stale));
        when(converter.convert(eq(cik), any(), any(), eq(start))).thenReturn(List.of(incoming, corrected));

        job.execute(null);

        org.mockito.InOrder writes = inOrder(entityManager);
        writes.verify(entityManager).remove(existing);
        writes.verify(entityManager).flush();
        writes.verify(entityManager).persist(incoming);
        verify(entityManager, never()).remove(otherFiling);
        verify(entityManager).remove(stale);
        verify(entityManager).persist(corrected);
        assertThat(incoming.getObservedAt()).isEqualTo(existing.getObservedAt());
        assertThat(incoming.getUpdatedAt()).isEqualTo(writeTime);
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
    }

    @Test
    void jsonBackfillReplacesMatchingSharesAndPreservesOtherDisclosures() throws Exception {
        String cik = "0000320193";
        ActiveStockWithStatus apple = target(cik, first);
        apple.stock().setSymbol("AAPL");
        apple.stock().setStockType("Common Stock");
        when(stocks.findSecFinancialFactsSyncStocks(any())).thenReturn(List.of(apple));
        when(statuses.findAllByIdForUpdate(List.of("F1"))).thenReturn(List.of(first));
        LocalDate start = LocalDate.of(2014, 1, 1);
        SecFinancialFact report = fact("0000320193-26-000001", "100");
        SecFinancialFact incoming = fact(report.getAccession(), "10");
        incoming.setTag("CommonStockSharesOutstanding");
        incoming.setUnit("shares");
        SecFinancialFact existing = fact(report.getAccession(), "10.0");
        existing.setTag(incoming.getTag());
        existing.setUnit(incoming.getUnit());
        SecFinancialFact otherDisclosure = fact("0000320193-26-000002", "9");
        otherDisclosure.setTag(incoming.getTag());
        otherDisclosure.setUnit(incoming.getUnit());
        when(facts.findByCikAndPeriodEndGreaterThanEqual(cik, start)).thenReturn(List.of(existing, otherDisclosure));
        when(converter.convert(eq(cik), any(), any(), eq(start))).thenReturn(List.of(report, incoming));

        job.execute(null);

        verify(entityManager).remove(existing);
        verify(entityManager, never()).remove(otherDisclosure);
        verify(entityManager).persist(incoming);
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
    }

    @Test
    void deduplicatesCiksAndEmptyFactsLeaveWatermarksNull() throws Exception {
        job.execute(null);
        verify(archive, times(2)).read(anyString());
        assertThat(List.of(first, secondClass, other)).allSatisfy(status -> assertThat(status.getSecFinancialFactsDate()).isNull());
        assertThat(first.getFinancialReportDate()).isEqualTo(LocalDate.parse("2026-09-30"));
        verify(archive).close();
    }

    @Test
    void samePeriodRerunPreservesTheExistingDisclosure() throws Exception {
        LocalDate previousDay = reportPeriod;
        first.setSecFinancialFactsDate(previousDay);
        secondClass.setSecFinancialFactsDate(previousDay);
        SecFinancialFact old = fact("0000320193-26-000001", "100");
        when(facts.findLatestFinancialPeriodEnds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"0000320193", previousDay}));
        when(facts.findByCikAndPeriodEndGreaterThanEqual("0000320193", previousDay)).thenReturn(List.of(old));
        SecFinancialFact incoming = fact("0000320193-26-000001", "100.0");
        incoming.setReportFiscalYear(2026);
        incoming.setReportFiscalPeriod("Q3");
        when(converter.convert(eq("0000320193"), any(), any(), eq(previousDay)))
                .thenReturn(List.of(incoming));
        job.execute(null);
        job.execute(null);
        verify(archive, times(2)).read("0000320193");
        verify(archive, times(2)).read("0000000002");
        verify(facts, times(2)).findByCikAndPeriodEndGreaterThanEqual("0000320193", previousDay);
        verify(entityManager, never()).persist(any());
        assertThat(old.getValue()).isEqualByComparingTo("100");
        assertThat(old.getUpdatedAt()).isNull();
        assertThat(old.getReportFiscalYear()).isEqualTo(2026);
        assertThat(old.getReportFiscalPeriod()).isEqualTo("Q3");
    }

    @Test
    void absentCompanyEntryKeepsWatermarksAndOtherCompanyStillCompletes() throws Exception {
        LocalDate oldDate = LocalDate.parse("2026-11-10");
        first.setSecFinancialFactsDate(oldDate);
        when(archive.read("0000320193")).thenThrow(new SecCompanyFactsArchive.EntryMissingException("0000320193"));

        job.execute(null);

        assertThat(first.getSecFinancialFactsDate()).isEqualTo(oldDate);
        assertThat(secondClass.getSecFinancialFactsDate()).isNull();
        assertThat(other.getSecFinancialFactsDate()).isNull();
        verify(statuses, never()).findAllByIdForUpdate(List.of("F1", "F2"));
        verify(facts, never()).findByCikAndPeriodEndGreaterThanEqual(anyString(), any());
    }

    @Test
    void companyFailureKeepsItsWatermarksAndFollowingCompanyCompletes() {
        LocalDate oldDate = LocalDate.parse("2026-11-10");
        first.setSecFinancialFactsDate(oldDate);
        when(archive.read("0000320193")).thenThrow(new IllegalStateException("bad company"));
        assertThatThrownBy(() -> job.execute(null)).isInstanceOf(JobExecutionException.class).hasMessageContaining("1/2");
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(oldDate);
        assertThat(secondClass.getSecFinancialFactsDate()).isNull();
        assertThat(other.getSecFinancialFactsDate()).isNull();
    }

    @Test
    void globalZipFailureDoesNotAdvanceAnyStock() {
        when(archives.prepare()).thenThrow(new IllegalStateException("bad zip"));
        assertThatThrownBy(() -> job.execute(null)).hasMessageContaining("bad zip");
        assertThat(first.getSecFinancialFactsDate()).isNull();
        verifyNoInteractions(entityManager, statuses);
    }

    @Test
    void nextArchiveUsesOldestPeriodInclusiveAndAcceptsASamePeriodAmendment() throws Exception {
        LocalDate oldest = reportPeriod.minusDays(2);
        first.setSecFinancialFactsDate(reportPeriod.minusDays(1));
        secondClass.setSecFinancialFactsDate(oldest);
        other.setSecFinancialFactsDate(reportPeriod);
        SecFinancialFact old = fact("0000320193-26-000001", "100");
        SecFinancialFact revision = fact("0000320193-26-000002", "400");
        when(facts.findLatestFinancialPeriodEnds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"0000320193", reportPeriod}));
        revision.setPeriodEnd(oldest);
        when(facts.findByCikAndPeriodEndGreaterThanEqual("0000320193", oldest)).thenReturn(List.of(old));
        when(converter.convert(eq("0000320193"), any(), any(), eq(oldest))).thenReturn(List.of(old, revision));
        job.execute(null);
        verify(archive).read("0000000002");
        verify(entityManager).persist(revision);
        assertThat(revision.getUpdatedAt()).isEqualTo(writeTime);
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
        assertThat(secondClass.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
    }

    @Test
    void incrementalSyncProcessesOnlyTheWatermarkPeriodAndLaterPeriods() throws Exception {
        String cik = "0000320193";
        LocalDate nextPeriod = LocalDate.parse("2026-12-31");
        first.setSecFinancialFactsDate(reportPeriod);
        secondClass.setSecFinancialFactsDate(reportPeriod);
        when(clock.now()).thenReturn(Instant.parse("2027-02-01T08:00:00Z"));
        SecFinancialFact existing = fact("0000320193-26-000001", "100");
        when(facts.findLatestFinancialPeriodEnds(List.of(cik))).thenReturn(List.<Object[]>of(new Object[]{cik, reportPeriod}));
        when(facts.findByCikAndPeriodEndGreaterThanEqual(cik, reportPeriod)).thenReturn(List.of(existing));
        when(archive.read(cik)).thenReturn(new ObjectMapper().readTree("""
                {"cik":320193,"facts":{"us-gaap":{"Assets":{"units":{"USD":[
                  {"end":"2026-09-30","val":100,"filed":"2026-11-03","accn":"0000320193-26-000001","form":"10-Q"},
                  {"end":"2026-12-31","val":120,"filed":"2027-01-28","accn":"0000320193-27-000001","form":"10-Q"},
                  {"end":"2026-06-30","val":95,"filed":"2027-01-29","accn":"0000320193-27-000002","form":"10-Q/A"},
                  {"end":"2026-09-30","val":110,"filed":"2027-01-29","accn":"0000320193-27-000003","form":"10-Q/A"}
                ]}}}}}
                """));
        SecFinancialFactConverter realConverter = new SecFinancialFactConverter(market);
        when(converter.convert(eq(cik), any(JsonNode.class), any(Instant.class), any(LocalDate.class))).thenAnswer(call ->
                realConverter.convert(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));

        job.execute(null);

        verify(facts).findByCikAndPeriodEndGreaterThanEqual(cik, reportPeriod);
        org.mockito.ArgumentCaptor<SecFinancialFact> inserted = org.mockito.ArgumentCaptor.forClass(SecFinancialFact.class);
        verify(entityManager, times(2)).persist(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(SecFinancialFact::getAccession, SecFinancialFact::getPeriodEnd)
                .containsExactlyInAnyOrder(tuple("0000320193-27-000003", reportPeriod), tuple("0000320193-27-000001", nextPeriod));
        verify(entityManager, never()).remove(any());
        assertThat(existing.getValue()).isEqualByComparingTo("100");
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(nextPeriod);
        assertThat(secondClass.getSecFinancialFactsDate()).isEqualTo(nextPeriod);
    }

    @Test
    void legacyZipWatermarkResumesAtTheStoredReportPeriod() throws Exception {
        first.setSecFinancialFactsDate(LocalDate.parse("2026-11-13"));
        secondClass.setSecFinancialFactsDate(reportPeriod);
        when(facts.findLatestFinancialPeriodEnds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"0000320193", reportPeriod}));
        SecFinancialFact existing = fact("0000320193-26-000001", "100");
        when(facts.findByCikAndPeriodEndGreaterThanEqual("0000320193", reportPeriod)).thenReturn(List.of(existing));
        when(converter.convert(eq("0000320193"), any(), any(), eq(reportPeriod))).thenReturn(List.of(existing));

        job.execute(null);

        verify(converter).convert(eq("0000320193"), any(), any(), eq(reportPeriod));
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
        assertThat(secondClass.getSecFinancialFactsDate()).isEqualTo(reportPeriod);
        verify(entityManager, never()).remove(any());
        verify(entityManager, never()).persist(any());
    }

    @Test
    void existingAccessionConflictNeverOverwritesOrAdvancesCompany() {
        LocalDate previousDay = reportPeriod.minusDays(1);
        first.setSecFinancialFactsDate(previousDay);
        secondClass.setSecFinancialFactsDate(previousDay);
        when(facts.findLatestFinancialPeriodEnds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"0000320193", reportPeriod}));
        when(facts.findByCikAndPeriodEndGreaterThanEqual("0000320193", previousDay))
                .thenReturn(List.of(fact("0000320193-26-000001", "100")));
        when(converter.convert(eq("0000320193"), any(), any(), eq(previousDay)))
                .thenReturn(List.of(fact("0000320193-26-000001", "200")));
        assertThatThrownBy(() -> job.execute(null)).isInstanceOf(JobExecutionException.class);
        assertThat(first.getSecFinancialFactsDate()).isEqualTo(previousDay);
        verify(entityManager, never()).persist(any());
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
        fact.setForm("10-Q");
        fact.setAccession(accession);
        fact.setValue(new BigDecimal(value));
        return fact;
    }
}
