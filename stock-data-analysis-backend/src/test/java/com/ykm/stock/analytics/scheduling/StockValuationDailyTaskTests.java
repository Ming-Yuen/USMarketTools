package com.ykm.stock.analytics.scheduling;

import com.ykm.stock.analytics.client.SecEdgarFeedArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive.ClassFact;
import com.ykm.stock.analytics.client.SecFinancialStatementArchive.FilingIndustry;

import com.ykm.common.common_lib.service.DateTimeService;
import com.ykm.stock.analytics.converter.sec.SecFinancialFactConverter;
import com.ykm.stock.analytics.enums.ActiveStatus;
import com.ykm.stock.analytics.enums.FinancialConcept;
import com.ykm.stock.analytics.enums.FinancialItemMappingStatus;
import com.ykm.stock.analytics.model.entity.*;
import com.ykm.stock.analytics.model.projection.ActiveStockWithStatus;
import com.ykm.stock.analytics.service.futu.FutuService;
import com.ykm.stock.analytics.service.stock.SecIssuerHistory;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import com.ykm.stock.analytics.repository.jpa.*;
import com.ykm.stock.analytics.service.market.MarketStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockValuationDailyTaskTests {
    @Test
    void alignsExelFiscalQuarterWithoutReplacingCompleteFutuEpsWithSecMissingQ4() throws Exception {
        aapl.stock().setCik("0000000001");
        String source = """
                {"facts":{"us-gaap":{"EarningsPerShareDiluted":{"units":{"USD/shares":[
                  {"start":"2025-01-04","end":"2026-01-02","val":2.78,"fy":2025,"fp":"FY","filed":"2026-02-10","form":"10-K","accn":"0000000001-26-000001"},
                  {"start":"2026-01-03","end":"2026-04-03","val":0.79,"fy":2026,"fp":"Q1","filed":"2026-05-05","form":"10-Q","accn":"0000000001-26-000002"},
                  {"start":"2026-04-04","end":"2026-07-03","val":0.82,"fy":2026,"fp":"Q2","filed":"2026-08-05","form":"10-Q","accn":"0000000001-26-000003"},
                  {"start":"2026-07-04","end":"2026-10-02","val":0.85,"fy":2026,"fp":"Q3","filed":"2026-10-29","form":"10-Q","accn":"0000000001-26-000004"}
                ]}}}}}
                """;
        List<SecFinancialFact> facts = new SecFinancialFactConverter(calendar).convert("0000000001",
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(source), NOW);
        List<FinancialReport> income = new ArrayList<>();
        String[] eps = {"0.88", "0.79", "0.82", "0.85"};
        for (int index = 0; index < 4; index++) {
            LocalDate end = LocalDate.of(2025, 12, 31).plusMonths(index * 3L);
            FinancialReport quarter = report("AAPL", 1, index == 0 ? 4 : index, end);
            quarter.setFiscalYear(index == 0 ? 2025 : 2026);
            quarter.setItems(List.of(new FinancialReport.Item(1, "Diluted EPS", new BigDecimal(eps[index]), null, null)));
            income.add(quarter);
        }
        when(reports.findValuationReports(anyCollection(), any(), any())).thenReturn(income);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), any(), any())).thenReturn(facts);

        task.execute(context(DAY));

        assertThat(saved.getFirst().getIncomePeriodEnd()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("29.9401");
        assertThat(saved.getFirst().getPeFinancialSource()).isEqualTo("FUTU");
    }

    @Test
    void noCikStillCalculatesFutuOnlyValuations() {
        aapl.stock().setCik(null);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE)))
                .thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPeReason()).isNull();
            assertThat(row.getPbReason()).isNull();
            assertThat(row.getSecFinancialCik()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "SEC_REVENUE_MISSING,REVENUE_MISSING",
            "SEC_FCF_COMPONENTS_MISSING,FCF_COMPONENTS_MISSING",
            "SEC_EPS_TTM_PERIODS_MISSING,EPS_TTM_PERIODS_MISSING",
            "SEC_TTM_PERIODS_MISSING,TTM_PERIODS_MISSING",
            "SEC_COMMON_EQUITY_UNRESOLVED,COMMON_EQUITY_UNRESOLVED",
            "SEC_EBITDA_COMPONENTS_UNRESOLVED,EBITDA_COMPONENTS_UNRESOLVED",
            "SEC_EV_COMPONENTS_UNRESOLVED,EV_COMPONENTS_UNRESOLVED",
            "SEC_FINANCIAL_INPUT_MISSING,FINANCIAL_INPUT_MISSING",
            "SEC_BALANCE_INPUT_MISSING,BALANCE_INPUT_MISSING",
            "SEC_FCF_PERIOD_MISMATCH,SEC_FCF_PERIOD_MISMATCH"
    })
    void combinedMissingReasonNamesSharedDataGapsWithoutHidingSecSpecificProblems(String source, String expected) {
        assertThat((String) ReflectionTestUtils.invokeMethod(task, "combinedMissingReason", source)).isEqualTo(expected);
    }

    @Test
    void recomputationUsesFutuReportObtainedAfterThePriceDate() {
        FinancialReport annual = report("AAPL", 1, 7, LocalDate.of(2026, 9, 30));
        annual.setObservedAt(CLOSE.plusSeconds(3600));
        when(reports.findValuationReports(anyCollection(), any(), any())).thenAnswer(call ->
                annual.getObservedAt().isAfter((Instant) call.getArgument(2)) ? List.of() : List.of(annual));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getValuationDate()).isEqualTo(DAY);
        assertThat(saved.getFirst().getFutuFinancialObservedAt()).isEqualTo(annual.getObservedAt());
    }

    @Test
    void historicalQuarterUsesItsLaterPublishedAnnualEpsInsteadOfTheLatestYear() {
        Instant recomputed = Instant.parse("2028-03-01T12:00:00Z");
        when(clock.now()).thenReturn(recomputed);
        when(calendar.getLastClosedTradingDay(recomputed)).thenReturn(DAY);
        aapl.stock().setCik("0000000001");
        aapl.status().setSplitDate(LocalDate.of(2028, 3, 1));
        when(reports.findValuationReports(anyCollection(), any(), any())).thenReturn(List.of());
        SecFinancialFact target = secFlow("EarningsPerShareDiluted", "5");
        target.setUnit("USD/shares");
        target.setPeriodStart(LocalDate.of(2026, 1, 1));
        target.setPeriodEnd(LocalDate.of(2026, 12, 31));
        target.setFiled(LocalDate.of(2027, 2, 1));
        target.setAvailableFromDate(LocalDate.of(2027, 2, 2));
        target.setObservedAt(Instant.parse("2027-02-03T12:00:00Z"));
        SecFinancialFact q3 = secFlow("NetIncomeLoss", "20");
        q3.setPeriodStart(LocalDate.of(2026, 1, 1));
        q3.setPeriodEnd(LocalDate.of(2026, 9, 30));
        SecFinancialFact nextYear = secFlow("EarningsPerShareDiluted", "10");
        nextYear.setUnit("USD/shares");
        nextYear.setPeriodStart(LocalDate.of(2027, 1, 1));
        nextYear.setPeriodEnd(LocalDate.of(2027, 12, 31));
        nextYear.setFiled(LocalDate.of(2028, 2, 1));
        nextYear.setAvailableFromDate(LocalDate.of(2028, 2, 2));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), any(), any())).thenReturn(List.of(target, q3, nextYear));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getIncomePeriodEnd()).isEqualTo(target.getPeriodEnd());
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getSecFinancialAvailableFromDate()).isEqualTo(target.getAvailableFromDate());
    }

    @Test
    void newSecQuarterPublishedAfterPriceDateCanReplaceFallbackPeriod() {
        aapl.stock().setCik("0000000001");
        when(reports.findValuationReports(anyCollection(), any(), any())).thenReturn(List.of());
        SecFinancialFact eps = secFlow("EarningsPerShareDiluted", "5");
        eps.setUnit("USD/shares");
        eps.setFiled(DAY.plusDays(1));
        eps.setAvailableFromDate(DAY.plusDays(2));
        eps.setObservedAt(NOW.minusSeconds(60));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), any(), any())).thenReturn(List.of(eps));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getSecFinancialAvailableFromDate()).isEqualTo(eps.getAvailableFromDate());
    }

    @Test
    void historicalSuccessorTickerUsesPredecessorAtTheOriginalValuationDate() {
        aapl.stock().setCik("0000000004");
        SecFinancialFact eps = secFlow("EarningsPerShareDiluted", "5");
        eps.setCik("0000000003");
        eps.setUnit("USD/shares");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(eq(List.of("0000000003")), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(eps));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getSecFinancialCik()).isEqualTo("0000000003");
        verify(statementArchives).loadDepreciation(Set.of("0000000003", "0000000004"));
    }

    /** Scenario：沒有 CIK、Futu 財報及產業時，Finnhub Banking 仍可識別兩項銀行不適用估值。 */
    @Test
    void finnhubBankingWithoutFinancialStatementsIsNotApplicableOnlyForCashFlowAndEv() {
        aapl.stock().setCik(null);
        aapl.stock().setFinnhubIndustry("Banking");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPeReason()).isEqualTo("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
            assertThat(row.getPbReason()).isEqualTo("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
            assertThat(row.getPsReason()).isEqualTo("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
            assertThat(row.getPfcf()).isNull();
            assertThat(row.getPfcfReason()).isEqualTo("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
            assertThat(row.getEvEbitda()).isNull();
            assertThat(row.getEvEbitdaReason()).isEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");
        });
    }

    /** Scenario：已知富途非銀行分類優先於 Finnhub Banking 備援，不錯誤地套用 VALID NULL。 */
    @Test
    void futuNonBankClassificationOverridesFinnhubBanking() {
        aapl.stock().setFinnhubIndustry("Banking");
        StockIndustry nonBank = new StockIndustry();
        nonBank.setSourcePlateCode("US.LIST2249");
        StockIndustryHistory history = new StockIndustryHistory();
        history.setFigi("AAPL");
        history.setIndustry(nonBank);
        when(industries.findByFigisAt(List.of("AAPL"), CLOSE)).thenReturn(List.of(history));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNotNull();
            assertThat(row.getPfcfReason()).isNotEqualTo("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
            assertThat(row.getEvEbitdaReason()).isNotEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");
        });
    }

    /** Scenario：有 SEC 非銀行 SIC 時不應被 Finnhub 當前 Banking 分類覆蓋。 */
    @Test
    void knownSecNonBankClassificationOverridesFinnhubBanking() {
        aapl.stock().setCik("0000000001");
        aapl.stock().setFinnhubIndustry("Banking");
        when(statementArchives.filingIndustries(anySet())).thenReturn(Map.of("0000000001",
                List.of(new FilingIndustry(DAY.minusDays(2), "7370"))));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPfcfReason()).isNotEqualTo("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
            assertThat(row.getEvEbitdaReason()).isNotEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");
        });
    }

    @Test
    void datedSecBankClassificationCanFillMissingFutuHistory() {
        aapl.stock().setCik("0000000001");
        when(statementArchives.filingIndustries(anySet())).thenReturn(Map.of("0000000001",
                List.of(new FilingIndustry(DAY.plusDays(1), "7370"), new FilingIndustry(DAY.minusDays(2), "6021"))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getEvEbitdaReason()).isEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");
        assertThat(saved.getFirst().getPfcfReason()).isEqualTo("PFCF_NOT_APPLICABLE_TO_INDUSTRY");
        assertThat(saved.getFirst().getPe()).isNotNull();
    }

    @Test
    void secBrokerClassificationRefinesBroadFutuCapitalMarketsIndustry() {
        aapl.stock().setCik("0000000001");
        StockIndustry capitalMarkets = new StockIndustry();
        capitalMarkets.setSourcePlateCode("US.LIST2260");
        StockIndustryHistory industry = new StockIndustryHistory();
        industry.setFigi("AAPL");
        industry.setIndustry(capitalMarkets);
        when(industries.findByFigisAt(List.of("AAPL"), CLOSE)).thenReturn(List.of(industry));
        when(statementArchives.filingIndustries(anySet())).thenReturn(Map.of("0000000001",
                List.of(new FilingIndustry(DAY.minusDays(2), "6211"))));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getLast();
        assertThat(row.getEvEbitda()).isNull();
        assertThat(row.getEvEbitdaReason()).isEqualTo("EV_EBITDA_NOT_APPLICABLE_TO_INDUSTRY");
        assertThat(row.getPfcf()).isNotNull();
        assertThat(com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(row, null)
                .metrics().evEbitda().status()).isEqualTo("NOT_APPLICABLE");

        when(statementArchives.filingIndustries(anySet())).thenReturn(Map.of("0000000001",
                List.of(new FilingIndustry(DAY.minusDays(2), "6282"))));
        task.execute(context(DAY));
        assertThat(saved.getLast().getEvEbitda()).isEqualByComparingTo("12");

        capitalMarkets.setSourcePlateCode("US.LIST2249");
        when(statementArchives.filingIndustries(anySet())).thenReturn(Map.of("0000000001",
                List.of(new FilingIndustry(DAY.minusDays(2), "6211"))));
        task.execute(context(DAY));
        assertThat(saved.getLast().getEvEbitda()).isEqualByComparingTo("12");
    }

    @Test
    void namedShareClassUsesItsLatestAnnualEpsWhileSharesKeepThePriceDateBasis() {
        aapl.stock().setCik("0000000001");
        aapl.stock().setLongName("Example Holdings Class C Common Stock");
        LocalDate end = LocalDate.of(2026, 9, 30);
        String accession = "0000000001-26-000001";
        SecFinancialFact fiscalPeriod = secFlow("Revenues", "500");
        fiscalPeriod.setAccession(accession);
        fiscalPeriod.setPeriodStart(end.minusYears(1).plusDays(1));
        fiscalPeriod.setPeriodEnd(end);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(fiscalPeriod));
        when(statementArchives.classFacts(anySet())).thenReturn(Map.of("0000000001", List.of(
                new ClassFact(accession, DAY.minusDays(1), "10-K", "A", end, end, 4, "USD", new BigDecimal("10"), "EarningsPerShareDiluted"),
                new ClassFact(accession, DAY.minusDays(1), "10-K", "C", end, end, 4, "USD", new BigDecimal("5"), "EarningsPerShareDiluted"),
                new ClassFact(accession, DAY.plusDays(1), "10-K", "C", end, end, 4, "USD", BigDecimal.ONE, "EarningsPerShareDiluted"),
                new ClassFact(accession, DAY.minusDays(1), "10-K", "C", end, end, 0, "shares", BigDecimal.TEN, "CommonStockSharesOutstanding"))));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("100");
        assertThat(saved.getFirst().getSecFinancialCik()).isEqualTo("0000000001");
        assertThat(saved.getFirst().getSecShareAccession()).isEqualTo(accession);
        assertThat(saved.getFirst().getPs()).isNull();
        assertThat(saved.getFirst().getPsReason()).isEqualTo("ISSUER_MARKET_CAP_UNRESOLVED");
    }

    @Test
    void companyEvUsesAllDisclosedClassesAndRequiresTheirSumToMatchIssuerShares() {
        String cik = "0000000001";
        aapl.stock().setCik(cik);
        aapl.stock().setLongName("Example Class A Common Stock");
        ActiveStockWithStatus second = target("CLASS-B");
        second.stock().setCik(cik);
        second.stock().setLongName("Example Class B Common Stock");
        List<ActiveStockWithStatus> peers = List.of(aapl, second);
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, DAY)).thenReturn(peers);
        when(stocks.findMultiSecurityIssuers(anyCollection(), eq(ActiveStatus.ACTIVE))).thenReturn(Set.of(cik));
        when(stocks.findIssuerSecurities(Set.of(cik), ActiveStatus.ACTIVE)).thenReturn(peers);
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(aapl.status(), second.status()));
        StockQuote firstQuote = quote("AAPL");
        StockQuote secondQuote = quote("CLASS-B");
        secondQuote.setClosePrice(new BigDecimal("200"));
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenReturn(List.of(firstQuote, secondQuote));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        LocalDate end = LocalDate.of(2026, 9, 30);
        SecFinancialFact total = secFlow("CommonStockSharesOutstanding", "30");
        total.setPeriodStart(null);
        total.setPeriodEnd(end);
        total.setUnit("shares");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(total));
        when(statementArchives.classFacts(anySet())).thenReturn(Map.of(cik, List.of(
                new ClassFact(total.getAccession(), total.getFiled(), "10-Q", "A", end, end, 0, "shares", BigDecimal.TEN, total.getTag()),
                new ClassFact(total.getAccession(), total.getFiled(), "10-Q", "B", end, end, 0, "shares", new BigDecimal("20"), total.getTag()))));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getEvEbitda()).isEqualByComparingTo("52");
        assertThat(saved.getFirst().getPb()).isEqualByComparingTo("10");
        assertThat(saved.getFirst().getPs()).isEqualByComparingTo("5");
        assertThat(saved.getFirst().getPfcf()).isEqualByComparingTo("100");
        saved.clear();
        total.setValue(new BigDecimal("31"));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getEvEbitda()).isNull();
        assertThat(saved.getFirst().getPb()).isNull();
        assertThat(saved.getFirst().getPs()).isNull();
        assertThat(saved.getFirst().getPfcf()).isNull();
    }

    @Test
    void aStockWithLaterFirstQuoteIsNotScheduledForEarlierDates() {
        LocalDate beforeListing = DAY.minusDays(1);
        ReflectionTestUtils.setField(task, "historyStartDate", beforeListing);
        when(quotes.findFirstQuoteDates(List.of("AAPL"))).thenReturn(List.<Object[]>of(new Object[]{"AAPL", DAY}));
        when(calendar.isTradingDay(beforeListing)).thenReturn(true);

        task.execute(context(null));

        assertThat(saved).extracting(StockValuationDaily::getValuationDate).containsExactly(DAY);
    }

    @Test
    void usesDisclosedCoverSharesIfSingleClassHasNoBalanceSheetShares() {
        aapl.stock().setCik("0000000001");
        SecFinancialFact shares = secFlow("EntityCommonStockSharesOutstanding", "12");
        shares.setTaxonomy("dei");
        shares.setUnit("shares");
        shares.setPeriodStart(null);
        shares.setForm("10-K");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(shares, secFlow("Revenues", "500")));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPs()).isEqualByComparingTo("2.4");
        assertThat(saved.getFirst().getSecShareAccession()).isEqualTo(shares.getAccession());
    }

    @ParameterizedTest
    @CsvSource({"0,true,2.4", "0,false,2.4", "1,true,4.8", "1,false,2.4"})
    void choosesTheLatestPublicShareCountAcrossStandardTags(int daysAfterBalance, boolean coverPublished, String expectedPs) {
        aapl.stock().setCik("0000000001");
        SecFinancialFact balance = secFlow("CommonStockSharesOutstanding", "12");
        balance.setUnit("shares");
        balance.setPeriodStart(null);
        SecFinancialFact cover = secFlow("EntityCommonStockSharesOutstanding", "24");
        cover.setTaxonomy("dei");
        cover.setUnit("shares");
        cover.setPeriodStart(null);
        cover.setPeriodEnd(balance.getPeriodEnd().plusDays(daysAfterBalance));
        cover.setAccession("0000000001-26-000002");
        cover.setAvailableFromDate(coverPublished ? DAY : DAY.plusDays(1));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(balance, cover, secFlow("Revenues", "500")));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPs()).isEqualByComparingTo(expectedPs);
        assertThat(row.getSecShareAccession()).isEqualTo(daysAfterBalance > 0 && coverPublished ? cover.getAccession() : balance.getAccession());
    }

    @ParameterizedTest
    @CsvSource({"-40", "0"})
    void nonPositiveCommonEquityCannotBeAnAvailablePriceToBookRatio(String equity) {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> source = allInputs(catalog);
        FinancialReport balance = source.stream().filter(report -> report.getStatementType() == 2).findFirst().orElseThrow();
        balance.setItems(balance.getItems().stream().map(item -> item.name().equals(FinancialConcept.STOCKHOLDERS_EQUITY.name())
                ? new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal(equity), null, null) : item).toList());
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(source);
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setClosePrice(new BigDecimal("100"));
        old.setPb(new BigDecimal("-2"));
        assertThat(com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(old, null))
                .satisfies(dto -> {
                    assertThat(dto.metrics().pb().status()).isEqualTo("NOT_APPLICABLE");
                    assertThat(dto.status()).isEqualTo("INSUFFICIENT_DATA");
                });
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        aapl.status().setValuationDate(DAY);

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPb()).isNull();
        assertThat(row.getPbReason()).isEqualTo("NON_POSITIVE_COMMON_EQUITY");
        assertThat(row.getPe()).isNotNull();
        assertThat(com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(row, null)
                .metrics().pb().status()).isEqualTo("NOT_APPLICABLE");
    }

    @ParameterizedTest
    @CsvSource({"false,false,4", "true,false,", "false,true,"})
    void historicalMarketCapUsesDisclosedSharesOnKnownSplitBasis(
            boolean unpublished, boolean splitBeforeFiling, String expectedPs) {
        aapl.stock().setCik("0000000001");
        SecFinancialFact shares = secFlow("CommonStockSharesOutstanding", "10");
        shares.setUnit("shares");
        shares.setPeriodStart(null);
        shares.setFiled(DAY.minusDays(5));
        shares.setAvailableFromDate(unpublished ? DAY.plusDays(1) : DAY.minusDays(4));
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setSourceEventId("SPLIT_AFTER_FILING");
        split.setExecutionDate(splitBeforeFiling ? DAY.minusDays(7) : DAY.minusDays(1));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(new BigDecimal("2"));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(shares, secFlow("Revenues", "500")));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        if (expectedPs == null) {
            assertThat(row.getPs()).isNull();
            assertThat(row.getSecShareAccession()).isNull();
        } else {
            assertThat(row.getPs()).isEqualByComparingTo(expectedPs);
            assertThat(row.getSecShareAccession()).isEqualTo(shares.getAccession());
            assertThat(row.getSecShareBasisDate()).isEqualTo(shares.getPeriodEnd());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,2.4", "true,4"})
    void historicalMarketCapPrefersFutuThenUsesLatestPublicSecShares(boolean hasFutu, String expectedPs) {
        aapl.stock().setCik("0000000001");
        SecFinancialFact latest = secFlow("CommonStockSharesOutstanding", "12");
        latest.setUnit("shares");
        latest.setPeriodStart(null);
        SecFinancialFact prior = secFlow("CommonStockSharesOutstanding", "9");
        prior.setPeriodEnd(latest.getPeriodEnd().minusMonths(3));
        prior.setUnit("shares");
        prior.setPeriodStart(null);
        SecFinancialFact unpublished = secFlow("CommonStockSharesOutstanding", "999");
        unpublished.setPeriodEnd(latest.getPeriodEnd().plusDays(1));
        unpublished.setAvailableFromDate(DAY.plusDays(1));
        unpublished.setUnit("shares");
        unpublished.setPeriodStart(null);
        SecFinancialFact assets = secFlow("Assets", "700");
        assets.setPeriodEnd(latest.getPeriodEnd().plusDays(1));
        assets.setPeriodStart(null);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(prior, latest, unpublished, assets, secFlow("Revenues", "500")));
        if (hasFutu) {
            StockShareObservation observation = shareObservation(CLOSE.minusSeconds(1));
            observation.setIssuedShares(20);
            when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(observation));
        }

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPs()).isEqualByComparingTo(expectedPs);
        assertThat(row.getSecShareBasisDate()).isEqualTo(hasFutu ? null : latest.getPeriodEnd());
    }

    @Test
    void aNamedShareClassCannotUseAnIssuerTotalEvenWhenOnlyOneTickerIsListed() {
        aapl.stock().setCik("0000000001");
        aapl.stock().setLongName("Example Holdings Class A Common Stock");
        SecFinancialFact shares = secFlow("CommonStockSharesOutstanding", "10");
        shares.setUnit("shares");
        shares.setPeriodStart(null);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(shares, secFlow("Revenues", "500")));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPs()).isNull();
        assertThat(saved.getFirst().getSecShareAccession()).isNull();
    }

    @Test
    void annualSecEpsRequiresSplitCoverageFromItsYearEndRatherThanYearStart() {
        aapl.stock().setCik("0000000001");
        SecFinancialFact annual = secFlow("EarningsPerShareDiluted", "5");
        annual.setUnit("USD/shares");
        aapl.status().setSplitFromDate(annual.getPeriodEnd());
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(annual));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getEpsBasisFromDate()).isEqualTo(annual.getPeriodEnd());
    }

    @Test
    void fillsMissingQuarterlyRevenueAndEpsWithoutReplacingOtherFutuQuarters() {
        assertMixedQuarter(false);
    }

    @Test
    void fillsAbsentFutuQuarterFromContiguousSecPeriods() {
        assertMixedQuarter(true);
    }

    private void assertMixedQuarter(boolean absentQuarter) {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = new ArrayList<>();
        FinancialReport.Item revenue = input(catalog, 1, FinancialConcept.REVENUE_AS_REPORTED, "100");
        FinancialReport.Item eps = input(catalog, 1, FinancialConcept.DILUTED_EPS, "1");
        LocalDate firstEnd = LocalDate.of(2025, 12, 31);
        for (int index = 0; index < 4; index++) {
            if (absentQuarter && index == 1) continue;
            LocalDate end = firstEnd.plusMonths(3L * index);
            FinancialReport quarter = report("AAPL", 1, index == 0 ? 4 : index, end);
            quarter.setFiscalYear(end.getYear());
            quarter.setItems(index == 1 ? List.of() : List.of(revenue, eps));
            inputs.add(quarter);
        }
        SecFinancialFact missingQuarter = secFlow("Revenues", "200");
        missingQuarter.setPeriodStart(LocalDate.of(2026, 1, 1));
        missingQuarter.setPeriodEnd(LocalDate.of(2026, 3, 31));
        SecFinancialFact laterQuarter = secFlow("Revenues", "900");
        laterQuarter.setPeriodStart(LocalDate.of(2026, 7, 1));
        laterQuarter.setPeriodEnd(LocalDate.of(2026, 9, 30));
        SecFinancialFact missingEps = secFlow("EarningsPerShareDiluted", "2");
        missingEps.setUnit("USD/shares");
        missingEps.setPeriodStart(missingQuarter.getPeriodStart());
        missingEps.setPeriodEnd(missingQuarter.getPeriodEnd());
        SecFinancialFact followingQuarter = secFlow("Revenues", "800");
        followingQuarter.setPeriodStart(LocalDate.of(2026, 4, 1));
        followingQuarter.setPeriodEnd(LocalDate.of(2026, 6, 30));
        aapl.stock().setCik("0000000001");
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(missingQuarter, followingQuarter, laterQuarter, missingEps));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPs()).isEqualByComparingTo("2");
        assertThat(saved.getFirst().getPsFinancialSource()).isEqualTo("FUTU_AND_SEC");
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getPeFinancialSource()).isEqualTo("FUTU_AND_SEC");
    }

    @Test
    void extendsKnownFutuQ4ToNewerSecQ3ForSameFourQuarterEpsBasis() {
        assertTrailingSecLatestQuarter(true);
    }

    @Test
    void fillsThreeFollowingSecQuartersFromKnownFutuFourthQuarter() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        FinancialReport.Item eps = input(catalog, 1, FinancialConcept.DILUTED_EPS, "1");
        FinancialReport q4 = report("AAPL", 1, 4, LocalDate.of(2025, 12, 31));
        q4.setFiscalYear(2025);
        q4.setItems(List.of(eps));
        List<SecFinancialFact> sec = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            LocalDate start = LocalDate.of(2026, 1 + index * 3, 1);
            LocalDate end = start.plusMonths(3).minusDays(1);
            SecFinancialFact quarter = secFlow("EarningsPerShareDiluted", "1");
            quarter.setUnit("USD/shares");
            quarter.setPeriodStart(start);
            quarter.setPeriodEnd(end);
            quarter.setAccession("0000000001-26-00000" + (index + 1));
            sec.add(quarter);
        }
        aapl.stock().setCik("0000000001");
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(q4));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(sec);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPe()).isEqualByComparingTo("25");
        assertThat(row.getPeReason()).isNull();
        assertThat(row.getPeFinancialSource()).isEqualTo("FUTU_AND_SEC");
        assertThat(row.getIncomePeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(row.getEpsBasisFromDate()).isEqualTo(q4.getPeriodEnd());
    }

    @Test
    void newerSecQuarterReplacesAlreadyAvailableButStaleFutuTtm() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        FinancialReport.Item eps = input(catalog, 1, FinancialConcept.DILUTED_EPS, "1");
        FinancialReport.Item previousEps = new FinancialReport.Item(eps.fieldId(), eps.name(), new BigDecimal("2"), null, null);
        FinancialReport q3 = report("AAPL", 1, 3, LocalDate.of(2025, 9, 30));
        q3.setFiscalYear(2025);
        q3.setItems(List.of(previousEps));
        FinancialReport q4 = report("AAPL", 1, 4, LocalDate.of(2025, 12, 31));
        q4.setFiscalYear(2025);
        q4.setItems(List.of(eps));
        FinancialReport q1 = report("AAPL", 1, 1, LocalDate.of(2026, 3, 31));
        q1.setFiscalYear(2026);
        q1.setItems(List.of(eps));
        FinancialReport q2 = report("AAPL", 1, 2, LocalDate.of(2026, 6, 30));
        q2.setFiscalYear(2026);
        q2.setItems(List.of(eps));
        SecFinancialFact q3Sec = secFlow("EarningsPerShareDiluted", "1");
        q3Sec.setUnit("USD/shares");
        q3Sec.setPeriodStart(LocalDate.of(2026, 7, 1));
        q3Sec.setPeriodEnd(LocalDate.of(2026, 9, 30));
        aapl.stock().setCik("0000000001");
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(q3, q4, q1, q2));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(q3Sec));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPe()).isEqualByComparingTo("25");
        assertThat(row.getPeReason()).isNull();
        assertThat(row.getPeFinancialSource()).isEqualTo("FUTU_AND_SEC");
        assertThat(row.getIncomePeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(row.getEpsBasisFromDate()).isEqualTo(q4.getPeriodEnd());
    }

    @Test
    void completeSecFourQuarterEpsTakesPrecedenceOverInconsistentMixedFutuEps() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        FinancialReport.Item mapped = input(catalog, 1, FinancialConcept.DILUTED_EPS, "1");
        List<FinancialReport> quarters = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            LocalDate end = LocalDate.of(2025, 12, 31).plusMonths(3L * index);
            FinancialReport quarter = report("AAPL", 1, index == 0 ? 4 : index, end);
            quarter.setFiscalYear(end.getYear());
            quarter.setItems(List.of(new FinancialReport.Item(mapped.fieldId(), mapped.name(),
                    new BigDecimal(index == 0 ? "0.5" : index == 1 ? "0.7" : "0.8"), null, null)));
            quarters.add(quarter);
        }
        List<SecFinancialFact> sec = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            LocalDate start = LocalDate.of(2025, 10, 1).plusMonths(3L * index);
            SecFinancialFact quarter = secFlow("EarningsPerShareDiluted", "1");
            quarter.setUnit("USD/shares");
            quarter.setPeriodStart(start);
            quarter.setPeriodEnd(start.plusMonths(3).minusDays(1));
            quarter.setAccession("0000000001-26-00000" + (index + 1));
            sec.add(quarter);
        }
        aapl.stock().setCik("0000000001");
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(quarters);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(sec);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPe()).isEqualByComparingTo("25");
        assertThat(row.getPeReason()).isNull();
        assertThat(row.getPeFinancialSource()).isEqualTo("SEC");
        assertThat(row.getIncomePeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(row.getEpsBasisFromDate()).isEqualTo(quarters.getFirst().getPeriodEnd());
    }

    @Test
    void newerSecQuarterWithNoncontiguousStartDoesNotCompleteFutuEpsTtm() {
        assertTrailingSecLatestQuarter(false);
    }

    private void assertTrailingSecLatestQuarter(boolean contiguous) {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        FinancialReport.Item eps = input(catalog, 1, FinancialConcept.DILUTED_EPS, "1");
        FinancialReport q4 = report("AAPL", 1, 4, LocalDate.of(2025, 12, 31));
        q4.setFiscalYear(2025);
        q4.setItems(List.of(eps));
        FinancialReport q1 = report("AAPL", 1, 1, LocalDate.of(2026, 3, 31));
        q1.setFiscalYear(2026);
        q1.setItems(List.of(eps));
        FinancialReport q2 = report("AAPL", 1, 2, LocalDate.of(2026, 6, 30));
        q2.setFiscalYear(2026);
        q2.setItems(List.of(eps));
        SecFinancialFact latest = secFlow("EarningsPerShareDiluted", "1");
        latest.setUnit("USD/shares");
        latest.setPeriodStart(LocalDate.of(2026, 7, contiguous ? 1 : 2));
        latest.setPeriodEnd(LocalDate.of(2026, 9, 30));
        aapl.stock().setCik("0000000001");
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(q4, q1, q2));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(latest));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        if (contiguous) {
            assertThat(row.getPe()).isEqualByComparingTo("25");
            assertThat(row.getPeReason()).isNull();
            assertThat(row.getIncomePeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(row.getEpsBasisFromDate()).isEqualTo(q4.getPeriodEnd());
            assertThat(row.getPeFinancialSource()).isEqualTo("FUTU_AND_SEC");
        } else {
            assertThat(row.getPe()).isNull();
            assertThat(row.getPeReason()).isEqualTo("EPS_TTM_PERIODS_MISSING");
        }
    }

    @Test
    void newlyAvailableSamePeriodPbIsNotLostWhenUnrelatedOldMetricsNeedRetention() {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> available = allInputs(catalog).stream()
                .filter(report -> report.getStatementType() == 2).toList();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(available);
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE)))
                .thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPb(null);
        old.setPbReason("SEC_COMMON_EQUITY_UNRESOLVED");
        old.setBalancePeriodEnd(available.getFirst().getPeriodEnd());
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPbReason()).isNull();
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
        });
    }

    @Test
    void newlyAvailableSamePeriodPeIsNotLostWhenUnrelatedOldMetricsNeedRetention() {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> available = allInputs(catalog).stream()
                .filter(report -> report.getStatementType() == 1).toList();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(available);
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE)))
                .thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setPe(null);
        old.setPeReason("SEC_EPS_TTM_PERIODS_MISSING");
        old.setIncomePeriodEnd(available.getFirst().getPeriodEnd());
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPeReason()).isNull();
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
        });
    }

    @Test
    void olderBalanceCannotRefillNewerKnownNullPbWhenOtherMetricsAreRetained() {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> older = allInputs(catalog).stream()
                .filter(report -> report.getStatementType() == 2).toList();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(older);
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE)))
                .thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPb(null);
        old.setPbReason("SEC_COMMON_EQUITY_UNRESOLVED");
        old.setBalancePeriodEnd(DAY.minusDays(11));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(old.getPb()).isNull();
        assertThat(old.getBalancePeriodEnd()).isEqualTo(DAY.minusDays(11));
        assertThat(old.getPs()).isEqualByComparingTo("1");
        assertThat(saved).isEmpty();
    }

    @Test
    void incompleteNewerReportDoesNotSilentlyKeepAnOlderAnnualMultiple() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        inputs.getFirst().setPeriodEnd(LocalDate.of(2024, 9, 30));
        StockValuationDaily old = trustedRow();
        old.setPsFinancialSource("FUTU");
        old.setPsPeriodEnd(inputs.getFirst().getPeriodEnd());
        old.setFinancialObservedAt(inputs.getFirst().getObservedAt());
        old.setSharesObservedAt(CLOSE.minusSeconds(1));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        SecFinancialFact recentQuarter = secFlow("Revenues", "100");
        recentQuarter.setPeriodStart(LocalDate.of(2026, 7, 1));
        recentQuarter.setPeriodEnd(LocalDate.of(2026, 9, 30));
        aapl.stock().setCik("0000000001");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(recentQuarter));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPs()).isNull();
        assertThat(saved.getFirst().getPsPeriodEnd()).isEqualTo(recentQuarter.getPeriodEnd());
        assertThat(saved.getFirst().getPsReason()).isEqualTo("TTM_PERIODS_MISSING");
    }

    @ParameterizedTest
    @CsvSource({"0,0,true", "1,0,true", "0,1,false"})
    void onlyEligibleSecIncomePeriodCanWithdrawOlderPeAndPs(int availableDaysAfterClose, int observedSecondsAfterRun, boolean eligible) {
        aapl.stock().setCik("0000000001");
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        LocalDate olderPeriod = inputs.getFirst().getPeriodEnd();
        LocalDate newerPeriod = LocalDate.of(2026, 9, 30);
        // 原 P/FCF 的財報暫不可讀；新的損益表不能撤掉獨立的現金流倍數。
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs.subList(0, 2));
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setIncomePeriodEnd(olderPeriod);
        old.setBalancePeriodEnd(olderPeriod);
        old.setPsPeriodEnd(olderPeriod);
        old.setPfcfPeriodEnd(olderPeriod);
        old.setEvEbitda(null);
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        SecFinancialFact newestIncome = secFlow("NetIncomeLoss", "600");
        newestIncome.setForm("10-Q");
        newestIncome.setPeriodStart(LocalDate.of(2026, 7, 1));
        newestIncome.setPeriodEnd(newerPeriod);
        newestIncome.setAvailableFromDate(DAY.plusDays(availableDaysAfterClose));
        newestIncome.setObservedAt(NOW.plusSeconds(observedSecondsAfterRun));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(newestIncome));

        task.execute(context(DAY));

        if (eligible) {
            assertThat(saved).hasSize(1);
            StockValuationDaily row = saved.getFirst();
            assertThat(row.getPe()).isNull();
            assertThat(row.getPs()).isNull();
            assertThat(row.getIncomePeriodEnd()).isEqualTo(newerPeriod);
            assertThat(row.getPsPeriodEnd()).isEqualTo(newerPeriod);
            assertThat(row.getPeReason()).isEqualTo("EPS_TTM_PERIODS_MISSING");
            assertThat(row.getPsReason()).isEqualTo("REVENUE_MISSING");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
            assertThat(row.getPfcfPeriodEnd()).isEqualTo(olderPeriod);
        } else {
            StockValuationDaily row = saved.isEmpty() ? old : saved.getFirst();
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getIncomePeriodEnd()).isEqualTo(olderPeriod);
            assertThat(row.getPsPeriodEnd()).isEqualTo(olderPeriod);
        }
    }

    @Test
    void latestSecBalanceSheetWithNoEquityRevokesPriorFutuPbAndEvWithoutAffectingPe() {
        aapl.stock().setCik("0000000001");
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        LocalDate olderPeriod = inputs.getFirst().getPeriodEnd();
        LocalDate newerPeriod = LocalDate.of(2026, 9, 30);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setIncomePeriodEnd(olderPeriod);
        old.setBalancePeriodEnd(olderPeriod);
        old.setEvEbitdaPeriodEnd(olderPeriod);
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        SecFinancialFact newestBalance = secFlow("Assets", "1600");
        newestBalance.setForm("10-Q");
        newestBalance.setPeriodStart(null);
        newestBalance.setPeriodEnd(newerPeriod);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(newestBalance));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPb()).isNull();
        assertThat(row.getPbReason()).isEqualTo("COMMON_EQUITY_UNRESOLVED");
        assertThat(row.getBalancePeriodEnd()).isEqualTo(newerPeriod);
        assertThat(row.getEvEbitda()).isNull();
        assertThat(row.getEvEbitdaReason()).isEqualTo("EV_COMPONENTS_UNRESOLVED");
        assertThat(row.getPe()).isEqualByComparingTo("20");
        assertThat(row.getIncomePeriodEnd()).isEqualTo(olderPeriod);
    }

    @Test
    void doesNotCombine2024BalanceWith2026IncomeForEvEbitda() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        inputs.get(1).setPeriodEnd(inputs.getFirst().getPeriodEnd().minusYears(2));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getEvEbitda()).isNull();
        assertThat(saved.getFirst().getEvEbitdaReason()).isEqualTo("FINANCIAL_PERIOD_CONFLICT");
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
    }

    @Test
    void fillsMissingQuarterBeforeTtmWithoutUsingAnAnnualAmountFromAnotherReport() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = new ArrayList<>(allInputs(catalog));
        inputs.removeIf(row -> row.getStatementType() == 3);
        FinancialReport.Item cfo = input(catalog, 3, FinancialConcept.OPERATING_CASH_FLOW, "100");
        FinancialReport.Item capex = input(catalog, 3, FinancialConcept.CAPITAL_EXPENDITURE, "10");
        for (int index = 0; index < 4; index++) {
            LocalDate end = LocalDate.of(2025, 12, 31).plusMonths(3L * index);
            FinancialReport cash = report("AAPL", 3, index == 0 ? 4 : index, end);
            cash.setFiscalYear(end.getYear());
            cash.setItems(index == 1 ? List.of(cfo) : List.of(cfo, capex));
            inputs.add(cash);
        }
        aapl.stock().setCik("0000000001");
        SecFinancialFact quarter = secFlow("PaymentsToAcquireProductiveAssets", "10");
        quarter.setPeriodStart(LocalDate.of(2026, 1, 1));
        quarter.setPeriodEnd(LocalDate.of(2026, 3, 31));
        SecFinancialFact annual = secFlow("PaymentsToAcquireProductiveAssets", "80");
        annual.setPeriodStart(LocalDate.of(2025, 10, 1));
        annual.setPeriodEnd(LocalDate.of(2026, 9, 30));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(quarter, annual));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPfcf()).isEqualByComparingTo(new BigDecimal("1000").divide(new BigDecimal("360"), java.math.MathContext.DECIMAL128));
        assertThat(saved.getFirst().getPfcfFinancialSource()).isEqualTo("FUTU_AND_SEC");
    }

    @Test
    void nullWatermarkRebuildReplacesEachExistingRowWithOnlyCurrentResults() {
        LocalDate first = DAY.minusDays(1);
        ReflectionTestUtils.setField(task, "historyStartDate", first);
        when(calendar.isTradingDay(first)).thenReturn(true);
        Instant firstClose = CLOSE.minusSeconds(86400);
        when(calendar.getSessionCloseAt(first)).thenReturn(firstClose);
        StockQuote firstQuote = quote("AAPL");
        firstQuote.setQuoteDate(first);
        when(quotes.findStoredQuotes(anyCollection(), eq(first))).thenReturn(List.of(firstQuote));
        when(reports.findValuationReports(anyCollection(), eq(first), eq(NOW)))
                .thenReturn(List.of(report("AAPL", 1, 7, first.minusMonths(2))));
        StockValuationDaily existing = new StockValuationDaily();
        existing.setFigi("AAPL");
        existing.setValuationDate(DAY);
        existing.setClosePrice(new BigDecimal("100"));
        existing.setEvEbitda(BigDecimal.TEN);
        when(valuations.findByFigiInAndValuationDate(List.of("AAPL"), DAY)).thenReturn(List.of(existing));

        task.execute(context(null));

        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(saved).hasSize(2);
        assertThat(saved.getLast().getEvEbitda()).isNull();
        org.mockito.InOrder writes = inOrder(entityManager);
        writes.verify(entityManager).remove(existing);
        writes.verify(entityManager).flush();
        writes.verify(entityManager).persist(saved.getLast());
        assertThat(saved.getLast().getUpdatedAt()).isEqualTo(WRITE_TIME);
        verify(entityManager, never()).merge(any(StockValuationDaily.class));
    }

    @Test
    void nullValuationWatermarkStartsIn2016WithoutWaitingForFutuDownloadDate() {
        LocalDate first = LocalDate.of(2016, 1, 4);
        LocalDate last = first.plusDays(2);
        ReflectionTestUtils.setField(task, "historyStartDate", LocalDate.of(2016, 1, 1));
        aapl.status().setValuationDate(null);
        aapl.status().setQuoteDate(last);
        aapl.stock().setListDate(null);
        when(calendar.getNextTradingDay(LocalDate.of(2016, 1, 1))).thenReturn(first);
        java.util.TreeMap<LocalDate, ?> planned = ReflectionTestUtils.invokeMethod(
                task, "planDates", List.of(aapl), null, last, java.util.Map.of(), LocalDate.of(2016, 1, 1));
        assertThat(planned).containsOnlyKeys(first, first.plusDays(1), last);
    }

    @Test
    void missingLeaseUsesMatchingOlderSecPeriodEvenWhenNewerSecPeriodExists() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport balance = inputs.get(1);
        balance.setItems(balance.getItems().stream().map(item -> item.name().equals(FinancialConcept.CURRENT_CAPITAL_LEASES.name())
                ? new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal("30"), null, null) : item).toList());
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        SecFinancialFact older = secFlow("OperatingLeaseLiabilityCurrent", "20");
        older.setPeriodStart(null);
        older.setPeriodEnd(balance.getPeriodEnd());
        SecFinancialFact newer = secFlow("OperatingLeaseLiabilityCurrent", "99");
        newer.setPeriodStart(null);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(older, newer));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getEvEbitda()).isEqualByComparingTo("11.8");
        assertThat(saved.getFirst().getEvEbitdaFinancialSource()).isEqualTo("FUTU_AND_SEC");
    }

    @Test
    void afterCloseSnapshotUsesTradingSessionDateRatherThanExactLastSecond() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation observation = shareObservation(CLOSE.plusSeconds(100));
        observation.setSnapshotQuoteTime(CLOSE.minusSeconds(120));
        observation.setSnapshotPrice(new BigDecimal("100"));
        observation.setSnapshotMarketCap(new BigDecimal("2000"));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), any(), eq(CLOSE), eq(NOW))).thenReturn(List.of(observation));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPb()).isEqualByComparingTo("4");
        assertThat(saved.getFirst().getMarketCapSource()).isEqualTo("FUTU_CLOSE_SNAPSHOT");
    }

    @Test
    void missingFutuFcfUsesFutuOperatingCashAndOnlyMissingSecCapex() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport cash = inputs.get(2);
        cash.setItems(List.of(input(catalog, 3, FinancialConcept.OPERATING_CASH_FLOW, "200")));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        SecFinancialFact capex = secFlow("PaymentsToAcquireProductiveAssets", "75");
        capex.setPeriodEnd(cash.getPeriodEnd());
        capex.setPeriodStart(capex.getPeriodEnd().minusYears(1).plusDays(1));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(capex));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPfcf()).isEqualByComparingTo("8");
        assertThat(saved.getFirst().getPfcfFinancialSource()).isEqualTo("FUTU_AND_SEC");
    }

    @Test
    void missingFutuOperatingProfitUsesOnlySamePeriodSecOperatingIncomeWithFutuDepreciation() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport income = inputs.getFirst();
        income.setItems(income.getItems().stream().filter(item -> !item.name().equals(FinancialConcept.OPERATING_PROFIT.name())).toList());
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        SecFinancialFact operatingProfit = secFlow("OperatingIncomeLoss", "80");
        operatingProfit.setPeriodEnd(income.getPeriodEnd());
        operatingProfit.setPeriodStart(operatingProfit.getPeriodEnd().minusYears(1).plusDays(1));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(operatingProfit));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getEvEbitda()).isEqualByComparingTo("12");
        assertThat(saved.getFirst().getEvEbitdaFinancialSource()).isEqualTo("FUTU_AND_SEC");
    }

    @Test
    void secDoesNotReplacePureFutuDepreciationAndAmortizationComponents() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport income = inputs.getFirst();
        List<FinancialReport.Item> items = new ArrayList<>(income.getItems().stream().filter(item ->
                !item.name().equals(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION.name())).toList());
        items.add(input(catalog, 1, FinancialConcept.INCOME_DEPRECIATION, "12"));
        items.add(input(catalog, 1, FinancialConcept.INCOME_AMORTIZATION, "8"));
        income.setItems(items);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        SecFinancialFact operatingProfit = secFlow("OperatingIncomeLoss", "110");
        SecFinancialFact depreciation = secFlow("Depreciation", "25");
        SecFinancialFact amortization = secFlow("AmortizationOfIntangibleAssets", "5");
        for (SecFinancialFact fact : List.of(operatingProfit, depreciation, amortization)) {
            fact.setPeriodEnd(income.getPeriodEnd());
            fact.setPeriodStart(fact.getPeriodEnd().minusYears(1).plusDays(1));
        }
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(operatingProfit, depreciation, amortization));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        assertThat(saved.getLast().getEvEbitda()).isEqualByComparingTo("12");
        assertThat(saved.getLast().getEvEbitdaFinancialSource()).isEqualTo("FUTU");
    }

    @Test
    void secDepreciationComponentsOnlyFillTheSamePeriodWhenFutuLacksThem() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport income = inputs.getFirst();
        List<FinancialReport.Item> original = income.getItems();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        SecFinancialFact depreciation = secFlow("Depreciation", "25");
        SecFinancialFact amortization = secFlow("AmortizationOfIntangibleAssets", "5");
        for (SecFinancialFact fact : List.of(depreciation, amortization)) {
            fact.setPeriodEnd(income.getPeriodEnd());
            fact.setPeriodStart(fact.getPeriodEnd().minusYears(1).plusDays(1));
        }
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(depreciation, amortization));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getLast().getEvEbitda()).isEqualByComparingTo("12");
        assertThat(saved.getLast().getEvEbitdaFinancialSource()).isEqualTo("FUTU");

        income.setItems(original.stream().filter(item -> !item.name().equals(FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION.name())).toList());
        task.execute(context(DAY));
        assertThat(saved.getLast().getEvEbitda()).isEqualByComparingTo(new BigDecimal("1200").divide(new BigDecimal("110"), java.math.MathContext.DECIMAL128));
        assertThat(saved.getLast().getEvEbitdaFinancialSource()).isEqualTo("FUTU_AND_SEC");

        depreciation.setAvailableFromDate(DAY.plusDays(1));
        task.execute(context(DAY));
        assertThat(saved.getLast().getEvEbitda()).isEqualByComparingTo(new BigDecimal("1200").divide(new BigDecimal("110"), java.math.MathContext.DECIMAL128));
        depreciation.setObservedAt(NOW.plusSeconds(1));
        task.execute(context(DAY));
        assertThat(saved.getLast().getEvEbitda()).isNull();
    }

    @Test
    void newerPublicReportingPeriodTakesPriorityOverOlderCompleteFutuValues() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(secFlows("2000", "200", "50"));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPs()).isEqualByComparingTo("0.5");
        assertThat(saved.getFirst().getPfcf()).isEqualByComparingTo(new BigDecimal("1000").divide(new BigDecimal("150"), java.math.MathContext.DECIMAL128));
        assertThat(saved.getFirst().getPsFinancialSource()).isEqualTo("SEC");
    }

    @Test
    void secFinanceLeaseSplitCompletesFutuDebtWithoutReplacingFutuCashOrEbitda() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport balance = inputs.get(1);
        balance.setItems(balance.getItems().stream().map(item ->
                item.name().equals(FinancialConcept.CURRENT_CAPITAL_LEASES.name())
                        ? new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal("30"), null, null) : item).toList());
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        SecFinancialFact operating = secFlow("OperatingLeaseLiabilityCurrent", "20");
        operating.setPeriodStart(null);
        operating.setPeriodEnd(balance.getPeriodEnd());
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(operating));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        task.execute(context(DAY));
        // Futu 債務租賃合計 100 + 150，扣 SEC 營運租賃 20，再扣 Futu 現金 50。
        assertThat(saved.getFirst().getEvEbitda()).isEqualByComparingTo("11.8");
        assertThat(saved.getFirst().getEvEbitdaFinancialSource()).isEqualTo("FUTU_AND_SEC");
    }

    private static final LocalDate DAY = LocalDate.of(2026, 11, 11);

    @Test
    void batchSavesEachStockOnlyOnceAfterConfirmingDepreciation() {
        aapl.stock().setCik("0000000001");
        ActiveStockWithStatus other = target("OTHER");
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, DAY)).thenReturn(List.of(aapl, other));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(aapl.status(), other.status()));
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenReturn(List.of(quote("AAPL"), quote("OTHER")));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> source = allInputs(catalog);
        FinancialReport income = source.getFirst();
        income.setItems(income.getItems().stream()
                .filter(item -> !FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION.name().equals(item.name())).toList());
        FinancialReport otherIncome = report("OTHER", 1, 7, income.getPeriodEnd());
        otherIncome.setItems(List.of(new FinancialReport.Item(1, FinancialConcept.DILUTED_EPS.name(), new BigDecimal("5"), null, null)));
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(List.of("AAPL", "OTHER"), DAY, NOW))
                .thenReturn(List.of(income, source.get(1), source.get(2), otherIncome));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        SecFinancialFact depreciation = secFlow("DepreciationDepletionAndAmortization", "20");
        SecFinancialFact profit = secFlow("OperatingIncomeLoss", "60");
        for (SecFinancialFact fact : List.of(depreciation, profit)) {
            fact.setPeriodEnd(income.getPeriodEnd());
            fact.setPeriodStart(income.getPeriodEnd().minusYears(1).plusDays(1));
        }
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(depreciation, profit));
        when(feedSession.confirm(anyCollection(), eq(CLOSE))).thenAnswer(call -> {
            assertThat(saved).isEmpty();
            verify(statuses, never()).findAllByIdForUpdate(anyCollection());
            verify(statuses, never()).saveAllAndFlush(anyList());
            return new SecEdgarFeedArchive.Confirmation(facts -> Set.of(SecFinancialFactConverter.key(depreciation)), List.of());
        });

        task.execute(context(DAY));

        assertThat(saved).extracting(StockValuationDaily::getFigi).containsExactly("AAPL", "OTHER");
        assertThat(saved.getFirst().getEvEbitda()).isEqualByComparingTo("12");
        assertThat(saved.getFirst().getEvEbitdaFinancialSource()).isEqualTo("FUTU_AND_SEC");
        assertThat(saved.getLast().getPe()).isEqualByComparingTo("20");
        verify(reports).findValuationReports(anyCollection(), eq(DAY), eq(NOW));
        verify(secFacts).findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW));
        verify(statuses).saveAllAndFlush(anyList());
        verify(feedSession).confirm(argThat(facts -> facts.size() == 1 && facts.contains(depreciation)), eq(CLOSE));
    }

    @Test
    void pureFutuDepreciationNeedsNoEdgarArchive() {
        aapl.stock().setCik("0000000001");
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> source = allInputs(catalog);
        SecFinancialFact depreciation = secFlow("DepreciationDepletionAndAmortization", "90");
        depreciation.setPeriodEnd(source.getFirst().getPeriodEnd());
        depreciation.setPeriodStart(depreciation.getPeriodEnd().minusYears(1).plusDays(1));
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(source);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(depreciation));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getEvEbitda()).isEqualByComparingTo("12");
            assertThat(row.getEvEbitdaFinancialSource()).isEqualTo("FUTU");
        });
        verify(feedSession, never()).confirm(anyCollection(), any());
    }

    @Test
    void secPreparesOnlyClosedDatesAfterTheWatermarkWithStoredQuotes() {
        aapl.stock().setCik("0000000001");
        aapl.status().setValuationDate(DAY.minusDays(1));
        SecFinancialFact depreciation = secFlow("DepreciationDepletionAndAmortization", "20");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(depreciation));

        assertThat(task.pendingEdgarFilings(NOW, DAY.minusMonths(1))).containsExactlyEntriesOf(Map.of(CLOSE, List.of(depreciation)));
        verify(reports).findValuationReports(anyCollection(), eq(DAY), eq(NOW));
        verify(reports, never()).findValuationReports(anyCollection(), eq(DAY.plusDays(1)), any());
        verifyNoInteractions(edgarFeed, futu, entityManager);

        aapl.status().setValuationDate(DAY);
        assertThat(task.pendingEdgarFilings(NOW, DAY.minusMonths(1))).isEmpty();
        verify(reports).findValuationReports(anyCollection(), eq(DAY), eq(NOW));
    }

    @Test
    void missingEdgarJsonSavesOtherMetricsAndDoesNotScheduleAReplay() {
        aapl.stock().setCik("0000000001");
        SecFinancialFact depreciation = secFlow("DepreciationDepletionAndAmortization", "20");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(depreciation));
        when(feedSession.confirm(anyCollection(), eq(CLOSE))).thenReturn(new SecEdgarFeedArchive.Confirmation(
                facts -> Set.of(), List.of(), Set.of(SecFinancialFactConverter.key(depreciation))));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNotNull();
            assertThat(row.getEvEbitda()).isNull();
            assertThat(row.getEvEbitdaReason()).isEqualTo("SEC_EDGAR_FEED_CACHE_NOT_READY");
        });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(aapl.status().getValuationMessage()).contains("EV_EBITDA=SEC_EDGAR_FEED_CACHE_NOT_READY");
        when(quotes.findFirstQuoteDates(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{"AAPL", DAY}));
        task.execute(context((LocalDate) null));
        verify(feedSession).confirm(anyCollection(), eq(CLOSE));
        assertThat(saved).hasSize(1);
    }

    @Test
    void archiveFailureKeepsIndependentMetricsAndMakesTheJobFailureVisible() {
        aapl.stock().setCik("0000000001");
        SecFinancialFact depreciation = secFlow("DepreciationDepletionAndAmortization", "20");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(depreciation));
        when(feedSession.confirm(anyCollection(), eq(CLOSE))).thenAnswer(call -> {
            assertThat(saved).isEmpty();
            return new SecEdgarFeedArchive.Confirmation(facts -> Set.of(), List.of(depreciation.getFiled()));
        });

        assertThatThrownBy(() -> task.execute(context(DAY))).hasMessageStartingWith("SEC_EDGAR_FEED_PARTIAL_FAILURE");
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNotNull();
            assertThat(row.getEvEbitda()).isNull();
            assertThat(row.getEvEbitdaReason()).isEqualTo("SEC_DEPRECIATION_AMORTIZATION_SCOPE_UNRESOLVED");
        });
    }
    private static final Instant CLOSE = Instant.parse("2026-11-11T21:00:00Z");
    private static final Instant OPEN = Instant.parse("2026-11-11T14:30:00Z");
    private static final Instant NOW = CLOSE.plusSeconds(86400);
    private static final LocalDateTime WRITE_TIME = LocalDateTime.parse("2026-11-13T05:00:01");
    private final StockRepository stocks = mock(StockRepository.class);
    private final StockStatusRepository statuses = mock(StockStatusRepository.class);
    private final StockQuoteRepository quotes = mock(StockQuoteRepository.class);
    private final StockSplitRepository splits = mock(StockSplitRepository.class);
    private final FinancialReportRepository reports = mock(FinancialReportRepository.class);
    private final FinancialReportItemMappingRepository mappings = mock(FinancialReportItemMappingRepository.class);
    private final StockValuationDailyRepository valuations = mock(StockValuationDailyRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final DateTimeService clock = mock(DateTimeService.class);
    private final MarketStatusService calendar = mock(MarketStatusService.class);
    private final StockShareObservationRepository shareObservations = mock(StockShareObservationRepository.class);
    private final FutuService futu = mock(FutuService.class);
    private final FutuService.ObservationSession session = mock(FutuService.ObservationSession.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final SecFinancialFactRepository secFacts = mock(SecFinancialFactRepository.class);
    private final StockIndustryHistoryRepository industries = mock(StockIndustryHistoryRepository.class);
    private final SecFinancialStatementArchive statementArchives = mock(SecFinancialStatementArchive.class);
    private final SecEdgarFeedArchive edgarFeed = mock(SecEdgarFeedArchive.class);
    private final SecEdgarFeedArchive.Session feedSession = mock(SecEdgarFeedArchive.Session.class);
    private final StockValuationDailyTask task = new StockValuationDailyTask(stocks, statuses, quotes, splits, reports, mappings,
            valuations, new TransactionTemplate(manager), clock, calendar, shareObservations, futu, entityManager, secFacts, industries, statementArchives, edgarFeed,
            new SecIssuerHistory("0000000004:0000000003:2026-11-12:0000000004-26-000003"));
    private final List<StockValuationDaily> saved = new ArrayList<>();
    private ActiveStockWithStatus aapl;

    @Test
    void recalculationPreservesTheExistingReadOnlyStockAssociationOnMerge() {
        aapl.status().setValuationDate(DAY);
        StockValuationDaily old = trustedRow();
        old.setStock(aapl.stock());
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE)))
                .thenReturn(List.of(shareObservation(CLOSE.minusSeconds(60))));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row).isNotSameAs(old);
            assertThat(row.getStock()).isSameAs(old.getStock());
            assertThat(row.getPe()).isEqualByComparingTo("20");
        });
    }

    @Test
    void formalSecRevisionRecalculatesHistoricalValueKeepingOriginalValuationDate() {
        aapl.stock().setCik("0000000001");
        aapl.status().setValuationDate(DAY);
        StockValuationDaily old = trustedRow();
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        SecFinancialFact revised = secFlow("EarningsPerShareDiluted", "4");
        revised.setUnit("USD/shares");
        revised.setForm("10-K/A");
        revised.setFiled(DAY);
        revised.setAvailableFromDate(DAY.plusDays(1));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(revised));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getValuationDate()).isEqualTo(DAY);
            assertThat(row.getPe()).isEqualByComparingTo("25");
            assertThat(row.getSecFinancialAvailableFromDate()).isEqualTo(revised.getAvailableFromDate());
            assertThat(row.getFinancialObservedAt()).isEqualTo(revised.getObservedAt());
        });
    }

    @BeforeEach
    void setup() {
        when(statementArchives.loadDepreciation(anySet())).thenReturn(facts -> java.util.Set.of());
        when(edgarFeed.session()).thenReturn(feedSession);
        when(feedSession.confirm(anyCollection(), any())).thenReturn(new SecEdgarFeedArchive.Confirmation(facts -> Set.of(), List.of()));
        aapl = target("AAPL");
        ReflectionTestUtils.setField(task, "historyStartDate", DAY);
        when(futu.openObservationSession()).thenReturn(session);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of());
        when(clock.now()).thenReturn(NOW);
        when(clock.nowLocalDateTime()).thenReturn(WRITE_TIME);
        when(calendar.getLastClosedTradingDay(NOW)).thenReturn(DAY);
        when(calendar.isTradingDay(DAY)).thenReturn(true);
        when(calendar.getSessionCloseAt(DAY)).thenReturn(CLOSE);
        when(calendar.getSessionOpenAt(any())).thenAnswer(call -> ((LocalDate) call.getArgument(0)).atTime(9, 30).atZone(MarketStatusService.NY_ZONE).toInstant());
        when(calendar.getNextTradingDay(any())).thenAnswer(call -> ((LocalDate) call.getArgument(0)).plusDays(1));
        when(calendar.getPreviousTradingDay(any())).thenAnswer(call -> ((LocalDate) call.getArgument(0)).minusDays(1));
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl));
        when(stocks.findValuationStocksForDate(eq(ActiveStatus.ACTIVE), any(LocalDate.class))).thenReturn(List.of(aapl));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(aapl.status()));
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenReturn(List.of(quote("AAPL")));
        when(mappings.findAll()).thenReturn(List.of(mapping("Diluted EPS", FinancialItemMappingStatus.MAPPED)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(report("AAPL", 1, 7, DAY.minusMonths(2))));
        doAnswer(call -> {
            Object value = call.getArgument(0);
            if (value instanceof StockValuationDaily row) saved.add(row);
            return null;
        }).when(entityManager).persist(any());
        when(entityManager.merge(any(StockValuationDaily.class))).thenAnswer(call -> {
            StockValuationDaily row = call.getArgument(0);
            saved.add(row);
            return row;
        });
    }

    @Test
    void secFactsNotYetObtainedAtCalculationCannotAdvanceWatermarkOrProduceMultiples() {
        aapl.stock().setCik("0000000001");
        List<SecFinancialFact> facts = secFlows("2000", "200", "50");
        for (SecFinancialFact fact : facts) fact.setObservedAt(NOW.plusSeconds(1));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(facts);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        assertThat(saved.getFirst().hasAvailableMetric()).isFalse();
        assertThat(saved.getFirst().getSecFinancialAvailableFromDate()).isNull();
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void newerPublicNegativeFcfReplacesOlderPositiveFutuFcf() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(secFlow("NetCashProvidedByUsedInOperatingActivities", "20"), secFlow("PaymentsToAcquireProductiveAssets", "50")));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setClosePrice(new BigDecimal("100"));
        old.setPfcf(new BigDecimal("20"));
        old.setFinancialObservedAt(CLOSE.minusSeconds(1));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPfcf()).isNull();
        assertThat(row.getPfcfReason()).isEqualTo("NON_POSITIVE_FCF");
        assertThat(row.getPfcfFinancialSource()).isEqualTo("SEC");
        assertThat(row.getPe()).isNotNull();
        assertThat(row.getPb()).isNotNull();
        assertThat(row.getPs()).isNotNull();
    }

    @Test
    void historicalRequestLoadsPublicFactsForTheIdentifiedIssuer() {
        LocalDate oldDay = DAY.minusDays(1);
        aapl.stock().setCik("0000000002");
        when(calendar.isTradingDay(oldDay)).thenReturn(true);
        when(calendar.getSessionCloseAt(oldDay)).thenReturn(CLOSE.minusSeconds(86400));
        StockQuote quote = quote("AAPL");
        quote.setQuoteDate(oldDay);
        when(quotes.findStoredQuotes(anyCollection(), eq(oldDay))).thenReturn(List.of(quote));

        task.execute(context(oldDay));

        verify(secFacts).findValuationFlows(eq(List.of("0000000002")), anyCollection(), eq(oldDay), eq(NOW));
    }

    @Test
    void historicalRecalculationUsesCikRecordedForThatDateInsteadOfCurrentIssuer() {
        LocalDate oldDay = DAY.minusDays(1);
        aapl.stock().setCik("0000000002");
        when(calendar.isTradingDay(oldDay)).thenReturn(true);
        when(calendar.getSessionCloseAt(oldDay)).thenReturn(CLOSE.minusSeconds(86400));
        StockQuote quote = quote("AAPL");
        quote.setQuoteDate(oldDay);
        when(quotes.findStoredQuotes(anyCollection(), eq(oldDay))).thenReturn(List.of(quote));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(oldDay);
        old.setSecFinancialCik("0000000001");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(oldDay))).thenReturn(List.of(old));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(oldDay), eq(NOW))).thenReturn(secFlows("2000", "200", "50"));

        task.execute(context(oldDay));

        verify(secFacts).findValuationFlows(argThat(ciks -> ciks.contains("0000000001") && !ciks.contains("0000000002")), anyCollection(), eq(oldDay), eq(NOW));
    }

    @Test
    void knownSecScopeConflictRevokesOnlyOldSecPsWhileTemporarilyMissingFcfIsRetained() {
        aapl.status().setValuationDate(DAY);
        aapl.stock().setCik("0000000001");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setClosePrice(new BigDecimal("100"));
        old.setPs(new BigDecimal("10"));
        old.setPfcf(new BigDecimal("20"));
        old.setPsFinancialSource("SEC");
        old.setPfcfFinancialSource("SEC");
        old.setSecFinancialCik("0000000001");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        List<SecFinancialFact> facts = new ArrayList<>();
        facts.add(secFlow("Revenues", "100"));
        facts.add(secFlow("SalesRevenueNet", "90"));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(facts);

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPs()).isNull();
        assertThat(saved.getFirst().getPsReason()).isEqualTo("SEC_REVENUE_SCOPE_CONFLICT");
        assertThat(saved.getFirst().getPfcf()).isEqualByComparingTo("20");
        assertThat(old.getUpdatedAt()).isEqualTo(WRITE_TIME);
    }

    @Test
    void secObservationTimeDoesNotEraseTrustedFutuPbAndEvWhenSharesAreTemporarilyMissing() {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        List<SecFinancialFact> facts = secFlows("2000", "200", "50");
        for (SecFinancialFact fact : facts) {
            fact.setPeriodEnd(inputs.getFirst().getPeriodEnd());
            fact.setPeriodStart(fact.getPeriodEnd().minusYears(1).plusDays(1));
        }
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(facts);
        StockValuationDaily old = trustedRow();
        old.setFinancialObservedAt(inputs.getFirst().getObservedAt());
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(old.getPb()).isEqualByComparingTo("2");
        assertThat(old.getEvEbitda()).isEqualByComparingTo("12");
        assertThat(saved).noneMatch(row -> row.getPb() == null || row.getEvEbitda() == null);
    }

    @Test
    void newerSecIncomeRefreshesPsAndRevokesEvWhileMissingBalanceRetainsPb() {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs.stream().filter(row -> row.getStatementType() != 2).toList());
        when(mappings.findAll()).thenReturn(catalog);
        aapl.stock().setCik("0000000001");
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(secFlows("2000", "200", "50"));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setFinancialObservedAt(inputs.getFirst().getObservedAt());
        old.setBalancePeriodEnd(inputs.get(1).getPeriodEnd());
        old.setEvEbitdaPeriodEnd(inputs.getFirst().getPeriodEnd());
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).hasSize(1);
        assertThat(old.getPs()).isEqualByComparingTo("0.5");
        assertThat(old.getPsFinancialSource()).isEqualTo("SEC");
        assertThat(old.getPb()).isEqualByComparingTo("2");
        assertThat(old.getBalancePeriodEnd()).isEqualTo(inputs.get(1).getPeriodEnd());
        assertThat(old.getEvEbitda()).isNull();
        assertThat(old.getEvEbitdaReason()).isEqualTo("EBITDA_COMPONENTS_UNRESOLVED");
        assertThat(old.getEvEbitdaPeriodEnd()).isEqualTo(DAY.minusDays(11));
    }

    @Test
    void reprocessingFillsEvEbitdaWithoutDiscardingPreviouslyAvailablePriceToBook() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport balance = inputs.get(1);
        balance.setItems(balance.getItems().stream().filter(item -> !item.name().equals(FinancialConcept.STOCKHOLDERS_EQUITY.name())).toList());
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(mappings.findAll()).thenReturn(catalog);
        aapl.status().setValuationDate(DAY);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setFinancialObservedAt(inputs.getFirst().getObservedAt());
        old.setEvEbitda(null);
        old.setEvEbitdaReason("SEC_EBITDA_COMPONENTS_UNRESOLVED");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getEvEbitda()).isEqualByComparingTo("12");
            assertThat(row.getEvEbitdaReason()).isNull();
            assertThat(row.getPb()).isEqualByComparingTo("2");
        });
    }

    @Test
    void newNetRevenueEvidenceRevokesOldSecPsForTheSamePeriod() {
        aapl.stock().setCik("0000000001");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        StockValuationDaily old = trustedRow();
        old.setPsFinancialSource("SEC");
        old.setPsPeriodEnd(DAY.minusDays(11));
        old.setSecFinancialCik("0000000001");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(secFlow("Revenues", "100"), secFlow("RevenuesNetOfInterestExpense", "100")));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPs()).isNull();
    }

    @Test
    void newerIncompleteCashFlowPeriodRevokesTrustedPriorSecFcf() {
        aapl.stock().setCik("0000000001");
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));
        StockValuationDaily old = trustedRow();
        old.setPfcfFinancialSource("SEC");
        old.setPfcfPeriodEnd(LocalDate.of(2026, 6, 30));
        old.setSecFinancialCik("0000000001");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        SecFinancialFact cfo = secFlow("NetCashProvidedByUsedInOperatingActivities", "200");
        cfo.setPeriodStart(LocalDate.of(2025, 10, 1));
        cfo.setPeriodEnd(LocalDate.of(2026, 9, 30));
        SecFinancialFact capex = secFlow("PaymentsToAcquireProductiveAssets", "50");
        capex.setPeriodStart(LocalDate.of(2025, 7, 1));
        capex.setPeriodEnd(LocalDate.of(2026, 6, 30));
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(cfo, capex));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPfcf()).isNull();
        assertThat(row.getPfcfPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(row.getPfcfReason()).isEqualTo("SEC_FCF_PERIOD_MISMATCH");
    }

    @Test
    void samePeriodFutuRevenueAndFcfAreSelectedOnceRatherThanAddedToSecValues() {
        aapl.stock().setCik("0000000001");
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> source = allInputs(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(source);
        when(mappings.findAll()).thenReturn(catalog);
        List<SecFinancialFact> facts = secFlows("2000", "200", "50");
        for (SecFinancialFact fact : facts) {
            fact.setPeriodEnd(DAY.minusMonths(2));
            fact.setPeriodStart(fact.getPeriodEnd().minusYears(1).plusDays(1));
        }
        List<SecFinancialFact> withSeparateEps = new ArrayList<>(facts);
        SecFinancialFact secEps = secFlow("EarningsPerShareDiluted", "99");
        secEps.setUnit("USD/shares");
        secEps.setPeriodEnd(DAY.minusMonths(2));
        secEps.setPeriodStart(secEps.getPeriodEnd().minusYears(1).plusDays(1));
        withSeparateEps.add(secEps);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW))).thenReturn(withSeparateEps);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        assertThat(saved.getFirst().getPs()).isEqualByComparingTo("1");
        assertThat(saved.getFirst().getPfcf()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getPsFinancialSource()).isEqualTo("FUTU");
        assertThat(saved.getFirst().getPfcfFinancialSource()).isEqualTo("FUTU");
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
    }

    @ParameterizedTest
    @CsvSource({"true,SEC,0.5", "false,FUTU,1"})
    void missingSourceFallsBackToOneCompleteRevenueAndFcfSource(boolean futuMissing, String expectedSource, String expectedPs) {
        aapl.stock().setCik("0000000001");
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(futuMissing ? inputs.stream().filter(report -> report.getStatementType() == 2).toList() : inputs);
        when(secFacts.findValuationFlows(anyCollection(), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(futuMissing ? secFlows("2000", "200", "50") : List.of());
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(1))));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPs()).isEqualByComparingTo(expectedPs);
        assertThat(row.getPsFinancialSource()).isEqualTo(expectedSource);
        assertThat(row.getPfcfFinancialSource()).isEqualTo(expectedSource);
        assertThat(row.getPfcf()).isEqualByComparingTo(new BigDecimal("1000").divide(new BigDecimal(futuMissing ? "150" : "50"), java.math.MathContext.DECIMAL128));
        assertThat(row.getPb()).isEqualByComparingTo("2");
    }

    @Test
    void commonStockClassesKeepTheirOwnPricesAndRejectIssuerWideShareCounts() {
        ActiveStockWithStatus goog = target("GOOG");
        ActiveStockWithStatus googl = target("GOOGL");
        goog.stock().setCik("0000000002");
        googl.stock().setCik("0000000002");
        when(stocks.findMultiSecurityIssuers(List.of("0000000002"), ActiveStatus.ACTIVE)).thenReturn(java.util.Set.of("0000000002"));
        when(stocks.findValuationStocksForDate(ActiveStatus.ACTIVE, DAY)).thenReturn(List.of(goog, googl));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(goog.status(), googl.status()));
        StockQuote googPrice = quote("GOOG");
        StockQuote googlPrice = quote("GOOGL");
        googlPrice.setClosePrice(new BigDecimal("110"));
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenReturn(List.of(googPrice, googlPrice));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        SecFinancialFact revenue = secFlow("Revenues", "2000");
        SecFinancialFact eps = secFlow("EarningsPerShareDiluted", "5");
        SecFinancialFact companyShares = secFlow("CommonStockSharesOutstanding", "10");
        companyShares.setCik("0000000002");
        companyShares.setUnit("shares");
        companyShares.setPeriodStart(null);
        revenue.setCik("0000000002");
        eps.setCik("0000000002");
        eps.setUnit("USD/shares");
        when(secFacts.findValuationFlows(eq(List.of("0000000002")), anyCollection(), eq(DAY), eq(NOW)))
                .thenReturn(List.of(revenue, eps, companyShares));
        goog.status().setValuationDate(DAY);
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("GOOG");
        old.setValuationDate(DAY);
        old.setClosePrice(new BigDecimal("100"));
        old.setPs(new BigDecimal("0.5"));
        old.setPsFinancialSource("SEC");
        old.setSecFinancialCik("0000000002");
        old.setMarketCapSource("OBSERVED_SHARES");
        old.setSharesObservedAt(CLOSE.minusSeconds(1));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        StockShareObservation googShares = shareObservation(CLOSE.minusSeconds(1));
        StockShareObservation googlShares = shareObservation(CLOSE.minusSeconds(1));
        googShares.setFigi("GOOG");
        googlShares.setFigi("GOOGL");
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(googShares, googlShares));

        task.execute(context(DAY));

        assertThat(saved).hasSize(2);
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getSecFinancialCik()).isEqualTo("0000000002");
            assertThat(row.getPsFinancialSource()).isEqualTo("SEC");
            assertThat(row.getPe()).isNull();
            if ("GOOGL".equals(row.getFigi())) assertThat(row.getPeReason()).isEqualTo("SEC_SHARE_CLASS_EPS_UNVERIFIED");
        });
        assertThat(saved).extracting(StockValuationDaily::getFigi).containsExactly("GOOG", "GOOGL");
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getPs()).isNull();
            assertThat(row.getPsReason()).isEqualTo("VALUATION_DATE_SHARES_MISSING");
            assertThat(row.getSecShareAccession()).isNull();
        });
        assertThat(saved.get(0).getMarketCapSource()).isNull();
        assertThat(saved.get(0).getClosePrice()).isEqualByComparingTo("100");
        assertThat(saved.get(1).getClosePrice()).isEqualByComparingTo("110");
    }

    @Test
    void bankCashFlowAndEnterpriseMultiplesAreNotApplicableEvenOnRecalculation() {
        StockIndustry bank = new StockIndustry();
        bank.setSourcePlateCode("US.LIST2481");
        StockIndustryHistory industry = new StockIndustryHistory();
        industry.setFigi("AAPL");
        industry.setIndustry(bank);
        when(industries.findByFigisAt(List.of("AAPL"), CLOSE)).thenReturn(List.of(industry));
        StockValuationDaily old = trustedRow();
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        StockValuationDaily row = saved.getFirst();
        assertThat(row.getPe()).isEqualByComparingTo("20");
        assertThat(row.getPfcf()).isNull();
        assertThat(row.getEvEbitda()).isNull();
        com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto dto =
                com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(row, null);
        assertThat(dto.status()).isEqualTo("AVAILABLE");
        assertThat(dto.metrics().pfcf().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(dto.metrics().evEbitda().status()).isEqualTo("NOT_APPLICABLE");
    }

    private StockValuationDaily trustedRow() {
        StockValuationDaily row = new StockValuationDaily();
        row.setFigi("AAPL");
        row.setValuationDate(DAY);
        row.setClosePrice(new BigDecimal("100"));
        row.setPe(new BigDecimal("20"));
        row.setPb(new BigDecimal("2"));
        row.setPs(BigDecimal.ONE);
        row.setPfcf(new BigDecimal("20"));
        row.setEvEbitda(new BigDecimal("12"));
        row.setSharesObservedAt(CLOSE.minusSeconds(1));
        row.setMarketCapSource("OBSERVED_SHARES");
        return row;
    }

    private List<SecFinancialFact> secFlows(String revenue, String cfo, String capex) {
        return List.of(secFlow("Revenues", revenue), secFlow("NetCashProvidedByUsedInOperatingActivities", cfo), secFlow("PaymentsToAcquireProductiveAssets", capex));
    }

    private SecFinancialFact secFlow(String tag, String value) {
        SecFinancialFact fact = new SecFinancialFact();
        fact.setCik("0000000001");
        fact.setTaxonomy("us-gaap");
        fact.setTag(tag);
        fact.setUnit("USD");
        fact.setPeriodEnd(DAY.minusDays(11));
        fact.setPeriodStart(fact.getPeriodEnd().minusYears(1).plusDays(1));
        fact.setValue(new BigDecimal(value));
        fact.setFiled(DAY.minusDays(2));
        fact.setAvailableFromDate(DAY.minusDays(1));
        fact.setObservedAt(NOW.minusSeconds(1));
        fact.setAccession("0000000001-26-000001");
        fact.setForm("10-K");
        return fact;
    }

    @ParameterizedTest
    @CsvSource({"100,0,true", "100,-1,true", "100,-60,true", "101,0,false", "100,32400,false"})
    void latestEstimateUsesOnlySameClosingPriceAndDateSnapshotMarketCap(String snapshotPrice, int quoteSeconds, boolean eligible) {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation observation = shareObservation(CLOSE.plusSeconds(10 * 3600));
        observation.setSnapshotQuoteTime(CLOSE.plusSeconds(quoteSeconds));
        observation.setSnapshotPrice(new BigDecimal(snapshotPrice));
        observation.setSnapshotMarketCap(new BigDecimal("1000"));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), eq(OPEN), eq(CLOSE), eq(NOW))).thenReturn(List.of(observation));
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(observation));

        task.execute(context(DAY));

        StockValuationDaily estimate = saved.getFirst();
        if (eligible) {
            assertThat(estimate.getPb()).isEqualByComparingTo("2");
            assertThat(estimate.getPs()).isEqualByComparingTo("1");
            assertThat(estimate.getPfcf()).isEqualByComparingTo("20");
            assertThat(estimate.getMarketCapSource()).isEqualTo("FUTU_CLOSE_SNAPSHOT");
        } else assertThat(estimate.getPb()).isNull();
        assertThat(estimate.getSharesObservedAt()).isEqualTo(eligible ? observation.getObservedAt() : null);
        assertThat(aapl.status().getSplitDate()).isEqualTo(DAY);
    }

    @Test
    void knownNextDaySplitDoesNotReadjustVendorSameCloseMarketCap() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation observation = shareObservation(CLOSE.plusSeconds(10 * 3600));
        observation.setSnapshotQuoteTime(CLOSE);
        observation.setSnapshotPrice(new BigDecimal("100"));
        observation.setSnapshotMarketCap(new BigDecimal("1000"));
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(observation));
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.plusDays(1));
        split.setSplitFrom(new BigDecimal("19"));
        split.setSplitTo(BigDecimal.ONE);
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));

        task.execute(context(DAY));

        assertThat(saved)
                .singleElement().satisfies(row -> {
                    assertThat(row.getPb()).isEqualByComparingTo("2");
                    assertThat(row.getMarketCapSource()).isEqualTo("FUTU_CLOSE_SNAPSHOT");
                });
        assertThat(aapl.status().getSplitDate()).isEqualTo(DAY);
    }

    @Test
    void intradayPriceMatchUsesObservedSharesInsteadOfSnapshotMarketCap() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation observation = shareObservation(CLOSE.minusSeconds(60));
        observation.setSnapshotQuoteTime(CLOSE.minusSeconds(60));
        observation.setSnapshotPrice(new BigDecimal("100"));
        observation.setSnapshotMarketCap(new BigDecimal("999999"));
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(observation));

        task.execute(context(DAY));

        assertThat(saved)
                .singleElement().satisfies(row -> {
                    assertThat(row.getPb()).isEqualByComparingTo("2");
                    assertThat(row.getMarketCapSource()).isEqualTo("OBSERVED_SHARES");
                });
    }

    @Test
    void currentSameCloseSnapshotTakesPriorityOverUsableEarlierShares() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation current = shareObservation(CLOSE.plusSeconds(10 * 3600));
        current.setIssuedShares(20);
        current.setSnapshotQuoteTime(CLOSE);
        current.setSnapshotPrice(new BigDecimal("100"));
        current.setSnapshotMarketCap(new BigDecimal("2000"));
        when(shareObservations.findLatestClosingSnapshotAt(anyCollection(), eq(OPEN), eq(CLOSE), eq(NOW))).thenReturn(List.of(current));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(shareObservation(CLOSE.plusSeconds(3600))));

        task.execute(context(DAY));

        assertThat(saved)
                .singleElement().satisfies(row -> {
                    assertThat(row.getPb()).isEqualByComparingTo("4");
                    assertThat(row.getSharesObservedAt()).isEqualTo(current.getObservedAt());
                    assertThat(row.getMarketCapSource()).isEqualTo("FUTU_CLOSE_SNAPSHOT");
                });
    }

    @Test
    void missingFinancialInputsDoNotRevokeSameCloseNativeMarketCapForHistoricalSplitCoverage() {
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setPb(new BigDecimal("2"));
        old.setSharesObservedAt(CLOSE.plusSeconds(10 * 3600));
        old.setMarketCapSource("FUTU_CLOSE_SNAPSHOT");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        StockShareObservation observation = shareObservation(old.getSharesObservedAt());
        observation.setSnapshotQuoteTime(CLOSE);
        observation.setSnapshotPrice(new BigDecimal("100"));
        observation.setSnapshotMarketCap(new BigDecimal("1000"));
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(observation));

        task.execute(context(DAY));

        assertThat(old.getPb()).isEqualByComparingTo("2");
        assertThat(old.getSharesObservedAt()).isEqualTo(observation.getObservedAt());
    }

    @Test
    void latestClosePersistsExactlyOneValuationForTheTradingDate() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation observation = shareObservation(CLOSE.plusSeconds(3600));
        when(shareObservations.findLatestAt(anyCollection(), any())).thenReturn(List.of(observation));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getValuationDate()).isEqualTo(DAY);
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getSharesObservedAt()).isEqualTo(observation.getObservedAt());
        });
    }

    @Test
    void firstRunCanUseSharesCollectedBeforeActualCalculationTime() {
        Instant started = CLOSE.plusSeconds(30);
        Instant observed = CLOSE.plusSeconds(60);
        Instant calculated = CLOSE.plusSeconds(90);
        when(clock.now()).thenReturn(started, observed, calculated);
        when(calendar.getLastClosedTradingDay(started)).thenReturn(DAY);
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of("AAPL"));
        when(session.shareCounts(List.of("AAPL"))).thenReturn(java.util.Map.of("AAPL", new FutuService.ShareSnapshot(10, null, null, null)));
        when(shareObservations.findLatestAt(anyCollection(), eq(calculated))).thenReturn(List.of(shareObservation(observed)));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(calculated))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);

        task.execute(context(null));

        StockValuationDaily estimate = saved.getFirst();
        assertThat(estimate.getPs()).isEqualByComparingTo("1");
        assertThat(estimate.getCalculatedAt()).isEqualTo(calculated);
        assertThat(estimate.getUpdatedAt()).isEqualTo(WRITE_TIME);
        assertThat(estimate.getSharesObservedAt()).isEqualTo(observed);
    }

    @Test
    void overnightCollectionChoosesRecentEarlierObservationWithCompleteSplitCoverage() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation earlier = shareObservation(CLOSE.plusSeconds(3600));
        StockShareObservation later = shareObservation(CLOSE.plusSeconds(9 * 3600));
        later.setIssuedShares(999);
        Instant coveredThrough = Instant.parse("2026-11-12T05:00:00Z").minusNanos(1000);
        when(shareObservations.findLatestAt(anyCollection(), eq(coveredThrough))).thenReturn(List.of(earlier));
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(later));

        task.execute(context(DAY));

        StockValuationDaily estimate = saved.getFirst();
        assertThat(estimate.getPb()).isEqualByComparingTo("2");
        assertThat(estimate.getPs()).isEqualByComparingTo("1");
        assertThat(estimate.getPfcf()).isEqualByComparingTo("20");
        assertThat(estimate.getSharesObservedAt()).isEqualTo(earlier.getObservedAt());
        assertThat(aapl.status().getSplitDate()).isEqualTo(DAY);
    }

    @Test
    void eligibleEarlierPitSharesAlsoRepairAnExistingUnavailableEstimate() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        StockShareObservation earlier = shareObservation(CLOSE.minusSeconds(60));
        Instant coveredThrough = Instant.parse("2026-11-12T05:00:00Z").minusNanos(1000);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(earlier));
        when(shareObservations.findLatestAt(anyCollection(), eq(coveredThrough))).thenReturn(List.of(earlier));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setPbReason("SPLIT_COVERAGE_INSUFFICIENT");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved)
                .singleElement().satisfies(row -> assertThat(row.getPb()).isEqualByComparingTo("2"));
    }

    @ParameterizedTest
    @CsvSource({"true,1", "false,0"})
    void lateSharesCrossingSplitAreConvertedBackOnlyWithCompleteCoverage(boolean covered, int expectedPb) {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(shareObservation(NOW.minusSeconds(60))));
        aapl.status().setSplitDate(covered ? DAY.plusDays(1) : DAY);
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.plusDays(1));
        split.setSourceEventId("NEXT-DAY-SPLIT");
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(new BigDecimal("2"));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));

        task.execute(context(DAY));

        StockValuationDaily estimate = saved.getFirst();
        if (covered) assertThat(estimate.getPb()).isEqualByComparingTo(String.valueOf(expectedPb));
        else {
            assertThat(estimate.getPb()).isNull();
            assertThat(estimate.getPbReason()).isEqualTo("SPLIT_COVERAGE_INSUFFICIENT");
        }
        assertThat(estimate.getPe()).isEqualByComparingTo("20");
    }

    @Test
    void newlyDiscoveredReverseSplitBoundaryRevokesOldEstimatedMarketCapEvenWithoutReports() {
        aapl.status().setValuationDate(DAY);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        aapl.status().setSplitDate(DAY.plusDays(1));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setPe(new BigDecimal("20"));
        old.setPb(new BigDecimal("2"));
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setSharesObservedAt(CLOSE.plusSeconds(9 * 3600));
        old.setCalculatedAt(CLOSE.plusSeconds(10 * 3600));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(shareObservations.findLatestAt(anyCollection(), eq(NOW))).thenReturn(List.of(shareObservation(old.getSharesObservedAt())));
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setSourceEventId("LATER-DISCOVERED");
        split.setExecutionDate(DAY.plusDays(1));
        split.setObservedAt(CLOSE.plusSeconds(11 * 3600));
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(new BigDecimal("2"));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));

        task.execute(context(DAY));

        assertThat(old.getPb()).isNull();
        assertThat(old.getPbReason()).isEqualTo("SHARE_SPLIT_BASIS_CHANGED");
        assertThat(old.getPe()).isEqualByComparingTo("20");
        assertThat(saved).contains(old);
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void historicalDateCannotLoadPresentSharesOrCreateLatestEstimate() {
        LocalDate historical = DAY.minusDays(1);
        Instant historicalClose = CLOSE.minusSeconds(86400);
        when(calendar.isTradingDay(historical)).thenReturn(true);
        when(calendar.getSessionCloseAt(historical)).thenReturn(historicalClose);
        StockQuote price = quote("AAPL");
        price.setQuoteDate(historical);
        when(quotes.findStoredQuotes(anyCollection(), eq(historical))).thenReturn(List.of(price));
        when(reports.findValuationReports(anyCollection(), eq(historical), eq(historicalClose)))
                .thenReturn(List.of(report("AAPL", 1, 7, DAY.minusMonths(2))));

        task.execute(context(historical));

        assertThat(saved).hasSize(1);
        verify(shareObservations, never()).findLatestAt(anyCollection(), eq(NOW));
        verifyNoInteractions(futu);
    }

    @Test
    void nonPositiveFcfRevokesStoredNegativeMultipleEvenWhenSharesAreTemporarilyMissing() {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport cashFlow = inputs.get(2);
        cashFlow.setItems(cashFlow.getItems().stream().map(item -> item.name().equals(FinancialConcept.FREE_CASH_FLOW.name())
                ? new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal("-50"), item.yearOverYearPercent(), item.quarterOverQuarterPercent()) : item).toList());
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setPb(new BigDecimal("2"));
        old.setPs(BigDecimal.ONE);
        old.setPfcf(new BigDecimal("-20"));
        old.setEvEbitda(new BigDecimal("12"));
        old.setSharesObservedAt(CLOSE.minusSeconds(60));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPfcf()).isNull();
            assertThat(row.getPfcfReason()).isEqualTo("NON_POSITIVE_FCF");
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getEvEbitda()).isEqualByComparingTo("12");
            assertThat(com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(row, null)
                    .metrics().pfcf().status()).isEqualTo("NOT_APPLICABLE");
        });
        assertThat(cashFlow.getItems()).anySatisfy(item -> {
            assertThat(item.name()).isEqualTo(FinancialConcept.FREE_CASH_FLOW.name());
            assertThat(item.value()).isEqualByComparingTo("-50");
        });
    }

    @ParameterizedTest
    @CsvSource({"-100", "0"})
    void nonPositiveFutuRevenueProducesNotApplicablePs(String revenue) {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport income = inputs.getFirst();
        income.setItems(income.getItems().stream().map(item -> item.name().equals(FinancialConcept.REVENUE_AS_REPORTED.name())
                ? new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal(revenue), item.yearOverYearPercent(), item.quarterOverQuarterPercent()) : item).toList());
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(60))));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPs()).isNull();
            assertThat(row.getPsReason()).isEqualTo("NON_POSITIVE_REVENUE");
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(row, null)
                    .metrics().ps().status()).isEqualTo("NOT_APPLICABLE");
        });
    }

    @Test
    void storedNegativePsCannotRemainAvailableWhenReportsDisappear() {
        aapl.status().setValuationDate(DAY);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setPb(new BigDecimal("2"));
        old.setPs(new BigDecimal("-3"));
        old.setPfcf(new BigDecimal("20"));
        old.setEvEbitda(new BigDecimal("12"));
        old.setSharesObservedAt(CLOSE.minusSeconds(60));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPs()).isNull();
            assertThat(row.getPsReason()).isEqualTo("NON_POSITIVE_REVENUE");
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
            assertThat(row.getEvEbitda()).isEqualByComparingTo("12");
        });
    }

    @Test
    void storedNonMeaningfulPfcfCannotRemainAvailableWhenReportsAlsoDisappear() {
        aapl.status().setValuationDate(DAY);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setPb(new BigDecimal("2"));
        old.setPs(BigDecimal.ONE);
        old.setPfcf(new BigDecimal("-20"));
        old.setEvEbitda(new BigDecimal("12"));
        old.setSharesObservedAt(CLOSE.minusSeconds(60));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPfcf()).isNull();
            assertThat(row.getPfcfReason()).isEqualTo("NON_POSITIVE_FCF");
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getEvEbitda()).isEqualByComparingTo("12");
        });
    }

    @Test
    void annualPeUsesPriceTradingDayAndAdvancesPartialWatermarkAtomically() {
        task.execute(context(null));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getValuationDate()).isEqualTo(DAY);
            assertThat(row.getCalculatedAt()).isEqualTo(NOW);
            assertThat(row.getFinancialObservedAt()).isBefore(CLOSE);
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isNull();
            assertThat(row.getPbReason()).isEqualTo("VALUATION_DATE_SHARES_MISSING");
            assertThat(row.getEvEbitdaReason()).isEqualTo("VALUATION_DATE_SHARES_MISSING");
        });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(aapl.status().getValuationMessage()).contains("VALUATION_DATE_SHARES_MISSING");
        org.mockito.InOrder order = inOrder(entityManager, statuses, manager);
        order.verify(statuses).findAllByIdForUpdate(anyCollection());
        order.verify(entityManager).flush();
        order.verify(statuses).saveAllAndFlush(anyList());
        order.verify(manager).commit(any());
        verify(reports).findValuationReports(List.of("AAPL"), DAY, NOW);
    }

    @Test
    void latestQuarterCannotFallBackToOlderAnnualEps() {
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(
                report("AAPL", 1, 3, DAY.minusMonths(1)), report("AAPL", 1, 7, DAY.minusMonths(10))));
        task.execute(context(null));
        assertThat(saved.getFirst().getPeReason()).isEqualTo("TTM_QUARTERS_MISSING");
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void noEligibleVersionSavesExplicitReasonsWithoutWatermark() {
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(null));
        assertThat(saved.getFirst().getPeReason()).isEqualTo("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void knownPendingDoesNotPausePe() {
        FinancialReport report = report("AAPL", 1, 7, DAY.minusMonths(2));
        report.setItems(List.of(report.getItems().getFirst(), new FinancialReport.Item(99, "Known pending", BigDecimal.ONE, null, null)));
        when(mappings.findAll()).thenReturn(List.of(mapping("Diluted EPS", FinancialItemMappingStatus.MAPPED), mapping("Known pending", FinancialItemMappingStatus.PENDING)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(report));
        task.execute(context(null));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
    }

    @Test
    void newNameOnBalanceSheetWarnsWithoutBlockingKnownPeOrOtherStocks() {
        ActiveStockWithStatus jpm = target("JPM");
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(List.of(aapl, jpm));
        when(statuses.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(aapl.status(), jpm.status()));
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenReturn(List.of(quote("AAPL"), quote("JPM")));
        FinancialReport balance = report("AAPL", 2, 7, DAY.minusMonths(2));
        balance.setItems(List.of(new FinancialReport.Item(888, "New balance field", BigDecimal.ONE, null, null)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(
                report("AAPL", 1, 7, DAY.minusMonths(2)), balance, report("JPM", 1, 7, DAY.minusMonths(2))));
        task.execute(context(null));
        assertThat(saved).filteredOn(StockValuationDaily::hasAvailableMetric).extracting(StockValuationDaily::getFigi).containsExactly("AAPL", "JPM");
        assertThat(saved).filteredOn(row -> row.getFigi().equals("AAPL")).singleElement()
                .satisfies(row -> {
                    assertThat(row.getPe()).isEqualByComparingTo("20");
                    assertThat(row.getPb()).isNull();
                });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(aapl.status().getValuationMessage()).contains("UNMAPPED_FINANCIAL_ITEM", "New balance field");
        assertThat(jpm.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void splitCoverageAndKnownPostPeriodSplitDoNotGuessEpsBasis() {
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.minusDays(1));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        task.execute(context(null));
        assertThat(saved.getFirst().getPeReason()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED");
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void missingSplitCoverageRemainsInsufficient() {
        aapl.status().setSplitDate(DAY.minusDays(1));
        task.execute(context(null));
        assertThat(saved.getFirst().getPeReason()).isEqualTo("SPLIT_COVERAGE_INSUFFICIENT");
    }

    @Test
    void splitErrorDoesNotTrustOldCoverageDatesOrAdvanceWatermark() {
        aapl.status().setSplitMessage("ERROR:SPLIT_RATIO_INVALID:2026-10-01");
        task.execute(context(null));
        assertThat(saved.getFirst().getPe()).isNull();
        assertThat(saved.getFirst().getPeReason()).isEqualTo("SPLIT_SYNC_ERROR");
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void aConfirmedNewSplitInvalidatesOldPeInsteadOfKeepingItAvailable() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setPe(BigDecimal.TEN);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        aapl.status().setValuationDate(DAY);
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.minusDays(1));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNull();
            assertThat(row.getPeReason()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED");
        });
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void laterSuccessfulDayAdvancesWatermarkWithoutRetryingTheEarlierGap() {
        ReflectionTestUtils.setField(task, "historyStartDate", DAY.minusDays(2));
        aapl.status().setValuationDate(DAY.minusDays(2));
        when(calendar.isTradingDay(DAY.minusDays(1))).thenReturn(true);
        when(calendar.getSessionCloseAt(DAY.minusDays(1))).thenReturn(CLOSE.minusSeconds(86400));
        task.execute(context(null));
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(saved).extracting(StockValuationDaily::getValuationDate).containsExactly(DAY.minusDays(1), DAY);
        assertThat(saved.getFirst().getPeReason()).isEqualTo("CLOSING_PRICE_MISSING");

        saved.clear();
        task.execute(context(null));

        assertThat(saved).isEmpty();
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void dailyRunRecalculatesExistingRowAfterWatermarkAndAdvancesOnSuccess() {
        aapl.status().setValuationDate(DAY.minusDays(1));
        StockValuationDaily previous = new StockValuationDaily();
        previous.setFigi("AAPL");
        previous.setValuationDate(DAY);
        previous.setPe(BigDecimal.TEN);
        when(valuations.findByFigiInAndValuationDate(List.of("AAPL"), DAY)).thenReturn(List.of(previous));
        task.execute(context(null));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getValuationDate()).isEqualTo(DAY);
            assertThat(row.getPe()).isEqualByComparingTo("20");
        });
        verify(entityManager).merge(saved.getFirst());
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void successfulBacklogProcessesEachTradingDayInOrder() {
        ReflectionTestUtils.setField(task, "historyStartDate", DAY.minusDays(2));
        aapl.status().setValuationDate(DAY.minusDays(2));
        when(calendar.isTradingDay(DAY.minusDays(1))).thenReturn(true);
        when(calendar.getSessionCloseAt(DAY.minusDays(1))).thenReturn(CLOSE.minusSeconds(86400));
        FinancialReport report = report("AAPL", 1, 7, DAY.minusMonths(2));
        report.setObservedAt(CLOSE.minusSeconds(2 * 86400));
        when(reports.findValuationReports(anyCollection(), any(), any())).thenReturn(List.of(report));
        when(quotes.findStoredQuotes(anyCollection(), any(LocalDate.class))).thenAnswer(call -> {
            StockQuote quote = quote("AAPL");
            quote.setQuoteDate(call.getArgument(1));
            return List.of(quote);
        });
        task.execute(context(null));
        assertThat(saved).extracting(StockValuationDaily::getValuationDate).containsExactly(DAY.minusDays(1), DAY);
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        org.mockito.InOrder order = inOrder(reports);
        order.verify(reports).findValuationReports(List.of("AAPL"), DAY.minusDays(1), NOW);
        order.verify(reports).findValuationReports(List.of("AAPL"), DAY, NOW);
    }

    @Test
    void explicitLaterDateUpdatesLatestSuccessWhileFailuresAreIndependentlyRetried() {
        aapl.status().setValuationDate(DAY.minusDays(2));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getPe()).isEqualByComparingTo("20"));
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        verify(valuations, never()).findLatestAvailableDates(anyCollection(), any(), any());
    }

    @Test
    void anErrorAlreadyScopedAfterTheValuationDayDoesNotInvalidateEarlierCoverage() {
        aapl.status().setSplitMessage("ERROR:SPLIT_STORED_EVENT_NOT_RETURNED:2026-11-12:dates=1");
        task.execute(context(null));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
    }

    @Test
    void invalidatingAnOlderDateDoesNotRetractANewerSuccessfulDate() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setPe(BigDecimal.TEN);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        aapl.status().setValuationDate(DAY.plusDays(1));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        FinancialReport report = report("AAPL", 1, 7, DAY.minusMonths(2));
        report.setItems(List.of(new FinancialReport.Item(1, "Diluted EPS", new BigDecimal("-5"), null, null)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(report));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNull();
            assertThat(row.getPeReason()).isEqualTo("NON_POSITIVE_EPS");
        });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY.plusDays(1));
    }

    @Test
    void temporarilyOverwrittenVersionsRetainThePriorQualifiedWholeResult() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setPe(BigDecimal.TEN);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setFinancialObservedAt(CLOSE.minusSeconds(60));
        aapl.status().setValuationDate(DAY);
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(saved).isEmpty();
        assertThat(old.getPe()).isEqualTo(BigDecimal.TEN);
        assertThat(old.getFinancialObservedAt()).isEqualTo(CLOSE.minusSeconds(60));
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(aapl.status().getValuationMessage()).contains("retained previous result");
    }

    @Test
    void temporarilyMissingReportCannotHideAnIndependentlyKnownSplitFailure() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setPe(BigDecimal.TEN);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        aapl.status().setValuationDate(DAY);
        aapl.status().setSplitMessage("ERROR:SPLIT_RATIO_INVALID:2026-10-01");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNull();
            assertThat(row.getPeReason()).isEqualTo("SPLIT_SYNC_ERROR");
        });
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void temporarilyMissingReportCannotHideANewSplitAfterTheOldIncomePeriod() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(BigDecimal.TEN);
        StockSplit split = new StockSplit();
        split.setExecutionDate(DAY.minusDays(1));
        split.setFigi("AAPL");
        aapl.status().setValuationDate(DAY);
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getPeReason()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED"));
        assertThat(old.getPe()).isNull();
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void aQualifiedSavedResultCanAdvanceLatestDateWhenItsSourceIsTemporarilyMissing() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setFinancialObservedAt(CLOSE.minusSeconds(60));
        old.setPe(BigDecimal.TEN);
        aapl.status().setValuationDate(DAY.minusDays(1));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(null));
        assertThat(saved).isEmpty();
        assertThat(old.getPe()).isEqualTo(BigDecimal.TEN);
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void newerEligibleQuarterDoesNotKeepAnOlderAnnualPeForTheSamePriceDate() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(10));
        old.setPe(BigDecimal.TEN);
        aapl.status().setValuationDate(DAY);
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(report("AAPL", 1, 3, DAY.minusMonths(1))));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isNull();
            assertThat(row.getPeReason()).isEqualTo("TTM_QUARTERS_MISSING");
        });
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void earlierManualSuccessOutsideConfigurationDoesNotAffectTheLatestPointer() {
        ReflectionTestUtils.setField(task, "historyStartDate", DAY.minusDays(2));
        LocalDate earlier = DAY.minusDays(6);
        aapl.status().setValuationDate(DAY);
        when(calendar.isTradingDay(earlier)).thenReturn(true);
        when(calendar.getSessionCloseAt(earlier)).thenReturn(CLOSE.minusSeconds(6 * 86400));
        FinancialReport historical = report("AAPL", 1, 7, DAY.minusMonths(2));
        historical.setObservedAt(CLOSE.minusSeconds(7 * 86400));
        when(reports.findValuationReports(anyCollection(), eq(earlier), any())).thenReturn(List.of(historical));
        StockQuote earlierQuote = quote("AAPL");
        earlierQuote.setQuoteDate(earlier);
        when(quotes.findStoredQuotes(anyCollection(), eq(earlier))).thenReturn(List.of(earlierQuote));
        task.execute(context(earlier));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getValuationDate()).isEqualTo(earlier);
            assertThat(row.getPe()).isEqualByComparingTo("20");
        });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);

        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(BigDecimal.TEN);
        FinancialReport corrected = report("AAPL", 1, 7, DAY.minusMonths(2));
        corrected.setItems(List.of(new FinancialReport.Item(1, "Diluted EPS", new BigDecimal("-1"), null, null)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(corrected));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        task.execute(context(DAY));
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void olderReplayRepairsAnAlreadyOverwrittenSummaryFromTheWatermarkRow() {
        LocalDate older = DAY.minusDays(2);
        aapl.status().setValuationDate(DAY);
        aapl.status().setValuationMessage(older + ":PE=CLOSING_PRICE_MISSING");
        StockValuationDaily latest = new StockValuationDaily();
        latest.setFigi("AAPL");
        latest.setValuationDate(DAY);
        latest.setPe(BigDecimal.TEN);
        when(valuations.findByFigiInAndValuationDate(List.of("AAPL"), DAY)).thenReturn(List.of(latest));
        when(calendar.isTradingDay(older)).thenReturn(true);
        when(calendar.getSessionCloseAt(older)).thenReturn(CLOSE.minusSeconds(2 * 86400));

        task.execute(context(older));

        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getPeReason()).isEqualTo("CLOSING_PRICE_MISSING"));
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(aapl.status().getValuationMessage()).startsWith(DAY + ":PE=AVAILABLE");
    }

    @Test
    void olderSuccessfulReplayKeepsTheNewerFailedAttemptSummary() {
        LocalDate older = DAY.minusDays(2);
        ReflectionTestUtils.setField(task, "historyStartDate", older);
        aapl.status().setValuationDate(older.minusDays(1));
        aapl.status().setValuationMessage(DAY + ":PE=CLOSING_PRICE_MISSING");
        StockQuote oldQuote = quote("AAPL");
        oldQuote.setQuoteDate(older);
        Instant close = CLOSE.minusSeconds(2 * 86400);
        when(quotes.findStoredQuotes(anyCollection(), eq(older))).thenReturn(List.of(oldQuote));
        when(reports.findValuationReports(anyCollection(), eq(older), eq(NOW)))
                .thenReturn(List.of(report("AAPL", 1, 7, older.minusMonths(2))));
        when(calendar.isTradingDay(older)).thenReturn(true);
        when(calendar.getSessionCloseAt(older)).thenReturn(close);

        task.execute(context(older));

        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getPe()).isNotNull());
        assertThat(aapl.status().getValuationDate()).isEqualTo(older);
        assertThat(aapl.status().getValuationMessage()).isEqualTo(DAY + ":PE=CLOSING_PRICE_MISSING");
    }

    @Test
    void earlierManualFailureDoesNotChangeTheEstablishedRangeOrWatermark() {
        ReflectionTestUtils.setField(task, "historyStartDate", DAY.minusDays(2));
        LocalDate earlier = DAY.minusDays(6);
        aapl.status().setValuationDate(DAY);
        aapl.status().setValuationMessage(DAY + ":PE=AVAILABLE");
        when(calendar.isTradingDay(earlier)).thenReturn(true);
        when(calendar.getSessionCloseAt(earlier)).thenReturn(CLOSE.minusSeconds(6 * 86400));
        task.execute(context(earlier));
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getPeReason()).isEqualTo("CLOSING_PRICE_MISSING"));
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
        assertThat(aapl.status().getValuationMessage()).isEqualTo(DAY + ":PE=AVAILABLE");
    }

    @Test
    void missingPriceDoesNotCreateAnEstimateOrAdvanceWatermark() {
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenReturn(List.of());
        task.execute(context(null));
        assertThat(saved).singleElement().satisfies(row -> assertThat(row.getPeReason()).isEqualTo("CLOSING_PRICE_MISSING"));
        assertThat(aapl.status().getValuationDate()).isNull();
        assertThat(aapl.status().getValuationMessage()).contains("CLOSING_PRICE_MISSING");
    }

    @Test
    void dailyRunDoesNotRecalculateTheLatestDayWhenTheWatermarkHasCompleted() {
        aapl.status().setValuationDate(DAY);
        task.execute(context(null));

        assertThat(saved).isEmpty();
        verify(quotes, never()).findStoredQuotes(anyCollection(), any(LocalDate.class));
        verify(futu, never()).openObservationSession();
        verify(statuses, never()).saveAllAndFlush(anyList());
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void valuationWriteFailureRollsBackWithoutPublishingStatus() {
        doThrow(new IllegalStateException("write failed")).when(entityManager).flush();
        assertThatThrownBy(() -> task.execute(context(null))).hasMessage("write failed");
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        verify(statuses, never()).saveAllAndFlush(anyList());
    }

    @Test
    void explicitRerunRetainsWholeOldResultIfInputsDisappearAndNeverRegressesWatermark() {
        StockValuationDaily previous = new StockValuationDaily();
        previous.setFigi("AAPL");
        previous.setValuationDate(DAY);
        previous.setIncomePeriodEnd(DAY.minusMonths(2));
        previous.setPe(BigDecimal.TEN);
        previous.setPb(BigDecimal.ONE);
        aapl.status().setValuationDate(DAY.plusDays(1));
        aapl.status().setValuationMessage(DAY.plusDays(1) + ":PE=AVAILABLE");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(previous));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(saved).isEmpty();
        assertThat(previous.getPe()).isEqualTo(BigDecimal.TEN);
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY.plusDays(1));
        assertThat(aapl.status().getValuationMessage()).isEqualTo(DAY.plusDays(1) + ":PE=AVAILABLE");
    }

    @Test
    void futureOrNonSessionDateIsRejectedBeforeReadingTargets() {
        when(calendar.getSessionCloseAt(DAY)).thenReturn(NOW.plusSeconds(1));
        assertThatThrownBy(() -> task.execute(context(DAY))).hasMessage("VALUATION_DATE_NOT_CLOSED_SESSION");
        verifyNoInteractions(stocks, reports, valuations);
    }

    @Test
    void moreThanOneHundredStocksUseBoundedBatchReadsWithoutPerStockQueries() {
        List<ActiveStockWithStatus> targets = new ArrayList<>();
        for (int index = 0; index < 101; index++) targets.add(target("F" + index));
        when(stocks.findValuationStocks(ActiveStatus.ACTIVE)).thenReturn(targets);
        when(statuses.findAllByIdForUpdate(anyCollection())).thenAnswer(call -> {
            java.util.Collection<String> figis = call.getArgument(0);
            return targets.stream().filter(row -> figis.contains(row.stock().getFigi())).map(ActiveStockWithStatus::status).toList();
        });
        when(quotes.findStoredQuotes(anyCollection(), eq(DAY))).thenAnswer(call -> {
            java.util.Collection<String> figis = call.getArgument(0);
            return figis.stream().map(this::quote).toList();
        });
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(null));
        assertThat(saved).hasSize(101);
        verify(reports, times(2)).findValuationReports(argThat(figis -> figis.size() <= 100), eq(DAY), eq(NOW));
        verify(quotes, times(2)).findStoredQuotes(anyCollection(), eq(DAY));
        verify(quotes).findFirstQuoteDates(argThat(figis -> figis.size() == 101));
        verify(splits, times(2)).findByFigiInOrderByExecutionDateAsc(anyCollection());
        verify(mappings).findAll();
        verifyNoMoreInteractions(reports, quotes, splits, mappings);
    }

    @Test
    void storedEligibleSharesAndMappedFinancialAmountsProduceFiveMultiples() {
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(60))));
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> financials = allInputs(catalog);
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(financials);

        task.execute(context(DAY));

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
            assertThat(row.getEvEbitda()).isEqualByComparingTo("12");
            assertThat(row.getSharesObservedAt()).isBefore(CLOSE);
        });
        verifyNoInteractions(futu);
    }

    @Test
    void todayShareCaptureIsStoredAtActualTimeAndCannotSupplyYesterdayValuation() {
        when(session.supportedUsStocks()).thenReturn(java.util.Set.of("AAPL"));
        when(session.shareCounts(List.of("AAPL"))).thenReturn(java.util.Map.of("AAPL", new FutuService.ShareSnapshot(10, null, null, null)));
        task.execute(context(null));
        org.mockito.ArgumentCaptor<StockShareObservation> observation = org.mockito.ArgumentCaptor.forClass(StockShareObservation.class);
        verify(entityManager).persist(observation.capture());
        assertThat(observation.getValue().getObservedAt()).isEqualTo(NOW);
        assertThat(observation.getValue().getIssuedShares()).isEqualTo(10);
        assertThat(saved.getFirst().getPsReason()).isEqualTo("VALUATION_DATE_SHARES_MISSING");
        verify(shareObservations).findLatestAt(List.of("AAPL"), CLOSE);
        verify(session).close();
    }

    @Test
    void shareDownloadFailureStillSavesIndependentPeAndReportsPartialJobFailure() {
        when(futu.openObservationSession()).thenThrow(new IllegalStateException("provider unavailable"));
        assertThatThrownBy(() -> task.execute(context(null))).hasMessage("SHARE_OBSERVATION_SYNC_FAILED").hasRootCauseMessage("provider unavailable");
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void splitFailureRevokesAllOldShareDependentRatiosEvenWithoutAnOldPe() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setSharesObservedAt(CLOSE.minusSeconds(86400));
        old.setPb(BigDecimal.ONE);
        old.setPs(BigDecimal.TEN);
        old.setPfcf(BigDecimal.TEN);
        old.setEvEbitda(BigDecimal.TEN);
        aapl.status().setValuationDate(DAY);
        aapl.status().setSplitMessage("ERROR:DOWNLOAD_FAILED");
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.hasAvailableMetric()).isFalse();
            assertThat(row.getPbReason()).isEqualTo("SPLIT_SYNC_ERROR");
            assertThat(row.getPsReason()).isEqualTo("SPLIT_SYNC_ERROR");
            assertThat(row.getPfcfReason()).isEqualTo("SPLIT_SYNC_ERROR");
            assertThat(row.getEvEbitdaReason()).isEqualTo("SPLIT_SYNC_ERROR");
        });
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void retractingLatestDateUsesActualSavedMaximumRatherThanInventedPreviousSession() {
        ReflectionTestUtils.setField(task, "historyStartDate", DAY.minusDays(2));
        aapl.status().setValuationDate(DAY);
        FinancialReport corrected = report("AAPL", 1, 7, DAY.minusMonths(2));
        corrected.setItems(List.of(new FinancialReport.Item(1, "Diluted EPS", BigDecimal.ZERO, null, null)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of(corrected));
        when(valuations.findLatestAvailableDates(anyCollection(), eq(DAY.minusDays(2)), eq(DAY)))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"AAPL", DAY.minusDays(2)}));
        task.execute(context(DAY));
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY.minusDays(2));
    }

    @Test
    void eligibleBalanceSheetCanCalculatePbWithoutAnyIncomeReport() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> financials = allInputs(catalog).stream().filter(row -> row.getStatementType() == 2).toList();
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(financials);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(60))));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPeReason()).isEqualTo("NO_FINANCIAL_VERSION_KNOWN_AT_CLOSE");
        assertThat(saved.getFirst().getPb()).isEqualByComparingTo("2");
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void knownSplitAdjustsObservedSharesButDoesNotGuessReportedEpsBasis() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(allInputs(catalog));
        when(mappings.findAll()).thenReturn(catalog);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(86400))));
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setSourceEventId("FUTU:split");
        split.setExecutionDate(DAY);
        split.setSplitFrom(BigDecimal.ONE);
        split.setSplitTo(new BigDecimal("2"));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPeReason()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED");
        assertThat(saved.getFirst().getPs()).isEqualByComparingTo("2");
    }

    @Test
    void quarterlyEpsUsesTheOldestOfFourQuarterEndsForSplitCoverage() {
        List<FinancialReport> quarters = new ArrayList<>();
        for (int index = 0; index < 4; index++) quarters.add(report("AAPL", 1, 4 - index, DAY.minusMonths(2 + index * 3)));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(quarters);
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("5");
        assertThat(saved.getFirst().getEpsBasisFromDate()).isEqualTo(DAY.minusMonths(11));
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.minusMonths(6));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        task.execute(context(DAY));
        assertThat(saved.getLast().getPeReason()).isEqualTo("EPS_SPLIT_BASIS_UNCONFIRMED");
    }

    @Test
    void changedQualifiedPeCannotBeHiddenByRetainingOldPbWhenItsInputsDisappear() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(BigDecimal.TEN);
        old.setPb(BigDecimal.ONE);
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isNull();
        });
    }

    @Test
    void knownAdditionalFinancingRevokesOldEvButKeepsIndependentPeQualified() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport balance = inputs.get(1);
        List<FinancialReport.Item> items = new ArrayList<>(balance.getItems());
        items.add(input(catalog, 2, FinancialConcept.SECURITIES_LENDING_AND_REPO_PAYABLES_AS_REPORTED, "6500"));
        balance.setItems(items);
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(60))));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setEvEbitda(new BigDecimal("12"));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        task.execute(context(DAY));
        assertThat(saved.getFirst().getPe()).isEqualByComparingTo("20");
        assertThat(saved.getFirst().getEvEbitda()).isNull();
        assertThat(saved.getFirst().getEvEbitdaReason()).isEqualTo("EV_FINANCING_SCOPE_UNRESOLVED");
    }

    @Test
    void pendingRedeemableSecurityRevokesOnlyEvAndSavesFourIndependentRatios() {
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport balance = inputs.get(1);
        List<FinancialReport.Item> items = new ArrayList<>(balance.getItems());
        items.add(new FinancialReport.Item(8077, "Convertible preferred securities", new BigDecimal("109453000"), null, null));
        balance.setItems(items);
        FinancialReportItemMapping pending = mapping("Convertible preferred securities", FinancialItemMappingStatus.PENDING);
        pending.setStatementType(2);
        catalog.add(pending);
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(List.of(shareObservation(CLOSE.minusSeconds(60))));
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setEvEbitda(new BigDecimal("12"));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
            assertThat(row.getEvEbitda()).isNull();
            assertThat(row.getEvEbitdaReason()).isEqualTo("EV_FINANCING_SCOPE_UNRESOLVED");
        });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @ParameterizedTest
    @CsvSource({"LEASE,true,EV_LEASE_SCOPE_UNRESOLVED", "LEASE,false,EV_LEASE_SCOPE_UNRESOLVED",
            "ACCOUNTING,true,EV_ACCOUNTING_STANDARD_UNCONFIRMED", "ACCOUNTING,false,EV_ACCOUNTING_STANDARD_UNCONFIRMED"})
    void correctingEvScopeRevokesOldEvWithoutDiscardingOtherFourMultiples(String scenario, boolean sharesAvailable, String expectedReason) {
        aapl.status().setValuationDate(DAY);
        List<FinancialReportItemMapping> catalog = new ArrayList<>();
        List<FinancialReport> inputs = allInputs(catalog);
        FinancialReport balance = inputs.get(1);
        if (scenario.equals("LEASE")) {
            List<FinancialReport.Item> items = balance.getItems().stream().map(item -> {
                if (item.name().equals(FinancialConcept.CURRENT_CAPITAL_LEASES.name())) return new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal("20"), null, null);
                if (item.name().equals(FinancialConcept.LONG_TERM_CAPITAL_LEASES.name())) return new FinancialReport.Item(item.fieldId(), item.name(), new BigDecimal("30"), null, null);
                return item;
            }).toList();
            balance.setItems(items);
        } else balance.setAccountingStandards(null);
        when(mappings.findAll()).thenReturn(catalog);
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(inputs);
        when(shareObservations.findLatestAt(anyCollection(), eq(CLOSE))).thenReturn(sharesAvailable ? List.of(shareObservation(CLOSE.minusSeconds(60))) : List.of());
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setIncomePeriodEnd(DAY.minusMonths(2));
        old.setPe(new BigDecimal("20"));
        old.setPb(new BigDecimal("2"));
        old.setPs(BigDecimal.ONE);
        old.setPfcf(new BigDecimal("20"));
        old.setSharesObservedAt(CLOSE.minusSeconds(60));
        old.setEvEbitda(new BigDecimal("12"));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        task.execute(context(DAY));
        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.getPe()).isEqualByComparingTo("20");
            assertThat(row.getPb()).isEqualByComparingTo("2");
            assertThat(row.getPs()).isEqualByComparingTo("1");
            assertThat(row.getPfcf()).isEqualByComparingTo("20");
            assertThat(row.getEvEbitda()).isNull();
            assertThat(row.getEvEbitdaReason()).isEqualTo(expectedReason);
            assertThat(com.ykm.stock.analytics.model.dto.stockInsight.analysis.ValuationResultDto.fromCalculated(row, null)
                    .metrics().evEbitda().status()).isEqualTo("UNVERIFIED");
        });
        assertThat(aapl.status().getValuationDate()).isEqualTo(DAY);
    }

    @Test
    void newlyObservedHistoricalSplitRevokesOldMarketCapRatiosEvenWhenFinancialReportsDisappear() {
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setSharesObservedAt(CLOSE.minusSeconds(10 * 86400));
        old.setCalculatedAt(CLOSE.plusSeconds(3600));
        old.setPb(new BigDecimal("3"));
        aapl.status().setValuationDate(DAY);
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.minusDays(2));
        split.setObservedAt(NOW.minusSeconds(60));
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(old.getPb()).isNull();
        assertThat(saved.getFirst().getPbReason()).isEqualTo("SHARE_SPLIT_BASIS_CHANGED");
        assertThat(aapl.status().getValuationDate()).isNull();
    }

    @Test
    void anUnchangedSplitAlreadyKnownAtCalculationDoesNotInvalidateQualifiedOldShares() {
        aapl.status().setValuationDate(DAY);
        StockValuationDaily old = new StockValuationDaily();
        old.setFigi("AAPL");
        old.setValuationDate(DAY);
        old.setSharesObservedAt(CLOSE.minusSeconds(10 * 86400));
        old.setCalculatedAt(CLOSE.plusSeconds(3600));
        old.setPb(new BigDecimal("3"));
        StockSplit split = new StockSplit();
        split.setFigi("AAPL");
        split.setExecutionDate(DAY.minusDays(2));
        split.setObservedAt(CLOSE);
        when(splits.findByFigiInOrderByExecutionDateAsc(anyCollection())).thenReturn(List.of(split));
        when(valuations.findByFigiInAndValuationDate(anyCollection(), eq(DAY))).thenReturn(List.of(old));
        when(reports.findValuationReports(anyCollection(), eq(DAY), eq(NOW))).thenReturn(List.of());
        task.execute(context(DAY));
        assertThat(old.getPb()).isEqualByComparingTo("3");
        assertThat(saved).isEmpty();
    }

    private StockShareObservation shareObservation(Instant time) {
        StockShareObservation observation = new StockShareObservation();
        observation.setFigi("AAPL");
        observation.setObservedAt(time);
        observation.setIssuedShares(10);
        observation.setSource("FUTU");
        return observation;
    }

    private List<FinancialReport> allInputs(List<FinancialReportItemMapping> catalog) {
        FinancialReport income = report("AAPL", 1, 7, DAY.minusMonths(2));
        FinancialReport balance = report("AAPL", 2, 7, DAY.minusMonths(2));
        FinancialReport cash = report("AAPL", 3, 7, DAY.minusMonths(2));
        income.setItems(List.of(input(catalog, 1, FinancialConcept.DILUTED_EPS, "5"), input(catalog, 1, FinancialConcept.REVENUE_AS_REPORTED, "1000"),
                input(catalog, 1, FinancialConcept.OPERATING_PROFIT, "80"), input(catalog, 1, FinancialConcept.INCOME_DEPRECIATION_AMORTIZATION, "20")));
        balance.setItems(List.of(input(catalog, 2, FinancialConcept.STOCKHOLDERS_EQUITY, "500"), input(catalog, 2, FinancialConcept.PREFERRED_STOCK_CAPITAL, "0"),
                input(catalog, 2, FinancialConcept.NONCONTROLLING_INTEREST_EQUITY, "0"), input(catalog, 2, FinancialConcept.CURRENT_DEBT_AND_LEASES, "100"),
                input(catalog, 2, FinancialConcept.CURRENT_CAPITAL_LEASES, "0"), input(catalog, 2, FinancialConcept.LONG_TERM_CAPITAL_LEASES, "0"),
                input(catalog, 2, FinancialConcept.LONG_TERM_DEBT_AND_LEASES, "150"), input(catalog, 2, FinancialConcept.CASH_AND_EQUIVALENTS, "50")));
        cash.setItems(List.of(input(catalog, 3, FinancialConcept.FREE_CASH_FLOW, "50")));
        return List.of(income, balance, cash);
    }

    private FinancialReport.Item input(List<FinancialReportItemMapping> catalog, int type, FinancialConcept concept, String value) {
        FinancialReportItemMapping mapping = new FinancialReportItemMapping();
        mapping.setStatementType(type);
        mapping.setItemName(concept.name());
        mapping.setFinancialConcept(concept);
        mapping.setMappingStatus(FinancialItemMappingStatus.MAPPED);
        catalog.add(mapping);
        return new FinancialReport.Item(catalog.size(), concept.name(), new BigDecimal(value), null, null);
    }
    private JobExecutionContext context(LocalDate day) {
        JobDataMap data = new JobDataMap();
        if (day != null) data.put("valuationDate", day.toString());
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(data);
        return context;
    }

    private ActiveStockWithStatus target(String figi) {
        Stock stock = new Stock();
        stock.setFigi(figi);
        stock.setCurrency("USD");
        stock.setSymbol(figi);
        stock.setStockType("Common Stock");
        StockStatus status = new StockStatus();
        status.setFigi(figi);
        status.setQuoteDate(DAY);
        status.setSplitFromDate(LocalDate.of(2016, 1, 1));
        status.setSplitDate(DAY);
        return new ActiveStockWithStatus(stock, status);
    }

    private StockQuote quote(String figi) {
        StockQuote quote = new StockQuote();
        quote.setFigi(figi);
        quote.setQuoteDate(DAY);
        quote.setClosePrice(new BigDecimal("100"));
        return quote;
    }

    private FinancialReport report(String figi, int type, int periodType, LocalDate end) {
        FinancialReport report = new FinancialReport();
        report.setFigi(figi);
        report.setStatementType(type);
        report.setPeriodType(periodType);
        report.setPeriodEnd(end);
        report.setFiscalYear(2026);
        report.setCurrency("USD");
        report.setAccountingStandards("US_GAAP");
        report.setObservedAt(CLOSE.minusSeconds(60));
        report.setItems(List.of(new FinancialReport.Item(1, "Diluted EPS", new BigDecimal("5"), null, null)));
        return report;
    }

    private FinancialReportItemMapping mapping(String name, FinancialItemMappingStatus status) {
        FinancialReportItemMapping mapping = new FinancialReportItemMapping();
        mapping.setStatementType(1);
        mapping.setItemName(name);
        mapping.setMappingStatus(status);
        if (status == FinancialItemMappingStatus.MAPPED) mapping.setFinancialConcept(FinancialConcept.DILUTED_EPS);
        return mapping;
    }
}
